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
    /// override from `set_session_config` when one exists.
    pub(crate) fn chat_config(&self) -> Option<roboco_proto::ChatConfig> {
        if let Some(override_config) = self.config_override() {
            return Some(override_config);
        }
        self.chat_row().and_then(|chat| chat.config)
    }

    pub(crate) fn config_override(&self) -> Option<roboco_proto::ChatConfig> {
        self.client
            .upgrade()
            .and_then(|inner| inner.sessions.override_of(&self.chat_id))
    }

    /// `cwd: chat.cwd ?? <space path> ?? "~"` for the RunRequest.
    pub(crate) fn chat_cwd(&self) -> Option<String> {
        let chat = self.chat_row()?;
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
        entry
            .rows
            .spaces
            .iter()
            .find(|space| space.id == space_id)
            .map(|space| space.path.clone())
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
            // 07's workspace store recomputes on a send-state change.
            if let Some(inner) = self.client.upgrade() {
                let revision = inner.sessions.bump_workspace_revision();
                inner.pump.workspace(revision);
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
        let snapshot = {
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
            SessionSnapshot {
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
            }
        };
        self.snapshot_tx.send_replace(Arc::new(snapshot));
        if let Some(inner) = self.client.upgrade() {
            inner.pump.session(&self.chat_id, revision);
        }
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
        if let Some(inner) = self.client.upgrade() {
            mark_seen(&inner, &self.chat_id, &self.engine_id);
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

/// Fire-and-forget `Mutate markChatSeen`. Never fails, never rolls back.
pub(crate) fn mark_seen(inner: &Arc<ClientInner>, scoped_chat_id: &str, engine_id: &str) {
    let Ok(handle) = Client::from_inner(inner.clone()).engine_handle(engine_id) else {
        return;
    };
    let chat_id = scoped_chat_id.to_owned();
    crate::runtime::shared().spawn(async move {
        let _ = handle
            .call(
                roboco_rpc::methods::MUTATE,
                serde_json::json!({ "op": "markChatSeen", "chatId": chat_id }),
            )
            .await;
    });
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
            state
                .pending
                .iter()
                .filter(|p| p.acked_at_ms != 0)
                .map(|p| p.acked_at_ms + grace.as_millis() as i64)
                .min()
                .map(|ms| {
                    let remaining = (ms - crate::engines::now_ms()).max(0);
                    tokio::time::Instant::now()
                        + Duration::from_millis(remaining as u64)
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
    pub(crate) overrides: HashMap<String, roboco_proto::ChatConfig>,
    /// Bumped for `WorkspaceChanged` events driven by session state.
    pub(crate) workspace_revision: u64,
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

    pub(crate) fn override_of(&self, chat_id: &str) -> Option<roboco_proto::ChatConfig> {
        lock(&self.inner).overrides.get(chat_id).cloned()
    }

    pub(crate) fn set_override(&self, chat_id: &str, config: roboco_proto::ChatConfig) -> bool {
        let mut inner = lock(&self.inner);
        let changed = inner.overrides.insert(chat_id.to_owned(), config) != inner.overrides.get(chat_id).cloned();
        inner.workspace_revision += 1;
        changed
    }

    pub(crate) fn clear_override(&self, chat_id: &str) -> bool {
        let mut inner = lock(&self.inner);
        let had = inner.overrides.remove(chat_id).is_some();
        if had {
            inner.workspace_revision += 1;
        }
        had
    }

    pub(crate) fn bump_workspace_revision(&self) -> u64 {
        let mut inner = lock(&self.inner);
        inner.workspace_revision += 1;
        inner.workspace_revision
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
        for (_, chat_id) in idle.into_iter().take(excess) {
            tracing::debug!(chat = %chat_id, "evicting warm session");
            if let Some(core) = registry.sessions.remove(&chat_id) {
                core.cancel();
            }
        }
    }

    /// `on_background`: close the subscriptions of detached sessions that
    /// are not streaming and have no pending sends.
    pub(crate) fn close_background(&self) {
        let mut registry = lock(&self.inner);
        let quiet: Vec<String> = registry
            .sessions
            .values()
            .filter(|core| {
                !core.view_attached()
                    && !core.has_pending_sends()
                    && !core.snapshot().streaming
            })
            .map(|core| core.chat_id.clone())
            .collect();
        for chat_id in quiet {
            if let Some(core) = registry.sessions.remove(&chat_id) {
                core.cancel();
            }
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
    pub fn session_config(&self, chat_id: &str) -> Option<roboco_proto::ChatConfig> {
        if let Some(config) = self.inner.sessions.override_of(chat_id) {
            return Some(config);
        }
        self.row_config(chat_id)
    }

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

    /// Store a chat config (PR #664 immediate refresh): validate, store the
    /// optimistic override, emit, send `Mutate setChatConfig`, clear the
    /// override when the engine's own `WatchChats` frame shows an equal
    /// config; on error revert and return it.
    pub async fn set_session_config(
        &self,
        chat_id: &str,
        config: roboco_proto::ChatConfig,
    ) -> Result<(), ClientError> {
        let (handle, _raw_id) = self.resolve(chat_id)?;
        self.inner.sessions.set_override(chat_id, config.clone());
        {
            // Composer + workspace refresh immediately.
            if let Some(handle) = self.session(chat_id) {
                handle.core.recompute_composer();
            }
            let revision = self.inner.sessions.bump_workspace_revision();
            self.inner.pump.workspace(revision);
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
        match result {
            Ok(_) => {
                // Watch for the engine echo; clear the override when the
                // row matches (bounded: 30 s, then the row check happens on
                // the next open anyway).
                let inner = self.inner.clone();
                let chat_id = chat_id.to_owned();
                let wanted = config;
                crate::runtime::shared().spawn(async move {
                    let mut fleet = inner.fleet.subscribe();
                    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
                    loop {
                        // The ENGINE's row, not the merged view (which would
                        // read the override itself and match at once).
                        let now_matches = Client::from_inner(inner.clone())
                            .row_config(&chat_id)
                            .is_some_and(|current| current == wanted);
                        if now_matches
                            && inner.sessions.clear_override(&chat_id)
                        {
                            if let Some(handle) = Client::from_inner(inner.clone()).session(&chat_id)
                            {
                                handle.core.recompute_composer();
                            }
                            let revision = inner.sessions.bump_workspace_revision();
                            inner.pump.workspace(revision);
                            return;
                        }
                        if now_matches {
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
                self.inner.sessions.clear_override(chat_id);
                if let Some(handle) = self.session(chat_id) {
                    handle.core.recompute_composer();
                }
                let revision = self.inner.sessions.bump_workspace_revision();
                self.inner.pump.workspace(revision);
                Err(error)
            }
        }
    }

    /// Fire-and-forget mark-seen (never fails, never rolls back).
    pub fn mark_seen(&self, chat_id: &str) {
        if let Ok((handle, _)) = self.resolve(chat_id) {
            mark_seen(&self.inner, chat_id, handle.engine_id());
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
