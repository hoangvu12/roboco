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

**Status:** ready-for-agent

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

- [ ] "Show hidden and ignored files" copy
- [ ] Autosave is a 112px RbSelect with the same five options
- [ ] Forget is the icon-only tooltip affordance, busy-disabled
- [ ] Tests + full app suite green
