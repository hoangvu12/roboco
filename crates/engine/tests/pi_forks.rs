//! Pi native forks through the REAL engine surfaces (t3-orchestration-v2
//! ticket 19): forking a Pi chat continues the source's real session. The
//! source turn's driver stamps a provider turn ref on the assistant entry
//! (the session file plus the turn's user entry id, `get_entries` after the
//! settle), the ForkSideChat RPC records the native-fork intent from it,
//! and the fork's first run performs the `pi --fork` launch plus the
//! exclusive fork RPC through the fixture's fork process — nothing is
//! pasted. A boundary the session tree no longer holds falls back to the
//! budgeted paste. Prior art: `pi_resume.rs` / `pi_steering.rs` drive the
//! real `PiHarness` against the fixture RPC script; `side_chats.rs` covers
//! the harness-agnostic fork RPC plumbing with the mock harness.
#![cfg(unix)]

use std::{path::PathBuf, sync::Arc, time::Duration};

use roboco_doc::{MessagePart, MessageRole, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::PiHarness;
use roboco_proto::{HarnessId, SessionStatus};

const SRC: &str = "pi-fork-src";
const SIDE: &str = "pi-fork-side";

fn fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../harness/tests/fixtures/fake-pi-rpc.sh")
}

/// Assemble a full engine around the REAL Pi harness pointed at the fixture
/// RPC process (short interrupt graces so a stuck scenario fails fast
/// instead of hanging the suite).
fn assemble(dir: &std::path::Path) -> EngineCore {
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(
        PiHarness::new()
            .with_executable(fixture_path())
            .with_graces(Duration::from_millis(150), Duration::from_millis(300)),
    ));
    EngineCore::assemble(dir, Arc::new(registry), HarnessId::Pi).expect("engine core assembles")
}

/// A pi chat in a real temp cwd (the fixture writes its session file and
/// runs there), pre-titled so the auto-titler never spawns a fixture run of
/// its own.
async fn create_chat(core: &EngineCore, chat: &str, cwd: &str) {
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": chat,
                "deviceId": core.device_id,
                "cwd": cwd,
                "config": {
                    "harness": "pi",
                    "model": null,
                    "reasoning": null,
                    "sandbox": "workspace-write",
                },
            }),
        )
        .await
        .expect("createChat");
    core.workspace
        .rename_chat(chat, "Pre-titled")
        .expect("rename chat");
}

fn texts_of(core: &EngineCore, chat: &str, role: MessageRole) -> Vec<String> {
    core.doc_host
        .open(chat)
        .expect("chat doc")
        .doc()
        .read_entries()
        .unwrap_or_default()
        .into_iter()
        .filter(|entry| entry.role == role)
        .map(|entry| {
            entry
                .parts
                .iter()
                .filter_map(|part| match part {
                    MessagePart::Text { text, .. } => Some(text.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("")
        })
        .collect()
}

fn error_texts_of(core: &EngineCore, chat: &str) -> Vec<String> {
    core.doc_host
        .open(chat)
        .expect("chat doc")
        .doc()
        .read_entries()
        .unwrap_or_default()
        .into_iter()
        .flat_map(|entry| entry.parts)
        .filter_map(|part| match part {
            MessagePart::Error { message, .. } => Some(message),
            _ => None,
        })
        .collect()
}

async fn wait_for<F>(mut predicate: F, what: &str)
where
    F: FnMut() -> bool,
{
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    while !predicate() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

async fn wait_idle(core: &EngineCore, chat: &str) {
    wait_for(
        || {
            core.sessions
                .session_status(chat)
                .map(|s| s.status)
                .is_some_and(|status| status == SessionStatus::Idle)
        },
        "the turn to settle",
    )
    .await;
}

async fn fork_side_chat(core: &EngineCore, side: &str, source: &str) {
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": side, "sourceChatId": source }),
        )
        .await
        .expect("the fork RPC succeeded");
}

/// A hand-built message entry (the fallback test writes a source doc the
/// driver never ran).
fn message(id: &str, role: MessageRole, text: &str) -> SessionMessageEntry {
    SessionMessageEntry {
        origin_chat_id: None,
        duration_ms: None,
        provider_turn: None,
        id: id.into(),
        role,
        parts: vec![MessagePart::Text {
            id: format!("{id}-text"),
            text: text.into(),
        }],
        created_at: 1,
        device_id: "device".into(),
        status: Some(roboco_doc::MessageStatus::Complete),
        continuation_of: None,
    }
}

/// Forking a Pi chat continues the source's REAL session: the source
/// turn's ref drives the fork, the fork process launches with `--fork` and
/// sends the exclusive fork RPC at the turn after the boundary (the
/// fixture asserts both through markers, plus that the main run resumes
/// the FORKED session file with a BARE prompt — a pasted history would
/// fail its scenario), and the forked chat's own turns then run normally
/// on the forked session.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_pi_fork_continues_the_source_session_and_pastes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("chat");
    std::fs::create_dir_all(&cwd).unwrap();
    let core = assemble(&dir.path().join("data"));
    create_chat(&core, SRC, &cwd.display().to_string()).await;

    // The source turn: the driver stamps the boundary ref on the reply.
    core.doc_host
        .queue_message(SRC, "hello", Vec::new())
        .expect("queue the source turn");
    wait_for(
        || {
            texts_of(&core, SRC, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("reply:hello"))
        },
        "the source reply to stream",
    )
    .await;
    wait_idle(&core, SRC).await;
    let reference = core
        .doc_host
        .open(SRC)
        .expect("chat doc")
        .doc()
        .read_entries()
        .expect("entries")
        .into_iter()
        .find(|entry| entry.role == MessageRole::Assistant)
        .and_then(|entry| entry.provider_turn)
        .expect("the driver stamped a turn ref on the reply");
    assert_eq!(reference.harness, HarnessId::Pi);
    assert!(std::path::Path::new(&reference.session_id).is_file(), "{reference:?}");
    assert_eq!(reference.turn_id, "u1");

    // Fork it, then run the fork's first turn.
    fork_side_chat(&core, SIDE, SRC).await;
    core.doc_host
        .queue_message(SIDE, "fork-turn", Vec::new())
        .expect("queue the fork turn");
    wait_for(
        || {
            texts_of(&core, SIDE, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("reply:fork-turn"))
        },
        "the forked reply to stream",
    )
    .await;
    wait_idle(&core, SIDE).await;

    // The fork process ran with --fork and sent the RPC fork at the turn
    // AFTER the boundary (the source's first turn): the markers prove both.
    let forked_path = cwd.join(".pi-fixture-forked.jsonl");
    let forked = std::fs::read_to_string(&forked_path).expect("the forked session file");
    assert!(forked.contains("fork-launch:"), "{forked}");
    assert!(forked.contains("fork-rpc:u2"), "{forked}");

    // The copied boundary keeps its ref, and the fork's own reply carries a
    // ref naming the FORKED session (its own process mints fresh entry ids).
    let side_entries = core
        .doc_host
        .open(SIDE)
        .expect("chat doc")
        .doc()
        .read_entries()
        .expect("entries");
    assert!(
        side_entries
            .iter()
            .any(|entry| entry.provider_turn.as_ref() == Some(&reference)),
        "the copied boundary keeps its ref: {side_entries:?}"
    );
    let own = side_entries
        .into_iter()
        .find(|entry| {
            entry.role == MessageRole::Assistant
                && entry
                    .provider_turn
                    .as_ref()
                    .is_some_and(|r| r.session_id.ends_with(".pi-fixture-forked.jsonl"))
        })
        .expect("the forked turn stamped its own ref")
        .provider_turn
        .expect("ref");
    assert_eq!(own.turn_id, "u1");

    // The intent is spent: the next turn is a normal turn on the forked
    // session — no second fork launch (the marker count proves it).
    core.doc_host
        .queue_message(SIDE, "fork-turn", Vec::new())
        .expect("queue the second fork turn");
    wait_for(
        || {
            texts_of(&core, SIDE, MessageRole::Assistant)
                .iter()
                .filter(|t| t.as_str() == "reply:fork-turn")
                .count()
                == 2
        },
        "the second forked turn to stream",
    )
    .await;
    wait_idle(&core, SIDE).await;
    let forked = std::fs::read_to_string(&forked_path).expect("the forked session file");
    assert_eq!(forked.matches("fork-launch:").count(), 1, "{forked}");
    assert_eq!(forked.matches("fork-rpc:").count(), 1, "{forked}");
    core.shutdown().await;
}

/// A fork whose boundary ref names no entry the source session holds (a
/// stale boundary, an old chat or a re-rooted branch) never re-roots: the
/// fork falls back to a fresh session with the budgeted transcript in
/// front of the prompt (the fixture asserts the pasted history and refuses
/// a resume), the refusal surfaces as an error chip, and the turn still
/// completes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_pi_fork_without_a_usable_boundary_falls_back_to_the_budgeted_paste() {
    let dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("chat");
    std::fs::create_dir_all(&cwd).unwrap();
    let core = assemble(&dir.path().join("data"));
    create_chat(&core, SRC, &cwd.display().to_string()).await;

    // A source doc the driver never ran: the boundary entry carries a ref
    // whose turn id the fixture session tree does not hold.
    let stale = cwd.join("stale-session.jsonl");
    std::fs::write(&stale, b"fixture session\n").unwrap();
    let source = core.doc_host.open(SRC).expect("chat doc");
    source
        .doc()
        .push_message(&message("u1", MessageRole::User, "Remember PINEAPPLE"))
        .expect("user entry");
    let mut boundary = message("a1", MessageRole::Assistant, "I remember PINEAPPLE");
    boundary.provider_turn = Some(roboco_proto::ProviderTurnRef {
        harness: HarnessId::Pi,
        session_id: stale.display().to_string(),
        turn_id: "u-gone".into(),
    });
    source.doc().push_message(&boundary).expect("boundary");

    fork_side_chat(&core, SIDE, SRC).await;
    core.doc_host
        .queue_message(SIDE, "fork-fallback", Vec::new())
        .expect("queue the fork turn");
    wait_for(
        || {
            texts_of(&core, SIDE, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("reply:fork-fallback"))
        },
        "the fallback reply to stream",
    )
    .await;
    wait_idle(&core, SIDE).await;

    // The fork process DID run (the launch marker) but never re-rooted (no
    // fork RPC marker): the stale boundary refused the fork.
    let forked_path = cwd.join(".pi-fixture-forked.jsonl");
    let forked = std::fs::read_to_string(&forked_path).expect("the forked session file");
    assert!(forked.contains("fork-launch:"), "{forked}");
    assert!(!forked.contains("fork-rpc:"), "{forked}");

    // The refusal surfaced as an error chip on the forked chat.
    assert!(
        error_texts_of(&core, SIDE)
            .iter()
            .any(|message| message.contains("pi could not fork")),
        "the fork refusal surfaced: {:?}",
        error_texts_of(&core, SIDE)
    );
    core.shutdown().await;
}
