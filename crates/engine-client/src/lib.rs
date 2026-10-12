//! `roboco-engine-client` — the GPUI-free client side of the engine
//! connection: pairing, session persistence, engine-scoped identities,
//! supervised reconnects, and the disposable per-engine cache.
//!
//! This is the Rust twin of `@roboco/engine-client` (web). The desktop UI
//! links it through `roboco-ui`; the Android mobile core links it directly.
//! No GPUI crate and (outside the Tailcat pairing shim that lives in
//! `roboco-ui`) no `roboco-engine` dependency may appear in its tree.

pub mod cache;
pub mod registry;
pub mod routing;

use crate::registry::{Reconnect, SavedEngine};
use roboco_proto::EngineInfo;
use roboco_rpc::RpcClient;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The in-process engine a desktop client embeds. A phone has none: it opens
/// the registry with `local: None` and reaches engines only by pairing.
pub struct LocalEngine {
    pub info: EngineInfo,
    pub client: Arc<RpcClient>,
    /// Revives the local entry when its transport dies: a URL to dial, or an
    /// in-process client factory. Remote entries reconnect through their
    /// saved credentials and never see this.
    pub reconnect: Option<Reconnect>,
}

/// How a registry is opened. `open()` keeps today's desktop defaults: a
/// JSON-file session store next to the data directory, a mandatory local
/// engine, and no extra pairing resolvers.
pub struct RegistryOptions {
    /// Directory holding `engine-cache-v1/` and (for resolvers that need one)
    /// `tailcat-forwarders/`.
    pub data_dir: PathBuf,
    /// Persistence for paired engines' session credentials.
    pub sessions: Arc<dyn SessionStore>,
    /// The embedded local engine, if this client has one.
    pub local: Option<LocalEngine>,
    /// Extra pairing-input resolvers, tried in order before URL parsing.
    pub resolvers: Vec<Arc<dyn PairingResolver>>,
}

/// Persistence for paired engines' session credentials
/// (CONTEXT.md: Session = the credential a paired client holds).
pub trait SessionStore: Send + Sync {
    /// Ok(vec![]) when nothing is saved yet. Err = present but unreadable:
    /// the registry enters configuration-error mode, keeps the stored bytes
    /// untouched, and refuses pair/forget.
    fn load(&self) -> anyhow::Result<Vec<SavedEngine>>;
    /// Atomically replace the saved set.
    fn save(&self, engines: &[SavedEngine]) -> anyhow::Result<()>;
}

/// Today's session store: `paired-engines-v1.json` written atomically
/// (tempfile + fsync + rename). The JSON shape is frozen (ADR 0005).
pub struct FileSessionStore {
    path: PathBuf,
}

impl FileSessionStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl SessionStore for FileSessionStore {
    fn load(&self) -> anyhow::Result<Vec<SavedEngine>> {
        let bytes = match std::fs::read(&self.path) {
            Ok(bytes) => bytes,
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Vec::new());
            }
            Err(err) => return Err(err.into()),
        };
        let saved = serde_json::from_slice::<crate::registry::SavedRegistry>(&bytes)?;
        Ok(saved.engines)
    }

    fn save(&self, engines: &[SavedEngine]) -> anyhow::Result<()> {
        let saved = crate::registry::SavedRegistry {
            engines: engines.to_vec(),
        };
        let parent = self
            .path
            .parent()
            .ok_or_else(|| anyhow::anyhow!("Invalid registry path"))?;
        std::fs::create_dir_all(parent)?;
        let mut temp = tempfile::NamedTempFile::new_in(parent)?;
        use std::io::Write;
        temp.write_all(&serde_json::to_vec(&saved)?)?;
        temp.as_file().sync_all()?;
        temp.persist(&self.path).map_err(|e| e.error)?;
        Ok(())
    }
}

/// A pairing input after a resolver has claimed it: where to redeem and the
/// one-time code to redeem with, plus an opaque keepalive (a Tailcat
/// forwarder process handle) dropped when the registry entry is forgotten.
pub struct ResolvedPairing {
    /// `http(s)://host[:port][/prefix]`, no trailing slash.
    pub base: String,
    /// One-time pairing token.
    pub code: String,
    /// Dropped when the entry is forgotten.
    pub keepalive: Option<Box<dyn std::any::Any + Send>>,
}

/// Claims non-URL pairing inputs (e.g. `roboco-tailcat:` invites) for the
/// registry. Implementations live where their machinery lives: the Tailcat
/// resolver is desktop-only and stays in `roboco-ui`.
pub trait PairingResolver: Send + Sync {
    /// Some(..) when this resolver owns `input`; None lets the next resolver /
    /// the URL parser try.
    fn resolve(&self, input: &str, data_dir: &Path) -> Option<anyhow::Result<ResolvedPairing>>;
}
