//! Pairing administration opens SQLite directly and never takes the engine lock.
use anyhow::Context as _;
use clap::Subcommand;
use roboco_engine::pairing::{DEFAULT_TTL_SECONDS, PairingStore, pairing_url};
use roboco_engine::tailcat::{INVITE_PREFIX, TailcatClient, TailcatInvite, read_recorded_address};

#[derive(Subcommand)]
pub enum EngineCommand {
    /// Manage credentials for remote clients.
    Pairing {
        #[command(subcommand)]
        command: PairingCommand,
    },
    /// Administer the Tailcat route: address, invites, and one client's forwarder.
    Tailcat {
        #[command(subcommand)]
        command: TailcatCommand,
    },
}

#[derive(Subcommand)]
pub enum PairingCommand {
    /// Mint a short-lived, single-use pairing URL.
    Create {
        /// HTTP(S) address clients use to reach this engine.
        #[arg(long)]
        base_url: String,
        #[arg(long, default_value = "")]
        label: String,
        #[arg(long, default_value_t = DEFAULT_TTL_SECONDS)]
        ttl_seconds: u64,
        #[arg(long)]
        json: bool,
    },
    /// List paired sessions, including revocation and last-seen timestamps.
    List {
        #[arg(long)]
        json: bool,
    },
    /// Revoke a session immediately for subsequent authentication attempts.
    Revoke { session_id: String },
}

#[derive(Subcommand)]
pub enum TailcatCommand {
    /// Print the address this engine last published, if any.
    Address {
        #[arg(long)]
        json: bool,
    },
    /// Mint a short-lived, single-use invite for this engine's route.
    Invite {
        #[arg(long, default_value_t = DEFAULT_TTL_SECONDS)]
        ttl_seconds: u64,
        #[arg(long)]
        json: bool,
    },
    /// Dial this engine's route and print the loopback pairing URL.
    Connect {
        /// Invite minted by the engine (`roboco-tailcat:…`) or a bare `tc…` address.
        #[arg(long)]
        invite: String,
        /// Pairing code, for a bare address: invites carry their own.
        #[arg(long)]
        token: Option<String>,
        /// DERP map URL override (default: the adapter's own map).
        #[arg(long)]
        derp_map: Option<String>,
        #[arg(long)]
        json: bool,
    },
    /// Loopback HTTP helper so browsers paste `roboco-tailcat:…` without a local URL.
    BrowserHelper {
        /// Listen address (default 127.0.0.1:7333).
        #[arg(long)]
        listen: Option<String>,
    },
}

/// Tailcat administration. Invites are minted here, paired exactly like a
/// Cloudflare link, and the forwarder lives as long as this process.
fn tailcat(command: TailcatCommand, data_dir: &std::path::Path) -> anyhow::Result<()> {
    match command {
        TailcatCommand::Address { json } => {
            let address = read_recorded_address(data_dir)?.with_context(|| {
                format!(
                    "no tailcat address recorded for {}; start the engine with --network-transport tailcat",
                    data_dir.display()
                )
            })?;
            if json {
                println!("{}", serde_json::json!({"address": address}));
            } else {
                println!("{address}");
            }
            Ok(())
        }
        TailcatCommand::Invite { ttl_seconds, json } => {
            let address = read_recorded_address(data_dir)?.with_context(|| {
                format!(
                    "no tailcat address recorded for {}; start the engine with --network-transport tailcat",
                    data_dir.display()
                )
            })?;
            let store = PairingStore::open(data_dir)?;
            let code = store.create_code("", ttl_seconds)?;
            let invite = TailcatInvite::new(&address, &code.credential, code.expires_at)?;
            let url = invite.encode()?;
            if json {
                println!(
                    "{}",
                    serde_json::json!({"id": code.id, "url": url, "expiresAt": code.expires_at})
                );
            } else {
                println!("{url}");
                println!(
                    "Expires at {} (Unix milliseconds). Paste it where a pairing link goes.",
                    code.expires_at
                );
            }
            Ok(())
        }
        TailcatCommand::Connect {
            invite,
            token,
            derp_map,
            json,
        } => {
            let (address, token) = if invite.trim_start().starts_with(INVITE_PREFIX) {
                let invite = TailcatInvite::decode(&invite)?;
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)?
                    .as_millis() as i64;
                anyhow::ensure!(!invite.expired(now), "that tailcat invite has expired; mint a new one");
                (invite.address, invite.token)
            } else {
                let token = token.context(
                    "a bare tailcat address needs --token with a pairing code; invites carry one",
                )?;
                roboco_engine::tailcat::validate_tailcat_address(&invite)?;
                (invite, token)
            };
            let client = TailcatClient::start(data_dir, &address, derp_map.as_deref())?;
            let url = format!("{}/pair#token={token}", client.url().trim_end_matches('/'));
            if json {
                println!("{}", serde_json::json!({"url": url}));
            } else {
                println!("{url}");
                println!("Forwarding this engine over Tailcat until interrupted; Ctrl-C stops it.");
            }
            // Hold the route: the adapter dies with this process, exactly like a
            // tunnel process would.
            std::thread::park();
            Ok(())
        }
        TailcatCommand::BrowserHelper { listen } => {
            crate::tailcat_browser_helper::run(data_dir, listen.as_deref())
        }
    }
}

pub fn run(command: EngineCommand, data_dir: &std::path::Path) -> anyhow::Result<()> {
    let command = match command {
        EngineCommand::Tailcat { command } => return tailcat(command, data_dir),
        EngineCommand::Pairing { command } => command,
    };
    let store = PairingStore::open(data_dir)?;
    match command {
        PairingCommand::Create {
            base_url,
            label,
            ttl_seconds,
            json,
        } => {
            // Validate the address before issuing a credential.
            pairing_url(&base_url, "")?;
            let code = store.create_code(&label, ttl_seconds)?;
            let url = pairing_url(&base_url, &code.credential)?;
            if json {
                println!(
                    "{}",
                    serde_json::json!({"id":code.id,"url":url,"expiresAt":code.expires_at})
                );
            } else {
                println!("{url}");
                println!(
                    "Expires at {} (Unix milliseconds). Use this link once to pair a client.",
                    code.expires_at
                );
            }
        }
        PairingCommand::List { json } => {
            let sessions = store.list_sessions()?;
            if json {
                println!("{}", serde_json::to_string(&sessions)?);
            } else if sessions.is_empty() {
                println!("No paired sessions.");
            } else {
                for session in sessions {
                    println!(
                        "{}\t{}\tlast seen {}\t{}",
                        session.id,
                        session.label,
                        session.last_seen,
                        if session.revoked_at.is_some() {
                            "revoked"
                        } else {
                            "active"
                        }
                    );
                }
            }
        }
        PairingCommand::Revoke { session_id } => {
            anyhow::ensure!(
                store.revoke(&session_id)?,
                "active session not found: {session_id}"
            );
            println!("Revoked {session_id}");
        }
    }
    Ok(())
}
