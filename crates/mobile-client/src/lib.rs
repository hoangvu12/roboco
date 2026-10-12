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
//! Pure Rust + tokio, UniFFI-free, so `cargo test` runs on any host.
//! `roboco-mobile` wraps this crate in `crates/mobile/src/client_ffi`.

pub mod cache;
pub mod clock;
pub mod compat;
pub mod engines;
pub mod events;
pub mod lenient;
pub mod pairing;
pub mod runtime;
pub mod timings;

use cache::{CacheCommand, CacheWriter, EngineCacheHandle};
use engines::{EngineCommand, EngineEntry, host_of};
use events::EventPump;

use roboco_engine_client::cache::{CachedRows, EngineCache};
use roboco_engine_client::registry::ScopedId;
use roboco_proto::EngineInfo;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;
use timings::Timings;
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

pub use engines::{
    Connectivity, ConnectivityState, EngineDiagnostics, EngineHandle, EngineSnapshot, EngineState,
    EngineView, FeedItem, FeedWatch, FleetSnapshot, ParkReason, PairedEngine,
    TranscriptConnection,
};
pub use events::{ClientEvent, ClientListener};
pub use pairing::{PairError, PairingTarget, parse_pairing_url};

pub(crate) fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
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
    #[error("not implemented: {message}")]
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
    /// Cancels the event pump on `shutdown`.
    client_cancel: CancellationToken,
    /// Client-wide network flag (`true` = online). `false` only for a
    /// definitive OS "no network"; ambiguity counts as online.
    network: watch::Sender<bool>,
    /// Client-wide foreground flag (drives the spare policy).
    foreground: watch::Sender<bool>,
    cache_tx: mpsc::UnboundedSender<CacheCommand>,
    cache: EngineCache,
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
    pub fn new(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        listener: Arc<dyn ClientListener>,
    ) -> Result<Arc<Self>, ClientError> {
        Self::new_with_timings(config, engines, listener, Timings::default())
    }

    /// Test seam: every supervisor timing in one place, so liveness
    /// behaviour runs in seconds.
    pub fn new_with_timings(
        config: ClientConfig,
        engines: Vec<PairedEngine>,
        listener: Arc<dyn ClientListener>,
        timings: Timings,
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
                crate::runtime::shared().spawn_blocking(move || {
                    crate::cache::prune_transcripts(&cache, &engine_id)
                });
            }
            let (feed_slot, _) = watch::channel(None);
            let (calls_slot, _) = watch::channel(None);
            entries.push(EngineEntry {
                paired,
                state: EngineState::Connecting,
                last_error: None,
                retry_at_ms: None,
                last_connected_ms: None,
                info: None,
                engine_name: None,
                notice: None,
                generation: 0,
                // No live feed frame has arrived yet this process.
                from_cache: true,
                rows,
                sidebar,
                diagnostics: EngineDiagnostics::default(),
                cache: handle,
                feed_slot,
                calls_slot,
                command_tx: {
                    let (tx, _rx) = mpsc::unbounded_channel();
                    tx
                },
                cancel: CancellationToken::new(),
                task: finished_task(),
            });
        }
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
            client_cancel,
            network,
            foreground,
            cache_tx,
            cache,
        });
        let ids: Vec<String> = lock(&inner.engines)
            .entries
            .iter()
            .map(|entry| entry.paired.engine_id.clone())
            .collect();
        for engine_id in ids {
            start_supervision(&inner, &engine_id, None);
        }
        publish(&inner);
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
        let redeemed =
            pairing::redeem_pairing(pairing_url, &self.inner.config.device_name).await?;
        let label = pairing::sanitize_label(&self.inner.config.device_name);
        // Dial and pin. A failed dial still returns the record: the
        // credential is already minted and single-use codes cannot be
        // re-redeemed; the engine shows `Reconnecting` and the first
        // successful connect pins it.
        let (initial_feed, device_id) =
            match roboco_rpc::connect_ws_authenticated(&redeemed.endpoint, &redeemed.credential)
                .await
            {
                Ok(client) => {
                    let client = Arc::new(client);
                    let info: Option<EngineInfo> = match tokio::time::timeout(
                        Duration::from_secs(10),
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
                    tracing::warn!(%error, "pairing dial failed; engine will reconnect in the background");
                    (None, None)
                }
            };
        // Duplicate engines: an explicit replacement, or the same engine
        // re-paired under a new link — one row per engine (the web's rule:
        // re-pairing an origin replaces its credential).
        let existing_index = {
            let state = lock(&self.inner.engines);
            match replace_engine_id.as_deref() {
                Some(id) => match state
                    .entries
                    .iter()
                    .position(|entry| entry.paired.engine_id == id)
                {
                    Some(index) => Some(index),
                    None => {
                        return Err(PairError::Failed {
                            message: format!("Unknown engine to replace: {id}"),
                        })
                    }
                },
                None => device_id.as_ref().and_then(|device_id| {
                    state.entries.iter().position(|entry| {
                        entry.paired.device_id.as_deref() == Some(device_id.as_str())
                    })
                }),
            }
        };
        let record = match existing_index {
            Some(index) => {
                let (engine_id, wipe_cache) = {
                    let mut state = lock(&self.inner.engines);
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
                    entry.paired.base_url = redeemed.base_url.clone();
                    entry.paired.credential = redeemed.credential.clone();
                    entry.paired.session_id = redeemed.session.id.clone();
                    entry.paired.label = label.clone();
                    entry.paired.device_id = device_id.clone();
                    entry.paired.name = None;
                    entry.state = EngineState::Connecting;
                    entry.last_error = None;
                    entry.retry_at_ms = None;
                    (engine_id, !same_device)
                };
                if wipe_cache {
                    update_engine(&self.inner, &engine_id, |entry| {
                        let cache = entry.cache.clone();
                        entry.rows = CachedRows::default();
                        entry.sidebar = None;
                        entry.from_cache = true;
                        crate::runtime::shared().spawn_blocking(move || {
                            let _ = cache.forget();
                        });
                    });
                }
                // Restart supervision: the old task's channel and token die
                // with it.
                let (old_task, old_cancel) = {
                    let mut state = lock(&self.inner.engines);
                    let entry = &mut state.entries[index];
                    (
                        std::mem::replace(&mut entry.task, finished_task()),
                        std::mem::replace(&mut entry.cancel, CancellationToken::new()),
                    )
                };
                old_cancel.cancel();
                old_task.abort();
                let _ = old_task.await;
                start_supervision(&self.inner, &engine_id, initial_feed);
                lock(&self.inner.engines)
                    .entries[index]
                    .paired
                    .clone()
            }
            None => {
                let record = PairedEngine {
                    engine_id: uuid::Uuid::new_v4().to_string(),
                    base_url: redeemed.base_url.clone(),
                    credential: redeemed.credential.clone(),
                    session_id: redeemed.session.id.clone(),
                    label: label.clone(),
                    paired_at_ms: engines::now_ms(),
                    device_id: device_id.clone(),
                    name: None,
                };
                let (feed_slot, _) = watch::channel(None);
                let (calls_slot, _) = watch::channel(None);
                let handle = EngineCacheHandle::new(
                    record.engine_id.clone(),
                    self.inner.cache.clone(),
                    self.inner.cache_tx.clone(),
                );
                let engine_id = record.engine_id.clone();
                let mut state = lock(&self.inner.engines);
                state.entries.push(EngineEntry {
                    paired: record.clone(),
                    state: EngineState::Connecting,
                    last_error: None,
                    retry_at_ms: None,
                    last_connected_ms: None,
                    info: None,
                    engine_name: None,
                    notice: None,
                    generation: 0,
                    from_cache: true,
                    rows: CachedRows::default(),
                    sidebar: None,
                    diagnostics: EngineDiagnostics::default(),
                    cache: handle,
                    feed_slot,
                    calls_slot,
                    command_tx: {
                        let (tx, _rx) = mpsc::unbounded_channel();
                        tx
                    },
                    cancel: CancellationToken::new(),
                    task: finished_task(),
                });
                drop(state);
                start_supervision(&self.inner, &engine_id, initial_feed);
                record
            }
        };
        if let Some(device_id) = &record.device_id {
            self.inner.pump.pinned(ClientEvent::EngineIdentityPinned {
                engine_id: record.engine_id.clone(),
                device_id: device_id.clone(),
                name: engines::display_name(None, &record.label, &host_of(&record.base_url)),
            });
        }
        publish(&self.inner);
        self.inner.pump.engines_changed();
        Ok(record)
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
        let _ = removed.task.await;
        // No new write can register once the engine task is cancelled;
        // joining the drained writes keeps an in-flight save from
        // recreating the directory being removed.
        removed.cache.flush().await;
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
    /// `forget_engine`; failures are logged, never fatal.
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
        match handle
            .call(
                roboco_rpc::methods::REVOKE_PAIRING_SESSION,
                serde_json::json!({"sessionId": session_id}),
            )
            .await
        {
            Ok(_) | Err(ClientError::Unsupported { .. }) => Ok(()),
            Err(error) => {
                tracing::warn!(%error, engine_id, "session revoke failed (best-effort)");
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
                    let _ = entry.command_tx.send(EngineCommand::DialNow);
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
                        let _ = entry.command_tx.send(EngineCommand::DialNow);
                    }
                    let _ = entry.command_tx.send(EngineCommand::Refresh(ack));
                    receipt
                })
                .collect()
        };
        let cap = self.inner.timings.refresh_cap;
        for receipt in acks {
            let _ = tokio::time::timeout(cap, receipt).await;
        }
    }

    /// The aggregate connectivity posture.
    pub fn connectivity(&self) -> Connectivity {
        lock(&self.inner.engines).last_connectivity.clone()
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /// OS network path: `false` only for a definitive "no network" —
    /// every non-parked engine becomes `Offline`, pending dials are
    /// cancelled, and no dial attempts run. `true` resets the backoff and
    /// dials every engine now.
    pub fn set_network_online(&self, online: bool) {
        self.inner.network.send_replace(online);
        if online {
            let state = lock(&self.inner.engines);
            for entry in &state.entries {
                let _ = entry.command_tx.send(EngineCommand::DialNow);
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

    /// The app is foreground: probe every connected feed immediately (this
    /// catches sockets Doze killed) and dial everything else now.
    /// Re-open spares.
    pub fn on_foreground(&self) {
        self.inner.foreground.send_replace(true);
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            let _ = entry.command_tx.send(EngineCommand::Foreground);
            if !matches!(entry.state, EngineState::Connected | EngineState::Parked { .. }) {
                let _ = entry.command_tx.send(EngineCommand::DialNow);
            }
        }
    }

    /// The app is backgrounding: flush every pending cache write (bounded
    /// at 2 s), then close spares. Feed and calls connections stay.
    pub fn on_background(&self) {
        self.inner.foreground.send_replace(false);
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
    }

    /// Stop all tasks (sign-out / app teardown). Snapshots stay readable.
    pub fn shutdown(&self) {
        self.inner.client_cancel.cancel();
        let state = lock(&self.inner.engines);
        for entry in &state.entries {
            entry.cancel.cancel();
            entry.task.abort();
        }
    }

    // ── internal routing API (tickets 07/08/09) ──────────────────────────

    /// A handle to one engine.
    pub fn engine_handle(&self, engine_id: &str) -> Result<EngineHandle, ClientError> {
        let inner = self.inner.clone();
        let handle = {
            let state = lock(&inner.engines);
            let entry = state
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == engine_id)
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown engine: {engine_id}"),
                })?;
            EngineHandle::from_parts(Arc::downgrade(&inner), entry)
        };
        Ok(handle)
    }

    /// Resolve a scoped id to its engine and the engine's raw id.
    pub fn resolve(&self, scoped_id: &str) -> Result<(EngineHandle, String), ClientError> {
        let scoped = ScopedId::parse(scoped_id).map_err(rpc_error_to_client_error)?;
        let handle = self.engine_handle(&scoped.engine.0)?;
        Ok((handle, scoped.raw_id))
    }

    /// Scope a raw id to an engine (ticket 04's `ScopedId::encode`).
    pub fn scope(&self, engine_id: &str, raw_id: &str) -> String {
        ScopedId::encode(&roboco_engine_client::registry::EngineKey(engine_id.to_owned()), raw_id)
    }

    /// The per-engine snapshot stream tickets 07/08/09 consume.
    pub fn fleet(&self) -> watch::Receiver<Arc<FleetSnapshot>> {
        self.inner.fleet.subscribe()
    }
}

fn rpc_error_to_client_error(error: roboco_rpc::RpcError) -> ClientError {
    engines::rpc_error_to_client_error(error)
}

/// A task handle that is already finished — the placeholder before
/// `start_supervision` installs the real supervisor. Aborting it is a
/// no-op and it leaks nothing.
fn finished_task() -> tokio::task::JoinHandle<()> {
    crate::runtime::shared().spawn(async {})
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
        engines: state
            .entries
            .iter()
            .map(engine_snapshot_of)
            .collect(),
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

fn engine_view(entry: &EngineEntry) -> EngineView {
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
        &entry.paired.label,
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
        device_id: entry.paired.device_id.clone(),
        platform: engine_device.map(|device| device.platform.clone()),
        version,
        state: entry.state.clone(),
        last_error: entry.last_error.clone(),
        retry_at_ms: entry.retry_at_ms.clone(),
        last_connected_ms: entry.last_connected_ms.clone(),
        capabilities,
        notice: entry.notice.clone(),
        from_cache: entry.from_cache,
        diagnostics: entry.diagnostics.clone(),
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
    if entries.is_empty() {
        return Connectivity {
            state: ConnectivityState::Disabled,
            retry_at_ms: None,
            last_failure: None,
            degraded_chats: Vec::new(),
        };
    }
    if !*inner.network.borrow() {
        return Connectivity {
            state: ConnectivityState::Offline,
            retry_at_ms: None,
            last_failure: None,
            degraded_chats: Vec::new(),
        };
    }
    let non_parked: Vec<&EngineEntry> = entries
        .iter()
        .filter(|entry| !matches!(entry.state, EngineState::Parked { .. }))
        .collect();
    if non_parked.iter().any(|entry| entry.state != EngineState::Connected) {
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
            degraded_chats: Vec::new(),
        };
    }
    Connectivity {
        state: ConnectivityState::Connected,
        retry_at_ms: None,
        last_failure: None,
        degraded_chats: Vec::new(),
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
            },
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
}
