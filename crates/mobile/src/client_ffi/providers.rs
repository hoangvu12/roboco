//! A computer's agents over FFI (upstream #966 `cc7da5c2a`,
//! `crates/mobile/src/client_ffi/providers.rs`): how each agent signs in
//! ([`agent_login_rules`], the rules every client's provider settings
//! follow) and the sign-in a phone starts on a paired computer
//! (`device_login_*`). Ported onto `roboco-mobile-client`'s engine-local
//! handle instead of upstream's relay (ADR 0004): `device_id` resolves to
//! one paired engine, the computer runs the sign-in, the phone opens its
//! page.

use roboco_proto::{AgentLoginMode, AgentLoginStatus, HarnessId};

use super::{AgentAccountsSnapshot, CoreClient, CoreResult, on_runtime};

// ── the records ────────────────────────────────────────────────────────────

/// A computer's sign-in under way: open `url` in a browser sheet and poll
/// until done — or, `paste_code`, take the code the page shows back with
/// `device_login_complete`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DeviceLoginInfo {
    pub login_id: String,
    /// Empty when the page is only known later (a poll carries it).
    pub url: String,
    pub paste_code: bool,
    /// The page redirects to this port on the computer's loopback when it
    /// finishes (Codex's 1455, Claude's own listener, …): a phone's
    /// browser can't reach it unless the redirect is relayed back.
    pub callback_port: Option<u16>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DeviceLoginPollInfo {
    /// `pending` · `done` · `error`.
    pub status: String,
    pub message: Option<String>,
    /// A page first known now, to open.
    pub url: Option<String>,
    /// [`DeviceLoginInfo::callback_port`] for a page that arrived now.
    pub callback_port: Option<u16>,
}

/// How an agent signs in, from the rules every client's Providers follows
/// (`roboco_proto::providers`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AgentLoginRules {
    /// The agent's display name ("Claude Code"); empty for one Roboco
    /// doesn't sign in to.
    pub name: String,
    /// The agent has accounts Roboco can sign in to at all.
    pub signs_in: bool,
    /// Roboco switches between its accounts (not Hermes, not Antigravity).
    pub switches: bool,
    /// It holds one login: once connected there is nothing to add.
    pub keeps_one_login: bool,
    pub reports_usage: bool,
    /// A standing note for an agent whose accounts work differently.
    pub note: Option<String>,
    pub options: Vec<LoginOptionInfo>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LoginOptionInfo {
    /// The engine's `provider` (`None`: the agent's only login).
    pub provider: Option<String>,
    pub label: String,
    /// The add row's title with no account yet, and with some.
    pub first_title: String,
    pub more_title: String,
}

// ── the rules ──────────────────────────────────────────────────────────────

/// [`AgentLoginRules`] for `harness` (`claude-code`, `codex`, …); an
/// unknown harness signs in to nothing.
#[uniffi::export]
pub fn agent_login_rules(harness: String) -> AgentLoginRules {
    use roboco_proto::providers as rules;
    let Ok(id) = serde_json::from_value::<HarnessId>(serde_json::json!(harness)) else {
        return AgentLoginRules {
            name: String::new(),
            signs_in: false,
            switches: false,
            keeps_one_login: false,
            reports_usage: false,
            note: None,
            options: Vec::new(),
        };
    };
    let signs_in = rules::signs_in(id);
    AgentLoginRules {
        name: if signs_in {
            rules::provider_name(id).to_owned()
        } else {
            String::new()
        },
        signs_in,
        switches: rules::switches_accounts(id),
        keeps_one_login: rules::keeps_one_login(id),
        reports_usage: rules::reports_usage(id),
        note: rules::provider_note(id).map(str::to_owned),
        options: if signs_in {
            rules::login_options(id)
                .into_iter()
                .map(|option| LoginOptionInfo {
                    provider: option.provider.map(str::to_owned),
                    label: option.label.to_owned(),
                    first_title: rules::add_option_label(id, option, true),
                    more_title: rules::add_option_label(id, option, false),
                })
                .collect()
        } else {
            Vec::new()
        },
    }
}

fn poll_status(status: AgentLoginStatus) -> String {
    match status {
        AgentLoginStatus::Pending => "pending",
        AgentLoginStatus::Done => "done",
        AgentLoginStatus::Error => "error",
    }
    .into()
}

// ── the calls ──────────────────────────────────────────────────────────────

#[uniffi::export]
impl CoreClient {
    /// Start signing `harness` in on `device_id` (`provider`: a login
    /// option's, `None` for the default).
    pub async fn device_login_start(
        &self,
        device_id: String,
        harness: String,
        provider: Option<String>,
    ) -> CoreResult<DeviceLoginInfo> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .start_agent_login(&device_id, &harness, provider.as_deref())
                .await
        })
        .await
        .map(|start| DeviceLoginInfo {
            login_id: start.login_id,
            url: start.url,
            paste_code: start.mode == AgentLoginMode::PasteCode,
            callback_port: start.callback_port,
        })
    }

    pub async fn device_login_poll(
        &self,
        device_id: String,
        login_id: String,
    ) -> CoreResult<DeviceLoginPollInfo> {
        let client = self.client.clone();
        on_runtime(async move { client.poll_agent_login(&device_id, &login_id).await })
            .await
            .map(|poll| DeviceLoginPollInfo {
                status: poll_status(poll.status),
                message: poll.message,
                url: poll.url,
                callback_port: poll.callback_port,
            })
    }

    /// The code the sign-in page showed (Claude's paste fallback); the
    /// updated accounts.
    pub async fn device_login_complete(
        &self,
        device_id: String,
        login_id: String,
        code: String,
    ) -> CoreResult<AgentAccountsSnapshot> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .complete_agent_login(&device_id, &login_id, &code)
                .await
        })
        .await
        .map(Into::into)
    }

    /// Give up on a computer's sign-in (best effort on the computer).
    pub fn device_login_cancel(&self, device_id: String, login_id: String) {
        self.client.cancel_agent_login(&device_id, &login_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rules_name_the_options_and_their_add_titles() {
        let claude = agent_login_rules("claude-code".into());
        assert!(claude.signs_in);
        assert_eq!(claude.name, "Claude Code");
        assert!(claude.switches);
        assert_eq!(claude.options.len(), 1);
        assert_eq!(claude.options[0].provider, None);
        assert_eq!(claude.options[0].first_title, "Connect a Claude Code account");
        assert_eq!(claude.options[0].more_title, "Add account");

        let opencode = agent_login_rules("opencode".into());
        let titles: Vec<_> = opencode
            .options
            .iter()
            .map(|o| (o.provider.as_deref(), o.first_title.as_str()))
            .collect();
        assert_eq!(
            titles,
            vec![
                (Some("openai"), "Connect ChatGPT"),
                (Some("github-copilot"), "Connect GitHub Copilot"),
            ]
        );

        let hermes = agent_login_rules("hermes".into());
        assert!(!hermes.switches);
        assert!(hermes.note.is_some());
        let antigravity = agent_login_rules("antigravity".into());
        assert!(antigravity.keeps_one_login);
        assert!(!antigravity.reports_usage);
    }

    #[test]
    fn an_unknown_or_unsigned_agent_signs_in_to_nothing() {
        for harness in ["mock", "no-such-agent", ""] {
            let rules = agent_login_rules(harness.into());
            assert!(!rules.signs_in, "{harness}");
            assert!(rules.name.is_empty(), "{harness}");
            assert!(rules.options.is_empty(), "{harness}");
        }
    }

    #[test]
    fn poll_statuses_are_the_upstream_strings() {
        assert_eq!(poll_status(AgentLoginStatus::Pending), "pending");
        assert_eq!(poll_status(AgentLoginStatus::Done), "done");
        assert_eq!(poll_status(AgentLoginStatus::Error), "error");
    }
}
