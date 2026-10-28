//! Settings for this engine's listener and paired clients, plus the Fleet
//! card (fleet follow — `fleet` module).
use crate::{popover, settings::widgets, state::AppState, theme::Theme};
use gpui::{
    AnyElement, ClipboardItem, Context, Entity, Image, ImageFormat, ImageSource, Render,
    SharedString, Task, Window, div, img, prelude::*, px,
};
use roboco_proto::{PairingLink, RemoteAccessSnapshot};
use roboco_rpc::methods;
use serde_json::{Value, json};
use std::sync::Arc;

mod fleet;

/// The pairing QR renders 176 px square (ticket 32) on a white tile, so a
/// version 6–8 code scans from across a desk.
const PAIRING_QR_SIZE: f32 = 176.0;

/// An invite is QR version 16–17 (89–93 modules): 248 px keeps ~2.7 px per module.
const INVITE_QR_SIZE: f32 = 248.0;

/// The `CreateTailcatInvite` reply (`{id, url, expiresAt, qrSvg}`); the page
/// keeps what its card needs. Like a pairing link, the invite is single-use
/// and never logged. `qr_svg` is `None` against engines that predate the
/// invite QR, and the card then shows no tile (the invite and Copy stay).
#[derive(Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct TailcatInviteLink {
    url: String,
    expires_at: i64,
    #[serde(default)]
    qr_svg: Option<String>,
}

pub struct RemoteAccessPage {
    state: Entity<AppState>,
    scroll: widgets::PageScroll,
    snapshot: Option<RemoteAccessSnapshot>,
    /// The minted pairing link; `qr_svg` is `None` against older engines, and
    /// the page then shows no QR (the URL and Copy stay as they are).
    link: Option<PairingLink>,
    /// The minted Tailcat invite, shown with the same QR card.
    invite: Option<TailcatInviteLink>,
    error: Option<String>,
    busy: bool,
    task: Option<Task<()>>,
    /// Wakes the page once the shown link expires, so the QR dims and the
    /// caption swaps without waiting for the next interaction.
    expiry_task: Option<Task<()>>,
    /// The same wake-up for the shown Tailcat invite.
    invite_expiry_task: Option<Task<()>>,
    /// The Fleet card (hidden unless the local engine speaks rooms-v2).
    fleet: fleet::FleetCard,
    _observe: gpui::Subscription,
}

impl RemoteAccessPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        // The Fleet card renders from AppState's fleet report.
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        Self::prime_fleet(&state, cx);
        let fleet = fleet::FleetCard::new(cx);
        let mut page = Self {
            fleet,
            _observe: observe,
            state,
            scroll: widgets::PageScroll::default(),
            snapshot: None,
            link: None,
            invite: None,
            error: None,
            busy: false,
            task: None,
            expiry_task: None,
            invite_expiry_task: None,
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
                            Ok(link) => page.show_link(link, cx),
                            Err(error) => page.error = Some(error.to_string()),
                        }
                    }
                    Ok(value) if method == methods::CREATE_TAILCAT_INVITE => {
                        match serde_json::from_value::<TailcatInviteLink>(value) {
                            Ok(invite) => page.show_invite(invite, cx),
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

    /// Show a freshly minted link, dropping any QR the previous one had
    /// rasterized into the asset cache and scheduling the one wake-up that
    /// dims it the moment it expires.
    fn show_link(&mut self, link: PairingLink, cx: &mut Context<Self>) {
        let expires_at = link.expires_at;
        if let Some(old) = self.link.replace(link) {
            evict_qr(old.qr_svg, cx);
        }
        self.expiry_task = Some(wake_at_expiry(expires_at, cx));
    }

    /// The Tailcat invite's twin of [`Self::show_link`].
    fn show_invite(&mut self, invite: TailcatInviteLink, cx: &mut Context<Self>) {
        let expires_at = invite.expires_at;
        if let Some(old) = self.invite.replace(invite) {
            evict_qr(old.qr_svg, cx);
        }
        self.invite_expiry_task = Some(wake_at_expiry(expires_at, cx));
    }

    fn on_scroll_hovered(&mut self, hovered: &bool, _: &mut Window, cx: &mut Context<Self>) {
        if self.scroll.set_list_hovered(*hovered) {
            cx.notify();
        }
    }
}

impl popover::ScrollRailHost for RemoteAccessPage {
    fn rail_bar(&mut self) -> &mut popover::MenuScrollbarState {
        self.scroll.rail_bar()
    }

    fn rail_scroll(&self) -> Option<gpui::ScrollHandle> {
        self.scroll.rail_scroll()
    }
}

/// Drop a replaced QR from the asset cache it was rasterized into.
fn evict_qr(svg: Option<String>, cx: &mut Context<RemoteAccessPage>) {
    if let Some(svg) = svg {
        let image = Arc::new(Image::from_bytes(ImageFormat::Svg, svg.into_bytes()));
        cx.defer(move |cx| ImageSource::Image(image).evict(None, cx));
    }
}

/// One wake-up just past `expires_at`, so the shown QR dims and its caption
/// swaps without waiting for the next interaction.
fn wake_at_expiry(expires_at: i64, cx: &mut Context<RemoteAccessPage>) -> Task<()> {
    let remaining = (expires_at - chrono::Utc::now().timestamp_millis()).max(0) as u64;
    cx.spawn(async move |this, cx| {
        cx.background_executor()
            .timer(std::time::Duration::from_millis(remaining + 500))
            .await;
        this.update(cx, |_, cx| cx.notify()).ok();
    })
}

/// A shown link is expired the moment its deadline passes: the QR dims so
/// nobody scans a dead code, and the caption says to mint a new one.
fn link_expired(expires_at: i64) -> bool {
    expires_at < chrono::Utc::now().timestamp_millis()
}

/// The caption under the pairing-link meta line.
fn pairing_caption(expired: bool) -> &'static str {
    if expired {
        "Expired. Create a new pairing link."
    } else {
        "Scan with the Roboco app on your phone."
    }
}

/// The caption under the Tailcat invite's meta line.
fn invite_caption(expired: bool) -> &'static str {
    if expired {
        "Expired. Create a new Tailcat invite."
    } else {
        "Scan with the Roboco app on your phone."
    }
}

/// The pairing QR on its white tile: radius 12, padding 10. The white is
/// deliberate, not theming — scanners need dark on light, and the SVG
/// carries its own quiet zone.
fn pairing_qr_tile(svg: &str, expired: bool) -> AnyElement {
    qr_tile("pairing-qr", svg, PAIRING_QR_SIZE, expired)
}

/// The Tailcat invite's QR on the same tile.
fn invite_qr_tile(svg: &str, expired: bool) -> AnyElement {
    qr_tile("tailcat-qr", svg, INVITE_QR_SIZE, expired)
}

pub(super) fn qr_tile(id: &'static str, svg: &str, size: f32, expired: bool) -> AnyElement {
    let image = Arc::new(Image::from_bytes(ImageFormat::Svg, svg.as_bytes().to_vec()));
    div()
        .id(id)
        .debug_selector(move || id.into())
        .flex_none()
        .p(px(10.0))
        .rounded(px(12.0))
        .bg(gpui::white())
        .when(expired, |tile| tile.opacity(0.3))
        .child(img(image).size(px(size)))
        .into_any_element()
}

impl Render for RemoteAccessPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.global::<Theme>().for_settings_surface();
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
                        .child(crate::icons::icon(crate::icons::REFRESH)
                            .size(px(14.0))
                            .text_color(theme.text_muted))
                        .child(SharedString::from("Refresh"))
                        .on_click(cx.listener(|page, _, _, cx| {
                            page.request(methods::GET_REMOTE_ACCESS, json!({}), cx)
                        })),
                )
                .child(widgets::toggle_switch(&theme, enabled, "remote-access")
                    .id("remote-access-toggle").cursor_pointer()
                    .on_click(cx.listener(move |page, _, _, cx| page.request(methods::SET_REMOTE_ACCESS, json!({"enabled":!enabled}), cx))))));
        if let Some(error) = error {
            page = page.child(widgets::error_strip(&theme, error));
        }
        let link = self.link.clone();
        let invite = self.invite.clone();
        if enabled || link.is_some() || invite.is_some() {
            // Both minting actions share the busy-gate in `request`, so a
            // second click while one is in flight is ignored, never stacked.
            let create = enabled.then(|| {
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .child(
                        widgets::ghost_action(&theme)
                            .id("create-pairing-link")
                            .child(
                                crate::icons::icon(crate::icons::PLUS)
                                    .size(px(14.0))
                                    .text_color(theme.text_muted),
                            )
                            .child(SharedString::from("Create pairing link"))
                            .on_click(cx.listener(|page, _, _, cx| {
                                page.request(methods::CREATE_PAIRING_LINK, json!({}), cx)
                            })),
                    )
                    .child(
                        widgets::ghost_action(&theme)
                            .id("create-tailcat-invite")
                            .child(
                                crate::icons::icon(crate::icons::PLUS)
                                    .size(px(14.0))
                                    .text_color(theme.text_muted),
                            )
                            .child(SharedString::from("Create Tailcat invite"))
                            .on_click(cx.listener(|page, _, _, cx| {
                                page.request(
                                    methods::CREATE_TAILCAT_INVITE,
                                    json!({"ttlSeconds": 300}),
                                    cx,
                                )
                            })),
                    )
                    .into_any_element()
            });
            page = page.child(widgets::section_header(&theme, "Pairing link", create));
            if let Some(link) = link {
                let url = link.url.clone();
                let expired = link_expired(link.expires_at);
                let copy = url.clone();
                let has_qr = link.qr_svg.is_some();
                page = page.child(
                    widgets::section_card(&theme).child(
                        widgets::card_row(&theme, true)
                            .when_some(
                                link.qr_svg
                                    .as_deref()
                                    .map(|svg| pairing_qr_tile(svg, expired)),
                                |row, qr| row.child(qr),
                            )
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .child(widgets::url_fragment(&theme, url))
                                    .child(widgets::meta_line(
                                        &theme,
                                        vec![
                                            div()
                                                .child(SharedString::from(
                                                    "Use once within five minutes.",
                                                ))
                                                .into_any_element(),
                                        ],
                                    ))
                                    .when(has_qr, |column| {
                                        column.child(widgets::meta_line(
                                            &theme,
                                            vec![
                                                div()
                                                    .child(SharedString::from(pairing_caption(
                                                        expired,
                                                    )))
                                                    .into_any_element(),
                                            ],
                                        ))
                                    }),
                            )
                            .child(
                                widgets::ghost_action(&theme)
                                    .id("copy-pairing-link")
                                    .child(
                                        crate::icons::icon(crate::icons::COPY)
                                            .size(px(14.0))
                                            .text_color(theme.text_muted),
                                    )
                                    .child(SharedString::from("Copy"))
                                    .on_click(cx.listener(move |_, _, _, cx| {
                                        cx.write_to_clipboard(ClipboardItem::new_string(
                                            copy.clone(),
                                        ))
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
            if let Some(invite) = invite {
                // Same card as the pairing link, QR tile and expiry included;
                // the invite string lives only here and on the clipboard,
                // never in a log.
                let expired = link_expired(invite.expires_at);
                let has_qr = invite.qr_svg.is_some();
                let copy = invite.url.clone();
                page = page.child(
                    widgets::section_card(&theme).child(
                        widgets::card_row(&theme, true)
                            .when_some(
                                invite
                                    .qr_svg
                                    .as_deref()
                                    .map(|svg| invite_qr_tile(svg, expired)),
                                |row, qr| row.child(qr),
                            )
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .child(widgets::url_fragment(&theme, invite.url))
                                    .child(widgets::meta_line(
                                        &theme,
                                        vec![
                                            div()
                                                .child(SharedString::from(
                                                    "Use once within five minutes.",
                                                ))
                                                .into_any_element(),
                                        ],
                                    ))
                                    .when(has_qr, |column| {
                                        column.child(widgets::meta_line(
                                            &theme,
                                            vec![
                                                div()
                                                    .child(SharedString::from(invite_caption(
                                                        expired,
                                                    )))
                                                    .into_any_element(),
                                            ],
                                        ))
                                    }),
                            )
                            .child(
                                widgets::ghost_action(&theme)
                                    .id("copy-tailcat-invite")
                                    .child(
                                        crate::icons::icon(crate::icons::COPY)
                                            .size(px(14.0))
                                            .text_color(theme.text_muted),
                                    )
                                    .child(SharedString::from("Copy"))
                                    .on_click(cx.listener(move |_, _, _, cx| {
                                        cx.write_to_clipboard(ClipboardItem::new_string(
                                            copy.clone(),
                                        ))
                                    })),
                            ),
                    ),
                );
            }
        }
        if let Some(fleet) = self.render_fleet(&theme, cx) {
            page = page.children(fleet);
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
                    .debug_selector(move || format!("remote-session-{index}"))
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
        let scrollbar = popover::rail(self, "remote-access-page-scrollbar", &theme, cx);
        div()
            .id("remote-access-page-host")
            .relative()
            .size_full()
            .on_hover(cx.listener(Self::on_scroll_hovered))
            .child(
                div()
                    .id("remote-access-page")
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll.scroll)
                    .child(page.child(card)),
            )
            .children(scrollbar)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn pairing_caption_swaps_at_expiry() {
        assert_eq!(
            pairing_caption(false),
            "Scan with the Roboco app on your phone."
        );
        assert_eq!(pairing_caption(true), "Expired. Create a new pairing link.");
    }

    #[test]
    fn link_expired_tracks_the_deadline() {
        let now = chrono::Utc::now().timestamp_millis();
        assert!(!link_expired(now + 1), "still live at now+1ms");
        assert!(link_expired(now - 1), "expired at now-1ms");
    }

    /// Drive one request/reply round through the page's fake engine: run the
    /// page's spawned task to its channel send, read the request frame, push
    /// the reply, and let the page settle on it.
    fn reply_to_next_request(
        runtime: &tokio::runtime::Runtime,
        cx: &mut gpui::VisualTestContext,
        requests: &mut tokio::sync::mpsc::Receiver<String>,
        replies: &tokio::sync::mpsc::Sender<String>,
        ok: serde_json::Value,
    ) -> String {
        cx.run_until_parked();
        let frame = requests.try_recv().expect("the page sent a request");
        let request: serde_json::Value = serde_json::from_str(&frame).unwrap();
        let id = request["id"].as_u64().unwrap();
        let method = request["method"].as_str().unwrap().to_owned();
        runtime.block_on(async {
            let reply = serde_json::to_string(&roboco_rpc::ServerFrame {
                id,
                ok: Some(ok),
                ..Default::default()
            })
            .unwrap();
            replies.send(reply).await.unwrap();
            while replies.capacity() < replies.max_capacity() {
                tokio::task::yield_now().await;
            }
        });
        cx.run_until_parked();
        method
    }

    fn draw(cx: &mut gpui::VisualTestContext) {
        cx.update(|window, cx| {
            window.refresh();
            window.draw(cx).clear();
        });
    }

    #[gpui::test]
    fn pairing_link_card_shows_the_engine_qr_and_drops_it_at_expiry_and_on_old_engines(
        cx: &mut gpui::TestAppContext,
    ) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel::<String>(16);
        let (replies, inbound) = tokio::sync::mpsc::channel::<String>(16);
        let state = cx.new(|_| AppState::new());
        state.update(cx, |state, _| {
            state.set_test_engine(crate::state::EngineHandle::from_test_client(
                roboco_rpc::RpcClient::new(out, inbound),
            ));
        });
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let (page, cx) = cx.add_window_view(|_, cx| RemoteAccessPage::new(state, cx));

        // Mount: the page loads the snapshot, so remote access is on.
        let snapshot = json!({
            "status": {"enabled": true, "configuredEnabled": true,
                       "address": "127.0.0.1:27655", "source": "test", "error": null},
            "sessions": []
        });
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone()),
            "GetRemoteAccess"
        );
        draw(cx);
        assert!(
            cx.debug_bounds("pairing-qr").is_none(),
            "no link minted yet"
        );

        // A fresh link carries the engine's QR: the tile sits in the card.
        let fresh_url = "http://127.0.0.1:27655/pair#token=fresh";
        let fresh = json!({
            "url": fresh_url,
            "expiresAt": chrono::Utc::now().timestamp_millis() + 300_000,
            "qrSvg": roboco_proto::qr::qr_svg(fresh_url),
        });
        page.update(cx, |page, cx| {
            page.request(methods::CREATE_PAIRING_LINK, json!({}), cx)
        });
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, fresh),
            "CreatePairingLink"
        );
        // The mint is followed by a snapshot refetch (engine truth).
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone()),
            "GetRemoteAccess"
        );
        draw(cx);
        let qr = cx
            .debug_bounds("pairing-qr")
            .expect("QR tile beside the URL");
        assert_eq!(qr.size.width, px(PAIRING_QR_SIZE + 20.0));
        assert_eq!(qr.size.height, px(PAIRING_QR_SIZE + 20.0));
        page.update(cx, |page, _| {
            let link = page.link.as_ref().expect("link shown");
            assert_eq!(link.url, fresh_url);
            assert!(link.qr_svg.is_some());
            assert!(!link_expired(link.expires_at));
            assert_eq!(
                pairing_caption(link_expired(link.expires_at)),
                "Scan with the Roboco app on your phone."
            );
        });

        // An expired link keeps the tile (dimmed at paint time) and swaps the
        // caption to tell the user to mint a new one.
        let expired_url = "http://127.0.0.1:27655/pair#token=stale";
        let expired = json!({
            "url": expired_url,
            "expiresAt": chrono::Utc::now().timestamp_millis() - 1,
            "qrSvg": roboco_proto::qr::qr_svg(expired_url),
        });
        page.update(cx, |page, cx| {
            page.request(methods::CREATE_PAIRING_LINK, json!({}), cx)
        });
        reply_to_next_request(&runtime, cx, &mut requests, &replies, expired);
        reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone());
        draw(cx);
        assert!(
            cx.debug_bounds("pairing-qr").is_some(),
            "tile stays, dimmed"
        );
        page.update(cx, |page, _| {
            let link = page.link.as_ref().expect("expired link shown");
            assert!(link_expired(link.expires_at));
            assert_eq!(
                pairing_caption(link_expired(link.expires_at)),
                "Expired. Create a new pairing link."
            );
        });

        // An older engine mints no qrSvg: the URL row stays, the QR goes.
        let bare_url = "http://127.0.0.1:27655/pair#token=bare";
        let bare =
            json!({"url": bare_url, "expiresAt": chrono::Utc::now().timestamp_millis() + 300_000});
        page.update(cx, |page, cx| {
            page.request(methods::CREATE_PAIRING_LINK, json!({}), cx)
        });
        reply_to_next_request(&runtime, cx, &mut requests, &replies, bare);
        reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone());
        draw(cx);
        assert!(
            cx.debug_bounds("pairing-qr").is_none(),
            "no QR against an engine without qrSvg"
        );
        page.update(cx, |page, _| {
            let link = page.link.as_ref().expect("link still shown");
            assert_eq!(link.url, bare_url);
            assert!(link.qr_svg.is_none());
        });
    }

    #[test]
    fn invite_caption_swaps_at_expiry() {
        assert_eq!(
            invite_caption(false),
            "Scan with the Roboco app on your phone."
        );
        assert_eq!(
            invite_caption(true),
            "Expired. Create a new Tailcat invite."
        );
    }

    #[gpui::test]
    fn tailcat_invite_card_shows_the_engine_qr_and_drops_it_at_expiry_and_on_old_engines(
        cx: &mut gpui::TestAppContext,
    ) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel::<String>(16);
        let (replies, inbound) = tokio::sync::mpsc::channel::<String>(16);
        let state = cx.new(|_| AppState::new());
        state.update(cx, |state, _| {
            state.set_test_engine(crate::state::EngineHandle::from_test_client(
                roboco_rpc::RpcClient::new(out, inbound),
            ));
        });
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let (page, cx) = cx.add_window_view(|_, cx| RemoteAccessPage::new(state, cx));
        let snapshot = json!({
            "status": {"enabled": true, "configuredEnabled": true,
                       "address": "127.0.0.1:27655", "source": "test", "error": null},
            "sessions": []
        });
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone()),
            "GetRemoteAccess"
        );
        draw(cx);
        assert!(
            cx.debug_bounds("tailcat-qr").is_none(),
            "no invite minted yet"
        );

        // A fresh invite carries the engine's QR: its tile sits in the invite
        // card, and the pairing link's tile stays absent.
        let fresh_url = "roboco-tailcat:fresh";
        let fresh = json!({
            "id": "code-1",
            "url": fresh_url,
            "expiresAt": chrono::Utc::now().timestamp_millis() + 300_000,
            "qrSvg": roboco_proto::qr::qr_svg(fresh_url),
        });
        page.update(cx, |page, cx| {
            page.request(
                methods::CREATE_TAILCAT_INVITE,
                json!({"ttlSeconds": 300}),
                cx,
            )
        });
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, fresh),
            "CreateTailcatInvite"
        );
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone()),
            "GetRemoteAccess"
        );
        draw(cx);
        let qr = cx
            .debug_bounds("tailcat-qr")
            .expect("QR tile beside the invite");
        assert_eq!(qr.size.width, px(INVITE_QR_SIZE + 20.0));
        assert_eq!(qr.size.height, px(INVITE_QR_SIZE + 20.0));
        assert!(
            cx.debug_bounds("pairing-qr").is_none(),
            "no pairing link minted"
        );
        page.update(cx, |page, _| {
            let invite = page.invite.as_ref().expect("invite shown");
            assert_eq!(invite.url, fresh_url);
            assert!(invite.qr_svg.is_some());
            assert_eq!(
                invite_caption(link_expired(invite.expires_at)),
                "Scan with the Roboco app on your phone."
            );
        });

        // An expired invite keeps the tile (dimmed at paint time) and swaps
        // the caption.
        let expired_url = "roboco-tailcat:stale";
        let expired = json!({
            "id": "code-2",
            "url": expired_url,
            "expiresAt": chrono::Utc::now().timestamp_millis() - 1,
            "qrSvg": roboco_proto::qr::qr_svg(expired_url),
        });
        page.update(cx, |page, cx| {
            page.request(
                methods::CREATE_TAILCAT_INVITE,
                json!({"ttlSeconds": 300}),
                cx,
            )
        });
        reply_to_next_request(&runtime, cx, &mut requests, &replies, expired);
        reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone());
        draw(cx);
        assert!(
            cx.debug_bounds("tailcat-qr").is_some(),
            "tile stays, dimmed"
        );
        page.update(cx, |page, _| {
            let invite = page.invite.as_ref().expect("expired invite shown");
            assert_eq!(
                invite_caption(link_expired(invite.expires_at)),
                "Expired. Create a new Tailcat invite."
            );
        });

        // An engine from before the invite QR replies `{id, url, expiresAt}`:
        // the invite and Copy stay, the tile goes.
        let bare_url = "roboco-tailcat:bare";
        let bare = json!({
            "id": "code-3",
            "url": bare_url,
            "expiresAt": chrono::Utc::now().timestamp_millis() + 300_000,
        });
        page.update(cx, |page, cx| {
            page.request(
                methods::CREATE_TAILCAT_INVITE,
                json!({"ttlSeconds": 300}),
                cx,
            )
        });
        reply_to_next_request(&runtime, cx, &mut requests, &replies, bare);
        reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot.clone());
        draw(cx);
        assert!(
            cx.debug_bounds("tailcat-qr").is_none(),
            "no QR against an engine without qrSvg"
        );
        page.update(cx, |page, _| {
            let invite = page.invite.as_ref().expect("invite still shown");
            assert_eq!(invite.url, bare_url);
            assert!(invite.qr_svg.is_none());
        });
    }

    /// The page scrolls like every other settings page (fleet-redesign 01):
    /// with more paired sessions than the window holds, the last row starts
    /// below the window edge and a wheel brings it into view.
    #[gpui::test]
    fn the_page_scrolls_its_last_row_into_view(cx: &mut gpui::TestAppContext) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let (out, mut requests) = tokio::sync::mpsc::channel::<String>(16);
        let (replies, inbound) = tokio::sync::mpsc::channel::<String>(16);
        let state = cx.new(|_| AppState::new());
        state.update(cx, |state, _| {
            state.set_test_engine(crate::state::EngineHandle::from_test_client(
                roboco_rpc::RpcClient::new(out, inbound),
            ));
        });
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let (page, cx) = cx.add_window_view(|_, cx| RemoteAccessPage::new(state, cx));
        cx.simulate_resize(gpui::size(px(900.0), px(500.0)));

        const SESSIONS: usize = 40;
        let now = chrono::Utc::now().timestamp_millis();
        let sessions: Vec<_> = (0..SESSIONS)
            .map(|index| {
                json!({"id": format!("session-{index}"), "label": format!("Phone {index}"),
                       "createdAt": now, "lastSeen": now, "revokedAt": null})
            })
            .collect();
        let snapshot = json!({
            "status": {"enabled": true, "configuredEnabled": true,
                       "address": "127.0.0.1:27655", "source": "test", "error": null},
            "sessions": sessions
        });
        assert_eq!(
            reply_to_next_request(&runtime, cx, &mut requests, &replies, snapshot),
            "GetRemoteAccess"
        );
        draw(cx);
        const LAST: &str = "remote-session-39";
        let window_bottom = px(500.0);
        let before = cx.debug_bounds(LAST).expect("last session row laid out");
        assert!(
            before.bottom() > window_bottom,
            "the content is taller than the window"
        );
        let scroll = page.read_with(cx, |page, _| page.scroll.scroll.clone());
        assert_eq!(scroll.offset().y, px(0.0));

        cx.simulate_event(gpui::ScrollWheelEvent {
            position: gpui::point(px(450.0), px(250.0)),
            delta: gpui::ScrollDelta::Pixels(gpui::point(px(0.0), px(-10_000.0))),
            ..Default::default()
        });
        cx.run_until_parked();
        draw(cx);
        assert!(scroll.offset().y < px(0.0), "the wheel moved the page");
        let after = cx.debug_bounds(LAST).expect("last session row laid out");
        assert!(
            after.top() >= px(0.0) && after.bottom() <= window_bottom,
            "the last row is visible after scrolling: {after:?}"
        );
    }

    /// The Fleet card follows the local engine's rooms-v2 capability: absent
    /// without it (capability hiding — the other page tests above run on an
    /// engine without it and see no extra requests), present with it, and
    /// its rows carry plain state with Retry only where it helps and a
    /// switch only for engines with an identity.
    #[gpui::test]
    fn the_fleet_card_shows_plain_rows_only_on_rooms_v2_engines(cx: &mut gpui::TestAppContext) {
        use crate::rooms::{FleetReport, FleetRow, FleetState, NotInYet};
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        let data_dir = tempfile::tempdir().unwrap();
        let (out, _requests) = tokio::sync::mpsc::channel::<String>(16);
        let (_replies, inbound) = tokio::sync::mpsc::channel::<String>(16);
        let state = cx.new(|_| AppState::new());
        state.update(cx, |state, _| {
            state.set_test_engine(
                crate::state::EngineHandle::from_test_client(roboco_rpc::RpcClient::new(
                    out, inbound,
                ))
                .with_capabilities(&[roboco_proto::capabilities::ROOMS_V2]),
            );
            state.data_dir = Some(data_dir.path().to_path_buf());
            // A report is already in: the card renders it, mount adds no pass.
            state.fleet.report = Some(FleetReport {
                exists: true,
                rows: vec![
                    FleetRow {
                        device_id: Some("dev-b".into()),
                        key: None,
                        label: "server".into(),
                        state: FleetState::In,
                    },
                    FleetRow {
                        device_id: Some("dev-c".into()),
                        key: None,
                        label: "laptop".into(),
                        state: FleetState::NotInYet(NotInYet::Offline),
                    },
                    FleetRow {
                        device_id: None,
                        key: None,
                        label: "elsewhere".into(),
                        state: FleetState::NotInYet(NotInYet::Offline),
                    },
                ],
            });
        });
        cx.update(|cx| {
            gpui_base::init(cx);
            cx.set_global(Theme::dark());
        });
        let (_page, cx) = cx.add_window_view(|_, cx| RemoteAccessPage::new(state.clone(), cx));
        draw(cx);
        assert!(
            cx.debug_bounds("fleet-card").is_some(),
            "rooms-v2: card shown"
        );
        assert!(cx.debug_bounds("fleet-include-0").is_some());
        assert!(cx.debug_bounds("fleet-include-1").is_some());
        assert!(
            cx.debug_bounds("fleet-include-2").is_none(),
            "no switch without an engine identity"
        );
        assert!(cx.debug_bounds("fleet-retry-0").is_none(), "In: no Retry");
        assert!(
            cx.debug_bounds("fleet-retry-1").is_some(),
            "Not in yet: Retry"
        );

        // Without rooms-v2 the card is gone — no error anywhere.
        state.update(cx, |state, _| {
            state.set_test_engine(crate::state::EngineHandle::from_test_client(
                roboco_rpc::RpcClient::new(
                    tokio::sync::mpsc::channel::<String>(16).0,
                    tokio::sync::mpsc::channel::<String>(16).1,
                ),
            ));
        });
        draw(cx);
        assert!(
            cx.debug_bounds("fleet-card").is_none(),
            "old engine: hidden"
        );
    }
}
