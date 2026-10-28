//! The ports card opens servers in the system browser (ADR 0012): a real
//! paired engine serves a chat-attributed dev server through its session
//! relay, the shell opens the row (`cx.open_url`, captured by the test
//! platform), and the URL it opened keeps serving through the client
//! preview proxy after the card closes and the chat changes — because the
//! shell's opened-preview watcher, not a browser tab, holds the proxy. The
//! watcher lets go when the servers stop, the session is revoked or the
//! engine is forgotten, and the proxy's listener then idles out.
//!
//! Real engines over real sockets (`paired_fixture`), so these opt into
//! `allow_parking` and pump with wall-clock deadlines.

use std::convert::Infallible;
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Bytes;
use http_body_util::{BodyExt, Full, combinators::UnsyncBoxBody};
use hyper::{Request, Response, body::Incoming, server::conn::http1, service::service_fn};
use hyper_util::rt::TokioIo;
use roboco_preview::discovery::Listener;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

use crate::engine_registry::PreviewProxy;
use crate::paired_fixture::{PairedEngines, wait_until};
use crate::shell::Shell;

/// Short enough to watch the watcher and the listener let go in a test.
const IDLE: Duration = Duration::from_secs(2);

type Body = UnsyncBoxBody<Bytes, hyper::Error>;

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
                // requests' write side (the ticket-03 finding).
                let service = service_fn(|_request: Request<Incoming>| async move {
                    let body: Body = Full::new(Bytes::from_static(b"remote ok"))
                        .map_err(|never: Infallible| match never {})
                        .boxed_unsync();
                    Ok::<_, Infallible>(Response::new(body))
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

fn listener(port: u16, chat: &str) -> Listener {
    let pid = std::process::id();
    Listener {
        pid,
        parent: 1,
        cwd: "/work/app".into(),
        args: vec!["node".into(), "vite".into()],
        started_at: process_started_at(pid),
        address: ([127, 0, 0, 1], port).into(),
        roboco_owned: true,
        origin: Some(format!("chat:{chat}")),
        chat_id: Some(chat.into()),
    }
}

/// The scanner's started_at for our own pid, so the relay's re-verification
/// (`discovery::same_process`) passes.
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

/// Windows: `0` takes `same_process`'s pid-only path.
#[cfg(windows)]
fn process_started_at(_pid: u32) -> u64 {
    0
}

/// `GET /` through the client proxy's bound port, routed by `hostname`.
async fn get(port: u16, hostname: &str) -> Option<(u16, String)> {
    let mut socket = TcpStream::connect(("127.0.0.1", port)).await.ok()?;
    socket
        .write_all(
            format!("GET / HTTP/1.1\r\nhost: {hostname}:{port}\r\nconnection: close\r\n\r\n")
                .as_bytes(),
        )
        .await
        .ok()?;
    socket.shutdown().await.ok()?;
    let mut response = Vec::new();
    socket.read_to_end(&mut response).await.ok()?;
    let head_end = response.windows(4).position(|w| w == b"\r\n\r\n")?;
    let head = String::from_utf8_lossy(&response[..head_end]).into_owned();
    let status = head.lines().next()?.split_whitespace().nth(1)?.parse().ok()?;
    Some((status, String::from_utf8_lossy(&response[head_end + 4..]).into_owned()))
}

/// A paired engine with chat `chat-1` running one dev server, a second
/// idle chat, the app's client proxy minted with a short idle window, and a
/// shell showing `chat-1`'s ports card.
struct Opened {
    fixture: PairedEngines,
    proxy: Arc<PreviewProxy>,
    window: gpui::WindowHandle<Shell>,
    chat: String,
    other: String,
    _dev: CancellationToken,
}

fn open_remote_row(cx: &mut gpui::TestAppContext) -> (Opened, String) {
    let fixture = PairedEngines::start(cx);
    let dev = CancellationToken::new();
    let dev_port = fixture.runtime.block_on(dev_server(dev.clone()));
    let remote = &fixture.remote;
    for chat in ["chat-1", "chat-2"] {
        remote
            .workspace
            .create_chat(
                chat,
                None,
                Some(remote.device_id.as_str()),
                None,
                Some("/work/app".into()),
            )
            .unwrap();
    }
    remote
        .previews
        .catalog()
        .replace_local(vec![("/work/app".into(), listener(dev_port, "chat-1"))])
        .unwrap();
    // An ephemeral port: a running Roboco on this machine may hold 7332.
    let proxy = PreviewProxy::start_on_port(
        fixture.registry.clone(),
        Some(fixture.local_dir.path().join("labels.json")),
        IDLE,
        0,
    );
    fixture
        .state
        .update(cx, |state, _| state.preview_proxy = Some(proxy.clone()));
    let (chat, other) = (fixture.scoped("chat-1"), fixture.scoped("chat-2"));
    fixture.wait_for_state(cx, "the paired engine's chats", |s| {
        s.chats.iter().any(|c| c.id == chat) && s.chats.iter().any(|c| c.id == other)
    });
    let window = cx.add_window(|_, cx| Shell::new(fixture.state.clone(), fixture.boot.clone(), cx));
    window
        .update(cx, |shell, _, cx| shell.open_chat(chat.clone(), cx))
        .unwrap();
    let service = std::cell::RefCell::new(None);
    wait_until(cx, "the ports card to list the remote server", |cx| {
        window
            .update(cx, |shell, _, cx| {
                shell.ensure_session_previews(&chat, cx);
                *service.borrow_mut() = shell.session_previews.snapshot.services.first().cloned();
                service.borrow().is_some() && shell.session_previews.remote.is_some()
            })
            .unwrap()
    });
    let service = service.into_inner().unwrap();
    window
        .update(cx, |shell, _, cx| {
            let url = service.url(roboco_proto::PREVIEW_CLIENT_PROXY_PORT);
            shell.open_session_preview(&service.id, &url, cx);
        })
        .unwrap();
    cx.run_until_parked();
    let opened = cx.opened_url().expect("the row opens a URL externally");
    (
        Opened {
            fixture,
            proxy,
            window,
            chat,
            other,
            _dev: dev,
        },
        opened,
    )
}

fn hostname_of(url: &str) -> String {
    url.trim_start_matches("http://")
        .split([':', '/'])
        .next()
        .unwrap()
        .to_owned()
}

fn wait_for_port(cx: &mut gpui::TestAppContext, proxy: &PreviewProxy) -> u16 {
    wait_until(cx, "the client proxy to bind", |_| proxy.status().port.is_some());
    proxy.status().port.unwrap()
}

/// Poll `GET /` through the proxy until it serves the dev server (the relay
/// dial and the route land asynchronously).
fn assert_serves(opened: &Opened, port: u16, hostname: &str, what: &str) {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let response = opened.fixture.runtime.block_on(get(port, hostname));
        if response.as_ref().is_some_and(|(status, _)| *status == 200) {
            assert_eq!(response.unwrap().1, "remote ok", "{what}");
            return;
        }
        assert!(Instant::now() < deadline, "{what}: last response {response:?}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn is_watching(cx: &mut gpui::TestAppContext, opened: &Opened) -> bool {
    let key = opened.fixture.key.clone();
    let chat = opened.chat.clone();
    opened
        .window
        .update(cx, |shell, _, _| shell.opened_previews.is_watching(&key, &chat))
        .unwrap()
}

fn wait_released(cx: &mut gpui::TestAppContext, opened: &Opened, what: &str) {
    wait_until(cx, &format!("the watcher to end after {what}"), |cx| {
        !is_watching(cx, opened)
    });
    let proxy = opened.proxy.clone();
    wait_until(cx, &format!("the listener to idle out after {what}"), |_| {
        proxy.status().port.is_none()
    });
}

fn shutdown(cx: &mut gpui::TestAppContext, opened: Opened) {
    let Opened {
        fixture,
        proxy,
        window,
        ..
    } = opened;
    let _ = window;
    fixture.runtime.block_on(proxy.stop());
    fixture.shutdown(cx);
}

#[gpui::test]
fn a_remote_row_opens_externally_and_keeps_routing_until_its_servers_stop(
    cx: &mut gpui::TestAppContext,
) {
    let (opened, url) = open_remote_row(cx);
    // The routed URL, on the client proxy's port — and no Roboco tab.
    assert!(
        url.ends_with(&format!(".localhost:{}", roboco_proto::PREVIEW_CLIENT_PROXY_PORT)),
        "{url}"
    );
    let no_tabs = opened
        .window
        .update(cx, |shell, _, _| shell.right_tabs.values().all(Vec::is_empty))
        .unwrap();
    assert!(no_tabs, "opening a server adds no right-pane surface");
    assert!(is_watching(cx, &opened), "a remote row starts a watcher");
    let hostname = hostname_of(&url);
    let port = wait_for_port(cx, &opened.proxy);
    assert_serves(&opened, port, &hostname, "the opened URL serves the remote dev server");

    // Close the card, move to another chat, outwait the idle window: the
    // shell still holds the proxy, so the page keeps loading.
    let other = opened.other.clone();
    opened
        .window
        .update(cx, |shell, _, cx| {
            shell.close_session_info_overlay(cx);
            shell.open_chat(other, cx);
        })
        .unwrap();
    let until = Instant::now() + IDLE * 2;
    while Instant::now() < until {
        cx.run_until_parked();
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(is_watching(cx, &opened), "the watcher outlives the card and the chat");
    assert_eq!(opened.proxy.status().port, Some(port));
    assert_serves(&opened, port, &hostname, "the URL after the card closed and the chat changed");

    // Reopening while it runs keeps the one watcher.
    let chat = opened.chat.clone();
    let key = opened.fixture.key.clone();
    opened
        .window
        .update(cx, |shell, _, cx| shell.ensure_opened_preview(key, chat, cx))
        .unwrap();
    let watchers = opened
        .window
        .update(cx, |shell, _, _| shell.opened_previews.watchers.len())
        .unwrap();
    assert_eq!(watchers, 1);

    // The servers stop: the watcher ends after the idle window, and the
    // listener idles out after it.
    opened
        .fixture
        .remote
        .previews
        .catalog()
        .replace_local(Vec::new())
        .unwrap();
    wait_released(cx, &opened, "the servers stopped");
    shutdown(cx, opened);
}

#[gpui::test]
fn revoking_the_session_ends_the_watcher(cx: &mut gpui::TestAppContext) {
    let (opened, _url) = open_remote_row(cx);
    wait_for_port(cx, &opened.proxy);
    assert!(is_watching(cx, &opened));
    let store = roboco_engine::pairing::PairingStore::open(opened.fixture.remote_dir.path())
        .unwrap();
    for session in store.list_sessions().unwrap() {
        if session.revoked_at.is_none() {
            store.revoke(&session.id).unwrap();
        }
    }
    // Revocation refuses the next dial; dropping the bind cuts the live
    // connection, so the registry redials into the refusal.
    let mut opened = opened;
    opened.fixture.stop_listener();
    let target = opened.fixture.target.clone();
    let deadline = Instant::now() + Duration::from_secs(60);
    while target.is_connected() {
        assert!(Instant::now() < deadline, "the registry never saw the engine go offline");
        cx.run_until_parked();
        std::thread::sleep(Duration::from_millis(50));
    }
    wait_released(cx, &opened, "the session was revoked");
    shutdown(cx, opened);
}

#[gpui::test]
fn forgetting_the_engine_ends_the_watcher(cx: &mut gpui::TestAppContext) {
    let (opened, _url) = open_remote_row(cx);
    wait_for_port(cx, &opened.proxy);
    assert!(is_watching(cx, &opened));
    let key = opened.fixture.key.clone();
    opened
        .fixture
        .runtime
        .block_on(opened.fixture.registry.forget(&key))
        .unwrap();
    wait_released(cx, &opened, "the engine was forgotten");
    shutdown(cx, opened);
}

/// A local chat's server opens at the snapshot's own proxy port (the
/// engine's always-on 7331) with no watcher.
#[gpui::test]
fn a_local_row_opens_its_engine_proxy_url_without_a_watcher(cx: &mut gpui::TestAppContext) {
    let fixture = PairedEngines::start(cx);
    let window = cx.add_window(|_, cx| Shell::new(fixture.state.clone(), fixture.boot.clone(), cx));
    let service: roboco_proto::PreviewService = serde_json::from_value(serde_json::json!({
        "id": "svc-1",
        "projectId": "project",
        "projectName": "app",
        "projectCwd": "/work/app",
        "deviceId": "device",
        "deviceName": "desk",
        "hostname": "desk.app.localhost",
        "name": "vite",
        "port": 5173,
        "pid": 1,
        "cwd": "/work/app",
        "startedAt": 0,
        "robocoOwned": true,
        "chatId": "local-chat",
    }))
    .unwrap();
    let url = window
        .update(cx, |shell, _, cx| {
            let previews = &mut shell.session_previews;
            previews.chat = Some("local-chat".into());
            previews.remote = None;
            previews.snapshot.services = vec![service.clone()];
            let url = service.url(previews.snapshot.proxy_port);
            shell.open_session_preview(&service.id, &url, cx);
            url
        })
        .unwrap();
    cx.run_until_parked();
    assert_eq!(cx.opened_url().as_deref(), Some(url.as_str()));
    assert!(url.ends_with(&format!(":{}", roboco_proto::PREVIEW_PROXY_PORT)));
    let watchers = window
        .update(cx, |shell, _, _| shell.opened_previews.watchers.len())
        .unwrap();
    assert_eq!(watchers, 0, "a local server needs no watcher");
    let _ = window;
    fixture.shutdown(cx);
}
