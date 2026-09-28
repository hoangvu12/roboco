use super::*;
use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry, pairing::PairingStore};

fn core(dir: &std::path::Path) -> EngineCore {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_space(
            "same-space",
            &core.device_id,
            &dir.to_string_lossy(),
            None,
            false,
        )
        .unwrap();
    core.workspace
        .create_chat("same-chat", Some("same-space"), None, None, None)
        .unwrap();
    core
}
async fn wait_for(registry: &EngineRegistry, predicate: impl Fn(&RegistrySnapshot) -> bool) {
    let mut changes = registry.watch();
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if predicate(&changes.borrow_and_update()) {
                return;
            }
            changes.changed().await.unwrap();
        }
    })
    .await
    .expect("registry did not reach expected state");
}

/// Every unary call gets a bounded reply deadline — interactive calls fail
/// fast, adapter discovery gets its cold-boot budget, network-bound git
/// methods get the long leash, and nothing awaits forever.
#[test]
fn call_deadlines_are_tiered_and_bounded() {
    assert_eq!(call_deadline(methods::LIST_MODELS), Duration::from_secs(100));
    assert_eq!(
        call_deadline(methods::LIST_COMMANDS),
        Duration::from_secs(100)
    );
    assert_eq!(call_deadline(methods::CLONE_REPO), Duration::from_secs(15 * 60));
    assert_eq!(call_deadline(methods::CREATE_WORKTREE), Duration::from_secs(30));
    assert_eq!(call_deadline(methods::QUEUE_COMMAND), Duration::from_secs(30));
}

#[test]
fn scoped_identity_codec_is_collision_safe() {
    let remote = EngineKey("remote".into());
    for raw in ["same-id", "engine:v1:invalid", "folder/??"] {
        for key in [EngineKey::local(), remote.clone()] {
            let encoded = ScopedId::encode(&key, raw);
            assert_eq!(
                ScopedId::parse(&encoded).unwrap(),
                ScopedId {
                    engine: key,
                    raw_id: raw.into()
                }
            );
        }
        assert_ne!(
            ScopedId::encode(&EngineKey::local(), raw),
            ScopedId::encode(&remote, raw)
        );
    }
    assert!(ScopedId::parse("engine:v1:bad").is_err());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_engine_reconnect_retains_rows_persists_pairing_and_forgets() {
    let local_dir = tempfile::tempdir().unwrap();
    let remote_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let local = core(local_dir.path());
    let remote = core(remote_dir.path());
    let client = Arc::new(roboco_rpc::memory_client(local.rpc_service()));
    let info: EngineInfo = client
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    let path = ui_dir.path().join("paired-engines.json");
    let registry = EngineRegistry::open(path.clone(), info.clone(), client.clone(), None)
        .await
        .unwrap();
    let listener = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        remote.rpc_service(),
        remote_dir.path(),
    )
    .await
    .unwrap();
    let address = listener.address;
    let store = PairingStore::open(remote_dir.path()).unwrap();
    let code = store.create_code("registry test", 300).unwrap();
    let key = registry
        .pair(
            &format!("http://{address}/pair#token={}", code.credential),
            "Test desktop",
        )
        .await
        .unwrap();
    // Wait for the projected rows themselves, not only the loaded flags: a
    // subscription's first frame can predate the workspace's first publish,
    // so a loaded flag does not guarantee the rows are visible yet.
    wait_for(&registry, |snapshot| {
        let projected = snapshot.projected();
        snapshot.engines.len() == 2
            && projected.chats.len() == 2
            && projected.spaces.len() == 2
            && snapshot.engines.iter().all(|e| {
                e.chats_loaded && e.spaces_loaded && e.state == EngineConnectionState::Connected
            })
    })
    .await;
    let projected = registry.snapshot().projected();
    assert_eq!(projected.chats.len(), 2);
    assert_ne!(projected.chats[0].id, projected.chats[1].id);
    assert_eq!(projected.spaces.len(), 2);
    let target = registry.target(&key).unwrap();
    let remote_chat = ScopedId::encode(&key, "same-chat");
    let mut transcript = target
        .subscribe_checked(methods::WATCH_DOC_MESSAGES, json!({"chatId":remote_chat}))
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_secs(5), transcript.recv())
            .await
            .unwrap()
            .is_some()
    );
    drop(listener);
    wait_for(&registry, |snapshot| {
        snapshot
            .engines
            .iter()
            .any(|e| e.key == key && e.state == EngineConnectionState::Reconnecting)
    })
    .await;
    assert_eq!(registry.snapshot().projected().chats.len(), 2);
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_err());
    assert!(
        registry
            .local()
            .call(methods::ENGINE_INFO, json!({}))
            .await
            .is_ok()
    );
    remote.shutdown().await;
    drop(remote);
    let remote = EngineCore::assemble_with_profile(
        EngineProfile::local(remote_dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let listener =
        roboco_engine::serve_engine_remote(address, remote.rpc_service(), remote_dir.path())
            .await
            .unwrap();
    wait_for(&registry, |snapshot| {
        snapshot.engines.iter().any(|e| {
            e.key == key && e.state == EngineConnectionState::Connected && e.generation >= 2
        })
    })
    .await;
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_ok());
    registry.shutdown().await;
    drop(listener);
    drop(registry);
    let reopened = EngineRegistry::open(path.clone(), info, client, None)
        .await
        .unwrap();
    assert_eq!(reopened.snapshot().engines.len(), 2);
    assert_eq!(
        reopened.snapshot().projected().chats.len(),
        2,
        "cached history survives app restart while remote is down"
    );
    let forgotten = reopened.target(&key).unwrap();
    reopened.forget(&key).await.unwrap();
    assert_eq!(reopened.snapshot().engines.len(), 1);
    assert!(
        forgotten
            .call(methods::ENGINE_INFO, json!({}))
            .await
            .is_err()
    );
    assert!(
        forgotten.engine_info().device_id.len() > 0,
        "stale target metadata remains safe"
    );
    let saved: SavedRegistry = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    assert!(saved.engines.is_empty());
    assert!(
        crate::engine_cache::EngineCache::new(ui_dir.path())
            .load_rows(&key.0)
            .is_none()
    );
    reopened.shutdown().await;
    local.shutdown().await;
    remote.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn damaged_pairing_config_preserves_local_access_and_original_bytes() {
    let dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let local = core(dir.path());
    let client = Arc::new(roboco_rpc::memory_client(local.rpc_service()));
    let info = client
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    let path = ui_dir.path().join("paired.json");
    std::fs::write(&path, b"damaged config").unwrap();
    let registry = EngineRegistry::open(path.clone(), info, client, None)
        .await
        .unwrap();
    // Same reasoning as above: wait for the chat row itself to arrive.
    wait_for(&registry, |snapshot| {
        !snapshot.engines.is_empty() && snapshot.projected().chats.len() == 1
    })
    .await;
    assert_eq!(registry.snapshot().projected().chats.len(), 1);
    assert!(registry.snapshot().configuration_error.is_some());
    assert!(
        registry
            .pair("http://localhost/pair#token=code", "test")
            .await
            .is_err()
    );
    assert_eq!(std::fs::read(path).unwrap(), b"damaged config");
    registry.shutdown().await;
    local.shutdown().await;
}
