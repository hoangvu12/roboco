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

## Comments

**Branch:** `wave3/14-first-send` → merged into main `Merge wave3/14`. Commits `69e97ec9` (desktop +364/−63: open_side_chat_tab unsaved model, unsaved_side_chat_create + side_chat_saved + start_chat_watches, first-send Mutate createChat 30s-bounded before the run, close drops unsent, subagent_rows Reverse(spawned_at)) + `40cb54ee` (web +399/−116: unsaved registry + mintUnsavedSideChat + markSideChatSaved, prune spares unsaved, newest-first, dead createChildChat removed). Post-merge re-verified: ui 1322/1322 (-P ci), web 137 files/2068.

**Judgment calls:** Roboco-only seam start_chat_watches via registry target_for_id + unsaved guard in spawn_registry_watch heal branch; web mint-before-snapshot (failed create preserves draft — web's own placement); unsaved registry is module state with subscribe seam. For 15: seam state.side_chat_unsaved() + selected_chat_row().parent_chat_id; upstream rewrites the ported first-send test — port 15 on top. For 16: apply_chat_config pending-copy stamping + web draft-only config flow.
