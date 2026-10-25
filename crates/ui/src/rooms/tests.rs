//! Rooms: desktop flows (create/join/leave/rotate) against real engines over
//! loopback, plus the pure helpers the Settings page renders from. The
//! dialer is faked (member addresses map to loopback listeners); the
//! production dialer spawns tailcat forwarders, which this box cannot run —
//! the engine-client crate covers the same machinery with the same shape.

use super::*;
use crate::engine_registry::{EngineConnectionState, EngineRegistry};
use roboco_engine::pairing::PairingStore;
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry, rooms::RoomStore,
    tailcat::record_address,
};
use roboco_engine_client::rooms::RoomMemberSession;
use roboco_engine_client::{FileSessionStore, LocalEngine, RegistryOptions};
use roboco_proto::room::{RoomInvite, RoomMember};
use serde_json::json;
use std::{collections::HashMap, path::Path, sync::Arc};

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

/// The fake dialer: addresses map to loopback bases; the production one
/// spawns a tailcat forwarder per address.
struct MapDialer {
    bases: HashMap<String, String>,
}

impl MapDialer {
    fn new(bases: HashMap<String, String>) -> Arc<Self> {
        Arc::new(Self { bases })
    }
}

impl RoomDialer for MapDialer {
    fn dial(&self, address: &str, _data_dir: &Path) -> anyhow::Result<ResolvedMember> {
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

/// A real member engine over loopback, with the data dir its room store and
/// pairing store live in.
struct MemberEngine {
    core: EngineCore,
    listener: roboco_engine::EngineListener,
    dir: tempfile::TempDir,
}

impl MemberEngine {
    fn base(&self) -> String {
        format!("http://{}", self.listener.address)
    }

    /// The engine-side join a host performs (Settings or the CLI).
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
            .filter(|session| session.label == roboco_engine_client::rooms::DESKTOP_LABEL)
            .collect()
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

/// The desktop registry: the local engine over the in-memory transport, the
/// session store in the same data dir the app uses (the desktop invariant:
/// one data dir for app and engine).
async fn desktop_registry(local_dir: &Path) -> EngineRegistry {
    let local = core(local_dir);
    let client = Arc::new(roboco_rpc::memory_client(local.rpc_service()));
    let info: roboco_proto::EngineInfo = client
        .call_as(roboco_rpc::methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    EngineRegistry::open_with(RegistryOptions {
        data_dir: local_dir.to_path_buf(),
        sessions: Arc::new(FileSessionStore::new(
            local_dir.join("paired-engines-v1.json"),
        )),
        local: Some(LocalEngine {
            info,
            client,
            reconnect: None,
        }),
        resolvers: Vec::new(),
    })
    .await
    .unwrap()
}

async fn wait_connected(registry: &EngineRegistry, engine_count: usize) {
    let mut changes = registry.watch();
    tokio::time::timeout(std::time::Duration::from_secs(20), async {
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

/// The desktop joins a room: the secret lands in the 0600 client file, the
/// local engine joins through its own room store, every other member is
/// presented to and adopted as an ordinary paired engine, and books union.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn joining_persists_the_secret_joins_the_local_engine_and_adopts_members() {
    let local_dir = tempfile::tempdir().unwrap();
    let registry = desktop_registry(local_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![member("desktop", "tcLOCAL"), member("server", "tcB")],
    )
    .unwrap();
    b.join(&invite);
    // The local engine serves a tailcat route (its recorded publication).
    record_address(local_dir.path(), "tcLOCAL").unwrap();

    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let ops = RoomOps::with_dialer(
        local_dir.path().to_path_buf(),
        registry.clone(),
        MapDialer::new(bases),
    );

    let outcome = ops.join(&invite.encode().unwrap()).await.unwrap();
    assert!(outcome.complete(), "the only remote member joined");

    // The client record: the secret (0600 at rest) plus one adopted session.
    let rooms = ops.load_rooms();
    assert_eq!(rooms.len(), 1);
    assert_eq!(rooms[0].secret, SECRET);
    assert_eq!(rooms[0].sessions.len(), 1);
    assert_eq!(rooms[0].sessions[0].address, "tcB");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(local_dir.path().join("rooms-v1.json"))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600, "the rooms file must be owner-only");
    }

    // The desktop engine itself joined: its room store holds the verifier
    // and the book (ticket-01's surface, driven from Settings).
    let records = RoomStore::open(local_dir.path()).unwrap().list().unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].id, rooms[0].id);
    assert_eq!(records[0].book.members.len(), 2);

    // The member minted exactly one desktop session, and the registry
    // adopted it as an ordinary paired engine.
    assert_eq!(b.desktop_sessions().len(), 1);
    wait_connected(&registry, 2).await;
    assert_eq!(registry.snapshot().engines.len(), 2);
}

/// Leaving revokes this client's sessions on every member, forgets the
/// registry entries the room adopted, and removes the room from the client
/// record and the local engine's store.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn leaving_revokes_forgets_and_removes_everywhere() {
    let local_dir = tempfile::tempdir().unwrap();
    let registry = desktop_registry(local_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![member("desktop", "tcLOCAL"), member("server", "tcB")],
    )
    .unwrap();
    b.join(&invite);
    record_address(local_dir.path(), "tcLOCAL").unwrap();
    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let ops = RoomOps::with_dialer(
        local_dir.path().to_path_buf(),
        registry.clone(),
        MapDialer::new(bases),
    );
    let outcome = ops.join(&invite.encode().unwrap()).await.unwrap();
    let room_id = outcome.room.id.clone();
    wait_connected(&registry, 2).await;

    let left = ops.leave(&room_id).await.unwrap();
    // The revoke reached the member.
    assert_eq!(left.revoked, vec![("server".to_owned(), true)]);
    assert!(
        left.engine_left,
        "the local engine held the room and dropped it"
    );
    let sessions = b.desktop_sessions();
    assert_eq!(sessions.len(), 1);
    assert!(sessions[0].revoked_at.is_some());
    // The registry entries the room adopted are gone; the room record is
    // gone; the local engine's store is empty.
    assert_eq!(registry.snapshot().engines.len(), 1);
    assert!(ops.load_rooms().is_empty());
    assert!(
        RoomStore::open(local_dir.path())
            .unwrap()
            .list()
            .unwrap()
            .is_empty()
    );
    // The room itself lives on the member engine: leaving is not eviction
    // — a room gates future introductions, and this client's revokes only
    // cut its own sessions (rotate to evict, ADR 0011).
    assert!(matches!(
        RoomStore::open(b.dir.path())
            .unwrap()
            .present(SECRET, "Pixel")
            .unwrap(),
        roboco_engine::rooms::PresentationOutcome::Granted(_)
    ));
}

/// Rotation: probes who still honors the old secret, revokes this client's
/// old sessions, joins the local engine into the new room, and keeps the
/// local old verifier until the user drops it. The member re-presents after
/// its host consumes the new invite — a Reconnect pass then records it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rotating_probes_revokes_and_guides_the_re_invite() {
    let local_dir = tempfile::tempdir().unwrap();
    let registry = desktop_registry(local_dir.path()).await;
    let b = member_engine().await;
    let invite = RoomInvite::new(
        "Home fleet",
        SECRET,
        vec![member("desktop", "tcLOCAL"), member("server", "tcB")],
    )
    .unwrap();
    b.join(&invite);
    record_address(local_dir.path(), "tcLOCAL").unwrap();
    let mut bases = HashMap::new();
    bases.insert("tcB".to_owned(), b.base());
    let ops = RoomOps::with_dialer(
        local_dir.path().to_path_buf(),
        registry.clone(),
        MapDialer::new(bases),
    );
    let outcome = ops.join(&invite.encode().unwrap()).await.unwrap();
    let old_room_id = outcome.room.id.clone();
    wait_connected(&registry, 2).await;

    let rotated = ops.rotate(&old_room_id).await.unwrap();
    let new_room_id = rotated.checklist.new_room_id.clone();
    assert_ne!(new_room_id, old_room_id, "rotation mints a new room id");

    // The checklist: the member has not re-presented yet (its host has not
    // consumed the new invite), the old secret still works there, this
    // client's old session is revoked, and this engine still holds the old
    // verifier — the guided drop.
    let checklist = &rotated.checklist;
    assert_eq!(checklist.rows.len(), 2);
    let server = checklist
        .rows
        .iter()
        .find(|row| row.label == "server")
        .expect("the member row");
    assert!(!server.re_presented);
    assert_eq!(server.old_secret, OldSecretStatus::StillAccepted);
    assert!(server.revoked, "this client's old session was revoked");
    assert!(checklist.local_old_verifier);
    assert!(!checklist.complete());
    assert!(checklist.hint().contains("roboco engine room leave"));
    // The old session died on the member.
    assert!(b.desktop_sessions()[0].revoked_at.is_some());
    // The old client record is retired; the new one is saved with no
    // sessions yet (nobody can welcome a secret minted moments ago).
    let rooms = ops.load_rooms();
    assert_eq!(rooms.len(), 1);
    assert_eq!(rooms[0].id, new_room_id);
    assert!(rooms[0].sessions.is_empty());

    // The member's host now consumes the new invite; a Reconnect pass
    // re-presents and the checklist's re-presented column fills in.
    b.join(&rotated.invite);
    let rejoined = ops.sync(&new_room_id).await.unwrap().expect("room saved");
    assert!(rejoined.complete());
    assert_eq!(rejoined.room.sessions.len(), 1);
    let refreshed = checklist.refresh_re_presented(&rejoined.room.members, &rejoined.room.sessions);
    let server = refreshed
        .rows
        .iter()
        .find(|row| row.label == "server")
        .expect("the member row");
    assert!(server.re_presented);
    assert_eq!(server.old_secret, OldSecretStatus::StillAccepted);
    assert_eq!(
        b.desktop_sessions().len(),
        2,
        "one revoked old session, one live new one"
    );

    // Dropping the old room on this engine: the local old verifier goes, the
    // new one stays.
    assert!(ops.local_engine_leave(&old_room_id).await.unwrap());
    let records = RoomStore::open(local_dir.path()).unwrap().list().unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].id, new_room_id);
    assert!(!ops.local_engine_holds(&old_room_id).await);
}

/// Creating a room: the book is seeded from the local engine's recorded
/// publication (its Tailcat route), the local engine joins, and an engine
/// without a Tailcat route is listed as unavailable rather than failing the
/// create.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn creating_seeds_the_book_from_the_local_engine() {
    let local_dir = tempfile::tempdir().unwrap();
    let registry = desktop_registry(local_dir.path()).await;
    record_address(local_dir.path(), "tcLOCAL").unwrap();
    let ops = RoomOps::with_dialer(
        local_dir.path().to_path_buf(),
        registry.clone(),
        MapDialer::new(HashMap::new()),
    );

    let created = ops.create("Home fleet", &[], "My desktop").await.unwrap();
    // The book: exactly the local engine, labeled for other clients.
    assert_eq!(created.invite.members.len(), 1);
    assert_eq!(created.invite.members[0].label, "My desktop");
    assert_eq!(created.invite.members[0].address, "tcLOCAL");
    assert!(created.unavailable.is_empty());
    assert!(created.qr_svg.is_some(), "a one-member invite fits a QR");
    assert!(created.encoded.starts_with("roboco-room:"));
    // The local engine joined through its store.
    let records = RoomStore::open(local_dir.path()).unwrap().list().unwrap();
    assert_eq!(records.len(), 1);
    // The record is saved.
    assert_eq!(ops.load_rooms().len(), 1);

    // No local Tailcat route: create says so instead of minting a bookless
    // room.
    let bare_dir = tempfile::tempdir().unwrap();
    let bare_registry = desktop_registry(bare_dir.path()).await;
    let bare_ops = RoomOps::with_dialer(
        bare_dir.path().to_path_buf(),
        bare_registry.clone(),
        MapDialer::new(HashMap::new()),
    );
    let error = bare_ops
        .create("Home fleet", &[], "My desktop")
        .await
        .unwrap_err()
        .to_string();
    assert!(
        error.contains("no Tailcat route"),
        "the refusal names the missing route: {error}"
    );

    // A selected engine without a Tailcat route lands in `unavailable`,
    // never in the book: the address comes from the engine's own invite
    // mint (`CreateTailcatInvite`), which that engine cannot serve.
    record_address(bare_dir.path(), "tcBARE").unwrap();
    let remote = member_engine().await;
    let code = PairingStore::open(remote.dir.path())
        .unwrap()
        .create_code("create test", 300)
        .unwrap();
    let key = bare_registry
        .pair(
            &format!(
                "http://{}/pair#token={}",
                remote.listener.address, code.credential
            ),
            "Test desktop",
        )
        .await
        .unwrap();
    let created = bare_ops
        .create("Fleet", &[MemberPick::Engine(key)], "My desktop")
        .await
        .unwrap();
    assert_eq!(
        created.invite.members.len(),
        1,
        "only the local engine is in the book"
    );
    assert_eq!(created.unavailable.len(), 1);
    assert!(
        created.unavailable[0].contains("no Tailcat route"),
        "the unavailable reason is fixed text: {}",
        created.unavailable[0]
    );
    assert!(
        !created.unavailable[0].starts_with("tc"),
        "no address leaks into the unavailable reason"
    );
}

#[test]
fn rotate_rows_is_a_pure_projection_of_the_probe_facts() {
    let members = vec![member("server", "tcB"), member("laptop", "tcC")];
    let re_presented = ["tcB".to_owned()].into_iter().collect();
    let old_secret = HashMap::from([
        ("tcB".to_owned(), OldSecretStatus::StillAccepted),
        ("tcC".to_owned(), OldSecretStatus::Refused),
    ]);
    let revoked = ["tcB".to_owned()].into_iter().collect();
    let rows = rotate_rows(&members, &re_presented, &old_secret, &revoked);
    assert_eq!(rows.len(), 2);
    assert!(rows[0].re_presented);
    assert_eq!(rows[0].old_secret, OldSecretStatus::StillAccepted);
    assert!(rows[0].revoked);
    assert!(!rows[1].re_presented);
    assert_eq!(rows[1].old_secret, OldSecretStatus::Refused);
    assert!(!rows[1].revoked);
    // Captions describe states, never secrets or addresses.
    let (first, second) = rows[0].status_fragments();
    assert_eq!(first, "Re-presented with the new secret");
    assert_eq!(
        second,
        "Old secret still accepted — drop the old room on that host"
    );
    let (first, second) = rows[1].status_fragments();
    assert_eq!(first, "Waiting to re-present");
    assert_eq!(second, "Old secret no longer accepted");
}

#[test]
fn the_checklist_completes_only_when_everything_re_presented_and_dropped() {
    let members = vec![member("server", "tcB")];
    let empty: std::collections::HashSet<String> =
        std::collections::HashSet::from_iter(std::iter::empty());
    let unchecked: HashMap<String, OldSecretStatus> = HashMap::new();
    let rows = rotate_rows(&members, &empty, &unchecked, &empty);
    let checklist = RotateChecklist {
        old_room_id: "old".into(),
        new_room_id: "new".into(),
        local_old_verifier: true,
        rows: rows.clone(),
    };
    assert!(!checklist.complete());
    assert!(checklist.hint().contains("drop it below"));

    let mut done = checklist.clone();
    done.local_old_verifier = false;
    done.rows = rotate_rows(
        &members,
        &["tcB".to_owned()].into_iter().collect(),
        &HashMap::from([("tcB".to_owned(), OldSecretStatus::Refused)]),
        &empty,
    );
    assert!(done.complete());
    assert!(done.hint().contains("roboco engine room leave"));
}

#[test]
fn engine_labels_fall_back_to_identity_not_secrets() {
    let snapshot = RegistrySnapshot::default();
    // An empty registry labels the local member with the generic label.
    assert_eq!(local_member_label(&snapshot), "Desktop");
    let info = roboco_proto::EngineInfo {
        device_id: "device-1".into(),
        workspace_scope: roboco_proto::WorkspaceScope::Local,
        cursor_sdk_version: None,
        capabilities: Vec::new(),
        version: None,
        protocol_version: None,
        min_client_protocol_version: None,
    };
    let mut snapshot = RegistrySnapshot::default();
    snapshot
        .engines
        .push(crate::engine_registry::EngineSnapshot {
            key: EngineKey::local(),
            info,
            state: crate::engine_registry::EngineConnectionState::Connected,
            last_error: None,
            generation: 1,
            chats: Vec::new(),
            spaces: Vec::new(),
            devices: Vec::new(),
            sessions: Vec::new(),
            chats_loaded: false,
            spaces_loaded: false,
        });
    // No devices landed yet: the label falls back to the generic one.
    assert_eq!(local_member_label(&snapshot), "Desktop");
    // A key the snapshot does not know labels as an engine, never an
    // address or credential.
    assert_eq!(engine_label(&snapshot, &EngineKey("gone".into())), "Engine");
}
