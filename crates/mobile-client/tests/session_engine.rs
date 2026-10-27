//! Real-engine session tests: every frame comes from an in-process
//! `EngineCore` served over loopback (spec "Testing Decisions" — no protocol
//! mocks). Coverage follows ticket 08's acceptance list: the send flow
//! (echo → adoption → streaming), the busy policies, the queue and the edit
//! lease, questions, offline refusal, the transcript cache, the warm set,
//! the live catalogs, file search, chat config, and the layout-facing
//! snapshot events.
//!
//! Mock-harness env knobs (`ROBOCO_MOCK_DELAY_MS`, `ROBOCO_MOCK_QUESTION`)
//! are process-global: every test in this file takes [`MOCK_ENV`], so a
//! knob set by one test never leaks into another's runs under `cargo test`
//! (nextest isolates per process anyway).

mod support;

use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry, HarnessId};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_mobile_client::session::SendRequest;
use roboco_mobile_client::{
    BusyPolicy, Client, ClientError, EngineState, OutgoingAttachment, SendOutcome, SendState,
    SessionHandle,
};
use roboco_proto::{
    AgentEvent, ChatConfig, DoneStatus, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SteeringMode,
};

use support::{Recorder, core as engine_core, pairing_url, serve, serve_on_port, test_client, wait_for_fleet, wait_for_view};

/// Serializes every mock-engine test in this binary (env knobs are
/// process-global).
static MOCK_ENV: OnceLock<Mutex<()>> = OnceLock::new();

fn mock_env() -> &'static Mutex<()> {
    MOCK_ENV.get_or_init(|| Mutex::new(()))
}

fn set_env(name: &str, value: Option<&str>) {
    // SAFETY: every mock-engine test in this binary holds `MOCK_ENV`, so no
    // other thread reads or writes the environment while this runs.
    unsafe {
        match value {
            Some(value) => std::env::set_var(name, value),
            None => std::env::remove_var(name),
        }
    }
}

/// Run `f` while `vars` are set, restoring the previous values after.
async fn with_mock_env<T>(vars: &[(&str, &str)], f: impl Future<Output = T>) -> T {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let previous: Vec<(&str, Option<String>)> = vars
        .iter()
        .map(|(name, _)| (*name, std::env::var(name).ok()))
        .collect();
    for (name, value) in vars {
        set_env(name, Some(value));
    }
    let result = f.await;
    for (name, value) in previous {
        set_env(name, value.as_deref());
    }
    result
}

/// Poll until `predicate` holds (bounded), with a helpful label. The bound
/// is generous: nextest runs every test as its own process and a full-suite
/// run competes for the machine with a dozen engines assembling.
async fn wait_until(predicate: impl Fn() -> bool, label: &str) {
    let deadline = Instant::now() + Duration::from_secs(60);
    while !predicate() {
        assert!(Instant::now() < deadline, "timed out waiting for: {label}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

/// A paired, connected client plus its engine core and dirs.
struct Rig {
    client: Arc<Client>,
    core: EngineCore,
    engine_id: String,
    engine_dir: tempfile::TempDir,
    data_dir: tempfile::TempDir,
    listener: EngineListenerHandle,
    recorder: Arc<Recorder>,
}

impl Rig {
    /// Re-serve the engine's listener on its fixed port after a stop.
    async fn restart_listener(&mut self) {
        if self.listener.inner.is_some() {
            return;
        }
        let core = &self.core;
        let dir = self.engine_dir.path().to_owned();
        self.listener.inner = Some(serve_on_port(core, &dir, self.listener.port).await);
    }
}

/// The engine listener, kept alive (and restartable on its fixed port).
struct EngineListenerHandle {
    port: u16,
    inner: Option<roboco_engine::EngineListener>,
}

impl EngineListenerHandle {
    fn stop(&mut self) {
        self.inner.take();
    }
}

impl Drop for EngineListenerHandle {
    fn drop(&mut self) {
        self.inner.take();
    }
}

/// Pair a fresh engine on a fixed port (so it can be stopped and re-served
/// for the reconnect tests), returning a connected client.
async fn rig() -> Rig {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = engine_core(engine_dir.path());
    // A fixed port so the listener can be re-served after a stop.
    let port = {
        let probe = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        probe.local_addr().unwrap().port()
    };
    let listener = serve_on_port(&core, engine_dir.path(), port).await;
    let host = listener.address.to_string();
    let url = format!("http://{host}/pair#token={}", {
        let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
        store.create_code("test", 300).unwrap().credential
    });
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder.clone());
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    wait_for_fleet(&client, "feed rows", |fleet| {
        fleet
            .engine(&record.engine_id)
            .is_some_and(|engine| engine.rows.chats.iter().any(|chat| chat.id == "same-chat"))
    })
    .await;
    Rig {
        client,
        core,
        engine_id: record.engine_id,
        engine_dir,
        data_dir,
        listener: EngineListenerHandle {
            port,
            inner: Some(listener),
        },
        recorder,
    }
}

impl Rig {
    /// The scoped id of the engine's chat.
    fn chat_id(&self) -> String {
        self.client.scope(&self.engine_id, "same-chat")
    }

    fn open(&self) -> SessionHandle {
        self.client.open_session(&self.chat_id()).unwrap()
    }
}

/// A harness that records every `RunRequest` and completes a one-line turn.
struct RecordingHarness {
    requests: Arc<Mutex<Vec<RunRequest>>>,
}

#[async_trait]
impl Harness for RecordingHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Recorder"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[ReasoningLevel::Medium]
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        Ok(vec![Model {
            id: "mock-1".into(),
            label: "Mock 1".into(),
            description: None,
            reasoning_levels: vec![ReasoningLevel::Medium],
            options: vec![],
        }])
    }
    async fn run(
        &self,
        request: RunRequest,
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.requests.lock().unwrap().push(request.clone());
        let events: Vec<Result<AgentEvent, HarnessError>> = vec![
            Ok(AgentEvent::SessionStarted {
                harness: HarnessId::Mock,
                model: "mock-1".into(),
                tools: vec![],
                cwd: request.cwd.clone(),
                session_id: format!("sess-{}", self.requests.lock().unwrap().len()),
                assistant_message_id: format!("a-{}", self.requests.lock().unwrap().len()).into(),
            }),
            Ok(AgentEvent::TextDelta {
                text: format!("ack: {}", request.prompt),
            }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: None,
            }),
        ];
        Ok(futures::stream::iter(events).boxed())
    }
}

/// Pair a client against an engine whose mock harness records RunRequests.
async fn recording_rig() -> (Rig, Arc<Mutex<Vec<RunRequest>>>) {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let registry = HarnessRegistry::new();
    let requests = Arc::new(Mutex::new(Vec::new()));
    registry.register(Arc::new(RecordingHarness {
        requests: requests.clone(),
    }));
    let core = EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(engine_dir.path()).unwrap(),
        Arc::new(registry),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat("same-chat", None, Some(&core.device_id), None, None)
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder.clone());
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    wait_for_fleet(&client, "feed rows", |fleet| {
        fleet
            .engine(&record.engine_id)
            .is_some_and(|engine| engine.rows.chats.iter().any(|chat| chat.id == "same-chat"))
    })
    .await;
    (
        Rig {
            client,
            core,
            engine_id: record.engine_id,
            engine_dir,
            data_dir,
            listener: EngineListenerHandle {
                port: listener.address.port(),
                inner: Some(listener),
            },
            recorder,
        },
        requests,
    )
}

// ── the core flow ──────────────────────────────────────────────────────────

/// Open → reset snapshot → send (idle) returns `Started`; the host user
/// entry adopts the echo (pending empty, no duplicate row); the mock reply
/// streams in; `hydrated` is true; `SessionChanged` fired.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn open_session_sends_adopts_and_streams() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let rig = rig().await;
    let handle = rig.open();
    // The chat is known to the client; the handle is warm-cached.
    assert_eq!(handle.chat_id(), rig.chat_id());
    assert_eq!(handle.engine_id(), rig.engine_id);
    assert!(rig.client.session(&rig.chat_id()).is_some());
    // Nothing yet: a loader, not an empty chat.
    assert!(!handle.snapshot().hydrated || handle.snapshot().entries.is_empty());
    let outcome = handle.send(SendRequest::text("hello")).await.unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("an idle send starts a turn: {outcome:?}")
    };
    // Adoption: the host user entry reuses the client-minted id.
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == message_id && e.role == MessageRole::User)
        },
        "the user entry lands with the sent id",
    )
    .await;
    wait_until(
        || handle.snapshot().pending.is_empty(),
        "the echo is adopted (no duplicate row)",
    )
    .await;
    // The mock reply streams in.
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.role == MessageRole::Assistant && !e.parts.is_empty())
        },
        "the assistant reply arrives",
    )
    .await;
    let status = handle.transcript_status();
    assert!(status.hydrated, "hydrated");
    assert_eq!(status.pending_count, 0, "no pending echo left");
    assert!(status.last_entry_id.is_some(), "a last entry id exists");
    // Events flowed for the transcript and the composer.
    let events = rig.recorder.events();
    assert!(events.iter().any(|event| matches!(
        event,
        roboco_mobile_client::ClientEvent::SessionChanged { chat_id, .. } if chat_id == &rig.chat_id()
    )));
    assert!(events.iter().any(|event| matches!(
        event,
        roboco_mobile_client::ClientEvent::ComposerChanged { chat_id, .. } if chat_id == &rig.chat_id()
    )));
    rig.client.shutdown();
}

/// An empty message is refused client-side.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_empty_send_is_refused() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let rig = rig().await;
    let handle = rig.open();
    let error = handle.send(SendRequest::text("   ")).await.unwrap_err();
    assert!(matches!(error, ClientError::InvalidArgument { message } if message == "empty message"));
    rig.client.shutdown();
}

/// With the engine stopped, `send` returns `HostUnavailable` within 50 ms
/// and creates no echo.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_offline_engine_refuses_the_send_with_no_echo() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    let handle = rig.open();
    rig.listener.stop();
    wait_for_view(&rig.client, &rig.engine_id, "disconnected", |view| {
        view.state != EngineState::Connected
    })
    .await;
    let started = Instant::now();
    let error = handle.send(SendRequest::text("offline")).await.unwrap_err();
    let elapsed = started.elapsed();
    assert!(
        matches!(error, ClientError::HostUnavailable { ref message } if message == "Engine is offline; reconnecting"),
        "got: {error:?}"
    );
    assert!(
        elapsed < Duration::from_millis(2_000),
        "the refusal is immediate, not a timeout ({elapsed:?})"
    );
    assert!(
        handle.snapshot().pending.is_empty(),
        "nothing is queued locally while offline"
    );
    rig.client.shutdown();
}

/// The RunRequest contract: `autoApprove == false` (PR #700), an empty
/// (never null) `modelOptions` object, `sandbox == workspace-write`, and
/// the cwd fallback.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_run_request_contract_is_autoapprove_false() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let (rig, requests) = recording_rig().await;
    let handle = rig.open();
    handle.send(SendRequest::text("contract")).await.unwrap();
    wait_until(|| !requests.lock().unwrap().is_empty(), "the run executed").await;
    let request = requests.lock().unwrap()[0].clone();
    assert!(!request.auto_approve, "autoApprove is always false");
    assert!(
        request.model_options.is_empty(),
        "modelOptions is {} (an object), never null",
        if request.model_options.is_empty() { "empty" } else { "non-empty" }
    );
    assert_eq!(request.sandbox, SandboxLevel::WorkspaceWrite);
    assert!(
        !request.cwd.is_empty(),
        "the chat cwd falls back to a home folder (the engine normalizes `~`): {:?}",
        request.cwd
    );
    assert!(request.prompt.ends_with("contract"));
    assert!(request.resume.is_none());
    rig.client.shutdown();
}

// ── the busy policies ──────────────────────────────────────────────────────

/// `ROBOCO_MOCK_DELAY_MS` keeps a turn running long enough to exercise the
/// busy table end-to-end.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn busy_sends_queue_steer_and_stop_and_send() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "600")], async {
        let rig = rig().await;
        let handle = rig.open();
        // Warm the live harness list first: the composer's
        // `mid_turn_steering` flag is known only once the catalog is fetched
        // (the chip menus do this on the real app).
        let harnesses = rig.client.list_harnesses(&rig.engine_id).await;
        assert!(
            harnesses.iter().any(|h| h.id == "mock"),
            "the smoke registry offers the mock in tests: {harnesses:?}"
        );
        // Turn 1: a delayed run keeps the host busy.
        handle.send(SendRequest::text("first")).await.unwrap();
        wait_until(|| handle.composer().live.turn_running, "the turn starts").await;

        // Busy + Queue (the default): parked on the shared queue.
        let outcome = handle
            .send(SendRequest { busy: BusyPolicy::Queue, ..SendRequest::text("parked") })
            .await
            .unwrap();
        let SendOutcome::Queued { queue_id } = outcome else {
            panic!("a busy Queue send parks the row: {outcome:?}")
        };
        wait_until(
            || {
                handle
                    .composer()
                    .queue
                    .iter()
                    .any(|row| row.id == queue_id && row.hold_for_turn_end)
            },
            "the queue row is visible with hold_for_turn_end",
        )
        .await;
        // No echo for the queued row: the row represents it. (The FIRST
        // send's echo may still await adoption — that one is its own.)
        assert!(
            handle
                .snapshot()
                .pending
                .iter()
                .all(|p| !p.text.starts_with("parked")),
            "a queued row creates no pending echo"
        );

        // Busy + Steer: the mock steers mid-turn (StepBoundary) — the turn
        // keeps running.
        let outcome = handle
            .send(SendRequest { busy: BusyPolicy::Steer, ..SendRequest::text("steered") })
            .await
            .unwrap();
        let SendOutcome::Steered { message_id } = outcome else {
            panic!("a busy Steer send steers the live turn: {outcome:?}")
        };
        wait_until(
            || {
                handle
                    .snapshot()
                    .entries
                    .iter()
                    .any(|e| e.id == message_id && e.role == MessageRole::User)
            },
            "the steered message lands",
        )
        .await;
        // The turn was NOT aborted: an assistant entry completes eventually.
        wait_until(
            || {
                handle.snapshot().entries.iter().any(|e| {
                    e.role == MessageRole::Assistant
                        && e.status == Some(MessageStatus::Complete)
                })
            },
            "the steered turn completes",
        )
        .await;

        // Busy + Interrupt: the turn is aborted and the new prompt runs as
        // the next turn, with the user entry id == the returned id.
        let outcome = handle
            .send(SendRequest { busy: BusyPolicy::Interrupt, ..SendRequest::text("stop and send") })
            .await
            .unwrap();
        let SendOutcome::Started { message_id } = outcome else {
            panic!("an Interrupt send stops and runs: {outcome:?}")
        };
        wait_until(
            || {
                handle
                    .snapshot()
                    .entries
                    .iter()
                    .any(|e| e.id == message_id && e.role == MessageRole::User)
            },
            "the stop-and-send message runs as the next turn",
        )
        .await;
        // The interrupted turn's assistant entry reads Aborted.
        wait_until(
            || {
                handle.snapshot().entries.iter().any(|e| {
                    e.role == MessageRole::Assistant && e.status == Some(MessageStatus::Aborted)
                })
            },
            "the interrupted turn is marked aborted",
        )
        .await;
        // The queue row from the Queue send is delivered after the turn ends.
        wait_until(
            || {
                handle
                    .snapshot()
                    .entries
                    .iter()
                    .any(|e| e.id == queue_id && e.role == MessageRole::User)
            },
            "the queued row is delivered at the turn boundary",
        )
        .await;
        rig.client.shutdown();
    })
    .await;
}

/// `interrupt()` stops a delayed turn; `turn_running` becomes false once
/// `WatchSessions` reports it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn interrupt_stops_a_running_turn() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "900")], async {
        let rig = rig().await;
        let handle = rig.open();
        handle.send(SendRequest::text("long turn")).await.unwrap();
        wait_until(|| handle.composer().live.turn_running, "the turn starts").await;
        handle.interrupt().await.unwrap();
        wait_until(|| !handle.composer().live.turn_running, "the turn stops").await;
        rig.client.shutdown();
    })
    .await;
}

// ── questions ──────────────────────────────────────────────────────────────

/// `ROBOCO_MOCK_QUESTION=1`: `open_input` appears with the mock's questions;
/// `respond_input` resolves it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn questions_appear_and_resolve() {
    with_mock_env(&[("ROBOCO_MOCK_QUESTION", "1")], async {
        let rig = rig().await;
        let handle = rig.open();
        handle.send(SendRequest::text("ask me")).await.unwrap();
        wait_until(
            || handle.composer().open_input.is_some(),
            "the question panel opens",
        )
        .await;
        let input = handle.composer().open_input.clone().unwrap();
        assert!(!input.questions.is_empty());
        let question = &input.questions[0];
        let label = question.options.first().cloned().unwrap_or_default();
        handle
            .respond_input(
                &input.request_id,
                vec![roboco_proto::UserInputAnswer {
                    question_id: question.id.clone(),
                    labels: vec![label],
                }],
            )
            .await
            .unwrap();
        wait_until(
            || handle.composer().open_input.is_none(),
            "the question panel closes",
        )
        .await;
        rig.client.shutdown();
    })
    .await;
}

// ── the queue and the edit lease ───────────────────────────────────────────

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn queue_actions_and_the_edit_lease() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "900")], async {
    // A busy turn keeps the queue from draining: the engine delivers the
    // head the moment the agent is idle, so rows only park mid-turn.
    let rig = rig().await;
    let handle = rig.open();
    handle.send(SendRequest::text("the busy turn")).await.unwrap();
    wait_until(|| handle.composer().live.turn_running, "the turn starts").await;
    // Two rows to move, edit and deliver.
    let first = handle.enqueue("first row", Vec::new(), false).await.unwrap();
    let second = handle.enqueue("second row", Vec::new(), false).await.unwrap();
    wait_until(
        || handle.composer().queue.len() == 2,
        "both queue rows arrive",
    )
    .await;
    // Both rows are this client's.
    assert!(handle.composer().queue.iter().all(|row| row.from_this_device));

    // Move: a nudge up swaps them; an out-of-range move returns false.
    assert!(handle.move_queued_by(&second, -1).await.unwrap());
    wait_until(
        || {
            let queue = &handle.composer().queue;
            queue.len() == 2 && queue[0].id == second && queue[1].id == first
        },
        "the move lands through WatchQueue",
    )
    .await;
    assert!(!handle.move_queued(&first, 9).await.unwrap());

    // Begin: acquired; a second instance is locked out.
    let lease = match handle.begin_queued_edit(&second, "instance-a").await {
        roboco_mobile_client::QueueEditStart::Acquired(lease) => lease,
        other => panic!("the first edit acquires: {other:?}"),
    };
    assert_eq!(lease.row_id, second);
    assert_eq!(lease.text, "second row");
    assert!(matches!(
        handle.begin_queued_edit(&second, "instance-b").await,
        roboco_mobile_client::QueueEditStart::Locked
    ));
    // Renewal is true while the lease is live.
    assert!(handle.renew_queued_edit(&lease).await);

    // Commit with a wrong hash: the engine compares the expected hash —
    // commit sends lease.base_text_hash, so a "wrong hash" comes from a
    // stale lease: finish the first lease with a forged base hash.
    let mut forged = lease.clone();
    forged.base_text_hash = "deadbeef".into();
    assert!(matches!(
        handle
            .finish_queued_edit(forged, roboco_mobile_client::QueueEditAction::Commit, Some("edited".into()), None)
            .await,
        roboco_mobile_client::QueueEditFinish::Conflict
    ));

    // Commit with the real lease: the row updates.
    assert!(matches!(
        handle
            .finish_queued_edit(lease, roboco_mobile_client::QueueEditAction::Commit, Some("second row, edited".into()), None)
            .await,
        roboco_mobile_client::QueueEditFinish::Finished
    ));
    wait_until(
        || {
            handle
                .composer()
                .queue
                .iter()
                .any(|row| row.id == second && row.visible_text == "second row, edited")
        },
        "the committed text lands",
    )
    .await;

    // `attachments: Some([])` clears the row's attachments: enqueue a row
    // with an attachment path, acquire, commit an empty set.
    let attached = handle
        .enqueue("with image", vec!["/uploads/ab12cd34-pic.png".to_owned()], false)
        .await
        .unwrap();
    wait_until(
        || handle.composer().queue.iter().any(|row| row.id == attached),
        "the attached row arrives",
    )
    .await;
    let lease = match handle.begin_queued_edit(&attached, "instance-c").await {
        roboco_mobile_client::QueueEditStart::Acquired(lease) => lease,
        other => panic!("the attached row acquires: {other:?}"),
    };
    assert!(matches!(
        handle
            .finish_queued_edit(lease, roboco_mobile_client::QueueEditAction::Commit, Some("with image".into()), Some(Vec::new()))
            .await,
        roboco_mobile_client::QueueEditFinish::Finished
    ));
    wait_until(
        || {
            handle
                .composer()
                .queue
                .iter()
                .any(|row| row.id == attached && row.attachments.is_empty())
        },
        "Some([]) clears the attachments",
    )
    .await;

    // After finish, renewal returns false.
    // (The lease above was consumed; a fresh one on the first row.)
    let lease = match handle.begin_queued_edit(&first, "instance-d").await {
        roboco_mobile_client::QueueEditStart::Acquired(lease) => lease,
        other => panic!("the first row acquires: {other:?}"),
    };
    assert!(matches!(
        handle
            .finish_queued_edit(lease, roboco_mobile_client::QueueEditAction::Release, None, None)
            .await,
        roboco_mobile_client::QueueEditFinish::Finished
    ));

    // Remove: the engine acks, the row leaves the queue.
    assert!(handle.remove_queued(&first).await.unwrap());
    wait_until(
        || !handle.composer().queue.iter().any(|row| row.id == first),
        "the removed row leaves the queue",
    )
    .await;
    // Clear the queue error surface.
    handle.clear_queue_error();
    assert!(handle.composer().queue_error.is_none());
    rig.client.shutdown();
    })
    .await;
}

/// #592: `deliver_queued_now` steers a text row (the turn is NOT aborted);
/// an attachment row sends now (interrupting).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn deliver_queued_now_resolves_by_row_kind() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "700")], async {
        let rig = rig().await;
        let handle = rig.open();
        // A delayed turn, then a text row.
        handle.send(SendRequest::text("the live turn")).await.unwrap();
        wait_until(|| handle.composer().live.turn_running, "the turn starts").await;
        let text_row = handle.enqueue("steer me", Vec::new(), false).await.unwrap();
        wait_until(
            || handle.composer().queue.iter().any(|row| row.id == text_row),
            "the text row arrives",
        )
        .await;
        assert!(handle.deliver_queued_now(&text_row).await.unwrap());
        // The turn was not aborted: the assistant entry completes.
        wait_until(
            || {
                handle.snapshot().entries.iter().any(|e| {
                    e.role == MessageRole::Assistant
                        && e.status == Some(MessageStatus::Complete)
                })
            },
            "the steered turn completes (not aborted)",
        )
        .await;
        wait_until(
            || {
                handle
                    .snapshot()
                    .entries
                    .iter()
                    .any(|e| e.id == text_row && e.role == MessageRole::User)
            },
            "the steered row lands",
        )
        .await;

        // A second delayed turn, then an attachment row (a real uploaded
        // path, so the engine can dispatch it).
        handle.send(SendRequest::text("another turn")).await.unwrap();
        wait_until(|| handle.composer().live.turn_running, "the second turn starts").await;
        let path = rig
            .client
            .upload_attachment(&rig.engine_id, "pic.png", vec![1, 2, 3, 4], None)
            .await
            .unwrap();
        let attach_row = handle
            .enqueue("with the image", vec![path], false)
            .await
            .unwrap();
        wait_until(
            || handle.composer().queue.iter().any(|row| row.id == attach_row),
            "the attachment row arrives",
        )
        .await;
        assert!(handle.deliver_queued_now(&attach_row).await.unwrap());
        // Send-now interrupts: the turn is aborted.
        wait_until(
            || {
                handle.snapshot().entries.iter().any(|e| {
                    e.role == MessageRole::Assistant
                        && e.status == Some(MessageStatus::Aborted)
                })
            },
            "send-now aborts the live turn",
        )
        .await;
        rig.client.shutdown();
    })
    .await;
}

// ── attachments ────────────────────────────────────────────────────────────

/// Uploading a 1.4 MB image (3 chunks) returns a host path; the read-back is
/// byte-identical; progress is monotonic and ends at 1.0; 25 MB is rejected
/// client-side; a send with the attachment produces the exact trailer and
/// `RunRequest.attachments == [path]`, and the echo renders before the RPC
/// completes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn attachments_upload_read_back_and_send() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let (rig, requests) = recording_rig().await;
    let handle = rig.open();
    // 1.4 MB base64-encodes to ~1.87 MB → 3 chunks of 680 000.
    let bytes: Vec<u8> = (0..1_400_000u32).map(|i| (i % 251) as u8).collect();
    let progress = Arc::new(ProgressRecorder::default());
    let path = rig
        .client
        .upload_attachment(
            &rig.engine_id,
            "pic with spaces.png",
            bytes.clone(),
            Some(Arc::new({
                let progress = progress.clone();
                move |fraction| progress.push(fraction)
            })),
        )
        .await
        .unwrap();
    assert!(!path.is_empty());
    assert!(path.contains(std::path::MAIN_SEPARATOR) || path.contains('/'), "an absolute host path: {path}");
    let progress_values = progress.values();
    assert!(!progress_values.is_empty());
    assert_eq!(progress_values.last().copied(), Some(1.0), "progress ends at 1.0");
    assert!(
        progress_values.windows(2).all(|pair| pair[0] <= pair[1]),
        "progress is monotonic: {progress_values:?}"
    );
    // The read-back is byte-identical (from the cache).
    let read = rig
        .client
        .read_attachment(&rig.engine_id, &path)
        .await
        .unwrap();
    assert_eq!(read, bytes);
    // 25 MB is rejected client-side.
    let error = rig
        .client
        .upload_attachment(&rig.engine_id, "big.png", vec![0; 25 * 1024 * 1024], None)
        .await
        .unwrap_err();
    assert!(
        matches!(error, ClientError::InvalidArgument { ref message } if message.contains("larger than 24 MB")),
        "got: {error:?}"
    );

    // A send with the attachment: the echo appears in snapshot.pending
    // BEFORE the RPC completes (the upload is in flight), then the run
    // carries the exact trailer and the path.
    let attachment = OutgoingAttachment {
        name: "photo.png".into(),
        mime_type: "image/png".into(),
        data: bytes.clone(),
    };
    let send_handle = handle.clone();
    let send_task = tokio::spawn(async move {
        send_handle
            .send(SendRequest {
                text: "look at this".into(),
                attachments: vec![attachment],
                worktree: None,
                busy: BusyPolicy::Queue,
            })
            .await
    });
    wait_until(
        || !handle.snapshot().pending.is_empty(),
        "the echo renders before the RPC completes",
    )
    .await;
    let echo = handle.snapshot().pending[0].clone();
    assert!(
        echo.text.starts_with("look at this\n\nAttached images (local files"),
        "the echo carries the placeholder trailer: {:?}",
        echo.text
    );
    let outcome = send_task.await.unwrap().unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("an idle send with an attachment starts a turn: {outcome:?}")
    };
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == message_id && e.role == MessageRole::User)
        },
        "the sent message lands",
    )
    .await;
    wait_until(|| !requests.lock().unwrap().is_empty(), "the run executed").await;
    let request = requests.lock().unwrap()[0].clone();
    // The send uploaded its own copy (a fresh engine-minted path).
    assert_eq!(request.attachments.len(), 1);
    let sent_path = request.attachments[0].clone();
    let sent_trailer = format!(
        "\n\nAttached images (local files — open them to view):\n- {sent_path}"
    );
    assert!(
        request.prompt.ends_with(&sent_trailer),
        "the prompt ends with the exact trailer: {:?}",
        request.prompt
    );
    // The landed user entry carries the same text.
    let entry = handle
        .snapshot()
        .entries
        .iter()
        .find(|e| e.id == message_id)
        .cloned()
        .unwrap();
    match &entry.parts[0] {
        roboco_doc::MessagePart::Text { text, .. } => {
            assert!(text.ends_with(&sent_trailer));
        }
        other => panic!("unexpected part: {other:?}"),
    }
    // The visible message text strips the trailer.
    let visible = handle.message_text(&message_id).unwrap();
    assert_eq!(visible, "look at this");
    rig.client.shutdown();
}

#[derive(Default)]
struct ProgressRecorder {
    values: Mutex<Vec<f64>>,
}

impl ProgressRecorder {
    fn push(&self, fraction: f64) {
        self.values.lock().unwrap().push(fraction);
    }

    fn values(&self) -> Vec<f64> {
        self.values.lock().unwrap().clone()
    }
}

// ── the offline transcript cache ───────────────────────────────────────────

/// Open a chat, send, shut the client down, reopen with the engine
/// stopped: `hydrated` from the cache, same entries.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_transcript_cache_hydrates_a_reopened_chat() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    let handle = rig.open();
    let outcome = handle.send(SendRequest::text("cache me")).await.unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("idle send: {outcome:?}")
    };
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == message_id && e.role == MessageRole::User)
        },
        "the message lands",
    )
    .await;
    // A quiet tail writes the cache on every frame; a streaming tail is
    // throttled to one write per second — wait for the quiet, then flush
    // through `on_background` (the documented flush barrier) before the
    // teardown.
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .last()
                .is_none_or(|e| e.status != Some(MessageStatus::Streaming))
        },
        "the tail quiets",
    )
    .await;
    let entries: Vec<SessionMessageEntry> = handle
        .snapshot()
        .entries
        .iter()
        .map(|e| (**e).clone())
        .collect();
    let data_dir = rig.data_dir.path().to_owned();
    let engine_id = rig.engine_id.clone();
    let record = rig.record_of();
    // Wait for the debounced cache write to reach the disk — the reopen
    // reads the file, so this is the deterministic barrier.
    wait_until(
        || find_any_chat_file(rig.data_dir.path()).is_some(),
        "the transcript cache file lands",
    )
    .await;
    rig.client.shutdown();
    // The engine is stopped: the cache is the only source.
    rig.listener.stop();
    let recorder = Arc::new(Recorder::default());
    let reopened = test_client(&data_dir, vec![record], recorder);
    let chat_id = reopened.scope(&engine_id, "same-chat");
    let handle = reopened.open_session(&chat_id).unwrap();
    let snapshot = handle.snapshot();
    assert!(snapshot.hydrated, "hydrated from the cache");
    assert_eq!(snapshot.entries.len(), entries.len());
    assert_eq!(
        snapshot
            .entries
            .iter()
            .map(|e| e.id.clone())
            .collect::<Vec<_>>(),
        entries.iter().map(|e| e.id.clone()).collect::<Vec<_>>()
    );
    // A historyPending preview never lands in the cache (the unit test in
    // `session/transcript.rs` covers the crafted-frame case).
    reopened.shutdown();
}

impl Rig {
    fn record_of(&self) -> roboco_mobile_client::PairedEngine {
        self.client
            .engines()
            .into_iter()
            .find(|view| view.engine_id == self.engine_id)
            .map(|view| roboco_mobile_client::PairedEngine {
                engine_id: view.engine_id,
                base_url: view.base_url,
                credential: "restored-by-test".into(),
                session_id: String::new(),
                label: view.name,
                paired_at_ms: 0,
                device_id: view.device_id,
                name: None,
                transport: Default::default(),
                room: None,
            })
            .unwrap()
    }
}

// ── the warm set ───────────────────────────────────────────────────────────

/// Opening 8 sessions keeps at most 6 detached subscriptions; the
/// view-attached session is never evicted.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_warm_set_keeps_at_most_six_detached_sessions() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "700")], async {
    let rig = rig().await;
    // A streaming session is never evicted: start a delayed turn in one
    // chat and leave it detached while its tail streams.
    let streaming_id = rig.chat_id();
    let streaming = rig.client.open_session(&streaming_id).unwrap();
    streaming.send(SendRequest::text("the streaming tail")).await.unwrap();
    wait_until(
        || streaming.snapshot().streaming,
        "the tail streams",
    )
    .await;
    // 8 chats on the engine.
    for i in 0..8 {
        rig.core
            .workspace
            .create_chat(&format!("warm-{i}"), None, Some(&rig.core.device_id), None, None)
            .unwrap();
    }
    wait_until(
        || rig.client.fleet().borrow_and_update().engine(&rig.engine_id)
            .map(|engine| engine.rows.chats.len() >= 9)
            .unwrap_or(false),
        "the engine rows arrive",
    )
    .await;
    let ids: Vec<String> = (0..8)
        .map(|i| rig.client.scope(&rig.engine_id, &format!("warm-{i}")))
        .collect();
    let attached_id = ids[0].clone();
    // Attach the first session BEFORE the later opens: an attached session
    // never counts against the cap and is never evicted.
    rig.client.open_session(&attached_id).unwrap();
    rig.client
        .session(&attached_id)
        .unwrap()
        .set_view_attached(true);
    for id in &ids[1..] {
        rig.client.open_session(id).unwrap();
    }
    // Trigger an eviction pass (a fresh open, and closing a detached one).
    let extra = rig.client.scope(&rig.engine_id, "same-chat");
    rig.client.open_session(&extra).unwrap();
    for id in &ids[1..] {
        rig.client.close_session(id);
    }
    let open = rig.client.open_session_ids();
    let protected = [attached_id.clone(), streaming_id.clone()];
    let detached = open.iter().filter(|id| !protected.contains(id)).count();
    assert!(
        open.len() <= roboco_mobile_client::session::WARM_SESSION_CAP + 3,
        "at most the cap plus the view-attached, streaming and just-opened          sessions: {open:?}"
    );
    assert!(
        detached <= roboco_mobile_client::session::WARM_SESSION_CAP,
        "at most {detached} detached subscriptions (cap 6)"
    );
    assert!(
        open.contains(&attached_id),
        "the view-attached session survives: {open:?}"
    );
    assert!(
        open.contains(&streaming_id),
        "the streaming session survives: {open:?}"
    );
    rig.client.shutdown();
    })
    .await;
}

// ── catalogs ───────────────────────────────────────────────────────────────

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_live_catalog_and_its_fallbacks() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    // Live: the mock (offered — `test_config` sets `show_mock_harness`) and
    // it steers mid-turn.
    let harnesses = rig.client.list_harnesses(&rig.engine_id).await;
    let mock = harnesses
        .iter()
        .find(|h| h.id == "mock")
        .expect("the mock is offered when configured");
    assert_eq!(mock.mid_turn_steering(), Some(true));
    let models = rig.client.list_models(&rig.engine_id, "mock").await;
    assert!(
        models.iter().any(|m| m.id == "mock-1"),
        "the engine's mock models list: {models:?}"
    );
    // The cache files exist on disk (the write is off-thread).
    wait_until(
        || find_file(rig.data_dir.path(), "models-mock.json").is_some(),
        "the model catalog is cached",
    )
    .await;

    // Now hide the engine: the disk cache answers for the models, and the
    // harnesses fall back to the static list.
    rig.listener.stop();
    wait_for_view(&rig.client, &rig.engine_id, "disconnected", |view| {
        view.state != EngineState::Connected
    })
    .await;
    let cached_models = rig.client.list_models(&rig.engine_id, "mock").await;
    assert!(
        cached_models.iter().any(|m| m.id == "mock-1"),
        "the disk cache answers while offline: {cached_models:?}"
    );
    let harnesses = rig.client.list_harnesses(&rig.engine_id).await;
    // The disk cache answers first (the live mock list), then the static
    // list only where no cache exists.
    assert!(
        harnesses.iter().any(|h| h.id == "mock"),
        "the cached live list answers while offline: {harnesses:?}"
    );
    // A harness without a cache entry: mid-turn steering stays unknown
    // offline, but the catalog shape survives.
    let claude = rig.client.list_models(&rig.engine_id, "claude-code").await;
    assert!(!claude.is_empty());

    // A fresh client (no cache) with the engine never seen: the static list.
    let fresh_dir = tempfile::tempdir().unwrap();
    let recorder = Arc::new(Recorder::default());
    let fresh = test_client(fresh_dir.path(), Vec::new(), recorder);
    let unknown_device = "device-that-was-never-paired";
    let harnesses = fresh.list_harnesses(unknown_device).await;
    assert!(harnesses.iter().any(|h| h.id == "claude-code"));
    assert!(harnesses.iter().any(|h| h.id == "codex"));
    fresh.shutdown();
    rig.client.shutdown();
}

/// With `show_mock_harness`, the mock is offered and steers mid-turn.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_mock_harness_is_offered_when_configured() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = engine_core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let config = support::test_config(data_dir.path());
    let client = Client::new_with_timings(config, Vec::new(), Vec::new(), recorder, support::test_timings())
        .unwrap();
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let harnesses = client.list_harnesses(&record.engine_id).await;
    let mock = harnesses
        .iter()
        .find(|h| h.id == "mock")
        .expect("the mock is offered");
    assert_eq!(mock.mid_turn_steering(), Some(true), "the mock steers mid-turn");
    assert!(mock.offered());
    client.shutdown();
}

// ── files ──────────────────────────────────────────────────────────────────

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn file_search_and_tool_blob() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    // A project dir with a known file, and a chat rooted there.
    let project = tempfile::tempdir().unwrap();
    std::fs::write(project.path().join("needle.txt"), b"haystack").unwrap();
    std::fs::create_dir(project.path().join("sub")).unwrap();
    std::fs::write(project.path().join("sub").join("other.rs"), b"fn main(){}").unwrap();
    let core = engine_core(engine_dir.path());
    core.workspace
        .create_chat(
            "project-chat",
            None,
            Some(&core.device_id),
            None,
            Some(project.path().to_string_lossy().to_string()),
        )
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let chat_id = client.scope(&record.engine_id, "project-chat");
    let matches = client
        .search_files(&record.engine_id, Some(chat_id.clone()), None, "needle")
        .await
        .unwrap();
    assert!(
        matches.iter().any(|m| m.path.ends_with("needle.txt")),
        "the known file is found: {matches:?}"
    );
    let matches = client
        .search_files(&record.engine_id, Some(chat_id.clone()), None, "other")
        .await
        .unwrap();
    assert!(matches.iter().any(|m| m.path.contains("sub") && m.path.ends_with("other.rs")));
    // An over-long query is refused by the engine.
    let long_query = "x".repeat(300);
    let error = client
        .search_files(&record.engine_id, Some(chat_id), None, &long_query)
        .await
        .unwrap_err();
    assert!(matches!(error, ClientError::HostError { .. }), "got: {error:?}");
    // An unknown blob ref maps to the engine's sentence.
    let handle = client.open_session(&client.scope(&record.engine_id, "project-chat")).unwrap();
    let error = handle.fetch_tool_blob("no/such/ref").await.unwrap_err();
    assert!(
        matches!(error, ClientError::HostError { ref message } if message == "tool output is not stored on this engine"),
        "got: {error:?}"
    );
    client.shutdown();
}

// ── chat config ────────────────────────────────────────────────────────────

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn set_session_config_applies_optimistically_and_reverts() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    let handle = rig.open();
    let chat_id = rig.chat_id();
    // The engine-side chat has no config (support's create_chat) → the
    // fallback is claude-code's defaults.
    let config = ChatConfig {
        harness: roboco_proto::HarnessId::Codex,
        model: Some("gpt-5.5".into()),
        reasoning: Some(roboco_proto::ReasoningLevel::High),
        model_options: Default::default(),
        sandbox: SandboxLevel::ReadOnly,
    };
    rig.client.set_session_config(&chat_id, config.clone()).await.unwrap();
    // The optimistic override reads back at once (07's store, wire-string
    // form — compare through the same mapping the FFI uses).
    let wanted = roboco_mobile_client::SessionConfig::of_chat(&config);
    let read = rig.client.session_config(&chat_id).unwrap();
    assert_eq!(read, wanted);
    // The engine's WatchChats echo clears the override: session_config
    // still reads the same value, now from the row.
    wait_until(
        || {
            rig.core
                .workspace
                .chat("same-chat")
                .map(|chat| chat.map(|chat| chat.config == Some(config.clone())))
                .unwrap_or(None)
                .unwrap_or(false)
        },
        "the engine row carries the config",
    )
    .await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    let read = rig.client.session_config(&chat_id).unwrap();
    assert_eq!(read, wanted, "the row answers once the echo lands");

    // A failed write (engine stopped) reverts and returns the error.
    rig.listener.stop();
    wait_for_view(&rig.client, &rig.engine_id, "disconnected", |view| {
        view.state != EngineState::Connected
    })
    .await;
    let next = ChatConfig {
        harness: roboco_proto::HarnessId::Mock,
        model: None,
        reasoning: None,
        model_options: Default::default(),
        sandbox: SandboxLevel::DangerFullAccess,
    };
    let error = rig.client
        .set_session_config(&chat_id, next.clone())
        .await
        .unwrap_err();
    assert!(matches!(error, ClientError::HostUnavailable { .. }));
    let read = rig.client.session_config(&chat_id).unwrap();
    assert_ne!(
        read,
        roboco_mobile_client::SessionConfig::of_chat(&next),
        "the failed write reverted"
    );
    rig.client.shutdown();
}

// ── reconnect ──────────────────────────────────────────────────────────────

/// Stop and re-serve the listener mid-session: the subscription
/// resubscribes, `history_pending` never sticks, and the content equals the
/// engine's.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "slow: waits out the reconnect backoff"]
async fn the_session_recovers_when_the_engine_restarts() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    let handle = rig.open();
    let outcome = handle.send(SendRequest::text("before restart")).await.unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("idle send: {outcome:?}")
    };
    wait_until(
        || handle.snapshot().entries.iter().any(|e| e.id == message_id),
        "the message lands",
    )
    .await;
    // Restart the engine's listener on the same port.
    rig.listener.stop();
    wait_for_view(&rig.client, &rig.engine_id, "disconnected", |view| {
        view.state != EngineState::Connected
    })
    .await;
    rig.restart_listener().await;
    wait_for_view(&rig.client, &rig.engine_id, "reconnected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // The session resubscribes and stays consistent.
    let outcome = handle.send(SendRequest::text("after restart")).await.unwrap();
    let SendOutcome::Started { message_id: after } = outcome else {
        panic!("post-restart send: {outcome:?}")
    };
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == after && e.role == MessageRole::User)
        },
        "the post-restart message lands",
    )
    .await;
    let snapshot = handle.snapshot();
    assert!(!snapshot.history_pending, "history_pending never sticks");
    // The content equals the engine's.
    let engine_entries = rig
        .core
        .doc_host
        .open("same-chat")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert_eq!(snapshot.entries.len(), engine_entries.len());
    assert_eq!(
        snapshot.entries.iter().map(|e| e.id.clone()).collect::<Vec<_>>(),
        engine_entries.iter().map(|e| e.id.clone()).collect::<Vec<_>>()
    );
    rig.client.shutdown();
}

/// The frozen-chat regression: an outage longer than 30 s used to end the
/// transcript task for good (the session stayed registered and uncancelled,
/// so a reopen handed back the dead core). The subscription now waits out
/// any outage and picks up what the engine wrote meanwhile and after.
///
/// The outage stands in for the phone's freeze/thaw cycle: the app is
/// backgrounded mid-run, the process freezes, the engine drops the silent
/// sockets after its 45 s lease, and the user taps the notification. The
/// time from the reconnect to the new entry on screen is bounded.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_transcript_survives_a_freeze_past_the_engine_lease() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let mut rig = rig().await;
    let handle = rig.open();
    wait_until(|| handle.snapshot().hydrated, "the first reset lands").await;
    rig.listener.stop();
    wait_for_view(&rig.client, &rig.engine_id, "disconnected", |view| {
        view.state != EngineState::Connected
    })
    .await;
    // Past the old 30 s give-up and the engine's 45 s socket lease.
    tokio::time::sleep(Duration::from_secs(46)).await;
    rig.core
        .doc_host
        .open("same-chat")
        .unwrap()
        .write_user_message("during-outage", "written while offline", 1, None)
        .unwrap();
    let thawed = Instant::now();
    rig.restart_listener().await;
    wait_for_view(&rig.client, &rig.engine_id, "reconnected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    rig.core
        .doc_host
        .open("same-chat")
        .unwrap()
        .write_user_message("after-outage", "written after the reconnect", 2, None)
        .unwrap();
    wait_until(
        || {
            let snapshot = handle.snapshot();
            ["during-outage", "after-outage"]
                .iter()
                .all(|id| snapshot.entries.iter().any(|e| e.id == *id))
        },
        "both entries reach the open session",
    )
    .await;
    let caught_up = thawed.elapsed();
    eprintln!("thaw to new entry on screen: {caught_up:?}");
    // The resubscribe follows the reconnect directly: no backoff that grew
    // during the outage (up to 30 s) is waited out first.
    assert!(
        caught_up < Duration::from_secs(10),
        "the chat took {caught_up:?} to catch up after the thaw"
    );
    // A reopen returns the same, still-live session.
    let reopened = rig.open();
    assert!(
        reopened.snapshot().entries.iter().any(|e| e.id == "after-outage"),
        "the reopened session shows the live transcript"
    );
    rig.client.shutdown();
}

/// `SendState::Sending` while the send is in flight, and `turn_running`
/// only once the host reports Working or the tail streams — never from the
/// pending send itself (#777). The upload window makes the in-flight state
/// deterministic: the command is not issued until the bytes are committed,
/// so nothing but a host status could report a turn.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn sending_is_not_working() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let (rig, _requests) = recording_rig().await;
    let handle = rig.open();
    let bytes: Vec<u8> = (0..800_000u32).map(|i| (i % 251) as u8).collect();
    let attachment = OutgoingAttachment {
        name: "slow.png".into(),
        mime_type: "image/png".into(),
        data: bytes,
    };
    let send_task = {
        let handle = handle.clone();
        tokio::spawn(async move {
            handle
                .send(SendRequest {
                    text: "slow".into(),
                    attachments: vec![attachment],
                    worktree: None,
                    busy: BusyPolicy::Queue,
                })
                .await
        })
    };
    // While the upload is in flight: the echo reads Sending, and no turn is
    // running (the command has not been issued yet, and `turn_running` is
    // derived only from host status or a streaming tail — never the echo).
    wait_until(
        || handle.composer().send_state == Some(SendState::Sending),
        "the send reads Sending",
    )
    .await;
    let composer = handle.composer();
    assert!(
        !composer.live.turn_running,
        "a pending send never reports a running turn (#777)"
    );
    assert_eq!(
        composer.live.indicator,
        roboco_proto::ChatIndicator::Idle,
        "the row indicator stays Idle while the send is merely in flight"
    );
    send_task.await.unwrap().unwrap();
    // Once the run starts, the host status (or the streaming tail) reports
    // the turn — and after adoption the send state is gone.
    wait_until(
        || {
            handle.snapshot().entries.iter().any(|e| {
                e.role == MessageRole::Assistant
                    && e.status == Some(MessageStatus::Complete)
            })
        },
        "the reply completes",
    )
    .await;
    assert_eq!(handle.composer().send_state, None);
    rig.client.shutdown();
}

/// Find any `chat-*.json` transcript cache file under `root`.
fn find_any_chat_file(root: &std::path::Path) -> Option<std::path::PathBuf> {
    fn walk(dir: &std::path::Path, depth: usize) -> Option<std::path::PathBuf> {
        if depth > 6 {
            return None;
        }
        for entry in std::fs::read_dir(dir).ok()?.flatten() {
            let path = entry.path();
            if path.is_file()
                && path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with("chat-") && n.ends_with(".json"))
            {
                return Some(path);
            }
            if path.is_dir()
                && let Some(found) = walk(&path, depth + 1)
            {
                return Some(found);
            }
        }
        None
    }
    walk(root, 0)
}

/// Find a file by name anywhere under `root` (bounded depth).
fn find_file(root: &std::path::Path, name: &str) -> Option<std::path::PathBuf> {
    fn walk(dir: &std::path::Path, name: &str, depth: usize) -> Option<std::path::PathBuf> {
        if depth > 6 {
            return None;
        }
        let entries = std::fs::read_dir(dir).ok()?;
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() && path.file_name().and_then(|n| n.to_str()) == Some(name) {
                return Some(path);
            }
            if path.is_dir() {
                if let Some(found) = walk(&path, name, depth + 1) {
                    return Some(found);
                }
            }
        }
        None
    }
    walk(root, name, 0)
}

/// Stopping lease renewal for >60 s turns the gate into `ReviewRequired`
/// (the lease is 60 s engine-side, with no short-lease test knob — hence
/// `#[ignore]`: a slow test for the batch run).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "slow: waits out the 60 s engine lease"]
async fn an_expired_edit_lease_gates_the_row_for_review() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "700")], async {
    // A busy turn keeps the queue from draining while the lease is taken
    // (once gated, the row survives even after the turn ends).
    let rig = rig().await;
    let handle = rig.open();
    handle.send(SendRequest::text("a turn to keep the queue parked")).await.unwrap();
    wait_until(|| handle.composer().live.turn_running, "the turn starts").await;
    let row = handle.enqueue("edit me", Vec::new(), false).await.unwrap();
    wait_until(
        || handle.composer().queue.iter().any(|r| r.id == row),
        "the row arrives",
    )
    .await;
    let lease = match handle.begin_queued_edit(&row, "expiry-instance").await {
        roboco_mobile_client::QueueEditStart::Acquired(lease) => lease,
        other => panic!("the row acquires: {other:?}"),
    };
    // No renewal: the engine's 60 s lease expires and the gate flips to
    // ReviewRequired (never silently sendable again).
    tokio::time::sleep(Duration::from_secs(65)).await;
    wait_until(
        || {
            handle
                .composer()
                .queue
                .iter()
                .any(|r| r.id == row && matches!(r.gate, Some(roboco_mobile_client::QueueGate::ReviewRequired { .. })))
        },
        "the lapsed lease gates the row for review",
    )
    .await;
    // Renewal on the dead lease returns false.
    assert!(!handle.renew_queued_edit(&lease).await);
    rig.client.shutdown();
    })
    .await;
}

/// A frozen transcript connection (06's stall proxy) is closed by the lag
/// check; the session recovers on a NEW connection within 30 s of the
/// freeze and the content catches up.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_frozen_transcript_connection_is_replaced() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    // Pair through the stall proxy so every connection can be frozen.
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = engine_core(engine_dir.path());
    let direct = serve(&core, engine_dir.path()).await;
    let proxy = support::stall_proxy::StallProxy::start(direct.address).await;
    let host = proxy.address.to_string();
    let url = format!("http://{host}/pair#token={}", {
        let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
        store.create_code("test", 300).unwrap().credential
    });
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let chat_id = client.scope(&record.engine_id, "same-chat");
    let handle = client.open_session(&chat_id).unwrap();
    let outcome = handle.send(SendRequest::text("before the freeze")).await.unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("idle send: {outcome:?}")
    };
    wait_until(
        || handle.snapshot().entries.iter().any(|e| e.id == message_id),
        "the message lands",
    )
    .await;
    // Freeze everything: the lag check closes the frozen transcript
    // connection, the feed supervisor notices, and nothing flows.
    proxy.pause();
    let froze_at = Instant::now();
    tokio::time::sleep(Duration::from_secs(2)).await;
    proxy.resume();
    // The session resubscribes on a fresh connection; a message sent after
    // the freeze still lands within 30 s of it.
    let outcome = handle.send(SendRequest::text("after the freeze")).await.unwrap();
    let SendOutcome::Started { message_id: after } = outcome else {
        panic!("post-freeze send: {outcome:?}")
    };
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == after && e.role == MessageRole::User)
        },
        "the post-freeze message lands",
    )
    .await;
    assert!(
        Instant::now().duration_since(froze_at) < Duration::from_secs(30),
        "the session recovered on a new connection within 30 s of the freeze"
    );
    assert!(!handle.snapshot().history_pending);
    client.shutdown();
}

/// The thaw: the phone's process was frozen, so its timers never fired,
/// and every socket it holds is dead without a close (the engine dropped
/// them, the FINs were lost). The client still believes it is Connected.
/// Coming back to the foreground alone must catch the open chat up within
/// seconds: an immediate wake probe fails the dead link, the transcript
/// connection closes with it, and the chat resubscribes on the new link.
/// Before, the foreground only reset the probe timer and the chat sat on a
/// dead socket until the next probe and lag check.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_thawed_app_on_dead_sockets_catches_up_within_seconds() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = engine_core(engine_dir.path());
    let direct = serve(&core, engine_dir.path()).await;
    let proxy = support::stall_proxy::StallProxy::start(direct.address).await;
    let url = format!("http://{}/pair#token={}", proxy.address, {
        let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
        store.create_code("test", 300).unwrap().credential
    });
    // The periodic checks are far apart, as for a process whose timers sat
    // frozen: only the foreground event can notice the dead link in time.
    let timings = roboco_mobile_client::timings::Timings {
        probe_every: Duration::from_secs(60),
        lag_every: Duration::from_secs(60),
        lag_timeout: Duration::from_secs(60),
        ..support::test_timings()
    };
    let client = Client::new_with_timings(
        support::test_config(data_dir.path()),
        Vec::new(),
        Vec::new(),
        Arc::new(Recorder::default()),
        timings,
    )
    .unwrap();
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let handle = client
        .open_session(&client.scope(&record.engine_id, "same-chat"))
        .unwrap();
    wait_until(|| handle.snapshot().hydrated, "the first reset lands").await;
    // Freeze: every open socket dies silently; the engine keeps writing.
    proxy.sever();
    core.doc_host
        .open("same-chat")
        .unwrap()
        .write_user_message("while-frozen", "written while the phone slept", 1, None)
        .unwrap();
    assert_eq!(
        client.engine(&record.engine_id).unwrap().state,
        EngineState::Connected,
        "the client still believes it is connected"
    );
    // Thaw.
    let thawed = Instant::now();
    client.on_foreground();
    wait_until(
        || handle.snapshot().entries.iter().any(|e| e.id == "while-frozen"),
        "the entry written during the freeze reaches the open chat",
    )
    .await;
    let caught_up = thawed.elapsed();
    eprintln!("thaw to new entry on screen: {caught_up:?}");
    assert!(
        caught_up < Duration::from_secs(10),
        "the chat took {caught_up:?} to catch up after the thaw"
    );
    client.shutdown();
    drop(proxy);
    core.shutdown().await;
}

/// The slow streaming test (`--ignored`): `ROBOCO_MOCK_CHARS` streams
/// N-char deltas with pacing, and the snapshot's tail entry grows in place
/// — the Arc-stable apply keeps the entry id stable across appends.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "slow: streams a paced long reply"]
async fn a_streaming_reply_grows_in_place() {
    with_mock_env(
        &[("ROBOCO_MOCK_DELAY_MS", "500"), ("ROBOCO_MOCK_CHARS", "40")],
        async {
            let rig = rig().await;
            let handle = rig.open();
            handle.send(SendRequest::text("stream me")).await.unwrap();
            // Wait for the streaming tail, then watch it grow.
            wait_until(
                || handle.snapshot().streaming,
                "the tail streams",
            )
            .await;
            let mut seen_growth = 0;
            let mut last_len = 0usize;
            let deadline = Instant::now() + Duration::from_secs(120);
            while Instant::now() < deadline && seen_growth < 3 {
                let snapshot = handle.snapshot();
                if let Some(last) = snapshot.entries.last() {
                    let len: usize = last
                        .parts
                        .iter()
                        .map(|part| match part {
                            roboco_doc::MessagePart::Text { text, .. } => text.len(),
                            _ => 0,
                        })
                        .sum();
                    if len > last_len {
                        last_len = len;
                        seen_growth += 1;
                    }
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
            assert!(seen_growth >= 3, "the streaming tail never grew");
            // The entry ids stay stable across the appends.
            let ids: Vec<String> = handle
                .snapshot()
                .entries
                .iter()
                .map(|e| e.id.clone())
                .collect();
            assert!(
                ids.windows(2).all(|w| w[0] != w[1]),
                "each entry id is unique (no rebuild churn)"
            );
            rig.client.shutdown();
        },
    )
    .await;
}

/// "Not delivered — tap to retry" on a fully adopted session: a no-op that
/// returns `Ok`, leaves the composer alone, and never re-issues anything.
///
/// The acked-but-unadopted path (the grace clock running out while the
/// engine holds the command) has no mock-free deterministic transport
/// seam: the stall proxy is transport-level and freezes the calls
/// connection the ack itself needs, and every mock turn eventually adopts
/// its echo. The state machine it drives is unit-tested in
/// `session/pending.rs` (the `Failed` boundary and the timer arithmetic in
/// `next_grace_deadline`); the engine's `RetryDelivery` re-issue (same
/// message id, new command id) is covered by the engine's own tests.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn retry_delivery_on_an_adopted_session_is_a_noop() {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let rig = rig().await;
    let handle = rig.open();
    let outcome = handle.send(SendRequest::text("already adopted")).await.unwrap();
    let SendOutcome::Started { message_id } = outcome else {
        panic!("idle send: {outcome:?}")
    };
    wait_until(
        || {
            handle
                .snapshot()
                .entries
                .iter()
                .any(|e| e.id == message_id && e.role == MessageRole::User)
        },
        "the message lands",
    )
    .await;
    wait_until(|| handle.snapshot().pending.is_empty(), "the echo is adopted").await;
    handle.retry_delivery().await.unwrap();
    assert!(
        handle.snapshot().pending.is_empty(),
        "a retry on an adopted session mints no new echo"
    );
    assert_eq!(
        handle.composer().send_state,
        None,
        "no send state after a fully adopted exchange"
    );
    rig.client.shutdown();
}
