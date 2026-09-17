/**
 * The anchored-menu placement family — port of the desktop's
 * `anchored_menu*`/`menu_at` helpers (`crates/ui/src/popover.rs:420-638`).
 *
 * Every variant pins the card to a corner of the trigger (or an explicit
 * point) and clamps it back inside the window with an 8px margin — the
 * web equivalent of gpui's `snap_to_window_with_margin(px(8.0))`. There is
 * no side-flip: the chosen side is fixed by which helper the caller picks,
 * not computed at render time.
 *
 * Coordinates are viewport CSS pixels, ready for `position: fixed` styling:
 * `{ left, top }` pins the card's top-left, `{ right, top }` its top-right,
 * `{ left, bottom }` / `{ right, bottom }` the bottom-anchored variants.
 */

/** `snap_to_window_with_margin(px(8.0))` — the clamp margin on both axes. */
export const SNAP_MARGIN = 8;
/** The default trigger→card gap (`anchored_menu_below`/`_above` use pt/pb 6). */
export const ANCHOR_GAP = 6;

/** The trigger subset the helpers read (any `DOMRect` qualifies). */
export interface AnchorRect {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly width: number;
}

export interface CardSize {
  readonly width: number;
  readonly height: number;
}

export interface Viewport {
  readonly width: number;
  readonly height: number;
}

export interface AnchorPoint {
  readonly x: number;
  readonly y: number;
}

export interface PlacementTopLeft {
  readonly left: number;
  readonly top: number;
}

export interface PlacementTopRight {
  readonly right: number;
  readonly top: number;
}

export interface PlacementBottomLeft {
  readonly left: number;
  readonly bottom: number;
}

export interface PlacementBottomRight {
  readonly right: number;
  readonly bottom: number;
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(value, low), Math.max(low, high));
}

function liveViewport(): Viewport {
  return { width: window.innerWidth, height: window.innerHeight };
}

function viewportOr(viewport: Viewport | undefined): Viewport {
  return viewport ?? liveViewport();
}

/** `anchored_menu_below` — the card's top-left at the trigger's, `6px` down. */
export function anchorBelow(
  trigger: AnchorRect,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementTopLeft {
  return anchorBelowGap(trigger, card, viewport, gap);
}

/** `anchored_menu_below_gap` — `anchorBelow` with a caller-chosen gap. */
export function anchorBelowGap(
  trigger: AnchorRect,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementTopLeft {
  const view = viewportOr(viewport);
  return {
    left: clamp(trigger.left, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    top: clamp(trigger.bottom + gap, SNAP_MARGIN, view.height - card.height - SNAP_MARGIN),
  };
}

/** `anchored_menu_below_end` — the card's top-right at the trigger's. */
export function anchorBelowEnd(
  trigger: AnchorRect,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementTopRight {
  const view = viewportOr(viewport);
  return {
    right: clamp(view.width - trigger.right, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    top: clamp(trigger.bottom + gap, SNAP_MARGIN, view.height - card.height - SNAP_MARGIN),
  };
}

/** `anchored_menu_above` — the card's bottom-left, `6px` above the trigger. */
export function anchorAbove(
  trigger: AnchorRect,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementBottomLeft {
  const view = viewportOr(viewport);
  return {
    left: clamp(trigger.left, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    bottom: clamp(
      view.height - trigger.top + gap,
      SNAP_MARGIN,
      view.height - card.height - SNAP_MARGIN,
    ),
  };
}

/** `anchored_menu_above_at` — `anchorAbove` at an explicit point (the
 * completions caret anchor), inside a relative trigger. */
export function anchorAboveAt(
  point: AnchorPoint,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementBottomLeft {
  const view = viewportOr(viewport);
  return {
    left: clamp(point.x, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    bottom: clamp(
      view.height - point.y + gap,
      SNAP_MARGIN,
      view.height - card.height - SNAP_MARGIN,
    ),
  };
}

/** `anchored_menu_above_end` — the card's bottom-right at the trigger's. */
export function anchorAboveEnd(
  trigger: AnchorRect,
  card: CardSize,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementBottomRight {
  const view = viewportOr(viewport);
  return {
    right: clamp(view.width - trigger.right, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    bottom: clamp(
      view.height - trigger.top + gap,
      SNAP_MARGIN,
      view.height - card.height - SNAP_MARGIN,
    ),
  };
}

/** `full_width_menu_above` — spans the trigger's width, bottom-anchored. */
export function fullWidthMenuAbove(
  trigger: AnchorRect,
  viewport?: Viewport,
  gap: number = ANCHOR_GAP,
): PlacementBottomLeft & { width: number } {
  const view = viewportOr(viewport);
  return {
    left: clamp(trigger.left, SNAP_MARGIN, view.width - trigger.width - SNAP_MARGIN),
    bottom: clamp(
      view.height - trigger.top + gap,
      SNAP_MARGIN,
      view.height - SNAP_MARGIN,
    ),
    width: trigger.width,
  };
}

/** `menu_at` — the card's top-left at an explicit window position (context
 * menus). Clamp-only: it never flips above on overflow (`popover.rs:621-638`). */
export function menuAt(
  point: AnchorPoint,
  card: CardSize,
  viewport?: Viewport,
): PlacementTopLeft {
  const view = viewportOr(viewport);
  return {
    left: clamp(point.x, SNAP_MARGIN, view.width - card.width - SNAP_MARGIN),
    top: clamp(point.y, SNAP_MARGIN, view.height - card.height - SNAP_MARGIN),
  };
}
