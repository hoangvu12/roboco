//! The session card's lanes for a chat that lives on a paired engine: its
//! side chats and subagents list, count, open and get created exactly as a
//! local chat's do, every request reaching the engine that owns the chat.
//! Real engines over loopback (`paired_fixture`).

use super::session_lanes::{SubagentLane, child_chat_rows, running_lanes, subagent_rows};
use super::*;
use crate::composer::ComposerInputEvent;
use crate::engine_registry::ScopedId;
use crate::paired_fixture::{PairedEngines, wait_until};
use gpui::TestAppContext;
use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry, SubagentStatus};
use roboco_proto::{Session, SessionStatus, ToolCall};

fn assistant(id: &str, parts: Vec<MessagePart>) -> SessionMessageEntry {
    SessionMessageEntry {
        duration_ms: None,
        id: id.into(),
        role: MessageRole::Assistant,
        parts,
        created_at: Utc::now().timestamp_millis(),
        device_id: "remote-device".into(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        origin_chat_id: None,
        provider_turn: None,
    }
}

fn text(id: &str, text: &str) -> MessagePart {
    MessagePart::Text {
        id: id.into(),
        text: text.into(),
    }
}

fn spawn_chip(id: &str, doc: &str, status: SubagentStatus) -> MessagePart {
    MessagePart::Tool {
        id: id.into(),
        call: ToolCall::Unknown {
            name: format!("Agent: {id}"),
            input: Some(serde_json::json!({ "description": id })),
        },
        is_error: false,
        resolved: true,
        output: None,
        diff: None,
        output_ref: None,
        output_bytes: None,
        diff_ref: None,
        diff_stats: None,
        subagent_ref: Some(doc.into()),
        subagent_status: Some(status),
        subagent_tail: None,
    }
}

/// A working session row, fresh for the whole test.
fn session(chat_id: &str, running_subagents: u32) -> Session {
    Session {
        last_completed_turn: None,
        chat_id: chat_id.into(),
        device_id: String::new(),
        status: SessionStatus::Working,
        started_at: Some(Utc::now()),
        updated_at: Utc::now() + chrono::Duration::minutes(5),
        limit: None,
        scheduled_resume: None,
        running_subagents,
    }
}

#[gpui::test]
fn a_paired_engines_chat_lists_counts_opens_and_creates_its_lanes(cx: &mut TestAppContext) {
    let fixture = PairedEngines::start(cx);
    let project = tempfile::tempdir().unwrap();
    // The paired engine: a chat with one working side chat, a live and a
    // finished subagent, and the finished one's frozen transcript.
    fixture.runtime.block_on(async {
        let remote = &fixture.remote;
        let config: roboco_proto::ChatConfig = serde_json::from_value(serde_json::json!({
            "harness": "mock", "sandbox": "workspace-write",
        }))
        .unwrap();
        remote
            .workspace
            .create_space(
                "project",
                &remote.device_id,
                &project.path().to_string_lossy(),
                None,
                false,
            )
            .unwrap();
        remote
            .workspace
            .create_chat("parent", Some("project"), None, Some(config.clone()), None)
            .unwrap();
        remote
            .workspace
            .create_chat_with_parent(
                "side",
                Some("project"),
                None,
                Some(config),
                None,
                Some("parent".into()),
                None,
            )
            .unwrap();
        remote
            .doc_host
            .open("parent")
            .unwrap()
            .doc()
            .push_message(&assistant(
                "reply",
                vec![
                    text("t0", "Spawning helpers."),
                    spawn_chip("live", "parent--sub--live", SubagentStatus::Running),
                    spawn_chip("done", "parent--sub--done", SubagentStatus::Done),
                ],
            ))
            .unwrap();
        remote
            .doc_host
            .open("parent--sub--live")
            .unwrap()
            .doc()
            .push_message(&assistant("live-entry", vec![text("t1", "Still going.")]))
            .unwrap();
        let frozen = serde_json::to_string(&vec![assistant(
            "frozen-entry",
            vec![text("t2", "All done.")],
        )])
        .unwrap();
        remote.doc_host.upload_tool_sidecar(
            "parent",
            roboco_doc::SidecarPayload {
                part_id: "parent--sub--done".into(),
                output: Some(frozen),
                diff: None,
            },
        );
    });
    let parent = fixture.scoped("parent");
    let side = fixture.scoped("side");
    let live_doc = fixture.scoped("parent--sub--live");
    let done_doc = fixture.scoped("parent--sub--done");
    fixture.wait_for_state(cx, "the paired engine's rows", |s| {
        s.chats.iter().any(|c| c.id == side)
    });
    let window = cx.add_window(|_, cx| Shell::new(fixture.state.clone(), fixture.boot.clone(), cx));
    window
        .update(cx, |shell, _, cx| shell.open_chat(parent.clone(), cx))
        .unwrap();
    fixture.wait_for_state(cx, "the parent's transcript", |s| {
        s.selected_chat.as_deref() == Some(parent.as_str()) && !s.transcript.is_empty()
    });

    // Listing and counting: the side chat hangs under its scoped parent (and
    // leaves the sidebar), and the titlebar counts it with the subagents.
    // Live statuses only come from a running harness (an engine publishes
    // just its own runs), so the two working rows are applied here exactly
    // as the projection delivers them, in the same update that counts.
    fixture.state.update(cx, |s, _| {
        let now = Utc::now();
        let mut sessions = s.sessions.clone();
        for mut row in [session("parent", 2), session("side", 0)] {
            row.chat_id = fixture.scoped(&row.chat_id);
            row.device_id = fixture.remote_device();
            sessions.push(row);
        }
        s.apply_sessions(sessions);
        let side_chats: Vec<_> = child_chat_rows(s, &parent, now)
            .into_iter()
            .map(|row| row.chat_id)
            .collect();
        assert_eq!(side_chats, [side.clone()]);
        assert!(s.visible_chats().all(|c| c.id != side));
        assert!(s.visible_chats().any(|c| c.id == parent));
        assert_eq!(running_lanes(s, &parent, now), 2 + 1);
        let lane = SubagentLane::new(subagent_rows(s, &parent));
        assert_eq!(
            lane.active.iter().map(|r| &r.doc_id).collect::<Vec<_>>(),
            [&live_doc]
        );
        assert_eq!(
            lane.finished.iter().map(|r| &r.doc_id).collect::<Vec<_>>(),
            [&done_doc]
        );
    });

    // Opening: a live subagent streams from the paired engine; a finished
    // one reads its frozen transcript there.
    window
        .update(cx, |shell, _, cx| {
            shell.add_subagent_surface(parent.clone(), live_doc.clone(), "live".into(), false, cx);
            shell.add_subagent_surface(parent.clone(), done_doc.clone(), "done".into(), true, cx);
        })
        .unwrap();
    fixture.wait_for_state(cx, "both subagent transcripts", |s| {
        s.sub_transcript(&live_doc)
            .iter()
            .any(|e| e.id == "live-entry")
            && s.sub_transcript(&done_doc)
                .iter()
                .any(|e| e.id == "frozen-entry")
    });
    assert!(
        fixture
            .state
            .read_with(cx, |s, _| s.target_for_id(&live_doc).unwrap().key() == &fixture.key)
    );

    // An existing side chat opens in the right pane on its own engine.
    window
        .update(cx, |shell, _, cx| shell.open_child_chat_tab(&side, cx))
        .unwrap();
    let side_tab = window
        .update(cx, |shell, _, cx| {
            shell
                .side_chats
                .values()
                .find(|tab| tab.state.read(cx).selected_chat.as_deref() == Some(side.as_str()))
                .map(|tab| tab.state.clone())
        })
        .unwrap()
        .expect("the side chat's tab");
    wait_until(cx, "the side chat's own engine", |cx| {
        side_tab.read_with(cx, |s, _| {
            s.target_for_id(&side)
                .is_ok_and(|target| target.is_connected() && target.key() == &fixture.key)
        })
    });

    // Fork: the paired engine copies the chat, and the copy opens under its
    // scoped parent.
    let source = fixture
        .state
        .read_with(cx, |s, _| s.chats.iter().find(|c| c.id == parent).cloned())
        .unwrap();
    let tabs_before = window.update(cx, |shell, _, _| shell.side_chats.len()).unwrap();
    window
        .update(cx, |shell, _, cx| {
            shell.fork_chat(source, parent.clone(), None, None, None, cx)
        })
        .unwrap();
    wait_until(cx, "the fork's reply", |cx| {
        window
            .update(cx, |shell, _, _| !shell.side_chat_creating)
            .unwrap()
    });
    let fork = window
        .update(cx, |shell, _, cx| {
            let failure = shell.composer.read(cx).failure().map(|f| f.to_string());
            assert!(failure.is_none(), "fork failed: {failure:?}");
            assert!(shell.side_chats.len() > tabs_before, "the fork opened");
            shell.side_chats[&shell.side_chat_seq]
                .state
                .read(cx)
                .selected_chat
                .clone()
        })
        .unwrap()
        .unwrap();
    assert_eq!(ScopedId::parse(&fork).unwrap().engine, fixture.key);
    fixture.wait_for_state(cx, "the fork listed under its parent", |s| {
        child_chat_rows(s, &parent, Utc::now())
            .iter()
            .any(|row| row.chat_id == fork)
    });

    // A blank side chat is minted on the parent's engine; its first send
    // creates it there.
    window
        .update(cx, |shell, _, cx| shell.create_child_chat(Some(parent.clone()), cx))
        .unwrap();
    let (blank, blank_state, composer) = window
        .update(cx, |shell, _, cx| {
            let tab = &shell.side_chats[&shell.side_chat_seq];
            let state = tab.state.clone();
            let id = state.read(cx).selected_chat.clone().unwrap();
            (id, state, tab.composer.clone())
        })
        .unwrap();
    let raw_blank = ScopedId::parse(&blank).unwrap();
    assert_eq!(raw_blank.engine, fixture.key);
    wait_until(cx, "the blank side chat's engine", |cx| {
        blank_state.read_with(cx, |s, _| {
            s.target_for_id(&blank)
                .is_ok_and(|target| target.is_connected())
        })
    });
    composer.update(cx, |composer, cx| {
        composer.input.update(cx, |input, cx| {
            input.set_text("Hello from a blank side chat", cx);
            cx.emit(ComposerInputEvent::Submitted);
        })
    });
    wait_until(cx, "the blank side chat on the paired engine", |cx| {
        let failure = composer.read_with(cx, |c, _| c.failure().map(|f| f.to_string()));
        assert!(failure.is_none(), "first send failed: {failure:?}");
        fixture
            .remote
            .workspace
            .chat(&raw_blank.raw_id)
            .unwrap()
            .is_some_and(|chat| chat.parent_chat_id.as_deref() == Some("parent"))
    });
    fixture.wait_for_state(cx, "the blank side chat listed under its parent", |s| {
        child_chat_rows(s, &parent, Utc::now())
            .iter()
            .any(|row| row.chat_id == blank)
    });
    assert!(
        fixture
            .state
            .read_with(cx, |s, _| s.chats.iter().all(|c| c.id != raw_blank.raw_id)),
        "nothing landed on the local engine"
    );
    drop(window);
    fixture.shutdown(cx);
}
