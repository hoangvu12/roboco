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

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `4d106b82` (#604) — `crates/engine/src/sessions.rs`
(+167 incl. tests). Our `SubagentSink::flush` (~line 1484) and `drive_run`
are the port targets.

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine` the commit-tick / sink flush tests (run the
engine lib tests as a suite to prove the thread-local fix).

- [ ] Empty folds clear the dirty flag; sink-only streams commit once per
      window
- [ ] Regression test proves no re-fire loop (thread-local counter)
- [ ] Engine lib tests green
- [ ] Port commit records upstream SHA

## Comments
