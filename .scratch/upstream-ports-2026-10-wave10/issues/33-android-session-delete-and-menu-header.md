# 33 — Android: Delete session; ⋯ menu headed by title + "project @ device" (wave-9 18, part)

**Lane:** 3 Android parity. **Effort:** S.

**What to build:** the part of wave-9 ticket 18 that needs no new
plumbing, as a 1:1 iPhone port (ADR 0009):
- **Delete session** from the Sessions list long-press menu and from the
  session's ⋯ menu, with a "Delete session?" confirmation matching the
  desktop. Archive is no longer styled destructive. Use the existing core
  call `crates/mobile/src/client_ffi/workspace.rs:134` `delete_session`.
  No FFI work.
- The ⋯ menu header shows the session title plus "project @ device",
  adapted to Roboco's engine-local naming (the paired engine's device
  name).

**Upstream:** `f30a8641b` (#967) iOS parts:
`Threads/SessionListController.swift:302-306`,
`Session/SessionViewController.swift:800-880`, UITest
`testSessionMenuHeaderAndDelete` (behaviour spec only).

**Roboco files:** `apps/android/.../sessions/{SessionRowActions,SessionsMenus}.kt`,
`apps/android/.../session/SessionMenu.kt`, tests and goldens.

**Excluded:** iOS code; cloud device naming.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** wave-9 `issues/18-android-error-cards-delete.md` (+ its
2026-10-28 comment); `2026-10-28.md` §C; `pass3-ios-1.md` housekeeping.

- [ ] Delete from the long-press and ⋯ menus with confirmation; the row leaves the list (unit + instrumented flow test)
- [ ] Archive styling is neutral
- [ ] The ⋯ header shows title + "project @ device" (golden)
- [ ] Commit records `f30a8641b` (iOS delete/menu parts) and absorbs part of wave-9 18

## Comments
