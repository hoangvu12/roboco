# 62 — Changes scope: one selector, not three buttons

**What to build:** The Changes surface's scope filter (Working tree / Branch changes / Latest turn) becomes the desktop's single 24px selector trigger opening a 180px popover with three menu rows — replacing the web's self-documented three-button row.

**Blocked by:** None — can start immediately.

**Status:** landed

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

- [ ] Screenshot pair (code-inspection level): **waived** — this session's verification contract is build + tests only (no dev server / browser); the geometry is code-inspected against the S3(b) table instead (24px trigger / 180px card / 10px below / 2px row gaps — see Comments).
- [x] Selecting each scope works and persists as before.
- [x] `pnpm -r build` + `pnpm test` green; no new literal hex.

## Comments

**Landed** (branch `wp2r2/62-changes-scope-selector`, worktree `wt/wp2r2-62`):

- `routes/changes-page.tsx`: `ChangesToolbar` now mounts `ChangesScopeSelector` where the three chips sat — a `PickerCard` (`placement="anchorBelowGap"`, `gap={10}`, `width={180}`, `role="menu"`, `initialFocus={false}`; the phone arm converts to ticket 49's bottom sheet for free) whose adopted trigger is the desktop's 24px chip: `DIFF_SCOPE_LABELS[scope]` (12px/14px) + the `altArrowDown` 12px chevron at `text_muted@0.7`, rest wash 0.05, hover 0.14, `--rb-radius-control`. The body is `ChangesScopeMenuRows` — one `MenuRowNav` per `DIFF_SCOPE_CHIPS` (`DiffScope::ALL`) on a 2px column gap, `selected` wash + `aria-selected` + a trailing 12px check on the active scope; every row's pick is the UNCHANGED `changesSurfaceStore.setScope` plus close (controlled `open`/`onOpenChange` owned by the toolbar). Rows split out as a unit because Base UI's portal renders nothing under `renderToString`, so the node suite asserts the rows through that unit.
- `styles/app.css`: `.changes-scope`/`.changes-scope-chip*` (the deviation block) replaced by `.changes-scope-trigger`/`-label`/`-caret`/`-menu` (2px gap)/`-check`; reduced-motion list updated. No literal hex — all `--rb-*`-derived.
- Comment fixes where the old control was named: `lib/diff.ts`, `state/changes-surface.ts`, `components/surface-registry.tsx`, and the `changes-page.tsx` header deviation note (now records that ticket 62 overturned ticket 22's sanction; the native `<select>` base picker stays the one accepted deviation).
- `tests/changes-surface.test.ts`: the 3-button markup smoke is now the selector interaction — closed toolbar = ONE trigger (`changes-scope-trigger`, active label only); open = three rows with the active one selected + checked; pick = `setScope` through the store the rows call, with the toolbar re-rendering the new label. +2 tests (1293 → 1295 in the app package).
- Verification: `pnpm -r build` green; `pnpm test` green (app 81 files / 1295 tests; engine-client 5 files / 43 tests). One environmental note: the first full `pnpm test` failed inside engine-client's cargo-gated suites (`cargo build --example web_smoke` exited 101, a cold-worktree Rust build transient; this ticket touches no Rust); the direct build and every subsequent run pass.
- Deviations: none in the numbers. One naming note — the port spec's `placement="anchorBelow"` + `gap={10}` would silently drop the gap (`anchorHelperPlacement("anchorBelow")` pins sideOffset to 6), so the selector passes `placement="anchorBelowGap"` + `gap={10}`, the helper the desktop's `anchored_menu_below_gap(..., 10.0)` maps to. The desktop's rows carry no check glyph (selected = wash only); the ticket's "checked mark on the active" is the wash PLUS a trailing check, matching the settings-engine menu's row idiom.

(User report 2026-09-20 #7. Supersedes ticket 22's recorded 3-button deviation.)
