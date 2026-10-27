//! Rooms: client-side machinery against real engines over loopback —
//! presentations, per-member joins into the registry (with identity
//! dedupe), book unions, the fleet `RoomJoin` push, and best-effort
//! revokes. The dialer is faked (member addresses map straight to loopback
//! listeners); production dials through tailcat forwarders, which is the
//! only piece these tests cannot exercise on this box.

use super::*;
use crate::SessionStore;
use crate::registry::EngineConnectionState;
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
/// recorded (skips and re-dials stay observable). Bases can be remapped
/// mid-test (a member restarting on a new port).
struct MapDialer {
    bases: Mutex<HashMap<String, String>>,
    dialed: Mutex<Vec<String>>,
    count: AtomicUsize,
}

impl MapDialer {
    fn new(bases: HashMap<String, String>) -> Arc<Self> {
        Arc::new(Self {
            bases: Mutex::new(bases),
            dialed: Mutex::new(Vec::new()),
            count: AtomicUsize::new(0),
        })
    }

    fn remap(&self, address: &str, base: String) {
        self.bases.lock().unwrap().insert(address.to_owned(), base);
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
            .lock()
            .unwrap()
            .get(address)
            .ok_or_else(|| anyhow::anyhow!("unknown member"))?
            .clone();
        Ok(ResolvedMember {
            base,
            keepalive: None,
            route: None,
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

    fn desktop_sessions(&self) -> Vec<roboco_proto::PairedSession> {
        PairingStore::open(self.dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|session| session.label.starts_with(DESKTOP_LABEL))
            .collect()
    }

    /// Restart the listener on a fresh port (the old URL dies), standing in
    /// for a member whose forwarder died with the previous app process.
    async fn restart_listener(&mut self) {
        self.listener.stop().await;
        self.listener = roboco_engine::serve_engine_remote(
            "127.0.0.1:0".parse().unwrap(),
            self.core.rpc_service(),
            self.dir.path(),
        )
        .await
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

/// Pair `engine` by hand (a pairing code), the way Settings → Devices does.
async fn pair_by_hand(registry: &EngineRegistry, engine: &MemberEngine) -> EngineKey {
    let code = PairingStore::open(engine.dir.path())
        .unwrap()
        .create_code("hand pairing", 300)
        .unwrap();
    registry
        .pair(
            &format!("{}/pair#token={}", engine.base(), code.credential),
            "Hand paired",
        )
        .await
        .unwrap()
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
        kind: RoomKind::Fleet,
        members: vec![member("server", "tcADDRESS1")],
        sessions: vec![RoomMemberSession {
            address: "tcADDRESS1".into(),
            engine_key: "engine-1".into(),
            session_id: Some("session-1".into()),
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

    // A corrupt file is an error, and the error never quotes the file.
    std::fs::write(
        dir.path().join(ROOMS_FILE),
        format!("{{\"rooms\": \"{SECRET}"),
    )
    .unwrap();
    let error = file.load().unwrap_err().to_string();
    assert!(!error.contains(SECRET), "{error}");
}

#[test]
fn client_room_redacts_secrets_everywhere_they_could_be_printed() {
    let mut room = ClientRoom {
        id: "room-id-1".into(),
        name: "Home fleet".into(),
        secret: SECRET.into(),
        joined_at: 5,
        kind: RoomKind::Fleet,
        members: vec![member("server", "tcMEMBERADDRESS")],
        sessions: vec![RoomMemberSession {
            address: "tcMEMBERADDRESS".into(),
            engine_key: "engine-1".into(),
            session_id: Some("session-1".into()),
        }],
    };
    room.link("tcLINKEDADDRESS", &EngineKey("engine-2".into()));
    let debug = format!("{room:?}");
    assert!(!debug.contains(SECRET), "{debug}");
    assert!(!debug.contains("tcMEMBERADDRESS"), "{debug}");
    assert!(!debug.contains("tcLINKEDADDRESS"), "{debug}");
    // Linking is idempotent per address and never owns the entry.
    room.link("tcLINKEDADDRESS", &EngineKey("engine-3".into()));
    assert_eq!(room.sessions.len(), 2);
    assert!(!room.sessions[1].adopted());
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
    // The record rebuilds the shareable invite exactly, kind included.
    let invite = room.invite().unwrap();
    assert_eq!(invite.secret, SECRET);
    assert_eq!(invite.members, room.members);
    assert!(invite.is_fleet());
    assert_eq!(ClientRoom::from_invite(&invite).kind, RoomKind::Fleet);
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
        DESKTOP_LABEL,
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
    for session in &outcome.room.sessions {
        assert!(session.adopted(), "fresh entries belong to the room");
        assert!(
            registry
                .target(&EngineKey(session.engine_key.clone()))
                .is_ok(),
            "the session's entry lives"
        );
    }
    // The presentation minted exactly one desktop session on each engine.
    for (engine, label) in [(&b, "server"), (&c, "laptop")] {
        assert_eq!(
            engine.desktop_sessions().len(),
            1,
            "{label} holds one desktop session"
        );
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
    // alive, no re-dial, no new session); members without a session keep
    // trying — the stranger is still refused, and the newly-learned "extra"
    // member is dialed for the first time (its address is unknown to the
    // fake dialer, standing in for an offline engine).
    let second = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        outcome.room.clone(),
        Some("tcLOCAL"),
        DESKTOP_LABEL,
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
    for engine in [&b, &c] {
        assert_eq!(engine.desktop_sessions().len(), 1, "no session churn");
    }
}

/// An engine the desktop already paired by hand is never added twice: the
/// presentation reattaches its existing entry (same key), the room only
/// links to it, and leave's revokes never touch it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_already_paired_member_is_reattached_never_duplicated() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let b = member_engine().await;
    let hand = pair_by_hand(&registry, &b).await;
    wait_connected(&registry, 2).await;

    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let outcome = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert!(outcome.complete());
    // One entry for b — the hand-paired key — and the room merely links it.
    assert_eq!(registry.snapshot().engines.len(), 2);
    assert_eq!(outcome.room.sessions.len(), 1);
    assert_eq!(outcome.room.sessions[0].engine_key, hand.0);
    assert!(!outcome.room.sessions[0].adopted());
    wait_connected(&registry, 2).await;

    // Re-pairing by hand is idempotent too: same key, one entry.
    let again = pair_by_hand(&registry, &b).await;
    assert_eq!(again, hand);
    assert_eq!(registry.snapshot().engines.len(), 2);
    wait_connected(&registry, 2).await;

    // A linked entry is live: the next pass does not dial it again.
    let dials = map.dial_count();
    let second = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        outcome.room,
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert_eq!(map.dial_count(), dials);
    // Leave's revoke machinery skips what the room did not create.
    assert!(
        revoke_room_sessions(&registry, &second.room)
            .await
            .is_empty()
    );
    assert!(registry.target(&hand).unwrap().is_connected());
}

/// F2: an engine reissues a room presentation's session per (room, label),
/// so two desktops in one fleet presenting under one label would revoke
/// each other's credential on every engine they share. Each desktop
/// presents under its own label, and both keep working sessions.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn two_desktops_presenting_to_one_engine_both_keep_their_sessions() {
    let b = member_engine().await;
    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let pairing = PairingStore::open(b.dir.path()).unwrap();

    let mut desktops = Vec::new();
    for _ in 0..2 {
        let local_dir = tempfile::tempdir().unwrap();
        let ui_dir = tempfile::tempdir().unwrap();
        let local = core(local_dir.path());
        let label = desktop_label("Same name", &local.device_id);
        let registry = registry_with_local(local, ui_dir.path()).await;
        let outcome = join_room_members(
            &registry,
            map.clone(),
            ui_dir.path(),
            ClientRoom::from_invite(&invite),
            None,
            &label,
        )
        .await
        .unwrap();
        assert!(outcome.complete());
        wait_connected(&registry, 2).await;
        desktops.push((registry, ui_dir, local_dir, label));
    }
    assert_ne!(desktops[0].3, desktops[1].3, "one label per machine");
    // Both credentials still authenticate after the second presentation.
    for (registry, ui_dir, _, _) in &desktops {
        let saved = crate::FileSessionStore::new(ui_dir.path().join("paired-engines-v1.json"))
            .load()
            .unwrap();
        let remote = saved.iter().find(|engine| !engine.key.is_local()).unwrap();
        assert!(
            pairing.authenticate(&remote.credential).unwrap().is_some(),
            "a desktop's session was reissued away"
        );
        let target = registry.target(&remote.key).unwrap();
        assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_ok());
    }
    assert_eq!(b.desktop_sessions().len(), 2);
}

/// A desktop's presenter label: the shared prefix, the machine's name and a
/// short id tag, inside the label cap; never blank, never just the prefix
/// when an identity exists.
#[test]
fn desktop_labels_are_per_machine_and_bounded() {
    assert_eq!(
        desktop_label("Studio PC", "3f9a1c2e-0000"),
        "Roboco desktop (Studio PC · 3f9a1c)"
    );
    assert_ne!(
        desktop_label("ubuntu", "aaaaaa11"),
        desktop_label("ubuntu", "bbbbbb22")
    );
    assert_eq!(desktop_label("dev-1", "dev-1"), "Roboco desktop (dev1)");
    assert_eq!(desktop_label("", ""), DESKTOP_LABEL);
    let long = desktop_label(&"n".repeat(400), "abcdef12");
    assert!(long.len() <= roboco_proto::room::MAX_LABEL_BYTES);
    assert!(long.ends_with("· abcdef)"));
    assert!(!desktop_label("a\nb", "x").contains('\n'));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn revoked_room_sessions_die_on_each_member_engine() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;

    let b = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let outcome = join_room_members(
        &registry,
        map,
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert!(outcome.complete());
    wait_connected(&registry, 2).await;

    // Leave's revoke machinery: every member confirms the revoke.
    let revoked = revoke_room_sessions(&registry, &outcome.room).await;
    assert_eq!(revoked, vec![("server".to_owned(), true)]);
    // The session row on the member engine is now revoked.
    let sessions = b.desktop_sessions();
    assert_eq!(sessions.len(), 1);
    assert!(sessions[0].revoked_at.is_some());

    // Forgetting the room's registry entries removes the paired engines the
    // room brought in.
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
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let outcome = join_room_members(
        &registry,
        map,
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
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
        fetch_room_book(
            &target,
            &roboco_proto::room::room_id("another-room-secret-123456"),
            SECRET
        )
        .await
        .unwrap()
        .is_none()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_forgotten_entry_is_replaced_by_a_fresh_dial() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new("Home fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let outcome = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    wait_connected(&registry, 2).await;

    for session in &outcome.room.sessions {
        registry
            .forget(&EngineKey(session.engine_key.clone()))
            .await
            .unwrap();
    }
    let dials_before = map.dial_count();
    // Re-joining (self-heal): the missing entry is re-dialed and a fresh
    // entry replaces it.
    let rejoined = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        outcome.room,
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert!(rejoined.complete());
    assert_eq!(map.dial_count(), dials_before + 1);
    assert_eq!(registry.snapshot().engines.len(), 2);
    assert_eq!(rejoined.room.sessions.len(), 1);
}

/// The desktop-restart case: the member's forwarder URL died, so its entry
/// sits reconnecting forever. The heal re-dials, and the registry reattaches
/// the SAME entry (same key — tabs and chat ids survive) instead of adding a
/// second one; the room's presentation session is reissued, never doubled.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dead_entry_is_reattached_under_its_key_without_session_churn() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let mut b = member_engine().await;
    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let map = MapDialer::new(HashMap::from([("tcB".to_owned(), b.base())]));
    let outcome = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    wait_connected(&registry, 2).await;
    let key = EngineKey(outcome.room.sessions[0].engine_key.clone());

    b.restart_listener().await;
    map.remap("tcB", b.base());
    // The old entry notices its endpoint is gone.
    let target = registry.target(&key).unwrap();
    tokio::time::timeout(Duration::from_secs(30), async {
        while target.is_connected() {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("the dead entry dropped");

    let healed = join_room_members(
        &registry,
        map.clone(),
        ui_dir.path(),
        outcome.room,
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert!(healed.complete());
    assert_eq!(healed.room.sessions.len(), 1);
    assert_eq!(healed.room.sessions[0].engine_key, key.0, "same entry");
    assert!(
        healed.room.sessions[0].adopted(),
        "still the room's own entry"
    );
    assert_eq!(registry.snapshot().engines.len(), 2, "never a duplicate");
    wait_connected(&registry, 2).await;
    let sessions = b.desktop_sessions();
    assert_eq!(sessions.len(), 1, "the room's session was reissued");
    assert!(sessions[0].revoked_at.is_none());
}

/// F4: an engine adopted through a room saves the dialer's route, so after
/// an app restart the pairing resolver rebuilds its forwarder instead of
/// supervision dialing the saved loopback URL, which died with the old
/// process (or now belongs to another forwarder) — with the credential.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_room_adopted_entry_is_rebuilt_from_its_route_after_a_restart() {
    /// Dials through a fake forwarder with a resolver route, like the
    /// desktop's Tailcat dialer.
    struct RoutedDialer(String);
    impl RoomDialer for RoutedDialer {
        fn dial(&self, address: &str, _data_dir: &Path) -> anyhow::Result<ResolvedMember> {
            Ok(ResolvedMember {
                base: self.0.clone(),
                keepalive: Some(Box::new(())),
                route: Some(format!("route:{address}")),
            })
        }
    }
    struct RestoreResolver {
        base: Arc<Mutex<String>>,
        restored: Arc<Mutex<Vec<String>>>,
    }
    impl crate::PairingResolver for RestoreResolver {
        fn resolve(
            &self,
            _input: &str,
            _data_dir: &Path,
        ) -> Option<anyhow::Result<crate::ResolvedPairing>> {
            None
        }
        fn restore(
            &self,
            route: &str,
            _data_dir: &Path,
        ) -> Option<anyhow::Result<crate::RestoredRoute>> {
            let address = route.strip_prefix("route:")?;
            self.restored.lock().unwrap().push(address.to_owned());
            Some(Ok(crate::RestoredRoute {
                base: self.base.lock().unwrap().clone(),
                keepalive: Some(Box::new(())),
            }))
        }
    }

    let mut b = member_engine().await;
    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    let ui_dir = tempfile::tempdir().unwrap();
    let store_path = ui_dir.path().join("paired-engines-v1.json");
    let base = Arc::new(Mutex::new(b.base()));
    let restored = Arc::new(Mutex::new(Vec::new()));
    let open = || {
        EngineRegistry::open_with(crate::RegistryOptions {
            data_dir: ui_dir.path().to_path_buf(),
            sessions: Arc::new(crate::FileSessionStore::new(store_path.clone())),
            local: None,
            resolvers: vec![Arc::new(RestoreResolver {
                base: base.clone(),
                restored: restored.clone(),
            })],
        })
    };
    let registry = open().await.unwrap();
    let outcome = join_room_members(
        &registry,
        Arc::new(RoutedDialer(b.base())),
        ui_dir.path(),
        ClientRoom::from_invite(&invite),
        None,
        DESKTOP_LABEL,
    )
    .await
    .unwrap();
    assert!(outcome.complete());
    wait_connected(&registry, 1).await;
    let key = EngineKey(outcome.room.sessions[0].engine_key.clone());
    let saved = crate::FileSessionStore::new(store_path.clone())
        .load()
        .unwrap();
    assert_eq!(saved[0].route.as_deref(), Some("route:tcB"));
    registry.shutdown().await;
    drop(registry);

    // The restart: the forwarder's old loopback port is dead.
    b.restart_listener().await;
    *base.lock().unwrap() = b.base();
    let registry = open().await.unwrap();
    wait_connected(&registry, 1).await;
    assert_eq!(*restored.lock().unwrap(), vec!["tcB".to_owned()]);
    let target = registry.target(&key).unwrap();
    assert!(target.call(methods::ENGINE_INFO, json!({})).await.is_ok());
    assert_eq!(b.desktop_sessions().len(), 1, "no re-presentation needed");
    registry.shutdown().await;
}

/// Fleet follow's push: `RoomJoin` over the paired session joins the member
/// engine idempotently, and an engine holding a different fleet answers the
/// recognizable conflict — never quoting the invite.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn room_join_pushes_idempotently_and_reports_fleet_conflicts() {
    let local_dir = tempfile::tempdir().unwrap();
    let ui_dir = tempfile::tempdir().unwrap();
    let registry = registry_with_local(core(local_dir.path()), ui_dir.path()).await;
    let b = member_engine().await;
    let key = pair_by_hand(&registry, &b).await;
    wait_connected(&registry, 2).await;
    let target = registry.target(&key).unwrap();

    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    let encoded = invite.encode().unwrap();
    let first = push_room_join(&target, &encoded).await.unwrap();
    let second = push_room_join(&target, &encoded).await.unwrap();
    assert_eq!(first, invite.room_id());
    assert_eq!(first, second);
    let store = RoomStore::open(b.dir.path()).unwrap();
    assert_eq!(store.list().unwrap().len(), 1, "one row, never two");
    assert_eq!(store.fleet_room().unwrap().unwrap().id, first);

    let other_secret = roboco_proto::room::mint_room_secret().unwrap();
    let other = RoomInvite::fleet("Fleet", &other_secret, Vec::new())
        .unwrap()
        .encode()
        .unwrap();
    let error = push_room_join(&target, &other).await.unwrap_err();
    assert!(is_fleet_conflict(&error), "{error}");
    assert!(!error.to_string().contains(&other_secret));
    assert!(!error.to_string().contains(&other));
}
