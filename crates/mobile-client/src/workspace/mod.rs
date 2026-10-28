//! The workspace store: the derived [`WorkspaceSnapshot`] the Sessions
//! tab, Search, folders, Archived, Settings → Sessions and the tab
//! accessory counts render, plus the workspace mutations.
//!
//! Everything upstream's `zeron-client` derived from its Loro registry
//! doc, this derives from the merged engine streams (ticket 06's
//! `FleetSnapshot`) — re-stamped session rows, scoped ids, one pin/section
//! bucket per engine profile — plus the optimistic overlays. Recompute
//! triggers: any fleet change, any overlay change, a PR status, an 08
//! send-state change, and a 1 Hz tick (time labels, the 45 s staleness
//! edge). The revision bumps only when the snapshot's content hash
//! changes.
//!
//! Mutations validate and fail fast (`NotFound`, `HostUnavailable`,
//! `Unsupported`, `InvalidArgument`), apply an optimistic overlay, emit
//! `WorkspaceChanged`, spawn the RPC on the calls connection and return
//! `Ok`. The engine's own frame confirms the overlay; an RPC failure
//! reverts it and emits `ClientEvent::Notice`. Writes are never queued
//! while the engine is offline (`.scratch/remote-access/spec.md` story
//! 24) — the call refuses immediately and the overlay reverts.

pub mod change_requests;
pub mod sidebar;
pub mod view;

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use roboco_engine_client::cache::CachedRows;
use roboco_engine_client::registry::ScopedId;
use roboco_proto::{Chat, SidebarSection, SidebarStateSnapshot};
use tokio::sync::{mpsc, watch, Notify};
use tokio_util::sync::CancellationToken;

pub use sidebar::{
    MAX_SIDEBAR_PINS, MAX_SIDEBAR_SECTIONS, SIDEBAR_SECTION_NAME_MAX_CHARS, PinChange,
};
pub use view::{
    ChangeRequests, DeriveContext, DeviceView, EngineRows, FrontPage, ProjectRef, ProjectView,
    PullRequestGroups, SearchField, SearchHit, SectionView, SendState, SessionConfig, SessionGroup,
    SessionRow, WorkspaceSnapshot, derive, project_color_count, project_color_index,
    relative_time_label,
};

use crate::engines::{EngineHandle, EngineState};
use crate::new_session::PendingChat;
use crate::{ClientError, ClientInner, lock};

/// The mobile sidebar write failure notice (the web's
/// `sidebar-state-sync.ts:602`): the user learns the engine never saw the
/// write.
pub(crate) const PIN_WRITE_NOTICE: &str = "Couldn't save pins: ";
/// The generic mutation failure notice (the web's `chat-actions.ts:138`).
pub(crate) const MUTATION_NOTICE: &str = "The change could not be applied.";

pub(crate) struct WorkspaceStore {
    weak: std::sync::Weak<ClientInner>,
    snapshot: RwLock<Arc<WorkspaceSnapshot>>,
    revision: AtomicU64,
    /// The overlays and per-engine sidebar desired state; shared with the
    /// per-engine sidebar writers.
    state: Arc<Mutex<StoreState>>,
    /// Signals a re-derive (overlay change, PR badge, 08 send state).
    notify: Arc<Notify>,
    cancel: CancellationToken,
    pr: change_requests::ChangeRequestWatches,
    /// Ticket 08's pending-send state per scoped chat id (rows read it).
    send_states: Arc<Mutex<HashMap<String, SendState>>>,
    /// Ticket 09's pending chats, keyed by scoped chat id: an extra row
    /// source for the derive (the optimistic row before `WatchChats`
    /// echoes the id) and a `cwd`/`config` source for 08's send.
    pending_chats: Arc<Mutex<HashMap<String, PendingChat>>>,
    /// The running-subagent count per scoped chat id (upstream #899's
    /// "● N" pill): the open sessions write it from their transcripts,
    /// the rows read it. Sessions that leave the registry clear theirs.
    running_subagents: Arc<Mutex<HashMap<String, u32>>>,
}

/// The mutable, lock-guarded half of the store.
#[derive(Default)]
pub(crate) struct StoreState {
    overlays: Overlays,
    /// Desired pin list per engine (raw ids) — the pending overlay over
    /// the last confirmed frame.
    pins: HashMap<String, Vec<String>>,
    /// Desired sections per engine (raw ids inside) — the pending overlay.
    sections: HashMap<String, Vec<SidebarSection>>,
    /// One ping channel per engine's serialized sidebar writer.
    writers: HashMap<String, mpsc::UnboundedSender<()>>,
}

type Overlays = view::Overlays;

impl WorkspaceStore {
    /// Build and start the store's re-derive loop, the 1 Hz tick and the
    /// PR badge watches. The caller installs the result in
    /// `ClientInner::workspace`.
    pub(crate) fn start(weak: std::sync::Weak<ClientInner>) -> Arc<Self> {
        let notify = Arc::new(Notify::new());
        let store = Arc::new(Self {
            weak: weak.clone(),
            snapshot: RwLock::new(Arc::new(WorkspaceSnapshot::default())),
            revision: AtomicU64::new(0),
            state: Arc::new(Mutex::new(StoreState::default())),
            notify: notify.clone(),
            cancel: CancellationToken::new(),
            pr: change_requests::ChangeRequestWatches::new(weak.clone(), notify),
            send_states: Arc::new(Mutex::new(HashMap::new())),
            pending_chats: Arc::new(Mutex::new(HashMap::new())),
            running_subagents: Arc::new(Mutex::new(HashMap::new())),
        });
        // Seed with the derive of an empty fleet: a client with no engines
        // stays silent (its first recompute reproduces this content hash),
        // while any real rows differ and publish revision 1.
        let empty = derive(&DeriveContext {
            engines: &[],
            now: crate::engines::now(),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &HashMap::new(),
            pending_chats: &HashMap::new(),
            running_subagents: &HashMap::new(),
            previous: None,
        });
        *store
            .snapshot
            .write()
            .expect("workspace snapshot lock") = Arc::new(empty);
        let (fleet, tick_every) = {
            let inner = weak
                .upgrade()
                .expect("store started while the client lives");
            (inner.fleet.subscribe(), inner.timings.workspace_tick)
        };
        let task = store.clone();
        crate::runtime::shared().spawn(async move {
            task.drive(fleet, tick_every).await;
        });
        store
    }

    /// Stop the re-derive loop (`Client::shutdown`).
    pub(crate) fn shutdown(&self) {
        self.cancel.cancel();
    }

    async fn drive(self: Arc<Self>, mut fleet: watch::Receiver<Arc<crate::FleetSnapshot>>, tick_every: Duration) {
        let mut tick = tokio::time::interval(tick_every);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = self.cancel.cancelled() => return,
                changed = fleet.changed() => {
                    if changed.is_err() {
                        return; // The client is gone.
                    }
                }
                _ = self.notify.notified() => {}
                _ = tick.tick() => {}
            }
            self.recompute();
        }
    }

    /// Re-derive off the FFI thread. Pure given the fleet + overlays; the
    /// revision bumps only when the content hash changes.
    fn recompute(&self) {
        let Some(inner) = self.weak.upgrade() else {
            return;
        };
        let gathered = gather(&inner);
        // Drop overlays the engine's own frames have confirmed, then build
        // the effective pins/sections (overlay ?? confirmed bucket).
        let mut state = lock(&self.state);
        clear_confirmed(&mut state, &gathered);
        let engines: Vec<EngineRows> = gathered
            .iter()
            .map(|engine| engine.rows_view(&state))
            .collect();
        let overlays = state.overlays.clone();
        let send_states = lock(&self.send_states).clone();
        let running_subagents = lock(&self.running_subagents).clone();
        // Ticket 09: drop pending chats the engine has echoed (or whose
        // 30 s TTL ran out); the survivors are the derive's extra rows.
        let pending_chats = {
            let now_ms = crate::engines::now_ms();
            let mut pending = lock(&self.pending_chats);
            pending.retain(|_id, chat| {
                let echoed = gathered
                    .iter()
                    .find(|engine| engine.engine_id == chat.engine_id)
                    .is_some_and(|engine| {
                        engine.rows.chats.iter().any(|row| row.id == chat.raw_chat_id)
                    });
                !chat.finished(echoed, now_ms)
            });
            pending.clone()
        };
        drop(state);
        // PR badges: reconcile the watch set, then read the statuses.
        let pr = self.pr.inner();
        pr.retarget(&engines);
        let statuses = self.pr.statuses();
        let previous = self.snapshot();
        let next = derive(&DeriveContext {
            engines: &engines,
            now: crate::engines::now(),
            overlays: &overlays,
            change_requests: &statuses,
            send_states: &send_states,
            pending_chats: &pending_chats,
            running_subagents: &running_subagents,
            previous: Some(&previous),
        });
        if next.content_hash() == previous.content_hash() {
            return; // Unchanged content (the seed covers the empty start).
        }
        let revision = self.revision.fetch_add(1, Ordering::AcqRel) + 1;
        let mut next = next;
        next.revision = revision;
        *self.snapshot.write().expect("workspace snapshot lock") = Arc::new(next);
        inner.pump.workspace(revision);
    }

    // ── reads (the FFI clones Arcs; never blocks on the network) ─────────

    pub fn snapshot(&self) -> Arc<WorkspaceSnapshot> {
        self.snapshot.read().expect("workspace snapshot lock").clone()
    }
    pub fn revision(&self) -> u64 {
        self.snapshot().revision
    }

    /// A chat's run configuration, wire-string form — the chat row's
    /// config, ticket 08's optimistic override if one exists, or ticket
    /// 09's pending chat (a chat created a moment ago).
    pub fn session_config(&self, chat_id: &str) -> Option<SessionConfig> {
        if let Some(config) = lock(&self.state).overlays.configs.get(chat_id) {
            return Some(config.clone());
        }
        let inner = self.weak.upgrade()?;
        let scoped = ScopedId::parse(chat_id).ok()?;
        if let Some(chat) = chat_of(&inner, &scoped) {
            return chat.config.as_ref().map(SessionConfig::of_chat);
        }
        // Ticket 09: the pending chat's config (before the echo).
        lock(&self.pending_chats)
            .get(chat_id)?
            .config
            .as_ref()
            .map(SessionConfig::of_chat)
    }

    /// Ticket 08's optimistic config override (the `set_session_config`
    /// write path): the row and the composer read it through
    /// [`Self::session_config`] until the engine's own frame confirms it,
    /// which [`Self::clear_config_overlay`] drops.
    pub(crate) fn set_config_overlay(&self, chat_id: &str, config: SessionConfig) {
        lock(&self.state)
            .overlays
            .configs
            .insert(chat_id.to_owned(), config);
        self.notify.notify_one();
    }

    /// Drop the override (engine echo confirmed, or the RPC failed and the
    /// write reverts).
    pub(crate) fn clear_config_overlay(&self, chat_id: &str) {
        if lock(&self.state).overlays.configs.remove(chat_id).is_some() {
            self.notify.notify_one();
        }
    }

    /// Ticket 08's per-chat send state (the row's `send_state`). The
    /// session refresh writes the headline state of its pending sends;
    /// `None` clears it (nothing in flight). Recomputes on every change.
    pub(crate) fn set_send_state(&self, chat_id: &str, state: Option<SendState>) {
        let mut states = lock(&self.send_states);
        match state {
            Some(state) => {
                if states.insert(chat_id.to_owned(), state) != Some(state) {
                    drop(states);
                    self.notify.notify_one();
                }
            }
            None => {
                if states.remove(chat_id).is_some() {
                    drop(states);
                    self.notify.notify_one();
                }
            }
        }
    }

    /// The running-subagent count of a chat (upstream #899's "● N" pill
    /// on session rows). The open session writes it on every transcript
    /// publication; `0` clears it. Recomputes on every change — a count
    /// that did not move changes nothing (the row hash holds it).
    pub(crate) fn set_running_subagents(&self, chat_id: &str, count: u32) {
        // A leaf lock: nothing else is taken while it is held, so the
        // session's publish path may call this from any lock state the
        // crate's order allows (it runs after the session state lock).
        let mut counts = lock(&self.running_subagents);
        // `0` removes the entry, so every chat ever opened does not leave
        // a zero behind (and clearing an absent count is a no-op).
        let changed = if count == 0 {
            counts.remove(chat_id).is_some()
        } else {
            counts.insert(chat_id.to_owned(), count) != Some(count)
        };
        drop(counts);
        if changed {
            self.notify.notify_one();
        }
    }

    // ── ticket 09's pending chats ──────────────────────────────────────────

    /// Register the optimistic pending chat of a just-created session
    /// (`create_session`): 07's derive renders it as a synthetic row until
    /// `WatchChats` echoes the id (or the 30 s TTL drops it), and 08's
    /// send reads its `cwd`/`config`. Recomputes.
    pub(crate) fn register_pending_chat(&self, pending: PendingChat) {
        let chat_id = pending.chat_id.clone();
        lock(&self.pending_chats).insert(chat_id, pending);
        self.notify.notify_one();
    }

    /// A pending chat by scoped id (08's `send` reads `cwd`/`config` off
    /// it while the row is still in flight).
    pub(crate) fn pending_chat(&self, chat_id: &str) -> Option<PendingChat> {
        lock(&self.pending_chats).get(chat_id).cloned()
    }

    // ── chat mutations ────────────────────────────────────────────────────

    /// Archive or unarchive: `Mutate setChatArchived` on the owning
    /// engine, an optimistic overlay, never queued while offline.
    pub fn set_chat_archived(&self, chat_id: &str, archived: bool) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        {
            let mut state = lock(&self.state);
            state.overlays.archived.insert(chat_id.to_owned(), archived);
        }
        self.notify.notify_one();
        let weak = self.weak.clone();
        let state = self.state.clone();
        let chat_id = chat_id.to_owned();
        crate::runtime::shared().spawn(async move {
            let result = handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({"op": "setChatArchived", "chatId": raw, "archived": archived}),
                )
                .await;
            if let Err(error) = result {
                revert_overlay(&state, |overlays| {
                    if overlays.archived.get(&chat_id) == Some(&archived) {
                        overlays.archived.remove(&chat_id);
                    }
                });
                notice_engine(&weak, error.to_string());
            }
        });
        Ok(())
    }

    /// Rename: `Mutate renameChat`. A blank (whitespace-only) title is a
    /// no-op returning `Ok` (the desktop's `submit_rename_chat`).
    pub fn rename_session(&self, chat_id: &str, title: &str) -> Result<(), ClientError> {
        let trimmed = title.trim();
        if trimmed.is_empty() {
            return Ok(());
        }
        let trimmed = trimmed.to_owned();
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        {
            let mut state = lock(&self.state);
            state
                .overlays
                .title
                .insert(chat_id.to_owned(), trimmed.clone());
        }
        self.notify.notify_one();
        let weak = self.weak.clone();
        let state = self.state.clone();
        let chat_id = chat_id.to_owned();
        crate::runtime::shared().spawn(async move {
            let result = handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({"op": "renameChat", "chatId": raw, "title": trimmed}),
                )
                .await;
            if let Err(error) = result {
                revert_overlay(&state, |overlays| {
                    if overlays.title.get(&chat_id).is_some_and(|t| *t == trimmed) {
                        overlays.title.remove(&chat_id);
                    }
                });
                notice_engine(&weak, error.to_string());
            }
        });
        Ok(())
    }

    /// Delete: `Mutate deleteChat` (the engine purges the doc too). The
    /// overlay hides the row until the frame drops it.
    pub fn delete_session(&self, chat_id: &str) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        {
            let mut state = lock(&self.state);
            state.overlays.deleted.insert(chat_id.to_owned());
        }
        self.notify.notify_one();
        let weak = self.weak.clone();
        let state = self.state.clone();
        let chat_id = chat_id.to_owned();
        crate::runtime::shared().spawn(async move {
            let result = handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({"op": "deleteChat", "chatId": raw}),
                )
                .await;
            if let Err(error) = result {
                revert_overlay(&state, |overlays| {
                    overlays.deleted.remove(&chat_id);
                });
                notice_engine(&weak, error.to_string());
            }
        });
        Ok(())
    }

    /// Mark seen: a monotonic local stamp (never rolled back — a dropped
    /// mutation must not make a chat the user plainly looked at flash
    /// unread again), then `Mutate markChatSeen` fire-and-forget; the
    /// engine stamps now.
    pub fn mark_seen(&self, chat_id: &str) {
        let now_ms = crate::engines::now_ms();
        let Ok(scoped) = ScopedId::parse(chat_id) else {
            return; // Fire-and-forget: never fails.
        };
        let Some(inner) = self.weak.upgrade() else {
            return;
        };
        let Ok(handle) = crate::engine_handle_of(&inner, &scoped.engine.0) else {
            return;
        };
        {
            let mut state = lock(&self.state);
            let stamp = state.overlays.seen_at.entry(chat_id.to_owned()).or_insert(i64::MIN);
            if *stamp >= now_ms {
                return; // Monotonic: a stamp at or after now is a no-op.
            }
            *stamp = now_ms;
        }
        self.notify.notify_one();
        let raw = scoped.raw_id;
        crate::runtime::shared().spawn(async move {
            // Fire-and-forget: the optimistic stamp stands either way.
            let _ = handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({"op": "markChatSeen", "chatId": raw}),
                )
                .await;
        });
    }

    // ── pins / sections ───────────────────────────────────────────────────

    /// Pin: the desktop's `set_chat_pinned(pinned = true)`. A chat already
    /// in the raw pin list (hidden by a section) only leaves its section;
    /// otherwise it pins after the engine's last visible pin, leaves every
    /// section, and both writes land through the sidebar writer.
    pub fn pin_session(&self, chat_id: &str) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        let engine_id = handle.engine_id().to_owned();
        let (_profile, pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        if pins.contains(&raw) {
            // Hidden by a section: un-section only.
            return self.assign_section_internal(&inner, &engine_id, &raw, None);
        }
        let visible = sidebar::visible_pins(&pins, &sections);
        let change = PinChange::Pin {
            session_id: raw.clone(),
            after: visible.last().cloned(),
            before: None,
        };
        let mut next = pins.clone();
        change.project(&mut next);
        sidebar::validate_pin_list(&pins, &next).map_err(invalid_argument)?;
        {
            let mut state = lock(&self.state);
            state.pins.insert(engine_id.clone(), next);
        }
        // Roboco is engine-local for every scope (ADR 0004): the pin write
        // lands synchronously, so section membership clears at the same
        // moment.
        let next_sections = sidebar::assign_section(&sections, &raw, None);
        if next_sections != sections {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.clone(), next_sections);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Pin between neighbours (wave-10 41, upstream #909's
    /// `pin_session_at`), as a drop into Pinned does: a chat already in
    /// the raw pin list moves there, any other is pinned there (both `None`
    /// = after the last visible pin, like [`Self::pin_session`]); either
    /// way it leaves every section. Anchors are scoped ids; anchors from
    /// other engines count as absent.
    pub fn pin_session_at(
        &self,
        chat_id: &str,
        after: Option<&str>,
        before: Option<&str>,
    ) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        let engine_id = handle.engine_id().to_owned();
        let (_profile, pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        let after = same_engine_raw(after, &engine_id);
        let before = same_engine_raw(before, &engine_id);
        let change = if pins.contains(&raw) {
            PinChange::Move {
                session_id: raw.clone(),
                after,
                before,
            }
        } else if after.is_none() && before.is_none() {
            PinChange::Pin {
                session_id: raw.clone(),
                after: sidebar::visible_pins(&pins, &sections).last().cloned(),
                before: None,
            }
        } else {
            PinChange::Pin {
                session_id: raw.clone(),
                after,
                before,
            }
        };
        let mut next = pins.clone();
        change.project(&mut next);
        sidebar::validate_pin_list(&pins, &next).map_err(invalid_argument)?;
        let next_sections = sidebar::assign_section(&sections, &raw, None);
        if next == pins && next_sections == sections {
            return Ok(());
        }
        {
            let mut state = lock(&self.state);
            if next != pins {
                state.pins.insert(engine_id.clone(), next);
            }
            if next_sections != sections {
                state.sections.insert(engine_id.clone(), next_sections);
            }
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Unpin: the pin list minus the id.
    pub fn unpin_session(&self, chat_id: &str) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        let engine_id = handle.engine_id().to_owned();
        let (_profile, pins, _sections) = self.effective_sidebar(&inner, &engine_id)?;
        if !pins.contains(&raw) {
            return Ok(()); // A no-op, like the desktop's equality early return.
        }
        let next: Vec<String> = pins.iter().filter(|id| **id != raw).cloned().collect();
        sidebar::validate_pin_list(&pins, &next).map_err(invalid_argument)?;
        {
            let mut state = lock(&self.state);
            state.pins.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Reorder a pin between neighbours. Anchors are scoped ids; anchors
    /// from other engines are treated as absent, and a move of an unpinned
    /// id is a no-op (a stale move never resurrects an unpinned item).
    pub fn move_pin(
        &self,
        chat_id: &str,
        after: Option<&str>,
        before: Option<&str>,
    ) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (handle, raw) = require_chat(&inner, chat_id)?;
        ensure_writable(&handle)?;
        let engine_id = handle.engine_id().to_owned();
        let (_profile, pins, _sections) = self.effective_sidebar(&inner, &engine_id)?;
        if !pins.contains(&raw) {
            return Ok(());
        }
        let anchor = |id: Option<&str>| {
            id.and_then(|id| ScopedId::parse(id).ok())
                .filter(|scoped| scoped.engine.0 == engine_id)
                .map(|scoped| scoped.raw_id)
        };
        let change = PinChange::Move {
            session_id: raw.clone(),
            after: anchor(after),
            before: anchor(before),
        };
        let mut next = pins.clone();
        change.project(&mut next);
        if next == pins {
            return Ok(()); // The projection changed nothing.
        }
        sidebar::validate_pin_list(&pins, &next).map_err(invalid_argument)?;
        {
            let mut state = lock(&self.state);
            state.pins.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Create a section. `engine_id: None` resolves to the first engine
    /// (in pairing order) that is `Connected`. Returns the **scoped**
    /// section id.
    pub fn create_section(&self, name: &str, engine_id: Option<&str>) -> Result<String, ClientError> {
        let inner = self.inner()?;
        let engine_id = match engine_id {
            Some(engine_id) => engine_id.to_owned(),
            None => {
                let state = lock(&inner.engines);
                state
                    .entries
                    .iter()
                    .find(|entry| entry.state == EngineState::Connected)
                    .map(|entry| entry.paired.engine_id.clone())
                    .ok_or_else(|| ClientError::HostUnavailable {
                        message: "Engine is offline; reconnecting".into(),
                    })?
            }
        };
        let handle = crate::engine_handle_of(&inner, &engine_id)?;
        ensure_writable(&handle)?;
        ensure_sidebar_ready(&inner, &engine_id)?;
        let name = sidebar::validate_section_name(name).map_err(invalid_argument)?;
        let (_profile, _pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        if sections.len() >= MAX_SIDEBAR_SECTIONS {
            return Err(ClientError::InvalidArgument {
                message: format!("A workspace can have at most {MAX_SIDEBAR_SECTIONS} sidebar sections"),
            });
        }
        let id = uuid::Uuid::new_v4().to_string();
        let next = sidebar::create_section(&sections, &id, &name);
        {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(ScopedId::encode(
            &roboco_engine_client::registry::EngineKey(engine_id.to_owned()),
            &id,
        ))
    }

    pub fn rename_section(&self, section_id: &str, name: &str) -> Result<(), ClientError> {
        let (inner, engine_id, raw) = self.require_section(section_id)?;
        let name = sidebar::validate_section_name(name).map_err(invalid_argument)?;
        let (_profile, _pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        if !sections.iter().any(|section| section.id == raw) {
            return Err(ClientError::NotFound {
                message: format!("Unknown section: {section_id}"),
            });
        }
        let next = sidebar::rename_section(&sections, &raw, &name);
        {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Deleting a section drops membership only; its sessions return to
    /// Recent.
    pub fn delete_section(&self, section_id: &str) -> Result<(), ClientError> {
        let (inner, engine_id, raw) = self.require_section(section_id)?;
        let (_profile, _pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        if !sections.iter().any(|section| section.id == raw) {
            return Err(ClientError::NotFound {
                message: format!("Unknown section: {section_id}"),
            });
        }
        let next = sidebar::delete_section(&sections, &raw);
        {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// The fold is engine-synced and shared with the desktop and web
    /// (upstream iOS kept every fold local).
    pub fn set_section_collapsed(&self, section_id: &str, collapsed: bool) -> Result<(), ClientError> {
        let (inner, engine_id, raw) = self.require_section(section_id)?;
        let (_profile, _pins, sections) = self.effective_sidebar(&inner, &engine_id)?;
        if !sections.iter().any(|section| section.id == raw) {
            return Err(ClientError::NotFound {
                message: format!("Unknown section: {section_id}"),
            });
        }
        let next = sidebar::set_section_collapsed(&sections, &raw, collapsed);
        {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.clone(), next);
        }
        self.kick_writer(&engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Move a chat into a section (`None` = back to Recent). Exclusive
    /// membership; a target on another engine is refused.
    pub fn assign_section(&self, chat_id: &str, section_id: Option<&str>) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (_handle, raw) = require_chat(&inner, chat_id)?;
        let engine_id = ScopedId::parse(chat_id)
            .map(|scoped| scoped.engine.0)
            .map_err(not_found_chat(chat_id))?;
        let target = match section_id {
            None => None,
            Some(section_id) => {
                let scoped = ScopedId::parse(section_id).map_err(not_found_section(section_id))?;
                if scoped.engine.0 != engine_id {
                    return Err(ClientError::InvalidArgument {
                        message: "The section and the session belong to different engines".into(),
                    });
                }
                Some(scoped.raw_id)
            }
        };
        self.assign_section_internal(&inner, &engine_id, &raw, target.as_deref())
    }

    /// Move a chat into a section between its members (wave-10 41,
    /// upstream #909's `place_session`), as a drop into the section does;
    /// `None` anchors at either end, both `None` appends; `section_id:
    /// None` = back to Recent. Anchors are scoped ids of the same engine.
    pub fn place_session(
        &self,
        chat_id: &str,
        section_id: Option<&str>,
        after: Option<&str>,
        before: Option<&str>,
    ) -> Result<(), ClientError> {
        let inner = self.inner()?;
        let (_handle, raw) = require_chat(&inner, chat_id)?;
        let engine_id = ScopedId::parse(chat_id)
            .map(|scoped| scoped.engine.0)
            .map_err(not_found_chat(chat_id))?;
        let target = match section_id {
            None => None,
            Some(section_id) => {
                let scoped = ScopedId::parse(section_id).map_err(not_found_section(section_id))?;
                if scoped.engine.0 != engine_id {
                    return Err(ClientError::InvalidArgument {
                        message: "The section and the session belong to different engines".into(),
                    });
                }
                Some(scoped.raw_id)
            }
        };
        let after = same_engine_raw(after, &engine_id);
        let before = same_engine_raw(before, &engine_id);
        self.place_internal(
            &inner,
            &engine_id,
            &raw,
            target.as_deref(),
            after.as_deref(),
            before.as_deref(),
        )
    }

    /// The shared tail of `assign_section` / `pin_session`'s un-section
    /// arm: connected + sidebar-ready, then the section edit.
    fn assign_section_internal(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
        raw: &str,
        target: Option<&str>,
    ) -> Result<(), ClientError> {
        self.place_internal(inner, engine_id, raw, target, None, None)
    }

    /// The section edit behind `assign_section` (no anchors: appended) and
    /// `place_session`: connected + sidebar-ready, then the placement.
    fn place_internal(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
        raw: &str,
        target: Option<&str>,
        after: Option<&str>,
        before: Option<&str>,
    ) -> Result<(), ClientError> {
        let handle = crate::engine_handle_of(inner, engine_id)?;
        ensure_writable(&handle)?;
        ensure_sidebar_ready(inner, engine_id)?;
        let (_profile, _pins, sections) = self.effective_sidebar(inner, engine_id)?;
        if let Some(target) = target
            && !sections.iter().any(|section| section.id == target)
        {
            return Err(ClientError::NotFound {
                message: "The section no longer exists".into(),
            });
        }
        let next = sidebar::place_in_section(&sections, raw, target, after, before);
        if next == sections {
            return Ok(());
        }
        {
            let mut state = lock(&self.state);
            state.sections.insert(engine_id.to_owned(), next);
        }
        self.kick_writer(engine_id);
        self.notify.notify_one();
        Ok(())
    }

    /// Resolve a scoped section id to `(inner, engine, raw id)` with the
    /// connection and readiness checks done.
    fn require_section(&self, section_id: &str) -> Result<(Arc<ClientInner>, String, String), ClientError> {
        let inner = self.inner()?;
        let scoped = ScopedId::parse(section_id).map_err(not_found_section(section_id))?;
        let handle = crate::engine_handle_of(&inner, &scoped.engine.0)?;
        ensure_writable(&handle)?;
        ensure_sidebar_ready(&inner, &scoped.engine.0)?;
        Ok((inner, scoped.engine.0, scoped.raw_id))
    }

    /// The effective sidebar state of one engine: the pending overlay,
    /// else the last confirmed frame's bucket for its profile key.
    fn effective_sidebar(
        &self,
        inner: &Arc<ClientInner>,
        engine_id: &str,
    ) -> Result<(String, Vec<String>, Vec<SidebarSection>), ClientError> {
        let profile = ensure_sidebar_ready(inner, engine_id)?;
        let (confirmed_pins, confirmed_sections) = {
            let entries = lock(&inner.engines);
            let entry = entries
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == engine_id)
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown engine: {engine_id}"),
                })?;
            let sidebar = entry.sidebar.clone();
            (
                sidebar
                    .as_ref()
                    .and_then(|s| s.pins_by_profile.get(&profile))
                    .cloned()
                    .unwrap_or_default(),
                sidebar
                    .as_ref()
                    .and_then(|s| s.sections_by_profile.get(&profile))
                    .cloned()
                    .unwrap_or_default(),
            )
        };
        let state = lock(&self.state);
        let pins = state
            .pins
            .get(engine_id)
            .cloned()
            .unwrap_or(confirmed_pins);
        let sections = state
            .sections
            .get(engine_id)
            .cloned()
            .unwrap_or(confirmed_sections);
        Ok((profile, pins, sections))
    }

    /// Wake (or start) the engine's serialized sidebar writer.
    fn kick_writer(&self, engine_id: &str) {
        let state = self.state.clone();
        let weak = self.weak.clone();
        let mut guard = lock(&state);
        let sender = guard
            .writers
            .entry(engine_id.to_owned())
            .or_insert_with(|| {
                let (tx, rx) = mpsc::unbounded_channel();
                let state = state.clone();
                let engine_id = engine_id.to_owned();
                crate::runtime::shared().spawn(async move {
                    sidebar_writer(weak, state, engine_id, rx).await;
                });
                tx
            });
        let _ = sender.send(());
    }

    fn inner(&self) -> Result<Arc<ClientInner>, ClientError> {
        self.weak.upgrade().ok_or(ClientError::Closed)
    }
}

/// The sidebar bucket for a profile key; when the key is unknown (the
/// engine is offline and never connected this process), a snapshot with
/// exactly one bucket is that engine's own — an engine serves one profile —
/// so the cached pins still render offline. Ambiguous multi-bucket
/// snapshots (never in practice) render nothing.
fn sidebar_bucket<T: Clone>(map: &std::collections::BTreeMap<String, Vec<T>>, profile: &Option<String>) -> Vec<T> {
    match profile {
        Some(key) => map.get(key).cloned().unwrap_or_default(),
        None => {
            if map.len() == 1 {
                map.values().next().cloned().unwrap_or_default()
            } else {
                Vec::new()
            }
        }
    }
}

/// Drop overlays the engine's own frames have confirmed: archived, title,
/// seen (once the engine's `last_seen_at` reaches the stamp), deleted (the
/// row is gone), and the pins/sections overlays that equal the confirmed
/// bucket. `seen_at` is never rolled back — it only clears on engine
/// confirmation.
fn clear_confirmed(state: &mut StoreState, gathered: &[GatheredEngine]) {
    let chat_of = |scoped: &str| -> Option<Chat> {
        let scoped = ScopedId::parse(scoped).ok()?;
        gathered
            .iter()
            .find(|engine| engine.engine_id == scoped.engine.0)
            .and_then(|engine| {
                engine
                    .rows
                    .chats
                    .iter()
                    .find(|chat| chat.id == scoped.raw_id)
                    .cloned()
            })
    };
    state.overlays.archived.retain(|id, want| {
        chat_of(id).map(|chat| chat.archived) != Some(*want)
    });
    state.overlays.title.retain(|id, want| {
        let confirmed = chat_of(id).and_then(|chat| {
            chat.title.as_deref().map(|t| t.trim().to_owned())
        });
        confirmed.as_deref() != Some(want.trim())
    });
    state.overlays.seen_at.retain(|id, stamp| {
        chat_of(id)
            .and_then(|chat| chat.last_seen_at.map(|t| t.timestamp_millis()))
            .is_none_or(|seen| seen < *stamp)
    });
    state.overlays.deleted.retain(|id| chat_of(id).is_some());
    for engine in gathered {
        if let Some(pins) = state.pins.get(&engine.engine_id) {
            let confirmed: Vec<String> = engine
                .sidebar
                .as_ref()
                .map(|s| sidebar_bucket(&s.pins_by_profile, &engine.profile_key))
                .unwrap_or_default();
            if confirmed == *pins {
                state.pins.remove(&engine.engine_id);
            }
        }
        if let Some(sections) = state.sections.get(&engine.engine_id) {
            let confirmed: Vec<SidebarSection> = engine
                .sidebar
                .as_ref()
                .map(|s| sidebar_bucket(&s.sections_by_profile, &engine.profile_key))
                .unwrap_or_default();
            if confirmed == *sections {
                state.sections.remove(&engine.engine_id);
            }
        }
    }
}

/// One engine's gathered state: the confirmed frame data plus the view
/// fields the derive synthesizes from.
struct GatheredEngine {
    engine_id: String,
    display_name: String,
    device_id: Option<String>,
    info: Option<roboco_proto::EngineInfo>,
    online: bool,
    from_cache: bool,
    rows: CachedRows,
    sidebar: Option<SidebarStateSnapshot>,
    profile_key: Option<String>,
}

impl GatheredEngine {
    /// The derive input: the effective pins/sections (overlay ?? confirmed
    /// bucket — the offline fallback when the profile key is unknown).
    fn rows_view(&self, state: &StoreState) -> EngineRows {
        let confirmed_pins = self
            .sidebar
            .as_ref()
            .map(|s| sidebar_bucket(&s.pins_by_profile, &self.profile_key))
            .unwrap_or_default();
        let confirmed_sections = self
            .sidebar
            .as_ref()
            .map(|s| sidebar_bucket(&s.sections_by_profile, &self.profile_key))
            .unwrap_or_default();
        EngineRows {
            engine_id: self.engine_id.clone(),
            display_name: self.display_name.clone(),
            device_id: self.device_id.clone(),
            info: self.info.clone(),
            online: self.online,
            from_cache: self.from_cache,
            sidebar_present: self.sidebar.is_some(),
            rows: self.rows.clone(),
            profile_key: self.profile_key.clone(),
            pins: state
                .pins
                .get(&self.engine_id)
                .cloned()
                .unwrap_or(confirmed_pins),
            sections: state
                .sections
                .get(&self.engine_id)
                .cloned()
                .unwrap_or(confirmed_sections),
        }
    }
}

/// Read the engine table once: the confirmed per-engine data the derive
/// and the overlay clearing read.
fn gather(inner: &Arc<ClientInner>) -> Vec<GatheredEngine> {
    let state = lock(&inner.engines);
    state
        .entries
        .iter()
        .map(|entry| GatheredEngine {
            engine_id: entry.paired.engine_id.clone(),
            display_name: crate::engine_view(entry).name,
            device_id: entry
                .info
                .as_ref()
                .map(|info| info.device_id.clone())
                .or_else(|| entry.paired.device_id.clone()),
            info: entry.info.clone(),
            online: entry.state == EngineState::Connected,
            from_cache: entry.from_cache,
            rows: entry.rows.clone(),
            sidebar: entry.sidebar.clone(),
            profile_key: sidebar::profile_key(entry.info.as_ref()),
        })
        .collect()
}

// ── the serialized per-engine sidebar writer ──────────────────────────────

/// One engine's sidebar write loop: single-flight, serialized per engine,
/// reading the latest overlay each pass (never a stale copy). A successful
/// write adopts the RPC's fresh snapshot as the confirmed state; a failed
/// one reverts the overlay and emits the notice.
async fn sidebar_writer(
    weak: std::sync::Weak<ClientInner>,
    state: Arc<Mutex<StoreState>>,
    engine_id: String,
    mut rx: mpsc::UnboundedReceiver<()>,
) {
    while rx.recv().await.is_some() {
        write_pending(&weak, &state, &engine_id).await;
    }
}

async fn write_pending(weak: &std::sync::Weak<ClientInner>, state: &Arc<Mutex<StoreState>>, engine_id: &str) {
    loop {
        let Some(inner) = weak.upgrade() else {
            return;
        };
        let (handle, profile, confirmed) = {
            let entries = lock(&inner.engines);
            let Some(entry) = entries
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == engine_id)
            else {
                return; // The engine was forgotten.
            };
            let handle = EngineHandle::from_parts(Arc::downgrade(&inner), entry);
            (
                handle,
                sidebar::profile_key(entry.info.as_ref()),
                entry.sidebar.clone(),
            )
        };
        let Some(profile) = profile else {
            return; // No profile key yet: nothing writable.
        };
        let (pins_job, sections_job) = {
            let state = lock(state);
            let pins = state
                .pins
                .get(engine_id)
                .filter(|pins| {
                    Some(pins.as_slice())
                        != confirmed
                            .as_ref()
                            .and_then(|s| s.pins_by_profile.get(&profile))
                            .map(|p| p.as_slice())
                })
                .cloned();
            let sections = state
                .sections
                .get(engine_id)
                .filter(|sections| {
                    Some(sections.as_slice())
                        != confirmed
                            .as_ref()
                            .and_then(|s| s.sections_by_profile.get(&profile))
                            .map(|s| s.as_slice())
                })
                .cloned();
            (pins, sections)
        };
        if pins_job.is_none() && sections_job.is_none() {
            return; // Clean: the overlay equals the confirmed state.
        }
        if let Some(ids) = pins_job {
            match handle
                .call(
                    roboco_rpc::methods::SET_SIDEBAR_PINS,
                    serde_json::json!({"profileKey": profile, "sessionIds": ids}),
                )
                .await
                .and_then(decode_sidebar_reply)
            {
                Ok(reply) => adopt_sidebar(&inner, engine_id, reply),
                Err(error) => {
                    revert_sidebar(state, engine_id, Surface::Pins(ids), &error, &inner);
                }
            }
        }
        if let Some(sections) = sections_job {
            match handle
                .call(
                    roboco_rpc::methods::SET_SIDEBAR_SECTIONS,
                    serde_json::json!({"profileKey": profile, "sections": sections}),
                )
                .await
                .and_then(decode_sidebar_reply)
            {
                Ok(reply) => adopt_sidebar(&inner, engine_id, reply),
                Err(error) => {
                    revert_sidebar(state, engine_id, Surface::Sections(sections), &error, &inner);
                }
            }
        }
        // Loop: a mid-flight edit stays dirty and is re-sent.
    }
}

/// The surface a sidebar write touched (for the revert).
enum Surface {
    Pins(Vec<String>),
    Sections(Vec<SidebarSection>),
}

/// Decode a `SetSidebarPins`/`SetSidebarSections` reply (the fresh
/// `SidebarStateSnapshot`).
fn decode_sidebar_reply(value: serde_json::Value) -> Result<SidebarStateSnapshot, ClientError> {
    serde_json::from_value(value).map_err(|error| ClientError::HostError {
        message: error.to_string(),
    })
}

/// Adopt the RPC reply as the engine's confirmed sidebar state: the reply
/// is fresher than any frame that could arrive after it, so the next frame
/// (or the recompute) confirms the overlay away.
fn adopt_sidebar(inner: &Arc<ClientInner>, engine_id: &str, reply: SidebarStateSnapshot) {
    crate::update_engine(inner, engine_id, |entry| {
        entry.cache.save_sidebar(reply.clone());
        entry.sidebar = Some(reply);
    });
    crate::publish(inner);
}

/// Revert the overlay for a failed write (a newer edit stays) and emit the
/// notice — the engine never saw this write.
fn revert_sidebar(
    state: &Arc<Mutex<StoreState>>,
    engine_id: &str,
    sent: Surface,
    error: &ClientError,
    inner: &Arc<ClientInner>,
) {
    let mut state = lock(state);
    match &sent {
        Surface::Pins(ids) => {
            if state.pins.get(engine_id) == Some(ids) {
                state.pins.remove(engine_id);
            }
        }
        Surface::Sections(sections) => {
            if state.sections.get(engine_id) == Some(sections) {
                state.sections.remove(engine_id);
            }
        }
    }
    drop(state);
    inner
        .pump
        .notice(format!("{PIN_WRITE_NOTICE}{error}"));
    crate::publish(inner);
}

// ── shared helpers ────────────────────────────────────────────────────────

/// Revert the overlays a failed write set (a newer edit stays), then wake
/// the re-derive.
fn revert_overlay(state: &Arc<Mutex<StoreState>>, f: impl FnOnce(&mut Overlays)) {
    let mut state = lock(state);
    f(&mut state.overlays);
}

/// Emit the generic mutation notice (`{error}`, or the fallback copy when
/// there is no message) and wake the re-derive.
fn notice_engine(weak: &std::sync::Weak<ClientInner>, message: String) {
    let Some(inner) = weak.upgrade() else {
        return;
    };
    let message = if message.trim().is_empty() {
        MUTATION_NOTICE.to_owned()
    } else {
        message
    };
    inner.pump.notice(message);
    crate::publish(&inner);
}

/// Resolve a scoped chat id to its engine handle and raw id, proving the
/// chat exists (any row: archived, child, or dangling-space).
fn require_chat(inner: &Arc<ClientInner>, chat_id: &str) -> Result<(EngineHandle, String), ClientError> {
    let scoped = ScopedId::parse(chat_id).map_err(not_found_chat(chat_id))?;
    let handle = crate::engine_handle_of(inner, &scoped.engine.0)?;
    let exists = {
        let state = lock(&inner.engines);
        state
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == scoped.engine.0)
            .is_some_and(|entry| {
                entry
                    .rows
                    .chats
                    .iter()
                    .any(|chat| chat.id == scoped.raw_id)
            })
    };
    if !exists {
        return Err(ClientError::NotFound {
            message: format!("Unknown chat: {chat_id}"),
        });
    }
    Ok((handle, scoped.raw_id))
}

fn chat_of(inner: &Arc<ClientInner>, scoped: &ScopedId) -> Option<Chat> {
    let state = lock(&inner.engines);
    state
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == scoped.engine.0)
        .and_then(|entry| {
            entry
                .rows
                .chats
                .iter()
                .find(|chat| chat.id == scoped.raw_id)
                .cloned()
        })
}

/// Writes need a connected engine: parked fails `Auth`, anything else
/// fails `HostUnavailable` (never queued). Shared by the workspace
/// mutations and ticket 09's session/project creates.
pub(crate) fn ensure_writable(handle: &EngineHandle) -> Result<(), ClientError> {
    match handle.state() {
        EngineState::Connected => Ok(()),
        EngineState::Parked { .. } => Err(ClientError::Auth {
            message: "Engine is parked; pair again to connect.".into(),
        }),
        _ => Err(ClientError::HostUnavailable {
            message: "Engine is offline; reconnecting".into(),
        }),
    }
}

/// Sidebar writes also need the engine's sidebar state to be known: the
/// profile key (from `EngineInfo`) plus a first frame or a cached
/// `sidebar.json`.
fn ensure_sidebar_ready(inner: &Arc<ClientInner>, engine_id: &str) -> Result<String, ClientError> {
    let state = lock(&inner.engines);
    let entry = state
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == engine_id)
        .ok_or_else(|| ClientError::NotFound {
            message: format!("Unknown engine: {engine_id}"),
        })?;
    let profile = sidebar::profile_key(entry.info.as_ref()).ok_or_else(|| ClientError::Unsupported {
        message: "pins are not synced yet".into(),
    })?;
    if entry.sidebar.is_none() {
        return Err(ClientError::Unsupported {
            message: "pins are not synced yet".into(),
        });
    }
    Ok(profile)
}

fn invalid_argument(message: &'static str) -> ClientError {
    ClientError::InvalidArgument {
        message: message.to_owned(),
    }
}

fn not_found_chat(chat_id: &str) -> impl Fn(roboco_rpc::RpcError) -> ClientError + '_ {
    move |_| ClientError::NotFound {
        message: format!("Unknown chat: {chat_id}"),
    }
}

fn not_found_section(section_id: &str) -> impl Fn(roboco_rpc::RpcError) -> ClientError + '_ {
    move |_| ClientError::NotFound {
        message: format!("Unknown section: {section_id}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ClientEvent;

    /// An RPC failure reverts the overlay and emits the notice: a real
    /// engine refuses an ordered-list replace that adds past its pin limit
    /// (the same rule the client's own guard enforces at the API level —
    /// injected here directly, to exercise the write path). The engine's
    /// `Failed` reply surfaces as `HostError`, the overlay reverts, and
    /// `ClientEvent::Notice` carries "Couldn't save pins: …".
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_failed_sidebar_write_reverts_the_overlay_and_notifies() {
        let engine_dir = tempfile::tempdir().unwrap();
        let data_dir = tempfile::tempdir().unwrap();
        let core = roboco_engine::EngineCore::assemble_with_profile(
            roboco_engine::EngineProfile::local(engine_dir.path()).unwrap(),
            std::sync::Arc::new(roboco_engine::smoke_registry()),
            roboco_engine::HarnessId::Mock,
        )
        .unwrap();
        let mut listener = roboco_engine::serve_engine_remote(
            "127.0.0.1:0".parse().unwrap(),
            core.rpc_service(),
            engine_dir.path(),
        )
        .await
        .unwrap();
        let recorder = std::sync::Arc::new(Recorder::default());
        let client = crate::Client::new_with_timings(
            crate::ClientConfig {
                data_dir: data_dir.path().to_owned(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            recorder.clone(),
            crate::test_support::test_timings(),
        )
        .unwrap();
        let store = client
            .inner
            .workspace
            .get()
            .expect("the store starts with the client");
        let pairing = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
        let code = pairing.create_code("test", 300).unwrap();
        let url = format!("http://{}/pair#token={}", listener.address, code.credential);
        let record = client.pair_engine(&url, None).await.unwrap();
        // Connected, sidebar frame arrived.
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let ready = {
                let state = lock(&client.inner.engines);
                state
                    .entries
                    .iter()
                    .any(|entry| {
                        entry.paired.engine_id == record.engine_id
                            && entry.state == EngineState::Connected
                            && entry.sidebar.is_some()
                            && entry.info.is_some()
                    })
            };
            if ready || std::time::Instant::now() > deadline {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        // Inject the overlay the engine refuses: a section name past the
        // engine's 120-char bound (the client's own guard would refuse it
        // at the API level — this exercises the write path's engine-side
        // check).
        let sections = vec![SidebarSection {
            id: "too-long".into(),
            name: "x".repeat(SIDEBAR_SECTION_NAME_MAX_CHARS + 1),
            session_ids: Vec::new(),
            collapsed: false,
        }];
        lock(&store.state)
            .sections
            .insert(record.engine_id.clone(), sections.clone());
        store.kick_writer(&record.engine_id);
        // The notice arrives and the overlay reverts.
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        loop {
            let notice = recorder
                .events()
                .iter()
                .any(|event| matches!(event, ClientEvent::Notice { message } if message.contains(PIN_WRITE_NOTICE)));
            if notice {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the pin write failure never notified: {:?}",
                recorder.events()
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(
            !lock(&store.state).sections.contains_key(&record.engine_id),
            "the failed write's overlay is reverted"
        );
        // The engine's own sidebar state is untouched.
        assert!(
            lock(&client.inner.engines)
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == record.engine_id)
                .and_then(|entry| entry.sidebar.clone())
                .is_some_and(|sidebar| sidebar.pins_by_profile.is_empty())
        );
        client.shutdown();
        listener.stop().await;
        core.shutdown().await;
    }

    /// A minimal event recorder.
    #[derive(Default)]
    struct Recorder(Mutex<Vec<ClientEvent>>);

    impl crate::ClientListener for Recorder {
        fn on_event(&self, event: ClientEvent) {
            lock(&self.0).push(event);
        }
    }

    impl Recorder {
        fn events(&self) -> Vec<ClientEvent> {
            lock(&self.0).clone()
        }
    }
}

/// A scoped anchor id as a raw id of `engine_id`; another engine's (or an
/// unparsable) anchor counts as absent.
fn same_engine_raw(id: Option<&str>, engine_id: &str) -> Option<String> {
    id.and_then(|id| ScopedId::parse(id).ok())
        .filter(|scoped| scoped.engine.0 == engine_id)
        .map(|scoped| scoped.raw_id)
}
