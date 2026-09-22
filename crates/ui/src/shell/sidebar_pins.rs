//! Sidebar pin intents, projected over the engine-local pin store.
//!
//! Upstream zeron 68306a17 ("Sync sidebar pins with per-item ordering keys")
//! fed these intents to the engine's `changeSidebarPin` registry op so pins
//! synced cross-device. Roboco does NOT port that sync layer (751a210b,
//! ed04b43a, d4329257): the queue writes `UiSettings` directly — the
//! engine-local store IS the authority — so there is no RPC roundtrip to
//! confirm and no fractional registry ordering keys. What survives, re-homed
//! ui-local, is the per-item intent model itself: each accepted interaction
//! queues ONE pin/unpin/move intent anchored to neighbor session ids, the
//! pending queue orders and dedups rapid drops, and a stale move can never
//! resurrect an unpinned item.

use super::*;
use std::collections::VecDeque;

/// Admission limit for NEW pins (`MAX_SIDEBAR_PINS`). Simultaneous offline
/// additions can exceed it; existing pins stay visible, reorderable and
/// removable — cleanup never truncates overflow.
pub(super) const MAX_SIDEBAR_PINS: usize = 200;

/// One per-item pin intent. Anchors are neighbor session ids, resolved
/// against the current projection when the intent lands: a surviving right
/// anchor wins, otherwise the left anchor, or append when both vanished.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) enum SidebarPinChange {
    Pin {
        session_id: String,
        after: Option<String>,
        before: Option<String>,
    },
    Move {
        session_id: String,
        after: Option<String>,
        before: Option<String>,
    },
    Unpin { session_id: String },
}

impl SidebarPinChange {
    pub(super) fn session_id(&self) -> &str {
        match self {
            Self::Pin { session_id, .. }
            | Self::Move { session_id, .. }
            | Self::Unpin { session_id } => session_id,
        }
    }

    /// Rebase a pending intent on the latest confirmed projection. A stale
    /// move never resurrects an unpinned item. A surviving right anchor wins;
    /// otherwise use the left anchor, or append when both disappeared.
    pub(super) fn project(&self, ids: &mut Vec<String>) {
        let id = self.session_id();
        if matches!(self, Self::Move { .. }) && !ids.iter().any(|v| v == id) {
            return;
        }
        ids.retain(|v| v != id);
        let (after, before) = match self {
            Self::Unpin { .. } => return,
            Self::Pin { after, before, .. } | Self::Move { after, before, .. } => (after, before),
        };
        let index = before
            .as_ref()
            .and_then(|v| ids.iter().position(|i| i == v))
            .or_else(|| {
                after
                    .as_ref()
                    .and_then(|v| ids.iter().position(|i| i == v).map(|i| i + 1))
            })
            .unwrap_or(ids.len());
        ids.insert(index, id.to_owned());
    }
}

/// Validate an optimistic projection without truncating concurrent overflow.
pub(super) fn validate_sidebar_pin_update(
    current: &[String],
    next: &[String],
) -> Result<(), &'static str> {
    let mut seen = std::collections::HashSet::new();
    if next.iter().any(|id| id.is_empty() || !seen.insert(id)) {
        return Err("Sidebar pins must be non-empty and unique");
    }
    if next.len() > MAX_SIDEBAR_PINS && next.iter().any(|id| !current.contains(id)) {
        return Err("You can pin up to 200 sessions");
    }
    Ok(())
}

/// The current write burst's intent ledger. Upstream serialized these over
/// one engine attachment; locally the profile is the scope — a switch to
/// another profile discards the burst (`discard_stale_sidebar_pin_writes`).
/// The writes themselves land in `UiSettings` synchronously; the queue
/// exists to order rapid drops and to keep the optimistic-overlay seam
/// (`optimistic_sidebar_pins`) that the sidebar-sections port consumes.
pub(super) struct PendingSidebarPins {
    pub id: u64,
    pub profile_key: String,
    pub queue: VecDeque<SidebarPinChange>,
    pub unconfirmed: bool,
}

impl Shell {
    pub(super) fn set_pin_write_notice(&mut self, message: SharedString) {
        self.sidebar_pin_write_notice = Some(message.clone());
        self.sidebar_notice = Some(message);
    }

    pub(super) fn clear_pin_write_notice(&mut self) {
        if self.sidebar_notice == self.sidebar_pin_write_notice {
            self.sidebar_notice = None;
        }
        self.sidebar_pin_write_notice = None;
    }

    fn pin_write_is_current(&self, pending: &PendingSidebarPins, cx: &App) -> bool {
        self.active_sidebar_pin_profile_key(cx).as_ref() == Some(&pending.profile_key)
    }

    /// The overlay while a current write burst exists. Upstream projected
    /// the queued intents over the REMOTE registry state (they had not
    /// landed yet); locally every intent lands in `UiSettings` synchronously,
    /// so the committed bucket already IS the post-state — replaying the
    /// intents over it would double-apply them. The seam keeps upstream's
    /// shape (Some while a current, confirmed burst exists) for the
    /// sidebar-sections port.
    pub(super) fn optimistic_sidebar_pins(&self, cx: &App) -> Option<Vec<String>> {
        let pending = self
            .sidebar_pin_write
            .as_ref()
            .filter(|pending| !pending.unconfirmed && self.pin_write_is_current(pending, cx))?;
        Some(self.settings.sidebar_pins(&pending.profile_key).to_vec())
    }

    pub(super) fn discard_stale_sidebar_pin_writes(&mut self, _cx: &App) {
        if self
            .sidebar_pin_write
            .as_ref()
            .is_some_and(|pending| !self.pin_write_is_current(pending, _cx))
        {
            self.sidebar_pin_write = None;
        }
    }

    /// Record one intent in the current burst. The caller has already
    /// written the projected bucket into `UiSettings` (the local store lands
    /// every intent synchronously); the queue is the ordering ledger for the
    /// sections port's acknowledgements, not a transport. An unconfirmed
    /// burst rejects new intents exactly like upstream's stalled write.
    pub(super) fn queue_sidebar_pin_write(
        &mut self,
        profile_key: String,
        change: SidebarPinChange,
        cx: &mut Context<Self>,
    ) -> bool {
        self.discard_stale_sidebar_pin_writes(cx);
        if let Some(pending) = &mut self.sidebar_pin_write {
            if pending.unconfirmed {
                self.set_pin_write_notice(
                    "Waiting for the previous pin change to settle.".into(),
                );
                cx.notify();
                return false;
            }
            pending.queue.push_back(change);
            cx.notify();
            return true;
        }
        self.sidebar_pin_write_generation += 1;
        let id = self.sidebar_pin_write_generation;
        self.sidebar_pin_write = Some(PendingSidebarPins {
            id,
            profile_key,
            queue: VecDeque::from([change]),
            unconfirmed: false,
        });
        cx.notify();
        true
    }

    /// Acknowledge the burst's front intent. Locally the write cannot fail
    /// (it already landed); `Err` keeps upstream's notice semantics for the
    /// seams that call this with a real result. Popping the last intent
    /// removes the overlay, revealing the committed bucket.
    pub(super) fn finish_sidebar_pin_write(
        &mut self,
        id: u64,
        result: Result<(), String>,
        cx: &mut Context<Self>,
    ) -> Option<SidebarPinChange> {
        self.discard_stale_sidebar_pin_writes(cx);
        if self
            .sidebar_pin_write
            .as_ref()
            .is_none_or(|pending| pending.id != id)
        {
            return None;
        }
        let committed_pin = if result.is_ok() {
            self.sidebar_pin_write.as_ref().and_then(|pending| {
                let SidebarPinChange::Pin { session_id, .. } = pending.queue.front()? else { return None };
                // A later move back into a section must survive this older ack.
                (!pending.queue.iter().skip(1).any(|change| matches!(change, SidebarPinChange::Unpin { session_id: id } if id == session_id))).then(|| session_id.clone())
            })
        } else {
            None
        };
        match result {
            Ok(()) => self.clear_pin_write_notice(),
            Err(error) => {
                self.set_pin_write_notice(format!("Couldn't save pins: {error}").into());
            }
        }
        let pending = self.sidebar_pin_write.as_mut().unwrap();
        pending.queue.pop_front();
        let next = pending.queue.front().cloned();
        if next.is_none() {
            self.sidebar_pin_write = None;
        }
        cx.notify();
        if let Some(chat_id) = committed_pin {
            self.assign_sidebar_section(&chat_id, None, cx);
        }
        next
    }

    /// A stalled burst: cancel the queued intents and refuse new ones until
    /// the burst resolves (upstream: the engine request with an uncertain
    /// execution order; locally the seam stands ready for the sections port).
    pub(super) fn mark_pin_write_unconfirmed(&mut self, id: u64, cx: &mut Context<Self>) {
        self.discard_stale_sidebar_pin_writes(cx);
        if self
            .sidebar_pin_write
            .as_ref()
            .is_some_and(|pending| pending.id == id)
        {
            let pending = self.sidebar_pin_write.as_mut().unwrap();
            pending.queue.clear();
            pending.unconfirmed = true;
            self.set_pin_write_notice(
                "Couldn't confirm pins. Queued edits were cancelled; waiting before allowing more pin changes.".into(),
            );
            cx.notify();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pending_move_does_not_revive_unpinned_item() {
        let mut ids = vec!["remote".to_string()];
        SidebarPinChange::Move {
            session_id: "gone".into(),
            after: None,
            before: None,
        }
        .project(&mut ids);
        assert_eq!(ids, vec!["remote"]);
    }

    #[test]
    fn intents_rebase_onto_the_latest_projection() {
        let mut ids = vec!["a".to_string(), "b".to_string()];
        // Pin between the two; a surviving right anchor wins over the left.
        SidebarPinChange::Pin {
            session_id: "mid".into(),
            after: Some("a".into()),
            before: Some("b".into()),
        }
        .project(&mut ids);
        assert_eq!(ids, vec!["a", "mid", "b"]);
        // Both anchors gone: append.
        SidebarPinChange::Pin {
            session_id: "tail".into(),
            after: Some("gone".into()),
            before: Some("also-gone".into()),
        }
        .project(&mut ids);
        assert_eq!(ids, vec!["a", "mid", "b", "tail"]);
        // Unpin removes; a later move for the same id is a no-op (not a
        // revival).
        SidebarPinChange::Unpin { session_id: "mid".into() }.project(&mut ids);
        assert_eq!(ids, vec!["a", "b", "tail"]);
        SidebarPinChange::Move {
            session_id: "mid".into(),
            after: None,
            before: None,
        }
        .project(&mut ids);
        assert_eq!(ids, vec!["a", "b", "tail"]);
    }

    #[test]
    fn pin_admission_rejects_duplicates_and_overflow_without_truncating() {
        assert!(validate_sidebar_pin_update(&[], &["a".to_string()]).is_ok());
        // Duplicates and empty ids are rejected outright.
        assert!(validate_sidebar_pin_update(&[], &["a".to_string(), "a".to_string()]).is_err());
        assert!(validate_sidebar_pin_update(&[], &[String::new()]).is_err());
        let full: Vec<String> = (0..MAX_SIDEBAR_PINS).map(|i| i.to_string()).collect();
        // Existing overflow stays valid (no truncation, still reorderable)…
        assert!(validate_sidebar_pin_update(&full, &full).is_ok());
        // …but one NEW pin past the limit is rejected.
        let mut overflow = full.clone();
        overflow.push("new".to_string());
        assert!(validate_sidebar_pin_update(&full, &overflow).is_err());
    }
}
