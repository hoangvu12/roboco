/**
 * `RbSelect` — the settings dropdown primitive on Base UI's Select (font
 * size, font family, title harness/model pickers — small fixed option
 * sets). Their desktop equivalents are plain dropdowns, so Select's
 * built-in focus-walking keyboard is the closest match and the least
 * motion/geometry-sensitive surface in the app (blueprint §6.5).
 *
 * The parity rules, encoded ONCE:
 * - **`alignItemWithTrigger: false` (hard-coded):** Select's default `true`
 *   slides the list under the selected row (native-menu geometry); `false`
 *   gives the popover-style below placement our menus use. This is the
 *   Select-family prop a minor could silently regress; the wrapper owns it.
 * - **Positioning:** the shared `noFlipPositionerProps` clamp-only preset
 *   (fixed side, 8px window margin, never flip).
 * - **Keyboard scope:** `overlaySource` registers on the `overlayKeyboard`
 *   registry while open, same as every wrapper.
 * - The trigger/portal/popup/item parts carry no parity rules of their
 *   own, so they ship as verbatim re-exports (`RbSelectTrigger` and
 *   friends below) — the README rule-5 import boundary without inventing
 *   wrappers; `RbSelect` owns exactly the Root/Positioner behavior.
 */

import { useState, type ComponentProps, type ReactNode } from "react";
import { Select, type SelectRootProps } from "@base-ui/react/select";
import { noFlipPositionerProps } from "./positioning";
import { useOverlayKeyboardSource } from "./overlay";

export interface RbSelectProps<Value, Multiple extends boolean | undefined = false>
  extends Omit<SelectRootProps<Value, Multiple>, "children"> {
  /** Registers this name on the `overlayKeyboard` registry while open. */
  readonly overlaySource?: string;
  /**
   * The Select children: `RbSelectTrigger` (with the value/caret markup)
   * plus `RbSelectPositioner` + `RbSelectPopup` carrying the list.
   */
  readonly children: ReactNode;
}

/**
 * `RbSelect` — Root with the open state shadowed so the overlayKeyboard
 * wiring can observe it without stealing the consumer's control: a
 * consumer-supplied `open` wins; uncontrolled consumers ride the shadow.
 */
export function RbSelect<Value, Multiple extends boolean | undefined = false>(props: RbSelectProps<Value, Multiple>) {
  const { overlaySource, open, onOpenChange, children, ...root } = props;
  const [shadowOpen, setShadowOpen] = useState(false);
  useOverlayKeyboardSource(overlaySource, open ?? shadowOpen);
  return (
    <Select.Root<Value, Multiple>
      {...root}
      open={open ?? shadowOpen}
      onOpenChange={(next, details) => {
        setShadowOpen(next);
        onOpenChange?.(next, details);
      }}
    >
      {children}
    </Select.Root>
  );
}

/**
 * `RbSelectPositioner` — the dropdown's placement: `alignItemWithTrigger:
 * false` (popover-style below placement) on the shared clamp-only preset.
 */
export function RbSelectPositioner(
  props: Omit<
    ComponentProps<typeof Select.Positioner>,
    "alignItemWithTrigger" | "side" | "align" | "collisionAvoidance" | "collisionPadding" | "positionMethod"
  >,
) {
  return (
    <Select.Positioner
      {...noFlipPositionerProps({ side: "bottom", align: "start" })}
      alignItemWithTrigger={false}
      {...props}
    />
  );
}

// ---------------------------------------------------------------------------
// Verbatim part re-exports (components/README.md rule 5)
// ---------------------------------------------------------------------------

/**
 * Raw Select parts, re-exported so `@base-ui` never leaks past `base/`
 * (README rule 5): the styled Trigger, the Portal transport, the Popup
 * list, and the option Item. Pure presentation with no parity rules to
 * encode — `RbSelect` and `RbSelectPositioner` own the contract.
 */
export const RbSelectTrigger = Select.Trigger;
export const RbSelectPortal = Select.Portal;
export const RbSelectPopup = Select.Popup;
export const RbSelectItem = Select.Item;
