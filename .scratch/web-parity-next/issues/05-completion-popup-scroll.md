# 05 — Completion popups scroll the active row into view

**What to build:** The desktop scrolls the highlighted row into view on
**every navigation step** — `move_mention`/`move_slash` call
`scroll_to_item(active)` (composer.rs:7066-7074, :7398-7406); the
result-change reset is separate (:7391-7393). The web resets `scrollTop`
only when the results change (mention-popup.tsx:91-97, slash-popup.tsx:43-49)
— with a long list the highlight walks off-screen under the keyboard.
Port the step-scroll, plus the row icon size (16px desktop,
composer.rs:~7168-7173 and ~:7565-7570, vs 14px web — mention-popup.tsx:172-176,
slash-popup.tsx:134-141).

**Blocked by:** None.

**Status:** ready-for-agent

**Research:** `.scratch/web-parity-next/research.md` (mention/slash items).

**Desktop reference (for look-ups only):**
`crates/ui/src/composer.rs:7066-7074` (mention step scroll), `:7398-7406`
(slash step scroll), `:7391-7393` (result-change reset).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer/mention-popup.tsx` | edit | add `useEffect(() => { listRef.current?.children.item(props.active ?? -1)?.scrollIntoView({ block: "nearest" }); }, [props.active, props.results])`, keeping the existing reset; FileIcon `size={16}` |
| `web/packages/app/src/components/composer/slash-popup.tsx` | edit | same effect keyed `[props.active, props.filtered]`; Icon `size={16}` |
| `web/packages/app/src/styles/app.css` | edit | `.composer-completion-row-icon` box 14×14 → 16×16 (app.css:4419-4425) |
| `web/packages/app/tests/composer-reasoning.test.ts` (or a new small suite) | edit | scrollIntoView spy assertions |

## 1. Notes

- Rows are direct children of `.composer-completion-list`, the scroll
  container (app.css:4405-4410, max-height 312) — `children.item` is the
  exact pattern the palette (command-palette.tsx:156-160) and add-space
  palette (add-space-palette.tsx:396-399) already use.
- `listRef` is null in skeleton/empty states — the `?.` chain guards.
- `block: "nearest"` scrolls only the innermost ancestor that needs it —
  the accepted repo-wide substitute for `scroll_to_item`
  (CursorList.tsx:148-152, file-tree-panel.tsx:343). No `behavior: "smooth"`.
- Escape hatch if the bottom-anchored popup ever nudges outer scrollers near
  the viewport edge: manual `list.scrollTop` from `row.offsetTop`.

## 2. Tests

jsdom mount + the standard `Element.prototype.scrollIntoView` stub/spy
(pattern: tests/composer-reasoning.test.ts:73-75): arrow-down past the fold
calls scrollIntoView on the active row; result-change reset still fires;
skeleton state no-ops. CSS-contract check for the 16×16 icon box.

## 3. Acceptance checklist

- [ ] Arrow navigation keeps the highlight visible in both popups
- [ ] Result-change reset behavior preserved
- [ ] Icons 16px (box + prop)
- [ ] Tests + full app suite green
