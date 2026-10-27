//! The room CLI works against a running engine, like the pairing CLI: the
//! subprocess creates, shares, joins, lists, and leaves rooms through the
//! pairing-domain store while the engine owns its instance lock, and
//! presentations mint ordinary sessions over HTTP — without ever mutating
//! remote-access settings.
//!
//! Every CLI call runs with `RUST_LOG=trace`, so its logs land in the
//! captured stdout/stderr; the in-process engine logs into a captured buffer.
//! Secrets may appear only on the stdout of `room invite`.
use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry};
use roboco_proto::room::{RoomInvite, RoomMember, mint_room_secret};
use roboco_proto::tailcat::INVITE_PREFIX as TAILCAT_INVITE_PREFIX;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::process::Output;
use std::sync::{Arc, Mutex, OnceLock};

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";
/// The Tailcat address the "running engine" recorded for the pairing CLI.
const SELF_ADDRESS: &str = "tcSELFADDRESSrecordedByTheEngine_1";
const FLEET_EXISTS: &str = "this engine already belongs to a fleet; leave it first";
const REFUSED: i32 = 3;

/// Every CLI output of one test, so the secret sweep sees all of it.
#[derive(Default)]
struct Transcript {
    /// (`room` subcommand, stdout, stderr)
    calls: Vec<(String, String, String)>,
}

impl Transcript {
    fn run(&mut self, dir: &Path, arguments: &[&str]) -> Output {
        let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
            .env("ROBOCO_DATA_DIR", dir)
            .env("RUST_LOG", "trace")
            .args(["engine", "room"])
            .args(arguments)
            .output()
            .unwrap();
        self.calls.push((
            arguments.first().copied().unwrap_or_default().to_owned(),
            String::from_utf8_lossy(&output.stdout).into_owned(),
            String::from_utf8_lossy(&output.stderr).into_owned(),
        ));
        output
    }

    /// Run and require success; returns stdout.
    fn ok(&mut self, dir: &Path, arguments: &[&str]) -> String {
        let output = self.run(dir, arguments);
        assert!(
            output.status.success(),
            "CLI {arguments:?} failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8(output.stdout).unwrap()
    }

    /// Run and require a refusal (exit 3); returns stderr.
    fn refused(&mut self, dir: &Path, arguments: &[&str]) -> String {
        let output = self.run(dir, arguments);
        assert_eq!(
            output.status.code(),
            Some(REFUSED),
            "CLI {:?} should be refused: {}",
            arguments.first(),
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8(output.stderr).unwrap()
    }

    /// No secret on any stream, except the stdout of `room invite`.
    fn assert_no_secret(&self, secrets: &[&str]) {
        assert!(!self.calls.is_empty());
        for (command, stdout, stderr) in &self.calls {
            for secret in secrets {
                assert!(
                    !stderr.contains(secret),
                    "a room secret reached stderr of `room {command}`"
                );
                if command != "invite" {
                    assert!(
                        !stdout.contains(secret),
                        "a room secret reached stdout of `room {command}`"
                    );
                }
            }
        }
    }
}

/// The one JSON document on stdout (trace logs, if any, are other lines).
fn json_line(stdout: &str) -> Value {
    let lines: Vec<&str> = stdout
        .lines()
        .filter(|line| line.starts_with('{') || line.starts_with('['))
        .collect();
    assert_eq!(lines.len(), 1, "expected one JSON line in {stdout:?}");
    serde_json::from_str(lines[0]).unwrap()
}

/// Engine logs from this test binary, at trace level.
#[derive(Clone, Default)]
struct Captured(Arc<Mutex<Vec<u8>>>);

impl std::io::Write for Captured {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn engine_logs() -> Captured {
    static LOGS: OnceLock<Captured> = OnceLock::new();
    LOGS.get_or_init(|| {
        let captured = Captured::default();
        let writer = captured.clone();
        let subscriber = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::TRACE)
            .with_ansi(false)
            .with_writer(move || writer.clone())
            .finish();
        tracing::subscriber::set_global_default(subscriber).unwrap();
        captured
    })
    .clone()
}

fn assert_logs_clean(logs: &Captured, secrets: &[&str]) {
    let logs = String::from_utf8_lossy(&logs.0.lock().unwrap()).into_owned();
    // The sweep means something only if the engine's trace output arrived.
    assert!(
        logs.contains("roboco_engine"),
        "no engine log was captured"
    );
    for secret in secrets {
        assert!(
            !logs.contains(secret),
            "a room secret reached the engine log"
        );
    }
}

fn plain_invite() -> String {
    RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![
            RoomMember {
                label: "desktop".into(),
                address: "tcDESKTOPADDRESS1".into(),
            },
            RoomMember {
                label: "server".into(),
                address: "tcSERVERADDRESS1".into(),
            },
        ],
    )
    .unwrap()
    .encode()
    .unwrap()
}

/// A saved remote-access configuration whose bytes must never change: the
/// room surface works on already-reachable engines, never on settings (C4a).
fn write_settings(dir: &Path) -> (PathBuf, Vec<u8>) {
    let settings = dir.join("remote-access.json");
    std::fs::write(
        &settings,
        br#"{"enabled":false,"bindAddress":"0.0.0.0:27655","publicUrl":null,"transport":"network"}"#,
    )
    .unwrap();
    let bytes = std::fs::read(&settings).unwrap();
    (settings, bytes)
}

async fn start_engine(dir: &Path) -> (EngineCore, roboco_engine::EngineListener, String) {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let remote =
        roboco_engine::serve_engine_remote("127.0.0.1:0".parse().unwrap(), core.rpc_service(), dir)
            .await
            .unwrap();
    let base = format!("http://{}", remote.address);
    (core, remote, base)
}

/// Present `secret` at `/room/present` and open a session with the grant.
async fn present(base: &str, secret: &str) -> roboco_rpc::RpcClient {
    let grant: Value = reqwest::Client::new()
        .post(format!("{base}/room/present"))
        .json(&json!({ "secret": secret, "label": "CLI test" }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap();
    roboco_rpc::connect_ws_authenticated(&base.replacen("http", "ws", 1), credential)
        .await
        .unwrap()
}

#[tokio::test]
async fn the_cli_joins_lists_and_leaves_against_a_running_engine() {
    let logs = engine_logs();
    let dir = tempfile::tempdir().unwrap();
    let (settings, settings_before) = write_settings(dir.path());
    let (core, mut remote, base) = start_engine(dir.path()).await;
    let mut cli = Transcript::default();

    // Join: the invite is passed on the command line; the output carries the
    // room id and book — never the invite's secret.
    let record = json_line(&cli.ok(dir.path(), &["join", &plain_invite(), "--json"]));
    let room_id = record["id"].as_str().unwrap().to_owned();
    assert_eq!(record["label"], "Home fleet");
    assert_eq!(record["kind"], "room");
    assert_eq!(record["memberCount"], 2);
    assert_eq!(record["book"]["members"].as_array().unwrap().len(), 2);

    // The store-level result is visible to the running engine: presenting
    // over HTTP mints an ordinary session.
    let client = present(&base, SECRET).await;
    let info = client
        .call(roboco_rpc::methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    assert_eq!(info["deviceId"], core.device_id);

    // The minted session reads the book through the RPC with the secret.
    let book = client
        .call(
            roboco_rpc::methods::GET_ROOM_BOOK,
            json!({ "roomId": room_id, "secret": SECRET }),
        )
        .await
        .unwrap();
    assert_eq!(book["members"][0]["label"], "desktop");

    // List: id, label, kind, and member count — no secret.
    let rows = json_line(&cli.ok(dir.path(), &["list", "--json"]));
    assert_eq!(rows[0]["id"], room_id.as_str());
    assert_eq!(rows[0]["label"], "Home fleet");
    assert_eq!(rows[0]["kind"], "room");
    assert_eq!(rows[0]["memberCount"], 2);
    let plain = cli.ok(dir.path(), &["list"]);
    assert!(plain.contains(&room_id));
    assert!(plain.contains("Home fleet"));
    assert!(plain.contains("2 members"));

    // A re-join of the same invite is idempotent (self-heal), not a second row.
    cli.ok(dir.path(), &["join", &plain_invite()]);
    let rows = json_line(&cli.ok(dir.path(), &["list", "--json"]));
    assert_eq!(rows.as_array().unwrap().len(), 1);

    // A plain room has no invite to print: engines keep only its verifier.
    let refusal = cli.refused(dir.path(), &["invite", &room_id]);
    assert!(refusal.contains("not a fleet room"), "{refusal}");

    // Leave: the verifier is forgotten, presentations stop verifying, the
    // book file is gone.
    let left = json_line(&cli.ok(dir.path(), &["leave", &room_id, "--json"]));
    assert_eq!(left, json!({ "roomId": room_id, "left": true }));
    let response = reqwest::Client::new()
        .post(format!("{base}/room/present"))
        .json(&json!({ "secret": SECRET, "label": "CLI test" }))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 401);
    assert!(!dir.path().join("rooms").join(&room_id).exists());
    assert_eq!(cli.ok(dir.path(), &["list"]), "No joined rooms.\n");
    assert_eq!(
        json_line(&cli.ok(dir.path(), &["list", "--json"])),
        json!([])
    );
    // Leaving twice is a refusal with fixed text.
    let refusal = cli.refused(dir.path(), &["leave", &room_id]);
    assert!(refusal.contains("no such room"), "{refusal}");

    // Joining, presenting, and reading the book never touched the saved
    // remote-access settings (the CreateTailcatInvite anti-pattern).
    assert_eq!(std::fs::read(&settings).unwrap(), settings_before);
    cli.assert_no_secret(&[SECRET]);

    core.shutdown().await;
    remote.stop().await;
    // Drop the engine before the disk walk: the live pairing store holds
    // sqlite WAL/SHM byte-range locks, and Windows refuses concurrent reads
    // of a locked region with a sharing violation (Linux tolerates them, so
    // the walk only ever tripped on the Windows runners).
    drop(client);
    drop(core);
    drop(remote);
    assert_logs_clean(&logs, &[SECRET]);

    // Nothing on disk carries the secret.
    for entry in walk(dir.path()) {
        let bytes = std::fs::read(&entry).unwrap();
        assert!(
            !contains(&bytes, SECRET),
            "the room secret leaked into {}",
            entry.display()
        );
    }
}

#[tokio::test]
async fn the_cli_creates_and_shares_a_fleet_against_a_running_engine() {
    let logs = engine_logs();
    let dir = tempfile::tempdir().unwrap();
    let (settings, settings_before) = write_settings(dir.path());
    // The address a Tailcat-serving engine records for the pairing CLI.
    roboco_engine::tailcat::record_address(dir.path(), SELF_ADDRESS).unwrap();
    let (core, mut remote, base) = start_engine(dir.path()).await;
    let mut cli = Transcript::default();

    // Create: the id (and the book seeded with this engine) — no secret.
    let created = json_line(&cli.ok(
        dir.path(),
        &[
            "create",
            "--name",
            "Home fleet",
            "--label",
            "desktop",
            "--json",
        ],
    ));
    let fleet_id = created["id"].as_str().unwrap().to_owned();
    assert_eq!(created["label"], "Home fleet");
    assert_eq!(created["kind"], "fleet");
    assert_eq!(created["memberCount"], 1);
    assert_eq!(
        created["book"]["members"],
        json!([{ "label": "desktop", "address": SELF_ADDRESS }])
    );
    let text = cli.ok(dir.path(), &["list"]);
    assert!(text.contains(&fleet_id), "{text}");
    assert!(text.contains("1 members\tfleet"), "{text}");

    // Invite: the only command whose stdout carries the invite.
    let shared = json_line(&cli.ok(dir.path(), &["invite", &fleet_id, "--json"]));
    assert_eq!(shared["roomId"], fleet_id.as_str());
    assert!(
        shared.get("qrSvg").is_none(),
        "qrSvg appears only with --qr"
    );
    let encoded = shared["invite"].as_str().unwrap().to_owned();
    let invite = RoomInvite::decode(&encoded).unwrap();
    assert!(invite.is_fleet());
    assert_eq!(invite.room_id(), fleet_id);
    assert_eq!(invite.name, "Home fleet");
    assert_eq!(invite.members.len(), 1);
    assert_eq!(invite.members[0].address, SELF_ADDRESS);
    let fleet_secret = invite.secret.clone();

    let with_qr = json_line(&cli.ok(dir.path(), &["invite", &fleet_id, "--qr", "--json"]));
    assert_eq!(with_qr["invite"], encoded.as_str());
    assert!(with_qr["qrSvg"].as_str().unwrap().contains("<svg"));
    let text = cli.ok(dir.path(), &["invite", &fleet_id, "--qr"]);
    let mut lines = text.lines();
    assert_eq!(lines.next(), Some(encoded.as_str()));
    assert!(
        text.contains('█') || text.contains('▀') || text.contains('▄'),
        "no terminal QR under the invite: {text}"
    );

    // The running engine sees the CLI's fleet: the secret presents, and the
    // session's GetFleetInvite serializes the same room.
    let client = present(&base, &fleet_secret).await;
    let fetched = client
        .call(roboco_rpc::methods::GET_FLEET_INVITE, json!({}))
        .await
        .unwrap();
    let fetched = RoomInvite::decode(fetched["invite"].as_str().unwrap()).unwrap();
    assert_eq!(fetched.room_id(), fleet_id);

    // One fleet per data dir: a second create and a different fleet's join
    // are refused with fixed text and exit 3.
    let refusal = cli.refused(dir.path(), &["create", "--name", "Second"]);
    assert!(refusal.contains(FLEET_EXISTS), "{refusal}");
    let other_secret = mint_room_secret().unwrap();
    let other = RoomInvite::fleet("Other fleet", &other_secret, Vec::new())
        .unwrap()
        .encode()
        .unwrap();
    let refusal = cli.refused(dir.path(), &["join", &other, "--json"]);
    assert!(refusal.contains(FLEET_EXISTS), "{refusal}");

    // Re-joining the fleet's own invite (a member pushing a bigger book) is
    // idempotent: one row, the book unioned.
    let grown = RoomInvite::fleet(
        "Home fleet",
        &fleet_secret,
        vec![RoomMember {
            label: "server".into(),
            address: "tcSERVERADDRESS1".into(),
        }],
    )
    .unwrap()
    .encode()
    .unwrap();
    let rejoined = json_line(&cli.ok(dir.path(), &["join", &grown, "--json"]));
    assert_eq!(rejoined["id"], fleet_id.as_str());
    assert_eq!(rejoined["kind"], "fleet");
    assert_eq!(rejoined["memberCount"], 2);
    cli.ok(dir.path(), &["join", &grown]);

    // A plain room alongside the fleet: listed with its kind, refused by
    // `invite`; unknown ids are refused the same way.
    let plain = json_line(&cli.ok(dir.path(), &["join", &plain_invite(), "--json"]));
    let plain_id = plain["id"].as_str().unwrap().to_owned();
    let rows = json_line(&cli.ok(dir.path(), &["list", "--json"]));
    let rows = rows.as_array().unwrap();
    assert_eq!(rows.len(), 2, "re-joins never add rows: {rows:?}");
    let fleet_row = rows
        .iter()
        .find(|row| row["id"] == fleet_id.as_str())
        .unwrap();
    assert_eq!(fleet_row["kind"], "fleet");
    assert_eq!(fleet_row["memberCount"], 2);
    let plain_row = rows
        .iter()
        .find(|row| row["id"] == plain_id.as_str())
        .unwrap();
    assert_eq!(plain_row["kind"], "room");
    assert_eq!(plain_row["memberCount"], 2);
    for id in [plain_id.as_str(), "no-such-room"] {
        let refusal = cli.refused(dir.path(), &["invite", id, "--qr", "--json"]);
        assert!(refusal.contains("not a fleet room"), "{refusal}");
    }

    // An invite pasted where a room id belongs is refused without echo.
    let refusal = cli.refused(dir.path(), &["leave", &encoded]);
    assert!(refusal.contains("no such room"), "{refusal}");

    // Leaving the fleet frees the data dir for a new one.
    assert_eq!(
        json_line(&cli.ok(dir.path(), &["leave", &fleet_id, "--json"])),
        json!({ "roomId": fleet_id, "left": true })
    );
    let recreated = json_line(&cli.ok(dir.path(), &["create", "--label", "desktop", "--json"]));
    let second_id = recreated["id"].as_str().unwrap().to_owned();
    assert_ne!(second_id, fleet_id);
    assert_eq!(recreated["label"], "Fleet", "the default fleet name");
    let second = json_line(&cli.ok(dir.path(), &["invite", &second_id, "--json"]));
    let second_secret = RoomInvite::decode(second["invite"].as_str().unwrap())
        .unwrap()
        .secret;

    // C4a: no room command touched the saved remote-access settings.
    assert_eq!(std::fs::read(&settings).unwrap(), settings_before);
    let secrets = [
        fleet_secret.as_str(),
        second_secret.as_str(),
        other_secret.as_str(),
        SECRET,
    ];
    cli.assert_no_secret(&secrets);

    core.shutdown().await;
    remote.stop().await;
    drop(client);
    drop(core);
    drop(remote);
    assert_logs_clean(&logs, &secrets);

    // At rest: fleet secrets only inside the owner-only pairing database;
    // the plain room's secret and the refused fleet's secret nowhere.
    for entry in walk(dir.path()) {
        let bytes = std::fs::read(&entry).unwrap();
        let name = entry.file_name().unwrap().to_string_lossy().into_owned();
        for secret in [other_secret.as_str(), SECRET] {
            assert!(!contains(&bytes, secret), "a secret leaked into {name}");
        }
        if !name.starts_with("pairing.sqlite3") {
            for secret in [fleet_secret.as_str(), second_secret.as_str()] {
                assert!(
                    !contains(&bytes, secret),
                    "a fleet secret leaked into {name}"
                );
            }
        }
    }
}

#[test]
fn create_seeds_the_book_only_from_a_recorded_address() {
    let mut cli = Transcript::default();

    // No recorded address: the fleet starts without this engine, and says so.
    let bare = tempfile::tempdir().unwrap();
    let output = cli.run(bare.path(), &["create", "--json"]);
    assert!(output.status.success());
    let created = json_line(&String::from_utf8(output.stdout).unwrap());
    assert_eq!(created["kind"], "fleet");
    assert_eq!(created["memberCount"], 0);
    let stderr = String::from_utf8(output.stderr).unwrap();
    assert!(
        stderr.contains("No Tailcat address is recorded"),
        "{stderr}"
    );

    // A recorded address without --label: this engine's device name labels it.
    let dir = tempfile::tempdir().unwrap();
    roboco_engine::tailcat::record_address(dir.path(), SELF_ADDRESS).unwrap();
    let created = json_line(&cli.ok(dir.path(), &["create", "--name", "Lab", "--json"]));
    assert_eq!(created["label"], "Lab");
    assert_eq!(
        created["book"]["members"],
        json!([{
            "label": roboco_engine::local_device_name_at(dir.path()),
            "address": SELF_ADDRESS,
        }])
    );
    let text = cli.refused(dir.path(), &["create"]);
    assert!(text.contains(FLEET_EXISTS), "{text}");
}

#[test]
fn every_room_command_documents_its_json_shape_and_exit_codes() {
    let dir = tempfile::tempdir().unwrap();
    let help = |command: &str| {
        let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
            .env("ROBOCO_DATA_DIR", dir.path())
            .args(["engine", "room", command, "--help"])
            .output()
            .unwrap();
        assert!(output.status.success());
        let text = String::from_utf8(output.stdout).unwrap();
        assert!(text.contains("Exit codes:"), "{command}: {text}");
        assert!(text.contains("3  refused"), "{command}: {text}");
        assert!(text.contains("--json"), "{command}: {text}");
        text
    };
    let room_object = r#""memberCount":<n>"#;
    assert!(help("create").contains(room_object));
    assert!(help("join").contains(room_object));
    assert!(help("list").contains(room_object));
    let invite = help("invite");
    assert!(invite.contains(r#"{"roomId":"<room id>","invite":"roboco-room:…"}"#));
    assert!(invite.contains(r#""qrSvg""#));
    assert!(help("leave").contains(r#"{"roomId":"<room id>","left":true}"#));
}

#[test]
fn a_malformed_invite_is_refused_with_a_shape_only_error() {
    let dir = tempfile::tempdir().unwrap();
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir.path())
        .args(["engine", "room", "join", "roboco-room:not-an-invite"])
        .output()
        .unwrap();
    assert_eq!(
        output.status.code(),
        Some(1),
        "malformed input is a failure"
    );
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    assert!(stderr.contains("room invite"), "{stderr}");
    // The error never echoes the input.
    assert!(!stderr.contains("not-an-invite"), "{stderr}");
    // A different grammar's invite says so by name.
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir.path())
        .args([
            "engine",
            "room",
            "join",
            &format!("{TAILCAT_INVITE_PREFIX}anything"),
        ])
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    assert!(stderr.contains("roboco-room:"), "{stderr}");
    assert!(!stderr.contains("anything"), "{stderr}");
}

fn contains(bytes: &[u8], secret: &str) -> bool {
    bytes.windows(secret.len()).any(|w| w == secret.as_bytes())
}

fn walk(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else {
                out.push(path);
            }
        }
    }
    out
}
