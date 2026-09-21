//! Settings → Devices (feature-inventory §1.5): the device registry — name,
//! platform, last-seen, presence dot, a "This device" badge, click-to-copy id,
//! and a Rename dialog (Mutate renameDevice).

use chrono::{DateTime, Utc};
use gpui::{
    AnyElement, ClipboardItem, Context, Entity, SharedString, Subscription, Task, Window, div,
    prelude::*, px,
};
use std::time::Duration;

use roboco_proto::WorkspaceScope;
use roboco_rpc::methods;

use crate::composer::{ComposerInput, ComposerInputEvent};
use crate::engine_registry::{EngineConnectionState, EngineKey, ScopedId};
use crate::popover;
use crate::settings::widgets;
use crate::state::AppState;
use crate::theme::Theme;
use gpui_tokio::Tokio;

/// A device that pinged within this window shows a presence dot (engines
/// heartbeat every 15s; 70s tolerates a couple of missed beats).
pub const DEVICE_ONLINE_WINDOW_SECS: i64 = 70;

/// Presence: last-seen within the online window (future timestamps count). Pure.
pub fn device_online(last_seen: Option<DateTime<Utc>>, now: DateTime<Utc>) -> bool {
    last_seen
        .is_some_and(|at| now.signed_duration_since(at).num_seconds() <= DEVICE_ONLINE_WINDOW_SECS)
}

/// Corner presence dot of a device row. A row backed by a known engine
/// reports the owning engine's registry connection; any other row keeps the
/// last-seen presence window. Pure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceDot {
    /// Emerald with a soft glow — live.
    Connected,
    /// Amber — the engine dropped and is retrying.
    Reconnecting,
    /// Faint ink — off (or past the last-seen window).
    Off,
}

pub fn presence_dot(connection: Option<&EngineConnectionState>, online: bool) -> PresenceDot {
    match connection {
        Some(EngineConnectionState::Connected) => PresenceDot::Connected,
        Some(EngineConnectionState::Reconnecting) => PresenceDot::Reconnecting,
        Some(EngineConnectionState::Off) => PresenceDot::Off,
        None if online => PresenceDot::Connected,
        None => PresenceDot::Off,
    }
}

/// Compact last-seen line. Pure.
pub fn format_last_seen(last_seen: Option<DateTime<Utc>>, now: DateTime<Utc>) -> String {
    let Some(at) = last_seen else {
        return "never seen".to_string();
    };
    let secs = now.signed_duration_since(at).num_seconds();
    if secs < 60 {
        "just now".to_string()
    } else if secs < 3600 {
        format!("{}m ago", secs / 60)
    } else if secs < 86_400 {
        format!("{}h ago", secs / 3600)
    } else {
        format!("{}d ago", secs / 86_400)
    }
}

/// Scope-aware copy: a local registry describes only the active local
/// workspace and must not imply that account device metadata is already live.
pub fn devices_subtitle(scope: Option<WorkspaceScope>) -> &'static str {
    match scope {
        Some(WorkspaceScope::Local) => "Manage device details stored in this local workspace.",
        Some(WorkspaceScope::Synced) => "Manage device names and inspect synced device metadata.",
        Some(WorkspaceScope::Development) | None => "Manage device names for this workspace.",
    }
}

struct RenameDialog {
    device_id: String,
    input: Entity<ComposerInput>,
    _events: Subscription,
}

pub struct DevicesPage {
    state: Entity<AppState>,
    scroll: widgets::PageScroll,
    rename: Option<RenameDialog>,
    pairing: Entity<ComposerInput>,
    _pairing_events: Subscription,
    pairing_busy: bool,
    /// Device id whose id-chip shows "Copied" right now.
    copied: Option<String>,
    error: Option<SharedString>,
    task: Option<Task<()>>,
    copy_task: Option<Task<()>>,
    _observe: Subscription,
}

impl DevicesPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        let pairing = cx.new(|cx| ComposerInput::new("Paste a pairing URL", cx));
        let pairing_events = cx.subscribe(&pairing, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.pair(cx);
            }
        });
        Self {
            pairing,
            _pairing_events: pairing_events,
            pairing_busy: false,
            state,
            scroll: widgets::PageScroll::default(),
            rename: None,
            copied: None,
            error: None,
            task: None,
            copy_task: None,
            _observe: observe,
        }
    }

    fn pair(&mut self, cx: &mut Context<Self>) {
        if self.pairing_busy {
            return;
        }
        let Some(registry) = self.state.read(cx).registry().cloned() else {
            return;
        };
        let url = self.pairing.read(cx).text().trim().to_string();
        if url.is_empty() {
            return;
        }
        self.pairing_busy = true;
        self.error = None;
        let operation = Tokio::spawn(
            cx,
            async move { registry.pair(&url, "Roboco desktop").await },
        );
        self.task = Some(cx.spawn(async move |this, cx| {
            let result = operation.await;
            this.update(cx, |page, cx| {
                page.pairing_busy = false;
                match result {
                    Ok(Ok(_)) => page
                        .pairing
                        .update(cx, |input, cx| input.set_text(String::new(), cx)),
                    Ok(Err(error)) => page.error = Some(error.to_string().into()),
                    Err(_) => page.error = Some("Pairing was interrupted".into()),
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn forget(&mut self, key: EngineKey, cx: &mut Context<Self>) {
        let Some(registry) = self.state.read(cx).registry().cloned() else {
            return;
        };
        let operation = Tokio::spawn(cx, async move { registry.forget(&key).await });
        cx.spawn(async move |this, cx| {
            let result = operation.await;
            this.update(cx, |page, cx| {
                if !matches!(result, Ok(Ok(()))) {
                    page.error = Some("Could not forget engine".into());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn open_rename(&mut self, device_id: String, current: String, cx: &mut Context<Self>) {
        let input = cx.new(|cx| ComposerInput::new("Device name", cx));
        input.update(cx, |input, cx| input.set_text(current, cx));
        let events = cx.subscribe(&input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.submit_rename(cx);
            }
        });
        self.rename = Some(RenameDialog {
            device_id,
            input,
            _events: events,
        });
        cx.notify();
    }

    fn submit_rename(&mut self, cx: &mut Context<Self>) {
        let Some(dialog) = self.rename.take() else {
            return;
        };
        let name = dialog.input.read(cx).text().trim().to_string();
        if name.is_empty() {
            cx.notify();
            return;
        }
        let Ok(engine) = self.state.read(cx).target_for_id(&dialog.device_id) else {
            return;
        };
        let params = serde_json::json!({
            "op": "renameDevice",
            "deviceId": dialog.device_id,
            "name": name,
        });
        self.task = Some(cx.spawn(async move |this, cx| {
            let result = engine.call(methods::MUTATE, params).await;
            this.update(cx, |page, cx| {
                if let Err(err) = result {
                    page.error = Some(format!("Rename failed: {err}").into());
                }
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn copy_id(&mut self, device_id: String, cx: &mut Context<Self>) {
        cx.write_to_clipboard(ClipboardItem::new_string(
            ScopedId::parse(&device_id)
                .map(|id| id.raw_id)
                .unwrap_or_else(|_| device_id.clone()),
        ));
        self.copied = Some(device_id);
        self.copy_task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(1500))
                .await;
            this.update(cx, |page, cx| {
                page.copied = None;
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn render_rename_dialog(
        &mut self,
        viewport: gpui::Size<gpui::Pixels>,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let theme = Theme::of(cx).clone();
        let dialog = self.rename.as_ref()?;
        let input = dialog.input.clone();
        let card = popover::dialog_card(&theme)
            .child(popover::dialog_title(&theme, "Rename device"))
            .child(
                div()
                    .mt(px(12.0))
                    .child(popover::dialog_field(input.into_any_element())),
            )
            .child(
                div()
                    .mt(px(16.0))
                    .flex()
                    .flex_row()
                    .justify_end()
                    .gap(px(8.0))
                    .child(
                        popover::btn_ghost(&theme, "Cancel", "rename-cancel")
                            .id("rename-cancel")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.rename = None;
                                cx.notify();
                            })),
                    )
                    .child(
                        popover::btn_primary(&theme, "Rename")
                            .id("rename-save")
                            .on_click(cx.listener(|this, _, _, cx| this.submit_rename(cx))),
                    ),
            )
            .into_any_element();
        Some(popover::modal("rename-device-dialog", viewport, card))
    }

    fn on_scroll_hovered(&mut self, hovered: &bool, _: &mut Window, cx: &mut Context<Self>) {
        if self.scroll.set_list_hovered(*hovered) {
            cx.notify();
        }
    }
}

impl popover::ScrollRailHost for DevicesPage {
    fn rail_bar(&mut self) -> &mut popover::MenuScrollbarState {
        self.scroll.rail_bar()
    }

    fn rail_scroll(&self) -> Option<gpui::ScrollHandle> {
        self.scroll.rail_scroll()
    }
}

/// Human platform label (roboco settings.devices.tsx `platformLabel`).
pub fn platform_label(platform: &str) -> &str {
    match platform {
        "macos" | "darwin" => "macOS",
        "linux" => "Linux",
        "windows" => "Windows",
        "web" => "Web",
        "ios" => "iOS",
        "android" => "Android",
        other => other,
    }
}

/// Short device id for the click-to-copy chip (`abcd1234…wxyz`).
pub fn short_id(id: &str) -> String {
    if id.len() > 12 {
        format!("{}…{}", &id[..8], &id[id.len() - 4..])
    } else {
        id.to_string()
    }
}

impl Render for DevicesPage {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = Theme::of(cx).clone();
        let now = Utc::now();
        let (devices, local_id, workspace_scope) = {
            let state = self.state.read(cx);
            (
                state.devices.clone(),
                state.local_device_id.clone(),
                state.workspace_scope,
            )
        };
        let copied = self.copied.clone();
        let dialog = self.render_rename_dialog(window.viewport_size(), cx);
        let emerald = theme.success; // emerald-400
        let count = devices.len();

        let rows: Vec<AnyElement> = devices
            .into_iter()
            .enumerate()
            .map(|(ix, device)| {
                let online = self.state.read(cx).device_online(&device.id, now);
                let engine_key = ScopedId::parse(&device.id).ok().map(|id| id.engine);
                let connection = engine_key
                    .as_ref()
                    .and_then(|key| {
                        self.state
                            .read(cx)
                            .registry_snapshot
                            .engines
                            .iter()
                            .find(|e| &e.key == key)
                    })
                    .map(|e| e.state.clone());
                let forget_key = engine_key.filter(|key| !key.is_local());
                let is_local = local_id.as_deref() == Some(device.id.as_str());
                let id_copied = copied.as_deref() == Some(device.id.as_str());
                let copy_id = device.id.clone();
                let rename_id = device.id.clone();
                let rename_name = device.name.clone();
                let platform_icon = match device.platform.as_str() {
                    "macos" | "darwin" => crate::icons::LAPTOP,
                    "web" => crate::icons::GLOBAL,
                    "ios" | "android" => crate::icons::SMARTPHONE,
                    _ => crate::icons::MONITOR,
                };
                // Presence lives ON the identity tile: a corner dot ringed by
                // the card tone so it "cuts" the tile. Engine-backed rows
                // report the owning engine's connection (emerald glow when
                // connected, amber while reconnecting, faint ink when off);
                // other rows keep the last-seen window — roboco
                // settings.devices.tsx `border-2 border-[var(--card)]` +
                // `shadow-[0_0_6px_rgba(52,211,153,0.55)]`.
                let dot = presence_dot(connection.as_ref(), online);
                let tile = widgets::row_tile(&theme, platform_icon).relative().child({
                    let el = div()
                        .absolute()
                        .bottom(px(-3.0))
                        .right(px(-3.0))
                        .size(px(9.0))
                        .rounded_full()
                        .border_2()
                        .border_color(theme.surface);
                    match dot {
                        PresenceDot::Connected => el.bg(emerald).shadow(vec![gpui::BoxShadow {
                            color: emerald.opacity(0.55),
                            offset: gpui::point(px(0.0), px(0.0)),
                            blur_radius: px(6.0),
                            spread_radius: px(0.0),
                            inset: false,
                        }]),
                        PresenceDot::Reconnecting => el.bg(theme.warning),
                        PresenceDot::Off => el.bg(crate::theme::ink(0.22)),
                    }
                });
                // One quiet meta line: platform · version · (offline: last
                // seen) · id chip.
                let mut meta: Vec<AnyElement> = vec![
                    div()
                        .child(SharedString::from(
                            platform_label(&device.platform).to_string(),
                        ))
                        .into_any_element(),
                ];
                if let Some(version) = device.version.as_deref().filter(|v| !v.is_empty()) {
                    meta.push(
                        div()
                            .child(SharedString::from(format!("v{version}")))
                            .into_any_element(),
                    );
                }
                if let Some(connection) = connection {
                    meta.push(
                        div()
                            .child(match connection {
                                EngineConnectionState::Connected => "Connected",
                                EngineConnectionState::Reconnecting => "Reconnecting",
                                EngineConnectionState::Off => "Off",
                            })
                            .into_any_element(),
                    );
                }
                if !online {
                    meta.push(
                        div()
                            .child(SharedString::from(format!(
                                "Last seen {}",
                                format_last_seen(device.last_seen_at, now)
                            )))
                            .into_any_element(),
                    );
                }
                // "Added {time ago}" — always present (roboco settings.devices.tsx).
                if let Some(created) = device.created_at {
                    meta.push(
                        div()
                            .child(SharedString::from(format!(
                                "Added {}",
                                format_last_seen(Some(created), now)
                            )))
                            .into_any_element(),
                    );
                }
                meta.push(
                    div()
                        .id(("device-id", ix))
                        .font_family(theme.font_mono.clone())
                        .text_size(crate::typography::ui_rems(10.5))
                        .text_color(if id_copied {
                            theme.success_muted.opacity(0.9)
                        } else {
                            theme.text_muted.opacity(0.5)
                        })
                        .cursor_pointer()
                        .hover(|s| s.text_color(theme.text_muted))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.copy_id(copy_id.clone(), cx);
                        }))
                        .child(SharedString::from(if id_copied {
                            "Copied".to_string()
                        } else {
                            short_id(
                                &ScopedId::parse(&device.id)
                                    .map(|id| id.raw_id)
                                    .unwrap_or_else(|_| device.id.clone()),
                            )
                        }))
                        .into_any_element(),
                );

                widgets::card_row(&theme, ix == 0)
                    .child(tile)
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .child(widgets::row_title(&theme, device.name.clone()))
                            .child(widgets::meta_line(&theme, meta)),
                    )
                    .when(is_local, |el| {
                        el.child(widgets::badge(
                            &theme,
                            if workspace_scope == Some(WorkspaceScope::Local)
                                && self.state.read(cx).registry().is_none()
                            {
                                "Local only"
                            } else {
                                "This device"
                            },
                        ))
                    })
                    .when_some(forget_key, |el, key| {
                        el.child(
                            widgets::ghost_action(&theme)
                                .id(("engine-forget", ix))
                                .child("Forget")
                                .on_click(
                                    cx.listener(move |this, _, _, cx| this.forget(key.clone(), cx)),
                                ),
                        )
                    })
                    .child(
                        // `opacity-70 hover:opacity-100` (roboco: also rises on
                        // row hover — gpui has no group-hover, so the button's
                        // own hover carries the reveal).
                        widgets::ghost_action(&theme)
                            .id(("device-rename", ix))
                            .opacity(0.7)
                            .hover(|s| {
                                s.opacity(1.0)
                                    .bg(crate::theme::ink(0.06))
                                    .text_color(theme.text)
                            })
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_rename(rename_id.clone(), rename_name.clone(), cx);
                            }))
                            .child(
                                crate::icons::icon(crate::icons::PEN)
                                    .size(px(14.0))
                                    .text_color(theme.text_muted),
                            )
                            .child(SharedString::from("Rename")),
                    )
                    .into_any_element()
            })
            .collect();

        let card = widgets::section_card(&theme);
        let card = if rows.is_empty() {
            card.child(
                div()
                    .px(px(16.0))
                    .py(px(40.0))
                    .text_center()
                    .text_size(crate::typography::ui_rems(14.0))
                    .text_color(theme.text_muted.opacity(0.6))
                    .child(SharedString::from("No devices registered")),
            )
        } else {
            card.children(rows)
        };

        let scrollbar = popover::rail(self, "devices-page-scrollbar", &theme, cx);
        div()
            .id("devices-page-host")
            .relative()
            .size_full()
            .on_hover(cx.listener(Self::on_scroll_hovered))
            .child(
                div()
                    .id("devices-page")
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll.scroll)
                    .child(
                        widgets::page_column()
                            .child(widgets::page_header(
                                &theme,
                                "Devices",
                                (count > 0).then_some(count),
                            ))
                            .child(widgets::page_subtitle(
                                &theme,
                                if self.state.read(cx).registry().is_some() {
                                    "Connect and manage engines."
                                } else {
                                    devices_subtitle(workspace_scope)
                                },
                            ))
                            .when_some(
                                self.error.clone().or_else(|| {
                                    self.state
                                        .read(cx)
                                        .registry_snapshot
                                        .configuration_error
                                        .clone()
                                        .map(Into::into)
                                }),
                                |el, message| {
                                    el.child(
                                        widgets::error_strip(&theme, message)
                                            .id("devices-error")
                                            .cursor_pointer()
                                            .on_click(cx.listener(|this, _, _, cx| {
                                                this.error = None;
                                                cx.notify();
                                            })),
                                    )
                                },
                            )
                            .child(
                                widgets::section_card(&theme).child(
                                    div()
                                        .px(px(16.0))
                                        .py(px(10.0))
                                        .flex()
                                        .flex_col()
                                        .child(
                                            div()
                                                .flex()
                                                .flex_row()
                                                .items_center()
                                                .gap(px(12.0))
                                                .child(
                                                    div()
                                                        .flex_1()
                                                        .min_w_0()
                                                        .child(popover::dialog_field(
                                                            self.pairing.clone().into_any_element(),
                                                        )),
                                                )
                                                .child(
                                                    popover::btn_primary(
                                                        &theme,
                                                        if self.pairing_busy {
                                                            "Connecting…"
                                                        } else {
                                                            "Connect"
                                                        },
                                                    )
                                                    .id("pair-engine")
                                                    .on_click(cx.listener(
                                                        |this, _, _, cx| this.pair(cx),
                                                    )),
                                                ),
                                        )
                                        .child(
                                            div()
                                                .mt(px(6.0))
                                                .text_size(crate::typography::ui_rems(11.0))
                                                .text_color(theme.text_muted.opacity(0.65))
                                                .child(SharedString::from(
                                                    "Create a pairing link in the engine's Remote access settings, then paste it here.",
                                                )),
                                        ),
                                ),
                            )
                            .child(card),
                    ),
            )
            .children(scrollbar)
            .when_some(dialog, |el, dialog| el.child(dialog))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeDelta;

    #[test]
    fn presence_window() {
        let now = Utc::now();
        assert!(device_online(Some(now - TimeDelta::seconds(10)), now));
        assert!(device_online(Some(now - TimeDelta::seconds(70)), now));
        assert!(!device_online(Some(now - TimeDelta::seconds(71)), now));
        assert!(!device_online(None, now));
        // Clock skew (future) counts as online.
        assert!(device_online(Some(now + TimeDelta::seconds(30)), now));
    }

    #[test]
    fn last_seen_formatting() {
        let now = Utc::now();
        assert_eq!(format_last_seen(None, now), "never seen");
        assert_eq!(
            format_last_seen(Some(now - TimeDelta::seconds(30)), now),
            "just now"
        );
        assert_eq!(
            format_last_seen(Some(now - TimeDelta::minutes(5)), now),
            "5m ago"
        );
        assert_eq!(
            format_last_seen(Some(now - TimeDelta::hours(3)), now),
            "3h ago"
        );
        assert_eq!(
            format_last_seen(Some(now - TimeDelta::days(2)), now),
            "2d ago"
        );
    }

    #[test]
    fn presence_dot_prefers_the_engine_connection_state() {
        use crate::engine_registry::EngineConnectionState::*;
        // Engine-backed rows report the registry connection, whatever the
        // last-seen window says.
        assert_eq!(presence_dot(Some(&Connected), false), PresenceDot::Connected);
        assert_eq!(
            presence_dot(Some(&Reconnecting), false),
            PresenceDot::Reconnecting
        );
        assert_eq!(presence_dot(Some(&Off), true), PresenceDot::Off);
        // Rows with no engine entry keep the last-seen presence window.
        assert_eq!(presence_dot(None, true), PresenceDot::Connected);
        assert_eq!(presence_dot(None, false), PresenceDot::Off);
    }

    #[test]
    fn local_subtitle_does_not_claim_synced_metadata() {
        let copy = devices_subtitle(Some(WorkspaceScope::Local));
        assert!(copy.contains("local workspace"));
        assert!(!copy.contains("synced"));
    }
}
