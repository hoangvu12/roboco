//! The header above a native child's right-pane transcript.
//!
//! The tab is the existing subagent tab, keyed by the child's stable doc id.
//! This panel adds what only the host knows: task, model, status, attempt, every
//! attempt's outcome, and steer and Stop bound to the exact attempt shown.

use std::collections::HashMap;

use gpui::{
    AnyElement, Context, Entity, IntoElement, ParentElement, Render, SharedString, Styled, Task,
    Window, div, prelude::*, px,
};
use roboco_proto::{NativeChild, NativeChildOutcome, NativeControl};
use roboco_rpc::methods;

use crate::{
    composer::{ComposerInput, ComposerInputEvent},
    native::{self, ChildControlSent, ControlReceipt, ControlVerdict},
    native_dock::send_control,
    settings::widgets::{ActionTone, text_action},
    state::AppState,
    theme::Theme,
};

const PANEL_MAX_HEIGHT: f32 = 260.0;

enum OutcomeLoad {
    Loading(#[allow(dead_code)] Task<()>),
    Ready(Box<NativeChildOutcome>),
    Failed(String),
    Hidden,
}

/// The canonical inventory entry for a header-only child, read for one header.
enum FullChild {
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

impl FullChild {
    fn key(&self) -> Option<&str> {
        match self {
            Self::Idle => None,
            Self::Loading { key } | Self::Ready { key, .. } | Self::Failed { key, .. } => Some(key),
        }
    }
}

pub struct NativeChildPanel {
    /// The owning chat's state: the main conversation's or a side chat's.
    state: Entity<AppState>,
    /// The chat whose conversation delegated this child.
    chat_id: String,
    doc_id: String,
    sent: Option<ChildControlSent>,
    control_task: Option<Task<()>>,
    control_seq: u64,
    full: FullChild,
    full_task: Option<Task<()>>,
    steer_input: Entity<ComposerInput>,
    outcomes: HashMap<(String, u32), OutcomeLoad>,
    _subscriptions: Vec<gpui::Subscription>,
}

impl NativeChildPanel {
    pub fn new(
        state: Entity<AppState>,
        chat_id: String,
        doc_id: String,
        cx: &mut Context<Self>,
    ) -> Self {
        let steer_input = cx.new(|cx| {
            ComposerInput::new("Guide this attempt", cx)
                .with_single_line()
                .with_text_metrics(12.0, 18.0)
        });
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        let edited = cx.subscribe(&steer_input, |_, _, event, cx| {
            if matches!(event, ComposerInputEvent::Edited) {
                cx.notify();
            }
        });
        let submitted = cx.subscribe(&steer_input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.steer(cx);
            }
        });
        Self {
            state,
            chat_id,
            doc_id,
            sent: None,
            control_task: None,
            control_seq: 0,
            full: FullChild::Idle,
            full_task: None,
            steer_input,
            outcomes: HashMap::new(),
            _subscriptions: vec![observe, edited, submitted],
        }
    }

    /// The projected child, only while its owning chat is the one its state
    /// shows. Never another chat's child under the same doc id.
    fn projected(&self, cx: &gpui::App) -> Option<NativeChild> {
        let state = self.state.read(cx);
        if state.selected_chat.as_deref() != Some(self.chat_id.as_str()) {
            return None;
        }
        native::child_by_doc(state.native.as_ref(), &self.doc_id).cloned()
    }

    /// The child as shown: a header-only projection replaced by its canonical
    /// inventory entry once read.
    pub(crate) fn current(&self, cx: &gpui::App) -> Option<(String, NativeChild)> {
        let projected = self.projected(cx)?;
        let inventory: &[NativeChild] = match &self.full {
            FullChild::Ready { key, children }
                if Some(key.as_str())
                    == Some(native::inventory_key(std::slice::from_ref(&projected)).as_str()) =>
            {
                children
            }
            _ => &[],
        };
        let child = native::hydrate_children(std::slice::from_ref(&projected), inventory)
            .pop()
            .unwrap_or(projected);
        Some((self.chat_id.clone(), child))
    }

    /// Read the owning chat's canonical inventory for a header-only child.
    fn fetch_full(&mut self, key: String, cx: &mut Context<Self>) {
        let engine = match self.state.read(cx).target_for_id(&self.chat_id) {
            Ok(engine) => engine,
            Err(error) => {
                self.full = FullChild::Failed {
                    key,
                    message: error.to_string(),
                };
                cx.notify();
                return;
            }
        };
        let params = serde_json::json!({ "chatId": self.chat_id });
        let read_key = key.clone();
        self.full_task = Some(cx.spawn(async move |this, cx| {
            let children = engine
                .call(methods::LIST_NATIVE_CHILDREN, params)
                .await
                .map_err(|error| error.to_string())
                .and_then(|value| {
                    serde_json::from_value::<Vec<NativeChild>>(value)
                        .map_err(|error| error.to_string())
                });
            this.update(cx, |this, cx| {
                if this.full.key() != Some(read_key.as_str()) {
                    return;
                }
                this.full = match children {
                    Ok(children) => FullChild::Ready {
                        key: read_key,
                        children,
                    },
                    Err(message) => FullChild::Failed {
                        key: read_key,
                        message,
                    },
                };
                cx.notify();
            })
            .ok();
        }));
        self.full = FullChild::Loading { key };
        cx.notify();
    }

    /// Queue one control for the exact attempt and show only what the host's
    /// ledger says about it. The draft is cleared only once the host accepted
    /// the guidance. A refusal, a moved attempt or an unknown answer keeps it,
    /// and nothing is ever re-sent to a newer attempt.
    fn send(&mut self, stop: bool, control: NativeControl, attempt: u32, cx: &mut Context<Self>) {
        let Some((chat_id, _)) = self.current(cx) else {
            return;
        };
        if native::receipt_in_flight(self.sent.as_ref().map(|sent| &sent.receipt)) {
            return;
        }
        self.control_seq += 1;
        let seq = self.control_seq;
        self.control_task = Some(send_control(
            self.state.clone(),
            chat_id,
            control,
            cx,
            move |this: &mut Self, receipt, cx| {
                if this.control_seq != seq {
                    return false;
                }
                let accepted_guidance = !stop
                    && matches!(
                        receipt,
                        ControlReceipt::Settled {
                            verdict: ControlVerdict::ChildAccepted,
                            ..
                        }
                    );
                if accepted_guidance {
                    this.steer_input
                        .update(cx, |input, cx| input.set_text("", cx));
                }
                this.sent = Some(ChildControlSent {
                    stop,
                    attempt,
                    receipt: receipt.clone(),
                });
                cx.notify();
                true
            },
        ));
        cx.notify();
    }

    fn steer(&mut self, cx: &mut Context<Self>) {
        let Some((_, child)) = self.current(cx) else {
            return;
        };
        let text = self.steer_input.read(cx).text().trim().to_owned();
        if text.is_empty() || child.status.is_terminal() {
            return;
        }
        let attempt = child.attempt;
        self.send(
            false,
            NativeControl::SteerChild {
                handle: child.handle,
                attempt,
                text,
            },
            attempt,
            cx,
        );
    }

    fn stop(&mut self, cx: &mut Context<Self>) {
        let Some((_, child)) = self.current(cx) else {
            return;
        };
        if child.status.is_terminal() {
            return;
        }
        let attempt = child.attempt;
        self.send(
            true,
            NativeControl::StopChild {
                handle: child.handle,
                attempt,
            },
            attempt,
            cx,
        );
    }

    fn toggle_outcome(&mut self, handle: String, attempt: u32, cx: &mut Context<Self>) {
        let key = (handle.clone(), attempt);
        match self.outcomes.get(&key) {
            Some(OutcomeLoad::Ready(_)) => {
                self.outcomes.insert(key, OutcomeLoad::Hidden);
                cx.notify();
                return;
            }
            Some(OutcomeLoad::Loading(_)) => return,
            _ => {}
        }
        let Some((chat_id, _)) = self.current(cx) else {
            return;
        };
        let Ok(engine) = self.state.read(cx).target_for_id(&chat_id) else {
            return;
        };
        let params = serde_json::json!({ "chatId": chat_id, "handle": handle, "attempt": attempt });
        let task_key = key.clone();
        let task = cx.spawn(async move |this, cx| {
            let result = engine.call(methods::GET_NATIVE_CHILD_OUTCOME, params).await;
            this.update(cx, |this, cx| {
                let next = match result {
                    Ok(value) => match serde_json::from_value::<NativeChildOutcome>(value) {
                        Ok(outcome) => OutcomeLoad::Ready(Box::new(outcome)),
                        Err(err) => OutcomeLoad::Failed(err.to_string()),
                    },
                    Err(err) => OutcomeLoad::Failed(err.to_string()),
                };
                this.outcomes.insert(task_key, next);
                cx.notify();
            })
            .ok();
        });
        self.outcomes.insert(key, OutcomeLoad::Loading(task));
        cx.notify();
    }

    fn button(
        theme: &Theme,
        id: impl Into<SharedString>,
        label: &'static str,
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

    fn render_outcome(&self, theme: &Theme, handle: &str, attempt: u32) -> Option<AnyElement> {
        let (title, lines): (&str, Vec<String>) = match self
            .outcomes
            .get(&(handle.to_owned(), attempt))?
        {
            OutcomeLoad::Loading(_) => ("Loading outcome…", Vec::new()),
            OutcomeLoad::Hidden => return None,
            OutcomeLoad::Failed(message) => ("Could not read this outcome.", vec![message.clone()]),
            OutcomeLoad::Ready(outcome) => {
                let mut lines = Vec::new();
                if let Some(result) = &outcome.result {
                    lines.push(result.clone());
                }
                if let Some(error) = &outcome.error {
                    lines.push(format!("Error: {error}"));
                }
                if !outcome.changed_files.is_empty() {
                    lines.push(format!(
                        "Changed files: {}",
                        outcome.changed_files.join(", ")
                    ));
                }
                lines.push(native::usage_line(&outcome.usage));
                (native::child_status_label(outcome.status), lines)
            }
        };
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(3.0))
                .pl(px(8.0))
                .text_color(theme.text_muted)
                .child(SharedString::from(title))
                .children(
                    lines
                        .into_iter()
                        .map(|line| div().child(SharedString::from(line))),
                )
                .into_any_element(),
        )
    }
}

impl Render for NativeChildPanel {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).clone();
        if let Some(projected) = self.projected(cx).filter(|child| child.oversized) {
            let key = native::inventory_key(std::slice::from_ref(&projected));
            if self.full.key() != Some(key.as_str()) {
                self.fetch_full(key, cx);
            }
        }
        let Some((_, child)) = self.current(cx) else {
            return div().into_any_element();
        };
        let terminal = child.status.is_terminal();
        let title = native::child_title(&child).to_owned();
        let title = if title.is_empty() {
            child.handle.clone()
        } else {
            title
        };
        let mut facts = vec![
            native::child_status_label(child.status).to_owned(),
            format!("attempt {}", child.attempt),
        ];
        if let Some(model) = child.model.as_deref().filter(|model| !model.is_empty()) {
            facts.push(model.to_owned());
        }
        if !child.profile.is_empty() {
            facts.push(child.profile.clone());
        }
        let mut panel = div()
            .id("native-child-panel")
            .w_full()
            .flex_none()
            .max_h(px(PANEL_MAX_HEIGHT))
            .overflow_y_scroll()
            .flex()
            .flex_col()
            .gap(px(6.0))
            .px(px(12.0))
            .py(px(8.0))
            .border_b_1()
            .border_color(theme.border)
            .bg(theme.bg)
            .text_size(px(12.0))
            .text_color(theme.text)
            .child(div().child(SharedString::from(title)))
            .child(
                div()
                    .text_color(theme.text_muted)
                    .child(SharedString::from(facts.join(" · "))),
            );
        if child.oversized {
            let (text, failed) = match &self.full {
                FullChild::Failed { message, .. } => (
                    format!(
                        "Mimir sent only this agent's header, and its full record could not be read. {message}"
                    ),
                    true,
                ),
                _ => (
                    "Reading this agent's full record from Mimir.".to_owned(),
                    false,
                ),
            };
            let mut row = div()
                .id("native-child-full")
                .flex()
                .flex_row()
                .flex_wrap()
                .items_center()
                .gap(px(8.0))
                .child(
                    div()
                        .text_color(if failed {
                            theme.danger
                        } else {
                            theme.text_muted
                        })
                        .child(SharedString::from(text)),
                );
            if let FullChild::Failed { key, .. } = &self.full {
                let key = key.clone();
                row = row.child(Self::button(
                    &theme,
                    "native-child-full-retry",
                    "Try again",
                    ActionTone::Outlined,
                    true,
                    move |this, cx| this.fetch_full(key.clone(), cx),
                    cx,
                ));
            }
            panel = panel.child(row);
        }
        if let Some(label) = native::child_control_label(self.sent.as_ref(), &child) {
            let color = match label.tone {
                native::ReceiptTone::Progress => theme.text_muted,
                native::ReceiptTone::Ok => theme.text,
                native::ReceiptTone::Warn => theme.warning,
                native::ReceiptTone::Danger => theme.danger,
            };
            panel = panel.child(
                div()
                    .id("native-child-feedback")
                    .text_color(color)
                    .child(SharedString::from(label.text)),
            );
        }
        if !terminal {
            let has_text = !self.steer_input.read(cx).text().trim().is_empty();
            panel = panel.child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap(px(8.0))
                    .child(
                        div()
                            .min_w_0()
                            .flex_1()
                            .child(crate::surface_chrome::input().child(self.steer_input.clone())),
                    )
                    .child(Self::button(
                        &theme,
                        "native-child-steer",
                        "Send guidance",
                        ActionTone::Outlined,
                        has_text,
                        |this, cx| this.steer(cx),
                        cx,
                    ))
                    .child(Self::button(
                        &theme,
                        "native-child-stop",
                        "Stop",
                        ActionTone::Outlined,
                        true,
                        |this, cx| this.stop(cx),
                        cx,
                    )),
            );
        }
        for attempt in child.attempts.iter().rev() {
            let current = attempt.attempt == child.attempt;
            let settled = attempt.status.is_terminal();
            let handle = child.handle.clone();
            let number = attempt.attempt;
            let shown = matches!(
                self.outcomes.get(&(handle.clone(), number)),
                Some(OutcomeLoad::Ready(_))
            );
            let mut row =
                div().flex().flex_row().items_center().gap(px(8.0)).child(
                    div().min_w_0().flex_1().text_color(theme.text_muted).child(
                        SharedString::from(format!(
                            "Attempt {number}{} · {}",
                            if current { " (current)" } else { "" },
                            native::child_status_label(attempt.status)
                        )),
                    ),
                );
            if settled {
                let toggle_handle = handle.clone();
                row = row.child(Self::button(
                    &theme,
                    format!("native-child-outcome-{number}"),
                    if shown {
                        "Hide outcome"
                    } else {
                        "Show outcome"
                    },
                    ActionTone::Quiet,
                    true,
                    move |this, cx| this.toggle_outcome(toggle_handle.clone(), number, cx),
                    cx,
                ));
            }
            panel = panel.child(row);
            if let Some(outcome) = self.render_outcome(&theme, &handle, number) {
                panel = panel.child(outcome);
            }
        }
        panel.into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use gpui::TestAppContext;
    use roboco_proto::{NativeChatState, NativeChildStatus, NativeLink};

    use super::*;
    use crate::native_dock::scripted::{self, only, pump};

    fn header() -> NativeChild {
        NativeChild {
            handle: "agent-1".into(),
            attempt: 2,
            profile: String::new(),
            description: String::new(),
            model: None,
            status: NativeChildStatus::Running,
            background: true,
            spawned_by: None,
            completion_pending: false,
            presentation: None,
            doc_id: "chat--sub--agent-1".into(),
            oversized: true,
            attempts: Vec::new(),
        }
    }

    /// The view sent only this child's header. The pane reads the owning chat's
    /// canonical inventory for its task and attempts, and a failed read is
    /// shown with a retry instead of an empty header.
    #[gpui::test]
    fn a_header_only_child_pane_reads_its_owning_chats_inventory(cx: &mut TestAppContext) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        cx.update(|cx| cx.set_global(Theme::dark()));
        let state = cx.new(|_| AppState::new());
        let mut wire = scripted::attach(&state, cx);
        state.update(cx, |state, _| {
            state.selected_chat = Some("chat".into());
            state.native = Some(NativeChatState {
                link: NativeLink::Attached,
                children: vec![header()],
                ..Default::default()
            });
            state.native_projected = true;
        });
        let window = cx.add_window(|_, cx| {
            NativeChildPanel::new(
                state.clone(),
                "chat".into(),
                "chat--sub--agent-1".into(),
                cx,
            )
        });
        pump(&runtime, cx);
        let first = only(&wire.drain(), methods::LIST_NATIVE_CHILDREN).clone();
        assert_eq!(first.params["chatId"], "chat");
        wire.fail(&first, "not attached");
        pump(&runtime, cx);
        window
            .update(cx, |panel, _, _| {
                assert!(matches!(&panel.full, FullChild::Failed { message, .. } if message.contains("not attached")));
            })
            .unwrap();
        window
            .update(cx, |panel, _, cx| {
                let key = panel.full.key().unwrap().to_owned();
                panel.fetch_full(key, cx);
            })
            .unwrap();
        pump(&runtime, cx);
        let retry = only(&wire.drain(), methods::LIST_NATIVE_CHILDREN).clone();
        let mut full = header();
        full.oversized = false;
        full.description = "Map the auth module".into();
        full.attempts = vec![roboco_proto::NativeChildAttempt {
            attempt: 1,
            status: NativeChildStatus::Failed,
            presentation: None,
            outcome_ref: Some("chat/agent-1.1.outcome".into()),
        }];
        wire.answer(&retry, serde_json::to_value(vec![full.clone()]).unwrap());
        pump(&runtime, cx);
        window
            .update(cx, |panel, _, cx| {
                let (chat, child) = panel.current(cx).unwrap();
                assert_eq!(chat, "chat");
                assert_eq!(child, full);
            })
            .unwrap();
        state.update(cx, |state, _| state.selected_chat = Some("other".into()));
        window
            .update(cx, |panel, _, cx| {
                assert!(
                    panel.current(cx).is_none(),
                    "another chat's state never shows this child"
                );
            })
            .unwrap();
    }
}
