//! Mimir chats. Mimir owns execution and the saved conversation; this module
//! owns each chat's native attachment and view, projects the host's saved and
//! live content into the chat doc, maps engine messages onto host
//! submissions, and executes typed controls. Chats never run `drive_run`.
//!
//! Identities stay distinct: the bridge process ([`Runtime`]), the native
//! conversation (`NativeChatState::conversation`, also the chat row's harness
//! session), the attachment held in this process, host requests (admitted
//! under the chat message id as submission key), view positions, journal
//! entries (`m-<entry id>` in the doc), tool invocations (tool part ids) and
//! child handles (one `{chat}--sub--<handle>` doc across attempts).
//!
//! Client disconnects never touch any of this: views are engine-owned.

mod journal;
mod projection;

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine as _;
use tokio::sync::{mpsc, watch};

use roboco_doc::{MessagePart, MessageRole};
use roboco_harness::Harness;
use roboco_harness::mimir::protocol::{
    Attached, BridgeError, ChildInfo, CommandDescriptor, CommandResult, ConfigurationChange,
    Delivery, ErrorKind, Event, JournalRef, Mode, PromptImage, PromptInput, SkillSelection,
    TextRange, ViewChild, ViewItem, ViewNote, ViewStart, ViewState,
};
use roboco_harness::mimir::{Failure, Runtime};
use roboco_proto::{
    HarnessId, NativeChatCatalog, NativeChatState, NativeChild, NativeChildAttempt,
    NativeChildOutcome, NativeControl, NativeControlOutcome, NativeDelivery, NativeErrorKind,
    NativeLink, NativeNotice, NativePlanArtifact, NativeReadiness, NativeSubmission,
    NativeSubmissionKind, RunRequest, SessionStatus,
};

use self::journal::{ChildLink, Echoes, Journal, Running};
use super::{Inner, SessionsEngine, SteerOutcome, lock, subagent_doc_id};
use crate::{EngineError, new_id, now_ms};

/// An idle attachment is released after this, so the TUI can open it.
const IDLE_RELEASE: Duration = Duration::from_secs(30 * 60);
/// A bridge with no attachments stops after this, releasing its lease.
const IDLE_BRIDGE: Duration = Duration::from_secs(5 * 60);
const SWEEP_EVERY: Duration = Duration::from_secs(60);
const SETTLE_WAIT: Duration = Duration::from_secs(15);
const KEEP_REQUESTS: usize = 16;
const KEEP_SUBMISSIONS: usize = 16;

const BUSY_GUIDANCE: &str = "This conversation is open in another Mimir process, usually the Mimir TUI. Exit it there (or switch to another session), then reconnect here. Nothing was sent.";

#[derive(Default)]
pub(super) struct NativeChats {
    chats: Mutex<HashMap<String, Arc<NativeChat>>>,
    swept: std::sync::OnceLock<()>,
}

pub(super) struct NativeChat {
    chat_id: String,
    /// Serializes attach, dispatch, controls and release for this chat.
    ops: tokio::sync::Mutex<()>,
    shared: Mutex<Shared>,
    /// Bumped on every state change, for bounded settle waits.
    changed: watch::Sender<u64>,
}

struct Attachment {
    runtime: Arc<Runtime>,
    session: String,
    view: String,
    task: tokio::task::AbortHandle,
    commands: Vec<CommandDescriptor>,
    cwd: String,
    releasing: bool,
}

#[derive(Default)]
struct Shared {
    loaded: bool,
    state: NativeChatState,
    attachment: Option<Attachment>,
    /// Admitted request → the chat message it carries (prompts, submitted commands).
    request_messages: HashMap<String, String>,
    /// Active request → steer messages not yet accepted, oldest first.
    steers: HashMap<String, VecDeque<String>>,
    inputs_seen: HashMap<String, usize>,
    /// Echo ids the host has not accepted yet.
    held: HashSet<String>,
    status: Option<SessionStatus>,
    idle_since: Option<Instant>,
    /// The last root completion, for the completion notification.
    last_completion: Option<(String, bool)>,
}

impl NativeChats {
    fn chat(&self, chat_id: &str) -> Arc<NativeChat> {
        lock(&self.chats)
            .entry(chat_id.to_owned())
            .or_insert_with(|| {
                Arc::new(NativeChat {
                    chat_id: chat_id.to_owned(),
                    ops: tokio::sync::Mutex::new(()),
                    shared: Mutex::new(Shared::default()),
                    changed: watch::channel(0).0,
                })
            })
            .clone()
    }

    fn existing(&self, chat_id: &str) -> Option<Arc<NativeChat>> {
        lock(&self.chats).get(chat_id).cloned()
    }

    fn all(&self) -> Vec<Arc<NativeChat>> {
        lock(&self.chats).values().cloned().collect()
    }
}

fn status_of(state: &NativeChatState) -> SessionStatus {
    match &state.link {
        NativeLink::Interrupted { .. } | NativeLink::Unavailable { .. } => SessionStatus::Errored,
        _ if state.user_request.is_some() => SessionStatus::AwaitingInput,
        _ if state.working() => SessionStatus::Working,
        _ => SessionStatus::Idle,
    }
}

fn refused(error: &BridgeError) -> NativeControlOutcome {
    NativeControlOutcome::Refused {
        kind: error.kind.native(),
        message: error.message.clone(),
    }
}

/// A host error as a control outcome: a lost reply decided nothing.
fn failed(error: &BridgeError) -> NativeControlOutcome {
    if error.kind == ErrorKind::Disconnected {
        NativeControlOutcome::Unknown {
            message: format!("Mimir did not confirm this: {}", error.message),
        }
    } else {
        refused(error)
    }
}

/// What the user sent, kept engine-local from before the first send until
/// the host's answer settles it, so a retry resends exactly this.
#[derive(serde::Serialize, serde::Deserialize)]
struct Intent {
    kind: NativeSubmissionKind,
    input: PromptInput,
    request: RunRequest,
}

/// The routing a prompt's text asks for.
fn submission_kind(input: &PromptInput) -> NativeSubmissionKind {
    if roboco_proto::invocation::leading_command(&input.text).is_some() {
        NativeSubmissionKind::Command
    } else {
        NativeSubmissionKind::Prompt
    }
}

/// How a host command settled a message.
enum CommandSettled {
    Admitted(String),
    Handled,
    Unknown(String),
    Refused(String),
}

fn split_model(model: &str) -> (Option<String>, Option<String>) {
    match model.split_once('/') {
        Some((provider, model)) => (Some(provider.to_owned()), Some(model.to_owned())),
        None => (None, Some(model.to_owned())),
    }
}

fn reasoning_name(level: roboco_proto::ReasoningLevel) -> Option<String> {
    serde_json::to_value(level)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
}

fn media_type(path: &str) -> &'static str {
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".png") {
        "image/png"
    } else if lower.ends_with(".jpg") || lower.ends_with(".jpeg") {
        "image/jpeg"
    } else if lower.ends_with(".gif") {
        "image/gif"
    } else if lower.ends_with(".webp") {
        "image/webp"
    } else {
        "application/octet-stream"
    }
}

/// The prompt text and typed skill selections for the host. Selected skills
/// keep their catalog identity; nothing is expanded or guessed.
fn prompt_input(prompt: &str, attachments: &[String]) -> Result<PromptInput, String> {
    use roboco_proto::invocation::{Invocation, invocation_links, native_skill_identity};
    let text = roboco_proto::file_mentions::file_mention_prompt(prompt);
    let mut out = String::new();
    let mut skills = Vec::new();
    let mut at = 0;
    for (range, invocation) in invocation_links(&text) {
        out.push_str(&text[at..range.start]);
        match &invocation {
            Invocation::Skill { name, path, .. } => {
                let visible = format!("${name}");
                let start = out.len() as u64;
                out.push_str(&visible);
                skills.push(SkillSelection {
                    name: name.clone(),
                    path: (!native_skill_identity(path)).then(|| path.clone()),
                    visible_text: visible,
                    text_range: Some(TextRange {
                        start,
                        end: out.len() as u64,
                    }),
                });
            }
            Invocation::Command { .. } => out.push_str(&invocation.prompt_text()),
        }
        at = range.end;
    }
    out.push_str(&text[at..]);
    let mut images = Vec::new();
    for path in attachments {
        let bytes =
            std::fs::read(path).map_err(|e| format!("could not read attachment {path}: {e}"))?;
        images.push(PromptImage {
            media_type: media_type(path).to_owned(),
            data: base64::engine::general_purpose::STANDARD.encode(bytes),
        });
    }
    Ok(PromptInput {
        text: out,
        images,
        skills,
    })
}

impl NativeChat {
    fn bump(&self) {
        self.changed.send_modify(|n| *n += 1);
    }
}

impl SessionsEngine {
    /// Whether this chat's turn is owned by the native path.
    pub(crate) fn native_chat_active(&self, chat_id: &str) -> bool {
        self.inner.native.existing(chat_id).is_some_and(|chat| {
            let shared = lock(&chat.shared);
            shared.attachment.is_some() || shared.state.working()
        })
    }

    fn mimir(&self) -> Result<Arc<dyn Harness>, EngineError> {
        let harness = self
            .inner
            .registry
            .resolve(HarnessId::Mimir)
            .map_err(|e| EngineError::Other(e.to_string()))?;
        if harness.native().is_none() {
            return Err(EngineError::Other("the Mimir harness is not native".into()));
        }
        Ok(harness)
    }

    fn load(&self, chat: &NativeChat) {
        let mut shared = lock(&chat.shared);
        if shared.loaded {
            return;
        }
        shared.loaded = true;
        if let Ok(handle) = self.doc_handle(&chat.chat_id)
            && let Some(mut state) = handle.doc().native_state()
        {
            // A send this process never saw answered: the host decides it on
            // the next attach (by key, for prompts); nothing is resent.
            for submission in &mut state.submissions {
                if submission.delivery == NativeDelivery::Submitting {
                    *submission = self.submission(
                        &chat.chat_id,
                        &submission.message_id,
                        submission.kind,
                        NativeDelivery::Unknown {
                            message: "The engine stopped before Mimir confirmed this message."
                                .into(),
                        },
                    );
                }
            }
            let status = |id: &str| state.requests.iter().find(|r| r.id == id).map(|r| r.status);
            // A prompt whose request already runs had its input taken in an
            // earlier life; no acceptance will arrive for it again. Steering
            // still waits for the running request's next model boundary.
            shared.held = state
                .submissions
                .iter()
                .filter(|s| match &s.delivery {
                    NativeDelivery::Submitting | NativeDelivery::Unknown { .. } => true,
                    NativeDelivery::Admitted { request_id } => {
                        status(request_id) == Some(roboco_proto::NativeRequestStatus::Queued)
                    }
                    NativeDelivery::Steered { request_id } => {
                        status(request_id).is_some_and(|s| !s.is_terminal())
                    }
                    NativeDelivery::Handled | NativeDelivery::Refused { .. } => false,
                })
                .map(|s| s.message_id.clone())
                .collect();
            shared.state = state;
        }
    }

    /// Persist the state mirror and move the chat status with it.
    fn publish(&self, chat: &NativeChat, shared: &mut Shared) {
        if shared.state.requests.len() > KEEP_REQUESTS {
            let excess = shared.state.requests.len() - KEEP_REQUESTS;
            shared.state.requests.drain(..excess);
        }
        let terminal: HashSet<String> = shared
            .state
            .requests
            .iter()
            .filter(|r| r.status.is_terminal())
            .map(|r| r.id.clone())
            .collect();
        let settled = |s: &NativeSubmission| match &s.delivery {
            NativeDelivery::Admitted { request_id } | NativeDelivery::Steered { request_id } => {
                terminal.contains(request_id)
            }
            NativeDelivery::Handled => true,
            _ => false,
        };
        let mut admitted = shared
            .state
            .submissions
            .iter()
            .filter(|s| settled(s))
            .count();
        shared.state.submissions.retain(|s| {
            if settled(s) && admitted > KEEP_SUBMISSIONS {
                admitted -= 1;
                return false;
            }
            true
        });
        if let Ok(handle) = self.doc_handle(&chat.chat_id)
            && let Err(err) = handle.doc().set_native_state(&shared.state)
        {
            tracing::warn!(chat = %chat.chat_id, error = %err, "native state write failed");
        }
        let status = status_of(&shared.state);
        // The host settles state before it announces the root completion, so
        // a clean completion that finds the chat at rest is the turn's end.
        let completed = (status == SessionStatus::Idle)
            .then(|| shared.last_completion.take())
            .flatten()
            .filter(|(_, clean)| *clean)
            .and_then(|_| {
                let handle = self.doc_handle(&chat.chat_id).ok()?;
                let entries = handle.doc().read_entries().ok()?;
                entries
                    .iter()
                    .rev()
                    .find(|e| e.role == MessageRole::Assistant)
                    .map(|e| e.id.clone())
            });
        if shared.status != Some(status) || completed.is_some() {
            let was = shared.status;
            shared.status = Some(status);
            if status == SessionStatus::Idle {
                shared.idle_since = Some(Instant::now());
            } else {
                shared.idle_since = None;
            }
            self.inner.set_status_with_completion(
                &chat.chat_id,
                status,
                status == SessionStatus::Working && was != Some(SessionStatus::Working),
                completed,
            );
        }
        chat.bump();
    }

    fn set_submission(
        &self,
        chat: &NativeChat,
        shared: &mut Shared,
        message_id: &str,
        delivery: NativeDelivery,
    ) {
        // A message stays last until the host accepts its input; an admitted
        // follow-up still waits behind the running request.
        match &delivery {
            NativeDelivery::Submitting | NativeDelivery::Unknown { .. } => {
                shared.held.insert(message_id.to_owned());
            }
            NativeDelivery::Handled | NativeDelivery::Refused { .. } => {
                shared.held.remove(message_id);
            }
            NativeDelivery::Admitted { .. } | NativeDelivery::Steered { .. } => {}
        }
        if let NativeDelivery::Admitted { request_id } = &delivery {
            shared
                .request_messages
                .insert(request_id.clone(), message_id.to_owned());
        }
        if !matches!(
            delivery,
            NativeDelivery::Submitting
                | NativeDelivery::Unknown { .. }
                | NativeDelivery::Refused { .. }
        ) && let Some(host) = self.inner.doc_host()
        {
            host.forget_native_intent(&chat.chat_id, message_id);
        }
        let kind = shared
            .state
            .submissions
            .iter()
            .find(|s| s.message_id == message_id)
            .map_or(NativeSubmissionKind::Prompt, |s| s.kind);
        let submission = self.submission(&chat.chat_id, message_id, kind, delivery);
        match shared
            .state
            .submissions
            .iter_mut()
            .find(|s| s.message_id == message_id)
        {
            Some(slot) => *slot = submission,
            None => shared.state.submissions.push(submission),
        }
        self.publish(chat, shared);
    }

    /// A message's record. Only a message whose original intent the engine
    /// still holds is offered for retry: without it nothing exact can be resent.
    fn submission(
        &self,
        chat_id: &str,
        message_id: &str,
        kind: NativeSubmissionKind,
        delivery: NativeDelivery,
    ) -> NativeSubmission {
        let mut submission = NativeSubmission::new(message_id, kind, delivery);
        submission.retryable &= self
            .inner
            .doc_host()
            .and_then(|host| host.load_native_intent(chat_id, message_id))
            .is_some();
        submission
    }

    /// Start tracking a message about to go to the host as `kind`.
    fn begin_submission(
        &self,
        chat: &NativeChat,
        shared: &mut Shared,
        message_id: &str,
        kind: NativeSubmissionKind,
    ) {
        shared
            .state
            .submissions
            .retain(|s| s.message_id != message_id);
        shared.state.submissions.push(NativeSubmission::new(
            message_id,
            kind,
            NativeDelivery::Submitting,
        ));
        self.set_submission(chat, shared, message_id, NativeDelivery::Submitting);
    }

    /// The host's answer for a message, as last recorded.
    pub(crate) fn native_delivery(
        &self,
        chat_id: &str,
        message_id: &str,
    ) -> Option<NativeDelivery> {
        let chat = self.inner.native.existing(chat_id)?;
        let shared = lock(&chat.shared);
        shared
            .state
            .submissions
            .iter()
            .find(|s| s.message_id == message_id)
            .map(|s| s.delivery.clone())
    }

    /// Make the chat's mapping and pending message durable before anything
    /// irreversible reaches the host. A failure here means nothing is sent.
    fn persist_native(&self, chat_id: &str) -> Result<(), String> {
        let host = self
            .inner
            .doc_host()
            .ok_or_else(|| "the chat store is not available".to_owned())?;
        host.persist_chat(chat_id).map_err(|e| e.to_string())?;
        if let Some(workspace) = self.inner.workspace() {
            workspace.persist().map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    fn save_intent(&self, chat_id: &str, message_id: &str, intent: &Intent) -> Result<(), String> {
        let host = self
            .inner
            .doc_host()
            .ok_or_else(|| "the chat store is not available".to_owned())?;
        let bytes = serde_json::to_vec(intent).map_err(|e| e.to_string())?;
        host.save_native_intent(chat_id, message_id, &bytes)
            .map_err(|e| e.to_string())
    }

    /// The configuration a message typed mid-turn is resent under: this
    /// chat's last send, else its saved settings, carrying only this prompt.
    fn resend_request(&self, chat_id: &str, prompt: &str) -> Option<RunRequest> {
        let mut request = self.last_request(chat_id).or_else(|| {
            self.inner
                .doc_host()?
                .request_from_chat_row(chat_id, prompt)
        })?;
        request.prompt = prompt.to_owned();
        request.resume = None;
        request.attachments = Vec::new();
        Some(request)
    }

    fn load_intent(&self, chat_id: &str, message_id: &str) -> Option<Intent> {
        let bytes = self
            .inner
            .doc_host()?
            .load_native_intent(chat_id, message_id)?;
        serde_json::from_slice(&bytes).ok()
    }

    fn refuse_submission(
        &self,
        chat: &NativeChat,
        message_id: &str,
        message: String,
    ) -> EngineError {
        let mut shared = lock(&chat.shared);
        self.set_submission(
            chat,
            &mut shared,
            message_id,
            NativeDelivery::Refused {
                message: message.clone(),
            },
        );
        EngineError::Other(message)
    }

    fn set_link(&self, chat: &NativeChat, link: NativeLink) {
        let mut shared = lock(&chat.shared);
        shared.state.link = link;
        self.publish(chat, &mut shared);
    }

    fn apply_state(&self, chat: &NativeChat, shared: &mut Shared, state: &ViewState) {
        let native = &mut shared.state;
        native.conversation = Some(state.info.native());
        native.configuration = Some(state.configuration.native());
        native.active_request = state.active_request.clone();
        native.requests = state.requests.iter().map(|r| r.native()).collect();
        native.plan = state.plan();
        native.goal = state.goal.as_ref().map(|g| g.native());
        native.user_request = state.user_request.as_ref().map(|r| r.native());
        // A lost reply never decided anything: the host's request list does.
        for submission in &mut native.submissions {
            if matches!(
                submission.delivery,
                NativeDelivery::Unknown { .. } | NativeDelivery::Submitting
            ) && let Some(request) = state.request_for_key(&submission.message_id)
            {
                *submission = NativeSubmission::new(
                    &submission.message_id,
                    submission.kind,
                    NativeDelivery::Admitted {
                        request_id: request.id.clone(),
                    },
                );
                shared
                    .request_messages
                    .insert(request.id.clone(), submission.message_id.clone());
                // Already running or settled: the host took this input while
                // nobody here was watching, so it no longer waits at the end.
                if request.status != roboco_harness::mimir::protocol::RequestStatus::Queued {
                    shared.held.remove(&submission.message_id);
                }
                if let Some(host) = self.inner.doc_host() {
                    host.forget_native_intent(&chat.chat_id, &submission.message_id);
                }
            }
        }
        for request in state.requests.iter().filter(|r| {
            !matches!(
                r.status,
                roboco_harness::mimir::protocol::RequestStatus::Queued
            )
        }) {
            if request.status != roboco_harness::mimir::protocol::RequestStatus::Running
                && let Some(message) = shared.request_messages.get(&request.id)
            {
                shared.held.remove(message);
            }
        }
        for request in &state.requests {
            if let Some(key) = &request.submission_key {
                shared
                    .request_messages
                    .entry(request.id.clone())
                    .or_insert_with(|| key.clone());
            }
        }
        self.publish(chat, shared);
    }

    /// Record a child's latest announcement. Returns what was known before
    /// and what is known now; earlier attempts stay in `attempts`.
    fn apply_child(
        &self,
        chat: &NativeChat,
        shared: &mut Shared,
        child: &ViewChild,
    ) -> (Option<NativeChild>, NativeChild) {
        let doc_id = subagent_doc_id(&chat.chat_id, child.handle());
        let previous = shared
            .state
            .children
            .iter()
            .find(|c| c.handle == child.handle())
            .cloned();
        let mut native = match child {
            ViewChild::Child(info) => native_child(info, doc_id),
            ViewChild::Oversized(header) => {
                let mut native = previous.clone().unwrap_or(NativeChild {
                    handle: header.handle.clone(),
                    attempt: header.attempt,
                    profile: String::new(),
                    description: String::new(),
                    model: None,
                    status: header.status.into(),
                    background: false,
                    spawned_by: None,
                    completion_pending: header.completion_pending,
                    presentation: None,
                    doc_id,
                    oversized: true,
                    attempts: Vec::new(),
                });
                native.attempt = header.attempt;
                native.status = header.status.into();
                native.completion_pending = header.completion_pending;
                native.oversized = true;
                native
            }
        };
        if let Some(previous) = &previous {
            native.attempts = previous.attempts.clone();
            if native.spawned_by.is_none() {
                native.spawned_by = previous.spawned_by.clone();
            }
        }
        record_attempt(&mut native);
        match shared
            .state
            .children
            .iter_mut()
            .find(|c| c.handle == native.handle)
        {
            Some(slot) => *slot = native.clone(),
            None => shared.state.children.push(native.clone()),
        }
        self.publish(chat, shared);
        (previous, native)
    }

    /// Keep a settled attempt's outcome under its attempt. The host reads the
    /// exact attempt, so an announcement handled after the child was continued
    /// still keeps its own attempt's outcome.
    async fn keep_outcome(
        &self,
        chat: &NativeChat,
        runtime: &Runtime,
        session: &str,
        child: &NativeChild,
    ) {
        let outcome = match runtime
            .child_outcome(session, &child.handle, child.attempt)
            .await
        {
            Ok(Some(outcome)) => outcome.native(),
            Ok(None) => return,
            Err(error) => {
                tracing::warn!(chat = %chat.chat_id, handle = %child.handle, error = %error.message, "child outcome unreadable");
                return;
            }
        };
        let Some(host) = self.inner.doc_host() else {
            return;
        };
        let name = format!("{}.{}.outcome", child.handle, child.attempt);
        host.store_native_blob(
            &chat.chat_id,
            &name,
            &serde_json::to_vec(&outcome).unwrap_or_default(),
        );
        let mut shared = lock(&chat.shared);
        if let Some(record) = shared
            .state
            .children
            .iter_mut()
            .find(|c| c.handle == child.handle)
            .and_then(|c| c.attempts.iter_mut().find(|a| a.attempt == child.attempt))
        {
            record.outcome_ref = Some(format!("{}/{name}", chat.chat_id));
        }
        self.publish(chat, &mut shared);
    }

    // ── dispatch ────────────────────────────────────────────────────────

    /// A user message for a Mimir chat: write its echo, record what is being
    /// sent, attach if needed, make all of that durable, and only then hand
    /// it to host command routing or keyed prompt admission.
    pub(crate) async fn native_dispatch(
        &self,
        chat_id: &str,
        request: RunRequest,
        message_id: Option<String>,
    ) -> Result<String, EngineError> {
        let chat = self.inner.native.chat(chat_id);
        self.load(&chat);
        let _op = chat.ops.lock().await;
        let handle = self.doc_handle(chat_id)?;
        let message_id = message_id.unwrap_or_else(new_id);
        handle.write_user_message(&message_id, &request.prompt, now_ms())?;
        lock(&self.inner.last_requests).insert(chat_id.to_owned(), request.clone());
        // A message is sent at most once from here; anything after a send
        // goes through RetrySubmission, which knows what is safe to resend.
        if lock(&chat.shared)
            .state
            .submissions
            .iter()
            .any(|s| s.message_id == message_id)
        {
            return Ok(message_id);
        }
        let input = match prompt_input(&request.prompt, &request.attachments) {
            Ok(input) => input,
            Err(message) => return Err(self.refuse_submission(&chat, &message_id, message)),
        };
        let kind = submission_kind(&input);
        {
            let mut shared = lock(&chat.shared);
            self.begin_submission(&chat, &mut shared, &message_id, kind);
        }
        let intent = Intent {
            kind,
            input,
            request,
        };
        if let Err(error) = self.save_intent(chat_id, &message_id, &intent) {
            let message =
                format!("Roboco could not save this message, so nothing was sent: {error}");
            return Err(self.refuse_submission(&chat, &message_id, message));
        }
        self.inner.note_message(chat_id, &intent.request.prompt);
        let attached = match self.native_attach(&chat, Some(&intent.request)).await {
            Ok(attached) => attached,
            Err(message) => return Err(self.refuse_submission(&chat, &message_id, message)),
        };
        self.native_submit(&chat, attached, &message_id, intent)
            .await
    }

    /// Send a recorded intent. The chat's mapping and the pending message are
    /// made durable first; a lost reply leaves the message unknown.
    async fn native_submit(
        &self,
        chat: &Arc<NativeChat>,
        (runtime, session, commands): (Arc<Runtime>, String, Vec<CommandDescriptor>),
        message_id: &str,
        Intent { input, request, .. }: Intent,
    ) -> Result<String, EngineError> {
        if let Err(error) = self.persist_native(&chat.chat_id) {
            let message = format!("Roboco could not save this chat, so nothing was sent: {error}");
            return Err(self.refuse_submission(chat, message_id, message));
        }
        if let Some((name, tail)) = roboco_proto::invocation::leading_command(&input.text) {
            return match self
                .native_command(chat, &runtime, &session, &commands, message_id, name, tail)
                .await
            {
                CommandSettled::Admitted(request_id) => Ok(request_id),
                CommandSettled::Handled | CommandSettled::Unknown(_) => Ok(message_id.to_owned()),
                CommandSettled::Refused(message) => Err(EngineError::Other(message)),
            };
        }
        let busy = lock(&chat.shared).state.active_request.is_some();
        let delivery = if busy {
            Delivery::FollowUp
        } else {
            Delivery::Start
        };
        let admitted = runtime.prompt(&session, &input, delivery, message_id).await;
        let mut shared = lock(&chat.shared);
        match admitted {
            Ok(request_id) => {
                self.set_submission(
                    chat,
                    &mut shared,
                    message_id,
                    NativeDelivery::Admitted {
                        request_id: request_id.clone(),
                    },
                );
                drop(shared);
                self.note_turn_start(&chat.chat_id, &request.cwd);
                if let Some(titles) = self.inner.titles.get() {
                    titles.maybe_generate(
                        &chat.chat_id,
                        HarnessId::Mimir,
                        &request.prompt,
                        &request.cwd,
                    );
                }
                Ok(request_id)
            }
            Err(error) if error.kind == ErrorKind::Disconnected => {
                // Admission may or may not have happened. The key decides on
                // reconnect; nothing is resent silently.
                let message = format!("Mimir did not confirm this message: {}", error.message);
                tracing::warn!(chat = %chat.chat_id, %message, "native admission unknown");
                self.set_submission(
                    chat,
                    &mut shared,
                    message_id,
                    NativeDelivery::Unknown { message },
                );
                Ok(message_id.to_owned())
            }
            Err(error) => {
                let message = error.message.clone();
                self.set_submission(
                    chat,
                    &mut shared,
                    message_id,
                    NativeDelivery::Refused {
                        message: message.clone(),
                    },
                );
                Err(EngineError::Other(message))
            }
        }
    }

    /// Host command routing for a message, idle or mid-turn alike: unknown
    /// and terminal-only commands are the host's refusal, never a model
    /// prompt. Commands carry no key, so a lost reply stays unknown.
    #[allow(clippy::too_many_arguments)]
    async fn native_command(
        &self,
        chat: &NativeChat,
        runtime: &Runtime,
        session: &str,
        commands: &[CommandDescriptor],
        message_id: &str,
        name: &str,
        tail: &str,
    ) -> CommandSettled {
        let name = commands
            .iter()
            .find(|c| c.answers_to(name))
            .map_or(name.to_owned(), |c| c.name.clone());
        let outcome = runtime.command(session, &name, tail).await;
        let mut shared = lock(&chat.shared);
        let (delivery, settled) = match outcome {
            Ok(CommandResult::Submitted(request_id)) => (
                NativeDelivery::Admitted {
                    request_id: request_id.clone(),
                },
                CommandSettled::Admitted(request_id),
            ),
            Ok(CommandResult::Handled) => (NativeDelivery::Handled, CommandSettled::Handled),
            Ok(CommandResult::Display(text)) => {
                self.set_submission(chat, &mut shared, message_id, NativeDelivery::Handled);
                drop(shared);
                self.native_display(chat, message_id, &text);
                return CommandSettled::Handled;
            }
            Err(error) if error.kind == ErrorKind::Disconnected => {
                let message = format!("Mimir did not confirm /{name}: {}", error.message);
                (
                    NativeDelivery::Unknown {
                        message: message.clone(),
                    },
                    CommandSettled::Unknown(message),
                )
            }
            Err(error) => {
                let message = format!("/{name}: {}", error.message);
                (
                    NativeDelivery::Refused {
                        message: message.clone(),
                    },
                    CommandSettled::Refused(message),
                )
            }
        };
        self.set_submission(chat, &mut shared, message_id, delivery);
        settled
    }

    /// Transient command output, shown in the transcript under the message
    /// that produced it.
    fn native_display(&self, chat: &NativeChat, message_id: &str, text: &str) {
        let Ok(handle) = self.doc_handle(&chat.chat_id) else {
            return;
        };
        let doc = handle.doc();
        let mut batch = doc.native_batch();
        let at = batch
            .ids()
            .iter()
            .position(|id| id == message_id)
            .map_or(batch.len(), |i| i + 1);
        let entry = roboco_doc::SessionMessageEntry {
            id: format!("display-{}", new_id()),
            role: MessageRole::System,
            parts: vec![MessagePart::Notice {
                id: "n0".into(),
                notice: NativeNotice::CommandDisplay {
                    text: text.to_owned(),
                },
            }],
            created_at: now_ms(),
            device_id: self.inner.device_id.clone(),
            status: Some(roboco_doc::MessageStatus::Complete),
            continuation_of: None,
            duration_ms: None,
        };
        if batch.upsert(&entry, at).is_ok() {
            let _ = batch.commit();
        }
    }

    /// Ensure this chat holds a live attachment and view. Busy and an
    /// unavailable bridge are refusals with guidance, never a new conversation.
    async fn native_attach(
        &self,
        chat: &Arc<NativeChat>,
        request: Option<&RunRequest>,
    ) -> Result<(Arc<Runtime>, String, Vec<CommandDescriptor>), String> {
        let live = lock(&chat.shared)
            .attachment
            .as_ref()
            .filter(|a| a.runtime.alive() && !a.releasing)
            .map(|a| (a.runtime.clone(), a.session.clone(), a.commands.clone()));
        if let Some(found) = live {
            return Ok(found);
        }
        let harness = self.mimir().map_err(|e| e.to_string())?;
        let mimir = harness.native().expect("checked");
        self.set_link(chat, NativeLink::Attaching);
        let runtime = match mimir.runtime().await {
            Ok(runtime) => runtime,
            Err(failure) => {
                let message = match &failure {
                    Failure::PluginMissing(message) => {
                        format!("{message}\n{}", roboco_harness::mimir::PLUGIN_INSTALL_HINT)
                    }
                    other => other.to_string(),
                };
                self.set_link(
                    chat,
                    NativeLink::Unavailable {
                        message: message.clone(),
                    },
                );
                return Err(message);
            }
        };
        let cwd = request.map(|r| r.cwd.clone()).or_else(|| {
            lock(&chat.shared)
                .state
                .conversation
                .as_ref()
                .map(|c| c.cwd.clone())
        });
        let conversation = lock(&chat.shared)
            .state
            .conversation
            .as_ref()
            .map(|c| c.id.clone())
            .or_else(|| {
                self.inner
                    .resume_for(&chat.chat_id, cwd.as_deref().unwrap_or_default())
            });
        let attached: Result<Attached, BridgeError> = match &conversation {
            Some(id) => runtime.open(id).await,
            None => {
                let Some(cwd) = cwd.as_deref() else {
                    let message =
                        "No folder is known for this chat yet; send a message from its folder."
                            .to_owned();
                    self.set_link(chat, NativeLink::Detached);
                    return Err(message);
                };
                runtime.create(cwd, &configuration_change(request)).await
            }
        };
        let attached = match attached {
            Ok(attached) => attached,
            Err(error) if error.kind == ErrorKind::Busy => {
                self.set_link(
                    chat,
                    NativeLink::Busy {
                        message: BUSY_GUIDANCE.into(),
                    },
                );
                return Err(BUSY_GUIDANCE.into());
            }
            Err(error) => {
                let message = match &conversation {
                    Some(id) if error.kind == ErrorKind::NotFound => {
                        format!(
                            "The saved Mimir conversation {id} was not found: {}",
                            error.message
                        )
                    }
                    _ => format!("Mimir could not open this chat: {}", error.message),
                };
                self.set_link(
                    chat,
                    NativeLink::Unavailable {
                        message: message.clone(),
                    },
                );
                return Err(message);
            }
        };
        let session = attached.session.clone();
        let info = attached.state.info.clone();
        self.inner
            .remember_harness_session(&chat.chat_id, &session, &info.cwd);
        let commands = runtime.commands(&session).await.unwrap_or_default();
        let skills = runtime.skills(&session).await.unwrap_or_default();
        mimir.remember_folder_catalog(
            &info.cwd,
            commands.iter().map(|c| c.native()).collect(),
            skills.iter().map(|s| s.native()).collect(),
        );
        if let Some(server) = self.inner.roboco_mcp(&chat.chat_id)
            && let Err(error) = runtime.attach_mcp(&session, &server).await
        {
            tracing::warn!(chat = %chat.chat_id, error = %error.message, "roboco mcp attachment refused");
        }
        let (view, notes) = match runtime.open_view(&session).await {
            Ok(view) => view,
            Err(error) => {
                let _ = runtime.close(&session).await;
                let message = format!("Mimir could not open a view: {}", error.message);
                self.set_link(
                    chat,
                    NativeLink::Unavailable {
                        message: message.clone(),
                    },
                );
                return Err(message);
            }
        };
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(view_task(ViewTask {
            engine: self.clone(),
            chat: chat.clone(),
            runtime: runtime.clone(),
            session: session.clone(),
            notes,
            started: Some(started_tx),
        }));
        {
            let mut shared = lock(&chat.shared);
            shared.attachment = Some(Attachment {
                runtime: runtime.clone(),
                session: session.clone(),
                view,
                task: task.abort_handle(),
                commands: commands.clone(),
                cwd: info.cwd.clone(),
                releasing: false,
            });
            shared.state.link = NativeLink::Attached;
            shared.state.conversation = Some(info.native());
            self.apply_state(chat, &mut shared, &attached.state);
        }
        // The first view item reconciles saved history (TUI turns included)
        // before anything new is admitted on top of it.
        let _ = tokio::time::timeout(SETTLE_WAIT, started_rx).await;
        self.ensure_sweeper();
        Ok((runtime, session, commands))
    }


    // ── steering, interrupts, answers ───────────────────────────────────

    /// A message typed while the root request runs. Slash commands go to
    /// host command routing first; anything else is guidance for the running
    /// request. A lost reply stays unknown and is never redelivered; only a
    /// definite refusal falls back to a keyed prompt.
    pub(crate) async fn native_steer(
        &self,
        chat_id: &str,
        prompt: &str,
        message_id: Option<String>,
        issued_at: i64,
    ) -> Result<SteerOutcome, EngineError> {
        let Some(chat) = self.inner.native.existing(chat_id) else {
            return Ok(SteerOutcome::NotSteerable);
        };
        let _op = chat.ops.lock().await;
        let target = {
            let shared = lock(&chat.shared);
            match (&shared.attachment, &shared.state.active_request) {
                (Some(attached), Some(active))
                    if attached.runtime.alive() && !attached.releasing =>
                {
                    Some((
                        attached.runtime.clone(),
                        attached.session.clone(),
                        attached.commands.clone(),
                        active.clone(),
                    ))
                }
                _ => None,
            }
        };
        let Some((runtime, session, commands, active)) = target else {
            return Ok(SteerOutcome::NotSteerable);
        };
        let message_id = message_id.unwrap_or_else(new_id);
        if lock(&chat.shared)
            .state
            .submissions
            .iter()
            .any(|s| s.message_id == message_id)
        {
            return Ok(SteerOutcome::Accepted);
        }
        let input = prompt_input(prompt, &[]).map_err(EngineError::Other)?;
        let handle = self.doc_handle(chat_id)?;
        handle.write_user_message(&message_id, prompt, issued_at.min(now_ms()))?;
        if let Some((name, tail)) = roboco_proto::invocation::leading_command(&input.text) {
            {
                let mut shared = lock(&chat.shared);
                self.begin_submission(
                    &chat,
                    &mut shared,
                    &message_id,
                    NativeSubmissionKind::Command,
                );
            }
            // A command the host refuses mid-turn can be resent as it was
            // once the turn ends. Without a configuration to resend it under
            // it is still sent, just never offered for retry.
            if let Some(request) = self.resend_request(chat_id, prompt) {
                let intent = Intent {
                    kind: NativeSubmissionKind::Command,
                    input: input.clone(),
                    request,
                };
                if let Err(error) = self.save_intent(chat_id, &message_id, &intent) {
                    let message =
                        format!("Roboco could not save this message, so nothing was sent: {error}");
                    self.refuse_submission(&chat, &message_id, message.clone());
                    return Ok(SteerOutcome::Refused(message));
                }
            }
            if let Err(error) = self.persist_native(chat_id) {
                let message =
                    format!("Roboco could not save this chat, so nothing was sent: {error}");
                self.refuse_submission(&chat, &message_id, message.clone());
                return Ok(SteerOutcome::Refused(message));
            }
            self.inner.note_message(chat_id, prompt);
            return Ok(
                match self
                    .native_command(
                        &chat,
                        &runtime,
                        &session,
                        &commands,
                        &message_id,
                        name,
                        tail,
                    )
                    .await
                {
                    CommandSettled::Admitted(_) | CommandSettled::Handled => SteerOutcome::Accepted,
                    CommandSettled::Unknown(message) => SteerOutcome::Unknown(message),
                    CommandSettled::Refused(message) => SteerOutcome::Refused(message),
                },
            );
        }
        let text = roboco_proto::invocation::invocation_prompt(prompt);
        {
            let mut shared = lock(&chat.shared);
            self.begin_submission(&chat, &mut shared, &message_id, NativeSubmissionKind::Steer);
            shared
                .steers
                .entry(active.clone())
                .or_default()
                .push_back(message_id.clone());
        }
        let forget = |engine: &Self| {
            let mut shared = lock(&chat.shared);
            shared.held.remove(&message_id);
            shared
                .state
                .submissions
                .retain(|s| s.message_id != message_id);
            if let Some(queue) = shared.steers.get_mut(&active) {
                queue.retain(|id| *id != message_id);
            }
            engine.publish(&chat, &mut shared);
        };
        if let Err(error) = self.persist_native(chat_id) {
            let message = format!("Roboco could not save this chat, so nothing was sent: {error}");
            if let Some(queue) = lock(&chat.shared).steers.get_mut(&active) {
                queue.retain(|id| *id != message_id);
            }
            self.refuse_submission(&chat, &message_id, message.clone());
            return Ok(SteerOutcome::Refused(message));
        }
        match runtime.steer(&session, &text).await {
            Ok(()) => {
                let mut shared = lock(&chat.shared);
                self.set_submission(
                    &chat,
                    &mut shared,
                    &message_id,
                    NativeDelivery::Steered { request_id: active },
                );
                drop(shared);
                self.inner.note_message(chat_id, prompt);
                Ok(SteerOutcome::Accepted)
            }
            Err(error) if error.kind == ErrorKind::Disconnected => {
                let message = format!("Mimir did not confirm this message: {}", error.message);
                tracing::warn!(chat = %chat_id, %message, "native steer unknown");
                let mut shared = lock(&chat.shared);
                self.set_submission(
                    &chat,
                    &mut shared,
                    &message_id,
                    NativeDelivery::Unknown {
                        message: message.clone(),
                    },
                );
                Ok(SteerOutcome::Unknown(message))
            }
            Err(error) => {
                // A definite refusal (the request just ended, say): the host
                // never took it, so it becomes the next keyed prompt.
                tracing::info!(chat = %chat_id, error = %error.message, "steer refused; delivering as a new turn");
                forget(self);
                Ok(SteerOutcome::NotSteerable)
            }
        }
    }

    /// Interrupt exactly the active root request and wait for the host to
    /// settle it. Children, the goal and other chats are untouched.
    pub(crate) async fn native_interrupt(&self, chat_id: &str) -> Result<bool, EngineError> {
        let Some(chat) = self.inner.native.existing(chat_id) else {
            return Ok(false);
        };
        let target = {
            let shared = lock(&chat.shared);
            match (&shared.attachment, &shared.state.active_request) {
                (Some(attached), Some(active)) => Some((
                    attached.runtime.clone(),
                    attached.session.clone(),
                    active.clone(),
                )),
                _ => None,
            }
        };
        let Some((runtime, session, active)) = target else {
            return Ok(false);
        };
        runtime
            .cancel_request(&session, &active)
            .await
            .map_err(|e| EngineError::Other(e.message))?;
        self.settle(&chat, |state| {
            state.active_request.as_deref() != Some(active.as_str())
        })
        .await;
        Ok(true)
    }

    async fn settle(&self, chat: &NativeChat, done: impl Fn(&NativeChatState) -> bool) {
        let mut changed = chat.changed.subscribe();
        let _ = tokio::time::timeout(SETTLE_WAIT, async {
            loop {
                if done(&lock(&chat.shared).state) {
                    return;
                }
                if changed.changed().await.is_err() {
                    return;
                }
            }
        })
        .await;
    }

    // ── controls ────────────────────────────────────────────────────────

    pub(crate) async fn native_control(
        &self,
        chat_id: &str,
        control: NativeControl,
    ) -> Result<NativeControlOutcome, EngineError> {
        let chat = self.inner.native.chat(chat_id);
        self.load(&chat);
        let _op = chat.ops.lock().await;
        match control {
            NativeControl::Release => {
                return Ok(self.native_release(&chat, NativeLink::Released).await);
            }
            NativeControl::Reconnect => {
                if let Ok(harness) = self.mimir()
                    && let Some(mimir) = harness.native()
                {
                    mimir.reset_restarts();
                }
                return Ok(match self.native_attach(&chat, None).await {
                    Ok(_) => NativeControlOutcome::Applied,
                    Err(message) => NativeControlOutcome::Refused {
                        kind: if message == BUSY_GUIDANCE {
                            NativeErrorKind::Busy
                        } else {
                            NativeErrorKind::Unavailable
                        },
                        message,
                    },
                });
            }
            NativeControl::RetrySubmission { message_id } => {
                drop(_op);
                return self.native_retry(&chat, &message_id).await;
            }
            _ => {}
        }
        let (runtime, session, _) = match self.native_attach(&chat, None).await {
            Ok(attached) => attached,
            Err(message) => {
                return Ok(NativeControlOutcome::Refused {
                    kind: if message == BUSY_GUIDANCE {
                        NativeErrorKind::Busy
                    } else {
                        NativeErrorKind::Unavailable
                    },
                    message,
                });
            }
        };
        let outcome = match control {
            NativeControl::Configure {
                provider,
                model,
                reasoning,
                mode,
            } => {
                let change = ConfigurationChange {
                    provider,
                    model,
                    reasoning,
                    mode: mode.map(Mode::from),
                };
                match runtime.configure(&session, &change).await {
                    Ok(configuration) => {
                        let mut shared = lock(&chat.shared);
                        shared.state.configuration = Some(configuration.native());
                        self.publish(&chat, &mut shared);
                        NativeControlOutcome::Configured {
                            configuration: configuration.native(),
                        }
                    }
                    Err(error) => failed(&error),
                }
            }
            NativeControl::Command { name, tail } => {
                match runtime.command(&session, &name, &tail).await {
                    Ok(CommandResult::Handled) => NativeControlOutcome::Applied,
                    Ok(CommandResult::Display(text)) => NativeControlOutcome::Display { text },
                    Ok(CommandResult::Submitted(request_id)) => {
                        NativeControlOutcome::Admitted { request_id }
                    }
                    Err(error) => failed(&error),
                }
            }
            NativeControl::DecidePlan { plan_id, decision } => {
                match runtime
                    .decide_plan(&session, &plan_id, decision.into())
                    .await
                {
                    Ok(Some(request_id)) => NativeControlOutcome::Admitted { request_id },
                    Ok(None) => NativeControlOutcome::Applied,
                    Err(error) => failed(&error),
                }
            }
            NativeControl::ChangeGoal { change } => {
                match runtime.change_goal(&session, &(&change).into()).await {
                    Ok(Some(request_id)) => NativeControlOutcome::Admitted { request_id },
                    Ok(None) => NativeControlOutcome::Applied,
                    Err(error) => failed(&error),
                }
            }
            NativeControl::Answer {
                request_id,
                answers,
            } => {
                let answers: Vec<_> = answers.iter().map(Into::into).collect();
                match runtime.answer(&session, &request_id, &answers).await {
                    Ok(()) => NativeControlOutcome::Applied,
                    Err(error) => failed(&error),
                }
            }
            NativeControl::Steer { text } => match runtime.steer(&session, &text).await {
                Ok(()) => NativeControlOutcome::Applied,
                Err(error) => failed(&error),
            },
            NativeControl::CancelRequest { request_id } => {
                match runtime.cancel_request(&session, &request_id).await {
                    Ok(newly) => NativeControlOutcome::Cancelled { newly },
                    Err(error) => failed(&error),
                }
            }
            NativeControl::SteerChild {
                handle,
                attempt,
                text,
            } => match runtime.steer_child(&session, &handle, attempt, &text).await {
                Ok(control) => NativeControlOutcome::Child {
                    control: control.into(),
                },
                Err(error) => failed(&error),
            },
            NativeControl::StopChild { handle, attempt } => {
                match runtime.stop_child(&session, &handle, attempt).await {
                    Ok(control) => NativeControlOutcome::Child {
                        control: control.into(),
                    },
                    Err(error) => failed(&error),
                }
            }
            NativeControl::Release
            | NativeControl::Reconnect
            | NativeControl::RetrySubmission { .. } => {
                unreachable!("handled above")
            }
        };
        Ok(outcome)
    }

    /// Resend a message exactly as recorded: a keyed prompt whose admission
    /// is unknown (the host returns the original admission if it exists), or
    /// a message the host refused outright. Unknown commands and steering
    /// carry no key and are never resent.
    async fn native_retry(
        &self,
        chat: &Arc<NativeChat>,
        message_id: &str,
    ) -> Result<NativeControlOutcome, EngineError> {
        let retryable = lock(&chat.shared)
            .state
            .submissions
            .iter()
            .any(|s| s.message_id == message_id && s.retryable);
        if !retryable {
            return Ok(NativeControlOutcome::Refused {
                kind: NativeErrorKind::Invalid,
                message: "only an unconfirmed prompt or a refused message can be retried".into(),
            });
        }
        let Some(intent) = self.load_intent(&chat.chat_id, message_id) else {
            return Ok(NativeControlOutcome::Refused {
                kind: NativeErrorKind::NotFound,
                message: "this engine no longer holds what was sent; send it again".into(),
            });
        };
        let _op = chat.ops.lock().await;
        {
            let mut shared = lock(&chat.shared);
            self.set_submission(chat, &mut shared, message_id, NativeDelivery::Submitting);
        }
        let attached = match self.native_attach(chat, Some(&intent.request)).await {
            Ok(attached) => attached,
            Err(message) => {
                self.refuse_submission(chat, message_id, message.clone());
                return Ok(NativeControlOutcome::Refused {
                    kind: NativeErrorKind::Unavailable,
                    message,
                });
            }
        };
        let sent = self.native_submit(chat, attached, message_id, intent).await;
        Ok(
            match (sent, self.native_delivery(&chat.chat_id, message_id)) {
                (_, Some(NativeDelivery::Unknown { message })) => {
                    NativeControlOutcome::Unknown { message }
                }
                (Ok(request_id), _) if request_id != message_id => {
                    NativeControlOutcome::Admitted { request_id }
                }
                (Ok(_), _) => NativeControlOutcome::Applied,
                (Err(error), _) => NativeControlOutcome::Refused {
                    kind: NativeErrorKind::Failed,
                    message: error.to_string(),
                },
            },
        )
    }

    /// Release the attachment and await the host's cleanup and lease
    /// release, keeping the mapping. Refused while work is still owed.
    async fn native_release(
        &self,
        chat: &Arc<NativeChat>,
        link: NativeLink,
    ) -> NativeControlOutcome {
        let attachment = {
            let mut shared = lock(&chat.shared);
            if shared.state.working() || shared.state.user_request.is_some() {
                return NativeControlOutcome::Refused {
                    kind: NativeErrorKind::Busy,
                    message: "Mimir is still working in this chat. Wait for it to finish or stop it, then continue in Mimir.".into(),
                };
            }
            match shared.attachment.as_mut() {
                Some(attached) => {
                    attached.releasing = true;
                    Some((
                        attached.runtime.clone(),
                        attached.session.clone(),
                        attached.view.clone(),
                        attached.task.clone(),
                    ))
                }
                None => None,
            }
        };
        if let Some((runtime, session, view, task)) = attachment {
            self.set_link(chat, NativeLink::Releasing);
            runtime.close_view(&view).await;
            let released = runtime.close(&session).await;
            task.abort();
            if let Err(error) = released
                && runtime.alive()
            {
                // The view is closed and its task gone, so nothing here can
                // deliver the host's events any more: the chat is detached
                // until a reconnect or the next send opens a fresh view.
                let mut shared = lock(&chat.shared);
                shared.attachment = None;
                shared.state.link = NativeLink::Detached;
                self.publish(chat, &mut shared);
                return failed(&error);
            }
        }
        let mut shared = lock(&chat.shared);
        shared.attachment = None;
        shared.state.link = link;
        self.publish(chat, &mut shared);
        NativeControlOutcome::Released
    }

    // ── reads ───────────────────────────────────────────────────────────

    fn attached_runtime(&self, chat_id: &str) -> Result<(Arc<Runtime>, String), EngineError> {
        self.inner
            .native
            .existing(chat_id)
            .and_then(|chat| {
                let shared = lock(&chat.shared);
                shared
                    .attachment
                    .as_ref()
                    .filter(|a| a.runtime.alive() && !a.releasing)
                    .map(|a| (a.runtime.clone(), a.session.clone()))
            })
            .ok_or_else(|| {
                EngineError::Other("this chat is not attached to Mimir; reconnect it first".into())
            })
    }

    pub async fn native_readiness(&self, force: bool) -> NativeReadiness {
        match self.mimir() {
            Ok(harness) => harness.native().expect("checked").readiness(force).await,
            Err(error) => NativeReadiness::Failed {
                message: error.to_string(),
            },
        }
    }

    pub async fn native_catalog(&self, chat_id: &str) -> Result<NativeChatCatalog, EngineError> {
        let (runtime, session) = self.attached_runtime(chat_id)?;
        let cwd = self
            .inner
            .native
            .existing(chat_id)
            .and_then(|chat| {
                lock(&chat.shared)
                    .attachment
                    .as_ref()
                    .map(|a| a.cwd.clone())
            })
            .unwrap_or_default();
        let bridge = |e: BridgeError| EngineError::Other(e.message);
        let commands = runtime.commands(&session).await.map_err(bridge)?;
        let skills = runtime.skills(&session).await.map_err(bridge)?;
        let providers = runtime.catalog(&cwd).await.map_err(bridge)?;
        Ok(NativeChatCatalog {
            commands: commands.iter().map(|c| c.native()).collect(),
            skills: skills.iter().map(|s| s.native()).collect(),
            providers: providers.iter().map(|p| p.native()).collect(),
        })
    }

    pub async fn native_plan(
        &self,
        chat_id: &str,
    ) -> Result<Option<NativePlanArtifact>, EngineError> {
        let (runtime, session) = self.attached_runtime(chat_id)?;
        runtime
            .plan(&session)
            .await
            .map(|plan| plan.map(|p| p.native()))
            .map_err(|e| EngineError::Other(e.message))
    }

    /// The canonical child inventory. Reading never claims a completion.
    pub async fn native_children(&self, chat_id: &str) -> Result<Vec<NativeChild>, EngineError> {
        let (runtime, session) = self.attached_runtime(chat_id)?;
        let children = runtime
            .children(&session)
            .await
            .map_err(|e| EngineError::Other(e.message))?;
        let known = self
            .inner
            .native
            .existing(chat_id)
            .map(|chat| lock(&chat.shared).state.children.clone())
            .unwrap_or_default();
        Ok(children
            .iter()
            .map(|c| {
                let mut child = native_child(c, subagent_doc_id(chat_id, &c.handle));
                if let Some(seen) = known.iter().find(|k| k.handle == child.handle) {
                    child.attempts = seen.attempts.clone();
                }
                record_attempt(&mut child);
                child
            })
            .collect())
    }

    pub async fn native_child_outcome(
        &self,
        chat_id: &str,
        handle: &str,
        attempt: u32,
    ) -> Result<Option<NativeChildOutcome>, EngineError> {
        let (runtime, session) = self.attached_runtime(chat_id)?;
        runtime
            .child_outcome(&session, handle, attempt)
            .await
            .map(|o| o.map(|o| o.native()))
            .map_err(|e| EngineError::Other(e.message))
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    /// Boot: chats that were attached when the engine stopped lost their
    /// bridge with it. Mark them interrupted and reconcile their saved state.
    pub fn recover_native(&self) {
        let Some(workspace) = self.inner.workspace() else {
            return;
        };
        let Ok(chats) = workspace.read_chats() else {
            return;
        };
        for row in chats {
            if row.config.as_ref().map(|c| c.harness) != Some(HarnessId::Mimir) {
                continue;
            }
            let Ok(handle) = self.doc_handle(&row.id) else {
                continue;
            };
            let Some(state) = handle.doc().native_state() else {
                continue;
            };
            if !matches!(
                state.link,
                NativeLink::Attached
                    | NativeLink::Attaching
                    | NativeLink::Interrupted { .. }
                    | NativeLink::Releasing
            ) {
                continue;
            }
            let chat = self.inner.native.chat(&row.id);
            self.load(&chat);
            let was_working = state.working();
            {
                let mut shared = lock(&chat.shared);
                shared.state.link = NativeLink::Interrupted {
                    message: "The engine stopped while this chat was attached to Mimir.".into(),
                };
                if was_working {
                    shared.state.active_request = None;
                }
                self.publish(&chat, &mut shared);
            }
            let engine = self.clone();
            tokio::spawn(async move { engine.native_recover(chat).await });
        }
    }

    /// Reopen after a lost bridge: the restart waits for the old tree to
    /// settle and spends the restart budget; the saved state reconciles.
    fn native_recover(&self, chat: Arc<NativeChat>) -> futures::future::BoxFuture<'static, ()> {
        let engine = self.clone();
        Box::pin(async move {
            let _op = chat.ops.lock().await;
            if let Err(message) = engine.native_attach(&chat, None).await {
                tracing::warn!(chat = %chat.chat_id, %message, "native reconcile after restart failed");
            }
        })
    }

    pub(crate) async fn native_shutdown(&self) {
        let chats = self.inner.native.all();
        for chat in &chats {
            let mut shared = lock(&chat.shared);
            if let Some(attached) = shared.attachment.as_mut() {
                attached.releasing = true;
            }
        }
        if let Ok(harness) = self.mimir()
            && let Some(mimir) = harness.native()
        {
            mimir.shutdown().await;
        }
        for chat in chats {
            let mut shared = lock(&chat.shared);
            if let Some(attached) = shared.attachment.take() {
                attached.task.abort();
                shared.state.link = if shared.state.working() {
                    NativeLink::Interrupted {
                        message: "The engine stopped while Mimir was working.".into(),
                    }
                } else {
                    NativeLink::Detached
                };
                self.publish(&chat, &mut shared);
            }
        }
    }

    fn ensure_sweeper(&self) {
        // Weak: a retired engine graph must still be able to drop.
        let weak = Arc::downgrade(&self.inner);
        self.inner.native.swept.get_or_init(move || {
            tokio::spawn(async move {
                let mut idle_bridge: Option<Instant> = None;
                loop {
                    tokio::time::sleep(SWEEP_EVERY).await;
                    let Some(inner) = weak.upgrade() else { return };
                    let engine = SessionsEngine { inner };
                    let update = engine.inner.registry.update_pending(HarnessId::Mimir);
                    for chat in engine.inner.native.all() {
                        let release = {
                            let shared = lock(&chat.shared);
                            shared.attachment.as_ref().is_some_and(|a| !a.releasing)
                                && !shared.state.working()
                                && shared.state.user_request.is_none()
                                && (update
                                    || shared
                                        .idle_since
                                        .is_some_and(|at| at.elapsed() > IDLE_RELEASE))
                        };
                        if release {
                            let _op = chat.ops.lock().await;
                            engine.native_release(&chat, NativeLink::Detached).await;
                        }
                    }
                    let Ok(harness) = engine.mimir() else {
                        continue;
                    };
                    let Some(mimir) = harness.native() else {
                        continue;
                    };
                    match mimir.live_runtime().await {
                        Some(runtime) if runtime.attachment_count() == 0 => {
                            let since = *idle_bridge.get_or_insert_with(Instant::now);
                            if update || since.elapsed() > IDLE_BRIDGE {
                                mimir.shutdown().await;
                                idle_bridge = None;
                            }
                        }
                        _ => idle_bridge = None,
                    }
                }
            });
        });
    }
}

fn configuration_change(request: Option<&RunRequest>) -> ConfigurationChange {
    let Some(request) = request else {
        return ConfigurationChange::default();
    };
    let (provider, model) = request
        .model
        .as_deref()
        .filter(|m| !m.is_empty() && *m != "default")
        .map(split_model)
        .unwrap_or((None, None));
    ConfigurationChange {
        provider,
        model,
        reasoning: request.reasoning.and_then(reasoning_name),
        mode: None,
    }
}

/// Fold a child's current attempt into its attempt history.
fn record_attempt(child: &mut NativeChild) {
    match child
        .attempts
        .iter_mut()
        .find(|a| a.attempt == child.attempt)
    {
        Some(record) => {
            record.status = child.status;
            if child.presentation.is_some() {
                record.presentation = child.presentation.clone();
            }
        }
        None => {
            child.attempts.push(NativeChildAttempt {
                attempt: child.attempt,
                status: child.status,
                presentation: child.presentation.clone(),
                outcome_ref: None,
            });
            child.attempts.sort_by_key(|a| a.attempt);
        }
    }
}

fn native_child(info: &ChildInfo, doc_id: String) -> NativeChild {
    NativeChild {
        handle: info.handle.clone(),
        attempt: info.attempt,
        profile: info.profile.clone(),
        description: info.description.clone(),
        model: info.model.clone(),
        status: info.status.into(),
        background: info.background,
        spawned_by: info.spawned_by.clone(),
        completion_pending: info.completion_pending,
        presentation: Some(info.presentation.native()),
        doc_id,
        oversized: false,
        attempts: Vec::new(),
    }
}

// ── the view task ───────────────────────────────────────────────────────────

struct ViewTask {
    engine: SessionsEngine,
    chat: Arc<NativeChat>,
    runtime: Arc<Runtime>,
    session: String,
    notes: mpsc::UnboundedReceiver<ViewNote>,
    started: Option<tokio::sync::oneshot::Sender<()>>,
}

struct Projector {
    root: Journal,
    children: HashMap<String, Journal>,
    /// Parent invocation → child, from the host's spawn lineage.
    links: HashMap<String, ChildLink>,
}

impl ViewTask {
    fn inner(&self) -> &Arc<Inner> {
        &self.engine.inner
    }

    fn journal_for<'p>(
        &self,
        projector: &'p mut Projector,
        journal: &JournalRef,
    ) -> Option<&'p mut Journal> {
        match journal {
            JournalRef::Root => Some(&mut projector.root),
            JournalRef::Child(handle) => {
                if !projector.children.contains_key(handle) {
                    let host = self.inner().doc_host()?;
                    let doc_id = subagent_doc_id(&self.chat.chat_id, handle);
                    let doc = host.open(&doc_id).ok()?.doc_arc();
                    projector.children.insert(
                        handle.clone(),
                        Journal::new(
                            &self.chat.chat_id,
                            doc,
                            &self.inner().device_id,
                            Some(handle.clone()),
                            host,
                        ),
                    );
                }
                projector.children.get_mut(handle)
            }
        }
    }

    /// Echo candidates after a lost view. Inputs this process saw accepted
    /// pair in admission order; after an engine restart nothing was seen, and
    /// only the oldest admitted message can be the first input after the leaf.
    fn echoes(&self, recovering: bool) -> Echoes {
        let shared = lock(&self.chat.shared);
        let mut pending = Vec::new();
        if recovering {
            let seen_any = !shared.inputs_seen.is_empty();
            pending = shared
                .state
                .submissions
                .iter()
                .filter_map(|s| match &s.delivery {
                    NativeDelivery::Admitted { request_id }
                        if !seen_any
                            || shared.inputs_seen.get(request_id).copied().unwrap_or(0) > 0 =>
                    {
                        Some(s.message_id.clone())
                    }
                    _ => None,
                })
                .collect();
            if !seen_any {
                pending.truncate(1);
            }
        }
        Echoes {
            held: shared.held.clone(),
            pending,
        }
    }

    async fn checkpoint(
        &self,
        projector: &mut Projector,
        cut: &roboco_harness::mimir::protocol::JournalCut,
        running: Running<'_>,
        recovering: bool,
    ) {
        let echoes = if cut.journal == JournalRef::Root {
            self.echoes(recovering)
        } else {
            Echoes::default()
        };
        let Some(journal) = self.journal_for(projector, &cut.journal) else {
            return;
        };
        let leaf = journal.leaf();
        let read = match journal::read_since(&self.runtime, &self.session, cut, leaf.as_deref())
            .await
        {
            Ok(read) => read,
            Err(error) => {
                tracing::warn!(chat = %self.chat.chat_id, error = %error.message, "native entries unreadable; waiting for the next checkpoint");
                return;
            }
        };
        if let Err(error) = journal.apply(read, cut.leaf.clone(), running, &echoes) {
            tracing::warn!(chat = %self.chat.chat_id, %error, "native projection failed");
        }
        if cut.journal == JournalRef::Root {
            projector.root.bind_children(&projector.links);
            self.note_preview(&projector.root);
        }
    }

    fn note_preview(&self, root: &Journal) {
        if let Ok(entries) = root.doc.read_entries()
            && let Some(text) = entries
                .iter()
                .rev()
                .find(|e| e.role == MessageRole::Assistant)
                .and_then(|e| {
                    e.parts.iter().rev().find_map(|p| match p {
                        MessagePart::Text { text, .. } if !text.is_empty() => Some(text.clone()),
                        _ => None,
                    })
                })
        {
            self.inner().note_message(&self.chat.chat_id, &text);
        }
    }

    async fn start(&mut self, projector: &mut Projector, start: &ViewStart) {
        {
            let mut shared = lock(&self.chat.shared);
            shared.state.recovering = start.recovering;
            self.engine
                .apply_state(&self.chat, &mut shared, &start.state);
        }
        if let Some(pending) = &start.state.user_request {
            projector.root.ask_pending(pending.native());
        }
        self.checkpoint(
            projector,
            &start.root.cut,
            Running::Listed(&start.root.live_invocations),
            true,
        )
        .await;
        for child in &start.children {
            self.child(projector, child).await;
        }
        projector.root.bind_children(&projector.links);
        let held = lock(&self.chat.shared).held.clone();
        let _ = projector.root.flush(&held);
    }

    async fn child(&self, projector: &mut Projector, child: &ViewChild) {
        let (previous, native) = {
            let mut shared = lock(&self.chat.shared);
            self.engine.apply_child(&self.chat, &mut shared, child)
        };
        let settled_now = native.status.is_terminal()
            && previous
                .as_ref()
                .is_none_or(|p| p.attempt != native.attempt || !p.status.is_terminal())
            && native
                .attempts
                .iter()
                .any(|a| a.attempt == native.attempt && a.outcome_ref.is_none());
        if settled_now {
            self.engine
                .keep_outcome(&self.chat, &self.runtime, &self.session, &native)
                .await;
        }
        if let Some(spawned_by) = &native.spawned_by {
            projector.links.insert(
                spawned_by.clone(),
                ChildLink {
                    handle: native.handle.clone(),
                    doc_id: native.doc_id.clone(),
                    status: native.status,
                },
            );
            projector.root.bind_children(&projector.links);
        }
        // A child's cut carries no running list: its running calls are
        // inferred from what its journal has saved.
        if let Some(cut) = child.cut() {
            let cut = cut.clone();
            self.checkpoint(projector, &cut, Running::Inferred, false)
                .await;
        }
    }

    fn observe(
        &self,
        projector: &mut Projector,
        observation: &roboco_harness::mimir::protocol::ViewObservation,
    ) {
        if observation.child_attempt.is_some() {
            let handle = observation.observation.source.agent_id.clone();
            if let Some(journal) = self.journal_for(projector, &JournalRef::Child(handle)) {
                journal.observe(observation);
            }
            return;
        }
        if let (Event::InputAccepted(_), Some(request)) =
            (&observation.observation.event, &observation.request_id)
        {
            let echo = {
                let mut shared = lock(&self.chat.shared);
                let seen = shared.inputs_seen.entry(request.clone()).or_insert(0);
                *seen += 1;
                let echo = if *seen == 1 {
                    shared.request_messages.get(request).cloned()
                } else {
                    shared.steers.get_mut(request).and_then(VecDeque::pop_front)
                };
                if let Some(echo) = &echo {
                    shared.held.remove(echo);
                }
                echo
            };
            projector.root.accept(echo);
        }
        if let Event::ToolApprovalSettled(id) = &observation.observation.event {
            projector.root.settle_input(id);
        }
        projector.root.observe(observation);
    }

    async fn handle(&mut self, projector: &mut Projector, item: ViewItem) {
        match item {
            ViewItem::Start(start) | ViewItem::Reset(start) => {
                self.start(projector, &start).await;
                if let Some(started) = self.started.take() {
                    let _ = started.send(());
                }
            }
            ViewItem::Observation(observation) => self.observe(projector, &observation),
            ViewItem::Checkpoint(checkpoint) => {
                let recovering = lock(&self.chat.shared).state.recovering;
                if recovering && checkpoint.cut.journal == JournalRef::Root {
                    let mut shared = lock(&self.chat.shared);
                    shared.state.recovering = false;
                    self.engine.publish(&self.chat, &mut shared);
                }
                self.checkpoint(
                    projector,
                    &checkpoint.cut,
                    Running::Listed(&checkpoint.live_invocations),
                    recovering,
                )
                .await;
            }
            ViewItem::State(state) => {
                let pending = projector.root.pending_input().map(|r| r.id.clone());
                {
                    let mut shared = lock(&self.chat.shared);
                    self.engine.apply_state(&self.chat, &mut shared, &state);
                }
                if let Some(pending) = pending
                    && state.user_request.as_ref().is_none_or(|r| r.id != pending)
                {
                    projector.root.settle_input(&pending);
                }
            }
            ViewItem::Child(child) => self.child(projector, &child).await,
            ViewItem::Display(text) => projector.root.notice(NativeNotice::CommandDisplay { text }),
            ViewItem::Completed(completion) => {
                let clean = completion.error.is_none() && completion.stop_reason == "end_turn";
                let (echo, accepted) = {
                    let mut shared = lock(&self.chat.shared);
                    shared.last_completion = Some((completion.request_id.clone(), clean));
                    let echo = shared.request_messages.get(&completion.request_id).cloned();
                    if let Some(echo) = &echo {
                        shared.held.remove(echo);
                    }
                    let accepted = shared
                        .inputs_seen
                        .get(&completion.request_id)
                        .copied()
                        .unwrap_or(0)
                        > 0;
                    (echo, accepted)
                };
                // The saved journal holds an accepted input. An echo it never
                // replaced would show the message twice.
                if let Some(echo) = echo
                    && accepted
                    && !projector
                        .root
                        .doc
                        .native_projection()
                        .echoes
                        .values()
                        .any(|e| *e == echo)
                {
                    let mut batch = projector.root.doc.native_batch();
                    if batch.remove(&echo).unwrap_or(false) {
                        let _ = batch.commit();
                    }
                }
                if let Some(error) = completion.error {
                    projector.root.notice(NativeNotice::Status { text: error });
                }
                let mut shared = lock(&self.chat.shared);
                self.engine.publish(&self.chat, &mut shared);
            }
        }
    }

    fn flush(&self, projector: &mut Projector) {
        let held = lock(&self.chat.shared).held.clone();
        if let Err(error) = projector.root.flush(&held) {
            tracing::warn!(chat = %self.chat.chat_id, %error, "native live flush failed");
        }
        for journal in projector.children.values_mut() {
            let _ = journal.flush(&HashSet::new());
        }
    }

    /// The view ended. A release or shutdown is expected; otherwise the
    /// bridge went away (interruption) or the host retired the attachment.
    fn ended(&self, projector: &mut Projector, reason: &str) -> bool {
        let releasing = lock(&self.chat.shared)
            .attachment
            .as_ref()
            .is_none_or(|a| a.releasing || a.session != self.session);
        if releasing {
            return false;
        }
        let alive = self.runtime.alive();
        let message = if alive {
            format!("Mimir closed this chat's view: {reason}")
        } else {
            let exit = self.runtime.exited().borrow().clone();
            exit.map(|e| e.message)
                .unwrap_or_else(|| format!("The Mimir bridge stopped: {reason}"))
        };
        let _ = projector.root.abort_live(&message);
        for journal in projector.children.values_mut() {
            let _ = journal.abort_live(&message);
        }
        let mut shared = lock(&self.chat.shared);
        shared.attachment = None;
        for submission in &mut shared.state.submissions {
            if matches!(submission.delivery, NativeDelivery::Submitting) {
                submission.delivery = NativeDelivery::Unknown {
                    message: message.clone(),
                };
            }
        }
        shared.state.link = if alive {
            NativeLink::Detached
        } else {
            NativeLink::Interrupted { message }
        };
        if !alive {
            shared.state.active_request = None;
            shared.state.user_request = None;
        }
        self.engine.publish(&self.chat, &mut shared);
        !alive
    }
}

async fn view_task(mut task: ViewTask) {
    let Some(host) = task.inner().doc_host() else {
        return;
    };
    let Ok(root_doc) = host.open(&task.chat.chat_id).map(|h| h.doc_arc()) else {
        return;
    };
    let mut projector = Projector {
        root: Journal::new(
            &task.chat.chat_id,
            root_doc,
            &task.inner().device_id,
            None,
            host,
        ),
        children: HashMap::new(),
        links: HashMap::new(),
    };
    let mut flush_at: Option<tokio::time::Instant> = None;
    let reason = loop {
        let note = tokio::select! {
            note = task.notes.recv() => note,
            _ = tokio::time::sleep_until(flush_at.unwrap_or_else(tokio::time::Instant::now)), if flush_at.is_some() => {
                flush_at = None;
                task.flush(&mut projector);
                continue;
            }
        };
        match note {
            Some(ViewNote::Event(event)) => {
                task.handle(&mut projector, event.item).await;
                let dirty =
                    projector.root.dirty() || projector.children.values().any(Journal::dirty);
                if dirty && flush_at.is_none() {
                    flush_at = Some(
                        tokio::time::Instant::now()
                            + Duration::from_millis(roboco_doc::STREAM_COMMIT_MS),
                    );
                }
            }
            Some(ViewNote::EventError { position, message }) => {
                tracing::warn!(chat = %task.chat.chat_id, ?position, %message, "native view event lost; the next checkpoint reconciles saved content");
            }
            Some(ViewNote::Ended { reason }) => break reason,
            None => break "view channel closed".to_owned(),
        }
    };
    task.flush(&mut projector);
    if let Some(started) = task.started.take() {
        let _ = started.send(());
    }
    if task.ended(&mut projector, &reason) {
        let engine = task.engine.clone();
        let chat = task.chat.clone();
        tokio::spawn(async move { engine.native_recover(chat).await });
    }
}
