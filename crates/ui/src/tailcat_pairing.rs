//! Tailcat pairing invites for the engine registry. The connection layer in
//! `roboco-engine-client` is engine-free; this resolver is the desktop's
//! plug-in that turns a `roboco-tailcat:` invite into a redeemable pairing
//! base by starting a Tailcat forwarder client (a Go adapter child process)
//! and keeping it alive on the registry entry.

use roboco_engine_client::{PairingResolver, ResolvedPairing};
use std::path::Path;

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
    // One forwarder session directory per invite redemption; the registry
    // drops the client (and with it the adapter process) when the entry is
    // forgotten. A restarted desktop does not recreate the forwarder for an
    // already-saved Tailcat entry — matching the pre-extraction registry.
    let session = data_dir
        .join("tailcat-forwarders")
        .join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&session)?;
    let client = roboco_engine::tailcat::TailcatClient::start(&session, &invite.address, None)?;
    let base = client.url().trim_end_matches('/').to_string();
    Ok(ResolvedPairing {
        base,
        code: invite.token,
        keepalive: Some(Box::new(client)),
    })
}
