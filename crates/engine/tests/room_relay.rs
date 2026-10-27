//! The room redeem relay (rooms#02, ADR 0011's browser join path) against
//! real listeners: a paired client POSTs `/pairing/room-redeem
//! {invite, target, label}` to the engine serving it, that engine dials the
//! target member from the invite's own book through its Tailcat client,
//! relays the `/room/present` round-trip, and returns the member's grant on
//! a `/tailcat-relay/{id}` base whose byte pump carries the caller's frames
//! unchanged. The member engine is a full in-process fixture; the tunnel is
//! a scripted stand-in adapter (`tailcat.rs` supervision-test contract) that
//! maps the invite's `tc<port>` member address to the member engine's
//! loopback listener, so the bytes really flow origin→member.
//!
//! Unix-only where the script is involved; the shape/CORS surface runs
//! everywhere. The pinned invariants: the origin engine joined nothing,
//! persisted neither the invite nor the room secret (only tunnel state under
//! `tailcat-relays/`, `connect.json` 0600), and an unreachable member
//! degrades to a per-member failure that names the label — never the
//! address, never the secret.

use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use futures::{SinkExt as _, StreamExt as _};
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry,
    listener::{AccessPolicy, serve_listener, serve_listener_with_policy},
    pairing::PairingStore,
    rooms::RoomStore,
    tailcat_relay::{PairedListenerContext, TailcatRelayRegistry},
};
use roboco_proto::room::{RoomInvite, RoomMember};
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

/// The engine serving the browser: paired listener plus (optionally) a
/// scripted adapter the relay dials with.
struct Origin {
    core: EngineCore,
    base: String,
    context: Arc<PairedListenerContext>,
    task: tokio::task::JoinHandle<()>,
}

/// The member engine the client is being introduced to: a real engine whose
/// paired listener the fake tunnel lands on.
struct Member {
    core: EngineCore,
    remote: roboco_engine::EngineListener,
}

async fn core(dir: &Path) -> EngineCore {
    EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap()
}

async fn member_engine(dir: &Path) -> Member {
    let core = core(dir).await;
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir,
    )
    .await
    .unwrap();
    Member { core, remote }
}

async fn origin_engine(dir: &Path, adapter: Option<PathBuf>) -> Origin {
    let core = core(dir).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let context = Arc::new(PairedListenerContext {
        data_dir: dir.to_path_buf(),
        tailcat_relays: TailcatRelayRegistry::new(),
        adapter,
    });
    let task = tokio::spawn(serve_listener_with_policy(
        listener,
        core.rpc_service(),
        PairingStore::open(dir).unwrap(),
        RoomStore::open(dir).unwrap(),
        AccessPolicy::Paired,
        Some(context.clone()),
        None,
    ));
    Origin {
        core,
        base,
        context,
        task,
    }
}

fn member(label: &str, address: &str) -> RoomMember {
    RoomMember {
        label: label.into(),
        address: address.into(),
    }
}

fn invite(members: Vec<RoomMember>) -> RoomInvite {
    RoomInvite::new("Home fleet", SECRET, members).unwrap()
}

/// The fake paired client's redeem: the room secret is the credential, so
/// the POST carries no session.
async fn redeem(
    http: &reqwest::Client,
    base: &str,
    invite: &RoomInvite,
    target: &str,
    label: &str,
) -> reqwest::Response {
    http.post(format!("{base}/pairing/room-redeem"))
        .json(&json!({
            "invite": invite.encode().unwrap(),
            "target": target,
            "label": label,
        }))
        .send()
        .await
        .unwrap()
}

/// A browser dial: an Origin header browsers cannot suppress and no
/// Authorization header browsers cannot set.
async fn browser_socket(url: &str) -> Socket {
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;
    let mut request = url.into_client_request().unwrap();
    request
        .headers_mut()
        .insert("origin", "https://browser.example".parse().unwrap());
    let (ws, response) = tokio_tungstenite::connect_async(request).await.unwrap();
    assert_eq!(response.status().as_u16(), 101);
    ws
}

async fn send(ws: &mut Socket, frame: Value) {
    ws.send(Message::Text(frame.to_string())).await.unwrap();
}

/// Read messages until a reply frame for `id` arrives.
async fn reply_frame(ws: &mut Socket, id: u64) -> Value {
    tokio::time::timeout(Duration::from_secs(10), async {
        while let Some(Ok(Message::Text(text))) = ws.next().await {
            for line in text.lines() {
                if let Ok(frame) = serde_json::from_str::<Value>(line.trim()) {
                    if frame["id"] == id {
                        return frame;
                    }
                }
            }
        }
        panic!("socket closed before a reply to {id}");
    })
    .await
    .unwrap()
}

/// The room secret is a secret: nothing on disk in either engine's data dir
/// (SQLite WAL sidecars included) may contain it — after any number of
/// redeems.
fn assert_secret_never_at_rest(dir: &Path) {
    fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk(&path, out);
            } else {
                out.push(path);
            }
        }
    }
    let mut files = Vec::new();
    walk(dir, &mut files);
    for path in files {
        let bytes = std::fs::read(&path).unwrap();
        assert!(
            !bytes.windows(SECRET.len()).any(|w| w == SECRET.as_bytes()),
            "the room secret leaked into {}",
            path.display()
        );
    }
}

#[cfg(unix)]
fn executable(path: &Path, script: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    std::fs::write(path, script).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).unwrap();
    path.to_path_buf()
}

/// A scripted stand-in for the tailcat adapter, driving the documented
/// contract (stdout readiness, parent-pipe lifetime, `--config` file): the
/// invite's member addresses are `tc<port>`, and `connect` reports the
/// member engine's loopback listener as the tunnel's URL, so relayed bytes
/// land on the real member.
#[cfg(unix)]
fn scripted_adapter(dir: &Path) -> PathBuf {
    executable(
        &dir.join("adapter"),
        r#"#!/bin/sh
cfg=""
while [ $# -gt 0 ]; do case "$1" in --config) cfg="$2";; esac; shift; done
addr=$(cut -d'"' -f4 "$cfg")
port=${addr#tc}
printf '{"url":"http://127.0.0.1:%s"}\n' "$port"
cat >/dev/null
"#,
    )
}

/// A stand-in that exits before reporting readiness: the dial itself fails.
#[cfg(unix)]
fn failing_adapter(dir: &Path) -> PathBuf {
    executable(&dir.join("adapter"), "#!/bin/sh\nexit 1\n")
}

/// A port with nothing behind it: bind, note, drop.
#[cfg(unix)]
async fn dead_port() -> u16 {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    port
}

async fn shutdown(origin: Origin, member: Member) {
    origin.task.abort();
    drop(origin.context);
    drop(member.remote);
    member.core.shutdown().await;
    origin.core.shutdown().await;
}

/// The end-to-end browser join: redeem, relayed presentation, granted
/// session minted by the member, `/tailcat-relay/{id}` base, first-frame
/// `Auth` and ndjson RPC pumped opaquely, repeat redeems minting fresh relays
/// — and no room state or secret ever at rest on the origin engine.
#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_room_redeem_relays_the_presentation_and_byte_pumps_frames() {
    let member_dir = tempfile::tempdir().unwrap();
    let origin_dir = tempfile::tempdir().unwrap();
    let scripts = tempfile::tempdir().unwrap();

    // The member engine joined the room (it holds the verifier); its
    // loopback port is the address the fake tunnel maps `tc<port>` to.
    let member_engine = member_engine(member_dir.path()).await;
    let member_port = member_engine.remote.address.port();
    let address = format!("tc{member_port}");
    let room = invite(vec![member("server", &address)]);
    RoomStore::open(member_dir.path()).unwrap().join(room.clone()).unwrap();

    // The origin engine joined nothing: the invite in the request is its
    // entire book, and it must stay that way.
    let origin = origin_engine(origin_dir.path(), Some(scripted_adapter(scripts.path()))).await;

    let http = reqwest::Client::new();
    let first = redeem(&http, &origin.base, &room, "server", "Pixel").await;
    assert_eq!(first.status(), 200);
    assert_eq!(first.headers()["access-control-allow-origin"], "*");
    let first: Value = first.json().await.unwrap();
    let credential = first["credential"].as_str().unwrap().to_owned();
    let session_id = first["session"]["id"].as_str().unwrap().to_owned();
    assert_eq!(first["session"]["label"], "Pixel");
    assert!(first["session"]["revokedAt"].is_null());
    // The grant rides a /tailcat-relay/{id} base on the origin listener, and
    // carries no room material.
    let base_url = first["baseUrl"].as_str().unwrap().to_owned();
    assert!(
        base_url.starts_with(&format!("{}/tailcat-relay/", origin.base)),
        "grant baseUrl {base_url}"
    );
    let relay_id = base_url
        .strip_prefix(&format!("{}/tailcat-relay/", origin.base))
        .unwrap()
        .to_owned();
    assert!(!relay_id.is_empty() && !relay_id.contains('/'));
    assert!(!serde_json::to_string(&first).unwrap().contains(SECRET));

    // The session is the member's: its store holds the row, the origin's
    // holds none. The origin engine mints nothing for a room redemption.
    assert!(
        PairingStore::open(member_dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .iter()
            .any(|s| s.id == session_id && s.label == "Pixel")
    );
    assert!(
        !PairingStore::open(origin_dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .iter()
            .any(|s| s.label == "Pixel")
    );

    // The browser rides the relay: its own Auth envelope and traffic pass
    // through the pump opaquely and the member serves the RPC.
    let ws_url = base_url.replacen("http", "ws", 1);
    let mut ws = browser_socket(&ws_url).await;
    send(&mut ws, json!({"auth": credential})).await;
    send(
        &mut ws,
        json!({"id": 1, "method": roboco_rpc::methods::ENGINE_INFO, "params": {}}),
    )
    .await;
    let info = reply_frame(&mut ws, 1).await;
    assert_eq!(info["ok"]["deviceId"], member_engine.core.device_id.to_string());
    assert!(
        info["ok"]["capabilities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|capability| capability == "rooms-v1")
    );
    // Full RPC surface through the pump: the member's remote-access snapshot
    // lists the presentation's session.
    send(
        &mut ws,
        json!({"id": 2, "method": roboco_rpc::methods::GET_REMOTE_ACCESS, "params": {}}),
    )
    .await;
    let access = reply_frame(&mut ws, 2).await;
    assert!(
        access["ok"]["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["id"] == session_id.as_str() && s["label"] == "Pixel")
    );
    // An unknown relay id never serves.
    assert_eq!(
        http.get(format!("{}/tailcat-relay/{}", origin.base, "0".repeat(36)))
            .send()
            .await
            .unwrap()
            .status(),
        404
    );

    // A second redeem against the same member mints a second relay (the
    // registry's per-redeem semantics) and re-presents: the same session row,
    // a fresh credential and base.
    let second = redeem(&http, &origin.base, &room, "server", "Pixel").await;
    assert_eq!(second.status(), 200);
    let second: Value = second.json().await.unwrap();
    assert_eq!(second["session"]["id"], session_id.as_str());
    assert_eq!(second["session"]["label"], "Pixel");
    let second_base = second["baseUrl"].as_str().unwrap().to_owned();
    assert_ne!(second_base, base_url);
    let mut ws_two = browser_socket(&second_base.replacen("http", "ws", 1)).await;
    send(&mut ws_two, json!({"auth": second["credential"].as_str().unwrap()})).await;
    send(
        &mut ws_two,
        json!({"id": 1, "method": roboco_rpc::methods::ENGINE_INFO, "params": {}}),
    )
    .await;
    let info_two = reply_frame(&mut ws_two, 1).await;
    assert_eq!(
        info_two["ok"]["deviceId"],
        member_engine.core.device_id.to_string()
    );
    drop(ws);
    drop(ws_two);

    // The origin engine never joined the room: no rows, no book, no verifier.
    assert!(RoomStore::open(origin_dir.path()).unwrap().list().unwrap().is_empty());
    // The secret never reached either disk — after two redeems.
    assert_secret_never_at_rest(origin_dir.path());
    assert_secret_never_at_rest(member_dir.path());

    // The origin's only footprint is tunnel state: one relay directory per
    // redeem, each with a 0600 connect.json carrying the member address.
    let relay_root = origin_dir.path().join("tailcat-relays");
    let relay_dirs: Vec<_> = std::fs::read_dir(&relay_root)
        .unwrap()
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_dir())
        .collect();
    assert_eq!(relay_dirs.len(), 2, "one relay directory per redeem");
    let mut configs = Vec::new();
    for dir in &relay_dirs {
        let clients = dir.join("tailcat").join("clients");
        for entry in std::fs::read_dir(&clients).unwrap().flatten() {
            configs.push(entry.path().join("connect.json"));
        }
    }
    assert_eq!(configs.len(), 2, "each relay kept its tunnel state");
    for config in &configs {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(config).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "{} must be owner-only", config.display());
        let content = std::fs::read_to_string(config).unwrap();
        assert!(
            content.contains(&format!("\"{address}\"")),
            "{content} must carry the member address"
        );
    }

    shutdown(origin, member_engine).await;
}

/// An unreachable member degrades to a per-member failure — clean error
/// naming the label, never the address, never the secret — and the listener
/// keeps serving the next redeem against an online member.
#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_room_redeem_degrades_cleanly_when_the_member_is_unreachable() {
    let member_dir = tempfile::tempdir().unwrap();
    let origin_dir = tempfile::tempdir().unwrap();
    let scripts = tempfile::tempdir().unwrap();

    let member_engine = member_engine(member_dir.path()).await;
    let member_port = member_engine.remote.address.port();
    let ghost_port = dead_port().await;
    let ghost_address = format!("tc{ghost_port}");
    let room = invite(vec![
        member("ghost", &ghost_address),
        member("server", &format!("tc{member_port}")),
    ]);
    RoomStore::open(member_dir.path()).unwrap().join(room.clone()).unwrap();

    let origin = origin_engine(origin_dir.path(), Some(scripted_adapter(scripts.path()))).await;
    let http = reqwest::Client::new();

    // The ghost member is offline: the tunnel comes up, the presentation
    // cannot reach it. The failure is per-member and label-shaped.
    let failed = redeem(&http, &origin.base, &room, "ghost", "Pixel").await;
    assert_eq!(failed.status(), 502);
    let body: Value = failed.json().await.unwrap();
    let error = body["error"].as_str().unwrap();
    assert!(error.contains("ghost"), "{error}");
    assert!(!error.contains(&ghost_address), "{error}");
    assert!(!serde_json::to_string(&body).unwrap().contains(SECRET));

    // The listener is not torn: a redeem against the online member still
    // relays, grants, and pumps.
    let granted = redeem(&http, &origin.base, &room, "server", "Pixel").await;
    assert_eq!(granted.status(), 200);
    let granted: Value = granted.json().await.unwrap();
    let base_url = granted["baseUrl"].as_str().unwrap().to_owned();
    let mut ws = browser_socket(&base_url.replacen("http", "ws", 1)).await;
    send(&mut ws, json!({"auth": granted["credential"].as_str().unwrap()})).await;
    send(
        &mut ws,
        json!({"id": 1, "method": roboco_rpc::methods::ENGINE_INFO, "params": {}}),
    )
    .await;
    let info = reply_frame(&mut ws, 1).await;
    assert_eq!(info["ok"]["deviceId"], member_engine.core.device_id.to_string());
    drop(ws);

    // The failed redeem left no live relay behind — its tunnel client was
    // dropped, its client state cleaned. (The empty `{id}` directory shell it
    // leaves under `tailcat-relays/` is the known relay-directory hygiene
    // item `tailcat-redeem` shares; out of scope here.)
    let relay_root = origin_dir.path().join("tailcat-relays");
    let live = |path: &std::path::PathBuf| {
        std::fs::read_dir(path.join("tailcat").join("clients"))
            .map(|entries| entries.flatten().count() > 0)
            .unwrap_or(false)
    };
    let live_relays: Vec<_> = std::fs::read_dir(&relay_root)
        .unwrap()
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_dir() && live(path))
        .collect();
    assert_eq!(live_relays.len(), 1, "only the successful redeem kept a live relay");
    assert_secret_never_at_rest(origin_dir.path());
    assert_secret_never_at_rest(member_dir.path());

    shutdown(origin, member_engine).await;
}

/// A dial that fails outright (the adapter never reports readiness) is the
/// same clean, label-shaped per-member failure — and the listener still
/// answers afterwards.
#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_room_redeem_reports_a_dial_failure_without_tearing_the_listener() {
    let origin_dir = tempfile::tempdir().unwrap();
    let scripts = tempfile::tempdir().unwrap();

    let origin = origin_engine(origin_dir.path(), Some(failing_adapter(scripts.path()))).await;
    let http = reqwest::Client::new();
    let room = invite(vec![member("server", "tcSERVERADDRESS000000000")]);
    let failed = redeem(&http, &origin.base, &room, "server", "Pixel").await;
    assert_eq!(failed.status(), 502);
    let body: Value = failed.json().await.unwrap();
    let error = body["error"].as_str().unwrap();
    assert!(error.contains("server"), "{error}");
    assert!(!error.contains("tcSERVERADDRESS"), "{error}");
    assert!(!serde_json::to_string(&body).unwrap().contains(SECRET));
    // The listener is not torn: it keeps answering (a shape refusal proves
    // the route and its validation still live).
    let refused = http
        .post(format!("{}/pairing/room-redeem", origin.base))
        .body("not json")
        .send()
        .await
        .unwrap();
    assert_eq!(refused.status(), 400);
    assert_eq!(refused.text().await.unwrap(), "expected JSON { invite, target, label }");
    // No secret on disk, and nothing was dialed through.
    assert_secret_never_at_rest(origin_dir.path());

    origin.task.abort();
    drop(origin.context);
    origin.core.shutdown().await;
}

/// The route's shape, CORS, and boundary surface (runs wherever the engine
/// tests run: none of these dials).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn room_redeem_shape_errors_are_cors_open_and_label_only() {
    let origin_dir = tempfile::tempdir().unwrap();
    let origin = origin_engine(origin_dir.path(), None).await;
    let http = reqwest::Client::new();
    let base = &origin.base;

    // The cross-origin preflight is answered exactly like the redeem routes.
    let preflight = http
        .request(reqwest::Method::OPTIONS, format!("{base}/pairing/room-redeem"))
        .header("origin", "https://web.example")
        .send()
        .await
        .unwrap();
    assert_eq!(preflight.status(), 204);
    assert_eq!(preflight.headers()["access-control-allow-origin"], "*");
    assert!(preflight
        .headers()["access-control-allow-methods"]
        .to_str()
        .unwrap()
        .contains("POST"));

    // Shape refusals: fixed text, never the payload.
    let bad = http
        .post(format!("{base}/pairing/room-redeem"))
        .json(&json!({"invite": "roboco-room:!!!!", "target": "server", "label": "Pixel"}))
        .send()
        .await
        .unwrap();
    assert_eq!(bad.status(), 400);
    let body = bad.text().await.unwrap();
    assert!(!body.contains("!!!!"), "{body}");
    for body in [
        json!({"target": "server", "label": "Pixel"}),
        json!({"invite": "roboco-room:x", "label": "Pixel"}),
        json!({"invite": "roboco-room:x", "target": "server"}),
        json!({"invite": "roboco-room:x", "target": "server", "label": "Pixel", "extra": 1}),
    ] {
        let response = http
            .post(format!("{base}/pairing/room-redeem"))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 400, "{body}");
    }
    // A room invite is required: a tailcat invite is refused with its own
    // fixed text.
    let tailcat = http
        .post(format!("{base}/pairing/room-redeem"))
        .json(&json!({"invite": "roboco-tailcat:eyJ2IjoxfQ", "target": "server", "label": "Pixel"}))
        .send()
        .await
        .unwrap();
    assert_eq!(tailcat.status(), 400);
    assert_eq!(
        tailcat.json::<Value>().await.unwrap()["error"],
        "a room invite starts with roboco-room:"
    );
    // A missing label or target is a shape error.
    let room = invite(vec![member("server", "tcSERVER1")]);
    for body in [
        json!({"invite": room.encode().unwrap(), "target": "server", "label": "  "}),
        json!({"invite": room.encode().unwrap(), "target": "", "label": "Pixel"}),
        json!({"invite": room.encode().unwrap(), "target": "no\u{7}pe", "label": "Pixel"}),
    ] {
        let response = http
            .post(format!("{base}/pairing/room-redeem"))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 400, "{body}");
    }

    // A target the invite does not carry names the label — and never an
    // address, never the secret.
    let missing = redeem(&http, base, &room, "laptop", "Pixel").await;
    assert_eq!(missing.status(), 404);
    assert_eq!(missing.headers()["access-control-allow-origin"], "*");
    let body: Value = missing.json().await.unwrap();
    let error = body["error"].as_str().unwrap();
    assert!(error.contains("laptop"), "{error}");
    assert!(!error.contains("tcSERVER1"), "{error}");
    assert!(!serde_json::to_string(&body).unwrap().contains(SECRET));

    // The route is paired-bind only: the local listener answers 404.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let local_base = format!("http://{}", listener.local_addr().unwrap());
    let local_task = tokio::spawn(serve_listener(
        listener,
        origin.core.rpc_service(),
        PairingStore::open(origin_dir.path()).unwrap(),
        RoomStore::open(origin_dir.path()).unwrap(),
    ));
    assert_eq!(
        redeem(&http, &local_base, &room, "server", "Pixel").await.status(),
        404
    );

    local_task.abort();
    assert!(RoomStore::open(origin_dir.path()).unwrap().list().unwrap().is_empty());
    assert_secret_never_at_rest(origin_dir.path());
    origin.task.abort();
    drop(origin.context);
    origin.core.shutdown().await;
}
