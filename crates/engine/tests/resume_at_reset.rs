//! Resume at reset (orchestration v2, ticket 07): a Limited chat's durable
//! continuation, owned by the engine. It fires the continue prompt at the
//! limit's reset time exactly like the manual Resume (ticket 06) — queued
//! messages behind it; it survives engine restarts and an overdue one fires
//! after boot; a new message, an archive or a delete cancels it; the
//! **Auto-resume limited chats** setting (off by default) arms it
//! automatically when a chat becomes Limited; and a continuation that hits
//! the limit again re-arms only via a NEW limit record whose reset advances
//! past the fired one — never in a loop.
//!
//! The harness seam is the same scripted stand-in as `limited_chats.rs`
//! (`ResetHarness`): each run's script says whether it ends on a classified
//! usage-limit error (with the reset time to report) or completes, every run
//! waits for a `finish` signal before its terminal event, and the script is
//! editable between runs so a continuation can hit the limit AGAIN.

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
    SandboxLevel, SessionStatus, SteeringMode,
};
use roboco_rpc::methods;

const CHAT: &str = "chat-resume-reset";
const CONTINUE: &str = roboco_proto::RESUME_CONTINUE_PROMPT;

/// The per-run script: `Some(resets_at)` ends the run on a classified
/// usage-limit error reporting that reset time; `None` completes normally.
type Script = Arc<Mutex<Vec<Option<i64>>>>;

/// A scripted harness: run `n` ends on a usage-limit error with
/// `script[n]`'s reset time when `Some`, else completes. Every run holds
/// until the test releases it, so turn-end order is deterministic.
struct ResetHarness {
    finish: tokio::sync::broadcast::Sender<()>,
    prompts: Arc<Mutex<Vec<String>>>,
    script: Script,
    runs: std::sync::atomic::AtomicUsize,
}

impl ResetHarness {
    /// `script` is the initial per-run plan (extendable between runs with
    /// [`Self::set_script`]). Returns the prompts log with the harness.
    fn new(script: Vec<Option<i64>>) -> (Arc<Self>, Arc<Mutex<Vec<String>>>) {
        let (finish, _) = tokio::sync::broadcast::channel(16);
        let prompts = Arc::new(Mutex::new(Vec::new()));
        (
            Arc::new(Self {
                finish,
                prompts: prompts.clone(),
                script: Arc::new(Mutex::new(script)),
                runs: std::sync::atomic::AtomicUsize::new(0),
            }),
            prompts,
        )
    }

    /// Rewrite one run's outcome — the "hits the limit again" scripts.
    fn set_script(&self, run: usize, resets_at: Option<i64>) {
        let mut script = self.script.lock().unwrap();
        while script.len() <= run {
            script.push(None);
        }
        script[run] = resets_at;
    }
}

#[async_trait]
impl Harness for ResetHarness {
    fn id(&self) -> HarnessId {
        HarnessId::ClaudeCode
    }
    fn display_name(&self) -> &str {
        "Reset"
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
        let resets_at = self.script.lock().unwrap().get(run).copied().flatten();
        let session = format!("sess-{run}");
        let started = futures::stream::iter(vec![Ok(AgentEvent::SessionStarted {
            harness: HarnessId::ClaudeCode,
            model: "reset-1".into(),
            tools: vec![],
            cwd: request.cwd.clone(),
            session_id: session.clone(),
            assistant_message_id: format!("a-{run}"),
        })]);
        let tail = futures::stream::once(async move {
            // The turn runs until the test says it ends.
            let _ = finish.recv().await;
            match resets_at {
                Some(resets_at) => vec![
                    Ok(AgentEvent::Error {
                        message: "Claude usage limit reached — try again after the limit resets."
                            .into(),
                        kind: Some(AgentErrorKind::UsageLimit {
                            window: "5-hour".into(),
                            resets_at: Some(resets_at),
                        }),
                    }),
                    Ok(AgentEvent::Done {
                        status: DoneStatus::Errored,
                        result: None,
                        error: Some("the turn failed".into()),
                        session_id: Some(session),
                    }),
                ],
                None => vec![
                    Ok(AgentEvent::TextDelta {
                        text: "Picked the work back up.".into(),
                    }),
                    Ok(AgentEvent::Done {
                        status: DoneStatus::Completed,
                        result: None,
                        error: None,
                        session_id: Some(session),
                    }),
                ],
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

/// Epoch ms `ms` from now — the tests' short reset times.
fn in_ms(ms: i64) -> i64 {
    chrono::Utc::now().timestamp_millis() + ms
}

fn run_request(prompt: &str) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: Some(HarnessId::ClaudeCode),
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

/// Queue a Run command the way the composer's send does.
fn queue_run(core: &EngineCore, prompt: &str, message_id: &str) {
    core.doc_host
        .queue_command(
            CHAT,
            SessionCommandPayload::Run {
                request: run_request(prompt),
                message_id: message_id.into(),
            },
        )
        .expect("queue run command");
}

fn prompts_contain(prompts: &Arc<Mutex<Vec<String>>>, needle: &str) -> bool {
    prompts.lock().unwrap().iter().any(|p| p == needle)
}

fn sent_prompts(prompts: &Arc<Mutex<Vec<String>>>) -> Vec<String> {
    prompts.lock().unwrap().clone()
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

/// Assemble a core around the scripted harness, pre-titled, with an
/// ISOLATED accounts store, and a chat carrying a Claude config row — the
/// continuation's run configuration (the same one the completion-notice
/// turn uses: the chat row first, the last request as fallback).
async fn setup(
    script: Vec<Option<i64>>,
) -> (
    EngineCore,
    Arc<ResetHarness>,
    Arc<Mutex<Vec<String>>>,
    roboco_rpc::RpcClient,
) {
    let tmp = tempfile::tempdir().unwrap();
    // Leak the guard: the engine outlives this helper; uniqueness is all the
    // test needs.
    let path = tmp.keep();
    setup_at(&path.join("data"), script).await
}

/// [`setup`] over an explicit data dir — the restart test reuses one dir for
/// both engines.
async fn setup_at(
    data_dir: &std::path::Path,
    script: Vec<Option<i64>>,
) -> (
    EngineCore,
    Arc<ResetHarness>,
    Arc<Mutex<Vec<String>>>,
    roboco_rpc::RpcClient,
) {
    let (harness, prompts) = ResetHarness::new(script);
    let registry = HarnessRegistry::new();
    registry.register(harness.clone());
    let core = EngineCore::assemble(data_dir, Arc::new(registry), HarnessId::Mock)
        .expect("engine core assembles");
    core.sessions.set_agent_accounts(AgentAccounts::new(
        AgentAccountsConfig::isolated(&data_dir.parent().unwrap().join("accounts")),
    ));
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": CHAT,
                "deviceId": core.device_id,
                "config": {
                    "harness": "claude-code",
                    "sandbox": "workspace-write",
                },
            }),
        )
        .await
        .expect("createChat");
    // Pre-title so the auto-titler never dispatches a harness request of its own.
    core.workspace
        .rename_chat(CHAT, "Pre-titled")
        .expect("rename chat");
    (core, harness, prompts, client)
}

/// Run the opening turn into the scripted limit and wait for the chat to
/// settle Limited.
async fn run_into_limit(
    core: &EngineCore,
    harness: &Arc<ResetHarness>,
    prompts: &Arc<Mutex<Vec<String>>>,
) {
    queue_run(core, "do the work", "m-open");
    wait_for(
        || prompts_contain(prompts, "do the work"),
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
        "the chat to settle Limited",
    )
    .await;
}

fn scheduled_fire_at(core: &EngineCore) -> Option<i64> {
    core.sessions
        .session_status(CHAT)
        .and_then(|s| s.scheduled_resume.map(|r| r.fire_at))
}

/// Arm the continuation through the client the way the banner's action does.
async fn schedule_resume(client: &roboco_rpc::RpcClient) {
    client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "scheduleResumeAtReset", "chatId": CHAT }),
        )
        .await
        .expect("scheduleResumeAtReset");
}

/// Flip the Auto-resume limited chats engine setting through the settings RPC.
async fn set_auto_resume(client: &roboco_rpc::RpcClient, on: bool) {
    let saved = client
        .call(
            methods::SET_ENGINE_SETTINGS,
            serde_json::json!({ "autoResumeLimited": on }),
        )
        .await
        .expect("SetEngineSettings");
    assert_eq!(saved["autoResumeLimited"], on, "the reply is the stored value");
}

/// **Resume at reset fires the continuation at the reset time** — the same
/// run the manual Resume is: the continue prompt as a normal run, the queued
/// messages behind it, the limit record cleared by the new turn, the armed
/// record cleared by the firing, and the row mirrored to the workspace doc
/// (remote sidebars' banners) both ways.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn resume_at_reset_fires_the_continue_prompt_at_the_reset_time() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), None]).await;

    run_into_limit(&core, &harness, &prompts).await;
    // Messages the user sent while the doomed run was working — they wait
    // behind the continuation, exactly like the manual Resume.
    core.doc_host
        .queue_message(CHAT, "queued one", Vec::new())
        .expect("queue message");

    schedule_resume(&client).await;
    // The engine arms at the limit record's own reset time — the exact time
    // the harness's error reported, read back from the settled row.
    let fire_at = core
        .sessions
        .session_status(CHAT)
        .and_then(|s| s.limit.and_then(|limit| limit.resets_at))
        .expect("the limit record carries the reset time");
    wait_for(
        || scheduled_fire_at(&core) == Some(fire_at),
        "the armed continuation to reach the session row",
    )
    .await;
    // The row rides the workspace doc too — remote devices' banners.
    wait_for(
        || {
            core.workspace
                .read_sessions()
                .map(|rows| {
                    rows.iter().any(|row| {
                        row.chat_id == CHAT
                            && row
                                .scheduled_resume
                                .is_some_and(|r| r.fire_at == fire_at)
                    })
                })
                .unwrap_or(false)
        },
        "the armed continuation to mirror into the workspace doc",
    )
    .await;

    // The reset time arrives; the engine fires the continuation.
    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the continuation to fire at the reset time",
    )
    .await;
    // The new turn owns the chat: the limit record and the armed record are
    // both gone.
    wait_for(
        || {
            core.sessions.session_status(CHAT).is_some_and(|s| {
                s.status == SessionStatus::Working
                    && s.limit.is_none()
                    && s.scheduled_resume.is_none()
            })
        },
        "the continuation's turn to clear the limit and armed records",
    )
    .await;
    // The queued rows still wait behind the continuation's turn.
    assert!(!prompts_contain(&prompts, "queued one"));

    let _ = harness.finish.send(());
    wait_for(
        || prompts_contain(&prompts, "queued one"),
        "the queued message to follow the continuation",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the followed-up turn to settle",
    )
    .await;
    assert_eq!(
        sent_prompts(&prompts),
        vec!["do the work", CONTINUE, "queued one"],
        "the continuation fired the continue prompt, the queue followed"
    );
    // The transcript carries the continuation as its user entry.
    assert!(user_texts(&core).contains(&CONTINUE.to_string()));
    core.shutdown().await;
}

/// **Restarts:** a pending continuation survives an engine restart, and one
/// whose time passed while the engine was down fires after boot. The
/// transcript entry is idempotent by its deterministic message id.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_pending_continuation_survives_a_restart_and_an_overdue_one_fires_after_boot() {
    let tmp = tempfile::tempdir().unwrap();
    let data_dir = tmp.path().join("data");
    let (core, harness, prompts, client) = setup_at(&data_dir, vec![Some(in_ms(2_000))]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;

    // A graceful shutdown while the continuation is still pending: it must
    // NOT be consumed (shutting down never fires one) — the record survives.
    core.shutdown().await;
    drop(core);
    // The reset time passes while no engine is running.
    tokio::time::sleep(Duration::from_millis(2_200)).await;
    // The first engine's harness never saw a continuation.
    assert!(!prompts_contain(&prompts, CONTINUE));

    // Boot a fresh engine over the same data dir: the overdue continuation
    // fires after the engine is up.
    let (core, harness, prompts, _client) = setup_at(&data_dir, vec![None]).await;
    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the overdue continuation to fire after boot",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the continuation's turn to settle",
    )
    .await;
    assert_eq!(sent_prompts(&prompts), vec![CONTINUE]);
    // Exactly one continuation entry — the deterministic message id keeps a
    // crash-raced re-fire from duplicating the transcript row.
    assert_eq!(
        user_texts(&core)
            .iter()
            .filter(|text| text.as_str() == CONTINUE)
            .count(),
        1
    );
    core.shutdown().await;
}

/// **Restart keeps the client-visible rows:** the in-memory status map is
/// the merged WatchSessions source for this device's chats, and it starts
/// empty at boot — without the republish, a still-Limited chat and its
/// armed continuation vanish from the banner (no "resumes in…", no
/// Cancel) and `ScheduleResumeAtReset` refuses ("not Limited"). The boot
/// re-creates both rows from the durable workspace row.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_restart_republishes_the_limit_and_scheduled_resume_rows() {
    let tmp = tempfile::tempdir().unwrap();
    let data_dir = tmp.path().join("data");
    // A reset far enough out that nothing fires while we look.
    let (core, harness, prompts, client) = setup_at(&data_dir, vec![Some(in_ms(60_000))]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    let fire_at = scheduled_fire_at(&core).expect("the continuation to arm");

    core.shutdown().await;
    drop(core);
    tokio::time::sleep(Duration::from_millis(200)).await;

    // Boot a fresh engine over the same data dir: the client-visible rows
    // are republished at boot, before anything fires.
    let (core, _harness, prompts, _client) = setup_at(&data_dir, vec![None]).await;
    let row = core.sessions.session_status(CHAT).expect("the republished row");
    assert_eq!(row.status, SessionStatus::Errored, "still Limited");
    assert!(row.limit.is_some(), "the limit record survived the restart");
    assert_eq!(
        row.scheduled_resume.map(|resume| resume.fire_at),
        Some(fire_at),
        "the armed continuation's row survived the restart"
    );
    // The workspace mirror carries it too (remote devices' banners).
    assert!(core
        .workspace
        .read_sessions()
        .map(|rows| rows.iter().any(|row| {
            row.chat_id == CHAT
                && row.limit.is_some()
                && row.scheduled_resume.is_some_and(|r| r.fire_at == fire_at)
        }))
        .unwrap_or(false));
    // Nothing fired while we looked.
    assert!(!prompts_contain(&prompts, CONTINUE));
    core.shutdown().await;
}

/// **Cancellation by a new message:** a normal send (the composer's Run
/// command) cancels the pending continuation — the user's newer intent wins.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_new_message_cancels_the_pending_continuation() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), None]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;

    // The user's own next message (a normal send, not the resume prompt).
    queue_run(&core, "try a different angle", "m-next");
    wait_for(
        || scheduled_fire_at(&core).is_none(),
        "the new message to cancel the continuation",
    )
    .await;
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Working)
        },
        "the new turn to start",
    )
    .await;
    let _ = harness.finish.send(());

    // The reset time comes and goes: no continuation ever fires.
    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert!(!prompts_contain(&prompts, CONTINUE));
    assert_eq!(
        sent_prompts(&prompts),
        vec!["do the work", "try a different angle"]
    );
    core.shutdown().await;
}

/// **Queueing is not sending (story 41):** a message the user queued on the
/// Limited chat does NOT cancel the armed continuation — queued messages
/// wait BEHIND it, order preserved. The continuation fires at the reset
/// time and the queued row follows it out (a queued row is not the newer
/// intent a direct send is; the user can still cancel with a message or an
/// archive).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_queued_message_waits_behind_the_pending_continuation() {
    // Three runs: the doomed turn, the continuation, the queued row's own.
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), None, None]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;

    core.doc_host
        .queue_message(CHAT, "queued while limited", Vec::new())
        .expect("queue message");
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(
        scheduled_fire_at(&core).is_some(),
        "queueing a row does not cancel the armed continuation"
    );

    // The reset time arrives: the continuation fires, and the queued row
    // follows it — the queue thaws with the continuation's dispatch and
    // drains when its turn ends.
    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the continuation to fire at the reset time",
    )
    .await;
    // The continuation owns the turn: the queued row waits behind it.
    assert!(!prompts_contain(&prompts, "queued while limited"));
    let _ = harness.finish.send(());
    wait_for(
        || prompts_contain(&prompts, "queued while limited"),
        "the queued row to follow the continuation",
    )
    .await;
    assert_eq!(
        sent_prompts(&prompts),
        vec!["do the work", CONTINUE, "queued while limited"],
        "order preserved: the continuation first, the queued row behind it"
    );
    core.shutdown().await;
}

/// **Cancellation by archiving:** putting the Limited chat away cancels its
/// pending continuation — archived work must not resume on its own
/// overnight.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn archiving_the_chat_cancels_the_pending_continuation() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500))]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;

    client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "setChatArchived", "chatId": CHAT, "archived": true }),
        )
        .await
        .expect("setChatArchived");
    wait_for(
        || scheduled_fire_at(&core).is_none(),
        "archiving to cancel the continuation",
    )
    .await;

    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert!(!prompts_contain(&prompts, CONTINUE));
    assert_eq!(sent_prompts(&prompts), vec!["do the work"]);
    core.shutdown().await;
}

/// **Archive that landed from another device** — through the synced
/// workspace row, not this engine's RPC: the arm survives (nothing told
/// this engine), and the fire path's own guard must drop it at the reset
/// time instead of resuming archived work on its own.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_remote_archive_drops_the_continuation_at_fire_time() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500))]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;
    // The other device's archive reaches this engine only through the
    // workspace doc — the mutate RPC never ran here.
    core.workspace
        .set_chat_archived(CHAT, true)
        .expect("remote setChatArchived");
    assert!(
        scheduled_fire_at(&core).is_some(),
        "nothing cancelled the arm on this engine"
    );

    // The reset time comes; the firing consumes the continuation and drops
    // it at the archived-chat guard instead of dispatching.
    wait_for(
        || scheduled_fire_at(&core).is_none(),
        "the fired continuation to consume the arm",
    )
    .await;
    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert!(!prompts_contain(&prompts, CONTINUE), "archived work never resumes");
    assert_eq!(sent_prompts(&prompts), vec!["do the work"]);
    core.shutdown().await;
}

/// **Cancellation by deleting:** the chat is gone; its continuation is too.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn deleting_the_chat_cancels_the_pending_continuation() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500))]).await;

    run_into_limit(&core, &harness, &prompts).await;
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;

    client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "deleteChat", "chatId": CHAT }),
        )
        .await
        .expect("deleteChat");
    wait_for(
        || scheduled_fire_at(&core).is_none(),
        "deleting to cancel the continuation",
    )
    .await;

    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert!(!prompts_contain(&prompts, CONTINUE));
    core.shutdown().await;
}

/// **The setting, off (the default):** a chat becoming Limited arms nothing —
/// the engine settings read back off, no continuation rides the row, and
/// nothing fires when the reset time passes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_setting_defaults_to_off_and_arms_nothing() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(500))]).await;

    let settings = client
        .call(methods::GET_ENGINE_SETTINGS, serde_json::json!({}))
        .await
        .expect("GetEngineSettings");
    assert_eq!(settings["autoResumeLimited"], false, "off by default");

    run_into_limit(&core, &harness, &prompts).await;
    assert!(scheduled_fire_at(&core).is_none(), "nothing auto-armed");
    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert!(!prompts_contain(&prompts, CONTINUE));
    core.shutdown().await;
}

/// **The setting, on:** the engine arms the continuation automatically when
/// the chat becomes Limited — no click needed — and it fires at the reset
/// time exactly like the manual arm.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_setting_on_arms_the_continuation_automatically() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), None]).await;
    set_auto_resume(&client, true).await;

    run_into_limit(&core, &harness, &prompts).await;
    // The auto-arm targets the record's reset time — still ahead.
    let fire_at = scheduled_fire_at(&core).expect("auto-armed when the chat went Limited");
    let reported = core
        .sessions
        .session_status(CHAT)
        .and_then(|s| s.limit.and_then(|limit| limit.resets_at))
        .expect("the limit record");
    assert_eq!(fire_at, reported, "the auto-arm fires at the reset time");

    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the auto-armed continuation to fire at the reset time",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the continuation's turn to settle",
    )
    .await;
    assert_eq!(sent_prompts(&prompts), vec!["do the work", CONTINUE]);
    core.shutdown().await;
}

/// **Overlap, re-armed via a new limit record:** with the setting on, a
/// continuation that fires into a STILL-limited chat becomes Limited again
/// with the new reset time — and the fresh record re-arms it (the new reset
/// genuinely advances past the fired one). The work continues across two
/// limit windows without a click.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_continuation_that_hits_the_limit_again_rearms_via_the_new_record() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), Some(in_ms(6_000)), None]).await;
    set_auto_resume(&client, true).await;

    // Turn one ends limited at +400ms → auto-armed.
    run_into_limit(&core, &harness, &prompts).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the first limit to auto-arm",
    )
    .await;

    // The continuation fires into the same wall — still limited — with a NEW
    // (later) reset time.
    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the first continuation to fire",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions.session_status(CHAT).is_some_and(|s| {
                s.status == SessionStatus::Errored
                    && s.limit.is_some_and(|l| l.resets_at.is_some())
            })
        },
        "the continuation to hit the limit again",
    )
    .await;
    // The fresh record re-armed the continuation at the NEW reset time.
    wait_for(
        || {
            scheduled_fire_at(&core)
                .is_some_and(|at| at > chrono::Utc::now().timestamp_millis())
        },
        "the new limit record to re-arm the continuation",
    )
    .await;

    // The second continuation fires at the new reset time and completes.
    wait_for(
        || {
            prompts
                .lock()
                .unwrap()
                .iter()
                .filter(|p| p.as_str() == CONTINUE)
                .count()
                == 2
        },
        "the re-armed continuation to fire",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the second continuation's turn to settle",
    )
    .await;
    assert_eq!(sent_prompts(&prompts), vec!["do the work", CONTINUE, CONTINUE]);
    core.shutdown().await;
}

/// **Overlap, never in a loop:** the fired continuation is consumed — a
/// second limit record whose reset does NOT advance past the fired one (the
/// same wall, already past) re-arms nothing. One continuation, then the
/// chat sits Limited waiting for the user, not cycling on its own.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stale_reset_time_never_rearms_into_a_loop() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(2_500)), Some(in_ms(2_500))]).await;
    set_auto_resume(&client, true).await;

    run_into_limit(&core, &harness, &prompts).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the first limit to auto-arm",
    )
    .await;

    // The continuation fires; the run hits the limit again reporting the
    // SAME reset time — already in the past by now.
    wait_for(
        || prompts_contain(&prompts, CONTINUE),
        "the continuation to fire",
    )
    .await;
    let _ = harness.finish.send(());
    wait_for(
        || {
            core.sessions
                .session_status(CHAT)
                .is_some_and(|s| s.status == SessionStatus::Errored)
        },
        "the continuation to hit the limit again",
    )
    .await;
    // No re-arm: the record's reset does not advance past the fired one.
    assert!(
        scheduled_fire_at(&core).is_none(),
        "a stale reset time never re-arms the continuation"
    );

    // And it stays that way — no second continuation, ever.
    tokio::time::sleep(Duration::from_millis(3_000)).await;
    assert_eq!(sent_prompts(&prompts), vec!["do the work", CONTINUE]);
    // The chat is Limited again with the new record — waiting for the user.
    assert!(core
        .sessions
        .session_status(CHAT)
        .is_some_and(|s| s.limit.is_some()));
    core.shutdown().await;
}

/// Scheduling requires a Limited chat with a reset time still ahead: a
/// not-limited chat refuses with an actionable error (the honest offer is
/// the manual Resume), and a working arm cancels cleanly and idempotently.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn scheduling_refuses_chats_without_an_upcoming_reset_time() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(60_000))]).await;

    // Not Limited yet.
    let err = client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "scheduleResumeAtReset", "chatId": CHAT }),
        )
        .await
        .expect_err("not Limited");
    assert!(err.to_string().contains("not Limited"), "{err}");

    run_into_limit(&core, &harness, &prompts).await;
    // A reset far in the future arms fine — then cancels cleanly.
    schedule_resume(&client).await;
    wait_for(
        || scheduled_fire_at(&core).is_some(),
        "the continuation to arm",
    )
    .await;
    client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "cancelScheduledResume", "chatId": CHAT }),
        )
        .await
        .expect("cancelScheduledResume");
    assert!(scheduled_fire_at(&core).is_none());
    // Cancelling again is a no-op, not an error.
    client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "cancelScheduledResume", "chatId": CHAT }),
        )
        .await
        .expect("cancelScheduledResume is idempotent");
    core.shutdown().await;
}

/// An already-due reset refuses: there is nothing left to wait for, and the
/// caller is pointed back at the manual Resume.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn scheduling_refuses_an_already_due_reset() {
    let (core, harness, prompts, client) = setup(vec![Some(in_ms(-1))]).await;
    run_into_limit(&core, &harness, &prompts).await;
    let err = client
        .call(
            methods::MUTATE,
            serde_json::json!({ "op": "scheduleResumeAtReset", "chatId": CHAT }),
        )
        .await
        .expect_err("the reset already passed");
    assert!(err.to_string().contains("already reset"), "{err}");
    core.shutdown().await;
}
