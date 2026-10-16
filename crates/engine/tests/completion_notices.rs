//! Completion notices wake an idle parent (orchestration v2, ticket 12).
//!
//! The tracer bullet for the spec's completion-notice stories 13–17 and 19:
//! - a turn of an agent-created, NOTIFYING side chat is notifiable when its
//!   starting message, or a steer within it, came from the parent;
//! - when such a turn ends, the engine records an outcome from its OWN
//!   turn-end state — completed; errored with a one-line reason; stopped;
//!   waiting for input with a one-line summary of the question — and wakes
//!   an idle parent with one engine-authored notice message that starts a
//!   parent turn;
//! - the notice is a SYSTEM entry (never a user message) carrying a
//!   completion-notice marker for clients to render distinctly (ticket 15),
//!   names the side chat by title and short id with its outcome, and tells
//!   the agent to use `read_chat` for the full result;
//! - a user-started turn in the child, a user-created side chat, and a
//!   `notify: false` child never wake the parent.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{
    MessagePart, MessageRole, NoticeOutcome, NoticeSide, SessionMessageEntry,
};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SessionStatus, SteeringMode, UserInputQuestion,
};

const PARENT: &str = "chat-parent";
const SIDE: &str = "chat-side";

/// What one `run()` call does — the tests script turns FIFO, one entry per
/// run the engine starts (a side chat's turn, then the parent's notice
/// turn, …).
enum Turn {
    /// Stream one line of text, then Done with the status and error.
    End {
        status: DoneStatus,
        error: Option<String>,
    },
    /// Ask a question through the engine's input bridge, then park awaiting
    /// the answer (the turn stays open, the chat awaits input).
    AskInput { question: &'static str },
    /// Hang until a steer arrives: consume it, emit the `Steered` boundary,
    /// then settle the turn Completed (what a steered run does when its
    /// work finishes).
    Hang,
}

/// A steerable mock whose turns the test scripts. Every run's prompt is
/// recorded, so a test can prove the parent was — or was never — woken by
/// a notice.
struct Scripted {
    turns: Arc<Mutex<VecDeque<Turn>>>,
    prompts: Arc<Mutex<Vec<String>>>,
    /// The prompts of steers the mock consumed (delivered mid-run).
    steered: Arc<Mutex<Vec<String>>>,
}

impl Scripted {
    fn new(turns: Vec<Turn>) -> Arc<Self> {
        Arc::new(Self {
            turns: Arc::new(Mutex::new(turns.into())),
            prompts: Arc::new(Mutex::new(Vec::new())),
            steered: Arc::new(Mutex::new(Vec::new())),
        })
    }

    fn run_count(&self) -> usize {
        self.prompts.lock().unwrap().len()
    }

    fn prompts_containing(&self, needle: &str) -> bool {
        self.prompts
            .lock()
            .unwrap()
            .iter()
            .any(|p| p.contains(needle))
    }
}

#[async_trait]
impl Harness for Scripted {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Scripted"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
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
        let turn = self
            .turns
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or(Turn::Hang);
        let session = format!("sess-{}", self.run_count());
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Result<AgentEvent, HarnessError>>(32);
        let steered = self.steered.clone();
        let mut steering = controls.steering;
        let request_input = controls.request_input;
        let started_session = session.clone();
        let cwd = request.cwd.clone();
        tokio::spawn(async move {
            let _ = tx
                .send(Ok(AgentEvent::SessionStarted {
                    harness: HarnessId::Mock,
                    model: "mock-1".into(),
                    tools: vec![],
                    cwd,
                    session_id: started_session,
                    assistant_message_id: "a0".into(),
                }))
                .await;
            match turn {
                Turn::End { status, error } => {
                    let _ = tx
                        .send(Ok(AgentEvent::TextDelta {
                            text: "side answer".into(),
                        }))
                        .await;
                    let _ = tx
                        .send(Ok(AgentEvent::Done {
                            status,
                            result: None,
                            error,
                            session_id: Some(session),
                        }))
                        .await;
                }
                Turn::AskInput { question } => {
                    // The engine's input bridge mints the id and parks the
                    // resolver before the event reaches the run loop, so a
                    // legitimate question is always known here.
                    let questions = vec![UserInputQuestion {
                        id: "q1".into(),
                        header: "Approval".into(),
                        question: question.into(),
                        options: vec!["Yes".into(), "No".into()],
                        multi_select: false,
                    }];
                    // Awaiting the answer parks the turn: the chat goes to
                    // awaiting input and stays there.
                    let _ = (request_input)(questions).await;
                    let _ = tx
                        .send(Ok(AgentEvent::Done {
                            status: DoneStatus::Completed,
                            result: None,
                            error: None,
                            session_id: Some(session),
                        }))
                        .await;
                }
                Turn::Hang => loop {
                    tokio::select! {
                        steer = steering.recv() => match steer {
                            Some(message) => {
                                steered.lock().unwrap().push(message.prompt.clone());
                                // The steer's boundary, then the steered turn
                                // settles: the engine retires the accepted
                                // steer at the boundary and parks the turn at
                                // Done.
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
                                let _ = tx
                                    .send(Ok(AgentEvent::Done {
                                        status: DoneStatus::Completed,
                                        result: None,
                                        error: None,
                                        session_id: Some(session),
                                    }))
                                    .await;
                                break;
                            }
                            None => break,
                        },
                        _ = tx.closed() => break,
                    }
                },
            }
        });
        Ok(rx.boxed())
    }
}

fn run_request(prompt: &str) -> RunRequest {
    RunRequest {
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

fn entries(core: &EngineCore, chat: &str) -> Vec<SessionMessageEntry> {
    core.doc_host
        .open(chat)
        .expect("chat doc")
        .doc()
        .read_entries()
        .expect("transcript")
}

/// The completion-notice entries in a chat: SYSTEM entries carrying the
/// marker — never user entries.
fn notice_entries(core: &EngineCore, chat: &str) -> Vec<SessionMessageEntry> {
    entries(core, chat)
        .into_iter()
        .filter(|entry| entry.role == MessageRole::System)
        .filter(|entry| {
            entry
                .parts
                .iter()
                .any(|part| matches!(part, MessagePart::CompletionNotice { .. }))
        })
        .collect()
}

fn notice_text(entry: &SessionMessageEntry) -> String {
    entry
        .parts
        .iter()
        .find_map(|part| match part {
            MessagePart::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

fn notice_sides(entry: &SessionMessageEntry) -> Vec<NoticeSide> {
    entry
        .parts
        .iter()
        .find_map(|part| match part {
            MessagePart::CompletionNotice { sides, .. } => Some(sides.clone()),
            _ => None,
        })
        .unwrap_or_default()
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

/// Create a parent (with a cwd, so a notice turn can run on it) and an
/// agent-created side chat under it, titled, with `notify`.
async fn create_parent_and_side(
    core: &EngineCore,
    client: &roboco_rpc::RpcClient,
    notify: Option<bool>,
) {
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": PARENT,
                "deviceId": core.device_id,
                "cwd": "/tmp",
            }),
        )
        .await
        .expect("createChat parent");
    core.workspace.rename_chat(PARENT, "Orchestrator").unwrap();
    let mut agent_origin = serde_json::json!({ "chatId": PARENT });
    if let Some(notify) = notify {
        agent_origin["notify"] = serde_json::json!(notify);
    }
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": SIDE,
                "deviceId": core.device_id,
                "parentChatId": PARENT,
                "agentOrigin": agent_origin,
            }),
        )
        .await
        .expect("createChat side");
    core.workspace.rename_chat(SIDE, "Implement login").unwrap();
}

/// Queue a Run command for `chat`, optionally sent by the parent through the
/// MCP surface (`origin_chat_id`).
async fn queue_run(
    client: &roboco_rpc::RpcClient,
    chat: &str,
    prompt: &str,
    message_id: &str,
    origin_chat_id: Option<&str>,
) {
    let mut params = serde_json::json!({
        "chatId": chat,
        "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
            request: run_request(prompt),
            message_id: message_id.into(),
        })
        .unwrap(),
    });
    if let Some(origin) = origin_chat_id {
        params["originChatId"] = serde_json::json!(origin);
    }
    client
        .call(roboco_rpc::methods::QUEUE_COMMAND, params)
        .await
        .expect("queue run");
}

/// An idle parent is woken by a notice when its side chat's parent-started
/// turn completes: the notice is a system entry (never a user message)
/// carrying the marker, names the child by title and short id, points at
/// `read_chat`, and starts a parent turn on the parent's own harness.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_idle_parent_is_woken_when_its_side_chat_turn_completes() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The parent's notice turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // The parent starts the child's turn through the MCP surface.
    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || scripted.prompts_containing("please implement login"),
        "the side chat's turn to start",
    )
    .await;

    // The child's turn completes; the idle parent is woken by the notice.
    wait_for(
        || scripted.prompts_containing("finished"),
        "the notice to start a parent turn",
    )
    .await;
    // The notice turn is the parent's SECOND run, on its own harness.
    assert_eq!(scripted.run_count(), 2);
    let notice_prompt = scripted.prompts.lock().unwrap()[1].clone();
    assert!(notice_prompt.contains("Side chat \"Implement login\""));
    assert!(notice_prompt.contains("read_chat"));

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1, "exactly one notice entry");
    let notice = &notices[0];
    // Engine/system message, not a user message, and engine-authored (no
    // originating chat).
    assert_eq!(notice.role, MessageRole::System);
    assert_eq!(notice.origin_chat_id, None);
    assert_eq!(notice.status, Some(roboco_doc::MessageStatus::Complete));
    // The marker clients render distinctly (ticket 15).
    assert_eq!(
        notice_sides(notice),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        }]
    );
    // The plain-text sibling names the child and points at read_chat.
    let text = notice_text(notice);
    let short = &SIDE[..8];
    assert!(text.contains(&format!("\"Implement login\" ({short}) finished")));
    assert!(text.contains("read_chat"));
    assert!(text.contains(short));

    // The parent's notice turn ran and settled.
    wait_idle(&core, PARENT).await;
    // The child's own transcript keeps its answer, unrun by the parent.
    assert!(entries(&core, SIDE)
        .iter()
        .any(|e| e.parts.iter().any(|p| matches!(p, MessagePart::Text { text, .. } if text == "side answer"))));

    core.shutdown().await;
}

/// A parent-started turn that errors notifies with the one-line reason —
/// the first line of the engine's recorded error, capped.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_errored_side_chat_turn_notifies_the_parent_with_the_reason() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        Turn::End {
            status: DoneStatus::Errored,
            error: Some(
                "provider 502: bad gateway\nstream failed mid-turn\nretry later".into(),
            ),
        },
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || scripted.prompts_containing("failed"),
        "the failure notice to start a parent turn",
    )
    .await;

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    let sides = notice_sides(&notices[0]);
    assert_eq!(
        sides,
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Errored,
            // One line: the first line of the recorded error only.
            detail: Some("provider 502: bad gateway".into()),
        }]
    );
    let text = notice_text(&notices[0]);
    assert!(text.contains("failed: provider 502: bad gateway"));
    assert!(!text.contains("retry later"));
    assert!(text.contains("read_chat"));

    core.shutdown().await;
}

/// A parent-started turn that is stopped or interrupted notifies as
/// stopped.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stopped_side_chat_turn_notifies_the_parent() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        Turn::End {
            status: DoneStatus::Interrupted,
            error: None,
        },
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || scripted.prompts_containing("was stopped"),
        "the stopped notice to start a parent turn",
    )
    .await;

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    let sides = notice_sides(&notices[0]);
    assert_eq!(
        sides,
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Stopped,
            detail: None,
        }]
    );
    assert!(notice_text(&notices[0]).contains("was stopped"));

    core.shutdown().await;
}

/// A parent-started turn that reaches awaiting input notifies with a
/// one-line summary of the question it is waiting on.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_awaiting_input_side_chat_turn_notifies_with_the_question() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        Turn::AskInput {
            question: "Should we bump the major version?",
        },
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, SIDE, "please review the API", "m-run-1", Some(PARENT)).await;
    // The child parks on its question; the parent is woken by the notice.
    wait_for(
        || {
            core.sessions
                .session_status(SIDE)
                .is_some_and(|s| s.status == SessionStatus::AwaitingInput)
        },
        "the side chat to await input",
    )
    .await;
    wait_for(
        || scripted.prompts_containing("waiting for input"),
        "the waiting-for-input notice to start a parent turn",
    )
    .await;

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    let sides = notice_sides(&notices[0]);
    assert_eq!(
        sides,
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::WaitingForInput,
            detail: Some("Should we bump the major version?".into()),
        }]
    );
    let text = notice_text(&notices[0]);
    assert!(text.contains("is waiting for input: Should we bump the major version?"));
    assert!(text.contains("read_chat"));

    core.shutdown().await;
}

/// A turn the USER started in the child (no parent-originated message)
/// produces no notice: the parent is never woken.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_user_started_turn_in_the_child_produces_no_notice() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // A typed send: no origin chat on the command or the entry.
    queue_run(&client, SIDE, "typed by the user", "m-run-1", None).await;
    wait_idle(&core, SIDE).await;
    // Let any (wrongly recorded) notice delivery settle before asserting
    // absence.
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "a user-started turn must not wake the parent"
    );
    assert!(entries(&core, PARENT).is_empty());
    assert_eq!(
        scripted.run_count(),
        1,
        "only the side chat's turn ran — no parent turn"
    );
    // The typed send carries no origin on its user entry.
    assert!(entries(&core, SIDE)
        .iter()
        .all(|e| e.origin_chat_id.is_none()));

    core.shutdown().await;
}

/// A user-created side chat (no agent origin on its row) produces no notice
/// even when a chat-originated message reaches it: the durable creator row
/// is what makes turns notifiable.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_user_created_side_chat_produces_no_notice() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    // A side chat with a parent but NO agent origin: the UI's blank side
    // chat shape.
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": PARENT,
                "deviceId": core.device_id,
                "cwd": "/tmp",
            }),
        )
        .await
        .expect("createChat parent");
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": SIDE,
                "deviceId": core.device_id,
                "parentChatId": PARENT,
            }),
        )
        .await
        .expect("createChat blank side chat");

    // Even a parent-originated message does not make its turns notifiable:
    // there is no durable creator row to key on.
    queue_run(&client, SIDE, "sent by a chat anyway", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "a user-created side chat must never notify"
    );
    assert_eq!(scripted.run_count(), 1, "no parent turn was started");

    core.shutdown().await;
}

/// A `notify: false` side chat (fire-and-forget) records no outcomes for its
/// creator: the opt-out at creation is durable.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_notify_false_side_chat_produces_no_notice() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, Some(false)).await;
    assert_eq!(
        core.workspace.chat(SIDE).unwrap().unwrap().agent_origin,
        Some(roboco_proto::AgentOrigin {
            chat_id: PARENT.into(),
            notify: false,
        })
    );

    queue_run(&client, SIDE, "fire and forget", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "a notify: false child must never wake its creator"
    );
    assert_eq!(scripted.run_count(), 1, "no parent turn was started");

    core.shutdown().await;
}

/// A steer the parent sent WITHIN a user-started turn makes that turn
/// notifiable: the notice fires when the steered turn completes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_parent_steer_within_the_turn_makes_it_notifiable() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The user starts the child's turn; it hangs.
        Turn::Hang,
        // The parent's notice turn, after the steered turn completes.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let registry = HarnessRegistry::new();
    registry.register(scripted.clone());
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // The user's opening turn — no origin, not notifiable by itself.
    queue_run(&client, SIDE, "typed by the user", "m-open", None).await;
    wait_for(
        || scripted.prompts_containing("typed by the user"),
        "the user's turn to start",
    )
    .await;
    wait_for(
        || {
            core.sessions
                .session_status(SIDE)
                .is_some_and(|s| s.status == SessionStatus::Working)
        },
        "the child to be working",
    )
    .await;

    // The parent steers mid-run: the steer joins the turn in flight.
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": SIDE,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Steer {
                    prompt: "use the other approach".into(),
                    message_id: Some("m-steer-1".into()),
                })
                .unwrap(),
                "originChatId": PARENT,
            }),
        )
        .await
        .expect("queue steer");
    // The mock consumes the steer and emits its Steered boundary.
    wait_for(
        || {
            scripted
                .steered
                .lock()
                .unwrap()
                .iter()
                .any(|p| p.contains("use the other approach"))
        },
        "the steer to be consumed",
    )
    .await;
    // The user entry the steer wrote carries the parent's origin.
    wait_for(
        || {
            entries(&core, SIDE)
                .iter()
                .any(|e| e.origin_chat_id.as_deref() == Some(PARENT))
        },
        "the steer's user entry to land",
    )
    .await;

    // The steered turn completes at the mock's Done: the parent is woken by
    // the completion notice.
    wait_for(
        || scripted.prompts_containing("finished"),
        "the completion notice to start a parent turn",
    )
    .await;
    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    assert_eq!(
        notice_sides(&notices[0]),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        }]
    );
    assert_eq!(scripted.run_count(), 2);

    core.shutdown().await;
}
