//! Provider switching between turns (upstream #966, ported onto Roboco's
//! SwitchHarness machinery): the new provider never sees the old one's
//! resume id, is handed the prior transcript once under the "taking over"
//! lead, and the switch is seamed in the transcript. Switching BACK resumes
//! the provider's own recorded session (its `ProviderSessionMark`
//! watermark) and hands it only the turns it missed, under the "while you
//! were away" lead.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use futures::stream::BoxStream;

use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::{Harness, HarnessError, RunControls};
use roboco_proto::{
    AgentEvent, ChatConfig, DoneStatus, HarnessId, Model, ReasoningLevel, RunRequest, SandboxLevel,
    SessionStatus, SteeringMode,
};

const CLAUDE: HarnessId = HarnessId::ClaudeCode;
const CODEX: HarnessId = HarnessId::Codex;

/// Every request a provider received, in order: (harness, request).
type Seen = Arc<Mutex<Vec<(HarnessId, RunRequest)>>>;

/// A fake provider: records every request it receives and answers with a
/// session id only it could have minted (`<name>-session`).
struct Fake {
    id: HarnessId,
    name: &'static str,
    seen: Seen,
    /// Runs left that fail before a session starts (signed out, say).
    failures: Arc<std::sync::atomic::AtomicUsize>,
}

#[async_trait]
impl Harness for Fake {
    fn id(&self) -> HarnessId {
        self.id
    }
    fn display_name(&self) -> &str {
        self.name
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
        self.seen.lock().unwrap().push((self.id, request.clone()));
        let fail = self
            .failures
            .fetch_update(
                std::sync::atomic::Ordering::SeqCst,
                std::sync::atomic::Ordering::SeqCst,
                |n| n.checked_sub(1),
            )
            .is_ok();
        if fail {
            return Ok(futures::stream::iter(vec![Ok(AgentEvent::Done {
                status: DoneStatus::Errored,
                result: None,
                error: Some("not signed in".into()),
                session_id: None,
            })])
            .boxed());
        }
        let session = format!("{}-session", self.name.to_lowercase());
        let reply = format!("{} answer", self.name);
        let reply_id = request
            .prompt
            .chars()
            .rev()
            .take(3)
            .collect::<String>()
            .replace(' ', "-");
        Ok(futures::stream::iter(vec![
            Ok(AgentEvent::SessionStarted {
                harness: self.id,
                model: String::new(),
                tools: vec![],
                cwd: request.cwd.clone(),
                session_id: session,
                assistant_message_id: format!("{reply_id}-a"),
            }),
            Ok(AgentEvent::TextDelta { text: reply }),
            Ok(AgentEvent::Done {
                status: DoneStatus::Completed,
                result: None,
                error: None,
                session_id: Some(format!("{}-session", self.name.to_lowercase())),
            }),
        ])
        .boxed())
    }
}

struct Rig {
    core: EngineCore,
    seen: Seen,
    _dir: tempfile::TempDir,
}

fn rig_with(codex_failures: usize) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let seen: Seen = Arc::default();
    let registry = HarnessRegistry::new();
    for (id, name) in [(CLAUDE, "Claude"), (CODEX, "Codex")] {
        registry.register(Arc::new(Fake {
            id,
            name,
            seen: seen.clone(),
            failures: Arc::new(std::sync::atomic::AtomicUsize::new(
                if id == CODEX { codex_failures } else { 0 },
            )),
        }));
    }
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), CLAUDE).unwrap();
    core.workspace
        .create_chat(
            "main",
            None,
            Some(&core.device_id),
            None,
            Some("/tmp".into()),
        )
        .unwrap();
    Rig {
        core,
        seen,
        _dir: dir,
    }
}

fn rig() -> Rig {
    rig_with(0)
}

fn run_request(harness: HarnessId, prompt: &str) -> RunRequest {
    RunRequest {
        mcp: None,
        prompt: prompt.into(),
        harness: Some(harness),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        resume: None,
        attachments: vec![],
        worktree: None,
        provider_fork: None,
        resume_fallback_history: None,
    }
}

impl Rig {
    async fn wait_idle(&self, count: usize) {
        tokio::time::timeout(Duration::from_secs(5), async {
            while self.seen.lock().unwrap().len() < count
                || self.core.sessions.session_status("main").is_some_and(|s| {
                    matches!(
                        s.status,
                        SessionStatus::Working | SessionStatus::AwaitingInput
                    )
                })
            {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("turn settles");
    }

    /// One full turn on `harness`; returns the request the provider received.
    async fn turn(&self, harness: HarnessId, prompt: &str, message_id: &str) -> RunRequest {
        let before = self.seen.lock().unwrap().len();
        self.core
            .sessions
            .dispatch(
                "main",
                harness,
                run_request(harness, prompt),
                Some(message_id.into()),
                None,
                None,
            )
            .await
            .unwrap();
        self.wait_idle(before + 1).await;
        // The row write rides the Done event; let it land.
        tokio::time::sleep(Duration::from_millis(30)).await;
        self.seen.lock().unwrap()[before].1.clone()
    }

    /// [`turn`](Self::turn) as a real send runs it: the send path writes the
    /// user's message to the transcript before it dispatches.
    async fn sent_turn(&self, harness: HarnessId, prompt: &str, message_id: &str) -> RunRequest {
        self.core
            .doc_host
            .open("main")
            .unwrap()
            .doc()
            .push_message(&SessionMessageEntry {
                origin_chat_id: None,
                duration_ms: None,
                provider_turn: None,
                id: message_id.into(),
                role: MessageRole::User,
                parts: vec![MessagePart::Text {
                    id: format!("{message_id}-t"),
                    text: prompt.into(),
                }],
                created_at: 0,
                device_id: "phone".into(),
                status: Some(MessageStatus::Complete),
                continuation_of: None,
            })
            .unwrap();
        self.turn(harness, prompt, message_id).await
    }

    /// Switch the chat onto `harness` between turns (Roboco's SwitchHarness
    /// path — upstream drives the same through its chat config).
    async fn switch(&self, harness: HarnessId) {
        self.core
            .sessions
            .switch_harness(
                "main",
                ChatConfig {
                    harness,
                    model: None,
                    reasoning: None,
                    model_options: Default::default(),
                    sandbox: SandboxLevel::WorkspaceWrite,
                },
            )
            .await
            .unwrap();
    }

    fn row(&self) -> roboco_proto::Chat {
        self.core.workspace.chat("main").unwrap().unwrap()
    }

    fn entries(&self) -> Vec<SessionMessageEntry> {
        self.core
            .doc_host
            .open("main")
            .unwrap()
            .doc()
            .read_entries()
            .unwrap()
    }

    /// (from, to) harness ids of every switch seam.
    fn seams(&self) -> Vec<(Option<HarnessId>, HarnessId)> {
        self.entries()
            .iter()
            .flat_map(|entry| entry.parts.iter())
            .filter_map(|part| match part {
                MessagePart::HarnessSwitch {
                    harness, from_harness, ..
                } => Some((*from_harness, *harness)),
                _ => None,
            })
            .collect()
    }
}

#[tokio::test]
async fn switching_provider_drops_the_foreign_resume_id_and_replays_the_history_once() {
    let rig = rig();

    let first = rig.turn(CLAUDE, "Remember PINEAPPLE", "u1").await;
    assert_eq!(first.resume, None);
    assert_eq!(
        first.prompt, "Remember PINEAPPLE",
        "a plain chat is never wrapped"
    );
    assert_eq!(
        rig.row().harness_session_id.as_deref(),
        Some("claude-session")
    );

    // Same provider again: resumes its own session, still unwrapped.
    let again = rig.turn(CLAUDE, "and MANGO", "u2").await;
    assert_eq!(again.resume.as_deref(), Some("claude-session"));
    assert_eq!(again.prompt, "and MANGO");
    assert!(rig.seams().is_empty());

    // Switch to Codex: Claude's session id must not reach it.
    rig.switch(CODEX).await;
    let switched = rig.turn(CODEX, "What fruit did I mention?", "u3").await;
    assert_eq!(
        switched.resume, None,
        "a foreign session id is never resumed"
    );
    for needle in [
        "PINEAPPLE",
        "MANGO",
        "Claude answer",
        "taking over",
        "What fruit did I mention?",
    ] {
        assert!(
            switched.prompt.contains(needle),
            "history prompt is missing {needle:?}: {}",
            switched.prompt
        );
    }
    assert_eq!(
        rig.seams(),
        vec![(Some(CLAUDE), CODEX)],
        "the seam names both providers"
    );
    assert_eq!(
        rig.row().harness_session_id.as_deref(),
        Some("codex-session")
    );

    // The seam sits above the message that triggered the switch.
    let entries = rig.entries();
    let seam = entries
        .iter()
        .position(|e| {
            e.parts
                .iter()
                .any(|p| matches!(p, MessagePart::HarnessSwitch { .. }))
        })
        .unwrap();
    assert_eq!(entries[seam].role, MessageRole::System);
    assert_eq!(entries[seam + 1].id, "u3");

    // Codex now holds the history: its next turn resumes and is unwrapped.
    let follow = rig.turn(CODEX, "thanks", "u4").await;
    assert_eq!(follow.resume.as_deref(), Some("codex-session"));
    assert_eq!(
        follow.prompt, "thanks",
        "history is replayed once, not every turn"
    );
    assert_eq!(rig.seams().len(), 1);

    // And back: Claude resumes its own session, which already holds the
    // first two turns, and is handed only what Codex did since.
    rig.switch(CLAUDE).await;
    let back = rig.turn(CLAUDE, "one more thing", "u5").await;
    assert_eq!(back.resume.as_deref(), Some("claude-session"));
    for needle in ["What fruit did I mention?", "Codex answer", "thanks"] {
        assert!(
            back.prompt.contains(needle),
            "missing {needle:?}: {}",
            back.prompt
        );
    }
    for seen in ["PINEAPPLE", "MANGO", "Claude answer"] {
        assert!(
            !back.prompt.contains(seen),
            "replayed {seen:?}: {}",
            back.prompt
        );
    }
    assert!(back.prompt.contains("While you were away"), "{}", back.prompt);
    assert!(back.prompt.ends_with("one more thing"), "{}", back.prompt);
    assert_eq!(rig.seams().len(), 2);
    assert_eq!(rig.seams()[1], (Some(CODEX), CLAUDE));

    // Claude is caught up: its next turn is unwrapped again.
    let settled = rig.turn(CLAUDE, "and done", "u6").await;
    assert_eq!(settled.resume.as_deref(), Some("claude-session"));
    assert_eq!(settled.prompt, "and done");

    // Over to Codex again: it resumes its own session too, catching up on
    // Claude's two turns only.
    rig.switch(CODEX).await;
    let codex = rig.turn(CODEX, "your turn", "u7").await;
    assert_eq!(codex.resume.as_deref(), Some("codex-session"));
    assert!(codex.prompt.contains("one more thing"), "{}", codex.prompt);
    assert!(codex.prompt.contains("and done"), "{}", codex.prompt);
    assert!(
        !codex.prompt.contains("What fruit did I mention?"),
        "{}",
        codex.prompt
    );
    assert_eq!(rig.seams().len(), 3);
}

/// Regression: the send path writes the triggering message before dispatch.
/// The outgoing provider was marked as having seen it, so a switch back
/// handed that provider the other's reply without the question it answered,
/// and the seam landed between the question and its reply.
#[tokio::test]
async fn a_switch_back_replays_the_message_that_triggered_the_first_switch() {
    let rig = rig();
    rig.sent_turn(CLAUDE, "Remember PINEAPPLE", "u1").await;
    rig.switch(CODEX).await;
    let switched = rig.sent_turn(CODEX, "Which fruit was it?", "u2").await;
    assert!(switched.prompt.contains("PINEAPPLE"), "{}", switched.prompt);

    rig.switch(CLAUDE).await;
    let back = rig.sent_turn(CLAUDE, "and now?", "u3").await;
    assert_eq!(back.resume.as_deref(), Some("claude-session"));
    for needle in ["Which fruit was it?", "Codex answer"] {
        assert!(
            back.prompt.contains(needle),
            "missing {needle:?}: {}",
            back.prompt
        );
    }
    assert!(
        !back.prompt.contains("PINEAPPLE"),
        "Claude already saw it: {}",
        back.prompt
    );

    // Each seam sits directly above the message that triggered it.
    let entries = rig.entries();
    let above = |message: &str| {
        let at = entries.iter().position(|e| e.id == message).unwrap();
        entries[at - 1]
            .parts
            .iter()
            .any(|p| matches!(p, MessagePart::HarnessSwitch { .. }))
    };
    assert!(above("u2") && above("u3"), "{entries:#?}");
}

#[tokio::test]
async fn a_switch_survives_an_engine_restart_and_a_retried_dispatch_writes_one_seam() {
    let dir = tempfile::tempdir().unwrap();
    let seen: Seen = Arc::default();
    let make = |dir: &std::path::Path| {
        let registry = HarnessRegistry::new();
        for (id, name) in [(CLAUDE, "Claude"), (CODEX, "Codex")] {
            registry.register(Arc::new(Fake {
                id,
                name,
                seen: seen.clone(),
                failures: Arc::default(),
            }));
        }
        EngineCore::assemble(dir, Arc::new(registry), CLAUDE).unwrap()
    };

    {
        let core = make(dir.path());
        core.workspace
            .create_chat(
                "main",
                None,
                Some(&core.device_id),
                None,
                Some("/tmp".into()),
            )
            .unwrap();
        core.sessions
            .dispatch(
                "main",
                CLAUDE,
                run_request(CLAUDE, "hello"),
                Some("u1".into()),
                None,
                None,
            )
            .await
            .unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while core
                .sessions
                .session_status("main")
                .is_none_or(|s| s.status != SessionStatus::Idle)
                || core
                    .workspace
                    .chat("main")
                    .unwrap()
                    .unwrap()
                    .harness_session_id
                    .is_none()
            {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
        core.sessions.switch_harness(
            "main",
            ChatConfig {
                harness: CODEX,
                model: None,
                reasoning: None,
                model_options: Default::default(),
                sandbox: SandboxLevel::WorkspaceWrite,
            },
        )
        .await
        .unwrap();
        core.shutdown().await;
    }

    // Fresh process, cold caches: only the persisted row and the recorded
    // provider session survive.
    let core = make(dir.path());
    let before = seen.lock().unwrap().len();
    for id in ["u2", "u2"] {
        // The same message id twice models the startup-crash retry.
        core.sessions
            .dispatch(
                "main",
                CODEX,
                run_request(CODEX, "continue please"),
                Some(id.into()),
                None,
                None,
            )
            .await
            .unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while core
                .sessions
                .session_status("main")
                .is_some_and(|s| s.status != SessionStatus::Idle)
            {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
    }
    let received = seen.lock().unwrap()[before].1.clone();
    assert_eq!(received.resume, None);
    assert!(received.prompt.contains("hello"), "{}", received.prompt);
    let seams = core
        .doc_host
        .open("main")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap()
        .iter()
        .flat_map(|e| e.parts.clone())
        .filter(|p| matches!(p, MessagePart::HarnessSwitch { .. }))
        .count();
    assert_eq!(seams, 1, "a retried dispatch does not stack seams");

    // Restart again: switching back still finds Claude's own session.
    core.shutdown().await;
    drop(core);
    let core = make(dir.path());
    let before = seen.lock().unwrap().len();
    core.sessions.switch_harness(
        "main",
        ChatConfig {
            harness: CLAUDE,
            model: None,
            reasoning: None,
            model_options: Default::default(),
            sandbox: SandboxLevel::WorkspaceWrite,
        },
    )
    .await
    .unwrap();
    core.sessions
        .dispatch(
            "main",
            CLAUDE,
            run_request(CLAUDE, "back to you"),
            Some("u3".into()),
            None,
            None,
        )
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), async {
        while seen.lock().unwrap().len() <= before {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    let back = seen.lock().unwrap()[before].1.clone();
    assert_eq!(back.resume.as_deref(), Some("claude-session"));
    assert!(back.prompt.contains("continue please"), "{}", back.prompt);
    assert!(!back.prompt.contains("hello"), "{}", back.prompt);
}

#[tokio::test]
async fn replayed_history_is_capped_to_the_newest_messages() {
    let rig = rig();
    let doc = rig.core.doc_host.open("main").unwrap();
    let big = "x".repeat(7_000);
    for i in 0..40 {
        let (id, role) = if i % 2 == 0 {
            (format!("u{i}"), MessageRole::User)
        } else {
            (format!("a{i}"), MessageRole::Assistant)
        };
        doc.doc()
            .push_message(&SessionMessageEntry {
                origin_chat_id: None,
                duration_ms: None,
                provider_turn: None,
                id: id.clone(),
                role,
                parts: vec![MessagePart::Text {
                    id: format!("{id}-t"),
                    text: format!("msg-{i} {big}"),
                }],
                created_at: i as i64 + 1,
                device_id: "device".into(),
                status: Some(MessageStatus::Complete),
                continuation_of: None,
            })
            .unwrap();
    }
    // Establish Claude as the owner without adding to the transcript.
    rig.core
        .workspace
        .set_chat_harness_session("main", "claude-session", "/tmp");
    rig.core
        .workspace
        .set_chat_config(
            "main",
            &ChatConfig {
                harness: CLAUDE,
                model: None,
                reasoning: None,
                model_options: Default::default(),
                sandbox: SandboxLevel::WorkspaceWrite,
            },
        )
        .unwrap();
    rig.switch(CODEX).await;

    let received = rig.turn(CODEX, "summarize", "final").await;
    assert!(
        received.prompt.len() < 100_000,
        "prompt is {} bytes",
        received.prompt.len()
    );
    assert!(
        received.prompt.contains("msg-39"),
        "newest message survives"
    );
    // Roboco's budget policy always keeps the first user message (the
    // conversation's opening ask); an old mid-transcript reply is what the
    // budget drops (upstream's newest-only replay drops msg-0 instead).
    assert!(
        !received.prompt.contains("msg-5 "),
        "old mid-transcript message is dropped"
    );
    assert!(received.prompt.contains("messages were omitted"));
}

/// A dispatch on a harness the chat row does not name (Roboco's
/// claim-on-first-command sends) is a switch too: the seam lands above the
/// message, without going through SwitchHarness (upstream writes every
/// seam this way).
#[tokio::test]
async fn a_foreign_harness_dispatch_writes_the_seam_itself() {
    let rig = rig();
    rig.turn(CLAUDE, "Remember PINEAPPLE", "u1").await;
    let switched = rig.turn(CODEX, "over to you", "u2").await;
    assert_eq!(switched.resume, None, "the foreign session never resumes");
    assert!(switched.prompt.contains("PINEAPPLE"), "{}", switched.prompt);
    assert_eq!(rig.seams(), vec![(Some(CLAUDE), CODEX)]);

    // And back the same way: Claude's own session resumes.
    let back = rig.turn(CLAUDE, "and back", "u3").await;
    assert_eq!(back.resume.as_deref(), Some("claude-session"));
    assert!(back.prompt.contains("over to you"), "{}", back.prompt);
    assert!(!back.prompt.contains("PINEAPPLE"), "{}", back.prompt);
    assert_eq!(rig.seams().len(), 2);
}

#[tokio::test]
async fn returning_after_a_failed_switch_resumes_the_owner_without_replaying_its_own_turns() {
    let rig = rig_with(1);
    rig.turn(CLAUDE, "Remember PINEAPPLE", "u1").await;
    // Codex fails before it starts a session: nothing of it to resume.
    rig.turn(CODEX, "What fruit?", "u2").await;
    let back = rig.turn(CLAUDE, "Which fruit was it?", "u3").await;
    assert_eq!(back.resume.as_deref(), Some("claude-session"));
    assert!(
        !back.prompt.contains("PINEAPPLE"),
        "Claude's own session already holds its turn: {}",
        back.prompt
    );
    // The message Codex never answered is all Claude missed.
    assert!(back.prompt.contains("What fruit?"), "{}", back.prompt);
    assert!(
        back.prompt.ends_with("Which fruit was it?"),
        "{}",
        back.prompt
    );
}

#[tokio::test]
async fn every_switch_writes_its_own_seam() {
    let rig = rig_with(1);
    rig.turn(CLAUDE, "Remember PINEAPPLE", "u1").await;
    rig.turn(CODEX, "What fruit?", "u2").await;
    rig.turn(CLAUDE, "Which fruit was it?", "u3").await;
    let again = rig.turn(CODEX, "And now?", "u4").await;
    assert_eq!(again.resume, None, "Codex never started a session");
    assert!(again.prompt.contains("PINEAPPLE"), "{}", again.prompt);
    assert_eq!(
        rig.seams(),
        vec![(Some(CLAUDE), CODEX), (Some(CODEX), CLAUDE), (Some(CLAUDE), CODEX)]
    );
}

#[tokio::test]
async fn a_switched_side_chat_counts_only_its_own_seams() {
    let rig = rig();
    // History copied from a parent that had switched to Codex, then the fork
    // seam: the side chat's own provider history starts below it.
    let doc = rig.core.doc_host.open("main").unwrap();
    for (id, part) in [
        (
            "switch:1",
            MessagePart::HarnessSwitch {
                id: "switch:1".into(),
                harness: CODEX,
                from_harness: Some(CLAUDE),
            },
        ),
        (
            "fork:main",
            MessagePart::Fork {
                id: "fork:main".into(),
                source_chat_id: "parent".into(),
                source_title: "Parent".into(),
            },
        ),
    ] {
        doc.doc()
            .push_message(&SessionMessageEntry {
                origin_chat_id: None,
                duration_ms: None,
                provider_turn: None,
                id: id.into(),
                role: MessageRole::System,
                parts: vec![part],
                created_at: 1,
                device_id: "device".into(),
                status: Some(MessageStatus::Complete),
                continuation_of: None,
            })
            .unwrap();
    }
    rig.turn(CLAUDE, "hello", "u1").await;
    assert_eq!(
        rig.seams().len(),
        1,
        "no seam for the side chat's first run"
    );
    rig.turn(CODEX, "over to you", "u2").await;
    assert_eq!(
        rig.seams().len(),
        2,
        "the side chat's own switch is marked"
    );
}
