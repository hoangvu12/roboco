//! Settings → Agents / accounts (feature-inventory §1.9): provider cards
//! (Claude Code, Codex, Cursor) with account rows — email, plan badge, Active, usage
//! meters (indigo → amber ≥80% → red ≥95%, reset time), Switch / Forget — plus
//! the add-account dialogs (paste-code and browser-poll flows) and
//! account-shaped loading skeletons. Roboco retargets devices from the settings
//! sidebar (`targetDeviceId` passthrough kept plumbed, unused single-device).
//!
//! The accounts RPC surface is being implemented engine-side in parallel —
//! every call here surfaces failures as inline UI states rather than assuming
//! the methods exist.

use chrono::{DateTime, Utc};
use gpui::{
    AnyElement, Context, Entity, Hsla, SharedString, Subscription, Task, Window, div, prelude::*,
    px,
};
use std::time::Duration;

use roboco_proto::{
    AgentAccount, AgentAccountsSnapshot, AgentLoginMode, AgentLoginPoll, AgentLoginStart,
    AgentLoginStatus, AgentSignInState, HarnessId,
};
use roboco_rpc::methods;

use crate::composer::{ComposerInput, ComposerInputEvent};
use crate::popover::{self, Loadable};
use crate::settings::widgets;
use crate::state::AppState;
use crate::theme::Theme;

// ---------------------------------------------------------------------------
// Pure: usage meters + labels
// ---------------------------------------------------------------------------

pub const USAGE_WARN_FRACTION: f32 = 0.80;
pub const USAGE_CRITICAL_FRACTION: f32 = 0.95;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UsageLevel {
    /// < 80% — indigo.
    Normal,
    /// ≥ 80% — amber.
    Warn,
    /// ≥ 95% — red.
    Critical,
}

/// Threshold classification of a usage fraction. Pure.
pub fn usage_level(fraction: f32) -> UsageLevel {
    if fraction >= USAGE_CRITICAL_FRACTION {
        UsageLevel::Critical
    } else if fraction >= USAGE_WARN_FRACTION {
        UsageLevel::Warn
    } else {
        UsageLevel::Normal
    }
}

pub fn usage_color(level: UsageLevel, theme: &Theme) -> Hsla {
    match level {
        UsageLevel::Normal => theme.accent,
        UsageLevel::Warn => theme.warning,
        UsageLevel::Critical => theme.danger,
    }
}

/// Why a `ListAgentAccounts` load is happening. Pure input to
/// [`force_usage_for`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoadTrigger {
    /// Page construction — the visit's first list.
    Mount,
    /// "Click to retry" after a failed load — still the visit's first
    /// successful list.
    Retry,
    /// The explicit Refresh button.
    Refresh,
    /// After a completed add-account login flow.
    PostLogin,
    /// After Switch/Forget succeeds.
    PostAction,
}

/// Whether a load should ask the engine to probe usage (`forceUsage`). The
/// engine only hits the provider when forced; non-forced lists serve the 60s
/// usage cache or nothing (engine/src/agent_accounts.rs module docs — the
/// design expects the UI to force "on page mount/refresh"). The visit's first
/// list (mount, or retry after a failure) must force, or every first open
/// renders "Usage unavailable" until a manual Refresh — the old app fetched
/// usage on every list. Post-Switch/Forget lists ride the still-warm cache.
pub fn force_usage_for(trigger: LoadTrigger) -> bool {
    match trigger {
        LoadTrigger::Mount | LoadTrigger::Retry | LoadTrigger::Refresh | LoadTrigger::PostLogin => {
            true
        }
        LoadTrigger::PostAction => false,
    }
}

/// Compact absolute reset moment (roboco settings.agents.tsx `formatReset`):
/// a local clock time ("3:45 PM") when it lands within ~22h, a short weekday
/// ("Mon") within a week, else month + day ("Sep 14") — a weekday is noise
/// when the window is a Codex free-tier MONTHLY reset weeks out. The caller
/// prefixes "resets ". Pure given `now`.
pub fn format_reset(resets_at: Option<DateTime<Utc>>, now: DateTime<Utc>) -> Option<String> {
    use chrono::Local;
    let at = resets_at?;
    let local = at.with_timezone(&Local);
    Some(if at.signed_duration_since(now).num_hours() < 22 {
        format!("resets {}", local.format("%-I:%M %p"))
    } else if at.signed_duration_since(now).num_hours() < 24 * 7 {
        format!("resets {}", local.format("%a"))
    } else {
        format!("resets {}", local.format("%b %-d"))
    })
}

/// "Session: resets 3:45 PM · Week: resets Thu" — the usage meters' tooltip
/// (shared by the Settings page's meters and the composer popover's).
pub(crate) fn usage_resets(
    windows: &[roboco_proto::AgentUsageWindow],
    now: DateTime<Utc>,
) -> String {
    windows
        .iter()
        .map(|window| match format_reset(window.resets_at, now) {
            Some(reset) => format!("{}: {reset}", window.label),
            None => window.label.clone(),
        })
        .collect::<Vec<_>>()
        .join(" · ")
}

/// Wave-10 36: listen on this computer's loopback `port` for a remote
/// engine's sign-in redirect and relay it there (`RelayAgentLoginCallback`
/// with the login's random requester). `Err` is the "Port … is in use on
/// this computer" message.
async fn listen_for_remote_callback(
    runtime: &tokio::runtime::Handle,
    port: u16,
    engine: roboco_engine_client::registry::EngineTarget,
    login_id: String,
    requester: String,
) -> Result<roboco_engine_client::login_relay::LoginRelay, String> {
    roboco_engine_client::login_relay::listen(
        runtime,
        port,
        "computer",
        Box::new(move |path| {
            let (engine, login_id, requester) = (engine.clone(), login_id.clone(), requester.clone());
            Box::pin(async move {
                let params = serde_json::to_value(roboco_proto::RelayAgentLoginCallback {
                    login_id,
                    requester_device_id: requester,
                    path,
                })
                .map_err(|e| e.to_string())?;
                engine
                    .call_as::<roboco_proto::RelayedLoginCallback>(
                        methods::RELAY_AGENT_LOGIN_CALLBACK,
                        params,
                    )
                    .await
                    .map_err(|e| e.to_string())
            })
        }),
    )
    .await
}

// The pure sign-in rules (which agents sign in, their login options,
// switching) live in `roboco_proto::providers`, shared with the Android
// core's `agent_login_rules` (upstream #966 moved them to proto too).
pub use roboco_proto::providers::{
    LoginOption, PROVIDERS, keeps_one_login, login_options, provider_name, provider_note,
    reports_usage, signs_in, switches_accounts,
};

/// Accounts of one provider, in the engine's order (slot creation). No
/// active-first re-sort: switching accounts must not move the switched-to
/// card — the Active badge already says which one is live, and a list that
/// reshuffles under the click reads as broken. Pure.
pub fn provider_accounts(
    snapshot: &AgentAccountsSnapshot,
    harness: HarnessId,
) -> Vec<&AgentAccount> {
    snapshot
        .accounts
        .iter()
        .filter(|a| a.harness == harness)
        .collect()
}

/// The add-account button's label for one [`LoginOption`]: a per-provider
/// sign-in names its provider ("Connect ChatGPT", "Add GitHub Copilot
/// account"); single-login agents keep the plain label. Pure.
pub fn add_option_label(option: LoginOption, empty: bool) -> String {
    match option.provider {
        None => "Add account".to_string(),
        Some(_) if empty => format!("Connect {}", option.label),
        Some(_) => format!("Add {} account", option.label),
    }
}

/// The model provider a "Sign in again" for `account` signs in to: its own
/// provider group for per-provider agents, the agent's only login otherwise.
/// Pure.
pub(crate) fn sign_in_option_for(account: &AgentAccount) -> Option<&'static str> {
    login_options(account.harness)
        .into_iter()
        .find(|option| option.provider == account.provider.as_deref())
        .and_then(|option| option.provider)
}

/// The optimistic half of a switch: `account` becomes the live login of its
/// group — its agent, or for agents that keep a login per model provider,
/// that provider — and every other group keeps its own. Pure.
pub fn mark_switched(snapshot: &mut AgentAccountsSnapshot, account: &AgentAccount) {
    for row in snapshot.accounts.iter_mut() {
        if row.harness == account.harness && row.provider == account.provider {
            row.active = row.id == account.id;
        }
    }
}

// ---------------------------------------------------------------------------
// Pure: sign-in state (ticket 08)
// ---------------------------------------------------------------------------

/// Whether the account is KNOWN to be signed out — a hard rejection (the
/// slot's refresh was refused, a revoked key) or a run's signed-out error
/// marked it. Ambiguous "session expired" and never-probed accounts are
/// `Unknown`, not signed out. Pure.
pub fn signed_out(account: &AgentAccount) -> bool {
    account.sign_in_state == AgentSignInState::SignedOut
}

/// The reason line an account row shows: the last probe's failure, or —
/// when a run error marked the account signed out with no probe behind it —
/// the sign-out itself. `None` when the account has nothing to complain
/// about. Shown BESIDE stale meters, never only when they are gone (a
/// cached meter used to hide the sign-out). Pure.
pub fn account_note(account: &AgentAccount) -> Option<String> {
    account
        .usage_error
        .clone()
        .or_else(|| signed_out(account).then(|| "Signed out — sign in again".to_string()))
}

/// The warning a switch to a signed-out account asks about first: the
/// account may not work until it is signed in again. Pure.
pub fn signed_out_switch_warning(account: &AgentAccount) -> String {
    let who = account
        .email
        .clone()
        .or_else(|| account.display_name.clone())
        .unwrap_or_else(|| "This account".into());
    format!(
        "{who} is signed out. Switching to it now may not work — sign in \
         again first, or switch anyway and sign in after."
    )
}

/// The red-tinged "Signed out" pill a signed-out row carries — the mark
/// that keeps a dead login from reading healthy beside green meters.
pub(crate) fn badge_signed_out(theme: &Theme) -> gpui::Div {
    let danger = theme.danger;
    let danger_text = theme.danger_muted;
    div()
        .flex_none()
        .px(px(8.0))
        .py(px(2.0))
        .rounded_full()
        .bg(danger.opacity(0.12))
        .text_size(crate::typography::ui_rems(10.5))
        .text_color(danger_text.opacity(0.9))
        .child(SharedString::from("Signed out"))
}

/// Usage meter columns (upstream accounts.rs): window label, a short bar,
/// percent — the compact one-line meter the footer's account-usage card
/// renders (the page's rows keep the pre-redesign meter until ticket 18).
const USAGE_LABEL_WIDTH: f32 = 52.0;
const USAGE_BAR_WIDTH: f32 = 88.0;
const USAGE_PERCENT_WIDTH: f32 = 34.0;

/// One mini meter line of the usage column: label, a short bar, percent.
/// The reset moment rides the row's tooltip instead of taking a column.
pub(crate) fn render_usage_meter(
    window: &roboco_proto::AgentUsageWindow,
    theme: &Theme,
) -> AnyElement {
    let fraction = window.used_fraction.clamp(0.0, 1.0);
    let level = usage_level(fraction);
    let fill = usage_color(level, theme).opacity(match level {
        UsageLevel::Normal => 0.8,
        _ => 0.9,
    });
    div()
        .h(px(16.0))
        .flex()
        .flex_row()
        .items_center()
        .gap(px(8.0))
        .text_size(crate::typography::ui_rems(11.5))
        .child(
            div()
                .w(px(USAGE_LABEL_WIDTH))
                .flex_none()
                .truncate()
                .text_color(theme.text_muted)
                .child(SharedString::from(window.label.clone())),
        )
        .child(
            div()
                .w(px(USAGE_BAR_WIDTH))
                .flex_none()
                .h(px(4.0))
                .rounded_full()
                .overflow_hidden()
                .bg(theme.wash(0.08))
                .when(fraction > 0.0, |el| {
                    el.child(
                        div()
                            .h_full()
                            // A 1.5% floor keeps tiny non-zero usage
                            // visible (upstream zeron: `max(used, 1.5)%`).
                            .w(gpui::relative(fraction.max(0.015)))
                            .rounded_full()
                            .bg(fill),
                    )
                }),
        )
        .child(
            div()
                .w(px(USAGE_PERCENT_WIDTH))
                .flex_none()
                .text_right()
                .text_color(match level {
                    UsageLevel::Normal => theme.text_muted,
                    _ => usage_color(level, theme),
                })
                .child(SharedString::from(format!(
                    "{}%",
                    (fraction * 100.0).round() as u32
                ))),
        )
        .into_any_element()
}

/// The sign-in dialog's browser-wait copy — one sentence per provider, same
/// shape. Pure.
fn login_copy(harness: HarnessId, provider: Option<&str>) -> &'static str {
    match (harness, provider) {
        (HarnessId::ClaudeCode, _) => {
            "Finish signing in to Claude in your browser. The new login is saved next to \
             your current one — nothing changes until you switch."
        }
        (HarnessId::Codex, _) => {
            "Finish signing in to ChatGPT in your browser. The new login is saved next to \
             your current one — nothing changes until you switch."
        }
        (HarnessId::Cursor, _) => {
            "Finish signing in to Cursor in your browser. This mints a roboco-named API key \
             you can revoke any time from Cursor's dashboard — it is separate from \
             `cursor-agent login`."
        }
        (HarnessId::Grok, _) => {
            "Finish signing in to Grok in your browser — approve the code shown below. The \
             new login is saved next to your current one — nothing changes until you switch."
        }
        (HarnessId::Devin, _) => {
            "Finish signing in to Devin in your browser. The new login is saved next to your \
             current one — nothing changes until you switch."
        }
        (HarnessId::Opencode, Some("github-copilot")) => {
            "Finish signing in to GitHub in your browser — enter the code shown below. The \
             new login is saved next to your current one — nothing changes until you switch."
        }
        (HarnessId::Opencode | HarnessId::Pi, _) => {
            "Finish signing in to ChatGPT in your browser. The agent gets its own login, saved \
             next to any current one — nothing changes until you switch."
        }
        (HarnessId::Hermes, _) => {
            "Finish signing in in your browser — enter the code shown below. Hermes adds the \
             login to its own credential pool and rotates through it itself."
        }
        (HarnessId::Antigravity, _) => {
            "Finish signing in to Google in your browser. Antigravity keeps one login on \
             this device; if it is already signed in, this just confirms it."
        }
        _ => "Finish signing in in your browser.",
    }
}

// ---------------------------------------------------------------------------
// Entity
// ---------------------------------------------------------------------------

/// The last accounts list per target device (`None` = this device), shared
/// by every accounts view (the page and the composer footer's usage ring)
/// so a switch in either shows up in the other.
#[derive(Default)]
pub(crate) struct AccountsSnapshotCache(
    pub(crate) std::collections::HashMap<Option<String>, AgentAccountsSnapshot>,
);

impl gpui::Global for AccountsSnapshotCache {}

enum LoginFlow {
    /// StartAgentLogin in flight.
    Starting {
        harness: HarnessId,
        /// The model provider signed in to, for per-provider agents.
        provider: Option<&'static str>,
    },
    /// Claude-style: open the URL, paste the code back.
    PasteCode {
        harness: HarnessId,
        provider: Option<&'static str>,
        start: AgentLoginStart,
        submitting: bool,
        error: Option<SharedString>,
    },
    /// Codex-style: open the URL, poll until the browser flow lands.
    Browser {
        harness: HarnessId,
        provider: Option<&'static str>,
        start: AgentLoginStart,
        message: Option<SharedString>,
        error: Option<SharedString>,
    },
}

impl LoginFlow {
    fn parts(&self) -> (HarnessId, Option<&'static str>) {
        match self {
            LoginFlow::Starting { harness, provider }
            | LoginFlow::PasteCode {
                harness, provider, ..
            }
            | LoginFlow::Browser {
                harness, provider, ..
            } => (*harness, *provider),
        }
    }

    /// Dialog title (roboco: "Add Claude account" / "Add Codex account"); a
    /// per-provider sign-in names who it is for ("Sign in to ChatGPT for
    /// OpenCode").
    fn title(&self) -> String {
        let (harness, provider) = self.parts();
        let option = login_options(harness)
            .into_iter()
            .find(|option| option.provider == provider);
        match option {
            Some(LoginOption {
                provider: Some(_),
                label,
            }) => format!("Sign in to {label} for {}", provider_name(harness)),
            _ => match harness {
                HarnessId::Codex => "Add Codex account".into(),
                HarnessId::Cursor => "Connect Cursor".into(),
                // The long-standing dialog title (roboco's grammar).
                HarnessId::ClaudeCode => "Add Claude account".into(),
                other => format!("Add {} account", provider_name(other)),
            },
        }
    }
}

pub struct AccountsPage {
    state: Entity<AppState>,
    scroll: widgets::PageScroll,
    /// Which device's logins are shown; `None` = this device (no passthrough).
    /// Retargeted by the page-header device switcher (roboco parity: the
    /// accounts RPCs are relay-forwardable, CLI logins are per-device).
    target_device: Option<String>,
    device_menu: popover::Popup<()>,
    snapshot: Loadable<AgentAccountsSnapshot>,
    /// Account id with an in-flight Switch/Forget.
    busy_account: Option<String>,
    /// A signed-out account whose Switch is asking for confirmation before
    /// it becomes the CLI's login.
    confirm_switch: Option<AgentAccount>,
    login: Option<LoginFlow>,
    error: Option<SharedString>,
    code_input: Entity<ComposerInput>,
    load_task: Option<Task<()>>,
    action_task: Option<Task<()>>,
    poll_task: Option<Task<()>>,
    /// Wave-10 36: a loopback sign-in started on a remote (fleet) engine —
    /// the listener on this computer's loopback relaying the browser's
    /// redirect to that engine. Dropped with the login.
    login_relay: Option<roboco_engine_client::login_relay::LoginRelay>,
    /// The current login's random requester (a remote engine only), for a
    /// loopback page that arrives with a poll (Antigravity).
    login_requester: Option<String>,
    /// The page a poll brought (opened once).
    login_late_url: Option<String>,
    _observe: Subscription,
    _code_events: Subscription,
}

impl AccountsPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        let code_input = cx.new(|cx| ComposerInput::new("Paste the authorization code", cx));
        let code_events = cx.subscribe(&code_input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.submit_code(cx);
            }
        });
        let mut page = Self {
            state,
            scroll: widgets::PageScroll::default(),
            target_device: None,
            device_menu: popover::Popup::default(),
            snapshot: Loadable::Idle,
            busy_account: None,
            confirm_switch: None,
            login: None,
            error: None,
            code_input,
            load_task: None,
            action_task: None,
            poll_task: None,
            login_relay: None,
            login_requester: None,
            login_late_url: None,
            _observe: observe,
            _code_events: code_events,
        };
        // Force the usage probe on the visit's first list — a plain list
        // returns no usage windows on a cold engine cache, which rendered
        // every account as "Usage unavailable" until a manual Refresh. The
        // Loading skeleton (meter ghosts) covers the probe latency, so
        // "Usage unavailable" is reserved for a probe that genuinely failed.
        page.load(force_usage_for(LoadTrigger::Mount), cx);
        page
    }

    /// Retarget the page at another device's logins: every accounts RPC is
    /// relay-forwardable, so the whole page — list, usage probes, switch,
    /// forget, login flows — follows the passthrough.
    fn close_device_menu(&mut self, cx: &mut Context<Self>) {
        if self.device_menu.begin_close() {
            popover::reap_popup(cx, |page: &mut Self| &mut page.device_menu);
            cx.notify();
        }
    }

    fn set_target_device(&mut self, target: Option<String>, cx: &mut Context<Self>) {
        self.close_device_menu(cx);
        if self.target_device == target {
            cx.notify();
            return;
        }
        self.target_device = target;
        // A different device = a different accounts world: drop in-flight
        // login/action state and reload with a forced usage probe (the new
        // device's cache is cold).
        self.login = None;
        self.login_relay = None;
        self.busy_account = None;
        self.confirm_switch = None;
        self.error = None;
        self.load(force_usage_for(LoadTrigger::Mount), cx);
    }

    /// Params with the `targetDeviceId` passthrough merged in.
    fn params(&self, value: serde_json::Value) -> serde_json::Value {
        let mut value = value;
        if let (Some(target), Some(object)) = (&self.target_device, value.as_object_mut()) {
            object.insert("targetDeviceId".into(), serde_json::json!(target));
        }
        value
    }

    fn on_scroll_hovered(&mut self, hovered: &bool, _: &mut Window, cx: &mut Context<Self>) {
        if self.scroll.set_list_hovered(*hovered) {
            cx.notify();
        }
    }

    /// The page-header device switcher (roboco device-switcher.tsx): a quiet
    /// trigger — platform glyph · name · presence dot · sort glyph — opening a
    /// dropdown of every registered device. Selecting one retargets the page.
    fn render_device_switcher(&mut self, theme: &Theme, cx: &mut Context<Self>) -> AnyElement {
        use crate::icons::{self, icon};
        let (mut devices, local_id) = {
            let s = self.state.read(cx);
            (s.devices.clone(), s.local_device_id.clone())
        };
        // Stable row order (registration time, then id) — roboco's switcher
        // sorts the same way so rows never reshuffle on heartbeats.
        devices.sort_by(|a, b| {
            a.created_at
                .cmp(&b.created_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        let effective = self.target_device.clone().or_else(|| local_id.clone());
        let selected = devices
            .iter()
            .find(|d| Some(d.id.as_str()) == effective.as_deref())
            .cloned();
        let platform_glyph = |platform: &str| match platform {
            "macos" | "darwin" => icons::LAPTOP,
            "ios" | "android" => icons::SMARTPHONE,
            _ => icons::MONITOR,
        };
        let trigger_glyph = platform_glyph(
            selected
                .as_ref()
                .map(|d| d.platform.as_str())
                .unwrap_or("macos"),
        );
        let trigger_label: SharedString = selected
            .as_ref()
            .map(|d| d.name.clone().into())
            .unwrap_or_else(|| SharedString::from("This device"));
        let emerald = theme.success;
        let open = self.device_menu.is_open();

        let mut trigger =
            div()
                .id("accounts-device-switcher")
                .relative()
                .flex_none()
                .h(px(28.0))
                .px(px(8.0))
                .rounded(px(6.0))
                .flex()
                .flex_row()
                .items_center()
                .gap(px(6.0))
                .cursor_pointer()
                .bg(if open {
                    crate::theme::ink(0.06)
                } else {
                    gpui::transparent_black()
                })
                .when(!open, |el| el.hover(|s| s.bg(crate::theme::ink(0.04))))
                .on_mouse_down(
                    gpui::MouseButton::Left,
                    cx.listener(|this, _, _, _| this.device_menu.note_trigger_press()),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    // A press that found the menu open closes it (the card's
                    // mouse-down-out already began the close) — never reopen.
                    if this.device_menu.take_press_was_open() {
                        this.close_device_menu(cx);
                    } else {
                        this.device_menu.open(());
                    }
                    cx.notify();
                }))
                .child(
                    icon(trigger_glyph)
                        .size(px(16.0))
                        .flex_none()
                        .text_color(theme.text_muted),
                )
                .child(
                    div()
                        .min_w_0()
                        .truncate()
                        .text_size(crate::typography::ui_rems(12.5))
                        .font_weight(gpui::FontWeight::MEDIUM)
                        .text_color(theme.text)
                        .child(trigger_label),
                )
                .child(div().size(px(6.0)).rounded_full().flex_none().bg(
                    if effective == local_id {
                        emerald
                    } else {
                        crate::theme::ink(0.2)
                    },
                ))
                .child(
                    icon(icons::SORT_VERTICAL)
                        .size(px(14.0))
                        .flex_none()
                        .text_color(theme.text_muted.opacity(if open { 0.9 } else { 0.4 })),
                );

        if self.device_menu.get().is_some() {
            let closing = self.device_menu.closing_since();
            let rows: Vec<AnyElement> = devices
                .into_iter()
                .enumerate()
                .map(|(ix, d)| {
                    let is_active = Some(d.id.as_str()) == effective.as_deref();
                    let is_local = local_id.as_deref() == Some(d.id.as_str());
                    let glyph = platform_glyph(&d.platform);
                    let name: SharedString = d.name.clone().into();
                    let pick_local = is_local;
                    let pick_id = d.id.clone();
                    popover::menu_row(theme, is_active, format!("accounts-device-row-{ix}"))
                        .id(("accounts-device-row", ix))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            // Local device = no passthrough (calls stay direct).
                            let target = (!pick_local).then(|| pick_id.clone());
                            this.set_target_device(target, cx);
                        }))
                        .child(
                            icon(glyph)
                                .size(px(16.0))
                                .flex_none()
                                .text_color(theme.text_muted),
                        )
                        .child(div().flex_1().min_w_0().truncate().child(name))
                        .when(is_local, |el| {
                            el.child(
                                div()
                                    .flex_none()
                                    .text_size(crate::typography::ui_rems(10.5))
                                    .text_color(theme.text_muted.opacity(0.35))
                                    .child(SharedString::from("You")),
                            )
                        })
                        .child(
                            div()
                                .size(px(6.0))
                                .rounded_full()
                                .flex_none()
                                .bg(if is_local {
                                    emerald
                                } else {
                                    crate::theme::ink(0.2)
                                }),
                        )
                        .into_any_element()
                })
                .collect();
            let menu = popover::popover_card(theme)
                .w(px(220.0))
                .on_mouse_down_out(cx.listener(|this, _, _, cx| {
                    this.close_device_menu(cx);
                }))
                .flex()
                .flex_col()
                .child(popover::menu_heading(theme, "Devices"))
                // Contained to the settings page pane, flipping above the
                // trigger when the lower half is too short; long device
                // lists scroll inside the same budget.
                .child(widgets::dropdown_rows(
                    "accounts-device-rows",
                    rows,
                    32.0,
                ));
            trigger = trigger.child(widgets::dropdown(
                "accounts-device-menu",
                menu,
                closing,
                28.0,
            ));
        }
        trigger.into_any_element()
    }

    fn load(&mut self, force_usage: bool, cx: &mut Context<Self>) {
        let Some(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        )
        .ok() else {
            self.snapshot = Loadable::Error("Engine not connected".into());
            return;
        };
        // Stale-while-revalidate: a painted snapshot stays on screen while
        // the list runs (the footer's usage ring shares the same cache, so
        // a switch there shows up here on the next reload).
        let key = self.target_device.clone();
        if !matches!(self.snapshot, Loadable::Ready(_)) {
            self.snapshot = match cx
                .try_global::<AccountsSnapshotCache>()
                .and_then(|cache| cache.0.get(&key))
            {
                Some(cached) => Loadable::Ready(cached.clone()),
                None => Loadable::Loading,
            };
        }
        let params = self.params(serde_json::json!({ "forceUsage": force_usage }));
        self.load_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::LIST_AGENT_ACCOUNTS, params).await;
            this.update(cx, |page, cx| {
                page.snapshot = match result {
                    Ok(value) => match serde_json::from_value::<AgentAccountsSnapshot>(value) {
                        Ok(snapshot) => {
                            cx.default_global::<AccountsSnapshotCache>()
                                .0
                                .insert(key, snapshot.clone());
                            Loadable::Ready(snapshot)
                        }
                        Err(err) => Loadable::Error(err.to_string()),
                    },
                    Err(err) => Loadable::Error(err.to_string()),
                };
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// Switch / Forget an account.
    fn account_action(
        &mut self,
        method: &'static str,
        account: &AgentAccount,
        cx: &mut Context<Self>,
    ) {
        let Some(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        )
        .ok() else {
            return;
        };
        self.busy_account = Some(account.id.clone());
        self.error = None;
        // Tolerant param shape: both `id` and `accountId` plus the harness.
        let params = self.params(serde_json::json!({
            "id": account.id,
            "accountId": account.id,
            "harness": account.harness,
        }));
        self.action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(method, params).await;
            this.update(cx, |page, cx| {
                page.busy_account = None;
                match result {
                    Ok(_) => page.load(force_usage_for(LoadTrigger::PostAction), cx),
                    Err(err) => page.error = Some(format!("{err}").into()),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    // ---- add-account flows ----

    /// Sign in with the provider's first option (a failed run's "Log in").
    pub(crate) fn start_first_login(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        let provider = login_options(harness)
            .first()
            .and_then(|option| option.provider);
        self.start_login(harness, provider, cx);
    }

    fn start_login(
        &mut self,
        harness: HarnessId,
        provider: Option<&'static str>,
        cx: &mut Context<Self>,
    ) {
        let Some(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        )
        .ok() else {
            return;
        };
        self.login = Some(LoginFlow::Starting { harness, provider });
        self.login_relay = None;
        self.error = None;
        // Wave-10 36: a login on another (fleet) engine redirects to THAT
        // computer's loopback, but the browser runs here. Name a fresh
        // random requester (only this page can relay the callback) and
        // relay the redirect this computer's browser lands on.
        let remote = {
            let state = self.state.read(cx);
            let local = state.local_target().ok().map(|target| target.key().clone());
            local.as_ref() != Some(engine.key())
        };
        let requester = remote.then(|| uuid::Uuid::new_v4().to_string());
        self.login_requester = requester.clone();
        self.login_late_url = None;
        let runtime = self.state.read(cx).registry().map(|registry| registry.runtime_handle());
        let mut params = serde_json::json!({ "harness": harness });
        if let (Some(provider), Some(object)) = (provider, params.as_object_mut()) {
            object.insert("provider".into(), serde_json::json!(provider));
        }
        if let (Some(requester), Some(object)) = (&requester, params.as_object_mut()) {
            object.insert("requesterDeviceId".into(), serde_json::json!(requester));
        }
        let params = self.params(params);
        let relay_engine = engine.clone();
        self.action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::START_AGENT_LOGIN, params).await;
            let result = result.and_then(|value| {
                serde_json::from_value::<AgentLoginStart>(value)
                    .map_err(|e| roboco_rpc::RpcError::Failed(e.to_string()))
            });
            // The relay listener, for a loopback login on a remote engine.
            let relay = match (&result, requester, runtime) {
                (Ok(start), Some(requester), Some(runtime))
                    if start.mode == AgentLoginMode::Browser =>
                {
                    match start.callback_port {
                        Some(port) => Some(
                            listen_for_remote_callback(
                                &runtime,
                                port,
                                relay_engine,
                                start.login_id.clone(),
                                requester,
                            )
                            .await,
                        ),
                        None => None,
                    }
                }
                _ => None,
            };
            this.update(cx, |page, cx| {
                let result = match (result, relay) {
                    (Ok(start), Some(Err(busy))) => {
                        // This computer can't catch the redirect: end the
                        // sign-in on the remote engine and say why.
                        let params = page.params(serde_json::json!({ "loginId": start.login_id }));
                        if let Ok(engine) = crate::request_routing::device_target(
                            page.state.read(cx),
                            page.target_device.as_deref(),
                        ) {
                            cx.spawn(async move |_, _| {
                                let _ = engine.call(methods::CANCEL_AGENT_LOGIN, params).await;
                            })
                            .detach();
                        }
                        Err(roboco_rpc::RpcError::Failed(busy))
                    }
                    (Ok(start), Some(Ok(relay))) => {
                        page.login_relay = Some(relay);
                        Ok(start)
                    }
                    (result, _) => result,
                };
                match result {
                    Ok(start) => {
                        if !start.cli_opens_browser {
                            cx.open_url(&start.url);
                        }
                        match start.mode {
                            AgentLoginMode::PasteCode => {
                                page.code_input
                                    .update(cx, |input, cx| input.set_text("", cx));
                                page.login = Some(LoginFlow::PasteCode {
                                    harness,
                                    provider,
                                    start,
                                    submitting: false,
                                    error: None,
                                });
                            }
                            AgentLoginMode::Browser => {
                                page.login = Some(LoginFlow::Browser {
                                    harness,
                                    provider,
                                    start,
                                    message: None,
                                    error: None,
                                });
                                page.spawn_poll(cx);
                            }
                        }
                    }
                    Err(err) => {
                        page.login = None;
                        page.login_relay = None;
                        page.error = Some(format!("Login failed to start: {err}").into());
                    }
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn submit_code(&mut self, cx: &mut Context<Self>) {
        let Some(LoginFlow::PasteCode {
            start, submitting, ..
        }) = &mut self.login
        else {
            return;
        };
        if *submitting {
            return;
        }
        let code = self.code_input.read(cx).text().trim().to_string();
        if code.is_empty() {
            return;
        }
        let login_id = start.login_id.clone();
        *submitting = true;
        let Some(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        )
        .ok() else {
            return;
        };
        let params = self.params(serde_json::json!({ "loginId": login_id, "code": code }));
        self.action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::COMPLETE_AGENT_LOGIN, params).await;
            this.update(cx, |page, cx| {
                match result {
                    Ok(_) => {
                        page.login = None;
                        page.login_relay = None;
                        page.load(force_usage_for(LoadTrigger::PostLogin), cx);
                    }
                    Err(err) => {
                        if let Some(LoginFlow::PasteCode {
                            submitting, error, ..
                        }) = &mut page.login
                        {
                            *submitting = false;
                            *error = Some(format!("{err}").into());
                        }
                    }
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// The browser-wait poll loop: PollAgentLogin every 1.5s until Done/Error.
    fn spawn_poll(&mut self, cx: &mut Context<Self>) {
        let Some(LoginFlow::Browser { start, .. }) = &self.login else {
            return;
        };
        let login_id = start.login_id.clone();
        let Some(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        )
        .ok() else {
            return;
        };
        let params = self.params(serde_json::json!({ "loginId": login_id }));
        self.poll_task = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(1500))
                    .await;
                let result = engine.call(methods::POLL_AGENT_LOGIN, params.clone()).await;
                let outcome = this.update(cx, |page, cx| {
                    let Some(LoginFlow::Browser { message, error, .. }) = &mut page.login else {
                        return true; // dialog dismissed — stop polling
                    };
                    match result.as_ref().ok().and_then(|value| {
                        serde_json::from_value::<AgentLoginPoll>(value.clone()).ok()
                    }) {
                        Some(poll) => match poll.status {
                            AgentLoginStatus::Done => {
                                page.login = None;
                                page.login_relay = None;
                                page.load(force_usage_for(LoadTrigger::PostLogin), cx);
                                cx.notify();
                                true
                            }
                            AgentLoginStatus::Error => {
                                *error = Some(
                                    poll.message
                                        .unwrap_or_else(|| "Login failed".to_string())
                                        .into(),
                                );
                                cx.notify();
                                true
                            }
                            AgentLoginStatus::Pending => {
                                if let Some(text) = poll.message {
                                    *message = Some(text.into());
                                }
                                // A page learned after the start (Antigravity
                                // prints its own once its server is up): open
                                // it once — and on a remote engine relay its
                                // loopback redirect, like a start reply's
                                // (wave-10 36).
                                if let Some(url) = poll
                                    .url
                                    .filter(|url| page.login_late_url.as_ref() != Some(url))
                                {
                                    cx.open_url(&url);
                                    page.login_late_url = Some(url);
                                    if let Some(port) = poll.callback_port {
                                        page.relay_late_callback(port, cx);
                                    }
                                }
                                cx.notify();
                                false
                            }
                        },
                        None => {
                            let text = match &result {
                                Err(err) => format!("Poll failed: {err}"),
                                Ok(_) => "Poll failed: malformed reply".to_string(),
                            };
                            *error = Some(text.into());
                            cx.notify();
                            true
                        }
                    }
                });
                match outcome {
                    Ok(true) | Err(_) => break,
                    Ok(false) => {}
                }
            }
        }));
    }

    /// Wave-10 36: a loopback page that arrived with a poll on a remote
    /// engine — listen here (once), like a start reply's port. A busy port
    /// ends the sign-in and says why, as at the start.
    fn relay_late_callback(&mut self, port: u16, cx: &mut Context<Self>) {
        if self.login_relay.is_some() {
            return;
        }
        let (Some(requester), Some(LoginFlow::Browser { start, .. })) =
            (self.login_requester.clone(), &self.login)
        else {
            return;
        };
        let login_id = start.login_id.clone();
        let Ok(engine) = crate::request_routing::device_target(
            self.state.read(cx),
            self.target_device.as_deref(),
        ) else {
            return;
        };
        let Some(runtime) = self
            .state
            .read(cx)
            .registry()
            .map(|registry| registry.runtime_handle())
        else {
            return;
        };
        cx.spawn(async move |this, cx| {
            let relay =
                listen_for_remote_callback(&runtime, port, engine, login_id.clone(), requester)
                    .await;
            this.update(cx, |page, cx| {
                let current = matches!(
                    &page.login,
                    Some(LoginFlow::Browser { start, .. }) if start.login_id == login_id
                );
                if !current {
                    return;
                }
                match relay {
                    Ok(relay) => page.login_relay = Some(relay),
                    Err(busy) => {
                        page.cancel_login(cx);
                        page.error = Some(format!("Login failed: {busy}").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn cancel_login(&mut self, cx: &mut Context<Self>) {
        let login_id = match &self.login {
            Some(LoginFlow::PasteCode { start, .. }) | Some(LoginFlow::Browser { start, .. }) => {
                Some(start.login_id.clone())
            }
            _ => None,
        };
        self.login = None;
        self.login_relay = None;
        self.poll_task = None;
        if let (Some(login_id), Some(engine)) = (
            login_id,
            crate::request_routing::device_target(
                self.state.read(cx),
                self.target_device.as_deref(),
            )
            .ok(),
        ) {
            let params = self.params(serde_json::json!({ "loginId": login_id }));
            self.action_task = Some(cx.spawn(async move |_, _| {
                if let Err(err) = engine.call(methods::CANCEL_AGENT_LOGIN, params).await {
                    tracing::debug!(error = %err, "CancelAgentLogin failed (best-effort)");
                }
            }));
        }
        cx.notify();
    }

    /// Escape that reached Settings unclaimed cancels an open login first,
    /// so it never closes Settings under the dialog. Returns whether it did.
    pub(crate) fn dismiss_on_escape(&mut self, cx: &mut Context<Self>) -> bool {
        if self.confirm_switch.is_some() {
            self.confirm_switch = None;
            cx.notify();
            return true;
        }
        if self.login.is_none() {
            return false;
        }
        self.cancel_login(cx);
        true
    }

    // ---- render pieces ----

    /// One usage window (roboco settings.agents.tsx `UsageMeter`): label ·
    /// 5px rounded-full bar (indigo → amber ≥80% → red ≥95%) · "NN% used" ·
    /// quiet reset time.
    fn render_usage_meter(
        &self,
        window: &roboco_proto::AgentUsageWindow,
        theme: &Theme,
        now: DateTime<Utc>,
    ) -> AnyElement {
        let fraction = window.used_fraction.clamp(0.0, 1.0);
        let level = usage_level(fraction);
        let fill = usage_color(level, theme).opacity(match level {
            UsageLevel::Normal => 0.8,
            _ => 0.85,
        });
        let reset = format_reset(window.resets_at, now);
        div()
            .flex()
            .flex_row()
            .items_center()
            .gap(px(8.0))
            .text_size(crate::typography::ui_rems(11.5))
            .text_color(theme.text_muted.opacity(0.7))
            .child(
                div()
                    .w(px(48.0))
                    .flex_none()
                    .truncate()
                    .child(SharedString::from(window.label.clone())),
            )
            .child(
                div()
                    .flex_1()
                    .min_w(px(56.0))
                    .max_w(px(230.0))
                    .h(px(5.0))
                    .rounded_full()
                    .overflow_hidden()
                    .bg(crate::theme::ink(0.07))
                    .when(fraction > 0.0, |el| {
                        el.child(
                            div()
                                .h_full()
                                // A 1.5% floor keeps tiny non-zero usage
                                // visible (roboco `max(used, 1.5)%`).
                                .w(gpui::relative(fraction.max(0.015)))
                                .rounded_full()
                                .bg(fill),
                        )
                    }),
            )
            .child(
                div()
                    .w(px(64.0))
                    .flex_none()
                    .text_right()
                    .child(SharedString::from(format!(
                        "{}% used",
                        (fraction * 100.0).round() as u32
                    ))),
            )
            .when_some(reset, |el, reset| {
                el.child(
                    div()
                        .flex_none()
                        .truncate()
                        .text_color(theme.text_muted.opacity(0.45))
                        .child(SharedString::from(reset)),
                )
            })
            .into_any_element()
    }

    /// One account row (roboco settings.agents.tsx `AccountRow`): initial
    /// avatar, email + usage meters left; badges over the Switch/Forget
    /// actions right-anchored.
    fn render_account_row(
        &self,
        account: &AgentAccount,
        ix: usize,
        first: bool,
        theme: &Theme,
        now: DateTime<Utc>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let is_busy = self.busy_account.as_deref() == Some(account.id.as_str());
        let refreshing = matches!(self.snapshot, Loadable::Loading);
        let email: SharedString = account
            .email
            .clone()
            .or_else(|| account.display_name.clone())
            .unwrap_or_else(|| "Unknown account".into())
            .into();
        let initial: SharedString = email
            .chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .unwrap_or_else(|| "?".into())
            .into();
        let switch_account = account.clone();
        let forget_account = account.clone();
        let sign_in_account = account.clone();
        // A signed-out account — marked, offered "Sign in again", and asked
        // about before a Switch lands on it.
        let is_signed_out = signed_out(account);

        let badges = div()
            .flex()
            .flex_row()
            .items_center()
            .gap(px(6.0))
            .when(account.active, |el| {
                el.child(widgets::badge_active(theme, "Active"))
            })
            .when_some(account.plan_label.clone(), |el, plan| {
                el.child(widgets::badge(theme, plan))
            })
            .when(is_signed_out, |el| el.child(badge_signed_out(theme)));

        // Actions on INACTIVE accounts (roboco `{!account.active && …}`):
        // an icon-only Forget (trash, hover → foreground) then Switch, which
        // reads "Switching…" while the activate round-trips — plus "Sign in
        // again" on any SIGNED-OUT row (active or not): it re-runs the
        // provider's login flow for this account's provider group.
        let actions: Option<gpui::Div> =
            ((!account.active) || is_signed_out).then(|| {
            div()
                .flex()
                .flex_row()
                .items_center()
                .gap(px(4.0))
                .when(!account.active, |el| {
                    el.child(
                        div()
                            .id(("account-forget", ix))
                            .rounded(px(6.0))
                            .px(px(6.0))
                            .py(px(4.0))
                            .text_color(theme.text_muted)
                            .cursor_pointer()
                            .when(is_busy, |el| el.opacity(0.5))
                            .hover(|s| s.bg(crate::theme::ink(0.06)).text_color(theme.text))
                            .tooltip(widgets::text_tooltip("Forget account"))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.account_action(
                                    methods::FORGET_AGENT_ACCOUNT,
                                    &forget_account,
                                    cx,
                                );
                            }))
                            .child(
                                crate::icons::icon(crate::icons::TRASH_BIN_MINIMALISTIC)
                                    .size(px(14.0))
                                    .text_color(theme.text_muted),
                            ),
                    )
                })
                .when(!account.active && account.switchable, |el| {
                    el.child(
                        crate::popover::btn_primary(
                            theme,
                            if is_busy { "Switching…" } else { "Switch" },
                        )
                        .id(("account-switch", ix))
                        .px(px(8.0))
                        .py(px(4.0))
                        .rounded(px(6.0))
                        .text_size(crate::typography::ui_rems(11.5))
                        .when(is_busy, |el| el.opacity(0.5))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            // A signed-out switch asks first: the account may
                            // not work until it is signed in again.
                            if signed_out(&switch_account) {
                                this.confirm_switch = Some(switch_account.clone());
                                cx.notify();
                            } else {
                                this.account_action(
                                    methods::ACTIVATE_AGENT_ACCOUNT,
                                    &switch_account,
                                    cx,
                                );
                            }
                        })),
                    )
                })
                .when(is_signed_out, |el| {
                    el.child(
                        crate::popover::btn_primary(theme, "Sign in again")
                            .id(("account-sign-in-again", ix))
                            .px(px(8.0))
                            .py(px(4.0))
                            .rounded(px(6.0))
                            .text_size(crate::typography::ui_rems(11.5))
                            .when(is_busy, |el| el.opacity(0.5))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.start_login(
                                    sign_in_account.harness,
                                    sign_in_option_for(&sign_in_account),
                                    cx,
                                );
                            })),
                    )
                })
        });

        div()
            .px(px(20.0))
            .py(px(14.0))
            .when(!first, |el| el.border_t_1().border_color(theme.border))
            .flex()
            .flex_row()
            .items_stretch()
            .gap(px(12.0))
            .child(
                // Initial avatar: size-8 rounded-full border bg-white/[0.03].
                div()
                    .flex_none()
                    .self_center()
                    .size(px(32.0))
                    .rounded_full()
                    .border_1()
                    .border_color(theme.border)
                    .bg(crate::theme::ink(0.03))
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(crate::typography::ui_rems(12.0))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .text_color(theme.text_muted)
                    .child(initial),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(widgets::row_title(theme, email))
                    .map(|el| {
                        // Meters XOR the quiet fallback line — never both
                        // (roboco: `usage ? meters : "Usage unavailable"…`).
                        // The engine's probe reason replaces the bare shrug;
                        // an unidentified login says so. A SIGNED-OUT account
                        // keeps its reason beside the meters (the display
                        // fix: stale meters used to hide the sign-out).
                        if account.usage_windows.is_empty() {
                            let note = if !account.switchable && switches_accounts(account.harness)
                            {
                                match account.harness {
                                    // Keychain denied: the login is there, its secret isn't.
                                    HarnessId::ClaudeCode => "Credentials unavailable".to_string(),
                                    // An opaque token whose account couldn't be looked up.
                                    _ => "Couldn't identify this login".to_string(),
                                }
                            } else if let Some(reason) = account_note(account) {
                                // "Rate limited by Anthropic — retrying in 2m",
                                // "Signed out — sign in again".
                                reason
                            } else if refreshing {
                                "Checking usage…".to_string()
                            } else {
                                "Usage unavailable".to_string()
                            };
                            el.child(
                                div()
                                    .mt(px(6.0))
                                    .truncate()
                                    .text_size(crate::typography::ui_rems(11.5))
                                    .text_color(if is_signed_out {
                                        theme.danger_muted.opacity(0.85)
                                    } else {
                                        theme.text_muted.opacity(0.6)
                                    })
                                    .child(SharedString::from(note)),
                            )
                        } else {
                            el.child(
                                div().mt(px(6.0)).flex().flex_col().gap(px(4.0))
                                    .children(
                                        account
                                            .usage_windows
                                            .iter()
                                            .map(|w| self.render_usage_meter(w, theme, now)),
                                    )
                                    // The reason line rides BELOW the meters,
                                    // so a cached meter no longer hides it.
                                    .when_some(account_note(account), |el, reason| {
                                        el.child(
                                            div()
                                                .truncate()
                                                .text_size(crate::typography::ui_rems(11.5))
                                                .text_color(if is_signed_out {
                                                    theme.danger_muted.opacity(0.85)
                                                } else {
                                                    theme.text_muted.opacity(0.6)
                                                })
                                                .child(SharedString::from(reason)),
                                        )
                                    }),
                            )
                        }
                    }),
            )
            .child(
                div()
                    .flex_none()
                    .flex()
                    .flex_col()
                    .items_end()
                    .justify_between()
                    .gap(px(8.0))
                    .child(badges)
                    .children(actions),
            )
            .into_any_element()
    }

    /// The "switch to a signed-out account" confirmation: Switch asks before
    /// making a dead login the CLI's own. Confirm runs the activate; Cancel
    /// (or Escape via [`Self::dismiss_on_escape`]) keeps the current account.
    fn render_switch_confirm(
        &mut self,
        viewport: gpui::Size<gpui::Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let theme = Theme::of(cx).for_popup();
        let account = self.confirm_switch.clone()?;
        let warning = signed_out_switch_warning(&account);
        let card = popover::dialog_card(&theme)
            .w(px(400.0))
            .gap(px(12.0))
            .child(popover::dialog_title(&theme, "Switch to a signed-out account?"))
            .child(popover::dialog_body(&theme, warning))
            .child(
                div()
                    .mt(px(4.0))
                    .flex()
                    .flex_row()
                    .justify_end()
                    .gap(px(8.0))
                    .child(
                        popover::btn_ghost(&theme, "Cancel", "switch-sign-out-cancel")
                            .id("switch-sign-out-cancel")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.confirm_switch = None;
                                cx.notify();
                            })),
                    )
                    .child(
                        popover::btn_primary(&theme, "Switch anyway")
                            .id("switch-sign-out-confirm")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let account = this.confirm_switch.take();
                                if let Some(account) = account {
                                    this.account_action(
                                        methods::ACTIVATE_AGENT_ACCOUNT,
                                        &account,
                                        cx,
                                    );
                                }
                                cx.notify();
                            })),
                    ),
            );
        Some(popover::modal(
            "account-switch-confirm",
            viewport,
            card.into_any_element(),
        ))
    }

    fn render_login_dialog(
        &mut self,
        viewport: gpui::Size<gpui::Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let theme = Theme::of(cx).for_popup();
        let red_text = theme.danger_muted.opacity(0.9); // red-300
        let login = self.login.as_ref()?;
        let title = login.title();
        let url_link =
            |id: &'static str, label: &'static str, url: &str, cx: &mut Context<Self>| {
                let open_url = url.to_string();
                // "Reopen the …" text link (roboco: `text-[12px]
                // text-muted-foreground/60 hover:underline`).
                div()
                    .id(id)
                    .mt(px(6.0))
                    .text_size(crate::typography::ui_rems(12.0))
                    .text_color(theme.text_muted)
                    .truncate()
                    .cursor_pointer()
                    .hover(|s| s.text_color(theme.text))
                    .on_click(cx.listener(move |_, _, _, cx| {
                        cx.open_url(&open_url);
                    }))
                    .child(SharedString::from(label))
            };
        let body: AnyElement = match login {
            LoginFlow::Starting { .. } => div()
                .mt(px(8.0))
                .child(popover::skeleton_rows(
                    "login-starting",
                    &theme,
                    2,
                    cx.entity_id(),
                    cx,
                ))
                .into_any_element(),
            LoginFlow::PasteCode {
                start,
                submitting,
                error,
                ..
            } => {
                let submitting = *submitting;
                div()
                    .flex()
                    .flex_col()
                    .child(div().mt(px(8.0)).child(popover::dialog_body(
                        &theme,
                        "A browser window opened. Sign in to the account you want to add, \
                         approve access, then paste the code Anthropic shows you below. Your \
                         current login is untouched until you switch.",
                    )))
                    .child(url_link(
                        "login-open-url",
                        "Reopen the authorization page",
                        &start.url,
                        cx,
                    ))
                    .child(
                        div().mt(px(12.0)).child(
                            popover::dialog_field(self.code_input.clone().into_any_element())
                                .font_family(theme.font_mono.clone())
                                .text_size(crate::typography::ui_rems(13.0)),
                        ),
                    )
                    .when_some(error.clone(), |el, message| {
                        el.child(
                            div()
                                .mt(px(8.0))
                                .text_size(crate::typography::ui_rems(12.0))
                                .text_color(red_text)
                                .child(message),
                        )
                    })
                    .child(
                        div()
                            .mt(px(16.0))
                            .flex()
                            .flex_row()
                            .justify_end()
                            .gap(px(8.0))
                            .child(
                                popover::btn_ghost(&theme, "Cancel", "login-cancel")
                                    .id("login-cancel")
                                    .on_click(cx.listener(|this, _, _, cx| this.cancel_login(cx))),
                            )
                            .child(
                                popover::btn_primary(
                                    &theme,
                                    if submitting {
                                        "Verifying…"
                                    } else {
                                        "Add account"
                                    },
                                )
                                .id("login-submit-code")
                                .when(submitting, |el| el.opacity(0.5))
                                .on_click(cx.listener(|this, _, _, cx| this.submit_code(cx))),
                            ),
                    )
                    .into_any_element()
            }
            LoginFlow::Browser {
                harness,
                provider,
                start,
                message,
                error,
            } => {
                let has_error = error.is_some();
                let body = login_copy(*harness, *provider);
                div()
                    .flex()
                    .flex_col()
                    .child(div().mt(px(8.0)).child(popover::dialog_body(&theme, body)))
                    .child(url_link(
                        "login-open-url-browser",
                        "Reopen the sign-in page",
                        &start.url,
                        cx,
                    ))
                    .when(!has_error, |el| {
                        el.child(
                            div()
                                .mt(px(16.0))
                                .flex()
                                .flex_row()
                                .items_center()
                                .gap(px(8.0))
                                .child(crate::loaders::gradient_spinner(
                                    "login-poll",
                                    &theme,
                                    3.0,
                                    cx.entity_id(),
                                    cx,
                                ))
                                .child(
                                    div()
                                        .text_size(crate::typography::ui_rems(12.5))
                                        .text_color(theme.text_muted)
                                        .child(message.clone().unwrap_or_else(|| {
                                            SharedString::from("Waiting for the browser…")
                                        })),
                                ),
                        )
                    })
                    .when_some(error.clone(), |el, message| {
                        el.child(
                            div()
                                .mt(px(12.0))
                                .text_size(crate::typography::ui_rems(12.0))
                                .text_color(red_text)
                                .child(message),
                        )
                    })
                    .child(
                        div().mt(px(16.0)).flex().flex_row().justify_end().child(
                            popover::btn_ghost(
                                &theme,
                                if has_error { "Close" } else { "Cancel" },
                                "login-cancel",
                            )
                            .id("login-cancel")
                            .on_click(cx.listener(|this, _, _, cx| this.cancel_login(cx))),
                        ),
                    )
                    .into_any_element()
            }
        };
        let card = popover::dialog_card(&theme)
            .child(popover::dialog_title(&theme, &title))
            .child(body)
            .into_any_element();
        Some(popover::modal("add-account-dialog", viewport, card))
    }

    /// A ghost account row (roboco settings.agents.tsx `SkeletonRow`): avatar,
    /// email line, two usage-meter ghosts, a badge — same geometry as the real
    /// row so loaded data lands without a layout jump. `dim` fades row two.
    fn render_skeleton_row(
        &self,
        _id: (&'static str, usize),
        dim: bool,
        first: bool,
        theme: &Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        use crate::motion;
        let delta = motion::pulse_delta(&motion::ROBOCO_PULSE, cx.entity_id(), cx);
        let ghost = |w: gpui::Length, h: f32, round_full: bool| {
            div()
                .w(w)
                .h(px(h))
                .flex_none()
                .map(|el| {
                    if round_full {
                        el.rounded_full()
                    } else {
                        el.rounded(px(4.0))
                    }
                })
                .bg(crate::theme::ink(0.05))
        };
        let meters = div()
            .mt(px(8.0))
            .flex()
            .flex_col()
            .gap(px(7.0))
            .children((0..2).map(|_| {
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(8.0))
                    .child(ghost(px(48.0).into(), 9.0, false))
                    .child(
                        div()
                            .flex_1()
                            .min_w(px(56.0))
                            .max_w(px(230.0))
                            .h(px(5.0))
                            .rounded_full()
                            .bg(crate::theme::ink(0.04)),
                    )
                    .child(ghost(px(64.0).into(), 9.0, false))
            }));
        let inner = div()
            .flex()
            .flex_row()
            .items_stretch()
            .gap(px(12.0))
            .child(
                div()
                    .flex_none()
                    .self_center()
                    .size(px(32.0))
                    .rounded_full()
                    .bg(crate::theme::ink(0.05)),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .child(ghost(px(176.0).into(), 13.0, false).max_w(gpui::relative(0.6)))
                    .child(meters),
            )
            .child(div().flex_none().flex().flex_col().items_end().child(ghost(
                px(64.0).into(),
                21.0,
                true,
            )));
        div()
            .px(px(20.0))
            .py(px(14.0))
            .when(!first, |el| el.border_t_1().border_color(theme.border))
            .when(dim, |el| el.opacity(0.6))
            .child(inner.opacity(0.55 + 0.35 * motion::pulse_wave(delta)))
            .into_any_element()
    }
}

impl popover::ScrollRailHost for AccountsPage {
    fn rail_bar(&mut self) -> &mut popover::MenuScrollbarState {
        self.scroll.rail_bar()
    }

    fn rail_scroll(&self) -> Option<gpui::ScrollHandle> {
        self.scroll.rail_scroll()
    }
}

impl Render for AccountsPage {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::toast::take_error(&mut self.error, cx);
        let theme = Theme::of(cx).for_settings_surface();
        let now = Utc::now();
        let viewport = window.viewport_size();
        let confirm = self.render_switch_confirm(viewport, cx);
        let dialog = self.render_login_dialog(viewport, cx);
        let refreshing = matches!(self.snapshot, Loadable::Loading);
        let account_count = self
            .snapshot
            .ready()
            .map(|s| s.accounts.len())
            .filter(|&n| n > 0);

        let provider_icon = |harness: HarnessId| match harness {
            HarnessId::Codex => (crate::icons::OPENAI_MARK, None),
            HarnessId::Cursor => (crate::icons::CURSOR_MARK, None),
            HarnessId::Devin => (crate::icons::DEVIN_MARK, None),
            HarnessId::Grok => (crate::icons::GROK_MARK, None),
            HarnessId::Hermes => (crate::icons::HERMES_MARK, None),
            HarnessId::Pi => (crate::icons::PI_MARK, None),
            HarnessId::Opencode => (crate::icons::OPENCODE_MARK, None),
            HarnessId::Antigravity => (crate::icons::ANTIGRAVITY_MARK, None),
            _ => (
                crate::icons::CLAUDE_MARK,
                Some(crate::icons::claude_brand()),
            ),
        };
        // Brand mark inside a 24px centered box (roboco: `grid size-6
        // place-items-center [&_svg]:size-4`).
        let provider_mark = |harness: HarnessId, theme: &Theme| {
            let (mark, tint) = provider_icon(harness);
            div()
                .flex_none()
                .size(px(24.0))
                .flex()
                .items_center()
                .justify_center()
                .child(
                    crate::icons::icon(mark)
                        .size(px(16.0))
                        .text_color(tint.unwrap_or(theme.text_muted)),
                )
        };

        // One section per provider (roboco settings.agents.tsx `ProviderSection`):
        // brand header + Add account, then the account rows card.
        let sections: Vec<AnyElement> = match &self.snapshot {
            Loadable::Idle | Loadable::Loading => PROVIDERS
                .into_iter()
                .map(|(harness, name, _cli)| {
                    let skeleton_id = match harness {
                        HarnessId::Codex => "accounts-skeleton-codex",
                        HarnessId::Cursor => "accounts-skeleton-cursor",
                        HarnessId::Grok => "accounts-skeleton-grok",
                        HarnessId::Devin => "accounts-skeleton-devin",
                        HarnessId::Opencode => "accounts-skeleton-opencode",
                        HarnessId::Pi => "accounts-skeleton-pi",
                        HarnessId::Hermes => "accounts-skeleton-hermes",
                        HarnessId::Antigravity => "accounts-skeleton-antigravity",
                        _ => "accounts-skeleton-claude",
                    };
                    div()
                        .mt(px(24.0))
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .flex()
                                .flex_row()
                                .items_center()
                                .gap(px(8.0))
                                .child(provider_mark(harness, &theme))
                                .child(
                                    div()
                                        .text_size(crate::typography::ui_rems(14.0))
                                        .font_weight(gpui::FontWeight::MEDIUM)
                                        .text_color(theme.text)
                                        .child(SharedString::from(name)),
                                ),
                        )
                        .child(
                            // Ghost rows shaped like real ones (row two dimmed)
                            // so the card keeps its size while data develops.
                            widgets::section_card(&theme)
                                .mt(px(8.0))
                                .child(self.render_skeleton_row(
                                    (skeleton_id, 0),
                                    false,
                                    true,
                                    &theme,
                                    cx,
                                ))
                                .child(self.render_skeleton_row(
                                    (skeleton_id, 1),
                                    true,
                                    false,
                                    &theme,
                                    cx,
                                )),
                        )
                        .into_any_element()
                })
                .collect(),
            Loadable::Error(message) => {
                let message = message.clone();
                vec![
                    widgets::error_strip(&theme, message)
                        .id("accounts-load-error")
                        .cursor_pointer()
                        .on_click(cx.listener(|this, _, _, cx| {
                            // Retry IS the visit's first successful list — force usage.
                            this.load(force_usage_for(LoadTrigger::Retry), cx)
                        }))
                        .child(
                            div()
                                .mt(px(4.0))
                                .text_size(crate::typography::ui_rems(11.5))
                                .text_color(theme.text_muted)
                                .child(SharedString::from("Click to retry")),
                        )
                        .into_any_element(),
                ]
            }
            Loadable::Ready(snapshot) => {
                let snapshot = snapshot.clone();
                PROVIDERS
                    .into_iter()
                    .map(|(harness, name, cli)| {
                        let accounts = provider_accounts(&snapshot, harness);
                        // EVERY warning renders its own strip (roboco maps them).
                        let warnings: Vec<String> = snapshot
                            .warnings
                            .iter()
                            .filter(|w| w.harness == harness)
                            .map(|w| w.message.clone())
                            .collect();
                        let rows: Vec<AnyElement> = accounts
                            .iter()
                            .enumerate()
                            .map(|(ix, account)| {
                                self.render_account_row(account, ix, ix == 0, &theme, now, cx)
                            })
                            .collect();
                        let add_id: SharedString = format!("add-account-{name}").into();
                        let empty = rows.is_empty();
                        // Antigravity keeps one login: once connected there is
                        // nothing to add — signing in again only re-confirms it.
                        let can_add = empty || !keeps_one_login(harness);
                        let card = widgets::section_card(&theme).mt(px(8.0));
                        let empty_copy = match harness {
                            // Cursor's app login is SEPARATE from `cursor-agent
                            // login` — pointing at the CLI would send users to a
                            // sign-in that does not light this up. Antigravity
                            // has no CLI login at all.
                            HarnessId::Cursor | HarnessId::Antigravity => format!(
                                "{name} isn't connected on this device — connect it to run \
                                 {name} sessions."
                            ),
                            _ => format!(
                                "No {name} login detected on this device — sign in \
                                 with \u{201C}{cli}\u{201D} or add an account."
                            ),
                        };
                        let card = if rows.is_empty() {
                            card.child(
                                div()
                                    .px(px(20.0))
                                    .py(px(32.0))
                                    .text_center()
                                    .text_size(crate::typography::ui_rems(14.0))
                                    .text_color(theme.text_muted.opacity(0.6))
                                    .child(SharedString::from(empty_copy)),
                            )
                        } else {
                            card.children(rows)
                        };
                        div()
                            .mt(px(24.0))
                            .flex()
                            .flex_col()
                            .child(
                                div()
                                    .flex()
                                    .flex_row()
                                    .items_center()
                                    .gap(px(8.0))
                                    .child(provider_mark(harness, &theme))
                                    .child(
                                        div()
                                            .text_size(crate::typography::ui_rems(14.0))
                                            .font_weight(gpui::FontWeight::MEDIUM)
                                            .text_color(theme.text)
                                            .child(SharedString::from(name)),
                                    )
                                    .child(div().flex_1())
                                    // One add button per way to sign in:
                                    // per-provider agents (OpenCode, Pi,
                                    // Hermes) offer each model provider
                                    // separately. A one-login provider that is
                                    // connected offers nothing (it can only
                                    // re-confirm the login it has).
                                    .when(can_add, |el| {
                                        el.children(
                                            login_options(harness).into_iter().enumerate().map(
                                                |(ix, option)| {
                                                    let label = add_option_label(option, empty);
                                                    widgets::ghost_action(&theme)
                                                        .id((add_id.clone(), ix))
                                                        // 23's action-button grammar bakes the
                                                        // ghost hover in; no explicit .hover here.
                                                        .on_click(cx.listener(
                                                            move |this, _, _, cx| {
                                                                this.start_login(
                                                                    harness,
                                                                    option.provider,
                                                                    cx,
                                                                );
                                                            },
                                                        ))
                                                        .child(
                                                            crate::icons::icon(
                                                                crate::icons::ADD_CIRCLE,
                                                            )
                                                            .size(px(16.0))
                                                            .text_color(theme.text_muted),
                                                        )
                                                        .child(SharedString::from(label))
                                                },
                                            ),
                                        )
                                    }),
                            )
                            .children(
                                warnings
                                    .into_iter()
                                    .map(|warning| widgets::warning_strip(&theme, warning)),
                            )
                            .when_some(provider_note(harness), |el, note| {
                                el.child(
                                    div()
                                        .mt(px(4.0))
                                        .text_size(crate::typography::ui_rems(12.0))
                                        .text_color(theme.text_muted)
                                        .child(SharedString::from(note)),
                                )
                            })
                            .child(card)
                            .into_any_element()
                    })
                    .collect()
            }
        };

        let scrollbar = popover::rail(self, "accounts-page-scrollbar", &theme, cx);
        div()
            .id("accounts-page-host")
            .relative()
            .size_full()
            .on_hover(cx.listener(Self::on_scroll_hovered))
            .child(
                div()
                    .id("accounts-page")
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll.scroll)
                    .child(
                        widgets::page_column()
                            .child(
                                div()
                                    .flex()
                                    .flex_row()
                                    .items_center()
                                    .gap(px(10.0))
                                    .child(widgets::page_header(&theme, "Accounts", account_count))
                                    .child(div().flex_1())
                                    .child(
                                        // `text-[12.5px]` + leading 16px Refresh icon,
                                        // dimmed while a refresh is in flight (roboco
                                        // `disabled:opacity-50`).
                                        widgets::ghost_action(&theme)
                                            .id("accounts-refresh")
                                            .flex_none()
                                            .text_size(crate::typography::ui_rems(12.5))
                                            .when(refreshing, |el| el.opacity(0.5))
                                            .on_click(cx.listener(|this, _, _, cx| {
                                                this.load(
                                                    force_usage_for(LoadTrigger::Refresh),
                                                    cx,
                                                )
                                            }))
                                            .child(
                                                crate::icons::icon(crate::icons::REFRESH)
                                                    .size(px(16.0))
                                                    .text_color(theme.text_muted),
                                            )
                                            .child(SharedString::from("Refresh")),
                                    )
                                    .child(self.render_device_switcher(&theme, cx)),
                            )
                            .child(widgets::page_subtitle(
                                &theme,
                                "The agent logins on this device — Claude Code, Codex, \
                                 Cursor, Grok, Devin, OpenCode, Pi, and Hermes. Roboco \
                                 detects the live session, keeps each account backed up, and \
                                 can swap between them.",
                            ))
                            .children(sections)
                            // Footer note (roboco: `mt-6 text-[12px] leading-relaxed
                            // text-muted-foreground/60`).
                            .child(
                                div()
                                    .mt(px(24.0))
                                    .text_size(crate::typography::ui_rems(12.0))
                                    .line_height(px(19.0))
                                    .text_color(theme.text_muted.opacity(0.6))
                                    .child(SharedString::from(
                                        "Switching rewrites the CLI\u{2019}s stored login, so new \
                                         agent sessions use the selected account immediately. On \
                                         macOS, an already-running Claude Code can hold the previous \
                                         login for up to ~30 seconds (Keychain cache).",
                                    )),
                            ),
                    ),
            )
            .children(scrollbar)
            .when_some(dialog, |el, dialog| el.child(dialog))
            .when_some(confirm, |el, confirm| el.child(confirm))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeDelta;

    /// A per-provider agent's section renders its rows (a live login, a
    /// saved one, an unidentified one) and one add button per provider, and
    /// a device-code sign-in's dialog carries the code line.
    #[gpui::test]
    fn per_provider_accounts_render_with_one_add_button_per_provider(
        cx: &mut gpui::TestAppContext,
    ) {
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let window = cx.add_window(|window, cx| {
            let state = cx.new(|_| AppState::new());
            AccountsPage::new(state, cx)
        });
        let row = |id: &str, email: &str, active, switchable| AgentAccount {
            id: id.into(),
            harness: HarnessId::Opencode,
            email: Some(email.into()),
            plan_label: Some("ChatGPT Plus".into()),
            active,
            usage_windows: vec![roboco_proto::AgentUsageWindow {
                label: "Session".into(),
                used_fraction: 0.4,
                resets_at: None,
            }],
            usage_fetched_at: None,
            usage_error: None,
            sign_in_state: AgentSignInState::Unknown,
            limited_until: None,
            display_name: None,
            organization: None,
            auth_kind: None,
            switchable,
            saved_at: None,
            provider: Some("openai".into()),
        };
        window
            .update(cx, |page, _, cx| {
                page.snapshot = Loadable::Ready(AgentAccountsSnapshot {
                    accounts: vec![
                        row("a", "a@example.com", true, true),
                        row("b", "b@example.com", false, true),
                        AgentAccount {
                            usage_windows: vec![],
                            provider: Some("github-copilot".into()),
                            ..row("c", "GitHub account", true, false)
                        },
                    ],
                    warnings: vec![],
                });
                page.login = Some(LoginFlow::Browser {
                    harness: HarnessId::Opencode,
                    provider: Some("github-copilot"),
                    start: AgentLoginStart {
                        login_id: "login-1".into(),
                        url: "https://github.com/login/device".into(),
                        mode: AgentLoginMode::Browser,
                        callback_port: None,
                        cli_opens_browser: false,
                    },
                    message: Some("Enter the code ABCD-1234 on GitHub.".into()),
                    error: None,
                });
                assert_eq!(
                    page.login.as_ref().unwrap().title(),
                    "Sign in to GitHub Copilot for OpenCode"
                );
            })
            .unwrap();
        cx.update_window(window.into(), |_, window, cx| window.draw(cx).clear())
            .unwrap();
    }

    #[test]
    fn first_load_of_a_visit_forces_the_usage_probe() {
        // The engine only probes usage when forced (M5c); without forcing on
        // mount, the first Accounts open always rendered "Usage unavailable".
        assert!(force_usage_for(LoadTrigger::Mount));
        // A retry after a failed load is still the visit's first successful
        // list — same requirement.
        assert!(force_usage_for(LoadTrigger::Retry));
        // Explicit refresh and a just-completed login always re-probe.
        assert!(force_usage_for(LoadTrigger::Refresh));
        assert!(force_usage_for(LoadTrigger::PostLogin));
        // Switch/Forget re-lists ride the still-warm 60s cache.
        assert!(!force_usage_for(LoadTrigger::PostAction));
    }

    #[test]
    fn usage_thresholds_match_roboco() {
        assert_eq!(usage_level(0.0), UsageLevel::Normal);
        assert_eq!(usage_level(0.79), UsageLevel::Normal);
        assert_eq!(usage_level(0.80), UsageLevel::Warn);
        assert_eq!(usage_level(0.94), UsageLevel::Warn);
        assert_eq!(usage_level(0.95), UsageLevel::Critical);
        assert_eq!(usage_level(1.0), UsageLevel::Critical);
    }

    #[test]
    fn usage_colors_map_to_theme_accents() {
        let theme = Theme::dark();
        assert_eq!(usage_color(UsageLevel::Normal, &theme), theme.accent);
        assert_eq!(usage_color(UsageLevel::Warn, &theme), theme.warning);
        assert_eq!(usage_color(UsageLevel::Critical, &theme), theme.danger);
    }

    #[test]
    fn usage_resets_lists_every_window() {
        let now = Utc::now();
        let soon = now + TimeDelta::minutes(125);
        let windows = [
            roboco_proto::AgentUsageWindow {
                label: "Session".into(),
                used_fraction: 0.07,
                resets_at: Some(soon),
            },
            roboco_proto::AgentUsageWindow {
                label: "Week".into(),
                used_fraction: 0.44,
                resets_at: None,
            },
        ];
        assert_eq!(
            usage_resets(&windows, now),
            format!(
                "Session: {} · Week",
                format_reset(Some(soon), now).unwrap()
            )
        );
        assert_eq!(usage_resets(&[], now), "");
    }

    #[test]
    fn reset_formatting_is_absolute() {
        use chrono::Local;
        let now = Utc::now();
        assert_eq!(format_reset(None, now), None);
        // Within ~22h: a local clock time ("resets 3:45 PM").
        let soon = now + TimeDelta::minutes(125);
        assert_eq!(
            format_reset(Some(soon), now),
            Some(format!(
                "resets {}",
                soon.with_timezone(&Local).format("%-I:%M %p")
            ))
        );
        // Within a week: a short weekday ("resets Mon").
        let later = now + TimeDelta::days(3);
        assert_eq!(
            format_reset(Some(later), now),
            Some(format!(
                "resets {}",
                later.with_timezone(&Local).format("%a")
            ))
        );
        // Beyond a week (Codex free tier resets ~monthly): month + day
        // ("resets Sep 14") — a weekday 4 weeks out carries no information.
        let monthly = now + TimeDelta::days(26);
        assert_eq!(
            format_reset(Some(monthly), now),
            Some(format!(
                "resets {}",
                monthly.with_timezone(&Local).format("%b %-d")
            ))
        );
    }

    #[test]
    fn provider_grouping_keeps_engine_order_even_when_active_is_later() {
        let account = |id: &str, harness: HarnessId, active: bool| AgentAccount {
            id: id.into(),
            harness,
            email: None,
            plan_label: None,
            active,
            usage_windows: vec![],
            usage_fetched_at: None,
            usage_error: None,
            sign_in_state: AgentSignInState::Unknown,
            limited_until: None,
            display_name: None,
            organization: None,
            auth_kind: None,
            switchable: true,
            saved_at: None,
            provider: None,
        };
        let snapshot = AgentAccountsSnapshot {
            accounts: vec![
                account("c1", HarnessId::ClaudeCode, false),
                account("x1", HarnessId::Codex, false),
                account("c2", HarnessId::ClaudeCode, true),
            ],
            warnings: vec![],
        };
        let claude = provider_accounts(&snapshot, HarnessId::ClaudeCode);
        let ids: Vec<&str> = claude.iter().map(|a| a.id.as_str()).collect();
        assert_eq!(
            ids,
            ["c1", "c2"],
            "engine (creation) order holds — switching must not move a card"
        );
        assert_eq!(provider_accounts(&snapshot, HarnessId::Codex).len(), 1);
        assert!(provider_accounts(&snapshot, HarnessId::Cursor).is_empty());
    }

    #[test]
    fn every_provider_with_a_login_offers_one_default_sign_in() {
        for harness in [
            HarnessId::ClaudeCode,
            HarnessId::Codex,
            HarnessId::Cursor,
            HarnessId::Grok,
            HarnessId::Devin,
        ] {
            // One login per agent: one add button, no provider param.
            let options = login_options(harness);
            assert_eq!(options.len(), 1, "{harness:?}");
            assert_eq!(options[0].provider, None);
            assert_eq!(add_option_label(options[0], false), "Add account");
            assert!(
                login_copy(harness, None).starts_with("Finish signing in to "),
                "{harness:?}"
            );
            assert!(switches_accounts(harness), "{harness:?} is switchable");
            assert!(provider_note(harness).is_none());
        }
    }

    #[test]
    fn antigravity_keeps_one_login_with_no_usage_view() {
        // The engine lists Antigravity rows; the page offers the section's
        // connect flow, but once connected there is nothing to add, and the
        // sign-in re-confirms the one login it has.
        assert!(signs_in(HarnessId::Antigravity));
        assert!(
            PROVIDERS
                .iter()
                .any(|(h, _, _)| *h == HarnessId::Antigravity)
        );
        assert!(keeps_one_login(HarnessId::Antigravity) && !keeps_one_login(HarnessId::Cursor));
        assert!(!reports_usage(HarnessId::Antigravity) && reports_usage(HarnessId::Codex));
        assert!(!switches_accounts(HarnessId::Antigravity));
        assert!(login_copy(HarnessId::Antigravity, None).contains("Google"));
        assert!(login_copy(HarnessId::Antigravity, None).contains("one login"));
    }

    #[test]
    fn per_provider_agents_offer_one_sign_in_per_provider() {
        let opencode = login_options(HarnessId::Opencode);
        let labels: Vec<_> = opencode.iter().map(|o| o.label).collect();
        assert_eq!(labels, ["ChatGPT", "GitHub Copilot"]);
        assert_eq!(add_option_label(opencode[0], true), "Connect ChatGPT");
        assert_eq!(
            add_option_label(opencode[1], false),
            "Add GitHub Copilot account"
        );
        let providers: Vec<_> = login_options(HarnessId::Hermes)
            .iter()
            .map(|o| o.provider.unwrap())
            .collect();
        assert_eq!(providers, ["openai-codex", "nous"]);
        for harness in [HarnessId::Opencode, HarnessId::Pi, HarnessId::Hermes] {
            for option in login_options(harness) {
                assert!(option.provider.is_some(), "{harness:?} names its provider");
                assert!(login_copy(harness, option.provider).starts_with("Finish signing in"));
            }
        }
        // Hermes rotates its own pool; roboco never switches it, and says so.
        assert!(!switches_accounts(HarnessId::Hermes) && switches_accounts(HarnessId::Grok));
        assert!(provider_note(HarnessId::Hermes).is_some_and(|n| n.contains("hermes auth add")));
        assert!(provider_note(HarnessId::Grok).is_none());
    }

    #[test]
    fn a_switch_only_moves_the_live_login_within_its_provider_group() {
        let row = |id: &str, harness, provider: Option<&str>, active| AgentAccount {
            id: id.into(),
            harness,
            email: None,
            plan_label: None,
            active,
            usage_windows: vec![],
            usage_fetched_at: None,
            usage_error: None,
            sign_in_state: AgentSignInState::Unknown,
            limited_until: None,
            display_name: None,
            organization: None,
            auth_kind: None,
            switchable: true,
            saved_at: None,
            provider: provider.map(str::to_string),
        };
        let mut snapshot = AgentAccountsSnapshot {
            accounts: vec![
                row("gpt-a", HarnessId::Opencode, Some("openai"), true),
                row("gpt-b", HarnessId::Opencode, Some("openai"), false),
                row("copilot", HarnessId::Opencode, Some("github-copilot"), true),
                row("grok-a", HarnessId::Grok, None, true),
            ],
            warnings: vec![],
        };
        let target = snapshot.accounts[1].clone();
        mark_switched(&mut snapshot, &target);
        let live: Vec<&str> = snapshot
            .accounts
            .iter()
            .filter(|a| a.active)
            .map(|a| a.id.as_str())
            .collect();
        assert_eq!(live, ["gpt-b", "copilot", "grok-a"]);
    }

    #[test]
    fn login_flow_titles_name_the_provider_when_one_is_signed_in_to() {
        let per_provider = per_provider_with_harness(HarnessId::Pi, "openai-codex");
        assert_eq!(per_provider.title(), "Sign in to ChatGPT for Pi");
        let copilot = per_provider_with_harness(HarnessId::Opencode, "github-copilot");
        assert_eq!(copilot.title(), "Sign in to GitHub Copilot for OpenCode");
        let grok = LoginFlow::Starting {
            harness: HarnessId::Grok,
            provider: None,
        };
        assert_eq!(grok.title(), "Add Grok account");
    }

    /// A [`LoginFlow::Browser`] with the given harness and provider.
    fn per_provider_with_harness(harness: HarnessId, provider: &'static str) -> LoginFlow {
        LoginFlow::Browser {
            harness,
            provider: Some(provider),
            start: AgentLoginStart {
                login_id: "login-1".into(),
                url: String::new(),
                mode: AgentLoginMode::Browser,
                callback_port: None,
                cli_opens_browser: false,
            },
            message: None,
            error: None,
        }
    }

    // ---- sign-in state (ticket 08) ----

    /// A harness account with the given sign-in state — the wire parses all
    /// three states, and a payload from before the field existed is unknown.
    fn state_account(sign_in_state: AgentSignInState) -> AgentAccount {
        serde_json::from_value(serde_json::json!({
            "id": "a1",
            "harness": "claude-code",
            "email": "user@example.com",
            "planLabel": null,
            "active": false,
            "usageWindows": [],
            "switchable": true,
            "signInState": sign_in_state,
        }))
        .unwrap()
    }

    #[test]
    fn only_a_signed_out_state_marks_the_account() {
        assert!(!signed_out(&state_account(AgentSignInState::Unknown)));
        assert!(!signed_out(&state_account(AgentSignInState::SignedIn)));
        assert!(signed_out(&state_account(AgentSignInState::SignedOut)));
        // A payload from before the field existed is unknown, never marked.
        let legacy: AgentAccount = serde_json::from_value(serde_json::json!({
            "id": "a1",
            "harness": "claude-code",
            "email": null,
            "planLabel": null,
            "active": true,
            "usageWindows": [],
            "switchable": true,
        }))
        .unwrap();
        assert!(!signed_out(&legacy));
    }

    /// The reason line survives cached meters and exists even when no probe
    /// failed (a run error marked the account signed out).
    #[test]
    fn the_note_shows_beside_meters_and_covers_run_error_marks() {
        // A probe failure is the reason, whatever the state.
        let mut account = state_account(AgentSignInState::SignedIn);
        account.usage_error = Some("Rate limited by Anthropic — retrying in 2m".into());
        assert_eq!(
            account_note(&account).as_deref(),
            Some("Rate limited by Anthropic — retrying in 2m")
        );
        // A signed-out account with no probe behind it still explains itself.
        let account = state_account(AgentSignInState::SignedOut);
        assert_eq!(
            account_note(&account).as_deref(),
            Some("Signed out — sign in again")
        );
        // A healthy account has nothing to say.
        assert_eq!(account_note(&state_account(AgentSignInState::SignedIn)), None);
        assert_eq!(account_note(&state_account(AgentSignInState::Unknown)), None);
    }

    #[test]
    fn the_switch_warning_names_the_signed_out_account() {
        let account = state_account(AgentSignInState::SignedOut);
        assert!(signed_out_switch_warning(&account).contains("user@example.com is signed out"));
        let mut unnamed = state_account(AgentSignInState::SignedOut);
        unnamed.email = None;
        unnamed.display_name = None;
        assert!(signed_out_switch_warning(&unnamed).starts_with("This account is signed out"));
    }

    /// "Sign in again" signs in to the account's own provider group, or the
    /// agent's only login when it isn't a per-provider one.
    #[test]
    fn sign_in_again_targets_the_accounts_provider_group() {
        let mut opencode = state_account(AgentSignInState::SignedOut);
        opencode.harness = HarnessId::Opencode;
        opencode.provider = Some("openai".into());
        assert_eq!(sign_in_option_for(&opencode), Some("openai"));
        opencode.provider = Some("github-copilot".into());
        assert_eq!(sign_in_option_for(&opencode), Some("github-copilot"));
        // Single-login agents: the agent's own (only) sign-in.
        let claude = state_account(AgentSignInState::SignedOut);
        assert_eq!(sign_in_option_for(&claude), None);
        assert_eq!(claude.provider, None);
    }

    /// A signed-out row renders: the mark beside the meters, the reason under
    /// them, and the Sign in again action — even on the ACTIVE account (the
    /// common run-error case). Draw-level smoke; the state transitions are
    /// the pure tests above.
    #[gpui::test]
    fn a_signed_out_row_renders_its_mark_and_reason(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let window = cx.add_window(|window, cx| {
            let state = cx.new(|_| AppState::new());
            AccountsPage::new(state, cx)
        });
        // Active, signed out, with STALE meters — the display-bug shape.
        let mut account = state_account(AgentSignInState::SignedOut);
        account.active = true;
        account.usage_windows = vec![roboco_proto::AgentUsageWindow {
            label: "Session".into(),
            used_fraction: 0.4,
            resets_at: None,
        }];
        account.usage_error = Some("Signed out — sign in again".into());
        window
            .update(cx, |page, _, cx| {
                page.snapshot = Loadable::Ready(AgentAccountsSnapshot {
                    accounts: vec![account],
                    warnings: vec![],
                });
                assert!(page.confirm_switch.is_none());
            })
            .unwrap();
        cx.update_window(window.into(), |_, window, cx| window.draw(cx).clear())
            .unwrap();
    }
}
