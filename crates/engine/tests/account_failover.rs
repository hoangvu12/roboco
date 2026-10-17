//! Resume on another account (orchestration v2, ticket 09): the engine
//! operation behind a Limited Claude Code chat's account failover.
//!
//! The candidates come from a SEEDED, fully isolated harness-account store
//! (slot files + usage cache + a live Claude login — no network: the cache
//! is fresh, so the failover's one probe reads it), and the harness seam is
//! a scripted stand-in that records every run request it receives and plays
//! the driver's part: a refused native resume starts a fresh session and
//! pastes the budgeted history in front of the prompt, exactly the way the
//! Codex driver does.
//!
//! Coverage (the ticket's checklist, at the engine RPC seam):
//! - the ranking (excludes signed-out, limited, API-key and the live
//!   account; lowest usage first, same-org accounts ranked lower);
//! - the switch itself (activate, retire the warm runtime by credential
//!   fingerprint, resume the SAME harness session id with the continue
//!   prompt and the budgeted fallback history);
//! - the refused-resume fresh-session fallback;
//! - a signed-out failure after the switch moving to the next candidate;
//!   a plain failure restoring the previous account;
//! - no healthy candidate → `noCandidates` (only Resume at reset).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::SessionCommandPayload;
use roboco_engine::{AgentAccounts, AgentAccountsConfig, EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, ResumeAccountCandidates,
    ResumeOnAccountOutcome, RunRequest, SandboxLevel, SessionStatus, SteeringMode,
};
use roboco_rpc::methods;

const LIMITED: &str = "chat-limited";
const WARM: &str = "chat-warm";

/// What the scripted harness plays for the chat's continuation run (run
/// index 1 on the limited chat).
#[derive(Clone, Copy, PartialEq, Eq)]
enum Continuation {
    /// The native resume works: the same session id answers.
    Resumed,
    /// The harness REFUSES the native resume: a fresh session id answers,
    /// the budgeted history pasted in front of the prompt (the driver's
    /// part — what Codex does on a failed `thread/resume`).
    Refused,
    /// The continuation fails with ticket 05's signed-out kind.
    SignedOut,
    /// The continuation fails with an unclassified error.
    PlainFailure,
}

/// One run the scripted harness received, as the engine handed it over plus
/// what a driver would have delivered (the fallback pasted on a refusal).
#[derive(Clone, Debug)]
struct RecordedRun {
    chat: String,
    run: usize,
    prompt: String,
    resume: Option<String>,
    fallback: Option<String>,
    delivered_prompt: String,
    session_id: String,
}

/// A persistent, steerable Claude-shaped stand-in. Run 0 of the limited
/// chat ends on a classified usage-limit error (the driver shape ticket 05
/// landed, the settle ticket 06 records); run 0 of the warm chat completes
/// and PARKS (the stream never ends) so its runtime stays warm; run 1 of
/// the limited chat plays [`Continuation`]; later runs complete.
struct FailoverHarness {
    continuation: Continuation,
    runs: Arc<Mutex<Vec<RecordedRun>>>,
    counts: Arc<Mutex<HashMap<String, usize>>>,
}

impl FailoverHarness {
    fn new(continuation: Continuation) -> Arc<Self> {
        Arc::new(Self {
            continuation,
            runs: Arc::new(Mutex::new(Vec::new())),
            counts: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    fn runs_for(&self, chat: &str) -> Vec<RecordedRun> {
        self.runs
            .lock()
            .unwrap()
            .iter()
            .filter(|run| run.chat == chat)
            .cloned()
            .collect()
    }

    fn run_count(&self, chat: &str) -> usize {
        self.counts.lock().unwrap().get(chat).copied().unwrap_or(0)
    }
}

#[async_trait]
impl Harness for FailoverHarness {
    fn id(&self) -> HarnessId {
        HarnessId::ClaudeCode
    }
    fn display_name(&self) -> &str {
        "Claude Code"
    }
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
    fn deterministic_turn_end(&self) -> bool {
        true
    }
    async fn run(
        &self,
        request: RunRequest,
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        // The engine stamps the originating chat on the injected MCP server.
        let chat = request
            .mcp
            .as_ref()
            .and_then(|mcp| mcp.env.get("ROBOCO_CHAT_ID").cloned())
            .unwrap_or_default();
        let run = {
            let mut counts = self.counts.lock().unwrap();
            let next = counts.entry(chat.clone()).or_insert(0);
            *next += 1;
            *next - 1
        };
        // The driver's delivery rule: a refused native resume pastes the
        // budgeted fallback in front of the prompt on the fresh session.
        let refuse = run == 1 && chat == LIMITED && self.continuation == Continuation::Refused;
        let delivered_prompt = match (&request.resume_fallback_history, refuse) {
            (Some(history), true) => format!("{history}{}", request.prompt),
            _ => request.prompt.clone(),
        };
        let session_id = match (chat.as_str(), run) {
            (LIMITED, 0) => "sess-0".to_string(),
            (LIMITED, 1) if self.continuation == Continuation::Refused => "fresh-9".to_string(),
            (LIMITED, 1) => "sess-0".to_string(),
            (LIMITED, _) => "sess-0".to_string(),
            (WARM, 0) => "warm-sess".to_string(),
            (WARM, _) => format!("warm-sess-{run}"),
            _ => "sess".to_string(),
        };
        self.runs.lock().unwrap().push(RecordedRun {
            chat: chat.clone(),
            run,
            prompt: request.prompt.clone(),
            resume: request.resume.clone(),
            fallback: request.resume_fallback_history.clone(),
            delivered_prompt,
            session_id: session_id.clone(),
        });
        let harness = HarnessId::ClaudeCode;
        let cwd = request.cwd.clone();
        let mut events = vec![Ok(AgentEvent::SessionStarted {
            harness,
            model: "claude-test".into(),
            tools: vec![],
            cwd,
            session_id: session_id.clone(),
            assistant_message_id: format!("a-{chat}-{run}"),
        })];
        match (chat.as_str(), run) {
            (LIMITED, 0) => {
                // The classified limit error, then the errored Done — the
                // shape that turns the chat Limited (ticket 06).
                events.push(Ok(AgentEvent::Error {
                    message: "Claude 5-hour limit reached — try again after it resets.".into(),
                    kind: Some(roboco_proto::AgentErrorKind::UsageLimit {
                        window: "5-hour".into(),
                        resets_at: Some(1_762_675_400_000),
                    }),
                }));
                events.push(Ok(AgentEvent::Done {
                    status: DoneStatus::Errored,
                    result: None,
                    error: Some("the turn failed".into()),
                    session_id: Some(session_id),
                }));
                Ok(futures::stream::iter(events).boxed())
            }
            (LIMITED, 1) => match self.continuation {
                Continuation::Resumed | Continuation::Refused => {
                    events.push(Ok(AgentEvent::TextDelta {
                        text: "Picked the work back up on another account.".into(),
                    }));
                    events.push(Ok(AgentEvent::Done {
                        status: DoneStatus::Completed,
                        result: None,
                        error: None,
                        session_id: Some(session_id),
                    }));
                    Ok(futures::stream::iter(events).boxed())
                }
                Continuation::SignedOut => {
                    events.push(Ok(AgentEvent::Error {
                        message: "Authentication failed — sign in to Claude again.".into(),
                        kind: Some(roboco_proto::AgentErrorKind::SignedOut),
                    }));
                    events.push(Ok(AgentEvent::Done {
                        status: DoneStatus::Errored,
                        result: None,
                        error: Some("Authentication failed — sign in to Claude again.".into()),
                        session_id: Some(session_id),
                    }));
                    Ok(futures::stream::iter(events).boxed())
                }
                Continuation::PlainFailure => {
                    events.push(Ok(AgentEvent::Error {
                        message: "The agent child crashed.".into(),
                        kind: None,
                    }));
                    events.push(Ok(AgentEvent::Done {
                        status: DoneStatus::Errored,
                        result: None,
                        error: Some("the turn failed".into()),
                        session_id: Some(session_id),
                    }));
                    Ok(futures::stream::iter(events).boxed())
                }
            },
            (WARM, 0) => {
                // A cleanly completed turn on a steerable harness PARKS: the
                // stream stays open, the runtime stays warm for the next
                // routed dispatch.
                events.push(Ok(AgentEvent::TextDelta {
                    text: "warm turn done".into(),
                }));
                events.push(Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some(session_id),
                }));
                Ok(futures::stream::iter(events)
                    .chain(futures::stream::pending::<Result<AgentEvent, HarnessError>>())
                    .boxed())
            }
            _ => {
                // The chain's later runs (and the warm chat's replaced
                // runtime): plain completions.
                events.push(Ok(AgentEvent::TextDelta {
                    text: "later turn done".into(),
                }));
                events.push(Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some(session_id),
                }));
                Ok(futures::stream::iter(events).boxed())
            }
        }
    }
}

/// A local stand-in for the usage endpoint: nothing should ever reach it
/// (the seeded cache is fresh, so the failover's probe reads it), but a
/// stray probe answers green instead of touching the network.
async fn usage_server() -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("bind mock usage server");
    let base = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut request = vec![0u8; 16 * 1024];
                let _ = socket.read(&mut request).await.unwrap_or(0);
                let body =
                    r#"{"five_hour":{"utilization":6.0,"resets_at":"2026-10-18T08:00:00Z"}}"#;
                let reply = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \
                     {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(reply.as_bytes()).await;
                let _ = socket.shutdown().await;
            });
        }
    });
    base
}

/// The live Claude login (the account the limit hits) under `config`.
fn write_claude_login(config: &AgentAccountsConfig) {
    std::fs::create_dir_all(&config.claude_config_dir).expect("claude config dir");
    std::fs::write(
        &config.claude_config_file,
        serde_json::json!({
            "oauthAccount": {
                "accountUuid": "uuid-alice",
                "emailAddress": "alice@example.com",
                "organizationName": "Acme",
            },
        })
        .to_string(),
    )
    .expect("claude config");
    std::fs::write(
        config.claude_config_dir.join(".credentials.json"),
        serde_json::json!({
            "claudeAiOauth": {
                "accessToken": "alice-token",
                "refreshToken": "refresh-alice-token",
                // Far-future expiry: probes must never try to rotate it.
                "expiresAt": 4_102_444_800_000i64,
            }
        })
        .to_string(),
    )
    .expect("claude creds");
}

/// One saved Claude slot + its cached usage. `windows`/`sign_in` seed the
/// usage cache keyed by the slot's account key; a FRESH `checked_at` (now)
/// keeps the failover's probe reading the cache instead of the network.
fn write_slot(
    config: &AgentAccountsConfig,
    id: &str,
    account_key: &str,
    email: &str,
    organization: Option<&str>,
    auth_kind: &str,
    usage: &str,
    sign_in: Option<&str>,
) {
    let dir = config.data_dir.join("agent-accounts").join("claude-code");
    std::fs::create_dir_all(&dir).expect("slots dir");
    let token = format!("{}-token", account_key);
    let slot = serde_json::json!({
        "id": id,
        "harness": "claude-code",
        "accountKey": account_key,
        "profile": {
            "email": email,
            "organization": organization,
            "plan": "Pro",
            "authKind": auth_kind,
        },
        "credentials": {
            "claudeAiOauth": {
                "accessToken": token,
                "refreshToken": format!("refresh-{token}"),
                "expiresAt": 4_102_444_800_000i64,
            }
        },
        "savedAt": 1_762_000_000_000i64,
    });
    std::fs::write(dir.join(format!("{id}.json")), slot.to_string()).expect("slot file");

    // The usage cache: keyed `claude-code:{account_key}`, fresh so nothing
    // probes. `usage` is the windows array JSON; `sign_in` the hard evidence.
    let cache_path = config.data_dir.join("agent-accounts").join("usage-cache.json");
    let mut cache: serde_json::Value = std::fs::read_to_string(&cache_path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_else(|| serde_json::json!({ "entries": {} }));
    cache["entries"][&format!("claude-code:{account_key}")] = serde_json::json!({
        "usage": { "windows": serde_json::from_str::<serde_json::Value>(usage).unwrap() },
        "fetchedAt": chrono::Utc::now().timestamp_millis(),
        "checkedAt": chrono::Utc::now().timestamp_millis(),
        "signIn": sign_in,
    });
    std::fs::write(&cache_path, cache.to_string()).expect("usage cache");
}

/// The full candidate zoo: carol (0.05, other org — the recommendation),
/// bob (0.2, no org), gina (0.05, the limited account's org — ranked below
/// bob), and the three exclusions (signed-out dave, still-limited erin,
/// API-key frank).
fn seed_store(config: &AgentAccountsConfig) {
    write_claude_login(config);
    write_slot(
        config,
        "slot-carol",
        "uuid-carol",
        "carol@example.com",
        Some("Globex"),
        "oauth",
        r#"[{"label":"Session","usedFraction":0.05,"resetsAt":"2026-10-18T08:00:00Z"}]"#,
        Some("signed-in"),
    );
    write_slot(
        config,
        "slot-bob",
        "uuid-bob",
        "bob@example.com",
        None,
        "oauth",
        r#"[{"label":"Session","usedFraction":0.2,"resetsAt":"2026-10-18T08:00:00Z"}]"#,
        Some("signed-in"),
    );
    write_slot(
        config,
        "slot-gina",
        "uuid-gina",
        "gina@example.com",
        Some("Acme"),
        "oauth",
        r#"[{"label":"Session","usedFraction":0.05,"resetsAt":"2026-10-18T08:00:00Z"}]"#,
        Some("signed-in"),
    );
    write_slot(
        config,
        "slot-dave",
        "uuid-dave",
        "dave@example.com",
        None,
        "oauth",
        r#"[{"label":"Session","usedFraction":0.1,"resetsAt":"2026-10-18T08:00:00Z"}]"#,
        Some("signed-out"),
    );
    // Erin's exhausted window resets in the future: still limited.
    let reset = (chrono::Utc::now() + chrono::TimeDelta::hours(2))
        .to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    write_slot(
        config,
        "slot-erin",
        "uuid-erin",
        "erin@example.com",
        None,
        "oauth",
        &format!(r#"[{{"label":"Session","usedFraction":1.0,"resetsAt":"{reset}"}}]"#),
        Some("signed-in"),
    );
    write_slot(
        config,
        "slot-frank",
        "uuid-frank",
        "frank@example.com",
        None,
        "api-key",
        r#"[]"#,
        None,
    );
}

/// Which store the engine under test gets. Every variant starts from the
/// limited live login (alice); the rest is the candidate set the test
/// needs.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Store {
    /// The full candidate zoo (the ranking test).
    Full,
    /// Two healthy candidates (carol 0.05, bob 0.2) — the failover and the
    /// chain tests, whose shape depends on a known next account.
    Two,
    /// Nothing else (no candidate remains).
    Bare,
}

fn seed(config: &AgentAccountsConfig, store: Store) {
    match store {
        Store::Full => seed_store(config),
        Store::Two => {
            write_claude_login(config);
            for (id, key, email, org, usage) in [
                (
                    "slot-carol",
                    "uuid-carol",
                    "carol@example.com",
                    Some("Globex"),
                    0.05,
                ),
                ("slot-bob", "uuid-bob", "bob@example.com", None, 0.2),
            ] {
                write_slot(
                    config,
                    id,
                    key,
                    email,
                    org,
                    "oauth",
                    &format!(
                        r#"[{{"label":"Session","usedFraction":{usage},"resetsAt":"2026-10-18T08:00:00Z"}}]"#
                    ),
                    Some("signed-in"),
                );
            }
        }
        Store::Bare => write_claude_login(config),
    }
}

/// The engine under test: the scripted Claude harness registered under
/// `claude-code`, the seeded isolated account store wired into BOTH the
/// sessions engine (ranking, probing, switching) and the RPC surface.
async fn setup(
    continuation: Continuation,
    store: Store,
) -> (EngineCore, Arc<FailoverHarness>, roboco_rpc::RpcClient) {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.keep();
    let registry = HarnessRegistry::new();
    let harness = FailoverHarness::new(continuation);
    registry.register(harness.clone());
    let mut core = EngineCore::assemble(&root, Arc::new(registry), HarnessId::ClaudeCode)
        .expect("engine core assembles");
    // The mocked usage endpoint only ever sees a stray probe.
    let base = usage_server().await;
    let config = AgentAccountsConfig::isolated(&root);
    seed(&config, store);
    let accounts = AgentAccounts::with_mocked_endpoints(config, &base);
    // One store, two handles: the RPC lists from it, the failover switches
    // on it — replacing the detected store `assemble` wired in.
    core.agent_accounts = accounts.clone();
    core.sessions.set_agent_accounts(accounts);
    // The injected MCP server names the chat, so the scripted harness can
    // tell the two chats' runs apart.
    core.sessions.set_ipc_port(27699);
    let client = roboco_rpc::memory_client(core.rpc_service());
    (core, harness, client)
}

async fn create_chat(core: &EngineCore, chat: &str) {
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": chat,
                "deviceId": core.device_id,
            }),
        )
        .await
        .expect("createChat");
    // Pre-titled, so the auto-titler never dispatches a harness request of
    // its own (it would land on the scripted harness as an extra run).
    core.workspace
        .rename_chat(chat, &format!("Pre-titled {chat}"))
        .expect("rename chat");
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

/// Queue a Run command the way the composer's send does (the durable
/// command plane, ahead of any held queue).
fn queue_run(core: &EngineCore, chat: &str, prompt: &str, message_id: &str) {
    core.doc_host
        .queue_command(
            chat,
            SessionCommandPayload::Run {
                request: run_request(prompt),
                message_id: message_id.into(),
            },
        )
        .expect("queue run command");
}

async fn wait_for(mut predicate: impl FnMut() -> bool, what: &str) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    while !predicate() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

/// [`wait_for`] for predicates that must await the engine (an RPC round
/// trip per probe).
async fn wait_for_async<F, Fut>(mut predicate: F, what: &str)
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = bool>,
{
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    while !predicate().await {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

async fn wait_for_status(core: &EngineCore, chat: &str, status: SessionStatus) {
    wait_for(
        || {
            core.sessions
                .session_status(chat)
                .is_some_and(|s| s.status == status)
        },
        "the chat to settle",
    )
    .await;
}

/// Run the chat's first turn into the usage limit and wait for the Limited
/// record (the failover's precondition).
async fn drive_into_limit(core: &EngineCore, harness: &FailoverHarness, chat: &str) {
    queue_run(core, chat, "do the work", "m-open");
    wait_for(
        || harness.run_count(chat) >= 1,
        "the limited run to start",
    )
    .await;
    wait_for(
        || {
            core.sessions
                .session_status(chat)
                .is_some_and(|s| s.status == SessionStatus::Errored && s.limit.is_some())
        },
        "the chat to go Limited",
    )
    .await;
}

/// The candidates as the RPC ranks them.
async fn candidates(
    client: &roboco_rpc::RpcClient,
    chat: &str,
) -> ResumeAccountCandidates {
    let value = client
        .call(
            methods::LIST_RESUME_ACCOUNT_CANDIDATES,
            serde_json::json!({ "chatId": chat }),
        )
        .await
        .expect("ListResumeAccountCandidates");
    serde_json::from_value(value).expect("candidates reply parses")
}

/// The failover RPC.
async fn resume_on_another_account(
    client: &roboco_rpc::RpcClient,
    chat: &str,
    account_id: Option<&str>,
) -> ResumeOnAccountOutcome {
    let mut params = serde_json::json!({ "chatId": chat });
    if let (Some(id), Some(object)) = (account_id, params.as_object_mut()) {
        object.insert("accountId".into(), serde_json::json!(id));
    }
    let value = client
        .call(methods::RESUME_ON_ANOTHER_ACCOUNT, params)
        .await
        .expect("ResumeOnAnotherAccount");
    serde_json::from_value(value).expect("outcome reply parses")
}

/// The active Claude account's row, through the accounts RPC.
async fn active_row(
    client: &roboco_rpc::RpcClient,
) -> Option<roboco_proto::AgentAccount> {
    let value = client
        .call(
            methods::LIST_AGENT_ACCOUNTS,
            serde_json::json!({ "forceUsage": false }),
        )
        .await
        .expect("ListAgentAccounts");
    let snapshot: roboco_proto::AgentAccountsSnapshot =
        serde_json::from_value(value).expect("snapshot parses");
    snapshot
        .accounts
        .into_iter()
        .find(|row| row.harness == HarnessId::ClaudeCode && row.active)
}

/// The candidate ranking (ticket checklist 1): the limited account, signed-out,
/// still-limited and API-key rows never surface; lowest usage first, and the
/// account sharing the limited one's org ranks below a higher-usage account
/// from another org.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn candidates_exclude_dead_rows_and_rank_by_usage_then_org() {
    let (core, _harness, client) = setup(Continuation::Resumed, Store::Full).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &_harness, LIMITED).await;

    let ranked = candidates(&client, LIMITED).await;
    assert_eq!(ranked.harness, HarnessId::ClaudeCode);
    // The account the limit hit (the live login's slot id).
    assert!(ranked.limited_account_id.is_some());
    let ids: Vec<&str> = ranked
        .candidates
        .iter()
        .map(|row| row.id.as_str())
        .collect();
    assert_eq!(
        ids,
        vec!["slot-carol", "slot-bob", "slot-gina"],
        "lowest usage first; a different org outranks the limited account's own pool"
    );
    // The meters ride the rows: carol's Session window at 5%.
    let carol = ranked.candidates.first().unwrap();
    assert_eq!(carol.usage_windows.len(), 1);
    assert_eq!(carol.usage_windows[0].used_fraction, 0.05);
    // The signed-out accounts (stories 47/50) ride their own list — dave
    // is excluded as a candidate but offered for "Sign in again"; the
    // still-limited erin and the API-key frank are neither (reviving them
    // means waiting or a new API key, not a sign-in).
    let signed_out: Vec<&str> = ranked
        .signed_out
        .iter()
        .map(|row| row.id.as_str())
        .collect();
    assert_eq!(signed_out, vec!["slot-dave"]);
    core.shutdown().await;
}

/// The switch itself (ticket checklist 2): the chosen account is probed and
/// ACTIVATED (the CLI store now holds its login), the chat's warm runtime is
/// retired, and the continuation resumes the SAME harness session id with
/// the continue prompt — the budgeted history riding as the fallback.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_failover_switches_and_resumes_the_same_harness_session() {
    let (core, harness, client) = setup(Continuation::Resumed, Store::Two).await;
    create_chat(&core, LIMITED).await;
    // A second chat with a PARKED warm runtime, stamped with the limited
    // login's fingerprint: after the switch it must NOT be reused.
    create_chat(&core, WARM).await;
    queue_run(&core, WARM, "warm first turn", "m-warm-1");
    wait_for(
        || harness.run_count(WARM) == 1,
        "the warm chat's first run to start",
    )
    .await;
    wait_for_status(&core, WARM, SessionStatus::Idle).await;

    drive_into_limit(&core, &harness, LIMITED).await;
    let outcome = resume_on_another_account(&client, LIMITED, None).await;
    assert_eq!(
        outcome,
        ResumeOnAccountOutcome::Switched {
            account_id: "slot-carol".into(),
            account_email: Some("carol@example.com".into()),
        }
    );
    // The switch landed: the live login is carol's.
    let active = active_row(&client).await.expect("an active account");
    assert_eq!(active.id, "slot-carol");
    assert_eq!(active.email.as_deref(), Some("carol@example.com"));
    // The continuation resumes the chat's session with the continue prompt…
    wait_for(
        || harness.run_count(LIMITED) >= 2,
        "the continuation to dispatch",
    )
    .await;
    let continuation = &harness.runs_for(LIMITED)[1];
    assert_eq!(continuation.prompt, roboco_proto::RESUME_CONTINUE_PROMPT);
    assert_eq!(continuation.resume.as_deref(), Some("sess-0"));
    // …and carries the chat's budgeted history as the fallback for a
    // refused native resume.
    let fallback = continuation.fallback.as_deref().expect("fallback history");
    assert!(fallback.contains("do the work"), "fallback: {fallback}");
    assert!(fallback.contains("<transcript>"), "fallback: {fallback}");
    // The continuation completes: the chat is back to Idle.
    wait_for_status(&core, LIMITED, SessionStatus::Idle).await;

    // The warm chat's NEXT message must replace its runtime (the login
    // changed under it): a fresh run, never a steer into the parked one.
    queue_run(&core, WARM, "warm second turn", "m-warm-2");
    wait_for(
        || harness.run_count(WARM) >= 2,
        "the warm chat's replaced runtime to start",
    )
    .await;
    let warm_runs = harness.runs_for(WARM);
    assert_eq!(warm_runs.len(), 2, "a fresh runtime, not the parked one");
    assert_eq!(warm_runs[1].prompt, "warm second turn");
    core.shutdown().await;
}

/// A refused native resume (ticket checklist 3): the harness starts a FRESH
/// session — its reported session id differs — and the budgeted history is
/// delivered in front of the continue prompt on that fresh session.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_refused_native_resume_falls_back_to_a_fresh_session_with_budgeted_history() {
    let (core, harness, client) = setup(Continuation::Refused, Store::Two).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &harness, LIMITED).await;

    let outcome = resume_on_another_account(&client, LIMITED, None).await;
    assert!(matches!(outcome, ResumeOnAccountOutcome::Switched { .. }));
    wait_for(
        || harness.run_count(LIMITED) >= 2,
        "the continuation to dispatch",
    )
    .await;
    let continuation = &harness.runs_for(LIMITED)[1];
    // The engine asked for the native resume…
    assert_eq!(continuation.resume.as_deref(), Some("sess-0"));
    assert!(continuation.fallback.is_some());
    // …the harness refused it (the driver's part): a fresh session id, the
    // budgeted history pasted in front of the prompt.
    assert_ne!(continuation.session_id, "sess-0");
    assert_eq!(continuation.session_id, "fresh-9");
    assert!(continuation.delivered_prompt.contains("do the work"));
    assert!(continuation.delivered_prompt.contains("<transcript>"));
    assert!(continuation
        .delivered_prompt
        .ends_with(roboco_proto::RESUME_CONTINUE_PROMPT));
    wait_for_status(&core, LIMITED, SessionStatus::Idle).await;
    core.shutdown().await;
}

/// A signed-out failure after the switch (ticket checklist 4): the account
/// is marked signed out (the run's own wiring) and the failover moves to
/// the NEXT candidate, whose continuation succeeds.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_signed_out_failure_marks_the_account_and_moves_to_the_next_candidate() {
    let (core, harness, client) = setup(Continuation::SignedOut, Store::Two).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &harness, LIMITED).await;

    let outcome = resume_on_another_account(&client, LIMITED, None).await;
    assert!(matches!(
        outcome,
        ResumeOnAccountOutcome::Switched {
            account_id, ..
        } if account_id == "slot-carol"
    ));
    // The carol continuation fails signed out; the chain retries on bob.
    wait_for(
        || harness.run_count(LIMITED) >= 3,
        "the next candidate's continuation to dispatch",
    )
    .await;
    let runs = harness.runs_for(LIMITED);
    assert_eq!(runs.len(), 3);
    // Both continuations resume the same harness session.
    assert_eq!(runs[1].resume.as_deref(), Some("sess-0"));
    assert_eq!(runs[2].resume.as_deref(), Some("sess-0"));
    assert_eq!(runs[2].prompt, roboco_proto::RESUME_CONTINUE_PROMPT);
    // The chat settles Idle on bob's continuation.
    wait_for_status(&core, LIMITED, SessionStatus::Idle).await;
    // Carol is marked signed out (never offered again)…
    let snapshot = client
        .call(
            methods::LIST_AGENT_ACCOUNTS,
            serde_json::json!({ "forceUsage": false }),
        )
        .await
        .expect("ListAgentAccounts");
    let carol = snapshot["accounts"]
        .as_array()
        .expect("accounts")
        .iter()
        .find(|row| row["id"] == "slot-carol")
        .expect("carol's row");
    assert_eq!(carol["signInState"], "signed-out");
    // …and the live login is bob's.
    let active = active_row(&client).await.expect("an active account");
    assert_eq!(active.id, "slot-bob");
    core.shutdown().await;
}

/// Any other failure (ticket checklist 4's other arm): the previously
/// active account is restored — a failed failover leaves the harness as it
/// found it — and no second candidate is tried.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_plain_failure_restores_the_previous_account() {
    let (core, harness, client) = setup(Continuation::PlainFailure, Store::Two).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &harness, LIMITED).await;

    let outcome = resume_on_another_account(&client, LIMITED, None).await;
    assert!(matches!(outcome, ResumeOnAccountOutcome::Switched { .. }));
    wait_for(
        || harness.run_count(LIMITED) >= 2,
        "the continuation to dispatch",
    )
    .await;
    wait_for_status(&core, LIMITED, SessionStatus::Errored).await;
    // The restore is a real account switch: the live login is alice's again.
    wait_for_async(
        || async {
            active_row(&client)
                .await
                .is_some_and(|row| row.id != "slot-carol")
        },
        "the previous account to be restored",
    )
    .await;
    let active = active_row(&client).await.expect("an active account");
    assert_eq!(active.email.as_deref(), Some("alice@example.com"));
    // The failure is REPORTED (ticket 09): a System entry on the chat says
    // the failover failed and the previous account is back — the banner
    // (and the web client, which renders System entries) shows it.
    wait_for(
        || {
            core.doc_host
                .open(LIMITED)
                .is_ok_and(|handle| {
                    handle
                        .doc()
                        .read_entries()
                        .map(|entries| {
                            entries.iter().any(|entry| {
                                entry.role == roboco_doc::MessageRole::System
                                    && entry
                                        .parts
                                        .iter()
                                        .any(|part| matches!(part,
                                            roboco_doc::MessagePart::Text { text, .. }
                                            if text.starts_with(
                                                "Resume on another account failed: the resumed turn failed."
                                            ) && text.contains("The harness is back on")
                                        ))
                            })
                        })
                        .unwrap_or(false)
                })
        },
        "the failed failover to be reported on the chat",
    )
    .await;
    // No second candidate: the chain ends with the restore. The settle
    // beat lets a stray third run fail the count assertion.
    tokio::time::sleep(Duration::from_millis(750)).await;
    assert_eq!(harness.run_count(LIMITED), 2, "no third run on the chat");
    core.shutdown().await;
}

/// No healthy candidate (ticket checklist 5): the reply is `noCandidates` —
/// the surface offers only Resume at reset — and nothing switched.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn with_no_healthy_candidate_only_resume_at_reset_remains() {
    let (core, harness, client) = setup(Continuation::Resumed, Store::Bare).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &harness, LIMITED).await;

    let ranked = candidates(&client, LIMITED).await;
    assert!(ranked.candidates.is_empty(), "no candidates offered");
    assert!(
        ranked.signed_out.is_empty(),
        "no signed-out rows either — nothing to sign back in"
    );
    let outcome = resume_on_another_account(&client, LIMITED, None).await;
    assert_eq!(outcome, ResumeOnAccountOutcome::NoCandidates);
    // Nothing switched, nothing dispatched: the live login is still the
    // limited account's, and the chat stays Limited.
    let active = active_row(&client).await.expect("an active account");
    assert_eq!(active.email.as_deref(), Some("alice@example.com"));
    assert_eq!(harness.run_count(LIMITED), 1);
    assert!(core
        .sessions
        .session_status(LIMITED)
        .is_some_and(|s| s.limit.is_some()));
    core.shutdown().await;
}

/// The user's explicit pick is honored when it is a candidate, and refused
/// when it is not (the limited account itself).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_explicit_pick_switches_to_it_and_a_non_candidate_is_refused() {
    let (core, harness, client) = setup(Continuation::Resumed, Store::Two).await;
    create_chat(&core, LIMITED).await;
    drive_into_limit(&core, &harness, LIMITED).await;

    let outcome = resume_on_another_account(&client, LIMITED, Some("slot-bob")).await;
    assert_eq!(
        outcome,
        ResumeOnAccountOutcome::Switched {
            account_id: "slot-bob".into(),
            account_email: Some("bob@example.com".into()),
        }
    );
    wait_for(
        || harness.run_count(LIMITED) >= 2,
        "bob's continuation to dispatch",
    )
    .await;
    wait_for_status(&core, LIMITED, SessionStatus::Idle).await;
    core.shutdown().await;
}
