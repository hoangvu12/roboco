# 09 — Settings cosmetics: files row copy, autosave select, Forget affordance

**What to build:** Three verified one-spot settings drift items, batched
because they share the settings-pages seam and are each small:

1. **Files row label.** Web "Show all files" (settings-files.tsx:75, aria
   :80) vs desktop "Show hidden and ignored files" (files.rs:193-198).
   One-line title/aria swap.
2. **Autosave-delay control shape.** Desktop renders a 112px select
   dropdown (files.rs:88-112, options "300 ms…3 s"); web renders 5 pill
   buttons (settings-files.tsx:41-58 + `Pill` :87-99). Port to the
   in-settings `RbSelect` precedent — `FontSizeSelect`/`FontFamilySelect`
   in settings-appearance.tsx with `settings-select-trigger`/
   `settings-select-menu` classes (app.css:12567-12572). Same
   `DELAY_OPTIONS` [300, 600, 900, 1500, 3000]; a unique `overlaySource`
   id; 112px trigger width; delete the `Pill`/`pill-row` markup after
   verifying the classes have no other users.
3. **Accounts Forget affordance.** Desktop: inactive-only, icon-only trash
   (14px, rounded 6, px 6 py 4, muted, hover wash + text,
   `tooltip("Forget account")`, busy → opacity 0.5) — accounts.rs:1147-1175.
   Web: a text `btn btn-danger-ghost` "Forget" button
   (settings-accounts.tsx:443-447). Port to an icon-only button with
   `aria-label="Forget account"` + the existing `Tooltip` wrapper
   (components/ui/Tooltip.tsx; usage chat-list.tsx:1347) and the
   `trashBinMinimalistic` icon (icons/generated/index.ts:121); keep
   `disabled={busy}`.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` (settings items 3-4).

**Desktop reference (for look-ups only):**
`crates/ui/src/settings/files.rs:88-112, 193-198`;
`crates/ui/src/settings/accounts.rs:1147-1175`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-files.tsx` | edit | label swap; RbSelect port; delete Pill markup |
| `web/packages/app/src/routes/settings-accounts.tsx` | edit | the Forget affordance |
| `web/packages/app/src/styles/app.css` | edit (only if the select needs a width tweak) | — |
| `web/packages/app/tests/account-row.test.ts` | edit | icon present, tooltip label, busy disables, click fires onForget |

## 1. Notes

- Reduced-motion snap for the select menu already exists
  (app.css:13110-13115).
- Icon-only on phone/touch is a discoverability trade the desktop already
  makes — the tooltip + aria-label carry it.
- The select's trigger must not grow past 112px (the row's layout contract).

## 2. Tests

- `account-row.test.ts` additions (the mounted idiom already exists there).
- The files page: a small mounted assertion of the label text and the
  select's option set (or fold into an existing settings suite if one
  covers files — check `tests/` first).

## 3. Acceptance checklist

- [x] "Show hidden and ignored files" copy
- [x] Autosave is a 112px RbSelect with the same five options
- [x] Forget is the icon-only tooltip affordance, busy-disabled
- [x] Tests + full app suite green

## Comments

- Implemented on `ticket/wpn-09-settings-cosmetics` (base `649f9d91`):
  the show-all row carries "Show hidden and ignored files" in title +
  switch aria (files.rs:193-198); the autosave delay is a `DelaySelect`
  riding the in-settings `RbSelect` precedent with the same
  `DELAY_OPTIONS` [300, 600, 900, 1500, 3000], unique
  `overlaySource="settings-files-autosave-delay"`, and
  `.delay-trigger`/`.delay-menu` at 112px (files.rs:111) so the trigger
  cannot grow past the row's layout contract (`.settings-select-label`
  ellipsis carries overflow); the Forget affordance is the icon-only
  trash at muted with hover wash + text and busy → opacity 0.5
  (`.account-forget`, accounts.rs:1147-1175 geometry), the label on the
  `Tooltip` wrapper + `aria-label="Forget account"`, `disabled={busy}`.
  Keyboard focus rides the global `:focus-visible` ring (app.css:66-69).
- The `Pill`/`pill-row` markup and its CSS (`.pill`, `.pill-selected`,
  `.settings-files-pills`) are deleted — grep-verified zero other users
  across `src/` and `tests/` before deletion (`.btn-danger-ghost` keeps
  its devices/remote-access users and stays).
- Deviation from the file table, adjudicated KEEP: app.css took the
  `.account-forget` styling in addition to the width tweak — the icon-only
  affordance's muted/hover/busy contract cannot ship as an unstyled button,
  and the pill-CSS deletion is exactly what "after verifying the classes
  have no other users" gated. Both are within item 2/3's described shape;
  the table's "only if the select needs a width tweak" row was
  under-specified for them.
- Deviation from the test plan, adjudicated KEEP: `account-row.test.ts`
  tests the SIDEBAR row; the Forget row is `settings-accounts.tsx`'s own
  (private) `AccountRow`. Following the ticket's named file, the settings
  row is exported for the suite (the file's own `LoginDialog` precedent,
  "exported for the mounted family test") and mounts standalone with plain
  props; the file's module mocks widen by three never-called exports
  (`useFleetRegistry`/`fleetStore`, `useEngineSession`, `useNow`) purely to
  satisfy the page's import graph.
- Known edge, deliberate: a persisted delay outside the five options
  (hand-edited localStorage only; the store clamps to [100, 10000]) shows
  its raw value in the trigger ("1.2 s") with no check in the menu — the
  desktop's own select highlights option 0 instead (a misleading label for
  the stored value). Neither side normalizes the store; web keeps the
  honest display. No spec requirement covers it.
- Tests: `tests/account-row.test.ts` gains the settings-row Forget suite
  (icon present + no text button, inactive-only, hover tooltip label,
  busy disables + swallows the press, click fires `onForget`);
  `tests/settings-files.test.ts` (new) mounts the page for the label/aria
  copy, the select family + `delay-trigger` + five-option set + pill
  absence, and the pick-commits-the-store seam.
- Verification: named seams `tests/settings-files.test.ts` +
  `tests/account-row.test.ts` 17/17; adjacent suites (ui-settings,
  settings-dialogs, settings-section, settings-general, accounts-view,
  reduced-motion, base-tooltip, responsive-surface) 137/137;
  `pnpm exec tsc --noEmit` clean; FULL app suite `pnpm exec vitest run` →
  **154 files / 2288 tests green**.
- Two-axis code review passed (both axes run in-session — no subagent
  runtime available in this lane; deviation stated in the review report).
  Standards: one actionable doc-truth fix landed (the files-test comment
  misattributed the select's phone-arm coverage; corrected to
  responsive-surface.test.ts); the DelaySelect/FontSizeSelect shape
  duplication is suppressed — the ticket itself names the per-surface
  precedent and the repo's own FontFamily/FontSize twins endorse it.
  Spec: all three items + both test asks landed; no missing, no unasked
  behavior beyond the adjudicated deviations above.
