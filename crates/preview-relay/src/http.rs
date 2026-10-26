//! HTTP relay hygiene shared by the engine's 7331 proxy and the client's
//! 7332 proxy: hop-by-hop header stripping, Host/Origin preservation, the
//! `X-Forwarded-*` set, localhost redirect rewrites, the bounded tunnel
//! budget, and the abort-on-drop body guard. One implementation, two proxies
//! (ticket 04 factored this out of `roboco-preview`'s `proxy.rs`).
use bytes::Bytes;
use http_body_util::{BodyExt as _, combinators::UnsyncBoxBody};
use hyper::{
    Response, StatusCode,
    header::{self, HeaderMap, HeaderValue},
};
use std::convert::Infallible;

pub type Body = UnsyncBoxBody<Bytes, hyper::Error>;

/// The shared upgrade-tunnel budget: at most 128 concurrent CONNECT/WebSocket
/// tunnels across this process's preview proxies. Bounded so a browser
/// opening hundreds of asset connections cannot hold streams the mux's own
/// 64-stream-per-connection limit would otherwise queue behind.
pub fn tunnel_slot() -> anyhow::Result<tokio::sync::SemaphorePermit<'static>> {
    static TUNNELS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(128);
    Ok(TUNNELS.try_acquire()?)
}

pub fn connection_has(headers: &HeaderMap, token: &str) -> bool {
    headers
        .get_all(header::CONNECTION)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .any(|v| v.trim().eq_ignore_ascii_case(token))
}

pub fn strip_hop_headers(headers: &mut HeaderMap, upgrade: bool) {
    let nominated: Vec<String> = headers
        .get_all(header::CONNECTION)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(|v| v.trim().to_ascii_lowercase())
        .collect();
    for name in nominated {
        if !(upgrade && name == "upgrade") {
            headers.remove(name);
        }
    }
    for name in [
        "connection",
        "keep-alive",
        "proxy-connection",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
    ] {
        headers.remove(name);
    }
    if upgrade {
        headers.insert(header::CONNECTION, HeaderValue::from_static("upgrade"));
    } else {
        headers.remove(header::UPGRADE);
    }
}

/// Prepare a browser request for the project server: strip hop-by-hop
/// headers, then preserve the browser's origin and authority together. In
/// particular, Next.js Server Actions compare Origin with X-Forwarded-Host;
/// rewriting only Origin to the ephemeral backend would reject valid
/// submissions. Never pass client-supplied proxy credentials onward.
pub fn forward_request_headers(headers: &mut HeaderMap, host: &str, upgrade: bool) -> anyhow::Result<()> {
    strip_hop_headers(headers, upgrade);
    headers.insert(header::HOST, HeaderValue::from_str(host)?);
    headers.insert("x-forwarded-host", HeaderValue::from_str(host)?);
    headers.insert("x-forwarded-proto", HeaderValue::from_static("http"));
    headers.remove(header::PROXY_AUTHORIZATION);
    Ok(())
}

/// Prepare the project server's response for the browser, rewriting
/// absolute `localhost` redirects back to the preview origin the browser
/// addressed.
pub fn forward_response_headers(headers: &mut HeaderMap, upstream: &str, preview: &str) {
    strip_hop_headers(headers, false);
    rewrite_location(headers, upstream, preview);
}

pub fn rewrite_location(headers: &mut HeaderMap, upstream: &str, preview: &str) {
    if let Some(value) = headers.get(header::LOCATION).and_then(|v| v.to_str().ok()) {
        let prefix = format!("http://{upstream}");
        if let Some(path) = value
            .strip_prefix(&prefix)
            .filter(|path| {
                path.is_empty()
                    || path.starts_with('/')
                    || path.starts_with('?')
                    || path.starts_with('#')
            })
            && let Ok(value) = HeaderValue::from_str(&format!("{preview}{path}"))
        {
            headers.insert(header::LOCATION, value);
        }
    }
}

pub fn error(message: String) -> Response<Body> {
    let mut response = Response::new(
        http_body_util::Full::new(Bytes::from(message))
            .map_err(|never: Infallible| match never {})
            .boxed_unsync(),
    );
    *response.status_mut() = StatusCode::BAD_GATEWAY;
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// Aborting a disconnected browser's pending request tears down its upstream
/// connection too, even when no response headers arrive.
pub struct AbortOnDrop(pub Option<tokio::task::AbortHandle>);
impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        if let Some(task) = self.0.take() {
            task.abort();
        }
    }
}

/// The response body's guard aborts the connection on cancellation; it is
/// retained until the last streamed byte is consumed.
pub struct GuardedBody {
    pub body: hyper::body::Incoming,
    pub guard: AbortOnDrop,
}
impl hyper::body::Body for GuardedBody {
    type Data = Bytes;
    type Error = hyper::Error;
    fn poll_frame(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<hyper::body::Frame<Bytes>, Self::Error>>> {
        std::pin::Pin::new(&mut self.body).poll_frame(cx)
    }
    fn is_end_stream(&self) -> bool {
        self.body.is_end_stream()
    }
    fn size_hint(&self) -> hyper::body::SizeHint {
        self.body.size_hint()
    }
}
