//! Framed JSON-RPC 2.0 client for the bridge's stdio: one writer task so frames
//! never interleave, one reader task that reassembles `bridge.fragment`
//! transfers before routing, replies matched by id, and view notifications
//! routed by view id. The reader never waits on a consumer: view channels are
//! unbounded, and the bridge bounds its own retained suffix with `reset`.

use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use base64::Engine as _;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::{mpsc, oneshot, watch};

use super::protocol::{BridgeError, ErrorKind, ViewEvent, ViewNote};

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

struct Pending {
    reply: oneshot::Sender<Result<Value, BridgeError>>,
    /// An `open_view` call: the reader registers this sink when it routes the
    /// reply, before it reads the view's first event.
    view: Option<mpsc::UnboundedSender<ViewNote>>,
}

#[derive(Default)]
struct Shared {
    pending: Mutex<HashMap<u64, Pending>>,
    views: Mutex<HashMap<String, mpsc::UnboundedSender<ViewNote>>>,
    next_id: AtomicU64,
}

impl Shared {
    fn fail_all(&self, reason: &str) {
        for (_, pending) in lock(&self.pending).drain() {
            let _ = pending.reply.send(Err(BridgeError::disconnected(reason)));
        }
        for (_, view) in lock(&self.views).drain() {
            let _ = view.send(ViewNote::Ended {
                reason: reason.to_owned(),
            });
        }
    }
}

enum Outgoing {
    Frame(Vec<u8>),
    /// Close stdin: the bridge releases every attachment at EOF.
    Close,
}

#[derive(Clone)]
pub struct Client {
    shared: Arc<Shared>,
    outgoing: mpsc::UnboundedSender<Outgoing>,
    /// `Some(reason)` once the connection ended.
    closed: watch::Receiver<Option<String>>,
    max_frame: Arc<std::sync::atomic::AtomicUsize>,
}

/// A reply with no deadline would wedge the caller behind a hung bridge.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(120);

impl Client {
    pub fn start(
        reader: impl AsyncRead + Unpin + Send + 'static,
        writer: impl AsyncWrite + Unpin + Send + 'static,
    ) -> Self {
        let shared = Arc::new(Shared::default());
        let (outgoing, outgoing_rx) = mpsc::unbounded_channel();
        let (closed_tx, closed) = watch::channel(None);
        let closed_tx = Arc::new(closed_tx);
        let max_frame = Arc::new(std::sync::atomic::AtomicUsize::new(super::FRAME_BYTES));
        tokio::spawn(write_loop(
            writer,
            outgoing_rx,
            closed_tx.clone(),
            shared.clone(),
        ));
        tokio::spawn(read_loop(
            reader,
            shared.clone(),
            closed_tx,
            max_frame.clone(),
        ));
        Self {
            shared,
            outgoing,
            closed,
            max_frame,
        }
    }

    pub fn closed(&self) -> watch::Receiver<Option<String>> {
        self.closed.clone()
    }

    pub fn set_max_frame(&self, bytes: usize) {
        self.max_frame.store(bytes, Ordering::Relaxed);
    }

    fn send(
        &self,
        method: &str,
        params: Value,
        view: Option<mpsc::UnboundedSender<ViewNote>>,
    ) -> Result<oneshot::Receiver<Result<Value, BridgeError>>, BridgeError> {
        if let Some(reason) = self.closed.borrow().clone() {
            return Err(BridgeError::disconnected(reason));
        }
        let id = self.shared.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let mut frame = serde_json::to_vec(&json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method,
            "params": params,
        }))
        .map_err(|error| BridgeError::malformed(error.to_string()))?;
        let limit = self.max_frame.load(Ordering::Relaxed);
        if frame.len() + 1 > limit {
            return Err(BridgeError {
                kind: ErrorKind::FrameTooLarge,
                code: 0,
                message: format!(
                    "{method} is {} bytes; the bridge accepts at most {limit} per frame",
                    frame.len()
                ),
                data: Value::Null,
            });
        }
        frame.push(b'\n');
        let (reply, rx) = oneshot::channel();
        lock(&self.shared.pending).insert(id, Pending { reply, view });
        if self.outgoing.send(Outgoing::Frame(frame)).is_err() {
            lock(&self.shared.pending).remove(&id);
            return Err(BridgeError::disconnected("bridge input closed"));
        }
        Ok(rx)
    }

    /// Call `method` and await its reply. A timeout means the outcome is
    /// unknown, not that the call failed: the bridge may still act on it.
    pub async fn call(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, BridgeError> {
        let rx = self.send(method, params, None)?;
        settle(method, rx, timeout).await
    }

    /// `session.open_view`: the sink is registered before the view's first event is read.
    pub async fn open_view(
        &self,
        params: Value,
        timeout: Duration,
    ) -> Result<(String, mpsc::UnboundedReceiver<ViewNote>), BridgeError> {
        let (tx, rx) = mpsc::unbounded_channel();
        let reply = self.send("session.open_view", params, Some(tx))?;
        let value = settle("session.open_view", reply, timeout).await?;
        let view = value
            .get("view")
            .and_then(Value::as_str)
            .ok_or_else(|| BridgeError::malformed("open_view reply without a view id"))?
            .to_owned();
        Ok((view, rx))
    }

    pub fn forget_view(&self, view: &str) {
        lock(&self.shared.views).remove(view);
    }

    /// Close the bridge's stdin; it releases every attachment and exits.
    pub fn close_input(&self) {
        let _ = self.outgoing.send(Outgoing::Close);
    }
}

async fn settle(
    method: &str,
    rx: oneshot::Receiver<Result<Value, BridgeError>>,
    timeout: Duration,
) -> Result<Value, BridgeError> {
    match tokio::time::timeout(timeout, rx).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => Err(BridgeError::disconnected("bridge ended before replying")),
        Err(_) => Err(BridgeError {
            kind: ErrorKind::Disconnected,
            code: 0,
            message: format!("{method} got no reply within {}s", timeout.as_secs()),
            data: json!({"timed_out": true}),
        }),
    }
}

async fn write_loop(
    mut writer: impl AsyncWrite + Unpin,
    mut outgoing: mpsc::UnboundedReceiver<Outgoing>,
    closed: Arc<watch::Sender<Option<String>>>,
    shared: Arc<Shared>,
) {
    while let Some(message) = outgoing.recv().await {
        match message {
            Outgoing::Frame(frame) => {
                let written = async {
                    writer.write_all(&frame).await?;
                    writer.flush().await
                }
                .await;
                if let Err(error) = written {
                    let reason = format!("bridge input failed: {error}");
                    closed.send_replace(Some(reason.clone()));
                    shared.fail_all(&reason);
                    return;
                }
            }
            Outgoing::Close => break,
        }
    }
    let _ = writer.shutdown().await;
}

#[derive(Default)]
struct Transfer {
    count: usize,
    total: usize,
    parts: BTreeMap<usize, Vec<u8>>,
}

#[derive(Default)]
struct Reassembler {
    transfers: HashMap<String, Transfer>,
}

impl Reassembler {
    /// The complete message a fragment finishes, or `None` while incomplete.
    fn feed(&mut self, params: &Value) -> Result<Option<Vec<u8>>, String> {
        let transfer = params
            .get("transfer")
            .and_then(Value::as_str)
            .ok_or("fragment without transfer")?
            .to_owned();
        let index = params
            .get("index")
            .and_then(Value::as_u64)
            .ok_or("fragment without index")? as usize;
        let count = params
            .get("count")
            .and_then(Value::as_u64)
            .ok_or("fragment without count")? as usize;
        let total = params
            .get("total_bytes")
            .and_then(Value::as_u64)
            .ok_or("fragment without total_bytes")? as usize;
        let data = params
            .get("data")
            .and_then(Value::as_str)
            .ok_or("fragment without data")?;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(data)
            .map_err(|error| format!("fragment data is not base64: {error}"))?;
        if count == 0 || index >= count {
            return Err(format!("fragment {index} of {count}"));
        }
        let entry = self
            .transfers
            .entry(transfer.clone())
            .or_insert_with(|| Transfer {
                count,
                total,
                parts: BTreeMap::new(),
            });
        if entry.count != count || entry.total != total {
            return Err(format!("transfer {transfer} changed its shape"));
        }
        if entry.parts.insert(index, bytes).is_some() {
            return Err(format!("transfer {transfer} repeated fragment {index}"));
        }
        if entry.parts.len() < entry.count {
            return Ok(None);
        }
        let entry = self.transfers.remove(&transfer).expect("present");
        let message: Vec<u8> = entry.parts.into_values().flatten().collect();
        if message.len() != entry.total {
            return Err(format!(
                "transfer {transfer} is {} bytes, announced {}",
                message.len(),
                entry.total
            ));
        }
        Ok(Some(message))
    }
}

async fn read_loop(
    reader: impl AsyncRead + Unpin,
    shared: Arc<Shared>,
    closed: Arc<watch::Sender<Option<String>>>,
    max_frame: Arc<std::sync::atomic::AtomicUsize>,
) {
    let mut reader = BufReader::with_capacity(64 << 10, reader);
    let mut reassembler = Reassembler::default();
    let mut line = Vec::new();
    let reason = loop {
        line.clear();
        let limit = max_frame.load(Ordering::Relaxed) as u64 + 2;
        match (&mut reader).take(limit).read_until(b'\n', &mut line).await {
            Ok(0) => break "bridge output ended".to_owned(),
            Ok(_) if line.last() != Some(&b'\n') && line.len() as u64 >= limit => {
                break format!("bridge sent a frame over {limit} bytes");
            }
            Ok(_) => {}
            Err(error) => break format!("bridge output failed: {error}"),
        }
        let body = line.strip_suffix(b"\n").unwrap_or(&line);
        let body = body.strip_suffix(b"\r").unwrap_or(body);
        if body.iter().all(u8::is_ascii_whitespace) {
            continue;
        }
        let frame: Value = match serde_json::from_slice(body) {
            Ok(frame) => frame,
            Err(error) => break format!("bridge sent invalid JSON: {error}"),
        };
        if frame.get("method").and_then(Value::as_str) == Some("bridge.fragment") {
            let params = frame.get("params").cloned().unwrap_or(Value::Null);
            match reassembler.feed(&params) {
                Ok(Some(message)) => match serde_json::from_slice::<Value>(&message) {
                    Ok(frame) => route(&shared, frame),
                    Err(error) => {
                        break format!("bridge sent an invalid fragmented message: {error}");
                    }
                },
                Ok(None) => {}
                Err(error) => break format!("bridge sent a broken fragment: {error}"),
            }
            continue;
        }
        route(&shared, frame);
    };
    tracing::debug!(target: "roboco_harness::mimir", %reason, "bridge connection ended");
    closed.send_replace(Some(reason.clone()));
    shared.fail_all(&reason);
}

fn route(shared: &Shared, frame: Value) {
    if let Some(method) = frame.get("method").and_then(Value::as_str) {
        if frame.get("id").is_some() {
            // The bridge never sends requests to its client.
            return;
        }
        let params = frame.get("params").cloned().unwrap_or(Value::Null);
        let Some(view) = params
            .get("view")
            .and_then(Value::as_str)
            .map(str::to_owned)
        else {
            return;
        };
        match method {
            "view.event" => {
                let note = match serde_json::from_value::<ViewEvent>(params.clone()) {
                    Ok(event) => ViewNote::Event(Box::new(event)),
                    Err(error) => ViewNote::EventError {
                        position: params.get("position").and_then(Value::as_u64),
                        message: format!("unreadable view event: {error}"),
                    },
                };
                if let Some(sink) = lock(&shared.views).get(&view) {
                    let _ = sink.send(note);
                }
            }
            "view.event_error" => {
                if let Some(sink) = lock(&shared.views).get(&view) {
                    let _ = sink.send(ViewNote::EventError {
                        position: params.get("position").and_then(Value::as_u64),
                        message: params
                            .get("message")
                            .and_then(Value::as_str)
                            .unwrap_or("event could not be serialized")
                            .to_owned(),
                    });
                }
            }
            "view.ended" => {
                if let Some(sink) = lock(&shared.views).remove(&view) {
                    let _ = sink.send(ViewNote::Ended {
                        reason: params
                            .get("reason")
                            .and_then(Value::as_str)
                            .unwrap_or("view ended")
                            .to_owned(),
                    });
                }
            }
            _ => {}
        }
        return;
    }
    let Some(id) = frame.get("id").and_then(Value::as_u64) else {
        // Replies to frames the bridge could not attribute (`id: null`).
        tracing::warn!(target: "roboco_harness::mimir", %frame, "unattributed bridge reply");
        return;
    };
    let Some(pending) = lock(&shared.pending).remove(&id) else {
        return;
    };
    let result = match (frame.get("result"), frame.get("error")) {
        (Some(result), _) => Ok(result.clone()),
        (None, Some(error)) => Err(BridgeError::from_wire(error)),
        (None, None) => Err(BridgeError::malformed("reply without result or error")),
    };
    if let (Ok(result), Some(sink)) = (&result, pending.view)
        && let Some(view) = result.get("view").and_then(Value::as_str)
    {
        lock(&shared.views).insert(view.to_owned(), sink);
    }
    let _ = pending.reply.send(result);
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use tokio::io::{AsyncBufReadExt, DuplexStream};

    /// The scripted bridge end of an in-memory pipe.
    pub(crate) struct Peer {
        pub lines: tokio::io::Lines<BufReader<tokio::io::ReadHalf<DuplexStream>>>,
        pub out: tokio::io::WriteHalf<DuplexStream>,
    }

    impl Peer {
        pub async fn request(&mut self) -> Value {
            let line = self.lines.next_line().await.unwrap().expect("a request");
            serde_json::from_str(&line).unwrap()
        }

        pub async fn send(&mut self, frame: Value) {
            let mut bytes = serde_json::to_vec(&frame).unwrap();
            bytes.push(b'\n');
            self.out.write_all(&bytes).await.unwrap();
        }

        pub async fn send_raw(&mut self, bytes: &[u8]) {
            self.out.write_all(bytes).await.unwrap();
        }
    }

    pub(crate) fn pair() -> (Client, Peer) {
        let (ours, theirs) = tokio::io::duplex(1 << 20);
        let (read, write) = tokio::io::split(ours);
        let (their_read, their_write) = tokio::io::split(theirs);
        (
            Client::start(read, write),
            Peer {
                lines: BufReader::new(their_read).lines(),
                out: their_write,
            },
        )
    }

    pub(crate) fn fragments(message: &Value, transfer: &str, size: usize) -> Vec<Value> {
        let bytes = serde_json::to_vec(message).unwrap();
        let chunks: Vec<_> = bytes.chunks(size).collect();
        chunks
            .iter()
            .enumerate()
            .map(|(index, chunk)| {
                json!({"jsonrpc": "2.0", "method": "bridge.fragment", "params": {
                    "transfer": transfer, "index": index, "count": chunks.len(),
                    "total_bytes": bytes.len(),
                    "data": base64::engine::general_purpose::STANDARD.encode(chunk)}})
            })
            .collect()
    }

    #[tokio::test]
    async fn replies_match_ids_out_of_order_and_errors_keep_their_kind() {
        let (client, mut peer) = pair();
        let a = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .call("session.state", json!({"session": "a"}), DEFAULT_TIMEOUT)
                    .await
            }
        });
        let first = peer.request().await;
        let b = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .call("session.open", json!({"session": "b"}), DEFAULT_TIMEOUT)
                    .await
            }
        });
        let second = peer.request().await;
        assert_eq!(second["method"], "session.open");
        peer.send(json!({"jsonrpc": "2.0", "id": second["id"], "error": {
            "code": -32004, "message": "another process owns it", "data": {"kind": "busy"}}}))
            .await;
        peer.send(json!({"jsonrpc": "2.0", "id": first["id"], "result": {"ok": 1}}))
            .await;
        assert_eq!(a.await.unwrap().unwrap(), json!({"ok": 1}));
        let error = b.await.unwrap().unwrap_err();
        assert_eq!(error.kind, ErrorKind::Busy);
        assert_eq!(error.message, "another process owns it");
    }

    #[tokio::test]
    async fn fragmented_replies_and_view_events_reassemble_in_order() {
        let (client, mut peer) = pair();
        let open = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .open_view(json!({"session": "s"}), DEFAULT_TIMEOUT)
                    .await
            }
        });
        let request = peer.request().await;
        // The first event is written right behind the reply: the sink must already exist.
        peer.send(json!({"jsonrpc": "2.0", "id": request["id"], "result": {"view": "v1", "session": "s"}})).await;
        let big = "x".repeat(50_000);
        let event = json!({"jsonrpc": "2.0", "method": "view.event", "params": {"view": "v1", "position": 1,
            "item": {"display": big}}});
        for fragment in fragments(&event, "t1", 7_000) {
            peer.send(fragment).await;
        }
        peer.send(json!({"jsonrpc": "2.0", "method": "view.ended", "params": {"view": "v1", "reason": "host_stream_ended"}})).await;
        let (view, mut notes) = open.await.unwrap().unwrap();
        assert_eq!(view, "v1");
        match notes.recv().await.unwrap() {
            ViewNote::Event(event) => {
                assert_eq!(event.position, 1);
                assert!(
                    matches!(event.item, super::super::protocol::ViewItem::Display(ref text) if text.len() == 50_000)
                );
            }
            other => panic!("{other:?}"),
        }
        assert!(
            matches!(notes.recv().await.unwrap(), ViewNote::Ended { ref reason } if reason == "host_stream_ended")
        );
    }

    #[tokio::test]
    async fn eof_fails_pending_calls_and_ends_views_with_the_reason() {
        let (client, mut peer) = pair();
        let open = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .open_view(json!({"session": "s"}), DEFAULT_TIMEOUT)
                    .await
            }
        });
        let request = peer.request().await;
        peer.send(json!({"jsonrpc": "2.0", "id": request["id"], "result": {"view": "v9"}}))
            .await;
        let (_, mut notes) = open.await.unwrap().unwrap();
        let pending = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .call("session.prompt", json!({}), DEFAULT_TIMEOUT)
                    .await
            }
        });
        let _ = peer.request().await;
        drop(peer);
        let error = pending.await.unwrap().unwrap_err();
        assert_eq!(error.kind, ErrorKind::Disconnected);
        assert!(
            matches!(notes.recv().await.unwrap(), ViewNote::Ended { ref reason } if reason == "bridge output ended")
        );
        assert_eq!(
            client.closed().borrow().as_deref(),
            Some("bridge output ended")
        );
        let after = client
            .call("bridge.status", json!({}), DEFAULT_TIMEOUT)
            .await
            .unwrap_err();
        assert_eq!(after.kind, ErrorKind::Disconnected);
    }

    #[tokio::test]
    async fn a_broken_fragment_ends_the_connection_instead_of_guessing() {
        let (client, mut peer) = pair();
        let mut repeated = fragments(&json!({"jsonrpc": "2.0", "id": 1, "result": {}}), "t", 4);
        repeated[1] = repeated[0].clone();
        for fragment in repeated {
            peer.send(fragment).await;
        }
        let mut closed = client.closed();
        closed.wait_for(Option::is_some).await.unwrap();
        assert!(
            closed
                .borrow()
                .as_deref()
                .unwrap()
                .contains("repeated fragment 0")
        );
    }

    #[tokio::test]
    async fn oversized_requests_are_refused_before_they_reach_the_bridge() {
        let (client, _peer) = pair();
        client.set_max_frame(64 << 10);
        let error = client
            .call(
                "session.prompt",
                json!({"text": "x".repeat(70_000)}),
                DEFAULT_TIMEOUT,
            )
            .await
            .unwrap_err();
        assert_eq!(error.kind, ErrorKind::FrameTooLarge);
    }

    #[tokio::test]
    async fn a_reply_lost_to_the_deadline_is_an_unknown_outcome() {
        let (client, mut peer) = pair();
        let call = tokio::spawn({
            let client = client.clone();
            async move {
                client
                    .call("session.prompt", json!({}), Duration::from_millis(50))
                    .await
            }
        });
        let _ = peer.request().await;
        let error = call.await.unwrap().unwrap_err();
        assert_eq!(error.data["timed_out"], true);
        peer.send_raw(b"\r\n\n").await;
        assert!(client.closed().borrow().is_none());
    }
}
