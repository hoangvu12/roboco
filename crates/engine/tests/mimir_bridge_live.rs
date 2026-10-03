//! The Mimir vertical slice against the real packaged bridge: a real `mimir`
//! binary, the built `sh.roboco.bridge` package installed into a throwaway
//! agent directory, and the loopback fake provider from
//! `integrations/mimir/tests`. No account, credential or network provider.
//!
//!     ROBOCO_MIMIR_BIN=/path/to/mimir \
//!     ROBOCO_MIMIR_PACKAGE=integrations/mimir/out/sh.roboco.bridge-0.1.0 \
//!     cargo test -p roboco-engine --test mimir_bridge_live -- --nocapture
//!
//! `ROBOCO_MIMIR_ROOT=<dir>` reuses a root whose agent directory already has
//! the package installed. Without the binary and package the test is skipped.
#![cfg(unix)]

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::Arc;
use std::time::Duration;

use roboco_doc::{
    MessagePart, MessageRole, SessionCommandEntry, SessionCommandPayload, SessionCommandStatus,
    SessionMessageEntry,
};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::Harness;
use roboco_harness::mimir::MimirHarness;
use roboco_proto::{
    HarnessId, NativeChatState, NativeChildControl, NativeControl, NativeControlOutcome,
    NativeDelivery, NativeLink, NativeReadiness, NativeRequestStatus, RunRequest, SandboxLevel,
    SessionStatus, ToolCall, UserInputAnswer,
};
use serde_json::{Value, json};

const TIMEOUT: Duration = Duration::from_secs(120);

struct Provider {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl Provider {
    fn start(agent: &Path) -> Self {
        let fixtures =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mimir_provider.py");
        let tests = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../integrations/mimir/tests");
        let mut child = Command::new("python3")
            .arg(fixtures)
            .arg(tests)
            .arg(agent)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .expect("python3 runs the fake provider");
        let stdin = child.stdin.take().unwrap();
        let mut stdout = BufReader::new(child.stdout.take().unwrap());
        let mut ready = String::new();
        stdout.read_line(&mut ready).unwrap();
        assert!(
            ready.contains("\"ready\": true"),
            "provider did not start: {ready}"
        );
        Self {
            child,
            stdin,
            stdout,
        }
    }

    fn send(&mut self, command: Value) -> Value {
        writeln!(self.stdin, "{command}").unwrap();
        self.stdin.flush().unwrap();
        let mut line = String::new();
        self.stdout.read_line(&mut line).unwrap();
        serde_json::from_str(&line).unwrap()
    }

    fn script(&mut self, script: Value, child_script: Value) {
        self.send(json!({"script": script, "child_script": child_script}));
    }
}

impl Drop for Provider {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct Root {
    _temp: Option<tempfile::TempDir>,
    path: PathBuf,
    bin: PathBuf,
}

impl Root {
    fn env(&self) -> Vec<(String, String)> {
        vec![
            ("HOME".into(), self.path.join("home").display().to_string()),
            (
                "MIMIR_CODING_AGENT_DIR".into(),
                self.path.join("agent").display().to_string(),
            ),
            (
                "MIMIR_MODELS_PATH".into(),
                self.path.join("absent-models.json").display().to_string(),
            ),
            ("MIMIR_DISABLE_MODELS_FETCH".into(), "1".into()),
            ("MIMIR_HYPERLINKS".into(), "0".into()),
            ("NO_COLOR".into(), "1".into()),
        ]
    }

    fn harness(&self) -> MimirHarness {
        let mut harness = MimirHarness::new().with_executable(&self.bin);
        for key in [
            "ANTHROPIC_API_KEY",
            "OPENAI_API_KEY",
            "GEMINI_API_KEY",
            "GOOGLE_API_KEY",
            "OPENROUTER_API_KEY",
        ] {
            harness = harness.with_env_removed(key);
        }
        for (key, value) in self.env() {
            harness = harness.with_env(key, value);
        }
        harness
    }

    fn cli(&self, args: &[&str]) -> std::process::Output {
        let mut command = Command::new(&self.bin);
        command.args(args).current_dir(self.path.join("work"));
        for (key, value) in self.env() {
            command.env(key, value);
        }
        command.output().expect("mimir runs")
    }

    fn workdir(&self, name: &str) -> String {
        let dir = self.path.join("work").join(name);
        std::fs::create_dir_all(&dir).unwrap();
        dir.canonicalize().unwrap().display().to_string()
    }
}

fn root() -> Option<Root> {
    let bin = PathBuf::from(std::env::var_os("ROBOCO_MIMIR_BIN")?);
    let package = PathBuf::from(std::env::var_os("ROBOCO_MIMIR_PACKAGE")?);
    let (temp, path) = match std::env::var_os("ROBOCO_MIMIR_ROOT") {
        Some(path) => (None, PathBuf::from(path)),
        None => {
            let temp = tempfile::tempdir().unwrap();
            let path = temp.path().to_path_buf();
            (Some(temp), path)
        }
    };
    for dir in ["home", "agent", "work"] {
        std::fs::create_dir_all(path.join(dir)).unwrap();
    }
    let root = Root {
        _temp: temp,
        path,
        bin,
    };
    let listed = root.cli(&["plugin", "list"]);
    if !String::from_utf8_lossy(&listed.stdout)
        .contains("sh.roboco.bridge (Roboco bridge) 0.1.0 [compatible]")
    {
        let package = package.display().to_string();
        let checked = root.cli(&["plugin", "check", &package]);
        assert!(
            checked.status.success(),
            "{}",
            String::from_utf8_lossy(&checked.stderr)
        );
        let installed = root.cli(&["plugin", "install", &package]);
        assert!(
            installed.status.success(),
            "{}",
            String::from_utf8_lossy(&installed.stderr)
        );
    }
    Some(root)
}

async fn wait_for(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = tokio::time::Instant::now() + TIMEOUT;
    while !condition() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

fn request(cwd: &str, prompt: &str) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: Some(HarnessId::Mimir),
        model: Some("fake/fake-model".into()),
        reasoning: None,
        model_options: Default::default(),
        cwd: cwd.into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        resume: None,
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
    }
}

fn queue(core: &EngineCore, chat: &str, id: &str, payload: SessionCommandPayload) {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .queue_command(&SessionCommandEntry {
            id: id.into(),
            payload,
            issued_by: "viewer".into(),
            issued_at: chrono::Utc::now().timestamp_millis(),
            based_on: None,
            expires_at: None,
            status: SessionCommandStatus::Pending,
            resolution: None,
            outcome: None,
        })
        .unwrap();
}

fn send(core: &EngineCore, chat: &str, cwd: &str, message: &str, prompt: &str) {
    queue(
        core,
        chat,
        &format!("cmd-{message}"),
        SessionCommandPayload::Run {
            request: request(cwd, prompt),
            message_id: message.into(),
        },
    );
}

async fn control(
    core: &EngineCore,
    chat: &str,
    id: &str,
    control: NativeControl,
) -> NativeControlOutcome {
    queue(core, chat, id, SessionCommandPayload::Native { control });
    let doc = core.doc_host.open(chat).unwrap().doc_arc();
    let mut outcome = None;
    wait_for(&format!("control {id}"), || {
        outcome = doc
            .read_commands()
            .unwrap()
            .into_iter()
            .find(|c| c.id == id && c.status != SessionCommandStatus::Pending)
            .and_then(|c| c.outcome);
        outcome.is_some()
    })
    .await;
    outcome.unwrap()
}

fn state(core: &EngineCore, chat: &str) -> NativeChatState {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .native_state()
        .unwrap_or_default()
}

fn entries(core: &EngineCore, chat: &str) -> Vec<SessionMessageEntry> {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .read_entries()
        .unwrap()
}

fn texts(entries: &[SessionMessageEntry], role: MessageRole) -> Vec<String> {
    entries
        .iter()
        .filter(|e| e.role == role)
        .flat_map(|e| e.parts.iter())
        .filter_map(|p| match p {
            MessagePart::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

fn settled(state: &NativeChatState, message: &str) -> bool {
    let Some(request) = state.submissions.iter().find_map(|s| match &s.delivery {
        NativeDelivery::Admitted { request_id } if s.message_id == message => {
            Some(request_id.clone())
        }
        _ => None,
    }) else {
        return false;
    };
    state.active_request.is_none()
        && state
            .requests
            .iter()
            .any(|r| r.id == request && r.status == NativeRequestStatus::Completed)
}

fn native_tools(
    entries: &[SessionMessageEntry],
) -> Vec<(String, roboco_proto::NativeToolView, bool, Option<String>)> {
    entries
        .iter()
        .flat_map(|e| e.parts.iter())
        .filter_map(|p| match p {
            MessagePart::Tool {
                id,
                call: ToolCall::Native { view },
                resolved,
                subagent_ref,
                ..
            } => Some((
                id.clone(),
                (**view).clone(),
                *resolved,
                subagent_ref.clone(),
            )),
            _ => None,
        })
        .collect()
}

fn assemble(engine_dir: &Path, root: &Root) -> (EngineCore, Arc<dyn Harness>) {
    let registry = HarnessRegistry::new();
    let harness: Arc<dyn Harness> = Arc::new(root.harness());
    registry.register(harness.clone());
    let core = EngineCore::assemble(engine_dir, Arc::new(registry), HarnessId::Mimir).unwrap();
    (core, harness)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn mimir_chats_run_park_share_one_bridge_hand_off_and_restore() {
    let Some(root) = root() else {
        eprintln!(
            "skipped: set ROBOCO_MIMIR_BIN and ROBOCO_MIMIR_PACKAGE to run the live Mimir bridge test"
        );
        return;
    };
    let mut provider = Provider::start(&root.path.join("agent"));
    let engine_dir = tempfile::tempdir().unwrap();
    let (core, harness) = assemble(engine_dir.path(), &root);
    let mimir = harness.native().unwrap();

    let readiness = core.sessions.native_readiness(true).await;
    assert!(
        matches!(readiness, NativeReadiness::Ready { ref bridge, .. } if bridge == "sh.roboco.bridge 0.1.0"),
        "{readiness:?}"
    );

    // A first turn: keyed admission, a public tool call with complete
    // details, streamed text, an authoritative completion, the echo kept.
    let cwd_a = root.workdir("chat-a");
    std::fs::write(
        Path::new(&cwd_a).join("note.txt"),
        "native tool result\nline2\n",
    )
    .unwrap();
    provider.script(
        json!([{"tool": "read_file", "args": {"path": "note.txt"}, "id": "call_1"}, {"text": ["hello ", "world"]}]),
        json!([]),
    );
    send(&core, "chat-a", &cwd_a, "a-1", "read the note");
    wait_for("first turn completion", || {
        settled(&state(&core, "chat-a"), "a-1")
    })
    .await;
    wait_for("first turn idle", || {
        core.sessions
            .session_status("chat-a")
            .is_some_and(|s| s.status == SessionStatus::Idle)
    })
    .await;
    let first = entries(&core, "chat-a");
    assert_eq!(
        texts(&first, MessageRole::User),
        vec!["read the note".to_string()],
        "the echo is the only copy of the message"
    );
    assert!(
        first.iter().any(|e| e.id == "a-1"),
        "the user's own message id is kept"
    );
    assert!(texts(&first, MessageRole::Assistant).contains(&"hello world".to_string()));
    assert!(
        !first.iter().any(|e| e.id.starts_with("live-")),
        "no provisional entry survives the checkpoint"
    );
    let tools = native_tools(&first);
    assert_eq!(tools.len(), 1);
    let (part_id, view, resolved, _) = &tools[0];
    assert!(*resolved);
    assert_eq!(view.title, "Read: note.txt");
    assert_eq!(view.kind, roboco_proto::NativeToolKind::FileRead);
    let detail_ref = view.detail_ref.clone().expect("details are referenced");
    let detail = core.doc_host.fetch_tool_blob(&detail_ref).await.unwrap();
    let detail: roboco_proto::NativeToolDetail = serde_json::from_str(&detail).unwrap();
    assert_eq!(detail.input, Some(json!({"paths": ["note.txt"]})));
    assert!(
        detail
            .output
            .as_deref()
            .unwrap()
            .contains("native tool result")
    );
    let window = core
        .doc_host
        .fetch_tool_blob_range(&format!("chat-a/{part_id}"), 0, 16)
        .await
        .unwrap();
    assert_eq!(window.offset, 0);
    assert!(window.total_bytes > 16 && window.next_offset == Some(window.text.len() as u64));
    let conversation = state(&core, "chat-a").conversation.unwrap();
    assert_eq!(conversation.cwd, cwd_a);
    let first_bridge = mimir.live_runtime().await.unwrap();
    wait_for("completion notification", || {
        core.sessions
            .session_status("chat-a")
            .is_some_and(|s| s.last_completed_turn.is_some())
    })
    .await;

    // A question parks the turn; the exact request answers it. The same
    // attachment carries this second turn.
    provider.script(
        json!([{"tool": "ask_user", "args": {"questions": [{"id": "q1", "prompt": "Which?", "options": [
            {"label": "Alpha", "description": "the first path"}, {"label": "Beta", "description": "the second path"}]}]}, "id": "call_q"},
            {"text": "answered"}]),
        json!([]),
    );
    send(&core, "chat-a", &cwd_a, "a-2", "ask me");
    wait_for("pending question", || {
        state(&core, "chat-a").user_request.is_some()
    })
    .await;
    let pending = state(&core, "chat-a").user_request.unwrap();
    assert_eq!(
        pending.questions[0].options[1].description,
        "the second path"
    );
    wait_for("awaiting input status", || {
        core.sessions
            .session_status("chat-a")
            .is_some_and(|s| s.status == SessionStatus::AwaitingInput)
    })
    .await;
    queue(
        &core,
        "chat-a",
        "answer-1",
        SessionCommandPayload::RespondInput {
            request_id: pending.id.clone(),
            answers: vec![UserInputAnswer {
                question_id: "q1".into(),
                labels: vec!["Beta".into()],
            }],
        },
    );
    wait_for("answered turn", || settled(&state(&core, "chat-a"), "a-2")).await;
    assert!(state(&core, "chat-a").user_request.is_none());
    assert!(
        texts(&entries(&core, "chat-a"), MessageRole::Assistant).contains(&"answered".to_string())
    );
    assert_eq!(
        mimir.live_runtime().await.unwrap().generation,
        first_bridge.generation,
        "one bridge across turns"
    );

    // A second chat shares the same bridge process with its own attachment.
    let cwd_b = root.workdir("chat-b");
    provider.script(json!([{"text": "reply b"}]), json!([]));
    send(&core, "chat-b", &cwd_b, "b-1", "hello b");
    wait_for("second chat turn", || {
        settled(&state(&core, "chat-b"), "b-1")
    })
    .await;
    let shared = mimir.live_runtime().await.unwrap();
    assert_eq!(shared.generation, first_bridge.generation);
    assert_eq!(shared.pid, first_bridge.pid);
    assert_eq!(shared.attachment_count(), 2);
    assert_ne!(
        state(&core, "chat-b").conversation.unwrap().id,
        conversation.id
    );

    // A delegated child: typed lineage binds the parent's agent call to the
    // child's own document, which keeps one identity.
    provider.script(
        json!([{"tool": "agent", "args": {"operation": {"kind": "launch", "agent": "general-purpose"},
            "brief": {"objective": "probe child work"}, "description": "Probe child"}, "id": "call_9"},
            {"text": "parent waits", "gate": "parent", "started": "parent-started"}]),
        json!([{"text": ["child ", "result"], "gate": "child", "started": "child-started"}]),
    );
    send(&core, "chat-b", &cwd_b, "b-2", "delegate");
    assert_eq!(provider.send(json!({"wait": "child-started"}))["ok"], true);
    wait_for("running child", || {
        state(&core, "chat-b")
            .children
            .iter()
            .any(|c| c.status == roboco_proto::NativeChildStatus::Running)
    })
    .await;
    let child = state(&core, "chat-b").children[0].clone();
    assert_eq!(child.doc_id, format!("chat-b--sub--{}", child.handle));
    assert_eq!(
        core.sessions.session_status("chat-b").unwrap().status,
        SessionStatus::Working
    );
    let stale = control(
        &core,
        "chat-b",
        "stop-stale",
        NativeControl::StopChild {
            handle: child.handle.clone(),
            attempt: child.attempt + 1,
        },
    )
    .await;
    assert_eq!(
        stale,
        NativeControlOutcome::Child {
            control: NativeChildControl::AttemptChanged {
                attempt: child.attempt
            }
        }
    );
    assert!(
        core.sessions
            .native_child_outcome("chat-b", &child.handle, 1)
            .await
            .unwrap()
            .is_none()
    );
    provider.send(json!({"open": "child"}));
    wait_for("child completion", || {
        state(&core, "chat-b").children.iter().any(|c| {
            c.handle == child.handle && c.status == roboco_proto::NativeChildStatus::Completed
        })
    })
    .await;
    provider.send(json!({"open": "parent"}));
    wait_for("delegating turn", || {
        settled(&state(&core, "chat-b"), "b-2")
    })
    .await;
    wait_for("child bound to its spawn call", || {
        native_tools(&entries(&core, "chat-b"))
            .iter()
            .any(|(_, view, _, subagent)| {
                view.child.as_deref() == Some(child.handle.as_str())
                    && subagent.as_deref() == Some(child.doc_id.as_str())
            })
    })
    .await;
    let child_doc = core.doc_host.open(&child.doc_id).unwrap().doc_arc();
    wait_for("child transcript", || {
        texts(&child_doc.read_entries().unwrap(), MessageRole::Assistant)
            .contains(&"child result".to_string())
    })
    .await;
    let outcome = core
        .sessions
        .native_child_outcome("chat-b", &child.handle, 1)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(outcome.result.as_deref(), Some("child result"));

    // Continuing the child starts attempt 2 under the same handle and in the
    // same right-pane document.
    provider.script(
        json!([{"tool": "agent", "args": {"operation": {"kind": "continue", "handle": child.handle},
            "brief": {"objective": "continue the same work"}, "description": "Continue child"}, "id": "call_10"},
            {"text": "parent integrates"}]),
        json!([{"text": "second result"}]),
    );
    send(&core, "chat-b", &cwd_b, "b-cont", "continue it");
    wait_for("second attempt", || {
        state(&core, "chat-b").children.iter().any(|c| {
            c.handle == child.handle
                && c.attempt == 2
                && c.status == roboco_proto::NativeChildStatus::Completed
        })
    })
    .await;
    wait_for("continuation turn", || {
        settled(&state(&core, "chat-b"), "b-cont")
    })
    .await;
    let children = state(&core, "chat-b").children;
    assert_eq!(children.len(), 1);
    assert_eq!(children[0].doc_id, child.doc_id);
    // The first attempt's record and outcome survive the continuation.
    wait_for("both attempts' outcomes kept", || {
        let attempts = &state(&core, "chat-b").children[0].attempts;
        attempts.len() == 2 && attempts.iter().all(|a| a.outcome_ref.is_some())
    })
    .await;
    let attempts = state(&core, "chat-b").children[0].attempts.clone();
    assert_eq!(
        attempts
            .iter()
            .map(|a| (a.attempt, a.status))
            .collect::<Vec<_>>(),
        vec![
            (1, roboco_proto::NativeChildStatus::Completed),
            (2, roboco_proto::NativeChildStatus::Completed)
        ]
    );
    assert!(attempts[0].presentation.is_some());
    let mut results = Vec::new();
    for attempt in &attempts {
        let blob = core
            .doc_host
            .fetch_tool_blob(attempt.outcome_ref.as_deref().unwrap())
            .await
            .unwrap();
        let outcome: roboco_proto::NativeChildOutcome = serde_json::from_str(&blob).unwrap();
        assert_eq!(outcome.attempt, attempt.attempt);
        assert!(blob.contains("\"usage\""), "{blob}");
        results.push(outcome.result);
    }
    assert_eq!(
        results,
        vec![
            Some("child result".to_string()),
            Some("second result".to_string())
        ]
    );
    let first = core
        .sessions
        .native_child_outcome("chat-b", &child.handle, 1)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        (first.attempt, first.result.as_deref()),
        (1, Some("child result")),
        "the first attempt stays readable after the continuation"
    );
    for part in entries(&core, "chat-b").iter().flat_map(|e| e.parts.iter()) {
        if let MessagePart::Notice {
            notice: roboco_proto::NativeNotice::SubagentCompletions { completions },
            ..
        } = part
        {
            assert!(
                completions
                    .iter()
                    .all(|c| c.usage.is_some() && c.run.is_some()),
                "{completions:?}"
            );
        }
    }
    wait_for("both attempts in one child document", || {
        let child_texts = texts(&child_doc.read_entries().unwrap(), MessageRole::Assistant);
        child_texts.contains(&"child result".to_string())
            && child_texts.contains(&"second result".to_string())
    })
    .await;

    // Continue in Mimir: the idle attachment is released and awaited. The
    // second process stands in for the TUI: same lease, same saved history.
    assert_eq!(
        control(&core, "chat-a", "release-1", NativeControl::Release).await,
        NativeControlOutcome::Released
    );
    assert_eq!(state(&core, "chat-a").link, NativeLink::Released);
    assert_eq!(mimir.live_runtime().await.unwrap().attachment_count(), 1);
    let tui = root.harness();
    let other = tui.runtime().await.unwrap();
    assert_ne!(other.pid, first_bridge.pid);
    let opened = other.open(&conversation.id).await.unwrap();
    let busy = control(&core, "chat-a", "reconnect-busy", NativeControl::Reconnect).await;
    assert!(
        matches!(
            busy,
            NativeControlOutcome::Refused {
                kind: roboco_proto::NativeErrorKind::Busy,
                ..
            }
        ),
        "{busy:?}"
    );
    assert!(matches!(
        state(&core, "chat-a").link,
        NativeLink::Busy { .. }
    ));
    assert_eq!(
        state(&core, "chat-a").conversation.unwrap().id,
        conversation.id,
        "busy never starts a replacement"
    );
    provider.script(json!([{"text": "tui reply"}]), json!([]));
    let input = roboco_harness::mimir::protocol::PromptInput {
        text: "tui turn".into(),
        images: vec![],
        skills: vec![],
    };
    let tui_request = other
        .prompt(
            &opened.session,
            &input,
            roboco_harness::mimir::protocol::Delivery::Start,
            "tui-key",
        )
        .await
        .unwrap();
    wait_for("tui turn", || {
        futures::executor::block_on(other.state(&opened.session)).is_ok_and(|s| {
            s.request(&tui_request).is_some_and(|r| {
                r.status == roboco_harness::mimir::protocol::RequestStatus::Completed
            })
        })
    })
    .await;
    assert!(other.close(&opened.session).await.unwrap());
    other.shutdown().await;
    assert_eq!(
        control(&core, "chat-a", "reconnect-1", NativeControl::Reconnect).await,
        NativeControlOutcome::Applied
    );
    wait_for("tui turn reconciled", || {
        texts(&entries(&core, "chat-a"), MessageRole::Assistant).contains(&"tui reply".to_string())
    })
    .await;
    let after = entries(&core, "chat-a");
    let users = texts(&after, MessageRole::User);
    assert_eq!(
        users,
        vec!["read the note", "ask me", "tui turn"],
        "history in order, nothing duplicated"
    );
    assert_eq!(
        state(&core, "chat-a").conversation.unwrap().id,
        conversation.id
    );

    // Steering mid-turn reaches the next model boundary; its echo stays the
    // only copy, in order between the two halves of the reply.
    let cwd_c = root.workdir("chat-c");
    provider.script(
        json!([{"text": ["slow ", "reply"], "gate": "c1", "started": "c1-started"}, {"text": "after steer"}]),
        json!([]),
    );
    send(&core, "chat-c", &cwd_c, "c-1", "first");
    assert_eq!(provider.send(json!({"wait": "c1-started"}))["ok"], true);
    wait_for("chat-c working", || {
        state(&core, "chat-c").active_request.is_some()
    })
    .await;
    queue(
        &core,
        "chat-c",
        "steer-1",
        SessionCommandPayload::Steer {
            prompt: "please also do X".into(),
            message_id: Some("c-steer".into()),
        },
    );
    wait_for("steer delivered", || {
        core.doc_host
            .open("chat-c")
            .unwrap()
            .doc()
            .read_commands()
            .unwrap()
            .iter()
            .any(|c| c.id == "steer-1" && c.status == SessionCommandStatus::Applied)
    })
    .await;
    provider.send(json!({"open": "c1"}));
    wait_for("steered turn", || settled(&state(&core, "chat-c"), "c-1")).await;
    let steered = entries(&core, "chat-c");
    assert_eq!(
        texts(&steered, MessageRole::User),
        vec!["first", "please also do X"]
    );
    assert!(
        steered.iter().any(|e| e.id == "c-steer"),
        "the steer echo keeps its id"
    );
    let order: Vec<String> = steered
        .iter()
        .flat_map(|e| e.parts.iter())
        .filter_map(|p| match p {
            MessagePart::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(
        order,
        vec!["first", "slow reply", "please also do X", "after steer"]
    );

    // An interrupt cancels exactly this chat's request; a sibling chat on
    // the same bridge keeps working.
    let cwd_d = root.workdir("chat-d");
    provider.script(
        json!([{"text": ["d working "], "gate": "d1", "started": "d1-started"}]),
        json!([]),
    );
    send(&core, "chat-d", &cwd_d, "d-1", "long work");
    assert_eq!(provider.send(json!({"wait": "d1-started"}))["ok"], true);
    provider.script(
        json!([{"text": ["c working "], "gate": "c2", "started": "c2-started"}]),
        json!([]),
    );
    send(&core, "chat-c", &cwd_c, "c-2", "cancel me");
    assert_eq!(provider.send(json!({"wait": "c2-started"}))["ok"], true);
    wait_for("both working", || {
        state(&core, "chat-c").active_request.is_some()
            && state(&core, "chat-d").active_request.is_some()
    })
    .await;
    queue(
        &core,
        "chat-c",
        "interrupt-1",
        SessionCommandPayload::Interrupt {},
    );
    wait_for("exact cancellation", || {
        let c = state(&core, "chat-c");
        c.active_request.is_none()
            && c.requests
                .iter()
                .any(|r| r.status == NativeRequestStatus::Cancelled)
    })
    .await;
    assert!(
        state(&core, "chat-d").active_request.is_some(),
        "the sibling keeps running"
    );
    assert_eq!(
        core.sessions.session_status("chat-d").unwrap().status,
        SessionStatus::Working
    );
    provider.send(json!({"open": "c2"}));
    provider.send(json!({"open": "d1"}));
    wait_for("sibling completes", || {
        settled(&state(&core, "chat-d"), "d-1")
    })
    .await;

    // A message sent while a turn runs is admitted as a follow-up; it stays
    // below the running turn's reply in the transcript.
    provider.script(
        json!([{"text": ["first answer"], "gate": "d2", "started": "d2-started"}, {"text": "follow answer"}]),
        json!([]),
    );
    send(&core, "chat-d", &cwd_d, "d-busy", "busy turn");
    assert_eq!(provider.send(json!({"wait": "d2-started"}))["ok"], true);
    wait_for("busy", || state(&core, "chat-d").active_request.is_some()).await;
    send(&core, "chat-d", &cwd_d, "d-follow", "queued behind it");
    wait_for("follow-up admitted", || {
        matches!(
            state(&core, "chat-d")
                .submissions
                .iter()
                .find(|s| s.message_id == "d-follow")
                .map(|s| &s.delivery),
            Some(NativeDelivery::Admitted { .. })
        )
    })
    .await;
    provider.send(json!({"open": "d2"}));
    wait_for("follow-up answered", || {
        settled(&state(&core, "chat-d"), "d-follow")
    })
    .await;
    let ordered: Vec<String> = entries(&core, "chat-d")
        .iter()
        .flat_map(|e| e.parts.iter())
        .filter_map(|p| match p {
            MessagePart::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .skip_while(|t| t != "busy turn")
        .collect();
    assert_eq!(
        ordered,
        vec![
            "busy turn",
            "first answer",
            "queued behind it",
            "follow answer"
        ]
    );

    // Commands route through the host; an unknown one is refused and never
    // becomes a model prompt.
    let catalog = core.sessions.native_catalog("chat-c").await.unwrap();
    assert!(catalog.commands.iter().any(|c| c.name == "goal"));
    assert_eq!(catalog.providers[0].id, "fake");
    let before_requests = provider.send(json!({"requests": true}))["requests"]
        .as_u64()
        .unwrap();
    send(&core, "chat-c", &cwd_c, "c-3", "/nope do it");
    wait_for("command refusal", || {
        state(&core, "chat-c")
            .submissions
            .iter()
            .any(|s| s.message_id == "c-3" && matches!(s.delivery, NativeDelivery::Refused { .. }))
    })
    .await;
    assert_eq!(
        provider.send(json!({"requests": true}))["requests"]
            .as_u64()
            .unwrap(),
        before_requests
    );

    // Goal and plan are host lifecycles driven by typed controls.
    provider.script(
        json!([{"text": "goal work", "gate": "g1", "started": "g1-started"}]),
        json!([]),
    );
    let started = control(
        &core,
        "chat-d",
        "goal-start",
        NativeControl::ChangeGoal {
            change: roboco_proto::NativeGoalChange::Start {
                objective: "ship the thing".into(),
                duration_seconds: None,
            },
        },
    )
    .await;
    assert!(
        matches!(started, NativeControlOutcome::Admitted { .. }),
        "{started:?}"
    );
    assert_eq!(provider.send(json!({"wait": "g1-started"}))["ok"], true);
    wait_for("active goal", || {
        state(&core, "chat-d")
            .goal
            .is_some_and(|g| g.phase == roboco_proto::NativeGoalPhase::Active)
    })
    .await;
    assert_eq!(
        control(
            &core,
            "chat-d",
            "goal-pause",
            NativeControl::ChangeGoal {
                change: roboco_proto::NativeGoalChange::Pause
            }
        )
        .await,
        NativeControlOutcome::Applied
    );
    wait_for("paused goal", || {
        state(&core, "chat-d").goal.is_some_and(|g| {
            g.phase == roboco_proto::NativeGoalPhase::Paused
                && g.cause == roboco_proto::NativeGoalCause::UserPaused
        })
    })
    .await;
    provider.send(json!({"open": "g1"}));
    wait_for("goal round settled", || {
        state(&core, "chat-d").active_request.is_none()
    })
    .await;
    assert_eq!(
        control(
            &core,
            "chat-d",
            "goal-clear",
            NativeControl::ChangeGoal {
                change: roboco_proto::NativeGoalChange::Clear
            }
        )
        .await,
        NativeControlOutcome::Applied
    );
    let configured = control(
        &core,
        "chat-d",
        "mode-plan",
        NativeControl::Configure {
            provider: None,
            model: None,
            reasoning: None,
            mode: Some(roboco_proto::NativeMode::Plan),
        },
    )
    .await;
    assert!(
        matches!(configured, NativeControlOutcome::Configured { ref configuration } if configuration.mode == roboco_proto::NativeMode::Plan),
        "{configured:?}"
    );
    let markdown = "# bridge-plan\n\n## Requirements\nShip the bridge.\n\n## Delivery Plan\n\n### 1. Stage one\nOutcome: done.";
    provider.script(json!([{"text": format!("Here is the plan.\n\n<proposed_plan>\n{markdown}\n</proposed_plan>")}]), json!([]));
    send(&core, "chat-d", &cwd_d, "d-2", "plan it");
    wait_for("plan proposed", || {
        state(&core, "chat-d")
            .plan
            .is_some_and(|p| p.status == roboco_proto::NativePlanStatus::ReviewPending)
    })
    .await;
    let plan = core.sessions.native_plan("chat-d").await.unwrap().unwrap();
    assert_eq!(plan.markdown, markdown);
    let saved = control(
        &core,
        "chat-d",
        "plan-save",
        NativeControl::DecidePlan {
            plan_id: plan.id.clone(),
            decision: roboco_proto::NativePlanDecision::SaveAndStop,
        },
    )
    .await;
    assert_eq!(saved, NativeControlOutcome::Applied);
    wait_for("saved plan", || {
        state(&core, "chat-d")
            .plan
            .is_some_and(|p| p.status == roboco_proto::NativePlanStatus::SavedStopped)
    })
    .await;

    // A large reply arrives as an oversized saved entry, read in chunks.
    let big: String = "large reply line\n".repeat(400_000);
    let pieces: Vec<String> = big
        .as_bytes()
        .chunks(262_144)
        .map(|c| String::from_utf8(c.to_vec()).unwrap())
        .collect();
    provider.script(json!([{"text": pieces}]), json!([]));
    send(&core, "chat-b", &cwd_b, "b-3", "write a lot");
    wait_for("large reply", || settled(&state(&core, "chat-b"), "b-3")).await;
    let large = texts(&entries(&core, "chat-b"), MessageRole::Assistant);
    assert_eq!(
        large.iter().filter(|t| t.len() == big.len()).count(),
        1,
        "one complete copy of the large reply"
    );

    // A bridge crash interrupts work visibly; the restart reopens each chat
    // and resubmits nothing.
    provider.script(
        json!([{"text": ["crash "], "gate": "e1", "started": "e1-started"}]),
        json!([]),
    );
    send(&core, "chat-c", &cwd_c, "c-4", "doomed");
    assert_eq!(provider.send(json!({"wait": "e1-started"}))["ok"], true);
    let doomed_bridge = mimir.live_runtime().await.unwrap();
    let pid = doomed_bridge.pid.unwrap();
    let requests_before_crash = provider.send(json!({"requests": true}))["requests"]
        .as_u64()
        .unwrap();
    assert!(
        Command::new("kill")
            .args(["-9", "--", &format!("-{pid}")])
            .status()
            .unwrap()
            .success()
    );
    wait_for("the killed bridge is reaped", || !doomed_bridge.alive()).await;
    let deadline = tokio::time::Instant::now() + TIMEOUT;
    let restarted = loop {
        assert!(
            tokio::time::Instant::now() < deadline,
            "no replacement bridge reattached chat-c"
        );
        if state(&core, "chat-c").link == NativeLink::Attached
            && let Some(runtime) = mimir.live_runtime().await
            && runtime.generation > doomed_bridge.generation
        {
            break runtime;
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    };
    assert_ne!(restarted.pid, doomed_bridge.pid);
    wait_for("interrupted request reconciled", || {
        let c = state(&core, "chat-c");
        c.active_request.is_none()
            && c.submissions.iter().any(|s| {
                s.message_id == "c-4" && matches!(s.delivery, NativeDelivery::Admitted { .. })
            })
    })
    .await;
    provider.send(json!({"open": "e1"}));
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(
        provider.send(json!({"requests": true}))["requests"]
            .as_u64()
            .unwrap(),
        requests_before_crash,
        "the interrupted prompt is not resubmitted"
    );
    let interrupted = entries(&core, "chat-c")
        .iter()
        .flat_map(|e| e.parts.clone())
        .find_map(|p| match p {
            MessagePart::Notice {
                notice:
                    roboco_proto::NativeNotice::Interrupted {
                        message,
                        detail_ref,
                    },
                ..
            } => Some((message, detail_ref)),
            _ => None,
        })
        .expect("the interruption is recorded with its reason");
    assert!(!interrupted.0.is_empty());
    let provisional = core
        .doc_host
        .fetch_tool_blob(&interrupted.1.expect("the provisional output is kept"))
        .await
        .unwrap();
    assert!(provisional.contains("crash"), "{provisional}");
    assert!(
        !entries(&core, "chat-c")
            .iter()
            .any(|e| e.id.starts_with("aborted-")),
        "provisional output is not copied into the transcript"
    );
    assert_eq!(
        texts(&entries(&core, "chat-c"), MessageRole::User)
            .iter()
            .filter(|t| *t == "doomed")
            .count(),
        1
    );

    // An engine restart reopens the same conversation and duplicates nothing.
    let before = entries(&core, "chat-a").len();
    core.shutdown().await;
    drop(core);
    let (core, harness) = assemble(engine_dir.path(), &root);
    provider.script(json!([{"text": "after restart"}]), json!([]));
    send(&core, "chat-a", &cwd_a, "a-3", "still there?");
    wait_for("turn after restart", || {
        settled(&state(&core, "chat-a"), "a-3")
    })
    .await;
    let restored = entries(&core, "chat-a");
    assert_eq!(
        state(&core, "chat-a").conversation.unwrap().id,
        conversation.id
    );
    assert_eq!(
        restored.len(),
        before + 2,
        "exactly the new message and its reply"
    );
    assert_eq!(
        texts(&restored, MessageRole::User)
            .last()
            .map(String::as_str),
        Some("still there?")
    );
    core.shutdown().await;
    drop(harness);
}
