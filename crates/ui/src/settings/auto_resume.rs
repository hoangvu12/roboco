//! Settings → General → Auto-resume limited chats (orchestration v2,
//! ticket 07): the ENGINE setting that arms the Resume-at-reset continuation
//! automatically whenever a chat becomes Limited. Off by default — an engine
//! that starts spending turns on its own is opt-in. Read and written through
//! the engine's `GetEngineSettings` / `SetEngineSettings` (the engine acts on
//! it even with the UI closed), persisted under the engine data dir.

use gpui::{AnyElement, Context, Entity, Subscription, Task, div, prelude::*, px};
use roboco_engine::EngineSettings;
use roboco_rpc::methods;

use crate::popover::Loadable;
use crate::settings::widgets;
use crate::state::AppState;
use crate::theme::Theme;

pub struct AutoResumeCard {
    state: Entity<AppState>,
    settings: Loadable<EngineSettings>,
    task: Option<Task<()>>,
    _state: Subscription,
}

impl AutoResumeCard {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        // Settings can open before the engine connects: load once it does.
        let state_sub = cx.observe(&state, |this: &mut Self, _, cx| {
            if matches!(this.settings, Loadable::Idle) {
                this.load(cx);
            }
        });
        let mut card = Self {
            state,
            settings: Loadable::Idle,
            task: None,
            _state: state_sub,
        };
        card.load(cx);
        card
    }

    /// `GetEngineSettings`, or `SetEngineSettings` when saving; either reply
    /// is the engine's authoritative settings.
    fn load(&mut self, cx: &mut Context<Self>) {
        let Some(engine) = self.state.read(cx).engine().cloned() else {
            return;
        };
        if matches!(self.settings, Loadable::Idle) {
            self.settings = Loadable::Loading;
        }
        self.task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .client()
                .call(methods::GET_ENGINE_SETTINGS, serde_json::json!({}))
                .await
                .map_err(|e| e.to_string())
                .and_then(|v| serde_json::from_value::<EngineSettings>(v).map_err(|e| e.to_string()));
            this.update(cx, |card, cx| {
                match result {
                    Ok(settings) => {
                        card.settings = Loadable::Ready(settings);
                    }
                    Err(error) => card.settings = Loadable::Error(error),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// Flip the toggle and persist it through the engine.
    fn toggle(&mut self, cx: &mut Context<Self>) {
        let Loadable::Ready(current) = &self.settings else {
            return;
        };
        let next = EngineSettings {
            auto_resume_limited: !current.auto_resume_limited,
        };
        self.settings = Loadable::Ready(next);
        let Some(engine) = self.state.read(cx).engine().cloned() else {
            return;
        };
        self.task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .client()
                .call(
                    methods::SET_ENGINE_SETTINGS,
                    serde_json::to_value(next).unwrap_or_default(),
                )
                .await
                .map_err(|e| e.to_string());
            this.update(cx, |card, cx| {
                if let Err(error) = result {
                    // Show the failure; the next load reconciles the toggle
                    // with what the engine actually kept.
                    card.settings = Loadable::Error(error);
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    /// Retry the load after an error (the error strip's action).
    fn retry(&mut self, cx: &mut Context<Self>) {
        self.settings = Loadable::Idle;
        self.load(cx);
    }
}

impl Render for AutoResumeCard {
    fn render(&mut self, _: &mut gpui::Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).for_settings_surface();
        let enabled = self
            .settings
            .ready()
            .is_some_and(|settings| settings.auto_resume_limited);
        let control: AnyElement = match &self.settings {
            Loadable::Ready(_) => widgets::toggle_switch(theme, enabled, "auto-resume-limited")
                .id("auto-resume-limited-toggle")
                .tab_index(0)
                .role(gpui::Role::Switch)
                .aria_label("Auto-resume limited chats")
                .aria_toggled(if enabled {
                    gpui::Toggled::True
                } else {
                    gpui::Toggled::False
                })
                .focus_visible(|s| s.border_2().border_color(theme.accent))
                .cursor_pointer()
                .on_click(cx.listener(|card, _, _, cx| card.toggle(cx)))
                .into_any_element(),
            Loadable::Error(error) => div()
                .flex()
                .flex_col()
                .items_end()
                .gap(px(4.0))
                .child(
                    div()
                        .text_color(theme.danger)
                        .child(error.clone())
                        .into_any_element(),
                )
                .child(
                    widgets::text_action(&theme, widgets::ActionTone::Quiet, "Retry")
                        .id("auto-resume-retry")
                        .tab_index(0)
                        .role(gpui::Role::Button)
                        .aria_label("Retry loading engine settings")
                        .focus_visible(|s| s.border_2().border_color(theme.accent))
                        .cursor_pointer()
                        .on_click(cx.listener(|card, _, _, cx| card.retry(cx))),
                )
                .into_any_element(),
            Loadable::Idle | Loadable::Loading => div()
                .text_color(theme.text_muted)
                .child("Loading…")
                .into_any_element(),
        };
        widgets::section_card(&theme)
            .child(
                widgets::card_row(&theme, true)
                    .child(
                        div()
                            .flex_1()
                            .min_w(px(160.0))
                            .child(widgets::row_title(&theme, "Auto-resume limited chats"))
                            .child(widgets::meta_line(
                                &theme,
                                vec![div()
                                    .child(
                                        "Continue Limited chats automatically when their \
                                         limit resets. Survives restarts; your messages \
                                         always cancel the scheduled resume.",
                                    )
                                    .into_any_element()],
                            )),
                    )
                    .child(control),
            )
    }
}
