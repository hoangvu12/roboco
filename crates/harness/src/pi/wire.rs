//! pi's first-party RPC wire: strict JSONL over the child's stdio.
//!
//! pi (`--mode rpc`) speaks a line-delimited JSON protocol — NOT JSON-RPC 2.0:
//! commands are `{"id"?, "type": <command>, …}` objects on stdin; stdout
//! interleaves id-echoing `{"type":"response", "command", "success",
//! "data"|"error"}` records with `{"type": <event>, …}` objects streamed as
//! they occur. No handshake, no protocol version (references:
//! `pi.dev/docs/latest/rpc` / `pi-mono` `docs/rpc.md`, validated against
//! `@earendil-works/pi-coding-agent` 0.87.0's `dist/modes/rpc/`).
//!
//! Framing follows pi's own `jsonl.js` contract, plus the noise tolerance the
//! ACP side learned in `371d79b1`:
//! - records split on `\n` ONLY (U+2028/U+2029 are valid inside JSON strings;
//!   never split on them), one trailing `\r` is stripped (CRLF tolerance);
//! - a final unterminated line is processed at stream end;
//! - non-JSON or non-object lines are skipped as noise (pi redirects stray
//!   `console.log` to stderr in non-interactive modes, but stay defensive);
//! - responses resolve the pending map by their echoed string id; a response
//!   without an id (pi's stdin-parse errors) is dropped;
//! - stdout EOF fails every pending request and delivers one final
//!   [`PiIncoming::Eof`].

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot};

use crate::HarnessError;
use crate::process::{ChildStdin, ChildStdout};

/// A non-response stdout record (an event object), in stdout order.
#[derive(Debug)]
pub(crate) enum PiIncoming {
    Event(Value),
    /// stdout EOF or read error: pi exited. All pending requests fail.
    Eof,
}

type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<Result<Value, String>>>>>;

#[derive(Clone)]
pub(crate) struct PiClient {
    next_id: Arc<AtomicU64>,
    pending: Pending,
    writer: mpsc::UnboundedSender<String>,
    closed: Arc<AtomicBool>,
}

impl PiClient {
    /// Spawn the writer + reader tasks over the child's stdio; returns the
    /// client and the event channel.
    pub fn new(stdin: ChildStdin, stdout: ChildStdout) -> (Self, mpsc::Receiver<PiIncoming>) {
        let (writer_tx, writer_rx) = mpsc::unbounded_channel::<String>();
        tokio::spawn(write_loop(stdin, writer_rx));
        let pending: Pending = Arc::default();
        let (incoming_tx, incoming_rx) = mpsc::channel(256);
        let closed = Arc::new(AtomicBool::new(false));
        tokio::spawn(read_loop(stdout, Arc::clone(&pending), incoming_tx, closed.clone()));
        (
            Self {
                next_id: Arc::new(AtomicU64::new(0)),
                pending,
                writer: writer_tx,
                closed,
            },
            incoming_rx,
        )
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }

    /// Send a command and await its id-correlated response. `params` carries
    /// the command's own fields (without `id`/`type`); the response's `data`
    /// is returned, or the response's `error` string as a protocol error.
    pub async fn request(&self, command: &str, params: Value) -> Result<Value, HarnessError> {
        let id = format!("roboco-{}", self.next_id.fetch_add(1, Ordering::Relaxed) + 1);
        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().expect("pending lock");
            // Check under the same lock as EOF cleanup: a request racing the
            // reader exit must either be rejected here or cleared by it.
            if self.is_closed() {
                return Err(HarnessError::Protocol(format!(
                    "{command}: pi exited before responding"
                )));
            }
            pending.insert(id.clone(), tx);
        }
        if self.send_command(&id, command, params).is_err() {
            self.pending.lock().expect("pending lock").remove(&id);
            return Err(HarnessError::Protocol(format!(
                "{command}: pi stdin closed"
            )));
        }
        match rx.await {
            Ok(Ok(data)) => Ok(data),
            Ok(Err(message)) => Err(HarnessError::Protocol(format!(
                "{command}: {message}"
            ))),
            // Sender dropped: the reader hit EOF and failed all pending.
            Err(_) => Err(HarnessError::Protocol(format!(
                "{command}: pi exited before responding"
            ))),
        }
    }

    /// Write a raw object with no request id and no expected response — the
    /// extension UI answers (`extension_ui_response`), which pi matches by
    /// the dialog id it minted and silently ignores when unmatched.
    pub fn send_value(&self, value: &Value) {
        let _ = self.writer.send(value.to_string());
    }

    fn send_command(&self, id: &str, command: &str, params: Value) -> Result<(), ()> {
        let Value::Object(mut map) = params else {
            return Err(());
        };
        map.insert("id".into(), Value::String(id.to_owned()));
        map.insert("type".into(), Value::String(command.to_owned()));
        self.writer.send(Value::Object(map).to_string()).map_err(|_| ())
    }
}

/// Owns the child's stdin; a write failure (EPIPE after the child died) is
/// tolerated and logged.
async fn write_loop(mut stdin: ChildStdin, mut rx: mpsc::UnboundedReceiver<String>) {
    while let Some(line) = rx.recv().await {
        let write = async {
            stdin.write_all(line.as_bytes()).await?;
            stdin.write_all(b"\n").await?;
            stdin.flush().await
        };
        if let Err(e) = write.await {
            tracing::debug!(target: "roboco_harness::pi", "stdin write failed (tolerated): {e}");
            return;
        }
    }
}

/// Split accumulated stdout bytes into complete `\n`-terminated lines,
/// returning them in order. One trailing `\r` is stripped per line.
pub(crate) fn frame_lines(buffer: &mut Vec<u8>) -> Vec<Vec<u8>> {
    let mut lines = Vec::new();
    while let Some(pos) = buffer.iter().position(|&b| b == b'\n') {
        let mut line: Vec<u8> = buffer.drain(..=pos).collect();
        // The terminator (and one CR before it — CRLF tolerance) never
        // belongs to the record.
        if line.last() == Some(&b'\n') {
            line.pop();
        }
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        lines.push(line);
    }
    lines
}

/// Parse one stdout line and route it: responses resolve the pending map,
/// everything else is forwarded as an event in stdout order. Noise (non-JSON,
/// non-object, or empty lines) is skipped. Returns false when the event
/// channel is gone (consumer dropped) — the reader should stop.
async fn route_line(line: &[u8], pending: &Pending, tx: &mpsc::Sender<PiIncoming>) -> bool {
    let Ok(text) = std::str::from_utf8(line) else {
        tracing::debug!(target: "roboco_harness::pi", "non-UTF-8 stdout line (skipped)");
        return true;
    };
    if text.trim().is_empty() {
        return true;
    }
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        tracing::debug!(target: "roboco_harness::pi", "non-JSON stdout line (skipped)");
        return true;
    };
    if !value.is_object() {
        return true;
    }
    if value.get("type").and_then(Value::as_str) != Some("response") {
        return tx.send(PiIncoming::Event(value)).await.is_ok();
    }
    // A response without an id cannot name a pending request (pi emits those
    // for stdin parse failures); drop it rather than stranding a caller.
    let Some(id) = value.get("id").and_then(Value::as_str) else {
        return true;
    };
    let Some(sender) = pending.lock().expect("pending lock").remove(id) else {
        return true;
    };
    let outcome = match value.get("success").and_then(Value::as_bool) {
        Some(true) => Ok(value.get("data").cloned().unwrap_or(Value::Null)),
        _ => Err(value
            .get("error")
            .and_then(Value::as_str)
            .unwrap_or("unknown error")
            .to_owned()),
    };
    let _ = sender.send(outcome);
    true
}

/// Parse stdout: responses resolve the pending map, events are forwarded in
/// order. A read error ends the loop like EOF; a final unterminated line is
/// still processed; on exit all pending requests fail and one final
/// [`PiIncoming::Eof`] is delivered.
pub(crate) async fn read_loop<R: AsyncRead + Unpin>(
    stdout: R,
    pending: Pending,
    tx: mpsc::Sender<PiIncoming>,
    closed: Arc<AtomicBool>,
) {
    let mut reader = stdout;
    let mut buffer: Vec<u8> = Vec::with_capacity(16 * 1024);
    let mut chunk = [0u8; 16 * 1024];
    loop {
        match reader.read(&mut chunk).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                buffer.extend_from_slice(&chunk[..n]);
                for line in frame_lines(&mut buffer) {
                    if !route_line(&line, &pending, &tx).await {
                        return;
                    }
                }
            }
        }
    }
    // pi's jsonl.js emits a final unterminated line at stream end.
    if !buffer.is_empty() {
        let mut line = std::mem::take(&mut buffer);
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        let _ = route_line(&line, &pending, &tx).await;
    }
    closed.store(true, Ordering::Release);
    pending.lock().expect("pending lock").clear();
    let _ = tx.send(PiIncoming::Eof).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pending() -> Pending {
        Arc::default()
    }

    #[test]
    fn framing_splits_on_lf_only_and_strips_one_cr() {
        // U+2028 inside the payload survives: it is valid inside a JSON
        // string and must never terminate a record (pi's jsonl.js rule).
        let payload = format!("{{\"a\":\"x\\u2028y\"}}");
        let mut buffer = Vec::new();
        let feed = |chunk: &[u8], buffer: &mut Vec<u8>| {
            buffer.extend_from_slice(chunk);
            frame_lines(buffer)
        };
        // Split mid-UTF-8 and mid-line: accumulation must reassemble.
        let bytes = format!("{}\r\n{}\n", payload, payload).into_bytes();
        let split = bytes.split_at(7);
        assert!(feed(split.0, &mut buffer).is_empty());
        let lines = feed(split.1, &mut buffer);
        assert_eq!(lines.len(), 2);
        assert_eq!(String::from_utf8(lines[0].clone()).unwrap(), payload);
        assert_eq!(String::from_utf8(lines[1].clone()).unwrap(), payload);
        // One \r stripped, a second is content.
        let mut buffer = b"a\r\r\n".to_vec();
        assert_eq!(frame_lines(&mut buffer), vec![b"a\r".to_vec()]);
    }

    #[test]
    fn framing_holds_unterminated_and_huge_lines() {
        let mut buffer = b"{\"partial".to_vec();
        assert!(frame_lines(&mut buffer).is_empty());
        buffer.extend_from_slice(b"\":1}\n");
        assert_eq!(frame_lines(&mut buffer), vec![b"{\"partial\":1}".to_vec()]);
        // A >1MB single line survives chunked accumulation.
        let big = format!("{{\"text\":\"{}\"}}", "x".repeat(1024 * 1024 + 17));
        let mut buffer = Vec::new();
        for piece in big.as_bytes().chunks(64 * 1024) {
            buffer.extend_from_slice(piece);
            assert!(frame_lines(&mut buffer).is_empty());
        }
        buffer.push(b'\n');
        assert_eq!(frame_lines(&mut buffer), vec![big.into_bytes()]);
    }

    #[tokio::test]
    async fn responses_resolve_by_id_noise_is_skipped_events_forward_in_order() {
        let (tx, mut rx) = mpsc::channel(256);
        let pending = pending();
        let (resolve_tx, resolve_rx) = oneshot::channel();
        pending
            .lock()
            .unwrap()
            .insert("roboco-1".into(), resolve_tx);
        let (resolve2_tx, resolve2_rx) = oneshot::channel();
        pending.lock().unwrap().insert("roboco-7".into(), resolve2_tx);

        // CRLF responses, plain-LF events, blank and non-JSON noise lines,
        // and a final unterminated event — all in one stream.
        let payload = concat!(
            "not json at all\r\n",
            "\n",
            "{\"id\":\"roboco-1\",\"type\":\"response\",\"command\":\"get_state\",\"success\":true,\"data\":{\"sessionId\":\"s1\"}}\r\n",
            "{\"type\":\"message_update\",\"usage\":{}}\n",
            "{\"id\":\"roboco-7\",\"type\":\"response\",\"command\":\"prompt\",\"success\":false,\"error\":\"already streaming\"}\n",
            "{\"type\":\"response\",\"command\":\"parse\",\"success\":false,\"error\":\"bad input\"}\n",
            "{\"type\":\"final_unterminated\"}"
        );
        let closed = Arc::new(AtomicBool::new(false));
        read_loop(std::io::Cursor::new(payload.as_bytes().to_vec()), pending.clone(), tx, closed.clone()).await;

        assert!(closed.load(Ordering::Acquire));
        // Events forward in stdout order, including the unterminated tail.
        assert!(
            matches!(rx.recv().await, Some(PiIncoming::Event(e)) if e["type"] == "message_update")
        );
        assert!(
            matches!(rx.recv().await, Some(PiIncoming::Event(e)) if e["type"] == "final_unterminated")
        );
        // Responses resolved by id: success carries data, failure the error.
        assert_eq!(resolve_rx.await.unwrap().unwrap()["sessionId"], "s1");
        assert_eq!(resolve2_rx.await.unwrap().unwrap_err(), "already streaming");
        assert!(pending.lock().unwrap().is_empty());
        // EOF delivered last.
        assert!(matches!(rx.recv().await, Some(PiIncoming::Eof)));
    }

    #[tokio::test]
    async fn eof_fails_pending_and_signals_once() {
        let (tx, mut rx) = mpsc::channel(256);
        let pending = pending();
        let (resolve_tx, resolve_rx) = oneshot::channel();
        pending.lock().unwrap().insert("roboco-9".into(), resolve_tx);
        let closed = Arc::new(AtomicBool::new(false));
        // Empty input: immediate EOF.
        read_loop(&[0u8; 0][..], pending.clone(), tx, closed.clone()).await;
        assert!(closed.load(Ordering::Acquire));
        assert!(matches!(rx.recv().await, Some(PiIncoming::Eof)));
        // A dropped sender is the failure signal (the reader's EOF cleanup
        // clears the map), matching how `request` reports it.
        assert!(resolve_rx.await.is_err());
        assert!(pending.lock().unwrap().is_empty());
    }
}
