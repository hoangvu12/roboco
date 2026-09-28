//! Settings → Agents: install and enable harnesses, one card row per agent.
//!
//! The state is PER-DEVICE and lives on the engine (`harness-prefs.json` in
//! its data dir): CLI installs are per-device, so enablement is too. The
//! page-header device switcher (the Accounts pattern) retargets both the
//! `ListHarnesses` probe and the `SetHarnessEnabled`/`InstallHarness` writes
//! at the selected paired engine (engine-local routing: the page calls the
//! engine being driven directly — `request_routing::device_target` — and
//! never through any relay).
//!
//! Enablement follows DETECTION: every harness whose CLI probe passes is on
//! unless the user switched it off, so installing an agent is all it takes
//! for it to appear here and in the composer. A harness whose CLI is missing
//! on the target device renders dimmed with an install hint and is never
//! enabled (enabling an agent that can't run would only manufacture
//! NotInstalled errors at send time); an ENABLED agent can always be turned
//! OFF except the last one standing — the composer needs something to run.
//! Catalogs from engines predating the detection model can still stamp
//! enabled-but-uninstalled rows; the hint covers that too. The engine
//! enforces the same gates where the state lives, so a raced or stale toggle
//! self-corrects from the RPC reply.

use gpui::{
    AnyElement, Context, Entity, IntoElement, Render, SharedString, Task, Window, div, prelude::*,
    px,
};

use std::time::Duration;

use roboco_engine::registry::TitleSettings;
use roboco_engine::registry::{HarnessDescriptor, descriptor_enabled};
use roboco_proto::Model;
use roboco_proto::{
    AgentLoginPoll, AgentLoginStart, AgentLoginStatus, HarnessId, HarnessUpdatePhase,
    HarnessUpdatePolicy, HarnessUpdateStatus,
};
use roboco_rpc::methods;

use crate::pickers::visible_harnesses;
use crate::popover::{self, Loadable};
use crate::settings::widgets;
use crate::motion;
use crate::state::AppState;
use crate::theme::Theme;

/// Left inset of an expanded provider's details: the header trigger's
/// padding, brand tile and gap, so the details start on the title's edge.
const DETAILS_INSET: f32 = 4.0 + 36.0 + 12.0;

/// One-line blurb per agent; the catalog descriptor does not carry one.
pub fn blurb(harness: HarnessId) -> &'static str {
    match harness {
        HarnessId::ClaudeCode => "Anthropic's coding agent, driven through the Claude Code CLI.",
        HarnessId::Codex => "OpenAI's coding agent, driven through the Codex CLI.",
        HarnessId::Cursor => "Cursor's coding agent, driven through the cursor-agent CLI.",
        HarnessId::Devin => "Cognition's Devin agent (devin CLI).",
        HarnessId::Grok => "xAI's Grok Build agent (grok CLI).",
        HarnessId::Hermes => "Nous Research's Hermes Agent (hermes CLI).",
        HarnessId::Pi => "The pi coding agent (pi CLI).",
        HarnessId::Opencode => "SST's opencode agent (opencode CLI).",
        HarnessId::Antigravity => "Google's Antigravity agent (Antigravity ACP server).",
        HarnessId::Mock => "Scripted test harness.",
    }
}

fn offers_sign_in(harness: HarnessId, installed: bool) -> bool {
    harness == HarnessId::Antigravity && installed
}

/// The CLI named in the not-installed hint.
pub fn cli_name(harness: HarnessId) -> &'static str {
    match harness {
        HarnessId::ClaudeCode => "claude",
        HarnessId::Codex => "codex",
        HarnessId::Cursor => "cursor-agent",
        HarnessId::Devin => "devin",
        HarnessId::Grok => "grok",
        HarnessId::Hermes => "hermes",
        HarnessId::Pi => "pi",
        HarnessId::Opencode => "opencode",
        HarnessId::Antigravity => "Antigravity",
        HarnessId::Mock => "mock",
    }
}

/// Update policies in menu order: the short label, and the note shown under
/// the row title for the chosen one.
const UPDATE_POLICIES: [(HarnessUpdatePolicy, &str, &str); 3] = [
    (
        HarnessUpdatePolicy::Notify,
        "Notify",
        "Install only when you choose Update.",
    ),
    (
        HarnessUpdatePolicy::AutoWhenIdle,
        "Auto when idle",
        "Install automatically after active runs finish.",
    ),
    (
        HarnessUpdatePolicy::Off,
        "Off",
        "Don't check for new versions.",
    ),
];

/// The one-line update status for the row's meta line (phase-tinted).
fn harness_update_label(status: &HarnessUpdateStatus, theme: &Theme) -> (SharedString, gpui::Hsla) {
    let installed = status
        .installed_version
        .as_deref()
        .map(|version| format!("v{version}"))
        .unwrap_or_else(|| "Version unavailable".into());
    let label = match status.phase {
        HarnessUpdatePhase::Dormant => "Update monitoring off".into(),
        HarnessUpdatePhase::Checking => "Checking for updates…".into(),
        HarnessUpdatePhase::Current => format!("{installed} · Up to date"),
        HarnessUpdatePhase::Available => {
            let available = match status.latest_version.as_deref() {
                Some(latest) => format!("{installed} · v{latest} available"),
                None => format!("{installed} · Update available"),
            };
            if status.can_apply {
                available
            } else {
                status
                    .manual_command
                    .as_deref()
                    .map(|instruction| format!("{available} · {instruction}"))
                    .unwrap_or(available)
            }
        }
        HarnessUpdatePhase::WaitingForIdle => format!("{installed} · Waiting for agent to be idle"),
        HarnessUpdatePhase::Preparing => format!("{installed} · Preparing update…"),
        HarnessUpdatePhase::Downloading => format!("{installed} · Downloading…"),
        HarnessUpdatePhase::Installing => format!("{installed} · Installing…"),
        HarnessUpdatePhase::Verifying => "Verifying updated CLI…".into(),
        HarnessUpdatePhase::Updated => format!("{installed} · Updated"),
        HarnessUpdatePhase::ManualActionRequired => status
            .manual_command
            .as_deref()
            .map(|command| format!("{installed} · {command}"))
            .unwrap_or_else(|| format!("{installed} · Manual update checks")),
        HarnessUpdatePhase::Failed => status
            .error
            .as_ref()
            .map(|error| format!("Update check failed · {}", error.message))
            .unwrap_or_else(|| "Update check failed".into()),
    };
    let color = match status.phase {
        HarnessUpdatePhase::Available => theme.accent,
        HarnessUpdatePhase::Updated => theme.success,
        HarnessUpdatePhase::Failed => theme.danger,
        HarnessUpdatePhase::ManualActionRequired => theme.warning_muted,
        _ => theme.text_muted.opacity(0.75),
    };
    (label.into(), color)
}

pub struct HarnessesPage {
    title_settings: Loadable<TitleSettings>,
    title_models: Loadable<Vec<Model>>,
    title_menu: Option<bool>, // false = harness, true = model
    title_task: Option<Task<()>>,
    title_saving: bool,
    state: Entity<AppState>,
    scroll: widgets::PageScroll,
    harnesses: Loadable<Vec<HarnessDescriptor>>,
    /// Which device's harnesses are shown/edited; `None` = this device (no
    /// passthrough). Retargeted by the page-header device switcher.
    target_device: Option<String>,
    device_menu_open: bool,
    /// Whether the menu was open when the trigger press began — the menu's
    /// `on_mouse_down_out` closes it on that same press, so by click time a
    /// plain toggle would reopen (the [`popover::Popup`] press note, for
    /// this page's bool-state menu).
    device_menu_pressed_open: bool,
    /// Last refused/failed toggle (engine guards), shown in the error strip.
    error: Option<String>,
    load_task: Option<Task<()>>,
    toggle_task: Option<Task<()>>,
    installing: Option<HarnessId>,
    install_task: Option<Task<()>>,
    /// a sign-in that switches its harness on once it succeeds.
    sign_in: Option<SignIn>,
    sign_in_failure: Option<SignInFailure>,
    sign_in_task: Option<Task<()>>,
    /// The provider whose expanded details are open (`None` = all collapsed).
    expanded_harness: Option<HarnessId>,
    /// The target device's agent-CLI update lifecycle (standing watch).
    updates: Loadable<Vec<HarnessUpdateStatus>>,
    /// Open-menu state per harness's update-policy dropdown.
    policy_selects: std::collections::HashMap<HarnessId, widgets::SelectState>,
    update_task: Option<Task<()>>,
    update_action_task: Option<Task<()>>,
}

struct SignIn {
    harness: HarnessId,
    /// known once the engine accepted the start.
    login_id: Option<String>,
    message: Option<String>,
    phase: SignInPhase,
}

#[derive(Clone, Copy)]
enum SignInPhase {
    Starting,
    Authenticating,
}

struct SignInFailure {
    harness: HarnessId,
    message: String,
    phase: SignInPhase,
}

impl SignInPhase {
    fn pending_label(self) -> &'static str {
        match self {
            Self::Starting => "Preparing Antigravity…",
            Self::Authenticating => "Finish signing in in your browser.",
        }
    }

    fn failure_label(self) -> &'static str {
        match self {
            Self::Starting => "Setup failed",
            Self::Authenticating => "Sign-in failed",
        }
    }
}

fn offers_install(harness: HarnessId, installed: bool, can_install: bool) -> bool {
    harness != HarnessId::Mock && !installed && can_install
}

fn install_hint(harness: HarnessId, enabled: bool, can_install: bool) -> String {
    if harness == HarnessId::Antigravity {
        return if can_install {
            "Install Antigravity to enable"
        } else {
            "Set ANTIGRAVITY_ACP_EXECUTABLE to enable Antigravity"
        }
        .into();
    }
    let hint = if enabled {
        format!(
            "{} CLI not installed — turn it off or install it",
            cli_name(harness)
        )
    } else {
        format!("Install the {} CLI to enable", cli_name(harness))
    };
    if !can_install
        && let Some(command) = roboco_harness::install::manual_command(harness)
    {
        format!("{hint}. Install with `{command}`")
    } else {
        hint
    }
}

fn install_label(name: &str) -> String {
    format!("Installing {name}…")
}

fn install_params(harness: HarnessId, target: &Option<String>) -> serde_json::Value {
    serde_json::json!({"harness": harness, "targetDeviceId": target})
}


impl HarnessesPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let mut page = Self {
            title_settings: Loadable::Idle,
            title_models: Loadable::Idle,
            title_menu: None,
            title_task: None,
            title_saving: false,
            state,
            scroll: widgets::PageScroll::default(),
            harnesses: Loadable::Idle,
            target_device: None,
            device_menu_open: false,
            device_menu_pressed_open: false,
            error: None,
            load_task: None,
            toggle_task: None,
            installing: None,
            install_task: None,
            sign_in: None,
            sign_in_failure: None,
            sign_in_task: None,
            expanded_harness: None,
            updates: Loadable::Idle,
            policy_selects: Default::default(),
            update_task: None,
            update_action_task: None,
        };
        page.load(cx);
        page
    }

    /// Params with the `targetDeviceId` passthrough merged in.
    fn with_target(&self, mut value: serde_json::Value) -> serde_json::Value {
        if let (Some(target), Some(object)) = (&self.target_device, value.as_object_mut()) {
            object.insert("targetDeviceId".into(), serde_json::json!(target));
        }
        value
    }

    /// Retarget the page at another device: a different device is a different
    /// install/enablement world, so drop the rows and reload through it.
    fn set_target_device(&mut self, target: Option<String>, cx: &mut Context<Self>) {
        self.device_menu_open = false;
        if self.target_device == target {
            cx.notify();
            return;
        }
        self.cancel_sign_in(cx);
        self.title_task = None;
        self.title_settings = Loadable::Idle;
        self.title_models = Loadable::Idle;
        self.title_menu = None;
        self.title_saving = false;
        self.installing = None;
        self.install_task = None;
        self.target_device = target;
        self.error = None;
        self.sign_in_failure = None;
        self.harnesses = Loadable::Idle;
        self.expanded_harness = None;
        self.policy_selects.clear();
        self.updates = Loadable::Idle;
        self.update_task = None;
        self.update_action_task = None;
        self.load(cx);
        cx.notify();
    }

    /// `ListHarnesses` against the target device (installed probe + enabled
    /// set both come from where the CLIs actually live), plus the standing
    /// update watch when the target engine advertises `harness-updates-v1`.
    fn load(&mut self, cx: &mut Context<Self>) {
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let params = self.with_target(serde_json::json!({}));
        let supports_updates = self.supports_updates(cx);
        let update_engine = engine.clone();
        self.load_titles(None, cx);
        self.harnesses = Loadable::Loading;
        self.load_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::LIST_HARNESSES, params).await;
            this.update(cx, |page, cx| {
                page.harnesses = match result {
                    Ok(value) => match serde_json::from_value::<Vec<HarnessDescriptor>>(value) {
                        Ok(list) => Loadable::Ready(list),
                        Err(err) => Loadable::Error(err.to_string()),
                    },
                    Err(err) => Loadable::Error(err.to_string()),
                };
                cx.notify();
            })
            .ok();
        }));
        self.updates = if supports_updates {
            Loadable::Loading
        } else {
            Loadable::Ready(Vec::new())
        };
        self.update_task = supports_updates.then(|| {
            let update_params = self.with_target(serde_json::json!({}));
            cx.spawn(async move |this, cx| {
                let mut retry = 1;
                loop {
                    match update_engine
                        .subscribe(methods::WATCH_HARNESS_UPDATES, update_params.clone())
                        .await
                    {
                        Ok(mut stream) => {
                            while let Some(value) = stream.recv().await {
                                retry = 1;
                                let parsed =
                                    serde_json::from_value::<Vec<HarnessUpdateStatus>>(value)
                                        .map_err(|error| error.to_string());
                                if this
                                    .update(cx, |page, cx| {
                                        page.updates = match parsed {
                                            Ok(statuses) => Loadable::Ready(statuses),
                                            Err(error) => Loadable::Error(error),
                                        };
                                        cx.notify();
                                    })
                                    .is_err()
                                {
                                    return;
                                }
                            }
                        }
                        Err(error) => {
                            this.update(cx, |page, cx| {
                                page.updates = Loadable::Error(error.to_string());
                                cx.notify();
                            })
                            .ok();
                        }
                    }
                    if this
                        .update(cx, |page, cx| {
                            if !matches!(page.updates, Loadable::Error(_)) {
                                page.updates = Loadable::Error(
                                    "Connection closed. Reconnecting to device…".into(),
                                );
                            }
                            cx.notify();
                        })
                        .is_err()
                    {
                        return;
                    }
                    cx.background_executor()
                        .timer(std::time::Duration::from_secs(retry))
                        .await;
                    retry = (retry * 2).min(15);
                }
            })
        });
    }

    /// Whether the target engine serves the harness-update lifecycle. The
    /// page retargets through `request_routing::device_target`, so the
    /// capability read comes from the TARGET engine's info — exactly like the
    /// calls themselves.
    fn supports_updates(&self, cx: &Context<Self>) -> bool {
        crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref())
            .map(|target| {
                target
                    .engine_info()
                    .supports(roboco_proto::capabilities::HARNESS_UPDATES_V1)
            })
            .unwrap_or(false)
    }

    fn can_control_updates(&self, cx: &Context<Self>) -> bool {
        self.supports_updates(cx) && matches!(self.updates, Loadable::Ready(_))
    }

    /// Toggle one provider's expanded details (one row open at a time).
    fn toggle_agent_details(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        if self.expanded_harness == Some(harness) {
            self.expanded_harness = None;
        } else {
            self.expanded_harness = Some(harness);
        }
        cx.notify();
    }

    fn load_titles(&mut self, save: Option<TitleSettings>, cx: &mut Context<Self>) {
        let Some(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()).ok() else {
            return;
        };
        let saving = save.is_some();
        let method = if saving {
            methods::SET_TITLE_SETTINGS
        } else {
            methods::GET_TITLE_SETTINGS
        };
        let params = self.with_target(
            save.map(|s| serde_json::to_value(s).unwrap())
                .unwrap_or_else(|| serde_json::json!({})),
        );
        let target = self.target_device.clone();
        self.title_menu = None;
        self.title_saving = saving;
        self.title_task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .call(method, params)
                .await
                .map_err(|e| e.to_string())
                .and_then(|v| {
                    serde_json::from_value::<TitleSettings>(v).map_err(|e| e.to_string())
                });
            let settings = match result {
                Ok(settings) => settings,
                Err(error) => {
                    this.update(cx, |page, cx| {
                        if saving {
                            page.error = Some(error);
                        } else {
                            page.title_settings = Loadable::Error(error);
                        }
                        page.title_saving = false;
                        cx.notify();
                    })
                    .ok();
                    return;
                }
            };
            let harness = settings.harness;
            this.update(cx, |page, cx| {
                page.title_settings = Loadable::Ready(settings);
                page.title_models = if harness.is_some() {
                    Loadable::Loading
                } else {
                    Loadable::Idle
                };
                page.title_saving = false;
                page.error = None;
                cx.notify();
            })
            .ok();
            if let Some(harness) = harness {
                let result = engine
                    .call(
                        methods::LIST_MODELS,
                        serde_json::json!({"harness": harness, "targetDeviceId": target}),
                    )
                    .await
                    .map_err(|e| e.to_string())
                    .and_then(|v| {
                        serde_json::from_value::<Vec<Model>>(v).map_err(|e| e.to_string())
                    });
                this.update(cx, |page, cx| {
                    page.title_models = match result {
                        Ok(models) => Loadable::Ready(models),
                        Err(error) => Loadable::Error(error),
                    };
                    cx.notify();
                })
                .ok();
            }
        }));
        cx.notify();
    }

    fn render_titles(&self, theme: &Theme, cx: &mut Context<Self>) -> AnyElement {
        let mut card = widgets::section_card(theme).mt(px(20.0)).p(px(16.0))
            .child(widgets::row_title(theme, "Session titles"))
            .child(widgets::page_subtitle(theme, "Choose the agent and model for automatic titles on this device. Claude Code and Codex support restricted title generation."));
        let Loadable::Ready(settings) = &self.title_settings else {
            let message = match &self.title_settings {
                Loadable::Error(error) => error.clone(),
                _ => "Loading title settings…".into(),
            };
            return card
                .child(div().mt(px(8.0)).child(message))
                .into_any_element();
        };
        for is_model in [false, true] {
            let label = if is_model {
                settings
                    .model
                    .as_ref()
                    .map(|id| {
                        if let Loadable::Ready(models) = &self.title_models {
                            models
                                .iter()
                                .find(|m| &m.id == id)
                                .map(|m| m.label.clone())
                                .unwrap_or_else(|| id.clone())
                        } else {
                            id.clone()
                        }
                    })
                    .unwrap_or_else(|| "Automatic (cheapest model)".into())
            } else {
                settings
                    .harness
                    .map(|id| match id {
                        HarnessId::ClaudeCode => "Claude Code".to_string(),
                        HarnessId::Codex => "Codex".to_string(),
                        _ => format!("{id:?}"),
                    })
                    .unwrap_or_else(|| "Automatic (session agent when supported)".into())
            };
            let interactive = !self.title_saving && (!is_model || settings.harness.is_some());
            let mut row = div()
                .mt(px(12.0))
                .child(widgets::row_title(
                    theme,
                    if is_model {
                        "Title model"
                    } else {
                        "Title harness"
                    },
                ))
                .child(
                    widgets::ghost_action(theme)
                        .id(if is_model {
                            "title-model"
                        } else {
                            "title-harness"
                        })
                        .when(interactive, |el| {
                            el.cursor_pointer()
                                .on_click(cx.listener(move |page, _, _, cx| {
                                    page.title_menu = if page.title_menu == Some(is_model) {
                                        None
                                    } else {
                                        Some(is_model)
                                    };
                                    cx.notify();
                                }))
                        })
                        .when(!interactive, |el| el.opacity(0.5))
                        .child(label),
                );
            if self.title_menu == Some(is_model) {
                let mut choices = vec![(
                    "Automatic".to_string(),
                    TitleSettings {
                        harness: if is_model { settings.harness } else { None },
                        model: None,
                    },
                )];
                if is_model {
                    if let Loadable::Ready(models) = &self.title_models {
                        choices.extend(models.iter().map(|m| {
                            (
                                m.label.clone(),
                                TitleSettings {
                                    harness: settings.harness,
                                    model: Some(m.id.clone()),
                                },
                            )
                        }));
                    }
                } else if let Loadable::Ready(harnesses) = &self.harnesses {
                    choices.extend(
                        harnesses
                            .iter()
                            .filter(|h| {
                                descriptor_enabled(h)
                                    && h.installed
                                    && roboco_harness::supports_titles(h.id)
                                    && h.id != HarnessId::Mock
                            })
                            .map(|h| {
                                (
                                    h.name.clone(),
                                    TitleSettings {
                                        harness: Some(h.id),
                                        model: None,
                                    },
                                )
                            }),
                    );
                }
                row =
                    row.child(
                        div()
                            .id(if is_model {
                                "title-model-options"
                            } else {
                                "title-harness-options"
                            })
                            .max_h(px(240.0))
                            .overflow_y_scroll()
                            .children(choices.into_iter().enumerate().map(
                                |(ix, (label, choice))| {
                                    popover::menu_row(
                                        theme,
                                        &choice == settings,
                                        format!("title-choice-{is_model}-{ix}"),
                                    )
                                    .id(("title-choice", ix))
                                    .on_click(cx.listener(move |page, _, _, cx| {
                                        page.load_titles(Some(choice.clone()), cx)
                                    }))
                                    .child(label)
                                },
                            )),
                    );
            }
            card = card.child(row);
        }
        if let Loadable::Error(error) = &self.title_models {
            card = card.child(widgets::error_strip(theme, error.clone()));
        }
        card.into_any_element()
    }

    /// Flip one harness on the target device. The reply carries the device's
    /// fresh catalog, so the rows repaint from the authoritative state in one
    /// round trip; refusals (engine guards) land in the error strip.
    fn toggle(&mut self, harness: HarnessId, enabled: bool, cx: &mut Context<Self>) {
        self.set_enabled(harness, enabled, cx);
    }

    /// Explicit sign-in: StartAgentLogin, then PollAgentLogin until the
    /// engine reports the outcome, opening the sign-in page the first time a
    /// poll names it.
    fn start_sign_in(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        if self.target_device.is_some() {
            // the sign-in redirect lands on a loopback port of the device
            // running the agent, which a browser here can't reach
            self.error = Some("Sign in to this agent from its own device.".into());
            cx.notify();
            return;
        }
        let Some(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()).ok() else {
            return;
        };
        self.error = None;
        self.sign_in_failure = None;
        self.sign_in = Some(SignIn {
            harness,
            login_id: None,
            message: None,
            phase: SignInPhase::Starting,
        });
        let start_params = serde_json::json!({ "harness": harness });
        self.sign_in_task = Some(cx.spawn(async move |this, cx| {
            let started = engine
                .call(methods::START_AGENT_LOGIN, start_params)
                .await
                .map_err(|e| e.to_string())
                .and_then(|value| {
                    serde_json::from_value::<AgentLoginStart>(value).map_err(|e| e.to_string())
                });
            let login_id = match started {
                Ok(start) => start.login_id,
                Err(error) => {
                    this.update(cx, |page, cx| {
                        page.fail_sign_in(harness, format!("Sign-in failed to start: {error}"));
                        cx.notify();
                    })
                    .ok();
                    return;
                }
            };
            this.update(cx, |page, _| {
                if let Some(sign_in) = &mut page.sign_in {
                    sign_in.login_id = Some(login_id.clone());
                    sign_in.phase = SignInPhase::Starting;
                }
            })
            .ok();
            let poll_params = serde_json::json!({ "loginId": login_id });
            let mut opened = false;
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(1000))
                    .await;
                let poll = engine
                    .call(methods::POLL_AGENT_LOGIN, poll_params.clone())
                    .await
                    .map_err(|e| e.to_string())
                    .and_then(|value| {
                        serde_json::from_value::<AgentLoginPoll>(value).map_err(|e| e.to_string())
                    });
                let finished = this.update(cx, |page, cx| {
                    let finished = match poll {
                        Ok(poll) => match poll.status {
                            AgentLoginStatus::Pending => {
                                if !opened && let Some(url) = &poll.url {
                                    opened = true;
                                    cx.open_url(url);
                                }
                                if let Some(sign_in) = &mut page.sign_in {
                                    if poll.url.is_some() {
                                        sign_in.phase = SignInPhase::Authenticating;
                                    }
                                    sign_in.message = poll.message;
                                }
                                false
                            }
                            AgentLoginStatus::Done => {
                                page.sign_in_failure = None;
                                page.sign_in = None;
                                crate::pickers::bump_harness_catalog(cx);
                                true
                            }
                            AgentLoginStatus::Error => {
                                page.fail_sign_in(
                                    harness,
                                    poll.message.unwrap_or_else(|| "Unknown error".into()),
                                );
                                true
                            }
                        },
                        Err(error) => {
                            page.fail_sign_in(harness, error);
                            true
                        }
                    };
                    cx.notify();
                    finished
                });
                if finished.unwrap_or(true) {
                    break;
                }
            }
        }));
        cx.notify();
    }

    fn fail_sign_in(&mut self, harness: HarnessId, message: String) {
        let phase = self
            .sign_in
            .take()
            .filter(|sign_in| sign_in.harness == harness)
            .map(|sign_in| sign_in.phase)
            .unwrap_or(SignInPhase::Starting);
        self.sign_in_failure = Some(SignInFailure {
            harness,
            message,
            phase,
        });
    }

    fn cancel_sign_in(&mut self, cx: &mut Context<Self>) {
        let Some(sign_in) = self.sign_in.take() else {
            return;
        };
        self.sign_in_task = None;
        if let (Some(login_id), Ok(engine)) = (
            sign_in.login_id,
            crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()),
        ) {
            cx.spawn(async move |_, _| {
                if let Err(err) = engine
                    .call(
                        methods::CANCEL_AGENT_LOGIN,
                        serde_json::json!({ "loginId": login_id }),
                    )
                    .await
                {
                    tracing::debug!(error = %err, "CancelAgentLogin failed (best-effort)");
                }
            })
            .detach();
        }
        cx.notify();
    }

    /// Re-check every provider on the target engine (or one, when the row
    /// pinned a provider). The watch republishes the result regardless.
    fn check_updates(&mut self, cx: &mut Context<Self>) {
        if !self.can_control_updates(cx) {
            return;
        }
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let params = self.with_target(serde_json::json!({}));
        self.error = None;
        self.update_action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::CHECK_HARNESS_UPDATES, params).await;
            this.update(cx, |page, cx| {
                match result {
                    Ok(value) => match serde_json::from_value::<Vec<HarnessUpdateStatus>>(value) {
                        Ok(statuses) => page.updates = Loadable::Ready(statuses),
                        Err(error) => page.error = Some(error.to_string()),
                    },
                    Err(error) => page.error = Some(error.to_string()),
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// Apply one provider's discovered release on the target engine. The
    /// engine owns the mutation; progress lands through the watch.
    fn apply_harness_update(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        if !self.can_control_updates(cx) {
            return;
        }
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let params = self.with_target(serde_json::json!({ "harness": harness }));
        self.error = None;
        self.update_action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::APPLY_HARNESS_UPDATE, params).await;
            this.update(cx, |page, cx| {
                if let Err(error) = result {
                    page.error = Some(error.to_string());
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// Cancel one provider's waiting/preparing/downloading update.
    fn cancel_harness_update(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        if !self.can_control_updates(cx) {
            return;
        }
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let params = self.with_target(serde_json::json!({ "harness": harness }));
        self.update_action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::CANCEL_HARNESS_UPDATE, params).await;
            this.update(cx, |page, cx| {
                if let Err(error) = result {
                    page.error = Some(error.to_string());
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// Set one provider's update policy (Notify / Auto when idle / Off). The
    /// engine persists it per-device and replies with the fresh row.
    fn set_update_policy(
        &mut self,
        harness: HarnessId,
        policy: HarnessUpdatePolicy,
        cx: &mut Context<Self>,
    ) {
        if !self.can_control_updates(cx) {
            return;
        }
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let params = self.with_target(serde_json::json!({
            "harness": harness,
            "policy": policy,
        }));
        self.error = None;
        self.update_action_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::SET_HARNESS_UPDATE_POLICY, params).await;
            this.update(cx, |page, cx| {
                if let Err(error) = result {
                    page.error = Some(error.to_string());
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// Run one explicit install ON THE TARGET ENGINE (engine-local routing:
    /// `request_routing::device_target` resolves the paired engine's
    /// connection — upstream's relay forward is deliberately excluded). The
    /// reply is the device's fresh catalog, so the rows repaint from the
    /// authoritative state in one round trip.
    fn cancel_install(&mut self, cx: &mut Context<Self>) {
        let Some(harness) = self.installing else {
            return;
        };
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        let target = self.target_device.clone();
        let params = install_params(harness, &target);
        cx.spawn(async move |this, cx| {
            if let Err(error) = engine.call(methods::CANCEL_INSTALL, params).await {
                this.update(cx, |page, cx| {
                    if page.target_device == target && page.installing == Some(harness) {
                        page.error = Some(format!("Cancellation failed — {error}"));
                        cx.notify();
                    }
                })
                .ok();
            }
        })
        .detach();
    }

    fn install(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        let Ok(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()) else {
            return;
        };
        if self.installing.is_some() {
            return;
        }
        let params = install_params(harness, &self.target_device);
        let target = self.target_device.clone();
        self.installing = Some(harness);
        self.error = None;
        self.install_task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .call(methods::INSTALL_HARNESS, params)
                .await
                .map_err(|error| error.to_string())
                .and_then(|value| {
                    serde_json::from_value::<Vec<HarnessDescriptor>>(value)
                        .map_err(|error| error.to_string())
                });
            this.update(cx, |page, cx| {
                if page.target_device != target {
                    return;
                }
                page.installing = None;
                match result {
                    Ok(list) => {
                        page.harnesses = Loadable::Ready(list);
                        crate::pickers::bump_harness_catalog(cx);
                    }
                    Err(error) => page.error = Some(format!("Installation failed — {error}")),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn set_enabled(&mut self, harness: HarnessId, enabled: bool, cx: &mut Context<Self>) {
        let Some(engine) = crate::request_routing::device_target(self.state.read(cx), self.target_device.as_deref()).ok() else {
            return;
        };
        let params = self.with_target(serde_json::json!({
            "harness": harness,
            "enabled": enabled,
        }));
        self.error = None;
        self.toggle_task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .call(methods::SET_HARNESS_ENABLED, params)
                .await;
            this.update(cx, |page, cx| {
                match result {
                    Ok(value) => {
                        if let Ok(list) = serde_json::from_value::<Vec<HarnessDescriptor>>(value) {
                            page.harnesses = Loadable::Ready(list);
                        }
                        // The composer caches its catalog per space — poke
                        // every Pickers to re-fetch, or the rail keeps the
                        // old set until restart.
                        crate::pickers::bump_harness_catalog(cx);
                    }
                    Err(err) => page.error = Some(err.to_string()),
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// The page-header device switcher (the Accounts pattern): platform glyph
    /// · name · presence dot · sort glyph, opening a dropdown of every
    /// registered device.
    fn render_device_switcher(&mut self, theme: &Theme, cx: &mut Context<Self>) -> AnyElement {
        use crate::icons::{self, icon};
        let (mut devices, local_id) = {
            let s = self.state.read(cx);
            (s.devices.clone(), s.local_device_id.clone())
        };
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
        let open = self.device_menu_open;

        let mut trigger =
            div()
                .id("harnesses-device-switcher")
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
                    cx.listener(|this, _, _, _| {
                        this.device_menu_pressed_open = this.device_menu_open;
                    }),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    // A press that found the menu open closes it — never
                    // reopen on the same gesture.
                    let pressed_open = std::mem::take(&mut this.device_menu_pressed_open);
                    this.device_menu_open = !pressed_open && !this.device_menu_open;
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

        if open {
            let theme = &theme.for_popup();
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
                    popover::menu_row(theme, is_active, format!("harnesses-device-row-{ix}"))
                        .id(("harnesses-device-row", ix))
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
                                    .text_color(theme.text_muted)
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
                    this.device_menu_open = false;
                    cx.notify();
                }))
                .flex()
                .flex_col()
                .child(popover::menu_heading(theme, "Devices"))
                // Contained to the settings page pane like the Accounts
                // device switcher: flip-above placement, scrolling rows.
                .child(widgets::dropdown_rows(
                    "harnesses-device-rows",
                    rows,
                    32.0,
                ));
            trigger = trigger.child(widgets::dropdown("harnesses-device-menu", menu, None, 28.0));
        }
        trigger.into_any_element()
    }

    /// The expanded provider's details. No box of its own: the details
    /// continue the provider row on the title's edge (the trigger's padding +
    /// brand tile + gap).
    fn render_agent_details(
        &self,
        harness: HarnessId,
        theme: &Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let content = div()
            .flex()
            .flex_col()
            .ml(px(DETAILS_INSET))
            .pb(px(10.0))
            .children(self.render_updates_for(harness, theme, cx));
        Some(
            motion::menu_in(format!("agent-details-{harness:?}"), content).into_any_element(),
        )
    }

    /// The expanded provider's Updates section: the update policy, short
    /// labels in the menu and the chosen one explained under the title.
    fn render_updates_for(
        &self,
        harness: HarnessId,
        theme: &Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let Loadable::Ready(statuses) = &self.updates else {
            return None;
        };
        let status = statuses.iter().find(|status| status.harness == harness)?;
        let selected = UPDATE_POLICIES
            .iter()
            .position(|(policy, ..)| *policy == status.policy)
            .unwrap_or(0);
        let closed = widgets::SelectState::default();
        let policy_select = widgets::select(
            format!("harness-update-policy-{harness:?}"),
            "Update policy",
            theme,
            move |page: &mut Self| page.policy_selects.entry(harness).or_default(),
        )
        .options(
            UPDATE_POLICIES
                .iter()
                .map(|(_, label, _)| widgets::SelectOption::new(*label)),
            selected,
        )
        // Fits the longest label, so switching never resizes the trigger.
        .width(136.0)
        .on_select(move |page, selected, _, cx| {
            if let Some((policy, ..)) = UPDATE_POLICIES.get(selected) {
                page.set_update_policy(harness, *policy, cx);
            }
        })
        .render(self.policy_selects.get(&harness).unwrap_or(&closed), cx);
        let row = div()
            .min_h(px(52.0))
            .py(px(10.0))
            .flex()
            .flex_row()
            .items_center()
            .gap(px(16.0))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .child(widgets::row_title(theme, "Update policy"))
                    .child(widgets::meta_line(
                        theme,
                        vec![div().child(UPDATE_POLICIES[selected].2).into_any_element()],
                    )),
            )
            .child(policy_select);
        Some(
            div()
                .flex()
                .flex_col()
                .child(widgets::details_label(theme, "Updates"))
                .child(row)
                .into_any_element(),
        )
    }

    fn rows(&self, cx: &mut Context<Self>) -> Vec<gpui::AnyElement> {
        let theme = Theme::of(cx).for_settings_surface();
        let Loadable::Ready(list) = &self.harnesses else {
            return Vec::new();
        };
        let descriptors = visible_harnesses(list);
        let enabled_count = descriptors.iter().filter(|d| descriptor_enabled(d)).count();
        descriptors
            .into_iter()
            .enumerate()
            .map(|(ix, descriptor)| {
                let harness = descriptor.id;
                let installed = descriptor.installed;
                let enabled = descriptor_enabled(&descriptor);
                // The one enabled harness left can't be switched off — the
                // composer needs something to run — but only when it could
                // actually run: an uninstalled last harness stays togglable
                // (its hint says to turn it off) and the composer handles the
                // resulting empty set (mirrors the engine guard).
                let last_enabled = enabled && enabled_count == 1 && installed;
                let signing_in = self
                    .sign_in
                    .as_ref()
                    .filter(|sign_in| sign_in.harness == harness);
                let sign_in_failure = self
                    .sign_in_failure
                    .as_ref()
                    .filter(|failure| failure.harness == harness);
                let sign_in_cancellable = signing_in.is_some();
                // Turning OFF never needs the CLI (a default-on agent the
                // user doesn't want must not be stuck on because it isn't
                // installed); turning ON still does.
                let interactive = signing_in.is_none() && !last_enabled && (enabled || installed);
                let (icon_path, tint) = crate::pickers::harness_brand_icon(harness);
                let mut meta: Vec<gpui::AnyElement> = vec![
                    div()
                        .child(SharedString::from(blurb(harness)))
                        .into_any_element(),
                ];
                if let Some(sign_in) = signing_in {
                    meta.push(
                        div()
                            .flex()
                            .flex_row()
                            .items_center()
                            .gap(px(6.0))
                            .child(crate::loaders::mini_mono_spinner(
                                format!("harness-setup-spinner-{harness:?}"),
                                1.5,
                                theme.text_muted,
                                cx.entity_id(),
                                cx,
                            ))
                            .child(SharedString::from(
                                sign_in
                                    .message
                                    .clone()
                                    .unwrap_or_else(|| sign_in.phase.pending_label().into()),
                            ))
                            .into_any_element(),
                    );
                }
                if let Some(failure) = sign_in_failure {
                    meta.push(
                        div()
                            .text_color(theme.danger_muted.opacity(0.9))
                            .child(SharedString::from(format!(
                                "{} — {}",
                                failure.phase.failure_label(),
                                failure.message
                            )))
                            .into_any_element(),
                    );
                }
                if self.installing == Some(harness) {
                    meta.push(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(6.0))
                            .child(crate::loaders::mini_mono_spinner(
                                format!("harness-install-spinner-{harness:?}"),
                                1.5,
                                theme.text_muted,
                                cx.entity_id(),
                                cx,
                            ))
                            .child(SharedString::from(install_label(&descriptor.name)))
                            .into_any_element(),
                    );
                }
                if !installed {
                    meta.push(
                        div()
                            .text_color(theme.warning_muted.opacity(0.9))
                            .child(SharedString::from(install_hint(
                                harness,
                                enabled,
                                descriptor.can_install,
                            )))
                            .into_any_element(),
                    );
                }
                let update = match &self.updates {
                    Loadable::Ready(statuses) => {
                        statuses.iter().find(|status| status.harness == harness)
                    }
                    _ => None,
                };
                if let Some(status) = update {
                    let (text, color) = harness_update_label(status, &theme);
                    meta.push(div().text_color(color).child(text).into_any_element());
                }
                match harness {
                    HarnessId::Cursor => meta.push(
                        div()
                            .text_color(theme.text_muted.opacity(0.65))
                            .child("Cursor SDK · Managed by Roboco")
                            .into_any_element(),
                    ),
                    HarnessId::Pi => meta.push(
                        div()
                            .text_color(theme.text_muted.opacity(0.65))
                            .child("pi RPC bridge · Managed by Roboco")
                            .into_any_element(),
                    ),
                    _ => {}
                }
                // widgets::row_tile with the brand tint honored (the Claude
                // mark keeps its orange, like the picker rail).
                let tile = div()
                    .flex_none()
                    .size(px(36.0))
                    .rounded(px(10.0))
                    .bg(theme.wash(0.06))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        crate::icons::icon(icon_path)
                            .size(px(16.0))
                            .text_color(tint.unwrap_or(theme.text_muted)),
                    );
                let expanded = enabled && self.expanded_harness == Some(harness);
                // The row's one update action sits inside the trigger, before
                // the chevron, so it appearing never moves the chevron; the
                // update policy lives in the expanded details.
                let update_action = update.and_then(|status| {
                    let (id, label, primary) = match status.phase {
                        HarnessUpdatePhase::Available
                        | HarnessUpdatePhase::ManualActionRequired
                            if status.can_apply =>
                        {
                            ("harness-update", "Update", true)
                        }
                        HarnessUpdatePhase::WaitingForIdle
                        | HarnessUpdatePhase::Preparing
                        | HarnessUpdatePhase::Downloading => {
                            ("harness-update-cancel", "Cancel", false)
                        }
                        _ => return None,
                    };
                    Some(
                        div()
                            .id((id, ix))
                            .flex_none()
                            .px(px(9.0))
                            .py(px(5.0))
                            .rounded(px(6.0))
                            .text_size(crate::typography::ui_rems(11.0))
                            .cursor_pointer()
                            .map(|button| {
                                if primary {
                                    button
                                        .bg(theme.accent_wash)
                                        .font_weight(gpui::FontWeight::MEDIUM)
                                        .text_color(theme.accent)
                                        .hover(|style| style.bg(theme.accent.opacity(0.16)))
                                } else {
                                    button
                                        .text_color(theme.text_muted)
                                        .hover(|style| style.bg(crate::theme::ink(0.05)))
                                }
                            })
                            .on_click(cx.listener(move |this, _, _, cx| {
                                // Inside the details trigger: act, don't expand.
                                cx.stop_propagation();
                                if primary {
                                    this.apply_harness_update(harness, cx)
                                } else {
                                    this.cancel_harness_update(harness, cx)
                                }
                            }))
                            .child(label),
                    )
                });
                let header = widgets::card_row(&theme, ix == 0)
                    .id(("harness-row", ix))
                    .when(!installed, |el| el.opacity(0.55))
                    .when(signing_in.is_some(), |el| el.opacity(0.65))
                    .child(
                        div()
                            .id(("harness-details-trigger", ix))
                            .group(format!("harness-details-{ix}"))
                            .flex_1()
                            .min_w(px(180.0))
                            .min_h(px(44.0))
                            .px(px(4.0))
                            .rounded(px(8.0))
                            .flex()
                            .flex_row()
                            .items_center()
                            .gap(px(12.0))
                            .when(enabled, |el| {
                                el.role(gpui::Role::Button)
                                    .aria_label(format!("{} preferences", descriptor.name))
                                    .aria_expanded(expanded)
                                    .tab_index(0)
                                    .cursor_pointer()
                                    // No hover slab (it stopped short of the
                                    // toggle and boxed the row); the chevron
                                    // lifts instead, like a list disclosure.
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(move |page, _, _, cx| {
                                        page.toggle_agent_details(harness, cx)
                                    }))
                                    .on_key_down(cx.listener(
                                        move |page, event: &gpui::KeyDownEvent, _, cx| {
                                            if !event.is_held
                                                && matches!(
                                                    event.keystroke.key.as_str(),
                                                    "enter" | "space"
                                                )
                                            {
                                                page.toggle_agent_details(harness, cx);
                                                cx.stop_propagation();
                                            }
                                        },
                                    ))
                            })
                            .child(tile)
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .flex()
                                    .flex_col()
                                    .child(widgets::row_title(&theme, descriptor.name.clone()))
                                    .child(widgets::meta_line(&theme, meta)),
                            )
                            .children(update_action)
                            .when(enabled, |el| {
                                el.child(
                                    div()
                                        .id(("harness-details-chevron", ix))
                                        // Fixed width either way, so expanding
                                        // never moves the chevron.
                                        .w(px(14.0))
                                        .flex_none()
                                        .flex()
                                        .items_center()
                                        .justify_center()
                                        .child(
                                            crate::icons::icon(if expanded {
                                                crate::icons::ALT_ARROW_DOWN
                                            } else {
                                                crate::icons::ALT_ARROW_RIGHT
                                            })
                                            .size(px(14.0))
                                            .text_color(theme.text_muted)
                                            .group_hover(
                                                format!("harness-details-{ix}"),
                                                |s| s.text_color(theme.text),
                                            ),
                                        ),
                                )
                            }),
                    )
                    .when(
                        offers_install(harness, installed, descriptor.can_install)
                            && self.installing != Some(harness),
                        |el| {
                            el.child(
                                widgets::ghost_action(&theme)
                                    .id(("harness-install", ix))
                                    .when(self.installing.is_none(), |el| {
                                        el.on_click(
                                            cx.listener(move |this, _, _, cx| {
                                                this.install(harness, cx)
                                            }),
                                        )
                                    })
                                    .child("Install"),
                            )
                        },
                    )
                    .when(self.installing == Some(harness), |el| {
                        el.child(
                            widgets::ghost_action(&theme)
                                .id(("harness-cancel-install", ix))
                                .on_click(cx.listener(|this, _, _, cx| this.cancel_install(cx)))
                                .child("Cancel"),
                        )
                    })
                    .when(
                        offers_sign_in(harness, installed)
                            && signing_in.is_none()
                            && sign_in_failure.is_none(),
                        |el| {
                            el.child(
                                widgets::ghost_action(&theme)
                                    .id(("harness-sign-in", ix))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.start_sign_in(harness, cx)
                                    }))
                                    .child(SharedString::from("Sign in")),
                            )
                        },
                    )

                    .when(sign_in_cancellable, |el| {
                        el.child(
                            widgets::ghost_action(&theme)
                                .id(("harness-cancel-sign-in", ix))
                                .on_click(cx.listener(|this, _, _, cx| {
                                    this.cancel_sign_in(cx);
                                }))
                                .child(SharedString::from("Cancel")),
                        )
                    })
                    .when(sign_in_failure.is_some(), |el| {
                        el.child(
                            widgets::ghost_action(&theme)
                                .id(("harness-retry-sign-in", ix))
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.start_sign_in(harness, cx);
                                }))
                                .child(SharedString::from("Retry")),
                        )
                    })
                    .child(
                        widgets::toggle_switch(&theme, enabled, format!("harness-toggle-{ix}"))
                            .id(("harness-toggle", ix))
                            .when(!interactive, |el| el.opacity(0.35))
                            .when(interactive, |el| {
                                el.cursor_pointer()
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.toggle(harness, !enabled, cx);
                                    }))
                            }),
                    );
                let details = expanded
                    .then(|| self.render_agent_details(harness, &theme, cx))
                    .flatten();
                div()
                    .flex()
                    .flex_col()
                    .child(header)
                    .children(details)
                    .into_any_element()
            })
            .collect()
    }
    fn on_scroll_hovered(&mut self, hovered: &bool, _: &mut Window, cx: &mut Context<Self>) {
        if self.scroll.set_list_hovered(*hovered) {
            cx.notify();
        }
    }
}

impl popover::ScrollRailHost for HarnessesPage {
    fn rail_bar(&mut self) -> &mut popover::MenuScrollbarState {
        self.scroll.rail_bar()
    }

    fn rail_scroll(&self) -> Option<gpui::ScrollHandle> {
        self.scroll.rail_scroll()
    }
}

impl Render for HarnessesPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).for_settings_surface();
        let body: gpui::AnyElement = match &self.harnesses {
            Loadable::Idle | Loadable::Loading => widgets::section_card(&theme)
                .p(px(16.0))
                .child(popover::skeleton_rows(
                    "harnesses-skeleton",
                    &theme,
                    4,
                    cx.entity_id(),
                    cx,
                ))
                .into_any_element(),
            Loadable::Error(message) => {
                let message = message.clone();
                div()
                    .child(widgets::error_strip(&theme, message))
                    .child(
                        widgets::ghost_action(&theme)
                            .id("harnesses-retry")
                            .mt(px(8.0))
                            .on_click(cx.listener(|page, _, _, cx| {
                                page.load(cx);
                                cx.notify();
                            }))
                            .child(SharedString::from("Retry")),
                    )
                    .into_any_element()
            }
            Loadable::Ready(_) => {
                let rows = self.rows(cx);
                widgets::section_card(&theme)
                    .children(rows)
                    .into_any_element()
            }
        };
        let error = self
            .error
            .clone()
            .map(|message| widgets::error_strip(&theme, message).into_any_element());
        let update_error = match &self.updates {
            Loadable::Error(message) => Some(
                div()
                    .child(widgets::error_strip(
                        &theme,
                        format!("Agent updates: {message}"),
                    ))
                    .child(
                        widgets::ghost_action(&theme)
                            .id("harness-updates-retry")
                            .mt(px(8.0))
                            .on_click(cx.listener(|page, _, _, cx| {
                                page.load(cx);
                                cx.notify();
                            }))
                            .child("Retry updates"),
                    )
                    .into_any_element(),
            ),
            _ => None,
        };
        let supports_updates = self.supports_updates(cx);
        let can_control = self.can_control_updates(cx);
        let check = div()
            .id("check-harness-updates")
            .flex_none()
            .px(px(9.0))
            .py(px(5.0))
            .rounded(px(6.0))
            .text_size(crate::typography::ui_rems(11.0))
            .text_color(theme.text_muted)
            .cursor_pointer()
            .hover(|style| style.bg(crate::theme::ink(0.05)))
            .opacity(if can_control { 1.0 } else { 0.4 })
            .when(can_control, |button| {
                button.on_click(cx.listener(|this, _, _, cx| this.check_updates(cx)))
            })
            .child("Check now");
        let switcher = self.render_device_switcher(&theme, cx);
        let titles = self.render_titles(&theme, cx);
        let scrollbar = popover::rail(self, "harnesses-page-scrollbar", &theme, cx);

        div()
            .id("harnesses-page-host")
            .relative()
            .size_full()
            .on_hover(cx.listener(Self::on_scroll_hovered))
            .child(
                div()
                    .id("harnesses-page")
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
                                    .justify_between()
                                    .child(widgets::page_header(&theme, "Agents", None))
                                    .child(
                                        div()
                                            .flex()
                                            .items_center()
                                            .gap(px(6.0))
                                            .when(supports_updates, |el| el.child(check))
                                            .child(switcher),
                                    ),
                            )
                            .child(
                                widgets::page_subtitle(
                                    &theme,
                                    "Install coding agents and choose which ones the composer offers. \
                                     Installations and settings apply to the selected device. \
                                     Downloads start only when you choose Install.",
                                )
                                .max_w(px(512.0))
                                .line_height(px(20.0)),
                            )
                            .children(error)
                            .children(update_error)
                            .child(body)
                            .child(titles),
                    ),
            )
            .children(scrollbar)
    }
}

#[cfg(test)]
mod tests {
    use super::SignInPhase;

    #[test]
    fn explicit_sign_in_requires_installed_antigravity() {
        use roboco_proto::HarnessId;
        assert!(super::offers_sign_in(HarnessId::Antigravity, true));
        assert!(!super::offers_sign_in(HarnessId::Antigravity, false));
        assert!(!super::offers_sign_in(HarnessId::Codex, true));
    }

    #[test]
    fn antigravity_setup_copy_matches_each_phase() {
        assert_eq!(
            SignInPhase::Starting.pending_label(),
            "Preparing Antigravity…"
        );
        assert_eq!(SignInPhase::Starting.failure_label(), "Setup failed");
        assert_eq!(
            SignInPhase::Authenticating.pending_label(),
            "Finish signing in in your browser."
        );
        assert_eq!(
            SignInPhase::Authenticating.failure_label(),
            "Sign-in failed"
        );
    }

    fn fixture_status(harness: roboco_proto::HarnessId, phase: roboco_proto::HarnessUpdatePhase) -> roboco_proto::HarnessUpdateStatus {
        roboco_proto::HarnessUpdateStatus {
            harness,
            installed_version: Some("1.0.0".into()),
            latest_version: Some("2.0.0".into()),
            channel: None,
            source: Default::default(),
            policy: Default::default(),
            phase,
            progress: None,
            checked_at: None,
            error: None,
            can_apply: true,
            manual_command: None,
        }
    }

    #[test]
    fn update_policy_menu_orders_short_labels_with_explanations() {
        let (first, label, note) = super::UPDATE_POLICIES[0];
        assert_eq!(first, roboco_proto::HarnessUpdatePolicy::Notify);
        assert_eq!(label, "Notify");
        assert!(note.ends_with('.'));
        assert_eq!(super::UPDATE_POLICIES[1].0, roboco_proto::HarnessUpdatePolicy::AutoWhenIdle);
        assert_eq!(super::UPDATE_POLICIES[1].1, "Auto when idle");
        assert_eq!(super::UPDATE_POLICIES[2].0, roboco_proto::HarnessUpdatePolicy::Off);
        assert_eq!(super::UPDATE_POLICIES[2].1, "Off");
    }

    #[test]
    fn update_label_names_versions_and_manual_guidance() {
        use roboco_proto::HarnessUpdatePhase as Phase;
        let theme = crate::theme::Theme::default();
        let status = fixture_status(roboco_proto::HarnessId::Codex, Phase::Available);
        let (label, _) = super::harness_update_label(&status, &theme);
        assert_eq!(label.as_ref(), "v1.0.0 · v2.0.0 available");
        let (label, _) = super::harness_update_label(
            &fixture_status(roboco_proto::HarnessId::Codex, Phase::Current),
            &theme,
        );
        assert_eq!(label.as_ref(), "v1.0.0 · Up to date");
        let mut manual = fixture_status(roboco_proto::HarnessId::Codex, Phase::ManualActionRequired);
        manual.can_apply = false;
        manual.manual_command = Some("npm install -g @openai/codex".into());
        let (label, _) = super::harness_update_label(&manual, &theme);
        assert_eq!(
            label.as_ref(),
            "v1.0.0 · npm install -g @openai/codex"
        );
    }

    #[gpui::test]
    fn expanded_agent_preferences_render_inside_the_agent_row(
        cx: &mut gpui::TestAppContext,
    ) {
        use gpui::AppContext;
        let dir = tempfile::tempdir().unwrap();
        cx.update(|cx| {
            crate::settings::init(Default::default(), dir.path(), cx);
            gpui_base::init(cx);
            cx.set_global(crate::theme::Theme::default());
        });
        let window = cx.add_window(|_, cx| {
            let state = cx.new(|_| crate::state::AppState::new());
            super::HarnessesPage::new(state, cx)
        });
        let descriptor = |id, name: &str| roboco_engine::registry::HarnessDescriptor {
            id,
            name: name.into(),
            supports_steering: false,
            steering_mode: roboco_proto::SteeringMode::TurnBoundary,
            reasoning_levels: Vec::new(),
            installed: true,
            can_install: false,
            enabled: Some(true),
        };
        window
            .update(cx, |page, _, cx| {
                page.harnesses = super::Loadable::Ready(vec![
                    descriptor(roboco_proto::HarnessId::ClaudeCode, "Claude Code"),
                    descriptor(roboco_proto::HarnessId::Codex, "Codex"),
                ]);
                // No engine is attached: the watch never starts and the
                // Updates section stays hidden until statuses arrive.
                assert!(page.render_updates_for(roboco_proto::HarnessId::ClaudeCode, &crate::theme::Theme::default(), cx).is_none());
                page.updates = super::Loadable::Ready(vec![fixture_status(
                    roboco_proto::HarnessId::ClaudeCode,
                    roboco_proto::HarnessUpdatePhase::Available,
                )]);
                page.toggle_agent_details(roboco_proto::HarnessId::ClaudeCode, cx);
                assert_eq!(
                    page.expanded_harness,
                    Some(roboco_proto::HarnessId::ClaudeCode)
                );
                assert!(
                    page.render_updates_for(
                        roboco_proto::HarnessId::ClaudeCode,
                        &crate::theme::Theme::default(),
                        cx
                    )
                    .is_some(),
                    "the expanded provider's details include its Updates section"
                );
            })
            .unwrap();
        cx.update_window(window.into(), |_, window, cx| window.draw(cx).clear())
            .unwrap();
        // Toggling another provider swaps the expanded row.
        window
            .update(cx, |page, _, cx| {
                page.toggle_agent_details(roboco_proto::HarnessId::Codex, cx);
                assert_eq!(page.expanded_harness, Some(roboco_proto::HarnessId::Codex));
                assert!(
                    page.render_updates_for(
                        roboco_proto::HarnessId::Codex,
                        &crate::theme::Theme::default(),
                        cx
                    )
                    .is_none(),
                    "a provider without a status row renders no Updates section"
                );
            })
            .unwrap();
        cx.update_window(window.into(), |_, window, cx| window.draw(cx).clear())
            .unwrap();
    }
}

#[cfg(test)]
#[test]
fn install_visibility_and_hint_follow_target_capabilities() {
    use roboco_proto::HarnessId;
    for id in [
        HarnessId::Antigravity,
        HarnessId::Codex,
        HarnessId::Opencode,
        HarnessId::ClaudeCode,
        HarnessId::Cursor,
        HarnessId::Pi,
        HarnessId::Grok,
        HarnessId::Hermes,
        HarnessId::Devin,
        HarnessId::Mock,
    ] {
        for installed in [false, true] {
            for available in [false, true] {
                assert_eq!(
                    offers_install(id, installed, available),
                    id != HarnessId::Mock && !installed && available
                );
                assert_eq!(
                    offers_sign_in(id, installed),
                    id == HarnessId::Antigravity && installed
                );
            }
        }
    }
    assert_eq!(cli_name(HarnessId::Antigravity), "Antigravity");
    assert_eq!(
        install_hint(HarnessId::Antigravity, false, true),
        "Install Antigravity to enable"
    );
    assert!(
        install_hint(HarnessId::Antigravity, false, false).contains("ANTIGRAVITY_ACP_EXECUTABLE")
    );
}

#[cfg(test)]
#[test]
fn install_phase_copy_and_cancel_target_match_install() {
    use roboco_proto::HarnessId;
    assert_eq!(install_label("Claude Code"), "Installing Claude Code…");
    assert_eq!(install_label("Pi"), "Installing Pi…");
    for target in [None, Some("remote-device".to_string())] {
        let params = install_params(HarnessId::Pi, &target);
        assert_eq!(params["harness"], "pi");
        assert_eq!(
            params["targetDeviceId"],
            serde_json::to_value(&target).unwrap()
        );
    }
    assert_eq!(
        install_hint(HarnessId::Codex, false, false),
        "Install the codex CLI to enable. Install with `npm install -g @openai/codex`"
    );
    assert!(
        install_hint(HarnessId::Codex, true, false)
            .starts_with("codex CLI not installed — turn it off or install it.")
    );
}
