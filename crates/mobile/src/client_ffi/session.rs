//! The per-chat FFI object: composer-facing state and every command.
//! Transcript rows never cross here — the layout engine reads
//! [`mc::SessionSnapshot`] in Rust through `TranscriptView::attach`.
//!
//! Network methods are **async** (upstream's were sync outbox writes): a
//! Roboco write is an engine round trip, and an offline engine refuses the
//! write instead of saving it for later (`.scratch/remote-access/spec.md`
//! story 24).

use std::sync::Arc;

use roboco_mobile_client as mc;

use super::CoreClient;
use super::types::{
    ChatConfig, ChatIndicator, CoreResult, FileMatch, HarnessInfo, ModelInfo, ParsedUserMessage,
    SendState,
};

// ── records (`mob:client_ffi/session.rs`, kept in shape) ───────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BusyPolicy {
    /// Park it on the shared queue until the turn ends (default).
    Queue,
    /// Deliver mid-turn (steer), when the harness steers mid-turn.
    Steer,
    /// Roboco's "Stop and send": stop the turn, run this message next.
    Interrupt,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OutgoingAttachment {
    pub name: String,
    pub mime_type: String,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WorktreeSpec {
    /// The repo to branch (the project's folder on the host).
    pub repo_path: String,
    /// Base ref for the fresh `roboco/<name>` branch.
    pub base: String,
    pub space_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SendRequest {
    pub text: String,
    pub attachments: Vec<OutgoingAttachment>,
    /// Isolated host worktree for this run (new-session "New worktree").
    pub worktree: Option<WorktreeSpec>,
    pub busy: BusyPolicy,
}

impl From<SendRequest> for mc::SendRequest {
    fn from(request: SendRequest) -> Self {
        Self {
            text: request.text,
            attachments: request
                .attachments
                .into_iter()
                .map(|attachment| mc::OutgoingAttachment {
                    name: attachment.name,
                    mime_type: attachment.mime_type,
                    data: attachment.data,
                })
                .collect(),
            worktree: request.worktree.map(|spec| roboco_proto::WorktreeSpec {
                repo_path: spec.repo_path,
                base: spec.base,
                space_id: spec.space_id,
            }),
            busy: match request.busy {
                BusyPolicy::Queue => mc::BusyPolicy::Queue,
                BusyPolicy::Steer => mc::BusyPolicy::Steer,
                BusyPolicy::Interrupt => mc::BusyPolicy::Interrupt,
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SendOutcome {
    /// A new turn was requested; the echo's id is `message_id`.
    Started { message_id: String },
    /// Delivered into the live turn.
    Steered { message_id: String },
    /// Parked on the shared queue.
    Queued { queue_id: String },
}

impl From<mc::SendOutcome> for SendOutcome {
    fn from(outcome: mc::SendOutcome) -> Self {
        match outcome {
            mc::SendOutcome::Started { message_id } => SendOutcome::Started { message_id },
            mc::SendOutcome::Steered { message_id } => SendOutcome::Steered { message_id },
            mc::SendOutcome::Queued { queue_id } => SendOutcome::Queued { queue_id },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostCapabilities {
    pub message_queue: bool,
    pub queue_actions: bool,
    pub queue_attachments: bool,
    pub clean_attachment_text: bool,
    pub queue_edit_lease: bool,
    /// Same wire capability as `queue_attachments`.
    pub queued_attachments: bool,
    /// The chat's harness steers mid-turn (unknown until a live catalog).
    pub mid_turn_steering: Option<bool>,
}

impl From<mc::HostCapabilities> for HostCapabilities {
    fn from(caps: mc::HostCapabilities) -> Self {
        Self {
            message_queue: caps.message_queue,
            queue_actions: caps.queue_actions,
            queue_attachments: caps.queue_attachments,
            clean_attachment_text: caps.clean_attachment_text,
            queue_edit_lease: caps.queue_edit_lease,
            queued_attachments: caps.queued_attachments,
            mid_turn_steering: caps.mid_turn_steering,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostInfo {
    pub device_id: String,
    pub name: Option<String>,
    pub online: bool,
    pub capabilities: HostCapabilities,
}

impl From<mc::HostInfo> for HostInfo {
    fn from(info: mc::HostInfo) -> Self {
        Self {
            device_id: info.device_id,
            name: info.name,
            online: info.online,
            capabilities: info.capabilities.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LiveStatus {
    /// The row's display status (no in-flight-send override — #777).
    pub indicator: ChatIndicator,
    /// A turn is actually running on the host (sends queue/steer).
    pub turn_running: bool,
    pub working_since_ms: Option<i64>,
    /// The newest transcript entry is streaming.
    pub streaming: bool,
    /// Stop is meaningful.
    pub can_interrupt: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum QueueGate {
    /// Someone holds an edit lease; the row won't deliver until released.
    Editing {
        owner_device_id: String,
        expires_at_ms: i64,
        mine: bool,
    },
    /// A lease lapsed — a person must review before it sends.
    ReviewRequired { owner_device_id: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QueueItem {
    pub id: String,
    /// Raw row text (what the host will send).
    pub text: String,
    /// User-visible text (trailer/Appshot context stripped).
    pub visible_text: String,
    pub attachments: Vec<String>,
    pub hold_for_turn_end: bool,
    pub issued_by: String,
    pub from_this_device: bool,
    pub issued_at_ms: i64,
    pub edited_at_ms: Option<i64>,
    pub gate: Option<QueueGate>,
    /// A Send-now / Remove is awaiting the host's acknowledgement.
    pub action_pending: bool,
}

impl From<mc::QueueItem> for QueueItem {
    fn from(item: mc::QueueItem) -> Self {
        Self {
            id: item.id,
            text: item.text,
            visible_text: item.visible_text,
            attachments: item.attachments,
            hold_for_turn_end: item.hold_for_turn_end,
            issued_by: item.issued_by,
            from_this_device: item.from_this_device,
            issued_at_ms: item.issued_at_ms,
            edited_at_ms: item.edited_at_ms,
            gate: item.gate.map(|gate| match gate {
                mc::QueueGate::Editing {
                    owner_device_id,
                    expires_at_ms,
                    mine,
                } => QueueGate::Editing {
                    owner_device_id,
                    expires_at_ms,
                    mine,
                },
                mc::QueueGate::ReviewRequired { owner_device_id } => {
                    QueueGate::ReviewRequired { owner_device_id }
                }
            }),
            action_pending: item.action_pending,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PendingKind {
    Run,
    Steer,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PendingSend {
    /// Client-minted id (the host's user entry reuses it).
    pub message_id: String,
    /// Raw content (attachment trailer included).
    pub text: String,
    pub visible_text: String,
    pub images: Vec<String>,
    pub kind: PendingKind,
    pub sent_at_ms: i64,
    pub state: SendState,
}

impl From<mc::PendingSend> for PendingSend {
    fn from(pending: mc::PendingSend) -> Self {
        Self {
            message_id: pending.message_id,
            text: pending.text,
            visible_text: pending.visible_text,
            images: pending.images,
            kind: match pending.kind {
                mc::PendingKind::Run => PendingKind::Run,
                mc::PendingKind::Steer => PendingKind::Steer,
            },
            sent_at_ms: pending.sent_at_ms,
            state: pending.state.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct UserInputQuestion {
    pub id: String,
    pub header: String,
    pub question: String,
    pub options: Vec<String>,
    pub multi_select: bool,
    /// Roboco's wire has no `prefill` (always `None`).
    pub prefill: Option<String>,
    /// Roboco's wire has no `multiline` (always `false`).
    pub multiline: bool,
}

impl From<roboco_proto::UserInputQuestion> for UserInputQuestion {
    fn from(question: roboco_proto::UserInputQuestion) -> Self {
        Self {
            id: question.id,
            header: question.header,
            question: question.question,
            options: question.options,
            multi_select: question.multi_select,
            prefill: None,
            multiline: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UserInputAnswer {
    pub question_id: String,
    /// Picked option labels (or free text as a single label).
    pub labels: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InputRequest {
    pub entry_id: String,
    pub request_id: String,
    pub questions: Vec<UserInputQuestion>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RoomState {
    /// Engine `Connected` **and** the transcript subscription is live.
    pub connected: bool,
    pub retry_at_ms: Option<i64>,
    /// Engine not connected (a send would not deliver promptly).
    pub degraded: bool,
}

impl From<mc::RoomState> for RoomState {
    fn from(room: mc::RoomState) -> Self {
        Self {
            connected: room.connected,
            retry_at_ms: room.retry_at_ms,
            degraded: room.degraded,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ContextUsage {
    pub tokens: Option<u64>,
    pub window: Option<u64>,
}

/// Everything the composer and its status strip render.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ComposerState {
    pub chat_id: String,
    pub revision: u64,
    pub title: String,
    pub host: HostInfo,
    pub live: LiveStatus,
    /// The shared queue, delivery order.
    pub queue: Vec<QueueItem>,
    /// This device's unadopted sends, oldest first.
    pub pending_sends: Vec<PendingSend>,
    /// Headline state of the oldest pending send.
    pub send_state: Option<SendState>,
    /// A send now would queue rather than deliver promptly.
    pub delivery_degraded: bool,
    pub room: RoomState,
    /// Attachment upload progress (0..1).
    pub transfer_progress: Option<f64>,
    /// Answer this instead of typing.
    pub open_input: Option<InputRequest>,
    /// Last queue action failure.
    pub queue_error: Option<String>,
    /// Scroll-to-own-send target.
    pub last_submitted_message_id: Option<String>,
    pub context_usage: Option<ContextUsage>,
}

impl From<mc::ComposerState> for ComposerState {
    fn from(composer: mc::ComposerState) -> Self {
        Self {
            chat_id: composer.chat_id,
            revision: composer.revision,
            title: composer.title,
            host: composer.host.into(),
            live: LiveStatus {
                indicator: composer.live.indicator.into(),
                turn_running: composer.live.turn_running,
                working_since_ms: composer.live.working_since_ms,
                streaming: composer.live.streaming,
                can_interrupt: composer.live.can_interrupt,
            },
            queue: composer.queue.into_iter().map(Into::into).collect(),
            pending_sends: composer.pending_sends.into_iter().map(Into::into).collect(),
            send_state: composer.send_state.map(Into::into),
            delivery_degraded: composer.delivery_degraded,
            room: composer.room.into(),
            transfer_progress: composer.transfer_progress,
            open_input: composer.open_input.map(|input| InputRequest {
                entry_id: input.entry_id,
                request_id: input.request_id,
                questions: input
                    .questions
                    .into_iter()
                    .map(Into::into)
                    .collect(),
            }),
            queue_error: composer.queue_error,
            last_submitted_message_id: composer.last_submitted_message_id,
            context_usage: composer.context_usage.map(|usage| ContextUsage {
                tokens: usage.tokens,
                window: usage.window,
            }),
        }
    }
}

/// Transcript-level facts the platform needs without the rows (loader vs
/// empty state, scroll-to-bottom affordances, the working pill).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TranscriptStatus {
    pub revision: u64,
    /// Content present (false = show a loader, not an empty chat).
    pub hydrated: bool,
    /// Host-written entries.
    pub entry_count: u32,
    pub pending_count: u32,
    pub streaming: bool,
    pub working: bool,
    pub working_since_ms: Option<i64>,
    /// Id of the newest entry (host or echo).
    pub last_entry_id: Option<String>,
    /// The shown content is an opening-tail preview, not the full history.
    pub history_pending: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct QueueEditLease {
    pub row_id: String,
    pub lease_id: String,
    /// Row text at lease time.
    pub text: String,
    pub base_text_hash: String,
    pub expires_at_ms: i64,
}

impl From<mc::QueueEditLease> for QueueEditLease {
    fn from(lease: mc::QueueEditLease) -> Self {
        Self {
            row_id: lease.row_id,
            lease_id: lease.lease_id,
            text: lease.text,
            base_text_hash: lease.base_text_hash,
            expires_at_ms: lease.expires_at_ms,
        }
    }
}

impl From<QueueEditLease> for mc::QueueEditLease {
    fn from(lease: QueueEditLease) -> Self {
        Self {
            row_id: lease.row_id,
            lease_id: lease.lease_id,
            text: lease.text,
            base_text_hash: lease.base_text_hash,
            expires_at_ms: lease.expires_at_ms,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum QueueEditStart {
    Acquired { lease: QueueEditLease },
    /// Another editor holds the row.
    Locked,
    /// The row left the queue.
    Missing,
    /// Host unreachable / capability missing.
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum QueueEditAction {
    /// Save the new text.
    Commit,
    /// Keep the old text, release the row.
    Cancel,
    /// Delete the row.
    Discard,
    /// Release without changing anything.
    Release,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum QueueEditFinish {
    Finished,
    /// The row changed under the lease.
    Conflict,
    Missing,
    /// The lease was superseded or expired.
    Lost,
    Unavailable,
}

// ── upload progress ────────────────────────────────────────────────────────

/// Upload progress (the committed-bytes fraction, monotonic, ending at 1.0).
#[uniffi::export(with_foreign)]
pub trait UploadProgress: Send + Sync {
    fn on_progress(&self, fraction: f64);
}

// ── the session object ─────────────────────────────────────────────────────

/// One open chat. Obtain via `CoreClient.open_session`.
#[derive(uniffi::Object)]
pub struct SessionHandle {
    pub(crate) inner: mc::SessionHandle,
}

impl SessionHandle {
    pub(crate) fn new(inner: mc::SessionHandle) -> Arc<Self> {
        Arc::new(Self { inner })
    }

    /// The Rust handle (transcript snapshots + subscription for the layout
    /// engine).
    pub fn handle(&self) -> &mc::SessionHandle {
        &self.inner
    }
}

/// Run an async client call on the client runtime (executor-agnostic).
async fn on_runtime<T, F>(fut: F) -> CoreResult<T>
where
    F: std::future::Future<Output = Result<T, mc::ClientError>> + Send + 'static,
    T: Send + 'static,
{
    mc::runtime::run(fut).await.map_err(Into::into)
}

#[uniffi::export]
impl SessionHandle {
    pub fn chat_id(&self) -> String {
        self.inner.chat_id().to_owned()
    }

    /// The engine owning this chat.
    pub fn engine_id(&self) -> String {
        self.inner.engine_id().to_owned()
    }

    /// Composer + status strip state (pull after `ComposerChanged`).
    pub fn composer(&self) -> ComposerState {
        self.inner.composer().as_ref().clone().into()
    }

    pub fn transcript_status(&self) -> TranscriptStatus {
        let (
            revision,
            hydrated,
            entry_count,
            pending_count,
            streaming,
            working,
            working_since_ms,
            last_entry_id,
            history_pending,
        ) = self.inner.transcript_status_fields();
        TranscriptStatus {
            revision,
            hydrated,
            entry_count: entry_count as u32,
            pending_count: pending_count as u32,
            streaming,
            working,
            working_since_ms,
            last_entry_id,
            history_pending,
        }
    }

    /// Plain text of one entry's text parts (copy / share).
    pub fn message_text(&self, entry_id: String) -> Option<String> {
        self.inner.message_text(&entry_id)
    }

    /// A transcript view is on/off screen (marks seen on attach).
    pub fn set_view_attached(&self, attached: bool) {
        self.inner.set_view_attached(attached);
    }

    pub fn mark_seen(&self) {
        self.inner.mark_seen();
    }

    /// Send: a new turn when idle; while working, queue (or steer, or
    /// stop-and-send, per `busy`).
    pub async fn send(&self, request: SendRequest) -> CoreResult<SendOutcome> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.send(request.into()).await.map(Into::into) }).await
    }

    pub async fn interrupt(&self) -> CoreResult<()> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.interrupt().await }).await
    }

    pub async fn respond_input(
        &self,
        request_id: String,
        answers: Vec<UserInputAnswer>,
    ) -> CoreResult<()> {
        let inner = self.inner.clone();
        let answers = answers
            .into_iter()
            .map(|answer| roboco_proto::UserInputAnswer {
                question_id: answer.question_id,
                labels: answer.labels,
            })
            .collect();
        on_runtime(async move { inner.respond_input(&request_id, answers).await }).await
    }

    /// Park a message on the shared queue directly. Returns the row id.
    pub async fn enqueue(
        &self,
        text: String,
        attachments: Vec<String>,
        hold_for_turn_end: bool,
    ) -> CoreResult<String> {
        let inner = self.inner.clone();
        on_runtime(async move {
            inner.enqueue(&text, attachments, hold_for_turn_end).await
        })
        .await
    }

    /// Move a queue row to index `to` (clamped). False when it can't move.
    pub async fn move_queued(&self, id: String, to: u32) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.move_queued(&id, to).await }).await
    }

    /// Nudge a row one slot up (`-1`) or down (`+1`).
    pub async fn move_queued_by(&self, id: String, delta: i32) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.move_queued_by(&id, delta).await }).await
    }

    pub async fn begin_queued_edit(&self, id: String, instance_id: String) -> QueueEditStart {
        let inner = self.inner.clone();
        let result = mc::runtime::run(async move {
            Ok(inner.begin_queued_edit(&id, &instance_id).await)
        })
        .await;
        match result {
            Ok(mc::QueueEditStart::Acquired(lease)) => QueueEditStart::Acquired {
                lease: lease.into(),
            },
            Ok(mc::QueueEditStart::Locked) => QueueEditStart::Locked,
            Ok(mc::QueueEditStart::Missing) => QueueEditStart::Missing,
            Ok(mc::QueueEditStart::Unavailable) | Err(_) => QueueEditStart::Unavailable,
        }
    }

    pub async fn renew_queued_edit(&self, lease: QueueEditLease) -> bool {
        let inner = self.inner.clone();
        let lease: mc::QueueEditLease = lease.into();
        mc::runtime::run(async move { Ok(inner.renew_queued_edit(&lease).await) })
            .await
            .unwrap_or(false)
    }

    /// Finish an edit. Commit sends `text`; `attachments: Some(v)` is sent
    /// even when `v` is empty (that clears the row's attachments).
    pub async fn finish_queued_edit(
        &self,
        lease: QueueEditLease,
        action: QueueEditAction,
        text: Option<String>,
        attachments: Option<Vec<String>>,
    ) -> QueueEditFinish {
        let inner = self.inner.clone();
        let lease: mc::QueueEditLease = lease.into();
        let action = match action {
            QueueEditAction::Commit => mc::QueueEditAction::Commit,
            QueueEditAction::Cancel => mc::QueueEditAction::Cancel,
            QueueEditAction::Discard => mc::QueueEditAction::Discard,
            QueueEditAction::Release => mc::QueueEditAction::Release,
        };
        let result = mc::runtime::run(async move {
            Ok(inner.finish_queued_edit(lease, action, text, attachments).await)
        })
        .await;
        match result {
            Ok(mc::QueueEditFinish::Finished) => QueueEditFinish::Finished,
            Ok(mc::QueueEditFinish::Conflict) => QueueEditFinish::Conflict,
            Ok(mc::QueueEditFinish::Missing) => QueueEditFinish::Missing,
            Ok(mc::QueueEditFinish::Lost) => QueueEditFinish::Lost,
            Ok(mc::QueueEditFinish::Unavailable) | Err(_) => QueueEditFinish::Unavailable,
        }
    }

    /// Deliver a queued row now (interrupting the live turn if needed).
    pub async fn send_queued_now(&self, id: String) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.send_queued_now(&id).await }).await
    }

    /// Steer a queued row into the live turn (never interrupts).
    pub async fn steer_queued_now(&self, id: String) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.steer_queued_now(&id).await }).await
    }

    /// A row's primary action: text steers (never interrupts); attachment
    /// rows send now (PR #592).
    pub async fn deliver_queued_now(&self, id: String) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.deliver_queued_now(&id).await }).await
    }

    /// Remove a queued row (applied locally after the host acks).
    pub async fn remove_queued(&self, id: String) -> CoreResult<bool> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.remove_queued(&id).await }).await
    }

    pub fn clear_queue_error(&self) {
        self.inner.clear_queue_error();
    }

    /// "Not delivered — tap to retry".
    pub async fn retry_delivery(&self) -> CoreResult<()> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.retry_delivery().await }).await
    }

    /// A tool part's full output by its doc-resident blob ref (ticket 17's
    /// tool-detail sheet).
    pub async fn fetch_tool_blob(&self, blob_ref: String) -> CoreResult<String> {
        let inner = self.inner.clone();
        on_runtime(async move { inner.fetch_tool_blob(&blob_ref).await }).await
    }
}

// ── the CoreClient additions ───────────────────────────────────────────────

#[uniffi::export]
impl CoreClient {
    /// Open (or return the open) session — instant from the cache; the
    /// subscription starts in the background.
    pub fn open_session(&self, chat_id: String) -> CoreResult<Arc<SessionHandle>> {
        Ok(SessionHandle::new(
            self.client.open_session(&chat_id)?,
        ))
    }

    /// An already-open session.
    pub fn session(&self, chat_id: String) -> Option<Arc<SessionHandle>> {
        self.client.session(&chat_id).map(SessionHandle::new)
    }

    /// The view closed; the session stays warm until evicted.
    pub fn close_session(&self, chat_id: String) {
        self.client.close_session(&chat_id);
    }

    /// Warm the most relevant sessions (front-page order, capped).
    pub fn preload_sessions(&self) {
        self.client.preload_sessions();
    }

    /// The chat's config (the optimistic override when one exists).
    pub fn session_config(&self, chat_id: String) -> Option<ChatConfig> {
        self.client
            .session_config(&chat_id)
            .map(|config| config.into())
    }

    /// Store a chat config (PR #664 immediate refresh): applies
    /// optimistically, sends `Mutate setChatConfig`, reverts on error.
    pub async fn set_session_config(&self, chat_id: String, config: ChatConfig) -> CoreResult<()> {
        let config: roboco_proto::ChatConfig = config.try_into()?;
        let client = self.client.clone();
        let chat_id = chat_id.clone();
        on_runtime(async move { client.set_session_config(&chat_id, config).await }).await
    }

    /// Harness catalog of an engine (live → disk cache → static fallback);
    /// never errors. `device_id` is 07's `DeviceView`/`SessionRow` field.
    pub async fn list_harnesses(&self, device_id: String) -> Vec<HarnessInfo> {
        let client = self.client.clone();
        mc::runtime::run(async move { Ok(client.list_harnesses(&device_id).await) })
            .await
            .unwrap_or_else(|_| mc::catalog_live::fallback_harnesses())
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Model catalog for `harness` on an engine (normalized; static
    /// fallback); never errors.
    pub async fn list_models(&self, device_id: String, harness: String) -> Vec<ModelInfo> {
        let client = self.client.clone();
        let fallback = harness.clone();
        mc::runtime::run(async move { Ok(client.list_models(&device_id, &harness).await) })
            .await
            .unwrap_or_else(|_| mc::catalog_live::fallback_models(&fallback))
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Files matching `query` in the chat's (or project's) workspace, for
    /// composer `@` mentions.
    pub async fn search_files(
        &self,
        device_id: String,
        chat_id: Option<String>,
        space_id: Option<String>,
        query: String,
    ) -> CoreResult<Vec<FileMatch>> {
        let client = self.client.clone();
        let files = on_runtime(async move {
            client
                .search_files(&device_id, chat_id, space_id, &query)
                .await
        })
        .await?;
        Ok(files.into_iter().map(Into::into).collect())
    }

    /// Chunked upload of one file; returns its durable path on the engine.
    pub async fn upload_attachment(
        &self,
        device_id: String,
        name: String,
        data: Vec<u8>,
        progress: Option<Arc<dyn UploadProgress>>,
    ) -> CoreResult<String> {
        let client = self.client.clone();
        let progress: Option<mc::attachments::ProgressFn> = progress.map(|progress| {
            Arc::new(move |fraction: f64| progress.on_progress(fraction))
                as mc::attachments::ProgressFn
        });
        on_runtime(async move {
            client
                .upload_attachment(&device_id, &name, data, progress)
                .await
        })
        .await
    }

    /// Attachment bytes (LRU-cached).
    pub async fn read_attachment(&self, device_id: String, path: String) -> CoreResult<Vec<u8>> {
        let client = self.client.clone();
        on_runtime(async move { client.read_attachment(&device_id, &path).await }).await
    }
}

// ── free functions ─────────────────────────────────────────────────────────

/// Split a user prompt into visible text + attachment refs.
#[uniffi::export]
pub fn parse_user_message(content: String) -> ParsedUserMessage {
    mc::attachments::parse_user_message(&content).into()
}

/// Largest attachment the composer may pick (bytes).
#[uniffi::export]
pub fn max_attachment_bytes() -> u64 {
    mc::attachments::MAX_ATTACHMENT_BYTES as u64
}

/// The canonical mention link the engine understands
/// (`[name](roboco-file:path)`).
#[uniffi::export]
pub fn file_mention_link(path: String, is_dir: bool) -> String {
    roboco_proto::file_mentions::local_file_link(&path, is_dir)
}

/// Append the attachment trailer to a prompt (no-op without paths).
#[uniffi::export]
pub fn with_attachments(text: String, paths: Vec<String>) -> String {
    mc::attachments::with_attachments(&text, &paths)
}

/// The row's raw text with its visible part replaced, keeping the hidden
/// context after it. Blank edits stay blank (the host removes the row).
/// Port of `ios:Session/CoreSessionSource.swift:189-195`.
#[uniffi::export]
pub fn replacing_visible(raw: String, visible: String, edited: String) -> String {
    if edited.trim().is_empty() || raw == visible {
        return edited;
    }
    let body = raw.trim_start();
    if body.starts_with(&visible) {
        return format!("{edited}{}", &body[visible.len()..]);
    }
    // Attachment-only rows show a placeholder: all of the raw text is
    // context.
    if body.is_empty() {
        return edited;
    }
    format!("{edited}\n\n{body}")
}

/// The #664 model-change rule: keep the model's effort default when the
/// current effort isn't offered, and drop options the model doesn't have.
#[uniffi::export]
pub fn config_for_model(config: ChatConfig, model: ModelInfo) -> ChatConfig {
    let mut next = config.clone();
    next.model = Some(model.id.clone());
    let offered = model.reasoning_levels.iter().any(|level| {
        config.reasoning.as_deref().is_some_and(|current| current == level)
    });
    if !offered {
        next.reasoning = model.default_reasoning.clone();
    }
    next.model_options = config
        .model_options
        .iter()
        .filter(|(option_id, choice_id)| {
            model
                .options
                .iter()
                .any(|option| {
                    option.id == option_id.as_str()
                        && option
                            .choices
                            .iter()
                            .any(|choice| choice.id == choice_id.as_str())
                })
        })
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    next
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The #664 rule: reasoning reset, option filtering, an unchanged valid
    /// effort.
    #[test]
    fn config_for_model_resets_reasoning_and_filters_options() {
        let model = ModelInfo {
            id: "claude-fable-5-1".into(),
            label: "Fable 5.1".into(),
            description: None,
            reasoning_levels: vec!["low".into(), "high".into()],
            options: vec![super::super::types::ModelOption {
                id: "contextWindow".into(),
                label: "Context Window".into(),
                choices: vec![
                    super::super::types::ModelOptionChoice {
                        id: "200k".into(),
                        label: "200K".into(),
                    },
                ],
                default_choice: "200k".into(),
            }],
            default_reasoning: Some("high".into()),
        };
        let config = ChatConfig {
            harness: "claude-code".into(),
            model: Some("claude-sonnet-5".into()),
            reasoning: Some("ultrathink".into()),
            model_options: [
                ("contextWindow".to_owned(), "1m".to_owned()),
                ("fastMode".to_owned(), "on".to_owned()),
            ]
            .into_iter()
            .collect(),
            sandbox: super::super::types::SandboxLevel::WorkspaceWrite,
        };
        let next = config_for_model(config, model);
        assert_eq!(next.model.as_deref(), Some("claude-fable-5-1"));
        assert_eq!(
            next.reasoning.as_deref(),
            Some("high"),
            "ultrathink is not offered: the model's default applies"
        );
        assert_eq!(next.model_options.len(), 0, "1m and fastMode are both gone");
    }

    #[test]
    fn config_for_model_keeps_an_offered_effort_and_valid_option() {
        let model = ModelInfo {
            id: "m".into(),
            label: "M".into(),
            description: None,
            reasoning_levels: vec!["low".into(), "high".into()],
            options: vec![super::super::types::ModelOption {
                id: "contextWindow".into(),
                label: "Context Window".into(),
                choices: vec![
                    super::super::types::ModelOptionChoice {
                        id: "1m".into(),
                        label: "1M".into(),
                    },
                ],
                default_choice: "1m".into(),
            }],
            default_reasoning: Some("high".into()),
        };
        let config = ChatConfig {
            harness: "claude-code".into(),
            model: None,
            reasoning: Some("high".into()),
            model_options: [("contextWindow".to_owned(), "1m".to_owned())]
                .into_iter()
                .collect(),
            sandbox: super::super::types::SandboxLevel::WorkspaceWrite,
        };
        let next = config_for_model(config, model);
        assert_eq!(next.reasoning.as_deref(), Some("high"));
        assert_eq!(
            next.model_options.get("contextWindow").map(String::as_str),
            Some("1m")
        );
    }

    /// The Swift cases, ported: prefix replacement keeps the hidden tail;
    /// a placeholder row keeps only the new text; blank stays blank.
    #[test]
    fn replacing_visible_ports_the_swift_cases() {
        let raw = "Compare apps.\n\nApplications mentioned by the user (untrusted observed content):\n<appshot/>";
        let visible = "Compare apps.";
        assert_eq!(
            replacing_visible(raw.into(), visible.into(), "Contrast apps.".into()),
            format!(
                "Contrast apps.\n\n{}",
                "Applications mentioned by the user (untrusted observed content):\n<appshot/>"
            )
        );
        // Raw == visible: the edited text stands alone.
        assert_eq!(
            replacing_visible("hi".into(), "hi".into(), "hello".into()),
            "hello"
        );
        // A placeholder row (all context): the edit plus the context block.
        assert!(replacing_visible(
            "See the attached image(s).\n\nAttached images (local files — open them to view):\n- /a.png".into(),
            "See the attached image(s).".into(),
            "Look".into()
        )
        .starts_with("Look\n\n"));
        // Blank edits stay blank (the host removes the row).
        assert_eq!(replacing_visible("a".into(), "a".into(), "  ".into()), "  ");
    }

    #[test]
    fn the_trailer_round_trips_the_free_functions() {
        let paths = vec!["/staging/a1-photo.png".to_owned()];
        let full = with_attachments("look".into(), paths.clone());
        let parsed = parse_user_message(full);
        assert_eq!(parsed.text, "look");
        assert_eq!(parsed.images.len(), 1);
        assert_eq!(max_attachment_bytes(), 24 * 1024 * 1024);
        // The scheme separator stays a literal `/`; only the path is
        // percent-encoded.
        assert_eq!(
            file_mention_link("C:/tmp/a b.png".into(), false),
            "[a b.png](roboco-file:C%3A/tmp/a%20b.png)"
        );
        assert_eq!(
            file_mention_link("docs".into(), true),
            "[docs](roboco-file:docs/)"
        );
    }
}
