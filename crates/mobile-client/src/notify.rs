//! The mobile notification driver (ticket 28): turns the fleet's session
//! watches into the events the phone notifies on.
//!
//! The **decision rule** is the shared one — `roboco_proto`'s
//! [`SessionNotificationState::sound_since`] (moved 1:1 from the desktop's
//! `crates/ui/src/sound.rs` so desktop, web and mobile can never disagree).
//! This module adds only the mobile-only halves around it:
//!
//! - **Baselines** keyed `(engine id, raw chat id)`; a row's first
//!   appearance seeds silently (boot, newly paired engine) and the new
//!   baseline is saved *before* deciding, every time — suppressed events
//!   are never replayed. Baselines survive reconnects (the reconnect
//!   catch-up compares against them) and are dropped when an engine is
//!   forgotten or re-paired.
//! - **Only top-level chats emit**: a session whose chat row is missing
//!   from `WatchChats` or has a `parentChatId` updates its baseline but
//!   never emits (the side-chat rule).
//! - **`send_pending`**: tracked in the driver (ticket 08's pending-send
//!   ledger lives in the session handle and dies with it; the driver's
//!   window outlives eviction): true from the `QueueCommand` call until
//!   the run it started ends (the session left Working) or 30 s pass —
//!   no Done for the phone's own pending send, questions still ring.
//! - **Reconnect catch-up**: on the first snapshot after a generation
//!   bump, a chat whose pre-gap baseline was Working/AwaitingInput and
//!   whose completion marker moved while unseen emits `Done` even though
//!   the 45 s freshness gate now suppresses it (a phone that slept through
//!   the completion would otherwise never learn it happened).
//! - **Link lost/restored**: ≥ 4 s continuously non-connected while one of
//!   the engine's chats was Working when it dropped, and ≥ 5 s
//!   (`STARTUP_QUIET`) since the engine first connected in this process;
//!   once per outage, restored on reconnect.
//! - **Resolved**: a notified state ends — Done/Failed once the chat is no
//!   longer unseen (seen on any device), Input once the indicator leaves
//!   AwaitingInput; the platform cancels that chat's notification.
//! - **Run activity**: the aggregate `working`/`awaiting` the foreground
//!   service's ongoing notification renders (top-level chats only).
//!
//! The decision half is pure ([`DriverState::apply`]) and unit-tested with
//! synthetic snapshots; the hub wraps it in one task that re-derives on
//! fleet changes, its own tick, and send notifications, delivering on a
//! client runtime thread. No lock is held while the listener runs — a
//! Kotlin callback may re-enter Rust synchronously.
//!
//! Clock skew: `updated_at` is the engine's clock, but ticket 06 re-stamps
//! rows on the phone's clock as frames arrive (`clock.rs`), so comparing
//! against `Utc::now()` here stays correct.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, Weak};

use chrono::{DateTime, Utc};
use roboco_proto::view::{Indicator, NotifyKind, SessionNotificationState};
use roboco_proto::{Chat, Session};
use tokio_util::sync::CancellationToken;

use roboco_engine_client::registry::{EngineKey, ScopedId};

use crate::engines::EngineState;
use crate::timings::NotifyTimings;
use crate::{ClientInner, lock};

// ── the listener surface (mirrors the FFI `NotificationSink`) ─────────────

/// A session notification event, ready for the platform: `kind` from the
/// shared rule, the title with the "New session" fallback already applied.
/// `chat_id` is the **scoped** id (`engine:v1:…` — the same id the Sessions
/// list uses), so two engines with colliding raw ids cannot misroute a tap.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionEvent {
    pub engine_id: String,
    pub engine_name: String,
    /// The engine-scoped chat id.
    pub chat_id: String,
    pub chat_title: String,
    pub kind: NotifyKind,
}

/// One working run in the aggregate activity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunRef {
    pub engine_id: String,
    pub engine_name: String,
    /// The engine-scoped chat id.
    pub chat_id: String,
    pub chat_title: String,
    /// `Session.started_at` (phone-stamped), for the chronometer.
    pub started_at_ms: Option<i64>,
    /// This process sent the run's `QueueCommand` (or created the chat with
    /// a first send) and the session has not left Working since.
    pub from_this_device: bool,
}

/// The aggregate run activity across every engine, after every change.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RunActivity {
    /// Working top-level chats, one `RunRef` each.
    pub working: Vec<RunRef>,
    /// Top-level chats in AwaitingInput.
    pub awaiting: u32,
}

/// The driver's delivery surface (the FFI layer bridges this to the Kotlin
/// `NotificationSink`). Called on a client runtime thread; implementations
/// must return quickly and never block.
pub trait NotifyListener: Send + Sync + 'static {
    fn session_event(&self, event: SessionEvent);
    fn resolved(&self, engine_id: String, chat_id: String);
    fn link_lost(&self, engine_id: String, engine_name: String);
    fn link_restored(&self, engine_id: String);
    fn run_activity(&self, activity: RunActivity);
}

// ── the pure state machine ────────────────────────────────────────────────

/// What one pass over an engine's snapshot decided to deliver.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Emission {
    Session(SessionEvent),
    Resolved { engine_id: String, chat_id: String },
    LinkLost { engine_id: String, engine_name: String },
    LinkRestored { engine_id: String },
    Activity(RunActivity),
}

/// One engine's slice of the driver state.
#[derive(Debug, Default)]
pub(crate) struct EngineNotifyState {
    /// Baseline per raw chat id, saved before every decision.
    baseline: HashMap<String, SessionNotificationState>,
    /// The last link generation seen; `None` until the first snapshot
    /// (seeds silently). A bump with a previous value ≥ 1 is a reconnect.
    generation: Option<u64>,
    /// The engine link watch (LinkLost/LinkRestored).
    link: LinkWatch,
    /// This device's sends per raw chat id.
    sends: HashMap<String, SendTrack>,
    /// A live notified state per raw chat id, awaiting its Resolved.
    notified: HashMap<String, NotifyKind>,
}

/// One send of this device: the window that suppresses its own completion
/// and the attribution that marks the working run `from_this_device`. The
/// track is dropped when the run it started ends (the session left
/// Working) or when the window expires unobserved — a surviving track with
/// `seen_working` is this device's run.
#[derive(Debug, Clone, Copy)]
pub(crate) struct SendTrack {
    pub(crate) sent_at_ms: i64,
    /// The session was observed Working after the send.
    seen_working: bool,
}

/// The engine link watch: emits `LinkLost` after `link_grace` of continuous
/// non-connected time (while a chat was Working at the drop and past
/// `startup_quiet` since the first connect), once per outage, and
/// `LinkRestored` on the reconnect that ends it.
#[derive(Debug, Default)]
pub(crate) struct LinkWatch {
    first_connected_ms: Option<i64>,
    non_connected_since_ms: Option<i64>,
    working_at_drop: bool,
    lost_emitted: bool,
}

impl LinkWatch {
    /// Feed one observation; append the link emissions it earned.
    fn update(
        &mut self,
        state: &EngineState,
        working_now: bool,
        now_ms: i64,
        timings: &NotifyTimings,
        engine_id: &str,
        engine_name: &str,
        out: &mut Vec<Emission>,
    ) {
        let grace_ms = timings.link_grace.as_millis() as i64;
        let quiet_ms = timings.startup_quiet.as_millis() as i64;
        if *state == EngineState::Connected {
            if self.first_connected_ms.is_none() {
                self.first_connected_ms = Some(now_ms);
            }
            if self.non_connected_since_ms.take().is_some() && self.lost_emitted {
                self.lost_emitted = false;
                out.push(Emission::LinkRestored {
                    engine_id: engine_id.to_owned(),
                });
            }
            return;
        }
        if self.non_connected_since_ms.is_none() {
            self.non_connected_since_ms = Some(now_ms);
            // "≥1 of that engine's chats was Working when it dropped": the
            // last snapshot before the drop, not whatever the rows decay to
            // during the outage.
            self.working_at_drop = working_now;
        }
        let since = self.non_connected_since_ms.unwrap_or(now_ms);
        let started = self.first_connected_ms;
        if !self.lost_emitted
            && now_ms - since >= grace_ms
            && self.working_at_drop
            && started.is_some_and(|first| now_ms - first >= quiet_ms)
        {
            self.lost_emitted = true;
            out.push(Emission::LinkLost {
                engine_id: engine_id.to_owned(),
                engine_name: engine_name.to_owned(),
            });
        }
    }
}

/// One engine's snapshot, gathered for a decision pass. Cloned rows (the
/// engines lock is released before `apply` runs and before delivery).
pub(crate) struct EngineInput {
    pub(crate) engine_id: String,
    pub(crate) engine_name: String,
    pub(crate) state: EngineState,
    pub(crate) generation: u64,
    /// No live frame has arrived yet this process (rows straight from the
    /// offline cache): the driver stays silent — the first live frame
    /// seeds every baseline, so boot and a newly paired engine never emit
    /// for events the phone did not observe.
    pub(crate) from_cache: bool,
    pub(crate) sessions: Vec<Session>,
    pub(crate) chats: Vec<Chat>,
}

/// The whole driver's pure state: per-engine slices plus the
/// emitted-on-change gate for the run activity.
#[derive(Debug, Default)]
pub(crate) struct DriverState {
    engines: HashMap<String, EngineNotifyState>,
    last_activity: Option<RunActivity>,
    timings: NotifyTimings,
}

impl DriverState {
    pub(crate) fn new(timings: NotifyTimings) -> Self {
        Self {
            engines: HashMap::new(),
            last_activity: None,
            timings,
        }
    }

    /// Record this device's Run/Steer `QueueCommand` for a chat (the
    /// pending-send window opens now).
    pub(crate) fn note_send(&mut self, engine_id: &str, raw_chat_id: &str, now_ms: i64) {
        self.engines.entry(engine_id.to_owned()).or_default().sends.insert(
            raw_chat_id.to_owned(),
            SendTrack {
                sent_at_ms: now_ms,
                seen_working: false,
            },
        );
    }

    /// Drop an engine's state (forgotten or re-paired: baselines, sends,
    /// notified states and the link watch all start over).
    pub(crate) fn reset_engine(&mut self, engine_id: &str) {
        self.engines.remove(engine_id);
    }

    /// One decision pass over the fleet; returns everything to deliver, in
    /// order (session events, resolved, link, then the activity once if it
    /// changed).
    pub(crate) fn apply(&mut self, engines: &[EngineInput], now: DateTime<Utc>) -> Vec<Emission> {
        let now_ms = now.timestamp_millis();
        let mut out: Vec<Emission> = Vec::new();
        // States of engines no longer in the fleet are gone for good.
        self.engines.retain(|id, _| {
            engines.iter().any(|engine| &engine.engine_id == id)
        });
        let mut working: Vec<RunRef> = Vec::new();
        let mut awaiting: u32 = 0;
        for engine in engines {
            if engine.from_cache {
                continue;
            }
            let state = self.engines.entry(engine.engine_id.clone()).or_default();
            apply_engine(state, engine, now, now_ms, &self.timings, &mut out);
            // The activity aggregate (top-level chats only): the rows were
            // already rebased on the phone's clock, so `now` works.
            let mut chat_by_id = HashMap::with_capacity(engine.chats.len());
            for chat in &engine.chats {
                chat_by_id.insert(chat.id.as_str(), chat);
            }
            for session in &engine.sessions {
                let Some(chat) = chat_by_id.get(session.chat_id.as_str()) else {
                    continue;
                };
                if chat.parent_chat_id.is_some() {
                    continue;
                }
                let indicator = SessionNotificationState::new(session, now).indicator;
                match indicator {
                    Indicator::Working => working.push(RunRef {
                        engine_id: engine.engine_id.clone(),
                        engine_name: engine.engine_name.clone(),
                        chat_id: scope_id(&engine.engine_id, &chat.id),
                        chat_title: chat_title(chat),
                        started_at_ms: session.started_at.map(|t| t.timestamp_millis()),
                        from_this_device: state
                            .sends
                            .get(chat.id.as_str())
                            .is_some_and(|t| t.seen_working),
                    }),
                    Indicator::AwaitingInput => awaiting = awaiting.saturating_add(1),
                    _ => {}
                }
            }
        }
        working.sort_by(|a, b| a.chat_id.cmp(&b.chat_id));
        let activity = RunActivity { working, awaiting };
        // Nothing live yet (every engine still on its offline cache): seed
        // the gate silently, like the baselines — the cached boot never
        // emits, and the first live pass only emits on a real change.
        if self.last_activity.is_none()
            && !engines.is_empty()
            && engines.iter().all(|engine| engine.from_cache)
        {
            self.last_activity = Some(activity);
            return out;
        }
        if self.last_activity.as_ref() != Some(&activity) {
            self.last_activity = Some(activity.clone());
            out.push(Emission::Activity(activity));
        }
        out
    }
}

/// The per-engine pass: link watch, baselines + the shared rule, the
/// reconnect catch-up, resolved, send tracking.
fn apply_engine(
    state: &mut EngineNotifyState,
    engine: &EngineInput,
    now: DateTime<Utc>,
    now_ms: i64,
    timings: &NotifyTimings,
    out: &mut Vec<Emission>,
) {
    let window_ms = timings.send_window.as_millis() as i64;

    // ── link watch ──────────────────────────────────────────────────────
    // "Working when it dropped": the effective indicators of this snapshot
    // (the rows are the last live ones while the link is down, gated by the
    // 45 s staleness rule, so a silent engine decays to not-Working).
    let working_now = engine
        .sessions
        .iter()
        .any(|s| SessionNotificationState::new(s, now).indicator == Indicator::Working);
    state.link.update(
        &engine.state,
        working_now,
        now_ms,
        timings,
        &engine.engine_id,
        &engine.engine_name,
        out,
    );

    // ── mode: the first snapshot of an engine (or of a fresh generation
    //    after the state was reset) seeds silently; a generation bump on
    //    an engine already seen connected is a reconnect (catch-up) ──────
    let generation_changed = state.generation.as_ref() != Some(&engine.generation);
    let was_connected = state.generation.is_some();
    state.generation = Some(engine.generation);
    let catch_up = generation_changed && was_connected;

    let mut chat_by_id = HashMap::with_capacity(engine.chats.len());
    for chat in &engine.chats {
        chat_by_id.insert(chat.id.as_str(), chat);
    }
    let mut seen: Vec<String> = Vec::with_capacity(engine.sessions.len());

    // ── rows: baseline first (always), then the rule ────────────────────
    for session in &engine.sessions {
        let raw = session.chat_id.as_str();
        seen.push(raw.to_owned());
        let baseline = SessionNotificationState::new(session, now);
        let chat = chat_by_id.get(raw);
        let top_level = chat.is_some_and(|c| c.parent_chat_id.is_none());

        // `send_pending`: the phone's own un-consumed send — the run this
        // device started has not ended and 30 s have not passed. The
        // decision reads the track BEFORE the lifecycle update below (a
        // run that ends on this pass still suppresses its own completion;
        // the track dies with it so a LATER run chimes again).
        // A send observed Working stays pending until its run ends (the
        // track lifecycle below drops it then); only a send never seen
        // Working is bounded by the window. Gating a seen-Working send on
        // the window too let any run longer than the window chime Done for
        // the phone's own send.
        let send_pending = state
            .sends
            .get(raw)
            .is_some_and(|t| t.seen_working || now_ms - t.sent_at_ms < window_ms);
        // Send tracking runs even while seeding, so a send that races the
        // first live frame is attributed correctly.
        let mut drop_track = false;
        if let Some(track) = state.sends.get_mut(raw) {
            if baseline.indicator == Indicator::Working {
                track.seen_working = true;
            } else if track.seen_working {
                // The run this device started has ended: the attribution
                // and the suppression are both over; drop the track.
                drop_track = true;
            } else if now_ms - track.sent_at_ms >= window_ms {
                // "…or 30 s pass": a send never observed Working expires.
                drop_track = true;
            }
        }
        if drop_track {
            state.sends.remove(raw);
        }

        // Save the new baseline BEFORE deciding (every time; suppressed
        // events are never replayed).
        let prev = state.baseline.insert(raw.to_owned(), baseline.clone());
        // First appearance: no stored baseline (boot, newly paired engine,
        // a row the last snapshot did not carry) — seed silently, never
        // replay on a later frame.
        let Some(prev) = prev else {
            continue;
        };
        // `chat` is `Option<&&Chat>` (the map holds `&Chat`): closures, not
        // fn paths, so auto-deref applies.
        let title = chat
            .map(|c| chat_title(c))
            .unwrap_or_else(|| "New session".into());
        let notify_kind = if top_level {
            baseline.sound_since(&prev, send_pending)
        } else {
            // Side chats keep their baseline current but never emit.
            None
        };
        let mut emitted = None;
        if let Some(kind) = notify_kind {
            emitted = Some(kind);
            out.push(Emission::Session(SessionEvent {
                engine_id: engine.engine_id.clone(),
                engine_name: engine.engine_name.clone(),
                chat_id: scope_id(&engine.engine_id, raw),
                chat_title: title,
                kind,
            }));
        } else if catch_up
            && top_level
            && !send_pending
            && matches!(
                prev.indicator,
                Indicator::Working | Indicator::AwaitingInput
            )
            && baseline.last_completed_turn != prev.last_completed_turn
            && chat.is_some_and(|c| c.unseen())
        {
            // Reconnect catch-up: the run finished while the phone could
            // not see it (socket dead in deep sleep). The 45 s freshness
            // gate suppresses the normal rule; the unseen completion still
            // deserves its Done, exactly once (the baseline above is
            // already the new one).
            emitted = Some(NotifyKind::Done);
            out.push(Emission::Session(SessionEvent {
                engine_id: engine.engine_id.clone(),
                engine_name: engine.engine_name.clone(),
                chat_id: scope_id(&engine.engine_id, raw),
                chat_title: title,
                kind: NotifyKind::Done,
            }));
        }
        if let Some(kind) = emitted {
            // A newer event for a chat replaces the older one (the platform
            // replaces the notification); the notified state tracks the
            // newest kind for its Resolved.
            state.notified.insert(raw.to_owned(), kind);
        }
    }

    // Baselines of chats whose session row vanished (deleted) do not
    // linger: a re-appearing row is a first appearance again.
    state.baseline.retain(|id, _| seen.iter().any(|s| s == id));
    // A send outlives a missing row for its window: a chat's FIRST run has
    // no session row until the engine starts it (createChat writes only
    // the chat), and the note_send wake runs a pass before that row
    // exists. Dropping the track there let the first run of a new chat
    // chime Done for the phone's own send.
    state
        .sends
        .retain(|id, t| seen.iter().any(|s| s == id) || now_ms - t.sent_at_ms < window_ms);

    // ── resolved: a notified state ends ─────────────────────────────────
    let finished: Vec<String> = state
        .notified
        .iter()
        .filter(|(raw, kind)| {
            let chat = chat_by_id.get(raw.as_str());
            match kind {
                NotifyKind::Done | NotifyKind::Attention => {
                    // Seen on any device — or the chat itself is gone.
                    chat.is_none_or(|c| !c.unseen())
                }
                NotifyKind::Request => {
                    chat.is_none()
                        || state
                            .baseline
                            .get(raw.as_str())
                            .is_none_or(|b| b.indicator != Indicator::AwaitingInput)
                }
            }
        })
        .map(|(raw, _)| raw.clone())
        .collect();
    for raw in finished {
        state.notified.remove(&raw);
        out.push(Emission::Resolved {
            engine_id: engine.engine_id.clone(),
            chat_id: scope_id(&engine.engine_id, &raw),
        });
    }
}

/// The engine-scoped chat id (ticket 07's `ScopedId::encode`): the same id
/// the Sessions list uses, and the deep link's id.
fn scope_id(engine_id: &str, raw: &str) -> String {
    ScopedId::encode(&EngineKey(engine_id.to_owned()), raw)
}

/// The chat's title with the "New session" fallback (the workspace derive's
/// rule, `view.rs`'s `build_row`).
fn chat_title(chat: &Chat) -> String {
    chat.title
        .as_deref()
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .unwrap_or("New session")
        .to_owned()
}

// ── the hub: one task, a listener slot, the send ledger ───────────────────

/// The driver's runtime half. Created with the client (empty, silent); the
/// FFI's `set_notification_sink` installs the listener and starts the task.
pub(crate) struct NotifyHub {
    timings: NotifyTimings,
    state: Mutex<DriverState>,
    listener: Mutex<Option<Arc<dyn NotifyListener>>>,
    wake: Arc<tokio::sync::Notify>,
    cancel: CancellationToken,
    started: std::sync::atomic::AtomicBool,
}

impl NotifyHub {
    pub(crate) fn new(timings: NotifyTimings) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(DriverState::new(timings.clone())),
            timings,
            listener: Mutex::new(None),
            wake: Arc::new(tokio::sync::Notify::new()),
            cancel: CancellationToken::new(),
            started: std::sync::atomic::AtomicBool::new(false),
        })
    }

    /// Install the delivery listener and start the drive task. Only the
    /// first call starts the task; later calls replace the listener (the
    /// task runs until `shutdown`).
    pub(crate) fn set_listener(self: &Arc<Self>, listener: Arc<dyn NotifyListener>, weak: Weak<ClientInner>) {
        {
            let mut slot = lock(&self.listener);
            *slot = Some(listener);
        }
        if self
            .started
            .swap(true, std::sync::atomic::Ordering::SeqCst)
        {
            return;
        }
        let hub = self.clone();
        crate::runtime::shared().spawn(async move {
            hub.drive(weak).await;
        });
    }

    /// This device issued a Run/Steer `QueueCommand` for a chat.
    pub(crate) fn note_send(&self, engine_id: &str, raw_chat_id: &str) {
        let now_ms = crate::engines::now_ms();
        lock(&self.state).note_send(engine_id, raw_chat_id, now_ms);
        self.wake.notify_one();
    }

    /// The engine was forgotten or re-paired: drop its state.
    pub(crate) fn reset_engine(&self, engine_id: &str) {
        lock(&self.state).reset_engine(engine_id);
    }

    pub(crate) fn shutdown(&self) {
        self.cancel.cancel();
    }

    async fn drive(self: Arc<Self>, weak: Weak<ClientInner>) {
        let Some(inner) = weak.upgrade() else { return };
        let mut fleet = inner.fleet.subscribe();
        // No strong ref across the loop (the hub lives inside ClientInner):
        // each pass upgrades the weak one.
        drop(inner);
        let mut tick = tokio::time::interval(self.timings.tick);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let wake = self.wake.clone();
        loop {
            tokio::select! {
                _ = self.cancel.cancelled() => return,
                changed = fleet.changed() => {
                    if changed.is_err() {
                        return; // The client is gone.
                    }
                }
                _ = wake.notified() => {}
                _ = tick.tick() => {}
            }
            let Some(inner) = weak.upgrade() else { return };
            self.recompute(&inner);
        }
    }

    /// One gather → decide → deliver pass. No lock is held while the
    /// listener runs.
    fn recompute(&self, inner: &Arc<ClientInner>) {
        // Gather under the engines lock (rows are cloned; the snapshot's
        // rows were already phone-clock rebased by the link's clock).
        let inputs: Vec<EngineInput> = {
            let state = lock(&inner.engines);
            state
                .entries
                .iter()
                .map(|entry| EngineInput {
                    engine_id: entry.paired.engine_id.clone(),
                    engine_name: crate::engine_view(entry).name,
                    state: entry.state.clone(),
                    generation: entry.generation,
                    from_cache: entry.from_cache,
                    sessions: entry.rows.sessions.clone(),
                    chats: entry.rows.chats.clone(),
                })
                .collect()
        };
        let now = Utc::now();
        let emissions = lock(&self.state).apply(&inputs, now);
        if emissions.is_empty() {
            return;
        }
        let listener = lock(&self.listener).clone();
        let Some(listener) = listener else { return };
        for emission in emissions {
            match emission {
                Emission::Session(event) => listener.session_event(event),
                Emission::Resolved { engine_id, chat_id } => {
                    listener.resolved(engine_id, chat_id)
                }
                Emission::LinkLost { engine_id, engine_name } => {
                    listener.link_lost(engine_id, engine_name)
                }
                Emission::LinkRestored { engine_id } => listener.link_restored(engine_id),
                Emission::Activity(activity) => listener.run_activity(activity),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::SessionStatus;
    use std::time::Duration;

    fn timings() -> NotifyTimings {
        NotifyTimings {
            link_grace: Duration::from_millis(400),
            startup_quiet: Duration::from_millis(500),
            send_window: Duration::from_millis(300),
            tick: Duration::from_millis(50),
        }
    }

    fn session(status: SessionStatus, updated: DateTime<Utc>, turn: Option<&str>) -> Session {
        Session {
            last_completed_turn: turn.map(str::to_owned),
            chat_id: "chat-1".into(),
            device_id: "engine".into(),
            status,
            started_at: Some(updated - chrono::TimeDelta::minutes(5)),
            updated_at: updated,
        }
    }

    fn chat(raw: &str, title: Option<&str>, parent: Option<&str>) -> Chat {
        Chat {
            id: raw.into(),
            device_id: "engine".into(),
            created_at: Utc::now(),
            title: title.map(str::to_owned),
            last_message_at: Some(Utc::now()),
            archived: false,
            cwd: None,
            branch: None,
            config: None,
            last_message_preview: None,
            last_seen_at: None,
            checkout_id: None,
            source_context: None,
            harness_session_id: None,
            harness_session_cwd: None,
            space_id: None,
            room_gen: None,
            parent_chat_id: parent.map(str::to_owned),
        }
    }

    fn engine(sessions: Vec<Session>, chats: Vec<Chat>) -> EngineInput {
        EngineInput {
            engine_id: "e1".into(),
            engine_name: "Studio Mac".into(),
            state: EngineState::Connected,
            generation: 1,
            from_cache: false,
            sessions,
            chats,
        }
    }

    fn now_plus(base: DateTime<Utc>, ms: i64) -> DateTime<Utc> {
        base + chrono::TimeDelta::milliseconds(ms)
    }

    /// The notification emissions only (the run-activity aggregate rides
    /// along on every pass; it has its own test).
    fn notify_only(out: &[Emission]) -> Vec<Emission> {
        out.iter()
            .filter(|e| !matches!(e, Emission::Activity(_)))
            .cloned()
            .collect()
    }

    #[test]
    fn first_appearance_seeds_silently_and_normal_completions_chime() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // Boot: the working row seeds silently.
        let out = notify_only(
            &driver.apply(
                &[engine(vec![session(SessionStatus::Working, base, None)], vec![chat("chat-1", None, None)])],
                base,
            ),
        );
        assert!(out.is_empty(), "boot must be silent: {out:?}");

        // The run finishes: Done.
        let done = now_plus(base, 2000);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, done, Some("turn-1"))],
                    vec![chat("chat-1", Some("Fix the login bug"), None)],
                )],
                done,
            ),
        );
        assert_eq!(
            out,
            vec![Emission::Session(SessionEvent {
                engine_id: "e1".into(),
                engine_name: "Studio Mac".into(),
                chat_id: scope_id("e1", "chat-1"),
                chat_title: "Fix the login bug".into(),
                kind: NotifyKind::Done,
            })]
        );

        // Marked seen on another device → Resolved cancels the notification.
        let mut seen_chat = chat("chat-1", Some("Fix the login bug"), None);
        seen_chat.last_seen_at = Some(done);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, done, Some("turn-1"))],
                    vec![seen_chat],
                )],
                now_plus(done, 100),
            ),
        );
        assert_eq!(
            out,
            vec![Emission::Resolved {
                engine_id: "e1".into(),
                chat_id: scope_id("e1", "chat-1"),
            }]
        );
    }

    #[test]
    fn blank_titles_fall_back_to_new_session_and_side_chats_never_emit() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        let rows = vec![
            session(SessionStatus::Working, base, None),
            {
                let mut s = session(SessionStatus::Working, base, None);
                s.chat_id = "chat-2".into();
                s
            },
        ];
        let chats = vec![
            chat("chat-1", Some("   "), None),
            chat("chat-2", Some("Side chat"), Some("chat-1")),
        ];
        driver.apply(&[engine(rows, chats.clone())], base);
        let done = now_plus(base, 2000);
        let rows = vec![
            session(SessionStatus::Idle, done, Some("t1")),
            {
                let mut s = session(SessionStatus::Idle, done, Some("t2"));
                s.chat_id = "chat-2".into();
                s
            },
        ];
        let out = notify_only(&driver.apply(&[engine(rows, chats)], done));
        // The blank title falls back to "New session"; the side chat
        // updated its baseline but never emits.
        assert_eq!(
            out,
            vec![Emission::Session(SessionEvent {
                engine_id: "e1".into(),
                engine_name: "Studio Mac".into(),
                chat_id: scope_id("e1", "chat-1"),
                chat_title: "New session".into(),
                kind: NotifyKind::Done,
            })]
        );
    }

    #[test]
    fn a_chats_own_pending_send_consumes_its_completion() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        driver.apply(
            &[engine(vec![session(SessionStatus::Idle, base, None)], vec![chat("chat-1", None, None)])],
            base,
        );
        // This phone sends; the run completes without the driver ever
        // observing Working (the sub-second run between two ticks).
        driver.note_send("e1", "chat-1", base.timestamp_millis());
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, now_plus(base, 150), Some("mine"))],
                    vec![chat("chat-1", None, None)],
                )],
                now_plus(base, 150),
            ),
        );
        assert!(
            out.is_empty(),
            "the phone's own send consumes its completion: {out:?}"
        );
        // The window expired: the NEXT completion chimes again.
        let later = now_plus(base, 1000);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, later, Some("again"))],
                    vec![chat("chat-1", None, None)],
                )],
                later,
            ),
        );
        assert!(matches!(
            out.first(),
            Some(Emission::Session(SessionEvent { kind: NotifyKind::Done, .. }))
        ));
    }

    #[test]
    fn a_send_before_the_chats_first_session_row_still_consumes_its_completion() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // A fresh chat: the chat row exists, no session row yet.
        driver.apply(&[engine(vec![], vec![chat("chat-1", None, None)])], base);
        driver.note_send("e1", "chat-1", base.timestamp_millis());
        // The note_send wake: a pass before the engine has started the run.
        driver.apply(&[engine(vec![], vec![chat("chat-1", None, None)])], now_plus(base, 10));
        // The run's row appears Working (first appearance, seeds silently)…
        let working = now_plus(base, 100);
        driver.apply(
            &[engine(vec![session(SessionStatus::Working, working, None)], vec![chat("chat-1", None, None)])],
            working,
        );
        // …and completes after the window: still this phone's own send.
        let done = now_plus(base, 1000);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, done, Some("mine"))],
                    vec![chat("chat-1", None, None)],
                )],
                done,
            ),
        );
        assert!(out.is_empty(), "the phone's own first send consumes its completion: {out:?}");
    }

    #[test]
    fn input_requests_resolve_when_the_indicator_leaves_awaiting() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        driver.apply(
            &[engine(vec![session(SessionStatus::Idle, base, None)], vec![chat("chat-1", None, None)])],
            base,
        );
        let asked = now_plus(base, 100);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::AwaitingInput, asked, Some("t1"))],
                    vec![chat("chat-1", None, None)],
                )],
                asked,
            ),
        );
        assert!(matches!(
            out.first(),
            Some(Emission::Session(SessionEvent { kind: NotifyKind::Request, .. }))
        ));
        // The user answers on the phone: the indicator leaves
        // AwaitingInput → Resolved.
        let answered = now_plus(base, 400);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Working, answered, Some("t1"))],
                    vec![chat("chat-1", None, None)],
                )],
                answered,
            ),
        );
        assert_eq!(
            out,
            vec![Emission::Resolved {
                engine_id: "e1".into(),
                chat_id: scope_id("e1", "chat-1"),
            }]
        );
    }

    #[test]
    fn reconnect_catch_up_recovers_a_completion_the_freshness_gate_dropped() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        let mut working = engine(
            vec![session(SessionStatus::Working, base, Some("old"))],
            vec![chat("chat-1", Some("Slow run"), None)],
        );
        working.generation = 1;
        driver.apply(&[working], base);
        // The socket dies; the run finishes unseen; the phone sleeps 60 s.
        let after = now_plus(base, 60_000);
        let reconnected = || {
            // The row last moved at `base`: 60 s old at `after`, past the
            // freshness gate.
            let mut e = engine(
                vec![session(SessionStatus::Idle, base, Some("turn-9"))],
                vec![chat("chat-1", Some("Slow run"), None)],
            );
            e.generation = 2;
            e
        };
        let out = notify_only(&driver.apply(&[reconnected()], after));
        // The normal rule returns none (the row is 60 s old — not fresh),
        // but the catch-up emits the Done exactly once. The chat stays
        // unseen, so nothing resolves yet.
        assert_eq!(
            out,
            vec![Emission::Session(SessionEvent {
                engine_id: "e1".into(),
                engine_name: "Studio Mac".into(),
                chat_id: scope_id("e1", "chat-1"),
                chat_title: "Slow run".into(),
                kind: NotifyKind::Done,
            })]
        );
        // The next pass (a heartbeat with the same rows) never replays it.
        let out = notify_only(&driver.apply(&[reconnected()], now_plus(after, 500)));
        assert!(out.is_empty(), "suppressed events are never replayed: {out:?}");
    }

    #[test]
    fn a_boot_from_cache_stays_silent_until_the_first_live_frame() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // The offline cache says a run completed just before the app died;
        // generation 0 = never connected this process.
        let mut cached = engine(
            vec![session(SessionStatus::Idle, base, Some("while-dead"))],
            vec![chat("chat-1", Some("Cached"), None)],
        );
        cached.generation = 0;
        cached.from_cache = true;
        let out = driver.apply(&[cached], base);
        assert!(out.is_empty(), "cached rows never emit: {out:?}");
        // The engine connects; the first live frame re-seeds silently
        // even though the completion marker moved while the app was dead.
        let live = now_plus(base, 2000);
        let out = driver.apply(
            &[engine(
                vec![session(SessionStatus::Idle, live, Some("while-dead-2"))],
                vec![chat("chat-1", Some("Cached"), None)],
            )],
            live,
        );
        assert!(
            out.is_empty(),
            "boot seeds silently (the activity gate stays quiet too: nothing was ever live): {out:?}"
        );
    }

    #[test]
    fn a_seen_chat_never_emits_the_catch_up_done() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        driver.apply(
            &[engine(
                vec![session(SessionStatus::Working, base, Some("old"))],
                vec![chat("chat-1", Some("Slow run"), None)],
            )],
            base,
        );
        // Marked seen on the desktop while the phone slept.
        let after = now_plus(base, 60_000);
        let mut seen_chat = chat("chat-1", Some("Slow run"), None);
        seen_chat.last_seen_at = Some(after);
        // A stale row (last moved at `base`): only the catch-up could emit,
        // and the chat is seen.
        let mut reconnected = engine(
            vec![session(SessionStatus::Idle, base, Some("turn-9"))],
            vec![seen_chat],
        );
        reconnected.generation = 2;
        let out = notify_only(&driver.apply(&[reconnected], after));
        assert!(out.is_empty(), "seen means no catch-up: {out:?}");
    }

    #[test]
    fn link_lost_fires_once_after_the_grace_while_a_run_was_working() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // Connected with a working chat: the first connect arms
        // STARTUP_QUIET.
        driver.apply(
            &[engine(
                vec![session(SessionStatus::Working, base, None)],
                vec![chat("chat-1", None, None)],
            )],
            base,
        );
        // The link drops; inside the grace nothing fires.
        let dropped = now_plus(base, 600);
        let mut out = driver.apply(
            &[{
                let mut e = engine(
                    vec![session(SessionStatus::Working, base, None)],
                    vec![chat("chat-1", None, None)],
                );
                e.state = EngineState::Reconnecting;
                e
            }],
            dropped,
        );
        assert!(
            !out.iter().any(|e| matches!(e, Emission::LinkLost { .. })),
            "inside the 400 ms grace: {out:?}"
        );
        // Past the grace and past STARTUP_QUIET: exactly one LinkLost.
        let lost = now_plus(base, 1200);
        out = driver.apply(
            &[{
                let mut e = engine(
                    vec![session(SessionStatus::Working, base, None)],
                    vec![chat("chat-1", None, None)],
                );
                e.state = EngineState::Reconnecting;
                e
            }],
            lost,
        );
        assert_eq!(
            out.iter().filter(|e| matches!(e, Emission::LinkLost { .. })).count(),
            1
        );
        // Staying non-connected emits nothing more.
        let still = now_plus(base, 2000);
        out = driver.apply(
            &[{
                let mut e = engine(
                    vec![session(SessionStatus::Working, base, None)],
                    vec![chat("chat-1", None, None)],
                );
                e.state = EngineState::Reconnecting;
                e
            }],
            still,
        );
        assert!(
            !out.iter().any(|e| matches!(e, Emission::LinkLost { .. })),
            "once per outage: {out:?}"
        );
        // Reconnect: LinkRestored cancels it.
        let back = now_plus(base, 3000);
        out = driver.apply(
            &[engine(
                vec![session(SessionStatus::Working, back, None)],
                vec![chat("chat-1", None, None)],
            )],
            back,
        );
        assert!(out.contains(&Emission::LinkRestored { engine_id: "e1".into() }));
    }

    #[test]
    fn a_boot_outage_and_a_drop_without_working_runs_never_alert() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // Never connected in this process (generation 0, reconnecting): a
        // boot outage stays silent.
        let out = notify_only(
            &driver.apply(
                &[{
                    let mut e = engine(Vec::new(), Vec::new());
                    e.generation = 0;
                    e.state = EngineState::Reconnecting;
                    e
                }],
                now_plus(base, 5000),
            ),
        );
        assert!(out.is_empty(), "a boot outage seeds silently: {out:?}");
        // Connected, then drops with nothing working: no LinkLost.
        let connected = now_plus(base, 6000);
        driver.apply(
            &[engine(
                vec![session(SessionStatus::Idle, connected, None)],
                vec![chat("chat-1", None, None)],
            )],
            connected,
        );
        let dropped = now_plus(base, 8000);
        let out = notify_only(
            &driver.apply(
                &[{
                    let mut e = engine(
                        vec![session(SessionStatus::Idle, connected, None)],
                        vec![chat("chat-1", None, None)],
                    );
                    e.state = EngineState::Reconnecting;
                    e
                }],
                dropped,
            ),
        );
        assert!(
            !out.iter().any(|e| matches!(e, Emission::LinkLost { .. })),
            "no run was working at the drop: {out:?}"
        );
    }

    #[test]
    fn run_activity_emits_on_change_and_attributes_this_devices_sends() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        // One engine's Working row with a FIXED run start (the engine sets
        // `started_at` once per run; only `updated_at` moves).
        let started = base - chrono::TimeDelta::minutes(5);
        let mut working_row = session(SessionStatus::Working, now_plus(base, 100), None);
        working_row.started_at = Some(started);
        // Boot: the first pass emits the (empty) activity once.
        let out = driver.apply(&[engine(Vec::new(), Vec::new())], base);
        assert_eq!(out, vec![Emission::Activity(RunActivity::default())]);
        // A desktop-started run appears.
        let t1 = now_plus(base, 100);
        let out = driver.apply(
            &[engine(vec![working_row.clone()], vec![chat("chat-1", Some("A"), None)])],
            t1,
        );
        assert_eq!(
            out.last(),
            Some(&Emission::Activity(RunActivity {
                working: vec![RunRef {
                    engine_id: "e1".into(),
                    engine_name: "Studio Mac".into(),
                    chat_id: scope_id("e1", "chat-1"),
                    chat_title: "A".into(),
                    started_at_ms: Some(started.timestamp_millis()),
                    from_this_device: false,
                }],
                awaiting: 0,
            }))
        );
        // This phone sends: the same run becomes from_this_device.
        driver.note_send("e1", "chat-1", t1.timestamp_millis());
        let t2 = now_plus(base, 200);
        let mut sent_row = working_row.clone();
        sent_row.updated_at = t2;
        let out = driver.apply(&[engine(vec![sent_row], vec![chat("chat-1", Some("A"), None)])], t2);
        assert!(matches!(
            out.as_slice(),
            [Emission::Activity(RunActivity { working, .. })] if working[0].from_this_device
        ));
        // Unchanged content (a heartbeat moved `updated_at` only): no
        // further emission — the chronometer ticks without reposts.
        let t3 = now_plus(base, 300);
        let mut heartbeat = working_row.clone();
        heartbeat.updated_at = t3;
        let out = driver.apply(&[engine(vec![heartbeat], vec![chat("chat-1", Some("A"), None)])], t3);
        assert!(out.is_empty(), "the activity only emits on change: {out:?}");
    }

    #[test]
    fn a_run_error_emits_attention_and_resolves_when_seen() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        driver.apply(
            &[engine(
                vec![session(SessionStatus::Working, base, None)],
                vec![chat("chat-1", Some("Failing"), None)],
            )],
            base,
        );
        let failed = now_plus(base, 300);
        let out = driver.apply(
            &[engine(
                vec![session(SessionStatus::Errored, failed, Some("boom"))],
                vec![chat("chat-1", Some("Failing"), None)],
            )],
            failed,
        );
        assert!(matches!(
            out.first(),
            Some(Emission::Session(SessionEvent { kind: NotifyKind::Attention, .. }))
        ));
        // An errored chat is unseen until read; seen → Resolved.
        let mut seen_chat = chat("chat-1", Some("Failing"), None);
        seen_chat.last_seen_at = Some(failed);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Errored, failed, Some("boom"))],
                    vec![seen_chat],
                )],
                now_plus(failed, 100),
            ),
        );
        assert_eq!(
            out,
            vec![Emission::Resolved {
                engine_id: "e1".into(),
                chat_id: scope_id("e1", "chat-1"),
            }]
        );
    }

    #[test]
    fn reset_engine_drops_baselines_for_a_forgotten_or_repaired_engine() {
        let base = Utc::now();
        let mut driver = DriverState::new(timings());
        driver.apply(
            &[engine(
                vec![session(SessionStatus::Working, base, None)],
                vec![chat("chat-1", None, None)],
            )],
            base,
        );
        driver.reset_engine("e1");
        // The engine re-pairs (a fresh generation 1, still the same rows):
        // everything re-seeds silently — no phantom completion.
        let done = now_plus(base, 2000);
        let out = notify_only(
            &driver.apply(
                &[engine(
                    vec![session(SessionStatus::Idle, done, Some("t1"))],
                    vec![chat("chat-1", None, None)],
                )],
                done,
            ),
        );
        assert!(out.is_empty(), "re-pair seeds silently: {out:?}");
    }
}
