//! Fleet follow on the desktop: `RoomOps` flows against real engines over
//! loopback (lazy mint, push, opt-out, dedupe, late-join heal, paste/switch,
//! leave), plus the pure survey/caption helpers the Fleet card renders from
//! and the C4a probe guard. The dialer and the address probe are faked
//! (test engines cannot run Tailcat); the engine-client crate covers the
//! same machinery with the same shape.

use super::*;
use crate::engine_registry::{EngineConnectionState, EngineRegistry, EngineSnapshot};
use roboco_engine::pairing::PairingStore;
use roboco_engine::{
    EngineCore, EngineProfile, HarnessId, HarnessRegistry, rooms::RoomStore,
    tailcat::record_address,
};
use roboco_engine_client::rooms::DESKTOP_LABEL;
use roboco_engine_client::{FileSessionStore, LocalEngine, RegistryOptions};
use roboco_proto::room::{RoomInvite, RoomMember};
use roboco_rpc::{RpcReply, methods};
use serde_json::json;
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

const SECRET: &str = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";
const OTHER_SECRET: &str = "Zr8kQ2mW5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

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
/// spawns a tailcat forwarder per address. Dials are counted.
struct MapDialer {
    bases: Mutex<HashMap<String, String>>,
    count: AtomicUsize,
}

impl MapDialer {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            bases: Mutex::new(HashMap::new()),
            count: AtomicUsize::new(0),
        })
    }

    fn map(&self, address: &str, base: String) {
        self.bases.lock().unwrap().insert(address.into(), base);
    }
}

impl RoomDialer for MapDialer {
    fn dial(&self, address: &str, _data_dir: &Path) -> anyhow::Result<ResolvedMember> {
        self.count.fetch_add(1, Ordering::SeqCst);
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
        })
    }
}

/// The fake probe: engines (by device id) mapped to an address serve their
/// Tailcat route; every other engine reads as Tailcat off.
struct MapProbe {
    addresses: Mutex<HashMap<String, String>>,
    address_calls: AtomicUsize,
}

impl MapProbe {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            addresses: Mutex::new(HashMap::new()),
            address_calls: AtomicUsize::new(0),
        })
    }

    fn serve(&self, device_id: &str, address: &str) {
        self.addresses
            .lock()
            .unwrap()
            .insert(device_id.into(), address.into());
    }
}

#[async_trait::async_trait]
impl FleetProbe for MapProbe {
    async fn shape(&self, target: &EngineTarget) -> TailcatShape {
        if self
            .addresses
            .lock()
            .unwrap()
            .contains_key(&target.engine_info().device_id)
        {
            TailcatShape::Serves
        } else {
            TailcatShape::Off
        }
    }

    async fn address(&self, target: &EngineTarget) -> anyhow::Result<String> {
        self.address_calls.fetch_add(1, Ordering::SeqCst);
        self.addresses
            .lock()
            .unwrap()
            .get(&target.engine_info().device_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("no route"))
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

    fn device_id(&self) -> String {
        self.core.device_id.clone()
    }

    fn store(&self) -> RoomStore {
        RoomStore::open(self.dir.path()).unwrap()
    }

    /// The engine-side join a host performs (CLI).
    fn join(&self, invite: &RoomInvite) {
        self.store().join(invite.clone()).unwrap();
    }

    fn desktop_sessions(&self) -> Vec<roboco_proto::PairedSession> {
        PairingStore::open(self.dir.path())
            .unwrap()
            .list_sessions()
            .unwrap()
            .into_iter()
            .filter(|session| session.label == DESKTOP_LABEL)
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
/// session store in the same data dir the app uses (one data dir for app
/// and engine). The core is returned so it outlives the test body.
async fn desktop_registry(local_dir: &Path) -> (EngineRegistry, EngineCore) {
    let local = core(local_dir);
    let client = Arc::new(roboco_rpc::memory_client(local.rpc_service()));
    let info: roboco_proto::EngineInfo = client
        .call_as(methods::ENGINE_INFO, json!({}))
        .await
        .unwrap();
    let registry = EngineRegistry::open_with(RegistryOptions {
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
    .unwrap();
    (registry, local)
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

struct Desktop {
    dir: tempfile::TempDir,
    registry: EngineRegistry,
    _core: EngineCore,
    dialer: Arc<MapDialer>,
    probe: Arc<MapProbe>,
}

impl Desktop {
    async fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let (registry, core) = desktop_registry(dir.path()).await;
        record_address(dir.path(), "tcLOCAL").unwrap();
        Self {
            dir,
            registry,
            _core: core,
            dialer: MapDialer::new(),
            probe: MapProbe::new(),
        }
    }

    fn ops(&self) -> RoomOps {
        RoomOps::with_dialer(
            self.dir.path().to_path_buf(),
            self.registry.clone(),
            self.dialer.clone(),
        )
        .with_probe(self.probe.clone())
    }

    fn store(&self) -> RoomStore {
        RoomStore::open(self.dir.path()).unwrap()
    }

    /// Pair `engine` by hand and let it serve a (fake) Tailcat route.
    async fn pair_serving(&self, engine: &MemberEngine, address: &str) -> EngineKey {
        let key = pair_by_hand(&self.registry, engine).await;
        self.probe.serve(&engine.device_id(), address);
        self.dialer.map(address, engine.base());
        key
    }
}

fn row<'a>(report: &'a FleetReport, device_id: &str) -> &'a FleetRow {
    report
        .rows
        .iter()
        .find(|row| row.device_id.as_deref() == Some(device_id))
        .unwrap_or_else(|| panic!("no row for {device_id}: {report:?}"))
}

/// The first fleet event mints the one fleet room (kind = fleet, secret held
/// by the local engine), pushes it to the paired engine with `RoomJoin`, and
/// adds nothing to the registry; a second pass is a no-op (no re-mint, no
/// session churn, no duplicate entries) — and nothing ever touches an
/// engine's remote-access settings (C4a).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_fleet_is_minted_lazily_and_pushed_idempotently() {
    let desktop = Desktop::new().await;
    let ops = desktop.ops();

    // No other engines: nothing to share, so nothing is minted.
    let report = ops.sync().await.unwrap();
    assert!(!report.exists);
    assert!(report.rows.is_empty());
    assert!(desktop.store().fleet_room().unwrap().is_none());

    // Pairing an engine that serves its Tailcat route is the fleet event.
    let b = member_engine().await;
    let key = desktop.pair_serving(&b, "tcB").await;
    wait_connected(&desktop.registry, 2).await;
    let report = ops.sync().await.unwrap();
    assert!(report.exists);
    assert_eq!(row(&report, &b.device_id()).state, FleetState::In);
    assert_eq!(row(&report, &b.device_id()).key, Some(key.clone()));

    // The local engine holds the fleet: kind = fleet, secret retained, the
    // book carrying this machine and b — exactly what GetFleetInvite serves.
    let fleet = desktop.store().fleet_room().unwrap().expect("minted");
    assert_eq!(fleet.kind, RoomKind::Fleet);
    assert_eq!(fleet.label, FLEET_NAME);
    let invite = desktop
        .store()
        .fleet_invite(&fleet.id)
        .unwrap()
        .expect("the secret is retained");
    assert!(invite.is_fleet());
    let addresses: Vec<_> = invite.members.iter().map(|m| m.address.clone()).collect();
    assert!(addresses.contains(&"tcLOCAL".to_owned()));
    assert!(addresses.contains(&"tcB".to_owned()));
    // b joined through the RoomJoin push (and retains the secret too).
    let b_fleet = b.store().fleet_room().unwrap().expect("pushed");
    assert_eq!(b_fleet.id, fleet.id);
    assert!(b.store().fleet_invite(&fleet.id).unwrap().is_some());

    // The hand-paired entry was linked, never presented to or duplicated.
    assert_eq!(desktop.registry.snapshot().engines.len(), 2);
    assert!(b.desktop_sessions().is_empty(), "no room session minted");
    assert_eq!(desktop.dialer.count.load(Ordering::SeqCst), 0);
    assert_eq!(desktop.probe.address_calls.load(Ordering::SeqCst), 1);

    // Idempotent re-push (app open again).
    let again = ops.sync().await.unwrap();
    assert_eq!(row(&again, &b.device_id()).state, FleetState::In);
    assert_eq!(desktop.store().fleet_room().unwrap().unwrap().id, fleet.id);
    assert_eq!(b.store().list().unwrap().len(), 1, "one row on b");
    assert_eq!(desktop.registry.snapshot().engines.len(), 2);
    assert!(b.desktop_sessions().is_empty());
    assert_eq!(
        desktop.probe.address_calls.load(Ordering::SeqCst),
        1,
        "the address is cached, never minted twice"
    );
    let rooms = ops.rooms_file().load().unwrap();
    assert_eq!(rooms.len(), 1);
    assert!(rooms[0].is_fleet());

    // C4a: fleet code never wrote b's remote-access settings.
    assert!(!b.dir.path().join("remote-access.json").exists());
}

/// An opted-out engine is never pushed and never contributes its address;
/// switching it back on brings it in on the next pass.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn opted_out_engines_are_skipped_until_switched_back_on() {
    let desktop = Desktop::new().await;
    let ops = desktop.ops();
    let b = member_engine().await;
    let c = member_engine().await;
    desktop.pair_serving(&b, "tcB").await;
    desktop.pair_serving(&c, "tcC").await;
    wait_connected(&desktop.registry, 3).await;
    ops.set_opt_out(&c.device_id(), true).unwrap();

    let report = ops.sync().await.unwrap();
    assert_eq!(row(&report, &b.device_id()).state, FleetState::In);
    assert_eq!(row(&report, &c.device_id()).state, FleetState::OptedOut);
    assert!(
        c.store().fleet_room().unwrap().is_none(),
        "c was never pushed"
    );
    let fleet = desktop.store().fleet_room().unwrap().unwrap();
    assert!(
        !fleet.book.members.iter().any(|m| m.address == "tcC"),
        "an opted-out engine's address stays out of the book"
    );
    // The preference persists beside rooms-v1.json.
    assert!(desktop.dir.path().join(FLEET_FILE).exists());
    assert!(
        ops.prefs_file()
            .load()
            .unwrap()
            .is_opted_out(&c.device_id())
    );

    ops.set_opt_out(&c.device_id(), false).unwrap();
    let report = ops.sync().await.unwrap();
    assert_eq!(row(&report, &c.device_id()).state, FleetState::In);
    assert_eq!(c.store().fleet_room().unwrap().unwrap().id, fleet.id);
}

/// A book member this desktop already paired by hand is reached through its
/// existing entry: the presentation reattaches it (same key), so the
/// registry never grows a second entry for it, and leave never forgets it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn joining_dedupes_a_member_already_paired_by_hand() {
    let desktop = Desktop::new().await;
    let ops = desktop.ops();
    let b = member_engine().await;
    // Paired by hand, but this desktop's probe does not see its Tailcat
    // route, so the pasted book is the only place its address comes from.
    let key = pair_by_hand(&desktop.registry, &b).await;
    desktop.dialer.map("tcB", b.base());
    wait_connected(&desktop.registry, 2).await;

    // A fleet minted elsewhere, which b already joined.
    let invite = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&invite);
    ops.join(&invite.encode().unwrap(), false).await.unwrap();

    assert_eq!(desktop.registry.snapshot().engines.len(), 2, "no duplicate");
    let room = ops.rooms_file().load().unwrap().remove(0);
    let session = room
        .sessions
        .iter()
        .find(|session| session.address == "tcB")
        .expect("b is reached");
    assert_eq!(session.engine_key, key.0, "through the hand-paired entry");
    assert!(!session.adopted(), "the fleet does not own it");
    wait_connected(&desktop.registry, 2).await;

    // A re-run does not dial again (the entry is live).
    let dials = desktop.dialer.count.load(Ordering::SeqCst);
    ops.sync().await.unwrap();
    assert_eq!(desktop.dialer.count.load(Ordering::SeqCst), dials);
    assert_eq!(desktop.registry.snapshot().engines.len(), 2);

    // Leaving never forgets an engine paired by hand.
    assert!(ops.leave().await.unwrap());
    assert!(desktop.registry.target(&key).is_ok());
    assert!(desktop.store().fleet_room().unwrap().is_none());
}

/// A member that joined the fleet after this desktop's last pass (its host
/// ran the CLI join; another client's heal put its address in b's book) is
/// found through the book union, presented to, and adopted — and the
/// unioned book reaches every member.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_late_joiner_is_healed_in_on_the_next_pass() {
    let desktop = Desktop::new().await;
    let ops = desktop.ops();
    let b = member_engine().await;
    desktop.pair_serving(&b, "tcB").await;
    wait_connected(&desktop.registry, 2).await;
    ops.sync().await.unwrap();
    let fleet = desktop.store().fleet_room().unwrap().unwrap();
    let invite = desktop.store().fleet_invite(&fleet.id).unwrap().unwrap();

    // c joins late, out of band; only b's book learns about it.
    let c = member_engine().await;
    c.join(&invite);
    b.store()
        .merge_book(&fleet.id, vec![member("late", "tcC")])
        .unwrap();
    desktop.dialer.map("tcC", c.base());

    let report = ops.sync().await.unwrap();
    wait_connected(&desktop.registry, 3).await;
    assert_eq!(desktop.registry.snapshot().engines.len(), 3);
    assert_eq!(row(&report, &c.device_id()).state, FleetState::In);
    assert_eq!(c.desktop_sessions().len(), 1, "one presentation session");
    // Every book now carries the union.
    let local_book = desktop.store().fleet_room().unwrap().unwrap().book;
    assert!(local_book.members.iter().any(|m| m.address == "tcC"));
    let c_book = c
        .store()
        .book_verified(&fleet.id, &invite.secret)
        .unwrap()
        .unwrap();
    assert!(c_book.members.iter().any(|m| m.address == "tcB"));
    assert!(c_book.members.iter().any(|m| m.address == "tcLOCAL"));

    // Healing again changes nothing.
    ops.sync().await.unwrap();
    assert_eq!(desktop.registry.snapshot().engines.len(), 3);
    assert_eq!(c.desktop_sessions().len(), 1, "no session churn");
}

/// The paste flow: a solo fleet (lazily minted by "Show fleet invite") is
/// left silently for the pasted one; a fleet shared with other engines asks
/// first, and the confirmed switch leaves it — revoking and forgetting only
/// what it adopted. Plain-room invites and junk get fixed text.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pasting_switches_from_a_solo_fleet_and_asks_before_leaving_a_shared_one() {
    let desktop = Desktop::new().await;
    let ops = desktop.ops();

    // "Show fleet invite" with nobody else around mints a solo fleet.
    let shown = ops.fleet_invite().await.unwrap();
    assert!(shown.encoded.starts_with("roboco-room:"));
    assert!(shown.qr_svg.is_some());
    let solo = RoomInvite::decode(&shown.encoded).unwrap();
    assert!(solo.is_fleet());
    assert!(!format!("{shown:?}").contains(&solo.secret));
    assert!(!format!("{shown:?}").contains(&shown.encoded));

    // PC 2: paste a fleet minted elsewhere (b is in it).
    let b = member_engine().await;
    let fleet = RoomInvite::fleet("Fleet", SECRET, vec![member("server", "tcB")]).unwrap();
    b.join(&fleet);
    desktop.dialer.map("tcB", b.base());
    let report = ops.join(&fleet.encode().unwrap(), false).await.unwrap();
    assert!(report.exists);
    assert_eq!(
        desktop.store().fleet_room().unwrap().unwrap().id,
        fleet.room_id(),
        "the solo fleet was left for the pasted one"
    );
    assert_eq!(desktop.store().list().unwrap().len(), 1);
    wait_connected(&desktop.registry, 2).await;
    assert_eq!(b.desktop_sessions().len(), 1);
    assert_eq!(row(&report, &b.device_id()).state, FleetState::In);

    // Another fleet while this one is shared: asks first, changes nothing.
    let other = RoomInvite::fleet("Fleet", OTHER_SECRET, Vec::new()).unwrap();
    let encoded = other.encode().unwrap();
    let error = ops.join(&encoded, false).await.unwrap_err();
    assert!(error.downcast_ref::<FleetSwitchNeeded>().is_some());
    let text = error.to_string();
    assert!(!text.contains(OTHER_SECRET) && !text.contains(&encoded));
    assert_eq!(
        desktop.store().fleet_room().unwrap().unwrap().id,
        fleet.room_id()
    );

    // Confirmed: the old fleet's adopted entry is revoked and forgotten.
    ops.join(&encoded, true).await.unwrap();
    assert_eq!(
        desktop.store().fleet_room().unwrap().unwrap().id,
        other.room_id()
    );
    assert_eq!(desktop.registry.snapshot().engines.len(), 1);
    assert!(b.desktop_sessions()[0].revoked_at.is_some());
    let rooms = ops.rooms_file().load().unwrap();
    assert_eq!(rooms.len(), 1);
    assert_eq!(rooms[0].id, other.room_id());

    // Re-pasting the same fleet is idempotent.
    ops.join(&encoded, false).await.unwrap();
    assert_eq!(desktop.store().list().unwrap().len(), 1);

    // A plain-room invite and junk: fixed text, nothing quoted.
    let plain = RoomInvite::new("Room", SECRET, Vec::new())
        .unwrap()
        .encode()
        .unwrap();
    let error = ops.join(&plain, false).await.unwrap_err().to_string();
    assert_eq!(error, PLAIN_ROOM_INVITE);
    let junk = format!("roboco-room:{SECRET}");
    let error = ops.join(&junk, false).await.unwrap_err().to_string();
    assert!(!error.contains(SECRET), "{error}");
}

fn engine_snapshot(key: &str, device_id: &str, caps: &[&str], connected: bool) -> EngineSnapshot {
    let mut info = roboco_proto::EngineInfo::current(
        device_id.into(),
        roboco_proto::WorkspaceScope::Local,
        None,
    );
    info.capabilities = caps.iter().map(|cap| cap.to_string()).collect();
    EngineSnapshot {
        key: EngineKey(key.into()),
        info,
        state: if connected {
            EngineConnectionState::Connected
        } else {
            EngineConnectionState::Reconnecting
        },
        last_error: None,
        generation: 1,
        chats: Vec::new(),
        spaces: Vec::new(),
        devices: Vec::new(),
        sessions: Vec::new(),
        chats_loaded: false,
        spaces_loaded: false,
    }
}

/// The survey decides what it can from the snapshot alone: an engine without
/// rooms-v2 reads "update that engine" and is never probed or pushed (no
/// error anywhere); opted-out and offline engines are skipped; the local
/// engine and a second entry for one identity never get rows.
#[test]
fn the_survey_skips_old_opted_out_offline_and_duplicate_engines() {
    let v2 = capabilities::ROOMS_V2;
    let snapshot = RegistrySnapshot {
        engines: vec![
            engine_snapshot("local", "dev-local", &[v2], true),
            engine_snapshot("k-old", "dev-old", &[capabilities::ROOMS_V1], true),
            engine_snapshot("k-new", "dev-new", &[v2], true),
            engine_snapshot("k-dup", "dev-new", &[v2], true),
            engine_snapshot("k-out", "dev-out", &[v2], true),
            engine_snapshot("k-off", "dev-off", &[v2], false),
            engine_snapshot("k-self", "dev-local", &[v2], true),
        ],
        configuration_error: None,
    };
    let prefs = FleetPrefs {
        opted_out: ["dev-out".to_owned()].into_iter().collect(),
    };
    let (rows, candidates) = survey(&snapshot, &prefs);
    let state = |device: &str| {
        rows.iter()
            .find(|row| row.device_id.as_deref() == Some(device))
            .map(|row| row.state)
    };
    assert_eq!(state("dev-old"), Some(FleetState::NeedsUpdate));
    assert_eq!(state("dev-out"), Some(FleetState::OptedOut));
    assert_eq!(
        state("dev-off"),
        Some(FleetState::NotInYet(NotInYet::Offline))
    );
    assert_eq!(state("dev-local"), None, "the local engine is not a row");
    assert_eq!(rows.len(), 4, "one row per identity: {rows:?}");
    assert_eq!(
        candidates
            .iter()
            .map(|candidate| candidate.device_id.as_str())
            .collect::<Vec<_>>(),
        vec!["dev-new"],
        "only the rooms-v2, opted-in, connected engine is probed and pushed"
    );
    assert_eq!(
        FleetState::NeedsUpdate.caption(),
        "Update that engine to add it"
    );
    assert!(!FleetState::NeedsUpdate.retryable());
}

/// Every caption is plain language: no room/verifier/session jargon, never
/// a secret; only "not in yet" rows offer Retry.
#[test]
fn fleet_captions_are_plain_language() {
    let states = [
        FleetState::In,
        FleetState::NotInYet(NotInYet::Offline),
        FleetState::NotInYet(NotInYet::TailcatOff),
        FleetState::NotInYet(NotInYet::Unconfirmed),
        FleetState::NeedsUpdate,
        FleetState::OptedOut,
        FleetState::OtherFleet,
    ];
    for state in states {
        let caption = state.caption().to_lowercase();
        for jargon in ["room", "verifier", "session", "secret", "present", "member"] {
            assert!(!caption.contains(jargon), "{caption}");
        }
        assert_eq!(state.retryable(), matches!(state, FleetState::NotInYet(_)));
    }
    assert_eq!(FleetState::In.caption(), "In");
    assert!(
        FleetState::NotInYet(NotInYet::Offline)
            .caption()
            .starts_with("Not in yet")
    );
}

/// App open and new pairings are fleet events: the first connect of each
/// engine (local included) schedules a pass; reconnects do not.
#[test]
fn fleet_events_fire_once_per_engine_connect() {
    let mut sync = FleetSync::default();
    let v2 = capabilities::ROOMS_V2;
    let mut snapshot = RegistrySnapshot {
        engines: vec![engine_snapshot("local", "dev-local", &[v2], true)],
        configuration_error: None,
    };
    assert!(sync.note_connected(&snapshot), "app open");
    assert!(!sync.note_connected(&snapshot));
    snapshot
        .engines
        .push(engine_snapshot("k-b", "dev-b", &[v2], false));
    assert!(!sync.note_connected(&snapshot), "not connected yet");
    snapshot.engines[1].state = EngineConnectionState::Connected;
    assert!(sync.note_connected(&snapshot), "a new pairing connected");
    snapshot.engines[1].state = EngineConnectionState::Reconnecting;
    assert!(!sync.note_connected(&snapshot));
    snapshot.engines[1].state = EngineConnectionState::Connected;
    assert!(
        !sync.note_connected(&snapshot),
        "a reconnect is not an event"
    );
}

#[test]
fn engine_labels_fall_back_to_identity_not_secrets() {
    let snapshot = RegistrySnapshot::default();
    assert_eq!(local_member_label(&snapshot), "Desktop");
    let snapshot = RegistrySnapshot {
        engines: vec![engine_snapshot("local", "dev-local", &[], true)],
        configuration_error: None,
    };
    assert_eq!(local_member_label(&snapshot), "Desktop");
    assert_eq!(engine_label(&snapshot, &EngineKey("gone".into())), "Engine");
}

#[test]
fn fleet_prefs_round_trip_and_stay_owner_only() {
    let dir = tempfile::tempdir().unwrap();
    let file = FleetPrefsFile::new(dir.path().join(FLEET_FILE));
    assert_eq!(file.load().unwrap(), FleetPrefs::default());
    let prefs = FleetPrefs {
        opted_out: ["dev-1".to_owned()].into_iter().collect(),
    };
    file.save(&prefs).unwrap();
    assert_eq!(file.load().unwrap(), prefs);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(dir.path().join(FLEET_FILE))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600);
    }
}

/// A recording stand-in engine: answers `EngineInfo`, `GetRemoteAccess`
/// with a caller-chosen snapshot, and — only when armed — mints
/// `CreateTailcatInvite` for a caller-chosen address. Every method asked is
/// recorded, so tests prove the probe never sends the mint (the
/// settings-mutating RPC) against engines not already serving Tailcat.
struct RecordingEngine {
    info: roboco_proto::EngineInfo,
    snapshot: serde_json::Value,
    invite_address: Option<String>,
    calls: Mutex<Vec<String>>,
}

impl RecordingEngine {
    fn new(snapshot: serde_json::Value, invite_address: Option<String>) -> Arc<Self> {
        Arc::new(Self {
            info: roboco_proto::EngineInfo::current(
                "member-engine".into(),
                roboco_proto::WorkspaceScope::Local,
                None,
            ),
            snapshot,
            invite_address,
            calls: Mutex::new(Vec::new()),
        })
    }

    fn count(&self, method: &str) -> usize {
        self.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|call| call.as_str() == method)
            .count()
    }
}

#[async_trait::async_trait]
impl roboco_rpc::RpcService for RecordingEngine {
    async fn handle(
        &self,
        method: &str,
        _params: serde_json::Value,
    ) -> Result<RpcReply, roboco_rpc::RpcError> {
        self.calls.lock().unwrap().push(method.to_owned());
        match method {
            methods::ENGINE_INFO => RpcReply::value(&self.info),
            methods::GET_REMOTE_ACCESS => RpcReply::value(&self.snapshot),
            methods::CREATE_TAILCAT_INVITE => match &self.invite_address {
                Some(address) => {
                    let invite = roboco_engine::tailcat::TailcatInvite::new(
                        address,
                        "oneSecondCodeNeverRedeemed",
                        i64::MAX,
                    )
                    .unwrap();
                    RpcReply::value(&json!({
                        "id": "code-1",
                        "url": invite.encode().unwrap(),
                        "expiresAt": i64::MAX,
                    }))
                }
                None => Err(roboco_rpc::RpcError::Failed("tailcat route is off".into())),
            },
            other => Err(roboco_rpc::RpcError::UnknownMethod(other.into())),
        }
    }
}

fn recording_registry(engine: Arc<RecordingEngine>) -> EngineRegistry {
    let client = Arc::new(roboco_rpc::memory_client(engine));
    let info = roboco_proto::EngineInfo::current(
        "member-engine".into(),
        roboco_proto::WorkspaceScope::Local,
        None,
    );
    EngineRegistry::test_local(info, client)
}

fn remote_access(enabled: bool, address: Option<&str>) -> serde_json::Value {
    json!({
        "status": {
            "enabled": enabled,
            "configuredEnabled": enabled,
            "address": address,
            "source": "test",
            "error": null,
        },
        "sessions": []
    })
}

/// C4a (`ac713514` regression): the production probe mints an address only
/// for an engine already serving its Tailcat route; a LAN listener or remote
/// access off reads as "turn it on" and the settings-mutating mint is never
/// sent.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_probe_never_mints_against_engines_without_a_tailcat_route() {
    // Serving: one guard call, one mint, the address comes back.
    let engine = RecordingEngine::new(
        remote_access(true, Some("127.0.0.1:27655")),
        Some("tcSHAPEDMEMBER1".into()),
    );
    let registry = recording_registry(engine.clone());
    let target = registry.local();
    assert_eq!(TailcatProbe.shape(&target).await, TailcatShape::Serves);
    assert_eq!(
        TailcatProbe.address(&target).await.unwrap(),
        "tcSHAPEDMEMBER1"
    );
    assert_eq!(engine.count(methods::GET_REMOTE_ACCESS), 1);
    assert_eq!(engine.count(methods::CREATE_TAILCAT_INVITE), 1);

    for (name, snapshot) in [
        (
            "network transport",
            remote_access(true, Some("0.0.0.0:27655")),
        ),
        ("remote access off", remote_access(false, None)),
    ] {
        // The mint is ARMED: only the guard keeps it from being sent.
        let engine = RecordingEngine::new(snapshot, Some("tcNEVERMINTED".into()));
        let registry = recording_registry(engine.clone());
        let target = registry.local();
        assert_eq!(
            TailcatProbe.shape(&target).await,
            TailcatShape::Off,
            "{name}"
        );
        assert_eq!(engine.count(methods::GET_REMOTE_ACCESS), 1, "{name}");
        assert_eq!(
            engine.count(methods::CREATE_TAILCAT_INVITE),
            0,
            "{name}: the settings-mutating RPC must never be sent"
        );
        assert_eq!(engine.count(methods::SET_REMOTE_ACCESS), 0, "{name}");
    }
    assert_eq!(
        TailcatShape::Off,
        tailcat_shape_of(&remote_access(true, Some("192.168.1.4:27655")))
    );
}
