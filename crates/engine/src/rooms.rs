//! Engine-local room state (ADR 0011): who may be introduced.
//!
//! A room is a hostless reachability group — a shared secret plus an address
//! book, copied to every member. Rooms share reachability, never data
//! (ADR 0004): nothing here syncs, relays, or owns a chat.
//!
//! Storage follows the `pairing.sqlite3` precedent: `rooms` and
//! `room_presentations` tables in that same file (the pairing data domain —
//! who is allowed in), created `IF NOT EXISTS` with no migration machinery.
//! Each room holds `{id, label, SHA-256 verifier, book}`:
//!
//! - the id is derived from the secret (`roboco_proto::room::room_id`), so
//!   every engine and client holding the same secret addresses the same room
//!   with no coordinator;
//! - only a domain-tagged SHA-256 **verifier** of the secret is ever at rest
//!   (the pairing invariant — no usable room secret persists on an engine);
//!   the secret is hashed and discarded at every boundary;
//! - the book's member addresses are transport secrets, persisted under
//!   `{data_dir}/rooms/{id}/book.json` with the same 0600 discipline as relay
//!   `connect.json` entries (`tailcat::write_private`).
//!
//! Presentations (`POST /room/present`) verify against the verifier and mint
//! ordinary sessions — listed, labeled, revocable like any other. They are
//! repeatable: re-presenting with the same label reuses the room's session row
//! (a fresh credential, the same id), so retries and self-healing never mint
//! unbounded sessions. Failed presentations are throttled and logged
//! shape-only.
//!
//! Joining never touches remote-access settings: unlike
//! `CreateTailcatInvite`, which force-enables and persists Tailcat, a room only
//! works on engines that are already reachable — there is no settings
//! escalation anywhere in this module.

use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use roboco_proto::room::{RoomBook, RoomInvite, RoomMember};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::pairing::SessionGrant;

/// At most this many refused presentations inside [`PRESENTATION_FAILURE_WINDOW`]
/// before further failures answer throttled.
const MAX_PRESENTATION_FAILURES: usize = 5;
const PRESENTATION_FAILURE_WINDOW: Duration = Duration::from_secs(60);

/// A joined room as the store reports it (the CLI and tests read this shape;
/// the verifier never leaves the store).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomRecord {
    pub id: String,
    pub label: String,
    pub joined_at: i64,
    pub book: RoomBook,
}

/// The outcome of one presentation attempt.
///
/// `Debug` never prints the granted credential — a grant is a secret (ADR 0008).
pub enum PresentationOutcome {
    /// The secret verified; the grant carries an ordinary session (a repeat
    /// presentation refreshes the room's existing session for that label).
    Granted(SessionGrant),
    /// No room matches the presented secret (the secret is never echoed).
    Refused,
    /// Too many failed presentations; retry after the window drains.
    Throttled,
}

impl std::fmt::Debug for PresentationOutcome {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Granted(grant) => formatter
                .debug_struct("Granted")
                .field("session", &grant.session)
                .finish_non_exhaustive(),
            Self::Refused => formatter.write_str("Refused"),
            Self::Throttled => formatter.write_str("Throttled"),
        }
    }
}

/// The engine's joined rooms: `pairing.sqlite3` rows plus 0600 book files.
#[derive(Clone)]
pub struct RoomStore {
    /// The pairing database — the "who is allowed in" data domain.
    path: PathBuf,
    data_dir: PathBuf,
    /// Shared per store instance (per listener): failed presentations.
    failures: Arc<FailureWindow>,
}

impl RoomStore {
    /// Open (creating if absent) the room tables in `pairing.sqlite3`. Order
    /// with [`crate::pairing::PairingStore::open`] does not matter — both use
    /// `IF NOT EXISTS`, and independent connections coordinate through WAL,
    /// which is what lets the CLI mutate rows while an engine runs.
    pub fn open(data_dir: &Path) -> anyhow::Result<Self> {
        std::fs::create_dir_all(data_dir)?;
        let store = Self {
            path: data_dir.join("pairing.sqlite3"),
            data_dir: data_dir.to_path_buf(),
            failures: Arc::new(FailureWindow::default()),
        };
        let db = store.connect()?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        db.execute_batch(
            "CREATE TABLE IF NOT EXISTS rooms (
            id TEXT PRIMARY KEY, label TEXT NOT NULL, verifier BLOB NOT NULL UNIQUE,
            joined_at INTEGER NOT NULL
        ); CREATE TABLE IF NOT EXISTS room_presentations (
            room_id TEXT NOT NULL, label TEXT NOT NULL, session_id TEXT NOT NULL,
            PRIMARY KEY (room_id, label)
        );",
        )?;
        Ok(store)
    }

    fn connect(&self) -> anyhow::Result<Connection> {
        let db = Connection::open(&self.path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        Ok(db)
    }

    /// Join a room from its invite: derive the id, hash the secret into a
    /// verifier, discard the secret, and union the invite's members into the
    /// stored book. Re-joining (self-heal, retry) refreshes the label and
    /// merges the book — never a second row: the same secret is always the
    /// same room.
    pub fn join(&self, invite: RoomInvite) -> anyhow::Result<RoomRecord> {
        let id = invite.room_id();
        let label = invite.name.trim().to_owned();
        crate::pairing::validate_label(&label)?;
        let now = crate::now_ms();
        self.connect()?.execute(
            "INSERT INTO rooms (id, label, verifier, joined_at) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO UPDATE SET label = excluded.label",
            params![id, label, verifier(&invite.secret), now],
        )?;
        // The secret stops existing here: only the verifier above and the
        // derived id remain.
        drop(invite.secret);
        let Some(book) = self.merge_book(&id, invite.members)? else {
            anyhow::bail!("room row vanished during join");
        };
        let joined_at = self
            .connect()?
            .query_row(
                "SELECT joined_at FROM rooms WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .optional()?
            .unwrap_or(now);
        Ok(RoomRecord {
            id,
            label,
            joined_at,
            book,
        })
    }

    /// Every joined room with its book. A missing or unreadable book file
    /// reads as empty — the room still verifies presentations; only the
    /// addresses are gone.
    pub fn list(&self) -> anyhow::Result<Vec<RoomRecord>> {
        let db = self.connect()?;
        let mut query =
            db.prepare("SELECT id, label, joined_at FROM rooms ORDER BY joined_at, id")?;
        let rows: Vec<(String, String, i64)> = query
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows.into_iter()
            .map(|(id, label, joined_at)| {
                Ok(RoomRecord {
                    book: self.read_book(&id)?,
                    id,
                    label,
                    joined_at,
                })
            })
            .collect()
    }

    /// Leave a room: forget its verifier (future presentations of that secret
    /// are refused), drop its 0600 book, and forget the presentation mapping.
    /// Sessions it minted are ordinary sessions — revoke them with
    /// `roboco engine pairing revoke` (eviction is rotate-then-revoke, ADR 0011).
    /// Returns whether a room with that id existed.
    pub fn leave(&self, id: &str) -> anyhow::Result<bool> {
        let deleted = self
            .connect()?
            .execute("DELETE FROM rooms WHERE id = ?1", params![id])?
            > 0;
        if deleted {
            self.connect()?.execute(
                "DELETE FROM room_presentations WHERE room_id = ?1",
                params![id],
            )?;
            // The book is a 0600 secret; leaving removes it outright.
            if let Ok(dir) = self.room_dir(id) {
                let _ = std::fs::remove_dir_all(dir);
            }
        }
        Ok(deleted)
    }

    /// Verify a presentation `{secret, label}` and mint (or refresh) the
    /// presenter's session. The secret is hashed against the stored verifier
    /// and discarded — never persisted, never logged; refusals log shape only.
    ///
    /// Verification happens before the throttle check, so a member holding
    /// the secret is never locked out by someone else's failed guesses.
    pub fn present(&self, secret: &str, label: &str) -> anyhow::Result<PresentationOutcome> {
        let now = Instant::now();
        let label = label.trim().to_owned();
        anyhow::ensure!(
            !label.is_empty(),
            "a room presentation carries a device label"
        );
        crate::pairing::validate_label(&label)?;
        let room: Option<(String,)> = if secret.is_empty() {
            None
        } else {
            self.connect()?
                .query_row(
                    "SELECT id FROM rooms WHERE verifier = ?1",
                    params![verifier(secret)],
                    |row| Ok((row.get(0)?,)),
                )
                .optional()?
        };
        let Some((room_id,)) = room else {
            if !self.failures.allowed(now) {
                return Ok(PresentationOutcome::Throttled);
            }
            self.failures.record(now);
            tracing::warn!(
                secret_chars = secret.chars().count(),
                label_chars = label.chars().count(),
                "room presentation refused: no joined room matches the presented secret"
            );
            return Ok(PresentationOutcome::Refused);
        };
        // A presentation mints an ordinary session via the pairing machinery.
        let pairing = crate::pairing::PairingStore::open(&self.data_dir)?;
        let label = label.trim().to_owned();
        let existing: Option<String> = self
            .connect()?
            .query_row(
                "SELECT session_id FROM room_presentations WHERE room_id = ?1 AND label = ?2",
                params![room_id, label],
                |row| row.get(0),
            )
            .optional()?;
        let grant = match existing {
            // Repeat presentation: reuse the room's session row with a fresh
            // credential (the old one stops authenticating). If the row was
            // revoked or vanished, mint a replacement in its place.
            Some(session_id) => match pairing.reissue_session(&session_id)? {
                Some(grant) => grant,
                None => {
                    let grant = pairing.mint_session(&label)?;
                    self.connect()?.execute(
                        "INSERT OR REPLACE INTO room_presentations VALUES (?1, ?2, ?3)",
                        params![room_id, label, grant.session.id],
                    )?;
                    grant
                }
            },
            None => {
                let grant = pairing.mint_session(&label)?;
                self.connect()?.execute(
                    "INSERT OR REPLACE INTO room_presentations VALUES (?1, ?2, ?3)",
                    params![room_id, label, grant.session.id],
                )?;
                grant
            }
        };
        Ok(PresentationOutcome::Granted(grant))
    }

    /// The room's book, for a caller that additionally presented the room
    /// secret — member addresses are transport secrets, so a session alone is
    /// not enough (ADR 0011). `None` when the room is unknown or the secret
    /// is wrong (indistinguishable by design).
    pub fn book_verified(&self, id: &str, secret: &str) -> anyhow::Result<Option<RoomBook>> {
        let stored: Option<Vec<u8>> = self
            .connect()?
            .query_row(
                "SELECT verifier FROM rooms WHERE id = ?1",
                params![id],
                |row| row.get(0),
            )
            .optional()?;
        let Some(stored) = stored else {
            return Ok(None);
        };
        if stored != verifier(secret) {
            return Ok(None);
        }
        Ok(Some(self.read_book(id)?))
    }

    /// Union-merge `members` into the room's stored book and persist it with
    /// 0600 discipline. Entries are only ever added — a stale address is
    /// corrected by a fresh entry arriving, never by deletion; eviction
    /// rotates the room (ADR 0011). `None` when the room is unknown.
    pub fn merge_book(
        &self,
        id: &str,
        members: Vec<RoomMember>,
    ) -> anyhow::Result<Option<RoomBook>> {
        anyhow::ensure!(
            members.len() <= roboco_proto::room::MAX_MEMBERS,
            "{}",
            roboco_proto::room::RoomInviteError::TooManyMembers
        );
        for member in &members {
            roboco_proto::room::validate_room_member(member)?;
        }
        let exists: bool = self
            .connect()?
            .query_row("SELECT 1 FROM rooms WHERE id = ?1", params![id], |_| {
                Ok(true)
            })
            .optional()?
            .is_some();
        if !exists {
            return Ok(None);
        }
        let mut merged = self.read_book(id)?;
        merged.members.extend(members);
        // Union: dedupe exact (label, address) pairs, keep a stable order so
        // writes and replies are deterministic.
        merged
            .members
            .sort_by(|a, b| (&a.label, &a.address).cmp(&(&b.label, &b.address)));
        merged
            .members
            .dedup_by(|a, b| a.label == b.label && a.address == b.address);
        self.write_book(id, &merged)?;
        Ok(Some(merged))
    }

    fn rooms_root(&self) -> PathBuf {
        self.data_dir.join("rooms")
    }

    /// `{data_dir}/rooms/{id}` — the id is validated first because RPC and
    /// CLI callers supply it; a room id is exactly 43 base64url characters
    /// (the derived digest), so no id can traverse paths.
    fn room_dir(&self, id: &str) -> anyhow::Result<PathBuf> {
        anyhow::ensure!(valid_room_id(id), "room id must be 43 base64url characters");
        Ok(self.rooms_root().join(id))
    }

    fn read_book(&self, id: &str) -> anyhow::Result<RoomBook> {
        let path = self.room_dir(id)?.join("book.json");
        match std::fs::read(&path) {
            Ok(bytes) => match serde_json::from_slice(&bytes) {
                Ok(book) => Ok(book),
                Err(error) => {
                    // write_private is atomic, so corruption means tampering
                    // or decay; a room without addresses still welcomes
                    // presentations, so self-heal to empty.
                    tracing::warn!(%error, book = %path.display(), "room book is unreadable; treating it as empty");
                    Ok(RoomBook::default())
                }
            },
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(RoomBook::default()),
            Err(error) => Err(error.into()),
        }
    }

    fn write_book(&self, id: &str, book: &RoomBook) -> anyhow::Result<()> {
        let dir = self.room_dir(id)?;
        std::fs::create_dir_all(&dir)?;
        #[cfg(unix)]
        crate::tailcat::set_mode(&dir, 0o700)?;
        #[cfg(unix)]
        crate::tailcat::set_mode(&self.rooms_root(), 0o700)?;
        // Member addresses are transport secrets: the book persists with the
        // same 0600 discipline as relay connect.json entries (ADR 0011).
        crate::tailcat::write_private(&dir.join("book.json"), &serde_json::to_vec(book)?)
    }
}

/// Domain-tagged SHA-256 of the room secret, constructed exactly like the
/// pairing verifier (`pairing.rs`): domain tag, kind, secret — in the room's
/// own domain so a room verifier can never collide with a session verifier.
/// Only this digest is ever at rest.
fn verifier(secret: &str) -> Vec<u8> {
    let mut hash = Sha256::new();
    hash.update(b"roboco-room-v1\0verifier\0");
    hash.update(secret.as_bytes());
    hash.finalize().to_vec()
}

/// Room ids are 43-character base64url (the derived `room_id_bytes` digest).
fn valid_room_id(id: &str) -> bool {
    id.len() == 43
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

/// Sliding window of failed presentations. Failures age out; nothing is
/// cleared on success (a valid secret already verifies before the window is
/// consulted, so clearing would only help a guesser interleave).
#[derive(Default)]
struct FailureWindow {
    failures: Mutex<Vec<Instant>>,
}

impl FailureWindow {
    fn allowed(&self, now: Instant) -> bool {
        let mut failures = self.failures.lock().unwrap();
        Self::prune(&mut failures, now);
        failures.len() < MAX_PRESENTATION_FAILURES
    }

    fn record(&self, now: Instant) {
        let mut failures = self.failures.lock().unwrap();
        Self::prune(&mut failures, now);
        failures.push(now);
    }

    fn prune(failures: &mut Vec<Instant>, now: Instant) {
        failures.retain(|at| now.duration_since(*at) < PRESENTATION_FAILURE_WINDOW);
    }
}

/// The derived id and the verifier are different domain tags of the same
/// secret: the id is a public handle, the verifier is the stored proof. This
/// doubles as the room-side of the verifier round-trip — `room_id_bytes`
/// lives in proto so clients derive the same handle.
#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::room::{room_id, room_id_bytes};

    const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

    fn member(label: &str, address: &str) -> RoomMember {
        RoomMember {
            label: label.into(),
            address: address.into(),
        }
    }

    fn invite(secret: &str, members: Vec<RoomMember>) -> RoomInvite {
        RoomInvite::new("Home fleet", secret, members).unwrap()
    }

    fn store(dir: &std::path::Path) -> RoomStore {
        RoomStore::open(dir).unwrap()
    }

    #[test]
    fn the_verifier_round_trips_and_only_the_verifier_is_at_rest() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        store
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();

        // The stored verifier is exactly the domain-tagged digest of the secret,
        // and the row's id is the public derivation of the same secret.
        let row: (String, Vec<u8>) = store
            .connect()
            .unwrap()
            .query_row("SELECT id, verifier FROM rooms", [], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .unwrap();
        assert_eq!(row.0, room_id(SECRET));
        assert_eq!(row.1, verifier(SECRET));
        assert_ne!(row.1.as_slice(), room_id_bytes(SECRET).as_slice());

        // The pairing-domain session verifier is a different domain: the same
        // plain SHA-256 of nothing overlaps nothing.
        assert_ne!(verifier(SECRET), verifier("another"));

        // Nothing at rest contains the secret: pairing.sqlite3 (including its
        // WAL/SHM sidecars) and every file under the data dir.
        for entry in walk(dir.path()) {
            let bytes = std::fs::read(&entry).unwrap();
            assert!(
                !bytes.windows(SECRET.len()).any(|w| w == SECRET.as_bytes()),
                "the room secret leaked into {}",
                entry.display()
            );
        }
    }

    #[test]
    fn joining_is_idempotent_and_unions_the_book() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let first = store
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        assert_eq!(first.book.members, vec![member("desktop", "tcA1")]);

        // Re-join with the same secret (self-heal): same room row, label
        // refreshed, book unioned — never a second row.
        let second = store
            .join(invite(SECRET, vec![member("server", "tcB2")]))
            .unwrap();
        assert_eq!(second.id, first.id);
        assert_eq!(second.joined_at, first.joined_at);
        assert_eq!(
            second.book.members,
            vec![member("desktop", "tcA1"), member("server", "tcB2")]
        );
        assert_eq!(store.list().unwrap().len(), 1);

        // A different secret is a different room (rotation mints a new one).
        store
            .join(invite("another-room-secret-1234567890abcdefghij", vec![]))
            .unwrap();
        assert_eq!(store.list().unwrap().len(), 2);
    }

    #[test]
    fn books_union_merge_and_persist_sorted() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let id = room_id(SECRET);
        store
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();

        let merged = store
            .merge_book(
                &id,
                vec![member("server", "tcB2"), member("desktop", "tcA1")],
            )
            .unwrap()
            .expect("room exists");
        assert_eq!(
            merged.members,
            vec![member("desktop", "tcA1"), member("server", "tcB2")]
        );

        // An unknown room refuses the merge.
        assert!(
            store
                .merge_book(&room_id("unknown-room-secret-00000000000000"), vec![])
                .unwrap()
                .is_none()
        );
        // Malformed entries are refused without writing.
        assert!(
            store
                .merge_book(&id, vec![member("bad", "has space")])
                .is_err()
        );
        let book = store.book_verified(&id, SECRET).unwrap().unwrap();
        assert_eq!(book.members.len(), 2);

        // Persistence: a fresh store (fresh process) reads the merged book.
        assert_eq!(
            RoomStore::open(dir.path())
                .unwrap()
                .book_verified(&id, SECRET)
                .unwrap()
                .unwrap(),
            book
        );
        // The wrong secret never reads the book.
        assert!(store.book_verified(&id, "wrong-secret").unwrap().is_none());
        assert!(
            store
                .book_verified(&room_id("unknown-room-secret-00000000000000"), SECRET)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn the_book_persists_owner_only() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let id = room_id(SECRET);
        store
            .join(invite(SECRET, vec![member("desktop", "tcADDRESS")]))
            .unwrap();
        let book = dir.path().join("rooms").join(&id).join("book.json");
        assert!(book.is_file());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&book).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600, "the book must be owner-only");
            let rooms_mode = std::fs::metadata(dir.path().join("rooms"))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(
                rooms_mode & 0o777,
                0o700,
                "the rooms dir must be owner-only"
            );
        }
        let bytes = std::fs::read(&book).unwrap();
        assert!(String::from_utf8_lossy(&bytes).contains("tcADDRESS"));
    }

    #[test]
    fn a_bad_room_id_cannot_traverse_paths() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        for evil in [
            "../../etc",
            "ok-but-not-a-real-id-value-at-all-aaaaaaaaaa",
            "",
            "short",
        ] {
            // The path guard is the load-bearing check: a room id is exactly
            // the 43-character derived digest, so no caller-supplied id can
            // traverse paths.
            assert!(store.room_dir(evil).is_err(), "{evil:?} accepted");
            // Merges and leaves on a bad id resolve to nothing, not to a
            // directory outside the rooms root.
            assert!(store.merge_book(evil, vec![]).unwrap().is_none());
            assert!(!store.leave(evil).unwrap());
        }
        assert!(dir.path().join("rooms").join("..").join("etc").is_dir() == false);
    }

    #[test]
    fn presentations_mint_reuse_and_refresh_ordinary_sessions() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        store
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();

        // First presentation mints an ordinary session.
        let first = match store.present(SECRET, "Pixel").unwrap() {
            PresentationOutcome::Granted(grant) => grant,
            other => panic!("presentation refused: {other:?}"),
        };
        assert_eq!(first.session.label, "Pixel");
        assert!(first.session.revoked_at.is_none());

        // Re-present with the same label: the SAME session row, refreshed —
        // not a second session.
        let second = match store.present(SECRET, "Pixel").unwrap() {
            PresentationOutcome::Granted(grant) => grant,
            other => panic!("presentation refused: {other:?}"),
        };
        assert_eq!(second.session.id, first.session.id);
        assert_eq!(second.session.label, "Pixel");
        let pairing = crate::pairing::PairingStore::open(dir.path()).unwrap();
        let sessions: Vec<_> = pairing
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|s| s.label == "Pixel")
            .collect();
        assert_eq!(sessions.len(), 1, "repeat presentation must not mint a row");
        // The refreshed credential authenticates; the old one does not.
        assert!(pairing.authenticate(&second.credential).unwrap().is_some());
        assert!(pairing.authenticate(&first.credential).unwrap().is_none());

        // A different label is a different presentation (a different device).
        let other = match store.present(SECRET, "Laptop").unwrap() {
            PresentationOutcome::Granted(grant) => grant,
            other => panic!("presentation refused: {other:?}"),
        };
        assert_ne!(other.session.id, first.session.id);
        assert_eq!(
            pairing
                .list_sessions()
                .unwrap()
                .into_iter()
                .filter(|s| s.revoked_at.is_none())
                .count(),
            2
        );

        // A revoked presentation session is replaced on the next presentation
        // (a room gates future introductions; revocation alone does not evict
        // — rotate the secret, ADR 0011).
        pairing.revoke(&first.session.id).unwrap();
        let third = match store.present(SECRET, "Pixel").unwrap() {
            PresentationOutcome::Granted(grant) => grant,
            other => panic!("presentation refused: {other:?}"),
        };
        assert_ne!(third.session.id, first.session.id);
        assert!(pairing.authenticate(&third.credential).unwrap().is_some());
        // Still one active row for the label: the revoked row stays in history.
        assert_eq!(
            pairing
                .list_sessions()
                .unwrap()
                .into_iter()
                .filter(|s| s.label == "Pixel" && s.revoked_at.is_none())
                .count(),
            1
        );

        // A wrong secret is refused.
        assert!(matches!(
            store
                .present("not-the-room-secret-at-all", "Pixel")
                .unwrap(),
            PresentationOutcome::Refused
        ));
        // And it never persisted anything.
        for entry in walk(dir.path()) {
            let bytes = std::fs::read(&entry).unwrap();
            assert!(
                !bytes.windows(21).any(|w| w == b"not-the-room-secret-"),
                "a presented secret leaked into {}",
                entry.display()
            );
        }
    }

    #[test]
    fn failed_presentation_secrets_never_reach_the_log() {
        // Shape-only logging is enforced by construction (the warn! records
        // lengths, never values); the observable contract here is that the
        // refusal is counted without any stringly echo available to leak.
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        store.join(invite(SECRET, vec![])).unwrap();
        assert!(matches!(
            store.present("totally-wrong-secret", "Pixel").unwrap(),
            PresentationOutcome::Refused
        ));
    }

    #[test]
    fn the_throttle_blocks_the_sixth_failure_and_drains_with_time() {
        let window = FailureWindow::default();
        let now = Instant::now();
        assert!(window.allowed(now));
        for _ in 0..MAX_PRESENTATION_FAILURES {
            assert!(window.allowed(now));
            window.record(now);
        }
        assert!(!window.allowed(now));
        // Failures age out of the window.
        let later = now + PRESENTATION_FAILURE_WINDOW + Duration::from_secs(1);
        assert!(window.allowed(later));
        window.record(later);
        // Aged failures no longer count.
        assert!(window.allowed(later));
    }

    #[test]
    fn presentation_failures_throttle_the_store_itself() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        store.join(invite(SECRET, vec![])).unwrap();
        for _ in 0..MAX_PRESENTATION_FAILURES {
            assert!(matches!(
                store.present("wrong-secret", "Pixel").unwrap(),
                PresentationOutcome::Refused
            ));
        }
        // The next failure is throttled…
        assert!(matches!(
            store.present("wrong-secret", "Pixel").unwrap(),
            PresentationOutcome::Throttled
        ));
        // …but a member holding the real secret is still welcomed: the secret
        // is verified before the window is consulted.
        assert!(matches!(
            store.present(SECRET, "Pixel").unwrap(),
            PresentationOutcome::Granted(_)
        ));
    }

    #[test]
    fn leaving_forgets_the_room_and_its_book() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let record = store
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        assert!(matches!(
            store.present(SECRET, "Pixel").unwrap(),
            PresentationOutcome::Granted(_)
        ));
        assert!(store.leave(&record.id).unwrap());
        // The verifier is gone: the secret no longer verifies, and the book
        // file no longer exists.
        assert!(matches!(
            store.present(SECRET, "Pixel").unwrap(),
            PresentationOutcome::Refused
        ));
        assert!(!dir.path().join("rooms").join(&record.id).exists());
        assert!(store.list().unwrap().is_empty());
        // Leaving twice reports the second as a no-op.
        assert!(!store.leave(&record.id).unwrap());
    }

    fn walk(dir: &Path) -> Vec<PathBuf> {
        let mut out = Vec::new();
        let mut stack = vec![dir.to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).unwrap().flatten() {
                let path = entry.path();
                if path.is_dir() {
                    stack.push(path);
                } else {
                    out.push(path);
                }
            }
        }
        out
    }
}
