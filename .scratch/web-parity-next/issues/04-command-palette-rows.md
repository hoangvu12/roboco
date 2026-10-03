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

**Status:** ready-for-agent

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

- [ ] New chat / New project / Settings rows show their shortcut badge
- [ ] Hovering a row moves the highlight; keyboard continues from it
- [ ] Archived chat rows' icons dim (active restores)
- [ ] Tests + full app suite green
