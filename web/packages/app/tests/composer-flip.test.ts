import { describe, expect, it } from "vitest";
import {
  COLLAPSE_HYSTERESIS,
  composerFlip,
  COMPOSER_WIDTH_EPSILON,
  MIN_COMPACT_INPUT_WIDTH,
  resizeSettling,
  RESIZE_SETTLE_MS,
} from "../src/lib/composer-flip";

/**
 * The compact↔expanded flip decision, mirroring `composer.rs`'s own unit
 * tests by name (`flip_decision`, `flip_hysteresis_band_prevents_oscillation`,
 * `resize_expands_live_but_defers_collapse`). The web port must make the same
 * calls the desktop does — the ping-pong crash this rule prevents is exactly
 * the bug that took the web composer down.
 */

describe("flip_decision", () => {
  it("compact stays compact while the text fits, expands on overflow", () => {
    // Fits in the pill → compact stays compact.
    expect(composerFlip(false, 150.0, 300.0, false, false)).toBe(false);
    // Overflow → expand.
    expect(composerFlip(false, 320.0, 300.0, false, false)).toBe(true);
    // Newline always expands (either mode, even mid-resize).
    expect(composerFlip(false, 10.0, 300.0, true, false)).toBe(true);
    expect(composerFlip(true, 10.0, 300.0, true, true)).toBe(true);
    // Narrow column (< MIN_COMPACT_INPUT_WIDTH) always expands.
    expect(composerFlip(false, 10.0, MIN_COMPACT_INPUT_WIDTH - 1, false, false)).toBe(true);
    expect(composerFlip(false, 10.0, MIN_COMPACT_INPUT_WIDTH, false, false)).toBe(false);
  });
});

describe("flip_hysteresis_band_prevents_oscillation", () => {
  it("shares no boundary between the expand and collapse thresholds", () => {
    const cap = 300.0;
    // Text just over capacity expands…
    expect(composerFlip(false, cap + 1.0, cap, false, false)).toBe(true);
    // …and the SAME width, now expanded, does NOT collapse back — the
    // collapse threshold sits COLLAPSE_HYSTERESIS below the expand one.
    expect(composerFlip(true, cap + 1.0, cap, false, false)).toBe(true);
    // Anywhere inside the band the two modes are both stable (no width in
    // (cap - 32, cap] flips in either direction).
    const inBand = cap - COLLAPSE_HYSTERESIS + 1.0;
    expect(composerFlip(false, inBand, cap, false, false)).toBe(false);
    expect(composerFlip(true, inBand, cap, false, false)).toBe(true);
    // Comfortably under the band → collapses.
    expect(composerFlip(true, cap - COLLAPSE_HYSTERESIS - 1.0, cap, false, false)).toBe(false);
  });
});

describe("resize_expands_live_but_defers_collapse", () => {
  it("expands immediately under resize, collapses only once settled", () => {
    // A compact composer expands immediately as its text or controls stop
    // fitting, even while the divider is moving.
    expect(composerFlip(false, 500.0, 300.0, false, true)).toBe(true);
    expect(composerFlip(false, 10.0, 150.0, false, true)).toBe(true);
    // An expanded composer waits for the drag to settle before collapsing,
    // avoiding mode chatter while the user reverses direction.
    expect(composerFlip(true, 0.0, 300.0, false, true)).toBe(true);
    // Once settled, the same wide layout may collapse.
    expect(composerFlip(false, 500.0, 300.0, false, false)).toBe(true);
    expect(composerFlip(true, 0.0, 300.0, false, false)).toBe(false);
    // The narrow column stays expanded either way.
    expect(composerFlip(false, 10.0, 150.0, false, false)).toBe(true);
  });
});

describe("resizeSettling", () => {
  it("arms the settle window on an epsilon-exceeding capacity change", () => {
    const now = 1_000;
    // A sub-epsilon move is noise, not a resize.
    const steady = resizeSettling(null, now, 300, 300 + COMPOSER_WIDTH_EPSILON);
    expect(steady.resizing).toBe(false);
    expect(steady.changedAtMs).toBe(null);
    // A real move arms the window (not yet settling — the drag is live).
    const armed = resizeSettling(null, now, 300, 260);
    expect(armed.resizing).toBe(false);
    expect(armed.changedAtMs).toBe(now);
    // After the move, the window holds `resizing` for RESIZE_SETTLE_MS.
    expect(resizeSettling(now, now + 10, 260, 260).resizing).toBe(true);
    expect(resizeSettling(now, now + RESIZE_SETTLE_MS - 1, 260, 260).resizing).toBe(true);
    expect(resizeSettling(now, now + RESIZE_SETTLE_MS, 260, 260).resizing).toBe(false);
    // A never-moved capacity never settles-resizes.
    expect(resizeSettling(null, now, 260, 260).resizing).toBe(false);
  });
});
