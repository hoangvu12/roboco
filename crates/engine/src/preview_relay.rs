//! The engine's half of the session-relay data plane (Phase C): a
//! session-authenticated WebSocket upgrade on the existing engine listener
//! that carries the preview mux frame protocol between a remote client and
//! the preview service's connector.
//!
//! Route shape: `GET /preview-relay/{chatId}/{serviceId}` — both ids ride the
//! *path*, never a query string (the listener rejects query parameters on
//! every route, so credentials and scope never appear in a URL or log), and
//! both segments must be url-safe ids (`[A-Za-z0-9_-]{1,128}`). The chat id is
//! the authorization scope (ticket 01's attribution); the service id names the
//! one service this relay may resolve, so a socket scoped to one service can
//! never open another, even within the same chat.
//!
//! Authentication is the existing session gate, exactly like the RPC socket:
//! a `Bearer` session credential on the upgrade (native clients) or a
//! first-frame `Auth` envelope (browser-shaped dials). The local (native IPC)
//! bind keeps its credential-free boundary — same policy as the RPC socket.
//! Revocation is inherited the same way: listener teardown cancels the
//! per-connection token this relay rides, which cuts the socket.
//!
//! This is the tailcat-relay shape: an opaque binary pump — no RPC envelope
//! change, no base64, no new RPC method, and the mux framing is untouched
//! ([`roboco_preview::mux`]). Bytes: one mux frame per WebSocket binary
//! message, bounded by the mux's own frame-size check.

use std::sync::Arc;

use bytes::Bytes;
use futures::{SinkExt as _, StreamExt as _};
use http_body_util::Full;
use hyper::{Request, Response, StatusCode, body::Incoming};
use roboco_preview::{PreviewService, mux};
use roboco_rpc::ProgressIo;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_tungstenite::WebSocketStream;
use tokio_tungstenite::tungstenite::{
    Message,
    handshake::derive_accept_key,
    protocol::{CloseFrame, Role, frame::coding::CloseCode},
};
use tokio_util::sync::CancellationToken;

type Reply = Response<Full<Bytes>>;

fn reply(status: StatusCode, body: &'static str) -> Reply {
    Response::builder()
        .status(status)
        .header("cache-control", "no-store")
        .body(Full::new(Bytes::from_static(body.as_bytes())))
        .unwrap()
}

/// The relay request's scope, parsed from the upgrade path.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RelayScope {
    pub chat_id: String,
    pub service_id: String,
}

/// `/preview-relay/{chatId}/{serviceId}` for url-safe ids, `None` otherwise.
pub fn scope_from_path(path: &str) -> Option<RelayScope> {
    let rest = path.strip_prefix("/preview-relay/")?;
    let rest = rest.trim_end_matches('/');
    let (chat_id, service_id) = rest.split_once('/')?;
    if valid_id(chat_id) && valid_id(service_id) {
        Some(RelayScope {
            chat_id: chat_id.to_owned(),
            service_id: service_id.to_owned(),
        })
    } else {
        None
    }
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Upgrade an authenticated `/preview-relay` request and pump mux frames
/// until either side closes. The response is the switching-protocols
/// handshake; the socket itself is handed to
/// [`mux::Mux::start`] with `initiator: false` — the client opens streams,
/// the engine resolves them through the scoped
/// [`PreviewService::relay_connector`] connector.
///
/// The listener authenticated the request's Bearer session before routing
/// it here. `cancel` is the listener's per-connection token — revoking the bind tears
/// the relay down with everything else.
pub fn serve_relay_websocket(
    scope: RelayScope,
    mut request: Request<Incoming>,
    previews: PreviewService,
    cancel: CancellationToken,
) -> Reply {
    // The caller routes only validated WebSocket upgrades here; a request
    // that slipped past that check still answers 400, never a panic.
    let Some(key) = request
        .headers()
        .get("sec-websocket-key")
        .and_then(|value| value.to_str().ok())
    else {
        return reply(StatusCode::BAD_REQUEST, "expected WebSocket upgrade");
    };
    let accept = derive_accept_key(key.as_bytes());
    let upgraded = hyper::upgrade::on(&mut request);
    tokio::spawn(async move {
        let Ok(io) = upgraded.await else {
            return;
        };
        // ProgressIo wrapping like the RPC socket: the socket's byte
        // activity stays observable at the framing layer; the mux itself
        // bounds every buffer and window above it.
        let (io, _progress) = ProgressIo::new(hyper_util::rt::TokioIo::new(io));
        let ws = WebSocketStream::from_raw_socket(io, Role::Server, None).await;
        let transport = Arc::new(WsTransport::new(ws));
        let connector = previews.relay_connector(&scope.chat_id, &scope.service_id);
        // Engine side of the mux: never the initiator — it only answers
        // OPENs, and only through the scoped connector.
        let mux = mux::Mux::start(transport.clone(), connector, false, cancel.child_token());
        mux.closed().await;
        transport.close().await;
    });
    Response::builder()
        .status(StatusCode::SWITCHING_PROTOCOLS)
        .header("upgrade", "websocket")
        .header("connection", "Upgrade")
        .header("sec-websocket-accept", accept)
        .body(Full::new(Bytes::new()))
        .unwrap()
}

/// A [`mux::Transport`] over a WebSocket: one mux frame per binary message.
/// Reads answer protocol pings (liveness), and anything that is not a
/// binary frame — a text frame, a close, EOF — ends the transport so the mux
/// tears its streams down. The write and read halves lock independently, so
/// a slow reader never blocks outbound frames.
pub struct WsTransport<S> {
    write: tokio::sync::Mutex<futures::stream::SplitSink<WebSocketStream<S>, Message>>,
    read: tokio::sync::Mutex<futures::stream::SplitStream<WebSocketStream<S>>>,
}

impl<S> WsTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    pub fn new(ws: WebSocketStream<S>) -> Self {
        let (sink, stream) = ws.split();
        Self {
            write: tokio::sync::Mutex::new(sink),
            read: tokio::sync::Mutex::new(stream),
        }
    }

    /// Close the underlying WebSocket cleanly (after the mux has ended).
    pub async fn close(&self) {
        let mut sink = self.write.lock().await;
        let _ = sink
            .send(Message::Close(Some(CloseFrame {
                code: CloseCode::Normal,
                reason: "relay closed".into(),
            })))
            .await;
    }
}

#[async_trait::async_trait]
impl<S> mux::Transport for WsTransport<S>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    async fn send(&self, bytes: &[u8]) -> anyhow::Result<()> {
        let mut sink = self.write.lock().await;
        sink.send(Message::Binary(bytes.to_vec())).await?;
        Ok(())
    }

    async fn receive(&self) -> anyhow::Result<Vec<u8>> {
        loop {
            let message = {
                let mut stream = self.read.lock().await;
                stream.next().await
            };
            match message {
                Some(Ok(Message::Binary(bytes))) => return Ok(bytes),
                Some(Ok(Message::Ping(payload))) => {
                    let mut sink = self.write.lock().await;
                    sink.send(Message::Pong(payload)).await?;
                }
                Some(Ok(Message::Pong(_))) => {}
                Some(Ok(Message::Text(_))) => {
                    anyhow::bail!("text frame on the preview relay");
                }
                Some(Ok(Message::Close(_))) | None => {
                    anyhow::bail!("preview relay closed");
                }
                Some(Ok(Message::Frame(_))) => {}
                Some(Err(error)) => return Err(anyhow::Error::from(error)),
            }
        }
    }
}
