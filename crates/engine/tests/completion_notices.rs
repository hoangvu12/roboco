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
const SIDE2: &str = "chat-side-2";

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

    /// How many runs so far carried `needle` in their prompt.
    fn prompt_count(&self, needle: &str) -> usize {
        self.prompts
            .lock()
            .unwrap()
            .iter()
            .filter(|p| p.contains(needle))
            .count()
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
        // Take the turn before recording the prompt: a test that sees this
        // prompt may start the next run at once, and that run must never
        // take the turn scripted for this one.
        let turn = self
            .turns
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or(Turn::Hang);
        self.prompts.lock().unwrap().push(request.prompt.clone());
        let session = format!("sess-{}", self.run_count());
        let (tx, rx) = tokio::sync::mpsc::channel::<Result<AgentEvent, HarnessError>>(32);
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
        Ok(futures::stream::unfold(rx, |mut rx| async move {
            rx.recv().await.map(|event| (event, rx))
        })
        .boxed())
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
        resume_fallback_history: None,
        attachments: vec![],
        worktree: None,
    }
}

async fn wait_for<F>(mut predicate: F, what: &str)
where
    F: FnMut() -> bool,
{
    // 30 s: the suite boots ~25 engines in parallel on a small CI runner.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
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

/// Queue a Run for `chat` and wait for THAT run to start and settle. A bare
/// `wait_idle` right after the queue can pass on the chat's previous Idle
/// row before this run even dispatched; the run's own prompt reaching the
/// harness means its Working status already published (dispatch sets it
/// before the run task starts), so the idle wait after it is this run's.
async fn run_to_idle(
    core: &EngineCore,
    client: &roboco_rpc::RpcClient,
    scripted: &Scripted,
    chat: &str,
    prompt: &str,
    message_id: &str,
    origin_chat_id: Option<&str>,
) {
    let before = scripted.prompt_count(prompt);
    queue_run(client, chat, prompt, message_id, origin_chat_id).await;
    wait_for(
        || scripted.prompt_count(prompt) > before,
        &format!("the run of {prompt:?} to start"),
    )
    .await;
    wait_idle(core, chat).await;
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
/// on, like the run journals. Ticket 14 wrapped the ledger as
/// `{ pending, loops }`; ticket 13's was a bare parent → rows map. Both
/// shapes read.
fn ledger_rows(data_dir: &std::path::Path) -> usize {
    std::fs::read(data_dir.join("orgs/dev-org/dev-user/completion-notices.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .and_then(|ledger| {
            let parents = ledger.get("pending").cloned().unwrap_or(ledger);
            parents.as_object().map(|parents| {
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
    run_to_idle(&core, &client, &scripted, SIDE, "typed by the user", "m-run-1", None).await;
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
    run_to_idle(&core, &client, &scripted, SIDE, "sent by a chat anyway", "m-run-1", Some(PARENT)).await;
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

    run_to_idle(&core, &client, &scripted, SIDE, "fire and forget", "m-run-1", Some(PARENT)).await;
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
    run_to_idle(&core, &client, &scripted, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;

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
    // Every child's run dispatched (its Working row published) before the
    // idle waits read the rows.
    wait_for(
        || scripted.prompt_count("please do your part") == sides.len(),
        "every child's turn to start",
    )
    .await;
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
    run_to_idle(&core, &client, &first, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
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

    run_to_idle(&core, &client, &scripted, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    // Let a wrongly recorded outcome settle before asserting absence.
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "an archived parent must receive no notices"
    );
    assert_eq!(scripted.run_count(), 1, "no parent turn was started");

    core.shutdown().await;
}

/// A deleted parent is owed nothing (ticket 13): the delete path drops the
/// parent's pending outcomes — the durable ledger forgets them, and a
/// delivery attempt for a chat whose row is gone stays quiet instead of
/// waking a chat that no longer exists.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_deleted_parent_is_owed_nothing() {
    let dir = tempfile::tempdir().unwrap();
    // The child's turn, the parent's working turn (Hang), then nothing for
    // the parent — the parked notice's turn never happens.
    let scripted = Scripted::new(vec![Turn::Hang, Turn::End {
        status: DoneStatus::Completed,
        error: None,
    }]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // The parent works; the child's outcome is recorded and stays pending.
    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    run_to_idle(&core, &client, &scripted, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the child's outcome to be recorded",
    )
    .await;

    // The parent is deleted through the RPC (the delete path purges the
    // chat, dropping what it is owed).
    client
        .call(
            roboco_rpc::methods::MUTATE,
            serde_json::json!({ "op": "deleteChat", "chatId": PARENT }),
        )
        .await
        .expect("deleteChat");
    wait_for(
        || ledger_rows(dir.path()) == 0,
        "the deleted parent's pending outcomes to go with the chat",
    )
    .await;

    // Nothing wakes the gone chat: no notice turn was started for it.
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(
        scripted.run_count(),
        2,
        "a deleted parent receives nothing: no notice turn started"
    );
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
    run_to_idle(&core, &client, &first, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
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

// ---- ticket 14: acknowledgement, stop, loop cap ---------------------------

/// Report an observation the way the Roboco MCP server does (ticket 14):
/// the parent — the server's calling chat — observed `side` after its last
/// outcome happened, through `wait_for_turn`, a send with wait, `read_chat`
/// or `get_chat`. All four arrive as this one RPC.
async fn acknowledge(client: &roboco_rpc::RpcClient, side: &str, parent: &str) {
    acknowledge_at(client, side, parent, None).await;
}

/// The observation report with the moment the observation STARTED (the
/// shape the Roboco MCP server sends for `read_chat`/`get_chat`/waits):
/// the engine's watermark covers only the settles that had happened by
/// then, so an outcome landing between the read and this report — the
/// parent's unseen work — survives it.
async fn acknowledge_at(
    client: &roboco_rpc::RpcClient,
    side: &str,
    parent: &str,
    observed_at: Option<i64>,
) {
    let mut params = serde_json::json!({ "chatId": side, "originChatId": parent });
    if let Some(at) = observed_at {
        params["observedAt"] = serde_json::json!(at);
    }
    client
        .call(roboco_rpc::methods::ACK_SIDE_CHAT, params)
        .await
        .expect("report observation");
}

/// The user's Stop on a chat (the composer's and the MCP `interrupt_chat`
/// tool's Interrupt command).
fn stop(core: &EngineCore, chat: &str) {
    core.doc_host
        .queue_command(chat, SessionCommandPayload::Interrupt {})
        .expect("queue interrupt");
}

/// The paused queue rows a chat currently shows (WatchQueue's surface).
fn queue_rows(core: &EngineCore, chat: &str) -> Vec<(String, String)> {
    queue_rows_with_sides(core, chat)
        .into_iter()
        .map(|(id, text, _)| (id, text))
        .collect()
}

/// The queue rows with the completion-notice sides a parked notice row
/// carries (ticket 14): the row is the notice's durable carrier while it
/// waits, and its release writes the entry those sides name.
fn queue_rows_with_sides(
    core: &EngineCore,
    chat: &str,
) -> Vec<(String, String, Option<Vec<NoticeSide>>)> {
    core.doc_host
        .open(chat)
        .expect("chat doc")
        .doc()
        .read_queue()
        .expect("queue")
        .into_iter()
        .map(|row| (row.id, row.text, row.completion_notice))
        .collect()
}

/// A parent that waited with `wait_for_turn` — and got the child's
/// completed result — receives no notice for it: the outcome it observed
/// after it happened is dropped, so the parent's next turn end delivers
/// nothing (the same report covers `read_chat` and `get_chat`: all three
/// arrive as the observation RPC).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_observed_outcome_owes_no_notice() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn: hangs until the user steers it.
        Turn::Hang,
        // The side chat's turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // The parent works; the child's parent-started turn completes while it
    // does, so the outcome is recorded and stays pending.
    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    run_to_idle(&core, &client, &scripted, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the child's outcome to be recorded durably",
    )
    .await;

    // The parent waits (or reads) — the observation is reported for it.
    acknowledge(&client, SIDE, PARENT).await;

    // The parent's turn ends: the pending outcome is gone, so no notice
    // turn starts.
    queue_user_steer(&client, PARENT, "please wrap up", "m-steer-1").await;
    wait_idle(&core, PARENT).await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "an outcome the parent observed owes no notice"
    );
    assert_eq!(
        ledger_rows(dir.path()),
        0,
        "the observed outcome is dropped from the ledger"
    );
    assert_eq!(
        scripted.run_count(),
        2,
        "only the parent's and the child's turns ran — no notice turn"
    );

    core.shutdown().await;
}

/// The observation covers exactly the settles it saw: a LATER turn of the
/// same side chat still owes its notice.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_outcome_after_the_observation_still_notifies() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn.
        Turn::Hang,
        // The child's first turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The child's second turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The parent's notice turn, for the second outcome only.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    run_to_idle(&core, &client, &scripted, SIDE, "first task", "m-run-1", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the first outcome to be recorded",
    )
    .await;

    // The parent observed the chat after the first turn ended.
    acknowledge(&client, SIDE, PARENT).await;
    assert_eq!(ledger_rows(dir.path()), 0);

    // A later turn of the same child settles while the parent still works:
    // its outcome is past the observation's watermark, so it is owed.
    run_to_idle(&core, &client, &scripted, SIDE, "second task", "m-run-2", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the second outcome to be recorded",
    )
    .await;

    // The parent's turn ends: the second outcome reaches it.
    queue_user_steer(&client, PARENT, "please wrap up", "m-steer-1").await;
    wait_for(
        || scripted.prompts_containing("finished"),
        "the second outcome's notice to start a parent turn",
    )
    .await;
    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1, "one notice, for the second outcome only");
    assert_eq!(
        notice_sides(&notices[0]),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        }]
    );
    assert_eq!(scripted.run_count(), 4);

    core.shutdown().await;
}

/// The report carries WHEN the observation happened (ticket 14): a settle
/// that lands between the read and the report is the parent's UNSEEN
/// outcome — the ack must not drop it, or a busy child's report races away
/// its own completion notice.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_late_observation_report_never_drops_an_unseen_outcome() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn.
        Turn::Hang,
        // The child's first turn (seen by the observation).
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The child's second turn (settles between the read and the report).
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // The parent's notice turn, for the second outcome only.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    run_to_idle(&core, &client, &scripted, SIDE, "first task", "m-run-1", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "the first outcome to be recorded",
    )
    .await;

    // The parent's read of the chat happens HERE. Millisecond separation is
    // forced on both sides so the settle marks order unambiguously.
    tokio::time::sleep(Duration::from_millis(5)).await;
    let observed_at = chrono::Utc::now().timestamp_millis();
    tokio::time::sleep(Duration::from_millis(5)).await;

    // The child's second turn settles after the read: unseen by it.
    run_to_idle(&core, &client, &scripted, SIDE, "second task", "m-run-2", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 2,
        "the second outcome to be recorded",
    )
    .await;

    // The report arrives now — long after the read it speaks for. Only the
    // settle the read saw is covered; the second outcome is owed.
    acknowledge_at(&client, SIDE, PARENT, Some(observed_at)).await;
    assert_eq!(
        ledger_rows(dir.path()),
        1,
        "the unseen outcome survives the late report; the seen one drops"
    );

    // The parent's turn ends: the notice covers the second outcome.
    queue_user_steer(&client, PARENT, "please wrap up", "m-steer-1").await;
    wait_for(
        || scripted.prompts_containing("finished"),
        "the unseen outcome's notice to start a parent turn",
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
        }],
        "the notice reports the unseen outcome only"
    );
    assert_eq!(scripted.run_count(), 4);

    core.shutdown().await;
}

/// Stopping the parent drops the notices owed to it and suppresses
/// outcomes from side chats whose notifiable turns were running at that
/// moment: the children keep running (Stop does not cascade), and their
/// later completions do not wake the stopped parent.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stopping_the_parent_drops_pending_notices_and_suppresses_running_children() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn.
        Turn::Hang,
        // Child A: completes while the parent works (a pending outcome).
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
        // Child B: still running when the parent is stopped, steered to its
        // completion afterwards.
        Turn::Hang,
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent(&core, &client).await;
    create_side(&core, &client, "chat-side-a", "Implement login", None).await;
    create_side(&core, &client, "chat-side-b", "Write the docs", None).await;

    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    run_to_idle(&core, &client, &scripted, "chat-side-a", "please implement login", "m-run-a", Some(PARENT)).await;
    wait_for(
        || ledger_rows(dir.path()) == 1,
        "child A's outcome to be recorded durably",
    )
    .await;
    queue_run(&client, "chat-side-b", "please write the docs", "m-run-b", Some(PARENT)).await;
    wait_for(
        || {
            core.sessions
                .session_status("chat-side-b")
                .is_some_and(|s| s.status == SessionStatus::Working)
        },
        "child B's turn to be running",
    )
    .await;

    // The user stops the parent.
    stop(&core, PARENT);
    wait_idle(&core, PARENT).await;
    wait_for(
        || ledger_rows(dir.path()) == 0,
        "the parent's pending notices to be dropped",
    )
    .await;
    // Child B keeps running — Stop did not cascade.
    assert_eq!(
        core.sessions
            .session_status("chat-side-b")
            .map(|s| s.status),
        Some(SessionStatus::Working),
        "the side chat keeps running across the parent's Stop"
    );

    // Child B's turn completes later: its outcome was suppressed at the
    // Stop, so the stopped parent is never woken.
    queue_user_steer(&client, "chat-side-b", "please wrap up", "m-steer-b").await;
    wait_idle(&core, "chat-side-b").await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(
        notice_entries(&core, PARENT).is_empty(),
        "a stopped parent receives no notices, and a running child's later \
         completion does not wake it"
    );
    assert_eq!(ledger_rows(dir.path()), 0);
    assert_eq!(
        scripted.run_count(),
        3,
        "the parent's and the two children's turns ran — no notice turn"
    );

    core.shutdown().await;
}

/// A later message from the parent to that child re-arms notices for its
/// next turn (ticket 14's re-arming rule): the steer joins the running
/// child, arms the turn again, and its completion wakes the parent.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_later_parent_message_re_arms_the_child() {
    let dir = tempfile::tempdir().unwrap();
    let scripted = Scripted::new(vec![
        // The parent's working turn.
        Turn::Hang,
        // The child's turn: hangs across the parent's Stop, then steered by
        // the re-engaging parent and completed.
        Turn::Hang,
        // The parent's notice turn.
        Turn::End {
            status: DoneStatus::Completed,
            error: None,
        },
    ]);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    queue_run(&client, PARENT, "orchestrate the release", "m-parent-1", None).await;
    wait_for(
        || scripted.prompts_containing("orchestrate the release"),
        "the parent's turn to start",
    )
    .await;
    queue_run(&client, SIDE, "please implement login", "m-run-1", Some(PARENT)).await;
    wait_for(
        || {
            core.sessions
                .session_status(SIDE)
                .is_some_and(|s| s.status == SessionStatus::Working)
        },
        "the child's turn to be running",
    )
    .await;

    // The user stops the parent; the child's turn keeps running, its
    // outcome suppressed.
    stop(&core, PARENT);
    wait_idle(&core, PARENT).await;
    assert_eq!(
        core.sessions.session_status(SIDE).map(|s| s.status),
        Some(SessionStatus::Working)
    );

    // The parent resumes orchestrating: its message steers the running
    // child, re-arming the turn's notifiability.
    queue_run(&client, SIDE, "round two, please", "m-run-2", Some(PARENT)).await;
    wait_for(
        || scripted.prompts_containing("finished"),
        "the re-armed turn's completion notice to wake the parent",
    )
    .await;

    let notices = notice_entries(&core, PARENT);
    assert_eq!(notices.len(), 1, "the re-armed turn notifies again");
    assert_eq!(
        notice_sides(&notices[0]),
        vec![NoticeSide {
            chat_id: SIDE.into(),
            title: "Implement login".into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        }]
    );
    // The parent's turn, the child's turn (steered in place) and the notice
    // turn: the child never restarted.
    assert_eq!(scripted.run_count(), 3);
    assert!(
        scripted
            .steered
            .lock()
            .unwrap()
            .iter()
            .any(|p| p.contains("round two, please")),
        "the parent's message steered the running child"
    );

    core.shutdown().await;
}

/// The 26th consecutive notice-started turn does not start: the notice
/// waits visibly in the parent's queue, paused, until the user acts. A user
/// message resets the count and thaws the queue, and the parked row then
/// runs as the parent's next turn (counting as notice-started itself).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_loop_cap_parks_the_26th_notice_until_the_user_acts() {
    let dir = tempfile::tempdir().unwrap();
    // 27 child turns (25 rounds + the capped 26th + the post-reset 27th)
    // + 26 notice turns + an agent's message to the parent + the user's
    // message + the parked row's own turn — every run pops one scripted
    // turn.
    let turns: Vec<Turn> = (0..27 + 26 + 1 + 1 + 1)
        .map(|_| Turn::End {
            status: DoneStatus::Completed,
            error: None,
        })
        .collect();
    let scripted = Scripted::new(turns);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // 25 rounds: the parent orchestrates, the child completes, the notice
    // starts the parent's next turn. The parent never sees a user message.
    for round in 0..25 {
        run_to_idle(
            &core,
            &client,
            &scripted,
            SIDE,
            &format!("round {round}"),
            &format!("m-run-{round}"),
            Some(PARENT),
        )
        .await;
        wait_for(
            || notice_entries(&core, PARENT).len() == round + 1,
            &format!("round {round}'s notice to be written"),
        )
        .await;
        // The notice turn itself dispatched (child + notice per round)
        // before the idle wait reads the parent's row.
        wait_for(
            || scripted.run_count() == 2 * (round + 1),
            &format!("round {round}'s notice turn to start"),
        )
        .await;
        wait_idle(&core, PARENT).await;
    }
    assert_eq!(notice_entries(&core, PARENT).len(), 25);
    assert_eq!(scripted.run_count(), 50);

    // Round 26: the child completes, but the 26th notice-started turn does
    // not start — the notice parks as a paused queue row.
    run_to_idle(&core, &client, &scripted, SIDE, "round 26", "m-run-26", Some(PARENT)).await;
    wait_for(
        || queue_rows(&core, PARENT).len() == 1,
        "the capped notice to park in the parent's queue",
    )
    .await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(
        notice_entries(&core, PARENT).len(),
        25,
        "no 26th notice entry — the notice did not start a turn"
    );
    assert_eq!(
        scripted.run_count(),
        51,
        "the 26th notice turn never ran (25 child + 25 notice + the 26th child)"
    );
    let rows = queue_rows_with_sides(&core, PARENT);
    assert_eq!(rows.len(), 1, "exactly one parked notice row");
    assert!(
        rows[0].0.starts_with("notice:"),
        "the parked row carries the engine's notice prefix: {}",
        rows[0].0
    );
    assert!(
        rows[0].1.contains("Implement login"),
        "the parked row shows the notice itself: {}",
        rows[0].1
    );
    assert!(
        rows[0].2.is_some(),
        "the parked row carries the notice's sides, so its release writes the
         same System completion-notice entry a direct delivery writes"
    );

    // An agent's message to the parent (a chat-origin Run, the MCP
    // `send_message` surface) is NOT the user acting: it must not thaw the
    // cap's pause, or a busy child lifts the brake that waits for the user
    // (ticket 14's stopped-parent cap rule). The message still runs as its
    // own turn — the queue, and the parked notice with it, stays held.
    queue_run(&client, PARENT, "an agent nudges the parent", "m-agent", Some(SIDE)).await;
    wait_for(
        || scripted.prompts_containing("an agent nudges the parent"),
        "the agent's turn to start",
    )
    .await;
    wait_idle(&core, PARENT).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(
        queue_rows(&core, PARENT).len(),
        1,
        "an agent-origin message does not release the cap's parked notice"
    );
    assert_eq!(
        notice_entries(&core, PARENT).len(),
        25,
        "the parked notice did not run on an agent's message"
    );

    // The user acts: their message resets the count, thaws the queue, and
    // runs as the parent's turn; the parked notice row follows it out.
    queue_run(&client, PARENT, "user speaks now", "m-user", None).await;
    wait_for(
        || scripted.prompts_containing("user speaks now"),
        "the user's turn to start",
    )
    .await;
    wait_idle(&core, PARENT).await;
    wait_for(
        || scripted.run_count() == 54,
        "the parked notice row to run as the parent's next turn",
    )
    .await;
    // The row's turn is the 54th run, carrying the notice text as its
    // prompt — and it lands as the SAME System completion-notice entry a
    // direct delivery writes (its own sides ride the row), never a user
    // message: notices are engine artifacts, not the user's words.
    wait_for(
        || notice_entries(&core, PARENT).len() == 26,
        "the released row's turn to write the 26th system notice entry",
    )
    .await;
    let released = notice_entries(&core, PARENT)
        .pop()
        .expect("the released notice entry");
    assert!(
        released.id.starts_with("notice:"),
        "the released entry keeps the parked row's id: {}",
        released.id
    );
    let prompts = scripted.prompts.lock().unwrap();
    assert!(
        prompts[53].contains("Implement login") && prompts[53].contains("read_chat"),
        "the released row ran the notice's own text: {}",
        prompts[53]
    );
    drop(prompts);
    assert!(
        queue_rows(&core, PARENT).is_empty(),
        "the parked row was consumed by its turn"
    );
    // A new outcome after the user's message can start a notice turn again:
    // the count reset with the user's message (plus the row's own turn).
    run_to_idle(&core, &client, &scripted, SIDE, "round 27", "m-run-27", Some(PARENT)).await;
    wait_for(
        || notice_entries(&core, PARENT).len() == 27,
        "the count reset lets the next notice start a turn again",
    )
    .await;

    core.shutdown().await;
}

/// Outcomes that arrive while the cap holds MERGE into the parked row
/// (ticket 13's merging rule, extended to the cap's waiting form): one
/// parked notice, not a row per capped delivery, and its release is ONE
/// system notice covering every side chat that reached an outcome.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn capped_outcomes_merge_into_the_one_parked_notice_row() {
    let dir = tempfile::tempdir().unwrap();
    // 26 side turns (25 rounds + the capped 26th) + 25 notice turns + the
    // second side chat's turn + the user's message + the released row's
    // turn — every run pops one scripted turn.
    let turns: Vec<Turn> = (0..26 + 25 + 1 + 1 + 1)
        .map(|_| Turn::End {
            status: DoneStatus::Completed,
            error: None,
        })
        .collect();
    let scripted = Scripted::new(turns);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;
    create_side(&core, &client, SIDE2, "Flaky test", None).await;

    // Fill the cap, then park the 26th notice.
    for round in 0..25 {
        run_to_idle(
            &core,
            &client,
            &scripted,
            SIDE,
            &format!("round {round}"),
            &format!("m-run-{round}"),
            Some(PARENT),
        )
        .await;
        wait_for(
            || notice_entries(&core, PARENT).len() == round + 1,
            &format!("round {round}'s notice to be written"),
        )
        .await;
        // The notice turn itself dispatched (child + notice per round)
        // before the idle wait reads the parent's row.
        wait_for(
            || scripted.run_count() == 2 * (round + 1),
            &format!("round {round}'s notice turn to start"),
        )
        .await;
        wait_idle(&core, PARENT).await;
    }
    run_to_idle(&core, &client, &scripted, SIDE, "round 26", "m-run-26", Some(PARENT)).await;
    wait_for(
        || queue_rows(&core, PARENT).len() == 1,
        "the capped notice to park in the parent's queue",
    )
    .await;

    // A second capped outcome parks INTO the same row: one row, its text
    // and sides now covering both side chats.
    run_to_idle(&core, &client, &scripted, SIDE2, "the second child fails", "m-run-2", Some(PARENT)).await;
    wait_for(
        || queue_rows_with_sides(&core, PARENT)
            .into_iter()
            .any(|(_, text, sides)| text.contains("Flaky test") && sides.is_some_and(|s| s.len() == 2)),
        "the second capped outcome to merge into the parked row",
    )
    .await;
    let rows = queue_rows_with_sides(&core, PARENT);
    assert_eq!(rows.len(), 1, "still exactly one parked notice row");
    assert!(
        rows[0].1.contains("2 side chats reached outcomes"),
        "the merged row's text is one notice: {}",
        rows[0].1
    );
    assert_eq!(
        scripted.run_count(),
        52,
        "no notice turn started while the cap held (25 child + 25 notice + 2 capped children)"
    );

    // The user acts: the released row is ONE system notice naming both.
    queue_run(&client, PARENT, "user speaks now", "m-user", None).await;
    wait_for(
        || scripted.prompts_containing("user speaks now"),
        "the user's turn to start",
    )
    .await;
    wait_idle(&core, PARENT).await;
    wait_for(
        || notice_entries(&core, PARENT).len() == 26,
        "the released row to write its merged system notice entry",
    )
    .await;
    let released = notice_entries(&core, PARENT).pop().expect("the released entry");
    let text = notice_text(&released);
    assert!(text.contains("Implement login"), "{text}");
    assert!(text.contains("Flaky test"), "{text}");
    assert!(
        text.starts_with("2 side chats reached outcomes:"),
        "the released notice is one merged notice: {text}"
    );
    // The entry lands before the released row's dispatch: wait for its turn.
    wait_for(
        || scripted.run_count() == 54,
        "the released row's notice turn to start",
    )
    .await;
    wait_idle(&core, PARENT).await;
    assert!(queue_rows(&core, PARENT).is_empty());

    core.shutdown().await;
}

/// A Stop also drops the loop cap's parked notice row (ticket 14: it is a
/// pending notice, the visible form the cap left it waiting in): after the
/// Stop the user's next message runs alone, and nothing restarts the
/// parent on the parked notice's behalf.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stopping_the_parent_drops_the_parked_notice_row() {
    let dir = tempfile::tempdir().unwrap();
    // 26 child turns + 25 notice turns + the user's message — the parked
    // row's turn never happens.
    let turns: Vec<Turn> = (0..26 + 25 + 1)
        .map(|_| Turn::End {
            status: DoneStatus::Completed,
            error: None,
        })
        .collect();
    let scripted = Scripted::new(turns);
    let core = assemble(dir.path(), scripted.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_parent_and_side(&core, &client, None).await;

    // Fill the cap: 25 notice-started turns, then the 26th child completion
    // parks its notice in the parent's queue.
    for round in 0..25 {
        run_to_idle(
            &core,
            &client,
            &scripted,
            SIDE,
            &format!("round {round}"),
            &format!("m-run-{round}"),
            Some(PARENT),
        )
        .await;
        wait_for(
            || notice_entries(&core, PARENT).len() == round + 1,
            &format!("round {round}'s notice to be written"),
        )
        .await;
        // The notice turn itself dispatched (child + notice per round)
        // before the idle wait reads the parent's row.
        wait_for(
            || scripted.run_count() == 2 * (round + 1),
            &format!("round {round}'s notice turn to start"),
        )
        .await;
        wait_idle(&core, PARENT).await;
    }
    run_to_idle(&core, &client, &scripted, SIDE, "round 26", "m-run-26", Some(PARENT)).await;
    wait_for(
        || queue_rows(&core, PARENT).len() == 1,
        "the capped notice to park in the parent's queue",
    )
    .await;
    assert_eq!(scripted.run_count(), 51);

    // The user stops the (idle) parent: the parked row goes with the rest
    // of its pending notices.
    stop(&core, PARENT);
    wait_for(
        || queue_rows(&core, PARENT).is_empty(),
        "the parked notice row to be dropped by the Stop",
    )
    .await;

    // The user's next message runs alone: the parked notice's turn never
    // starts, and no later outcome wakes the parent unprompted.
    queue_run(&client, PARENT, "user speaks now", "m-user", None).await;
    wait_for(
        || scripted.prompts_containing("user speaks now"),
        "the user's turn to start",
    )
    .await;
    wait_idle(&core, PARENT).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(
        scripted.run_count(),
        52,
        "the user's turn is the last run — the parked row never ran"
    );
    assert!(queue_rows(&core, PARENT).is_empty());
    assert_eq!(
        notice_entries(&core, PARENT).len(),
        25,
        "no new notice entry appeared"
    );

    core.shutdown().await;
}
