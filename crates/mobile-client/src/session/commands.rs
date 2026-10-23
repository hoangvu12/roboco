//! Send, interrupt and respond-input: the busy decision table and the
//! upload-first flow.
//!
//! Every action is an engine RPC (there is no client outbox — ADR 0004 /
//! remote-access story 24): an offline engine refuses the write, and the
//! composer keeps the text.
//!
//! The decision table (`busy × policy × steering × attachments ×
//! capabilities`) is a pure function so its every cell unit-tests; the
//! `autoApprove: false` rule is pinned by a contract test at the engine
//! boundary (PR #700: `true` restarts a parked host and kills its
//! background subagents).

use std::sync::Arc;

use roboco_doc::SessionCommandPayload;
use roboco_proto::{RunRequest, SandboxLevel, UserInputAnswer, WorktreeSpec};

use crate::{ClientError, lock};

use super::composer::HostCapabilities;
use super::pending::{PendingKind, PendingSend, SendState};
use super::SessionCore;

/// What to do with a message typed while the agent is working.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum BusyPolicy {
    /// Park it on the shared queue until the turn ends (the default).
    #[default]
    Queue,
    /// Deliver mid-turn: a steer command into the live turn when the
    /// harness steers mid-turn; otherwise it queues.
    Steer,
    /// Roboco-specific: stop the turn and run this message as the next
    /// turn (`QueueMessage` + `SendQueuedMessageNow` — the engine pauses
    /// the queue on a bare interrupt, so iOS's interrupt-then-queue would
    /// strand the row).
    Interrupt,
}

/// Image bytes picked in the composer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutgoingAttachment {
    pub name: String,
    pub mime_type: String,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct SendRequest {
    pub text: String,
    pub attachments: Vec<OutgoingAttachment>,
    /// Engine-side isolated worktree for this run (new-session "New
    /// worktree"; ticket 09 uses it).
    pub worktree: Option<WorktreeSpec>,
    pub busy: BusyPolicy,
}

impl SendRequest {
    pub fn text(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            ..Default::default()
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SendOutcome {
    /// A new turn was requested; the echo's id is `message_id`.
    Started { message_id: String },
    /// Delivered into the live turn.
    Steered { message_id: String },
    /// Parked on the shared queue.
    Queued { queue_id: String },
}

/// The send decision, computed from the composer snapshot. Pure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Decision {
    /// Park on the shared queue (`QueueMessage`).
    Queue,
    /// Roboco's "Stop and send": queue, then `SendQueuedMessageNow`.
    Interrupt,
    /// A steer command into the live turn.
    Steer,
    /// A fresh run command.
    Run,
}

/// The busy decision table.
///
/// - `starting` = some pending send has kind `Run` and state `Sending`
///   (a Run in flight but not yet adopted counts as busy, so two turns
///   never start — upstream #777);
/// - `busy = turn_running || starting`;
/// - `steer = busy && policy == Steer && caps.mid_turn_steering == Some(true)
///   && attachments.is_empty()`;
/// - `queued = !steer && busy && policy != Interrupt && caps.message_queue &&
///   (attachments.is_empty() || caps.queue_attachments)`;
/// - otherwise `Run` (the steer flag returns `Steer` before the queue check).
pub(crate) fn decide(
    turn_running: bool,
    starting: bool,
    policy: BusyPolicy,
    has_attachments: bool,
    caps: &HostCapabilities,
) -> Decision {
    let busy = turn_running || starting;
    let steer = busy
        && policy == BusyPolicy::Steer
        && caps.mid_turn_steering == Some(true)
        && !has_attachments;
    if steer {
        return Decision::Steer;
    }
    if busy && policy == BusyPolicy::Interrupt {
        return Decision::Interrupt;
    }
    if busy
        && policy != BusyPolicy::Interrupt
        && caps.message_queue
        && (!has_attachments || caps.queue_attachments)
    {
        return Decision::Queue;
    }
    Decision::Run
}

/// The rows count as current while the link proved itself within this
/// (two health-probe intervals).
pub(crate) const ROWS_CURRENT_MS: i64 = 10_000;

/// How long a send waits for the engine's own word on whether the chat is
/// busy before falling back to the rows.
const BUSY_CHECK_BUDGET: std::time::Duration = std::time::Duration::from_secs(3);

/// Clock skew tolerated when judging the engine's own session row on the
/// phone's clock (the rows' staleness check runs on rebased stamps; this
/// one reads the engine's raw ones).
const ENGINE_ROW_SKEW_MS: i64 = 120_000;

/// E1: a send the rows judged idle must be confirmed with the engine when
/// the rows can't vouch for that — the session row still says
/// Working/AwaitingInput but went stale (no heartbeat reached this phone),
/// or the link has not proved itself recently (a thawed app on dead
/// sockets). A `Run` into a turn that is in fact running becomes an
/// immediate steer for a live Claude Code process and cuts the reply off.
/// Pure.
pub(crate) fn confirm_idle_with_engine(
    row: Option<&roboco_proto::Session>,
    proven_ms: Option<i64>,
    now_ms: i64,
) -> bool {
    let row_says_busy = row.is_some_and(|session| {
        matches!(
            session.status,
            roboco_proto::SessionStatus::Working | roboco_proto::SessionStatus::AwaitingInput
        )
    });
    let rows_current = proven_ms.is_some_and(|at| now_ms - at <= ROWS_CURRENT_MS);
    row_says_busy || !rows_current
}

/// The engine's answer (a `WatchSessions` frame): is this chat's turn
/// running? A missing row is idle; an undecodable frame says nothing.
/// Pure.
pub(crate) fn engine_reports_busy(
    frame: serde_json::Value,
    raw_chat_id: &str,
    now: chrono::DateTime<chrono::Utc>,
) -> Option<bool> {
    let decoded = crate::lenient::decode_rows::<roboco_proto::Session>(
        frame,
        &crate::lenient::session_substitutions(),
        "chatId",
    )
    .ok()?;
    let Some(session) = decoded.rows.iter().find(|row| row.chat_id == raw_chat_id) else {
        return Some(false);
    };
    let running = matches!(
        session.status,
        roboco_proto::SessionStatus::Working | roboco_proto::SessionStatus::AwaitingInput
    );
    let age_ms = now.signed_duration_since(session.updated_at).num_milliseconds();
    Some(running && age_ms <= roboco_proto::view::SESSION_STALE_MS + ENGINE_ROW_SKEW_MS)
}

impl SessionCore {
    /// E1: ask the engine whether this chat's turn runs (see
    /// [`confirm_idle_with_engine`]). `None` when it could not answer in
    /// time.
    async fn engine_says_busy(&self) -> Option<bool> {
        let handle = self.engine_handle().ok()?;
        let frame = handle
            .first_item(
                roboco_rpc::methods::WATCH_SESSIONS,
                serde_json::json!({}),
                BUSY_CHECK_BUDGET,
            )
            .await
            .inspect_err(|error| {
                tracing::info!(chat = %self.chat_id, %error, "busy check failed; deciding from the rows");
            })
            .ok()?;
        engine_reports_busy(frame, &self.raw_chat_id, chrono::Utc::now())
    }

    /// Send a message. The pending echo is created before the upload (the
    /// faded user row appears at once — after the E1 busy check, when the
    /// rows could not vouch for an idle chat — rendering the local image bytes
    /// from the read cache under `pending://` refs), replaced in place with
    /// the real host paths once the upload commits, and adopted when the
    /// engine's user entry lands with the same id.
    pub(crate) async fn send(self: Arc<Self>, request: SendRequest) -> Result<SendOutcome, ClientError> {
        let text = request.text.trim_end().to_owned();
        if text.trim().is_empty() && request.attachments.is_empty() {
            return Err(ClientError::InvalidArgument {
                message: "empty message".into(),
            });
        }
        for attachment in &request.attachments {
            if attachment.data.len() > crate::attachments::MAX_ATTACHMENT_BYTES {
                return Err(ClientError::InvalidArgument {
                    message: format!("{} is larger than 24 MB", attachment.name),
                });
            }
        }
        // Rule 2: the engine refuses writes while offline — nothing is
        // queued locally, the composer keeps the text, and no echo appears
        // (EngineHandle::call fails within microseconds when disconnected;
        // the pre-check keeps the "no echo" guarantee exact).
        if !self.engine_handle()?.is_connected() {
            return Err(ClientError::HostUnavailable {
                message: "Engine is offline; reconnecting".into(),
            });
        }

        // One send decides at a time, until its echo exists: a second send
        // racing the first one's busy check must see the first as a Run in
        // flight (`starting`), never decide Run itself.
        let gate = self.send_gate.lock().await;
        let composer = self.composer();
        let starting = composer
            .pending_sends
            .iter()
            .any(|p| p.kind == PendingKind::Run && p.state == SendState::Sending);
        let decide_with = |turn_running: bool| {
            decide(
                turn_running,
                starting,
                request.busy,
                !request.attachments.is_empty(),
                &composer.host.capabilities,
            )
        };
        let mut decision = decide_with(composer.live.turn_running);
        if decision == Decision::Run {
            let row = self.row_inputs().session;
            let proven = self.engine_handle().ok().and_then(|handle| handle.proven_ms());
            if confirm_idle_with_engine(row.as_ref(), proven, crate::engines::now_ms()) {
                // No answer: trust a Working row even though it went stale
                // (queueing on an idle chat only delays the message; a run
                // into a live turn cuts its reply off).
                let row_says_busy = row.as_ref().is_some_and(|session| {
                    matches!(
                        session.status,
                        roboco_proto::SessionStatus::Working
                            | roboco_proto::SessionStatus::AwaitingInput
                    )
                });
                if self.engine_says_busy().await.unwrap_or(row_says_busy) {
                    tracing::info!(chat = %self.chat_id, "the rows read idle but the engine is mid-turn; not starting a new run");
                    decision = decide_with(true);
                }
            }
        }
        let (decision, refs, message_id, config, cwd) = {
            let refs = request
                .attachments
                .iter()
                .map(|attachment| {
                    let upload_id = uuid::Uuid::new_v4().to_string();
                    crate::attachments::pending_ref(
                        &upload_id,
                        &crate::attachments::upload_file_name(&attachment.name),
                    )
                })
                .collect::<Vec<_>>();
            (
                decision,
                refs,
                uuid::Uuid::new_v4().to_string(),
                self.chat_config(),
                self.chat_cwd(),
            )
        };

        // The echo (Run/Steer only — a queue row represents a queued
        // message) is created before the upload so the row paints at once,
        // with `pending://` refs standing in for the host paths.
        let build_command = |paths: &[String]| {
            match decision {
                Decision::Queue | Decision::Interrupt => None,
                Decision::Steer => Some(SessionCommandPayload::Steer {
                    prompt: crate::attachments::with_attachments(&text, paths),
                    message_id: Some(message_id.clone()),
                }),
                Decision::Run => Some(SessionCommandPayload::Run {
                    request: RunRequest {
                        prompt: crate::attachments::with_attachments(&text, paths),
                        harness: config.as_ref().map(|c| c.harness),
                        model: config.as_ref().and_then(|c| c.model.clone()),
                        reasoning: config.as_ref().and_then(|c| c.reasoning),
                        model_options: config
                            .as_ref()
                            .map(|c| c.model_options.clone())
                            .unwrap_or_default(),
                        cwd: cwd.clone().unwrap_or_else(|| "~".to_owned()),
                        sandbox: config
                            .as_ref()
                            .map(|c| c.sandbox)
                            .unwrap_or(SandboxLevel::WorkspaceWrite),
                        // Hard rule (PR #700): the desktop and web both send
                        // false; `true` restarts a parked host and kills its
                        // background subagents.
                        auto_approve: false,
                        resume: None,
                        provider_fork: None,
                        resume_fallback_history: None,
                        attachments: paths.to_vec(),
                        worktree: request.worktree.clone(),
                        mcp: None,
                    },
                    message_id: message_id.clone(),
                }),
            }
        };
        if let Some(command) = build_command(&refs) {
            lock(&self.state).pending.push(PendingSend::new(
                message_id.clone(),
                crate::attachments::with_attachments(&text, &refs),
                match decision {
                    Decision::Steer => PendingKind::Steer,
                    _ => PendingKind::Run,
                },
                crate::engines::now_ms(),
                command,
            ));
            self.refresh();
        }
        drop(gate);
        // Ticket 28: the notification driver's pending-send window opens
        // here (this device issued a Run/Steer `QueueCommand`); it closes
        // when the session is seen Working after it or after 30 s.
        if let Some(inner) = self.client.upgrade() {
            inner.notify.note_send(&self.engine_id, &self.raw_chat_id);
        }
        // Uploads: report one overall progress, then replace the echo's
        // `pending://` refs with the real host paths in place.
        let mut paths = Vec::with_capacity(refs.len());
        if !request.attachments.is_empty() {
            let engine = self.engine_handle()?;
            let Some(cache) = self.attachment_cache() else {
                return Err(ClientError::Closed);
            };
            let total: u64 = request.attachments.iter().map(|a| a.data.len() as u64).sum();
            let done_before = Arc::new(std::sync::atomic::AtomicU64::new(0));
            for (attachment, reference) in request.attachments.iter().zip(&refs) {
                // Own bytes render from the cache before the upload.
                let bytes = Arc::new(attachment.data.clone());
                cache.put(&self.engine_id, reference, bytes.clone());
                let progress = {
                    let core = self.clone();
                    let attachment_bytes = attachment.data.len() as u64;
                    let done_before = done_before.clone();
                    let total = total;
                    move |fraction: f64| {
                        let committed = done_before.load(std::sync::atomic::Ordering::Relaxed)
                            + (fraction * attachment_bytes as f64) as u64;
                        core.set_transfer_progress(Some(
                            (committed as f64 / total.max(1) as f64).min(1.0),
                        ));
                    }
                };
                let uploaded = crate::attachments::upload_attachment(
                    &engine,
                    &attachment.name,
                    attachment.data.clone(),
                    Some(Arc::new(progress)),
                )
                .await;
                match uploaded {
                    Ok(path) => {
                        done_before.fetch_add(
                            attachment.data.len() as u64,
                            std::sync::atomic::Ordering::Relaxed,
                        );
                        cache.put(&self.engine_id, &path, bytes);
                        paths.push(path);
                    }
                    Err(error) => {
                        // Any error removes the echo and the progress.
                        self.remove_echo(&message_id);
                        self.set_transfer_progress(None);
                        return Err(error);
                    }
                }
            }
            self.replace_echo_refs(&message_id, &paths);
            // The issued command (and the echo's retry command) carry the
            // real host paths, not the placeholder refs.
            if let Some(command) = build_command(&paths) {
                let mut state = lock(&self.state);
                if let Some(pending) = state
                    .pending
                    .iter_mut()
                    .find(|p| p.message_id == message_id)
                {
                    pending.command = command.clone();
                }
            }
            self.set_transfer_progress(None);
        }

        let outcome = match decision {
            Decision::Queue => {
                let caps = { self.composer().host.capabilities.clone() };
                let body = queued_body(&text, &refs, &paths, &caps);
                let id = self.queue_message_row(&body, paths, request.busy == BusyPolicy::Queue).await?;
                SendOutcome::Queued { queue_id: id }
            }
            Decision::Interrupt => {
                // "Stop and send": park the row (hold_for_turn_end: false),
                // then send it now — Send-now interrupts the active turn
                // before dispatching, and the engine keeps the queue id as
                // the dispatched user message id. The queued-body rule is
                // the same as the Queue outcome's.
                let body = queued_body(&text, &refs, &paths, &self.composer().host.capabilities);
                let id = self.queue_message_row(&body, paths, false).await?;
                let sent = self
                    .engine_handle()?
                    .call(
                        roboco_rpc::methods::SEND_QUEUED_MESSAGE_NOW,
                        serde_json::json!({ "chatId": self.chat_id, "id": id }),
                    )
                    .await?;
                if sent.get("sent").and_then(|v| v.as_bool()) != Some(true) {
                    return Err(ClientError::HostError {
                        message:
                            "The engine did not confirm the action. The message may have already left the queue."
                                .into(),
                    });
                }
                SendOutcome::Started { message_id: id }
            }
            Decision::Steer => {
                let command = build_command(&paths).or_else(|| {
                    lock(&self.state)
                        .pending
                        .iter()
                        .find(|p| p.message_id == message_id)
                        .map(|p| p.command.clone())
                });
                self.issue_command(command.expect("steer command")).await?;
                SendOutcome::Steered {
                    message_id: message_id.clone(),
                }
            }
            Decision::Run => {
                let command = build_command(&paths).or_else(|| {
                    lock(&self.state)
                        .pending
                        .iter()
                        .find(|p| p.message_id == message_id)
                        .map(|p| p.command.clone())
                });
                self.issue_command(command.expect("run command")).await?;
                SendOutcome::Started {
                    message_id: message_id.clone(),
                }
            }
        };
        if matches!(
            outcome,
            SendOutcome::Started { .. } | SendOutcome::Steered { .. }
        ) {
            lock(&self.state).last_submitted = Some(message_id);
        }
        self.recompute_composer();
        Ok(outcome)
    }

    /// `QueueMessage`, remembering the row as this client's.
    async fn queue_message_row(
        &self,
        body: &str,
        attachments: Vec<String>,
        hold_for_turn_end: bool,
    ) -> Result<String, ClientError> {
        let id = self
            .engine_handle()?
            .call(
                roboco_rpc::methods::QUEUE_MESSAGE,
                serde_json::json!({
                    "chatId": self.chat_id,
                    "text": body,
                    "attachments": attachments,
                    "holdForTurnEnd": hold_for_turn_end,
                }),
            )
            .await?
            .get("id")
            .and_then(|v| v.as_str())
            .map(str::to_owned)
            .ok_or_else(|| ClientError::HostError {
                message: "QueueMessage returned no id".into(),
            })?;
        lock(&self.state).mine_queued.insert(id.clone());
        self.recompute_composer();
        Ok(id)
    }

    /// `QueueCommand {kind:"interrupt"}`: the engine cancels earlier pending
    /// Run/Steer commands, pauses the queue and cancels sends held for the
    /// turn end.
    pub(crate) async fn interrupt(&self) -> Result<(), ClientError> {
        self.issue_command(SessionCommandPayload::Interrupt {})
            .await?;
        self.recompute_composer();
        Ok(())
    }

    /// `QueueCommand {kind:"respondInput"}`. Free text ("Other…") is sent
    /// as a single label.
    pub(crate) async fn respond_input(
        &self,
        request_id: &str,
        answers: Vec<UserInputAnswer>,
    ) -> Result<(), ClientError> {
        self.issue_command(SessionCommandPayload::RespondInput {
            request_id: request_id.to_owned(),
            answers,
        })
        .await?;
        self.recompute_composer();
        Ok(())
    }

    /// Park a message on the shared queue directly. Returns the row id.
    pub(crate) async fn enqueue(
        &self,
        text: &str,
        attachments: Vec<String>,
        hold_for_turn_end: bool,
    ) -> Result<String, ClientError> {
        if text.trim().is_empty() {
            // The engine's own refusal (the wire table's message), failed
            // fast client-side.
            return Err(ClientError::InvalidArgument {
                message: "queued message text required".into(),
            });
        }
        self.queue_message_row(text, attachments, hold_for_turn_end).await
    }

    /// Issue one `QueueCommand` RPC and mark the matching pending send
    /// acked (the engine holds it durably).
    async fn issue_command(&self, command: SessionCommandPayload) -> Result<(), ClientError> {
        let message_id = message_id_of(&command).map(str::to_owned);
        let reply = self
            .engine_handle()?
            .call(
                roboco_rpc::methods::QUEUE_COMMAND,
                serde_json::json!({
                    "chatId": self.chat_id,
                    "command": command,
                    "transfers": [],
                }),
            )
            .await;
        match reply {
            Ok(_) => {
                if let Some(message_id) = message_id {
                    let mut state = lock(&self.state);
                    if let Some(pending) = state
                        .pending
                        .iter_mut()
                        .find(|p| p.message_id == message_id)
                    {
                        pending.restart_clock(crate::engines::now_ms());
                    }
                }
                Ok(())
            }
            Err(error) => {
                // Any RPC error removes the echo; the composer keeps the
                // text (the platform shows "Couldn't send: {error}").
                if let Some(message_id) = message_id {
                    self.remove_echo(&message_id);
                }
                self.set_transfer_progress(None);
                Err(error)
            }
        }
    }

    /// "Not delivered — tap to retry": restart the grace clocks of the
    /// failed sends, re-issue the commands that never got an ack (same
    /// message id — the engine dedupes the user entry by it), and
    /// `RetryDelivery` for the acked ones (the engine re-issues dead
    /// Run/Steer attempts under a new command id with the same message id).
    /// The banner clears at once (the restarted clocks read `Sending`), and
    /// the transcript re-reads in full: a send the engine already wrote but
    /// the stream missed is adopted by that reset.
    pub(crate) async fn retry_delivery(&self) -> Result<(), ClientError> {
        // A re-issued command re-arms the notification driver's
        // pending-send window (ticket 28), like the original send.
        if let Some(inner) = self.client.upgrade() {
            inner.notify.note_send(&self.engine_id, &self.raw_chat_id);
        }
        let mut reissue: Vec<SessionCommandPayload> = Vec::new();
        let mut engine_retry = false;
        {
            let mut state = lock(&self.state);
            let now = crate::engines::now_ms();
            for pending in state.pending.iter_mut() {
                if pending.state != SendState::Failed {
                    continue;
                }
                if pending.acked_at_ms != 0 {
                    engine_retry = true;
                    pending.restart_clock(now);
                } else {
                    reissue.push(pending.command.clone());
                }
            }
        }
        self.refresh();
        self.heal.notify_one();
        for command in reissue {
            let _ = self.issue_command(command).await;
        }
        if engine_retry {
            let _ = self
                .engine_handle()?
                .call(
                    roboco_rpc::methods::RETRY_DELIVERY,
                    serde_json::json!({ "chatId": self.chat_id }),
                )
                .await;
        }
        self.refresh();
        Ok(())
    }
}

/// The queued row's text: the bare text (the placeholder when it is empty)
/// when the trailer is not needed — no attachments, or
/// `clean_attachment_text` — otherwise the trailer form.
fn queued_body(
    text: &str,
    refs: &[String],
    paths: &[String],
    caps: &HostCapabilities,
) -> String {
    if refs.is_empty() || caps.clean_attachment_text {
        if text.is_empty() {
            crate::attachments::ATTACHMENT_ONLY_TEXT.to_owned()
        } else {
            text.to_owned()
        }
    } else {
        crate::attachments::with_attachments(text, paths)
    }
}

fn message_id_of(command: &SessionCommandPayload) -> Option<&str> {
    match command {
        SessionCommandPayload::Run { message_id, .. } => Some(message_id),
        SessionCommandPayload::Steer {
            message_id: Some(message_id),
            ..
        } => Some(message_id),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn caps(
        message_queue: bool,
        queue_attachments: bool,
        mid_turn_steering: Option<bool>,
    ) -> HostCapabilities {
        HostCapabilities {
            message_queue,
            queue_attachments,
            mid_turn_steering,
            ..Default::default()
        }
    }

    /// The busy × policy × steering × attachments × capabilities table.
    #[test]
    fn decision_table() {
        let full = caps(true, true, Some(true));
        // Idle → always a fresh run, whatever the policy.
        for policy in [BusyPolicy::Queue, BusyPolicy::Steer, BusyPolicy::Interrupt] {
            assert_eq!(decide(false, false, policy, false, &full), Decision::Run);
            assert_eq!(decide(false, false, policy, true, &full), Decision::Run);
        }
        // Busy + Queue policy (the default) → parked on the queue.
        assert_eq!(decide(true, false, BusyPolicy::Queue, false, &full), Decision::Queue);
        // Busy + Queue with attachments → queued only when the engine
        // accepts queue attachments.
        assert_eq!(decide(true, false, BusyPolicy::Queue, true, &full), Decision::Queue);
        let no_attach = caps(true, false, Some(true));
        assert_eq!(
            decide(true, false, BusyPolicy::Queue, true, &no_attach),
            Decision::Run,
            "without queue-attachments the send becomes a fresh run"
        );
        // Busy + Steer + mid-turn steering + text → a steer command.
        assert_eq!(decide(true, false, BusyPolicy::Steer, false, &full), Decision::Steer);
        // Steer with attachments never steers: it queues (or runs).
        assert_eq!(decide(true, false, BusyPolicy::Steer, true, &full), Decision::Queue);
        // No mid-turn steering → Steer degrades to the queue.
        let no_steer = caps(true, true, Some(false));
        assert_eq!(decide(true, false, BusyPolicy::Steer, false, &no_steer), Decision::Queue);
        assert_eq!(
            decide(true, false, BusyPolicy::Steer, false, &caps(true, true, None)),
            Decision::Queue
        );
        // Busy + Interrupt → the Roboco stop-and-send path, never a plain
        // queue row (a bare interrupt pauses the queue; the row would wait
        // for a prompt that never comes).
        assert_eq!(decide(true, false, BusyPolicy::Interrupt, false, &full), Decision::Interrupt);
        assert_eq!(decide(true, false, BusyPolicy::Interrupt, true, &full), Decision::Interrupt);
        // A Run in flight but not adopted counts as busy (two turns never
        // start, #777).
        assert_eq!(decide(false, true, BusyPolicy::Queue, false, &full), Decision::Queue);
        // Without the message-queue capability a busy send falls through to
        // a fresh run command (the engine holds it until the turn ends).
        let no_queue = caps(false, true, Some(true));
        assert_eq!(decide(true, false, BusyPolicy::Queue, false, &no_queue), Decision::Run);
    }

    fn session(status: roboco_proto::SessionStatus, age_ms: i64) -> roboco_proto::Session {
        let updated = chrono::Utc::now() - chrono::TimeDelta::milliseconds(age_ms);
        roboco_proto::Session {
            last_completed_turn: None,
            chat_id: "c1".into(),
            device_id: "engine".into(),
            status,
            started_at: Some(updated),
            updated_at: updated,
            limit: None,
            scheduled_resume: None,
        }
    }

    /// E1: an idle-looking send is confirmed with the engine when the rows
    /// can't vouch for it — never on a link that just proved itself with an
    /// idle row.
    #[test]
    fn an_idle_send_is_confirmed_when_the_rows_cannot_vouch_for_it() {
        use roboco_proto::SessionStatus::{AwaitingInput, Idle, Working};
        let now = 1_000_000;
        let fresh = Some(now - 2_000);
        let old = Some(now - ROWS_CURRENT_MS - 1);
        // Current rows, idle: decide locally (no extra round trip).
        assert!(!confirm_idle_with_engine(Some(&session(Idle, 0)), fresh, now));
        assert!(!confirm_idle_with_engine(None, fresh, now));
        // The link has not proved itself lately (a thawed app): ask.
        assert!(confirm_idle_with_engine(Some(&session(Idle, 0)), old, now));
        assert!(confirm_idle_with_engine(None, None, now));
        // A Working/AwaitingInput row that went stale reads idle locally,
        // but the turn may well still run: ask.
        assert!(confirm_idle_with_engine(Some(&session(Working, 60_000)), fresh, now));
        assert!(confirm_idle_with_engine(Some(&session(AwaitingInput, 60_000)), fresh, now));
    }

    #[test]
    fn the_engines_session_row_decides_busy() {
        use roboco_proto::SessionStatus::{Idle, Working};
        let now = chrono::Utc::now();
        let frame = |rows: Vec<roboco_proto::Session>| serde_json::to_value(rows).unwrap();
        assert_eq!(
            engine_reports_busy(frame(vec![session(Working, 1_000)]), "c1", now),
            Some(true)
        );
        assert_eq!(
            engine_reports_busy(frame(vec![session(Idle, 1_000)]), "c1", now),
            Some(false)
        );
        // Another chat's turn is not this one's; no row is idle.
        assert_eq!(
            engine_reports_busy(frame(vec![session(Working, 1_000)]), "other", now),
            Some(false)
        );
        // A Working row the engine stopped heartbeating long ago (a run
        // that died without settling) is not busy, even with clock skew
        // allowed for.
        assert_eq!(
            engine_reports_busy(frame(vec![session(Working, 10 * 60_000)]), "c1", now),
            Some(false)
        );
        // An unreadable answer says nothing.
        assert_eq!(engine_reports_busy(serde_json::json!("nope"), "c1", now), None);
    }

    /// The busy check rides the real wire: a one-shot `WatchSessions` read
    /// on the calls connection answers for an idle chat.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_busy_check_reads_the_engines_sessions() {
        let rig = crate::test_support::Rig::start().await;
        rig.core
            .workspace
            .create_chat("e1-chat", None, Some(&rig.core.device_id), None, None)
            .unwrap();
        let scoped = rig.client.scope(&rig.engine_id, "e1-chat");
        let handle = rig.client.open_session(&scoped).unwrap();
        assert_eq!(handle.core.engine_says_busy().await, Some(false));
        rig.stop().await;
    }
}
