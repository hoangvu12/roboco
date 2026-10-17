//! Limited chats (orchestration v2, ticket 06): a run that ends on a
//! usage-limit error leaves the additive limit record on the chat's status —
//! harness, window, reset time (filled from the harness account's cached
//! windows when the harness's own error carried none) and the account it
//! happened on. Queued messages wait behind the continuation; Resume sends
//! the continue prompt as a normal run; a new turn clears the record.
//!
//! The harness seam is a scripted stand-in (`LimitHarness`): its first run
//! ends on a classified usage-limit error exactly the way the Claude/Codex
//! drivers do (`Error { kind }` then an errored `Done`), later runs hold
//! until the test releases them so queue-order assertions are deterministic.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessagePart, MessageRole, SessionCommandPayload};
use roboco_engine::{AgentAccounts, AgentAccountsConfig, EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentErrorKind, AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest,
    SandboxLevel, SessionLimit, SessionStatus, SteeringMode,
};

const CHAT: &str = "chat-limited";

/// A scripted harness: the FIRST run ends on an error carrying `kind`
/// (a usage-limit kind for the Limited tests, `None` for plain failures);
/// every run waits for a `finish` signal before its terminal event, so the
/// test owns when turns end; later runs complete normally.
struct LimitHarness {
    id: HarnessId,
    kind: Option<AgentErrorKind>,
    /// A second classified error the doomed run emits AFTER the first —
    /// Claude's live shape (a detailed `rate_limit_event`, then the CLI's
    /// terse `rate_limit` assistant code), which the engine's limit merge
    /// must survive.
    second_kind: Option<AgentErrorKind>,
    finish: tokio::sync::broadcast::Sender<()>,
    prompts: Arc<Mutex<Vec<String>>>,
    runs: std::sync::atomic::AtomicUsize,
}

impl LimitHarness {
    fn new(id: HarnessId, kind: Option<AgentErrorKind>) -> (Arc<Self>, Arc<Mutex<Vec<String>>>) {
        Self::with_second_kind(id, kind, None)
    }

    fn with_second_kind(
        id: HarnessId,
        kind: Option<AgentErrorKind>,
        second_kind: Option<AgentErrorKind>,
    ) -> (Arc<Self>, Arc<Mutex<Vec<String>>>) {
        let (finish, _) = tokio::sync::broadcast::channel(16);
        let prompts = Arc::new(Mutex::new(Vec::new()));
        (
            Arc::new(Self {
                id,
                kind,
                second_kind,
                finish,
                prompts: prompts.clone(),
                runs: std::sync::atomic::AtomicUsize::new(0),
            }),
            prompts,
        )
    }
}

#[async_trait]
impl Harness for LimitHarness {
    fn id(&self) -> HarnessId {
        self.id
    }
    fn display_name(&self) -> &str {
        "Limit"
    }
    fn supports_steering(&self) -> bool {
        false
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
        let run = self.runs.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let kind = self.kind.clone();
        let second_kind = self.second_kind.clone();
        let session = format!("sess-{run}");
        let started = futures::stream::iter(vec![Ok(AgentEvent::SessionStarted {
            harness: self.id,
            model: "limit-1".into(),
            tools: vec![],
            cwd: request.cwd.clone(),
            session_id: session.clone(),
            assistant_message_id: format!("a-{run}"),
        })]);
        let tail = futures::stream::once(async move {
            // The turn runs until the test says it ends.
            let _ = finish.recv().await;
            if run == 0 {
                // The classified limit error (then an optional second one —
                // `second_kind`, Claude's live shape), then the errored Done
                // — the driver shape ticket 05 landed. `None` kind = a
                // plain, unclassified failure (the not-Limited control
                // case).
                let (message, kind) = match kind {
                    Some(kind @ AgentErrorKind::UsageLimit { .. }) => (
                        "Claude 5-hour limit reached — the turn was blocked.",
                        Some(kind),
                    ),
                    Some(kind) => ("The agent failed its login.", Some(kind)),
                    None => ("The agent child crashed.", None),
                };
                let mut events = vec![
                    Ok(AgentEvent::Error {
                        message: message.into(),
                        kind,
                    }),
                ];
                if let Some(kind) = second_kind {
                    events.push(Ok(AgentEvent::Error {
                        message: "Claude usage limit reached — try again after the limit resets."
                            .into(),
                        kind: Some(kind),
                    }));
                }
                events.push(Ok(AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some("the turn failed".into()),
                    session_id: Some(session),
                }));
                events
            } else {
                vec![
                    Ok(AgentEvent::TextDelta {
                        text: "Picked the work back up.".into(),
                    }),
                    Ok(AgentEvent::Done {
                        status: DoneStatus::Completed,
                        result: None,
                        error: None,
                        session_id: Some(session),
                    }),
                ]
            }
        })
        .flat_map(futures::stream::iter);
        Ok(started.chain(tail).boxed())
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

/// The continue prompt Resume sends — asserted as the exact engine-side run
/// prompt, spelled by the shared proto constant.
fn run_request(prompt: &str, harness: HarnessId) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: Some(harness),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: ".".into(),
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

/// Queue a Run command the way the composer's Resume / normal send does
/// (the durable command plane, not the visible message queue).
fn queue_run(core: &EngineCore, prompt: &str, harness: HarnessId, message_id: &str) {
    core.doc_host
        .queue_command(
            CHAT,
            SessionCommandPayload::Run {
                request: run_request(prompt, harness),
                message_id: message_id.into(),
            },
        )
        .expect("queue run command");
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

/// Assemble a core around the scripted harness, pre-titled so the
/// auto-titler never dispatches a request of its own, and mount an ISOLATED
/// accounts store (empty by default) so limit records never read the
/// machine's real CLI logins.
async fn setup(
    built: (Arc<LimitHarness>, Arc<Mutex<Vec<String>>>),
) -> (
    EngineCore,
    Arc<LimitHarness>,
    Arc<Mutex<Vec<String>>>,
) {
    let tmp = tempfile::tempdir().unwrap();
    // Leak the guard: the engine outlives this helper; uniqueness is all the
    // test needs.
    let path = tmp.keep();
    let (harness, prompts) = built;
    let registry = HarnessRegistry::new();
    registry.register(harness.clone());
    let core = EngineCore::assemble(&path.join("data"), Arc::new(registry), HarnessId::Mock)
        .expect("engine core assembles");
    core.sessions
        .set_agent_accounts(AgentAccounts::new(AgentAccountsConfig::isolated(
            &path.join("accounts"),
        )));
    create_chat(&core).await;
    (core, harness, prompts)
}

async fn create_chat(core: &EngineCore) {
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": CHAT,
                "deviceId": core.device_id,
            }),
        )
        .await
        .expect("createChat");
    // Pre-title so the auto-titler never dispatches a harness request of its own.
    core.workspace
        .rename_chat(CHAT, "Pre-titled")
        .expect("rename chat");
}

fn limit_row(core: &EngineCore) -> Option<SessionLimit> {
    core.sessions
        .session_status(CHAT)
        .and_then(|s| s.limit.clone())
}

/// **The terse code never overwrites the detailed event** (Claude's live
/// order): a turn that first reports a rejected window WITH its reset, then
/// the CLI's terse `rate_limit` assistant code (window unknown, no reset)
/// ends Limited on the DETAILED classification — the later, windowless
/// limit merges into the earlier one instead of replacing it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_terse_limit_code_does_not_overwrite_the_detailed_event() {
    let (core, harness, prompts) = setup(LimitHarness::with_second_kind(
        HarnessId::ClaudeCode,
        Some(AgentErrorKind::UsageLimit {
            window: "5-hour".into(),
            resets_at: Some(1_762_675_400_000),
        }),
        // The terse code: window unknown ("usage"), no reset.
        Some(AgentErrorKind::UsageLimit {
            window: "usage".into(),
            resets_at: None,
        }),
    ))
    .await;

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the limited run to start",
    )
    .await;
    let _ = harness.finish.send(());

    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Errored && s.limit.is_some())
        },
        "the chat to go Limited",
    )
    .await;
    let limit = limit_row(&core).expect("the limit record");
    assert_eq!(limit.harness, HarnessId::ClaudeCode);
    assert_eq!(limit.window, "5-hour", "the detailed window survives the terse code");
    assert_eq!(
        limit.resets_at,
        Some(1_762_675_400_000),
        "the reported reset survives the terse code"
    );
    core.shutdown().await;
}

/// A run ending on a classified usage-limit error leaves the additive limit
/// record on the errored chat — harness, window and reset time exactly as
/// the harness's own error reported them — and mirrors it onto the workspace
/// status row so remote sidebars can show it too.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_limit_errored_run_records_the_limit_on_the_chat_status() {
    let (core, harness, prompts) = setup(LimitHarness::new(
        HarnessId::ClaudeCode,
        Some(AgentErrorKind::UsageLimit {
            window: "5-hour".into(),
            resets_at: Some(1_762_675_400_000),
        }),
    ))
    .await;

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the limited run to start",
    )
    .await;
    let _ = harness.finish.send(());

    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Errored)
        },
        "the chat to settle errored",
    )
    .await;
    // The chat stays ERRORED on the wire (ADR 0005); the record is the
    // additive Limited detail.
    assert_eq!(
        limit_row(&core),
        Some(SessionLimit {
            harness: HarnessId::ClaudeCode,
            window: "5-hour".into(),
            resets_at: Some(1_762_675_400_000),
            account_id: None,
        })
    );
    // No accounts on the engine's isolated store → no account on the record.
    // The row rides the workspace doc too (remote devices' markers).
    wait_for(
        || {
            core.workspace
                .read_sessions()
                .map(|rows| {
                    rows.iter().any(|row| {
                        row.chat_id == CHAT && row.status == SessionStatus::Errored
                            && row.limit.as_ref().is_some_and(|l| l.window == "5-hour")
                    })
                })
                .unwrap_or(false)
        },
        "the limit row to mirror into the workspace doc",
    )
    .await;
    core.shutdown().await;
}

/// A run that ends on an UNCLASSIFIED error leaves the chat errored with no
/// limit record — and its queued messages still flush at turn end (the
/// queue-hold is a Limited behavior, not a general error behavior).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_plain_error_records_no_limit_and_flushes_the_queue_as_before() {
    let (core, harness, prompts) =
        setup(LimitHarness::new(HarnessId::ClaudeCode, None)).await;

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the failing run to start",
    )
    .await;
    core.doc_host
        .queue_message(CHAT, "follow up", Vec::new())
        .expect("queue message");
    let _ = harness.finish.send(());

    // Today's behavior, unchanged: the turn ended errored and the queued row
    // goes out at once. The Errored status is only a blip before that next
    // run starts, so the flush itself is the proof the errored turn ended.
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "follow up"),
        "the queued message to flush after a plain error",
    )
    .await;
    assert_eq!(limit_row(&core), None, "unclassified errors are not Limited");
    let _ = harness.finish.send(());
    core.shutdown().await;
}

/// The reset time the harness did not carry is filled from the harness
/// account's cached usage windows, and the record names that account: the
/// active login at run time, by its engine slot id.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_missing_reset_time_is_filled_from_the_accounts_cached_windows() {
    let tmp = tempfile::tempdir().unwrap();
    let root = tmp.path().join("accounts");
    let config = AgentAccountsConfig {
        data_dir: root.join("data"),
        claude_config_dir: root.join("claude"),
        claude_config_file: root.join("claude.json"),
        codex_home: root.join("codex"),
        cursor_sdk_auth_file: root.join("cursor-sdk").join("auth.json"),
        // File-only: a temp config must never reach the real Keychain login.
        claude_keychain_service: None,
        antigravity_home: Some(root.join("gemini")),
        antigravity_keychain: false,
        ..AgentAccountsConfig::isolated(&root)
    };
    // A live Claude login (accountUuid uuid-limit-1 → usage key
    // "claude-code:uuid-limit-1") with cached meters: the Session window is
    // EXHAUSTED (1.0) so the account reads limited-until its reset; the Week
    // window has usage left and a later reset — it must contribute nothing
    // to the fill.
    std::fs::create_dir_all(&config.claude_config_dir).unwrap();
    std::fs::write(
        &config.claude_config_file,
        serde_json::json!({
            "oauthAccount": {
                "accountUuid": "uuid-limit-1",
                "emailAddress": "dev@roboco.test",
                "displayName": "Dev Tester",
                "organizationName": "Test Org",
            },
            "userID": "user-uuid-limit-1",
        })
        .to_string(),
    )
    .unwrap();
    std::fs::write(
        config.claude_config_dir.join(".credentials.json"),
        serde_json::json!({
            "claudeAiOauth": {
                "accessToken": "token-limit-1",
                "refreshToken": "refresh-limit-1",
                "expiresAt": 4_102_444_800_000i64,
            }
        })
        .to_string(),
    )
    .unwrap();
    let session_reset = chrono::DateTime::parse_from_rfc3339("2026-11-09T10:30:00Z")
        .unwrap()
        .to_utc();
    let cache = root.join("data").join("agent-accounts");
    std::fs::create_dir_all(&cache).unwrap();
    std::fs::write(
        cache.join("usage-cache.json"),
        serde_json::json!({
            "entries": {
                "claude-code:uuid-limit-1": {
                    "usage": {
                        "windows": [
                            { "label": "Session", "usedFraction": 1.0, "resetsAt": "2026-11-09T10:30:00Z" },
                            { "label": "Week", "usedFraction": 0.4, "resetsAt": "2026-11-12T09:00:00Z" },
                        ],
                    },
                    "fetchedAt": 1_762_670_000_000i64,
                    "checkedAt": 1_762_670_000_000i64,
                },
            },
        })
        .to_string(),
    )
    .unwrap();
    let accounts = AgentAccounts::new(config);
    // The account the limit "happened on": the active login's slot id.
    let expected_account = accounts
        .active_account(HarnessId::ClaudeCode)
        .await
        .expect("the fake login is the active account");

    let (core, harness, prompts) = setup(LimitHarness::new(
        HarnessId::ClaudeCode,
        Some(AgentErrorKind::UsageLimit {
            window: "usage".into(),
            resets_at: None,
        }),
    ))
    .await;
    // Mount the SEEDED store the engine should read the fill from.
    core.sessions.set_agent_accounts(accounts);

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the limited run to start",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Errored)
        },
        "the chat to settle errored",
    )
    .await;
    assert_eq!(
        limit_row(&core),
        Some(SessionLimit {
            harness: HarnessId::ClaudeCode,
            window: "usage".into(),
            // Filled from the account's limited_until: the exhausted
            // Session window's reset, not the unexhausted Week's later one.
            resets_at: Some(session_reset.timestamp_millis()),
            account_id: Some(expected_account.id),
        }),
        "the reset time and account come from the harness account"
    );
    core.shutdown().await;
}

/// Resume sends the continue prompt as a NORMAL run — the composer's Run
/// command — which dispatches ahead of the queue the limited chat is
/// holding; the queued messages are delivered after the continuation, in
/// order; a new turn clears the limit record.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn resume_runs_the_continue_prompt_with_the_queue_behind_it() {
    let (core, harness, prompts) = setup(LimitHarness::new(
        HarnessId::ClaudeCode,
        Some(AgentErrorKind::UsageLimit {
            window: "5-hour".into(),
            resets_at: None,
        }),
    ))
    .await;

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the limited run to start",
    )
    .await;
    // Messages the user sent while the doomed run was working.
    core.doc_host
        .queue_message(CHAT, "queued one", Vec::new())
        .expect("queue one");
    core.doc_host
        .queue_message(CHAT, "queued two", Vec::new())
        .expect("queue two");
    let _ = harness.finish.send(());

    wait_for(|| limit_row(&core).is_some(), "the chat to go Limited").await;
    // Limited: the queued rows WAIT instead of dispatching into the limit.
    assert_eq!(queue_texts(&core), vec!["queued one", "queued two"]);
    assert_eq!(
        prompts.lock().unwrap().clone(),
        vec!["do the work"],
        "no queued message dispatches into the limited chat"
    );

    // Resume — the composer's normal-send path with the fixed prompt.
    queue_run(
        &core,
        roboco_proto::RESUME_CONTINUE_PROMPT,
        HarnessId::ClaudeCode,
        "m-resume",
    );
    wait_for(
        || {
            prompts
                .lock()
                .unwrap()
                .iter()
                .any(|p| p == roboco_proto::RESUME_CONTINUE_PROMPT)
        },
        "the continue prompt to run ahead of the queue",
    )
    .await;
    // The continuation owns the turn; the queued rows still wait behind it.
    assert_eq!(queue_texts(&core), vec!["queued one", "queued two"]);
    // A new turn started: the limit record is gone.
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.limit.is_none())
        },
        "the new turn to clear the limit record",
    )
    .await;

    // The continuation ends → the queue drains behind it, head first.
    let _ = harness.finish.send(());
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "queued one"),
        "the first queued message to follow the continuation",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "queued two"),
        "the second queued message to follow in order",
    )
    .await;
    let _ = harness.finish.send(());

    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the last turn to settle",
    )
    .await;
    let sent: Vec<String> = prompts.lock().unwrap().clone();
    assert_eq!(
        sent,
        vec![
            "do the work",
            roboco_proto::RESUME_CONTINUE_PROMPT,
            "queued one",
            "queued two",
        ],
        "resume runs first, queued messages follow in order"
    );
    assert!(queue_texts(&core).is_empty(), "the queue drained");
    // The transcript carries the continue prompt as the run's user entry.
    assert!(user_texts(&core).contains(&roboco_proto::RESUME_CONTINUE_PROMPT.to_string()));
    assert_eq!(
        limit_row(&core),
        None,
        "the limit record stayed cleared after the resumed turns"
    );
    core.shutdown().await;
}

/// ANY new turn clears the record, not just a Resume: an ordinary follow-up
/// message starts a fresh turn and the chat stops reading as Limited.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_plain_new_turn_clears_the_limit_record() {
    let (core, harness, prompts) = setup(LimitHarness::new(
        HarnessId::ClaudeCode,
        Some(AgentErrorKind::UsageLimit {
            window: "5-hour".into(),
            resets_at: None,
        }),
    ))
    .await;

    queue_run(&core, "do the work", HarnessId::ClaudeCode, "m-open");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "do the work"),
        "the limited run to start",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(|| limit_row(&core).is_some(), "the chat to go Limited").await;

    // The user's own next message (a normal send, not the resume prompt).
    queue_run(&core, "try a different angle", HarnessId::ClaudeCode, "m-next");
    // Wait for the harness to hold the run, not just for Working: the
    // status publishes before run() subscribes to `finish`, so a send in
    // that window would be lost and the turn would never settle.
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "try a different angle"),
        "the new turn to start",
    )
    .await;
    assert_eq!(
        limit_row(&core),
        None,
        "the limit record clears when the new turn starts"
    );
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the new turn to settle",
    )
    .await;
    assert_eq!(limit_row(&core), None);
    core.shutdown().await;
}
