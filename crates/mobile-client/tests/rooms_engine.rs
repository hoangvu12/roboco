//! Rooms end to end (ADR 0011, ticket 04): real in-process engines joined
//! to a real room — no protocol mocks — and a phone core that joins it,
//! heals missing members, and maintains the book. The engines join
//! through the same `RoomStore` the CLI drives (WAL coordinates the
//! listener's own handle, which is exactly the deployed shape: the
//! desktop joins its engines, the phone presents the secret).

mod support;

use std::sync::Arc;
use std::time::{Duration, Instant};

use roboco_engine::pairing::PairingStore;
use roboco_engine::rooms::RoomStore;
use roboco_engine::{EngineCore, EngineListener, EngineProfile, HarnessId};
use roboco_proto::room::{RoomInvite, RoomMember, room_id};

use roboco_mobile_client::rooms::RoomError;
use roboco_mobile_client::{
    Client, ClientEvent, EngineState, EngineTransport, ParkReason, RoomRecord,
};
use support::{Recorder, test_config, test_timings};

const SECRET: &str = "rooms-engine-secret-0123456789abcdefghij";
const ROOM_NAME: &str = "Home fleet";

fn member(label: &str, address: &str) -> RoomMember {
    RoomMember {
        label: label.into(),
        address: address.into(),
    }
}

/// One real engine joined to a room, served on loopback (`None` for an
/// asleep member: joined, but nothing dials it until `wake_up`).
struct RoomEngine {
    #[allow(dead_code)] // keeps the data dir alive for the store reads
    dir: tempfile::TempDir,
    core: EngineCore,
    listener: Option<EngineListener>,
    /// The address a book carries for this member.
    address: String,
}

impl RoomEngine {
    /// Assemble the engine, join the room, then serve. `members` is the
    /// book this engine joins with (books are copied to every member, but
    /// the tests vary them to prove the union).
    async fn start(members: Vec<RoomMember>) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = EngineCore::assemble_with_profile(
            EngineProfile::local(dir.path()).unwrap(),
            Arc::new(roboco_engine::smoke_registry()),
            HarnessId::Mock,
        )
        .unwrap();
        let invite = RoomInvite::new(ROOM_NAME, SECRET, members).unwrap();
        RoomStore::open(dir.path()).unwrap().join(invite).unwrap();
        let listener = support::serve(&core, dir.path()).await;
        Self {
            address: format!("http://{}", listener.address),
            dir,
            core,
            listener: Some(listener),
        }
    }

    /// A joined-but-unreachable member: assemble and join, but do not
    /// serve — the pre-picked port nothing listens on is its address.
    fn asleep(members: Vec<RoomMember>, port: u16) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let core = EngineCore::assemble_with_profile(
            EngineProfile::local(dir.path()).unwrap(),
            Arc::new(roboco_engine::smoke_registry()),
            HarnessId::Mock,
        )
        .unwrap();
        let invite = RoomInvite::new(ROOM_NAME, SECRET, members).unwrap();
        RoomStore::open(dir.path()).unwrap().join(invite).unwrap();
        Self {
            address: format!("http://127.0.0.1:{port}"),
            dir,
            core,
            listener: None,
        }
    }

    /// Assemble and serve an engine that never joined the room (a
    /// stranger: its `/room/present` answers 401).
    async fn start_stranger() -> Self {
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

    /// Bring an asleep member up on its pre-picked port.
    async fn wake_up(&mut self, port: u16) {
        let core = &self.core;
        self.listener = Some(support::serve_on_port(core, self.dir.path(), port).await);
    }

    /// Drop the serving listener and re-serve on the same port: the
    /// phone's connections drop and its next dial re-authenticates (a
    /// revocation only bites on a fresh handshake — an established
    /// WebSocket was authenticated before the revoke).
    async fn bounce_listener(&mut self) {
        let port: u16 = self
            .address
            .rsplit(':')
            .next()
            .and_then(|p| p.parse().ok())
            .expect("the loopback address carries a port");
        self.listener = None;
        // Give the phone's supervisor a beat to see the drop.
        tokio::time::sleep(Duration::from_millis(200)).await;
        self.wake_up(port).await;
    }

    fn room_book(&self) -> Vec<RoomMember> {
        RoomStore::open(self.dir.path())
            .unwrap()
            .book_verified(&room_id(SECRET), SECRET)
            .unwrap()
            .unwrap()
            .members
    }

    fn sessions_labeled_pixel(&self) -> Vec<roboco_proto::PairedSession> {
        PairingStore::open(self.dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|session| session.label == "Pixel 9")
            .collect()
    }
}

/// A free port (bound then released).
fn free_port() -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    port
}

fn client(recorder: &Arc<Recorder>) -> Arc<Client> {
    // `into_path`: the client's cache dir outlives the helper (TempDir
    // would delete it under the running client).
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

/// Waits for a view satisfying the predicate to appear (a joined member
/// whose id the test does not know yet).
async fn wait_for_engine(
    client: &Client,
    predicate: impl Fn(&roboco_mobile_client::EngineView) -> bool,
    what: &str,
) -> roboco_mobile_client::EngineView {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(view) = client.engines().into_iter().find(|view| predicate(view)) {
            return view.clone();
        }
        assert!(Instant::now() < deadline, "never reached: {what}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// Waits (bounded) until the recorder holds an event matching `what`.
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

/// A bare HTTP server answering every request with `status` — the
/// old-engine / refused stand-ins (no room machinery at all).
async fn bare_server(status: &'static str) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            let status = status;
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut scratch = [0u8; 4096];
                let _ = socket.read(&mut scratch).await;
                let head =
                    format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                let _ = socket.write_all(head.as_bytes()).await;
            });
        }
    });
    address
}

// ── the tests ───────────────────────────────────────────────────────────────

#[tokio::test]
async fn joining_mints_one_session_per_member_and_lists_the_engines() {
    let a = RoomEngine::start(Vec::new()).await;
    let a_address = a.address.clone();
    let b = RoomEngine::start(vec![member("desktop", &a_address)]).await;
    let members = vec![
        member("desktop", &a_address),
        member("server", &b.address),
    ];
    // Both engines join the room carrying the full book (a re-join unions).
    for engine in [&a, &b] {
        RoomStore::open(engine.dir.path())
            .unwrap()
            .join(RoomInvite::new(ROOM_NAME, SECRET, members.clone()).unwrap())
            .unwrap();
    }
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let join = client.join_room(&invite_of(&members)).await.unwrap();

    // One engine entry per member, each with its own session, the room
    // tag in place, and nothing else missing.
    assert_eq!(join.engines.len(), 2, "both members joined");
    assert!(join.missing.is_empty());
    assert_eq!(join.room.room_id, room_id(SECRET));
    assert_eq!(join.room.label, ROOM_NAME);
    assert_eq!(join.room.secret, SECRET);
    assert_eq!(join.room.members.len(), 2);
    for record in &join.engines {
        let room = record.room.as_ref().expect("the room tag");
        assert_eq!(room.room_id, room_id(SECRET));
        assert_eq!(room.label, ROOM_NAME);
        assert!(matches!(record.transport, EngineTransport::Direct));
        assert!(
            members.iter().any(|m| m.address == record.base_url),
            "the record dials the member's own address"
        );
    }
    // Every member is connected and shows the room in its view.
    for record in &join.engines {
        wait_connected(&client, &record.engine_id, "the joined engine").await;
        let view = client.engine(&record.engine_id).unwrap();
        assert_eq!(view.room.as_ref().unwrap().room_id, room_id(SECRET));
        assert_eq!(view.room.as_ref().unwrap().label, ROOM_NAME);
    }
    // The engines each hold exactly one ordinary session for this phone.
    assert_eq!(a.sessions_labeled_pixel().len(), 1);
    assert_eq!(b.sessions_labeled_pixel().len(), 1);

    // Re-joining the same room is a skip, not a re-present: no new
    // engines, no extra sessions (presentations are repeatable, but the
    // phone never re-issues a live credential for nothing).
    let again = client.join_room(&invite_of(&members)).await.unwrap();
    assert!(again.engines.is_empty(), "skipped, not re-joined");
    assert!(again.missing.is_empty());
    assert_eq!(a.sessions_labeled_pixel().len(), 1);
    assert_eq!(b.sessions_labeled_pixel().len(), 1);
}

#[tokio::test]
async fn a_partial_join_persists_and_heals_on_the_next_trigger() {
    let port = free_port();
    let a = RoomEngine::start(Vec::new()).await;
    let a_address = a.address.clone();
    let members = vec![
        member("desktop", &a_address),
        member("server", &format!("http://127.0.0.1:{port}")),
    ];
    // The asleep member joins the room (its store holds the verifier) but
    // nothing serves its address yet.
    let mut b = RoomEngine::asleep(members.clone(), port);
    RoomStore::open(a.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, members.clone()).unwrap())
        .unwrap();
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    // The join is partial: one member joined, the asleep one is missing —
    // a success, never an error.
    let join = client.join_room(&invite_of(&members)).await.unwrap();
    assert_eq!(join.engines.len(), 1);
    assert_eq!(join.missing, vec!["server".to_string()]);
    let a_engine_id = join.engines[0].engine_id.clone();
    wait_connected(&client, &a_engine_id, "the awake member").await;

    // The asleep member wakes; the next network-change trigger heals the
    // room without any user action.
    b.wake_up(port).await;
    client.set_network_changed();
    let healed = wait_for_engine(&client, |view| view.base_url == b.address, "the healed member").await;
    wait_connected(&client, &healed.engine_id, "the healed member").await;
    assert_eq!(healed.room.as_ref().unwrap().room_id, room_id(SECRET));
    // The healed member minted exactly one session.
    assert_eq!(b.sessions_labeled_pixel().len(), 1);

    // The heal minted record(s) crossed as events (what the platform
    // persists) — never coalesced away, one per member. The event lands
    // when the heal pass finishes (after the book push), so wait for it.
    wait_for_event(
        &recorder,
        "the healed member's record",
        |event| {
            matches!(
                event,
                ClientEvent::RoomEngineJoined { room_id, engine }
                    if *room_id == room_id_of_secret() && engine.engine_id == healed.engine_id
            )
        },
    )
    .await;
    let joined_events: Vec<_> = recorder
        .events()
        .into_iter()
        .filter_map(|event| match event {
            ClientEvent::RoomEngineJoined { room_id, engine } => Some((room_id, engine)),
            _ => None,
        })
        .collect();
    assert!(
        joined_events.iter().all(|(_, engine)| !engine.credential.is_empty()),
        "healed records carry live credentials"
    );
}

#[tokio::test]
async fn the_book_union_discovers_members_and_pushes_back() {
    // The phone's invite knows two members; one engine's book carries a
    // third that joined later (through another client).
    let a = RoomEngine::start(Vec::new()).await;
    let a_address = a.address.clone();
    let b = RoomEngine::start(Vec::new()).await;
    let c = RoomEngine::start(Vec::new()).await;
    let phone_members = vec![
        member("desktop", &a_address),
        member("server", &b.address),
    ];
    let full_members = vec![
        member("desktop", &a_address),
        member("server", &b.address),
        member("nas", &c.address),
    ];
    // A knows only the phone's two; B and C know the full book.
    RoomStore::open(a.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, phone_members.clone()).unwrap())
        .unwrap();
    RoomStore::open(b.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, full_members.clone()).unwrap())
        .unwrap();
    RoomStore::open(c.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, full_members.clone()).unwrap())
        .unwrap();
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let join = client.join_room(&invite_of(&phone_members)).await.unwrap();
    assert_eq!(join.engines.len(), 2, "the phone's two known members");
    for record in &join.engines {
        wait_connected(&client, &record.engine_id, "a known member").await;
    }

    // The heal (a network change is the trigger): union the connected
    // books (B's reveals C), dial-verify the discovered member, push the
    // merged book back.
    client.set_network_changed();
    let c_view =
        wait_for_engine(&client, |view| view.base_url == c.address, "the discovered member").await;
    wait_connected(&client, &c_view.engine_id, "the discovered member").await;

    // The push landed: A (which never knew C) now carries the merged book.
    let a_book = a.room_book();
    assert_eq!(a_book.len(), 3, "the merged book was pushed back");
    assert!(a_book.iter().any(|m| m.address == c.address));
    // C joined the room like any member and holds the phone's session.
    assert_eq!(c.sessions_labeled_pixel().len(), 1);

    // The discovered member's record crossed as a heal event.
    wait_for_event(
        &recorder,
        "the discovered member's record",
        |event| {
            matches!(
                event,
                ClientEvent::RoomEngineJoined { engine, .. } if engine.engine_id == c_view.engine_id
            )
        },
    )
    .await;
}

#[tokio::test]
async fn an_old_engine_without_rooms_is_skipped_silently() {
    // A real member plus a bare 404 server: an engine one version behind
    // has no /room/present route. The join of that member is a silent
    // skip — no error, no toast; it heals when the engine is upgraded.
    let a = RoomEngine::start(Vec::new()).await;
    let a_address = a.address.clone();
    let old = bare_server("404 Not Found").await;
    let members = vec![
        member("desktop", &a_address),
        member("old-engine", &old),
    ];
    RoomStore::open(a.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, members.clone()).unwrap())
        .unwrap();
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let join = client.join_room(&invite_of(&members)).await.unwrap();

    assert_eq!(join.engines.len(), 1, "the real member joined");
    assert_eq!(join.missing, vec!["old-engine".to_string()]);
    // No error event, no engine entry for the old member.
    assert!(client.engines().iter().all(|view| view.base_url != old));
    assert!(recorder
        .events()
        .iter()
        .all(|event| !matches!(event, ClientEvent::Notice { .. })));
}

#[tokio::test]
async fn a_refused_secret_fails_the_join_without_leaking_it() {
    // An engine that never joined this room answers 401: the whole join
    // fails as Refused (a rotated secret never heals), and no error text
    // or record ever carries the secret.
    let stranger = RoomEngine::start_stranger().await;
    let members = vec![member("stranger", &stranger.address)];
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let error = client
        .join_room(&invite_of(&members))
        .await
        .err()
        .expect("the join fails");

    assert_eq!(error, RoomError::Refused);
    let printed = format!("{error:?}");
    assert!(!printed.contains(SECRET), "{printed}");
    assert!(client.engines().is_empty(), "nothing was adopted");
    // No room-minted session anywhere.
    assert_eq!(stranger.sessions_labeled_pixel().len(), 0);
}

#[tokio::test]
async fn an_empty_book_is_a_shape_only_failure() {
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);
    let error = client
        .join_room(&invite_of(&[]))
        .await
        .err()
        .expect("an empty book cannot be joined");
    assert!(matches!(error, RoomError::Failed { .. }));
    let printed = format!("{error:?}");
    assert!(!printed.contains(SECRET), "{printed}");
}

#[tokio::test]
async fn a_revoked_room_session_re_heals_through_the_room() {
    // "Re-present": the engine revokes the phone's room session (the
    // eviction runbook's per-engine revoke). The phone parks; the next
    // heal pass re-presents — the engine re-mints the row for that label
    // — and the engine comes back connected, all without user action.
    let mut a = RoomEngine::start(Vec::new()).await;
    let members = vec![member("desktop", &a.address)];
    RoomStore::open(a.dir.path())
        .unwrap()
        .join(RoomInvite::new(ROOM_NAME, SECRET, members.clone()).unwrap())
        .unwrap();
    let recorder = Arc::new(Recorder::default());
    let client = client(&recorder);

    let join = client.join_room(&invite_of(&members)).await.unwrap();
    let engine_id = join.engines[0].engine_id.clone();
    wait_connected(&client, &engine_id, "the joined engine").await;
    let session_id = a.sessions_labeled_pixel()[0].id.clone();

    // The engine-side revoke: the phone's credential stops authenticating
    // — visible once the link drops and the supervisor redials (a
    // revocation only bites on a fresh handshake).
    PairingStore::open(a.dir.path())
        .unwrap()
        .revoke(&session_id)
        .unwrap();
    a.bounce_listener().await;
    support::wait_for_view(&client, &engine_id, "parked after the revoke", |view| {
        view.state
            == EngineState::Parked {
                reason: ParkReason::Revoked,
            }
    })
    .await;

    // The next trigger heals through the room: a fresh presentation
    // re-mints the session (the engine reuses the row's label slot).
    client.set_network_changed();
    wait_connected(&client, &engine_id, "the re-presented engine").await;
    let sessions = a.sessions_labeled_pixel();
    // The revoked row stays in history (the engine's own rule); the
    // re-presentation minted exactly one fresh row for the label.
    assert_eq!(
        sessions.iter().filter(|s| s.revoked_at.is_none()).count(),
        1,
        "one live row for the label"
    );
    assert_eq!(
        sessions.iter().filter(|s| s.revoked_at.is_some()).count(),
        1,
        "the revoked row stays in history"
    );

    // The fresh record crossed as a heal event (the platform persists the
    // new credential — otherwise a restart would park again).
    wait_for_event(
        &recorder,
        "the re-presented member's record",
        |event| {
            matches!(
                event,
                ClientEvent::RoomEngineJoined { engine, .. } if engine.engine_id == engine_id
            )
        },
    )
    .await;
}

#[tokio::test]
async fn the_start_heal_joins_members_of_a_restored_room() {
    // The restart path: the platform hands the stored room back on
    // `Client::new` with no engine records (the credentials were lost,
    // say); the start heal joins every member and emits the records.
    let a = RoomEngine::start(Vec::new()).await;
    let b = RoomEngine::start(Vec::new()).await;
    let members = vec![
        member("desktop", &a.address),
        member("server", &b.address),
    ];
    for engine in [&a, &b] {
        RoomStore::open(engine.dir.path())
            .unwrap()
            .join(RoomInvite::new(ROOM_NAME, SECRET, members.clone()).unwrap())
            .unwrap();
    }
    let recorder = Arc::new(Recorder::default());
    let data_dir = tempfile::tempdir().unwrap().keep();
    let restored = RoomRecord {
        room_id: room_id(SECRET),
        label: ROOM_NAME.into(),
        secret: SECRET.into(),
        members: members.clone(),
    };
    let client = Client::new_with_timings(
        test_config(&data_dir),
        Vec::new(),
        vec![restored],
        recorder.clone(),
        test_timings(),
    )
    .unwrap();

    // Both members joined on the same start (the heal rides it — no
    // timers of its own).
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if client.engines().len() >= 2 {
            break;
        }
        assert!(Instant::now() < deadline, "the members never joined");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    for view in client.engines() {
        wait_connected(&client, &view.engine_id, "connected").await;
        assert_eq!(view.room.as_ref().unwrap().room_id, room_id(SECRET));
    }
    // Both records crossed for the platform to persist (the pass pushes
    // them when it finishes, after the book push — wait, don't sample).
    for engine in client.engines() {
        wait_for_event(
            &recorder,
            "a member's persistable record",
            |event| {
                matches!(
                    event,
                    ClientEvent::RoomEngineJoined { engine: joined, .. } if joined.engine_id == engine.engine_id
                )
            },
        )
        .await;
    }
    // And the book was pushed to the members (idempotent union).
    assert_eq!(a.room_book().len(), 2);
    assert_eq!(b.room_book().len(), 2);
}

/// The test room's derived id (a local binding so closures can name it).
fn room_id_of_secret() -> String {
    room_id(SECRET)
}

fn invite_of(members: &[RoomMember]) -> String {
    RoomInvite::new(ROOM_NAME, SECRET, members.to_vec())
        .unwrap()
        .encode()
        .unwrap()
}
