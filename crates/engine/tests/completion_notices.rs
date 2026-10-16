//! Completion notices (orchestration v2, tickets 12 + 13).
//!
//! Ticket 12's tracer bullet for the spec's completion-notice stories
//! 13–17 and 19:
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
//!
//! Ticket 13's rules on top:
//! - a BUSY parent is never steered: the notice waits for the parent's
//!   turn to end, then starts its next turn;
//! - several children finishing while the parent works merge into ONE
//!   notice;
//! - pending outcomes survive an engine restart and are delivered after
//!   boot;
//! - a notifiable run lost to a restart without auto-resume yields an
//!   INTERRUPTED outcome; with auto-resume, the notice follows the resumed
//!   turn;
//! - archived parents receive nothing and archived side chats send
//!   nothing.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{
    MessagePart, MessageRole, MessageStatus, NoticeOutcome, NoticeSide, SessionCommandPayload,
    SessionDoc, SessionMessageEntry,
};
use roboco_engine::{EngineCore, HarnessRegistry, RunJournal};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SessionStatus, SteeringMode, UserInputQuestion,
};
use roboco_sync::DocsStore;

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
    /// work finishes). A Stop (the engine's interrupt token) ends the
    /// parked turn as Interrupted, the way a real harness does.
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
        let interrupt = controls.interrupt;
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
                        _ = interrupt.cancelled() => {
                            // A Stop: the turn ends interrupted, the runtime
                            // goes with it.
                            let _ = tx
                                .send(Ok(AgentEvent::Done {
                                    status: DoneStatus::Interrupted,
                                    result: None,
                                    error: None,
                                    session_id: Some(session),
                                }))
                                .await;
                            break;
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
        provider_fork: None,
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

/// Create the orchestrating parent: a cwd'd, titled chat a notice turn can
/// run on.
async fn create_parent(core: &EngineCore, client: &roboco_rpc::RpcClient) {
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
}

/// Create an agent-created, notifying side chat under the parent: titled,
/// with a cwd (a boot auto-resume re-derives its run config from the row)
/// and the `notify` opt in/out.
async fn create_side(
    core: &EngineCore,
    client: &roboco_rpc::RpcClient,
    chat_id: &str,
    title: &str,
    notify: Option<bool>,
) {
    let mut agent_origin = serde_json::json!({ "chatId": PARENT });
    if let Some(notify) = notify {
        agent_origin["notify"] = serde_json::json!(notify);
    }
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({
                "op": "createChat",
                "chatId": chat_id,
                "deviceId": core.device_id,
                "parentChatId": PARENT,
                "cwd": "/tmp",
                "agentOrigin": agent_origin,
            }),
        )
        .await
        .expect("createChat side");
    core.workspace.rename_chat(chat_id, title).unwrap();
}

/// The ticket-12 shape: one parent and one titled side chat.
async fn create_parent_and_side(
    core: &EngineCore,
    client: &roboco_rpc::RpcClient,
    notify: Option<bool>,
) {
    create_parent(core, client).await;
    create_side(core, client, SIDE, "Implement login", notify).await;
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

/// Assemble an engine over `dir` whose runs the Scripted mock serves.
fn assemble(dir: &std::path::Path, scripted: Arc<Scripted>) -> EngineCore {
    let registry = HarnessRegistry::new();
    registry.register(scripted);
    EngineCore::assemble(dir, Arc::new(registry), HarnessId::Mock).expect("engine core assembles")
}

/// A user's steer (no origin chat): the queue routes it into a working
/// chat's mailbox, and the mock's Hang turn consumes it and settles — the
/// way a working parent's turn ends.
async fn queue_user_steer(
    client: &roboco_rpc::RpcClient,
    chat: &str,
    prompt: &str,
    message_id: &str,
) {
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": chat,
                "command": serde_json::to_value(SessionCommandPayload::Steer {
                    prompt: prompt.into(),
                    message_id: Some(message_id.into()),
                })
                .unwrap(),
            }),
        )
        .await
        .expect("queue steer");
}

/// Rows the durable completion-notice ledger under this engine's data dir
/// currently holds — the engine-local surface restart round-trips assert
/// on, like the run journals.
fn ledger_rows(data_dir: &std::path::Path) -> usize {
    std::fs::read(data_dir.join("orgs/dev-org/dev-user/completion-notices.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .and_then(|ledger| {
            ledger.as_object().map(|parents| {
                parents
                    .values()
                    .map(|rows| rows.as_array().map_or(0, Vec::len))
                    .sum()
            })
        })
        .unwrap_or(0)
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

/// A busy parent is never steered: the child's outcome waits for the
/// parent's turn to end, and the notice then starts the parent's NEXT turn
/// (ticket 13).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_working_parent_receives_the_notice_after_its_turn_as_its_next_turn() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn: hangs until the user steers it.
        Turn::Hang,
        // The side chat's turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The parent's notice turn, after its turn ends.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // The user starts the parent's turn; it hangs (the parent is working).
    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;

    // The parent starts the child's turn through the MCP surface; the
    // child completes while the parent still works.
    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;

    // No notice while the parent is busy: nothing steered in, no parent
    // turn started, no entry written. The outcome is recorded durably
    // (one ledger row) before the parent's turn ends.
    assert!(
        core.sessions.turn_in_flight(PARENT),
        "the parent is still working"
    );
    assert!(notice_entries(&core, PARENT).is_empty());
    assert_eq!(scripted.run_count(), 2);
    assert!(
        scripted.steered.lock().unwrap().is_empty(),
        "nothing was steered into the parent mid-turn"
    );
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the child's outcome to be recorded durably",
    )
    .await;

    // The user's steer ends the parent's turn.
    queue_user_steer(&client, PARENT, "please wrap up", "m-steer-1").await;
    wait_for(
        || scripted.prompts_containing("finished"),
        "the notice to start the parent's next turn",
    )
    .await;

    // ONE notice, delivered as the parent's next turn — never steered in.
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
    assert_eq!(scripted.run_count(), 3);
    assert_eq!(
        scripted.steered.lock().unwrap().clone(),
        vec!["please wrap up".to_string()],
        "the only consumed steer is the user's"
    );
    wait_idle(&core, PARENT).await;

    core.shutdown().await;
}

/// Three children finishing while the parent works produce ONE merged
/// notice naming each of them (ticket 13).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn three_children_finishing_while_the_parent_works_merge_into_one_notice() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn.
        Turn::Hang,
        // The three children's turns.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The parent's merged notice turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent(&core, &client).await;
    let sides = [
        ("chat-side-1", "Implement login"),
        ("chat-side-2", "Write the docs"),
        ("chat-side-3", "Run the tests"),
    ];
    for (chat_id, title) in sides {
        create_side(&core, &client, chat_id, title, None).await;
    }

    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    for (index, (chat_id, _)) in sides.iter().enumerate() {
        queue_run(
            &client,
            chat_id,
            "please do your part",
            &format!("m-run-{index}"),
            Some(PARENT),
        )
        .await;
    }
    for (chat_id, _) in sides {
        wait_idle(&core, chat_id).await;
    }

    // All three outcomes are owed while the parent works: no notice yet,
    // and all three are recorded durably before the turn ends — the merged
    // notice is what the ledger holds at the boundary.
    assert!(core.sessions.turn_in_flight(PARENT));
    assert!(notice_entries(&core, PARENT).is_empty());
    assert_eq!(scripted.run_count(), 4);
    wait_for(
        || ledger_rows(dir.path()) == 3,
        "the three children's outcomes to be recorded durably",
    )
    .await;

    // The user's steer ends the parent's turn: the merged notice is the
    // next one.
    queue_user_steer(&client, PARENT, "please wrap up", "m-steer-1").await;
    wait_for(
        || scripted.prompts_containing("3 side chats reached outcomes"),
        "the merged notice to start the parent's next turn",
    )
    .await;

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1, "one merged notice, not one per child");
    let mut rows = notice_sides(&notices[0]);
    rows.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
    let mut expected: Vec<NoticeSide> = sides
        .iter()
        .map(|(chat_id, title)| NoticeSide {
            chat_id: (*chat_id).into(),
            title: (*title).into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        })
        .collect();
    expected.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
    assert_eq!(rows, expected);
    let text = notice_text(&notices[0]);
    assert!(text.starts_with("3 side chats reached outcomes:"));
    for (chat_id, title) in sides {
        assert!(text.contains(&format!("\"{title}\" ({}) finished", &chat_id[..8])));
    }
    assert!(text.contains("Read each chat's full result"));
    assert_eq!(
        scripted.run_count(),
        5,
        "the parent ran its own turn and one notice turn"
    );
    wait_idle(&core, PARENT).await;

    core.shutdown().await;
}

/// A pending notice survives an engine restart: the outcome a busy parent
/// never received is loaded from the durable ledger and delivered after
/// boot (restart-resume prior art: shutdown → relaunch over one dir).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_pending_notice_survives_an_engine_restart_and_is_delivered_after_boot() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("data");
    std::fs::create_dir_all(&dir).unwrap();

    // Engine 1: the child completes while the parent works, so the outcome
    // is recorded into the durable ledger and stays pending.
    let first = Scripted::new(vec![
        Turn::Hang,
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(&dir, first.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;
    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || first.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;
    assert!(core.sessions.turn_in_flight(PARENT));
    assert!(notice_entries(&core, PARENT).is_empty());
    // The outcome is recorded durably before the engine dies — that is the
    // whole point of the ledger.
    wait_for(
        || ledger_rows(&dir) == 1,
        "the child's outcome to be recorded durably",
    )
    .await;

    // The engine dies: shutdown interrupts the parent's turn, and an
    // interrupted turn wakes nobody, so the notice stays pending.
    core.shutdown().await;
    drop(core);

    // Engine 2 over the same data dir: the ledger survives, and the notice
    // is delivered after boot.
    let second = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let core = assemble(&dir, second.clone());
    wait_for(
        || second.prompts_containing("finished"),
        "the pending notice to be delivered after boot",
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
    assert_eq!(
        second.run_count(),
        1,
        "the notice turn is the relaunch's only run"
    );

    core.shutdown().await;
}

/// The kill -9 state for a notifiable side-chat run: rows from a live
/// engine, the doc and journal written by hand (restart-resume prior art).
/// `age_ms` backs the crash, deciding whether boot recovery auto-resumes
/// the run; the side chat's last user message carries the parent origin.
fn manufacture_lost_side_chat_run(dir: &std::path::Path, device_id: &str, age_ms: i64) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let store = DocsStore::open(dir.join("orgs/dev-org/dev-user")).unwrap();
    let doc = SessionDoc::init(SIDE).unwrap();
    doc.push_message(&SessionMessageEntry {
        origin_chat_id: Some(PARENT.into()),
        id: "m-run-1".into(),
        role: MessageRole::User,
        parts: vec![MessagePart::Text {
            id: "t0".into(),
            text: "please implement login".into(),
        }],
        created_at: now - age_ms,
        device_id: device_id.into(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        duration_ms: None,
        provider_turn: None,
    })
    .unwrap();
    store
        .save_snapshot(SIDE, &doc.export_snapshot().unwrap())
        .unwrap();
    let journal = RunJournal::open(dir.join("orgs/dev-org/dev-user/journals")).unwrap();
    journal
        .append(
            SIDE,
            &AgentEvent::SessionStarted {
                harness: HarnessId::Mock,
                model: "mock-1".into(),
                tools: vec![],
                cwd: "/tmp".into(),
                session_id: "hs-side".into(),
                assistant_message_id: "a-1".into(),
            },
        )
        .unwrap();
}

/// A child run lost to a restart with NO auto-resume (the crashed turn is
/// older than the revival window) yields an INTERRUPTED outcome at boot:
/// the parent is told the work never finished, and it may restart it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_run_lost_to_a_restart_without_auto_resume_notifies_interrupted() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("data");
    std::fs::create_dir_all(&dir).unwrap();

    // Engine 1 creates the rows only.
    {
        let scripted = Scripted::new(vec![]);
        let core = assemble(&dir, scripted);
        let client = roboco_rpc::memory_client(core.rpc_service());
        create_parent_and_side(&core, &client, None).await;
        let device_id = core.device_id.clone();
        core.shutdown().await;
        // The run died 13h ago — outside the 12h auto-resume window.
        manufacture_lost_side_chat_run(&dir, &device_id, 13 * 60 * 60 * 1000);
    }

    // Engine 2: recovery records the interrupted outcome, and the boot
    // delivery wakes the parent with it.
    let scripted = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let core = assemble(&dir, scripted.clone());
    wait_for(
        || scripted.prompts_containing("was interrupted"),
        "the interrupted notice to be delivered after boot",
    )
    .await;
    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    assert_eq!(
        notice_sides(&notices[0]),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Interrupted,
            detail: Some("engine restart".into()),
        }]
    );
    let text = notice_text(&notices[0]);
    assert!(text.contains("was interrupted: engine restart"));
    assert!(text.contains("read_chat"));
    // The lost run was not revived: the notice turn is the only run.
    assert_eq!(scripted.run_count(), 1);

    core.shutdown().await;
}

/// A child run lost to a restart WITH auto-resume (a fresh crash) owes no
/// interrupted outcome: the revival re-dispatch keeps the starting
/// message's parent origin, so the notice follows the RESUMED turn's end.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_auto_resumed_run_notifies_when_the_resumed_turn_ends() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("data");
    std::fs::create_dir_all(&dir).unwrap();

    // Engine 1 creates the rows only.
    {
        let scripted = Scripted::new(vec![]);
        let core = assemble(&dir, scripted);
        let client = roboco_rpc::memory_client(core.rpc_service());
        create_parent_and_side(&core, &client, None).await;
        let device_id = core.device_id.clone();
        core.shutdown().await;
        // The run died a minute ago — inside the revival window.
        manufacture_lost_side_chat_run(&dir, &device_id, 60_000);
    }

    // Engine 2: the crashed run is picked back up (staggered start) under
    // the same user message, and the notice follows its completion.
    let scripted = Scripted::new(vec![
        // The revived side-chat turn.
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
    let core = assemble(&dir, scripted.clone());
    wait_for(
        || scripted.prompts_containing("please implement login"),
        "the crashed turn to be auto-resumed",
    )
    .await;
    wait_for(
        || scripted.prompts_containing("finished"),
        "the completion notice to reach the parent",
    )
    .await;
    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1);
    assert_eq!(
        notice_sides(&notices[0]),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            // The notice reports the RESUMED turn's completion, not an
            // interruption.
            outcome: NoticeOutcome::Completed,
            detail: None,
        }]
    );
    let text = notice_text(&notices[0]);
    assert!(!text.contains("interrupted"));
    // The revival ran the crashed prompt; the notice is the second turn.
    assert_eq!(scripted.run_count(), 2);
    assert_eq!(
        scripted.prompts.lock().unwrap()[0],
        "please implement login",
        "the revival re-dispatched the crashed turn's own message"
    );

    core.shutdown().await;
}

/// An archived parent receives nothing: new outcomes are never recorded
/// for it (ticket 13).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_archived_parent_receives_no_notices() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;
    core.workspace.set_chat_archived(PARENT, true).unwrap();

    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;
    // Let a wrongly recorded outcome settle before asserting absence.
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "an archived parent must receive no notices"
    );
    assert_eq!(scripted.run_count(), 1, "no parent turn was started");

    core.shutdown().await;
}

/// An archived side chat sends nothing: archiving the child mid-run
/// suppresses the outcome its stopped turn would otherwise owe (an
/// interrupt is not a message, so it never revives the archived chat).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_archived_side_chat_sends_no_notices() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![Turn::Hang]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || {
            core.sessions
                .session_status(SIDE)
                .is_some_and(|s| s.status == SessionStatus::Working)
        },
        "the side chat's turn to start",
    )
    .await;
    // Archive the child while its turn runs; a Stop ends the turn.
    core.workspace.set_chat_archived(SIDE, true).unwrap();
    core.doc_host
        .queue_command(SIDE, SessionCommandPayload::Interrupt {})
        .unwrap();
    wait_idle(&core, SIDE).await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "an archived side chat must send no notices"
    );
    assert_eq!(scripted.run_count(), 1, "no parent turn was started");

    core.shutdown().await;
}

/// Outcomes already owed to a parent when it is ARCHIVED are dropped, not
/// held: after a restart the boot delivery finds the parent archived and
/// the ledger forgets its rows — an archived parent receives nothing even
/// from the pending set (ticket 13's delivery-path rule, beside the
/// record-time one).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn notices_owed_to_a_parent_archived_before_delivery_are_dropped_at_boot() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path().join("data");
    std::fs::create_dir_all(&dir).unwrap();

    // Engine 1: the child completes while the parent works, so the outcome
    // is recorded — the parent is owed it — and then the parent is
    // archived before it could ever receive the notice.
    let first = Scripted::new(vec![
        Turn::Hang,
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(&dir, first.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;
    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || first.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_idle(&core, SIDE).await;
    wait_for(
        || ledger_rows(&dir) == 1,
        "the child's outcome to be recorded durably",
    )
    .await;
    core.workspace.set_chat_archived(PARENT, true).unwrap();
    core.shutdown().await;
    drop(core);

    // Engine 2: the boot delivery finds the archived parent and drops the
    // owed outcome — no notice turn ever starts, and the ledger is empty.
    let second = Scripted::new(vec![Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let core = assemble(&dir, second.clone());
    wait_for(
        || ledger_rows(&dir) == 0,
        "the archived parent's owed outcomes to be dropped",
    )
    .await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "an archived parent receives nothing, even outcomes it was already owed"
    );
    assert_eq!(
        second.run_count(),
        0,
        "no notice turn was started for the archived parent"
    );

    core.shutdown().await;
}
