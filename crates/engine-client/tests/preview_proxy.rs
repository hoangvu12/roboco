//! The client preview proxy against a REAL engine (ticket 04's verification
//! budget): a paired remote engine serves the ticket-03 relay route, the
//! registry-backed proxy ingests the engine's `WatchPreviews` snapshot, binds
//! 7332 (an ephemeral port here), and relays real HTTP to a loopback dev
//! server through the session relay. No RPC method is involved on the data
//! path, the client never serves OPENs, and a busy port surfaces the inline
//! error.
//!
//! Round-trip assertions need `discovery::same_process` to pass for the test
//! process, so they run where the scanner works (Linux/macOS) — the same
//! gating as the engine-side relay suite. The bind-conflict and
//! local-engine assertions run everywhere.
use std::{convert::Infallible, sync::Arc, time::Duration};

use bytes::Bytes;
use http_body_util::{BodyExt, Full, combinators::UnsyncBoxBody};
use hyper::{Request, Response, body::Incoming, server::conn::http1, service::service_fn};
use hyper_util::rt::TokioIo;
use roboco_engine_client::preview_proxy::PreviewProxy;
use roboco_engine_client::registry::{EngineRegistry, Reconnect};
use roboco_preview::discovery::Listener;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;

type Body = UnsyncBoxBody<Bytes, hyper::Error>;

fn full(value: impl Into<Bytes>) -> Body {
    Full::new(value.into())
        .map_err(|never: Infallible| match never {})
        .boxed_unsync()
}

async fn dev_server(stop: CancellationToken) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let socket = tokio::select! {
                _ = stop.cancelled() => break,
                socket = listener.accept() => socket.unwrap().0,
            };
            tokio::spawn(async move {
                // `half_close(true)`: the mux END idiom half-closes relayed
                // requests' write side; hyper 1's default would treat that
                // client EOF as IncompleteMessage (the ticket-03 finding).
                let service = service_fn(|_request: Request<Incoming>| async move {
                    Ok::<_, Infallible>(Response::new(full("remote relay ok")))
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

fn listener(pid: u32, started_at: u64, port: u16, chat: &str) -> Listener {
    Listener {
        pid,
        parent: 1,
        cwd: "/work".into(),
        args: vec!["node".into(), "vite".into()],
        started_at,
        address: ([127, 0, 0, 1], port).into(),
        roboco_owned: true,
        origin: Some(format!("chat:{chat}")),
        chat_id: Some(chat.into()),
    }
}

/// The engine-side `same_process` input for our own pid: the scanner's
/// started_at format (ms since the epoch), computed the same way
/// `discovery::same_process` does so the relay's re-verification passes.
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

/// The local engine's WS listener is the registry's mandatory local entry —
/// its dial URL and in-process service.
async fn local_engine(dir: &std::path::Path) -> (roboco_engine::EngineCore, String) {
    let core = roboco_engine::EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(dir).unwrap(),
        Arc::new(roboco_engine::HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    let service = core.rpc_service();
    tokio::spawn(roboco_rpc::serve_ws_listener(listener, service));
    (core, url)
}

struct Fixture {
    registry: EngineRegistry,
    key: roboco_engine_client::registry::EngineKey,
    core: roboco_engine::EngineCore,
    remote: Option<roboco_engine::EngineListener>,
    /// The registry's mandatory local entry; shut down with the fixture.
    local: roboco_engine::EngineCore,
    dir: std::path::PathBuf,
    services: Vec<roboco_proto::PreviewService>,
}

/// One remote engine with a chat-attributed live dev server, paired into a
/// registry whose local entry is a second, ordinary engine.
async fn fixture(dir: &std::path::Path) -> Fixture {
    let (local, local_url) = local_engine(&dir.join("local-engine")).await;
    let local_client = Arc::new(roboco_rpc::connect_ws(&local_url).await.unwrap());
    let info = local_client
        .call_as(roboco_rpc::methods::ENGINE_INFO, serde_json::json!({}))
        .await
        .unwrap();
    let registry = EngineRegistry::open(
        dir.join("client-engines.json"),
        info,
        local_client,
        Some(Reconnect::Url(local_url)),
    )
    .await
    .unwrap();

    let core = roboco_engine::EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(&dir.join("remote-engine")).unwrap(),
        Arc::new(roboco_engine::HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat(
            "chat-1",
            None,
            Some(core.device_id.as_str()),
            None,
            Some("/work/app".into()),
        )
        .unwrap();
    let stop = CancellationToken::new();
    let dev_port = dev_server(stop).await;
    let (pid, started_at) = (std::process::id(), process_started_at(std::process::id()));
    core.previews
        .catalog()
        .replace_local(vec![(
            "/work/app".into(),
            listener(pid, started_at, dev_port, "chat-1"),
        )])
        .unwrap();
    let remote = roboco_engine::serve_engine_remote_with_previews(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        Some(core.previews.clone()),
        &dir.join("remote-engine"),
    )
    .await
    .unwrap();
    let store = roboco_engine::pairing::PairingStore::open(&dir.join("remote-engine")).unwrap();
    let code = store.create_code("preview proxy test", 300).unwrap();
    let pairing_url = roboco_engine::pairing::pairing_url(
        &format!("http://{}", remote.address),
        &code.credential,
    )
    .unwrap();
    let key = registry.pair(&pairing_url, "Remote").await.unwrap();
    // The pane's actual source: the WatchPreviews snapshot over the paired
    // session.
    let target = registry.target(&key).unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while !target.is_connected() {
        assert!(std::time::Instant::now() < deadline, "engine never connected");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let mut updates = target
        .subscribe(
            roboco_rpc::methods::WATCH_PREVIEWS,
            serde_json::json!({"chatId": "chat-1"}),
        )
        .await
        .unwrap();
    let value = tokio::time::timeout(Duration::from_secs(10), updates.recv())
        .await
        .expect("watch frame")
        .expect("watch open");
    let snapshot: roboco_proto::PreviewSnapshot = serde_json::from_value(value).unwrap();
    assert!(!snapshot.services.is_empty(), "no services: {snapshot:?}");
    Fixture {
        registry,
        key,
        core,
        remote: Some(remote),
        local,
        dir: dir.to_owned(),
        services: snapshot.services,
    }
}

/// One origin-form HTTP round-trip through the client proxy.
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

#[tokio::test]
async fn a_busy_port_surfaces_the_inline_error_and_local_engines_never_relay() {
    let dir = tempfile::tempdir().unwrap();
    let (local, local_url) = local_engine(&dir.path().join("local-engine")).await;
    let local_client = Arc::new(roboco_rpc::connect_ws(&local_url).await.unwrap());
    let info = local_client
        .call_as(roboco_rpc::methods::ENGINE_INFO, serde_json::json!({}))
        .await
        .unwrap();
    let registry = EngineRegistry::open(
        dir.path().join("client-engines.json"),
        info,
        local_client,
        Some(Reconnect::Url(local_url)),
    )
    .await
    .unwrap();
    // The local engine has no saved endpoint: its previews go through the
    // engine's own 7331 proxy, never the relay.
    assert!(registry.relay_dial(registry.local().key()).is_none());

    // Another application owns the port: the bind fails, the inline error
    // carries the 7331-style message, and the URL never changes (the stable
    // port is what the pane renders regardless).
    let squat = TcpListener::bind(("127.0.0.1", roboco_proto::PREVIEW_CLIENT_PROXY_PORT))
        .await
        .unwrap_or_else(|_| panic!("7332 should be free for the squatter"));
    let proxy = PreviewProxy::start(
        registry.clone(),
        None,
        Duration::from_secs(60),
    );
    proxy.watch(registry.local().key(), true);
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        let status = proxy.status();
        if let Some(error) = status.error {
            assert!(
                error.contains(&format!(
                    "could not listen on port {}",
                    roboco_proto::PREVIEW_CLIENT_PROXY_PORT
                )),
                "unexpected error: {error}"
            );
            assert!(status.port.is_none());
            break;
        }
        assert!(std::time::Instant::now() < deadline, "bind error never surfaced");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    drop(squat);
    proxy.stop().await;
    local.shutdown().await;
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn remote_previews_relay_through_the_client_proxy() {
    let dir = tempfile::tempdir().unwrap();
    let mut fixture = fixture(dir.path()).await;

    // The manager: registry-backed dialing, driven exactly as the pane does.
    let proxy = PreviewProxy::start(
        fixture.registry.clone(),
        Some(dir.path().join("labels.json")),
        Duration::from_secs(60),
    );
    proxy.watch(&fixture.key, true);
    let effective = proxy
        .ingest(&fixture.key, fixture.services.clone())
        .unwrap();
    assert_eq!(effective.len(), 1);
    assert_eq!(effective[0].hostname, fixture.services[0].hostname);

    // Lazy start: 7332 binds only once a watcher exists.
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
    assert_ne!(port, roboco_proto::PREVIEW_PROXY_PORT);

    // The hostname routes through the real engine's relay: request →
    // registry dial → /preview-relay/{chat}/{service} → mux → dev server.
    let (status_code, body) = get(port, &effective[0].hostname, "/").await;
    assert_eq!(status_code, 200, "body was {body}");
    assert_eq!(body, "remote relay ok");

    // A stale hostname 502s with the existing "not running" shape.
    let (status_code, body) = get(port, "gone.app.localhost", "/").await;
    assert_eq!(status_code, 502);
    assert_eq!(
        body,
        "This preview is not running. Start the project's dev server and try again."
    );

    // Revoking the engine's session is inherited: the relay dial is refused,
    // the open preview stream is cut, and the pane's source of truth (the
    // WatchPreviews subscription) fails — the reconnect state.
    let store = roboco_engine::pairing::PairingStore::open(&fixture.dir.join("remote-engine"))
        .unwrap();
    let session = store
        .list_sessions()
        .unwrap()
        .into_iter()
        .find(|session| session.revoked_at.is_none())
        .expect("the paired session");
    store.revoke(&session.id).unwrap();
    fixture.remote.take().unwrap().stop().await;
    let (status_code, body) =
        tokio::time::timeout(Duration::from_secs(30), get(port, &effective[0].hostname, "/"))
            .await
            .expect("the cut relay must fail promptly");
    assert_eq!(status_code, 502, "body: {body}");

    proxy.stop().await;
    if let Some(mut remote) = fixture.remote.take() {
        remote.stop().await;
    }
    fixture.core.shutdown().await;
    fixture.local.shutdown().await;
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn forgetting_the_engine_stops_routing_its_hostnames() {
    let dir = tempfile::tempdir().unwrap();
    let mut fixture = fixture(dir.path()).await;
    let proxy = PreviewProxy::start(
        fixture.registry.clone(),
        None,
        Duration::from_secs(60),
    );
    proxy.watch(&fixture.key, true);
    let effective = proxy
        .ingest(&fixture.key, fixture.services.clone())
        .unwrap();
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
    let (status_code, _) = get(port, &effective[0].hostname, "/").await;
    assert_eq!(status_code, 200);

    // Unpairing the engine: the registry hygiene task drops its routes, so
    // its hostnames stop answering instead of dialing a forgotten engine.
    fixture.registry.forget(&fixture.key).await.unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        if proxy.by_hostname(&effective[0].hostname).is_none() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "routes never dropped");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let (status_code, _) = get(port, &effective[0].hostname, "/").await;
    assert_eq!(status_code, 502);
    proxy.stop().await;
    fixture.remote.take().unwrap().stop().await;
    fixture.core.shutdown().await;
    fixture.local.shutdown().await;
}
