//! Server-side Tailcat pairing for browsers: dial a remote engine, redeem the
//! invite, and expose it at `/tailcat-relay/{id}` on this listener so the web
//! client never needs a local `browser-helper` process.
//!
//! `/pairing/room-redeem` (ADR 0011) is the room-shaped sibling: a browser,
//! which cannot dial, hands the engine serving it a `roboco-room:` invite
//! and the label of the member to reach. The invite's members ARE the
//! address book, so the origin engine holds no prior membership — it parses
//! the invite, dials the named member through its own Tailcat client, relays
//! the `/room/present` round-trip over the tunnel, and returns the member's
//! grant on the same `/tailcat-relay/{id}` byte pump. The room secret rides
//! that round-trip in transit and is never persisted: the origin engine's
//! only disk footprint is the tunnel state every relay already keeps
//! (`tailcat-relays/{id}/`), preserving the verifier-only invariant.

use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
};

use bytes::Bytes;
use futures::{SinkExt as _, StreamExt as _};
use http_body_util::{BodyExt, Full, Limited};
use hyper::{Request, Response, StatusCode, body::Incoming};
use roboco_rpc::ProgressIo;
use tokio_tungstenite::{
    connect_async,
    tungstenite::{
        Message,
        handshake::derive_accept_key,
        protocol::{CloseFrame, Role, frame::coding::CloseCode},
    },
};
use uuid::Uuid;

use crate::tailcat::{self, TailcatClient, TailcatInvite};
use roboco_proto::room::{RoomInvite, RoomMember};

type Reply = Response<Full<Bytes>>;

pub struct TailcatRelayRegistry {
    inner: Mutex<HashMap<String, TailcatRelay>>,
}

struct TailcatRelay {
    downstream_ws: String,
    _client: TailcatClient,
}

impl TailcatRelayRegistry {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(HashMap::new()),
        })
    }

    /// Reload every relay under `{data_dir}/tailcat-relays/` after a process restart.
    pub fn restore_from_disk(data_dir: &PathBuf, registry: &Arc<Self>) -> anyhow::Result<()> {
        let root = data_dir.join("tailcat-relays");
        if !root.is_dir() {
            return Ok(());
        }
        let adapter = tailcat::adapter_path()?;
        for entry in std::fs::read_dir(&root)? {
            let entry = entry?;
            if !entry.file_type()?.is_dir() {
                continue;
            }
            let relay_id = entry.file_name().to_string_lossy().into_owned();
            let relay_dir = entry.path();
            let Some(client_dir) = client_state_dir(&relay_dir) else {
                tracing::debug!(relay_id = %relay_id, "skipping tailcat relay with no client state");
                continue;
            };
            match TailcatClient::resume_with_adapter(adapter.clone(), &client_dir, None) {
                Ok(client) => {
                    let downstream = client.url().trim_end_matches('/').to_string();
                    registry.insert(relay_id.clone(), &downstream, client);
                    tracing::info!(relay_id = %relay_id, "tailcat relay restored");
                }
                Err(error) => {
                    tracing::warn!(relay_id = %relay_id, %error, "tailcat relay restore failed");
                }
            }
        }
        Ok(())
    }

    pub fn insert(&self, id: String, downstream_http: &str, client: TailcatClient) {
        let downstream_ws = downstream_http
            .replacen("http://", "ws://", 1)
            .replacen("https://", "wss://", 1)
            .trim_end_matches('/')
            .to_string()
            + "/";
        self.inner.lock().unwrap().insert(
            id,
            TailcatRelay {
                downstream_ws,
                _client: client,
            },
        );
    }

    fn downstream_ws(&self, id: &str) -> Option<String> {
        self.inner
            .lock()
            .unwrap()
            .get(id)
            .map(|relay| relay.downstream_ws.clone())
    }
}

#[derive(Clone)]
pub struct PairedListenerContext {
    pub data_dir: PathBuf,
    pub tailcat_relays: Arc<TailcatRelayRegistry>,
    /// Adapter override for relay dials, following the
    /// `TailcatClient::start_with_adapter` seam: supervision tests drive a
    /// scripted stand-in adapter. `None` (the engine itself) resolves the
    /// installed adapter when a dial runs.
    pub adapter: Option<PathBuf>,
}

/// The adapter a relay dial runs: the context's override, else the installed
/// one. Runs inside the dial's blocking task — resolving the installed
/// adapter touches the file system.
fn dial_adapter(adapter: Option<PathBuf>) -> anyhow::Result<PathBuf> {
    match adapter {
        Some(adapter) => Ok(adapter),
        None => tailcat::adapter_path(),
    }
}

fn client_state_dir(relay_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let clients = relay_dir.join("tailcat/clients");
    let mut dirs: Vec<std::path::PathBuf> = std::fs::read_dir(&clients)
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.path().is_dir())
        .map(|entry| entry.path())
        .collect();
    dirs.sort();
    dirs.into_iter().next()
}

pub fn relay_id_from_path(path: &str) -> Option<&str> {
    let id = path.strip_prefix("/tailcat-relay/")?;
    let id = id.trim_end_matches('/');
    if id.is_empty() || id.contains('/') {
        return None;
    }
    Some(id)
}

pub fn public_relay_base(request: &Request<Incoming>, relay_id: &str) -> String {
    let host = request
        .headers()
        .get("host")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("127.0.0.1");
    let proto = request
        .headers()
        .get("x-forwarded-proto")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("http");
    format!("{proto}://{host}/tailcat-relay/{relay_id}")
}

pub async fn tailcat_redeem(
    request: Request<Incoming>,
    context: &PairedListenerContext,
) -> Reply {
    let public_host = request
        .headers()
        .get("host")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("127.0.0.1")
        .to_owned();
    let public_proto = request
        .headers()
        .get("x-forwarded-proto")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("http")
        .to_owned();
    let body = match tokio::time::timeout(
        std::time::Duration::from_secs(10),
        Limited::new(request.into_body(), 65_536).collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        _ => return reply(StatusCode::BAD_REQUEST, "invalid request body"),
    };
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Body {
        invite: String,
        label: String,
    }
    let Ok(input) = serde_json::from_slice::<Body>(&body) else {
        return reply(StatusCode::BAD_REQUEST, "expected JSON { invite, label }");
    };
    let invite = match TailcatInvite::decode(&input.invite) {
        Ok(invite) => invite,
        Err(error) => {
            return json(
                StatusCode::BAD_REQUEST,
                &serde_json::json!({ "error": error.to_string() }),
            );
        }
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    if invite.expired(now) {
        return json(
            StatusCode::UNAUTHORIZED,
            &serde_json::json!({ "error": "that tailcat invite has expired; mint a new one" }),
        );
    }
    let relay_id = Uuid::new_v4().to_string();
    let relay_dir = context
        .data_dir
        .join("tailcat-relays")
        .join(&relay_id);
    let token = invite.token.clone();
    let address = invite.address.clone();
    let adapter_override = context.adapter.clone();
    let client = match tokio::task::spawn_blocking(move || {
        let adapter = dial_adapter(adapter_override)?;
        TailcatClient::start_with_adapter(adapter, &relay_dir, &address, None)
    })
    .await
    {
        Ok(Ok(client)) => client,
        Ok(Err(error)) => {
            return json(
                StatusCode::BAD_GATEWAY,
                &serde_json::json!({ "error": error.to_string() }),
            );
        }
        Err(_) => return reply(StatusCode::INTERNAL_SERVER_ERROR, "tailcat connect failed"),
    };
    let downstream = client.url().trim_end_matches('/').to_string();
    let redeem_url = format!("{downstream}/pairing/redeem");
    let http = match reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(std::time::Duration::from_secs(15))
        .build()
    {
        Ok(client) => client,
        Err(_) => return reply(StatusCode::INTERNAL_SERVER_ERROR, "http client failed"),
    };
    let response = match http
        .post(&redeem_url)
        .bearer_auth(&token)
        .json(&serde_json::json!({ "label": input.label }))
        .send()
        .await
    {
        Ok(response) => response,
        Err(_) => {
            return json(
                StatusCode::BAD_GATEWAY,
                &serde_json::json!({ "error": "could not reach the engine through Tailcat" }),
            );
        }
    };
    if !response.status().is_success() {
        return json(
            StatusCode::UNAUTHORIZED,
            &serde_json::json!({ "error": "pairing was refused or has expired" }),
        );
    }
    let mut grant: serde_json::Value = match response.json().await {
        Ok(grant) => grant,
        Err(_) => return reply(StatusCode::BAD_GATEWAY, "invalid pairing response"),
    };
    context
        .tailcat_relays
        .insert(relay_id.clone(), &downstream, client);
    let public_base = format!("{public_proto}://{public_host}/tailcat-relay/{relay_id}");
    if let Some(object) = grant.as_object_mut() {
        object.insert("baseUrl".into(), public_base.into());
    }
    json(StatusCode::OK, &grant)
}

/// Resolve `target` (a member label) among the invite's members — the invite
/// IS the address book on this path. A repeated label dials the first entry
/// carrying it: books only ever union (a stale address is corrected by a
/// fresh entry arriving, never removed), and the caller holds the book, so
/// it controls the order of the members it pastes.
fn resolve_target<'a>(members: &'a [RoomMember], target: &str) -> Option<&'a RoomMember> {
    members.iter().find(|member| member.label == target)
}

/// `POST /pairing/room-redeem {invite, target, label}`: the browser join
/// path of ADR 0011, next to `/pairing/tailcat-redeem` on the paired
/// listener. The invite's members ARE the address book, so the origin
/// engine needs no prior membership — and it must never persist the invite
/// or the room secret: the secret rides the presentation round-trip in
/// transit, then stops existing. This engine resolves `target` (a member
/// label) among the invite's members, dials that member through its own
/// Tailcat client — tunnel state under `tailcat-relays/{id}/` exactly like
/// every other relay — and forwards the caller's `/room/present
/// {secret, label}` call over the tunnel (ticket 01's pre-session surface,
/// served by the member). The member's session grant comes back with
/// `baseUrl` rewritten to this listener's `/tailcat-relay/{id}`, and the
/// caller's WebSocket frames then ride the plain byte pump
/// ([`serve_relay_websocket`]): its own `Auth` envelope and all traffic pass
/// opaquely, so the member stays the only engine that ever saw the caller's
/// traffic as more than bytes. Each redeem mints a fresh relay, exactly as
/// `/pairing/tailcat-redeem` does (registry semantics; relay-directory
/// growth is separate hygiene). An unreachable or refusing member degrades
/// to a per-member failure naming the label — never the member's address,
/// never the secret — not a torn listener.
pub async fn room_redeem(
    request: Request<Incoming>,
    context: &PairedListenerContext,
) -> Reply {
    let public_host = request
        .headers()
        .get("host")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("127.0.0.1")
        .to_owned();
    let public_proto = request
        .headers()
        .get("x-forwarded-proto")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("http")
        .to_owned();
    // An invite is up to 64 KiB of members; the label and target add a few
    // hundred bytes at most.
    let body = match tokio::time::timeout(
        std::time::Duration::from_secs(10),
        Limited::new(
            request.into_body(),
            roboco_proto::room::MAX_INVITE_BYTES + 1024,
        )
        .collect(),
    )
    .await
    {
        Ok(Ok(body)) => body.to_bytes(),
        _ => return reply(StatusCode::BAD_REQUEST, "invalid request body"),
    };
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Body {
        invite: String,
        target: String,
        label: String,
    }
    let Ok(input) = serde_json::from_slice::<Body>(&body) else {
        return reply(
            StatusCode::BAD_REQUEST,
            "expected JSON { invite, target, label }",
        );
    };
    let invite = match RoomInvite::decode(&input.invite) {
        Ok(invite) => invite,
        Err(error) => {
            return json(
                StatusCode::BAD_REQUEST,
                &serde_json::json!({ "error": error.to_string() }),
            );
        }
    };
    let label = input.label.trim().to_owned();
    if label.is_empty() {
        return reply(
            StatusCode::BAD_REQUEST,
            "a room presentation carries a device label",
        );
    }
    if crate::pairing::validate_label(&label).is_err() {
        return reply(
            StatusCode::BAD_REQUEST,
            "device label must contain at most 256 bytes and no control characters",
        );
    }
    let target = input.target.trim().to_owned();
    if target.is_empty()
        || target.len() > roboco_proto::room::MAX_LABEL_BYTES
        || target.chars().any(char::is_control)
    {
        return reply(
            StatusCode::BAD_REQUEST,
            "a room redemption names one member of the invite by label",
        );
    }
    // The invite is the whole consultation: no store lookup, no membership.
    // The secret and the members are dropped before the reply is built.
    let RoomInvite { secret, members, .. } = invite;
    let Some(member) = resolve_target(&members, &target) else {
        return json(
            StatusCode::NOT_FOUND,
            &serde_json::json!({
                "error": format!("the invite has no member labeled \"{target}\"")
            }),
        );
    };
    let member = member.clone();
    let relay_id = Uuid::new_v4().to_string();
    let relay_dir = context
        .data_dir
        .join("tailcat-relays")
        .join(&relay_id);
    let address = member.address.clone();
    let adapter_override = context.adapter.clone();
    let client = match tokio::task::spawn_blocking(move || {
        let adapter = dial_adapter(adapter_override)?;
        TailcatClient::start_with_adapter(adapter, &relay_dir, &address, None)
    })
    .await
    {
        Ok(Ok(client)) => client,
        Ok(Err(error)) => {
            // The dial error never quotes the address (tailcat.rs); the reply
            // names the label only.
            tracing::warn!(member = %member.label, %error, "room relay dial failed");
            return json(
                StatusCode::BAD_GATEWAY,
                &serde_json::json!({
                    "error": format!("could not dial the room member \"{}\"", member.label)
                }),
            );
        }
        Err(_) => return reply(StatusCode::INTERNAL_SERVER_ERROR, "room relay dial failed"),
    };
    let downstream = client.url().trim_end_matches('/').to_string();
    let present_url = format!("{downstream}/room/present");
    let http = match reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(std::time::Duration::from_secs(15))
        .build()
    {
        Ok(client) => client,
        Err(_) => return reply(StatusCode::INTERNAL_SERVER_ERROR, "http client failed"),
    };
    // The room secret rides this request in transit only: never persisted,
    // never logged. The member verifies it against its own stored verifier.
    let response = match http
        .post(&present_url)
        .json(&serde_json::json!({ "secret": secret, "label": label }))
        .send()
        .await
    {
        Ok(response) => response,
        Err(_) => {
            tracing::warn!(
                member = %member.label,
                "room presentation could not reach the member through Tailcat"
            );
            return json(
                StatusCode::BAD_GATEWAY,
                &serde_json::json!({
                    "error": format!(
                        "could not reach the room member \"{}\" through Tailcat",
                        member.label
                    )
                }),
            );
        }
    };
    let status = response.status();
    if !status.is_success() {
        // Shape-only log: which member, which status — never a body that a
        // future member version might fill with something else.
        tracing::warn!(
            member = %member.label,
            status = status.as_u16(),
            "room presentation refused by the member"
        );
        let (code, message) = match status.as_u16() {
            401 => (
                StatusCode::UNAUTHORIZED,
                format!("the room member \"{}\" refused the presentation", member.label),
            ),
            429 => (
                StatusCode::TOO_MANY_REQUESTS,
                format!(
                    "the room member \"{}\" is throttling presentations; try again later",
                    member.label
                ),
            ),
            _ => (
                StatusCode::BAD_GATEWAY,
                format!(
                    "the room member \"{}\" could not complete the presentation",
                    member.label
                ),
            ),
        };
        return json(code, &serde_json::json!({ "error": message }));
    }
    let mut grant: serde_json::Value = match response.json().await {
        Ok(grant) => grant,
        Err(_) => return reply(StatusCode::BAD_GATEWAY, "invalid presentation response"),
    };
    // The secret stops existing here: nothing below reads it.
    drop(secret);
    let public_base = format!("{public_proto}://{public_host}/tailcat-relay/{relay_id}");
    let Some(object) = grant.as_object_mut() else {
        return reply(StatusCode::BAD_GATEWAY, "invalid presentation response");
    };
    object.insert("baseUrl".into(), public_base.into());
    context
        .tailcat_relays
        .insert(relay_id.clone(), &downstream, client);
    json(StatusCode::OK, &grant)
}

pub fn serve_relay_websocket(
    relay_id: &str,
    mut request: Request<Incoming>,
    relays: Arc<TailcatRelayRegistry>,
    cancel: tokio_util::sync::CancellationToken,
) -> Option<Reply> {
    let key = request
        .headers()
        .get("sec-websocket-key")
        .and_then(|value| value.to_str().ok())?;
    let downstream = relays.downstream_ws(relay_id)?;
    let accept = derive_accept_key(key.as_bytes());
    let upgraded = hyper::upgrade::on(&mut request);
    tokio::spawn(async move {
        let Ok(browser_io) = upgraded.await else {
            return;
        };
        let Ok((mut downstream, _)) = connect_async(&downstream).await else {
            return;
        };
        let (browser_io, _) = ProgressIo::new(hyper_util::rt::TokioIo::new(browser_io));
        let mut browser =
            tokio_tungstenite::WebSocketStream::from_raw_socket(browser_io, Role::Server, None)
                .await;
        let (mut browser_tx, mut browser_rx) = browser.split();
        let (mut downstream_tx, mut downstream_rx) = downstream.split();
        let browser_to_downstream = async {
            while let Some(message) = browser_rx.next().await {
                match message {
                    Ok(Message::Text(text)) => {
                        if downstream_tx.send(Message::Text(text)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Binary(bytes)) => {
                        if downstream_tx.send(Message::Binary(bytes)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Ping(payload)) => {
                        if downstream_tx.send(Message::Ping(payload)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Pong(payload)) => {
                        if downstream_tx.send(Message::Pong(payload)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Close(_)) | Err(_) => break,
                    Ok(Message::Frame(_)) => {}
                }
            }
        };
        let downstream_to_browser = async {
            while let Some(message) = downstream_rx.next().await {
                match message {
                    Ok(Message::Text(text)) => {
                        if browser_tx.send(Message::Text(text)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Binary(bytes)) => {
                        if browser_tx.send(Message::Binary(bytes)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Ping(payload)) => {
                        if browser_tx.send(Message::Ping(payload)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Pong(payload)) => {
                        if browser_tx.send(Message::Pong(payload)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Message::Close(_)) | Err(_) => break,
                    Ok(Message::Frame(_)) => {}
                }
            }
        };
        tokio::select! {
            _ = cancel.cancelled() => {},
            _ = browser_to_downstream => {},
            _ = downstream_to_browser => {},
        }
        let _ = browser_tx
            .send(Message::Close(Some(CloseFrame {
                code: CloseCode::Normal,
                reason: "relay closed".into(),
            })))
            .await;
    });
    Some(
        Response::builder()
            .status(StatusCode::SWITCHING_PROTOCOLS)
            .header("upgrade", "websocket")
            .header("connection", "Upgrade")
            .header("sec-websocket-accept", accept)
            .body(Full::new(Bytes::new()))
            .unwrap(),
    )
}

fn reply(status: StatusCode, message: &str) -> Reply {
    Response::builder()
        .status(status)
        .header("content-type", "text/plain; charset=utf-8")
        .header("cache-control", "no-store")
        .body(Full::new(Bytes::from(message.to_owned())))
        .unwrap()
}

fn json(status: StatusCode, value: &serde_json::Value) -> Reply {
    Response::builder()
        .status(status)
        .header("content-type", "application/json")
        .header("cache-control", "no-store")
        .body(Full::new(Bytes::from(value.to_string())))
        .unwrap()
}

/// The room-redeem resolution unit surface: the invite is the whole book, the
/// lookup is by label, and nothing it produces ever exposes an address.
#[cfg(test)]
mod tests {
    use super::resolve_target;
    use roboco_proto::room::{RoomInvite, RoomMember};

    const SECRET: &str = "room_relay_unit_secret_0123456789abcdef";
    const STALE: &str = "tcSTALEmemberaddress000000000000000";
    const FRESH: &str = "tcFRESHmemberaddress0000000000000";

    fn member(label: &str, address: &str) -> RoomMember {
        RoomMember {
            label: label.into(),
            address: address.into(),
        }
    }

    #[test]
    fn target_resolution_picks_the_first_entry_with_that_label() {
        // Books only ever union: a repeated label carries a stale and a
        // fresh address, and the first entry is the one dialed (the caller
        // holds the book and controls the order it pastes).
        let members = vec![
            member("desktop", STALE),
            member("server", "tcSERVER1"),
            member("desktop", FRESH),
        ];
        assert_eq!(resolve_target(&members, "desktop").unwrap().address, STALE);
        assert_eq!(resolve_target(&members, "server").unwrap().address, "tcSERVER1");
        assert!(resolve_target(&members, "laptop").is_none());
        // Matching is exact: no prefix, case, or whitespace tolerance.
        assert!(resolve_target(&members, "Desktop").is_none());
        assert!(resolve_target(&members, " desktop").is_none());
        assert!(resolve_target(&members, "desk").is_none());
        assert!(resolve_target(&[], "desktop").is_none());
    }

    #[test]
    fn the_invite_decodes_into_the_relay_book() {
        // The origin engine's only input is the invite: decode plus this
        // lookup is the whole membership consultation, and it involves no
        // store, no verifier, and no disk.
        let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", FRESH)])
            .unwrap();
        let decoded = RoomInvite::decode(&invite.encode().unwrap()).unwrap();
        assert_eq!(resolve_target(&decoded.members, "server").unwrap().address, FRESH);
        assert!(resolve_target(&decoded.members, "desktop").is_none());
        // A tailcat invite is not a room invite (fixed-text refusal, no echo).
        let error = RoomInvite::decode("roboco-tailcat:eyJ2IjoxfQ")
            .err()
            .unwrap()
            .to_string();
        assert_eq!(error, "a room invite starts with roboco-room:");
    }

    #[test]
    fn resolution_never_exposes_member_addresses() {
        // Member addresses are transport secrets: `RoomMember`'s Debug
        // redacts them, and the lookup itself is label-only.
        let members = vec![member("server", FRESH)];
        let debug = format!("{:?}", resolve_target(&members, "server").unwrap());
        assert!(!debug.contains(FRESH), "{debug}");
        assert!(debug.contains("server"), "{debug}");
        assert!(debug.contains("redacted") || debug.contains("…"), "{debug}");
    }
}
