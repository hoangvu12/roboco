# 15 — Fix completion discovery in unsaved side chats

**What to build:** Composer completion discovery (skills/commands/mentions)
works in unsaved (local-only) side chats — the discovery loop previously
replaced the side chat's config before the first-send assertions; createChat
now carries the fixture's model. Swap only the harness and end on the
inherited one.

**Blocked by:** 14.

**Status:** ready-for-agent

**Upstream SHAs:** `9b756647` (#588) — `crates/ui/src/composer.rs` (169+/53-).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
completion tests.

- [ ] Completion works in unsaved side chats; inherited config kept
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments

**Branch:** `wave3/15-completion-discovery` → merged into main `Merge wave3/15`. Commit `67c6005d`: completion_workspace_params (unsaved → parent chatId, cwd/device from local row); discovery read-only, never queries the unsaved chat; web completionTargetChatId mirrors it (web had the exact same gap). Post-merge re-verified within the wave-18 boundary run.

**Judgment calls:** dropped upstream's targetDeviceId=="local" frame assertion (roboco strips it at the socket — red-test discovery, commented); 16× runtime-yield pump + advance_clock for debounce; #590's test refinement deliberately left for ticket 16 (port on top of 67c6005d, keep pump + deviation).
