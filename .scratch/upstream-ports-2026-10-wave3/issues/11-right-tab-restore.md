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
