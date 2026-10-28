//! The desktop/phone manager around the shared client preview proxy
//! (ticket 04): registry-backed relay dialing, `WatchPreviews`-driven
//! ingestion, and the watcher lifecycle that keeps the 7332 listener bound
//! only while an opened remote preview's server runs (the desktop shell
//! holds the watchers, ADR 0012).
//!
//! The GPUI-free proxy itself lives in `roboco-preview-relay` (linked by
//! this crate, so the desktop app and the Android mobile core share it).
//! This module is the glue every registry-holding client needs: it dials
//! each engine's `/preview-relay` route over the *same* endpoint and session
//! credential the RPC socket uses — the registry's saved record, a LAN
//! address or a Tailcat tunnel's loopback base. Local previews keep their
//! direct 7331 path.

use std::{collections::HashSet, path::PathBuf, sync::Arc, time::Duration};

use roboco_preview_relay::client::{ClientProxy, ClientProxyOptions, RelayDialer};
use roboco_proto::PreviewService;
use tokio_util::sync::CancellationToken;

use crate::registry::{EngineKey, EngineRegistry};

/// The manager's published state: the shared proxy's listener status.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PreviewProxyStatus {
    /// The bound 7332-family port while the listener runs.
    pub port: Option<u16>,
    /// A bind failure surfaced inline (a busy port), mirroring the engine's
    /// 7331 failure mode. Never a URL change: the rendered port stays
    /// `PREVIEW_CLIENT_PROXY_PORT` either way.
    pub error: Option<String>,
}

/// A handle to the app's client preview proxy. Cheap to clone; one instance
/// per client process.
pub struct PreviewProxy {
    proxy: ClientProxy,
    registry: EngineRegistry,
    idle: Duration,
    stop: CancellationToken,
}

impl PreviewProxy {
    /// Start the manager. Nothing binds until the first engine's previews
    /// are watched. `labels_file` persists the hostname labels assigned to
    /// colliding remote device names so URLs survive restarts.
    pub fn start(
        registry: EngineRegistry,
        labels_file: Option<PathBuf>,
        idle: Duration,
    ) -> Arc<Self> {
        Self::start_on_port(
            registry,
            labels_file,
            idle,
            ClientProxyOptions::default().port,
        )
    }

    /// [`Self::start`] on another listener port; tests pass `0` for an
    /// ephemeral one, so a running Roboco holding 7332 cannot collide.
    pub fn start_on_port(
        registry: EngineRegistry,
        labels_file: Option<PathBuf>,
        idle: Duration,
        port: u16,
    ) -> Arc<Self> {
        let proxy = ClientProxy::new(
            Arc::new(RegistryDialer(registry.clone())),
            ClientProxyOptions {
                port,
                labels_file,
                idle,
            },
            registry.runtime_handle(),
        );
        let stop = CancellationToken::new();
        // Registry hygiene: an engine that was forgotten stops routing — its
        // hostnames must not answer (and its dials would fail anyway).
        let removed = proxy.clone();
        let mut registry_updates = registry.watch();
        let token = stop.clone();
        registry.runtime_handle().spawn(async move {
            loop {
                let snapshot = registry_updates.borrow_and_update().clone();
                let live: HashSet<String> = snapshot
                    .engines
                    .into_iter()
                    .map(|engine| engine.key.0)
                    .collect();
                {
                    let engines = removed.engines();
                    for engine in engines {
                        if !live.contains(&engine) {
                            removed.remove_engine(&engine);
                        }
                    }
                }
                tokio::select! {
                    _ = token.cancelled() => return,
                    changed = registry_updates.changed() => {
                        if changed.is_err() {
                            return;
                        }
                    }
                }
            }
        });
        Arc::new(Self {
            proxy,
            registry,
            idle,
            stop,
        })
    }

    /// How long the listener outlives its last watcher.
    pub fn idle(&self) -> Duration {
        self.idle
    }

    /// Mark an engine's previews as viewed (or not). The first watcher
    /// lazily binds the listener; after the last one leaves, the listener
    /// shuts down once the idle window passes.
    pub fn watch(&self, engine: &EngineKey, watching: bool) {
        self.proxy.watch_engine(&engine.0, watching);
    }

    /// Ingest one remote engine's `WatchPreviews` services. Returns them
    /// with their effective hostnames (a colliding device name gets a
    /// persistent distinct label). The pane renders these, not the raw
    /// snapshot, so the URL shown is the URL that routes.
    pub fn ingest(
        &self,
        engine: &EngineKey,
        services: Vec<PreviewService>,
    ) -> anyhow::Result<Vec<PreviewService>> {
        self.proxy.ingest(&engine.0, services)
    }

    /// The services one engine currently contributes, effective hostnames.
    pub fn effective_services(&self, engine: &EngineKey) -> Vec<PreviewService> {
        self.proxy.effective_services(&engine.0)
    }

    /// Look a hostname up in the routing table (whose service answers it).
    pub fn by_hostname(&self, hostname: &str) -> Option<PreviewService> {
        self.proxy.by_hostname(hostname)
    }

    /// The current listener status.
    pub fn status(&self) -> PreviewProxyStatus {
        let client = self.proxy.status();
        PreviewProxyStatus {
            port: client.port,
            error: client.error,
        }
    }

    /// Subscribe to listener status changes.
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<roboco_preview_relay::client::ClientStatus> {
        self.proxy.subscribe()
    }

    /// Whether the local engine is this registry's only engine — a fast
    /// answer for callers deciding whether a proxy is worth starting.
    pub fn has_remote_engines(&self) -> bool {
        self.registry
            .snapshot()
            .engines
            .iter()
            .any(|engine| !engine.key.is_local())
    }

    /// Stop serving, drop every relay, and end the hygiene task.
    pub async fn stop(&self) {
        self.stop.cancel();
        self.proxy.stop().await;
    }
}

/// Dials an engine's relay over the transport the client already holds: the
/// registry entry's saved endpoint (LAN `ws://` address, or a tunnel's
/// loopback base) and session credential — exactly what the supervise loop
/// redials the RPC socket with.
struct RegistryDialer(EngineRegistry);

#[async_trait::async_trait]
impl RelayDialer for RegistryDialer {
    async fn dial(
        &self,
        engine: &str,
        chat: &str,
        service: &str,
    ) -> anyhow::Result<Box<dyn roboco_preview_relay::mux::Transport>> {
        let key = EngineKey(engine.to_owned());
        let dial = self
            .0
            .relay_dial(&key)
            .ok_or_else(|| anyhow::anyhow!("engine is not paired for remote previews"))?;
        let stream = roboco_preview_relay::client::dial_relay(
            &dial.endpoint,
            Some(&dial.credential),
            chat,
            service,
        )
        .await?;
        Ok(Box::new(stream))
    }
}
