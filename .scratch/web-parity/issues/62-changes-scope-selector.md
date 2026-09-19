# 62 — Changes scope: one selector, not three buttons

**What to build:** The Changes surface's scope filter (Working tree / Branch changes / Latest turn) becomes the desktop's single 24px selector trigger opening a 180px popover with three menu rows — replacing the web's self-documented three-button row.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-20/nav-picker-diff-subagent.md` — S3 (the web's 3-button row at changes-page.tsx:37-42 with its ticket-22 deviation note; the desktop's trigger + popover at changes.rs:3699-3871: 24px trigger, 180px popover, 3 `menu_row`s, 2px gaps, 10px-below placement; the port spec).

**Desktop reference (for lookups only):** `crates/ui/src/changes.rs:3699-3871` (the scope selector control — geometry, rows, placement, checked state).

## 1. Context a fresh session needs

- Ticket 22 landed the Changes pane with the 3-button row as a recorded deviation (changes-page.tsx:37-42 self-documents it). The `setScope` store (state/changes-surface.ts / changes-store.ts) is correct and UNCHANGED — only the control swaps.
- The web has the primitives: `PickerCard` (ticket 49's popover card, `anchorBelow`, `gap`, width props) and `MenuRowNav` (the menu row primitive the research names).

## 2. Spec

Copy the research S3(b) port spec verbatim:
- ONE 24px trigger (icon = the desktop's scope glyph — find its icon id in the Rust) where the 3 buttons sat.
- Popover: `PickerCard` with `anchorBelow`, gap 10, width 180.
- Rows: three `MenuRowNav`s over the unchanged `setScope` — labels "Working tree", "Branch changes", "Latest turn" (desktop strings, changes.rs), checked mark on the active scope.
- 2px row gaps (the Rust's 3699-3871 spacing — copy the exact numbers from the research file's table).

## 3. Pure logic to port

None — the store is landed. If a test asserts the 3-button markup, update it to the selector (open → three rows → click switches scope).

## 4. Gaps this ticket closes

| item | kind | desktop value (file:line) | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| Scope control | layout/behavior | 24px trigger + 180px popover, 3 menu rows (changes.rs:3699-3871) | 3 adjacent buttons (changes-page.tsx:37-42) | §2 |

## 5. Do not

- Do not change `setScope` semantics or the scope persistence.
- Do not touch the changes list/diff rendering (22/23 own).
- Do not invent a fourth scope or rename the labels.
- Do not touch the desktop Rust.

## 6. Acceptance

- [ ] Screenshot pair (code-inspection level): one trigger; popover matches the desktop's geometry (24/180/10/2px) and row set.
- [ ] Selecting each scope works and persists as before.
- [ ] `pnpm -r build` + `pnpm test` green; no new literal hex.

## Comments

(User report 2026-09-20 #7. Supersedes ticket 22's recorded 3-button deviation.)
