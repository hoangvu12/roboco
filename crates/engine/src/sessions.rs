//! SessionsEngine — per-chat agent runs: dispatch, steering, interrupts, input bridging,
//! journal + broadcast fan-out, and 120ms coalesced doc streaming.
//!
//! Pragmatic port of roboco's `sessions.ts` (spec: feature-inventory §3.2):
//! - every `AgentEvent` is (a) appended to the on-disk run journal, (b) broadcast to
//!   in-process subscribers, (c) folded via `fold_event_into_parts` and diffed into the
//!   chat's `SessionDoc` through `SegmentWriter` on a coalesced `STREAM_COMMIT_MS` timer;
//! - the user message entry is pushed to the doc immediately on dispatch (id = the
//!   command's client-minted message id, so optimistic echoes never flicker);
//! - a `Steered` event splits the assistant entry at the exact boundary;
//! - recovery (interrupt or a stale journal at boot) stamps the streaming entry `aborted`.
//!
//! Scope notes: sessions are keyed by chat id (one live run per chat). Roboco's pulse
//! loop is ported as the 15s liveness heartbeat in `drive_run`; its stall watchdog is
//! deliberately NOT ported (rejected in review — agents may legitimately wait on
//! something for far longer than any timeout, and a live child IS the working signal).
//! Every dying path must instead carry its own visible error (child crash with stderr,
//! spawn failure, stream error, engine-restart recovery).

use std::collections::HashMap;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError};

use chrono::Utc;
use futures::StreamExt;
use tokio::sync::{broadcast, mpsc, oneshot, watch};

use roboco_doc::{
    DocError, MessagePart, MessageRole, MessageStatus, STREAM_COMMIT_MS, SegmentWriter, SessionDoc,
    SessionMessageEntry, fold_event_into_parts, sanitize_tool_call,
};
use roboco_harness::{CancellationToken, Harness, RunControls, SteerMessage, TurnControl};
use roboco_proto::{
    AgentErrorKind, AgentEvent, DoneStatus, HarnessId, RunRequest, ScheduledResume, Session,
    SessionLimit, SessionStatus, UserInputAnswer, UserInputQuestion,
};

use crate::doc_host::{ChatDocHandle, DocHost};
use crate::registry::{HarnessDescriptor, HarnessRegistry};
use crate::run_journal::RunJournal;
use crate::{EngineError, new_id, now_ms};

/// One journaled event: the durable seq plus the event, as broadcast to subscribers.
#[derive(Debug, Clone)]
pub struct JournaledEvent {
    pub seq: u64,
    pub event: AgentEvent,
}

/// Outcome of a steer attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SteerOutcome {
    /// Delivered into the live run's steering mailbox.
    Accepted,
    /// A live run owns the turn, but an accepted CLI update prevents accepting
    /// another prompt: it stays queued until the update finishes.
    DeferredByUpdate,
    /// No live steerable run — the caller should dispatch the prompt as a new turn.
    NotSteerable,
}

type PendingInputs = Arc<Mutex<HashMap<String, oneshot::Sender<Vec<UserInputAnswer>>>>>;

/// A harness-native session id plus the cwd it was created under. Harness
/// session stores are cwd-scoped (claude keys conversations by project
/// directory — roboco sessions.ts:563 "harness session stores are keyed by
/// cwd"), so resume is only injected for runs launched from the same cwd.
#[derive(Debug, Clone)]
struct HarnessSessionRef {
    session_id: String,
    cwd: String,
}

/// The configuration a live harness runtime launched with. The steering
/// mailbox carries only the prompt (and its images), so a request whose
/// launch settings differ would silently run with the old process's — it
/// must replace the runtime and resume its harness-native session instead.
/// The driver decides what "differ" means ([`Harness::same_runtime`]): a
/// replacement kills the runtime's background work, so a setting the
/// launch never reads, or the same value spelled differently by another
/// client, must never force one.
#[derive(Clone)]
struct RuntimeConfig {
    harness_id: HarnessId,
    request: RunRequest,
    harness: Arc<dyn Harness>,
}

impl RuntimeConfig {
    fn new(harness_id: HarnessId, request: &RunRequest, harness: Arc<dyn Harness>) -> Self {
        Self {
            harness_id,
            request: request.clone(),
            harness,
        }
    }

    fn can_route(&self, harness_id: HarnessId, request: &RunRequest) -> bool {
        self.harness_id == harness_id && self.harness.same_runtime(&self.request, request)
    }

    /// How `request` reaches this runtime: `Some(false)` as is, `Some(true)`
    /// with its configuration adopted in place first
    /// ([`Harness::reconfigures_in_place`]), `None` only by replacing it.
    fn route(&self, harness_id: HarnessId, request: &RunRequest) -> Option<bool> {
        if self.can_route(harness_id, request) {
            Some(false)
        } else if self.harness_id == harness_id
            && self.harness.reconfigures_in_place(&self.request, request)
        {
            Some(true)
        } else {
            None
        }
    }
}

struct RunHandle {
    run_id: String,
    steerable: bool,
    runtime_config: RuntimeConfig,
    steer_tx: mpsc::Sender<SteerMessage>,
    /// Harness-level cancellation (protocol interrupt + child teardown).
    interrupt_token: CancellationToken,
    /// Turn-level stop + the runtime's background-work report. Used for
    /// stopping a turn when the driver [`Harness::stops_turn_in_place`].
    turn: TurnControl,
    stops_turn_in_place: bool,
    /// The harness stream is open. Before that there is no runtime to keep:
    /// a stop tears the pending start down instead.
    started: Arc<std::sync::atomic::AtomicBool>,
    /// Engine-level cancel: arms the run task's grace deadline so a harness that
    /// ignores its token can never strand the run.
    cancel: watch::Sender<bool>,
    engine_tx: mpsc::UnboundedSender<AgentEvent>,
    pending_inputs: PendingInputs,
    /// Steers accepted into the mailbox but not yet confirmed by a `Steered`
    /// event — the at-least-once ledger. A run can die with accepted steers
    /// still in its mailbox (idle reaper vs. a routed send; a mid-turn error
    /// discarding queued boundary steers): the run task drains this at exit
    /// and re-dispatches each entry as a fresh turn, so an accepted message
    /// can never silently evaporate from a transcript that shows it as sent.
    routed_steers: Arc<Mutex<std::collections::VecDeque<RoutedSteer>>>,
    /// This runtime's provider session holds the fork's copied history (a
    /// side chat's bootstrap went out on its run or on one of its steers).
    fork_history_sent: Arc<std::sync::atomic::AtomicBool>,
    /// The parent chat this run's CURRENT turn owes a completion notice to
    /// (orchestration v2): set when the turn's starting message or a steer
    /// within it came from the notifying parent that created the chat,
    /// reset when a user message starts a new turn. Read at the engine's
    /// own turn-end states to record the outcome.
    notifiable: Mutex<Option<String>>,
    /// The user stopped this runtime: it is being torn down and takes no
    /// more prompts. Flipped under the ledger lock, so every accepted steer
    /// is unambiguously before the stop (cancelled with it) or after it
    /// (re-dispatched to a fresh runtime — never lost).
    retiring: Arc<std::sync::atomic::AtomicBool>,
}

/// One accepted-but-unconfirmed steer: enough to re-dispatch it verbatim.
#[derive(Debug, Clone)]
struct RoutedSteer {
    prompt: String,
    message_id: String,
    /// The chat that sent this steer, when a chat sent it through the Roboco
    /// MCP server — the re-dispatch of an orphaned steer keeps it, so the
    /// turn it starts stays notifiable for its parent (ticket 11's rule,
    /// extended to the orphan path by ticket 12).
    origin_chat_id: Option<String>,
    /// The delivered text carried the fork's copied history: its delivery
    /// is recorded only when the runtime confirms consuming it (`Steered`).
    /// An orphan re-dispatches the bare prompt, still owing the history.
    fork_history: bool,
    /// Accepted after the user's Stop began tearing the runtime down: the
    /// Stop did not cancel it, so the message still runs.
    after_stop: bool,
    /// The run configuration it was sent with, images included: an orphan
    /// re-dispatches exactly this, not whatever the chat's latest request
    /// became. `None` when the chat had no recorded request.
    request: Option<Box<RunRequest>>,
}

struct Inner {
    device_id: String,
    /// The engine is shutting down: dying runs re-dispatch nothing.
    shutting_down: std::sync::atomic::AtomicBool,
    /// Per chat: messages a Stop cancelled while they were still on their
    /// way (see [`SessionsEngine::cancel_sent_messages`]).
    cancelled_sends: Mutex<HashMap<String, std::collections::HashSet<String>>>,
    /// Loopback IPC port this engine serves, once known (0 = not serving):
    /// what the injected `roboco mcp` server dials back into.
    ipc_port: std::sync::atomic::AtomicU16,
    journal: Arc<RunJournal>,
    registry: Arc<HarnessRegistry>,
    /// Set-once (first wins), cleared on runtime retirement: sessions and
    /// doc-host reference each other through Arcs, so this back-edge must be
    /// severable for a replaced engine graph to drop.
    doc_host: Mutex<Option<DocHost>>,
    /// chat_id → live run.
    runs: Mutex<HashMap<String, RunHandle>>,
    /// chat_id → broadcast hub (retained across runs so subscribers survive turns).
    hubs: Mutex<HashMap<String, broadcast::Sender<JournaledEvent>>>,
    statuses: Mutex<HashMap<String, Session>>,
    sessions_tx: watch::Sender<Vec<Session>>,
    /// Last dispatched request per chat — the steer→new-turn fallback re-derives its
    /// run config from this (chat config rows land with the workspace doc in M4).
    last_requests: Mutex<HashMap<String, RunRequest>>,
    /// Harness-native session ids per chat (resume continuity across turns) —
    /// the live-process cache over the durable copy on the workspace chat row
    /// (roboco kept the same pair on `chats.harness_session_id`). An empty
    /// session id is the "do not resume" tombstone after a rejected resume.
    harness_sessions: Mutex<HashMap<String, HarnessSessionRef>>,
    /// Completion-notice bookkeeping (orchestration v2): the outcomes
    /// side-chat turns owe their agent parents, mirrored to a durable
    /// engine-local ledger so undelivered outcomes survive restarts
    /// (ticket 13).
    notices: crate::completion_notices::CompletionNotices,
    /// Pending Resume-at-reset continuations (orchestration v2, ticket 07):
    /// the durable entries + disarm flags, persisted under the engine data
    /// dir. Arming, firing and cancellation live on [`SessionsEngine`].
    scheduled_resumes: crate::scheduled_resumes::ScheduledResumes,
    /// Engine settings the engine itself acts on (auto-resume limited
    /// chats, …) — loaded at assembly, persisted under the data dir.
    engine_settings: crate::engine_settings::EngineSettingsStore,
    /// Auto-titler for untitled chats (wired at engine assembly; absent in bare tests).
    titles: OnceLock<crate::titles::TitleGenerator>,
    generated_images: OnceLock<(crate::uploads::Uploads, std::path::PathBuf)>,
    /// Fired with `(chat_id, cwd)` when a user prompt starts a turn (fresh
    /// dispatch or accepted steer) — the diff sync snapshots the checkout tree
    /// for the Changes pane's "Latest turn" scope. Absent in bare tests.
    turn_listener: OnceLock<TurnListener>,
    /// Harness accounts (engine assembly; absent in bare tests): usage-limit
    /// records read the active login's cached windows (which account the
    /// limit hit, reset-time fill — ticket 06), and a run that fails with a
    /// signed-out error marks the harness's live login on them (ticket 08).
    /// A `Mutex` (not `OnceLock`) so a test's isolated store can swap in over
    /// the detected one — every reader must see one store.
    agent_accounts: Mutex<Option<crate::agent_accounts::AgentAccounts>>,
}

/// Turn-start hook: called with `(chat_id, cwd)`.
pub type TurnListener = Arc<dyn Fn(&str, &str) + Send + Sync>;

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

#[derive(Clone)]
pub struct SessionsEngine {
    inner: Arc<Inner>,
}

impl SessionsEngine {
    pub fn new(
        device_id: String,
        journal: Arc<RunJournal>,
        registry: Arc<HarnessRegistry>,
    ) -> Self {
        let (sessions_tx, _) = watch::channel(Vec::new());
        Self {
            inner: Arc::new(Inner {
                device_id,
                shutting_down: std::sync::atomic::AtomicBool::new(false),
                cancelled_sends: Mutex::new(HashMap::new()),
                ipc_port: std::sync::atomic::AtomicU16::new(0),
                journal,
                registry,
                doc_host: Mutex::new(None),
                runs: Mutex::new(HashMap::new()),
                hubs: Mutex::new(HashMap::new()),
                statuses: Mutex::new(HashMap::new()),
                sessions_tx,
                last_requests: Mutex::new(HashMap::new()),
                harness_sessions: Mutex::new(HashMap::new()),
                notices: crate::completion_notices::CompletionNotices::default(),
                scheduled_resumes: crate::scheduled_resumes::ScheduledResumes::default(),
                engine_settings: crate::engine_settings::EngineSettingsStore::default(),
                titles: OnceLock::new(),
                generated_images: OnceLock::new(),
                turn_listener: OnceLock::new(),
                agent_accounts: Mutex::new(None),
            }),
        }
    }

    /// Record the loopback IPC port this engine serves. Runs started after
    /// this carry Roboco's MCP server (see [`Inner::roboco_mcp`]); until then —
    /// or with 0 — agents get no Roboco tools rather than a dead server.
    pub fn set_ipc_port(&self, port: u16) {
        self.inner
            .ipc_port
            .store(port, std::sync::atomic::Ordering::Relaxed);
    }

    /// Wire the doc host (called once at engine assembly; the two services are mutually
    /// referential by design — sessions stream into docs, docs execute commands here).
    pub fn set_doc_host(&self, host: DocHost) {
        // First set wins (the OnceLock contract this slot replaced).
        let mut slot = lock(&self.inner.doc_host);
        if slot.is_none() {
            *slot = Some(host);
        }
    }

    /// Sever the doc-host back-edge (runtime retirement; the doc host's
    /// `shutdown_workers` clears its own sessions edge). Every access site
    /// already treats a missing doc host as "not wired".
    pub fn clear_doc_host(&self) {
        lock(&self.inner.doc_host).take();
    }

    /// Bind generated-image intake to the same profile store used by attachment RPCs.
    pub fn set_generated_images(
        &self,
        uploads: crate::uploads::Uploads,
        allowed_root: std::path::PathBuf,
    ) {
        let _ = self.inner.generated_images.set((uploads, allowed_root));
    }

    /// Wire the chat auto-titler (called once at engine assembly). After each
    /// completed exchange the run task fires it for still-untitled chats.
    pub fn set_titles(&self, titles: crate::titles::TitleGenerator) {
        let _ = self.inner.titles.set(titles);
    }

    /// Wire the turn-start listener (called once at engine assembly).
    pub fn set_turn_listener(&self, listener: TurnListener) {
        let _ = self.inner.turn_listener.set(listener);
    }

    /// Wire the harness accounts (called once at engine assembly; a test
    /// swaps in an isolated store through the same call). Usage-limit records
    /// read the active login's cached windows (see [`Inner::limit_record`]),
    /// and runs that fail signed-out mark the live login on them.
    pub fn set_agent_accounts(&self, accounts: crate::agent_accounts::AgentAccounts) {
        *lock(&self.inner.agent_accounts) = Some(accounts);
    }

    /// Persist the Resume-at-reset store under `path`, loading anything
    /// already there (engine assembly — `EngineCore::assemble`). The
    /// entries carry no timers until [`Self::recover_scheduled_resumes`]
    /// arms them, because firing needs the doc host this runs before.
    pub fn set_scheduled_resumes_path(&self, path: std::path::PathBuf) {
        self.inner.scheduled_resumes.set_path(path);
    }

    /// Wire the engine settings store (engine assembly; a bare engine keeps
    /// the in-memory default — auto-resume off).
    pub fn set_engine_settings_store(&self, store: crate::engine_settings::EngineSettingsStore) {
        self.inner.engine_settings = store;
    }

    /// The engine's settings (see [`crate::engine_settings::EngineSettings`]).
    pub fn engine_settings(&self) -> crate::engine_settings::EngineSettings {
        self.inner.engine_settings.get()
    }

    /// Update the engine's settings, persisting them when a store path is
    /// wired. A failed disk write still applies in memory (and is reported).
    pub fn set_engine_settings(
        &self,
        settings: crate::engine_settings::EngineSettings,
    ) -> Result<(), EngineError> {
        self.inner
            .engine_settings
            .set(settings)
            .map_err(|err| EngineError::Other(format!("engine settings write failed: {err}")))
    }

    fn note_turn_start(&self, chat_id: &str, cwd: &str) {
        if let Some(listener) = self.inner.turn_listener.get() {
            listener(chat_id, cwd);
        }
    }

    fn doc_handle(&self, chat_id: &str) -> Result<Arc<ChatDocHandle>, EngineError> {
        let host = self
            .inner
            .doc_host()
            .ok_or_else(|| EngineError::Other("doc host not wired into sessions engine".into()))?;
        host.open(chat_id)
    }

    /// Status watch: the full session list, re-sent on every transition.
    pub fn watch_sessions(&self) -> watch::Receiver<Vec<Session>> {
        self.inner.sessions_tx.subscribe()
    }

    pub fn session_status(&self, chat_id: &str) -> Option<Session> {
        lock(&self.inner.statuses).get(chat_id).cloned()
    }

    /// The completion-notice state (crate-internal; orchestration v2).
    pub(crate) fn notices(&self) -> &crate::completion_notices::CompletionNotices {
        &self.inner.notices
    }

    /// Load the durable completion-notice ledger a previous engine run
    /// left (ticket 13): outcomes recorded but never delivered survive the
    /// restart. Called at assembly, BEFORE boot recovery, so the
    /// interrupted outcomes recovery records for runs the restart lost
    /// land in the same ledger.
    pub(crate) fn load_pending_notices(&self, path: &std::path::Path) {
        self.inner.notices.load(path);
    }

    /// Boot delivery (ticket 13): every parent the ledger owes gets its
    /// notice now, unless it is already busy again. A parent whose crashed
    /// run is auto-resumed may still read idle here — the staggered
    /// revival lands up to a couple of seconds later — in which case the
    /// notice turn starts and the revival joins it like any later message;
    /// either order loses nothing, and the outcome always reaches the
    /// parent.
    pub(crate) fn deliver_pending_notices(&self) {
        for parent in self.inner.notices.parents() {
            spawn_notice_delivery(&self.inner, &parent);
        }
    }

    /// The doc host, for the completion-notice delivery path (crate-internal).
    pub(crate) fn host(&self) -> Option<crate::doc_host::DocHost> {
        self.inner.doc_host()
    }

    /// Whether the engine is tearing down (crate-internal): notice
    /// delivery defers — outcomes stay durably pending, and the next boot
    /// delivers them.
    pub(crate) fn shutting_down(&self) -> bool {
        self.inner
            .shutting_down
            .load(std::sync::atomic::Ordering::Acquire)
    }

    /// The parent a message from `origin` makes a turn of `chat_id`
    /// notifiable for: only the chat the durable agent-origin row
    /// (`roboco_proto::AgentOrigin` on the chat) names, and only while it
    /// did not opt out of notices at creation. `None` for user-typed sends
    /// (no origin), third-party sends, user-created side chats (no agent
    /// origin) and opted-out creators — those never notify.
    fn notifiable_parent_for(&self, chat_id: &str, origin: Option<&str>) -> Option<String> {
        let origin = origin?;
        let agent_origin = self
            .inner
            .workspace()
            .and_then(|ws| ws.chat(chat_id).ok().flatten())
            .and_then(|chat| chat.agent_origin)?;
        agent_origin
            .notify
            .then(|| agent_origin.chat_id)
            .filter(|parent| parent == origin)
    }

    /// Stamp a live run's notifiable parent for a message that just joined
    /// it. A message into a parked (idle) session starts a NEW turn: the
    /// turn's notifiability is that message's own. One into a working turn
    /// only ever ARMS notices — a parent message makes the turn notifiable;
    /// anything else (a user's steer) leaves the starting message's claim
    /// intact.
    fn mark_notifiable(&self, chat_id: &str, run_id: &str, origin: Option<&str>) {
        let mid_turn = matches!(
            self.session_status(chat_id).map(|session| session.status),
            Some(SessionStatus::Working) | Some(SessionStatus::AwaitingInput)
        );
        let from_parent = self.notifiable_parent_for(chat_id, origin);
        let mut handle = lock(&self.inner.runs);
        let Some(run) = handle.get_mut(chat_id).filter(|h| h.run_id == *run_id) else {
            return;
        };
        let mut notifiable = lock(&run.notifiable);
        let current = notifiable.take();
        *notifiable = if mid_turn {
            from_parent.or(current)
        } else {
            from_parent
        };
    }

    /// Whether this harness takes a prompt *during* a turn, rather than only at
    /// the boundary between turns. The queue drain's central question: a
    /// mid-turn agent gets the message now, everything else holds it until the
    /// turn ends. Catalog lookup only — never spawns.
    pub fn steers_mid_turn(&self, harness: HarnessId) -> bool {
        self.inner
            .registry
            .descriptors()
            .iter()
            .find(|d| d.id == harness)
            .is_some_and(HarnessDescriptor::steers_mid_turn)
    }

    /// Whether this chat has a turn in flight — streaming, or parked on a
    /// question it is still owed an answer to (see [`is_active`]). A persistent
    /// session parked BETWEEN turns is not: it holds a warm child with nothing
    /// outstanding, and takes the next prompt straight through its mailbox.
    pub fn turn_in_flight(&self, chat_id: &str) -> bool {
        lock(&self.inner.statuses)
            .get(chat_id)
            .is_some_and(is_active)
    }

    /// Any run currently working or blocked on input — the auto-updater's
    /// "don't restart from under a session" gate.
    pub fn any_active(&self) -> bool {
        lock(&self.inner.statuses).values().any(is_active)
    }

    /// Whether the chat's live runtime reports background work (subagents,
    /// background shells) still running — parked or not.
    pub fn holds_background_work(&self, chat_id: &str) -> bool {
        lock(&self.inner.runs)
            .get(chat_id)
            .is_some_and(|h| h.turn.background_live())
    }

    /// A run handle exists but its harness stream has not opened yet — the
    /// dispatch returned, the spawn has not polled. A Stop racing this
    /// window must wait it out (or it tears the pending start down before
    /// the user's message ever reached a process).
    pub fn live_run_pending_start(&self, chat_id: &str) -> bool {
        lock(&self.inner.runs)
            .get(chat_id)
            .is_some_and(|h| !h.started.load(std::sync::atomic::Ordering::Acquire))
    }

    /// A text prompt for `chat_id` would land in the mailbox of a live
    /// turn-boundary agent mid-turn. The agent reads it only after the turn,
    /// but a mailbox delivery writes the user message now — above the reply
    /// still streaming for the message before it. Such prompts belong in the
    /// visible queue. `request` = a Run that may differ from the live config
    /// (one the runtime adopts in place still lands in its mailbox; any other
    /// config restarts the runtime instead, which is no hold).
    pub fn defers_to_turn_end(
        &self,
        chat_id: &str,
        request: Option<(HarnessId, &RunRequest)>,
    ) -> bool {
        if !self.turn_in_flight(chat_id) {
            return false;
        }
        let live = lock(&self.inner.runs).get(chat_id).map(|h| {
            (
                h.runtime_config.harness_id,
                h.steerable && request.is_none_or(|(id, r)| h.runtime_config.route(id, r).is_some()),
            )
        });
        live.is_some_and(|(harness, routable)| routable && !self.steers_mid_turn(harness))
    }

    /// The chat's live run accepts steering into its mailbox.
    pub fn live_run_steerable(&self, chat_id: &str) -> bool {
        lock(&self.inner.runs)
            .get(chat_id)
            .is_some_and(|h| h.steerable && !h.retiring.load(std::sync::atomic::Ordering::Acquire))
    }

    /// Whether an accepted agent-CLI update gates the chat's live run, so a
    /// new prompt waits in the queue rather than joining that run's transcript.
    pub fn live_run_update_pending(&self, chat_id: &str) -> bool {
        let harness = lock(&self.inner.runs)
            .get(chat_id)
            .map(|h| h.runtime_config.harness_id);
        harness.is_some_and(|harness| self.inner.registry.update_pending(harness))
    }

    /// The last request dispatched for a chat (steer→new-turn fallback).
    pub fn last_request(&self, chat_id: &str) -> Option<RunRequest> {
        lock(&self.inner.last_requests).get(chat_id).cloned()
    }

    /// Subscribe to a chat's live event stream: returns the journal replay after
    /// `after_seq` plus a live receiver. Subscribe-then-replay ordering means overlap
    /// (dedupe by seq) rather than gaps.
    pub fn subscribe(
        &self,
        chat_id: &str,
        after_seq: u64,
    ) -> Result<(Vec<JournaledEvent>, broadcast::Receiver<JournaledEvent>), EngineError> {
        let rx = {
            let mut hubs = lock(&self.inner.hubs);
            hubs.entry(chat_id.to_string())
                .or_insert_with(|| broadcast::channel(1024).0)
                .subscribe()
        };
        let replay = self
            .inner
            .journal
            .replay(chat_id, after_seq)?
            .into_iter()
            .map(|(seq, event)| JournaledEvent { seq, event })
            .collect();
        Ok((replay, rx))
    }

    /// Start (or route) a run for `chat_id`.
    ///
    /// - The user message entry is written to the doc immediately (id = `message_id`).
    /// - A live steerable run receives the prompt as its next turn via the mailbox
    ///   (roboco's persistent-session routing); otherwise any live run is interrupted
    ///   first — never two runtimes driving one chat.
    ///
    /// `origin_chat_id` is the structured attribution when a chat sent this
    /// through the Roboco MCP server; the doc-host command path has usually
    /// written the entry already (idempotent by id), and the queue-drain
    /// path relies on this write to stamp it.
    pub async fn dispatch(
        &self,
        chat_id: &str,
        harness_id: HarnessId,
        request: RunRequest,
        message_id: Option<String>,
        origin_chat_id: Option<String>,
    ) -> Result<String, EngineError> {
        self.dispatch_with(
            chat_id,
            harness_id,
            request,
            message_id,
            origin_chat_id,
            false,
        )
        .await
    }

    /// [`Self::dispatch`] with the startup-crash retry marker: the retry
    /// re-dispatches with `startup_retry = true`, which makes that attempt
    /// final (its own startup death surfaces instead of retrying again).
    /// Boxed future: `drive_run` re-enters this for that retry, and the
    /// erasure breaks the opaque-type cycle the recursion would otherwise form.
    fn dispatch_with<'a>(
        &'a self,
        chat_id: &'a str,
        harness_id: HarnessId,
        request: RunRequest,
        message_id: Option<String>,
        origin_chat_id: Option<String>,
        startup_retry: bool,
    ) -> futures::future::BoxFuture<'a, Result<String, EngineError>> {
        Box::pin(self.dispatch_inner(
            chat_id,
            harness_id,
            request,
            message_id,
            origin_chat_id,
            startup_retry,
        ))
    }

    async fn dispatch_inner(
        &self,
        chat_id: &str,
        harness_id: HarnessId,
        mut request: RunRequest,
        mut message_id: Option<String>,
        origin_chat_id: Option<String>,
        startup_retry: bool,
    ) -> Result<String, EngineError> {
        // Project-less chats store cwd `~` (the creating device can't know the
        // host's home); expand it here, on the host, where the run spawns.
        request.cwd = crate::repos::expand_home(&request.cwd)
            .map_err(|error| EngineError::Other(error.to_string()))?;
        // Native-only catalog entries have no portable file fallback. Reject
        // cross-harness delivery before recording or routing the user turn.
        roboco_proto::invocation::validate_harness_invocations(&request.prompt, harness_id)
            .map_err(EngineError::Other)?;
        let routed = lock(&self.inner.runs).get(chat_id).map(|h| {
            (
                h.run_id.clone(),
                h.steerable && !h.retiring.load(std::sync::atomic::Ordering::Acquire),
                h.runtime_config.route(harness_id, &request),
                h.steer_tx.clone(),
                h.routed_steers.clone(),
                h.fork_history_sent.clone(),
                h.retiring.clone(),
            )
        });
        if let Some((run_id, steerable, route, steer_tx, ledger, history_sent, retiring)) = routed {
            let user_id = message_id.clone().unwrap_or_else(new_id);
            let mut bootstrap = None;
            let same_runtime = route.is_some();
            let reconfigure = route == Some(true);
            let accepted = if steerable && same_runtime {
                bootstrap =
                    self.warm_fork_history(chat_id, harness_id, &request.prompt, &history_sent);
                let delivered = bootstrap.as_deref().unwrap_or(&request.prompt);
                // Warm dispatch uses the same mailbox as explicit steering.
                // Register acceptance before a fast boundary can retire it.
                let message = SteerMessage {
                    // OpenCode must see the canonical selection before it
                    // decodes the provider command: a project-scoped command
                    // can disappear between composer discovery and delivery.
                    prompt: if harness_id == HarnessId::Opencode {
                        delivered.to_owned()
                    } else {
                        roboco_proto::invocation::harness_prompt(delivered, harness_id)
                    },
                    message_id: Some(user_id.clone()),
                    // Images ride the mailbox too: replacing the runtime just
                    // to inline them killed its background work.
                    attachments: request.attachments.clone(),
                    // A changed model/effort/option the live process adopts
                    // in place: replacing it killed its background work.
                    config: reconfigure.then(|| Box::new(request.clone())),
                };
                // Commit the mailbox slot atomically with the update marker.
                // An accepted update releases the slot instead, and the prompt
                // takes the fresh-run path behind the update's execution gate.
                self.inner
                    .registry
                    .while_update_clear(harness_id, || {
                        let mut pending = lock(&ledger);
                        if steer_tx.try_send(message).is_err() {
                            return false;
                        }
                        pending.push_back(RoutedSteer {
                            prompt: request.prompt.clone(),
                            message_id: user_id.clone(),
                            origin_chat_id: origin_chat_id.clone(),
                            fork_history: bootstrap.is_some(),
                            after_stop: retiring.load(std::sync::atomic::Ordering::Acquire),
                            request: Some(Box::new(request.clone())),
                        });
                        true
                    })
                    .unwrap_or(false)
            } else {
                false
            };
            if accepted {
                // A warm dispatch is a steer: the message joins the turn in
                // flight (arming its completion notice) or starts the parked
                // session's next turn (whose notifiability is the message's
                // own) — orchestration v2.
                self.mark_notifiable(chat_id, &run_id, origin_chat_id.as_deref());
                if reconfigure {
                    // The runtime runs this configuration from now on: later
                    // sends compare against it, and a re-dispatch of an
                    // orphaned steer starts from it.
                    if let Some(h) = lock(&self.inner.runs)
                        .get_mut(chat_id)
                        .filter(|h| h.run_id == run_id)
                    {
                        h.runtime_config.request = request.clone();
                    }
                    lock(&self.inner.last_requests).insert(chat_id.to_string(), request.clone());
                }
                let handle = self.doc_handle(chat_id)?;
                handle.write_user_message(
                    &user_id,
                    &request.prompt,
                    now_ms(),
                    origin_chat_id.as_deref(),
                )?;
                // A turn counts from acceptance — a prompt deferred behind an
                // update must not snapshot a turn that has not started.
                self.note_turn_start(chat_id, &request.cwd);
                if self.is_live(chat_id, &run_id) {
                    if bootstrap.is_some() {
                        history_sent.store(true, std::sync::atomic::Ordering::Release);
                    }
                    // Working BEFORE the lastMessageAt bump: both ride the
                    // workspace doc from this one peer, so causal order makes it
                    // impossible for an observer to hold [new message, old status]
                    // — that gap read as unseen-with-no-live-run = a phantom
                    // "completed" flash on every remote send (2026-07-31).
                    self.set_status(chat_id, SessionStatus::Working, false);
                    self.inner.note_message(chat_id, &request.prompt);
                    return Ok(run_id);
                }
                // The run died around the send. If its exit drain already
                // claimed the entry, that re-dispatch owns the message —
                // otherwise reclaim it and fall through to a fresh run.
                let reclaimed = {
                    let mut ledger = lock(&ledger);
                    let before = ledger.len();
                    ledger.retain(|s| s.message_id != user_id);
                    ledger.len() != before
                };
                if !reclaimed {
                    self.inner.note_message(chat_id, &request.prompt);
                    return Ok(run_id);
                }
                // Keep the already-written doc entry's id for the fresh run
                // below (write_user_message dedupes by id).
                message_id = Some(user_id);
            }
            if !same_runtime {
                tracing::info!(
                    chat = %chat_id,
                    "restarting live harness to apply changed run configuration"
                );
            }
            // Mailbox closed (runtime mid-teardown / non-steering harness) or
            // the routed run died with the message reclaimed, or the launch
            // configuration changed: replace the runtime.
            self.terminate(chat_id).await?;
        }

        // A Stop sent after this message cancelled it while it was on its
        // way: never start the stopped work again in a fresh runtime.
        if let Some(id) = &message_id
            && lock(&self.inner.cancelled_sends)
                .get_mut(chat_id)
                .is_some_and(|ids| ids.remove(id))
        {
            tracing::info!(chat = %chat_id, "message cancelled by a later Stop: not dispatched");
            return Ok(String::new());
        }
        let harness = self.inner.registry.resolve(harness_id)?;
        let handle = self.doc_handle(chat_id)?;
        let user_id = message_id.unwrap_or_else(new_id);
        handle.write_user_message(
            &user_id,
            &request.prompt,
            now_ms(),
            origin_chat_id.as_deref(),
        )?;

        // Engine-owned resume (roboco sessions.ts:736 — every dispatch read the
        // chat's stored harness session): callers always send `resume: None`;
        // the engine threads the chat's prior harness session back in so a new
        // process (app restart) continues the same harness conversation. The
        // startup-crash retry injects too — a stale id is the harness's
        // problem now (`session/load` falls back to `session/new` internally),
        // and starting the retry fresh silently dropped a good conversation.
        let mut resume_injected = false;
        if request.resume.is_none() {
            request.resume = self.inner.resume_for(chat_id, &request.cwd);
            resume_injected = request.resume.is_some();
        }
        // A fork's first run continues the SOURCE's provider session
        // natively (t3-orchestration-v2 ticket 17): the ForkSideChat RPC
        // recorded the fork source and boundary on the chat, and the driver
        // forks the provider session there — the copied history rides the
        // provider, so nothing is pasted. The intent is consumed once; a
        // missing ref (old chats), a changed harness, or a refused fork
        // falls back to the budgeted transcript from ticket 01. Consumed
        // even when the harness mismatches: the native path never re-arms.
        if request.provider_fork.is_none() && request.resume.is_none() {
            if let Some(fork) = handle.doc().take_provider_fork(harness_id) {
                request.provider_fork = Some(fork);
            }
        }
        lock(&self.inner.last_requests).insert(chat_id.to_string(), request.clone());

        let run_id = new_id();
        let (steer_tx, steer_rx) = mpsc::channel::<SteerMessage>(32);
        let (cancel_tx, cancel_rx) = watch::channel(false);
        let (engine_tx, engine_rx) = mpsc::unbounded_channel::<AgentEvent>();
        let pending_inputs: PendingInputs = Arc::new(Mutex::new(HashMap::new()));

        // Input bridge: the harness asks questions; we mint the request id, park the
        // resolver for `respond_input`, and surface the event through the run pipeline.
        // The parked resolver and the receiver the harness awaits are separate
        // channels: only an actual answer is forwarded to the harness — a dropped
        // resolver (Err) forwards nothing, so the harness resolves Err (an error),
        // never an empty answer posing as one. Deliberate empty answers (run-end
        // drain, post-turn auto-decline, interrupt) still forward as empties.
        let request_input = {
            let pending = pending_inputs.clone();
            let engine_tx = engine_tx.clone();
            Box::new(move |questions: Vec<UserInputQuestion>| {
                let (mut tx, rx) = oneshot::channel();
                let (answer_tx, answer_rx) = oneshot::channel();
                let request_id = new_id();
                lock(&pending).insert(request_id.clone(), answer_tx);
                let _ = engine_tx.send(AgentEvent::InputRequested {
                    request_id: request_id.clone(),
                    questions,
                });
                let pending = pending.clone();
                let engine_tx = engine_tx.clone();
                tokio::spawn(async move {
                    tokio::select! {
                        // A dropped resolver stays an error for the harness, as before.
                        answer = answer_rx => if let Ok(answer) = answer { let _ = tx.send(answer); },
                        // The harness gave up on the answer (input cancelled):
                        // retire the question so a late answer finds no resolver,
                        // and release the AwaitingInput park like a real answer would.
                        _ = tx.closed() => {
                            lock(&pending).remove(&request_id);
                            let _ = engine_tx.send(AgentEvent::InputResolved { request_id });
                        }
                    }
                });
                rx
            })
        };
        let interrupt_token = CancellationToken::new();
        let turn = TurnControl::default();
        let started = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let fork_history_sent = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let controls = RunControls {
            execution_lease: None,
            request_input,
            steering: steer_rx,
            interrupt: interrupt_token.clone(),
            turn: turn.clone(),
        };

        lock(&self.inner.runs).insert(
            chat_id.to_string(),
            RunHandle {
                run_id: run_id.clone(),
                steerable: harness.supports_steering(),
                runtime_config: RuntimeConfig::new(harness_id, &request, harness.clone()),
                steer_tx,
                interrupt_token,
                stops_turn_in_place: harness.supports_steering() && harness.stops_turn_in_place(),
                turn: turn.clone(),
                started: started.clone(),
                cancel: cancel_tx,
                engine_tx,
                pending_inputs,
                routed_steers: Arc::new(Mutex::new(std::collections::VecDeque::new())),
                fork_history_sent: fork_history_sent.clone(),
                notifiable: Mutex::new(
                    self.notifiable_parent_for(chat_id, origin_chat_id.as_deref()),
                ),
                retiring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            },
        );
        self.set_status(chat_id, SessionStatus::Working, true);
        // AFTER Working (same causal-order guarantee as the steer path): the
        // lastMessageAt bump must never be observable ahead of the live run.
        self.inner.note_message(chat_id, &request.prompt);

        // Name the chat NOW, off the first prompt — not after the first
        // exchange completes ("called New session for a long time for no
        // reason"; the titler only needs the prompt and skips titled chats;
        // the Done-time call below stays as the retry for a failed
        // generation).
        if let Some(titles) = self.inner.titles.get() {
            titles.maybe_generate(chat_id, harness_id, &request.prompt, &request.cwd);
        }

        tokio::spawn(drive_run(
            self.inner.clone(),
            chat_id.to_string(),
            run_id.clone(),
            harness,
            request,
            handle.doc_arc(),
            controls,
            engine_rx,
            cancel_rx,
            RunResumeState {
                user_message_id: user_id,
                resume_injected,
                startup_retry,
                fork_history_sent,
                started,
            },
            turn,
        ));
        Ok(run_id)
    }

    /// A warm send's prompt with the fork's copied history in front, when the
    /// live provider session never received it (the fork's first turns were
    /// native commands) and no earlier send in this runtime carries it
    /// (`sent`). `None` = send the prompt as is.
    fn warm_fork_history(
        &self,
        chat_id: &str,
        harness_id: HarnessId,
        prompt: &str,
        sent: &std::sync::atomic::AtomicBool,
    ) -> Option<String> {
        if sent.load(std::sync::atomic::Ordering::Acquire) {
            return None;
        }
        let session = lock(&self.inner.harness_sessions)
            .get(chat_id)
            .map(|known| known.session_id.clone())
            .filter(|id| !id.is_empty());
        let handle = self.doc_handle(chat_id).ok()?;
        self.inner.fork_history_prompt(
            chat_id,
            handle.doc(),
            harness_id,
            prompt,
            None,
            ProviderSession::Continued(session.as_deref()),
        )
    }

    /// Push a steer prompt into the live run's mailbox. `NotSteerable` when no live
    /// steerable run exists — the caller (command executor) dispatches a new turn.
    pub async fn steer(
        &self,
        chat_id: &str,
        prompt: &str,
        message_id: Option<String>,
        origin_chat_id: Option<String>,
    ) -> Result<SteerOutcome, EngineError> {
        self.steer_at(chat_id, prompt, message_id, now_ms(), origin_chat_id)
            .await
    }

    /// [`Self::steer`] with the prompt's issue time: a queued row promoted
    /// into a live run keeps its original send time on the transcript.
    /// `origin_chat_id` is the structured attribution when a chat sent this
    /// through the Roboco MCP server; the user entry it writes keeps it.
    pub(crate) async fn steer_at(
        &self,
        chat_id: &str,
        prompt: &str,
        message_id: Option<String>,
        issued_at: i64,
        origin_chat_id: Option<String>,
    ) -> Result<SteerOutcome, EngineError> {
        let target = lock(&self.inner.runs)
            .get(chat_id)
            .filter(|h| h.steerable && !h.retiring.load(std::sync::atomic::Ordering::Acquire))
            .map(|h| {
                (
                    h.run_id.clone(),
                    h.runtime_config.harness_id,
                    h.steer_tx.clone(),
                    h.routed_steers.clone(),
                    h.fork_history_sent.clone(),
                    h.retiring.clone(),
                )
            });
        let Some((run_id, harness_id, steer_tx, ledger, history_sent, retiring)) = target else {
            return Ok(SteerOutcome::NotSteerable);
        };
        roboco_proto::invocation::validate_harness_invocations(prompt, harness_id)
            .map_err(EngineError::Other)?;
        let user_id = message_id.unwrap_or_else(new_id);
        let bootstrap = self.warm_fork_history(chat_id, harness_id, prompt, &history_sent);
        let delivered = bootstrap.as_deref().unwrap_or(prompt);
        // A steer runs on the configuration live now; record it with the
        // message so a re-dispatch keeps it.
        let sent_with = self.last_request(chat_id).map(|mut request| {
            request.prompt = prompt.to_string();
            request.attachments = Vec::new();
            Box::new(request)
        });
        let message = SteerMessage {
            prompt: if harness_id == HarnessId::Opencode {
                delivered.to_owned()
            } else {
                roboco_proto::invocation::harness_prompt(delivered, harness_id)
            },
            message_id: Some(user_id.clone()),
            attachments: Vec::new(),
            config: None,
        };
        // Serialize mailbox acceptance with confirmation and Done-time
        // inspection: a fast consumer must never outrun its ledger entry. The
        // update marker is checked in the same critical section, so an
        // accepted CLI update releases the mailbox slot instead: `None`
        // (deferred) stays distinct from `Some(false)` (mailbox closed).
        let accepted = self.inner.registry.while_update_clear(harness_id, || {
            let mut pending = lock(&ledger);
            let sent = steer_tx.try_send(message).is_ok();
            if sent {
                pending.push_back(RoutedSteer {
                    prompt: prompt.to_string(),
                    message_id: user_id.clone(),
                    origin_chat_id: origin_chat_id.clone(),
                    fork_history: bootstrap.is_some(),
                    after_stop: retiring.load(std::sync::atomic::Ordering::Acquire),
                    request: sent_with,
                });
            }
            sent
        });
        let Some(true) = accepted else {
            // `None` = deferred behind an update; `Some(false)` = mailbox closed.
            return Ok(match accepted {
                Some(false) => SteerOutcome::NotSteerable,
                _ => SteerOutcome::DeferredByUpdate,
            });
        };
        // A parent's steer within the turn keeps the turn notifiable; one
        // into a parked session defines the next turn's notifiability —
        // orchestration v2 completion notices.
        self.mark_notifiable(chat_id, &run_id, origin_chat_id.as_deref());
        let handle = self.doc_handle(chat_id)?;
        handle.write_user_message(
            &user_id,
            prompt,
            issued_at.min(now_ms()),
            origin_chat_id.as_deref(),
        )?;
        // A routed steer is a turn too. Fired here (not only on the confirmed
        // path) — a reclaim falls back to dispatch, which just re-snapshots.
        if let Some(request) = self.last_request(chat_id) {
            self.note_turn_start(chat_id, &request.cwd);
        }
        if self.is_live(chat_id, &run_id) {
            if bootstrap.is_some() {
                history_sent.store(true, std::sync::atomic::Ordering::Release);
            }
            self.set_status(chat_id, SessionStatus::Working, false);
            self.inner.note_message(chat_id, prompt);
            return Ok(SteerOutcome::Accepted);
        }
        // The run died around the send. Exit drain claimed the entry → its
        // re-dispatch owns the message; still ours → reclaim and report
        // NotSteerable so the executor falls back to a fresh dispatch
        // (same message id — the doc entry dedupes).
        let reclaimed = {
            let mut ledger = lock(&ledger);
            let before = ledger.len();
            ledger.retain(|s| s.message_id != user_id);
            ledger.len() != before
        };
        if reclaimed {
            return Ok(SteerOutcome::NotSteerable);
        }
        self.inner.note_message(chat_id, prompt);
        Ok(SteerOutcome::Accepted)
    }

    /// A Stop acting now cancels these messages, sent before it but still
    /// on their way: one that reaches dispatch after the runtime went down
    /// is dropped rather than run in a fresh runtime.
    pub fn cancel_sent_messages(&self, chat_id: &str, message_ids: Vec<String>) {
        if message_ids.is_empty() {
            return;
        }
        lock(&self.inner.cancelled_sends)
            .entry(chat_id.to_string())
            .or_default()
            .extend(message_ids);
    }

    /// The user's Stop: a hard boundary. The in-flight turn ends `aborted`
    /// and the runtime goes with it — background subagents, background
    /// shells, everything the agent runs. The next message resumes the
    /// conversation in a fresh process. (Sending, steering and switching
    /// models never come here: they keep the runtime and its work.)
    pub async fn interrupt(&self, chat_id: &str) -> Result<bool, EngineError> {
        self.terminate(chat_id).await
    }

    /// End the in-flight turn so another prompt can take its place ("send
    /// now"), keeping the runtime and its background work. Its streaming
    /// entry is stamped `aborted`; this waits (bounded) for that settlement
    /// so callers observe a consistent doc. Returns whether there was a turn
    /// (or runtime) to stop.
    ///
    /// A driver that [`Harness::stops_turn_in_place`] ends only the turn: the
    /// runtime parks with its background work — subagents, background shells
    /// — still running, and the next prompt routes into it. Drivers without
    /// that, runs whose harness has not started yet, and stops that do not
    /// settle fall back to [`Self::terminate`].
    pub async fn stop_turn(&self, chat_id: &str) -> Result<bool, EngineError> {
        const STOP_SETTLE: std::time::Duration = std::time::Duration::from_secs(15);
        let target = lock(&self.inner.runs)
            .get(chat_id)
            .filter(|h| {
                h.stops_turn_in_place && h.started.load(std::sync::atomic::Ordering::Acquire)
            })
            .map(|h| (h.run_id.clone(), h.turn.clone(), h.pending_inputs.clone()));
        let Some((run_id, turn, pending)) = target else {
            return self.terminate(chat_id).await;
        };
        if !self.turn_in_flight(chat_id) {
            return Ok(false); // parked between turns: nothing to stop
        }
        // Unpark questions first: the stopped turn can never be answered.
        let parked: Vec<_> = lock(&pending).drain().map(|(_, tx)| tx).collect();
        for tx in parked {
            let _ = tx.send(Vec::new());
        }
        turn.stop_turn();
        let deadline = tokio::time::Instant::now() + STOP_SETTLE;
        while tokio::time::Instant::now() < deadline {
            if !self.is_live(chat_id, &run_id) || !self.turn_in_flight(chat_id) {
                return Ok(true);
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        tracing::warn!(chat = %chat_id, "turn stop did not settle; tearing the runtime down");
        self.terminate(chat_id).await
    }

    /// Tear the chat's live runtime down (protocol interrupt, then the
    /// child). The run settles with a synthetic `Done{interrupted}` and its
    /// streaming entry stamped `aborted`; this waits (bounded) for that
    /// settlement. Background work the runtime held dies with it.
    pub async fn terminate(&self, chat_id: &str) -> Result<bool, EngineError> {
        let target = lock(&self.inner.runs).get(chat_id).map(|h| {
            (
                h.run_id.clone(),
                h.interrupt_token.clone(),
                h.cancel.clone(),
                h.pending_inputs.clone(),
                h.routed_steers.clone(),
                h.retiring.clone(),
            )
        });
        let Some((run_id, token, cancel, pending, ledger, retiring)) = target else {
            return Ok(false);
        };
        // From here the runtime takes no prompt: a send now starts a fresh
        // runtime, and one that raced into this mailbox is re-dispatched.
        {
            let _order = lock(&ledger);
            retiring.store(true, std::sync::atomic::Ordering::Release);
        }
        // Publish cancellation BEFORE waking the harness. Otherwise a fast
        // EOF after token.cancel() can beat this watch notification and be
        // classified as an error instead of an interrupted turn.
        let _ = cancel.send(true);
        // Unpark questions before harness teardown, which can await them.
        let parked: Vec<_> = lock(&pending).drain().map(|(_, tx)| tx).collect();
        for tx in parked {
            let _ = tx.send(Vec::new());
        }
        // Harness-level interrupt (protocol + child teardown) …
        token.cancel();
        // Bounded settle wait (the run task appends Done + stamps `aborted`).
        for _ in 0..500 {
            if !self.is_live(chat_id, &run_id) {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        Ok(true)
    }

    /// One-way harness switch between turns (t3-orchestration-v2 ticket 24).
    /// Writes the "Switched to <harness>" seam into the transcript, drops
    /// the chat's harness session (the tombstone also blocks the journal
    /// scan from resurrecting the old harness's id), retires any stale
    /// native-fork intent, and saves `config` — the caller's resolved picks
    /// (model options reset to the new harness's defaults unless the user
    /// chose them; the engine only stores what arrived). A warm runtime from
    /// the old harness is torn down by the next dispatch (its run
    /// configuration no longer routes). The next send starts a fresh
    /// provider session on the new harness and carries the budgeted history
    /// of everything above the seam. Switching back is just another switch.
    ///
    /// Refused while a turn is in flight (the picker stays locked while the
    /// chat works), for an unknown chat, and for a switch onto the harness
    /// the chat already runs.
    pub async fn switch_harness(
        &self,
        chat_id: &str,
        config: roboco_proto::ChatConfig,
    ) -> Result<(), EngineError> {
        let Some(ws) = self.inner.workspace() else {
            return Err(EngineError::Other("workspace unavailable".into()));
        };
        let chat = ws
            .chat(chat_id)?
            .ok_or_else(|| EngineError::Other("Chat no longer exists".into()))?;
        if !ws.is_host(chat_id) {
            return Err(EngineError::Other(
                "Harness switch must run on the chat's host device".into(),
            ));
        }
        if self.turn_in_flight(chat_id) {
            return Err(EngineError::Other(
                "The chat is working; switch harnesses between turns".into(),
            ));
        }
        // The target harness must resolve first: the switch is pointless (and
        // the next dispatch would fail) against an unregistered harness, and
        // its display name names it in the refusal below.
        let harness = self.inner.registry.resolve(config.harness)?;
        if chat.config.as_ref().is_some_and(|existing| existing.harness == config.harness) {
            return Err(EngineError::Other(format!(
                "Chat is already on {}",
                harness.display_name()
            )));
        }
        // The seam first: once the row names the new harness, a crash between
        // the two writes would at worst dispatch the next send on the new
        // harness with the history still riding (the seam is what arms it).
        let handle = self.doc_handle(chat_id)?;
        handle.doc().clear_provider_fork();
        let marker_id = format!("switch:{}", new_id());
        handle
            .doc()
            .push_message(&SessionMessageEntry {
                origin_chat_id: None,
                duration_ms: None,
                provider_turn: None,
                id: marker_id.clone(),
                role: MessageRole::System,
                parts: vec![MessagePart::HarnessSwitch {
                    id: marker_id,
                    harness: config.harness,
                }],
                created_at: now_ms(),
                device_id: self.inner.device_id.clone(),
                status: Some(MessageStatus::Complete),
                continuation_of: None,
            })
            .map_err(|e| EngineError::Other(e.to_string()))?;
        // Drop the session from every resume source: the live cache, the
        // durable row (the empty-string tombstone also stops the journal
        // scan — the old harness's session ids must never ride a resume on
        // the new harness), and the native-fork intent above.
        lock(&self.inner.harness_sessions).remove(chat_id);
        ws.set_chat_harness_session(chat_id, "", "");
        ws.set_chat_config(chat_id, &config)?;
        Ok(())
    }

    /// Resolve a pending `request_input` question set. Returns `false` when no such
    /// request is pending (unknown id, or the run already settled).
    pub fn respond_input(
        &self,
        chat_id: &str,
        request_id: &str,
        answers: Vec<UserInputAnswer>,
    ) -> Result<bool, EngineError> {
        let target = lock(&self.inner.runs)
            .get(chat_id)
            .map(|h| (h.pending_inputs.clone(), h.engine_tx.clone()));
        let Some((pending, engine_tx)) = target else {
            return Ok(false);
        };
        let Some(resolver) = lock(&pending).remove(request_id) else {
            return Ok(false);
        };
        let _ = resolver.send(answers);
        let _ = engine_tx.send(AgentEvent::InputResolved {
            request_id: request_id.to_string(),
        });
        Ok(true)
    }

    /// Boot recovery: for every journal whose last event is not `Done` (a run died
    /// mid-stream), stamp this device's abandoned `streaming` doc entries `aborted`
    /// with a VISIBLE "Run interrupted by engine restart" error part, close the
    /// journal with a synthetic `Done{interrupted}` — and then PICK THE RUN BACK
    /// UP: a fresh crashed turn with revival budget left is re-dispatched against
    /// the remembered harness session (roboco: "not just eulogized";
    /// `MAX_AUTO_RESUME` = 3 consecutive revivals, fresh = crashed < 12h ago).
    pub fn recover_stale(&self) -> Result<usize, EngineError> {
        const MAX_AUTO_RESUME: u32 = 3;
        const RESUME_FRESH_MS: i64 = 12 * 60 * 60 * 1000;
        // Auto-resume is a boot burst: every crashed chat would re-dispatch
        // at once (the incident saw 8 simultaneous dispatches, each spawning
        // a harness CLI and hashing its checkout while the engine was still
        // warming). Cap the concurrent resumes and stagger their starts
        // with per-chat jitter so a fleet-wide restart does not stampede.
        const MAX_CONCURRENT_RESUMES: usize = 2;
        let resume_gate = Arc::new(tokio::sync::Semaphore::new(MAX_CONCURRENT_RESUMES));
        let mut resume_ix = 0u64;

        let stale = self.inner.journal.stale_sessions()?;
        let mut recovered = 0usize;
        for chat_id in stale {
            if lock(&self.inner.runs).contains_key(&chat_id) {
                continue; // a live run owns this journal
            }
            let handle = self.doc_handle(&chat_id)?;
            // Harness continuity first: the crashed run's session id may only
            // exist in the journal (the debounced workspace-row write may
            // never have landed) — remember it so the revived run resumes the
            // same harness conversation (roboco recoverDraft, sessions.ts:538).
            if let Some((session_id, cwd)) = self.inner.journal_harness_session(&chat_id) {
                self.inner
                    .remember_harness_session(&chat_id, &session_id, &cwd);
            }
            // The revival prompt: the last user message (idempotent re-dispatch
            // under the SAME id — `write_user_message` dedupes by id, so the
            // transcript never shows a duplicate). Its structured origin
            // rides along, so a parent-started turn that is revived stays
            // notifiable for its completion notice (orchestration v2).
            let prompt = handle.doc().read_entries().ok().and_then(|entries| {
                entries
                    .iter()
                    .rev()
                    .find(|e| e.role == MessageRole::User)
                    .and_then(|e| {
                        e.parts.iter().find_map(|p| match p {
                            MessagePart::Text { text, .. } => Some((
                                e.id.clone(),
                                text.clone(),
                                e.origin_chat_id.clone(),
                            )),
                            _ => None,
                        })
                    })
            });
            let attempts = self.inner.journal.resume_attempts(&chat_id);
            let fresh = handle
                .doc()
                .read_entries()
                .ok()
                .and_then(|entries| {
                    entries
                        .iter()
                        .rev()
                        .find(|e| e.status == Some(MessageStatus::Streaming))
                        // Fresh: the crashed reply's streaming entry is recent — or no
                        // reply entry reached disk at all (the doc saves on a debounce,
                        // and a kill moments into the turn leaves the user's message as
                        // the transcript's last word), and that message is recent.
                        .or_else(|| entries.last().filter(|e| e.role == MessageRole::User))
                        .map(|e| now_ms() - e.created_at < RESUME_FRESH_MS)
                })
                .unwrap_or(false);
            let will_resume = fresh && prompt.is_some() && attempts < MAX_AUTO_RESUME;

            let note = if will_resume {
                "Run interrupted by engine restart — resuming"
            } else {
                "Run interrupted by engine restart"
            };
            let done = AgentEvent::Done {
                status: DoneStatus::Interrupted,
                result: None,
                error: Some(note.into()),
                session_id: None,
            };
            self.inner.publish(&chat_id, &done);
            let stamped = handle.mark_abandoned_streams(note)?.len();
            self.set_status(&chat_id, SessionStatus::Idle, false);
            tracing::info!(chat = %chat_id, stamped, will_resume, attempts, "recovered stale session journal");
            recovered += 1;

            if !will_resume {
                // Completion notices (orchestration v2, ticket 13): a
                // notifiable run lost to the restart with no auto-resume
                // owes its parent an interrupted outcome — the work never
                // finished, and the parent may want to restart it. Recorded
                // synchronously here (not through `spawn_notice_record`)
                // so the boot delivery that runs after recovery sees it in
                // the ledger. Archived chats are quiet both ways: an
                // archived side chat sends nothing, an archived parent is
                // owed nothing.
                let notifiable = prompt
                    .as_ref()
                    .and_then(|(_, _, origin)| origin.as_deref())
                    .and_then(|origin| self.notifiable_parent_for(&chat_id, Some(origin)))
                    .filter(|parent| {
                        !crate::completion_notices::chat_archived(self, &chat_id)
                            && !crate::completion_notices::chat_archived(self, parent)
                    });
                if let Some(parent) = notifiable {
                    self.inner.notices.record(
                        &parent,
                        crate::completion_notices::PendingOutcome {
                            side_chat_id: chat_id.clone(),
                            outcome: crate::completion_notices::Outcome::Interrupted {
                                reason: Some(
                                    crate::completion_notices::INTERRUPTED_REASON.into(),
                                ),
                            },
                        },
                    );
                }
                continue;
            }
            let attempt = self.inner.journal.note_resume_attempt(&chat_id);
            let delay = std::time::Duration::from_millis(
                resume_ix * RESUME_STAGGER_MS + resume_jitter_ms(&chat_id),
            );
            resume_ix += 1;
            let (user_id, prompt_text, origin_chat_id) = prompt.expect("gated by will_resume");
            let sessions = self.clone();
            let gate = resume_gate.clone();
            tokio::spawn(async move {
                // Staggered start, then the concurrency cap: resumes begin
                // spaced out, and a slow dispatch (CLI spawn + session
                // handshake) holds its slot until it lands.
                tokio::time::sleep(delay).await;
                let _permit = gate.acquire().await;
                let Some(host) = sessions.inner.doc_host() else {
                    return;
                };
                let request = sessions
                    .last_request(&chat_id)
                    .or_else(|| host.request_from_chat_row(&chat_id, &prompt_text))
                    // Last resort: the journal's own cwd (roboco's draft config)
                    // — a crash can predate the debounced workspace-row write.
                    .or_else(|| {
                        let (_, cwd) = sessions.inner.journal_harness_session(&chat_id)?;
                        Some(RunRequest {
                            prompt: String::new(),
                            harness: None,
                            model: None,
                            reasoning: None,
                            model_options: Default::default(),
                            cwd,
                            sandbox: roboco_proto::SandboxLevel::WorkspaceWrite,
                            auto_approve: false,
                            attachments: Vec::new(),
                            resume: None,
                            provider_fork: None,
                            worktree: None,
                            mcp: None,
                        })
                    });
                let Some(mut request) = request else {
                    tracing::warn!(chat = %chat_id, "auto-resume skipped: no run config");
                    return;
                };
                request.prompt = prompt_text;
                request.resume = None; // dispatch re-injects the remembered session
                request.attachments = Vec::new();
                let harness_id = host.harness_for_request(&chat_id, &request);
                match host
                    .dispatch_with_source_context(
                        &sessions,
                        &chat_id,
                        harness_id,
                        request,
                        Some(user_id),
                        origin_chat_id,
                    )
                    .await
                {
                    Ok(_) => {
                        tracing::info!(chat = %chat_id, attempt, "auto-resumed crashed run")
                    }
                    Err(err) => {
                        tracing::warn!(chat = %chat_id, error = %err, "auto-resume dispatch failed")
                    }
                }
            });
        }
        Ok(recovered)
    }

    // ── Resume at reset (orchestration v2, ticket 07) ─────────────────────

    /// The chat's pending Resume-at-reset continuation, when one is armed.
    /// (The session row carries the same record for clients; this is the
    /// engine-side read.)
    pub fn scheduled_resume(&self, chat_id: &str) -> Option<ScheduledResume> {
        self.inner.scheduled_resumes.scheduled(chat_id)
    }

    /// **Resume at reset**: arm the durable continuation for a Limited chat,
    /// firing the continue prompt at its limit's reset time. Requires the
    /// chat to be Limited with a reset time still ahead — otherwise the
    /// honest offer is the manual Resume, and the caller is told so.
    pub fn schedule_resume_at_reset(&self, chat_id: &str) -> Result<(), EngineError> {
        let limit = lock(&self.inner.statuses)
            .get(chat_id)
            .and_then(|row| row.limit.clone())
            .ok_or_else(|| {
                EngineError::Other(format!("chat {chat_id} is not Limited: no limit record"))
            })?;
        let fire_at = limit
            .resets_at
            .ok_or_else(|| EngineError::Other(
                "the limit's reset time is unknown — use Resume instead".into(),
            ))?;
        if fire_at <= now_ms() {
            return Err(EngineError::Other(
                "the limit has already reset — use Resume instead".into(),
            ));
        }
        self.arm_scheduled_resume(chat_id, fire_at);
        Ok(())
    }

    /// Cancel the chat's pending Resume-at-reset continuation. A new message
    /// to the chat, archiving it or deleting it all land here — the user's
    /// newer intent wins. `true` when there was one to cancel.
    pub fn cancel_scheduled_resume(&self, chat_id: &str) -> bool {
        let cancelled = self.inner.scheduled_resumes.cancel(chat_id);
        if cancelled {
            self.inner.set_scheduled_resume_row(chat_id, None);
            tracing::info!(chat = %chat_id, "scheduled resume cancelled");
        }
        cancelled
    }

    /// Cancel the continuation as the chat is DELETED: the store entry and
    /// timer go, the in-memory row clears, but nothing mirrors into the
    /// workspace doc — its sessions row is being deleted with the chat, and
    /// a mirror would resurrect it.
    pub(crate) fn cancel_scheduled_resume_with_chat(&self, chat_id: &str) {
        if self.inner.scheduled_resumes.cancel(chat_id) {
            self.inner.set_scheduled_resume_row_local(chat_id, None);
            tracing::info!(chat = %chat_id, "scheduled resume cancelled with the deleted chat");
        }
    }

    /// Boot recovery: arm timers for every continuation that survived the
    /// restart — an overdue one fires now, after the engine is up. Called
    /// from assembly once the doc host is wired (firing needs it).
    pub fn recover_scheduled_resumes(&self) {
        let pending = self.inner.scheduled_resumes.pending();
        for (ix, (chat_id, fire_at, disarmed)) in pending.into_iter().enumerate() {
            // Same stagger discipline as the crashed-run auto-resume: a
            // fleet of overdue continuations re-dispatches spaced out, not
            // as one boot burst of CLI spawns.
            let delay = std::time::Duration::from_millis(ix as u64 * RESUME_STAGGER_MS);
            self.spawn_resume_timer(chat_id, fire_at, disarmed, delay);
            tracing::info!(
                chat = %chat_id,
                fire_at,
                overdue = fire_at <= now_ms(),
                "scheduled resume armed at boot"
            );
        }
    }

    /// Arm (or re-arm) the chat's Resume-at-reset continuation at `fire_at`:
    /// store it durably, publish it on the status row, spawn its timer.
    fn arm_scheduled_resume(&self, chat_id: &str, fire_at: i64) {
        let disarmed = self.inner.scheduled_resumes.arm(chat_id, fire_at);
        self.inner
            .set_scheduled_resume_row(chat_id, Some(ScheduledResume { fire_at }));
        self.spawn_resume_timer(
            chat_id.to_string(),
            fire_at,
            disarmed,
            std::time::Duration::ZERO,
        );
    }

    /// The **Auto-resume limited chats** setting's arm: a fresh limit record
    /// re-arms the continuation only when its reset time is still ahead AND
    /// advances past the last continuation this chat fired — the loop guard.
    /// A record whose reset does not beat it is the same wall, and re-arming
    /// would fire straight back into the limit in a loop (T3's model: the
    /// scheduler only ever works from a fresh persisted limit record).
    fn maybe_auto_arm_resume(&self, chat_id: &str, limit: &SessionLimit) {
        if !self.inner.engine_settings.get().auto_resume_limited {
            return;
        }
        let Some(resets_at) = limit.resets_at else {
            return;
        };
        if !self.inner.scheduled_resumes.advances(chat_id, resets_at) {
            return;
        }
        tracing::info!(
            chat = %chat_id,
            resets_at,
            "auto-resume limited chats: arming the continuation"
        );
        self.arm_scheduled_resume(chat_id, resets_at);
    }

    /// One continuation's timer: wait out the delay plus the remaining time
    /// to `fire_at`, then hand the firing to the engine — unless the entry
    /// was replaced or cancelled while it slept (the flag). `consume`
    /// re-checks under the store's lock either way, so an obsolete timer can
    /// never fire.
    fn spawn_resume_timer(
        &self,
        chat_id: String,
        fire_at: i64,
        disarmed: Arc<AtomicBool>,
        delay: std::time::Duration,
    ) {
        let engine = self.clone();
        tokio::spawn(async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            // Clock skew and a reset time minted on a lagging client clamp
            // to "due now": never a panic, never a negative sleep.
            let target = std::time::SystemTime::UNIX_EPOCH
                + std::time::Duration::from_millis(fire_at.max(0) as u64);
            tokio::time::sleep_until(tokio::time::Instant::from_std(target)).await;
            if disarmed.load(std::sync::atomic::Ordering::Acquire) {
                return;
            }
            engine.fire_scheduled_resume(&chat_id, fire_at).await;
        });
    }

    /// Fire one armed continuation: send the continue prompt exactly like
    /// the manual Resume — a normal run, the queued messages behind it.
    /// Owned by the timer task spawned in [`Self::spawn_resume_timer`].
    async fn fire_scheduled_resume(&self, chat_id: &str, fire_at: i64) {
        // A shutdown that began before the timer woke leaves the record
        // armed for the next boot — shutting down never consumes one.
        if self
            .inner
            .shutting_down
            .load(std::sync::atomic::Ordering::Acquire)
        {
            return;
        }
        // Consume BEFORE anything async: the record is gone before the run
        // starts, so a crash mid-fire can never double-schedule it.
        if !self.inner.scheduled_resumes.consume(chat_id, fire_at) {
            return; // cancelled or replaced while this timer slept
        }
        // The chat fired this continuation — the loop guard's progress
        // floor (a new limit record must beat it to re-arm).
        self.inner.scheduled_resumes.note_fired(chat_id, fire_at);
        self.inner.set_scheduled_resume_row(chat_id, None);
        // A shutdown that raced the consume: put the record back — the next
        // boot fires it overdue instead of losing the armed continuation.
        if self
            .inner
            .shutting_down
            .load(std::sync::atomic::Ordering::Acquire)
        {
            self.inner.scheduled_resumes.requeue(chat_id, fire_at);
            return;
        }
        // A turn already in flight is the user's newer intent — its send
        // cancelled this continuation on the way in and the timer raced
        // the cancellation. Ours loses.
        if self.turn_in_flight(chat_id) {
            tracing::info!(
                chat = %chat_id,
                "scheduled resume dropped: a turn is already in flight"
            );
            return;
        }
        let Some(host) = self.inner.doc_host() else {
            tracing::warn!(chat = %chat_id, "scheduled resume dropped: no doc host");
            return;
        };
        // The chat row is gone — deleted here or on another device (the
        // row syncs through the workspace doc), or swept with its space —
        // or the chat was archived, possibly from another device whose
        // sync never ran the archive RPC here: the continuation dies with
        // the chat either way, and archived work must not resume on its
        // own overnight. A bare host with no workspace to ask falls
        // through to the last-request configuration below.
        let chat = host.workspace().and_then(|ws| ws.chat(chat_id).ok());
        match chat {
            Some(None) => {
                tracing::info!(chat = %chat_id, "scheduled resume dropped: the chat is gone");
                return;
            }
            Some(Some(chat)) if chat.archived => {
                tracing::info!(chat = %chat_id, "scheduled resume dropped: the chat is archived");
                return;
            }
            _ => {}
        }
        // The continuation runs on the chat's own configuration, like the
        // completion-notice turn: the chat row when there is one, else the
        // last live request. `dispatch` re-derives the harness session, so
        // the continuation continues the same harness conversation.
        let prompt = roboco_proto::RESUME_CONTINUE_PROMPT;
        let request = host.request_from_chat_row(chat_id, prompt).or_else(|| {
            self.last_request(chat_id).map(|mut request| {
                request.prompt = prompt.to_string();
                // Dispatch re-injects the chat's remembered harness session —
                // never carry a stale resume ref into the continuation.
                request.resume = None;
                request.attachments = Vec::new();
                request
            })
        });
        let Some(request) = request else {
            tracing::warn!(
                chat = %chat_id,
                "scheduled resume dropped: no run configuration for the chat"
            );
            return;
        };
        let harness = host.harness_for_request(chat_id, &request);
        // Deterministic id: a re-fire after a crash between consume and
        // persistence lands as the same transcript entry (dedupe by id).
        let message_id = format!("resume-at-reset:{fire_at}:{chat_id}");
        tracing::info!(chat = %chat_id, fire_at, "scheduled resume firing");
        match host
            .dispatch_with_source_context(
                self,
                chat_id,
                harness,
                request,
                Some(message_id),
                None,
            )
            .await
        {
            Ok(_) => {
                // The continuation is the explicit prompt that thaws the
                // queue the limited chat holds — the same deliberate action
                // the manual Resume's run is (queued messages follow it).
                if let Ok(handle) = host.open(chat_id) {
                    handle.thaw_queue();
                }
            }
            Err(err) => {
                if self
                    .inner
                    .shutting_down
                    .load(std::sync::atomic::Ordering::Acquire)
                {
                    // The engine went down under the dispatch: requeue for
                    // the next boot rather than drop the armed continuation.
                    self.inner.scheduled_resumes.requeue(chat_id, fire_at);
                }
                tracing::warn!(
                    chat = %chat_id,
                    error = %err,
                    "scheduled resume turn failed to start"
                );
            }
        }
    }

    /// Graceful shutdown: interrupt every live run so streaming entries settle.
    pub async fn shutdown(&self) {
        self.inner
            .shutting_down
            .store(true, std::sync::atomic::Ordering::Release);
        let chats: Vec<String> = lock(&self.inner.runs).keys().cloned().collect();
        for chat_id in chats {
            if let Err(err) = self.terminate(&chat_id).await {
                tracing::warn!(chat = %chat_id, error = %err, "shutdown interrupt failed");
            }
        }
    }

    fn is_live(&self, chat_id: &str, run_id: &str) -> bool {
        lock(&self.inner.runs)
            .get(chat_id)
            .is_some_and(|h| h.run_id == run_id)
    }

    fn set_status(&self, chat_id: &str, status: SessionStatus, fresh_start: bool) {
        self.inner.set_status(chat_id, status, fresh_start);
    }
}

impl Inner {
    /// Journal + broadcast one event (the two unconditional legs of the pipeline).
    /// Sanitize before ANY journal/publish path, including nested subagent events.
    async fn prepare_generated_image(
        &self,
        chat_id: &str,
        event: AgentEvent,
        seen: &mut std::collections::HashSet<String>,
    ) -> Vec<AgentEvent> {
        let mut parents = Vec::new();
        let mut leaf = event;
        while let AgentEvent::Subagent {
            parent_tool_use_id,
            event,
        } = leaf
        {
            parents.push(parent_tool_use_id);
            leaf = *event;
        }
        let events = if let AgentEvent::GeneratedImage { id, path, .. } = leaf {
            let key = std::iter::once(chat_id)
                .chain(parents.iter().map(String::as_str))
                .chain(std::iter::once(id.as_str()))
                .collect::<Vec<_>>()
                .join("\0");
            if seen.contains(&key) {
                return vec![];
            }
            let result = if let Some((uploads, allowed_root)) = self.generated_images.get() {
                let uploads = uploads.clone();
                let root = allowed_root.clone();
                let stable_key = key.clone();
                tokio::task::spawn_blocking(move || {
                    uploads.import_generated_image(std::path::Path::new(&path), &root, &stable_key)
                })
                .await
                .unwrap_or_else(|err| Err(EngineError::Other(err.to_string())))
            } else {
                Err(EngineError::Other(
                    "Generated image intake is not configured".into(),
                ))
            };
            match result {
                Ok(image) => {
                    seen.insert(key);
                    vec![AgentEvent::GeneratedImage {
                        id,
                        path: image.path,
                        name: image.name,
                        mime_type: image.mime_type,
                    }]
                }
                Err(err) => {
                    tracing::warn!(chat = %chat_id, error = %err, "generated image import failed");
                    vec![
                        AgentEvent::ToolResult {
                            id: id.strip_suffix(":image").unwrap_or(&id).into(),
                            is_error: true,
                            output: None,
                            diff: None,
                        },
                        AgentEvent::Error {
                            message: "Generated image unavailable".into(),
                            kind: None,
                        },
                    ]
                }
            }
        } else {
            vec![leaf]
        };
        events
            .into_iter()
            .map(|mut event| {
                for parent in parents.iter().rev() {
                    event = AgentEvent::Subagent {
                        parent_tool_use_id: parent.clone(),
                        event: Box::new(event),
                    };
                }
                event
            })
            .collect()
    }

    /// A run failed with ticket 05's structured signed-out error: mark the
    /// harness's live login's account signed out (ticket 08) — the account
    /// every new run on this harness would use, so the account switcher
    /// stops showing it as healthy. Best-effort by design: a marking failure
    /// is logged and never disturbs the run's own error path, and the next
    /// sign-in or successful usage probe clears the mark.
    async fn note_run_signed_out(&self, chat_id: &str, harness: HarnessId) {
        let Some(accounts) = lock(&self.agent_accounts).clone() else {
            return;
        };
        if let Err(err) = accounts.mark_live_signed_out(harness).await {
            tracing::warn!(
                chat = %chat_id,
                provider = ?harness,
                error = %err,
                "could not mark the harness account signed out"
            );
        }
    }

    fn publish(&self, chat_id: &str, event: &AgentEvent) -> u64 {
        let seq = match self.journal.append(chat_id, event) {
            Ok(seq) => seq,
            Err(err) => {
                tracing::error!(chat = %chat_id, error = %err, "journal append failed");
                0
            }
        };
        if let Some(hub) = lock(&self.hubs).get(chat_id) {
            let _ = hub.send(JournaledEvent {
                seq,
                event: event.clone(),
            });
        }
        seq
    }

    /// Bump the session's freshness on stream activity WITHOUT a status
    /// transition. Long silent-LOOKING stretches (thinking heartbeats, a big
    /// tool input being generated) still carry events — the UI's 45s
    /// staleness gate must not flip "Working" off mid-run. Throttled: a
    /// workspace-doc mirror per delta would be far too chatty.
    fn touch_session(&self, chat_id: &str) {
        const TOUCH_THROTTLE_MS: i64 = 10_000;
        let now = Utc::now();
        let session = {
            let mut statuses = lock(&self.statuses);
            let Some(entry) = statuses.get_mut(chat_id) else {
                return;
            };
            let age = now
                .signed_duration_since(entry.updated_at)
                .num_milliseconds();
            if age < TOUCH_THROTTLE_MS {
                return;
            }
            entry.updated_at = now;
            let session = entry.clone();
            let mut list: Vec<Session> = statuses.values().cloned().collect();
            list.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
            self.sessions_tx.send_replace(list);
            session
        };
        if let Some(ws) = self.workspace() {
            ws.record_session(&session);
        }
    }

    fn set_status(&self, chat_id: &str, status: SessionStatus, fresh_start: bool) {
        self.set_status_with_completion(chat_id, status, fresh_start, None);
    }

    /// Complete a usage-limit record for a turn that ended on one: which
    /// harness account it hit (the active login — resolved when the turn
    /// settles; warm runtimes keep the token they started with, so that is
    /// the login the run used in practice), and — when the harness's own
    /// error carried no reset time — the fill from that account: the window
    /// whose LABEL matches the limit's (Codex's meters name the driver's
    /// windows; Claude's do not), else the account's `limited_until` (the
    /// latest reset among its exhausted windows — ticket 08's derivation,
    /// one shared meaning for "the account is limited until"). Engines
    /// without an accounts store (bare tests) record without either.
    /// `None` when the classification was not a usage limit.
    async fn limit_record(
        &self,
        harness: HarnessId,
        kind: AgentErrorKind,
    ) -> Option<SessionLimit> {
        let AgentErrorKind::UsageLimit { window, resets_at } = kind else {
            return None;
        };
        let accounts = lock(&self.agent_accounts).clone();
        let active = match accounts {
            Some(accounts) => accounts.active_account(harness).await,
            None => None,
        };
        let resets_at = resets_at.or_else(|| {
            let account = active.as_ref()?;
            // Same window, when the account's meters name it.
            let by_label = account
                .usage_windows
                .iter()
                .find(|w| w.resets_at.is_some() && w.label.eq_ignore_ascii_case(&window));
            by_label
                .and_then(|w| w.resets_at)
                .or(account.limited_until)
                .map(|at| at.timestamp_millis())
        });
        Some(SessionLimit {
            harness,
            window,
            resets_at,
            account_id: active.map(|account| account.id),
        })
    }

    fn has_pending_steers(&self, chat_id: &str, run_id: &str) -> bool {
        lock(&self.runs)
            .get(chat_id)
            .filter(|h| h.run_id == run_id)
            .is_some_and(|h| !lock(&h.routed_steers).is_empty())
    }

    /// Publish the completion marker atomically with the settled status. Keep
    /// it on subsequent Working/heartbeat rows for coalesced and remote watches.
    fn set_status_with_completion(
        &self,
        chat_id: &str,
        status: SessionStatus,
        fresh_start: bool,
        completed_turn: Option<String>,
    ) {
        self.set_status_with_limit(chat_id, status, fresh_start, completed_turn, None);
    }

    /// The full status transition: everything above, plus the usage-limit
    /// record a limited turn ends with. `None` CLEARS any previous record —
    /// the marker lives exactly as long as the errored status that recorded
    /// it, so a new turn (any Working transition) or any other settled
    /// outcome drops it. Only the run task's limit-errored exit passes
    /// `Some`.
    fn set_status_with_limit(
        &self,
        chat_id: &str,
        status: SessionStatus,
        fresh_start: bool,
        completed_turn: Option<String>,
        limit: Option<SessionLimit>,
    ) {
        let now = Utc::now();
        let session = {
            let mut statuses = lock(&self.statuses);
            let entry = statuses
                .entry(chat_id.to_string())
                .or_insert_with(|| Session {
                    last_completed_turn: None,
                    chat_id: chat_id.to_string(),
                    device_id: self.device_id.clone(),
                    status,
                    started_at: None,
                    updated_at: now,
                    limit: None,
                    scheduled_resume: None,
                });
            // `started_at` is the elapsed-timer base and must only ever mean
            // "this turn". Entering Working from a settled state always
            // restamps (a steer into a parked session is a NEW turn — reusing
            // the old base showed the previous turn's 30min on send), and a
            // settled session drops its base entirely so no later reader can
            // resurrect a stale elapsed.
            let was_active = matches!(
                entry.status,
                SessionStatus::Working | SessionStatus::AwaitingInput
            );
            if let Some(turn) = completed_turn {
                entry.last_completed_turn = Some(turn);
            }
            entry.status = status;
            entry.updated_at = now;
            entry.limit = limit;
            match status {
                SessionStatus::Working if fresh_start || !was_active => {
                    entry.started_at = Some(now);
                }
                SessionStatus::Working | SessionStatus::AwaitingInput => {}
                SessionStatus::Idle | SessionStatus::Errored => {
                    entry.started_at = None;
                }
            }
            let session = entry.clone();
            let mut list: Vec<Session> = statuses.values().cloned().collect();
            list.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
            // send_replace: keep the current value fresh even with no receivers,
            // so late WatchSessions subscribers see the last transition.
            self.sessions_tx.send_replace(list);
            session
        };
        // Mirror the transition into the workspace doc's session-status row so
        // remote devices' sidebars show this run (staleness-checked client-side).
        if let Some(ws) = self.workspace() {
            ws.record_session(&session);
        }
    }

    /// Publish the chat's pending Resume-at-reset continuation onto its
    /// status row (arming and cancellation both land here), leaving the
    /// status itself untouched, and mirror it into the workspace doc so
    /// remote devices' banners see it. No row yet — nothing armed, nothing
    /// to clear; the arm paths only ever run for a chat with a row.
    fn set_scheduled_resume_row(&self, chat_id: &str, scheduled: Option<ScheduledResume>) {
        if let Some(session) = self.set_scheduled_resume_row_local(chat_id, scheduled) {
            if let Some(ws) = self.workspace() {
                ws.record_session(&session);
            }
        }
    }

    /// [`set_scheduled_resume_row`] without the workspace mirror: the
    /// in-memory row and the watch list only. The delete paths use this —
    /// they are deleting the chat's sessions row, which a mirror would
    /// resurrect. Returns the updated row when there was one.
    fn set_scheduled_resume_row_local(
        &self,
        chat_id: &str,
        scheduled: Option<ScheduledResume>,
    ) -> Option<Session> {
        let now = Utc::now();
        let mut statuses = lock(&self.statuses);
        let entry = statuses.get_mut(chat_id)?;
        entry.scheduled_resume = scheduled;
        entry.updated_at = now;
        let session = entry.clone();
        let mut list: Vec<Session> = statuses.values().cloned().collect();
        list.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
        self.sessions_tx.send_replace(list);
        Some(session)
    }

    /// The doc host, once wired. `None` before assembly or after retirement.
    fn doc_host(&self) -> Option<DocHost> {
        lock(&self.doc_host).clone()
    }

    /// A message the user already sent follows the turn that is ending
    /// (see [`crate::doc_host::ChatDocHandle::continues_with_sent_message`]).
    fn continues_with_sent_message(&self, chat_id: &str) -> bool {
        self.doc_host()
            .and_then(|host| host.open(chat_id).ok())
            .is_some_and(|handle| handle.continues_with_sent_message())
    }

    /// Roboco's own MCP server for a run of `chat_id`: this binary's `roboco
    /// mcp` subcommand, dialing the engine's IPC port and stamped with the
    /// originating chat + device so the agent's side chats link back here.
    /// None when the engine serves no port or its executable is unknown.
    fn roboco_mcp(&self, chat_id: &str) -> Option<roboco_proto::McpServer> {
        let port = self.ipc_port.load(std::sync::atomic::Ordering::Relaxed);
        if port == 0 {
            return None;
        }
        let command = std::env::current_exe().ok()?.to_str()?.to_owned();
        Some(roboco_proto::McpServer {
            name: "roboco".into(),
            command,
            args: vec!["mcp".into()],
            env: [
                ("ROBOCO_IPC_PORT".to_owned(), port.to_string()),
                ("ROBOCO_CHAT_ID".to_owned(), chat_id.to_owned()),
                ("ROBOCO_DEVICE_ID".to_owned(), self.device_id.clone()),
            ]
            .into_iter()
            .collect(),
        })
    }

    fn workspace(&self) -> Option<crate::workspace_host::WorkspaceHost> {
        self.doc_host().and_then(|host| host.workspace().cloned())
    }

    /// Sidebar freshness: push a message-persist preview into the chat's workspace row.
    fn note_message(&self, chat_id: &str, text: &str) {
        if text.is_empty() {
            return;
        }
        if let Some(ws) = self.workspace() {
            ws.note_message(chat_id, text);
        }
    }

    /// Record the chat's harness-native session id (and its cwd): live-process
    /// cache plus the durable workspace chat row — the row is what survives an
    /// engine restart (roboco sessions.ts:1039).
    fn remember_harness_session(&self, chat_id: &str, session_id: &str, cwd: &str) {
        if session_id.is_empty() {
            return;
        }
        lock(&self.harness_sessions).insert(
            chat_id.to_string(),
            HarnessSessionRef {
                session_id: session_id.to_string(),
                cwd: cwd.to_string(),
            },
        );
        if let Some(ws) = self.workspace() {
            ws.set_chat_harness_session(chat_id, session_id, cwd);
        }
    }

    // NB: there is deliberately no `forget_harness_session` anymore. The old
    // tombstone fired on "run died before SessionStarted", which — since the
    // ACP conversion made stale ids a harness-internal fallback — only ever
    // meant a child STARTUP failure, and permanently severed good
    // conversations (user incident 2026-08-13). A truly stale id simply
    // yields a fresh session whose SessionStarted overwrites the row.

    /// The session id to resume for a run in `chat_id` launching from `cwd`
    /// (roboco sessions.ts:736, looked up on every dispatch):
    /// live-process cache → workspace chat row → journal scan (the crash path
    /// where the debounced row write never landed — SessionStarted/Done events
    /// are journaled per event, flushed immediately). Cwd-gated throughout:
    /// harness session stores are keyed by cwd, so a session created elsewhere
    /// never rides `--resume`. An empty stored id is the explicit tombstone —
    /// no resume, no falling through to staler sources.
    fn resume_for(&self, chat_id: &str, cwd: &str) -> Option<String> {
        // The same directory, however it is spelled: an agent reports where
        // it runs with symlinks resolved (`/private/var/…` for `/var/…` on
        // macOS), and until a turn's Done restores the request's spelling a
        // mid-turn crash leaves that one stored — a string compare then
        // started the resumed run in a fresh session, its history gone.
        let cwd_ok = |session_cwd: &str| {
            session_cwd.is_empty()
                || session_cwd == cwd
                || std::fs::canonicalize(session_cwd)
                    .is_ok_and(|a| std::fs::canonicalize(cwd).is_ok_and(|b| a == b))
        };
        if let Some(known) = lock(&self.harness_sessions).get(chat_id).cloned() {
            return (!known.session_id.is_empty() && cwd_ok(&known.cwd))
                .then_some(known.session_id);
        }
        if let Some(ws) = self.workspace()
            && let Some((session_id, session_cwd)) = ws.chat_harness_session(chat_id)
        {
            return (!session_id.is_empty() && cwd_ok(session_cwd.as_deref().unwrap_or("")))
                .then_some(session_id);
        }
        let (session_id, session_cwd) = self.journal_harness_session(chat_id)?;
        // Cache the journal hit (memory + row) so later dispatches skip the scan.
        self.remember_harness_session(chat_id, &session_id, &session_cwd);
        cwd_ok(&session_cwd).then_some(session_id)
    }

    /// The last harness session id named anywhere in the chat's journal, with
    /// the cwd of the `SessionStarted` that governs it. `Done.session_id`
    /// inherits the cwd of the most recent `SessionStarted` (same run).
    ///
    /// Walks the journal newest-first and stops at the answer, so boot recovery
    /// doesn't load whole multi-MB journals: the newest non-empty id wins, and a
    /// `Done` id takes the cwd of the nearest earlier `SessionStarted` (any id).
    fn journal_harness_session(&self, chat_id: &str) -> Option<(String, String)> {
        let warn = |err: crate::run_journal::JournalError| {
            tracing::warn!(chat = %chat_id, error = %err, "journal scan for harness session failed");
        };
        let events = match self.journal.events_rev(chat_id) {
            Ok(events) => events,
            Err(err) => {
                warn(err);
                return None;
            }
        };
        // A `Done` id seen first, still waiting for its run's `SessionStarted` cwd.
        let mut done_id: Option<String> = None;
        for item in events {
            let (seq, event) = match item {
                Ok(item) => item,
                Err(err) => {
                    warn(err);
                    return None;
                }
            };
            if seq == 0 {
                continue; // `replay(_, 0)` (the old full scan) never yielded seq 0
            }
            match event {
                AgentEvent::SessionStarted {
                    session_id, cwd, ..
                } => {
                    if let Some(id) = done_id {
                        return Some((id, cwd));
                    }
                    if !session_id.is_empty() {
                        return Some((session_id, cwd));
                    }
                }
                AgentEvent::Done {
                    session_id: Some(session_id),
                    ..
                } if done_id.is_none() && !session_id.is_empty() => {
                    done_id = Some(session_id);
                }
                _ => {}
            }
        }
        done_id.map(|id| (id, String::new()))
    }

    fn remove_run(&self, chat_id: &str, run_id: &str) {
        let mut runs = lock(&self.runs);
        if runs.get(chat_id).is_some_and(|h| h.run_id == run_id) {
            runs.remove(chat_id);
        }
    }
}

// ── run task ────────────────────────────────────────────────────────────────

/// Which provider session a side chat's prompt goes to.
#[derive(Clone, Copy)]
enum ProviderSession<'a> {
    /// A new provider session: it holds none of the transcript.
    Fresh,
    /// An existing one (id when known): it holds the fork's own turns.
    Continued(Option<&'a str>),
}

/// A run's provider session now holds the fork history its own prompt
/// carried (steers record theirs when confirmed, not here).
fn note_fork_history_session(doc: &SessionDoc, carried: bool, session_id: &str) {
    if carried && !session_id.is_empty() {
        let _ = doc.set_fork_history_session(session_id);
    }
}

impl Inner {
    /// `prompt` with the transcript a provider session lacks in front of it,
    /// or `None` when it lacks nothing. A side chat's fresh session gets its
    /// budgeted copied transcript (`current` excluded); a main chat's fresh
    /// session gets the budgeted transcript only after a harness switch (the
    /// seam marks what the dropped session held); a continued one only the
    /// history above the chat's newest seam, until that went out to that
    /// session. The budget and the selection rules live in
    /// [`crate::transcript_budget`]. Native commands are never wrapped —
    /// providers route a leading `/name`, so their history rides the first
    /// ordinary send instead.
    fn fork_history_prompt(
        &self,
        chat_id: &str,
        doc: &SessionDoc,
        harness_id: HarnessId,
        prompt: &str,
        current: Option<&str>,
        provider: ProviderSession<'_>,
    ) -> Option<String> {
        self.fork_history_prefix(chat_id, doc, harness_id, prompt, current, provider)
            .map(|prefix| format!("{prefix}{prompt}"))
    }

    /// The transcript prefix [`Self::fork_history_prompt`] would deliver —
    /// the budgeted selection alone, without the prompt appended. The
    /// native-fork path (ticket 17) rides this as the request's fallback
    /// history: the driver pastes it in front of the prompt only when the
    /// harness refuses the fork, so a successful fork delivers nothing.
    fn fork_history_prefix(
        &self,
        chat_id: &str,
        doc: &SessionDoc,
        harness_id: HarnessId,
        prompt: &str,
        current: Option<&str>,
        provider: ProviderSession<'_>,
    ) -> Option<String> {
        if native_command(prompt, harness_id) {
            return None;
        }
        let entries: Vec<_> = doc
            .read_entries()
            .unwrap_or_default()
            .into_iter()
            // `current` — and any continuation fragment of it — belongs to
            // the turn being sent, never to the transcript in front of it.
            .filter(|entry| {
                !current.is_some_and(|current| {
                    entry.id == current || entry.continuation_of.as_deref() == Some(current)
                })
            })
            .collect();
        // A side chat owes its copied transcript to any session that lacks
        // it. A main chat owes its transcript only after a one-way harness
        // switch (t3-orchestration-v2 ticket 24): the seam marks the
        // conversation the new harness's fresh session never saw, so the
        // first send after the switch carries the budgeted history of
        // everything above it; without a seam a main chat's fresh session
        // starts empty (nothing was copied, nothing was dropped).
        let side_chat = self
            .workspace()
            .and_then(|ws| ws.chat(chat_id).ok().flatten())
            .is_some_and(|chat| chat.parent_chat_id.is_some());
        if !side_chat && !entries.iter().any(is_harness_switch_seam) {
            return None;
        }
        let end = match provider {
            ProviderSession::Fresh => entries.len(),
            ProviderSession::Continued(session) => {
                let delivered = doc.fork_history_session();
                if session.is_some() && delivered.as_deref() == session {
                    return None;
                }
                // The owed unit ends at the NEWEST seam, whichever kind: a
                // fork's copied transcript above its seam, or the dropped
                // conversation above a harness-switch seam (ticket 24). A
                // session that changed below the newest seam lacks
                // everything above it — including the case where the first
                // post-switch send was a native command (never wrapped), so
                // the history rides the first ordinary send instead.
                entries.iter().rposition(is_history_seam)?
            }
        };
        // The chat the omitted messages are readable in: the newest fork
        // seam names the chat this transcript was copied from (its
        // transcript holds everything above the seam); a seam-less side
        // chat points at itself.
        let source = entries
            .iter()
            .rev()
            .find_map(fork_seam_source)
            .unwrap_or_else(|| chat_id.to_owned());
        crate::transcript_budget::budgeted_transcript_prefix(&entries[..end], Some(&source))
    }
}

/// A fork seam entry: the transcript divider above which messages were
/// copied from another chat.
fn is_fork_seam(entry: &SessionMessageEntry) -> bool {
    entry
        .parts
        .iter()
        .any(|part| matches!(part, MessagePart::Fork { .. }))
}

/// A history seam of either kind: the divider below which the chat's
/// provider session started fresh — a fork's copy boundary or a harness
/// switch's drop boundary. The newest one bounds what a changed session is
/// owed.
fn is_history_seam(entry: &SessionMessageEntry) -> bool {
    is_fork_seam(entry) || is_harness_switch_seam(entry)
}

/// A harness-switch seam entry (t3-orchestration-v2 ticket 24): the divider
/// below which the chat runs on the harness the switch named. Everything
/// above it belongs to the provider session the switch dropped, so it is
/// the history the new harness's first session is owed.
fn is_harness_switch_seam(entry: &SessionMessageEntry) -> bool {
    entry
        .parts
        .iter()
        .any(|part| matches!(part, MessagePart::HarnessSwitch { .. }))
}

/// The chat a fork seam copied its transcript from.
fn fork_seam_source(entry: &SessionMessageEntry) -> Option<String> {
    entry
        .parts
        .iter()
        .find_map(|part| match part {
            MessagePart::Fork { source_chat_id, .. } => Some(source_chat_id.clone()),
            _ => None,
        })
}

/// Whether the provider routes this prompt as a native command: its delivered
/// text leads with `/name` (Codex `command_request`, OpenCode commands, Claude
/// slash commands). Anything put in front of it would make it model input.
fn native_command(prompt: &str, harness: HarnessId) -> bool {
    let delivered = if harness == HarnessId::Codex {
        roboco_proto::invocation::invocation_prompt(prompt)
    } else {
        roboco_proto::invocation::harness_prompt(prompt, harness)
    };
    roboco_proto::invocation::leading_command(&delivered).is_some()
}

/// A turn is in flight: streaming, or parked on a question it is still owed an
/// answer to. Notably NOT a persistent session that has parked between turns —
/// that holds a warm child with nothing outstanding.
fn is_active(session: &Session) -> bool {
    matches!(
        session.status,
        SessionStatus::Working | SessionStatus::AwaitingInput
    )
}

// ── subagent docs ───────────────────────────────────────────────────────────

/// The per-subagent doc id: `{chatId}--sub--{suffix}`. Constrained by the
/// edge's `ID_RE` (`^[A-Za-z0-9_-]{1,128}$` — the same id names the ChatRoom
/// `chat2/{id}/ws` and the frozen blob `blob/{chatId}/{id}`): a clean, short
/// tool-use id rides verbatim; anything unclean or over budget hashes.
pub(crate) fn subagent_doc_id(chat_id: &str, tool_use_id: &str) -> String {
    let clean = tool_use_id
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
    let budget = 128usize.saturating_sub(chat_id.len() + "--sub--".len());
    if clean && !tool_use_id.is_empty() && tool_use_id.len() <= budget {
        return format!("{chat_id}--sub--{tool_use_id}");
    }
    use sha2::Digest as _;
    let digest = sha2::Sha256::digest(tool_use_id.as_bytes());
    let mut hex = String::with_capacity(16);
    for b in &digest[..8] {
        use std::fmt::Write as _;
        let _ = write!(hex, "{b:02x}");
    }
    format!("{chat_id}--sub--{hex}")
}

/// Test-only instrumentation: how often `drive_run`'s coalesced commit branch
/// fires. The sink-dirty regression test proves the branch ticks once per
/// commit window instead of spinning on a deadline left in the past.
/// Thread-local so parallel tests' runs can't bleed into the count: the
/// test's current-thread runtime runs its spawned `drive_run` on its own
/// thread.
#[cfg(test)]
thread_local! {
    static FLUSH_TICKS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// A live subagent transcript sink: its own doc (opened by id — the room
/// `chat2/{docId}/ws` dials automatically, so viewers sync it like a chat),
/// one streaming assistant entry folded from the tagged events. The held
/// doc Arc pins the doc warm for the LRU while the subagent runs.
struct SubagentSink {
    doc_id: String,
    doc: Arc<SessionDoc>,
    entry_id: String,
    started_at: i64,
    entry_index: Option<usize>,
    written: Vec<MessagePart>,
    folded: Vec<MessagePart>,
    dirty: bool,
}

impl SubagentSink {
    fn flush(&mut self, device_id: &str) {
        if !self.dirty {
            return;
        }
        // An empty fold still counts as flushed: leaving dirty set would hold
        // the run loop's coalesced-commit guard open on a deadline already in
        // the past, spinning it until the subagent emits real content.
        self.dirty = false;
        if self.folded.is_empty() {
            return;
        }
        let rendered = render_parts(&self.folded);
        let result = match self.entry_index {
            Some(ix) => {
                let mut w = SegmentWriter::resume(&self.doc, ix, std::mem::take(&mut self.written));
                let r = w.sync(&rendered);
                let (ix, written) = w.into_state();
                self.entry_index = Some(ix);
                self.written = written;
                r
            }
            None => {
                match SegmentWriter::begin(&self.doc, &self.entry_id, device_id, self.started_at) {
                    Ok(mut w) => {
                        let r = w.sync(&rendered);
                        let (ix, written) = w.into_state();
                        self.entry_index = Some(ix);
                        self.written = written;
                        r
                    }
                    Err(e) => Err(e),
                }
            }
        };
        if let Err(err) = result {
            // Fail soft: a broken subagent doc degrades to chip-only, never
            // errors the chat.
            tracing::warn!(doc = %self.doc_id, error = %err, "subagent sink flush failed");
        }
    }

    /// A parent→subagent steer ([`AgentEvent::UserMessage`], tagged): close
    /// the open assistant segment (it reads Complete above the steer), write
    /// the steer as its own USER entry, and reset so the next tagged delta
    /// opens a fresh assistant entry below it — the subagent transcript then
    /// reads like any steered chat.
    fn push_user(&mut self, device_id: &str, text: &str) {
        let rendered = render_parts(&self.folded);
        let closed = match self.entry_index.take() {
            Some(ix) => SegmentWriter::resume(&self.doc, ix, std::mem::take(&mut self.written))
                .finish(&rendered, MessageStatus::Complete),
            None if !self.folded.is_empty() => {
                match SegmentWriter::begin(&self.doc, &self.entry_id, device_id, self.started_at) {
                    Ok(w) => w.finish(&rendered, MessageStatus::Complete),
                    Err(e) => Err(e),
                }
            }
            None => Ok(()),
        };
        if let Err(err) = closed {
            tracing::warn!(doc = %self.doc_id, error = %err, "subagent segment close failed");
        }
        let entry = SessionMessageEntry {
            origin_chat_id: None,
            id: new_id(),
            role: MessageRole::User,
            parts: vec![MessagePart::Text {
                id: "t0".into(),
                text: text.to_owned(),
            }],
            created_at: now_ms(),
            device_id: device_id.to_owned(),
            status: Some(MessageStatus::Complete),
            continuation_of: None,
            duration_ms: None,
            provider_turn: None,
        };
        if let Err(err) = self.doc.push_message(&entry) {
            tracing::warn!(doc = %self.doc_id, error = %err, "subagent steer write failed");
        }
        self.entry_id = new_id();
        self.started_at = now_ms();
        self.written = Vec::new();
        self.folded = Vec::new();
        self.dirty = false;
    }

    /// Final flush + entry finalize; returns the transcript JSON for the
    /// frozen blob (entries as the client renders them).
    fn finish(mut self, device_id: &str, status: MessageStatus) -> Option<String> {
        let rendered = render_parts(&self.folded);
        let finished = match self.entry_index {
            Some(ix) => SegmentWriter::resume(&self.doc, ix, std::mem::take(&mut self.written))
                .finish(&rendered, status),
            None if !self.folded.is_empty() => {
                match SegmentWriter::begin(&self.doc, &self.entry_id, device_id, self.started_at) {
                    Ok(w) => w.finish(&rendered, status),
                    Err(e) => Err(e),
                }
            }
            None => Ok(()),
        };
        if let Err(err) = finished {
            tracing::warn!(doc = %self.doc_id, error = %err, "subagent sink finish failed");
        }
        let entries = roboco_doc::join_continuation_entries(self.doc.read_entries().ok()?);
        serde_json::to_string(&entries).ok()
    }
}

/// The parent-chip refresh a tagged event implies, for the in-place (parked)
/// path — LIFECYCLE ONLY, mirroring the fold (live tails were rejected:
/// per-delta chip rewrites grew the parent doc for the whole run).
fn subagent_chip_update(event: &AgentEvent) -> Option<&'static str> {
    match event {
        AgentEvent::Done { status, .. } => Some(match status {
            DoneStatus::Errored => "failed",
            _ => "done",
        }),
        _ => Some("running"),
    }
}

/// Apply the render-parts privacy policy: strip heavy/sensitive tool inputs before doc
/// entry. Full inputs live only in the local run journal.
fn render_parts(parts: &[MessagePart]) -> Vec<MessagePart> {
    parts
        .iter()
        .map(|part| match part {
            MessagePart::Tool {
                id,
                call,
                is_error,
                resolved,
                output,
                diff,
                output_ref,
                output_bytes,
                diff_ref,
                diff_stats,
                subagent_ref,
                subagent_status,
                subagent_tail,
            } => MessagePart::Tool {
                id: id.clone(),
                call: sanitize_tool_call(call),
                is_error: *is_error,
                resolved: *resolved,
                // Output summaries, diff stats, and sidecar refs are
                // deliberately kept: unlike raw tool inputs they are the
                // transcript's record of what happened, and the strip already
                // bounded them (docs/chat2-sync.md A1).
                output: output.clone(),
                diff: diff.clone(),
                output_ref: output_ref.clone(),
                output_bytes: *output_bytes,
                diff_ref: diff_ref.clone(),
                diff_stats: diff_stats.clone(),
                subagent_ref: subagent_ref.clone(),
                subagent_status: *subagent_status,
                subagent_tail: subagent_tail.clone(),
            },
            other => other.clone(),
        })
        .collect()
}

/// The persisted assistant text of a folded segment (workspace preview source).
fn folded_text(parts: &[MessagePart]) -> String {
    parts
        .iter()
        .filter_map(|part| match part {
            MessagePart::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn sync_segment<'a>(
    doc: &'a SessionDoc,
    writer: &mut Option<SegmentWriter<'a>>,
    entry_id: &str,
    device_id: &str,
    started_at: i64,
    folded: &[MessagePart],
) -> Result<(), DocError> {
    if folded.is_empty() {
        return Ok(());
    }
    let rendered = render_parts(folded);
    if writer.is_none() {
        *writer = Some(SegmentWriter::begin(doc, entry_id, device_id, started_at)?);
    }
    if let Some(w) = writer.as_mut() {
        w.sync(&rendered)?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn finish_segment<'a>(
    doc: &'a SessionDoc,
    writer: Option<SegmentWriter<'a>>,
    entry_id: &str,
    device_id: &str,
    started_at: i64,
    folded: &[MessagePart],
    status: MessageStatus,
    turn_ref: Option<&roboco_proto::ProviderTurnRef>,
) -> Result<(), DocError> {
    let rendered = render_parts(folded);
    let outcome = match writer {
        Some(w) => w.finish(&rendered, status),
        None if !folded.is_empty() => {
            SegmentWriter::begin(doc, entry_id, device_id, started_at)?.finish(&rendered, status)
        }
        None => Ok(()),
    }?;
    // The provider turn the finished entry belongs to (ticket 17): stamped
    // after the write, a no-op for an entry that never streamed. A failure
    // here costs the ref (a fork falls back to the budgeted paste), never
    // the segment.
    if let Some(reference) = turn_ref
        && let Err(err) = doc.stamp_provider_turn(entry_id, reference)
    {
        tracing::warn!(chat = %doc.chat_id().unwrap_or_default(), error = %err, "provider turn ref stamp failed");
    }
    Ok(outcome)
}

/// Resume bookkeeping for one run task: which user entry the run answers (so
/// the startup-crash retry re-dispatches idempotently against the same doc
/// entry), whether `dispatch` injected the resume id itself (only
/// engine-injected resumes retry — a caller-specified resume fails loudly),
/// and whether this run already IS the retry (one attempt only).
struct RunResumeState {
    user_message_id: String,
    resume_injected: bool,
    startup_retry: bool,
    fork_history_sent: Arc<std::sync::atomic::AtomicBool>,
    /// Set once the harness stream is open (see [`RunHandle::started`]).
    started: Arc<std::sync::atomic::AtomicBool>,
}

fn cursor_unstarted_history(
    doc: &SessionDoc,
    current_id: &str,
    prompt: &str,
    has_session: bool,
) -> Result<String, DocError> {
    // Convert each message before JSON encoding. Rewriting canonical chips in
    // the encoded envelope can introduce unescaped quotes or newlines and can
    // cause Cursor's current message to be converted twice.
    let prompt = roboco_proto::invocation::harness_prompt(prompt, HarnessId::Cursor);
    let entries = doc.read_entries()?;
    let preceding: Vec<_> = entries
        .iter()
        .take_while(|entry| entry.id != current_id)
        .collect();
    // With an existing session, only bridge the tail whose assistant never
    // produced content. A process can die before writing its SDK-side receipt,
    // even though an older session ID still exists.
    let after = if has_session {
        preceding
            .iter()
            .rposition(|entry| {
                entry.role == MessageRole::Assistant
                    && entry.parts.iter().any(|part| match part {
                        MessagePart::Text { text, .. } | MessagePart::Reasoning { text, .. } => {
                            !text.is_empty()
                        }
                        MessagePart::Tool { .. } | MessagePart::Input { .. } => true,
                        _ => false,
                    })
            })
            .map_or(0, |i| i + 1)
    } else {
        0
    };
    let previous: Vec<String> = preceding
        .iter()
        .skip(after)
        .filter(|entry| entry.role == MessageRole::User)
        .map(|entry| {
            entry
                .parts
                .iter()
                .filter_map(|part| match part {
                    MessagePart::Text { text, .. } => Some(text.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .filter(|text| !text.is_empty())
        .map(|text| roboco_proto::invocation::harness_prompt(&text, HarnessId::Cursor))
        .collect();
    if previous.is_empty() {
        return Ok(prompt);
    }
    Ok(format!(
        "The preceding user messages may not have reached a Cursor checkpoint before startup stopped. Retain this JSON as conversation history; do not rerun prior tools or side effects. Respond to the current message.\n{}",
        serde_json::json!({"previousUserMessages": previous, "currentUserMessage": prompt})
    ))
}

/// Whether `event` carries ticket 05's signed-out error kind — directly, or
/// tagged inside a subagent event (a subagent runs on the same login, so its
/// rejection is equally hard evidence the login is dead).
fn event_signs_out(event: &AgentEvent) -> bool {
    let leaf = match event {
        AgentEvent::Subagent { event, .. } => event.as_ref(),
        event => event,
    };
    matches!(
        leaf,
        AgentEvent::Error {
            kind: Some(AgentErrorKind::SignedOut),
            ..
        }
    )
}

/// The parent the run's CURRENT turn owes a completion notice to, read at
/// the engine's own turn-end states (orchestration v2).
fn run_notifiable_parent(inner: &Inner, chat_id: &str, run_id: &str) -> Option<String> {
    lock(&inner.runs)
        .get(chat_id)
        .filter(|h| h.run_id == run_id)
        .and_then(|h| lock(&h.notifiable).clone())
}

/// Record a completion-notice outcome, fire-and-forget: the side chat's
/// event stream never waits on the parent's notice turn. Engine teardown
/// records nothing — the runs it kills were not user-visible outcomes;
/// a restart's lost runs are covered at boot (interrupted outcomes),
/// where the crash state is durable enough to read.
fn spawn_notice_record(
    inner: Arc<Inner>,
    side_chat: String,
    parent: String,
    outcome: crate::completion_notices::Outcome,
) {
    if inner
        .shutting_down
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return;
    }
    let engine = SessionsEngine { inner };
    tokio::spawn(async move {
        crate::completion_notices::record_and_deliver(&engine, &side_chat, &parent, outcome).await;
    });
}

/// Deliver the completion notices `chat_id` is owed as a PARENT, now that
/// its turn has ended (ticket 13's busy-parent rule): the outcomes that
/// piled up while it worked were never steered in, so they start the
/// chat's next turn now. Fire-and-forget, like the records: the run loop
/// never waits on another chat's turn. Delivery re-checks `turn_in_flight`
/// (a queued user message may have claimed the boundary first — then the
/// notice waits for that turn's own end) and `shutting_down` (teardown
/// never starts recovered work). A turn ended by an interrupt is the
/// user's Stop: no wakeup — ticket 14 owns the stopped-parent rule.
fn spawn_notice_delivery(inner: &Arc<Inner>, chat_id: &str) {
    if inner
        .shutting_down
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return;
    }
    let engine = SessionsEngine {
        inner: inner.clone(),
    };
    let chat = chat_id.to_string();
    tokio::spawn(async move {
        crate::completion_notices::deliver(&engine, &chat).await;
    });
}

/// Record the outcome of a turn that settled INSIDE the run loop (a parked
/// persistent session, the quiesce watchdog): only real turn ends record —
/// an internal handoff (a pending steer or a sent message owns the
/// continuation) notifies nothing.
fn record_parked_turn(
    inner: &Arc<Inner>,
    chat_id: &str,
    run_id: &str,
    completed: bool,
    stopped_in_place: bool,
) {
    let outcome = if stopped_in_place {
        Some(crate::completion_notices::Outcome::Stopped)
    } else if completed {
        Some(crate::completion_notices::Outcome::Completed)
    } else {
        None
    };
    if let Some(outcome) = outcome
        && let Some(parent) = run_notifiable_parent(inner, chat_id, run_id)
    {
        spawn_notice_record(inner.clone(), chat_id.to_string(), parent, outcome);
    }
}

/// The outcome for the run's FINAL turn-end, from the engine's own recorded
/// state: the Done status and error, and whether the completion marker was
/// minted (a Done with a pending steer or a sent message on its way is an
/// internal handoff, not a completion).
fn final_outcome(
    status: DoneStatus,
    error: Option<&str>,
    completed_turn: bool,
) -> Option<crate::completion_notices::Outcome> {
    match status {
        DoneStatus::Completed if completed_turn => {
            Some(crate::completion_notices::Outcome::Completed)
        }
        DoneStatus::Completed => None,
        DoneStatus::Errored => Some(crate::completion_notices::Outcome::Errored {
            reason: crate::completion_notices::one_line(error),
        }),
        DoneStatus::Interrupted => Some(crate::completion_notices::Outcome::Stopped),
    }
}

#[allow(clippy::too_many_arguments)]
async fn drive_run(
    inner: Arc<Inner>,
    chat_id: String,
    run_id: String,
    harness: Arc<dyn Harness>,
    mut request: RunRequest,
    doc: Arc<SessionDoc>,
    mut controls: RunControls,
    mut engine_rx: mpsc::UnboundedReceiver<AgentEvent>,
    mut cancel_rx: watch::Receiver<bool>,
    resume_state: RunResumeState,
    turn: TurnControl,
) {
    let device_id = inner.device_id.clone();
    // Captured for post-run auto-titling (the request moves into the harness).
    let harness_id = harness.id();
    let user_prompt = request.prompt.clone();
    let run_cwd = request.cwd.clone();
    if request.resume.is_none() {
        let _ = doc.clear_context_usage();
    }
    // The host stamps its own MCP server onto every run it drives, so the
    // agent can spawn and talk to side chats through the engine it runs in.
    if request.mcp.is_none() {
        request.mcp = inner.roboco_mcp(&chat_id);
    }
    // Kept whole for the startup-crash retry (same user entry; dispatch
    // re-injects the stored resume id). Option so the retry branch (inside
    // the event loop) can take ownership. The native-fork directive spreads
    // in from `request` — a startup crash is transient, so the retry
    // re-attempts the fork and re-derives the fallback history.
    let mut retry_request = Some(RunRequest {
        resume: None,
        ..request.clone()
    });
    // A side chat owns a fresh provider session. Bootstrap it from the frozen
    // conversation, never resume (and mutate) the parent's provider session.
    let mut carried_history = false;
    if let Some(fork) = request.provider_fork.as_mut() {
        // A native fork (t3-orchestration-v2 ticket 17): the driver forks the
        // source provider session at the boundary, so the copied history
        // rides the provider and the delivered prompt carries nothing. The
        // budgeted transcript still rides the request — the driver pastes it
        // in front of the prompt on the fresh session it starts when the
        // harness refuses the fork. Either way the session this run reports
        // holds the copied history, so warm sends never re-deliver it.
        if fork.fallback_history.is_none() {
            // The Fresh selection: a session the fork refused to continue
            // knows nothing of the chat, so the fallback is the whole
            // budgeted transcript (the seam and everything after it are
            // structure, not transcript).
            fork.fallback_history = inner.fork_history_prefix(
                &chat_id,
                &doc,
                harness_id,
                &request.prompt,
                Some(&resume_state.user_message_id),
                ProviderSession::Fresh,
            );
        }
        carried_history = true;
        resume_state
            .fork_history_sent
            .store(true, std::sync::atomic::Ordering::Release);
    } else {
        let provider = match request.resume.as_deref() {
            None => ProviderSession::Fresh,
            resumed => ProviderSession::Continued(resumed),
        };
        if let Some(prompt) = inner.fork_history_prompt(
            &chat_id,
            &doc,
            harness_id,
            &request.prompt,
            Some(&resume_state.user_message_id),
            provider,
        ) {
            request.prompt = prompt;
            carried_history = true;
            resume_state
                .fork_history_sent
                .store(true, std::sync::atomic::Ordering::Release);
        }
    }
    // Startup can stop before the SDK saves user text, with no new session
    // ID or receipt. Bridge that unacknowledged tail from our transcript;
    // a fresh session needs all prior user text, not just the latest tail.
    let prepared = if harness_id == HarnessId::Cursor {
        cursor_unstarted_history(
            &doc,
            &resume_state.user_message_id,
            &request.prompt,
            request.resume.is_some(),
        )
        .map(|prompt| request.prompt = prompt)
        .map_err(|e| roboco_harness::HarnessError::Protocol(e.to_string()))
    } else {
        Ok(())
    };
    let started = match prepared {
        Ok(()) => {
            // Waiting here keeps dispatch and the shared queue-flush watcher
            // responsive; the pending-update marker still orders new
            // subprocesses after installation. The lease is shared with the
            // adapter so child cleanup outlives this event loop, and an
            // interrupt that lands while waiting never starts the subprocess.
            let lease = tokio::select! {
                biased;
                _ = controls.interrupt.cancelled() => None,
                lease = inner.registry.execution_lease(harness_id) => Some(Arc::new(lease)),
            };
            match lease {
                Some(lease) => {
                    controls.execution_lease = Some(lease);
                    if let Some(listener) = inner.turn_listener.get() {
                        listener(&chat_id, &request.cwd);
                    }
                    let mut wire_request = request;
                    // Cursor's bridge converted every message before JSON encoding
                    // (rewriting the encoded envelope can double-convert); OpenCode
                    // converts the canonical selection itself, at the provider.
                    if !matches!(harness_id, HarnessId::Cursor | HarnessId::Opencode) {
                        wire_request.prompt =
                            roboco_proto::invocation::harness_prompt(&wire_request.prompt, harness_id);
                        // The fork fallback rides the same conversion the
                        // pasted prompt would get (Codex: file mentions), so a
                        // refused fork's fresh session reads a transcript as
                        // close to the native paste as the driver can deliver.
                        if let Some(fork) = wire_request.provider_fork.as_mut()
                            && let Some(history) = fork.fallback_history.clone()
                        {
                            fork.fallback_history = Some(
                                roboco_proto::invocation::harness_prompt(&history, harness_id),
                            );
                        }
                    }
                    harness.run(wire_request, controls).await
                }
                None => Ok(futures::stream::once(async {
                    Ok(AgentEvent::Done {
                        status: DoneStatus::Interrupted,
                        result: None,
                        error: None,
                        session_id: None,
                    })
                })
                .boxed()),
            }
        },
        Err(error) => Err(error),
    };
    let mut stream = match started {
        Ok(stream) => {
            resume_state
                .started
                .store(true, std::sync::atomic::Ordering::Release);
            stream
        }
        Err(err) => {
            let message = err.to_string();
            tracing::warn!(chat = %chat_id, harness = ?harness_id, error = %message, "run failed to start");
            // The journal alone is live-only: without an entry the transcript
            // shows "Run failed" with no reason (an OpenCode server that never
            // booted looked exactly like that).
            let parts = [MessagePart::Error {
                id: "e0".into(),
                message: message.clone(),
            }];
            if let Err(err) = finish_segment(
                &doc,
                None,
                &new_id(),
                &device_id,
                now_ms(),
                &parts,
                MessageStatus::Complete,
                // The run never started: no provider turn ever streamed.
                None,
            ) {
                tracing::warn!(chat = %chat_id, error = %err, "start failure entry failed");
            }
            inner.publish(
                &chat_id,
                &AgentEvent::Error {
                    message: message.clone(),
                    kind: None,
                },
            );
            inner.publish(
                &chat_id,
                &AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some(message.clone()),
                    session_id: None,
                },
            );
            // Completion notices (orchestration v2): a notifiable turn that
            // never started is still a turn the parent is owed — it errored.
            if let Some(parent) = run_notifiable_parent(&inner, &chat_id, &run_id) {
                let outcome = crate::completion_notices::Outcome::Errored {
                    reason: crate::completion_notices::one_line(Some(message.as_str())),
                };
                spawn_notice_record(inner.clone(), chat_id.clone(), parent, outcome);
            }
            inner.remove_run(&chat_id, &run_id);
            inner.set_status(&chat_id, SessionStatus::Errored, false);
            // The chat's own turn is over (it errored before it started):
            // notices that piled up while it was working start its next
            // turn now (ticket 13).
            spawn_notice_delivery(&inner, &chat_id);
            return;
        }
    };

    let doc_ref: &SessionDoc = &doc;
    let mut folded: Vec<MessagePart> = Vec::new();
    // Every tool id this run has folded, across segment resets. Adapters
    // re-emit shape-bearing `tool_call_update`s (title/rawInput refreshes,
    // long-running completions) as full ToolCall events; once the fold has
    // reset at a steer/park boundary those ids are gone from `folded`, and
    // folding the echo would mint an orphan chip mid-text in the NEXT
    // segment — the mid-word transcript splits.
    let mut seen_tools: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut seen_images = std::collections::HashSet::new();
    for entry in doc_ref.read_entries().unwrap_or_default() {
        for part in entry.parts {
            if let MessagePart::Image { id, .. } = part {
                seen_images.insert(format!("{chat_id}\0{id}"));
                if let Some(tool_id) = id.strip_suffix(":image") {
                    seen_tools.insert(tool_id.to_owned());
                }
            }
        }
    }
    let mut prepared_events = std::collections::VecDeque::new();
    let mut entry_id = new_id();
    let mut segment_started = now_ms();
    // The provider turn the entry currently streaming belongs to (ticket 17):
    // set by a TurnRef event, stamped onto the entry when its segment
    // finishes, so a fork can continue the provider session at exactly that
    // turn. Survives steer boundaries — a steer mid-turn keeps the turn.
    let mut turn_ref: Option<roboco_proto::ProviderTurnRef> = None;
    let mut writer: Option<SegmentWriter<'_>> = None;
    let mut dirty = false;
    let mut flush_at = tokio::time::Instant::now();
    // Set when the engine interrupts the run: the harness gets this long to end its own
    // stream (its token was cancelled); past it, a terminal Done is synthesized.
    let mut interrupt_deadline: Option<tokio::time::Instant> = None;
    let mut interrupted = false;
    let mut saw_session_started = false;
    // One signed-out mark per run: repeats and retries all describe the same
    // dead login, and each marking pass resolves the live login (a detection
    // sweep) — once is enough.
    let mut marked_signed_out = false;
    // Liveness heartbeat: this loop RUNNING is proof the harness stream is
    // open, so freshness must not depend on events arriving. Silent stretches
    // are normal and UNBOUNDED — a long tool call, redacted thinking, an
    // agent waiting on an external process, a question parked for an hour —
    // and each starved the UI's 45s staleness gate in turn (working strip /
    // AwaitingInput dot vanishing mid-run, both user-reported). No stall
    // timeout here by design (a first port was rejected — agents may
    // legitimately be quiet for >10min): a live child means Working, dying
    // paths each carry their own error, and engine death stops these ticks
    // so the gate still catches real crashes. touch_session throttles at 10s.
    let mut live_heartbeat = tokio::time::interval(std::time::Duration::from_secs(15));
    live_heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    // PERSISTENT SESSION (roboco runsBySession): a completed turn on a
    // steerable harness parks here instead of ending the run — the child and
    // its steering mailbox stay warm, and the next user message (dispatch
    // routes into a live run) starts the next turn with zero respawn/resume
    // latency. `Some(when)` = idle since then; the 30-min reaper below ends
    // a session nobody comes back to (roboco SESSION_IDLE_MS).
    // `ROBOCO_SESSION_IDLE_MS` overrides the window (tests).
    let session_idle = std::env::var("ROBOCO_SESSION_IDLE_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(std::time::Duration::from_millis)
        .unwrap_or(std::time::Duration::from_secs(30 * 60));
    // A live subagent stretches the window, but never unbounded: every
    // driver's subagent terminal is a best-effort vendor signal (claude's
    // untagged task_notification, grok's subagent_finished, codex thread
    // closure), and one lost signal would otherwise pin the child — and this
    // loop's registry heartbeat — until the chat is archived. A subagent
    // silent this long (4h by default) is presumed lost; the reap stamps its
    // chip failed.
    let subagent_silence = session_idle * 8;
    let mut idle_since: Option<tokio::time::Instant> = None;
    // A parked runtime whose agent reports background work (see
    // `TurnControl::background_live`) is not idle: reaping it killed that
    // work. The reaper re-checks a full idle window later instead.
    let mut reap_deferred_until: Option<tokio::time::Instant> = None;
    // The reaper last deferred for background work. Its end is not idleness
    // either: the agent reports the drained set a moment before the turn the
    // completion wakes, and reaping in that gap lost the wake (upstream #831,
    // caught by the new live repro).
    let mut held_for_background = false;
    let steerable = harness.supports_steering();
    let stops_turn_in_place = steerable && harness.stops_turn_in_place();
    // TURN-QUIESCE WATCHDOG (2026-08-12 stuck-Working incident): a harness
    // that loses a turn's Done — the adapter never settles `session/prompt`
    // even though the agent finished — strands Working forever: the live
    // heartbeat above keeps the row fresh, and there is no per-turn timeout
    // by design. This is NOT that stall timeout: it never ends the run or
    // errors anything. When the stream has been silent past the window AND
    // the fold shows completed output with nothing in flight (no unresolved
    // tool, no open question), the turn parks exactly like a Done would —
    // segment finalized Complete, status Idle, child and mailbox warm. A
    // false trip (the agent was quietly waiting on something invisible)
    // costs a status dip: the parked-resume path below re-arms Working the
    // moment output flows again, and nothing is lost. `ROBOCO_TURN_QUIESCE_MS`
    // overrides the window; 0 disables.
    // RETIRED for native drivers: a harness whose every turn shape ends with
    // a deterministic wire Done (claude/codex/cursor native) needs no
    // quiesce backstop — arming one only risks false parks on long silent
    // work. The env knob can configure a diagnostic window, but a prompt
    // with an authoritative completion signal must still await that signal.
    // ACP retains the watchdog only for unowned self-continued activity.
    let deterministic_turn_end = harness.deterministic_turn_end();
    let authoritative_prompt_end = harness.authoritative_prompt_end();
    let quiesce_after: Option<std::time::Duration> = match std::env::var("ROBOCO_TURN_QUIESCE_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
    {
        Some(0) => None,
        Some(ms) => Some(std::time::Duration::from_millis(ms)),
        None if deterministic_turn_end => None,
        None => Some(std::time::Duration::from_secs(120)),
    };
    let mut last_stream_activity = tokio::time::Instant::now();
    // SELF-CONTINUED turns get a much SHORTER quiesce window. A turn the
    // agent starts on its own (background-task wake) can never receive a
    // harness Done: the adapter has no `session/prompt` outstanding to
    // settle — verified in claude-agent-acp's autonomous-result lane, which
    // consumes the SDK's turn-end without emitting anything; codex shows
    // the same shape. The watchdog is that turn shape's ONLY settle path,
    // so the default 120s window read as 2min of stuck-Working after every
    // background notification (user report 2026-08-13). The in-flight
    // fold gate below still protects running tools; reasoning heartbeats
    // push the window during real thinking. `ROBOCO_SELF_TURN_QUIESCE_MS`
    // overrides; 0 falls back to the normal window. An explicit
    // `ROBOCO_TURN_QUIESCE_MS=0` still disables the watchdog entirely.
    let self_quiesce_after: Option<std::time::Duration> =
        match std::env::var("ROBOCO_SELF_TURN_QUIESCE_MS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
        {
            Some(0) => None,
            Some(ms) => Some(std::time::Duration::from_millis(ms)),
            None => Some(std::time::Duration::from_secs(20)),
        };
    let mut self_continued_turn = false;
    // Spawn chips that have SETTLED (tagged Done seen). Content events for a
    // settled chip with no live sink are dropped — a straggler frame after
    // the freeze must not mint a new doc entry or wedge the transcript back
    // into Streaming. Only a steer (UserMessage) legitimately REOPENS a
    // settled subagent: it announces more work is coming.
    let mut settled_subagents: std::collections::HashSet<String> = std::collections::HashSet::new();
    // Live subagent sinks, parent tool-use id → transcript doc state.
    let mut subagents: std::collections::HashMap<String, SubagentSink> =
        std::collections::HashMap::new();
    // Last tagged subagent event. A background subagent outlives the turn that
    // spawned it, and its traffic never un-parks the chat, so the idle reaper
    // measures from here as well as from the park.
    let mut last_subagent_activity: Option<tokio::time::Instant> = None;

    let mut final_completed_turn = None;
    // The run's LAST error classification. Harnesses emit the classified
    // `AgentEvent::Error` (usage limit / signed out) before their terminal
    // `Done`; when that Done is Errored, this is what turns the settled row
    // into a Limited one (the record on the session status).
    let mut last_error_kind: Option<AgentErrorKind> = None;
    // The run's FINAL Done (status + error), captured at the break that ends
    // the run — the engine's own recorded turn-end state for the completion
    // notice. Parked settles record inside the loop instead.
    let mut final_done: Option<(DoneStatus, Option<String>)> = None;
    let final_status = loop {
        let event: AgentEvent = if let Some(event) = prepared_events.pop_front() {
            event
        } else {
            let raw_event = tokio::select! {
                biased;
                changed = cancel_rx.changed(), if !interrupted => {
                    let _ = changed;
                    interrupted = true;
                    interrupt_deadline = Some(
                        tokio::time::Instant::now() + std::time::Duration::from_secs(3),
                    );
                    continue;
                }
                _ = tokio::time::sleep_until(
                    interrupt_deadline.unwrap_or_else(tokio::time::Instant::now)
                ), if interrupt_deadline.is_some() => AgentEvent::Done {
                    status: DoneStatus::Interrupted,
                    result: None,
                    error: None,
                    session_id: None,
                },
                _ = live_heartbeat.tick() => {
                    inner.touch_session(&chat_id);
                    continue;
                }
                // An accepted update must not wait behind a warm between-turn
                // child for the full idle-reaper window. The completed turn is
                // already durable, so retire the parked process cleanly and let
                // the queued exclusive lease proceed.
                _ = tokio::time::sleep_until(tokio::time::Instant::now()),
                    if idle_since.is_some()
                        && inner.registry.update_pending(harness_id)
                        && !turn.background_live()
                        && (subagents.is_empty()
                            || last_subagent_activity.is_none_or(|at| at.elapsed() >= subagent_silence)) =>
                {
                    if let Some(token) = lock(&inner.runs)
                        .get(&chat_id)
                        .filter(|h| h.run_id == run_id)
                        .map(|h| h.interrupt_token.clone())
                    {
                        token.cancel();
                    }
                    break SessionStatus::Idle;
                }
                // Idle reaper (roboco SESSION_IDLE_MS): a parked persistent session
                // nobody returned to in 30 minutes releases its child. The turn
                // was finalized at Done, so this end is clean — no aborted stamp.
                // A live background subagent is somebody still using the child:
                // the window counts from its last activity, and stretches to
                // `subagent_silence` while its sink is open.
                _ = tokio::time::sleep_until(
                    {
                        let window = if subagents.is_empty() { session_idle } else { subagent_silence };
                        let base = idle_since
                            .map(|at| at.max(last_subagent_activity.unwrap_or(at)) + window);
                        match (base, reap_deferred_until) {
                            (Some(base), Some(deferred)) => base.max(deferred),
                            (base, deferred) => base.or(deferred).unwrap_or_else(tokio::time::Instant::now),
                        }
                    }
                ), if idle_since.is_some() => {
                    // The driver's own report of background work (Claude's
                    // background agents and shells) is authoritative — it
                    // lists every task the process still runs — so it holds
                    // the runtime for as long as that work lives.
                    if turn.background_live() {
                        tracing::info!(chat = %chat_id, "idle reaper deferred: the agent reports background work");
                        reap_deferred_until = Some(tokio::time::Instant::now() + session_idle);
                        held_for_background = true;
                        continue;
                    }
                    if std::mem::take(&mut held_for_background) {
                        // The work just ended: a full idle window from here.
                        reap_deferred_until = Some(tokio::time::Instant::now() + session_idle);
                        continue;
                    }
                    tracing::info!(
                        chat = %chat_id,
                        live_subagents = subagents.len(),
                        "reaping idle persistent session"
                    );
                    if let Some(token) = lock(&inner.runs)
                        .get(&chat_id)
                        .filter(|h| h.run_id == run_id)
                        .map(|h| h.interrupt_token.clone())
                    {
                        token.cancel();
                    }
                    break SessionStatus::Idle;
                }
                Some(event) = engine_rx.recv() => event,
                next = stream.next() => match next {
                    Some(Ok(event)) => event,
                    // A stream error while PARKED is a post-turn child death —
                    // the turn was already finalized, so the run ends clean
                    // instead of stamping a completed session Errored.
                    Some(Err(err)) if idle_since.is_some() => {
                        tracing::warn!(chat = %chat_id, error = %err, "parked session child died; ending clean");
                        break SessionStatus::Idle;
                    }
                    Some(Err(err)) => AgentEvent::Done {
                        status: DoneStatus::Errored,
                        result: None,
                        error: Some(err.to_string()),
                        session_id: None,
                    },
                    None if interrupted => AgentEvent::Done {
                        status: DoneStatus::Interrupted,
                        result: None,
                        error: None,
                        session_id: None,
                    },
                    // Stream end while PARKED idle: a per-turn adapter closing
                    // after its final Done — a clean end, not a crash (the turn
                    // was already finalized). Persistent adapters keep the
                    // stream open and never hit this.
                    None if idle_since.is_some() => break SessionStatus::Idle,
                    None => AgentEvent::Done {
                        status: DoneStatus::Errored,
                        result: None,
                        error: Some("harness stream ended without Done".into()),
                        session_id: None,
                    },
                },
                _ = tokio::time::sleep_until(flush_at), if dirty || subagents.values().any(|s| s.dirty) => {
                    #[cfg(test)]
                    FLUSH_TICKS.with(|t| t.set(t.get() + 1));
                    // Coalesced STREAM_COMMIT_MS tick: one doc commit per window
                    // (parent + any dirty subagent docs).
                    if dirty {
                        if let Err(err) = sync_segment(
                            doc_ref, &mut writer, &entry_id, &device_id, segment_started, &folded,
                        ) {
                            tracing::warn!(chat = %chat_id, error = %err, "segment sync failed");
                        }
                        dirty = false;
                    }
                    for sink in subagents.values_mut() {
                        sink.flush(&device_id);
                    }
                    continue;
                }
                // Turn-quiesce watchdog (see the knob above). Armed only when the
                // fold says nothing is in flight: an unresolved tool part is a
                // command still running (legitimately silent for minutes — the
                // rejected stall-timeout case), an unresolved input part is a
                // question awaiting the user. The live-plan chip is exempt from
                // the tool check: it is a singleton that never resolves. An EMPTY
                // fold still arms — a Steered boundary that no output ever
                // follows is one of the wedge shapes — it just parks without
                // writing a segment (an empty finalize would leave a stub entry).
                _ = tokio::time::sleep_until({
                    let mut window = quiesce_after.unwrap_or_default();
                    if self_continued_turn && let Some(short) = self_quiesce_after {
                        window = window.min(short);
                    }
                    last_stream_activity + window
                }), if quiesce_after.is_some()
                    && (self_continued_turn || !authoritative_prompt_end)
                    && idle_since.is_none()
                    && !interrupted
                    && steerable
                    && !folded.iter().any(|p| match p {
                        MessagePart::Tool { id, resolved: false, .. } => {
                            id != roboco_proto::LIVE_PLAN_TOOL_ID
                        }
                        MessagePart::Input { resolved: false, .. } => true,
                        _ => false,
                    }) =>
                {
                    tracing::warn!(
                        chat = %chat_id,
                        quiet_ms = quiesce_after.unwrap_or_default().as_millis() as u64,
                        "turn quiesced: stream silent after completed output with no \
                         turn-end; parking (suspected missing harness Done)"
                    );
                    // Some adapters close a completed response through this
                    // engine watchdog instead of a native Done. Preserve that
                    // completion notice, but never notify for an empty boundary
                    // or while an accepted steer still awaits delivery, or a
                    // message the user already sent is on its way.
                    let completed_turn = ((!folded.is_empty() || writer.is_some())
                        && !inner.has_pending_steers(&chat_id, &run_id)
                        && !inner.continues_with_sent_message(&chat_id))
                        .then(|| entry_id.clone());
                    if !folded.is_empty() || writer.is_some() {
                        if let Err(err) = finish_segment(
                            doc_ref,
                            writer.take(),
                            &entry_id,
                            &device_id,
                            segment_started,
                            &folded,
                            MessageStatus::Complete,
                            turn_ref.as_ref(),
                        ) {
                            tracing::warn!(chat = %chat_id, error = %err, "quiesce segment finish failed");
                        }
                        inner.note_message(&chat_id, &folded_text(&folded));
                    }
                    folded.clear();
                    dirty = false;
                    entry_id = new_id();
                    segment_started = now_ms();
                    idle_since = Some(tokio::time::Instant::now());
                    reap_deferred_until = None;
                    self_continued_turn = false;
                    inner.set_status_with_completion(
                        &chat_id, SessionStatus::Idle, false, completed_turn.clone(),
                    );
                    // The watchdog finalized a completed turn (no native
                    // Done): it owes its parent the same notice a Done would.
                    record_parked_turn(
                        &inner,
                        &chat_id,
                        &run_id,
                        completed_turn.is_some(),
                        false,
                    );
                    // The watchdog-finalized turn is over: notices that
                    // piled up while the chat worked start its next turn
                    // (ticket 13).
                    spawn_notice_delivery(&inner, &chat_id);
                    continue;
                }
            };

            prepared_events.extend(
                inner
                    .prepare_generated_image(&chat_id, raw_event, &mut seen_images)
                    .await,
            );
            let Some(event) = prepared_events.pop_front() else {
                continue;
            };
            event
        };

        // Sign-out evidence (ticket 05's structured kind, ticket 08's
        // marking): the harness rejected its login — the login is dead, not
        // stale. Checked BEFORE the subagent routing and the parked gate so
        // every arrival shape counts (a subagent running on the same login
        // fails signed-out just as hard), and awaited inline so the mark has
        // landed by the time the run's own terminal state settles.
        if !marked_signed_out && event_signs_out(&event) {
            marked_signed_out = true;
            inner.note_run_signed_out(&chat_id, harness_id).await;
        }

        // ── subagent routing ───────────────────────────────────────────
        // Tagged events NEVER fold into the parent transcript: they stream
        // into the subagent's own doc, and the parent keeps only the spawn
        // chip (ref + status + one-line tail) — refreshed through the live
        // fold while its segment still streams, else stamped in place (the
        // eager-done norm: the chip's entry finished before the background
        // subagent spoke). Handled BEFORE the parked gate so a parked
        // session's background traffic still reaches the subagent doc
        // without un-parking the chat.
        if let AgentEvent::Subagent {
            parent_tool_use_id,
            event: sub_event,
        } = &event
        {
            inner.publish(&chat_id, &event);
            last_subagent_activity = Some(tokio::time::Instant::now());
            let is_steer = matches!(
                sub_event.as_ref(),
                AgentEvent::UserMessage { .. } | AgentEvent::Steered { .. }
            );
            if is_steer {
                settled_subagents.remove(parent_tool_use_id);
            } else if settled_subagents.contains(parent_tool_use_id)
                && !subagents.contains_key(parent_tool_use_id)
            {
                // Straggler after the freeze: chip-only silence (the old
                // pre-viz behavior), never a reopened doc.
                continue;
            }
            let sub_id = subagent_doc_id(&chat_id, parent_tool_use_id);
            let chip_streaming = folded
                .iter()
                .any(|p| matches!(p, MessagePart::Tool { id, .. } if id == parent_tool_use_id));
            let sink_known = subagents.contains_key(parent_tool_use_id);
            // A Done with NO sink (a subagent that never streamed — codex
            // turn ends can beat registration) is chip-only: minting a doc
            // just to freeze it empty helps no one, and stamping the ref
            // would link the chip to that never-created doc (an empty tab
            // on click).
            let done_only = !sink_known && matches!(sub_event.as_ref(), AgentEvent::Done { .. });
            if chip_streaming {
                if !sink_known && !done_only {
                    for p in folded.iter_mut() {
                        if let MessagePart::Tool {
                            id,
                            call,
                            subagent_ref,
                            ..
                        } = p
                            && id == parent_tool_use_id
                            // Genus gate: a subagent ref may only ever bind
                            // to a SPAWN call — mis-keyed tagged traffic
                            // must not turn an ordinary chip into a spawn
                            // link (empty tab on click).
                            && call.is_subagent_spawn()
                        {
                            *subagent_ref = Some(sub_id.clone());
                        }
                    }
                }
                roboco_doc::fold_event_into_parts(&mut folded, &event);
                if !dirty {
                    dirty = true;
                    flush_at = tokio::time::Instant::now()
                        + std::time::Duration::from_millis(STREAM_COMMIT_MS);
                }
            }
            // Open the sink lazily; an open failure degrades to chip-only.
            if !sink_known && !done_only {
                let opened = inner.doc_host().and_then(|host| match host.open(&sub_id) {
                    Ok(handle) => Some(handle.doc_arc()),
                    Err(err) => {
                        tracing::warn!(doc = %sub_id, error = %err, "subagent doc open failed (chip-only)");
                        None
                    }
                });
                if let Some(sub_doc) = opened {
                    subagents.insert(
                        parent_tool_use_id.clone(),
                        SubagentSink {
                            doc_id: sub_id.clone(),
                            doc: sub_doc,
                            entry_id: new_id(),
                            started_at: now_ms(),
                            entry_index: None,
                            written: Vec::new(),
                            folded: Vec::new(),
                            dirty: false,
                        },
                    );
                    if !chip_streaming {
                        let _ = doc_ref.update_subagent_chip(
                            parent_tool_use_id,
                            Some(&sub_id),
                            Some("running"),
                            None,
                        );
                    }
                }
            }
            let done = matches!(sub_event.as_ref(), AgentEvent::Done { .. });
            if done {
                settled_subagents.insert(parent_tool_use_id.clone());
                if !chip_streaming {
                    // A resumed run can finish an older chip without ever
                    // opening a sink. The chip's lifecycle is independent of
                    // whether this run received transcript content.
                    let _ = doc_ref.update_subagent_chip(
                        parent_tool_use_id,
                        None,
                        subagent_chip_update(sub_event),
                        None,
                    );
                }
            }
            if let Some(sink) = subagents.get_mut(parent_tool_use_id) {
                if let AgentEvent::UserMessage { text } = sub_event.as_ref() {
                    // A steer splits ENTRIES, not parts — handled at the
                    // sink level (the fold ignores UserMessage).
                    sink.push_user(&device_id, text);
                    continue;
                }
                roboco_doc::fold_event_into_parts(&mut sink.folded, sub_event);
                let was_clean = !sink.dirty;
                sink.dirty = true;
                // A sink waking on its own must arm the same commit window
                // the parent's dirty flag does — `flush_at` is otherwise only
                // rescheduled on the parent's flip, so a past deadline would
                // fire the commit branch on every event instead of once per
                // STREAM_COMMIT_MS window.
                if was_clean && !dirty && flush_at <= tokio::time::Instant::now() {
                    flush_at = tokio::time::Instant::now()
                        + std::time::Duration::from_millis(STREAM_COMMIT_MS);
                }
                if done {
                    let status = match sub_event.as_ref() {
                        AgentEvent::Done {
                            status: DoneStatus::Errored,
                            ..
                        } => MessageStatus::Complete,
                        AgentEvent::Done {
                            status: DoneStatus::Interrupted,
                            ..
                        } => MessageStatus::Aborted,
                        _ => MessageStatus::Complete,
                    };
                    let sink = subagents.remove(parent_tool_use_id).expect("checked");
                    let doc_id = sink.doc_id.clone();
                    // FREEZE: the finished transcript uploads as a static R2
                    // blob (`blob/{chatId}/{subDocId}`) so viewers of
                    // finished subagents never wake the doc's room; dropping
                    // the sink unpins the doc for the LRU and the room
                    // idles. The live doc remains the fallback.
                    if let Some(json) = sink.finish(&device_id, status)
                        && let Some(host) = inner.doc_host()
                    {
                        host.upload_tool_sidecar(
                            &chat_id,
                            roboco_doc::SidecarPayload {
                                part_id: doc_id,
                                output: Some(json),
                                diff: None,
                            },
                        );
                    }
                }
            }
            continue;
        }

        // Any stream activity proves the run is alive — keep the session's
        // freshness inside the UI's 45s staleness window (throttled), and
        // push the quiesce watchdog's window out.
        inner.touch_session(&chat_id);
        last_stream_activity = tokio::time::Instant::now();
        // The engine's input bridge is the sole authority on input requests:
        // it mints the id and parks the resolver BEFORE emitting the event,
        // so a legitimate id is always pending here. A harness emitting its
        // own copy (a different id no resolver knows) would fold an
        // unanswerable twin chip into the doc — and answering the twin would
        // never resume the run. Dropped BEFORE the parked gate below: letting
        // it un-park the session and then dropping it would disarm the idle
        // reaper with no turn running (a leaked child no reap ever ends).
        if let AgentEvent::InputRequested { request_id, .. } = &event {
            let pending = lock(&inner.runs)
                .get(&chat_id)
                .map(|h| h.pending_inputs.clone());
            let known = pending.is_some_and(|p| lock(&p).contains_key(request_id));
            if !known {
                tracing::warn!(
                    chat = %chat_id,
                    request = %request_id,
                    "dropping harness-emitted InputRequested (unknown id; \
                     the engine input bridge owns this lifecycle)"
                );
                continue;
            }
        }
        // Capacity/occupancy can settle after Done; updating it must not reopen a turn.
        if let AgentEvent::ContextUsage { tokens, window } = &event {
            if let Err(err) = doc_ref.update_context_usage(*tokens, *window) {
                tracing::warn!(%chat_id, error = %err, "context usage write failed");
            }
            continue;
        }
        // PARKED: a steer boundary, a terminal Done, or SELF-CONTINUED OUTPUT
        // re-opens the session; everything else stays gated. The ACP child
        // keeps forwarding `session/update` frames after a turn completes,
        // and they split two ways:
        //
        // - Post-turn NOISE — late tool_call_updates for commands folded in a
        //   prior segment, command refreshes, reasoning heartbeats. Treating
        //   those as "the next turn" re-armed Working with no Done ever
        //   coming (the eternally-running session bug) and folded orphan
        //   parts into a phantom segment. Still dropped.
        // - SELF-CONTINUED WORK — Claude Code re-invokes itself when a
        //   background task finishes (turns no prompt started) and streams
        //   real output for them. Dropping those LOST transcript content
        //   (2026-08-12: "Build finished successfully…" streamed by the
        //   agent, absent from the doc). Fresh text or a genuinely new tool
        //   call resumes the session: new segment, Working, and the turn
        //   settles again via Done — or via the quiesce watchdog, which is
        //   what makes this resume safe where the naive version was not.
        //
        // The RESUME_GATE separates the two by arrival time: a finished
        // turn's tail flush lands within milliseconds of its Done, while a
        // self-continued turn starts a whole new agent round trip (seconds
        // at minimum, minutes in the incident). Inside the gate everything
        // non-boundary stays inert, exactly as before.
        let turn_was_active = idle_since.is_none();
        const RESUME_GATE: std::time::Duration = std::time::Duration::from_secs(1);
        if idle_since.is_some() {
            let self_continued = idle_since
                .is_some_and(|parked_at| parked_at.elapsed() >= RESUME_GATE)
                && (matches!(
                    &event,
                    AgentEvent::TextDelta { text } if !text.is_empty()
                ) || matches!(
                    &event,
                    AgentEvent::ToolCall { id, .. }
                        if id == roboco_proto::LIVE_PLAN_TOOL_ID || !seen_tools.contains(id)
                ));
            if self_continued {
                tracing::info!(
                    chat = %chat_id,
                    "parked session resumed by self-continued agent output"
                );
                idle_since = None;
                self_continued_turn = true;
                // The park cleared the fold; rotate to a fresh entry and
                // fall through — this event is the new segment's first part.
                entry_id = new_id();
                segment_started = now_ms();
                inner.set_status(&chat_id, SessionStatus::Working, true);
            } else {
                match &event {
                    AgentEvent::Steered { .. } => {
                        idle_since = None;
                        inner.set_status(&chat_id, SessionStatus::Working, true);
                    }
                    AgentEvent::Done { .. } => {
                        idle_since = None;
                    }
                    // A question with NO turn behind it (post-turn permission
                    // noise): answer it empty right here. Un-parking into
                    // AwaitingInput would disarm the idle reaper with no Done
                    // ever coming — stranded status, leaked warm child.
                    AgentEvent::InputRequested { request_id, .. } => {
                        let resolver = lock(&inner.runs)
                            .get(&chat_id)
                            .and_then(|h| lock(&h.pending_inputs).remove(request_id));
                        if let Some(tx) = resolver {
                            let _ = tx.send(Vec::new());
                        }
                        tracing::debug!(chat = %chat_id, "parked session: post-turn input request auto-declined");
                        continue;
                    }
                    // A stale answer settling after its turn already closed:
                    // nothing is running — stay parked.
                    AgentEvent::InputResolved { .. } => continue,
                    _ => continue,
                }
            }
        }
        // Empty reasoning deltas are PURE heartbeats: redacted thinking and
        // tool-input-generation windows stream them with no text. They fold
        // to nothing, so journaling/publishing them is only noise (hundreds
        // per long turn observed) — the touch above already did their job.
        if matches!(&event, AgentEvent::ReasoningDelta { text } if text.is_empty()) {
            continue;
        }

        // Stale tool echoes: a ToolCall/ToolResult naming an id folded in a
        // PRIOR segment (the fold reset at a steer or park since) belongs to
        // a chip that already rendered in its own entry. Folding the echo
        // would splice a phantom chip into the middle of the current
        // segment's streaming text (the mid-word transcript splits). Dropped;
        // same-segment refreshes still land in place.
        let in_segment = |folded: &[MessagePart], id: &str| {
            folded
                .iter()
                .any(|p| matches!(p, MessagePart::Tool { id: pid, .. } if pid == id))
        };
        match &event {
            // The live plan chip is a deliberate singleton (every update
            // reuses `LIVE_PLAN_TOOL_ID` so the fold refreshes in place):
            // treating its reappearance after a park/steer reset as a stale
            // echo dropped the todo list for the rest of the run — from the
            // first boundary on, plans never rendered again.
            AgentEvent::ToolCall { id, .. } if id == roboco_proto::LIVE_PLAN_TOOL_ID => {}
            AgentEvent::ToolResult { id, .. } if id == roboco_proto::LIVE_PLAN_TOOL_ID => {}
            AgentEvent::ToolCall { id, .. } => {
                if !in_segment(&folded, id) && seen_tools.contains(id) {
                    continue;
                }
                seen_tools.insert(id.clone());
            }
            AgentEvent::ToolResult { id, .. }
                if !in_segment(&folded, id) && seen_tools.contains(id) =>
            {
                continue;
            }
            _ => {}
        }

        // Startup-crash retry: a run that dies before ever starting (errored
        // Done, no SessionStarted, nothing streamed) means the AGENT CHILD
        // failed to come up — not that the injected resume id was bad. Since
        // the ACP conversion (2026-08-08) a stale id is handled inside the
        // harness (`session/load` falls back to `session/new`), so the old
        // guess here — tombstone the id, retry fresh — fired only on child
        // startup failures and permanently severed GOOD conversations (user
        // incident 2026-08-13). The id stays; retry ONCE against the same
        // user entry, resume and all, in case the crash was transient. A
        // helper that is down hard fails the retry too and surfaces its
        // crash text (the harness now appends exit status + stderr).
        if resume_state.resume_injected
            && !resume_state.startup_retry
            && !saw_session_started
            && folded.is_empty()
            && !interrupted
            && matches!(
                &event,
                AgentEvent::Done {
                    status: DoneStatus::Errored,
                    ..
                }
            )
            && let Some(retry) = retry_request.take()
        {
            tracing::warn!(
                chat = %chat_id,
                "run died before session start; retrying once (resume kept)"
            );
            inner.remove_run(&chat_id, &run_id);
            let engine = SessionsEngine {
                inner: inner.clone(),
            };
            let chat = chat_id.clone();
            let message_id = resume_state.user_message_id.clone();
            tokio::spawn(async move {
                // The user entry write inside dispatch is idempotent by
                // message id; `startup_retry` makes this attempt final.
                if let Err(err) = engine
                    .dispatch_with(&chat, harness_id, retry, Some(message_id), None, true)
                    .await
                {
                    tracing::error!(chat = %chat, error = %err, "startup-crash retry dispatch failed");
                    // No run is coming: leaving the row Working would spin
                    // the session forever with nothing behind it.
                    engine
                        .inner
                        .set_status(&chat, SessionStatus::Errored, false);
                }
            });
            return;
        }

        // A steer boundary splits the assistant entry exactly where the fold resets.
        if let AgentEvent::Steered {
            next_assistant_message_id,
            ..
        } = &event
        {
            inner.publish(&chat_id, &event);
            // A steer boundary means a real prompt owns the turn again — its
            // Done will come; the short self-continued window stands down.
            self_continued_turn = false;
            if let Err(err) = finish_segment(
                doc_ref,
                writer.take(),
                &entry_id,
                &device_id,
                segment_started,
                &folded,
                MessageStatus::Complete,
                // The segment closes at the steer, but the provider turn it
                // belongs to carries on: the ref survives the rotation so the
                // NEXT segment of the same turn keeps it (a new turn's TurnRef
                // replaces it before that segment finishes).
                turn_ref.as_ref(),
            ) {
                tracing::warn!(chat = %chat_id, error = %err, "segment finish failed");
            }
            inner.note_message(&chat_id, &folded_text(&folded));
            folded.clear();
            dirty = false;
            entry_id = next_assistant_message_id.clone().unwrap_or_else(new_id);
            segment_started = now_ms();
            // The elapsed timer is per user message, not per child process: a
            // steer boundary restarts it (matches the parked-resume path and
            // the composer's optimistic overlay, which already reads 0:00).
            inner.set_status(&chat_id, SessionStatus::Working, true);
            // The boundary confirms delivery of the oldest accepted steer —
            // retire its at-least-once ledger entry.
            let confirmed = lock(&inner.runs)
                .get(&chat_id)
                .filter(|h| h.run_id == run_id)
                .and_then(|h| lock(&h.routed_steers).pop_front());
            // Consumed: a steer carrying the fork history delivered it to
            // this runtime's provider session.
            if confirmed.is_some_and(|steer| steer.fork_history)
                && let Some(session) = lock(&inner.harness_sessions)
                    .get(&chat_id)
                    .map(|known| known.session_id.clone())
                    .filter(|id| !id.is_empty())
            {
                let _ = doc.set_fork_history_session(&session);
            }
            continue;
        }

        match &event {
            AgentEvent::SessionStarted {
                session_id, cwd, ..
            } => {
                saw_session_started = true;
                // The event's own cwd (where the harness actually created the
                // session) scopes the stored id, not the request's.
                inner.remember_harness_session(&chat_id, session_id, cwd);
                note_fork_history_session(&doc, carried_history, session_id);
            }
            AgentEvent::TurnRef { reference } => {
                // The provider turn now streaming into `entry_id`: held for
                // the stamp at segment finish (below). Like SessionStarted,
                // it is engine bookkeeping — the fold below renders nothing
                // for it.
                turn_ref = Some(reference.clone());
            }
            AgentEvent::Done {
                session_id: Some(session_id),
                ..
            } => {
                inner.remember_harness_session(&chat_id, session_id, &run_cwd);
                note_fork_history_session(&doc, carried_history, session_id);
            }
            AgentEvent::InputRequested { questions, .. } => {
                // Known-id guaranteed: the unknown-id twin was dropped above,
                // before the parked gate.
                let was_awaiting = lock(&inner.statuses)
                    .get(&chat_id)
                    .is_some_and(|s| s.status == SessionStatus::AwaitingInput);
                inner.set_status(&chat_id, SessionStatus::AwaitingInput, false);
                // A notifiable turn reaching awaiting input owes its parent a
                // notice with a one-line summary of the question — once per
                // awaiting stretch, not per twin question.
                if !was_awaiting
                    && let Some(parent) = run_notifiable_parent(&inner, &chat_id, &run_id)
                {
                    let summary = crate::completion_notices::question_summary(questions);
                    spawn_notice_record(
                        inner.clone(),
                        chat_id.clone(),
                        parent,
                        crate::completion_notices::Outcome::WaitingForInput { summary },
                    );
                }
            }
            AgentEvent::InputResolved { .. } => {
                inner.set_status(&chat_id, SessionStatus::Working, false);
            }
            AgentEvent::Error { kind, .. } => {
                // Track the latest classification; a later unclassified
                // error supersedes an earlier limit one (the turn then ended
                // on that failure, not the limit).
                last_error_kind = kind.clone();
            }
            _ => {}
        }

        inner.publish(&chat_id, &event);

        // Defensive rule from roboco: a mid-run SessionStarted re-emission (Claude SDK
        // background re-invocations) must not wipe the segment being written.
        let skip_fold = matches!(&event, AgentEvent::SessionStarted { .. }) && !folded.is_empty();
        if !skip_fold {
            fold_event_into_parts(&mut folded, &event);
            // R2 sidecar PARKED (2026-08-10, product call): the fold's
            // summary/stats ARE the doc's whole record — no refs stamped, no
            // uploads. Full outputs survive only in the host's local run
            // journal. To reintroduce: `roboco_doc::sidecar_payload(&event)`
            // → `apply_sidecar_refs` → `doc_host.upload_tool_sidecar`, all
            // still in place and tested.
        }

        if let AgentEvent::Done { status, error, .. } = &event {
            // A question still pending at turn end can never be legitimately
            // answered (its turn is over): drain the resolvers NOW, or a late
            // `respond_input` finds one, emits InputResolved, and un-parks
            // the session into Working with no turn behind it — stranded
            // Working, timer forever, reaper disarmed. Empty answers unblock
            // the harness-side bridge like an interrupt does.
            let pending = lock(&inner.runs)
                .get(&chat_id)
                .filter(|h| h.run_id == run_id)
                .map(|h| h.pending_inputs.clone());
            if let Some(pending) = pending {
                for (_, tx) in lock(&pending).drain() {
                    let _ = tx.send(Vec::new());
                }
            }
            let message_status = match status {
                DoneStatus::Interrupted => MessageStatus::Aborted,
                DoneStatus::Completed | DoneStatus::Errored => MessageStatus::Complete,
            };
            // No dangling chips: a run that ends for ANY reason (completed,
            // errored, interrupted) terminally resolves its input parts — an
            // unresolved question must not outlive the run that asked it
            // (its resolver died with the run; an answer could never land).
            for part in folded.iter_mut() {
                if let MessagePart::Input { resolved, .. } = part {
                    *resolved = true;
                }
            }
            // A Done landing on a PARKED session with nothing streamed (the
            // idle reaper's or an interrupt's own teardown) has no entry to
            // finalize — writing one would leave an empty aborted stub.
            let nothing_streamed = writer.is_none() && folded.is_empty();
            if !nothing_streamed {
                if let Err(err) = finish_segment(
                    doc_ref,
                    writer.take(),
                    &entry_id,
                    &device_id,
                    segment_started,
                    &folded,
                    message_status,
                    turn_ref.as_ref(),
                ) {
                    tracing::warn!(chat = %chat_id, error = %err, "final segment finish failed");
                }
                inner.note_message(&chat_id, &folded_text(&folded));
            }
            if *status == DoneStatus::Completed {
                // A cleanly completed turn resets the auto-resume revival
                // budget: only consecutive crash-revive-crash cycles spend it.
                inner.journal.clear_resume_attempts(&chat_id);
            }
            // Exchange completed on an untitled chat → name it (fire-and-forget;
            // interrupted/errored turns never trigger naming).
            if *status == DoneStatus::Completed
                && let Some(titles) = inner.titles.get()
            {
                titles.maybe_generate(&chat_id, harness_id, &user_prompt, &run_cwd);
            }
            // A turn stopped in place: the runtime lives on, parked like a
            // completed turn. Steers it had accepted but not yet delivered
            // were cancelled with the turn (never delivered, never
            // confirmed) — drop them, or they would hold the parked session
            // Working forever.
            let stopped_in_place =
                *status == DoneStatus::Interrupted && stops_turn_in_place && !interrupted;
            if stopped_in_place
                && let Some(ledger) = lock(&inner.runs)
                    .get(&chat_id)
                    .filter(|h| h.run_id == run_id)
                    .map(|h| h.routed_steers.clone())
            {
                lock(&ledger).clear();
            }
            // An accepted steer awaiting its boundary owns the continuation:
            // the previous Done is an internal handoff, not a completion ping.
            // So does a message the user already sent that is still on its
            // way (see `continues_with_sent_message`); rows the user queued
            // themselves still notify.
            let pending_steer = inner.has_pending_steers(&chat_id, &run_id);
            let completed_turn = (*status == DoneStatus::Completed
                && !interrupted
                && turn_was_active
                && !pending_steer
                && !inner.continues_with_sent_message(&chat_id))
            .then(|| entry_id.clone());
            // PERSISTENT SESSION: a cleanly completed turn on a steerable
            // harness PARKS instead of ending — child + mailbox stay warm for
            // the next routed dispatch; per-turn state resets for it.
            if (*status == DoneStatus::Completed || stopped_in_place)
                && steerable
                && !interrupted
                && !inner.registry.update_pending(harness_id)
            {
                folded.clear();
                dirty = false;
                entry_id = new_id();
                segment_started = now_ms();
                // Resume-retry is strictly a first-turn concern.
                saw_session_started = true;
                idle_since = Some(tokio::time::Instant::now());
                reap_deferred_until = None;
                self_continued_turn = false;
                inner.set_status_with_completion(
                    &chat_id,
                    SessionStatus::Idle,
                    false,
                    completed_turn.clone(),
                );
                // The parked turn settled: a completed turn notifies its
                // parent, a stop-in-place notifies as stopped, an internal
                // handoff (a pending steer owns the continuation) does not.
                record_parked_turn(
                    &inner,
                    &chat_id,
                    &run_id,
                    completed_turn.is_some(),
                    stopped_in_place,
                );
                // The parked turn is over: notices that piled up while the
                // chat worked start its next turn (ticket 13).
                spawn_notice_delivery(&inner, &chat_id);
                continue;
            }
            final_completed_turn = completed_turn;
            final_done = Some((*status, error.clone()));
            break match status {
                DoneStatus::Errored => SessionStatus::Errored,
                _ => SessionStatus::Idle,
            };
        }

        if !folded.is_empty() && !dirty {
            dirty = true;
            flush_at =
                tokio::time::Instant::now() + std::time::Duration::from_millis(STREAM_COMMIT_MS);
        }
    };

    // Any subagent still streaming when the run ends freezes as-is: the
    // parent process is gone, so nothing more can arrive on this stream.
    for (parent_id, sink) in subagents.drain() {
        let doc_id = sink.doc_id.clone();
        let _ = doc_ref.update_subagent_chip(&parent_id, None, Some("failed"), None);
        if let Some(json) = sink.finish(&device_id, MessageStatus::Aborted)
            && let Some(host) = inner.doc_host()
        {
            host.upload_tool_sidecar(
                &chat_id,
                roboco_doc::SidecarPayload {
                    part_id: doc_id,
                    output: Some(json),
                    diff: None,
                },
            );
        }
    }

    // Claim any accepted-but-unconfirmed steers BEFORE the handle goes away:
    // a routed send that raced this exit either finds its entry gone (we own
    // it — re-dispatched below) or reclaims it and starts a fresh run itself.
    let orphans: Vec<RoutedSteer> = lock(&inner.runs)
        .get(&chat_id)
        .filter(|h| h.run_id == run_id)
        .map(|h| std::mem::take(&mut *lock(&h.routed_steers)).into())
        .unwrap_or_default();
    // A turn that ended on a usage limit records the additive limit row
    // (account + reset-time fill) while this run still owns the chat — the
    // accounts lookup is the only await in the exit path, so it runs before
    // the handle goes away rather than racing a fresh dispatch's status.
    let limit = match (final_status, last_error_kind) {
        (SessionStatus::Errored, Some(kind)) => {
            inner.limit_record(harness_id, kind).await
        }
        _ => None,
    };
    if limit.is_some() {
        // Queued messages wait BEHIND the continuation instead of dispatching
        // into the same wall: a limited chat holds its queue exactly like a
        // Stop does, and the next explicit prompt (Resume's continue prompt,
        // or the user's own message) thaws it when its run dispatches.
        if let Some(host) = inner.doc_host()
            && let Ok(handle) = host.open(&chat_id)
        {
            handle.pause_queue();
        }
    }
    // Completion notices (orchestration v2): the run's final turn ended —
    // read the notifiable parent before the handle goes away, then record
    // the outcome the engine's own state observed.
    let notifiable = run_notifiable_parent(&inner, &chat_id, &run_id);
    inner.remove_run(&chat_id, &run_id);
    let final_turn_completed = final_completed_turn.is_some();
    let auto_arm_limit = limit.clone();
    inner.set_status_with_limit(&chat_id, final_status, false, final_completed_turn, limit);
    if let Some(limit) = auto_arm_limit
        .as_ref()
        .filter(|_| final_status == SessionStatus::Errored)
    {
        // Auto-resume limited chats (orchestration v2, ticket 07): the fresh
        // limit record arms the continuation when the setting is on — only
        // ever via a NEW record whose reset advances past the last fired
        // continuation (the loop guard), never from the failure of the
        // continuation itself.
        SessionsEngine { inner: inner.clone() }.maybe_auto_arm_resume(&chat_id, limit);
    }
    if let (Some(parent), Some((status, error))) = (notifiable, final_done) {
        let completed_turn = final_turn_completed;
        if let Some(outcome) = final_outcome(status, error.as_deref(), completed_turn) {
            spawn_notice_record(inner.clone(), chat_id.clone(), parent, outcome);
        }
    }
    // A busy parent's notices waited — never steered mid-turn (ticket 13):
    // its turn is over, so they start its next one now. A turn ended by an
    // interrupt is the user's Stop: no wakeup (ticket 14 owns the full
    // stopped-parent rule).
    if !interrupted {
        spawn_notice_delivery(&inner, &chat_id);
    }
    // A Stop cancels the messages it found waiting; one accepted after it
    // began is still the user's next message and must run.
    let orphans: Vec<RoutedSteer> = if inner
        .shutting_down
        .load(std::sync::atomic::Ordering::Acquire)
    {
        Vec::new()
    } else if interrupted {
        orphans.into_iter().filter(|s| s.after_stop).collect()
    } else {
        orphans
    };
    if !orphans.is_empty() {
        // The dying run accepted these into its mailbox but never confirmed a
        // Steered boundary (idle-reaper race, a mid-turn error discarding
        // queued boundary steers, a parked child death). Their user entries
        // are already in the transcript — a message that shows as sent must
        // never silently not run. Re-dispatch each as a fresh turn
        // (write_user_message dedupes by id; resume is engine-injected).
        let engine = SessionsEngine {
            inner: inner.clone(),
        };
        let chat = chat_id.clone();
        tokio::spawn(async move {
            for steer in orphans {
                let Some(mut request) = steer
                    .request
                    .map(|request| *request)
                    .or_else(|| engine.last_request(&chat))
                else {
                    tracing::warn!(chat = %chat, "orphaned steer lost: no run config to re-dispatch");
                    break;
                };
                request.prompt = steer.prompt.clone();
                request.resume = None;
                tracing::info!(chat = %chat, "re-dispatching steer orphaned by a dying run");
                let Some(host) = engine.inner.doc_host() else {
                    tracing::warn!(chat = %chat, "orphaned steer lost: doc host unavailable");
                    break;
                };
                if let Err(err) = host
                    .dispatch_with_source_context(
                        &engine,
                        &chat,
                        harness_id,
                        request,
                        Some(steer.message_id.clone()),
                        steer.origin_chat_id.clone(),
                    )
                    .await
                {
                    tracing::warn!(chat = %chat, error = %err, "orphaned steer re-dispatch failed");
                    engine
                        .inner
                        .set_status(&chat, SessionStatus::Errored, false);
                    break;
                }
            }
        });
    }
}

/// Auto-resume stagger window (perf 03c): crashed chats re-dispatch spaced
/// by one window each, with per-chat jitter inside the slot.
const RESUME_STAGGER_MS: u64 = 750;

/// Per-chat resume jitter (FNV-1a over the chat id, modulo the stagger
/// window): stable per chat, needs no RNG dependency, and spreads a fleet
/// restart's auto-resumes inside each stagger slot.
fn resume_jitter_ms(chat_id: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in chat_id.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    hash % RESUME_STAGGER_MS
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::{HarnessId, RunRequest, SandboxLevel};

    /// Perf 03c: the auto-resume jitter stays inside one stagger window and
    /// differs between chats — a fleet restart must not stampede, and the
    /// spacing must not collapse to zero for distinct ids.
    #[test]
    fn resume_jitter_is_bounded_and_spreads_chats() {
        for chat_id in ["chat-1", "chat-2", "a", "b", "chat-1"] {
            let jitter = resume_jitter_ms(chat_id);
            assert!(jitter < RESUME_STAGGER_MS, "{chat_id}: {jitter}");
        }
        // Equal inputs jitter equally (stable, no RNG flake).
        assert_eq!(resume_jitter_ms("chat-1"), resume_jitter_ms("chat-1"));
        // Distinct chats usually land in distinct slots; with a 750 ms window
        // and these two ids the hashes genuinely differ.
        assert_ne!(
            resume_jitter_ms("chat-1"),
            resume_jitter_ms("chat-2"),
            "distinct chats must spread"
        );
    }

    #[tokio::test]
    async fn generated_image_failure_is_sanitized_even_inside_subagents() {
        use super::*;
        let dir = tempfile::tempdir().unwrap();
        let sessions = SessionsEngine::new(
            "host".into(),
            Arc::new(RunJournal::open(dir.path().join("journals")).unwrap()),
            Arc::new(HarnessRegistry::new()),
        );
        let source = "/outside/private/source.png";
        let event = AgentEvent::Subagent {
            parent_tool_use_id: "child".into(),
            event: Box::new(AgentEvent::GeneratedImage {
                id: "i:image".into(),
                path: source.into(),
                name: "secret".into(),
                mime_type: "bad".into(),
            }),
        };
        let events = sessions
            .inner
            .prepare_generated_image("chat", event, &mut std::collections::HashSet::new())
            .await;
        assert_eq!(events.len(), 2);
        let mut parts = vec![];
        fold_event_into_parts(
            &mut parts,
            &AgentEvent::ToolCall {
                id: "i".into(),
                call: roboco_proto::ToolCall::Unknown {
                    name: "Generate image".into(),
                    input: None,
                },
            },
        );
        for event in &events {
            sessions.inner.publish("chat", event);
            let AgentEvent::Subagent {
                parent_tool_use_id,
                event,
            } = event
            else {
                panic!("lost owner")
            };
            assert_eq!(parent_tool_use_id, "child");
            fold_event_into_parts(&mut parts, event);
        }
        assert!(matches!(
            parts[0],
            MessagePart::Tool {
                is_error: true,
                resolved: true,
                ..
            }
        ));
        assert!(matches!(parts[1], MessagePart::Error { .. }));
        let journal =
            serde_json::to_string(&sessions.inner.journal.replay("chat", 0).unwrap()).unwrap();
        assert!(!journal.contains(source));
        assert!(!journal.contains("secret"));
    }

    #[test]
    fn cursor_recovery_converts_rich_messages_before_json_encoding() {
        let doc = roboco_doc::SessionDoc::init("cursor-recovery").unwrap();
        let skill = roboco_proto::invocation::Invocation::Skill {
            name: "review \"quoted\"".into(),
            path: "/repo/quoted \"path\"/SKILL.md".into(),
            command: None,
        }
        .link();
        let previous = format!("Previous {skill}\nSecond line with \\ and \"quotes\"");
        doc.push_message(&roboco_doc::SessionMessageEntry {
            origin_chat_id: None,
            id: "u1".into(),
            role: roboco_doc::MessageRole::User,
            parts: vec![roboco_doc::MessagePart::Text {
                id: "u1-text".into(),
                text: previous.clone(),
            }],
            created_at: 0,
            device_id: "test".into(),
            status: None,
            continuation_of: None,
            duration_ms: None,
            provider_turn: None,
        })
        .unwrap();
        let current = format!("Current {skill}\nKeep **Markdown**");
        let delivered = super::cursor_unstarted_history(&doc, "u2", &current, false).unwrap();
        let (_, json) = delivered.split_once('\n').unwrap();
        let parsed: serde_json::Value = serde_json::from_str(json).unwrap();
        assert_eq!(
            parsed["currentUserMessage"],
            roboco_proto::invocation::harness_prompt(&current, roboco_proto::HarnessId::Cursor)
        );
        assert_eq!(
            parsed["previousUserMessages"][0],
            roboco_proto::invocation::harness_prompt(&previous, roboco_proto::HarnessId::Cursor)
        );
    }

    #[test]
    fn cursor_without_a_session_id_retains_only_preceding_user_messages() {
        let doc = roboco_doc::SessionDoc::init("cursor-unstarted").unwrap();
        for (id, role, text) in [
            (
                "u1",
                roboco_doc::MessageRole::User,
                "first interrupted request",
            ),
            ("a1", roboco_doc::MessageRole::Assistant, "partial output"),
            ("u2", roboco_doc::MessageRole::User, "current request"),
            ("u3", roboco_doc::MessageRole::User, "future pending request"),
        ] {
            doc.push_message(&roboco_doc::SessionMessageEntry {
                origin_chat_id: None,
                id: id.into(),
                role,
                parts: vec![roboco_doc::MessagePart::Text {
                    id: format!("{id}-text"),
                    text: text.into(),
                }],
                created_at: 0,
                device_id: "test".into(),
                status: None,
                continuation_of: None,
                duration_ms: None,
                provider_turn: None,
            })
            .unwrap();
        }
        assert_eq!(
            super::cursor_unstarted_history(&doc, "u1", "first interrupted request", false)
                .unwrap(),
            "first interrupted request"
        );
        let prompt = super::cursor_unstarted_history(&doc, "u2", "current request", false).unwrap();
        assert!(prompt.contains("first interrupted request"));
        assert!(prompt.contains("current request"));
        assert!(!prompt.contains("partial output"));
        assert!(!prompt.contains("future pending request"));
        assert!(prompt.contains("do not rerun prior tools or side effects"));
        assert_eq!(
            super::cursor_unstarted_history(&doc, "u2", "current request", true).unwrap(),
            "current request",
            "content from the preceding turn is already in the native session"
        );
        assert!(
            super::cursor_unstarted_history(&doc, "u3", "future pending request", true)
                .unwrap()
                .contains("current request"),
            "an unacknowledged prompt after an older checkpoint must survive"
        );
        assert_eq!(doc.read_entries().unwrap().len(), 4);
    }

    /// The backwards journal scan must answer exactly what the old full forward
    /// replay did: newest non-empty id, cwd from the governing `SessionStarted`.
    #[tokio::test]
    async fn journal_harness_session_matches_forward_scan() {
        fn started(id: &str, cwd: &str) -> AgentEvent {
            AgentEvent::SessionStarted {
                harness: HarnessId::ClaudeCode,
                model: "m".into(),
                tools: Vec::new(),
                cwd: cwd.into(),
                session_id: id.into(),
                assistant_message_id: "a".into(),
            }
        }
        fn done(id: Option<&str>) -> AgentEvent {
            AgentEvent::Done {
                status: roboco_proto::DoneStatus::Completed,
                result: None,
                error: None,
                session_id: id.map(Into::into),
            }
        }
        fn text() -> AgentEvent {
            AgentEvent::TextDelta { text: "t".into() }
        }
        /// The pre-change implementation, verbatim, over a forward replay.
        fn forward(events: Vec<(u64, AgentEvent)>) -> Option<(String, String)> {
            let mut current_cwd = String::new();
            let mut found: Option<(String, String)> = None;
            for (_, event) in events {
                match event {
                    AgentEvent::SessionStarted {
                        session_id, cwd, ..
                    } => {
                        current_cwd = cwd;
                        if !session_id.is_empty() {
                            found = Some((session_id, current_cwd.clone()));
                        }
                    }
                    AgentEvent::Done {
                        session_id: Some(session_id),
                        ..
                    } if !session_id.is_empty() => {
                        found = Some((session_id, current_cwd.clone()));
                    }
                    _ => {}
                }
            }
            found
        }

        /// (chat id, journaled events, expected `(session id, cwd)`).
        type Case = (
            &'static str,
            Vec<AgentEvent>,
            Option<(&'static str, &'static str)>,
        );
        let cases: Vec<Case> = vec![
            ("empty", vec![], None),
            ("no-ids", vec![text(), done(None), done(Some(""))], None),
            (
                "started-only",
                vec![started("s1", "/a"), text()],
                Some(("s1", "/a")),
            ),
            (
                "done-inherits-run-cwd",
                vec![started("s1", "/a"), text(), done(Some("s2"))],
                Some(("s2", "/a")),
            ),
            (
                "done-before-any-started",
                vec![text(), done(Some("s1"))],
                Some(("s1", "")),
            ),
            (
                "empty-started-sets-cwd-for-later-done",
                vec![started("s1", "/a"), started("", "/b"), done(Some("s2"))],
                Some(("s2", "/b")),
            ),
            (
                "later-empty-started-does-not-override",
                vec![
                    started("s1", "/a"),
                    done(Some("s2")),
                    started("", "/b"),
                    text(),
                ],
                Some(("s2", "/a")),
            ),
            (
                "newest-run-wins",
                vec![
                    started("s1", "/a"),
                    done(Some("s1")),
                    started("s3", "/c"),
                    text(),
                ],
                Some(("s3", "/c")),
            ),
            (
                "empty-done-ids-ignored",
                vec![started("s1", "/a"), done(Some("")), done(None)],
                Some(("s1", "/a")),
            ),
            (
                "only-empty-started-then-done-from-older-run",
                vec![
                    started("s1", "/a"),
                    done(Some("s9")),
                    started("", "/b"),
                    done(None),
                ],
                Some(("s9", "/a")),
            ),
        ];

        let dir = tempfile::tempdir().unwrap();
        let sessions = SessionsEngine::new(
            "host".into(),
            Arc::new(RunJournal::open(dir.path().join("journals")).unwrap()),
            Arc::new(HarnessRegistry::new()),
        );
        for (chat, events, want) in cases {
            for event in &events {
                sessions.inner.journal.append(chat, event).unwrap();
            }
            let want = want.map(|(id, cwd)| (id.to_string(), cwd.to_string()));
            let replayed = sessions.inner.journal.replay(chat, 0).unwrap();
            assert_eq!(forward(replayed), want, "{chat}: reference");
            assert_eq!(sessions.inner.journal_harness_session(chat), want, "{chat}");
        }
        // Torn tail: the last complete SessionStarted still governs.
        let path = dir.path().join("journals").join("started-only.jsonl");
        let mut f = std::fs::OpenOptions::new().append(true).open(path).unwrap();
        std::io::Write::write_all(&mut f, b"{\"seq\":3,\"event\":{\"type\":\"done\"").unwrap();
        assert_eq!(
            sessions.inner.journal_harness_session("started-only"),
            Some(("s1".into(), "/a".into()))
        );
    }

    fn request() -> RunRequest {
        RunRequest {
            prompt: "first".into(),
            harness: None,
            model: Some("grok-4.6".into()),
            reasoning: Some(roboco_proto::ReasoningLevel::High),
            model_options: serde_json::Map::new(),
            cwd: "/tmp".into(),
            sandbox: SandboxLevel::WorkspaceWrite,
            auto_approve: true,
            resume: None,
            provider_fork: None,
            attachments: Vec::new(),
            worktree: None,
            mcp: None,
        }
    }

    #[test]
    fn live_routing_requires_the_same_runtime_configuration() {
        let initial = request();
        let config = RuntimeConfig::new(
            HarnessId::Grok,
            &initial,
            Arc::new(roboco_harness::AcpHarness::grok()),
        );

        let mut follow_up = initial.clone();
        follow_up.prompt = "second".into();
        follow_up.resume = Some("session-1".into());
        assert!(config.can_route(HarnessId::Grok, &follow_up));
        assert!(!config.can_route(HarnessId::Devin, &follow_up));

        // Images ride the mailbox: they never force a replacement runtime.
        follow_up.attachments.push("/tmp/image.png".into());
        assert!(config.can_route(HarnessId::Grok, &follow_up));

        // Another client's spelling of the same option value is the same runtime.
        let mut spelled = initial.clone();
        spelled
            .model_options
            .insert("fastMode".into(), serde_json::json!(true));
        let config = RuntimeConfig::new(
            HarnessId::Grok,
            &spelled,
            Arc::new(roboco_harness::AcpHarness::grok()),
        );
        follow_up
            .model_options
            .insert("fastMode".into(), serde_json::json!("true"));
        assert!(config.can_route(HarnessId::Grok, &follow_up));

        follow_up.model = Some("grok-4.5".into());
        assert!(!config.can_route(HarnessId::Grok, &follow_up));
        follow_up.model = initial.model.clone();

        follow_up.reasoning = Some(roboco_proto::ReasoningLevel::Medium);
        assert!(!config.can_route(HarnessId::Grok, &follow_up));
    }

    #[test]
    fn claude_routing_compares_what_the_cli_launches_with() {
        let claude = || -> Arc<dyn Harness> { Arc::new(roboco_harness::ClaudeHarness::new()) };
        let mut initial = request();
        initial.model = Some("claude-opus-5-5".into());
        initial
            .model_options
            .insert("fastMode".into(), serde_json::json!(false));
        let config = RuntimeConfig::new(HarnessId::ClaudeCode, &initial, claude());

        // The CLI never reads the sandbox level; an option that launches no
        // flag in either spelling is the same runtime.
        let mut follow_up = initial.clone();
        follow_up.sandbox = SandboxLevel::DangerFullAccess;
        follow_up.model_options.clear();
        follow_up.worktree = None;
        assert!(config.can_route(HarnessId::ClaudeCode, &follow_up));

        follow_up
            .model_options
            .insert("fastMode".into(), serde_json::json!("true"));
        assert!(!config.can_route(HarnessId::ClaudeCode, &follow_up));
        follow_up.model_options.clear();

        follow_up.cwd = "/elsewhere".into();
        assert!(!config.can_route(HarnessId::ClaudeCode, &follow_up));
    }

    #[test]
    fn clean_tool_ids_ride_verbatim_and_fit_id_re() {
        let id = subagent_doc_id("chat-abc123", "toolu_01EjFLnNhCiMR2PBNKcVvkT");
        assert_eq!(id, "chat-abc123--sub--toolu_01EjFLnNhCiMR2PBNKcVvkT");
        assert!(id.len() <= 128);
        assert!(
            id.chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        );
    }

    #[test]
    fn unclean_or_oversized_ids_hash_deterministically() {
        let dirty = subagent_doc_id("chat", "call/with:odd chars");
        assert!(
            dirty
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-'),
            "{dirty}"
        );
        assert_eq!(dirty, subagent_doc_id("chat", "call/with:odd chars"));
        let long_chat = "c".repeat(100);
        let long = subagent_doc_id(&long_chat, &"t".repeat(64));
        assert!(long.len() <= 128, "{}", long.len());
        // Distinct inputs stay distinct.
        assert_ne!(
            subagent_doc_id("chat", "a:b"),
            subagent_doc_id("chat", "a:c")
        );
    }

    #[tokio::test]
    async fn subagent_sink_flush_clears_dirty_when_nothing_folded() {
        use super::*;
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(roboco_sync::DocsStore::open(dir.path()).unwrap());
        let host = DocHost::new(
            store,
            crate::doc_host::DocHostConfig {
                device_id: "dev-test".into(),
                default_harness: HarnessId::Mock,
            },
        );
        let handle = host.open("chat-sub-flush--sub--t1").unwrap();
        let mut sink = SubagentSink {
            doc_id: "chat-sub-flush--sub--t1".into(),
            doc: handle.doc_arc(),
            entry_id: "entry-1".into(),
            started_at: 0,
            entry_index: None,
            written: Vec::new(),
            folded: Vec::new(),
            dirty: true,
        };
        // Nothing to write is still a completed flush — left set, the flag
        // holds the run loop's commit guard open on a past deadline.
        sink.flush("dev-test");
        assert!(!sink.dirty);
    }

    /// Feed-by-hand harness: the test pushes wire events through a channel
    /// and decides when the stream ends.
    struct FeedHarness {
        feed: Mutex<Option<mpsc::UnboundedReceiver<AgentEvent>>>,
    }

    #[async_trait::async_trait]
    impl Harness for FeedHarness {
        fn id(&self) -> HarnessId {
            HarnessId::Mock
        }
        fn display_name(&self) -> &str {
            "Feed"
        }
        fn supports_steering(&self) -> bool {
            true
        }
        fn steering_mode(&self) -> roboco_proto::SteeringMode {
            roboco_proto::SteeringMode::StepBoundary
        }
        fn reasoning_levels(&self) -> &[roboco_proto::ReasoningLevel] {
            &[roboco_proto::ReasoningLevel::Medium]
        }
        async fn models(&self) -> Result<Vec<roboco_proto::Model>, roboco_harness::HarnessError> {
            Ok(vec![])
        }
        async fn run(
            &self,
            _request: RunRequest,
            _controls: RunControls,
        ) -> Result<
            futures::stream::BoxStream<'static, Result<AgentEvent, roboco_harness::HarnessError>>,
            roboco_harness::HarnessError,
        > {
            let mut feed = self
                .feed
                .lock()
                .unwrap()
                .take()
                .expect("FeedHarness serves one run per test");
            Ok(futures::stream::poll_fn(move |cx| feed.poll_recv(cx).map(|e| e.map(Ok))).boxed())
        }
    }

    // A tagged subagent event that folds to NO parts used to leave its sink
    // dirty forever: the commit branch's guard stayed true on a deadline in
    // the past and the run loop burned a core until real content arrived.
    #[tokio::test]
    async fn empty_subagent_events_commit_once_per_window_not_per_tick() {
        use super::*;
        FLUSH_TICKS.with(|t| t.set(0));
        let (feed, rx) = mpsc::unbounded_channel();
        let registry = HarnessRegistry::new();
        registry.register(Arc::new(FeedHarness {
            feed: Mutex::new(Some(rx)),
        }));
        let dir = tempfile::tempdir().unwrap();
        let core =
            crate::EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock)
                .unwrap();
        let chat = "chat-sink-spin";
        core.sessions
            .dispatch(chat, HarnessId::Mock, request(), None, None)
            .await
            .unwrap();
        feed.send(AgentEvent::SessionStarted {
            harness: HarnessId::Mock,
            model: "mock-1".into(),
            tools: vec![],
            cwd: "/tmp".into(),
            session_id: "hs-1".into(),
            assistant_message_id: "a1".into(),
        })
        .unwrap();
        feed.send(AgentEvent::Subagent {
            parent_tool_use_id: "tool-1".into(),
            event: Box::new(AgentEvent::ReasoningDelta {
                text: String::new(),
            }),
        })
        .unwrap();
        // Well past several commit windows with the stream still open: the
        // loop must idle between commits, not spin on a past deadline.
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        let ticks = FLUSH_TICKS.with(|t| t.get());
        assert!(
            (1..=8).contains(&ticks),
            "commit branch fired {ticks} times in 1s for one empty subagent event"
        );
        assert_eq!(
            core.sessions.session_status(chat).map(|s| s.status),
            Some(SessionStatus::Working),
            "stream still open, turn still running"
        );
        feed.send(AgentEvent::Done {
            status: DoneStatus::Completed,
            result: None,
            error: None,
            session_id: None,
        })
        .unwrap();
        core.sessions.shutdown().await;
    }
}
