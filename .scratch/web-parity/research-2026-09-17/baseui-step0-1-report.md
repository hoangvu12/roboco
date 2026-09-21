# Base UI adoption â€” steps 0 and 1 report

Date: 2026-09-18 Â· Branch: `wp2/00-baseui-foundation` (off
`web-parity/wave-1` @ `8553587a`) Â· Worktree: `roboco-wt/00-baseui`.

Implements blueprint steps 0 (dep + `components/base/` wrapper layer) and 1
(dialog migration) of
[`round2-baseui-blueprint.md`](./round2-baseui-blueprint.md) Â§8. Popover
surfaces are NOT touched (Phase 2, later).

## Comments

### Step 0 â€” foundation

**Dependency:** `@base-ui/react` **1.8.0**, pinned exact (no `^`) per the
blueprint's Â§1 pinning note. Satisfies the repo's `react ^19.2.0`
(Base UI peer range `^17 || ^18 || ^19`). Installed with
`pnpm --filter @roboco/app add --save-exact @base-ui/react@1.8.0`; pulled
`@base-ui/utils@0.4.0` + `@floating-ui/react-dom@2.1.9` as deps.

**Bundle delta** (measured by rebuilding the base commit via stash, then
this branch): `main-*.js` 1,432.00 â†’ 1,485.44 kB raw / 537.82 â†’ 555.99 kB
gzip â€” **+53.4 kB raw / +18.2 kB gzip**. All six wrappers import their
parts (only Dialog renders today; the rest sit in the bundle ready for
Phase 2). Within the blueprint's +20â€“45 kB gzip estimate for the full
adopted set.

**The wrapper layer** â€” `web/packages/app/src/components/base/`:

| File | What it encodes (once) |
| --- | --- |
| `positioning.ts` | The pure parity core: `NO_FLIP_COLLISION_AVOIDANCE` (`side: 'shift'`, `align: 'shift'`, `fallbackAxisSide: 'none'` â€” clamp-only, never flip, `snap_to_window_with_margin(8)`), `noFlipPositionerProps` (adds `collisionPadding: 8`, `positionMethod: 'fixed'`, default `sideOffset: 6`), the Â§5 anchor-helper table (`anchorBelow`â€¦`menuAt` â€” the `lib/popover-anchor.ts` replacement as pure data), `virtualAnchorAt(x, y)` (zero-rect VirtualElement for caret/pointer anchors), `exitMotionMs(speedScale)` (100 Ã— speed), and `escapeFinalFocusTarget` (reason `escape-key` â†’ target, else `false`). |
| `overlay.ts` | The ticket-12 `overlayKeyboard` wiring: pure `overlayKeyboardTransition(source, prev, next)` + `useOverlayKeyboardSource(source, open)` â€” registers while open (incl. unmount-while-open), unregisters on close, opts out with `undefined`. |
| `popover.tsx` | `RbPopover` â€” Root+Portal+Positioner+Popup: the no-flip preset, `modal: false`, reason-recording `onOpenChange` feeding the `finalFocus` escape decision (`pickers.rs:871-890`), `initialFocus` passthrough, `motionSpeed` â†’ inline `--rb-motion-menu-out`, popup wears `.rb-popover-popup` + `.popover-card` (exit hooks + occluder via CSS), `anchor` passthrough (VirtualElement included), `overlaySource`, `actionsRef`/`onOpenChangeComplete`. |
| `dialog.tsx` | `RbDialog` â€” `modal: true` + `disablePointerDismissal: true` (the desktop's `.occlude()`d scrim swallows presses, never dismisses â€” popover.rs:693), Backdrop wears `.modal-backdrop` (token scrim), Popup wears `.modal-card rb-dialog-card` (self-centered, see CSS below), `initialFocus`/`finalFocus`/`ariaLabel` passthrough, no exit motion (the desktop has no `dialog_out` â€” instant unmount), `overlaySource` opt-in only (desktop modals do NOT claim the keyboard â€” `overlay_owns_keyboard` covers palette/pickers). |
| `menu.tsx` | `RbMenu` â€” `modal: false` hard-coded (Menu's default `true` would trap focus/lock scroll/occlude â€” wrong per gap row 87), `loopFocus` + `highlightItemOnHover` restated, shared no-flip preset, `.rb-popover-popup` popup, `overlaySource`. `RbMenuItem` wears `.menu-row` (with the `[data-highlighted]` CSS wash). `RbContextMenu` (pointer Root; Base UI omits `modal` outright â€” non-modal by the library's own design; open shadowed for the registry) + `RbContextMenuPositioner` (clamp-only at the pointer). |
| `tooltip.tsx` | `RbTooltip` (label-content API) + `RbTooltipTrigger` (280ms HOVER_DELAY default) + `RbTooltipProvider` (shared delay/timeout group). No-flip preset (top/center default). The visual-only rule is documented in the wrapper: content cards are `RbPopover openOnHover`, not tooltips. |
| `select.tsx` | `RbSelect` (generic Root passthrough; open shadowed only for the `overlayKeyboard` wiring â€” a consumer `open` wins) + `RbSelectPositioner` with `alignItemWithTrigger: false` hard-coded (the Select-family parity rule, Â§6.5/Â§10.2) on the shared preset. |
| `switch.tsx` | `RbSwitch` â€” Root `.toggle rb-switch` + Thumb `.toggle-thumb` on Base UI parts; `[data-checked]` CSS drives the fill and thumb slide (replaces the `.toggle-on` class juggling; the 32Ã—18 geometry is untouched). |

**CSS** (all additive; the hand-rolled layer's rules are untouched and keep
serving until Phase 2): `.rb-popover-popup[data-open]`/`[data-closed]`
(entrance/exit keying, dead hit-testing, `::after` occluder â€”
popover.rs:406 as pure CSS), `.rb-tooltip-popup[data-instant]`,
`.menu-row[data-highlighted]` (the one-tone selected wash),
`.rb-dialog-card` (see below), `.rb-switch[data-checked]`, plus the
reduced-motion entries (popups snap, dialog entrance snaps â€” motion.rs:118).

**Tests:** `tests/base-popover.test.ts` â€” 19 node-env pure tests: the
preset object + a never-flip property over all side/align combos, the Â§5
anchor table, `virtualAnchorAt`'s rect, the escape-focus decision, the
exit-duration scaling, the `overlayKeyboardTransition` table, and the real
registry's owns-while-open semantics.

### Step 1 â€” dialog migration

Migrated onto `RbDialog` (the only `Modal` consumers that exist):

- `chat-menu.tsx` â€” `RenameChatDialog`, `DeleteChatDialog`.
- `space-filter.tsx` â€” `RenameSpaceDialog`, `DeleteSpaceDialog`.

Each keeps its conditional mount (`{dialog === "rename" && â€¦}`) with
`open` always true while mounted; `onOpenChange(false)` (Escape /
close-press) routes to the existing close callbacks. The manual
form-level `onKeyDown` Escape handlers and `autoFocus` attrs are deleted â€”
Base UI owns Escape, and `initialFocus` (the `DialogField` input ref)
replaces `autoFocus`. Strings, button rows, `DialogCard`/`DialogTitle`/
`DialogBody`/`DialogField`/`Btn*` chrome, and all submit/confirm logic are
byte-identical.

Deleted: `Modal` + `ModalGlass` from `components/popover/menu.tsx` (per
blueprint Phase 1 â€” the shells "move into components/base/dialog.tsx";
the inner chrome parts stay exported there). `createPortal` import gone.

Deliberately NOT migrated: the login dialog (`settings-accounts.tsx`,
`.login-dialog-*` â€” per instructions), the subagent overlay and engine
drawer (drawers, not dialogs), the add-space palette (a Phase 2 popover
surface), the attachments lightbox (blueprint Â§7: not a Dialog â€” custom
pan/zoom surface, keep).

**Centering change:** `.rb-dialog-card` is `position: fixed; top/left 50%`
+ `translate(-50%, -50%)`, `max-height: calc(100dvh - 2 *
var(--rb-space-lg))`, `overflow: auto` (hidden scrollbar), `z-index:
var(--rb-z-modal)` â€” Base UI renders Backdrop and Popup as portal
SIBLINGS, so the card centers itself instead of riding the backdrop's flex
box; tall cards scroll inside instead of clipping above the fold (the
flex-overflow trap the blueprint's Phase 1 called out). The entrance re-keys
to `[data-open]` so a close inside the 180ms window cancels the animation
(under `animation: none` while not open) instead of replaying it; the
winning `rb-dialog-in` keyframes animate the `translate` property, which
composes with â€” not clobbers â€” the centering `transform`.

### Deviations from the blueprint (and why)

1. **Dialog consumers never enter the `[data-closed]` transition state.**
   The blueprint's Phase 1 says "swap open/close keying to
   `[data-open]`/`[data-closed]`" â€” the CSS hooks exist, but the migrated
   dialogs keep unmount-on-close (`open` is always true while mounted)
   because the desktop has no `dialog_out` motion: modals unmount
   instantly, so there is no exit animation to keep painting. `RbDialog`'s
   `onOpenChangeComplete`/`actionsRef` are wired for a future surface that
   does add exit motion.
2. **Delete dialogs gain Escape-close.** The old web delete dialogs had no
   Escape handler at all (only the rename forms did, via form
   `onKeyDown`); Base UI's dialog handles Escape by default, so both
   dialogs now close on it. Matches the rename contract that already
   shipped and WAI-ARIA's dialog expectation; flagged if the desktop truly
   leaves delete dialogs Escape-less.
3. **`ModalGlass` deleted with zero consumers.** Nothing imported it (the
   add-space palette hand-renders the `.modal-glass-backdrop`/`.modal-card`
   classes). The glass scrim CSS stays for the palette; its Phase 2
   migration decides the wrapper shape.
4. **`RbContextMenu` cannot hard-code `modal: false`** â€” Base UI's
   ContextMenu omits the `modal` prop entirely (never modal by design);
   parity holds by the library's own construction, documented in the
   wrapper.
5. **`RbSelect`/`RbContextMenu`/`RbTooltip` skeleton shape.** Only the
   Root/Positioner parity rules are encoded (per the blueprint's Â§2 file
   sketch); Trigger/Value/Items parts stay raw `Select.*`/`Menu.*` imports
   at consumers. Select/ContextMenu shadow the open state internally just
   so `useOverlayKeyboardSource` can observe an otherwise-uncontrolled
   root (a consumer `open` still wins). Phase 2/3 tickets extend the
   wrappers when the first real consumer lands.
6. **Focus behavior deltas, all Base UI defaults the blueprint blesses:**
   dialogs now trap focus and lock page scroll (`modal: true` â€” "matches
   desktop `popover::modal` occluding behavior" per the blueprint's
   mapping table), initial focus moves in (rename: the input via
   `initialFocus`, was `autoFocus`; delete: the first tabbable â€” the Cancel
   button â€” where the old code left focus wherever it was). Scrim clicks
   remain non-dismissing (`disablePointerDismissal: true` â€” preserved).
7. **`RbPopover`'s exit window drops the +20ms reap grace** (unmount at
   animation end, ~100ms, not 120ms) â€” the blueprint's own Â§6.1
   recommendation; only affects future RbPopover consumers, since the
   hand-rolled `Popup` layer still serves every popover until Phase 2.

### Verification

- `pnpm -r build` (all web packages: proto, engine-client, app typecheck +
  vite build): **green**. Bundle numbers above.
- `@roboco/app` vitest: **681/681** across 46 files (662/662 at base + the
  19 new `base-popover` tests). Nothing else moved.
- `git stash`-based base rebuild used only to measure the bundle delta;
  working tree restored, dist rebuilt from this branch afterwards.
- **Browser verification: SKIPPED â€” port 27699 is held** by
  `web_smoke.exe` (PID 29248, path `C:\Users\ADMIN\Desktop\nguyenvu\roboco\target\debug\examples\web_smoke.exe`
  â€” the MAIN checkout, i.e. a human's smoke session). Per the runbook
  ("kill ONLY processes whose path is in your worktree") it was left
  running and no schtask/browser was started. The DOM-level re-check the
  next session should run for this step: open the chat rename/delete and
  space rename/delete dialogs on the smoke build and verify â€” scrim
  renders at `--rb-scrim-alpha`, card centered both axes with
  `translate(-50%,-50%)` + 16px radius + 44px blur, `rb-dialog-in` plays
  on `[data-open]`, Escape closes each dialog, input focused on open
  (rename), Cancel/submit paths, and re-shoot the ticket-10 pairs
  (`10-m-space-rename-dialog`, `10-n-space-delete-dialog` + the chat
  dialog equivalents). All logic-level guarantees (positioning preset,
  escape contract, overlay wiring) are covered by the new unit tests.

### Notes for the next step (Phase 2 â€” popover surfaces)

- **The wrapper layer is additive and unused by popovers so far** â€” every
  popover still rides `components/popover/popup.tsx` +
  `lib/popover-anchor.ts` + `lib/popup-lifecycle.ts`. Nothing of the old
  layer was deleted except `Modal`/`ModalGlass`. The `[data-rb-popup]` CSS
  rules and the new `.rb-popover-popup` rules coexist; the old ones retire
  surface-by-surface.
- Migrate in the blueprint's Â§8.2 order (context menus â†’ sidebar menus â†’
  footer pickers â†’ identity card â†’ add-space palette), one green landing
  each. `RbPopover`'s placement accepts the old helper names
  (`"anchorBelowEnd"` etc.) or a `{side, align, sideOffset}` object;
  `virtualAnchorAt` covers `menuAt`/`anchorAboveAt` points.
- The pickers' `noteTriggerPress`/`takePressWasOpen` dance is NOT needed on
  Base UI triggers (the dismissal reason `trigger-press` handles the
  close-and-reopen-in-one-gesture case; blueprint Â§6.2) â€” delete it per
  consumer as they migrate, and retire the `trigger_press_noteâ€¦` test block
  with the last consumer.
- Composer pickers should pass `overlaySource="composer-pickers"` and the
  add-space palette `overlaySource="add-space"` to keep ticket 12's
  quiet-under-overlay semantics; both currently register by hand â€” swap to
  the wrapper prop when they migrate.
- `Popover.Trigger` (with `data-popup-open` for the trigger open-snap) is
  NOT re-exported from the wrapper yet; the first Phase 2 consumer that
  needs it should extend `popover.tsx` (the multi-chip footer pickers will
  want `Popover.createHandle` + per-trigger payloads, per blueprint Â§6.2).
- The login dialog stays hand-rolled (`.login-dialog-*`) until its own
  ticket says otherwise.

---

# Phase 2 — the popover migration

Date: 2026-09-18 · Branch: `wp2/02-popover-migration` (off `web-parity/wave-1`
@ `14e737f6`) · Worktree: `roboco-wt/02-popovers`. Six commits, one per
family, each landing on a green `pnpm -r build`.

## What migrated, family by family

### 1. Context menus (`feat(web): Base UI phase 2 — migrate context menus to Rb wrappers`)

- **Chat right-click menu** (`chat-menu.tsx`, `chat-list.tsx`,
  `archived-section.tsx`): `useChatMenu` now returns `{ menu, element }` —
  `menu(row)` wraps the chat row in `RbContextMenu` + `ContextMenu.Trigger
  render={row}` (the Trigger ADOPTS the row element — no wrapper div, the
  row's DOM is byte-identical). The library owns the right-click open at
  the pointer (`menu_at` clamp-only geometry), Escape, outside-press
  dismissal, and the exit window (`[data-closed]` CSS). The Copy sub-page
  still swaps the card's content in place (page state mounts per open);
  the dialogs render outside the trigger wrapper so their state outlives
  the menu. `noteTriggerPress`/`menuAt`/the card's escape handler deleted.
- **Space context menu** (`space-filter.tsx`): per-row `RbContextMenu` with
  `ContextMenu.Trigger render={<MenuRowNav …/>}` — one root per row sharing
  one overlay state (`SpaceOverlay`'s "menu" variant no longer carries x/y).
  Its rows close both menus and open the dialog (the desktop's
  `close_space_menu`, spaces.rs:3278-3283); an outside press that lands
  outside the spaces card too closes both (see deviations).

### 2. Sidebar menus (`feat(web): Base UI phase 2 — migrate sidebar menus to Rb wrappers`)

- **Spaces menu** (`SpaceFilter`): `RbPopover` + `RbPopoverTrigger` (the
  wrapper's `Popover.Trigger` re-export) at `anchorBelow`. Search input
  keeps focus on open; the `menuStep`/Enter/typing-reset handler rides
  `RbPopover`'s new `onKeyDown` passthrough (the popup is the card, keys
  bubble from the input exactly as they did to the old `PopoverCard`).
- **View-options menu** (`SidebarViewMenu`): `RbPopover` + Trigger at
  `anchorBelowEnd`, `initialFocus` unset (the card takes no focus on open —
  the old quirk where arrow keys only worked after a click is preserved).
  **Deviation from the blueprint's sketch:** §8.2 said `RbMenu` for
  view-options, but the desktop's view menu is a CURSOR menu
  (`menu_step`, `Option<usize>`, "click clears the cursor",
  spaces.rs:871-973) — roving focus + typeahead would change the keyboard
  semantics, so it rides the Popover part like every other cursor surface
  (§6.5's split, which the implementation brief's "as their shape
  requires" confirms). `RbMenu` remains unused after Phase 2; its first
  consumer should be a surface whose desktop self is genuinely a simple
  focus-walking menu (wave-2/3 candidates: history author/column menus).

### 3. Footer pickers (`feat(web): Base UI phase 2 — migrate footer pickers to Rb wrappers`)

Device / project / checkout / ref (`composer-footer.tsx`): each chip is its
own `RbPopover` + `RbPopoverTrigger` adopting the `FooterChip` button via
`render` (the component now spreads the merged trigger props). Pressing
another chip switches menus (the first popover's outside-click dismisses
it; the new chip's Trigger opens its own) — the four-chip behavior the old
`noteTriggerPressMatching` machinery provided. `createHandle` was NOT
needed: the four chips own separate popovers, so the re-export
(`createRbPopoverHandle`) sits in the wrapper for wave-2's shared-popover
surfaces instead. The cursor handler rides a `display: contents` key frame
(`.picker-key-frame` — the same sink ticket 12's list frames used) so the
popup's flex column is unchanged. Checkout passes `initialFocus={false}`
(it never moved focus on open); the other three focus their search inputs
via the per-card open effects. Widths 224/280/224/320 verbatim.
**Scope change per the brief:** all four pass
`overlaySource="composer-pickers"` — the old web layer registered only the
identity card, but the desktop's `composer.pickers().is_open()` covers the
footer pickers too (shell.rs:3681-3683), so session-nav now goes quiet
under any composer picker.

### 4. Identity card (`feat(web): Base UI phase 2 — migrate the identity card to Rb wrappers`)

`composer-pickers.tsx`: `RbPopover` at `anchorAboveEnd` (right edge flush,
`popover-card-flush identity-card`, 304px/640 cap) + `RbPopoverTrigger`
rendering the chip. The window-capture keydown listener keeps the cursor
model (menuStep, Enter, Cmd/Ctrl+1-9) and DROPS its Escape case — Base UI's
dismiss pipeline owns Escape, which is what records the `escape-key`
reason the focus return reads: `onReturnFocus` became
`escapeFocusTarget={() => textareaRef.current}` (composer.tsx), consumed
by `RbPopover`'s `finalFocus`. The takeover states focus the popup element
(`.rb-popover-popup[data-open]` replaces the dead
`[data-rb-popup="open"]` selector). `overlaySource="composer-pickers"`
replaces the hand-registered effect.

### 5. Add-space palette (`feat(web): Base UI phase 2 — migrate the add-space palette to Rb wrappers`)

New `RbDialogGlass` in `components/base/dialog.tsx` — the `modal_glass`
variant of `RbDialog`: 0.35 scrim on the Backdrop, scrim presses DISMISS
(the palette's contract, vs `RbDialog`'s swallowing scrim), card
self-centers via `.rb-dialog-card` with the same `[data-open]` entrance.
The 680px card, input row, body/rail, and footer are untouched; the 14px
radius moved from an inline style to a `.add-space-frost` CSS rule. The
exit is the `[data-closed]` fade on BOTH portal siblings — Base UI's
animation-aware unmount waits it out, replacing the old 100ms reap timer.

**`state/add-space.ts` dropped `PopupLifecycle`** (the last consumer): the
store now holds `#open` (the dialog's flag) and `#mounted`; the exit
window ends when the mounted component reports Base UI's
`onOpenChangeComplete(false)` ? `unmounted()`, which drops the flow.
`forceClose()` covers a host that is unmounting (engine switch — no exit
to paint). `overlaySource="add-space"` + `overlayOpen` (the component
renders through "closing", so the claim holds until the layer is gone)
replaces the hand-registered effect, keeping ticket 12's quiet-through-
the-exit semantics. The shell's Escape ladder still owns Escape at the
`addSpace` priority, unchanged.

### 6. Old-layer deletion (`refactor(web): delete the hand-rolled popup layer`)

- `components/popover/popup.tsx` — **deleted** (zero consumers remained).
- `lib/popover-anchor.ts` — **deleted** (the wrappers' Positioner preset +
  the `anchorHelperPlacement` table are the replacement).
- `lib/popup-lifecycle.ts` — **deleted whole**, not reduced: the 3-state
  machine, reap timer, press-note, and `exitProgress` are all Base UI's or
  the triggers' now; `speedScale` lives in `exitMotionMs` + the wrapper's
  `motionSpeed` prop; the escape contract lives in `escapeFinalFocusTarget`.
  Nothing had a consumer.
- `tests/popup-lifecycle.test.ts` — **retired with its subjects** (10
  tests). The behavior is the library's own; the two wrapper-owned
  guarantees that replaced retired ones stay unit-tested:
  `escapeFinalFocusTarget` (already in `tests/base-popover.test.ts`) and
  the new `shouldVetoDismissal` (the focus-out veto — ticket 09's "Tab
  does not close it", see deviations). The two `add-space` store tests
  that drove the reap timer now drive the completion callback.
- CSS: `.popover-layer`, `.popover-exit-occlude`, and the
  `[data-rb-popup]` animation rules (incl. their reduced-motion entries)
  deleted; `rb-menu-out` keyframes stay (the `[data-closed]` exit plays
  them); the palette's closing rules re-keyed to `[data-closed]`.
  `components/popover/menu.tsx`'s styled chrome (PopoverCard/Flush/
  PaletteCard, rows, key caps, dialog parts) stays per the blueprint's
  final-state table — wave-2 content rides inside Base UI parts.
- `overlayKeyboard` registration sites: composer-pickers (identity card +
  footer chips) and add-space now register through the wrapper's
  `overlaySource` prop; no hand-registered effect remains.

## Wrapper extensions this phase landed

- `popover.tsx`: `onKeyDown` passthrough (the cursor keyboard model's
  card-frame sink), `RbPopoverTrigger` + `createRbPopoverHandle`
  re-exports, the `focus-out` dismissal veto (below), Positioner wears
  `.rb-popover-positioner`.
- `positioning.ts`: `shouldVetoDismissal`/`VETOED_DISMISSAL_REASONS` (pure
  — unit-tested).
- `menu.tsx`: `RbContextMenuPositioner`/`RbMenu`'s positioner wears the
  same menu-tier class.
- `dialog.tsx`: `RbDialogGlass` (+ `overlayOpen` on both dialog wrappers'
  overlay-keyboard wiring).
- CSS: `.rb-popover-positioner { z-index: var(--rb-z-menu) }` (Base UI sets
  no z-index; the old `.popover-layer`'s tier), `.picker-key-frame {
  display: contents }`, `.add-space-frost { border-radius: 14px }`.

## Deviations (and why)

1. **Tab-out closes ? vetoed.** Base UI's non-modal popovers dismiss on
   `focus-out` (Tab away, programmatic blur). The desktop's popover family
   has no Tab handling and no focus trap (ticket 09 gap rows 29/87), and
   ticket 09's acceptance says "Tab does not close it" — so `RbPopover`
   cancels `focus-out` dismissals outright (`details.cancel()` before the
   store sees them). Outside presses and Escape dismiss normally.
2. **Outside-press consumption.** The old layer swallowed the dismissing
   POINTERDOWN (bubble-phase stop) so content behind couldn't act on it;
   Base UI dismisses on the outside CLICK and never consumes the press —
   content behind reacts to the dismissing gesture, and focus lands where
   clicked. The blueprint's §6.4 left this a per-surface decision and the
   library's default is the accepted path; the observable close outcome is
   identical.
3. **Context menus are library-modal.** Base UI's ContextMenu internally
   traps Tab and `aria-hidden`s the rest of the page while open (its own
   design — the wrapper's step-0 doc comment already blessed it). The
   desktop's context menus have no focus trap; nothing in tickets 10/11/12
   keys on Tab-under-a-context-menu, so this is documented rather than
   fought.
4. **Trigger ARIA.** `Popover.Trigger` emits `aria-haspopup="dialog"` (and
   `aria-controls`); the old hand-rolled triggers spelled
   `aria-haspopup="listbox"` (spaces menu) / `"menu"` (chips). Card `role`s
   pass through verbatim. Cosmetic-DOM-level only.
5. **Footer pickers claim the overlay keyboard** (scope note in family 3) —
   closer to the desktop than the old web layer was.
6. **Nested space context menu over the spaces menu.** Escape closes ONLY
   the context menu (Base UI's tree coordination: innermost first) where
   the old layer's behavior was focus-dependent and accidental (the
   spaces menu's Escape handler fired via the focused search input's
   bubbling). Outside-press-beyond-both and row-pick-into-dialog both
   close the two menus together, matching the desktop
   (`close_space_menu`); presses inside the spaces card leave it open.
7. **Trigger open-snap timing.** The `*-open` chip/trigger classes now key
   off the controlled `open` (drops during the 100ms exit) where the old
   `popup.get() !== null` included the closing phase — a =100ms wash-fade
   difference on close.
8. **Palette modality.** `RbDialogGlass` is a modal Dialog: focus trap +
   scroll lock while open (the old palette had neither), scrim presses
   dismiss on the press (Base UI) instead of a swallowed pointerdown, and
   focus restores to the pre-open element on close (the old layer dropped
   focus to body). All are the blessed step-1 dialog semantics applied to
   the `modal_glass` variant.
9. **Engine-switch palette close is instant** (`forceClose`) — the old
   store-owned timer "played" an exit with nothing left to paint.

## Verification

- `pnpm -r build` (all web packages: proto, engine-client, app typecheck +
  vite build): **green after every family**.
- `@roboco/app` vitest: **673/673** across 45 files. From 681/681 at base:
  -10 retired `popup-lifecycle` tests (subjects deleted — library-owned
  behavior now, per the blueprint's own §6.2 note), +2 new
  `shouldVetoDismissal` cases in `tests/base-popover.test.ts`; the 2
  `add-space` store tests ported from the reap timer to the completion
  callback.
- **Browser verification: SKIPPED — port 27699 is held** by `web_smoke.exe`
  (PID 23684, path `C:\Users\ADMIN\Desktop\ngyenvu\roboco\target\debug\examples\web_smoke.exe`
  — the MAIN checkout, i.e. a human's smoke session). Per the runbook ("a
  human may be testing; if held, skip and document") it was left running;
  no schtask, no browser, no shots. The next session should re-run the
  ticket-09/10/11 pairs' DOM checks on this branch's build: the context
  menus' right-click open + Copy page swap, the spaces/view menus'
  keyboard + search-focus contracts, the four footer popovers'
  switch-on-chip-press, the identity card's Escape?composer-focus return
  (`activeElement` assert), and the palette's scrim-press/Escape close
  with the exit fade, then shoot `.scratch/web-parity/shots/phase2/`.

## Notes for wave-2 tickets

- Import `RbPopover`/`RbPopoverTrigger`/`RbContextMenu`/`RbMenu`/
  `RbDialogGlass` from `components/base/`; the cursor keyboard model stays
  consumer-side (`onKeyDown` passthrough or a `display: contents` frame);
  Escape is NEVER the consumer's job — it flows the dismiss pipeline, and
  `escapeFocusTarget` is how a surface opts into the focus return.
- `components/popover/menu.tsx`'s chrome (PopoverCard, MenuRowNav, key
  caps, skeletons, dialog parts) is still the visual vocabulary — render
  it INSIDE the Base UI parts (the popup already wears `.popover-card` via
  `cardClassName`/default, so content components render the inner pieces,
  not a second card).
- Ticket 14's caret popups: `anchor` + `virtualAnchorAt(x, y)` +
  `placement="anchorAboveAt"`; ticket 13's full-width menus:
  `fullWidthMenuAbove` + CSS `width: var(--anchor-width)`.
- The multi-trigger shared-popover shape (`createRbPopoverHandle` +
  per-trigger payloads, blueprint §6.2) is re-exported and waiting for its
  first consumer.
- `RbMenu` is still unexercised — its first consumer should verify the
  `[data-highlighted]` wash and `closeOnClick` behavior against the
  desktop's row recipe in a real surface before wave-2 leans on it.
