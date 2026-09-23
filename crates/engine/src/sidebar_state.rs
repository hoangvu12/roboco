//! Engine-local sidebar organization state: pinned sessions + custom sections.
//!
//! Upstream zeron synced these through the cloud registry (pin intents with
//! fractional ordering keys, per-item `changeSidebarPin` ops). Roboco removed
//! that sync layer on purpose (ADR 0004: engine-local data, no cloud). This
//! module re-homes the *state* engine-side on the project-actions precedent:
//! the owning engine is the only authority that persists it, every mutation
//! is an ordered-list replace that replies with the fresh snapshot, and a
//! watch channel fans every change out to all connected clients. Conflicts
//! resolve last-write-wins — there is no cloud merge, and nothing here ever
//! leaves the engine's own data dir.
//!
//! Buckets are keyed by the workspace profile key the clients compute from
//! the engine's scope + device id, so a browser paired to the same engine
//! mirrors the desktop's sidebar exactly, while a second engine keeps its
//! own separate slice.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use serde::{Deserialize, Serialize};
use tokio::sync::watch;
use uuid::Uuid;

use roboco_proto::{SidebarSection, SidebarStateSnapshot};

use crate::EngineError;

/// Admission limit for NEW pins — mirrors the desktop's
/// `sidebar_pins::MAX_SIDEBAR_PINS`. An ordered-list replace may keep an
/// existing overflow (clients stay reorderable and removable), but adding a
/// pin past the limit is refused.
pub const MAX_SIDEBAR_PINS: usize = 200;
/// `SIDEBAR_SECTION_NAME_MAX` — `submit_section_dialog`'s name bound.
pub const SIDEBAR_SECTION_NAME_MAX_CHARS: usize = 120;
/// A profile's section count bound (same scale as `MAX_PROJECT_ACTIONS`).
pub const MAX_SIDEBAR_SECTIONS: usize = 50;

const STORE_FILE: &str = "sidebar-state.json";
const STORE_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SidebarStateFile {
    version: u32,
    #[serde(default)]
    pins_by_profile: BTreeMap<String, Vec<String>>,
    #[serde(default)]
    sections_by_profile: BTreeMap<String, Vec<SidebarSection>>,
}

struct SidebarStateInner {
    path: PathBuf,
    state: Mutex<SidebarStateFile>,
    changes: watch::Sender<SidebarStateSnapshot>,
}

/// Engine-local storage for sidebar pins and custom sections — the
/// project-actions pattern applied to chat-organization state.
#[derive(Clone)]
pub struct SidebarStateStore {
    inner: Arc<SidebarStateInner>,
}

impl SidebarStateStore {
    /// Open (or start) the store under the engine profile root. A missing,
    /// corrupt, or future-versioned file starts empty — sidebar organization is
    /// presentation state, never worth failing engine boot over.
    pub fn open(profile_store_root: &Path) -> Result<Self, EngineError> {
        std::fs::create_dir_all(profile_store_root)?;
        let path = profile_store_root.join(STORE_FILE);
        let file = match std::fs::read(&path) {
            Ok(bytes) => match serde_json::from_slice::<SidebarStateFile>(&bytes) {
                Ok(file) if file.version == STORE_VERSION => heal(file),
                Ok(file) => {
                    tracing::warn!(
                        path = %path.display(),
                        version = file.version,
                        "unsupported sidebar state store version; starting empty"
                    );
                    empty_file()
                }
                Err(err) => {
                    tracing::warn!(
                        path = %path.display(),
                        error = %err,
                        "invalid sidebar state store; starting empty"
                    );
                    empty_file()
                }
            },
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => empty_file(),
            Err(err) => return Err(err.into()),
        };
        let (changes, _) = watch::channel(file.snapshot());
        Ok(Self {
            inner: Arc::new(SidebarStateInner {
                path,
                state: Mutex::new(file),
                changes,
            }),
        })
    }

    /// The healed snapshot clients render.
    pub fn snapshot(&self) -> SidebarStateSnapshot {
        lock(&self.inner.state).snapshot()
    }

    /// Live changes — current value first, then every mutation.
    pub fn subscribe(&self) -> watch::Receiver<SidebarStateSnapshot> {
        self.inner.changes.subscribe()
    }

    /// Ordered-list replace for one profile's pins. Last write wins; the
    /// reply is the fresh snapshot so the writer self-corrects.
    pub fn set_pins(
        &self,
        profile_key: &str,
        mut session_ids: Vec<String>,
    ) -> Result<SidebarStateSnapshot, EngineError> {
        let mut next = lock(&self.inner.state).clone();
        let current = next.pins_by_profile.get(profile_key).cloned();
        heal_pin_list(&mut session_ids);
        validate_pin_list(current.as_deref(), &session_ids)?;
        if session_ids.is_empty() {
            next.pins_by_profile.remove(profile_key);
        } else {
            next.pins_by_profile.insert(profile_key.to_string(), session_ids);
        }
        Ok(self.commit(next))
    }

    /// Ordered-list replace for one profile's sections.
    pub fn set_sections(
        &self,
        profile_key: &str,
        sections: Vec<SidebarSection>,
    ) -> Result<SidebarStateSnapshot, EngineError> {
        let mut next = lock(&self.inner.state).clone();
        let sections = heal_sections(sections)?;
        if sections.is_empty() {
            next.sections_by_profile.remove(profile_key);
        } else {
            next.sections_by_profile
                .insert(profile_key.to_string(), sections);
        }
        Ok(self.commit(next))
    }

    /// Persist the next state under the lock, publish it, and return it.
    fn commit(&self, next: SidebarStateFile) -> SidebarStateSnapshot {
        let snapshot = {
            let mut state = lock(&self.inner.state);
            persist(&self.inner.path, &next).unwrap_or_else(|err| {
                // The in-memory state stays authoritative; a failed write
                // retries with the next mutation.
                tracing::warn!(path = %self.inner.path.display(), error = %err, "sidebar state persist failed");
            });
            *state = next;
            state.snapshot()
        };
        self.inner.changes.send_replace(snapshot.clone());
        snapshot
    }
}

impl SidebarStateFile {
    fn snapshot(&self) -> SidebarStateSnapshot {
        SidebarStateSnapshot {
            pins_by_profile: self.pins_by_profile.clone(),
            sections_by_profile: self.sections_by_profile.clone(),
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Drop duplicate and empty pin ids in place (the shape the clients heal on
/// their side; the engine is the last line of defense).
fn heal_pin_list(ids: &mut Vec<String>) {
    let mut seen = std::collections::HashSet::new();
    ids.retain(|id| !id.is_empty() && seen.insert(id.clone()));
}

/// The desktop's admission rule: an overflow is only refused when it adds a
/// NEW pin — existing overflow stays valid, reorderable, and removable.
fn validate_pin_list(current: Option<&[String]>, next: &[String]) -> Result<(), EngineError> {
    if next.len() > MAX_SIDEBAR_PINS
        && let Some(current) = current
        && next.iter().any(|id| !current.contains(id))
    {
        return Err(EngineError::Other(format!(
            "You can pin up to {MAX_SIDEBAR_PINS} sessions"
        )));
    }
    Ok(())
}

/// Heal a section list to the stored shape: non-empty ids and names, unique
/// section ids, deduped membership, name length bound. Empty sections are
/// retained (the client renders their \"Drop sessions here\" target).
fn heal_sections(sections: Vec<SidebarSection>) -> Result<Vec<SidebarSection>, EngineError> {
    if sections.len() > MAX_SIDEBAR_SECTIONS {
        return Err(EngineError::Other(format!(
            "A workspace can have at most {MAX_SIDEBAR_SECTIONS} sidebar sections"
        )));
    }
    let mut seen_ids = std::collections::HashSet::new();
    let mut healed = Vec::with_capacity(sections.len());
    for mut section in sections {
        if section.id.is_empty()
            || section.name.trim().is_empty()
            || !seen_ids.insert(section.id.clone())
        {
            continue;
        }
        section.name = section.name.trim().to_string();
        if section.name.chars().count() > SIDEBAR_SECTION_NAME_MAX_CHARS {
            return Err(EngineError::Other(format!(
                "Section names must not exceed {SIDEBAR_SECTION_NAME_MAX_CHARS} characters"
            )));
        }
        heal_pin_list(&mut section.session_ids);
        healed.push(section);
    }
    Ok(healed)
}

/// Repair a loaded file in place (duplicates/empties heal out bucket by
/// bucket, exactly like the clients' load-time healing).
fn heal(mut file: SidebarStateFile) -> SidebarStateFile {
    for ids in file.pins_by_profile.values_mut() {
        heal_pin_list(ids);
    }
    file.pins_by_profile
        .retain(|key, ids| !key.is_empty() && !ids.is_empty());
    for sections in file.sections_by_profile.values_mut() {
        // Load-time healing never fails: junk sections drop out one by one.
        let healed = heal_sections(std::mem::take(sections)).unwrap_or_default();
        *sections = healed;
    }
    file.sections_by_profile
        .retain(|key, sections| !key.is_empty() && !sections.is_empty());
    file.version = STORE_VERSION;
    file
}

/// The versioned empty store — `Default` would stamp `version: 0`.
fn empty_file() -> SidebarStateFile {
    SidebarStateFile {
        version: STORE_VERSION,
        pins_by_profile: BTreeMap::new(),
        sections_by_profile: BTreeMap::new(),
    }
}

fn persist(path: &Path, file: &SidebarStateFile) -> Result<(), EngineError> {
    let mut bytes = serde_json::to_vec_pretty(file)
        .map_err(|err| EngineError::Other(format!("serialize sidebar state: {err}")))?;
    bytes.push(b'\n');
    let parent = path
        .parent()
        .ok_or_else(|| EngineError::Other("sidebar state store has no parent".into()))?;
    std::fs::create_dir_all(parent)?;
    let temp_path = parent.join(format!(".{STORE_FILE}.tmp-{}", Uuid::new_v4()));
    let result = (|| -> Result<(), EngineError> {
        let mut temp = std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temp_path)?;
        std::io::Write::write_all(&mut temp, &bytes)?;
        temp.sync_all()?;
        std::fs::rename(&temp_path, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temp_path);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roots() -> (tempfile::TempDir, PathBuf) {
        let temp = tempfile::tempdir().unwrap();
        let store = temp.path().join("profile");
        (temp, store)
    }

    fn section(id: &str, name: &str, sessions: &[&str]) -> SidebarSection {
        SidebarSection {
            id: id.into(),
            name: name.into(),
            session_ids: sessions.iter().map(|s| s.to_string()).collect(),
            collapsed: false,
        }
    }

    #[test]
    fn pins_and_sections_round_trip_through_disk() {
        let (_temp, store_root) = roots();
        let store = SidebarStateStore::open(&store_root).unwrap();
        store.set_pins("local", vec!["a".into(), "b".into()]).unwrap();
        store
            .set_sections("local", vec![section("s1", "Focus", &["a"]), section("s2", "Later", &[])])
            .unwrap();

        let reopened = SidebarStateStore::open(&store_root).unwrap();
        let snapshot = reopened.snapshot();
        assert_eq!(
            snapshot.pins_by_profile["local"],
            vec!["a".to_string(), "b".to_string()]
        );
        assert_eq!(snapshot.sections_by_profile["local"].len(), 2);
        assert_eq!(snapshot.sections_by_profile["local"][0].session_ids, ["a"]);
        // Empty sections are retained on disk (their drop target survives).
        assert!(snapshot.sections_by_profile["local"][1].session_ids.is_empty());
    }

    #[test]
    fn corrupt_or_future_store_starts_empty_and_repairs_on_write() {
        let (_temp, store_root) = roots();
        std::fs::create_dir_all(&store_root).unwrap();
        std::fs::write(store_root.join(STORE_FILE), b"not json").unwrap();
        let store = SidebarStateStore::open(&store_root).unwrap();
        assert!(store.snapshot().pins_by_profile.is_empty());
        store.set_pins("local", vec!["a".into()]).unwrap();
        assert_eq!(
            SidebarStateStore::open(&store_root)
                .unwrap()
                .snapshot()
                .pins_by_profile["local"],
            ["a"]
        );

        std::fs::write(
            store_root.join(STORE_FILE),
            serde_json::json!({"version": 99, "pinsByProfile": {"local": ["x"]}}).to_string(),
        )
        .unwrap();
        assert!(SidebarStateStore::open(&store_root)
            .unwrap()
            .snapshot()
            .pins_by_profile
            .is_empty());
    }

    #[test]
    fn duplicate_and_empty_ids_heal_out_before_persisting() {
        let (_temp, store_root) = roots();
        let store = SidebarStateStore::open(&store_root).unwrap();
        store
            .set_pins("local", vec!["a".into(), "".into(), "a".into(), "b".into()])
            .unwrap();
        assert_eq!(
            store.snapshot().pins_by_profile["local"],
            vec!["a".to_string(), "b".to_string()]
        );
        // Emptying the bucket removes it (the clients drop it the same way).
        store.set_pins("local", vec![]).unwrap();
        assert!(!store.snapshot().pins_by_profile.contains_key("local"));
    }

    #[test]
    fn pin_overflow_admits_existing_pins_but_refuses_new_ones() {
        let (_temp, store_root) = roots();
        let store = SidebarStateStore::open(&store_root).unwrap();
        let full: Vec<String> = (0..MAX_SIDEBAR_PINS).map(|i| i.to_string()).collect();
        store.set_pins("local", full.clone()).unwrap();
        // Replacing the full list with itself stays valid (reorder/remove).
        store.set_pins("local", full.clone()).unwrap();
        let mut overflow = full;
        overflow.push("new".into());
        assert!(store.set_pins("local", overflow).is_err());
    }

    #[test]
    fn section_validation_mirrors_the_clients() {
        let (_temp, store_root) = roots();
        let store = SidebarStateStore::open(&store_root).unwrap();
        // Duplicate ids and empty names heal out; junk membership drops.
        store
            .set_sections(
                "local",
                vec![
                    section("s1", "Focus", &["a", "a", ""]),
                    section("s1", "Dup", &[]),
                    section("", "No id", &[]),
                ],
            )
            .unwrap();
        let sections = &store.snapshot().sections_by_profile["local"];
        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].session_ids, ["a"]);

        // Over-long names and too many sections are refused.
        let long = "x".repeat(SIDEBAR_SECTION_NAME_MAX_CHARS + 1);
        assert!(store
            .set_sections("local", vec![section("s9", &long, &[])])
            .is_err());
        let many: Vec<SidebarSection> = (0..MAX_SIDEBAR_SECTIONS + 1)
            .map(|i| section(&format!("s{i}"), "S", &[]))
            .collect();
        assert!(store.set_sections("local", many).is_err());
    }

    #[test]
    fn profile_roots_do_not_share_state() {
        let temp = tempfile::tempdir().unwrap();
        let a = SidebarStateStore::open(&temp.path().join("profile-a")).unwrap();
        let b = SidebarStateStore::open(&temp.path().join("profile-b")).unwrap();
        a.set_pins("local", vec!["pin".into()]).unwrap();
        assert!(b.snapshot().pins_by_profile.is_empty());
    }

    #[test]
    fn mutations_publish_the_watch_channel() {
        let (_temp, store_root) = roots();
        let store = SidebarStateStore::open(&store_root).unwrap();
        let mut rx = store.subscribe();
        assert_eq!(rx.borrow_and_update().pins_by_profile.len(), 0);
        store.set_pins("local", vec!["a".into()]).unwrap();
        assert!(rx.has_changed().unwrap());
        assert_eq!(rx.borrow_and_update().pins_by_profile["local"], ["a"]);
        store.set_sections("local", vec![section("s", "S", &["a"])]).unwrap();
        assert!(rx.has_changed().unwrap());
        assert_eq!(rx.borrow_and_update().sections_by_profile["local"].len(), 1);
    }

    /// Two clients paired to one engine mirror live, mutations reply with
    /// the fresh snapshot, and concurrent writes resolve last-write-wins.
    /// The scripted two-client surface the ticket's verification budget
    /// allows — no dev server, no live pairing.
    #[tokio::test(flavor = "current_thread")]
    async fn sidebar_state_two_client_mirror_and_lww() {
        use roboco_rpc::{RpcService, memory_client, methods};

        let temp = tempfile::tempdir().unwrap();
        let core = crate::EngineCore::assemble(
            temp.path(),
            std::sync::Arc::new(crate::HarnessRegistry::new()),
            roboco_proto::HarnessId::Mock,
        )
        .unwrap();
        let rpc = core.rpc_service();
        let client_a = memory_client(rpc.clone());
        let client_b = memory_client(rpc.clone());

        let mut watch_a = client_a
            .subscribe(methods::WATCH_SIDEBAR_STATE, serde_json::json!({}))
            .await
            .unwrap();
        let mut watch_b = client_b
            .subscribe(methods::WATCH_SIDEBAR_STATE, serde_json::json!({}))
            .await
            .unwrap();

        // The stream's first item is the current (empty) state.
        let first: SidebarStateSnapshot =
            serde_json::from_value(watch_a.recv().await.unwrap()).unwrap();
        assert!(first.pins_by_profile.is_empty());
        let _: SidebarStateSnapshot = serde_json::from_value(watch_b.recv().await.unwrap()).unwrap();

        // Client A writes pins; the reply is the fresh snapshot…
        let reply: SidebarStateSnapshot = client_a
            .call_as(
                methods::SET_SIDEBAR_PINS,
                serde_json::json!({"profileKey": "local", "sessionIds": ["chat-a"]}),
            )
            .await
            .unwrap();
        assert_eq!(reply.pins_by_profile["local"], ["chat-a".to_string()]);
        // …and client B's watch mirrors it without any B-side action.
        let mirrored: SidebarStateSnapshot =
            serde_json::from_value(watch_b.recv().await.unwrap()).unwrap();
        assert_eq!(mirrored.pins_by_profile["local"], ["chat-a".to_string()]);
        let own: SidebarStateSnapshot =
            serde_json::from_value(watch_a.recv().await.unwrap()).unwrap();
        assert_eq!(own.pins_by_profile["local"], ["chat-a".to_string()]);

        // Sections mirror the same way.
        client_a
            .call(
                methods::SET_SIDEBAR_SECTIONS,
                serde_json::json!({"profileKey": "local", "sections": [
                    {"id": "s1", "name": "Focus", "sessionIds": ["chat-a"], "collapsed": false}
                ]}),
            )
            .await
            .unwrap();
        let mirrored: SidebarStateSnapshot =
            serde_json::from_value(watch_b.recv().await.unwrap()).unwrap();
        assert_eq!(mirrored.sections_by_profile["local"].len(), 1);
        let _: SidebarStateSnapshot = serde_json::from_value(watch_a.recv().await.unwrap()).unwrap();

        // LWW: B replaces the list after A; the last write to arrive wins…
        client_b
            .call(
                methods::SET_SIDEBAR_PINS,
                serde_json::json!({"profileKey": "local", "sessionIds": ["chat-b", "chat-c"]}),
            )
            .await
            .unwrap();
        let mirrored: SidebarStateSnapshot =
            serde_json::from_value(watch_a.recv().await.unwrap()).unwrap();
        assert_eq!(
            mirrored.pins_by_profile["local"],
            ["chat-b".to_string(), "chat-c".to_string()]
        );
        let _: SidebarStateSnapshot = serde_json::from_value(watch_b.recv().await.unwrap()).unwrap();

        // …and it survives a restart: the engine's store is the authority.
        drop(watch_a);
        drop(watch_b);
        drop(client_a);
        drop(client_b);
        drop(rpc);
        drop(core);
        let reopened = crate::EngineCore::assemble(
            temp.path(),
            std::sync::Arc::new(crate::HarnessRegistry::new()),
            roboco_proto::HarnessId::Mock,
        )
        .unwrap();
        let snapshot = reopened.sidebar_state.snapshot();
        assert_eq!(
            snapshot.pins_by_profile["local"],
            ["chat-b".to_string(), "chat-c".to_string()]
        );
        assert_eq!(snapshot.sections_by_profile["local"].len(), 1);
    }

    /// The new surface rides the existing device-addressed routing rule:
    /// a `targetDeviceId` that matches this engine is tolerated (the
    /// clients strip it at the socket — an engine never sees it in
    /// practice), a foreign one fails closed before the store is touched.
    #[tokio::test(flavor = "current_thread")]
    async fn sidebar_state_requests_honor_device_routing() {
        use roboco_rpc::{RpcService, memory_client, methods};

        let temp = tempfile::tempdir().unwrap();
        let core = crate::EngineCore::assemble(
            temp.path(),
            std::sync::Arc::new(crate::HarnessRegistry::new()),
            roboco_proto::HarnessId::Mock,
        )
        .unwrap();
        let device_id = core.device_id.clone();
        let rpc = core.rpc_service();
        let client = memory_client(rpc);

        // A foreign target fails closed before the mutation lands.
        let error = client
            .call(
                methods::SET_SIDEBAR_PINS,
                serde_json::json!({"profileKey": "local", "sessionIds": ["x"], "targetDeviceId": "other-device"}),
            )
            .await
            .unwrap_err();
        assert!(error.to_string().contains("not connected"));
        assert!(core.sidebar_state.snapshot().pins_by_profile.is_empty());

        // This engine's own id is accepted and ignored (routing ends here).
        let reply: SidebarStateSnapshot = client
            .call_as(
                methods::SET_SIDEBAR_PINS,
                serde_json::json!({"profileKey": "local", "sessionIds": ["x"], "targetDeviceId": device_id}),
            )
            .await
            .unwrap();
        assert_eq!(reply.pins_by_profile["local"], ["x".to_string()]);
    }
}
