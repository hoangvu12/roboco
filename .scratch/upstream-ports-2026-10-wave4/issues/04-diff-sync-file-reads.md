# 04 — Diff-sync ignores file reads

**What to build:** `notify` reports opens and closes as access events, and
the sync's own git runs open files under both watched roots, so each
capture kicked the next one — idle checkouts re-ran `git status` and three
diffs every couple of seconds. Only events that can change the diff kick a
sync now (the workspace file watcher already filtered this way).

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `bf64e71f` (#605) — `crates/engine/src/diff_sync.rs`
(+58 incl. test). Our `diff_sync.rs` exists (we also have
`tests/diff_sync_churn.rs` — extend it with the access-event regression).

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine diff_sync`.

- [x] Access (open/close) events no longer re-kick captures
- [x] Churn regression test covers it
- [x] Tests green
- [x] Port commit records upstream SHA

## Comments

- Ported `bf64e71f` (#605) by intent into
  `crates/engine/src/diff_sync.rs`: `build_watchers`' callback gates on
  `event.as_ref().is_ok_and(is_checkout_change)`; `is_checkout_change`
  rejects `EventKind::Access(_)` (opens/closes/reads). Consistent with
  the workspace file watcher, which already filters Access
  (`workspace_files.rs`).
- Regression placement: upstream's tests live in `diff_sync.rs`'s
  `watch_budget_tests` (`watcher_stays_quiet_on_reads_and_kicks_on_writes`
  through a real watcher + the synthetic-kind table) because
  `build_watchers` is private — ported there verbatim. The churn file
  was still extended per the ticket with the e2e other-direction test
  (`reads_stay_quiet_and_writes_still_rekick`): a read burst leaves the
  published diff untouched, a real write still re-kicks → re-captures →
  republishes. (A read-kick on an unchanged tree publishes nothing —
  unchanged checksum returns before publish — so the watcher-level test
  is the only place the read half can fail pre-fix.)
- Exclusions: none — the upstream diff is engine-only.
- Verification: `cargo check -p roboco-engine --tests -j 3` clean; nextest
  watch_budget + churn filters 11/11; full `--test diff_sync_churn` 4/4.
