/**
 * `RbMenu` / `RbContextMenu` — action menus WITHOUT a search field on
 * Base UI's Menu (sidebar view-options, history author/column menus, editor
 * context items; the pointer-positioned chat/space/link right-click menus).
 * The keyboard model split is the wrapper's load-bearing rule (blueprint
 * §6.5): search-driven pickers use `RbPopover` + the consumer's
 * `menuStep`/`classifyKey` cursor logic; only focus-walking, typeahead
 * menus come here.
 *
 * The parity rules, encoded ONCE:
 * - **`modal: false` (hard-coded):** Menu's default is `true`, which would
 *   trap focus, lock page scroll, and occlude — none of which the desktop's
 *   menus do (popover.rs has no focus trap; gap row 87). This is the one
 *   prop a Base UI minor could silently regress; the wrapper owns it.
 * - **`loopFocus: true`, `highlightItemOnHover: true`** (Base UI defaults,
 *   restated for the record): wrap-around walking matches `menuStep`'s
 *   Euclidean remainder; hover carries the cursor.
 * - **Positioning:** the same `noFlipPositionerProps` clamp-only preset as
 *   `RbPopover` (fixed side, 8px window margin, never flip). The Context
 *   Menu positioner starts at the pointer (right-click/long-press) —
 *   `menu_at` geometry from the component itself.
 * - **Rows:** `RbMenuItem` wears our `.menu-row` class verbatim —
 *   10/8/6/8 metrics, `HOVER_FADE` wash — with `[data-highlighted]`
 *   carrying the `card_selected_bg()` wash via CSS (popover.rs:752-765,
 *   the shipped one-tone behavior).
 * - **Exit window / occlusion:** the popup carries `.rb-popover-popup`, so
 *   the `[data-open]`/`[data-closed]` motion hooks and the dead
 *   hit-testing + `::after` occluder of popover.rs:406 apply unchanged.
 * - **Keyboard scope:** `overlaySource` registers on the `overlayKeyboard`
 *   registry while open, same as `RbPopover`.
 */

import { useState, type CSSProperties, type ReactNode } from "react";
import { Menu, type MenuItemProps, type MenuPositionerProps, type MenuRootProps } from "@base-ui/react/menu";
import {
  ContextMenu,
  type ContextMenuPositionerProps,
  type ContextMenuRootProps,
} from "@base-ui/react/context-menu";
import {
  anchorHelperPlacement,
  exitMotionMs,
  noFlipPositionerProps,
  type AnchorHelperId,
  type AnchorPlacement,
} from "./positioning";
import { useOverlayKeyboardSource } from "./overlay";

export interface RbMenuProps {
  /** Controlled open — every parity consumer is controlled. */
  readonly open: boolean;
  /** Base UI's change event verbatim. */
  readonly onOpenChange: NonNullable<MenuRootProps["onOpenChange"]>;
  /** Fires when the open/close animations have fully completed. */
  readonly onOpenChangeComplete?: MenuRootProps["onOpenChangeComplete"];
  /** Imperative `unmount`/`close`. */
  readonly actionsRef?: MenuRootProps["actionsRef"];
  /** The placement, or an old `popover-anchor.ts` helper name. Defaults to `anchorBelow`. */
  readonly placement?: AnchorPlacement | AnchorHelperId;
  /** The caller's gap for `anchorBelowGap`. */
  readonly gap?: number;
  /** The anchor to position against; defaults to Base UI's trigger. */
  readonly anchor?: MenuPositionerProps["anchor"];
  /** The card's class list beyond the base `.popover-card`. */
  readonly cardClassName?: string;
  readonly role?: string;
  readonly ariaLabel?: string;
  /** Registers this name on the `overlayKeyboard` registry while open. */
  readonly overlaySource?: string;
  /** The desktop's `motion::speed_scale`; rescales the exit duration. */
  readonly motionSpeed?: number;
  readonly children: ReactNode;
}

/** `RbMenu` — Root+Portal+Positioner+Popup with the parity preset. */
export function RbMenu(props: RbMenuProps) {
  useOverlayKeyboardSource(props.overlaySource, props.open);
  const placement: AnchorPlacement =
    props.placement === undefined || typeof props.placement === "string"
      ? anchorHelperPlacement((props.placement ?? "anchorBelow") as AnchorHelperId, props.gap)
      : props.placement;
  const motionStyle =
    props.motionSpeed === undefined
      ? undefined
      : ({ "--rb-motion-menu-out": `${exitMotionMs(props.motionSpeed)}ms` } as CSSProperties);
  return (
    <Menu.Root
      open={props.open}
      onOpenChange={props.onOpenChange}
      onOpenChangeComplete={props.onOpenChangeComplete}
      actionsRef={props.actionsRef}
      modal={false}
      loopFocus
      highlightItemOnHover
    >
      <Menu.Portal>
        <Menu.Positioner {...noFlipPositionerProps(placement)} anchor={props.anchor}>
          <Menu.Popup
            className={`rb-popover-popup ${props.cardClassName ?? "popover-card"}`}
            style={motionStyle}
            role={props.role}
            aria-label={props.ariaLabel}
          >
            {props.children}
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  );
}

/**
 * `RbMenuItem` — one action row on our `.menu-row` recipe (popover.rs:713);
 * Base UI's `[data-highlighted]` wears the selected wash via CSS.
 * Checkbox/radio/submenu parts stay raw `Menu.*` imports at the consumer
 * until a surface needs them pre-wired.
 */
export function RbMenuItem(props: MenuItemProps) {
  const { className, ...rest } = props;
  return <Menu.Item className={`menu-row ${className ?? ""}`} {...rest} />;
}

export interface RbContextMenuProps {
  /** Base UI's change event verbatim (right-click opens, outside/escape closes). */
  readonly onOpenChange?: ContextMenuRootProps["onOpenChange"];
  /** Controlled open, if the consumer drives it; otherwise uncontrolled. */
  readonly open?: boolean;
  /** Registers this name on the `overlayKeyboard` registry while open. */
  readonly overlaySource?: string;
  /**
   * The ContextMenu children: the Trigger wrapper around the right-clicked
   * content, then `RbContextMenuPositioner` + `ContextMenu.Popup` (raw —
   * the popup needs `.rb-popover-popup` on its className for the exit
   * hooks).
   */
  readonly children: ReactNode;
}

/**
 * `RbContextMenu` — the pointer-positioned menu Root (`menu_at`, clamp-only
 * at the pointer, popover.rs:621-638). Base UI's Context Menu omits the
 * `modal` prop outright (right-click menus are never modal), so the
 * non-modal rule holds by the library's own design. Uncontrolled like
 * Base UI's own — the wrapper shadows the open state only so the
 * overlayKeyboard wiring can observe it (a consumer-supplied `open` wins).
 */
export function RbContextMenu(props: RbContextMenuProps) {
  const [shadowOpen, setShadowOpen] = useState(false);
  useOverlayKeyboardSource(props.overlaySource, props.open ?? shadowOpen);
  return (
    <ContextMenu.Root
      open={props.open ?? shadowOpen}
      onOpenChange={(next, details) => {
        setShadowOpen(next);
        props.onOpenChange?.(next, details);
      }}
    >
      {props.children}
    </ContextMenu.Root>
  );
}

/**
 * `RbContextMenuPositioner` — the clamp-only preset for pointer menus: keep
 * the fixed placement, shift inside the 8px window margin, never flip
 * (`menu_at` has no gap, hence the 0 offset default).
 */
export function RbContextMenuPositioner(
  props: Omit<
    ContextMenuPositionerProps,
    "side" | "align" | "collisionAvoidance" | "collisionPadding" | "positionMethod" | "sideOffset"
  > & { readonly sideOffset?: number },
) {
  const { sideOffset, ...rest } = props;
  return (
    <ContextMenu.Positioner
      {...noFlipPositionerProps({ side: "bottom", align: "start", sideOffset: sideOffset ?? 0 })}
      {...rest}
    />
  );
}
