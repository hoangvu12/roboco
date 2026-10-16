//! Side chats: forked chats and agent-spawned chats that list under a parent.
//!
//! Engine-side machinery under test (upstream #498):
//! - `ForkSideChat` copies the source's completed history (up to the last
//!   complete assistant turn), appends the `fork` seam, persists the doc
//!   BEFORE publishing the registry row, and lists the copy under its parent
//!   (or the source's parent, so a side chat's own fork lands as a sibling);
//! - the fork is idempotent by chat id, refuses empty sources and collisions
//!   with main chats, and resets provider-session continuity so the copy
//!   never resumes the parent's conversation;
//! - a side chat's first send bootstraps the provider session with the
//!   transcript it lacks — budgeted (t3-orchestration-v2 ticket 01): a
//!   16k-byte selection of the copied transcript with a coverage header
//!   naming the omitted messages and the `read_chat` source chat — once per
//!   provider session, never in front of a native command;
//! - a fork whose first turn was a native command owes its copied history to
//!   the first ordinary send — warm dispatch, steer, or a cold resume —
//!   exactly once, recorded durably per provider session;
//! - the fork of a chat whose boundary entry carries a provider turn ref
//!   continues the SOURCE's provider session natively instead
//!   (t3-orchestration-v2 ticket 17): the RPC records the fork source and
//!   boundary, the first run's request carries them (Codex `thread/fork
//!   {threadId, lastTurnId}`), nothing is pasted, and the budgeted
//!   transcript rides the request as the refused-fork fallback. A missing
//!   ref or a changed harness keeps the ticket-01 budgeted paste. The turn
//!   refs themselves ride assistant entries via a harness event the engine
//!   stamps at segment finish.
//! - "Fork from here" (t3-orchestration-v2 ticket 21): the RPC's optional
//!   `boundary` picks the reply to fork at — any settled assistant entry,
//!   an earlier, errored or interrupted one — and the copy (and the budgeted
//!   fallback) stop there, inclusive, while the native fork boundary becomes
//!   exactly that reply's turn ref. Without it, today's behaviour stays: the
//!   latest complete reply.

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;
use std::sync::{Arc, Mutex};
use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine::{transcript_budget::TRANSCRIPT_BUDGET_BYTES, EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SteeringMode,
};
use roboco_rpc::methods;

struct Capture(Arc<Mutex<Vec<RunRequest>>>);
#[async_trait]
impl Harness for Capture {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Capture"
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
        _: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.0.lock().unwrap().push(request);
        Ok(futures::stream::iter(vec![
            Ok(AgentEvent::TextDelta {
                text: "Side answer".into(),
            }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some("side-provider-session".into()),
            }),
        ])
        .boxed())
    }
}
fn message(id: &str, role: MessageRole, text: &str, status: MessageStatus) -> SessionMessageEntry {
    SessionMessageEntry {
        origin_chat_id: None,
        duration_ms: None,
        id: id.into(),
        role,
        parts: vec![MessagePart::Text {
            id: format!("{id}-text"),
            text: text.into(),
        }],
        created_at: 1,
        device_id: "device".into(),
        status: Some(status),
        provider_turn: None,
        continuation_of: None,
    }
}

/// An assistant entry carrying a provider turn ref, as a run that reports
/// one stamps it (ticket 17): the harness is the chat's own so the dispatch
/// below takes the native-fork path.
fn turn_entry(id: &str, text: &str, status: MessageStatus, turn: &str) -> SessionMessageEntry {
    let mut entry = message(id, MessageRole::Assistant, text, status);
    entry.provider_turn = Some(roboco_proto::ProviderTurnRef {
        harness: HarnessId::Mock,
        session_id: "parent-provider-session".into(),
        turn_id: turn.into(),
    });
    entry
}

#[tokio::test]
async fn fork_is_frozen_durable_idempotent_and_has_an_independent_provider_session() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.workspace
        .rename_chat("main", "Main conversation")
        .unwrap();
    core.workspace
        .set_chat_harness_session("main", "parent-provider-session", "/tmp");
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "Remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "a1",
            MessageRole::Assistant,
            "I remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "u2",
            MessageRole::User,
            "unfinished turn",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "a2",
            MessageRole::Assistant,
            "still streaming",
            MessageStatus::Streaming,
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    let params = serde_json::json!({ "chatId": "side", "sourceChatId": "main" });
    let fork = client
        .call_as::<roboco_proto::Chat>(methods::FORK_SIDE_CHAT, params.clone())
        .await
        .unwrap();
    assert_eq!(fork.parent_chat_id.as_deref(), Some("main"));
    assert_eq!(fork.harness_session_id, None);
    assert_eq!(fork.cwd, Some("/tmp".into()));
    assert!(fork.on_chat2());
    let target = core.doc_host.open("side").unwrap();
    let copied = target.doc().read_entries().unwrap();
    // The frozen prefix, then the seam: a system entry naming the source.
    assert_eq!(copied[..2], source.doc().read_entries().unwrap()[..2]);
    assert_eq!(copied.len(), 3);
    assert_eq!(copied[2].role, MessageRole::System);
    assert_eq!(copied[2].status, Some(MessageStatus::Complete));
    assert_eq!(
        copied[2].parts,
        vec![roboco_doc::MessagePart::Fork {
            id: "fork:side".into(),
            source_chat_id: "main".into(),
            source_title: "Main conversation".into(),
        }]
    );
    client.call(methods::FORK_SIDE_CHAT, params).await.unwrap();
    assert_eq!(target.doc().read_entries().unwrap().len(), 3);
    core.sessions.set_ipc_port(27699);
    core.sessions
        .dispatch(
            "side",
            HarnessId::Mock,
            RunRequest {
                mcp: None,
                prompt: "What did I ask you to remember?".into(),
                harness: Some(HarnessId::Mock),
                model: None,
                reasoning: None,
                model_options: Default::default(),
                cwd: "/tmp".into(),
                sandbox: SandboxLevel::WorkspaceWrite,
                auto_approve: true,
                resume: None,
                provider_fork: None,
                attachments: vec![],
                worktree: None,
            },
            Some("side-user".into()),
            None,
        )
        .await
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while requests.lock().unwrap().is_empty() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let request = requests.lock().unwrap()[0].clone();
    assert_eq!(request.resume, None);
    // The host stamps its MCP server onto the run: this binary's `roboco
    // mcp`, dialing the served port, identified as the side chat.
    let mcp = request
        .mcp
        .clone()
        .expect("run carries the roboco MCP server");
    assert_eq!(mcp.name, "roboco");
    assert_eq!(mcp.args, ["mcp"]);
    assert_eq!(mcp.env["ROBOCO_IPC_PORT"], "27699");
    assert_eq!(mcp.env["ROBOCO_CHAT_ID"], "side");
    assert_eq!(mcp.env["ROBOCO_DEVICE_ID"], core.device_id);
    assert!(request.prompt.contains("PINEAPPLE"));
    assert!(!request.prompt.contains("unfinished turn"));
    assert_eq!(source.doc().read_entries().unwrap().len(), 4);
    // The fork's first own turn lands after the seam, and the seam itself
    // never reaches the provider as conversation text.
    assert_eq!(
        target.doc().read_entries().unwrap()[3].parts[0],
        MessagePart::Text {
            id: target.doc().read_entries().unwrap()[3].parts[0]
                .id()
                .to_string(),
            text: "What did I ask you to remember?".into()
        }
    );
    assert!(!request.prompt.contains("fork:side"));
    core.shutdown().await;
    drop(client);
    drop(source);
    drop(target);
    drop(core);
    let restarted = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    assert_eq!(
        restarted
            .workspace
            .chat("side")
            .unwrap()
            .unwrap()
            .parent_chat_id
            .as_deref(),
        Some("main")
    );
    let entries = restarted
        .doc_host
        .open("side")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert!(entries.iter().any(|e| e.id == "a1"));
    assert!(!entries.iter().any(|e| e.id == "a2"));
    restarted.shutdown().await;
}

/// A harness that reports the provider turn ref the Codex driver emits
/// (ticket 17): one turn whose events name the provider session and turn.
struct RefCapture(Arc<Mutex<Vec<RunRequest>>>);
#[async_trait]
impl Harness for RefCapture {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "RefCapture"
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
        _: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.0.lock().unwrap().push(request);
        Ok(futures::stream::iter(vec![
            Ok(AgentEvent::SessionStarted {
                harness: HarnessId::Mock,
                model: "mock-1".into(),
                tools: vec![],
                cwd: "/tmp".into(),
                session_id: "ref-provider-session".into(),
                assistant_message_id: uuid::Uuid::new_v4().to_string(),
            }),
            Ok(AgentEvent::TurnRef {
                reference: roboco_proto::ProviderTurnRef {
                    harness: HarnessId::Mock,
                    session_id: "ref-provider-session".into(),
                    turn_id: "turn-42".into(),
                },
            }),
            Ok(AgentEvent::TextDelta {
                text: "Ref answer".into(),
            }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some("ref-provider-session".into()),
            }),
        ])
        .boxed())
    }
}

/// Codex assistant entries carry a provider turn reference (ticket 17): the
/// harness event rides the stream and the engine stamps it on the entry the
/// turn streamed into, so a later fork finds its boundary there.
#[tokio::test]
async fn assistant_entries_record_the_provider_turn_ref() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(RefCapture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "chat",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.sessions
        .dispatch(
            "chat",
            HarnessId::Mock,
            RunRequest {
                mcp: None,
                prompt: "turn one".into(),
                harness: Some(HarnessId::Mock),
                model: None,
                reasoning: None,
                model_options: Default::default(),
                cwd: "/tmp".into(),
                sandbox: SandboxLevel::WorkspaceWrite,
                auto_approve: true,
                resume: None,
                provider_fork: None,
                attachments: vec![],
                worktree: None,
            },
            Some("u1".into()),
            None,
        )
        .await
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while requests.lock().unwrap().is_empty() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while core
            .sessions
            .session_status("chat")
            .is_some_and(|s| s.status != roboco_proto::SessionStatus::Idle)
        {
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    let entries = core
        .doc_host
        .open("chat")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    let assistant: Vec<_> = entries
        .iter()
        .filter(|e| e.role == MessageRole::Assistant)
        .collect();
    assert_eq!(assistant.len(), 1, "{entries:?}");
    assert_eq!(
        assistant[0].provider_turn.as_ref(),
        Some(&roboco_proto::ProviderTurnRef {
            harness: HarnessId::Mock,
            session_id: "ref-provider-session".into(),
            turn_id: "turn-42".into(),
        }),
        "the turn ref rides the entry it streamed into: {entries:?}"
    );
    core.shutdown().await;
}

/// The boundary entry's ref drives the fork: the RPC records the source
/// provider session and boundary, the first dispatch carries them to the
/// harness (Codex's `thread/fork {threadId, lastTurnId}`), nothing is
/// pasted, and the budgeted transcript rides the request as the refused-fork
/// fallback.
#[tokio::test]
async fn a_fork_with_a_provider_turn_ref_forks_the_source_session() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.workspace
        .set_chat_harness_session("main", "parent-provider-session", "/tmp");
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "Remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    // The boundary entry: complete, and carrying the provider turn ref a
    // Codex run would have stamped (harness = the chat's own harness here,
    // so the dispatch below takes the native path).
    let mut boundary = message(
        "a1",
        MessageRole::Assistant,
        "I remember PINEAPPLE",
        MessageStatus::Complete,
    );
    boundary.provider_turn = Some(roboco_proto::ProviderTurnRef {
        harness: HarnessId::Mock,
        session_id: "parent-provider-session".into(),
        turn_id: "turn-1".into(),
    });
    source.doc().push_message(&boundary).unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side", "sourceChatId": "main" }),
        )
        .await
        .unwrap();

    // The fork's first run: the native-fork directive rides the request and
    // the delivered prompt carries no transcript.
    let request = side_turn(&core, &requests, "side", "What did I ask?", "s1").await;
    assert_eq!(
        request.resume, None,
        "the fork never resumes the source thread"
    );
    let fork = request
        .provider_fork
        .expect("native fork directive rides the run");
    assert_eq!(fork.session_id, "parent-provider-session");
    assert_eq!(fork.last_turn, "turn-1");
    // Nothing pasted: the forked session carries the history natively.
    assert_eq!(request.prompt, "What did I ask?", "{}", request.prompt);
    // The budgeted transcript still rides the request as the refused-fork
    // fallback (what the driver pastes onto a fresh session).
    let fallback = fork.fallback_history.expect("fallback history attached");
    assert!(fallback.contains("PINEAPPLE"), "{fallback}");
    assert!(fallback.ends_with("\n\n"), "{fallback}");
    // The copied boundary keeps its ref, so a later fork of the fork still
    // finds a boundary.
    let copied = core
        .doc_host
        .open("side")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert_eq!(
        copied.iter().find(|e| e.id == "a1").unwrap().provider_turn,
        boundary.provider_turn
    );

    // The second run: the intent is spent, the fork's own session resumes,
    // and nothing is pasted (the session already holds the history).
    let request = side_turn(&core, &requests, "side", "And now?", "s2").await;
    assert_eq!(request.provider_fork, None);
    assert_eq!(request.resume.as_deref(), Some("side-provider-session"));
    assert_eq!(request.prompt, "And now?", "{}", request.prompt);
    core.shutdown().await;
}

/// Old chats (no refs) and a changed harness fall back to the budgeted paste
/// from ticket 01: no native-fork directive, history in front of the prompt.
#[tokio::test]
async fn forks_without_refs_or_with_a_changed_harness_paste_the_budget() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "plain",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("plain").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "Remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "a1",
            MessageRole::Assistant,
            "I remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    // An old chat: no provider turn ref on the boundary entry.
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "old-fork", "sourceChatId": "plain" }),
        )
        .await
        .unwrap();
    let request = side_turn(&core, &requests, "old-fork", "What did I ask?", "o1").await;
    assert_eq!(request.provider_fork, None, "no ref, no native fork");
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.ends_with("What did I ask?"));

    // A ref recorded for a DIFFERENT harness: the run's harness spends the
    // intent without ever taking the native path.
    let mut boundary = message(
        "a2",
        MessageRole::Assistant,
        "answer with a foreign ref",
        MessageStatus::Complete,
    );
    boundary.provider_turn = Some(roboco_proto::ProviderTurnRef {
        harness: HarnessId::Codex,
        session_id: "codex-thread".into(),
        turn_id: "turn-9".into(),
    });
    source.doc().push_message(&boundary).unwrap();
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "switched-fork", "sourceChatId": "plain" }),
        )
        .await
        .unwrap();
    let request = side_turn(&core, &requests, "switched-fork", "What did I ask?", "c1").await;
    assert_eq!(request.provider_fork, None, "a changed harness never forks");
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);
    core.shutdown().await;
}

/// "Fork from here" on an earlier reply (t3-orchestration-v2 ticket 21):
/// the copy stops at the chosen entry, inclusive, and the first run's
/// native-fork boundary is THAT entry's turn ref — not the latest one's.
/// The budgeted fallback history is cut at the boundary too, so the fork
/// never sees later messages even when the harness refuses the fork.
#[tokio::test]
async fn forking_from_an_earlier_reply_cuts_the_copy_and_forks_at_that_turn() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.workspace
        .set_chat_harness_session("main", "parent-provider-session", "/tmp");
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "Remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a1",
            "I remember PINEAPPLE",
            MessageStatus::Complete,
            "turn-1",
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "u2",
            MessageRole::User,
            "And the code?",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a2",
            "the code is fine",
            MessageStatus::Complete,
            "turn-2",
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "u3",
            MessageRole::User,
            "ship it",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a3",
            "shipping",
            MessageStatus::Complete,
            "turn-3",
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());

    // Fork from the FIRST reply.
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side-early", "sourceChatId": "main", "boundary": "a1" }),
        )
        .await
        .unwrap();
    // The transcript ends at that reply: only u1, a1, and the seam — the
    // fork never sees u2, a2, u3, or a3.
    let ids = |chat: &str| {
        core.doc_host
            .open(chat)
            .unwrap()
            .doc()
            .read_entries()
            .unwrap()
            .into_iter()
            .map(|entry| entry.id)
            .collect::<Vec<_>>()
    };
    assert_eq!(
        ids("side-early"),
        vec!["u1".to_string(), "a1".to_string(), "fork:side-early".to_string()],
        "the copy stops at the boundary, inclusive"
    );
    // The copied boundary keeps its ref, so a later fork of the fork still
    // finds a boundary.
    let copied = core
        .doc_host
        .open("side-early")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert_eq!(
        copied.iter().find(|e| e.id == "a1").unwrap().provider_turn,
        Some(roboco_proto::ProviderTurnRef {
            harness: HarnessId::Mock,
            session_id: "parent-provider-session".into(),
            turn_id: "turn-1".into(),
        })
    );

    // The first run forks natively at the CHOSEN boundary…
    let request = side_turn(&core, &requests, "side-early", "What did I ask?", "s1").await;
    let fork = request
        .provider_fork
        .expect("native fork at the chosen boundary");
    assert_eq!(fork.session_id, "parent-provider-session");
    assert_eq!(fork.last_turn, "turn-1");
    // …nothing is pasted onto the prompt…
    assert_eq!(request.prompt, "What did I ask?", "{}", request.prompt);
    // …and the refused-fork fallback is cut at the boundary: it holds the
    // copied prefix and never a later message.
    let fallback = fork.fallback_history.expect("fallback history attached");
    assert!(fallback.contains("PINEAPPLE"), "{fallback}");
    assert!(!fallback.contains("the code is fine"), "{fallback}");
    assert!(!fallback.contains("shipping"), "{fallback}");

    // Without a boundary, today's behaviour stays: the fork continues from
    // the LATEST reply.
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side-latest", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    assert_eq!(
        ids("side-latest"),
        vec![
            "u1".to_string(),
            "a1".to_string(),
            "u2".to_string(),
            "a2".to_string(),
            "u3".to_string(),
            "a3".to_string(),
            "fork:side-latest".to_string(),
        ],
        "no boundary copies through the latest reply"
    );
    let request = side_turn(&core, &requests, "side-latest", "What next?", "s2").await;
    let fork = request
        .provider_fork
        .expect("the latest reply's ref drives the default fork");
    assert_eq!(fork.last_turn, "turn-3");
    core.shutdown().await;
}

/// Errored and interrupted replies are forkable (ticket 21): an errored
/// turn is a COMPLETE entry with an error part, an interrupted one an
/// ABORTED entry — both keep the turn ref the run stamped before it ended,
/// and a fork at either continues the source session natively at exactly
/// that turn.
#[tokio::test]
async fn errored_and_interrupted_replies_can_be_forked() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.workspace
        .set_chat_harness_session("main", "parent-provider-session", "/tmp");
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a1",
            "all good",
            MessageStatus::Complete,
            "turn-1",
        ))
        .unwrap();
    // An errored reply: complete status with an error part, ref stamped.
    let mut errored = turn_entry("a2", "", MessageStatus::Complete, "turn-2");
    errored.parts = vec![
        MessagePart::Text {
            id: "a2-text".into(),
            text: "half an answer".into(),
        },
        MessagePart::Error {
            id: "a2-err".into(),
            message: "the model blew up".into(),
        },
    ];
    source.doc().push_message(&errored).unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "retry?",
            MessageStatus::Complete,
        ))
        .unwrap();
    // An interrupted reply: aborted status, ref stamped before the stop.
    source
        .doc()
        .push_message(&turn_entry(
            "a3",
            "partial answer",
            MessageStatus::Aborted,
            "turn-3",
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());

    // Fork at the ERRORED reply.
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side-err", "sourceChatId": "main", "boundary": "a2" }),
        )
        .await
        .unwrap();
    let ids = |chat: &str| {
        core.doc_host
            .open(chat)
            .unwrap()
            .doc()
            .read_entries()
            .unwrap()
            .into_iter()
            .map(|entry| entry.id)
            .collect::<Vec<_>>()
    };
    assert_eq!(
        ids("side-err"),
        vec![
            "a1".to_string(),
            "a2".to_string(),
            "fork:side-err".to_string(),
        ],
        "the errored reply is the boundary, inclusive"
    );
    let request = side_turn(&core, &requests, "side-err", "What went wrong?", "e1").await;
    let fork = request
        .provider_fork
        .expect("the errored turn's ref drives the native fork");
    assert_eq!(fork.last_turn, "turn-2");

    // Fork at the INTERRUPTED reply — the boundary a default fork would
    // never pick (it is aborted, not complete).
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side-int", "sourceChatId": "main", "boundary": "a3" }),
        )
        .await
        .unwrap();
    assert_eq!(
        ids("side-int"),
        vec![
            "a1".to_string(),
            "a2".to_string(),
            "u1".to_string(),
            "a3".to_string(),
            "fork:side-int".to_string(),
        ],
        "the interrupted reply is the boundary, inclusive"
    );
    let request = side_turn(&core, &requests, "side-int", "Continue", "i1").await;
    let fork = request
        .provider_fork
        .expect("the interrupted turn's ref drives the native fork");
    assert_eq!(fork.last_turn, "turn-3");
    core.shutdown().await;
}

/// A boundary that is not a settled assistant reply is refused, and no
/// chat row appears: an unknown id, a user entry, and a reply still
/// streaming (its run is writing it, and its turn ref is not stamped yet).
#[tokio::test]
async fn an_invalid_boundary_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "hi",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a1",
            "hello",
            MessageStatus::Complete,
            "turn-1",
        ))
        .unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a2",
            "still going…",
            MessageStatus::Streaming,
            "turn-2",
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    for boundary in ["nope", "u1", "a2"] {
        let error = client
            .call(
                methods::FORK_SIDE_CHAT,
                serde_json::json!({
                    "chatId": "side-bad",
                    "sourceChatId": "main",
                    "boundary": boundary,
                }),
            )
            .await
            .unwrap_err();
        assert!(
            error.to_string().contains("settled assistant reply"),
            "boundary {boundary}: {error}"
        );
        assert!(core.workspace.chat("side-bad").unwrap().is_none());
    }
    core.shutdown().await;
}

/// A boundary without a provider turn ref (an old chat, or a run that never
/// reported one) falls back to the budgeted paste — cut at the boundary,
/// so the pasted history never carries a later message (ticket 21's rule:
/// the fork never sees anything after the boundary, whichever path it
/// takes).
#[tokio::test]
async fn a_boundary_without_a_ref_pastes_the_budget_up_to_the_boundary() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    source
        .doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "Remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "a1",
            MessageRole::Assistant,
            "I remember PINEAPPLE",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "u2",
            MessageRole::User,
            "And the code?",
            MessageStatus::Complete,
        ))
        .unwrap();
    source
        .doc()
        .push_message(&message(
            "a2",
            MessageRole::Assistant,
            "the code is fine",
            MessageStatus::Complete,
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side-early", "sourceChatId": "main", "boundary": "a1" }),
        )
        .await
        .unwrap();
    let request = side_turn(&core, &requests, "side-early", "What did I ask?", "s1").await;
    assert_eq!(request.provider_fork, None, "no ref, no native fork");
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);
    assert!(
        !request.prompt.contains("the code is fine"),
        "the paste is cut at the boundary: {}",
        request.prompt
    );
    assert!(request.prompt.ends_with("What did I ask?"));
    core.shutdown().await;
}

/// A logical message can span continuation fragments after its root
/// (oversized relayed replies). A boundary on the root copies the WHOLE
/// message — never a truncated prefix — and the native boundary ref comes
/// from the root, where a run stamps it.
#[tokio::test]
async fn a_split_boundary_entry_copies_whole() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    core.workspace
        .set_chat_harness_session("main", "parent-provider-session", "/tmp");
    let source = core.doc_host.open("main").unwrap();
    let root = turn_entry("a1", "first half", MessageStatus::Complete, "turn-1");
    source.doc().push_message(&root).unwrap();
    let mut fragment = message(
        "a1#c1",
        MessageRole::Assistant,
        "second half",
        MessageStatus::Complete,
    );
    fragment.continuation_of = Some("a1".into());
    source.doc().push_message(&fragment).unwrap();
    source
        .doc()
        .push_message(&turn_entry(
            "a2",
            "later reply",
            MessageStatus::Complete,
            "turn-2",
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    // Both the root id and a fragment id resolve to the whole message.
    for (chat, boundary) in [("side-root", "a1"), ("side-fragment", "a1#c1")] {
        client
            .call(
                methods::FORK_SIDE_CHAT,
                serde_json::json!({
                    "chatId": chat,
                    "sourceChatId": "main",
                    "boundary": boundary,
                }),
            )
            .await
            .unwrap();
        let ids: Vec<String> = core
            .doc_host
            .open(chat)
            .unwrap()
            .doc()
            .read_entries()
            .unwrap()
            .into_iter()
            .map(|entry| entry.id)
            .collect();
        assert_eq!(
            ids,
            vec![
                "a1".to_string(),
                "a1#c1".to_string(),
                format!("fork:{chat}"),
            ],
            "boundary {boundary} copies the whole logical message"
        );
        let request = side_turn(&core, &requests, chat, "Continue", "s1").await;
        let fork = request.provider_fork.expect("the root's ref drives the fork");
        assert_eq!(fork.last_turn, "turn-1", "boundary {boundary}");
    }
    core.shutdown().await;
}

#[tokio::test]
async fn cannot_fork_an_empty_chat_or_overwrite_a_main_chat() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat("main", None, Some(&core.device_id), None, None)
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    assert!(
        client
            .call(
                methods::FORK_SIDE_CHAT,
                serde_json::json!({ "chatId": "side", "sourceChatId": "main" })
            )
            .await
            .is_err()
    );
    assert!(core.workspace.chat("side").unwrap().is_none());
    assert!(
        client
            .call(
                methods::FORK_SIDE_CHAT,
                serde_json::json!({ "chatId": "main", "sourceChatId": "main" })
            )
            .await
            .is_err()
    );
}

#[tokio::test]
async fn forking_a_side_chat_can_land_as_a_sibling_under_the_main_chat() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat("main", None, Some(&core.device_id), None, None)
        .unwrap();
    let main = core.doc_host.open("main").unwrap();
    main.doc()
        .push_message(&message(
            "u1",
            MessageRole::User,
            "hi",
            MessageStatus::Complete,
        ))
        .unwrap();
    main.doc()
        .push_message(&message(
            "a1",
            MessageRole::Assistant,
            "hello",
            MessageStatus::Complete,
        ))
        .unwrap();
    let client = roboco_rpc::memory_client(core.rpc_service());
    // A first-level side chat, then its own fork button: the copy hangs
    // under MAIN (the side chat's parent), not under the side chat.
    let side = client
        .call_as::<roboco_proto::Chat>(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "side", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    core.workspace.rename_chat("side", "Side quest").unwrap();
    assert_eq!(side.parent_chat_id.as_deref(), Some("main"));
    // A turn of its own after the seam, so the seam sits inside the frozen
    // prefix the next fork copies.
    let side_doc = core.doc_host.open("side").unwrap();
    side_doc
        .doc()
        .push_message(&message(
            "u2",
            MessageRole::User,
            "more",
            MessageStatus::Complete,
        ))
        .unwrap();
    side_doc
        .doc()
        .push_message(&message(
            "a2",
            MessageRole::Assistant,
            "sure",
            MessageStatus::Complete,
        ))
        .unwrap();
    let sibling = client
        .call_as::<roboco_proto::Chat>(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({
                "chatId": "side-2",
                "sourceChatId": "side",
                "parentChatId": "main",
            }),
        )
        .await
        .unwrap();
    assert_eq!(sibling.parent_chat_id.as_deref(), Some("main"));
    let entries = core
        .doc_host
        .open("side-2")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    // Lineage survives: the side chat's own seam is copied, then a new one
    // names the side chat (by its title at fork time) as this copy's source.
    let seams: Vec<_> = entries
        .iter()
        .filter_map(|e| match e.parts.first() {
            Some(roboco_doc::MessagePart::Fork { source_title, .. }) => Some(source_title.clone()),
            _ => None,
        })
        .collect();
    // The untitled main chat falls back to the placeholder title.
    assert_eq!(seams, ["New session", "Side quest"]);
    // An empty parent falls back to the source, like an omitted one.
    let nested = client
        .call_as::<roboco_proto::Chat>(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({
                "chatId": "side-3",
                "sourceChatId": "side",
                "parentChatId": "",
            }),
        )
        .await
        .unwrap();
    assert_eq!(nested.parent_chat_id.as_deref(), Some("side"));
}

/// One turn through the engine; returns the request the provider received.
async fn side_turn(
    core: &EngineCore,
    requests: &Arc<Mutex<Vec<RunRequest>>>,
    chat: &str,
    prompt: &str,
    message_id: &str,
) -> RunRequest {
    let before = requests.lock().unwrap().len();
    let resume = core
        .workspace
        .chat(chat)
        .unwrap()
        .unwrap()
        .harness_session_id;
    core.sessions
        .dispatch(
            chat,
            HarnessId::Mock,
            RunRequest {
                mcp: None,
                prompt: prompt.into(),
                harness: Some(HarnessId::Mock),
                model: None,
                reasoning: None,
                model_options: Default::default(),
                cwd: "/tmp".into(),
                sandbox: SandboxLevel::WorkspaceWrite,
                auto_approve: true,
                resume,
                provider_fork: None,
                attachments: vec![],
                worktree: None,
            },
            Some(message_id.into()),
            None,
        )
        .await
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        while requests.lock().unwrap().len() == before
            || core
                .sessions
                .session_status(chat)
                .is_some_and(|s| s.status != roboco_proto::SessionStatus::Idle)
        {
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    requests.lock().unwrap()[before].clone()
}

/// A native command must lead the delivered prompt so the provider routes it
/// (Codex `command_request`, OpenCode commands): no bootstrap wrapper in front
/// of it, and none at all when there is no prior conversation.
#[tokio::test]
async fn native_commands_and_empty_side_chats_skip_the_history_wrapper() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    for (id, parent) in [
        ("main", None),
        ("plus-review", Some("main")),
        ("plus", Some("main")),
    ] {
        core.workspace
            .create_chat_with_parent(
                id,
                None,
                Some(&core.device_id),
                None,
                Some("/tmp".into()),
                parent.map(str::to_owned),
                None,
            )
            .unwrap();
    }
    // A fresh `+` side chat: nothing to bootstrap.
    let request = side_turn(&core, &requests, "plus-review", "/review", "u-review").await;
    assert_eq!(request.prompt, "/review");
    let request = side_turn(&core, &requests, "plus", "hello there", "u-hello").await;
    assert_eq!(request.prompt, "hello there");

    // A fork whose first turn is a command: the command goes out bare, and
    // the copied history rides the first ordinary turn, once.
    let source = core.doc_host.open("main").unwrap();
    for (id, role, text) in [
        ("u1", MessageRole::User, "Remember PINEAPPLE"),
        ("a1", MessageRole::Assistant, "I remember PINEAPPLE"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let request = side_turn(&core, &requests, "fork", "  /review tests", "f1").await;
    assert_eq!(request.resume, None);
    assert_eq!(request.prompt, "  /review tests");
    let request = side_turn(&core, &requests, "fork", "What should I remember?", "f2").await;
    assert_eq!(request.resume.as_deref(), Some("side-provider-session"));
    assert!(request.prompt.contains("PINEAPPLE"), "{}", request.prompt);
    assert!(request.prompt.ends_with("What should I remember?"));
    assert!(!request.prompt.contains("/review"), "{}", request.prompt);
    let request = side_turn(&core, &requests, "fork", "And now?", "f3").await;
    assert_eq!(request.prompt, "And now?");
    core.shutdown().await;
}

/// A provider runtime that stays alive between turns: later sends arrive
/// through the steering mailbox (the engine's warm dispatch), not new runs.
/// Each record: (prompt, resumed session, arrived through the mailbox).
type WarmLog = Arc<Mutex<Vec<(String, Option<String>, bool)>>>;
struct Warm(WarmLog);
#[async_trait]
impl Harness for Warm {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Warm"
    }
    fn supports_steering(&self) -> bool {
        true
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
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        self.0
            .lock()
            .unwrap()
            .push((request.prompt.clone(), request.resume.clone(), false));
        let turn = |text: &str| {
            vec![
                Ok(AgentEvent::TextDelta { text: text.into() }),
                Ok(AgentEvent::Done {
                    status: DoneStatus::Completed,
                    result: None,
                    error: None,
                    session_id: Some("warm-session".into()),
                }),
            ]
        };
        let mut first = vec![Ok(AgentEvent::SessionStarted {
            harness: HarnessId::Mock,
            model: "mock-1".into(),
            tools: vec![],
            cwd: request.cwd.clone(),
            session_id: "warm-session".into(),
            assistant_message_id: uuid::Uuid::new_v4().to_string(),
        })];
        first.extend(turn("first answer"));
        let seen = self.0.clone();
        let later = futures::stream::unfold(controls.steering, move |mut steering| {
            let seen = seen.clone();
            async move {
                let message = steering.recv().await?;
                seen.lock().unwrap().push((message.prompt, None, true));
                let mut events = vec![Ok(AgentEvent::Steered {
                    assistant_message_id: None,
                    next_assistant_message_id: Some(uuid::Uuid::new_v4().to_string()),
                })];
                events.extend(turn("later answer"));
                Some((futures::stream::iter(events), steering))
            }
        })
        .flatten();
        Ok(futures::stream::iter(first).chain(later).boxed())
    }
}

/// A fork whose first turn is a native command, continued in the SAME live
/// provider runtime: the copied history must ride the next ordinary send
/// (warm dispatch or steer), exactly once, and survive a cold resume.
#[tokio::test]
async fn warm_side_chat_sends_owed_fork_history_once() {
    let dir = tempfile::tempdir().unwrap();
    let seen = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Warm(seen.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    for (id, role, text) in [
        ("u1", MessageRole::User, "Remember PINEAPPLE"),
        ("a1", MessageRole::Assistant, "I remember PINEAPPLE"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let request = |prompt: &str| RunRequest {
        mcp: None,
        prompt: prompt.into(),
        harness: Some(HarnessId::Mock),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        resume: None,
        provider_fork: None,
        attachments: vec![],
        worktree: None,
    };
    let settle = |count: usize| {
        let seen = seen.clone();
        let core = &core;
        async move {
            tokio::time::timeout(std::time::Duration::from_secs(5), async {
                while seen.lock().unwrap().len() < count
                    || core
                        .sessions
                        .session_status("fork")
                        .is_some_and(|s| s.status != roboco_proto::SessionStatus::Idle)
                {
                    tokio::time::sleep(std::time::Duration::from_millis(5)).await;
                }
            })
            .await
            .unwrap();
            seen.lock().unwrap()[count - 1].0.clone()
        }
    };
    let dispatch = |prompt: &'static str, id: &'static str| {
        core.sessions
            .dispatch("fork", HarnessId::Mock, request(prompt), Some(id.into()), None)
    };
    dispatch("/review", "f1").await.unwrap();
    assert_eq!(settle(1).await, "/review");
    let doc = core.doc_host.open("fork").unwrap();
    assert_eq!(
        doc.doc().fork_history_session(),
        None,
        "nothing delivered yet"
    );
    // Warm dispatch into the live runtime: the owed history goes out.
    dispatch("What should you remember?", "f2").await.unwrap();
    let warm = settle(2).await;
    assert!(warm.contains("PINEAPPLE"), "{warm}");
    assert!(warm.ends_with("What should you remember?"), "{warm}");
    assert!(seen.lock().unwrap()[1].2, "delivered to the live runtime");
    assert_eq!(
        doc.doc().fork_history_session().as_deref(),
        Some("warm-session")
    );
    // Delivered: an explicit steer and later sends go out bare.
    core.sessions
        .steer("fork", "And now?", Some("f3".into()), None)
        .await
        .unwrap();
    assert_eq!(settle(3).await, "And now?");
    assert!(seen.lock().unwrap()[2].2);
    // A cold resume of the same provider session owes nothing either.
    core.sessions.interrupt("fork").await.unwrap();
    dispatch("Again", "f4").await.unwrap();
    assert_eq!(settle(4).await, "Again");
    assert!(!seen.lock().unwrap()[3].2, "a new runtime");
    assert_eq!(
        seen.lock().unwrap()[3].1.as_deref(),
        Some("warm-session"),
        "resumed the provider session"
    );
    core.shutdown().await;
}

/// A runtime that answers its first turn, then exits once released without
/// reading its mailbox: sends routed into it are orphaned and re-dispatched.
struct Dropping {
    runs: Arc<Mutex<Vec<(String, Option<String>)>>>,
    release: Arc<tokio::sync::Notify>,
}
#[async_trait]
impl Harness for Dropping {
    fn id(&self) -> HarnessId {
        HarnessId::Mock
    }
    fn display_name(&self) -> &str {
        "Dropping"
    }
    fn supports_steering(&self) -> bool {
        true
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
        controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        let first = {
            let mut runs = self.runs.lock().unwrap();
            runs.push((request.prompt.clone(), request.resume.clone()));
            runs.len() == 1
        };
        let events = vec![
            Ok(AgentEvent::SessionStarted {
                harness: HarnessId::Mock,
                model: "mock-1".into(),
                tools: vec![],
                cwd: request.cwd.clone(),
                session_id: "drop-session".into(),
                assistant_message_id: uuid::Uuid::new_v4().to_string(),
            }),
            Ok(AgentEvent::TextDelta {
                text: "answer".into(),
            }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some("drop-session".into()),
            }),
        ];
        let release = self.release.clone();
        // The first runtime lingers (mailbox unread) until released, then
        // ends; later ones end after their turn.
        let tail = futures::stream::once(async move {
            let _mailbox = controls.steering;
            if first {
                release.notified().await;
            }
        })
        .filter_map(|_| async { None });
        Ok(futures::stream::iter(events).chain(tail).boxed())
    }
}

/// A steer carrying the owed fork history counts as delivered only once the
/// runtime consumes it: orphaned in a dying runtime, its re-dispatch into
/// the same provider session still carries the history.
#[tokio::test]
async fn orphaned_history_steer_still_owes_the_history() {
    let dir = tempfile::tempdir().unwrap();
    let runs = Arc::new(Mutex::new(Vec::new()));
    let release = Arc::new(tokio::sync::Notify::new());
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Dropping {
        runs: runs.clone(),
        release: release.clone(),
    }));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    for (id, role, text) in [
        ("u1", MessageRole::User, "Remember PINEAPPLE"),
        ("a1", MessageRole::Assistant, "I remember PINEAPPLE"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let request = |prompt: &str| RunRequest {
        mcp: None,
        prompt: prompt.into(),
        harness: Some(HarnessId::Mock),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        resume: None,
        provider_fork: None,
        attachments: vec![],
        worktree: None,
    };
    core.sessions
        .dispatch(
            "fork",
            HarnessId::Mock,
            request("/review"),
            Some("f1".into()),
            None,
        )
        .await
        .unwrap();
    let idle = || {
        core.sessions
            .session_status("fork")
            .is_some_and(|s| s.status == roboco_proto::SessionStatus::Idle)
    };
    for _ in 0..500 {
        if idle() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    assert!(idle(), "the /review turn ends; its runtime lingers");
    // Routed into the live runtime's mailbox, never read.
    core.sessions
        .dispatch(
            "fork",
            HarnessId::Mock,
            request("What should you remember?"),
            Some("f2".into()),
            None,
        )
        .await
        .unwrap();
    let doc = core.doc_host.open("fork").unwrap();
    assert_eq!(doc.doc().fork_history_session(), None, "not consumed yet");
    assert_eq!(runs.lock().unwrap().len(), 1);
    release.notify_one();
    for _ in 0..500 {
        if runs.lock().unwrap().len() == 2 && idle() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    let (prompt, resume) = runs.lock().unwrap()[1].clone();
    assert_eq!(
        resume.as_deref(),
        Some("drop-session"),
        "same provider session"
    );
    assert!(prompt.contains("PINEAPPLE"), "{prompt}");
    assert!(prompt.ends_with("What should you remember?"), "{prompt}");
    assert_eq!(
        doc.doc().fork_history_session().as_deref(),
        Some("drop-session")
    );
    core.shutdown().await;
}

// ── budgeted fork transcript (t3-orchestration-v2 ticket 01) ─────────────────

/// The count inside the coverage header's "N messages were omitted".
fn omitted_count(prompt: &str) -> usize {
    let at = prompt
        .find(" messages were omitted")
        .expect("coverage header");
    let start = prompt[..at]
        .char_indices()
        .rev()
        .find(|(_, c)| !c.is_ascii_digit())
        .map(|(i, _)| i + 1)
        .unwrap_or(0);
    prompt[start..at].parse().expect("omitted count")
}

/// A fork of a long chat no longer pastes the whole transcript: what goes
/// in front of the new message is a 16k-byte selection that keeps the first
/// and latest user messages and the latest assistant message, fills with
/// the newest remainder, and carries a coverage header with the omitted
/// count and the `read_chat` pointer to the source chat. The new user
/// message rides whole at the end.
#[tokio::test]
async fn a_long_fork_sends_budgeted_transcript_with_a_coverage_header() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    for (id, role, text) in [
        (
            "u0",
            MessageRole::User,
            "FIRSTASK: build the budgeted fork transcript",
        ),
        ("a0", MessageRole::Assistant, "FIRSTANS: on it"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    for i in 1..=30 {
        source
            .doc()
            .push_message(&message(
                &format!("u{i}"),
                MessageRole::User,
                &format!("FILLER-{i}: {}", "x".repeat(900)),
                MessageStatus::Complete,
            ))
            .unwrap();
        source
            .doc()
            .push_message(&message(
                &format!("a{i}"),
                MessageRole::Assistant,
                &format!("FILLERA-{i}: {}", "y".repeat(500)),
                MessageStatus::Complete,
            ))
            .unwrap();
    }
    for (id, role, text) in [
        ("u99", MessageRole::User, "LASTASK: ship it"),
        ("a99", MessageRole::Assistant, "LASTANS: done"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let new_message = "What did I ask you to do first?";
    let request = side_turn(&core, &requests, "fork", new_message, "f1").await;
    let prompt = request.prompt;
    // The new user message is never shortened and always last.
    assert!(prompt.ends_with(new_message), "{prompt}");
    // The priorities survive every budget.
    for marker in ["FIRSTASK", "LASTASK", "LASTANS"] {
        assert!(prompt.contains(marker), "missing {marker}: {prompt}");
    }
    // Recent context is preferred over the middle of the transcript.
    assert!(prompt.contains("FILLER-30:"), "{prompt}");
    assert!(!prompt.contains("FILLER-2:"), "{prompt}");
    // The coverage header: omitted count, read_chat, the source chat id.
    assert!(omitted_count(&prompt) >= 1);
    assert!(prompt.contains("read_chat"), "{prompt}");
    assert!(prompt.contains("chat id \"main\""), "{prompt}");
    // Everything in front of the new message fits the budget.
    let delivered = &prompt[..prompt.len() - new_message.len()];
    assert!(
        delivered.len() <= TRANSCRIPT_BUDGET_BYTES,
        "delivered {} bytes",
        delivered.len()
    );
    core.shutdown().await;
}

/// A fork of a chat whose whole transcript fits the budget sends it complete
/// — every message verbatim, no omission note, no truncation.
#[tokio::test]
async fn a_short_fork_sends_its_whole_transcript_without_an_omission_note() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    for (id, role, text) in [
        ("u1", MessageRole::User, "Remember PINEAPPLE"),
        ("a1", MessageRole::Assistant, "I remember PINEAPPLE"),
        ("u2", MessageRole::User, "And the code?"),
        ("a2", MessageRole::Assistant, "Checked in"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let new_message = "What did I ask you to remember?";
    let request = side_turn(&core, &requests, "fork", new_message, "f1").await;
    let prompt = request.prompt;
    assert!(prompt.ends_with(new_message), "{prompt}");
    assert!(prompt.contains("<transcript>"), "{prompt}");
    // Complete means complete.
    for text in [
        "Remember PINEAPPLE",
        "I remember PINEAPPLE",
        "And the code?",
        "Checked in",
    ] {
        assert!(prompt.contains(text), "missing {text}: {prompt}");
    }
    assert!(!prompt.contains("omitted"), "{prompt}");
    assert!(!prompt.contains("read_chat"), "{prompt}");
    assert!(!prompt.contains("…[truncated]"), "{prompt}");
    core.shutdown().await;
}

/// A single message larger than the whole budget is truncated — present,
/// marked, bounded — while the rest of the transcript and the new user
/// message ride along whole.
#[tokio::test]
async fn an_oversized_fork_message_is_truncated_not_dropped() {
    let dir = tempfile::tempdir().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(Capture(requests.clone())));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Mock).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    let source = core.doc_host.open("main").unwrap();
    let big = format!("BIGMARK {}", "z".repeat(30_000));
    for (id, role, text) in [
        ("u0", MessageRole::User, "FIRST: the original request"),
        ("a0", MessageRole::Assistant, "acknowledged"),
        ("u1", MessageRole::User, big.as_str()),
        ("a1", MessageRole::Assistant, "done"),
    ] {
        source
            .doc()
            .push_message(&message(id, role, text, MessageStatus::Complete))
            .unwrap();
    }
    let client = roboco_rpc::memory_client(core.rpc_service());
    client
        .call(
            methods::FORK_SIDE_CHAT,
            serde_json::json!({ "chatId": "fork", "sourceChatId": "main" }),
        )
        .await
        .unwrap();
    let new_message = "Summarize what I asked for.";
    let request = side_turn(&core, &requests, "fork", new_message, "f1").await;
    let prompt = request.prompt;
    // The oversized message is present but cut, visibly.
    assert!(prompt.contains("BIGMARK"), "{prompt}");
    assert!(prompt.contains("…[truncated]"), "{prompt}");
    assert!(prompt.contains(&"z".repeat(4000)), "{prompt}");
    assert!(!prompt.contains(&"z".repeat(5000)), "{prompt}");
    // The other messages still ride along.
    assert!(prompt.contains("FIRST: the original request"), "{prompt}");
    assert!(prompt.contains("done"), "{prompt}");
    // Nothing was omitted, but the truncation still points at read_chat.
    assert!(!prompt.contains("messages were omitted"), "{prompt}");
    assert!(prompt.contains("read_chat"), "{prompt}");
    assert!(prompt.contains("chat id \"main\""), "{prompt}");
    // Bounded, and the new message whole at the end.
    let delivered = &prompt[..prompt.len() - new_message.len()];
    assert!(
        delivered.len() <= TRANSCRIPT_BUDGET_BYTES,
        "delivered {} bytes",
        delivered.len()
    );
    assert!(prompt.ends_with(new_message), "{prompt}");
    core.shutdown().await;
}

