//! The per-engine route (android-next#02): every dial (feed, calls,
//! transcript, spare) asks it for a fresh endpoint.
//!
//! - **Direct** engines dial `websocket_endpoint(base_url)`, as always.
//! - **Tailcat** engines dial a loopback tunnel that the route opens on
//!   demand and rebuilds when it goes bad. Concurrent dials share one open.
//!
//! Rebuild rules. The Go library already recovers on its own: after a
//! failed tunnel dial it rebuilds its tailcat client with the same key and
//! keeps the loopback listener (`adapters/roboco-tailcat/adapter.go`
//! `Client.dial`, proven across a server restart by `recovery_test.go`).
//! So the route must not fight it:
//!
//! 1. A failed WebSocket dial drops the tunnel only when its loopback no
//!    longer accepts TCP: a dead Go client. A dial that reached the
//!    loopback (a refused or unfinished handshake) leaves the Go side to
//!    recover; tearing it down there restarts its recovery from scratch.
//!    An identity timeout (a tunnel that connects but never answers) also
//!    drops it.
//! 2. A network change drops it, so magicsock binds the new interface
//!    (Android's netmon only polls every 10 minutes).
//! 3. Forget, re-pair and shutdown drop it.
//!
//! A route error is a transport error to the link: a normal drop with
//! backoff, never a park. A revoked session still shows only as a refused
//! WebSocket upgrade.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use roboco_rpc::{RpcClient, RpcError};

use super::EngineTransport;
use crate::lock;
use crate::tailcat::{OpenedTunnel, TailcatError, Tunnel, TunnelOpener};

/// How long the loopback probe after a failed dial may take.
const LOOPBACK_PROBE: Duration = Duration::from_secs(2);

/// The WebSocket upgrade budget through a Tailcat tunnel. The loopback
/// accepts at once, then the Go side dials the engine through the tunnel
/// (a 5 s attempt, a client reset, another 5 s) before the upgrade can
/// answer; the 5 s localhost default gave up in the middle of that.
const TAILCAT_HANDSHAKE: Duration = Duration::from_secs(15);

pub(crate) enum Route {
    Direct { endpoint: String },
    Tailcat(TailcatRoute),
}

pub(crate) struct TailcatRoute {
    address: String,
    /// The identity the next open uses; replaced by what the library hands
    /// back (a generated one when this started empty).
    client_state: Mutex<String>,
    opener: Arc<dyn TunnelOpener>,
    /// The live tunnel, if any.
    slot: Mutex<Option<Arc<Tunnel>>>,
    /// The app is in the background: a tunnel opened now starts with its
    /// path pings paused.
    background: std::sync::atomic::AtomicBool,
    /// Held across an open, so concurrent dials share it.
    gate: tokio::sync::Mutex<()>,
}

impl Route {
    /// The route a stored record dials.
    pub(crate) fn for_record(
        base_url: &str,
        transport: &EngineTransport,
        opener: &Arc<dyn TunnelOpener>,
    ) -> Arc<Route> {
        match transport {
            EngineTransport::Direct => Arc::new(Route::Direct {
                endpoint: crate::pairing::websocket_endpoint(base_url),
            }),
            EngineTransport::Tailcat {
                address,
                client_state,
            } => Arc::new(Route::Tailcat(TailcatRoute::new(
                address.clone(),
                client_state.clone(),
                opener.clone(),
            ))),
        }
    }

    /// The endpoint for one dial, opening a Tailcat tunnel if none is live.
    pub(crate) async fn endpoint(&self) -> Result<String, TailcatError> {
        match self {
            Route::Direct { endpoint } => Ok(endpoint.clone()),
            Route::Tailcat(route) => route.tunnel().await.map(|tunnel| tunnel.endpoint()),
        }
    }

    /// Record a dial's outcome (rule 1): after a failure, probe the
    /// tunnel's loopback and drop the tunnel only if nothing accepts there.
    /// An auth refusal came through a working tunnel.
    pub(crate) async fn record_dial(&self, result: &Result<RpcClient, RpcError>) {
        let Route::Tailcat(route) = self else {
            return;
        };
        match result {
            Err(error) if !roboco_rpc::is_auth_refused(error) => route.drop_if_dead().await,
            _ => {}
        }
    }

    /// Drop a Tailcat tunnel (network change, identity timeout, forget).
    /// Connections through it close; the next dial opens a new one.
    pub(crate) fn invalidate(&self) {
        if let Route::Tailcat(route) = self {
            route.drop_tunnel();
        }
    }

    pub(crate) fn is_tailcat(&self) -> bool {
        matches!(self, Route::Tailcat(_))
    }

    /// How a Tailcat route's live tunnel last reached the engine
    /// (fleet-redesign 16); `None` for a direct route or no tunnel.
    pub(crate) fn link_path(&self) -> Option<roboco_proto::tailcat::LinkPath> {
        match self {
            Route::Direct { .. } => None,
            Route::Tailcat(route) => lock(&route.slot).as_ref().and_then(|tunnel| tunnel.path()),
        }
    }

    /// The app went to (or came back from) the background: pause or re-arm
    /// the tunnel's path pings (ADR 0010).
    pub(crate) fn set_background(&self, background: bool) {
        if let Route::Tailcat(route) = self {
            route
                .background
                .store(background, std::sync::atomic::Ordering::Release);
            if let Some(tunnel) = lock(&route.slot).as_ref() {
                tunnel.set_background(background);
            }
        }
    }

    /// The identity a Tailcat route holds now (what the record stores).
    pub(crate) fn client_state(&self) -> Option<String> {
        match self {
            Route::Direct { .. } => None,
            Route::Tailcat(route) => Some(lock(&route.client_state).clone()),
        }
    }

    /// The loopback HTTP base of a Tailcat route, opening it if needed
    /// (pairing redeems through it).
    pub(crate) async fn http_base(&self) -> Result<String, TailcatError> {
        match self {
            Route::Direct { endpoint } => Ok(endpoint.clone()),
            Route::Tailcat(route) => route
                .tunnel()
                .await
                .map(|tunnel| tunnel.url().trim_end_matches('/').to_owned()),
        }
    }
}

impl TailcatRoute {
    pub(crate) fn new(address: String, client_state: String, opener: Arc<dyn TunnelOpener>) -> Self {
        Self {
            address,
            client_state: Mutex::new(client_state),
            opener,
            slot: Mutex::new(None),
            background: std::sync::atomic::AtomicBool::new(false),
            gate: tokio::sync::Mutex::new(()),
        }
    }

    async fn tunnel(&self) -> Result<Arc<Tunnel>, TailcatError> {
        if let Some(tunnel) = lock(&self.slot).clone() {
            return Ok(tunnel);
        }
        let _gate = self.gate.lock().await;
        // Another dial may have opened it while this one waited.
        if let Some(tunnel) = lock(&self.slot).clone() {
            return Ok(tunnel);
        }
        let opener = self.opener.clone();
        let address = self.address.clone();
        let state = lock(&self.client_state).clone();
        tracing::debug!("tailcat route: opening a tunnel");
        let opened = tokio::task::spawn_blocking(move || opener.open(&address, &state))
            .await
            .map_err(|_| TailcatError::Unreachable("the Tailcat open was interrupted".into()))
            .and_then(|opened| opened);
        let OpenedTunnel {
            tunnel,
            client_state,
        } = match opened {
            Ok(opened) => opened,
            Err(error) => {
                tracing::debug!(%error, "tailcat route: open failed");
                return Err(error);
            }
        };
        tracing::debug!(url = tunnel.url(), "tailcat route: tunnel open");
        if !client_state.is_empty() {
            *lock(&self.client_state) = client_state;
        }
        if self.background.load(std::sync::atomic::Ordering::Acquire) {
            tunnel.set_background(true);
        }
        let tunnel = Arc::new(tunnel);
        *lock(&self.slot) = Some(tunnel.clone());
        Ok(tunnel)
    }

    fn drop_tunnel(&self) {
        // The tunnel closes when the last dial holding it lets go.
        let _ = lock(&self.slot).take();
    }

    /// Drops the live tunnel if its loopback no longer accepts TCP (the Go
    /// client is gone). A newer tunnel opened meanwhile is left alone.
    async fn drop_if_dead(&self) {
        let Some(tunnel) = lock(&self.slot).clone() else {
            return;
        };
        if loopback_accepts(tunnel.url()).await {
            tracing::debug!(url = tunnel.url(), "tailcat route: dial failed, loopback alive; Go recovers");
            return;
        }
        tracing::info!("the Tailcat loopback is gone; rebuilding the tunnel");
        let mut slot = lock(&self.slot);
        if slot.as_ref().is_some_and(|current| Arc::ptr_eq(current, &tunnel)) {
            slot.take();
        }
    }
}

/// Whether `http://127.0.0.1:<port>` accepts a TCP connection within
/// [LOOPBACK_PROBE]. The probe connection is closed at once; the Go proxy
/// treats it like any client that hung up.
async fn loopback_accepts(url: &str) -> bool {
    let Some(authority) = url.strip_prefix("http://") else {
        return false;
    };
    let authority = authority.trim_end_matches('/').to_owned();
    matches!(
        tokio::time::timeout(LOOPBACK_PROBE, tokio::net::TcpStream::connect(authority)).await,
        Ok(Ok(_))
    )
}

/// What a dial needs: the route and the credential, handed around as one.
#[derive(Clone)]
pub(crate) struct DialTarget {
    pub route: Arc<Route>,
    pub credential: String,
}

impl Default for DialTarget {
    fn default() -> Self {
        // A missing engine: the dial fails as a plain transport error.
        Self {
            route: Arc::new(Route::Direct {
                endpoint: String::new(),
            }),
            credential: String::new(),
        }
    }
}

impl DialTarget {
    /// Resolve the route and dial an authenticated WebSocket.
    pub(crate) async fn connect(&self) -> Result<RpcClient, RpcError> {
        let endpoint = self
            .route
            .endpoint()
            .await
            .map_err(|error| RpcError::Transport(error.to_string()))?;
        let result = if self.route.is_tailcat() {
            roboco_rpc::connect_ws_authenticated_within(&endpoint, &self.credential, TAILCAT_HANDSHAKE)
                .await
        } else {
            roboco_rpc::connect_ws_authenticated(&endpoint, &self.credential).await
        };
        if self.route.is_tailcat() {
            match &result {
                Ok(_) => tracing::debug!(%endpoint, "tailcat route: dial ok"),
                Err(error) => tracing::debug!(%endpoint, %error, "tailcat route: dial failed"),
            }
        }
        self.route.record_dial(&result).await;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A fake opener: counts opens, can be slow, can fail, reports tunnel
    /// closes, and hands out the loopback ports it is given (in order),
    /// else 40000 + n.
    struct Fake {
        opens: AtomicUsize,
        closes: Arc<AtomicUsize>,
        delay: Duration,
        fail: Option<TailcatError>,
        ports: Mutex<VecDeque<u16>>,
    }

    impl Fake {
        fn with(delay: Duration, fail: Option<TailcatError>, ports: Vec<u16>) -> Arc<Self> {
            Arc::new(Self {
                opens: AtomicUsize::new(0),
                closes: Arc::new(AtomicUsize::new(0)),
                delay,
                fail,
                ports: Mutex::new(ports.into()),
            })
        }

        fn new() -> Arc<Self> {
            Self::with(Duration::ZERO, None, Vec::new())
        }
    }

    impl TunnelOpener for Fake {
        fn open(&self, address: &str, client_state: &str) -> Result<OpenedTunnel, TailcatError> {
            assert_eq!(address, "tcADDRESS");
            std::thread::sleep(self.delay);
            if let Some(error) = &self.fail {
                return Err(error.clone());
            }
            let count = self.opens.fetch_add(1, Ordering::SeqCst) + 1;
            let port = lock(&self.ports)
                .pop_front()
                .unwrap_or(40000 + count as u16);
            let closes = self.closes.clone();
            Ok(OpenedTunnel {
                tunnel: Tunnel::new(
                    format!("http://127.0.0.1:{port}"),
                    Box::new(move || {
                        closes.fetch_add(1, Ordering::SeqCst);
                    }),
                ),
                client_state: if client_state.is_empty() {
                    "generated-state".into()
                } else {
                    client_state.into()
                },
            })
        }
    }

    fn tailcat(fake: &Arc<Fake>, state: &str) -> Arc<Route> {
        let opener: Arc<dyn TunnelOpener> = fake.clone();
        Route::for_record(
            "tailcat:00000000",
            &EngineTransport::Tailcat {
                address: "tcADDRESS".into(),
                client_state: state.into(),
            },
            &opener,
        )
    }

    fn failed_dial() -> Result<RpcClient, RpcError> {
        Err(RpcError::Transport(
            "WebSocket protocol error: Handshake not finished".into(),
        ))
    }

    /// A loopback port nothing listens on (bound, then released).
    async fn closed_port() -> u16 {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        listener.local_addr().unwrap().port()
    }

    async fn wait_for(counter: &AtomicUsize, value: usize) {
        for _ in 0..200 {
            if counter.load(Ordering::SeqCst) == value {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("counter stayed at {}", counter.load(Ordering::SeqCst));
    }

    #[tokio::test]
    async fn direct_routes_never_open_anything() {
        let fake = Fake::new();
        let opener: Arc<dyn TunnelOpener> = fake.clone();
        let route = Route::for_record("http://h:8123", &EngineTransport::Direct, &opener);
        assert_eq!(route.endpoint().await.unwrap(), "ws://h:8123/");
        route.record_dial(&failed_dial()).await;
        route.invalidate();
        assert!(!route.is_tailcat());
        assert_eq!(fake.opens.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn concurrent_dials_share_one_open() {
        let fake = Fake::with(Duration::from_millis(200), None, Vec::new());
        let route = tailcat(&fake, "");
        let dials: Vec<_> = (0..5)
            .map(|_| {
                let route = route.clone();
                tokio::spawn(async move { route.endpoint().await.unwrap() })
            })
            .collect();
        let mut endpoints = Vec::new();
        for dial in dials {
            endpoints.push(dial.await.unwrap());
        }
        assert_eq!(fake.opens.load(Ordering::SeqCst), 1);
        assert!(endpoints.iter().all(|endpoint| endpoint == "ws://127.0.0.1:40001/"));
        // The generated identity is what the record stores.
        assert_eq!(route.client_state().as_deref(), Some("generated-state"));
    }

    #[tokio::test]
    async fn failures_through_a_live_loopback_leave_recovery_to_the_go_client() {
        // The Go proxy is alive (its listener accepts): the server side is
        // what failed, and the Go client rebuilds itself. Never tear it
        // down from here, however many dials fail.
        let live = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = live.local_addr().unwrap().port();
        let fake = Fake::with(Duration::ZERO, None, vec![port]);
        let route = tailcat(&fake, "stored-state");
        let endpoint = route.endpoint().await.unwrap();
        for _ in 0..5 {
            route.record_dial(&failed_dial()).await;
        }
        assert_eq!(route.endpoint().await.unwrap(), endpoint);
        assert_eq!(fake.opens.load(Ordering::SeqCst), 1);
        assert_eq!(fake.closes.load(Ordering::SeqCst), 0);
        drop(live);
    }

    #[tokio::test]
    async fn a_failure_with_a_dead_loopback_rebuilds_the_tunnel() {
        let (dead, next) = (closed_port().await, closed_port().await);
        let fake = Fake::with(Duration::ZERO, None, vec![dead, next]);
        let route = tailcat(&fake, "stored-state");
        assert_eq!(route.endpoint().await.unwrap(), format!("ws://127.0.0.1:{dead}/"));
        route.record_dial(&failed_dial()).await;
        wait_for(&fake.closes, 1).await;
        assert_eq!(route.endpoint().await.unwrap(), format!("ws://127.0.0.1:{next}/"));
        assert_eq!(fake.opens.load(Ordering::SeqCst), 2);
        // The stored identity is reused, never replaced.
        assert_eq!(route.client_state().as_deref(), Some("stored-state"));
    }

    #[tokio::test]
    async fn an_auth_refusal_never_rebuilds() {
        let dead = closed_port().await;
        let fake = Fake::with(Duration::ZERO, None, vec![dead]);
        let route = tailcat(&fake, "s");
        route.endpoint().await.unwrap();
        route
            .record_dial(&Err(RpcError::Transport("HTTP error: 401 Unauthorized".into())))
            .await;
        route.endpoint().await.unwrap();
        assert_eq!(fake.opens.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn invalidate_drops_the_tunnel_and_the_next_dial_reopens() {
        let fake = Fake::new();
        let route = tailcat(&fake, "s");
        route.endpoint().await.unwrap();
        route.invalidate();
        wait_for(&fake.closes, 1).await;
        assert_eq!(route.endpoint().await.unwrap(), "ws://127.0.0.1:40002/");
        // Forget: dropping the route closes its tunnel too.
        drop(route);
        wait_for(&fake.closes, 2).await;
    }

    #[tokio::test]
    async fn open_failures_surface_as_transport_errors_without_the_address() {
        let fake = Fake::with(
            Duration::ZERO,
            Some(TailcatError::Unreachable("Tailcat client startup failed".into())),
            Vec::new(),
        );
        let target = DialTarget {
            route: tailcat(&fake, "s"),
            credential: "credential".into(),
        };
        // RpcClient is not Debug, so no unwrap_err.
        let error = match target.connect().await {
            Ok(_) => panic!("a failed open must not connect"),
            Err(error) => error,
        };
        assert!(!roboco_rpc::is_auth_refused(&error));
        let text = error.to_string();
        assert!(text.contains("Tailcat"), "{text}");
        assert!(!text.contains("tcADDRESS"), "{text}");
    }
}
