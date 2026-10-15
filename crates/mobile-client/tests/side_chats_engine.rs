//! Real-engine side-chat and subagent tests (android-next #06, the port
//! of upstream PR #899): `fork_side_chat`, the empty side-chat create,
//! the subagent viewer's read-only docs (live while running, persisted
//! once finished, live again on a later write), and the "● N" running
//! counts on the workspace rows. Every frame comes from an in-process
//! `EngineCore` served over loopback — no protocol mocks.

mod support;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_engine::{EngineCore, HarnessRegistry, HarnessId};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_mobile_client::session::{SendRequest, SubagentState};
use roboco_mobile_client::{Client, NewSession, SessionTarget};
use roboco_proto::{
    AgentEvent, ChatConfig, DoneStatus, HarnessId as ProtoHarnessId, Model, ReasoningLevel,
    RunRequest, SandboxLevel, SteeringMode, ToolCall,
};

use support::{serve, test_client, wait_for_fleet, wait_for_view};

// ── the harness ────────────────────────────────────────────────────────────

/// What the harness's one reply does.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Reply {
    /// A plain one-line turn (nothing nested).
    Plain,
    /// Spawn a subagent that streams one line and finishes inside the turn.
    SpawnFinish,
    /// Spawn a subagent and keep the turn (and it) running: the stream
    /// stays open after the nested text.
    SpawnLive,
    /// The user's claude-code background shape: the harness's stream is a
    /// channel the TEST drives — the parent turn completes (a parked,
    /// steerable session), and the subagent's nested traffic arrives
    /// afterwards, exactly like claude's `run_in_background` lane (the
    /// `task_notification` re-keying of `agent_tasks`).
    Background,
}

/// A mock harness that records every `RunRequest` and answers with the
/// scenario's event stream. `SpawnLive` parks the stream (the turn never
/// completes), so the spawned subagent stays `Running`. `Background`
/// answers from a channel the test feeds.
struct ScenarioHarness {
    reply: Reply,
    requests: Arc<Mutex<Vec<RunRequest>>>,
    /// `Reply::Background`: the events the test feeds.
    background: std::sync::Mutex<Option<tokio::sync::mpsc::Receiver<Result<AgentEvent, HarnessError>>>>,
}

#[async_trait]
impl Harness for ScenarioHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Scenario"
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
        Ok(vec![Model {
            id: "mock-1".into(),
            label: "Mock 1".into(),
            description: None,
            reasoning_levels: vec![ReasoningLevel::Medium],
            options: vec![],
        }])
    }
    async fn run(
        &self,
        request: RunRequest,
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.requests.lock().unwrap().push(request);
        if let Some(rx) = self.background.lock().unwrap().take() {
            // The test drives every event: the parent turn's completion,
            // then the background subagent's nested traffic.
            let rx = Arc::new(tokio::sync::Mutex::new(rx));
            return Ok(futures::stream::unfold(rx, |rx| async move {
                rx.lock().await.recv().await.map(|item| (item, rx))
            })
            .boxed());
        }
        let reply = self.reply;
        let events: Vec<Result<AgentEvent, HarnessError>> = match reply {
            Reply::Plain => vec![
                Ok(AgentEvent::SessionStarted {
                    harness: HarnessId::Mock,
                    model: "mock-1".into(),
                    tools: vec![],
                    cwd: request.cwd.clone(),
                    session_id: "sess-1".into(),
                    assistant_message_id: "a-1".into(),
                }),
                Ok(AgentEvent::TextDelta {
                    text: format!("ack: {}", request.prompt),
                }),
                Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: None,
                }),
            ],
            Reply::SpawnFinish | Reply::SpawnLive => {
                let sub_done = reply == Reply::SpawnFinish;
                let mut events = vec![
                    Ok(AgentEvent::SessionStarted {
                        harness: HarnessId::Mock,
                        model: "mock-1".into(),
                        tools: vec![],
                        cwd: request.cwd.clone(),
                        session_id: "sess-1".into(),
                        assistant_message_id: "a-1".into(),
                    }),
                    // The spawn call (the `Agent[: desc]` convention the
                    // genus gate keys on).
                    Ok(AgentEvent::ToolCall {
                        id: "tool-1".into(),
                        call: ToolCall::Unknown {
                            name: "Agent: Audit the repo".into(),
                            input: Some(serde_json::json!({ "prompt": "audit", "model": "mock-1" })),
                        },
                    }),
                    // Nested traffic: routed to the subagent's own doc,
                    // never folded into the parent.
                    Ok(AgentEvent::Subagent {
                        parent_tool_use_id: "tool-1".into(),
                        event: Box::new(AgentEvent::TextDelta {
                            text: "subagent: reading the tree".into(),
                        }),
                    }),
                    Ok(AgentEvent::ToolResult {
                        id: "tool-1".into(),
                        is_error: false,
                        output: Some("spawned".into()),
                        diff: None,
                    }),
                ];
                if sub_done {
                    events.push(Ok(AgentEvent::Subagent {
                        parent_tool_use_id: "tool-1".into(),
                        event: Box::new(AgentEvent::Done {
                            status: DoneStatus::Completed,
                            result: None,
                            error: None,
                            session_id: None,
                        }),
                    }));
                }
                events.push(Ok(AgentEvent::TextDelta {
                    text: "parent done".into(),
                }));
                if sub_done {
                    events.push(Ok(AgentEvent::Done {
                        status: DoneStatus::Completed,
                        result: None,
                        error: None,
                        session_id: None,
                    }));
                }
                events
            }
        };
        if reply == Reply::SpawnLive {
            // Park the turn: everything above is delivered, then the
            // stream never ends — the subagent keeps running.
            return Ok(futures::stream::select(
                futures::stream::iter(events),
                futures::stream::pending(),
            )
            .boxed());
        }
        Ok(futures::stream::iter(events).boxed())
    }
}

// ── the rig ────────────────────────────────────────────────────────────────

struct Rig {
    client: Arc<Client>,
    core: EngineCore,
    engine_id: String,
    #[allow(dead_code)]
    engine_dir: tempfile::TempDir,
    #[allow(dead_code)]
    data_dir: tempfile::TempDir,
    requests: Arc<Mutex<Vec<RunRequest>>>,
}

impl Rig {
    fn scope(&self, raw: &str) -> String {
        self.client.scope(&self.engine_id, raw)
    }

    /// The parent chat this rig works on (its own checkout).
    fn parent_id(&self) -> String {
        self.scope("parent")
    }
}

/// Assemble the engine with the scenario harness, a parent chat with a
/// known checkout, serve it, and pair a client.
async fn rig(reply: Reply) -> Rig {
    rig_with_background(reply).await.0
}

/// [`rig`] plus the background lane: when `reply` is `Reply::Background`,
/// the returned sender feeds the harness's event stream (the test drives
/// the parent turn and the subagent's nested traffic itself).
async fn rig_with_background(
    reply: Reply,
) -> (Rig, Option<tokio::sync::mpsc::Sender<Result<AgentEvent, HarnessError>>>) {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let registry = HarnessRegistry::new();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let background = if reply == Reply::Background {
        let (tx, rx) = tokio::sync::mpsc::channel(32);
        (Some(tx), Some(rx))
    } else {
        (None, None)
    };
    let (background_tx, background_rx) = background;
    registry.register(Arc::new(ScenarioHarness {
        reply,
        requests: requests.clone(),
        background: std::sync::Mutex::new(background_rx),
    }));
    let core = EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(engine_dir.path()).unwrap(),
        Arc::new(registry),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat_with_parent(
            "parent",
            None,
            Some(&core.device_id),
            Some(ChatConfig {
                harness: ProtoHarnessId::Mock,
                model: Some("mock-1".into()),
                reasoning: None,
                model_options: Default::default(),
                sandbox: SandboxLevel::WorkspaceWrite,
            }),
            Some("/checkout/parent".into()),
            None,
        )
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let url = support::pairing_url(&listener, engine_dir.path(), None);
    let client = test_client(data_dir.path(), Vec::new(), Arc::new(support::Recorder::default()));
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == roboco_mobile_client::EngineState::Connected
    })
    .await;
    wait_for_fleet(&client, "parent row", |fleet| {
        fleet
            .engine(&record.engine_id)
            .is_some_and(|engine| engine.rows.chats.iter().any(|chat| chat.id == "parent"))
    })
    .await;
    let rig = Rig {
        client,
        core,
        engine_id: record.engine_id,
        engine_dir,
        data_dir,
        requests,
    };
    (rig, background_tx)
}

impl Rig {
    /// Send one message on the parent chat and wait for the mock reply's
    /// first text to land (the plain reply's `ack:` line).
    async fn ask(&self, text: &str) {
        let handle = self.client.open_session(&self.parent_id()).unwrap();
        let outcome = handle
            .send(SendRequest::text(text))
            .await
            .expect("send");
        assert!(matches!(outcome, roboco_mobile_client::SendOutcome::Started { .. }));
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let answered = handle
                .snapshot()
                .entries
                .iter()
                .any(|entry| entry.role == roboco_doc::MessageRole::Assistant);
            if answered {
                return;
            }
            assert!(Instant::now() < deadline, "the reply never arrived");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    /// The engine's own row for a raw chat id.
    fn engine_row(&self, raw: &str) -> roboco_proto::Chat {
        self.core
            .workspace
            .chat(raw)
            .unwrap()
            .unwrap_or_else(|| panic!("engine row for {raw}"))
    }

    /// The subagent doc id the parent's chips carry (scoped).
    fn subagent_doc(&self) -> String {
        let handle = self.client.open_session(&self.parent_id()).unwrap();
        let rows = handle.subagents();
        assert_eq!(rows.len(), 1, "one subagent: {rows:?}");
        rows[0].doc_id.clone()
    }
}

// ── fork ───────────────────────────────────────────────────────────────────

/// Forking copies the history through the last completed reply and lists
/// the copy under its parent; forking before anything completed fails
/// with the engine's reason; a side chat's fork lists as a sibling.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn fork_side_chat_copies_history_under_its_parent() {
    let rig = rig(Reply::Plain).await;
    let parent = rig.parent_id();

    // Nothing completed yet: the engine refuses with its reason.
    let error = rig
        .client
        .fork_side_chat(&parent)
        .await
        .expect_err("fork needs a completed reply");
    assert!(
        matches!(&error, roboco_mobile_client::ClientError::HostError { message } if message.contains("completed")),
        "the engine's reason carries over: {error:?}"
    );

    // A completed turn, then the fork.
    rig.ask("hello").await;
    let fork = rig.client.fork_side_chat(&parent).await.expect("fork");
    assert!(roboco_engine_client::registry::ScopedId::is_scoped(&fork));
    let raw_fork = roboco_engine_client::registry::ScopedId::parse(&fork)
        .expect("scoped fork id")
        .raw_id;
    // The row: a child of the parent, in its checkout, not archived.
    let row = rig.engine_row(&raw_fork);
    assert_eq!(row.parent_chat_id.as_deref(), Some("parent"));
    assert_eq!(row.cwd.as_deref(), Some("/checkout/parent"));
    assert!(!row.archived);
    // The history: the parent's entries plus the fork seam.
    let entries = rig
        .core
        .doc_host
        .open(&raw_fork)
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert!(
        entries
            .iter()
            .any(|entry| entry.role == roboco_doc::MessageRole::User),
        "the source's history was copied"
    );
    assert!(
        entries.iter().any(|entry| entry
            .parts
            .iter()
            .any(|part| matches!(part, roboco_doc::MessagePart::Fork { .. }))),
        "the fork seam sits between the copy and the fork's own turns"
    );

    // The fork opens at once (before the WatchChats echo matters) and
    // lists in the workspace children.
    let handle = rig.client.open_session(&fork).expect("open the fork");
    assert!(!handle.is_subagent());
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let listed = rig
            .client
            .workspace()
            .children(&parent)
            .iter()
            .any(|row| row.id == fork);
        if listed {
            break;
        }
        assert!(Instant::now() < deadline, "the fork never listed as a child");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // A side chat's fork lists as its sibling: the fork's parent is the
    // same root, not the fork itself.
    let sibling = rig.client.fork_side_chat(&fork).await.expect("sibling fork");
    let raw_sibling = roboco_engine_client::registry::ScopedId::parse(&sibling)
        .expect("scoped id")
        .raw_id;
    assert_eq!(
        rig.engine_row(&raw_sibling).parent_chat_id.as_deref(),
        Some("parent"),
        "the sibling hangs off the fork's parent"
    );
}

// ── the empty side chat ────────────────────────────────────────────────────

/// Creating a side chat takes the parent's host, checkout and sandbox,
/// lists under the parent (never the front page), and its first send
/// runs in the parent's folder. A side chat's own side chat is a sibling.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn create_side_chat_runs_in_the_parents_checkout() {
    let rig = rig(Reply::Plain).await;
    let parent = rig.parent_id();
    let config = ChatConfig {
        harness: "mock".into(),
        model: Some("mock-1".into()),
        reasoning: None,
        model_options: Default::default(),
        sandbox: SandboxLevel::ReadOnly,
    };

    let side = rig
        .client
        .create_session(NewSession {
            target: SessionTarget::Projectless {
                device_id: rig.client.scope(&rig.engine_id, "unused"),
            },
            config: Some(config),
            branch: Some("ignored".into()),
            cwd: Some("/ignored".into()),
            title: None,
            parent_chat_id: Some(parent.clone()),
        })
        .await
        .expect("side chat");
    assert!(roboco_engine_client::registry::ScopedId::is_scoped(&side));
    let raw_side = roboco_engine_client::registry::ScopedId::parse(&side)
        .expect("scoped id")
        .raw_id;
    // The engine row: the parent's checkout and host, the parent's
    // sandbox (not the draft's read-only), the draft's model.
    let row = rig.engine_row(&raw_side);
    assert_eq!(row.parent_chat_id.as_deref(), Some("parent"));
    assert_eq!(row.cwd.as_deref(), Some("/checkout/parent"));
    assert_eq!(row.device_id, rig.core.device_id);
    let row_config = row.config.expect("the row carries the config");
    assert_eq!(row_config.sandbox, SandboxLevel::WorkspaceWrite, "the parent's sandbox");
    assert_eq!(row_config.harness, ProtoHarnessId::Mock);
    assert_eq!(row_config.model.as_deref(), Some("mock-1"));

    // The first send runs in the parent's checkout.
    let handle = rig.client.open_session(&side).unwrap();
    handle
        .send(SendRequest::text("side hello"))
        .await
        .expect("send");
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let ran_in_parent_folder = rig
            .requests
            .lock()
            .unwrap()
            .iter()
            .any(|request| request.prompt.contains("side hello")
                && request.cwd == "/checkout/parent");
        if ran_in_parent_folder {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the side chat's first send never ran in the parent's checkout"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // The row lists under the parent (the pending overlay first, then the
    // engine's echo) and never in the front page.
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let child = rig
            .client
            .workspace()
            .children(&parent)
            .iter()
            .any(|row| row.id == side);
        let front = rig
            .client
            .workspace()
            .front
            .recent
            .iter()
            .any(|row| row.id == side);
        if child && !front {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the side chat never listed under its parent (and off the front page)"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // A side chat of the side chat is a sibling: the same root parent.
    let sibling = rig
        .client
        .create_session(NewSession {
            target: SessionTarget::Projectless {
                device_id: rig.client.scope(&rig.engine_id, "unused"),
            },
            config: None,
            branch: None,
            cwd: None,
            title: None,
            parent_chat_id: Some(side.clone()),
        })
        .await
        .expect("sibling");
    let raw_sibling = roboco_engine_client::registry::ScopedId::parse(&sibling)
        .expect("scoped id")
        .raw_id;
    assert_eq!(
        rig.engine_row(&raw_sibling).parent_chat_id.as_deref(),
        Some("parent"),
        "a side chat's side chat hangs off the same root"
    );

    // An unknown parent is a NotFound, before any engine call.
    let error = rig
        .client
        .create_session(NewSession {
            target: SessionTarget::Projectless {
                device_id: rig.client.scope(&rig.engine_id, "unused"),
            },
            config: None,
            branch: None,
            cwd: None,
            title: None,
            parent_chat_id: Some("engine:v1:no-such-chat".into()),
        })
        .await
        .expect_err("unknown parent");
    assert!(matches!(error, roboco_mobile_client::ClientError::NotFound { .. }), "{error:?}");
}

// ── the subagent viewer ────────────────────────────────────────────────────

/// A spawned subagent lists on the open session, its doc opens read-only
/// and streams, and the parent's row carries the "● N" count while it
/// runs — cleared when the chip settles.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_subagent_doc_streams_and_counts_while_it_runs() {
    let rig = rig(Reply::SpawnLive).await;
    let parent = rig.parent_id();

    // The turn spawns a subagent and parks: the chip stays running.
    rig.ask("spawn one").await;
    let deadline = Instant::now() + Duration::from_secs(20);
    let rows = loop {
        let handle = rig.client.open_session(&parent).unwrap();
        let rows = handle.subagents();
        if !rows.is_empty() {
            break rows;
        }
        assert!(Instant::now() < deadline, "the subagent never spawned");
        tokio::time::sleep(Duration::from_millis(50)).await;
    };
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].state, SubagentState::Running);
    assert_eq!(rows[0].title, "Audit the repo");
    assert_eq!(rows[0].model.as_deref(), Some("mock-1"));
    assert_eq!(rows[0].tail.as_deref(), Some("subagent: reading the tree"));
    assert_eq!(rows[0].doc_id, rig.scope("parent--sub--tool-1"));

    // The parent's row counts it (the "● N" pill).
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let count = rig
            .client
            .workspace()
            .session(&parent)
            .map(|row| row.running_subagents)
            .unwrap_or(0);
        if count == 1 {
            break;
        }
        assert!(Instant::now() < deadline, "the running count never landed: {count}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // The doc opens read-only and streams its transcript.
    let doc = rig.subagent_doc();
    let viewer = rig.client.open_subagent(&doc).expect("open subagent");
    assert!(viewer.is_subagent());
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let text = viewer.snapshot();
        if text
            .entries
            .iter()
            .any(|entry| entry
                .parts
                .iter()
                .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("reading the tree"))))
        {
            break;
        }
        assert!(Instant::now() < deadline, "the subagent transcript never streamed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // A doc id that is not a subagent doc is refused.
    let error = rig
        .client
        .open_subagent(&parent)
        .expect_err("a chat id is not a subagent doc");
    assert!(
        matches!(error, roboco_mobile_client::ClientError::InvalidArgument { .. }),
        "{error:?}"
    );

    // A later write to the doc (a resumed subagent) streams into the SAME
    // handle — upstream #899's fix, by construction: there is no frozen
    // copy to get stuck on.
    let raw_doc = roboco_engine_client::registry::ScopedId::parse(&doc)
        .expect("scoped doc id")
        .raw_id;
    rig.core
        .doc_host
        .open(&raw_doc)
        .unwrap()
        .doc()
        .push_message(&roboco_doc::schema::SessionMessageEntry {
            duration_ms: None,
            id: "resumed".into(),
            role: roboco_doc::MessageRole::Assistant,
            parts: vec![roboco_doc::MessagePart::Text {
                id: "p1".into(),
                text: "resumed output".into(),
            }],
            created_at: 2_000,
            device_id: rig.core.device_id.clone(),
            status: Some(roboco_doc::MessageStatus::Complete),
            continuation_of: None,
        })
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let resumed = viewer.snapshot().entries.iter().any(|entry| entry
            .parts
            .iter()
            .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("resumed output"))));
        if resumed {
            break;
        }
        assert!(Instant::now() < deadline, "the resumed subagent never streamed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// A subagent that finishes settles its chip and clears the count; its
/// transcript still reads (persisted on the engine), and the session's
/// subagent list marks it done.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_finished_subagent_clears_the_count_and_still_reads() {
    let rig = rig(Reply::SpawnFinish).await;
    let parent = rig.parent_id();

    rig.ask("spawn and finish").await;
    // The chip settles to Done.
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let handle = rig.client.open_session(&parent).unwrap();
        let rows = handle.subagents();
        if rows.len() == 1 && rows[0].state == SubagentState::Done {
            break;
        }
        assert!(Instant::now() < deadline, "the subagent never settled: {rows:?}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    // The count cleared.
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let count = rig
            .client
            .workspace()
            .session(&parent)
            .map(|row| row.running_subagents)
            .unwrap_or(0);
        if count == 0 {
            break;
        }
        assert!(Instant::now() < deadline, "the count never cleared: {count}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    // The transcript still reads from the engine.
    let doc = rig.subagent_doc();
    let viewer = rig.client.open_subagent(&doc).expect("open the finished subagent");
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let text = viewer.snapshot();
        if text
            .entries
            .iter()
            .any(|entry| entry
                .parts
                .iter()
                .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("reading the tree"))))
        {
            break;
        }
        assert!(Instant::now() < deadline, "the finished transcript never read");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// The user's claude-code background shape (run_in_background): the spawn
/// call's tool result returns at once ("Async agent launched") and the
/// parent turn completes, while the subagent's nested traffic arrives
/// afterwards — the run loop stays parked and routes it into the
/// subagent's doc (sessions.rs: "Handled BEFORE the parked gate so a
/// parked session's background traffic still reaches the subagent doc").
/// The card binds to the doc (a link with running dots), the doc opens
/// live and keeps streaming, and the chip freezes when the agent's Done
/// lands.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_background_subagent_binds_after_its_tool_result_and_freezes_on_done() {
    let (rig, sender) = rig_with_background(Reply::Background).await;
    let sender = sender.expect("the background lane");
    let parent = rig.parent_id();
    let parent_handle = rig.client.open_session(&parent).unwrap();
    let request_cwd = "/checkout/parent".to_owned();
    let send = parent_handle
        .send(SendRequest::text("run an agent in the background"))
        .await
        .expect("send");
    assert!(matches!(send, roboco_mobile_client::SendOutcome::Started { .. }));

    // Phase 1 — the parent turn: the spawn call, its IMMEDIATE result
    // (the async-launch ack resolves the tool call), the parent's text,
    // and the turn's Done (the steerable session parks, not ends).
    let event = |e: AgentEvent| -> Result<AgentEvent, HarnessError> { Ok(e) };
    sender
        .send(event(AgentEvent::SessionStarted {
            harness: HarnessId::Mock,
            model: "mock-1".into(),
            tools: vec![],
            cwd: request_cwd.clone(),
            session_id: "sess-1".into(),
            assistant_message_id: "a-1".into(),
        }))
        .await
        .unwrap();
    sender
        .send(event(AgentEvent::ToolCall {
            id: "tool-1".into(),
            call: ToolCall::Unknown {
                name: "Agent: Audit the repo".into(),
                input: Some(serde_json::json!({ "prompt": "audit", "run_in_background": true })),
            },
        }))
        .await
        .unwrap();
    sender
        .send(event(AgentEvent::ToolResult {
            id: "tool-1".into(),
            is_error: false,
            output: Some("Async agent launched…".into()),
            diff: None,
        }))
        .await
        .unwrap();
    sender
        .send(event(AgentEvent::TextDelta {
            text: "parent turn done".into(),
        }))
        .await
        .unwrap();
    sender
        .send(event(AgentEvent::Done {
            status: DoneStatus::Completed,
            result: None,
            error: None,
            session_id: None,
        }))
        .await
        .unwrap();

    // The parent's reply landed; the subagent has not spoken yet, so
    // nothing is listed (the chip IS the index).
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let answered = parent_handle
            .snapshot()
            .entries
            .iter()
            .any(|entry| entry
                .parts
                .iter()
                .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("parent turn done")));
        if answered {
            break;
        }
        assert!(Instant::now() < deadline, "the parent turn never landed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert!(parent_handle.subagents().is_empty(), "no subagent yet");

    // Phase 2 — the background agent speaks, AFTER the turn completed.
    // The engine stamps the (already-resolved) chip with the doc ref and
    // Running, and routes the text into the subagent's own doc.
    sender
        .send(event(AgentEvent::Subagent {
            parent_tool_use_id: "tool-1".into(),
            event: Box::new(AgentEvent::TextDelta {
                text: "background: scanning the tree".into(),
            }),
        }))
        .await
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    let rows = loop {
        let rows = parent_handle.subagents();
        if !rows.is_empty() {
            break rows;
        }
        assert!(Instant::now() < deadline, "the background subagent never bound");
        tokio::time::sleep(Duration::from_millis(50)).await;
    };
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].state, SubagentState::Running);
    assert_eq!(rows[0].doc_id, rig.scope("parent--sub--tool-1"));
    assert_eq!(rows[0].tail.as_deref(), Some("background: scanning the tree"));
    // The running count lands on the row (the card's dots).
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let count = rig
            .client
            .workspace()
            .session(&parent)
            .map(|row| row.running_subagents)
            .unwrap_or(0);
        if count == 1 {
            break;
        }
        assert!(Instant::now() < deadline, "the background count never landed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    // The doc opens read-only and streams the background output.
    let viewer = rig
        .client
        .open_subagent(&rig.scope("parent--sub--tool-1"))
        .expect("open the background subagent");
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let streaming = viewer.snapshot().entries.iter().any(|entry| entry
            .parts
            .iter()
            .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("scanning the tree"))));
        if streaming {
            break;
        }
        assert!(Instant::now() < deadline, "the background doc never streamed");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    // Phase 3 — the agent finishes: the chip freezes (Done), the count
    // clears, and the transcript still reads.
    sender
        .send(event(AgentEvent::Subagent {
            parent_tool_use_id: "tool-1".into(),
            event: Box::new(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: None,
            }),
        }))
        .await
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let rows = parent_handle.subagents();
        if rows.len() == 1 && rows[0].state == SubagentState::Done {
            break;
        }
        assert!(Instant::now() < deadline, "the background subagent never froze: {rows:?}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let count = rig
            .client
            .workspace()
            .session(&parent)
            .map(|row| row.running_subagents)
            .unwrap_or(0);
        if count == 0 {
            break;
        }
        assert!(Instant::now() < deadline, "the frozen count never cleared");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let still_reads = viewer
        .snapshot()
        .entries
        .iter()
        .any(|entry| entry
            .parts
            .iter()
            .any(|part| matches!(part, roboco_doc::MessagePart::Text { text, .. } if text.contains("scanning the tree"))));
    assert!(still_reads, "the frozen transcript still reads");
}
