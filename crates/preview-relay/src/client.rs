//! The client's half of the session-relay data plane (Phase C, ticket 04):
//! a GPUI-free loopback proxy that serves a remote engine's previews on
//! `127.0.0.1:7332` by dialing that engine's `/preview-relay` route over the
//! transport the client already holds.
//!
//! What this module is: the *one* Rust client proxy every Roboco client
//! shares. The desktop links it through `roboco-engine-client`; the Android
//! mobile core links the same crate. It never touches GPUI, the engine, or
//! WebRTC — the relayed bytes ride the engine session the client already
//! holds (a LAN WebSocket, or a Tailcat tunnel's loopback base, supplied by
//! the caller through [`RelayDialer`]).
//!
//! Routing is by Host header against the remote engine's preview hostnames,
//! ingested from the `WatchPreviews` snapshots the client already receives —
//! the dormant `catalog.set_remote` semantics (validation, bounded counts,
//! distinct labels on device-name collisions, unique hostnames) applied on
//! the client, where the "remote" is every engine this client is paired
//! with. A hostname maps to exactly one (engine, chat, service); a stale
//! hostname answers 502 with the "not running" shape.
//!
//! Lifecycle: the listener binds only while some engine's previews are being
//! watched (lazy start on the first watcher, idle shutdown after the last
//! one leaves), and a busy port surfaces an inline error — never a URL
//! change: the rendered port stays 7332 (`PREVIEW_CLIENT_PROXY_PORT`) either
//! way, mirroring the 7331 failure mode on the engine.
//!
//! Data plane: each accepted HTTP connection opens a mux stream on a
//! per-(engine, chat, service) relay — the client is always `Mux` initiator
//! (`initiator: true`, never answering OPENs) over a WebSocket transport
//! that mirrors the engine's frame-per-binary-message shape. HTTP relaying
//! applies the same header hygiene as the engine's 7331 proxy
//! ([`crate::http`]): hop-by-hop stripping, Host/Origin preserved,
//! `X-Forwarded-Host`, localhost redirect rewrites, bounded upgrade tunnels,
//! and WebSocket passthrough for HMR.
//!
//! Entry shapes: origin-form requests routed by Host (a browser that
//! resolves `*.localhost` itself — Android's WebView, or a desktop on a
//! synthesizing resolver), absolute-form requests routed by authority
//! (WebKitGTK's proxy mode, which needs no `*.localhost` synthesis at all),
//! and CONNECT tunnels to this listener (the WebKit per-domain CONNECT
//! registration's shape, macOS and Linux alike).

use std::{
    collections::{HashMap, HashSet},
    net::{Ipv4Addr, Ipv6Addr},
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};

use futures::{SinkExt as _, StreamExt as _};
use hyper::{
    Method, Request, Response, Uri,
    body::Incoming,
    header::{self, HeaderValue},
};
use http_body_util::BodyExt as _;
use hyper_util::rt::TokioIo;
use roboco_proto::{PREVIEW_CLIENT_PROXY_PORT, PreviewService};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{TcpListener, TcpStream},
};
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest};
use tokio_util::sync::CancellationToken;
use tracing::warn;

use crate::{
    http::{self, AbortOnDrop, Body, GuardedBody},
    mux::{self, BoxIo, Connector},
};

/// The client proxy's stable port. The engine owns 7331 and the browser
/// helper 7333; 7332 is unclaimed. Tests may bind an ephemeral port instead.
pub const CLIENT_PROXY_PORT: u16 = PREVIEW_CLIENT_PROXY_PORT;

/// How one engine's relay is reached: the dial runs over the transport the
/// client already holds for that engine — the registry's saved `ws://`
/// endpoint plus session credential (a LAN address, or a Tailcat tunnel's
/// loopback base) on the desktop, a `PairedEngine` record on the phone.
///
/// `engine` is the caller's opaque, stable engine id (an `EngineKey` on the
/// desktop). Implementations return an *authenticated* relay transport for
/// exactly one (chat, service) scope; the route and its credentials are the
/// implementation's business, never a URL the browser sees.
#[async_trait::async_trait]
pub trait RelayDialer: Send + Sync {
    async fn dial(
        &self,
        engine: &str,
        chat: &str,
        service: &str,
    ) -> anyhow::Result<Box<dyn mux::Transport>>;
}

/// The client never accepts OPENs: the relay protocol is client-initiated,
/// so the client-side mux hands every incoming OPEN to a connector that
/// refuses it (the engine-side tests' `Refused`, in production).
struct Refused;
#[async_trait::async_trait]
impl Connector for Refused {
    async fn connect(&self, _service: &str) -> anyhow::Result<BoxIo> {
        anyhow::bail!("the relay client never accepts OPENs")
    }
}

/// Dial one engine's relay route over its saved RPC endpoint: the same
/// `ws://`/`wss://` URL and session credential the RPC socket dials with, so
/// LAN engines and tunneled engines (a Tailcat forwarder's loopback base)
/// both work without new network setup. The credential rides the
/// `Authorization` header, never the URL; the path segments are the
/// url-safe ids the engine's route parses.
pub async fn dial_relay(
    endpoint: &str,
    credential: Option<&str>,
    chat: &str,
    service: &str,
) -> anyhow::Result<WebSocketRelayStream<tokio_tungstenite::MaybeTlsStream<TcpStream>>> {
    let url = format!(
        "{}/preview-relay/{chat}/{service}",
        endpoint.trim_end_matches('/')
    );
    let mut request = url
        .as_str()
        .into_client_request()
        .map_err(|error| anyhow::anyhow!("invalid engine relay URL: {error}"))?;
    if let Some(credential) = credential {
        let mut value = HeaderValue::from_str(&format!("Bearer {credential}"))
            .map_err(|_| anyhow::anyhow!("invalid session credential"))?;
        value.set_sensitive(true);
        request.headers_mut().insert("authorization", value);
    }
    // Mux frames are one binary WebSocket message of at most CHUNK+5 bytes;
    // the ceiling only bounds a misbehaving peer's frames.
    let (stream, response) = tokio_tungstenite::connect_async_with_config(
        request,
        Some(tokio_tungstenite::tungstenite::protocol::WebSocketConfig {
            max_message_size: Some(1024 * 1024),
            max_frame_size: Some(1024 * 1024),
            ..Default::default()
        }),
        false,
    )
    .await?;
    anyhow::ensure!(
        response.status().as_u16() == 101,
        "engine refused the preview relay: HTTP {}",
        response.status().as_u16()
    );
    Ok(WebSocketRelayStream::new(stream))
}

/// A WebSocket carrying mux frames — the client twin of the engine's
/// `preview_relay::WsTransport`: one mux frame per binary message, pings
/// answered, and anything that is not a binary frame (a text frame, a close,
/// EOF) ends the transport so the mux tears its streams down. The halves
/// lock independently, so a slow reader never blocks outbound frames.
pub struct WebSocketRelayStream<S> {
    write: tokio::sync::Mutex<
        futures::stream::SplitSink<tokio_tungstenite::WebSocketStream<S>, Message>,
    >,
    read: tokio::sync::Mutex<futures::stream::SplitStream<tokio_tungstenite::WebSocketStream<S>>>,
}

impl<S> WebSocketRelayStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    pub fn new(stream: tokio_tungstenite::WebSocketStream<S>) -> Self {
        let (write, read) = stream.split();
        Self {
            write: tokio::sync::Mutex::new(write),
            read: tokio::sync::Mutex::new(read),
        }
    }
}

#[async_trait::async_trait]
impl<S> mux::Transport for WebSocketRelayStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    async fn send(&self, bytes: &[u8]) -> anyhow::Result<()> {
        let mut sink = self.write.lock().await;
        sink.send(Message::Binary(bytes.to_vec())).await?;
        Ok(())
    }

    async fn receive(&self) -> anyhow::Result<Vec<u8>> {
        loop {
            let message = {
                let mut stream = self.read.lock().await;
                stream.next().await
            };
            match message {
                Some(Ok(Message::Binary(bytes))) => return Ok(bytes),
                Some(Ok(Message::Ping(payload))) => {
                    let mut sink = self.write.lock().await;
                    sink.send(Message::Pong(payload)).await?;
                }
                Some(Ok(Message::Pong(_))) => {}
                Some(Ok(Message::Text(_))) => {
                    anyhow::bail!("text frame on the preview relay");
                }
                Some(Ok(Message::Close(_))) | None => {
                    anyhow::bail!("preview relay closed");
                }
                Some(Ok(Message::Frame(_))) => {}
                Some(Err(error)) => return Err(anyhow::anyhow!("{error}")),
            }
        }
    }
}

/// A live client-side proxy: a cloneable handle over one shared inner.
#[derive(Clone)]
pub struct ClientProxy(Arc<Inner>);

/// How a client proxy binds. The port defaults to
/// [`PREVIEW_CLIENT_PROXY_PORT`]; tests bind `0` for an ephemeral port. The
/// idle window is how long the listener outlives the last watcher.
#[derive(Clone)]
pub struct ClientProxyOptions {
    pub port: u16,
    pub idle: Duration,
    /// Persistence for the hostname labels assigned to colliding remote
    /// device names, so preview URLs survive a client restart.
    pub labels_file: Option<PathBuf>,
}

impl Default for ClientProxyOptions {
    fn default() -> Self {
        Self {
            port: CLIENT_PROXY_PORT,
            idle: Duration::from_secs(30),
            labels_file: None,
        }
    }
}

/// The proxy's listener state, published to subscribers.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClientStatus {
    /// The bound port while running (the effective port when an ephemeral
    /// port was requested).
    pub port: Option<u16>,
    /// A bind failure surfaced inline (a busy port), mirroring the engine's
    /// 7331 failure mode. Cleared on recovery.
    pub error: Option<String>,
}

type DialResult = anyhow::Result<Arc<Relay>>;

struct Inner {
    dialer: Arc<dyn RelayDialer>,
    options: ClientProxyOptions,
    runtime: tokio::runtime::Handle,
    /// Lock order: `state` before `relays` before `pending`, never reversed.
    state: Mutex<State>,
    status: tokio::sync::watch::Sender<ClientStatus>,
    /// One relay mux per (engine, chat, service): every accepted HTTP
    /// connection opens a *stream* on it; the socket itself is shared.
    relays: Mutex<HashMap<String, Arc<Relay>>>,
    /// Waiters sharing an in-flight dial, keyed like `relays`.
    pending: Mutex<HashMap<String, Vec<tokio::sync::oneshot::Sender<DialResult>>>>,
}

#[derive(Default)]
struct State {
    /// Engines whose previews are being viewed right now (the watcher set
    /// that keeps the listener alive).
    watchers: HashSet<String>,
    /// The routing table (the client-side `set_remote` ingestion).
    table: Table,
    /// The live listener run, if any.
    run: Option<Run>,
    /// The port the last run bound, so a restart (or a watcher returning
    /// after an idle stop) keeps the address stable even when an ephemeral
    /// port was requested.
    bound: Option<u16>,
}

struct Run {
    token: CancellationToken,
}

struct Relay {
    mux: mux::Mux,
    cancel: CancellationToken,
}

/// The client-side twin of the engine catalog's remote ingestion
/// (`Catalog::set_remote`): validation, bounded counts, and distinct labels
/// when two engines' hostnames collide — driven from `WatchPreviews`
/// snapshots instead of peer signaling.
#[derive(Default)]
struct Table {
    /// engine id → services with effective hostnames.
    engines: HashMap<String, Vec<PreviewService>>,
    /// effective hostname → (engine, service).
    routes: HashMap<String, Route>,
    /// engine id → the label its hostnames render under.
    labels: HashMap<String, String>,
}

#[derive(Clone)]
struct Route {
    engine: String,
    service: PreviewService,
}

impl ClientProxy {
    /// Create the proxy. Nothing binds until the first watcher arrives.
    pub fn new(
        dialer: Arc<dyn RelayDialer>,
        options: ClientProxyOptions,
        runtime: tokio::runtime::Handle,
    ) -> Self {
        let (status, _) = tokio::sync::watch::channel(ClientStatus::default());
        // Labels assigned to colliding device names persist so preview URLs
        // survive a client restart; unreadable or missing files start fresh.
        let labels = options
            .labels_file
            .as_deref()
            .and_then(|file| std::fs::read(file).ok())
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Self(Arc::new(Inner {
            dialer,
            options,
            runtime,
            state: Mutex::new(State {
                table: Table {
                    labels,
                    ..Table::default()
                },
                ..State::default()
            }),
            status,
            relays: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
        }))
    }

    /// The listener status (running port, bind failure).
    pub fn status(&self) -> ClientStatus {
        self.0.status.borrow().clone()
    }

    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<ClientStatus> {
        self.0.status.subscribe()
    }

    /// Mark an engine's previews as viewed (or not). The first watcher
    /// lazily starts the listener; after the last one leaves, the listener
    /// shuts down once the idle window passes.
    pub fn watch_engine(&self, engine: &str, watching: bool) {
        let inner = &self.0;
        let mut state = lock(&inner.state);
        if watching {
            if state.watchers.insert(engine.to_owned()) && state.run.is_none() {
                inner.spawn_run(&mut state);
            }
        } else if state.watchers.remove(engine) && state.watchers.is_empty() {
            let idle = inner.options.idle;
            let inner = inner.clone();
            let runtime = inner.runtime.clone();
            runtime.spawn(async move {
                tokio::time::sleep(idle).await;
                let mut state = lock(&inner.state);
                if state.watchers.is_empty() {
                    inner.stop_locked(&mut state);
                }
            });
        }
    }

    /// Ingest one engine's `WatchPreviews` services (the dormant
    /// `set_remote` semantics, driven from the snapshot). Returns the
    /// services with their *effective* hostnames — identical unless another
    /// ingested engine already claimed the same name, in which case this
    /// engine's names get a persistent distinct label. Services without a
    /// chat attribution are skipped: the engine's relay scopes every dial to
    /// the chat that started the server, so they cannot be relayed at all.
    pub fn ingest(
        &self,
        engine: &str,
        services: Vec<PreviewService>,
    ) -> anyhow::Result<Vec<PreviewService>> {
        let inner = &self.0;
        let mut state = lock(&inner.state);
        let effective = state
            .table
            .ingest(engine, services, inner.options.labels_file.as_deref())?;
        inner.trim_relays_locked(&state);
        Ok(effective)
    }

    /// Forget one engine's routes entirely (it was unpaired).
    pub fn remove_engine(&self, engine: &str) {
        let inner = &self.0;
        let mut state = lock(&inner.state);
        state.table.remove(engine);
        inner.trim_relays_locked(&state);
    }

    /// Look a hostname up in the routing table.
    pub fn by_hostname(&self, hostname: &str) -> Option<PreviewService> {
        lock(&self.0.state)
            .table
            .routes
            .get(hostname)
            .map(|route| route.service.clone())
    }

    /// The engines currently ingested (for hygiene: an engine that left the
    /// registry should stop routing).
    pub fn engines(&self) -> Vec<String> {
        lock(&self.0.state).table.engines.keys().cloned().collect()
    }

    /// The services one engine currently contributes, effective hostnames.
    pub fn effective_services(&self, engine: &str) -> Vec<PreviewService> {
        lock(&self.0.state)
            .table
            .engines
            .get(engine)
            .cloned()
            .unwrap_or_default()
    }

    /// Stop serving and drop every relay, regardless of watchers.
    pub async fn stop(&self) {
        let inner = &self.0;
        let mut state = lock(&inner.state);
        inner.stop_locked(&mut state);
    }
}

impl Inner {
    fn spawn_run(self: &Arc<Self>, state: &mut State) {
        debug_assert!(state.run.is_none());
        let token = CancellationToken::new();
        state.run = Some(Run {
            token: token.clone(),
        });
        self.status.send_modify(|status| {
            *status = ClientStatus::default();
        });
        let requested = state.bound.unwrap_or(self.options.port);
        let inner = self.clone();
        self.runtime.spawn(async move {
            inner.run_loop(token, requested).await;
        });
    }

    /// Bind (and rebind while watchers remain). A busy port surfaces the
    /// inline error and is retried — never worked around by changing the
    /// port the browser addresses.
    async fn run_loop(self: Arc<Self>, token: CancellationToken, requested: u16) {
        loop {
            if token.is_cancelled() {
                return;
            }
            match serve(self.clone(), requested, token.child_token()).await {
                Ok((port, tasks)) => {
                    self.status.send_modify(|status| {
                        status.port = Some(port);
                        status.error = None;
                    });
                    {
                        let mut state = lock(&self.state);
                        if state.run.is_some() {
                            state.bound = Some(port);
                        }
                    }
                    for task in tasks {
                        let _ = task.await;
                    }
                    // The accept loops only end when this run's token is
                    // cancelled (stop() has already taken the run) or a
                    // listener died. Restart while anyone still watches.
                    let restart = {
                        let mut state = lock(&self.state);
                        if state.run.is_some() {
                            if state.watchers.is_empty() {
                                self.stop_locked(&mut state);
                                false
                            } else {
                                state.run = None;
                                true
                            }
                        } else {
                            false
                        }
                    };
                    if restart {
                        let mut state = lock(&self.state);
                        self.spawn_run(&mut state);
                    }
                    return;
                }
                Err(error) => {
                    self.status.send_modify(|status| {
                        status.error = Some(format!(
                            "Remote previews could not listen on port {requested}: {error}"
                        ));
                        status.port = None;
                    });
                    let alive = {
                        let mut state = lock(&self.state);
                        if state.watchers.is_empty() {
                            self.stop_locked(&mut state);
                            false
                        } else {
                            true
                        }
                    };
                    if !alive {
                        return;
                    }
                    tokio::select! {
                        _ = token.cancelled() => return,
                        _ = tokio::time::sleep(Duration::from_secs(2)) => {}
                    }
                }
            }
        }
    }

    fn stop_locked(&self, state: &mut State) {
        // Cancelling the run's token (taking alone would not — tokens are
        // cooperative) ends the listener, its tunnels, and the relays.
        if let Some(run) = state.run.take() {
            run.token.cancel();
        }
        state.watchers.clear();
        let relays = lock(&self.relays);
        for relay in relays.values() {
            relay.cancel.cancel();
        }
        drop(relays);
        self.status.send_modify(|status| {
            *status = ClientStatus::default();
        });
    }

    /// Relay bookkeeping shared by ingest/remove: muxes for services that
    /// left the table are cancelled (their janitors drop them). The caller
    /// holds the state lock; only the relays lock is taken here.
    fn trim_relays_locked(&self, state: &State) {
        let live: HashSet<String> = state
            .table
            .routes
            .values()
            .map(|route| relay_key(&route.engine, route))
            .collect();
        let relays = lock(&self.relays);
        for (key, relay) in relays.iter() {
            if !live.contains(key) {
                relay.cancel.cancel();
            }
        }
    }

    /// One relay mux for a (engine, chat, service) scope, dialed once and
    /// shared by every connection that hostname serves; concurrent first
    /// requests share one dial.
    async fn relay(
        self: &Arc<Self>,
        engine: &str,
        chat: &str,
        service: &str,
    ) -> anyhow::Result<Arc<Relay>> {
        let key = relay_key_raw(engine, chat, service);
        enum Wait {
            Pending(tokio::sync::oneshot::Receiver<DialResult>),
            Dial,
        }
        let wait = {
            // A live relay short-circuits before any dial bookkeeping.
            let relays = lock(&self.relays);
            if let Some(relay) = relays.get(&key)
                && !relay.mux.is_closed()
            {
                return Ok(relay.clone());
            }
            let mut pending = lock(&self.pending);
            if let Some(waiters) = pending.get_mut(&key) {
                let (tx, rx) = tokio::sync::oneshot::channel();
                waiters.push(tx);
                Wait::Pending(rx)
            } else {
                pending.insert(key.clone(), Vec::new());
                Wait::Dial
            }
        };
        match wait {
            Wait::Pending(rx) => {
                // A closed result fails the caller's `open` at once; the next
                // request dials fresh — no unbounded retry loop here.
                rx.await
                    .map_err(|_| anyhow::anyhow!("relay dial was abandoned"))?
            }
            Wait::Dial => {
                let result = match self.dialer.dial(engine, chat, service).await {
                    Ok(transport) => Ok(self.start_relay(key.clone(), transport)),
                    Err(error) => Err(anyhow::anyhow!(
                        "could not reach this engine's preview relay: {error}"
                    )),
                };
                let waiters = lock(&self.pending).remove(&key);
                if let Some(waiters) = waiters {
                    for waiter in waiters {
                        // anyhow errors are not Clone: re-render for each waiter.
                        let shared = match &result {
                            Ok(relay) => Ok(relay.clone()),
                            Err(error) => Err(anyhow::anyhow!("{error:#}")),
                        };
                        let _ = waiter.send(shared);
                    }
                }
                result
            }
        }
    }

    fn start_relay(self: &Arc<Self>, key: String, transport: Box<dyn mux::Transport>) -> Arc<Relay> {
        let transport: Arc<dyn mux::Transport> = Arc::from(transport);
        let cancel = CancellationToken::new();
        let mux = mux::Mux::start(
            transport.clone(),
            Arc::new(Refused),
            true,
            cancel.child_token(),
        );
        let relay = Arc::new(Relay {
            mux: mux.clone(),
            cancel: cancel.clone(),
        });
        lock(&self.relays).insert(key.clone(), relay.clone());
        // The janitor drops the slot (and with it the WebSocket) once the
        // mux ends — cancelled here, closed by the engine, or failed.
        let inner = self.clone();
        self.runtime.spawn(async move {
            mux.closed().await;
            lock(&inner.relays).remove(&key);
        });
        relay
    }

    /// Serve one accepted HTTP request: route by Host (origin-form, what
    /// browsers that resolve `*.localhost` themselves send, and what arrives
    /// inside a CONNECT tunnel), by authority (absolute-form, the
    /// WebKitGTK-proxy shape), or tunnel a CONNECT to this listener —
    /// the engine proxy's three entry shapes, with its header hygiene.
    async fn request(
        self: &Arc<Self>,
        port: u16,
        mut request: Request<Incoming>,
    ) -> anyhow::Result<Response<Body>> {
        if request.method() == Method::CONNECT {
            return self.connect_loopback(port, request).await;
        }
        let (host, host_port) = if request.uri().scheme().is_some() {
            // WebKitGTK's proxy mode sends the full target as an absolute
            // URI, so `*.localhost` never needs to resolve: the project
            // server gets origin-form with the Host it expects.
            let authority = request
                .uri()
                .authority()
                .ok_or_else(|| anyhow::anyhow!("preview request has no authority"))?
                .clone();
            let port = authority.port_u16().unwrap_or(80);
            let host = format!("{}:{port}", authority.host());
            let path = request
                .uri()
                .path_and_query()
                .cloned()
                .unwrap_or_else(|| hyper::http::uri::PathAndQuery::from_static("/"));
            *request.uri_mut() = Uri::builder().path_and_query(path).build()?;
            request
                .headers_mut()
                .insert(header::HOST, HeaderValue::from_str(&host)?);
            (host, port)
        } else {
            let host = request
                .headers()
                .get(header::HOST)
                .and_then(|value| value.to_str().ok())
                .ok_or_else(|| anyhow::anyhow!("missing preview hostname"))?
                .to_ascii_lowercase();
            let port = host
                .parse::<hyper::http::uri::Authority>()
                .ok()
                .and_then(|authority| authority.port_u16())
                .unwrap_or(80);
            (host, port)
        };
        self.forward(port, request, host, host_port).await
    }

    async fn forward(
        self: &Arc<Self>,
        port: u16,
        mut request: Request<Incoming>,
        host: String,
        host_port: u16,
    ) -> anyhow::Result<Response<Body>> {
        anyhow::ensure!(host_port == port, "incorrect preview proxy port");
        let hostname = host
            .parse::<hyper::http::uri::Authority>()
            .ok()
            .map(|authority| authority.host().to_ascii_lowercase())
            .unwrap_or_else(|| host.clone());
        let route = match lock(&self.state).table.route(&hostname) {
            Some(route) => route,
            None => anyhow::bail!("{}", unknown_host_message(&hostname)),
        };
        let upgrade = request
            .headers()
            .get(header::UPGRADE)
            .is_some_and(|value| value.as_bytes().eq_ignore_ascii_case(b"websocket"))
            && http::connection_has(request.headers(), "upgrade");
        let tunnel_permit = upgrade.then(http::tunnel_slot).transpose()?;
        let browser_upgrade = upgrade.then(|| hyper::upgrade::on(&mut request));
        let upstream_host = format!("localhost:{}", route.service.port);
        let preview_origin = format!("http://{host}");
        http::forward_request_headers(request.headers_mut(), &host, upgrade)?;
        let stream = self.open_stream(&route, upgrade).await?;
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(stream)).await?;
        let connection = tokio::spawn(async move {
            let _ = connection.with_upgrades().await;
        });
        // Aborting a disconnected browser's pending request tears down its
        // relay stream too, even when no response headers arrive.
        let guard = AbortOnDrop(Some(connection.abort_handle()));
        let mut response = sender.send_request(request).await?;
        if response.status() == hyper::StatusCode::SWITCHING_PROTOCOLS {
            anyhow::ensure!(upgrade, "unsolicited server upgrade");
            let server_upgrade = hyper::upgrade::on(&mut response);
            let browser_upgrade = browser_upgrade.unwrap();
            let run = lock(&self.state).run.as_ref().map(|run| run.token.clone());
            tokio::spawn(async move {
                let _guard = guard;
                let _permit = tunnel_permit;
                tokio::select! { _ = run_cancelled(run) => {}, _ = async {
                    if let Ok((browser, server)) = tokio::try_join!(browser_upgrade, server_upgrade) {
                        let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(browser), &mut TokioIo::new(server)).await;
                    }
                } => {} }
            });
        } else {
            let (mut parts, body) = response.into_parts();
            http::forward_response_headers(&mut parts.headers, &upstream_host, &preview_origin);
            return Ok(Response::from_parts(
                parts,
                GuardedBody { body, guard }.boxed_unsync(),
            ));
        }
        http::strip_hop_headers(response.headers_mut(), true);
        Ok(response.map(http_body_util::BodyExt::boxed_unsync))
    }

    /// WebKit's per-domain CONNECT registration reaches this same loopback
    /// HTTP listener without resolving `*.localhost` at all: targets are
    /// table names at our port only, and the tunnel lands back on the
    /// origin-form handling above.
    async fn connect_loopback(
        self: &Arc<Self>,
        port: u16,
        mut request: Request<Incoming>,
    ) -> anyhow::Result<Response<Body>> {
        let authority = request
            .uri()
            .authority()
            .ok_or_else(|| anyhow::anyhow!("invalid preview CONNECT"))?;
        let host = authority.host().to_ascii_lowercase();
        anyhow::ensure!(
            authority.port_u16() == Some(port)
                && lock(&self.state).table.route(&host).is_some(),
            "unknown preview CONNECT target"
        );
        let permit = http::tunnel_slot()?;
        let mut socket = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await?;
        let upgrade = hyper::upgrade::on(&mut request);
        let run = lock(&self.state).run.as_ref().map(|run| run.token.clone());
        tokio::spawn(async move {
            let _permit = permit;
            tokio::select! { _ = run_cancelled(run) => {}, _ = async {
                if let Ok(browser) = upgrade.await {
                    let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(browser), &mut socket).await;
                }
            } => {} }
        });
        Ok(Response::new(
            http_body_util::Full::new(bytes::Bytes::new())
                .map_err(|never: std::convert::Infallible| match never {})
                .boxed_unsync(),
        ))
    }

    /// One mux stream per accepted HTTP connection, on the (shared) relay
    /// for that (engine, chat, service).
    async fn open_stream(self: &Arc<Self>, route: &Route, websocket: bool) -> anyhow::Result<mux::Stream> {
        let chat = route
            .service
            .chat_id
            .as_deref()
            .ok_or_else(|| anyhow::anyhow!("preview service has no chat attribution"))?;
        let relay = self.relay(&route.engine, chat, &route.service.id).await?;
        relay.mux.open(&route.service.id, websocket).await
    }
}

/// Await a run token, or never when this tunnel outlives its run's bookkeeping.
async fn run_cancelled(run: Option<CancellationToken>) {
    match run {
        Some(token) => token.cancelled().await,
        None => std::future::pending().await,
    }
}

fn relay_key(engine: &str, route: &Route) -> String {
    relay_key_raw(
        engine,
        route.service.chat_id.as_deref().unwrap_or_default(),
        &route.service.id,
    )
}

fn relay_key_raw(engine: &str, chat: &str, service: &str) -> String {
    format!("{engine}\u{0}{chat}\u{0}{service}")
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}

fn unknown_host_message(hostname: &str) -> &'static str {
    if hostname.ends_with(".localhost") {
        // The existing "not running" shape: a stale hostname degrades
        // exactly like the engine's own proxy answers it.
        "This preview is not running. Start the project's dev server and try again."
    } else {
        "This address is not a Roboco preview; open external sites in your default browser."
    }
}

impl Table {
    fn ingest(
        &mut self,
        engine: &str,
        services: Vec<PreviewService>,
        labels_file: Option<&std::path::Path>,
    ) -> anyhow::Result<Vec<PreviewService>> {
        anyhow::ensure!(services.len() <= 256, "too many remote preview services");
        let mut ids = HashSet::new();
        let mut relayable: Vec<PreviewService> = services
            .into_iter()
            .filter(|service| service.chat_id.is_some())
            .collect();
        anyhow::ensure!(
            relayable.iter().all(|service| {
                valid_hostname(&service.hostname)
                    && !service.id.is_empty()
                    && service.id.len() <= 128
                    && ids.insert(service.id.clone())
                    && service.project_cwd.len() <= 4096
                    && service.name.len() <= 128
                    && service.chat_id.as_ref().is_none_or(|chat| chat.len() <= 128)
            }),
            "invalid remote preview advertisement"
        );
        if relayable.is_empty() {
            self.engines.remove(engine);
            self.labels.remove(engine);
            self.rebuild_routes();
            return Ok(Vec::new());
        }
        // The dormant set_remote label semantics: a device name another
        // ingested engine already claimed gets a persistent distinct label,
        // so hostnames stay unique and URLs stay stable across restarts.
        let base = relayable[0]
            .hostname
            .split('.')
            .next()
            .unwrap_or("remote")
            .to_owned();
        let used: HashSet<String> = self
            .labels
            .iter()
            .filter(|(id, _)| id.as_str() != engine)
            .map(|(_, label)| label.clone())
            .collect();
        if !self.labels.contains_key(engine) {
            let assigned = allocate_label(&base, &used);
            self.labels.insert(engine.to_owned(), assigned);
            if let Some(file) = labels_file
                && let Err(error) = persist_labels(&self.labels, file)
            {
                warn!(%error, "could not persist preview relay labels");
            }
        }
        let label = self.labels[engine].clone();
        for service in &mut relayable {
            if let Some((_, rest)) = service.hostname.split_once('.') {
                service.hostname = format!("{label}.{rest}");
            }
        }
        self.engines.insert(engine.to_owned(), relayable.clone());
        self.rebuild_routes();
        Ok(relayable)
    }

    fn remove(&mut self, engine: &str) {
        self.engines.remove(engine);
        self.labels.remove(engine);
        self.rebuild_routes();
    }

    fn rebuild_routes(&mut self) {
        self.routes.clear();
        for (engine, services) in &self.engines {
            for service in services {
                self.routes.insert(
                    service.hostname.clone(),
                    Route {
                        engine: engine.clone(),
                        service: service.clone(),
                    },
                );
            }
        }
    }

    fn route(&self, hostname: &str) -> Option<Route> {
        self.routes.get(hostname).cloned()
    }
}

fn allocate_label(base: &str, used: &HashSet<String>) -> String {
    let base = &base[..base.len().min(58)];
    let base = base.trim_end_matches('-');
    if !used.contains(base) {
        return base.to_owned();
    }
    for index in 2.. {
        let label = format!("{}-{index}", &base[..base.len().min(48)]);
        if !used.contains(&label) {
            return label;
        }
    }
    unreachable!()
}

fn persist_labels(labels: &HashMap<String, String>, file: &std::path::Path) -> anyhow::Result<()> {
    if let Some(parent) = file.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = file.with_extension(format!("{}.tmp", std::process::id()));
    std::fs::write(&temporary, serde_json::to_vec_pretty(labels)?)?;
    std::fs::rename(temporary, file)?;
    Ok(())
}

/// The same three-label `.localhost` shape the engine's catalog validates
/// (the hostname grammar is shared, so a remote advertisement can never name
/// an arbitrary host).
fn valid_hostname(host: &str) -> bool {
    let labels: Vec<&str> = host.split('.').collect();
    labels.len() == 3
        && labels[2] == "localhost"
        && labels[..2].iter().all(|s| {
            !s.is_empty()
                && s.len() <= 63
                && !s.starts_with('-')
                && !s.ends_with('-')
                && s.bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        })
}

/// Bind both loopback families: `.localhost` commonly resolves to ::1 first.
/// Fail explicitly if another application owns the stable port.
async fn serve(
    inner: Arc<Inner>,
    port: u16,
    stop: CancellationToken,
) -> anyhow::Result<(u16, Vec<tokio::task::JoinHandle<()>>)> {
    let v4 = TcpListener::bind((Ipv4Addr::LOCALHOST, port)).await?;
    let port = v4.local_addr()?.port();
    let mut sockets = vec![v4];
    match TcpListener::bind((Ipv6Addr::LOCALHOST, port)).await {
        Ok(v6) => sockets.push(v6),
        Err(error)
            if matches!(
                error.raw_os_error(),
                Some(libc::EAFNOSUPPORT | libc::EPROTONOSUPPORT | libc::EADDRNOTAVAIL)
            ) => {}
        Err(error) => return Err(error.into()),
    }
    let slots = Arc::new(tokio::sync::Semaphore::new(128));
    let mut listeners = Vec::new();
    for listener in sockets {
        let inner = inner.clone();
        let stop = stop.clone();
        let slots = slots.clone();
        listeners.push(tokio::spawn(async move {
            loop {
                let accepted = tokio::select! { _ = stop.cancelled() => break, result = listener.accept() => result };
                let Ok((socket, _)) = accepted else { break };
                let Ok(permit) = slots.clone().try_acquire_owned() else { drop(socket); continue };
                let inner = inner.clone();
                let stop = stop.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let service = hyper::service::service_fn(move |request| {
                        let inner = inner.clone();
                        let port = port;
                        async move {
                            Ok::<_, std::convert::Infallible>(
                                inner.request(port, request).await.unwrap_or_else(|e| {
                                    http::error(e.to_string())
                                }),
                            )
                        }
                    });
                    let mut builder = hyper::server::conn::http1::Builder::new();
                    // `half_close(true)`: a client that shuts down its write
                    // side after a complete request (the mux END idiom's
                    // shape; some HTTP clients do it deliberately) still
                    // gets its response — hyper 1's default would treat the
                    // read EOF as IncompleteMessage and abort instead.
                    builder
                        .half_close(true)
                        .timer(hyper_util::rt::TokioTimer::new())
                        .header_read_timeout(Duration::from_secs(15));
                    tokio::select! { _ = stop.cancelled() => {}, _ = builder.serve_connection(TokioIo::new(socket), service).with_upgrades() => {} }
                });
            }
        }));
    }
    Ok((port, listeners))
}
