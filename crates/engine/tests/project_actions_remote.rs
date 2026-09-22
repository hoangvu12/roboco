//! Remote-execution and version-skew hardening for project Actions over the
//! engine-local pairing surface (port of upstream device_routing.rs coverage,
//! re-homed per the ticket-24 decision: direct paired connections, no relay).

use std::sync::Arc;
use std::time::Duration;

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::{connect_ws_authenticated, memory_client, methods};
use serde_json::{Value, json};

async fn git(cwd: &std::path::Path, args: &[&str]) {
    let output = tokio::process::Command::new("git")
        .args(args)
        .current_dir(cwd)
        .env("GIT_AUTHOR_NAME", "test")
        .env("GIT_AUTHOR_EMAIL", "test@test")
        .env("GIT_COMMITTER_NAME", "test")
        .env("GIT_COMMITTER_EMAIL", "test@test")
        .output()
        .await
        .expect("git spawns");
    assert!(
        output.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
}

fn assemble(dir: &std::path::Path) -> EngineCore {
    EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap()
}

/// The roboco counterpart of upstream's offline-forward assertions: there is
/// no relay to answer for another device, so every action method (and the
/// terminal stream entry point) fails closed at the engine's entry check.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn wrong_target_connection_fails_closed_for_every_action_method() {
    let dirs = tempfile::tempdir().unwrap();
    let core = assemble(&dirs.path().join("solo"));
    let client = memory_client(core.rpc_service());
    for (method, params) in [
        (
            methods::LIST_PROJECT_ACTIONS,
            json!({
                "spaceId": "remote-space",
                "targetDeviceId": "device-elsewhere",
            }),
        ),
        (
            methods::UPSERT_PROJECT_ACTION,
            json!({
                "spaceId": "remote-space",
                "action": { "name": "Build", "command": "make", "icon": "build" },
                "targetDeviceId": "device-elsewhere",
            }),
        ),
        (
            methods::DELETE_PROJECT_ACTION,
            json!({
                "spaceId": "remote-space",
                "actionId": "build",
                "targetDeviceId": "device-elsewhere",
            }),
        ),
        (
            methods::RUN_PROJECT_ACTION,
            json!({
                "spaceId": "remote-space",
                "chatId": "remote-chat",
                "actionId": "build",
                "cols": 80,
                "rows": 24,
                "targetDeviceId": "device-elsewhere",
            }),
        ),
    ] {
        let err = client
            .call(method, params)
            .await
            .expect_err("wrong-target Action request must fail closed");
        assert!(
            err.to_string().contains("is not connected"),
            "{method}: {err}"
        );
    }
    // Upstream proves an offline relay subscribe closes promptly; roboco has
    // no relay, so the wrong-target terminal subscription is accepted for
    // streaming and then closed by the engine's fail-closed entry check.
    let mut offline_stream = client
        .subscribe(
            methods::SUBSCRIBE_TERMINAL,
            json!({
                "terminalId": "remote-terminal",
                "targetDeviceId": "device-elsewhere",
            }),
        )
        .await
        .expect("stream request is accepted before the engine reports the routing failure");
    assert!(
        tokio::time::timeout(Duration::from_secs(1), offline_stream.recv())
            .await
            .expect("offline stream closes promptly")
            .is_none(),
        "offline subscribe must not produce local terminal output"
    );
    core.shutdown().await;
}

/// Two engines, one direct paired connection: Actions manage and execute only
/// on the engine owning the space, env vars are canonical, pre-subscribe
/// output is recovered by replay, and the setup worktree outcome carries an
/// already-open terminal.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paired_actions_run_on_the_owning_engine() {
    let dirs = tempfile::tempdir().unwrap();
    let core_a = assemble(&dirs.path().join("a"));
    let b_dir = dirs.path().join("b");
    std::fs::create_dir_all(&b_dir).unwrap();
    let core_b = assemble(&b_dir);

    // Project Actions are private host-local config: a versioned project file
    // only contributes import offers.
    let project_root = b_dir.join("project-on-b");
    std::fs::create_dir_all(&project_root).unwrap();
    git(&project_root, &["init", "-b", "main"]).await;
    std::fs::write(project_root.join("README.md"), "host B\n").unwrap();
    std::fs::write(
        project_root.join("roboco.json"),
        r#"{"actions":[{"name":"Lint","command":"pnpm lint","icon":"lint"}]}"#,
    )
    .unwrap();
    git(&project_root, &["add", "."]).await;
    git(&project_root, &["commit", "-m", "seed"]).await;
    core_b
        .workspace
        .create_space(
            "space-actions",
            &core_b.device_id,
            &project_root.to_string_lossy(),
            None,
            true,
        )
        .unwrap();

    // The client pairs directly with B (no relay in between).
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core_b.rpc_service(),
        &b_dir,
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    let url = format!("ws://{}", remote.address);
    let store = PairingStore::open(&b_dir).unwrap();
    let code = store.create_code("actions test", 300).unwrap();
    let http = reqwest::Client::new();
    let grant: Value = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&code.credential)
        .json(&json!({"label":"A"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let client = connect_ws_authenticated(&url, grant["credential"].as_str().unwrap())
        .await
        .unwrap();

    // A request naming the wrong engine over B's own connection fails closed.
    assert!(
        client
            .call(
                methods::LIST_PROJECT_ACTIONS,
                json!({
                    "spaceId": "space-actions",
                    "targetDeviceId": core_a.device_id,
                }),
            )
            .await
            .expect_err("wrong engine over B's connection")
            .to_string()
            .contains("is not connected")
    );

    let listed = client
        .call(
            methods::LIST_PROJECT_ACTIONS,
            json!({
                "spaceId": "space-actions",
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();
    assert_eq!(listed["actions"].as_array().unwrap().len(), 0);
    assert_eq!(listed["importableActions"].as_array().unwrap().len(), 1);

    // One action serves the manual run and the worktree setup: the setup
    // marker is only written when the worktree env var is present.
    let action_command = if cfg!(windows) {
        concat!(
            "echo remote-action> action-marker&& ",
            "echo remote-action&& ",
            "if not \"%ROBOCO_WORKTREE_PATH%\"==\"\" ",
            "(echo ROOT=%ROBOCO_PROJECT_ROOT%> setup-marker&& ",
            "echo WT=%ROBOCO_WORKTREE_PATH%>> setup-marker&& ",
            "echo CWD=%CD%>> setup-marker)"
        )
        .to_string()
    } else {
        concat!(
            "printf 'remote-action\\n' > action-marker; ",
            "if [ -n \"$ROBOCO_WORKTREE_PATH\" ]; then ",
            "printf 'ROOT=%s\\nWT=%s\\nCWD=%s\\n' ",
            "\"$ROBOCO_PROJECT_ROOT\" \"$ROBOCO_WORKTREE_PATH\" \"$PWD\" > setup-marker; ",
            "fi; printf 'remote-action\\n'"
        )
        .to_string()
    };
    let saved = client
        .call(
            methods::UPSERT_PROJECT_ACTION,
            json!({
                "spaceId": "space-actions",
                "targetDeviceId": core_b.device_id,
                "action": {
                    "name": "Lint",
                    "command": action_command,
                    "icon": "lint",
                    "runOnWorktreeCreate": true,
                },
            }),
        )
        .await
        .unwrap();
    let action_id = saved["actions"][0]["id"].as_str().unwrap().to_string();
    assert!(saved["importableActions"].as_array().unwrap().is_empty());
    assert_eq!(
        core_b
            .project_actions
            .actions("space-actions", &project_root)
            .unwrap()
            .len(),
        1
    );
    assert!(
        core_a
            .project_actions
            .actions("space-actions", &project_root)
            .unwrap()
            .is_empty(),
        "the paired engine must not persist the command on A"
    );

    core_b
        .workspace
        .create_chat("chat-actions", Some("space-actions"), None, None, None)
        .unwrap();
    let run = client
        .call(
            methods::RUN_PROJECT_ACTION,
            json!({
                "spaceId": "space-actions",
                "chatId": "chat-actions",
                "actionId": action_id,
                "cols": 80,
                "rows": 24,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .expect("run remote Action");
    let action_terminal = run["terminal"]["id"].as_str().unwrap().to_string();
    let mut action_stream = client
        .subscribe(
            methods::SUBSCRIBE_TERMINAL,
            json!({
                "terminalId": action_terminal,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    loop {
        let item = tokio::time::timeout_at(deadline, action_stream.recv())
            .await
            .expect("remote Action output before timeout")
            .expect("Action stream alive");
        if item["type"] == "data" {
            let output = BASE64
                .decode(item["data"].as_str().expect("Action data"))
                .expect("Action base64");
            if String::from_utf8_lossy(&output).contains("remote-action")
                && project_root.join("action-marker").exists()
            {
                break;
            }
        }
    }
    assert!(
        std::fs::read_to_string(project_root.join("action-marker"))
            .unwrap()
            .contains("remote-action"),
        "marker on B"
    );
    assert!(
        !dirs.path().join("a").join("action-marker").exists()
            && !dirs
                .path()
                .join("a")
                .join("project-on-b")
                .join("action-marker")
                .exists(),
        "remote execution must not fall back to A's filesystem"
    );
    client
        .call(
            methods::CLOSE_TERMINAL,
            json!({
                "terminalId": action_terminal,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();

    // New worktree setup runs entirely on B and returns an already-open PTY.
    // Subscribe after a delay to prove the early output is recovered by replay.
    let setup_outcome = client
        .call(
            methods::CREATE_WORKTREE,
            json!({
                "repoPath": project_root,
                "branch": "main",
                "spaceId": "space-actions",
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .expect("create remote worktree with setup");
    assert!(setup_outcome.get("setupError").is_none());
    let setup_terminal = setup_outcome["setupAction"]["terminal"]["id"]
        .as_str()
        .expect("remote setup terminal")
        .to_string();
    let setup_worktree =
        std::path::PathBuf::from(setup_outcome["path"].as_str().expect("remote setup worktree"));
    tokio::time::sleep(Duration::from_millis(250)).await;
    let mut setup_stream = client
        .subscribe(
            methods::SUBSCRIBE_TERMINAL,
            json!({
                "terminalId": setup_terminal,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .expect("subscribe remote setup replay");
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    loop {
        let item = tokio::time::timeout_at(deadline, setup_stream.recv())
            .await
            .expect("remote setup replay before timeout")
            .expect("setup stream alive");
        if item["type"] == "data" {
            let output = BASE64
                .decode(item["data"].as_str().expect("setup data"))
                .expect("setup base64");
            if String::from_utf8_lossy(&output).contains("remote-action")
                && setup_worktree.join("setup-marker").exists()
            {
                break;
            }
        }
    }
    let canonical_project = std::fs::canonicalize(&project_root).unwrap();
    let canonical_worktree = std::fs::canonicalize(&setup_worktree).unwrap();
    let setup_marker =
        std::fs::read_to_string(setup_worktree.join("setup-marker")).expect("setup marker on B");
    assert!(
        setup_marker.contains(&format!("ROOT={}", canonical_project.display())),
        "{setup_marker}"
    );
    assert!(
        setup_marker.contains(&format!("WT={}", canonical_worktree.display())),
        "{setup_marker}"
    );
    if !cfg!(windows) {
        // cmd.exe reports %CD% in its own (non-canonical) form.
        assert!(
            setup_marker.contains(&format!("CWD={}", canonical_worktree.display())),
            "{setup_marker}"
        );
    }
    assert!(
        !dirs.path().join("a").join("setup-marker").exists()
            && !dirs
                .path()
                .join("a")
                .join("project-on-b")
                .join("setup-marker")
                .exists(),
        "setup must not fall back to A's filesystem"
    );
    client
        .call(
            methods::CLOSE_TERMINAL,
            json!({
                "terminalId": setup_terminal,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();
    client
        .call(
            methods::DELETE_WORKTREE,
            json!({
                "repoPath": project_root,
                "worktreePath": setup_worktree,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();

    let deleted = client
        .call(
            methods::DELETE_PROJECT_ACTION,
            json!({
                "spaceId": "space-actions",
                "actionId": action_id,
                "targetDeviceId": core_b.device_id,
            }),
        )
        .await
        .unwrap();
    assert_eq!(deleted["actions"].as_array().unwrap().len(), 0);

    drop(client);
    drop(remote);
    core_a.shutdown().await;
    core_b.shutdown().await;
}
