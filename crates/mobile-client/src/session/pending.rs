//! The pending-send echo: this device's un-adopted Run/Steer sends.
//!
//! A pending send is created per Run and Steer `QueueCommand` (before the
//! RPC completes, so the faded user row appears immediately). It
//! disappears when any applied transcript frame contains its `message_id`
//! (adoption — the engine's user entry reuses the client-minted id, so the
//! swap happens with no flicker and no duplicate row).
//!
//! States (the Roboco adaptation of upstream's connectivity states — there
//! is no client outbox, ADR 0004):
//!
//! | State | Meaning |
//! |---|---|
//! | `Sending` | The RPC is in flight, or it was acked and the engine is connected |
//! | `Queued` | Acked by the engine (it holds the command durably), but the engine connection is down right now |
//! | `Failed` | Not adopted within `UNDELIVERED_GRACE_MS` of the ack, nor by the heal reset after it |
//!
//! The flip to `Failed` is timer-driven: the session's watch task sleeps to
//! the earliest un-expired grace deadline and recomputes, so
//! `ComposerChanged` fires at that moment, not only on the next unrelated
//! event (android-ui-parity#73's "Not delivered · Tap to retry" banner).
//!
//! The heal: an acked send still missing at its grace deadline while the
//! engine is connected usually means the transcript subscription missed
//! the engine's user entry, not that the engine lost the command. So the
//! first expiry forces one plain-reset resubscribe instead of failing; the
//! send reads `Failed` only once that reset lands without it, or the heal
//! window runs out.

use roboco_doc::SessionCommandPayload;

// One `SendState` for the whole crate (the merge of tickets 07+08): the
// workspace rows read it per chat and the pending sends carry it, so the
// enum lives in `workspace::view` (07's home) and this module re-exports
// it — `session::SendState`, the crate root's `SendState` and the rows'
// `SendState` are the same type.
pub use crate::workspace::SendState;

/// Delivery-grace clock: an acked send not adopted within this of the ack
/// reads `Failed` (web `state/transcript-store.ts`'s
/// `UNDELIVERED_GRACE_MS`, upstream `connectivity.rs`).
pub const UNDELIVERED_GRACE_MS: i64 = 120_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PendingKind {
    Run,
    Steer,
}

/// One un-adopted send of THIS device.
#[derive(Debug, Clone, PartialEq)]
pub struct PendingSend {
    /// Client-minted id (the engine's user entry reuses it: adoption).
    pub message_id: String,
    /// Raw content exactly as the engine will write it (attachment trailer
    /// included — the row builders split it themselves). Starts as the
    /// `pending://`-ref trailer and is replaced in place with the real host
    /// paths once the upload commits.
    pub text: String,
    /// The user-visible part of `text` (trailer + Appshot context stripped).
    pub visible_text: String,
    /// Attachment refs/paths from the trailer.
    pub images: Vec<String>,
    pub kind: PendingKind,
    pub sent_at_ms: i64,
    pub state: SendState,
    /// Epoch ms of the engine's ack (0 while the RPC is in flight).
    pub acked_at_ms: i64,
    /// The original command, re-issued verbatim (same message id — the
    /// engine dedupes the user entry by it) by `retry_delivery` when the
    /// RPC never got an ack.
    pub(crate) command: SessionCommandPayload,
    /// Epoch ms the grace expiry forced the heal resubscribe (0: not yet).
    pub(crate) heal_requested_at_ms: i64,
    /// A full reset landed after the heal request without adopting this
    /// send: it is genuinely missing.
    pub(crate) heal_settled: bool,
}

impl PendingSend {
    pub(crate) fn new(
        message_id: String,
        text: String,
        kind: PendingKind,
        sent_at_ms: i64,
        command: SessionCommandPayload,
    ) -> Self {
        let parsed = crate::attachments::parse_user_message(&text);
        Self {
            message_id,
            visible_text: parsed.text,
            images: parsed.images.into_iter().map(|i| i.path).collect(),
            text,
            kind,
            sent_at_ms,
            state: SendState::Sending,
            acked_at_ms: 0,
            command,
            heal_requested_at_ms: 0,
            heal_settled: false,
        }
    }

    /// Restart the delivery clock (retry): a fresh grace from `now_ms` and
    /// a fresh heal.
    pub(crate) fn restart_clock(&mut self, now_ms: i64) {
        self.acked_at_ms = now_ms;
        self.heal_requested_at_ms = 0;
        self.heal_settled = false;
    }
}

/// Derive one pending send's state. Pure — the paused-clock test covers the
/// flip boundaries.
pub(crate) fn send_state(
    now_ms: i64,
    acked_at_ms: i64,
    engine_connected: bool,
    grace_ms: i64,
) -> SendState {
    if acked_at_ms == 0 {
        // The RPC is in flight.
        return SendState::Sending;
    }
    if now_ms >= acked_at_ms + grace_ms {
        return SendState::Failed;
    }
    if engine_connected {
        SendState::Sending
    } else {
        SendState::Queued
    }
}

/// One pending send's state with the heal applied, and whether this
/// refresh must request the heal resubscribe. Pure.
///
/// - Inside the grace window: [`send_state`].
/// - First grace expiry while connected: still `Sending`, and request the
///   heal (a plain-reset resubscribe that adopts the send if the engine
///   wrote it).
/// - Expiry while disconnected: `Failed` (no heal can run; the reconnect's
///   own resubscribe still adopts a delivered send).
/// - Heal requested: `Sending`/`Queued` until the heal reset lands without
///   the send or `heal_ms` passes, then `Failed`.
pub(crate) fn delivery_state(
    now_ms: i64,
    pending: &PendingSend,
    engine_connected: bool,
    grace_ms: i64,
    heal_ms: i64,
) -> (SendState, bool) {
    let base = send_state(now_ms, pending.acked_at_ms, engine_connected, grace_ms);
    if base != SendState::Failed || pending.heal_settled {
        return (base, false);
    }
    if pending.heal_requested_at_ms == 0 {
        return if engine_connected {
            (SendState::Sending, true)
        } else {
            (SendState::Failed, false)
        };
    }
    if now_ms >= pending.heal_requested_at_ms + heal_ms {
        return (SendState::Failed, false);
    }
    let waiting = if engine_connected {
        SendState::Sending
    } else {
        SendState::Queued
    };
    (waiting, false)
}

/// Headline state of a pending list: `Failed` if any send failed, else the
/// oldest send's state (upstream `oldest_state`).
pub(crate) fn headline_state(pending: &[PendingSend]) -> Option<SendState> {
    pending.iter().min_by_key(|p| p.sent_at_ms).map(|oldest| {
        if pending.iter().any(|p| p.state == SendState::Failed) {
            SendState::Failed
        } else {
            oldest.state
        }
    })
}

/// The earliest grace (or heal) deadline over the acked, not yet failed
/// pending sends, in epoch ms (`None` while nothing is acked — an in-flight
/// send never arms the timer). The watch task sleeps to this and
/// refreshes, so the `Failed` flip emits `ComposerChanged` at the moment it
/// happens, not on the next unrelated event. A `Failed` send has no
/// deadline left: its past one would re-wake the task forever. Pure: the
/// paused-clock arithmetic, unit-tested without a runtime; the caller
/// clamps a past deadline to an immediate wake (the state flips on that
/// refresh).
pub(crate) fn next_grace_deadline(
    pending: &[PendingSend],
    grace_ms: i64,
    heal_ms: i64,
) -> Option<i64> {
    pending
        .iter()
        .filter(|p| p.acked_at_ms != 0 && p.state != SendState::Failed)
        .map(|p| {
            if p.heal_requested_at_ms != 0 {
                p.heal_requested_at_ms + heal_ms
            } else {
                p.acked_at_ms + grace_ms
            }
        })
        .min()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sending_while_in_flight_and_while_acked_and_connected() {
        assert_eq!(send_state(0, 0, false, UNDELIVERED_GRACE_MS), SendState::Sending);
        assert_eq!(send_state(5_000, 1_000, true, UNDELIVERED_GRACE_MS), SendState::Sending);
    }

    #[test]
    fn queued_when_acked_but_the_engine_connection_is_down() {
        assert_eq!(send_state(5_000, 1_000, false, UNDELIVERED_GRACE_MS), SendState::Queued);
    }

    #[test]
    fn failed_exactly_at_the_grace_boundary_even_while_connected() {
        assert_eq!(
            send_state(1_000 + UNDELIVERED_GRACE_MS, 1_000, true, UNDELIVERED_GRACE_MS),
            SendState::Failed
        );
        // A shrunken grace (tests drive the flip in milliseconds).
        assert_eq!(send_state(1_500, 1_000, false, 500), SendState::Failed);
        assert_eq!(send_state(1_499, 1_000, false, 500), SendState::Queued);
    }

    fn send(id: &str, sent_at: i64, acked_at: i64, state: SendState) -> PendingSend {
        PendingSend {
            message_id: id.into(),
            text: "hi".into(),
            visible_text: "hi".into(),
            images: Vec::new(),
            kind: PendingKind::Run,
            sent_at_ms: sent_at,
            state,
            acked_at_ms: acked_at,
            command: SessionCommandPayload::Interrupt {},
            heal_requested_at_ms: 0,
            heal_settled: false,
        }
    }

    #[test]
    fn headline_is_failed_when_any_send_failed_otherwise_the_oldest() {
        let mk = |id: &str, at: i64, state: SendState| send(id, at, 0, state);
        let pending = vec![mk("a", 2, SendState::Sending), mk("b", 1, SendState::Queued)];
        assert_eq!(headline_state(&pending), Some(SendState::Queued));
        let pending = vec![mk("a", 2, SendState::Failed), mk("b", 1, SendState::Queued)];
        assert_eq!(headline_state(&pending), Some(SendState::Failed));
        assert_eq!(headline_state(&[]), None);
    }

    /// The grace timer arms on the earliest ACKED send only — an in-flight
    /// send has no deadline to sleep to (its RPC is bounded by its own
    /// call deadline), and a shrunken grace arms proportionally.
    #[test]
    fn the_grace_deadline_is_the_earliest_acked_send() {
        let mk = |id: &str, at: i64, acked_at: i64| send(id, at, acked_at, SendState::Sending);
        let pending = vec![
            mk("a", 1_000, 1_050),
            mk("b", 2_000, 2_100),
            mk("in-flight", 3_000, 0),
        ];
        assert_eq!(next_grace_deadline(&pending, 500, 100), Some(1_550));
        assert_eq!(
            next_grace_deadline(&pending, UNDELIVERED_GRACE_MS, 100),
            Some(121_050)
        );
        // Only in-flight sends: no deadline at all (the timer stays
        // disarmed, `pending()` in the select).
        assert_eq!(
            next_grace_deadline(&[mk("in-flight", 3_000, 0)], 500, 100),
            None
        );
        assert_eq!(next_grace_deadline(&[], 500, 100), None);
        // A past deadline survives (the caller clamps to an immediate wake
        // so the flip still emits).
        assert_eq!(next_grace_deadline(&[mk("a", 1, 100)], 100, 100), Some(200));
    }

    /// A failed send arms no timer: its deadline is in the past, so
    /// counting it re-woke the watch task immediately, forever.
    #[test]
    fn a_failed_send_has_no_deadline() {
        let failed = send("a", 1, 100, SendState::Failed);
        assert_eq!(next_grace_deadline(std::slice::from_ref(&failed), 100, 100), None);
        let live = send("b", 2, 5_000, SendState::Sending);
        assert_eq!(
            next_grace_deadline(&[failed, live], 100, 100),
            Some(5_100),
            "only the live send's deadline arms"
        );
    }

    /// Once the heal was requested, the timer sleeps to the heal deadline
    /// instead of the (already past) grace deadline.
    #[test]
    fn a_healing_send_arms_the_heal_deadline() {
        let mut healing = send("a", 1, 100, SendState::Sending);
        healing.heal_requested_at_ms = 300;
        assert_eq!(next_grace_deadline(&[healing], 100, 1_000), Some(1_300));
    }

    #[test]
    fn grace_expiry_while_connected_requests_one_heal_instead_of_failing() {
        let grace = 100;
        let heal = 1_000;
        let mut pending = send("a", 1, 100, SendState::Sending);
        // Inside the grace window: the plain state, no heal.
        assert_eq!(
            delivery_state(150, &pending, true, grace, heal),
            (SendState::Sending, false)
        );
        // At expiry, connected: keep Sending and request the heal.
        assert_eq!(
            delivery_state(200, &pending, true, grace, heal),
            (SendState::Sending, true)
        );
        pending.heal_requested_at_ms = 200;
        // The heal is in flight: no second request, still Sending (Queued
        // when the link dropped meanwhile).
        assert_eq!(
            delivery_state(500, &pending, true, grace, heal),
            (SendState::Sending, false)
        );
        assert_eq!(
            delivery_state(500, &pending, false, grace, heal),
            (SendState::Queued, false)
        );
        // The heal window ran out: Failed.
        assert_eq!(
            delivery_state(1_200, &pending, true, grace, heal),
            (SendState::Failed, false)
        );
        // The heal reset landed without the send: Failed at once.
        pending.heal_settled = true;
        assert_eq!(
            delivery_state(500, &pending, true, grace, heal),
            (SendState::Failed, false)
        );
    }

    #[test]
    fn grace_expiry_while_disconnected_fails_without_a_heal() {
        let pending = send("a", 1, 100, SendState::Queued);
        assert_eq!(
            delivery_state(200, &pending, false, 100, 1_000),
            (SendState::Failed, false)
        );
    }

    #[test]
    fn a_retry_restarts_the_clock_and_the_heal() {
        let mut pending = send("a", 1, 100, SendState::Failed);
        pending.heal_requested_at_ms = 200;
        pending.heal_settled = true;
        pending.restart_clock(5_000);
        assert_eq!(
            delivery_state(5_050, &pending, true, 100, 1_000),
            (SendState::Sending, false)
        );
        assert_eq!(
            delivery_state(5_100, &pending, true, 100, 1_000),
            (SendState::Sending, true),
            "the retried send gets its own heal"
        );
    }
}
