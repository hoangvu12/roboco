//! Per-engine state: the paired record, the connection state machine, the
//! view the platform renders, and the diagnostics of lenient decoding.

mod handle;
pub(crate) mod link;
pub(crate) mod route;

pub(crate) use handle::rpc_error_to_client_error;
pub use handle::{EngineHandle, FeedItem, FeedWatch, TranscriptConnection};
pub(crate) use link::EngineCommand;

use crate::cache::EngineCacheHandle;
use chrono::{DateTime, Utc};
use roboco_engine_client::cache::CachedRows;
use roboco_proto::{EngineInfo, SidebarStateSnapshot};
use std::sync::Arc;

/// How an engine is reached (android-next#02).
#[derive(Clone, Default, PartialEq, Eq)]
pub enum EngineTransport {
    /// `base_url` is dialed directly (LAN, or an operator tunnel).
    #[default]
    Direct,
    /// Through an in-process Tailcat tunnel. Both fields are secrets: the
    /// address embeds the engine's pre-shared key, the state this phone's
    /// private key. The platform stores them encrypted, never in plain
    /// metadata.
    Tailcat {
        address: String,
        client_state: String,
    },
}

impl EngineTransport {
    pub fn kind(&self) -> TransportKind {
        match self {
            EngineTransport::Direct => TransportKind::Direct,
            EngineTransport::Tailcat { .. } => TransportKind::Tailcat,
        }
    }
}

impl std::fmt::Debug for EngineTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print both secrets.
        match self {
            EngineTransport::Direct => f.write_str("Direct"),
            EngineTransport::Tailcat { address, .. } => f
                .debug_struct("Tailcat")
                .field("address", &crate::tailcat::describe(address))
                .field("client_state", &"[redacted]")
                .finish(),
        }
    }
}

/// The secret-free half of [`EngineTransport`], for views and the pair
/// confirm UI.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum TransportKind {
    #[default]
    Direct,
    Tailcat,
}

/// The room an engine was joined through (ADR 0011): the id is the public
/// handle every holder of the secret derives, the label is the room's name.
/// Metadata only — the secret lives with the platform (Keystore on
/// Android), never in this record; a room never syncs or owns anything
/// (ADR 0004).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineRoom {
    pub room_id: String,
    pub label: String,
}

/// A paired engine's durable record. `credential` is the secret: Rust keeps
/// it in memory only — the platform owns its encrypted storage and hands it
/// back on every `CoreClient::new`. `engine_id` is a client-minted UUID v4
/// (the `EngineKey`): stable across re-pairs, so scoped chat ids and the
/// cache survive. Never `Debug`-print this type.
#[derive(Clone, PartialEq)]
pub struct PairedEngine {
    pub engine_id: String,
    /// `http(s)://host[:port][/prefix]`, no trailing slash — or, for a
    /// Tailcat engine, the non-secret display handle `tailcat:<8 hex>`
    /// (never the address; the route lives in `transport`).
    pub base_url: String,
    /// The never-expiring session secret.
    pub credential: String,
    /// The engine's session row id (for `revoke_own_session`).
    pub session_id: String,
    /// The redeem label this device showed the engine.
    pub label: String,
    pub paired_at_ms: i64,
    /// The pinned `EngineInfo.deviceId`, once a connect has proven it.
    pub device_id: Option<String>,
    /// Last known engine device name, for offline display.
    pub name: Option<String>,
    /// How this engine is dialed. Records stored before Tailcat existed
    /// are `Direct`.
    pub transport: EngineTransport,
    /// The room this engine was joined through (ADR 0011), if any. Older
    /// records (pre-rooms) carry `None`.
    pub room: Option<EngineRoom>,
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
            .field("transport", &self.transport)
            .field("room", &self.room)
            .finish()
    }
}

/// One engine's connection state. `Offline` means the OS reports no
/// network; only `Parked` is pairable ("Pair again").
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineState {
    Connecting,
    Connected,
    Reconnecting,
    Offline,
    Parked { reason: ParkReason },
}

/// Why an engine stopped dialing. `Incompatible` covers ticket 11's
/// `ClientTooOld` / `EngineTooOld`; the specific sentence lives in
/// `EngineView.last_error`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParkReason {
    /// The engine refused the credential on upgrade (HTTP 401/403), or the
    /// record arrived with an empty credential.
    Revoked,
    /// `EngineInfo.deviceId` no longer matches the pinned id.
    IdentityChanged,
    /// Protocol versions are irreconcilable in either direction.
    Incompatible,
}

/// Lenient-decoding and sync health for one engine (ported from #621's
/// per-stream counters).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EngineDiagnostics {
    /// Rows kept after a repair.
    pub repaired_rows: u32,
    /// Rows skipped entirely (undecodable, no previous copy).
    pub skipped_rows: u32,
    /// The last decode error, if any.
    pub last_decode_error: Option<String>,
    /// Feed streams that have not delivered a frame this link.
    pub missing_streams: Vec<String>,
    /// The link generation; increments on every reconnect.
    pub generation: u64,
}

/// What the platform renders for one engine. `name` falls back in order:
/// the engine's own `Device.name` (trimmed, not `"unknown-device"`), the
/// paired label, then the host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineView {
    pub engine_id: String,
    pub name: String,
    /// `host[:port]` of the base URL.
    pub host: String,
    pub base_url: String,
    /// Direct or Tailcat ("Reconnecting over Tailcat").
    pub transport: TransportKind,
    pub device_id: Option<String>,
    pub platform: Option<String>,
    pub version: Option<String>,
    pub state: EngineState,
    pub last_error: Option<String>,
    /// Epoch ms of the next scheduled dial, while reconnecting.
    pub retry_at_ms: Option<i64>,
    /// Epoch ms of the last moment this engine was connected.
    pub last_connected_ms: Option<i64>,
    pub capabilities: Vec<String>,
    /// The "newer engine" notice (non-blocking).
    pub notice: Option<String>,
    /// No live feed frame has arrived yet this process: rows come from the
    /// offline cache.
    pub from_cache: bool,
    pub diagnostics: EngineDiagnostics,
    /// The room this engine was joined through (ADR 0011), if any —
    /// metadata only; the secret never crosses a view.
    pub room: Option<EngineRoom>,
    /// How a connected Tailcat engine is reached: directly or through a
    /// relay, with the round trip (fleet-redesign 16). `None` for LAN
    /// engines and until measured.
    pub link_path: Option<roboco_proto::tailcat::LinkPath>,
}

/// The aggregate posture across every engine.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Connectivity {
    pub state: ConnectivityState,
    /// Epoch ms of the next scheduled redial (countdown), if any.
    pub retry_at_ms: Option<i64>,
    /// The failure that started the current outage.
    pub last_failure: Option<String>,
    /// Open sessions whose engine is not connected (ticket 08 fills this).
    pub degraded_chats: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ConnectivityState {
    /// No engines paired — hide the pill.
    #[default]
    Disabled,
    /// The OS network is down.
    Offline,
    /// Some non-parked engine is not connected.
    Reconnecting,
    Connected,
}

/// The immutable per-engine slice tickets 07/08/09 consume: rows carry raw
/// engine ids (scope them with
/// [`roboco_engine_client::registry::ScopedId::encode`] through
/// [`EngineHandle`]); identity and health are as last seen.
#[derive(Debug, Clone)]
pub struct EngineSnapshot {
    /// The engine id (the `EngineKey` string).
    pub key: String,
    pub info: Option<EngineInfo>,
    pub state: EngineState,
    pub rows: CachedRows,
    pub sidebar: Option<SidebarStateSnapshot>,
    pub from_cache: bool,
    pub generation: u64,
    pub notice: Option<String>,
    pub diagnostics: EngineDiagnostics,
}

#[derive(Debug, Clone, Default)]
pub struct FleetSnapshot {
    pub engines: Vec<EngineSnapshot>,
}

impl FleetSnapshot {
    pub fn engine(&self, key: &str) -> Option<&EngineSnapshot> {
        self.engines.iter().find(|engine| engine.key == key)
    }
}

/// A live connection plus the link generation it belongs to.
#[derive(Clone)]
pub(crate) struct LiveClient {
    pub client: Arc<roboco_rpc::RpcClient>,
    pub generation: u64,
}

/// The shared, lock-guarded state of one engine. The supervisor task owns
/// the live connections and is the only writer of the mutable fields; the
/// client reads them for views and snapshots.
pub(crate) struct EngineEntry {
    /// `engine_id` also appears here (the `EngineKey` string).
    pub paired: PairedEngine,
    /// Where every dial goes (android-next#02). Replaced on re-pair.
    pub route: std::sync::Arc<route::Route>,
    pub state: EngineState,
    pub last_error: Option<String>,
    pub retry_at_ms: Option<i64>,
    pub last_connected_ms: Option<i64>,
    /// When the live link last proved itself (a feed frame or a probe
    /// reply): the rows are at most this old. A send decides "idle" from
    /// the rows only while this is recent (E1).
    pub proven_ms: Option<i64>,
    /// The last live `EngineInfo` (identity + capabilities).
    pub info: Option<EngineInfo>,
    /// The engine's own device name (from its `Device` row).
    pub engine_name: Option<String>,
    pub notice: Option<String>,
    pub generation: u64,
    pub from_cache: bool,
    pub rows: CachedRows,
    pub sidebar: Option<SidebarStateSnapshot>,
    pub diagnostics: EngineDiagnostics,
    pub cache: EngineCacheHandle,
    /// The live feed connection and its generation (`None` while down).
    pub feed_slot: tokio::sync::watch::Sender<Option<LiveClient>>,
    /// The live calls connection and its generation (`None` while down).
    pub calls_slot: tokio::sync::watch::Sender<Option<LiveClient>>,
    pub command_tx: tokio::sync::mpsc::UnboundedSender<link::EngineCommand>,
    /// The supervisor task's cancellation token.
    pub cancel: tokio_util::sync::CancellationToken,
    /// The supervisor task handle (aborted on forget/re-pair/shutdown).
    pub task: tokio::task::JoinHandle<()>,
}

impl EngineEntry {
    /// Bump and return the link generation (supervisor only).
    pub(crate) fn next_generation(&mut self) -> u64 {
        self.generation += 1;
        self.generation
    }
}

/// Display name fallback chain: the engine's own device row name (trimmed,
/// and not the engine's "unknown-device" placeholder), then the last known
/// engine device name from the paired record, then the host.
pub(crate) fn display_name(
    engine_device_name: Option<&str>,
    paired_name: Option<&str>,
    host: &str,
) -> String {
    engine_device_name
        .map(str::trim)
        .filter(|name| !name.is_empty() && *name != "unknown-device")
        .or_else(|| {
            paired_name
                .map(str::trim)
                .filter(|name| !name.is_empty() && *name != "unknown-device")
        })
        .unwrap_or(host)
        .to_owned()
}

/// Host of a base URL, for `EngineView.host`.
pub(crate) fn host_of(base_url: &str) -> String {
    match reqwest::Url::parse(base_url) {
        Ok(url) => match (url.host_str(), url.port()) {
            (Some(host), Some(port)) => format!("{host}:{port}"),
            (Some(host), None) => host.to_owned(),
            _ => base_url.to_owned(),
        },
        Err(_) => base_url.to_owned(),
    }
}

/// The feed watch set, kept a constant list so ticket 07 can rely on it:
/// extra feed streams go through `EngineHandle::watch_feed`, never here.
pub(crate) const FEED_STREAMS: [&str; 5] = [
    roboco_rpc::methods::WATCH_CHATS,
    roboco_rpc::methods::WATCH_SESSIONS,
    roboco_rpc::methods::WATCH_SPACES,
    roboco_rpc::methods::WATCH_DEVICES,
    roboco_rpc::methods::WATCH_SIDEBAR_STATE,
];

/// Milliseconds since the epoch (the phone's clock).
pub(crate) fn now_ms() -> i64 {
    Utc::now().timestamp_millis()
}

pub(crate) fn now() -> DateTime<Utc> {
    Utc::now()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_name_falls_back_in_order() {
        assert_eq!(
            display_name(Some("  Studio Mac "), None, "h:1"),
            "Studio Mac"
        );
        assert_eq!(
            display_name(Some("unknown-device"), Some("engine-name"), "h:1"),
            "engine-name"
        );
        assert_eq!(
            display_name(Some("   "), Some("engine-name"), "h:1"),
            "engine-name"
        );
        assert_eq!(
            display_name(None, Some("engine-name"), "h:1"),
            "engine-name"
        );
        assert_eq!(display_name(None, Some("  "), "host:8123"), "host:8123");
        assert_eq!(display_name(None, None, "host:8123"), "host:8123");
    }

    #[test]
    fn debug_of_a_paired_engine_never_prints_the_credential() {
        let record = PairedEngine {
            engine_id: "e1".into(),
            base_url: "http://h".into(),
            credential: "SECRET-CREDENTIAL".into(),
            session_id: "s1".into(),
            label: "Pixel".into(),
            paired_at_ms: 1,
            device_id: Some("d1".into()),
            name: None,
            transport: EngineTransport::Tailcat {
                address: "tcSECRET-ADDRESS".into(),
                client_state: "SECRET-STATE".into(),
            },
            room: None,
        };
        let printed = format!("{record:?}");
        assert!(!printed.contains("SECRET"), "{printed}");
        assert!(printed.contains("[redacted]"));
        // A room reference is metadata: it prints, and never carries the
        // room secret (which no record holds).
        let room = record.clone();
        let _ = room;
        let tagged = PairedEngine {
            room: Some(EngineRoom {
                room_id: "r".into(),
                label: "Home fleet".into(),
            }),
            ..record
        };
        let printed = format!("{tagged:?}");
        assert!(printed.contains("Home fleet"), "{printed}");
    }

    #[test]
    fn debug_of_a_transport_redacts_both_secrets() {
        let transport = EngineTransport::Tailcat {
            address: "tcSECRET-ADDRESS".into(),
            client_state: "SECRET-STATE".into(),
        };
        let printed = format!("{transport:?}");
        assert!(!printed.contains("SECRET"), "{printed}");
        assert_eq!(transport.kind(), TransportKind::Tailcat);
        assert_eq!(format!("{:?}", EngineTransport::default()), "Direct");
        assert_eq!(EngineTransport::default().kind(), TransportKind::Direct);
    }
}
