//! Rooms: client-side machinery against real engines over loopback —
//! presentations, per-member joins into the registry, book unions, and
//! best-effort revokes. The dialer is faked (member addresses map straight
//! to loopback listeners); production dials through tailcat forwarders,
//! which is the only piece these tests cannot exercise on this box.

use super::*;
use crate::registry::EngineConnectionState;
use crate::{FileSessionStore, RegistryOptions};
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry, pairing::PairingStore, rooms::RoomStore,
};
use roboco_proto::room::{RoomInvite, RoomMember};
use serde_json::json;
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

fn member(label: &str, address: &str) -> RoomMember {
    RoomMember {
        label: label.into(),
        address: address.into(),
    }
}

fn core(dir: &Path) -> EngineCore {
    EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(HarnessRegistry::new()),
        HarnessId::Mock,
    )
    .unwrap()
}

/// The test dialer: member addresses map to loopback bases and every dial is
/// recorded (skips and re-dials stay observable). Tests keep their own
/// `Arc<MapDialer>` handle for the record and pass the trait object onward.
struct MapDialer {
    bases: HashMap<String, String>,
    dialed: Mutex<Vec<String>>,
    count: AtomicUsize,
}

impl MapDialer {
    fn new(bases: HashMap<String, String>) -> Arc<Self> {
        Arc::new(Self {
            bases,
            dialed: Mutex::new(Vec::new()),
            count: AtomicUsize::new(0),
        })
    }

    fn dials(&self) -> Vec<String> {
        self.dialed.lock().unwrap().clone()
    }

    fn dial_count(&self) -> usize {
        self.count.load(Ordering::SeqCst)
    }
}

impl RoomDialer for MapDialer {
    fn dial(&self, address: &str, _data_dir: &Path) -> anyhow::Result<ResolvedMember> {
        self.count.fetch_add(1, Ordering::SeqCst);
        self.dialed.lock().unwrap().push(address.to_owned());
        let base = self
            .bases
            .get(address)
            .ok_or_else(|| anyhow::anyhow!("unknown member"))?
            .clone();
        Ok(ResolvedMember {
            base,
            keepalive: None,
        })
    }
}

/// A real member engine over loopback, plus the data dir its room store
/// (and pairing store) live in.
struct MemberEngine {
    core: EngineCore,
    listener: roboco_engine::EngineListener,
    dir: tempfile::TempDir,
}

impl MemberEngine {
    fn base(&self) -> String {
        format!("http://{}", self.listener.address)
    }

    /// The engine-side join a host performs (CLI or Settings): the store and
    /// the running listener coordinate through WAL, exactly as the CLI does
    /// against a running engine.
    fn join(&self, invite: &RoomInvite) {
        RoomStore::open(self.dir.path())
            .unwrap()
            .join(invite.clone())
            .unwrap();
    }
}

async fn member_engine() -> MemberEngine {
    let dir = tempfile::tempdir().unwrap();
    let core = core(dir.path());
    let listener = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    MemberEngine {
        core,
        listener,
        dir,
    }
}

async fn registry_with_local(local: EngineCore, ui_dir: &Path) -> EngineRegistry {
    let client = Arc::new(roboco_rpc::memory_client(local.rpc_service()));
    let info: roboco_proto::EngineInfo = client
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    EngineRegistry::open(ui_dir.join("paired-engines-v1.json"), info, client, None)
        .await
        .unwrap()
}

async fn wait_connected(registry: &EngineRegistry, engine_count: usize) {
    let mut changes = registry.watch();
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            let connected = changes
                .borrow_and_update()
                .engines
                .iter()
                .filter(|engine| engine.state == EngineConnectionState::Connected)
                .count();
            if connected >= engine_count {
                return;
            }
            changes.changed().await.unwrap();
        }
    })
    .await
    .expect("engines did not connect");
}

#[test]
fn minted_room_secrets_have_the_pairing_secret_shape() {
    let a = mint_room_secret().unwrap();
    let b = mint_room_secret().unwrap();
    // 32 bytes, base64url without padding: 43 characters.
    assert_eq!(a.len(), 43);
    assert!(
        a.bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    );
    assert_ne!(a, b, "every mint is fresh");
}

#[test]
fn rooms_file_round_trips_and_stays_owner_only() {
    let dir = tempfile::tempdir().unwrap();
    let file = RoomSecretFile::new(dir.path().join(ROOMS_FILE));
    // Nothing joined yet: a missing file is an empty set, not an error.
    assert!(file.load().unwrap().is_empty());
    let room = ClientRoom {
        id: "room-id-1".into(),
        name: "Home fleet".into(),
        secret: SECRET.into(),
        joined_at: 5,
        members: vec![member("server", "tcADDRESS1")],
        sessions: vec![RoomMemberSession {
            address: "tcADDRESS1".into(),
            engine_key: "engine-1".into(),
            session_id: "session-1".into(),
        }],
    };
    file.upsert(room.clone()).unwrap();
    assert_eq!(file.load().unwrap(), vec![room.clone()]);
    // Same id upserts in place; the file never grows a duplicate row.
    let mut refreshed = room.clone();
    refreshed.members.push(member("laptop", "tcADDRESS2"));
    file.upsert(refreshed.clone()).unwrap();
    assert_eq!(file.load().unwrap(), vec![refreshed]);

    // The file is 0600: the room secret is a usable secret at rest.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(dir.path().join(ROOMS_FILE))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600, "the rooms file must be owner-only");
    }

    // Removing: Some the first time, None the second.
    assert!(file.remove(&room.id).unwrap().is_some());
    assert!(file.load().unwrap().is_empty());
    assert!(file.remove(&room.id).unwrap().is_none());
    // remove_except retires everything but the kept ids (rotation).
    file.upsert(room.clone()).unwrap();
    file.upsert(ClientRoom {
        id: "room-id-2".into(),
        ..room.clone()
    })
    .unwrap();
    let removed = file.remove_except(&["room-id-2".to_owned()]).unwrap();
    assert_eq!(removed.len(), 1);
    assert_eq!(removed[0].id, "room-id-1");
    assert_eq!(file.load().unwrap().len(), 1);
}

#[test]
fn client_room_redacts_secrets_everywhere_they_could_be_printed() {
    let room = ClientRoom {
        id: "room-id-1".into(),
        name: "Home fleet".into(),
        secret: SECRET.into(),
        joined_at: 5,
        members: vec![member("server", "tcMEMBERADDRESS")],
        sessions: vec![RoomMemberSession {
            address: "tcMEMBERADDRESS".into(),
            engine_key: "engine-1".into(),
            session_id: "session-1".into(),
        }],
    };
    let debug = format!("{room:?}");
    assert!(!debug.contains(SECRET), "{debug}");
    assert!(!debug.contains("tcMEMBERADDRESS"), "{debug}");
    // The grant never prints its credential either.
    let grant = RoomGrant {
        session: roboco_proto::PairedSession {
            id: "s".into(),
            label: "l".into(),
            created_at: 1,
            last_seen: 2,
            revoked_at: None,
        },
        credential: "CREDENTIAL".into(),
    };
    let grant_debug = format!("{grant:?}");
    assert!(!grant_debug.contains("CREDENTIAL"), "{grant_debug}");
    // The record rebuilds the shareable invite exactly.
    let invite = room.invite().unwrap();
    assert_eq!(invite.secret, SECRET);
    assert_eq!(invite.members, room.members);
}

#[test]
fn union_members_only_ever_adds_in_a_deterministic_order() {
    let mut members = vec![member("b", "tcB"), member("a", "tcA")];
    union_members(&mut members, vec![member("a", "tcA"), member("c", "tcC")]);
    // Sorted by (label, address), exact pairs deduped.
    assert_eq!(
        members,
        vec![member("a", "tcA"), member("b", "tcB"), member("c", "tcC")]
    );
    // A same-label-different-address entry is a fresh correction, not a
    // duplicate: both stay (books only grow; eviction rotates).
    union_members(&mut members, vec![member("a", "tcA2")]);
    assert!(members.contains(&member("a", "tcA2")));
    assert!(members.contains(&member("a", "tcA")));
    assert_eq!(members.len(), 4);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn presentations_mint_sessions_and_refusals_carry_fixed_text() {
    let engine = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcA")]).unwrap();
    engine.join(&invite);
    let base = engine.base();

    // A presentation mints an ordinary session, labeled by the presenter.
    let grant = present_room_member(&base, SECRET, "Pixel").await.unwrap();
    assert_eq!(grant.session.label, "Pixel");
    assert!(grant.session.revoked_at.is_none());
    // The granted credential is a real session: it authenticates a
    // WebSocket and reaches the RPC surface.
    let endpoint = base.replacen("http", "ws", 1);
    let client = roboco_rpc::connect_ws_authenticated(&endpoint, &grant.credential)
        .await
        .unwrap();
    let info = client.call(methods::ENGINE_INFO, json!({})).await.unwrap();
    assert_eq!(info["deviceId"], engine.core.device_id);

    // A wrong secret is refused with fixed text — nothing echoes the guess,
    // and no error mentions the real secret.
    let refused = present_room_member(&base, "a-wrong-secret-guess", "Pixel")
        .await
        .unwrap_err()
        .to_string();
    assert_eq!(refused, "the member refused the room secret");
    assert!(!refused.contains("a-wrong-secret-guess"));
    assert!(!refused.contains(SECRET));

    // An unreachable member fails as unreachable, not as a refusal.
    let dead = present_room_member("http://127.0.0.1:1", SECRET, "Pixel")
        .await
        .unwrap_err()
        .to_string();
    assert_eq!(dead, "could not reach the member engine");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn joining_presents_to_each_member_adopts_entries_and_unions_books() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;

    // Three member engines: b and c joined the room (their hosts ran the
    // engine-side join), d never did (its presentation must be refused).
    let b = member_engine().await;
    let c = member_engine().await;
    let d = member_engine().await;
    // b's own book knows an engine the invite does not (a stale book is
    // corrected by union, never by deletion — ADR 0011).
    let invite = RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![
            member("local", "tcLOCAL"),
            member("server", "tcB"),
            member("laptop", "tcC"),
            member("stranger", "tcD"),
        ],
    )
    .unwrap();
    let b_invite = RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![member("server", "tcB"), member("extra", "tcX")],
    )
    .unwrap();
    b.join(&b_invite);
    c.join(&invite);

    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    bases.insert("tcC".to_owned(), c.base());
    bases.insert("tcD".to_owned(), d.base());
    let map = MapDialer::new(bases);
    let dialer: Arc<dyn RoomDialer> = map.clone();

    let outcome = join_room_members(
        &registry,
        dialer,
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        Some("tcLOCAL"),
    )
    .await
    .unwrap();

    // The local engine's own entry is never dialed: it joins through its
    // room store, not over the network.
    assert!(!map.dials().contains(&"tcLOCAL".to_owned()));
    assert_eq!(
        map.dial_count(),
        3,
        "b, c and the stranger were dialed once"
    );

    // The never-joined member is the one failure, with fixed text.
    assert_eq!(outcome.failures.len(), 1);
    assert_eq!(outcome.failures[0].label, "stranger");
    assert!(
        !outcome.failures[0].error.contains(SECRET),
        "the failure text never carries the secret"
    );
    assert!(
        !outcome.failures[0].error.contains("tcD"),
        "the failure text never carries the address"
    );

    // Both live members were adopted as ordinary registry engines: the
    // registry now holds local + server + laptop, all connected.
    wait_connected(&registry, 3).await;
    assert_eq!(outcome.room.sessions.len(), 2);
    for key in outcome
        .room
        .sessions
        .iter()
        .map(|session| EngineKey(session.engine_key.clone()))
    {
        assert!(registry.target(&key).is_ok(), "the session's entry lives");
    }
    // The presentation minted exactly one desktop session on each engine.
    for (engine, label) in [(&b, "server"), (&c, "laptop")] {
        let sessions = PairingStore::open(engine.dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|session| session.label == DESKTOP_LABEL)
            .collect::<Vec<_>>();
        assert_eq!(sessions.len(), 1, "{label} holds one desktop session");
    }

    // The book union: b's extra engine arrived in our record…
    assert!(outcome.room.members.contains(&member("extra", "tcX")));
    // …and the merged book was pushed back: b and c both now carry the
    // union, including entries the other one knew.
    let room_id = outcome.room.id.clone();
    for engine in [&b, &c] {
        let book = RoomStore::open(engine.dir.path())
            .unwrap()
            .book_verified(&room_id, SECRET)
            .unwrap()
            .expect("the member joined the room");
        assert!(book.members.contains(&member("extra", "tcX")));
        assert!(book.members.contains(&member("laptop", "tcC")));
        assert!(book.members.contains(&member("server", "tcB")));
    }

    // A second pass is idempotent for live members (their forwarders stay
    // alive, no re-dial); members without a session keep trying — the
    // stranger is still refused, and the newly-learned "extra" member is
    // dialed for the first time (its address is unknown to the fake dialer,
    // standing in for an offline engine).
    let second = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        outcome.room.clone(),
        Some("tcLOCAL"),
    )
    .await
    .unwrap();
    assert_eq!(
        map.dial_count(),
        5,
        "b and c are live (no re-dial); the stranger and extra retry"
    );
    let failed: Vec<_> = second
        .failures
        .iter()
        .map(|failure| failure.label.clone())
        .collect();
    assert_eq!(failed, vec!["extra".to_owned(), "stranger".to_owned()]);
    assert_eq!(second.room.sessions.len(), 2);
    // Session rows are reused, never duplicated.
    assert_eq!(registry.snapshot().engines.len(), 3);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn revoked_room_sessions_die_on_each_member_engine() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;

    let b = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let map = MapDialer::new(bases);
    let outcome = join_room_members(
        &registry,
        map,
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
    )
    .await
    .unwrap();
    assert!(outcome.complete());
    wait_connected(&registry, 2).await;

    // Leave's revoke machinery: every member confirms the revoke.
    let revoked = revoke_room_sessions(&registry, &outcome.room).await;
    assert_eq!(revoked, vec![("server".to_owned(), true)]);
    // The session row on the member engine is now revoked.
    let sessions = PairingStore::open(b.dir.path())
        .unwrap()
        .list_sessions()
        .unwrap()
        .into_iter()
        .filter(|session| session.label == DESKTOP_LABEL)
        .collect::<Vec<_>>();
    assert_eq!(sessions.len(), 1);
    assert!(sessions[0].revoked_at.is_some());

    // Forgetting the room's registry entries removes the paired engines the
    // room brought in — an engine paired by hand is never touched, because
    // only the keys the room's own sessions recorded are forgotten.
    for session in &outcome.room.sessions {
        registry
            .forget(&EngineKey(session.engine_key.clone()))
            .await
            .unwrap();
    }
    assert_eq!(registry.snapshot().engines.len(), 1, "only the local entry");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn book_reads_distinguish_refused_from_unreachable() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let map = MapDialer::new(bases);
    let outcome = join_room_members(
        &registry,
        map,
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
    )
    .await
    .unwrap();
    wait_connected(&registry, 2).await;
    let session = outcome.room.sessions.first().cloned().unwrap();
    let target = registry
        .target(&EngineKey(session.engine_key.clone()))
        .unwrap();
    assert!(await_live(&target, Duration::from_secs(10)).await);

    // The right secret reads the book.
    let book = fetch_room_book(&target, &outcome.room.id, SECRET)
        .await
        .unwrap()
        .expect("the room joined and the secret matches");
    assert!(book.members.contains(&member("server", "tcB")));
    // A wrong secret reads as `None` — refused, indistinguishable from an
    // unknown room by design.
    assert!(
        fetch_room_book(&target, &outcome.room.id, "wrong-secret")
            .await
            .unwrap()
            .is_none()
    );
    // An unknown room id reads as `None` too.
    assert!(
        fetch_room_book(&target, &room_id_of("another-room-secret-123456"), SECRET)
            .await
            .unwrap()
            .is_none()
    );
}

/// `room_id` from proto, spelled out through the re-export the crate's
/// library uses, so tests read like the production callers.
fn room_id_of(secret: &str) -> String {
    roboco_proto::room::room_id(secret)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dead_forwarder_entry_is_replaced_by_a_fresh_dial() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let map = MapDialer::new(bases);
    let outcome = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
    )
    .await
    .unwrap();
    wait_connected(&registry, 2).await;

    // Simulate the restart case: the forwarder's loopback URL is gone, so
    // the saved entry's endpoint is dead and its state drops off connected.
    for session in &outcome.room.sessions {
        registry
            .forget(&EngineKey(session.engine_key.clone()))
            .await
            .unwrap();
    }
    let dials_before = map.dial_count();
    // Re-joining (self-heal): the dead entry is retired, the member is
    // re-dialed, and a fresh session replaces the stale one.
    let rejoined = join_room_members(&registry, map.clone(), ui_dir.path(), outcome.room, None)
        .await
        .unwrap();
    assert!(rejoined.complete());
    assert_eq!(map.dial_count(), dials_before + 1);
    assert_eq!(registry.snapshot().engines.len(), 2);
}
