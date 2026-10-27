//! The client side of rooms (ADR 0011) and fleet follow: joined-room records
//! persisted beside `paired-engines-v1.json` (`rooms-v1.json`, 0600 — the
//! secret lives with clients; plain-room engines keep only verifiers), the
//! presentation call, and the per-member join that turns a `roboco-room:`
//! invite into ordinary registry entries.
//!
//! Rooms share reachability, never data (ADR 0004): a joined member engine
//! is an ordinary paired engine — listed, projected, and revocable exactly
//! like one paired by hand — so this module owns no routing and never
//! touches `ScopedId` semantics. An engine the registry already holds is
//! linked or reattached, never added twice (fleet follow).
//!
//! Every secret-bearing value (the room secret, member addresses) is
//! redacted in `Debug` and never appears in an error: refusals and failures
//! carry fixed text only (ADR 0008), and the rooms file is written 0600
//! atomically, like the tailcat `connect.json` entries. Room secrets are
//! minted by `roboco_proto::room::mint_room_secret`.

use std::{
    any::Any,
    fmt,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use roboco_proto::room::{RoomBook, RoomInvite, RoomKind, RoomMember};
use roboco_rpc::{RpcError, methods};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::registry::{Adoption, EngineKey, EngineRegistry, EngineTarget};

/// The joined-room file, beside `paired-engines-v1.json` in the client's
/// data dir. Additive (ADR 0005): unknown fields are ignored on read.
pub const ROOMS_FILE: &str = "rooms-v1.json";

/// The prefix of every desktop's presenter label (the label the pairing flow
/// uses, so a room-minted session reads like a paired one in the engine's
/// session table).
pub const DESKTOP_LABEL: &str = "Roboco desktop";

/// The label one desktop presents rooms under: [`DESKTOP_LABEL`] plus this
/// machine's engine device name and a short tag of its device id, e.g.
/// `Roboco desktop (Studio PC · 3f9a1c)`. An engine keys a room's
/// presentation session by (room, label) and reissues it on every repeat,
/// so two desktops presenting under one label would revoke each other's
/// session on every engine they share. Stable per machine, so a desktop's
/// own re-presentations still reuse its row.
pub fn desktop_label(device_name: &str, device_id: &str) -> String {
    let mut name: String = device_name
        .trim()
        .chars()
        .filter(|character| !character.is_control())
        .collect();
    // Room for the prefix and the tag inside the 256-byte label cap.
    while name.len() > 200 {
        name.pop();
    }
    let tag: String = device_id
        .chars()
        .filter(char::is_ascii_alphanumeric)
        .take(6)
        .collect();
    let named = !name.is_empty() && name != device_id.trim();
    match (named, tag.is_empty()) {
        (true, false) => format!("{DESKTOP_LABEL} ({name} · {tag})"),
        (true, true) => format!("{DESKTOP_LABEL} ({name})"),
        (false, false) => format!("{DESKTOP_LABEL} ({tag})"),
        (false, true) => DESKTOP_LABEL.to_owned(),
    }
}

/// The presentation reply: an ordinary session grant (`POST /room/present`).
/// The credential is a secret — `Debug` never prints it.
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomGrant {
    pub session: roboco_proto::PairedSession,
    pub credential: String,
}

impl fmt::Debug for RoomGrant {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RoomGrant")
            .field("session", &self.session)
            .finish_non_exhaustive()
    }
}

/// Present `{secret, label}` to a member engine reachable at `base`
/// (typically a local tailcat forwarder). The secret is the credential —
/// never logged, never echoed in an error; refusals describe only shape.
pub async fn present_room_member(
    base: &str,
    secret: &str,
    label: &str,
) -> anyhow::Result<RoomGrant> {
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()?;
    let response = http
        .post(format!("{base}/room/present"))
        .json(&json!({"secret": secret, "label": label}))
        .send()
        .await
        .map_err(|_| anyhow::anyhow!("could not reach the member engine"))?;
    match response.status() {
        reqwest::StatusCode::OK => response
            .json::<RoomGrant>()
            .await
            .map_err(|_| anyhow::anyhow!("invalid presentation response")),
        reqwest::StatusCode::UNAUTHORIZED => {
            Err(anyhow::anyhow!("the member refused the room secret"))
        }
        reqwest::StatusCode::TOO_MANY_REQUESTS => Err(anyhow::anyhow!(
            "the member is throttling presentations; try again later"
        )),
        reqwest::StatusCode::NOT_FOUND => {
            Err(anyhow::anyhow!("the member engine does not serve rooms"))
        }
        reqwest::StatusCode::SERVICE_UNAVAILABLE => Err(anyhow::anyhow!(
            "the member could not verify the presentation"
        )),
        _ => Err(anyhow::anyhow!("the member refused the presentation")),
    }
}

/// How a room member is reached: the production dialer starts a tailcat
/// forwarder (a `connect` adapter child) for the member's address; tests map
/// addresses to loopback engines. The keepalive keeps the forwarder process
/// alive for as long as the registry entry exists.
pub trait RoomDialer: Send + Sync {
    /// Dial `address`, returning the local base URL presentations and
    /// WebSocket connections go through. Blocking: callers run it on a
    /// blocking thread (a cold dial can block ~20 s waiting for readiness).
    fn dial(&self, address: &str, data_dir: &Path) -> anyhow::Result<ResolvedMember>;
}

/// One dialed member: where to reach it, plus the opaque keepalive (the
/// forwarder handle) the registry entry must hold.
pub struct ResolvedMember {
    pub base: String,
    pub keepalive: Option<Box<dyn Any + Send>>,
}

/// A room member this client reaches through a registry entry. When the
/// room's own presentation created the entry, `session_id` names the
/// minted session (leave revokes it, and forgets the entry); `None` means
/// the room is linked to an entry it did not create — an engine paired by
/// hand, or one the registry already held — which leave never touches.
/// The address is redacted in `Debug` — it is a transport secret.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomMemberSession {
    pub address: String,
    pub engine_key: String,
    #[serde(default)]
    pub session_id: Option<String>,
}

impl RoomMemberSession {
    /// Whether the room's own presentation created this registry entry.
    pub fn adopted(&self) -> bool {
        self.session_id.is_some()
    }
}

impl fmt::Debug for RoomMemberSession {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RoomMemberSession")
            .field("address", &roboco_proto::room::describe(&self.address))
            .field("engine_key", &self.engine_key)
            .field("session_id", &self.session_id)
            .finish()
    }
}

/// A room this client joined, as persisted in `rooms-v1.json`: the secret
/// (secret-bearing — the client re-presents it), the room kind, the unioned
/// address book, and the registry entries the room reaches its members
/// through. `Debug` redacts the secret and every member address.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientRoom {
    pub id: String,
    pub name: String,
    pub secret: String,
    pub joined_at: i64,
    #[serde(default)]
    pub kind: RoomKind,
    #[serde(default)]
    pub members: Vec<RoomMember>,
    #[serde(default)]
    pub sessions: Vec<RoomMemberSession>,
}

impl fmt::Debug for ClientRoom {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ClientRoom")
            .field("id", &self.id)
            .field("name", &self.name)
            .field("secret", &roboco_proto::room::describe(&self.secret))
            .field("joined_at", &self.joined_at)
            .field("kind", &self.kind)
            .field("members", &self.members)
            .field("sessions", &self.sessions)
            .finish()
    }
}

impl ClientRoom {
    /// The room as an invite (the shareable, secret-bearing form): name,
    /// secret, kind, and the unioned book.
    pub fn invite(&self) -> Result<RoomInvite, roboco_proto::room::RoomInviteError> {
        let invite = RoomInvite::new(&self.name, &self.secret, self.members.clone())?;
        Ok(RoomInvite {
            kind: self.kind,
            ..invite
        })
    }

    /// The room record for a decoded invite, before any member is dialed.
    pub fn from_invite(invite: &RoomInvite) -> Self {
        Self {
            id: invite.room_id(),
            name: invite.name.clone(),
            secret: invite.secret.clone(),
            joined_at: now_ms(),
            kind: invite.kind,
            members: invite.members.clone(),
            sessions: Vec::new(),
        }
    }

    pub fn is_fleet(&self) -> bool {
        self.kind == RoomKind::Fleet
    }

    /// Record that `address` is reached through `engine_key`, an entry the
    /// room did not create (no session to revoke on leave). An existing
    /// record for the address is kept as is.
    pub fn link(&mut self, address: &str, engine_key: &EngineKey) {
        if self
            .sessions
            .iter()
            .any(|session| session.address == address)
        {
            return;
        }
        self.sessions.push(RoomMemberSession {
            address: address.to_owned(),
            engine_key: engine_key.0.clone(),
            session_id: None,
        });
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis() as i64)
        .unwrap_or_default()
}

/// Write `bytes` to `path` atomically (tempfile + fsync + rename), owner-only
/// on platforms with a mode bit. The shape every client secret file uses.
pub fn write_private_file(path: &Path, bytes: &[u8]) -> anyhow::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("invalid client file path"))?;
    std::fs::create_dir_all(parent)?;
    let mut temp = tempfile::NamedTempFile::new_in(parent)?;
    use std::io::Write;
    temp.write_all(bytes)?;
    temp.as_file().sync_all()?;
    // The temp file is already 0600; make it explicit.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = temp.as_file().metadata()?.permissions();
        permissions.set_mode(0o600);
        temp.as_file().set_permissions(permissions)?;
    }
    temp.persist(path).map_err(|error| error.error)?;
    Ok(())
}

/// The joined-room store: `rooms-v1.json` in the client data dir, written
/// atomically (tempfile + fsync + rename) with 0600 discipline — the room
/// secret is a usable secret, exactly like the paired-engine credentials
/// that live beside it.
#[derive(Debug, Clone)]
pub struct RoomSecretFile {
    path: PathBuf,
}

#[derive(Default, Serialize, Deserialize)]
struct SavedRooms {
    #[serde(default)]
    rooms: Vec<ClientRoom>,
}

impl RoomSecretFile {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    /// `Ok(vec![])` when nothing is joined yet; a present-but-unreadable
    /// file is an error the caller surfaces (the file is preserved).
    pub fn load(&self) -> anyhow::Result<Vec<ClientRoom>> {
        let bytes = match std::fs::read(&self.path) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Vec::new());
            }
            Err(error) => return Err(error.into()),
        };
        // serde's message can quote a value from the file: fixed text only.
        let saved: SavedRooms = serde_json::from_slice(&bytes)
            .map_err(|_| anyhow::anyhow!("the rooms file could not be read"))?;
        Ok(saved.rooms)
    }

    /// Atomically replace the saved set (0600).
    pub fn save(&self, rooms: &[ClientRoom]) -> anyhow::Result<()> {
        let saved = SavedRooms {
            rooms: rooms.to_vec(),
        };
        write_private_file(&self.path, &serde_json::to_vec(&saved)?)
    }

    /// Insert or refresh one room (same id), keeping order stable.
    pub fn upsert(&self, room: ClientRoom) -> anyhow::Result<()> {
        let mut rooms = self.load()?;
        match rooms.iter_mut().find(|saved| saved.id == room.id) {
            Some(saved) => *saved = room,
            None => rooms.push(room),
        }
        self.save(&rooms)
    }

    /// Remove one room; `None` when it was not saved.
    pub fn remove(&self, id: &str) -> anyhow::Result<Option<ClientRoom>> {
        let mut rooms = self.load()?;
        let Some(index) = rooms.iter().position(|saved| saved.id == id) else {
            return Ok(None);
        };
        let removed = rooms.remove(index);
        self.save(&rooms)?;
        Ok(Some(removed))
    }
}

/// Union-merge book entries (the ADR 0011 book rule: entries are only ever
/// added; a stale address is corrected by a fresh entry, never deleted).
/// Deterministic order: sorted by `(label, address)`, exact pairs deduped.
pub fn union_members(members: &mut Vec<RoomMember>, extra: Vec<RoomMember>) {
    members.extend(extra);
    members.sort_by(|a, b| (&a.label, &a.address).cmp(&(&b.label, &b.address)));
    members.dedup_by(|a, b| a.label == b.label && a.address == b.address);
}

/// Poll until the target's entry is live (or the budget drains). Adopting
/// an engine connects its WebSocket immediately, but its supervision
/// finishes the watch subscriptions a beat later; callers that want to
/// talk to a just-adopted member wait here first.
pub async fn await_live(target: &EngineTarget, budget: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + budget;
    loop {
        if target.is_connected() {
            return true;
        }
        if tokio::time::Instant::now() >= deadline {
            return false;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// One member's join failure, for the join report. The error is fixed text —
/// never the address, never the secret.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MemberFailure {
    pub label: String,
    pub error: String,
}

/// The outcome of presenting one room to all its members.
#[derive(Clone, Debug)]
pub struct JoinOutcome {
    /// The room record with its sessions — save it after the pass.
    pub room: ClientRoom,
    /// Members that could not be joined (offline, refused, or too old);
    /// presentations are repeatable, so a later pass finishes the job.
    pub failures: Vec<MemberFailure>,
}

impl JoinOutcome {
    /// Whether every member of the book was joined by this client.
    pub fn complete(&self) -> bool {
        self.failures.is_empty()
    }
}

/// Join every member of `room` this client does not reach yet: dial each
/// member address, present the room secret, and adopt the granted session
/// into the registry as an ordinary paired engine. Members whose registry
/// entry is already live are skipped (no re-dial, no session churn);
/// members matching `local_address` (this machine's engine) are skipped
/// entirely — the local engine joins through its own room store, never over
/// the network. An engine the registry already holds is reattached under
/// its key, never duplicated. Failures are collected per member: joining is
/// deliberately non-atomic and repeatable.
///
/// After the pass, the address book is unioned with every reachable
/// member's book (`GetRoomBook`) and the merged book is pushed back
/// (`UpdateRoomBook`) — books merge as unions, maintained by clients
/// (ADR 0011); engines never gossip with each other.
///
/// `presenter` is the label presentations carry: it must be unique to this
/// client ([`desktop_label`]), because an engine reissues — and so revokes
/// — the session of an earlier presentation with the same label.
pub async fn join_room_members(
    registry: &EngineRegistry,
    dialer: Arc<dyn RoomDialer>,
    data_dir: &Path,
    room: ClientRoom,
    local_address: Option<&str>,
    presenter: &str,
) -> anyhow::Result<JoinOutcome> {
    let mut room = room;
    let mut failures = Vec::new();
    let members = room.members.clone();
    for member in members {
        if local_address == Some(member.address.as_str()) {
            continue;
        }
        // A live entry means the forwarder is alive and the session works:
        // presentations are repeatable, so re-presenting adds nothing.
        if let Some(session) = room
            .sessions
            .iter()
            .find(|session| session.address == member.address)
            && let Ok(target) = registry.target(&EngineKey(session.engine_key.clone()))
            && target.is_connected()
        {
            continue;
        }
        // A record whose entry is not live is retried: the re-dial reattaches
        // the entry under its key (same engine identity) instead of adding one.
        let stale = room
            .sessions
            .iter()
            .position(|session| session.address == member.address)
            .map(|index| room.sessions.remove(index));
        match dial_and_present(
            registry,
            dialer.clone(),
            data_dir,
            &room,
            &member,
            presenter,
        )
        .await
        {
            Ok((mut session, adoption)) => {
                // Ownership: a fresh entry is the room's; a reattached one is
                // the room's only when the room created it in the first place.
                if adoption == Adoption::Reattached {
                    let owned = stale.as_ref().is_some_and(|stale| {
                        stale.adopted() && stale.engine_key == session.engine_key
                    }) || room
                        .sessions
                        .iter()
                        .any(|other| other.adopted() && other.engine_key == session.engine_key);
                    if !owned {
                        session.session_id = None;
                    }
                }
                // A stale room-owned entry the fresh dial did not reattach
                // (its engine changed identity) is retired.
                if let Some(stale) = stale
                    && stale.adopted()
                    && stale.engine_key != session.engine_key
                {
                    let _ = registry.forget(&EngineKey(stale.engine_key)).await;
                }
                room.sessions.push(session);
            }
            Err(error) => {
                if let Some(stale) = stale {
                    // Keep the record: the entry may come back on its own,
                    // and a later pass retries the dial.
                    room.sessions.push(stale);
                }
                failures.push(MemberFailure {
                    label: member.label,
                    error,
                });
            }
        }
    }
    heal_room_books(registry, &mut room).await;
    Ok(JoinOutcome { room, failures })
}

/// Book maintenance: union every reachable member's book into the room's,
/// then push the merged book back so every member learns the union too.
/// Entries adopted moments ago get a bounded moment to finish connecting
/// before one is treated as unreachable.
pub async fn heal_room_books(registry: &EngineRegistry, room: &mut ClientRoom) {
    heal_room_books_with(registry, room, &[]).await;
}

/// [`heal_room_books`] over the room's entries plus `extra` — engines the
/// caller just pushed the room to (`RoomJoin`) whose address it has not
/// linked yet, so they still receive the union.
pub async fn heal_room_books_with(
    registry: &EngineRegistry,
    room: &mut ClientRoom,
    extra: &[EngineKey],
) {
    let mut keys: Vec<String> = room
        .sessions
        .iter()
        .map(|session| session.engine_key.clone())
        .chain(extra.iter().map(|key| key.0.clone()))
        .collect();
    keys.sort();
    keys.dedup();
    let mut live = Vec::new();
    for key in keys {
        let Ok(target) = registry.target(&EngineKey(key)) else {
            continue;
        };
        if !await_live(&target, Duration::from_secs(10)).await {
            continue;
        }
        if let Ok(Some(book)) = fetch_room_book(&target, &room.id, &room.secret).await {
            let mut members = room.members.clone();
            union_members(&mut members, book.members);
            room.members = members;
        }
        live.push(target);
    }
    for target in live {
        let _ = push_room_book(&target, &room.id, room.members.clone()).await;
    }
}

/// Dial one member and present the room secret, adopting the granted
/// session into the registry. The returned error is fixed text — the
/// underlying one can quote a loopback URL, never the secret.
async fn dial_and_present(
    registry: &EngineRegistry,
    dialer: Arc<dyn RoomDialer>,
    data_dir: &Path,
    room: &ClientRoom,
    member: &RoomMember,
    presenter: &str,
) -> Result<(RoomMemberSession, Adoption), String> {
    let address = member.address.clone();
    let data_dir = data_dir.to_path_buf();
    let dialed = tokio::task::spawn_blocking(move || dialer.dial(&address, &data_dir))
        .await
        .map_err(|_| "the member dial was interrupted".to_owned())?
        .map_err(|_| "the member could not be reached".to_owned())?;
    registry
        .join_room_member(
            &dialed.base,
            &member.address,
            &room.secret,
            presenter,
            dialed.keepalive,
            None,
        )
        .await
        .map_err(|error| {
            // The member label is not a secret; the underlying error is
            // fixed text or a loopback URL, never the secret.
            tracing::warn!(member = %member.label, error = %error, "room member join failed");
            "the member has not joined yet".to_owned()
        })
}

/// Best-effort revoke of the sessions this room's presentations created —
/// leave drives it. Entries the room only linked to (paired by hand) are
/// never revoked. Returns `(member label, confirmed)` pairs.
pub async fn revoke_room_sessions(
    registry: &EngineRegistry,
    room: &ClientRoom,
) -> Vec<(String, bool)> {
    let mut revoked = Vec::new();
    for session in &room.sessions {
        let Some(session_id) = session.session_id.clone() else {
            continue;
        };
        let label = room
            .members
            .iter()
            .find(|member| member.address == session.address)
            .map(|member| member.label.clone())
            .unwrap_or_else(|| "member".into());
        let confirmed = match registry.target(&EngineKey(session.engine_key.clone())) {
            Ok(target) => target
                .call(
                    methods::REVOKE_PAIRING_SESSION,
                    json!({"sessionId": session_id}),
                )
                .await
                .is_ok(),
            Err(_) => false,
        };
        revoked.push((label, confirmed));
    }
    revoked
}

/// Read a member's book for `room_id`, presenting the room secret (member
/// addresses are transport secrets, so a session alone is not enough).
/// `Ok(None)` = the room is unknown there or the secret was refused —
/// indistinguishable by design.
pub async fn fetch_room_book(
    target: &EngineTarget,
    room_id: &str,
    secret: &str,
) -> Result<Option<RoomBook>, RpcError> {
    let reply = target
        .call(
            methods::GET_ROOM_BOOK,
            json!({"roomId": room_id, "secret": secret}),
        )
        .await;
    match reply {
        Ok(value) => serde_json::from_value::<RoomBook>(value)
            .map(Some)
            .map_err(|_| RpcError::Failed("invalid room book reply".into())),
        Err(RpcError::Failed(message))
            if message.contains("room not found or the presented secret was refused") =>
        {
            Ok(None)
        }
        Err(error) => Err(error),
    }
}

/// Push `members` into the member's book (union) and reply with the fresh
/// snapshot. Books only grow; eviction rotates the room (ADR 0011).
pub async fn push_room_book(
    target: &EngineTarget,
    room_id: &str,
    members: Vec<RoomMember>,
) -> Result<RoomBook, RpcError> {
    let value = target
        .call(
            methods::UPDATE_ROOM_BOOK,
            json!({"roomId": room_id, "members": members}),
        )
        .await?;
    serde_json::from_value::<RoomBook>(value)
        .map_err(|_| RpcError::Failed("invalid room book reply".into()))
}

/// Fleet follow's remote join (`RoomJoin`, rooms-v2): push the encoded
/// invite to a member engine over its paired session. Idempotent on the
/// engine (same secret = same row; the book unions). The invite is a secret:
/// it rides only the authenticated session, and no error here quotes it.
pub async fn push_room_join(target: &EngineTarget, encoded: &str) -> Result<String, RpcError> {
    let value = target
        .call(methods::ROOM_JOIN, json!({"invite": encoded}))
        .await?;
    value
        .get("roomId")
        .and_then(|id| id.as_str())
        .map(str::to_owned)
        .ok_or_else(|| RpcError::Failed("invalid room join reply".into()))
}

/// The fixed text an engine answers when a fleet join meets a different
/// fleet it already holds (`FleetConflict` on the engine side).
pub const FLEET_CONFLICT_TEXT: &str = "this engine already belongs to a fleet";

/// Whether an RPC error is the engine's one-fleet-per-data-dir refusal.
pub fn is_fleet_conflict(error: &RpcError) -> bool {
    matches!(error, RpcError::Failed(message) if message.contains(FLEET_CONFLICT_TEXT))
}

#[cfg(test)]
mod tests;
