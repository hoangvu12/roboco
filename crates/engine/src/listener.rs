//! HTTP pairing and WebSocket RPC share an engine listener.
//! The remote (paired) bind requires a Bearer session on every route but the
//! redeem-style ones; local IPC keeps its credential-free native-only
//! boundary. Neither bind has browser clients (ADR 0012): both refuse
//! requests that carry an `Origin`.
use crate::pairing::PairingStore;
use crate::rooms::{PresentationOutcome, RoomStore};
use base64::Engine as _;
use bytes::Bytes;
use http_body_util::{BodyExt, Full, Limited};
use hyper::{Request, Response, StatusCode, body::Incoming, service::service_fn};
use hyper_util::rt::{TokioIo, TokioTimer};
use std::{convert::Infallible, sync::Arc, time::Duration};
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::{handshake::derive_accept_key, protocol::Role};

type Reply = Response<Full<Bytes>>;

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
    rooms: RoomStore,
) {
    serve_listener_with_policy(listener, service, pairing, rooms, AccessPolicy::Local, None).await;
}

/// The local bind with the preview relay route: same native-only boundary
/// (no credentials, no origins), with `/preview-relay` upgrades served for
/// same-machine clients that want the uniform relay path instead of the
/// engine's own 7331 proxy.
pub async fn serve_listener_with_previews(
    listener: TcpListener,
    service: Arc<dyn roboco_rpc::RpcService>,
    pairing: PairingStore,
    rooms: RoomStore,
    previews: roboco_preview::PreviewService,
) {
    serve_listener_with_policy(
        listener,
        service,
        pairing,
        rooms,
        AccessPolicy::Local,
        Some(previews),
    )
    .await;
}

pub async fn serve_listener_with_policy(
    listener: TcpListener,
    service: Arc<dyn roboco_rpc::RpcService>,
    pairing: PairingStore,
    rooms: RoomStore,
    policy: AccessPolicy,
    previews: Option<roboco_preview::PreviewService>,
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
                let rooms = rooms.clone();
                let connection_cancel = cancel.clone();
                let previews = previews.clone();
                tokio::spawn(async move {
                    let request_cancel = connection_cancel.clone();
                    let handler = service_fn(move |request| {
                        handle(
                            request,
                            service.clone(),
                            pairing.clone(),
                            rooms.clone(),
                            policy,
                            previews.clone(),
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
    rooms: RoomStore,
    policy: AccessPolicy,
    previews: Option<roboco_preview::PreviewService>,
    cancel: tokio_util::sync::CancellationToken,
) -> Result<Reply, Infallible> {
    // Preserve the native-only local IPC boundary for HTTP as well as
    // WebSocket.
    if policy == AccessPolicy::Local && request.headers().contains_key("origin") {
        return Ok(reply(
            StatusCode::FORBIDDEN,
            "origin not allowed on local IPC",
        ));
    }
    // The paired bind has no browser clients either (ADR 0012). Native
    // clients (tungstenite, reqwest, the mobile core) send no Origin, so a
    // request carrying one is a web page reaching in: refused before any
    // route, redeem and room presentation included.
    if policy == AccessPolicy::Paired && request.headers().contains_key("origin") {
        return Ok(reply(StatusCode::FORBIDDEN, "origin not allowed"));
    }
    if request.uri().query().is_some() {
        return Ok(reply(
            StatusCode::BAD_REQUEST,
            "query parameters are not supported",
        ));
    }
    let path = request.uri().path().to_owned();
    if path == "/pairing/redeem" && request.method() == hyper::Method::POST {
        return Ok(redeem(request, pairing).await);
    }
    if path == "/room/present" && request.method() == hyper::Method::POST {
        return Ok(room_present(request, rooms).await);
    }
    let websocket_key = websocket_key(&request).map(str::to_owned);
    let preview_relay = crate::preview_relay::scope_from_path(&path);
    let relay_upgrade = preview_relay.is_some()
        && request.method() == hyper::Method::GET
        && websocket_key.is_some();
    let upgrade_key = if path == "/" && request.method() == hyper::Method::GET {
        websocket_key
    } else {
        None
    };
    // The paired bind serves no pages (ADR 0012). Anything that is not an
    // engine route — a plain GET of `/`, `/pair`, any other path — is a 404
    // ahead of the session gate, so a browser following a pairing link gets
    // nothing to load.
    if policy == AccessPolicy::Paired {
        let session_route = (path == "/health" && request.method() == hyper::Method::GET)
            || upgrade_key.is_some()
            || relay_upgrade;
        if !session_route {
            return Ok(reply(StatusCode::NOT_FOUND, "not found"));
        }
    }
    // Redemption above authenticates a single-use pair code. Every other
    // remote request requires a current session presented as a Bearer
    // header, health and both upgrades included: a missing one is a 401
    // before any upgrade.
    if policy == AccessPolicy::Paired {
        let Some(credential) = bearer(&request).map(str::to_owned) else {
            return Ok(reply(StatusCode::UNAUTHORIZED, "invalid credential"));
        };
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
    // The preview relay rides the same gate: on the paired bind it is
    // reachable only through it, on the local bind the native-only boundary
    // stands unchanged — no credential, as with the RPC socket.
    if let Some(scope) = preview_relay {
        if relay_upgrade {
            let Some(previews) = previews else {
                return Ok(reply(StatusCode::NOT_FOUND, "not found"));
            };
            return Ok(crate::preview_relay::serve_relay_websocket(
                scope, request, previews, cancel,
            ));
        }
        return Ok(reply(StatusCode::NOT_FOUND, "not found"));
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
        let ws =
            tokio_tungstenite::WebSocketStream::from_raw_socket(io, Role::Server, None).await;
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

/// `POST /room/present {secret, label}`: redeem-style and pre-session, next
/// to `/pairing/redeem` (ADR 0011). The room secret is verified against the
/// stored verifier — hashed and discarded, never persisted, never logged;
/// refusals describe only shape — and mints an ordinary session labeled by
/// the presenter. Repeatable: re-presenting with the same label reuses the
/// room's session. This route never mutates remote-access settings.
async fn room_present(request: Request<Incoming>, rooms: RoomStore) -> Reply {
    let body = match tokio::time::timeout(
        Duration::from_secs(5),
        Limited::new(request.into_body(), 8192).collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        _ => return reply(StatusCode::BAD_REQUEST, "invalid request body"),
    };
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Presentation {
        secret: String,
        label: String,
    }
    let Ok(input) = serde_json::from_slice::<Presentation>(&body) else {
        return reply(StatusCode::BAD_REQUEST, "expected JSON { secret, label }");
    };
    if input.label.trim().is_empty() {
        return reply(
            StatusCode::BAD_REQUEST,
            "a room presentation carries a device label",
        );
    }
    if crate::pairing::validate_label(&input.label).is_err() {
        return reply(
            StatusCode::BAD_REQUEST,
            "device label must contain at most 256 bytes and no control characters",
        );
    }
    let secret = input.secret;
    let label = input.label;
    let result = tokio::task::spawn_blocking(move || rooms.present(&secret, &label)).await;
    match result {
        Ok(Ok(PresentationOutcome::Granted(grant))) => json(StatusCode::OK, &grant),
        Ok(Ok(PresentationOutcome::Refused)) => {
            reply(StatusCode::UNAUTHORIZED, "invalid credential")
        }
        Ok(Ok(PresentationOutcome::Throttled)) => reply(
            StatusCode::TOO_MANY_REQUESTS,
            "too many failed presentations; try again later",
        ),
        _ => reply(StatusCode::SERVICE_UNAVAILABLE, "presentation unavailable"),
    }
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
