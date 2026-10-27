//! Joining rooms on the phone (ADR 0011, ticket 04): scan once, join every
//! member, self-heal.
//!
//! A room is a hostless reachability group — a shared secret plus an
//! address book, copied to every member ([`roboco_proto::room`]). Joining
//! mints one ordinary session per member: the phone dials each book
//! member's address directly (a Direct `http(s)` base URL, or through the
//! per-engine [`TailcatRoute`](crate::engines::route) machinery — exactly
//! the two transports a pairing can produce), POSTs `/room/present
//! {secret, label}`, and stores the resulting [`crate::PairedEngine`]
//! like a pairing. Rooms share reachability, never data (ADR 0004):
//! nothing here syncs or relays.
//!
//! The room secret is **not** at rest in this crate: the platform hands
//! the joined rooms back on every `Client::new` (Keystore-encrypted
//! `room:` blobs on Android) and Rust keeps them in memory only, exactly
//! like session credentials.
//!
//! Partial joins are normal (C6: join atomicity): a member that was
//! asleep during the join simply stays unjoined — presentations are
//! repeatable, so the next heal pass picks it up. No rollback, no
//! per-failure bookkeeping.
//!
//! **Book maintenance** is client-driven: the heal pass unions the books
//! of every reachable member (`GetRoomBook`, which additionally presents
//! the secret because member addresses are transport secrets), dials the
//! members it discovers (that is the dial-verify), and pushes the merged
//! book back (`UpdateRoomBook`). Engines never gossip with each other.
//!
//! **Battery envelope.** The heal pass rides the existing machinery's
//! trigger moments and adds no cadence of its own: it runs once at client
//! start (beside the supervisors that dial every engine) and once per
//! network-change event (beside the `Verify` path that rebuilds Tailcat
//! routes — `set_network_changed`, and the offline→online transition in
//! `set_network_online`). There are no new timers, no liveness probing,
//! no wake locks and no background loops: a member that joined is an
//! ordinary paired engine whose reconnects ride the supervisor backoff,
//! and a member that never joined is retried only while the app is
//! already doing dial work. The ADR 0010 service rules are untouched —
//! the heal runs from the same client lifecycle every dial already runs
//! from, never from the service.
//!
//! **Capability hiding.** A member engine without `rooms-v1` answers
//! `/room/present` with 404 (it has no such route) and the book RPCs
//! with `unknown method`. Both map to "skipped silently" — no error
//! toast; the member just does not join and heals once that engine is
//! upgraded (ADR 0011's degrade-gracefully rule).

use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use roboco_proto::room::{RoomBook, RoomInvite, RoomInviteError, RoomMember};
use roboco_proto::PairedSession;
use serde::Deserialize;

use crate::engines::{EngineRoom, EngineTransport};
use crate::pairing::{Redeemed, sanitize_label};
use crate::{ClientInner, lock};

/// The whole HTTP budget for one presentation (the pairing redeem's 15 s:
/// a present is a redeem-shaped call).
const PRESENT_TIMEOUT: Duration = Duration::from_secs(15);

/// A joined room as the platform stores it: the secret plus the book.
/// This is the `room:` Keystore blob's plaintext — never written by Rust,
/// never `Debug`-printed without redaction, never logged.
#[derive(Clone)]
pub struct RoomRecord {
    pub room_id: String,
    pub label: String,
    pub secret: String,
    /// The phone's copy of the book: what a heal pass dials when no
    /// member is reachable to re-learn it from. Member addresses are
    /// transport secrets (ADR 0008).
    pub members: Vec<RoomMember>,
}

impl std::fmt::Debug for RoomRecord {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print the secret and
        // every member address.
        f.debug_struct("RoomRecord")
            .field("room_id", &self.room_id)
            .field("label", &self.label)
            .field("secret", &roboco_proto::room::describe(&self.secret))
            .field("members", &self.members)
            .finish()
    }
}

/// The minimum a `roboco-room:` invite carries, for the confirm UI: the
/// name and the member labels. The secret and the member addresses are
/// secrets and never cross this record (they stay inside the join call).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RoomInviteTarget {
    pub name: String,
    /// The book's member labels, in invite order (device names, like any
    /// engine label — never the addresses).
    pub member_labels: Vec<String>,
}

/// Why a room join failed. Messages are shape-only: none ever quotes the
/// invite, the secret or an address.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum RoomError {
    #[error("invalid room invite: {message}")]
    InvalidInvite { message: String },
    /// A member answered 401: no joined room matches the secret (the
    /// secret rotated, or the room was left).
    #[error("the room invite was refused")]
    Refused,
    /// A member answered 429: too many failed presentations; retry later.
    #[error("that engine throttled room presentations; try again later")]
    Throttled,
    /// No member could be reached (offline, no route, DERP blocked).
    #[error("could not reach any room member: {message}")]
    Unreachable { message: String },
    #[error("joining the room failed: {message}")]
    Failed { message: String },
}

impl From<RoomInviteError> for RoomError {
    fn from(error: RoomInviteError) -> Self {
        // The proto error's text is fixed and shape-only by construction.
        RoomError::InvalidInvite {
            message: error.to_string(),
        }
    }
}

impl From<crate::tailcat::TailcatError> for RoomError {
    fn from(error: crate::tailcat::TailcatError) -> Self {
        match error {
            crate::tailcat::TailcatError::Unavailable(message) => RoomError::Failed {
                message: format!("tailcat is not available: {message}"),
            },
            crate::tailcat::TailcatError::Unreachable(message) => RoomError::Unreachable { message },
        }
    }
}

/// What one join produced. Partial joins are the normal outcome for a
/// fleet with asleep members.
#[derive(Debug, Clone)]
pub struct RoomJoin {
    /// The room to persist: the secret plus the phone's copy of the book.
    /// The platform stores it as its `room:` blob.
    pub room: RoomRecord,
    /// Every engine record this call changed — a member that got its own
    /// session, or an already-paired engine without a room that was
    /// adopted into this one by tag (no new session) — one per engine id.
    /// The platform persists them like a pairing result, as upserts by
    /// engine id (an adopted engine is an existing row, never a second
    /// one). Members already paired with a room tag are not listed —
    /// they were skipped, not re-joined.
    pub engines: Vec<crate::PairedEngine>,
    /// Labels of book members that did not join (offline, old engine, or
    /// throttled) — informational only: they heal on the next trigger.
    pub missing: Vec<String>,
}

/// Why one member presentation did not mint a session. Kept internal: a
/// member failure is a count in a heal pass, never a user-facing error
/// by itself (offline members are a status, not an outage).
#[derive(Debug, Clone)]
enum MemberFailure {
    /// 401 — no joined room matches the secret.
    Refused,
    /// 429 — throttled by the engine's failed-presentation guard.
    Throttled,
    /// Another join for this member is already in flight (skipped, not a
    /// failure — the in-flight one owns the outcome).
    InFlight,
    /// 404 on present (an engine without `rooms-v1` has no route) or
    /// `unknown method` on the book RPCs: skipped silently, no toast.
    Unsupported,
    /// No route to the member (offline, tunnel down).
    Unreachable,
    /// The engine answered some other error. The message is carried for
    /// the shape-only debug log (never a user-facing error by itself).
    #[allow(dead_code)] // read through the Debug log only
    Failed(String),
}

impl MemberFailure {
    fn from_tailcat(error: crate::tailcat::TailcatError) -> Self {
        match error {
            crate::tailcat::TailcatError::Unavailable(message) => {
                MemberFailure::Failed(format!("tailcat is not available: {message}"))
            }
            crate::tailcat::TailcatError::Unreachable(_) => MemberFailure::Unreachable,
        }
    }
}

/// Parse a `roboco-room:` invite for the confirm UI. Neither the secret
/// nor a member address crosses this boundary.
pub fn parse_room_invite(input: &str) -> Result<RoomInviteTarget, RoomError> {
    let invite = RoomInvite::decode(input)?;
    Ok(RoomInviteTarget {
        name: invite.name,
        member_labels: invite.members.iter().map(|m| m.label.clone()).collect(),
    })
}

/// How a book member's address is dialed. An `http(s)` URL is a Direct
/// member (LAN or an operator tunnel — exactly the base URLs a pairing
/// link produces); anything else is a Tailcat address (it embeds the
/// engine's pre-shared key, ADR 0008), dialed through the same
/// `TailcatRoute` machinery a Tailcat pairing uses.
enum MemberRoute {
    Direct { base_url: String },
    Tailcat { address: String },
}

fn classify_member_address(address: &str) -> MemberRoute {
    match reqwest::Url::parse(address) {
        Ok(url)
            if matches!(url.scheme(), "http" | "https")
                && url.host_str().is_some()
                && url.query().is_none()
                && url.fragment().is_none()
                && url.username().is_empty()
                && url.password().is_none() =>
        {
            // Normalize like a pairing link's base: no trailing slash, no
            // path debris a hand-edited invite might carry.
            let base = url.as_str().trim_end_matches('/').to_owned();
            MemberRoute::Direct {
                base_url: if base.is_empty() {
                    address.to_owned()
                } else {
                    base
                },
            }
        }
        _ => MemberRoute::Tailcat {
            address: address.to_owned(),
        },
    }
}

/// The `POST {base}/room/present {secret, label}` reply: the pairing
/// grant shape (a room presentation mints an ordinary session).
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Grant {
    session: PairedSession,
    credential: String,
}

impl std::fmt::Debug for Grant {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print the credential.
        f.debug_struct("Grant")
            .field("session", &self.session)
            .field("credential", &"[redacted]")
            .finish()
    }
}

/// Present the room secret to one member. The secret crosses exactly this
/// request — never a log, never an error (refusals describe only shape).
/// Status mapping: 401 → [`MemberFailure::Refused`], 404 →
/// [`MemberFailure::Unsupported`] (an engine without `rooms-v1` has no
/// route; the member is skipped silently, never an error toast), 429 →
/// [`MemberFailure::Throttled`], connect errors →
/// [`MemberFailure::Unreachable`].
async fn present(base: &str, secret: &str, label: &str) -> Result<Grant, MemberFailure> {
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(PRESENT_TIMEOUT)
        .build()
        .map_err(|error| MemberFailure::Failed(error.to_string()))?;
    let response = http
        .post(format!("{base}/room/present"))
        .json(&serde_json::json!({ "secret": secret, "label": label }))
        .send()
        .await
        .map_err(|_| MemberFailure::Unreachable)?;
    let status = response.status();
    if status.as_u16() == 401 {
        return Err(MemberFailure::Refused);
    }
    if status.as_u16() == 404 {
        return Err(MemberFailure::Unsupported);
    }
    if status.as_u16() == 429 {
        return Err(MemberFailure::Throttled);
    }
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        return Err(MemberFailure::Failed(format!("{status} {body}")));
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|_| MemberFailure::Failed("invalid presentation response".into()))?;
    serde_json::from_slice(&bytes)
        .map_err(|_| MemberFailure::Failed("invalid presentation response".into()))
}

/// Union two books: exact (label, address) pairs dedupe, the order stays
/// stable (sorted, like the engine's merge).
fn union_books(base: &mut Vec<RoomMember>, extra: Vec<RoomMember>) {
    base.extend(extra);
    base.sort_by(|a, b| (&a.label, &a.address).cmp(&(&b.label, &b.address)));
    base.dedup_by(|a, b| a.label == b.label && a.address == b.address);
    base.truncate(roboco_proto::room::MAX_MEMBERS);
}

/// What the phone already holds for one book member, decided **before**
/// any presentation (fleet follow, ticket 04: "no engine appears twice").
#[derive(Debug, Clone, PartialEq, Eq)]
enum MemberStanding {
    /// No live entry dials this address: present (mint a session).
    Absent,
    /// A live entry dials this address and already carries a room tag
    /// (this room, or another one): skip — never re-present, never mint a
    /// second session, never move the tag between rooms.
    Joined,
    /// A live entry dials this address but carries no room tag (a plain
    /// pairing — typically the engine that just introduced the fleet):
    /// adopt it into this room by tagging the existing entry. No
    /// presentation, no new session; the record is reported for
    /// persistence (an upsert by engine id).
    Untagged { engine_id: String },
}

/// Whether an engine entry already dials this member (Tailcat address or
/// Direct base URL) with a live credential — **whatever room it was
/// paired or joined through**. Presenting to a member the phone already
/// holds a session for would mint (or re-key) a second session for
/// nothing, so it is skipped ([`MemberStanding::Joined`]) or, when the
/// entry has no room yet, adopted by tag ([`MemberStanding::Untagged`]).
///
/// One exception re-presents: an entry `Parked { Revoked }` (the engine
/// revoked the session, or the platform lost the credential) — the room
/// is the recovery path, a fresh presentation re-mints it (self-heal).
///
/// `Parked { IdentityChanged }` and `Parked { Incompatible }` stay
/// skipped — an identity change is a security signal the user must see,
/// and a protocol mismatch is not fixable by re-presenting.
///
/// The identity half of the dedupe (the same engine behind a different
/// address than the one it was paired through) cannot be decided before
/// a presentation — engines answer nothing unauthenticated — so it lives
/// in [`crate::adopt_engine`]: a presentation whose engine pins the same
/// `device_id` as an existing entry folds into that entry (one row per
/// engine, the engine id kept).
fn member_standing(inner: &Arc<ClientInner>, address: &MemberRoute) -> MemberStanding {
    let state = lock(&inner.engines);
    let mut standing = MemberStanding::Absent;
    for entry in &state.entries {
        let dials_it = match address {
            MemberRoute::Direct { base_url } => {
                matches!(entry.paired.transport, EngineTransport::Direct)
                    && entry.paired.base_url == *base_url
            }
            MemberRoute::Tailcat { address } => match &entry.paired.transport {
                EngineTransport::Tailcat {
                    address: entry_address,
                    ..
                } => entry_address == address,
                EngineTransport::Direct => false,
            },
        };
        if !dials_it {
            continue;
        }
        // A revoked entry is re-presented: the room is its recovery path.
        if entry.paired.credential.is_empty()
            || matches!(
                entry.state,
                crate::EngineState::Parked {
                    reason: crate::ParkReason::Revoked,
                }
            )
        {
            continue;
        }
        if entry.paired.room.is_some() {
            return MemberStanding::Joined;
        }
        standing = MemberStanding::Untagged {
            engine_id: entry.paired.engine_id.clone(),
        };
    }
    standing
}

/// Tag an existing untagged entry with `room` (the [`MemberStanding::Untagged`]
/// adoption): no presentation, no new session. Returns the updated record
/// for persistence, or `None` when the entry vanished or got a tag in the
/// meantime (a concurrent join or re-pair won — nothing to persist).
fn adopt_untagged(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    room: &EngineRoom,
) -> Option<crate::PairedEngine> {
    let mut state = lock(&inner.engines);
    let entry = state
        .entries
        .iter_mut()
        .find(|entry| entry.paired.engine_id == engine_id)?;
    if entry.paired.room.is_some() {
        return None;
    }
    entry.paired.room = Some(room.clone());
    Some(entry.paired.clone())
}

/// One record per engine id, the last write winning (two book addresses
/// of the same engine fold into one entry through the identity dedupe —
/// the platform must still see one record).
fn dedupe_records(records: &mut Vec<crate::PairedEngine>) {
    let mut seen = HashSet::new();
    let mut deduped: Vec<crate::PairedEngine> = Vec::with_capacity(records.len());
    for record in records.drain(..).rev() {
        if seen.insert(record.engine_id.clone()) {
            deduped.push(record);
        }
    }
    deduped.reverse();
    *records = deduped;
}

/// At most one presentation per (room, address) at a time (see
/// `join_member`'s comment). `HashSet<(room id, address)>` of the joins
/// in flight; entries release on drop.
#[derive(Default)]
pub(crate) struct JoinGuard(Mutex<HashSet<(String, String)>>);

impl JoinGuard {
    fn claim(&self, room_id: &str, address: &str) -> Option<Claim<'_>> {
        let key = (room_id.to_owned(), address.to_owned());
        let mut set = lock(&self.0);
        if !set.insert(key.clone()) {
            return None; // Already in flight.
        }
        Some(Claim { guard: self, key })
    }
}

/// Releases the claim on drop (the join ended, however it ended).
struct Claim<'a> {
    guard: &'a JoinGuard,
    key: (String, String),
}

impl Drop for Claim<'_> {
    fn drop(&mut self) {
        lock(&self.guard.0).remove(&self.key);
    }
}

/// One member join: classify the address, dial (opening the Tailcat
/// tunnel through the shared opener when needed), present, then hand the
/// grant to the engine-adoption path a pairing uses. `Redeemed`-shaped so
/// the whole post-grant machinery (first dial, identity pin, dedupe,
/// supervision, cache) is the pairing's, not a copy.
async fn join_member(
    inner: &Arc<ClientInner>,
    member: &RoomMember,
    secret: &str,
    label: &str,
    room: &EngineRoom,
) -> Result<Option<crate::PairedEngine>, MemberFailure> {
    // One presentation per (room, address) at a time: the start-heal's
    // task can otherwise race a user join that just registered its room
    // (the engine's per-label mint is read-then-insert, so two presents
    // would mint two session rows).
    let _claim = inner
        .room_joins
        .claim(&room.room_id, &member.address)
        .ok_or(MemberFailure::InFlight)?;
    let route = match classify_member_address(&member.address) {
        MemberRoute::Direct { base_url } => {
            let route = crate::engines::route::Route::for_record(
                &base_url,
                &EngineTransport::Direct,
                &inner.tailcat,
            );
            let present_base = base_url.clone();
            (route, base_url, EngineTransport::Direct, present_base)
        }
        MemberRoute::Tailcat { address } => {
            // An empty identity: the library generates this phone's key,
            // which the record then keeps for every reconnect — the same
            // first-join rule a Tailcat pairing follows.
            let transport = EngineTransport::Tailcat {
                address: address.clone(),
                client_state: String::new(),
            };
            let route = crate::engines::route::Route::for_record(
                &crate::tailcat::display_handle(&address),
                &transport,
                &inner.tailcat,
            );
            // Presenting rides the loopback tunnel the route opens (the
            // pairing redeem's rule); a Direct route never reaches here.
            let present_base = route
                .http_base()
                .await
                .map_err(MemberFailure::from_tailcat)?;
            let handle = crate::tailcat::display_handle(&address);
            (route, handle, transport, present_base)
        }
    };
    let (route, base_url, mut transport, present_base) = route;
    let grant = present(&present_base, secret, label).await?;
    if let EngineTransport::Tailcat { client_state, .. } = &mut transport {
        // The identity the open generated, if it did (the present opened
        // the tunnel; the first dial reuses it).
        *client_state = route.client_state().unwrap_or_default();
    }
    let redeemed = Redeemed {
        session: grant.session,
        credential: grant.credential,
        base_url,
        transport,
        route,
    };
    Ok(Some(
        crate::adopt_engine(inner, redeemed, label, Some(room.clone()), None).await,
    ))
}

/// One book member, deduped first ([`member_standing`]): skip a member
/// the phone already holds a live session for, adopt an untagged one by
/// tag, present only to the rest. `Ok(Some)` is a record to persist
/// (minted or adopted), `Ok(None)` a skip.
async fn join_or_skip(
    inner: &Arc<ClientInner>,
    member: &RoomMember,
    secret: &str,
    label: &str,
    room: &EngineRoom,
) -> Result<Option<crate::PairedEngine>, MemberFailure> {
    match member_standing(inner, &classify_member_address(&member.address)) {
        MemberStanding::Joined => Ok(None),
        MemberStanding::Untagged { engine_id } => Ok(adopt_untagged(inner, &engine_id, room)),
        MemberStanding::Absent => join_member(inner, member, secret, label, room).await,
    }
}

/// Join a room from its invite (the user action: scan or paste). Joins
/// every book member that can be dialed — in parallel, like the
/// supervisors' start — and returns what the platform must persist: the
/// room record (secret + book, its `room:` blob) and one
/// [`crate::PairedEngine`] per member minted this call. A member already
/// paired for this room is skipped (never re-presented: presentations
/// are repeatable, but re-issuing a live credential buys nothing).
///
/// Errors only when the invite does not parse, when it carries no
/// members, or when **no** member joined and none was already paired —
/// an aggregate of the per-member failures (any refusal wins: a rotated
/// secret never heals). A partial join is a success: the missing members
/// heal on the next trigger.
pub(crate) async fn join_room(
    inner: &Arc<ClientInner>,
    invite: &str,
) -> Result<RoomJoin, RoomError> {
    let invite = RoomInvite::decode(invite)?;
    join_decoded(inner, invite).await
}

pub(crate) async fn join_decoded(
    inner: &Arc<ClientInner>,
    invite: RoomInvite,
) -> Result<RoomJoin, RoomError> {
    let room_id = invite.room_id();
    let label = sanitize_label(&inner.config.device_name);
    let engine_room = EngineRoom {
        room_id: room_id.clone(),
        label: invite.name.clone(),
    };
    // Register/refresh the room in memory first: every member join and
    // every later heal reads it from the table. The book starts as the
    // invite's (unioned with what a previous join of this room learned).
    let members = {
        let mut rooms = lock(&inner.rooms);
        match rooms.iter_mut().find(|record| record.room_id == room_id) {
            Some(existing) => {
                existing.label = invite.name.clone();
                existing.secret = invite.secret.clone();
                let extra = invite.members.clone();
                union_books(&mut existing.members, extra);
                existing.members.clone()
            }
            None => {
                rooms.push(RoomRecord {
                    room_id: room_id.clone(),
                    label: invite.name.clone(),
                    secret: invite.secret.clone(),
                    members: invite.members.clone(),
                });
                invite.members.clone()
            }
        }
    };
    if members.is_empty() {
        // Roll the in-memory registration back: a room with no members
        // can never heal anything, so it must not persist either.
        let mut rooms = lock(&inner.rooms);
        rooms.retain(|record| !(record.room_id == room_id && record.members.is_empty()));
        return Err(RoomError::Failed {
            message: "the invite carries no members".into(),
        });
    }
    // Join every member that is not already paired for this room, in
    // parallel (a room of N members costs N bounded dials, like N pairings).
    let joins: Vec<_> = members
        .iter()
        .map(|member| {
            let member = member.clone();
            let secret = invite.secret.clone();
            let engine_room = engine_room.clone();
            let room_id = room_id.clone();
            let label = label.clone();
            async move {
                tracing::debug!(room = %room_id, member = %member.label, "joining a room member");
                join_or_skip(inner, &member, &secret, &label, &engine_room).await
            }
        })
        .collect();
    let results = futures::future::join_all(joins).await;
    let mut engines = Vec::new();
    let mut missing = Vec::new();
    let mut failures: Vec<MemberFailure> = Vec::new();
    for (member, result) in members.iter().zip(results) {
        match result {
            Ok(Some(record)) => engines.push(record),
            Ok(None) => {}
            Err(failure) => {
                tracing::debug!(
                    room = %room_id, member = %member.label,
                    failure = ?failure, "room member did not join"
                );
                missing.push(member.label.clone());
                failures.push(failure);
            }
        }
    }
    // Success when at least one member joined **or** was already paired
    // (skipped): the phone holds sessions for this room either way.
    let skipped = members.len() - engines.len() - missing.len();
    // Two book addresses of one engine fold into one entry (the identity
    // dedupe in `adopt_engine`): report it once.
    dedupe_records(&mut engines);
    if !engines.is_empty() || skipped > 0 {
        // Adopted-by-tag entries changed their views (the room row).
        crate::publish(inner);
        return Ok(RoomJoin {
            room: RoomRecord {
                room_id,
                label: invite.name,
                secret: invite.secret,
                members,
            },
            engines,
            missing,
        });
    }
    // Nobody joined and nobody was already paired: aggregate the failures.
    // A refusal wins (a rotated secret never heals); throttling next; the
    // rest is "unreachable", which the next trigger would retry anyway —
    // but the user should see the failure now. Shape-only: counts and
    // kinds, never labels or addresses.
    //
    // Roll the in-memory registration back too: the platform persists
    // nothing for a failed join, so the client's memory must match (the
    // heal would otherwise retry a dead secret on every trigger).
    let error = match failures.iter().find(|f| matches!(f, MemberFailure::Refused)) {
        Some(_) => RoomError::Refused,
        None => match failures
            .iter()
            .find(|f| matches!(f, MemberFailure::Throttled))
        {
            Some(_) => RoomError::Throttled,
            None => RoomError::Unreachable {
                message: format!(
                    "{} of {} members unreachable",
                    failures.len(),
                    members.len()
                ),
            },
        },
    };
    {
        let mut rooms = lock(&inner.rooms);
        rooms.retain(|record| record.room_id != room_id);
    }
    Err(error)
}

// ── fleet follow ────────────────────────────────────────────────────────────
//
// "Pair my phone to any engine and the whole fleet comes along." After a
// successful pairing the platform asks the freshly paired engine for its
// fleet (`GetFleetInvite`, session-authed, gated on `rooms-v2`) and, with
// one in hand, runs the ordinary silent room join above. The pairing link
// itself is untouched (no composite QR): the fleet arrives over the
// session the pairing just minted.
//
// Every failure here is silent (`None`, a shape-only debug line): an old
// engine, an engine without a fleet, a dropped link or a junk answer all
// make the pairing look exactly like it did before fleets existed.

/// How long the fetch waits for the freshly paired engine's link to come
/// up (the pairing's own first dial usually already did; a Tailcat route
/// can take a few seconds more).
const FLEET_CONNECT_WAIT: Duration = Duration::from_secs(20);

/// A fleet invite fetched from a paired engine. Opaque on purpose: the
/// secret and the member addresses stay inside (they cross only into the
/// join and, from there, into the platform's `room:` blob); the UI gets
/// the fleet's name and member labels.
#[derive(Clone)]
pub struct FleetOffer {
    invite: RoomInvite,
}

impl std::fmt::Debug for FleetOffer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the invite carries the secret and addresses.
        f.debug_struct("FleetOffer")
            .field("room_id", &self.invite.room_id())
            .field("name", &self.invite.name)
            .field("members", &self.invite.members.len())
            .finish()
    }
}

impl FleetOffer {
    /// The fleet's name (the room label, "Fleet" by default).
    pub fn name(&self) -> &str {
        &self.invite.name
    }

    /// The fleet room's derived id (not a secret).
    pub fn room_id(&self) -> String {
        self.invite.room_id()
    }

    /// The book's member labels (device names, never the addresses).
    pub fn member_labels(&self) -> Vec<String> {
        self.invite.members.iter().map(|m| m.label.clone()).collect()
    }
}

/// The engine's info once its link is up, or `None` when it never comes
/// up within `wait`, parks, or is forgotten meanwhile.
async fn connected_info(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    wait: Duration,
) -> Option<roboco_proto::EngineInfo> {
    let mut fleet = inner.fleet.subscribe();
    tokio::time::timeout(wait, async {
        loop {
            {
                let snapshot = fleet.borrow_and_update();
                let engine = snapshot.engine(engine_id)?;
                match (&engine.state, &engine.info) {
                    (crate::EngineState::Connected, Some(info)) => return Some(info.clone()),
                    (crate::EngineState::Parked { .. }, _) => return None,
                    _ => {}
                }
            }
            if fleet.changed().await.is_err() {
                return None;
            }
        }
    })
    .await
    .ok()
    .flatten()
}

/// Ask a paired engine for its fleet. `None` — silently — when the engine
/// does not advertise `rooms-v2`, has no fleet (`{"invite": null}`), does
/// not come up, or answers anything unexpected (including a plain-room
/// invite: only fleets follow a pairing).
pub(crate) async fn fetch_fleet(inner: &Arc<ClientInner>, engine_id: &str) -> Option<FleetOffer> {
    let Some(info) = connected_info(inner, engine_id, FLEET_CONNECT_WAIT).await else {
        tracing::debug!(engine = %engine_id, "fleet fetch skipped: the engine is not connected");
        return None;
    };
    if !info.supports(roboco_proto::capabilities::ROOMS_V2) {
        // An older engine: no fleets, the pairing stays exactly as it was.
        return None;
    }
    let handle = crate::engine_handle_of(inner, engine_id).ok()?;
    let value = match handle
        .call(roboco_rpc::methods::GET_FLEET_INVITE, serde_json::json!({}))
        .await
    {
        Ok(value) => value,
        Err(error) => {
            // The engine's error text is fixed and shape-only.
            tracing::debug!(engine = %engine_id, %error, "fleet fetch skipped");
            return None;
        }
    };
    // `{"invite": null}` (no fleet) and any other shape both end here.
    let encoded = value.get("invite").and_then(serde_json::Value::as_str)?;
    let invite = match RoomInvite::decode(encoded) {
        Ok(invite) => invite,
        Err(_) => {
            // Never the invite itself, not even its length.
            tracing::debug!(engine = %engine_id, "fleet fetch skipped: the invite did not decode");
            return None;
        }
    };
    if !invite.is_fleet() {
        return None;
    }
    Some(FleetOffer { invite })
}

/// Join a fetched fleet: the ordinary room join, deduped against what the
/// phone already holds ([`member_standing`] + the identity fold in
/// `adopt_engine`) — the introducing engine is adopted by tag, members
/// already paired are skipped, the rest get one session each.
pub(crate) async fn join_fleet(
    inner: &Arc<ClientInner>,
    offer: FleetOffer,
) -> Result<RoomJoin, RoomError> {
    join_decoded(inner, offer.invite).await
}

/// The heal gate: at most one heal pass in flight; a trigger that lands
/// while one runs sets the rerun flag and the pass runs once more when it
/// finishes (coalescing — a network flap can never queue an unbounded
/// pile of passes).
#[derive(Default)]
pub(crate) struct HealGate {
    pub(crate) state: Mutex<HealState>,
}

#[derive(Default)]
pub(crate) struct HealState {
    running: bool,
    rerun: bool,
}

impl HealGate {
    /// Spawn a heal pass if none is running; else mark a rerun. The
    /// rooms are snapshotted **here**, synchronously at the trigger
    /// moment — never inside the spawned task: the start trigger's
    /// snapshot is exactly the constructor's rooms, so a start-heal
    /// scheduled late can never pick up a room a user join registered
    /// afterwards and race its member dials (the [JoinGuard] would turn
    /// that race into a silent partial join).
    pub(crate) fn trigger(&self, inner: &Arc<ClientInner>) {
        {
            let mut state = lock(&self.state);
            if state.running {
                state.rerun = true;
                return;
            }
            state.running = true;
        }
        let mut rooms = lock(&inner.rooms).clone();
        let inner = inner.clone();
        crate::runtime::shared().spawn(async move {
            loop {
                heal_rooms(&inner, &rooms).await;
                let mut state = lock(&inner.room_heal.state);
                if state.rerun {
                    state.rerun = false;
                    drop(state);
                    // A rerun is a fresh trigger: a fresh snapshot.
                    rooms = lock(&inner.rooms).clone();
                    continue;
                }
                state.running = false;
                break;
            }
        });
    }
}

/// One heal pass over the given rooms (no timers of its own — see the
/// module docs for the trigger points; the snapshot comes from the
/// trigger, never re-read here):
///
/// 1. Dial every book member without an engine entry for its room and
///    present (the same per-member join the user action runs). Failures
///    are logged shape-only and retried on the next trigger.
/// 2. Union the books of the room's **connected** member engines
///    (`GetRoomBook {roomId, secret}` — the secret is presented per call
///    because member addresses are transport secrets). Old engines
///    (`unknown method`) and refusals are skipped silently.
/// 3. Dial members the union discovered (the dial-verify: an address is
///    verified by presenting to it), and fold them into the in-memory
///    book so the next heal dials them directly.
/// 4. Push the merged book back to every connected member
///    (`UpdateRoomBook {roomId, members}` — a union on the engine, so
///    the push is idempotent).
///
/// Records minted here are emitted as
/// [`crate::ClientEvent::RoomEngineJoined`] so the platform persists them
/// exactly like a pairing result (the heal mints credentials the phone
/// must not lose — otherwise every restart would re-present every
/// missing member).
pub(crate) async fn heal_rooms(inner: &Arc<ClientInner>, rooms: &[RoomRecord]) {
    for room in rooms {
        let mut joined: Vec<crate::PairedEngine> = Vec::new();
        let mut book = room.members.clone();
        // Step 1: join every known missing member.
        join_missing(inner, room, &book, &mut joined).await;
        // Step 2: union the books of connected room members.
        merge_connected_books(inner, room, &mut book).await;
        // Step 3: members the union discovered (absent from the stored
        // book) — dial-verify them like any missing member.
        let discovered: Vec<RoomMember> = book
            .iter()
            .filter(|member| {
                !room
                    .members
                    .iter()
                    .any(|known| known.label == member.label && known.address == member.address)
            })
            .cloned()
            .collect();
        if !discovered.is_empty() {
            join_missing(inner, room, &discovered, &mut joined).await;
            // The in-memory book grows so the next heal dials them
            // directly; the platform's stored copy stays the invite's —
            // re-learning from any reachable member costs one RPC.
            let extra = book.clone();
            let mut rooms = lock(&inner.rooms);
            if let Some(existing) = rooms
                .iter_mut()
                .find(|record| record.room_id == room.room_id)
            {
                union_books(&mut existing.members, extra);
            }
        }
        // Step 4: push the merged book back to every connected member.
        push_book(inner, room, &book).await;
        // Persist what the pass minted or adopted: one event per engine,
        // never coalesced away (the pins' rule — each carries a
        // credential).
        dedupe_records(&mut joined);
        for record in joined {
            inner
                .pump
                .pinned(crate::ClientEvent::RoomEngineJoined {
                    room_id: room.room_id.clone(),
                    engine: record,
                });
        }
    }
    crate::publish(inner);
}

/// Dial and present every member of `members` that has no engine entry
/// for the room (the skip rule: never re-present to a member we already
/// hold a session for). Joined records land in `joined`.
async fn join_missing(
    inner: &Arc<ClientInner>,
    room: &RoomRecord,
    members: &[RoomMember],
    joined: &mut Vec<crate::PairedEngine>,
) {
    let label = sanitize_label(&inner.config.device_name);
    let engine_room = EngineRoom {
        room_id: room.room_id.clone(),
        label: room.label.clone(),
    };
    let joins: Vec<_> = members
        .iter()
        .map(|member| {
            let member = member.clone();
            let secret = room.secret.clone();
            let engine_room = engine_room.clone();
            let label = label.clone();
            async move { join_or_skip(inner, &member, &secret, &label, &engine_room).await }
        })
        .collect();
    for result in futures::future::join_all(joins).await {
        match result {
            Ok(Some(record)) => joined.push(record),
            Ok(None) => {}
            Err(failure) => {
                // Shape only: an offline member is the normal case, not an
                // error; never the label, never the address.
                tracing::debug!(room = %room.room_id, ?failure, "room heal: a member did not join");
            }
        }
    }
}

/// Union the books of every **connected** engine joined through this
/// room. `GetRoomBook` additionally presents the secret per call (member
/// addresses are transport secrets). Unreachable/unsupported/refused
/// members are skipped silently — an old engine hides the feature, never
/// errors (ADR 0011).
async fn merge_connected_books(
    inner: &Arc<ClientInner>,
    room: &RoomRecord,
    book: &mut Vec<RoomMember>,
) {
    for handle in connected_room_engines(inner, &room.room_id) {
        let params = serde_json::json!({
            "roomId": room.room_id,
            "secret": room.secret,
        });
        match handle
            .call(roboco_rpc::methods::GET_ROOM_BOOK, params)
            .await
        {
            Ok(value) => {
                // Lenient: a newer engine's extra fields never break the
                // union.
                if let Ok(fresh) = serde_json::from_value::<RoomBook>(value) {
                    let count_before = book.len();
                    let extra = fresh.members;
                    union_books(book, extra);
                    if book.len() != count_before {
                        tracing::debug!(
                            room = %room.room_id, members = book.len(),
                            "room book union learned members"
                        );
                    }
                }
            }
            Err(error) => {
                // The error text is the engine's own (shape-only); the
                // secret never appears in it.
                tracing::debug!(room = %room.room_id, %error, "room book fetch skipped");
            }
        }
    }
}

/// Push the merged book to every connected room member
/// (`UpdateRoomBook {roomId, members}` — a union on the engine, so this
/// is idempotent and never deletes). Failures are skipped silently: the
/// next trigger pushes again.
async fn push_book(inner: &Arc<ClientInner>, room: &RoomRecord, book: &[RoomMember]) {
    for handle in connected_room_engines(inner, &room.room_id) {
        let params = serde_json::json!({
            "roomId": room.room_id,
            "members": book,
        });
        if let Err(error) = handle
            .call(roboco_rpc::methods::UPDATE_ROOM_BOOK, params)
            .await
        {
            tracing::debug!(room = %room.room_id, %error, "room book push skipped");
        }
    }
}

/// The connected engines joined through this room (handles to drive
/// `GetRoomBook` / `UpdateRoomBook` on the calls connection).
fn connected_room_engines(inner: &Arc<ClientInner>, room_id: &str) -> Vec<crate::EngineHandle> {
    let ids: Vec<String> = {
        let state = lock(&inner.engines);
        state
            .entries
            .iter()
            .filter(|entry| {
                entry
                    .paired
                    .room
                    .as_ref()
                    .is_some_and(|room| room.room_id == room_id)
                    && matches!(entry.state, crate::EngineState::Connected)
            })
            .map(|entry| entry.paired.engine_id.clone())
            .collect()
    };
    ids.into_iter()
        .filter_map(|id| crate::engine_handle_of(inner, &id).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn member_addresses_classify_by_shape() {
        for (address, direct, base) in [
            ("http://127.0.0.1:8123", true, "http://127.0.0.1:8123"),
            ("https://engine.example.ts.net/sub", true, "https://engine.example.ts.net/sub"),
            ("http://h/", true, "http://h"),
            ("tcSECRETaddress1234", false, ""),
            ("not a url", false, ""),
            ("ftp://h/", false, ""),
            ("http://u:p@h/", false, ""),
            ("http://h/?q=1", false, ""),
            ("http://h/#f", false, ""),
        ] {
            match classify_member_address(address) {
                MemberRoute::Direct { base_url } => {
                    assert!(direct, "{address} classified Direct");
                    assert_eq!(base_url, base, "{address}");
                }
                MemberRoute::Tailcat { .. } => assert!(!direct, "{address} classified Tailcat"),
            }
        }
    }

    #[test]
    fn books_union_like_the_engine() {
        let mut book = vec![member("b", "tcB"), member("a", "tcA")];
        union_books(&mut book, vec![member("a", "tcA"), member("c", "tcC")]);
        assert_eq!(
            book,
            vec![member("a", "tcA"), member("b", "tcB"), member("c", "tcC")]
        );
        // The cap holds (distinct members: exact pairs dedupe first).
        let many: Vec<RoomMember> = (0..roboco_proto::room::MAX_MEMBERS + 5)
            .map(|index| member("m", &format!("tcM{index}")))
            .collect();
        let mut book = Vec::new();
        union_books(&mut book, many);
        assert_eq!(book.len(), roboco_proto::room::MAX_MEMBERS);
    }

    #[test]
    fn the_invite_target_carries_labels_never_secrets() {
        let invite = RoomInvite::new(
            "Home fleet",
            "SECRET-ROOM-SECRET",
            vec![
                member("desktop", "tcSECRETADDRESS"),
                member("server", "tcOTHER"),
            ],
        )
        .unwrap();
        let target = parse_room_invite(&invite.encode().unwrap()).expect("the invite parses");
        assert_eq!(target.name, "Home fleet");
        assert_eq!(target.member_labels, vec!["desktop", "server"]);
        let printed = format!("{target:?}");
        assert!(!printed.contains("SECRET"), "{printed}");
        // Junk is a shape-only refusal.
        for junk in ["", "roboco-tailcat:x", "roboco-room:!!!", "http://h/pair#token=x"] {
            assert!(parse_room_invite(junk).is_err(), "{junk:?} parsed");
        }
    }

    #[test]
    fn room_records_never_print_their_secrets() {
        let record = RoomRecord {
            room_id: "r".into(),
            label: "Home fleet".into(),
            secret: "SECRET-ROOM".into(),
            members: vec![member("desktop", "tcSECRETADDRESS")],
        };
        let printed = format!("{record:?}");
        assert!(!printed.contains("SECRET-ROOM"), "{printed}");
        assert!(!printed.contains("tcSECRETADDRESS"), "{printed}");
        assert!(printed.contains("room secret…(15 chars)"), "{printed}");
    }

    #[test]
    fn fleet_offers_never_print_their_secrets() {
        let offer = FleetOffer {
            invite: RoomInvite::fleet(
                "Fleet",
                "SECRET-FLEET-SECRET-0123456789abcdefghij",
                vec![member("desktop", "tcSECRETADDRESS")],
            )
            .unwrap(),
        };
        let printed = format!("{offer:?} {offer:#?}");
        assert!(!printed.contains("SECRET-FLEET"), "{printed}");
        assert!(!printed.contains("tcSECRETADDRESS"), "{printed}");
        assert_eq!(offer.name(), "Fleet");
        assert_eq!(offer.member_labels(), vec!["desktop"]);
    }

    #[test]
    fn records_dedupe_by_engine_id_keeping_the_last_write() {
        let record = |id: &str, credential: &str| crate::PairedEngine {
            engine_id: id.into(),
            base_url: "http://h".into(),
            credential: credential.into(),
            session_id: "s".into(),
            label: "Pixel".into(),
            paired_at_ms: 0,
            device_id: None,
            name: None,
            transport: EngineTransport::Direct,
            room: None,
        };
        let mut records = vec![record("a", "old"), record("b", "b"), record("a", "new")];
        dedupe_records(&mut records);
        let ids: Vec<_> = records.iter().map(|r| r.engine_id.as_str()).collect();
        assert_eq!(ids, vec!["b", "a"]);
        assert_eq!(records[1].credential, "new");
    }

    fn member(label: &str, address: &str) -> RoomMember {
        RoomMember {
            label: label.into(),
            address: address.into(),
        }
    }
}
