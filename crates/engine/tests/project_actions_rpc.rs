//! Project Actions over direct engine connections (ticket-24 decision):
//! upstream exercised this surface through the cloud device-room relay; roboco
//! has no relay, so the same behavior maps to two engines with their own
//! clients — the owning engine answers, and a `targetDeviceId` that names
//! another engine fails closed at `EngineRpc::handle` entry instead of
//! forwarding.

use std::sync::Arc;

use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_proto::HarnessId;
use roboco_rpc::{memory_client, methods};

fn assemble(dir: &std::path::Path, device_id: &str) -> EngineCore {
    std::fs::create_dir_all(dir).expect("create data dir");
    std::fs::write(dir.join("device-id"), device_id).expect("write device id");
    EngineCore::assemble(
        dir,
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .expect("engine assembles")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn project_actions_manage_on_owning_engine_and_fail_closed_elsewhere() {
    let dirs = tempfile::tempdir().expect("tempdir");

    let core_a = assemble(&dirs.path().join("a"), "device-a");
    let core_b = assemble(&dirs.path().join("b"), "device-b");

    // Project Actions are device-routed and persist only in the owning
    // engine's private profile store. A versioned project file only
    // contributes import offers.
    let project_root = dirs.path().join("project-on-b");
    std::fs::create_dir_all(&project_root).expect("project root on B");
    std::fs::write(
        project_root.join("roboco.json"),
        r#"{"actions":[{"name":"Lint","command":"pnpm lint","icon":"lint"}]}"#,
    )
    .expect("project file");
    core_b
        .workspace
        .create_space(
            "space-actions",
            "device-b",
            &project_root.to_string_lossy(),
            None,
            true,
        )
        .expect("space row on B");

    // Each engine serves its own connection; routing is client-side.
    let client_b = memory_client(core_b.rpc_service());
    let client_a = memory_client(core_a.rpc_service());

    // Our own id in targetDeviceId: handled locally, no forward.
    let listed = client_b
        .call(
            methods::LIST_PROJECT_ACTIONS,
            serde_json::json!({
                "spaceId": "space-actions",
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect("list actions on B");
    assert_eq!(listed["actions"].as_array().unwrap().len(), 0);
    assert_eq!(listed["importableActions"].as_array().unwrap().len(), 1);

    let saved = client_b
        .call(
            methods::UPSERT_PROJECT_ACTION,
            serde_json::json!({
                "spaceId": "space-actions",
                "targetDeviceId": "device-b",
                "action": {
                    "name": "Lint",
                    "command": "pnpm lint",
                    "icon": "lint",
                    "runOnWorktreeCreate": true,
                },
            }),
        )
        .await
        .expect("save action on B");
    let action_id = saved["actions"][0]["id"]
        .as_str()
        .expect("normalized action id")
        .to_string();
    assert!(saved["importableActions"].as_array().unwrap().is_empty());
    assert_eq!(
        core_b
            .project_actions
            .actions("space-actions", &project_root)
            .expect("B store")
            .len(),
        1
    );
    assert!(
        core_a
            .project_actions
            .actions("space-actions", &project_root)
            .expect("A store")
            .is_empty(),
        "actions persist only on the engine owning the space row"
    );

    let deleted = client_b
        .call(
            methods::DELETE_PROJECT_ACTION,
            serde_json::json!({
                "spaceId": "space-actions",
                "actionId": action_id,
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect("delete action on B");
    assert!(deleted["actions"].as_array().unwrap().is_empty());

    // A wrong target fails closed: roboco has no relay, so engine A's surface
    // refuses the request instead of forwarding it to B (upstream asserted the
    // relay path; the decision re-homes this to the fail-closed entry check).
    let wrong_target = client_a
        .call(
            methods::LIST_PROJECT_ACTIONS,
            serde_json::json!({
                "spaceId": "space-actions",
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect_err("wrong target must fail closed");
    assert!(
        wrong_target.to_string().contains("not connected"),
        "got: {wrong_target}"
    );

    // The ownership guard still defends depth on the owning engine: a space
    // row owned by another device is rejected …
    core_b
        .workspace
        .create_space(
            "space-wrong-owner",
            "device-a",
            &project_root.to_string_lossy(),
            None,
            true,
        )
        .expect("foreign-owned row on B");
    let wrong_owner = client_b
        .call(
            methods::LIST_PROJECT_ACTIONS,
            serde_json::json!({
                "spaceId": "space-wrong-owner",
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect_err("foreign-owned space rejected by B");
    assert!(
        wrong_owner
            .to_string()
            .contains("belongs to another device")
    );

    // … an unknown space is rejected …
    let missing = client_b
        .call(
            methods::LIST_PROJECT_ACTIONS,
            serde_json::json!({
                "spaceId": "missing",
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect_err("missing space rejected by B");
    assert!(missing.to_string().contains("Project space not found"));

    // … and a moved project root fails the store's identity guard.
    let moved_root = dirs.path().join("project-moved-on-b");
    std::fs::create_dir_all(&moved_root).expect("moved project root");
    let mut moved_space = core_b
        .workspace
        .space("space-actions")
        .expect("read space")
        .expect("space exists");
    moved_space.path = moved_root.to_string_lossy().to_string();
    core_b
        .workspace
        .import_space_row(&moved_space)
        .expect("replace space root");
    let changed_root = client_b
        .call(
            methods::LIST_PROJECT_ACTIONS,
            serde_json::json!({
                "spaceId": "space-actions",
                "targetDeviceId": "device-b",
            }),
        )
        .await
        .expect_err("changed project root rejected by B");
    assert!(
        changed_root
            .to_string()
            .contains("identity no longer matches")
    );

    core_a.shutdown().await;
    core_b.shutdown().await;
}
