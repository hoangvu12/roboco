//! The fork dialog (t3-orchestration-v2 ticket 22): the harness and model a
//! fork runs on, chosen when the fork starts.
//!
//! Both desktop fork entry points — the classic "Fork this chat" button and
//! ticket 21's "Fork from here" — open this dialog instead of forking
//! immediately. It defaults to the source's own harness and model (spec
//! story 61), so confirming without touching anything forks exactly as
//! before; picking another harness makes the fork a cross-harness one, which
//! the engine serves with the budgeted history instead of a native session
//! fork. The dialog is deliberately local to the fork flow: the composer's
//! own harness/model picker (and its unlock in ticket 24's one-way harness
//! switch) stays untouched, so the two features never share state.

use super::*;

use roboco_engine::registry::HarnessDescriptor;
use roboco_proto::{HarnessId, Model};

/// A pending fork: what is being forked, from where, and onto which harness
/// and model. Held by [`Shell::fork_dialog`] while the dialog is open.
pub(super) struct ForkDialog {
    /// The chat being forked — the row the FORK_SIDE_CHAT RPC copies.
    source: roboco_proto::Chat,
    /// Where the copy hangs in the tree: the source, or the source's parent
    /// when a side chat forks itself (the copy lists as a sibling).
    parent_id: String,
    /// Ticket 21's boundary entry id: the reply the copy stops at,
    /// inclusive. `None` is the classic fork (the latest complete reply).
    boundary: Option<String>,
    /// The harness the fork will run on. `None` until a default resolves —
    /// the source's own config first, the first offered harness when the
    /// source carries no config. `None` at confirm time means "no override":
    /// the fork keeps the source's harness and its native fork behaviour.
    harness: Option<HarnessId>,
    /// The model the fork will run on. `None` = the harness's default at run
    /// time. The source's model only applies while the source's harness is
    /// the pick; another harness resets it.
    model: Option<String>,
    /// The source device's harness catalog, loaded once per dialog.
    harnesses: Loadable<Vec<HarnessDescriptor>>,
    /// Model lists per harness, loaded once per dialog per harness.
    models: std::collections::HashMap<HarnessId, Loadable<Vec<Model>>>,
    /// Stale-response guard: bumped when the dialog closes, so an in-flight
    /// catalog load lands on a closed (or reopened) dialog and no-ops.
    generation: u64,
    /// The model list's scroll.
    model_scroll: gpui::ScrollHandle,
}

/// The ForkSideChat params for a fork (t3-orchestration-v2 tickets 21+22):
/// the idempotent chat id, the source, where the copy hangs, and the chosen
/// boundary / harness / model. Overrides ride only when chosen — an engine
/// that predates them keeps its classic fork behaviour, and a dialog that
/// confirmed the defaults sends no override at all.
pub(super) fn fork_params(
    chat_id: &str,
    source: &roboco_proto::Chat,
    parent_id: &str,
    boundary: Option<&str>,
    harness: Option<HarnessId>,
    model: Option<&str>,
) -> serde_json::Value {
    let mut params = serde_json::json!({
        "chatId": chat_id,
        "sourceChatId": source.id,
        "parentChatId": parent_id,
        "targetDeviceId": source.device_id,
    });
    // Only send what was chosen: absent keys are the old payload verbatim.
    if let Some(boundary) = boundary.map(str::trim).filter(|id| !id.is_empty()) {
        params["boundary"] = serde_json::Value::String(boundary.to_owned());
    }
    if let Some(harness) = harness {
        // The kebab-case wire name ("codex", "claude-code").
        if let Ok(value) = serde_json::to_value(harness) {
            params["harness"] = value;
        }
    }
    if let Some(model) = model.map(str::trim).filter(|id| !id.is_empty()) {
        params["model"] = serde_json::Value::String(model.to_owned());
    }
    params
}

/// The harnesses the fork dialog offers: the same offered set the composer's
/// picker uses (installed and enabled on the listing device), plus the
/// harness `fallback` names (the source's own, or the current pick) whenever
/// the catalog carries it — a chat can outlive its harness's enablement, and
/// the default must stay pickable so "fork as-is" is always one click.
pub(super) fn fork_offered(
    catalog: &[HarnessDescriptor],
    fallback: Option<HarnessId>,
) -> Vec<HarnessDescriptor> {
    let mut offered = crate::pickers::offered_harnesses(catalog);
    if let Some(harness) = fallback
        && !offered.iter().any(|descriptor| descriptor.id == harness)
        && let Some(descriptor) = catalog.iter().find(|descriptor| descriptor.id == harness)
    {
        offered.push(descriptor.clone());
    }
    offered
}

impl Shell {
    /// Open the fork dialog for `source`, hanging the copy under
    /// `parent_id`, from `boundary` (ticket 21's "Fork from here") or the
    /// latest reply. The selection starts as the source's own harness and
    /// model; confirming performs the fork.
    pub(super) fn open_fork_dialog(
        &mut self,
        source: roboco_proto::Chat,
        parent_id: String,
        boundary: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if self.side_chat_creating {
            return;
        }
        let harness = source.config.as_ref().map(|config| config.harness);
        let model = source.config.as_ref().and_then(|config| config.model.clone());
        self.fork_dialog = Some(ForkDialog {
            source,
            parent_id,
            boundary,
            harness,
            model,
            harnesses: Loadable::Idle,
            models: std::collections::HashMap::new(),
            generation: 0,
            model_scroll: gpui::ScrollHandle::new(),
        });
        self.ensure_fork_loads(cx);
        cx.notify();
    }

    /// Close the dialog without forking. The generation bump retires
    /// in-flight catalog loads.
    pub(super) fn close_fork_dialog(&mut self, cx: &mut Context<Self>) {
        if let Some(dialog) = self.fork_dialog.as_mut() {
            dialog.generation += 1;
        }
        self.fork_dialog = None;
        cx.notify();
    }

    /// Pick the fork's harness. A pick of another harness resets the model
    /// (the source's model id is meaningless there; the new harness's own
    /// default applies at run time); a pick back onto the source's harness
    /// restores it.
    pub(super) fn pick_fork_harness(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        {
            let Some(dialog) = self.fork_dialog.as_mut() else {
                return;
            };
            if dialog.harness == Some(harness) {
                return;
            }
            let source_harness = dialog
                .source
                .config
                .as_ref()
                .map(|config| config.harness);
            let source_model = dialog
                .source
                .config
                .as_ref()
                .and_then(|config| config.model.clone());
            dialog.harness = Some(harness);
            dialog.model = (source_harness == Some(harness))
                .then_some(source_model)
                .flatten();
        }
        self.ensure_fork_models(harness, cx);
        cx.notify();
    }

    /// Pick the fork's model on the selected harness.
    pub(super) fn pick_fork_model(&mut self, model: String, cx: &mut Context<Self>) {
        if let Some(dialog) = self.fork_dialog.as_mut() {
            dialog.model = Some(model);
        }
        cx.notify();
    }

    /// Confirm: hand the dialog's fork — source, parent, boundary and the
    /// chosen harness/model — to the fork RPC flow.
    pub(super) fn confirm_fork(&mut self, cx: &mut Context<Self>) {
        let Some(dialog) = self.fork_dialog.take() else {
            return;
        };
        let ForkDialog {
            source,
            parent_id,
            boundary,
            harness,
            model,
            ..
        } = dialog;
        self.fork_chat(source, parent_id, boundary, harness, model, cx);
    }

    /// Kick every load the dialog's current selection needs: the harness
    /// catalog when still idle, and the selected harness's model list.
    fn ensure_fork_loads(&mut self, cx: &mut Context<Self>) {
        self.ensure_fork_harnesses(cx);
        let harness = self.fork_dialog.as_ref().and_then(|dialog| dialog.harness);
        if let Some(harness) = harness {
            self.ensure_fork_models(harness, cx);
        }
    }

    /// The catalog load's `targetDeviceId`: the fork runs on the SOURCE's
    /// device (the RPC refuses a source hosted elsewhere), so its installed
    /// agents are the ones that matter. `None` for a locally-hosted source —
    /// the connected engine serves it directly.
    fn fork_catalog_target(&self, cx: &App) -> Option<String> {
        let local = self.state.read(cx).local_device_id.as_deref()?;
        let dialog = self.fork_dialog.as_ref()?;
        (local != dialog.source.device_id.as_str()).then(|| dialog.source.device_id.clone())
    }

    /// Load the harness catalog once per dialog: the list is short-lived, so
    /// no refresh-on-open and no error retry — reopening the dialog retries.
    fn ensure_fork_harnesses(&mut self, cx: &mut Context<Self>) {
        let idle = self
            .fork_dialog
            .as_ref()
            .is_some_and(|dialog| matches!(dialog.harnesses, Loadable::Idle));
        if !idle {
            return;
        }
        let Some(engine) = crate::request_routing::selected_target(self.state.read(cx)).ok()
        else {
            return;
        };
        let target = self.fork_catalog_target(cx);
        let generation = self
            .fork_dialog
            .as_ref()
            .map(|dialog| dialog.generation)
            .unwrap_or_default();
        if let Some(dialog) = self.fork_dialog.as_mut() {
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
                let Some(dialog) = shell.fork_dialog.as_mut() else {
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
                // A source with no config keeps no default of its own: the
                // first offered harness becomes the pick once the catalog
                // lands, mirroring the composer's picker.
                let first = dialog
                    .fork_offered()
                    .first()
                    .map(|descriptor| descriptor.id);
                if dialog.harness.is_none()
                    && let Some(harness) = first
                {
                    dialog.harness = Some(harness);
                }
                cx.notify();
            })
            .ok();
            // The default may have just resolved; its model list is next.
            this.update(cx, |shell, cx| shell.ensure_fork_loads(cx)).ok();
        })
        .detach();
    }

    /// Load one harness's model list, once per dialog per harness.
    fn ensure_fork_models(&mut self, harness: HarnessId, cx: &mut Context<Self>) {
        let needs_load = self
            .fork_dialog
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
        let target = self.fork_catalog_target(cx);
        let generation = self
            .fork_dialog
            .as_ref()
            .map(|dialog| dialog.generation)
            .unwrap_or_default();
        if let Some(dialog) = self.fork_dialog.as_mut() {
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
                let Some(dialog) = shell.fork_dialog.as_mut() else {
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

    /// The fork dialog overlay: a modal card with the harness chips, the
    /// selected harness's models, and the fork/cancel pair.
    pub(super) fn render_fork_dialog(
        &mut self,
        viewport: gpui::Size<Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let dialog = self.fork_dialog.as_ref()?;
        let theme = Theme::of(cx).for_popup();
        let source_harness = dialog.source.config.as_ref().map(|config| config.harness);
        let catalog: Vec<HarnessDescriptor> = dialog.harnesses.ready().cloned().unwrap_or_default();
        let offered = fork_offered(&catalog, source_harness.or(dialog.harness));
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
        let boundary_set = dialog.boundary.is_some();
        let cross_harness = selected_harness.is_some_and(|harness| source_harness != Some(harness));
        let scroll = dialog.model_scroll.clone();

        let title = if boundary_set {
            "Fork from here"
        } else {
            "Fork this chat"
        };
        let body = if boundary_set {
            "A new side chat continues from this reply. Files are not rewound."
        } else {
            "A new side chat continues from the latest reply. Files are not rewound."
        };
        let mut card = popover::dialog_card(&theme)
            .child(popover::dialog_title(&theme, title))
            .child(div().mt(px(6.0)).child(popover::dialog_body(&theme, body)));

        // ── Agent: one chip per offered harness; the pick rides the border
        //    and fill, like the composer's rail tabs.
        let label_style = |el: gpui::Div| {
            el.mt(px(14.0))
                .mb(px(6.0))
                .text_size(crate::typography::ui_rems(11.0))
                .font_weight(gpui::FontWeight::SEMIBOLD)
                .text_color(theme.text_muted)
        };
        card = card.child(label_style(div()).child(SharedString::from("AGENT")));
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
                    .id(("fork-harness-chip", ix))
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
                        this.pick_fork_harness(harness, cx);
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
                SharedString::from(format!("Couldn't load the agents: {error}"))
            } else if harness_loading {
                SharedString::from("Loading this device's agents…")
            } else {
                SharedString::from("No agents are available on this device.")
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
            .id("fork-model-list")
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
                SharedString::from("This agent lists no models.")
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
            let selected =
                selected_model.as_deref() == Some(model.id.as_str()) || (selected_model.is_none() && ix == 0);
            let default_row = selected_model.is_none() && ix == 0;
            let label: SharedString = SharedString::from(model.label.clone());
            let subline: Option<SharedString> = model
                .description
                .as_deref()
                .map(str::trim)
                .filter(|description| !description.is_empty())
                .map(SharedString::from);
            let mut row = div()
                .id(("fork-model-row", ix))
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
                    this.pick_fork_model(model_id.clone(), cx);
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

        // ── Cross-harness note: the one behaviour difference the pick made.
        if cross_harness {
            card = card.child(
                div()
                    .mt(px(12.0))
                    .text_size(crate::typography::ui_rems(12.0))
                    .line_height(px(17.0))
                    .text_color(theme.text_muted)
                    .child(SharedString::from(
                        "A different agent starts a fresh session and receives a \
                         size-limited transcript of this conversation, not the full history.",
                    )),
            );
        }

        card = card.child(
            div()
                .mt(px(16.0))
                .flex()
                .flex_row()
                .justify_end()
                .gap(px(8.0))
                .child(
                    popover::btn_ghost(&theme, "Cancel", "fork-dialog-cancel")
                        .id("fork-dialog-cancel")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.close_fork_dialog(cx);
                        })),
                )
                .child(
                    popover::btn_primary(&theme, "Fork")
                        .id("fork-dialog-confirm")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.confirm_fork(cx);
                        })),
                ),
        );
        Some(popover::modal("fork-dialog", viewport, card.into_any_element()))
    }
}

impl ForkDialog {
    /// The harnesses this dialog offers — `fork_offered` over its catalog
    /// with the source's harness as the guaranteed member.
    fn fork_offered(&self) -> Vec<HarnessDescriptor> {
        let source_harness = self.source.config.as_ref().map(|config| config.harness);
        let catalog = self
            .harnesses
            .ready()
            .map(|list| list.as_slice())
            .unwrap_or_default();
        fork_offered(catalog, source_harness.or(self.harness))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gpui::{AppContext, TestAppContext};

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

    /// The wire payload: the four identity keys always ride; the boundary,
    /// harness and model ride only when chosen, so a pre-ticket-22 engine
    /// still reads its classic fork and a defaults-confirm is byte-for-byte
    /// the old request.
    #[test]
    fn fork_params_carries_only_the_chosen_overrides() {
        let source = chat_row(
            "src",
            Some(serde_json::json!({"harness": "codex", "sandbox": "workspace-write"})),
        );
        let plain = fork_params("new", &source, "src", None, None, None);
        assert_eq!(
            plain,
            serde_json::json!({
                "chatId": "new",
                "sourceChatId": "src",
                "parentChatId": "src",
                "targetDeviceId": "local",
            }),
            "a defaults-confirm sends the classic payload"
        );
        let overridden = fork_params(
            "new",
            &source,
            "src",
            Some("  a1  "),
            Some(HarnessId::Pi),
            Some("  glm-5.3  "),
        );
        assert_eq!(overridden["boundary"], "a1");
        assert_eq!(overridden["harness"], "pi");
        assert_eq!(overridden["model"], "glm-5.3");
        // Blank strings are absent picks, not empty overrides.
        let blank = fork_params("new", &source, "src", Some(" "), None, Some(""));
        assert!(blank.get("boundary").is_none());
        assert!(blank.get("harness").is_none());
        assert!(blank.get("model").is_none());
    }

    /// The offered list: the composer's offered set, with the source's own
    /// harness appended when the catalog carries it but it is not offered —
    /// the default must stay pickable.
    #[test]
    fn fork_offered_keeps_the_source_harness_pickable() {
        let descriptor = |id: HarnessId, name: &str, installed: bool| HarnessDescriptor {
            id,
            name: name.into(),
            supports_steering: true,
            steering_mode: roboco_proto::SteeringMode::StepBoundary,
            reasoning_levels: vec![],
            installed,
            can_install: false,
            enabled: Some(true),
        };
        let catalog = vec![
            descriptor(HarnessId::Codex, "Codex", true),
            descriptor(HarnessId::Pi, "Pi", true),
            // Present but not installed: offered_harnesses drops it.
            descriptor(HarnessId::ClaudeCode, "Claude Code", false),
        ];
        let offered = fork_offered(&catalog, Some(HarnessId::Codex));
        assert_eq!(
            offered.iter().map(|d| d.id).collect::<Vec<_>>(),
            vec![HarnessId::Codex, HarnessId::Pi]
        );
        // The source's harness is not offered (its CLI is gone): it still
        // rides the list, behind the offered ones.
        let offered = fork_offered(&catalog, Some(HarnessId::ClaudeCode));
        assert_eq!(
            offered.iter().map(|d| d.id).collect::<Vec<_>>(),
            vec![HarnessId::Codex, HarnessId::Pi, HarnessId::ClaudeCode]
        );
        // Not in the catalog at all: nothing to append.
        let offered = fork_offered(&catalog, Some(HarnessId::Grok));
        assert_eq!(
            offered.iter().map(|d| d.id).collect::<Vec<_>>(),
            vec![HarnessId::Codex, HarnessId::Pi]
        );
        assert!(fork_offered(&[], Some(HarnessId::Pi)).is_empty());
    }

    /// The dialog opens on both entry points with the source's own harness
    /// and model — the classic fork button passes no boundary, "Fork from
    /// here" passes the chosen reply's entry id.
    #[gpui::test]
    fn fork_dialog_opens_with_the_source_defaults(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let source = chat_row(
            "src",
            Some(serde_json::json!({
                "harness": "codex",
                "model": "gpt-6-codex",
                "sandbox": "workspace-write",
            })),
        );
        window
            .update(cx, |shell, _, cx| {
                // The classic button: no boundary, copy hangs under the
                // source.
                shell.open_fork_dialog(source.clone(), "src".into(), None, cx);
                let dialog = shell.fork_dialog.as_ref().unwrap();
                assert_eq!(dialog.source.id, "src");
                assert_eq!(dialog.parent_id, "src");
                assert_eq!(dialog.boundary, None);
                assert_eq!(dialog.harness, Some(HarnessId::Codex));
                assert_eq!(dialog.model.as_deref(), Some("gpt-6-codex"));
                shell.close_fork_dialog(cx);
                // "Fork from here": the boundary rides the dialog.
                shell.open_fork_dialog(source.clone(), "src".into(), Some("a1".into()), cx);
                let dialog = shell.fork_dialog.as_ref().unwrap();
                assert_eq!(dialog.boundary.as_deref(), Some("a1"));
                assert_eq!(dialog.harness, Some(HarnessId::Codex));
                shell.close_fork_dialog(cx);
            })
            .unwrap();
    }

    /// A source with no config yet: the dialog starts unpicked, and the
    /// first offered harness becomes the default once a catalog lands.
    #[gpui::test]
    fn fork_dialog_defaults_a_configless_source_to_the_first_offered_harness(
        cx: &mut TestAppContext,
    ) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let source = chat_row("src", None);
        window
            .update(cx, |shell, _, cx| {
                shell.open_fork_dialog(source.clone(), "src".into(), None, cx);
                assert!(shell.fork_dialog.as_ref().unwrap().harness.is_none());
                // The catalog lands (as the load task would write it):
                let catalog = vec![
                    HarnessDescriptor {
                        id: HarnessId::Codex,
                        name: "Codex".into(),
                        supports_steering: true,
                        steering_mode: roboco_proto::SteeringMode::StepBoundary,
                        reasoning_levels: vec![],
                        installed: true,
                        can_install: false,
                        enabled: Some(true),
                    },
                ];
                let dialog = shell.fork_dialog.as_mut().unwrap();
                dialog.harnesses = Loadable::Ready(catalog);
                // The default-resolution the load completion applies —
                // exercised through the dialog's own offered list here.
                let first = dialog.fork_offered().first().map(|d| d.id);
                if dialog.harness.is_none() && let Some(harness) = first {
                    dialog.harness = Some(harness);
                }
                assert_eq!(shell.fork_dialog.as_ref().unwrap().harness, Some(HarnessId::Codex));
                shell.close_fork_dialog(cx);
            })
            .unwrap();
    }

    /// Picking another harness resets the model (the source's model id is
    /// meaningless there); picking the source's harness back restores it.
    /// Confirming hands the selection to the fork flow and clears the
    /// dialog; closing retires it without one.
    #[gpui::test]
    fn picking_harnesses_and_models_steers_the_override(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let source = chat_row(
            "src",
            Some(serde_json::json!({
                "harness": "codex",
                "model": "gpt-6-codex",
                "sandbox": "workspace-write",
            })),
        );
        window
            .update(cx, |shell, _, cx| {
                shell.open_fork_dialog(source.clone(), "src".into(), Some("a1".into()), cx);
                // Another harness: the model resets with it.
                shell.pick_fork_harness(HarnessId::Pi, cx);
                let dialog = shell.fork_dialog.as_ref().unwrap();
                assert_eq!(dialog.harness, Some(HarnessId::Pi));
                assert_eq!(dialog.model, None, "the source's model never crosses harnesses");
                // A model pick on the new harness rides the override.
                shell.pick_fork_model("glm-5.3".into(), cx);
                assert_eq!(
                    shell.fork_dialog.as_ref().unwrap().model.as_deref(),
                    Some("glm-5.3")
                );
                // Back onto the source's harness: its own model returns.
                shell.pick_fork_harness(HarnessId::Codex, cx);
                let dialog = shell.fork_dialog.as_ref().unwrap();
                assert_eq!(dialog.harness, Some(HarnessId::Codex));
                assert_eq!(dialog.model.as_deref(), Some("gpt-6-codex"));
                // Closing retires the dialog without forking.
                shell.close_fork_dialog(cx);
                assert!(shell.fork_dialog.is_none());
                // Confirm: the dialog hands its fork over (this fixture has
                // no engine, so the RPC flow steps out early — the dialog is
                // still consumed, and nothing panics).
                shell.open_fork_dialog(source.clone(), "src".into(), Some("a1".into()), cx);
                shell.pick_fork_harness(HarnessId::Pi, cx);
                shell.confirm_fork(cx);
                assert!(shell.fork_dialog.is_none());
            })
            .unwrap();
    }

    /// Escape closes the fork dialog, like the other modal overlays.
    #[gpui::test]
    fn escape_closes_the_fork_dialog(cx: &mut TestAppContext) {
        let dir = tempfile::tempdir().unwrap();
        let window = shell_window(dir.path(), cx);
        let source = chat_row("src", None);
        window
            .update(cx, |shell, _, cx| {
                shell.open_fork_dialog(source, "src".into(), None, cx);
                assert!(shell.fork_dialog.is_some());
                assert!(shell.overlay_owns_keyboard(cx));
                assert!(shell.capture_escape_surface(cx));
                assert!(shell.fork_dialog.is_none());
                assert!(!shell.overlay_owns_keyboard(cx));
            })
            .unwrap();
    }
}
