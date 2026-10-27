//! The phone's preview surface (agent-previews Phase C, ticket 05): the
//! per-chat `WatchPreviews` subscription plus ticket 04's client-side
//! preview proxy, both running inside the mobile core.
//!
//! What this module adds on top of the shared proxy
//! (`roboco-preview-relay`, linked by the desktop and the phone alike):
//!
//! - **The panel state machine** ([`PreviewPanel`]): one
//!   `WatchPreviews {chatId}` subscription per open panel, mirroring the
//!   desktop's per-browser-tab loop (`ui/src/browser/mod.rs`
//!   `watch_previews`) and the web's `state/preview.ts` shape — a first
//!   frame flips `Loading` to `Live`, a stream that ends mid-connection
//!   clears the rows and shows the reconnect state ("Connecting to
//!   preview discovery…", exactly the desktop's wording), and the
//!   subscription re-runs after a 2 s beat. Engine supervision supplies
//!   the reconnect redial; an engine too old to serve the method degrades
//!   once ([`PanelPhase::Unsupported`]) instead of polling forever.
//! - **The dialer** ([`MobileDialer`]): every relay dial asks the
//!   engine's [`crate::engines::route`] for its endpoint — the LAN
//!   `ws://` address, or a Tailcat tunnel's loopback base opened on
//!   demand — and carries the paired session credential in the
//!   `Authorization` header. Preview reachability therefore follows the
//!   existing transport exactly; no new pairing, no new network setup.
//! - **The lifecycle**: the proxy's loopback listener binds only while a
//!   panel (or an open browser entry) watches that engine, and a
//!   forgotten engine's routes are dropped, mirroring the desktop's
//!   registry hygiene.
//!
//! The FFI layer wraps this in `crates/mobile/src/client_ffi/previews.rs`;
//! this crate stays UniFFI-free.

use std::{
    path::Path,
    sync::{Arc, Mutex, Weak},
    time::Duration,
};

use roboco_preview_relay::client::{ClientProxy, ClientProxyOptions, ClientStatus, RelayDialer};
use roboco_proto::{PreviewService, PreviewSnapshot, PREVIEW_CLIENT_PROXY_PORT};
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

use crate::{ClientError, ClientInner, engines::EngineState, lock};

/// The desktop's resubscribe beat (browser/mod.rs `watch_previews`,
/// `state/preview.ts` `RESUBSCRIBE_MS`).
const RESUBSCRIBE: Duration = Duration::from_secs(2);

/// The stable port the panel's rows open — `http://{hostname}:7332` (the
/// Android WebView's Chromium synthesizes `*.localhost` to loopback
/// in-process, so no OS resolver is involved). The shared proxy's own
/// constant, re-stated for the FFI docs to point at.
pub const PANEL_PROXY_PORT: u16 = PREVIEW_CLIENT_PROXY_PORT;

/// The default idle window before the listener unwinds after the last
/// watcher leaves (tests shrink it; production keeps the shared default).
pub const DEFAULT_IDLE: Duration = Duration::from_secs(30);

/// The panel's published state — the `state/preview.ts` shape: a snapshot
/// with effective hostnames, an error to surface, or the reconnecting /
/// unsupported / loading phase with the rows cleared.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PanelState {
    pub phase: PanelPhase,
    /// The services with their *effective* hostnames — what the proxy
    /// actually routes, and what the panel's rows open.
    pub services: Vec<PreviewService>,
    /// The inline error (a bind failure, an ingest refusal, or the
    /// snapshot's own `error`); `None` while healthy.
    pub error: Option<String>,
}

impl PanelState {
    fn loading() -> Self {
        Self {
            phase: PanelPhase::Loading,
            services: Vec::new(),
            error: None,
        }
    }
}

/// The panel's phase. `Reconnecting` is the desktop's reconnect state:
/// the engine session is offline, revoked or re-dialing, and the relayed
/// previews went with it — the panel keeps watching and heals on its own.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum PanelPhase {
    #[default]
    Loading,
    /// A snapshot is on screen (the rows may still be empty — that is the
    /// honest empty state, not a degraded panel).
    Live,
    Reconnecting,
    /// The engine predates previews; degrade once, never poll it forever.
    Unsupported,
}

/// One client's preview hub: the shared client proxy plus the
/// per-chat panels. Installed on [`ClientInner`] right after
/// construction (it needs a `Weak` back-reference, like the workspace
/// store) and reached through [`crate::Client::watch_previews`].
pub(crate) struct PreviewsHub {
    proxy: ClientProxy,
}

impl PreviewsHub {
    /// Build the hub. Nothing binds until the first watcher arrives (the
    /// shared proxy's own lazy start). `port` is 7332 in production and an
    /// ephemeral port in tests; `labels_file` persists the hostname labels
    /// assigned to colliding remote device names so preview URLs survive
    /// app restarts.
    pub(crate) fn start(
        weak: Weak<ClientInner>,
        data_dir: &Path,
        port: u16,
        idle: Duration,
    ) -> Arc<Self> {
        let proxy = ClientProxy::new(
            Arc::new(MobileDialer { weak: weak.clone() }),
            ClientProxyOptions {
                port,
                idle,
                labels_file: Some(data_dir.join("preview-relay-labels.json")),
            },
            crate::runtime::handle(),
        );
        // Registry hygiene, the desktop's twin: an engine that was
        // forgotten stops routing — its hostnames must not answer, and
        // its dials would fail anyway.
        let removed = proxy.clone();
        let mut fleet = weak
            .upgrade()
            .map(|inner| inner.fleet.subscribe())
            .expect("the hub starts while the client is alive");
        crate::runtime::shared().spawn(async move {
            loop {
                let live: Vec<String> = fleet
                    .borrow_and_update()
                    .engines
                    .iter()
                    .map(|engine| engine.key.clone())
                    .collect();
                for engine in removed.engines() {
                    if !live.contains(&engine) {
                        removed.remove_engine(&engine);
                    }
                }
                if fleet.changed().await.is_err() {
                    return; // The client is gone.
                }
            }
        });
        Arc::new(Self { proxy })
    }

    /// Mark an engine's previews as viewed (or not) — the listener's lazy
    /// start / idle shutdown. The panel does this on open/close; the
    /// browser entry holds its own watcher while it is on screen.
    pub(crate) fn watch_engine(&self, engine: &str, watching: bool) {
        self.proxy.watch_engine(engine, watching);
    }

    /// The listener status (bound port, bind failure).
    pub(crate) fn status(&self) -> ClientStatus {
        self.proxy.status()
    }
}

// ── the panel ─────────────────────────────────────────────────────────────

/// The shared panel core; the state machine task, the FFI object and the
/// drop path all hold this. `Drop` releases the watcher (the engine stops
/// keeping the listener bound) and ends the state machine, so even a
/// leaked panel eventually lets the proxy idle out.
struct PanelCore {
    engine_id: String,
    /// The **scoped** chat id (the id the app holds); the wire params are
    /// routed (the scoped id decoded to the engine's raw id).
    chat_id: String,
    proxy: ClientProxy,
    cancel: CancellationToken,
    states: Mutex<Option<tokio::sync::watch::Sender<Arc<PanelState>>>>,
}

impl Drop for PanelCore {
    fn drop(&mut self) {
        self.cancel.cancel();
        lock(&self.states).take();
        self.proxy.watch_engine(&self.engine_id, false);
    }
}

/// A live per-chat preview panel: the subscription state machine plus a
/// cheap read of its latest state. Create through
/// [`crate::Client::watch_previews`]; closing stops the watch (the FFI
/// object's `close()`, or dropping the last handle).
pub struct PreviewPanel {
    core: Arc<PanelCore>,
    states: tokio::sync::watch::Receiver<Arc<PanelState>>,
}

impl PreviewPanel {
    /// The latest state.
    pub fn state(&self) -> PanelState {
        (**self.states.borrow()).clone()
    }

    /// Subscribe to state changes (the FFI bridge's input). The receiver
    /// ends when the panel closes or the client drops.
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<Arc<PanelState>> {
        self.states.clone()
    }

    /// The engine whose previews this panel watches (the caller's engine
    /// id, stable across re-pairs of the same engine record).
    pub fn engine_id(&self) -> &str {
        &self.core.engine_id
    }

    /// Stop watching: the state machine ends, the engine's watcher is
    /// released, and the state stream closes. Idempotent.
    pub fn close(&self) {
        self.core.cancel.cancel();
        lock(&self.core.states).take();
        self.core.proxy.watch_engine(&self.core.engine_id, false);
    }
}

/// Start one panel's state machine. Publishing the initial `Loading`
/// state synchronously means `state()` never reads a default.
pub(crate) fn start_panel(inner: &Arc<ClientInner>, engine_id: &str, chat_id: &str) -> PreviewPanel {
    let (states, receiver) = tokio::sync::watch::channel(Arc::new(PanelState::loading()));
    let core = Arc::new(PanelCore {
        engine_id: engine_id.to_owned(),
        chat_id: chat_id.to_owned(),
        proxy: inner
            .previews
            .get()
            .expect("the preview hub starts with the client")
            .proxy
            .clone(),
        cancel: CancellationToken::new(),
        states: Mutex::new(Some(states)),
    });
    // The panel keeps the listener bound while it is open — the desktop's
    // watcher is the browser pane's lifetime; here it is the panel's (and
    // the browser entry holds its own while it is on top).
    core.proxy.watch_engine(engine_id, true);
    let weak = Arc::downgrade(inner);
    let task_core = core.clone();
    crate::runtime::shared().spawn(async move {
        run(task_core, weak).await;
    });
    PreviewPanel {
        core,
        states: receiver,
    }
}

/// The state machine: subscribe → items → resubscribe after 2 s, with the
/// engine's own state driving the reconnect phase. Holds the client only
/// weakly (a dropped client ends the panel); the fleet watch and the
/// subscription's socket both fail closed when the engine goes away.
async fn run(core: Arc<PanelCore>, weak: Weak<ClientInner>) {
    // One fleet subscription for the whole panel run: engine state changes
    // (offline, revoked, reconnected) drive the reconnect phase and end a
    // live subscription early.
    let mut fleet: Option<watch::Receiver<Arc<crate::engines::FleetSnapshot>>> = None;    loop {
        if core.cancel.is_cancelled() {
            return;
        }
        let Some(inner) = weak.upgrade() else {
            return;
        };
        let fleet_rx = fleet.get_or_insert_with(|| inner.fleet.subscribe());
        let handle = match crate::engine_handle_of(&inner, &core.engine_id) {
            Ok(handle) => handle,
            Err(_) => {
                // Unknown engine (unpaired mid-view): the honest note, not
                // an error surface.
                publish(&core, reconnect_state());
                if !wait(&core, &inner).await {
                    return;
                }
                continue;
            }
        };
        match handle.state() {
            EngineState::Connected => {}
            EngineState::Parked { .. } => {
                // A revoked or re-paired session: the previews went with
                // it, exactly like the chat degrades — reconnect state.
                publish(&core, reconnect_state());
                if !wait(&core, &inner).await {
                    return;
                }
                continue;
            }
            _ => {
                // Offline / connecting / re-dialing: the reconnect state.
                publish(&core, reconnect_state());
                if !wait(&core, &inner).await {
                    return;
                }
                continue;
            }
        }
        let subscription = handle
            .subscribe_now(
                roboco_rpc::methods::WATCH_PREVIEWS,
                serde_json::json!({"chatId": core.chat_id}),
            )
            .await;
        let mut subscription = match subscription {
            Ok(subscription) => subscription,
            Err(ClientError::Unsupported { message }) => {
                // The web's `unknown-method` rule: degrade once; a
                // re-pair or engine update is the only way out.
                publish(
                    &core,
                    PanelState {
                        phase: PanelPhase::Unsupported,
                        services: Vec::new(),
                        error: Some(message),
                    },
                );
                if !wait(&core, &inner).await {
                    return;
                }
                continue;
            }
            Err(error) => {
                publish(&core, with_note(reconnect_state(), &error));
                if !wait(&core, &inner).await {
                    return;
                }
                continue;
            }
        };
        // The subscribed state: items stream until the link dies; a fleet
        // change that leaves `Connected` ends it early (the engine's own
        // supervision is the redial).
        loop {
            let item = tokio::select! {
                _ = core.cancel.cancelled() => return,
                changed = fleet_rx.changed() => {
                    if changed.is_err() {
                        return;
                    }
                    if engine_state(&inner, &core.engine_id) != Some(EngineState::Connected) {
                        break;
                    }
                    continue;
                }
                item = subscription.recv() => item,
            };
            let Some(value) = item else {
                // The stream ended (link loss or the engine closing it):
                // clear the rows, show the reconnect state, retry after
                // the 2 s beat — the desktop's outer loop.
                publish(&core, reconnect_state());
                break;
            };
            if let Some(state) = snapshot_state(&core, value) {
                publish(&core, state);
            }
        }
        // The desktop's 2 s resubscribe beat (woken early when the fleet
        // changes, so a recovered engine re-subscribes immediately).
        if !wait(&core, &inner).await {
            return;
        }
    }
}

/// Decode one `WatchPreviews` frame leniently, ingest it into the client
/// proxy, and fold the proxy's own status (a busy 7332 surfaces inline,
/// never as a URL change — the 7331 failure mode).
fn snapshot_state(core: &PanelCore, value: serde_json::Value) -> Option<PanelState> {
    let snapshot = crate::lenient::decode_row::<PreviewSnapshot>(&value, &[])
        .map_err(|error| {
            tracing::warn!(%error, "preview snapshot frame was unreadable");
            error
        })
        .ok()?
        .0;
    match core.proxy.ingest(&core.engine_id, snapshot.services) {
        Ok(services) => {
            let live = core.proxy.status();
            Some(PanelState {
                phase: PanelPhase::Live,
                // The snapshot's own error (discovery degraded on the
                // engine) or the listener's bind failure, inline.
                error: snapshot.error.or(live.error),
                services,
            })
        }
        Err(error) => Some(PanelState {
            phase: PanelPhase::Live,
            error: Some(format!(
                "Remote previews could not load this engine's services: {error}"
            )),
            services: Vec::new(),
        }),
    }
}

/// The reconnect state: rows cleared, the desktop's wording.
fn reconnect_state() -> PanelState {
    PanelState {
        phase: PanelPhase::Reconnecting,
        services: Vec::new(),
        error: None,
    }
}

fn with_note(mut state: PanelState, error: &ClientError) -> PanelState {
    state.error = Some(format!("{error}"));
    state
}

fn publish(core: &Arc<PanelCore>, state: PanelState) {
    if let Some(states) = lock(&core.states).as_ref() {
        states.send_replace(Arc::new(state));
    }
}

/// Wait for the next beat: the 2 s resubscribe timer, a fleet change, or
/// the panel closing. `false` = the panel (or the client) is done.
async fn wait(core: &Arc<PanelCore>, inner: &Arc<ClientInner>) -> bool {
    let mut fleet = inner.fleet.subscribe();
    let _ = fleet.borrow_and_update();
    tokio::select! {
        _ = core.cancel.cancelled() => false,
        _ = fleet.changed() => true,
        _ = tokio::time::sleep(RESUBSCRIBE) => true,
    }
}

fn engine_state(inner: &Arc<ClientInner>, engine_id: &str) -> Option<EngineState> {
    lock(&inner.engines)
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == engine_id)
        .map(|entry| entry.state.clone())
}

// ── the dialer ─────────────────────────────────────────────────────────────

/// Dials an engine's `/preview-relay` route over the transport the client
/// already holds: the engine entry's [`crate::engines::route`] (a LAN
/// `ws://` endpoint, or a Tailcat tunnel's loopback base opened on
/// demand — the same path every RPC dial takes) plus the paired session
/// credential. The engine id and chat/service ids are the core's own
/// (the chat id the engine's snapshot advertised, never a scoped one).
struct MobileDialer {
    weak: Weak<ClientInner>,
}

#[async_trait::async_trait]
impl RelayDialer for MobileDialer {
    async fn dial(
        &self,
        engine: &str,
        chat: &str,
        service: &str,
    ) -> anyhow::Result<Box<dyn roboco_preview_relay::mux::Transport>> {
        let inner = self
            .weak
            .upgrade()
            .ok_or_else(|| anyhow::anyhow!("the Roboco client is shut down"))?;
        let (route, credential) = {
            let state = lock(&inner.engines);
            let entry = state
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == engine)
                .ok_or_else(|| anyhow::anyhow!("engine is not paired for remote previews"))?;
            (entry.route.clone(), entry.paired.credential.clone())
        };
        // The same fresh-endpoint rule every dial follows: a Tailcat
        // engine's tunnel is opened (and shared) on demand.
        let endpoint = route
            .endpoint()
            .await
            .map_err(|error| anyhow::anyhow!("engine transport unavailable: {error}"))?;
        let stream = roboco_preview_relay::client::dial_relay(
            &endpoint,
            Some(&credential),
            chat,
            service,
        )
        .await?;
        Ok(Box::new(stream))
    }
}

#[cfg(test)]
mod tests;
