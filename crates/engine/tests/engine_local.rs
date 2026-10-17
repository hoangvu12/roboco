//! Local listener feeds and document durability after removing cloud rooms.
use roboco_engine::{EngineCore, HarnessRegistry};
use roboco_proto::HarnessId;
use roboco_rpc::methods;
use serde_json::json;
use std::{sync::Arc, time::Duration};

#[tokio::test]
async fn local_listener_feeds_exclude_legacy_cloud_rows_and_keep_transcripts() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(roboco_rpc::serve_ws_listener(listener, core.rpc_service()));
    let client = roboco_rpc::connect_ws(&format!("ws://{address}"))
        .await
        .unwrap();
    client
        .call(
            methods::MUTATE,
            json!({"op":"createChat", "chatId":"local-chat", "deviceId":core.device_id}),
        )
        .await
        .unwrap();

    // Simulate rows left in a previously synced registry, without networking.
    let mut foreign = core.workspace.chat("local-chat").unwrap().unwrap();
    foreign.id = "foreign-chat".into();
    foreign.device_id = "old-cloud-device".into();
    core.workspace.import_chat_row(&foreign).unwrap();
    let mut device = core.workspace.read_devices().unwrap().remove(0);
    device.id = foreign.device_id;
    core.workspace.upsert_device_row(&device);

    let mut chats = client
        .subscribe(methods::WATCH_CHATS, json!({}))
        .await
        .unwrap();
    let mut devices = client
        .subscribe(methods::WATCH_DEVICES, json!({}))
        .await
        .unwrap();
    let rows = tokio::time::timeout(Duration::from_secs(3), async {
        loop {
            let rows = chats.recv().await.unwrap();
            if rows
                .as_array()
                .unwrap()
                .iter()
                .any(|row| row["id"] == "local-chat")
            {
                break rows;
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(rows.as_array().unwrap().len(), 1);
    let rows = devices.recv().await.unwrap();
    assert_eq!(rows.as_array().unwrap().len(), 1);
    assert_eq!(rows[0]["id"], core.device_id);

    core.doc_host
        .open("local-chat")
        .unwrap()
        .write_user_message("m1", "saved locally", 123, None)
        .unwrap();
    // Completed subagent/tool sidecars use the local database too.
    core.doc_host.upload_tool_sidecar(
        "local-chat",
        roboco_doc::SidecarPayload {
            part_id: "tool#1".into(),
            output: Some("durable output".into()),
            diff: None,
        },
    );
    assert_eq!(
        core.doc_host
            .fetch_tool_blob("local-chat/tool#1")
            .await
            .unwrap(),
        "durable output"
    );
    server.abort();
    core.shutdown().await;
    drop(client);
    drop(core);
    let core = EngineCore::assemble(
        dir.path(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    assert_eq!(core.workspace.read_chats().unwrap().len(), 1);
    let entries = core
        .doc_host
        .open("local-chat")
        .unwrap()
        .doc()
        .read_entries()
        .unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].id, "m1");
    assert_eq!(
        core.doc_host
            .fetch_tool_blob("local-chat/tool#1")
            .await
            .unwrap(),
        "durable output"
    );
    core.shutdown().await;
}
