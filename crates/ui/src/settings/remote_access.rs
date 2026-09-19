//! Settings for this engine's listener and paired clients.
use crate::{settings::widgets, state::AppState, theme::Theme};
use gpui::{
    ClipboardItem, Context, Entity, Render, SharedString, Task, Window, div, prelude::*, px,
};
use roboco_proto::{PairingLink, RemoteAccessSnapshot};
use roboco_rpc::methods;
use serde_json::{Value, json};

pub struct RemoteAccessPage {
    state: Entity<AppState>,
    snapshot: Option<RemoteAccessSnapshot>,
    url: Option<String>,
    error: Option<String>,
    busy: bool,
    task: Option<Task<()>>,
}

impl RemoteAccessPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let mut page = Self {
            state,
            snapshot: None,
            url: None,
            error: None,
            busy: false,
            task: None,
        };
        page.request(methods::GET_REMOTE_ACCESS, json!({}), cx);
        page
    }

    fn request(&mut self, method: &'static str, params: Value, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(engine) = self.state.read(cx).engine().cloned() else {
            return;
        };
        self.busy = true;
        self.error = None;
        self.task = Some(cx.spawn(async move |this, cx| {
            let result = engine
                .client()
                .call(method, params)
                .await
                .map_err(|error| error.to_string());
            let snapshot = if result.is_ok() && method != methods::GET_REMOTE_ACCESS {
                engine
                    .client()
                    .call(methods::GET_REMOTE_ACCESS, json!({}))
                    .await
                    .map_err(|error| error.to_string())
            } else {
                result.clone()
            };
            this.update(cx, |page, cx| {
                page.busy = false;
                match result {
                    Ok(value) if method == methods::CREATE_PAIRING_LINK => {
                        match serde_json::from_value::<PairingLink>(value) {
                            Ok(link) => page.url = Some(link.url),
                            Err(error) => page.error = Some(error.to_string()),
                        }
                    }
                    Err(error) => page.error = Some(error.to_string()),
                    _ => {}
                }
                match snapshot {
                    Ok(value) => match serde_json::from_value::<RemoteAccessSnapshot>(value) {
                        Ok(snapshot) => page.snapshot = Some(snapshot),
                        Err(error) => page.error = Some(error.to_string()),
                    },
                    Err(error) => page.error = Some(error.to_string()),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }
}

impl Render for RemoteAccessPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.global::<Theme>().clone();
        let status = self.snapshot.as_ref().map(|snapshot| &snapshot.status);
        let enabled = status.map(|status| status.enabled).unwrap_or(false);
        let error = self
            .error
            .clone()
            .or_else(|| status.and_then(|status| status.error.clone()));
        let mut page = widgets::page_column()
            .child(widgets::page_header(&theme, "Remote access", None))
            .child(widgets::page_subtitle(&theme, "Pair your other devices with this engine. Use a trusted network or your own tunnel."))
            .child(widgets::section_card(&theme).child(widgets::card_row(&theme, true)
                .child(crate::icons::icon(crate::icons::GLOBE)
                    .size(px(16.0))
                    .text_color(theme.text_muted))
                .child(div().flex_1().child(widgets::row_title(&theme, "Allow remote connections"))
                    .child(widgets::page_subtitle(&theme, if enabled { "Remote clients can connect with a paired session." } else { "Only local clients can connect." })))
                .child(
                    widgets::ghost_action(&theme)
                        .id("remote-refresh")
                        .hover(|s| widgets::ghost_hover(&theme, s))
                        .child(crate::icons::icon(crate::icons::REFRESH)
                            .size(px(14.0))
                            .text_color(theme.text_muted))
                        .child(SharedString::from("Refresh"))
                        .on_click(cx.listener(|page, _, _, cx| {
                            page.request(methods::GET_REMOTE_ACCESS, json!({}), cx)
                        })),
                )
                .child(widgets::toggle_switch(&theme, enabled).id("remote-access-toggle").cursor_pointer()
                    .on_click(cx.listener(move |page, _, _, cx| page.request(methods::SET_REMOTE_ACCESS, json!({"enabled":!enabled}), cx))))));
        if let Some(error) = error {
            page = page.child(widgets::error_strip(&theme, error));
        }
        let url = self.url.clone();
        if enabled || url.is_some() {
            let create = enabled.then(|| {
                widgets::ghost_action(&theme)
                    .id("create-pairing-link")
                    .hover(|s| widgets::ghost_hover(&theme, s))
                    .child(crate::icons::icon(crate::icons::PLUS)
                        .size(px(14.0))
                        .text_color(theme.text_muted))
                    .child(SharedString::from("Create pairing link"))
                    .on_click(cx.listener(|page, _, _, cx| {
                        page.request(methods::CREATE_PAIRING_LINK, json!({}), cx)
                    }))
                    .into_any_element()
            });
            page = page.child(widgets::section_header(&theme, "Pairing link", create));
            if let Some(url) = url {
                let copy = url.clone();
                page = page.child(
                    widgets::section_card(&theme).child(
                        widgets::card_row(&theme, true)
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .child(widgets::url_fragment(&theme, url))
                                    .child(widgets::meta_line(&theme, vec![div()
                                        .child(SharedString::from("Use once within five minutes."))
                                        .into_any_element()])),
                            )
                            .child(
                                widgets::ghost_action(&theme)
                                    .id("copy-pairing-link")
                                    .hover(|s| widgets::ghost_hover(&theme, s))
                                    .child(crate::icons::icon(crate::icons::COPY)
                                        .size(px(14.0))
                                        .text_color(theme.text_muted))
                                    .child(SharedString::from("Copy"))
                                    .on_click(cx.listener(move |_, _, _, cx| {
                                        cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
                                    })),
                            ),
                    ),
                );
            } else {
                page = page.child(
                    widgets::section_card(&theme).child(
                        div()
                            .px(px(16.0))
                            .py(px(10.0))
                            .text_size(crate::typography::ui_rems(12.0))
                            .text_color(theme.text_muted.opacity(0.6))
                            .child(SharedString::from(
                                "No link yet. Create one and paste it on the other device under Settings → Devices.",
                            )),
                    ),
                );
            }
        }
        let rows = self
            .snapshot
            .as_ref()
            .map(|snapshot| snapshot.sessions.clone())
            .unwrap_or_default();
        page = page.child(widgets::section_header(&theme, "Paired sessions", None));
        let mut card = widgets::section_card(&theme);
        if rows.is_empty() {
            card = card.child(
                div()
                    .px(px(16.0))
                    .py(px(10.0))
                    .text_size(crate::typography::ui_rems(12.0))
                    .text_color(theme.text_muted.opacity(0.6))
                    .child(SharedString::from("No clients paired yet.")),
            );
        }
        for (index, row) in rows.into_iter().enumerate() {
            let revoked = row.revoked_at.is_some();
            let id = row.id.clone();
            let label = if row.label.is_empty() {
                "Paired device".to_owned()
            } else {
                row.label
            };
            let seen = chrono::DateTime::from_timestamp_millis(row.last_seen);
            let last_seen = super::devices::format_last_seen(seen, chrono::Utc::now());
            let dot = if revoked {
                crate::theme::ink(0.22)
            } else {
                theme.success.opacity(0.9)
            };
            let meta = if revoked {
                div()
                    .text_color(theme.danger_muted.opacity(0.9))
                    .child(SharedString::from("Revoked"))
                    .into_any_element()
            } else {
                div()
                    .child(SharedString::from(format!("Last seen {last_seen}")))
                    .into_any_element()
            };
            card = card.child(
                widgets::card_row(&theme, false)
                    .child(widgets::status_dot(dot))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(widgets::row_title(&theme, label))
                            .child(widgets::meta_line(&theme, vec![meta])),
                    )
                    .when(!revoked, |row| {
                        row.child(
                            widgets::ghost_action(&theme)
                                .id(("revoke-session", index))
                                .hover(|s| widgets::ghost_hover(&theme, s))
                                .child(SharedString::from("Revoke"))
                                .on_click(cx.listener(move |page, _, _, cx| {
                                    page.request(
                                        methods::REVOKE_PAIRING_SESSION,
                                        json!({"sessionId":id}),
                                        cx,
                                    )
                                })),
                        )
                    }),
            );
        }
        page.child(card)
    }
}
