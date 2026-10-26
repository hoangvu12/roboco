//! PiHarness integration tests against the fixture RPC process in
//! `tests/fixtures/fake-pi-rpc.sh` (no real `pi` CLI involved). The fixture
//! scripts pi's first-party RPC surface: id-correlated responses, every
//! event kind the driver maps, noise/CRLF tolerance, turn errors, auto
//! retry, compaction, the extension UI sub-protocol, and the steer queue.

#![cfg(unix)]

use std::path::PathBuf;
use std::time::Duration;

use futures::StreamExt;
use futures::stream::BoxStream;
use tokio::sync::{mpsc, oneshot};

use roboco_harness::{CancellationToken, Harness, PiHarness, RunControls, SteerMessage, TurnControl};
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
        provider_fork: None,
        resume_fallback_history: None,
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
        turn: TurnControl::default(),
        origin: None,
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

/// Collect events through the next `Done` (bounded), keeping the stream
/// open for further turns.
async fn until_done(
    stream: &mut BoxStream<'static, Result<AgentEvent, roboco_harness::HarnessError>>,
    events: &mut Vec<AgentEvent>,
    budget: Duration,
) {
    loop {
        let event = tokio::time::timeout(budget, stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let done = matches!(event, AgentEvent::Done { .. });
        events.push(event);
        if done {
            return;
        }
    }
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
    // Step-boundary (orchestration v2 ticket 03): pi's steer queue delivers
    // after the current assistant turn's tool calls — the engine registry
    // descriptor must agree (its own tests check the pair).
    assert_eq!(pi.steering_mode(), SteeringMode::StepBoundary);
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
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: "some-old-session.jsonl".into(),
        last_turn: "u1".into(),
        fallback_history: None,
    });
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
    // The smuggled fork directive never spawned a fork process either.
    assert!(!scratch.path().join(".pi-fixture-forked.jsonl").exists());
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
        AgentEvent::Error { message, .. } if message == "provider returned 529"
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
        AgentEvent::Error { message, .. } if message.contains("attempt 1/3")
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
        AgentEvent::Error { message, .. } if message.contains("without the previous context")
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
                    attachments: Vec::new(),
                    config: None,
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
                    attachments: Vec::new(),
                    config: None,
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
            attachments: Vec::new(),
            config: None,
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

/// A model or thinking switch reaches the live process before the message
/// it was sent with: no new process, no lost session.
#[tokio::test]
async fn a_model_switch_applies_to_the_live_process() {
    let cwd = tempfile::tempdir().unwrap();
    let (controls, steer, _token) = controls();
    let mut opening = request_cwd("which-model", &cwd.path().display().to_string());
    opening.model = Some("mock/mock".into());
    let mut switched = opening.clone();
    switched.model = Some("mock/mock-2".into());
    switched.reasoning = Some(ReasoningLevel::High);
    let driver = harness();
    assert!(driver.reconfigures_in_place(&opening, &switched));
    let mut stream = driver.run(opening, controls).await.unwrap();
    let mut text = String::new();
    let mut dones = 0;
    let mut started = 0;
    while dones < 2 {
        let event = tokio::time::timeout(Duration::from_secs(5), stream.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        match event {
            AgentEvent::SessionStarted { .. } => started += 1,
            AgentEvent::TextDelta { text: delta } => text.push_str(&delta),
            AgentEvent::Done { .. } => {
                dones += 1;
                text.push('|');
                if dones == 1 {
                    steer
                        .send(SteerMessage {
                            config: Some(Box::new(switched.clone())),
                            ..SteerMessage::text("which-model")
                        })
                        .await
                        .unwrap();
                }
            }
            _ => {}
        }
    }
    assert_eq!(started, 1);
    assert!(text.contains("reply:mock/medium|"), "{text}");
    assert!(text.contains("reply:mock-2/high|"), "{text}");
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

// ---------------------------------------------------------------------------
// Prompt delivery hardening: dispositions, rejections, the settle probe
// (orchestration v2, ticket 02)
// ---------------------------------------------------------------------------

/// A prompt pi handles without a run (an extension command) ends the turn —
/// the settle probe confirms pi at rest — instead of sticking the chat on
/// Working forever, and the session keeps serving.
#[tokio::test]
async fn a_handled_prompt_ends_the_turn_and_parks_the_session() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("handled"), controls).await.unwrap();
    let mut events = Vec::new();
    until_done(&mut stream, &mut events, Duration::from_secs(15)).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // The session parks: the next message runs as a normal turn.
    steer
        .send(SteerMessage::text("after handled"))
        .await
        .unwrap();
    let mut second = Vec::new();
    until_done(&mut stream, &mut second, Duration::from_secs(15)).await;
    assert_eq!(dones(&second), vec![(DoneStatus::Completed, None)], "{second:?}");
    assert!(second.iter().any(|e| matches!(e, AgentEvent::Steered { .. })));
    assert_eq!(text_of(&second), "reply:after handled");
}

/// A prompt rejected while pi is streaming surfaces the rejection, returns
/// the message to the queue, and never ends the session: the message
/// re-delivers once pi comes to rest, and the session keeps serving.
#[tokio::test]
async fn a_prompt_rejected_while_pi_streams_keeps_the_session_and_the_message() {
    let (controls, steer, _token) = controls();
    let mut stream = harness()
        .run(request("reject-while-streaming"), controls)
        .await
        .unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    loop {
        let event = tokio::time::timeout(Duration::from_secs(15), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let is_working = matches!(&event, AgentEvent::TextDelta { text } if text == "working");
        let saw_reply = text_of(&events).contains("reply:steered while streaming");
        let is_done = matches!(&event, AgentEvent::Done { .. });
        events.push(event);
        if is_working && !sent {
            // The extension run is streaming: this is the window where the
            // message is delivered and rejected.
            sent = true;
            steer
                .send(SteerMessage::text("steered while streaming"))
                .await
                .unwrap();
        }
        if is_done && saw_reply {
            break;
        }
    }
    // The prompted turn, the extension run, and the re-delivered message:
    // three completed turns — the session never died, the turn never
    // stranded.
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None); 3],
        "{events:?}"
    );
    // The rejection surfaced...
    assert!(
        events.iter().any(|e| matches!(
            e,
            AgentEvent::Error { message, .. } if message.contains("busy")
        )),
        "{events:?}"
    );
    // ...and the message survived the rejection and ran.
    assert!(
        text_of(&events).contains("reply:steered while streaming"),
        "{events:?}"
    );
    // The session is alive: a follow-up still runs.
    steer.send(SteerMessage::text("still alive")).await.unwrap();
    let mut tail = Vec::new();
    until_done(&mut stream, &mut tail, Duration::from_secs(15)).await;
    assert_eq!(dones(&tail), vec![(DoneStatus::Completed, None)], "{tail:?}");
    assert_eq!(text_of(&tail), "reply:still alive");
}

/// A slow pi preflight (extensions, the Roboco MCP bridge, compaction) is
/// allowed: no fixed acceptance budget kills the run — only Stop interrupts
/// a slow acceptance.
#[tokio::test]
async fn a_slow_pi_preflight_completes_the_run() {
    let (controls, steer, _token) = controls();
    drop(steer);
    let started = std::time::Instant::now();
    let mut stream = harness().run(request("slow-preflight"), controls).await.unwrap();
    let mut events = Vec::new();
    until_done(&mut stream, &mut events, Duration::from_secs(60)).await;
    assert!(started.elapsed() >= Duration::from_secs(31));
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert_eq!(text_of(&events), "reply:slow-preflight");
}

/// Stop interrupts a slow acceptance: no fixed timeout kills the run, but
/// Stop still ends it — exactly one `Interrupted` Done, never an
/// error-then-interrupted pair from the unanswered acceptance.
#[tokio::test]
async fn stop_interrupts_a_slow_pi_preflight() {
    let (controls, steer, token) = controls();
    drop(steer);
    let mut stream = harness().run(request("slow-preflight"), controls).await.unwrap();
    // The acceptance is in flight (the fake holds its answer for 31 s).
    tokio::time::sleep(Duration::from_millis(500)).await;
    token.cancel();
    let mut events = Vec::new();
    while let Some(event) =
        tokio::time::timeout(Duration::from_secs(10), stream.next()).await.expect("progress")
    {
        let event = event.expect("stream event");
        let done = matches!(event, AgentEvent::Done { .. });
        events.push(event);
        if done {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Interrupted, None)], "{events:?}");
    // The run never started: nothing streamed before the Stop.
    assert!(text_of(&events).is_empty(), "{events:?}");
}

/// A prompt racing `agent_settled` either joins the run or starts the next
/// turn: it is delivered exactly once and the turn never strands.
#[tokio::test]
async fn a_prompt_racing_agent_settled_never_strands_the_turn() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("settle-race"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while dones(&events).len() < 2 {
        let event = tokio::time::timeout(Duration::from_secs(15), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let race_now = matches!(&event, AgentEvent::TextDelta { text } if text == "reply:settle-race")
            && !sent;
        events.push(event);
        if race_now {
            // The settle probe is in flight (the fixture holds its answer
            // open): the message lands behind the turn.
            sent = true;
            tokio::time::sleep(Duration::from_millis(150)).await;
            steer.send(SteerMessage::text("race steer")).await.unwrap();
        }
    }
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None), (DoneStatus::Completed, None)],
        "{events:?}"
    );
    // The raced message ran — joined the run or started the next turn.
    assert!(text_of(&events).contains("reply:settle-race"));
    assert!(text_of(&events).contains("reply:race steer"), "{events:?}");
    assert!(events.iter().any(|e| matches!(e, AgentEvent::Steered { .. })));
}

/// A settle probe that reports busy (pi still streaming) re-arms: the turn
/// ends only once pi is at rest — never stranded, never errored.
#[tokio::test]
async fn a_busy_settle_probe_re_arms_until_pi_rests() {
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request("busy-probe"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert_eq!(text_of(&events), "reply:busy-probe");
}

/// A settle probe that keeps failing ends the turn ERRORED — visibly, with
/// the reason — instead of hanging or silently killing the process.
#[tokio::test]
async fn a_failing_settle_probe_ends_the_turn_errored() {
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request("probe-fail"), controls).await;
    let dones = dones(&events);
    assert_eq!(dones.len(), 1, "{events:?}");
    assert_eq!(dones[0].0, DoneStatus::Errored);
    let error = dones[0].1.as_deref().unwrap();
    assert!(error.contains("probe") && error.contains("wedged"), "{error}");
}

/// pi older than 0.99 reports no disposition: the driver infers from whether
/// a run starts — a running prompt settles normally, and a handled one ends
/// the turn instead of sticking on Working forever.
#[tokio::test]
async fn old_pi_without_disposition_is_inferred_not_assumed() {
    let (first, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request("old-pi"), first).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert_eq!(text_of(&events), "reply:old-pi");

    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("old-pi-handled"), controls).await.unwrap();
    let mut events = Vec::new();
    until_done(&mut stream, &mut events, Duration::from_secs(15)).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // The session lives on: the next message runs.
    steer.send(SteerMessage::text("revived")).await.unwrap();
    let mut tail = Vec::new();
    until_done(&mut stream, &mut tail, Duration::from_secs(15)).await;
    assert_eq!(dones(&tail), vec![(DoneStatus::Completed, None)], "{tail:?}");
    assert_eq!(text_of(&tail), "reply:revived");
}

// ---------------------------------------------------------------------------
// Mid-turn steering (orchestration v2, ticket 03): pi is a step-boundary
// harness — steering mode "all" at session start, steers carrying images,
// clear_queue before abort, compaction recovery, bursts in order.
// ---------------------------------------------------------------------------

/// The steering mode is set to "all" at session start unless the user
/// configured one: pi's one-at-a-time default would deliver a burst of
/// steers one assistant turn at a time; roboco's steers belong together.
#[tokio::test]
async fn steering_mode_defaults_to_all_at_session_start() {
    let home = tempfile::tempdir().unwrap();
    let cwd = tempfile::tempdir().unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(
        &harness().with_agent_dir(home.path()),
        request_cwd("hello", &cwd.path().display().to_string()),
        controls,
    )
    .await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // The fixture records the wire selection in its session file.
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(recorded.contains("steering-mode:all"), "{recorded}");
}

/// A steering mode the user already configured (the agent directory's
/// settings — their own edit, or `/steering` picked globally) is never
/// overwritten: the driver sends no `set_steering_mode` at all.
#[tokio::test]
async fn a_configured_steering_mode_is_never_overwritten() {
    let home = tempfile::tempdir().unwrap();
    std::fs::write(
        home.path().join("settings.json"),
        r#"{"steeringMode": "one-at-a-time"}"#,
    )
    .unwrap();
    let cwd = tempfile::tempdir().unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(
        &harness().with_agent_dir(home.path()),
        request_cwd("hello", &cwd.path().display().to_string()),
        controls,
    )
    .await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(!recorded.contains("steering-mode:"), "{recorded}");
}

/// A mode configured in the project `.pi/settings.json` counts the same:
/// an explicit choice, never overwritten.
#[tokio::test]
async fn project_settings_count_as_a_configured_steering_mode() {
    let home = tempfile::tempdir().unwrap();
    let cwd = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(cwd.path().join(".pi")).unwrap();
    std::fs::write(
        cwd.path().join(".pi").join("settings.json"),
        r#"{"steeringMode": "all"}"#,
    )
    .unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(
        &harness().with_agent_dir(home.path()),
        request_cwd("hello", &cwd.path().display().to_string()),
        controls,
    )
    .await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(!recorded.contains("steering-mode:"), "{recorded}");
}

/// A mid-run steer carries its staged images inline — the same
/// `ImageContent` blocks a turn-start prompt rides — so steering with a
/// screenshot reaches pi. The fixture asserts the wire block itself.
#[tokio::test]
async fn steers_carry_their_attached_images() {
    let image = tempfile::tempdir().unwrap();
    let png = image.path().join("shot.png");
    std::fs::write(&png, b"\x89PNG\r\n\x1a\nroboco steer image").unwrap();
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-image"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "first part ")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            steer
                .send(SteerMessage {
                    prompt: "look at this".into(),
                    message_id: None,
                    attachments: vec![png.display().to_string()],
                    config: None,
                })
                .await
                .unwrap();
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert!(events.iter().any(|e| matches!(e, AgentEvent::Steered { .. })), "{events:?}");
    assert!(text_of(&events).contains("reply-to-image-steer"), "{events:?}");
}

/// Stop sends `clear_queue` before `abort`: steers pi queued but never
/// consumed are cancelled with the turn — never delivered into the aborted
/// run. The fixture asserts the wire order and that the cleared queue held
/// the stranded steer; here the run ends interrupted, exactly once.
#[tokio::test]
async fn stop_clears_pi_queue_before_abort() {
    let (controls, steer, token) = controls();
    let mut stream = harness().run(request("stop-clears-queue"), controls).await.unwrap();
    let events = tokio::time::timeout(Duration::from_secs(10), async {
        let mut events = Vec::new();
        let mut sent = false;
        while let Some(event) = stream.next().await {
            let event = event.unwrap();
            let steer_now =
                matches!(&event, AgentEvent::TextDelta { text } if text == "working") && !sent;
            let stop_now = matches!(&event, AgentEvent::TextDelta { text } if text == "steer queued");
            events.push(event);
            if steer_now {
                sent = true;
                steer.send(SteerMessage::text("stranded steer")).await.unwrap();
            }
            if stop_now {
                token.cancel();
            }
            if matches!(events.last(), Some(AgentEvent::Done { .. })) {
                break;
            }
        }
        events
    })
    .await
    .expect("stop must settle");
    assert_eq!(dones(&events), vec![(DoneStatus::Interrupted, None)], "{events:?}");
}

/// A run that recovered from an error through a successful compaction retry
/// ends completed, not errored: the recovered error no longer rejects the
/// prompt.
#[tokio::test]
async fn a_compaction_recovered_run_completes() {
    let (controls, steer, _token) = controls();
    drop(steer);
    let events = run_to_end(&harness(), request("compaction-recover"), controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // The overflow error surfaced (as a transient notice) and the retried
    // turn still completed the run.
    assert!(text_of(&events).contains("doomed"), "{events:?}");
    assert!(text_of(&events).contains("recovered"), "{events:?}");
    assert!(
        events.iter().any(|e| matches!(e, AgentEvent::Error { message, .. } if message.contains("overflow"))),
        "{events:?}"
    );
}

/// A burst mixing an image steer stays in mailbox order (B5): the driver
/// loads a steer's images before queueing its wire call, so the
/// FuturesUnordered's completion order cannot overtake the image steer
/// with the text steer queued after it — pi receives exactly
/// [text, image, text]. The fixture asserts the wire order and the image
/// block itself; here the burst folds in with its boundaries.
#[tokio::test]
async fn a_burst_with_an_image_steer_stays_in_order() {
    let image = tempfile::tempdir().unwrap();
    let png = image.path().join("burst.png");
    std::fs::write(&png, b"\x89PNG\r\n\x1a\nroboco burst image").unwrap();
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-burst-mixed"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "first part ")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            for (text, with_image) in [
                ("mixed one", None),
                ("mixed two", Some(png.display().to_string())),
                ("mixed three", None),
            ] {
                steer
                    .send(SteerMessage {
                        prompt: text.into(),
                        message_id: None,
                        attachments: with_image.map(|path| vec![path]).unwrap_or_default(),
                        config: None,
                    })
                    .await
                    .unwrap();
            }
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let steered = events
        .iter()
        .filter(|e| matches!(e, AgentEvent::Steered { .. }))
        .count();
    assert_eq!(steered, 3, "{events:?}");
    assert!(text_of(&events).contains("reply-to-mixed-burst"), "{events:?}");
}

/// A steer whose acknowledgement lands AFTER the settle does not end the
/// turn early (B4): the quiescent probe reads pi at rest while the input
/// is still in preflight, and the driver must wait it out — the turn ends
/// once, after the acked steer runs and settles in its own turn.
#[tokio::test]
async fn a_late_acknowledgement_does_not_end_the_turn_early() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("late-ack"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "working")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            steer.send(SteerMessage::text("late steer")).await.unwrap();
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    // Exactly one Done, and only after the late steer ran: the reply to
    // the steer is in the SAME turn (no Done between the settle and the
    // ack), and the turn completes.
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert!(text_of(&events).contains("reply-to-late-steer"), "{events:?}");
    assert!(events.iter().any(|e| matches!(e, AgentEvent::Steered { .. })), "{events:?}");
}

/// A message pi keeps rejecting is capped, not retried forever (B1): past
/// the re-delivery attempts the message is handed back to the engine as a
/// failed send and the turn ends ERRORED — one Error naming the attempts,
/// and NEVER a Done{Completed} for a message that never ran.
#[tokio::test]
async fn an_endlessly_rejected_message_is_capped_and_ends_the_turn_errored() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("reject-cap"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while dones(&events).len() < 2 {
        let event = tokio::time::timeout(Duration::from_secs(20), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        // The prompted turn completes and parks; the parked message goes
        // out after the first Done.
        let send_now =
            matches!(&event, AgentEvent::Done { status: DoneStatus::Completed, .. }) && !sent;
        events.push(event);
        if send_now {
            sent = true;
            steer.send(SteerMessage::text("always rejected")).await.unwrap();
        }
    }
    drop(steer);
    let dones = dones(&events);
    assert_eq!(dones.len(), 2, "{events:?}");
    assert_eq!(dones[0], (DoneStatus::Completed, None), "{events:?}");
    match &dones[1] {
        (DoneStatus::Errored, Some(error)) => assert!(
            error.contains("pi rejected the message 2 times"),
            "the cap ends the turn errored with the attempt count: {error}"
        ),
        other => panic!("the capped rejection ends the turn errored: {other:?}"),
    }
    // One rejection Error per attempt — and no third attempt (the cap).
    let rejections = events
        .iter()
        .filter(|e| matches!(e, AgentEvent::Error { message, .. } if message.contains("rejected")))
        .count();
    assert_eq!(rejections, 2, "{events:?}");
}

/// The mailbox never wedges behind a dead turn (B2): a message queued
/// behind the settle probe still runs as the next turn after the probe
/// fails out and the turn ends errored.
#[tokio::test]
async fn a_message_queued_behind_a_failing_probe_still_runs() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("probe-fail-mailbox"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while dones(&events).len() < 2 {
        let event = tokio::time::timeout(Duration::from_secs(20), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let send_now = matches!(
            &event,
            AgentEvent::TextDelta { text } if text == "reply:probe-fail-mailbox"
        ) && !sent;
        events.push(event);
        if send_now {
            // The fixture holds the first probe answer open: the message
            // lands behind the settling turn.
            sent = true;
            tokio::time::sleep(Duration::from_millis(300)).await;
            steer.send(SteerMessage::text("queued during the probe")).await.unwrap();
        }
    }
    drop(steer);
    let dones = dones(&events);
    assert_eq!(
        dones.len(),
        2,
        "the failed turn, then the queued message's own turn: {dones:?}"
    );
    assert_eq!(dones[0].0, DoneStatus::Errored);
    assert_eq!(dones[1].0, DoneStatus::Completed, "{events:?}");
    assert!(
        text_of(&events).contains("reply:queued during the probe"),
        "the queued message ran its own turn: {events:?}"
    );
}

/// A stranded steer is recovered INTACT (B6): the images it was sent with
/// ride the re-delivery too — the recovery re-queues the original
/// message, never a bare-text copy. The fixture asserts the re-delivered
/// prompt's image block on the wire.
#[tokio::test]
async fn a_stranded_steer_recovers_with_its_images() {
    let image = tempfile::tempdir().unwrap();
    let png = image.path().join("strand.png");
    std::fs::write(&png, b"\x89PNG\r\n\x1a\nroboco strand image").unwrap();
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-strand-image"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while dones(&events).len() < 2 {
        let event = tokio::time::timeout(Duration::from_secs(15), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "reply part")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            steer
                .send(SteerMessage {
                    prompt: "stranded with an image".into(),
                    message_id: None,
                    attachments: vec![png.display().to_string()],
                    config: None,
                })
                .await
                .unwrap();
        }
    }
    // Recovered via clear_queue after the first Done, re-delivered as the
    // next turn's prompt with its image (the fixture checks the wire).
    assert_eq!(
        dones(&events),
        vec![(DoneStatus::Completed, None), (DoneStatus::Completed, None)],
        "{events:?}"
    );
    assert!(
        text_of(&events).contains("reply-to-recovered-image-steer"),
        "the recovered steer ran its own turn: {events:?}"
    );
    assert!(events.iter().any(|e| matches!(e, AgentEvent::Steered { .. })), "{events:?}");
}

/// A Stop while the settle probe is armed during a LIVE run (B3: an old pi
/// with no disposition arms it at acceptance) still clears the queue and
/// aborts — the "already settled" shortcut is only for a truly settled
/// turn. The fixture records the abort in its session file.
#[tokio::test]
async fn stop_with_a_probe_armed_mid_run_still_aborts() {
    let cwd = tempfile::tempdir().unwrap();
    let (controls, _steer, token) = controls();
    let req = request_cwd("old-pi-stop", &cwd.path().display().to_string());
    let mut stream = harness().run(req, controls).await.unwrap();
    let mut events = Vec::new();
    while dones(&events).is_empty() {
        let event = tokio::time::timeout(Duration::from_secs(15), stream.next())
            .await
            .expect("progress")
            .expect("stream open")
            .expect("stream event");
        let stop_now = matches!(&event, AgentEvent::TextDelta { text } if text == "working");
        events.push(event);
        if stop_now {
            // Past the probe's re-arm delay: it is armed (pi reports busy).
            tokio::time::sleep(Duration::from_millis(400)).await;
            token.cancel();
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Interrupted, None)], "{events:?}");
    let recorded =
        std::fs::read_to_string(cwd.path().join(".pi-fixture-session.jsonl")).unwrap();
    assert!(recorded.contains("abort\n"), "Stop must abort the live run: {recorded}");
}

/// Several quick steers are all delivered, in order: pi (steering mode
/// "all") queues each while streaming and delivers them together at the
/// next step boundary. The fixture asserts the send order on the wire;
/// here every steer folds in with its own boundary and one turn ends.
#[tokio::test]
async fn a_burst_of_steers_is_delivered_in_order() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-burst"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "first part ")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            for text in ["burst one", "burst two", "burst three"] {
                steer.send(SteerMessage::text(text)).await.unwrap();
            }
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let steered = events
        .iter()
        .filter(|e| matches!(e, AgentEvent::Steered { .. }))
        .count();
    assert_eq!(steered, 3, "{events:?}");
    assert!(text_of(&events).contains("reply-to-burst"), "{events:?}");
}

// ---------------------------------------------------------------------------
// Provider turn refs + native forks (t3-orchestration-v2 ticket 19)
// ---------------------------------------------------------------------------

/// The session-tree helpers: the driver names the turn's ref after each
/// settle, the engine stamps it on the assistant entry, and a fork reads
/// the boundary off it. The unit level (walks, boundaries) lives in the
/// driver module; this proves the wire shape end to end.
fn session_file_of(events: &[AgentEvent]) -> String {
    events
        .iter()
        .find_map(|e| match e {
            AgentEvent::SessionStarted { session_id, .. } => Some(session_id.clone()),
            _ => None,
        })
        .expect("SessionStarted")
}

fn turn_refs_of(events: &[AgentEvent]) -> Vec<roboco_proto::ProviderTurnRef> {
    events
        .iter()
        .filter_map(|e| match e {
            AgentEvent::TurnRef { reference } => Some(reference.clone()),
            _ => None,
        })
        .collect()
}

/// Each settled turn emits its provider turn ref: the session file plus
/// the turn's user entry id — the first turn's prompt, and the second
/// turn's prompt once the parked session runs another message (the `since`
/// cursor bounds the second capture to the second turn's entries).
#[tokio::test]
async fn turn_refs_name_the_session_file_and_each_turns_user_entry() {
    let cwd = tempfile::tempdir().unwrap();
    let cwd = cwd.path().display().to_string();
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request_cwd("first", &cwd), controls).await.unwrap();
    let mut events = Vec::new();
    until_done(&mut stream, &mut events, Duration::from_secs(15)).await;
    // The parked session runs a second turn on the same process.
    steer.send(SteerMessage::text("second")).await.unwrap();
    let mut tail = Vec::new();
    until_done(&mut stream, &mut tail, Duration::from_secs(15)).await;
    events.extend(tail);
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None); 2], "{events:?}");
    let session = session_file_of(&events);
    assert_eq!(
        turn_refs_of(&events),
        vec![
            roboco_proto::ProviderTurnRef {
                harness: HarnessId::Pi,
                session_id: session.clone(),
                turn_id: "u1".into(),
            },
            roboco_proto::ProviderTurnRef {
                harness: HarnessId::Pi,
                session_id: session.clone(),
                turn_id: "u2".into(),
            },
        ],
        "{events:?}"
    );
    // The refs name the file the runs actually continue.
    assert!(session.ends_with(".pi-fixture-session.jsonl"), "{session}");
    assert!(std::path::Path::new(&session).is_file());
}

/// A turn a steer landed in names its LAST user entry — the steer — as the
/// ref: pi's exclusive fork boundary is "the user entry after the ref", so
/// naming the last consumed entry keeps the whole turn (prompt, steer and
/// reply) when a fork cuts at the next turn's start.
#[tokio::test]
async fn a_steered_turn_names_its_last_user_entry() {
    let (controls, steer, _token) = controls();
    let mut stream = harness().run(request("steer-flow"), controls).await.unwrap();
    let mut events = Vec::new();
    let mut sent = false;
    while let Some(event) = stream.next().await {
        let event = event.unwrap();
        let steer_now = matches!(&event, AgentEvent::TextDelta { text } if text == "first part ")
            && !sent;
        events.push(event);
        if steer_now {
            sent = true;
            steer.send(SteerMessage::text("steered text")).await.unwrap();
        }
        if matches!(events.last(), Some(AgentEvent::Done { .. })) {
            break;
        }
    }
    let session = session_file_of(&events);
    assert_eq!(
        turn_refs_of(&events),
        vec![roboco_proto::ProviderTurnRef {
            harness: HarnessId::Pi,
            session_id: session,
            // u1 = the prompt, u2 = the steer: the turn consumed the steer
            // last, so that is the ref.
            turn_id: "u2".into(),
        }],
        "{events:?}"
    );
}

/// A native fork at the source's LATEST turn: the fork process launches
/// with `--fork` and sends no RPC fork (the copy already ends at the
/// boundary), the run resumes the FORKED session file, and nothing is
/// pasted — the fake refuses the turn if the budgeted history reaches the
/// prompt or the run resumes anything but the forked file.
#[tokio::test]
async fn a_native_fork_at_the_latest_turn_copies_the_whole_session() {
    let cwd = tempfile::tempdir().unwrap();
    let source = cwd.path().join("source-session.jsonl");
    std::fs::write(&source, b"fixture session\n").unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-turn", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: source.display().to_string(),
        // The fake fork tree's NEWEST user entry: forking there keeps the
        // whole copied session — no RPC fork.
        last_turn: "u3".into(),
        // What the engine would have pasted for a fresh session — the
        // driver must NOT use it when the fork succeeds.
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // The run continues the FORKED session file, never the source.
    let session = session_file_of(&events);
    assert!(session.ends_with(".pi-fixture-forked.jsonl"), "{session}");
    assert!(std::path::Path::new(&session).is_file());
    assert_ne!(session, source.display().to_string());
    // The fork process ran with --fork and sent NO fork RPC: the markers
    // in the forked session file prove both.
    let forked = std::fs::read_to_string(cwd.path().join(".pi-fixture-forked.jsonl")).unwrap();
    assert!(forked.contains("fork-launch:"), "{forked}");
    assert!(!forked.contains("fork-rpc:"), "{forked}");
    assert_eq!(text_of(&events), "reply:fork-turn");
}

/// A native fork at an EARLIER turn also sends the RPC `fork` with the
/// user entry id of the turn AFTER the boundary: pi's fork is exclusive
/// (it re-roots before the named user message), so the forked session ends
/// exactly at the boundary. Nothing is pasted (the fake refuses it).
#[tokio::test]
async fn a_native_fork_at_an_earlier_turn_forks_at_the_next_turns_user_entry() {
    let cwd = tempfile::tempdir().unwrap();
    let source = cwd.path().join("source-session.jsonl");
    std::fs::write(&source, b"fixture session\n").unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-turn", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: source.display().to_string(),
        // The fake fork tree's SECOND user entry: the boundary is turn 2,
        // so the RPC fork must name turn 3's user entry (u3).
        last_turn: "u2".into(),
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let session = session_file_of(&events);
    assert!(session.ends_with(".pi-fixture-forked.jsonl"), "{session}");
    // The RPC fork named the turn AFTER the boundary.
    let forked = std::fs::read_to_string(cwd.path().join(".pi-fixture-forked.jsonl")).unwrap();
    assert!(forked.contains("fork-rpc:u3"), "{forked}");
    assert_eq!(text_of(&events), "reply:fork-turn");
}

/// A stale boundary ref (an entry the session branch no longer holds)
/// refuses the fork: the run starts a FRESH session with the budgeted
/// transcript in front of the prompt — never a failed send. The fake
/// asserts the pasted history and refuses a --session resume.
#[tokio::test]
async fn a_stale_fork_boundary_falls_back_to_the_budgeted_paste() {
    let cwd = tempfile::tempdir().unwrap();
    let source = cwd.path().join("source-session.jsonl");
    std::fs::write(&source, b"fixture session\n").unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-fallback", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: source.display().to_string(),
        // Not on the fake tree branch: the driver must refuse the fork.
        last_turn: "u-gone".into(),
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    // A fresh session served the turn (the fake refuses the fork-turn
    // scenario on a resume).
    let session = session_file_of(&events);
    assert!(session.ends_with(".pi-fixture-session.jsonl"), "{session}");
    assert_eq!(text_of(&events), "reply:fork-fallback");
    // The refusal surfaced.
    assert!(
        events.iter().any(|e| matches!(
            e,
            AgentEvent::Error { message, .. } if message.contains("pi could not fork")
        )),
        "{events:?}"
    );
    // No fork RPC ever ran (the marker proves the refusal came first).
    let forked = cwd.path().join(".pi-fixture-forked.jsonl");
    if forked.exists() {
        let forked = std::fs::read_to_string(&forked).unwrap();
        assert!(!forked.contains("fork-rpc:"), "{forked}");
    }
}

/// A fork a pi extension cancels falls back the same way: fresh session,
/// budgeted transcript, visible notice.
#[tokio::test]
async fn a_cancelled_fork_falls_back_to_the_budgeted_paste() {
    let cwd = tempfile::tempdir().unwrap();
    let source = cwd.path().join("fork-cancel-source.jsonl");
    std::fs::write(&source, b"fixture session\n").unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-fallback", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: source.display().to_string(),
        // An earlier-turn boundary, so the cancelled RPC actually fires
        // (the source name scripts the cancellation).
        last_turn: "u2".into(),
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let session = session_file_of(&events);
    assert!(session.ends_with(".pi-fixture-session.jsonl"), "{session}");
    assert_eq!(text_of(&events), "reply:fork-fallback");
    assert!(
        events.iter().any(|e| matches!(
            e,
            AgentEvent::Error { message, .. } if message.contains("pi could not fork")
        )),
        "{events:?}"
    );
}

/// A fork whose source session file is gone never spawns the fork process:
/// the run starts fresh with the budgeted paste (the same fallback as a
/// lost resume pointer).
#[tokio::test]
async fn a_fork_with_a_missing_source_file_falls_back_to_the_budgeted_paste() {
    let cwd = tempfile::tempdir().unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-fallback", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: cwd.path().join("no-such-session.jsonl").display().to_string(),
        last_turn: "u1".into(),
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    assert_eq!(text_of(&events), "reply:fork-fallback");
    assert!(
        events.iter().any(|e| matches!(
            e,
            AgentEvent::Error { message, .. } if message.contains("pi could not fork")
        )),
        "{events:?}"
    );
    // No fork process ever ran.
    assert!(!cwd.path().join(".pi-fixture-forked.jsonl").exists());
}

/// A fork RPC pi REFUSES (success: false) falls back the same way: fresh
/// session, budgeted transcript, visible notice.
#[tokio::test]
async fn a_refused_fork_falls_back_to_the_budgeted_paste() {
    let cwd = tempfile::tempdir().unwrap();
    let source = cwd.path().join("fork-refuse-source.jsonl");
    std::fs::write(&source, b"fixture session\n").unwrap();
    let (controls, steer, _token) = controls();
    drop(steer);
    let mut req = request_cwd("fork-fallback", &cwd.path().display().to_string());
    req.provider_fork = Some(roboco_proto::ProviderFork {
        session_id: source.display().to_string(),
        // An earlier-turn boundary, so the refused RPC actually fires
        // (the source name scripts the refusal).
        last_turn: "u2".into(),
        fallback_history: Some(
            "Continue this side chat using the following prior transcript as context.\n\
             <transcript>\nuser: Remember PINEAPPLE\n</transcript>\n\n"
                .into(),
        ),
    });
    let events = run_to_end(&harness(), req, controls).await;
    assert_eq!(dones(&events), vec![(DoneStatus::Completed, None)], "{events:?}");
    let session = session_file_of(&events);
    assert!(session.ends_with(".pi-fixture-session.jsonl"), "{session}");
    assert_eq!(text_of(&events), "reply:fork-fallback");
    assert!(
        events.iter().any(|e| matches!(
            e,
            AgentEvent::Error { message, .. } if message.contains("pi could not fork")
        )),
        "{events:?}"
    );
    // The refused RPC still ran (its marker proves the refusal, not a
    // silent skip).
    let forked = std::fs::read_to_string(cwd.path().join(".pi-fixture-forked.jsonl")).unwrap();
    assert!(forked.contains("fork-rpc:u3"), "{forked}");
}
