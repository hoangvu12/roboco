//! Real-engine workspace tests (ticket 07's acceptance list): every frame
//! comes from in-process `EngineCore`s served over loopback, paired through
//! ticket 06's client — no protocol mocks anywhere. Two engines exercise
//! the merged derive and per-engine routing; a second client of the same
//! engine observes the engine-synced sidebar state.

mod support;

use std::sync::Arc;
use std::time::Duration;

use roboco_engine::{EngineCore, EngineListener};
use roboco_mobile_client::{
    Client, ClientEvent, ClientError, EngineState, PairedEngine, WorkspaceSnapshot,
};

use support::{Recorder, pairing_url, serve, test_client, test_config, test_timings, wait_for_view};

/// One engine: its own directory (kept alive), core and listener.
struct Engine {
    _dir: tempfile::TempDir,
    core: EngineCore,
    listener: EngineListener,
}

impl Engine {
    async fn start() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = support::core(dir.path());
        let listener = serve(&core, dir.path()).await;
        Self {
            _dir: dir,
            core,
            listener,
        }
    }

    fn url(&self) -> String {
        pairing_url(&self.listener, self._dir.path(), None)
    }
}

/// Wait until the derived workspace satisfies `predicate`.
async fn wait_for_workspace(client: &Client, label: &str, predicate: impl Fn(&WorkspaceSnapshot) -> bool) {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if predicate(&client.workspace()) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("workspace never reached: {label}"));
}

/// Wait until the engine's link has been up for a while: the initial
/// pairing dial can flap once before the steady supervisor link settles,
/// and mutations racing that window hit a dying socket.
async fn wait_for_stable_link(client: &Client, engine_id: &str) {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            let stable = client.engine(engine_id).is_some_and(|view| {
                view.state == EngineState::Connected
                    && view
                        .last_connected_ms
                        .is_some_and(|at| support::now_ms() - at > 1_500)
            });
            if stable {
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("the engine link never stabilised");
}

/// Wait until the engine's own sidebar state (the client's fleet mirror of
/// `WatchSidebarState`) satisfies `predicate` — the authoritative check
/// that a sidebar write landed.
async fn wait_for_engine_sidebar(
    client: &Client,
    engine_id: &str,
    label: &str,
    predicate: impl Fn(&roboco_proto::SidebarStateSnapshot) -> bool,
) {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            let mut fleet = client.fleet();
            let ok = fleet
                .borrow_and_update()
                .engine(engine_id)
                .and_then(|engine| engine.sidebar.clone())
                .map(|sidebar| predicate(&sidebar))
                .unwrap_or(false);
            if ok {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("engine sidebar never reached: {label}"));
}

/// Wait until the offline cache carries the expected sidebar (the debounced
/// writer settles after the frames).
async fn wait_for_cached_sidebar(
    client: &Client,
    engine_id: &str,
    label: &str,
    predicate: impl Fn(&roboco_proto::SidebarStateSnapshot) -> bool,
) {
    let handle = client.engine_handle(engine_id).unwrap();
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if handle
                .cache()
                .load_sidebar()
                .map(|sidebar| predicate(&sidebar))
                .unwrap_or(false)
            {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("cached sidebar never reached: {label}"));
}

/// The raw engine row of one chat (for echo assertions).
fn engine_chat(core: &EngineCore, chat_id: &str) -> Option<roboco_proto::Chat> {
    core.workspace.chat(chat_id).ok().flatten()
}

/// Two paired engines in one client; the pair (A, B) with their records.
async fn two_engine_client(
    data_dir: &std::path::Path,
    recorder: Arc<Recorder>,
) -> (Engine, Engine, Arc<Client>, PairedEngine, PairedEngine) {
    let engine_a = Engine::start().await;
    let engine_b = Engine::start().await;
    let client = test_client(data_dir, Vec::new(), recorder);
    let record_a = client.pair_engine(&engine_a.url(), None).await.unwrap();
    let record_b = client.pair_engine(&engine_b.url(), None).await.unwrap();
    for (engine, record) in [(&engine_a, &record_a), (&engine_b, &record_b)] {
        wait_for_view(&client, &record.engine_id, "connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        wait_for_workspace(&client, "feed rows arrive", |snapshot| {
            snapshot
                .session(&client.scope(&record.engine_id, "same-chat"))
                .is_some()
        })
        .await;
        let _ = engine;
    }
    (engine_a, engine_b, client, record_a, record_b)
}

/// Archive a chat on engine B: only B's row changes, `archived_sessions()`
/// shows it, `WorkspaceChanged` fires (the overlay recompute — before the
/// engine echo, which changes nothing the derive can see). Unarchive
/// restores it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn archiving_on_one_engine_only_changes_that_engines_row() {
    let data_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let (mut engine_a, mut engine_b, client, _record_a, record_b) =
        two_engine_client(data_dir.path(), recorder.clone()).await;
    engine_b
        .core
        .workspace
        .create_chat("b-chat", None, Some(&engine_b.core.device_id), None, None)
        .unwrap();
    let b_chat = client.scope(&record_b.engine_id, "b-chat");
    wait_for_workspace(&client, "engine B's chat", |snapshot| {
        snapshot.session(&b_chat).is_some()
    })
    .await;
    // Engine A's rows before the mutation, for the unchanged check.
    let a_chat = client.scope(&_record_a.engine_id, "same-chat");
    let before = client.workspace();
    let a_revision = before.session(&a_chat).unwrap().revision;
    let events_before = recorder.events().len();

    client.archive_session(&b_chat).unwrap();
    wait_for_workspace(&client, "B's chat archived", |snapshot| {
        snapshot
            .archived
            .iter()
            .any(|row| row.id == b_chat && row.archived)
            && !snapshot
                .front
                .recent
                .iter()
                .any(|row| row.id == b_chat)
    })
    .await;
    // A WorkspaceChanged event fired for the overlay recompute.
    let events = recorder.events();
    assert!(
        events[events_before..]
            .iter()
            .any(|event| matches!(event, ClientEvent::WorkspaceChanged { .. })),
        "the overlay recompute must emit WorkspaceChanged: {:?}",
        events,
    );
    // Only engine B's row changed.
    let after = client.workspace();
    assert_eq!(
        after.session(&a_chat).unwrap().revision,
        a_revision,
        "engine A's row must be untouched"
    );
    assert!(
        !engine_chat(&engine_a.core, "same-chat").unwrap().archived
    );

    // Unarchive restores it (the overlay flips back, the engine follows).
    client.unarchive_session(&b_chat).unwrap();
    wait_for_workspace(&client, "B's chat restored", |snapshot| {
        !snapshot
            .archived
            .iter()
            .any(|row| row.id == b_chat)
            && snapshot.front.recent.iter().any(|row| row.id == b_chat)
    })
    .await;
    client.shutdown();
    engine_a.listener.stop().await;
    engine_b.listener.stop().await;
    engine_a.core.shutdown().await;
    engine_b.core.shutdown().await;
}

/// Rename trims the title; a blank (whitespace-only) title is a no-op that
/// sends nothing.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rename_trims_and_a_blank_title_is_a_noop() {
    let data_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let (mut _engine_a, mut _engine_b, client, _record_a, record_b) =
        two_engine_client(data_dir.path(), recorder).await;
    let b_chat = client.scope(&record_b.engine_id, "same-chat");
    client.rename_session(&b_chat, "  Trimmed title  ").unwrap();
    wait_for_workspace(&client, "title trimmed", |snapshot| {
        snapshot.session(&b_chat).is_some_and(|row| {
            row.title == "Trimmed title" && row.has_title
        })
    })
    .await;
    // A blank title is a no-op returning Ok.
    client.rename_session(&b_chat, "   ").unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    wait_for_workspace(&client, "title unchanged after blank rename", |snapshot| {
        snapshot
            .session(&b_chat)
            .is_some_and(|row| row.title == "Trimmed title")
    })
    .await;
    client.shutdown();
    _engine_a.listener.stop().await;
    _engine_b.listener.stop().await;
    _engine_a.core.shutdown().await;
    _engine_b.core.shutdown().await;
}

/// `mark_seen` turns a Completed row Idle immediately (the local stamp,
/// before the engine echo) and survives the echo (the engine's
/// `last_seen_at` reaches the stamp, the overlay drops, the row stays
/// Idle).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn mark_seen_clears_completed_immediately_and_survives_the_echo() {
    let data_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let (mut _engine_a, mut engine_b, client, _record_a, record_b) =
        two_engine_client(data_dir.path(), recorder).await;
    // A finished-but-unseen chat on engine B reads Completed.
    engine_b
        .core
        .workspace
        .create_chat("seeded", None, Some(&engine_b.core.device_id), None, None)
        .unwrap();
    engine_b
        .core
        .workspace
        .set_chat_activity("seeded", Some(1_000), None)
        .unwrap();
    let seeded = client.scope(&record_b.engine_id, "seeded");
    wait_for_workspace(&client, "row reads Completed", |snapshot| {
        snapshot.session(&seeded).is_some_and(|row| {
            row.indicator == roboco_proto::ChatIndicator::Completed && row.unseen
        })
    })
    .await;
    // Fire-and-forget: never fails, takes effect immediately.
    client.mark_seen(&seeded);
    wait_for_workspace(&client, "row reads Idle", |snapshot| {
        snapshot
            .session(&seeded)
            .is_some_and(|row| row.indicator == roboco_proto::ChatIndicator::Idle)
    })
    .await;
    // The engine echo lands (its own last_seen_at stamp) — the row stays
    // Idle and the chat row is now seen engine-side.
    wait_for_fleet_chat_seen(&client, &record_b.engine_id, "seeded").await;
    wait_for_workspace(&client, "row still Idle after the echo", |snapshot| {
        snapshot
            .session(&seeded)
            .is_some_and(|row| row.indicator == roboco_proto::ChatIndicator::Idle)
    })
    .await;
    client.shutdown();
    _engine_a.listener.stop().await;
    engine_b.listener.stop().await;
    _engine_a.core.shutdown().await;
    engine_b.core.shutdown().await;
}

/// Wait until the engine's own chat row carries a seen marker (the echo).
async fn wait_for_fleet_chat_seen(client: &Client, engine_id: &str, chat_id: &str) {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            let seen = client
                .fleet()
                .borrow_and_update()
                .engine(engine_id)
                .map(|engine| {
                    engine
                        .rows
                        .chats
                        .iter()
                        .find(|chat| chat.id == chat_id)
                        .and_then(|chat| chat.last_seen_at)
                        .is_some()
                })
                .unwrap_or(false);
            if seen {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("the engine echo (last_seen_at) never arrived");
}

/// The sidebar mutations reach the engine's own store (a second client of
/// the same engine sees them over `WatchSidebarState`): pin, move, section
/// assignment and the engine-synced fold.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pin_and_section_ops_reach_the_engine_and_a_second_client() {
    let data_dir = tempfile::tempdir().unwrap();
    let second_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let (mut engine, mut _b, client, record, _record_b) = {
        let engine_a = Engine::start().await;
        let engine_b = Engine::start().await;
        let client = test_client(data_dir.path(), Vec::new(), recorder);
        let record = client.pair_engine(&engine_a.url(), None).await.unwrap();
        let record_b = client.pair_engine(&engine_b.url(), None).await.unwrap();
        wait_for_view(&client, &record.engine_id, "A connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        wait_for_view(&client, &record_b.engine_id, "B connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        (engine_a, engine_b, client, record, record_b)
    };
    // Two more chats on engine A to pin and reorder.
    engine
        .core
        .workspace
        .create_chat("pin-1", None, Some(&engine.core.device_id), None, None)
        .unwrap();
    engine
        .core
        .workspace
        .create_chat("pin-2", None, Some(&engine.core.device_id), None, None)
        .unwrap();
    let pin_1 = client.scope(&record.engine_id, "pin-1");
    let pin_2 = client.scope(&record.engine_id, "pin-2");
    wait_for_workspace(&client, "chats to pin", |snapshot| {
        snapshot.session(&pin_1).is_some() && snapshot.session(&pin_2).is_some()
    })
    .await;
    // The engine's sidebar state is known (its first frame arrived) and
    // the link has settled past the pairing dial's flap window.
    wait_for_view(&client, &record.engine_id, "sidebar ready", |view| {
        !view.from_cache
    })
    .await;
    wait_for_stable_link(&client, &record.engine_id).await;

    // Pin both; the pinned list shows stored order.
    client.pin_session(&pin_1).unwrap();
    client.pin_session(&pin_2).unwrap();
    wait_for_workspace(&client, "both pinned", |snapshot| {
        snapshot.front.pinned.len() == 2
            && snapshot.front.pinned[0].id == pin_1
            && snapshot.front.pinned[1].id == pin_2
    })
    .await;
    // The engine's own store carries the raw ids under the LOCAL profile
    // key ("local" for a local-scope engine) — the second client sees it.
    let (second, second_record) = {
        let url = engine.url();
        let client = test_client(second_dir.path(), Vec::new(), Arc::new(Recorder::default()));
        let record = client.pair_engine(&url, None).await.unwrap();
        (client, record)
    };
    // Each pairing mints its own engine key: the second client's fleet
    // snapshot keys by ITS id.
    let engine_id = second_record.engine_id.clone();
    wait_for_view(&second, &engine_id, "second client connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    wait_for_engine_sidebar(&second, &engine_id, "pins under local", |sidebar| {
        sidebar
            .pins_by_profile
            .get("local")
            .map(|ids| ids.as_slice() == ["pin-1".to_owned(), "pin-2".to_owned()])
            .unwrap_or(false)
    })
    .await;

    // move_pin: the surviving `before` anchor wins (port of the desktop's
    // projection): pin-2 moves in front of pin-1.
    client
        .move_pin(&pin_2, None, Some(&pin_1))
        .unwrap();
    wait_for_workspace(&client, "pin order moved", |snapshot| {
        snapshot.front.pinned.len() == 2
            && snapshot.front.pinned[0].id == pin_2
            && snapshot.front.pinned[1].id == pin_1
    })
    .await;
    wait_for_engine_sidebar(&second, &engine_id, "engine pin order", |sidebar| {
        sidebar
            .pins_by_profile
            .get("local")
            .map(|ids| ids.as_slice() == ["pin-2".to_owned(), "pin-1".to_owned()])
            .unwrap_or(false)
    })
    .await;

    // A section: create, collapse, then assign pin-1 into it — the chat
    // leaves Recent (and its pin hides), the section un-collapses.
    let section = client.create_section("Work", None).unwrap();
    client.set_section_collapsed(&section, true).unwrap();
    wait_for_workspace(&client, "section collapsed", |snapshot| {
        snapshot
            .front
            .sections
            .iter()
            .any(|s| s.id == section && s.collapsed)
    })
    .await;
    // The engine-synced fold is visible to the second client BEFORE the
    // assign un-collapses it (a fresh frame carries collapsed = true).
    wait_for_engine_sidebar(&second, &engine_id, "second client sees the fold", |sidebar| {
        sidebar
            .sections_by_profile
            .get("local")
            .and_then(|sections| sections.first())
            .is_some_and(|section| section.collapsed)
    })
    .await;
    client.assign_section(&pin_1, Some(&section)).unwrap();
    wait_for_workspace(&client, "chat assigned into the section", |snapshot| {
        let in_section = snapshot
            .front
            .sections
            .iter()
            .find(|s| s.id == section)
            .map(|s| !s.collapsed && s.sessions.iter().any(|r| r.id == pin_1));
        in_section == Some(true)
            && !snapshot.front.pinned.iter().any(|r| r.id == pin_1)
            && !snapshot.front.recent.iter().any(|r| r.id == pin_1)
    })
    .await;
    // The second client sees the engine-synced fold: its own copy of the
    // section is un-collapsed and carries the chat.
    wait_for_engine_sidebar(&second, &engine_id, "section un-collapsed", |sidebar| {
        sidebar
            .sections_by_profile
            .get("local")
            .and_then(|sections| sections.first())
            .map(|section| {
                section.name == "Work"
                    && !section.collapsed
                    && section.session_ids == ["pin-1".to_owned()]
            })
            .unwrap_or(false)
    })
    .await;

    client.shutdown();
    second.shutdown();
    engine.listener.stop().await;
    _b.listener.stop().await;
    engine.core.shutdown().await;
    _b.core.shutdown().await;
}


/// A mutation on a chat whose engine is stopped returns `HostUnavailable`
/// immediately and leaves no overlay (the revision and the pin list are
/// untouched).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_mutation_on_a_stopped_engine_fails_fast_with_no_overlay() {
    let data_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let mut engine = Engine::start().await;
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&engine.url(), None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    wait_for_workspace(&client, "rows arrive", |snapshot| {
        snapshot
            .session(&client.scope(&record.engine_id, "same-chat"))
            .is_some()
    })
    .await;
    // Stop the engine and let the client notice.
    engine.listener.stop().await;
    wait_for_view(&client, &record.engine_id, "reconnecting", |view| {
        view.state == EngineState::Reconnecting
    })
    .await;
    // Settle: the workspace stops changing.
    tokio::time::sleep(Duration::from_millis(300)).await;
    let before = client.workspace();
    let chat = client.scope(&record.engine_id, "same-chat");
    let revision_before = client.workspace_revision();
    match client.pin_session(&chat) {
        Err(ClientError::HostUnavailable { message }) => {
            assert_eq!(message, "Engine is offline; reconnecting");
        }
        other => panic!("a stopped engine must fail with HostUnavailable, got {other:?}"),
    }
    tokio::time::sleep(Duration::from_millis(300)).await;
    // No overlay: the revision and the pinned list are untouched.
    assert_eq!(client.workspace_revision(), revision_before);
    assert!(client.workspace().front.pinned.is_empty());
    assert_eq!(before.front.pinned, client.workspace().front.pinned);
    client.shutdown();
    engine.core.shutdown().await;
}

/// Offline: with the engine down, a fresh client's `workspace()` still
/// shows the pins and sections (from `sidebar.json`), `pins_ready` is
/// false, and pin ops fail with `HostUnavailable`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn offline_cache_serves_pins_and_sections_but_pins_are_not_ready() {
    let data_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let mut engine = Engine::start().await;
    let client = test_client(data_dir.path(), Vec::new(), recorder.clone());
    let record = client.pair_engine(&engine.url(), None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    wait_for_view(&client, &record.engine_id, "sidebar frame", |view| {
        !view.from_cache
    })
    .await;
    let chat = client.scope(&record.engine_id, "same-chat");
    wait_for_workspace(&client, "rows arrive", |snapshot| {
        snapshot.session(&chat).is_some()
    })
    .await;
    wait_for_stable_link(&client, &record.engine_id).await;
    client.pin_session(&chat).unwrap();
    let section = client.create_section("Keep", None).unwrap();
    client.assign_section(&chat, Some(&section)).unwrap();
    // The engine's own state confirms both writes landed.
    wait_for_engine_sidebar(&client, &record.engine_id, "pinned+sectioned", |sidebar| {
        sidebar
            .sections_by_profile
            .get("local")
            .is_some_and(|sections| {
                sections.iter().any(|s| {
                    s.name == "Keep" && s.session_ids == ["same-chat".to_owned()]
                })
            })
            && sidebar
                .pins_by_profile
                .get("local")
                .is_some_and(|pins| pins == &["same-chat".to_owned()])
    })
    .await;
    // The debounced cache writer settles, then flush.
    wait_for_cached_sidebar(&client, &record.engine_id, "cached sidebar", |sidebar| {
        sidebar
            .sections_by_profile
            .get("local")
            .is_some_and(|sections| sections.iter().any(|s| s.name == "Keep"))
    })
    .await;
    client
        .engine_handle(&record.engine_id)
        .unwrap()
        .cache()
        .flush()
        .await;
    client.shutdown();
    engine.listener.stop().await;

    // A fresh client from the same data dir: the list paints from cache.
    let recorder = Arc::new(Recorder::default());
    let offline = Client::new_with_timings(
        test_config(data_dir.path()),
        vec![record.clone()],
        recorder,
        test_timings(),
    )
    .unwrap();
    let chat = offline.scope(&record.engine_id, "same-chat");
    wait_for_workspace(&offline, "pins from cache", |snapshot| {
        snapshot
            .front
            .sections
            .iter()
            .any(|s| s.name == "Keep" && s.sessions.iter().any(|r| r.id == chat))
    })
    .await;
    let snapshot = offline.workspace();
    assert!(!snapshot.pins_ready, "the engine is down: pins are not ready");
    assert!(!snapshot.synced, "no live frame arrived");
    // Pin ops fail with HostUnavailable (the sidebar state IS known — the
    // failure is the connection, not the readiness).
    match offline.pin_session(&chat) {
        Err(ClientError::HostUnavailable { message }) => {
            assert_eq!(message, "Engine is offline; reconnecting");
        }
        other => panic!("an offline engine must fail with HostUnavailable, got {other:?}"),
    }
    offline.shutdown();
    engine.core.shutdown().await;
}

/// PR badge smoke: subscribing `WatchCheckoutChangeRequest` with a temp git
/// repo cwd does not error (the engine accepts the subscription — the ack
/// handshake completes). GitHub resolution needs network access, so the
/// badge itself is unit-tested in `workspace::change_requests`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pr_watch_subscription_with_a_temp_git_repo_does_not_error() {
    let data_dir = tempfile::tempdir().unwrap();
    let repo = tempfile::tempdir().unwrap();
    assert!(std::process::Command::new("git")
        .args(["init", "-q"])
        .arg(repo.path())
        .status()
        .expect("git is available")
        .success());
    let recorder = Arc::new(Recorder::default());
    let mut engine = Engine::start().await;
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&engine.url(), None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // A chat whose cwd is the repo root makes the checkout known to the
    // engine's `change_request_root`.
    engine
        .core
        .workspace
        .create_chat(
            "pr-chat",
            None,
            Some(&engine.core.device_id),
            None,
            Some(repo.path().to_string_lossy().into_owned()),
        )
        .unwrap();
    let handle = client.engine_handle(&record.engine_id).unwrap();
    let connection = handle.transcript_connection().await.unwrap();
    let params = serde_json::json!({
        "cwd": repo.path().to_string_lossy(),
        "branch": "master",
    });
    let subscription = tokio::time::timeout(
        Duration::from_secs(10),
        connection
            .client()
            .subscribe_checked(roboco_rpc::methods::WATCH_CHECKOUT_CHANGE_REQUEST, params),
    )
    .await
    .expect("the subscribe handshake completes")
    .expect("the engine accepted the WatchCheckoutChangeRequest subscription");
    drop(subscription);
    drop(connection);
    client.shutdown();
    engine.listener.stop().await;
    engine.core.shutdown().await;
}
