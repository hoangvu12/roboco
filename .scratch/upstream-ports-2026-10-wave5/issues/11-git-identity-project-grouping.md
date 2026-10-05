# 11 — Group projects by git identity (+ rolling labels, no icons)

**What to build:** Port the merged `dbb639be` (#799) subset, per the
wave spec's scope decisions. Each git project gains a repository id
stamped by its owning engine: the normalized origin remote
(host/owner/repo), else a device-scoped hash of the common git dir so
remote-less worktrees still match — clones and worktrees of one
repository share the id across devices. Then:

1. **Sidebar project mode** folds projects with the same repository id
   into one group, named by the oldest checkout; the project icon
   moves to the group header and its rows drop theirs. Every checkout
   of a repository shares one project icon and color — using the
   EXISTING Solar icon set and coloring (no new icons: skipped by
   user decision 2026-10-05).
2. **New-session composer picks the project first** (one row per
   repository), then the device among its checkouts; project-less
   sessions still pick any device — this reshapes
   `pickers.rs` (+881 upstream) and `pickers/compact.rs`.
3. **Rolling labels:** overflow labels roll rather than truncate — the
   new `roll_text.rs` (504 lines upstream) in its SIMPLE form only.
   The blur/tilt glyph treatment from the unmerged upstream branch is
   explicitly NOT ported (future drift window).

**Engine/proto/doc substrate:** `parse_git_remote` already exists in
`crates/engine/src/source_control.rs:324-390` (feeds PR heads only) —
repository-id stamping is new plumbing in `crates/engine/src/repos.rs`
(+156 upstream) + engine tests (new
`m5_repos_diffs_terminals.rs` sections, +149), `spaces.rs`,
`workspace_host.rs`; proto `entities.rs` + view grouping (today
grouping keys on cwd basename, `crates/proto/src/view.rs:204-215` —
`ChatGroup`/`project_label`/`group_chats`); doc registry/workspace
entries. Per-space remote URLs are not currently surfaced into
sidebar data — new engine→proto plumbing, engine-local (local remote
reads, no cloud).

**Exclusions (wave spec, binding):** `apps/ios/*`,
`crates/client/*` (incl. demo-workspace clone fixtures and
`client/tests/*`), `crates/mobile/*`, `crates/mcp/src/zeron.rs` hunk,
Zeron Icons (`icons.rs`, `assets/icons/*`, web generated icon set,
ARCHITECTURE.md icons prose), and the PR's own Cargo.toml/Cargo.lock
pin hunks (ticket 01 owns the pins).

**Web parity:** grouping + rolling labels in the web sidebar; no icon
changes.

**Blocked by:** 01 (rolling labels ride the `0966d06` glyph work the
pin sync brings).

**Status:** ready-for-agent

**Upstream SHAs:** `dbb639be` (#799, squash-merge) — 58 files,
+2702/−701 total; the subset above. The unmerged
`zeron/git-identity-project-grouping` branch (tip `c3af3110`, 10+
review commits) is OUT of scope — future drift windows re-triage.
Source: `.scratch/upstream-drift/2026-10-05.md` § #799 (full blocker
analysis + commit message excerpts).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-engine` (repository-id tests), `-p
roboco-ui --lib` (picker/grouping/roll_text tests); `wiregen --check`;
`pnpm -r build` + app vitest (web sidebar).

- [ ] Repository id stamped by the engine (origin remote, else
      device-scoped git-dir hash); clones/worktrees match
- [ ] Sidebar project mode folds same-id projects; oldest checkout
      names the group; shared icon/color per repository (existing
      Solar set)
- [ ] New-session composer: project (repository) first, then device
- [ ] `roll_text.rs` ported in simple form; no blur/tilt
- [ ] Engine tests for id stamping + grouping
- [ ] Web sidebar: grouping + rolling labels
- [ ] iOS/client/mobile/icons/pins hunks excluded, recorded
- [ ] Port commit records the upstream SHA + exclusions
