# 60 — Settings → Worktrees: per-device custom worktree locations (#530 UI)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** The engine and proto half landed in `c40b42f32`
(`worktree_settings.rs`, `validate_windows_worktree_path`, GET/SET RPCs
at `rpc/src/lib.rs:193-194`). No UI calls them, so users can only set
`ROBOCO_WORKTREES_DIR`. Port the page: `settings/worktrees.rs` (+638),
`popover/contained.rs`, the `widgets.rs` additions and the `spaces.rs`
`MenuAlign` change. Per-device rows (fleet: one per engine in the
registry), browse + validate. Windows long-path guidance appears where
the engine rejects a path.

**Upstream:** `01a399db1` (#530) UI slice.

**Roboco files:** `crates/ui/src/settings/{worktrees.rs (new),widgets.rs}`,
`crates/ui/src/popover/contained.rs` (new),
`crates/ui/src/shell/spaces.rs`, `crates/ui/src/settings.rs` (section).

**Excluded:** none.

**Proto change:** no (RPCs exist).

**Blocked by:** 01. **Blocks:** 61 (search anchors).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #530; `part-06.md` `01a399db1`.

- [ ] Set, clear and validate a per-device location; a new worktree lands there (UI + engine test)
- [ ] Windows invalid paths show the engine's message
- [ ] Commit records `01a399db1` (UI slice)

## Comments
