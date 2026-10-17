//! Engine settings (orchestration v2): preferences the ENGINE itself acts
//! on — set from any client (Settings → General on desktop), persisted under
//! the engine data dir so they survive restarts and apply with the UI
//! closed. Per-device like the harness prefs, not synced.
//!
//! First setting: **Auto-resume limited chats** (ticket 07, default off) —
//! arm the Resume-at-reset continuation automatically whenever a chat
//! becomes Limited, so long work continues overnight without a click per
//! chat. Off by default: an engine that starts spending turns on its own is
//! opt-in.

use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, PoisonError};

use serde::{Deserialize, Serialize};

/// The engine's user-facing settings. Additive only (ADR 0005): every field
/// carries `#[serde(default)]` so settings files from older engines — and
/// older engines reading newer files — keep loading.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize, ts_rs::TS)]
#[serde(default, rename_all = "camelCase")]
pub struct EngineSettings {
    /// Arm **Resume at reset** automatically whenever a chat becomes
    /// Limited (orchestration v2, ticket 07). Off by default.
    pub auto_resume_limited: bool,
}

/// The settings plus where they persist. `path = None` (bare engines,
/// tests) keeps everything in memory. One instance lives on the sessions
/// engine's `Inner`.
#[derive(Debug, Default)]
pub(crate) struct EngineSettingsStore {
    settings: Mutex<EngineSettings>,
    path: Mutex<Option<PathBuf>>,
}

impl EngineSettingsStore {
    /// The persisted store at `path`, loaded. Test-only now: engine
    /// assembly wires the shared store in place with [`Self::attach`], so
    /// production never opens a second copy. The default (no path) serves
    /// bare engines and tests.
    #[cfg(test)]
    pub(crate) fn open(path: Option<PathBuf>) -> Self {
        let settings = path.as_deref().and_then(load_file).unwrap_or_default();
        Self {
            settings: Mutex::new(settings),
            path: Mutex::new(path),
        }
    }

    /// Point an existing store at `path` and load what is persisted there
    /// (engine assembly; the store lives on the shared sessions engine, so
    /// it is wired in place rather than replaced).
    pub(crate) fn attach(&self, path: PathBuf) {
        let settings = load_file(&path).unwrap_or_default();
        *lock(&self.settings) = settings;
        *lock(&self.path) = Some(path);
    }

    pub(crate) fn get(&self) -> EngineSettings {
        *lock(&self.settings)
    }

    /// Update in memory and on disk. Returns the persistence error (the
    /// in-memory value still updated — a failed write never wedges the
    /// toggle, and the next successful write heals the file). The written
    /// snapshot is taken under the lock with the assignment: two racing
    /// `set`s otherwise persist out of order and the file ends up behind
    /// the in-memory value.
    pub(crate) fn set(&self, settings: EngineSettings) -> std::io::Result<()> {
        let snapshot = {
            let mut current = lock(&self.settings);
            *current = settings;
            current.clone()
        };
        let path = lock(&self.path).clone();
        match path {
            Some(path) => write_file(&path, &snapshot),
            None => Ok(()),
        }
    }
}

fn load_file(path: &Path) -> Option<EngineSettings> {
    let bytes = std::fs::read(path).ok()?;
    match serde_json::from_slice::<EngineSettings>(&bytes) {
        Ok(settings) => Some(settings),
        Err(err) => {
            // A broken file must never refuse the engine over one toggle.
            tracing::warn!(path = %path.display(), error = %err, "engine settings unreadable");
            None
        }
    }
}

/// Write atomically (temp file + rename): a crash mid-write must never
/// leave a truncated `engine-settings.json` that boots as defaults — the
/// Auto-resume setting is durable intent.
fn write_file(path: &Path, settings: &EngineSettings) -> std::io::Result<()> {
    let bytes = serde_json::to_vec_pretty(settings)
        .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidData, err))?;
    write_file_atomic(path, &bytes)
}

/// Temp-file-then-rename, with the temp file in the target's directory so
/// the rename stays on one filesystem.
fn write_file_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let dir = path.parent().unwrap_or_else(|| path.as_path());
    let mut staged = tempfile::NamedTempFile::new_in(dir)?;
    staged.write_all(bytes)?;
    staged.flush()?;
    staged.as_file().sync_all()?;
    staged.persist(path).map_err(|err| err.error)
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path() -> PathBuf {
        tempfile::tempdir().unwrap().keep().join("engine-settings.json")
    }

    #[test]
    fn defaults_are_off_and_round_trip_through_disk() {
        let path = temp_path();
        let store = EngineSettingsStore::open(Some(path.clone()));
        assert_eq!(store.get(), EngineSettings::default());
        assert!(!store.get().auto_resume_limited);

        store
            .set(EngineSettings {
                auto_resume_limited: true,
            })
            .unwrap();
        let reloaded = EngineSettingsStore::open(Some(path));
        assert!(reloaded.get().auto_resume_limited);

        // Back off again.
        reloaded.set(EngineSettings::default()).unwrap();
        assert!(!EngineSettingsStore::open(Some(path)).get().auto_resume_limited);
    }

    #[test]
    fn missing_and_broken_files_default_off_and_old_settings_gain_defaults() {
        // Missing.
        let missing = EngineSettingsStore::open(Some(temp_path()));
        assert_eq!(missing.get(), EngineSettings::default());
        // Broken.
        let broken = temp_path();
        std::fs::write(&broken, b"{not json").unwrap();
        assert_eq!(EngineSettingsStore::open(Some(broken)).get(), EngineSettings::default());
        // A file from an engine with fewer settings still loads.
        let sparse = temp_path();
        std::fs::write(&sparse, b"{}").unwrap();
        assert_eq!(EngineSettingsStore::open(Some(sparse)).get(), EngineSettings::default());
        // A file from a newer engine keeps its known fields.
        let future = temp_path();
        std::fs::write(&future, br#"{"autoResumeLimited":true,"someFutureField":9}"#).unwrap();
        assert!(EngineSettingsStore::open(Some(future)).get().auto_resume_limited);
    }

    #[test]
    fn a_failed_write_never_wedges_the_in_memory_toggle() {
        // A path whose parent does not exist cannot be written.
        let store = EngineSettingsStore::open(Some(PathBuf::from("/nonexistent/engine-settings.json")));
        assert!(store
            .set(EngineSettings {
                auto_resume_limited: true,
            })
            .is_err());
        assert!(store.get().auto_resume_limited);
    }
}
