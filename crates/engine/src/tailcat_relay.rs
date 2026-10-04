//! Server-side Tailcat pairing for browsers: dial a remote engine, redeem the
//! invite, and expose it at `/tailcat-relay/{id}` on this listener so the web
//! client never needs a local `browser-helper` process.

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
    let client = match tokio::task::spawn_blocking(move || {
        TailcatClient::start(&relay_dir, &address, None)
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
