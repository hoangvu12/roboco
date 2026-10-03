# 03 — Footer popover placement and SwitchRef feedback parity

**What to build:** Three verified cosmetic/wiring gaps on the footer chips:

1. **Canvas popovers open below.** Desktop attaches the canvas
   Branch/Checkout menus with `attach_overlay_below` — **below, always, no
   flip** (pickers.rs:3242-3258); `measure_trigger` (:2758-2801) only sizes
   the height budget (prefer below when ≥180px fits, clamp 640). The
   in-thread draft footer is adaptive (`attach_overlay`, :3404-3416). Web
   pins `anchorAbove` everywhere (composer-footer.tsx:533 checkout, :730
   ref) — on the canvas the card covers the input pill.
2. **Device popover end-alignment.** Desktop attaches the canvas device AND
   project popovers with `attach_overlay_end` (pickers.rs:3175/:3182,
   helper :5293-5310); web's DeviceChip is start-aligned
   (composer-footer.tsx:246). (The footer's Layer B device chip is a
   web-only surface — desktop's draft footer has no device chip,
   pickers.rs:3351-3353 — keep its current alignment.)
3. **SwitchRef list dim.** Desktop dims the whole ref list to 0.55 during a
   switch (pickers.rs:3666); web shows only a per-row "switching…" tag
   (composer-footer.tsx:849, `.picker-row-switching` app.css:8973).

**Blocked by:** ticket 02 (shared RefChip surgery; land after).

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` §2.

**Desktop reference (for lookups only):**
`crates/ui/src/pickers.rs:3242-3258` (below attach), `:5293-5310`
(attach_overlay_end), `:3666` (dim).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/components/composer-footer.tsx` | edit | `placement` passthrough on CheckoutChip/RefChip/DeviceChip; `data-switching` attr + `.picker-list[data-switching] { opacity: 0.55 }` |
| `web/packages/app/src/components/composer/new-thread-selectors.tsx` | edit | canvas mounts pass `"anchorBelow"` (checkout/ref) and `"anchorAboveEnd"` (device/project) |
| `web/packages/app/src/styles/app.css` | edit | the dim rule |
| `web/packages/app/tests/` | edit | placement-prop routing assertions (jsdom style/attr) |

## 1. Notes

- The phone arm is unaffected — the sheet replaces placement
  (ui/PickerCard.tsx:79-100).
- Base UI's positioner is no-flip by contract (base/popover.tsx), so
  "below" is a placement prop, not new geometry code.
- Ticket 10's original table pinned above (`.scratch/web-parity/issues/
  10-pickers-and-menus.md:234-235`) — the desktop has since moved to below;
  this ticket follows the desktop and should note the supersession.

## 2. Tests

- Placement routing: canvas footer renders CheckoutChip/RefChip with the
  below placement prop; the draft footer keeps above; DeviceChip canvas =
  end-aligned.
- The dim: `switching !== null` → the list container carries the attr/inline
  opacity.

## 3. Acceptance checklist

- [x] Canvas checkout/ref cards open below the row; draft footer unchanged
- [x] Device/project canvas popovers end-aligned
- [x] Ref list dims during SwitchRef
- [x] Tests + full app suite green

## Comments

**Implemented and reviewed** (branch `ticket/wpn-03-footer-popover-direction`, commit `3159f0cb` → review fixes on top):

- `DeviceChip`/`CheckoutChip`/`RefChip` (`composer-footer.tsx`) each gained a `placement` passthrough (PickerCard's `AnchorPlacement | AnchorHelperId`, default `anchorAbove` — the footer Layer B is unchanged). The canvas mounts pass it through `new-thread-selectors.tsx`: checkout/ref `anchorBelow` (below always, no flip — the desktop's `attach_overlay_below`, pickers.rs:3242-3258; the floating row sits above the pill, so an upward card covers the input), device `anchorAboveEnd` (`attach_overlay_end`, :3175). ProjectChip was already hard-pinned `anchorAboveEnd`, so the canvas project popover needed no change — the ticket's file table lists the passthrough on three chips only, and both arms demonstrably render the project card end-aligned.
- The ref list dim: `BranchCard`'s `.picker-list` carries `data-switching` while a SwitchRef is in flight, and the dim rule is the ticket's container form `.picker-list[data-switching] { opacity: 0.55 }` (pickers.rs:3666 dims every row; the per-row "switching…" tag rides the dim). Ticket 10's per-row selector was DEAD at the base — `git grep data-switching 649f9d91 -- web/` shows the CSS rule and nothing rendering the attr; this ticket wires it and folds the rule onto the container.
- Tests (TDD, red-verified against the pre-fix code): `openChipPlacement` reads Base UI's `data-side`/`data-align` off the open `.rb-popover-positioner` — canvas checkout/ref `bottom`/`start`, canvas device/project `top`/`end`, draft footer `top`/`start` + device `start` + project `end` (the unchanged guard); the dim test defers the SwitchRef RPC (a `deferSwitch` arm on the harness double) and asserts the attr mid-flight plus the 0.55 rule text in app.css, then resolves and checks the pick records. Phone arm untouched — the sheet replaces placement (`picker-card-phone.test.ts` green).
- Verification: `tests/composer-footer-git.test.ts` 11/11 and `tests/new-thread-git-selectors.test.ts` 5/5; phone/popover suites (`picker-card-phone`, `footer-git-draft`, `composer-dock`, `escape`, `base-popover`, `responsive-surface`, `completion-popup-scroll`) 96/96; `pnpm exec tsc --noEmit` clean; full app suite `pnpm exec vitest run` → **153 files / 2285 tests green**.
- Two-axis review (Standards + Spec, both axes run in-session — no subagent tool). Findings fixed: the DeviceChip doc's draft-footer citation corrected (:3351-3353, copied from this ticket, → :3372-3373, the real `"Space/Device popovers mount in the floating row above the pill"` arm); the CSS comment now attributes the container form to the ticket (the desktop dims rows); the implementation commit message's supersession story corrected by amend — ticket 10's table prescribed `anchorBelow` for the new-chat git row in BOTH revisions and `attach_overlay_below` predates ticket 10's implementation, so the drift was ticket 10's code (one hard-coded `anchorAbove` shared by both mounts), not a desktop move (this ticket's own note mis-cites :234-235 as "pinned above").
- Adjudicated, no action: `measure_trigger`'s height budget (≥180px-fits-below, clamp 640) stays unported — the ticket asks for the placement passthrough only ("Base UI's positioner is no-flip by contract, so 'below' is a placement prop, not new geometry code"), and the web's pre-existing `.picker-list` 224px cap already carries `list_budget`'s `clamp(0, 224)`; the footer Layer B device chip keeps start per the ticket's parenthetical; the footer's ProjectChip stays end (its shipped placement, no desktop counterpart to mirror).
