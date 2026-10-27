//! The preview surface's tests (agent-previews ticket 05): one **real**
//! in-process engine serving the ticket-03 relay route with a
//! catalog-injected, chat-attributed dev server, one paired mobile client
//! whose proxy binds an ephemeral port — no protocol mocks (spec
//! "Testing Decisions"). The panel state machine (`state/preview.ts`
//! shape), the watcher lifecycle, and a real HTTP round-trip through the
//! phone-side 7332 proxy over the paired session are all exercised.
//!
//! The round-trip needs `discovery::same_process` to pass for the test
//! process (the LocalConnector re-verifies process identity before
//! dialing), so it runs where the scanner works (Linux/macOS) — the same
//! gating as the engine-side relay suite. The lifecycle tests run
//! everywhere.

use std::{convert::Infallible, sync::Arc, time::Duration};

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

use super::PanelPhase;
use crate::{
    Client, ClientConfig, NullListener,
    engines::EngineState,
    test_support::test_timings,
};

type Body = UnsyncBoxBody<Bytes, hyper::Error>;

fn full(value: impl Into<Bytes>) -> Body {
    Full::new(value.into())
        .map_err(|never: Infallible| match never {})
        .boxed_unsync()
}

/// One loopback dev server answering "remote relay ok" — the same shape
/// the engine-side relay suite uses (`half_close(true)`: the mux END
/// idiom half-closes relayed requests' write side).
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

/// A rig with previews: one real engine (the relay route + the preview
/// catalog seeded with a chat-attributed dev server) and one paired
/// client whose proxy binds an **ephemeral** port with a short idle
/// window, so the tests never touch the stable 7332 another test binary
/// may be squatting on.
struct PreviewRig {
    #[allow(dead_code)]
    engine_dir: tempfile::TempDir,
    #[allow(dead_code)]
    data_dir: tempfile::TempDir,
    core: roboco_engine::EngineCore,
    listener: Option<roboco_engine::EngineListener>,
    client: Arc<Client>,
    engine_id: String,
    stop: CancellationToken,
}

impl PreviewRig {
    async fn start() -> PreviewRig {
        let engine_dir = tempfile::tempdir().expect("engine tempdir");
        let data_dir = tempfile::tempdir().expect("client tempdir");
        let core = roboco_engine::EngineCore::assemble_with_profile(
            roboco_engine::EngineProfile::local(engine_dir.path()).expect("engine profile"),
            Arc::new(roboco_engine::HarnessRegistry::new()),
            roboco_engine::HarnessId::Mock,
        )
        .expect("engine core");
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
        let dev_port = dev_server(stop.clone()).await;
        let (pid, started_at) = (std::process::id(), process_started_at(std::process::id()));
        core.previews
            .catalog()
            .replace_local(vec![(
                "/work/app".into(),
                listener(pid, started_at, dev_port, "chat-1"),
            )])
            .unwrap();
        let listener = roboco_engine::serve_engine_remote_with_previews(
            "127.0.0.1:0".parse().expect("loopback address"),
            core.rpc_service(),
            Some(core.previews.clone()),
            engine_dir.path(),
        )
        .await
        .expect("engine listener");
        let client = Client::new_with_preview_knobs(
            ClientConfig {
                data_dir: data_dir.path().to_owned(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            Arc::new(NullListener),
            test_timings(),
            Arc::new(crate::tailcat::LibraryOpener::new(None, None)),
            0,
            Duration::from_millis(150),
        )
        .expect("client");
        let pairing =
            roboco_engine::pairing::PairingStore::open(engine_dir.path()).expect("pairing store");
        let code = pairing.create_code("previews test", 300).expect("pairing code");
        let url = format!("http://{}/pair#token={}", listener.address, code.credential);
        let record = client.pair_engine(&url, None).await.expect("pairing");
        let engine_id = record.engine_id;
        // Connected before the panel starts.
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let ready = client
                .engine(&engine_id)
                .is_some_and(|view| view.state == EngineState::Connected);
            if ready {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the paired engine never became ready"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        PreviewRig {
            engine_dir,
            data_dir,
            core,
            listener: Some(listener),
            client,
            engine_id,
            stop,
        }
    }

    /// The scoped chat id the app holds.
    fn scoped_chat(&self) -> String {
        self.client.scope(&self.engine_id, "chat-1")
    }

    /// Wait until the panel's phase matches, or panic with the last state.
    async fn await_phase(&self, panel: &super::PreviewPanel, phase: PanelPhase) -> super::PanelState {
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            let state = panel.state();
            if state.phase == phase {
                return state;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the panel never reached {phase:?}; last: {state:?}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

impl Drop for PreviewRig {
    fn drop(&mut self) {
        // `EngineListener`'s own Drop aborts the accept task; the dev
        // server's cancellation token goes with the rig. Nothing else
        // needs an async teardown at test end.
        self.stop.cancel();
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

/// An unknown engine fails fast: no panel, no watcher, nothing bound.
#[tokio::test]
async fn an_unknown_chat_fails_fast_without_binding_anything() {
    let rig = PreviewRig::start().await;
    // A scoped id of an engine this client never paired.
    let stranger = rig.client.scope("engine-nope", "chat-1");
    match rig.client.watch_previews(&stranger) {
        Err(crate::ClientError::NotFound { .. }) => {}
        Err(other) => panic!("unexpected error: {other:?}"),
        Ok(_) => panic!("an unknown engine must not open a panel"),
    }
    // A bare (unscoped) id resolves to the local engine key, which no
    // phone client ever holds.
    match rig.client.watch_previews("chat-1") {
        Err(crate::ClientError::NotFound { .. }) => {}
        Err(other) => panic!("unexpected error: {other:?}"),
        Ok(_) => panic!("a bare chat id must not open a panel"),
    }
    // Nothing was watched: the listener stays unbound.
    assert_eq!(rig.client.preview_proxy_status().port, None);
}

/// The panel's happy path: `Loading` → `Live` with the effective
/// hostnames, the listener lazily bound on the ephemeral port, and the
/// rows' URL pointing at the client proxy.
#[tokio::test]
async fn a_panel_loads_the_remote_snapshot_and_binds_the_listener() {
    let rig = PreviewRig::start().await;
    let panel = rig.client.watch_previews(&rig.scoped_chat()).expect("panel");
    assert_eq!(panel.state().phase, PanelPhase::Loading);
    let state = rig.await_phase(&panel, PanelPhase::Live).await;
    assert!(state.error.is_none(), "unexpected error: {:?}", state.error);
    assert_eq!(state.services.len(), 1, "the panel's rows: {state:?}");
    let service = &state.services[0];
    assert_eq!(service.chat_id.as_deref(), Some("chat-1"));
    // The URL the browser entry opens: the client proxy's port, not the
    // engine's 7331.
    assert_eq!(
        service.url(super::PANEL_PROXY_PORT),
        format!("http://{}:{}", service.hostname, super::PANEL_PROXY_PORT)
    );
    // The listener is bound (lazily) while the panel watches.
    let port = rig.client.preview_proxy_status().port.expect("bound");
    assert_ne!(port, super::PANEL_PROXY_PORT, "the test binds ephemeral");
    panel.close();
}

/// Closing the panel releases the watcher: after the (short) idle window
/// the listener unwinds, so nothing keeps 7332-family ports bound.
#[tokio::test]
async fn closing_the_panel_releases_the_listener_after_the_idle_window() {
    let rig = PreviewRig::start().await;
    let panel = rig.client.watch_previews(&rig.scoped_chat()).expect("panel");
    rig.await_phase(&panel, PanelPhase::Live).await;
    let status = rig.client.preview_proxy_status();
    assert!(status.port.is_some(), "the watcher keeps the listener bound");
    panel.close();
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    loop {
        let status = rig.client.preview_proxy_status();
        if status.port.is_none() {
            break;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the listener never unwound after the panel closed"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// An engine that goes offline degrades the panel to the reconnect state
/// (rows cleared) — matching how a chat degrades when its engine's link
/// drops, with the engine's supervision as the redial.
#[tokio::test]
async fn an_offline_engine_degrades_the_panel_to_the_reconnect_state() {
    let mut rig = PreviewRig::start().await;
    let panel = rig.client.watch_previews(&rig.scoped_chat()).expect("panel");
    rig.await_phase(&panel, PanelPhase::Live).await;
    // The engine's listener dies: the link drops, the subscription ends.
    if let Some(mut listener) = rig.listener.take() {
        listener.stop().await;
    }
    let state = rig.await_phase(&panel, PanelPhase::Reconnecting).await;
    assert!(state.services.is_empty(), "rows clear: {state:?}");
    panel.close();
}

/// The whole point of the phone's half: a real HTTP round-trip through
/// the in-app proxy, over the paired session's relay, to the engine's
/// dev server. The browser entry's exact data path.
#[cfg(any(target_os = "linux", target_os = "macos"))]
#[tokio::test]
async fn remote_previews_relay_through_the_phone_side_proxy() {
    let rig = PreviewRig::start().await;
    let panel = rig.client.watch_previews(&rig.scoped_chat()).expect("panel");
    let state = rig.await_phase(&panel, PanelPhase::Live).await;
    let service = &state.services[0];
    let port = rig.client.preview_proxy_status().port.expect("bound");
    let (status, body) = get(port, &service.hostname, "/").await;
    assert_eq!(status, 200);
    assert_eq!(body, "remote relay ok");
    panel.close();
}

/// A second frame updates the panel in place: the dev server's snapshot
/// advances (the catalog publishes) and the rows follow.
#[tokio::test]
async fn a_new_snapshot_frame_updates_the_rows() {
    let rig = PreviewRig::start().await;
    let panel = rig.client.watch_previews(&rig.scoped_chat()).expect("panel");
    let first = rig.await_phase(&panel, PanelPhase::Live).await;
    assert_eq!(first.services.len(), 1);
    // The dev server goes away: the engine's catalog publishes an empty
    // snapshot for the chat.
    rig.stop.cancel();
    rig.core
        .previews
        .catalog()
        .replace_local(Vec::new())
        .unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        let state = panel.state();
        if state.phase == PanelPhase::Live && state.services.is_empty() {
            break;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "the panel never saw the empty snapshot; last: {state:?}"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panel.close();
}
