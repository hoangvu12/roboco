//! The room half of the FFI surface (ADR 0011, ticket 04): the records
//! the platform stores (`room:` blobs), the static invite parser for the
//! confirm UI, and `join_room` — the phone's scan-once. Everything here
//! mirrors [`roboco_mobile_client::rooms`]; the secret crosses exactly
//! twice per room, in (`CoreClient::new`) and out (`join_room`), like a
//! session credential.

use roboco_mobile_client as mc;

use super::CoreClient;
use super::types::PairedEngine;

// ── records ────────────────────────────────────────────────────────────────

/// One address-book entry of a room: a member engine's label and the
/// address a client dials it through (a transport secret, ADR 0008 —
/// never logged, never in an error).
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct RoomMemberRecord {
    pub label: String,
    pub address: String,
}

impl std::fmt::Debug for RoomMemberRecord {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print the address.
        f.debug_struct("RoomMemberRecord")
            .field("label", &self.label)
            .field("address", &roboco_proto::room::describe(&self.address))
            .finish()
    }
}

impl From<roboco_proto::room::RoomMember> for RoomMemberRecord {
    fn from(member: roboco_proto::room::RoomMember) -> Self {
        Self {
            label: member.label,
            address: member.address,
        }
    }
}

impl From<RoomMemberRecord> for roboco_proto::room::RoomMember {
    fn from(member: RoomMemberRecord) -> Self {
        Self {
            label: member.label,
            address: member.address,
        }
    }
}

/// A joined room as the platform stores it: the secret plus the phone's
/// copy of the book — the `room:` Keystore blob's plaintext. The secret
/// is a secret: never log this record, never put it in metadata.
#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct RoomRecord {
    pub room_id: String,
    pub label: String,
    pub secret: String,
    pub members: Vec<RoomMemberRecord>,
}

impl std::fmt::Debug for RoomRecord {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Manual on purpose: the derived Debug would print the secret.
        f.debug_struct("RoomRecord")
            .field("room_id", &self.room_id)
            .field("label", &self.label)
            .field("secret", &roboco_proto::room::describe(&self.secret))
            .field("members", &self.members)
            .finish()
    }
}

impl From<mc::rooms::RoomRecord> for RoomRecord {
    fn from(record: mc::rooms::RoomRecord) -> Self {
        Self {
            room_id: record.room_id,
            label: record.label,
            secret: record.secret,
            members: record.members.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<RoomRecord> for mc::rooms::RoomRecord {
    fn from(record: RoomRecord) -> Self {
        Self {
            room_id: record.room_id,
            label: record.label,
            secret: record.secret,
            members: record.members.into_iter().map(Into::into).collect(),
        }
    }
}

/// The minimum a `roboco-room:` invite carries, for the confirm UI: the
/// room name and the member labels (device names). The secret and the
/// member addresses never cross this record.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RoomInviteTarget {
    pub name: String,
    /// The book's member labels, in invite order.
    pub member_labels: Vec<String>,
}

impl From<mc::rooms::RoomInviteTarget> for RoomInviteTarget {
    fn from(target: mc::rooms::RoomInviteTarget) -> Self {
        Self {
            name: target.name,
            member_labels: target.member_labels,
        }
    }
}

/// What one join produced. Partial joins are the normal outcome (a
/// member asleep during the join heals later); `engines` are the records
/// to persist, `missing` is informational.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RoomJoinResult {
    /// The room to persist as its `room:` blob.
    pub room: RoomRecord,
    /// One [`PairedEngine`] per member minted this call.
    pub engines: Vec<PairedEngine>,
    /// Labels of book members that did not join (offline or old): they
    /// heal on app start and network change.
    pub missing: Vec<String>,
}

// ── errors ─────────────────────────────────────────────────────────────────

/// Why joining a room failed. Messages are shape-only: none ever quotes
/// the invite, the secret or an address.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum RoomError {
    #[error("invalid room invite: {message}")]
    InvalidInvite { message: String },
    /// A member answered 401: the secret does not match any joined room
    /// (it rotated, or the room was left).
    #[error("the room invite was refused")]
    Refused,
    /// A member answered 429: too many failed presentations.
    #[error("that engine throttled room presentations; try again later")]
    Throttled,
    /// No member could be reached.
    #[error("could not reach any room member: {message}")]
    Unreachable { message: String },
    #[error("joining the room failed: {message}")]
    Failed { message: String },
}

impl From<mc::rooms::RoomError> for RoomError {
    fn from(error: mc::rooms::RoomError) -> Self {
        use mc::rooms::RoomError as E;
        match error {
            E::InvalidInvite { message } => RoomError::InvalidInvite { message },
            E::Refused => RoomError::Refused,
            E::Throttled => RoomError::Throttled,
            E::Unreachable { message } => RoomError::Unreachable { message },
            E::Failed { message } => RoomError::Failed { message },
        }
    }
}

// ── the calls ──────────────────────────────────────────────────────────────

#[uniffi::export]
impl CoreClient {
    /// Join a room from a `roboco-room:` invite (ADR 0011): every book
    /// member gets its own session and engine entry, exactly like a
    /// pairing. Runs on the client's runtime like every async export
    /// (the presents and first dials need a reactor). A partial join is
    /// a success — missing members heal on app start and network change;
    /// only "nobody joined and nobody was already paired" errors.
    pub async fn join_room(&self, invite: String) -> Result<RoomJoinResult, RoomError> {
        // See `pair_engine` for why this hops to the client's runtime:
        // UniFFI polls the future on the foreign executor, where the
        // presents' HTTP requests and the tokio timers have no reactor.
        let client = self.client.clone();
        match mc::runtime::shared()
            .spawn(async move { client.join_room(&invite).await })
            .await
        {
            Ok(result) => result
                .map(|join| RoomJoinResult {
                    room: join.room.into(),
                    engines: join.engines.into_iter().map(Into::into).collect(),
                    missing: join.missing,
                })
                .map_err(RoomError::from),
            Err(err) => Err(RoomError::Failed {
                message: format!("the join task failed: {err}"),
            }),
        }
    }

    /// Fleet follow, step 1 (call right after a successful pairing has
    /// been persisted): ask the freshly paired engine for its fleet.
    /// `None` — never an error — when the engine lacks `rooms-v2`, has no
    /// fleet, does not connect, or answers junk: the pairing then looks
    /// exactly like it always did. The offer is opaque (the secret and the
    /// member addresses stay in Rust); show a progress toast, then
    /// [`CoreClient::join_fleet`].
    pub async fn fetch_fleet(&self, engine_id: String) -> Option<std::sync::Arc<FleetOffer>> {
        let client = self.client.clone();
        mc::runtime::shared()
            .spawn(async move { client.fetch_fleet(&engine_id).await })
            .await
            .ok()
            .flatten()
            .map(|offer| std::sync::Arc::new(FleetOffer(offer)))
    }

    /// Fleet follow, step 2: the silent room join of the offered fleet,
    /// deduped against every engine already paired on this device (the
    /// introducing engine is adopted into the fleet by tag, already-paired
    /// members are skipped, the rest get one session each). Persist the
    /// result exactly like [`CoreClient::join_room`]'s: the `room:` blob,
    /// then each record as an upsert by engine id. Idempotent: re-running
    /// it for the same fleet mints nothing.
    pub async fn join_fleet(
        &self,
        offer: std::sync::Arc<FleetOffer>,
    ) -> Result<RoomJoinResult, RoomError> {
        let client = self.client.clone();
        let offer = offer.0.clone();
        match mc::runtime::shared()
            .spawn(async move { client.join_fleet(offer).await })
            .await
        {
            Ok(result) => result
                .map(|join| RoomJoinResult {
                    room: join.room.into(),
                    engines: join.engines.into_iter().map(Into::into).collect(),
                    missing: join.missing,
                })
                .map_err(RoomError::from),
            Err(err) => Err(RoomError::Failed {
                message: format!("the join task failed: {err}"),
            }),
        }
    }
}

/// A fleet invite fetched from a paired engine (fleet follow). Opaque: the
/// platform reads the name and the member labels for its progress toast,
/// and hands it back to [`CoreClient::join_fleet`]; the secret and the
/// member addresses never cross.
#[derive(Debug, uniffi::Object)]
pub struct FleetOffer(mc::FleetOffer);

#[uniffi::export]
impl FleetOffer {
    /// The fleet's name ("Fleet" by default).
    pub fn name(&self) -> String {
        self.0.name().to_owned()
    }

    /// The book's member labels (device names, never addresses).
    pub fn member_labels(&self) -> Vec<String> {
        self.0.member_labels()
    }
}

/// Parse a `roboco-room:` invite for the confirm UI ("Join room 'Home
/// fleet' · 2 engines"). Neither the secret nor a member address crosses.
#[uniffi::export]
pub fn parse_room_invite(input: String) -> Result<RoomInviteTarget, RoomError> {
    mc::parse_room_invite(&input)
        .map(RoomInviteTarget::from)
        .map_err(RoomError::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";
    const ADDRESS: &str = "tcSECRETADDRESS1234";

    fn invite() -> String {
        roboco_proto::room::RoomInvite::new(
            "Home fleet",
            SECRET,
            vec![
                roboco_proto::room::RoomMember {
                    label: "desktop".into(),
                    address: ADDRESS.into(),
                },
                roboco_proto::room::RoomMember {
                    label: "server".into(),
                    address: "http://10.0.2.2:8123".into(),
                },
            ],
        )
        .unwrap()
        .encode()
        .unwrap()
    }

    #[test]
    fn the_invite_parser_maps_across_and_never_echoes_secrets() {
        let target = parse_room_invite(invite()).unwrap();
        assert_eq!(target.name, "Home fleet");
        assert_eq!(target.member_labels, vec!["desktop", "server"]);
        let printed = format!("{target:?} {target:?}");
        assert!(!printed.contains(SECRET), "{printed}");
        assert!(!printed.contains(ADDRESS), "{printed}");
        // A junk invite is a shape-only refusal.
        match parse_room_invite("roboco-room:!!!".into()).unwrap_err() {
            RoomError::InvalidInvite { message } => {
                assert!(!message.contains("roboco-room"), "{message}")
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn room_records_and_members_redact_their_secrets() {
        let record = RoomRecord {
            room_id: "r".into(),
            label: "Home fleet".into(),
            secret: SECRET.into(),
            members: vec![RoomMemberRecord {
                label: "desktop".into(),
                address: ADDRESS.into(),
            }],
        };
        let printed = format!("{record:?} {record:?}");
        assert!(!printed.contains(SECRET), "{printed}");
        assert!(!printed.contains(ADDRESS), "{printed}");
        // The FFI record round-trips into the client's shape.
        let core: mc::rooms::RoomRecord = record.into();
        assert_eq!(core.room_id, "r");
        assert_eq!(core.members[0].label, "desktop");
        assert_eq!(core.members[0].address, ADDRESS);
    }

    #[test]
    fn room_errors_map_across() {
        use mc::rooms::RoomError as E;
        assert_eq!(
            RoomError::from(E::Refused),
            RoomError::Refused
        );
        assert_eq!(
            RoomError::from(E::InvalidInvite {
                message: "shape".into()
            }),
            RoomError::InvalidInvite {
                message: "shape".into()
            }
        );
    }
}
