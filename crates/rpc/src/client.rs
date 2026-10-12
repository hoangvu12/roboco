//! Client side: request/stream multiplexing over string frames + the WebSocket dialer.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use tokio::sync::{mpsc, oneshot};

use crate::{ClientFrame, RpcError, ServerFrame};

/// Per-stream queue depth. Bounded: route_frame awaits a full queue, pausing
/// the connection reader — transport backpressure instead of unbounded growth
/// when a consumer stalls behind a fast producer (watch frames every 120ms
/// during streaming used to pile up whole-transcript payloads here).
const STREAM_QUEUE_CAP: usize = 256;

enum Pending {
    Call(oneshot::Sender<Result<serde_json::Value, RpcError>>),
    Stream(mpsc::Sender<serde_json::Value>),
    CheckedStream {
        items: mpsc::Sender<serde_json::Value>,
        ready: Option<oneshot::Sender<Result<(), RpcError>>>,
    },
}

struct Shared {
    pending: Mutex<HashMap<u64, Pending>>,
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<u64, Pending>> {
        self.pending.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// A multiplexing RPC client over any string-frame duplex ([`crate::memory_client`] or
/// [`connect_ws`]). Cheap to clone-by-Arc internally; use one per connection.
pub struct RpcClient {
    out: mpsc::Sender<String>,
    shared: Arc<Shared>,
    next_id: AtomicU64,
    reader: tokio::task::JoinHandle<()>,
}

/// Owned stream receiver whose drop immediately cancels the server task.
pub struct RpcSubscription {
    id: u64,
    items: mpsc::Receiver<serde_json::Value>,
    out: mpsc::Sender<String>,
    shared: Arc<Shared>,
}

impl RpcSubscription {
    pub async fn recv(&mut self) -> Option<serde_json::Value> {
        self.items.recv().await
    }
}

impl Drop for RpcSubscription {
    fn drop(&mut self) {
        if self.shared.lock().remove(&self.id).is_none() {
            return;
        }
        let Ok(frame) = serde_json::to_string(&ClientFrame {
            id: self.id,
            method: None,
            params: serde_json::Value::Null,
            cancel: true,
        }) else {
            return;
        };
        match self.out.try_send(frame) {
            Ok(()) | Err(mpsc::error::TrySendError::Closed(_)) => {}
            Err(mpsc::error::TrySendError::Full(frame)) => {
                let out = self.out.clone();
                if let Ok(runtime) = tokio::runtime::Handle::try_current() {
                    runtime.spawn(async move {
                        let _ = out.send(frame).await;
                    });
                }
            }
        }
    }
}

impl RpcClient {
    /// Wrap an existing duplex: `out` carries client frames, `inbound` server frames.
    pub fn new(out: mpsc::Sender<String>, mut inbound: mpsc::Receiver<String>) -> Self {
        let shared = Arc::new(Shared {
            pending: Mutex::new(HashMap::new()),
        });
        let reader_shared = shared.clone();
        let reader_out = out.clone();
        let reader = tokio::spawn(async move {
            while let Some(payload) = inbound.recv().await {
                for line in payload.lines() {
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }
                    let frame: ServerFrame = match serde_json::from_str(line) {
                        Ok(frame) => frame,
                        Err(err) => {
                            tracing::warn!(error = %err, "rpc: dropping malformed server frame");
                            continue;
                        }
                    };
                    route_frame(&reader_shared, &reader_out, frame).await;
                }
            }
            // Connection closed: fail everything still pending.
            let drained: Vec<Pending> = {
                let mut pending = reader_shared.lock();
                pending.drain().map(|(_, p)| p).collect()
            };
            for entry in drained {
                match entry {
                    Pending::Call(tx) => {
                        let _ = tx.send(Err(RpcError::Closed));
                    }
                    Pending::CheckedStream {
                        ready: Some(ready), ..
                    } => {
                        let _ = ready.send(Err(RpcError::Closed));
                    }
                    Pending::Stream(_) | Pending::CheckedStream { ready: None, .. } => {}
                }
                // Stream item receivers end by sender drop.
            }
        });
        Self {
            out,
            shared,
            next_id: AtomicU64::new(1),
            reader,
        }
    }

    /// Resolves when the transport stops accepting frames.
    pub fn is_closed(&self) -> bool {
        self.out.is_closed()
    }

    pub async fn closed(&self) {
        self.out.closed().await;
    }

    /// Force-close the transport now, even while [`Arc`] clones of this
    /// client are still held: pending RPCs fail as [`RpcError::Closed`] and
    /// every stream subscriber sees its receiver end. The client's pump owns
    /// the socket; aborting the inbound reader drops the receiver the pump
    /// selects on, which tears the socket down from either half. Idempotent.
    ///
    /// The mobile client's transcript lag check uses this to drop a frozen
    /// connection whose `Arc` is still held by an open session handle.
    pub fn close(&self) {
        self.reader.abort();
        // The aborted reader is what would have failed every pending call;
        // do it here instead so callers do not hang behind a dead socket.
        let drained: Vec<Pending> = {
            let mut pending = self.shared.lock();
            pending.drain().map(|(_, p)| p).collect()
        };
        for entry in drained {
            match entry {
                Pending::Call(tx) => {
                    let _ = tx.send(Err(RpcError::Closed));
                }
                Pending::CheckedStream {
                    ready: Some(ready), ..
                } => {
                    let _ = ready.send(Err(RpcError::Closed));
                }
                Pending::Stream(_) | Pending::CheckedStream { ready: None, .. } => {}
            }
            // Stream item receivers end by sender drop.
        }
    }

    /// Unary request.
    pub async fn call(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<serde_json::Value, RpcError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.shared.lock().insert(id, Pending::Call(tx));
        self.send(ClientFrame {
            id,
            method: Some(method.into()),
            params,
            cancel: false,
        })
        .await
        .inspect_err(|_| {
            self.shared.lock().remove(&id);
        })?;
        rx.await.map_err(|_| RpcError::Closed)?
    }

    /// Typed unary request.
    pub async fn call_as<T: serde::de::DeserializeOwned>(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<T, RpcError> {
        let value = self.call(method, params).await?;
        serde_json::from_value(value).map_err(|e| RpcError::BadParams(e.to_string()))
    }

    /// Legacy streaming request. Cancellation on receiver drop is only detected
    /// when another item arrives. Prefer `subscribe_scoped` for owned streams.
    pub async fn subscribe(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<mpsc::Receiver<serde_json::Value>, RpcError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel(STREAM_QUEUE_CAP);
        self.shared.lock().insert(id, Pending::Stream(tx));
        self.send(ClientFrame {
            id,
            method: Some(method.into()),
            params,
            cancel: false,
        })
        .await
        .inspect_err(|_| {
            self.shared.lock().remove(&id);
        })?;
        Ok(rx)
    }

    /// A drop-cancelled stream without waiting for its first item. Unlike a
    /// checked subscription this also works for legitimately silent streams.
    pub async fn subscribe_scoped(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<RpcSubscription, RpcError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel(STREAM_QUEUE_CAP);
        self.shared.lock().insert(id, Pending::Stream(tx));
        // Own cancellation before the send, including cancellation while the
        // outbound channel is backpressured.
        let subscription = RpcSubscription {
            id,
            items: rx,
            out: self.out.clone(),
            shared: self.shared.clone(),
        };
        self.send(ClientFrame {
            id,
            method: Some(method.into()),
            params,
            cancel: false,
        })
        .await?;
        Ok(subscription)
    }

    /// Streaming request with a server acknowledgement before returning.
    ///
    /// Use this for optional/versioned stream methods: an older server's
    /// `unknown method` is returned distinctly instead of looking like an empty stream.
    pub async fn subscribe_checked(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<RpcSubscription, RpcError> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (items_tx, items_rx) = mpsc::channel(STREAM_QUEUE_CAP);
        let (ready_tx, ready_rx) = oneshot::channel();
        self.shared.lock().insert(
            id,
            Pending::CheckedStream {
                items: items_tx,
                ready: Some(ready_tx),
            },
        );
        let subscription = RpcSubscription {
            id,
            items: items_rx,
            out: self.out.clone(),
            shared: self.shared.clone(),
        };
        self.send(ClientFrame {
            id,
            method: Some(method.into()),
            params,
            cancel: false,
        })
        .await
        .inspect_err(|_| {
            self.shared.lock().remove(&id);
        })?;
        ready_rx.await.map_err(|_| RpcError::Closed)??;
        Ok(subscription)
    }

    async fn send(&self, frame: ClientFrame) -> Result<(), RpcError> {
        let json = serde_json::to_string(&frame)
            .map_err(|e| RpcError::Transport(format!("serialize frame: {e}")))?;
        self.out.send(json).await.map_err(|_| RpcError::Closed)
    }
}

impl Drop for RpcClient {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

async fn route_frame(shared: &Arc<Shared>, out: &mpsc::Sender<String>, frame: ServerFrame) {
    let id = frame.id;
    if let Some(err) = frame.err {
        let error = wire_error(err);
        match shared.lock().remove(&id) {
            Some(Pending::Call(tx)) => {
                let _ = tx.send(Err(error));
            }
            Some(Pending::CheckedStream {
                ready: Some(ready), ..
            }) => {
                let _ = ready.send(Err(error));
            }
            Some(Pending::Stream(_)) | Some(Pending::CheckedStream { ready: None, .. }) | None => {
                // Stream errored: the sender drop closes the receiver.
                tracing::debug!(id, %error, "rpc: stream ended with error");
            }
        }
        return;
    }
    if let Some(value) = frame.ok {
        let mut pending = shared.lock();
        if matches!(pending.get(&id), Some(Pending::Call(_))) {
            if let Some(Pending::Call(tx)) = pending.remove(&id) {
                let _ = tx.send(Ok(value));
            }
        } else if let Some(Pending::CheckedStream { ready, .. }) = pending.get_mut(&id)
            && let Some(ready) = ready.take()
        {
            let _ = ready.send(Ok(()));
        }
        return;
    }
    if let Some(item) = frame.item {
        let ready = match shared.lock().get_mut(&id) {
            Some(Pending::CheckedStream { ready, .. }) => ready.take(),
            _ => None,
        };
        if let Some(ready) = ready {
            let _ = ready.send(Ok(()));
        }
        // Clone the sender out of the lock: the bounded send must await
        // (backpressure) without holding `shared`.
        let tx = match shared.lock().get(&id) {
            Some(Pending::Stream(tx)) => Some(tx.clone()),
            Some(Pending::CheckedStream { items, .. }) => Some(items.clone()),
            _ => None,
        };
        let dead = match tx {
            Some(tx) => tx.send(item).await.is_err(),
            None => false,
        };
        if dead {
            // Receiver was dropped — cancel server-side and forget the stream.
            shared.lock().remove(&id);
            if let Ok(json) = serde_json::to_string(&ClientFrame {
                id,
                method: None,
                params: serde_json::Value::Null,
                cancel: true,
            }) {
                let _ = out.send(json).await;
            }
        }
        return;
    }
    if frame.done
        && let Some(Pending::CheckedStream {
            ready: Some(ready), ..
        }) = shared.lock().remove(&id)
    {
        let _ = ready.send(Ok(()));
    }
}

fn wire_error(error: String) -> RpcError {
    error
        .strip_prefix("unknown method: ")
        .map(|method| RpcError::UnknownMethod(method.to_owned()))
        .unwrap_or(RpcError::Failed(error))
}

/// Did the WebSocket upgrade refuse this client's session credential?
///
/// Tungstenite renders a refused upgrade as
/// `RpcError::Transport("HTTP error: 401 Unauthorized")` (403 when the bind
/// policy forbids the address). A paired client maps this to "session
/// revoked / pair again" — unlike a browser it never sees the 4401 close
/// code, because the pump treats Close as end-of-stream.
pub fn is_auth_refused(err: &RpcError) -> bool {
    match err {
        RpcError::Transport(message) => message.contains("401") || message.contains("403"),
        _ => false,
    }
}

/// How long each dial phase (TCP connect, WebSocket upgrade) may take before
/// we give up.
///
/// This is localhost: a real engine answers in milliseconds. Without a bound,
/// *any* other process holding the port accepts the TCP connection and then
/// never completes the WebSocket handshake, and the caller waits forever — a
/// stranger on port 27654 would hang the app at boot rather than degrade it.
const CONNECT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

/// Dial a WebSocket RPC server (`ws://127.0.0.1:{ipc_port}`).
pub async fn connect_ws(url: &str) -> Result<RpcClient, RpcError> {
    connect_ws_with_session(url, None).await
}

/// Dial a paired engine without placing its credential in the URL.
pub async fn connect_ws_authenticated(url: &str, credential: &str) -> Result<RpcClient, RpcError> {
    connect_ws_with_session(url, Some(credential)).await
}

/// Per-message size ceiling for client WebSocket connections. #621 measured
/// a 16.4 MB transcript reset; tungstenite 0.24's 64 MiB default would drop a
/// growing one, so the mobile client raises the ceiling to 256 MiB (the
/// engine's server side stays at defaults: client→engine frames are small).
pub const MAX_MESSAGE_BYTES: usize = 256 << 20;

async fn connect_ws_with_session(
    url: &str,
    credential: Option<&str>,
) -> Result<RpcClient, RpcError> {
    use tokio_tungstenite::tungstenite::{
        client::IntoClientRequest, http::HeaderValue, protocol::WebSocketConfig,
    };
    let mut request = url
        .into_client_request()
        .map_err(|_| RpcError::Transport("invalid engine URL".into()))?;
    if let Some(credential) = credential {
        let mut header = HeaderValue::from_str(&format!("Bearer {credential}"))
            .map_err(|_| RpcError::Transport("invalid session credential".into()))?;
        header.set_sensitive(true);
        request.headers_mut().insert("authorization", header);
    }
    // The dial is manual so the session rides the bounded pump with byte
    // progress tracking. `wss://` engines (TLS-terminating tunnels such as
    // cloudflared) ride the same pump: client_async_tls wraps our progress
    // stream with rustls when the URI scheme is wss and passes it through
    // untouched for plain ws.
    let uri = request.uri().clone();
    let secure = match uri.scheme_str() {
        Some("ws") => false,
        Some("wss") => true,
        _ => {
            return Err(RpcError::Transport(format!(
                "unsupported engine URL scheme (expected ws or wss): {url}"
            )));
        }
    };
    let host = uri
        .host()
        .ok_or_else(|| RpcError::Transport("engine URL has no host".into()))?;
    let port = uri.port_u16().unwrap_or(if secure { 443 } else { 80 });
    let io = tokio::time::timeout(
        CONNECT_TIMEOUT,
        tokio::net::TcpStream::connect((host, port)),
    )
    .await
    .map_err(|_| RpcError::Transport(format!("timed out dialing {url}")))?
    .map_err(|e| RpcError::Transport(e.to_string()))?;
    let (io, progress) = crate::pump::ProgressIo::new(io);
    let (ws, _) = tokio::time::timeout(
        CONNECT_TIMEOUT,
        tokio_tungstenite::client_async_tls_with_config(
            request,
            io,
            Some(WebSocketConfig {
                max_message_size: Some(MAX_MESSAGE_BYTES),
                max_frame_size: Some(MAX_MESSAGE_BYTES),
                ..Default::default()
            }),
            None,
        ),
    )
    .await
    .map_err(|_| RpcError::Transport(format!("timed out dialing {url}")))?
    .map_err(|e| RpcError::Transport(e.to_string()))?;
    let (out_tx, out_rx) = mpsc::channel::<String>(256);
    let (in_tx, in_rx) = mpsc::channel::<String>(256);
    tokio::spawn(crate::pump::pump(
        crate::pump::Connection {
            socket: ws,
            progress,
        },
        out_rx,
        in_tx,
    ));
    Ok(RpcClient::new(out_tx, in_rx))
}

#[cfg(test)]
mod wss_dial_tests {
    use super::*;

    #[tokio::test]
    async fn rejects_non_websocket_engine_schemes() {
        let err = match connect_ws("http://127.0.0.1:27655").await {
            Err(err) => err,
            Ok(_) => panic!("http engine URLs must be rejected at the scheme gate"),
        };
        assert!(
            err.to_string().contains("unsupported engine URL scheme"),
            "unexpected error: {err}"
        );
    }

    #[tokio::test]
    async fn wss_passes_the_scheme_gate_and_dials_port_443() {
        // A wss URL must clear the scheme check and reach the TCP dial
        // (127.0.0.1:1 refuses instantly) instead of being rejected as an
        // unsupported scheme — proving TLS engine URLs are dialable.
        let err = match connect_ws("wss://127.0.0.1:1/").await {
            Err(err) => err,
            Ok(_) => panic!("nothing listens on 127.0.0.1:1; the dial must fail"),
        };
        assert!(
            !err.to_string().contains("unsupported engine URL scheme"),
            "wss was rejected at the scheme gate: {err}"
        );
    }
}

#[cfg(test)]
mod auth_and_close_tests {
    use super::*;

    #[test]
    fn auth_refusal_is_recognized_from_tungstenites_exact_strings() {
        // The exact string tungstenite renders for a refused upgrade.
        assert!(is_auth_refused(&RpcError::Transport(
            "HTTP error: 401 Unauthorized".into()
        )));
        assert!(is_auth_refused(&RpcError::Transport(
            "HTTP error: 403 Forbidden".into()
        )));
        // Other transports, and every non-transport error, are not refusals:
        // a store hiccup is 503 and must keep the reconnect loop alive.
        assert!(!is_auth_refused(&RpcError::Transport(
            "HTTP error: 503 Service Unavailable".into()
        )));
        assert!(!is_auth_refused(&RpcError::Transport(
            "timed out dialing ws://127.0.0.1:1/".into()
        )));
        assert!(!is_auth_refused(&RpcError::Failed("401-ish".into())));
        assert!(!is_auth_refused(&RpcError::Closed));
    }

    #[tokio::test]
    async fn close_fails_pending_calls_and_ends_streams_while_clones_live() {
        // Closing a client must tear down its transport even though a clone
        // of its `Arc` is still held — the shape the mobile transcript lag
        // check depends on.
        struct Never;
        #[async_trait::async_trait]
        impl crate::RpcService for Never {
            async fn handle(
                &self,
                _method: &str,
                _params: serde_json::Value,
            ) -> Result<crate::RpcReply, RpcError> {
                // Hang: the call stays pending until the transport dies.
                std::future::pending().await
            }
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}", listener.local_addr().unwrap());
        let service: Arc<dyn crate::RpcService> = Arc::new(Never);
        let server = tokio::spawn(crate::serve_ws_listener(listener, service));
        let client = Arc::new(connect_ws(&url).await.unwrap());
        let held = client.clone();
        let pending = tokio::spawn({
            let client = client.clone();
            async move { client.call("Whatever", serde_json::json!({})).await }
        });
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        client.close();
        let deadline = std::time::Duration::from_secs(5);
        assert!(matches!(
            tokio::time::timeout(deadline, pending)
                .await
                .unwrap()
                .unwrap(),
            Err(RpcError::Closed)
        ));
        tokio::time::timeout(deadline, client.closed())
            .await
            .unwrap();
        assert!(
            held.is_closed(),
            "the held clone must see the transport closed"
        );
        server.abort();
    }
}
