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

use roboco_proto::{AgentAccountsSnapshot, AgentLoginPoll, AgentLoginStart};

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
    /// Wave-10 36: the sign-ins this phone started on a computer, by login
    /// id — the random requester it named (the only key that relays the
    /// login's callback) and, for a loopback login, the listener catching
    /// the browser's redirect.
    pub(crate) logins: Mutex<HashMap<String, PhoneLogin>>,
}

/// One sign-in this phone started on a computer (wave-10 36).
pub(crate) struct PhoneLogin {
    requester: String,
    relay: Option<roboco_engine_client::login_relay::LoginRelay>,
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

    /// Start signing `harness` in on `device_id`'s engine (`provider`: one
    /// of the agent's login options, `None` for its default) — upstream
    /// #966's `device_login_start`, on the engine handle instead of the
    /// relay. The computer runs the sign-in; the phone opens the reply's
    /// page. A `callback_port` means the page redirects to the computer's
    /// loopback, which a phone's browser can't reach on its own.
    pub async fn start_agent_login(
        &self,
        device_id: &str,
        harness: &str,
        provider: Option<&str>,
    ) -> Result<AgentLoginStart, ClientError> {
        let inner = self.inner_arc();
        let handle = resolve(&inner, device_id)?;
        // Wave-10 36: a fresh random requester per sign-in — the computer
        // publishes the login's callback route for it alone, and only this
        // phone knows it.
        let requester = uuid::Uuid::new_v4().to_string();
        let value = handle
            .call(
                roboco_rpc::methods::START_AGENT_LOGIN,
                serde_json::json!({
                    "harness": harness,
                    "provider": provider,
                    "requesterDeviceId": requester,
                }),
            )
            .await?;
        let start: AgentLoginStart = decode_as(value, "agent login start")?;
        lock(&inner.accounts.logins).insert(
            start.login_id.clone(),
            PhoneLogin {
                requester,
                relay: None,
            },
        );
        if start.mode == roboco_proto::AgentLoginMode::Browser
            && let Some(port) = start.callback_port
            && let Err(message) = relay_login_callback(&inner, &handle, &start.login_id, port).await
        {
            self.cancel_agent_login(device_id, &start.login_id);
            return Err(ClientError::HostError { message });
        }
        Ok(start)
    }

    /// `PollAgentLogin`: still pending, done, or failed (a page that only
    /// became known now rides along).
    pub async fn poll_agent_login(
        &self,
        device_id: &str,
        login_id: &str,
    ) -> Result<AgentLoginPoll, ClientError> {
        let inner = self.inner_arc();
        let handle = resolve(&inner, device_id)?;
        let value = handle
            .call(
                roboco_rpc::methods::POLL_AGENT_LOGIN,
                serde_json::json!({ "loginId": login_id }),
            )
            .await?;
        let poll: AgentLoginPoll = decode_as(value, "agent login poll")?;
        match poll.status {
            roboco_proto::AgentLoginStatus::Pending => {
                // A page that only became known now (an agent that had to
                // install first, Antigravity's) gets its relay here.
                let listening = lock(&inner.accounts.logins)
                    .get(login_id)
                    .is_some_and(|login| login.relay.is_some());
                if let Some(port) = poll.callback_port
                    && !listening
                {
                    relay_login_callback(&inner, &handle, login_id, port)
                        .await
                        .map_err(|message| ClientError::HostError { message })?;
                }
            }
            roboco_proto::AgentLoginStatus::Done | roboco_proto::AgentLoginStatus::Error => {
                lock(&inner.accounts.logins).remove(login_id);
            }
        }
        Ok(poll)
    }

    /// `CompleteAgentLogin`: the code the sign-in page showed, pasted back
    /// (Claude's paste fallback). Replies with the updated accounts and
    /// refreshes the cache.
    pub async fn complete_agent_login(
        &self,
        device_id: &str,
        login_id: &str,
        code: &str,
    ) -> Result<AgentAccountsSnapshot, ClientError> {
        let inner = self.inner_arc();
        let handle = resolve(&inner, device_id)?;
        let value = handle
            .call(
                roboco_rpc::methods::COMPLETE_AGENT_LOGIN,
                serde_json::json!({ "loginId": login_id, "code": code.trim() }),
            )
            .await?;
        let snapshot: AgentAccountsSnapshot = decode(value)?;
        store(&inner, &handle, snapshot.clone());
        lock(&inner.accounts.logins).remove(login_id);
        Ok(snapshot)
    }

    /// Give up on a sign-in, best effort on the computer (fire-and-forget:
    /// a cancel that can't reach the engine is reaped there by its TTL).
    pub fn cancel_agent_login(&self, device_id: &str, login_id: &str) {
        let inner = self.inner_arc();
        // The listener stops with its login.
        lock(&inner.accounts.logins).remove(login_id);
        let Ok(handle) = resolve(&inner, device_id) else {
            return;
        };
        let login_id = login_id.to_owned();
        crate::runtime::shared().spawn(async move {
            let _ = handle
                .call(
                    roboco_rpc::methods::CANCEL_AGENT_LOGIN,
                    serde_json::json!({ "loginId": login_id }),
                )
                .await;
        });
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

/// Listen on this phone's loopback `port` for `login_id`'s browser
/// redirect and relay it to the computer (`RelayAgentLoginCallback` with
/// the login's requester) — wave-10 36, upstream #966's
/// `relay_login_callback`. `Err` carries the "Port … is in use on this
/// phone" message.
async fn relay_login_callback(
    inner: &std::sync::Arc<ClientInner>,
    handle: &EngineHandle,
    login_id: &str,
    port: u16,
) -> Result<(), String> {
    let requester = lock(&inner.accounts.logins)
        .get(login_id)
        .map(|login| login.requester.clone())
        .ok_or_else(|| "This sign-in attempt expired — start again.".to_owned())?;
    let (relay_handle, relay_login) = (handle.clone(), login_id.to_owned());
    let relay = roboco_engine_client::login_relay::listen(
        crate::runtime::shared().handle(),
        port,
        "phone",
        Box::new(move |path| {
            let (handle, login_id, requester) =
                (relay_handle.clone(), relay_login.clone(), requester.clone());
            Box::pin(async move {
                let value = handle
                    .call(
                        roboco_rpc::methods::RELAY_AGENT_LOGIN_CALLBACK,
                        serde_json::to_value(roboco_proto::RelayAgentLoginCallback {
                            login_id,
                            requester_device_id: requester,
                            path,
                        })
                        .map_err(|e| e.to_string())?,
                    )
                    .await
                    .map_err(|e| e.to_string())?;
                serde_json::from_value::<roboco_proto::RelayedLoginCallback>(value)
                    .map_err(|e| e.to_string())
            })
        }),
    )
    .await?;
    if let Some(login) = lock(&inner.accounts.logins).get_mut(login_id) {
        login.relay = Some(relay);
    }
    Ok(())
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

fn decode_as<T: serde::de::DeserializeOwned>(value: serde_json::Value, what: &str) -> Result<T, ClientError> {
    serde_json::from_value(value).map_err(|error| ClientError::HostError {
        message: format!("undecodable {what}: {error}"),
    })
}

fn store(inner:&std::sync::Arc<ClientInner>, handle: &EngineHandle, snapshot: AgentAccountsSnapshot) {
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
