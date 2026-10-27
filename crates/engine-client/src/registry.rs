//! Paired engine connections and engine-scoped client identity. No GPUI dependency.
use crate::{
    FileSessionStore, LocalEngine, PairingResolver, RegistryOptions, ResolvedPairing, SessionStore,
};
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

use crate::rooms::{RoomGrant, RoomMemberSession};

/// How [`EngineRegistry::join_room_member`] (and pairing) placed an engine
/// in the registry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Adoption {
    /// A new entry under a fresh key.
    New,
    /// The registry already held this engine (same device id): its existing
    /// entry was reattached with the fresh credential — never a duplicate.
    Reattached,
}

struct Adopted {
    key: EngineKey,
    adoption: Adoption,
}

/// What an adoption does with the entry's saved route (the secret a
/// resolver rebuilds a dead transport from on the next start).
enum RouteUpdate {
    /// Keep the route already saved for a reattached engine (none for a new
    /// one): the caller's endpoint says nothing about how to rebuild it.
    Keep,
    /// Save exactly this route: a pairing always knows its transport — a
    /// LAN pairing has none, which clears a stale Tailcat route.
    Set(Option<String>),
}

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

/// How the supervise loop rebuilds a dead connection for the local registry
/// entry: dial a URL, or mint a fresh in-process client over the engine's
/// shared service. Remote entries reconnect through their saved credentials;
/// this covers the rest.
///
/// Without the in-process factory, a single transport failure (an
/// `ENGINE_INFO` health-check timeout, a dead memory-transport server task)
/// ended supervision for the embedded engine permanently: `supervise` had
/// nothing to reconnect with, every UI call failed as "Engine is offline;
/// reconnecting" forever, and only an app restart recovered — while the
/// engine itself kept running in-process the whole time.
#[derive(Clone)]
pub enum Reconnect {
    Url(String),
    /// Infallible: `memory_client` only needs the service and a runtime
    /// context (supervise runs inside one).
    Factory(Arc<dyn Fn() -> Arc<RpcClient> + Send + Sync>),
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
    /// Whether two snapshots differ in what views render from them directly:
    /// engine identity, connection state, errors, and load flags — not the
    /// row payloads (chats/spaces/devices/sessions), whose visible
    /// presentation flows through `AppState`'s apply_* reducers with their
    /// own change detection. Liveness heartbeats churn payload timestamps
    /// without touching anything here.
    pub fn observable_differs(&self, other: &Self) -> bool {
        self.configuration_error != other.configuration_error
            || self.engines.len() != other.engines.len()
            || self.engines.iter().zip(&other.engines).any(|(a, b)| {
                a.key != b.key
                    || a.info != b.info
                    || a.state != b.state
                    || a.last_error != b.last_error
                    || a.generation != b.generation
                    || a.chats_loaded != b.chats_loaded
                    || a.spaces_loaded != b.spaces_loaded
            })
    }

    pub fn projected(&self) -> ProjectedSnapshot {
        let mut out = ProjectedSnapshot::default();
        for engine in &self.engines {
            let scope = |id: &str| ScopedId::encode(&engine.key, id);
            for mut chat in engine.chats.clone() {
                scope_chat(&engine.key, &mut chat);
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
                    // `EngineInfo.version` is authoritative on the first round
                    // trip; the Devices page can show it before the devices
                    // stream arrives.
                    version: engine.info.version.clone(),
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

/// Project one chat row of engine `key` into the client's id space: its own
/// id and every id naming another row of the same engine — device, space,
/// checkouts, the parent a side chat hangs under, and the chat whose agent
/// created it. Rows from the same engine must keep comparing equal to each
/// other once scoped (the Android core's projection does the same).
pub fn scope_chat(key: &EngineKey, chat: &mut Chat) {
    let scope = |id: &str| ScopedId::encode(key, id);
    chat.id = scope(&chat.id);
    chat.device_id = scope(&chat.device_id);
    chat.space_id = chat.space_id.as_deref().map(scope);
    chat.checkout_id = chat.checkout_id.as_deref().map(scope);
    if let Some(context) = &mut chat.source_context {
        context.checkout_id = scope(&context.checkout_id);
    }
    chat.parent_chat_id = chat.parent_chat_id.as_deref().map(scope);
    if let Some(origin) = &mut chat.agent_origin {
        origin.chat_id = scope(&origin.chat_id);
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
            match part {
                roboco_doc::MessagePart::Tool {
                    subagent_ref: Some(reference),
                    ..
                } => scope(key, reference),
                // A completion notice opens the side chats it reports; they
                // live on the parent's engine.
                roboco_doc::MessagePart::CompletionNotice { sides, .. } => {
                    for side in sides {
                        scope(key, &mut side.chat_id);
                    }
                }
                _ => {}
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
pub struct SavedEngine {
    pub key: EngineKey,
    pub endpoint: String,
    pub credential: String,
    pub info: EngineInfo,
    /// Set when a pairing resolver's transport carries the connection (a
    /// Tailcat forwarder): `endpoint` is then that transport's loopback URL,
    /// which dies with the process, and the resolver rebuilds it from this
    /// on the next start. A secret, like `credential`. Additive (ADR 0005).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub route: Option<String>,
}
#[derive(Default, Serialize, Deserialize)]
pub(crate) struct SavedRegistry {
    pub(crate) engines: Vec<SavedEngine>,
}
struct Entry {
    snapshot: EngineSnapshot,
    client: Option<Arc<RpcClient>>,
    saved: Option<SavedEngine>,
    /// Keeps a pairing forwarder (a Tailcat `connect` client) alive for this
    /// registry entry; dropped when the entry is forgotten, or when it is
    /// found dead so the route is rebuilt.
    keepalive: Option<Box<dyn std::any::Any + Send>>,
}
struct RegistryState {
    entries: BTreeMap<EngineKey, Entry>,
    tasks: BTreeMap<EngineKey, tokio::task::JoinHandle<()>>,
}
/// How long the cache writer gathers saves before writing (the latest per
/// engine wins).
const CACHE_SAVE_WINDOW: Duration = Duration::from_millis(500);

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
        rows: crate::cache::CachedRows,
    },
    Flush(tokio::sync::oneshot::Sender<()>),
}
struct Inner {
    data_dir: PathBuf,
    sessions: Arc<dyn SessionStore>,
    resolvers: Vec<Arc<dyn PairingResolver>>,
    configuration_error: Option<String>,
    runtime: tokio::runtime::Handle,
    state: Mutex<RegistryState>,
    updates: watch::Sender<RegistrySnapshot>,
    changes: tokio::sync::Mutex<()>,
    cache: crate::cache::EngineCache,
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
    /// The same engine entry of the same registry: a target minted before a
    /// runtime replacement never matches one minted after it.
    pub fn same_target(&self, other: &Self) -> bool {
        self.key == other.key && Weak::ptr_eq(&self.registry, &other.registry)
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
        let params = crate::routing::wire_params(&self.key, method, params)?;
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
        let params = crate::routing::wire_params(&self.key, method, params)?;
        self.live()?.subscribe(method, params).await
    }
    pub async fn subscribe_checked(
        &self,
        method: &str,
        params: Value,
    ) -> Result<roboco_rpc::RpcSubscription, RpcError> {
        let params = crate::routing::wire_params(&self.key, method, params)?;
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
pub fn call_deadline(method: &str) -> Duration {
    if method.contains("Clone") || method.contains("Fetch") {
        return Duration::from_secs(900);
    }
    if method == methods::INSTALL_HARNESS {
        // An explicit CLI install may download and extract for many minutes
        // (the installer's own deadline is 15 minutes; matches upstream's
        // forward deadline for the relay route we do not have — here the
        // client leash is the only cut-off).
        return Duration::from_secs(900);
    }
    if matches!(
        method,
        methods::UPLOAD_CHUNK | methods::UPLOAD_COMMIT | methods::READ_ATTACHMENT_CHUNK
    ) {
        // Attachment calls carry their own ladder (the 90 s/30 s per-chunk
        // and 150 s commit timeouts race timers in `attachments.rs`); the
        // inner default of 30 s would fire first on a slow link — exactly
        // the bug the web client fixed for its own transport. A 100 MB
        // upload over a slow tailcat link needs chunk calls that outlast
        // the ladder's per-chunk budget, and the whole-file deadline
        // (size-proportional since `uploads-100mb-v1`) is the real bound.
        // Mirrors the mobile client's `call_deadline` and the web client's
        // `#timeoutFor` long budget.
        return Duration::from_secs(900);
    }
    if method == methods::LIST_MODELS || method == methods::LIST_COMMANDS {
        return Duration::from_secs(100);
    }
    if method == methods::CHECK_HARNESS_UPDATES {
        // A full fleet of enabled providers is checked two at a time; each
        // provider may need both a CLI probe and a network request.
        return Duration::from_secs(240);
    }
    if method == methods::APPLY_HARNESS_UPDATE {
        // Leave headroom beyond the provider's 15-minute mutation timeout for
        // queueing, verification, and the response itself.
        return Duration::from_secs(20 * 60);
    }
    if method == methods::DISCARD_WORKING_TREE {
        // A discard batches several git invocations per step on big working
        // trees; unlike a read, a timeout here would misreport an in-flight
        // destructive task that the engine always runs to completion.
        return Duration::from_secs(900);
    }
    Duration::from_secs(30)
}

fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|e| e.into_inner())
}
fn entry(
    key: EngineKey,
    info: EngineInfo,
    saved: Option<SavedEngine>,
    keepalive: Option<Box<dyn std::any::Any + Send>>,
) -> Entry {
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
        keepalive,
    }
}
impl EngineRegistry {
    /// Adopt the local bootstrap's client. `reconnect` revives the local entry
    /// when its transport dies (a URL to dial, or an in-process client factory);
    /// remote entries reconnect independently through their saved credentials.
    /// Convenience for the file-store-plus-local shape: a JSON session store
    /// next to the data directory and no extra pairing resolvers (callers that
    /// need those use `open_with`).
    pub async fn open(
        path: PathBuf,
        local_info: EngineInfo,
        client: Arc<RpcClient>,
        reconnect: Option<Reconnect>,
    ) -> anyhow::Result<Self> {
        let data_dir = path
            .parent()
            .unwrap_or(std::path::Path::new("."))
            .to_path_buf();
        Self::open_with(RegistryOptions {
            data_dir,
            sessions: Arc::new(FileSessionStore::new(path)),
            local: Some(LocalEngine {
                info: local_info,
                client,
                reconnect,
            }),
            resolvers: Vec::new(),
        })
        .await
    }

    /// Open a registry from explicit parts. `local: None` is the phone shape:
    /// no `EngineKey::local()` entry, every projected id scoped, and pairing
    /// as the only way in. `sessions` decides where credentials live —
    /// Android wraps them in Keystore behind its own `SessionStore`.
    pub async fn open_with(options: RegistryOptions) -> anyhow::Result<Self> {
        let RegistryOptions {
            data_dir,
            sessions,
            local,
            resolvers,
        } = options;
        let (saved_engines, mut configuration_error) = match sessions.load() {
            Ok(saved) => (saved, None),
            Err(_) => (
                Vec::new(),
                Some("Saved engine configuration could not be read. The file is preserved; pairing is unavailable until it is repaired.".to_string()),
            ),
        };
        let (updates, _) = watch::channel(RegistrySnapshot::default());
        let mut entries = BTreeMap::new();
        if let Some(local) = &local {
            entries.insert(
                EngineKey::local(),
                entry(EngineKey::local(), local.info.clone(), None, None),
            );
        }
        for saved in saved_engines {
            if saved.key.is_local() || saved.key.0.is_empty() || entries.contains_key(&saved.key) {
                configuration_error = Some("Saved engine configuration contains invalid identities. The file is preserved; pairing is unavailable until it is repaired.".into());
                continue;
            }
            entries.insert(
                saved.key.clone(),
                entry(saved.key.clone(), saved.info.clone(), Some(saved), None),
            );
        }
        let cache = crate::cache::EngineCache::new(&data_dir);
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
            // Coalesce: every feed frame asks for a save of the whole rows
            // file (hundreds of KB, fsynced), so the writer gathers the
            // saves of a short window and writes only each engine's latest.
            // A flush ends the window at once and is acked after the writes.
            while let Some(first) = cache_rx.recv().await {
                let mut saves: BTreeMap<String, crate::cache::CachedRows> = BTreeMap::new();
                let mut flushes = Vec::new();
                let mut absorb = |command: CacheCommand| match command {
                    CacheCommand::Save { key, rows } => {
                        saves.insert(key, rows);
                        false
                    }
                    CacheCommand::Flush(done) => {
                        flushes.push(done);
                        true
                    }
                };
                let mut flushing = absorb(first);
                let window = tokio::time::Instant::now() + CACHE_SAVE_WINDOW;
                while !flushing {
                    match tokio::time::timeout_at(window, cache_rx.recv()).await {
                        Ok(Some(command)) => flushing = absorb(command),
                        Ok(None) | Err(_) => break,
                    }
                }
                for (key, rows) in saves {
                    let cache = writer_cache.clone();
                    if let Ok(Err(error)) =
                        tokio::task::spawn_blocking(move || cache.save_rows(&key, &rows)).await
                    {
                        tracing::warn!(%error, "Could not save engine history");
                    }
                }
                for done in flushes {
                    let _ = done.send(());
                }
            }
        });
        let registry = Self {
            inner: Arc::new(Inner {
                data_dir,
                sessions,
                resolvers,
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
        if let Some(local) = local {
            registry.spawn(EngineKey::local(), Some(local.client), local.reconnect);
        }
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
    ///
    /// Outside this crate only under the `test-support` feature, so
    /// `roboco-ui`'s `set_test_engine` keeps building against it.
    #[cfg(any(test, feature = "test-support"))]
    pub fn test_local(info: EngineInfo, client: Arc<RpcClient>) -> Self {
        let (updates, _) = watch::channel(RegistrySnapshot::default());
        let mut entries = BTreeMap::new();
        let mut local = entry(EngineKey::local(), info, None, None);
        local.snapshot.state = EngineConnectionState::Connected;
        local.client = Some(client);
        entries.insert(EngineKey::local(), local);
        // The receiver is dropped on purpose: sends fail fast, and the
        // guard in `flush_cache_writes` already tolerates a closed channel.
        let (cache_tx, _) = tokio::sync::mpsc::unbounded_channel::<CacheCommand>();
        Self {
            inner: Arc::new(Inner {
                data_dir: std::env::temp_dir(),
                sessions: Arc::new(FileSessionStore::new(
                    std::env::temp_dir().join("roboco-test-registry-unused.json"),
                )),
                resolvers: Vec::new(),
                configuration_error: None,
                runtime: tokio::runtime::Handle::current(),
                state: Mutex::new(RegistryState {
                    entries,
                    tasks: BTreeMap::new(),
                }),
                updates,
                changes: tokio::sync::Mutex::new(()),
                cache: crate::cache::EngineCache::new(&std::env::temp_dir()),
                cache_tx,
            }),
        }
    }
    pub fn snapshot(&self) -> RegistrySnapshot {
        self.inner.updates.borrow().clone()
    }
    /// The local engine's target. Desktop registries always carry one; a
    /// registry opened without a local engine (a phone) has no local entry
    /// and this panics — resolve an engine key with `target` instead.
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
        let ResolvedPairing {
            base,
            code,
            keepalive,
            route,
        } = match self
            .inner
            .resolvers
            .iter()
            .find_map(|resolver| resolver.resolve(pairing_url, &self.inner.data_dir))
        {
            Some(result) => result?,
            None => {
                let (base, code) = parse_pairing_url(pairing_url)?;
                ResolvedPairing {
                    base,
                    code,
                    keepalive: None,
                    route: None,
                }
            }
        };
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
        Ok(self
            .adopt_engine(&base, &grant.credential, keepalive, RouteUpdate::Set(route))
            .await?
            .key)
    }

    /// Present a room secret to one member engine and adopt it as an
    /// ordinary paired engine (ADR 0011): `POST {base}/room/present
    /// {secret, label}` mints (or reissues) a session labeled by the
    /// presenter, and the registry wires that session in exactly like a
    /// pairing — saved credential, supervised reconnect, cache rows. An
    /// engine this registry already holds (same device id) is reattached
    /// under its existing key, never added twice. `address` only labels the
    /// returned record (it is a transport secret and never logged).
    ///
    /// `route` is the dialer's rebuild route for `base` (a Tailcat
    /// forwarder's `tailcat:<address>`), saved so a restart rebuilds the
    /// transport instead of dialing a dead loopback port. `None` keeps
    /// whatever route a reattached entry already had.
    pub async fn join_room_member(
        &self,
        base: &str,
        address: &str,
        secret: &str,
        label: &str,
        keepalive: Option<Box<dyn std::any::Any + Send>>,
        route: Option<String>,
    ) -> anyhow::Result<(RoomMemberSession, Adoption)> {
        anyhow::ensure!(
            self.inner.configuration_error.is_none(),
            "Saved engine configuration needs repair before joining rooms"
        );
        // Fixed-text failures: the presentation's own errors never echo the
        // secret (ADR 0008), and adopt errors quote at most a loopback URL.
        let grant: RoomGrant = crate::rooms::present_room_member(base, secret, label)
            .await
            .map_err(|error| anyhow::anyhow!("{error}"))?;
        let route = match route {
            Some(route) => RouteUpdate::Set(Some(route)),
            None => RouteUpdate::Keep,
        };
        let adopted = self
            .adopt_engine(base, &grant.credential, keepalive, route)
            .await
            .map_err(|error| anyhow::anyhow!("{error}"))?;
        Ok((
            RoomMemberSession {
                address: address.to_owned(),
                engine_key: adopted.key.0,
                session_id: Some(grant.session.id),
            },
            adopted.adoption,
        ))
    }

    /// Wire one authenticated credential into the registry: connect the
    /// WebSocket, read `EngineInfo`, persist the saved entry, publish, and
    /// start supervision. The tail both `pair` and `join_room_member`
    /// share — a room-minted session is an ordinary paired engine in every
    /// respect (ADR 0011).
    ///
    /// Idempotent per engine identity (fleet follow): when a remote entry
    /// with the same device id already exists, it is reattached under its
    /// key — the fresh endpoint, credential, and keepalive replace the old
    /// ones and supervision restarts on the new client — instead of a
    /// second entry. That is also what heals a Tailcat-paired entry whose
    /// forwarder died with the previous app process.
    async fn adopt_engine(
        &self,
        base: &str,
        credential: &str,
        keepalive: Option<Box<dyn std::any::Any + Send>>,
        route: RouteUpdate,
    ) -> anyhow::Result<Adopted> {
        let endpoint = base.replacen("http", "ws", 1);
        let client = Arc::new(roboco_rpc::connect_ws_authenticated(&endpoint, credential).await?);
        let info: EngineInfo = tokio::time::timeout(
            Duration::from_secs(10),
            client.call_as(methods::ENGINE_INFO, json!({})),
        )
        .await??;
        let _change = self.inner.changes.lock().await;
        let duplicate = lock(&self.inner.state)
            .entries
            .iter()
            .find(|(key, entry)| !key.is_local() && entry.snapshot.info.device_id == info.device_id)
            .map(|(key, _)| key.clone());
        if let Some(key) = duplicate {
            let (previous_saved, previous_keepalive, previous_info) = {
                let mut state = lock(&self.inner.state);
                let entry = state
                    .entries
                    .get_mut(&key)
                    .ok_or_else(|| anyhow::anyhow!("Engine was forgotten"))?;
                let route = match route {
                    RouteUpdate::Keep => entry.saved.as_ref().and_then(|s| s.route.clone()),
                    RouteUpdate::Set(route) => route,
                };
                let saved = SavedEngine {
                    key: key.clone(),
                    endpoint,
                    credential: credential.to_owned(),
                    info: info.clone(),
                    route,
                };
                (
                    entry.saved.replace(saved),
                    std::mem::replace(&mut entry.keepalive, keepalive),
                    std::mem::replace(&mut entry.snapshot.info, info),
                )
            };
            if let Err(err) = self.persist() {
                // Roll the entry back whole: the live forwarder it was
                // running on stays alive (the fresh one drops instead).
                if let Some(entry) = lock(&self.inner.state).entries.get_mut(&key) {
                    entry.saved = previous_saved;
                    entry.keepalive = previous_keepalive;
                    entry.snapshot.info = previous_info;
                }
                return Err(err);
            }
            self.publish();
            // `spawn` aborts the old supervision task; the old forwarder (if
            // any) goes with it.
            self.spawn(key.clone(), Some(client), None);
            drop(previous_keepalive);
            return Ok(Adopted {
                key,
                adoption: Adoption::Reattached,
            });
        }
        let key = EngineKey(uuid::Uuid::new_v4().to_string());
        let saved = SavedEngine {
            key: key.clone(),
            endpoint,
            credential: credential.to_owned(),
            info: info.clone(),
            route: match route {
                RouteUpdate::Keep => None,
                RouteUpdate::Set(route) => route,
            },
        };
        lock(&self.inner.state).entries.insert(
            key.clone(),
            entry(key.clone(), info, Some(saved), keepalive),
        );
        if let Err(err) = self.persist() {
            lock(&self.inner.state).entries.remove(&key);
            return Err(err);
        }
        self.publish();
        self.spawn(key.clone(), Some(client), None);
        Ok(Adopted {
            key,
            adoption: Adoption::New,
        })
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
        let saved: Vec<SavedEngine> = lock(&self.inner.state)
            .entries
            .values()
            .filter_map(|e| e.saved.clone())
            .collect();
        self.inner.sessions.save(&saved)
    }
    fn publish(&self) {
        publish(&self.inner);
    }
    fn spawn(&self, key: EngineKey, client: Option<Arc<RpcClient>>, reconnect: Option<Reconnect>) {
        let weak = Arc::downgrade(&self.inner);
        let task_key = key.clone();
        let task = tokio::spawn(async move {
            supervise(weak, task_key, client, reconnect).await;
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
    reconnect: Option<Reconnect>,
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
            match routed_endpoint(&weak, &key, saved).await {
                Ok(endpoint) => {
                    let connected =
                        roboco_rpc::connect_ws_authenticated(&endpoint, &saved.credential)
                            .await
                            .map(Arc::new);
                    // A forwarder whose loopback no longer accepts is dead:
                    // drop it so the next pass rebuilds the route. Behind a
                    // live loopback the forwarder recovers on its own.
                    if connected.is_err()
                        && saved.route.is_some()
                        && !loopback_accepts(&endpoint).await
                    {
                        update(&weak, &key, |entry| entry.keepalive = None);
                    }
                    connected
                }
                Err(error) => Err(error),
            }
        } else if let Some(reconnect) = &reconnect {
            match reconnect {
                Reconnect::Url(url) => roboco_rpc::connect_ws(url).await.map(Arc::new),
                Reconnect::Factory(factory) => Ok(factory()),
            }
        } else {
            // No reconnect path was provided (test-local clients): the
            // entry stays offline for the registry's lifetime.
            return;
        };
        let started = std::time::Instant::now();
        let (transport, result) = match result {
            Ok(client) => {
                let driven = drive(&weak, &key, client.clone(), &expected).await;
                (Some(client), driven)
            }
            Err(err) => (None, Err(err)),
        };
        // A clean drop has no probe error to name — the transport's own
        // close reason (an oversized frame, a reset socket, a lease expiry)
        // says why the session went away, so the failure surface can tell a
        // transport drop from an engine crash (upstream acd7c9ba7).
        let error = result
            .err()
            .map(|e| e.to_string())
            .or_else(|| transport.and_then(|client| client.close_reason()))
            .unwrap_or_else(|| "Engine connection closed".into());
        // The drop reason was previously stored in `last_error` but never
        // logged, so an engine going silent left the log showing only
        // downstream symptoms ("Engine is offline" retries). One line per
        // drop; retries are bounded by the backoff below.
        tracing::warn!(engine = %key.0, error = %error, "engine connection dropped");
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
/// The endpoint a saved engine dials now. An engine reached through a
/// resolver's transport (a Tailcat forwarder) saved that transport's
/// loopback URL, which died with the process that ran it: whenever this
/// registry holds no live transport for the entry, the resolver that paired
/// it rebuilds one from the saved route. A route no resolver claims keeps
/// dialing the saved endpoint, as before routes were saved.
async fn routed_endpoint(
    weak: &Weak<Inner>,
    key: &EngineKey,
    saved: &SavedEngine,
) -> Result<String, RpcError> {
    let Some(route) = saved.route.clone() else {
        return Ok(saved.endpoint.clone());
    };
    let (resolvers, data_dir) = {
        let inner = weak.upgrade().ok_or(RpcError::Closed)?;
        let state = lock(&inner.state);
        let entry = state.entries.get(key).ok_or(RpcError::Closed)?;
        if entry.keepalive.is_some() {
            let current = entry.saved.as_ref().unwrap_or(saved);
            return Ok(current.endpoint.clone());
        }
        (inner.resolvers.clone(), inner.data_dir.clone())
    };
    // Starting a forwarder blocks (a child process and its readiness line).
    let restored = tokio::task::spawn_blocking(move || {
        resolvers
            .iter()
            .find_map(|resolver| resolver.restore(&route, &data_dir))
    })
    .await
    .map_err(|_| RpcError::Closed)?;
    let restored = match restored {
        Some(Ok(restored)) => restored,
        // Fixed text: the route is a secret and never reaches errors.
        Some(Err(error)) => {
            tracing::warn!(engine = %key.0, %error, "engine transport could not be rebuilt");
            return Err(RpcError::Transport(
                "Could not start the engine's transport".into(),
            ));
        }
        None => return Ok(saved.endpoint.clone()),
    };
    let endpoint = restored.base.replacen("http", "ws", 1);
    let keepalive = restored.keepalive;
    update(weak, key, |entry| {
        entry.keepalive = keepalive;
        if let Some(saved) = &mut entry.saved {
            saved.endpoint = endpoint.clone();
        }
    });
    Ok(endpoint)
}

/// Whether a forwarder's loopback listener still accepts connections.
async fn loopback_accepts(endpoint: &str) -> bool {
    let Some(address) = reqwest::Url::parse(endpoint).ok().and_then(|url| {
        Some(format!(
            "{}:{}",
            url.host_str()?,
            url.port_or_known_default()?
        ))
    }) else {
        return false;
    };
    matches!(
        tokio::time::timeout(
            Duration::from_secs(2),
            tokio::net::TcpStream::connect(address)
        )
        .await,
        Ok(Ok(_))
    )
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
                // The local entry is an IN-PROCESS engine sharing this
                // process's scheduling: under load (many side chats, boot
                // recovery, snapshot writes) its RPC worker can lag the 5 s
                // probe window for a long while while the engine stays
                // perfectly alive. Tearing the client down for that meant a
                // full teardown → reconnect → resubscribe storm exactly when
                // the machine was busiest (perf 03d). Degrade, not drop: the
                // memory transport's `closed()` is the authoritative death
                // signal for the local entry, so a probe timeout only logs
                // (and the probe itself gets a much wider window); a FAILED
                // call or a changed identity still tears the connection down.
                let probe = if key.is_local() {
                    Duration::from_secs(30)
                } else {
                    Duration::from_secs(5)
                };
                let probed =
                    tokio::time::timeout(probe, client.call_as(methods::ENGINE_INFO, json!({}))).await;
                let info: EngineInfo = match probed {
                    Err(_) if key.is_local() => {
                        tracing::debug!(
                            engine = %key.0,
                            "engine health check lagged (in-process); keeping the connection"
                        );
                        continue;
                    }
                    Err(_) => {
                        return Err(RpcError::Transport("Engine health check timed out".into()))
                    }
                    Ok(result) => result?,
                };
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
    let Some(rows) = lock(&inner.state)
        .entries
        .get(key)
        .map(|entry| crate::cache::CachedRows {
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
    let _ = inner.cache_tx.send(CacheCommand::Save {
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
