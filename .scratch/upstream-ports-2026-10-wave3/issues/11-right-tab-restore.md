# 11 — Restore the most recently visited right-sidebar tab on close

**What to build:** Closing the active right tab returns to the most
recently visited live tab rather than the leftmost one.

**Blocked by:** 09 (web parity: 10).

**Status:** ready-for-agent

**Upstream SHAs:** `1f76f017` (#571) — `crates/ui/src/shell.rs` (205+),
`crates/ui/src/shell/side_chats.rs`. Web parity: a restore stack in the
web right-pane store (none exists today).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest for tab close/restore; web right-pane vitest.

- [ ] Most-recently-visited tab restored on close (desktop + web)
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments

**Branch:** `wave3/11-right-tab-restore` → merged into main `Merge wave3/11`. Commit `52c81dfb` (2 files, +239/−25): right_tab_history per panel (unique visits, non-Picker), forget_right_surface + right_surface_fallback (newest-first vs live tabs, first tab as never-visited fallback, empty→Picker), wired into resolved_right_active/close_right_surface/complete_file_close/remove_deleted_side_chats. 4 ported tests (2 unit verbatim, 2 gpui adapted: EngineBootConfig engine-local, settings::init in harness). Post-merge re-verified: full ui 1303/1303.

**Judgment calls:** delete_chat keeps no-forget (upstream parity, stale history inert); draft-bearing tab close keeps detach semantics. Web restore stack deferred (no web host for session-header controls; noted for a follow-up parity ticket) — desktop-only acceptance recorded.
