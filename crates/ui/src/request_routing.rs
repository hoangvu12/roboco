//! Resolve a request owner before asynchronous work starts.
//!
//! `raw_id`, `wire_params` and `scope_checkout_frame` are GPUI-free and live
//! in `roboco-engine-client`; the two functions that read `AppState` stay
//! here, beside the UI dispatch tests that exercise them.

use crate::engine_registry::{EngineTarget, ScopedId};
use crate::state::AppState;
use roboco_rpc::RpcError;

pub use roboco_engine_client::routing::{raw_id, scope_checkout_frame, wire_params};

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

/// An explicit device-tab owner, or the local engine when the Local tab is chosen.
pub fn device_target(state: &AppState, device: Option<&str>) -> Result<EngineTarget, RpcError> {
    match device {
        Some(id) => state.target_for_id(id),
        None => state.local_target(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Per-chat engine calls go through an [`EngineTarget`]
    /// (`target_for_id`, [`selected_target`], [`device_target`]). The local
    /// engine's raw client (`state.engine()` + `.client()`) answers only for
    /// this machine — Roboco engines relay nothing, so a paired engine's chat
    /// sent there fails (`targetDeviceId` is refused). Only the deliberate
    /// local-engine sites below may use it; a new one fails this test.
    #[test]
    fn raw_local_client_calls_stay_on_the_allow_list() {
        // (file, raw RPC chains `.client().call…/.subscribe…`, captures of
        // the local handle `.engine().cloned()`, why it is local)
        const ALLOWED: &[(&str, usize, usize, &str)] = &[
            ("lib.rs", 0, 1, "quit stops the local engine"),
            ("state.rs", 6, 0, "bootstrap watches, the local-device probe, engine boot tests"),
            ("settings/auto_resume.rs", 2, 2, "this engine's own settings"),
            ("settings/completion.rs", 1, 1, "this engine's own settings"),
            ("settings/thread_naming.rs", 1, 1, "this engine's own settings"),
            ("settings/remote_access.rs", 2, 1, "this engine's listener and paired clients"),
            ("shell/harness_updates.rs", 1, 2, "this engine's harness updates"),
            ("shell/spaces.rs", 0, 1, "test teardown"),
            ("paired_fixture.rs", 0, 1, "test teardown"),
        ];
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut found = Vec::new();
        let mut pending = vec![root.clone()];
        while let Some(dir) = pending.pop() {
            for entry in std::fs::read_dir(&dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    pending.push(path);
                    continue;
                }
                // This file names the patterns it looks for.
                if path.extension().is_none_or(|ext| ext != "rs")
                    || path == root.join("request_routing.rs")
                {
                    continue;
                }
                // Whitespace-free, so rustfmt's line breaks cannot hide a chain.
                let code: String = std::fs::read_to_string(&path)
                    .unwrap()
                    .chars()
                    .filter(|c| !c.is_whitespace())
                    .collect();
                let rpc = code.matches(".client().call").count()
                    + code.matches(".client().subscribe").count();
                let captures = code.matches(".engine().cloned()").count();
                if rpc + captures > 0 {
                    let name = path
                        .strip_prefix(&root)
                        .unwrap()
                        .to_string_lossy()
                        .replace('\\', "/");
                    found.push((name, rpc, captures));
                }
            }
        }
        found.sort();
        let mut allowed: Vec<_> = ALLOWED
            .iter()
            .map(|(name, rpc, captures, _)| (name.to_string(), *rpc, *captures))
            .collect();
        allowed.sort();
        assert_eq!(
            found, allowed,
            "route per-chat calls through an EngineTarget (target_for_id / selected_target / \
             device_target); only deliberately local sites may join the allow-list"
        );
    }

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
}
