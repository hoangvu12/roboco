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

## Comments

**Branch:** `wave3/13-composer-width` → merged into main `Merge wave3/13`. Commit `e94b16d8` (side_chats.rs 6+/4-): side-chat composer routes through composer_target_width(right_visible_width, transcript_width, true) — docked cap shared with the main composer. Straight 1:1 carry (pre-change state byte-identical). Post-merge re-verified within the wave-C boundary run.

**Note:** web side-chat surface feeds live-measured pane width with no docked cap — desktop-only fix matches upstream; web parity of the cap would be a separate ticket.
