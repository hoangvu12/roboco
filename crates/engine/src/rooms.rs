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
//!
//! **Fleet rooms** (ADR 0011 amendment, fleet follow) are the one exception
//! to "only the verifier is at rest": a fleet secret is a group pairing
//! token, retained in a `fleet_rooms (room_id, secret)` row of the same
//! pairing database (owner-only on unix) so any member can serialize the
//! fleet for a newly paired client (`GetFleetInvite`). A data dir holds at
//! most one fleet room; plain rooms keep verifier-only semantics unchanged.

use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use roboco_proto::room::{RoomBook, RoomInvite, RoomKind, RoomMember};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::pairing::SessionGrant;

/// At most this many refused presentations inside [`PRESENTATION_FAILURE_WINDOW`]
/// before further failures answer throttled.
const MAX_PRESENTATION_FAILURES: usize = 5;
const PRESENTATION_FAILURE_WINDOW: Duration = Duration::from_secs(60);

/// A joined room as the store reports it (the CLI and tests read this shape;
/// the verifier and any fleet secret never leave the store this way).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomRecord {
    pub id: String,
    pub label: String,
    pub joined_at: i64,
    pub kind: RoomKind,
    pub book: RoomBook,
}

/// A fleet join or create refused because this data dir already holds a
/// different fleet room (one per data dir). Fixed text: callers surface it
/// verbatim (CLI, RPC) and tests match on the type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FleetConflict;

impl std::fmt::Display for FleetConflict {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("this engine already belongs to a fleet; leave it first")
    }
}

impl std::error::Error for FleetConflict {}

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
        ); CREATE TABLE IF NOT EXISTS fleet_rooms (
            room_id TEXT PRIMARY KEY, secret TEXT NOT NULL
        );",
        )?;
        drop(db);
        // The database can now hold a usable fleet secret: owner-only, like
        // every other secret file in the data dir (no-op off unix).
        store.restrict_database()?;
        Ok(store)
    }

    fn connect(&self) -> anyhow::Result<Connection> {
        let db = Connection::open(&self.path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        Ok(db)
    }

    /// `pairing.sqlite3` and its WAL/SHM sidecars at 0600. SQLite creates
    /// later sidecars with the database's own mode, so tightening the main
    /// file at open covers them; existing sidecars are tightened here too.
    #[cfg(unix)]
    fn restrict_database(&self) -> anyhow::Result<()> {
        for suffix in ["", "-wal", "-shm"] {
            let mut path = self.path.clone().into_os_string();
            path.push(suffix);
            match crate::tailcat::set_mode(Path::new(&path), 0o600) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }
        Ok(())
    }

    #[cfg(not(unix))]
    fn restrict_database(&self) -> anyhow::Result<()> {
        Ok(())
    }

    /// Join a room from its invite: derive the id, hash the secret into a
    /// verifier, and union the invite's members into the stored book.
    /// Re-joining (self-heal, retry, a repeated `RoomJoin` push) refreshes
    /// the label and merges the book — never a second row: the same secret is
    /// always the same room.
    ///
    /// A plain room discards the secret here (only the verifier and the
    /// derived id remain). A fleet invite (`kind = fleet`) additionally
    /// retains the secret in `fleet_rooms` so this engine can introduce new
    /// clients; it is refused with [`FleetConflict`] when the data dir
    /// already holds a different fleet room.
    pub fn join(&self, invite: RoomInvite) -> anyhow::Result<RoomRecord> {
        let id = invite.room_id();
        let label = invite.name.trim().to_owned();
        crate::pairing::validate_label(&label)?;
        let now = crate::now_ms();
        let fleet = invite.is_fleet();
        let mut db = self.connect()?;
        if fleet {
            // The orphan sweep below may erase a secret: zero freed pages.
            db.pragma_update(None, "secure_delete", true)?;
        }
        // IMMEDIATE: the one-fleet check and the writes are one decision,
        // even against a CLI process joining concurrently.
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        if fleet {
            // A retained secret whose room row is gone (an interrupted leave
            // by an older store) is no fleet: `fleet_room` already ignores
            // it, so erase it here instead of refusing the join forever.
            if tx.execute(
                "DELETE FROM fleet_rooms WHERE room_id NOT IN (SELECT id FROM rooms)",
                [],
            )? > 0
            {
                tracing::warn!("erased a fleet secret left without its room");
            }
            let other: Option<String> = tx
                .query_row(
                    "SELECT fleet_rooms.room_id FROM fleet_rooms
                     JOIN rooms ON rooms.id = fleet_rooms.room_id
                     WHERE fleet_rooms.room_id != ?1 LIMIT 1",
                    params![id],
                    |row| row.get(0),
                )
                .optional()?;
            if other.is_some() {
                return Err(FleetConflict.into());
            }
        }
        tx.execute(
            "INSERT INTO rooms (id, label, verifier, joined_at) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO UPDATE SET label = excluded.label",
            params![id, label, verifier(&invite.secret), now],
        )?;
        if fleet {
            tx.execute(
                "INSERT INTO fleet_rooms (room_id, secret) VALUES (?1, ?2)
                 ON CONFLICT(room_id) DO NOTHING",
                params![id, invite.secret],
            )?;
        }
        tx.commit()?;
        drop(db);
        // The caller's copy of the secret stops existing here; a fleet
        // secret lives on only in its owner-only row.
        drop(invite.secret);
        let Some(book) = self.merge_book(&id, invite.members)? else {
            anyhow::bail!("room row vanished during join");
        };
        // A fleet member introduces itself: its own Tailcat address joins
        // the book, so whoever heals or fetches this book (the desktop's
        // sync, a phone's `GetFleetInvite`) learns how to reach it without
        // asking it to mint anything (C4a). Plain rooms stay as they were.
        if fleet
            && let Some(own) = own_member(&self.data_dir)
            && !book
                .members
                .iter()
                .any(|member| member.address == own.address)
        {
            self.merge_book(&id, vec![own])?;
        }
        self.record(&id)?
            .ok_or_else(|| anyhow::anyhow!("room row vanished during join"))
    }

    /// One room's record, or `None` when this engine has not joined it.
    pub fn record(&self, id: &str) -> anyhow::Result<Option<RoomRecord>> {
        let row: Option<(String, i64, bool)> = self
            .connect()?
            .query_row(
                "SELECT label, joined_at,
                        EXISTS(SELECT 1 FROM fleet_rooms WHERE room_id = rooms.id)
                 FROM rooms WHERE id = ?1",
                params![id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()?;
        let Some((label, joined_at, fleet)) = row else {
            return Ok(None);
        };
        Ok(Some(RoomRecord {
            book: self.read_book(id)?,
            id: id.to_owned(),
            label,
            joined_at,
            kind: kind_of(fleet),
        }))
    }

    /// Every joined room with its book. A missing or unreadable book file
    /// reads as empty — the room still verifies presentations; only the
    /// addresses are gone.
    pub fn list(&self) -> anyhow::Result<Vec<RoomRecord>> {
        let db = self.connect()?;
        let mut query = db.prepare(
            "SELECT id, label, joined_at,
                    EXISTS(SELECT 1 FROM fleet_rooms WHERE room_id = rooms.id)
             FROM rooms ORDER BY joined_at, id",
        )?;
        let rows: Vec<(String, String, i64, bool)> = query
            .query_map([], |row| {
                Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows.into_iter()
            .map(|(id, label, joined_at, fleet)| {
                Ok(RoomRecord {
                    book: self.read_book(&id)?,
                    id,
                    label,
                    joined_at,
                    kind: kind_of(fleet),
                })
            })
            .collect()
    }

    /// The data dir's fleet room, if it holds one (at most one by
    /// construction).
    pub fn fleet_room(&self) -> anyhow::Result<Option<RoomRecord>> {
        let id: Option<String> = self
            .connect()?
            .query_row(
                "SELECT fleet_rooms.room_id FROM fleet_rooms
                 JOIN rooms ON rooms.id = fleet_rooms.room_id
                 ORDER BY fleet_rooms.room_id LIMIT 1",
                [],
                |row| row.get(0),
            )
            .optional()?;
        match id {
            Some(id) => self.record(&id),
            None => Ok(None),
        }
    }

    /// A fresh fleet invite for room `id`: the retained secret, the label as
    /// the name, the current book as the members, `kind = fleet`. `None` when
    /// `id` is unknown or a plain room (no retained secret). The returned
    /// invite is a secret: encode it only toward a caller who asked for it.
    pub fn fleet_invite(&self, id: &str) -> anyhow::Result<Option<RoomInvite>> {
        let row: Option<(String, String)> = self
            .connect()?
            .query_row(
                "SELECT rooms.label, fleet_rooms.secret FROM fleet_rooms
                 JOIN rooms ON rooms.id = fleet_rooms.room_id
                 WHERE fleet_rooms.room_id = ?1",
                params![id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        let Some((label, secret)) = row else {
            return Ok(None);
        };
        let book = self.read_book(id)?;
        Ok(Some(RoomInvite::fleet(&label, &secret, book.members)?))
    }

    /// Mint this data dir's fleet room: a fresh secret from the proto mint,
    /// joined locally as `kind = fleet` with `members` as the initial book.
    /// Refused with [`FleetConflict`] when a fleet room already exists (one
    /// per data dir). Returns the record (the id is the public handle; the
    /// secret is never returned, printed, or logged).
    pub fn create(&self, name: &str, members: Vec<RoomMember>) -> anyhow::Result<RoomRecord> {
        if self.fleet_room()?.is_some() {
            return Err(FleetConflict.into());
        }
        let secret = roboco_proto::room::mint_room_secret()?;
        let invite = RoomInvite::fleet(name.trim(), &secret, members)?;
        drop(secret);
        self.join(invite)
    }

    /// Leave a room: forget its verifier (future presentations of that secret
    /// are refused), drop its 0600 book, forget the presentation mapping, and
    /// — for a fleet room — erase the retained secret.
    /// Sessions it minted are ordinary sessions — revoke them with
    /// `roboco engine pairing revoke` (eviction is rotate-then-revoke, ADR 0011).
    /// Returns whether a room with that id existed.
    pub fn leave(&self, id: &str) -> anyhow::Result<bool> {
        let mut db = self.connect()?;
        // A fleet secret must not survive in freed pages: zero them.
        db.pragma_update(None, "secure_delete", true)?;
        // One transaction: a crash between the deletes must never leave a
        // `fleet_rooms` row without its room (which `join` would then
        // refuse as a conflict while `fleet_room` reports no fleet).
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let deleted = tx.execute("DELETE FROM rooms WHERE id = ?1", params![id])? > 0;
        let fleet = tx.execute("DELETE FROM fleet_rooms WHERE room_id = ?1", params![id])? > 0;
        tx.execute(
            "DELETE FROM room_presentations WHERE room_id = ?1",
            params![id],
        )?;
        tx.commit()?;
        if deleted {
            // The book is a 0600 secret; leaving removes it outright.
            if let Ok(dir) = self.room_dir(id) {
                let _ = std::fs::remove_dir_all(dir);
            }
        }
        if fleet {
            // Best effort: fold the WAL (which still carries the pre-delete
            // page) back into the zeroed file. A concurrent reader (a running
            // engine) can keep frames alive until its own next checkpoint.
            let _ = db.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()));
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

/// This engine's own fleet book entry: the Tailcat address it last
/// published (`tailcat-address.json`), labelled with its device name (what
/// `EngineInfo` clients show for it). `None` when no address is recorded —
/// an engine that never served Tailcat has nothing to offer the book. Only
/// reads files: nothing here enables, mints, or saves anything.
pub fn own_member(data_dir: &Path) -> Option<RoomMember> {
    let address = crate::tailcat::read_recorded_address(data_dir)
        .ok()
        .flatten()?;
    let mut label: String = crate::local_device_name_at(data_dir)
        .trim()
        .chars()
        .filter(|character| !character.is_control())
        .collect();
    while label.len() > roboco_proto::room::MAX_LABEL_BYTES {
        label.pop();
    }
    if label.is_empty() {
        label = "Engine".into();
    }
    let member = RoomMember { label, address };
    roboco_proto::room::validate_room_member(&member).ok()?;
    Some(member)
}

fn kind_of(fleet: bool) -> RoomKind {
    if fleet {
        RoomKind::Fleet
    } else {
        RoomKind::Room
    }
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

    fn fleet_invite_for(secret: &str, members: Vec<RoomMember>) -> RoomInvite {
        RoomInvite::fleet("Fleet", secret, members).unwrap()
    }

    fn secret_on_disk(dir: &Path, secret: &str) -> bool {
        walk(dir).iter().any(|path| {
            std::fs::read(path)
                .unwrap()
                .windows(secret.len())
                .any(|w| w == secret.as_bytes())
        })
    }

    #[test]
    fn a_fleet_join_retains_the_secret_and_a_plain_join_does_not() {
        let plain_dir = tempfile::tempdir().unwrap();
        let record = store(plain_dir.path())
            .join(invite(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        assert_eq!(record.kind, RoomKind::Room);
        assert!(
            !secret_on_disk(plain_dir.path(), SECRET),
            "a plain room must stay verifier-only"
        );
        assert!(store(plain_dir.path()).fleet_room().unwrap().is_none());
        assert!(
            store(plain_dir.path())
                .fleet_invite(&record.id)
                .unwrap()
                .is_none(),
            "a plain room never serializes as a fleet"
        );

        let fleet_dir = tempfile::tempdir().unwrap();
        let fleet = store(fleet_dir.path());
        let record = fleet
            .join(fleet_invite_for(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        assert_eq!(record.kind, RoomKind::Fleet);
        let retained: String = fleet
            .connect()
            .unwrap()
            .query_row(
                "SELECT secret FROM fleet_rooms WHERE room_id = ?1",
                params![record.id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(retained, SECRET);
        // The record (what the CLI prints) never carries it.
        assert!(!serde_json::to_string(&record).unwrap().contains(SECRET));
        assert!(!format!("{record:?}").contains(SECRET));
    }

    #[test]
    fn fleet_invites_round_trip_and_absent_fleets_answer_none() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        assert!(store.fleet_room().unwrap().is_none());
        assert!(store.fleet_invite(&room_id(SECRET)).unwrap().is_none());

        let record = store
            .join(fleet_invite_for(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        store
            .merge_book(&record.id, vec![member("server", "tcB2")])
            .unwrap();
        let fleet = store.fleet_room().unwrap().expect("a fleet room");
        assert_eq!(fleet.id, record.id);

        let encoded = store
            .fleet_invite(&record.id)
            .unwrap()
            .expect("a fleet invite")
            .encode()
            .unwrap();
        let decoded = RoomInvite::decode(&encoded).unwrap();
        assert_eq!(decoded.room_id(), record.id);
        assert_eq!(decoded.kind, RoomKind::Fleet);
        assert_eq!(decoded.name, "Fleet");
        // The book travels as it stands now, not as it was at join time.
        assert_eq!(
            decoded.members,
            vec![member("desktop", "tcA1"), member("server", "tcB2")]
        );
        // A second engine joining that invite lands in the same room.
        let other = tempfile::tempdir().unwrap();
        let joined = RoomStore::open(other.path())
            .unwrap()
            .join(decoded)
            .unwrap();
        assert_eq!(joined.id, record.id);
        assert_eq!(joined.kind, RoomKind::Fleet);
    }

    #[test]
    fn one_fleet_per_data_dir_and_rejoining_it_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let created = store
            .create("Fleet", vec![member("desktop", "tcA1")])
            .unwrap();
        assert_eq!(created.kind, RoomKind::Fleet);
        assert_eq!(created.label, "Fleet");

        // A second create is refused with fixed text.
        let error = store.create("Fleet", vec![]).unwrap_err();
        assert!(error.downcast_ref::<FleetConflict>().is_some());
        assert_eq!(
            error.to_string(),
            "this engine already belongs to a fleet; leave it first"
        );
        // So is joining a different fleet.
        let error = store
            .join(fleet_invite_for(
                "another-fleet-secret-0123456789abcdefghijk",
                vec![],
            ))
            .unwrap_err();
        assert!(error.downcast_ref::<FleetConflict>().is_some());
        assert_eq!(store.list().unwrap().len(), 1);

        // Re-joining the same fleet (a repeated push) unions, never a second
        // row; a plain room alongside is still welcome.
        let invite = store.fleet_invite(&created.id).unwrap().unwrap();
        let mut again = invite.clone();
        again.members = vec![member("server", "tcB2")];
        let rejoined = store.join(again).unwrap();
        assert_eq!(rejoined.id, created.id);
        assert_eq!(rejoined.joined_at, created.joined_at);
        assert_eq!(rejoined.book.members.len(), 2);
        store.join(invite).unwrap();
        store
            .join(invite_plain("plain-room-secret-0123456789abcdefghijklm"))
            .unwrap();
        let rooms = store.list().unwrap();
        assert_eq!(rooms.len(), 2);
        assert_eq!(
            rooms.iter().filter(|r| r.kind == RoomKind::Fleet).count(),
            1
        );
        let retained: i64 = store
            .connect()
            .unwrap()
            .query_row("SELECT COUNT(*) FROM fleet_rooms", [], |row| row.get(0))
            .unwrap();
        assert_eq!(retained, 1);

        // Leaving the fleet frees the slot.
        assert!(store.leave(&created.id).unwrap());
        assert!(store.fleet_room().unwrap().is_none());
        store.create("Fleet", vec![]).unwrap();
    }

    /// A fleet join (store-level, so the `RoomJoin` RPC, CLI `room join`
    /// and `create` all get it) adds this engine's own recorded Tailcat
    /// address to the book, once; without a recorded address it adds
    /// nothing, and a plain room never gains an entry.
    #[test]
    fn a_fleet_join_introduces_the_engine_by_its_recorded_address() {
        const OWN: &str = "tcOWNRECORDEDADDRESS";
        let own_label = crate::local_device_name_at;

        // Nothing recorded: the book is exactly the invite's.
        let bare = tempfile::tempdir().unwrap();
        let record = store(bare.path())
            .join(fleet_invite_for(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        assert_eq!(record.book.members, vec![member("desktop", "tcA1")]);

        let dir = tempfile::tempdir().unwrap();
        crate::tailcat::record_address(dir.path(), OWN).unwrap();
        let store = store(dir.path());

        // A plain room is unchanged: no self entry.
        let plain = store
            .join(invite_plain("plain-room-secret-0123456789abcdefghijklm"))
            .unwrap();
        assert!(plain.book.members.is_empty());

        // A fleet join adds `{device name, recorded address}`.
        let record = store
            .join(fleet_invite_for(SECRET, vec![member("desktop", "tcA1")]))
            .unwrap();
        let own = member(&own_label(dir.path()), OWN);
        assert!(record.book.members.contains(&own), "{:?}", record.book);
        assert_eq!(record.book.members.len(), 2);
        // Re-joining (a repeated push) never adds it twice.
        let again = store
            .join(fleet_invite_for(SECRET, vec![member("server", "tcB2")]))
            .unwrap();
        assert_eq!(
            again
                .book
                .members
                .iter()
                .filter(|member| member.address == OWN)
                .count(),
            1
        );
        // The served invite carries it: a phone pairing here learns it.
        let invite = store.fleet_invite(&record.id).unwrap().unwrap();
        assert!(invite.members.contains(&own));

        // An entry for the address under another label (CLI `--label`, the
        // desktop's own entry) is this engine already: no second entry.
        let labelled = tempfile::tempdir().unwrap();
        crate::tailcat::record_address(labelled.path(), OWN).unwrap();
        let created = RoomStore::open(labelled.path())
            .unwrap()
            .create("Fleet", vec![member("my label", OWN)])
            .unwrap();
        assert_eq!(created.book.members, vec![member("my label", OWN)]);
    }

    /// An older store's interrupted leave could drop the room row and keep
    /// its `fleet_rooms` secret. `fleet_room` never saw such an orphan, so
    /// the conflict check must not either: the next fleet join (the
    /// desktop's lazy create) erases it and succeeds instead of failing
    /// forever.
    #[test]
    fn an_orphaned_fleet_secret_never_blocks_the_next_fleet() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let orphan = store.join(fleet_invite_for(SECRET, vec![])).unwrap();
        store
            .connect()
            .unwrap()
            .execute("DELETE FROM rooms WHERE id = ?1", params![orphan.id])
            .unwrap();
        assert!(store.fleet_room().unwrap().is_none());

        let created = store.create("Fleet", vec![]).unwrap();
        assert_ne!(created.id, orphan.id);
        assert_eq!(store.fleet_room().unwrap().unwrap().id, created.id);
        let retained: Vec<String> = store
            .connect()
            .unwrap()
            .prepare("SELECT room_id FROM fleet_rooms")
            .unwrap()
            .query_map([], |row| row.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(retained, vec![created.id.clone()], "the orphan was erased");
        drop(store);
        assert!(
            !secret_on_disk(dir.path(), SECRET),
            "the orphaned secret survived on disk"
        );
    }

    /// `leave` deletes every row of the room in one transaction, so there is
    /// nothing left for the orphan sweep to find.
    #[test]
    fn leaving_a_fleet_leaves_no_rows_behind() {
        let dir = tempfile::tempdir().unwrap();
        let store = store(dir.path());
        let record = store.join(fleet_invite_for(SECRET, vec![])).unwrap();
        assert!(matches!(
            store.present(SECRET, "Pixel").unwrap(),
            PresentationOutcome::Granted(_)
        ));
        assert!(store.leave(&record.id).unwrap());
        let db = store.connect().unwrap();
        for table in ["rooms", "fleet_rooms", "room_presentations"] {
            let count: i64 = db
                .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(count, 0, "{table}");
        }
    }

    fn invite_plain(secret: &str) -> RoomInvite {
        RoomInvite::new("Side room", secret, vec![]).unwrap()
    }

    #[test]
    fn leaving_a_fleet_erases_the_retained_secret_from_disk() {
        let dir = tempfile::tempdir().unwrap();
        {
            let store = store(dir.path());
            let record = store.join(fleet_invite_for(SECRET, vec![])).unwrap();
            assert!(secret_on_disk(dir.path(), SECRET));
            assert!(store.leave(&record.id).unwrap());
            assert!(store.fleet_invite(&record.id).unwrap().is_none());
        }
        assert!(
            !secret_on_disk(dir.path(), SECRET),
            "a left fleet's secret survived on disk"
        );
    }

    #[cfg(unix)]
    #[test]
    fn the_pairing_database_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        // The pairing store may create the file first with the umask mode;
        // opening the room store tightens it.
        crate::pairing::PairingStore::open(dir.path()).unwrap();
        let store = store(dir.path());
        store.join(fleet_invite_for(SECRET, vec![])).unwrap();
        for name in [
            "pairing.sqlite3",
            "pairing.sqlite3-wal",
            "pairing.sqlite3-shm",
        ] {
            let path = dir.path().join(name);
            if path.exists() {
                let mode = std::fs::metadata(&path).unwrap().permissions().mode();
                assert_eq!(mode & 0o777, 0o600, "{name} must be owner-only");
            }
        }
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
