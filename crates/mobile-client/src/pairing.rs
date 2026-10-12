//! Pairing: the URL grammar, code redemption and the WebSocket endpoint.
//!
//! An engine mints `http(s)://host[:port][/prefix]/pair#token=<base64url>`.
//! The token is single-use (default TTL 300 s, max 3600 s). The client
//! POSTs `{base}/pairing/redeem` with `Authorization: Bearer <token>` and
//! body `{"label":"<name>"}`; the reply grants a `credential` that never
//! expires. See `crates/engine/src/pairing.rs` and
//! `crates/engine/src/listener.rs` for the engine side.
//!
//! This module is the Rust twin of `ec:pairing.ts` and the desktop's
//! `crates/engine-client/src/registry.rs` parser (identical rules).

use roboco_proto::PairedSession;
use serde::Deserialize;
use std::time::Duration;

/// The minimum a pairing link must carry, for the confirm UI. The token is
/// deliberately not part of this value: the FFI layer shows it to Kotlin,
/// and a confirm screen must never receive the secret.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingTarget {
    /// `http(s)://host[:port][/prefix]`, no trailing slash, no fragment.
    pub base_url: String,
    /// `host[:port]`, for the confirm UI.
    pub host: String,
}

/// Why a pairing attempt failed. Ticket 18 maps each variant to the web's
/// copy (`web:lib/pairing-errors.ts`).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PairError {
    #[error("invalid pairing URL: {message}")]
    InvalidUrl { message: String },
    /// `roboco-tailcat:` invites are out of scope on mobile (spec D9).
    #[error("tailcat pairing is not supported on this device")]
    TailcatUnsupported,
    /// The engine answered 401: the code was expired, already used or
    /// unknown.
    #[error("pairing link refused")]
    Refused,
    /// The engine could not be reached at all (connect error, timeout).
    #[error("could not reach the engine: {message}")]
    Unreachable { message: String },
    #[error("pairing failed: {message}")]
    Failed { message: String },
    #[error("storage error: {message}")]
    Storage { message: String },
}

impl PairError {
    pub(crate) fn invalid(message: impl Into<String>) -> Self {
        PairError::InvalidUrl {
            message: message.into(),
        }
    }
}

/// A parsed pairing link, token included (crate-internal only).
#[derive(Debug, Clone, PartialEq, Eq)]
struct ParsedPairing {
    base_url: String,
    host: String,
    code: String,
}

/// Split a pairing link. Same grammar as the desktop parser
/// (`roboco-engine-client`'s `parse_pairing_url`) and `ec:pairing.ts`:
/// http(s) scheme, a host, no userinfo, no query, a fragment of exactly
/// `token=` + `[A-Za-z0-9_-]+`, and a path ending in `/pair`.
fn parse(input: &str) -> Result<ParsedPairing, PairError> {
    let trimmed = input.trim();
    if trimmed.starts_with("roboco-tailcat:") {
        return Err(PairError::TailcatUnsupported);
    }
    let mut url = reqwest::Url::parse(trimmed).map_err(|_| PairError::invalid("not a URL"))?;
    let host = match url.host_str() {
        Some(host) => host.to_owned(),
        None => return Err(PairError::invalid("pairing URL has no host")),
    };
    if !matches!(url.scheme(), "http" | "https") {
        return Err(PairError::invalid(
            "pairing URL must use HTTP or HTTPS",
        ));
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(PairError::invalid(
            "pairing URL must not carry user information",
        ));
    }
    if url.query().is_some() {
        return Err(PairError::invalid("pairing URL must not have a query"));
    }
    let code = match url.fragment().and_then(|f| f.strip_prefix("token=")) {
        Some(code)
            if !code.is_empty()
                && code
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') =>
        {
            code.to_owned()
        }
        _ => {
            return Err(PairError::invalid(
                "pairing URL is missing its fragment credential",
            ))
        }
    };
    let base_path = url
        .path()
        .strip_suffix("/pair")
        .ok_or_else(|| PairError::invalid("pairing URL path must end in /pair"))?
        .to_owned();
    url.set_path(&base_path);
    url.set_fragment(None);
    let base_url = url.as_str().trim_end_matches('/').to_owned();
    // `host[:port]`: the confirm UI shows what the user typed, minus the
    // scheme noise. `Url::port` is `Some` only when the port was written.
    let host = match url.port() {
        Some(port) => format!("{host}:{port}"),
        None => host.to_owned(),
    };
    Ok(ParsedPairing {
        base_url,
        host,
        code,
    })
}

/// Parse a pairing link for the confirm UI. The token never leaves this
/// crate through this function.
pub fn parse_pairing_url(input: &str) -> Result<PairingTarget, PairError> {
    parse(input).map(|parsed| PairingTarget {
        base_url: parsed.base_url,
        host: parsed.host,
    })
}

/// The redeem label: `config.device_name` (e.g. `Build.MODEL`), trimmed,
/// control characters removed, truncated to 256 bytes on a char boundary —
/// or `"Android"` when nothing usable is left. The engine enforces the same
/// limits (`crates/engine/src/listener.rs`: label ≤256 bytes, no control
/// characters, body ≤4096 bytes).
pub fn sanitize_label(device_name: &str) -> String {
    let trimmed: String = device_name
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim()
        .to_owned();
    if trimmed.is_empty() {
        return "Android".into();
    }
    let mut label = trimmed;
    while label.len() > 256 && !label.is_empty() {
        // Cut on a char boundary, never mid-UTF-8.
        label = label.chars().take(label.chars().count() - 1).collect();
    }
    label
}

/// The `POST {base}/pairing/redeem` reply.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Grant {
    session: PairedSession,
    credential: String,
}

/// The engine's WebSocket endpoint: scheme swapped (`http→ws`,
/// `https→wss`), same host and port, path `/` — the engine upgrades only
/// `/` (`crates/engine/src/listener.rs`), and only tailcat relay engines
/// (out of scope here, spec D9) pair on any other path
/// (`web:lib/engine-store.ts::engineWsEndpoint`).
pub fn websocket_endpoint(base_url: &str) -> String {
    let Ok(mut url) = reqwest::Url::parse(base_url) else {
        // Hand-in records can carry anything; dialing will fail with a
        // transport error, so degrade to a plainly-invalid endpoint.
        return format!("ws://{base_url}/");
    };
    let scheme = match url.scheme() {
        "https" => "wss",
        _ => "ws",
    };
    let _ = url.set_scheme(scheme);
    url.set_path("/");
    url.to_string()
}

/// Redeem a one-time pairing code against an engine.
///
/// Redirects are disabled and the whole request is bounded at 15 s (the
/// desktop's `engine_registry.rs` budget). A 401 means the code was
/// expired, used or unknown → [`PairError::Refused`]; other non-2xx
/// statuses and unreadable bodies → [`PairError::Failed`]; connect errors
/// and timeouts → [`PairError::Unreachable`].
async fn redeem(base_url: &str, code: &str, label: &str) -> Result<Grant, PairError> {
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| PairError::Failed {
            message: error.to_string(),
        })?;
    let response = http
        .post(format!("{base_url}/pairing/redeem"))
        .bearer_auth(code)
        .json(&serde_json::json!({ "label": label }))
        .send()
        .await
        .map_err(|error| PairError::Unreachable {
            message: error.to_string(),
        })?;
    let status = response.status();
    if status.as_u16() == 401 {
        return Err(PairError::Refused);
    }
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        return Err(PairError::Failed {
            message: format!("{status} {body}"),
        });
    }
    let bytes = response.bytes().await.map_err(|_| PairError::Failed {
        message: "Invalid pairing response".into(),
    })?;
    serde_json::from_slice(&bytes).map_err(|_| PairError::Failed {
        message: "Invalid pairing response".into(),
    })
}

/// One completed pairing: the stored record's raw material.
#[derive(Debug, Clone)]
pub(crate) struct Redeemed {
    pub session: PairedSession,
    pub credential: String,
    pub base_url: String,
    pub endpoint: String,
}

/// Parse, redeem, and compute the endpoint. The caller dials.
pub(crate) async fn redeem_pairing(
    pairing_url: &str,
    device_name: &str,
) -> Result<Redeemed, PairError> {
    let parsed = parse(pairing_url)?;
    let label = sanitize_label(device_name);
    let grant = redeem(&parsed.base_url, &parsed.code, &label).await?;
    let endpoint = websocket_endpoint(&parsed.base_url);
    Ok(Redeemed {
        session: grant.session,
        credential: grant.credential,
        base_url: parsed.base_url,
        endpoint,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_table_rejects_the_documented_bad_urls() {
        // A query.
        assert!(matches!(
            parse_pairing_url("http://h:1/pair?x=1#token=abc"),
            Err(PairError::InvalidUrl { .. })
        ));
        // Userinfo.
        assert!(matches!(
            parse_pairing_url("http://u:p@h/pair#token=abc"),
            Err(PairError::InvalidUrl { .. })
        ));
        // Missing #token=.
        assert!(matches!(
            parse_pairing_url("http://h/pair"),
            Err(PairError::InvalidUrl { .. })
        ));
        // Illegal token character.
        assert!(matches!(
            parse_pairing_url("http://h/pair#token=ab!c"),
            Err(PairError::InvalidUrl { .. })
        ));
        // Path not ending in /pair.
        assert!(matches!(
            parse_pairing_url("http://h/other#token=abc"),
            Err(PairError::InvalidUrl { .. })
        ));
        // Non-HTTP scheme.
        assert!(matches!(
            parse_pairing_url("ftp://h/pair#token=abc"),
            Err(PairError::InvalidUrl { .. })
        ));
    }

    #[test]
    fn tailcat_invites_are_refused_before_url_parsing() {
        assert_eq!(
            parse_pairing_url("roboco-tailcat:invite-xyz"),
            Err(PairError::TailcatUnsupported)
        );
    }

    #[test]
    fn sub_path_pairs_parse_to_their_base_like_the_desktop_parser() {
        let target = parse_pairing_url("https://h/sub/pair#token=x").unwrap();
        assert_eq!(target.base_url, "https://h/sub");
        assert_eq!(target.host, "h");
        // The token never crosses into the FFI-facing value.
        let raw = format!("{:?}", target);
        assert!(!raw.contains("token"));
        // Whitespace is trimmed, and a canonical link round-trips.
        let target = parse_pairing_url("  http://h:8123/pair#token=ab-c_d  ").unwrap();
        assert_eq!(target.base_url, "http://h:8123");
        assert_eq!(target.host, "h:8123");
    }

    #[test]
    fn endpoints_swap_the_scheme_and_reset_the_path() {
        assert_eq!(
            websocket_endpoint("http://127.0.0.1:53712"),
            "ws://127.0.0.1:53712/"
        );
        assert_eq!(websocket_endpoint("https://h/sub"), "wss://h/");
        // A written default port is normalized away (same host:port).
        assert_eq!(websocket_endpoint("https://h:443"), "wss://h/");
    }

    #[test]
    fn labels_are_trimmed_deratted_and_bounded() {
        assert_eq!(sanitize_label("  Pixel 9 \u{7}"), "Pixel 9");
        assert_eq!(sanitize_label("\u{1}\u{2}"), "Android");
        assert_eq!(sanitize_label("  "), "Android");
        let long = "é".repeat(200); // 400 bytes.
        let label = sanitize_label(&long);
        assert!(label.len() <= 256);
        assert!(label.chars().all(|c| c == 'é'));
    }

    #[tokio::test]
    async fn redeem_maps_statuses_and_connect_errors() {
        // Nothing listens: unreachable, not "refused".
        match redeem("http://127.0.0.1:1", "code", "Pixel").await {
            Err(PairError::Unreachable { .. }) => {}
            other => panic!("closed port must be Unreachable, got {other:?}"),
        }
        // A plain HTTP server that answers 401 without the pairing path:
        // spin one on an ephemeral port.
        async fn reply_with(
            status: &'static str,
            body: &'static str,
        ) -> (String, tokio::task::JoinHandle<()>) {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let task = tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                if let Ok((mut socket, _)) = listener.accept().await {
                    let mut scratch = [0u8; 4096];
                    let _ = socket.read(&mut scratch).await;
                    let head = format!("HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
                    let _ = socket.write_all(head.as_bytes()).await;
                }
            });
            (url, task)
        }
        let (url, server) = reply_with("401 Unauthorized", "{\"e\":1}").await;
        assert_eq!(
            redeem(&url, "code", "Pixel").await.err(),
            Some(PairError::Refused)
        );
        server.await.unwrap();
        let (url, server) = reply_with("400 Bad Request", "{\"e\":1}").await;
        assert!(matches!(
            redeem(&url, "code", "Pixel").await,
            Err(PairError::Failed { message }) if message.starts_with("400")
        ));
        server.await.unwrap();
        let (url, server) = reply_with("200 OK", "not json").await;
        assert!(matches!(
            redeem(&url, "code", "Pixel").await,
            Err(PairError::Failed { message }) if message == "Invalid pairing response"
        ));
        server.await.unwrap();
    }
}
