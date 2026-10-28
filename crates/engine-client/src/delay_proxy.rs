//! A loopback TCP forwarder that behaves like a slow remote link: every
//! byte waits out a fixed one-way delay and shares a bandwidth cap, and a
//! new connection pays one round trip before its first byte crosses (the
//! TCP handshake through a tunnel). It also taps the WebSocket RPC frames
//! it carries, so a test can read which requests a client made, when, and
//! when their replies landed — without instrumenting the client.
//!
//! Test support only (fleet-redesign 17): the measurement and the
//! regression tests for opening a chat on another machine run desktop and
//! phone clients through it.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::mpsc;
use tokio::time::Instant;

/// The shape of the simulated link.
#[derive(Debug, Clone, Copy)]
pub struct LinkProfile {
    /// Delay of each direction; a round trip is twice this.
    pub one_way: Duration,
    /// Bytes per second each direction carries.
    pub bytes_per_sec: u64,
}

impl LinkProfile {
    /// The user's relayed Tailcat link (fleet-redesign 16/17): about
    /// 160 ms per round trip and about 1.2 MB/s.
    pub const RELAYED: Self = Self {
        one_way: Duration::from_millis(80),
        bytes_per_sec: 1_200_000,
    };

    pub fn round_trip(&self) -> Duration {
        self.one_way * 2
    }
}

/// One RPC request seen on the wire, with the timing of its replies as the
/// client received them. Times are offsets from [`DelayProxy::epoch`].
#[derive(Debug, Clone)]
pub struct RpcCall {
    /// The proxied connection it rode (accept order, from 0).
    pub connection: usize,
    pub id: u64,
    pub method: String,
    /// When the client sent the request.
    pub sent: Duration,
    /// When the client held the whole first reply frame (a unary reply or a
    /// stream's first item).
    pub first_reply: Option<Duration>,
    /// When the client held the whole most recent reply frame.
    pub last_reply: Option<Duration>,
    /// Reply frames received so far.
    pub replies: usize,
    /// Reply payload bytes received so far.
    pub reply_bytes: usize,
}

#[derive(Default)]
struct Log {
    calls: Vec<RpcCall>,
    connections: usize,
    /// Bytes delivered to clients so far, frames finished or not.
    bytes_down: u64,
}

pub struct DelayProxy {
    pub address: SocketAddr,
    pub profile: LinkProfile,
    epoch: Instant,
    log: Arc<Mutex<Log>>,
    task: tokio::task::JoinHandle<()>,
}

impl DelayProxy {
    /// Forward `127.0.0.1:0` → `target` over a link shaped like `profile`.
    pub async fn start(target: SocketAddr, profile: LinkProfile) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind the delay proxy");
        let address = listener.local_addr().expect("proxy address");
        let epoch = Instant::now();
        let log = Arc::new(Mutex::new(Log::default()));
        let accept_log = log.clone();
        let task = tokio::spawn(async move {
            loop {
                let Ok((client, _)) = listener.accept().await else {
                    break;
                };
                let accepted = Instant::now();
                let connection = {
                    let mut log = lock(&accept_log);
                    log.connections += 1;
                    log.connections - 1
                };
                let log = accept_log.clone();
                tokio::spawn(async move {
                    let Ok(engine) = tokio::net::TcpStream::connect(target).await else {
                        return;
                    };
                    let _ = client.set_nodelay(true);
                    let _ = engine.set_nodelay(true);
                    let (client_read, client_write) = client.into_split();
                    let (engine_read, engine_write) = engine.into_split();
                    // The handshake: nothing the client sends crosses before
                    // one round trip has passed.
                    let handshake = accepted + profile.round_trip();
                    let up = Tap::new(Direction::Up, connection, epoch, log.clone());
                    let down = Tap::new(Direction::Down, connection, epoch, log);
                    tokio::spawn(pipe(client_read, engine_write, profile, handshake, up));
                    tokio::spawn(pipe(engine_read, client_write, profile, accepted, down));
                });
            }
        });
        Self {
            address,
            profile,
            epoch,
            log,
            task,
        }
    }

    /// The instant every [`RpcCall`] time is measured from.
    pub fn epoch(&self) -> Instant {
        self.epoch
    }

    /// Time since [`Self::epoch`].
    pub fn now(&self) -> Duration {
        self.epoch.elapsed()
    }

    /// Every request seen so far, in send order.
    pub fn calls(&self) -> Vec<RpcCall> {
        let mut calls = lock(&self.log).calls.clone();
        calls.sort_by_key(|call| call.sent);
        calls
    }

    /// Requests sent at or after `since`.
    pub fn calls_since(&self, since: Duration) -> Vec<RpcCall> {
        self.calls()
            .into_iter()
            .filter(|call| call.sent >= since)
            .collect()
    }

    /// Connections accepted so far.
    pub fn connections(&self) -> usize {
        lock(&self.log).connections
    }

    /// Bytes delivered to clients so far, whole frames or not: a test that
    /// waits for the link to go quiet must see a big frame still crossing.
    pub fn bytes_down(&self) -> u64 {
        lock(&self.log).bytes_down
    }

    /// A readable table of `calls`, times relative to `since`, in round
    /// trips as well as milliseconds.
    pub fn timeline(&self, calls: &[RpcCall], since: Duration) -> String {
        let rtt = self.profile.round_trip().as_secs_f64() * 1000.0;
        let ms = |d: Duration| d.saturating_sub(since).as_secs_f64() * 1000.0;
        let mut out = String::from(
            "conn  method                         sent ms   1st reply ms  (rtt)  frames  bytes\n",
        );
        for call in calls {
            let reply = call
                .first_reply
                .map(|t| format!("{:>8.0}  ({:>4.1})", ms(t), ms(t) / rtt))
                .unwrap_or_else(|| "       -        ".into());
            out.push_str(&format!(
                "{:>4}  {:<28} {:>8.0}  {}  {:>6}  {:>8}\n",
                call.connection,
                call.method,
                ms(call.sent),
                reply,
                call.replies,
                call.reply_bytes
            ));
        }
        out
    }
}

impl Drop for DelayProxy {
    fn drop(&mut self) {
        self.task.abort();
    }
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}

/// Carry `from` → `to`: each chunk leaves once the link is free, takes
/// `len / bytes_per_sec` to serialize, and lands `one_way` later.
async fn pipe(
    mut from: OwnedReadHalf,
    mut to: OwnedWriteHalf,
    profile: LinkProfile,
    open_at: Instant,
    mut tap: Tap,
) {
    let (tx, mut rx) = mpsc::unbounded_channel::<(Instant, Vec<u8>)>();
    let reader = tokio::spawn(async move {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            match from.read(&mut buf).await {
                Ok(0) | Err(_) => return,
                Ok(n) => {
                    if tx.send((Instant::now(), buf[..n].to_vec())).is_err() {
                        return;
                    }
                }
            }
        }
    });
    let mut link_free = open_at;
    while let Some((read_at, bytes)) = rx.recv().await {
        if tap.direction == Direction::Up {
            tap.feed(&bytes, read_at);
        }
        let start = link_free.max(read_at);
        let serialize = Duration::from_secs_f64(bytes.len() as f64 / profile.bytes_per_sec as f64);
        link_free = start + serialize;
        tokio::time::sleep_until(link_free + profile.one_way).await;
        if to.write_all(&bytes).await.is_err() {
            break;
        }
        if tap.direction == Direction::Down {
            lock(&tap.log).bytes_down += bytes.len() as u64;
            tap.feed(&bytes, Instant::now());
        }
    }
    reader.abort();
    let _ = to.shutdown().await;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Direction {
    /// Client → engine: requests, timed when the client sent them.
    Up,
    /// Engine → client: replies, timed when the client received them.
    Down,
}

/// How many payload bytes of each frame are kept for parsing (`id` and
/// `method` lead every RPC frame).
const PREFIX: usize = 256;

/// An incremental parser for one direction of one connection: skips the
/// HTTP upgrade, then reads WebSocket frames, joins fragmented messages
/// (the engine fragments big replies), and logs the RPC messages.
struct Tap {
    direction: Direction,
    connection: usize,
    epoch: Instant,
    log: Arc<Mutex<Log>>,
    upgraded: bool,
    header_tail: Vec<u8>,
    /// The frame header being read.
    header: Vec<u8>,
    /// Inside a frame's payload.
    in_frame: bool,
    remaining: u64,
    mask: Option<[u8; 4]>,
    /// The frame being read is a control frame.
    control: bool,
    /// FIN of the header just parsed, and of the data frame being read.
    header_fin: bool,
    fin: bool,
    seen: u64,
    /// The message being assembled: its opcode (from its first frame),
    /// payload prefix, total length, and when its first byte arrived.
    message_opcode: u8,
    message_prefix: Vec<u8>,
    message_len: u64,
    message_started: Option<Instant>,
}

impl Tap {
    fn new(direction: Direction, connection: usize, epoch: Instant, log: Arc<Mutex<Log>>) -> Self {
        Self {
            direction,
            connection,
            epoch,
            log,
            upgraded: false,
            header_tail: Vec::new(),
            header: Vec::new(),
            in_frame: false,
            remaining: 0,
            mask: None,
            control: false,
            header_fin: false,
            fin: false,
            seen: 0,
            message_opcode: 0,
            message_prefix: Vec::new(),
            message_len: 0,
            message_started: None,
        }
    }

    fn feed(&mut self, bytes: &[u8], at: Instant) {
        if !self.upgraded {
            self.header_tail.extend_from_slice(bytes);
            let Some(end) = find(&self.header_tail, b"\r\n\r\n") else {
                return;
            };
            self.upgraded = true;
            let rest = self.header_tail.split_off(end + 4);
            self.header_tail.clear();
            return self.feed_frames(&rest, at);
        }
        self.feed_frames(bytes, at);
    }

    fn feed_frames(&mut self, mut bytes: &[u8], at: Instant) {
        while !bytes.is_empty() {
            if !self.in_frame {
                self.header.push(bytes[0]);
                bytes = &bytes[1..];
                let Some((payload_len, mask)) = parse_header(&self.header) else {
                    continue;
                };
                self.header_fin = self.header[0] & 0x80 != 0;
                let opcode = self.header[0] & 0x0f;
                self.header.clear();
                self.remaining = payload_len;
                self.mask = mask;
                self.seen = 0;
                self.in_frame = true;
                // Control frames (ping, pong, close) may arrive between the
                // fragments of a data message; they carry no RPC and never
                // touch the message being assembled.
                self.control = opcode & 0x08 != 0;
                if !self.control {
                    self.fin = self.header_fin;
                    if opcode != 0 {
                        self.message_opcode = opcode;
                        self.message_prefix.clear();
                        self.message_len = 0;
                        self.message_started = Some(at);
                    }
                    self.message_len += payload_len;
                }
                if payload_len == 0 {
                    self.end_frame(at);
                }
                continue;
            }
            let take = (self.remaining as usize).min(bytes.len());
            if !self.control && self.message_prefix.len() < PREFIX {
                let keep = take.min(PREFIX - self.message_prefix.len());
                for (i, byte) in bytes[..keep].iter().enumerate() {
                    let offset = (self.seen as usize + i) % 4;
                    self.message_prefix
                        .push(self.mask.map_or(*byte, |mask| byte ^ mask[offset]));
                }
            }
            self.seen += take as u64;
            self.remaining -= take as u64;
            bytes = &bytes[take..];
            if self.remaining == 0 {
                self.end_frame(at);
            }
        }
    }

    fn end_frame(&mut self, at: Instant) {
        self.in_frame = false;
        if !self.control && self.fin {
            self.finish(at);
        }
    }

    fn finish(&mut self, at: Instant) {
        let started = self.message_started.take().unwrap_or(at);
        // Text and binary messages carry the RPC JSON; control frames are
        // not RPC.
        if self.message_opcode != 1 && self.message_opcode != 2 {
            return;
        }
        let text = String::from_utf8_lossy(&self.message_prefix);
        let Some(id) = json_u64(&text, "\"id\":") else {
            return;
        };
        let mut log = lock(&self.log);
        match self.direction {
            Direction::Up => {
                let Some(method) = json_str(&text, "\"method\":\"") else {
                    return; // a cancel
                };
                log.calls.push(RpcCall {
                    connection: self.connection,
                    id,
                    method,
                    sent: started.duration_since(self.epoch),
                    first_reply: None,
                    last_reply: None,
                    replies: 0,
                    reply_bytes: 0,
                });
            }
            Direction::Down => {
                let connection = self.connection;
                if let Some(call) = log
                    .calls
                    .iter_mut()
                    .rev()
                    .find(|call| call.connection == connection && call.id == id)
                {
                    let at = at.duration_since(self.epoch);
                    call.first_reply.get_or_insert(at);
                    call.last_reply = Some(at);
                    call.replies += 1;
                    call.reply_bytes += self.message_len as usize;
                }
            }
        }
    }
}

/// `(payload length, mask)` once `frame` holds a whole WebSocket frame
/// header.
fn parse_header(frame: &[u8]) -> Option<(u64, Option<[u8; 4]>)> {
    if frame.len() < 2 {
        return None;
    }
    let masked = frame[1] & 0x80 != 0;
    let (len_bytes, payload_len) = match frame[1] & 0x7f {
        126 => (2, None),
        127 => (8, None),
        len => (0, Some(u64::from(len))),
    };
    let header = 2 + len_bytes + if masked { 4 } else { 0 };
    if frame.len() < header {
        return None;
    }
    let payload_len = payload_len.unwrap_or_else(|| {
        frame[2..2 + len_bytes]
            .iter()
            .fold(0u64, |acc, byte| (acc << 8) | u64::from(*byte))
    });
    let mask = masked.then(|| {
        let at = 2 + len_bytes;
        [frame[at], frame[at + 1], frame[at + 2], frame[at + 3]]
    });
    Some((payload_len, mask))
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn json_u64(text: &str, key: &str) -> Option<u64> {
    let start = text.find(key)? + key.len();
    let digits: String = text[start..]
        .chars()
        .skip_while(|c| c.is_whitespace())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse().ok()
}

fn json_str(text: &str, key: &str) -> Option<String> {
    let start = text.find(key)? + key.len();
    let end = text[start..].find('"')?;
    Some(text[start..start + end].to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A real client and engine over the proxy: the call is logged with its
    /// method, and the reply lands no sooner than the handshake round trip
    /// plus the upgrade round trip plus the call's own round trip.
    #[tokio::test]
    async fn logs_calls_and_delays_them_by_the_link() {
        struct Echo;
        #[async_trait::async_trait]
        impl roboco_rpc::RpcService for Echo {
            async fn handle(
                &self,
                method: &str,
                _params: serde_json::Value,
            ) -> Result<roboco_rpc::RpcReply, roboco_rpc::RpcError> {
                roboco_rpc::RpcReply::value(&serde_json::json!({ "method": method }))
            }
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let engine = listener.local_addr().unwrap();
        tokio::spawn(roboco_rpc::serve_ws_listener(listener, Arc::new(Echo)));
        let profile = LinkProfile {
            one_way: Duration::from_millis(40),
            bytes_per_sec: 10_000_000,
        };
        let proxy = DelayProxy::start(engine, profile).await;
        let started = proxy.now();
        let client = roboco_rpc::connect_ws(&format!("ws://{}", proxy.address))
            .await
            .unwrap();
        let reply = client.call("Ping", serde_json::json!({})).await.unwrap();
        assert_eq!(reply["method"], "Ping");
        let calls = proxy.calls();
        assert_eq!(calls.len(), 1, "{calls:?}");
        assert_eq!(calls[0].method, "Ping");
        let waited = calls[0].first_reply.unwrap() - started;
        assert!(waited >= profile.round_trip() * 3, "{waited:?}");
        assert!(waited < profile.round_trip() * 6, "{waited:?}");
    }

    /// A reply fragmented across frames, with a ping between the fragments,
    /// counts as one reply of its whole size.
    #[test]
    fn joins_fragmented_replies_around_control_frames() {
        let epoch = Instant::now();
        let log = Arc::new(Mutex::new(Log::default()));
        let mut up = Tap::new(Direction::Up, 0, epoch, log.clone());
        let mut down = Tap::new(Direction::Down, 0, epoch, log.clone());
        up.feed(b"GET / HTTP/1.1\r\n\r\n", epoch);
        down.feed(b"HTTP/1.1 101 Switching\r\n\r\n", epoch);
        let request = br#"{"id":7,"method":"WatchDocMessages"}"#;
        let mask = [1u8, 2, 3, 4];
        let mut frame = vec![0x81, 0x80 | request.len() as u8];
        frame.extend_from_slice(&mask);
        frame.extend(request.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
        up.feed(&frame, epoch);
        let first = br#"{"id":7,"item":"#;
        let rest = br#""tail"}"#;
        let mut stream = vec![0x01, first.len() as u8];
        stream.extend_from_slice(first);
        stream.extend_from_slice(&[0x89, 0x00]); // a ping between fragments
        stream.extend_from_slice(&[0x80, rest.len() as u8]);
        stream.extend_from_slice(rest);
        // Byte by byte: frame boundaries never line up with reads.
        for byte in stream {
            down.feed(&[byte], epoch);
        }
        let calls = lock(&log).calls.clone();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].method, "WatchDocMessages");
        assert_eq!(calls[0].replies, 1);
        assert_eq!(calls[0].reply_bytes, first.len() + rest.len());
    }
}
