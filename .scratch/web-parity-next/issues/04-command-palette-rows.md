# 04 — Command palette rows: badges, hover, archived muting

**What to build:** Three verified gaps in one surface:

1. **Action rows lack shortcut badges.** Desktop renders `kbd_hint` on New
   chat (keymap `mod-n`), New project (`mod-shift-n`), Open settings
   (hardcoded `mod-`,` — not keymap-bound; theme action gets no badge)
   (command_palette.rs:273-293). Web's `MenuRowNav` renders icon + label
   only (command-palette.tsx:379-390). Everything needed exists:
   `KbdHint` (ui/KeyHint.tsx:60-64, already imported and used for the
   header ⌘K chip at :332), `useKeymap()` over `KeymapConfig`
   (`newSession`/`newProject`, state/ui-settings.ts:151-168,
   state/keymap.ts:35-37), and the `badgeCombo`/`validOrDefault` helpers
   (state/shortcuts.ts:260-262, :381-387).
2. **Hover doesn't move the highlight.** Desktop `hover_command`
   (command_palette.rs:154-163, wired :228-231) sets `palette.active = ix`
   permanently on row **mouse-move** (motion only — so rows scrolling under
   a resting pointer don't steal the keyboard's place; the keyboard then
   continues from the hovered row; hover does NOT scroll into view). Web
   rows have no pointer handler and the store has no hover API
   (state/command-palette.ts:141-171).
3. **Archived rows are not muted.** Desktop dims archived rows' **icons**
   (monogram opacity 0.4, harness brand alpha 0.4 vs 0.8 — shell.rs:6154,
   :6590-6598; **no title muting on the desktop** — the 55% title dim is the
   web sidebar's own treatment, app.css:11104-11127). Web's `ChatRow` never
   reads `row.archived` though lib/command-palette.ts:114 supplies it;
   `.command-chat-harness { opacity: 0.8 }` (app.css:15812-15815).

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` §3.

**Desktop reference (for lookups only):**
`crates/ui/src/shell/command_palette.rs:273-293` (badges), `:154-163`
(hover_command), `crates/ui/src/shell.rs:6154-6161, 6590-6598` (icon
muting).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/command-palette.tsx` | edit | badge helper + `<KbdHint>` after the label (label gets `flex: 1` so the chip pins right, desktop :287); `onMouseMove` on rows → `commandPaletteStore.hover(ix)`; `command-chat-row-archived` modifier on ChatRow |
| `web/packages/app/src/state/command-palette.ts` | edit | `hover(ix)` — `if (!open \|\| ix === active) return; active = ix; commit()` (last-writer-wins with keyboard = desktop semantics) |
| `web/packages/app/src/styles/app.css` | edit | `.command-chat-row-archived .command-chat-harness { opacity: 0.4 }`, restored under active; optional web-consistency title 55% mirroring the sidebar |
| `web/packages/app/tests/command-palette.test.ts` | edit | badge helper cases + store hover tests |

## 1. Notes

- The badge is outside `Highlighted` (the fuzzy-match span) so it is not a
  search target.
- Bindings: `newSession`→new-chat, `newProject`→new-project, settings→the
  literal `"mod-,"`, theme→no badge (null).
- Use `onMouseMove` (not pointer-enter) to keep "motion only" semantics.
- Spot-verified at parity (do not touch): sort/cap-30, keyboard wrap +
  enter latch + esc, sections/empty/footer copy, action order (pinned by
  command-palette.test.ts:36-50).

## 2. Tests

- Badge helper (pure, in the command-palette.test.ts style): custom rebind
  renders the rebound combo; invalid binding → default; settings constant;
  theme null.
- Store: `hover(0)` moves active; same-ix no-op; closed store ignores;
  `move` after hover continues from the hovered row.
- Archived muting: extend the existing `rows[0].archived` assertion
  (command-palette.test.ts:110-121) + a CSS-regex test (pattern:
  tests/sidebar-fade.test.ts:26).

## 3. Acceptance checklist

- [x] New chat / New project / Settings rows show their shortcut badge
- [x] Hovering a row moves the highlight; keyboard continues from it
- [x] Archived chat rows' icons dim (active restores)
- [x] Tests + full app suite green

## Comments

**Implemented on `ticket/wpn-04-command-palette-rows`** (base `f375fcb0`;
implementation `005fb5dc`, review pass on top). What landed:

- Badges: `actionBadge(actionId, keymap, isMac)` (exported pure helper in
  `components/command-palette.tsx`) — `newSession`→new-chat,
  `newProject`→new-project, settings→literal `"mod-,"`, theme→null;
  unparseable/cleared rebinds fall back to the default via `validOrDefault`.
  Rows render `<KbdHint>` after a `flex: 1` label wrapper (badge outside
  `Highlighted`, so it is not a search target).
- Hover: `CommandPaletteStore.hover(ix)` —
  `if (!open || ix === active) return; active = ix; commit()` — wired from
  both row kinds' `onMouseMove` (motion only). The keyboard reveal
  (`scroll_to_item`) moved from a `state.active` effect into the up/down
  key arms so hover never scrolls; wrap/enter-latch/esc semantics are
  byte-identical otherwise (diff-verified) and query-edit/open no longer
  scroll — which also matches the desktop, which only scrolls in the
  key arms.
- Archived muting: `command-chat-row-archived` on ChatRow + CSS (harness
  icon 0.4 restored to 0.8 under active; optional web-consistency 55%
  title dim scoped to the title line, mirroring the sidebar).

Verification (web/packages/app):

- `pnpm exec vitest run tests/command-palette.test.ts` → 25/25 (was 18;
  each TDD cycle red→green).
- Adjacent CSS-parsing suites (`sidebar-fade`, `shortcuts`, `archived`,
  `archived-section-rows`) → 103/103.
- `pnpm exec vitest run` (full app suite) → 146 files / 2200 tests, all
  green.
- `pnpm exec tsc --noEmit` → exit 0.

Spot-verified-at-parity items confirmed untouched: `src/lib/` diff is
empty (sort/cap-30); keyDown's wrap/enter-latch/esc lines identical apart
from the reveal calls; sections/empty/footer copy zero diff; action order
still pinned by the unchanged `actionsFor` tests.

Known adjacent debt (pre-existing, NOT introduced here): the keyboard
reveal indexes `listRef.children.item(active)`, but the section separator
is a list child on the web (the desktop nests it inside the row wrapper),
so the reveal targets one slot above for chat rows while the Actions
section renders. Behavior is preserved verbatim by this ticket; fix
belongs in its own ticket (needs a uniform row-identity attribute —
action rows and chat rows carry different `data-rb-row-key` prefixes —
plus a regression test).
