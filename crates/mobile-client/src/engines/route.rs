//! The per-engine route (android-next#02): every dial (feed, calls,
//! transcript, spare) asks it for a fresh endpoint.
//!
//! - **Direct** engines dial `websocket_endpoint(base_url)`, as always.
//! - **Tailcat** engines dial a loopback tunnel that the route opens on
//!   demand and rebuilds when it goes bad. Concurrent dials share one open.
//!
//! Rebuild rules (the Go client already rebuilds its tailcat client after
//! a failed tunnel dial, keeping the loopback port):
//!
//! 1. [`REBUILD_AFTER`] WebSocket dial failures in a row through the same
//!    tunnel, or an identity timeout, drop it: the next dial reopens it.
//!    This covers a dead Go client.
//! 2. A network change drops it, so magicsock binds the new interface
//!    (Android's netmon only polls every 10 minutes).
//! 3. Forget, re-pair and shutdown drop it.
//!
//! A route error is a transport error to the link: a normal drop with
//! backoff, never a park. A revoked session still shows only as a refused
//! WebSocket upgrade.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use roboco_rpc::{RpcClient, RpcError};

use super::EngineTransport;
use crate::lock;
use crate::tailcat::{OpenedTunnel, TailcatError, Tunnel, TunnelOpener};

/// Consecutive failed dials through one tunnel before it is rebuilt.
pub(crate) const REBUILD_AFTER: u32 = 2;

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
    /// Held across an open, so concurrent dials share it.
    gate: tokio::sync::Mutex<()>,
    failures: AtomicU32,
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

    /// Record a dial's outcome. An auth refusal proves the tunnel works.
    pub(crate) fn record_dial(&self, result: &Result<RpcClient, RpcError>) {
        let Route::Tailcat(route) = self else {
            return;
        };
        match result {
            Ok(_) => route.failures.store(0, Ordering::Relaxed),
            Err(error) if roboco_rpc::is_auth_refused(error) => {
                route.failures.store(0, Ordering::Relaxed)
            }
            Err(_) => {
                if route.failures.fetch_add(1, Ordering::Relaxed) + 1 >= REBUILD_AFTER {
                    tracing::info!("Tailcat dials keep failing; rebuilding the tunnel");
                    route.drop_tunnel();
                }
            }
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
            gate: tokio::sync::Mutex::new(()),
            failures: AtomicU32::new(0),
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
        let OpenedTunnel {
            tunnel,
            client_state,
        } = tokio::task::spawn_blocking(move || opener.open(&address, &state))
            .await
            .map_err(|_| TailcatError::Unreachable("the Tailcat open was interrupted".into()))??;
        if !client_state.is_empty() {
            *lock(&self.client_state) = client_state;
        }
        let tunnel = Arc::new(tunnel);
        *lock(&self.slot) = Some(tunnel.clone());
        self.failures.store(0, Ordering::Relaxed);
        Ok(tunnel)
    }

    fn drop_tunnel(&self) {
        self.failures.store(0, Ordering::Relaxed);
        // The tunnel closes when the last dial holding it lets go.
        let _ = lock(&self.slot).take();
    }
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
        let result = roboco_rpc::connect_ws_authenticated(&endpoint, &self.credential).await;
        self.route.record_dial(&result);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use std::time::Duration;

    /// A fake opener: counts opens, can be slow, can fail, and reports
    /// tunnel closes.
    struct Fake {
        opens: AtomicUsize,
        closes: Arc<AtomicUsize>,
        delay: Duration,
        fail: Option<TailcatError>,
    }

    impl Fake {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                opens: AtomicUsize::new(0),
                closes: Arc::new(AtomicUsize::new(0)),
                delay: Duration::ZERO,
                fail: None,
            })
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
            let closes = self.closes.clone();
            Ok(OpenedTunnel {
                tunnel: Tunnel::new(
                    format!("http://127.0.0.1:{}", 40000 + count),
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
        Err(RpcError::Transport("connection refused".into()))
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
        route.record_dial(&failed_dial());
        route.record_dial(&failed_dial());
        route.invalidate();
        assert!(!route.is_tailcat());
        assert_eq!(fake.opens.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn concurrent_dials_share_one_open() {
        let fake = Arc::new(Fake {
            delay: Duration::from_millis(200),
            ..Arc::try_unwrap(Fake::new()).ok().unwrap()
        });
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
    async fn repeated_dial_failures_rebuild_the_tunnel() {
        let fake = Fake::new();
        let route = tailcat(&fake, "stored-state");
        assert_eq!(route.endpoint().await.unwrap(), "ws://127.0.0.1:40001/");
        route.record_dial(&failed_dial());
        // One failure keeps the tunnel (the Go client retries itself).
        assert_eq!(route.endpoint().await.unwrap(), "ws://127.0.0.1:40001/");
        assert_eq!(fake.closes.load(Ordering::SeqCst), 0);
        route.record_dial(&failed_dial());
        wait_for(&fake.closes, 1).await;
        assert_eq!(route.endpoint().await.unwrap(), "ws://127.0.0.1:40002/");
        assert_eq!(fake.opens.load(Ordering::SeqCst), 2);
        // The stored identity is reused, never replaced.
        assert_eq!(route.client_state().as_deref(), Some("stored-state"));
    }

    #[tokio::test]
    async fn a_success_or_an_auth_refusal_resets_the_failure_count() {
        let fake = Fake::new();
        let route = tailcat(&fake, "s");
        route.endpoint().await.unwrap();
        route.record_dial(&failed_dial());
        route.record_dial(&Err(RpcError::Transport("HTTP error: 401 Unauthorized".into())));
        route.record_dial(&failed_dial());
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
        let fake = Arc::new(Fake {
            fail: Some(TailcatError::Unreachable("Tailcat client startup failed".into())),
            ..Arc::try_unwrap(Fake::new()).ok().unwrap()
        });
        let target = DialTarget {
            route: tailcat(&fake, "s"),
            credential: "credential".into(),
        };
        let error = target.connect().await.unwrap_err();
        assert!(!roboco_rpc::is_auth_refused(&error));
        let text = error.to_string();
        assert!(text.contains("Tailcat"), "{text}");
        assert!(!text.contains("tcADDRESS"), "{text}");
    }
}
