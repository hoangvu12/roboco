# Base UI adoption — steps 0 and 1 report

Date: 2026-09-18 · Branch: `wp2/00-baseui-foundation` (off
`web-parity/wave-1` @ `8553587a`) · Worktree: `roboco-wt/00-baseui`.

Implements blueprint steps 0 (dep + `components/base/` wrapper layer) and 1
(dialog migration) of
[`round2-baseui-blueprint.md`](./round2-baseui-blueprint.md) §8. Popover
surfaces are NOT touched (Phase 2, later).

## Comments

### Step 0 — foundation

**Dependency:** `@base-ui/react` **1.8.0**, pinned exact (no `^`) per the
blueprint's §1 pinning note. Satisfies the repo's `react ^19.2.0`
(Base UI peer range `^17 || ^18 || ^19`). Installed with
`pnpm --filter @roboco/app add --save-exact @base-ui/react@1.8.0`; pulled
`@base-ui/utils@0.4.0` + `@floating-ui/react-dom@2.1.9` as deps.

**Bundle delta** (measured by rebuilding the base commit via stash, then
this branch): `main-*.js` 1,432.00 → 1,485.44 kB raw / 537.82 → 555.99 kB
gzip — **+53.4 kB raw / +18.2 kB gzip**. All six wrappers import their
parts (only Dialog renders today; the rest sit in the bundle ready for
Phase 2). Within the blueprint's +20–45 kB gzip estimate for the full
adopted set.

**The wrapper layer** — `web/packages/app/src/components/base/`:

| File | What it encodes (once) |
| --- | --- |
| `positioning.ts` | The pure parity core: `NO_FLIP_COLLISION_AVOIDANCE` (`side: 'shift'`, `align: 'shift'`, `fallbackAxisSide: 'none'` — clamp-only, never flip, `snap_to_window_with_margin(8)`), `noFlipPositionerProps` (adds `collisionPadding: 8`, `positionMethod: 'fixed'`, default `sideOffset: 6`), the §5 anchor-helper table (`anchorBelow`…`menuAt` — the `lib/popover-anchor.ts` replacement as pure data), `virtualAnchorAt(x, y)` (zero-rect VirtualElement for caret/pointer anchors), `exitMotionMs(speedScale)` (100 × speed), and `escapeFinalFocusTarget` (reason `escape-key` → target, else `false`). |
| `overlay.ts` | The ticket-12 `overlayKeyboard` wiring: pure `overlayKeyboardTransition(source, prev, next)` + `useOverlayKeyboardSource(source, open)` — registers while open (incl. unmount-while-open), unregisters on close, opts out with `undefined`. |
| `popover.tsx` | `RbPopover` — Root+Portal+Positioner+Popup: the no-flip preset, `modal: false`, reason-recording `onOpenChange` feeding the `finalFocus` escape decision (`pickers.rs:871-890`), `initialFocus` passthrough, `motionSpeed` → inline `--rb-motion-menu-out`, popup wears `.rb-popover-popup` + `.popover-card` (exit hooks + occluder via CSS), `anchor` passthrough (VirtualElement included), `overlaySource`, `actionsRef`/`onOpenChangeComplete`. |
| `dialog.tsx` | `RbDialog` — `modal: true` + `disablePointerDismissal: true` (the desktop's `.occlude()`d scrim swallows presses, never dismisses — popover.rs:693), Backdrop wears `.modal-backdrop` (token scrim), Popup wears `.modal-card rb-dialog-card` (self-centered, see CSS below), `initialFocus`/`finalFocus`/`ariaLabel` passthrough, no exit motion (the desktop has no `dialog_out` — instant unmount), `overlaySource` opt-in only (desktop modals do NOT claim the keyboard — `overlay_owns_keyboard` covers palette/pickers). |
| `menu.tsx` | `RbMenu` — `modal: false` hard-coded (Menu's default `true` would trap focus/lock scroll/occlude — wrong per gap row 87), `loopFocus` + `highlightItemOnHover` restated, shared no-flip preset, `.rb-popover-popup` popup, `overlaySource`. `RbMenuItem` wears `.menu-row` (with the `[data-highlighted]` CSS wash). `RbContextMenu` (pointer Root; Base UI omits `modal` outright — non-modal by the library's own design; open shadowed for the registry) + `RbContextMenuPositioner` (clamp-only at the pointer). |
| `tooltip.tsx` | `RbTooltip` (label-content API) + `RbTooltipTrigger` (280ms HOVER_DELAY default) + `RbTooltipProvider` (shared delay/timeout group). No-flip preset (top/center default). The visual-only rule is documented in the wrapper: content cards are `RbPopover openOnHover`, not tooltips. |
| `select.tsx` | `RbSelect` (generic Root passthrough; open shadowed only for the `overlayKeyboard` wiring — a consumer `open` wins) + `RbSelectPositioner` with `alignItemWithTrigger: false` hard-coded (the Select-family parity rule, §6.5/§10.2) on the shared preset. |
| `switch.tsx` | `RbSwitch` — Root `.toggle rb-switch` + Thumb `.toggle-thumb` on Base UI parts; `[data-checked]` CSS drives the fill and thumb slide (replaces the `.toggle-on` class juggling; the 32×18 geometry is untouched). |

**CSS** (all additive; the hand-rolled layer's rules are untouched and keep
serving until Phase 2): `.rb-popover-popup[data-open]`/`[data-closed]`
(entrance/exit keying, dead hit-testing, `::after` occluder —
popover.rs:406 as pure CSS), `.rb-tooltip-popup[data-instant]`,
`.menu-row[data-highlighted]` (the one-tone selected wash),
`.rb-dialog-card` (see below), `.rb-switch[data-checked]`, plus the
reduced-motion entries (popups snap, dialog entrance snaps — motion.rs:118).

**Tests:** `tests/base-popover.test.ts` — 19 node-env pure tests: the
preset object + a never-flip property over all side/align combos, the §5
anchor table, `virtualAnchorAt`'s rect, the escape-focus decision, the
exit-duration scaling, the `overlayKeyboardTransition` table, and the real
registry's owns-while-open semantics.

### Step 1 — dialog migration

Migrated onto `RbDialog` (the only `Modal` consumers that exist):

- `chat-menu.tsx` — `RenameChatDialog`, `DeleteChatDialog`.
- `space-filter.tsx` — `RenameSpaceDialog`, `DeleteSpaceDialog`.

Each keeps its conditional mount (`{dialog === "rename" && …}`) with
`open` always true while mounted; `onOpenChange(false)` (Escape /
close-press) routes to the existing close callbacks. The manual
form-level `onKeyDown` Escape handlers and `autoFocus` attrs are deleted —
Base UI owns Escape, and `initialFocus` (the `DialogField` input ref)
replaces `autoFocus`. Strings, button rows, `DialogCard`/`DialogTitle`/
`DialogBody`/`DialogField`/`Btn*` chrome, and all submit/confirm logic are
byte-identical.

Deleted: `Modal` + `ModalGlass` from `components/popover/menu.tsx` (per
blueprint Phase 1 — the shells "move into components/base/dialog.tsx";
the inner chrome parts stay exported there). `createPortal` import gone.

Deliberately NOT migrated: the login dialog (`settings-accounts.tsx`,
`.login-dialog-*` — per instructions), the subagent overlay and engine
drawer (drawers, not dialogs), the add-space palette (a Phase 2 popover
surface), the attachments lightbox (blueprint §7: not a Dialog — custom
pan/zoom surface, keep).

**Centering change:** `.rb-dialog-card` is `position: fixed; top/left 50%`
+ `translate(-50%, -50%)`, `max-height: calc(100dvh - 2 *
var(--rb-space-lg))`, `overflow: auto` (hidden scrollbar), `z-index:
var(--rb-z-modal)` — Base UI renders Backdrop and Popup as portal
SIBLINGS, so the card centers itself instead of riding the backdrop's flex
box; tall cards scroll inside instead of clipping above the fold (the
flex-overflow trap the blueprint's Phase 1 called out). The entrance re-keys
to `[data-open]` so a close inside the 180ms window cancels the animation
(under `animation: none` while not open) instead of replaying it; the
winning `rb-dialog-in` keyframes animate the `translate` property, which
composes with — not clobbers — the centering `transform`.

### Deviations from the blueprint (and why)

1. **Dialog consumers never enter the `[data-closed]` transition state.**
   The blueprint's Phase 1 says "swap open/close keying to
   `[data-open]`/`[data-closed]`" — the CSS hooks exist, but the migrated
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
4. **`RbContextMenu` cannot hard-code `modal: false`** — Base UI's
   ContextMenu omits the `modal` prop entirely (never modal by design);
   parity holds by the library's own construction, documented in the
   wrapper.
5. **`RbSelect`/`RbContextMenu`/`RbTooltip` skeleton shape.** Only the
   Root/Positioner parity rules are encoded (per the blueprint's §2 file
   sketch); Trigger/Value/Items parts stay raw `Select.*`/`Menu.*` imports
   at consumers. Select/ContextMenu shadow the open state internally just
   so `useOverlayKeyboardSource` can observe an otherwise-uncontrolled
   root (a consumer `open` still wins). Phase 2/3 tickets extend the
   wrappers when the first real consumer lands.
6. **Focus behavior deltas, all Base UI defaults the blueprint blesses:**
   dialogs now trap focus and lock page scroll (`modal: true` — "matches
   desktop `popover::modal` occluding behavior" per the blueprint's
   mapping table), initial focus moves in (rename: the input via
   `initialFocus`, was `autoFocus`; delete: the first tabbable — the Cancel
   button — where the old code left focus wherever it was). Scrim clicks
   remain non-dismissing (`disablePointerDismissal: true` — preserved).
7. **`RbPopover`'s exit window drops the +20ms reap grace** (unmount at
   animation end, ~100ms, not 120ms) — the blueprint's own §6.1
   recommendation; only affects future RbPopover consumers, since the
   hand-rolled `Popup` layer still serves every popover until Phase 2.

### Verification

- `pnpm -r build` (all web packages: proto, engine-client, app typecheck +
  vite build): **green**. Bundle numbers above.
- `@roboco/app` vitest: **681/681** across 46 files (662/662 at base + the
  19 new `base-popover` tests). Nothing else moved.
- `git stash`-based base rebuild used only to measure the bundle delta;
  working tree restored, dist rebuilt from this branch afterwards.
- **Browser verification: SKIPPED — port 27699 is held** by
  `web_smoke.exe` (PID 29248, path `C:\Users\ADMIN\Desktop\nguyenvu\roboco\target\debug\examples\web_smoke.exe`
  — the MAIN checkout, i.e. a human's smoke session). Per the runbook
  ("kill ONLY processes whose path is in your worktree") it was left
  running and no schtask/browser was started. The DOM-level re-check the
  next session should run for this step: open the chat rename/delete and
  space rename/delete dialogs on the smoke build and verify — scrim
  renders at `--rb-scrim-alpha`, card centered both axes with
  `translate(-50%,-50%)` + 16px radius + 44px blur, `rb-dialog-in` plays
  on `[data-open]`, Escape closes each dialog, input focused on open
  (rename), Cancel/submit paths, and re-shoot the ticket-10 pairs
  (`10-m-space-rename-dialog`, `10-n-space-delete-dialog` + the chat
  dialog equivalents). All logic-level guarantees (positioning preset,
  escape contract, overlay wiring) are covered by the new unit tests.

### Notes for the next step (Phase 2 — popover surfaces)

- **The wrapper layer is additive and unused by popovers so far** — every
  popover still rides `components/popover/popup.tsx` +
  `lib/popover-anchor.ts` + `lib/popup-lifecycle.ts`. Nothing of the old
  layer was deleted except `Modal`/`ModalGlass`. The `[data-rb-popup]` CSS
  rules and the new `.rb-popover-popup` rules coexist; the old ones retire
  surface-by-surface.
- Migrate in the blueprint's §8.2 order (context menus → sidebar menus →
  footer pickers → identity card → add-space palette), one green landing
  each. `RbPopover`'s placement accepts the old helper names
  (`"anchorBelowEnd"` etc.) or a `{side, align, sideOffset}` object;
  `virtualAnchorAt` covers `menuAt`/`anchorAboveAt` points.
- The pickers' `noteTriggerPress`/`takePressWasOpen` dance is NOT needed on
  Base UI triggers (the dismissal reason `trigger-press` handles the
  close-and-reopen-in-one-gesture case; blueprint §6.2) — delete it per
  consumer as they migrate, and retire the `trigger_press_note…` test block
  with the last consumer.
- Composer pickers should pass `overlaySource="composer-pickers"` and the
  add-space palette `overlaySource="add-space"` to keep ticket 12's
  quiet-under-overlay semantics; both currently register by hand — swap to
  the wrapper prop when they migrate.
- `Popover.Trigger` (with `data-popup-open` for the trigger open-snap) is
  NOT re-exported from the wrapper yet; the first Phase 2 consumer that
  needs it should extend `popover.tsx` (the multi-chip footer pickers will
  want `Popover.createHandle` + per-trigger payloads, per blueprint §6.2).
- The login dialog stays hand-rolled (`.login-dialog-*`) until its own
  ticket says otherwise.
