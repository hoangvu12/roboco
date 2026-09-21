# 54 — Mobile-friendly settings pages

**What to build:** Every settings page becomes usable on a 375px phone. The
named offender — Appearance's interface-font row with its fixed 220px + 128px
dropdown triggers that overflow the 343px phone content box and scroll the
page sideways — goes fluid/stacked; the effect-pill cluster stops hanging
right-aligned off a 430px cap; and every centered 360px dialog card fits the
viewport (`min(360px, calc(100vw − 2 · space-lg))`) or renders as 49's phone
sheet where adopted. After this ticket, a phone user can walk every settings
route without horizontal scroll and use every control.

**Blocked by:** 49 (Responsive dialog/drawer primitive — centrally converts
the modal dialogs, supplies the sheet pattern and `useIsPhone()` this
ticket's `RbSelect` arm reuses).

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M6(a)–(d) + "Current
mobile layer inventory" rows (settings phone `app.css:11956-11971`; add-space
phone `:13438-13444`; phone polish settings/account rows `:12967-12980`).

**Desktop reference (for lookups only):** the desktop settings grid is a
single fixed 768-capped column — `crates/ui/src/settings/widgets.rs`
(`page_column`, `:17-27`) and `crates/ui/src/settings/appearance.rs`
(`:2327-2347`, `:2397-2417` — the 220/128 triggers' parity numbers). **The
desktop has no phone analog** (its layout is width-stable, nothing to adapt —
M6(b)); the phone rules below are the research's mobile-native target. The
desktop parity numbers are what the ≥769px rules keep.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/styles/app.css` | edit | the settings phone block (`:11956-11971`) + new ≤768 rules: `.settings-font-row` / `.settings-font-controls` / `.settings-select-trigger` / `.size-trigger` fluid, `.settings-effect-choices` full-width left, `.dialog-card` `min()` width (the backstop, §2.4) |
| `web/packages/app/src/components/base/select.tsx` | edit | `RbSelect`'s phone arm — the popup renders in 49's bottom-sheet form at ≤768 (trigger unchanged), so the font/size selects convert at once (§2.4; 49's explicit deferral: "`RbSelect` is not `PickerCard`; it needs its own phone arm" → later, i.e. here) |

The modal dialog sites (`chat-menu.tsx:290`/`:359`, `space-filter.tsx:669`/
`:728`, `settings-appearance.tsx:662`/`:870`) need NO edits here — 49
converts them centrally through `components/ui/Dialog.tsx` (49's file table:
"`Dialog` renders `RbResponsiveDialog` instead of `RbDialog` — all five
modal dialogs convert at once"). The theme-family popover is likewise 49's
(`PickerCard` branch — 49's M8 table: "also closes the M6 'theme select
popover' row").

No state or logic changes beyond the select arm. No new tests (CSS +
component arm — see §3).

---

## 1. Context a fresh session needs

- The settings shell itself is sound on phone (M6(a)): the nav is the sidebar
  drawer's content (`components/settings-nav.tsx:58-68` — the sidebar column
  swaps to the settings nav on `/settings/*`); `settings-layout.tsx:10-16`
  renders only the scrolling outlet; `.settings-scroll` is `overflow-y: auto`
  (`app.css:9424-9429`); the phone block adjusts page padding and
  stacks/wraps rows (`app.css:11956-11971`: `.settings-page` padding
  20/16/48 at `:11960-11962`; `.settings-option-row` column at
  `:11964-11966`; `.settings-row` wrap at `:11968-11970`). The breakage is
  fixed-width furniture inside the pages.
- The named offender (M6(a) item 1): `.settings-font-row`
  (`app.css:10412-10417`) keeps `justify-content: space-between; gap: 24px`,
  its trailing cluster `.settings-font-controls` is `flex: none`
  (`app.css:10435-10440`), and the two dropdown triggers are hard widths:
  `.settings-select-trigger { width: 220px }` (`app.css:10444-10459`) and
  `.size-trigger { width: 128px }` (`app.css:10465-10467`). 220 + 8 + 128 +
  24 gap = 380px of non-shrinking content inside `.settings-page`'s phone
  content box (375 − 2×16 padding = 343px) → horizontal overflow; because
  `overflow-y: auto` on `.settings-scroll` computes `overflow-x` to auto,
  the page scrolls sideways.
- The effect pills (item 2): `.settings-effect-choices { max-width: 430px;
  margin-left: 10px; justify-content: flex-end }` (`app.css:10096-10104`)
  with `.settings-row { flex-wrap: wrap }` at phone (`:11968-11970`) —
  wraps below the label, but a 430px-capped, right-aligned cluster on a
  343px row overflows until it wraps and then hangs left-of-nothing.
- Root cause (M6(c)): tickets 28/29 ported the desktop's fixed furniture
  verbatim (the widths are parity numbers) and the phone block
  (`app.css:11956-11971`) only covered the generic row shapes, not the
  appearance page's fixed clusters.
- The appearance page's pickers: the theme-family selector is a `PickerCard`
  with a 218×34 trigger (`app.css:10333`) and a 260px menu
  (`routes/settings-appearance.tsx:374-395`); the font/size selectors are
  `RbSelect`s with the same fixed triggers
  (`settings-appearance.tsx:500-563`).
- The dialog furniture: `.dialog-card { width: 360px }`
  (`app.css:4830-4841`) inside `.modal-backdrop { padding:
  var(--rb-space-lg) }` (`app.css:4744-4753`); only `.add-space-card` has a
  phone override today (`app.css:13438-13444`).
- **Decision-5 amendment (user directive, 2026-09-19):** spec decision 5
  (`../../spec.md:29-31`) declared phone widths out of scope; this ticket
  amends that for the settings pages — phone work is in scope, and desktop
  (≥769px) behavior is untouched (the fixed widths ARE the desktop parity).
- What 49 provides by the time this runs: `RbResponsiveDialog` +
  `state/media.ts` (`useIsPhone()`); the central `components/ui/Dialog.tsx`
  conversion (every modal dialog site — the audit's item 4 — becomes a
  phone sheet, removing the 360px overflow by construction); and the
  `PickerCard` phone branch (the theme-family popover, audit item 3). What
  49 explicitly does NOT cover and defers to THIS ticket: the font/size
  `RbSelect` menus (`settings-appearance.tsx:500-563`) — "`RbSelect` is not
  `PickerCard`; it needs its own phone arm" — §2.4. The chat context menu
  (`chat-menu.tsx:68`, `RbContextMenu`) is also left floating by 49
  ("later, 52/54 per the wave plan") — not this ticket's section; leave it
  and record in Comments.

## 2. Spec

### 2.1 The audit (research M6(a), verbatim — every culprit + file:line)

1. **Appearance — interface font row (the named offender).**
   `.settings-font-row` (`app.css:10412-10417`) keeps `justify-content:
   space-between; gap: 24px`, its trailing cluster `.settings-font-controls`
   is `flex: none` (`app.css:10435-10440`), and the two dropdown triggers
   are hard widths: `.settings-select-trigger { width: 220px }`
   (`app.css:10444-10459`) and `.size-trigger { width: 128px }`
   (`app.css:10465-10467`). 220 + 8 + 128 + 24 gap = 380px of non-shrinking
   content inside `.settings-page`'s phone content box (375 − 2×16 padding
   = 343px, `app.css:11960-11962`) → horizontal overflow; because
   `overflow-y: auto` on `.settings-scroll` computes `overflow-x` to auto,
   the page scrolls sideways.
2. **Appearance — effect pills.** `.settings-effect-choices { max-width:
   430px; margin-left: 10px; justify-content: flex-end }` (`app.css:10096-
   10104`) with `.settings-row { flex-wrap: wrap }` at phone
   (`app.css:11968-11970`) — wraps below the label, but a 430px-capped,
   right-aligned cluster on a 343px row overflows until it wraps and then
   hangs left-of-nothing (the row's own alignment breaks visually).
3. **Appearance — theme select row.** The 218px-wide trigger
   (`theme-select-trigger`, ticket 28 Comments: "the custom 218×34
   PickerCard popover") + `PickerCard width 260` menu
   (`settings-appearance.tsx:374-395`) — the trigger fits, the popover
   clamps (8px margin, `positioning.ts:76-86`) but loses rows to clipping
   on narrow screens; acceptable, listed for completeness.
4. **All modal dialogs.** `.dialog-card { width: 360px }` (`app.css:4830-
   4841`) inside `.modal-backdrop { padding: var(--rb-space-lg) }`
   (`app.css:4744-4753`): 360 > 375 − 32 = 343 → the rename/delete dialogs
   (`chat-menu.tsx:290`, `:359`; `space-filter.tsx:669`, `:728`) and the
   theme import/review dialogs (`settings-appearance.tsx:662`, `:870`)
   overflow horizontally on ≤392px viewports. No phone override exists for
   `.dialog-card` (only `.add-space-card` has one, `app.css:13438-13444`).
5. **Shortcuts.** `.shortcuts-header` `gap: 24px` (`app.css:11584-11589`)
   and the min-72px segmented options (`app.css:11660-11671`) fit; the kbd
   chips ride `.settings-row` wrap. Listed as OK.
6. **Files / pills / accounts** — wrap correctly via the phone block
   (`app.css:12967-12980`, `.settings-account-row` wrap) and
   `.pill-row { flex-wrap: wrap }` (`app.css:11534-11539`). OK.

### 2.2 The phone rules (research M6(b), verbatim)

Mobile-native (the desktop settings grid is a single fixed 768-capped
column — `settings.rs` layout is width-stable, nothing to adapt):

- Every `flex: none` trailing cluster in a settings row gets a phone rule:
  `width: 100%; justify-content: flex-start;` under `@media (max-width:
  768px)`, and the fixed-width triggers become fluid:
  `.settings-select-trigger { width: 100% }`, `.size-trigger { width: 50% }`
  (or wrap the two triggers, `flex-wrap: wrap`).
- `.dialog-card` phone: `width: min(360px, calc(100vw - 2 *
  var(--rb-space-lg)))` — or adopt the M8 responsive pattern so the dialogs
  become drawers at phone, which removes the overflow by construction.
- `.settings-effect-choices` phone: `max-width: none; justify-content:
  flex-start; margin-left: 0;`.

**Layout table (the rules to add, ≤768 only):**

| selector | rule | source |
| --- | --- | --- |
| `.settings-font-controls` (every `flex: none` trailing cluster in a settings row) | `width: 100%; justify-content: flex-start;` | M6(b) |
| `.settings-select-trigger` | `width: 100%` | M6(b) |
| `.size-trigger` | `width: 50%` (or wrap the pair: `flex-wrap: wrap` on the cluster) | M6(b) |
| `.dialog-card` | `width: min(360px, calc(100vw - 2 * var(--rb-space-lg)))` | M6(b) |
| `.settings-effect-choices` | `max-width: none; justify-content: flex-start; margin-left: 0;` | M6(b) |

**States:** none — static media-block rules.

**Interactions:** unchanged — the triggers/popovers open as at desktop
(their phone form is 49's, cross-ref §2.4).

**Motion:** none.

**Text:** none (geometry only; the pages' strings are tickets 28/29's).

**Data:** none.

### 2.3 Scope of the audit walk

Every settings route the research's audit lists (plus ticket 29's sections,
same furniture): Appearance (items 1–4), Shortcuts (item 5 — OK, verify
only), Files / pills / accounts (item 6 — OK, verify only). The phone walk
in §6 covers each route at 375×667.

### 2.4 Dialogs and popovers — ownership split (cross-ref 49)

- **Modal dialogs (audit item 4): closed by 49 by construction** — 49's
  central `components/ui/Dialog.tsx` conversion renders every dialog site
  (`chat-menu.tsx:290`/`:359` "Rename session"/"Delete session?",
  `space-filter.tsx:669`/`:728` "Rename project"/"Remove project?",
  `settings-appearance.tsx:662` "Add a theme" / `:870` "Theme mapping") as
  the phone bottom sheet (49: "this also removes the M6 'fixed 360px
  dialog card overflows ≤392px viewports' row by construction for these
  sites"). This ticket verifies that in the §6 walk — no per-site edits.
- **The `.dialog-card` `min()` phone rule still lands here as the
  backstop** for any dialog card that does not route through the primitive
  (M6(b)'s first option): `width: min(360px, calc(100vw - 2 *
  var(--rb-space-lg)))`. Where 49's sheet form is active it supersedes the
  rule; do not do both for one site.
- **The font/size `RbSelect` phone arm is THIS ticket's** (49's explicit
  deferral): at ≤768 the `RbSelect` popup renders in 49's bottom-sheet form
  (reuse 49's `.rb-drawer-card` CSS and Drawer mounting — the same pattern
  49 landed for `PickerCard`, applied in ONE place,
  `components/base/select.tsx:44-90`), the trigger unchanged, so the
  interface-font and size selects convert at once.
- **The theme-family popover (audit item 3)** is 49's `PickerCard` branch —
  verify usable in the walk; no code here.

## 3. Pure logic to port

None. The fixes are CSS rules inside the existing `@media (max-width:
768px)` block(s) plus the `RbSelect` phone arm (a render branch reusing
49's landed sheet, not new logic).

- New web unit tests: none — no function changes, and the component trees
  render nothing in the node environment (the `base-popover.test.ts`
  header's note, cited by 50 §3); the 375px walkthrough (§6) is the check.
- Desktop tests: none apply — the desktop settings grid is width-stable
  (`settings.rs`); there is nothing to port (M6(b)).

## 4. Gaps this ticket closes

From research M6(d), verbatim:

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| font row triggers | geometry | fluid/stacked ≤768 | 220px + 128px fixed (`app.css:10444-10467`) | phone widths/wrap |
| effect pill cluster | geometry | full-width, left-aligned | 430px cap, right-aligned (`app.css:10096-10104`) | phone override |
| dialog cards | geometry | ≤ viewport − 32 | fixed 360px (`app.css:4830-4841`) | `min()` width or M8 drawer |
| theme select popover | behavior | usable | 260px popover (`settings-appearance.tsx:381`) | M8 drawer at phone |

(Rows 3 and 4 close across 49 + this ticket: 49's central `ui/Dialog.tsx`
conversion removes the dialog-card overflow by construction and its
`PickerCard` branch makes the theme select usable — this ticket lands the
`min()` backstop and the `RbSelect` phone arm, and verifies both in the
walkthrough; cross-ref §2.4.)

From the research's consolidated gap table, the M6 rows (font row, dialog
cards, effect pills) — same content.

## 5. Do not

- Do not change desktop (≥769px) settings geometry: the 220/128 triggers
  (`app.css:10444-10467`), the 430px cluster (`:10096-10104`), the 360px
  card (`:4830-4841`), and the 218px theme-select trigger (`:10333`) are
  parity numbers (`appearance.rs:2327-2347`, `:2397-2417`) — keep them at
  desktop. Every new rule lives inside `@media (max-width: 768px)`.
- Ticket 49 owns the responsive primitive and the `PickerCard` phone branch
  — do not add width branches to popover components here; the `RbSelect`
  arm reuses 49's landed sheet pattern (`state/media.ts` +
  `.rb-drawer-card`), it does not invent a second one. The chat context
  menu (`chat-menu.tsx:68`) stays floating at phone (49's deferral; not
  this ticket's section) — record in Comments.
- Tickets 28/29 own the settings pages' content and desktop styling — this
  ticket adds phone rules only; do not restyle rows, cards, or triggers
  beyond the spec table.
- Do not re-add INVENTED UI: no phone-only settings chrome, no collapsible
  sections, no hamburger nav.
- The audit's "OK" items (5 Shortcuts, 6 Files/pills/accounts) — leave them
  alone; do not "fix" what fits.
- Do not touch `.add-space-card`'s existing phone override
  (`app.css:13438-13444`) — it already works.

## 6. Acceptance

- [ ] appearance settings fully usable at 375px without horizontal scroll:
      the interface-font row's triggers are fluid/stacked (the 220+128 pair
      no longer overflows the 343px content box), and the effect pills sit
      full-width, left-aligned, below the label.
- [ ] Every settings route the audit lists (Appearance, Shortcuts, Files,
      Accounts — plus Devices/Agents/Notifications/Archived from ticket
      29's set) walks at 375×667 with no sideways scroll and every control
      reachable.
- [ ] The rename/delete dialogs (chat + project) and the theme
      import/review dialogs fit within viewport − 32 at 375px (the `min()`
      rule), or render as 49's phone sheet where adopted.
- [ ] The theme-family, font, and size pickers open usable phone forms at
      375px (49's `PickerCard` branch for the theme menu; this ticket's
      `RbSelect` arm for font/size).
- [ ] At ≥769px nothing changes.
- [ ] Screenshot: `use-browser` at 375×667 against `web_smoke`, states:
      "Appearance, interface font row", "Appearance, effect row", "rename
      chat dialog open", "theme import dialog open"; plus a ≥769px capture
      (desktop pair) unchanged.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists (the `min()` uses
      `var(--rb-space-lg)`; the fluid widths are percentages).

## Comments

### Implementer note (2026-09-19)

**Landed** (branch `wp2r2/54-mobile-settings-friendly`, one commit):

- `web/packages/app/src/styles/app.css` — three additions, every rule inside
  `@media (max-width: 768px)` (≥769 untouched, the 220/128/430/360/218
  literals above them are the parity numbers and stay):
  - The settings phone block (after the existing
    `.settings-page`/`.settings-option-row`/`.settings-row` rules): the
    §2.2 table verbatim — `.settings-font-row { flex-wrap: wrap }` (the
    file-table's named rule; without it the M6(b) cluster rule would crush
    the `flex: 1` label instead of stacking under it),
    `.settings-font-controls { width: 100%; justify-content: flex-start }`,
    `.settings-select-trigger { width: 100% }`, `.size-trigger { width:
    50% }` (the pair rides flex-shrink at ~2:1 — the desktop's own
    220:128 ratio), `.settings-effect-choices { width: 100%; max-width:
    none; justify-content: flex-start; margin-left: 0 }` (`width: 100%`
    from M6(b)'s general "every `flex: none` trailing cluster" rule —
    acceptance's "full-width, left-aligned, below the label").
  - The `.dialog-card` `min()` backstop, placed in the dialog-primitives
    region right after the `.dialog-card` base rule.
  - The RbSelect sheet's menu rules (see below), placed after the
    `.settings-select-*` family.
- `web/packages/app/src/components/base/select.tsx` — the `RbSelect` phone
  arm, 49's explicit deferral: at `useIsPhone()` the popup opens as 49's
  bottom sheet. Mechanics: `RbSelect` already shadows the open state
  (Select is controlled through it), so at phone it provides
  `RbSelectPhoneContext` (module-internal — the open flag plus the SAME
  `change` path Select's own `onOpenChange` takes); `RbSelectPositioner`'s
  phone arm renders `RbDrawerSheet` (49's `Drawer.Root/Portal/Backdrop/
  Viewport/Popup` + `.rb-drawer-card`) with the `Select.Positioner` tree
  inside, pinned to the sheet's flow via the consumer-style per-key merge
  (`position: static`, inset keys `auto` — Base UI merges consumer style
  after its computed coordinates). The trigger is unchanged (its press
  toggles the Select, whose state drives the sheet); the popup, items,
  keyboard model, and every Base UI dismissal path (item pick, Escape,
  outside press) stay the Select's own; sheet swipe/scrim route through
  `drawerOnOpenChange` into the one `change` path. Supporting CSS: inside
  `.rb-drawer-card` the menu goes `width: 100%; max-height: none; overflow:
  visible` (the 220/128 menu widths and the font menu's 320px scroll cap
  are the floating form's parity numbers; the sheet frame owns the height
  clamp + scrolling) and the floating form's `rb-menu-in`/`rb-menu-out`
  yield to the sheet's `rb-dialog-in` (the sheet is the motion source at
  phone, same as every 49 sheet). The desktop arm is byte-identical to the
  pre-arm tree; the context Provider renders only at ≤768. No consumer
  edits — the font and size selects converted with zero changes to
  `settings-appearance.tsx`.

**Deviations (2, both deliberate):**

1. The `.dialog-card` backstop is scoped
   `.dialog-card:not(.rb-drawer-card *)` rather than the table's bare
   `.dialog-card`: a plain global rule would also shrink the 360px card
   INSIDE 49's sheets (`.dialog-card` renders within `.rb-drawer-card` for
   every converted dialog site), restyling 49's landed sheet interiors and
   violating §2.4's own "Where 49's sheet form is active it supersedes the
   rule; do not do both for one site". The `:not()` makes that sentence
   literal in CSS — the backstop only catches cards that do not route
   through the primitive (today: none; it is the safety net for future
   hardcoded ones). No dialog site needed a per-site edit, as specced.
2. The sheet's inner-menu rules above are not in the ticket's table but
   are entailed by "the popup renders in 49's bottom-sheet form" — without
   them the 220px menu would float narrow inside the viewport-wide sheet
   and double-animate against the sheet's entrance.

**Recorded per §1/Do-not:** the chat context menu (`chat-menu.tsx:68`,
`RbContextMenu`) stays floating at phone — 49's deferral to 52/54's wave
plan, and this ticket's §2.4 explicitly leaves it out of scope. The theme
family popover is 49's `PickerCard` phone branch (verified by inspection:
`settings-appearance.tsx:377-415` routes through `PickerCard`, whose phone
arm renders the 260px menu body as the sheet) — no code here, per the file
table. The audit's OK rows (Shortcuts, Files/pills/accounts) were
re-verified by inspection (`.settings-row` wrap covers the shortcut rows;
`.settings-account-row` composes `settings-row`; `.pill-row` wraps at all
widths) and left alone, as were `.add-space-card`'s phone override and the
218px theme-select trigger (fits the 343px content box).

**Verification:** `pnpm -r build` green (5 workspace projects, vite build
clean); `pnpm test` in `packages/app` green — 81 files / 1276 tests
passed. One first-run failure in `registry.test.ts`'s
`reconnectBackoffDoublesAndResetsAfterALongLivedConnection` (a ±15ms
backoff-timing bound — "expected 65 to be less than 65") reproduced
nothing: passed on the clean tree, 3/3 in isolation with these changes,
and green in the full re-run — a pre-existing timing flake under suite
load, in files this ticket does not touch.

**Screenshot pairs waived** (operator instruction for this session — no
dev server / browser / `web_smoke` runs): the §6 acceptance walk was
performed by code inspection against the audit's file:line inventory —
each M6(a) item maps to a landed rule above, item 4's dialog sites map to
49's sheet conversion (plus the `min()` backstop), and items 5/6 map to
the existing phone block. A human pass with the §6 capture list
(Appearance font row / effect row, rename + theme import dialogs, ≥769
desktop pair) is the remaining unexecuted step.
