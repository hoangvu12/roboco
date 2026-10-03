//! Mimir chats against a scripted bridge (`fixtures/mimir_fake_bridge.py`):
//! deterministic failure paths the real host cannot produce on demand.
#![cfg(unix)]

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use roboco_doc::{
    MessagePart, MessageRole, SessionCommandEntry, SessionCommandPayload, SessionCommandStatus,
};
use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry};
use roboco_harness::mimir::MimirHarness;
use roboco_proto::{
    HarnessId, NativeChatState, NativeControl, NativeControlOutcome, NativeDelivery,
    NativeErrorKind, NativeGoalChange, NativeLink, NativeReadiness, NativeSubmission,
    NativeSubmissionKind, RunRequest, SandboxLevel,
};
use serde_json::{Value, json};

struct Fake {
    dir: tempfile::TempDir,
    script: PathBuf,
}

impl Fake {
    fn new(scenario: Value) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let source =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mimir_fake_bridge.py");
        let script = dir.path().join("mimir");
        std::fs::copy(source, &script).unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        std::fs::write(dir.path().join("scenario.json"), scenario.to_string()).unwrap();
        Self { dir, script }
    }

    fn harness(&self) -> MimirHarness {
        MimirHarness::new()
            .with_executable(&self.script)
            .with_env("FAKE_BRIDGE_DIR", self.dir.path().display().to_string())
            .with_env(
                "MIMIR_CODING_AGENT_DIR",
                self.dir.path().join("agent").display().to_string(),
            )
    }

    fn calls(&self, method: &str) -> Vec<Value> {
        std::fs::read_to_string(self.dir.path().join("calls.jsonl"))
            .unwrap_or_default()
            .lines()
            .map(|line| serde_json::from_str::<Value>(line).unwrap())
            .filter(|call| call["method"] == method)
            .collect()
    }

    fn core(&self, engine: &Path) -> EngineCore {
        let registry = HarnessRegistry::new();
        registry.register(Arc::new(self.harness()));
        EngineCore::assemble(engine, Arc::new(registry), HarnessId::Mimir).unwrap()
    }
}

async fn wait_for(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
    while !condition() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
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

fn request(cwd: &str, prompt: &str, attachments: Vec<String>) -> RunRequest {
    RunRequest {
        prompt: prompt.into(),
        harness: Some(HarnessId::Mimir),
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: cwd.into(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        resume: None,
        attachments,
        worktree: None,
        mcp: None,
    }
}

fn send(core: &EngineCore, chat: &str, cwd: &str, message: &str, prompt: &str) {
    queue(
        core,
        chat,
        &format!("cmd-{message}"),
        SessionCommandPayload::Run {
            request: request(cwd, prompt, Vec::new()),
            message_id: message.into(),
        },
    );
}

fn steer(core: &EngineCore, chat: &str, message: &str, prompt: &str) {
    queue(
        core,
        chat,
        &format!("cmd-{message}"),
        SessionCommandPayload::Steer {
            prompt: prompt.into(),
            message_id: Some(message.into()),
        },
    );
}

fn command(core: &EngineCore, chat: &str, id: &str) -> Option<SessionCommandEntry> {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .read_commands()
        .unwrap()
        .into_iter()
        .find(|c| c.id == id)
}

/// Queue a control and wait for its recorded outcome.
async fn control(
    core: &EngineCore,
    chat: &str,
    id: &str,
    control: NativeControl,
) -> SessionCommandEntry {
    queue(core, chat, id, SessionCommandPayload::Native { control });
    settled(core, chat, id).await
}

async fn settled(core: &EngineCore, chat: &str, id: &str) -> SessionCommandEntry {
    wait_for(&format!("command {id} to settle"), || {
        command(core, chat, id).is_some_and(|c| c.status != SessionCommandStatus::Pending)
    })
    .await;
    command(core, chat, id).unwrap()
}

fn submission(state: &NativeChatState, message: &str) -> NativeSubmission {
    state
        .submissions
        .iter()
        .find(|s| s.message_id == message)
        .cloned()
        .unwrap_or_else(|| panic!("no submission for {message}: {:?}", state.submissions))
}

fn bridges(fake: &Fake) -> usize {
    fake.calls("initialize")
        .iter()
        .map(|c| c["pid"].clone())
        .collect::<std::collections::HashSet<_>>()
        .len()
}

/// What a hard kill leaves behind: a consistent copy of the engine's files
/// as they are on disk right now, with SQLite copied transactionally.
fn kill_copy(from: &Path, to: &Path) {
    for entry in walk(from) {
        let relative = entry.strip_prefix(from).unwrap();
        let target = to.join(relative);
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        let name = entry.file_name().unwrap().to_string_lossy().to_string();
        if name.ends_with("-wal") || name.ends_with("-shm") {
            continue;
        }
        if name.ends_with(".sqlite3") {
            rusqlite::Connection::open(&entry)
                .unwrap()
                .execute("VACUUM INTO ?1", [target.display().to_string()])
                .unwrap();
        } else {
            std::fs::copy(&entry, &target).unwrap();
        }
    }
}

fn walk(dir: &Path) -> Vec<PathBuf> {
    let mut files = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap().flatten() {
        let path = entry.path();
        if path.is_dir() {
            files.extend(walk(&path));
        } else {
            files.push(path);
        }
    }
    files
}

fn disk_doc(engine: &Path, chat: &str) -> Option<roboco_doc::SessionDoc> {
    let root = EngineProfile::development(engine, "dev-org", "dev-user")
        .store_root()
        .to_path_buf();
    let store = roboco_sync::DocsStore::open(root).unwrap();
    let bytes = store.load_snapshot(chat).unwrap()?;
    let doc = loro::LoroDoc::new();
    doc.import(&bytes).unwrap();
    Some(roboco_doc::SessionDoc::from_doc(doc))
}

fn state(core: &EngineCore, chat: &str) -> NativeChatState {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .native_state()
        .unwrap_or_default()
}

fn texts(core: &EngineCore, chat: &str, role: MessageRole) -> Vec<String> {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .read_entries()
        .unwrap()
        .iter()
        .filter(|e| e.role == role)
        .flat_map(|e| e.parts.clone())
        .filter_map(|p| match p {
            MessagePart::Text { text, .. } => Some(text),
            _ => None,
        })
        .collect()
}

fn delivery(state: &NativeChatState, message: &str) -> Option<NativeDelivery> {
    state
        .submissions
        .iter()
        .find(|s| s.message_id == message)
        .map(|s| s.delivery.clone())
}

#[tokio::test]
async fn a_missing_plugin_or_mismatched_contract_names_its_corrective_action() {
    let missing = Fake::new(json!({"missing_plugin": true}));
    let readiness = missing.harness().readiness(true).await;
    assert!(
        matches!(&readiness, NativeReadiness::PluginMissing { message, action }
            if message.contains("must be installed") && action.contains("mimir plugin install")),
        "{readiness:?}"
    );
    let engine = tempfile::tempdir().unwrap();
    let core = missing.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "hello");
    wait_for("refusal", || {
        matches!(
            delivery(&state(&core, "chat"), "m-1"),
            Some(NativeDelivery::Refused { .. })
        )
    })
    .await;
    assert!(matches!(
        state(&core, "chat").link,
        NativeLink::Unavailable { .. }
    ));
    assert!(
        state(&core, "chat").conversation.is_none(),
        "nothing was created"
    );
    let commands = core
        .doc_host
        .open("chat")
        .unwrap()
        .doc()
        .read_commands()
        .unwrap();
    assert_eq!(commands[0].status, SessionCommandStatus::Rejected);
    assert!(
        commands[0]
            .resolution
            .as_deref()
            .unwrap()
            .contains("mimir plugin install")
    );

    let mismatched = Fake::new(json!({"contracts": {
        "mimir:sessions/session-control": "9.0.0",
        "mimir:observations/session-observation": "7.0.0",
        "mimir:presentation/types": "3.0.0",
        "mimir:plugin-core/plugin-runtime": "3.0.0",
        "mimir:frontend/frontend": "1.0.0"
    }}));
    let readiness = mismatched.harness().readiness(true).await;
    assert!(
        matches!(&readiness, NativeReadiness::Incompatible { message, .. } if message.contains("session-control@9.0.0")),
        "{readiness:?}"
    );
}

#[tokio::test]
async fn mapped_native_prompts_never_reapply_stale_chat_configuration() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "initial", "first prompt");
    assert_eq!(
        settled(&core, "chat", "cmd-initial").await.status,
        SessionCommandStatus::Applied
    );
    wait_for("first response", || state(&core, "chat").active_request.is_none()).await;

    let mut stale = request(&cwd, "queued prompt", Vec::new());
    stale.model = Some("fake/stale-model".into());
    stale.reasoning = Some(roboco_proto::ReasoningLevel::High);
    queue(
        &core,
        "chat",
        "cmd-queued",
        SessionCommandPayload::Run {
            request: stale.clone(),
            message_id: "queued".into(),
        },
    );
    assert_eq!(
        settled(&core, "chat", "cmd-queued").await.status,
        SessionCommandStatus::Applied
    );
    wait_for("second response", || state(&core, "chat").active_request.is_none()).await;
    assert!(fake.calls("session.configure").is_empty());
    assert_eq!(fake.calls("session.prompt").len(), 2);

    let released = control(&core, "chat", "release-config", NativeControl::Release).await;
    assert_eq!(released.status, SessionCommandStatus::Applied);
    queue(
        &core,
        "chat",
        "cmd-reopened",
        SessionCommandPayload::Run {
            request: stale,
            message_id: "reopened".into(),
        },
    );
    assert_eq!(
        settled(&core, "chat", "cmd-reopened").await.status,
        SessionCommandStatus::Applied
    );
    assert!(fake.calls("session.configure").is_empty());
    assert_eq!(fake.calls("session.create").len(), 1);
    assert_eq!(fake.calls("session.prompt").len(), 3);
}
#[tokio::test]
async fn a_lost_admission_reply_reconciles_by_key_without_resending() {
    let fake = Fake::new(json!({"lose_prompt_reply": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "do the thing");
    wait_for("reconciled admission", || {
        matches!(
            delivery(&state(&core, "chat"), "m-1"),
            Some(NativeDelivery::Admitted { .. })
        ) && state(&core, "chat").link == NativeLink::Attached
    })
    .await;
    assert_eq!(
        fake.calls("session.prompt").len(),
        1,
        "the lost prompt is never sent again"
    );
    let pids: std::collections::HashSet<_> = fake
        .calls("initialize")
        .iter()
        .map(|c| c["pid"].clone())
        .collect();
    assert_eq!(pids.len(), 2, "a new bridge replaced the one that died");
    assert_eq!(
        texts(&core, "chat", MessageRole::User),
        vec!["do the thing"]
    );
}

#[tokio::test]
async fn a_branch_reset_replaces_the_stale_projection() {
    let fake = Fake::new(json!({"reset_after_prompt": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "first");
    wait_for("rewritten branch", || {
        texts(&core, "chat", MessageRole::Assistant)
            == vec!["rewritten on another branch".to_string()]
    })
    .await;
    assert_eq!(texts(&core, "chat", MessageRole::User), vec!["first"]);
    assert!(
        fake.calls("session.read_entries")
            .iter()
            .any(|c| c["params"]["anchor"]["after"].is_string())
    );
}

#[tokio::test]
async fn oversized_entries_and_fragmented_frames_arrive_exactly() {
    let fake =
        Fake::new(json!({"page_bytes": 2000, "fragment_bytes": 4096, "reply_bytes": 300000}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "write a lot");
    wait_for("large reply", || {
        texts(&core, "chat", MessageRole::Assistant)
            .iter()
            .any(|t| t.len() == 300_000)
    })
    .await;
    let replies = texts(&core, "chat", MessageRole::Assistant);
    assert_eq!(replies, vec!["x".repeat(300_000)]);
    assert!(
        fake.calls("session.read_entry_chunk").len() >= 3,
        "the oversized entry was read in chunks"
    );
}

#[tokio::test]
async fn the_bridge_holds_the_execution_lease_until_its_tree_is_reaped() {
    let fake = Fake::new(json!({}));
    let gate = Arc::new(tokio::sync::RwLock::new(()));
    let harness = fake.harness().with_execution_gate(gate.clone());
    let runtime = harness.runtime().await.unwrap();
    assert!(runtime.alive());
    assert!(
        gate.try_write().is_err(),
        "an update cannot replace a live bridge"
    );
    let pid = runtime.pid.unwrap();
    let exit = runtime.shutdown().await;
    assert!(exit.requested);
    assert!(
        gate.try_write().is_ok(),
        "the lease ends with the process tree"
    );
    let alive = std::process::Command::new("kill")
        .args(["-0", &pid.to_string()])
        .status()
        .unwrap()
        .success();
    assert!(!alive, "the bridge process was reaped");
    assert_eq!(
        fake.calls("bridge.shutdown").len(),
        1,
        "shutdown released attachments through the bridge first"
    );
}

#[tokio::test]
async fn a_lost_steer_reply_stays_unknown_and_is_never_redispatched() {
    let fake = Fake::new(json!({"hold_turns": true, "lose_steer_reply": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "work on it");
    wait_for("running turn", || {
        state(&core, "chat").active_request.is_some()
    })
    .await;
    steer(&core, "chat", "m-2", "also this");
    let steered = settled(&core, "chat", "cmd-m-2").await;
    // The lost bridge is replaced and the conversation reopened.
    wait_for("reattached", || {
        bridges(&fake) == 2 && state(&core, "chat").link == NativeLink::Attached
    })
    .await;
    assert_eq!(fake.calls("session.steer").len(), 1);
    assert_eq!(
        fake.calls("session.prompt").len(),
        1,
        "the steer must never be resent as a new prompt"
    );
    let state = state(&core, "chat");
    let steer = submission(&state, "m-2");
    assert_eq!(steer.kind, NativeSubmissionKind::Steer);
    assert!(
        matches!(steer.delivery, NativeDelivery::Unknown { .. }),
        "{steer:?}"
    );
    assert!(!steer.retryable, "steering carries no key to retry under");
    assert_eq!(steered.status, SessionCommandStatus::Unknown, "{steered:?}");
}

#[tokio::test]
async fn slash_commands_sent_mid_turn_route_to_the_host_command_path() {
    let fake = Fake::new(json!({"hold_turns": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "work on it");
    wait_for("running turn", || {
        state(&core, "chat").active_request.is_some()
    })
    .await;
    steer(&core, "chat", "m-2", "/goal pause");
    let handled = settled(&core, "chat", "cmd-m-2").await;
    steer(&core, "chat", "m-3", "/nope at all");
    let refused = settled(&core, "chat", "cmd-m-3").await;
    assert_eq!(
        fake.calls("session.steer").len(),
        0,
        "a slash command is never steered into the model"
    );
    let commands = fake.calls("session.command");
    let names: Vec<_> = commands
        .iter()
        .map(|c| c["params"]["name"].clone())
        .collect();
    assert_eq!(names, vec![json!("goal"), json!("nope")]);
    assert_eq!(commands[0]["params"]["tail"], "pause");
    let state = state(&core, "chat");
    assert_eq!(submission(&state, "m-2").delivery, NativeDelivery::Handled);
    assert_eq!(
        submission(&state, "m-2").kind,
        NativeSubmissionKind::Command
    );
    assert!(matches!(
        submission(&state, "m-3").delivery,
        NativeDelivery::Refused { .. }
    ));
    assert_eq!(handled.status, SessionCommandStatus::Applied);
    assert_eq!(refused.status, SessionCommandStatus::Rejected);
    assert!(
        state.active_request.is_some(),
        "the running turn was untouched"
    );
    assert_eq!(fake.calls("session.prompt").len(), 1);
}

#[tokio::test]
async fn a_lost_command_reply_stays_unknown_and_cannot_be_retried() {
    let fake = Fake::new(json!({"lose_command_reply": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "/plan ship it");
    let sent = settled(&core, "chat", "cmd-m-1").await;
    let state = state(&core, "chat");
    let command = submission(&state, "m-1");
    assert!(
        matches!(command.delivery, NativeDelivery::Unknown { .. }),
        "{command:?}"
    );
    assert_eq!(command.kind, NativeSubmissionKind::Command);
    assert!(
        !command.retryable,
        "a command has no key; resending could run it twice"
    );
    assert_eq!(sent.status, SessionCommandStatus::Unknown, "{sent:?}");
    let retry = control(
        &core,
        "chat",
        "retry-1",
        NativeControl::RetrySubmission {
            message_id: "m-1".into(),
        },
    )
    .await;
    assert!(
        matches!(
            retry.outcome,
            Some(NativeControlOutcome::Refused {
                kind: NativeErrorKind::Invalid,
                ..
            })
        ),
        "{retry:?}"
    );
    assert_eq!(fake.calls("session.command").len(), 1, "never re-run");
}

#[tokio::test]
async fn a_lost_control_reply_is_recorded_unknown() {
    let fake = Fake::new(json!({"lose_control_reply": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "hello");
    wait_for("reply", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["echo: hello".to_string()]
    })
    .await;
    let paused = control(
        &core,
        "chat",
        "goal-1",
        NativeControl::ChangeGoal {
            change: NativeGoalChange::Pause,
        },
    )
    .await;
    assert_eq!(paused.status, SessionCommandStatus::Unknown, "{paused:?}");
    assert!(
        matches!(paused.outcome, Some(NativeControlOutcome::Unknown { .. })),
        "{paused:?}"
    );
    assert_eq!(fake.calls("session.change_goal").len(), 1);
}

#[tokio::test]
async fn the_conversation_and_intent_are_durable_before_admission_and_a_kill_leaves_unknown() {
    let fake = Fake::new(json!({"stall_prompt": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "hello");
    wait_for("admission in flight", || {
        fake.calls("session.prompt").len() == 1
    })
    .await;
    // Kill here: only what is on disk survives.
    let killed = tempfile::tempdir().unwrap();
    kill_copy(engine.path(), killed.path());
    let disk = disk_doc(killed.path(), "chat").expect("the chat is on disk before admission");
    let native = disk.native_state().expect("native state is on disk");
    let session = fake.calls("session.create")[0].clone();
    assert!(
        native.conversation.is_some(),
        "the conversation mapping is durable before the prompt: {session:?}"
    );
    assert_eq!(
        submission(&native, "m-1").delivery,
        NativeDelivery::Submitting
    );
    let conversation = native.conversation.unwrap().id;

    // A new engine over the killed files reopens the same conversation and
    // reports the unmatched send as unknown, sending nothing.
    let restarted = fake.core(killed.path());
    wait_for("reconciled after the kill", || {
        state(&restarted, "chat").link == NativeLink::Attached
            && fake
                .calls("session.open")
                .iter()
                .any(|c| c["params"]["session"] == conversation.as_str())
    })
    .await;
    let state = state(&restarted, "chat");
    assert!(
        matches!(
            submission(&state, "m-1").delivery,
            NativeDelivery::Unknown { .. }
        ),
        "{state:?}"
    );
    assert!(submission(&state, "m-1").retryable);
    assert_eq!(
        fake.calls("session.create").len(),
        1,
        "no second conversation"
    );
    assert_eq!(fake.calls("session.prompt").len(), 1, "nothing was resent");
    wait_for("the interrupted command settles", || {
        command(&restarted, "chat", "cmd-m-1")
            .is_some_and(|c| c.status != SessionCommandStatus::Pending)
    })
    .await;
    assert_eq!(
        command(&restarted, "chat", "cmd-m-1").unwrap().status,
        SessionCommandStatus::Unknown
    );
    assert_eq!(texts(&restarted, "chat", MessageRole::User), vec!["hello"]);

    // The durable intent lets the user resend exactly what was sent, under
    // the same key.
    let retry = control(
        &restarted,
        "chat",
        "retry-1",
        NativeControl::RetrySubmission {
            message_id: "m-1".into(),
        },
    )
    .await;
    assert!(
        matches!(retry.outcome, Some(NativeControlOutcome::Admitted { .. })),
        "{retry:?}"
    );
    let prompts = fake.calls("session.prompt");
    assert_eq!(prompts.len(), 2);
    assert_eq!(prompts[0]["params"]["input"], prompts[1]["params"]["input"]);
    assert_eq!(prompts[1]["params"]["submission_key"], "m-1");
    wait_for("the reply", || {
        texts(&restarted, "chat", MessageRole::Assistant) == vec!["echo: hello".to_string()]
    })
    .await;
    assert_eq!(texts(&restarted, "chat", MessageRole::User), vec!["hello"]);
}

#[tokio::test]
async fn a_retry_resends_the_original_images_under_the_same_key() {
    let fake = Fake::new(json!({"refuse_prompt_once": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    let image = engine.path().join("pic.png");
    std::fs::write(&image, b"\x89PNG\r\n\x1a\nnot really").unwrap();
    queue(
        &core,
        "chat",
        "cmd-m-1",
        SessionCommandPayload::Run {
            request: request(&cwd, "what is this?", vec![image.display().to_string()]),
            message_id: "m-1".into(),
        },
    );
    wait_for("refusal", || {
        matches!(
            delivery(&state(&core, "chat"), "m-1"),
            Some(NativeDelivery::Refused { .. })
        )
    })
    .await;
    std::fs::remove_file(&image).unwrap();
    let retry = control(
        &core,
        "chat",
        "retry-1",
        NativeControl::RetrySubmission {
            message_id: "m-1".into(),
        },
    )
    .await;
    assert!(
        matches!(retry.outcome, Some(NativeControlOutcome::Admitted { .. })),
        "{retry:?}"
    );
    let prompts = fake.calls("session.prompt");
    assert_eq!(prompts.len(), 2);
    assert_eq!(prompts[0]["params"]["input"], prompts[1]["params"]["input"]);
    assert_eq!(
        prompts[1]["params"]["input"]["images"]
            .as_array()
            .map(Vec::len),
        Some(1)
    );
    assert_eq!(prompts[1]["params"]["submission_key"], "m-1");
}

#[tokio::test]
async fn a_failed_release_leaves_no_dead_view_and_reconnect_restores_delivery() {
    let fake = Fake::new(json!({"close_fails": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "first");
    wait_for("first reply", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["echo: first".to_string()]
            && !state(&core, "chat").working()
    })
    .await;
    let released = control(&core, "chat", "release-1", NativeControl::Release).await;
    assert!(
        matches!(released.outcome, Some(NativeControlOutcome::Refused { .. })),
        "{released:?}"
    );
    assert_ne!(
        state(&core, "chat").link,
        NativeLink::Attached,
        "its view is closed, so the chat is not attached"
    );
    let reconnected = control(&core, "chat", "reconnect-1", NativeControl::Reconnect).await;
    assert_eq!(reconnected.outcome, Some(NativeControlOutcome::Applied));
    assert_eq!(fake.calls("session.open_view").len(), 2, "a fresh view");
    assert_eq!(state(&core, "chat").link, NativeLink::Attached);
    send(&core, "chat", &cwd, "m-2", "second");
    wait_for("second reply through the new view", || {
        texts(&core, "chat", MessageRole::Assistant)
            == vec!["echo: first".to_string(), "echo: second".to_string()]
    })
    .await;
}

fn entries(core: &EngineCore, chat: &str) -> Vec<roboco_doc::SessionMessageEntry> {
    core.doc_host
        .open(chat)
        .unwrap()
        .doc()
        .read_entries()
        .unwrap()
}

struct ToolPart {
    view: roboco_proto::NativeToolView,
    resolved: bool,
    /// The part's output summary.
    output: Option<String>,
    output_ref: Option<String>,
}

fn tools(core: &EngineCore, chat: &str) -> Vec<ToolPart> {
    entries(core, chat)
        .iter()
        .flat_map(|e| e.parts.clone())
        .filter_map(|p| match p {
            MessagePart::Tool {
                call: roboco_proto::ToolCall::Native { view },
                resolved,
                output,
                output_ref,
                ..
            } => Some(ToolPart {
                view: *view,
                resolved,
                output,
                output_ref,
            }),
            _ => None,
        })
        .collect()
}

async fn detail(
    core: &EngineCore,
    view: &roboco_proto::NativeToolView,
) -> roboco_proto::NativeToolDetail {
    let text = core
        .doc_host
        .fetch_tool_blob(view.detail_ref.as_deref().unwrap())
        .await
        .unwrap();
    serde_json::from_str(&text).unwrap()
}

/// The first `deltas` 64 KiB output deltas of the scripted streaming tool.
fn stream_bytes(deltas: usize) -> Vec<u8> {
    (0..160_000)
        .map(|i| format!("line {i:06} ünïcödé ☃\n"))
        .collect::<String>()
        .into_bytes()
        .into_iter()
        .take(deltas * 65536)
        .collect()
}

/// A tool detail's complete stream and progress, read back through their series.
async fn assert_streamed(
    core: &EngineCore,
    detail: &roboco_proto::NativeToolDetail,
    deltas: usize,
    steps: usize,
) {
    let expected = stream_bytes(deltas);
    let stream = detail.stream.clone().expect("the streamed output is kept");
    assert_eq!(
        (stream.bytes, stream.records),
        (expected.len() as u64, expected.len().div_ceil(65536) as u64),
        "{stream:?}"
    );
    assert!(
        series(core, &stream).await == expected,
        "every byte, in order"
    );
    let progress = detail.progress.clone().expect("progress is kept");
    let lines: Vec<String> = String::from_utf8(series(core, &progress).await)
        .unwrap()
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    let wanted: Vec<String> = (1..=steps).map(|i| format!("step {i}")).collect();
    assert_eq!(lines, wanted);
}

/// Every byte of a blob series, read through bounded windows.
async fn series(core: &EngineCore, series: &roboco_proto::NativeBlobSeries) -> Vec<u8> {
    let mut out = Vec::new();
    for index in 0..series.chunks {
        let blob = format!("{}.{index:06}", series.blob_ref);
        let mut offset = 0;
        loop {
            let window = core
                .doc_host
                .fetch_tool_blob_range(&blob, offset, 100_000)
                .await
                .unwrap();
            assert_eq!(
                window.encoding,
                roboco_engine::doc_host::ToolBlobEncoding::Utf8,
                "UTF-8 output stays UTF-8 in every chunk"
            );
            out.extend_from_slice(window.text.as_bytes());
            match window.next_offset {
                Some(next) => offset = next as usize,
                None => break,
            }
        }
    }
    out
}

#[tokio::test]
async fn returning_to_a_branch_restores_the_echo_a_rewind_took_away() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "u-1", "first");
    wait_for("reply", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["echo: first".to_string()]
    })
    .await;
    send(&core, "chat", &cwd, "rw-1", "/rewind 0");
    wait_for("rewound", || {
        texts(&core, "chat", MessageRole::Assistant).is_empty()
            && !entries(&core, "chat").iter().any(|e| e.id == "u-1")
    })
    .await;
    send(&core, "chat", &cwd, "rs-1", "/restore");
    wait_for("restored", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["echo: first".to_string()]
    })
    .await;
    let restored = entries(&core, "chat");
    let firsts: Vec<_> = restored
        .iter()
        .filter(|e| {
            e.parts
                .iter()
                .any(|p| matches!(p, MessagePart::Text { text, .. } if text == "first"))
        })
        .collect();
    assert_eq!(firsts.len(), 1, "{restored:#?}");
    assert_eq!(firsts[0].id, "u-1", "the user's own message id comes back");
    assert_eq!(firsts[0].role, MessageRole::User);
    let user = restored.iter().position(|e| e.id == "u-1").unwrap();
    let reply = restored
        .iter()
        .position(|e| e.role == MessageRole::Assistant)
        .unwrap();
    assert!(user < reply, "the message precedes its reply");
}

#[tokio::test]
async fn an_exact_shorter_cut_drops_results_the_branch_no_longer_has() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "t-1", "tool plain");
    wait_for("tool turn", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["done".to_string()]
            && tools(&core, "chat").first().is_some_and(|t| t.resolved)
    })
    .await;
    // Saved: user, tool call, tool result, reply. Cut back to the call.
    send(&core, "chat", &cwd, "rw-1", "/rewind 2");
    wait_for("rewound", || {
        texts(&core, "chat", MessageRole::Assistant).is_empty()
    })
    .await;
    let tools = tools(&core, "chat");
    assert_eq!(tools.len(), 1);
    let tool = &tools[0];
    assert!(!tool.resolved, "the result is not on this branch");
    assert_eq!((&tool.output, &tool.output_ref), (&None, &None));
    let detail = detail(&core, &tool.view).await;
    assert_eq!(detail.output, None, "{detail:?}");
    assert!(detail.display_content.is_empty());
    assert_eq!(detail.input, Some(json!({"path": "note.txt"})));
}

#[tokio::test]
async fn a_public_display_list_is_authoritative_and_live_output_survives_the_checkpoint() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    for (n, variant) in ["resource", "image", "live"].iter().enumerate() {
        send(
            &core,
            "chat",
            &cwd,
            &format!("t-{n}"),
            &format!("tool {variant}"),
        );
        wait_for(&format!("{variant} turn"), || {
            tools(&core, "chat").len() == n + 1
                && tools(&core, "chat").iter().all(|t| t.resolved)
                && texts(&core, "chat", MessageRole::Assistant).len() == n + 1
        })
        .await;
    }
    let tools = tools(&core, "chat");
    let mut outputs = Vec::new();
    for tool in &tools {
        let output = core
            .doc_host
            .fetch_tool_blob(tool.output_ref.as_deref().unwrap())
            .await
            .unwrap();
        let detail = detail(&core, &tool.view).await;
        assert_eq!(detail.output.as_deref(), Some(output.as_str()));
        let stored = serde_json::to_string(&detail).unwrap();
        assert!(!stored.contains("MODEL_ONLY"), "{stored}");
        outputs.push((output, detail));
    }
    assert_eq!(outputs[0].0, "PUBLIC RESOURCE");
    assert_eq!(tools[0].output.as_deref(), Some("PUBLIC RESOURCE"));
    assert_eq!(
        outputs[1].0, "",
        "an image-only display has no text to show"
    );
    assert!(outputs[1].1.display_content[0].get("image").is_some());
    assert_eq!(
        outputs[2].0,
        "FULL LIVE OUTPUT ".repeat(20000),
        "the live public output, not the saved model-facing text"
    );
    let doc = serde_json::to_string(&entries(&core, "chat")).unwrap();
    assert!(!doc.contains("MODEL_ONLY"));
}

#[tokio::test]
async fn output_saved_before_a_lost_checkpoint_is_not_duplicated() {
    let fake = Fake::new(json!({"crash_after_save": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "u-1", "hi");
    wait_for("reconnected with the saved reply", || {
        bridges(&fake) == 2
            && state(&core, "chat").link == NativeLink::Attached
            && !texts(&core, "chat", MessageRole::Assistant).is_empty()
    })
    .await;
    let entries = entries(&core, "chat");
    assert_eq!(
        texts(&core, "chat", MessageRole::Assistant),
        vec!["Hello".to_string()],
        "{entries:#?}"
    );
    assert!(!entries.iter().any(|e| e.id.starts_with("aborted-")));
    let interrupted = entries
        .iter()
        .flat_map(|e| e.parts.iter())
        .find_map(|p| match p {
            MessagePart::Notice {
                notice: roboco_proto::NativeNotice::Interrupted { detail_ref, .. },
                ..
            } => Some(detail_ref.clone()),
            _ => None,
        })
        .expect("the interruption is recorded as its own notice");
    let provisional = core
        .doc_host
        .fetch_tool_blob(&interrupted.unwrap())
        .await
        .unwrap();
    let parts: Vec<MessagePart> = serde_json::from_str(&provisional).unwrap();
    assert!(matches!(&parts[0], MessagePart::Text { text, .. } if text == "Hello"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_tool_running_across_a_restart_keeps_its_complete_stream_and_progress() {
    let fake = Fake::new(json!({"hold_tool": true, "stream_deltas": 72, "progress_lines": 250}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "s-1", "tool stream");
    wait_for("running tool", || {
        tools(&core, "chat").len() == 1 && state(&core, "chat").active_request.is_some()
    })
    .await;
    let killed = tempfile::tempdir().unwrap();
    kill_copy(engine.path(), killed.path());
    let restarted = fake.core(killed.path());
    wait_for("the tool finishes after the restart", || {
        tools(&restarted, "chat")
            .first()
            .is_some_and(|t| t.resolved)
            && texts(&restarted, "chat", MessageRole::Assistant) == vec!["done".to_string()]
    })
    .await;
    let order: Vec<String> = entries(&restarted, "chat")
        .iter()
        .map(|e| e.id.clone())
        .collect();
    assert_eq!(
        order.first().map(String::as_str),
        Some("s-1"),
        "the message stays ahead of its own turn after a restart: {order:?}"
    );
    let view = tools(&restarted, "chat").remove(0).view;
    let detail = detail(&restarted, &view).await;
    assert_eq!(detail.input, Some(json!({"path": "note.txt"})));
    assert_eq!(detail.output.as_deref(), Some("streamed done"));
    assert!(
        detail.stream.as_ref().is_some_and(|s| s.chunks > 1),
        "{detail:?}"
    );
    assert_streamed(&restarted, &detail, 72, 250).await;
}

const CHILD_DOC: &str = "chat--sub--agent-t";

async fn assert_child_tool_streamed(core: &EngineCore) {
    wait_for("the child tool and the parent turn finish", || {
        tools(core, CHILD_DOC).first().is_some_and(|t| t.resolved)
            && texts(core, "chat", MessageRole::Assistant) == vec!["done".to_string()]
    })
    .await;
    let child_tools = tools(core, CHILD_DOC);
    assert_eq!(child_tools.len(), 1);
    let detail = detail(core, &child_tools[0].view).await;
    assert_eq!(detail.output.as_deref(), Some("streamed done"));
    assert_streamed(core, &detail, 6, 40).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_child_announced_mid_tool_keeps_its_complete_stream_and_progress() {
    let fake = Fake::new(json!({"stream_deltas": 6, "progress_lines": 40}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "c-1", "child tool");
    assert_child_tool_streamed(&core).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_child_tool_running_across_a_restart_keeps_its_complete_stream_and_progress() {
    let fake = Fake::new(json!({"hold_tool": true, "stream_deltas": 6, "progress_lines": 40}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "c-1", "child tool");
    wait_for("running child tool", || {
        tools(&core, CHILD_DOC).len() == 1 && state(&core, "chat").active_request.is_some()
    })
    .await;
    let killed = tempfile::tempdir().unwrap();
    kill_copy(engine.path(), killed.path());
    let restarted = fake.core(killed.path());
    assert_child_tool_streamed(&restarted).await;
}

#[tokio::test]
async fn an_attempts_outcome_is_kept_when_the_child_continued_before_it_was_read() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "c-1", "child twice");
    wait_for("the continued attempt settles", || {
        let state = state(&core, "chat");
        !state.working()
            && state
                .children
                .first()
                .is_some_and(|c| c.attempts.len() == 2 && c.attempts[1].outcome_ref.is_some())
    })
    .await;
    let attempts = state(&core, "chat").children[0].attempts.clone();
    assert_eq!(
        attempts
            .iter()
            .map(|a| (a.attempt, a.outcome_ref.is_some()))
            .collect::<Vec<_>>(),
        vec![(1, true), (2, true)],
        "{attempts:?}"
    );
    let mut results = Vec::new();
    for attempt in &attempts {
        let blob = core
            .doc_host
            .fetch_tool_blob(attempt.outcome_ref.as_deref().unwrap())
            .await
            .unwrap();
        let outcome: roboco_proto::NativeChildOutcome = serde_json::from_str(&blob).unwrap();
        results.push((outcome.attempt, outcome.result));
    }
    assert_eq!(
        results,
        vec![
            (1, Some("first result".to_string())),
            (2, Some("second result".to_string()))
        ]
    );
}

#[tokio::test]
async fn a_refused_mid_turn_command_retries_its_original_text_after_the_turn() {
    let fake = Fake::new(json!({"hold_turns": true, "refuse_command_while_running": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "work on it");
    wait_for("running turn", || {
        state(&core, "chat").active_request.is_some()
    })
    .await;
    steer(&core, "chat", "m-2", "/plan ship it  now");
    let refused = settled(&core, "chat", "cmd-m-2").await;
    assert_eq!(
        refused.status,
        SessionCommandStatus::Rejected,
        "{refused:?}"
    );
    let command = submission(&state(&core, "chat"), "m-2");
    assert_eq!(command.kind, NativeSubmissionKind::Command);
    assert!(
        matches!(command.delivery, NativeDelivery::Refused { .. }) && command.retryable,
        "{command:?}"
    );

    let active = state(&core, "chat").active_request.unwrap();
    let cancelled = control(
        &core,
        "chat",
        "cancel-1",
        NativeControl::CancelRequest { request_id: active },
    )
    .await;
    assert_eq!(
        cancelled.outcome,
        Some(NativeControlOutcome::Cancelled { newly: true })
    );
    wait_for("the turn ended", || !state(&core, "chat").working()).await;
    let retry = control(
        &core,
        "chat",
        "retry-1",
        NativeControl::RetrySubmission {
            message_id: "m-2".into(),
        },
    )
    .await;
    assert!(
        matches!(retry.outcome, Some(NativeControlOutcome::Admitted { .. })),
        "{retry:?}"
    );
    let sent: Vec<_> = fake
        .calls("session.command")
        .iter()
        .map(|c| (c["params"]["name"].clone(), c["params"]["tail"].clone()))
        .collect();
    assert_eq!(
        sent,
        vec![
            (json!("plan"), json!("ship it  now")),
            (json!("plan"), json!("ship it  now"))
        ]
    );
    assert!(matches!(
        submission(&state(&core, "chat"), "m-2").delivery,
        NativeDelivery::Admitted { .. }
    ));
}

#[tokio::test]
async fn a_message_whose_intent_was_never_saved_is_not_offered_for_retry() {
    let fake = Fake::new(json!({}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    let missing = engine.path().join("gone.png").display().to_string();
    queue(
        &core,
        "chat",
        "cmd-m-1",
        SessionCommandPayload::Run {
            request: request(&cwd, "what is this?", vec![missing]),
            message_id: "m-1".into(),
        },
    );
    wait_for("refusal", || {
        matches!(
            delivery(&state(&core, "chat"), "m-1"),
            Some(NativeDelivery::Refused { .. })
        )
    })
    .await;
    let refused = submission(&state(&core, "chat"), "m-1");
    assert!(!refused.retryable, "{refused:?}");
    let retry = control(
        &core,
        "chat",
        "retry-1",
        NativeControl::RetrySubmission {
            message_id: "m-1".into(),
        },
    )
    .await;
    assert!(
        matches!(
            retry.outcome,
            Some(NativeControlOutcome::Refused {
                kind: NativeErrorKind::Invalid,
                ..
            })
        ),
        "{retry:?}"
    );
    assert!(fake.calls("session.prompt").is_empty(), "nothing was sent");
}

fn answer(core: &EngineCore, id: &str, label: &str) {
    queue(
        core,
        "chat",
        id,
        SessionCommandPayload::RespondInput {
            request_id: "q-1".into(),
            answers: vec![roboco_proto::UserInputAnswer {
                question_id: "color".into(),
                labels: vec![label.into()],
            }],
        },
    );
}

#[tokio::test]
async fn an_answer_whose_reply_was_lost_stays_unknown_and_a_stale_one_is_refused() {
    let fake = Fake::new(json!({"lose_answer_reply": true}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "m-1", "ask");
    wait_for("the question", || {
        state(&core, "chat")
            .user_request
            .is_some_and(|r| r.id == "q-1")
    })
    .await;
    answer(&core, "answer-1", "blue");
    let answered = settled(&core, "chat", "answer-1").await;
    assert_eq!(
        answered.status,
        SessionCommandStatus::Unknown,
        "{answered:?}"
    );
    wait_for("the answered turn after reattaching", || {
        bridges(&fake) == 2
            && state(&core, "chat").link == NativeLink::Attached
            && texts(&core, "chat", MessageRole::Assistant) == vec!["answered: blue".to_string()]
    })
    .await;
    assert_eq!(
        fake.calls("session.answer").len(),
        1,
        "never answered twice"
    );

    answer(&core, "answer-2", "red");
    let stale = settled(&core, "chat", "answer-2").await;
    assert_eq!(stale.status, SessionCommandStatus::Rejected, "{stale:?}");
    assert!(
        stale
            .resolution
            .as_deref()
            .is_some_and(|r| r.contains("no pending request")),
        "{stale:?}"
    );
}

#[tokio::test]
async fn returning_to_a_completed_cut_restores_its_stream_progress_and_output() {
    let fake = Fake::new(json!({"stream_deltas": 3, "progress_lines": 5}));
    let engine = tempfile::tempdir().unwrap();
    let core = fake.core(engine.path());
    let cwd = engine.path().display().to_string();
    send(&core, "chat", &cwd, "t-1", "tool stream");
    wait_for("tool turn", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["done".to_string()]
            && tools(&core, "chat").first().is_some_and(|t| t.resolved)
    })
    .await;
    let finished = detail(&core, &tools(&core, "chat")[0].view).await;
    assert_streamed(&core, &finished, 3, 5).await;

    // Saved: user, tool call, tool result, reply. Cut back to the call.
    send(&core, "chat", &cwd, "rw-1", "/rewind 2");
    wait_for("rewound", || {
        texts(&core, "chat", MessageRole::Assistant).is_empty()
    })
    .await;
    let short = detail(&core, &tools(&core, "chat")[0].view).await;
    assert_eq!(
        (&short.output, &short.stream, &short.progress),
        (&None, &None, &None),
        "the short cut never shows its future result: {short:?}"
    );

    send(&core, "chat", &cwd, "rs-1", "/restore");
    wait_for("restored", || {
        texts(&core, "chat", MessageRole::Assistant) == vec!["done".to_string()]
            && tools(&core, "chat").first().is_some_and(|t| t.resolved)
    })
    .await;
    let tool = tools(&core, "chat").remove(0);
    let restored = detail(&core, &tool.view).await;
    assert_eq!(
        (&restored.stream, &restored.progress),
        (&finished.stream, &finished.progress)
    );
    assert_eq!(restored.output.as_deref(), Some("streamed done"));
    assert_streamed(&core, &restored, 3, 5).await;
    let output = core
        .doc_host
        .fetch_tool_blob(tool.output_ref.as_deref().unwrap())
        .await
        .unwrap();
    assert_eq!(output, "streamed done");
}
