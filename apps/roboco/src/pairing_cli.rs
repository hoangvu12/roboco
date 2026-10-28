//! Pairing and room administration use SQLite; Tailcat setup talks to the
//! owning engine. Rooms (ADR 0011) share reachability, never data.
use anyhow::Context as _;
use clap::Subcommand;
use roboco_engine::pairing::{DEFAULT_TTL_SECONDS, PairingStore, pairing_url};
use roboco_engine::rooms::{FleetConflict, RoomRecord, RoomStore};
use roboco_engine::tailcat::{INVITE_PREFIX, TailcatClient, TailcatInvite, read_recorded_address};
use roboco_proto::room::{RoomInvite, RoomKind, RoomMember};

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
    /// Create, share, join, list, and leave rooms and this engine's fleet (ADR 0011).
    #[command(after_help = ROOM_EXIT_CODES)]
    Room {
        #[command(subcommand)]
        command: RoomCommand,
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
        /// Print the pairing URL as a terminal QR code under the URL line
        /// (and add `qrSvg` to `--json` output).
        #[arg(long)]
        qr: bool,
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
    /// Start the engine if needed, enable Tailcat, and print a single-use invite.
    Invite {
        #[arg(long, default_value_t = DEFAULT_TTL_SECONDS)]
        ttl_seconds: u64,
        /// Print the invite as a terminal QR code under the invite line
        /// (and keep `qrSvg` in `--json` output).
        #[arg(long)]
        qr: bool,
        #[arg(long)]
        json: bool,
    },
    /// Dial this engine's route and print a loopback pairing link for a
    /// Roboco app.
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
        TailcatCommand::Invite {
            ttl_seconds,
            qr,
            json,
        } => {
            let mut invite = crate::tailcat_setup::invite(data_dir, ttl_seconds)?;
            let url = invite["url"]
                .as_str()
                .context("engine returned no Tailcat invite")?
                .to_owned();
            if json {
                // As `pairing create`: `qrSvg` appears only with --qr, so
                // existing JSON consumers keep the old shape. The engine sends
                // it from this release on; an older engine sends none, so
                // --qr encodes it here instead.
                if let Some(fields) = invite.as_object_mut() {
                    if !qr {
                        fields.remove("qrSvg");
                    } else if !fields.contains_key("qrSvg") {
                        fields.insert("qrSvg".into(), roboco_proto::qr::try_qr_svg(&url).into());
                    }
                }
                println!("{invite}");
            } else {
                println!("{url}");
                // The half-block QR scans from the terminal itself. An invite
                // too long for any QR (an oversized custom address) prints
                // none; the invite line above still pastes.
                if qr {
                    match roboco_proto::qr::try_qr_terminal(&url) {
                        Some(code) => println!("{code}"),
                        None => {
                            eprintln!("This invite is too long for a QR code; paste it instead.")
                        }
                    }
                }
                println!(
                    "Expires at {} (Unix milliseconds). Paste it where a pairing link goes.",
                    invite["expiresAt"]
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
                println!(
                    "Paste this loopback pairing link into a Roboco app. Forwarding this \
                     engine over Tailcat until interrupted; Ctrl-C stops it."
                );
            }
            // Hold the route: the adapter dies with this process, exactly like a
            // tunnel process would.
            std::thread::park();
            Ok(())
        }
    }
}

/// The exit-code contract shared by every `roboco engine room` command.
const ROOM_EXIT_CODES: &str = "\
Exit codes:
  0  success
  1  failure: a malformed invite, an unreadable store, an I/O error
  2  usage error: unknown flag or missing argument
  3  refused: this engine already belongs to a fleet, the room is not a
     fleet room, or no such room is joined

Errors go to stderr, starting with `Error:`; no JSON is printed on
failure. Refusals are one line of fixed text. Room errors never quote
an invite or a secret.";

/// Room administration (ADR 0011). Rooms are hostless reachability groups:
/// joining a plain room stores only a verifier of its secret plus the member
/// address book; clients that present the secret mint ordinary sessions. A
/// fleet room (one per data dir) also keeps its secret so any member engine
/// can introduce a newly paired client to the whole fleet.
#[derive(Subcommand)]
pub enum RoomCommand {
    /// Mint this engine's fleet room and print its id (never its secret).
    ///
    /// The fleet's book starts with this engine's own entry (--label plus the
    /// Tailcat address the engine recorded) when an address is recorded, and
    /// empty otherwise. A data dir holds one fleet: a second create is
    /// refused (exit 3) until `room leave` drops the first.
    ///
    /// --json prints one room object:
    ///   {"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}
    #[command(verbatim_doc_comment, after_help = ROOM_EXIT_CODES)]
    Create {
        /// The fleet's name.
        #[arg(long, default_value = "Fleet")]
        name: String,
        /// This engine's label in the fleet's book (default: its device name).
        #[arg(long)]
        label: Option<String>,
        #[arg(long)]
        json: bool,
    },
    /// Print a fleet room's invite (a secret) and optionally its QR code.
    ///
    /// This is the only room command whose output carries an invite: anyone
    /// holding it can join the fleet and pair with its engines, so share it
    /// only with your own devices. Plain rooms and unknown ids are refused
    /// with "not a fleet room" (exit 3) — engines keep no plain-room secret.
    ///
    /// --json prints:
    ///   {"roomId":"<room id>","invite":"roboco-room:…"}
    /// and with --qr adds "qrSvg":"<svg>" (null when the invite is too long
    /// for a QR code).
    #[command(verbatim_doc_comment, after_help = ROOM_EXIT_CODES)]
    Invite {
        /// The fleet room's id (from `room create` or `room list`).
        // Room ids are base64url and may begin with `-`.
        #[arg(allow_hyphen_values = true)]
        room_id: String,
        /// Print the invite as a terminal QR code under the invite line (and
        /// add `qrSvg` to `--json` output).
        #[arg(long)]
        qr: bool,
        #[arg(long)]
        json: bool,
    },
    /// Join a room from a `roboco-room:` invite. The invite is never printed
    /// or logged.
    ///
    /// Re-joining the same invite is idempotent: the label is refreshed and
    /// the book unioned, never a second row. A fleet invite while this engine
    /// belongs to a different fleet is refused (exit 3).
    ///
    /// --json prints one room object:
    ///   {"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"room"|"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}
    #[command(verbatim_doc_comment, after_help = ROOM_EXIT_CODES)]
    Join {
        /// The `roboco-room:` invite (it is a secret: paste it, never commit it).
        invite: String,
        #[arg(long)]
        json: bool,
    },
    /// List joined rooms with their kind and member counts.
    ///
    /// --json prints an array of room objects (empty: []):
    ///   [{"id":"<room id>","label":"<room name>","joinedAt":<unix ms>,"kind":"room"|"fleet","memberCount":<n>,"book":{"members":[{"label":"<engine label>","address":"<tc… address>"}]}}]
    #[command(verbatim_doc_comment, after_help = ROOM_EXIT_CODES)]
    List {
        #[arg(long)]
        json: bool,
    },
    /// Leave a room: forget its verifier, address book, and (for a fleet) its
    /// secret. Sessions it minted are ordinary sessions — revoke them with
    /// `pairing revoke`.
    ///
    /// An unknown id is refused with "no such room" (exit 3).
    ///
    /// --json prints:
    ///   {"roomId":"<room id>","left":true}
    #[command(verbatim_doc_comment, after_help = ROOM_EXIT_CODES)]
    Leave {
        // Room ids are base64url and may begin with `-`.
        #[arg(allow_hyphen_values = true)]
        room_id: String,
        #[arg(long)]
        json: bool,
    },
}

/// A room command refused with fixed text (exit code 3). The text never
/// carries caller input, so a mistakenly pasted invite is never echoed.
#[derive(Debug)]
struct RoomRefusal(&'static str);

impl std::fmt::Display for RoomRefusal {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.0)
    }
}

impl std::error::Error for RoomRefusal {}

/// Exit code for refusals; see [`ROOM_EXIT_CODES`].
const REFUSED_EXIT_CODE: i32 = 3;

/// The `--json` room object documented in each command's help: the store
/// record plus its member count. The record carries no secret by construction.
fn room_json(record: &RoomRecord) -> anyhow::Result<serde_json::Value> {
    let mut value = serde_json::to_value(record)?;
    value["memberCount"] = record.book.members.len().into();
    Ok(value)
}

fn kind_name(kind: RoomKind) -> &'static str {
    match kind {
        RoomKind::Room => "room",
        RoomKind::Fleet => "fleet",
    }
}

/// Room administration works directly on the pairing-domain store, so it
/// runs while an engine owns the data directory (WAL coordinates the two
/// processes) — the same boundary `roboco engine pairing` uses. Nothing here
/// reads or writes remote-access settings.
fn room(command: RoomCommand, data_dir: &std::path::Path) -> anyhow::Result<()> {
    let store = RoomStore::open(data_dir)?;
    match command {
        RoomCommand::Create { name, label, json } => {
            // The engine records the address it publishes over Tailcat; with
            // none recorded the fleet starts without this engine's entry.
            let members = match read_recorded_address(data_dir)? {
                Some(address) => vec![RoomMember {
                    label: label
                        .map(|label| label.trim().to_owned())
                        .unwrap_or_else(|| roboco_engine::local_device_name_at(data_dir)),
                    address,
                }],
                None => Vec::new(),
            };
            let seeded = !members.is_empty();
            let record = store.create(&name, members)?;
            if json {
                println!("{}", room_json(&record)?);
            } else {
                println!(
                    "Created fleet \"{}\" ({} members). Room id: {}",
                    record.label,
                    record.book.members.len(),
                    record.id
                );
                println!(
                    "Share it with `roboco engine room invite {}` — the invite is a secret.",
                    record.id
                );
            }
            if !seeded {
                eprintln!(
                    "No Tailcat address is recorded for this engine, so the fleet's book starts without it."
                );
            }
        }
        RoomCommand::Invite { room_id, qr, json } => {
            let invite = store
                .fleet_invite(&room_id)?
                .ok_or(RoomRefusal("not a fleet room"))?;
            let encoded = invite.encode()?;
            drop(invite);
            if json {
                let mut output = serde_json::json!({"roomId": room_id, "invite": encoded});
                if qr {
                    output["qrSvg"] = roboco_proto::qr::try_qr_svg(&encoded).into();
                }
                println!("{output}");
            } else {
                println!("{encoded}");
                if qr {
                    match roboco_proto::qr::try_qr_terminal(&encoded) {
                        Some(code) => println!("{code}"),
                        None => {
                            eprintln!("This invite is too long for a QR code; paste it instead.")
                        }
                    }
                }
                println!(
                    "This invite is a secret: anyone holding it can join the fleet. Share it only with your own devices."
                );
            }
        }
        RoomCommand::Join { invite, json } => {
            // Decode errors describe only the invite's shape (ADR 0008);
            // nothing here prints the invite or its secret.
            let invite = RoomInvite::decode(&invite)?;
            let record = store.join(invite)?;
            if json {
                println!("{}", room_json(&record)?);
            } else {
                println!(
                    "Joined \"{}\" ({} members). Room id: {}",
                    record.label,
                    record.book.members.len(),
                    record.id
                );
                println!(
                    "Clients that present this room's secret mint ordinary sessions; leave with `roboco engine room leave {}`.",
                    record.id
                );
            }
        }
        RoomCommand::List { json } => {
            let rooms = store.list()?;
            if json {
                let rooms = rooms
                    .iter()
                    .map(room_json)
                    .collect::<anyhow::Result<Vec<_>>>()?;
                println!("{}", serde_json::Value::Array(rooms));
            } else if rooms.is_empty() {
                println!("No joined rooms.");
            } else {
                for room in rooms {
                    println!(
                        "{}\t{}\t{} members\t{}",
                        room.id,
                        room.label,
                        room.book.members.len(),
                        kind_name(room.kind)
                    );
                }
            }
        }
        RoomCommand::Leave { room_id, json } => {
            if !store.leave(&room_id)? {
                return Err(RoomRefusal("no such room").into());
            }
            if json {
                println!("{}", serde_json::json!({"roomId": room_id, "left": true}));
            } else {
                println!("Left {room_id}");
            }
        }
    }
    Ok(())
}

/// Refusals ([`RoomRefusal`], [`FleetConflict`]) exit with
/// [`REFUSED_EXIT_CODE`] and their fixed text; every other error keeps the
/// default `Error:` report and exit code 1.
fn exit_on_refusal(error: anyhow::Error) -> anyhow::Result<()> {
    if error.downcast_ref::<RoomRefusal>().is_some()
        || error.downcast_ref::<FleetConflict>().is_some()
    {
        eprintln!("Error: {error}");
        std::process::exit(REFUSED_EXIT_CODE);
    }
    Err(error)
}

pub fn run(command: EngineCommand, data_dir: &std::path::Path) -> anyhow::Result<()> {
    let command = match command {
        EngineCommand::Tailcat { command } => return tailcat(command, data_dir),
        EngineCommand::Room { command } => {
            return room(command, data_dir).or_else(exit_on_refusal);
        }
        EngineCommand::Pairing { command } => command,
    };
    let store = PairingStore::open(data_dir)?;
    match command {
        PairingCommand::Create {
            base_url,
            label,
            ttl_seconds,
            qr,
            json,
        } => {
            // Validate the address before issuing a credential.
            pairing_url(&base_url, "")?;
            let code = store.create_code(&label, ttl_seconds)?;
            let url = pairing_url(&base_url, &code.credential)?;
            if json {
                // `qrSvg` joins only on --qr, so existing JSON consumers see
                // byte-identical output without it.
                if qr {
                    println!(
                        "{}",
                        serde_json::json!({"id":code.id,"url":url,"expiresAt":code.expires_at,"qrSvg":roboco_proto::qr::qr_svg(&url)})
                    );
                } else {
                    println!(
                        "{}",
                        serde_json::json!({"id":code.id,"url":url,"expiresAt":code.expires_at})
                    );
                }
            } else {
                println!("{url}");
                // The half-block QR scans from the terminal itself — phone
                // cameras read it straight off the screen.
                if qr {
                    println!("{}", roboco_proto::qr::qr_terminal(&url));
                }
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

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[derive(Parser)]
    struct Cli {
        #[command(subcommand)]
        command: RoomCommand,
    }

    fn parse(args: &[&str]) -> RoomCommand {
        Cli::try_parse_from(std::iter::once("room").chain(args.iter().copied()))
            .unwrap()
            .command
    }

    /// Room ids are base64url, so about one in 64 starts with `-`: those must
    /// still parse as the id, while `--json` stays a flag on either side.
    #[test]
    fn room_ids_may_begin_with_a_hyphen() {
        for args in [&["leave", "-Ab_c", "--json"][..], &["leave", "--json", "-Ab_c"]] {
            let RoomCommand::Leave { room_id, json } = parse(args) else {
                panic!("not a leave");
            };
            assert_eq!((room_id.as_str(), json), ("-Ab_c", true), "{args:?}");
        }
        let RoomCommand::Invite { room_id, qr, json } = parse(&["invite", "-x9", "--qr"]) else {
            panic!("not an invite");
        };
        assert_eq!((room_id.as_str(), qr, json), ("-x9", true, false));
    }
}
