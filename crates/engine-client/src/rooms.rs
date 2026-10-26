//! The client side of rooms (ADR 0011): joined-room records persisted beside
//! `paired-engines-v1.json` (`rooms-v1.json`, 0600 — the secret lives with
//! clients, engines keep only verifiers), the presentation call, and the
//! per-member join that turns a `roboco-room:` invite into ordinary registry
//! entries.
//!
//! Rooms share reachability, never data (ADR 0004): a joined member engine
//! is an ordinary paired engine — listed, projected, and revocable exactly
//! like one paired by hand — so this module owns no routing and never
//! touches `ScopedId` semantics.
//!
//! Every secret-bearing value (the room secret, member addresses) is
//! redacted in `Debug` and never appears in an error: refusals and failures
//! carry fixed text only (ADR 0008), and the rooms file is written 0600
//! atomically, like the tailcat `connect.json` entries.

use std::{
    any::Any,
    fmt,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use roboco_proto::room::{RoomBook, RoomInvite, RoomMember};
use roboco_rpc::{RpcError, methods};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::registry::{EngineKey, EngineRegistry, EngineTarget};

/// The joined-room file, beside `paired-engines-v1.json` in the client's
/// data dir. Additive (ADR 0005): unknown fields are ignored on read.
pub const ROOMS_FILE: &str = "rooms-v1.json";

/// The presenter label the desktop joins rooms under (the same label the
/// pairing flow uses, so a room-minted session is indistinguishable in the
/// engine's session table from a paired one).
pub const DESKTOP_LABEL: &str = "Roboco desktop";

/// Mint a fresh room secret: 32 bytes of OS randomness, base64url-encoded —
/// the same shape as a pairing secret (`pairing::secret`), and the shape
/// `RoomInvite::new` documents for callers.
pub fn mint_room_secret() -> anyhow::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes)
        .map_err(|_| anyhow::anyhow!("operating system randomness unavailable"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
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

/// A room member this client successfully presented to: the registry entry
/// the presentation adopted, and the session it minted (leave revokes it by
/// id). The address is redacted in `Debug` — it is a transport secret.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomMemberSession {
    pub address: String,
    pub engine_key: String,
    pub session_id: String,
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
/// (secret-bearing — engines hold only verifiers, so the client must keep
/// it to re-present), the unioned address book, and the registry sessions
/// its joins minted. `Debug` redacts the secret and every member address.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientRoom {
    pub id: String,
    pub name: String,
    pub secret: String,
    pub joined_at: i64,
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
            .field("members", &self.members)
            .field("sessions", &self.sessions)
            .finish()
    }
}

impl ClientRoom {
    /// The room as an invite (the shareable, secret-bearing form): name,
    /// secret, and the unioned book.
    pub fn invite(&self) -> Result<RoomInvite, roboco_proto::room::RoomInviteError> {
        RoomInvite::new(&self.name, &self.secret, self.members.clone())
    }

    /// The room record for a decoded invite, before any member is dialed.
    pub fn from_invite(invite: &RoomInvite) -> Self {
        Self {
            id: invite.room_id(),
            name: invite.name.clone(),
            secret: invite.secret.clone(),
            joined_at: now_ms(),
            members: invite.members.clone(),
            sessions: Vec::new(),
        }
    }

    /// This room's per-member state as the settings page shows it: the
    /// book's entries, annotated with whether this client holds a session
    /// for that member and whether its registry entry is live. Pure
    /// projection — addresses never leave the record.
    pub fn member_rows(&self, registry: &EngineRegistry) -> Vec<MemberRow> {
        self.members
            .iter()
            .map(|member| {
                let session = self
                    .sessions
                    .iter()
                    .find(|session| session.address == member.address);
                MemberRow {
                    label: member.label.clone(),
                    joined: session.is_some(),
                    connected: session
                        .and_then(|session| {
                            registry
                                .target(&EngineKey(session.engine_key.clone()))
                                .ok()
                                .map(|target| target.is_connected())
                        })
                        .unwrap_or(false),
                }
            })
            .collect()
    }
}

/// One member of a room as rendered by the settings page.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MemberRow {
    pub label: String,
    /// This client presented to the member (a session exists).
    pub joined: bool,
    /// The member's registry entry is live right now.
    pub connected: bool,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis() as i64)
        .unwrap_or_default()
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
        let saved: SavedRooms = serde_json::from_slice(&bytes)?;
        Ok(saved.rooms)
    }

    /// Atomically replace the saved set (0600).
    pub fn save(&self, rooms: &[ClientRoom]) -> anyhow::Result<()> {
        let saved = SavedRooms {
            rooms: rooms.to_vec(),
        };
        let parent = self
            .path
            .parent()
            .ok_or_else(|| anyhow::anyhow!("invalid rooms file path"))?;
        std::fs::create_dir_all(parent)?;
        let mut temp = tempfile::NamedTempFile::new_in(parent)?;
        use std::io::Write;
        temp.write_all(&serde_json::to_vec(&saved)?)?;
        temp.as_file().sync_all()?;
        // The secret lives in this file: keep it owner-only on platforms
        // with a mode bit (the temp file is already 0600; make it explicit).
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut permissions = temp.as_file().metadata()?.permissions();
            permissions.set_mode(0o600);
            temp.as_file().set_permissions(permissions)?;
        }
        temp.persist(&self.path).map_err(|error| error.error)?;
        Ok(())
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

    /// Every room whose id is not in `keep` — rotation retires the old
    /// secret's record once the new one is saved.
    pub fn remove_except(&self, keep: &[String]) -> anyhow::Result<Vec<ClientRoom>> {
        let rooms = self.load()?;
        let (kept, removed): (Vec<_>, Vec<_>) =
            rooms.into_iter().partition(|room| keep.contains(&room.id));
        self.save(&kept)?;
        Ok(removed)
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
async fn await_live(target: &EngineTarget, budget: Duration) -> bool {
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
    /// The room record with its minted sessions — save it after the pass.
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

/// Join every member of `room` this client has not yet presented to:
/// dial each member address, present the room secret, and adopt the granted
/// session into the registry as an ordinary paired engine. Members whose
/// registry entry is already live are skipped (their forwarder is alive);
/// members matching `local_address` (this machine's engine) are skipped
/// entirely — the local engine joins through its own room store, never over
/// the network. Failures are collected per member: joining is deliberately
/// non-atomic and repeatable.
///
/// After the pass, the address book is unioned with every reachable
/// member's book (`GetRoomBook`) and the merged book is pushed back
/// (`UpdateRoomBook`) — books merge as unions, maintained by clients
/// (ADR 0011); engines never gossip with each other.
pub async fn join_room_members(
    registry: &EngineRegistry,
    dialer: Arc<dyn RoomDialer>,
    data_dir: &Path,
    room: ClientRoom,
    local_address: Option<&str>,
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
        // Retire the dead entry first: a stale forwarder URL (a restart
        // since the join) reconnects forever, while a fresh dial adopts a
        // working entry in its place.
        if let Some(index) = room
            .sessions
            .iter()
            .position(|session| session.address == member.address)
        {
            let stale = room.sessions.remove(index);
            let _ = registry.forget(&EngineKey(stale.engine_key)).await;
        }
        match dial_and_present(registry, dialer.clone(), data_dir, &room, &member).await {
            Ok(session) => room.sessions.push(session),
            Err(error) => failures.push(MemberFailure {
                label: member.label,
                error,
            }),
        }
    }
    // Book maintenance: union every reachable member's book into ours, then
    // push the merged book back so every member learns the union too. The
    // entries were adopted moments ago; give their supervision a bounded
    // moment to finish connecting before treating one as unreachable.
    for session in room.sessions.clone() {
        let Ok(target) = registry.target(&EngineKey(session.engine_key.clone())) else {
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
    }
    for session in room.sessions.clone() {
        let Ok(target) = registry.target(&EngineKey(session.engine_key.clone())) else {
            continue;
        };
        if !await_live(&target, Duration::from_secs(10)).await {
            continue;
        }
        let _ = push_room_book(&target, &room.id, room.members.clone()).await;
    }
    Ok(JoinOutcome { room, failures })
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
) -> Result<RoomMemberSession, String> {
    let address = member.address.clone();
    let data_dir = data_dir.to_path_buf();
    let dialed = tokio::task::spawn_blocking(move || dialer.dial(&address, &data_dir))
        .await
        .map_err(|_| "the member dial was interrupted".to_owned())?
        .map_err(|_| "the member could not be dialed".to_owned())?;
    registry
        .join_room_member(
            &dialed.base,
            &member.address,
            &room.secret,
            DESKTOP_LABEL,
            dialed.keepalive,
        )
        .await
        .map_err(|error| {
            // The member label is not a secret; the underlying error is
            // logged (it can carry a loopback URL, never the secret).
            tracing::warn!(member = %member.label, error = %error, "room member join failed");
            "the member refused the room secret or does not serve rooms".to_owned()
        })
}

/// Best-effort revoke of the sessions this room minted, per member engine —
/// leaving and rotating both drive it (a kicked member's credentials stay
/// valid on every member engine until individually revoked, so the
/// checklist surfaces per-engine revokes; ADR 0011). Returns
/// `(member label, confirmed)` pairs.
pub async fn revoke_room_sessions(
    registry: &EngineRegistry,
    room: &ClientRoom,
) -> Vec<(String, bool)> {
    let mut revoked = Vec::new();
    for session in &room.sessions {
        let label = room
            .members
            .iter()
            .find(|member| member.address == session.address)
            .map(|member| member.label.clone())
            .unwrap_or_else(|| "member".into());
        let confirmed = match registry.target(&EngineKey(session.engine_key.clone())) {
            Ok(target) => {
                let session_id = session.session_id.clone();
                target
                    .call(
                        methods::REVOKE_PAIRING_SESSION,
                        json!({"sessionId": session_id}),
                    )
                    .await
                    .is_ok()
            }
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

#[cfg(test)]
mod tests;
