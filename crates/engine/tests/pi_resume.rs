//! Pi's native RPC driver keeps durable resume parity: the session file
//! pointer survives an idle process crash through engine dispatch — the
//! next run spawns `pi --mode rpc --session <file>` (ticket 21's parity
//! expectation with the old ACP-path pi_resume test).
use std::{sync::Arc, time::Duration};
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_harness::PiHarness;
use roboco_proto::{HarnessId, RunRequest, SandboxLevel};

#[tokio::test]
async fn pi_idle_crash_next_dispatch_resumes_the_stored_session() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../harness/tests/fixtures/fake-pi-rpc.sh");
    let registry = HarnessRegistry::new();
    registry.register(Arc::new(
        PiHarness::new()
            .with_executable(fixture)
            .with_graces(Duration::from_millis(50), Duration::from_millis(100)),
    ));
    let core = EngineCore::assemble(dir.path(), Arc::new(registry), HarnessId::Pi).unwrap();
    let chat = "pi-idle-crash";
    let handle = core.doc_host.open(chat).unwrap();
    let doc = handle.doc();
    for prompt in ["idle-crash", "require-resume"] {
        let req = RunRequest {
            prompt: prompt.into(),
            harness: None,
            model: None,
            reasoning: None,
            model_options: Default::default(),
            cwd: dir.path().display().to_string(),
            sandbox: SandboxLevel::WorkspaceWrite,
            auto_approve: true,
            attachments: Vec::new(),
            worktree: None,
            mcp: None,
            resume: None,
        };
        core.sessions
            .dispatch(chat, HarnessId::Pi, req, None)
            .await
            .unwrap();
        tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let entries = doc.read_entries().unwrap();
                if entries.iter().any(|entry| entry.parts.iter().any(|part|
                    matches!(part, roboco_doc::MessagePart::Text { text, .. } if text == &format!("reply:{prompt}"))
                )) { break; }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }).await.expect("fixture requires --session on the next dispatch");
        // Let the fixture exit and the driver remove its live mailbox.
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}
