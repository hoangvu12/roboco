//! Resume at reset (orchestration v2, ticket 07): the durable continuation a
//! Limited chat's **Resume at reset** action arms. The engine — not the
//! client — owns it: at the limit's reset time it sends the continue prompt
//! exactly like the manual Resume (a normal run, queued messages behind it).
//!
//! One pending continuation per chat. It is cancelled by a newer message to
//! the chat, by archiving it, or by deleting it — the user's newer intent
//! always wins. It survives engine restarts (this file), and one whose time
//! passed while the engine was down fires after boot.
//!
//! This module is the store: the entries, their JSON persistence under the
//! engine data dir, and the disarm flags the timers check. The sessions
//! engine owns arming, firing and the session-row publishing
//! (`sessions.rs`).
//!
//! The loop guard: a fired continuation is consumed, never re-armed from its
//! own failure. A *new* limit record re-arms only when its reset time is
//! strictly later than the last continuation the chat fired (see
//! [`ScheduledResumes::advances`]) — a record whose reset does not advance
//! past the fired one is the same wall, and re-arming it would fire straight
//! back into the limit in a loop.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use roboco_proto::ScheduledResume;
use serde::{Deserialize, Serialize};

use crate::now_ms;

/// The durable shape of `scheduled-resumes.json`.
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct ScheduledResumeFile {
    /// The pending continuations, one per chat.
    resumes: Vec<ScheduledResumeRecord>,
    /// When each chat's last continuation FIRED (epoch ms) — the loop guard's
    /// progress floor. Survives restarts so a lying reset time cannot loop
    /// across them either.
    fired: HashMap<String, i64>,
}

/// One pending continuation, as persisted.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScheduledResumeRecord {
    chat_id: String,
    /// Epoch ms when the continuation fires.
    fire_at: i64,
}

/// One in-memory entry: the record plus the flag its timer checks.
#[derive(Debug)]
struct Entry {
    fire_at: i64,
    /// Set when the entry is replaced or cancelled: its timer wakes, sees
    /// the flag, and goes away without firing.
    disarmed: Arc<AtomicBool>,
}

/// The engine's pending Resume-at-reset continuations. In-memory state
/// mirrors the JSON store; `path = None` (bare engines, tests) keeps
/// everything in memory. One instance lives on the sessions engine's
/// `Inner`.
#[derive(Debug, Default)]
pub(crate) struct ScheduledResumes {
    entries: Mutex<HashMap<String, Entry>>,
    fired: Mutex<HashMap<String, i64>>,
    path: Mutex<Option<PathBuf>>,
}

impl ScheduledResumes {
    /// Persist to `path` (loading anything already there) — engine assembly.
    pub(crate) fn set_path(&self, path: PathBuf) {
        let loaded = load_file(&path);
        {
            let mut entries = lock(&self.entries);
            let mut fired = lock(&self.fired);
            // Data only: boot recovery spawns the timers separately, after
            // the doc host is wired (firing needs it).
            for record in loaded.resumes {
                entries.insert(
                    record.chat_id.clone(),
                    Entry {
                        fire_at: record.fire_at,
                        // No timer owns this entry yet — the disarm flag is
                        // for the timer boot recovery is about to spawn.
                        disarmed: Arc::new(AtomicBool::new(false)),
                    },
                );
            }
            fired.extend(loaded.fired);
        }
        *lock(&self.path) = Some(path);
        self.persist();
    }

    /// The pending continuation for `chat`, when one is armed.
    pub(crate) fn scheduled(&self, chat_id: &str) -> Option<ScheduledResume> {
        lock(&self.entries)
            .get(chat_id)
            .map(|entry| ScheduledResume {
                fire_at: entry.fire_at,
            })
    }

    /// Arm (or re-arm) `chat`'s continuation at `fire_at`, disarming whatever
    /// timer the replaced entry owned. Returns the new entry's disarm flag —
    /// the timer the caller spawns for it.
    pub(crate) fn arm(&self, chat_id: &str, fire_at: i64) -> Arc<AtomicBool> {
        let disarmed = Arc::new(AtomicBool::new(false));
        {
            let mut entries = lock(&self.entries);
            if let Some(old) = entries.insert(
                chat_id.to_string(),
                Entry {
                    fire_at,
                    disarmed: disarmed.clone(),
                },
            ) {
                old.disarmed.store(true, Ordering::Release);
            }
        }
        self.persist();
        disarmed
    }

    /// Cancel `chat`'s continuation: remove, persist, disarm its timer.
    /// `true` when there was one to cancel.
    pub(crate) fn cancel(&self, chat_id: &str) -> bool {
        let removed = lock(&self.entries).remove(chat_id);
        if let Some(old) = &removed {
            old.disarmed.store(true, Ordering::Release);
        }
        if removed.is_some() {
            self.persist();
        }
        removed.is_some()
    }

    /// Consume the continuation this timer owns: remove and persist it —
    /// BEFORE anything async happens, so a crash mid-fire can never
    /// double-schedule. `false` when the entry is gone or was replaced by a
    /// different one (this timer is obsolete).
    pub(crate) fn consume(&self, chat_id: &str, fire_at: i64) -> bool {
        let consumed = {
            let mut entries = lock(&self.entries);
            match entries.get(chat_id) {
                Some(entry) if entry.fire_at == fire_at => {
                    entries.remove(chat_id);
                    true
                }
                _ => false,
            }
        };
        if consumed {
            self.persist();
        }
        consumed
    }

    /// Put a consumed continuation back after a shutdown raced its dispatch:
    /// the next boot re-arms it (overdue) instead of losing the user's
    /// armed continuation to a graceful shutdown.
    pub(crate) fn requeue(&self, chat_id: &str, fire_at: i64) {
        let disarmed = Arc::new(AtomicBool::new(false));
        if let Some(old) = lock(&self.entries).insert(
            chat_id.to_string(),
            Entry {
                fire_at,
                disarmed,
            },
        ) {
            old.disarmed.store(true, Ordering::Release);
        }
        self.persist();
    }

    /// Every pending continuation with its disarm flag, for boot recovery's
    /// timers.
    pub(crate) fn pending(&self) -> Vec<(String, i64, Arc<AtomicBool>)> {
        lock(&self.entries)
            .iter()
            .map(|(chat, entry)| (chat.clone(), entry.fire_at, entry.disarmed.clone()))
            .collect()
    }

    /// Stamp that `chat`'s continuation fired at `fire_at` — the progress
    /// floor [`Self::advances`] re-arms must beat.
    pub(crate) fn note_fired(&self, chat_id: &str, fire_at: i64) {
        lock(&self.fired)
            .entry(chat_id.to_string())
            .and_modify(|at| *at = (*at).max(fire_at))
            .or_insert(fire_at);
        self.persist();
    }

    /// The loop guard: does `resets_at` advance past everything the chat
    /// already fired (and the present)? A limit record whose reset does not
    /// beat the last fired continuation is the same wall — re-arming it
    /// would loop. See the module docs.
    pub(crate) fn advances(&self, chat_id: &str, resets_at: i64) -> bool {
        let floor = lock(&self.fired)
            .get(chat_id)
            .copied()
            .unwrap_or(i64::MIN);
        resets_at > floor.max(now_ms())
    }

    fn persist(&self) {
        // Snapshot under the locks (both maps in one acquisition, so an
        // arming racing this persist cannot write a file that mixes
        // pre- and post-arm state), then write outside them.
        let (path, file) = {
            let path = lock(&self.path).clone();
            let file = {
                let entries = lock(&self.entries);
                let fired = lock(&self.fired);
                ScheduledResumeFile {
                    resumes: entries
                        .iter()
                        .map(|(chat, entry)| ScheduledResumeRecord {
                            chat_id: chat.clone(),
                            fire_at: entry.fire_at,
                        })
                        .collect(),
                    fired: fired.clone(),
                }
            };
            (path, file)
        };
        let Some(path) = path else {
            return;
        };
        if let Err(err) = write_file(&path, &file) {
            tracing::warn!(path = %path.display(), error = %err, "scheduled-resume persist failed");
        }
    }
}

fn load_file(path: &Path) -> ScheduledResumeFile {
    std::fs::read(path)
        .map_err(|err| err.to_string())
        .and_then(|bytes| {
            serde_json::from_slice::<ScheduledResumeFile>(&bytes).map_err(|err| err.to_string())
        })
        .unwrap_or_else(|err| {
            // Missing file = fresh engine; broken file = keep going with
            // nothing armed (never refuse the engine over one feature).
            if path.exists() {
                tracing::warn!(path = %path.display(), error = %err, "scheduled-resume store unreadable");
            }
            ScheduledResumeFile::default()
        })
}

/// Write atomically (temp file + rename): a crash or power loss mid-write
/// must never leave a truncated `scheduled-resumes.json` that boots as
/// "unreadable — nothing armed", silently dropping every pending
/// continuation. The rename lands whole or not at all.
fn write_file(path: &Path, file: &ScheduledResumeFile) -> std::io::Result<()> {
    let bytes = serde_json::to_vec_pretty(file)
        .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidData, err))?;
    write_file_atomic(path, &bytes)
}

/// Temp-file-then-rename, with the temp file in the target's directory so
/// the rename stays on one filesystem.
fn write_file_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let dir = path.parent().unwrap_or(Path::new("."));
    let mut staged = tempfile::NamedTempFile::new_in(dir)?;
    staged.write_all(bytes)?;
    staged.flush()?;
    staged.as_file().sync_all()?;
    staged.persist(path).map(drop).map_err(|err| err.error)
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path() -> PathBuf {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.keep().join("scheduled-resumes.json");
        path
    }

    #[test]
    fn arm_cancel_and_consume_track_one_entry_per_chat() {
        let store = ScheduledResumes::default();
        assert!(store.scheduled("chat").is_none());
        assert!(!store.cancel("chat"));

        let first = store.arm("chat", 1_000);
        assert_eq!(store.scheduled("chat").unwrap().fire_at, 1_000);
        // Re-arming replaces: the old timer's flag flips.
        let second = store.arm("chat", 2_000);
        assert!(first.load(Ordering::Acquire));
        assert!(!second.load(Ordering::Acquire));
        assert_eq!(store.scheduled("chat").unwrap().fire_at, 2_000);

        // A timer for the replaced entry cannot consume the new one.
        assert!(!store.consume("chat", 1_000));
        assert_eq!(store.scheduled("chat").unwrap().fire_at, 2_000);
        // The owner consumes.
        assert!(store.consume("chat", 2_000));
        assert!(store.scheduled("chat").is_none());
        // Taken is taken.
        assert!(!store.consume("chat", 2_000));
    }

    #[test]
    fn cancel_disarms_the_live_timer() {
        let store = ScheduledResumes::default();
        let disarmed = store.arm("chat", 1_000);
        assert!(store.cancel("chat"));
        assert!(disarmed.load(Ordering::Acquire));
        assert!(store.scheduled("chat").is_none());
        // Nothing left to cancel.
        assert!(!store.cancel("chat"));
    }

    #[test]
    fn entries_and_fired_floors_survive_a_store_reload() {
        // `advances` also guards against the present, so the resets sit in
        // the future.
        let base = now_ms() + 3_600_000;
        let path = temp_path();
        let first = ScheduledResumes::default();
        first.set_path(path.clone());
        first.arm("chat-live", base);
        first.arm("chat-done", base + 1_000);
        first.consume("chat-done", base + 1_000);
        first.note_fired("chat-done", base + 1_000);

        let reloaded = ScheduledResumes::default();
        reloaded.set_path(path);
        assert_eq!(reloaded.scheduled("chat-live").unwrap().fire_at, base);
        assert!(reloaded.scheduled("chat-done").is_none());
        assert_eq!(reloaded.pending().len(), 1);
        // The fired floor survives the restart.
        assert!(reloaded.advances("chat-done", base + 2_000));
        assert!(!reloaded.advances("chat-done", base + 1_000));
        // A reset in the past never advances.
        assert!(!reloaded.advances("chat-live", 1));
    }

    #[test]
    fn a_broken_store_file_loads_as_nothing_armed() {
        let path = temp_path();
        std::fs::write(&path, b"{not json").unwrap();
        let store = ScheduledResumes::default();
        store.set_path(path.clone());
        assert!(store.pending().is_empty());
        // And it heals: the next persist writes a valid file.
        store.arm("chat", 9_000);
        let healed = ScheduledResumes::default();
        healed.set_path(path);
        assert_eq!(healed.scheduled("chat").unwrap().fire_at, 9_000);
    }

    #[test]
    fn requeue_restores_a_consumed_entry_for_the_next_boot() {
        let path = temp_path();
        let store = ScheduledResumes::default();
        store.set_path(path.clone());
        store.arm("chat", 5_000);
        assert!(store.consume("chat", 5_000));
        store.requeue("chat", 5_000);
        let reloaded = ScheduledResumes::default();
        reloaded.set_path(path);
        assert_eq!(reloaded.scheduled("chat").unwrap().fire_at, 5_000);
    }
}
