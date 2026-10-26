//! One-way harness switch between turns (t3-orchestration-v2 ticket 24).
//!
//! Engine-side machinery under test:
//! - the `SwitchHarness` RPC writes the "Switched to <harness>" seam as a
//!   system entry, clears the chat's harness session (the empty-string
//!   tombstone — which also stops the journal scan from resurrecting the old
//!   harness's session ids), retires any stale native-fork intent, and saves
//!   the new harness and model;
//! - it is refused while a turn runs, on a switch onto the harness the chat
//!   already runs, for an unknown chat, and for a harness that is not
//!   registered;
//! - the next send dispatches on the NEW harness with no resume, carrying
//!   the budgeted transcript (ticket 01) of everything above the seam — for
//!   a queued message too (the queue rides the chat row's config) — exactly
//!   once per provider session;
//! - switching back is just another one-way switch: a second seam, another
//!   fresh session, the whole conversation (old-harness turns included) in
//!   the budgeted history;
//! - the cleared session stays cleared across an engine restart: the
//!   journal's old-harness session ids never ride a resume on the new
//!   harness.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, ChatConfig, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest,
    SandboxLevel, SteeringMode,
};
use roboco_rpc::methods;

const CHAT: &str = "chat-switch";

/// A scripted harness that answers at once and records every request it ran.
/// One instance per harness id, so a switch shows up as the requests moving
/// from one capture to the other.
struct Capture {
    id: HarnessId,
    name: &'static str,
    /// The provider session id every Done reports (distinct per harness, so
    /// assertions can tell which session the engine resumed).
    session: &'static str,
    reply: &'static str,
    requests: Arc<Mutex<Vec<RunRequest>>>,
}

impl Capture {
    fn build(
        id: HarnessId,
        name: &'static str,
        session: &'static str,
        reply: &'static str,
    ) -> (Arc<Self>, Arc<Mutex<Vec<RunRequest>>>) {
        let requests = Arc::new(Mutex::new(Vec::new()));
        (
            Arc::new(Self {
                id,
                name,
                session,
                reply,
                requests: requests.clone(),
            }),
            requests,
        )
    }
}

#[async_trait]
impl Harness for Capture {
    fn id(&self) -> HarnessId {
        self.id
    }
    fn display_name(&self) -> &str {
        self.name
    }
    fn supports_steering(&self) -> bool {
        false
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::TurnBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[]
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        Ok(vec![])
    }
    async fn run(
        &self,
        request: RunRequest,
        _: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.requests.lock().unwrap().push(request.clone());
        let reply = self.reply.to_string();
        let session = self.session.to_string();
        Ok(futures::stream::iter(vec![
            Ok(AgentEvent::TextDelta { text: reply }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some(session),
            }),
        ])
        .boxed())
    }
}

/// A harness whose turn runs until the test says so — "the chat is working"
/// as a state the test controls rather than races.
struct Held {
    finish: tokio::sync::broadcast::Sender<()>,
}

impl Held {
    fn new() -> (Arc<Self>, tokio::sync::broadcast::Sender<()>) {
        let (finish, _) = tokio::sync::broadcast::channel(16);
        (Arc::new(Self { finish: finish.clone() }), finish)
    }
}

#[async_trait]
impl Harness for Held {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Held"
    }
    fn supports_steering(&self) -> bool {
        false
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::TurnBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[]
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        Ok(vec![])
    }
    async fn run(
        &self,
        _: RunRequest,
        _: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let mut finish = self.finish.subscribe();
        Ok(futures::stream::once(async move {
            let _ = finish.recv().await;
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some("held-session".into()),
            })
        })
        .boxed())
    }
}

async fn wait_for<F>(mut predicate: F, what: &str)
where
    F: FnMut() -> bool,
{
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while !predicate() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(15)).await;
    }
}

fn create_chat(core: &EngineCore, config: ChatConfig) {
    core.workspace
        .create_chat(CHAT, None, Some(&core.device_id), Some(config), Some("/tmp".into()))
        .expect("create chat");
    // Pre-title so the auto-titler never dispatches a harness request of its own.
    core.workspace.rename_chat(CHAT, "Pre-titled").expect("rename chat");
}

fn entries(core: &EngineCore) -> Vec<SessionMessageEntry> {
    core.doc_host
        .open(CHAT)
        .expect("open chat")
        .doc()
        .read_entries()
        .expect("read entries")
}

/// The chat row as the workspace doc sees it.
fn chat_row(core: &EngineCore) -> roboco_proto::Chat {
    core.workspace.chat(CHAT).expect("chat row").expect("chat exists")
}

fn mock_config() -> ChatConfig {
    ChatConfig {
        harness: HarnessId::Mock,
        model: Some("mock-model".into()),
        reasoning: None,
        model_options: Default::default(),
        sandbox: SandboxLevel::WorkspaceWrite,
    }
}

/// Queue one message on the (idle) chat — the composer's send path; the
/// drain dispatches it on the chat row's harness.
fn send(core: &EngineCore, text: &str) {
    core.doc_host.queue_message(CHAT, text, Vec::new()).expect("queue message");
}

async fn switch(
    core: &EngineCore,
    harness: HarnessId,
    model: Option<&str>,
) -> Result<serde_json::Value, roboco_rpc::RpcError> {
    let client = roboco_rpc::memory_client(core.rpc_service());
    let mut params = serde_json::json!({ "chatId": CHAT, "harness": harness });
    if let Some(model) = model {
        params["model"] = serde_json::Value::String(model.into());
    }
    client.call(methods::SWITCH_HARNESS, params).await
}

/// The transcript's harness-switch seams naming `harness`: `count` of them,
/// each a complete system row.
fn expect_seams(core: &EngineCore, harness: HarnessId, count: usize) {
    let seams: Vec<SessionMessageEntry> = entries(core)
        .into_iter()
        .filter(|entry| {
            entry.role == MessageRole::System
                && entry.parts.iter().any(|part| {
                    matches!(
                        part,
                        MessagePart::HarnessSwitch { harness: to, .. } if *to == harness
                    )
                })
        })
        .collect();
    assert_eq!(seams.len(), count, "seams for {harness:?}");
    for seam in &seams {
        assert_eq!(seam.status, Some(MessageStatus::Complete));
    }
}

/// One mock exchange leaves the transcript and the row session behind.
async fn run_mock_turn(
    core: &EngineCore,
    mock_requests: &Arc<Mutex<Vec<RunRequest>>>,
    prompt: &str,
) {
    send(core, prompt);
    wait_for(
        || mock_requests.lock().unwrap().iter().any(|r| r.prompt.contains(prompt)),
        "the mock turn to run",
    )
    .await;
    wait_for(
        || chat_row(core).harness_session_id.as_deref() == Some("mock-session"),
        "the mock session to be recorded",
    )
    .await;
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_none_or(|s| s.status != roboco_proto::SessionStatus::Working)
        },
        "the mock turn to settle",
    )
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn switch_writes_the_seam_clears_the_session_and_saves_the_harness() {
    let tmp = tempfile::tempdir().unwrap();
    let (mock, mock_requests) =
        Capture::build(HarnessId::Mock, "Mock", "mock-session", "I remember PINEAPPLE");
    let (pi, _) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(mock);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());

    run_mock_turn(&core, &mock_requests, "Remember PINEAPPLE").await;
    let before = entries(&core).len();
    assert_eq!(chat_row(&core).harness_session_id.as_deref(), Some("mock-session"));

    switch(&core, HarnessId::Pi, Some("pi-default")).await.expect("switch");

    // The seam: a system entry after the transcript, complete, naming the
    // new harness.
    expect_seams(&core, HarnessId::Pi, 1);
    assert_eq!(entries(&core).len(), before + 1);
    // The row: new harness and model, and no session left to resume (how
    // the clear is spelled is the engine's business; that the old session
    // never rides a resume — not even after a restart's journal scan — is
    // asserted by the post-switch send tests below).
    let row = chat_row(&core);
    assert_eq!(row.config.as_ref().map(|c| c.harness), Some(HarnessId::Pi));
    assert_eq!(row.config.as_ref().and_then(|c| c.model.as_deref()), Some("pi-default"));
    assert!(row.harness_session_id.as_deref().is_none_or(str::is_empty));
    assert!(row.harness_session_cwd.as_deref().is_none_or(|cwd| cwd.is_empty()));

    // Refusals: the same harness, an unregistered one, an unknown chat.
    let again = switch(&core, HarnessId::Pi, None).await.unwrap_err();
    assert!(again.to_string().contains("already on"), "{again}");
    let unregistered = switch(&core, HarnessId::Codex, None).await.unwrap_err();
    assert!(unregistered.to_string().contains("not found"), "{unregistered}");
    let client = roboco_rpc::memory_client(core.rpc_service());
    let no_chat = client
        .call(
            methods::SWITCH_HARNESS,
            serde_json::json!({ "chatId": "nope", "harness": "pi" }),
        )
        .await
        .unwrap_err();
    assert!(no_chat.to_string().contains("no longer exists"), "{no_chat}");
    // The refusals wrote nothing: one seam, the pi row.
    expect_seams(&core, HarnessId::Pi, 1);
    assert_eq!(chat_row(&core).config.as_ref().map(|c| c.harness), Some(HarnessId::Pi));

    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_switch_is_refused_while_the_chat_works_and_allowed_once_idle() {
    let tmp = tempfile::tempdir().unwrap();
    let (held, finish) = Held::new();
    let (pi, _) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(held);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());

    send(&core, "hold this turn");
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == roboco_proto::SessionStatus::Working)
        },
        "the turn to start",
    )
    .await;

    let refused = switch(&core, HarnessId::Pi, None).await.unwrap_err();
    assert!(refused.to_string().contains("between turns"), "{refused}");
    // Nothing was written: no seam, no row change.
    assert!(entries(&core).iter().all(|entry| entry.role != MessageRole::System));
    assert_eq!(chat_row(&core).config.as_ref().map(|c| c.harness), Some(HarnessId::Mock));

    // The turn ends → the chat is idle → the same switch goes through.
    let _ = finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_none_or(|s| s.status != roboco_proto::SessionStatus::Working)
        },
        "the turn to settle",
    )
    .await;
    switch(&core, HarnessId::Pi, None).await.expect("switch once idle");
    expect_seams(&core, HarnessId::Pi, 1);

    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_next_send_carries_the_budgeted_history_exactly_once_on_the_new_harness() {
    let tmp = tempfile::tempdir().unwrap();
    let (mock, mock_requests) =
        Capture::build(HarnessId::Mock, "Mock", "mock-session", "I remember PINEAPPLE");
    let (pi, pi_requests) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(mock);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());

    run_mock_turn(&core, &mock_requests, "Remember PINEAPPLE").await;
    switch(&core, HarnessId::Pi, Some("pi-default")).await.expect("switch");

    // A queued message after the switch: the chat row routes it to the new
    // harness, the old session never rides a resume, and the budgeted
    // transcript of everything above the seam rides in front of it.
    send(&core, "What did I ask you to remember?");
    wait_for(
        || {
            pi_requests
                .lock()
                .unwrap()
                .iter()
                .any(|r| r.prompt.contains("What did I ask you to remember?"))
        },
        "the post-switch send to reach pi",
    )
    .await;
    let request = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.harness, Some(HarnessId::Pi));
    assert_eq!(request.resume, None, "the old harness's session must not resume");
    assert!(request.prompt.contains("Remember PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.contains("I remember PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.contains("<transcript>"), "{}", request.prompt);
    // The seam itself is structure, never agent-visible text.
    assert!(!request.prompt.contains("switch:"), "{}", request.prompt);
    wait_for(
        || chat_row(&core).harness_session_id.as_deref() == Some("pi-session"),
        "the new harness's session to be recorded",
    )
    .await;

    // The send after that continues the pi session as-is: the history went
    // out exactly once, to the session that now holds it.
    send(&core, "and now?");
    wait_for(
        || pi_requests.lock().unwrap().len() >= 2,
        "the second post-switch send to reach pi",
    )
    .await;
    let request = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.resume.as_deref(), Some("pi-session"));
    assert!(
        !request.prompt.contains("PINEAPPLE"),
        "the history must not ride twice: {}",
        request.prompt
    );
    assert!(request.prompt.contains("and now?"), "{}", request.prompt);

    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_native_command_first_turn_defers_the_history_to_the_next_ordinary_send() {
    let tmp = tempfile::tempdir().unwrap();
    let (mock, mock_requests) =
        Capture::build(HarnessId::Mock, "Mock", "mock-session", "I remember PINEAPPLE");
    let (pi, pi_requests) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(mock);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());

    run_mock_turn(&core, &mock_requests, "Remember PINEAPPLE").await;
    switch(&core, HarnessId::Pi, None).await.expect("switch");

    // The first post-switch send is a native command: it goes out bare (no
    // wrapper in front of a leading `/name`), so the history it owes the
    // fresh session has to ride the first ORDINARY send instead.
    send(&core, "/review");
    wait_for(
        || pi_requests.lock().unwrap().iter().any(|r| r.prompt.contains("/review")),
        "the native command to reach pi",
    )
    .await;
    let command = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(command.prompt, "/review");
    assert!(!command.prompt.contains("PINEAPPLE"), "{}", command.prompt);
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_none_or(|s| s.status != roboco_proto::SessionStatus::Working)
        },
        "the command turn to settle",
    )
    .await;

    send(&core, "What did I ask you to remember?");
    wait_for(
        || pi_requests.lock().unwrap().len() >= 2,
        "the first ordinary send to reach pi",
    )
    .await;
    let request = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.resume.as_deref(), Some("pi-session"));
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.contains("What did I ask you to remember?"), "{}", request.prompt);
    // The command's own turn is below the seam: it already rode its run,
    // and is never pasted as conversation text.
    assert!(!request.prompt.contains("/review"), "{}", request.prompt);
    assert!(!request.prompt.contains("switch:"), "{}", request.prompt);

    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn switching_back_resumes_the_providers_own_session_with_what_it_missed() {
    let tmp = tempfile::tempdir().unwrap();
    let (mock, mock_requests) =
        Capture::build(HarnessId::Mock, "Mock", "mock-session", "I remember PINEAPPLE");
    let (pi, pi_requests) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "the pi exchange");
    let registry = HarnessRegistry::new();
    registry.register(mock);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());

    run_mock_turn(&core, &mock_requests, "Remember PINEAPPLE").await;
    switch(&core, HarnessId::Pi, None).await.expect("switch to pi");
    send(&core, "continue on pi");
    wait_for(
        || pi_requests.lock().unwrap().iter().any(|r| r.prompt.contains("continue on pi")),
        "the pi turn to run",
    )
    .await;
    wait_for(
        || chat_row(&core).harness_session_id.as_deref() == Some("pi-session"),
        "the pi session to be recorded",
    )
    .await;
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_none_or(|s| s.status != roboco_proto::SessionStatus::Working)
        },
        "the pi turn to settle",
    )
    .await;

    // Switching back: a second seam, the pi session dropped the same way.
    switch(&core, HarnessId::Mock, Some("mock-model")).await.expect("switch back");
    expect_seams(&core, HarnessId::Pi, 1);
    expect_seams(&core, HarnessId::Mock, 1);
    assert_eq!(chat_row(&core).harness_session_id.as_deref(), Some(""));
    let mock_turns = mock_requests.lock().unwrap().len();
    assert_eq!(mock_turns, 1, "the mock harness ran exactly its one turn");

    send(&core, "back on mock");
    wait_for(
        || mock_requests.lock().unwrap().len() >= 2,
        "the post-switch send to reach the mock harness",
    )
    .await;
    let request = mock_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.harness, Some(HarnessId::Mock));
    // Upstream #966's switch back: the mock's own recorded session resumes,
    // and it is handed only what it missed while pi ran (under the
    // "while you were away" lead) — its own first exchange is never
    // replayed, and the seams are never pasted as conversation text.
    assert_eq!(
        request.resume.as_deref(),
        Some("mock-session"),
        "the provider's own session resumes on the switch back"
    );
    assert!(request.prompt.contains("the pi exchange"), "{}", request.prompt);
    assert!(request.prompt.contains("back on mock"), "{}", request.prompt);
    assert!(!request.prompt.contains("Remember PINEAPPLE"), "{}", request.prompt);
    assert!(!request.prompt.contains("switch:"), "{}", request.prompt);

    core.shutdown().await
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_cleared_session_stays_cleared_across_an_engine_restart() {
    let tmp = tempfile::tempdir().unwrap();
    let (mock, mock_requests) =
        Capture::build(HarnessId::Mock, "Mock", "mock-session", "I remember PINEAPPLE");
    let (pi, _) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(mock);
    registry.register(pi);
    let core = EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    create_chat(&core, mock_config());
    run_mock_turn(&core, &mock_requests, "Remember PINEAPPLE").await;
    switch(&core, HarnessId::Pi, None).await.expect("switch");

    // Restart: the journal still holds the mock session ids; the tombstone
    // on the row must keep them from riding a resume on the new harness.
    core.shutdown().await;
    drop(core);
    let (pi, pi_requests) = Capture::build(HarnessId::Pi, "Pi", "pi-session", "pi answer");
    let registry = HarnessRegistry::new();
    registry.register(pi);
    let restarted =
        EngineCore::assemble(tmp.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    assert_eq!(chat_row(&restarted).harness_session_id.as_deref(), Some(""));

    send(&restarted, "What did I ask you to remember?");
    wait_for(
        || {
            pi_requests
                .lock()
                .unwrap()
                .iter()
                .any(|r| r.prompt.contains("What did I ask you to remember?"))
        },
        "the post-restart send to reach pi",
    )
    .await;
    let request = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.harness, Some(HarnessId::Pi));
    assert_eq!(
        request.resume, None,
        "the journal must not resurrect the old harness's session"
    );
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);

    restarted.shutdown().await;
}
