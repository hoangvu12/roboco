# 04 — Diff-sync ignores file reads

**What to build:** `notify` reports opens and closes as access events, and
the sync's own git runs open files under both watched roots, so each
capture kicked the next one — idle checkouts re-ran `git status` and three
diffs every couple of seconds. Only events that can change the diff kick a
sync now (the workspace file watcher already filtered this way).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `bf64e71f` (#605) — `crates/engine/src/diff_sync.rs`
(+58 incl. test). Our `diff_sync.rs` exists (we also have
`tests/diff_sync_churn.rs` — extend it with the access-event regression).

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine diff_sync`.

- [ ] Access (open/close) events no longer re-kick captures
- [ ] Churn regression test covers it
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
