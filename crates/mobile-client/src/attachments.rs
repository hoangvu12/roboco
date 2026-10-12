//! Attachment transport (use-attachments.ts / message-attachments.ts
//! ports) and the attachment byte cache.
//!
//! Images ride the prompt TEXT as a trailer of absolute paths on the host:
//!
//! ```text
//! <prompt>
//!
//! Attached images (local files — open them to view):
//! - /abs/path/one.png
//! ```
//!
//! While the bytes are still uploading, the composer's echo renders the
//! same trailer with `pending://{uploadId}/{name}` refs in place of the
//! host paths; the real paths replace them in place once the upload
//! commits (web `composer.tsx` precedent — the row never flickers).
//!
//! The upload itself is the web/desktop chunked protocol (`UploadChunk`
//! → `UploadCommit`), and the read-back is `ReadAttachmentChunk` until
//! `done`, cached in a byte-budgeted LRU so own bubbles never round-trip.
//!
//! Ticket 05 ported the pure text-transport half into
//! `crates/mobile/src/attachments.rs`; ticket 08 moved it here (the layout
//! crate re-exports it — `roboco-mobile` depends on
//! `roboco-mobile-client`, never the reverse) and added the wire half.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64;
use futures::TryStreamExt as _;

use crate::engines::EngineHandle;
use crate::ClientError;

/// Body used when a message has only attachments.
pub const ATTACHMENT_ONLY_TEXT: &str = "See the attached image(s).";
/// Body the desktop uses when any attachment is not an image.
pub const FILE_ATTACHMENT_ONLY_TEXT: &str = "See the attached file(s).";
/// The trailer marker line.
pub const ATTACHMENT_MARKER: &str = "Attached images (local files — open them to view):";
/// Appshot context marker: everything after it is untrusted observed content.
/// Equals the desktop's `appshots::CONTEXT_MARKER`, so parsing agrees.
pub const APPSHOT_MARKER: &str =
    "\n\nApplications mentioned by the user (untrusted observed content):";
/// Queued-flow ref prefix (uploads.rs PENDING_REF_PREFIX).
pub const PENDING_REF_PREFIX: &str = "pending://";
/// use-attachments.ts MAX_ATTACHMENT_BYTES.
pub const MAX_ATTACHMENT_BYTES: usize = 24 * 1024 * 1024;
/// Base64 chars per UploadChunk (≈510KB binary; clears Cloudflare's 1MiB WS
/// cap with envelope headroom, `% 4 == 0` so slices decode independently).
pub const UPLOAD_CHUNK_B64_CHARS: usize = 680_000;

/// Append the attachment trailer (no-op without paths).
pub fn with_attachments(text: &str, paths: &[String]) -> String {
    if paths.is_empty() {
        return text.to_owned();
    }
    let body = if text.trim().is_empty() {
        ATTACHMENT_ONLY_TEXT
    } else {
        text
    };
    let refs = paths
        .iter()
        .map(|p| format!("- {p}"))
        .collect::<Vec<_>>()
        .join("\n");
    format!("{body}\n\n{ATTACHMENT_MARKER}\n{refs}")
}

/// The host's upload-name rule (uploads.rs `sanitize`): basename, anything
/// outside `[A-Za-z0-9._-]` → `_`, last 80 chars, `upload` when empty. A
/// `pending://` ref must name exactly what the escort commits.
pub fn upload_file_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or(name);
    let cleaned: String = base
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let tail: String = cleaned
        .chars()
        .rev()
        .take(80)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if tail.is_empty() {
        "upload".to_owned()
    } else {
        tail
    }
}

pub fn pending_ref(upload_id: &str, name: &str) -> String {
    format!("{PENDING_REF_PREFIX}{upload_id}/{name}")
}

/// `(upload_id, file_name)` of a pending ref.
pub fn parse_pending_ref(reference: &str) -> Option<(&str, &str)> {
    let body = reference.strip_prefix(PENDING_REF_PREFIX)?;
    let (id, name) = body.split_once('/')?;
    (!id.is_empty() && !name.is_empty()).then_some((id, name))
}

/// Source labels for one Appshot capture (observed text never surfaces).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppshotLabel {
    pub app_name: String,
    pub window_title: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserImage {
    pub path: String,
    pub name: String,
    pub appshot: Option<AppshotLabel>,
}

/// A user message split into its visible prompt and attachment refs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedUserMessage {
    pub text: String,
    pub images: Vec<UserImage>,
}

/// The visible part of a prompt (drops the Appshot context).
pub fn visible_text(text: &str) -> &str {
    match text.find(APPSHOT_MARKER) {
        Some(at) => text[..at].trim(),
        None => text,
    }
}

fn attribute(tag: &str, name: &str) -> Option<String> {
    let needle = format!("{name}=\"");
    let start = tag.find(&needle)? + needle.len();
    let end = tag[start..].find('"')? + start;
    Some(
        tag[start..end]
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&"),
    )
}

fn appshot_labels(text: &str) -> HashMap<String, AppshotLabel> {
    let Some(at) = text.find(APPSHOT_MARKER) else {
        return HashMap::new();
    };
    let mut out = HashMap::new();
    let mut rest = &text[at + APPSHOT_MARKER.len()..];
    while let Some(open) = rest.find("<appshot ") {
        let Some(close) = rest[open..].find('>') else {
            break;
        };
        let tag = &rest[open..open + close];
        if let (Some(image), Some(app)) = (attribute(tag, "image"), attribute(tag, "app"))
            && !app.trim().is_empty()
        {
            out.insert(
                image,
                AppshotLabel {
                    app_name: app,
                    window_title: attribute(tag, "window-title").filter(|t| !t.is_empty()),
                },
            );
        }
        rest = &rest[open + close..];
    }
    out
}

/// message-attachments.ts `parseUserMessageImages`.
pub fn parse_user_message(content: &str) -> ParsedUserMessage {
    let labels = appshot_labels(content);
    let lines: Vec<&str> = content.split('\n').collect();
    let marker = (1..lines.len()).find(|&ix| {
        let line = lines[ix].trim();
        lines[ix - 1].trim().is_empty()
            && line
                .to_lowercase()
                .starts_with("attached images (local files")
            && line.ends_with("):")
    });
    let Some(marker) = marker else {
        return ParsedUserMessage {
            text: visible_text(content).to_owned(),
            images: Vec::new(),
        };
    };
    let images: Vec<UserImage> = lines[marker + 1..]
        .iter()
        .filter_map(|line| {
            let path = line.trim().strip_prefix("- ")?.trim();
            (!path.is_empty()).then(|| UserImage {
                path: path.to_owned(),
                name: path
                    .rsplit('/')
                    .next()
                    .filter(|n| !n.is_empty())
                    .unwrap_or("image")
                    .to_owned(),
                appshot: labels.get(path).cloned(),
            })
        })
        .collect();
    if images.is_empty() {
        return ParsedUserMessage {
            text: visible_text(content).to_owned(),
            images,
        };
    }
    let body = lines[..marker - 1].join("\n");
    let visible = visible_text(body.trim()).to_owned();
    ParsedUserMessage {
        text: if visible == ATTACHMENT_ONLY_TEXT || visible == FILE_ATTACHMENT_ONLY_TEXT {
            String::new()
        } else {
            visible
        },
        images,
    }
}

/// Queue rows show user text only — never the transport trailer.
pub fn queue_visible_text(text: &str, attachments: &[String]) -> String {
    if attachments.is_empty() {
        return visible_text(text).to_owned();
    }
    let parsed = parse_user_message(text);
    if parsed.text.is_empty() {
        ATTACHMENT_ONLY_TEXT.to_owned()
    } else {
        parsed.text
    }
}

// ── chunked upload / read-back (web lib/attachments.ts port) ──────────────

/// Chunks in flight at once. `seq` slots are idempotent engine-side, so
/// completion order doesn't matter; a small window hides per-chunk latency
/// without flooding the connection.
const UPLOAD_CONCURRENCY: usize = 3;
/// The first WINDOW (not just seq 0) gets the cold-dial allowance — its
/// chunks all start before the link is warm.
const FIRST_CHUNK_TIMEOUT: Duration = Duration::from_secs(90);
const CHUNK_TIMEOUT: Duration = Duration::from_secs(30);
const COMMIT_TIMEOUT: Duration = Duration::from_secs(150);
/// Safety cap on the read-back loop.
const MAX_READ_CHUNKS: usize = 1_000;
const READ_CHUNK_TIMEOUT: Duration = Duration::from_secs(20);
/// Per-chunk retries (2 → 3 attempts total), staggered by
/// `50ms * attempt * (seq + 1)` so parallel chunks that failed together
/// don't re-collide in lockstep.
const CHUNK_RETRIES: u32 = 2;
const RETRY_STAGGER: Duration = Duration::from_millis(50);

/// Whole-attachment deadline. Per-chunk timeouts + retries bound each
/// CALL, but on a flapping link chunks that succeed on attempt 2-of-3
/// never trip any abort — an upload could lawfully crawl for hours reading
/// "Uploading…". Scaled with size, capped (web `attachmentDeadlineMs`).
fn attachment_deadline(n_chunks: usize) -> Duration {
    Duration::from_millis((120_000 + 15_000 * n_chunks as u64).min(900_000))
}

/// The `(seq, b64 byte-range)` plan for a file's chunks. An empty file
/// still sends one empty chunk (the commit needs the uploadId staged).
pub fn chunk_ranges(b64_len: usize) -> Vec<(u64, std::ops::Range<usize>)> {
    let mut ranges = Vec::new();
    let mut start = 0usize;
    let mut seq = 0u64;
    loop {
        let end = (start + UPLOAD_CHUNK_B64_CHARS).min(b64_len);
        ranges.push((seq, start..end));
        start = end;
        seq += 1;
        if start >= b64_len {
            break;
        }
    }
    ranges
}

/// Progress callback for [`upload_attachment`]: the committed-bytes
/// fraction, monotonic, ending at `1.0`.
pub type ProgressFn = Arc<dyn Fn(f64) + Send + Sync>;

/// Chunked upload of one file to the engine; returns its durable absolute
/// path there. `progress` (when given) reports committed bytes / total.
/// The caller mints nothing: the upload id is minted here (the echo's
/// `pending://` ref is derived from the same identity by the caller before
/// the bytes move — see `session/commands.rs`).
pub async fn upload_attachment(
    engine: &EngineHandle,
    name: &str,
    data: Vec<u8>,
    progress: Option<ProgressFn>,
) -> Result<String, ClientError> {
    if data.len() > MAX_ATTACHMENT_BYTES {
        return Err(ClientError::InvalidArgument {
            message: format!("{name} is larger than 24 MB"),
        });
    }
    let total = data.len();
    let b64 = BASE64.encode(&data);
    let ranges = chunk_ranges(b64.len());
    let upload_id = uuid::Uuid::new_v4().to_string();
    let file_name = upload_file_name(name);
    let uploaded = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let work = async {
        futures::stream::iter(ranges.iter().cloned().map(Ok::<_, ClientError>))
            .try_for_each_concurrent(UPLOAD_CONCURRENCY, |(seq, range)| {
                let engine = engine.clone();
                let upload_id = upload_id.clone();
                let b64 = &b64;
                let progress = progress.clone();
                let uploaded = uploaded.clone();
                let total = total;
                async move {
                    let data = b64[range.clone()].to_owned();
                    let timeout = if seq < UPLOAD_CONCURRENCY as u64 {
                        FIRST_CHUNK_TIMEOUT
                    } else {
                        CHUNK_TIMEOUT
                    };
                    // One transient blip must not abort the upload; `seq`
                    // slots are idempotent engine-side, so a blind re-send
                    // is safe (timeouts retry too).
                    let mut attempt = 0u32;
                    loop {
                        let call = engine.call(roboco_rpc::methods::UPLOAD_CHUNK, serde_json::json!({
                            "uploadId": upload_id,
                            "seq": seq,
                            "data": data,
                        }));
                        let result = match tokio::time::timeout(timeout, call).await {
                            Ok(result) => result.map(|_| ()),
                            Err(_) => Err(ClientError::HostUnavailable {
                                message: format!("upload chunk {seq} timed out"),
                            }),
                        };
                        match result {
                            Ok(()) => break,
                            Err(error) => {
                                if attempt >= CHUNK_RETRIES {
                                    return Err(error);
                                }
                                attempt += 1;
                                tokio::time::sleep(RETRY_STAGGER * attempt * (seq + 1) as u32)
                                    .await;
                            }
                        }
                    }
                    // Binary bytes ≈ b64 chars * 3/4; capped so the final
                    // reported value matches the total exactly.
                    let bytes = (range.len() * 3 / 4) as u64;
                    let now = (uploaded.fetch_add(bytes, std::sync::atomic::Ordering::Relaxed)
                        + bytes)
                        .min(total as u64);
                    if let Some(progress) = &progress {
                        progress(now as f64 / total.max(1) as f64);
                    }
                    Ok(())
                }
            })
            .await?;
        let reply = tokio::time::timeout(
            COMMIT_TIMEOUT,
            engine.call(
                roboco_rpc::methods::UPLOAD_COMMIT,
                serde_json::json!({ "uploadId": upload_id, "fileName": file_name }),
            ),
        )
        .await
        .map_err(|_| ClientError::HostUnavailable {
            message: "upload commit timed out".into(),
        })??;
        let path = reply
            .get("path")
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| ClientError::HostError {
                message: "UploadCommit returned no path".into(),
            })?;
        Ok(path)
    };
    let path = tokio::time::timeout(attachment_deadline(ranges.len()), work)
        .await
        .map_err(|_| ClientError::HostUnavailable {
            message: "attachment upload exceeded its deadline".into(),
        })??;
    if let Some(progress) = &progress {
        progress(1.0);
    }
    Ok(path)
}

/// Loop `ReadAttachmentChunk` until `done`, accumulating base64. Aborts on
/// a timeout, an RPC error, a malformed chunk, a stuck offset or a runaway
/// loop (defensive, never infinite).
pub async fn read_attachment_chunks(
    engine: &EngineHandle,
    path: &str,
) -> Result<Vec<u8>, ClientError> {
    let mut name = String::new();
    let mut b64 = String::new();
    let mut offset: u64 = 0;
    for _ in 0..MAX_READ_CHUNKS {
        let reply = tokio::time::timeout(
            READ_CHUNK_TIMEOUT,
            engine.call(
                roboco_rpc::methods::READ_ATTACHMENT_CHUNK,
                serde_json::json!({ "path": path, "offset": offset }),
            ),
        )
        .await
        .map_err(|_| ClientError::HostUnavailable {
            message: "attachment read timed out".into(),
        })??;
        let (chunk, done, next) = match (
            reply.get("data").and_then(serde_json::Value::as_str),
            reply.get("done").and_then(serde_json::Value::as_bool),
            reply.get("nextOffset").and_then(serde_json::Value::as_u64),
        ) {
            (Some(chunk), Some(done), Some(next)) => (chunk, done, next),
            _ => {
                return Err(ClientError::HostError {
                    message: "malformed ReadAttachmentChunk reply".into(),
                })
            }
        };
        if let Some(reported) = reply.get("name").and_then(serde_json::Value::as_str) {
            name = reported.to_owned();
        }
        b64.push_str(chunk);
        if done {
            let bytes = BASE64
                .decode(b64.as_bytes())
                .map_err(|error| ClientError::HostError {
                    message: format!("attachment decode failed: {error}"),
                })?;
            let _ = name;
            return Ok(bytes);
        }
        if next <= offset {
            return Err(ClientError::HostError {
                message: "ReadAttachmentChunk stopped advancing".into(),
            });
        }
        offset = next;
    }
    Err(ClientError::HostError {
        message: "attachment read exceeded the chunk cap".into(),
    })
}

// ── byte-budgeted LRU for reads (`(engine, path)` → bytes) ────────────────

/// Byte-budgeted LRU for attachment reads, keyed `(engine, path)`. Seeded
/// by the send path (own bubbles never round-trip) and by uploads.
pub struct AttachmentCache {
    budget: usize,
    inner: Mutex<CacheInner>,
}

#[derive(Default)]
struct CacheInner {
    bytes: usize,
    map: HashMap<(String, String), Arc<Vec<u8>>>,
    order: VecDeque<(String, String)>,
}

/// The desktop's mobile bound: 48 MiB of decoded attachment bytes.
pub const ATTACHMENT_CACHE_BUDGET: usize = 48 * 1024 * 1024;

impl AttachmentCache {
    pub fn new(budget: usize) -> Self {
        Self {
            budget,
            inner: Mutex::new(CacheInner::default()),
        }
    }

    pub fn get(&self, engine_id: &str, path: &str) -> Option<Arc<Vec<u8>>> {
        let mut inner = crate::lock(&self.inner);
        let key = (engine_id.to_owned(), path.to_owned());
        let hit = inner.map.get(&key).cloned()?;
        if let Some(at) = inner.order.iter().position(|k| *k == key) {
            inner.order.remove(at);
        }
        inner.order.push_back(key);
        Some(hit)
    }

    pub fn put(&self, engine_id: &str, path: &str, data: Arc<Vec<u8>>) {
        let mut inner = crate::lock(&self.inner);
        let key = (engine_id.to_owned(), path.to_owned());
        if let Some(old) = inner.map.insert(key.clone(), data.clone()) {
            inner.bytes -= old.len();
            if let Some(at) = inner.order.iter().position(|k| *k == key) {
                inner.order.remove(at);
            }
        }
        inner.bytes += data.len();
        inner.order.push_back(key);
        while inner.bytes > self.budget && inner.order.len() > 1 {
            let Some(oldest) = inner.order.pop_front() else {
                break;
            };
            if let Some(evicted) = inner.map.remove(&oldest) {
                inner.bytes -= evicted.len();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trailer_round_trips() {
        let paths = vec!["/tmp/a.png".to_owned(), pending_ref("u1", "b.png")];
        let text = with_attachments("look", &paths);
        let parsed = parse_user_message(&text);
        assert_eq!(parsed.text, "look");
        assert_eq!(parsed.images.len(), 2);
        assert_eq!(parsed.images[1].name, "b.png");
        assert_eq!(parse_pending_ref(&paths[1]), Some(("u1", "b.png")));
        let only = with_attachments("", &paths[..1]);
        assert_eq!(parse_user_message(&only).text, "");
        assert_eq!(queue_visible_text(&only, &paths[..1]), ATTACHMENT_ONLY_TEXT);
        let files = format!(
            "{FILE_ATTACHMENT_ONLY_TEXT}\n\n{ATTACHMENT_MARKER}\n- /tmp/ab12cd34-notes.zip"
        );
        assert_eq!(parse_user_message(&files).text, "");
    }

    #[test]
    fn appshot_context_stays_hidden() {
        let text = format!(
            "Compare.{APPSHOT_MARKER}\n<appshot app=\"Notes\" window-title=\"Plan\" image=\"/a.png\">SECRET</appshot>"
        );
        let full = with_attachments(&text, &["/a.png".to_owned()]);
        let parsed = parse_user_message(&full);
        assert_eq!(parsed.text, "Compare.");
        assert_eq!(parsed.images[0].appshot.as_ref().unwrap().app_name, "Notes");
        assert!(!parsed.text.contains("SECRET"));
    }

    #[test]
    fn parse_user_message_round_trips_with_attachments_output() {
        // The acceptance round-trip: parse(with_attachments(x, ps)) == x + ps.
        let paths = vec!["/engine/staging/a1b2-photo.png".to_owned()];
        for text in ["look at this", ""] {
            let full = with_attachments(text, &paths);
            let parsed = parse_user_message(&full);
            assert_eq!(parsed.text, text);
            assert_eq!(
                parsed.images.iter().map(|i| i.path.clone()).collect::<Vec<_>>(),
                paths
            );
        }
    }

    #[test]
    fn cache_evicts_oldest_over_budget() {
        let cache = AttachmentCache::new(10);
        cache.put("d", "a", Arc::new(vec![0; 6]));
        cache.put("d", "b", Arc::new(vec![0; 6]));
        assert!(cache.get("d", "a").is_none());
        assert!(cache.get("d", "b").is_some());
    }

    #[test]
    fn chunk_ranges_tile_the_base64_with_one_empty_chunk_minimum() {
        assert_eq!(chunk_ranges(0), vec![(0, 0..0)]);
        let ranges = chunk_ranges(UPLOAD_CHUNK_B64_CHARS * 2 + 10);
        assert_eq!(ranges.len(), 3);
        assert_eq!(ranges[0], (0, 0..UPLOAD_CHUNK_B64_CHARS));
        assert_eq!(ranges[1], (1, UPLOAD_CHUNK_B64_CHARS..UPLOAD_CHUNK_B64_CHARS * 2));
        assert_eq!(ranges[2], (2, UPLOAD_CHUNK_B64_CHARS * 2..UPLOAD_CHUNK_B64_CHARS * 2 + 10));
    }

    #[test]
    fn upload_file_name_matches_the_host_rule() {
        assert_eq!(upload_file_name("C:\\Users\\me\\Screenshot 2!.png"), "Screenshot_2_.png");
        assert_eq!(upload_file_name("photo.png"), "photo.png");
        assert_eq!(upload_file_name(""), "upload");
        let long = "a".repeat(120);
        assert_eq!(upload_file_name(&long).len(), 80);
    }

    #[test]
    fn attachment_deadline_scales_and_caps() {
        assert_eq!(attachment_deadline(1), Duration::from_secs(135));
        assert_eq!(attachment_deadline(60), Duration::from_secs(900));
    }
}

// ── the Client surface ─────────────────────────────────────────────────────

impl crate::Client {
    /// Chunked upload of one file to the engine owning `device_id` (07's
    /// `DeviceView` field); returns its durable absolute path there. Seeds
    /// the read cache with the bytes under the returned path.
    pub async fn upload_attachment(
        &self,
        device_id: &str,
        name: &str,
        data: Vec<u8>,
        progress: Option<ProgressFn>,
    ) -> Result<String, ClientError> {
        let handle = crate::catalog_live::resolve_engine(&self.inner, device_id)?;
        let path = upload_attachment(&handle, name, data, progress).await?;
        Ok(path)
    }

    /// Attachment bytes from the engine owning `device_id`, served from the
    /// LRU cache.
    pub async fn read_attachment(
        &self,
        device_id: &str,
        path: &str,
    ) -> Result<Vec<u8>, ClientError> {
        let handle = crate::catalog_live::resolve_engine(&self.inner, device_id)?;
        if let Some(bytes) = self.inner.attachments.get(handle.engine_id(), path) {
            return Ok((*bytes).clone());
        }
        let bytes = read_attachment_chunks(&handle, path).await?;
        self.inner
            .attachments
            .put(handle.engine_id(), path, Arc::new(bytes.clone()));
        Ok(bytes)
    }
}
