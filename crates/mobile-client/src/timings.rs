//! Every timing the engine link supervisor honors, in one place. Tests
//! shrink them through [`Client::new_with_timings`] so liveness behaviour
//! runs in seconds; production uses [`Timings::default`].
//!
//! Sources: the desktop's `crates/ui/src/engine_registry.rs`, the web
//! client's `web/packages/app/src/lib/backoff.ts` and `registry.ts`,
//! upstream zeron PR #621's `host.rs`, and the mobile adaptations (ADAPT)
//! this ticket calls out.

use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Timings {
    /// Reconnect backoff: 500 ms initial, doubling, capped at 15 s, plus
    /// 0–255 ms jitter. The curve resets after a connection that lived
    /// longer than `backoff_reset_after`.
    pub initial_backoff: Duration,
    pub max_backoff: Duration,
    pub backoff_reset_after: Duration,
    /// `EngineInfo` must answer within this on connect and on probe
    /// (ADAPT: desktop remote uses 5 s, too tight on cellular).
    pub identity_timeout: Duration,
    /// Budget for every feed `subscribe_checked` ack.
    pub subscribe_timeout: Duration,
    /// Every feed stream must deliver its first item within this, else the
    /// link drops with "connected to the engine, but it sent no …".
    pub sync_deadline: Duration,
    /// Health probe cadence: `EngineInfo` on the feed.
    pub probe_every: Duration,
    /// Transcript lag check cadence and its reply budget; a transcript
    /// connection that misses the budget is closed.
    pub lag_every: Duration,
    pub lag_timeout: Duration,
    /// Rows-cache write debounce per engine.
    pub cache_debounce: Duration,
    /// `on_background` flush budget for pending cache writes.
    pub background_flush: Duration,
    /// Hard cap for `refresh()`.
    pub refresh_cap: Duration,
    /// The workspace store's re-derive tick: refreshes time labels and
    /// the 45 s staleness edge (ticket 07).
    pub workspace_tick: Duration,
}

impl Default for Timings {
    fn default() -> Self {
        Self {
            initial_backoff: Duration::from_millis(500),
            max_backoff: Duration::from_secs(15),
            backoff_reset_after: Duration::from_secs(10),
            identity_timeout: Duration::from_secs(10),
            subscribe_timeout: Duration::from_secs(15),
            sync_deadline: Duration::from_secs(20),
            probe_every: Duration::from_secs(5),
            lag_every: Duration::from_secs(10),
            lag_timeout: Duration::from_secs(15),
            cache_debounce: Duration::from_millis(300),
            background_flush: Duration::from_secs(2),
            refresh_cap: Duration::from_secs(3),
            workspace_tick: Duration::from_secs(1),
        }
    }
}

/// Reconnect backoff: 500 ms → ×2 → 15 s cap, 0–255 ms jitter; reset after
/// a connection that lived > 10 s (`crates/ui/src/engine_registry.rs`,
/// `ec:backoff.ts`).
#[derive(Debug, Clone)]
pub(crate) struct Backoff {
    delay: Duration,
    initial: Duration,
    max: Duration,
    reset_after: Duration,
}

impl Backoff {
    pub(crate) fn new(timings: &Timings) -> Self {
        Self {
            delay: timings.initial_backoff,
            initial: timings.initial_backoff,
            max: timings.max_backoff,
            reset_after: timings.backoff_reset_after,
        }
    }

    pub(crate) fn reset(&mut self) {
        self.delay = self.initial;
    }

    /// The next wait, with jitter; doubles afterwards.
    pub(crate) fn next_wait(&mut self) -> Duration {
        let jitter = Duration::from_millis(u64::from(uuid::Uuid::new_v4().as_bytes()[0]));
        let wait = self.delay + jitter;
        self.delay = (self.delay * 2).min(self.max);
        wait
    }

    /// A connection that outlived `reset_after` earns a fresh curve.
    pub(crate) fn maybe_reset(&mut self, lived: Duration) {
        if lived > self.reset_after {
            self.reset();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_doubles_resets_and_jitters() {
        let mut backoff = Backoff::new(&Timings::default());
        let first = backoff.next_wait();
        assert!(first >= Duration::from_millis(500));
        assert!(first <= Duration::from_millis(500 + 255));
        let second = backoff.next_wait();
        assert!(second >= Duration::from_millis(1000));
        assert!(second <= Duration::from_millis(1000 + 255));
        for _ in 0..8 {
            backoff.next_wait();
        }
        let capped = backoff.next_wait();
        assert!(capped <= Duration::from_millis(15_000 + 255));
        // A long-lived connection resets the curve.
        backoff.maybe_reset(Duration::from_secs(11));
        let after = backoff.next_wait();
        assert!(after >= Duration::from_millis(500));
        // A short-lived one does not.
        backoff.maybe_reset(Duration::from_secs(2));
        assert!(backoff.next_wait() >= Duration::from_millis(1000));
    }
}
