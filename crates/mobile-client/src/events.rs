//! Change notifications. The platform registers one [`ClientListener`];
//! events are *coalesced* (at most one delivery burst per [`MIN_INTERVAL`])
//! and carry only revisions — the platform pulls the latest snapshot after
//! an event, so a skipped intermediate revision costs nothing.
//!
//! Delivery happens on a client runtime thread. Implementations must return
//! quickly (hop to the UI thread and pull there). No lock may be held while
//! `on_event` runs: a JNA callback into Kotlin that re-enters Rust under the
//! same lock deadlocks.
//!
//! Port of upstream zeronsh/zeron's `crates/client/src/events.rs`, minus
//! the auth events.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

use crate::{Connectivity, lock};

/// Minimum spacing between delivery bursts (one display frame).
pub const MIN_INTERVAL: Duration = Duration::from_millis(16);

#[derive(Debug, Clone, PartialEq)]
pub enum ClientEvent {
    /// The derived workspace advanced to `revision`.
    WorkspaceChanged { revision: u64 },
    /// A session's transcript advanced. Emitted by ticket 08.
    SessionChanged { chat_id: String, revision: u64 },
    /// A session's composer state advanced. Emitted by ticket 08.
    ComposerChanged { chat_id: String, revision: u64 },
    /// The aggregate connectivity posture changed.
    ConnectivityChanged { connectivity: Connectivity },
    /// Any engine view changed (state, name, notice, capabilities,
    /// diagnostics): pull `engines()`.
    EnginesChanged,
    /// A background write failed (a reverted overlay): show a transient
    /// notice. Never coalesced away — ordered delivery, one per failure.
    Notice { message: String },
    /// An engine's identity was pinned after the first successful connect.
    /// The platform must persist `device_id` (and `name`) — it is the key
    /// re-pairing matches on, and the offline display name.
    EngineIdentityPinned {
        engine_id: String,
        device_id: String,
        name: String,
    },
    /// A room heal pass (app start or network change) minted a session for
    /// a book member that had been missing (ADR 0011, ticket 04). The
    /// platform must persist the record exactly like a pairing result —
    /// otherwise every restart re-presents every member the heal picked
    /// up. Never coalesced away: each carries a live credential.
    RoomEngineJoined {
        room_id: String,
        engine: crate::PairedEngine,
    },
}

pub trait ClientListener: Send + Sync + 'static {
    fn on_event(&self, event: ClientEvent);
}

/// A listener that drops everything (tests, headless tools).
pub struct NullListener;

impl ClientListener for NullListener {
    fn on_event(&self, _event: ClientEvent) {}
}

#[derive(Default)]
struct Pending {
    ordered: Vec<ClientEvent>,
    workspace: Option<u64>,
    sessions: BTreeMap<String, u64>,
    composers: BTreeMap<String, u64>,
    engines: bool,
    pinned: Vec<ClientEvent>,
}

impl Pending {
    fn is_empty(&self) -> bool {
        self.ordered.is_empty()
            && self.workspace.is_none()
            && self.sessions.is_empty()
            && self.composers.is_empty()
            && !self.engines
            && self.pinned.is_empty()
    }

    fn drain(&mut self) -> Vec<ClientEvent> {
        let mut out = std::mem::take(&mut self.ordered);
        out.append(&mut std::mem::take(&mut self.pinned));
        if let Some(revision) = self.workspace.take() {
            out.push(ClientEvent::WorkspaceChanged { revision });
        }
        for (chat_id, revision) in std::mem::take(&mut self.sessions) {
            out.push(ClientEvent::SessionChanged { chat_id, revision });
        }
        for (chat_id, revision) in std::mem::take(&mut self.composers) {
            out.push(ClientEvent::ComposerChanged { chat_id, revision });
        }
        if self.engines {
            self.engines = false;
            out.push(ClientEvent::EnginesChanged);
        }
        out
    }
}

/// Coalescing delivery queue in front of the platform listener.
pub(crate) struct EventPump {
    listener: Arc<dyn ClientListener>,
    pending: Mutex<Pending>,
    notify: Notify,
}

impl EventPump {
    pub(crate) fn new(listener: Arc<dyn ClientListener>) -> Arc<Self> {
        Arc::new(Self {
            listener,
            pending: Mutex::new(Pending::default()),
            notify: Notify::new(),
        })
    }

    pub(crate) fn start(self: &Arc<Self>, cancel: CancellationToken) {
        let pump = self.clone();
        crate::runtime::shared().spawn(async move {
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = pump.notify.notified() => {}
                }
                let events = lock(&pump.pending).drain();
                for event in events {
                    // Deliberately no lock is held here: the listener may
                    // call back into the client synchronously.
                    pump.listener.on_event(event);
                }
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(MIN_INTERVAL) => {}
                }
                if !lock(&pump.pending).is_empty() {
                    pump.notify.notify_one();
                }
            }
        });
    }

    // The revision-pump seam tickets 07/08 drive; declared here so the
    // platform surface is stable from this ticket on.
    #[allow(dead_code)] // consumed by tickets 07/08
    pub(crate) fn workspace(&self, revision: u64) {
        {
            let mut pending = lock(&self.pending);
            pending.workspace = Some(pending.workspace.map_or(revision, |r| r.max(revision)));
        }
        self.notify.notify_one();
    }

    #[allow(dead_code)] // consumed by tickets 07/08
    pub(crate) fn session(&self, chat_id: &str, revision: u64) {
        {
            let mut pending = lock(&self.pending);
            let slot = pending.sessions.entry(chat_id.to_owned()).or_default();
            *slot = (*slot).max(revision);
        }
        self.notify.notify_one();
    }

    #[allow(dead_code)] // consumed by ticket 08
    pub(crate) fn composer(&self, chat_id: &str, revision: u64) {
        {
            let mut pending = lock(&self.pending);
            let slot = pending.composers.entry(chat_id.to_owned()).or_default();
            *slot = (*slot).max(revision);
        }
        self.notify.notify_one();
    }

    pub(crate) fn engines_changed(&self) {
        lock(&self.pending).engines = true;
        self.notify.notify_one();
    }

    /// A background-write failure notice: never coalesced (ordered,
    /// one per failure — the web's notice-strip semantics).
    pub(crate) fn notice(&self, message: String) {
        lock(&self.pending)
            .ordered
            .push(ClientEvent::Notice { message });
        self.notify.notify_one();
    }

    pub(crate) fn connectivity(&self, connectivity: Connectivity) {
        lock(&self.pending)
            .ordered
            .push(ClientEvent::ConnectivityChanged { connectivity });
        self.notify.notify_one();
    }

    /// Identity pins are never coalesced away — the platform must persist
    /// each one as it arrives.
    pub(crate) fn pinned(&self, event: ClientEvent) {
        lock(&self.pending).pinned.push(event);
        self.notify.notify_one();
    }

    #[allow(dead_code)] // consumed by tickets 07/08
    pub(crate) fn ordered(&self, event: ClientEvent) {
        lock(&self.pending).ordered.push(event);
        self.notify.notify_one();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[derive(Default)]
    struct Collector(Mutex<Vec<ClientEvent>>);

    impl ClientListener for Collector {
        fn on_event(&self, event: ClientEvent) {
            lock(&self.0).push(event);
        }
    }

    #[tokio::test]
    async fn bursts_are_coalesced_and_revisions_keep_the_latest() {
        let collector = Arc::new(Collector::default());
        let pump = EventPump::new(collector.clone());
        let cancel = CancellationToken::new();
        pump.start(cancel.clone());
        // Ten revisions land inside one 16 ms window.
        for revision in 1..=10u64 {
            pump.workspace(revision);
        }
        pump.session("chat-a", 4);
        pump.session("chat-a", 9);
        pump.session("chat-b", 2);
        pump.engines_changed();
        pump.engines_changed();
        let deadline = Instant::now() + Duration::from_millis(500);
        loop {
            if lock(&collector.0).len() >= 4 {
                break;
            }
            assert!(Instant::now() < deadline, "no burst was delivered");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        cancel.cancel();
        let events = lock(&collector.0).clone();
        assert_eq!(
            events,
            vec![
                // One WorkspaceChanged with the latest revision, one
                // SessionChanged per chat with the latest revision, one
                // EnginesChanged — the whole burst collapsed.
                ClientEvent::WorkspaceChanged { revision: 10 },
                ClientEvent::SessionChanged {
                    chat_id: "chat-a".into(),
                    revision: 9
                },
                ClientEvent::SessionChanged {
                    chat_id: "chat-b".into(),
                    revision: 2
                },
                ClientEvent::EnginesChanged,
            ]
        );
    }

    #[tokio::test]
    async fn identity_pins_survive_coalescing() {
        let collector = Arc::new(Collector::default());
        let pump = EventPump::new(collector.clone());
        let cancel = CancellationToken::new();
        pump.start(cancel.clone());
        pump.pinned(ClientEvent::EngineIdentityPinned {
            engine_id: "e1".into(),
            device_id: "d1".into(),
            name: "Mac".into(),
        });
        pump.pinned(ClientEvent::EngineIdentityPinned {
            engine_id: "e2".into(),
            device_id: "d2".into(),
            name: "PC".into(),
        });
        let deadline = Instant::now() + Duration::from_millis(500);
        loop {
            if lock(&collector.0).len() >= 2 {
                break;
            }
            assert!(Instant::now() < deadline, "pins were not delivered");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        cancel.cancel();
        assert_eq!(lock(&collector.0).len(), 2);
    }
}
