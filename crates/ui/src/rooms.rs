//! Desktop fleet operations (fleet follow, ADR 0011 amendment): the one
//! fleet room this data dir keeps, pushed to every paired engine that serves
//! its Tailcat route, over the client machinery in
//! `roboco-engine-client::rooms` plus the pieces only the desktop has: the
//! tailcat dialer, the address probe, and the local engine's room store.
//!
//! The room concept never reaches the UI: Settings → Remote access shows a
//! Fleet card with plain per-engine state (In / Not in yet / opt-out). Joined
//! member engines become ordinary registry entries, so the Devices flow and
//! the composer's device picker need no changes (ADR 0004).
//!
//! The fleet invite is secret-bearing (ADR 0008): it is never logged, never
//! written anywhere but the 0600 rooms file and the local engine's 0600
//! pairing database, and the invite card warns that anyone holding it can
//! reach the fleet. Nothing here ever mutates remote-access settings on any
//! engine (C4a): the only remote-access call is the read-only
//! `GetRemoteAccess` shape check, and member addresses arrive in the fleet
//! books — each engine adds its own recorded Tailcat address when it joins.

use std::{
    collections::{BTreeSet, HashSet},
    fmt,
    path::{Path, PathBuf},
    sync::Arc,
};

use crate::engine_registry::{
    EngineConnectionState, EngineKey, EngineRegistry, EngineTarget, RegistrySnapshot,
};
use roboco_engine::rooms::{FleetConflict, RoomRecord, RoomStore};
use roboco_engine_client::rooms::{
    ClientRoom, ResolvedMember, RoomDialer, RoomSecretFile, fetch_room_book,
    heal_room_books_with, is_fleet_conflict, join_room_members, push_room_join,
    revoke_room_sessions, union_members, write_private_file,
};
use roboco_proto::capabilities;
use roboco_proto::qr;
use roboco_proto::room::{RoomInvite, RoomKind, RoomMember};
use roboco_rpc::{RpcError, methods};
use serde::{Deserialize, Serialize};
use serde_json::json;

/// The fleet's default name (the label member engines show for it).
pub const FLEET_NAME: &str = "Fleet";

/// Fleet preferences beside `rooms-v1.json`: the per-engine opt-outs.
pub const FLEET_FILE: &str = "fleet-v1.json";

/// The paste field refuses plain-room invites with this fixed text.
pub const PLAIN_ROOM_INVITE: &str =
    "That invite is for a room, not a fleet. Join rooms from a terminal: roboco engine room join";

/// The desktop's dialer: one tailcat forwarder per member address, the same
/// layout the pairing resolver uses. The forwarder (and its adapter child)
/// lives exactly as long as the registry entry the join adopts.
pub struct TailcatRoomDialer;

impl RoomDialer for TailcatRoomDialer {
    fn dial(&self, address: &str, data_dir: &Path) -> anyhow::Result<ResolvedMember> {
        let session = data_dir
            .join("tailcat-forwarders")
            .join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(&session)?;
        let client = roboco_engine::tailcat::TailcatClient::start(&session, address, None)?;
        Ok(ResolvedMember {
            base: client.url().trim_end_matches('/').to_string(),
            keepalive: Some(Box::new(client)),
        })
    }
}

/// What an engine's remote-access state says about its Tailcat route.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TailcatShape {
    /// Remote access is on and the listener is the loopback serve target:
    /// the engine runs the Tailcat transport, and the address it publishes
    /// is the one the fleet book wants.
    Serves,
    /// Remote access is off, or the listener is a LAN bind: fleet code never
    /// enables it — the user does, on that engine.
    Off,
    /// The engine could not be asked.
    Unreachable,
}

/// Which paired engines the fleet pushes to. Production asks the engine
/// itself ([`TailcatProbe`]); tests map engines to shapes (test engines
/// cannot run Tailcat). The probe never learns addresses: each engine adds
/// its own to the fleet book when it joins, and the sync reads it there.
#[async_trait::async_trait]
pub trait FleetProbe: Send + Sync {
    async fn shape(&self, target: &EngineTarget) -> TailcatShape;
}

/// The production probe: one read-only `GetRemoteAccess`. Fleet code never
/// sends `CreateTailcatInvite` (or any other settings-writing RPC): that
/// mint force-enables and persists Tailcat, wiping a Direct engine's
/// transport and public URL (C4a).
pub struct TailcatProbe;

#[async_trait::async_trait]
impl FleetProbe for TailcatProbe {
    async fn shape(&self, target: &EngineTarget) -> TailcatShape {
        let Ok(snapshot) = target.call(methods::GET_REMOTE_ACCESS, json!({})).await else {
            return TailcatShape::Unreachable;
        };
        tailcat_shape_of(&snapshot)
    }
}

/// The client-visible signature of an engine serving its Tailcat route. The
/// Tailcat transport's listener is by construction a loopback serve target
/// (`listener_bind` routes Tailcat through `serve_target`, which rejects
/// non-loopback), so an engine with remote access off, or a LAN-bound
/// network listener, never matches. The snapshot does not name the
/// transport, so a Direct engine bound to loopback (behind a reverse proxy)
/// reads as serving too; that only costs it a settings-free `RoomJoin`, and
/// it adds a book entry only if it ever recorded a Tailcat address. Pure.
pub fn tailcat_shape_of(snapshot: &serde_json::Value) -> TailcatShape {
    let enabled = snapshot["status"]["enabled"].as_bool().unwrap_or(false);
    let loopback = snapshot["status"]["address"]
        .as_str()
        .and_then(|address| address.parse::<std::net::SocketAddr>().ok())
        .is_some_and(|address| address.ip().is_loopback());
    if enabled && loopback {
        TailcatShape::Serves
    } else {
        TailcatShape::Off
    }
}

/// A display label for a registry engine: its own device row's name when
/// the devices stream has landed, else the engine identity — never a secret.
pub fn engine_label(snapshot: &RegistrySnapshot, key: &EngineKey) -> String {
    snapshot
        .engines
        .iter()
        .find(|engine| &engine.key == key)
        .map(|engine| {
            engine
                .devices
                .iter()
                .find(|device| device.id == engine.info.device_id)
                .or_else(|| engine.devices.first())
                .map(|device| device.name.clone())
                .unwrap_or_else(|| engine.info.device_id.clone())
        })
        .unwrap_or_else(|| "Engine".into())
}

/// The fleet preferences file (`fleet-v1.json`, 0600, atomic): which
/// engines (by device id — stable across re-pairing) the user left out.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FleetPrefs {
    #[serde(default)]
    pub opted_out: BTreeSet<String>,
}

impl FleetPrefs {
    pub fn is_opted_out(&self, device_id: &str) -> bool {
        self.opted_out.contains(device_id)
    }
}

#[derive(Debug, Clone)]
pub struct FleetPrefsFile {
    path: PathBuf,
}

impl FleetPrefsFile {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    /// Defaults when absent; an unreadable file is an error (preserved).
    pub fn load(&self) -> anyhow::Result<FleetPrefs> {
        match std::fs::read(&self.path) {
            Ok(bytes) => serde_json::from_slice(&bytes)
                .map_err(|_| anyhow::anyhow!("the fleet preferences could not be read")),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(FleetPrefs::default()),
            Err(error) => Err(error.into()),
        }
    }

    pub fn save(&self, prefs: &FleetPrefs) -> anyhow::Result<()> {
        write_private_file(&self.path, &serde_json::to_vec(prefs)?)
    }
}

/// Why an engine is not in the fleet yet — each maps to one plain caption.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NotInYet {
    /// The engine is not connected right now.
    Offline,
    /// Its remote access is off or not on the Tailcat transport.
    TailcatOff,
    /// It answered, but the join did not confirm (Retry runs it again).
    Unconfirmed,
}

/// One engine's fleet state, as the card renders it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FleetState {
    In,
    NotInYet(NotInYet),
    /// The engine predates `rooms-v2`: it cannot join, never an error.
    NeedsUpdate,
    /// The user switched this engine off.
    OptedOut,
    /// The engine already belongs to a different fleet.
    OtherFleet,
}

impl FleetState {
    /// The row's caption: plain language, never a secret or an address.
    pub fn caption(self) -> &'static str {
        match self {
            FleetState::In => "In",
            FleetState::NotInYet(NotInYet::Offline) => "Not in yet · offline",
            FleetState::NotInYet(NotInYet::TailcatOff) => {
                "Not in yet · turn on Remote access with Tailcat on that engine"
            }
            FleetState::NotInYet(NotInYet::Unconfirmed) => "Not in yet",
            FleetState::NeedsUpdate => "Update that engine to add it",
            FleetState::OptedOut => "Left out",
            FleetState::OtherFleet => "In a different fleet",
        }
    }

    pub fn retryable(self) -> bool {
        matches!(self, FleetState::NotInYet(_))
    }
}

/// One engine row on the Fleet card. `device_id` is `None` for a book member
/// this desktop has never reached (no switch for it — nothing to opt out).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FleetRow {
    pub device_id: Option<String>,
    pub key: Option<EngineKey>,
    pub label: String,
    pub state: FleetState,
}

/// The Fleet card's model: whether this data dir holds a fleet yet, and one
/// row per other engine.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FleetReport {
    pub exists: bool,
    pub rows: Vec<FleetRow>,
}

/// A paired engine the survey wants probed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FleetCandidate {
    pub key: EngineKey,
    pub device_id: String,
    pub label: String,
}

/// The survey: the rows decided from the registry snapshot alone (opt-outs,
/// old engines, offline), and the candidates still to probe. One row per
/// engine identity — a registry carrying the same device twice still shows
/// (and pushes) it once. Pure.
pub fn survey(
    snapshot: &RegistrySnapshot,
    prefs: &FleetPrefs,
) -> (Vec<FleetRow>, Vec<FleetCandidate>) {
    let local_device = snapshot
        .engines
        .iter()
        .find(|engine| engine.key.is_local())
        .map(|engine| engine.info.device_id.clone());
    let mut seen = HashSet::new();
    let mut rows = Vec::new();
    let mut candidates = Vec::new();
    for engine in &snapshot.engines {
        let device_id = engine.info.device_id.clone();
        if engine.key.is_local() || local_device.as_deref() == Some(device_id.as_str()) {
            continue;
        }
        if !seen.insert(device_id.clone()) {
            continue;
        }
        let label = engine_label(snapshot, &engine.key);
        let state = if prefs.is_opted_out(&device_id) {
            Some(FleetState::OptedOut)
        } else if !engine.info.supports(capabilities::ROOMS_V2) {
            Some(FleetState::NeedsUpdate)
        } else if engine.state != EngineConnectionState::Connected {
            Some(FleetState::NotInYet(NotInYet::Offline))
        } else {
            None
        };
        rows.push(FleetRow {
            device_id: Some(device_id.clone()),
            key: Some(engine.key.clone()),
            label: label.clone(),
            state: state.unwrap_or(FleetState::NotInYet(NotInYet::Unconfirmed)),
        });
        if state.is_none() {
            candidates.push(FleetCandidate {
                key: engine.key.clone(),
                device_id,
                label,
            });
        }
    }
    (rows, candidates)
}

/// The shareable fleet invite. Secret-bearing: `Debug` prints shape only.
#[derive(Clone, PartialEq, Eq)]
pub struct FleetInvite {
    pub encoded: String,
    pub qr_svg: Option<String>,
}

impl fmt::Debug for FleetInvite {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("FleetInvite")
            .field("encoded", &roboco_proto::room::describe(&self.encoded))
            .field("qr", &self.qr_svg.is_some())
            .finish()
    }
}

/// The paste flow met a fleet this machine already shares with other
/// engines: joining the pasted one means leaving it, so the card asks first.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FleetSwitchNeeded {
    /// Other engines in the current fleet's book.
    pub others: usize,
}

impl fmt::Display for FleetSwitchNeeded {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(
            "This computer is already in a fleet with other engines. Joining this one leaves it.",
        )
    }
}

impl std::error::Error for FleetSwitchNeeded {}

/// All fleet operations the desktop drives. One data dir — the app and the
/// embedded (or daemon) engine share it, which is what lets the local engine
/// hold the fleet through its own room store while the listener keeps
/// serving (WAL coordinates the two).
pub struct RoomOps {
    data_dir: PathBuf,
    registry: EngineRegistry,
    dialer: Arc<dyn RoomDialer>,
    probe: Arc<dyn FleetProbe>,
}

impl RoomOps {
    pub fn new(data_dir: PathBuf, registry: EngineRegistry) -> Self {
        Self {
            data_dir,
            registry,
            dialer: Arc::new(TailcatRoomDialer),
            probe: Arc::new(TailcatProbe),
        }
    }

    /// The test seam: a flow with a fake dialer (member addresses mapped to
    /// loopback engines) instead of spawning adapter processes.
    #[cfg(test)]
    pub fn with_dialer(
        data_dir: PathBuf,
        registry: EngineRegistry,
        dialer: Arc<dyn RoomDialer>,
    ) -> Self {
        Self {
            data_dir,
            registry,
            dialer,
            probe: Arc::new(TailcatProbe),
        }
    }

    /// The probe seam beside the dialer seam (test engines cannot serve
    /// Tailcat, so tests map engines to addresses).
    #[cfg(test)]
    pub fn with_probe(mut self, probe: Arc<dyn FleetProbe>) -> Self {
        self.probe = probe;
        self
    }

    pub fn rooms_file(&self) -> RoomSecretFile {
        RoomSecretFile::new(self.data_dir.join(roboco_engine_client::rooms::ROOMS_FILE))
    }

    pub fn prefs_file(&self) -> FleetPrefsFile {
        FleetPrefsFile::new(self.data_dir.join(FLEET_FILE))
    }

    /// The address this machine's engine last published, if it serves a
    /// tailcat route. `None` until Remote access runs the Tailcat transport.
    pub fn local_address(&self) -> Option<String> {
        roboco_engine::tailcat::read_recorded_address(&self.data_dir)
            .ok()
            .flatten()
    }

    /// Run one operation against the local engine's room store on a
    /// blocking thread (SQLite, WAL-coordinated with the running engine).
    async fn with_local_store<T, F>(&self, operation: F) -> anyhow::Result<T>
    where
        T: Send + 'static,
        F: FnOnce(RoomStore) -> anyhow::Result<T> + Send + 'static,
    {
        let data_dir = self.data_dir.clone();
        tokio::task::spawn_blocking(move || operation(RoomStore::open(&data_dir)?))
            .await
            .map_err(|_| anyhow::anyhow!("the engine room store could not be reached"))?
    }

    /// The fleet room the local engine holds, if any.
    pub async fn local_fleet(&self) -> anyhow::Result<Option<RoomRecord>> {
        self.with_local_store(|store| store.fleet_room()).await
    }

    /// Mint this data dir's fleet room if it has none yet (kind = fleet, the
    /// secret retained by the local engine so phones pairing here get it via
    /// `GetFleetInvite`). Returns the fleet's id; idempotent.
    pub async fn create(&self) -> anyhow::Result<String> {
        if let Some(fleet) = self.local_fleet().await? {
            return Ok(fleet.id);
        }
        let local = self.local_member();
        let record = self
            .with_local_store(move |store| match store.create(FLEET_NAME, local) {
                // A concurrent mint (CLI) won the race: use its fleet.
                Err(error) if error.downcast_ref::<FleetConflict>().is_some() => store
                    .fleet_room()?
                    .ok_or_else(|| anyhow::anyhow!("the fleet could not be created")),
                other => other,
            })
            .await?;
        tracing::info!(room_id = %record.id, "fleet minted");
        Ok(record.id)
    }

    /// This machine's own book entry — the one its engine's store adds on a
    /// fleet join, so the two never disagree on the label.
    fn local_member(&self) -> Vec<RoomMember> {
        roboco_engine::rooms::own_member(&self.data_dir)
            .into_iter()
            .collect()
    }

    /// The client record for fleet `id`: the saved one, else rebuilt from the
    /// local engine's fleet invite (a fleet joined through the CLI). Records
    /// of fleets the local engine no longer holds are dropped.
    async fn client_fleet(&self, id: &str) -> anyhow::Result<ClientRoom> {
        let rooms = self.rooms_file().load()?;
        let stale: Vec<String> = rooms
            .iter()
            .filter(|room| room.is_fleet() && room.id != id)
            .map(|room| room.id.clone())
            .collect();
        for stale in stale {
            self.rooms_file().remove(&stale)?;
        }
        if let Some(room) = rooms.into_iter().find(|room| room.id == id) {
            return Ok(room);
        }
        let fleet_id = id.to_owned();
        let invite = self
            .with_local_store(move |store| store.fleet_invite(&fleet_id))
            .await?
            .ok_or_else(|| anyhow::anyhow!("the fleet could not be read"))?;
        Ok(ClientRoom::from_invite(&invite))
    }

    /// The fleet invite to show (QR + copy): mints the fleet first when this
    /// data dir has none, pushing it to every eligible engine.
    pub async fn fleet_invite(&self) -> anyhow::Result<FleetInvite> {
        let id = match self.local_fleet().await? {
            Some(fleet) => fleet.id,
            None => {
                self.sync_with(true).await?;
                self.create().await?
            }
        };
        let invite = self
            .with_local_store(move |store| store.fleet_invite(&id))
            .await?
            .ok_or_else(|| anyhow::anyhow!("the fleet could not be read"))?;
        let encoded = invite.encode()?;
        Ok(FleetInvite {
            qr_svg: qr::try_qr_svg(&encoded),
            encoded,
        })
    }

    /// Leave an engine out of the fleet (or bring it back). Forward-only: an
    /// engine that already joined keeps the fleet until its host leaves it.
    pub fn set_opt_out(&self, device_id: &str, opted_out: bool) -> anyhow::Result<()> {
        let file = self.prefs_file();
        let mut prefs = file.load()?;
        if opted_out {
            prefs.opted_out.insert(device_id.to_owned());
        } else {
            prefs.opted_out.remove(device_id);
        }
        file.save(&prefs)
    }

    /// The push + heal pass (app open, new pairings, Retry, opt-out changes):
    /// mint the fleet lazily, push it (`RoomJoin`) to every eligible engine,
    /// present to book members this desktop does not reach yet, and union
    /// the books. Idempotent: live members are untouched, re-pushes are no-op
    /// joins on the engines, and no engine is ever added twice.
    pub async fn sync(&self) -> anyhow::Result<FleetReport> {
        self.sync_with(false).await
    }

    async fn sync_with(&self, force_mint: bool) -> anyhow::Result<FleetReport> {
        let prefs = self.prefs_file().load()?;
        let (mut rows, candidates) = survey(&self.registry.snapshot(), &prefs);
        let set_state = |rows: &mut Vec<FleetRow>, device_id: &str, state: FleetState| {
            if let Some(row) = rows
                .iter_mut()
                .find(|row| row.device_id.as_deref() == Some(device_id))
            {
                row.state = state;
            }
        };
        // Probe: only engines already serving their Tailcat route qualify.
        let mut eligible = Vec::new();
        for candidate in candidates {
            let Ok(target) = self.registry.target(&candidate.key) else {
                set_state(
                    &mut rows,
                    &candidate.device_id,
                    FleetState::NotInYet(NotInYet::Offline),
                );
                continue;
            };
            match self.probe.shape(&target).await {
                TailcatShape::Serves => eligible.push((candidate, target)),
                TailcatShape::Off => set_state(
                    &mut rows,
                    &candidate.device_id,
                    FleetState::NotInYet(NotInYet::TailcatOff),
                ),
                TailcatShape::Unreachable => set_state(
                    &mut rows,
                    &candidate.device_id,
                    FleetState::NotInYet(NotInYet::Offline),
                ),
            }
        }
        // Lazy mint: the first fleet event with someone to share it with.
        let fleet_id = match self.local_fleet().await? {
            Some(fleet) => fleet.id,
            None if eligible.is_empty() && !force_mint => {
                return Ok(FleetReport {
                    exists: false,
                    rows,
                });
            }
            None => self.create().await?,
        };
        let mut room = self.client_fleet(&fleet_id).await?;
        union_members(&mut room.members, self.local_member());
        // The local engine holds the union (kind = fleet, secret retained):
        // phones pairing here fetch exactly this invite.
        let id = fleet_id.clone();
        let book = room.members.clone();
        let invite = self
            .with_local_store(move |store| {
                store.merge_book(&id, book)?;
                store.fleet_invite(&id)
            })
            .await?
            .ok_or_else(|| anyhow::anyhow!("the fleet could not be read"))?;
        room.members = invite.members.clone();
        let sent: HashSet<String> = invite
            .members
            .iter()
            .map(|member| member.address.clone())
            .collect();
        let encoded = invite.encode()?;
        drop(invite);
        // Push: each engine's store join adds the engine's own recorded
        // Tailcat address to its book (C4a: nobody is asked to mint one).
        let mut pushed = Vec::new();
        for (candidate, target) in eligible {
            let state = match push_room_join(&target, &encoded).await {
                Ok(_) => {
                    pushed.push((candidate.key.clone(), target));
                    FleetState::In
                }
                Err(error) if is_fleet_conflict(&error) => FleetState::OtherFleet,
                Err(RpcError::UnknownMethod(_)) => FleetState::NeedsUpdate,
                Err(_) => FleetState::NotInYet(NotInYet::Unconfirmed),
            };
            set_state(&mut rows, &candidate.device_id, state);
        }
        drop(encoded);
        // Learn each pushed engine's address from its own book.
        let local_address = self.local_address();
        for (key, target) in &pushed {
            let Ok(Some(book)) = fetch_room_book(target, &room.id, &room.secret).await else {
                continue;
            };
            if let Some(address) =
                own_entry(&room, key, &sent, local_address.as_deref(), &book.members)
            {
                room.link(&address, key);
            }
            union_members(&mut room.members, book.members);
        }
        // Push the union back to every pushed and linked engine, so every
        // book (and every `GetFleetInvite` answer) carries every member that
        // introduced itself in this pass.
        let pushed_keys: Vec<EngineKey> = pushed.into_iter().map(|(key, _)| key).collect();
        heal_room_books_with(&self.registry, &mut room, &pushed_keys).await;
        // Durable before dialing: presentations are repeatable.
        self.rooms_file().upsert(room.clone())?;
        // The client pass: book members this desktop does not reach yet are
        // presented to (late joiners, other clients' engines, dead entries
        // after a restart), and the books union.
        let outcome = join_room_members(
            &self.registry,
            self.dialer.clone(),
            &self.data_dir,
            room,
            local_address.as_deref(),
        )
        .await?;
        let id = fleet_id.clone();
        let book = outcome.room.members.clone();
        self.with_local_store(move |store| store.merge_book(&id, book))
            .await?;
        self.rooms_file().upsert(outcome.room.clone())?;
        Ok(finish_rows(
            rows,
            &self.registry,
            &prefs,
            &outcome.room,
            &outcome
                .failures
                .iter()
                .map(|failure| failure.label.clone())
                .collect::<Vec<_>>(),
        ))
    }

    /// Join a fleet minted elsewhere from a pasted invite (the PC 2 case).
    /// Decoding fails with fixed text before any work. If this machine
    /// already shares a different fleet with other engines, the join is
    /// refused with [`FleetSwitchNeeded`] unless `replace` confirms leaving
    /// it; a solo fleet (nobody else in its book) is left silently.
    pub async fn join(&self, invite_text: &str, replace: bool) -> anyhow::Result<FleetReport> {
        let invite = RoomInvite::decode(invite_text.trim())?;
        anyhow::ensure!(invite.is_fleet(), "{PLAIN_ROOM_INVITE}");
        let id = invite.room_id();
        if let Some(current) = self.local_fleet().await?
            && current.id != id
        {
            let local_address = self.local_address();
            let others = current
                .book
                .members
                .iter()
                .filter(|member| Some(&member.address) != local_address.as_ref())
                .count();
            if others > 0 && !replace {
                return Err(FleetSwitchNeeded { others }.into());
            }
            self.leave().await?;
        }
        let mut room = match self
            .rooms_file()
            .load()?
            .into_iter()
            .find(|room| room.id == id)
        {
            Some(room) => room,
            None => ClientRoom::from_invite(&invite),
        };
        union_members(&mut room.members, invite.members.clone());
        room.kind = RoomKind::Fleet;
        self.with_local_store(move |store| store.join(invite).map(|_| ()))
            .await?;
        self.rooms_file().upsert(room)?;
        self.sync_with(false).await
    }

    /// Leave the fleet: revoke the sessions its presentations created,
    /// forget the registry entries it adopted (engines paired by hand are
    /// never touched), drop the client record, and drop the local engine's
    /// fleet row (erasing the retained secret). Returns whether this data dir
    /// held a fleet.
    pub async fn leave(&self) -> anyhow::Result<bool> {
        let fleet = self.local_fleet().await?;
        let rooms = self.rooms_file().load()?;
        for room in rooms.iter().filter(|room| room.is_fleet()) {
            revoke_room_sessions(&self.registry, room).await;
            for session in room.sessions.iter().filter(|session| session.adopted()) {
                let _ = self
                    .registry
                    .forget(&EngineKey(session.engine_key.clone()))
                    .await;
            }
            self.rooms_file().remove(&room.id)?;
        }
        let Some(fleet) = fleet else {
            return Ok(false);
        };
        self.with_local_store(move |store| store.leave(&fleet.id))
            .await
    }
}

/// The final rows after a pass: engines the pass reached (presentations or
/// reattached entries) read as In, engines it brought in get rows, and book
/// members it could not reach yet show as offline — one row per identity.
fn finish_rows(
    mut rows: Vec<FleetRow>,
    registry: &EngineRegistry,
    prefs: &FleetPrefs,
    room: &ClientRoom,
    failed_labels: &[String],
) -> FleetReport {
    let snapshot = registry.snapshot();
    let local_device = snapshot
        .engines
        .iter()
        .find(|engine| engine.key.is_local())
        .map(|engine| engine.info.device_id.clone());
    for session in &room.sessions {
        let key = EngineKey(session.engine_key.clone());
        let Some(engine) = snapshot.engines.iter().find(|engine| engine.key == key) else {
            continue;
        };
        if key.is_local() || local_device.as_deref() == Some(engine.info.device_id.as_str()) {
            continue;
        }
        let live = engine.state == EngineConnectionState::Connected
            || registry
                .target(&key)
                .is_ok_and(|target| target.is_connected());
        let device_id = engine.info.device_id.clone();
        match rows
            .iter_mut()
            .find(|row| row.device_id.as_deref() == Some(device_id.as_str()))
        {
            Some(row) => {
                if live && matches!(row.state, FleetState::NotInYet(NotInYet::Offline)) {
                    row.state = FleetState::In;
                }
            }
            None => rows.push(FleetRow {
                label: engine_label(&snapshot, &key),
                key: Some(key),
                state: if prefs.is_opted_out(&device_id) {
                    FleetState::OptedOut
                } else {
                    FleetState::In
                },
                device_id: Some(device_id),
            }),
        }
    }
    for label in failed_labels {
        if rows.iter().any(|row| &row.label == label) {
            continue;
        }
        rows.push(FleetRow {
            device_id: None,
            key: None,
            label: label.clone(),
            state: FleetState::NotInYet(NotInYet::Offline),
        });
    }
    FleetReport { exists: true, rows }
}

/// The address a just-pushed engine introduced itself with: the one entry in
/// its book that the pushed invite did not carry (its store adds exactly its
/// own recorded address on a fleet join), not this machine's, and not one
/// already linked to another engine. `None` when the engine is linked
/// already, added nothing (no recorded address), or the difference is
/// ambiguous (its book held other entries before); the client pass then
/// reaches any such address by presenting, and the identity dedupe folds
/// that into the existing entry. Pure.
fn own_entry(
    room: &ClientRoom,
    key: &EngineKey,
    sent: &HashSet<String>,
    local_address: Option<&str>,
    book: &[RoomMember],
) -> Option<String> {
    if room
        .sessions
        .iter()
        .any(|session| session.engine_key == key.0)
    {
        return None;
    }
    let fresh: BTreeSet<&str> = book
        .iter()
        .map(|member| member.address.as_str())
        .filter(|address| !sent.contains(*address) && Some(*address) != local_address)
        .filter(|address| {
            !room
                .sessions
                .iter()
                .any(|session| session.address == *address)
        })
        .collect();
    let mut fresh = fresh.into_iter();
    match (fresh.next(), fresh.next()) {
        (Some(address), None) => Some(address.to_owned()),
        _ => None,
    }
}

/// The desktop's fleet sync scheduling, kept on `AppState`: one pass at a
/// time with one coalesced re-run, triggered by app open (the local engine's
/// first connect) and by every engine that connects for the first time this
/// process (new pairings from any flow).
#[derive(Default)]
pub struct FleetSync {
    pub report: Option<FleetReport>,
    pub error: Option<String>,
    pub running: bool,
    pub rerun: bool,
    seen: HashSet<EngineKey>,
    pub(crate) task: Option<gpui::Task<()>>,
}

impl FleetSync {
    /// Record which engines are connected; true when one connected for the
    /// first time this process (a fleet event). Pure over the snapshot.
    pub fn note_connected(&mut self, snapshot: &RegistrySnapshot) -> bool {
        let mut fresh = false;
        for engine in &snapshot.engines {
            if engine.state == EngineConnectionState::Connected
                && self.seen.insert(engine.key.clone())
            {
                fresh = true;
            }
        }
        fresh
    }
}

#[cfg(test)]
mod tests;
