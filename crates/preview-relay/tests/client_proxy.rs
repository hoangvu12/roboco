//! The client proxy against a test relay speaking the engine's exact wire
//! shape (ticket-03 idioms, no `roboco-engine` dependency): a
//! Bearer-gated `/preview-relay/{chatId}/{serviceId}` WebSocket upgrade that
//! runs the mux with `initiator: false` and a connector to a loopback dev
//! server. Covers hostname routing from a snapshot (the client-side
//! `set_remote` semantics), the origin/absolute/CONNECT entry shapes, the
//! transport idioms, the bind-conflict error, and the lazy/idle lifecycle.

use std::{convert::Infallible, sync::Arc, time::Duration};

use bytes::Bytes;
use http_body_util::{BodyExt, Full, combinators::UnsyncBoxBody};
use hyper::{Request, Response, body::Incoming, server::conn::http1, service::service_fn};
use hyper_util::rt::TokioIo;
use roboco_preview_relay::{
    client::{ClientProxy, ClientProxyOptions, RelayDialer},
    mux::{self, BoxIo, Connector},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_tungstenite::tungstenite::Message;
use tokio_util::sync::CancellationToken;

type Body = UnsyncBoxBody<Bytes, hyper::Error>;

fn full(value: impl Into<Bytes>) -> Body {
    Full::new(value.into())
        .map_err(|never: Infallible| match never {})
        .boxed_unsync()
}

const LARGE: usize = 2 * 1024 * 1024;

async fn dev_server(stop: CancellationToken) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let socket = tokio::select! {
                _ = stop.cancelled() => break,
                socket = listener.accept() => socket.unwrap().0,
            };
            let dev_port = port;
            tokio::spawn(async move {
                // `half_close(true)`: relayed requests half-close their write
                // side after the request body (the mux END idiom), and hyper
                // 1's default is to treat a mid-request client EOF as
                // IncompleteMessage — Node-style dev servers accept it.
                let service = service_fn(move |request: Request<Incoming>| {
                    let dev_port = dev_port;
                    async move {
                        Ok::<_, Infallible>(dev_route(request, dev_port).await)
                    }
                });
                let _ = http1::Builder::new()
                    .half_close(true)
                    .serve_connection(TokioIo::new(socket), service)
                    .await;
            });
        }
    });
    port
}

async fn dev_route(request: Request<Incoming>, dev_port: u16) -> Response<Body> {
    match request.uri().path() {
        "/" => Response::new(full("relay ok")),
        "/large" => Response::new(full(Bytes::from(vec![b'x'; LARGE]))),
        "/who" => {
            let host = request
                .headers()
                .get("host")
                .and_then(|h| h.to_str().ok())
                .unwrap_or_default();
            let forwarded = request
                .headers()
                .get("x-forwarded-host")
                .and_then(|h| h.to_str().ok())
                .unwrap_or_default();
            Response::new(full(format!("host={host} forwarded={forwarded}")))
        }
        "/redirect" => Response::builder()
            .status(hyper::StatusCode::FOUND)
            // The localhost redirect the engine proxy rewrites back to the
            // preview origin: an absolute `http://localhost:{port}` target.
            .header("location", format!("http://localhost:{dev_port}/next"))
            .body(full("redirecting"))
            .unwrap(),
        _ => Response::builder()
            .status(hyper::StatusCode::NOT_FOUND)
            .body(full("not found"))
            .unwrap(),
    }
}

/// The engine's half of the relay, as ticket 03 landed it: a Bearer-gated
/// upgrade on `/preview-relay/{chatId}/{serviceId}`, then
/// `Mux::start(transport, connector, initiator: false)` with a connector
/// scoped to the path's service.
struct TestEngine {
    chat: String,
    service: String,
    credential: String,
    dev_port: u16,
    stop: CancellationToken,
}

impl TestEngine {
    async fn serve(self, listener: TcpListener) {
        loop {
            let socket = tokio::select! {
                _ = self.stop.cancelled() => break,
                socket = listener.accept() => socket.unwrap().0,
            };
            let engine = self.clone();
            tokio::spawn(async move {
                let socket = socket;
                let engine = engine.clone();
                // The gate: Bearer checked before the upgrade, like the RPC
                // socket; a wrong scope (chat/service) is refused at the
                // handshake so the client's dial fails.
                let scoped = engine.clone();
                // The large `Err` (ErrorResponse) is the shape tungstenite's
                // Callback trait requires; it can't be boxed away here.
                #[allow(clippy::result_large_err)]
                let callback = move |request: &tokio_tungstenite::tungstenite::handshake::server::Request,
                                     response: tokio_tungstenite::tungstenite::handshake::server::Response|
                      -> Result<
                    tokio_tungstenite::tungstenite::handshake::server::Response,
                    tokio_tungstenite::tungstenite::handshake::server::ErrorResponse,
                > {
                    use tokio_tungstenite::tungstenite::http::StatusCode;
                    let path = request.uri().path();
                    let expected =
                        format!("/preview-relay/{}/{}", scoped.chat, scoped.service);
                    let bearer = request
                        .headers()
                        .get("authorization")
                        .and_then(|value| value.to_str().ok())
                        .and_then(|value| value.strip_prefix("Bearer "));
                    if path != expected || bearer != Some(scoped.credential.as_str()) {
                        let mut refused =
                            tokio_tungstenite::tungstenite::handshake::server::ErrorResponse::new(
                                Some("unauthorized".to_string()),
                            );
                        *refused.status_mut() = StatusCode::UNAUTHORIZED;
                        return Err(refused);
                    }
                    let _ = request;
                    Ok(response)
                };
                let Ok(ws) = tokio_tungstenite::accept_hdr_async(socket, callback).await
                else {
                    return;
                };
                let transport = Arc::new(
                    roboco_preview_relay::client::WebSocketRelayStream::new(ws),
                );
                let connector = Arc::new(Scoped {
                    service: engine.service.to_owned(),
                    dev_port: engine.dev_port,
                });
                let mux =
                    mux::Mux::start(transport, connector, false, engine.stop.child_token());
                mux.closed().await;
            });
        }
    }
}

impl Clone for TestEngine {
    fn clone(&self) -> Self {
        Self {
            chat: self.chat.clone(),
            service: self.service.clone(),
            credential: self.credential.clone(),
            dev_port: self.dev_port,
            stop: self.stop.clone(),
        }
    }
}

/// The engine's scoped connector: every OPEN resolves only the path's
/// service id, dialing the loopback dev server.
struct Scoped {
    service: String,
    dev_port: u16,
}

#[async_trait::async_trait]
impl Connector for Scoped {
    async fn connect(&self, service: &str) -> anyhow::Result<BoxIo> {
        anyhow::ensure!(service == self.service, "service outside relay scope");
        Ok(Box::new(
            TcpStream::connect(("127.0.0.1", self.dev_port)).await?,
        ))
    }
}

/// The client's dialer: the registry's shape — a ws endpoint plus a session
/// credential — aimed at the relay route.
#[derive(Clone)]
struct TestDialer {
    base: String,
    credential: String,
}

#[async_trait::async_trait]
impl RelayDialer for TestDialer {
    async fn dial(
        &self,
        _engine: &str,
        chat: &str,
        service: &str,
    ) -> anyhow::Result<Box<dyn mux::Transport>> {
        let stream = roboco_preview_relay::client::dial_relay(
            &self.base,
            Some(&self.credential),
            chat,
            service,
        )
        .await?;
        Ok(Box::new(stream))
    }
}

fn service(id: &str, hostname: &str, chat: &str, port: u16) -> roboco_proto::PreviewService {
    roboco_proto::PreviewService {
        id: id.into(),
        project_id: "project".into(),
        project_name: "app".into(),
        project_cwd: "/work/app".into(),
        device_id: "device".into(),
        device_name: "MacBook".into(),
        hostname: hostname.into(),
        name: "Vite".into(),
        port,
        pid: 7,
        cwd: "/work/app".into(),
        started_at: 1,
        roboco_owned: true,
        chat_id: Some(chat.into()),
    }
}

struct Fixture {
    proxy: ClientProxy,
    port: u16,
    stop: CancellationToken,
    hostname: String,
    service_id: String,
}

async fn fixture(idle: Duration) -> Fixture {
    let stop = CancellationToken::new();
    let dev_port = dev_server(stop.clone()).await;
    let service = service("srv-1", "macbook.app.localhost", "chat-1", dev_port);
    // The relay's service id is the catalog id the engine mints.
    let relay_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("ws://{}", relay_listener.local_addr().unwrap());
    tokio::spawn(
        TestEngine {
            chat: "chat-1".into(),
            service: service.id.clone(),
            credential: "credential".into(),
            dev_port,
            stop: stop.clone(),
        }
        .serve(relay_listener),
    );
    let dialer = TestDialer {
        base,
        credential: "credential".into(),
    };
    let proxy = ClientProxy::new(
        Arc::new(dialer),
        ClientProxyOptions {
            port: 0,
            idle,
            labels_file: None,
        },
        tokio::runtime::Handle::current(),
    );
    let effective = proxy.ingest("engine-1", vec![service.clone()]).unwrap();
    assert_eq!(effective.len(), 1);
    assert_eq!(effective[0].hostname, "macbook.app.localhost");
    proxy.watch_engine("engine-1", true);
    let mut status = proxy.subscribe();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        if proxy.status().port.is_some() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "listener never bound");
        let _ = tokio::time::timeout(Duration::from_millis(100), status.changed()).await;
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let port = proxy.status().port.unwrap();
    Fixture {
        proxy,
        port,
        stop,
        hostname: "macbook.app.localhost".into(),
        service_id: service.id,
    }
}

/// One origin-form HTTP round-trip through the client proxy, hand-rolled
/// like the engine-side tests: request with the preview Host, response read
/// to EOF.
async fn get(port: u16, hostname: &str, path: &str) -> (u16, String) {
    let mut socket = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    socket
        .write_all(
            format!("GET {path} HTTP/1.1\r\nhost: {hostname}:{port}\r\nconnection: close\r\n\r\n")
                .as_bytes(),
        )
        .await
        .unwrap();
    socket.shutdown().await.unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.unwrap();
    let head_end = response
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("http response headers");
    let head = String::from_utf8_lossy(&response[..head_end]).into_owned();
    let status: u16 = head
        .lines()
        .next()
        .unwrap()
        .split_whitespace()
        .nth(1)
        .unwrap()
        .parse()
        .unwrap();
    (status, String::from_utf8_lossy(&response[head_end + 4..]).into_owned())
}

/// An absolute-form request — the WebKitGTK proxy-mode shape: the full
/// target rides the request line, and no `*.localhost` synthesis is needed.
async fn get_absolute(port: u16, hostname: &str, path: &str) -> (u16, String) {
    let mut socket = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    socket
        .write_all(
            format!("GET http://{hostname}:{port}{path} HTTP/1.1\r\nhost: {hostname}:{port}\r\nconnection: close\r\n\r\n")
                .as_bytes(),
        )
        .await
        .unwrap();
    socket.shutdown().await.unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.unwrap();
    let head_end = response
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("http response headers");
    let head = String::from_utf8_lossy(&response[..head_end]).into_owned();
    let status: u16 = head
        .lines()
        .next()
        .unwrap()
        .split_whitespace()
        .nth(1)
        .unwrap()
        .parse()
        .unwrap();
    (status, String::from_utf8_lossy(&response[head_end + 4..]).into_owned())
}

#[tokio::test]
async fn hostnames_route_from_a_snapshot_and_relay_http() {
    let fixture = fixture(Duration::from_secs(60)).await;

    // The ingested service routes by its hostname, to its own engine scope.
    assert_eq!(
        fixture
            .proxy
            .by_hostname(&fixture.hostname)
            .unwrap()
            .id,
        fixture.service_id
    );

    // The origin-form shape (a browser that resolves *.localhost itself).
    let (status, body) = get(fixture.port, &fixture.hostname, "/").await;
    assert_eq!(status, 200, "body was {body}");
    assert_eq!(body, "relay ok");

    // Large body through the mux windows, byte-exact.
    let (status, body) = get(fixture.port, &fixture.hostname, "/large").await;
    assert_eq!(status, 200);
    assert_eq!(body.len(), LARGE);
    assert!(body.bytes().all(|byte| byte == b'x'));

    // The engine proxy's header hygiene: Host and X-Forwarded-Host preserved.
    let (status, body) = get(fixture.port, &fixture.hostname, "/who").await;
    assert_eq!(status, 200);
    assert_eq!(
        body,
        format!(
            "host={}:{} forwarded={}:{}",
            fixture.hostname, fixture.port, fixture.hostname, fixture.port
        )
    );

    // The absolute-form shape (WebKitGTK's proxy mode).
    let (status, body) = get_absolute(fixture.port, &fixture.hostname, "/").await;
    assert_eq!(status, 200);
    assert_eq!(body, "relay ok");

    // A localhost redirect rewrites back to the preview origin.
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).await.unwrap();
    socket
        .write_all(
            format!(
                "GET /redirect HTTP/1.1\r\nhost: {}:{}\r\nconnection: close\r\n\r\n",
                fixture.hostname, fixture.port
            )
            .as_bytes(),
        )
        .await
        .unwrap();
    socket.shutdown().await.unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.unwrap();
    let head = String::from_utf8_lossy(&response).into_owned();
    assert!(
        head.contains(&format!("location: http://{}:{}/next", fixture.hostname, fixture.port)),
        "redirect was not rewritten: {head}"
    );

    // A stale hostname 502s with the existing "not running" shape.
    let (status, body) = get(fixture.port, "gone.app.localhost", "/").await;
    assert_eq!(status, 502);
    assert_eq!(
        body,
        "This preview is not running. Start the project's dev server and try again."
    );

    // A non-preview host (an external site that reached the proxy) answers
    // with the external-browser fallback message, not a relay attempt.
    let (status, body) = get(fixture.port, "example.com", "/").await;
    assert_eq!(status, 502);
    assert_eq!(
        body,
        "This address is not a Roboco preview; open external sites in your default browser."
    );

    // The hostname routes only while the snapshot lists it.
    fixture
        .proxy
        .ingest("engine-1", Vec::new())
        .unwrap();
    let (status, _) = get(fixture.port, &fixture.hostname, "/").await;
    assert_eq!(status, 502, "the service must stop routing after removal");

    fixture.proxy.stop().await;
    fixture.stop.cancel();
}

#[tokio::test]
async fn connect_tunnels_reach_the_same_routing() {
    let fixture = fixture(Duration::from_secs(60)).await;
    // The WebKit per-domain CONNECT registration shape: a tunnel to this
    // listener, then origin-form HTTP inside it.
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).await.unwrap();
    socket
        .write_all(
            format!(
                "CONNECT {}:{} HTTP/1.1\r\nhost: {}:{}\r\n\r\n",
                fixture.hostname, fixture.port, fixture.hostname, fixture.port
            )
            .as_bytes(),
        )
        .await
        .unwrap();
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        socket.read_exact(&mut byte).await.unwrap();
        head.push(byte[0]);
    }
    let head = String::from_utf8_lossy(&head).into_owned();
    assert!(
        head.starts_with("HTTP/1.1 200"),
        "CONNECT must succeed, got: {head}"
    );
    socket
        .write_all(
            format!(
                "GET / HTTP/1.1\r\nhost: {}:{}\r\nconnection: close\r\n\r\n",
                fixture.hostname, fixture.port
            )
            .as_bytes(),
        )
        .await
        .unwrap();
    socket.shutdown().await.unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.unwrap();
    let response = String::from_utf8_lossy(&response).into_owned();
    assert!(response.contains("relay ok"), "tunnel response: {response}");

    // An unknown CONNECT target is refused, exactly like the engine proxy.
    let mut socket = TcpStream::connect(("127.0.0.1", fixture.port)).await.unwrap();
    socket
        .write_all(b"CONNECT example.com:80 HTTP/1.1\r\n\r\n")
        .await
        .unwrap();
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.unwrap();
    let response = String::from_utf8_lossy(&response).into_owned();
    assert!(
        response.contains("502"),
        "unknown CONNECT target must be refused: {response}"
    );

    fixture.proxy.stop().await;
    fixture.stop.cancel();
}

#[tokio::test]
async fn colliding_device_names_get_distinct_persistent_labels() {
    let dir = tempfile::tempdir().unwrap();
    let labels = dir.path().join("labels.json");
    let dialer = TestDialer {
        base: "ws://127.0.0.1:1".into(),
        credential: "credential".into(),
    };
    let proxy = ClientProxy::new(
        Arc::new(dialer.clone()),
        ClientProxyOptions {
            port: 0,
            idle: Duration::from_secs(60),
            labels_file: Some(labels.clone()),
        },
        tokio::runtime::Handle::current(),
    );
    let first = service("a", "macbook.app.localhost", "chat-1", 1);
    let second = service("b", "macbook.app.localhost", "chat-2", 2);
    let effective = proxy.ingest("engine-1", vec![first]).unwrap();
    assert_eq!(effective[0].hostname, "macbook.app.localhost");
    let effective = proxy.ingest("engine-2", vec![second]).unwrap();
    assert_eq!(
        effective[0].hostname, "macbook-2.app.localhost",
        "a colliding device name gets a distinct label"
    );
    // Both route, to their own engines.
    assert!(
        proxy
            .by_hostname("macbook.app.localhost")
            .is_some_and(|service| service.id == "a")
    );
    assert!(
        proxy
            .by_hostname("macbook-2.app.localhost")
            .is_some_and(|service| service.id == "b")
    );
    // Invalid advertisements are refused, like the engine's set_remote.
    let bad = service("c", "app.localhost", "chat-3", 3);
    assert!(proxy.ingest("engine-3", vec![bad]).is_err());
    let too_many = (0..257)
        .map(|index| service(&format!("s{index}"), "x.app.localhost", "chat", 4))
        .collect();
    assert!(proxy.ingest("engine-3", too_many).is_err());
    // Services without a chat attribution cannot be relayed at all.
    let mut chatless = service("d", "macbook.api.localhost", "chat-1", 5);
    chatless.chat_id = None;
    let effective = proxy.ingest("engine-4", vec![chatless]).unwrap();
    assert!(effective.is_empty());
    // Labels persist, so the second engine keeps its URL across a restart.
    let proxy = ClientProxy::new(
        Arc::new(dialer),
        ClientProxyOptions {
            port: 0,
            idle: Duration::from_secs(60),
            labels_file: Some(labels),
        },
        tokio::runtime::Handle::current(),
    );
    let second = service("b", "macbook.app.localhost", "chat-2", 2);
    let effective = proxy.ingest("engine-2", vec![second]).unwrap();
    assert_eq!(effective[0].hostname, "macbook-2.app.localhost");
}

#[tokio::test]
async fn a_busy_port_surfaces_the_inline_error_and_never_changes_the_url() {
    // A port another application owns: the bind fails, the error is
    // surfaced inline (the 7331 failure mode), and the port never changes —
    // the URL the browser addresses is unaffected.
    let squat = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = squat.local_addr().unwrap().port();
    let dialer = TestDialer {
        base: "ws://127.0.0.1:1".into(),
        credential: "credential".into(),
    };
    let proxy = ClientProxy::new(
        Arc::new(dialer),
        ClientProxyOptions {
            port,
            idle: Duration::from_secs(60),
            labels_file: None,
        },
        tokio::runtime::Handle::current(),
    );
    proxy.watch_engine("engine-1", true);
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        let status = proxy.status();
        if let Some(error) = status.error {
            assert!(
                error.contains(&format!("Remote previews could not listen on port {port}")),
                "the bind failure must carry the 7331-style message"
            );
            assert!(status.port.is_none(), "a failed bind never reports a port");
            break;
        }
        assert!(std::time::Instant::now() < deadline, "bind error never surfaced");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    proxy.watch_engine("engine-1", false);
    drop(squat);
    proxy.stop().await;
}

#[tokio::test]
async fn the_listener_binds_only_while_watched_and_idles_out() {
    let fixture = fixture(Duration::from_millis(150)).await;
    assert!(fixture.proxy.status().port.is_some());
    // The watcher leaves: after the idle window, the listener unbinds.
    fixture.proxy.watch_engine("engine-1", false);
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        if fixture.proxy.status().port.is_none() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "listener never idled out");
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    // A watcher returning rebinds — on the same address it had before.
    fixture.proxy.watch_engine("engine-1", true);
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        if fixture.proxy.status().port.is_some() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "listener never rebound");
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
    assert_eq!(fixture.proxy.status().port, Some(fixture.port));
    fixture.proxy.stop().await;
    fixture.stop.cancel();
}

#[tokio::test]
async fn an_engine_that_dies_serves_the_not_running_shape() {
    let fixture = fixture(Duration::from_secs(60)).await;
    let (status, _) = get(fixture.port, &fixture.hostname, "/").await;
    assert_eq!(status, 200);
    // The engine goes away: the relay dial fails, and the proxy answers the
    // "not running" shape instead of hanging.
    fixture.stop.cancel();
    tokio::time::sleep(Duration::from_millis(100)).await;
    let (status, body) =
        tokio::time::timeout(Duration::from_secs(20), get(fixture.port, &fixture.hostname, "/"))
            .await
            .expect("the request must fail promptly");
    assert_eq!(status, 502, "body: {body}");
    fixture.proxy.stop().await;
}

#[tokio::test]
async fn a_revoked_session_is_not_a_open_proxy() {
    // The dialer's credential is refused by the engine's gate: the request
    // degrades to an error, never to an unauthenticated dial of the dev
    // server. (The full 401-then-degrade story is pinned against the real
    // engine in roboco-engine-client's tests.)
    let stop = CancellationToken::new();
    let dev_port = dev_server(stop.clone()).await;
    let service = service("srv-1", "macbook.app.localhost", "chat-1", dev_port);
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("ws://{}", listener.local_addr().unwrap());
    tokio::spawn(TestEngine {
        chat: "chat-1".into(),
        service: service.id.clone(),
        credential: "a-different-credential".into(),
        dev_port,
        stop: stop.clone(),
    }
    .serve(listener));
    let dialer = TestDialer {
        base,
        credential: "credential".into(),
    };
    let proxy = ClientProxy::new(
        Arc::new(dialer),
        ClientProxyOptions {
            port: 0,
            idle: Duration::from_secs(60),
            labels_file: None,
        },
        tokio::runtime::Handle::current(),
    );
    proxy.ingest("engine-1", vec![service]).unwrap();
    proxy.watch_engine("engine-1", true);
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        if proxy.status().port.is_some() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "listener never bound");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let port = proxy.status().port.unwrap();
    let (status, body) =
        tokio::time::timeout(Duration::from_secs(20), get(port, "macbook.app.localhost", "/"))
            .await
            .expect("the refused dial must fail promptly");
    assert_eq!(status, 502, "body: {body}");
    assert!(
        body.contains("could not reach this engine's preview relay"),
        "body: {body}"
    );
    proxy.stop().await;
    stop.cancel();
}

// Keep the Message import used by the relay's future shape compile-checked.
#[allow(dead_code)]
fn message_kind(message: Message) -> bool {
    matches!(message, Message::Binary(_))
}
