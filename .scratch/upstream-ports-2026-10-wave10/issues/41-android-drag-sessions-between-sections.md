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

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §C; `part-06.md` `26c8e8404`.

- [ ] Drag Pinned → section, section → Recent, Recent → Pinned at a chosen index (instrumented flow test)
- [ ] Desktop sees the same placement (engine-side state test)
- [ ] Commit records `26c8e8404` (iPhone half)

## Comments
