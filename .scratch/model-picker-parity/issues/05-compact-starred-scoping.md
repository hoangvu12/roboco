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

**Status:** ready-for-agent

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

- [ ] Starred row scopes the models page to favorites
- [ ] Placeholder and empty-note strings match the desktop
- [ ] Rail resets on close/reopen
- [ ] Full app suite green
