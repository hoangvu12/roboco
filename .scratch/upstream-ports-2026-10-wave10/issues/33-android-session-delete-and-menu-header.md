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

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** wave-9 `issues/18-android-error-cards-delete.md` (+ its
2026-10-28 comment); `2026-10-28.md` §C; `pass3-ios-1.md` housekeeping.

- [x] Delete from the long-press and ⋯ menus with confirmation; the row leaves the list (unit + instrumented flow test)
- [x] Archive styling is neutral
- [x] The ⋯ header shows title + "project @ device" (capture test; see comments)
- [x] Commit records `f30a8641b` (iOS delete/menu parts) and absorbs part of wave-9 18

## Comments

2026-10-28 (wave10/android, lane 3): carried `f30a8641b` (#967) iOS
delete/menu parts by intent.

- Plumbing: `SessionWrites.deleteSession` → the existing
  `CoreClient.deleteSession` FFI (no Rust change); `RowActions.delete`
  (`OpKind.Chat`, no toast/Undo); `SessionPrompts.confirmDeleteSession`
  shows the "Delete session?" alert (Cancel + destructive Delete; message
  `“<title>” will be permanently deleted. This can’t be undone.`, untitled
  → "New session"; no Cloud-machine variant).
- List long-press menu (`sessionRowMenu`): Archive no longer destructive;
  "Delete" (trash, destructive) trails it, and the Archived list's menu
  gains it too (iOS `deleteAction`). Tablet sidebar rows share the wrapper.
- Session ⋯ menu: Archive neutral, "Delete" row last (disabled with
  Archive while read-only), the menu is headed by `title
"project @ device"`
  from `SessionChrome` (one wrapped `Menu.title`, iOS's header). After a
  delete the screen leaves like Archive (`leaveAfterArchive`: split draft,
  else guarded pop).
- Deviation: the header bar keeps its two-line title (iOS moved it into
  the menu only); removing it would re-capture every session golden for
  no behaviour gain.
- Tests: `RowActionsTest.deleteRoutes…`, `SessionRowMenuTest` (menu
  shape, Archive neutral, Delete in both menus, adapter → confirm),
  `SessionPromptsTest.confirmDeleteSession…` (alert copy, Cancel, refused
  delete), `SessionMenuTest` (builder, header rule, the ⋯ Delete → confirm
  → delete → leave wiring), `SessionMenuScreenshotTest.{light,dark}HeadedWithDelete`
  captures; instrumented `SessionFlowTest.sessionMenuHeaderAndDelete` on
  the fixture's `chat-home` (no other flow test reads it; the engine lives
  for the whole run).
- Goldens: CI's `android-screenshots`/`record_goldens` job was dropped in
  `33e5590da`, so new captures run as render/geometry checks inside
  `testDebugUnitTest` and no PNG is committed (the existing
  `session_menu_*` shots are unchanged: they pass no `onDelete`/title).

2026-10-28 (PR #38 review fixes, one batch):
- The ⋯ menu header keeps each line to one, ellipsized (`GlassMenu`'s
  `MenuTitleLine` splits a title with a line break), so a long title can't
  push "project @ device" out.
- Deleting the session open beside the tablet sidebar from its row menu
  now leaves it for the draft (`AppRouter.leaveDeletedSession`, the
  tablet router's override; `SessionRowCommands(onDeleted)`), as the ⋯
  menu's Delete does. Test: `SessionRowMenuTest.aConfirmedDeleteReachesTheRouterHook`.
