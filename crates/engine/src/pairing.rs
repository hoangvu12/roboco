//! Engine-local pairing credentials. SQLite rows are shared with the CLI;
//! only verifiers are persisted, and redemption commits consumption and issuance together.
use std::{
    path::{Path, PathBuf},
    time::Duration,
};

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use roboco_proto::PairedSession;
use rusqlite::{Connection, OptionalExtension, TransactionBehavior, params};
use serde::Serialize;
use sha2::{Digest, Sha256};

pub const DEFAULT_TTL_SECONDS: u64 = 300;
pub const MAX_TTL_SECONDS: u64 = 3600;

#[derive(Clone)]
pub struct PairingStore {
    path: PathBuf,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingCode {
    pub id: String,
    pub credential: String,
    pub expires_at: i64,
}

#[derive(Serialize, ts_rs::TS)]
#[serde(rename_all = "camelCase")]
pub struct SessionGrant {
    pub session: PairedSession,
    pub credential: String,
}

impl PairingStore {
    pub fn open(data_dir: &Path) -> anyhow::Result<Self> {
        std::fs::create_dir_all(data_dir)?;
        let store = Self {
            path: data_dir.join("pairing.sqlite3"),
        };
        let db = store.connect()?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        db.execute_batch(
            "CREATE TABLE IF NOT EXISTS pair_codes (
            id TEXT PRIMARY KEY, verifier BLOB NOT NULL UNIQUE,
            label TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
        ); CREATE TABLE IF NOT EXISTS paired_sessions (
            id TEXT PRIMARY KEY, verifier BLOB NOT NULL UNIQUE, label TEXT NOT NULL,
            created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, revoked_at INTEGER
        );",
        )?;
        Ok(store)
    }

    fn connect(&self) -> anyhow::Result<Connection> {
        let db = Connection::open(&self.path)?;
        db.busy_timeout(Duration::from_secs(5))?;
        Ok(db)
    }

    pub fn create_code(&self, label: &str, ttl_seconds: u64) -> anyhow::Result<PairingCode> {
        anyhow::ensure!(
            (1..=MAX_TTL_SECONDS).contains(&ttl_seconds),
            "pairing lifetime must be 1..={MAX_TTL_SECONDS} seconds"
        );
        validate_label(label)?;
        let credential = secret()?;
        let id = uuid::Uuid::new_v4().to_string();
        let now = crate::now_ms();
        let expires_at = now + (ttl_seconds * 1000) as i64;
        self.connect()?.execute(
            "INSERT INTO pair_codes VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, verifier("pair", &credential), label, now, expires_at],
        )?;
        Ok(PairingCode {
            id,
            credential,
            expires_at,
        })
    }

    pub fn redeem(&self, code: &str, label: &str) -> anyhow::Result<Option<SessionGrant>> {
        validate_label(label)?;
        if !valid_secret(code) {
            return Ok(None);
        }
        let credential = secret()?;
        let mut db = self.connect()?;
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let now = crate::now_ms();
        let code_label: Option<String> = tx
            .query_row(
                "DELETE FROM pair_codes WHERE verifier = ?1 AND expires_at > ?2 RETURNING label",
                params![verifier("pair", code), now],
                |row| row.get(0),
            )
            .optional()?;
        let Some(code_label) = code_label else {
            return Ok(None);
        };
        let session = PairedSession {
            id: uuid::Uuid::new_v4().to_string(),
            label: if label.trim().is_empty() {
                code_label
            } else {
                label.trim().to_owned()
            },
            created_at: now,
            last_seen: now,
            revoked_at: None,
        };
        tx.execute(
            "INSERT INTO paired_sessions VALUES (?1, ?2, ?3, ?4, ?4, NULL)",
            params![
                session.id,
                verifier("session", &credential),
                session.label,
                now
            ],
        )?;
        tx.commit()?;
        Ok(Some(SessionGrant {
            session,
            credential,
        }))
    }

    pub fn authenticate(&self, credential: &str) -> anyhow::Result<Option<PairedSession>> {
        if !valid_secret(credential) {
            return Ok(None);
        }
        Ok(self.connect()?.query_row(
            "UPDATE paired_sessions SET last_seen = ?2 WHERE verifier = ?1 AND revoked_at IS NULL
             RETURNING id, label, created_at, last_seen, revoked_at",
            params![verifier("session", credential), crate::now_ms()], session_row).optional()?)
    }

    pub fn list_sessions(&self) -> anyhow::Result<Vec<PairedSession>> {
        let db = self.connect()?;
        let mut query = db.prepare("SELECT id, label, created_at, last_seen, revoked_at FROM paired_sessions ORDER BY created_at, id")?;
        Ok(query
            .query_map([], session_row)?
            .collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn revoke(&self, id: &str) -> anyhow::Result<bool> {
        Ok(self.connect()?.execute(
            "UPDATE paired_sessions SET revoked_at = ?2 WHERE id = ?1 AND revoked_at IS NULL",
            params![id, crate::now_ms()],
        )? > 0)
    }
}

fn session_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<PairedSession> {
    Ok(PairedSession {
        id: row.get(0)?,
        label: row.get(1)?,
        created_at: row.get(2)?,
        last_seen: row.get(3)?,
        revoked_at: row.get(4)?,
    })
}

fn secret() -> anyhow::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes)
        .map_err(|_| anyhow::anyhow!("operating system randomness unavailable"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

fn valid_secret(value: &str) -> bool {
    value.len() == 43
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn verifier(kind: &str, credential: &str) -> Vec<u8> {
    let mut hash = Sha256::new();
    hash.update(b"roboco-pairing-v1\0");
    hash.update(kind.as_bytes());
    hash.update(b"\0");
    hash.update(credential.as_bytes());
    hash.finalize().to_vec()
}

fn validate_label(label: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        label.len() <= 256 && !label.chars().any(char::is_control),
        "device label must contain at most 256 bytes and no control characters"
    );
    Ok(())
}

pub fn pairing_url(base_url: &str, credential: &str) -> anyhow::Result<String> {
    let mut url = reqwest::Url::parse(base_url)?;
    anyhow::ensure!(
        matches!(url.scheme(), "http" | "https")
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none(),
        "base URL must be HTTP or HTTPS without credentials, query, or fragment"
    );
    // The listener exact-matches its routes at the root, so a tunnel base URL
    // carrying a path prefix would mint links the engine can never serve.
    anyhow::ensure!(
        matches!(url.path(), "" | "/"),
        "base URL must not carry a path prefix"
    );
    url.set_path("/pair");
    url.set_fragment(Some(&format!("token={credential}")));
    Ok(url.to_string())
}
