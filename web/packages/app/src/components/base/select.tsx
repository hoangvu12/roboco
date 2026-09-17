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
 * - The trigger/value/items parts stay raw `Select.*` imports at the
 *   consumer — they are pure styled elements with no parity rules to
 *   encode; `RbSelect` owns exactly the Root/Positioner behavior.
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
   * The Select children: `Select.Trigger` (with `Value`/`Icon`) plus
   * `RbSelectPositioner` + `Select.Popup` carrying the list.
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
