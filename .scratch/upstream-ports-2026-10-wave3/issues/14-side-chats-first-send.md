# 14 — Side chats saved on first send; newest subagents first

**What to build:** "New side chat" no longer mints the chat up front: the
tab opens on a local-only chat that opens no doc and writes no registry
row; its first send runs createChat (with the parent link, cwd, branch and
any config picked meanwhile) before the run, then attaches the doc watches;
closing it unsent drops it, draft or not. Subagents section lists most
recently updated first (latest spawning or steering turn; later spawns lead
within one turn), matching Chats.

**Blocked by:** 09, 13.

**Status:** ready-for-agent

**Upstream SHAs:** `bbd5f4dd` (#568) — `crates/ui/src/composer.rs` (99+),
`crates/ui/src/files/sections.rs`, `shell.rs`, `shell/side_chats.rs`,
`state.rs`. Web parity: local-only chat tab on web right pane (ticket 10's
store) with first-send createChat.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest (side-chat first-send + sections ordering); web side-chat vitest.

- [ ] Local-only until first send; closing unsent drops (desktop + web)
- [ ] Subagents newest-first
- [ ] Tests green
- [ ] Port commit records upstream SHA
