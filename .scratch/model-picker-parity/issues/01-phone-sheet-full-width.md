# 01 — Phone sheets render full width

**What to build:** At phone widths (≤768px) every `PickerCard` opens as the
shared bottom sheet (`.rb-drawer-card`, `position: fixed; left: 0; right: 0;
bottom: 0`), but three surfaces paint a narrow flush-left card instead of
spanning the viewport, because a class carrying `width:` on the sheet popup
element over-constrains `left + right + width` — **width wins**. After this
ticket, the model picker (both arms), the compact card, and the sidebar
section menu all render as full-width sheets.

The victims, verified today:

| Surface | Surviving rule | Symptom |
| --- | --- | --- |
| Model picker (standard) | `.identity-card { width: 304px }` (app.css:4821-4823) lands on the sheet popup via `cardClassName` | 304px flush-left |
| Model picker (compact) | + inner `.compact-card { width: 256px }` (app.css:17729-17731) | 256 inside 304 |
| Sidebar section menu | `.section-menu-body { width: 180px }` (app.css:2743-2746; sidebar-sections.tsx:289 passes it as `PickerCard`'s `cardClassName`) | 180px flush-left |

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `../research.md` §3 (the full phone-sheet audit table — every
other sheet arm is already full-width; do not re-audit them).

**Desktop reference (for lookups only):** none — the phone sheet is
web-native (ticket 49, research M8 §(b)2); the desktop's minimum window
geometry makes a 375px viewport impossible.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | add `width: 100%;` to the `.rb-drawer-card` frame rule inside the existing `@media (max-width: 768px)` block (app.css:5866-5887), and a new `.rb-drawer-card .compact-card { width: 100%; }` rule in the same block |
| `web/packages/app/tests/responsive-surface.test.ts` | edit | extend the `phoneRules` assertions |

## 1. Why the frame rule, not per-class fixes

Ticket 15 solved this per-arm for `.rb-drawer-card .dialog-card { width: 100% }`
(app.css:5908-5913) and ticket 54 for `.settings-select-menu`
(app.css:12625-12630). Three arms now share the same failure mode, so the
fix generalizes to the frame: **one rule owns the sheet's width** the way it
already owns placement (fixed, bottom, radius, safe-area). Per-class fixes
would repeat ticket 15's scoping without its per-arm chrome needs and would
miss the next fixed-width class that lands on a sheet popup.

## 2. Exact rules

1. Inside the existing phone block (app.css:5866-5887), add `width: 100%;` to
   the `.rb-drawer-card` rule. Specificity is equal (0,1,0) to
   `.identity-card` and `.section-menu-body`; **later source order wins**
   (5866 > 4821 > 2743), so both victims go full-width.
2. Add `.rb-drawer-card .compact-card { width: 100%; }` in the same block —
   (0,2,0) beats `.compact-card`'s (0,1,0) at app.css:17729 regardless of
   order.

**Do NOT touch** (verified no-ops or owned elsewhere): the frame's
radius/max-height/overflow/safe-area terms (app.css:5871-5887),
`.rb-drawer-card.popover-card` padding (app.css:5925-5927),
`.right-plus-menu-sheet` (app.css:5939-5947 — has its own phone recipe), the
dialog arm (app.css:5908 — `width: 100%` is a no-op), the settings-select arm
(app.css:12625 — same). The sidebar drawer (`min(20rem, 85vw)`,
app.css:8429) and right-pane drawer (`min(30rem, 88vw)`, app.css:8282) are
deliberate partial-width side drawers — intentional, leave them.

## 3. Tests

`tests/responsive-surface.test.ts` already asserts phone-block rules through
a `phoneRules` helper (the frame rule is asserted around :329-341 — read the
file and follow its idiom). Extend it:
- assert `width: 100%` on the `.rb-drawer-card` frame rule;
- new case: `phoneRules(".rb-drawer-card .compact-card")` → `width: 100%`.

## 4. Acceptance checklist

- [x] `.rb-drawer-card` frame rule carries `width: 100%` in the phone block
- [x] `.rb-drawer-card .compact-card` rule added; compact sheet spans width
- [x] Model picker standard + compact, and the sidebar section menu, render
      full-width sheets at ≤768px (manual phone-width check)
- [x] No other sheet arm changed (dialog/select/`+`-menu/palettes)
- [x] `responsive-surface.test.ts` pins both rules; full app suite green

## Comments

**Verification evidence** (commit `6d453ad6`, branch
`ticket/mp-01-phone-sheet-full-width`):

- Both rules landed in the existing phone block per §2; pinned by two new
  `phoneRules` contracts in `tests/responsive-surface.test.ts` (written
  red-first: 2 failed before the CSS, 13/13 after).
- The phone-width render criterion is demonstrated by the deterministic
  cascade, not a live browser run (no browser may run on this machine):
  `PickerCard`'s phone arm drops `width`/`style` (PickerCard.tsx),
  `RbDrawerSheet` puts `cardClassName` on the fixed popup
  (responsive-surface.tsx:169), the frame rule (0,1,0) is later in source
  order than `.identity-card` (:4821) and `.section-menu-body` (:2743),
  `.rb-drawer-card .compact-card` (0,2,0) beats `.compact-card`'s (0,1,0),
  and no other `width`/`max-width` rule matches any sheet-riding class
  (grep-verified) — with universal `box-sizing: border-box`, `width: 100%`
  spans exactly as `left: 0; right: 0` did. Every link is deterministic
  CSS semantics or source-verified structure.
- No other arm changed: grep over every `cardClassName` in the app finds no
  other width-carrying class; the frame term is a no-op for arms already
  spanning via `left: 0; right: 0`.
- Verification: `pnpm exec vitest run tests/responsive-surface.test.ts`
  (13/13), the adjacent phone suites (33/33 across 4 files),
  `pnpm exec tsc --noEmit` (clean), and the full app suite
  `pnpm exec vitest run` (146 files, 2195 tests, all green).
