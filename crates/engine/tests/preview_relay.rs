//! The session-relay data plane against the real engine listener: the
//! `/preview-relay/{chatId}/{serviceId}` upgrade is gated by the session gate
//! exactly like the RPC socket (Bearer header or first-frame `Auth`), then
//! carries the unmodified mux frame protocol to the preview service's
//! chat-scoped connector — real HTTP round-trips to a loopback dev server,
//! with the `transport.rs` idioms (large bodies, slow readers, half-close,
//! cancellation), scoping refusals, and listener teardown cutting an open
//! relay. No RPC method is involved: the dial assertions ride the raw
//! WebSocket, the mux frames are binary, and the mux framing is untouched.
//!
//! Round-trip assertions need `discovery::same_process` to pass for the test
//! process, so they run where the scanner works (Linux/macOS). Windows gets
//! the gate, revocation and path assertions; its round-trip coverage arrives
//! with the Windows discovery ticket.
use std::{
    convert::Infallible,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use bytes::Bytes;
use futures::SinkExt;
use http_body_util::{BodyExt, Full, StreamBody, combinators::UnsyncBoxBody};
use hyper::{Request, Response, body::Incoming, server::conn::http1, service::service_fn};
use hyper_util::rt::TokioIo;
use roboco_engine::{
    EngineCore, EngineListener, EngineProfile, HarnessRegistry, pairing::PairingStore,
    preview_relay::WsTransport, serve_engine_remote_with_previews,
};
use roboco_preview::{
    discovery::Listener,
    mux::{self, BoxIo, Connector},
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::{Message, client::IntoClientRequest};
use tokio_util::sync::CancellationToken;

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;
type WsError = tokio_tungstenite::tungstenite::Error;

// ---------------------------------------------------------------------------
// The loopback dev server a relayed preview resolves to.
// ---------------------------------------------------------------------------

type Body = UnsyncBoxBody<Bytes, hyper::Error>;

fn full(value: impl Into<Bytes>) -> Body {
    Full::new(value.into())
        .map_err(|never: Infallible| match never {})
        .boxed_unsync()
}

const LARGE: usize = 2 * 1024 * 1024;

async fn dev_server(stop: CancellationToken, active: Arc<AtomicUsize>) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let socket = tokio::select! {
                _ = stop.cancelled() => break,
                socket = listener.accept() => socket.unwrap().0,
            };
            active.fetch_add(1, Ordering::SeqCst);
            let active = active.clone();
            tokio::spawn(async move {
                // Dropping it (client disconnect, cancellation) releases the
                // active count, so cancellation is observable server-side.
                let _connection = Connection(active);
                let service = service_fn(|request: Request<Incoming>| async move {
                    Ok::<_, Infallible>(dev_route(request).await)
                });
                // `half_close(true)`: relayed requests half-close their
                // write side after the request body (the mux END idiom), and
                // hyper 1's default is to treat a mid-request client EOF as
                // IncompleteMessage — Node-style dev servers accept it.
                let _ = http1::Builder::new()
                    .half_close(true)
                    .serve_connection(TokioIo::new(socket), service)
                    .await;
            });
        }
    });
    port
}

struct Connection(Arc<AtomicUsize>);
impl Drop for Connection {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

async fn dev_route(request: Request<Incoming>) -> Response<Body> {
    match request.uri().path() {
        "/" => Response::new(full("relay ok")),
        "/large" => Response::new(full(Bytes::from(vec![b'x'; LARGE]))),
        // A body that never ends: cancellation's playground.
        "/infinite" => {
            let chunks = futures::stream::unfold(0, |index| async move {
                tokio::time::sleep(Duration::from_millis(5)).await;
                Some((
                    Ok::<_, hyper::Error>(hyper::body::Frame::data(Bytes::from(vec![42; 8192]))),
                    index + 1,
                ))
            });
            Response::new(StreamBody::new(chunks).boxed_unsync())
        }
        _ => Response::builder()
            .status(hyper::StatusCode::NOT_FOUND)
            .body(full("not found"))
            .unwrap(),
    }
}

// ---------------------------------------------------------------------------
// The engine fixture: a real engine, its preview catalog seeded like
// discovery would (chat-attributed, one stale), and a paired listener.
// ---------------------------------------------------------------------------

struct Fixture {
    core: EngineCore,
    remote: EngineListener,
    base: String,
    token: String,
    session_id: String,
    /// Live service attributed to `chat-1`.
    chat_one: String,
    /// Live service attributed to `chat-2` (a foreign chat for `chat-1`).
    chat_two: String,
    /// `chat-1`'s service whose process no longer matches (stale port reuse).
    stale: String,
    active: Arc<AtomicUsize>,
}

async fn fixture(dir: &std::path::Path) -> Fixture {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let active = Arc::new(AtomicUsize::new(0));
    let dev_port = dev_server(CancellationToken::new(), active.clone()).await;
    // What discovery would hand the catalog for three chat-attributed
    // servers: chat-1's live server, chat-2's (another chat's server on the
    // same machine), and a chat-1 entry whose process no longer matches —
    // the stale-port case the connector's re-verification exists for.
    let (pid, started_at) = (std::process::id(), process_started_at(std::process::id()));
    let catalog = core.previews.catalog();
    catalog
        .replace_local(vec![
            (
                "/work/app".into(),
                listener(pid, started_at, dev_port, "chat-1"),
            ),
            (
                "/work/other".into(),
                listener(pid, started_at, dev_port + 2, "chat-2"),
            ),
            ("/work/app".into(), listener(pid, 0, dev_port + 1, "chat-1")),
        ])
        .unwrap();
    let services = catalog.local_services();
    let service = |cwd: &str, port: u16| {
        services
            .iter()
            .find(|s| s.project_cwd == cwd && s.port == port)
            .unwrap_or_else(|| panic!("no service for {cwd}:{port}"))
            .id
            .clone()
    };
    let store = PairingStore::open(dir).unwrap();
    let code = store.create_code("relay test", 300).unwrap();
    let grant = store.redeem(&code.credential, "Laptop").unwrap().unwrap();
    let (token, session_id) = (grant.credential, grant.session.id);
    let remote = serve_engine_remote_with_previews(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        Some(core.previews.clone()),
        dir,
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    Fixture {
        core,
        remote,
        base,
        token,
        session_id,
        chat_one: service("/work/app", dev_port),
        chat_two: service("/work/other", dev_port + 2),
        stale: service("/work/app", dev_port + 1),
        active,
    }
}

fn listener(pid: u32, started_at: u64, port: u16, chat: &str) -> Listener {
    Listener {
        pid,
        parent: 1,
        cwd: "/work".into(),
        args: vec!["node".into(), "server.js".into()],
        started_at,
        address: ([127, 0, 0, 1], port).into(),
        roboco_owned: true,
        origin: Some(format!("chat:{chat}")),
        chat_id: Some(chat.into()),
    }
}

/// The engine-side `same_process` input for our own pid: the scanner's
/// started_at format (ms since the epoch), computed the same way
/// `discovery::same_process` does so the re-verification passes.
#[cfg(target_os = "linux")]
fn process_started_at(pid: u32) -> u64 {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
    let (_, tail) = stat.rsplit_once(") ").unwrap();
    let started_ticks: u64 = tail.split_whitespace().nth(19).unwrap().parse().unwrap();
    let boot: u64 = std::fs::read_to_string("/proc/stat")
        .unwrap()
        .lines()
        .find_map(|line| line.strip_prefix("btime "))
        .unwrap()
        .parse()
        .unwrap();
    let ticks = unsafe { libc::sysconf(libc::_SC_CLK_TCK) }.max(1) as u64;
    boot * 1000 + started_ticks * 1000 / ticks
}

/// Windows: `0` takes `same_process`'s pid-only path (the row shape of a
/// process whose creation time could not be read), so the live entry
/// re-verifies; the stale-start-time cases stay Linux/macOS-gated.
#[cfg(windows)]
fn process_started_at(_pid: u32) -> u64 {
    0
}

#[cfg(target_os = "macos")]
fn process_started_at(pid: u32) -> u64 {
    let output = std::process::Command::new("/bin/ps")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .env("LC_ALL", "C")
        .output()
        .unwrap();
    let parts: Vec<_> = String::from_utf8_lossy(&output.stdout)
        .split_whitespace()
        .collect();
    use chrono::TimeZone;
    chrono::NaiveDateTime::parse_from_str(&parts[..5].join(" "), "%a %b %e %T %Y")
        .ok()
        .and_then(|d| chrono::Local.from_local_datetime(&d).earliest())
        .map(|d| d.timestamp_millis().max(0) as u64)
        .unwrap()
}

// ---------------------------------------------------------------------------
// The client side: dial the relay upgrade, run the mux over it.
// ---------------------------------------------------------------------------

/// The client never accepts OPENs; the relay protocol is client-initiated.
struct Refused;
#[async_trait::async_trait]
impl Connector for Refused {
    async fn connect(&self, _service: &str) -> anyhow::Result<BoxIo> {
        anyhow::bail!("the relay client never accepts OPENs")
    }
}

async fn dial(
    base: &str,
    token: Option<&str>,
    chat: &str,
    service: &str,
) -> Result<Socket, WsError> {
    let mut request = format!("{base}/preview-relay/{chat}/{service}")
        .replacen("http", "ws", 1)
        .into_client_request()
        .unwrap();
    if let Some(token) = token {
        request
            .headers_mut()
            .insert("authorization", format!("Bearer {token}").parse().unwrap());
    }
    let (ws, response) = tokio_tungstenite::connect_async(request).await?;
    assert_eq!(response.status().as_u16(), 101);
    Ok(ws)
}

async fn relay(
    base: &str,
    token: Option<&str>,
    chat: &str,
    service: &str,
) -> (mux::Mux, CancellationToken) {
    let stop = CancellationToken::new();
    let ws = dial(base, token, chat, service).await.unwrap();
    let mux = mux::Mux::start(
        Arc::new(WsTransport::new(ws)),
        Arc::new(Refused),
        true,
        stop.child_token(),
    );
    (mux, stop)
}

/// One HTTP round-trip over a mux stream: request → LocalConnector → dev
/// server → response. The write half is shut down after the request — the
/// half-close idiom — and the response is read to EOF.
async fn get(stream: mux::Stream, path: &str) -> (String, Vec<u8>) {
    let (mut read, mut write) = tokio::io::split(stream);
    write
        .write_all(
            format!("GET {path} HTTP/1.1\r\nhost: dev\r\nconnection: close\r\n\r\n").as_bytes(),
        )
        .await
        .unwrap();
    write.shutdown().await.unwrap();
    let mut response = Vec::new();
    read.read_to_end(&mut response).await.unwrap();
    let head_end = response
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("http response headers");
    let head = String::from_utf8_lossy(&response[..head_end]).into_owned();
    (
        head.lines().next().unwrap().to_owned(),
        response[head_end + 4..].to_vec(),
    )
}

async fn get_ok(mux: &mux::Mux, service: &str, path: &str) -> (String, Vec<u8>) {
    let stream = tokio::time::timeout(Duration::from_secs(10), mux.open(service, false))
        .await
        .expect("open stalled")
        .expect("service unavailable");
    tokio::time::timeout(Duration::from_secs(30), get(stream, path))
        .await
        .expect("round trip stalled")
}

async fn assert_cut(mux: &mux::Mux, what: &str) {
    tokio::time::timeout(Duration::from_secs(5), mux.closed())
        .await
        .unwrap_or_else(|_| panic!("{what} must be cut"));
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// The gate: the relay upgrade is authenticated exactly like the RPC socket.
/// A bad Bearer is refused at the handshake (401); a browser-shaped dial
/// with no Bearer gets its first frame judged — a binary frame or a wrong
/// `Auth` envelope closes the socket before any service resolves; query
/// strings stay rejected everywhere; and the route is reserved (an
/// authenticated plain GET finds 404, never the web client's SPA shell).
#[tokio::test]
async fn unauthenticated_dials_are_rejected_on_the_route() {
    let dir = tempfile::tempdir().unwrap();
    tokio::time::timeout(Duration::from_secs(60), async {
        let mut fixture = fixture(dir.path()).await;
        // A wrong credential never reaches the upgrade.
        let error = match dial(
            &fixture.base,
            Some("not-a-real-credential"),
            "chat-1",
            &fixture.chat_one,
        )
        .await
        {
            Err(error) => error,
            Ok(_) => panic!("dial with a wrong bearer must fail"),
        };
        match error {
            WsError::Http(response) => {
                assert_eq!(response.status().as_u16(), 401, "wrong bearer must 401");
            }
            other => panic!("expected an HTTP rejection, got {other:?}"),
        }
        // A browser-shaped dial (no Bearer header possible) succeeds at the
        // handshake and is judged on its first frame: a binary frame is not
        // an `Auth` envelope, so the socket is cut before any OPEN resolves.
        let mut ws = dial(&fixture.base, None, "chat-1", &fixture.chat_one)
            .await
            .unwrap();
        ws.send(Message::Binary(vec![2, 0, 0, 0, 2])).await.unwrap();
        let mux = mux::Mux::start(
            Arc::new(WsTransport::new(ws)),
            Arc::new(Refused),
            true,
            CancellationToken::new().child_token(),
        );
        assert_cut(&mux, "an unauthenticated dial").await;
        // The same dial with a wrong first-frame `Auth` envelope: same verdict.
        let mut ws = dial(&fixture.base, None, "chat-1", &fixture.chat_one)
            .await
            .unwrap();
        ws.send(Message::Text(r#"{"auth":"not-a-real-credential"}"#.into()))
            .await
            .unwrap();
        let mux = mux::Mux::start(
            Arc::new(WsTransport::new(ws)),
            Arc::new(Refused),
            true,
            CancellationToken::new().child_token(),
        );
        assert_cut(&mux, "a wrong first-frame auth").await;
        // The relay path is reserved: query strings are rejected on every
        // route (no credentials or scope in URLs), and an authenticated
        // non-upgrade request finds the route, not the SPA shell.
        let http = reqwest::Client::new();
        let response = http
            .get(format!(
                "{}/preview-relay/chat-1/{}?token=x",
                fixture.base, fixture.chat_one
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status().as_u16(), 400);
        for path in [
            "/preview-relay/chat-1".to_owned(),
            format!("/preview-relay/chat-1/{}", fixture.chat_one),
            "/preview-relay/chat-1/no-such-service".to_owned(),
        ] {
            let response = http
                .get(format!("{}{path}", fixture.base))
                .bearer_auth(&fixture.token)
                .send()
                .await
                .unwrap();
            assert_eq!(response.status().as_u16(), 404, "{path} must be 404");
        }
        fixture.remote.stop().await;
        fixture.core.shutdown().await;
    })
    .await
    .expect("gate test stalled");
}

/// A session-authenticated client streams real HTTP through the mux, with
/// the `transport.rs` idioms: large bodies, a stalled stream blocking
/// nothing else, slow readers, half-close (every request shuts down its
/// write half) and cancellation dropping the server connection. Also: the
/// browser-shaped first-frame `Auth` dial with a valid credential.
#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn authenticated_round_trips_through_the_mux() {
    let dir = tempfile::tempdir().unwrap();
    tokio::time::timeout(Duration::from_secs(60), async {
        let mut fixture = fixture(dir.path()).await;
        let (mux, stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.chat_one,
        )
        .await;

        // Small body: request → LocalConnector → dev server → response.
        let (status, body) = get_ok(&mux, &fixture.chat_one, "/").await;
        assert!(status.starts_with("HTTP/1.1 200"), "status was {status}");
        assert_eq!(body, b"relay ok");

        // Large body: 2 MiB through the mux windows, byte-exact.
        let (status, body) = get_ok(&mux, &fixture.chat_one, "/large").await;
        assert!(status.starts_with("HTTP/1.1 200"), "status was {status}");
        assert_eq!(body.len(), LARGE);
        assert!(body.iter().all(|byte| *byte == b'x'));

        // A stalled stream blocks nothing: open /large and never read it,
        // then run independent round-trips beside it, then drain it.
        let blocked = mux.open(&fixture.chat_one, false).await.unwrap();
        let (mut blocked_read, mut blocked_write) = tokio::io::split(blocked);
        blocked_write
            .write_all(b"GET /large HTTP/1.1\r\nhost: dev\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        let mut others = Vec::new();
        for number in 0..8 {
            let (mux, service) = (mux.clone(), fixture.chat_one.clone());
            others.push(tokio::spawn(async move {
                let stream =
                    tokio::time::timeout(Duration::from_secs(10), mux.open(&service, false))
                        .await
                        .expect("task open stalled")
                        .expect("task service unavailable");
                let (status, body) = get(stream, "/").await;
                assert!(status.starts_with("HTTP/1.1 200"), "{number}: {status}");
                assert_eq!(body, b"relay ok", "{number}");
            }));
        }
        for task in others {
            task.await.unwrap();
        }
        let mut drained = Vec::new();
        tokio::time::timeout(
            Duration::from_secs(30),
            blocked_read.read_to_end(&mut drained),
        )
        .await
        .expect("draining the stalled stream stalled")
        .unwrap();
        drop(blocked_write);
        assert!(drained.len() >= LARGE, "the stalled body must still arrive");
        // A slow reader still finishes: the receive window paces, never deadlocks.
        let stream = mux.open(&fixture.chat_one, false).await.unwrap();
        let (mut read, mut write) = tokio::io::split(stream);
        write
            .write_all(b"GET /large HTTP/1.1\r\nhost: dev\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        write.shutdown().await.unwrap();
        let mut slow = Vec::new();
        let deadline = tokio::time::timeout(Duration::from_secs(30), async {
            let mut chunk = [0u8; 8192];
            loop {
                let length = read.read(&mut chunk).await.unwrap();
                if length == 0 {
                    break;
                }
                slow.extend_from_slice(&chunk[..length]);
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        })
        .await;
        assert!(deadline.is_ok(), "slow reader stalled");
        assert!(slow.ends_with(&[b'x'; 64]), "slow body must be complete");

        // Cancellation: dropping an unfinished stream cuts the server
        // connection (the mux CANCEL travels both ways).
        let stream = mux.open(&fixture.chat_one, false).await.unwrap();
        let (mut read, mut write) = tokio::io::split(stream);
        write
            .write_all(b"GET /infinite HTTP/1.1\r\nhost: dev\r\nconnection: close\r\n\r\n")
            .await
            .unwrap();
        write.shutdown().await.unwrap();
        let mut chunk = [0u8; 8192];
        let received = read.read(&mut chunk).await.unwrap();
        assert!(received > 0, "the infinite body must yield some bytes");
        drop(read);
        drop(write);
        let waited = tokio::time::timeout(Duration::from_secs(10), async {
            while fixture.active.load(Ordering::SeqCst) != 0 {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await;
        assert!(
            waited.is_ok(),
            "cancellation must drop the server connection"
        );

        // The browser-shaped dial with a valid credential: first-frame
        // `Auth`, then the same binary mux frames.
        let mut ws = dial(&fixture.base, None, "chat-1", &fixture.chat_one)
            .await
            .unwrap();
        ws.send(Message::Text(format!(r#"{{"auth":"{}"}}"#, fixture.token)))
            .await
            .unwrap();
        let browser_stop = CancellationToken::new();
        let browser_mux = mux::Mux::start(
            Arc::new(WsTransport::new(ws)),
            Arc::new(Refused),
            true,
            browser_stop.child_token(),
        );
        let (status, body) = get_ok(&browser_mux, &fixture.chat_one, "/").await;
        assert!(status.starts_with("HTTP/1.1 200"));
        assert_eq!(body, b"relay ok");
        browser_stop.cancel();

        stop.cancel();
        fixture.remote.stop().await;
        fixture.core.shutdown().await;
    })
    .await
    .expect("round-trip test stalled");
}

/// The connector's scope: only the relay request's service id, only while
/// the live catalog attributes it to the relay request's chat, and only
/// while the process still matches. Foreign chat, a foreign service (even
/// one in the same catalog), a stale process and an unknown id all refuse;
/// the scoped service itself still resolves.
#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn service_and_chat_scoping_is_enforced() {
    let dir = tempfile::tempdir().unwrap();
    tokio::time::timeout(Duration::from_secs(60), async {
        let mut fixture = fixture(dir.path()).await;
        // The scoped service resolves first, so the refusals below are
        // refusals, not a broken relay.
        let (mux, stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.chat_one,
        )
        .await;
        let (_, body) = get_ok(&mux, &fixture.chat_one, "/").await;
        assert_eq!(body, b"relay ok");

        // Over the working relay: an OPEN may name only the scoped service —
        // not another chat's, not a stale one, not an unknown id.
        let outside = [
            fixture.chat_two.clone(),
            fixture.stale.clone(),
            "never-in-the-catalog".to_owned(),
        ];
        let mut refusals = Vec::new();
        for service in outside {
            let (mux, service) = (mux.clone(), service);
            refusals.push(tokio::spawn(async move {
                // The engine cancels the slot when its connector refuses the
                // OPEN (the client sees CANCEL, so `open` fails at once),
                // but they still run beside each other to keep the test
                // wall clock off the client's readiness budget.
                let refused =
                    tokio::time::timeout(Duration::from_secs(15), mux.open(&service, false))
                        .await
                        .expect("refused OPEN stalled");
                assert!(
                    refused.is_err(),
                    "OPEN for a service outside the scope must be refused"
                );
            }));
        }
        for task in refusals {
            task.await.unwrap();
        }
        stop.cancel();

        // A relay scoped to chat-1 may not resolve chat-2's service id.
        let (mux, stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.chat_two,
        )
        .await;
        let refused =
            tokio::time::timeout(Duration::from_secs(15), mux.open(&fixture.chat_two, false))
                .await
                .expect("refused OPEN stalled");
        assert!(
            refused.is_err(),
            "chat-1's relay must not resolve chat-2's service"
        );
        stop.cancel();

        // A relay scoped to chat-2 may not resolve chat-1's service id.
        let (mux, stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-2",
            &fixture.chat_one,
        )
        .await;
        let refused =
            tokio::time::timeout(Duration::from_secs(15), mux.open(&fixture.chat_one, false))
                .await
                .expect("refused OPEN stalled");
        assert!(
            refused.is_err(),
            "chat-2's relay must not resolve chat-1's service"
        );
        stop.cancel();

        // The stale service: in the catalog, attributed correctly, but the
        // process no longer matches — the connector's re-verification refuses.
        let (mux, stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.stale,
        )
        .await;
        let refused =
            tokio::time::timeout(Duration::from_secs(15), mux.open(&fixture.stale, false))
                .await
                .expect("refused OPEN stalled");
        assert!(refused.is_err(), "a stale process id must be refused");
        stop.cancel();

        fixture.remote.stop().await;
        fixture.core.shutdown().await;
    })
    .await
    .expect("scoping test stalled");
}

/// Revocation is inherited from the listener and the gate, exactly as for the
/// RPC socket: a revoked session cannot dial again (401 at the handshake),
/// and stopping the remote bind (what Settings' remote-access toggle and
/// pairing teardown run) cuts an open relay socket with every other
/// connection; a fresh dial after the cut finds nothing.
#[tokio::test]
async fn stopping_the_listener_cuts_an_open_relay() {
    let dir = tempfile::tempdir().unwrap();
    tokio::time::timeout(Duration::from_secs(60), async {
        let mut fixture = fixture(dir.path()).await;
        let (mux, _stop) = relay(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.chat_one,
        )
        .await;
        assert!(!mux.is_closed());
        // Revoking the session (the Settings action) cuts its next dial.
        assert!(
            PairingStore::open(dir.path())
                .unwrap()
                .revoke(&fixture.session_id)
                .unwrap(),
            "the fixture session must revoke"
        );
        let refused = match dial(
            &fixture.base,
            Some(&fixture.token),
            "chat-1",
            &fixture.chat_one,
        )
        .await
        {
            Err(WsError::Http(response)) => response.status().as_u16(),
            Err(_) => panic!("a revoked session must be refused at the gate"),
            Ok(_) => panic!("a revoked session must not dial the relay"),
        };
        assert_eq!(refused, 401);
        // EngineListener::stop is exactly what disabling remote access runs;
        // it cuts the open relay with every other connection.
        fixture.remote.stop().await;
        assert_cut(&mux, "listener teardown").await;
        assert!(
            dial(
                &fixture.base,
                Some(&fixture.token),
                "chat-1",
                &fixture.chat_one
            )
            .await
            .is_err(),
            "a dial after the cut must fail"
        );
        fixture.core.shutdown().await;
    })
    .await
    .expect("revocation test stalled");
}

/// Both bind flavors: the paired bind requires the credential (above); the
/// local (native IPC) bind serves the route without one — its policy is
/// unchanged — and still rejects origin-carrying browser dials.
#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn the_local_bind_serves_the_relay_with_its_native_policy() {
    let dir = tempfile::tempdir().unwrap();
    tokio::time::timeout(Duration::from_secs(60), async {
        let mut fixture = fixture(dir.path()).await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(roboco_engine::listener::serve_listener_with_previews(
            listener,
            fixture.core.rpc_service(),
            PairingStore::open(dir.path()).unwrap(),
            roboco_engine::rooms::RoomStore::open(dir.path()).unwrap(),
            fixture.core.previews.clone(),
        ));
        // No credential: the local bind's native boundary, unchanged.
        let (mux, stop) = relay(&base, None, "chat-1", &fixture.chat_one).await;
        let (_, body) = get_ok(&mux, &fixture.chat_one, "/").await;
        assert_eq!(body, b"relay ok");
        stop.cancel();
        // A session-authenticated dial is accepted on both flavors: the
        // local bind simply does not require the credential.
        let (mux, stop) = relay(&base, Some(&fixture.token), "chat-1", &fixture.chat_one).await;
        let (_, body) = get_ok(&mux, &fixture.chat_one, "/").await;
        assert_eq!(body, b"relay ok");
        stop.cancel();
        // The local bind keeps rejecting origin-carrying requests.
        let http = reqwest::Client::new();
        let response = http
            .get(format!("{base}/preview-relay/chat-1/{}", fixture.chat_one))
            .header("origin", "https://browser.example")
            .send()
            .await
            .unwrap();
        assert_eq!(response.status().as_u16(), 403);
        task.abort();
        fixture.remote.stop().await;
        fixture.core.shutdown().await;
    })
    .await
    .expect("local-bind test stalled");
}
