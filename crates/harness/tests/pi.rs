//! PiHarness integration tests against the fixture RPC process in
//! `tests/fixtures/fake-pi-rpc.sh` (no real `pi` CLI involved). The fixture
//! scripts pi's first-party RPC surface: id-correlated responses, every
//! event kind the driver maps, noise/CRLF tolerance, turn errors, auto
//! retry, compaction, the extension UI sub-protocol, and the steer queue.

#![cfg(unix)]

use std::path::PathBuf;
use std::time::Duration;

use futures::StreamExt;
use tokio::sync::{mpsc, oneshot};

use roboco_harness::{CancellationToken, Harness, PiHarness, RunControls, SteerMessage};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, ReasoningLevel, RunRequest, SandboxLevel, SteeringMode,
    ToolCall, UserInputAnswer,
};

fn fixture_path() -> PathBuf {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
        .join("fake-pi-rpc.sh");
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755));
    }
    path
}

fn harness() -> PiHarness {
    PiHarness::new()
        .with_executable(fixture_path())
        .with_graces(Duration::from_millis(100), Duration::from_millis(150))
}

fn request(prompt: &str) -> RunRequest {
    request_cwd(prompt, "/tmp")
}

fn request_cwd(prompt: &str, cwd: &str) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: None,
        model: None,
        reasoning: None,
        model_options: serde_json::Map::new(),
        cwd: cwd.into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
        resume: None,
    }
}

fn controls() -> (RunControls, mpsc::Sender<SteerMessage>, CancellationToken) {
    let (steer_tx, steer_rx) = mpsc::channel(8);
    let token = CancellationToken::new();
    let controls = RunControls {
        execution_lease: None,
        request_input: Box::new(|questions| {
            let (tx, rx) = oneshot::channel();
            let answers: Vec<UserInputAnswer> = questions
                .iter()
                .map(|q| {
                    // Yes/No questions answer Yes; select dialogs pick the
                    // second option (the fixture asserts "npm").
                    let label = if q
                        .options
                        .iter()
                        .any(|option| option.eq_ignore_ascii_case("yes"))
                    {
                        "Yes".to_owned()
                    } else {
                        q.options.get(1).cloned().unwrap_or_else(|| "Yes".into())
                    };
                    UserInputAnswer {
                        question_id: q.id.clone(),
                        labels: vec![label],
                    }
                })
                .collect();
            let _ = tx.send(answers);
            rx
        }),
        steering: steer_rx,
        interrupt: token.clone(),
    };
    (controls, steer_tx, token)
}

fn dones(events: &[AgentEvent]) -> Vec<(DoneStatus, Option<String>)> {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::Done { status, error, .. } => Some((*status, error.clone())),
            _ => None,
        })
        .collect()
}

async fn run_to_end(harness: &PiHarness, req: RunRequest, controls: RunControls) -> Vec<AgentEvent> {
    let mut stream = harness.run(req, controls).await.unwrap();
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

fn text_of(events: &[AgentEvent]) -> String {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::TextDelta { text } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Descriptor + catalog surfaces
// ---------------------------------------------------------------------------

#[test]
fn pi_surfaces_match_registry_expectations() {
    let pi = harness();
    assert_eq!(pi.id(), HarnessId::Pi);
    assert_eq!(pi.display_name(), "Pi");
    assert!(pi.supports_steering());
    assert_eq!(pi.steering_mode(), SteeringMode::TurnBoundary);
    assert_eq!(
        pi.reasoning_levels(),
        &[
            ReasoningLevel::Minimal,
            ReasoningLevel::Low,
            ReasoningLevel::Medium,
            ReasoningLevel::High,
            ReasoningLevel::XHigh,
            ReasoningLevel::Max,
        ]
    );
    assert!(pi.installed());
    assert!(pi.deterministic_turn_end());
    assert!(pi.authoritative_prompt_end());
}

#[tokio::test]
async fn models_list_the_live_provider_scoped_catalog() {
    let models = harness().models().await.unwrap();
    // Provider-scoped composite ids (what `set_model` splits back into its
    // two fields); provider-less and name-less entries are skipped / fall
    // back to the id; reasoning models carry pi's ladder.
    let ids: Vec<&str> = models.iter().map(|m| m.id.as_str()).collect();
    assert_eq!(
        ids,
        vec![
            "mock/flash-model",
            "mock/reasoning-model",
            "mock/nested/gateway/model",
            "mock/no-name-model",
            "mock/bare-model",
        ],
        "{models:?}"
    );
    assert_eq!(models[0].label, "mock/Flash Model");
    assert_eq!(models[1].label, "mock/Reasoning Model");
    // A name-less entry labels with the composite id itself.
    assert_eq!(models[3].label, "mock/no-name-model");
    assert!(models[1].reasoning_levels.contains(&ReasoningLevel::Max));
    assert!(models[0].reasoning_levels.is_empty());
    // A repeated call on the same harness instance serves the cached
    // catalog without a second probe.
    let pi = harness();
    assert_eq!(pi.models().await.unwrap().len(), 5);
    assert_eq!(pi.models().await.unwrap().len(), 5);
}

#[tokio::test]
async fn missing_override_is_not_installed_and_fails_discovery() {
    let pi = PiHarness::new().with_executable("/nonexistent/never-a-pi");
    assert!(!pi.installed());
    let err = pi.models().await.expect_err("missing override");
    assert!(
        matches!(err, roboco_harness::HarnessError::NotInstalled(_)),
        "{err:?}"
    );
}

// ---------------------------------------------------------------------------
// Happy-path event mapping
// ---------------------------------------------------------------------------

#[tokio::test]
async fn happy_path_maps_the_full_event_surface() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("hello"), controls).await;
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None)],
        "{events:?}"
    );
    // SessionStarted carries the durable resume pointer (the session FILE).
    let started = events.iter().find_map(|e| match e {
        AgentEvent::SessionStarted {
            session_id, cwd, ..
        } => Some((session_id.clone(), cwd.clone())),
        _ => None,
    });
    let (session_id, cwd) = started.expect("SessionStarted");
    assert!(session_id.ends_with(".pi-fixture-session.jsonl"), "{session_id}");
    assert!(std::path::Path::new(&session_id).is_file());
    assert_eq!(cwd, "/tmp"); // the request cwd was passed through
    assert_eq!(
        text_of(&events),
        "reply:hello",
        "{events:?}"
    );
    // Per-turn usage rides the turn boundary; the context meter reads
    // get_session_stats after the settle.
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::Usage {
            input_tokens: 800,
            output_tokens: 10,
        }
    )));
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::ContextUsage {
            tokens: Some(60000),
            window: Some(200000),
        }
    )));
    // The user-message echo of our own prompt is not a steer delivery.
    assert!(!events.iter().any(|e| matches!(e, AgentEvent::Steered { .. })));
}

#[tokio::test]
async fn thinking_deltas_map_to_reasoning() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("thinking"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::ReasoningDelta { text } if text == "thinking hard "
    )));
}

#[tokio::test]
async fn tool_events_map_to_typed_calls_and_results() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("tools"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    let calls: Vec<(&str, &ToolCall)> = events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::ToolCall { id, call } => Some((id.as_str(), call)),
            _ => None,
        })
        .collect();
    assert_eq!(calls.len(), 2, "{events:?}");
    assert_eq!(
        calls[0].1,
        &ToolCall::Exec {
            command: "printf TOOL-OK".into()
        }
    );
    assert_eq!(
        calls[1].1,
        &ToolCall::EditFile {
            path: "src/lib.rs".into(),
            old_string: Some("old".into()),
            new_string: Some("new".into()),
        }
    );
    let results: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::ToolResult {
                id,
                is_error,
                output,
                diff,
            } => Some((id.as_str(), *is_error, output.clone(), diff.is_some())),
            _ => None,
        })
        .collect();
    assert_eq!(results.len(), 2, "{events:?}");
    assert_eq!(results[0], ("tool-1", false, Some("TOOL-OK".into()), false));
    // The partial update never resolves the chip early: the FINAL result
    // ("TOOL-OK", not the partial "TOOL") is what lands.
    assert_eq!(
        results[1],
        ("tool-2", true, Some("edited".into()), true)
    );
}

#[tokio::test]
async fn noise_crlf_and_huge_frames_are_tolerated() {
    let (ctl, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("noise"), ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events), "reply:noise");

    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("huge"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events).len(), 1024 * 1024 + 17);
}

#[tokio::test]
async fn unknown_events_and_id_less_responses_are_tolerated() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("unknown-events"), controls).await;
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None)],
        "{events:?}"
    );
    assert_eq!(text_of(&events), "reply:unknown-events");
}

#[tokio::test]
async fn thinking_level_is_applied_at_startup() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("hello", &cwd.path().display().to_string());
    req.reasoning = Some(ReasoningLevel::XHigh);
    let events = run_to_end(&harness(), req, ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    // The fixture appends the applied level to its session file: the
    // set_thinking_level command carried the run's reasoning level.
    let recorded = std::fs::read_to_string(
        cwd.path().join(".pi-fixture-session.jsonl"),
    )
    .unwrap();
    assert!(recorded.contains("thinking:xhigh"), "{recorded}");
}

// ---------------------------------------------------------------------------
// Requested models are applied through `set_model`
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_composite_model_id_switches_the_model_before_the_first_prompt() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("hello", &cwd.path().display().to_string());
    req.model = Some("mock/nested/gateway/model".into());
    let events = run_to_end(&harness(), req, ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    // Composite ids split on the FIRST slash: provider "mock", model id
    // "nested/gateway/model" (pi model ids may themselves contain slashes).
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(recorded.contains("model:mock|nested/gateway/model"), "{recorded}");
}

#[tokio::test]
async fn a_bare_legacy_model_id_resolves_its_provider_through_the_catalog() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("hello", &cwd.path().display().to_string());
    req.model = Some("bare-model".into());
    let events = run_to_end(&harness(), req, ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(recorded.contains("model:mock|bare-model"), "{recorded}");
}

#[tokio::test]
async fn the_pass_through_default_never_switches_the_model() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("hello", &cwd.path().display().to_string());
    req.model = Some("default".into());
    let events = run_to_end(&harness(), req, ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(!recorded.contains("model:"), "{recorded}");
}

#[tokio::test]
async fn a_rejected_model_switch_is_best_effort_and_the_run_proceeds() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("hello", &cwd.path().display().to_string());
    // The fixture rejects this id: pi-acp parity — log and run the default.
    req.model = Some("mock/reject-model".into());
    let events = run_to_end(&harness(), req, ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events), "reply:hello", "{events:?}");
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(!recorded.contains("model:"), "{recorded}");
}

// ---------------------------------------------------------------------------
// Title runs
// ---------------------------------------------------------------------------

#[tokio::test]
async fn title_runs_spawn_an_isolated_one_shot_and_answer_with_a_title() {
    let scratch = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd(
        "You generate session titles. Session request (JSON string): \"fix the login flow\"",
        &scratch.path().display().to_string(),
    );
    // A hostile caller smuggles interaction surfaces; run_title must clear
    // every one of them before spawning.
    req.resume = Some("some-old-session".into());
    req.attachments = vec!["/etc/passwd".into()];
    req.auto_approve = true;
    let mut stream = harness().run_title(req, ctl).await.unwrap();
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
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events), "Fix Login Flow", "{events:?}");
    // No session file in the scratch dir: the title sandbox is ephemeral
    // and never resumes. (The fixture asserts the spawn flags itself.)
    assert!(!scratch.path().join(".pi-fixture-session.jsonl").exists());
    // pi participates in device-local title generation.
    assert!(roboco_harness::supports_titles(HarnessId::Pi));
}

#[tokio::test]
async fn mcp_injection_installs_the_extension_bridge() {
    let (controls, _steer, _token) = controls();
    let mut req = request("hello");
    req.mcp = Some(roboco_proto::McpServer {
        name: "roboco".into(),
        // Never spawned by the fixture: the bridge extension is the thing
        // that would launch this command inside a real pi, and the fixture
        // asserts the wiring (flag + env + written file) at startup.
        command: "/nonexistent/roboco".into(),
        args: vec!["mcp".into()],
        env: [
            ("ROBOCO_IPC_PORT".to_owned(), "27654".to_owned()),
            ("ROBOCO_CHAT_ID".to_owned(), "chat-1234".to_owned()),
            ("ROBOCO_DEVICE_ID".to_owned(), "dev-5678".to_owned()),
        ]
        .into_iter()
        .collect(),
    });
    let events = run_to_end(&harness(), req, controls).await;
    // The fixture asserts --extension + ROBOCO_MCP_SERVER + the file at
    // spawn; the run itself must complete normally with the bridge along.
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert_eq!(text_of(&events), "reply:hello");
}

// ---------------------------------------------------------------------------
// Turn errors reject the prompt
// ---------------------------------------------------------------------------

#[tokio::test]
async fn errored_assistant_message_rejects_the_prompt() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("error"), controls).await;
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Errored, Some("provider returned 529".into()))],
        "{events:?}"
    );
    // The failed message also surfaces as a visible error chip.
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::Error { message } if message == "provider returned 529"
    )));
}

#[tokio::test]
async fn exhausted_auto_retry_rejects_the_prompt_and_recovery_completes() {
    let (ctl, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("retry-exhausted"), ctl).await;
    assert_eq!(
        dones(&events),
        vec![(
            DoneStatus::Errored,
            Some("provider overloaded (all retries failed)".into())
        )],
        "{events:?}"
    );
    // Retry status surfaces as a visible notice.
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::Error { message } if message.contains("attempt 1/3")
    )));

    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("retry-recover"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events), "reply:retry-recover");
}

#[tokio::test]
async fn failed_compaction_rejects_the_prompt_but_success_completes() {
    let (ctl, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("compaction-fail"), ctl).await;
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Errored, Some("summarizer failed".into()))],
        "{events:?}"
    );

    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("compaction-ok"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
}

#[tokio::test]
async fn prompt_rejection_errors_the_run_with_pi_reason() {
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request("reject"), controls).await;
    let dones = dones(&events);
    assert_eq!(dones.len(), 1);
    assert_eq!(dones[0].0, DoneStatus::Errored);
    assert!(dones[0].1.as_deref().unwrap().contains("busy"), "{events:?}");
}

// ---------------------------------------------------------------------------
// Session lifecycle: durable resume
// ---------------------------------------------------------------------------

#[tokio::test]
async fn idle_crash_then_resume_passes_the_session_file() {
    let cwd = tempfile::tempdir().unwrap();
    let (ctl, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request_cwd("idle-crash", &cwd.path().display().to_string()), ctl).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    let session = events
        .iter()
        .find_map(|event| match event {
            AgentEvent::Done { session_id, .. } => session_id.clone(),
            _ => None,
        })
        .unwrap();
    // Give the fixture its exit window, then resume by the file pointer.
    tokio::time::sleep(Duration::from_millis(400)).await;
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("resumed", &cwd.path().display().to_string());
    req.resume = Some(session);
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert_eq!(text_of(&events), "reply:resumed");
}

#[tokio::test]
async fn lost_resume_pointer_announces_fresh_context() {
    let cwd = tempfile::tempdir().unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fresh", &cwd.path().display().to_string());
    // A legacy ACP-era pointer (a bare id): never passed as --session
    // (pi's global id search prompts interactively on stdin).
    req.resume = Some("pi-session".into());
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    assert!(events.iter().any(|e| matches!(
        e,
        AgentEvent::Error { message } if message.contains("without the previous context")
    )));
    assert_eq!(text_of(&events), "reply:fresh");
}

// ---------------------------------------------------------------------------
// Steering
// ---------------------------------------------------------------------------

#[tokio::test]
async fn mid_run_steer_delivers_at_pi_turn_boundary() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-flow"), controls).await.unwrap();
    let mut events = Vec::new();
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "first part ");
        events.push(event);
        if steer_now {
            steer
                .send(SteerMessage {
                    prompt: "steered text".into(),
                    message_id: None,
                })
                .await
                .unwrap();
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    // One run, one Done: the steer folded in at pi's turn boundary with the
    // boundary BEFORE the reply to it.
    let steered_at = events
        .iter()
        .position(|e| matches!(e, AgentEvent::Steered { .. }))
        .expect("a Steered boundary must exist");
    let reply_at = events
        .iter()
        .position(|e| matches!(e, AgentEvent::TextDelta { text } if text == "reply-to-steer"))
        .expect("the steer's reply must stream");
    assert!(steered_at < reply_at, "{events:?}");
    let first_at = events
        .iter()
        .position(|e| matches!(e, AgentEvent::TextDelta { text } if text == "first part "))
        .unwrap();
    assert!(first_at < steered_at, "{events:?}");
}

#[tokio::test]
async fn stranded_steer_is_recovered_after_the_done() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-strand"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now =
            matches!(&event, AgentEvent::TextDelta { text } if text == "reply part") && !sent;
        if steer_now {
            sent = true;
            steer
                .send(SteerMessage {
                    prompt: "recovered steer".into(),
                    message_id: None,
                })
                .await
                .unwrap();
        }
        events.push(event);
        if dones(&events).len() == 2 {
            break;
        }
    }
    // The stranded steer recovered via clear_queue and delivered as the next
    // prompt: two turns, two Dones, a Steered boundary between them.
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None), (DoneStatus::Completed, None)],
        "{events:?}"
    );
    let first_done = events
        .iter()
        .position(|e| matches!(e, AgentEvent::Done { .. }))
        .unwrap();
    let steered_at = events
        .iter()
        .position(|e| matches!(e, AgentEvent::Steered { .. }))
        .expect("recovered steer needs a boundary");
    assert!(first_done < steered_at, "{events:?}");
    assert!(text_of(&events).contains("reply:recovered steer"));
}

#[tokio::test]
async fn parked_session_next_message_runs_as_a_new_prompt() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("first"), controls).await.unwrap();
    let mut events = Vec::new();
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        events.push(event);
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)]);
    // The parked session's next user message rides the steering mailbox.
    steer
        .send(SteerMessage {
            prompt: "follow-up-prompt".into(),
            message_id: None,
        })
        .await
        .unwrap();
    let mut second = Vec::new();
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        second.push(event);
        if matches!(second.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&second), vec![(DoneStatus::Completed, None)]);
    assert!(second
        .iter()
        .any(|e| matches!(e, AgentEvent::Steered { .. })));
    assert_eq!(text_of(&second), "reply:follow-up-prompt");
    drop(steer);
    assert!(
        tokio::time::timeout(Duration::from_secs(5), stream.next())
            .await
            .unwrap()
            .is_none()
    );
}

// ---------------------------------------------------------------------------
// Extension UI sub-protocol
// ---------------------------------------------------------------------------

#[tokio::test]
async fn extension_dialogs_round_trip_through_the_input_bridge() {
    let (controls, _steer, _token) = controls();
    let events = run_to_end(&harness(), request("extension-ui"), controls).await;
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None)],
        "{events:?}"
    );
    // The run completes only because the fixture received its two
    // extension_ui_response answers (it asserts their content); the
    // questions themselves surfaced through the bridge.
    assert_eq!(text_of(&events), "dialogs done");
}

// ---------------------------------------------------------------------------
// Interrupt + crash + process ownership
// ---------------------------------------------------------------------------

#[tokio::test]
async fn interrupt_settles_as_interrupted() {
    for prompt in ["interrupt", "hung"] {
        let (controls, _steer, token) = controls();
        let mut stream = harness().run(request(prompt), controls).await.unwrap();
        let events = tokio::time::timeout(Duration::from_secs(10), async {
            let mut events = Vec::new();
            while let Some(event) = stream.next().await {
                let event = event.unwrap();
                if matches!(&event, AgentEvent::TextDelta { text } if text == "working") {
                    token.cancel();
                }
                let done = matches!(&event, AgentEvent::Done { .. });
                events.push(event);
                if done {
                    break;
                }
            }
            events
        })
        .await
        .expect("interrupt must settle");
        assert_eq!(
            dones(&events),
            vec![(DoneStatus::Interrupted, None)],
            "{prompt}: {events:?}"
        );
    }
}

#[tokio::test]
async fn crash_reports_status_and_stderr_once() {
    for (prompt, status, tail) in [
        ("crash", "exit code 23", "last stderr context"),
        ("signal-crash", "signal 9", "signal context"),
        (
            "inherited-pipe-crash",
            "exit code 25",
            "inherited pipe context",
        ),
    ] {
        let (controls, steer, _token) = controls();
        drop(steer);
        let events = run_to_end(&harness(), request(prompt), controls).await;
        let done = dones(&events);
        assert_eq!(done.len(), 1, "{events:?}");
        assert_eq!(done[0].0, DoneStatus::Errored);
        let error = done[0].1.as_deref().unwrap();
        assert!(error.contains(status) && error.contains(tail), "{error}");
    }
}

#[tokio::test]
async fn interrupt_kills_tool_process_group() {
    let (controls, _steer, token) = controls();
    let mut stream = harness().run(request("tree"), controls).await.unwrap();
    let mut tree_pids = Vec::new();
    let events = tokio::time::timeout(Duration::from_secs(10), async {
        let mut events = Vec::new();
        while let Some(event) = stream.next().await {
            let event = event.unwrap();
            if let AgentEvent::TextDelta { text } = &event
                && let Some(pid) = text.strip_prefix("tree:")
            {
                tree_pids.push(pid.parse::<i32>().unwrap());
                token.cancel();
            }
            events.push(event);
        }
        events
    })
    .await
    .unwrap();
    assert_eq!(dones(&events), vec![(DoneStatus::Interrupted, None)]);
    assert_eq!(tree_pids.len(), 2);
    for pid in tree_pids {
        // A zombie awaiting the host reaper is dead; no tool may remain running.
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap_or_default();
        assert!(
            stat.is_empty() || stat.split_whitespace().nth(2) == Some("Z"),
            "{stat}"
        );
    }
}

#[tokio::test]
async fn dropping_stream_terminates_tool_tree() {
    let (controls, _steer, _token) = controls();
    let mut stream = harness().run(request("tree"), controls).await.unwrap();
    let mut pids = Vec::new();
    tokio::time::timeout(Duration::from_secs(10), async {
        while pids.len() < 2 {
            if let AgentEvent::TextDelta { text } = stream.next().await.unwrap().unwrap()
                && let Some(pid) = text.strip_prefix("tree:")
            {
                pids.push(pid.parse::<i32>().unwrap());
            }
        }
    })
    .await
    .unwrap();
    drop(stream);
    tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            if pids.iter().all(|pid| {
                let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap_or_default();
                stat.is_empty() || stat.split_whitespace().nth(2) == Some("Z")
            }) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("consumer shutdown must terminate the tool tree");
}

#[tokio::test]
async fn wake_turn_after_settle_still_gets_its_done() {
    let (controls, _steer, _token) = controls();
    let mut stream = harness().run(request("wake-turn"), controls).await.unwrap();
    let events = tokio::time::timeout(Duration::from_secs(10), async {
        let mut events = Vec::new();
        while let Some(event) = stream.next().await {
            let event = event.unwrap();
            let done = matches!(&event, AgentEvent::Done { .. });
            events.push(event);
            if done && dones(&events).len() == 2 {
                break;
            }
        }
        events
    })
    .await
    .expect("wake turn must settle");
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None), (DoneStatus::Completed, None)],
        "{events:?}"
    );
    assert!(text_of(&events).contains("wake output"));
}
