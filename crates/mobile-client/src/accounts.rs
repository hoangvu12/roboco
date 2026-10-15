//! Agent (provider) accounts: who is signed in to each agent CLI on an
//! engine, which login is in use, and their plan-usage windows
//! (android-next#05 — the accounts half of upstream zeron PR #578, ported
//! onto the engine-local handle instead of zeron's relay).
//!
//! The engine owns detection, slots and usage probing (`ListAgentAccounts
//! {forceUsage}`, `ActivateAgentAccount`, `ForgetAgentAccount`; a paired
//! phone has the full capability surface, `crates/engine/src/rpc.rs` §3.7).
//! This module is the phone-side wrapper: it resolves the platform's
//! `device_id` to an engine ([`crate::catalog_live::resolve_engine`]),
//! calls the RPCs, applies the **forced-probe deadline** #578 gave the
//! relay route (45 s — a forced list probes every saved login's usage,
//! ~8 s per provider call, concurrently), and caches the newest snapshot
//! **per engine** so screens paint at once and refresh in the background.
//!
//! Usage thresholds (amber ≥ 80 %, red ≥ 95 %) are the desktop's
//! (`crates/ui/src/settings/accounts.rs`); [`usage_level`] is the shared
//! rule the FFI re-exports so every surface classifies identically.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use roboco_proto::AgentAccountsSnapshot;

use crate::engines::EngineHandle;
use crate::{ClientError, lock};

use crate::ClientInner;

// ── usage levels (shared with the desktop) ─────────────────────────────────

/// Amber at 80 % of a window (`crates/ui/src/settings/accounts.rs`).
pub const USAGE_WARN_FRACTION: f32 = 0.80;

/// Red at 95 % of a window.
pub const USAGE_CRITICAL_FRACTION: f32 = 0.95;

/// How full a usage window reads. Pure classification, shared by every
/// accounts surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UsageLevel {
    /// < 80 % — accent.
    Normal,
    /// ≥ 80 % — amber.
    Warning,
    /// ≥ 95 % — red.
    Critical,
}

/// Threshold classification of a usage fraction (desktop `usage_level`).
pub fn usage_level(used_fraction: f32) -> UsageLevel {
    if used_fraction >= USAGE_CRITICAL_FRACTION {
        UsageLevel::Critical
    } else if used_fraction >= USAGE_WARN_FRACTION {
        UsageLevel::Warning
    } else {
        UsageLevel::Normal
    }
}

// ── the client-side snapshot cache ─────────────────────────────────────────

/// Newest accounts snapshot per engine (keyed by the resolved engine id,
/// so every `device_id` shape the platform holds — scoped id, engine
/// device id, raw engine id — shares one entry).
#[derive(Default)]
pub(crate) struct AccountsState {
    pub(crate) snapshots: Mutex<HashMap<String, AgentAccountsSnapshot>>,
}

impl crate::Client {
    /// Who's signed in to each agent CLI on `device_id`'s engine, which
    /// login is in use, and plan-usage windows. `force_usage` re-probes
    /// usage on the host (throttled host-side per account); a plain list
    /// answers from the host's last probe and stays offline-fast.
    /// Every successful call refreshes [`Self::cached_agent_accounts`].
    pub async fn list_agent_accounts(
        &self,
        device_id: &str,
        force_usage: bool,
    ) -> Result<AgentAccountsSnapshot, ClientError> {
        let inner = self.inner_arc();
        let handle = resolve(&inner, device_id)?;
        let value = accounts_call(
            &handle,
            roboco_rpc::methods::LIST_AGENT_ACCOUNTS,
            serde_json::json!({ "forceUsage": force_usage }),
            force_usage,
            inner.timings.accounts_forced_deadline,
        )
        .await?;
        let snapshot: AgentAccountsSnapshot = decode(value)?;
        store(&inner, &handle, snapshot.clone());
        Ok(snapshot)
    }

    /// Make `account_id` the login `harness` uses on `device_id`'s engine
    /// (new sessions pick it up). Replies with the updated accounts and
    /// refreshes the cache.
    pub async fn activate_agent_account(
        &self,
        device_id: &str,
        harness: &str,
        account_id: &str,
    ) -> Result<AgentAccountsSnapshot, ClientError> {
        agent_account_call(
            self,
            device_id,
            roboco_rpc::methods::ACTIVATE_AGENT_ACCOUNT,
            harness,
            account_id,
        )
        .await
    }

    /// Remove a saved login from `device_id`'s engine (signs the CLI out
    /// when it was the one in use). Replies with the updated accounts and
    /// refreshes the cache.
    pub async fn forget_agent_account(
        &self,
        device_id: &str,
        harness: &str,
        account_id: &str,
    ) -> Result<AgentAccountsSnapshot, ClientError> {
        agent_account_call(
            self,
            device_id,
            roboco_rpc::methods::FORGET_AGENT_ACCOUNT,
            harness,
            account_id,
        )
        .await
    }

    /// The newest snapshot for `device_id`'s engine (the cache every
    /// successful list/activate/forget refreshes), so a screen paints at
    /// once and refreshes behind it. `None` before the first success.
    pub fn cached_agent_accounts(&self, device_id: &str) -> Option<AgentAccountsSnapshot> {
        let inner = self.inner_arc();
        let handle = resolve(&inner, device_id).ok()?;
        lock(&inner.accounts.snapshots)
            .get(&handle.engine_id().to_owned())
            .cloned()
    }
}

fn resolve(inner: &std::sync::Arc<ClientInner>, device_id: &str) -> Result<EngineHandle, ClientError> {
    crate::catalog_live::resolve_engine(inner, device_id)
}

async fn agent_account_call(
    client: &crate::Client,
    device_id: &str,
    method: &'static str,
    harness: &str,
    account_id: &str,
) -> Result<AgentAccountsSnapshot, ClientError> {
    let inner = client.inner_arc();
    let handle = resolve(&inner, device_id)?;
    let value = accounts_call(
        &handle,
        method,
        serde_json::json!({ "harness": harness, "accountId": account_id }),
        false,
        inner.timings.accounts_forced_deadline,
    )
    .await?;
    let snapshot: AgentAccountsSnapshot = decode(value)?;
    store(&inner, &handle, snapshot.clone());
    Ok(snapshot)
}

/// One accounts RPC over the engine handle. `force_usage` calls carry the
/// 45 s deadline #578 gave the relay route: the engine probes every saved
/// login's usage concurrently, so a hung probe must not pin the caller
/// (the shared 30 s default is the mobile table's, `engines/handle.rs`).
async fn accounts_call(
    handle: &EngineHandle,
    method: &'static str,
    params: serde_json::Value,
    forced: bool,
    forced_deadline: Duration,
) -> Result<serde_json::Value, ClientError> {
    let call = handle.call(method, params);
    if !forced {
        return call.await;
    }
    tokio::time::timeout(forced_deadline, call)
        .await
        .map_err(|_| ClientError::HostUnavailable {
            message: "The usage probe timed out".into(),
        })?
}

fn decode(value: serde_json::Value) -> Result<AgentAccountsSnapshot, ClientError> {
    serde_json::from_value(value).map_err(|error| ClientError::HostError {
        message: format!("undecodable agent accounts: {error}"),
    })
}

fn store(inner: &std::sync::Arc<ClientInner>, handle: &EngineHandle, snapshot: AgentAccountsSnapshot) {
    lock(&inner.accounts.snapshots)
        .insert(handle.engine_id().to_owned(), snapshot);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::timings::Timings;

    #[test]
    fn usage_levels_match_the_desktop_thresholds() {
        assert_eq!(usage_level(0.0), UsageLevel::Normal);
        assert_eq!(usage_level(0.79), UsageLevel::Normal);
        assert_eq!(usage_level(0.8), UsageLevel::Warning);
        assert_eq!(usage_level(0.94), UsageLevel::Warning);
        assert_eq!(usage_level(0.95), UsageLevel::Critical);
        assert_eq!(usage_level(1.0), UsageLevel::Critical);
    }

    #[test]
    fn the_forced_deadline_defaults_to_pr578s_45s() {
        assert_eq!(
            Timings::default().accounts_forced_deadline,
            Duration::from_secs(45)
        );
    }
}
