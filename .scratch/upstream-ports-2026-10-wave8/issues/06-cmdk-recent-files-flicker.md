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

**Status:** done (ported in 783027f5b on wave8/engine)

**Verification budget:** `cargo check -p roboco-engine` + the repos/
workspace_files tests. Source: `.scratch/upstream-drift/2026-10-22.md`
§ engine tickets.

- [x] Stable recent-files snapshot + projection ported
- [x] Commit records upstream SHA `2a209cc6c` (#890)

## Comments

Ported in 783027f5b. All three upstream hunks carried:

- `checkout_identity` single `rev-parse` + 3-line fallback: verbatim.
- `workspace_checkout`: roboco already had the TTL-cached
  `linked_worktrees()` (one `git worktree list` until
  `.git/worktrees` mtime changes or 10 s pass), so upstream's new
  `worktree_paths()` helper was folded into that cache rather than
  replacing a `refs()` call that roboco's version never made. The
  NUL-terminated `-z` parsing with the pre-2.36 newline fallback and
  the `linked_worktree_paths` `split('\0')` port carry upstream's
  newline-forgery protection; the newline-in-path test
  (`workspace_checkout_accepts_every_linked_worktree`, using roboco's
  `git_fixture` instead of upstream's env-var `git`) passes. Detached
  worktrees were already accepted by roboco's stanza parser; the new
  test pins that.
- `resolve_target` `tokio::join!` concurrency: verbatim (both match
  arms now yield `(root, identity)`).

Verified: `cargo check -p roboco-engine`; repos module 21/21,
`--test workspace_files` 8/8. No new wire types, so no wiregen check.
