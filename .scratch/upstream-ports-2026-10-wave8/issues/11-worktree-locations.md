# 11 — Per-device custom worktree locations (upstream #530, `01a399db1`)

**What to build:** Port `01a399db1` (#530): custom worktree locations —
new `crates/engine/src/worktree_settings.rs` (+290) holding per-project
worktree path preferences, consumed by `repos.rs` (+486) when resolving
where a project's worktree lives, with rpc/host/test plumbing
(`source_control.rs` +83, `rpc.rs` +12, `doc_host.rs`, `workspace_host.rs`,
`lib.rs`).

**Why now:** engine-only, self-contained feature; roboco holds
`repos.rs`, `source_control.rs`, `doc_host.rs`, `workspace_host.rs` and
both test files (`device_routing.rs`, `worktree_on_run.rs`).

**Adaptation — single-device reality:** upstream's settings are
"per-device" over paired devices. Roboco is engine-local with one local
device: keep the settings storage and routing shape (the
`device_routing` surface exists), but drop any semantics that sync or
reconcile worktree preferences across devices — resolution is always
local. Record the adaptation in the port commit.

**Steps:**
1. `git show 01a399db1` — study `worktree_settings.rs` and the repos.rs
   resolution flow first.
2. Port `worktree_settings.rs` (roboco naming, engine-local store),
   then the repos.rs consumption, then hosts/rpc plumbing.
3. Port `device_routing.rs` (+35) and `worktree_on_run.rs` (+314) tests,
   dropping cross-device reconciliation cases that assume pairing.
4. If the port introduces new engine RPC surface types, run
   `cargo run -p wiregen -- --check` inside the ticket.

**Excluded upstream hunks:** `.github/workflows/windows.yml` (+5) —
roboco's windows.yml is its own; fold the test registration in if the
test names map, otherwise skip.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo test -p roboco-engine` (device_routing,
worktree_on_run). Source: `.scratch/upstream-drift/2026-10-22.md` §
engine tickets.

- [ ] `worktree_settings.rs` + repos resolution + tests ported
- [ ] Cross-device sync semantics adapted out
- [ ] Commit records upstream SHA `01a399db1` (#530)

## Comments
