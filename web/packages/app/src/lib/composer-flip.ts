/**
 * The composer's compact↔expanded flip, ported whole from
 * `crates/ui/src/composer.rs:107-144`.
 *
 * The desktop's discipline, which this port must keep:
 *
 * - the decision input is a **layout-stable width pair** — the text's
 *   unwrapped widest-line width vs the compact-mode wrap capacity. Neither
 *   number may depend on which mode is currently rendered: the post-flip
 *   measured width "differs per mode and would feed back into the decision"
 *   (the ping-pong that used to crash the web port with React's nested-update
 *   limit);
 * - a newline always expands;
 * - a too-narrow composer (`capacity < MIN_COMPACT_INPUT_WIDTH`) always
 *   expands — there is no compact layout to fall back to;
 * - hysteresis: expanding and collapsing share no boundary (`COLLAPSE_HYSTERESIS`
 *   slack), so a text width right at the flip threshold cannot oscillate;
 * - while the layout is being interactively resized, an expanded composer
 *   stays expanded until the widths settle (`RESIZE_SETTLE_MS`).
 */

/// `COLLAPSE_HYSTERESIS` (composer.rs:112) — hysteresis slack for the
/// expanded→compact flip.
export const COLLAPSE_HYSTERESIS = 32;

/// `MIN_COMPACT_INPUT_WIDTH` (composer.rs:102) — below this capacity the
/// composer always expands.
export const MIN_COMPACT_INPUT_WIDTH = 200;

/// `RESIZE_SETTLE_MS` (composer.rs:116) — how long an interactive resize
/// defers the collapse decision.
export const RESIZE_SETTLE_MS = 150;

/// `COMPOSER_WIDTH_EPSILON` (composer.rs:94) — subpixel noise a width change
/// must exceed to count as a resize.
export const COMPOSER_WIDTH_EPSILON = 0.5;

/**
 * `composer_flip` (composer.rs:126-144): compact expands only when
 * `textWidth > capacity`; expanded collapses only when
 * `textWidth < capacity - COLLAPSE_HYSTERESIS`. `resizing` keeps an expanded
 * composer expanded until the drag settles.
 */
export function composerFlip(
  expanded: boolean,
  textWidth: number,
  capacity: number,
  hasNewline: boolean,
  resizing: boolean,
): boolean {
  if (hasNewline) {
    return true;
  }
  if (capacity < MIN_COMPACT_INPUT_WIDTH) {
    return true;
  }
  if (expanded) {
    return resizing || textWidth >= capacity - COLLAPSE_HYSTERESIS;
  }
  return textWidth > capacity;
}

/**
 * The desktop's `width_changed_at` bookkeeping (composer.rs:7240-7244): a
 * capacity move larger than the epsilon marks an interactive resize in
 * flight; the flag stays armed for `RESIZE_SETTLE_MS`.
 */
export function resizeSettling(
  changedAtMs: number | null,
  nowMs: number,
  previousCapacity: number,
  capacity: number,
): { readonly resizing: boolean; readonly changedAtMs: number | null } {
  if (Math.abs(capacity - previousCapacity) > COMPOSER_WIDTH_EPSILON) {
    return { resizing: false, changedAtMs: nowMs };
  }
  return {
    resizing: changedAtMs !== null && nowMs - changedAtMs < RESIZE_SETTLE_MS,
    changedAtMs,
  };
}
