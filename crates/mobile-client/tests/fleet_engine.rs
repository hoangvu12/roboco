//! Fleet follow end to end (fleet-follow ticket 04): "pair my phone to any
//! engine and the whole fleet comes along, no duplicates." Real in-process
//! engines joined to a real fleet room through the same `RoomStore` the
//! desktop's `RoomJoin` push and the CLI drive — no protocol mocks. The
//! phone pairs with an ordinary one-time pairing link, then fetches the
//! fleet over the fresh session (`GetFleetInvite`) and joins it silently.

mod support;

use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, Instant};

use roboco_engine::pairing::PairingStore;
use roboco_engine::rooms::RoomStore;
use roboco_engine::{EngineCore, EngineListener, EngineProfile, HarnessId};
use roboco_proto::room::{RoomInvite, RoomMember, room_id};

use roboco_mobile_client::{Client, ClientEvent, EngineState, RoomRecord};
use support::{Recorder, test_config, test_timings};

const SECRET: &str = "fleet-engine-secret-0123456789abcdefghij";
const FLEET: &str = "Fleet";

fn member(label: &str, address: &str) -> RoomMember {
    RoomMember {
        label: label.into(),
        address: address.into(),
    }
}

/// One real engine served on loopback, optionally joined to the fleet.
struct FleetEngine {
    dir: tempfile::TempDir,
    core: EngineCore,
    listener: Option<EngineListener>,
    /// The address a book carries for this member (its loopback URL).
    address: String,
}

impl FleetEngine {
    async fn start() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = EngineCore::assemble_with_profile(
            EngineProfile::local(dir.path()).unwrap(),
            Arc::new(roboco_engine::smoke_registry()),
            HarnessId::Mock,
        )
        .unwrap();
        let listener = support::serve(&core, dir.path()).await;
        Self {
            address: format!("http://{}", listener.address),
            dir,
            core,
            listener: Some(listener),
        }
    }

    /// An engine that is a fleet member but asleep: joined, not served —
    /// the pre-picked port nothing listens on is its address.
    fn asleep(port: u16) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = EngineCore::assemble_with_profile(
            EngineProfile::local(dir.path()).unwrap(),
            Arc::new(roboco_engine::smoke_registry()),
            HarnessId::Mock,
        )
        .unwrap();
        Self {
            address: format!("http://127.0.0.1:{port}"),
            dir,
            core,
            listener: None,
        }
    }

    async fn wake_up(&mut self, port: u16) {
        self.listener = Some(support::serve_on_port(&self.core, self.dir.path(), port).await);
    }

    /// Join the fleet with this book — the desktop's `RoomJoin` push lands
    /// in exactly this store call (idempotent; a re-join unions the book).
    fn join_fleet(&self, members: &[RoomMember]) {
        RoomStore::open(self.dir.path())
            .unwrap()
            .join(RoomInvite::fleet(FLEET, SECRET, members.to_vec()).unwrap())
            .unwrap();
    }

    /// Join a plain (non-fleet) room: verifier only, never offered.
    fn join_plain_room(&self, members: &[RoomMember]) {
        RoomStore::open(self.dir.path())
            .unwrap()
            .join(RoomInvite::new("Plain room", SECRET, members.to_vec()).unwrap())
            .unwrap();
    }

    /// A fresh one-time pairing link (the existing QR's payload).
    fn pairing_url(&self) -> String {
        support::pairing_url(self.listener.as_ref().unwrap(), self.dir.path(), None)
    }

    /// The phone's sessions on this engine, revoked ones included.
    fn phone_sessions(&self) -> Vec<roboco_proto::PairedSession> {
        PairingStore::open(self.dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|session| session.label == "Pixel 9")
            .collect()
    }

    fn live_phone_sessions(&self) -> usize {
        self.phone_sessions()
            .iter()
            .filter(|session| session.revoked_at.is_none())
            .count()
    }
}

fn free_port() -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    port
}

fn client(recorder: &Arc<Recorder>) -> Arc<Client> {
    let data_dir = tempfile::tempdir().unwrap().keep();
    Client::new_with_timings(
        test_config(&data_dir),
        Vec::new(),
        Vec::new(),
        recorder.clone(),
        test_timings(),
    )
    .unwrap()
}

async fn wait_connected(client: &Client, engine_id: &str, what: &str) {
    support::wait_for_view(client, engine_id, what, |view| {
        view.state == EngineState::Connected
    })
    .await;
}

async fn wait_all_connected(client: &Client) {
    for view in client.engines() {
        wait_connected(client, &view.engine_id, "every engine connects").await;
    }
}

/// Every engine view's pinned device id — one per engine, no duplicates.
fn assert_no_duplicate_engines(client: &Client, expected: usize) {
    let views = client.engines();
    assert_eq!(views.len(), expected, "engine rows");
    let ids: HashSet<_> = views.iter().map(|view| view.engine_id.clone()).collect();
    assert_eq!(ids.len(), expected, "engine ids are unique");
    let devices: HashSet<_> = views
        .iter()
        .map(|view| {
            view.device_id
                .clone()
                .expect("every engine pinned its identity")
        })
        .collect();
    assert_eq!(devices.len(), expected, "no engine appears twice");
}

async fn wait_for_event(recorder: &Arc<Recorder>, what: &str, matches: impl Fn(&ClientEvent) -> bool) {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if recorder.events().iter().any(|event| matches(event)) {
            return;
        }
        assert!(Instant::now() < deadline, "event never arrived: {what}");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

// ── the tests ───────────────────────────────────────────────────────────────

#[tokio::test]
async fn pairing_one_member_brings_the_fleet_along_and_re_pairing_is_idempotent() {
    let desktop = FleetEngine::start().await;
    let server = FleetEngine::start().await;
    let nas = FleetEngine::start().await;
    let book = vec![
        member("desktop", &desktop.address),
        member("server", &server.address),
        member("nas", &nas.address),
    ];
    for engine in [&desktop, &server, &nas] {
        engine.join_fleet(&book);
    }
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    // The ordinary pairing (the existing QR, unchanged).
    let paired = client.pair_engine(&desktop.pairing_url(), None).await.unwrap();
    assert!(paired.room.is_none(), "a pairing is not a room join");
    assert_eq!(desktop.live_phone_sessions(), 1);

    // Step 1: the fleet arrives over the fresh session — opaque, secret-free.
    let offer = client
        .fetch_fleet(&paired.engine_id)
        .await
        .expect("the desktop offers its fleet");
    assert_eq!(offer.name(), FLEET);
    assert_eq!(offer.room_id(), room_id(SECRET));
    assert_eq!(offer.member_labels().len(), 3);
    let printed = format!("{offer:?}");
    assert!(!printed.contains(SECRET), "{printed}");
    assert!(!printed.contains(&server.address), "{printed}");

    // Step 2: the silent join.
    let join = client.join_fleet(offer).await.unwrap();
    assert!(join.missing.is_empty());
    assert_eq!(join.room.room_id, room_id(SECRET));
    assert_eq!(join.room.secret, SECRET, "the record the platform keeps in its room: blob");
    // The desktop was adopted by tag (same engine id, no presentation); the
    // two others joined with one session each.
    assert_eq!(join.engines.len(), 3, "three records to persist");
    let adopted = join
        .engines
        .iter()
        .find(|record| record.engine_id == paired.engine_id)
        .expect("the introducing engine is adopted, not duplicated");
    assert_eq!(adopted.credential, paired.credential, "no session churn");
    assert_eq!(adopted.session_id, paired.session_id);
    for record in &join.engines {
        assert_eq!(record.room.as_ref().unwrap().room_id, room_id(SECRET));
    }
    wait_all_connected(&client).await;
    assert_no_duplicate_engines(&client, 3);
    for view in client.engines() {
        assert_eq!(view.room.as_ref().unwrap().room_id, room_id(SECRET));
    }
    // One session per engine for this phone: the desktop's is the pairing's.
    assert_eq!(desktop.live_phone_sessions(), 1);
    assert_eq!(server.live_phone_sessions(), 1);
    assert_eq!(nas.live_phone_sessions(), 1);

    // Re-running the fetch + join is a no-op: nothing to persist, no
    // sessions, no rows.
    let again = client
        .follow_fleet(&paired.engine_id)
        .await
        .unwrap()
        .expect("the fleet is still offered");
    assert!(again.engines.is_empty(), "nothing changed: {:?}", again.engines.len());
    assert!(again.missing.is_empty());
    assert_eq!(again.room.room_id, room_id(SECRET), "the room record upserts");
    assert_no_duplicate_engines(&client, 3);
    assert_eq!(desktop.phone_sessions().len(), 1);
    assert_eq!(server.phone_sessions().len(), 1);
    assert_eq!(nas.phone_sessions().len(), 1);

    // Re-pairing the same engine (a fresh link) folds into its row, keeps
    // the room tag, and the re-run fetch mints nothing anywhere: only the
    // re-pair's own pairing session is new.
    let repaired = client.pair_engine(&desktop.pairing_url(), None).await.unwrap();
    assert_eq!(repaired.engine_id, paired.engine_id, "re-pair folds by identity");
    assert_eq!(
        repaired.room.as_ref().map(|room| room.room_id.clone()),
        Some(room_id(SECRET)),
        "a plain re-pair keeps the room tag"
    );
    let desktop_sessions_after_repair = desktop.phone_sessions().len();
    assert_eq!(desktop_sessions_after_repair, 2, "the re-pair's own pairing session");
    let rerun = client
        .follow_fleet(&repaired.engine_id)
        .await
        .unwrap()
        .expect("the fleet is offered again");
    assert!(rerun.engines.is_empty(), "the re-run persists nothing new");
    wait_all_connected(&client).await;
    assert_no_duplicate_engines(&client, 3);
    assert_eq!(desktop.phone_sessions().len(), desktop_sessions_after_repair);
    assert_eq!(server.phone_sessions().len(), 1);
    assert_eq!(nas.phone_sessions().len(), 1);
}

#[tokio::test]
async fn already_paired_members_are_skipped_and_a_second_address_folds_by_identity() {
    let desktop = FleetEngine::start().await;
    let server = FleetEngine::start().await;
    let laptop = FleetEngine::start().await;
    // The book names the desktop by another spelling of its address than
    // the one the phone pairs through (a LAN pairing vs a book address):
    // only the engine identity can tell they are the same engine.
    let port = desktop.address.rsplit(':').next().unwrap().to_owned();
    let desktop_alias = format!("http://localhost:{port}");
    let book = vec![
        member("desktop", &desktop_alias),
        member("server", &server.address),
        member("laptop", &laptop.address),
    ];
    for engine in [&desktop, &server, &laptop] {
        engine.join_fleet(&book);
    }
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    // The server was paired on this phone long before the fleet existed.
    let server_record = client.pair_engine(&server.pairing_url(), None).await.unwrap();
    wait_connected(&client, &server_record.engine_id, "the server").await;
    // Now the phone pairs the desktop and follows its fleet.
    let paired = client.pair_engine(&desktop.pairing_url(), None).await.unwrap();
    let join = client
        .follow_fleet(&paired.engine_id)
        .await
        .unwrap()
        .expect("the fleet follows the pairing");
    assert!(join.missing.is_empty());

    wait_all_connected(&client).await;
    // Three engines, three rows: the server's existing row was adopted by
    // tag (no new session), the desktop's alias folded into the desktop's
    // row by identity, the laptop joined.
    assert_no_duplicate_engines(&client, 3);
    let engine_ids: HashSet<_> = join.engines.iter().map(|r| r.engine_id.clone()).collect();
    assert_eq!(engine_ids.len(), join.engines.len(), "one record per engine");
    assert!(engine_ids.contains(&server_record.engine_id));
    assert!(engine_ids.contains(&paired.engine_id), "the alias folded into the paired row");
    assert_eq!(server.phone_sessions().len(), 1, "the paired server got no second session");
    assert_eq!(laptop.phone_sessions().len(), 1);
    // The alias cost one room session on the desktop (the engine reuses it
    // per room and label from now on); the row now dials the book address.
    assert_eq!(desktop.live_phone_sessions(), 2);
    let desktop_view = client.engine(&paired.engine_id).unwrap();
    assert_eq!(desktop_view.base_url, desktop_alias);

    // Idempotent from here: re-following and a heal mint nothing.
    let again = client
        .follow_fleet(&paired.engine_id)
        .await
        .unwrap()
        .expect("offered again");
    assert!(again.engines.is_empty());
    client.set_network_changed();
    tokio::time::sleep(Duration::from_millis(500)).await;
    wait_all_connected(&client).await;
    assert_no_duplicate_engines(&client, 3);
    assert_eq!(desktop.phone_sessions().len(), 2);
    assert_eq!(server.phone_sessions().len(), 1);
    assert_eq!(laptop.phone_sessions().len(), 1);
}

#[tokio::test]
async fn an_engine_without_a_fleet_answers_null_and_the_pairing_stays_plain() {
    // No room at all, then only a plain room: both answer null — plain
    // rooms are never offered.
    let lone = FleetEngine::start().await;
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);
    let paired = client.pair_engine(&lone.pairing_url(), None).await.unwrap();

    assert!(client.fetch_fleet(&paired.engine_id).await.is_none());
    assert!(client.follow_fleet(&paired.engine_id).await.unwrap().is_none());

    lone.join_plain_room(&[member("lone", &lone.address)]);
    assert!(client.fetch_fleet(&paired.engine_id).await.is_none());

    // Nothing changed: one engine, untagged, one session, no notices.
    assert_eq!(client.engines().len(), 1);
    assert!(client.engine(&paired.engine_id).unwrap().room.is_none());
    assert_eq!(lone.phone_sessions().len(), 1);
    assert!(recorder
        .events()
        .iter()
        .all(|event| !matches!(event, ClientEvent::Notice { .. } | ClientEvent::RoomEngineJoined { .. })));
    // An unknown engine id is the same silent no-op.
    assert!(client.fetch_fleet("no-such-engine").await.is_none());
}

#[tokio::test]
async fn a_late_member_heals_into_the_fleet_on_the_next_trigger() {
    let port = free_port();
    let desktop = FleetEngine::start().await;
    let mut late = FleetEngine::asleep(port);
    let book = vec![
        member("desktop", &desktop.address),
        member("late", &late.address),
    ];
    desktop.join_fleet(&book);
    late.join_fleet(&book);
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let paired = client.pair_engine(&desktop.pairing_url(), None).await.unwrap();
    let join = client
        .follow_fleet(&paired.engine_id)
        .await
        .unwrap()
        .expect("the fleet follows");
    // A partial join is a success: the desktop adopted, the late member
    // missing (informational, never an error).
    assert_eq!(join.missing, vec!["late".to_string()]);
    assert_eq!(client.engines().len(), 1);

    late.wake_up(port).await;
    client.set_network_changed();
    let deadline = Instant::now() + Duration::from_secs(20);
    while client.engines().len() < 2 {
        assert!(Instant::now() < deadline, "the late member never healed in");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    wait_all_connected(&client).await;
    assert_no_duplicate_engines(&client, 2);
    assert_eq!(late.phone_sessions().len(), 1);
    assert_eq!(desktop.phone_sessions().len(), 1, "the heal never re-presents to the desktop");
}

#[tokio::test]
async fn the_heal_adopts_an_untagged_paired_member_without_a_session() {
    // The restart path: the platform hands back a stored fleet plus an
    // engine paired before the fleet was followed (untagged). The start
    // heal adopts it by tag — a persistable record, no presentation.
    let desktop = FleetEngine::start().await;
    let book = vec![member("desktop", &desktop.address)];
    desktop.join_fleet(&book);
    let first = client(&Arc::new(Recorder::default()));
    let record = first.pair_engine(&desktop.pairing_url(), None).await.unwrap();
    drop(first);
    assert_eq!(desktop.phone_sessions().len(), 1);

    let recorder = Arc::new(Recorder::default());
    let data_dir = tempfile::tempdir().unwrap().keep();
    let stored_fleet = RoomRecord {
        room_id: room_id(SECRET),
        label: FLEET.into(),
        secret: SECRET.into(),
        members: book.clone(),
    };
    let client = Client::new_with_timings(
        test_config(&data_dir),
        vec![record.clone()],
        vec![stored_fleet],
        recorder.clone(),
        test_timings(),
    )
    .unwrap();

    wait_for_event(&recorder, "the adopted record", |event| {
        matches!(
            event,
            ClientEvent::RoomEngineJoined { engine, .. }
                if engine.engine_id == record.engine_id
                    && engine.room.as_ref().is_some_and(|room| room.room_id == room_id(SECRET))
                    && engine.credential == record.credential
        )
    })
    .await;
    wait_all_connected(&client).await;
    assert_no_duplicate_engines(&client, 1);
    assert_eq!(desktop.phone_sessions().len(), 1, "adopted by tag, no presentation");
}
