//! HTTP pairing and WebSocket RPC share an engine listener.
//! The remote (paired) bind additionally serves the embedded web client pages
//! and authenticates browser WebSockets with a first-frame `Auth` envelope
//! (ADR 0006); local IPC keeps its native-only boundary.
use crate::pairing::PairingStore;
use base64::Engine as _;
use bytes::Bytes;
use futures::{SinkExt as _, StreamExt as _};
use http_body_util::{BodyExt, Full, Limited};
use hyper::{Request, Response, StatusCode, body::Incoming, service::service_fn};
use hyper_util::rt::{TokioIo, TokioTimer};
use std::{convert::Infallible, sync::Arc, time::Duration};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::{
    Message,
    handshake::derive_accept_key,
    protocol::{CloseFrame, Role, frame::coding::CloseCode},
};

type Reply = Response<Full<Bytes>>;

/// How long a browser connection may stay silent before its first-frame
/// `Auth` envelope arrives. The credential is sent the moment the socket
/// opens, so this only bounds abandoned sockets.
pub const FIRST_FRAME_AUTH_TIMEOUT: Duration = Duration::from_secs(10);

/// The bind determines policy, never the address of an individual peer.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum AccessPolicy {
    Local,
    Paired,
}

/// Serve a previously bound local socket, preserving credential-free native IPC.
pub async fn serve_listener(
    listener: TcpListener,
    service: Arc<dyn roboco_rpc::RpcService>,
    pairing: PairingStore,
) {
    serve_listener_with_policy(listener, service, pairing, AccessPolicy::Local).await;
}

pub async fn serve_listener_with_policy(
    listener: TcpListener,
    service: Arc<dyn roboco_rpc::RpcService>,
    pairing: PairingStore,
    policy: AccessPolicy,
) {
    let cancel = tokio_util::sync::CancellationToken::new();
    let _cancel_on_drop = cancel.clone().drop_guard();
    if let Ok(address) = listener.local_addr() {
        tracing::info!(%address, paired = policy == AccessPolicy::Paired, "engine listener serving");
    }
    loop {
        match listener.accept().await {
            Ok((stream, _)) => {
                let service = service.clone();
                let pairing = pairing.clone();
                let connection_cancel = cancel.clone();
                tokio::spawn(async move {
                    let request_cancel = connection_cancel.clone();
                    let handler = service_fn(move |request| {
                        handle(
                            request,
                            service.clone(),
                            pairing.clone(),
                            policy,
                            request_cancel.clone(),
                        )
                    });
                    let mut builder = hyper::server::conn::http1::Builder::new();
                    builder
                        .timer(TokioTimer::new())
                        .header_read_timeout(Duration::from_secs(10))
                        .max_buf_size(16 * 1024);
                    tokio::select! {
                        _ = connection_cancel.cancelled() => {},
                        _ = builder.serve_connection(TokioIo::new(stream), handler).with_upgrades() => {},
                    }
                });
            }
            Err(error) => {
                tracing::warn!(%error, "engine listener accept failed");
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

async fn handle(
    mut request: Request<Incoming>,
    service: Arc<dyn roboco_rpc::RpcService>,
    pairing: PairingStore,
    policy: AccessPolicy,
    cancel: tokio_util::sync::CancellationToken,
) -> Result<Reply, Infallible> {
    // Preserve the native-only local IPC boundary for HTTP as well as
    // WebSocket. The remote listener serves browsers, so it accepts
    // Origin-carrying requests — the session credential is the lock, never
    // the origin (ADR 0006).
    if policy == AccessPolicy::Local && request.headers().contains_key("origin") {
        return Ok(reply(
            StatusCode::FORBIDDEN,
            "origin not allowed on local IPC",
        ));
    }
    if request.uri().query().is_some() {
        return Ok(reply(
            StatusCode::BAD_REQUEST,
            "query parameters are not supported",
        ));
    }
    let path = request.uri().path();
    if path == "/pairing/redeem"
        && request.method() == hyper::Method::OPTIONS
        && request.headers().contains_key("origin")
    {
        // A cross-origin preflight is the only OPTIONS answered; the redeem
        // route is CORS-open because the bearer pair code is the credential.
        return Ok(Response::builder()
            .status(StatusCode::NO_CONTENT)
            .header("cache-control", "no-store")
            .header("access-control-allow-origin", "*")
            .header("access-control-allow-methods", "POST, OPTIONS")
            .header(
                "access-control-allow-headers",
                "authorization, content-type",
            )
            .header("access-control-max-age", "600")
            .body(Full::new(Bytes::new()))
            .unwrap());
    }
    if path == "/pairing/redeem" && request.method() == hyper::Method::POST {
        return Ok(cors_open(redeem(request, pairing).await));
    }
    // A browser WebSocket upgrade carries no Authorization header; validate
    // the upgrade shape once so the session gate can let it through for
    // first-frame authentication.
    let upgrade_key = if path == "/" && request.method() == hyper::Method::GET {
        websocket_key(&request).map(str::to_owned)
    } else {
        None
    };
    // The web client shell must load before pairing, so the asset routes sit
    // ahead of the session gate. Only the paired bind serves them; the data
    // behind the socket and the redeem route stays credential-gated. The
    // bundle is the staged Vite build (build.rs → crates/engine/web-staging/),
    // so this serves the real React app: the HTML shell at `/` and `/pair`,
    // every JS/CSS/font chunk under `/assets/`, and the app shell for any
    // client-side route (TanStack Router takes over after a hard refresh).
    // A WebSocket upgrade to `/` is the RPC channel and must NOT be served
    // HTML — it falls through to the credential gate and the upgrade block
    // further down.
    if policy == AccessPolicy::Paired
        && request.method() == hyper::Method::GET
        && upgrade_key.is_none()
    {
        if path == "/pair" {
            return Ok(web_page("pair.html"));
        }
        if path == "/" {
            return Ok(web_page("index.html"));
        }
        if let Some(reply) = web_static_asset(path) {
            return Ok(reply);
        }
        // SPA fallback: any non-extension path is a client-side route and
        // gets the app shell. Reserved paths still fall through to the
        // credential gate below.
        if !RESERVED_API_PATHS.contains(&path) && !path.contains('.') {
            return Ok(web_page("index.html"));
        }
    }
    // Redemption above authenticates a single-use pair code. Every other
    // remote request requires a current session, including health and
    // upgrades. A browser upgrade presents no Bearer credential here and
    // authenticates with a first-frame `Auth` envelope after the upgrade;
    // the native Bearer path is unchanged.
    let mut first_frame_auth = false;
    if policy == AccessPolicy::Paired {
        match bearer(&request).map(str::to_owned) {
            Some(credential) => {
                let store = pairing.clone();
                match tokio::task::spawn_blocking(move || store.authenticate(&credential)).await {
                    Ok(Ok(Some(_))) => {}
                    Ok(Ok(None)) => {
                        return Ok(reply(StatusCode::UNAUTHORIZED, "invalid credential"));
                    }
                    _ => {
                        return Ok(reply(
                            StatusCode::SERVICE_UNAVAILABLE,
                            "session check unavailable",
                        ));
                    }
                }
            }
            None if upgrade_key.is_some() => first_frame_auth = true,
            None => return Ok(reply(StatusCode::UNAUTHORIZED, "invalid credential")),
        }
    }
    if path == "/health" && request.method() == hyper::Method::GET {
        return Ok(json(StatusCode::OK, &serde_json::json!({"status":"ok"})));
    }
    if path != "/" || request.method() != hyper::Method::GET {
        return Ok(reply(StatusCode::NOT_FOUND, "not found"));
    }
    let Some(key) = upgrade_key else {
        return Ok(reply(StatusCode::BAD_REQUEST, "expected WebSocket upgrade"));
    };
    let accept = derive_accept_key(key.as_bytes());
    let upgraded = hyper::upgrade::on(&mut request);
    tokio::spawn(async move {
        let Ok(io) = upgraded.await else { return };
        let (io, progress) = roboco_rpc::ProgressIo::new(TokioIo::new(io));
        let mut ws =
            tokio_tungstenite::WebSocketStream::from_raw_socket(io, Role::Server, None).await;
        if first_frame_auth {
            let authenticated = tokio::select! {
                _ = cancel.cancelled() => false,
                authenticated = authenticate_first_frame(&mut ws, &pairing) => authenticated,
            };
            if !authenticated {
                return;
            }
        }
        tokio::select! {
            _ = cancel.cancelled() => {},
            _ = roboco_rpc::serve_websocket(
                roboco_rpc::Connection { socket: ws, progress },
                service,
            ) => {},
        }
    });
    Ok(Response::builder()
        .status(StatusCode::SWITCHING_PROTOCOLS)
        .header("upgrade", "websocket")
        .header("connection", "Upgrade")
        .header("sec-websocket-accept", accept)
        .body(Full::new(Bytes::new()))
        .unwrap())
}

/// `POST /pairing/redeem`: the pair code itself is the credential.
async fn redeem(request: Request<Incoming>, pairing: PairingStore) -> Reply {
    let Some(code) = bearer(&request).map(str::to_owned) else {
        return reply(StatusCode::UNAUTHORIZED, "invalid credential");
    };
    let body = match tokio::time::timeout(
        Duration::from_secs(5),
        Limited::new(request.into_body(), 4096).collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        _ => return reply(StatusCode::BAD_REQUEST, "invalid request body"),
    };
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Redemption {
        #[serde(default)]
        label: String,
    }
    let Ok(input) = serde_json::from_slice::<Redemption>(&body) else {
        return reply(StatusCode::BAD_REQUEST, "expected device label");
    };
    let result = tokio::task::spawn_blocking(move || pairing.redeem(&code, &input.label)).await;
    match result {
        Ok(Ok(Some(grant))) => json(StatusCode::OK, &grant),
        Ok(Ok(None)) => reply(StatusCode::UNAUTHORIZED, "invalid credential"),
        _ => reply(StatusCode::BAD_REQUEST, "redemption failed"),
    }
}

/// Gate a browser WebSocket on its first frame: browsers cannot set
/// `Authorization` on the handshake, so the session credential arrives as an
/// `Auth` envelope (`{"auth":"<credential>"}`) in the first text frame.
/// A wrong, malformed, or missing credential closes the connection before any
/// RPC is served; `roboco_rpc::serve_websocket` stays agnostic so the
/// unauthenticated local IPC path is untouched.
async fn authenticate_first_frame<S>(
    ws: &mut tokio_tungstenite::WebSocketStream<S>,
    pairing: &PairingStore,
) -> bool
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let first = match tokio::time::timeout(FIRST_FRAME_AUTH_TIMEOUT, ws.next()).await {
        Ok(Some(Ok(Message::Text(text)))) => text,
        Ok(Some(Ok(_))) => return refuse(ws, "invalid credential").await,
        Ok(Some(Err(_))) | Ok(None) => return false,
        Err(_) => return refuse(ws, "authentication timeout").await,
    };
    // The envelope is the first line of the frame; ndjson batching puts
    // nothing before it.
    let credential = first
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .and_then(|line| serde_json::from_str::<serde_json::Value>(line).ok())
        .and_then(|frame| frame.get("auth")?.as_str().map(str::to_owned));
    let Some(credential) = credential else {
        return refuse(ws, "invalid credential").await;
    };
    let store = pairing.clone();
    match tokio::task::spawn_blocking(move || store.authenticate(&credential)).await {
        Ok(Ok(Some(_))) => true,
        Ok(Ok(None)) => refuse(ws, "invalid credential").await,
        _ => refuse(ws, "session check unavailable").await,
    }
}

/// Close with 4401 so a browser can tell a refused session apart from a
/// network drop; the reasons match the HTTP gate's bodies. Always returns
/// false so the caller can `return refuse(...)`.
async fn refuse<S>(ws: &mut tokio_tungstenite::WebSocketStream<S>, reason: &'static str) -> bool
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let _ = ws
        .send(Message::Close(Some(CloseFrame {
            code: CloseCode::Library(4401),
            reason: reason.into(),
        })))
        .await;
    false
}

/// The validated WebSocket key when the request is a well-formed upgrade.
fn websocket_key(request: &Request<Incoming>) -> Option<&str> {
    let upgrade = request.headers().get("upgrade")?.to_str().ok()?;
    let connection = request
        .headers()
        .get("connection")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    let version = request
        .headers()
        .get("sec-websocket-version")
        .and_then(|v| v.to_str().ok());
    let key = request.headers().get("sec-websocket-key")?.to_str().ok()?;
    if !upgrade.eq_ignore_ascii_case("websocket")
        || !connection
            .split(',')
            .any(|v| v.trim().eq_ignore_ascii_case("upgrade"))
        || version != Some("13")
        || !base64::engine::general_purpose::STANDARD
            .decode(key)
            .is_ok_and(|bytes| bytes.len() == 16)
    {
        return None;
    }
    Some(key)
}

/// One embedded web client page. The bundle is compiled in, so a missing
/// name is a build-time mistake, not a runtime case.
fn web_page(name: &str) -> Reply {
    let file = crate::web::WebAssets::get(name).expect("embedded web page");
    Response::builder()
        .status(StatusCode::OK)
        .header("cache-control", "no-store")
        .header("content-type", "text/html; charset=utf-8")
        .body(Full::new(Bytes::from(file.data.into_owned())))
        .unwrap()
}

/// A static asset from the embedded web bundle: the Vite build's hashed
/// JS/CSS/font/image chunks. The path must not escape the bundle (no `..`
/// segments, no leading `/`), and the answer is `None` when the bundle has
/// no such file — the caller falls through to the SPA shell or 404.
fn web_static_asset(path: &str) -> Option<Reply> {
    if path == "/" {
        return None;
    }
    let stripped = path.strip_prefix('/').unwrap_or(path);
    if stripped.is_empty() || stripped.contains("..") {
        return None;
    }
    let file = crate::web::WebAssets::get(stripped)?;
    let cache_control = static_asset_cache_control(stripped);
    Some(
        Response::builder()
            .status(StatusCode::OK)
            .header("cache-control", cache_control)
            .header("content-type", crate::web::content_type(stripped))
            .body(Full::new(Bytes::from(file.data.into_owned())))
            .unwrap(),
    )
}

/// `immutable` for hashed assets (Vite emits content hashes in the
/// filenames, so the URL changes whenever the bytes do) and `no-store`
/// for everything else. The HTML shell is served via [`web_page`].
fn static_asset_cache_control(name: &str) -> &'static str {
    if name.starts_with("assets/") {
        "public, max-age=31536000, immutable"
    } else {
        "no-store"
    }
}

/// Paths that must NOT fall through to the SPA shell. They are real
/// engine routes (credential-gated health, the redeem endpoint) and
/// returning the app shell for them would mask a 401 / 404.
const RESERVED_API_PATHS: &[&str] = &["/health", "/pairing/redeem"];

/// Allow any origin to read a redeem response; the bearer pair code is the
/// credential, never the origin. No other route gets CORS headers.
fn cors_open(mut response: Reply) -> Reply {
    response
        .headers_mut()
        .insert("access-control-allow-origin", "*".parse().unwrap());
    response
}

fn bearer(request: &Request<Incoming>) -> Option<&str> {
    if request.headers().get_all("authorization").iter().count() != 1 {
        return None;
    }
    request
        .headers()
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}
fn reply(status: StatusCode, body: &'static str) -> Reply {
    Response::builder()
        .status(status)
        .header("cache-control", "no-store")
        .body(Full::new(Bytes::from_static(body.as_bytes())))
        .unwrap()
}
fn json(status: StatusCode, value: &impl serde::Serialize) -> Reply {
    Response::builder()
        .status(status)
        .header("cache-control", "no-store")
        .header("content-type", "application/json")
        .body(Full::new(Bytes::from(
            serde_json::to_vec(value).expect("serializable response"),
        )))
        .unwrap()
}
