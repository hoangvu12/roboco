//! Pi driver integration tests against a real Rust RPC peer
//! (`harness-pi-fixture`, built from `tests/fixtures/pi_rpc.rs`) — the
//! Windows-portable counterpart to the sh/python `fake-pi-rpc.sh` suite
//! (`tests/pi.rs`): no POSIX shell, native stdio, real threads for the
//! mid-run shapes. Gated on the `native-fixture` feature so a normal build
//! never compiles the fixture binary.

#![cfg(feature = "native-fixture")]

use std::time::Duration;

use futures::StreamExt;
use tokio::sync::{mpsc, oneshot};

use roboco_harness::{CancellationToken, Harness, PiHarness, RunControls, SteerMessage};
use roboco_proto::{AgentEvent, DoneStatus, RunRequest, SandboxLevel};

fn harness() -> PiHarness {
    PiHarness::new()
        .with_executable(env!("CARGO_BIN_EXE_harness-pi-fixture"))
        // Never consult the developer's own Pi settings: a real
        // `~/.pi/settings.json` would read as a configured steering mode.
        .with_agent_dir(
            std::path::Path::new(env!("CARGO_TARGET_TMPDIR")).join("pi-rpc-agent"),
        )
        .with_graces(Duration::from_millis(100), Duration::from_millis(100))
}

fn request(cwd: &std::path::Path, prompt: &str) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: None,
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: cwd.display().to_string(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        resume: None,
        attachments: vec![],
        worktree: None,
        mcp: None,
        provider_fork: None,
        resume_fallback_history: None,
    }
}

fn controls() -> (RunControls, mpsc::Sender<SteerMessage>, CancellationToken) {
    let (tx, rx) = mpsc::channel(8);
    let token = CancellationToken::new();
    (
        RunControls {
            execution_lease: None,
            steering: rx,
            interrupt: token.clone(),
            request_input: Box::new(|_| {
                let (tx, rx) = oneshot::channel();
                let _ = tx.send(vec![]);
                rx
            }),
            turn: Default::default(),
            origin: None,
        },
        tx,
        token,
    )
}

/// Run a prompt to its `Done` (bounded). Roboco's pi runs park their
/// session after the Done — the stream stays open and the caller owns
/// teardown — so the collection ends at the Done like the sh-fixture suite.
async fn collect(prompt: &str) -> Vec<AgentEvent> {
    let dir = tempfile::tempdir().unwrap();
    let (c, tx, _) = controls();
    drop(tx);
    let mut stream = harness().run(request(dir.path(), prompt), c).await.unwrap();
    let mut events = Vec::new();
    while let Some(event) =
        tokio::time::timeout(Duration::from_secs(15), stream.next()).await.expect("progress")
    {
        let event = event.expect("stream event");
        let done = matches!(event, AgentEvent::Done { .. });
        events.push(event);
        if done {
            break;
        }
    }
    events
}

fn dones(events: &[AgentEvent]) -> Vec<DoneStatus> {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::Done { status, .. } => Some(*status),
            _ => None,
        })
        .collect()
}

fn text_of(events: &[AgentEvent]) -> String {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::TextDelta { text } => Some(text.as_str()),
            _ => None,
        })
        .collect()
}

/// The terminal contract over every scenario shape the peer scripts: a
/// normal turn, an errored message, a rejected prompt, a recovered
/// auto-retry, a handled command (no run at all), and a crash mid-turn.
#[tokio::test]
async fn terminal_contract_handles_errors_retries_and_consumed_prompts() {
    for (prompt, status) in [
        ("hello", DoneStatus::Completed),
        ("error", DoneStatus::Errored),
        ("reject", DoneStatus::Errored),
        ("retry", DoneStatus::Completed),
        ("handled", DoneStatus::Completed),
        ("crash", DoneStatus::Errored),
    ] {
        let events = collect(prompt).await;
        assert_eq!(dones(&events), vec![status], "{prompt}: {events:?}");
        if prompt == "hello" {
            assert_eq!(text_of(&events), "reply:hello", "{events:?}");
        }
    }
}

/// Threshold compaction still running at the settle barrier ends with no
/// settle event after it: the barrier must re-check at `compaction_end`.
#[tokio::test]
async fn compaction_after_the_settle_barrier_still_ends_the_turn() {
    let events = collect("compact-tail").await;
    assert_eq!(dones(&events), [DoneStatus::Completed], "{events:?}");
}

/// Extension errors and stdout noise (a stray console line, a string sliced
/// mid surrogate pair) are shown or skipped — never fatal to the turn.
#[tokio::test]
async fn extension_errors_and_stdout_noise_do_not_fail_the_turn() {
    for prompt in ["ext-error", "noise"] {
        let events = collect(prompt).await;
        assert_eq!(dones(&events), [DoneStatus::Completed], "{prompt}: {events:?}");
        if prompt == "noise" {
            // The lone surrogate half is repaired into the frame.
            assert!(
                events.contains(&AgentEvent::TextDelta {
                    text: "cut \u{fffd}".into()
                }),
                "{events:?}"
            );
            // The stray console line is not part of the reply.
            assert_eq!(text_of(&events), "cut \u{fffd}reply:noise", "{events:?}");
        } else {
            assert!(
                events.iter().any(
                    |e| matches!(e, AgentEvent::Error { message, .. } if message.contains("handler threw"))
                ),
                "{events:?}"
            );
        }
    }
}

/// A frame over the driver's 32 MiB bound costs that frame, not the run.
#[tokio::test]
async fn an_oversized_frame_does_not_end_the_turn() {
    let events = collect("oversized").await;
    assert_eq!(dones(&events), [DoneStatus::Completed], "{events:?}");
    assert_eq!(text_of(&events), "reply:oversized", "{events:?}");
}

/// pi refusing a steer fails that message, not the turn it was sent into.
#[tokio::test]
async fn a_rejected_steer_reports_and_the_turn_goes_on() {
    let dir = tempfile::tempdir().unwrap();
    let (c, tx, _) = controls();
    let mut stream = harness().run(request(dir.path(), "slow"), c).await.unwrap();
    let mut events = vec![];
    while let Some(event) = tokio::time::timeout(Duration::from_secs(15), stream.next())
        .await
        .unwrap()
    {
        let event = event.unwrap();
        if matches!(&event, AgentEvent::SessionStarted { .. }) {
            tx.send(SteerMessage::text("reject")).await.unwrap();
        }
        let done = matches!(event, AgentEvent::Done { .. });
        events.push(event);
        if done {
            break;
        }
    }
    drop(tx);
    assert!(
        events
            .iter()
            .any(|e| matches!(e, AgentEvent::Error { message, .. } if message.contains("A prompt is already running"))),
        "{events:?}"
    );
    assert_eq!(dones(&events), [DoneStatus::Completed], "{events:?}");
    assert!(text_of(&events).contains("reply:slow"), "{events:?}");
}

/// Input pi left queued while idle never starts: the settle probe clears
/// the queue and the text re-delivers as the next turn's prompt (Roboco's
/// strand-recovery contract — the first Done ends the stranded attempt,
/// the recovered message runs as its own turn).
#[tokio::test]
async fn stranded_input_is_recovered_by_the_queue_clear() {
    let dir = tempfile::tempdir().unwrap();
    let (c, tx, _) = controls();
    drop(tx);
    let mut stream = harness().run(request(dir.path(), "stranded"), c).await.unwrap();
    let mut events = Vec::new();
    let mut done_count = 0;
    while let Some(event) =
        tokio::time::timeout(Duration::from_secs(15), stream.next()).await.expect("progress")
    {
        let event = event.expect("stream event");
        if matches!(event, AgentEvent::Done { .. }) {
            done_count += 1;
        }
        events.push(event);
        if done_count == 2 {
            break;
        }
    }
    assert_eq!(done_count, 2, "{events:?}");
    assert_eq!(
        dones(&events),
        [DoneStatus::Completed, DoneStatus::Completed],
        "{events:?}"
    );
    // The re-delivery ran as a real turn.
    assert!(text_of(&events).contains("reply:stranded"), "{events:?}");
}

/// A resumed run keeps the stored session file: the peer's `--session`
/// round trip (the run's own file pointer is the resume identity).
#[tokio::test]
async fn resume_reuses_the_durable_session_file() {
    let dir = tempfile::tempdir().unwrap();
    let first: Vec<AgentEvent> = {
        let (c, tx, _) = controls();
        drop(tx);
        let mut stream = harness().run(request(dir.path(), "hello"), c).await.unwrap();
        let mut events = Vec::new();
        while let Some(event) =
            tokio::time::timeout(Duration::from_secs(15), stream.next()).await.expect("progress")
        {
            let event = event.expect("stream event");
            let done = matches!(event, AgentEvent::Done { .. });
            events.push(event);
            if done {
                break;
            }
        }
        events
    };
    let Some(AgentEvent::Done { session_id: Some(file), .. }) = first.last() else {
        panic!("no session file on the first Done: {first:?}");
    };
    let (c, tx, _) = controls();
    drop(tx);
    let mut req = request(dir.path(), "hello");
    req.resume = Some(file.clone());
    let mut stream = harness().run(req, c).await.unwrap();
    let mut events = Vec::new();
    while let Some(event) =
        tokio::time::timeout(Duration::from_secs(15), stream.next()).await.expect("progress")
    {
        let event = event.expect("stream event");
        let done = matches!(event, AgentEvent::Done { .. });
        events.push(event);
        if done {
            break;
        }
    }
    assert_eq!(dones(&events), [DoneStatus::Completed], "{events:?}");
    assert!(text_of(&events).contains("reply:hello"), "{events:?}");
}
