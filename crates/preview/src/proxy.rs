//! HTTP/1 reverse proxy. Hyper owns HTTP framing; upgrades carry their original
//! bytes through the same multiplexed stream, including WebSocket close frames.
//!
//! The header hygiene (hop-by-hop stripping, Host/Origin preservation,
//! localhost redirect rewrites) lives in `roboco-preview-relay`'s `http`
//! module (factored in ticket 04) so the engine's 7331 proxy and the
//! client's 7332 proxy share one implementation.
use crate::{
    catalog::Catalog,
    mux::{Mux, Stream},
    peer::Peers,
};
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::{Request, Response, StatusCode, body::Incoming, header};
use hyper_util::rt::TokioIo;
use roboco_preview_relay::http as relay_http;
use roboco_preview_relay::http::{AbortOnDrop, Body, GuardedBody};
use std::{convert::Infallible, sync::Arc};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub struct Router {
    pub catalog: Catalog,
    pub local: Mux,
    pub peers: Peers,
}
impl Router {
    async fn open(&self, device: &str, service: &str, websocket: bool) -> anyhow::Result<Stream> {
        if device == self.catalog.device_id() {
            self.local.open(service, websocket).await
        } else {
            self.peers.open(device, service, websocket).await
        }
    }
    /// WebKit on older macOS releases delegates `.localhost` DNS to the OS.
    /// Its per-domain CONNECT proxy reaches this same loopback HTTP listener
    /// without a hosts-file entry. Targets are catalog names at our port only.
    async fn connect_loopback(
        &self,
        mut request: Request<Incoming>,
    ) -> anyhow::Result<Response<Body>> {
        let authority = request
            .uri()
            .authority()
            .ok_or_else(|| anyhow::anyhow!("invalid preview CONNECT"))?;
        let port = self.catalog.snapshot().proxy_port;
        anyhow::ensure!(
            authority.port_u16() == Some(port)
                && self
                    .catalog
                    .by_hostname(&authority.host().to_ascii_lowercase())
                    .is_some(),
            "unknown preview CONNECT target"
        );
        let permit = relay_http::tunnel_slot()?;
        let lifecycle = self.local.clone();
        let mut socket =
            tokio::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).await?;
        let upgrade = hyper::upgrade::on(&mut request);
        tokio::spawn(async move {
            let _permit = permit;
            tokio::select! { _ = lifecycle.closed() => {}, _ = async {
                if let Ok(browser) = upgrade.await {
                    let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(browser), &mut socket).await;
                }
            } => {} }
        });
        Ok(Response::new(
            Full::new(Bytes::new())
                .map_err(|never: Infallible| match never {})
                .boxed_unsync(),
        ))
    }
    async fn request(&self, mut request: Request<Incoming>) -> anyhow::Result<Response<Body>> {
        if request.method() == hyper::Method::CONNECT {
            return self.connect_loopback(request).await;
        }
        anyhow::ensure!(
            request.uri().scheme().is_none(),
            "only origin-form HTTP requests are supported"
        );
        let host = request
            .headers()
            .get(header::HOST)
            .and_then(|h| h.to_str().ok())
            .ok_or_else(|| anyhow::anyhow!("missing preview hostname"))?
            .to_ascii_lowercase();
        let authority: hyper::http::uri::Authority = host.parse()?;
        anyhow::ensure!(
            authority.port_u16().unwrap_or(80) == self.catalog.snapshot().proxy_port,
            "incorrect preview proxy port"
        );
        let service = self.catalog.by_hostname(authority.host()).ok_or_else(|| {
            anyhow::anyhow!(
                "This preview is not running. Start the project's dev server and try again."
            )
        })?;
        let upgrade = request
            .headers()
            .get(header::UPGRADE)
            .is_some_and(|v| v.as_bytes().eq_ignore_ascii_case(b"websocket"))
            && relay_http::connection_has(request.headers(), "upgrade");
        let tunnel_permit = upgrade.then(relay_http::tunnel_slot).transpose()?;
        let browser_upgrade = upgrade.then(|| hyper::upgrade::on(&mut request));
        let upstream_host = format!("localhost:{}", service.port);
        let preview_origin = format!("http://{host}");
        // Preserve the browser's origin and authority together. In particular,
        // Next.js Server Actions compare Origin with X-Forwarded-Host; rewriting
        // only Origin to the ephemeral backend would reject valid submissions.
        // Never pass client-supplied proxy credentials to a project server.
        relay_http::forward_request_headers(request.headers_mut(), &host, upgrade)?;
        let stream = self.open(&service.device_id, &service.id, upgrade).await?;
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake(TokioIo::new(stream)).await?;
        let connection = tokio::spawn(async move {
            let _ = connection.with_upgrades().await;
        });
        // Aborting a disconnected browser's pending request tears down its
        // upstream mux stream too, even when no response headers arrive.
        let guard = AbortOnDrop(Some(connection.abort_handle()));
        let mut response = sender.send_request(request).await?;
        if response.status() == StatusCode::SWITCHING_PROTOCOLS {
            anyhow::ensure!(upgrade, "unsolicited server upgrade");
            let server_upgrade = hyper::upgrade::on(&mut response);
            let browser_upgrade = browser_upgrade.unwrap();
            let lifecycle = self.local.clone();
            tokio::spawn(async move {
                let _guard = guard;
                let _permit = tunnel_permit;
                tokio::select! { _ = lifecycle.closed() => {}, _ = async {
                    if let Ok((browser, server)) = tokio::try_join!(browser_upgrade, server_upgrade) {
                        let _ = tokio::io::copy_bidirectional(&mut TokioIo::new(browser), &mut TokioIo::new(server)).await;
                    }
                } => {} }
            });
        } else {
            // The response body's guard aborts the connection on cancellation;
            // it is retained until the last streamed byte is consumed.
            let (mut parts, body) = response.into_parts();
            relay_http::forward_response_headers(&mut parts.headers, &upstream_host, &preview_origin);
            return Ok(Response::from_parts(
                parts,
                GuardedBody { body, guard }.boxed_unsync(),
            ));
        }
        relay_http::strip_hop_headers(response.headers_mut(), true);
        Ok(response.map(BodyExt::boxed_unsync))
    }
}
fn error(message: String) -> Response<Body> {
    relay_http::error(message)
}
/// Bind both loopback families: `.localhost` commonly resolves to ::1 first.
/// Fail explicitly if another application owns the stable port.
pub async fn serve(
    router: Router,
    port: u16,
    stop: CancellationToken,
) -> anyhow::Result<(u16, Vec<tokio::task::JoinHandle<()>>)> {
    let v4 = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port)).await?;
    let port = v4.local_addr()?.port();
    let mut sockets = vec![v4];
    match TcpListener::bind((std::net::Ipv6Addr::LOCALHOST, port)).await {
        Ok(v6) => sockets.push(v6),
        Err(error)
            if matches!(
                error.raw_os_error(),
                Some(libc::EAFNOSUPPORT | libc::EPROTONOSUPPORT | libc::EADDRNOTAVAIL)
            ) => {}
        Err(error) => return Err(error.into()),
    }
    let slots = Arc::new(tokio::sync::Semaphore::new(128));
    let mut listeners = Vec::new();
    for listener in sockets {
        let router = router.clone();
        let stop = stop.clone();
        let slots = slots.clone();
        listeners.push(tokio::spawn(async move {
            loop {
                let accepted = tokio::select! { _ = stop.cancelled() => break, result = listener.accept() => result };
                let Ok((socket, _)) = accepted else { break; };
                let Ok(permit) = slots.clone().try_acquire_owned() else { drop(socket); continue; };
                let router = router.clone(); let stop = stop.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let service = hyper::service::service_fn(move |request| {
                        let router = router.clone();
                        async move { Ok::<_, Infallible>(router.request(request).await.unwrap_or_else(|e| error(e.to_string()))) }
                    });
                    let mut builder = hyper::server::conn::http1::Builder::new();
                    builder.timer(hyper_util::rt::TokioTimer::new()).header_read_timeout(std::time::Duration::from_secs(15));
                    tokio::select! { _ = stop.cancelled() => {}, _ = builder.serve_connection(TokioIo::new(socket), service).with_upgrades() => {} }
                });
            }
        }));
    }
    Ok((port, listeners))
}
