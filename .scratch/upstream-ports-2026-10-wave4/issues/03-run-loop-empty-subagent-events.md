# 03 — Stop the run loop spinning on empty subagent events

**What to build:** A tagged subagent event that folds to no parts still
marked its sink dirty, and `SubagentSink::flush` returned on the empty fold
without clearing the flag — the coalesced-commit guard then stayed true
with its deadline in the past, so `drive_run` re-fired that branch on every
pass and burned a core per active chat until real content arrived. Clear
`dirty` whenever a flush runs (empty fold included), arm the commit window
when a sink turns dirty on its own so sink-only streams still commit once
per window, and keep the flush-tick test counter thread-local (a
process-global counter picks up ticks from other lib tests running in
parallel).

**Blocked by:** 02 — both rewrite `crates/engine/src/sessions.rs` (reaper
first, run-loop second; upstream order too).

**Status:** ready-for-human

**Upstream SHAs:** `4d106b82` (#604) — `crates/engine/src/sessions.rs`
(+167 incl. tests). Our `SubagentSink::flush` (~line 1484) and `drive_run`
are the port targets.

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine` the commit-tick / sink flush tests (run the
engine lib tests as a suite to prove the thread-local fix).

- [x] Empty folds clear the dirty flag; sink-only streams commit once per
      window
- [x] Regression test proves no re-fire loop (thread-local counter)
- [x] Engine lib tests green
- [x] Port commit records upstream SHA

## Comments

- Ported `4d106b82` (#604) by intent into `crates/engine/src/sessions.rs`:
  `SubagentSink::flush` clears `dirty` up front (empty fold included);
  the fold site arms the commit window when a sink turns dirty on its own
  (`was_clean && !dirty && flush_at <= now` → rearm at
  now + STREAM_COMMIT_MS); the commit branch gains the `#[cfg(test)]`
  `FLUSH_TICKS` thread-local counter.
- Tests ported into `mod tests` (rebrand: `roboco_*` crate paths,
  `DocHostConfig` without upstream's `edge` field — engine-local ADR 0004,
  `handle.doc_arc()` where upstream writes `handle.writer()`, 3-arg
  `EngineCore::assemble`): the direct flush unit test and the
  feed-by-hand spin regression. `mod tests` imports switched to
  `use super::*` like upstream.
- Exclusions: none — the upstream diff is engine-only.
- Verification: `cargo check -p roboco-engine --tests -j 3` clean (17s
  warm); nextest `-p roboco-engine --lib` → 321/321 pass with the new
  tests running in the same binary as all other lib tests (proves the
  thread-local counter picks up only its own run's ticks); targeted
  rerun of the 2 new tests green.
