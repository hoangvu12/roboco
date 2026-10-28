//! `roboco-mobile-client` — the data plane of the Roboco Android app.
//!
//! A client pairs with each engine separately (there is no cloud, no
//! account, no edge relay — ADR 0004) and holds supervised authenticated
//! WebSocket connections per engine: a **feed** connection (identity,
//! health, the registry watches), a **calls** connection (every unary RPC),
//! on-demand **transcript** connections, and one **spare**. Frames are
//! decoded leniently so a newer engine degrades one row, never the list;
//! session rows are re-stamped on the phone's clock; rows and sidebar state
//! are cached on disk for offline starts.
//!
//! [`workspace`] derives the merged multi-engine snapshot the Sessions
//! tab, Search, folders, Archived and Settings render, and routes the
//! workspace mutations (archive, rename, mark seen, pins, sections) to
//! the owning engine; [`catalog`] holds the static harness/model labels;
//! [`accounts`] wraps the engine's agent (provider) accounts — the
//! logins in use and their plan-usage windows — with the paint-at-once
//! snapshot cache (android-next#05);
//! [`new_session`] creates chats (ticket 09) with the optimistic pending
//! overlay; [`projects`] browses folders, drives and refs and creates
//! projects; [`host_paths`] speaks the engine's own path style.
//!
//! Pure Rust + tokio, UniFFI-free, so `cargo test` runs on any host.
//! `roboco-mobile` wraps this crate in `crates/mobile/src/client_ffi`.

pub mod accounts;
pub mod attachments;
pub mod cache;
pub mod catalog;
pub mod catalog_live;
pub mod clock;
pub mod compat;
pub mod engines;
pub mod events;
pub mod failover;
pub mod files;
pub mod host_paths;
pub mod lenient;
pub mod new_session;
pub mod notify;
pub mod pairing;
pub mod previews;
pub mod projects;
pub mod rooms;
pub mod runtime;
pub mod session;
pub mod tailcat;
pub mod timings;
pub mod workspace;

#[cfg(test)]
pub(crate) mod test_support;

use cache::{CacheCommand, CacheWriter, EngineCacheHandle};
use engines::{EngineCommand, EngineEntry, host_of};
use events::EventPump;

use roboco_engine_client::cache::{CachedRows, EngineCache};
use roboco_engine_client::registry::ScopedId;
use roboco_proto::EngineInfo;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::Duration;
use timings::Timings;
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

pub use accounts::{UsageLevel, usage_level, USAGE_CRITICAL_FRACTION, USAGE_WARN_FRACTION};
pub use engines::{
    Connectivity, ConnectivityState, EngineDiagnostics, EngineHandle, EngineRoom, EngineSnapshot,
    EngineState, EngineTransport, EngineView, FeedItem, FeedWatch, FleetSnapshot, PairedEngine,
    ParkReason, TranscriptConnection, TransportKind,
};
pub use events::{ClientEvent, ClientListener};
pub use notify::{NotifyListener, RunActivity, RunRef, SessionEvent};
pub use pairing::{PairError, PairingTarget, parse_pairing_url};
pub use previews::{PanelPhase, PanelState, PreviewPanel, DEFAULT_IDLE, PANEL_PROXY_PORT};
pub use roboco_preview_relay::client::ClientStatus;
pub use rooms::{FleetOffer, RoomError, RoomInviteTarget, RoomJoin, RoomRecord, parse_room_invite};
pub use files::FileMatch;
pub use new_session::{
    NewSession, PENDING_CHAT_TTL_MS, PendingChat, ProjectGroup, SessionTarget, group_projects,
};
pub use projects::PreparedPath;
pub use session::{
    BusyPolicy, ComposerState, HostCapabilities, HostInfo, InputRequest, LiveStatus,
    OutgoingAttachment, PendingKind, PendingSend, QueueEditAction, QueueEditFinish, QueueEditLease,
    QueueEditStart, QueueGate, QueueItem, RoomState, SendOutcome, SendRequest, SessionHandle,
    SessionLimitInfo, SessionSnapshot, SnapshotWatch, SubagentRow, SubagentState,
    UNDELIVERED_GRACE_MS, subagent_title,
};
// `SendState` binds once at the crate root, through workspace (07's
// canonical re-export): session/pending re-exports the same type, so
// `mc::session::SendState`, the workspace rows and the pending sends all
// share one enum.
pub use workspace::{
    DeviceView, FrontPage, ProjectRef, ProjectView, PullRequestGroups, SearchField, SearchHit,
    SectionView, SendState, SessionConfig, SessionGroup, SessionRow, WorkspaceSnapshot,
    project_color_index, relative_time_label,
};

pub(crate) fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Static per-install configuration.
#[derive(Debug, Clone)]
pub struct ClientConfig {
    /// App-private, disposable cache root. Android passes
    /// `context.noBackupFilesDir/core` (backup excludes it automatically).
    pub data_dir: PathBuf,
    /// The redeem label this device shows the engine (`Build.MODEL`).
    pub device_name: String,
    /// `android`.
    pub platform: String,
    /// The app's display version.
    pub app_version: String,
    /// Offer the mock harness in catalogs (debug builds and tests set it;
    /// the mock is a test rig, never a production picker entry).
    pub show_mock_harness: bool,
    /// Where `libroboco_tailcat` lives (Android:
    /// `applicationInfo.nativeLibraryDir`; host tests: the xtask's host
    /// directory). `None`: Tailcat is unavailable (android-next#02).
    pub native_lib_dir: Option<PathBuf>,
}

/// The client error surface. `HostUnavailable` = the engine is not
/// connected ("Engine is offline; reconnecting"); `Auth` = the engine is
/// parked; `Unsupported` = the engine answered `unknown method`;
/// `HostError` = the engine answered an error.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ClientError {
    #[error("not found: {message}")]
    NotFound { message: String },
    #[error("invalid argument: {message}")]
    InvalidArgument { message: String },
    #[error("engine unavailable: {message}")]
    HostUnavailable { message: String },
    #[error("not supported by the engine: {message}")]
    Unsupported { message: String },
    #[error("engine error: {message}")]
    HostError { message: String },
    #[error("network: {message}")]
    Network { message: String },
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

/// The shared, lock-guarded engine table plus the last emitted aggregates.
struct EnginesState {
    entries: Vec<EngineEntry>,
    last_views: Vec<EngineView>,
    last_connectivity: Connectivity,
}

pub(crate) struct ClientInner {
    config: ClientConfig,
    timings: Timings,
    engines: Mutex<EnginesState>,
    fleet: watch::Sender<Arc<FleetSnapshot>>,
    pump: Arc<EventPump>,
    /// The derived workspace store (ticket 07); installed right after
    /// construction because it needs a `Weak` back-reference.
    pub(crate) workspace: OnceLock<Arc<workspace::WorkspaceStore>>,
    /// The preview hub (agent-previews ticket 05): the shared client proxy
    /// on 7332 plus the per-chat panel state machines. Same install shape
    /// as the workspace store — a `Weak` back-reference is needed.
    pub(crate) previews: OnceLock<Arc<previews::PreviewsHub>>,
    /// Cancels the event pump on `shutdown`.
    client_cancel: CancellationToken,
    /// Client-wide network flag (`true` = online). `false` only for a
    /// definitive OS "no network"; ambiguity counts as online.
    network: watch::Sender<bool>,
    /// Client-wide foreground flag (drives the spare policy).
    foreground: watch::Sender<bool>,
    cache_tx: mpsc::UnboundedSender<CacheCommand>,
    cache: EngineCache,
    /// The warm-session registry and the config overrides (ticket 08).
    sessions: session::SessionRegistry,
    /// The live harness/model catalog state (ticket 08).
    catalog: catalog_live::Catalog,
    /// The newest agent-accounts snapshot per engine (android-next#05):
    /// the paint-at-once cache behind the usage chip and the Accounts &
    /// Usage page.
    accounts: accounts::AccountsState,
    /// Decoded attachment bytes, byte-budgeted LRU (ticket 08). Shared so
    /// session tasks can hold it across awaits.
    attachments: Arc<attachments::AttachmentCache>,
    /// The notification driver (ticket 28): baselines, link watches and
    /// the send ledger; silent until a listener is installed.
    pub(crate) notify: Arc<notify::NotifyHub>,
    /// Opens Tailcat tunnels (android-next#02): the library opener in
    /// production, a fake in route tests.
    tailcat: Arc<dyn tailcat::TunnelOpener>,
    /// The joined rooms (ADR 0011, ticket 04): the platform's stored
    /// records (secret + book), in memory only — Rust never persists
    /// them, exactly like credentials.
    rooms: Mutex<Vec<rooms::RoomRecord>>,
    /// One heal pass at a time, triggered from the existing start and
    /// network-change moments (see `rooms`).
    room_heal: rooms::HealGate,
    /// The in-flight room-member joins (rooms): one presentation per
    /// (room, address) at a time.
    room_joins: rooms::JoinGuard,
}

/// The mobile client. Cheap to clone. Create once per app run with the
/// platform's stored paired-engine records; `pair_engine` mints new ones.
#[derive(Clone)]
pub struct Client {
    inner: Arc<ClientInner>,
}

impl Client {
    /// Build and start. Never blocks on the network: the disk cache loads
    /// synchronously (rows + sidebar publish with `from_cache = true`),
    /// then every engine dials in the background. An engine whose record
    /// carries an empty credential parks as revoked without dialing.
    /// `rooms` are the platform's stored joined rooms (the `room:` blobs):
    /// in-memory only, and the room heal pass rides the same start —
    /// members without an engine entry get one dial each, then their own
    /// supervisor (ADR 0011).
    pub fn new(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<rooms::RoomRecord>,
        listener: Arc<dyn ClientListener>,
    ) -> Result<Arc<Self>, ClientError> {
        Self::new_with_timings(config, engines, rooms, listener, Timings::default())
    }

    /// Test seam: every supervisor timing in one place, so liveness
    /// behaviour runs in seconds.
    pub fn new_with_timings(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<rooms::RoomRecord>,
        listener: Arc<dyn ClientListener>,
        timings: Timings,
    ) -> Result<Arc<Self>, ClientError> {
        let opener: Arc<dyn tailcat::TunnelOpener> = Arc::new(tailcat::LibraryOpener::new(
            config.native_lib_dir.clone(),
            None,
        ));
        Self::new_with_opener(config, engines, rooms, listener, timings, opener)
    }

    /// Test seam: a custom Tailcat tunnel opener (no Go library needed).
    #[doc(hidden)]
    pub fn new_with_opener(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<rooms::RoomRecord>,
        listener: Arc<dyn ClientListener>,
        timings: Timings,
        opener: Arc<dyn tailcat::TunnelOpener>,
    ) -> Result<Arc<Self>, ClientError> {
        Self::new_with_knobs(
            config,
            engines,
            rooms,
            listener,
            timings,
            opener,
            previews::PANEL_PROXY_PORT,
            previews::DEFAULT_IDLE,
        )
    }

    /// Test seam: the client preview proxy's bind knobs — an ephemeral
    /// port and a short idle, so the preview tests never touch the stable
    /// 7332 another test binary may be squatting on.
    #[doc(hidden)]
    pub fn new_with_preview_knobs(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<rooms::RoomRecord>,
        listener: Arc<dyn ClientListener>,
        timings: Timings,
        opener: Arc<dyn tailcat::TunnelOpener>,
        preview_port: u16,
        preview_idle: Duration,
    ) -> Result<Arc<Self>, ClientError> {
        Self::new_with_knobs(
            config,
            engines,
            rooms,
            listener,
            timings,
            opener,
            preview_port,
            preview_idle,
        )
    }

    fn new_with_knobs(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<rooms::RoomRecord>,
        listener: Arc<dyn ClientListener>,
        timings: Timings,
        opener: Arc<dyn tailcat::TunnelOpener>,
        preview_port: u16,
        preview_idle: Duration,
    ) -> Result<Arc<Self>, ClientError> {
        std::fs::create_dir_all(&config.data_dir).map_err(|error| ClientError::Storage {
            message: error.to_string(),
        })?;
        let cache = EngineCache::new(&config.data_dir);
        let cache_tx = CacheWriter::start(cache.clone(), timings.cache_debounce);
        let (fleet, _) = watch::channel(Arc::new(FleetSnapshot::default()));
        let (network, _) = watch::channel(true);
        let (foreground, _) = watch::channel(true);
        let client_cancel = CancellationToken::new();
        let pump = EventPump::new(listener);
        pump.start(client_cancel.clone());
        let mut entries = Vec::with_capacity(engines.len());
        for paired in engines {
            // Cached rows load before any dial: the list paints immediately.
            let handle =
                EngineCacheHandle::new(paired.engine_id.clone(), cache.clone(), cache_tx.clone());
            let rows = handle.load_rows().unwrap_or_default();
            let sidebar = handle.load_sidebar();
            // Mobile-only bound: keep the newest 100 transcripts.
            {
                let cache = cache.clone();
                let engine_id = paired.engine_id.clone();
                crate::runtime::shared()
                    .spawn_blocking(move || crate::cache::prune_transcripts(&cache, &engine_id));
            }
            let route =
                engines::route::Route::for_record(&paired.base_url, &paired.transport, &opener);
            entries.push(pending_entry(paired, route, handle, rows, sidebar));
        }
        // Built before the literal: `timings` moves into its field below.
        let notify = notify::NotifyHub::new(timings.notify.clone());
        // The preview hub's label file lives under the same cache root;
        // read before `config` moves into the literal.
        let preview_labels_dir = config.data_dir.clone();
        let inner = Arc::new(ClientInner {
            config,
            timings,
            engines: Mutex::new(EnginesState {
                entries,
                last_views: Vec::new(),
                last_connectivity: Connectivity::default(),
            }),
            fleet,
            pump,
            workspace: OnceLock::new(),
            previews: OnceLock::new(),
            client_cancel,
            network,
            foreground,
            cache_tx,
            cache,
            sessions: session::SessionRegistry::new(),
            catalog: catalog_live::Catalog::default(),
            accounts: accounts::AccountsState::default(),
            attachments: Arc::new(attachments::AttachmentCache::new(
                attachments::ATTACHMENT_CACHE_BUDGET,
            )),
            notify,
            rooms: Mutex::new(rooms),
            room_heal: rooms::HealGate::default(),
            room_joins: rooms::JoinGuard::default(),
            tailcat: opener,
        });
        let workspace = workspace::WorkspaceStore::start(Arc::downgrade(&inner));
        let _ = inner.workspace.set(workspace);
        let previews = previews::PreviewsHub::start(
            Arc::downgrade(&inner),
            &preview_labels_dir,
            preview_port,
            preview_idle,
        );
        let _ = inner.previews.set(previews);
        let ids: Vec<String> = lock(&inner.engines)
            .entries
            .iter()
            .map(|entry| entry.paired.engine_id.clone())
            .collect();
        for engine_id in ids {
            start_supervision(&inner, &engine_id, None);
        }
        publish(&inner);
        // The room heal rides the same start the supervisors do (one
        // bounded dial per missing book member — no timers of its own).
        // The room heal rides the same start the supervisors do (one
        // bounded dial per missing book member — no timers of its own).
        inner.room_heal.trigger(&inner);
        Ok(Arc::new(Self { inner }))
    }

    // ── engines ──────────────────────────────────────────────────────────

    /// Pair a new engine (or re-pair one). Redeems the one-time code,
    /// dials, pins the engine identity when the dial answers, and returns
    /// the record the platform must persist (Keystore-encrypted on
    /// Android). If the dial or `EngineInfo` fails, the record is still
    /// returned with `device_id: None` — the credential is already minted
    /// and single-use codes cannot be re-redeemed; the engine shows
    /// `Reconnecting` and the first successful connect pins it.
    pub async fn pair_engine(
        &self,
        pairing_url: &str,
        replace_engine_id: Option<String>,
    ) -> Result<PairedEngine, PairError> {
        let redeemed = pairing::redeem_pairing(
            pairing_url,
            &self.inner.config.device_name,
            &self.inner.tailcat,
        )
        .await?;
        let label = pairing::sanitize_label(&self.inner.config.device_name);
        let record = adopt_engine(
            &self.inner,
            redeemed,
            &label,
            None,
            replace_engine_id,
        )
        .await;
        pin_event(&self.inner, &record);
        publish(&self.inner);
        self.inner.pump.engines_changed();
        Ok(record)
    }

    /// Join a room from a `roboco-room:` invite (ADR 0011, ticket 04): the
    /// phone's "scan once" — every book member gets its own session and
    /// engine entry, exactly like a pairing (see [`rooms::join_room`]).
    /// Partial joins are a success: missing members heal on app start and
    /// network change.
    pub async fn join_room(&self, invite: &str) -> Result<rooms::RoomJoin, rooms::RoomError> {
        rooms::join_room(&self.inner, invite).await
    }

    /// Fleet follow, step 1: ask a paired engine (normally the one
    /// `pair_engine` just returned) for its fleet. `None`, silently, when
    /// the engine lacks `rooms-v2`, has no fleet, never connects, or
    /// answers junk — the pairing then looks exactly like it always did.
    /// The offer is opaque: the secret never leaves Rust here.
    pub async fn fetch_fleet(&self, engine_id: &str) -> Option<rooms::FleetOffer> {
        rooms::fetch_fleet(&self.inner, engine_id).await
    }

    /// Fleet follow, step 2: join the offered fleet — the silent room
    /// join, deduped against every engine already paired (see
    /// [`rooms::join_fleet`]). Re-running it for the same fleet is
    /// idempotent: no new sessions, no new engine entries.
    pub async fn join_fleet(
        &self,
        offer: rooms::FleetOffer,
    ) -> Result<rooms::RoomJoin, rooms::RoomError> {
        rooms::join_fleet(&self.inner, offer).await
    }

    /// Both fleet steps at once (tests and headless callers):
    /// `Ok(None)` when there is no fleet to follow.
    pub async fn follow_fleet(
        &self,
        engine_id: &str,
    ) -> Result<Option<rooms::RoomJoin>, rooms::RoomError> {
        match self.fetch_fleet(engine_id).await {
            Some(offer) => self.join_fleet(offer).await.map(Some),
            None => Ok(None),
        }
    }

    /// Every paired engine, in pairing order.
    pub fn engines(&self) -> Vec<EngineView> {
        lock(&self.inner.engines).last_views.clone()
    }

    pub fn engine(&self, engine_id: &str) -> Option<EngineView> {
        self.engines()
            .into_iter()
            .find(|view| view.engine_id == engine_id)
    }

    /// Abort the links, flush the cache writer, then remove the engine's
    /// cache directory. Scoped ids of the forgotten engine stop resolving.
    pub async fn forget_engine(&self, engine_id: &str) -> Result<(), ClientError> {
        let removed = {
            let mut state = lock(&self.inner.engines);
            let index = state
                .entries
                .iter()
                .position(|entry| entry.paired.engine_id == engine_id)
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown engine: {engine_id}"),
                })?;
            state.entries.remove(index)
        };
        removed.cancel.cancel();
        removed.task.abort();
        removed.route.invalidate();
        let _ = removed.task.await;
        // No new write can register once the engine task is cancelled;
        // joining the drained writes keeps an in-flight save from
        // recreating the directory being removed.
        removed.cache.flush().await;
        // A forgotten engine's notification state (baselines, notified
        // chats, its link watch) goes with it; the platform cancels its
        // notifications.
        self.inner.notify.reset_engine(engine_id);
        let cache = removed.cache.clone();
        let _ = tokio::task::spawn_blocking(move || cache.forget())
            .await
            .map_err(|error| ClientError::Internal {
                message: error.to_string(),
            })?;
        publish(&self.inner);
        self.inner.pump.engines_changed();
        Ok(())
    }

    /// Rename the engine's own device row on the engine
    /// (`Mutate {op: "renameDevice"}`); the pinned device id is required.
    pub async fn rename_engine(&self, engine_id: &str, name: &str) -> Result<(), ClientError> {
        let handle = self.engine_handle(engine_id)?;
        let device_id = handle
            .engine_info()
            .map(|info| info.device_id)
            .or_else(|| {
                lock(&self.inner.engines)
                    .entries
                    .iter()
                    .find(|entry| entry.paired.engine_id == engine_id)
                    .and_then(|entry| entry.paired.device_id.clone())
            })
            .ok_or_else(|| ClientError::InvalidArgument {
                message: "Pair the engine before renaming it.".into(),
            })?;
        let name = name.trim();
        if name.is_empty() {
            return Err(ClientError::InvalidArgument {
                message: "Name cannot be empty.".into(),
            });
        }
        handle
            .call(
                roboco_rpc::methods::MUTATE,
                serde_json::json!({"op": "renameDevice", "deviceId": device_id, "name": name}),
            )
            .await?;
        Ok(())
    }

    /// Revoke this device's pairing session on the engine
    /// (`RevokePairingSession`), best-effort: called before
    /// `forget_engine`; failures are logged, never fatal. Capped at 3 s
    /// (the Android forget flow's own cap — a hung engine must never
    /// block the forget sequence, and the FFI call is cancellable too).
    pub async fn revoke_own_session(&self, engine_id: &str) -> Result<(), ClientError> {
        let handle = self.engine_handle(engine_id)?;
        let session_id = lock(&self.inner.engines)
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == engine_id)
            .map(|entry| entry.paired.session_id.clone())
            .unwrap_or_default();
        if session_id.is_empty() {
            return Ok(());
        }
        match tokio::time::timeout(
            Duration::from_secs(3),
            handle.call(
                roboco_rpc::methods::REVOKE_PAIRING_SESSION,
                serde_json::json!({"sessionId": session_id}),
            ),
        )
        .await
        {
            Ok(Ok(_)) | Ok(Err(ClientError::Unsupported { .. })) => Ok(()),
            Ok(Err(error)) => {
                tracing::warn!(%error, engine_id, "session revoke failed (best-effort)");
                Ok(())
            }
            Err(_) => {
                tracing::warn!(engine_id, "session revoke timed out (best-effort)");
                Ok(())
            }
        }
    }

    /// Reset backoff and dial now. Non-parked engines only — except
    /// `Parked { Incompatible }`, which is re-assessed on a fresh connect.
    pub fn reconnect_engine(&self, engine_id: &str) {
        let mut restart = None;
        {
            let mut state = lock(&self.inner.engines);
            let Some(entry) = state
                .entries
                .iter_mut()
                .find(|entry| entry.paired.engine_id == engine_id)
            else {
                return;
            };
            match entry.state {
                EngineState::Parked {
                    reason: ParkReason::Revoked | ParkReason::IdentityChanged,
                } => return,
                EngineState::Parked {
                    reason: ParkReason::Incompatible,
                } => {
                    let task = std::mem::replace(&mut entry.task, finished_task());
                    entry.cancel.cancel();
                    restart = Some((task, entry.paired.engine_id.clone()));
                }
                _ => {
                    let _ = entry.command_tx.send(EngineCommand::dial_now());
                }
            }
        }
        if let Some((task, engine_id)) = restart {
            task.abort();
            start_supervision(&self.inner, &engine_id, None);
        }
        publish(&self.inner);
    }

    /// Pull-to-refresh: reconnect everything not connected, probe every
    /// connected feed; resolves when every non-parked engine delivered a
    /// fresh feed item or failed, capped at `timings.refresh_cap`.
    pub async fn refresh(&self) {
        let acks: Vec<oneshot::Receiver<()>> = {
            let state = lock(&self.inner.engines);
            state
                .entries
                .iter()
                .filter(|entry| !matches!(entry.state, EngineState::Parked { .. }))
                .map(|entry| {
                    let (ack, receipt) = oneshot::channel();
                    if entry.state != EngineState::Connected {
                        let _ = entry.command_tx.send(EngineCommand::dial_now());
                    }
                    let _ = entry.command_tx.send(EngineCommand::Refresh(ack));
                    receipt
                })
                .collect()
        };
        // One hard cap across every engine (the ticket's "hard cap of 3 s"),
        // not one per engine: the receipts always resolve on their own
        // within the per-engine refresh deadline.
        let cap = self.inner.timings.refresh_cap;
        let waits: Vec<_> = acks
            .into_iter()
            .map(|receipt| {
                tokio::spawn(async move {
                    let _ = receipt.await;
                })
            })
            .collect();
        let _ = tokio::time::timeout(cap, async move {
            for wait in waits {
                let _ = wait.await;
            }
        })
        .await;
    }

    /// The aggregate connectivity posture.
    pub fn connectivity(&self) -> Connectivity {
        lock(&self.inner.engines).last_connectivity.clone()
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /// OS network path: `false` only for a definitive "no network" —
    /// every non-parked engine becomes `Offline`, pending dials are
    /// cancelled, and no dial attempts run. `true` resets the backoff and
    /// dials every engine that is not connected now; a connected link is
    /// left alone (the platform repeats `true` on every network callback).
    pub fn set_network_online(&self, online: bool) {
        let was_online = self.inner.network.send_replace(online);
        if online && !was_online {
            // Back from offline: the interface may have changed under the
            // Tailcat tunnels (android-next#02), and the room heal rides
            // the same transition (missing members get one dial).
            self.drop_tailcat_tunnels();
            self.inner.room_heal.trigger(&self.inner);
        }
        if online {
            // Only a backoff wait is cut short: a dial in flight finishes on
            // its own (a redial request would tear down the link it
            // produces), and an offline engine resumes when the network
            // flag flips.
            let state = lock(&self.inner.engines);
            for entry in state
                .entries
                .iter()
                .filter(|entry| entry.state == EngineState::Reconnecting)
            {
                let _ = entry.command_tx.send(EngineCommand::dial_now());
            }
        } else {
            let mut state = lock(&self.inner.engines);
            for entry in &mut state.entries {
                if !matches!(entry.state, EngineState::Parked { .. }) {
                    entry.state = EngineState::Offline;
                    entry.retry_at_ms = None;
                }
            }
        }
        publish(&self.inner);
    }

    /// The OS default network changed (another `Network`, or its link
    /// properties). A connected Tailcat engine is verified first: an
    /// immediate probe on a short budget, and only a failed one rebuilds
    /// the tunnel (so the next dial binds the new interface) and redials —
    /// a network event must not kill a link that still works. A Tailcat
    /// engine that is down rebuilds its tunnel and dials now. Direct
    /// engines are untouched. The room heal pass rides the same moment:
    /// book members without a session get one dial each (presentations
    /// are repeatable, so an interrupted join heals here) — no new timer,
    /// no probing (ADR 0011).
    pub fn set_network_changed(&self) {
        {
            let state = lock(&self.inner.engines);
            for entry in state.entries.iter().filter(|entry| {
                entry.route.is_tailcat() && !matches!(entry.state, EngineState::Parked { .. })
            }) {
                let _ = entry.command_tx.send(EngineCommand::verify());
            }
        }
        self.inner.room_heal.trigger(&self.inner);
    }

    fn drop_tailcat_tunnels(&self) {
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            entry.route.invalidate();
        }
    }

    /// The app is foreground: probe every connected feed immediately (this
    /// catches sockets Doze or a frozen process killed) and dial everything
    /// else now. Re-open spares. One command per engine: a second one
    /// queued behind the first used to redial the link the first produced.
    pub fn on_foreground(&self) {
        self.inner.foreground.send_replace(true);
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            entry.route.set_background(false);
        }
        for entry in &state.entries {
            // A dial in flight (Connecting) finishes on its own; an offline
            // engine waits for the network.
            let command = match entry.state {
                EngineState::Connected => EngineCommand::foreground(),
                EngineState::Reconnecting => EngineCommand::dial_now(),
                _ => continue,
            };
            let _ = entry.command_tx.send(command);
        }
    }

    /// The app is backgrounding: flush every pending cache write (bounded
    /// at 2 s), then close spares. Feed and calls connections stay; the
    /// subscriptions of detached, quiet sessions close (ticket 08).
    pub fn on_background(&self) {
        self.inner.foreground.send_replace(false);
        for entry in &lock(&self.inner.engines).entries {
            entry.route.set_background(true);
        }
        {
            let cache_tx = self.inner.cache_tx.clone();
            crate::runtime::shared().spawn(async move {
                let (done, receipt) = oneshot::channel();
                if cache_tx.send(CacheCommand::Flush(done)).is_ok() {
                    let _ = tokio::time::timeout(Duration::from_secs(2), receipt).await;
                }
            });
        }
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            let _ = entry.command_tx.send(EngineCommand::Background);
        }
        drop(state);
        self.inner.sessions.close_background();
    }

    /// Stop all tasks (sign-out / app teardown). Snapshots stay readable.
    pub fn shutdown(&self) {
        self.inner.client_cancel.cancel();
        self.inner.notify.shutdown();
        if let Some(workspace) = self.inner.workspace.get() {
            workspace.shutdown();
        }
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            entry.cancel.cancel();
            entry.task.abort();
            entry.route.invalidate();
        }
    }

    // ── notifications (ticket 28) ───────────────────────────────────────

    /// Install the notification driver's delivery surface (the FFI layer
    /// bridges the platform sink here). Idempotent-ish: a later call
    /// replaces the listener; the drive task runs until `shutdown`.
    pub fn set_notify_listener(&self, listener: Arc<dyn NotifyListener>) {
        self.inner
            .notify
            .set_listener(listener, Arc::downgrade(&self.inner));
    }

    // ── previews (agent-previews ticket 05) ──────────────────────────

    /// Start watching one chat's previews: `WatchPreviews {chatId}` on
    /// the chat's engine, with the panel state machine of the desktop's
    /// per-browser-tab subscription (2 s resubscribe, the
    /// `state/preview.ts` phases). `chat_id` is the **scoped** id the
    /// app holds; a chat whose engine is not paired fails fast with
    /// `NotFound` (no preview surface exists on unpaired engines beyond
    /// the panel's honest note).
    pub fn watch_previews(&self, chat_id: &str) -> Result<PreviewPanel, ClientError> {
        let scoped = ScopedId::parse(chat_id).map_err(rpc_error_to_client_error)?;
        let engine_id = scoped.engine.0;
        // The engine key must be one of ours (a local/unscoped id cannot
        // be: the app only ever holds scoped chat ids, but a malformed
        // one must fail fast, not dial an empty engine).
        if !lock(&self.inner.engines)
            .entries
            .iter()
            .any(|entry| entry.paired.engine_id == engine_id)
        {
            return Err(ClientError::NotFound {
                message: format!("Unknown engine for chat: {engine_id}"),
            });
        }
        Ok(previews::start_panel(
            &self.inner,
            &engine_id,
            chat_id,
        ))
    }

    /// The client preview proxy's listener status (the bound 7332, or the
    /// inline bind error). Never blocks; the listener itself starts
    /// lazily on the first watcher.
    pub fn preview_proxy_status(
        &self,
    ) -> roboco_preview_relay::client::ClientStatus {
        self.hub().status()
    }

    /// Mark an engine's previews as viewed (or not) — the proxy
    /// listener's lazy start / idle shutdown. The panel does this on its
    /// own open/close; the browser entry holds its own watcher while it
    /// is on screen, so the 7332 listener stays bound while a preview is
    /// actually being viewed even with the panel's composition disposed
    /// beneath it.
    pub fn preview_proxy_set_watching(&self, engine_id: &str, watching: bool) {
        self.hub().watch_engine(engine_id, watching);
    }

    fn hub(&self) -> &Arc<previews::PreviewsHub> {
        self.inner
            .previews
            .get()
            .expect("the preview hub starts with the client")
    }

    // ── workspace (ticket 07) ─────────────────────────────────────────

    /// The whole derived workspace. Cheap: clones an `Arc`.
    pub fn workspace(&self) -> Arc<WorkspaceSnapshot> {
        self.store().snapshot()
    }

    /// Current workspace revision (compare before pulling).
    pub fn workspace_revision(&self) -> u64 {
        self.store().revision()
    }

    /// Case-insensitive client-side search over the cached merged rows
    /// (Roboco has no search RPC — ADR 0004).
    pub fn search(&self, query: &str, limit: usize) -> Vec<SearchHit> {
        self.workspace().search(query, limit)
    }

    /// A chat's run configuration, wire-string form (or ticket 08's
    /// optimistic override).
    pub fn session_config(&self, chat_id: &str) -> Option<SessionConfig> {
        self.store().session_config(chat_id)
    }

    pub fn archive_session(&self, chat_id: &str) -> Result<(), ClientError> {
        self.store().set_chat_archived(chat_id, true)
    }

    pub fn unarchive_session(&self, chat_id: &str) -> Result<(), ClientError> {
        self.store().set_chat_archived(chat_id, false)
    }

    /// A blank (whitespace-only) title is a no-op returning `Ok`.
    pub fn rename_session(&self, chat_id: &str, title: &str) -> Result<(), ClientError> {
        self.store().rename_session(chat_id, title)
    }

    pub fn delete_session(&self, chat_id: &str) -> Result<(), ClientError> {
        self.store().delete_session(chat_id)
    }

    /// Fire-and-forget; never fails, never rolls back.
    pub fn mark_seen(&self, chat_id: &str) {
        self.store().mark_seen(chat_id);
    }

    pub fn pin_session(&self, chat_id: &str) -> Result<(), ClientError> {
        self.store().pin_session(chat_id)
    }

    pub fn unpin_session(&self, chat_id: &str) -> Result<(), ClientError> {
        self.store().unpin_session(chat_id)
    }

    /// Reorder a pin between neighbours (`None` at either end). Anchors
    /// are scoped ids; anchors from other engines count as absent.
    pub fn move_pin(
        &self,
        chat_id: &str,
        after: Option<&str>,
        before: Option<&str>,
    ) -> Result<(), ClientError> {
        self.store().move_pin(chat_id, after, before)
    }

    /// Create a section; `engine_id: None` picks the first connected
    /// engine. Returns the **scoped** section id.
    pub fn create_section(
        &self,
        name: &str,
        engine_id: Option<&str>,
    ) -> Result<String, ClientError> {
        self.store().create_section(name, engine_id)
    }

    pub fn rename_section(&self, section_id: &str, name: &str) -> Result<(), ClientError> {
        self.store().rename_section(section_id, name)
    }

    pub fn delete_section(&self, section_id: &str) -> Result<(), ClientError> {
        self.store().delete_section(section_id)
    }

    pub fn set_section_collapsed(&self, section_id: &str, collapsed: bool) -> Result<(), ClientError> {
        self.store().set_section_collapsed(section_id, collapsed)
    }

    /// Move a chat into a section (`None` = back to Recent).
    pub fn assign_section(
        &self,
        chat_id: &str,
        section_id: Option<&str>,
    ) -> Result<(), ClientError> {
        self.store().assign_section(chat_id, section_id)
    }

    fn store(&self) -> &Arc<workspace::WorkspaceStore> {
        self.inner
            .workspace
            .get()
            .expect("the workspace store starts with the client")
    }

    // ── internal routing API (tickets 07/08/09) ──────────────────────────

    /// A handle to one engine.
    pub fn engine_handle(&self, engine_id: &str) -> Result<EngineHandle, ClientError> {
        engine_handle_of(&self.inner, engine_id)
    }

    /// The inner client (crate-internal consumers: tickets 07–09).
    pub(crate) fn inner_arc(&self) -> Arc<ClientInner> {
        self.inner.clone()
    }

    /// Resolve a scoped id to its engine and the engine's raw id.
    pub fn resolve(&self, scoped_id: &str) -> Result<(EngineHandle, String), ClientError> {
        let scoped = ScopedId::parse(scoped_id).map_err(rpc_error_to_client_error)?;
        let handle = self.engine_handle(&scoped.engine.0)?;
        Ok((handle, scoped.raw_id))
    }

    /// Scope a raw id to an engine (ticket 04's `ScopedId::encode`).
    pub fn scope(&self, engine_id: &str, raw_id: &str) -> String {
        ScopedId::encode(
            &roboco_engine_client::registry::EngineKey(engine_id.to_owned()),
            raw_id,
        )
    }

    /// The per-engine snapshot stream tickets 07/08/09 consume.
    pub fn fleet(&self) -> watch::Receiver<Arc<FleetSnapshot>> {
        self.inner.fleet.subscribe()
    }
}

fn rpc_error_to_client_error(error: roboco_rpc::RpcError) -> ClientError {
    engines::rpc_error_to_client_error(error)
}

/// A handle to one engine (crate-internal callers: the workspace store,
/// the change-request watches).
pub(crate) fn engine_handle_of(
    inner: &Arc<ClientInner>,
    engine_id: &str,
) -> Result<EngineHandle, ClientError> {
    let state = lock(&inner.engines);
    let entry = state
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == engine_id)
        .ok_or_else(|| ClientError::NotFound {
            message: format!("Unknown engine: {engine_id}"),
        })?;
    Ok(EngineHandle::from_parts(Arc::downgrade(inner), entry))
}

/// Adopt a freshly redeemed engine (pairing **or** a room presentation,
/// ticket 04): dial and pin through the redeemed route, dedupe (an
/// explicit replacement, or the same engine re-redeemed under a new
/// link/room — one row per engine), install the entry, and start its
/// supervisor. A failed dial still returns the record: the credential is
/// already minted and cannot be re-minted; the engine shows
/// `Reconnecting` and the first successful connect pins it.
///
/// `room` tags the record with the room a presentation joined it through
/// (ADR 0011) — `None` for a plain pairing. The caller publishes and
/// emits the engines-changed event.
pub(crate) async fn adopt_engine(
    inner: &Arc<ClientInner>,
    redeemed: pairing::Redeemed,
    label: &str,
    room: Option<engines::EngineRoom>,
    replace_engine_id: Option<String>,
) -> PairedEngine {
    // Dial and pin — through the redeemed route, so a Tailcat redeem
    // reuses the tunnel it redeemed through.
    let first_dial = engines::route::DialTarget {
        route: redeemed.route.clone(),
        credential: redeemed.credential.clone(),
    };
    let (initial_feed, device_id) = match first_dial.connect().await {
        Ok(client) => {
            let client = Arc::new(client);
            let info: Option<EngineInfo> = match tokio::time::timeout(
                inner.timings.identity_timeout,
                client.call_as(roboco_rpc::methods::ENGINE_INFO, serde_json::json!({})),
            )
            .await
            {
                Ok(Ok(info)) => Some(info),
                _ => None,
            };
            (Some(client), info.map(|info| info.device_id))
        }
        Err(error) => {
            tracing::warn!(%error, "the redeem dial failed; the engine will reconnect in the background");
            (None, None)
        }
    };
    // Duplicate engines: an explicit replacement, or the same engine
    // re-redeemed — one row per engine (the web's rule: re-pairing an
    // origin replaces its credential).
    let existing_index = {
        let state = lock(&inner.engines);
        match replace_engine_id.as_deref() {
            Some(id) => state
                .entries
                .iter()
                .position(|entry| entry.paired.engine_id == id),
            None => device_id.as_ref().and_then(|device_id| {
                state
                    .entries
                    .iter()
                    .position(|entry| entry.paired.device_id.as_deref() == Some(device_id.as_str()))
            }),
        }
    };
    match existing_index {
        Some(index) => {
            let (engine_id, wipe_cache) = {
                let mut state = lock(&inner.engines);
                let entry = &mut state.entries[index];
                // Keep the cache when the engine identity is unchanged;
                // wipe it when it changed.
                let same_device = entry
                    .paired
                    .device_id
                    .as_ref()
                    .map_or(true, |pinned| Some(pinned) == device_id.as_ref());
                let engine_id = entry.paired.engine_id.clone();
                // Replace the pairing material; the engine id (and so
                // every scoped chat id and the cache directory) stays.
                // A re-pair may switch Direct <-> Tailcat: the old
                // route (and any tunnel) goes.
                entry.paired.base_url = redeemed.base_url.clone();
                entry.paired.transport = redeemed.transport.clone();
                let old_route = std::mem::replace(&mut entry.route, redeemed.route.clone());
                old_route.invalidate();
                entry.paired.credential = redeemed.credential.clone();
                entry.paired.session_id = redeemed.session.id.clone();
                entry.paired.label = label.to_owned();
                entry.paired.device_id = device_id.clone();
                entry.paired.name = None;
                // A plain re-pair (no room) keeps the room the engine was
                // joined through: re-pairing is not leaving the room, and
                // dropping the tag would make the next fleet follow re-adopt
                // it (and the platform sweep the room blob in between).
                if room.is_some() {
                    entry.paired.room = room;
                }
                entry.state = EngineState::Connecting;
                entry.last_error = None;
                entry.retry_at_ms = None;
                (engine_id, !same_device)
            };
            if wipe_cache {
                update_engine(inner, &engine_id, |entry| {
                    let cache = entry.cache.clone();
                    entry.rows = CachedRows::default();
                    entry.sidebar = None;
                    entry.from_cache = true;
                    crate::runtime::shared().spawn_blocking(move || {
                        let _ = cache.forget();
                    });
                });
            }
            // Re-pairing drops the notification driver's state for this
            // engine (baselines, sends, notified states): a re-paired
            // engine re-seeds silently (ticket 28).
            inner.notify.reset_engine(&engine_id);
            // Restart supervision: the old task's channel and token die
            // with it.
            let (old_task, old_cancel) = {
                let mut state = lock(&inner.engines);
                let entry = &mut state.entries[index];
                (
                    std::mem::replace(&mut entry.task, finished_task()),
                    std::mem::replace(&mut entry.cancel, CancellationToken::new()),
                )
            };
            old_cancel.cancel();
            old_task.abort();
            let _ = old_task.await;
            start_supervision(inner, &engine_id, initial_feed);
            lock(&inner.engines).entries[index].paired.clone()
        }
        None => {
            let record = PairedEngine {
                engine_id: uuid::Uuid::new_v4().to_string(),
                base_url: redeemed.base_url.clone(),
                credential: redeemed.credential.clone(),
                session_id: redeemed.session.id.clone(),
                label: label.to_owned(),
                paired_at_ms: engines::now_ms(),
                device_id: device_id.clone(),
                name: None,
                transport: redeemed.transport.clone(),
                room,
            };
            let handle = EngineCacheHandle::new(
                record.engine_id.clone(),
                inner.cache.clone(),
                inner.cache_tx.clone(),
            );
            let engine_id = record.engine_id.clone();
            let mut state = lock(&inner.engines);
            state.entries.push(pending_entry(
                record.clone(),
                redeemed.route.clone(),
                handle,
                CachedRows::default(),
                None,
            ));
            drop(state);
            start_supervision(inner, &engine_id, initial_feed);
            record
        }
    }
}

/// The identity-pin event for an adopted record (the platform persists
/// `device_id` and the display name).
fn pin_event(inner: &Arc<ClientInner>, record: &PairedEngine) {
    if let Some(device_id) = &record.device_id {
        inner.pump.pinned(ClientEvent::EngineIdentityPinned {
            engine_id: record.engine_id.clone(),
            device_id: device_id.clone(),
            name: engines::display_name(
                None,
                record.name.as_deref(),
                &host_of(&record.base_url),
            ),
        });
    }
}

/// A task handle that is already finished — the placeholder before
/// `start_supervision` installs the real supervisor. Aborting it is a
/// no-op and it leaks nothing.
fn finished_task() -> tokio::task::JoinHandle<()> {
    crate::runtime::shared().spawn(async {})
}

/// A fresh engine entry, before supervision starts: cached rows and
/// sidebar loaded (if any), no live frame yet, placeholder task. The
/// command channel and watch slots exist only so the entry is complete
/// before `start_supervision` fills in the real ones.
fn pending_entry(
    paired: PairedEngine,
    route: Arc<engines::route::Route>,
    cache: EngineCacheHandle,
    rows: CachedRows,
    sidebar: Option<roboco_proto::SidebarStateSnapshot>,
) -> EngineEntry {
    let (feed_slot, _) = watch::channel(None);
    let (calls_slot, _) = watch::channel(None);
    let (command_tx, _command_rx) = mpsc::unbounded_channel();
    EngineEntry {
        paired,
        route,
        state: EngineState::Connecting,
        last_error: None,
        retry_at_ms: None,
        last_connected_ms: None,
        proven_ms: None,
        info: None,
        engine_name: None,
        notice: None,
        generation: 0,
        // No live feed frame has arrived yet this process.
        from_cache: true,
        rows,
        sidebar,
        diagnostics: EngineDiagnostics::default(),
        cache,
        feed_slot,
        calls_slot,
        command_tx,
        cancel: CancellationToken::new(),
        task: finished_task(),
    }
}

/// Spawn (or restart) one engine's supervisor.
fn start_supervision(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    initial_feed: Option<Arc<roboco_rpc::RpcClient>>,
) {
    let (command_tx, command_rx) = mpsc::unbounded_channel();
    let cancel = CancellationToken::new();
    let cache = {
        let state = lock(&inner.engines);
        state
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == engine_id)
            .map(|entry| entry.cache.clone())
            .expect("engine entry")
    };
    let ctx = engines::link::EngineCtx {
        weak: Arc::downgrade(inner),
        engine_id: engine_id.to_owned(),
        cancel: cancel.clone(),
        command_rx,
        timings: inner.timings.clone(),
        network: inner.network.subscribe(),
        foreground: inner.foreground.subscribe(),
        cache,
        initial_feed,
        spare_refill: Arc::default(),
    };
    let task = engines::link::supervise(ctx);
    update_engine(inner, engine_id, |entry| {
        entry.command_tx = command_tx;
        entry.cancel = cancel;
        entry.task = task;
    });
}

/// Mutate one engine's shared entry and return the mutation's value (no
/// publish; callers publish when the observable surface changed).
pub(crate) fn mutate_engine<R>(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    f: impl FnOnce(&mut EngineEntry) -> R,
) -> Option<R> {
    let mut state = lock(&inner.engines);
    let entry = state
        .entries
        .iter_mut()
        .find(|entry| entry.paired.engine_id == engine_id)?;
    Some(f(entry))
}

/// Mutate one engine's shared entry (no publish; callers publish when the
/// observable surface changed).
pub(crate) fn update_engine(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    f: impl FnOnce(&mut EngineEntry),
) -> bool {
    mutate_engine(inner, engine_id, f).is_some()
}

/// Rebuild the fleet snapshot and the engine views; emit `EnginesChanged`
/// when any view changed and `ConnectivityChanged` when the aggregate did.
pub(crate) fn publish(inner: &Arc<ClientInner>) {
    let mut state = lock(&inner.engines);
    let views: Vec<EngineView> = state.entries.iter().map(engine_view).collect();
    let fleet = Arc::new(FleetSnapshot {
        engines: state.entries.iter().map(engine_snapshot_of).collect(),
    });
    let connectivity = connectivity_of(inner, &state.entries);
    let engines_changed = views != state.last_views;
    let connectivity_changed = connectivity != state.last_connectivity;
    state.last_views = views;
    state.last_connectivity = connectivity.clone();
    inner.fleet.send_replace(fleet);
    let pump = inner.pump.clone();
    drop(state);
    if connectivity_changed {
        pump.connectivity(connectivity);
    }
    if engines_changed {
        pump.engines_changed();
    }
}

pub(crate) fn engine_view(entry: &EngineEntry) -> EngineView {
    let host = host_of(&entry.paired.base_url);
    let engine_device = entry.paired.device_id.as_ref().and_then(|device_id| {
        entry
            .rows
            .devices
            .iter()
            .find(|device| &device.id == device_id)
    });
    let name = engines::display_name(
        engine_device
            .map(|device| device.name.as_str())
            .or(entry.engine_name.as_deref()),
        entry.paired.name.as_deref(),
        &host,
    );
    let version = entry
        .info
        .as_ref()
        .and_then(|info| info.version.clone())
        .or_else(|| engine_device.and_then(|device| device.version.clone()));
    let capabilities = entry
        .info
        .as_ref()
        .map(|info| info.capabilities.clone())
        .unwrap_or_default();
    EngineView {
        engine_id: entry.paired.engine_id.clone(),
        name,
        host,
        base_url: entry.paired.base_url.clone(),
        transport: entry.paired.transport.kind(),
        device_id: entry.paired.device_id.clone(),
        platform: engine_device.map(|device| device.platform.clone()),
        version,
        state: entry.state.clone(),
        last_error: entry.last_error.clone(),
        retry_at_ms: entry.retry_at_ms,
        last_connected_ms: entry.last_connected_ms,
        capabilities,
        notice: entry.notice.clone(),
        from_cache: entry.from_cache,
        diagnostics: entry.diagnostics.clone(),
        room: entry.paired.room.clone(),
        // Refreshed with every publish; the link's 5 s probe publishes.
        link_path: (entry.state == EngineState::Connected)
            .then(|| entry.route.link_path())
            .flatten(),
    }
}

fn engine_snapshot_of(entry: &EngineEntry) -> engines::EngineSnapshot {
    engines::EngineSnapshot {
        key: entry.paired.engine_id.clone(),
        info: entry.info.clone(),
        state: entry.state.clone(),
        rows: entry.rows.clone(),
        sidebar: entry.sidebar.clone(),
        from_cache: entry.from_cache,
        generation: entry.generation,
        notice: entry.notice.clone(),
        diagnostics: entry.diagnostics.clone(),
    }
}

fn connectivity_of(inner: &ClientInner, entries: &[EngineEntry]) -> Connectivity {
    // Ticket 08: open sessions whose engine is not connected. The engine
    // table is already locked here (publish holds it), so the states are
    // read straight off `entries` — never through `engine_handle` (the
    // engines lock is not reentrant).
    let degraded_chats: Vec<String> = inner
        .sessions
        .ids_with_engines()
        .into_iter()
        .filter(|(_chat_id, engine_id)| {
            entries
                .iter()
                .find(|entry| entry.paired.engine_id == engine_id.as_str())
                .is_none_or(|entry| entry.state != EngineState::Connected)
        })
        .map(|(chat_id, _)| chat_id)
        .collect();
    if entries.is_empty() {
        return Connectivity {
            state: ConnectivityState::Disabled,
            retry_at_ms: None,
            last_failure: None,
            degraded_chats,
        };
    }
    if !*inner.network.borrow() {
        return Connectivity {
            state: ConnectivityState::Offline,
            retry_at_ms: None,
            last_failure: None,
            degraded_chats,
        };
    }
    let non_parked: Vec<&EngineEntry> = entries
        .iter()
        .filter(|entry| !matches!(entry.state, EngineState::Parked { .. }))
        .collect();
    if non_parked
        .iter()
        .any(|entry| entry.state != EngineState::Connected)
    {
        let retry_at_ms = non_parked
            .iter()
            .filter_map(|entry| entry.retry_at_ms)
            .min();
        let last_failure = non_parked
            .iter()
            .filter(|entry| entry.state != EngineState::Connected)
            .find_map(|entry| entry.last_error.clone());
        return Connectivity {
            state: ConnectivityState::Reconnecting,
            retry_at_ms,
            last_failure,
            degraded_chats,
        };
    }
    Connectivity {
        state: ConnectivityState::Connected,
        retry_at_ms: None,
        last_failure: None,
        degraded_chats,
    }
}

/// A listener that drops every event (tests, headless tools).
pub use events::NullListener;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_client_with_no_engines_is_disabled_and_silent() {
        let dir = tempfile::tempdir().unwrap();
        let events = Arc::new(std::sync::Mutex::new(Vec::new()));
        let seen = events.clone();
        struct Recorder(Arc<Mutex<Vec<ClientEvent>>>);
        impl ClientListener for Recorder {
            fn on_event(&self, event: ClientEvent) {
                self.0.lock().unwrap().push(event);
            }
        }
        let client = Client::new(
            ClientConfig {
                data_dir: dir.path().to_owned(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            Arc::new(Recorder(seen)),
        )
        .unwrap();
        assert!(client.engines().is_empty());
        assert_eq!(client.connectivity().state, ConnectivityState::Disabled);
        std::thread::sleep(Duration::from_millis(100));
        // No engines → no events (not even an initial ConnectivityChanged).
        assert!(events.lock().unwrap().is_empty());
        client.shutdown();
    }

    /// An opener whose tunnels never come up (android-next#02).
    struct DeadOpener(std::sync::atomic::AtomicUsize);

    impl tailcat::TunnelOpener for DeadOpener {
        fn open(
            &self,
            _address: &str,
            _client_state: &str,
        ) -> Result<tailcat::OpenedTunnel, tailcat::TailcatError> {
            self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Err(tailcat::TailcatError::Unreachable(
                "Tailcat client startup failed".into(),
            ))
        }
    }

    fn tailcat_record(credential: &str) -> PairedEngine {
        PairedEngine {
            engine_id: "e-tailcat".into(),
            base_url: tailcat::display_handle("tcSECRETaddress"),
            credential: credential.into(),
            session_id: "s".into(),
            label: "Pixel 9".into(),
            paired_at_ms: 0,
            device_id: None,
            name: None,
            transport: EngineTransport::Tailcat {
                address: "tcSECRETaddress".into(),
                client_state: "state".into(),
            },
            room: None,
        }
    }

    fn tailcat_client(
        dir: &std::path::Path,
        record: PairedEngine,
        opener: Arc<DeadOpener>,
    ) -> Arc<Client> {
        Client::new_with_opener(
            ClientConfig {
                data_dir: dir.to_owned(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            vec![record],
            Vec::new(),
            Arc::new(NullListener),
            Timings::default(),
            opener,
        )
        .unwrap()
    }

    fn wait_for_engine(client: &Client, done: impl Fn(&EngineView) -> bool) -> EngineView {
        for _ in 0..100 {
            if let Some(view) = client.engines().into_iter().find(|view| done(view)) {
                return view;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        panic!("engine never reached the state: {:?}", client.engines());
    }

    #[test]
    fn a_tailcat_route_error_reconnects_and_never_parks() {
        let dir = tempfile::tempdir().unwrap();
        let opener = Arc::new(DeadOpener(Default::default()));
        let client = tailcat_client(dir.path(), tailcat_record("credential"), opener.clone());
        let view = wait_for_engine(&client, |view| view.state == EngineState::Reconnecting);
        assert!(!matches!(view.state, EngineState::Parked { .. }));
        assert_eq!(view.transport, TransportKind::Tailcat);
        let error = view.last_error.unwrap_or_default();
        assert!(error.contains("Tailcat"), "{error}");
        assert!(!error.contains("SECRET"), "{error}");
        assert!(!view.base_url.contains("SECRET"));
        assert!(opener.0.load(std::sync::atomic::Ordering::SeqCst) >= 1);
        client.shutdown();
    }

    #[test]
    fn a_tailcat_record_without_its_credential_parks_without_opening() {
        let dir = tempfile::tempdir().unwrap();
        let opener = Arc::new(DeadOpener(Default::default()));
        let client = tailcat_client(dir.path(), tailcat_record(""), opener.clone());
        wait_for_engine(&client, |view| {
            view.state
                == EngineState::Parked {
                    reason: ParkReason::Revoked,
                }
        });
        assert_eq!(opener.0.load(std::sync::atomic::Ordering::SeqCst), 0);
        client.shutdown();
    }
}
