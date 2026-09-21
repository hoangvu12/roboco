//! Remote-access and pairing wire types shared by the engine and its clients.

use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use ts_rs::TS;

/// An engine's effective remote-access state (`GetRemoteAccess`).
#[derive(Debug, Clone, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct RemoteAccessStatus {
    pub enabled: bool,
    pub configured_enabled: bool,
    pub address: Option<SocketAddr>,
    pub source: String,
    pub error: Option<String>,
}

/// A paired client session — the credential a paired client holds for an
/// engine (CONTEXT.md). Does not expire; lives until revoked.
#[derive(Debug, Clone, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct PairedSession {
    pub id: String,
    pub label: String,
    pub created_at: i64,
    pub last_seen: i64,
    pub revoked_at: Option<i64>,
}

/// The `GetRemoteAccess` snapshot; also the reply of `SetRemoteAccess` and
/// `RevokePairingSession`, so a caller always lands on fresh state.
#[derive(Debug, Clone, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct RemoteAccessSnapshot {
    pub status: RemoteAccessStatus,
    pub sessions: Vec<PairedSession>,
}

/// A freshly minted single-use pairing link (`CreatePairingLink`).
#[derive(Debug, Clone, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct PairingLink {
    pub url: String,
    pub expires_at: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn remote_access_payloads_use_camel_case_fields() {
        let snapshot = RemoteAccessSnapshot {
            status: RemoteAccessStatus {
                enabled: true,
                configured_enabled: true,
                address: Some("127.0.0.1:27655".parse().unwrap()),
                source: "remote-access.json".into(),
                error: None,
            },
            sessions: vec![PairedSession {
                id: "session-1".into(),
                label: "Laptop".into(),
                created_at: 1,
                last_seen: 2,
                revoked_at: None,
            }],
        };
        let value = serde_json::to_value(&snapshot).unwrap();
        assert_eq!(value["status"]["configuredEnabled"], true);
        assert_eq!(value["status"]["address"], "127.0.0.1:27655");
        assert_eq!(value["sessions"][0]["lastSeen"], 2);
        assert_eq!(value["sessions"][0]["revokedAt"], serde_json::Value::Null);
        let round: RemoteAccessSnapshot = serde_json::from_value(value).unwrap();
        assert_eq!(round.sessions[0].label, "Laptop");

        let link = PairingLink {
            url: "https://engine.example/pair#token=abc".into(),
            expires_at: 3,
        };
        let value = serde_json::to_value(&link).unwrap();
        assert_eq!(value["expiresAt"], 3);
    }
}
