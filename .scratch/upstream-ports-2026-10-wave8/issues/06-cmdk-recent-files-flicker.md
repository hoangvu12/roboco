# 06 — Cmd+K: fix Recent Files flicker (upstream #890, `2a209cc6c`)

**What to build:** Port `2a209cc6c` (#890): the Cmd+K palette's Recent
Files section flickered as the recent-files list changed underneath the
rendered rows; the engine now serves a stable snapshot shape and the
workspace-files projection consumes it without mid-render churn.

**Why now:** engine-only, two files: `crates/engine/src/repos.rs` (+126)
and `crates/engine/src/workspace_files.rs` (+32). Roboco holds both
files and the Cmd+K palette.

**Steps:**
1. `git show 2a209cc6c`.
2. Port the repos.rs snapshot logic and the workspace_files.rs
   projection changes; adapt to any drift in roboco's repos/watch
   plumbing.

**Excluded upstream hunks:** none expected.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine` + the repos/
workspace_files tests. Source: `.scratch/upstream-drift/2026-10-22.md`
§ engine tickets.

- [ ] Stable recent-files snapshot + projection ported
- [ ] Commit records upstream SHA `2a209cc6c` (#890)

## Comments
