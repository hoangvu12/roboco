//! Tailcat pairing invites for the engine registry. The connection layer in
//! `roboco-engine-client` is engine-free; this resolver is the desktop's
//! plug-in that turns a `roboco-tailcat:` invite into a redeemable pairing
//! base by starting a Tailcat forwarder client (a Go adapter child process)
//! and keeping it alive on the registry entry.

use roboco_engine::tailcat::TailcatClient;
use roboco_engine_client::{PairingResolver, ResolvedPairing, RestoredRoute};
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex, Weak};
use std::time::Duration;

/// Saved routes name the invite's Tailcat address. The address is a secret
/// (ADR 0008): it lives only in the 0600 paired-engine store beside the
/// session credential, and never in logs or errors.
const ROUTE_PREFIX: &str = "tailcat:";

pub struct TailcatResolver;

impl PairingResolver for TailcatResolver {
    fn resolve(&self, input: &str, data_dir: &Path) -> Option<anyhow::Result<ResolvedPairing>> {
        if !input
            .trim_start()
            .starts_with(roboco_engine::tailcat::INVITE_PREFIX)
        {
            return None;
        }
        Some(tailcat_pairing(input, data_dir))
    }

    /// A restarted desktop rebuilds the forwarder for a saved Tailcat
    /// engine from its address; the saved loopback URL died with the old
    /// process.
    fn restore(&self, route: &str, data_dir: &Path) -> Option<anyhow::Result<RestoredRoute>> {
        let address = route.strip_prefix(ROUTE_PREFIX)?;
        Some(forwarder(address, data_dir).map(|client| RestoredRoute {
            base: base(&client),
            keepalive: Some(Box::new(client)),
        }))
    }
}

fn tailcat_pairing(input: &str, data_dir: &Path) -> anyhow::Result<ResolvedPairing> {
    let invite = roboco_engine::tailcat::TailcatInvite::decode(input)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_millis() as i64;
    anyhow::ensure!(
        !invite.expired(now),
        "that tailcat invite has expired; mint a new one"
    );
    // The registry drops the client (and with it the adapter process) when
    // the entry is forgotten; the saved route rebuilds it after a restart.
    let client = forwarder(&invite.address, data_dir)?;
    Ok(ResolvedPairing {
        base: base(&client),
        code: invite.token,
        keepalive: Some(Box::new(client)),
        route: Some(format!("{ROUTE_PREFIX}{}", invite.address)),
    })
}

fn base(client: &TailcatClient) -> String {
    client.url().trim_end_matches('/').to_string()
}

/// One live forwarder per Tailcat address in this process. Every registry
/// shares it (each side-chat pane opens its own registry) instead of each
/// starting an adapter process; it stops when the last one drops it. One
/// whose loopback stopped accepting is replaced.
fn forwarder(address: &str, data_dir: &Path) -> anyhow::Result<Arc<TailcatClient>> {
    static FORWARDERS: LazyLock<Mutex<HashMap<String, Weak<TailcatClient>>>> =
        LazyLock::new(Default::default);
    // Held while a forwarder starts, so concurrent restores share it.
    let mut forwarders = FORWARDERS.lock().unwrap_or_else(|e| e.into_inner());
    forwarders.retain(|_, client| client.strong_count() > 0);
    if let Some(client) = forwarders.get(address).and_then(Weak::upgrade)
        && loopback_accepts(client.url())
    {
        return Ok(client);
    }
    // One forwarder session directory per started forwarder.
    let session = data_dir
        .join("tailcat-forwarders")
        .join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&session)?;
    let client = Arc::new(TailcatClient::start(&session, address, None)?);
    forwarders.insert(address.to_owned(), Arc::downgrade(&client));
    Ok(client)
}

fn loopback_accepts(url: &str) -> bool {
    let Some(address) = reqwest::Url::parse(url)
        .ok()
        .and_then(|url| url.socket_addrs(|| None).ok())
        .and_then(|addresses| addresses.into_iter().next())
    else {
        return false;
    };
    std::net::TcpStream::connect_timeout(&address, Duration::from_secs(2)).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Only Tailcat routes are claimed, and a route that cannot come up
    /// fails without quoting its address (no adapter in a source build, or
    /// a malformed address where one is installed: both fail before any
    /// process starts, and both describe only the shape).
    #[test]
    fn restore_claims_tailcat_routes_and_never_echoes_the_address() {
        let dir = tempfile::tempdir().unwrap();
        assert!(TailcatResolver.restore("other:route", dir.path()).is_none());
        let secret = "tcSECRET!notbase64url";
        let Some(Err(error)) = TailcatResolver.restore(&format!("tailcat:{secret}"), dir.path())
        else {
            panic!("a dead route must fail");
        };
        assert!(!format!("{error:#}").contains(secret), "{error:#}");
    }
}
