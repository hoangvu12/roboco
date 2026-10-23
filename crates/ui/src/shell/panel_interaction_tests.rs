//! The session card's side-chat interactions through Shell's actions and
//! actual GPUI layout. (The panel-lifecycle suite of this file's upstream
//! shape rides the #677 panels port — wave-7 ticket 08 — and lands there;
//! these are the card-specific tests #895 added.)
use super::*;
use gpui::{AppContext, TestAppContext, VisualTestContext};

fn setup(cx: &mut TestAppContext) -> (tempfile::TempDir, Entity<Shell>, &mut VisualTestContext) {
    let dir = tempfile::tempdir().unwrap();
    cx.update(|cx| {
        gpui_base::init(cx);
        cx.set_global(Theme::default());
        crate::app_menus::init(cx);
        crate::history::init(
            Default::default(),
            Default::default(),
            Default::default(),
            Default::default(),
            cx,
        );
        let mut config = UiSettings::default();
        config.sidebar_width = 300.0;
        // The session card shifts the composer while it fits; these tests
        // measure the card, not the composer's column.
        config.session_info_hidden = true;
        settings::init(config, dir.path(), cx);
    });
    let (shell, cx) = cx.add_window_view(|_, cx| {
        let state = cx.new(|_| {
            let mut state = AppState::new();
            state.no_project = true;
            state.selected_chat = Some("a".into());
            state.chats = ["a", "b"]
                .into_iter()
                .map(|id| {
                    serde_json::from_value(serde_json::json!({
                        "id": id, "title": id, "deviceId": "local", "archived": false,
                        "createdAt": Utc::now(),
                    }))
                    .unwrap()
                })
                .collect();
            state
        });
        let mut shell = Shell::new(
            state,
            EngineBootConfig {
                data_dir: dir.path().into(),
                ipc_port: 0,
                default_harness: roboco_proto::HarnessId::Mock,
            },
            cx,
        );
        shell.debug_gate = Some(GatePhase::Ready);
        shell.splash = SplashPhase::Gone;
        shell.active_chat = "a".into();
        shell
    });
    cx.simulate_resize(gpui::size(px(1600.0), px(900.0)));
    (dir, shell, cx)
}

fn change(
    shell: &Entity<Shell>,
    cx: &mut VisualTestContext,
    action: impl FnOnce(&mut Shell, &mut Window, &mut Context<Shell>),
) {
    cx.update(|window, cx| {
        shell.update(cx, |shell, cx| {
            action(shell, window, cx);
            cx.notify();
        })
    });
}

fn settled(_shell: &Entity<Shell>, cx: &mut VisualTestContext) {
    for _ in 0..2 {
        cx.run_until_parked();
        cx.update(|window, cx| {
            window.refresh();
            window.draw(cx).clear();
        });
    }
}

#[gpui::test]
fn side_chat_menu_rename_keeps_the_editor_visible_without_opening_files(cx: &mut TestAppContext) {
    let (_dir, shell, cx) = setup(cx);
    change(&shell, cx, |shell, _, cx| {
        shell.state.update(cx, |state, cx| {
            state.chats.push(
                serde_json::from_value(serde_json::json!({
                    "id": "side", "title": "Side chat", "parentChatId": "a",
                    "deviceId": "local", "archived": false, "createdAt": Utc::now(),
                }))
                .unwrap(),
            );
            cx.notify();
        });
        shell.chat_menu.open(ChatMenuState {
            chat_id: "side".into(),
            tab: None,
            position: gpui::point(px(800.0), px(200.0)),
            page: ChatMenuPage::Root,
        });
    });
    settled(&shell, cx);
    assert!(shell.read_with(cx, |shell, cx| !shell.files_panel_open(cx)));

    let rename = cx.debug_bounds("chat-menu-rename").unwrap();
    cx.simulate_click(rename.center(), Default::default());
    settled(&shell, cx);

    shell.read_with(cx, |shell, cx| {
        assert!(
            !shell.files_panel_open(cx),
            "Rename must not open the explorer"
        );
        assert!(shell.session_info_visible(cx));
        assert_eq!(shell.chat_rename.as_ref().unwrap().chat_id, "side");
    });
    let field = cx.debug_bounds("session-lanes-chat-side-title-editor").unwrap();
    let card = cx.debug_bounds("session-lanes").unwrap();
    assert!(field.top() >= card.top() && field.bottom() <= card.bottom());
}

#[gpui::test]
fn side_chat_rename_leaves_takeover_to_show_its_editor(cx: &mut TestAppContext) {
    let (_dir, shell, cx) = setup(cx);
    change(&shell, cx, |shell, _, cx| {
        shell.state.update(cx, |state, cx| {
            state.chats.push(
                serde_json::from_value(serde_json::json!({
                    "id": "side", "title": "Side chat", "parentChatId": "a",
                    "deviceId": "local", "archived": false, "createdAt": Utc::now(),
                }))
                .unwrap(),
            );
            cx.notify();
        });
        shell.toggle_right_pane(cx);
    });
    settled(&shell, cx);
    change(&shell, cx, |s, _, cx| s.toggle_right_pane_expand(cx));
    settled(&shell, cx);
    assert!(shell.read_with(cx, |s, _| s.right_pane_expanded));

    change(&shell, cx, |s, _, cx| s.open_rename_chat("side".into(), cx));
    settled(&shell, cx);
    assert!(!shell.read_with(cx, |s, _| s.right_pane_expanded));
    let field = cx.debug_bounds("session-lanes-chat-side-title-editor").unwrap();
    let card = cx.debug_bounds("session-lanes").unwrap();
    assert!(field.top() >= card.top() && field.bottom() <= card.bottom());
}

fn check_side_chat_rename_scroll(cx: &mut TestAppContext, width: f32, reduced_motion: bool) {
    let (_dir, shell, cx) = setup(cx);
    cx.simulate_resize(gpui::size(px(width), px(900.0)));
    cx.update(|_, cx| motion::set_reduced_motion(cx, reduced_motion));
    change(&shell, cx, |shell, _, cx| {
        shell.state.update(cx, |state, cx| {
            let now = Utc::now();
            for index in 0..40 {
                state.chats.push(
                    serde_json::from_value(serde_json::json!({
                        "id": format!("side-{index}"), "title": format!("Side {index}"),
                        "parentChatId": "a", "deviceId": "local", "archived": false,
                        "createdAt": now - chrono::Duration::minutes(index),
                    }))
                    .unwrap(),
                );
            }
            cx.notify();
        });
    });
    settled(&shell, cx);
    assert!(cx.debug_bounds("session-lanes").is_none());

    // Reveal a paged-out row from a hidden card, then a row above the new
    // scroll position. Both directions must leave the whole editor visible.
    for (chat, selector) in [
        ("side-39", "session-lanes-chat-side-39-title-editor"),
        ("side-0", "session-lanes-chat-side-0-title-editor"),
    ] {
        change(&shell, cx, |shell, _, cx| {
            shell.open_rename_chat(chat.into(), cx);
        });
        settled(&shell, cx);
        for _ in 0..4 {
            cx.run_until_parked();
            cx.update(|window, cx| {
                window.refresh();
                window.draw(cx).clear();
            });
        }
        let field = cx.debug_bounds(selector).unwrap();
        let card = cx.debug_bounds("session-lanes").unwrap();
        assert!(
            field.top() >= card.top() && field.bottom() <= card.bottom(),
            "{chat}'s editor {field:?} is outside the card {card:?}"
        );
        cx.update(|window, cx| {
            shell.read_with(cx, |shell, cx| {
                let rename = shell.chat_rename.as_ref().unwrap();
                assert_eq!(rename.chat_id, chat);
                assert!(rename.input.focus_handle(cx).is_focused(window));
            });
        });
    }
}

#[gpui::test]
fn side_chat_rename_scrolls_to_its_row_in_a_docked_card(cx: &mut TestAppContext) {
    check_side_chat_rename_scroll(cx, 1600.0, false);
}

#[gpui::test]
fn side_chat_rename_scrolls_to_its_row_in_a_floating_card_without_motion(cx: &mut TestAppContext) {
    check_side_chat_rename_scroll(cx, 1000.0, true);
}
