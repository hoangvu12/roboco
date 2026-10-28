# 41 — Android: drag a session between Pinned, a section and Recent (#909 iPhone half)

**Lane:** 3 Android parity. **Effort:** M.

**What to build:** Long-press dragging a session between Pinned, a custom
section and Recent, as on the iPhone. Android only reorders within
Pinned today (`sessions/PinReorder.kt`), and `crates/mobile` has
`move_pin`/`assign_section` but no placement. Add the FFI calls
`pin_session_at` and `place_session`. `f382151fd` held them "for a
mobile follow-up" that never got a ticket. Pin and section state is
engine-side in Roboco (`engine/src/sidebar_state.rs`), so placement
goes through the existing sidebar-state RPCs.

**Upstream:** `26c8e8404` (#909) iPhone half:
`Threads/SessionListController.swift:364-470` (behaviour spec), FFI
`pin_session_at`/`place_session`.

**Roboco files:** `crates/mobile/src/client_ffi/workspace.rs`,
`apps/android/.../sessions/{PinReorder,ListSection,SessionsMenus}.kt`.

**Excluded:** the registry/sync ordering keys (cloud). Roboco's
engine-side store is the authority.

**Proto change:** no wire type (FFI over existing sidebar-state RPCs;
add an RPC only if placement can't be expressed, and flag it).

**Blocked by:** 01.

**Status:** in-progress (2026-10-28, `wave10/android`): core, FFI, write seam and drop model landed; the Android drag gesture is a follow-up (see comments)

**Source:** `2026-10-28.md` §C; `part-06.md` `26c8e8404`.

- [ ] Drag Pinned → section, section → Recent, Recent → Pinned at a chosen index (instrumented flow test) — the gesture is the follow-up
- [x] Desktop sees the same placement (engine-side state test)
- [x] Commit records `26c8e8404` (iPhone half)

## Comments

2026-10-28 (wave10/android, lane 3): carried `26c8e8404` (#909) iPhone
half by intent — the FFI and the drop semantics; no new RPC (placement is
expressed through the existing sidebar-state write: sections already keep
an ordered `sessionIds`).

- `roboco-mobile-client`: `pin_session_at(chat, after, before)` (a chat in
  the raw pins moves, any other pins at the slot — both `None` after the
  last visible pin like `pin_session`; either way it leaves its section)
  and `place_session(chat, section?, after, before)` (`sidebar::
  place_in_section`: leave every section, join the target before the
  surviving `before` anchor, else after `after`, else at the end; the
  target un-collapses; `None` = back to Recent). `assign_section` is the
  anchorless placement. Anchors are scoped ids; another engine's count as
  absent. FFI `CoreClient.pinSessionAt` / `placeSession`.
- Android: `SessionWrites.pinSessionAt/placeSession`, `RowActions.pinAt/
  place` (one serial write lane, Sidebar failure copy), and
  `sessions/SessionDrop.kt` — iOS's `dropTarget` (Pinned, Recent, user
  sections; never project groups or search results), `dropSlot` (the
  neighbours without the dragged row) and `performDropWith`'s writes
  (Pinned: move or pin at; section: place; Recent: unpin from Pinned,
  leave from a section, nothing within Recent) with `RowActions.applyDrop`.
- **Not done — the gesture.** The front page's long press already owns
  the lift context menu (`Modifier.contextMenu`); iOS's lift-then-drag
  needs `ContextMenuHost` to hand a moving finger from the lifted preview
  to a drag (dismissing the menu) and the list to show the drop gap.
  That interaction has to be tuned on a device and can't be compiled on
  this host (no Android SDK), so it is left for a follow-up that calls
  `sessionDropSlot` / `sessionDropWrite` / `applyDrop`.
- Tests: `cargo test -p roboco-mobile-client --lib
  placement_lands_between_the_anchors_and_leaves_other_sections`;
  `--test workspace_engine pin_and_section_ops_reach_the_engine_and_a_second_client`
  (extended: a placement before a member, and a pin at a slot that leaves
  the section, both read back from the engine by a second client — what
  a desktop sees); JVM `SessionDropTest`, `RowActionsTest.dropsRouteTo…`.

2026-10-28 (PR #38 review fixes, one batch):
- The drag gesture stays a follow-up: it has to hand off from the
  context-menu lift and needs a compiler and a device to tune, which this
  host doesn't have. Status stays in-progress; the PR title says
  "41 core only; gesture follow-up".
