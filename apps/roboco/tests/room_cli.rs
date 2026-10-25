//! The room CLI works against a running engine, like the pairing CLI: the
//! subprocess joins, lists, and leaves rooms through the pairing-domain store
//! while the engine owns its instance lock, and presentations mint ordinary
//! sessions over HTTP — without ever mutating remote-access settings.
use roboco_engine::{EngineCore, EngineProfile, HarnessId, HarnessRegistry};
use roboco_proto::room::{RoomInvite, RoomMember};
use roboco_proto::tailcat::INVITE_PREFIX as TAILCAT_INVITE_PREFIX;
use serde_json::{Value, json};
use std::sync::Arc;

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

fn cli(dir: &std::path::Path, arguments: &[&str]) -> std::process::Output {
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir)
        .args(["engine", "room"])
        .args(arguments)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "CLI failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

fn invite() -> String {
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

#[tokio::test]
async fn the_cli_joins_lists_and_leaves_against_a_running_engine() {
    let dir = tempfile::tempdir().unwrap();
    // A saved remote-access configuration whose bytes must never change: the
    // room surface works on already-reachable engines, never on settings.
    let settings = dir.path().join("remote-access.json");
    std::fs::write(
        &settings,
        br#"{"enabled":false,"bindAddress":"0.0.0.0:27655","publicUrl":null,"transport":"network"}"#,
    )
    .unwrap();
    let settings_before = std::fs::read(&settings).unwrap();

    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let mut remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let base = format!("http://{}", remote.address);

    // Join: the invite is passed on the command line; the output carries the
    // room id and book — never the invite's secret.
    let joined = cli(dir.path(), &["join", &invite(), "--json"]);
    let stdout = String::from_utf8(joined.stdout).unwrap();
    assert!(!stdout.contains(SECRET), "the room secret was printed");
    let record: Value = serde_json::from_str(&stdout).unwrap();
    let room_id = record["id"].as_str().unwrap().to_owned();
    assert_eq!(record["label"], "Home fleet");
    assert_eq!(record["book"]["members"].as_array().unwrap().len(), 2);

    // The store-level result is visible to the running engine: presenting
    // over HTTP mints an ordinary session.
    let http = reqwest::Client::new();
    let grant: Value = http
        .post(format!("{base}/room/present"))
        .json(&json!({ "secret": SECRET, "label": "CLI test" }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap();
    let ws = base.replacen("http", "ws", 1);
    let client = roboco_rpc::connect_ws_authenticated(&ws, credential)
        .await
        .unwrap();
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

    // List: id, label, and member count — no secret.
    let listed = cli(dir.path(), &["list", "--json"]);
    let stdout = String::from_utf8(listed.stdout).unwrap();
    assert!(!stdout.contains(SECRET));
    let rows: Value = serde_json::from_str(&stdout).unwrap();
    assert_eq!(rows[0]["id"], room_id.as_str());
    assert_eq!(rows[0]["label"], "Home fleet");
    let plain = String::from_utf8(cli(dir.path(), &["list"]).stdout).unwrap();
    assert!(plain.contains(&room_id));
    assert!(plain.contains("Home fleet"));
    assert!(plain.contains("2 members"));

    // A re-join of the same invite is idempotent (self-heal), not a second row.
    cli(dir.path(), &["join", &invite()]);
    let rows: Value = serde_json::from_slice(&cli(dir.path(), &["list", "--json"]).stdout).unwrap();
    assert_eq!(rows.as_array().unwrap().len(), 1);

    // Leave: the verifier is forgotten, presentations stop verifying, the
    // book file is gone.
    cli(dir.path(), &["leave", &room_id]);
    let response = http
        .post(format!("{base}/room/present"))
        .json(&json!({ "secret": SECRET, "label": "CLI test" }))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 401);
    assert!(!dir.path().join("rooms").join(&room_id).exists());
    let listed = String::from_utf8(cli(dir.path(), &["list"]).stdout).unwrap();
    assert_eq!(listed, "No joined rooms.\n");

    // Joining, presenting, and reading the book never touched the saved
    // remote-access settings (the CreateTailcatInvite anti-pattern).
    assert_eq!(std::fs::read(&settings).unwrap(), settings_before);

    // Nothing on disk carries the secret.
    for entry in walk(dir.path()) {
        let bytes = std::fs::read(&entry).unwrap();
        assert!(
            !bytes.windows(SECRET.len()).any(|w| w == SECRET.as_bytes()),
            "the room secret leaked into {}",
            entry.display()
        );
    }

    core.shutdown().await;
    remote.stop().await;
}

#[test]
fn a_malformed_invite_is_refused_with_a_shape_only_error() {
    let dir = tempfile::tempdir().unwrap();
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir.path())
        .args(["engine", "room", "join", "roboco-room:not-an-invite"])
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    assert!(stderr.contains("room invite"), "{stderr}");
    // The error never echoes the input.
    assert!(!stderr.contains("not-an-invite"), "{stderr}");
    // A different grammar's invite says so by name.
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_roboco"))
        .env("ROBOCO_DATA_DIR", dir.path())
        .args(["engine", "room", "join", &format!("{TAILCAT_INVITE_PREFIX}anything")])
        .output()
        .unwrap();
    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    assert!(stderr.contains("roboco-room:"), "{stderr}");
    assert!(!stderr.contains("anything"), "{stderr}");
}

fn walk(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
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
