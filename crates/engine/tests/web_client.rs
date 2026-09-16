//! The browser path against the real listener: embedded pages, the lifted
//! origin policy on the remote bind (local IPC keeps it), CORS-open redeem,
//! and first-frame `Auth` WebSocket authentication.
use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use futures::{SinkExt as _, StreamExt as _};
use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::methods;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::{
    Message, client::IntoClientRequest, protocol::frame::coding::CloseCode,
};

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn remote_engine(dir: &std::path::Path) -> (EngineCore, roboco_engine::EngineListener) {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let listener =
        roboco_engine::serve_engine_remote("127.0.0.1:0".parse().unwrap(), core.rpc_service(), dir)
            .await
            .unwrap();
    (core, listener)
}

async fn local_listener(
    dir: &std::path::Path,
    core: &EngineCore,
) -> (String, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(roboco_engine::listener::serve_listener(
        listener,
        core.rpc_service(),
        PairingStore::open(dir).unwrap(),
    ));
    (base, task)
}

async fn redeem(http: &reqwest::Client, address: &std::net::SocketAddr, code: &str) -> Value {
    http.post(format!("http://{address}/pairing/redeem"))
        .bearer_auth(code)
        .json(&json!({"label":"Browser"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap()
}

/// A browser dial: Origin header (which browsers cannot suppress) and no
/// Authorization header (which browsers cannot set on a WebSocket).
async fn browser_socket(address: &std::net::SocketAddr) -> Socket {
    let mut request = format!("ws://{address}").into_client_request().unwrap();
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

/// Read messages until a reply frame for `id` arrives; frames may batch
/// several newline-separated envelopes per message.
async fn reply_frame(ws: &mut Socket, id: u64) -> Value {
    tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(Ok(Message::Text(text))) = ws.next().await {
            for line in text.lines() {
                let Ok(frame) = serde_json::from_str::<Value>(line.trim()) else {
                    continue;
                };
                if frame["id"] == id {
                    return frame;
                }
            }
        }
        panic!("socket closed before a reply to {id}");
    })
    .await
    .unwrap()
}

/// Read until the connection ends; the server must close with a close frame.
async fn close_frame(
    ws: &mut Socket,
) -> tokio_tungstenite::tungstenite::protocol::CloseFrame<'static> {
    while let Some(message) = ws.next().await {
        if let Ok(Message::Close(frame)) = message {
            return frame.expect("close frame");
        }
    }
    panic!("connection ended without a close frame");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn remote_listener_serves_embedded_pages_without_a_session() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    let (local_base, local_task) = local_listener(dir.path(), &core).await;
    let http = reqwest::Client::new();
    // The app shell must load before pairing, so no credential is required.
    // Markers match the staged Vite build as well as the hand-written
    // debug-only placeholders, so the test is green on a clean checkout.
    for (path, marker) in [("/", "<title>Roboco</title>"), ("/pair", "Pair this browser")] {
        let response = http
            .get(format!("http://{}{path}", remote.address))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200, "{path}");
        assert_eq!(
            response.headers()["content-type"],
            "text/html; charset=utf-8"
        );
        assert_eq!(response.headers()["cache-control"], "no-store");
        let body = response.text().await.unwrap();
        assert!(body.contains(marker), "{path} must serve the embedded page");
    }
    // Query strings stay refused — pair codes travel in the URL fragment.
    assert_eq!(
        http.get(format!("http://{}/?token=x", remote.address))
            .send()
            .await
            .unwrap()
            .status(),
        400
    );
    // Local IPC gains no pages: the native-only boundary is unchanged.
    assert_eq!(
        http.get(format!("{local_base}/pair"))
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    assert_eq!(http.get(local_base).send().await.unwrap().status(), 400);
    local_task.abort();
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn remote_listener_allows_browser_origins_local_ipc_rejects_them() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    let (local_base, local_task) = local_listener(dir.path(), &core).await;
    let http = reqwest::Client::new();
    let store = PairingStore::open(dir.path()).unwrap();
    let code = store.create_code("browser", 300).unwrap();
    let grant = redeem(&http, &remote.address, &code.credential).await;
    let credential = grant["credential"].as_str().unwrap();
    // Remote: an Origin-carrying request with a session succeeds.
    assert_eq!(
        http.get(format!("http://{}/health", remote.address))
            .header("origin", "https://browser.example")
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    // Remote: an Origin-carrying WebSocket handshake with a Bearer session
    // succeeds and serves RPC.
    let mut request = format!("ws://{}", remote.address)
        .into_client_request()
        .unwrap();
    request
        .headers_mut()
        .insert("origin", "https://browser.example".parse().unwrap());
    request.headers_mut().insert(
        "authorization",
        format!("Bearer {credential}").parse().unwrap(),
    );
    let (mut ws, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    send(
        &mut ws,
        json!({"id":1,"method":methods::ENGINE_INFO,"params":{}}),
    )
    .await;
    let reply = reply_frame(&mut ws, 1).await;
    assert_eq!(reply["ok"]["deviceId"], core.device_id.to_string());
    drop(ws);
    // Local: any request carrying Origin is refused, preflights included.
    assert_eq!(
        http.get(format!("{local_base}/health"))
            .header("origin", "https://browser.example")
            .send()
            .await
            .unwrap()
            .status(),
        403
    );
    assert_eq!(
        http.request(
            reqwest::Method::OPTIONS,
            format!("{local_base}/pairing/redeem")
        )
        .header("origin", "https://browser.example")
        .header("access-control-request-method", "POST")
        .send()
        .await
        .unwrap()
        .status(),
        403
    );
    local_task.abort();
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn redeem_answers_cross_origin_preflights_and_requests() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    let http = reqwest::Client::new();
    let redeem_url = format!("http://{}/pairing/redeem", remote.address);
    // A real preflight: answered without any credential.
    let preflight = http
        .request(reqwest::Method::OPTIONS, &redeem_url)
        .header("origin", "https://web.example")
        .header("access-control-request-method", "POST")
        .header(
            "access-control-request-headers",
            "authorization, content-type",
        )
        .send()
        .await
        .unwrap();
    assert_eq!(preflight.status(), 204);
    assert_eq!(preflight.headers()["access-control-allow-origin"], "*");
    assert!(
        preflight.headers()["access-control-allow-methods"]
            .to_str()
            .unwrap()
            .contains("POST")
    );
    let allowed_headers = preflight.headers()["access-control-allow-headers"]
        .to_str()
        .unwrap();
    assert!(allowed_headers.contains("authorization"));
    assert!(allowed_headers.contains("content-type"));
    // Cross-origin redeem: the bearer pair code is the credential.
    let store = PairingStore::open(dir.path()).unwrap();
    let code = store.create_code("web", 300).unwrap();
    let granted = http
        .post(&redeem_url)
        .header("origin", "https://web.example")
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Phone"}))
        .send()
        .await
        .unwrap();
    assert_eq!(granted.status(), 200);
    assert_eq!(granted.headers()["access-control-allow-origin"], "*");
    let grant: Value = granted.json().await.unwrap();
    assert_eq!(grant["credential"].as_str().unwrap().len(), 43);
    assert_eq!(grant["session"]["label"], "Phone");
    // A bad pair code fails exactly as today — and the browser can read why.
    let refused = http
        .post(&redeem_url)
        .header("origin", "https://web.example")
        .bearer_auth("x".repeat(43))
        .json(&json!({"label":"Phone"}))
        .send()
        .await
        .unwrap();
    assert_eq!(refused.status(), 401);
    assert_eq!(refused.headers()["access-control-allow-origin"], "*");
    // A bare OPTIONS (no Origin) is not a preflight and gets no special
    // treatment: the session gate answers as it does today.
    assert_eq!(
        http.request(reqwest::Method::OPTIONS, &redeem_url)
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    // No other route carries CORS headers.
    let health = http
        .get(format!("http://{}/health", remote.address))
        .header("origin", "https://web.example")
        .bearer_auth(grant["credential"].as_str().unwrap())
        .send()
        .await
        .unwrap();
    assert_eq!(health.status(), 200);
    assert!(!health.headers().contains_key("access-control-allow-origin"));
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn first_frame_auth_serves_browser_rpc_and_reports_the_capability() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    let http = reqwest::Client::new();
    let store = PairingStore::open(dir.path()).unwrap();
    let code = store.create_code("browser", 300).unwrap();
    let grant = redeem(&http, &remote.address, &code.credential).await;
    let credential = grant["credential"].as_str().unwrap().to_string();
    let session_id = grant["session"]["id"].as_str().unwrap().to_string();
    // First-frame Auth, then ordinary ndjson RPC on the same socket.
    let mut ws = browser_socket(&remote.address).await;
    send(&mut ws, json!({"auth": credential})).await;
    send(
        &mut ws,
        json!({"id":1,"method":methods::ENGINE_INFO,"params":{}}),
    )
    .await;
    let reply = reply_frame(&mut ws, 1).await;
    assert_eq!(reply["ok"]["deviceId"], core.device_id.to_string());
    assert!(
        reply["ok"]["capabilities"]
            .as_array()
            .unwrap()
            .contains(&json!("web-client"))
    );
    drop(ws);
    // Revocation gates the next first-frame handshake exactly like the
    // Bearer path: the credential stops authenticating.
    assert!(store.revoke(&session_id).unwrap());
    let mut ws = browser_socket(&remote.address).await;
    send(&mut ws, json!({"auth": credential})).await;
    let frame = tokio::time::timeout(Duration::from_secs(5), close_frame(&mut ws))
        .await
        .unwrap();
    assert_eq!(frame.code, CloseCode::Library(4401));
    assert_eq!(frame.reason, "invalid credential");
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn first_frame_auth_refuses_bad_credentials_and_plain_frames() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    // A well-formed but unknown secret.
    let mut ws = browser_socket(&remote.address).await;
    send(&mut ws, json!({"auth": "x".repeat(43)})).await;
    let frame = tokio::time::timeout(Duration::from_secs(5), close_frame(&mut ws))
        .await
        .unwrap();
    assert_eq!(frame.code, CloseCode::Library(4401));
    assert_eq!(frame.reason, "invalid credential");
    // An RPC frame first is not an Auth envelope.
    let mut ws = browser_socket(&remote.address).await;
    send(
        &mut ws,
        json!({"id":1,"method":methods::ENGINE_INFO,"params":{}}),
    )
    .await;
    let frame = tokio::time::timeout(Duration::from_secs(5), close_frame(&mut ws))
        .await
        .unwrap();
    assert_eq!(frame.code, CloseCode::Library(4401));
    // Garbage is not an Auth envelope either.
    let mut ws = browser_socket(&remote.address).await;
    ws.send(Message::Text("not json".to_string()))
        .await
        .unwrap();
    let frame = tokio::time::timeout(Duration::from_secs(5), close_frame(&mut ws))
        .await
        .unwrap();
    assert_eq!(frame.code, CloseCode::Library(4401));
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn first_frame_auth_times_out_a_silent_socket() {
    let dir = tempfile::tempdir().unwrap();
    let (core, remote) = remote_engine(dir.path()).await;
    let mut ws = browser_socket(&remote.address).await;
    let started = Instant::now();
    let frame = tokio::time::timeout(
        roboco_engine::listener::FIRST_FRAME_AUTH_TIMEOUT + Duration::from_secs(5),
        close_frame(&mut ws),
    )
    .await
    .unwrap();
    // The socket was refused for silence, not instantly rejected.
    assert!(
        started.elapsed()
            >= roboco_engine::listener::FIRST_FRAME_AUTH_TIMEOUT - Duration::from_secs(1)
    );
    assert_eq!(frame.code, CloseCode::Library(4401));
    assert_eq!(frame.reason, "authentication timeout");
    drop(remote);
    core.shutdown().await;
}
