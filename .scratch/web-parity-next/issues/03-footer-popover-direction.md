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

**Status:** ready-for-agent

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

- [ ] Canvas checkout/ref cards open below the row; draft footer unchanged
- [ ] Device/project canvas popovers end-aligned
- [ ] Ref list dims during SwitchRef
- [ ] Tests + full app suite green
