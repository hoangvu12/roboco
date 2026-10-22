//! Engine-side MCP routing surface (upstream #706, engine-local adaptation).
//!
//! Upstream's #706 routes `targetDeviceId` to a *remote* engine — synced
//! spaces, device relays, cross-engine catalog validation — before creating
//! standalone MCP sessions. Roboco is engine-local (ADR 0004): one engine,
//! one device, no forwarding. The routing surface that survives here is the
//! fail-closed contract (`EngineRpc`: a target device must be this engine,
//! "requests must use its own connection"), pinned by the first test. The
//! second exercises the ported feature itself — MCP standalone sessions
//! (`kind: "chat"`/`"side"`) end to end against a real assembled engine.

use std::sync::Arc;

use async_trait::async_trait;
use futures::stream::BoxStream;
use serde_json::json;

use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::mock::MockHarness;
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_mcp::{Origin, Roboco, Tools};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SteeringMode,
};
use roboco_rpc::methods;

/// A scripted, installed Codex adapter (the production catalog excludes Mock,
/// and `create_chat` validates the harness against `list_harnesses`).
struct ReplyHarness;

#[async_trait]
impl Harness for ReplyHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Codex
    }
    fn display_name(&self) -> &str {
        "Scripted Codex (test only)"
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
        Ok(vec![Model {
            id: "reply-1".into(),
            label: "Reply 1".into(),
            description: None,
            reasoning_levels: vec![],
            options: vec![],
        }])
    }
    async fn run(
        &self,
        request: RunRequest,
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let session_id = uuid::Uuid::new_v4().to_string();
        let mock = MockHarness {
            script: vec![
                AgentEvent::SessionStarted {
                    harness: HarnessId::Codex,
                    model: "reply-1".into(),
                    tools: vec![],
                    cwd: request.cwd.clone(),
                    session_id: session_id.clone(),
                    assistant_message_id: uuid::Uuid::new_v4().to_string(),
                },
                AgentEvent::TextDelta {
                    text: "pong".into(),
                },
                AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some(session_id),
                },
            ],
        };
        mock.run(request, controls).await
    }
}

fn assemble(dir: &std::path::Path, device_id: &str) -> EngineCore {
    std::fs::create_dir_all(dir).unwrap();
    std::fs::write(dir.join("device-id"), device_id).unwrap();
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(ReplyHarness));
    EngineCore::assemble(dir, Arc::new(registry), HarnessId::Codex).expect("engine assembles")
}

/// The engine-local remnant of device-addressed routing: a request naming
/// THIS engine is handled locally; any other target fails closed because
/// there is no other connection to forward it to (ADR 0004).
#[tokio::test]
async fn target_device_ids_must_name_this_engine() {
    let dir = tempfile::tempdir().unwrap();
    let core = assemble(&dir.path().join("solo"), "solo-device");
    let client = roboco_rpc::memory_client(core.rpc_service());

    // Our own id in targetDeviceId: handled locally, no forward.
    let local = client
        .call(
            methods::LIST_HARNESSES,
            json!({ "targetDeviceId": "solo-device" }),
        )
        .await
        .expect("local list");
    assert!(local.is_array());

    // A foreign target fails closed — unary calls and watches alike. The
    // guard lives in `EngineRpc::handle`, before every method dispatch, so it
    // rejects watches too; only the ack-checked subscribe surfaces the
    // rejection (the legacy subscribe returns before the server's reply).
    for method in [methods::LIST_HARNESSES, methods::LIST_MODELS] {
        let err = client
            .call(
                method,
                json!({ "targetDeviceId": "device-elsewhere", "harness": "codex" }),
            )
            .await
            .expect_err("foreign target must fail closed");
        assert!(err.to_string().contains("not connected"), "{method}: {err}");
    }
    assert!(
        client
            .subscribe_checked(
                methods::WATCH_DOC_MESSAGES,
                json!({ "chatId": "solo-chat", "targetDeviceId": "device-elsewhere" }),
            )
            .await
            .is_err()
    );
    core.shutdown().await;
}

/// The engine-local remnant of upstream's per-device worktree locations
/// (upstream #530): preferences and their filesystem validation belong to
/// THIS engine. There is no paired device to reconcile them with (ADR 0004):
/// a foreign `targetDeviceId` fails closed on both worktree-settings methods,
/// and saving always lands in this engine's own store.
#[tokio::test]
async fn worktree_settings_live_on_this_engine() {
    let dir = tempfile::tempdir().unwrap();
    let core = assemble(&dir.path().join("solo"), "solo-device");
    let client = roboco_rpc::memory_client(core.rpc_service());

    // Defaults before any choice: no custom directory, no override.
    let initial = client
        .call(methods::GET_WORKTREE_SETTINGS, json!({}))
        .await
        .expect("read defaults");
    assert_eq!(initial["settings"]["useCustomDirectory"], false);
    assert_eq!(initial["environmentOverride"], serde_json::Value::Null);

    // A foreign target fails closed — the settings are this engine's alone.
    for method in [methods::GET_WORKTREE_SETTINGS, methods::SET_WORKTREE_SETTINGS] {
        let err = client
            .call(
                method,
                json!({ "targetDeviceId": "device-elsewhere" }),
            )
            .await
            .expect_err("foreign target must fail closed");
        assert!(err.to_string().contains("not connected"), "{method}: {err}");
    }

    // Saving a custom location round-trips through this engine's own RPC.
    let custom_worktrees = dir.path().join("custom-worktrees");
    let saved = client
        .call(
            methods::SET_WORKTREE_SETTINGS,
            json!({
                "useCustomDirectory": true,
                "customDirectory": custom_worktrees,
            }),
        )
        .await
        .expect("save worktree location");
    let reread = client
        .call(methods::GET_WORKTREE_SETTINGS, json!({}))
        .await
        .expect("read worktree location");
    assert_eq!(saved, reread);
    assert_eq!(
        std::path::PathBuf::from(saved["effectiveDirectory"].as_str().unwrap()),
        custom_worktrees.canonicalize().unwrap()
    );
    assert_eq!(
        serde_json::to_value(core.repos.worktree_settings()).unwrap(),
        reread,
        "the RPC view and the engine's own state agree"
    );
    core.shutdown().await;
}

/// The engine-local companion of upstream #876's device-routing update: a
/// terminal opened for a projectless chat runs in that chat's own scratch
/// folder under the engine data dir — never the host's home. (Upstream
/// asserts the same over the relay to device B; roboco has only this
/// engine, ADR 0004, so the routing question collapses to this engine's
/// own resolution.)
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn projectless_terminal_runs_in_the_chats_scratch_folder() {
    let dir = tempfile::tempdir().unwrap();
    let core = assemble(&dir.path().join("engine"), "scratch-device");
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::MUTATE,
            serde_json::json!({
                "op": "createChat", "chatId": "chat-term", "deviceId": core.device_id,
            }),
        )
        .await
        .expect("createChat");
    let chat = core.workspace.chat("chat-term").unwrap().unwrap();
    assert_eq!(chat.space_id, None);
    let chat_cwd = chat.cwd.clone().unwrap();
    assert!(
        roboco_proto::projectless::is_scratch_cwd(&chat_cwd),
        "{chat_cwd}"
    );
    assert!(core.workspace.read_spaces().unwrap().is_empty());

    // The terminal resolves the chat's own scratch folder, created on open.
    let session = client
        .call(
            methods::OPEN_TERMINAL,
            serde_json::json!({ "chatId": "chat-term", "cols": 80, "rows": 24 }),
        )
        .await
        .expect("open terminal");
    let name = chat_cwd.rsplit('/').next().unwrap();
    let expected = dir.path().join("engine").join("scratch").join(name);
    assert_eq!(
        std::path::Path::new(session["cwd"].as_str().unwrap()),
        std::path::Path::new(&expected),
        "cwd from the chat's scratch folder"
    );
    assert!(expected.is_dir(), "the folder is created on first use");
    client
        .call(
            methods::CLOSE_TERMINAL,
            serde_json::json!({ "terminalId": session["id"] }),
        )
        .await
        .expect("close terminal");
    core.shutdown().await;
}

/// The ported feature: an MCP standalone session (`kind: "chat"`) runs on
/// this engine, lands parentless in the main Sessions list, replies through
/// the id-matched wait, and keeps the side-chat guards.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn mcp_standalone_session_executes_on_this_engine() {
    let dir = tempfile::tempdir().unwrap();
    let core = assemble(&dir.path().join("engine"), "smoke-device");
    let project = dir.path().join("smoke-project");
    std::fs::create_dir_all(&project).unwrap();
    core.workspace
        .create_space(
            "smoke-project",
            "smoke-device",
            project.to_str().unwrap(),
            Some("Smoke project".into()),
            false,
        )
        .unwrap();
    core.workspace
        .create_chat("coordinator", Some("smoke-project"), None, None, None)
        .unwrap();
    core.workspace
        .rename_chat("coordinator", "Coordinator")
        .unwrap();
    let tools = Tools::new(Arc::new(Roboco::with_client(
        roboco_rpc::memory_client(core.rpc_service()),
        Origin {
            chat_id: Some("coordinator".into()),
            device_id: Some("smoke-device".into()),
        },
    )));

    // Discovery over the real engine: one device, this engine's catalogs.
    let devices = tools.call("list_devices", json!({})).await.unwrap();
    assert_eq!(devices["devices"].as_array().unwrap().len(), 1);
    assert_eq!(devices["devices"][0]["id"], "smoke-device");
    let harnesses = tools
        .call("list_harnesses", json!({ "device": "smoke-device" }))
        .await
        .unwrap();
    assert_eq!(harnesses["harnesses"][0]["id"], "codex");
    let models = tools
        .call(
            "list_models",
            json!({ "harness": "codex", "device": "smoke-device" }),
        )
        .await
        .unwrap();
    assert_eq!(models["models"][0]["id"], "reply-1");

    // Standalone: kind chat forbids a parent and lands in the main list.
    let created = tools
        .call(
            "create_chat",
            json!({
                "kind":"chat", "device":"smoke-device", "project":"smoke-project",
                "harness":"codex", "title":"Standalone MCP session",
                "prompt":"execute on this engine", "wait":true, "timeout_secs":30
            }),
        )
        .await
        .unwrap();
    let chat_id = created["chatId"].as_str().unwrap().to_owned();
    assert_eq!(created["kind"], "chat");
    assert!(created["parentChatId"].is_null());
    assert_eq!(created["deviceId"], "smoke-device");
    assert_eq!(created["turn"]["outcome"], "completed", "{created}");
    assert_eq!(created["turn"]["replies"][0]["text"], "pong");
    let chat = core.workspace.chat(&chat_id).unwrap().unwrap();
    assert!(chat.parent_chat_id.is_none(), "eligible for Sessions");
    assert!(
        core.sessions.session_status(&chat_id).is_some(),
        "this engine ran the harness"
    );

    // The reply reads back through the chat's transcript.
    let read = tools
        .call("read_chat", json!({ "chat": chat_id }))
        .await
        .unwrap();
    assert!(
        read["messages"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["role"] == "assistant" && m["text"] == "pong"),
        "{read}"
    );

    // A second turn returns only the new reply (id-matched, not by time).
    let sent = tools
        .call(
            "send_message",
            json!({ "chat": chat_id, "text": "a second turn", "wait": true, "timeout_secs": 30 }),
        )
        .await
        .unwrap();
    assert_eq!(sent["turn"]["outcome"], "completed", "{sent}");
    assert_eq!(
        sent["turn"]["replies"].as_array().unwrap().len(),
        1,
        "only the new turn: {sent}"
    );
    assert_eq!(sent["turn"]["replies"][0]["text"], "pong");

    // A non-blocking send, then wait_for_turn reusing the send baseline.
    tools
        .call(
            "send_message",
            json!({ "chat": chat_id, "text": "a third turn", "wait": false }),
        )
        .await
        .unwrap();
    let waited = tools
        .call(
            "wait_for_turn",
            json!({ "chat": chat_id, "timeout_secs": 30 }),
        )
        .await
        .unwrap();
    assert_eq!(waited["turn"]["outcome"], "completed", "{waited}");

    // Side chats keep the guards: a child cannot create chats.
    let side = tools
        .call(
            "create_chat",
            json!({ "kind": "side", "parent": "coordinator", "project": "smoke-project" }),
        )
        .await
        .unwrap();
    assert_eq!(side["kind"], "side");
    assert_eq!(side["parentChatId"], "coordinator");
    let side_id = side["chatId"].as_str().unwrap().to_owned();
    let side_roboco = Arc::new(Roboco::with_client(
        roboco_rpc::memory_client(core.rpc_service()),
        Origin {
            chat_id: Some(side_id.clone()),
            device_id: Some("smoke-device".into()),
        },
    ));
    // WatchChats folds registry writes asynchronously: a real MCP server for
    // a side chat starts after the row is long visible, and this test must
    // meet the same bar before speaking for the chat — poll until the fresh
    // row resolves instead of racing the publish task.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        if side_roboco.resolve_chat(&side_id).await.is_ok() {
            break;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "side chat {side_id} never became visible to WatchChats"
        );
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    let side_tools = Tools::new(side_roboco);
    let err = side_tools
        .call("create_chat", json!({ "kind": "chat" }))
        .await
        .unwrap_err();
    assert!(err.contains("Side chats cannot"), "{err}");

    // A mixed batch: standalone + side succeed per request, errors per row.
    let batch = tools
        .call(
            "create_chats",
            json!({"requests":[
                {"kind":"chat", "project":"smoke-project", "prompt":"first worker", "wait":false},
                {"kind":"side", "parent":"coordinator", "prompt":"second worker", "wait":false},
                {"kind":"bogus"}
            ]}),
        )
        .await
        .unwrap();
    let results = batch["results"].as_array().unwrap();
    assert_eq!(results[0]["isError"], false, "{}", json!(batch));
    assert_eq!(results[0]["result"]["kind"], "chat");
    assert_eq!(results[1]["isError"], false);
    assert_eq!(results[1]["result"]["kind"], "side");
    assert_eq!(results[2]["isError"], true);
    // Both workers settle before shutdown.
    for request in &results[..2] {
        let id = request["result"]["chatId"].as_str().unwrap();
        let settled = tools
            .call("wait_for_turn", json!({ "chat": id, "timeout_secs": 30 }))
            .await
            .unwrap();
        assert_eq!(settled["turn"]["outcome"], "completed", "{settled}");
    }

    core.shutdown().await;
}
