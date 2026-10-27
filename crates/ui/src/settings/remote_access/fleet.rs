//! The Fleet card on Settings → Remote access (fleet follow): plain
//! per-engine state — In / Not in yet (Retry) — with a per-engine switch,
//! plus the two flows the card keeps: "Show fleet invite" (QR + copy, with
//! the ADR 0008 caption) and "Join a fleet with an invite" (the PC 2 case).
//!
//! The room concept never shows here. The card hides entirely when the local
//! engine lacks `rooms-v2`; remote engines without it read "Update that
//! engine to add it" — never an error.

use gpui::{
    AnyElement, ClipboardItem, Context, Entity, SharedString, Subscription, div, prelude::*, px,
};
use gpui_tokio::Tokio;

use super::{INVITE_QR_SIZE, RemoteAccessPage, qr_tile};
use crate::composer::{ComposerInput, ComposerInputEvent};
use crate::rooms::{FleetInvite, FleetReport, FleetState, FleetSwitchNeeded};
use crate::settings::widgets;
use crate::state::AppState;
use crate::theme::Theme;

/// The secrecy caption under the shown invite (ADR 0008).
pub(super) const FLEET_INVITE_CAPTION: &str = "This invite is a secret: anyone who has it can reach every engine in your fleet. Share it only with your own devices.";

/// The card's explanation line.
pub(super) const FLEET_SUBTITLE: &str = "Engines you pair here join your fleet automatically. A phone that pairs with any of them gets them all.";

/// The card's empty state.
pub(super) const FLEET_EMPTY: &str =
    "No other engines yet. Pair one under Settings → Devices and it joins automatically.";

/// The page-side state of the Fleet card.
pub(super) struct FleetCard {
    pub(super) invite: Option<FleetInvite>,
    pub(super) join: Entity<ComposerInput>,
    _join_events: Subscription,
    pub(super) busy: bool,
    pub(super) message: Option<String>,
    /// A pasted invite waiting for the "leave your current fleet" confirm.
    /// Secret-bearing: held in memory only, never printed.
    pub(super) pending_switch: Option<String>,
    task: Option<gpui::Task<()>>,
}

impl FleetCard {
    pub(super) fn new(cx: &mut Context<RemoteAccessPage>) -> Self {
        let join = cx.new(|cx| ComposerInput::new("Paste a fleet invite (roboco-room:…)", cx));
        let join_events = cx.subscribe(&join, |page: &mut RemoteAccessPage, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                page.join_fleet(false, cx);
            }
        });
        Self {
            invite: None,
            join,
            _join_events: join_events,
            busy: false,
            message: None,
            pending_switch: None,
            task: None,
        }
    }
}

impl RemoteAccessPage {
    /// Mount: run the fleet pass once if nothing has reported yet (the app
    /// open trigger normally beat us to it).
    pub(super) fn prime_fleet(state: &Entity<AppState>, cx: &mut Context<Self>) {
        state.update(cx, |state, cx| {
            if state.fleet_ops().is_some() && state.fleet_report().is_none() {
                state.request_fleet_sync(cx);
            }
        });
    }

    fn retry_fleet(&mut self, cx: &mut Context<Self>) {
        self.state
            .update(cx, |state, cx| state.request_fleet_sync(cx));
    }

    fn set_fleet_opt_out(&mut self, device_id: String, opted_out: bool, cx: &mut Context<Self>) {
        let Some(ops) = self.state.read(cx).fleet_ops() else {
            return;
        };
        match ops.set_opt_out(&device_id, opted_out) {
            Ok(()) => {
                self.fleet.message = None;
                // Show the switch flip at once; the pass confirms it.
                self.state.update(cx, |state, cx| {
                    if let Some(report) = state.fleet.report.as_mut()
                        && let Some(row) = report
                            .rows
                            .iter_mut()
                            .find(|row| row.device_id.as_deref() == Some(device_id.as_str()))
                    {
                        row.state = if opted_out {
                            FleetState::OptedOut
                        } else {
                            FleetState::NotInYet(crate::rooms::NotInYet::Unconfirmed)
                        };
                    }
                    state.request_fleet_sync(cx);
                });
            }
            Err(error) => self.fleet.message = Some(error.to_string()),
        }
        cx.notify();
    }

    fn show_fleet_invite(&mut self, cx: &mut Context<Self>) {
        if self.fleet.busy {
            return;
        }
        let Some(ops) = self.state.read(cx).fleet_ops() else {
            return;
        };
        self.fleet.busy = true;
        self.fleet.message = None;
        let operation = Tokio::spawn(cx, async move { ops.fleet_invite().await });
        self.fleet.task = Some(cx.spawn(async move |this, cx| {
            let result = operation.await;
            this.update(cx, |page, cx| {
                page.fleet.busy = false;
                match result {
                    Ok(Ok(invite)) => page.fleet.invite = Some(invite),
                    Ok(Err(error)) => page.fleet.message = Some(error.to_string()),
                    Err(_) => page.fleet.message = Some("Interrupted".into()),
                }
                // A first invite may have minted the fleet: refresh the rows.
                page.state
                    .update(cx, |state, cx| state.request_fleet_sync(cx));
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn hide_fleet_invite(&mut self, cx: &mut Context<Self>) {
        if let Some(invite) = self.fleet.invite.take() {
            super::evict_qr(invite.qr_svg, cx);
        }
        cx.notify();
    }

    /// Join the pasted fleet. `replace` confirms leaving a fleet this machine
    /// already shares with other engines.
    pub(super) fn join_fleet(&mut self, replace: bool, cx: &mut Context<Self>) {
        if self.fleet.busy {
            return;
        }
        let Some(ops) = self.state.read(cx).fleet_ops() else {
            return;
        };
        let text = if replace {
            self.fleet.pending_switch.take().unwrap_or_default()
        } else {
            self.fleet.join.read(cx).text().trim().to_string()
        };
        if text.is_empty() {
            return;
        }
        self.fleet.busy = true;
        self.fleet.message = None;
        let pasted = text.clone();
        let operation = Tokio::spawn(cx, async move { ops.join(&pasted, replace).await });
        self.fleet.task = Some(cx.spawn(async move |this, cx| {
            let result = operation.await;
            this.update(cx, |page, cx| {
                page.fleet.busy = false;
                match result {
                    Ok(Ok(report)) => {
                        page.fleet.pending_switch = None;
                        page.fleet
                            .join
                            .update(cx, |input, cx| input.set_text(String::new(), cx));
                        page.state.update(cx, |state, cx| {
                            state.fleet.report = Some(report);
                            state.fleet.error = None;
                            cx.notify();
                        });
                    }
                    Ok(Err(error)) => {
                        if error.downcast_ref::<FleetSwitchNeeded>().is_some() {
                            page.fleet.pending_switch = Some(text);
                        }
                        // Fixed text only: decode errors never quote the
                        // paste, store errors never carry the secret.
                        page.fleet.message = Some(error.to_string());
                    }
                    Err(_) => page.fleet.message = Some("Interrupted".into()),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn cancel_fleet_switch(&mut self, cx: &mut Context<Self>) {
        self.fleet.pending_switch = None;
        self.fleet.message = None;
        cx.notify();
    }

    /// The Fleet section, or nothing when the local engine cannot hold one.
    pub(super) fn render_fleet(
        &mut self,
        theme: &Theme,
        cx: &mut Context<Self>,
    ) -> Option<Vec<AnyElement>> {
        let (report, syncing, sync_error) = {
            let state = self.state.read(cx);
            state.fleet_ops()?;
            (
                state.fleet_report().cloned(),
                state.fleet_syncing(),
                state.fleet_error().map(str::to_owned),
            )
        };
        let mut out = Vec::new();
        let action = if self.fleet.invite.is_some() {
            widgets::ghost_action(theme)
                .id("hide-fleet-invite")
                .child(SharedString::from("Hide invite"))
                .on_click(cx.listener(|page, _, _, cx| page.hide_fleet_invite(cx)))
        } else {
            widgets::ghost_action(theme)
                .id("show-fleet-invite")
                .child(SharedString::from(if self.fleet.busy {
                    "Preparing…"
                } else {
                    "Show fleet invite"
                }))
                .on_click(cx.listener(|page, _, _, cx| page.show_fleet_invite(cx)))
        };
        out.push(
            widgets::section_header(theme, "Fleet", Some(action.into_any_element()))
                .into_any_element(),
        );
        let mut card = widgets::section_card(theme)
            .id("fleet-card")
            .debug_selector(|| "fleet-card".into())
            .child(
                div()
                    .px(px(16.0))
                    .py(px(10.0))
                    .text_size(crate::typography::ui_rems(12.0))
                    .text_color(theme.text_muted)
                    .child(SharedString::from(FLEET_SUBTITLE)),
            );
        match &report {
            None => {
                card = card.child(muted_line(
                    theme,
                    if syncing {
                        "Checking your engines…"
                    } else {
                        FLEET_EMPTY
                    },
                ))
            }
            Some(report) if report.rows.is_empty() => {
                card = card.child(muted_line(theme, FLEET_EMPTY))
            }
            Some(report) => card = self.fleet_rows(card, theme, report, cx),
        }
        out.push(card.into_any_element());
        if let Some(message) = self.fleet.message.clone().or(sync_error) {
            out.push(widgets::error_strip(theme, message).into_any_element());
        }
        if let Some(invite) = self.fleet.invite.clone() {
            out.push(fleet_invite_card(theme, invite, cx));
        }
        out.push(self.fleet_join_card(theme, cx));
        Some(out)
    }

    fn fleet_rows(
        &self,
        mut card: gpui::Stateful<gpui::Div>,
        theme: &Theme,
        report: &FleetReport,
        cx: &mut Context<Self>,
    ) -> gpui::Stateful<gpui::Div> {
        for (index, row) in report.rows.iter().enumerate() {
            let dot = if row.state == FleetState::In {
                theme.success.opacity(0.9)
            } else {
                crate::theme::ink(0.22)
            };
            let included = row.state != FleetState::OptedOut;
            let device_id = row.device_id.clone();
            card = card.child(
                widgets::card_row(theme, false)
                    .child(widgets::status_dot(dot))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(widgets::row_title(theme, row.label.clone()))
                            .child(widgets::meta_line(
                                theme,
                                vec![
                                    div()
                                        .child(SharedString::from(row.state.caption()))
                                        .into_any_element(),
                                ],
                            )),
                    )
                    .when(row.state.retryable(), |line| {
                        line.child(
                            widgets::ghost_action(theme)
                                .id(("fleet-retry", index))
                                .debug_selector(move || format!("fleet-retry-{index}"))
                                .child(SharedString::from("Retry"))
                                .on_click(cx.listener(|page, _, _, cx| page.retry_fleet(cx))),
                        )
                    })
                    .when_some(device_id, |line, device_id| {
                        line.child(
                            widgets::toggle_switch(
                                theme,
                                included,
                                SharedString::from(format!("fleet-{index}")),
                            )
                            .id(("fleet-include", index))
                            .debug_selector(move || format!("fleet-include-{index}"))
                            .cursor_pointer()
                            .on_click(cx.listener(
                                move |page, _, _, cx| {
                                    page.set_fleet_opt_out(device_id.clone(), included, cx)
                                },
                            )),
                        )
                    }),
            );
        }
        card
    }

    fn fleet_join_card(&self, theme: &Theme, cx: &mut Context<Self>) -> AnyElement {
        let busy = self.fleet.busy;
        let switching = self.fleet.pending_switch.is_some();
        widgets::section_card(theme)
            .child(
                div()
                    .px(px(16.0))
                    .py(px(10.0))
                    .flex()
                    .flex_col()
                    .child(widgets::row_title(theme, "Join a fleet with an invite"))
                    .child(
                        div()
                            .mt(px(6.0))
                            .flex()
                            .flex_row()
                            .items_center()
                            .gap(px(12.0))
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .child(crate::popover::dialog_field(
                                        self.fleet.join.clone().into_any_element(),
                                    )),
                            )
                            .child(
                                widgets::text_action(
                                    theme,
                                    widgets::ActionTone::Solid,
                                    if busy { "Joining…" } else { "Join" },
                                )
                                .id("join-fleet")
                                .on_click(cx.listener(|page, _, _, cx| page.join_fleet(false, cx))),
                            ),
                    )
                    .when(switching, |column| {
                        column.child(
                            div()
                                .mt(px(8.0))
                                .flex()
                                .flex_row()
                                .gap(px(8.0))
                                .child(
                                    widgets::text_action(
                                        theme,
                                        widgets::ActionTone::Solid,
                                        "Leave it and join",
                                    )
                                    .id("confirm-fleet-switch")
                                    .on_click(
                                        cx.listener(|page, _, _, cx| page.join_fleet(true, cx)),
                                    ),
                                )
                                .child(
                                    widgets::ghost_action(theme)
                                        .id("cancel-fleet-switch")
                                        .child(SharedString::from("Cancel"))
                                        .on_click(
                                            cx.listener(|page, _, _, cx| {
                                                page.cancel_fleet_switch(cx)
                                            }),
                                        ),
                                ),
                        )
                    })
                    .child(
                        div()
                            .mt(px(6.0))
                            .text_size(crate::typography::ui_rems(11.0))
                            .text_color(theme.text_muted)
                            .child(SharedString::from(
                                "Use this on another computer to bring it into the fleet you made here.",
                            )),
                    ),
            )
            .into_any_element()
    }
}

fn muted_line(theme: &Theme, text: &'static str) -> gpui::Div {
    div()
        .px(px(16.0))
        .py(px(10.0))
        .text_size(crate::typography::ui_rems(12.0))
        .text_color(theme.text_muted.opacity(0.6))
        .child(SharedString::from(text))
}

/// The shown invite: QR tile, the invite text, Copy, and the secrecy
/// caption. The invite lives only here and on the clipboard — never a log.
fn fleet_invite_card(
    theme: &Theme,
    invite: FleetInvite,
    cx: &mut Context<RemoteAccessPage>,
) -> AnyElement {
    let copy = invite.encoded.clone();
    widgets::section_card(theme)
        .child(
            widgets::card_row(theme, true)
                .when_some(invite.qr_svg.as_deref(), |row, svg| {
                    row.child(qr_tile("fleet-qr", svg, INVITE_QR_SIZE, false))
                })
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(widgets::url_fragment(theme, invite.encoded))
                        .child(widgets::meta_line(
                            theme,
                            vec![
                                div()
                                    .child(SharedString::from(FLEET_INVITE_CAPTION))
                                    .into_any_element(),
                            ],
                        )),
                )
                .child(
                    widgets::ghost_action(theme)
                        .id("copy-fleet-invite")
                        .child(
                            crate::icons::icon(crate::icons::COPY)
                                .size(px(14.0))
                                .text_color(theme.text_muted),
                        )
                        .child(SharedString::from("Copy"))
                        .on_click(cx.listener(move |_, _, _, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
                        })),
                ),
        )
        .into_any_element()
}
