//! `ReadImageChunk` — serve any image reference an agent can write in
//! Markdown, in the same 45KB base64 chunks as `ReadAttachmentChunk`.
//!
//! A reference is tried every reasonable way before it fails:
//! - `http(s)://` URLs are fetched by the engine (redirects followed), so
//!   `localhost`, LAN hosts and anything else only this machine can reach
//!   work. A failed fetch is remembered briefly so a dead URL is not
//!   hammered by retrying tiles.
//! - `data:` URIs decode here too (clients usually decode them locally and
//!   only send the ones they can't, such as SVG).
//! - `file://` URIs, `roboco-file:` mention links, `pending://` upload refs,
//!   `~/…` (this user's home), absolute paths, and paths relative to the
//!   chat's cwd (`shots/x.png`, `./x.png`, `../x.png`). Percent-encoded and
//!   `?query`/`#fragment`-suffixed forms are tried as well.
//! - SVG (by extension, MIME type or content) is rasterized to PNG with
//!   resvg, because phones have no SVG decoder.
//!
//! There is no path jail: see `uploads.rs`. Remote bytes, data URIs and
//! rasterized SVGs sit in a small in-memory cache so the follow-up chunk
//! calls of one read don't refetch or re-render.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD as BASE64, STANDARD_NO_PAD, URL_SAFE_NO_PAD};

use crate::EngineError;
use crate::uploads::{AttachmentChunk, Uploads, read_file_chunk, sniff_mime};

/// Same chunk size as `ReadAttachmentChunk`. The per-image cap here bounds
/// engine-SOURCED images (URL fetches, data URIs, SVGs before rasterization)
/// and stayed at 32 MB when uploads rose to 100 MB behind
/// `uploads-100mb-v1` (ADR 0011): local file reads route through
/// `read_file_chunk`, which follows the uploads cap.
const CHUNK_BYTES: usize = 45_000;
const MAX_BYTES: usize = 32 * 1024 * 1024;
/// Total budget of cached remote/rendered images, and how long one lives
/// unread.
const CACHE_BUDGET: usize = 64 * 1024 * 1024;
const CACHE_TTL: Duration = Duration::from_secs(120);
/// How long a failed URL fetch answers from memory instead of refetching.
const FAILURE_TTL: Duration = Duration::from_secs(15);
/// Connect and whole-request limits for one URL fetch. The whole-request
/// limit stays under the clients' 30 s call deadline, so a slow host fails
/// with its own error instead of a client timeout.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const FETCH_TIMEOUT: Duration = Duration::from_secs(20);
const FETCH_DEADLINE: Duration = Duration::from_secs(24);
/// Rasterized SVGs: twice the intrinsic size, with the long side kept
/// between these bounds.
const SVG_MIN_SIDE: f32 = 512.0;
const SVG_MAX_SIDE: f32 = 2048.0;

const USER_AGENT: &str = concat!(
    "Mozilla/5.0 (compatible; Roboco/",
    env!("CARGO_PKG_VERSION"),
    "; +https://github.com/hoangvu12/roboco)"
);
const ACCEPT: &str = "image/avif,image/webp,image/apng,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5";

/// One fully loaded image held for chunked replies.
struct Loaded {
    name: String,
    mime_type: String,
    bytes: Vec<u8>,
}

#[derive(Default)]
struct Cache {
    entries: HashMap<String, (Instant, Arc<Loaded>)>,
    failures: HashMap<String, (Instant, String)>,
    bytes: usize,
}

impl Cache {
    /// A hit refreshes the entry: the TTL slides and eviction is least
    /// recently used, so a long chunked read keeps its bytes to the end.
    fn get(&mut self, key: &str) -> Option<Arc<Loaded>> {
        self.expire();
        let (at, loaded) = self.entries.get_mut(key)?;
        *at = Instant::now();
        Some(loaded.clone())
    }

    fn put(&mut self, key: String, loaded: Arc<Loaded>) {
        self.expire();
        if loaded.bytes.len() > CACHE_BUDGET {
            return;
        }
        if let Some((_, old)) = self.entries.remove(&key) {
            self.bytes -= old.bytes.len();
        }
        while self.bytes + loaded.bytes.len() > CACHE_BUDGET {
            let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, (at, _))| *at)
                .map(|(key, _)| key.clone())
            else {
                break;
            };
            if let Some((_, old)) = self.entries.remove(&oldest) {
                self.bytes -= old.bytes.len();
            }
        }
        self.bytes += loaded.bytes.len();
        self.entries.insert(key, (Instant::now(), loaded));
    }

    fn failure(&mut self, key: &str) -> Option<String> {
        self.expire();
        self.failures.get(key).map(|(_, error)| error.clone())
    }

    fn expire(&mut self) {
        let now = Instant::now();
        let mut freed = 0;
        self.entries.retain(|_, (at, loaded)| {
            let keep = now.duration_since(*at) < CACHE_TTL;
            if !keep {
                freed += loaded.bytes.len();
            }
            keep
        });
        self.bytes -= freed;
        self.failures
            .retain(|_, (at, _)| now.duration_since(*at) < FAILURE_TTL);
    }
}

/// The engine's image-reference reader (one per RPC service).
#[derive(Clone)]
pub struct ImageRefs {
    uploads: Uploads,
    http: reqwest::Client,
    cache: Arc<Mutex<Cache>>,
}

impl ImageRefs {
    pub fn new(uploads: Uploads) -> Self {
        let http = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .redirect(reqwest::redirect::Policy::limited(10))
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(FETCH_TIMEOUT)
            .build()
            .unwrap_or_default();
        Self {
            uploads,
            http,
            cache: Arc::default(),
        }
    }

    /// One chunk of the image `reference` names. `cwd` is the chat's working
    /// directory, the base for relative paths.
    pub async fn read_chunk(
        &self,
        reference: &str,
        cwd: Option<PathBuf>,
        offset: u64,
    ) -> Result<AttachmentChunk, EngineError> {
        let reference = clean_reference(reference);
        if reference.is_empty() {
            return Err(EngineError::Other("Empty image reference".into()));
        }
        if starts_with_ignore_case(reference, "http://")
            || starts_with_ignore_case(reference, "https://")
        {
            let loaded = self.remote(reference).await?;
            return Ok(chunk_of(&loaded, offset));
        }
        if starts_with_ignore_case(reference, "data:") {
            let key = cache_key("data", reference);
            let loaded = match self.cached(&key) {
                Some(hit) => hit,
                None => {
                    let (mime, bytes) = decode_data_uri(reference)
                        .ok_or_else(|| EngineError::Other("Malformed data: URI".into()))?;
                    let loaded = finish("image".into(), Some(mime), bytes, None).await?;
                    self.store(key, loaded)
                }
            };
            return Ok(chunk_of(&loaded, offset));
        }
        let uploads = self.uploads.clone();
        let reference_owned = reference.to_owned();
        let path = tokio::task::spawn_blocking(move || {
            resolve_local(&uploads, &reference_owned, cwd.as_deref())
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()))??;
        if !is_svg_path(&path) && !file_is_svg(&path) {
            return tokio::task::spawn_blocking(move || {
                // An image read answers with an image or an error, never
                // bytes a client would cache and fail to decode.
                if !crate::uploads::has_known_image_extension(&path) && !file_sniffs_as_image(&path) {
                    return Err(EngineError::Other(format!("{} is not an image", path.display())));
                }
                read_file_chunk(&path, offset)
            })
            .await
            .map_err(|e| EngineError::Other(e.to_string()))?;
        }
        let key = cache_key("svg", &path.to_string_lossy());
        let loaded = match self.cached(&key) {
            Some(hit) => hit,
            None => {
                let read_path = path.clone();
                let bytes = tokio::task::spawn_blocking(move || std::fs::read(read_path))
                    .await
                    .map_err(|e| EngineError::Other(e.to_string()))??;
                if bytes.len() > MAX_BYTES {
                    return Err(EngineError::Other("Image is too large".into()));
                }
                let loaded = finish(
                    file_name(&path.to_string_lossy()),
                    Some("image/svg+xml".into()),
                    bytes,
                    path.parent().map(Path::to_path_buf),
                )
                .await?;
                self.store(key, loaded)
            }
        };
        Ok(chunk_of(&loaded, offset))
    }

    fn cached(&self, key: &str) -> Option<Arc<Loaded>> {
        lock(&self.cache).get(key)
    }

    fn store(&self, key: String, loaded: Loaded) -> Arc<Loaded> {
        let loaded = Arc::new(loaded);
        lock(&self.cache).put(key, loaded.clone());
        loaded
    }

    async fn remote(&self, url: &str) -> Result<Arc<Loaded>, EngineError> {
        let key = cache_key("url", url);
        if let Some(hit) = self.cached(&key) {
            return Ok(hit);
        }
        if let Some(error) = lock(&self.cache).failure(&key) {
            return Err(EngineError::Other(error));
        }
        // One deadline over the whole fetch (a GitHub `blob` page costs two
        // requests), under the clients' 30 s call deadline.
        let fetched = tokio::time::timeout(FETCH_DEADLINE, self.fetch(url))
            .await
            .unwrap_or_else(|_| Err(EngineError::Other(format!("Fetching {url} timed out"))));
        match fetched {
            Ok(loaded) => Ok(self.store(key, loaded)),
            Err(error) => {
                lock(&self.cache)
                    .failures
                    .insert(key, (Instant::now(), error.to_string()));
                Err(error)
            }
        }
    }

    async fn fetch(&self, url: &str) -> Result<Loaded, EngineError> {
        let (mime, bytes) = self.get(url).await?;
        // A GitHub `blob` page is HTML around the file; its `raw` twin is the
        // file itself.
        let html = mime.as_deref().is_some_and(|m| m.starts_with("text/html"));
        let (mime, bytes) = match github_raw_url(url) {
            Some(raw) if html => self.get(&raw).await?,
            _ => (mime, bytes),
        };
        finish(file_name(url_path(url)), mime, bytes, None).await
    }

    async fn get(&self, url: &str) -> Result<(Option<String>, Vec<u8>), EngineError> {
        let failed = |e: reqwest::Error| EngineError::Other(format!("Couldn't fetch {url}: {e}"));
        let mut response = self
            .http
            .get(url)
            .header(reqwest::header::ACCEPT, ACCEPT)
            .send()
            .await
            .map_err(failed)?
            .error_for_status()
            .map_err(failed)?;
        if response
            .content_length()
            .is_some_and(|len| len as usize > MAX_BYTES)
        {
            return Err(EngineError::Other("Image is too large".into()));
        }
        let mime = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .map(|value| {
                value
                    .split(';')
                    .next()
                    .unwrap_or_default()
                    .trim()
                    .to_ascii_lowercase()
            })
            .filter(|value| !value.is_empty());
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(failed)? {
            if bytes.len() + chunk.len() > MAX_BYTES {
                return Err(EngineError::Other("Image is too large".into()));
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok((mime, bytes))
    }
}

/// Settle the type of loaded bytes and rasterize SVG to PNG.
async fn finish(
    name: String,
    declared: Option<String>,
    bytes: Vec<u8>,
    resources_dir: Option<PathBuf>,
) -> Result<Loaded, EngineError> {
    let head = &bytes[..bytes.len().min(512)];
    let sniffed = sniff_mime(head);
    let mime_type = match (sniffed, declared) {
        (Some(sniffed), _) => sniffed.to_owned(),
        (None, Some(declared)) => declared,
        (None, None) => "application/octet-stream".to_owned(),
    };
    if !mime_type.starts_with("image/") {
        // An error page, a JSON body…: an error, so nothing caches it.
        return Err(EngineError::Other(format!("{name} is not an image ({mime_type})")));
    }
    if mime_type != "image/svg+xml" {
        return Ok(Loaded {
            name,
            mime_type,
            bytes,
        });
    }
    let png = tokio::task::spawn_blocking(move || rasterize_svg(&bytes, resources_dir))
        .await
        .map_err(|e| EngineError::Other(e.to_string()))??;
    Ok(Loaded {
        name: format!("{}.png", name.trim_end_matches(".svg")),
        mime_type: "image/png".into(),
        bytes: png,
    })
}

fn lock(cache: &Mutex<Cache>) -> std::sync::MutexGuard<'_, Cache> {
    cache.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn chunk_of(loaded: &Loaded, offset: u64) -> AttachmentChunk {
    let size = loaded.bytes.len();
    let start = (offset as usize).min(size);
    let end = (start + CHUNK_BYTES).min(size);
    AttachmentChunk {
        name: loaded.name.clone(),
        mime_type: loaded.mime_type.clone(),
        data: BASE64.encode(&loaded.bytes[start..end]),
        next_offset: end as u64,
        done: end >= size,
    }
}

fn cache_key(kind: &str, value: &str) -> String {
    use sha2::{Digest, Sha256};
    format!("{kind}:{:x}", Sha256::digest(value.as_bytes()))
}

/// Render an SVG document to PNG bytes.
pub(crate) fn rasterize_svg(
    bytes: &[u8],
    resources_dir: Option<PathBuf>,
) -> Result<Vec<u8>, EngineError> {
    static FONTS: OnceLock<Arc<resvg::usvg::fontdb::Database>> = OnceLock::new();
    let fontdb = FONTS
        .get_or_init(|| {
            let mut db = resvg::usvg::fontdb::Database::new();
            db.load_system_fonts();
            Arc::new(db)
        })
        .clone();
    let options = resvg::usvg::Options {
        resources_dir,
        fontdb,
        ..Default::default()
    };
    let tree = resvg::usvg::Tree::from_data(bytes, &options)
        .map_err(|e| EngineError::Other(format!("Couldn't read the SVG: {e}")))?;
    let size = tree.size();
    let long = size.width().max(size.height()).max(1.0);
    let scale = (long * 2.0).clamp(SVG_MIN_SIDE, SVG_MAX_SIDE) / long;
    let width = (size.width() * scale).ceil().max(1.0) as u32;
    let height = (size.height() * scale).ceil().max(1.0) as u32;
    let mut pixmap = resvg::tiny_skia::Pixmap::new(width, height)
        .ok_or_else(|| EngineError::Other("SVG is too large to render".into()))?;
    resvg::render(
        &tree,
        resvg::tiny_skia::Transform::from_scale(scale, scale),
        &mut pixmap.as_mut(),
    );
    pixmap
        .encode_png()
        .map_err(|e| EngineError::Other(format!("Couldn't encode the SVG render: {e}")))
}

/// Trim whitespace and Markdown's optional `<…>` destination brackets.
fn clean_reference(reference: &str) -> &str {
    let trimmed = reference.trim();
    trimmed
        .strip_prefix('<')
        .and_then(|inner| inner.strip_suffix('>'))
        .unwrap_or(trimmed)
        .trim()
}

fn starts_with_ignore_case(text: &str, prefix: &str) -> bool {
    text.get(..prefix.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
}

/// The local file a non-URL reference names: the first candidate that is
/// a file, in [`local_candidates`] order.
fn resolve_local(
    uploads: &Uploads,
    reference: &str,
    cwd: Option<&Path>,
) -> Result<PathBuf, EngineError> {
    if crate::uploads::is_pending_ref(reference) {
        return uploads.resolve_attachment_path(reference);
    }
    let candidates = local_candidates(reference, cwd, &crate::repos::home_dir());
    candidates
        .iter()
        .find(|path| path.is_file())
        .cloned()
        .ok_or_else(|| match candidates.first() {
            Some(path) => EngineError::Other(format!("No image at {}", path.display())),
            None if cwd.is_none() => EngineError::Other(format!(
                "Can't resolve the relative image path {reference} without its chat"
            )),
            None => EngineError::Other(format!("Can't resolve {reference}")),
        })
}

/// Every path a local reference might mean, most literal first.
fn local_candidates(reference: &str, cwd: Option<&Path>, home: &Path) -> Vec<PathBuf> {
    fn push(spellings: &mut Vec<String>, spelling: String) {
        if !spelling.is_empty() && !spellings.contains(&spelling) {
            spellings.push(spelling);
        }
    }
    let mut spellings: Vec<String> = Vec::new();
    if let Some(rest) = strip_prefix_ignore_case(reference, "roboco-file:") {
        push(&mut spellings, percent_decode(rest));
    } else if let Some(rest) = strip_prefix_ignore_case(reference, "file:") {
        push(&mut spellings, file_uri_path(rest));
    } else {
        push(&mut spellings, reference.to_owned());
        push(&mut spellings, percent_decode(reference));
    }
    // `shot.png?raw=1`, `diagram.svg#layer`: the file without the suffix.
    for spelling in spellings.clone() {
        if let Some(end) = spelling.find(['?', '#']) {
            push(&mut spellings, spelling[..end].to_owned());
        }
    }
    let mut paths = Vec::new();
    for spelling in spellings {
        let path = if spelling == "~" {
            Some(home.to_path_buf())
        } else if let Some(rest) = spelling
            .strip_prefix("~/")
            .or_else(|| spelling.strip_prefix("~\\"))
        {
            Some(home.join(rest))
        } else if is_absolute_reference(&spelling) {
            Some(PathBuf::from(&spelling))
        } else {
            cwd.map(|cwd| cwd.join(&spelling))
        };
        if let Some(path) = path
            && !paths.contains(&path)
        {
            paths.push(path);
        }
    }
    paths
}

/// Absolute on this host, or absolute in a form agents print on any host
/// (`/…`, `\\server\share`, `C:\…`, `C:/…`).
fn is_absolute_reference(path: &str) -> bool {
    let bytes = path.as_bytes();
    Path::new(path).is_absolute()
        || path.starts_with('/')
        || path.starts_with('\\')
        || (bytes.len() >= 3
            && bytes[0].is_ascii_alphabetic()
            && bytes[1] == b':'
            && matches!(bytes[2], b'/' | b'\\'))
}

fn strip_prefix_ignore_case<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    starts_with_ignore_case(text, prefix).then(|| &text[prefix.len()..])
}

/// The path of a `file:` URI's remainder (after `file:`): `///C:/x`,
/// `///home/x`, `//localhost/x`, `//server/share/x` (UNC) or `x`.
fn file_uri_path(rest: &str) -> String {
    let path = match rest.strip_prefix("//") {
        Some(authority_and_path) => {
            let (host, path) = match authority_and_path.find('/') {
                Some(at) => authority_and_path.split_at(at),
                None => (authority_and_path, ""),
            };
            if host.is_empty() || host.eq_ignore_ascii_case("localhost") {
                path.to_owned()
            } else {
                format!("//{host}{path}")
            }
        }
        None => rest.to_owned(),
    };
    let path = percent_decode(&path);
    // `/C:/x` → `C:/x`.
    let bytes = path.as_bytes();
    if bytes.len() >= 3 && bytes[0] == b'/' && bytes[1].is_ascii_alphabetic() && bytes[2] == b':' {
        path[1..].to_owned()
    } else {
        path
    }
}

fn percent_decode(text: &str) -> String {
    String::from_utf8_lossy(&percent_decode_bytes(text)).into_owned()
}

/// `%XX` escapes (two ASCII hex digits, nothing else) to their bytes; any
/// other `%` stays as written.
fn percent_decode_bytes(text: &str) -> Vec<u8> {
    fn hex(digit: u8) -> Option<u8> {
        (digit as char).to_digit(16).map(|value| value as u8)
    }
    let raw = text.as_bytes();
    let mut out = Vec::with_capacity(raw.len());
    let mut at = 0;
    while at < raw.len() {
        if raw[at] == b'%'
            && let Some(&[high, low]) = raw.get(at + 1..at + 3)
            && let (Some(high), Some(low)) = (hex(high), hex(low))
        {
            out.push((high << 4) | low);
            at += 3;
        } else {
            out.push(raw[at]);
            at += 1;
        }
    }
    out
}

/// `data:[<mime>][;base64],<payload>` → (mime, bytes).
fn decode_data_uri(uri: &str) -> Option<(String, Vec<u8>)> {
    let rest = strip_prefix_ignore_case(uri, "data:")?;
    let (meta, payload) = rest.split_once(',')?;
    let mut params = meta.split(';');
    let mime = params
        .next()
        .map(|m| m.trim().to_ascii_lowercase())
        .filter(|m| !m.is_empty())
        .unwrap_or_else(|| "text/plain".into());
    let base64 = params.any(|p| p.trim().eq_ignore_ascii_case("base64"));
    let bytes = if base64 {
        let compact: String = percent_decode(payload)
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect();
        let compact = compact.trim_end_matches('=');
        STANDARD_NO_PAD
            .decode(compact)
            .or_else(|_| URL_SAFE_NO_PAD.decode(compact))
            .ok()?
    } else {
        // Raw bytes: a lossy UTF-8 pass would mangle `%89PNG…`.
        percent_decode_bytes(payload)
    };
    Some((mime, bytes))
}

fn url_path(url: &str) -> &str {
    let after_scheme = url.split_once("://").map_or(url, |(_, rest)| rest);
    let path = after_scheme.find('/').map_or("", |at| &after_scheme[at..]);
    path.split(['?', '#']).next().unwrap_or_default()
}

fn file_name(path: &str) -> String {
    path.rsplit(['/', '\\'])
        .find(|part| !part.is_empty())
        .map(percent_decode)
        .unwrap_or_else(|| "image".into())
}

/// `https://github.com/o/r/blob/ref/x.png` → `…/o/r/raw/ref/x.png`.
fn github_raw_url(url: &str) -> Option<String> {
    let rest = strip_prefix_ignore_case(url, "https://github.com/")?;
    let mut parts = rest.splitn(4, '/');
    let (owner, repo, kind, tail) = (parts.next()?, parts.next()?, parts.next()?, parts.next()?);
    (kind == "blob").then(|| format!("https://github.com/{owner}/{repo}/raw/{tail}"))
}

fn is_svg_path(path: &Path) -> bool {
    path.extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| ext.eq_ignore_ascii_case("svg") || ext.eq_ignore_ascii_case("svgz"))
}

/// Extensionless files may still be SVG (`diagram` written by a tool).
fn file_is_svg(path: &Path) -> bool {
    !crate::uploads::has_known_image_extension(path) && file_sniff(path) == Some("image/svg+xml")
}

fn file_sniffs_as_image(path: &Path) -> bool {
    file_sniff(path).is_some()
}

fn file_sniff(path: &Path) -> Option<&'static str> {
    use std::io::Read;
    let mut head = [0u8; 512];
    let mut file = std::fs::File::open(path).ok()?;
    let n = file.read(&mut head).unwrap_or(0);
    sniff_mime(&head[..n])
}

#[cfg(test)]
mod tests {
    use super::*;

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\nfixture";

    fn decode(chunk: &AttachmentChunk) -> Vec<u8> {
        BASE64.decode(&chunk.data).unwrap()
    }

    async fn read_all(refs: &ImageRefs, reference: &str, cwd: Option<&Path>) -> AttachmentChunk {
        let mut first = refs
            .read_chunk(reference, cwd.map(Path::to_path_buf), 0)
            .await
            .unwrap_or_else(|e| panic!("{reference}: {e}"));
        let mut bytes = decode(&first);
        while !first.done {
            let next = refs
                .read_chunk(reference, cwd.map(Path::to_path_buf), first.next_offset)
                .await
                .unwrap();
            bytes.extend(decode(&next));
            first.done = next.done;
            first.next_offset = next.next_offset;
        }
        first.data = BASE64.encode(bytes);
        first
    }

    fn fixture() -> (tempfile::TempDir, ImageRefs) {
        let dir = tempfile::tempdir().unwrap();
        let refs = ImageRefs::new(Uploads::from_root(&dir.path().join("uploads")));
        (dir, refs)
    }

    #[tokio::test]
    async fn local_reference_forms_all_resolve() {
        let (dir, refs) = fixture();
        let cwd = dir.path().join("project");
        std::fs::create_dir_all(cwd.join("shots")).unwrap();
        std::fs::write(cwd.join("shots").join("a b.png"), PNG).unwrap();
        std::fs::write(dir.path().join("up.png"), PNG).unwrap();
        let absolute = cwd.join("shots").join("a b.png");
        let absolute_text = absolute.to_string_lossy().replace('\\', "/");
        let file_uri = if absolute_text.starts_with('/') {
            format!("file://{}", absolute_text.replace(' ', "%20"))
        } else {
            format!("file:///{}", absolute_text.replace(' ', "%20"))
        };
        for reference in [
            absolute.to_string_lossy().into_owned(),
            format!("<{}>", absolute.to_string_lossy()),
            file_uri,
            "shots/a b.png".into(),
            "./shots/a%20b.png".into(),
            "shots/a b.png?raw=1".into(),
            "../project/shots/a b.png".into(),
            "roboco-file:shots/a%20b.png".into(),
            "../up.png".into(),
        ] {
            let chunk = read_all(&refs, &reference, Some(&cwd)).await;
            assert_eq!(decode(&chunk), PNG, "{reference}");
            assert_eq!(chunk.mime_type, "image/png", "{reference}");
        }
        // A relative path without a chat has nothing to resolve against.
        let error = refs.read_chunk("shots/a b.png", None, 0).await.unwrap_err();
        assert!(error.to_string().contains("without its chat"), "{error}");
    }

    #[test]
    fn home_and_file_uri_spellings() {
        let home = Path::new("/home/me");
        assert_eq!(
            local_candidates("~/shots/x.png", None, home),
            vec![home.join("shots/x.png")]
        );
        assert_eq!(file_uri_path("///C:/Users/me/a%20b.png"), "C:/Users/me/a b.png");
        assert_eq!(file_uri_path("///home/me/x.png"), "/home/me/x.png");
        assert_eq!(file_uri_path("//localhost/tmp/x.png"), "/tmp/x.png");
        assert_eq!(file_uri_path("//server/share/x.png"), "//server/share/x.png");
        assert!(is_absolute_reference("C:/x.png"));
        assert!(is_absolute_reference(r"C:\x.png"));
        assert!(is_absolute_reference(r"\\server\share\x.png"));
        assert!(!is_absolute_reference("C:x.png"));
        assert!(!is_absolute_reference("shots/x.png"));
    }

    #[test]
    fn percent_escapes_need_two_hex_digits() {
        assert_eq!(percent_decode("a%20b%2Fc%2fd"), "a b/c/d");
        // `from_str_radix` would read `+1` and `-1` as numbers.
        assert_eq!(percent_decode("50%+1 %-1 %g0 %2"), "50%+1 %-1 %g0 %2");
        assert_eq!(percent_decode_bytes("%89PNG%ff"), b"\x89PNG\xff");
    }

    #[tokio::test]
    async fn data_uri_payloads_decode_to_raw_bytes() {
        let (_dir, refs) = fixture();
        let chunk = read_all(&refs, "data:image/png,%89PNG%0D%0A%1A%0Afixture", None).await;
        assert_eq!(decode(&chunk), PNG);
        assert_eq!(chunk.mime_type, "image/png");
    }

    #[tokio::test]
    async fn non_images_are_errors() {
        let (dir, refs) = fixture();
        assert!(refs.read_chunk("data:text/plain,hello", None, 0).await.is_err());
        let notes = dir.path().join("notes.txt");
        std::fs::write(&notes, "not an image").unwrap();
        let error = refs
            .read_chunk(&notes.to_string_lossy(), None, 0)
            .await
            .unwrap_err();
        assert!(error.to_string().contains("is not an image"), "{error}");
        // An extensionless file with image bytes still loads.
        let shot = dir.path().join("shot");
        std::fs::write(&shot, PNG).unwrap();
        let chunk = read_all(&refs, &shot.to_string_lossy(), None).await;
        assert_eq!(decode(&chunk), PNG);
        assert_eq!(chunk.mime_type, "image/png");
    }

    #[tokio::test]
    async fn data_uris_decode_including_svg() {
        let (_dir, refs) = fixture();
        let uri = format!("data:image/png;base64,{}", BASE64.encode(PNG));
        assert_eq!(decode(&read_all(&refs, &uri, None).await), PNG);
        let svg = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='20'><rect width='10' height='20' fill='red'/></svg>";
        let chunk = read_all(&refs, svg, None).await;
        assert_eq!(chunk.mime_type, "image/png");
        let png = decode(&chunk);
        assert!(png.starts_with(b"\x89PNG"));
        // 10×20 → long side doubled to the 512 floor, aspect kept.
        assert_eq!(png_size(&png), (256, 512));
    }

    #[tokio::test]
    async fn svg_files_rasterize_to_png() {
        let (dir, refs) = fixture();
        let path = dir.path().join("diagram.svg");
        std::fs::write(
            &path,
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 300'><circle cx='200' cy='150' r='100'/></svg>",
        )
        .unwrap();
        let chunk = read_all(&refs, &path.to_string_lossy(), None).await;
        assert_eq!(chunk.mime_type, "image/png");
        assert_eq!(chunk.name, "diagram.png");
        assert_eq!(png_size(&decode(&chunk)), (800, 600));
    }

    #[tokio::test]
    async fn urls_are_fetched_by_the_engine_including_localhost() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let big: Vec<u8> = PNG.iter().copied().chain((0..100_000u32).map(|i| i as u8)).collect();
        let body = big.clone();
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let server_hits = hits.clone();
        tokio::spawn(async move {
            loop {
                let (mut socket, _) = listener.accept().await.unwrap();
                let body = body.clone();
                let hits = server_hits.clone();
                tokio::spawn(async move {
                    let mut request = vec![0u8; 4096];
                    let n = socket.read(&mut request).await.unwrap();
                    let request = String::from_utf8_lossy(&request[..n]).into_owned();
                    hits.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    let response: Vec<u8> = if request.starts_with("GET /moved ") {
                        b"HTTP/1.1 302 Found\r\nLocation: /shot\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec()
                    } else if request.starts_with("GET /shot ") {
                        assert!(request.contains("Roboco/"), "user agent: {request}");
                        let mut response = format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        )
                        .into_bytes();
                        response.extend(body);
                        response
                    } else {
                        b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec()
                    };
                    socket.write_all(&response).await.unwrap();
                });
            }
        });
        let (_dir, refs) = fixture();
        let url = format!("http://{address}/moved");
        let chunk = read_all(&refs, &url, None).await;
        assert_eq!(decode(&chunk), big);
        assert_eq!(chunk.mime_type, "image/png");
        // Three chunks, one redirect + one fetch: the follow-ups hit the cache.
        assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 2);

        // A dead URL fails, and the failure is remembered briefly.
        let dead = format!("http://{address}/missing.png");
        assert!(refs.read_chunk(&dead, None, 0).await.is_err());
        assert!(refs.read_chunk(&dead, None, 0).await.is_err());
        assert_eq!(hits.load(std::sync::atomic::Ordering::SeqCst), 3);
    }

    #[test]
    fn github_blob_pages_map_to_raw_files() {
        assert_eq!(
            github_raw_url("https://github.com/o/r/blob/main/docs/x.png").as_deref(),
            Some("https://github.com/o/r/raw/main/docs/x.png")
        );
        assert_eq!(github_raw_url("https://github.com/o/r/raw/main/x.png"), None);
        assert_eq!(file_name(url_path("https://h/a/b%20c.png?x=1")), "b c.png");
    }

    fn png_size(png: &[u8]) -> (u32, u32) {
        let be = |at: usize| u32::from_be_bytes(png[at..at + 4].try_into().unwrap());
        (be(16), be(20))
    }
}
