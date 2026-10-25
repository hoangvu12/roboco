//! Settings → Rooms (ADR 0011): create, join, and manage rooms. The page is
//! the whole v1 room surface — joined engines appear as ordinary paired
//! engines in the existing device lists, so there are no sidebar changes.
//! The invite is secret-bearing (ADR 0008): never logged, and the card's
//! copy warns that anyone holding it can join the room.

use std::{collections::BTreeSet, sync::Arc};

use gpui::{
    AnyElement, ClipboardItem, Context, Div, Entity, SharedString, Subscription, Task, Window, div,
    prelude::*, px,
};

use crate::engine_registry::EngineKey;
use roboco_engine::rooms::RoomRecord;
use roboco_engine_client::rooms::{ClientRoom, JoinOutcome};
use roboco_proto::capabilities;

use crate::composer::{ComposerInput, ComposerInputEvent};
use crate::engine_registry::EngineConnectionState;
use crate::popover;
use crate::rooms::{self, MemberPick, RoomOps, RotateChecklist, short_id};
use crate::settings::widgets;
use crate::state::AppState;
use crate::theme::Theme;
use gpui_tokio::Tokio;

/// A room invite renders ~248 px square (an invite is 300–700 bytes, QR
/// version 16–19 at level M, the same tile the Tailcat invite card uses); a
/// huge book may not fit a QR at all, and the card then shows text only.
const INVITE_QR_SIZE: f32 = 248.0;

/// The pasted-join input's placeholder.
const JOIN_PLACEHOLDER: &str = "Paste a roboco-room: invite";

/// The create flow's room-name placeholder.
const NAME_PLACEHOLDER: &str = "Room name";

/// The caption under a room invite card: the ADR 0008 clipboard warning —
/// the invite carries the room's secret.
const INVITE_CAPTION: &str = "This invite carries the room's secret. Anyone holding it can join the room — share it only over a channel you trust.";

/// The share caption when the QR tile is shown.
const INVITE_SCAN_CAPTION: &str = "Scan with the Roboco app on your phone.";

/// The rotate flow's caption.
const ROTATE_CAPTION: &str =
    "Share the new invite with every member — they re-present with the new secret.";

/// What one member's row in the status card shows after a flow.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MemberStatus {
    label: SharedString,
    state: MemberState,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum MemberState {
    /// Joined by this client, its engine a live paired engine.
    Joined,
    /// In the book but not joined by this client yet (offline at the time,
    /// or unreachable) — presentations are repeatable, so a later pass
    /// finishes the job.
    Waiting(SharedString),
}

impl MemberState {
    fn dot(&self, theme: &Theme) -> gpui::Hsla {
        match self {
            Self::Joined => theme.success.opacity(0.9),
            Self::Waiting(_) => theme.warning.opacity(0.85),
        }
    }

    fn text(&self) -> SharedString {
        match self {
            Self::Joined => "Joined".into(),
            Self::Waiting(reason) => format!("Waiting — {reason}").into(),
        }
    }
}

/// The member status card after a join/create/sync flow.
#[derive(Clone, Debug)]
struct Report {
    room_name: SharedString,
    rows: Vec<MemberStatus>,
    /// Create-only: engines that could not seed the book (labels only).
    unavailable: Vec<SharedString>,
}

/// The invite card (create result, re-share, and the rotate flow's new
/// invite): QR tile, the invite string, Copy, and the secrecy caption.
#[derive(Clone, Debug)]
struct InviteCard {
    room_name: SharedString,
    encoded: String,
    qr_svg: Option<String>,
    caption: &'static str,
}

/// The rotate flow's view: the guided checklist plus the new invite.
#[derive(Clone, Debug)]
struct RotateView {
    checklist: RotateChecklist,
    invite: InviteCard,
}

pub struct RoomsPage {
    state: Entity<AppState>,
    scroll: widgets::PageScroll,
    ops: Option<Arc<RoomOps>>,
    /// The rooms this client joined (rooms-v1.json).
    rooms: Vec<ClientRoom>,
    /// The rooms the local engine holds (its own membership).
    local_rooms: Vec<RoomRecord>,
    invite_input: Entity<ComposerInput>,
    _invite_events: Subscription,
    name_input: Entity<ComposerInput>,
    _name_events: Subscription,
    /// Engines picked as members for the create flow.
    selected: BTreeSet<EngineKey>,
    report: Option<Report>,
    invite_card: Option<InviteCard>,
    rotate: Option<RotateView>,
    busy: bool,
    error: Option<SharedString>,
    task: Option<Task<()>>,
    local_task: Option<Task<()>>,
    _observe: Subscription,
}

impl RoomsPage {
    pub fn new(state: Entity<AppState>, cx: &mut Context<Self>) -> Self {
        let observe = cx.observe(&state, |_, _, cx| cx.notify());
        let invite_input = cx.new(|cx| ComposerInput::new(JOIN_PLACEHOLDER, cx));
        let invite_events = cx.subscribe(&invite_input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::Submitted) {
                this.join(cx);
            }
        });
        let name_input = cx.new(|cx| ComposerInput::new(NAME_PLACEHOLDER, cx));
        let name_events = cx.subscribe(&name_input, |this: &mut Self, _, event, cx| {
            if matches!(event, ComposerInputEvent::ModifiedSubmitted) {
                this.create(cx);
            }
        });
        let ops = state.read(cx).registry().cloned().map(|registry| {
            let data_dir = state
                .read(cx)
                .data_dir
                .clone()
                .unwrap_or_else(|| std::env::temp_dir().join("roboco-ui-unpersisted"));
            Arc::new(RoomOps::new(data_dir, registry))
        });
        let mut page = Self {
            state,
            scroll: widgets::PageScroll::default(),
            ops,
            rooms: Vec::new(),
            local_rooms: Vec::new(),
            invite_input,
            _invite_events: invite_events,
            name_input,
            _name_events: name_events,
            selected: BTreeSet::new(),
            report: None,
            invite_card: None,
            rotate: None,
            busy: false,
            error: None,
            task: None,
            local_task: None,
            _observe: observe,
        };
        page.reload(cx);
        page
    }

    /// Refresh from disk: the client rooms file (sync) and the local
    /// engine's membership (a blocking-thread read of its room store).
    fn reload(&mut self, cx: &mut Context<Self>) {
        let Some(ops) = self.ops.clone() else {
            return;
        };
        match ops.load_rooms_checked() {
            Ok(rooms) => {
                self.rooms = rooms;
                self.error = None;
            }
            Err(error) => {
                // The file is preserved; only the view falls back.
                self.rooms = Vec::new();
                self.error = Some(format!("Room records are unreadable: {error}").into());
            }
        }
        self.reload_local(cx);
        cx.notify();
    }

    /// Refresh the local engine's membership in the background. The store
    /// read is SQLite: it runs on gpui's background executor (not tokio —
    /// this future lives on the app executor, which has no ambient runtime).
    fn reload_local(&mut self, cx: &mut Context<Self>) {
        let Some(ops) = self.ops.clone() else {
            return;
        };
        let read = cx
            .background_executor()
            .spawn(async move { ops.local_engine_rooms_blocking() });
        let local = cx.spawn(async move |this, cx| {
            let rooms = read.await;
            this.update(cx, |page, cx| {
                page.local_rooms = rooms;
                cx.notify();
            })
            .ok();
        });
        self.local_task = Some(local);
    }

    fn join(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        let text = self.invite_input.read(cx).text().trim().to_string();
        if text.is_empty() {
            return;
        }
        // Decode before any async work: a refused invite is a fixed-text
        // shape error (ADR 0008) and needs no runtime at all.
        let invite = match roboco_proto::room::RoomInvite::decode(&text) {
            Ok(invite) => invite,
            Err(error) => {
                self.error = Some(format!("{error}").into());
                cx.notify();
                return;
            }
        };
        self.busy = true;
        self.error = None;
        self.report = None;
        self.invite_card = None;
        self.rotate = None;
        let operation = Tokio::spawn(cx, async move { ops.join_decoded(invite).await });
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(joined)) => {
                        page.invite_input
                            .update(cx, |input, cx| input.set_text(String::new(), cx));
                        page.report = Some(Report {
                            room_name: joined.room.name.clone().into(),
                            rows: member_statuses(&joined),
                            unavailable: Vec::new(),
                        });
                        page.reload(cx);
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Joining was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    fn create(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        let name = self.name_input.read(cx).text().trim().to_string();
        if name.is_empty() {
            return;
        }
        let selected: Vec<MemberPick> = self
            .selected
            .iter()
            .map(|key| MemberPick::Engine(key.clone()))
            .collect();
        let local_label = rooms::local_member_label(&self.state.read(cx).registry_snapshot);
        self.busy = true;
        self.error = None;
        self.report = None;
        self.invite_card = None;
        self.rotate = None;
        let operation = Tokio::spawn(cx, async move {
            ops.create(&name, &selected, &local_label).await
        });
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(created)) => {
                        page.name_input
                            .update(cx, |input, cx| input.set_text(String::new(), cx));
                        page.selected.clear();
                        // Everyone is waiting for the invite: a member engine
                        // only welcomes the secret once its host consumed it,
                        // so the card's Reconnect does the introductions.
                        let local_address = page.ops.as_ref().and_then(|ops| ops.local_address());
                        let rows = created
                            .invite
                            .members
                            .iter()
                            .map(|member| MemberStatus {
                                label: member.label.clone().into(),
                                state: if local_address.as_deref() == Some(member.address.as_str())
                                {
                                    MemberState::Joined
                                } else {
                                    MemberState::Waiting(
                                        "share the invite; Reconnect once its engine joins".into(),
                                    )
                                },
                            })
                            .collect();
                        page.report = Some(Report {
                            room_name: created.invite.name.clone().into(),
                            rows,
                            unavailable: created
                                .unavailable
                                .iter()
                                .map(|label| SharedString::from(label.clone()))
                                .collect(),
                        });
                        page.invite_card = Some(InviteCard {
                            room_name: created.invite.name.clone().into(),
                            encoded: created.encoded,
                            qr_svg: created.qr_svg,
                            caption: INVITE_SCAN_CAPTION,
                        });
                        page.reload(cx);
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Creating the room was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// Re-run the member join pass (reconnect offline members, union books).
    fn sync(&mut self, room_id: String, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        let op_room_id = room_id.clone();
        let operation = Tokio::spawn(cx, async move { ops.sync(&op_room_id).await });
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(Some(joined))) => {
                        page.report = Some(Report {
                            room_name: joined.room.name.clone().into(),
                            rows: member_statuses(&joined),
                            unavailable: Vec::new(),
                        });
                        // A Reconnect after a rotation is how the checklist's
                        // re-presented column fills in: members whose hosts
                        // consumed the new invite now welcome it.
                        if let Some(rotate) = &mut page.rotate
                            && rotate.checklist.new_room_id == room_id
                        {
                            rotate.checklist = rotate
                                .checklist
                                .refresh_re_presented(&joined.room.members, &joined.room.sessions);
                        }
                        page.reload(cx);
                    }
                    Ok(Ok(None)) => {
                        page.error = Some("That room is not joined by this device".into());
                        cx.notify();
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Reconnecting was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    fn leave(&mut self, room_id: String, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        let op_room_id = room_id.clone();
        let operation = Tokio::spawn(cx, async move { ops.leave(&op_room_id).await });
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(left)) => {
                        page.report = None;
                        page.invite_card = None;
                        if let Some(rotate) = &page.rotate
                            && rotate.checklist.new_room_id == room_id
                        {
                            page.rotate = None;
                        }
                        // Best-effort revokes that could not be confirmed
                        // (offline members) stay visible: the kick
                        // checklist's manual remainder.
                        let unrevoked: Vec<_> = left
                            .revoked
                            .iter()
                            .filter(|(_, confirmed)| !confirmed)
                            .map(|(label, _)| label.clone())
                            .collect();
                        if !unrevoked.is_empty() {
                            page.error = Some(
                                format!(
                                    "Left the room. Revoke the remaining sessions on: {}",
                                    unrevoked.join(", ")
                                )
                                .into(),
                            );
                        }
                        page.reload(cx);
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Leaving was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    fn rotate(&mut self, room_id: String, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        let op_room_id = room_id.clone();
        let operation = Tokio::spawn(cx, async move { ops.rotate(&op_room_id).await });
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(rotated)) => {
                        page.rotate = Some(RotateView {
                            checklist: rotated.checklist,
                            invite: InviteCard {
                                room_name: rotated.invite.name.clone().into(),
                                encoded: rotated.encoded,
                                qr_svg: rotated.qr_svg,
                                caption: ROTATE_CAPTION,
                            },
                        });
                        page.invite_card = None;
                        page.report = None;
                        page.reload(cx);
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Rotating was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// Show a saved room's invite (re-share): rebuild the card from the
    /// record — the invite string exists only here and on the clipboard,
    /// never in a log.
    fn show_invite(&mut self, room_id: String, cx: &mut Context<Self>) {
        let Some(ops) = self.ops.clone() else {
            return;
        };
        let Some(room) = ops.load_rooms().into_iter().find(|room| room.id == room_id) else {
            return;
        };
        if let Ok(invite) = room.invite()
            && let Ok(encoded) = invite.encode()
        {
            self.invite_card = Some(InviteCard {
                room_name: room.name.clone().into(),
                qr_svg: roboco_proto::qr::try_qr_svg(&encoded),
                encoded,
                caption: INVITE_SCAN_CAPTION,
            });
            self.rotate = None;
            cx.notify();
        }
    }

    /// The rotate checklist's local drop: forget the old verifier on this
    /// engine (the manual "drop the old room" step, ticket-01's surface).
    fn drop_old_room(&mut self, old_room_id: String, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(ops) = self.ops.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        let operation = Tokio::spawn(
            cx,
            async move { ops.local_engine_leave(&old_room_id).await },
        );
        self.task = Some(cx.spawn(async move |this, cx| {
            let outcome = operation.await;
            this.update(cx, |page, cx| {
                page.busy = false;
                match outcome {
                    Ok(Ok(true)) => {
                        if let Some(rotate) = &mut page.rotate {
                            rotate.checklist.local_old_verifier = false;
                        }
                        page.reload(cx);
                    }
                    Ok(Ok(false)) => {
                        page.error = Some("This engine no longer holds the old room".into());
                        cx.notify();
                    }
                    Ok(Err(error)) => {
                        page.error = Some(flow_error(&error));
                        cx.notify();
                    }
                    Err(_) => page.error = Some("Dropping the old room was interrupted".into()),
                }
            })
            .ok();
        }));
        cx.notify();
    }

    fn toggle_member(&mut self, key: EngineKey, cx: &mut Context<Self>) {
        if self.selected.contains(&key) {
            self.selected.remove(&key);
        } else {
            self.selected.insert(key);
        }
        cx.notify();
    }

    fn copy_invite(&mut self, text: String, cx: &mut Context<Self>) {
        // The invite is secret-bearing (ADR 0008): it goes to the clipboard
        // and nowhere else — never a log, never a toast body.
        cx.write_to_clipboard(ClipboardItem::new_string(text));
    }

    fn on_scroll_hovered(&mut self, hovered: &bool, _: &mut Window, cx: &mut Context<Self>) {
        if self.scroll.set_list_hovered(*hovered) {
            cx.notify();
        }
    }
}

/// The member rows of a join/create/sync outcome: joined members, and the
/// waiting ones with the failure's fixed text. Labels only — no addresses,
/// no secrets.
fn member_statuses(outcome: &JoinOutcome) -> Vec<MemberStatus> {
    outcome
        .room
        .members
        .iter()
        .map(|member| {
            let joined = outcome
                .room
                .sessions
                .iter()
                .any(|session| session.address == member.address);
            let failure = outcome
                .failures
                .iter()
                .find(|failure| failure.label == member.label);
            let state = if let Some(failure) = failure {
                MemberState::Waiting(failure.error.clone().into())
            } else if joined {
                MemberState::Joined
            } else {
                MemberState::Waiting("not joined by this device yet".into())
            };
            MemberStatus {
                label: member.label.clone().into(),
                state,
            }
        })
        .collect()
}

/// Room-flow errors reach the user as fixed text. Every layer below already
/// refuses to echo invite content (decode errors describe shape, presents
/// map status codes, dials never quote addresses) — this is the last gate.
fn flow_error(error: &anyhow::Error) -> SharedString {
    SharedString::from(format!("{error:#}"))
}

/// The invite card: QR on its white tile, the invite string, Copy, and the
/// secrecy captions.
fn render_invite_card(
    theme: &Theme,
    card: &InviteCard,
    index: usize,
    cx: &mut Context<RoomsPage>,
) -> AnyElement {
    let copy = card.encoded.clone();
    let has_qr = card.qr_svg.is_some();
    let qr_tile = card.qr_svg.as_deref().map(room_qr_tile);
    widgets::section_card(theme)
        .child(
            widgets::card_row(theme, true)
                .when_some(qr_tile, |row, tile| row.child(tile))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(widgets::url_fragment(theme, card.encoded.clone()))
                        .child(widgets::meta_line(
                            theme,
                            vec![
                                div()
                                    .child(SharedString::from(INVITE_CAPTION))
                                    .into_any_element(),
                            ],
                        ))
                        .when(has_qr, |column| {
                            column.child(widgets::meta_line(
                                theme,
                                vec![
                                    div()
                                        .child(SharedString::from(card.caption))
                                        .into_any_element(),
                                ],
                            ))
                        }),
                )
                .child(
                    widgets::text_action(&theme, widgets::ActionTone::Filled, "Copy")
                        .id(("room-invite-copy", index))
                        .debug_selector(move || format!("room-invite-copy-{index}"))
                        .tab_index(0)
                        .role(gpui::Role::Button)
                        .aria_label("Copy room invite")
                        .focus_visible(|s| s.border_2().border_color(theme.accent))
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.copy_invite(copy.clone(), cx)),
                        ),
                ),
        )
        .into_any_element()
}

/// The room invite QR on its white tile (radius 12, padding 10): dark on
/// light is a scanner requirement, not a theme choice.
fn room_qr_tile(svg: &str) -> AnyElement {
    let image = Arc::new(gpui::Image::from_bytes(
        gpui::ImageFormat::Svg,
        svg.as_bytes().to_vec(),
    ));
    div()
        .id("room-qr")
        .debug_selector(move || "room-qr".into())
        .flex_none()
        .p(px(10.0))
        .rounded(px(12.0))
        .bg(gpui::white())
        .child(gpui::img(image).size(px(INVITE_QR_SIZE)))
        .into_any_element()
}

/// The page host wrapper: the scrolling column with its fade bands and rail.
fn page_host(
    page: Div,
    scroll: &widgets::PageScroll,
    scrollbar: Option<AnyElement>,
    cx: &mut Context<RoomsPage>,
) -> impl IntoElement {
    div()
        .id("rooms-page-host")
        .relative()
        .size_full()
        .on_hover(cx.listener(RoomsPage::on_scroll_hovered))
        .child(
            crate::edge_fade::edge_faded(
                16.0,
                true,
                true,
                div()
                    .id("rooms-page")
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&scroll.scroll)
                    .child(page),
            )
            .fade_overflow_y(&scroll.scroll),
        )
        .children(scrollbar)
}

impl popover::ScrollRailHost for RoomsPage {
    fn rail_bar(&mut self) -> &mut popover::MenuScrollbarState {
        self.scroll.rail_bar()
    }

    fn rail_scroll(&self) -> Option<gpui::ScrollHandle> {
        self.scroll.rail_scroll()
    }
}

impl Render for RoomsPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::toast::take_error(&mut self.error, cx);
        let theme = Theme::of(cx).for_settings_surface();
        let registry = self.state.read(cx).registry().is_some();
        let supported = self
            .state
            .read(cx)
            .local_engine_supports(capabilities::ROOMS_V1);
        let busy = self.busy;
        let scrollbar = popover::rail(self, "rooms-page-scrollbar", &theme, cx);

        let mut page = widgets::page_column()
            .child(widgets::page_header(&theme, "Rooms", None))
            .child(widgets::page_subtitle(
                &theme,
                "Join once, reach every engine in the group. Rooms share reachability, never data.",
            ));

        // Capability gate (C5): hide the room surface on engines that do not
        // advertise rooms-v1 — never an error toast. No registry yet (engine
        // still connecting) shows the same quiet state.
        if !registry || !supported {
            page = page.child(
                widgets::section_card(&theme).child(
                    div()
                        .id("rooms-unsupported")
                        .debug_selector(move || "rooms-unsupported".into())
                        .px(px(16.0))
                        .py(px(10.0))
                        .text_size(crate::typography::ui_rems(12.0))
                        .text_color(theme.text_muted.opacity(0.6))
                        .child(SharedString::from(if registry {
                            "Rooms need an engine that supports them. Update this engine to use rooms."
                        } else {
                            "Rooms are available once the engine connects."
                        })),
                ),
            );
            return page_host(page, &self.scroll, scrollbar, cx);
        }

        if let Some(error) = self.error.clone() {
            page = page.child(widgets::error_strip(&theme, error).id("rooms-error"));
        }

        // Join.
        page = page.child(widgets::section_header(&theme, "Join a room", None));
        page = page.child(
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
                                div().flex_1().min_w_0().child(popover::dialog_field(
                                    self.invite_input.clone().into_any_element(),
                                )),
                            )
                            .child(
                                widgets::text_action(
                                    &theme,
                                    widgets::ActionTone::Solid,
                                    if busy { "Joining…" } else { "Join room" },
                                )
                                .id("room-join")
                                .debug_selector(|| "room-join".to_owned())
                                .tab_index(0)
                                .role(gpui::Role::Button)
                                .focus_visible(|s| s.border_2().border_color(theme.accent))
                                .on_click(cx.listener(|this, _, _, cx| this.join(cx))),
                            ),
                    )
                    .child(
                        div()
                            .mt(px(6.0))
                            .text_size(crate::typography::ui_rems(11.0))
                            .text_color(theme.text_muted)
                            .child(SharedString::from(
                                "Paste an invite from another member. Headless engines join with roboco engine room join.",
                            )),
                    ),
            ),
        );

        // The last flow's member report.
        if let Some(report) = self.report.clone() {
            page = page.child(widgets::section_header(
                &theme,
                format!("Members — {}", report.room_name),
                None,
            ));
            let mut card = widgets::section_card(&theme);
            if report.rows.is_empty() {
                card = card.child(quiet_row(&theme, "No members yet."));
            }
            for (index, row) in report.rows.iter().enumerate() {
                card = card.child(
                    widgets::card_row(&theme, index == 0)
                        .child(widgets::status_dot(row.state.dot(&theme)))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .child(widgets::row_title(&theme, row.label.clone())),
                        )
                        .child(
                            div()
                                .flex_none()
                                .text_size(crate::typography::ui_rems(12.0))
                                .text_color(match &row.state {
                                    MemberState::Joined => theme.success_muted,
                                    MemberState::Waiting(_) => theme.text_muted,
                                })
                                .child(row.state.text()),
                        ),
                );
            }
            for (index, label) in report.unavailable.iter().enumerate() {
                card = card.child(
                    widgets::card_row(&theme, report.rows.is_empty() && index == 0)
                        .child(widgets::status_dot(crate::theme::ink(0.22)))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .child(widgets::row_title(&theme, label.clone())),
                        )
                        .child(
                            div()
                                .flex_none()
                                .text_size(crate::typography::ui_rems(12.0))
                                .text_color(theme.text_muted)
                                .child(SharedString::from(
                                    "Not in the room — enable its Tailcat route first",
                                )),
                        ),
                );
            }
            page = page.child(card);
        }

        // Create.
        page = page.child(widgets::section_header(&theme, "Create a room", None));
        let snapshot = self.state.read(cx).registry_snapshot.clone();
        let engines: Vec<(EngineKey, SharedString, bool)> = snapshot
            .engines
            .iter()
            .map(|engine| {
                let label = if engine.key.is_local() {
                    rooms::local_member_label(&snapshot)
                } else {
                    engine
                        .devices
                        .first()
                        .map(|device| device.name.clone())
                        .unwrap_or_else(|| engine.info.device_id.clone())
                };
                (
                    engine.key.clone(),
                    SharedString::from(label),
                    engine.state == EngineConnectionState::Connected,
                )
            })
            .collect();
        let mut create_card = widgets::section_card(&theme).child(
            div()
                .px(px(16.0))
                .py(px(10.0))
                .flex()
                .flex_row()
                .items_center()
                .gap(px(12.0))
                .child(div().flex_1().min_w_0().child(popover::dialog_field(
                    self.name_input.clone().into_any_element(),
                )))
                .child(
                    widgets::text_action(
                        &theme,
                        widgets::ActionTone::Solid,
                        if busy { "Creating…" } else { "Create room" },
                    )
                    .id("room-create")
                    .debug_selector(|| "room-create".to_owned())
                    .tab_index(0)
                    .role(gpui::Role::Button)
                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                    .on_click(cx.listener(|this, _, _, cx| this.create(cx))),
                ),
        );
        for (index, (key, label, connected)) in engines.iter().enumerate() {
            let selected = key.is_local() || self.selected.contains(key);
            let key = key.clone();
            // The local engine is always a member: its toggle is on and does
            // nothing. Picked engines toggle on click; the switch carries the
            // id the click handler needs.
            let switch = if key.is_local() {
                widgets::toggle_switch(&theme, true, format!("room-member-{index}"))
                    .into_any_element()
            } else {
                widgets::toggle_switch(&theme, selected, format!("room-member-{index}"))
                    .id(("room-member-toggle", index))
                    .cursor_pointer()
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.toggle_member(key.clone(), cx)),
                    )
                    .into_any_element()
            };
            create_card = create_card.child(
                widgets::card_row(&theme, false)
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(widgets::row_title(&theme, label.clone())),
                    )
                    .child(
                        div()
                            .flex_none()
                            .text_size(crate::typography::ui_rems(12.0))
                            .text_color(if *connected {
                                theme.success_muted
                            } else {
                                theme.text_muted
                            })
                            .child(SharedString::from(if *connected {
                                "Online"
                            } else {
                                "Offline"
                            })),
                    )
                    .child(switch),
            );
        }
        page = page.child(create_card);
        page = page.child(
            div()
                .px(px(16.0))
                .mt(px(6.0))
                .text_size(crate::typography::ui_rems(11.0))
                .text_color(theme.text_muted)
                .child(SharedString::from(
                    "This engine is always a member; pick the engines to group with it. Share the invite so each member's engine can join, then Reconnect to reach them.",
                )),
        );

        // The invite card (create result or a re-share).
        if let Some(card) = self.invite_card.clone() {
            page = page
                .child(widgets::section_header(
                    &theme,
                    format!("Room invite — {}", card.room_name),
                    None,
                ))
                .child(render_invite_card(&theme, &card, 0, cx));
        }

        // The rotate flow.
        if let Some(rotate) = self.rotate.clone() {
            page = page.child(widgets::section_header(&theme, "Rotate room secret", None));
            let mut checklist = widgets::section_card(&theme);
            if rotate.checklist.rows.is_empty() {
                checklist = checklist.child(quiet_row(&theme, "No members yet."));
            }
            for (index, row) in rotate.checklist.rows.iter().enumerate() {
                let (first, second) = row.status_fragments();
                checklist = checklist.child(
                    widgets::card_row(&theme, index == 0)
                        .child(widgets::status_dot(if row.re_presented {
                            theme.success.opacity(0.9)
                        } else {
                            theme.warning.opacity(0.85)
                        }))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .child(widgets::row_title(&theme, row.label.clone()))
                                .child(widgets::meta_line(
                                    &theme,
                                    vec![
                                        div().child(SharedString::from(first)).into_any_element(),
                                        div().child(SharedString::from(second)).into_any_element(),
                                    ],
                                )),
                        ),
                );
            }
            page = page.child(checklist);
            page = page.child(
                div()
                    .px(px(16.0))
                    .mt(px(6.0))
                    .text_size(crate::typography::ui_rems(11.0))
                    .text_color(theme.text_muted)
                    .child(SharedString::from(rotate.checklist.hint())),
            );
            if rotate.checklist.local_old_verifier {
                let old_room_id = rotate.checklist.old_room_id.clone();
                page = page.child(
                    div()
                        .px(px(16.0))
                        .mt(px(8.0))
                        .flex()
                        .items_center()
                        .gap(px(8.0))
                        .child(
                            widgets::text_action(
                                &theme,
                                widgets::ActionTone::Quiet,
                                if busy {
                                    "Dropping…"
                                } else {
                                    "Drop old room on this engine"
                                },
                            )
                            .id("room-drop-old")
                            .text_color(theme.danger)
                            .tab_index(0)
                            .role(gpui::Role::Button)
                            .focus_visible(|s| s.border_2().border_color(theme.accent))
                            .on_click(cx.listener(
                                move |this, _, _, cx| this.drop_old_room(old_room_id.clone(), cx),
                            )),
                        ),
                );
            }
            page = page
                .child(widgets::section_header(&theme, "New room invite", None))
                .child(render_invite_card(&theme, &rotate.invite, 1, cx));
        }

        // Your rooms.
        page = page.child(widgets::section_header(&theme, "Your rooms", None));
        if self.rooms.is_empty() {
            page = page.child(widgets::section_card(&theme).child(quiet_row(
                &theme,
                "No rooms yet. Join one or create your first.",
            )));
        }
        for (index, room) in self.rooms.clone().into_iter().enumerate() {
            let member_count = room.members.len();
            let local_member = self.local_rooms.iter().any(|record| record.id == room.id);
            let sync_id = room.id.clone();
            let rotate_id = room.id.clone();
            let leave_id = room.id.clone();
            let show_id = room.id.clone();
            page = page.child(
                widgets::section_card(&theme).child(
                    widgets::card_row(&theme, index == 0)
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .child(widgets::row_title(&theme, room.name.clone()))
                                .child(widgets::meta_line(
                                    &theme,
                                    vec![
                                        div()
                                            .child(SharedString::from(format!(
                                                "{} members · {}",
                                                member_count,
                                                short_id(&room.id)
                                            )))
                                            .into_any_element(),
                                        div()
                                            .child(SharedString::from(if local_member {
                                                "This engine is a member"
                                            } else {
                                                "This engine is not a member"
                                            }))
                                            .into_any_element(),
                                    ],
                                )),
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
                                        "Show invite",
                                    )
                                    .id(("room-show", index))
                                    .tab_index(0)
                                    .role(gpui::Role::Button)
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(
                                        move |this, _, _, cx| this.show_invite(show_id.clone(), cx),
                                    )),
                                )
                                .child(
                                    widgets::text_action(
                                        &theme,
                                        widgets::ActionTone::Quiet,
                                        "Reconnect",
                                    )
                                    .id(("room-sync", index))
                                    .tab_index(0)
                                    .role(gpui::Role::Button)
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(
                                        move |this, _, _, cx| this.sync(sync_id.clone(), cx),
                                    )),
                                )
                                .child(
                                    widgets::text_action(
                                        &theme,
                                        widgets::ActionTone::Quiet,
                                        "Rotate",
                                    )
                                    .id(("room-rotate", index))
                                    .tab_index(0)
                                    .role(gpui::Role::Button)
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(
                                        move |this, _, _, cx| this.rotate(rotate_id.clone(), cx),
                                    )),
                                )
                                .child(
                                    widgets::text_action(
                                        &theme,
                                        widgets::ActionTone::Quiet,
                                        "Leave",
                                    )
                                    .id(("room-leave", index))
                                    .text_color(theme.danger)
                                    .tab_index(0)
                                    .role(gpui::Role::Button)
                                    .focus_visible(|s| s.border_2().border_color(theme.accent))
                                    .on_click(cx.listener(
                                        move |this, _, _, cx| this.leave(leave_id.clone(), cx),
                                    )),
                                ),
                        ),
                ),
            );
        }

        page_host(page, &self.scroll, scrollbar, cx)
    }
}

/// A quiet placeholder row.
fn quiet_row(theme: &Theme, copy: &'static str) -> Div {
    div()
        .px(px(16.0))
        .py(px(10.0))
        .text_size(crate::typography::ui_rems(12.0))
        .text_color(theme.text_muted.opacity(0.6))
        .child(SharedString::from(copy))
}

#[cfg(test)]
mod tests;
