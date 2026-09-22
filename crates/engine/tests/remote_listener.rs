use std::{sync::Arc, time::Duration};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use roboco_engine::{EngineCore, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::{connect_ws, connect_ws_authenticated, memory_client, methods};
use serde_json::{Value, json};

/// Generated media uses the same path jail on the local in-process surface
/// and over a paired peer connection: the materialized upload reads back on
/// both, while the agent-owned source path stays outside the jail on both.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn generated_media_reads_share_the_jail_across_local_and_paired_rpc() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let generated_root = dir.path().join("generated_images");
    std::fs::create_dir_all(&generated_root).unwrap();
    let source = generated_root.join("codex.png");
    let payload = b"\x89PNG\r\n\x1a\nremote-generated-image";
    std::fs::write(&source, payload).unwrap();
    let image = core
        .uploads
        .import_generated_image(&source, &generated_root, "chat-remote\0image")
        .unwrap();
    let local = memory_client(core.rpc_service());
    let local_image = local
        .call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": image.path, "offset": 0}),
        )
        .await
        .unwrap();
    let store = PairingStore::open(dir.path()).unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    let url = format!("ws://{}", remote.address);
    let http = reqwest::Client::new();
    let code = store.create_code("remote", 300).unwrap();
    let grant: Value = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Laptop"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap();
    let rpc = connect_ws_authenticated(&url, credential).await.unwrap();
    let remote_image = rpc
        .call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": image.path, "offset": 0, "targetDeviceId": core.device_id}),
        )
        .await
        .unwrap();
    assert_eq!(remote_image, local_image);
    assert_eq!(remote_image["mimeType"], "image/png");
    assert_eq!(remote_image["done"], true);
    assert!(
        rpc.call(
            methods::READ_ATTACHMENT_CHUNK,
            json!({"path": source, "offset": 0, "targetDeviceId": core.device_id})
        )
        .await
        .is_err()
    );
    drop(rpc);
    drop(remote);
    core.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn paired_bind_enforces_sessions_and_preserves_rpc_capabilities() {
    let dir = tempfile::tempdir().unwrap();
    let folder = dir.path().join("project");
    std::fs::create_dir(&folder).unwrap();
    std::fs::write(folder.join("note.txt"), "engine-local content").unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_space(
            "space",
            &core.device_id,
            &folder.to_string_lossy(),
            None,
            false,
        )
        .unwrap();
    core.workspace
        .create_chat("chat", Some("space"), None, None, None)
        .unwrap();
    let store = PairingStore::open(dir.path()).unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);
    let url = format!("ws://{}", remote.address);
    let http = reqwest::Client::new();
    assert_eq!(
        http.get(format!("{base}/health"))
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    // A credential-less dial now passes the HTTP upgrade — browsers
    // authenticate with a first-frame Auth envelope instead — but the engine
    // refuses RPC until a session credential arrives.
    let unauthenticated = connect_ws(&url).await.unwrap();
    assert!(
        unauthenticated
            .call(methods::ENGINE_INFO, json!({}))
            .await
            .is_err()
    );
    drop(unauthenticated);
    assert!(connect_ws_authenticated(&url, "wrong").await.is_err());
    let code = store.create_code("remote", 300).unwrap();
    let grant: Value = http
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Laptop"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap();
    let session_id = grant["session"]["id"].as_str().unwrap();
    assert_eq!(
        http.get(format!("{base}/health"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        200
    );
    let rpc = connect_ws_authenticated(&url, credential).await.unwrap();
    let info = rpc.call(methods::ENGINE_INFO, json!({})).await.unwrap();
    assert_eq!(info["deviceId"], core.device_id);
    let read = rpc
        .call(
            methods::READ_WORKSPACE_FILE,
            json!({"chatId":"chat", "path":"note.txt"}),
        )
        .await
        .unwrap();
    assert_eq!(read["text"], "engine-local content");
    let mut transcript = rpc
        .subscribe(methods::WATCH_DOC_MESSAGES, json!({"chatId":"chat"}))
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), transcript.recv())
            .await
            .unwrap()
            .is_some()
    );
    let mut queue = rpc
        .subscribe(methods::WATCH_QUEUE, json!({"chatId":"chat"}))
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), queue.recv())
            .await
            .unwrap()
            .is_some()
    );
    let terminal = rpc
        .call(
            methods::OPEN_TERMINAL,
            json!({"chatId":"chat","cols":80,"rows":24}),
        )
        .await
        .unwrap();
    let terminal_id = terminal["id"].as_str().unwrap();
    rpc.call(
        methods::WRITE_TERMINAL,
        json!({"terminalId":terminal_id, "data":STANDARD.encode("echo remote-listener\r\n")}),
    )
    .await
    .unwrap();
    rpc.call(methods::CLOSE_TERMINAL, json!({"terminalId":terminal_id}))
        .await
        .unwrap();
    assert!(
        rpc.call(
            methods::READ_WORKSPACE_FILE,
            json!({"chatId":"chat","path":"note.txt","targetDeviceId":"another-engine"})
        )
        .await
        .is_err()
    );
    assert!(store.revoke(session_id).unwrap());
    assert!(connect_ws_authenticated(&url, credential).await.is_err());
    assert_eq!(
        http.get(format!("{base}/health"))
            .bearer_auth(credential)
            .send()
            .await
            .unwrap()
            .status(),
        401
    );
    // Disabling a bind also closes already-authorized connections and streams.
    drop(remote);
    assert!(
        tokio::time::timeout(Duration::from_secs(5), transcript.recv())
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        tokio::time::timeout(
            Duration::from_secs(5),
            rpc.call(methods::ENGINE_INFO, json!({}))
        )
        .await
        .unwrap()
        .is_err()
    );
    drop(queue);
    drop(rpc);
    core.shutdown().await;
}
