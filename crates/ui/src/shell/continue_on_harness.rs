//! The "Continue on another harness" dialog (t3-orchestration-v2 ticket 25):
//! the harness and model a Limited chat continues on, chosen when the
//! continuation starts.
//!
//! The Limited banner's Resume (ticket 06) sends the continue prompt on the
//! SAME harness; this dialog is the escape hatch spec story 55 asks for —
//! pick any OTHER enabled harness and a model, and confirming performs
//! ticket 24's one-way harness switch first, then sends the fixed continue
//! prompt (ticket 06's path). The engine writes the "Switched to <harness>"
//! seam, so the new agent's first run carries the budgeted transcript of
//! the conversation above the seam, and the run clears the limit record.
//!
//! The dialog is deliberately local to this flow: the composer's own
//! harness/model picker (and its unlock in ticket 24's one-way switch) stays
//! untouched, so the two features never share state.

use super::*;

use roboco_engine::registry::HarnessDescriptor;
use roboco_proto::{ChatConfig, HarnessId, Model, SandboxLevel};

/// A pending "Continue on another harness": the Limited chat being switched
/// and the harness and model the continuation runs on. Held by
/// [`Shell::continue_dialog`] while the dialog is open.
pub(super) struct ContinueOnHarnessDialog {
    /// The Limited chat the continuation switches — the row the
    /// SwitchHarness RPC rewrites.
    chat: roboco_proto::Chat,
    /// The harness the chat runs today (its row config). Never offered: the
    /// continuation is a switch to ANOTHER harness, not a re-pick of the one
    /// that hit the limit.
    current_harness: Option<HarnessId>,
    /// The harness the continuation will run on. `None` until a default
    /// resolves — the first OFFERED harness once the catalog lands (the
    /// current one is already excluded from that list).
    harness: Option<HarnessId>,
    /// The model the continuation will run on. `None` = the harness's
    /// default at run time.
    model: Option<String>,
    /// The chat's host device's harness catalog, loaded once per dialog.
    harnesses: Loadable<Vec<HarnessDescriptor>>,
    /// Model lists per harness, loaded once per dialog per harness.
    models: std::collections::HashMap<HarnessId, Loadable<Vec<Model>>>,
    /// Stale-response guard: bumped when the dialog closes, so an in-flight
    /// catalog load lands on a closed (or reopened) dialog and no-ops.
    generation: u64,
    /// The model list's scroll.
    model_scroll: gpui::ScrollHandle,
}

/// The SwitchHarness params a confirm sends (ticket 24's RPC): the chat, the
/// chosen harness and the chosen model. Reasoning and model options never
/// ride — the dialog picks none, so the new harness's own defaults apply
/// (the engine stores what arrives; "model options reset to the new
/// harness's defaults unless the user chose them"). The sandbox the switch
/// keeps is the chat's own pick, so the engine RPC reads it from the row.
pub(super) fn switch_params(
    chat_id: &str,
    harness: HarnessId,
    model: Option<&str>,
) -> serde_json::Value {
    let mut params = serde_json::json!({
        "chatId": chat_id,
        "harness": harness,
    });
    if let Some(model) = model.map(str::trim).filter(|id| !id.is_empty()) {
        params["model"] = serde_json::Value::String(model.to_owned());
    }
    params
}

/// The harnesses the "Continue on another harness" dialog offers: the same
/// offered set the composer's picker uses (installed and enabled on the
/// listing device), minus the chat's current harness — a Limited chat
/// escapes its limit by switching to ANOTHER harness, never by re-picking
/// the one that hit it.
pub(super) fn continue_offered_harnesses(
    catalog: &[HarnessDescriptor],
    current: Option<HarnessId>,
) -> Vec<HarnessDescriptor> {
    crate::pickers::offered_harnesses(catalog)
        .into_iter()
        .filter(|descriptor| current != Some(descriptor.id))
        .collect()
}

impl Shell {
    /// Open the "Continue on another harness" dialog (orchestration v2
    /// ticket 25) for the selected Limited chat: pick the harness and model
    /// the continuation switches to; confirming performs ticket 24's
    /// one-way switch and then sends the continue prompt.
    pub(super) fn open_continue_on_harness(&mut self, cx: &mut Context<Self>) {
        if self.continue_dialog.is_some() {
            return;
        }
        let Some(chat) = self.state.read(cx).selected_chat_row().cloned() else {
            return;
        };
        self.continue_dialog = Some(ContinueOnHarnessDialog {
            current_harness: chat.config.as_ref().map(|config| config.harness),
            chat,
            harness: None,
            model: None,
            harnesses: Loadable::Idle,
            models: Default::default(),
            generation: 0,
            model_scroll: gpui::ScrollHandle::new(),
        });
        self.ensure_continue_loads(cx);
        cx.notify();
    }

    /// Close the dialog without continuing. The generation bump retires
    /// in-flight catalog loads.
    pub(super) fn close_continue_dialog(&mut self, cx: &mut Context<Self>) {
        if let Some(dialog) = self.continue_dialog.as_mut() {
            dialog.generation += 1;
        }
        self.continue_dialog = None;
        cx.notify();
    }

    /// Pick the continuation's harness. A pick resets the model — the
    /// previous pick's model id is meaningless on the new harness; its own
    /// default applies at run time.
    pub(super) fn pick_continue_harness(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        if let Some(dialog) = self.continue_dialog.as_mut() {
            if dialog.harness == Some(harness) {
                return;
            }
            dialog.harness = Some(harness);
            dialog.model = None;
        }
        self.ensure_continue_models(harness, cx);
        cx.notify();
    }

    /// Pick the continuation's model on the selected harness.
    pub(super) fn pick_continue_model(&mut self, model: String, cx: &mut Context<Self>) {
        if let Some(dialog) = self.continue_dialog.as_mut() {
            dialog.model = Some(model);
        }
        cx.notify();
    }

    /// Confirm: perform ticket 24's one-way harness switch, then send the
    /// continue prompt (ticket 06's Resume path). The switch must land
    /// FIRST — its seam is what arms the budgeted history and drops the
    /// limited harness's session — so the prompt is sent only after the
    /// RPC succeeds. A failed switch leaves the chat untouched (the local
    /// row is stamped only on success, so nothing needs reverting): still
    /// Limited, still on its old harness, the banner still up.
    pub(super) fn confirm_continue_on_harness(&mut self, cx: &mut Context<Self>) {
        let Some(dialog) = self.continue_dialog.as_ref() else {
            return;
        };
        // Nothing picked yet (no other harness is available): nothing to
        // confirm — the button renders disabled.
        let Some(harness) = dialog.harness else {
            return;
        };
        let chat_id = dialog.chat.id.clone();
        let model = dialog.model.clone();
        let Some(engine) = crate::request_routing::selected_target(self.state.read(cx)).ok()
        else {
            // No engine to switch on: say so (the same surface a failed
            // switch uses) instead of swallowing the click.
            self.continue_dialog = None;
            self.composer.update(cx, |composer, cx| {
                composer.show_error("Engine not connected", cx);
            });
            cx.notify();
            return;
        };
        self.continue_dialog = None;
        // The one field the switch keeps: the chat's sandbox pick stays.
        let sandbox = self
            .state
            .read(cx)
            .chats
            .iter()
            .find(|chat| chat.id == chat_id)
            .and_then(|chat| chat.config.as_ref())
            .map(|config| config.sandbox)
            .unwrap_or(SandboxLevel::WorkspaceWrite);
        let params = switch_params(&chat_id, harness, model.as_deref());
        cx.spawn(async move |this, cx| {
            let result = engine.call(methods::SWITCH_HARNESS, params).await;
            this.update(cx, |shell, cx| {
                match result {
                    Ok(_) => {
                        // The engine's row already switched; stamp the local
                        // row with the same picks so the composer's send
                        // carries the new harness and model deterministically
                        // (the chats watch confirms the same value after it).
                        let config = ChatConfig {
                            harness,
                            model: model.clone(),
                            reasoning: None,
                            model_options: Default::default(),
                            sandbox,
                        };
                        shell.state.update(cx, |state, cx| {
                            state.apply_chat_config(&chat_id, config);
                            cx.notify();
                        });
                        // The continue prompt — the same send path, guards and
                        // queue semantics as the banner's Resume. The run
                        // starts on the new harness, clears the limit record,
                        // and queued messages stay behind it. The prompt is
                        // addressed through the composer's CURRENT selection
                        // (`resume_limited` sends to the selected chat), so
                        // re-check it live here: if the selection moved while
                        // the switch was in flight, the switched chat keeps
                        // its Limited banner and Resume — never send another
                        // chat's continuation into it.
                        let still_selected = shell
                            .state
                            .read(cx)
                            .selected_chat
                            .as_deref()
                            == Some(chat_id.as_str());
                        if still_selected {
                            shell
                                .composer
                                .update(cx, |composer, cx| composer.resume_limited(cx));
                        }
                    }
                    Err(err) => {
                        // The switch never landed: the chat keeps its old
                        // harness and its Limited state — the continuation is
                        // never sent into the same wall.
                        tracing::warn!(error = %err, "continue-on-harness switch failed");
                        shell.composer.update(cx, |composer, cx| {
                            composer.show_error(
                                format!("Could not switch the harness: {err}"),
                                cx,
                            );
                        });
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Kick every load the dialog's current selection needs: the harness
    /// catalog when still idle, and the selected harness's model list.
    fn ensure_continue_loads(&mut self, cx: &mut Context<Self>) {
        self.ensure_continue_harnesses(cx);
        let harness = self.continue_dialog.as_ref().and_then(|dialog| dialog.harness);
        if let Some(harness) = harness {
            self.ensure_continue_models(harness, cx);
        }
    }

    /// The catalog load's `targetDeviceId`: the switch runs on the CHAT's
    /// host device (the RPC refuses a source hosted elsewhere), so its
    /// installed harnesses are the ones that matter. `None` for a
    /// locally-hosted chat — the connected engine serves it directly.
    fn continue_catalog_target(&self, cx: &App) -> Option<String> {
        let local = self.state.read(cx).local_device_id.as_deref()?;
        let dialog = self.continue_dialog.as_ref()?;
        (local != dialog.chat.device_id.as_str()).then(|| dialog.chat.device_id.clone())
    }

    /// Load the harness catalog once per dialog: the list is short-lived, so
    /// no refresh-on-open and no error retry — reopening the dialog retries.
    fn ensure_continue_harnesses(&mut self, cx: &mut Context<Self>) {
        let idle = self
            .continue_dialog
            .as_ref()
            .is_some_and(|dialog| matches!(dialog.harnesses, Loadable::Idle));
        if !idle {
            return;
        }
        let Some(engine) = crate::request_routing::selected_target(self.state.read(cx)).ok()
        else {
            return;
        };
        let target = self.continue_catalog_target(cx);
        let generation = self
            .continue_dialog
            .as_ref()
            .map(|dialog| dialog.generation)
            .unwrap_or_default();
        if let Some(dialog) = self.continue_dialog.as_mut() {
            dialog.harnesses = Loadable::Loading;
        }
        cx.spawn(async move |this, cx| {
            let mut params = serde_json::Map::new();
            if let Some(target) = &target {
                params.insert(
                    "targetDeviceId".into(),
                    serde_json::Value::String(target.clone()),
                );
            }
            let result = engine
                .call(
                    methods::LIST_HARNESSES,
                    serde_json::Value::Object(params),
                )
                .await;
            this.update(cx, |shell, cx| {
                let Some(dialog) = shell.continue_dialog.as_mut() else {
                    return;
                };
                if dialog.generation != generation {
                    return;
                }
                dialog.harnesses = match result {
                    Ok(value) => match serde_json::from_value::<Vec<HarnessDescriptor>>(value) {
                        Ok(list) => Loadable::Ready(list),
                        Err(error) => Loadable::Error(error.to_string()),
                    },
                    Err(error) => Loadable::Error(error.to_string()),
                };
                // No pick yet: the first OFFERED harness (the current one is
                // already excluded from the offered list) becomes the pick
                // once the catalog lands — one click confirms.
                let first = dialog.offered().first().map(|descriptor| descriptor.id);
                if dialog.harness.is_none()
                    && let Some(harness) = first
                {
                    dialog.harness = Some(harness);
                }
                cx.notify();
            })
            .ok();
            // The default may have just resolved; its model list is next.
            this.update(cx, |shell, cx| shell.ensure_continue_loads(cx)).ok();
        })
        .detach();
    }

    /// Load one harness's model list, once per dialog per harness.
    fn ensure_continue_models(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        let needs_load = self
            .continue_dialog
            .as_ref()
            .and_then(|dialog| dialog.models.get(&harness))
            .is_none_or(|slot| matches!(slot, Loadable::Idle));
        if !needs_load {
            return;
        }
        let Some(engine) = crate::request_routing::selected_target(self.state.read(cx)).ok()
        else {
            return;
        };
        let target = self.continue_catalog_target(cx);
        let generation = self
            .continue_dialog
            .as_ref()
            .map(|dialog| dialog.generation)
            .unwrap_or_default();
        if let Some(dialog) = self.continue_dialog.as_mut() {
            dialog.models.insert(harness, Loadable::Loading);
        }
        cx.spawn(async move |this, cx| {
            let mut params = serde_json::json!({ "harness": harness });
            if let (Some(target), Some(object)) = (&target, params.as_object_mut()) {
                object.insert(
                    "targetDeviceId".into(),
                    serde_json::Value::String(target.clone()),
                );
            }
            let result = engine.call(methods::LIST_MODELS, params).await;
            this.update(cx, |shell, cx| {
                let Some(dialog) = shell.continue_dialog.as_mut() else {
                    return;
                };
                if dialog.generation != generation {
                    return;
                }
                let loaded = match result {
                    Ok(value) => match serde_json::from_value::<Vec<Model>>(value) {
                        Ok(models) => Loadable::Ready(models),
                        Err(error) => Loadable::Error(error.to_string()),
                    },
                    Err(error) => Loadable::Error(error.to_string()),
                };
                dialog.models.insert(harness, loaded);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The "Continue on another harness" overlay: a modal card with the
    /// harness chips (the current one absent), the selected harness's
    /// models, and the continue/cancel pair.
    pub(super) fn render_continue_on_harness(
        &mut self,
        viewport: gpui::Size<Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let dialog = self.continue_dialog.as_ref()?;
        let theme = Theme::of(cx).for_popup();
        let catalog: Vec<HarnessDescriptor> = dialog.harnesses.ready().cloned().unwrap_or_default();
        let offered = continue_offered_harnesses(&catalog, dialog.current_harness);
        let harness_loading = matches!(dialog.harnesses, Loadable::Loading);
        let harness_error = dialog.harnesses.error().map(str::to_owned);
        let selected_harness = dialog.harness;
        let selected_model = dialog.model.clone();
        let models: Vec<Model> = selected_harness
            .and_then(|harness| dialog.models.get(&harness))
            .and_then(|slot| slot.ready().cloned())
            .unwrap_or_default();
        let models_loading = selected_harness.is_some_and(|harness| {
            matches!(dialog.models.get(&harness), Some(Loadable::Loading))
        });
        let models_error = selected_harness
            .and_then(|harness| match dialog.models.get(&harness) {
                Some(Loadable::Error(error)) => Some(error.clone()),
                _ => None,
            });
        let scroll = dialog.model_scroll.clone();
        let confirmable = selected_harness.is_some();

        let mut card = popover::dialog_card(&theme)
            .child(popover::dialog_title(&theme, "Continue on another harness"))
            .child(div().mt(px(6.0)).child(popover::dialog_body(
                &theme,
                "This chat hit its usage limit. Switching harnesses is one-way: the chat runs \
                 on the one you pick from here on, and the new agent receives a size-limited \
                 transcript of the conversation, then the continue prompt.",
            )));

        // ── Harness: one chip per offered harness (never the current one);
        //    the pick rides the border and fill, like the composer's rail
        //    tabs.
        let label_style = |el: gpui::Div| {
            el.mt(px(14.0))
                .mb(px(6.0))
                .text_size(crate::typography::ui_rems(11.0))
                .font_weight(gpui::FontWeight::SEMIBOLD)
                .text_color(theme.text_muted)
        };
        card = card.child(label_style(div()).child(SharedString::from("HARNESS")));
        let mut chips = div().flex().flex_row().flex_wrap().gap(px(6.0));
        for (ix, descriptor) in offered.iter().enumerate() {
            let harness = descriptor.id;
            let selected = selected_harness == Some(harness);
            let label: SharedString = SharedString::from(descriptor.name.clone());
            let (icon_path, tint) = crate::pickers::harness_brand_icon(harness);
            let icon_color = tint.unwrap_or(if selected {
                theme.text
            } else {
                theme.text_muted
            });
            chips = chips.child(
                div()
                    .id(("continue-harness-chip", ix))
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(6.0))
                    .px(px(10.0))
                    .py(px(5.0))
                    .rounded(px(8.0))
                    .border_1()
                    .border_color(if selected {
                        theme.border_strong
                    } else {
                        theme.border
                    })
                    .when(selected, |el| el.bg(crate::theme::card_selected_bg()))
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.pick_continue_harness(harness, cx);
                    }))
                    .child(icon(icon_path).size(px(13.0)).text_color(icon_color))
                    .child(
                        div()
                            .text_size(crate::typography::ui_rems(12.5))
                            .text_color(if selected {
                                theme.text
                            } else {
                                theme.text_muted
                            })
                            .child(label),
                    ),
            );
        }
        if offered.is_empty() {
            let text = if let Some(error) = harness_error {
                SharedString::from(format!("Couldn't load the harnesses: {error}"))
            } else if harness_loading {
                SharedString::from("Loading this device's harnesses…")
            } else {
                SharedString::from("No other harnesses are available on this device.")
            };
            chips = chips.child(
                div()
                    .px(px(10.0))
                    .py(px(5.0))
                    .rounded(px(8.0))
                    .border_1()
                    .border_color(theme.border)
                    .text_size(crate::typography::ui_rems(12.5))
                    .text_color(theme.text_muted)
                    .child(text),
            );
        }
        card = card.child(chips);

        // ── Model: the selected harness's list; the highlighted row is the
        //    pick, or the harness's default row (first) when nothing is
        //    picked — a `None` model means "the default at run time".
        card = card.child(label_style(div()).child(SharedString::from("MODEL")));
        let mut list = div()
            .id("continue-model-list")
            .max_h(px(216.0))
            .overflow_y_scroll()
            .track_scroll(&scroll)
            .flex()
            .flex_col()
            .gap(px(2.0));
        if models.is_empty() {
            let text = if let Some(error) = models_error {
                SharedString::from(format!("Couldn't load the models: {error}"))
            } else if models_loading {
                SharedString::from("Loading models…")
            } else {
                SharedString::from("This harness lists no models.")
            };
            list = list.child(
                div()
                    .py(px(6.0))
                    .text_size(crate::typography::ui_rems(12.5))
                    .text_color(theme.text_muted)
                    .child(text),
            );
        }
        for (ix, model) in models.iter().enumerate() {
            let model_id = model.id.clone();
            let selected = selected_model.as_deref() == Some(model.id.as_str())
                || (selected_model.is_none() && ix == 0);
            let default_row = selected_model.is_none() && ix == 0;
            let label: SharedString = SharedString::from(model.label.clone());
            let subline: Option<SharedString> = model
                .description
                .as_deref()
                .map(str::trim)
                .filter(|description| !description.is_empty())
                .map(SharedString::from);
            let mut row = div()
                .id(("continue-model-row", ix))
                .px(px(8.0))
                .py(px(6.0))
                .rounded(px(popover::MENU_ITEM_RADIUS))
                .flex()
                .flex_col()
                .gap(px(2.0))
                .cursor_pointer();
            if selected {
                row = row
                    .bg(crate::theme::card_selected_bg())
                    .shadow(crate::theme::card_selected_shadows());
            }
            row = row
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.pick_continue_model(model_id.clone(), cx);
                }))
                .child(
                    div()
                        .w_full()
                        .flex()
                        .flex_row()
                        .items_center()
                        .gap(px(6.0))
                        .child(
                            div()
                                .min_w_0()
                                .flex_1()
                                .truncate()
                                .text_size(crate::typography::ui_rems(12.5))
                                .font_weight(gpui::FontWeight::MEDIUM)
                                .text_color(theme.text)
                                .child(label),
                        )
                        .when(default_row, |el| {
                            el.child(
                                div()
                                    .flex_none()
                                    .text_size(crate::typography::ui_rems(10.0))
                                    .text_color(theme.text_muted)
                                    .child(SharedString::from("Default")),
                            )
                        }),
                )
                .when_some(subline, |el, subline| {
                    el.child(
                        div()
                            .w_full()
                            .truncate()
                            .text_size(crate::typography::ui_rems(11.0))
                            .text_color(theme.text_muted)
                            .child(subline),
                    )
                });
            list = list.child(row);
        }
        card = card.child(list);

        card = card.child(
            div()
                .mt(px(16.0))
                .flex()
                .flex_row()
                .justify_end()
                .gap(px(8.0))
                .child(
                    popover::btn_ghost(&theme, "Cancel", "continue-dialog-cancel")
                        .id("continue-dialog-cancel")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.close_continue_dialog(cx);
                        })),
                )
                .child(
                    popover::btn_primary(&theme, "Continue")
                        .id("continue-dialog-confirm")
                        .when(!confirmable, |el| el.opacity(0.4).cursor_default())
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if confirmable {
                                this.confirm_continue_on_harness(cx);
                            }
                        })),
                ),
        );
        Some(popover::modal(
            "continue-on-harness",
            viewport,
            card.into_any_element(),
        ))
    }
}

impl ContinueOnHarnessDialog {
    /// The harnesses this dialog offers — `continue_offered_harnesses` over
    /// its catalog, with the current harness always excluded.
    fn offered(&self) -> Vec<HarnessDescriptor> {
        let catalog = self
            .harnesses
            .ready()
            .map(|list| list.as_slice())
            .unwrap_or_default();
        continue_offered_harnesses(catalog, self.current_harness)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gpui::{AppContext, TestAppContext};

    fn descriptor(id: HarnessId, name: &str, installed: bool) -> HarnessDescriptor {
        HarnessDescriptor {
            id,
            name: name.into(),
            supports_steering: true,
            steering_mode: roboco_proto::SteeringMode::StepBoundary,
            reasoning_levels: vec![],
            installed,
            can_install: false,
            enabled: Some(true),
        }
    }

    fn chat_row(id: &str, config: Option<serde_json::Value>) -> roboco_proto::Chat {
        let mut json = serde_json::json!({
            "id": id,
            "deviceId": "local",
            "cwd": "/tmp/main",
            "archived": false,
            "createdAt": Utc::now(),
        });
        if let (Some(config), Some(object)) = (config, json.as_object_mut()) {
            object.insert("config".into(), config);
        }
        serde_json::from_value(json).unwrap()
    }

    fn shell_window(dir: &std::path::Path, cx: &mut TestAppContext) -> gpui::WindowHandle<Shell> {
        cx.update(|cx| {
            settings::init(settings::UiSettings::default(), dir, cx);
            crate::history::init(
                Default::default(),
                Default::default(),
                Default::default(),
                Default::default(),
                cx,
            );
            gpui_base::init(cx);
            cx.set_global(Theme::default());
            crate::app_menus::init(cx);
        });
        cx.add_window(|_, cx| {
            let state = cx.new(|_| AppState::new());
            Shell::new(
                state,
                EngineBootConfig {
                    data_dir: dir.into(),
                    ipc_port: 0,
                    default_harness: roboco_proto::HarnessId::Mock,
                },
                cx,
            )
        })
    }

    /// The limited chat's row and status: errored with the additive limit
    /// record on claude-code, the state the Limited banner renders for.
    fn limited_chat(id: &str) -> (roboco_proto::Chat, roboco_proto::Session) {
        let chat = chat_row(
            id,
            Some(serde_json::json!({
                "harness": "claude-code",
                "model": "claude-default",
                "sandbox": "workspace-write",
            })),
        );
        let session = roboco_proto::Session {
            last_completed_turn: None,
            chat_id: id.into(),
            device_id: "local".into(),
            status: roboco_proto::SessionStatus::Errored,
            started_at: None,
            updated_at: Utc::now(),
            limit: Some(roboco_proto::SessionLimit {
                harness: HarnessId::ClaudeCode,
                window: "5-hour".into(),
                resets_at: Some(1_762_675_400_000),
                account_id: None,
            }),
        };
        (chat, session)
    }

    /// The wire payload: the chat and the chosen harness always ride; the
    /// model rides only when one was picked, so the new harness's own
    /// default applies otherwise. Blank strings are absent picks.
    #[test]
    fn switch_params_carry_the_chosen_harness_and_model() {
        let plain = switch_params("chat-1", HarnessId::Pi, None);
        assert_eq!(
            plain,
            serde_json::json!({ "chatId": "chat-1", "harness": "pi" })
        );
        let picked = switch_params("chat-1", HarnessId::Codex, Some("  gpt-6-codex  "));
        assert_eq!(picked["model"], "gpt-6-codex");
        let blank = switch_params("chat-1", HarnessId::Pi, Some(" "));
        assert!(blank.get("model").is_none(), "a blank model is no pick");
    }

    /// The offered list: the composer's offered set (installed and enabled),
    /// with the CURRENT harness always dropped — a Limited chat escapes its
    /// limit on another harness, never a re-pick of the one that hit it.
    #[test]
    fn continue_offered_harnesses_skip_the_current_harness() {
        let catalog = vec![
            descriptor(HarnessId::ClaudeCode, "Claude Code", true),
            descriptor(HarnessId::Codex, "Codex", true),
            descriptor(HarnessId::Pi, "Pi", true),
            // Present but not installed: offered_harnesses drops it.
            descriptor(HarnessId::Grok, "Grok", false),
        ];
        let offered = continue_offered_harnesses(&catalog, Some(HarnessId::ClaudeCode));
        assert_eq!(
            offered.iter().map(|d| d.id).collect::<Vec<_>>(),
            vec![HarnessId::Codex, HarnessId::Pi]
        );
        // The current harness is the ONLY other one: nothing to offer.
        let only = continue_offered_harnesses(&catalog[..1], Some(HarnessId::ClaudeCode));
        assert!(only.is_empty());
        // A config-less chat (no current harness): the whole offered set.
        let all = continue_offered_harnesses(&catalog, None);
        assert_eq!(all.len(), 3);
        assert!(continue_offered_harnesses(&[], Some(HarnessId::Pi)).is_empty());
    }

    /// The dialog opens on a Limited chat unpicked; the catalog landing
    /// resolves the default to the first OFFERED harness — never the
    /// current one, which the offered list already excludes.
    #[gpui::test]
    fn the_dialog_defaults_to_the_first_other_harness(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat.clone()];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                });
                shell.open_continue_on_harness(cx);
                let dialog = shell.continue_dialog.as_ref().unwrap();
                assert_eq!(dialog.chat.id, "limited");
                assert_eq!(dialog.current_harness, Some(HarnessId::ClaudeCode));
                assert_eq!(dialog.harness, None, "no pick before the catalog lands");
                // The catalog lands (as the load task would write it):
                let catalog = vec![
                    descriptor(HarnessId::ClaudeCode, "Claude Code", true),
                    descriptor(HarnessId::Pi, "Pi", true),
                ];
                let dialog = shell.continue_dialog.as_mut().unwrap();
                dialog.harnesses = Loadable::Ready(catalog);
                // The default resolution the load completion applies —
                // exercised through the dialog's own offered list here.
                let first = dialog.offered().first().map(|d| d.id);
                if dialog.harness.is_none()
                    && let Some(harness) = first
                {
                    dialog.harness = Some(harness);
                }
                assert_eq!(
                    shell.continue_dialog.as_ref().unwrap().harness,
                    Some(HarnessId::Pi),
                    "the default skips the current harness"
                );
                shell.close_continue_dialog(cx);
                assert!(shell.continue_dialog.is_none());
            })
            .unwrap();
    }

    /// Picking a harness resets the model (the previous pick's model id is
    /// meaningless there); a model pick rides the confirm. Closing retires
    /// the dialog without continuing.
    #[gpui::test]
    fn picking_harnesses_and_models_steers_the_continuation(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat.clone()];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                });
                shell.open_continue_on_harness(cx);
                {
                    let dialog = shell.continue_dialog.as_mut().unwrap();
                    dialog.harness = Some(HarnessId::Pi);
                    dialog.model = Some("stale-model".into());
                }
                // A harness pick resets the model.
                shell.pick_continue_harness(HarnessId::Codex, cx);
                let dialog = shell.continue_dialog.as_ref().unwrap();
                assert_eq!(dialog.harness, Some(HarnessId::Codex));
                assert_eq!(dialog.model, None, "the model resets with the harness");
                // A model pick on the new harness rides the continuation.
                shell.pick_continue_model("gpt-6-codex".into(), cx);
                assert_eq!(
                    shell.continue_dialog.as_ref().unwrap().model.as_deref(),
                    Some("gpt-6-codex")
                );
                // Closing retires the dialog without continuing.
                shell.close_continue_dialog(cx);
                assert!(shell.continue_dialog.is_none());
            })
            .unwrap();
    }

    /// Escape closes the dialog, like the other modal overlays.
    #[gpui::test]
    fn escape_closes_the_continue_dialog(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                });
                shell.open_continue_on_harness(cx);
                assert!(shell.continue_dialog.is_some());
                assert!(shell.overlay_owns_keyboard(cx));
                assert!(shell.capture_escape_surface(cx));
                assert!(shell.continue_dialog.is_none());
                assert!(!shell.overlay_owns_keyboard(cx));
            })
            .unwrap();
    }

    /// The overlay renders while the dialog is open — harness chips, model
    /// rows and the disabled confirm against a landed catalog with no pick —
    /// and stops once it closes.
    #[gpui::test]
    fn the_dialog_overlay_builds_from_a_landed_catalog(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                });
                shell.open_continue_on_harness(cx);
                let viewport = gpui::size(px(800.0), px(600.0));
                // Nothing has loaded yet: the overlay still renders (its
                // loading states).
                assert!(shell.render_continue_on_harness(viewport, cx).is_some());
                // A landed catalog and model list, with a resolved pick.
                {
                    let dialog = shell.continue_dialog.as_mut().unwrap();
                    dialog.harnesses = Loadable::Ready(vec![
                        descriptor(HarnessId::Pi, "Pi", true),
                        descriptor(HarnessId::Codex, "Codex", true),
                    ]);
                    dialog.harness = Some(HarnessId::Pi);
                    dialog.models.insert(
                        HarnessId::Pi,
                        Loadable::Ready(vec![Model {
                            id: "glm-5.3".into(),
                            label: "GLM 5.3".into(),
                            description: Some("the flagship".into()),
                            reasoning_levels: vec![],
                            options: vec![],
                        }]),
                    );
                }
                assert!(shell.render_continue_on_harness(viewport, cx).is_some());
                shell.close_continue_dialog(cx);
                assert!(shell.render_continue_on_harness(viewport, cx).is_none());
            })
            .unwrap();
    }

    /// The action appears on Limited chats: the status strip's Limited
    /// branch builds the banner — window label, ticket 06's Resume pill, and
    /// the new Continue on another harness pill whose click opens this
    /// dialog.
    #[gpui::test]
    fn the_limited_banner_renders_the_continue_action(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                });
                // The Limited branch builds — including the new pill.
                let strip = shell.render_status_strip(640.0, cx);
                drop(strip);
                // The pill's click opens this dialog.
                shell.open_continue_on_harness(cx);
                assert!(shell.continue_dialog.is_some());
                shell.close_continue_dialog(cx);
            })
            .unwrap();
    }

    /// Drive the scripted engine's current_thread runtime and the gpui
    /// queue once, collecting every frame that went out. `EngineTarget::call`
    /// spawns onto the registry's runtime handle, so a frame lands only once
    /// the runtime is driven (the sidebar_state_sync pump idiom).
    fn pump_once(
        runtime: &tokio::runtime::Runtime,
        cx: &mut TestAppContext,
        requests: &mut tokio::sync::mpsc::Receiver<String>,
    ) -> Vec<serde_json::Value> {
        for _ in 0..16 {
            runtime.block_on(async {
                tokio::task::yield_now().await;
            });
        }
        cx.run_until_parked();
        let mut frames = Vec::new();
        while let Ok(raw) = requests.try_recv() {
            frames.push(serde_json::from_str(&raw).expect("a well-formed frame"));
        }
        frames
    }

    /// Pump until `done` holds over the frames collected SO FAR — selecting a
    /// chat starts unrelated standing traffic (doc watches, seen-marks), so
    /// the assertions filter by method instead of assuming frame order.
    fn pump_until(
        runtime: &tokio::runtime::Runtime,
        cx: &mut TestAppContext,
        requests: &mut tokio::sync::mpsc::Receiver<String>,
        mut done: impl FnMut(&[serde_json::Value]) -> bool,
    ) -> Vec<serde_json::Value> {
        let mut frames = Vec::new();
        for _ in 0..12 {
            frames.extend(pump_once(runtime, cx, requests));
            if done(&frames) {
                break;
            }
        }
        frames
    }

    fn frame_for<'a>(frames: &'a [serde_json::Value], method: &str) -> Option<&'a serde_json::Value> {
        frames
            .iter()
            .find(|frame| frame["method"].as_str() == Some(method))
    }

    /// Confirming a switch: the dialog hands the chat's one-way switch to
    /// the engine (harness and model, nothing else), and only once the
    /// engine accepts it does the continue prompt go out as a normal send —
    /// carrying the new harness (the stamped row) and the picked model.
    #[gpui::test]
    fn confirm_switches_the_harness_then_sends_the_continue_prompt(
        cx: &mut TestAppContext,
    ) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel::<String>(256);
        let (replies, inbound) = tokio::sync::mpsc::channel::<String>(256);
        let engine = crate::state::EngineHandle::from_test_client(roboco_rpc::RpcClient::new(
            out, inbound,
        ));
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                    state.set_test_engine(engine);
                });
                shell.open_continue_on_harness(cx);
                {
                    let dialog = shell.continue_dialog.as_mut().unwrap();
                    dialog.harness = Some(HarnessId::Pi);
                    dialog.model = Some("glm-5.3".into());
                }
                shell.confirm_continue_on_harness(cx);
                assert!(shell.continue_dialog.is_none(), "confirm consumes the dialog");
            })
            .unwrap();

        // The switch went out, with only the chosen picks.
        let frames = pump_until(&runtime, cx, &mut requests, |frames| {
            frame_for(frames, "SwitchHarness").is_some()
        });
        let switch = frame_for(&frames, "SwitchHarness")
            .expect("the switch must reach the engine RPC")
            .clone();
        assert_eq!(switch["params"]["chatId"], "limited");
        assert_eq!(switch["params"]["harness"], "pi");
        assert_eq!(switch["params"]["model"], "glm-5.3");
        assert!(
            switch["params"].get("reasoning").is_none()
                && switch["params"].get("modelOptions").is_none(),
            "the dialog picks neither reasoning nor options — the new harness's defaults apply"
        );
        // Nothing raced ahead of the reply: no continue prompt before the
        // switch lands.
        assert!(
            frame_for(&frames, "QueueCommand").is_none(),
            "no send before the switch lands"
        );

        // The engine accepts; the row is stamped and the continue prompt
        // goes out as a normal run.
        runtime.block_on(async {
            replies
                .send(serde_json::json!({ "id": switch["id"], "ok": {} }).to_string())
                .await
                .unwrap();
            // Drive the client's reader task until the reply is routed to
            // the pending call (the capacity returns when it is consumed).
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        let frames = pump_until(&runtime, cx, &mut requests, |frames| {
            frame_for(frames, "QueueCommand").is_some()
        });
        let send = frame_for(&frames, "QueueCommand")
            .expect("the continue prompt must follow the switch")
            .clone();
        assert_eq!(send["params"]["chatId"], "limited");
        assert_eq!(
            send["params"]["command"]["request"]["prompt"],
            roboco_proto::RESUME_CONTINUE_PROMPT
        );
        assert_eq!(send["params"]["command"]["request"]["harness"], "pi");
        assert_eq!(send["params"]["command"]["request"]["model"], "glm-5.3");
        // Answer the send so the composer's task settles.
        runtime.block_on(async {
            replies
                .send(serde_json::json!({ "id": send["id"], "ok": {} }).to_string())
                .await
                .unwrap();
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        pump_once(&runtime, cx, &mut requests);

        window
            .update(cx, |shell, _, cx| {
                // The optimistic stamp: the row names the new harness and
                // model, so the composer's send carried them.
                let row = shell
                    .state
                    .read(cx)
                    .chats
                    .iter()
                    .find(|chat| chat.id == "limited")
                    .unwrap();
                assert_eq!(
                    row.config.as_ref().map(|config| config.harness),
                    Some(HarnessId::Pi)
                );
                assert_eq!(row.config.as_ref().and_then(|c| c.model.as_deref()), Some("glm-5.3"));
            })
            .unwrap();
    }

    /// A switch the engine refuses never sends the continue prompt: the
    /// chat keeps its old harness (nothing was stamped) and the failure
    /// surfaces in the composer — the banner stays up for another try.
    #[gpui::test]
    fn a_refused_switch_never_sends_the_continue_prompt(cx: &mut TestAppContext) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel::<String>(256);
        let (replies, inbound) = tokio::sync::mpsc::channel::<String>(256);
        let engine = crate::state::EngineHandle::from_test_client(roboco_rpc::RpcClient::new(
            out, inbound,
        ));
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let (chat, session) = limited_chat("limited");
        window
            .update(cx, |shell, _, cx| {
                shell.state.update(cx, |state, cx| {
                    state.chats = vec![chat];
                    state.sessions = vec![session];
                    state.select_chat(Some("limited".into()), cx);
                    state.set_test_engine(engine);
                });
                shell.open_continue_on_harness(cx);
                {
                    let dialog = shell.continue_dialog.as_mut().unwrap();
                    dialog.harness = Some(HarnessId::Pi);
                }
                shell.confirm_continue_on_harness(cx);
            })
            .unwrap();

        let frames = pump_until(&runtime, cx, &mut requests, |frames| {
            frame_for(frames, "SwitchHarness").is_some()
        });
        let switch = frame_for(&frames, "SwitchHarness")
            .expect("the switch must reach the engine RPC")
            .clone();
        // The engine refuses (the chat is working, say).
        runtime.block_on(async {
            replies
                .send(
                    serde_json::json!({
                        "id": switch["id"],
                        "err": "The chat is working; switch harnesses between turns"
                    })
                    .to_string(),
                )
                .await
                .unwrap();
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        // Let the refusal land and anything it could have started settle.
        let mut frames = frames;
        for _ in 0..3 {
            frames.extend(pump_once(&runtime, cx, &mut requests));
        }

        // No continue prompt followed the refusal.
        assert!(
            frame_for(&frames, "QueueCommand").is_none(),
            "a refused switch must never send the continue prompt"
        );
        window
            .update(cx, |shell, _, cx| {
                // The row was never stamped: still on the limited harness.
                let row = shell
                    .state
                    .read(cx)
                    .chats
                    .iter()
                    .find(|chat| chat.id == "limited")
                    .unwrap();
                assert_eq!(
                    row.config.as_ref().map(|config| config.harness),
                    Some(HarnessId::ClaudeCode)
                );
                // The failure surfaced in the composer.
                let failure = shell
                    .composer
                    .read(cx)
                    .failure()
                    .map(|failure| failure.to_string())
                    .unwrap_or_default();
                assert!(failure.contains("switch"), "{failure}");
            })
            .unwrap();
    }
}
