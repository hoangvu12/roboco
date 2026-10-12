//! FFI records and enums mirroring `roboco-mobile-client`'s surface, plus
//! the conversions both ways. Plain data: Kotlin gets value types.
//!
//! Wire-string ids stay strings so a newer engine's values never fail to
//! cross the boundary. Credentials cross this boundary exactly twice: in
//! (`CoreClient::new`) and out (`pair_engine`) — Kotlin owns the
//! Keystore-encrypted storage (ticket 18); Rust never persists them.

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
