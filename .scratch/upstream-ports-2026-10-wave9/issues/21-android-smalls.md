# 21 — Android smalls: Providers no-crash, composer level, chip icon (upstream #980/#1014/#1010-icon)

**What to build:** Three tiny 1:1 iOS-parity ports:
- `5b8edb2de` (#980): the Providers/accounts sheet doesn't crash when
  there's nothing to pick — adapted: no device to pick = no paired engine.
- `2ea4e3a05` (#1014): the composer rests level with the sidebar toolbar —
  tablet split-shell alignment (`shell/SplitRoot.kt`).
- `3c97a05cf` (#1010, icon only): the subagents chip icon fix. (The terminal glyph painter does not port —
  ticket 23 declined.)

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** app unit tests (+ tablet screenshot if the level
move is visible). Source: `.scratch/upstream-drift/2026-10-25.md` § D-a.

- [ ] Three smalls ported
- [ ] Commit records upstream SHAs `5b8edb2de`, `2ea4e3a05`, `3c97a05cf` (icon)

## Comments
