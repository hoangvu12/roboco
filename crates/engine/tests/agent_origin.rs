//! Message origin and agent-created side chats (orchestration v2, ticket 11).
//!
//! The groundwork completion notices need, all of it additive on the wire:
//! - a side chat created through the Roboco MCP surface (`Mutate createChat`
//!   with `agentOrigin`) durably records the creating chat and its notify
//!   flag, and survives engine restarts;
//! - user-made chats — forks, blank side chats, plain creates — carry no
//!   agent origin and never notify;
//! - Run and Steer commands sent with an origin chat (`QueueCommand
//!   originChatId`), and queued rows (`QueueMessage originChatId`), stamp
//!   that origin on the user message entry they write, through every
//!   delivery path: run, steer, hold-for-turn-end — and the Stop that
//!   cancels a held send writes it unrun with the origin kept.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessagePart, MessageRole, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SessionStatus, SteeringMode,
};

const CHAT: &str = "chat-origin";
const PARENT: &str = "chat-parent-1";

/// A turn that runs until the test says so, so "busy" is a state the test
/// controls. Steers are accepted through the mailbox (the entry is written
/// before the harness ever sees the prompt) and confirmed with a `Steered`
/// boundary.
struct HeldHarness {
    steering: SteeringMode,
    finish: tokio::sync::broadcast::Sender<()>,
    prompts: Arc<Mutex<Vec<String>>>,
}

impl HeldHarness {
    fn new(steering: SteeringMode) -> Arc<Self> {
        let (finish, _) = tokio::sync::broadcast::channel(16);
        Arc::new(Self {
            steering,
            finish,
            prompts: Arc::new(Mutex::new(Vec::new())),
        })
    }
}

#[async_trait]
impl Harness for HeldHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Held"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        self.steering
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
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.prompts.lock().unwrap().push(request.prompt.clone());
        let mut finish = self.finish.subscribe();
        let mut steering = controls.steering;
        let (tx, mut rx) = tokio::sync::mpsc::channel(16);
        let _ = tx.try_send(Ok(AgentEvent::SessionStarted {
            harness: HarnessId::Mock,
            model: "mock-1".into(),
            tools: vec![],
            cwd: request.cwd.clone(),
            session_id: "sess-origin".into(),
            assistant_message_id: format!("a-{}", request.prompt),
        }));
        tokio::spawn(async move {
            let done = || {
                Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some("sess-origin".into()),
                })
            };
            loop {
                tokio::select! {
                    _ = finish.recv() => {
                        let _ = tx.send(done()).await;
                        break;
                    }
                    steer = steering.recv() => {
                        if steer.is_none() {
                            let _ = tx.send(done()).await;
                            break;
                        }
                        // Confirm the steer's boundary like a real
                        // step-boundary agent: an accepted steer that never
                        // gets one is re-dispatched as a fresh turn when the
                        // run exits (a sent message must never silently not
                        // run), and that turn would hang past the test's
                        // one `finish`.
                        if tx
                            .send(Ok(AgentEvent::Steered {
                                assistant_message_id: None,
                                next_assistant_message_id: None,
                            }))
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                }
            }
        });
        Ok(futures::stream::poll_fn(move |cx| rx.poll_recv(cx)).boxed())
    }
}

fn run_request(prompt: &str) -> RunRequest {
    RunRequest {
        env: Default::default(),
        mcp: None,
        prompt: prompt.into(),
        harness: Some(HarnessId::Mock),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        resume: None,
        provider_fork: None,
        resume_fallback_history: None,
        attachments: vec![],
        worktree: None,
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

fn setup(
    steering: SteeringMode,
) -> (
    EngineCore,
    Arc<HeldHarness>,
    Arc<Mutex<Vec<String>>>,
    std::path::PathBuf,
) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.keep();
    let harness = HeldHarness::new(steering);
    let prompts = harness.prompts.clone();
    let registry = HarnessRegistry::new();
    registry.register(harness.clone());
    let core = EngineCore::assemble(&path, Arc::new(registry), HarnessId::Mock)
        .expect("engine core assembles");
    (core, harness, prompts, path)
}

fn entries(core: &EngineCore, chat: &str) -> Vec<SessionMessageEntry> {
    core.doc_host
        .open(chat)
        .expect("chat doc")
        .doc()
        .read_entries()
        .expect("transcript")
}

/// The user entry whose text is exactly `text`, with its origin chat.
fn user_entry_origin(core: &EngineCore, chat: &str, text: &str) -> Option<Option<String>> {
    entries(core, chat)
        .into_iter()
        .find(|entry| {
            entry.role == MessageRole::User
                && entry
                    .parts
                    .iter()
                    .any(|part| matches!(part, MessagePart::Text { text: body, .. } if body == text))
        })
        .map(|entry| entry.origin_chat_id)
}

async fn wait_idle(core: &EngineCore, chat: &str) {
    wait_for(
        || {
            core.sessions
                .session_status(chat)
                .is_some_and(|s| s.status == SessionStatus::Idle)
        },
        "the chat to go idle",
    )
    .await;
}

/// An agent-made side chat records its creator and notify flag durably; the
/// row is the record completion notices will key on, so it must survive a
/// restart with the chat itself.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn agent_created_side_chats_record_their_creator_and_notify_durably() {
    let (core, harness, _prompts, data_dir) = setup(SteeringMode::StepBoundary);
    let client = roboco_rpc::memory_client(core.rpc_service());

    for (chat, notify) in [("side-notify", None), ("side-quiet", Some(false))] {
        let mut params = serde_json::json!({
            "op": "createChat",
            "chatId": chat,
            "deviceId": core.device_id,
            "parentChatId": PARENT,
            "agentOrigin": { "chatId": PARENT },
        });
        if let Some(notify) = notify {
            params["agentOrigin"]["notify"] = serde_json::json!(notify);
        }
        client
            .call(roboco_rpc::methods::MUTATE, params)
            .await
            .expect("createChat with agentOrigin");
    }

    let notify_row = core.workspace.chat("side-notify").unwrap().unwrap();
    assert_eq!(
        notify_row.agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: true,
        }),
        "the notify flag defaults to on"
    );
    assert_eq!(notify_row.parent_chat_id.as_deref(), Some(PARENT));
    let quiet_row = core.workspace.chat("side-quiet").unwrap().unwrap();
    assert_eq!(
        quiet_row.agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: false,
        }),
        "notify: false is honoured"
    );

    let _ = harness.finish.send(());
    core.shutdown().await;
    drop(client);
    // The data-dir instance lock lives as long as the core: drop it before
    // the restart, exactly like an app restart releases it.
    drop(core);

    // The row is durable: the restart reads it back exactly.
    let restarted =
        EngineCore::assemble(&data_dir, Arc::new(HarnessRegistry::new()), HarnessId::Mock)
            .unwrap();
    assert_eq!(
        restarted.workspace.chat("side-notify").unwrap().unwrap().agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: true,
        })
    );
    assert_eq!(
        restarted.workspace.chat("side-quiet").unwrap().unwrap().agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: false,
        })
    );
    restarted.shutdown().await;
}

/// User-made chats are never marked: a fork, a blank side chat and a plain
/// create all carry no agent origin, so none of them can ever notify.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn user_created_chats_are_never_marked_agent_created() {
    let (core, harness, _prompts, _data_dir) = setup(SteeringMode::StepBoundary);
    let client = roboco_rpc::memory_client(core.rpc_service());

    // A plain create.
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": "plain",
                "deviceId": core.device_id,
            }),
        )
        .await
        .expect("createChat");
    // A blank side chat (the UI's "+ New Side Chat").
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": "blank-side",
                "deviceId": core.device_id,
                "parentChatId": "plain",
            }),
        )
        .await
        .expect("createChat");
    // A createChat that carries a parent but no agentOrigin (a human at a
    // terminal naming a parent).
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": "human-side",
                "deviceId": core.device_id,
                "parentChatId": "plain",
            }),
        )
        .await
        .expect("createChat");

    // A fork of a chat with history: the copy lists under the parent but is
    // user-made. The fork button copies through the latest complete reply,
    // so the source needs a settled turn, not just a user message.
    settled_turn(&core, "plain");
    client
        .call(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "plain" }),
        )
        .await
        .expect("fork");

    for chat in ["plain", "blank-side", "human-side", "fork"] {
        let row = core.workspace.chat(chat).unwrap().unwrap();
        assert_eq!(
            row.agent_origin, None,
            "{chat} is user-created and must never be marked"
        );
    }
    assert_eq!(
        core.workspace.chat("fork").unwrap().unwrap().parent_chat_id,
        Some("plain".into())
    );

    let _ = harness.finish.send(());
    core.shutdown().await;
}

/// A source chat with one settled turn, so the fork RPC has a boundary to
/// copy through.
fn settled_turn(core: &EngineCore, chat: &str) {
    let doc = core.doc_host.open(chat).expect("chat doc");
    for (id, role, text) in [
        ("u1", MessageRole::User, "remember this"),
        ("a1", MessageRole::Assistant, "noted"),
    ] {
        doc.doc()
            .push_message(&SessionMessageEntry {
                origin_chat_id: None,
                id: id.into(),
                role,
                parts: vec![MessagePart::Text {
                    id: format!("t-{id}"),
                    text: text.into(),
                }],
                created_at: 1,
                device_id: core.device_id.clone(),
                status: Some(roboco_doc::MessageStatus::Complete),
                continuation_of: None,
                duration_ms: None,
                provider_turn: None,
            })
            .expect("push entry");
    }
}

/// An agent-made fork (the `fork_chat` MCP tool, ticket 23) stamps the
/// creator and notify flag on the copy — the row completion notices key on
/// — while a fork RPC without an `agentOrigin` stays user-made and never
/// notifies, even when the source row itself was agent-created.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn agent_made_forks_notify_and_user_forks_stay_plain() {
    let (core, harness, _prompts, _data_dir) = setup(SteeringMode::StepBoundary);
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": "fork-source",
                "deviceId": core.device_id,
            }),
        )
        .await
        .expect("createChat");
    settled_turn(&core, "fork-source");

    // An agent fork: the agentOrigin param rides the RPC (notify omitted
    // reads as on, notify: false opts out), and the copy hangs under the
    // creator the tool names.
    let fork = client
        .call_as::<roboco_proto::Chat>(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({
                "chatId": "agent-fork-on",
                "sourceChatId": "fork-source",
                "parentChatId": PARENT,
                "agentOrigin": { "chatId": PARENT },
            }),
        )
        .await
        .expect("agent fork");
    assert_eq!(
        fork.agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: true,
        })
    );
    client
        .call(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({
                "chatId": "agent-fork-off",
                "sourceChatId": "fork-source",
                "parentChatId": PARENT,
                "agentOrigin": { "chatId": PARENT, "notify": false },
            }),
        )
        .await
        .expect("agent fork");
    for (chat, notify) in [("agent-fork-on", true), ("agent-fork-off", false)] {
        let row = core.workspace.chat(chat).unwrap().unwrap();
        assert_eq!(
            row.agent_origin,
            Some(roboco_proto::AgentOrigin {
                chat_id: PARENT.into(),
                notify,
            }),
            "{chat} records its creator and notify flag"
        );
        assert_eq!(row.parent_chat_id.as_deref(), Some(PARENT));
    }

    // A fork without the param (the UI's fork button): user-made, hanging
    // under the source as always.
    client
        .call(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "ui-fork", "sourceChatId": "fork-source" }),
        )
        .await
        .expect("ui fork");
    let row = core.workspace.chat("ui-fork").unwrap().unwrap();
    assert_eq!(row.agent_origin, None);
    assert_eq!(row.parent_chat_id.as_deref(), Some("fork-source"));

    // The strip: a USER fork of an agent-created side chat is not marked,
    // even though the source row carried an origin (ticket 11 — user forks
    // never notify).
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": "agent-side",
                "deviceId": core.device_id,
                "parentChatId": PARENT,
                "agentOrigin": { "chatId": PARENT },
            }),
        )
        .await
        .expect("createChat");
    settled_turn(&core, "agent-side");
    client
        .call(
            roboco_rpc::methods::FORK_SIDE_CHAT,
            serde_json::json!({
                "chatId": "user-sibling",
                "sourceChatId": "agent-side",
                "parentChatId": PARENT,
            }),
        )
        .await
        .expect("sibling fork");
    let row = core.workspace.chat("user-sibling").unwrap().unwrap();
    assert_eq!(
        row.agent_origin, None,
        "a user fork never inherits the source's agent origin"
    );
    assert_eq!(row.parent_chat_id.as_deref(), Some(PARENT));

    let _ = harness.finish.send(());
    core.shutdown().await;
}

/// A Run command sent by a chat through the Roboco MCP surface carries its
/// origin on the ledger row AND on the user message entry it writes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn run_commands_stamp_their_origin_on_the_user_entry() {
    let (core, harness, prompts, _data_dir) = setup(SteeringMode::StepBoundary);
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

    let command = serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
        request: run_request("from the orchestrator"),
        message_id: "m-run-1".into(),
    })
    .unwrap();
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": command,
                "originChatId": PARENT,
            }),
        )
        .await
        .expect("queue run");

    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "from the orchestrator"),
        "the run to dispatch",
    )
    .await;

    // The ledger row keeps the origin…
    let commands = core.doc_host.open(CHAT).unwrap().doc().read_commands().unwrap();
    let run = commands
        .iter()
        .find(|c| c.payload.kind() == roboco_doc::SessionCommandKind::Run)
        .expect("run command in ledger");
    assert_eq!(run.origin_chat_id.as_deref(), Some(PARENT));
    // …and so does the user entry the run wrote.
    assert_eq!(
        user_entry_origin(&core, CHAT, "from the orchestrator"),
        Some(Some(PARENT.into())),
        "the user entry keeps the originating chat"
    );

    let _ = harness.finish.send(());
    wait_idle(&core, CHAT).await;
    core.shutdown().await;
}

/// A Steer command into a live steerable run writes its user entry with the
/// origin (the mid-run mailbox path).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn steer_commands_stamp_their_origin_on_the_user_entry() {
    let (core, harness, prompts, _data_dir) = setup(SteeringMode::StepBoundary);
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

    // Start a turn and let it hang.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
                    request: run_request("opening turn"),
                    message_id: "m-open".into(),
                }).unwrap(),
            }),
        )
        .await
        .expect("queue opening run");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "opening turn"),
        "the opening turn to start",
    )
    .await;

    let steer = serde_json::to_value(roboco_doc::SessionCommandPayload::Steer {
        prompt: "mid-run course fix".into(),
        message_id: Some("m-steer-1".into()),
    })
    .unwrap();
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": steer,
                "originChatId": PARENT,
            }),
        )
        .await
        .expect("queue steer");

    wait_for(
        || user_entry_origin(&core, CHAT, "mid-run course fix").is_some(),
        "the steer's user entry to land",
    )
    .await;
    let commands = core.doc_host.open(CHAT).unwrap().doc().read_commands().unwrap();
    let steer = commands
        .iter()
        .find(|c| matches!(&c.payload, roboco_doc::SessionCommandPayload::Steer { message_id, .. } if message_id.as_deref() == Some("m-steer-1")))
        .expect("steer command in ledger");
    assert_eq!(steer.origin_chat_id.as_deref(), Some(PARENT));
    assert_eq!(
        user_entry_origin(&core, CHAT, "mid-run course fix"),
        Some(Some(PARENT.into()))
    );

    let _ = harness.finish.send(());
    wait_idle(&core, CHAT).await;
    core.shutdown().await;
}

/// A queued row sent by a chat keeps its origin through the hold and the
/// promotion at turn end (the `mode: "queue"` delivery).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn queued_rows_keep_their_origin_when_promoted() {
    let (core, harness, prompts, _data_dir) = setup(SteeringMode::TurnBoundary);
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

    // Start a turn and let it hang: a turn-boundary agent holds the row.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
                    request: run_request("opening turn"),
                    message_id: "m-open".into(),
                }).unwrap(),
            }),
        )
        .await
        .expect("queue opening run");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "opening turn"),
        "the opening turn to start",
    )
    .await;

    let queued = client
        .call(
            roboco_rpc::methods::QUEUE_MESSAGE,
            serde_json::json!({
                "chatId": CHAT,
                "text": "held from the orchestrator",
                "originChatId": PARENT,
            }),
        )
        .await
        .expect("queue message");
    let queued_id = queued["id"].as_str().unwrap().to_owned();

    // The row itself carries the origin while it waits.
    let row = core
        .doc_host
        .open(CHAT)
        .unwrap()
        .doc()
        .read_queue()
        .unwrap()
        .into_iter()
        .find(|row| row.id == queued_id)
        .expect("held row");
    assert_eq!(row.origin_chat_id.as_deref(), Some(PARENT));

    // End the turn: the row is promoted and its user entry keeps the origin.
    let _ = harness.finish.send(());
    wait_for(
        || user_entry_origin(&core, CHAT, "held from the orchestrator").is_some(),
        "the held row to be promoted",
    )
    .await;
    wait_for(
        || {
            core.doc_host
                .open(CHAT)
                .unwrap()
                .doc()
                .read_queue()
                .unwrap()
                .is_empty()
        },
        "the queue to empty",
    )
    .await;
    assert_eq!(
        user_entry_origin(&core, CHAT, "held from the orchestrator"),
        Some(Some(PARENT.into()))
    );

    // Let the promoted turn end too, then settle.
    let _ = harness.finish.send(());
    wait_idle(&core, CHAT).await;
    core.shutdown().await;
}

/// A Stop drops the sends it cancels: the held steer leaves the queue and
/// lands in the transcript unrun — still carrying its origin chat.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stop_cancels_a_held_send_but_keeps_its_origin() {
    let (core, harness, prompts, _data_dir) = setup(SteeringMode::TurnBoundary);
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

    // Start a turn and let it hang: a turn-boundary agent holds the steer.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
                    request: run_request("opening turn"),
                    message_id: "m-open".into(),
                }).unwrap(),
            }),
        )
        .await
        .expect("queue opening run");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "opening turn"),
        "the opening turn to start",
    )
    .await;

    // A steer sent by the orchestrator mid-turn: held for the turn's end.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Steer {
                    prompt: "stopped mid course".into(),
                    message_id: Some("m-stopped".into()),
                }).unwrap(),
                "originChatId": PARENT,
            }),
        )
        .await
        .expect("queue steer");
    wait_for(
        || {
            core.doc_host
                .open(CHAT)
                .unwrap()
                .doc()
                .read_queue()
                .unwrap()
                .iter()
                .any(|row| row.id == "m-stopped")
        },
        "the steer to be held for the turn's end",
    )
    .await;

    // Stop: the held send is cancelled — it lands in the transcript unrun,
    // with the origin it was sent with.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Interrupt {}) .unwrap(),
            }),
        )
        .await
        .expect("queue stop");
    wait_for(
        || user_entry_origin(&core, CHAT, "stopped mid course").is_some(),
        "the cancelled steer to land unrun in the transcript",
    )
    .await;
    assert_eq!(
        user_entry_origin(&core, CHAT, "stopped mid course"),
        Some(Some(PARENT.into()))
    );
    wait_for(
        || {
            core.doc_host
                .open(CHAT)
                .unwrap()
                .doc()
                .read_queue()
                .unwrap()
                .is_empty()
        },
        "the cancelled row to leave the queue",
    )
    .await;
    assert!(
        !prompts
            .lock()
            .unwrap()
            .iter()
            .any(|p| p == "stopped mid course"),
        "the cancelled steer never reaches the agent"
    );

    let _ = harness.finish.send(());
    wait_idle(&core, CHAT).await;
    core.shutdown().await;
}

/// Typed messages (the composer path) never carry an origin.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn typed_sends_carry_no_origin() {
    let (core, harness, prompts, _data_dir) = setup(SteeringMode::StepBoundary);
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

    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": CHAT,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
                    request: run_request("typed by the user"),
                    message_id: "m-typed".into(),
                }).unwrap(),
            }),
        )
        .await
        .expect("queue typed run");
    wait_for(
        || prompts.lock().unwrap().iter().any(|p| p == "typed by the user"),
        "the typed run to dispatch",
    )
    .await;

    assert_eq!(user_entry_origin(&core, CHAT, "typed by the user"), Some(None));
    let commands = core.doc_host.open(CHAT).unwrap().doc().read_commands().unwrap();
    assert!(commands.iter().all(|c| c.origin_chat_id.is_none()));

    let _ = harness.finish.send(());
    wait_idle(&core, CHAT).await;
    core.shutdown().await;
}
