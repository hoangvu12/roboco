//! The requesting side of a relayed sign-in (wave-10 36, upstream #966's
//! `relay_login_callback`): a computer runs a loopback OAuth login (Codex's
//! `localhost:1455`, Roboco's engine-served Claude login, Antigravity's
//! listener), but the browser that finishes it runs elsewhere — a phone, or
//! a desktop managing another fleet engine. This device listens on the
//! login's port on its **own** loopback, catches the browser's redirect and
//! hands its request target to `relay` (the caller's
//! `RelayAgentLoginCallback` call on the computer), then answers the browser
//! the way the computer's listener answered.
//!
//! Shared by the Android core (`roboco-mobile-client`) and the desktop, so
//! both devices answer the browser identically. Reachability only (ADR 0011):
//! nothing but the one callback crosses, over the client's own paired link.

use std::future::Future;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use roboco_proto::RelayedLoginCallback;
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

/// How long the listener waits for the browser (upstream's ten minutes).
pub const LOGIN_RELAY_WAIT: Duration = Duration::from_secs(10 * 60);

/// One relay: the caller's call to the computer with the caught path.
pub type RelayFn = Box<
    dyn Fn(String) -> Pin<Box<dyn Future<Output = Result<RelayedLoginCallback, String>> + Send>>
        + Send
        + Sync,
>;

/// Connections served at once: a browser's speculative preconnects must not
/// hold the real callback back, nor may a stray client pile them up.
const MAX_CONNECTIONS: usize = 8;

/// A listening relay. Dropping it stops listening (the login ended, was
/// cancelled, or the page went away).
pub struct LoginRelay {
    stop: CancellationToken,
    port: u16,
}

impl LoginRelay {
    /// The loopback port it listens on (the login's callback port).
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Whether it still listens (it stops after a relayed success or the
    /// wait).
    pub fn is_listening(&self) -> bool {
        !self.stop.is_cancelled()
    }
}

impl Drop for LoginRelay {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

/// The message when this device can't listen on the login's port.
pub fn port_busy_message(port: u16, device: &str) -> String {
    format!("Port {port} is in use on this {device}, so the sign-in can't come back to it.")
}

/// Bind `port` on both loopback families (like the CLIs' redirect targets
/// and `CallbackForwarder`) on `runtime` and relay the browser's callback
/// through `relay`. `Err` when the port can't be bound
/// ([`port_busy_message`] with `device` — "phone", "computer"). Each
/// connection is served on its own, so a preconnect never blocks the real
/// callback. A success (2xx/3xx from the computer) answers the browser with
/// a "signed in" page and stops; a refusal answers 502 and keeps listening
/// (the user can retry the page); `/favicon…` gets a 404.
pub async fn listen(
    runtime: &tokio::runtime::Handle,
    port: u16,
    device: &str,
    relay: RelayFn,
) -> Result<LoginRelay, String> {
    let listeners = runtime
        .spawn(async move {
            let v4 = TcpListener::bind((Ipv4Addr::LOCALHOST, port)).await?;
            let mut listeners = vec![v4];
            match TcpListener::bind((Ipv6Addr::LOCALHOST, port)).await {
                Ok(v6) => listeners.push(v6),
                Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => return Err(error),
                // No IPv6 loopback on this device: `localhost` can't resolve there.
                Err(_) => {}
            }
            Ok::<_, std::io::Error>(listeners)
        })
        .await
        .map_err(|e| e.to_string())?
        .map_err(|_| port_busy_message(port, device))?;
    let stop = CancellationToken::new();
    let relay = Arc::new(relay);
    let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    for listener in listeners {
        let (stop, relay, slots) = (stop.clone(), relay.clone(), slots.clone());
        runtime.spawn(async move {
            let serve = async {
                loop {
                    let Ok((socket, _)) = listener.accept().await else {
                        continue;
                    };
                    let Ok(permit) = slots.clone().try_acquire_owned() else {
                        continue;
                    };
                    let (stop, relay) = (stop.clone(), relay.clone());
                    tokio::spawn(async move {
                        let _permit = permit;
                        tokio::select! {
                            _ = stop.cancelled() => {}
                            signed_in = serve_one(socket, &relay) => if signed_in { stop.cancel() },
                        }
                    });
                }
            };
            tokio::select! {
                _ = stop.cancelled() => {}
                _ = tokio::time::sleep(LOGIN_RELAY_WAIT) => stop.cancel(),
                _ = serve => {}
            }
        });
    }
    Ok(LoginRelay { stop, port })
}

/// One browser connection; `true` once the computer accepted the callback
/// (the browser was told it's signed in).
async fn serve_one(mut socket: TcpStream, relay: &RelayFn) -> bool {
    let Some(path) = read_request_target(&mut socket).await else {
        return false;
    };
    if path.starts_with("/favicon") {
        let _ = socket.write_all(&http_response("404 Not Found", "")).await;
        return false;
    }
    let status = relay(path).await.map(|reply| reply.status).unwrap_or(0);
    if (200..400).contains(&status) {
        let _ = socket.write_all(&http_response("200 OK", SIGNED_IN_PAGE)).await;
        return true;
    }
    let _ = socket.write_all(&http_response("502 Bad Gateway", REFUSED_PAGE)).await;
    false
}

const SIGNED_IN_PAGE: &str = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">\
<title>Signed in</title><body style=\"font-family:system-ui,sans-serif;padding:2em\">\
<h2>Signed in</h2><p>You can close this page and go back to Roboco.</p>";

const REFUSED_PAGE: &str = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">\
<title>Sign-in not accepted</title><body style=\"font-family:system-ui,sans-serif;padding:2em\">\
<h2>The computer didn't accept the sign-in</h2><p>Go back to Roboco and try again.</p>";

fn http_response(status: &str, body: &str) -> Vec<u8> {
    format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n{body}",
        body.len()
    )
    .into_bytes()
}

/// The request target of the browser's `GET` (`/path?query`), read off the
/// request line; `None` for anything else (or a slow/oversized head).
async fn read_request_target(socket: &mut TcpStream) -> Option<String> {
    let mut head = Vec::new();
    let mut buf = [0u8; 2048];
    let read = async {
        while head.len() < 16 * 1024 {
            let n = socket.read(&mut buf).await.ok()?;
            if n == 0 {
                break;
            }
            head.extend_from_slice(&buf[..n]);
            if head.windows(2).any(|w| w == b"\r\n") {
                break;
            }
        }
        Some(())
    };
    tokio::time::timeout(Duration::from_secs(10), read).await.ok()??;
    let text = String::from_utf8_lossy(&head);
    let line = text.lines().next()?;
    let mut parts = line.split_whitespace();
    let method = parts.next()?;
    let target = parts.next()?;
    (method == "GET" && target.starts_with('/')).then(|| target.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    async fn browser_get(port: u16, target: &str) -> String {
        let mut socket = tokio::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port))
            .await
            .unwrap();
        socket
            .write_all(format!("GET {target} HTTP/1.1\r\nHost: localhost:{port}\r\n\r\n").as_bytes())
            .await
            .unwrap();
        let mut reply = Vec::new();
        socket.read_to_end(&mut reply).await.unwrap();
        String::from_utf8_lossy(&reply).into_owned()
    }

    fn free_port() -> u16 {
        std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_caught_callback_is_relayed_and_the_browser_told() {
        let seen = Arc::new(Mutex::new(Vec::<String>::new()));
        let answers = Arc::new(Mutex::new(vec![502u16, 302]));
        let (seen_in, answers_in) = (seen.clone(), answers.clone());
        let port = free_port();
        let relay = listen(
            &tokio::runtime::Handle::current(),
            port,
            "phone",
            Box::new(move |path| {
                seen_in.lock().unwrap().push(path);
                let status = answers_in.lock().unwrap().remove(0);
                Box::pin(async move { Ok(RelayedLoginCallback { status }) })
            }),
        )
        .await
        .unwrap();
        assert_eq!(relay.port(), port);
        assert!(browser_get(port, "/favicon.ico").await.starts_with("HTTP/1.1 404"));
        // A refusal keeps listening; the retry succeeds and the relay stops.
        assert!(browser_get(port, "/cb?code=a").await.starts_with("HTTP/1.1 502"));
        let ok = browser_get(port, "/cb?code=b&state=s").await;
        assert!(ok.starts_with("HTTP/1.1 200"), "{ok}");
        assert!(ok.contains("Signed in"));
        assert_eq!(*seen.lock().unwrap(), ["/cb?code=a", "/cb?code=b&state=s"]);
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!relay.is_listening());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_busy_port_says_so() {
        let held = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = held.local_addr().unwrap().port();
        let err = listen(
            &tokio::runtime::Handle::current(),
            port,
            "phone",
            Box::new(|_| Box::pin(async { Err("unused".to_owned()) })),
        )
        .await
        .err()
        .expect("the port is taken");
        assert_eq!(err, format!("Port {port} is in use on this phone, so the sign-in can't come back to it."));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn dropping_the_relay_stops_listening() {
        let port = free_port();
        let relay = listen(
            &tokio::runtime::Handle::current(),
            port,
            "phone",
            Box::new(|_| Box::pin(async { Err("unused".to_owned()) })),
        )
        .await
        .unwrap();
        drop(relay);
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(tokio::net::TcpListener::bind(("127.0.0.1", port)).await.is_ok(), "the port is free again");
    }
}
