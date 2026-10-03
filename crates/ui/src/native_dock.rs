//! The native (Mimir) control dock above the composer.
//!
//! Every row is a pure view of `AppState.native`, the host-confirmed state the
//! engine sends whole with each transcript update. A control is queued through
//! the durable command ledger and answered with a command id only. The dock then
//! reads that command back, bounded, and shows the host's typed outcome. A
//! command the host has not settled stays "not confirmed" and is never resent.

use std::collections::HashMap;

use gpui::{
    AnyElement, Context, Entity, EventEmitter, FontWeight, IntoElement, ParentElement, Render,
    SharedString, Styled, Task, Window, div, prelude::*, px,
};
use roboco_doc::SessionCommandEntry;
use roboco_proto::{
    NativeChatCatalog, NativeChatState, NativeChild, NativeControl, NativeGoalChange, NativeMode,
    NativePlanArtifact, NativePlanDecision, NativePlanStatus, NativeReadiness,
};
use roboco_rpc::methods;

use crate::{
    composer::{ComposerInput, ComposerInputEvent},
    engine_registry::{EngineConnectionState, ScopedId},
    native::{self, ControlReceipt, GoalAction, Tone},
    settings::widgets::{ActionTone, text_action},
    state::AppState,
    theme::Theme,
};

/// Height cap for the dock: it overlays the transcript, so a long plan or
/// question scrolls inside it instead of pushing the composer off screen.
const DOCK_MAX_HEIGHT: f32 = 340.0;

pub enum NativeDockEvent {
    OpenChild {
        chat_id: String,
        doc_id: String,
        title: String,
    },
}

impl EventEmitter<NativeDockEvent> for NativeDock {}

/// The plan document the user asked to read, bound to the plan id it was read
/// for. A different plan id makes it stale.
enum PlanLoad {
    Closed,
    Loading { plan_id: String },
    Open(Box<NativePlanArtifact>),
    Missing { plan_id: String },
    Failed { plan_id: String, message: String },
}

impl PlanLoad {
    fn plan_id(&self) -> Option<&str> {
        match self {
            Self::Closed => None,
            Self::Loading { plan_id }
            | Self::Missing { plan_id }
            | Self::Failed { plan_id, .. } => Some(plan_id),
            Self::Open(artifact) => Some(&artifact.id),
        }
    }
}

/// The chat's host catalog: listed when the model list opens, and again after
/// a failure or a reconnect.
enum CatalogLoad {
    Idle,
    Loading(#[allow(dead_code)] Task<()>),
    Ready(NativeChatCatalog),
    Failed(String),
}

/// The canonical inventory for header-only children, read for one set of headers.
enum InventoryLoad {
    Idle,
    Loading {
        key: String,
    },
    Ready {
        key: String,
        children: Vec<NativeChild>,
    },
    Failed {
        key: String,
        message: String,
    },
}

impl InventoryLoad {
    fn key(&self) -> Option<&str> {
        match self {
            Self::Idle => None,
            Self::Loading { key } | Self::Ready { key, .. } | Self::Failed { key, .. } => Some(key),
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum GoalEditor {
    Start,
    Edit,
}

pub struct NativeDock {
    state: Entity<AppState>,
    /// The chat the cached plan, catalog and drafts belong to.
    chat_key: Option<String>,
    plan: PlanLoad,
    plan_task: Option<Task<()>>,
    catalog: CatalogLoad,
    /// The link was attached when last rendered: a reconnect re-lists a catalog that failed.
    attached: bool,
    models_open: bool,
    inventory: InventoryLoad,
    inventory_task: Option<Task<()>>,
    drafts: HashMap<String, native::QuestionDraft>,
    freeform_inputs: HashMap<String, Entity<ComposerInput>>,
    freeform_subscriptions: HashMap<String, gpui::Subscription>,
    /// The open question the drafts belong to; a new request id starts clean.
    request_id: Option<String>,
    goal_input: Entity<ComposerInput>,
    goal_editor: Option<GoalEditor>,
    receipt: Option<ControlReceipt>,
    /// Dropping the task stops its reads. A newer control replaces it.
    control_task: Option<Task<()>>,
    control_seq: u64,
    _goal_events: gpui::Subscription,
}

impl NativeDock {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let goal_input = cx.new(|cx| {
            ComposerInput::new("What should Mimir work toward?", cx)
                .with_single_line()
                .with_text_metrics(12.0, 18.0)
        });
        let goal_events = cx.subscribe(&goal_input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.submit_goal(cx);
            }
        });
        cx.observe(&state, |_, _, cx| cx.notify()).detach();
        Self {
            state,
            chat_key: None,
            plan: PlanLoad::Closed,
            plan_task: None,
            catalog: CatalogLoad::Idle,
            attached: false,
            models_open: false,
            inventory: InventoryLoad::Idle,
            inventory_task: None,
            drafts: HashMap::new(),
            freeform_inputs: HashMap::new(),
            freeform_subscriptions: HashMap::new(),
            request_id: None,
            goal_input,
            goal_editor: None,
            receipt: None,
            control_task: None,
            control_seq: 0,
            _goal_events: goal_events,
        }
    }

    /// The dock is shown only for a native chat; every other harness is untouched.
    pub fn visible(state: &AppState) -> bool {
        state.native.is_some() && state.selected_chat.is_some()
    }

    pub(crate) fn selected(&self, cx: &gpui::App) -> Option<(String, NativeChatState)> {
        let state = self.state.read(cx);
        Some((state.selected_chat.clone()?, state.native.clone()?))
    }

    /// Drop anything cached for another chat, another question or another plan.
    fn sync(&mut self, chat_id: &str, native: &NativeChatState, cx: &mut Context<Self>) {
        if self.chat_key.as_deref() != Some(chat_id) {
            self.chat_key = Some(chat_id.to_owned());
            self.plan = PlanLoad::Closed;
            self.plan_task = None;
            self.catalog = CatalogLoad::Idle;
            self.models_open = false;
            self.inventory = InventoryLoad::Idle;
            self.inventory_task = None;
            self.goal_editor = None;
            self.receipt = None;
            self.control_task = None;
        }
        let current = native
            .user_request
            .as_ref()
            .map(|request| request.id.clone());
        if self.request_id != current {
            self.request_id = current;
            self.drafts.clear();
            self.freeform_inputs.clear();
            self.freeform_subscriptions.clear();
        }
        // A read plan stays readable whatever its status; a new plan id is a new document.
        if self.plan.plan_id().is_some()
            && self.plan.plan_id() != native.plan.as_ref().map(|plan| plan.id.as_str())
        {
            self.plan = PlanLoad::Closed;
            self.plan_task = None;
        }
        let reconnected = native::attached(native) && !self.attached;
        self.attached = native::attached(native);
        if reconnected && matches!(self.catalog, CatalogLoad::Failed(_)) {
            self.fetch_catalog(cx);
        }
        let key = native::inventory_key(&native.children);
        let failed = matches!(self.inventory, InventoryLoad::Failed { .. });
        if native::needs_inventory(&native.children)
            && (self.inventory.key() != Some(key.as_str()) || (reconnected && failed))
        {
            self.fetch_inventory(key, cx);
        }
    }

    fn fetch_catalog(&mut self, cx: &mut Context<Self>) {
        let Some(chat_id) = self.chat_key.clone() else {
            return;
        };
        let engine = match crate::request_routing::selected_target(self.state.read(cx)) {
            Ok(engine) => engine,
            Err(error) => {
                self.catalog = CatalogLoad::Failed(error.to_string());
                cx.notify();
                return;
            }
        };
        let params = serde_json::json!({ "chatId": chat_id });
        let task = cx.spawn(async move |this, cx| {
            let catalog = engine
                .call(methods::GET_NATIVE_CATALOG, params)
                .await
                .map_err(|error| error.to_string())
                .and_then(|value| {
                    serde_json::from_value::<NativeChatCatalog>(value).map_err(|error| {
                        format!("Mimir listed models this version cannot read: {error}")
                    })
                });
            this.update(cx, |dock, cx| {
                if dock.chat_key.as_deref() == Some(chat_id.as_str()) {
                    dock.catalog = match catalog {
                        Ok(catalog) => CatalogLoad::Ready(catalog),
                        Err(message) => CatalogLoad::Failed(message),
                    };
                    cx.notify();
                }
            })
            .ok();
        });
        self.catalog = CatalogLoad::Loading(task);
        cx.notify();
    }

    fn toggle_models(&mut self, cx: &mut Context<Self>) {
        self.models_open = !self.models_open;
        if self.models_open && !matches!(self.catalog, CatalogLoad::Loading(_)) {
            self.fetch_catalog(cx);
        }
        cx.notify();
    }

    /// Read the canonical inventory for this chat's header-only children.
    fn fetch_inventory(&mut self, key: String, cx: &mut Context<Self>) {
        let Some(chat_id) = self.chat_key.clone() else {
            return;
        };
        let engine = match crate::request_routing::selected_target(self.state.read(cx)) {
            Ok(engine) => engine,
            Err(error) => {
                self.inventory = InventoryLoad::Failed {
                    key,
                    message: error.to_string(),
                };
                return;
            }
        };
        let params = serde_json::json!({ "chatId": chat_id });
        let read_key = key.clone();
        self.inventory_task = Some(cx.spawn(async move |this, cx| {
            let children = engine
                .call(methods::LIST_NATIVE_CHILDREN, params)
                .await
                .map_err(|error| error.to_string())
                .and_then(|value| {
                    serde_json::from_value::<Vec<NativeChild>>(value)
                        .map_err(|error| error.to_string())
                });
            this.update(cx, |dock, cx| {
                if dock.chat_key.as_deref() != Some(chat_id.as_str())
                    || dock.inventory.key() != Some(read_key.as_str())
                {
                    return;
                }
                dock.inventory = match children {
                    Ok(children) => InventoryLoad::Ready {
                        key: read_key,
                        children,
                    },
                    Err(message) => InventoryLoad::Failed {
                        key: read_key,
                        message,
                    },
                };
                cx.notify();
            })
            .ok();
        }));
        self.inventory = InventoryLoad::Loading { key };
    }

    pub fn send(&mut self, control: NativeControl, cx: &mut Context<Self>) {
        let Some((chat_id, native)) = self.selected(cx) else {
            return;
        };
        // The receipt belongs to the chat this control names, even when the
        // dock has not painted since the selection changed.
        self.sync(&chat_id, &native, cx);
        if native::receipt_in_flight(self.receipt.as_ref()) {
            return;
        }
        self.control_seq += 1;
        let seq = self.control_seq;
        let chat = chat_id.clone();
        self.control_task = Some(send_control(
            self.state.clone(),
            chat_id,
            control,
            cx,
            move |dock, receipt, cx| {
                if dock.control_seq != seq || dock.chat_key.as_deref() != Some(chat.as_str()) {
                    return false;
                }
                dock.receipt = Some(receipt.clone());
                cx.notify();
                true
            },
        ));
        cx.notify();
    }

    /// Read the current plan's document. The answer lands only while the same
    /// chat shows the same plan id, and an artifact for any other id is not it.
    fn open_plan(&mut self, cx: &mut Context<Self>) {
        let Some((chat_id, native)) = self.selected(cx) else {
            return;
        };
        let Some(plan_id) = native.plan.map(|plan| plan.id) else {
            return;
        };
        let engine = match crate::request_routing::selected_target(self.state.read(cx)) {
            Ok(engine) => engine,
            Err(error) => {
                self.plan = PlanLoad::Failed {
                    plan_id,
                    message: error.to_string(),
                };
                cx.notify();
                return;
            }
        };
        self.plan = PlanLoad::Loading {
            plan_id: plan_id.clone(),
        };
        let params = serde_json::json!({ "chatId": chat_id });
        self.plan_task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::GET_NATIVE_PLAN, params).await;
            this.update(cx, |dock, cx| {
                let current = dock
                    .selected(cx)
                    .and_then(|(chat, native)| Some((chat, native.plan?.id)));
                if current.as_ref() != Some(&(chat_id.clone(), plan_id.clone()))
                    || dock.plan.plan_id() != Some(plan_id.as_str())
                {
                    return;
                }
                dock.plan = match result {
                    Ok(value) => {
                        match serde_json::from_value::<Option<NativePlanArtifact>>(value) {
                            Ok(Some(plan)) if plan.id == plan_id => PlanLoad::Open(Box::new(plan)),
                            Ok(_) => PlanLoad::Missing { plan_id },
                            Err(err) => PlanLoad::Failed {
                                plan_id,
                                message: err.to_string(),
                            },
                        }
                    }
                    Err(err) => PlanLoad::Failed {
                        plan_id,
                        message: err.to_string(),
                    },
                };
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn start_goal_editor(&mut self, mode: GoalEditor, objective: &str, cx: &mut Context<Self>) {
        self.goal_editor = Some(mode);
        self.goal_input
            .update(cx, |input, cx| input.set_text(objective.to_owned(), cx));
        cx.notify();
    }

    fn submit_goal(&mut self, cx: &mut Context<Self>) {
        let Some(mode) = self.goal_editor else {
            return;
        };
        let objective = self.goal_input.read(cx).text().trim().to_owned();
        if objective.is_empty() {
            return;
        }
        let change = match mode {
            GoalEditor::Start => NativeGoalChange::Start {
                objective,
                duration_seconds: None,
            },
            GoalEditor::Edit => NativeGoalChange::Edit { objective },
        };
        self.goal_editor = None;
        self.send(NativeControl::ChangeGoal { change }, cx);
    }

    fn freeform_input(
        &mut self,
        question_id: &str,
        cx: &mut Context<Self>,
    ) -> Entity<ComposerInput> {
        if let Some(input) = self.freeform_inputs.get(question_id) {
            return input.clone();
        }
        let input = cx.new(|cx| {
            ComposerInput::new("Or type your own answer", cx)
                .with_single_line()
                .with_text_metrics(12.0, 18.0)
        });
        let id = question_id.to_owned();
        let subscription = cx.subscribe(&input, move |this: &mut Self, input, event, cx| {
            if matches!(event, ComposerInputEvent::Edited) {
                let text = input.read(cx).text().to_owned();
                this.drafts.entry(id.clone()).or_default().freeform = text;
                cx.notify();
            }
        });
        self.freeform_subscriptions
            .insert(question_id.to_owned(), subscription);
        self.freeform_inputs
            .insert(question_id.to_owned(), input.clone());
        input
    }

    // ---- rendering ----

    fn button(
        theme: &Theme,
        id: impl Into<SharedString>,
        label: impl Into<SharedString>,
        tone: ActionTone,
        enabled: bool,
        on_click: impl Fn(&mut Self, &mut Context<Self>) + 'static,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let button = text_action(theme, tone, label).id(id.into());
        if enabled {
            button
                .on_click(cx.listener(move |this, _, _, cx| on_click(this, cx)))
                .into_any_element()
        } else {
            button.opacity(0.45).cursor_default().into_any_element()
        }
    }

    fn tone_color(theme: &Theme, tone: Tone) -> gpui::Hsla {
        match tone {
            Tone::Neutral => theme.text_muted,
            Tone::Active => theme.accent,
            Tone::Warn => theme.warning,
            Tone::Danger => theme.danger,
        }
    }

    fn card(theme: &Theme) -> gpui::Div {
        div()
            .flex()
            .flex_col()
            .gap(px(6.0))
            .rounded(px(10.0))
            .border_1()
            .border_color(theme.border)
            .bg(theme.surface_raised)
            .px(px(12.0))
            .py(px(8.0))
            .text_size(px(12.0))
            .text_color(theme.text)
    }

    fn engine_state(&self, chat_id: &str, cx: &gpui::App) -> Option<EngineConnectionState> {
        let scope = ScopedId::parse(chat_id).ok()?;
        self.state
            .read(cx)
            .registry_snapshot
            .engines
            .iter()
            .find(|engine| engine.key == scope.engine)
            .map(|engine| engine.state.clone())
    }

    fn render_link(
        &mut self,
        theme: &Theme,
        chat_id: &str,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let view = native::link_view(&native.link);
        let offline = self
            .engine_state(chat_id, cx)
            .and_then(native::engine_offline_copy);
        let blocker = native::release_blocker(native);
        let show_status = offline.is_some()
            || native.recovering
            || view.tone != Tone::Neutral
            || !matches!(native.link, roboco_proto::NativeLink::Attached);
        if !show_status && !view.show_release {
            return None;
        }
        let mut card = Self::card(theme);
        if let Some(copy) = offline {
            card = card.child(
                div()
                    .id("native-engine-offline")
                    .text_color(theme.warning)
                    .child(copy),
            );
        }
        if show_status {
            card = card.child(
                div()
                    .id("native-link")
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(8.0))
                    .child(
                        div()
                            .font_weight(FontWeight::MEDIUM)
                            .text_color(Self::tone_color(theme, view.tone))
                            .child(view.title),
                    )
                    .children(view.detail.clone().map(|detail| {
                        div()
                            .min_w_0()
                            .text_color(theme.text_muted)
                            .child(SharedString::from(detail))
                    })),
            );
        }
        if native.recovering {
            card = card.child(
                div()
                    .id("native-recovering")
                    .text_color(theme.warning)
                    .child(native::RECOVERING_COPY),
            );
        }
        let mut actions = div().flex().flex_row().flex_wrap().gap(px(6.0));
        if view.can_reconnect {
            actions = actions.child(Self::button(
                theme,
                "native-reconnect",
                "Reconnect",
                ActionTone::Outlined,
                true,
                |this, cx| this.send(NativeControl::Reconnect, cx),
                cx,
            ));
        }
        if view.show_release {
            actions = actions.child(Self::button(
                theme,
                "native-release",
                "Continue in Mimir",
                ActionTone::Quiet,
                blocker.is_none(),
                |this, cx| this.send(NativeControl::Release, cx),
                cx,
            ));
        }
        card = card.child(actions);
        if view.show_release
            && let Some(copy) = blocker
        {
            card = card.child(
                div()
                    .id("native-release-blocked")
                    .text_color(theme.text_muted)
                    .child(copy),
            );
        }
        Some(card.into_any_element())
    }

    fn render_config(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let configuration = native.configuration.clone()?;
        let mut row = div()
            .id("native-config")
            .flex()
            .flex_row()
            .flex_wrap()
            .items_center()
            .gap(px(6.0))
            .child(div().text_color(theme.text_muted).child(SharedString::from(
                native::configuration_label(&configuration),
            )));
        for mode in [NativeMode::Build, NativeMode::Plan] {
            let selected = configuration.mode == mode;
            let current = configuration.clone();
            row = row.child(Self::button(
                theme,
                format!("native-mode-{}", native::mode_label(mode)),
                native::mode_label(mode),
                if selected {
                    ActionTone::Filled
                } else {
                    ActionTone::Quiet
                },
                !selected,
                move |this, cx| {
                    this.send(
                        native::configure_control(Some(&current), None, None, Some(mode)),
                        cx,
                    )
                },
                cx,
            ));
        }
        row = row.child(Self::button(
            theme,
            "native-models-toggle",
            if self.models_open {
                "Hide models"
            } else {
                "Change model"
            },
            ActionTone::Quiet,
            true,
            |this, cx| this.toggle_models(cx),
            cx,
        ));
        let mut card = Self::card(theme).child(row);
        if self.models_open {
            match &self.catalog {
                CatalogLoad::Idle | CatalogLoad::Loading(_) => {
                    card = card.child(
                        div()
                            .id("native-models-loading")
                            .text_color(theme.text_muted)
                            .child("Asking Mimir for its models."),
                    )
                }
                CatalogLoad::Failed(message) => {
                    card = card.child(
                        div()
                            .id("native-models-failed")
                            .flex()
                            .flex_row()
                            .flex_wrap()
                            .items_center()
                            .gap(px(8.0))
                            .child(div().text_color(theme.danger).child(SharedString::from(
                                format!("Could not list Mimir's models. {message}"),
                            )))
                            .child(Self::button(
                                theme,
                                "native-models-retry",
                                "Try again",
                                ActionTone::Outlined,
                                true,
                                |this, cx| this.fetch_catalog(cx),
                                cx,
                            )),
                    )
                }
                CatalogLoad::Ready(catalog) => {
                    let mut list = div()
                        .id("native-model-list")
                        .flex()
                        .flex_col()
                        .gap(px(2.0))
                        .max_h(px(120.0))
                        .overflow_y_scroll();
                    for provider in &catalog.providers {
                        for model in &provider.models {
                            let id = format!("{}/{}", provider.id, model.id);
                            let selected = native::configuration_model_id(&configuration)
                                .as_deref()
                                == Some(id.as_str());
                            let current = configuration.clone();
                            let pick = id.clone();
                            list = list.child(Self::button(
                                theme,
                                format!("native-model-{id}"),
                                format!("{} · {}", provider.name, model.name),
                                if selected {
                                    ActionTone::Filled
                                } else {
                                    ActionTone::Quiet
                                },
                                !selected,
                                move |this, cx| {
                                    this.send(
                                        native::configure_control(
                                            Some(&current),
                                            Some(&pick),
                                            None,
                                            None,
                                        ),
                                        cx,
                                    )
                                },
                                cx,
                            ));
                        }
                    }
                    card = card.child(list);
                    let levels = native::reasoning_levels(Some(catalog), Some(&configuration));
                    if !levels.is_empty() {
                        let mut reasoning = div()
                            .flex()
                            .flex_row()
                            .flex_wrap()
                            .gap(px(6.0))
                            .child(div().text_color(theme.text_muted).child("Reasoning"));
                        for level in levels {
                            let selected =
                                configuration.reasoning.as_deref() == Some(level.as_str());
                            let current = configuration.clone();
                            let pick = level.clone();
                            reasoning = reasoning.child(Self::button(
                                theme,
                                format!("native-reasoning-{level}"),
                                level,
                                if selected {
                                    ActionTone::Filled
                                } else {
                                    ActionTone::Quiet
                                },
                                !selected,
                                move |this, cx| {
                                    this.send(
                                        native::configure_control(
                                            Some(&current),
                                            None,
                                            Some(&pick),
                                            None,
                                        ),
                                        cx,
                                    )
                                },
                                cx,
                            ));
                        }
                        card = card.child(reasoning);
                    }
                }
            }
        }
        if native.active_request.is_some() {
            card = card.child(
                div()
                    .text_color(theme.text_muted)
                    .child("Changes made while Mimir is running apply to the next prompt."),
            );
        }
        Some(card.into_any_element())
    }

    fn render_plan(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let plan = native.plan.clone()?;
        // Every plan stays readable; only one awaiting review can be decided.
        let review = plan.status == NativePlanStatus::ReviewPending;
        let mut card = Self::card(theme).id("native-plan").child(
            div()
                .flex()
                .flex_row()
                .items_center()
                .gap(px(8.0))
                .child(
                    div()
                        .font_weight(FontWeight::MEDIUM)
                        .child(SharedString::from(format!("Plan \"{}\"", plan.name))),
                )
                .child(
                    div()
                        .text_color(theme.text_muted)
                        .child(native::plan_status_label(plan.status)),
                ),
        );
        let retry = |label: &'static str, cx: &mut Context<Self>| {
            Self::button(
                theme,
                "native-plan-show",
                label,
                ActionTone::Quiet,
                true,
                |this, cx| this.open_plan(cx),
                cx,
            )
        };
        match &self.plan {
            PlanLoad::Closed => card = card.child(retry("Show plan", cx)),
            PlanLoad::Loading { .. } => {
                card = card.child(
                    div()
                        .text_color(theme.text_muted)
                        .child("Loading the plan."),
                )
            }
            PlanLoad::Missing { .. } => {
                card = card
                    .child(
                        div()
                            .text_color(theme.warning)
                            .child("Mimir no longer has this plan's document."),
                    )
                    .child(retry("Check again", cx))
            }
            PlanLoad::Failed { message, .. } => {
                card = card
                    .child(
                        div()
                            .id("native-plan-failed")
                            .text_color(theme.danger)
                            .child(SharedString::from(format!(
                                "Could not read the plan. {message}"
                            ))),
                    )
                    .child(retry("Try again", cx))
            }
            PlanLoad::Open(artifact) => {
                card = card.child(
                    div()
                        .id("native-plan-body")
                        .max_h(px(140.0))
                        .overflow_y_scroll()
                        .text_color(theme.text_muted)
                        .child(SharedString::from(artifact.markdown.clone())),
                );
            }
        }
        if review {
            let plan_id = plan.id.clone();
            let mut actions = div().flex().flex_row().flex_wrap().gap(px(6.0));
            for (decision, label, tone) in [
                (
                    NativePlanDecision::Implement,
                    "Implement",
                    ActionTone::Solid,
                ),
                (
                    NativePlanDecision::ImplementFresh,
                    "Implement with fresh context",
                    ActionTone::Outlined,
                ),
                (
                    NativePlanDecision::SaveAndStop,
                    "Save and stop",
                    ActionTone::Quiet,
                ),
            ] {
                let plan_id = plan_id.clone();
                actions = actions.child(Self::button(
                    theme,
                    format!("native-plan-{label}"),
                    label,
                    tone,
                    true,
                    move |this, cx| {
                        this.send(
                            NativeControl::DecidePlan {
                                plan_id: plan_id.clone(),
                                decision,
                            },
                            cx,
                        )
                    },
                    cx,
                ));
            }
            card = card.child(actions);
        }
        Some(card.into_any_element())
    }

    fn render_question(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let request = native.user_request.clone()?;
        let mut card = Self::card(theme).id("native-question");
        for question in &request.questions {
            let draft = self.drafts.get(&question.id).cloned().unwrap_or_default();
            let mut block = div().flex().flex_col().gap(px(4.0)).child(
                div()
                    .font_weight(FontWeight::MEDIUM)
                    .child(SharedString::from(question.prompt.clone())),
            );
            if question.allow_multiple {
                block = block.child(
                    div()
                        .text_color(theme.text_muted)
                        .child("Choose any that apply."),
                );
            }
            for option in &question.options {
                let selected = draft.selected.iter().any(|label| label == &option.label);
                let qid = question.id.clone();
                let label = option.label.clone();
                let question_clone = question.clone();
                block = block.child(
                    div()
                        .flex()
                        .flex_col()
                        .child(Self::button(
                            theme,
                            format!("native-option-{}-{}", question.id, option.label),
                            if selected {
                                format!("[x] {}", option.label)
                            } else {
                                option.label.clone()
                            },
                            if selected {
                                ActionTone::Filled
                            } else {
                                ActionTone::Outlined
                            },
                            true,
                            move |this, cx| {
                                this.drafts
                                    .entry(qid.clone())
                                    .or_default()
                                    .toggle_option(&question_clone, &label);
                                cx.notify();
                            },
                            cx,
                        ))
                        .children((!option.description.is_empty()).then(|| {
                            div()
                                .px(px(10.0))
                                .text_color(theme.text_muted)
                                .child(SharedString::from(option.description.clone()))
                        })),
                );
            }
            let qid = question.id.clone();
            block = block.child(Self::button(
                theme,
                format!("native-none-{}", question.id),
                if draft.none_of_above {
                    "[x] None of the above"
                } else {
                    "None of the above"
                },
                if draft.none_of_above {
                    ActionTone::Filled
                } else {
                    ActionTone::Quiet
                },
                true,
                move |this, cx| {
                    this.drafts
                        .entry(qid.clone())
                        .or_default()
                        .toggle_none_of_above();
                    cx.notify();
                },
                cx,
            ));
            let input = self.freeform_input(&question.id, cx);
            block = block.child(
                crate::surface_chrome::input()
                    .child(div().min_w_0().flex_1().overflow_hidden().child(input)),
            );
            card = card.child(block);
        }
        let answers = native::build_answers(&request.questions, &self.drafts);
        let request_id = request.id.clone();
        let questions = request.questions.clone();
        card = card.child(Self::button(
            theme,
            "native-answer",
            "Send answer",
            ActionTone::Solid,
            answers.is_some(),
            move |this, cx| {
                if let Some(answers) = native::build_answers(&questions, &this.drafts) {
                    this.send(
                        NativeControl::Answer {
                            request_id: request_id.clone(),
                            answers,
                        },
                        cx,
                    );
                }
            },
            cx,
        ));
        Some(card.into_any_element())
    }

    fn render_goal(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let goal = native
            .goal
            .as_ref()
            .filter(|goal| native::goal_is_open(Some(goal)));
        let editing = self.goal_editor.is_some();
        if goal.is_none() && !editing {
            return None;
        }
        let mut card = Self::card(theme).id("native-goal");
        if let Some(goal) = goal {
            let view = native::goal_view(goal);
            card = card
                .child(
                    div()
                        .flex()
                        .flex_row()
                        .items_center()
                        .gap(px(8.0))
                        .child(div().font_weight(FontWeight::MEDIUM).child("Goal"))
                        .child(
                            div()
                                .text_color(Self::tone_color(theme, view.tone))
                                .child(view.phase_label),
                        )
                        .child(div().text_color(theme.text_muted).child(view.cause_label))
                        .child(div().text_color(theme.text_muted).child(SharedString::from(
                            format!("{} work turns", view.work_turns),
                        ))),
                )
                .child(div().child(SharedString::from(goal.objective.clone())));
            if let Some(reason) = view.reason {
                card = card.child(
                    div()
                        .text_color(theme.text_muted)
                        .child(SharedString::from(reason)),
                );
            }
            if let Some(gap) = view.review_gap {
                card = card.child(
                    div()
                        .text_color(theme.warning)
                        .child(SharedString::from(format!("Review gap: {gap}"))),
                );
            }
            if let Some(completion) = &goal.completion {
                let mut evidence = div().flex().flex_col().gap(px(2.0)).child(
                    div()
                        .font_weight(FontWeight::MEDIUM)
                        .child("Completion evidence"),
                );
                for item in &completion.checklist {
                    evidence = evidence.child(div().child(SharedString::from(format!(
                        "{}: {}",
                        item.requirement, item.evidence
                    ))));
                }
                if let Some(limitations) = &completion.limitations {
                    evidence = evidence.child(
                        div()
                            .text_color(theme.text_muted)
                            .child(SharedString::from(format!("Limitations: {limitations}"))),
                    );
                }
                card = card.child(evidence);
            }
            if !editing {
                let objective = goal.objective.clone();
                let mut actions = div().flex().flex_row().flex_wrap().gap(px(6.0));
                for action in view.actions {
                    let objective = objective.clone();
                    actions = actions.child(Self::button(
                        theme,
                        format!("native-goal-{}", action.label()),
                        action.label(),
                        ActionTone::Outlined,
                        true,
                        move |this, cx| match action {
                            GoalAction::Edit => {
                                this.start_goal_editor(GoalEditor::Edit, &objective, cx)
                            }
                            GoalAction::Pause => this.send(
                                NativeControl::ChangeGoal {
                                    change: NativeGoalChange::Pause,
                                },
                                cx,
                            ),
                            GoalAction::Resume => this.send(
                                NativeControl::ChangeGoal {
                                    change: NativeGoalChange::Resume {
                                        duration_seconds: None,
                                    },
                                },
                                cx,
                            ),
                            GoalAction::Clear => this.send(
                                NativeControl::ChangeGoal {
                                    change: NativeGoalChange::Clear,
                                },
                                cx,
                            ),
                        },
                        cx,
                    ));
                }
                card = card.child(actions);
            }
        }
        if editing {
            card = card
                .child(
                    crate::surface_chrome::input().child(
                        div()
                            .min_w_0()
                            .flex_1()
                            .overflow_hidden()
                            .child(self.goal_input.clone()),
                    ),
                )
                .child(
                    div()
                        .flex()
                        .flex_row()
                        .gap(px(6.0))
                        .child(Self::button(
                            theme,
                            "native-goal-save",
                            "Save goal",
                            ActionTone::Solid,
                            true,
                            |this, cx| this.submit_goal(cx),
                            cx,
                        ))
                        .child(Self::button(
                            theme,
                            "native-goal-cancel",
                            "Cancel",
                            ActionTone::Quiet,
                            true,
                            |this, cx| {
                                this.goal_editor = None;
                                cx.notify();
                            },
                            cx,
                        )),
                );
        }
        Some(card.into_any_element())
    }

    fn render_deliveries(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let deliveries = native::attention_deliveries(native);
        if deliveries.is_empty() {
            return None;
        }
        let mut card = Self::card(theme).id("native-deliveries");
        for delivery in deliveries {
            let mut row = div().flex().flex_col().gap(px(2.0)).child(
                div()
                    .text_color(Self::tone_color(theme, delivery.tone))
                    .font_weight(FontWeight::MEDIUM)
                    .child(SharedString::from(delivery.label.clone())),
            );
            if let Some(detail) = delivery.detail.clone() {
                row = row.child(
                    div()
                        .text_color(theme.text_muted)
                        .child(SharedString::from(detail)),
                );
            }
            if delivery.retryable {
                let message_id = delivery.message_id.clone();
                row = row.child(Self::button(
                    theme,
                    format!("native-retry-{}", delivery.message_id),
                    "Retry this message",
                    ActionTone::Outlined,
                    true,
                    move |this, cx| {
                        this.send(
                            NativeControl::RetrySubmission {
                                message_id: message_id.clone(),
                            },
                            cx,
                        )
                    },
                    cx,
                ));
            }
            card = card.child(row);
        }
        Some(card.into_any_element())
    }

    fn render_children(
        &mut self,
        theme: &Theme,
        chat_id: &str,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if native.children.is_empty() {
            return None;
        }
        let inventory: &[NativeChild] = match &self.inventory {
            InventoryLoad::Ready { children, .. } => children,
            _ => &[],
        };
        let children = native::hydrate_children(&native.children, inventory);
        let mut card = Self::card(theme).id("native-children");
        match &self.inventory {
            InventoryLoad::Loading { .. } if native::needs_inventory(&children) => {
                card = card.child(
                    div()
                        .text_color(theme.text_muted)
                        .child("Reading the full details of large agents from Mimir."),
                )
            }
            InventoryLoad::Failed { key, message } if native::needs_inventory(&children) => {
                let key = key.clone();
                card = card.child(
                    div()
                        .id("native-children-failed")
                        .flex()
                        .flex_row()
                        .flex_wrap()
                        .items_center()
                        .gap(px(8.0))
                        .child(div().text_color(theme.danger).child(SharedString::from(format!(
                            "Some agents show only their header. Mimir's full list could not be read. {message}"
                        ))))
                        .child(Self::button(
                            theme,
                            "native-children-retry",
                            "Try again",
                            ActionTone::Outlined,
                            true,
                            move |this, cx| this.fetch_inventory(key.clone(), cx),
                            cx,
                        )),
                )
            }
            _ => {}
        }
        for child in &children {
            let doc_id = native::child_doc_for(chat_id, &child.doc_id);
            let title = native::child_title(child).to_owned();
            let title = if title.is_empty() {
                child.handle.clone()
            } else {
                title
            };
            let chat = chat_id.to_owned();
            let label = format!(
                "{} · {} · attempt {}",
                title,
                native::child_status_label(child.status),
                child.attempt
            );
            card = card.child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(8.0))
                    .child(div().min_w_0().flex_1().child(SharedString::from(label)))
                    .child(Self::button(
                        theme,
                        format!("native-child-open-{}", child.handle),
                        "Open",
                        ActionTone::Outlined,
                        true,
                        move |_, cx| {
                            cx.emit(NativeDockEvent::OpenChild {
                                chat_id: chat.clone(),
                                doc_id: doc_id.clone(),
                                title: title.clone(),
                            })
                        },
                        cx,
                    )),
            );
        }
        Some(card.into_any_element())
    }

    fn render_work(
        &mut self,
        theme: &Theme,
        native: &NativeChatState,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let request_id = native.active_request.clone()?;
        Some(
            Self::card(theme)
                .id("native-work")
                .flex_row()
                .items_center()
                .gap(px(8.0))
                .child(div().flex_1().child(SharedString::from(format!(
                    "Mimir is working on request {request_id}."
                ))))
                .child(Self::button(
                    theme,
                    "native-stop",
                    "Stop",
                    ActionTone::Outlined,
                    true,
                    move |this, cx| {
                        this.send(
                            NativeControl::CancelRequest {
                                request_id: request_id.clone(),
                            },
                            cx,
                        )
                    },
                    cx,
                ))
                .into_any_element(),
        )
    }
}

/// Queue a typed control once, then read its ledger entry on the bounded
/// schedule and report each receipt to `on_receipt`. The callback returns false
/// when its owner no longer wants the result, which stops the work. Dropping
/// the returned task stops it too. The command is never resent.
pub(crate) fn send_control<T: 'static>(
    state: Entity<AppState>,
    chat_id: String,
    control: NativeControl,
    cx: &mut Context<T>,
    on_receipt: impl Fn(&mut T, &ControlReceipt, &mut Context<T>) -> bool + 'static,
) -> Task<()> {
    let engine =
        crate::request_routing::selected_target(state.read(cx)).map_err(|err| err.to_string());
    let params = native::control_params(&chat_id, &control);
    cx.spawn(async move |this, cx| {
        let executor = cx.background_executor().clone();
        let mut report_cx = cx.clone();
        let mut report = |receipt: &ControlReceipt| {
            this.update(&mut report_cx, |entity, cx| on_receipt(entity, receipt, cx))
                .unwrap_or(false)
        };
        let queue_engine = engine.clone();
        let read_engine = engine;
        let read_executor = executor.clone();
        native::run_control(
            || async move {
                let value = queue_engine?
                    .call(methods::QUEUE_COMMAND, params)
                    .await
                    .map_err(|err| err.to_string())?;
                value
                    .get("commandId")
                    .and_then(|id| id.as_str())
                    .map(str::to_owned)
                    .ok_or_else(|| "the engine returned no command id".to_owned())
            },
            |command_id| {
                let engine = read_engine.clone();
                let executor = read_executor.clone();
                let chat_id = chat_id.clone();
                async move { read_command(engine?, &executor, &chat_id, &command_id).await }
            },
            |delay| executor.timer(delay),
            &mut report,
        )
        .await;
    })
}

/// Per-read leash. The schedule in `native::RECEIPT_DELAYS_MS` is the budget,
/// so one hung read must not stretch it.
const RECEIPT_READ_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

async fn read_command(
    engine: crate::engine_registry::EngineTarget,
    executor: &gpui::BackgroundExecutor,
    chat_id: &str,
    command_id: &str,
) -> Result<Option<SessionCommandEntry>, String> {
    let params = serde_json::json!({ "chatId": chat_id, "commandId": command_id });
    let value = crate::attachments::call_with_timeout(
        &engine,
        executor,
        methods::GET_COMMAND,
        params,
        RECEIPT_READ_TIMEOUT,
    )
    .await?;
    serde_json::from_value(value).map_err(|err| format!("the entry was unreadable: {err}"))
}

impl Render for NativeDock {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).clone();
        let Some((chat_id, native)) = self.selected(cx) else {
            return div().into_any_element();
        };
        self.sync(&chat_id, &native, cx);

        let mut stack = div()
            .id("native-dock")
            .flex()
            .flex_col()
            .gap(px(6.0))
            .p(px(8.0))
            .max_h(px(DOCK_MAX_HEIGHT))
            .overflow_y_scroll()
            // Opaque: the dock overlays the transcript's bottom.
            .bg(theme.bg);
        let sections: Vec<Option<AnyElement>> = vec![
            self.render_link(&theme, &chat_id, &native, cx),
            self.render_config(&theme, &native, cx),
            self.render_work(&theme, &native, cx),
            self.render_plan(&theme, &native, cx),
            self.render_question(&theme, &native, cx),
            self.render_goal(&theme, &native, cx),
            self.render_deliveries(&theme, &native, cx),
            self.render_children(&theme, &chat_id, &native, cx),
        ];
        let mut any = false;
        for section in sections.into_iter().flatten() {
            any = true;
            stack = stack.child(section);
        }
        let can_start_goal = native::attached(&native)
            && !native::goal_is_open(native.goal.as_ref())
            && self.goal_editor.is_none();
        if can_start_goal {
            any = true;
            stack = stack.child(Self::button(
                &theme,
                "native-goal-start",
                "Start a goal",
                ActionTone::Quiet,
                true,
                |this, cx| this.start_goal_editor(GoalEditor::Start, "", cx),
                cx,
            ));
        } else if self.goal_editor == Some(GoalEditor::Start) {
            any = true;
        }
        if let Some(receipt) = &self.receipt {
            any = true;
            let view = native::receipt_view(receipt);
            let color = match view.tone {
                native::ReceiptTone::Progress => theme.text_muted,
                native::ReceiptTone::Ok => theme.text,
                native::ReceiptTone::Warn => theme.warning,
                native::ReceiptTone::Danger => theme.danger,
            };
            stack = stack.child(
                div()
                    .id("native-feedback")
                    .text_color(color)
                    .child(SharedString::from(view.text)),
            );
        }
        if !any {
            return div().into_any_element();
        }
        stack.into_any_element()
    }
}

/// Setup readiness for the settings page: a pure view the harness row renders.
pub fn readiness_lines(readiness: &NativeReadiness) -> (native::ReadinessView, Option<String>) {
    let view = native::readiness_view(readiness);
    let manual = view
        .action
        .as_ref()
        .map(|action| format!("Run this yourself, Roboco will not install it: {action}"));
    (view, manual)
}

/// A scripted engine for native client tests: every RPC the real code makes
/// is a frame the test reads and answers, so the production paths run end to end.
#[cfg(test)]
pub(crate) mod scripted {
    use gpui::{Entity, TestAppContext};
    use roboco_rpc::{ClientFrame, ServerFrame};
    use tokio::sync::mpsc::{Receiver, Sender};

    use crate::state::AppState;

    pub(crate) struct Wire {
        requests: Receiver<String>,
        replies: Sender<String>,
    }

    /// Route `state`'s requests to this wire. Call inside an entered runtime.
    pub(crate) fn attach(state: &Entity<AppState>, cx: &mut TestAppContext) -> Wire {
        let (out, requests) = tokio::sync::mpsc::channel(4096);
        let (replies, inbound) = tokio::sync::mpsc::channel(4096);
        state.update(cx, |state, _| {
            state.set_test_engine(crate::state::EngineHandle::from_test_client(
                roboco_rpc::RpcClient::new(out, inbound),
            ));
        });
        Wire { requests, replies }
    }

    /// Drive both executors until the frames written so far are readable.
    pub(crate) fn pump(runtime: &tokio::runtime::Runtime, cx: &mut TestAppContext) {
        for _ in 0..4 {
            cx.run_until_parked();
            for _ in 0..16 {
                runtime.block_on(tokio::task::yield_now());
            }
        }
        cx.run_until_parked();
    }

    impl Wire {
        pub(crate) fn drain(&mut self) -> Vec<ClientFrame> {
            let mut frames = Vec::new();
            while let Ok(frame) = self.requests.try_recv() {
                frames.push(serde_json::from_str(&frame).unwrap());
            }
            frames
        }

        pub(crate) fn answer(&self, frame: &ClientFrame, value: serde_json::Value) {
            self.send(ServerFrame {
                id: frame.id,
                ok: Some(value),
                ..Default::default()
            });
        }

        pub(crate) fn fail(&self, frame: &ClientFrame, message: &str) {
            self.send(ServerFrame {
                id: frame.id,
                err: Some(message.to_owned()),
                ..Default::default()
            });
        }

        fn send(&self, frame: ServerFrame) {
            self.replies
                .try_send(serde_json::to_string(&frame).unwrap())
                .unwrap();
        }
    }

    pub(crate) fn only<'a>(frames: &'a [ClientFrame], method: &str) -> &'a ClientFrame {
        let matching: Vec<&ClientFrame> = frames
            .iter()
            .filter(|frame| frame.method.as_deref() == Some(method))
            .collect();
        assert_eq!(matching.len(), 1, "one {method} in {frames:?}");
        matching[0]
    }
}

#[cfg(test)]
mod tests {
    use super::scripted::{self, only, pump};
    use super::*;
    use gpui::TestAppContext;
    use roboco_proto::{NativeChildStatus, NativeConfiguration, NativeLink, NativePlanSummary};

    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
    }

    fn native() -> NativeChatState {
        NativeChatState {
            link: NativeLink::Attached,
            configuration: Some(NativeConfiguration {
                provider: Some("a".into()),
                model: Some("m".into()),
                reasoning: None,
                mode: NativeMode::Build,
            }),
            ..Default::default()
        }
    }

    fn plan(id: &str, status: NativePlanStatus) -> NativePlanSummary {
        NativePlanSummary {
            id: id.into(),
            name: format!("Plan {id}"),
            status,
        }
    }

    fn artifact(id: &str, status: NativePlanStatus) -> serde_json::Value {
        serde_json::to_value(NativePlanArtifact {
            id: id.into(),
            name: format!("Plan {id}"),
            path: format!("/plans/{id}.md"),
            markdown: format!("# Plan {id}"),
            sections: Vec::new(),
            stages: Vec::new(),
            status,
        })
        .unwrap()
    }

    fn mount(
        state: NativeChatState,
        cx: &mut TestAppContext,
    ) -> (Entity<AppState>, Entity<NativeDock>, scripted::Wire) {
        let app = cx.new(|_| AppState::new());
        let wire = scripted::attach(&app, cx);
        app.update(cx, |app, _| {
            app.selected_chat = Some("chat".into());
            app.native = Some(state);
            app.native_projected = true;
        });
        let dock = cx.new(|cx| NativeDock::new(app.clone(), cx));
        sync(&dock, cx);
        (app, dock, wire)
    }

    fn sync(dock: &Entity<NativeDock>, cx: &mut TestAppContext) {
        dock.update(cx, |dock, cx| {
            let (chat, native) = dock.selected(cx).unwrap();
            dock.sync(&chat, &native, cx);
        });
    }

    fn set(
        app: &Entity<AppState>,
        cx: &mut TestAppContext,
        edit: impl FnOnce(&mut NativeChatState),
    ) {
        app.update(cx, |app, cx| {
            edit(app.native.as_mut().unwrap());
            cx.notify();
        });
    }

    fn open_plan_id(dock: &Entity<NativeDock>, cx: &mut TestAppContext) -> Option<String> {
        dock.read_with(cx, |dock, _| match &dock.plan {
            PlanLoad::Open(artifact) => Some(artifact.id.clone()),
            _ => None,
        })
    }

    #[gpui::test]
    fn a_plan_read_lands_only_on_its_own_plan_and_saved_plans_stay_readable(
        cx: &mut TestAppContext,
    ) {
        let runtime = runtime();
        let _guard = runtime.enter();
        let mut state = native();
        state.plan = Some(plan("p1", NativePlanStatus::ReviewPending));
        let (app, dock, mut wire) = mount(state, cx);

        dock.update(cx, |dock, cx| dock.open_plan(cx));
        pump(&runtime, cx);
        let first = only(&wire.drain(), methods::GET_NATIVE_PLAN).clone();
        assert_eq!(first.params["chatId"], "chat");
        set(&app, cx, |state| {
            state.plan = Some(plan("p2", NativePlanStatus::ReviewPending))
        });
        sync(&dock, cx);
        wire.answer(&first, artifact("p1", NativePlanStatus::ReviewPending));
        pump(&runtime, cx);
        assert!(
            dock.read_with(cx, |dock, _| matches!(dock.plan, PlanLoad::Closed)),
            "a late answer for plan p1 is never shown under plan p2"
        );

        dock.update(cx, |dock, cx| dock.open_plan(cx));
        pump(&runtime, cx);
        let second = only(&wire.drain(), methods::GET_NATIVE_PLAN).clone();
        wire.answer(&second, artifact("p2", NativePlanStatus::ReviewPending));
        pump(&runtime, cx);
        assert_eq!(open_plan_id(&dock, cx).as_deref(), Some("p2"));
        set(&app, cx, |state| {
            state.plan = Some(plan("p2", NativePlanStatus::SavedStopped))
        });
        sync(&dock, cx);
        assert_eq!(
            open_plan_id(&dock, cx).as_deref(),
            Some("p2"),
            "a saved plan stays readable after the decision"
        );

        set(&app, cx, |state| {
            state.plan = Some(plan("p3", NativePlanStatus::Completed))
        });
        sync(&dock, cx);
        dock.update(cx, |dock, cx| dock.open_plan(cx));
        pump(&runtime, cx);
        let third = only(&wire.drain(), methods::GET_NATIVE_PLAN).clone();
        wire.fail(&third, "bridge restarting");
        pump(&runtime, cx);
        assert!(dock.read_with(cx, |dock, _| matches!(
            &dock.plan,
            PlanLoad::Failed { plan_id, message } if plan_id == "p3" && message.contains("bridge restarting")
        )));
        dock.update(cx, |dock, cx| dock.open_plan(cx));
        pump(&runtime, cx);
        let retry = only(&wire.drain(), methods::GET_NATIVE_PLAN).clone();
        wire.answer(&retry, artifact("p3", NativePlanStatus::Completed));
        pump(&runtime, cx);
        assert_eq!(
            open_plan_id(&dock, cx).as_deref(),
            Some("p3"),
            "a failed read retries"
        );
    }

    #[gpui::test]
    fn model_list_failures_are_shown_and_read_again_on_open_and_reconnect(cx: &mut TestAppContext) {
        let runtime = runtime();
        let _guard = runtime.enter();
        let (app, dock, mut wire) = mount(native(), cx);
        pump(&runtime, cx);
        assert!(
            wire.drain()
                .iter()
                .all(|frame| frame.method.as_deref() != Some(methods::GET_NATIVE_CATALOG)),
            "nothing is listed until the user opens the model list"
        );
        dock.update(cx, |dock, cx| dock.toggle_models(cx));
        pump(&runtime, cx);
        let first = only(&wire.drain(), methods::GET_NATIVE_CATALOG).clone();
        wire.fail(&first, "not attached");
        pump(&runtime, cx);
        assert!(dock.read_with(cx, |dock, _| matches!(
            &dock.catalog,
            CatalogLoad::Failed(message) if message.contains("not attached")
        )));

        set(&app, cx, |state| {
            state.link = NativeLink::Interrupted {
                message: "bridge exited".into(),
            }
        });
        sync(&dock, cx);
        set(&app, cx, |state| state.link = NativeLink::Attached);
        sync(&dock, cx);
        pump(&runtime, cx);
        let again = only(&wire.drain(), methods::GET_NATIVE_CATALOG).clone();
        wire.answer(
            &again,
            serde_json::json!({"commands": [], "skills": [], "providers": [
                {"id": "a", "name": "A", "models": [{"id": "m", "name": "M", "reasoning": ["low"]}]}
            ]}),
        );
        pump(&runtime, cx);
        assert!(dock.read_with(cx, |dock, _| matches!(dock.catalog, CatalogLoad::Ready(_))));

        dock.update(cx, |dock, cx| {
            dock.toggle_models(cx);
            dock.toggle_models(cx);
        });
        pump(&runtime, cx);
        only(&wire.drain(), methods::GET_NATIVE_CATALOG);
    }

    fn header(handle: &str, attempt: u32) -> NativeChild {
        NativeChild {
            handle: handle.into(),
            attempt,
            profile: String::new(),
            description: String::new(),
            model: None,
            status: NativeChildStatus::Running,
            background: true,
            spawned_by: None,
            completion_pending: false,
            presentation: None,
            doc_id: format!("chat--sub--{handle}"),
            oversized: true,
            attempts: Vec::new(),
        }
    }

    #[gpui::test]
    fn header_only_children_take_the_owning_chats_canonical_inventory(cx: &mut TestAppContext) {
        let runtime = runtime();
        let _guard = runtime.enter();
        let mut state = native();
        state.children = vec![header("agent-1", 2)];
        let (app, dock, mut wire) = mount(state, cx);
        pump(&runtime, cx);
        let list = only(&wire.drain(), methods::LIST_NATIVE_CHILDREN).clone();
        assert_eq!(list.params["chatId"], "chat");
        let mut full = header("agent-1", 2);
        full.oversized = false;
        full.description = "Map the auth module".into();
        full.attempts = vec![roboco_proto::NativeChildAttempt {
            attempt: 1,
            status: NativeChildStatus::Failed,
            presentation: None,
            outcome_ref: Some("chat/agent-1.1.outcome".into()),
        }];
        wire.answer(&list, serde_json::to_value(vec![full.clone()]).unwrap());
        pump(&runtime, cx);
        let shown = dock.read_with(cx, |dock, cx| {
            let InventoryLoad::Ready { children, .. } = &dock.inventory else {
                panic!("the inventory was read");
            };
            native::hydrate_children(
                &dock.state.read(cx).native.as_ref().unwrap().children,
                children,
            )
        });
        assert_eq!(shown, vec![full]);

        set(&app, cx, |state| {
            state.children = vec![header("agent-1", 3)]
        });
        sync(&dock, cx);
        pump(&runtime, cx);
        let moved = only(&wire.drain(), methods::LIST_NATIVE_CHILDREN).clone();
        app.update(cx, |app, _| app.selected_chat = Some("other".into()));
        sync(&dock, cx);
        wire.answer(&moved, serde_json::json!([]));
        pump(&runtime, cx);
        assert!(
            dock.read_with(cx, |dock, _| !matches!(
                dock.inventory,
                InventoryLoad::Ready { .. }
            )),
            "an inventory read for another chat is dropped"
        );
    }
}
