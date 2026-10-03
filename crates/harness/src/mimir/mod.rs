//! Mimir harness: drives Mimir natively through the Roboco-owned frontend
//! plugin `sh.roboco.bridge` (`integrations/mimir/`), launched as
//! `mimir plugin run sh.roboco.bridge` and spoken to over framed JSON-RPC on
//! stdio. No ACP and no per-prompt process: one bridge per effective Mimir
//! agent directory serves every chat, each through its own native attachment.
//!
//! Mimir owns execution and the saved conversation. This crate owns the
//! process and the typed wire ([`Runtime`]); the engine owns attachments,
//! projections and controls. Chats therefore never go through [`Harness::run`].
//!
//! - **Discovery**: `MIMIR_EXECUTABLE`, else `mimir` on PATH or in the usual
//!   install directories. `installed()` is a filesystem check only.
//! - **Readiness**: probed asynchronously and cached by executable identity
//!   and agent directory; missing plugin, incompatible bridge and missing
//!   models each name their corrective action.
//! - **Environment**: inherited unchanged, including `MIMIR_CODING_AGENT_DIR`
//!   and credentials. Roboco never points Mimir at a private directory.
//! - **Lifetime**: the bridge holds the harness execution lease until its
//!   process tree is reaped; a crashed bridge restarts only after the old tree
//!   settled, at most [`RESTART_LIMIT`] times per [`RESTART_WINDOW`].

mod client;
pub mod protocol;
mod runtime;

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use async_trait::async_trait;
use futures::stream::BoxStream;

use roboco_proto::{
    AgentEvent, HarnessId, Model, NativeCommand, NativeReadiness, NativeSkill, ReasoningLevel,
    RunRequest, SlashCommand, SteeringMode,
};

pub use runtime::{Exit, Failure, Runtime};

use crate::{Harness, HarnessError, RunControls};

/// Frame and chunk budgets proposed at `initialize`: prompts carry inline
/// images, so frames are larger than the bridge's 1 MiB default.
pub(crate) const FRAME_BYTES: usize = 8 << 20;
pub(crate) const CHUNK_BYTES: usize = 1 << 20;

pub const RESTART_LIMIT: usize = 3;
pub const RESTART_WINDOW: Duration = Duration::from_secs(10 * 60);
const READINESS_TTL: Duration = Duration::from_secs(5 * 60);
const CATALOG_TTL: Duration = Duration::from_secs(60);

/// Shown wherever the bridge plugin is missing. Installing is always the
/// user's explicit act; Roboco never installs or enables plugins.
pub const PLUGIN_INSTALL_HINT: &str = "Install the Roboco bridge plugin with `mimir plugin install <path to sh.roboco.bridge-0.1.0>` (built by `python3 integrations/mimir/build.py`; see docs/reference/mimir.md), then check it with `mimir plugin list`.";

const INSTALL_HINT: &str =
    "Mimir is not installed: put `mimir` on PATH or set MIMIR_EXECUTABLE to its full path.";

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Commands and skills a folder's conversations reported, for composers that
/// ask before a chat is attached.
#[derive(Clone, Default)]
struct FolderCatalog {
    commands: Vec<NativeCommand>,
    skills: Vec<NativeSkill>,
}

pub struct MimirHarness {
    executable: Option<PathBuf>,
    /// Extra child environment. Tests isolate MIMIR_CODING_AGENT_DIR here
    /// instead of mutating the engine's own environment.
    env: Vec<(String, String)>,
    env_removed: Vec<String>,
    gate: Option<Arc<tokio::sync::RwLock<()>>>,
    runtimes: tokio::sync::Mutex<HashMap<PathBuf, Arc<Runtime>>>,
    crashes: Mutex<VecDeque<Instant>>,
    generation: std::sync::atomic::AtomicU64,
    readiness: Mutex<Option<(ReadinessKey, Instant, NativeReadiness)>>,
    models: Mutex<Option<(Instant, Vec<Model>)>>,
    folders: Mutex<HashMap<String, FolderCatalog>>,
}

#[derive(Clone, PartialEq, Eq)]
struct ReadinessKey {
    executable: PathBuf,
    modified: Option<std::time::SystemTime>,
    len: u64,
    agent_dir: PathBuf,
}

impl Default for MimirHarness {
    fn default() -> Self {
        Self {
            executable: None,
            env: Vec::new(),
            env_removed: Vec::new(),
            gate: None,
            runtimes: tokio::sync::Mutex::new(HashMap::new()),
            crashes: Mutex::new(VecDeque::new()),
            generation: std::sync::atomic::AtomicU64::new(0),
            readiness: Mutex::new(None),
            models: Mutex::new(None),
            folders: Mutex::new(HashMap::new()),
        }
    }
}

impl MimirHarness {
    pub fn new() -> Self {
        Self::default()
    }

    /// Use a fixed CLI binary instead of PATH/known-location resolution.
    pub fn with_executable(mut self, path: impl Into<PathBuf>) -> Self {
        self.executable = Some(path.into());
        self
    }

    /// Add child environment variables (tests: an isolated agent directory).
    pub fn with_env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env.push((key.into(), value.into()));
        self
    }

    /// Withhold an inherited variable from the child (tests: real credentials).
    pub fn with_env_removed(mut self, key: impl Into<String>) -> Self {
        self.env_removed.push(key.into());
        self
    }

    /// The registry's per-harness execution gate; a live bridge holds a read lease on it.
    pub fn with_execution_gate(mut self, gate: Arc<tokio::sync::RwLock<()>>) -> Self {
        self.gate = Some(gate);
        self
    }

    fn resolve_executable(&self) -> Result<PathBuf, HarnessError> {
        if let Some(path) = &self.executable {
            return crate::executable::validate_native_override(path);
        }
        if let Some(path) = std::env::var_os("MIMIR_EXECUTABLE").filter(|p| !p.is_empty()) {
            return crate::executable::validate_native_override(&PathBuf::from(path));
        }
        let home = crate::executable::home_dir();
        let extra = home
            .map(|home| {
                vec![
                    home.join(".cargo").join("bin"),
                    home.join(".local").join("bin"),
                ]
            })
            .unwrap_or_default();
        crate::executable::find_on_paths("mimir", extra)
            .ok_or_else(|| HarnessError::NotInstalled(INSTALL_HINT.into()))
    }

    /// The agent directory this engine's Mimir uses: `MIMIR_CODING_AGENT_DIR`
    /// (from the test env, else inherited), else Mimir's `~/.mimir/agent`.
    /// One bridge serves each identity.
    pub fn agent_dir(&self) -> PathBuf {
        let configured = self
            .env
            .iter()
            .rev()
            .find(|(key, _)| key == "MIMIR_CODING_AGENT_DIR")
            .map(|(_, value)| PathBuf::from(value))
            .or_else(|| {
                std::env::var_os("MIMIR_CODING_AGENT_DIR")
                    .filter(|v| !v.is_empty())
                    .map(PathBuf::from)
            });
        let dir = configured.unwrap_or_else(|| {
            crate::executable::home_or_current_dir()
                .join(".mimir")
                .join("agent")
        });
        dir.canonicalize().unwrap_or(dir)
    }

    /// The live bridge, starting one when none runs. A crashed bridge is
    /// replaced only after its process tree settled, and only within the
    /// restart budget; an explicit [`Self::reset_restarts`] renews it.
    pub async fn runtime(&self) -> Result<Arc<Runtime>, Failure> {
        let identity = self.agent_dir();
        let mut runtimes = self.runtimes.lock().await;
        if let Some(existing) = runtimes.get(&identity).cloned() {
            if existing.alive() {
                return Ok(existing);
            }
            existing.request_stop();
            let exit = existing.wait_exit().await;
            runtimes.remove(&identity);
            if !exit.requested {
                self.note_crash(&exit.message)?;
            }
        }
        let executable = self
            .resolve_executable()
            .map_err(|error| Failure::MissingExecutable(error.to_string()))?;
        let generation = self
            .generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            + 1;
        let runtime = Runtime::launch(runtime::Launch {
            executable,
            env: self.env.clone(),
            env_removed: self.env_removed.clone(),
            gate: self.gate.clone(),
            generation,
        })
        .await?;
        runtimes.insert(identity, runtime.clone());
        Ok(runtime)
    }

    /// The running bridge, if any, without starting one.
    pub async fn live_runtime(&self) -> Option<Arc<Runtime>> {
        self.runtimes
            .lock()
            .await
            .get(&self.agent_dir())
            .filter(|runtime| runtime.alive())
            .cloned()
    }

    fn note_crash(&self, message: &str) -> Result<(), Failure> {
        let mut crashes = lock(&self.crashes);
        let now = Instant::now();
        while crashes
            .front()
            .is_some_and(|at| now.duration_since(*at) > RESTART_WINDOW)
        {
            crashes.pop_front();
        }
        crashes.push_back(now);
        if crashes.len() > RESTART_LIMIT {
            return Err(Failure::Failed(format!(
                "the Mimir bridge exited {} times in {} minutes and was not restarted again; last exit: {message}",
                crashes.len(),
                RESTART_WINDOW.as_secs() / 60
            )));
        }
        Ok(())
    }

    /// An explicit user retry renews the restart budget.
    pub fn reset_restarts(&self) {
        lock(&self.crashes).clear();
    }

    /// Stop the bridge after releasing its attachments (engine shutdown, updates).
    pub async fn shutdown(&self) {
        let runtimes: Vec<_> = self.runtimes.lock().await.drain().map(|(_, r)| r).collect();
        for runtime in runtimes {
            runtime.shutdown().await;
        }
    }

    /// Setup readiness, cached by executable identity and agent directory.
    pub async fn readiness(&self, force: bool) -> NativeReadiness {
        let executable = match self.resolve_executable() {
            Ok(path) => path,
            Err(_) => {
                return NativeReadiness::MissingExecutable {
                    action: INSTALL_HINT.into(),
                };
            }
        };
        let metadata = std::fs::metadata(&executable).ok();
        let key = ReadinessKey {
            modified: metadata.as_ref().and_then(|m| m.modified().ok()),
            len: metadata.map_or(0, |m| m.len()),
            executable: executable.clone(),
            agent_dir: self.agent_dir(),
        };
        if !force
            && let Some((cached, at, readiness)) = lock(&self.readiness).clone()
            && cached == key
            && at.elapsed() < READINESS_TTL
        {
            return readiness;
        }
        let readiness = match self.runtime().await {
            Err(Failure::MissingExecutable(_)) => NativeReadiness::MissingExecutable {
                action: INSTALL_HINT.into(),
            },
            Err(Failure::PluginMissing(message)) => NativeReadiness::PluginMissing {
                message,
                action: PLUGIN_INSTALL_HINT.into(),
            },
            Err(Failure::Incompatible(message)) => NativeReadiness::Incompatible {
                message,
                action: format!(
                    "Install the sh.roboco.bridge build that matches this Roboco version. {PLUGIN_INSTALL_HINT}"
                ),
            },
            Err(Failure::Failed(message)) => NativeReadiness::Failed { message },
            Ok(runtime) => match runtime.catalog(&home_folder()).await {
                Ok(providers) if providers.iter().all(|p| p.models.is_empty()) => {
                    NativeReadiness::NoModels {
                        action:
                            "Configure and sign in to a model provider in Mimir, then check again."
                                .into(),
                    }
                }
                Ok(_) => NativeReadiness::Ready {
                    executable: executable.display().to_string(),
                    version: crate::executable::binary_version(&executable).map(|v| v.to_string()),
                    bridge: format!(
                        "{} {}",
                        runtime.hello.bridge.id, runtime.hello.bridge.version
                    ),
                },
                Err(error) => NativeReadiness::Failed {
                    message: format!("Mimir could not list models: {}", error.message),
                },
            },
        };
        *lock(&self.readiness) = Some((key, Instant::now(), readiness.clone()));
        readiness
    }

    /// Remember what a folder's conversation offers, for composers asking
    /// before their chat attaches.
    pub fn remember_folder_catalog(
        &self,
        cwd: &str,
        commands: Vec<NativeCommand>,
        skills: Vec<NativeSkill>,
    ) {
        lock(&self.folders).insert(cwd.to_owned(), FolderCatalog { commands, skills });
    }

    fn folder_catalog(&self, cwd: &Path) -> Option<FolderCatalog> {
        let folders = lock(&self.folders);
        folders
            .get(cwd.to_string_lossy().as_ref())
            .or_else(|| {
                let canonical = cwd.canonicalize().ok()?;
                folders.get(canonical.to_string_lossy().as_ref())
            })
            .cloned()
    }
}

fn home_folder() -> String {
    crate::executable::home_or_current_dir()
        .display()
        .to_string()
}

/// Mimir's reasoning names (`off` … `max`) mapped onto Roboco's ladder. `off`
/// has no Roboco level: it stays selectable through the native catalog.
pub fn reasoning_level(level: &str) -> Option<ReasoningLevel> {
    serde_json::from_value(serde_json::Value::String(level.to_owned())).ok()
}

/// The catalog as picker rows: `provider/model` ids.
pub fn catalog_models(providers: &[protocol::ProviderChoice]) -> Vec<Model> {
    providers
        .iter()
        .flat_map(|provider| {
            provider.models.iter().map(move |model| Model {
                id: format!("{}/{}", provider.id, model.id),
                label: model.name.clone(),
                description: Some(provider.name.clone()),
                reasoning_levels: match &model.reasoning {
                    protocol::ReasoningControl::Unsupported => Vec::new(),
                    protocol::ReasoningControl::Levels(levels) => {
                        levels.iter().filter_map(|l| reasoning_level(l)).collect()
                    }
                },
                options: Vec::new(),
            })
        })
        .collect()
}

#[async_trait]
impl Harness for MimirHarness {
    fn id(&self) -> HarnessId {
        HarnessId::Mimir
    }
    fn display_name(&self) -> &str {
        "Mimir"
    }
    fn supports_steering(&self) -> bool {
        true
    }
    /// `session.steer` reaches the next model boundary without cancelling work.
    fn steering_mode(&self) -> SteeringMode {
        SteeringMode::StepBoundary
    }
    /// Each model carries its own levels; there is no harness-wide ladder.
    fn reasoning_levels(&self) -> &[ReasoningLevel] {
        &[]
    }
    fn installed(&self) -> bool {
        self.resolve_executable().is_ok()
    }
    fn executable_path(&self) -> Option<PathBuf> {
        self.resolve_executable().ok()
    }
    fn native(&self) -> Option<&MimirHarness> {
        Some(self)
    }

    async fn models(&self) -> Result<Vec<Model>, HarnessError> {
        if let Some((at, models)) = lock(&self.models).clone()
            && at.elapsed() < CATALOG_TTL
        {
            return Ok(models);
        }
        let runtime = self
            .runtime()
            .await
            .map_err(|failure| HarnessError::Protocol(failure.to_string()))?;
        let providers = runtime
            .catalog(&home_folder())
            .await
            .map_err(|error| HarnessError::Protocol(error.message))?;
        let models = catalog_models(&providers);
        *lock(&self.models) = Some((Instant::now(), models.clone()));
        Ok(models)
    }

    /// Commands are session-scoped in Mimir: this answers with what a
    /// conversation in this folder last reported, never a guessed list.
    async fn commands_for(&self, cwd: &Path) -> Result<Vec<SlashCommand>, HarnessError> {
        Ok(self
            .folder_catalog(cwd)
            .map(|catalog| {
                catalog
                    .commands
                    .into_iter()
                    .map(|command| SlashCommand {
                        name: command.name,
                        description: command.description,
                        input_hint: command.argument,
                    })
                    .collect()
            })
            .unwrap_or_default())
    }

    /// The host's effective skill catalog for this folder. Lazy plugin skills
    /// have no file; their identity is `harness-skill:mimir:<name>`.
    async fn skills(
        &self,
        cwd: &Path,
    ) -> Result<Option<Vec<roboco_proto::invocation::Skill>>, HarnessError> {
        Ok(Some(
            self.folder_catalog(cwd)
                .map(|catalog| {
                    catalog
                        .skills
                        .into_iter()
                        .map(|skill| roboco_proto::invocation::Skill {
                            path: skill
                                .path
                                .clone()
                                .unwrap_or_else(|| format!("harness-skill:mimir:{}", skill.name)),
                            name: skill.name,
                            description: skill.description,
                            enabled: true,
                            command: None,
                        })
                        .collect()
                })
                .unwrap_or_default(),
        ))
    }

    async fn run(
        &self,
        _request: RunRequest,
        _controls: RunControls,
    ) -> Result<BoxStream<'static, Result<AgentEvent, HarnessError>>, HarnessError> {
        Err(HarnessError::Protocol(
            "Mimir chats run through the engine's native conversation owner".into(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mimir_reasoning_names_map_onto_the_roboco_ladder() {
        assert_eq!(reasoning_level("xhigh"), Some(ReasoningLevel::XHigh));
        assert_eq!(reasoning_level("minimal"), Some(ReasoningLevel::Minimal));
        assert_eq!(reasoning_level("max"), Some(ReasoningLevel::Max));
        assert_eq!(reasoning_level("off"), None);
        let providers: Vec<protocol::ProviderChoice> = serde_json::from_value(serde_json::json!([
            {"id": "fake", "name": "Fake", "models": [
                {"id": "fake-model", "name": "Fake model", "reasoning": "unsupported"},
                {"id": "deep", "name": "Deep", "reasoning": {"levels": ["off", "low", "high"]}}
            ]}
        ]))
        .unwrap();
        let models = catalog_models(&providers);
        assert_eq!(
            models.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["fake/fake-model", "fake/deep"]
        );
        assert!(models[0].reasoning_levels.is_empty());
        assert_eq!(
            models[1].reasoning_levels,
            [ReasoningLevel::Low, ReasoningLevel::High]
        );
    }

    #[test]
    fn the_restart_budget_refuses_a_crash_loop_until_reset() {
        let harness = MimirHarness::new();
        for _ in 0..RESTART_LIMIT {
            assert!(harness.note_crash("boom").is_ok());
        }
        let refused = harness.note_crash("boom").unwrap_err();
        assert!(refused.to_string().contains("was not restarted again"));
        harness.reset_restarts();
        assert!(harness.note_crash("boom").is_ok());
    }

    #[test]
    fn the_agent_dir_identity_follows_the_child_environment() {
        let dir = tempfile::tempdir().unwrap();
        let harness = MimirHarness::new()
            .with_env("MIMIR_CODING_AGENT_DIR", dir.path().display().to_string());
        assert_eq!(harness.agent_dir(), dir.path().canonicalize().unwrap());
    }

    #[tokio::test]
    async fn a_missing_executable_reports_its_corrective_action() {
        let harness = MimirHarness::new().with_executable("/nonexistent/mimir");
        assert!(!harness.installed());
        assert!(matches!(
            harness.readiness(true).await,
            NativeReadiness::MissingExecutable { ref action } if action.contains("MIMIR_EXECUTABLE")
        ));
    }
}
