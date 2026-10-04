//! Resolve a request owner before asynchronous work starts.

use crate::engine_registry::{EngineTarget, ScopedId};
use crate::state::AppState;
use roboco_rpc::RpcError;

/// The selected chat wins; a new chat uses its chosen space/device.
/// Once captured, this target never follows later UI selection changes.
pub fn selected_target(state: &AppState) -> Result<EngineTarget, RpcError> {
    if let Some(chat) = state.selected_chat.as_deref() {
        return state.target_for_id(chat);
    }
    if let Some(space) = state.selected_space_row() {
        return state.target_for_id(&space.id);
    }
    if let Some(device) = state.effective_device_id() {
        return state.target_for_id(&device);
    }
    state.local_target()
}

pub fn raw_id(id: &str) -> Result<String, RpcError> {
    Ok(ScopedId::parse(id)?.raw_id)
}

/// An explicit device-tab owner, or the local engine when the Local tab is chosen.
pub fn device_target(state: &AppState, device: Option<&str>) -> Result<EngineTarget, RpcError> {
    match device {
        Some(id) => state.target_for_id(id),
        None => state.local_target(),
    }
}

/// Only RPC envelope identity fields cross this boundary. Text, paths, model
/// options, tools, and user JSON remain byte-for-byte unchanged.
pub fn wire_params(
    owner: &crate::engine_registry::EngineKey,
    method: &str,
    mut params: serde_json::Value,
) -> Result<serde_json::Value, RpcError> {
    fn decode(
        owner: &crate::engine_registry::EngineKey,
        value: &mut serde_json::Value,
    ) -> Result<(), RpcError> {
        let Some(id) = value.as_str() else {
            return Ok(());
        };
        let parsed = ScopedId::parse(id)?;
        if ScopedId::is_scoped(id) && &parsed.engine != owner {
            return Err(RpcError::Failed(
                "Request identity belongs to another engine".into(),
            ));
        }
        *value = serde_json::Value::String(parsed.raw_id);
        Ok(())
    }
    fn fields(
        owner: &crate::engine_registry::EngineKey,
        object: &mut serde_json::Map<String, serde_json::Value>,
    ) -> Result<(), RpcError> {
        for field in [
            "chatId",
            "spaceId",
            "deviceId",
            "checkoutId",
            "expectedCheckoutId",
            "docId",
            "parentChatId",
            "targetDeviceId",
        ] {
            if let Some(value) = object.get_mut(field) {
                decode(owner, value)?;
            }
        }
        // Routing ends at the socket; no engine forwards this request.
        object.remove("targetDeviceId");
        Ok(())
    }
    if let Some(object) = params.as_object_mut() {
        fields(owner, object)?;
        if let Some(target) = object
            .get_mut("target")
            .and_then(serde_json::Value::as_object_mut)
        {
            fields(owner, target)?;
        }
        if method == roboco_rpc::methods::MUTATE {
            if let Some(id) = object.get_mut("id") {
                decode(owner, id)?;
            }
        }
    }
    Ok(params)
}

/// Checkout diff streams are engine-local; only their row identity is projected.
pub fn scope_checkout_frame(
    owner: &crate::engine_registry::EngineKey,
    mut frame: serde_json::Value,
) -> serde_json::Value {
    fn row(owner: &crate::engine_registry::EngineKey, value: &mut serde_json::Value) {
        if let Some(object) = value.as_object_mut() {
            for field in ["checkoutId", "deviceId"] {
                if let Some(value) = object.get_mut(field) {
                    if let Some(id) = value.as_str() {
                        *value = serde_json::Value::String(ScopedId::encode(owner, id));
                    }
                }
            }
        }
    }
    match &mut frame {
        serde_json::Value::Array(rows) => rows.iter_mut().for_each(|value| row(owner, value)),
        value => row(owner, value),
    }
    frame
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine_registry::EngineKey;
    use serde_json::json;

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn two_real_engines_keep_colliding_ids_and_uploads_separate() {
        use crate::engine_registry::{EngineRegistry, Reconnect};
        use base64::{Engine as _, engine::general_purpose::STANDARD};
        use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry, pairing};
        use roboco_rpc::methods;
        use std::{sync::Arc, time::Duration};

        let a = tempfile::tempdir().unwrap();
        let b = tempfile::tempdir().unwrap();
        let mut cores = Vec::new();
        for (dir, content) in [(&a, "local bytes"), (&b, "remote bytes")] {
            let folder = dir.path().join("project");
            std::fs::create_dir(&folder).unwrap();
            std::fs::write(folder.join("note.txt"), content).unwrap();
            let core = EngineCore::assemble_with_profile(
                EngineProfile::local(dir.path()).unwrap(),
                Arc::new(HarnessRegistry::new()),
                HarnessId::Mock,
            )
            .unwrap();
            core.workspace
                .create_space(
                    "same-space",
                    &core.device_id,
                    &folder.to_string_lossy(),
                    None,
                    false,
                )
                .unwrap();
            core.workspace
                .create_chat("same-chat", Some("same-space"), None, None, None)
                .unwrap();
            cores.push(core);
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}", listener.local_addr().unwrap());
        let local_server = tokio::spawn(roboco_rpc::serve_ws_listener(
            listener,
            cores[0].rpc_service(),
        ));
        let client = Arc::new(roboco_rpc::connect_ws(&url).await.unwrap());
        let info = client
            .call_as(methods::ENGINE_INFO, json!({}))
            .await
            .unwrap();
        let registry = EngineRegistry::open(
            a.path().join("client-engines.json"),
            info,
            client,
            Some(Reconnect::Url(url)),
        )
        .await
        .unwrap();
        let remote = roboco_engine::serve_engine_remote(
            "127.0.0.1:0".parse().unwrap(),
            cores[1].rpc_service(),
            b.path(),
        )
        .await
        .unwrap();
        let code = pairing::PairingStore::open(b.path())
            .unwrap()
            .create_code("routing test", 300)
            .unwrap();
        let pairing_url =
            pairing::pairing_url(&format!("http://{}", remote.address), &code.credential).unwrap();
        let key = registry.pair(&pairing_url, "Remote").await.unwrap();
        let target = registry.target(&key).unwrap();
        tokio::time::timeout(Duration::from_secs(10), async {
            while !target.is_connected() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        let chat = ScopedId::encode(&key, "same-chat");
        for (owner, chat_id, expected) in [
            (registry.local(), "same-chat".to_string(), "local bytes"),
            (target.clone(), chat.clone(), "remote bytes"),
        ] {
            let read = owner
                .call(
                    methods::READ_WORKSPACE_FILE,
                    json!({"chatId":chat_id,"path":"note.txt"}),
                )
                .await
                .unwrap();
            assert_eq!(read["text"], expected);
            if !owner.key().is_local() {
                owner.call(methods::WRITE_WORKSPACE_FILE, json!({
                    "chatId":chat_id,"path":"note.txt","text":"remote edit",
                    "expectedCheckoutId":read["checkoutId"],
                    "expectedContentHash":read["contentHash"],"encoding":"utf8","lineEnding":"lf"
                })).await.unwrap();
                assert_eq!(
                    std::fs::read_to_string(b.path().join("project/note.txt")).unwrap(),
                    "remote edit"
                );
                assert_eq!(
                    std::fs::read_to_string(a.path().join("project/note.txt")).unwrap(),
                    "local bytes"
                );
            }
            let mut image_bytes = std::io::Cursor::new(Vec::new());
            image::DynamicImage::ImageRgba8(image::RgbaImage::from_pixel(
                1,
                1,
                image::Rgba([if owner.key().is_local() { 20 } else { 80 }, 0, 0, 255]),
            ))
            .write_to(&mut image_bytes, image::ImageFormat::Png)
            .unwrap();
            let image_bytes = image_bytes.into_inner();
            owner
                .call(
                    methods::UPLOAD_CHUNK,
                    json!({"uploadId":"same-upload","seq":0,"data":STANDARD.encode(&image_bytes)}),
                )
                .await
                .unwrap();
            let upload = owner
                .call(
                    methods::UPLOAD_COMMIT,
                    json!({"uploadId":"same-upload","fileName":"note.png"}),
                )
                .await
                .unwrap();
            let read = owner
                .call(
                    methods::READ_ATTACHMENT_CHUNK,
                    json!({"path":upload["path"],"offset":0}),
                )
                .await
                .unwrap();
            assert_eq!(
                STANDARD.decode(read["data"].as_str().unwrap()).unwrap(),
                image_bytes
            );
        }
        let mut transcript = target
            .subscribe(methods::WATCH_DOC_MESSAGES, json!({"chatId":chat}))
            .await
            .unwrap();
        assert!(
            tokio::time::timeout(Duration::from_secs(5), transcript.recv())
                .await
                .unwrap()
                .is_some()
        );
        let terminal = target
            .call(
                methods::OPEN_TERMINAL,
                json!({"chatId":chat,"cols":80,"rows":24}),
            )
            .await
            .unwrap();
        target
            .call(
                methods::RESIZE_TERMINAL,
                json!({"terminalId":terminal["id"],"cols":100,"rows":30}),
            )
            .await
            .unwrap();
        target.call(methods::WRITE_TERMINAL, json!({"terminalId":terminal["id"],"data":STANDARD.encode("echo routing-test\r\n")})).await.unwrap();
        target
            .call(
                methods::CLOSE_TERMINAL,
                json!({"terminalId":terminal["id"]}),
            )
            .await
            .unwrap();
        drop(remote);
        tokio::time::timeout(Duration::from_secs(5), async {
            while target.is_connected() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        assert!(
            tokio::time::timeout(
                Duration::from_millis(250),
                target.call(
                    methods::READ_WORKSPACE_FILE,
                    json!({"chatId":chat,"path":"note.txt"})
                )
            )
            .await
            .unwrap()
            .is_err()
        );
        let local = registry
            .local()
            .call(
                methods::READ_WORKSPACE_FILE,
                json!({"chatId":"same-chat","path":"note.txt"}),
            )
            .await
            .unwrap();
        assert_eq!(local["text"], "local bytes");
        registry.shutdown().await;
        local_server.abort();
        for core in cores {
            core.shutdown().await;
        }
    }

    #[test]
    fn wire_boundary_decodes_only_request_identity() {
        let owner = EngineKey("engine-b".into());
        let id = ScopedId::encode(&owner, "same-chat");
        let params = json!({"chatId": id, "targetDeviceId": ScopedId::encode(&owner, "device"),
            "text": id, "options": {"chatId": id}, "path": id,
            "target": {"chatId": id}});
        let wire = wire_params(&owner, "ReadWorkspaceFile", params.clone()).unwrap();
        assert_eq!(wire["chatId"], "same-chat");
        assert_eq!(wire["target"]["chatId"], "same-chat");
        assert!(wire.get("targetDeviceId").is_none());
        for key in ["text", "options", "path"] {
            assert_eq!(wire[key], params[key]);
        }
    }

    #[test]
    fn wire_boundary_rejects_another_engine_identity() {
        let owner = EngineKey("engine-b".into());
        let foreign = ScopedId::encode(&EngineKey("engine-a".into()), "same-chat");
        assert!(wire_params(&owner, "QueueCommand", json!({"chatId": foreign})).is_err());
        let local_reserved = ScopedId::encode(&EngineKey::local(), "engine:v1:literal-id");
        assert!(wire_params(&owner, "QueueCommand", json!({"chatId": local_reserved})).is_err());
    }
}
