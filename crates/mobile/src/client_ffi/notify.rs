//! The notification FFI (ticket 28): the [`NotificationSink`] callback
//! interface Kotlin implements, the event records, and
//! [`CoreClient::set_notification_sink`].
//!
//! `chat_id` in every record and callback is the **engine-scoped** id
//! (`engine:v1:…` — the same id the Sessions list uses): the deep link
//! `roboco://chat/<id>` always carries it, so two engines with colliding
//! raw ids cannot misroute a tap.
//!
//! Callbacks run on a client runtime thread. Kotlin must not block in
//! them: hand off to a coroutine.

use std::sync::Arc;

use roboco_mobile_client as mc;

// ── records ───────────────────────────────────────────────────────────────

/// One session notification kind (mobile naming; the shared rule calls
/// these Done / Request / Attention).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionEventKind {
    /// A run finished (`Run finished`).
    Done,
    /// The agent is waiting on your input (`Waiting on your input`).
    Input,
    /// A run failed (`Run failed`).
    Failed,
}

impl From<roboco_proto::view::NotifyKind> for SessionEventKind {
    fn from(kind: roboco_proto::view::NotifyKind) -> Self {
        match kind {
            roboco_proto::view::NotifyKind::Done => SessionEventKind::Done,
            roboco_proto::view::NotifyKind::Request => SessionEventKind::Input,
            roboco_proto::view::NotifyKind::Attention => SessionEventKind::Failed,
        }
    }
}

/// A session event to notify on (a run finished, needs input, or failed).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SessionEvent {
    pub engine_id: String,
    pub engine_name: String,
    /// The engine-scoped chat id (`engine:v1:…`), the deep-link id.
    pub chat_id: String,
    /// The chat's title, "New session" fallback already applied.
    pub chat_title: String,
    pub kind: SessionEventKind,
}

impl From<mc::SessionEvent> for SessionEvent {
    fn from(event: mc::SessionEvent) -> Self {
        Self {
            engine_id: event.engine_id,
            engine_name: event.engine_name,
            chat_id: event.chat_id,
            chat_title: event.chat_title,
            kind: event.kind.into(),
        }
    }
}

/// One working run in the aggregate activity.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RunRef {
    pub engine_id: String,
    pub engine_name: String,
    /// The engine-scoped chat id (`engine:v1:…`).
    pub chat_id: String,
    pub chat_title: String,
    /// `Session.started_at` (phone-stamped) in epoch ms, for the
    /// chronometer.
    pub started_at_ms: Option<i64>,
    /// This phone sent the run's `QueueCommand` (or created the chat with
    /// a first send) and the session has not left Working since.
    pub from_this_device: bool,
}

impl From<mc::RunRef> for RunRef {
    fn from(run: mc::RunRef) -> Self {
        Self {
            engine_id: run.engine_id,
            engine_name: run.engine_name,
            chat_id: run.chat_id,
            chat_title: run.chat_title,
            started_at_ms: run.started_at_ms,
            from_this_device: run.from_this_device,
        }
    }
}

/// The aggregate run activity across every engine, delivered after every
/// change: what the foreground service's ongoing notification renders.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct RunActivity {
    /// Working top-level chats, one `RunRef` each.
    pub working: Vec<RunRef>,
    /// Top-level chats in AwaitingInput.
    pub awaiting: u32,
}

impl From<mc::RunActivity> for RunActivity {
    fn from(activity: mc::RunActivity) -> Self {
        Self {
            working: activity.working.into_iter().map(Into::into).collect(),
            awaiting: activity.awaiting,
        }
    }
}

// ── the sink ──────────────────────────────────────────────────────────────

/// The platform's notification delivery surface. Every method is called on
/// a client runtime thread: hop to a coroutine, never block, return fast.
///
/// `session_event` replaces any earlier event for the same chat (one
/// notification per chat, newest wins); `resolved` cancels it (the chat
/// was seen on any device, or the question was answered); `link_lost` /
/// `link_restored` bracket one engine outage; `run_activity` arrives after
/// every aggregate change and drives the foreground service.
#[uniffi::export(with_foreign)]
pub trait NotificationSink: Send + Sync {
    fn session_event(&self, event: SessionEvent);
    fn resolved(&self, engine_id: String, chat_id: String);
    fn link_lost(&self, engine_id: String, engine_name: String);
    fn link_restored(&self, engine_id: String);
    fn run_activity(&self, activity: RunActivity);
}

/// Bridges the foreign sink onto the client crate's listener. No Rust lock
/// is held here: a foreign callback that re-enters Rust synchronously must
/// not deadlock.
pub(crate) struct SinkBridge(pub(crate) Arc<dyn NotificationSink>);

impl mc::NotifyListener for SinkBridge {
    fn session_event(&self, event: mc::SessionEvent) {
        self.0.session_event(event.into());
    }

    fn resolved(&self, engine_id: String, chat_id: String) {
        self.0.resolved(engine_id, chat_id);
    }

    fn link_lost(&self, engine_id: String, engine_name: String) {
        self.0.link_lost(engine_id, engine_name);
    }

    fn link_restored(&self, engine_id: String) {
        self.0.link_restored(engine_id);
    }

    fn run_activity(&self, activity: mc::RunActivity) {
        self.0.run_activity(activity.into());
    }
}
