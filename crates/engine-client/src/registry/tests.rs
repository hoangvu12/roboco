use super::*;
use crate::{FileSessionStore, PairingResolver, RegistryOptions, ResolvedPairing, SessionStore};
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
/// methods and attachment transfers get the long leash, and nothing awaits
/// forever.
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
    // Attachment calls carry their own ladder; the inner cap must never
    // bind below it (the 90 s first-chunk budget vs a 30 s default).
    for method in [
        methods::UPLOAD_CHUNK,
        methods::UPLOAD_COMMIT,
        methods::READ_ATTACHMENT_CHUNK,
    ] {
        assert_eq!(call_deadline(method), Duration::from_secs(15 * 60), "deadline for {method}");
    }
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

/// A side chat keeps pointing at its parent once projected: `parentChatId`
/// and the agent origin are scoped with the row's own engine, so a paired
/// engine's side chat lists under its (scoped) parent and never under a
/// local chat that happens to share the raw id.
#[test]
fn projection_scopes_side_chat_parents_and_agent_origins() {
    fn chat(id: &str, parent: Option<&str>) -> Chat {
        serde_json::from_value(json!({
            "id": id, "deviceId": "device", "spaceId": "space",
            "archived": false, "createdAt": "2026-10-27T00:00:00Z",
            "parentChatId": parent,
            "agentOrigin": parent.map(|chat| json!({ "chatId": chat })),
        }))
        .unwrap()
    }
    let engine = |key: EngineKey| EngineSnapshot {
        info: EngineInfo::current("device".into(), roboco_proto::WorkspaceScope::Local, None),
        key,
        state: EngineConnectionState::Connected,
        last_error: None,
        generation: 1,
        chats: vec![chat("parent", None), chat("side", Some("parent"))],
        spaces: vec![],
        devices: vec![],
        sessions: vec![],
        chats_loaded: true,
        spaces_loaded: true,
    };
    let remote = EngineKey("server".into());
    let projected = RegistrySnapshot {
        engines: vec![engine(EngineKey::local()), engine(remote.clone())],
        configuration_error: None,
    }
    .projected();
    let find = |id: &str| projected.chats.iter().find(|c| c.id == id).unwrap();
    // The local engine's rows stay raw.
    assert_eq!(find("side").parent_chat_id.as_deref(), Some("parent"));
    assert_eq!(find("side").agent_origin.as_ref().unwrap().chat_id, "parent");
    // The paired engine's side chat names its own scoped parent.
    let parent = ScopedId::encode(&remote, "parent");
    let side = find(&ScopedId::encode(&remote, "side"));
    assert_eq!(side.parent_chat_id.as_deref(), Some(parent.as_str()));
    assert_eq!(side.agent_origin.as_ref().unwrap().chat_id, parent);
    assert!(projected.chats.iter().any(|c| c.id == parent));
    assert!(find(&parent).parent_chat_id.is_none());
    assert!(find(&parent).agent_origin.is_none());
}

/// Transcript references that open other chats of the same engine — spawn
/// chips' subagent docs and completion notices' side chats — are scoped once
/// and stay stable when a cached (already scoped) copy is scoped again.
#[test]
fn transcript_scoping_covers_subagents_and_completion_notices() {
    use roboco_doc::{MessagePart, MessageRole, NoticeOutcome, NoticeSide, SessionMessageEntry};
    let remote = EngineKey("server".into());
    let notice = MessagePart::CompletionNotice {
        id: "n0".into(),
        sides: vec![NoticeSide {
            chat_id: "side".into(),
            title: "Side".into(),
            outcome: NoticeOutcome::Completed,
            detail: None,
        }],
    };
    let mut entries = vec![SessionMessageEntry {
        duration_ms: None,
        id: "e1".into(),
        role: MessageRole::Assistant,
        parts: vec![notice],
        created_at: 0,
        device_id: "device".into(),
        status: None,
        continuation_of: None,
        origin_chat_id: None,
        provider_turn: None,
    }];
    for _ in 0..2 {
        scope_transcript_entries(&remote, &mut entries);
        let MessagePart::CompletionNotice { sides, .. } = &entries[0].parts[0] else {
            panic!("notice part");
        };
        assert_eq!(sides[0].chat_id, ScopedId::encode(&remote, "side"));
        assert_eq!(entries[0].device_id, ScopedId::encode(&remote, "device"));
    }
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
        crate::cache::EngineCache::new(ui_dir.path())
            .load_rows(&key.0)
            .is_none()
    );
    reopened.shutdown().await;
    local.shutdown().await;
    remote.shutdown().await;
}

/// A phone registry has no local engine: `open_with(local: None)` holds
/// only paired engines, every projected id is scoped, reconnects ride the
/// saved credentials, and forgetting drops both rows and cache.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn remote_only_registry_pairs_reconnects_and_forgets() {
    let remote_dir = tempfile::tempdir().unwrap();
    let client_dir = tempfile::tempdir().unwrap();
    let remote = core(remote_dir.path());
    let registry = EngineRegistry::open_with(RegistryOptions {
        data_dir: client_dir.path().to_path_buf(),
        sessions: Arc::new(FileSessionStore::new(
            client_dir.path().join("paired-engines-v1.json"),
        )),
        local: None,
        resolvers: Vec::new(),
    })
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
    let code = store.create_code("remote-only test", 300).unwrap();
    let key = registry
        .pair(
            &format!("http://{address}/pair#token={}", code.credential),
            "Test phone",
        )
        .await
        .unwrap();
    // Only the paired engine exists — no local entry, so every projected id
    // must be scoped to it.
    wait_for(&registry, |snapshot| {
        let projected = snapshot.projected();
        snapshot.engines.len() == 1
            && projected.chats.len() == 1
            && projected.spaces.len() == 1
            && snapshot.engines.iter().all(|e| {
                e.chats_loaded && e.spaces_loaded && e.state == EngineConnectionState::Connected
            })
    })
    .await;
    let projected = registry.snapshot().projected();
    assert_eq!(
        ScopedId::parse(&projected.chats[0].id).unwrap().engine,
        key,
        "remote-only registries never leave ids raw"
    );
    assert_eq!(
        ScopedId::parse(&projected.spaces[0].id).unwrap().engine,
        key
    );
    let saved = FileSessionStore::new(client_dir.path().join("paired-engines-v1.json"))
        .load()
        .unwrap();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].key, key);
    drop(listener);
    remote.shutdown().await;
    drop(remote);
    wait_for(&registry, |snapshot| {
        snapshot
            .engines
            .iter()
            .any(|e| e.key == key && e.state == EngineConnectionState::Reconnecting)
    })
    .await;
    assert_eq!(
        registry.snapshot().projected().chats.len(),
        1,
        "cached history survives the outage"
    );
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
    let target = registry.target(&key).unwrap();
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_ok());
    registry.forget(&key).await.unwrap();
    assert!(registry.snapshot().engines.is_empty());
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_err());
    assert!(
        crate::cache::EngineCache::new(client_dir.path())
            .load_rows(&key.0)
            .is_none(),
        "forgetting the engine drops its cache"
    );
    assert!(
        FileSessionStore::new(client_dir.path().join("paired-engines-v1.json"))
            .load()
            .unwrap()
            .is_empty()
    );
    registry.shutdown().await;
    drop(listener);
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

/// A store implementation Android can back with Keystore: the on-disk JSON
/// stays frozen (ADR 0005), and a fixture in today's format must load and
/// re-save to the same value.
#[test]
fn file_session_store_reads_todays_file() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("paired-engines-v1.json");
    std::fs::write(&path, include_str!("fixtures/paired-engines-v1.json")).unwrap();
    let engines = FileSessionStore::new(path.clone()).load().unwrap();
    assert_eq!(engines.len(), 1);
    assert_eq!(
        engines[0].key,
        EngineKey("f47ac10b-58cc-4372-a567-0e02b2c3d479".into())
    );
    assert_eq!(engines[0].endpoint, "ws://127.0.0.1:27655/");
    assert_eq!(
        engines[0].credential,
        "fixture-session-credential-not-a-real-secret"
    );
    assert_eq!(engines[0].info.device_id, "fixture-engine-device");
    assert_eq!(
        engines[0].info.capabilities,
        vec![
            "composer-references-v1".to_string(),
            "message-queue-v1".to_string(),
            "web-client".to_string()
        ]
    );
    let out = dir.path().join("roundtrip.json");
    FileSessionStore::new(out.clone()).save(&engines).unwrap();
    let original: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    let rewritten: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&out).unwrap()).unwrap();
    assert_eq!(original, rewritten, "the JSON format is frozen");
    // And the file the engines came from still loads identically afterwards.
    assert_eq!(FileSessionStore::new(path).load().unwrap().len(), 1);
}

/// A `SessionStore` that is present but unreadable must never be written:
/// the registry parks in configuration-error mode, refuses to pair, and
/// leaves the stored bytes untouched for repair.
#[tokio::test]
async fn unreadable_store_never_writes() {
    struct Unreadable {
        saves: std::sync::atomic::AtomicUsize,
    }
    impl SessionStore for Unreadable {
        fn load(&self) -> anyhow::Result<Vec<SavedEngine>> {
            anyhow::bail!("store unreadable")
        }
        fn save(&self, _engines: &[SavedEngine]) -> anyhow::Result<()> {
            self.saves.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
    }
    let store = Arc::new(Unreadable {
        saves: std::sync::atomic::AtomicUsize::new(0),
    });
    let registry = EngineRegistry::open_with(RegistryOptions {
        data_dir: std::env::temp_dir(),
        sessions: store.clone(),
        local: None,
        resolvers: Vec::new(),
    })
    .await
    .unwrap();
    let snapshot = registry.snapshot();
    assert_eq!(snapshot.engines.len(), 0);
    assert_eq!(
        snapshot.configuration_error.as_deref(),
        Some(
            "Saved engine configuration could not be read. The file is preserved; pairing is unavailable until it is repaired."
        )
    );
    assert!(
        registry
            .pair("http://localhost/pair#token=code", "test")
            .await
            .is_err()
    );
    assert_eq!(
        store.saves.load(std::sync::atomic::Ordering::SeqCst),
        0,
        "an unreadable store must never be written"
    );
    registry.shutdown().await;
}

/// A service that fails every call while `down` is set — a stand-in for the
/// wedged-engine / dead-transport conditions that end a `drive` loop
/// mid-session.
struct WedgedEngine {
    engine: Arc<dyn roboco_rpc::RpcService>,
    down: Arc<std::sync::atomic::AtomicBool>,
}

#[async_trait::async_trait]
impl roboco_rpc::RpcService for WedgedEngine {
    async fn handle(
        &self,
        method: &str,
        params: serde_json::Value,
    ) -> Result<roboco_rpc::RpcReply, RpcError> {
        if self.down.load(std::sync::atomic::Ordering::SeqCst) {
            return Err(RpcError::Failed("engine wedged".into()));
        }
        self.engine.handle(method, params).await
    }
}

fn local_entry(
    snapshot: &RegistrySnapshot,
    state: EngineConnectionState,
) -> Option<&EngineSnapshot> {
    snapshot
        .engines
        .iter()
        .find(|e| e.key.is_local() && e.state == state)
}

/// A resolver claims a non-URL pairing input before `pair()` ever reaches
/// the URL parser, and its keepalive lives exactly as long as the entry:
/// dropped on `forget`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn resolver_claims_input_before_url_parsing() {
    /// Counts drops of the resolver's keepalive.
    struct KeepaliveCounter(Arc<std::sync::atomic::AtomicUsize>);
    impl Drop for KeepaliveCounter {
        fn drop(&mut self) {
            self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        }
    }
    struct FakeResolver {
        base: String,
        code: String,
        dropped: Arc<std::sync::atomic::AtomicUsize>,
    }
    impl PairingResolver for FakeResolver {
        fn resolve(
            &self,
            input: &str,
            _data_dir: &std::path::Path,
        ) -> Option<anyhow::Result<ResolvedPairing>> {
            if input != "fake:xyz" {
                return None;
            }
            Some(Ok(ResolvedPairing {
                base: self.base.clone(),
                code: self.code.clone(),
                keepalive: Some(Box::new(KeepaliveCounter(self.dropped.clone()))),
            }))
        }
    }
    let remote_dir = tempfile::tempdir().unwrap();
    let client_dir = tempfile::tempdir().unwrap();
    let remote = core(remote_dir.path());
    let listener = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        remote.rpc_service(),
        remote_dir.path(),
    )
    .await
    .unwrap();
    let store = PairingStore::open(remote_dir.path()).unwrap();
    let code = store.create_code("resolver test", 300).unwrap();
    let dropped = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let registry = EngineRegistry::open_with(RegistryOptions {
        data_dir: client_dir.path().to_path_buf(),
        sessions: Arc::new(FileSessionStore::new(
            client_dir.path().join("paired-engines-v1.json"),
        )),
        local: None,
        resolvers: vec![Arc::new(FakeResolver {
            base: format!("http://{}", listener.address),
            code: code.credential.clone(),
            dropped: dropped.clone(),
        })],
    })
    .await
    .unwrap();
    // "fake:xyz" is not a URL: without the resolver, pairing would reject it.
    let key = registry.pair("fake:xyz", "Test phone").await.unwrap();
    let target = registry.target(&key).unwrap();
    wait_for(&registry, |snapshot| {
        snapshot
            .engines
            .iter()
            .any(|e| e.key == key && e.state == EngineConnectionState::Connected)
    })
    .await;
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_ok());
    assert_eq!(
        dropped.load(std::sync::atomic::Ordering::SeqCst),
        0,
        "the keepalive lives while the entry does"
    );
    registry.forget(&key).await.unwrap();
    assert_eq!(
        dropped.load(std::sync::atomic::Ordering::SeqCst),
        1,
        "forgetting the entry drops the resolver's keepalive"
    );
    registry.shutdown().await;
    drop(listener);
    remote.shutdown().await;
}

/// The local entry must survive its transport failing mid-session. Without
/// the in-process reconnect factory, `supervise` had no reconnect path for
/// the local engine: the first `drive` failure ended supervision
/// permanently, every UI call failed as "Engine is offline; reconnecting"
/// while the engine itself kept running, and only an app restart recovered.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn in_process_engine_reconnects_after_its_transport_fails() {
    let engine_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let engine = core(engine_dir.path());
    let down = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let service: Arc<dyn roboco_rpc::RpcService> = Arc::new(WedgedEngine {
        engine: engine.rpc_service(),
        down: down.clone(),
    });
    let client = Arc::new(roboco_rpc::memory_client(service.clone()));
    let info: EngineInfo = client
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    // The factory mirrors InProcessEngine::reconnect: a fresh memory
    // transport over the same service per attempt.
    let minted = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let reconnect = {
        let service = service.clone();
        let minted = minted.clone();
        Reconnect::Factory(Arc::new(move || {
            minted.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Arc::new(roboco_rpc::memory_client(service.clone()))
        }))
    };
    let registry = EngineRegistry::open(
        ui_dir.path().join("paired-engines.json"),
        info,
        client,
        Some(reconnect),
    )
    .await
    .unwrap();
    wait_for(&registry, |snapshot| {
        local_entry(snapshot, EngineConnectionState::Connected).is_some()
    })
    .await;

    // Wedge the engine: every call fails, so the 5s health check in `drive`
    // drops the connection.
    down.store(true, std::sync::atomic::Ordering::SeqCst);
    wait_for(&registry, |snapshot| {
        local_entry(snapshot, EngineConnectionState::Reconnecting).is_some()
    })
    .await;
    // The entry answers offline while wedged — the symptom users saw.
    assert!(
        registry
            .local()
            .call(methods::ENGINE_INFO, json!({}))
            .await
            .is_err()
    );

    // Unwedge: supervision mints a fresh transport and recovers.
    down.store(false, std::sync::atomic::Ordering::SeqCst);
    wait_for(&registry, |snapshot| {
        local_entry(snapshot, EngineConnectionState::Connected).is_some_and(|e| e.generation >= 2)
    })
    .await;
    assert!(
        registry
            .local()
            .call(methods::ENGINE_INFO, json!({}))
            .await
            .is_ok()
    );
    assert!(minted.load(std::sync::atomic::Ordering::SeqCst) >= 1);
    registry.shutdown().await;
    engine.shutdown().await;
}
