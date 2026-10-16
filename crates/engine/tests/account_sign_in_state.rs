//! Harness-account sign-in state driven from RUNS (orchestration v2,
//! ticket 08).
//!
//! Ticket 05 gave `AgentEvent::Error` a structured kind; this is the wiring
//! that consumes it: a run whose error is classified signed-out marks the
//! harness's LIVE login's account signed out (visible through
//! `ListAgentAccounts`, cached meters and all), and a later successful
//! usage probe flips it back to signed-in. The scripted harness carries the
//! same classification ticket 05's Claude driver attaches to
//! `authentication_failed`, so the engine-side behavior is covered without
//! a real CLI.

use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_engine::{AgentAccounts, AgentAccountsConfig, EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentErrorKind, AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest,
    SandboxLevel, SessionStatus, SteeringMode,
};
use roboco_rpc::methods;

const CHAT: &str = "chat-signed-out";

/// A Claude-shaped harness whose scripted run fails with ticket 05's
/// structured signed-out kind. `nested` wraps the error in a subagent event
/// instead — a subagent runs on the same login, so its rejection is equally
/// hard evidence the login is dead.
struct SignedOutHarness {
    nested: bool,
}

#[async_trait]
impl Harness for SignedOutHarness {
    fn id(&self) -> HarnessId {
        HarnessId::ClaudeCode
    }
    fn display_name(&self) -> &str {
        "Claude Code"
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
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let signed_out = AgentEvent::Error {
            message: "Authentication failed — sign in to Claude again.".into(),
            kind: Some(AgentErrorKind::SignedOut),
        };
        let error = if self.nested {
            AgentEvent::Subagent {
                parent_tool_use_id: "sub-signout".into(),
                event: Box::new(signed_out),
            }
        } else {
            signed_out
        };
        let events = vec![
            Ok(AgentEvent::SessionStarted {
                harness: HarnessId::ClaudeCode,
                model: "claude-test".into(),
                tools: vec![],
                cwd: request.cwd.clone(),
                session_id: "sess-signout".into(),
                assistant_message_id: "a-signout".into(),
            }),
            Ok(error),
            Ok(AgentEvent::Done {
                status: DoneStatus::Errored,
                result: None,
                error: Some("Authentication failed — sign in to Claude again.".into()),
                session_id: Some("sess-signout".into()),
            }),
        ];
        Ok(futures::stream::iter(events).boxed())
    }
}

/// A local stand-in for Anthropic's usage endpoint: every probe answered
/// with a green usage body, so a forced list re-establishes a marked
/// account as signed-in.
async fn usage_server() -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("bind mock usage server");
    let base = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                // Drain the request (a headers-only probe GET).
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

/// A Claude Code login (config + credentials) under the isolated `config`.
fn write_claude_login(config: &AgentAccountsConfig, uuid: &str, email: &str, token: &str) {
    std::fs::create_dir_all(&config.claude_config_dir).expect("claude config dir");
    std::fs::write(
        &config.claude_config_file,
        serde_json::json!({
            "oauthAccount": { "accountUuid": uuid, "emailAddress": email },
        })
        .to_string(),
    )
    .expect("claude config");
    std::fs::write(
        config.claude_config_dir.join(".credentials.json"),
        serde_json::json!({
            "claudeAiOauth": {
                "accessToken": token,
                "refreshToken": format!("refresh-{token}"),
                // Far-future expiry: probes must never try to rotate it.
                "expiresAt": 4_102_444_800_000i64,
            }
        })
        .to_string(),
    )
    .expect("claude creds");
}

fn run_request(prompt: &str) -> RunRequest {
    RunRequest {
        mcp: None,
        prompt: prompt.into(),
        harness: Some(HarnessId::ClaudeCode),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        resume: None,
        attachments: vec![],
        worktree: None,
        provider_fork: None,
        resume_fallback_history: None,
    }
}

/// The engine under test: a Claude login live in an isolated account store
/// (whose usage endpoint is the local mock), and the signed-out harness
/// registered under `claude-code`. The SAME account store is wired into the
/// sessions engine (the run-error marking) and the RPC surface
/// (`ListAgentAccounts`).
async fn setup(nested: bool) -> (EngineCore, roboco_rpc::RpcClient) {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.keep();
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(SignedOutHarness { nested }));
    let core =
        EngineCore::assemble(&root, Arc::new(registry), HarnessId::ClaudeCode).expect("engine");

    let config = AgentAccountsConfig::isolated(&root);
    write_claude_login(&config, "uuid-alice", "alice@example.com", "alice-token");
    let base = usage_server().await;
    let accounts = AgentAccounts::with_mocked_endpoints(config, &base);
    // One store, two handles: the RPC lists from it, the marking writes to
    // it — replacing the detected store `assemble` wired in.
    core.agent_accounts = accounts.clone();
    core.sessions.set_agent_accounts(accounts);
    let client = roboco_rpc::memory_client(core.rpc_service());
    (core, client)
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

/// The signed-in state of the Claude row, as `ListAgentAccounts` reports it.
async fn claude_sign_in_state(client: &roboco_rpc::RpcClient, force: bool) -> String {
    let snapshot = client
        .call(
            methods::LIST_AGENT_ACCOUNTS,
            serde_json::json!({ "forceUsage": force }),
        )
        .await
        .expect("ListAgentAccounts");
    snapshot["accounts"]
        .as_array()
        .expect("accounts")
        .iter()
        .find(|account| account["harness"] == "claude-code")
        .map(|account| account["signInState"].as_str().expect("signInState").into())
        .expect("a claude-code account row")
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

/// A run error of kind signed-out marks the harness's active account signed
/// out — the row says so through `ListAgentAccounts` even though its cached
/// meters never left — and a later successful usage probe marks it signed
/// in again.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_signed_out_run_error_marks_the_active_account_until_a_probe_clears_it() {
    let (core, client) = setup(false).await;
    core.doc_host.open(CHAT).expect("chat doc");

    // Before any run: the live login is unknown (never probed).
    assert_eq!(claude_sign_in_state(&client, false).await, "unknown");

    core.sessions
        .dispatch(CHAT, HarnessId::ClaudeCode, run_request("hello"), None, None)
        .await
        .expect("dispatch");
    wait_for_status(&core, CHAT, SessionStatus::Errored).await;

    // The run's error marked the LIVE account signed out — no probe ran
    // (the mark is offline), so this is the run's evidence, not a probe's.
    assert_eq!(claude_sign_in_state(&client, false).await, "signed-out");

    // A later successful probe re-establishes the account: the forced list
    // probes the mock usage endpoint, which answers green.
    assert_eq!(claude_sign_in_state(&client, true).await, "signed-in");
}

/// A subagent's signed-out error marks the shared login just the same: the
/// child runs on the same credentials the parent does.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_subagents_signed_out_error_marks_the_shared_login_too() {
    let (core, client) = setup(true).await;
    core.doc_host.open(CHAT).expect("chat doc");

    assert_eq!(claude_sign_in_state(&client, false).await, "unknown");
    core.sessions
        .dispatch(CHAT, HarnessId::ClaudeCode, run_request("hello"), None, None)
        .await
        .expect("dispatch");
    wait_for_status(&core, CHAT, SessionStatus::Errored).await;

    assert_eq!(claude_sign_in_state(&client, false).await, "signed-out");
}
