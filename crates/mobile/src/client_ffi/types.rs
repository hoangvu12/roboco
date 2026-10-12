//! FFI records and enums mirroring `roboco-mobile-client`'s surface, plus
//! the conversions both ways. Plain data: Kotlin gets value types.
//!
//! Wire-string ids stay strings so a newer engine's values never fail to
//! cross the boundary. Credentials cross this boundary exactly twice: in
//! (`CoreClient::new`) and out (`pair_engine`) — Kotlin owns the
//! Keystore-encrypted storage (ticket 18); Rust never persists them.

use std::collections::HashMap;

use roboco_mobile_client as mc;

// ── errors ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("not found: {message}")]
    NotFound { message: String },
    #[error("invalid argument: {message}")]
    InvalidArgument { message: String },
    /// The engine could not be reached.
    #[error("engine unavailable: {message}")]
    HostUnavailable { message: String },
    #[error("not supported by the engine: {message}")]
    Unsupported { message: String },
    /// The engine answered with an error (bad request, git failure, …).
    #[error("engine error: {message}")]
    HostError { message: String },
    #[error("network: {message}")]
    Network { message: String },
    /// The engine is parked (revoked / identity changed / incompatible).
    #[error("auth: {message}")]
    Auth { message: String },
    #[error("storage: {message}")]
    Storage { message: String },
    #[error("not implemented yet: {message}")]
    NotImplemented { message: String },
    #[error("client is shut down")]
    Closed,
    #[error("{message}")]
    Internal { message: String },
}

impl From<mc::ClientError> for CoreError {
    fn from(err: mc::ClientError) -> Self {
        use mc::ClientError as E;
        match err {
            E::NotFound { message } => CoreError::NotFound { message },
            E::InvalidArgument { message } => CoreError::InvalidArgument { message },
            E::HostUnavailable { message } => CoreError::HostUnavailable { message },
            E::Unsupported { message } => CoreError::Unsupported { message },
            E::HostError { message } => CoreError::HostError { message },
            E::Network { message } => CoreError::Network { message },
            E::Auth { message } => CoreError::Auth { message },
            E::Storage { message } => CoreError::Storage { message },
            E::NotImplemented { message } => CoreError::NotImplemented { message },
            E::Closed => CoreError::Closed,
            E::Internal { message } => CoreError::Internal { message },
        }
    }
}

pub type CoreResult<T> = Result<T, CoreError>;

/// Why pairing failed. Ticket 18 maps these to the web's copy
/// (`web:lib/pairing-errors.ts`).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum PairError {
    #[error("invalid pairing URL: {message}")]
    InvalidUrl { message: String },
    /// `roboco-tailcat:` invites are out of scope on mobile (spec D9).
    #[error("tailcat pairing is not supported on this device")]
    TailcatUnsupported,
    /// The code was expired, already used or unknown.
    #[error("pairing link refused")]
    Refused,
    #[error("could not reach the engine: {message}")]
    Unreachable { message: String },
    #[error("pairing failed: {message}")]
    Failed { message: String },
    #[error("storage error: {message}")]
    Storage { message: String },
}

impl From<mc::PairError> for PairError {
    fn from(err: mc::PairError) -> Self {
        use mc::PairError as P;
        match err {
            P::InvalidUrl { message } => PairError::InvalidUrl { message },
            P::TailcatUnsupported => PairError::TailcatUnsupported,
            P::Refused => PairError::Refused,
            P::Unreachable { message } => PairError::Unreachable { message },
            P::Failed { message } => PairError::Failed { message },
            P::Storage { message } => PairError::Storage { message },
        }
    }
}

// ── config / records ──────────────────────────────────────────────────────

/// Static per-install configuration.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreConfig {
    /// Writable cache directory, app-private and disposable. Kotlin passes
    /// `context.noBackupFilesDir/core`.
    pub data_dir: String,
    /// The redeem label this device shows the engine (`Build.MODEL`).
    pub device_name: String,
    /// `android`.
    pub platform: String,
    pub app_version: String,
}

impl From<CoreConfig> for mc::ClientConfig {
    fn from(config: CoreConfig) -> Self {
        mc::ClientConfig {
            data_dir: config.data_dir.into(),
            device_name: config.device_name,
            platform: config.platform,
            app_version: config.app_version,
        }
    }
}

/// A paired engine's durable record. `credential` is the secret: Kotlin
/// stores the whole record Keystore-encrypted (ticket 18); Rust keeps it in
/// memory only. Never log this type — the Kotlin data class's `toString`
/// includes every field.
#[derive(Clone, uniffi::Record)]
pub struct PairedEngine {
    /// Client-minted UUID v4, stable across re-pairs (the `EngineKey`).
    pub engine_id: String,
    /// `http(s)://host[:port][/prefix]`, no trailing slash.
    pub base_url: String,
    pub credential: String,
    pub session_id: String,
    pub label: String,
    pub paired_at_ms: i64,
    /// The pinned `EngineInfo.deviceId`, once a connect has proven it.
    pub device_id: Option<String>,
    /// Last known engine device name, for offline display.
    pub name: Option<String>,
}

impl std::fmt::Debug for PairedEngine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print the credential.
        f.debug_struct("PairedEngine")
            .field("engine_id", &self.engine_id)
            .field("base_url", &self.base_url)
            .field("credential", &"[redacted]")
            .field("session_id", &self.session_id)
            .field("label", &self.label)
            .field("paired_at_ms", &self.paired_at_ms)
            .field("device_id", &self.device_id)
            .field("name", &self.name)
            .finish()
    }
}

impl From<mc::PairedEngine> for PairedEngine {
    fn from(record: mc::PairedEngine) -> Self {
        Self {
            engine_id: record.engine_id,
            base_url: record.base_url,
            credential: record.credential,
            session_id: record.session_id,
            label: record.label,
            paired_at_ms: record.paired_at_ms,
            device_id: record.device_id,
            name: record.name,
        }
    }
}

impl From<PairedEngine> for mc::PairedEngine {
    fn from(record: PairedEngine) -> Self {
        Self {
            engine_id: record.engine_id,
            base_url: record.base_url,
            credential: record.credential,
            session_id: record.session_id,
            label: record.label,
            paired_at_ms: record.paired_at_ms,
            device_id: record.device_id,
            name: record.name,
        }
    }
}

/// The minimum a pairing link carries, for the confirm UI. The token never
/// crosses this record.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PairingTarget {
    pub base_url: String,
    /// `host[:port]`.
    pub host: String,
}

impl From<mc::PairingTarget> for PairingTarget {
    fn from(target: mc::PairingTarget) -> Self {
        Self {
            base_url: target.base_url,
            host: target.host,
        }
    }
}

// ── engine views / state ──────────────────────────────────────────────────

/// One engine's connection state. `Offline` means the OS reports no
/// network; only `Parked` is pairable ("Pair again").
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EngineState {
    Connecting,
    Connected,
    Reconnecting,
    Offline,
    Parked { reason: ParkReason },
}

impl From<mc::EngineState> for EngineState {
    fn from(state: mc::EngineState) -> Self {
        match state {
            mc::EngineState::Connecting => EngineState::Connecting,
            mc::EngineState::Connected => EngineState::Connected,
            mc::EngineState::Reconnecting => EngineState::Reconnecting,
            mc::EngineState::Offline => EngineState::Offline,
            mc::EngineState::Parked { reason } => EngineState::Parked {
                reason: reason.into(),
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ParkReason {
    Revoked,
    IdentityChanged,
    Incompatible,
}

impl From<mc::ParkReason> for ParkReason {
    fn from(reason: mc::ParkReason) -> Self {
        match reason {
            mc::ParkReason::Revoked => ParkReason::Revoked,
            mc::ParkReason::IdentityChanged => ParkReason::IdentityChanged,
            mc::ParkReason::Incompatible => ParkReason::Incompatible,
        }
    }
}

/// Lenient-decoding and sync health for one engine.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct EngineDiagnostics {
    pub repaired_rows: u32,
    pub skipped_rows: u32,
    pub last_decode_error: Option<String>,
    pub missing_streams: Vec<String>,
    pub generation: u64,
}

impl From<mc::EngineDiagnostics> for EngineDiagnostics {
    fn from(diagnostics: mc::EngineDiagnostics) -> Self {
        Self {
            repaired_rows: diagnostics.repaired_rows,
            skipped_rows: diagnostics.skipped_rows,
            last_decode_error: diagnostics.last_decode_error,
            missing_streams: diagnostics.missing_streams,
            generation: diagnostics.generation,
        }
    }
}

/// What the platform renders for one engine.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EngineView {
    pub engine_id: String,
    pub name: String,
    pub host: String,
    pub base_url: String,
    pub device_id: Option<String>,
    pub platform: Option<String>,
    pub version: Option<String>,
    pub state: EngineState,
    pub last_error: Option<String>,
    pub retry_at_ms: Option<i64>,
    pub last_connected_ms: Option<i64>,
    pub capabilities: Vec<String>,
    /// The "newer engine" notice (non-blocking).
    pub notice: Option<String>,
    /// Rows come from the offline cache; no live frame yet this process.
    pub from_cache: bool,
    pub diagnostics: EngineDiagnostics,
}

impl From<mc::EngineView> for EngineView {
    fn from(view: mc::EngineView) -> Self {
        Self {
            engine_id: view.engine_id,
            name: view.name,
            host: view.host,
            base_url: view.base_url,
            device_id: view.device_id,
            platform: view.platform,
            version: view.version,
            state: view.state.into(),
            last_error: view.last_error,
            retry_at_ms: view.retry_at_ms,
            last_connected_ms: view.last_connected_ms,
            capabilities: view.capabilities,
            notice: view.notice,
            from_cache: view.from_cache,
            diagnostics: view.diagnostics.into(),
        }
    }
}

// ── events / connectivity ─────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ConnectivityState {
    /// No engines paired — hide the pill.
    Disabled,
    /// The OS network is down.
    Offline,
    /// Some non-parked engine is not connected.
    Reconnecting,
    Connected,
}

impl From<mc::ConnectivityState> for ConnectivityState {
    fn from(state: mc::ConnectivityState) -> Self {
        match state {
            mc::ConnectivityState::Disabled => ConnectivityState::Disabled,
            mc::ConnectivityState::Offline => ConnectivityState::Offline,
            mc::ConnectivityState::Reconnecting => ConnectivityState::Reconnecting,
            mc::ConnectivityState::Connected => ConnectivityState::Connected,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Connectivity {
    pub state: ConnectivityState,
    /// Epoch ms of the next scheduled redial (countdown), if any.
    pub retry_at_ms: Option<i64>,
    /// The failure that started the current outage.
    pub last_failure: Option<String>,
    /// Open sessions whose engine is not connected (ticket 08 fills this).
    pub degraded_chats: Vec<String>,
}

impl From<mc::Connectivity> for Connectivity {
    fn from(connectivity: mc::Connectivity) -> Self {
        Self {
            state: connectivity.state.into(),
            retry_at_ms: connectivity.retry_at_ms,
            last_failure: connectivity.last_failure,
            degraded_chats: connectivity.degraded_chats,
        }
    }
}

/// Coalesced change notification. Pull the new state after receiving one;
/// delivered on a client thread — hop to the main thread and return fast.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum ClientEvent {
    /// `workspace()` advanced (ticket 07 emits it).
    WorkspaceChanged {
        revision: u64,
    },
    SessionChanged {
        chat_id: String,
        revision: u64,
    },
    ComposerChanged {
        chat_id: String,
        revision: u64,
    },
    ConnectivityChanged {
        connectivity: Connectivity,
    },
    /// Any engine view changed (state, name, notice, diagnostics,
    /// capabilities): pull `engines()`.
    EnginesChanged,
    /// A background write failed (a reverted overlay): show a transient
    /// notice. Never coalesced away.
    Notice { message: String },
    /// An engine's identity was pinned: persist `device_id` (and `name`).
    EngineIdentityPinned {
        engine_id: String,
        device_id: String,
        name: String,
    },
}

impl From<mc::ClientEvent> for ClientEvent {
    fn from(event: mc::ClientEvent) -> Self {
        match event {
            mc::ClientEvent::WorkspaceChanged { revision } => {
                ClientEvent::WorkspaceChanged { revision }
            }
            mc::ClientEvent::SessionChanged { chat_id, revision } => {
                ClientEvent::SessionChanged { chat_id, revision }
            }
            mc::ClientEvent::ComposerChanged { chat_id, revision } => {
                ClientEvent::ComposerChanged { chat_id, revision }
            }
            mc::ClientEvent::ConnectivityChanged { connectivity } => {
                ClientEvent::ConnectivityChanged {
                    connectivity: connectivity.into(),
                }
            }
            mc::ClientEvent::EnginesChanged => ClientEvent::EnginesChanged,
            mc::ClientEvent::Notice { message } => ClientEvent::Notice { message },
            mc::ClientEvent::EngineIdentityPinned {
                engine_id,
                device_id,
                name,
            } => ClientEvent::EngineIdentityPinned {
                engine_id,
                device_id,
                name,
            },
        }
    }
}

// ── workspace (ticket 07) ───────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChatIndicator {
    Working,
    AwaitingInput,
    Errored,
    /// Finished but not seen yet on any device.
    Completed,
    Idle,
}

impl From<roboco_proto::ChatIndicator> for ChatIndicator {
    fn from(i: roboco_proto::ChatIndicator) -> Self {
        match i {
            roboco_proto::ChatIndicator::Working => ChatIndicator::Working,
            roboco_proto::ChatIndicator::AwaitingInput => ChatIndicator::AwaitingInput,
            roboco_proto::ChatIndicator::Errored => ChatIndicator::Errored,
            roboco_proto::ChatIndicator::Completed => ChatIndicator::Completed,
            roboco_proto::ChatIndicator::Idle => ChatIndicator::Idle,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SendState {
    Sending,
    /// Durable, waiting on a degraded path.
    Queued,
    /// "Not delivered — retry".
    Failed,
}

impl From<mc::SendState> for SendState {
    fn from(s: mc::SendState) -> Self {
        match s {
            mc::SendState::Sending => SendState::Sending,
            mc::SendState::Queued => SendState::Queued,
            mc::SendState::Failed => SendState::Failed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PullRequestState {
    Open,
    Merged,
    Closed,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PullRequest {
    pub provider: String,
    pub number: u64,
    pub title: String,
    pub url: String,
    pub state: PullRequestState,
    pub base_ref: String,
    pub head_ref: String,
}

impl From<&roboco_proto::ChangeRequestSummary> for PullRequest {
    fn from(p: &roboco_proto::ChangeRequestSummary) -> Self {
        Self {
            provider: p.provider.clone(),
            number: p.number,
            title: p.title.clone(),
            url: p.url.clone(),
            state: match p.state {
                roboco_proto::ChangeRequestState::Open => PullRequestState::Open,
                roboco_proto::ChangeRequestState::Merged => PullRequestState::Merged,
                roboco_proto::ChangeRequestState::Closed => PullRequestState::Closed,
            },
            base_ref: p.base_ref.clone(),
            head_ref: p.head_ref.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectRef {
    pub id: String,
    pub name: String,
    /// Stable palette slot (0..PROJECT_COLOR_COUNT).
    pub color_index: u32,
}

/// One session row, as every list renders it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SessionRow {
    pub id: String,
    /// The owning engine (the `EngineKey` string).
    pub engine_id: String,
    /// Content hash — equal ⇒ nothing to redraw.
    pub revision: u64,
    /// Display title ("New session" when untitled).
    pub title: String,
    pub has_title: bool,
    pub preview: Option<String>,
    /// `None` = projectless (host's home folder).
    pub project: Option<ProjectRef>,
    pub device_id: String,
    pub device_name: Option<String>,
    pub device_online: bool,
    pub engine_online: bool,
    /// Wire harness id (`claude-code`).
    pub harness: Option<String>,
    pub harness_label: Option<String>,
    pub model: Option<String>,
    pub model_label: Option<String>,
    /// Wire effort (`xhigh`).
    pub reasoning: Option<String>,
    pub branch: Option<String>,
    pub cwd: Option<String>,
    /// Host-reported status (45 s staleness-gated); an in-flight send is
    /// not evidence of a running turn.
    pub indicator: ChatIndicator,
    /// Host-reported status only (no local-send override).
    pub host_indicator: ChatIndicator,
    /// Run start of the live turn while Working/AwaitingInput.
    pub working_since_ms: Option<i64>,
    pub last_activity_ms: i64,
    /// `now` / `34m` / `4h` / `2d` at derive time.
    pub time_label: String,
    pub created_at_ms: i64,
    pub unseen: bool,
    pub archived: bool,
    pub pinned: bool,
    pub section_id: Option<String>,
    pub pull_request: Option<PullRequest>,
    /// Oldest unadopted send from this device (ticket 08).
    pub send_state: Option<SendState>,
    pub parent_chat_id: Option<String>,
}

impl From<&mc::SessionRow> for SessionRow {
    fn from(r: &mc::SessionRow) -> Self {
        Self {
            id: r.id.clone(),
            engine_id: r.engine_id.clone(),
            revision: r.revision,
            title: r.title.clone(),
            has_title: r.has_title,
            preview: r.preview.clone(),
            project: r.project.as_ref().map(|p| ProjectRef {
                id: p.id.clone(),
                name: p.name.clone(),
                color_index: p.color_index,
            }),
            device_id: r.device_id.clone(),
            device_name: r.device_name.clone(),
            device_online: r.device_online,
            engine_online: r.engine_online,
            harness: r.harness.clone(),
            harness_label: r.harness_label.clone(),
            model: r.model.clone(),
            model_label: r.model_label.clone(),
            reasoning: r.reasoning.clone(),
            branch: r.branch.clone(),
            cwd: r.cwd.clone(),
            indicator: r.indicator.into(),
            host_indicator: r.host_indicator.into(),
            working_since_ms: r.working_since_ms,
            last_activity_ms: r.last_activity_ms,
            time_label: r.time_label.clone(),
            created_at_ms: r.created_at_ms,
            unseen: r.unseen,
            archived: r.archived,
            pinned: r.pinned,
            section_id: r.section_id.clone(),
            pull_request: r.pull_request.as_ref().map(PullRequest::from),
            send_state: r.send_state.map(Into::into),
            parent_chat_id: r.parent_chat_id.clone(),
        }
    }
}

pub(crate) fn rows(rows: &[std::sync::Arc<mc::SessionRow>]) -> Vec<SessionRow> {
    rows.iter().map(|r| SessionRow::from(&**r)).collect()
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SectionView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub collapsed: bool,
    /// Recency order.
    pub sessions: Vec<SessionRow>,
}

/// A "Group by Project" bucket over `recent`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SessionGroup {
    /// `"project:<groupKey>"` or `"home:<deviceId>"`.
    pub id: String,
    pub name: String,
    pub sessions: Vec<SessionRow>,
}

/// The Sessions page — the desktop sidebar order.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FrontPage {
    /// Manual pin order: engine pairing order, then stored order.
    pub pinned: Vec<SessionRow>,
    /// User sections ("folders"), each in recency order.
    pub sections: Vec<SectionView>,
    /// Everything else, by recency.
    pub recent: Vec<SessionRow>,
    /// `recent` bucketed by project / host device.
    pub groups: Vec<SessionGroup>,
}

impl From<&mc::FrontPage> for FrontPage {
    fn from(f: &mc::FrontPage) -> Self {
        Self {
            pinned: rows(&f.pinned),
            sections: f
                .sections
                .iter()
                .map(|s| SectionView {
                    id: s.id.clone(),
                    engine_id: s.engine_id.clone(),
                    name: s.name.clone(),
                    collapsed: s.collapsed,
                    sessions: rows(&s.sessions),
                })
                .collect(),
            recent: rows(&f.recent),
            groups: f
                .groups
                .iter()
                .map(|g| SessionGroup {
                    id: g.id.clone(),
                    name: g.name.clone(),
                    sessions: rows(&g.sessions),
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ProjectView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub path: String,
    pub color_index: u32,
    pub device_id: String,
    pub device_name: Option<String>,
    pub device_online: bool,
    pub git_detected: bool,
    pub created_at_ms: i64,
    /// Shared by every checkout of one repository, on any device or engine.
    pub group_key: String,
    /// The repository group's name, shared like `color_index`.
    pub group_name: String,
    /// Most urgent indicator among its active sessions.
    pub indicator: ChatIndicator,
    pub unseen_count: u32,
    /// Active sessions, recency order.
    pub sessions: Vec<SessionRow>,
}

impl From<&mc::ProjectView> for ProjectView {
    fn from(p: &mc::ProjectView) -> Self {
        Self {
            id: p.id.clone(),
            engine_id: p.engine_id.clone(),
            name: p.name.clone(),
            path: p.path.clone(),
            color_index: p.color_index,
            device_id: p.device_id.clone(),
            device_name: p.device_name.clone(),
            device_online: p.device_online,
            git_detected: p.git_detected,
            created_at_ms: p.created_at_ms,
            group_key: p.group_key.clone(),
            group_name: p.group_name.clone(),
            indicator: p.indicator.into(),
            unseen_count: p.unseen_count,
            sessions: rows(&p.sessions),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PullRequestGroups {
    pub open: Vec<SessionRow>,
    pub merged: Vec<SessionRow>,
    pub closed: Vec<SessionRow>,
}

impl From<&mc::PullRequestGroups> for PullRequestGroups {
    fn from(p: &mc::PullRequestGroups) -> Self {
        Self {
            open: rows(&p.open),
            merged: rows(&p.merged),
            closed: rows(&p.closed),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DeviceView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub platform: String,
    pub online: bool,
    pub last_seen_ms: Option<i64>,
    pub version: Option<String>,
    pub capabilities: Vec<String>,
    /// Can run sessions (desktop/server engines — not phones).
    pub is_execution_host: bool,
    /// Always `false`: a phone is never an engine device.
    pub is_self: bool,
    pub session_count: u32,
}

impl From<&mc::DeviceView> for DeviceView {
    fn from(d: &mc::DeviceView) -> Self {
        Self {
            id: d.id.clone(),
            engine_id: d.engine_id.clone(),
            name: d.name.clone(),
            platform: d.platform.clone(),
            online: d.online,
            last_seen_ms: d.last_seen_ms,
            version: d.version.clone(),
            capabilities: d.capabilities.clone(),
            is_execution_host: d.is_execution_host,
            is_self: d.is_self,
            session_count: d.session_count,
        }
    }
}

/// Everything the workspace screens render.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WorkspaceSnapshot {
    pub revision: u64,
    pub computed_at_ms: i64,
    /// Pin/section edits allowed (every engine connected + sidebar known).
    pub pins_ready: bool,
    /// A live frame reached this device.
    pub synced: bool,
    pub front: FrontPage,
    /// Creation order.
    pub projects: Vec<ProjectView>,
    /// Active sessions without a project.
    pub projectless: Vec<SessionRow>,
    pub pull_requests: PullRequestGroups,
    pub archived: Vec<SessionRow>,
    pub devices: Vec<DeviceView>,
}

impl From<&mc::WorkspaceSnapshot> for WorkspaceSnapshot {
    fn from(w: &mc::WorkspaceSnapshot) -> Self {
        Self {
            revision: w.revision,
            computed_at_ms: w.computed_at_ms,
            pins_ready: w.pins_ready,
            synced: w.synced,
            front: (&w.front).into(),
            projects: w.projects.iter().map(Into::into).collect(),
            projectless: rows(&w.projectless),
            pull_requests: (&w.pull_requests).into(),
            archived: rows(&w.archived),
            devices: w.devices.iter().map(Into::into).collect(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SearchField {
    Title,
    Project,
    Branch,
    Preview,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchHit {
    pub session: SessionRow,
    pub score: u32,
    /// The strongest field that matched.
    pub field: SearchField,
}

impl From<&mc::SearchHit> for SearchHit {
    fn from(h: &mc::SearchHit) -> Self {
        Self {
            session: (&*h.session).into(),
            score: h.score,
            field: match h.field {
                mc::SearchField::Title => SearchField::Title,
                mc::SearchField::Project => SearchField::Project,
                mc::SearchField::Branch => SearchField::Branch,
                mc::SearchField::Preview => SearchField::Preview,
            },
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SandboxLevel {
    ReadOnly,
    WorkspaceWrite,
    DangerFullAccess,
}

impl From<roboco_proto::SandboxLevel> for SandboxLevel {
    fn from(level: roboco_proto::SandboxLevel) -> Self {
        match level {
            roboco_proto::SandboxLevel::ReadOnly => SandboxLevel::ReadOnly,
            roboco_proto::SandboxLevel::WorkspaceWrite => SandboxLevel::WorkspaceWrite,
            roboco_proto::SandboxLevel::DangerFullAccess => SandboxLevel::DangerFullAccess,
        }
    }
}

/// A chat's run configuration. Ids are wire strings.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ChatConfig {
    /// `claude-code`, `codex`, …
    pub harness: String,
    pub model: Option<String>,
    /// `low` … `ultrathink`.
    pub reasoning: Option<String>,
    /// Model option id → choice id (`contextWindow` → `1m`).
    pub model_options: HashMap<String, String>,
    pub sandbox: SandboxLevel,
}

impl From<&mc::SessionConfig> for ChatConfig {
    fn from(c: &mc::SessionConfig) -> Self {
        Self {
            harness: c.harness.clone(),
            model: c.model.clone(),
            reasoning: c.reasoning.clone(),
            model_options: c.model_options.clone(),
            sandbox: c.sandbox.into(),
        }
    }
}

// ── catalogs (static; ticket 08 adds the live calls) ───────────────────────

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HarnessInfo {
    /// Wire id (`claude-code`).
    pub id: String,
    pub label: String,
    pub supports_steering: Option<bool>,
    /// `step-boundary` / `turn-boundary`.
    pub steering_mode: Option<String>,
    pub reasoning_levels: Vec<String>,
    pub installed: bool,
    /// Offered by the listing device; `None` ⇒ legacy.
    pub enabled: Option<bool>,
}

impl From<&mc::catalog::HarnessInfo> for HarnessInfo {
    fn from(h: &mc::catalog::HarnessInfo) -> Self {
        Self {
            id: h.id.clone(),
            label: h.label.clone(),
            supports_steering: h.supports_steering,
            steering_mode: h.steering_mode.clone(),
            reasoning_levels: h.reasoning_levels.clone(),
            installed: h.installed,
            enabled: h.enabled,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ModelOptionChoice {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ModelOption {
    pub id: String,
    pub label: String,
    pub choices: Vec<ModelOptionChoice>,
    pub default_choice: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ModelInfo {
    pub id: String,
    pub label: String,
    pub description: Option<String>,
    /// Lowercase wire values (`low`…`ultrathink`); empty = no ladder.
    pub reasoning_levels: Vec<String>,
    pub options: Vec<ModelOption>,
}

impl From<&mc::catalog::ModelInfo> for ModelInfo {
    fn from(m: &mc::catalog::ModelInfo) -> Self {
        Self {
            id: m.id.clone(),
            label: m.label.clone(),
            description: m.description.clone(),
            reasoning_levels: m.reasoning_levels.clone(),
            options: m
                .options
                .iter()
                .map(|o| ModelOption {
                    id: o.id.clone(),
                    label: o.label.clone(),
                    choices: o
                        .choices
                        .iter()
                        .map(|c| ModelOptionChoice {
                            id: c.id.clone(),
                            label: c.label.clone(),
                        })
                        .collect(),
                    default_choice: o.default_choice.clone(),
                })
                .collect(),
        }
    }
}
