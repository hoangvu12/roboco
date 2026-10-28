//! The `roboco-tailcat:` invite grammar, shared by the engine (which mints
//! invites), the desktop and CLI (which redeem them) and the Android core
//! (android-next#02), so there is exactly one decoder.
//!
//! An invite is `roboco-tailcat:` + base64url-no-pad(JSON `{"address":"tc…",
//! "token":"<pair code>","expiresAt":<ms>}`). The address can embed the
//! server's pre-shared key and the token is a live pairing code, so both are
//! secrets: errors here describe only shape, the [`Debug`] impl redacts both,
//! and [`describe`] is the only form of an address fit for a log.

use std::fmt;

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};

/// Prefix of a single-paste tailcat handle carrying route plus pair code.
pub const INVITE_PREFIX: &str = "roboco-tailcat:";

/// The longest address (and pairing code) accepted.
pub const MAX_ADDRESS_BYTES: usize = 4096;

/// Shape-only description of an address, safe to log.
pub fn describe(address: &str) -> String {
    format!("tc…({} chars)", address.len())
}

/// Why an address, code or invite was refused. Every message is fixed text:
/// none ever quotes the input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InviteError {
    AddressEmpty,
    AddressTooLong,
    AddressPrefix,
    AddressAlphabet,
    CodeMissing,
    CodeMalformed,
    NotAnInvite,
    NotBase64,
    Malformed,
    Encode,
}

impl fmt::Display for InviteError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::AddressEmpty => "tailcat address is empty",
            Self::AddressTooLong => "tailcat address exceeds 4096 bytes",
            Self::AddressPrefix => "tailcat address must start with \"tc\"",
            Self::AddressAlphabet => "tailcat address carries characters outside base64url",
            Self::CodeMissing => "tailcat invite carries no pairing code",
            Self::CodeMalformed => "tailcat invite pairing code is malformed",
            Self::NotAnInvite => {
                "a tailcat invite starts with roboco-tailcat:; pass --address for a bare address"
            }
            Self::NotBase64 => "tailcat invite payload is not base64url",
            Self::Malformed => "tailcat invite payload is malformed",
            Self::Encode => "tailcat invite could not be encoded",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for InviteError {}

fn base64url(value: &str) -> bool {
    value
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

/// A `tc…` address is compact base64url text. Validate shape, not existence:
/// only the adapter can tell whether the peer is listening.
pub fn validate_tailcat_address(address: &str) -> Result<(), InviteError> {
    if address.is_empty() {
        return Err(InviteError::AddressEmpty);
    }
    if address.len() > MAX_ADDRESS_BYTES {
        return Err(InviteError::AddressTooLong);
    }
    if !address.starts_with("tc") {
        return Err(InviteError::AddressPrefix);
    }
    if !base64url(address) {
        return Err(InviteError::AddressAlphabet);
    }
    Ok(())
}

/// A pairing code: non-empty base64url text, at most 4096 bytes.
pub fn validate_pair_code(code: &str) -> Result<(), InviteError> {
    if code.is_empty() {
        return Err(InviteError::CodeMissing);
    }
    if code.len() > MAX_ADDRESS_BYTES || !base64url(code) {
        return Err(InviteError::CodeMalformed);
    }
    Ok(())
}

/// One pasteable handle: the route to the engine plus a short-lived,
/// single-use pairing code minted by that engine.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TailcatInvite {
    pub address: String,
    pub token: String,
    /// Unix milliseconds.
    pub expires_at: i64,
}

impl fmt::Debug for TailcatInvite {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TailcatInvite")
            .field("address", &describe(&self.address))
            .field("token", &"<redacted>")
            .field("expires_at", &self.expires_at)
            .finish()
    }
}

impl TailcatInvite {
    pub fn new(address: &str, token: &str, expires_at: i64) -> Result<Self, InviteError> {
        validate_tailcat_address(address)?;
        validate_pair_code(token)?;
        Ok(Self {
            address: address.to_owned(),
            token: token.to_owned(),
            expires_at,
        })
    }

    pub fn encode(&self) -> Result<String, InviteError> {
        validate_tailcat_address(&self.address)?;
        validate_pair_code(&self.token)?;
        let payload = serde_json::to_vec(self).map_err(|_| InviteError::Encode)?;
        Ok(format!("{INVITE_PREFIX}{}", URL_SAFE_NO_PAD.encode(payload)))
    }

    pub fn decode(value: &str) -> Result<Self, InviteError> {
        let payload = value
            .trim()
            .strip_prefix(INVITE_PREFIX)
            .ok_or(InviteError::NotAnInvite)?;
        let bytes = URL_SAFE_NO_PAD
            .decode(payload)
            .map_err(|_| InviteError::NotBase64)?;
        // serde_json's message can quote an unknown field name; it is
        // dropped for the fixed text.
        let invite: Self = serde_json::from_slice(&bytes).map_err(|_| InviteError::Malformed)?;
        validate_tailcat_address(&invite.address)?;
        validate_pair_code(&invite.token)?;
        Ok(invite)
    }

    pub fn expired(&self, now_millis: i64) -> bool {
        self.expires_at <= now_millis
    }
}

/// How a Tailcat client last reached its engine (fleet-redesign 16):
/// directly, or through a relay region, with the measured round trip. The
/// adapter's status line, and `roboco_tailcat_path`'s reply:
/// `{"path":"direct","rttMs":35}` or
/// `{"path":"relay","region":"tok","rttMs":160}`. Never an address or a key
/// (ADR 0008): the region is a short code.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkPath {
    pub path: LinkPathKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region: Option<String>,
    pub rtt_ms: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum LinkPathKind {
    Direct,
    Relay,
}

impl LinkPath {
    /// Parse one status line; anything malformed is `None`.
    pub fn parse(line: &str) -> Option<Self> {
        let mut path: Self = serde_json::from_str(line.trim()).ok()?;
        // Keep the region a short code, whatever the adapter sent.
        path.region = path.region.filter(|code| {
            !code.is_empty()
                && code.len() <= 16
                && code.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        });
        Some(path)
    }

    /// The row label: "Direct · 35 ms", "Via relay (Tokyo) · 160 ms".
    pub fn label(&self) -> String {
        match self.path {
            LinkPathKind::Direct => format!("Direct · {} ms", self.rtt_ms),
            LinkPathKind::Relay => match self.region.as_deref() {
                Some(code) => format!("Via relay ({}) · {} ms", relay_city(code), self.rtt_ms),
                None => format!("Via relay · {} ms", self.rtt_ms),
            },
        }
    }
}

/// The city of a Tailscale DERP region code ("tok" → "Tokyo"); an unknown
/// code (a custom DERP map) shows as itself, upper-cased.
pub fn relay_city(code: &str) -> String {
    let city = match code.to_ascii_lowercase().as_str() {
        "nyc" => "New York",
        "sfo" => "San Francisco",
        "sin" => "Singapore",
        "fra" => "Frankfurt",
        "syd" => "Sydney",
        "blr" => "Bangalore",
        "tok" => "Tokyo",
        "lhr" => "London",
        "sao" => "São Paulo",
        "ord" => "Chicago",
        "dfw" => "Dallas",
        "sea" => "Seattle",
        "den" => "Denver",
        "mia" => "Miami",
        "hkg" => "Hong Kong",
        "nai" => "Nairobi",
        "jnb" => "Johannesburg",
        "mad" => "Madrid",
        "waw" => "Warsaw",
        "par" => "Paris",
        "ams" => "Amsterdam",
        "hnl" => "Honolulu",
        "tor" => "Toronto",
        "lax" => "Los Angeles",
        "dbi" => "Dubai",
        "nue" => "Nuremberg",
        "hel" => "Helsinki",
        "ash" => "Ashburn",
        "mel" => "Melbourne",
        "bom" => "Mumbai",
        "scl" => "Santiago",
        "bog" => "Bogotá",
        "qro" => "Querétaro",
        _ => return code.to_ascii_uppercase(),
    };
    city.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_paths_parse_the_adapter_lines_and_label_them() {
        let direct = LinkPath::parse(r#"{"path":"direct","rttMs":35}"#).unwrap();
        assert_eq!(direct.label(), "Direct · 35 ms");
        let relay = LinkPath::parse(r#"{"path":"relay","region":"tok","rttMs":160}"#).unwrap();
        assert_eq!(relay.label(), "Via relay (Tokyo) · 160 ms");
        let custom = LinkPath::parse(r#"{"path":"relay","region":"sg-1","rttMs":20}"#).unwrap();
        assert_eq!(custom.label(), "Via relay (SG-1) · 20 ms");
        // A region that is not a short code is dropped, never shown.
        let odd =
            LinkPath::parse(r#"{"path":"relay","region":"10.0.0.1:41641","rttMs":9}"#).unwrap();
        assert_eq!(odd.label(), "Via relay · 9 ms");
        assert!(LinkPath::parse("not json").is_none());
        assert!(LinkPath::parse(r#"{"url":"http://127.0.0.1:1"}"#).is_none());
    }

    const ADDRESS: &str = "tcAAAABBBBccccDDDD_eeee-ffff0123";

    #[test]
    fn addresses_are_shape_checked_and_never_logged() {
        assert!(validate_tailcat_address(ADDRESS).is_ok());
        let oversized = format!("tc{}", "A".repeat(MAX_ADDRESS_BYTES));
        for bad in [
            "",
            "http://127.0.0.1:27655/",
            "tcAAAA BBBB",
            "tcAAAA\nBBBB",
            "tcAAAAé",
            oversized.as_str(),
        ] {
            assert!(validate_tailcat_address(bad).is_err(), "{bad:?} was accepted");
        }
        assert_eq!(describe(ADDRESS), format!("tc…({} chars)", ADDRESS.len()));
        assert!(!describe("tcSECRETSECRET").contains("SECRET"));
    }

    #[test]
    fn invites_round_trip_and_reject_junk() {
        let invite = TailcatInvite::new(ADDRESS, "code-123", 1_700_000_000_000).unwrap();
        let encoded = invite.encode().unwrap();
        assert!(encoded.starts_with(INVITE_PREFIX));
        assert_eq!(TailcatInvite::decode(&encoded).unwrap(), invite);
        assert_eq!(
            TailcatInvite::decode(&format!("  {encoded}\n")).unwrap(),
            invite
        );
        for junk in [
            "",
            "http://example/pair#token=1",
            "roboco-tailcat:!!!!",
            "roboco-tailcat:eyJhIjoxfQ",
        ] {
            assert!(TailcatInvite::decode(junk).is_err(), "{junk:?} decoded");
        }
    }

    #[test]
    fn the_expiry_boundary_is_inclusive() {
        let invite = TailcatInvite::new(ADDRESS, "code", 1_700_000_000_000).unwrap();
        assert!(!invite.expired(1_699_999_999_999));
        assert!(invite.expired(1_700_000_000_000));
        assert!(invite.expired(1_700_000_000_001));
    }

    #[test]
    fn errors_and_debug_never_echo_secrets() {
        let secret_address = "tcSECRETADDRESS";
        let secret_token = "SECRETTOKEN";
        let invite = TailcatInvite::new(secret_address, secret_token, 1).unwrap();
        let debug = format!("{invite:?}");
        assert!(!debug.contains("SECRET"), "{debug}");

        // An unknown field and bad values in an otherwise valid payload.
        let unknown = format!(
            "{INVITE_PREFIX}{}",
            URL_SAFE_NO_PAD.encode(
                br#"{"address":"tcSECRETADDRESS","token":"SECRETTOKEN","expiresAt":1,"SECRETFIELD":1}"#
            )
        );
        let bad_address = format!(
            "{INVITE_PREFIX}{}",
            URL_SAFE_NO_PAD
                .encode(br#"{"address":"SECRET ADDRESS","token":"SECRETTOKEN","expiresAt":1}"#)
        );
        let bad_token = format!(
            "{INVITE_PREFIX}{}",
            URL_SAFE_NO_PAD
                .encode(br#"{"address":"tcSECRETADDRESS","token":"SECRET TOKEN","expiresAt":1}"#)
        );
        for input in [
            unknown.as_str(),
            bad_address.as_str(),
            bad_token.as_str(),
            "roboco-tailcat:SECRET*",
            "SECRET",
        ] {
            let error = TailcatInvite::decode(input).unwrap_err().to_string();
            assert!(!error.contains("SECRET"), "{input:?} -> {error}");
        }
    }
}
