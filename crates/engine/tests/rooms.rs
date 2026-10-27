//! Rooms through the real engine listener: presentations over HTTP, the book
//! RPCs over an authenticated session, and the pinned regression that room
//! code never mutates remote-access settings. Independent SQLite connections
//! stand in for the CLI (the pairing test's coordination-free boundary).
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry,
    listener::serve_listener,
    pairing::PairingStore,
    rooms::{PresentationOutcome, RoomStore},
};
use roboco_proto::room::{RoomInvite, RoomMember};
use serde_json::{Value, json};
use std::{path::Path, sync::Arc, time::Duration};

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

struct Fixture {
    core: EngineCore,
    /// The local (pre-session) listener base: presentations go here.
    local_base: String,
    local_task: tokio::task::JoinHandle<()>,
    /// The paired listener: the minted session authenticates here.
    remote: roboco_engine::EngineListener,
}

async fn engine(dir: &Path) -> Fixture {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let local_base = format!("http://{}", listener.local_addr().unwrap());
    let local_task = tokio::spawn(serve_listener(
        listener,
        core.rpc_service(),
        PairingStore::open(dir).unwrap(),
        RoomStore::open(dir).unwrap(),
    ));
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir,
    )
    .await
    .unwrap();
    Fixture {
        core,
        local_base,
        local_task,
        remote,
    }
}

fn invite(members: Vec<RoomMember>) -> RoomInvite {
    RoomInvite::new(
        "Home fleet",
        SECRET,
        members,
    )
    .unwrap()
}

fn member(label: &str, address: &str) -> RoomMember {
    RoomMember {
        label: label.into(),
        address: address.into(),
    }
}

async fn present(http: &reqwest::Client, base: &str, secret: &str, label: &str) -> reqwest::Response {
    http.post(format!("{base}/room/present"))
        .json(&json!({ "secret": secret, "label": label }))
        .send()
        .await
        .unwrap()
}

/// The room secret is a secret: nothing on disk, nothing in a reply, and
/// nothing in pairing.sqlite3 (WAL sidecars included) may contain it.
fn assert_secret_never_at_rest(dir: &Path) {
    fn walk(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
        for entry in std::fs::read_dir(dir).unwrap().flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk(&path, out);
            } else {
                out.push(path);
            }
        }
    }
    let mut files = Vec::new();
    walk(dir, &mut files);
    for path in files {
        let bytes = std::fs::read(&path).unwrap();
        assert!(
            !bytes.windows(SECRET.len()).any(|w| w == SECRET.as_bytes()),
            "the room secret leaked into {}",
            path.display()
        );
    }
}

#[tokio::test]
async fn presentation_mints_an_ordinary_session_and_never_touches_settings() {
    let dir = tempfile::tempdir().unwrap();
    // A saved remote-access configuration, remote off: joining a room must
    // never flip it — the anti-pattern is CreateTailcatInvite, which
    // force-enables and persists Tailcat (remote_access.rs). A room only
    // works on engines that are already reachable.
    let settings = dir.path().join("remote-access.json");
    std::fs::write(
        &settings,
        br#"{"enabled":false,"bindAddress":"0.0.0.0:27655","publicUrl":null,"transport":"network"}"#,
    )
    .unwrap();
    let settings_before = std::fs::read(&settings).unwrap();

    let fixture = engine(dir.path()).await;
    // A second process (the CLI's boundary) joins the room while the engine runs.
    let store = RoomStore::open(dir.path()).unwrap();
    let record = store
        .join(invite(vec![member("desktop", "tcDESKTOP1"), member("server", "tcSERVER1")]))
        .unwrap();

    let http = reqwest::Client::new();
    let response = present(&http, &fixture.local_base, SECRET, "Pixel").await;
    assert_eq!(response.status(), 200);
    let grant: Value = response.json().await.unwrap();
    let credential = grant["credential"].as_str().unwrap().to_owned();
    let session_id = grant["session"]["id"].as_str().unwrap().to_owned();
    assert_eq!(grant["session"]["label"], "Pixel");
    // The reply is a pairing grant: no room secret rides in it.
    assert!(!serde_json::to_string(&grant).unwrap().contains(SECRET));

    // The minted session is ordinary: it authenticates the paired WebSocket
    // boundary like a pair-code session, and GET_REMOTE_ACCESS lists it.
    let remote_url = format!("ws://{}", fixture.remote.address);
    let client = roboco_rpc::connect_ws_authenticated(&remote_url, &credential)
        .await
        .unwrap();
    let info = client
        .call(roboco_rpc::methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    assert_eq!(info["deviceId"], fixture.core.device_id);
    assert!(info["capabilities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|c| c == "rooms-v1"));
    let remote_access = client
        .call(roboco_rpc::methods::GET_REMOTE_ACCESS, json!({}))
        .await
        .unwrap();
    assert!(remote_access["sessions"]
        .as_array()
        .unwrap()
        .iter()
        .any(|s| s["id"] == session_id.as_str() && s["label"] == "Pixel"));

    // Revocation refuses its next connection: eviction's second step.
    assert!(PairingStore::open(dir.path()).unwrap().revoke(&session_id).unwrap());
    drop(client);
    assert!(
        roboco_rpc::connect_ws_authenticated(&remote_url, &credential)
            .await
            .is_err()
    );

    // Nothing on disk carries the secret, and the settings file is
    // byte-identical: joining and presenting never mutate remote access.
    assert_secret_never_at_rest(dir.path());
    assert_eq!(std::fs::read(&settings).unwrap(), settings_before);

    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
    assert_eq!(
        RoomStore::open(dir.path()).unwrap().list().unwrap().len(),
        1
    );
    assert_eq!(
        record.book.members,
        vec![member("desktop", "tcDESKTOP1"), member("server", "tcSERVER1")]
    );
}

#[tokio::test]
async fn re_presenting_is_idempotent_over_the_wire() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = engine(dir.path()).await;
    RoomStore::open(dir.path())
        .unwrap()
        .join(invite(vec![member("desktop", "tcDESKTOP1")]))
        .unwrap();

    let http = reqwest::Client::new();
    let first = present(&http, &fixture.local_base, SECRET, "Pixel").await;
    assert_eq!(first.status(), 200);
    let first: Value = first.json().await.unwrap();
    // A retry (interrupted join, reconnect self-heal) lands on the same
    // session row with a refreshed credential, not a second session.
    let second = present(&http, &fixture.local_base, SECRET, "Pixel").await;
    assert_eq!(second.status(), 200);
    let second: Value = second.json().await.unwrap();
    assert_eq!(first["session"]["id"], second["session"]["id"]);
    let sessions: Vec<Value> = PairingStore::open(dir.path())
        .unwrap()
        .list_sessions()
        .unwrap()
        .iter()
        .filter(|s| s.label == "Pixel")
        .map(|s| serde_json::to_value(s).unwrap())
        .collect();
    assert_eq!(sessions.len(), 1);
    // The refreshed credential authenticates; the superseded one does not.
    let remote_url = format!("ws://{}", fixture.remote.address);
    roboco_rpc::connect_ws_authenticated(&remote_url, second["credential"].as_str().unwrap())
        .await
        .unwrap();
    assert!(
        roboco_rpc::connect_ws_authenticated(&remote_url, first["credential"].as_str().unwrap())
            .await
            .is_err()
    );

    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
}

#[tokio::test]
async fn failed_presentations_are_throttled_and_shape_only() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = engine(dir.path()).await;
    RoomStore::open(dir.path())
        .unwrap()
        .join(invite(vec![]))
        .unwrap();

    let http = reqwest::Client::new();
    // Shape errors are 400 and describe only the body's shape.
    let bad = http
        .post(format!("{}/room/present", fixture.local_base))
        .json(&json!({"label": "Pixel"}))
        .send()
        .await
        .unwrap();
    assert_eq!(bad.status(), 400);
    let bad = http
        .post(format!("{}/room/present", fixture.local_base))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(bad.status(), 400);
    let unlabeled = present(&http, &fixture.local_base, SECRET, "  ").await;
    assert_eq!(unlabeled.status(), 400);
    // The correct secret with a bad label never echoes the secret.
    let refused = present(&http, &fixture.local_base, "not-the-secret", "Pixel").await;
    assert_eq!(refused.status(), 401);
    let body = refused.text().await.unwrap();
    assert!(!body.contains("not-the-secret"));

    // The throttle: five refusals, then the next failure answers 429.
    for _ in 0..4 {
        assert_eq!(present(&http, &fixture.local_base, "wrong", "Pixel").await.status(), 401);
    }
    let throttled = present(&http, &fixture.local_base, "wrong", "Pixel").await;
    assert_eq!(throttled.status(), 429);
    // A member holding the real secret is still welcomed — the secret is
    // verified before the failure window is consulted.
    let member = present(&http, &fixture.local_base, SECRET, "Pixel").await;
    assert_eq!(member.status(), 200);

    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
}

#[tokio::test]
async fn the_book_rpcs_gate_on_the_secret_and_union_merge() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = engine(dir.path()).await;
    let store = RoomStore::open(dir.path()).unwrap();
    let record = store
        .join(invite(vec![member("desktop", "tcDESKTOP1")]))
        .unwrap();
    let room_id = record.id.clone();

    let http = reqwest::Client::new();
    let grant: Value = present(&http, &fixture.local_base, SECRET, "Pixel")
        .await
        .json()
        .await
        .unwrap();
    let credential = grant["credential"].as_str().unwrap().to_owned();

    let remote_url = format!("ws://{}", fixture.remote.address);
    let client = roboco_rpc::connect_ws_authenticated(&remote_url, &credential)
        .await
        .unwrap();

    // GetRoomBook demands the secret in addition to the session: member
    // addresses are transport secrets.
    let book = client
        .call(
            roboco_rpc::methods::GET_ROOM_BOOK,
            json!({ "roomId": room_id, "secret": SECRET }),
        )
        .await
        .unwrap();
    assert_eq!(book["members"][0]["label"], "desktop");
    assert_eq!(book["members"][0]["address"], "tcDESKTOP1");
    // A session alone is not enough — the wrong secret is refused without
    // revealing whether the room exists.
    let wrong = client
        .call(
            roboco_rpc::methods::GET_ROOM_BOOK,
            json!({ "roomId": room_id, "secret": "wrong-secret" }),
        )
        .await
        .unwrap_err();
    assert!(wrong.to_string().contains("presented secret was refused"));
    let unknown = client
        .call(
            roboco_rpc::methods::GET_ROOM_BOOK,
            json!({ "roomId": "no-such-room", "secret": SECRET }),
        )
        .await
        .unwrap_err();
    assert!(unknown.to_string().contains("room not found"));

    // UpdateRoomBook union-merges and replies with the fresh book.
    let merged = client
        .call(
            roboco_rpc::methods::UPDATE_ROOM_BOOK,
            json!({ "roomId": room_id, "members": [
                { "label": "server", "address": "tcSERVER1" },
                { "label": "desktop", "address": "tcDESKTOP1" },
            ]}),
        )
        .await
        .unwrap();
    assert_eq!(merged["members"].as_array().unwrap().len(), 2);
    assert_eq!(merged["members"][0]["label"], "desktop");
    assert_eq!(merged["members"][1]["label"], "server");
    // The merged book persisted (a fresh store reads it) and a re-read
    // through the RPC sees the same union.
    assert_eq!(
        store.book_verified(&room_id, SECRET).unwrap().unwrap().members.len(),
        2
    );
    let book = client
        .call(
            roboco_rpc::methods::GET_ROOM_BOOK,
            json!({ "roomId": room_id, "secret": SECRET }),
        )
        .await
        .unwrap();
    assert_eq!(book["members"], merged["members"]);
    // An unknown room refuses the merge.
    let missing = client
        .call(
            roboco_rpc::methods::UPDATE_ROOM_BOOK,
            json!({ "roomId": "no-such-room", "members": [] }),
        )
        .await
        .unwrap_err();
    assert!(missing.to_string().contains("room not found"));

    // The store-level throttle and idempotency also hold through the RPC
    // surface's session minting: presenting again reuses the row.
    let again: Value = present(&http, &fixture.local_base, SECRET, "Pixel")
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(again["session"]["id"], grant["session"]["id"]);

    assert_secret_never_at_rest(dir.path());
    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
}

#[tokio::test]
async fn leaving_refuses_further_presentations() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = engine(dir.path()).await;
    let store = RoomStore::open(dir.path()).unwrap();
    let record = store.join(invite(vec![member("desktop", "tcDESKTOP1")])).unwrap();

    let http = reqwest::Client::new();
    assert_eq!(present(&http, &fixture.local_base, SECRET, "Pixel").await.status(), 200);
    assert!(store.leave(&record.id).unwrap());
    // The verifier is gone: the same secret is refused from now on.
    assert_eq!(present(&http, &fixture.local_base, SECRET, "Pixel").await.status(), 401);

    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
}

/// The store-level outcome enum keeps its contract across the surface:
/// refused and throttled are distinguishable without ever carrying the
/// presented secret.
#[tokio::test]
async fn refused_and_throttled_presentations_distinguish_through_the_store() {
    let dir = tempfile::tempdir().unwrap();
    let store = RoomStore::open(dir.path()).unwrap();
    store.join(invite(vec![])).unwrap();
    assert!(matches!(
        store.present("no-such-secret", "Pixel").unwrap(),
        PresentationOutcome::Refused
    ));
    for _ in 0..4 {
        assert!(matches!(
            store.present("no-such-secret", "Pixel").unwrap(),
            PresentationOutcome::Refused
        ));
    }
    assert!(matches!(
        store.present("no-such-secret", "Pixel").unwrap(),
        PresentationOutcome::Throttled
    ));
}

/// A quiet soak: presentations stay repeatable and the session table stays
/// bounded when a client retries in a loop (self-healing reconnects).
#[tokio::test]
async fn repeated_presentations_stay_bounded() {
    let dir = tempfile::tempdir().unwrap();
    let fixture = engine(dir.path()).await;
    RoomStore::open(dir.path()).unwrap().join(invite(vec![])).unwrap();
    let http = reqwest::Client::new();
    let mut first_id = None;
    for _ in 0..3 {
        let grant: Value = present(&http, &fixture.local_base, SECRET, "Pixel")
            .await
            .json()
            .await
            .unwrap();
        let id = grant["session"]["id"].as_str().unwrap().to_owned();
        if first_id.is_none() {
            first_id = Some(id);
        } else {
            assert_eq!(first_id.as_deref(), Some(id.as_str()));
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let active = PairingStore::open(dir.path())
        .unwrap()
        .list_sessions()
        .unwrap()
        .into_iter()
        .filter(|s| s.label == "Pixel" && s.revoked_at.is_none())
        .count();
    assert_eq!(active, 1);
    fixture.local_task.abort();
    drop(fixture.remote);
    fixture.core.shutdown().await;
}
