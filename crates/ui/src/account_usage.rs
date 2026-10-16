//! The composer footer's ring cluster. The plan-usage ring shows how much of
//! the active account's rate limit the session's harness has used, beside
//! the context ring. Clicking it opens the harness's accounts — each with its
//! usage meters — and clicking one switches to it, the same `ActivateAgentAccount` Settings →
//! Accounts runs. Both views share [`AccountsSnapshotCache`], so a switch in
//! either shows up in the other.
//!
//! A signed-out account is marked on its row, offers "Sign in again" (the
//! engine's own login flow, driven inline here), and asks before a switch
//! lands on it — ticket 08, the same rules Settings → Accounts follows.
use std::time::{Duration, Instant};

use gpui::{
    Context, Entity, IntoElement, Render, SharedString, Subscription, Task, Window, div,
    prelude::*, px,
};
use roboco_proto::{
    AgentAccount, AgentAccountsSnapshot, AgentLoginMode, AgentLoginStart, AgentLoginStatus,
    HarnessId,
};
use roboco_rpc::methods;

use crate::popover;
use crate::settings::accounts::{
    self, AccountsSnapshotCache, UsageLevel, account_note, render_usage_meter, reports_usage,
    signed_out, signed_out_switch_warning, signs_in, usage_color, usage_level,
};
use crate::state::AppState;
use crate::theme::Theme;

/// Forced probes hit the provider; the engine throttles them too, but the
/// ring re-probes on every open, so don't even ask more often than this.
const FORCE_MIN_INTERVAL: Duration = Duration::from_secs(30);
/// Background re-probe while a composer is alive: usage moves as turns run.
const POLL_INTERVAL: Duration = Duration::from_secs(5 * 60);

/// The binding limit: the most-used window of the account. Pure.
pub fn used_fraction(account: &AgentAccount) -> Option<f32> {
    account
        .usage_windows
        .iter()
        .map(|window| window.used_fraction.clamp(0.0, 1.0))
        .reduce(f32::max)
}

/// The harness's live account, when it reports usage. Pure.
pub fn active_account(
    snapshot: &AgentAccountsSnapshot,
    harness: HarnessId,
) -> Option<&AgentAccount> {
    snapshot
        .accounts
        .iter()
        .find(|account| account.harness == harness && account.active)
}

/// The popover's inline "Sign in again" flow — the engine's own login
/// (`StartAgentLogin`), driven inside the accounts card: the browser opens,
/// the row reports progress, and a landed sign-in reloads the accounts (the
/// fresh credentials re-probe, so the row flips back to signed in).
enum SignInFlow {
    /// `StartAgentLogin` in flight.
    Starting {
        harness: HarnessId,
        provider: Option<&'static str>,
    },
    /// Open the URL, poll until the browser flow lands.
    Browser {
        harness: HarnessId,
        provider: Option<&'static str>,
        start: AgentLoginStart,
        error: Option<SharedString>,
    },
    /// Claude's fallback: paste the code the page shows back here.
    PasteCode {
        harness: HarnessId,
        provider: Option<&'static str>,
        start: AgentLoginStart,
        submitting: bool,
        error: Option<SharedString>,
    },
}

impl SignInFlow {
    /// Who is being signed in to, for the progress line.
    fn parts(&self) -> (HarnessId, Option<&'static str>) {
        match self {
            SignInFlow::Starting { harness, provider }
            | SignInFlow::Browser { harness, provider, .. }
            | SignInFlow::PasteCode { harness, provider, .. } => (*harness, *provider),
        }
    }
}

/// Loads (and switches) the accounts of the composer's target device.
pub struct AccountUsage {
    state: Entity<AppState>,
    /// `None` = this device, as in [`AccountsSnapshotCache`].
    target: Option<String>,
    harness: Option<HarnessId>,
    /// Once any list has been asked for the current target.
    loaded: bool,
    last_forced: Option<Instant>,
    error: Option<SharedString>,
    load_task: Option<Task<()>>,
    action_task: Option<Task<()>>,
    /// A signed-out account whose switch is asking first.
    confirm_switch: Option<AgentAccount>,
    /// The inline "Sign in again" flow, when one is running.
    sign_in: Option<SignInFlow>,
    sign_in_task: Option<Task<()>>,
    sign_in_poll: Option<Task<()>>,
    /// The paste-code field of a [`SignInFlow::PasteCode`].
    code_input: Entity<crate::composer::ComposerInput>,
    popup: popover::Popup<FooterCard>,
    _poll: Task<()>,
    _cache: Subscription,
    _state: Subscription,
    _code_events: Subscription,
}

impl AccountUsage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let poll = cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(POLL_INTERVAL).await;
                if this
                    .update(cx, |usage, cx| {
                        if usage.harness.is_some() {
                            usage.load(true, cx);
                        }
                    })
                    .is_err()
                {
                    break;
                }
            }
        });
        // The paste-code field of a Claude sign-in (its loopless fallback).
        let code_input =
            cx.new(|cx| crate::composer::ComposerInput::new("Paste the authorization code", cx));
        let code_events = cx.subscribe(&code_input, |usage: &mut Self, _, event, cx| {
            if matches!(event, crate::composer::ComposerInputEvent::Submitted) {
                usage.submit_sign_in_code(cx);
            }
        });
        Self {
            _state: cx.observe(&state, |_, _, cx| cx.notify()),
            state,
            target: None,
            harness: None,
            loaded: false,
            last_forced: None,
            error: None,
            load_task: None,
            action_task: None,
            confirm_switch: None,
            sign_in: None,
            sign_in_task: None,
            sign_in_poll: None,
            code_input,
            popup: popover::Popup::default(),
            _poll: poll,
            // Settings → Accounts writes the same cache.
            _cache: cx.observe_global::<AccountsSnapshotCache>(|_, cx| cx.notify()),
            _code_events: code_events,
        }
    }

    /// Point at the session's harness and device; loads on first sight of a
    /// device. Cheap enough to call every render.
    pub fn track(
        &mut self,
        harness: Option<HarnessId>,
        target: Option<String>,
        cx: &mut Context<Self>,
    ) {
        let harness = harness.filter(|h| signs_in(*h) && reports_usage(*h));
        self.harness = harness;
        if self.target != target {
            self.target = target;
            self.loaded = false;
            self.last_forced = None;
            self.error = None;
        }
        if harness.is_some() && !self.loaded {
            self.loaded = true;
            self.load(true, cx);
        }
    }

    fn snapshot<'a>(&self, cx: &'a gpui::App) -> Option<&'a AgentAccountsSnapshot> {
        cx.try_global::<AccountsSnapshotCache>()?
            .0
            .get(&self.target)
    }

    fn params(&self, mut value: serde_json::Value) -> serde_json::Value {
        if let (Some(target), Some(object)) = (&self.target, value.as_object_mut()) {
            object.insert("targetDeviceId".into(), serde_json::json!(target));
        }
        value
    }

    /// Plain list first when nothing is cached (the engine's persisted usage
    /// paints at once), then the forced probe replaces it.
    fn load(&mut self, force_usage: bool, cx: &mut Context<Self>) {
        if force_usage {
            if self
                .last_forced
                .is_some_and(|at| at.elapsed() < FORCE_MIN_INTERVAL)
            {
                return;
            }
            self.last_forced = Some(Instant::now());
        }
        let Some(engine) =
            crate::request_routing::device_target(self.state.read(cx), self.target.as_deref()).ok()
        else {
            return;
        };
        let key = self.target.clone();
        let paint_first = force_usage && self.snapshot(cx).is_none();
        let plain = self.params(serde_json::json!({ "forceUsage": false }));
        let params = self.params(serde_json::json!({ "forceUsage": force_usage }));
        self.load_task = Some(cx.spawn(async move |this, cx| {
            let fetch = |params: serde_json::Value| async {
                engine
                    .call(methods::LIST_AGENT_ACCOUNTS, params)
                    .await
                    .ok()
                    .and_then(|value| serde_json::from_value::<AgentAccountsSnapshot>(value).ok())
            };
            let store = |snapshot: AgentAccountsSnapshot, cx: &mut gpui::AsyncApp| {
                let key = key.clone();
                this.update(cx, |_, cx| {
                    cx.default_global::<AccountsSnapshotCache>()
                        .0
                        .insert(key, snapshot);
                })
                .ok();
            };
            if paint_first && let Some(snapshot) = fetch(plain).await {
                store(snapshot, cx);
            }
            if let Some(snapshot) = fetch(params).await {
                store(snapshot, cx);
            }
        }));
    }

    /// Switch optimistically, like Settings → Accounts: the rows flip at once
    /// and the engine's reply replaces them; a refusal restores the list.
    fn switch(&mut self, account: &AgentAccount, cx: &mut Context<Self>) {
        let Some(engine) =
            crate::request_routing::device_target(self.state.read(cx), self.target.as_deref()).ok()
        else {
            return;
        };
        let key = self.target.clone();
        let cache = &mut cx.default_global::<AccountsSnapshotCache>().0;
        let previous = cache.get(&key).cloned();
        if let Some(snapshot) = cache.get_mut(&key) {
            // Per-provider agents keep one live login per provider: only the
            // switched-to row's group changes.
            accounts::mark_switched(snapshot, account);
        }
        self.error = None;
        let params = self.params(serde_json::json!({
            "id": account.id,
            "accountId": account.id,
            "harness": account.harness,
        }));
        self.action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::ACTIVATE_AGENT_ACCOUNT, params).await;
            this.update(cx, |usage, cx| {
                match result.map(serde_json::from_value::<AgentAccountsSnapshot>) {
                    Ok(Ok(snapshot)) => {
                        cx.default_global::<AccountsSnapshotCache>()
                            .0
                            .insert(key, snapshot);
                    }
                    Ok(Err(_)) => usage.load(false, cx),
                    Err(err) => {
                        if let Some(previous) = previous {
                            cx.default_global::<AccountsSnapshotCache>()
                                .0
                                .insert(key, previous);
                        }
                        usage.error = Some(err.to_string().into());
                    }
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    // ---- "Sign in again" (the engine's own login flow, inline) ----

    /// Start the existing login flow for a signed-out account's provider
    /// group — the same `StartAgentLogin` Settings → Accounts runs. The
    /// browser opens at once; the card shows the flow until it lands.
    /// `pub(crate)` so the Limited banner's failover picker (ticket 09)
    /// drives the same flow through the composer's account ring.
    pub(crate) fn sign_in_again(&mut self, account: &AgentAccount, cx: &mut Context<Self>) {
        let Some(engine) =
            crate::request_routing::device_target(self.state.read(cx), self.target.as_deref()).ok()
        else {
            return;
        };
        let provider = accounts::sign_in_option_for(account);
        let harness = account.harness;
        self.sign_in = Some(SignInFlow::Starting { harness, provider });
        self.error = None;
        let mut params = serde_json::json!({ "harness": harness });
        if let (Some(provider), Some(object)) = (provider, params.as_object_mut()) {
            object.insert("provider".into(), serde_json::json!(provider));
        }
        let params = self.params(params);
        self.sign_in_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::START_AGENT_LOGIN, params).await;
            this.update(cx, |usage, cx| {
                match result.and_then(|value| {
                    serde_json::from_value::<AgentLoginStart>(value)
                        .map_err(|e| roboco_rpc::RpcError::Failed(e.to_string()))
                }) {
                    Ok(start) => {
                        if !start.cli_opens_browser {
                            cx.open_url(&start.url);
                        }
                        match start.mode {
                            AgentLoginMode::PasteCode => {
                                usage.code_input.update(cx, |input, cx| input.set_text("", cx));
                                usage.sign_in = Some(SignInFlow::PasteCode {
                                    harness,
                                    provider,
                                    start,
                                    submitting: false,
                                    error: None,
                                });
                            }
                            AgentLoginMode::Browser => {
                                usage.sign_in = Some(SignInFlow::Browser {
                                    harness,
                                    provider,
                                    start,
                                    error: None,
                                });
                                usage.spawn_sign_in_poll(cx);
                            }
                        }
                    }
                    Err(err) => {
                        usage.sign_in = None;
                        usage.error = Some(format!("Login failed to start: {err}").into());
                    }
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// Submit the pasted code of a Claude paste-code sign-in.
    fn submit_sign_in_code(&mut self, cx: &mut Context<Self>) {
        let Some(SignInFlow::PasteCode {
            start, submitting, ..
        }) = &mut self.sign_in
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
        let Some(engine) =
            crate::request_routing::device_target(self.state.read(cx), self.target.as_deref()).ok()
        else {
            return;
        };
        let params = self.params(serde_json::json!({ "loginId": login_id, "code": code }));
        self.sign_in_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::COMPLETE_AGENT_LOGIN, params).await;
            this.update(cx, |usage, cx| {
                match result {
                    Ok(_) => usage.sign_in_landed(cx),
                    Err(err) => {
                        if let Some(SignInFlow::PasteCode {
                            submitting, error, ..
                        }) = &mut usage.sign_in
                        {
                            *submitting = false;
                            *error = Some(err.to_string().into());
                        }
                    }
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// Poll a browser sign-in until it lands (or fails).
    fn spawn_sign_in_poll(&mut self, cx: &mut Context<Self>) {
        let Some(SignInFlow::Browser { start, .. }) = &self.sign_in else {
            return;
        };
        let login_id = start.login_id.clone();
        let Some(engine) =
            crate::request_routing::device_target(self.state.read(cx), self.target.as_deref()).ok()
        else {
            return;
        };
        let params = self.params(serde_json::json!({ "loginId": login_id }));
        self.sign_in_poll = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(1500))
                    .await;
                let result = engine.call(methods::POLL_AGENT_LOGIN, params.clone()).await;
                let outcome = this
                    .update(cx, |usage, cx| {
                        let Some(SignInFlow::Browser { error, .. }) = &mut usage.sign_in else {
                            return true; // flow closed — stop polling
                        };
                        match result
                            .as_ref()
                            .ok()
                            .and_then(|value| serde_json::from_value::<roboco_proto::AgentLoginPoll>(value.clone()).ok())
                        {
                            Some(poll) => match poll.status {
                                AgentLoginStatus::Done => {
                                    usage.sign_in_landed(cx);
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
                                AgentLoginStatus::Pending => false,
                            },
                            None => {
                                *error = Some(
                                    match &result {
                                        Err(err) => format!("Poll failed: {err}"),
                                        Ok(_) => "Poll failed: malformed reply".to_string(),
                                    }
                                    .into(),
                                );
                                cx.notify();
                                true
                            }
                        }
                    })
                    .unwrap_or(true);
                if outcome {
                    break;
                }
            }
        }));
        cx.notify();
    }

    /// A sign-in landed: close the flow and re-probe — fresh credentials
    /// lift the engine's freshness gate, so the row flips back to signed in.
    fn sign_in_landed(&mut self, cx: &mut Context<Self>) {
        self.sign_in = None;
        self.sign_in_poll = None;
        self.last_forced = None;
        self.load(true, cx);
    }

    /// Cancel the inline sign-in (the engine kills the flow; the CLI child
    /// and its throwaway home go with it).
    fn cancel_sign_in(&mut self, cx: &mut Context<Self>) {
        let login_id = match &self.sign_in {
            Some(SignInFlow::Browser { start, .. })
            | Some(SignInFlow::PasteCode { start, .. }) => Some(start.login_id.clone()),
            _ => None,
        };
        self.sign_in = None;
        self.sign_in_poll = None;
        self.sign_in_task = None;
        if let (Some(login_id), Some(engine)) = (
            login_id,
            crate::request_routing::device_target(
                self.state.read(cx),
                self.target.as_deref(),
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

    /// The ring's reading: the live account's most-used window, when the
    /// harness has a live account with usage to show.
    fn fraction(&self, cx: &gpui::App) -> Option<f32> {
        used_fraction(active_account(self.snapshot(cx)?, self.harness?)?)
    }

    /// A trigger click: open this ring's popover, or close it when the
    /// press found it open (the card's mouse-down-out already began that
    /// close — see `Popup::note_trigger_press`).
    fn toggle(&mut self, card: FooterCard, cx: &mut Context<Self>) {
        if self.popup.take_press_was_open() || self.popup.as_open() == Some(&card) {
            self.dismiss(cx);
            return;
        }
        if card == FooterCard::Accounts {
            // Opening the card is the moment someone cares: re-probe.
            self.load(true, cx);
        }
        self.popup.open(card);
        cx.notify();
    }

    fn dismiss(&mut self, cx: &mut Context<Self>) {
        if self.popup.begin_close() {
            popover::reap_popup(cx, |usage: &mut Self| &mut usage.popup);
        }
        cx.notify();
    }

    /// A footer ring that opens `card` above itself, right-aligned like the
    /// model picker.
    fn trigger(
        &self,
        chip: gpui::Stateful<gpui::Div>,
        card: FooterCard,
        content: impl FnOnce(&Self, &mut Context<Self>) -> gpui::Div,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let chip = chip
            .relative()
            .on_mouse_down(
                gpui::MouseButton::Left,
                cx.listener(move |usage, _, _, _| {
                    usage
                        .popup
                        .note_trigger_press_matching(|open| *open == card);
                }),
            )
            .on_click(cx.listener(move |usage, _, _, cx| usage.toggle(card, cx)));
        if self.popup.get() != Some(&card) {
            return chip.into_any_element();
        }
        let content = content(self, cx)
            .on_mouse_down_out(cx.listener(|usage, _, _, cx| usage.dismiss(cx)))
            .into_any_element();
        chip.child(popover::anchored_menu_above_end(
            card.id(),
            content,
            self.popup.closing_since(),
        ))
        .into_any_element()
    }

    fn accounts_card(&self, cx: &mut Context<Self>) -> gpui::Div {
        let theme = &Theme::of(cx).for_popup();
        let harness = self.harness;
        let rows: Vec<AgentAccount> = match (harness, self.snapshot(cx)) {
            (Some(harness), Some(snapshot)) => accounts::provider_accounts(snapshot, harness)
                .into_iter()
                .cloned()
                .collect(),
            _ => Vec::new(),
        };
        let title = format!(
            "{} accounts",
            harness.map_or("Agent", accounts::provider_name)
        );
        popover::popover_card(theme)
            .w(px(400.0))
            .flex()
            .flex_col()
            .child(popover::menu_heading(theme, &title))
            .children(rows.iter().enumerate().map(|(ix, account)| {
                let email: SharedString = account
                    .email
                    .clone()
                    .or_else(|| account.display_name.clone())
                    .unwrap_or_else(|| "Unknown account".into())
                    .into();
                let can_switch = !account.active && account.switchable;
                let is_signed_out = signed_out(account);
                let mut meta: Vec<gpui::AnyElement> = account
                    .plan_label
                    .iter()
                    .map(|plan| {
                        div()
                            .child(SharedString::from(plan.clone()))
                            .into_any_element()
                    })
                    .collect();
                if account.active {
                    meta.push(
                        div()
                            .text_color(theme.accent)
                            .child(SharedString::from("In use"))
                            .into_any_element(),
                    );
                }
                if is_signed_out {
                    // The mark that keeps a dead login from reading healthy.
                    meta.push(
                        div()
                            .text_color(theme.danger)
                            .child(SharedString::from("Signed out"))
                            .into_any_element(),
                    );
                }
                // The probe's reason rides the meta line EVEN WHEN stale
                // meters are shown — cached meters used to hide the sign-out
                // (the ticket's display bug).
                if let Some(reason) = account_note(account) {
                    meta.push(
                        div()
                            .text_color(if is_signed_out {
                                theme.danger_muted
                            } else {
                                theme.text_muted
                            })
                            .child(SharedString::from(reason))
                            .into_any_element(),
                    );
                }
                let switch_to = account.clone();
                let sign_in_to = account.clone();
                popover::menu_row(theme, account.active, format!("account-usage-row-{ix}"))
                    .id(("account-usage-row", ix))
                    .when(!can_switch, |row| row.cursor_default())
                    .when(can_switch, |row| {
                        row.on_click(cx.listener(move |usage, _, _, cx| {
                            // Switching to a signed-out account asks first.
                            if signed_out(&switch_to) {
                                usage.confirm_switch = Some(switch_to.clone());
                                cx.notify();
                            } else {
                                usage.switch(&switch_to, cx);
                            }
                        }))
                    })
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(
                                div()
                                    .truncate()
                                    .text_size(px(12.5))
                                    .font_weight(gpui::FontWeight::MEDIUM)
                                    .child(email),
                            )
                            .when(!meta.is_empty(), |el| {
                                el.child(crate::settings::widgets::meta_line(theme, meta))
                            }),
                    )
                    .child(
                        div()
                            .flex_none()
                            .flex()
                            .flex_col()
                            .gap(px(2.0))
                            .children(
                                account
                                    .usage_windows
                                    .iter()
                                    .take(2)
                                    .map(|window| render_usage_meter(window, theme)),
                            )
                            // The signed-out row's own action, nested so the
                            // row click (the switch confirm) still works.
                            .when(is_signed_out, |el| {
                                el.child(
                                    div()
                                        .id(("account-usage-sign-in", ix))
                                        .mt(px(2.0))
                                        .px(px(8.0))
                                        .py(px(3.0))
                                        .rounded(px(6.0))
                                        .bg(theme.accent.opacity(0.14))
                                        .text_size(px(11.5))
                                        .text_color(theme.accent)
                                        .cursor_pointer()
                                        .on_mouse_down(
                                            gpui::MouseButton::Left,
                                            cx.listener(|_, _, _, cx| cx.stop_propagation()),
                                        )
                                        .on_click(cx.listener(move |usage, _, _, cx| {
                                            cx.stop_propagation();
                                            usage.sign_in_again(&sign_in_to, cx);
                                        }))
                                        .child(SharedString::from("Sign in again")),
                                )
                            }),
                    )
            }))
            .children(self.sign_in_footer(cx))
            .children(self.error.clone().map(|error| {
                div()
                    .px(px(8.0))
                    .py(px(4.0))
                    .text_size(px(12.0))
                    .text_color(theme.danger)
                    .child(error)
            }))
    }

    /// The inline "Sign in again" progress: who is being signed in to, the
    /// paste-code field when the CLI has no loopback, and its failure.
    fn sign_in_footer(&self, cx: &mut Context<Self>) -> Option<gpui::Div> {
        let theme = &Theme::of(cx).for_popup();
        let (harness, provider) = self.sign_in.as_ref()?.parts();
        let who = provider
            .and_then(|key| {
                accounts::login_options(harness)
                    .into_iter()
                    .find(|option| option.provider == Some(key))
                    .map(|option| option.label.to_string())
            })
            .unwrap_or_else(|| accounts::provider_name(harness).to_string());
        let mut card = div()
            .mx(px(8.0))
            .my(px(4.0))
            .px(px(8.0))
            .py(px(6.0))
            .rounded(px(6.0))
            .flex()
            .flex_col()
            .gap(px(4.0))
            .bg(theme.wash(0.06))
            .child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(6.0))
                    .child(
                        div()
                            .flex_1()
                            .truncate()
                            .text_size(px(12.0))
                            .text_color(theme.text_muted)
                            .child(SharedString::from(format!(
                                "Signing in to {who} — finish in your browser"
                            ))),
                    )
                    .child(
                        div()
                            .id("account-usage-sign-in-cancel")
                            .px(px(8.0))
                            .py(px(3.0))
                            .rounded(px(6.0))
                            .text_size(px(11.5))
                            .text_color(theme.text_muted)
                            .cursor_pointer()
                            .on_mouse_down(
                                gpui::MouseButton::Left,
                                cx.listener(|_, _, _, cx| cx.stop_propagation()),
                            )
                            .on_click(
                                cx.listener(|usage, _, _, cx| usage.cancel_sign_in(cx)),
                            )
                            .child(SharedString::from("Cancel")),
                    ),
            );
        match self.sign_in.as_ref()? {
            SignInFlow::Starting { .. } | SignInFlow::Browser { .. } => {}
            SignInFlow::PasteCode {
                start,
                submitting,
                error,
                ..
            } => {
                let open_url = start.url.clone();
                card = card
                    .child(
                        div()
                            .id("account-usage-sign-in-url")
                            .text_size(px(11.5))
                            .text_color(theme.text_muted)
                            .cursor_pointer()
                            .hover(|s| s.text_color(theme.text))
                            .on_mouse_down(
                                gpui::MouseButton::Left,
                                cx.listener(|_, _, _, cx| cx.stop_propagation()),
                            )
                            .on_click(cx.listener(move |_, _, _, cx| {
                                cx.open_url(&open_url);
                            }))
                            .child(SharedString::from("Reopen the authorization page")),
                    )
                    .child(
                        div()
                            .px(px(2.0))
                            .child(self.code_input.clone().into_any_element()),
                    )
                    .children(error.clone().map(|error| {
                        div()
                            .text_size(px(11.5))
                            .text_color(theme.danger)
                            .child(error)
                    }))
                    .child(
                        div().flex().flex_row().justify_end().child(
                            div()
                                .id("account-usage-sign-in-submit")
                                .px(px(8.0))
                                .py(px(3.0))
                                .rounded(px(6.0))
                                .bg(theme.accent.opacity(0.14))
                                .text_size(px(11.5))
                                .text_color(theme.accent)
                                .cursor_pointer()
                                .when(*submitting, |el| el.opacity(0.5))
                                .on_mouse_down(
                                    gpui::MouseButton::Left,
                                    cx.listener(|_, _, _, cx| cx.stop_propagation()),
                                )
                                .on_click(cx.listener(|usage, _, _, cx| {
                                    cx.stop_propagation();
                                    usage.submit_sign_in_code(cx);
                                }))
                                .child(SharedString::from(if *submitting {
                                    "Verifying…"
                                } else {
                                    "Add account"
                                })),
                        ),
                    );
            }
        }
        Some(card)
    }
}

/// Which footer ring's popover is up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FooterCard {
    Accounts,
    Context,
}

impl FooterCard {
    fn id(self) -> &'static str {
        match self {
            FooterCard::Accounts => "account-usage-menu",
            FooterCard::Context => "context-usage-menu",
        }
    }
}

/// The footer's ring cluster: account usage (accent arc, so the two read
/// apart), then context occupancy. Each opens its popover on click.
impl Render for AccountUsage {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).clone();
        let context = self.state.read(cx).context_usage;
        let confirm = self.render_switch_confirm(window.viewport_size(), cx);
        let account = self.fraction(cx).map(|fraction| {
            let level = usage_level(fraction);
            let chip = crate::context_usage::ring_chip(
                "account-usage",
                fraction,
                usage_color(level, &theme),
                match level {
                    UsageLevel::Normal => theme.text_muted,
                    _ => usage_color(level, &theme),
                },
                format!("{}%", (fraction * 100.0).round() as u32),
                self.popup.get() == Some(&FooterCard::Accounts),
                &theme,
            );
            self.trigger(
                chip,
                FooterCard::Accounts,
                |usage, cx| usage.accounts_card(cx),
                cx,
            )
        });
        let context = crate::context_usage::has_window(context).then(|| {
            let chip = crate::context_usage::chip(
                context,
                self.popup.get() == Some(&FooterCard::Context),
                &theme,
            );
            self.trigger(
                chip,
                FooterCard::Context,
                move |_, cx| crate::context_usage::card(context, &Theme::of(cx).for_popup()),
                cx,
            )
        });
        div()
            .flex()
            .items_center()
            .gap(px(4.0))
            .children(account)
            .children(context)
            .when_some(confirm, |el, confirm| el.child(confirm))
    }
}

impl AccountUsage {
    /// The "switch to a signed-out account" confirmation, over the popover —
    /// the same warning Settings → Accounts asks with.
    fn render_switch_confirm(
        &mut self,
        viewport: gpui::Size<gpui::Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<gpui::AnyElement> {
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
                        popover::btn_ghost(&theme, "Cancel", "usage-switch-sign-out-cancel")
                            .id("usage-switch-sign-out-cancel")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.confirm_switch = None;
                                cx.notify();
                            })),
                    )
                    .child(
                        popover::btn_primary(&theme, "Switch anyway")
                            .id("usage-switch-sign-out-confirm")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let account = this.confirm_switch.take();
                                if let Some(account) = account {
                                    this.switch(&account, cx);
                                }
                                cx.notify();
                            })),
                    ),
            );
        Some(popover::modal(
            "account-usage-switch-confirm",
            viewport,
            card.into_any_element(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::AgentUsageWindow;

    fn account(harness: HarnessId, active: bool, used: &[f32]) -> AgentAccount {
        serde_json::from_value(serde_json::json!({
            "id": format!("{harness:?}-{active}"),
            "harness": harness,
            "email": null,
            "planLabel": null,
            "active": active,
            "switchable": true,
            "usageWindows": used
                .iter()
                .map(|fraction| AgentUsageWindow {
                    label: "5h".into(),
                    used_fraction: *fraction,
                    resets_at: None,
                })
                .collect::<Vec<_>>(),
        }))
        .unwrap()
    }

    #[test]
    fn ring_shows_the_most_used_window() {
        assert_eq!(used_fraction(&account(HarnessId::Codex, true, &[])), None);
        assert_eq!(
            used_fraction(&account(HarnessId::Codex, true, &[0.12, 0.64])),
            Some(0.64)
        );
        assert_eq!(
            used_fraction(&account(HarnessId::Codex, true, &[1.4])),
            Some(1.0)
        );
    }

    #[test]
    fn active_account_is_scoped_to_the_harness() {
        let snapshot = AgentAccountsSnapshot {
            accounts: vec![
                account(HarnessId::ClaudeCode, true, &[0.3]),
                account(HarnessId::Codex, false, &[0.1]),
                account(HarnessId::Codex, true, &[0.2]),
            ],
            warnings: Vec::new(),
        };
        assert_eq!(
            active_account(&snapshot, HarnessId::Codex).map(|a| a.id.as_str()),
            Some("Codex-true")
        );
        assert!(active_account(&snapshot, HarnessId::Cursor).is_none());
    }

    /// A signed-out account parses off the wire, is marked, and explains
    /// itself BESIDE its cached meters — the popover used to show the reason
    /// only when no windows existed, hiding a sign-out behind stale meters.
    #[test]
    fn signed_out_accounts_read_from_the_wire_and_show_their_reason() {
        let mut account = account(HarnessId::ClaudeCode, false, &[0.4]);
        assert!(!signed_out(&account), "a wire payload without the field is unknown");
        account.sign_in_state = roboco_proto::AgentSignInState::SignedOut;
        assert!(signed_out(&account));
        assert_eq!(
            account_note(&account).as_deref(),
            Some("Signed out — sign in again"),
            "the note exists even without a probe failure behind it"
        );
        // The reason shows WITH the meters — the display fix.
        assert!(!account.usage_windows.is_empty());
        // A probe failure is the note whatever the state.
        account.usage_error = Some("Rate limited by Anthropic — retrying in 2m".into());
        assert_eq!(
            account_note(&account).as_deref(),
            Some("Rate limited by Anthropic — retrying in 2m")
        );
    }
}
