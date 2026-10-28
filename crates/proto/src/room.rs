//! The `roboco-room:` invite grammar plus the room wire types shared by the
//! engine, the CLI, and the clients that join rooms (ADR 0011), so there is
//! exactly one decoder.
//!
//! An invite is `roboco-room:` + base64url-no-pad(JSON
//! `{"v":1,"name":"…","secret":"…","members":[{"label":"…","address":"tc…"}]}`).
//! The secret is the room's shared secret and member addresses are transport
//! secrets (ADR 0008): errors here describe only shape, the [`Debug`] impls
//! redact both, and [`describe`] is the only form of a secret fit for a log.
//! The room id is derived, not minted — every engine and client holding the
//! same secret derives the same id, so books and RPCs address one room with
//! no coordinator (the room is hostless).

use std::fmt;

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Prefix of a room invite carrying the room secret plus its address book.
pub const INVITE_PREFIX: &str = "roboco-room:";

/// The only grammar version (frozen at first ship, ADR 0005).
pub const INVITE_VERSION: u32 = 1;

/// The longest encoded invite accepted.
pub const MAX_INVITE_BYTES: usize = 65_536;

/// The longest room secret accepted (invites carry it verbatim).
pub const MAX_SECRET_BYTES: usize = 4096;

/// The longest member address accepted (matches the tailcat address grammar).
pub const MAX_MEMBER_ADDRESS_BYTES: usize = 4096;

/// The most members one invite or book update may carry.
pub const MAX_MEMBERS: usize = 512;

/// The longest label (room name, member label, presenter label): the pairing
/// label rule, `pairing.rs::validate_label`.
pub const MAX_LABEL_BYTES: usize = 256;

/// Shape-only description of a secret, safe to log.
pub fn describe(secret: &str) -> String {
    format!("room secret…({} chars)", secret.len())
}

/// Mint a fresh room secret: 32 bytes of OS randomness, base64url-encoded —
/// the same entropy budget as a pairing code. One definition, shared by the
/// engine store, the desktop, and the CLI.
pub fn mint_room_secret() -> Result<String, RoomInviteError> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|_| RoomInviteError::Mint)?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

/// Why an invite, secret, or book entry was refused. Every message is fixed
/// text: none ever quotes the input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoomInviteError {
    NotAnInvite,
    NotBase64,
    Malformed,
    Version,
    Name,
    Secret,
    MemberLabel,
    MemberAddress,
    TooManyMembers,
    Encode,
    Mint,
}

impl fmt::Display for RoomInviteError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::NotAnInvite => "a room invite starts with roboco-room:",
            Self::NotBase64 => "room invite payload is not base64url",
            Self::Malformed => "room invite payload is malformed",
            Self::Version => "room invite grammar version is not 1",
            Self::Name => "room name must be at most 256 bytes with no control characters",
            Self::Secret => "room secret must be 1 to 4096 bytes with no control characters",
            Self::MemberLabel => {
                "member labels must be at most 256 bytes with no control characters"
            }
            Self::MemberAddress => {
                "member addresses must be 1 to 4096 bytes with no whitespace or control characters"
            }
            Self::TooManyMembers => "room invites carry at most 512 members",
            Self::Encode => "room invite could not be encoded",
            Self::Mint => "operating system randomness unavailable",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for RoomInviteError {}

fn valid_label(value: &str) -> bool {
    !value.is_empty() && value.len() <= MAX_LABEL_BYTES && !value.chars().any(char::is_control)
}

fn valid_secret(value: &str) -> bool {
    !value.is_empty() && value.len() <= MAX_SECRET_BYTES && !value.chars().any(char::is_control)
}

fn valid_member_address(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_MEMBER_ADDRESS_BYTES
        && !value.chars().any(|c| c.is_control() || c.is_whitespace())
}

/// Validate one address-book entry (also the invite's `members` entries).
/// Errors are fixed text and never quote the input.
pub fn validate_room_member(member: &RoomMember) -> Result<(), RoomInviteError> {
    if !valid_label(&member.label) {
        return Err(RoomInviteError::MemberLabel);
    }
    if !valid_member_address(&member.address) {
        return Err(RoomInviteError::MemberAddress);
    }
    Ok(())
}

/// One address-book entry: a member engine's label and the address a client
/// dials it through (a transport secret — ADR 0008).
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomMember {
    pub label: String,
    pub address: String,
}

impl fmt::Debug for RoomMember {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RoomMember")
            .field("label", &self.label)
            .field("address", &describe(&self.address))
            .finish()
    }
}

/// A room's address book: the `GetRoomBook` reply and the
/// `UpdateRoomBook` fresh snapshot.
#[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomBook {
    pub members: Vec<RoomMember>,
}

impl fmt::Debug for RoomBook {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RoomBook")
            .field("members", &self.members)
            .finish()
    }
}

/// What a room is for. Plain rooms keep the v0.13.0 hostless semantics
/// (verifier-only engines, manual invites); fleet rooms are the
/// pairing-adjacent group whose secret member engines retain (ADR 0011
/// amendment, fleet follow) so any member can introduce a new client.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoomKind {
    #[default]
    Room,
    Fleet,
}

impl RoomKind {
    /// Whether the kind is the default. The invite field serializes only
    /// for fleet rooms, so plain-room invites stay byte-identical to the
    /// v0.13.0 grammar (and old invites decode as plain rooms).
    fn is_plain(&self) -> bool {
        matches!(self, Self::Room)
    }
}

/// One pasteable handle: the room serialized. The secret is what members
/// present, so the invite is a secret — written 0600 where files are
/// involved, never logged (ADR 0008).
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RoomInvite {
    pub v: u32,
    pub name: String,
    pub secret: String,
    pub members: Vec<RoomMember>,
    /// Fleet rooms retain their secret on member engines; plain rooms keep
    /// verifier-only semantics. Absent in the wire form means plain.
    #[serde(default, skip_serializing_if = "RoomKind::is_plain")]
    pub kind: RoomKind,
}

impl fmt::Debug for RoomInvite {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RoomInvite")
            .field("v", &self.v)
            .field("name", &self.name)
            .field("secret", &describe(&self.secret))
            .field("members", &self.members)
            .field("kind", &self.kind)
            .finish()
    }
}

impl RoomInvite {
    /// Validate and build a v1 fleet invite: [`RoomInvite::new`] with
    /// `kind = fleet`, so joining engines retain the secret.
    pub fn fleet(
        name: &str,
        secret: &str,
        members: Vec<RoomMember>,
    ) -> Result<Self, RoomInviteError> {
        Ok(Self {
            kind: RoomKind::Fleet,
            ..Self::new(name, secret, members)?
        })
    }

    pub fn is_fleet(&self) -> bool {
        self.kind == RoomKind::Fleet
    }

    /// Validate and build a v1 invite. The secret is moved in as-is; callers
    /// mint it from OS randomness (32 bytes, base64url, like a pairing code).
    pub fn new(
        name: &str,
        secret: &str,
        members: Vec<RoomMember>,
    ) -> Result<Self, RoomInviteError> {
        if !valid_label(name) {
            return Err(RoomInviteError::Name);
        }
        if !valid_secret(secret) {
            return Err(RoomInviteError::Secret);
        }
        if members.len() > MAX_MEMBERS {
            return Err(RoomInviteError::TooManyMembers);
        }
        for member in &members {
            validate_room_member(member)?;
        }
        Ok(Self {
            v: INVITE_VERSION,
            name: name.to_owned(),
            secret: secret.to_owned(),
            members,
            kind: RoomKind::Room,
        })
    }

    pub fn encode(&self) -> Result<String, RoomInviteError> {
        if self.v != INVITE_VERSION {
            return Err(RoomInviteError::Version);
        }
        if !valid_label(&self.name) {
            return Err(RoomInviteError::Name);
        }
        if !valid_secret(&self.secret) {
            return Err(RoomInviteError::Secret);
        }
        if self.members.len() > MAX_MEMBERS {
            return Err(RoomInviteError::TooManyMembers);
        }
        for member in &self.members {
            validate_room_member(member)?;
        }
        let payload = serde_json::to_vec(self).map_err(|_| RoomInviteError::Encode)?;
        let encoded = format!("{INVITE_PREFIX}{}", URL_SAFE_NO_PAD.encode(payload));
        // Length is shape, not content: an oversized invite fails without
        // any of its bytes reaching an error message or a log.
        if encoded.len() > MAX_INVITE_BYTES {
            return Err(RoomInviteError::Encode);
        }
        Ok(encoded)
    }

    pub fn decode(value: &str) -> Result<Self, RoomInviteError> {
        let payload = value
            .trim()
            .strip_prefix(INVITE_PREFIX)
            .ok_or(RoomInviteError::NotAnInvite)?;
        if payload.len() + INVITE_PREFIX.len() > MAX_INVITE_BYTES {
            // Shape-only: the length is the shape, the payload never logs.
            return Err(RoomInviteError::NotBase64);
        }
        let bytes = URL_SAFE_NO_PAD
            .decode(payload)
            .map_err(|_| RoomInviteError::NotBase64)?;
        // serde_json's message can quote a value; it is dropped for fixed text.
        let invite: Self =
            serde_json::from_slice(&bytes).map_err(|_| RoomInviteError::Malformed)?;
        if invite.v != INVITE_VERSION {
            return Err(RoomInviteError::Version);
        }
        if !valid_label(&invite.name) {
            return Err(RoomInviteError::Name);
        }
        if !valid_secret(&invite.secret) {
            return Err(RoomInviteError::Secret);
        }
        if invite.members.len() > MAX_MEMBERS {
            return Err(RoomInviteError::TooManyMembers);
        }
        for member in &invite.members {
            validate_room_member(member)?;
        }
        Ok(invite)
    }

    /// This room's id: [`room_id`] of the secret.
    pub fn room_id(&self) -> String {
        room_id(&self.secret)
    }
}

/// The deterministic room id: a domain-tagged SHA-256 of the secret,
/// base64url-encoded. Every engine and client that holds the secret derives
/// the same id, so books and RPCs address one room with no coordinator; an
/// id is a public handle and never reveals the secret it hashes.
pub fn room_id(secret: &str) -> String {
    URL_SAFE_NO_PAD.encode(room_id_bytes(secret))
}

/// Byte form of [`room_id`] (the verifier-style domain-tagged digest).
pub fn room_id_bytes(secret: &str) -> [u8; 32] {
    tagged_digest(b"id", secret)
}

/// Domain-tagged digest with the room's domain and kind: the same
/// construction as the pairing verifier (`pairing.rs`), in the room's own
/// domain so room and pairing verifiers can never collide.
pub(crate) fn tagged_digest(kind: &[u8], secret: &str) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(b"roboco-room-v1\0");
    hash.update(kind);
    hash.update(b"\0");
    hash.update(secret.as_bytes());
    hash.finalize().into()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";
    const ADDRESS: &str = "tcAAAABBBBccccDDDD_eeee-ffff0123";

    fn members() -> Vec<RoomMember> {
        vec![
            RoomMember {
                label: "desktop".into(),
                address: ADDRESS.into(),
            },
            RoomMember {
                label: "server".into(),
                address: "tcanotheraddress1234".into(),
            },
        ]
    }

    #[test]
    fn invites_round_trip_and_reject_junk() {
        let invite = RoomInvite::new("Home fleet", SECRET, members()).unwrap();
        let encoded = invite.encode().unwrap();
        assert!(encoded.starts_with(INVITE_PREFIX));
        // The wire fields are exactly the frozen grammar: v, name, secret, members.
        let decoded = RoomInvite::decode(&encoded).unwrap();
        assert_eq!(decoded, invite);
        assert_eq!(decoded.v, 1);
        assert_eq!(decoded.name, "Home fleet");
        assert_eq!(decoded.members.len(), 2);
        assert_eq!(decoded.members[1].label, "server");
        assert_eq!(
            decoded.members[1].address.to_lowercase(),
            decoded.members[1].address
        );
        // Whitespace around a pasted invite is tolerated.
        assert_eq!(
            RoomInvite::decode(format!("  {encoded}\n").as_str()).unwrap(),
            invite
        );
        for junk in [
            "",
            "http://example/pair#token=1",
            "roboco-tailcat:eyJ2IjoxfQ",
            "roboco-room:!!!!",
            "roboco-room:eyJhIjoxfQ",
        ] {
            assert!(RoomInvite::decode(junk).is_err(), "{junk:?} decoded");
        }
    }

    #[test]
    fn the_grammar_carries_exactly_the_frozen_fields() {
        let invite = RoomInvite::new("Home fleet", SECRET, members()).unwrap();
        let payload = invite.encode().unwrap();
        let json = URL_SAFE_NO_PAD
            .decode(payload.strip_prefix(INVITE_PREFIX).unwrap())
            .unwrap();
        let value: serde_json::Value = serde_json::from_slice(&json).unwrap();
        // Sorted before comparing: serde_json's Map is a BTreeMap here, but a
        // workspace build that pulls the ui-linked crates enables serde_json's
        // `preserve_order` feature (via GPUI), flipping the Map to IndexMap and
        // its key order to insertion order. Key order on the wire is
        // irrelevant — the assertion is "exactly the frozen fields, none
        // missing, none extra".
        let mut keys: Vec<String> = value.as_object().unwrap().keys().cloned().collect();
        keys.sort();
        assert_eq!(keys, ["members", "name", "secret", "v"]);
        assert_eq!(value["v"], 1);
        assert_eq!(value["name"], "Home fleet");
        assert_eq!(value["secret"], SECRET);
        assert_eq!(value["members"][0]["label"], "desktop");
        assert_eq!(value["members"][0]["address"], ADDRESS);
    }

    #[test]
    fn shape_rules_reject_bad_invites_without_echoing_them() {
        // Hand-crafted payloads (an encoder never produces these) so decode's
        // own shape rules are what gets exercised.
        let payload = |json: &str| format!("{INVITE_PREFIX}{}", URL_SAFE_NO_PAD.encode(json));
        // Every bad invite decodes to a refusal, and the refusal text is one
        // of the fixed messages — none quotes the input.
        let fixed_texts: Vec<String> = [
            RoomInviteError::NotAnInvite,
            RoomInviteError::NotBase64,
            RoomInviteError::Malformed,
            RoomInviteError::Version,
            RoomInviteError::Name,
            RoomInviteError::Secret,
            RoomInviteError::MemberLabel,
            RoomInviteError::MemberAddress,
            RoomInviteError::TooManyMembers,
            RoomInviteError::Encode,
        ]
        .iter()
        .map(|error| error.to_string())
        .collect();
        let oversized = "x".repeat(MAX_SECRET_BYTES + 1);
        let oversized_name = "n".repeat(MAX_LABEL_BYTES + 1);
        let oversized_members: Vec<RoomMember> = vec![
            RoomMember {
                label: "m".into(),
                address: ADDRESS.into(),
            };
            MAX_MEMBERS + 1
        ];
        let oversized_members_json = serde_json::to_string(&oversized_members).unwrap();
        for bad in [
            // empty, oversized, and control-carrying secrets
            payload(r#"{"v":1,"name":"room","secret":"","members":[]}"#),
            payload(&format!(
                r#"{{\"v\":1,\"name\":\"room\",\"secret\":\"{oversized}\",\"members\":[]}}"#
            )),
            payload(r#"{"v":1,"name":"room","secret":"bad\nsecret","members":[]}"#),
            // bad room name
            payload(&format!(
                r#"{{\"v\":1,\"name\":\"\",\"secret\":\"{SECRET}\",\"members\":[]}}"#
            )),
            payload(&format!(
                r#"{{\"v\":1,\"name\":\"{oversized_name}\",\"secret\":\"{SECRET}\",\"members\":[]}}"#
            )),
            payload(r#"{"v":1,"name":"bad\nname","secret":"SECRETTOKEN","members":[]}"#),
            // bad member entries
            payload(
                r#"{"v":1,"name":"room","secret":"SECRETTOKEN","members":[{"label":"ok","address":"has space"}]}"#,
            ),
            payload(
                r#"{"v":1,"name":"room","secret":"SECRETTOKEN","members":[{"label":"bad\nlabel","address":"tcADDRESS"}]}"#,
            ),
            // too many members
            payload(&format!(
                r#"{{\"v\":1,\"name\":\"room\",\"secret\":\"{SECRET}\",\"members\":{oversized_members_json}}}"#
            )),
        ] {
            let error = RoomInvite::decode(&bad)
                .map(|_| ())
                .unwrap_err()
                .to_string();
            assert!(fixed_texts.contains(&error), "unfixed error text: {error}");
            assert!(!error.contains("bad\n"), "{error}");
            assert!(!error.contains("has space"), "{error}");
            assert!(!error.contains("bad label"), "{error}");
            assert!(!error.contains(SECRET), "{error}");
            assert!(!error.contains(&oversized), "{error}");
        }
        // Unknown fields in an otherwise valid payload are refused.
        let unknown = format!(
            "{INVITE_PREFIX}{}",
            URL_SAFE_NO_PAD.encode(
                br#"{"v":1,"name":"r","secret":"SECRETTOKEN","members":[],"SECRETFIELD":1}"#
            )
        );
        let error = RoomInvite::decode(&unknown).unwrap_err().to_string();
        assert!(!error.contains("SECRETFIELD"), "{error}");
        // Wrong grammar version.
        let versioned = format!(
            "{INVITE_PREFIX}{}",
            URL_SAFE_NO_PAD.encode(br#"{"v":2,"name":"r","secret":"SECRETTOKEN","members":[]}"#)
        );
        assert_eq!(
            RoomInvite::decode(&versioned).unwrap_err(),
            RoomInviteError::Version
        );
    }

    #[test]
    fn the_fleet_kind_round_trips_and_plain_stays_absent() {
        let fleet = RoomInvite::fleet("Fleet", SECRET, members()).unwrap();
        assert!(fleet.is_fleet());
        let encoded = fleet.encode().unwrap();
        let decoded = RoomInvite::decode(&encoded).unwrap();
        assert_eq!(decoded, fleet);
        assert_eq!(decoded.kind, RoomKind::Fleet);
        assert_eq!(decoded.room_id(), fleet.room_id());
        let json = URL_SAFE_NO_PAD
            .decode(encoded.strip_prefix(INVITE_PREFIX).unwrap())
            .unwrap();
        let value: serde_json::Value = serde_json::from_slice(&json).unwrap();
        assert_eq!(value["kind"], "fleet");

        // Plain rooms never carry the field, and an old invite (no kind)
        // decodes as a plain room; an explicit "room" is plain too.
        let plain = RoomInvite::new("Home fleet", SECRET, members()).unwrap();
        assert!(!plain.is_fleet());
        let payload = |json: &str| format!("{INVITE_PREFIX}{}", URL_SAFE_NO_PAD.encode(json));
        let old = RoomInvite::decode(&payload(&format!(
            r#"{{"v":1,"name":"r","secret":"{SECRET}","members":[]}}"#
        )))
        .unwrap();
        assert_eq!(old.kind, RoomKind::Room);
        let explicit = RoomInvite::decode(&payload(&format!(
            r#"{{"v":1,"name":"r","secret":"{SECRET}","members":[],"kind":"room"}}"#
        )))
        .unwrap();
        assert_eq!(explicit.kind, RoomKind::Room);

        // Malformed kinds are shape errors with fixed text.
        for bad in [r#""FLEET""#, r#""party""#, "1", "null", r#"["fleet"]"#] {
            let error = RoomInvite::decode(&payload(&format!(
                r#"{{"v":1,"name":"r","secret":"{SECRET}","members":[],"kind":{bad}}}"#
            )))
            .unwrap_err();
            assert_eq!(error, RoomInviteError::Malformed, "{bad}");
            assert!(!error.to_string().contains(SECRET));
            assert!(!error.to_string().contains("party"));
        }

        // Debug names the kind and still redacts the secret.
        let debug = format!("{fleet:?}");
        assert!(debug.contains("Fleet"), "{debug}");
        assert!(!debug.contains(SECRET), "{debug}");
    }

    #[test]
    fn minted_secrets_are_fresh_and_valid() {
        let a = mint_room_secret().unwrap();
        let b = mint_room_secret().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), 43);
        assert!(RoomInvite::fleet("Fleet", &a, vec![]).is_ok());
    }

    #[test]
    fn errors_and_debug_never_echo_secrets() {
        let invite = RoomInvite::new("Home fleet", "SECRETSECRET", members()).unwrap();
        let debug = format!("{invite:?}");
        assert!(!debug.contains("SECRETSECRET"), "{debug}");
        let member_debug = format!("{:?}", invite.members[0]);
        assert!(!member_debug.contains(ADDRESS), "{member_debug}");
        assert!(!describe("SECRETSECRET").contains("SECRET"));
        // A member address is redacted in book debug output too.
        let book = RoomBook { members: members() };
        let book_debug = format!("{book:?}");
        assert!(!book_debug.contains(ADDRESS), "{book_debug}");
    }

    #[test]
    fn the_room_id_is_deterministic_and_opaque() {
        let a = room_id(SECRET);
        let b = room_id(SECRET);
        assert_eq!(a, b);
        assert_eq!(a.len(), 43);
        // Different secrets map to different ids, and the id never contains
        // the secret.
        assert_ne!(a, room_id("another-secret"));
        assert!(!a.contains(SECRET));
        // The id digest and the engine's verifier digest are different tags of
        // the same secret (never equal), and the invite derives the same id.
        assert_ne!(room_id_bytes(SECRET), tagged_digest(b"verifier", SECRET));
        let invite = RoomInvite::new("room", SECRET, vec![]).unwrap();
        assert_eq!(invite.room_id(), a);
    }

    #[test]
    fn room_wire_types_use_camel_case_and_round_trip() {
        let book = RoomBook { members: members() };
        let value = serde_json::to_value(&book).unwrap();
        assert_eq!(value["members"][0]["label"], "desktop");
        assert_eq!(value["members"][0]["address"], ADDRESS);
        let round: RoomBook = serde_json::from_value(value).unwrap();
        assert_eq!(round, book);
        // A newer engine's extra fields never break an older client's book.
        let lenient: RoomBook = serde_json::from_value(serde_json::json!({
            "members": [{"label": "a", "address": "tca", "futureField": 1}],
            "futureField": 2
        }))
        .unwrap();
        assert_eq!(lenient.members.len(), 1);
    }
}
