# 49 — Responsive dialog/drawer primitive

**What to build:** Today every modal dialog and picker menu renders as a centered
or floating desktop card at every width — on a phone they clamp, clip, or
overflow, and the pickers the user must reach while typing float above the
composer. After this ticket, at ≤768px every adopted dialog and PickerCard menu
opens as a bottom sheet that slides up from the bottom edge, dismisses by
swipe-down or Escape, and can never overflow a 375px viewport; at ≥769px every
one of them renders exactly as before. One shared media hook (`state/media.ts`)
replaces the app's three ad-hoc width checks. This ticket also supplies the
phone form that resolves the "project selector shows on mobile but not on
desktop" asymmetry (M9): the chips' drawer treatment lands here; ticket 53
un-hides the selector row itself.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

**Research:** `../research-2026-09-19/mobile-layer.md` M8 (responsive
dialog/drawer pattern) + M9 (project-selector asymmetry) + the "Current mobile
layer inventory" section + the "Recommended mobile layout system" section
(items 5 and 6).

**Spec amendment (user directive 2026-09-19):** spec decision 5 ("phone widths
are out of scope", `.scratch/web-parity/spec.md:29-31`) is amended for this
ticket — phone-layer work (≤768px) is in scope here; decisions 1–4 still govern
every ≥769px surface unchanged.

**Desktop reference (for lookups only):** The ≥769px form is already ported and
stays fixed: `crates/ui/src/popover.rs::modal` (657), `::modal_glass` (684),
`::dialog_card` (978-990) and the anchored-menu family (`popover.rs:420-638`) —
web ports `components/base/dialog.tsx::RbDialog` (67), `::RbDialogGlass` (132),
`components/ui/Dialog.tsx::Dialog` (29), `components/ui/PickerCard.tsx` (88),
`components/base/popover.tsx::RbPopover` (150). The desktop has NO phone analog
for the sheet form — its minimum window geometry (`CHAT_PANEL_MIN` 300 +
`SIDEBAR_MIN` 224) makes a 375px window impossible — so the bottom-sheet spec
below is mobile-native, from the research's M8 §(b).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/state/media.ts` | **new** | `useMediaQuery`, `useIsPhone`, `useIsDesktop`, `PHONE_QUERY`, `DESKTOP_QUERY` |
| `web/packages/app/src/components/base/responsive-surface.tsx` | **new** | `RbResponsiveDialog` — the Drawer ≤768 / Dialog ≥769 surface + the shared sheet body |
| `web/packages/app/src/components/ui/Dialog.tsx` | edit | `Dialog` renders `RbResponsiveDialog` instead of `RbDialog` — all five modal dialogs convert at once |
| `web/packages/app/src/components/base/dialog.tsx` | edit | `RbDialogGlass` gains the phone arm (bottom sheet); `RbDialog` itself stays the desktop-only primitive, byte-unchanged |
| `web/packages/app/src/components/ui/PickerCard.tsx` | edit | phone branch: the card body renders inside the Drawer sheet, the trigger unchanged |
| `web/packages/app/src/components/transcript.tsx` | edit | delete the local `useMediaQuery` (`:1299-1311`), import from `state/media.ts`; `DESKTOP_QUERY` (`:100`) moves with it |
| `web/packages/app/src/components/app-shell.tsx` | edit | `onToggleSidebar` (`:197`) consumes `useIsPhone()` instead of the one-shot `window.matchMedia` |
| `web/packages/app/src/routes/chat-page.tsx` | edit | `phone` (`:440`) = `useIsPhone()` instead of the `viewport <= PHONE_MAX_WIDTH` innerWidth compare |
| `web/packages/app/src/styles/app.css` | edit | **new** `.rb-drawer-card` phone sheet (+ its reduced-motion snap) |
| `web/packages/app/tests/media.test.ts` | **new** | breakpoint primitive behavior (§3) |

---

## 1. Context a fresh session needs

- **Spec amendment (decision 5).** The wave-1 spec froze the phone layer
  ("stays as is; do not break it, do not extend it", `spec.md:29-31`). The
  user directive of 2026-09-19 amends that for this ticket: phone work is now
  in scope. Everything this ticket changes must be a no-op at ≥769px.
- **Package facts (verified at this HEAD).** The dependency is
  **`@base-ui/react` 1.8.0** (`web/packages/app/package.json:15`) — not
  `@baseuijs`, not `@baseui-components`. React Aria is NOT a dependency.
  That version **ships a complete Drawer component**:
  `node_modules/@base-ui/react/drawer/` exports `Root, Provider,
  DrawerIndent, DrawerIndentBackground, Trigger, Portal, Popup, SwipeArea,
  Content, Backdrop, Viewport, Title, Description, Close,
  VirtualKeyboardProvider` (`drawer/index.d.ts`), re-exported from the
  package barrel (`node_modules/@base-ui/react/index.d.ts:14`: `export * from
  "./drawer/index.js"`). `DrawerRoot` supports `open`, `modal: true |
  'trap-focus' | false`, `swipeDirection: 'up' | 'down' | 'left' | 'right'`
  (default `'down'`), `snapPoints`, `onOpenChangeComplete`,
  `actionsRef {unmount, close}` (`drawer/root/DrawerRoot.d.ts`) — the same
  control surface `RbDialog` already uses. It also ships
  `unstable-use-media-query` (subpath
  `@base-ui/react/unstable-use-media-query`, verified present) with SSR
  options. **No component in `src/` uses either today** (grep for
  `@base-ui/react/drawer` → 0 hits).
- **The wrappers that exist, none width-aware.** The repo's overlay wrappers
  are `components/base/dialog.tsx` (`RbDialog` `:67-91`, `RbDialogGlass`
  `:132-153`), `components/base/popover.tsx` (`RbPopover` `:150-213`),
  `components/ui/Dialog.tsx` (`Dialog` mount-while-open shell `:29-44`),
  `components/ui/PickerCard.tsx` (`:88-119`). **None of them branches on
  width today** (grep `isMobile|useMediaQuery|matchMedia` over
  `components/base` + `components/ui` → 0 width hits). The only width-aware
  hook in the app is a local `useMediaQuery` in
  `components/transcript.tsx:1299-1311` (min-width 769, drives `lastRowPad`
  `:564-577`).
- **Current mobile layer inventory rows relevant here (copied from the
  research's inventory, JS branches — 3 total, plus the transcript flag):**

  | site | file:line | what it does |
  | --- | --- | --- |
  | sidebar toggle | `app-shell.tsx:196-202` | `matchMedia(max-width: 768px)` → open phone drawer instead of collapsing the column |
  | phone flag | `routes/chat-page.tsx:440-443` | `viewport <= PHONE_MAX_WIDTH` → `dockReduced`, composer never re-anchors |
  | hero gate | `routes/chat-page.tsx:565` | `&& !phone` → hero unmounted at phone |
  | transcript desktop flag | `components/transcript.tsx:100, 564, 577` | local `useMediaQuery(min-width: 769px)` → `lastRowPad` 16 vs clearance-based |

  Relevant CSS block: the core phone layout lives at `app.css:6738-6851`
  (left drawer `6816-6832`, backdrop `6834-6844`, selector strip `6807-6809`).
  The dialogs/popovers have NO phone rules at all — that is the gap this
  ticket closes.
- **The z-ladder is fixed and shared** (`app.css:233-255`, documented tiers):
  titlebar 40, drawers/sheets 50, menus + popovers 55, modals + dialogs 70,
  gate 80; the phone sidebar sits at 30, deliberately under the titlebar
  ("its cluster must stay clickable to close what it opened",
  `app.css:247-248`). The sheet built here rides the modal tier (70) via the
  existing `.modal-backdrop` (`app.css:4744-4753`) — no new tiers.
- **M9, folded in (the research's conclusion).** "The project selector dialog
  shows on mobile but not on desktop" is **pure CSS, inverted in exactly one
  place**: `.dock-target-selectors { display: none }` at ≤768
  (`app.css:6807-6809`) removes the canvas's device+project chip row — the
  desktop's ONLY on-canvas project selector (mounted at all widths by
  `composer.tsx:2670-2678`) — from the phone, leaving only the footer's
  draft-row `ProjectChip` (`composer-footer.tsx:141-142`, rendered when an
  established chat is uncommitted) with its `role="dialog"` 280px popover
  (`composer-footer.tsx:356-368`). There is **no JS width branch** in any of
  the three files the hunch named (`composer/new-thread-selectors.tsx`,
  `surface-picker.tsx`, `add-space-palette.tsx`). Resolved by construction:
  **53 (M5) deletes the `display: none` and re-shows the row; this ticket
  (M8) gives the chips the drawer-at-phone form** — the canvas chips import
  `DeviceChip`/`ProjectChip` from `composer-footer`
  (`composer/new-thread-selectors.tsx:10`), so they convert automatically
  once visible. The footer chip keeps its desktop popover contract
  (`pickers.rs:2012-2124`) at ≥769. If the asymmetry somehow persists after
  both land, the next suspect is the popover's `anchorAboveEnd` placement
  (`composer-footer.tsx:360` + `positioning.ts:119`) — which this ticket's
  sheet form also removes at phone.
- **Vocabulary** (`CONTEXT.md`): chat (not session/thread), harness (not
  provider), engine, space; *Session* only for the pairing credential.
  User-visible strings stay verbatim — the dialogs already carry their copy
  ("Rename session", "Delete session?" …), and this ticket changes no copy.

---

## 2. Spec

Copied from the research's M8 §(b) and its implementation note, verbatim; the
adoption tables at §2.5 are the research's own tables with an added
"adopts-in" column.

### 2.1 `state/media.ts` — the one shared media hook

Standardize on ONE media hook and ONE responsive surface:

1. `state/media.ts` (new): promote `transcript.tsx:1299-1311`'s hook; export
   `useMediaQuery`, `useIsPhone()` (`(max-width: 768px)` — the stylesheet's
   breakpoint, `PHONE_MAX_WIDTH` `state/layout.ts:43`), and `useIsDesktop()`.
   Replace the raw one-shot `window.matchMedia` in `app-shell.tsx:197` and
   the `viewport <= PHONE_MAX_WIDTH` innerWidth compare in
   `chat-page.tsx:440` (innerWidth and the CSS media query can disagree by
   rounding — the dead-band comment at `app-shell.tsx:193-196` documents
   exactly that class of bug). Adopting
   `@base-ui/react/unstable-use-media-query` is the alternative; it works
   (verified above) but adds an unstable-named subpath import where the repo
   already has the 12-line hook.

**Exports**

| Export | Value / behavior | Source |
| --- | --- | --- |
| `PHONE_QUERY` | `` `(max-width: ${PHONE_MAX_WIDTH}px)` `` = `(max-width: 768px)` | research M8 §(b)1; `state/layout.ts:43` |
| `DESKTOP_QUERY` | `(min-width: 769px)` — moves here from `transcript.tsx:100` | research inventory (transcript flag) |
| `useMediaQuery(query)` | the exact hook body of `transcript.tsx:1299-1311` (matchMedia subscribe + `useSyncExternalStore`, server snapshot `false`) | research M8 §(b)1 |
| `useIsPhone()` | `useMediaQuery(PHONE_QUERY)` | research M8 §(b)1 |
| `useIsDesktop()` | `useMediaQuery(DESKTOP_QUERY)` — the two queries are exact complements | research M8 §(b)1 |

**Call-site replacements (this ticket's edits):**

| Call site | Today | After |
| --- | --- | --- |
| `app-shell.tsx:197` (inside `onToggleSidebar`) | one-shot `window.matchMedia(...)` per click | `useIsPhone()` read at render, captured in the callback |
| `chat-page.tsx:440` | `viewport <= PHONE_MAX_WIDTH` (innerWidth compare) | `const phone = useIsPhone()` |
| `transcript.tsx:100,564` | local `useMediaQuery` + `DESKTOP_QUERY` | import both from `state/media.ts` |

**Parallel-ticket note:** ticket 50 consumes `useIsPhone()` too (the
`sidebarForGeometry` branch in `AppShell`). 50 is not blocked by this ticket:
if 50 lands first it creates `state/media.ts` from this identical §2.1 spec —
do not fork it, converge on this file and this export set.

### 2.2 `RbResponsiveDialog` — the responsive surface

`components/base/responsive-surface.tsx` (new): `RbResponsiveDialog` — renders
`RbDialog`'s exact tree at `useIsDesktop()`, and at phone renders Base UI's
Drawer with the same children.

**API** — props are `RbDialogProps` verbatim (`base/dialog.tsx:44-64`):
`open`, `onOpenChange`, `onOpenChangeComplete`, `actionsRef`, `ariaLabel`,
`initialFocus`, `finalFocus`, `overlaySource`, `cardClassName`, `children`.
(`modal` and `disablePointerDismissal` are not props — `RbDialog` hardcodes
them in its tree at `base/dialog.tsx:75-76`; the phone arm mirrors that, see
Interactions.) No new props; the wrapper is a drop-in for `RbDialog`.

**Tree at ≥769px (`useIsDesktop()`):** `RbDialog`'s exact tree
(`base/dialog.tsx:70-89`) — `Dialog.Root {open, onOpenChange,
onOpenChangeComplete, actionsRef, modal, disablePointerDismissal}` →
`Dialog.Portal` → `Dialog.Backdrop className="modal-backdrop"` +
`Dialog.Popup className={"modal-card rb-dialog-card " + cardClassName}`.
Byte-identical to ticket 09's contract; do not modify `RbDialog` itself.

**Tree at ≤768px (phone)** — from the research, verbatim:
`Drawer.Root {open, onOpenChange, modal}` + `Drawer.Portal` + `Backdrop
className="modal-backdrop"` + `Popup className="modal-card rb-drawer-card"`,
`swipeDirection="down"` (dismiss toward the bottom edge), **no snap points
(the dialogs are small)**.

**Layout (the phone sheet CSS, verbatim):**

| property | value | source |
| --- | --- | --- |
| `.rb-drawer-card` position | `fixed; left: 0; right: 0; bottom: 0` | research M8 §(b)2 |
| radius | `16px 16px 0 0` | research M8 §(b)2 |
| max-height | `calc(100dvh - var(--rb-space-lg))` | research M8 §(b)2 |
| guard | the rule lives under `@media (max-width: 768px)` | research M8 §(b)2 |
| card fill | `.modal-card` / `.dialog-card` internals unchanged — the sheet replaces placement, not the card's inner layout | research M8 §(b)2 |
| z-index | via `.modal-backdrop` = `var(--rb-z-modal)` (70) | `app.css:4744-4753`, ladder `app.css:233-255` |

**States**

| state | condition | what changes |
| --- | --- | --- |
| open, ≥769 | `useIsDesktop()` | the exact `RbDialog` centered modal (360px `.dialog-card` etc.) |
| open, ≤768 | phone | the bottom sheet; focus trapped, document page scroll locked, pointer interactions outside disabled (`DrawerRoot modal: true`, `drawer/root/DrawerRoot.d.ts` — the same semantics Dialog provides) |
| swipe-dismiss | drag down on the sheet | `swipeDirection="down"` → `onOpenChange(false)`; the sheet tracks the finger |
| reduced motion | `prefers-reduced-motion: reduce` | the entrance snaps (§Motion) |

**Interactions**

- Swipe down dismisses (the primitive's built-in gesture; no snap points).
- Escape closes (Base UI built-in for modal overlays).
- Backdrop press: match each wrapper's desktop contract — carry
  `disablePointerDismissal` through on the dialog-sheet form so Escape/Cancel
  close it exactly as `RbDialog` does (`base/dialog.tsx:76`); `RbDialogGlass`
  does NOT pass it (the scrim press closes, `add-space-palette.tsx:141-144`)
  and its phone arm must not start.
- Carry `useOverlayKeyboardSource(overlaySource, open)` into the phone arms
  (`base/dialog.tsx:68`, `base/popover.tsx:152`) so session-nav shortcuts
  stay quiet under the sheet, same as under the floating forms (the
  `overlayKeyboard` contract tested in `tests/base-popover.test.ts`).

**Motion** (from the research, verbatim): "The entrance can reuse
`rb-dialog-in` (`app.css:4818-4827`); reduced motion snaps (`app.css:4811-4816`
pattern)."

| what | trigger | spec | from → to | reduced motion |
| --- | --- | --- | --- | --- |
| sheet entrance | open flips at phone | reuse `rb-dialog-in` | `opacity 0 → 1`, `translateY(2px) → none` | snap (`animation: none`, the `:4811-4816` pattern) |
| swipe-dismiss | user drag | gesture-driven, tracks the pointer | — (not an animation) | n/a |

**Data** — none: pure presentation over the caller's existing
`open`/`onOpenChange` state. Every adopted site keeps its own store calls
exactly as today.

### 2.3 `RbDialogGlass` phone arm

From the research, verbatim: "`RbDialogGlass` gets the same option (the
add-space palette as a bottom sheet at phone is the natural mobile form of a
680px palette; its existing phone override `app.css:13438-13444` already goes
full-width)."

- At phone render the glass tree through the Drawer: `Drawer.Root {open,
  onOpenChange, modal}` → `Drawer.Portal` → `Backdrop
  className="modal-glass-backdrop"` → `Popup className="modal-card
  rb-dialog-card rb-drawer-card"`. The `[data-closed]` exit CSS keeps working
  (Base UI's animation-aware unmount waits it out, `base/dialog.tsx:128-131`).
- The add-space card's own phone cap (`width: 100%; max-width: 680px`,
  `app.css:13440-13442`) applies inside the sheet — no change to it.
- Do NOT pass `disablePointerDismissal` here (the glass contract's scrim
  press closes, as today).

### 2.4 `PickerCard` phone branch

From the research's implementation note, verbatim: "`PickerCard` composes
`RbPopover` (`components/ui/PickerCard.tsx:100-117`); the responsive branch
belongs in `PickerCard` (one place) — at phone render the card body inside the
Drawer sheet with the trigger unchanged, so every consumer converts at once."

- At `useIsPhone()`: render `props.trigger` unchanged (same element, same
  classes; its press opens the sheet instead of the floating card), and the
  card body (`props.children`) inside the Drawer sheet (§2.2's tree,
  `swipeDirection="down"`, no snap points).
- `open`/`onOpenChange` state is unchanged — the trigger's pressed/expanded
  styling and `aria` follow the same open flag.
- `placement`, `gap`, and `width` are ignored at phone (the sheet spans the
  viewport; the research's M6 width concerns do not apply to the sheet form).
- Keep `useOverlayKeyboardSource(props.overlaySource, props.open)` on the
  phone arm.
- At `useIsDesktop()`: the existing `RbPopoverTrigger` + `RbPopover` tree,
  byte-unchanged (ticket 09/10's contract).

### 2.5 Adoption tables (copied from the research; "adopts" column added)

**Dialog sites — every dialog, today centered with no phone treatment:**

| site | file:line | today (all widths) | adopts in |
| --- | --- | --- | --- |
| Rename chat | `components/chat-menu.tsx:290` (`Dialog`) | centered modal, 360px card | **this ticket** (via `ui/Dialog.tsx`) |
| Delete chat | `components/chat-menu.tsx:359` | centered modal | **this ticket** (via `ui/Dialog.tsx`) |
| Rename space | `components/space-filter.tsx:669` | centered modal | **this ticket** (via `ui/Dialog.tsx`) |
| Delete space | `components/space-filter.tsx:728` | centered modal | **this ticket** (via `ui/Dialog.tsx`) |
| Theme import | `routes/settings-appearance.tsx:662` | centered modal | **this ticket** (via `ui/Dialog.tsx`) |
| Theme review | `routes/settings-appearance.tsx:870` | centered modal | **this ticket** (via `ui/Dialog.tsx`) |
| Add-space palette | `components/add-space-palette.tsx:146` (`RbDialogGlass`) | centered, 680px card | **this ticket** (via the `RbDialogGlass` phone arm) |

Mechanism: `components/ui/Dialog.tsx::Dialog` is the one shell all five
modal dialogs render through (`:29-44`); switching its internals to
`RbResponsiveDialog` converts all five with **no call-site edits**. The
add-space palette converts through the `RbDialogGlass` arm with no call-site
edits. This also removes the M6 "fixed 360px dialog card overflows ≤392px
viewports" row by construction for these seven sites (`.dialog-card` is
360px, `app.css:4830-4841`; the sheet spans the viewport).

**Popover/menu sites that should become drawers at phone (recommended —
these are the surfaces the user must reach while typing; desktop keeps the
popover exactly as ticket 09/10 specced, `popover.rs:420-638`):**

| site | file:line | phone today | adopts in |
| --- | --- | --- | --- |
| Device chip popover (224px) | `composer-footer.tsx:224-252` | floating card, clamp-only | **this ticket** (PickerCard branch) |
| Project chip popover (280px) | `composer-footer.tsx:357-368` | floating card (see M9) | **this ticket** (PickerCard branch) |
| Checkout chip (224px) | `composer-footer.tsx:508-530` | floating card | **this ticket** (PickerCard branch) |
| Ref chip (320px) | `composer-footer.tsx:705-726` | floating card | **this ticket** (PickerCard branch) |
| Canvas device+project row | `composer/new-thread-selectors.tsx:110-130` | hidden (`app.css:6807-6809`, M5) | **this ticket** supplies the chips' phone form (they are the same `DeviceChip`/`ProjectChip`, imported at `:10`); **53** deletes the `display: none` that hides the row |
| Model/harness identity picker (304px) | `composer-pickers.tsx:229-300` | floating card | **this ticket** (PickerCard branch) |
| Spaces menu (listbox) | `space-filter.tsx:170-270` | floating card inside drawer sidebar | **this ticket** (PickerCard branch) |
| Sidebar view menu | `space-filter.tsx:484-528` | floating card | **this ticket** (PickerCard branch) |
| Theme family menus (260px) | `settings-appearance.tsx:374-412` | floating card | **this ticket** (PickerCard branch — also closes the M6 "theme select popover" row) |
| Font/size Selects | `settings-appearance.tsx:500-563` (`RbSelect`) | floating card | later (54) — `RbSelect` is not `PickerCard`; it needs its own phone arm |
| Chat context menu | `chat-menu.tsx:68` (`RbContextMenu`) | pointer-anchored card | later (52/54 per the wave plan) — `RbContextMenu` needs its own arm |
| Right-pane `+` menu (168px) | `right-tab-strip.tsx` (`PLUS_MENU_W` `:41`) | floating card | later (52) — rides the right-pane phone-drawer work |

---

## 3. Pure logic to port

**Breakpoint resolution.** `PHONE_MAX_WIDTH = 768` (`state/layout.ts:43`) is
the stylesheet's boundary: every phone block in `app.css` is
`@media (max-width: 768px)` and every desktop block `(min-width: 769px)`.
`useIsPhone()` resolves `(max-width: 768px)` through `matchMedia`, so JS and
CSS agree by construction — innerWidth and the media query can disagree by
rounding, which is the dead-band class of bug the comment at
`app-shell.tsx:193-196` documents ("asking at a wider one left a dead band
where the click flipped the drawer flag while CSS still drew the column").

**useMediaQuery choice (the research's recommendation, copied):** promote
`transcript.tsx:1299-1311`'s 12-line hook into `state/media.ts`. "Adopting
`@base-ui/react/unstable-use-media-query` is the alternative; it works
(verified above) but adds an unstable-named subpath import where the repo
already has the 12-line hook." → **Choose the local hook.** Do not import the
unstable subpath.

**Desktop test names: NONE map.** The research is explicit: "Almost nothing
new ports: the mobile layer is web-native (the desktop has no phone layout —
its minimum geometry assumptions, `CHAT_PANEL_MIN` 300 plus `SIDEBAR_MIN`,
make a 375px window impossible)… No desktop test names map to these — that is
the point; they are mobile-native rules and must say so in the ticket."

**NEW web unit tests to write — `web/packages/app/tests/media.test.ts`
(breakpoint primitive behavior):**

- `PHONE_QUERY` equals `` `(max-width: ${PHONE_MAX_WIDTH}px)` `` and
  `"(max-width: 768px)"` (derived from `state/layout.ts:43`, so the JS
  breakpoint can never drift from the layout constant).
- `DESKTOP_QUERY` equals `"(min-width: 769px)"` — the exact complement of
  `PHONE_QUERY` (one must match whenever the other does not).
- The node environment renders nothing (the `base-popover.test.ts` header's
  own rule) — test the exported query constants and any derivation helper;
  the hook body is covered by the acceptance captures, not a render test.

---

## 4. Gaps this ticket closes

Copied verbatim from the research's §(d) tables (M8 and M9) and its
consolidated gap table.

**M8 rows:**

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| responsive dialog primitive | component | Drawer ≤768 / Dialog ≥769 | none (`base/dialog.tsx:67-153`) | `RbResponsiveDialog` |
| media hook | state | one shared `useIsPhone` | local copy in `transcript.tsx:1299-1311`; raw matchMedia `app-shell.tsx:197`; innerWidth compare `chat-page.tsx:440` | `state/media.ts` |
| popover phone form | component | sheet/drawer body | floating popover cards (table above) | `PickerCard` phone branch |

**M9 rows:**

| item | kind | expected | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| canvas project selector, phone | component | chips visible (drawer-form popovers) | `display: none` (`app.css:6807-6809`) | M5 + M8 (delete the rule) — **split: 53 deletes the rule; this ticket supplies the drawer form** |
| which surface the user meets | behavior | same selector both widths | phone: only the uncommitted footer's `role="dialog"` popover (`composer-footer.tsx:141-142`, `:356-368`) | superseded by the above |

**Consolidated-table rows for this ticket:**

| # | item | kind | expected | web value (file:line) | fix sketch |
| --- | --- | --- | --- | --- | --- |
| M8 | responsive surface | component | Drawer ≤768 / Dialog ≥769 | none (`base/dialog.tsx`) | `RbResponsiveDialog` + `PickerCard` branch |
| M8 | media hook | state | shared | local/absent (`transcript.tsx:1299-1311`; `app-shell.tsx:197`; `chat-page.tsx:440`) | `state/media.ts` |
| M6 | dialog cards | geometry | ≤ viewport − 32 | fixed 360px (`app.css:4830-4841`) | min() or M8 — **resolved here by construction for the 7 adopted dialogs (sheet spans the viewport)** |
| M6 | theme select popover | behavior | usable | 260px popover (`settings-appearance.tsx:381`) | M8 drawer at phone — **resolved here via the PickerCard branch** |
| M9 | project selector phone | component | chips like desktop | row hidden, only footer `role="dialog"` popover (`app.css:6807-6809`; `composer-footer.tsx:141-142`, `356-368`) | fixed by M5+M8 — **this ticket's half is the phone form; 53's half is the un-hide** |

---

## 5. Do not

- **Do not change any ≥769px behavior.** Decisions 1–4 still apply at desktop
  widths: `RbDialog`, `RbPopover`, and the desktop arms of `RbDialogGlass` /
  `PickerCard` render exactly as tickets 06/09/10/11 specced. The ≥769px
  screenshot pair below must be pixel-identical to a pre-ticket capture.
- **Do not adopt `@base-ui/react/unstable-use-media-query`** — the research's
  recommendation is the local 12-line hook (§3); an unstable-named subpath
  import can break under upgrades.
- **Do not add snap points to the dialog sheets** (research: "no snap points
  (the dialogs are small)").
- **Do not build a side-drawer form for dialogs or pickers.** Navigation
  panes (the phone sidebar, the right pane) are side drawers — later tickets;
  transient pickers/dialogs are bottom sheets (the research's open question 2
  recommended split, confirmed by the user directive for this wave).
- **Do not restyle the card interiors for phone.** The sheet replaces
  *placement*, not the card's inner layout — `.dialog-card` (360px,
  `app.css:4830-4841`), the popover rows, the add-space card's 680px cap
  (`app.css:13440-13442`) all keep their values inside the sheet.
- **Do not touch the not-yet-adopted sites:** the font/size `RbSelect` menus
  (`settings-appearance.tsx:500-563`), the chat context menu
  (`chat-menu.tsx:68`), and the right-pane `+` menu (`right-tab-strip.tsx`)
  keep their floating forms at phone until 52/54 own their arms.
- **Do not delete `.dock-target-selectors { display: none }`
  (`app.css:6807-6809`)** — ticket 53 (M5) owns that edit; this ticket only
  supplies the phone form the chips will use once visible.
- **Do not create any new z-index tier** — the sheet rides `--rb-z-modal`
  (70) through `.modal-backdrop` (`app.css:4744-4753`); the ladder at
  `app.css:233-255` is closed (research layout-system item 6: "no new
  tiers").
- **Do not add hover-open delays or a Tab-trap to the sheet** — the primitive
  follows the same absent-by-contract rules as ticket 09 (gap rows 87, 88).
- **Do not change any user-visible string.** The dialogs' copy ("Rename
  session", "Delete session?", "Rename project", "Remove project?", "Add a
  theme", "Theme mapping") is ticket 06/10/11's verbatim port.

---

## 6. Acceptance

- [ ] `state/media.ts` exports `useMediaQuery`/`useIsPhone`/`useIsDesktop`
      (+ `PHONE_QUERY`/`DESKTOP_QUERY`); `transcript.tsx`'s local copy
      (`:1299-1311`) is deleted and imported from the shared module;
      `app-shell.tsx:197` and `chat-page.tsx:440` consume the shared hook.
- [ ] At ≥769px every adopted surface renders exactly as before: rename chat,
      delete chat, rename space, delete space, theme import, theme review
      (centered 360px `.dialog-card`), the add-space palette (centered 680px
      glass card), and every PickerCard menu (floating popover) — no visual
      diff.
- [ ] Open any adopted dialog at ≤768px → it slides in as a bottom sheet per
      spec (fixed left/right/bottom, `border-radius: 16px 16px 0 0`,
      `max-height: calc(100dvh - var(--rb-space-lg))`, backdrop at
      `--rb-z-modal`); at ≥769px → centered dialog. No adopted card can
      overflow a 375px viewport.
- [ ] Open any PickerCard menu at ≤768px (device chip, project chip,
      checkout, ref, model/harness picker, spaces menu, sidebar view menu,
      theme family menu) → the card body opens as the sheet with the trigger
      unchanged; at ≥769px → the floating popover, unchanged.
- [ ] Swipe down on an open sheet dismisses it (`swipeDirection="down"`);
      Escape closes it; the rename/delete dialogs' backdrop press does NOT
      close them (matching `RbDialog`'s `disablePointerDismissal`), the
      add-space palette's scrim press DOES (matching `RbDialogGlass`).
- [ ] Focus is trapped in an open sheet and the document behind does not
      scroll (modal contract); session-nav shortcuts stay quiet while a sheet
      is open (`overlaySource` claim carried through).
- [ ] Reduced motion: the sheet entrance snaps (no `rb-dialog-in` animation).
- [ ] Unit tests: `web/packages/app/tests/media.test.ts` — the breakpoint
      primitive behavior cases in §3. No desktop test names map (mobile
      native, §3).
- [ ] Screenshot pair at 375px (phone web vs ≥769px web, proving both arms):
      states — rename-chat dialog open; project-chip sheet open over the
      composer; add-space palette open (full-width sheet); model/harness
      picker sheet open.
- [ ] `pnpm -r build` green; package vitest green.
- [ ] No new literal hex/px where a `--rb-*` token exists.

## Comments

(empty; appended during implementation)
