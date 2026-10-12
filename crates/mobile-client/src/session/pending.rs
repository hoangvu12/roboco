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
//! | `Failed` | Not adopted within `UNDELIVERED_GRACE_MS` of the ack |
//!
//! The flip to `Failed` is timer-driven: the session's watch task sleeps to
//! the earliest un-expired grace deadline and recomputes, so
//! `ComposerChanged` fires at that moment, not only on the next unrelated
//! event (android-ui-parity#73's "Not delivered · Tap to retry" banner).

use roboco_doc::SessionCommandPayload;

/// Delivery-grace clock: an acked send not adopted within this of the ack
/// reads `Failed` (web `state/transcript-store.ts`'s
/// `UNDELIVERED_GRACE_MS`, upstream `connectivity.rs`).
pub const UNDELIVERED_GRACE_MS: i64 = 120_000;

/// What the composer and the session row show for an un-adopted send.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SendState {
    /// In flight, or acked on a connected engine.
    #[default]
    Sending,
    /// Acked and durably held by the engine, whose connection is down.
    Queued,
    /// Not adopted within the grace window.
    Failed,
}

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
        }
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

/// The earliest grace deadline over the acked pending sends, in epoch ms
/// (`None` while nothing is acked — an in-flight send never arms the
/// timer). The watch task sleeps to this and refreshes, so the `Failed`
/// flip emits `ComposerChanged` at the moment it happens, not on the next
/// unrelated event. Pure: the paused-clock arithmetic, unit-tested
/// without a runtime; the caller clamps a past deadline to an immediate
/// wake (the state flips on that refresh).
pub(crate) fn next_grace_deadline(pending: &[PendingSend], grace_ms: i64) -> Option<i64> {
    pending
        .iter()
        .filter(|p| p.acked_at_ms != 0)
        .map(|p| p.acked_at_ms + grace_ms)
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

    #[test]
    fn headline_is_failed_when_any_send_failed_otherwise_the_oldest() {
        let mk = |id: &str, at: i64, state: SendState| PendingSend {
            message_id: id.into(),
            text: "hi".into(),
            visible_text: "hi".into(),
            images: Vec::new(),
            kind: PendingKind::Run,
            sent_at_ms: at,
            state,
            acked_at_ms: 0,
            command: SessionCommandPayload::Interrupt {},
        };
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
        let mk = |id: &str, at: i64, acked_at: i64| PendingSend {
            message_id: id.into(),
            text: "hi".into(),
            visible_text: "hi".into(),
            images: Vec::new(),
            kind: PendingKind::Run,
            sent_at_ms: at,
            state: SendState::Sending,
            acked_at_ms: acked_at,
            command: SessionCommandPayload::Interrupt {},
        };
        let pending = vec![
            mk("a", 1_000, 1_050),
            mk("b", 2_000, 2_100),
            mk("in-flight", 3_000, 0),
        ];
        assert_eq!(next_grace_deadline(&pending, 500), Some(1_550));
        assert_eq!(next_grace_deadline(&pending, UNDELIVERED_GRACE_MS), Some(121_050));
        // Only in-flight sends: no deadline at all (the timer stays
        // disarmed, `pending()` in the select).
        assert_eq!(
            next_grace_deadline(&[mk("in-flight", 3_000, 0)], 500),
            None
        );
        assert_eq!(next_grace_deadline(&[], 500), None);
        // A past deadline survives (the caller clamps to an immediate wake
        // so the flip still emits).
        assert_eq!(next_grace_deadline(&[mk("a", 1, 100)], 100), Some(200));
    }
}
