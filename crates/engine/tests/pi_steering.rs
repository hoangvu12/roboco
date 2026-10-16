//! Pi steers mid-turn through the REAL engine surfaces (orchestration v2,
//! ticket 03): a message sent to a working Pi chat reaches the fake pi at
//! its next step boundary — steered into the live run, never held to turn
//! end — and a Stop with steers pi never consumed cancels them exactly like
//! any other held send. Prior art: `pi_resume.rs` drives the real
//! `PiHarness` against the fixture RPC script through engine dispatch;
//! `message_queue.rs` drives the queue + steer surfaces. The fixture's
//! `steer-flow` scenario is the ordering oracle: it cannot settle before
//! the steer arrives, so a message held to turn end would hang the run.
#![cfg(unix)]

use std::{path::PathBuf, sync::Arc, time::Duration};

use roboco_doc::{MessagePart, MessageRole, SessionCommandPayload, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::PiHarness;
use roboco_proto::{HarnessId, SessionStatus};

const CHAT: &str = "pi-steer";

fn fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../harness/tests/fixtures/fake-pi-rpc.sh")
}

/// Assemble a full engine around the REAL Pi harness pointed at the fixture
/// RPC process (short interrupt graces so a stuck scenario fails fast
/// instead of hanging the suite).
fn assemble(dir: &std::path::Path, agent_dir: &std::path::Path) -> EngineCore {
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(
        PiHarness::new()
            .with_executable(fixture_path())
            .with_agent_dir(agent_dir)
            .with_graces(Duration::from_millis(150), Duration::from_millis(300)),
    ));
    EngineCore::assemble(dir, Arc::new(registry), HarnessId::Pi).expect("engine core assembles")
}

/// A pi chat in a real temp cwd (the fixture writes its session file and
/// runs there), pre-titled so the auto-titler never spawns a fixture run of
/// its own.
async fn create_pi_chat(core: &EngineCore, cwd: &str) {
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": CHAT,
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
    core.workspace.rename_chat(CHAT, "Pre-titled").expect("rename chat");
}

fn entries(core: &EngineCore) -> Vec<SessionMessageEntry> {
    core.doc_host
        .open(CHAT)
        .expect("chat doc")
        .doc()
        .read_entries()
        .unwrap_or_default()
}

fn texts_of(core: &EngineCore, role: MessageRole) -> Vec<String> {
    entries(core)
        .iter()
        .filter(|e| e.role == role)
        .map(|e| {
            e.parts
                .iter()
                .filter_map(|p| match p {
                    MessagePart::Text { text, .. } => Some(text.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("")
        })
        .collect()
}

fn queue_texts(core: &EngineCore) -> Vec<String> {
    core.doc_host
        .open(CHAT)
        .expect("chat doc")
        .doc()
        .read_queue()
        .unwrap_or_default()
        .into_iter()
        .map(|row| row.text)
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

/// A message sent to a working Pi chat from the composer's queue row (the
/// "Steer" action) reaches pi at its next step boundary: the user message
/// lands in the transcript and the row leaves the queue WHILE the turn is
/// still in flight, and the message folds into that turn — it never waits
/// for the run to end and never starts a follow-up turn of its own. The
/// fixture's `steer-flow` script structurally guarantees the wire order
/// (it reads the steer before it settles); a held message would deadlock
/// the scenario and this test would time out instead.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_message_sent_mid_run_is_steered_not_held_to_turn_end() {
    let dir = tempfile::tempdir().unwrap();
    let agent_dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("chat");
    std::fs::create_dir_all(&cwd).unwrap();
    let core = assemble(&dir.path().join("data"), agent_dir.path());
    create_pi_chat(&core, &cwd.display().to_string()).await;

    // The opening turn: the fixture streams its first segment and then
    // waits for the steer — the window where the message must go IN.
    core.doc_host
        .queue_message(CHAT, "steer-flow", Vec::new())
        .expect("queue opening");
    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("first part "))
        },
        "the opening turn to stream",
    )
    .await;
    assert!(core.sessions.turn_in_flight(CHAT));

    // The composer path while busy: the row lands in the queue, then its
    // primary action — Steer, because pi is a step-boundary harness —
    // routes it into the live run.
    let row = core
        .doc_host
        .queue_message(CHAT, "steered text", Vec::new())
        .expect("queue the mid-run message");
    assert_eq!(queue_texts(&core), vec!["steered text"]);
    let sent = core.doc_host.steer_queued_now(CHAT, &row).await.expect("steer now");
    assert!(sent);

    // The message reached the transcript, the row left the queue, and the
    // turn is STILL in flight (the fixture pauses after the queued ack, so
    // this window is wide): steered, not held to turn end.
    wait_for(
        || {
            texts_of(&core, MessageRole::User).iter().any(|m| m == "steered text")
                && queue_texts(&core).is_empty()
        },
        "the steered message to land in the transcript",
    )
    .await;
    assert!(
        core.sessions.turn_in_flight(CHAT),
        "the steer must land while the turn is still working"
    );
    assert!(
        !texts_of(&core, MessageRole::Assistant)
            .iter()
            .any(|t| t.contains("reply-to-steer")),
        "the run cannot have answered the steer before accepting it"
    );

    // The whole exchange stays one turn: the steer folds into the live run
    // and pi answers it there (never a fresh follow-up turn, which the
    // fixture would answer as its own reply).
    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("reply-to-steer"))
        },
        "the steered reply to stream",
    )
    .await;
    wait_for(
        || !core.sessions.turn_in_flight(CHAT),
        "the turn to settle",
    )
    .await;
    assert_eq!(
        texts_of(&core, MessageRole::User),
        vec!["steer-flow", "steered text"]
    );
    assert!(
        !texts_of(&core, MessageRole::Assistant)
            .iter()
            .any(|t| t.contains("reply:steered text")),
        "the steered message must not re-run as its own turn"
    );
    assert_eq!(queue_texts(&core), Vec::<String>::new());
    assert_eq!(
        core.sessions.session_status(CHAT).map(|s| s.status),
        Some(SessionStatus::Idle)
    );
    core.shutdown().await;
}

/// The MCP `send_message` auto-mode route: a `Steer` command queued while
/// pi works goes through the command log and `deliver_prompt`, which — for
/// a step-boundary harness — routes it into the live run instead of holding
/// it to turn end (the pre-flip behavior this ticket replaces). Same
/// fixture ordering oracle: the scenario settles only after the steer.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_mcp_steer_command_reaches_the_live_run() {
    let dir = tempfile::tempdir().unwrap();
    let agent_dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("chat");
    std::fs::create_dir_all(&cwd).unwrap();
    let core = assemble(&dir.path().join("data"), agent_dir.path());
    create_pi_chat(&core, &cwd.display().to_string()).await;

    core.doc_host
        .queue_message(CHAT, "steer-flow", Vec::new())
        .expect("queue opening");
    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("first part "))
        },
        "the opening turn to stream",
    )
    .await;

    // The exact durable command the MCP server queues for a working chat
    // (send_message auto mode resolves to a Steer command).
    core.doc_host
        .queue_command(
            CHAT,
            SessionCommandPayload::Steer {
                prompt: "steered text".into(),
                message_id: Some("mcp-steer-1".into()),
            },
        )
        .expect("queue the steer command");

    // Steered into the live run: the message lands in the transcript while
    // the turn is still working, and never sits in the queue as a held row.
    wait_for(
        || {
            texts_of(&core, MessageRole::User).iter().any(|m| m == "steered text")
                && queue_texts(&core).is_empty()
        },
        "the steered message to land in the transcript",
    )
    .await;
    assert!(core.sessions.turn_in_flight(CHAT));

    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("reply-to-steer"))
        },
        "the steered reply to stream",
    )
    .await;
    wait_for(
        || !core.sessions.turn_in_flight(CHAT),
        "the turn to settle",
    )
    .await;
    assert_eq!(
        texts_of(&core, MessageRole::User),
        vec!["steer-flow", "steered text"]
    );
    assert!(!texts_of(&core, MessageRole::Assistant)
        .iter()
        .any(|t| t.contains("reply:steered text")));
    core.shutdown().await;
}

/// Stop with steers pi never consumed: the driver clears pi queue before
/// the abort (the fixture asserts the wire order and the stranded text),
/// the turn ends interrupted, and the message is handled exactly like a
/// held send cancelled by Stop — it stays in the transcript, unrun, and no
/// fresh runtime picks it back up.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stop_cancels_unconsumed_steers_like_any_held_send() {
    let dir = tempfile::tempdir().unwrap();
    let agent_dir = tempfile::tempdir().unwrap();
    let cwd = dir.path().join("chat");
    std::fs::create_dir_all(&cwd).unwrap();
    let core = assemble(&dir.path().join("data"), agent_dir.path());
    create_pi_chat(&core, &cwd.display().to_string()).await;

    core.doc_host
        .queue_message(CHAT, "stop-clears-queue", Vec::new())
        .expect("queue opening");
    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("working"))
        },
        "the opening turn to stream",
    )
    .await;

    // The steer is accepted into pi queue but never consumed (the fixture
    // streams its marker and waits for the Stop).
    let row = core
        .doc_host
        .queue_message(CHAT, "stranded steer", Vec::new())
        .expect("queue the steer");
    let sent = core.doc_host.steer_queued_now(CHAT, &row).await.expect("steer now");
    assert!(sent);
    wait_for(
        || {
            texts_of(&core, MessageRole::Assistant)
                .iter()
                .any(|t| t.contains("steer queued"))
        },
        "pi to accept the steer into its queue",
    )
    .await;

    core.doc_host
        .queue_command(CHAT, SessionCommandPayload::Interrupt {})
        .expect("queue interrupt");
    wait_for(
        || !core.sessions.turn_in_flight(CHAT),
        "the interrupted turn to settle",
    )
    .await;

    // The message was sent — it is in the transcript, like every held send
    // Stop cancels — and it never runs: no reply, no re-dispatch, no new
    // turn. The fixture asserts clear_queue carried the stranded text away
    // BEFORE the abort.
    wait_for(
        || texts_of(&core, MessageRole::User).iter().any(|m| m == "stranded steer"),
        "the cancelled message to stay in the transcript",
    )
    .await;
    assert!(
        !texts_of(&core, MessageRole::Assistant)
            .iter()
            .any(|t| t.contains("reply:")),
        "the cancelled steer must never run"
    );
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert!(
        !texts_of(&core, MessageRole::Assistant)
            .iter()
            .any(|t| t.contains("reply:")),
        "no fresh runtime may pick the cancelled steer back up"
    );
    assert!(!core.sessions.turn_in_flight(CHAT));
    assert_eq!(
        core.sessions.session_status(CHAT).map(|s| s.status),
        Some(SessionStatus::Idle)
    );
    assert_eq!(queue_texts(&core), Vec::<String>::new());
    core.shutdown().await;
}
