//! The per-chat session: one chat's transcript mirror, its composer state,
//! and every command a viewer can issue.
//!
//! Roboco's engine is reached directly (ADR 0004), so every action is an
//! RPC on the owning engine — `WatchDocMessages` reset/delta frames feed
//! the layout, `QueueCommand` carries run/steer/interrupt/respondInput,
//! the queue RPCs and the edit lease handle queued messages, and
//! `Mutate setChatConfig` stores chat config. There is no Loro session
//! doc and no command ledger: pending sends are tracked client-side and
//! adopted by entry id.
//!
//! Warm sessions: at most [`WARM_SESSION_CAP`] detached sessions stay
//! subscribed; the least recently used are evicted beyond that (never one
//! that is view-attached, streaming, or has pending sends).

pub(crate) mod commands;
pub(crate) mod composer;
pub(crate) mod pending;
pub(crate) mod queue;
pub(crate) mod transcript;

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use roboco_doc::MessageStatus;
use roboco_doc::MessagePart;
use roboco_doc::parts::SubagentStatus;
use roboco_doc::schema::SessionMessageEntry;
use roboco_engine_client::registry::ScopedId;
use roboco_proto::ContextUsage;
use tokio::sync::{watch, Notify};
use tokio_util::sync::CancellationToken;

use crate::engines::EngineHandle;
use crate::{ClientError, lock};

pub use commands::{BusyPolicy, OutgoingAttachment, SendOutcome, SendRequest};
pub use composer::{ComposerState, HostCapabilities, HostInfo, InputRequest, LiveStatus, RoomState};
pub use pending::{PendingKind, PendingSend, SendState, UNDELIVERED_GRACE_MS};
pub use queue::{QueueEditAction, QueueEditFinish, QueueEditLease, QueueEditStart, QueueGate, QueueItem};
pub use transcript::apply_frame_arc;

// ── subagents (upstream #899, engine-local) ────────────────────────────────

/// Where a spawned subagent stands (its spawn chip's `subagent_status`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubagentState {
    Running,
    Done,
    Failed,
}

/// A subagent this session's agent spawned: one linked spawn chip (the
/// chip IS the index — the engine stamps the doc id onto it; there is no
/// listing endpoint). Direct children only; nested spawns live in the
/// subagent's own transcript.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubagentRow {
    /// Its transcript doc (`{chatId}--sub--{id}`, scoped on this client)
    /// — what [`Client::open_subagent`] takes.
    pub doc_id: String,
    /// What the spawn asked for ("Audit grapheme handling").
    pub title: String,
    pub state: SubagentState,
    /// The engine's one-line live tail of its output.
    pub tail: Option<String>,
    /// The model the spawn picked, when it named one.
    pub model: Option<String>,
    /// When the spawning reply was written.
    pub spawned_at_ms: i64,
}

/// The subagents of one transcript, in spawn order: every spawn chip the
/// engine linked to a subagent doc. `resolved` alone is NOT "done" —
/// under the eager-done policy the spawn tool's own result lands while
/// the subagent still runs; `subagent_status` is the lifecycle. Pure.
pub(crate) fn subagents_of(entries: &[Arc<SessionMessageEntry>]) -> Vec<SubagentRow> {
    let mut rows: Vec<SubagentRow> = Vec::new();
    for entry in entries {
        let at = entry.created_at;
        for part in &entry.parts {
            let MessagePart::Tool {
                call,
                is_error,
                resolved,
                subagent_ref: Some(doc_id),
                subagent_status,
                subagent_tail,
                ..
            } = part
            else {
                continue;
            };
            if !call.is_subagent_spawn() {
                continue;
            }
            let state = match subagent_status {
                Some(SubagentStatus::Running) => SubagentState::Running,
                Some(SubagentStatus::Failed) => SubagentState::Failed,
                _ if *is_error => SubagentState::Failed,
                Some(SubagentStatus::Done) => SubagentState::Done,
                None if *resolved => SubagentState::Done,
                None => SubagentState::Running,
            };
            let detail = roboco_proto::view::tool_chip_content(call).1;
            // A re-spawn (a steered subagent) updates its row in place.
            let title = detail.trim();
            let row = SubagentRow {
                doc_id: doc_id.clone(),
                title: if title.is_empty() {
                    "Subagent".to_owned()
                } else {
                    title.to_owned()
                },
                state,
                tail: subagent_tail
                    .as_deref()
                    .map(str::trim)
                    .filter(|t| !t.is_empty())
                    .map(str::to_owned),
                model: call.subagent_model().map(str::to_owned),
                spawned_at_ms: at,
            };
            match rows.iter_mut().find(|r| r.doc_id == row.doc_id) {
                Some(existing) => *existing = row,
                None => rows.push(row),
            }
        }
    }
    rows
}

/// Subagents whose spawn chips still run — the sessions-list "● N" pill
/// (plus the working side chats the view layers on top). Pure.
fn running_subagent_count(entries: &[Arc<SessionMessageEntry>]) -> u32 {
    subagents_of(entries)
        .into_iter()
        .filter(|row| row.state == SubagentState::Running)
        .count() as u32
}

use crate::cache::EngineCacheHandle;
use crate::{Client, ClientInner};

/// Detached sessions that stay subscribed; the least recently used beyond
/// this are evicted.
pub const WARM_SESSION_CAP: usize = 6;
/// Sessions `preload_sessions` opens in front-page order.
pub const PRELOAD_CAP: usize = 4;

// ── the snapshot ───────────────────────────────────────────────────────────

/// The transcript at one revision: host entries (Arc-shared, pointer-equal
/// across snapshots while unchanged — the layout engine's row-reuse key),
/// this device's un-adopted sends, and the live flags.
#[derive(Debug, Clone)]
pub struct SessionSnapshot {
    pub chat_id: String,
    /// Monotonic per session; bumps on every transcript change.
    pub revision: u64,
    /// Host-written entries.
    pub entries: Vec<Arc<SessionMessageEntry>>,
    /// This device's un-adopted sends (the pending echo rows).
    pub pending: Vec<PendingSend>,
    /// A turn is running (the engine's own status is Working, or the tail
    /// streams). Never set by a pending send alone (#777).
    pub working: bool,
    pub working_since_ms: Option<i64>,
    /// The newest host entry is still streaming.
    pub streaming: bool,
    /// Content is present (a cache seed or the first sync landed).
    pub hydrated: bool,
    /// The shown content is an opening-tail preview, not the full history.
    pub history_pending: bool,
    pub context_usage: Option<ContextUsage>,
}

impl SessionSnapshot {
    /// Host entries only (what the layout renders).
    pub fn host_entries(&self) -> &[Arc<SessionMessageEntry>] {
        &self.entries
    }

    /// Subagents spawned in this transcript, in spawn order (pull after
    /// `SessionChanged`). Empty while the transcript has no linked spawn
    /// chips.
    pub fn subagents(&self) -> Vec<SubagentRow> {
        subagents_of(&self.entries)
    }
}

// ── mutable state ──────────────────────────────────────────────────────────

/// Mutable per-session state behind one lock.
#[derive(Default)]
pub(crate) struct SessionState {
    pub(crate) entries: Vec<Arc<SessionMessageEntry>>,
    /// Engine-computed entry digests (ticket 12), stored opaquely.
    pub(crate) digests: HashMap<String, String>,
    pub(crate) pending: Vec<PendingSend>,
    pub(crate) queue: Vec<queue::WireQueuedMessage>,
    /// Queue row ids this client's `QueueMessage` minted.
    pub(crate) mine_queued: std::collections::HashSet<String>,
    /// Lease ids this client currently holds.
    pub(crate) held_leases: std::collections::HashSet<String>,
    pub(crate) queue_actions_pending: std::collections::HashSet<String>,
    pub(crate) queue_error: Option<String>,
    pub(crate) last_submitted: Option<String>,
    pub(crate) transfer_progress: Option<f64>,
    pub(crate) hydrated: bool,
    /// Seeded from the offline cache: an opening-tail preview must not
    /// replace it.
    pub(crate) hydrated_from_cache: bool,
    /// A full authoritative reset has landed in this handle.
    pub(crate) has_full_reset: bool,
    pub(crate) history_pending: bool,
    pub(crate) context_usage: Option<ContextUsage>,
    pub(crate) transcript_revision: u64,
    pub(crate) composer_revision: u64,
    pub(crate) composer: Arc<ComposerState>,
    /// Next resubscribe attempt, while the subscription is down.
    pub(crate) resubscribe_at_ms: Option<i64>,
}

/// One open chat. Shared by the registry and every handle.
pub(crate) struct SessionCore {
    pub(crate) engine_id: String,
    /// The scoped chat id (`engine:v1:…`) — what the FFI sees.
    pub(crate) chat_id: String,
    /// The engine's raw chat id — what the wire sees.
    pub(crate) raw_chat_id: String,
    /// A subagent transcript (`{chatId}--sub--{id}`): read-only — no chat
    /// row exists, so nothing marks it seen and no send is expected (the
    /// UI shows no composer; upstream #899's viewer contract).
    pub(crate) read_only: bool,
    pub(crate) client: Weak<ClientInner>,
    pub(crate) cache: EngineCacheHandle,
    pub(crate) state: Mutex<SessionState>,
    snapshot_tx: watch::Sender<Arc<SessionSnapshot>>,
    /// Wakes the watch task (composer/snapshot recompute).
    pub(crate) wake: Notify,
    view_attached: AtomicBool,
    touched_ms: std::sync::atomic::AtomicI64,
    /// Cancels this session's tasks (eviction, shutdown); replaced when an
    /// evicted-but-held session revives.
    tasks: Mutex<CancellationToken>,
    subscription_live: AtomicBool,
}

impl SessionCore {
    pub(crate) fn new(
        engine_id: &str,
        chat_id: &str,
        raw_chat_id: &str,
        client: Weak<ClientInner>,
        cache: EngineCacheHandle,
        tasks: CancellationToken,
        read_only: bool,
    ) -> Arc<Self> {
        // Seed from the offline cache: the chat paints before any frame.
        let seed = cache.load_transcript_state(raw_chat_id);
        let hydrated_from_cache = seed
            .as_ref()
            .is_some_and(|state| !state.entries.is_empty());
        let (entries, digests) = match seed {
            Some(state) if !state.entries.is_empty() => (
                state.entries.into_iter().map(Arc::new).collect::<Vec<_>>(),
                state.digests,
            ),
            _ => (Vec::new(), HashMap::new()),
        };
        let state = SessionState {
            entries,
            digests,
            hydrated: hydrated_from_cache,
            hydrated_from_cache,
            ..Default::default()
        };
        let revision = if hydrated_from_cache { 1 } else { 0 };
        let (snapshot_tx, _) = watch::channel(Arc::new(SessionSnapshot {
            chat_id: chat_id.to_owned(),
            revision,
            entries: state.entries.clone(),
            pending: Vec::new(),
            working: false,
            working_since_ms: None,
            streaming: false,
            hydrated: hydrated_from_cache,
            history_pending: false,
            context_usage: None,
        }));
        Arc::new(Self {
            engine_id: engine_id.to_owned(),
            chat_id: chat_id.to_owned(),
            raw_chat_id: raw_chat_id.to_owned(),
            read_only,
            client,
            cache,
            state: Mutex::new(state),
            snapshot_tx,
            wake: Notify::new(),
            view_attached: AtomicBool::new(false),
            touched_ms: std::sync::atomic::AtomicI64::new(crate::engines::now_ms()),
            tasks: Mutex::new(tasks),
            subscription_live: AtomicBool::new(false),
        })
    }

    /// A handle to the owning engine.
    /// A handle to the owning engine. Fails with `Closed` once the client
    /// shut down or the engine was forgotten.
    pub(crate) fn engine_handle(&self) -> Result<EngineHandle, ClientError> {
        let inner = self
            .client
            .upgrade()
            .ok_or(ClientError::Closed)?;
        Client::from_inner(inner).engine_handle(&self.engine_id)
    }

    /// The fleet watch (engine rows, connection state), when the client
    /// still lives.
    pub(crate) fn fleet(&self) -> Option<watch::Receiver<Arc<crate::FleetSnapshot>>> {
        self.client.upgrade().map(|inner| inner.fleet.subscribe())
    }

    /// The attachment byte cache, while the client lives.
    pub(crate) fn attachment_cache(
        &self,
    ) -> Option<Arc<crate::attachments::AttachmentCache>> {
        self.client.upgrade().map(|inner| inner.attachments.clone())
    }

    /// The engine advertises ticket 12's `transcript-resume-v1`.
    pub(crate) fn resume_capable(&self) -> bool {
        self.engine_handle()
            .ok()
            .and_then(|handle| handle.engine_info())
            .is_some_and(|info| {
                info.capabilities
                    .iter()
                    .any(|c| c == roboco_proto::capabilities::TRANSCRIPT_RESUME_V1)
            })
    }

    pub(crate) fn subscription_live(&self) -> bool {
        self.subscription_live.load(Ordering::Acquire)
    }

    pub(crate) fn set_subscription_live(&self) {
        self.subscription_live.store(true, Ordering::Release);
        self.recompute_composer();
    }

    pub(crate) fn set_subscription_down(&self, retry_at_ms: Option<i64>) {
        self.subscription_live.store(false, Ordering::Release);
        lock(&self.state).resubscribe_at_ms = retry_at_ms;
        self.recompute_composer();
    }

    pub(crate) fn cancel(&self) {
        lock(&self.tasks).cancel();
    }

    pub(crate) fn is_cancelled(&self) -> bool {
        lock(&self.tasks).is_cancelled()
    }

    /// A fresh task token (revival): a child of the client's own token.
    pub(crate) fn revive_tasks(&self) -> CancellationToken {
        let token = self
            .client
            .upgrade()
            .map(|inner| inner.client_cancel.child_token())
            .unwrap_or_default();
        *lock(&self.tasks) = token.clone();
        token
    }

    pub(crate) fn view_attached(&self) -> bool {
        self.view_attached.load(Ordering::Acquire)
    }

    pub(crate) fn touch(&self) {
        self.touched_ms
            .store(crate::engines::now_ms(), Ordering::Release);
    }

    pub(crate) fn touched_ms(&self) -> i64 {
        self.touched_ms.load(Ordering::Acquire)
    }

    pub(crate) fn has_pending_sends(&self) -> bool {
        !lock(&self.state).pending.is_empty()
    }

    /// The current snapshot (O(1): an `Arc` clone).
    pub(crate) fn snapshot(&self) -> Arc<SessionSnapshot> {
        self.snapshot_tx.borrow().clone()
    }

    pub(crate) fn composer(&self) -> Arc<ComposerState> {
        lock(&self.state).composer.clone()
    }

    /// The chat's run config: the engine's `Chat.config`, or the optimistic
    /// override from `set_session_config` when one exists. The override
    /// lives in 07's workspace store (wire-string form); this converts it
    /// back to the proto form the RunRequest carries.
    pub(crate) fn chat_config(&self) -> Option<roboco_proto::ChatConfig> {
        if let Some(store) = self.workspace_store() {
            if let Some(wire) = store.session_config(&self.chat_id) {
                return wire_to_proto(wire);
            }
        }
        self.chat_row().and_then(|chat| chat.config)
    }

    /// 07's workspace store, when the client still lives.
    fn workspace_store(&self) -> Option<Arc<crate::workspace::WorkspaceStore>> {
        self.client
            .upgrade()
            .and_then(|inner| inner.workspace.get().cloned())
    }

    /// `cwd: chat.cwd ?? <space path> ?? "~"` for the RunRequest — or,
    /// for a chat created a moment ago (ticket 09), the pending chat's
    /// cwd (the `NewSession.cwd` override, the space path, or `~`).
    pub(crate) fn chat_cwd(&self) -> Option<String> {
        if let Some(chat) = self.chat_row() {
            if let Some(cwd) = chat.cwd.clone() {
                return Some(cwd);
            }
            let space_id = chat.space_id.clone()?;
            let inner = self.client.upgrade()?;
            let state = lock(&inner.engines);
            let entry = state
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == self.engine_id)?;
            return entry
                .rows
                .spaces
                .iter()
                .find(|space| space.id == space_id)
                .map(|space| space.path.clone());
        }
        self.workspace_store()
            .and_then(|store| store.pending_chat(&self.chat_id))
            .map(|pending| pending.cwd)
    }

    /// The chat row off the engine's cached rows (raw ids on the wire side,
    /// matched by the raw chat id).
    pub(crate) fn chat_row(&self) -> Option<roboco_proto::Chat> {
        let inner = self.client.upgrade()?;
        let state = lock(&inner.engines);
        let entry = state
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == self.engine_id)?;
        entry
            .rows
            .chats
            .iter()
            .find(|chat| chat.id == self.raw_chat_id)
            .cloned()
    }

    /// Engine-row inputs for the composer.
    pub(crate) fn row_inputs(&self) -> composer::RowInputs {
        let inner = self.client.upgrade();
        let mut inputs = composer::RowInputs {
            chat: None,
            session: None,
            engine_connected: false,
            engine_name: None,
            engine_device_id: None,
            capabilities: Vec::new(),
            retry_at_ms: None,
        };
        let Some(inner) = inner else {
            return inputs;
        };
        let state = lock(&inner.engines);
        let Some(entry) = state
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == self.engine_id)
        else {
            return inputs;
        };
        inputs.chat = entry
            .rows
            .chats
            .iter()
            .find(|chat| chat.id == self.raw_chat_id)
            .cloned();
        inputs.session = entry
            .rows
            .sessions
            .iter()
            .find(|session| session.chat_id == self.raw_chat_id)
            .cloned();
        inputs.engine_connected = entry.state == crate::EngineState::Connected;
        inputs.retry_at_ms = entry.retry_at_ms;
        inputs.engine_device_id = entry
            .info
            .as_ref()
            .map(|info| info.device_id.clone())
            .or_else(|| entry.paired.device_id.clone());
        inputs.engine_name = (|| {
            let device = inputs.engine_device_id.as_ref()?;
            let device = entry.rows.devices.iter().find(|d| &d.id == device)?;
            let name = device.name.trim();
            (!name.is_empty() && name != "unknown-device").then(|| name.to_owned())
        })();
        if let Some(info) = &entry.info {
            inputs.capabilities = info.capabilities.clone();
        }
        inputs
    }

    /// The chat harness's mid-turn steering flag, from the live catalog.
    pub(crate) fn mid_turn_steering(&self) -> Option<bool> {
        let harness = self
            .chat_config()
            .map(|config| wire_harness(&config.harness))
            .or_else(|| {
                self.chat_row()
                    .and_then(|chat| chat.config.map(|c| wire_harness(&c.harness)))
            })
            .unwrap_or_else(|| "claude-code".to_owned());
        let inner = self.client.upgrade()?;
        crate::catalog_live::mid_turn_steering(&inner, &self.engine_id, &harness)
    }

    // ── snapshot publication ─────────────────────────────────────────────

    /// Recompute the pending-send states and the live flags, then publish
    /// the snapshot (when anything visible changed) and recompute the
    /// composer.
    pub(crate) fn refresh(&self) {
        // Lock order (the crate's invariant): engines → catalog → registry
        // → session state — never the reverse. Every row read happens
        // before the state lock is taken.
        let engine_connected = self
            .engine_handle()
            .map(|handle| handle.is_connected())
            .unwrap_or(false);
        let session_indicator = self
            .row_inputs()
            .session
            .as_ref()
            .map(|session| {
                roboco_proto::view::effective_indicator(Some(session), chrono::Utc::now())
            })
            .unwrap_or(roboco_proto::view::Indicator::None);
        let grace = self
            .client
            .upgrade()
            .map(|inner| inner.timings.undelivered_grace.as_millis() as i64)
            .unwrap_or(UNDELIVERED_GRACE_MS);
        let now = crate::engines::now_ms();
        let (states_changed, publish) = {
            let mut state = lock(&self.state);
            let mut states_changed = false;
            for pending in state.pending.iter_mut() {
                let next = pending::send_state(now, pending.acked_at_ms, engine_connected, grace);
                if pending.state != next {
                    pending.state = next;
                    states_changed = true;
                }
            }
            // The snapshot's pending echo list and live flags move with the
            // rows, not only with state transitions: an added or adopted
            // echo (the same `Sending` state either way) must publish too.
            let streaming = state
                .entries
                .last()
                .is_some_and(|e| e.status == Some(MessageStatus::Streaming));
            let working = session_indicator == roboco_proto::view::Indicator::Working || streaming;
            let published = self.snapshot_tx.borrow().clone();
            let publish = published.pending != state.pending
                || published.working != working
                || published.streaming != streaming
                || published.hydrated != state.hydrated;
            (states_changed, publish)
        };
        if publish {
            self.publish_transcript_bump();
        }
        self.recompute_composer();
        if states_changed {
            // 07's workspace rows read this chat's headline send state (the
            // composer's rule: `Failed` if any send failed, else the
            // oldest) and re-derive. The state lock is released before the
            // store call — the crate's lock order never nests the session
            // state under the store's.
            let headline = pending::headline_state(&lock(&self.state).pending);
            if let Some(store) = self.workspace_store() {
                store.set_send_state(&self.chat_id, headline);
            }
        }
    }

    pub(crate) fn publish_transcript_bump(&self) {
        let revision = {
            let mut state = lock(&self.state);
            state.transcript_revision += 1;
            state.transcript_revision
        };
        self.publish_transcript(revision);
    }

    /// Publish the snapshot at `revision` and emit `SessionChanged`.
    pub(crate) fn publish_transcript(&self, revision: u64) {
        let inputs = self.row_inputs();
        let (snapshot, running_subagents) = {
            let state = lock(&self.state);
            let session = inputs.session.as_ref();
            let streaming = state
                .entries
                .last()
                .is_some_and(|e| e.status == Some(MessageStatus::Streaming));
            let working_from_row = session.is_some_and(|session| {
                roboco_proto::view::effective_indicator(Some(session), chrono::Utc::now())
                    == roboco_proto::view::Indicator::Working
            });
            let running = running_subagent_count(&state.entries);
            let snapshot = SessionSnapshot {
                chat_id: self.chat_id.clone(),
                revision,
                entries: state.entries.clone(),
                pending: state.pending.clone(),
                working: working_from_row || streaming,
                working_since_ms: session
                    .filter(|s| {
                        roboco_proto::view::effective_indicator(Some(s), chrono::Utc::now())
                            != roboco_proto::view::Indicator::None
                    })
                    .and_then(|s| s.started_at.map(|t| t.timestamp_millis())),
                streaming,
                hydrated: state.hydrated,
                history_pending: state.history_pending,
                context_usage: state.context_usage,
            };
            (snapshot, running)
        };
        self.snapshot_tx.send_replace(Arc::new(snapshot));
        // The "● N" pill: the running-subagent count rides the same
        // publication as the rows (a chip flipping to Done is a transcript
        // change). Written after the state lock is gone — the store is
        // last in the crate's lock order.
        self.sync_running_subagents(running_subagents);
        if let Some(inner) = self.client.upgrade() {
            inner.pump.session(&self.chat_id, revision);
        }
    }

    /// Push the running-subagent count to 07's workspace store (the
    /// sessions-list "● N" pill). Read-only sessions never write: nested
    /// spawns live in the subagent's own doc, which has no sidebar row.
    pub(crate) fn sync_running_subagents(&self, running: u32) {
        if self.read_only {
            return;
        }
        if let Some(store) = self.workspace_store() {
            store.set_running_subagents(&self.chat_id, running);
        }
    }

    /// The session left the registry (evicted or backgrounded): its pill
    /// count is no longer maintained — clear it, so a stale "● N" never
    /// outlives the subscription that proved it.
    pub(crate) fn clear_running_subagents(&self) {
        self.sync_running_subagents(0);
    }

    // ── echo maintenance ─────────────────────────────────────────────────

    pub(crate) fn remove_echo(&self, message_id: &str) {
        lock(&self.state)
            .pending
            .retain(|p| p.message_id != message_id);
        self.publish_transcript_bump();
    }

    /// Replace the echo's `pending://` refs with the real host paths, in
    /// place (the row never flickers).
    pub(crate) fn replace_echo_refs(&self, message_id: &str, paths: &[String]) {
        let mut state = lock(&self.state);
        if let Some(pending) = state
            .pending
            .iter_mut()
            .find(|p| p.message_id == message_id)
        {
            if paths.is_empty() {
                return;
            }
            pending.text = crate::attachments::with_attachments(
                &strip_trailer(&pending.text),
                paths,
            );
            let parsed = crate::attachments::parse_user_message(&pending.text);
            pending.visible_text = parsed.text;
            pending.images = parsed.images.into_iter().map(|i| i.path).collect();
        }
        drop(state);
        self.publish_transcript_bump();
    }

    pub(crate) fn set_transfer_progress(&self, progress: Option<f64>) {
        lock(&self.state).transfer_progress = progress;
        self.recompute_composer();
    }

    /// Tell the session whether a transcript view is on screen. On a
    /// false→true edge: ensure the subscription is live and mark seen.
    pub(crate) fn set_view_attached(self: &Arc<Self>, attached: bool) {
        let was = self.view_attached.swap(attached, Ordering::AcqRel);
        self.touch();
        if attached && !was {
            self.revive_if_needed();
            self.mark_seen();
        }
    }

    pub(crate) fn mark_seen(&self) {
        // A subagent transcript has no chat row; marking it seen would
        // fire a `markChatSeen` for a doc id the engine does not know.
        if self.read_only {
            return;
        }
        if let Some(inner) = self.client.upgrade() {
            // 07's mark_seen: the monotonic local stamp (never rolled
            // back) plus the fire-and-forget `Mutate markChatSeen`.
            Client::from_inner(inner).mark_seen(&self.chat_id);
        }
    }

    /// An evicted session whose handle is still held: restart its tasks so
    /// the subscription is live again.
    pub(crate) fn revive_if_needed(self: &Arc<Self>) {
        if !self.is_cancelled() {
            return;
        }
        let Some(inner) = self.client.upgrade() else {
            return;
        };
        self.revive_tasks();
        if inner.sessions.re_register(self) {
            spawn_session_tasks(self);
        }
    }
}

/// The visible text of a pending echo (trailer stripped), for rebuilding it
/// with real paths.
fn strip_trailer(text: &str) -> String {
    crate::attachments::parse_user_message(text).text
}

/// The harness wire string (`claude-code`, `mock`, …).
pub(crate) fn wire_harness(harness: &roboco_proto::HarnessId) -> String {
    serde_json::to_value(harness)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
        .unwrap_or_default()
}

/// Recompute every open session's composer (the live catalog changed, so
/// `mid_turn_steering` flags may have moved). The cores are cloned out
/// before any recompute: the registry lock never nests under the engines
/// lock a recompute takes (the crate's lock order is engines → registry →
/// state, and `publish` holds engines while reading the registry).
pub(crate) fn recompute_all_composers(inner: &Arc<ClientInner>) {
    let cores: Vec<Arc<SessionCore>> = lock(&inner.sessions.inner)
        .sessions
        .values()
        .cloned()
        .collect();
    for core in cores {
        core.recompute_composer();
    }
}

/// Rebuild a typed chat config from 07's wire-string form. The strings
/// were validated when the override was set, so the parse never fails in
/// practice; a malformed entry (a hand-edited store) just reads as absent.
fn wire_to_proto(wire: crate::workspace::SessionConfig) -> Option<roboco_proto::ChatConfig> {
    let parse = |value: &str| {
        serde_json::from_value::<roboco_proto::HarnessId>(serde_json::Value::String(
            value.to_owned(),
        ))
        .ok()
    };
    let harness = parse(&wire.harness)?;
    let reasoning = match wire.reasoning.as_deref() {
        None => None,
        Some(level) => {
            serde_json::from_value::<roboco_proto::ReasoningLevel>(serde_json::Value::String(
                level.to_owned(),
            ))
            .ok()
        }
    };
    Some(roboco_proto::ChatConfig {
        harness,
        model: wire.model,
        reasoning,
        model_options: wire
            .model_options
            .into_iter()
            .map(|(k, v)| (k, serde_json::Value::String(v)))
            .collect(),
        sandbox: wire.sandbox,
    })
}

// ── the watch task ─────────────────────────────────────────────────────────

/// Recompute on local wakes, engine-row changes, and the earliest pending
/// grace deadline — the `Failed` flip emits `ComposerChanged` at the moment
/// it happens, not on the next unrelated event.
async fn watch_task(core: Arc<SessionCore>, cancel: CancellationToken) {
    let Some(mut fleet) = core.fleet() else {
        return;
    };
    loop {
        let grace = core
            .client
            .upgrade()
            .map(|inner| inner.timings.undelivered_grace)
            .unwrap_or(Duration::from_millis(UNDELIVERED_GRACE_MS as u64));
        let deadline = {
            let state = lock(&core.state);
            pending::next_grace_deadline(&state.pending, grace.as_millis() as i64).map(|ms| {
                let remaining = (ms - crate::engines::now_ms()).max(0);
                tokio::time::Instant::now() + Duration::from_millis(remaining as u64)
            })
        };
        let timer = async {
            match deadline {
                Some(deadline) => tokio::time::sleep_until(deadline).await,
                None => std::future::pending::<()>().await,
            }
        };
        tokio::select! {
            _ = cancel.cancelled() => return,
            _ = core.wake.notified() => core.refresh(),
            changed = fleet.changed() => {
                if changed.is_err() {
                    return;
                }
                let _ = fleet.borrow_and_update();
                core.refresh();
            }
            _ = timer => core.refresh(),
        }
    }
}

/// Spawn (or restart) one session's three tasks.
fn spawn_session_tasks(core: &Arc<SessionCore>) {
    let cancel = core.tasks_token();
    let transcript_core = core.clone();
    let transcript_cancel = cancel.clone();
    crate::runtime::shared().spawn(async move {
        transcript::run(transcript_core, transcript_cancel).await;
    });
    let queue_core = core.clone();
    let queue_cancel = cancel.clone();
    crate::runtime::shared().spawn(async move {
        queue::run(queue_core, queue_cancel).await;
    });
    let watch_core = core.clone();
    crate::runtime::shared().spawn(async move {
        watch_task(watch_core, cancel).await;
    });
}

impl SessionCore {
    /// The task cancellation token (the spawn helper's seam).
    pub(crate) fn tasks_token(&self) -> CancellationToken {
        lock(&self.tasks).clone()
    }
}

// ── the registry ───────────────────────────────────────────────────────────

/// The warm-session map plus the config overrides.
pub(crate) struct SessionRegistry {
    pub(crate) inner: Mutex<RegistryInner>,
}

#[derive(Default)]
pub(crate) struct RegistryInner {
    pub(crate) sessions: HashMap<String, Arc<SessionCore>>,
}

impl SessionRegistry {
    pub(crate) fn new() -> Self {
        Self {
            inner: Mutex::new(RegistryInner::default()),
        }
    }

    pub(crate) fn get(&self, chat_id: &str) -> Option<Arc<SessionCore>> {
        lock(&self.inner).sessions.get(chat_id).cloned()
    }

    pub(crate) fn re_register(&self, core: &Arc<SessionCore>) -> bool {
        let mut registry = lock(&self.inner);
        // Only when nobody replaced it with a fresh core.
        if registry.sessions.contains_key(&core.chat_id) {
            return false;
        }
        registry
            .sessions
            .insert(core.chat_id.clone(), core.clone());
        true
    }

    /// Open (or return the open) session. Never blocks on the network: the
    /// disk cache seeds it and the subscription starts in the background.
    pub(crate) fn open(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
        chat_id: &str,
        raw_chat_id: &str,
    ) -> Arc<SessionCore> {
        self.open_inner(inner, engine_id, chat_id, raw_chat_id, false)
    }

    /// A subagent's transcript doc, read-only (upstream #899's viewer):
    /// same seeding and subscription, but [`SessionCore::read_only`] — no
    /// mark-seen, no pill count — and the doc id is the registry key.
    pub(crate) fn open_read_only(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
        doc_id: &str,
        raw_doc_id: &str,
    ) -> Arc<SessionCore> {
        self.open_inner(inner, engine_id, doc_id, raw_doc_id, true)
    }

    fn open_inner(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
        chat_id: &str,
        raw_chat_id: &str,
        read_only: bool,
    ) -> Arc<SessionCore> {
        {
            let registry = lock(&self.inner);
            if let Some(core) = registry.sessions.get(chat_id) {
                if !core.is_cancelled() {
                    core.touch();
                    return core.clone();
                }
            }
        }
        let tasks = inner.client_cancel.child_token();
        let cache = Client::from_inner(inner.clone())
            .engine_handle(engine_id)
            .expect("engine registered")
            .cache()
            .clone();
        let core = SessionCore::new(
            engine_id,
            chat_id,
            raw_chat_id,
            Arc::downgrade(inner),
            cache,
            tasks,
            read_only,
        );
        {
            let mut registry = lock(&self.inner);
            registry.sessions.insert(chat_id.to_owned(), core.clone());
        }
        spawn_session_tasks(&core);
        self.evict();
        core
    }

    /// Drop the least recently used detached, quiet sessions past the cap.
    /// A session is never evicted while view-attached, while its tail is
    /// streaming, or while it has pending sends.
    pub(crate) fn evict(&self) {
        let evicted: Vec<Arc<SessionCore>> = {
            let mut registry = lock(&self.inner);
            let mut idle: Vec<(i64, String)> = registry
                .sessions
                .values()
                .filter(|core| {
                    !core.view_attached()
                        && !core.has_pending_sends()
                        && !core.snapshot().streaming
                })
                .map(|core| (core.touched_ms(), core.chat_id.clone()))
                .collect();
            if idle.len() <= WARM_SESSION_CAP {
                return;
            }
            idle.sort();
            let excess = idle.len() - WARM_SESSION_CAP;
            idle.into_iter()
                .take(excess)
                .filter_map(|(_, chat_id)| registry.sessions.remove(&chat_id))
                .collect()
        };
        // Off the registry lock (the store is last in the lock order):
        // the pill count a dropped session proved is cleared with it.
        for core in evicted {
            tracing::debug!(chat = %core.chat_id, "evicting warm session");
            core.cancel();
            core.clear_running_subagents();
        }
    }

    /// `on_background`: close the subscriptions of detached sessions that
    /// are not streaming and have no pending sends.
    pub(crate) fn close_background(&self) {
        let dropped: Vec<Arc<SessionCore>> = {
            let mut registry = lock(&self.inner);
            let quiet: Vec<Arc<SessionCore>> = registry
                .sessions
                .values()
                .filter(|core| {
                    !core.view_attached()
                        && !core.has_pending_sends()
                        && !core.snapshot().streaming
                })
                .cloned()
                .collect();
            quiet
                .into_iter()
                .filter_map(|core| registry.sessions.remove(&core.chat_id))
                .collect()
        };
        // Off the registry lock: the pill counts go with the sessions.
        for core in dropped {
            core.cancel();
            core.clear_running_subagents();
        }
    }

    /// Open session ids, most recently used first.
    pub(crate) fn ids(&self) -> Vec<String> {
        self.ids_with_engines()
            .into_iter()
            .map(|(chat_id, _)| chat_id)
            .collect()
    }

    /// Open session ids with their engines, most recently used first.
    pub(crate) fn ids_with_engines(&self) -> Vec<(String, String)> {
        let registry = lock(&self.inner);
        let mut cores: Vec<Arc<SessionCore>> = registry.sessions.values().cloned().collect();
        cores.sort_by_key(|core| std::cmp::Reverse(core.touched_ms()));
        cores
            .iter()
            .map(|core| (core.chat_id.clone(), core.engine_id.clone()))
            .collect()
    }
}

// ── the handle ─────────────────────────────────────────────────────────────

/// Stops a [`SessionHandle::watch`] when dropped.
#[derive(Debug)]
pub struct SnapshotWatch {
    cancel: CancellationToken,
}

impl Drop for SnapshotWatch {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

/// One open chat. Cheap to clone; all clones share the session.
#[derive(Clone)]
pub struct SessionHandle {
    pub(crate) core: Arc<SessionCore>,
}

impl std::fmt::Debug for SessionHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SessionHandle")
            .field("chat_id", &self.core.chat_id)
            .finish()
    }
}

impl SessionHandle {
    pub fn chat_id(&self) -> &str {
        &self.core.chat_id
    }

    /// NEW: the engine owning this chat.
    pub fn engine_id(&self) -> &str {
        &self.core.engine_id
    }

    /// The current transcript (O(1): an `Arc` clone).
    pub fn snapshot(&self) -> Arc<SessionSnapshot> {
        self.core.snapshot()
    }

    /// The raw subscription (Rust consumers).
    pub fn subscribe(&self) -> watch::Receiver<Arc<SessionSnapshot>> {
        self.core.snapshot_tx_subscribe()
    }

    /// Callback-style subscription: `f` gets the current snapshot at once,
    /// then every newer one (coalesced). Runs on a client runtime thread;
    /// dropping the returned guard stops it.
    pub fn watch(&self, f: impl Fn(Arc<SessionSnapshot>) + Send + Sync + 'static) -> SnapshotWatch {
        self.core.watch(f)
    }

    /// Composer + status strip state (pull after `ComposerChanged`).
    pub fn composer(&self) -> Arc<ComposerState> {
        self.core.composer()
    }

    /// Plain text of one entry's text parts (copy / share). For the User
    /// role the attachment trailer and Appshot context are stripped.
    pub fn message_text(&self, entry_id: &str) -> Option<String> {
        let snapshot = self.core.snapshot();
        let entry = snapshot.entries.iter().find(|e| e.id == entry_id)?;
        let texts: Vec<&str> = entry
            .parts
            .iter()
            .filter_map(|p| match p {
                roboco_doc::MessagePart::Text { text, .. } => Some(text.as_str()),
                _ => None,
            })
            .collect();
        let joined = texts.join("\n\n");
        Some(if entry.role == roboco_doc::MessageRole::User {
            crate::attachments::parse_user_message(&joined).text
        } else {
            joined
        })
    }

    /// A transcript view is on/off screen (marks seen on attach).
    pub fn set_view_attached(&self, attached: bool) {
        self.core.set_view_attached(attached);
    }

    pub fn mark_seen(&self) {
        self.core.mark_seen();
    }

    /// Send: a new turn when idle; while working, queue (or steer, or
    /// stop-and-send, per `busy`).
    pub async fn send(&self, request: SendRequest) -> Result<SendOutcome, ClientError> {
        self.core.clone().send(request).await
    }

    /// Stop the live turn.
    pub async fn interrupt(&self) -> Result<(), ClientError> {
        self.core.interrupt().await
    }

    /// Answer the open question panel.
    pub async fn respond_input(
        &self,
        request_id: &str,
        answers: Vec<roboco_proto::UserInputAnswer>,
    ) -> Result<(), ClientError> {
        self.core.respond_input(request_id, answers).await
    }

    /// Park a message on the shared queue directly. Returns the row id.
    pub async fn enqueue(
        &self,
        text: &str,
        attachments: Vec<String>,
        hold_for_turn_end: bool,
    ) -> Result<String, ClientError> {
        self.core.enqueue(text, attachments, hold_for_turn_end).await
    }

    /// Move a queue row to `to` (clamped engine-side; refused locally when
    /// out of range or gated).
    pub async fn move_queued(&self, id: &str, to: u32) -> Result<bool, ClientError> {
        self.core.move_queued(id, to).await
    }

    /// Nudge a row one slot up (`-1`) or down (`+1`).
    pub async fn move_queued_by(&self, id: &str, delta: i32) -> Result<bool, ClientError> {
        self.core.move_queued_by(id, delta).await
    }

    pub async fn begin_queued_edit(&self, id: &str, instance_id: &str) -> QueueEditStart {
        self.core.begin_queued_edit(id, instance_id).await
    }

    pub async fn renew_queued_edit(&self, lease: &QueueEditLease) -> bool {
        self.core.renew_queued_edit(lease).await
    }

    pub async fn finish_queued_edit(
        &self,
        lease: QueueEditLease,
        action: QueueEditAction,
        text: Option<String>,
        attachments: Option<Vec<String>>,
    ) -> QueueEditFinish {
        self.core
            .finish_queued_edit(lease, action, text, attachments)
            .await
    }

    /// Deliver a queued row now (interrupting the live turn if needed).
    pub async fn send_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        self.core.send_queued_now(id).await
    }

    /// Steer a queued row into the live turn (never interrupts).
    pub async fn steer_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        self.core.steer_queued_now(id).await
    }

    /// A row's primary action: text steers (never interrupts); attachment
    /// rows send now (PR #592).
    pub async fn deliver_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        self.core.deliver_queued_now(id).await
    }

    /// Remove a queued row (applied only after the engine acks).
    pub async fn remove_queued(&self, id: &str) -> Result<bool, ClientError> {
        self.core.remove_queued(id).await
    }

    pub fn clear_queue_error(&self) {
        lock(&self.core.state).queue_error = None;
        self.core.recompute_composer();
    }

    /// "Not delivered — tap to retry".
    pub async fn retry_delivery(&self) -> Result<(), ClientError> {
        self.core.retry_delivery().await
    }

    /// Fetch a tool part's full output by its doc-resident blob ref.
    pub async fn fetch_tool_blob(&self, blob_ref: &str) -> Result<String, ClientError> {
        crate::files::fetch_tool_blob(&self.core.engine_handle()?, blob_ref).await
    }

    /// Transcript-level facts the platform needs without the rows.
    pub fn transcript_status(&self) -> TranscriptFacts {
        let snapshot = self.core.snapshot();
        TranscriptFacts {
            revision: snapshot.revision,
            hydrated: snapshot.hydrated,
            entry_count: snapshot.entries.len(),
            pending_count: snapshot.pending.len(),
            streaming: snapshot.streaming,
            working: snapshot.working,
            working_since_ms: snapshot.working_since_ms,
            last_entry_id: snapshot.entries.last().map(|e| e.id.clone()),
            history_pending: snapshot.history_pending,
        }
    }
}

/// The `transcript_status()` payload (what the FFI record mirrors).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranscriptFacts {
    pub revision: u64,
    pub hydrated: bool,
    pub entry_count: usize,
    pub pending_count: usize,
    pub streaming: bool,
    pub working: bool,
    pub working_since_ms: Option<i64>,
    pub last_entry_id: Option<String>,
    pub history_pending: bool,
}

impl SessionCore {
    pub(crate) fn snapshot_tx_subscribe(&self) -> watch::Receiver<Arc<SessionSnapshot>> {
        self.snapshot_tx.subscribe()
    }

    pub(crate) fn watch(&self, f: impl Fn(Arc<SessionSnapshot>) + Send + Sync + 'static) -> SnapshotWatch {
        let mut rx = self.snapshot_tx.subscribe();
        let cancel = CancellationToken::new();
        let token = cancel.clone();
        crate::runtime::shared().spawn(async move {
            let first = rx.borrow_and_update().clone();
            f(first);
            loop {
                tokio::select! {
                    _ = token.cancelled() => return,
                    changed = rx.changed() => if changed.is_err() { return },
                }
                f(rx.borrow_and_update().clone());
            }
        });
        SnapshotWatch { cancel }
    }
}

// ── the Client surface (ticket 08's half of `client_ffi/mod.rs`) ───────────

impl Client {
    /// Open (or return the open) session — instant from the cache; the
    /// subscription starts in the background. The id is accepted when the
    /// chat is in the workspace **or** its scoped id names a paired engine
    /// (a chat created a moment ago opens before `WatchChats` echoes it);
    /// anything else is `NotFound`.
    pub fn open_session(&self, chat_id: &str) -> Result<SessionHandle, ClientError> {
        let (handle, raw_id) = self.resolve(chat_id)?;
        let engine_id = handle.engine_id().to_owned();
        let core = self
            .inner
            .sessions
            .open(&self.inner, &engine_id, chat_id, &raw_id);
        Ok(SessionHandle { core })
    }

    /// An already-open session.
    pub fn session(&self, chat_id: &str) -> Option<SessionHandle> {
        let core = self.inner.sessions.get(chat_id)?;
        Some(SessionHandle { core })
    }

    /// Open a subagent's transcript read-only — the doc a spawn chip's
    /// `subagent_ref` names (`{chatId}--sub--{suffix}`, scoped on this
    /// client). The engine serves the doc like any chat doc, so a running
    /// subagent streams live, a finished one reads its persisted copy, and
    /// one that resumes streams its new output (upstream #899's fix, by
    /// construction: there is no frozen copy to get stuck on). The doc
    /// has no chat row, so the session is read-only — no `markChatSeen`,
    /// no sends — and the transcript cache still persists it, so a
    /// subagent already read shows offline after a restart.
    pub fn open_subagent(&self, doc_id: &str) -> Result<SessionHandle, ClientError> {
        let (handle, raw_doc_id) = self.resolve(doc_id)?;
        if !raw_doc_id.contains("--sub--") {
            return Err(ClientError::InvalidArgument {
                message: format!("not a subagent doc: {doc_id}"),
            });
        }
        let engine_id = handle.engine_id().to_owned();
        let core = self
            .inner
            .sessions
            .open_read_only(&self.inner, &engine_id, doc_id, &raw_doc_id);
        Ok(SessionHandle { core })
    }

    /// The view closed; the session stays warm until evicted.
    pub fn close_session(&self, chat_id: &str) {
        if let Some(handle) = self.session(chat_id) {
            handle.set_view_attached(false);
        }
        self.inner.sessions.evict();
    }

    /// Warm the most relevant sessions (front-page order: pinned, then
    /// sections, then recent), skipping disconnected engines.
    pub fn preload_sessions(&self) {
        for chat_id in front_page_order(&self.inner).into_iter().take(PRELOAD_CAP) {
            let _ = self.open_session(&chat_id);
        }
    }

    /// Open session ids, most recently used first.
    pub fn open_session_ids(&self) -> Vec<String> {
        self.inner.sessions.ids()
    }

    /// The chat's config: `Chat.config` mapped to wire strings, or the
    /// optimistic override from `set_session_config` when one exists.
    /// The engine's own `Chat.config` (no override) — the echo watcher's
    /// comparison, so an override never matches itself. Reads the live
    /// engine rows, never the debounced disk cache.
    fn row_config(&self, chat_id: &str) -> Option<roboco_proto::ChatConfig> {
        let scoped = roboco_engine_client::registry::ScopedId::parse(chat_id).ok()?;
        let state = lock(&self.inner.engines);
        state
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == scoped.engine.0)?
            .rows
            .chats
            .iter()
            .find(|chat| chat.id == scoped.raw_id)
            .and_then(|chat| chat.config.clone())
    }

    /// 07's workspace store (installed right after construction).
    fn workspace_store(&self) -> Option<Arc<crate::workspace::WorkspaceStore>> {
        self.inner.workspace.get().cloned()
    }

    /// Store a chat config (PR #664 immediate refresh): validate, store the
    /// optimistic override in 07's workspace store, emit, send `Mutate
    /// setChatConfig`, clear the override when the engine's own `WatchChats`
    /// frame shows an equal config; on error revert and return it.
    pub async fn set_session_config(
        &self,
        chat_id: &str,
        config: roboco_proto::ChatConfig,
    ) -> Result<(), ClientError> {
        let (handle, _raw_id) = self.resolve(chat_id)?;
        let wanted = crate::workspace::SessionConfig::of_chat(&config);
        if let Some(store) = self.workspace_store() {
            store.set_config_overlay(chat_id, wanted.clone());
        }
        // The composer refreshes immediately (its own reads see the
        // store's overlay at once).
        if let Some(handle) = self.session(chat_id) {
            handle.core.recompute_composer();
        }
        let result = handle
            .call(
                roboco_rpc::methods::MUTATE,
                serde_json::json!({
                    "op": "setChatConfig",
                    "chatId": chat_id,
                    "config": config,
                }),
            )
            .await;
        let clear_override = |inner: &Arc<ClientInner>, chat_id: &str| {
            if let Some(store) = inner.workspace.get() {
                store.clear_config_overlay(chat_id);
            }
            if let Some(handle) = Client::from_inner(inner.clone()).session(chat_id) {
                handle.core.recompute_composer();
            }
        };
        match result {
            Ok(_) => {
                // Watch for the engine echo; clear the override when the
                // row matches (bounded: 30 s, then the row check happens on
                // the next open anyway).
                let inner = self.inner.clone();
                let chat_id = chat_id.to_owned();
                crate::runtime::shared().spawn(async move {
                    let mut fleet = inner.fleet.subscribe();
                    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
                    loop {
                        // The ENGINE's row, not the merged view (which would
                        // read the override itself and match at once).
                        let now_matches = Client::from_inner(inner.clone())
                            .row_config(&chat_id)
                            .is_some_and(|current| {
                                crate::workspace::SessionConfig::of_chat(&current) == wanted
                            });
                        if now_matches {
                            clear_override(&inner, &chat_id);
                            return;
                        }
                        tokio::select! {
                            _ = tokio::time::sleep_until(deadline) => return,
                            changed = fleet.changed() => {
                                if changed.is_err() { return; }
                                let _ = fleet.borrow_and_update();
                            }
                        }
                    }
                });
                Ok(())
            }
            Err(error) => {
                clear_override(&self.inner, chat_id);
                Err(error)
            }
        }
    }

    pub(crate) fn from_inner(inner: Arc<ClientInner>) -> Client {
        Client { inner }
    }
}

/// Front-page order (pinned, then sections, then recent) over the merged
/// engine rows — the preload order. 07's workspace derive owns the real
/// projection; this reads the same frames directly so it works before 07
/// lands and follows the same precedence.
fn front_page_order(inner: &Arc<ClientInner>) -> Vec<String> {
    let state = lock(&inner.engines);
    let mut pinned: Vec<String> = Vec::new();
    let mut sectioned: Vec<String> = Vec::new();
    let mut recent: Vec<String> = Vec::new();
    for entry in &state.entries {
        let engine_id = &entry.paired.engine_id;
        let key = entry
            .info
            .as_ref()
            .map(|info| profile_key(info, entry.paired.device_id.as_deref()))
            .unwrap_or_else(|| "local".to_owned());
        let scope = |raw: &str| ScopedId::encode(&roboco_engine_client::registry::EngineKey(engine_id.clone()), raw);
        if let Some(sidebar) = &entry.sidebar {
            if let Some(pins) = sidebar.pins_by_profile.get(&key) {
                pinned.extend(pins.iter().map(|raw| scope(raw)));
            }
            if let Some(sections) = sidebar.sections_by_profile.get(&key) {
                for section in sections {
                    sectioned.extend(section.session_ids.iter().map(|raw| scope(raw)));
                }
            }
        }
        let space_ids: std::collections::HashSet<&str> =
            entry.rows.spaces.iter().map(|s| s.id.as_str()).collect();
        let mut chats: Vec<&roboco_proto::Chat> = entry
            .rows
            .chats
            .iter()
            .filter(|chat| {
                !chat.archived
                    && chat.parent_chat_id.is_none()
                    && chat
                        .space_id
                        .as_deref()
                        .is_none_or(|id| space_ids.contains(id))
            })
            .collect();
        chats.sort_by(|a, b| {
            let key = |chat: &roboco_proto::Chat| {
                chat.last_message_at.unwrap_or(chat.created_at)
            };
            key(b).cmp(&key(a)).then_with(|| a.id.cmp(&b.id))
        });
        recent.extend(chats.iter().map(|chat| scope(&chat.id)));
    }
    let mut seen = std::collections::HashSet::new();
    pinned
        .into_iter()
        .chain(sectioned)
        .chain(recent)
        .filter(|id| seen.insert(id.clone()))
        .collect()
}

/// The sidebar profile key of one engine (07's rule; every Roboco engine is
/// local-scoped in practice).
fn profile_key(info: &roboco_proto::EngineInfo, device_id: Option<&str>) -> String {
    use roboco_proto::WorkspaceScope;
    match info.workspace_scope {
        WorkspaceScope::Local => "local".to_owned(),
        scope => {
            let name = match scope {
                WorkspaceScope::Synced => "synced",
                WorkspaceScope::Development => "development",
                WorkspaceScope::Local => "local",
            };
            match device_id {
                Some(device_id) => format!("{name}:{device_id}"),
                None => name.to_owned(),
            }
        }
    }
}

/// A session core with no client behind it, for pure-logic tests.
#[cfg(test)]
pub(crate) mod testkit {
    use super::*;

    pub(crate) fn core() -> SessionCore {
        let core = SessionCore::new(
            "engine-1",
            "engine-1:v1:chat",
            "chat",
            std::sync::Weak::new(),
            cache_handle(),
            tokio_util::sync::CancellationToken::new(),
            false,
        );
        Arc::try_unwrap(core).unwrap_or_else(|_| unreachable!("the only reference"))
    }

    fn cache_handle() -> crate::cache::EngineCacheHandle {
        let dir = tempfile::tempdir().unwrap();
        let cache = roboco_engine_client::cache::EngineCache::new(dir.path());
        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
        crate::cache::EngineCacheHandle::new("engine-1".into(), cache, tx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One assistant entry carrying the given tool parts.
    fn entry(parts: Vec<roboco_doc::MessagePart>) -> Arc<SessionMessageEntry> {
        Arc::new(SessionMessageEntry {
            id: "e1".into(),
            role: roboco_doc::MessageRole::Assistant,
            parts,
            created_at: 1_000,
            device_id: "dev".into(),
            status: Some(roboco_doc::MessageStatus::Complete),
            continuation_of: None,
            duration_ms: None,
        })
    }

    /// One spawn chip (an `Agent` tool call) with the subagent stamps.
    fn spawn(
        doc_id: &str,
        status: Option<roboco_doc::parts::SubagentStatus>,
        resolved: bool,
        is_error: bool,
    ) -> roboco_doc::MessagePart {
        roboco_doc::MessagePart::Tool {
            id: format!("p-{doc_id}"),
            call: roboco_proto::ToolCall::Unknown {
                name: "Agent: Audit grapheme handling".into(),
                input: Some(serde_json::json!({ "model": "haiku" })),
            },
            is_error,
            resolved,
            output: None,
            diff: None,
            output_ref: None,
            output_bytes: None,
            diff_ref: None,
            diff_stats: None,
            subagent_ref: Some(doc_id.into()),
            subagent_status: status,
            subagent_tail: Some("  Reading RowView.swift…  ".into()),
        }
    }

    /// `subagents_of`: one row per linked spawn chip, in spawn order,
    /// with the chip's lifecycle (never `resolved` alone), the model the
    /// spawn named, the trimmed tail, and the description as the title.
    #[test]
    fn subagents_read_the_spawn_chips() {
        use roboco_doc::parts::SubagentStatus as S;
        let entries = vec![entry(vec![
            spawn("c--sub--a", Some(S::Running), true, false),
            spawn("c--sub--b", None, false, false),
            // Not a spawn: an ordinary tool with a stray ref must not
            // surface (the genus gate).
            roboco_doc::MessagePart::Tool {
                id: "p-x".into(),
                call: roboco_proto::ToolCall::Exec { command: "ls".into() },
                is_error: false,
                resolved: true,
                output: None,
                diff: None,
                output_ref: None,
                output_bytes: None,
                diff_ref: None,
                diff_stats: None,
                subagent_ref: Some("c--sub--stray".into()),
                subagent_status: None,
                subagent_tail: None,
            },
        ])];
        let rows = subagents_of(&entries);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].doc_id, "c--sub--a");
        assert_eq!(rows[0].title, "Audit grapheme handling");
        assert_eq!(rows[0].state, SubagentState::Running);
        assert_eq!(rows[0].model.as_deref(), Some("haiku"));
        assert_eq!(rows[0].tail.as_deref(), Some("Reading RowView.swift…"));
        assert_eq!(rows[0].spawned_at_ms, 1_000);
        // No status, unresolved: the chip is still live (running).
        assert_eq!(rows[1].state, SubagentState::Running);
    }

    /// The lifecycle table (upstream #899's `subagent_status` rules):
    /// `resolved` alone is NOT done (eager-done); an error fails; a
    /// resolved-with-no-status chip is done.
    #[test]
    fn subagent_states_follow_the_chip_lifecycle() {
        use roboco_doc::parts::SubagentStatus as S;
        let of = |status: Option<S>, resolved: bool, is_error: bool| {
            subagents_of(&[entry(vec![spawn("c--sub--s", status, resolved, is_error)])])
                .pop()
                .unwrap()
                .state
        };
        assert_eq!(of(Some(S::Running), true, false), SubagentState::Running);
        assert_eq!(of(Some(S::Done), true, false), SubagentState::Done);
        assert_eq!(of(Some(S::Failed), false, false), SubagentState::Failed);
        assert_eq!(of(None, false, false), SubagentState::Running);
        assert_eq!(of(None, true, false), SubagentState::Done);
        assert_eq!(of(None, false, true), SubagentState::Failed);
        assert_eq!(
            of(None, true, true),
            SubagentState::Failed,
            "an error outranks a resolution"
        );
        assert_eq!(
            of(Some(S::Done), true, true),
            SubagentState::Failed,
            "an error outranks a done stamp"
        );
    }

    /// A re-spawn (the same doc id, a steered subagent) updates its row
    /// in place instead of listing twice.
    #[test]
    fn a_respawned_subagent_updates_its_row() {
        use roboco_doc::parts::SubagentStatus as S;
        let first = entry(vec![spawn("c--sub--s", Some(S::Done), true, false)]);
        let mut second = (*first).clone();
        second.created_at = 2_000;
        let rows = subagents_of(&[first, Arc::new(second)]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].doc_id, "c--sub--s");
        assert_eq!(rows[0].spawned_at_ms, 2_000, "the newest spawn wins");
    }

    /// The pill counts only running subagents.
    #[test]
    fn running_subagent_count_counts_only_the_running() {
        use roboco_doc::parts::SubagentStatus as S;
        let entries = vec![entry(vec![
            spawn("c--sub--a", Some(S::Running), true, false),
            spawn("c--sub--b", Some(S::Done), true, false),
            spawn("c--sub--c", Some(S::Failed), false, false),
            spawn("c--sub--d", None, false, false),
        ])];
        assert_eq!(running_subagent_count(&entries), 2);
        assert_eq!(running_subagent_count(&[]), 0);
    }

    #[test]
    fn the_warm_cap_and_preload_cap_are_the_tickets_values() {
        assert_eq!(WARM_SESSION_CAP, 6);
        assert_eq!(PRELOAD_CAP, 4);
    }

    #[test]
    fn wire_harness_names_are_kebab_case() {
        assert_eq!(wire_harness(&roboco_proto::HarnessId::ClaudeCode), "claude-code");
        assert_eq!(wire_harness(&roboco_proto::HarnessId::Mock), "mock");
    }

    #[test]
    fn profile_key_is_local_for_local_scope_and_scoped_otherwise() {
        use roboco_proto::WorkspaceScope;
        let info = |scope: WorkspaceScope| roboco_proto::EngineInfo {
            device_id: "d1".into(),
            workspace_scope: scope,
            cursor_sdk_version: None,
            capabilities: Vec::new(),
            version: None,
            protocol_version: None,
            min_client_protocol_version: None,
        };
        assert_eq!(profile_key(&info(WorkspaceScope::Local), Some("d1")), "local");
        assert_eq!(
            profile_key(&info(WorkspaceScope::Synced), Some("d1")),
            "synced:d1"
        );
        assert_eq!(
            profile_key(&info(WorkspaceScope::Development), None),
            "development"
        );
    }
}
