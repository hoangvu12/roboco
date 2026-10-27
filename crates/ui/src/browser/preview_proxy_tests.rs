//! Browser pane tests for remote previews (ticket 04): a real remote engine
//! (a second engine over the wire, paired into a registry) serves the
//! ticket-03 relay route, and a browser pane watches its previews like the
//! shell does. The pane's snapshot is rewritten — effective hostnames, the
//! client proxy's port, no tunnel-URL degradation — and the URL it renders
//! actually serves the remote dev server through the client proxy. Revoking
//! the engine's session degrades the pane to the reconnect state.
//!
//! Real engines over real sockets, like `state/cache_tests`: RPC completions
//! wake gpui tasks from tokio workers, mixed with the deterministic
//! scheduler (`allow_parking`).
#![cfg(any(target_os = "linux", target_os = "macos"))]

use std::{convert::Infallible, sync::Arc, time::{Duration, Instant}};

use bytes::Bytes;
use http_body_util::{BodyExt, Full, combinators::UnsyncBoxBody};
use hyper::{Request, Response, body::Incoming, server::conn::http1, service::service_fn};
use hyper_util::rt::TokioIo;
use roboco_preview::discovery::Listener;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;

use crate::browser::{BrowserContext, BrowserSurface};
use crate::engine_registry::{EngineRegistry, PreviewProxy, Reconnect};

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
                // requests' write side; hyper 1's default treats that client
                // EOF as IncompleteMessage (the ticket-03 finding).
                let service = service_fn(|_request: Request<Incoming>| async move {
                    Ok::<_, Infallible>(Response::new(full("remote pane ok")))
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

/// The engine-side `same_process` input for our own pid (the scanner's
/// started_at format, computed the way `discovery::same_process` does so the
/// relay's re-verification passes).
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

struct RemoteEngine {
    core: roboco_engine::EngineCore,
    remote: Option<roboco_engine::EngineListener>,
    dir: std::path::PathBuf,
}

/// A paired remote engine with one chat-attributed live dev server, beside a
/// local engine the registry requires.
async fn remote_engine(dir: &std::path::Path) -> (EngineRegistry, RemoteEngine) {
    let local = roboco_engine::EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(&dir.join("local-engine")).unwrap(),
        Arc::new(roboco_engine::HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let ipc = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", ipc.local_addr().unwrap());
    let service = local.rpc_service();
    tokio::spawn(roboco_rpc::serve_ws_listener(ipc, service));
    let client = Arc::new(roboco_rpc::connect_ws(&url).await.unwrap());
    let info = client
        .call_as(roboco_rpc::methods::ENGINE_INFO, serde_json::json!({}))
        .await
        .unwrap();
    let registry = EngineRegistry::open(
        dir.join("client-engines.json"),
        info,
        client,
        Some(Reconnect::Url(url)),
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
    let code = store.create_code("pane test", 300).unwrap();
    let pairing_url = roboco_engine::pairing::pairing_url(
        &format!("http://{}", remote.address),
        &code.credential,
    )
    .unwrap();
    registry.pair(&pairing_url, "Remote").await.unwrap();
    (
        registry,
        RemoteEngine {
            core,
            remote: Some(remote),
            dir: dir.to_owned(),
        },
    )
}

fn wait_for(
    cx: &mut gpui::TestAppContext,
    window: &gpui::WindowHandle<BrowserSurface>,
    description: &str,
    predicate: impl Fn(&BrowserSurface) -> bool,
) {
    // Real engines over sockets beside the deterministic scheduler: a wide
    // deadline, because the suite's parallel tests can starve this window.
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        cx.run_until_parked();
        if window
            .update(cx, |browser, _, _| predicate(browser))
            .unwrap()
        {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "Timed out waiting for {description}"
        );
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[gpui::test]
fn a_remote_panels_previews_rewrite_against_the_client_proxy_and_load(
    cx: &mut gpui::TestAppContext,
) {
    cx.executor().allow_parking();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    cx.update(|cx| gpui_tokio::init_from_handle(cx, runtime.handle().clone()));
    cx.update(|cx| {
        gpui_base::init(cx);
        crate::composer::init(cx, Default::default());
        cx.set_global(crate::theme::Theme::default());
    });
    let dir = tempfile::tempdir().unwrap();
    let (registry, mut engine) = runtime.block_on(remote_engine(dir.path()));
    let key = registry
        .snapshot()
        .engines
        .iter()
        .find(|engine| !engine.key.is_local())
        .map(|engine| engine.key.clone())
        .expect("the paired remote engine");

    // The pane holds the manager exactly as the shell hands it over
    // (`AppState::ensure_preview_proxy` mints exactly this).
    let proxy: Arc<PreviewProxy> = PreviewProxy::start(
        registry.clone(),
        Some(dir.path().join("labels.json")),
        Duration::from_secs(60),
    );
    let window = cx.add_window(|window, cx| {
        BrowserSurface::new(BrowserContext::default(), true, window, cx)
    });
    let target = registry.target(&key).unwrap();
    window
        .update(cx, |browser, _, cx| {
            browser.watch_previews(target, "chat-1".into(), Some(proxy.clone()), cx)
        })
        .unwrap();

    // The pane lists the remote engine's service, rewritten: effective
    // hostname, the client proxy's port (never the engine's 7331), remote
    // flag set, and no tunnel-URL degradation.
    wait_for(
        cx,
        &window,
        "the remote preview to list",
        |browser| browser.previews.services.len() == 1,
    );
    let snapshot = window
        .update(cx, |browser: &mut BrowserSurface, _, _| browser.previews.clone())
        .unwrap();
    assert!(snapshot.remote, "the snapshot must be marked remote");
    assert_eq!(snapshot.proxy_port, roboco_proto::PREVIEW_CLIENT_PROXY_PORT);
    assert_eq!(snapshot.error, None, "no tunnel-URL degradation");
    let service = &snapshot.services[0];
    assert!(service.hostname.ends_with(".localhost"));
    assert_eq!(service.chat_id.as_deref(), Some("chat-1"));
    let url = service.url(snapshot.proxy_port);

    // The listener bound only because this pane watches (lazy start).
    wait_for(cx, &window, "the listener to bind", |browser| {
        let _ = browser;
        proxy.status().port.is_some()
    });
    let port = match proxy.status().port {
        Some(port) => port,
        None => panic!("the client proxy must bind: {:?}", proxy.status().error),
    };

    // The URL the pane renders actually serves the remote dev server through
    // the session relay (origin-form, routed by Host).
    let (status, body) = runtime.block_on(async move {
        let mut socket = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        socket
            .write_all(
                format!(
                    "GET / HTTP/1.1\r\nhost: {}:{port}\r\nconnection: close\r\n\r\n",
                    service.hostname
                )
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
    });
    assert_eq!(status, 200, "body was {body}");
    assert_eq!(body, "remote pane ok");
    assert!(url.contains(&format!(":{}", roboco_proto::PREVIEW_CLIENT_PROXY_PORT)));

    // Revoking the session and stopping the remote engine: the preview pane
    // degrades to the reconnect state (its WatchPreviews subscription is
    // cut with the session).
    let store = roboco_engine::pairing::PairingStore::open(&engine.dir.join("remote-engine"))
        .unwrap();
    let session = store
        .list_sessions()
        .unwrap()
        .into_iter()
        .find(|session| session.revoked_at.is_none())
        .expect("the paired session");
    store.revoke(&session.id).unwrap();
    runtime.block_on(async {
        engine.remote.take().unwrap().stop().await;
    });
    wait_for(
        cx,
        &window,
        "the pane to degrade to the reconnect state",
        |browser| {
            browser.previews.error.as_deref()
                == Some("Connecting to preview discovery…")
        },
    );

    runtime.block_on(async {
        engine.core.shutdown().await;
    });
}
