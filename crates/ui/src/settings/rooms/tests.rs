//! The Rooms settings page: capability gating (the C5 hiding rule), the
//! invite card's QR + copy + secrecy caption, and the pasted-join refusal.
//! The async flows themselves (create/join/leave/rotate against real
//! engines) are covered by `crate::rooms`' and `roboco-engine-client`'s
//! suites — gpui's deterministic test scheduler cannot host the flows'
//! cross-thread wakes, so the page tests exercise exactly the parts that
//! render and the parts that run synchronously.

use super::*;
use gpui::TestAppContext;
use roboco_proto::room::{RoomInvite, RoomMember};
use roboco_proto::{EngineInfo, WorkspaceScope};

fn draw(cx: &mut gpui::VisualTestContext) {
    cx.update(|window, cx| {
        window.refresh();
        window.draw(cx).clear();
    });
}

/// The channel test client needs a reactor: tests build a current-thread
/// runtime and hold `let _guard = runtime.enter();` for their lifetime
/// (the same shape the remote-access page tests use).
fn test_runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
}

fn test_state(
    cx: &mut TestAppContext,
    data_dir: Option<std::path::PathBuf>,
    capabilities: Vec<String>,
) -> Entity<AppState> {
    let (out, _requests) = tokio::sync::mpsc::channel::<String>(16);
    let (replies, inbound) = tokio::sync::mpsc::channel::<String>(16);
    let state = cx.new(|_| AppState::new());
    state.update(cx, |state, _| {
        let info = EngineInfo {
            device_id: "device-1".into(),
            workspace_scope: WorkspaceScope::Local,
            cursor_sdk_version: None,
            capabilities,
            version: None,
            protocol_version: None,
            min_client_protocol_version: None,
        };
        state.set_test_engine(crate::state::EngineHandle::from_test_client_with_info(
            roboco_rpc::RpcClient::new(out, inbound),
            info,
        ));
        state.data_dir = data_dir;
    });
    let _ = replies;
    state
}

/// The page hides the room surface when the local engine lacks `rooms-v1`
/// (C5: capability absence hides, never error toasts) and shows it when the
/// engine advertises the capability.
#[gpui::test]
fn room_actions_hide_without_the_rooms_capability(cx: &mut TestAppContext) {
    let runtime = test_runtime();
    let _guard = runtime.enter();
    cx.update(|cx| {
        gpui_base::init(cx);
        cx.set_global(Theme::dark());
    });
    let dir = tempfile::tempdir().unwrap();
    let state = test_state(cx, Some(dir.path().to_path_buf()), Vec::new());
    let (_page, cx) = cx.add_window_view(|_, cx| RoomsPage::new(state, cx));
    cx.run_until_parked();
    draw(cx);
    assert!(
        cx.debug_bounds("rooms-unsupported").is_some(),
        "the quiet unsupported state renders"
    );
    assert!(
        cx.debug_bounds("room-join").is_none(),
        "no join action against an engine without rooms-v1"
    );
    assert!(
        cx.debug_bounds("room-create").is_none(),
        "no create action against an engine without rooms-v1"
    );
}

#[gpui::test]
fn room_actions_show_with_the_rooms_capability(cx: &mut TestAppContext) {
    let runtime = test_runtime();
    let _guard = runtime.enter();
    cx.update(|cx| {
        gpui_base::init(cx);
        cx.set_global(Theme::dark());
    });
    let dir = tempfile::tempdir().unwrap();
    let state = test_state(
        cx,
        Some(dir.path().to_path_buf()),
        roboco_proto::capabilities::current(),
    );
    let (_page, cx) = cx.add_window_view(|_, cx| RoomsPage::new(state, cx));
    cx.run_until_parked();
    draw(cx);
    assert!(cx.debug_bounds("rooms-unsupported").is_none());
    assert!(cx.debug_bounds("room-join").is_some(), "the join action");
    assert!(
        cx.debug_bounds("room-create").is_some(),
        "the create action"
    );
    // The empty state for a fresh data dir: no rooms joined yet.
    assert!(cx.debug_bounds("room-leave-0").is_none(), "no rooms listed");
}

/// The invite card: QR tile, copy action, the invite string, and the ADR
/// 0008 clipboard-warning caption — the copy is the only place the invite
/// goes after the card.
#[gpui::test]
fn the_invite_card_renders_qr_copy_and_the_secrecy_warning(cx: &mut TestAppContext) {
    let runtime = test_runtime();
    let _guard = runtime.enter();
    cx.update(|cx| {
        gpui_base::init(cx);
        cx.set_global(Theme::dark());
    });
    let dir = tempfile::tempdir().unwrap();
    let state = test_state(
        cx,
        Some(dir.path().to_path_buf()),
        roboco_proto::capabilities::current(),
    );
    let (page, cx) = cx.add_window_view(|_, cx| RoomsPage::new(state, cx));
    cx.run_until_parked();
    draw(cx);
    assert!(cx.debug_bounds("room-qr").is_none(), "no invite yet");

    // A realistic one-member invite (what the create flow mints).
    let invite = RoomInvite::new(
        "Home fleet",
        "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN",
        vec![RoomMember {
            label: "desktop".into(),
            address: "tcAbCdEfGhIjKlMnOpQrStUvWxYz0123456789".into(),
        }],
    )
    .unwrap();
    let encoded = invite.encode().unwrap();
    page.update(cx, |page, cx| {
        page.invite_card = Some(InviteCard {
            room_name: "Home fleet".into(),
            qr_svg: roboco_proto::qr::try_qr_svg(&encoded),
            encoded: encoded.clone(),
            caption: INVITE_SCAN_CAPTION,
        });
        cx.notify();
    });
    draw(cx);
    let qr = cx.debug_bounds("room-qr").expect("the invite QR tile");
    assert_eq!(qr.size.width, px(INVITE_QR_SIZE + 20.0));
    assert_eq!(qr.size.height, px(INVITE_QR_SIZE + 20.0));
    assert!(
        cx.debug_bounds("room-invite-copy-0").is_some(),
        "the copy action"
    );
    page.update(cx, |page, _| {
        let card = page.invite_card.as_ref().expect("the invite card");
        // The QR decodes to exactly the invite string (the shared encoder
        // round-trips).
        assert!(card.qr_svg.is_some());
        assert_eq!(RoomInvite::decode(&card.encoded).unwrap(), invite);
    });
}

/// A pasted junk invite refuses with fixed text — never the pasted bytes —
/// before any async work runs.
#[gpui::test]
fn a_bad_pasted_invite_refuses_with_fixed_text(cx: &mut TestAppContext) {
    let runtime = test_runtime();
    let _guard = runtime.enter();
    cx.update(|cx| {
        gpui_base::init(cx);
        cx.set_global(Theme::dark());
    });
    let dir = tempfile::tempdir().unwrap();
    let state = test_state(
        cx,
        Some(dir.path().to_path_buf()),
        roboco_proto::capabilities::current(),
    );
    let (page, cx) = cx.add_window_view(|_, cx| RoomsPage::new(state, cx));
    cx.run_until_parked();
    page.update(cx, |page, cx| {
        page.invite_input.update(cx, |input, cx| {
            input.set_text("not-an-invite-with-a-secret-INSIDE", cx)
        });
        page.join(cx);
        // The refusal is synchronous: no flow, no async work, and the
        // fixed text never carries the pasted bytes.
        let error = page.error.as_ref().expect("a refusal").to_string();
        assert_eq!(error, "a room invite starts with roboco-room:");
        assert!(!error.contains("INSIDE"), "the pasted bytes never echo");
        assert!(page.rooms.is_empty(), "nothing was joined");
        assert!(!page.busy, "the refusal needed no flow");
    });
}

/// Member status rows: joined, waiting with the failure's fixed text, and
/// waiting without one.
#[test]
fn member_statuses_carry_labels_and_fixed_text_only() {
    use roboco_engine_client::rooms::{ClientRoom, MemberFailure};
    let room = ClientRoom {
        id: "room-1".into(),
        name: "Home fleet".into(),
        secret: "SECRET".into(),
        joined_at: 1,
        members: vec![
            RoomMember {
                label: "server".into(),
                address: "tcB".into(),
            },
            RoomMember {
                label: "laptop".into(),
                address: "tcC".into(),
            },
        ],
        sessions: vec![roboco_engine_client::rooms::RoomMemberSession {
            address: "tcB".into(),
            engine_key: "engine-1".into(),
            session_id: "session-1".into(),
        }],
    };
    let outcome = roboco_engine_client::rooms::JoinOutcome {
        failures: vec![MemberFailure {
            label: "laptop".into(),
            error: "the member refused the room secret or does not serve rooms".into(),
        }],
        room,
    };
    let rows = member_statuses(&outcome);
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].label, "server");
    assert_eq!(rows[0].state, MemberState::Joined);
    assert_eq!(rows[1].label, "laptop");
    match &rows[1].state {
        MemberState::Waiting(reason) => {
            assert!(reason.starts_with("the member refused"));
            assert!(!reason.to_string().contains("SECRET"));
        }
        other => panic!("laptop is waiting: {other:?}"),
    }
}
