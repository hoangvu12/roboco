//! The agent-accounts FFI surface (android-next#05): the records behind
//! the composer's usage chip, the Settings → Agent Accounts section and
//! the Accounts & Usage page, plus the core calls they run. The accounts
//! half of upstream zeron PR #578's `client_ffi`, ported onto
//! `roboco-mobile-client`'s engine-local handle (no relay — ADR 0004).
//!
//! Every method is safe from any thread; `async` methods run on the
//! client's own runtime and can be awaited from Kotlin coroutines
//! directly. Snapshots cross as plain records; thresholds live in the
//! core ([`usage_level`], shared with the desktop), and the Kotlin side
//! mirrors the same numbers for its pure derivations.

use roboco_mobile_client as mc;

use super::CoreClient;
use super::CoreResult;
use super::on_runtime;

// ── the records ────────────────────────────────────────────────────────────

/// One plan rate-limit window ("Session", "Week", "Month", …).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgentUsageWindow {
    pub label: String,
    /// 0.0..=1.0 of the window used.
    pub used_fraction: f32,
    /// Epoch millis the window resets, if the provider says.
    pub resets_at_ms: Option<i64>,
}

/// A login saved for an agent CLI on one engine.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgentAccount {
    pub id: String,
    /// Wire harness id (`claude-code`, `codex`, …).
    pub harness: String,
    pub email: Option<String>,
    pub plan_label: Option<String>,
    /// The login the CLI uses now (new sessions run on it).
    pub active: bool,
    pub usage_windows: Vec<AgentUsageWindow>,
    /// Epoch millis of the usage probe the windows came from.
    pub usage_fetched_at_ms: Option<i64>,
    /// Why usage is missing or stale ("Rate limited — retrying in 2m").
    pub usage_error: Option<String>,
    pub display_name: Option<String>,
    pub organization: Option<String>,
    /// Signed in with a raw API key (no plan usage).
    pub api_key: bool,
    /// Can be made the one in use (false: credentials unreadable, or the
    /// agent manages its own pool).
    pub switchable: bool,
    /// Upstream login group inside multi-provider agents (OpenCode's
    /// `openai`, …); rows sharing it are one single-choice group.
    pub provider: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgentAccountWarning {
    pub harness: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgentAccountsSnapshot {
    pub accounts: Vec<AgentAccount>,
    pub warnings: Vec<AgentAccountWarning>,
}

/// The proto harness enum's wire string (`"claude-code"`).
fn harness_wire(h: &roboco_proto::HarnessId) -> String {
    serde_json::to_value(h)
        .ok()
        .and_then(|v| v.as_str().map(str::to_owned))
        .unwrap_or_default()
}

impl From<roboco_proto::AgentAccountsSnapshot> for AgentAccountsSnapshot {
    fn from(s: roboco_proto::AgentAccountsSnapshot) -> Self {
        Self {
            accounts: s
                .accounts
                .into_iter()
                .map(|a| AgentAccount {
                    id: a.id,
                    harness: harness_wire(&a.harness),
                    email: a.email,
                    plan_label: a.plan_label,
                    active: a.active,
                    usage_windows: a
                        .usage_windows
                        .into_iter()
                        .map(|w| AgentUsageWindow {
                            label: w.label,
                            used_fraction: w.used_fraction.clamp(0.0, 1.0),
                            resets_at_ms: w.resets_at.map(|t| t.timestamp_millis()),
                        })
                        .collect(),
                    usage_fetched_at_ms: a.usage_fetched_at,
                    usage_error: a.usage_error,
                    display_name: a.display_name,
                    organization: a.organization,
                    api_key: a.auth_kind == Some(roboco_proto::AgentAuthKind::ApiKey),
                    switchable: a.switchable,
                    provider: a.provider,
                })
                .collect(),
            warnings: s
                .warnings
                .into_iter()
                .map(|w| AgentAccountWarning {
                    harness: harness_wire(&w.harness),
                    message: w.message,
                })
                .collect(),
        }
    }
}

// ── the calls ──────────────────────────────────────────────────────────────

#[uniffi::export]
impl CoreClient {
    /// Agent accounts on `device_id`: logins per agent CLI, the one in
    /// use, plan usage. `force_usage` re-probes usage on the host (a
    /// forced list carries the 45 s deadline).
    pub async fn list_agent_accounts(
        &self,
        device_id: String,
        force_usage: bool,
    ) -> CoreResult<AgentAccountsSnapshot> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .list_agent_accounts(&device_id, force_usage)
                .await
        })
        .await
        .map(Into::into)
    }

    /// Switch `harness` on `device_id` to `account_id` (new sessions pick
    /// it up). Replies with the updated accounts.
    pub async fn activate_agent_account(
        &self,
        device_id: String,
        harness: String,
        account_id: String,
    ) -> CoreResult<AgentAccountsSnapshot> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .activate_agent_account(&device_id, &harness, &account_id)
                .await
        })
        .await
        .map(Into::into)
    }

    /// Remove a saved login from `device_id`.
    pub async fn forget_agent_account(
        &self,
        device_id: String,
        harness: String,
        account_id: String,
    ) -> CoreResult<AgentAccountsSnapshot> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .forget_agent_account(&device_id, &harness, &account_id)
                .await
        })
        .await
        .map(Into::into)
    }

    /// The newest snapshot the core holds for `device_id` (every
    /// successful list/activate/forget refreshes it), so a screen paints
    /// at once and refreshes behind it. `None` before the first success.
    pub fn cached_agent_accounts(&self, device_id: String) -> Option<AgentAccountsSnapshot> {
        self.client
            .cached_agent_accounts(&device_id)
            .map(Into::into)
    }
}

// ── usage levels (shared with the desktop) ────────────────────────────────

/// How full a usage window reads (desktop thresholds: amber at 80 %,
/// red at 95 %).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UsageLevel {
    Normal,
    Warning,
    Critical,
}

impl From<mc::UsageLevel> for UsageLevel {
    fn from(level: mc::UsageLevel) -> Self {
        match level {
            mc::UsageLevel::Normal => UsageLevel::Normal,
            mc::UsageLevel::Warning => UsageLevel::Warning,
            mc::UsageLevel::Critical => UsageLevel::Critical,
        }
    }
}

/// Threshold classification of a usage fraction (the desktop's
/// `usage_level`, the one rule every surface shares).
#[uniffi::export]
pub fn usage_level(used_fraction: f32) -> UsageLevel {
    mc::usage_level(used_fraction).into()
}
