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
