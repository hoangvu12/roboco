# 22 — Sidebar: a chat left before its first message lands shows its run, not "Failed" (A6)

**Lane:** 1d Fixes: sidebar + chat. **Effort:** M.

**What to build:** Start a chat (the host first checks out a fresh
worktree) and switch away before the first message lands. The row then
reads Working whatever happens, the done chime is lost, and it turns
"Failed" 120 s in while the run is fine. That's common when juggling
several chats. Port upstream's adoption: `adopt_pending_sends`,
`adopted_by`, `begin_first_send`/`begin_send` in `crates/ui/src/state.rs`,
plus the composer hook. Roboco's `PendingSend` and
`ack_pending_send_from_transcript` (`state.rs:653, 1502`) exist, so this
extends them. The adoption rules key on session rows, which Roboco has.
Route through the chat's `EngineTarget` (fleet) where upstream assumes
one engine.

**Upstream:** `ec0897feb` (#1066): `state.rs` (+431), `composer.rs` (+7),
tests.

**Roboco files:** `crates/ui/src/state.rs`, `crates/ui/src/composer.rs`,
sidebar status derivation in `crates/ui/src/shell/spaces.rs`.

**Excluded:** the Linux `examples/browser-fixture/linux.rs`
typing-race fix (in-app browser removal).

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A6; `part-07.md` `ec0897feb`.

- [ ] Leave-before-land: the row tracks the real run, the chime fires on done, no false "Failed" (state tests ported)
- [ ] Works for a chat on a non-local fleet engine (test with a scoped engine target)
- [ ] Commit records `ec0897feb` and the excluded browser-fixture hunk

## Comments
