//! Explicit, opt-in smoke test against an installed bridge and existing login.
//! Set ROBOCO_MIMIR_AUTH_ROOT to a private test root with agent/auth.json and
//! the bridge installed there, then run this test with --ignored.
//! This test calls a real model. It never runs in the ordinary test suite.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use roboco_doc::{MessagePart, MessageRole, SessionCommandPayload, SessionCommandStatus};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::mimir::MimirHarness;
use roboco_proto::{
    HarnessId, NativeChatState, NativeControl, NativeLink, NativeMode, NativeReadiness,
    NativeRequestStatus, ReasoningLevel, RunRequest, SandboxLevel,
};
use serde_json::json;

const CHAT: &str = "authenticated-smoke";

fn state(core: &EngineCore) -> NativeChatState {
    core.doc_host
        .open(CHAT)
        .unwrap()
        .doc()
        .native_state()
        .unwrap_or_default()
}

async fn wait_for(mut ready: impl FnMut() -> bool) {
    tokio::time::timeout(Duration::from_secs(240), async {
        while !ready() {
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await
    .expect("real-model smoke reached its deadline");
}

async fn control(core: &EngineCore, control: NativeControl) {
    let id = core
        .doc_host
        .queue_command(CHAT, SessionCommandPayload::Native { control })
        .unwrap();
    wait_for(|| {
        core.doc_host
            .command(CHAT, &id)
            .unwrap()
            .is_some_and(|entry| entry.status != SessionCommandStatus::Pending)
    })
    .await;
    assert_eq!(
        core.doc_host.command(CHAT, &id).unwrap().unwrap().status,
        SessionCommandStatus::Applied
    );
}

async fn prompt(core: &EngineCore, request: RunRequest, message_id: &str, marker: &str) -> u128 {
    let started = Instant::now();
    let id = core
        .doc_host
        .queue_command(
            CHAT,
            SessionCommandPayload::Run {
                request,
                message_id: message_id.into(),
            },
        )
        .unwrap();
    wait_for(|| {
        core.doc_host
            .command(CHAT, &id)
            .unwrap()
            .is_some_and(|entry| entry.status != SessionCommandStatus::Pending)
    })
    .await;
    assert_eq!(
        core.doc_host.command(CHAT, &id).unwrap().unwrap().status,
        SessionCommandStatus::Applied
    );
    wait_for(|| {
        let state = state(core);
        state.active_request.is_none()
            && state.requests.iter().any(|request| {
                request.submission_key.as_deref() == Some(message_id)
                    && request.status == NativeRequestStatus::Completed
            })
    })
    .await;
    let entries = core
        .doc_host
        .open(CHAT)
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert!(
        entries
            .iter()
            .filter(|entry| entry.role == MessageRole::Assistant)
            .flat_map(|entry| &entry.parts)
            .any(|part| matches!(part, MessagePart::Text { text, .. } if text.contains(marker))),
        "the real model's response marker is absent"
    );
    assert_eq!(state(core).link, NativeLink::Attached);
    started.elapsed().as_millis()
}

#[tokio::test]
#[ignore = "requires explicit login isolation and calls a real model"]
async fn real_luna_reasoning_and_handoff_through_roboco() {
    let root = PathBuf::from(
        std::env::var_os("ROBOCO_MIMIR_AUTH_ROOT").expect("set the private isolated test root"),
    );
    let binary =
        PathBuf::from(std::env::var_os("ROBOCO_MIMIR_BIN").expect("set the built Mimir binary"));
    let run = tempfile::Builder::new()
        .prefix("run-")
        .tempdir_in(&root)
        .unwrap()
        .keep();
    let work = run.join("work");
    std::fs::create_dir_all(&work).unwrap();
    let registry = HarnessRegistry::new();
    let harness = MimirHarness::new()
        .with_executable(binary)
        .with_env(
            "MIMIR_CODING_AGENT_DIR",
            root.join("agent").display().to_string(),
        )
        .with_env("HOME", root.join("home").display().to_string())
        .with_env(
            "MIMIR_MODELS_PATH",
            root.join("absent-models.json").display().to_string(),
        )
        .with_env("MIMIR_DISABLE_MODELS_FETCH", "1")
        .with_env("MIMIR_HYPERLINKS", "0")
        .with_env("NO_COLOR", "1");
    registry.register(Arc::new(harness));
    let core =
        EngineCore::assemble(&run.join("engine"), Arc::new(registry), HarnessId::Mimir).unwrap();
    let started = Instant::now();
    assert!(
        matches!(
            core.sessions.native_readiness(true).await,
            NativeReadiness::Ready { .. }
        ),
        "isolated Mimir login or bridge is not ready"
    );
    let readiness_ms = started.elapsed().as_millis();
    let mut request = RunRequest {
        prompt: "Reply with exactly ROBOCO_LUNA_LOW_OK. Do not call tools.".into(),
        harness: Some(HarnessId::Mimir),
        model: Some("chatgpt/gpt-6-luna".into()),
        reasoning: Some(ReasoningLevel::Low),
        model_options: Default::default(),
        cwd: work.canonicalize().unwrap().display().to_string(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        attachments: Vec::new(),
        resume: None,
        worktree: None,
        mcp: None,
    };
    let first_ms = prompt(&core, request.clone(), "live-low", "ROBOCO_LUNA_LOW_OK").await;
    let initial = state(&core);
    let configuration = initial.configuration.unwrap();
    assert_eq!(configuration.provider.as_deref(), Some("chatgpt"));
    assert_eq!(configuration.model.as_deref(), Some("gpt-6-luna"));
    assert_eq!(configuration.reasoning.as_deref(), Some("low"));
    let conversation = initial.conversation.unwrap();
    let catalog = core.sessions.native_catalog(CHAT).await.unwrap();
    let model = catalog
        .providers
        .iter()
        .find(|provider| provider.id == "chatgpt")
        .unwrap()
        .models
        .iter()
        .find(|model| model.id == "gpt-6-luna")
        .unwrap();
    let mut runs = vec![json!({"reasoning": "low", "milliseconds": first_ms})];
    request.model = None;
    request.reasoning = None;
    for level in &model.reasoning {
        if level == "low" {
            continue;
        }
        control(
            &core,
            NativeControl::Configure {
                provider: None,
                model: None,
                reasoning: Some(level.clone()),
                mode: Some(NativeMode::Build),
            },
        )
        .await;
        assert_eq!(
            state(&core).configuration.unwrap().reasoning.as_deref(),
            Some(level.as_str())
        );
        let marker = format!("ROBOCO_LUNA_{}_OK", level.to_ascii_uppercase());
        request.prompt = format!("Reply with exactly {marker}. Do not call tools.");
        let elapsed = prompt(&core, request.clone(), &format!("live-{level}"), &marker).await;
        runs.push(json!({"reasoning": level, "milliseconds": elapsed}));
    }
    assert!(
        runs.len() >= 2,
        "the model catalog did not expose multiple reasoning levels"
    );
    control(
        &core,
        NativeControl::Configure {
            provider: None,
            model: None,
            reasoning: None,
            mode: Some(NativeMode::Plan),
        },
    )
    .await;
    assert_eq!(state(&core).configuration.unwrap().mode, NativeMode::Plan);
    control(
        &core,
        NativeControl::Configure {
            provider: None,
            model: None,
            reasoning: None,
            mode: Some(NativeMode::Build),
        },
    )
    .await;
    control(&core, NativeControl::Release).await;
    assert_eq!(state(&core).link, NativeLink::Released);
    control(&core, NativeControl::Reconnect).await;
    assert_eq!(state(&core).conversation.unwrap().id, conversation.id);
    assert_eq!(state(&core).link, NativeLink::Attached);
    control(&core, NativeControl::Release).await;
    let report = json!({"model": "chatgpt/gpt-6-luna", "readiness_ms": readiness_ms, "runs": runs, "mode_round_trip": true, "release_reconnect_same_conversation": true});
    std::fs::write(
        root.join("../authenticated-smoke-report.json"),
        serde_json::to_vec_pretty(&report).unwrap(),
    )
    .unwrap();
    println!(
        "Authenticated Roboco engine smoke passed {} reasoning levels; mode and same-conversation handoff passed.",
        report["runs"].as_array().unwrap().len()
    );
}
