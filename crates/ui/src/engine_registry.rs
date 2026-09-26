//! Paired engine connections and engine-scoped client identity. No GPUI dependency.
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use roboco_proto::{Chat, Device, EngineInfo, Session, Space};
use roboco_rpc::{RpcClient, RpcError, methods};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{Arc, Mutex, Weak},
    time::Duration,
};
use tokio::sync::{mpsc, watch};

const PREFIX: &str = "engine:v1:";
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct EngineKey(pub String);
impl EngineKey {
    pub fn local() -> Self {
        Self("local".into())
    }
    pub fn is_local(&self) -> bool {
        *self == Self::local()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScopedId {
    pub engine: EngineKey,
    pub raw_id: String,
}
impl ScopedId {
    pub fn is_scoped(id: &str) -> bool {
        id.starts_with(PREFIX)
    }
    pub fn encode(engine: &EngineKey, raw_id: &str) -> String {
        if engine.is_local() && !raw_id.starts_with(PREFIX) {
            return raw_id.into();
        }
        format!(
            "{PREFIX}{}",
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(&(engine, raw_id)).expect("string tuple"))
        )
    }
    pub fn parse(id: &str) -> Result<Self, RpcError> {
        let Some(encoded) = id.strip_prefix(PREFIX) else {
            return Ok(Self {
                engine: EngineKey::local(),
                raw_id: id.into(),
            });
        };
        let decoded = URL_SAFE_NO_PAD
            .decode(encoded)
            .map_err(|_| RpcError::Failed("Invalid engine-scoped identity".into()))?;
        let (engine, raw_id): (EngineKey, String) = serde_json::from_slice(&decoded)
            .map_err(|_| RpcError::Failed("Invalid engine-scoped identity".into()))?;
        if engine.0.is_empty() {
            return Err(RpcError::Failed("Missing engine identity".into()));
        }
        Ok(Self { engine, raw_id })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EngineConnectionState {
    Connected,
    Reconnecting,
    Off,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EngineSnapshot {
    pub key: EngineKey,
    pub info: EngineInfo,
    pub state: EngineConnectionState,
    pub last_error: Option<String>,
    pub generation: u64,
    pub chats: Vec<Chat>,
    pub spaces: Vec<Space>,
    pub devices: Vec<Device>,
    pub sessions: Vec<Session>,
    pub chats_loaded: bool,
    pub spaces_loaded: bool,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RegistrySnapshot {
    pub engines: Vec<EngineSnapshot>,
    pub configuration_error: Option<String>,
}
#[derive(Default)]
pub struct ProjectedSnapshot {
    pub chats: Vec<Chat>,
    pub spaces: Vec<Space>,
    pub devices: Vec<Device>,
    pub sessions: Vec<Session>,
}
impl RegistrySnapshot {
    pub fn projected(&self) -> ProjectedSnapshot {
        let mut out = ProjectedSnapshot::default();
        for engine in &self.engines {
            let scope = |id: &str| ScopedId::encode(&engine.key, id);
            for mut chat in engine.chats.clone() {
                chat.id = scope(&chat.id);
                chat.device_id = scope(&chat.device_id);
                chat.space_id = chat.space_id.map(|id| scope(&id));
                chat.checkout_id = chat.checkout_id.map(|id| scope(&id));
                if let Some(context) = &mut chat.source_context {
                    context.checkout_id = scope(&context.checkout_id);
                }
                out.chats.push(chat);
            }
            for mut space in engine.spaces.clone() {
                space.id = scope(&space.id);
                space.device_id = scope(&space.device_id);
                space.checkout_id = space.checkout_id.map(|id| scope(&id));
                out.spaces.push(space);
            }
            let devices = if engine.devices.is_empty() && !engine.key.is_local() {
                vec![Device {
                    id: engine.info.device_id.clone(),
                    name: "Remote engine".into(),
                    platform: String::new(),
                    last_seen_at: None,
                    created_at: None,
                    version: None,
                    cursor_sdk_version: None,
                    capabilities: engine.info.capabilities.clone(),
                }]
            } else {
                engine.devices.clone()
            };
            for mut device in devices {
                device.id = scope(&device.id);
                // Stale cached timestamps must never imply a live connection.
                if engine.state != EngineConnectionState::Connected {
                    device.last_seen_at = None;
                }
                out.devices.push(device);
            }
            for mut session in engine.sessions.clone() {
                session.chat_id = scope(&session.chat_id);
                session.device_id = scope(&session.device_id);
                out.sessions.push(session);
            }
        }
        out
    }
}

/// Project only transcript references which leave their owning chat. Message and
/// tool IDs stay document-local; text, tool payloads and sidecar paths are untouched.
pub fn scope_transcript_entries(key: &EngineKey, entries: &mut [roboco_doc::SessionMessageEntry]) {
    fn scope(key: &EngineKey, id: &mut String) {
        if ScopedId::is_scoped(id) && ScopedId::parse(id).is_ok_and(|id| &id.engine == key) {
            return;
        }
        *id = ScopedId::encode(key, id);
    }
    for entry in entries {
        scope(key, &mut entry.device_id);
        for part in &mut entry.parts {
            if let roboco_doc::MessagePart::Tool {
                subagent_ref: Some(reference),
                ..
            } = part
            {
                scope(key, reference);
            }
        }
    }
}

pub fn scope_transcript_frame(key: &EngineKey, update: &mut roboco_doc::TranscriptUpdate) {
    match &mut update.frame {
        roboco_doc::TranscriptFrame::Reset { reset } => scope_transcript_entries(key, reset),
        roboco_doc::TranscriptFrame::Delta { upsert, .. } => {
            for upsert in upsert {
                scope_transcript_entries(key, std::slice::from_mut(&mut upsert.entry));
            }
        }
    }
}

// Session credentials are deliberately excluded from Debug and public snapshots.
#[derive(Clone, Serialize, Deserialize)]
struct SavedEngine {
    key: EngineKey,
    endpoint: String,
    credential: String,
    info: EngineInfo,
}
#[derive(Default, Serialize, Deserialize)]
struct SavedRegistry {
    engines: Vec<SavedEngine>,
}
struct Entry {
    snapshot: EngineSnapshot,
    client: Option<Arc<RpcClient>>,
    saved: Option<SavedEngine>,
}
struct RegistryState {
    entries: BTreeMap<EngineKey, Entry>,
    tasks: BTreeMap<EngineKey, tokio::task::JoinHandle<()>>,
}
/// Command for the registry's single cache-writer task. Writes flow through
/// one FIFO channel so the newest rows always win the rename: concurrent
/// `save_rows` calls can persist out of order, and a stale frame would then
/// overwrite the latest history. `Flush` rides the same queue, letting
/// `forget`/`shutdown` prove every earlier write hit disk before they touch
/// the cache directory (an orphaned `spawn_blocking` write otherwise races
/// `remove_dir_all` with ENOTEMPTY).
enum CacheCommand {
    Save {
        key: String,
        rows: crate::engine_cache::CachedRows,
    },
    Flush(tokio::sync::oneshot::Sender<()>),
}
struct Inner {
    path: PathBuf,
    configuration_error: Option<String>,
    runtime: tokio::runtime::Handle,
    state: Mutex<RegistryState>,
    updates: watch::Sender<RegistrySnapshot>,
    changes: tokio::sync::Mutex<()>,
    cache: crate::engine_cache::EngineCache,
    cache_tx: tokio::sync::mpsc::UnboundedSender<CacheCommand>,
}
impl Drop for Inner {
    fn drop(&mut self) {
        for task in self
            .state
            .get_mut()
            .unwrap_or_else(|e| e.into_inner())
            .tasks
            .values()
        {
            task.abort();
        }
    }
}
#[derive(Clone)]
pub struct EngineRegistry {
    inner: Arc<Inner>,
}
#[derive(Clone)]
pub struct EngineTarget {
    registry: Weak<Inner>,
    key: EngineKey,
    info: EngineInfo,
}
impl EngineTarget {
    pub fn key(&self) -> &EngineKey {
        &self.key
    }
    pub fn engine_info(&self) -> EngineInfo {
        self.info.clone()
    }
    pub fn connection_state(&self) -> EngineConnectionState {
        self.registry
            .upgrade()
            .and_then(|inner| {
                lock(&inner.state)
                    .entries
                    .get(&self.key)
                    .map(|e| e.snapshot.state.clone())
            })
            .unwrap_or(EngineConnectionState::Off)
    }
    pub fn is_connected(&self) -> bool {
        self.live().is_ok()
    }
    fn live(&self) -> Result<Arc<RpcClient>, RpcError> {
        let inner = self.registry.upgrade().ok_or(RpcError::Closed)?;
        let state = lock(&inner.state);
        let entry = state
            .entries
            .get(&self.key)
            .ok_or_else(|| RpcError::Failed("Engine was forgotten".into()))?;
        entry
            .client
            .clone()
            .filter(|client| !client.is_closed())
            .ok_or_else(|| RpcError::Transport("Engine is offline; reconnecting".into()))
    }
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, RpcError> {
        let params = crate::request_routing::wire_params(&self.key, method, params)?;
        let client = self.live()?;
        let runtime = self
            .registry
            .upgrade()
            .ok_or(RpcError::Closed)?
            .runtime
            .clone();
        let method = method.to_owned();
        let timeout = call_deadline(&method);
        runtime
            .spawn(async move {
                tokio::time::timeout(timeout, client.call(&method, params))
                    .await
                    .map_err(|_| RpcError::Transport("Engine request timed out".into()))?
            })
            .await
            .map_err(|_| RpcError::Closed)?
    }

    pub async fn call_as<T: DeserializeOwned>(
        &self,
        method: &str,
        params: Value,
    ) -> Result<T, RpcError> {
        serde_json::from_value(self.call(method, params).await?)
            .map_err(|error| RpcError::Failed(error.to_string()))
    }
    pub async fn subscribe(
        &self,
        method: &str,
        params: Value,
    ) -> Result<mpsc::Receiver<Value>, RpcError> {
        let params = crate::request_routing::wire_params(&self.key, method, params)?;
        self.live()?.subscribe(method, params).await
    }
    pub async fn subscribe_checked(
        &self,
        method: &str,
        params: Value,
    ) -> Result<roboco_rpc::RpcSubscription, RpcError> {
        let params = crate::request_routing::wire_params(&self.key, method, params)?;
        self.live()?.subscribe_checked(method, params).await
    }
}

/// Reply deadline for a unary engine call. Adapter catalog discovery
/// (ListModels/ListCommands) may cold-boot a CLI for up to ~90s, so it gets
/// the adapter discovery budget plus shutdown overhead; network-bound git
/// and update methods get a long leash; everything else is interactive and
/// must fail fast. Mirrors the tiered forward deadlines the engine-side
/// relay forwarder uses upstream — Roboco is engine-local, so this client
/// deadline is the only one that can cut remote discovery off.
fn call_deadline(method: &str) -> Duration {
    if method.contains("Clone") || method.contains("Fetch") {
        return Duration::from_secs(900);
    }
    if method == methods::LIST_MODELS || method == methods::LIST_COMMANDS {
        return Duration::from_secs(100);
    }
    Duration::from_secs(30)
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}
fn entry(key: EngineKey, info: EngineInfo, saved: Option<SavedEngine>) -> Entry {
    Entry {
        snapshot: EngineSnapshot {
            key,
            info,
            state: EngineConnectionState::Reconnecting,
            last_error: None,
            generation: 0,
            chats: vec![],
            spaces: vec![],
            devices: vec![],
            sessions: vec![],
            chats_loaded: false,
            spaces_loaded: false,
        },
        client: None,
        saved,
    }
}
impl EngineRegistry {
    /// Adopt the local bootstrap's client; remote entries reconnect independently.
    pub async fn open(
        path: PathBuf,
        local_info: EngineInfo,
        client: Arc<RpcClient>,
        reconnect_url: Option<String>,
    ) -> anyhow::Result<Self> {
        let loaded = match std::fs::read(&path) {
            Ok(bytes) => {
                serde_json::from_slice::<SavedRegistry>(&bytes).map_err(anyhow::Error::from)
            }
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(SavedRegistry::default()),
            Err(err) => Err(err.into()),
        };
        let (saved, mut configuration_error) = match loaded {
            Ok(saved) => (saved, None),
            Err(_) => (SavedRegistry::default(), Some("Saved engine configuration could not be read. The file is preserved; pairing is unavailable until it is repaired.".to_string())),
        };
        let (updates, _) = watch::channel(RegistrySnapshot::default());
        let mut entries = BTreeMap::new();
        entries.insert(
            EngineKey::local(),
            entry(EngineKey::local(), local_info, None),
        );
        for saved in saved.engines {
            if saved.key.is_local() || saved.key.0.is_empty() || entries.contains_key(&saved.key) {
                configuration_error = Some("Saved engine configuration contains invalid identities. The file is preserved; pairing is unavailable until it is repaired.".into());
                continue;
            }
            entries.insert(
                saved.key.clone(),
                entry(saved.key.clone(), saved.info.clone(), Some(saved)),
            );
        }
        let cache = crate::engine_cache::EngineCache::new(
            path.parent().unwrap_or(std::path::Path::new(".")),
        );
        for (key, entry) in &mut entries {
            if let Some(rows) = cache.load_rows(&key.0) {
                entry.snapshot.chats = rows.chats;
                entry.snapshot.spaces = rows.spaces;
                entry.snapshot.devices = rows.devices;
                entry.snapshot.sessions = rows.sessions;
                entry.snapshot.chats_loaded = true;
                entry.snapshot.spaces_loaded = true;
            }
        }
        let (cache_tx, mut cache_rx) = tokio::sync::mpsc::unbounded_channel::<CacheCommand>();
        let writer_cache = cache.clone();
        tokio::task::spawn(async move {
            while let Some(command) = cache_rx.recv().await {
                match command {
                    CacheCommand::Save { key, rows } => {
                        let cache = writer_cache.clone();
                        if let Ok(Err(error)) =
                            tokio::task::spawn_blocking(move || cache.save_rows(&key, &rows)).await
                        {
                            tracing::warn!(%error, "Could not save engine history");
                        }
                    }
                    CacheCommand::Flush(done) => {
                        let _ = done.send(());
                    }
                }
            }
        });
        let registry = Self {
            inner: Arc::new(Inner {
                path,
                configuration_error,
                runtime: tokio::runtime::Handle::current(),
                state: Mutex::new(RegistryState {
                    entries,
                    tasks: BTreeMap::new(),
                }),
                updates,
                changes: tokio::sync::Mutex::new(()),
                cache,
                cache_tx,
            }),
        };
        registry.publish();
        registry.spawn(EngineKey::local(), Some(client), reconnect_url);
        let remotes: Vec<_> = lock(&registry.inner.state)
            .entries
            .keys()
            .filter(|key| !key.is_local())
            .cloned()
            .collect();
        for key in remotes {
            registry.spawn(key, None, None);
        }
        Ok(registry)
    }
    pub fn watch(&self) -> watch::Receiver<RegistrySnapshot> {
        self.inner.updates.subscribe()
    }

    /// A single connected local entry over a raw client — no watches, no
    /// reconnects, no cache writes. For state tests that exercise request
    /// dispatch through the registry (the composer's interrupt path): every
    /// frame the client writes lands on the client's own channel.
    #[cfg(test)]
    pub(crate) fn test_local(info: EngineInfo, client: Arc<RpcClient>) -> Self {
        let (updates, _) = watch::channel(RegistrySnapshot::default());
        let mut entries = BTreeMap::new();
        let mut local = entry(EngineKey::local(), info, None);
        local.snapshot.state = EngineConnectionState::Connected;
        local.client = Some(client);
        entries.insert(EngineKey::local(), local);
        // The receiver is dropped on purpose: sends fail fast, and the
        // guard in `flush_cache_writes` already tolerates a closed channel.
        let (cache_tx, _) = tokio::sync::mpsc::unbounded_channel::<CacheCommand>();
        Self {
            inner: Arc::new(Inner {
                path: std::env::temp_dir().join("roboco-test-registry-unused.json"),
                configuration_error: None,
                runtime: tokio::runtime::Handle::current(),
                state: Mutex::new(RegistryState {
                    entries,
                    tasks: BTreeMap::new(),
                }),
                updates,
                changes: tokio::sync::Mutex::new(()),
                cache: crate::engine_cache::EngineCache::new(&std::env::temp_dir()),
                cache_tx,
            }),
        }
    }
    pub fn snapshot(&self) -> RegistrySnapshot {
        self.inner.updates.borrow().clone()
    }
    pub fn local(&self) -> EngineTarget {
        self.target(&EngineKey::local())
            .expect("local engine entry")
    }
    pub fn target(&self, key: &EngineKey) -> Result<EngineTarget, RpcError> {
        let info = lock(&self.inner.state)
            .entries
            .get(key)
            .map(|entry| entry.snapshot.info.clone())
            .ok_or_else(|| RpcError::Failed("Unknown engine".into()))?;
        Ok(EngineTarget {
            registry: Arc::downgrade(&self.inner),
            key: key.clone(),
            info,
        })
    }
    pub fn resolve(&self, id: &str) -> Result<(EngineTarget, String), RpcError> {
        let scoped = ScopedId::parse(id)?;
        Ok((self.target(&scoped.engine)?, scoped.raw_id))
    }
    pub async fn pair(&self, pairing_url: &str, label: &str) -> anyhow::Result<EngineKey> {
        anyhow::ensure!(
            self.inner.configuration_error.is_none(),
            "Saved engine configuration needs repair before pairing"
        );
        let (base, code) = parse_pairing_url(pairing_url)?;
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(15))
            .build()?;
        let response = http
            .post(format!("{base}/pairing/redeem"))
            .bearer_auth(&code)
            .json(&json!({"label":label}))
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("Could not reach the pairing endpoint"))?;
        anyhow::ensure!(
            response.status().is_success(),
            "Pairing URL was refused or has expired"
        );
        #[derive(Deserialize)]
        struct Grant {
            credential: String,
        }
        let grant: Grant = response
            .json()
            .await
            .map_err(|_| anyhow::anyhow!("Invalid pairing response"))?;
        let endpoint = base.replacen("http", "ws", 1);
        let client =
            Arc::new(roboco_rpc::connect_ws_authenticated(&endpoint, &grant.credential).await?);
        let info: EngineInfo = tokio::time::timeout(
            Duration::from_secs(10),
            client.call_as(methods::ENGINE_INFO, json!({})),
        )
        .await??;
        let _change = self.inner.changes.lock().await;
        let key = EngineKey(uuid::Uuid::new_v4().to_string());
        let saved = SavedEngine {
            key: key.clone(),
            endpoint,
            credential: grant.credential,
            info: info.clone(),
        };
        lock(&self.inner.state)
            .entries
            .insert(key.clone(), entry(key.clone(), info, Some(saved)));
        if let Err(err) = self.persist() {
            lock(&self.inner.state).entries.remove(&key);
            return Err(err);
        }
        self.publish();
        self.spawn(key.clone(), Some(client), None);
        Ok(key)
    }
    pub async fn forget(&self, key: &EngineKey) -> anyhow::Result<()> {
        anyhow::ensure!(!key.is_local(), "The local engine cannot be forgotten");
        let _change = self.inner.changes.lock().await;
        let removed = lock(&self.inner.state).entries.remove(key);
        if let Err(err) = self.persist() {
            if let Some(entry) = removed {
                lock(&self.inner.state).entries.insert(key.clone(), entry);
            }
            return Err(err);
        }
        let task = lock(&self.inner.state).tasks.remove(key);
        if let Some(task) = task {
            task.abort();
            let _ = task.await;
        }
        // No new write can register once the engine task is cancelled and the
        // `changes` lock is held; joining the drained writes keeps an
        // in-flight save from recreating the directory being removed.
        self.flush_cache_writes().await;
        let cache = self.inner.cache.clone();
        let key = key.0.clone();
        tokio::task::spawn_blocking(move || cache.forget_engine(&key)).await??;
        self.publish();
        Ok(())
    }
    pub async fn shutdown(&self) {
        let tasks = {
            let mut state = lock(&self.inner.state);
            for entry in state.entries.values_mut() {
                entry.client = None;
                entry.snapshot.state = EngineConnectionState::Off;
            }
            std::mem::take(&mut state.tasks)
        };
        for (_, task) in tasks {
            task.abort();
            let _ = task.await;
        }
        self.flush_cache_writes().await;
        self.publish();
    }
    /// Prove every cache write queued before this call has hit disk. The flush
    /// marker rides the writer's FIFO channel behind those writes; callers
    /// hold the `changes` lock (or have cancelled every engine task), which is
    /// what stops a new save racing the drain.
    async fn flush_cache_writes(&self) {
        let (done, receipt) = tokio::sync::oneshot::channel();
        if self.inner.cache_tx.send(CacheCommand::Flush(done)).is_ok() {
            let _ = receipt.await;
        }
    }
    fn persist(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.inner.configuration_error.is_none(),
            "Saved engine configuration needs repair"
        );
        let saved = SavedRegistry {
            engines: lock(&self.inner.state)
                .entries
                .values()
                .filter_map(|e| e.saved.clone())
                .collect(),
        };
        let parent = self
            .inner
            .path
            .parent()
            .ok_or_else(|| anyhow::anyhow!("Invalid registry path"))?;
        std::fs::create_dir_all(parent)?;
        let mut temp = tempfile::NamedTempFile::new_in(parent)?;
        use std::io::Write;
        temp.write_all(&serde_json::to_vec(&saved)?)?;
        temp.as_file().sync_all()?;
        temp.persist(&self.inner.path).map_err(|e| e.error)?;
        Ok(())
    }
    fn publish(&self) {
        publish(&self.inner);
    }
    fn spawn(&self, key: EngineKey, client: Option<Arc<RpcClient>>, local_url: Option<String>) {
        let weak = Arc::downgrade(&self.inner);
        let task_key = key.clone();
        let task = tokio::spawn(async move {
            supervise(weak, task_key, client, local_url).await;
        });
        if let Some(old) = lock(&self.inner.state).tasks.insert(key, task) {
            old.abort();
        }
    }
}
fn publish(inner: &Inner) {
    let engines = lock(&inner.state)
        .entries
        .values()
        .map(|e| e.snapshot.clone())
        .collect();
    inner.updates.send_replace(RegistrySnapshot {
        engines,
        configuration_error: inner.configuration_error.clone(),
    });
}
fn update(weak: &Weak<Inner>, key: &EngineKey, f: impl FnOnce(&mut Entry)) -> bool {
    let Some(inner) = weak.upgrade() else {
        return false;
    };
    {
        let mut state = lock(&inner.state);
        let Some(entry) = state.entries.get_mut(key) else {
            return false;
        };
        f(entry);
    }
    publish(&inner);
    true
}
fn parse_pairing_url(input: &str) -> anyhow::Result<(String, String)> {
    let mut url =
        reqwest::Url::parse(input.trim()).map_err(|_| anyhow::anyhow!("Invalid pairing URL"))?;
    anyhow::ensure!(
        matches!(url.scheme(), "http" | "https")
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none(),
        "Pairing URL must use HTTP or HTTPS without a query or user information"
    );
    let code = url
        .fragment()
        .and_then(|s| s.strip_prefix("token="))
        .filter(|s| {
            !s.is_empty()
                && s.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
        .ok_or_else(|| anyhow::anyhow!("Pairing URL is missing its fragment credential"))?
        .to_string();
    let path = url
        .path()
        .strip_suffix("/pair")
        .ok_or_else(|| anyhow::anyhow!("Invalid pairing URL path"))?
        .to_string();
    url.set_path(&path);
    url.set_fragment(None);
    Ok((url.as_str().trim_end_matches('/').to_string(), code))
}
async fn supervise(
    weak: Weak<Inner>,
    key: EngineKey,
    mut initial: Option<Arc<RpcClient>>,
    local_url: Option<String>,
) {
    let mut delay = Duration::from_millis(500);
    loop {
        let Some((saved, expected)) = weak.upgrade().and_then(|inner| {
            lock(&inner.state)
                .entries
                .get(&key)
                .map(|e| (e.saved.clone(), e.snapshot.info.device_id.clone()))
        }) else {
            return;
        };
        let result = if let Some(client) = initial.take() {
            Ok(client)
        } else if let Some(saved) = &saved {
            roboco_rpc::connect_ws_authenticated(&saved.endpoint, &saved.credential)
                .await
                .map(Arc::new)
        } else if let Some(url) = &local_url {
            roboco_rpc::connect_ws(url).await.map(Arc::new)
        } else {
            return;
        };
        let started = std::time::Instant::now();
        let result = match result {
            Ok(client) => drive(&weak, &key, client, &expected).await,
            Err(err) => Err(err),
        };
        let error = result
            .err()
            .map(|e| e.to_string())
            .unwrap_or_else(|| "Engine connection closed".into());
        let refused =
            error.contains("401") || error.contains("403") || error.contains("identity changed");
        if !update(&weak, &key, |entry| {
            entry.client = None;
            entry.snapshot.state = if refused {
                EngineConnectionState::Off
            } else {
                EngineConnectionState::Reconnecting
            };
            entry.snapshot.last_error = Some(error);
        }) {
            return;
        }
        if refused {
            return;
        }
        if started.elapsed() > Duration::from_secs(10) {
            delay = Duration::from_millis(500);
        }
        let jitter = Duration::from_millis(u64::from(uuid::Uuid::new_v4().as_bytes()[0]));
        tokio::time::sleep(delay + jitter).await;
        delay = (delay * 2).min(Duration::from_secs(15));
    }
}
async fn drive(
    weak: &Weak<Inner>,
    key: &EngineKey,
    client: Arc<RpcClient>,
    expected: &str,
) -> Result<(), RpcError> {
    let info: EngineInfo = tokio::time::timeout(
        Duration::from_secs(10),
        client.call_as(methods::ENGINE_INFO, json!({})),
    )
    .await
    .map_err(|_| RpcError::Transport("Engine identity timed out".into()))??;
    if info.device_id != expected {
        return Err(RpcError::Failed(
            "Engine identity changed; pair again".into(),
        ));
    }
    let (mut chats, mut spaces, mut devices, mut sessions) =
        tokio::time::timeout(Duration::from_secs(15), async {
            let chats = client
                .subscribe_checked(methods::WATCH_CHATS, json!({}))
                .await?;
            let spaces = client
                .subscribe_checked(methods::WATCH_SPACES, json!({}))
                .await?;
            let devices = client
                .subscribe_checked(methods::WATCH_DEVICES, json!({}))
                .await?;
            let sessions = client
                .subscribe_checked(methods::WATCH_SESSIONS, json!({}))
                .await?;
            Ok::<_, RpcError>((chats, spaces, devices, sessions))
        })
        .await
        .map_err(|_| RpcError::Transport("Engine subscriptions timed out".into()))??;
    if !update(weak, key, |e| {
        e.client = Some(client.clone());
        e.snapshot.info = info;
        e.snapshot.state = EngineConnectionState::Connected;
        e.snapshot.last_error = None;
        e.snapshot.generation += 1;
    }) {
        return Ok(());
    }
    let mut health = tokio::time::interval(Duration::from_secs(5));
    loop {
        tokio::select! {
            _ = health.tick() => {
                let info: EngineInfo = tokio::time::timeout(Duration::from_secs(5), client.call_as(methods::ENGINE_INFO, json!({}))).await.map_err(|_| RpcError::Transport("Engine health check timed out".into()))??;
                if info.device_id != expected { return Err(RpcError::Failed("Engine identity changed; pair again".into())); }
                continue;
            }
            _ = client.closed() => return Err(RpcError::Closed),
            frame = chats.recv() => { let rows = decode(frame)?; if !update(weak,key,|e| {e.snapshot.chats=rows; e.snapshot.chats_loaded=true;}) {return Ok(());} }
            frame = spaces.recv() => { let rows = decode(frame)?; if !update(weak,key,|e| {e.snapshot.spaces=rows; e.snapshot.spaces_loaded=true;}) {return Ok(());} }
            frame = devices.recv() => { let rows = decode(frame)?; if !update(weak,key,|e| e.snapshot.devices=rows) {return Ok(());} }
            frame = sessions.recv() => { let rows = decode(frame)?; if !update(weak,key,|e| e.snapshot.sessions=rows) {return Ok(());} }
        }
        cache_rows(weak, key).await;
    }
}
async fn cache_rows(weak: &Weak<Inner>, key: &EngineKey) {
    let Some(inner) = weak.upgrade() else {
        return;
    };
    let _change = inner.changes.lock().await;
    let Some(rows) =
        lock(&inner.state)
            .entries
            .get(key)
            .map(|entry| crate::engine_cache::CachedRows {
                chats: entry.snapshot.chats.clone(),
                spaces: entry.snapshot.spaces.clone(),
                devices: entry.snapshot.devices.clone(),
                sessions: entry.snapshot.sessions.clone(),
            })
    else {
        return;
    };
    // The single writer applies saves in submission order, so the newest
    // frame always wins the rename onto rows.json; sending under the
    // `changes` lock keeps the queue ordered against forget/shutdown.
    let _ = inner
        .cache_tx
        .send(CacheCommand::Save {
            key: key.0.clone(),
            rows,
        });
}
fn decode<T: DeserializeOwned>(value: Option<Value>) -> Result<T, RpcError> {
    serde_json::from_value(value.ok_or(RpcError::Closed)?)
        .map_err(|_| RpcError::Failed("Invalid engine state frame".into()))
}

#[cfg(test)]
mod tests;
