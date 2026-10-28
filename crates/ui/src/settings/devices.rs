//! Settings → Devices (feature-inventory §1.5): the device registry — name,
//! platform, last-seen, presence dot, a "This device" badge, click-to-copy id,
//! and a Rename dialog (Mutate renameDevice).

use chrono::{DateTime, Utc};
use gpui::{
    AnyElement, ClipboardItem, Context, Entity, SharedString, Subscription, Task, Window, div,
    prelude::*, px,
};
use std::time::Duration;

use roboco_proto::{Device, WorkspaceScope};
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
        Some(WorkspaceScope::Local) => "Devices in this local workspace.",
        Some(WorkspaceScope::Synced) => "Devices synced to this workspace.",
        Some(WorkspaceScope::Development) | None => "Devices in this workspace.",
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
    /// Re-renders when an engine's Tailcat path label changes (the adapter
    /// re-measures at most every minute; nothing else notifies for it).
    _paths: Task<()>,
}

impl DevicesPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        let paths = cx.spawn(async move |this, cx| {
            let mut shown = Vec::new();
            loop {
                cx.background_executor().timer(Duration::from_secs(5)).await;
                let Ok(now) = this.update(cx, |this, cx| link_paths(this.state.read(cx))) else {
                    return;
                };
                if now != shown {
                    shown = now;
                    if this.update(cx, |_, cx| cx.notify()).is_err() {
                        return;
                    }
                }
            }
        });
        let pairing =
            cx.new(|cx| ComposerInput::new("Paste a pairing URL or roboco-tailcat:… invite", cx));
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
            _paths: paths,
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

    pub(crate) fn open_rename(
        &mut self,
        device_id: String,
        current: String,
        cx: &mut Context<Self>,
    ) {
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

    /// Escape that reached Settings unclaimed closes the rename dialog first,
    /// so it never closes Settings under the dialog. Returns whether it did.
    pub(crate) fn dismiss_on_escape(&mut self, cx: &mut Context<Self>) -> bool {
        if self.rename.take().is_none() {
            return false;
        }
        cx.notify();
        true
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
        let theme = Theme::of(cx).for_popup();
        let dialog = self.rename.as_ref()?;
        let input = dialog.input.clone();
        let card = popover::dialog_card(&theme)
            .id("rename-device-card")
            .role(gpui::Role::Dialog)
            .aria_label("Rename device")
            .on_key_down(cx.listener(|this, event: &gpui::KeyDownEvent, _, cx| {
                if event.keystroke.key == "escape" {
                    this.rename = None;
                    cx.notify();
                    cx.stop_propagation();
                }
            }))
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
                        widgets::text_action(&theme, widgets::ActionTone::Quiet, "Cancel")
                            .id("rename-cancel")
                            .tab_index(0)
                            .role(gpui::Role::Button)
                            .focus_visible(|s| s.border_2().border_color(theme.accent).opacity(1.0))
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.rename = None;
                                cx.notify();
                            })),
                    )
                    .child(
                        widgets::text_action(&theme, widgets::ActionTone::Solid, "Rename")
                            .id("rename-save")
                            .tab_index(0)
                            .role(gpui::Role::Button)
                            .focus_visible(|s| s.border_2().border_color(theme.accent).opacity(1.0))
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

/// A connected remote engine's Tailcat path, as its row shows it: "Direct ·
/// 35 ms" or "Via relay (Tokyo) · 160 ms" (fleet-redesign 16). `None` for
/// the local engine, LAN engines, and until the adapter has measured it.
fn link_path_label(state: &AppState, key: &EngineKey) -> Option<String> {
    if key.is_local() {
        return None;
    }
    let dial = state.registry()?.relay_dial(key)?;
    crate::tailcat_pairing::link_path(&dial.endpoint).map(|path| path.label())
}

/// Every connected remote engine's path label (the refresh compares them).
fn link_paths(state: &AppState) -> Vec<(EngineKey, Option<String>)> {
    state
        .registry_snapshot
        .engines
        .iter()
        .filter(|engine| engine.state == EngineConnectionState::Connected)
        .map(|engine| (engine.key.clone(), link_path_label(state, &engine.key)))
        .collect()
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
        crate::toast::take_error(&mut self.error, cx);
        let theme = Theme::of(cx).for_settings_surface();
        let now = Utc::now();
        let (devices, local_id, workspace_scope, can_pair) = {
            let state = self.state.read(cx);
            (
                state.devices.clone(),
                state.local_device_id.clone(),
                state.workspace_scope,
                state.registry().is_some(),
            )
        };
        let copied = self.copied.clone();
        let dialog = self.render_rename_dialog(window.viewport_size(), cx);
        // Split into this device and the rest; each renders as rows in one
        // block, like every other settings page.
        let (local, others): (Vec<_>, Vec<_>) = devices
            .into_iter()
            .enumerate()
            .partition(|(_, device)| local_id.as_deref() == Some(device.id.as_str()));
        // The engine's own scope is always Local here, yet paired engines are
        // other devices: list them whenever there are any, and keep the
        // pairing empty state wherever this client can pair.
        let show_others =
            !others.is_empty() || can_pair || workspace_scope != Some(WorkspaceScope::Local);
        let device_row = |ix: usize, device: Device, first: bool, cx: &mut Context<Self>| {
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
            let forget_key = engine_key.clone().filter(|key| !key.is_local());
            let is_local = local_id.as_deref() == Some(device.id.as_str());
            let id_copied = copied.as_deref() == Some(device.id.as_str());
            let copy_id = device.id.clone();
            let forget_selector = format!("engine-forget-{}", device.id);
            let rename_selector = format!("device-rename-{}", device.id);
            let rename_id = device.id.clone();
            let rename_name = device.name.clone();
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
            // Presence only says something about other devices; engine-backed
            // rows report the owning engine's connection instead.
            if is_local {
                if let Some(connection) = connection {
                    meta.push(
                        div()
                            .child(SharedString::from(match connection {
                                EngineConnectionState::Connected => "Connected",
                                EngineConnectionState::Reconnecting => "Reconnecting",
                                EngineConnectionState::Off => "Off",
                            }))
                            .into_any_element(),
                    );
                }
            } else if let Some(connection) = connection {
                let connected = connection == EngineConnectionState::Connected;
                meta.push(
                    div()
                        .text_color(if connected {
                            theme.success_muted
                        } else {
                            theme.text_muted
                        })
                        .child(SharedString::from(match connection {
                            EngineConnectionState::Connected => "Online",
                            EngineConnectionState::Reconnecting => "Reconnecting",
                            EngineConnectionState::Off => "Off",
                        }))
                        .into_any_element(),
                );
                if let Some(label) = engine_key
                    .as_ref()
                    .filter(|_| connected)
                    .and_then(|key| link_path_label(self.state.read(cx), key))
                {
                    meta.push(div().child(SharedString::from(label)).into_any_element());
                }
            } else if online {
                meta.push(
                    div()
                        .text_color(theme.success_muted)
                        .child(SharedString::from("Online"))
                        .into_any_element(),
                );
            } else {
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
            widgets::card_row(&theme, first)
                .child(
                    div()
                        .flex_1()
                        .min_w(px(160.0))
                        .child(widgets::row_title(&theme, device.name.clone()))
                        .child(widgets::meta_line(&theme, meta)),
                )
                .child(
                    div()
                        .flex_none()
                        .flex()
                        .items_center()
                        .gap(px(4.0))
                        .child(
                            widgets::text_action(
                                &theme,
                                widgets::ActionTone::Quiet,
                                if id_copied { "Copied" } else { "Copy ID" },
                            )
                            .id(("device-id", ix))
                            .tab_index(0)
                            .role(gpui::Role::Button)
                            .aria_label(format!("Copy device ID {}", device.id))
                            .focus_visible(|s| s.border_2().border_color(theme.accent))
                            .on_click(cx.listener(
                                move |this, _, _, cx| {
                                    this.copy_id(copy_id.clone(), cx);
                                },
                            )),
                        )
                        .when_some(forget_key, |actions, key| {
                            actions.child(
                                widgets::text_action(&theme, widgets::ActionTone::Quiet, "Forget")
                                    .id(("engine-forget", ix))
                                    .debug_selector(move || forget_selector)
                                    .text_color(theme.danger)
                                    .tab_index(0)
                                    .role(gpui::Role::Button)
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.forget(key.clone(), cx)
                                    })),
                            )
                        })
                        .child(
                            widgets::text_action(&theme, widgets::ActionTone::Filled, "Rename")
                                .id(("device-rename", ix))
                                .debug_selector(move || rename_selector)
                                .tab_index(0)
                                .role(gpui::Role::Button)
                                .aria_label(format!("Rename {}", device.name))
                                .focus_visible(|s| s.border_2().border_color(theme.accent))
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.open_rename(rename_id.clone(), rename_name.clone(), cx);
                                })),
                        ),
                )
                .into_any_element()
        };
        let local_block = (!local.is_empty()).then(|| {
            let mut block = widgets::section_card(&theme).mt(px(8.0));
            for (n, (ix, device)) in local.into_iter().enumerate() {
                block = block.child(device_row(ix, device, n == 0, cx));
            }
            block
        });
        let others_block = if others.is_empty() {
            widgets::section_card(&theme).mt(px(8.0)).child(
                widgets::card_row(&theme, true).child(
                    div()
                        .text_size(crate::typography::ui_rems(13.0))
                        .text_color(theme.text_muted)
                        .child(SharedString::from("Pair another device to see it here.")),
                ),
            )
        } else {
            let mut block = widgets::section_card(&theme).mt(px(8.0));
            for (n, (ix, device)) in others.into_iter().enumerate() {
                block = block.child(device_row(ix, device, n == 0, cx));
            }
            block
        };
        let card = div()
            .flex()
            .flex_col()
            .when_some(local_block, |el, block| {
                el.child(widgets::section_label(&theme, "This device").mt(px(28.0)))
                    .child(block)
            })
            .when(show_others, |el| {
                el.child(
                    widgets::section_label(&theme, "Other devices")
                        .mt(px(28.0))
                        .debug_selector(|| "devices-others".into()),
                )
                .child(others_block)
            });

        let scrollbar = popover::rail(self, "devices-page-scrollbar", &theme, cx);
        div()
            .id("devices-page-host")
            .relative()
            .size_full()
            .on_hover(cx.listener(Self::on_scroll_hovered))
            .child(
                crate::edge_fade::edge_faded(
                    16.0,
                    true,
                    true,
                    div()
                        .id("devices-page")
                        .size_full()
                        .overflow_y_scroll()
                        .track_scroll(&self.scroll.scroll)
                        .child(
                            widgets::page_column()
                                .child(widgets::page_header(&theme, "Devices", None))
                                .child(widgets::page_subtitle(
                                    &theme,
                                    if self.state.read(cx).registry().is_some() {
                                        "Connect and manage engines."
                                    } else {
                                        devices_subtitle(workspace_scope)
                                    },
                                ))
                                .when_some(
                                    self.state
                                        .read(cx)
                                        .registry_snapshot
                                        .configuration_error
                                        .clone()
                                        .map(SharedString::from),
                                    |el, message| {
                                        el.child(
                                            widgets::error_strip(&theme, message)
                                                .id("devices-error"),
                                        )
                                    },
                                )
                                .child(
                                    widgets::section(
                                        &theme,
                                        "Engines",
                                        widgets::section_card(&theme).mt_0().child(
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
                                                    widgets::text_action(
                                                        &theme,
                                                        widgets::ActionTone::Solid,
                                                        if self.pairing_busy {
                                                            "Connecting…"
                                                        } else {
                                                            "Connect"
                                                        },
                                                    )
                                                    .id("pair-engine")
                                                    .tab_index(0)
                                                    .role(gpui::Role::Button)
                                                    .focus_visible(|s| {
                                                        s.border_2().border_color(theme.accent).opacity(1.0)
                                                    })
                                                    .on_click(cx.listener(
                                                        |this, _, _, cx| this.pair(cx),
                                                    )),
                                                ),
                                        )
                                        .child(
                                            div()
                                                .mt(px(6.0))
                                                .text_size(crate::typography::ui_rems(11.0))
                                                .text_color(theme.text_muted)
                                                .child(SharedString::from(
                                                    "Create a pairing link or roboco-tailcat:… invite in the engine's Remote access settings, then paste it here.",
                                                )),
                                        ),
                                        ),
                                    )
                                    .mt(px(28.0)),
                                )
                                .child(card),
                        ),
                )
                .fade_overflow_y(&self.scroll.scroll),
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
        assert_eq!(
            presence_dot(Some(&Connected), false),
            PresenceDot::Connected
        );
        assert_eq!(
            presence_dot(Some(&Reconnecting), false),
            PresenceDot::Reconnecting
        );
        assert_eq!(presence_dot(Some(&Off), true), PresenceDot::Off);
        // Rows with no engine entry keep the last-seen presence window.
        assert_eq!(presence_dot(None, true), PresenceDot::Connected);
        assert_eq!(presence_dot(None, false), PresenceDot::Off);
    }

    /// A paired engine lists under "Other devices" with its Forget and
    /// Rename actions, although the local engine's scope is always Local;
    /// Forget really unpairs it.
    #[gpui::test]
    fn paired_engines_list_under_other_devices_with_forget(cx: &mut gpui::TestAppContext) {
        let fixture = crate::paired_fixture::PairedEngines::start(cx);
        let remote = fixture.remote_device();
        let local = fixture.local_device(cx);
        fixture.wait_for_state(cx, "the paired engine's device row", |state| {
            state.devices.iter().any(|device| device.id == remote)
        });
        assert_eq!(
            fixture.state.read_with(cx, |s, _| s.workspace_scope),
            Some(WorkspaceScope::Local)
        );
        let selector = |name: &str, id: &str| -> &'static str {
            Box::leak(format!("{name}-{id}").into_boxed_str())
        };
        let state = fixture.state.clone();
        let (_page, vcx) = cx.add_window_view(|_, cx| DevicesPage::new(state, cx));
        let draw = |vcx: &mut gpui::VisualTestContext| {
            vcx.update(|window, cx| {
                window.refresh();
                window.draw(cx).clear();
            })
        };
        draw(vcx);
        assert!(vcx.debug_bounds("devices-others").is_some());
        let forget = vcx
            .debug_bounds(selector("engine-forget", &remote))
            .expect("the paired engine's Forget");
        assert!(
            vcx.debug_bounds(selector("device-rename", &remote))
                .is_some()
        );
        assert!(
            vcx.debug_bounds(selector("engine-forget", &local)).is_none(),
            "the local engine cannot be forgotten"
        );
        vcx.simulate_click(forget.center(), gpui::Modifiers::none());
        let registry = fixture.registry.clone();
        crate::paired_fixture::wait_until(vcx, "the engine to be forgotten", |cx| {
            registry.snapshot().engines.len() == 1
                && fixture
                    .state
                    .read_with(cx, |s, _| s.devices.iter().all(|d| d.id != remote))
        });
        draw(vcx);
        assert!(
            vcx.debug_bounds(selector("engine-forget", &remote))
                .is_none()
        );
        // The pairing empty state stays: this client can pair again.
        assert!(vcx.debug_bounds("devices-others").is_some());
        fixture.shutdown(vcx);
    }

    #[test]
    fn local_subtitle_does_not_claim_synced_metadata() {
        let copy = devices_subtitle(Some(WorkspaceScope::Local));
        assert!(copy.contains("local workspace"));
        assert!(!copy.contains("synced"));
    }
}
