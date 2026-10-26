//! A Limited chat continues on another harness (t3-orchestration-v2 ticket
//! 25): the composition of ticket 06's Limited record and ticket 24's
//! one-way harness switch.
//!
//! "Continue on another harness" performs the switch first — the seam, the
//! dropped session, the saved config — and then sends the continue prompt as
//! a normal run (ticket 06's Resume path, the request carrying the new
//! harness the way the composer's optimistic row stamp routes it), so:
//! - the continuation dispatches on the NEW harness with the budgeted
//!   transcript of everything above the seam, never resuming the limited
//!   harness's session;
//! - the limit record survives the switch alone (the chat stays Limited
//!   until a turn actually starts) and clears when the continuation runs;
//! - messages queued while the chat was Limited wait behind the
//!   continuation and then follow on the new harness.
//!
//! The harness seam is scripted: a `Limit` stand-in whose first run ends on
//! a classified usage-limit error exactly the way the Claude/Codex drivers
//! do (`Error { kind }` then an errored `Done`), and a `Capture` stand-in
//! that records every request it ran and holds its turns until the test
//! releases them — one instance per harness id, so the continuation shows
//! up as the requests moving from one capture to the other.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessagePart, MessageRole, SessionCommandPayload};
use roboco_engine::{AgentAccounts, AgentAccountsConfig, EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentErrorKind, AgentEvent, ChatConfig, DoneStatus, HarnessId, Model, ReasoningLevel,
    RunRequest, SandboxLevel, SessionLimit, SessionStatus, SteeringMode,
};
use roboco_rpc::methods;

const CHAT: &str = "chat-continue";
const CONTINUE_PROMPT: &str = roboco_proto::RESUME_CONTINUE_PROMPT;

/// A scripted harness: the FIRST run ends on a classified usage-limit error
/// (`Error { kind }` then an errored `Done` — the driver shape ticket 05
/// landed); every run waits for a `finish` signal before its terminal event,
/// so the test owns when the limited turn ends. Later runs never happen in
/// these tests — the continuation always switches to the `Capture` harness.
struct Limit {
    finish: tokio::sync::broadcast::Sender<()>,
    prompts: Arc<Mutex<Vec<String>>>,
}

impl Limit {
    fn new() -> (Arc<Self>, Arc<Mutex<Vec<String>>>) {
        let (finish, _) = tokio::sync::broadcast::channel(16);
        let prompts = Arc::new(Mutex::new(Vec::new()));
        (
            Arc::new(Self {
                finish,
                prompts: prompts.clone(),
            }),
            prompts,
        )
    }
}

#[async_trait]
impl Harness for Limit {
    fn id(&self) -> HarnessId {
        HarnessId::ClaudeCode
    }
    fn display_name(&self) -> &str {
        "Limit"
    }
    // A turn-boundary agent like the Claude driver it stands in for: a Run
    // sent mid-turn on the same runtime is held for the turn's end, not
    // dispatched over the live turn.
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::TurnBoundary
    }
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[ReasoningLevel::Medium]
    }
    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        Ok(vec![])
    }
    async fn run(
        &self,
        request: RunRequest,
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        // Subscribe before recording the prompt: a test that sees the prompt
        // may send `finish` at once.
        let mut finish = self.finish.subscribe();
        self.prompts.lock().unwrap().push(request.prompt.clone());
        Ok(futures::stream::once(async move {
            // The turn runs until the test says it ends — then it dies on
            // the usage limit exactly the way the driver reports one.
            let _ = finish.recv().await;
            vec![
                Ok(AgentEvent::Error {
                    message: "Claude 5-hour limit reached — try again after the limit resets."
                        .into(),
                    kind: Some(AgentErrorKind::UsageLimit {
                        window: "5-hour".into(),
                        resets_at: Some(1_762_675_400_000),
                    }),
                }),
                Ok(AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some("the turn failed".into()),
                    session_id: Some("claude-session".into()),
                }),
            ]
        })
        .flat_map(futures::stream::iter)
        .boxed())
    }
}

/// A scripted harness that records every request it ran and holds each
/// turn until the test releases it (the same control ticket 06's harness
/// has), so queue-order assertions stay deterministic. The continuation
/// switches onto it, so the switch shows up as the requests moving from
/// the `Limit` capture to this one.
struct Capture {
    session: &'static str,
    reply: &'static str,
    requests: Arc<Mutex<Vec<RunRequest>>>,
    finish: tokio::sync::broadcast::Sender<()>,
}

impl Capture {
    fn build(
        session: &'static str,
        reply: &'static str,
    ) -> (Arc<Self>, Arc<Mutex<Vec<RunRequest>>>) {
        let requests = Arc::new(Mutex::new(Vec::new()));
        let (finish, _) = tokio::sync::broadcast::channel(16);
        (
            Arc::new(Self {
                session,
                reply,
                requests: requests.clone(),
                finish,
            }),
            requests,
        )
    }
}

#[async_trait]
impl Harness for Capture {
    fn id(&self) -> HarnessId {
        HarnessId::Pi
    }
    fn display_name(&self) -> &str {
        "Capture"
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
        let mut finish = self.finish.subscribe();
        self.requests.lock().unwrap().push(request.clone());
        let reply = self.reply.to_string();
        let session = self.session.to_string();
        Ok(futures::stream::once(async move {
            // The turn runs until the test says it ends.
            let _ = finish.recv().await;
            vec![
                Ok(AgentEvent::TextDelta { text: reply }),
                Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some(session),
                }),
            ]
        })
        .flat_map(futures::stream::iter)
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

fn claude_config() -> ChatConfig {
    ChatConfig {
        harness: HarnessId::ClaudeCode,
        model: Some("claude-default".into()),
        reasoning: None,
        model_options: Default::default(),
        sandbox: SandboxLevel::WorkspaceWrite,
    }
}

/// Queue a Run command the way the composer's Resume / normal send does (the
/// durable command plane, not the visible message queue). `model` is the
/// pick the dialog landed on for the new harness; `None` leaves the request
/// to the harness's own default.
fn queue_run(
    core: &EngineCore,
    prompt: &str,
    harness: HarnessId,
    model: Option<&str>,
    message_id: &str,
) {
    let request = RunRequest {
        env: Default::default(),
        prompt: prompt.into(),
        harness: Some(harness),
        model: model.map(str::to_string),
        reasoning: None,
        model_options: Default::default(),
        // The chat row's cwd: queued rows dispatch from the row, and a
        // harness session only resumes from the cwd it was opened in.
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
        resume: None,
        provider_fork: None,
        resume_fallback_history: None,
    };
    core.doc_host
        .queue_command(
            CHAT,
            SessionCommandPayload::Run {
                request,
                message_id: message_id.into(),
            },
        )
        .expect("queue run command");
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

fn queue_texts(core: &EngineCore) -> Vec<String> {
    core.doc_host
        .open(CHAT)
        .ok()
        .and_then(|h| h.doc().read_queue().ok())
        .unwrap_or_default()
        .into_iter()
        .map(|item| item.text)
        .collect()
}

fn user_texts(core: &EngineCore) -> Vec<String> {
    core.doc_host
        .open(CHAT)
        .ok()
        .and_then(|h| h.doc().read_entries().ok())
        .unwrap_or_default()
        .iter()
        .filter(|e| e.role == MessageRole::User)
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

fn limit_row(core: &EngineCore) -> Option<SessionLimit> {
    core.sessions
        .session_status(CHAT)
        .and_then(|s| s.limit.clone())
}

/// The chat row as the workspace doc sees it.
fn chat_row(core: &EngineCore) -> roboco_proto::Chat {
    core.workspace.chat(CHAT).expect("chat row").expect("chat exists")
}

/// The transcript's harness-switch seams naming `harness`: `count` of them,
/// each a complete system row.
fn expect_seams(core: &EngineCore, harness: HarnessId, count: usize) {
    let seams: Vec<roboco_doc::SessionMessageEntry> = core
        .doc_host
        .open(CHAT)
        .expect("open chat")
        .doc()
        .read_entries()
        .expect("read entries")
        .into_iter()
        .filter(|entry| {
            entry.role == MessageRole::System
                && entry
                    .parts
                    .iter()
                    .any(|part| matches!(part, MessagePart::HarnessSwitch { harness: to, .. } if *to == harness))
        })
        .collect();
    assert_eq!(seams.len(), count, "seams for {harness:?}");
    for seam in &seams {
        assert_eq!(seam.status, Some(roboco_doc::MessageStatus::Complete));
    }
}

/// Assemble a core around the two scripted harnesses, pre-titled so the
/// auto-titler never dispatches a request of its own, and mount an ISOLATED
/// accounts store (empty) so limit records never read the machine's real
/// CLI logins.
fn setup() -> (
    EngineCore,
    Arc<Limit>,
    Arc<Mutex<Vec<String>>>,
    Arc<Capture>,
    Arc<Mutex<Vec<RunRequest>>>,
    tempfile::TempDir,
) {
    let tmp = tempfile::tempdir().unwrap();
    let (limited, limited_prompts) = Limit::new();
    let (pi, pi_requests) = Capture::build("pi-session", "Picked the work back up.");
    let registry = HarnessRegistry::new();
    registry.register(limited.clone());
    registry.register(pi.clone());
    let core = EngineCore::assemble(&tmp.path().join("data"), Arc::new(registry), HarnessId::Mock)
        .expect("engine core assembles");
    core.sessions.set_agent_accounts(AgentAccounts::new(
        AgentAccountsConfig::isolated(&tmp.path().join("accounts")),
    ));
    core.workspace
        .create_chat(
            CHAT,
            None,
            Some(&core.device_id),
            Some(claude_config()),
            Some("/tmp".into()),
        )
        .expect("create chat");
    // Pre-title so the auto-titler never dispatches a harness request of its own.
    core.workspace
        .rename_chat(CHAT, "Pre-titled")
        .expect("rename chat");
    (core, limited, limited_prompts, pi, pi_requests, tmp)
}

/// Start the turn that hits the limit and wait for the run to be working.
async fn start_limited_turn(prompts: &Arc<Mutex<Vec<String>>>) {
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "Remember PINEAPPLE"),
        "the limited run to start",
    )
    .await;
}

/// Release the limited turn and wait for the chat to settle Limited.
async fn settle_limited(core: &EngineCore) {
    wait_for(|| limit_row(core).is_some(), "the chat to go Limited").await;
}

/// Choose "Continue on another harness": the switch (ticket 24), then the
/// continue prompt (ticket 06's Resume path) — the new agent picks the work
/// up from the budgeted history, the limited harness's session never
/// resumes, the limit record clears when the continuation runs, and the
/// messages queued while the chat was Limited follow behind the
/// continuation on the new harness.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn continuing_on_another_harness_switches_then_sends_the_continue_prompt() {
    let (core, limited, limited_prompts, pi, pi_requests, _tmp) = setup();
    queue_run(&core, "Remember PINEAPPLE", HarnessId::ClaudeCode, None, "m-open");
    start_limited_turn(&limited_prompts).await;
    // A message the user sent while the doomed run was working.
    core.doc_host
        .queue_message(CHAT, "queued one", Vec::new())
        .expect("queue message");
    let _ = limited.finish.send(());
    settle_limited(&core).await;
    // Limited: the queued row WAITS instead of dispatching into the limit.
    assert_eq!(queue_texts(&core), vec!["queued one"]);
    assert_eq!(
        limited_prompts.lock().unwrap().clone(),
        vec!["Remember PINEAPPLE"],
        "no queued message dispatches into the limited chat"
    );

    // The switch is allowed while the chat is Limited (it is errored, not
    // working) — ticket 24's one-way switch runs first.
    switch(&core, HarnessId::Pi, Some("pi-default"))
        .await
        .expect("switch while limited");
    expect_seams(&core, HarnessId::Pi, 1);
    let row = chat_row(&core);
    assert_eq!(row.config.as_ref().map(|c| c.harness), Some(HarnessId::Pi));
    assert_eq!(row.config.as_ref().and_then(|c| c.model.as_deref()), Some("pi-default"));
    assert_eq!(
        row.harness_session_id.as_deref(),
        Some(""),
        "the limited harness's session is tombstoned, never resumed"
    );
    // Still holding the queue — the switch thaws nothing by itself.
    assert_eq!(queue_texts(&core), vec!["queued one"]);

    // The continue prompt — the composer's normal-send path with the fixed
    // prompt, carrying the new harness (the optimistic row stamp routes it)
    // and the model the dialog picked.
    queue_run(&core, CONTINUE_PROMPT, HarnessId::Pi, Some("pi-default"), "m-resume");
    wait_for(
        || pi_requests
            .lock()
            .unwrap()
            .iter()
            .any(|r| r.prompt.ends_with(CONTINUE_PROMPT)),
        "the continue prompt to run on the new harness",
    )
    .await;
    let request = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request.harness, Some(HarnessId::Pi));
    assert_eq!(request.model.as_deref(), Some("pi-default"));
    assert_eq!(
        request.resume, None,
        "the limited harness's session must never resume"
    );
    assert!(request.prompt.contains("Remember PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.ends_with(CONTINUE_PROMPT), "{}", request.prompt);
    assert!(request.prompt.contains("<transcript>"), "{}", request.prompt);
    // The continuation owns the turn (it holds until the test releases it);
    // the queued row still waits behind it.
    assert_eq!(queue_texts(&core), vec!["queued one"]);
    // The continuation ran: the limit record is gone.
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.limit.is_none())
        },
        "the continuation to clear the limit record",
    )
    .await;

    // The continuation ends → the queue drains behind it, onto the new
    // harness, continuing the session the continuation opened.
    let _ = pi.finish.send(());
    wait_for(
        || chat_row(&core).harness_session_id.as_deref() == Some("pi-session"),
        "the new harness's session to be recorded",
    )
    .await;
    wait_for(
        || pi_requests.lock().unwrap().iter().any(|r| r.prompt.contains("queued one")),
        "the queued message to follow the continuation on the new harness",
    )
    .await;
    let follow_up = pi_requests.lock().unwrap().last().cloned().unwrap();
    assert_eq!(follow_up.harness, Some(HarnessId::Pi));
    assert_eq!(follow_up.resume.as_deref(), Some("pi-session"));
    assert!(!follow_up.prompt.contains("PINEAPPLE"), "{}", follow_up.prompt);

    let _ = pi.finish.send(());
    wait_for(
        || {
            queue_texts(&core).is_empty()
                && core
                    .sessions
                    .session_status(CHAT)
                    .is_none_or(|s| s.status != SessionStatus::Working)
        },
        "the last turn to settle and the queue to drain",
    )
    .await;
    // The transcript carries the continue prompt as the run's user entry.
    assert!(user_texts(&core).contains(&CONTINUE_PROMPT.to_string()));
    assert!(queue_texts(&core).is_empty(), "the queue drained");
    assert_eq!(limit_row(&core), None, "the limit record stayed cleared");
    assert_eq!(
        limited_prompts.lock().unwrap().clone(),
        vec!["Remember PINEAPPLE"],
        "the limited harness never ran again"
    );
    core.shutdown().await;
}

/// A message held for the turn end carries its FULL RunRequest — the
/// harness, model, options it was sent with — and the drain prefers that
/// stored request over the chat row. The switch rewrites the stored request
/// onto its config (ticket 24: "queued messages after a switch go to the new
/// harness"), or the held message would run on the OLD harness (its model
/// ids in the new harness's vocabulary) after the switch.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_full_request_held_across_the_switch_runs_on_the_new_harness() {
    let (core, limited, limited_prompts, pi, pi_requests, _tmp) = setup();
    // The opening turn runs the same claude model the held Run is sent
    // with: only a Run the live runtime would carry is held — any other
    // config replaces the runtime instead.
    queue_run(
        &core,
        "Remember PINEAPPLE",
        HarnessId::ClaudeCode,
        Some("claude-model"),
        "m-open",
    );
    start_limited_turn(&limited_prompts).await;
    // A full Run command sent while the turn-boundary run is working: it is
    // HELD for the turn's end with the exact request it was sent with
    // (claude, a claude model) — not a text-only queue row.
    queue_run(
        &core,
        "queued as a full request",
        HarnessId::ClaudeCode,
        Some("claude-model"),
        "m-held",
    );
    wait_for(
        || queue_texts(&core) == vec!["queued as a full request"],
        "the full request to be held for the turn's end",
    )
    .await;
    let _ = limited.finish.send(());
    settle_limited(&core).await;
    assert_eq!(queue_texts(&core), vec!["queued as a full request"]);

    // The switch, while the held row waits behind the Limited pause.
    switch(&core, HarnessId::Pi, Some("pi-default"))
        .await
        .expect("switch while limited");
    expect_seams(&core, HarnessId::Pi, 1);

    // The continuation runs on the new harness; when it settles, the held
    // row follows — rewritten onto the switch's config.
    queue_run(&core, CONTINUE_PROMPT, HarnessId::Pi, Some("pi-default"), "m-resume");
    wait_for(
        || pi_requests
            .lock()
            .unwrap()
            .iter()
            .any(|r| r.prompt.ends_with(CONTINUE_PROMPT)),
        "the continue prompt to run on the new harness",
    )
    .await;
    let _ = pi.finish.send(());
    wait_for(
        || pi_requests
            .lock()
            .unwrap()
            .iter()
            .any(|r| r.prompt.contains("queued as a full request")),
        "the held row to follow the continuation",
    )
    .await;
    let held = pi_requests
        .lock()
        .unwrap()
        .iter()
        .find(|r| r.prompt.contains("queued as a full request"))
        .cloned()
        .expect("the held request");
    assert_eq!(
        held.harness, Some(HarnessId::Pi),
        "the stored request runs on the NEW harness after the switch"
    );
    assert_eq!(
        held.model.as_deref(),
        Some("pi-default"),
        "the stored request carries the switch's model, not the claude one"
    );
    assert!(held.prompt.contains("queued as a full request"), "{}", held.prompt);
    let _ = pi.finish.send(());
    wait_for(
        || queue_texts(&core).is_empty()
            && core
                .sessions
                .session_status(CHAT)
                .is_none_or(|s| s.status != SessionStatus::Working),
        "the last turn to settle and the queue to drain",
    )
    .await;
    assert_eq!(
        limited_prompts.lock().unwrap().clone(),
        vec!["Remember PINEAPPLE"],
        "the limited harness never ran the held message"
    );
    core.shutdown().await;
}

/// The switch alone does NOT clear the Limited state: the record lives as
/// long as the errored status it rode, so the banner stays up (and the
/// Resume / continue actions stay offered) until a turn actually starts. A
/// crash between the switch and the send leaves an honest Limited chat on
/// the new harness, ready to resume.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_switch_alone_keeps_the_chat_limited_until_a_turn_runs() {
    let (core, limited, limited_prompts, pi, pi_requests, _tmp) = setup();
    queue_run(&core, "Remember PINEAPPLE", HarnessId::ClaudeCode, None, "m-open");
    start_limited_turn(&limited_prompts).await;
    let _ = limited.finish.send(());
    settle_limited(&core).await;

    switch(&core, HarnessId::Pi, None).await.expect("switch");
    expect_seams(&core, HarnessId::Pi, 1);
    assert_eq!(
        chat_row(&core).config.as_ref().map(|c| c.harness),
        Some(HarnessId::Pi)
    );
    // Still Limited: errored with the record — no turn has started.
    assert!(limit_row(&core).is_some());
    assert_eq!(
        core.sessions.session_status(CHAT).map(|s| s.status),
        Some(SessionStatus::Errored)
    );

    // The user's own next message (a normal send, not the resume prompt)
    // clears it just the same.
    queue_run(&core, "try a different angle", HarnessId::Pi, None, "m-next");
    // Wait for the harness to hold the run, not just for Working: the
    // status publishes before run() subscribes to `finish`, so a send in
    // that window would be lost and the turn would never settle.
    wait_for(
        || pi_requests
            .lock()
            .unwrap()
            .iter()
            .any(|r| r.prompt.contains("try a different angle")),
        "the new turn to start",
    )
    .await;
    let _ = pi.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the new turn to settle",
    )
    .await;
    assert_eq!(limit_row(&core), None, "the new turn cleared the limit record");
    core.shutdown().await;
}
