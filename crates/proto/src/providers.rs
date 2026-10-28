//! Which agents Roboco signs in to and how: the rules every client's
//! provider settings follow (desktop Settings → Providers, the Android
//! Accounts & Usage page), so the platforms offer the same sign-ins, labels
//! and switching. Pure. Upstream #966 (`cc7da5c2a`) moved these rules to
//! `zeron_proto::providers`; the desktop re-exports them from
//! `crates/ui/src/settings/accounts.rs`.

use crate::HarnessId;

/// The provider cards, in display order: (harness, name, CLI command — named
/// in the empty-state copy, roboco settings.agents.tsx `PROVIDERS`). Every
/// agent with a login of its own is here; what each one supports is
/// documented engine-side (`agent_accounts` module docs). Antigravity's
/// command never prints in the copy — it has no CLI login (see the
/// empty-state arm).
pub const PROVIDERS: [(HarnessId, &str, &str); 9] = [
    (HarnessId::ClaudeCode, "Claude Code", "claude"),
    (HarnessId::Codex, "Codex", "codex"),
    (HarnessId::Cursor, "Cursor", "cursor-agent"),
    (HarnessId::Antigravity, "Antigravity", "Antigravity"),
    (HarnessId::Grok, "Grok", "grok login"),
    (HarnessId::Devin, "Devin", "devin auth login"),
    (HarnessId::Opencode, "OpenCode", "opencode auth login"),
    (HarnessId::Pi, "Pi", "pi"),
    (HarnessId::Hermes, "Hermes", "hermes auth add"),
];

/// Whether `harness` has an Accounts section (and sign-in flow). Pure.
pub fn signs_in(harness: HarnessId) -> bool {
    PROVIDERS
        .iter()
        .any(|(provider, _, _)| *provider == harness)
}

/// Whether the provider reports plan usage. One that doesn't shows no meters
/// and no "usage unavailable" note — there is nothing missing. Pure.
pub fn reports_usage(harness: HarnessId) -> bool {
    harness != HarnessId::Antigravity
}

/// Providers whose agent holds exactly ONE login (Antigravity): once it is
/// connected there is nothing to add — signing in again only re-confirms it.
pub fn keeps_one_login(harness: HarnessId) -> bool {
    harness == HarnessId::Antigravity
}

/// Display name of one agent (upstream `provider_name`) — the fallback
/// [`LoginOption`] label for single-login agents. Pure.
pub fn provider_name(harness: HarnessId) -> &'static str {
    match harness {
        HarnessId::ClaudeCode => "Claude Code",
        HarnessId::Codex => "Codex",
        HarnessId::Cursor => "Cursor",
        HarnessId::Antigravity => "Antigravity",
        HarnessId::Grok => "Grok",
        HarnessId::Devin => "Devin",
        HarnessId::Opencode => "OpenCode",
        HarnessId::Pi => "Pi",
        HarnessId::Hermes => "Hermes",
        _ => "Agent",
    }
}

/// Whether roboco switches this agent's logins. Hermes rotates through its
/// own credential pool (listed, never reordered); Antigravity keeps one.
pub fn switches_accounts(harness: HarnessId) -> bool {
    !matches!(harness, HarnessId::Hermes | HarnessId::Antigravity)
}

/// A standing note under a provider's card, for an agent whose accounts work
/// differently. Hermes owns its credential pool: roboco lists it and adds to
/// it through Hermes' own CLI, but never switches or removes its entries.
/// Pure.
pub fn provider_note(harness: HarnessId) -> Option<&'static str> {
    match harness {
        HarnessId::Hermes => Some(
            "Hermes manages its own credential pool and rotates through it. Accounts added \
             here go through `hermes auth add`; remove one with `hermes auth remove`.",
        ),
        _ => None,
    }
}

/// One way to add an account: agents that keep a login PER model provider
/// (OpenCode, Pi, Hermes) sign in to a named provider; the rest have one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LoginOption {
    /// The engine's `provider` param (`None` = the agent's only login).
    pub provider: Option<&'static str>,
    /// Who the user signs in to.
    pub label: &'static str,
}

/// The sign-ins roboco offers for `harness`, in button order. Pure.
pub fn login_options(harness: HarnessId) -> Vec<LoginOption> {
    let option = |provider, label| LoginOption {
        provider: Some(provider),
        label,
    };
    match harness {
        HarnessId::Opencode => vec![
            option("openai", "ChatGPT"),
            option("github-copilot", "GitHub Copilot"),
        ],
        HarnessId::Pi => vec![option("openai-codex", "ChatGPT")],
        HarnessId::Hermes => vec![
            option("openai-codex", "ChatGPT"),
            option("nous", "Nous Portal"),
        ],
        _ => vec![LoginOption {
            provider: None,
            label: provider_name(harness),
        }],
    }
}

/// The list's last row for a single-login agent: connect the first
/// account, or add another (upstream `add_account_label`). Pure.
pub fn add_account_label(harness: HarnessId, empty: bool) -> String {
    if empty {
        format!("Connect a {} account", provider_name(harness))
    } else {
        "Add account".to_string()
    }
}

/// [`add_account_label`] for one [`LoginOption`]: a per-provider sign-in
/// names its provider ("Connect ChatGPT", "Add GitHub Copilot account").
/// The phone's add rows (upstream `add_option_label`). Pure.
pub fn add_option_label(harness: HarnessId, option: LoginOption, empty: bool) -> String {
    match option.provider {
        None => add_account_label(harness, empty),
        Some(_) if empty => format!("Connect {}", option.label),
        Some(_) => format!("Add {} account", option.label),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signs_in_covers_the_provider_table_only() {
        for (harness, _, _) in PROVIDERS {
            assert!(signs_in(harness), "{harness:?}");
        }
        assert!(!signs_in(HarnessId::Mock));
    }

    #[test]
    fn add_labels_follow_upstream() {
        let single = login_options(HarnessId::ClaudeCode)[0];
        assert_eq!(add_option_label(HarnessId::ClaudeCode, single, true), "Connect a Claude Code account");
        assert_eq!(add_option_label(HarnessId::ClaudeCode, single, false), "Add account");
        let copilot = login_options(HarnessId::Opencode)[1];
        assert_eq!(add_option_label(HarnessId::Opencode, copilot, true), "Connect GitHub Copilot");
        assert_eq!(add_option_label(HarnessId::Opencode, copilot, false), "Add GitHub Copilot account");
    }
}
