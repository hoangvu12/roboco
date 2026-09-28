import { createContext, useContext } from "react";

/**
 * The floating-card containment context — the web peer of the desktop's
 * `popover/contained.rs` (ticket 24): a dialog card whose anchored menus
 * must stay INSIDE it, flipping to the roomier side instead of sliding
 * over their trigger or spilling past the card's edges.
 *
 * The host (the settings dialog) provides its card element; the popover
 * wrappers (`RbPopover`, `RbSelectPositioner`) consume it and swap their
 * clamp-only positioning for the contained preset (`containedPositionerProps`
 * in `positioning.ts`): the boundary becomes the card, and the side flips
 * when the card lacks room on the preferred one. Consumers outside a host
 * see `null` and keep the window-clamped, never-flip contract — nothing
 * changes for the composer pickers, the sidebar menus, or any other
 * popover in the app.
 *
 * Base UI's `collisionBoundary` accepts a live `Element`, so a `RefObject`
 * read at render time tracks the mounted card without re-providing.
 */
export const ContainedBoundsContext = createContext<HTMLElement | null>(null);

/** The containment boundary, or null outside a host that provides one. */
export function useContainedBounds(): HTMLElement | null {
  return useContext(ContainedBoundsContext);
}
