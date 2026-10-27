//! Session process hygiene (`.scratch/session-process-hygiene/`):
//!
//! - **Warm-park budget** (ticket 02): beyond `ROBOCO_WARM_SESSIONS` parked
//!   persistent sessions, the OLDEST idle one is retired through the same
//!   clean interrupt the idle reaper uses — the recent tail stays warm, and
//!   the retired session simply resumes on its next message (no error
//!   stamp, no lost turn).
//! - **Un-stranded side-chat questions** (ticket 03): a side chat whose
//!   parent is gone (archived/deleted) must not sit in AwaitingInput
//!   forever holding its harness process — the engine answers its question
//!   empty, the turn settles, the session parks. Stopping a live parent
//!   does the same for its side chats' pending questions.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, Once};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;
use tokio::sync::mpsc;

use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SessionStatus, SteeringMode, UserInputQuestion,
};

const PARENT: &str = "chat-parent";
const SIDE_A: &str = "chat-side-a";
const SIDE_B: &str = "chat-side-b";
const SIDE_C: &str = "chat-side-c";

fn init_env() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        // SAFETY: set before any engine (and thus any reader) exists in this
        // test process; every test in this file shares the one value.
        unsafe {
            std::env::set_var("ROBOCO_SESSION_IDLE_MS", "3600000");
            std::env::set_var("ROBOCO_WARM_SESSIONS", "2");
        }
    });
}

fn run_request(prompt: &str) -> RunRequest {
    RunRequest {
        env: Default::default(),
        prompt: prompt.into(),
        harness: None,
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
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

fn done(status: DoneStatus) -> AgentEvent {
    AgentEvent::Done {
        status,
        result: None,
        error: None,
        session_id: Some("hs-hygiene".into()),
    }
}

/// What one `run()` serves for a prompt.
enum Turn {
    /// One text line + Done(Completed), then PARK: the stream stays open
    /// until the engine retires the runtime (what a parked harness CLI
    /// does between turns).
    Park,
    /// Ask one question through the engine's input bridge and park awaiting
    /// the answer; when it resolves, record how many labels came back, emit
    /// `answered(<n>)`, then Done(Completed) and park.
    Ask,
}

/// A persistent-session mock keyed by prompt: each scripted turn records
/// every prompt the engine (re)dispatched and every runtime the engine
/// retired (interrupt fired), so tests can prove exactly which sessions
/// stayed warm.
struct ParkingMock {
    turns: Arc<Mutex<VecDeque<(String, Turn)>>>,
    /// Every `run()` call's prompt, in order (a re-dispatch re-runs it).
    prompts: Arc<Mutex<Vec<String>>>,
    /// Prompts whose runtime the engine retired (interrupt cancelled).
    retired: Arc<Mutex<Vec<String>>>,
    /// Answer label counts per asked question, in ask order.
    answers: Arc<Mutex<Vec<usize>>>,
}

impl ParkingMock {
    fn new(turns: Vec<(&str, Turn)>) -> Arc<Self> {
        Arc::new(Self {
            turns: Arc::new(Mutex::new(
                turns
                    .into_iter()
                    .map(|(prompt, turn)| (prompt.into(), turn))
                    .collect(),
            )),
            prompts: Arc::new(Mutex::new(Vec::new())),
            retired: Arc::new(Mutex::new(Vec::new())),
            answers: Arc::new(Mutex::new(Vec::new())),
        })
    }
}

#[async_trait]
impl Harness for ParkingMock {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Parking"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
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
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let prompt = request.prompt;
        let turn = {
            let mut turns = self.turns.lock().unwrap();
            let index = turns
                .iter()
                .position(|(p, _)| p == &prompt)
                .unwrap_or_else(|| panic!("unexpected prompt dispatched: {prompt}"));
            turns.remove(index).expect("position checked").1
        };
        self.prompts.lock().unwrap().push(prompt.clone());
        let (tx, rx) = mpsc::channel::<Result<AgentEvent, HarnessError>>(16);
        let cancel = controls.interrupt.clone();
        let retired = self.retired.clone();
        let retired_prompt = prompt.clone();
        let answers = self.answers.clone();
        tokio::spawn(async move {
            match turn {
                Turn::Park => {
                    let _ = tx
                        .send(Ok(AgentEvent::TextDelta {
                            text: format!("did {retired_prompt}"),
                        }))
                        .await;
                    let _ = tx.send(Ok(done(DoneStatus::Completed))).await;
                }
                Turn::Ask => {
                    let question = vec![UserInputQuestion {
                        id: "q1".into(),
                        header: "Pick".into(),
                        question: "Which one?".into(),
                        options: vec!["a".into(), "b".into()],
                        multi_select: false,
                    }];
                    // The bridge parks the answer's resolver; awaiting it is
                    // exactly a harness blocked on the user's pick.
                    let received = (controls.request_input)(question).await.unwrap_or_default();
                    answers.lock().unwrap().push(received.len());
                    let _ = tx
                        .send(Ok(AgentEvent::TextDelta {
                            text: format!("answered({})", received.len()),
                        }))
                        .await;
                    let _ = tx.send(Ok(done(DoneStatus::Completed))).await;
                }
            }
            // Park: hold the stream open until the engine retires this
            // runtime — the interrupt token firing is exactly that signal.
            cancel.cancelled().await;
            retired.lock().unwrap().push(retired_prompt);
            drop(tx);
        });
        Ok(futures::stream::unfold(rx, |mut rx| async move {
            rx.recv().await.map(|event| (event, rx))
        })
        .boxed())
    }
}

fn assemble(dir: &std::path::Path, mock: Arc<ParkingMock>) -> EngineCore {
    init_env();
    let registry = HarnessRegistry::new();
    registry.register(mock);
    EngineCore::assemble(dir, Arc::new(registry), HarnessId::Mock).expect("engine core assembles")
}

async fn create_chat(
    core: &EngineCore,
    client: &roboco_rpc::RpcClient,
    chat_id: &str,
    parent: Option<&str>,
) {
    let mut params = serde_json::json!({
        "op": "createChat",
        "chatId": chat_id,
        "deviceId": core.device_id,
        "cwd": "/tmp",
    });
    if let Some(parent) = parent {
        params["parentChatId"] = serde_json::json!(parent);
    }
    client
        .call(roboco_rpc::methods::MUTATE, params)
        .await
        .expect("createChat");
}

async fn queue_run(client: &roboco_rpc::RpcClient, chat: &str, prompt: &str, message_id: &str) {
    client
        .call(
            roboco_rpc::methods::QUEUE_COMMAND,
            serde_json::json!({
                "chatId": chat,
                "command": serde_json::to_value(roboco_doc::SessionCommandPayload::Run {
                    request: run_request(prompt),
                    message_id: message_id.into(),
                })
                .unwrap(),
            }),
        )
        .await
        .expect("queue run");
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
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

fn status(core: &EngineCore, chat: &str) -> SessionStatus {
    core.sessions
        .session_status(chat)
        .map(|s| s.status)
        .unwrap_or(SessionStatus::Idle)
}

fn entries_text(core: &EngineCore, chat: &str) -> String {
    core.doc_host
        .open(chat)
        .ok()
        .and_then(|h| h.doc().read_entries().ok())
        .unwrap_or_default()
        .iter()
        .filter_map(|e| {
            e.parts.iter().find_map(|p| match p {
                roboco_doc::MessagePart::Text { text, .. } => Some(text.clone()),
                _ => None,
            })
        })
        .collect()
}

/// Budget 2: the third park retires the OLDEST idle session; the two newest
/// stay warm; the retired one resumes cleanly on its next message (the
/// transcript shows both turns, no error part, status Idle).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn warm_park_budget_retires_the_oldest_idle_session() {
    let dir = tempfile::tempdir().unwrap();
    let mock = ParkingMock::new(vec![
        ("work a", Turn::Park),
        ("work b", Turn::Park),
        ("work c", Turn::Park),
        // The re-dispatch after the eviction of side A.
        ("work a again", Turn::Park),
    ]);
    let core = assemble(dir.path(), mock.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_chat(&core, &client, SIDE_A, None).await;
    create_chat(&core, &client, SIDE_B, None).await;
    create_chat(&core, &client, SIDE_C, None).await;

    // Park three sessions sequentially (a fresh chat has no status row yet,
    // so park completion is observed through the turn's transcript text).
    let parked = |chat: &str, text: &str| entries_text(&core, chat).contains(text);
    queue_run(&client, SIDE_A, "work a", "m-1").await;
    wait_for(|| parked(SIDE_A, "did work a"), "side A's turn completed").await;
    wait_for(
        || status(&core, SIDE_A) == SessionStatus::Idle,
        "side A parked",
    )
    .await;
    queue_run(&client, SIDE_B, "work b", "m-2").await;
    wait_for(|| parked(SIDE_B, "did work b"), "side B's turn completed").await;
    wait_for(
        || status(&core, SIDE_B) == SessionStatus::Idle,
        "side B parked",
    )
    .await;
    // Two parked, budget 2: nothing retired yet.
    assert!(
        mock.retired.lock().unwrap().is_empty(),
        "budget not exceeded"
    );

    queue_run(&client, SIDE_C, "work c", "m-3").await;
    wait_for(|| parked(SIDE_C, "did work c"), "side C's turn completed").await;
    wait_for(
        || status(&core, SIDE_C) == SessionStatus::Idle,
        "side C parked",
    )
    .await;
    // The third park exceeded the budget: the OLDEST (side A) is retired.
    wait_for(
        || mock.retired.lock().unwrap().len() == 1,
        "the oldest parked session is retired",
    )
    .await;
    assert_eq!(mock.retired.lock().unwrap()[0], "work a");
    assert_eq!(status(&core, SIDE_A), SessionStatus::Idle);

    // The retired session resumes cleanly: a fresh runtime serves it.
    queue_run(&client, SIDE_A, "work a again", "m-4").await;
    wait_for(
        || entries_text(&core, SIDE_A).contains("did work a again"),
        "the retired session's next message ran on a fresh runtime",
    )
    .await;
    let text = entries_text(&core, SIDE_A);
    assert!(text.contains("did work a"), "the first turn is still there");
    assert!(!text.contains("interrupted"), "no interrupted stamp");

    core.shutdown().await;
}

/// A side chat whose parent is ARCHIVED asks a question: nobody can ever
/// answer it, so the engine answers it empty (the harness saw zero labels)
/// and the turn settles — instead of stranding AwaitingInput forever with
/// its harness process held.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_gone_parents_side_chat_question_is_answered_empty() {
    let dir = tempfile::tempdir().unwrap();
    let mock = ParkingMock::new(vec![("side work", Turn::Ask)]);
    let core = assemble(dir.path(), mock.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_chat(&core, &client, PARENT, None).await;
    create_chat(&core, &client, SIDE_A, Some(PARENT)).await;
    // The parent is archived: gone from the sidebar, never coming back.
    core.workspace.set_chat_archived(PARENT, true).unwrap();

    queue_run(&client, SIDE_A, "side work", "m-1").await;
    wait_for(
        || !mock.answers.lock().unwrap().is_empty(),
        "the stranded question was answered (empty)",
    )
    .await;
    assert_eq!(mock.answers.lock().unwrap()[0], 0, "the answer was empty");
    wait_for(
        || status(&core, SIDE_A) == SessionStatus::Idle,
        "the side chat's turn settled and parked",
    )
    .await;
    assert!(entries_text(&core, SIDE_A).contains("answered(0)"));

    core.shutdown().await;
}

/// Stopping a parent with a live side chat parked on a question drains the
/// child's question empty (a stopped parent owes no answers), letting the
/// child's turn settle.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stopping_a_parent_drains_its_side_chats_question() {
    let dir = tempfile::tempdir().unwrap();
    let mock = ParkingMock::new(vec![("side work", Turn::Ask)]);
    let core = assemble(dir.path(), mock.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_chat(&core, &client, PARENT, None).await;
    create_chat(&core, &client, SIDE_A, Some(PARENT)).await;

    queue_run(&client, SIDE_A, "side work", "m-1").await;
    wait_for(
        || status(&core, SIDE_A) == SessionStatus::AwaitingInput,
        "the side chat asked its question",
    )
    .await;

    // The user stops the (idle) parent: its side chat's question is drained.
    core.sessions.stop_turn(PARENT).await.expect("stop parent");
    wait_for(
        || !mock.answers.lock().unwrap().is_empty(),
        "the stopped parent drained the side chat's question",
    )
    .await;
    assert_eq!(
        mock.answers.lock().unwrap()[0],
        0,
        "the drain answer was empty"
    );
    wait_for(
        || status(&core, SIDE_A) == SessionStatus::Idle,
        "the side chat's turn settled after the drain",
    )
    .await;
    assert!(entries_text(&core, SIDE_A).contains("answered(0)"));

    core.shutdown().await;
}

/// Sanity: with the parent alive, the SAME question flow is untouched — the
/// harness blocks until a real answer arrives (the experience is unchanged
/// whenever somebody can still answer).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_live_parents_side_chat_question_still_waits_for_a_real_answer() {
    let dir = tempfile::tempdir().unwrap();
    let mock = ParkingMock::new(vec![("side work", Turn::Ask)]);
    let core = assemble(dir.path(), mock.clone());
    let client = roboco_rpc::memory_client(core.rpc_service());
    create_chat(&core, &client, PARENT, None).await;
    create_chat(&core, &client, SIDE_A, Some(PARENT)).await;

    queue_run(&client, SIDE_A, "side work", "m-1").await;
    wait_for(
        || status(&core, SIDE_A) == SessionStatus::AwaitingInput,
        "the side chat asked its question",
    )
    .await;
    // Give the engine a moment to (wrongly) auto-answer if it would.
    tokio::time::sleep(Duration::from_millis(150)).await;
    assert!(
        mock.answers.lock().unwrap().is_empty(),
        "a question with a live parent must wait, not auto-answer"
    );

    core.shutdown().await;
}
