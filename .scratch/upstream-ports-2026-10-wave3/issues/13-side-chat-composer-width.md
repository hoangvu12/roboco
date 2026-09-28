# 13 — Fix side chat composer conversation width

**What to build:** The side-chat composer measures the conversation width
correctly (6+/4- fix in the composer's width calc).

**Blocked by:** 09.

**Status:** ready-for-agent

**Upstream SHAs:** `cdec9726` (#567) — `crates/ui/src/shell/side_chats.rs`.
Trivial; ride alongside 11 or 14 if convenient, but keep its own commit.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
side-chat tests.

- [ ] Composer width fixed
- [ ] Port commit records upstream SHA
