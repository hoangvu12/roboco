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

**Status:** done (ported in c40b42f32 on wave8/engine)

**Verification budget:** `cargo test -p roboco-engine` (device_routing,
worktree_on_run). Source: `.scratch/upstream-drift/2026-10-22.md` §
engine tickets.

- [x] `worktree_settings.rs` + repos resolution + tests ported
- [x] Cross-device sync semantics adapted out
- [x] Commit records upstream SHA `01a399db1` (#530)

## Comments

Ported in c40b42f32. Carried: `worktree_settings.rs` (WorktreePreferences,
atomic persistence, write-probe validation, env override), the full
repos.rs resolution flow (destination snapshot per creation, nesting
refusal, Windows UTF-16 launch limits, two-step `worktree add` with
`core.longpaths`), proto `device_paths` + `WorktreeSettings`/
`WorktreeSettingsStatus` (TS + wiregen manifest, generated TS
refreshed), rpc constants + dispatch, doc_host/workspace_host
same-file identity compares, and the device_routing/worktree_on_run/
repos/source_control tests.

**Single-device adaptation:** upstream's forwardable-relay entry and
device-B routing test are gone by design (no relay in roboco); the
fail-closed targetDeviceId guard already covers the new methods, and
`worktree_settings_live_on_this_engine` pins the local round trip.
No preference reconciliation/sync semantics exist to port.

**Excluded:** all `crates/ui` hunks (settings worktrees panel,
popover, widgets) — UI lane, nothing in roboco consumes the RPCs yet.
`.github/workflows/windows.yml` step folded in with nextest + roboco
crate names (test names map: `device_paths`, `worktree_location_*`).

**Enabling fix:** four pre-existing i32-overflowing literals in
roboco-proto tests (agent.rs:868, entities.rs:2040/2078/2094) broke
the proto lib-test target on edition 2024 — verified failing on the
base commit too; given `i64` suffixes so the ported CI step compiles.

Verified: proto device_paths 4/4; engine lib worktree* 15/15;
source_control fake-gh 1/1; `--test device_routing` 3/3;
`--test worktree_on_run` 2/2 (incl. symlink variant);
m5 rpc_dispatch_for_m5_methods (env-var override path) 1/1;
`cargo check -p roboco-engine/-p roboco-mobile-client` clean;
`cargo run -p wiregen -- --check` green. Windows-only tests compile
on the Windows CI path (cfg'd out locally).
