# 05 — Compact picker: Starred row scopes to favorites

**What to build:** In the compact picker's Providers page, the "Starred" row
should show **only starred models** — the desktop's
`show_compact_starred` switches the models page to the favorites rail with
the placeholder "Search starred…" (pickers/compact.rs:339-342; desktop test
pickers.rs:7519-7538). The web's `showStarred()` keeps the rail `"all"`
(composer-pickers.tsx:1385-1391), so the Starred row shows the **full list
starred-first**, not starred-only. Add a per-page rail state
(`"all" | "favorites"`) and swap the placeholder when starred.

Also fold in the one-line compact row height: web rows are 34px
(32 + 2 gap, composer-pickers.tsx:1092-1095) vs the desktop's 32px
(compact.rs:5-19's `compact_list_height` math) — align to 32 + 2 gap is
*close enough*; only change if trivially safe, otherwise leave and note.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `../research.md` §2 compact notes (item 13a).

**Desktop reference (for lookups only):**
`crates/ui/src/pickers/compact.rs:339-342` (starred scoping +
placeholder), `:327-336` (`show_compact_models` — the "all" rail),
`crates/ui/src/pickers.rs:7519-7538` (the desktop's pinned test).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-pickers.tsx` | edit | per-page rail state; `showStarred()` sets it to favorites; the models-page filter uses it; placeholder swaps to "Search starred…" |
| `web/packages/app/tests/composer-reasoning.test.ts` | edit | the compact block (~:250) gains the starred-scoping case |

## 1. Notes

- The rail state is per-open (reset with the rest of the compact page state
  on every open — the existing reset covers it; verify).
- Empty starred list under the favorites rail: "No starred models yet — hit
  a row's star" (the standard card's empty note, pickers.rs:4032-4050) —
  reuse the same string.

## 2. Tests

`composer-reasoning.test.ts` compact block: Starred row → the models page
lists only starred models, placeholder reads "Search starred…", empty
favorites → the starred empty note; Escape returns to the panel with the
rail reset.

## 3. Acceptance checklist

- [x] Starred row scopes the models page to favorites
- [x] Placeholder and empty-note strings match the desktop
- [x] Rail resets on close/reopen
- [x] Full app suite green

## Comments

**Branch:** `ticket/mp-05-compact-starred-scoping` (base `a485d5ce`). Commit
`f151ae22` (implementation, TDD red→green — red failed at the ticket's exact
bug: `['Opus 5.5','Haiku 4.5','GPT']` instead of `['Opus 5.5']`) + review-pass
commit (extracts the repeated browse-rail decision into one `browseRail`
const and the filter's placeholder/aria-label into one `filterLabel`; adds
two regression guards: the panel's ↑/↓ neighbor math rides the browse rail
while the rail state still holds a favorites scope — verified failing
without the `listRail` derivation — and the card close/reopen rail reset;
the compact block's afterEach clears the starred sticky defaults so no
favorite leaks into the later identity suites). Verification (all from
`web/packages/app`): named seam `tests/composer-reasoning.test.ts` → 38/38;
adjacent composer-pickers mounters (`flyout-side`, `shortcuts`,
`base-popover`) → 85/85; full app suite `pnpm exec vitest run` → **148
files, 2232 tests, all passed**; `pnpm exec tsc --noEmit` clean.

**Row height — left as-is, with cause:** the ticket's "34px vs 32px"
compared the web's virtualizer ITEM box against the desktop's ROW: the 34
is `COMPACT_ROW_HEIGHT + 2` with the 2px inter-row gap baked into the box
(`.model-row-item { padding-bottom: 2px; box-sizing: border-box }`), so
the visual row is 32px — exactly the desktop's `COMPACT_ROW_HEIGHT: 32.0`
+ `MENU_GAP: 2.0` (compact.rs:6-7, popover.rs:316), and `compactListHeight`
is `compact_list_height` verbatim (`count * (32 + 2) + 2 * 4`). Shrinking
the box to 32 would double-count the gap (30px visual rows) — a
regression, not an alignment. The desktop renders the compact models
page's rows one-line on every rail (`render_model_row`'s
`compact_model_picker` gate, pickers.rs:4157-4159 → `render_compact_model_row`),
so the web's one-line rows match on every rail too.

**Judgment calls:** the rail state is the full `ModelRail` union
(`"all" | "favorites" | "harness"`) rather than the ticket's
`"all" | "favorites"` sketch — the locked chat's Harness scope must
survive (compact.rs:327-336; ticket 04's lane). `showStarred()` keeps
`setCursor(0)`; the desktop anchors on the selected row when it is
starred (compact.rs:357-363) — an anchor difference only when the selected
model is starred below index 0's row, left as a residual. The filter's
placeholder/aria-label derive from one `filterLabel` ("Search starred…",
"Search models…", "Search providers…" — all label + ellipsis).
