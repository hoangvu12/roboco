//! Desktop room operations (ADR 0011): the Settings → Rooms flows — create,
//! join, leave, rotate — over the client machinery in
//! `roboco-engine-client::rooms` plus the pieces only the desktop has: the
//! tailcat dialer, the local engine's room store, and the rotate checklist.
//!
//! Rooms share reachability, never data (ADR 0004): joined member engines
//! become ordinary registry entries, so the existing Devices/sidebar flow
//! and the composer's device picker need no changes and no new grouping
//! layer — that is the whole point of the design.
//!
//! The invite is secret-bearing (ADR 0008): it is never logged, never
//! written anywhere but the 0600 rooms file, and the QR card's copy warns
//! that anyone holding it can join the room.

use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::Arc,
};

use crate::engine_registry::{EngineKey, EngineRegistry, EngineTarget, RegistrySnapshot};
use roboco_engine::rooms::{RoomRecord, RoomStore};
use roboco_engine_client::rooms::{
    ClientRoom, JoinOutcome, ResolvedMember, RoomDialer, RoomSecretFile, join_room_members,
    mint_room_secret, revoke_room_sessions,
};
use roboco_proto::qr;
use roboco_proto::room::{RoomInvite, RoomMember};
use roboco_rpc::methods;
use serde_json::json;

/// The desktop's dialer: one tailcat forwarder per member address, the same
/// layout the pairing resolver uses. The forwarder (and its adapter child)
/// lives exactly as long as the registry entry the join adopts.
pub struct TailcatRoomDialer;

impl RoomDialer for TailcatRoomDialer {
    fn dial(&self, address: &str, data_dir: &Path) -> anyhow::Result<ResolvedMember> {
        let session = data_dir
            .join("tailcat-forwarders")
            .join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(&session)?;
        let client = roboco_engine::tailcat::TailcatClient::start(&session, address, None)?;
        Ok(ResolvedMember {
            base: client.url().trim_end_matches('/').to_string(),
            keepalive: Some(Box::new(client)),
        })
    }
}

/// One engine picked as a room member in the create flow.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MemberPick {
    /// This machine's engine (the app data dir holds its publication).
    Local,
    Engine(EngineKey),
}

/// A display label for a registry engine: its device name when the devices
/// stream has landed, else the engine identity — never a secret.
pub fn engine_label(snapshot: &RegistrySnapshot, key: &EngineKey) -> String {
    snapshot
        .engines
        .iter()
        .find(|engine| &engine.key == key)
        .map(|engine| {
            engine
                .devices
                .first()
                .map(|device| device.name.clone())
                .unwrap_or_else(|| engine.info.device_id.clone())
        })
        .unwrap_or_else(|| "Engine".into())
}

/// The local engine's book label: its device row's name, else a stable
/// generic label (member labels are what other clients see in the book).
pub fn local_member_label(snapshot: &RegistrySnapshot) -> String {
    let engine = snapshot
        .engines
        .iter()
        .find(|engine| engine.key.is_local())
        .or_else(|| snapshot.engines.first());
    match engine {
        Some(engine) => engine
            .devices
            .first()
            .map(|device| device.name.clone())
            .unwrap_or_else(|| "Desktop".into()),
        None => "Desktop".into(),
    }
}

/// The client-visible signature of an engine already serving its Tailcat
/// route. The Tailcat transport's listener is by construction a loopback
/// serve target (`listener_bind` routes Tailcat through `serve_target`,
/// which rejects non-loopback), so an engine with remote access off, or a
/// LAN-bound network listener, never matches.
///
/// This is the C4a guard: room code must never mutate remote-access
/// settings, and `CreateTailcatInvite` force-enables and persists Tailcat —
/// so the mint only ever runs against engines already in this shape, where
/// its settings save is byte-idempotent (enabled, Tailcat transport,
/// loopback bind). Engines not in the shape are skipped with an
/// enable-first note; nothing is ever enabled for them.
async fn tailcat_shape(target: &EngineTarget) -> TailcatShape {
    let Ok(snapshot) = target.call(methods::GET_REMOTE_ACCESS, json!({})).await else {
        return TailcatShape::Unreachable;
    };
    let enabled = snapshot["status"]["enabled"].as_bool().unwrap_or(false);
    let loopback = snapshot["status"]["address"]
        .as_str()
        .and_then(|address| address.parse::<std::net::SocketAddr>().ok())
        .is_some_and(|address| address.ip().is_loopback());
    if enabled && loopback {
        TailcatShape::Serves
    } else {
        TailcatShape::Off
    }
}

/// What an engine's remote-access state says about its Tailcat route.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TailcatShape {
    /// Remote access is on and the listener is the loopback serve target:
    /// the engine runs the Tailcat transport, and the address it publishes
    /// is the one the room book wants.
    Serves,
    /// Remote access is off, or the listener is a LAN bind: room code never
    /// enables it — the user does, on that engine.
    Off,
    /// The engine could not be asked.
    Unreachable,
}

/// The fixed note for a member engine the create flow must skip: label-only,
/// telling the user what to do, never naming an address.
const ENABLE_TAILCAT_FIRST: &str = "enable its Tailcat route first (Settings → Remote access on that engine), then re-create or rotate to add it";

/// The address a room book carries for one member engine: the address the
/// engine's one-second `CreateTailcatInvite` embeds (durable transport
/// handle). The invite's single-use token is never redeemed — it expires
/// unused a second after minting; rooms present a secret, not a pair code.
/// Callers must first prove the engine already serves its Tailcat route
/// ([`tailcat_shape`]): the mint persists Tailcat settings, so it is only
/// safe where those settings are already the ones it would write.
async fn member_address(target: &EngineTarget) -> anyhow::Result<String> {
    let reply = target
        .call(methods::CREATE_TAILCAT_INVITE, json!({"ttlSeconds": 1}))
        .await
        .map_err(|_| anyhow::anyhow!("the engine's Tailcat route is unavailable"))?;
    let url = reply
        .get("url")
        .and_then(|value| value.as_str())
        .ok_or_else(|| anyhow::anyhow!("the engine minted no invite"))?;
    let invite = roboco_engine::tailcat::TailcatInvite::decode(url)?;
    Ok(invite.address)
}

/// The outcome of creating a room: the invite (secret-bearing — never
/// logged), its QR, and which selected engines could not seed the book
/// (labels only). Presentations are NOT attempted: a member engine only
/// welcomes the secret after its host consumed the invite, so the flow
/// ends by handing the user the invite to share.
#[derive(Clone, Debug)]
pub struct CreateOutcome {
    pub invite: RoomInvite,
    pub encoded: String,
    pub qr_svg: Option<String>,
    /// Selected engines that are not in the book (no Tailcat route or
    /// offline). They were skipped, not failed: the room exists, and
    /// re-sharing the invite with them (or rotating) adds them later.
    pub unavailable: Vec<String>,
}

/// The outcome of leaving a room.
#[derive(Clone, Debug)]
pub struct LeaveOutcome {
    /// Per member: (label, revoke confirmed).
    pub revoked: Vec<(String, bool)>,
    /// Whether the local engine held the room and dropped it.
    pub engine_left: bool,
}

/// The outcome of rotating a room's secret.
#[derive(Clone, Debug)]
pub struct RotateOutcome {
    pub invite: RoomInvite,
    pub encoded: String,
    pub qr_svg: Option<String>,
    pub checklist: RotateChecklist,
}

/// All room operations the Settings page drives. One data dir — the app and
/// the embedded (or daemon) engine share it, which is what lets the local
/// engine join through its own room store the way the CLI does, while the
/// listener keeps serving (WAL coordinates the two).
pub struct RoomOps {
    data_dir: PathBuf,
    registry: EngineRegistry,
    dialer: Arc<dyn RoomDialer>,
}

impl RoomOps {
    pub fn new(data_dir: PathBuf, registry: EngineRegistry) -> Self {
        Self {
            data_dir,
            registry,
            dialer: Arc::new(TailcatRoomDialer),
        }
    }

    /// The test seam: a page-level flow with a fake dialer (member addresses
    /// mapped to loopback engines) instead of spawning adapter processes.
    #[cfg(test)]
    pub fn with_dialer(
        data_dir: PathBuf,
        registry: EngineRegistry,
        dialer: Arc<dyn RoomDialer>,
    ) -> Self {
        Self {
            data_dir,
            registry,
            dialer,
        }
    }

    pub fn rooms_file(&self) -> RoomSecretFile {
        RoomSecretFile::new(self.data_dir.join(roboco_engine_client::rooms::ROOMS_FILE))
    }

    /// The rooms this client joined. An unreadable file surfaces as an empty
    /// list here; the page reads it through [`Self::load_rooms_checked`] to
    /// tell the two apart.
    pub fn load_rooms(&self) -> Vec<ClientRoom> {
        self.rooms_file().load().unwrap_or_default()
    }

    pub fn load_rooms_checked(&self) -> anyhow::Result<Vec<ClientRoom>> {
        self.rooms_file().load()
    }

    /// The address this machine's engine last published, if it serves a
    /// tailcat route (the recorded publication the pairing CLI also mints
    /// from). `None` until Remote access runs the Tailcat transport.
    pub fn local_address(&self) -> Option<String> {
        roboco_engine::tailcat::read_recorded_address(&self.data_dir)
            .ok()
            .flatten()
    }

    /// Run one operation against the local engine's room store on a
    /// blocking thread (SQLite, WAL-coordinated with the running engine).
    async fn with_local_store<T, F>(&self, operation: F) -> anyhow::Result<T>
    where
        T: Send + 'static,
        F: FnOnce(RoomStore) -> anyhow::Result<T> + Send + 'static,
    {
        let data_dir = self.data_dir.clone();
        tokio::task::spawn_blocking(move || operation(RoomStore::open(&data_dir)?))
            .await
            .map_err(|_| anyhow::anyhow!("the engine room store could not be reached"))?
    }

    /// The desktop engine joins a room through ticket-01's surface (the
    /// store the CLI drives): only a verifier and the book land in the
    /// engine; the invite's secret stays with the client.
    pub async fn local_engine_join(&self, invite: &RoomInvite) -> anyhow::Result<()> {
        let invite = invite.clone();
        self.with_local_store(move |store| store.join(invite).map(|_| ()))
            .await
    }

    /// The local engine leaves a room (forgets its verifier and book).
    /// Returns whether it held the room.
    pub async fn local_engine_leave(&self, room_id: &str) -> anyhow::Result<bool> {
        let room_id = room_id.to_owned();
        self.with_local_store(move |store| store.leave(&room_id))
            .await
    }

    /// The rooms the local engine holds, read synchronously (for callers on
    /// a non-tokio executor — the settings page's background reads). Errors
    /// degrade to an empty list: membership display, never a gate.
    pub fn local_engine_rooms_blocking(&self) -> Vec<RoomRecord> {
        RoomStore::open(&self.data_dir)
            .and_then(|store| store.list())
            .unwrap_or_default()
    }

    /// The rooms the local engine holds (membership the page shows), on a
    /// blocking thread for tokio-context callers.
    pub async fn local_engine_rooms(&self) -> Vec<RoomRecord> {
        self.with_local_store(move |store| store.list())
            .await
            .unwrap_or_default()
    }

    /// Create a room: mint the secret, seed the book from the local engine
    /// plus the selected paired engines, save the record, and join the
    /// local engine through its store. No presentations run here: a member
    /// engine only welcomes a secret once its host consumed the invite
    /// (hostless by design), so the flow hands the user the invite to share;
    /// the room's Reconnect pass presents once members have joined.
    pub async fn create(
        &self,
        name: &str,
        selected: &[MemberPick],
        local_label: &str,
    ) -> anyhow::Result<CreateOutcome> {
        let secret = mint_room_secret()?;
        let mut members = Vec::new();
        let mut unavailable: Vec<String> = Vec::new();
        let Some(local_address) = self.local_address() else {
            return Err(anyhow::anyhow!(
                "This engine has no Tailcat route — turn on Remote access with the Tailcat transport first"
            ));
        };
        members.push(RoomMember {
            label: trim_member_label(local_label),
            address: local_address,
        });
        for pick in selected {
            let MemberPick::Engine(key) = pick else {
                continue; // the local engine is already in the book
            };
            let label = engine_label(&self.registry.snapshot(), key);
            match self.registry.target(key) {
                Ok(target) => {
                    // The C4a guard: only engines already serving their
                    // Tailcat route are asked for an address. Room code
                    // never enables anything — engines without the route
                    // are skipped with an enable-first note.
                    match tailcat_shape(&target).await {
                        TailcatShape::Serves => match member_address(&target).await {
                            Ok(address) => members.push(RoomMember {
                                label: trim_member_label(&label),
                                address,
                            }),
                            Err(error) => unavailable.push(format!("{label}: {error}")),
                        },
                        TailcatShape::Off => {
                            unavailable.push(format!("{label}: {ENABLE_TAILCAT_FIRST}"))
                        }
                        TailcatShape::Unreachable => {
                            unavailable.push(format!("{label}: engine is offline"))
                        }
                    }
                }
                Err(_) => unavailable.push(format!("{label}: engine is offline")),
            }
        }
        let invite = RoomInvite::new(name, &secret, members.clone())?;
        let room = ClientRoom {
            id: invite.room_id(),
            name: invite.name.clone(),
            secret: invite.secret.clone(),
            joined_at: now_ms(),
            members,
            sessions: Vec::new(),
        };
        // Durable before anything else: the secret survives an interrupted
        // create, and the room card's Reconnect finishes the introductions.
        self.rooms_file().upsert(room.clone())?;
        // The creator's engine is a member (its host ran the join).
        self.local_engine_join(&invite).await?;
        let encoded = invite.encode()?;
        Ok(CreateOutcome {
            invite,
            qr_svg: qr::try_qr_svg(&encoded),
            encoded,
            unavailable,
        })
    }

    /// Join a room from a pasted invite: decode (fixed-text errors only),
    /// persist the record, join the local engine through its store, then
    /// present to every member — joined engines appear as ordinary paired
    /// engines in the existing device lists.
    pub async fn join(&self, invite_text: &str) -> anyhow::Result<JoinOutcome> {
        let invite = RoomInvite::decode(invite_text.trim())?;
        self.join_decoded(invite).await
    }

    /// Join from an already-decoded invite (the create flow's twin).
    pub async fn join_decoded(&self, invite: RoomInvite) -> anyhow::Result<JoinOutcome> {
        let local_address = self.local_address();
        let room = ClientRoom::from_invite(&invite);
        // Durable before dialing: presentations are repeatable, so an
        // interrupted join self-heals by re-running the pass.
        self.rooms_file().upsert(room.clone())?;
        self.local_engine_join(&invite).await?;
        let outcome = join_room_members(
            &self.registry,
            self.dialer.clone(),
            &self.data_dir,
            room,
            local_address.as_deref(),
        )
        .await?;
        self.rooms_file().upsert(outcome.room.clone())?;
        Ok(outcome)
    }

    /// Re-run the member join pass for a saved room (reconnect): live
    /// members are untouched, offline members are re-dialed and
    /// re-presented, and the address books union again.
    pub async fn sync(&self, room_id: &str) -> anyhow::Result<Option<JoinOutcome>> {
        let Some(room) = self
            .load_rooms_checked()?
            .into_iter()
            .find(|room| room.id == room_id)
        else {
            return Ok(None);
        };
        let local_address = self.local_address();
        let outcome = join_room_members(
            &self.registry,
            self.dialer.clone(),
            &self.data_dir,
            room,
            local_address.as_deref(),
        )
        .await?;
        self.rooms_file().upsert(outcome.room.clone())?;
        Ok(Some(outcome))
    }

    /// Leave a room: forget the room (client record and the local engine's
    /// verifier), revoke this client's own sessions best-effort on every
    /// member, and forget the registry entries the room's joins adopted.
    /// An engine paired by hand keeps its own entry — only the keys this
    /// room's sessions recorded are forgotten.
    pub async fn leave(&self, room_id: &str) -> anyhow::Result<LeaveOutcome> {
        let Some(room) = self
            .load_rooms_checked()?
            .into_iter()
            .find(|room| room.id == room_id)
        else {
            anyhow::bail!("that room is not joined by this device");
        };
        let revoked = revoke_room_sessions(&self.registry, &room).await;
        for session in &room.sessions {
            let _ = self
                .registry
                .forget(&EngineKey(session.engine_key.clone()))
                .await;
        }
        self.rooms_file().remove(room_id)?;
        let engine_left = self.local_engine_leave(room_id).await?;
        Ok(LeaveOutcome {
            revoked,
            engine_left,
        })
    }

    /// Rotate a room's secret: mint a new one, keep the name and the unioned
    /// book, join the local engine into the new room, revoke this client's
    /// old sessions, and return the guided checklist plus the new invite to
    /// re-share. Members re-present once their hosts consume the new invite
    /// — the room's Reconnect pass then records who has ([
    /// RotateChecklist::refresh_re_presented]). The old verifier keeps
    /// accepting the old secret on every engine until its host drops the old
    /// room — the checklist says exactly that (eviction is
    /// rotate-then-revoke, ADR 0011).
    pub async fn rotate(&self, room_id: &str) -> anyhow::Result<RotateOutcome> {
        let Some(old) = self
            .load_rooms_checked()?
            .into_iter()
            .find(|room| room.id == room_id)
        else {
            anyhow::bail!("that room is not joined by this device");
        };
        // Probe the old secret first, while the old sessions are still ours
        // to talk through: a readable old book means the old verifier still
        // welcomes presentations there.
        let mut old_secret: HashMap<String, OldSecretStatus> = HashMap::new();
        for session in &old.sessions {
            let status = match self.registry.target(&EngineKey(session.engine_key.clone())) {
                Ok(target) => {
                    match roboco_engine_client::rooms::fetch_room_book(
                        &target,
                        &old.id,
                        &old.secret,
                    )
                    .await
                    {
                        Ok(Some(_)) => OldSecretStatus::StillAccepted,
                        Ok(None) => OldSecretStatus::Refused,
                        Err(_) => OldSecretStatus::Unchecked,
                    }
                }
                Err(_) => OldSecretStatus::Unchecked,
            };
            old_secret.insert(session.address.clone(), status);
        }
        // The new secret and the new room record.
        let new_secret = mint_room_secret()?;
        let invite = RoomInvite::new(&old.name, &new_secret, old.members.clone())?;
        let new_room = ClientRoom::from_invite(&invite);
        // The local engine joins the new room; the old verifier stays until
        // the user drops it (Self::local_engine_leave) — by design.
        self.local_engine_join(&invite).await?;
        // Retire the old client record only after the new one is saved.
        self.rooms_file().upsert(new_room.clone())?;
        self.rooms_file().remove(&old.id)?;
        // Cut this client off from the old room: revoke the old sessions,
        // forget the entries they adopted.
        let revoked = revoke_room_sessions(&self.registry, &old).await;
        let revoked_addresses: HashSet<String> = old
            .members
            .iter()
            .filter(|member| {
                revoked
                    .iter()
                    .any(|(label, confirmed)| *confirmed && *label == member.label)
            })
            .map(|member| member.address.clone())
            .collect();
        for session in &old.sessions {
            let _ = self
                .registry
                .forget(&EngineKey(session.engine_key.clone()))
                .await;
        }
        let checklist = RotateChecklist {
            old_room_id: old.id.clone(),
            new_room_id: new_room.id.clone(),
            local_old_verifier: self.local_engine_holds(&old.id).await,
            rows: rotate_rows(
                &new_room.members,
                &HashSet::new(),
                &old_secret,
                &revoked_addresses,
            ),
        };
        let encoded = invite.encode()?;
        Ok(RotateOutcome {
            invite,
            qr_svg: qr::try_qr_svg(&encoded),
            encoded,
            checklist,
        })
    }

    /// Whether the local engine still holds a room (its verifier accepts the
    /// secret — the rotate checklist's "drop the old room here" fact).
    pub async fn local_engine_holds(&self, room_id: &str) -> bool {
        self.local_engine_rooms()
            .await
            .iter()
            .any(|record| record.id == room_id)
    }
}

fn trim_member_label(label: &str) -> String {
    let trimmed = label.trim();
    let mut out = String::with_capacity(trimmed.len());
    for character in trimmed.chars() {
        if !char::is_control(character) {
            out.push(character);
        }
    }
    if out.len() > roboco_proto::room::MAX_LABEL_BYTES {
        out.truncate(roboco_proto::room::MAX_LABEL_BYTES);
    }
    out
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis() as i64)
        .unwrap_or_default()
}

/// What a member engine answered about the old secret during rotation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OldSecretStatus {
    /// The old verifier still accepts the old secret there.
    StillAccepted,
    /// The old room is gone (or was never held): the old secret no longer
    /// introduces anyone.
    Refused,
    /// The member was unreachable — check again when it is online.
    Unchecked,
}

/// One member's row in the rotate checklist.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RotateRow {
    pub label: String,
    /// The member re-presented with the new secret.
    pub re_presented: bool,
    pub old_secret: OldSecretStatus,
    /// This client's old session was revoked there.
    pub revoked: bool,
}

impl RotateRow {
    /// The two status fragments the row renders. Pure.
    pub fn status_fragments(&self) -> (String, String) {
        let first = if self.re_presented {
            "Re-presented with the new secret".to_owned()
        } else {
            "Waiting to re-present".to_owned()
        };
        let second = match self.old_secret {
            OldSecretStatus::StillAccepted => {
                "Old secret still accepted — drop the old room on that host".to_owned()
            }
            OldSecretStatus::Refused => "Old secret no longer accepted".to_owned(),
            OldSecretStatus::Unchecked => "Offline — could not check the old secret".to_owned(),
        };
        (first, second)
    }
}

/// The rotate flow's guided checklist (user story 10): who has re-presented
/// with the new secret, whether the old sessions are revoked, and where the
/// old secret still works.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RotateChecklist {
    pub old_room_id: String,
    pub new_room_id: String,
    /// The local engine still holds the old verifier (drop it with the
    /// page's action; other hosts use the CLI).
    pub local_old_verifier: bool,
    pub rows: Vec<RotateRow>,
}

impl RotateChecklist {
    /// The caption under the checklist: the manual drop step. Pure.
    pub fn hint(&self) -> String {
        if self.local_old_verifier {
            format!(
                "The old room {} still accepts the old secret on this engine — drop it below. On other hosts: roboco engine room leave {}",
                short_id(&self.old_room_id),
                self.old_room_id
            )
        } else {
            format!(
                "Drop the old room on each member host: roboco engine room leave {}",
                self.old_room_id
            )
        }
    }

    /// Refresh the re-presented column after a Reconnect pass: members this
    /// client now holds a session for have re-presented with the new secret
    /// (a presentation only succeeds against a member that joined the new
    /// room). The old-secret and revoked facts stay as probed at rotate
    /// time. Pure.
    pub fn refresh_re_presented(
        &self,
        members: &[RoomMember],
        sessions: &[roboco_engine_client::rooms::RoomMemberSession],
    ) -> RotateChecklist {
        let re_presented: HashSet<String> = sessions
            .iter()
            .map(|session| session.address.clone())
            .collect();
        let rows = members
            .iter()
            .map(|member| {
                let existing = self.rows.iter().find(|row| row.label == member.label);
                RotateRow {
                    label: member.label.clone(),
                    re_presented: re_presented.contains(&member.address),
                    old_secret: existing
                        .map(|row| row.old_secret)
                        .unwrap_or(OldSecretStatus::Unchecked),
                    revoked: existing.map(|row| row.revoked).unwrap_or(false),
                }
            })
            .collect();
        RotateChecklist {
            old_room_id: self.old_room_id.clone(),
            new_room_id: self.new_room_id.clone(),
            local_old_verifier: self.local_old_verifier,
            rows,
        }
    }

    /// Whether every member has re-presented and no old secret still works.
    pub fn complete(&self) -> bool {
        !self.local_old_verifier
            && self
                .rows
                .iter()
                .all(|row| row.re_presented && row.old_secret != OldSecretStatus::StillAccepted)
    }
}

/// Build the checklist rows from the raw facts of a rotation. Pure — the
/// addresses never leave the inputs, the rows carry labels only.
pub fn rotate_rows(
    members: &[RoomMember],
    re_presented: &HashSet<String>,
    old_secret: &HashMap<String, OldSecretStatus>,
    revoked: &HashSet<String>,
) -> Vec<RotateRow> {
    members
        .iter()
        .map(|member| RotateRow {
            label: member.label.clone(),
            re_presented: re_presented.contains(&member.address),
            old_secret: old_secret
                .get(&member.address)
                .copied()
                .unwrap_or(OldSecretStatus::Unchecked),
            revoked: revoked.contains(&member.address),
        })
        .collect()
}

/// Short room id chip (`abcd1234…wxyz`) for display.
pub fn short_id(id: &str) -> String {
    if id.len() > 12 {
        format!("{}…{}", &id[..8], &id[id.len() - 4..])
    } else {
        id.to_string()
    }
}

#[cfg(test)]
mod tests;
