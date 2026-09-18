/**
 * The tree-sidebar split's pure math — ports of `crates/ui/src/files/preview.rs`:
 * the split constants (`:35-39`), the wide/narrow layout rules (`:326-328`,
 * `:475-477`), and the `TreeSidebarMotion` openness sampler (`:94-133`).
 * The resize-drag sample and edge bounce the handle reuses live in
 * `state/layout.ts` (the pane seam's port of the same `motion.rs` source).
 */

import { motion } from "@roboco/theme";

/** `TREE_SPLIT_DEFAULT` (preview.rs:36). */
export const TREE_SPLIT_DEFAULT = 286;
/** `TREE_SPLIT_MIN` (preview.rs:37). */
export const TREE_SPLIT_MIN = 220;
/** `TREE_SPLIT_MAX` (preview.rs:38). */
export const TREE_SPLIT_MAX = 360;
/** `TREE_SPLIT_HITBOX_HALF_WIDTH` (preview.rs:39) — 20px total hit target. */
export const TREE_SPLIT_HITBOX_HALF_WIDTH = 10;
/** `WIDE_BREAKPOINT` (preview.rs:35). */
export const WIDE_BREAKPOINT = 680;
/** `preview.rs:475` — the narrow-layout floor. */
const NARROW_TREE_MIN = 152;
/** The narrow sidebar takes 44% of the surface (preview.rs:477). */
const NARROW_TREE_FRACTION = 0.44;

/** `clampTreeWidth` — the free-drag range. */
export function clampTreeWidth(width: number): number {
  return Math.min(Math.max(width, TREE_SPLIT_MIN), TREE_SPLIT_MAX);
}

/** `narrow_tree_width` — `(surfaceWidth * 0.44).clamp(152, treeWidth)`. */
export function narrowTreeWidth(surfaceWidth: number, treeWidth: number): number {
  return Math.min(Math.max(surfaceWidth * NARROW_TREE_FRACTION, NARROW_TREE_MIN), treeWidth);
}

/** `is_wide` — the split layout breakpoint. */
export function isWide(surfaceWidth: number): boolean {
  return surfaceWidth >= WIDE_BREAKPOINT;
}

// ---------------------------------------------------------------------------
// The openness sampler (TreeSidebarMotion, preview.rs:94-133)
// ---------------------------------------------------------------------------

const RESIZE_SPEC = motion.specs.find((spec) => spec.name === "resize") ?? {
  name: "resize",
  durationMs: 200,
  delayMs: 0,
  curve: "easeOut",
};
const RESIZE_CURVE = motion.curves[RESIZE_SPEC.curve] ?? ([0, 0, 0.58, 1] as const);
/** The web's `speed_scale()` — 1 (no speed preference on the web client). */
export const SPEED_SCALE = 1;

/** `motion.rs::CubicBezier::eval`, compact port (see state/layout.ts's twin). */
function cubicBezierEval(
  curve: readonly [number, number, number, number],
  x: number,
): number {
  const [x1, y1, x2, y2] = curve;
  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;
  const sampleX = (t: number): number => ((ax * t + bx) * t + cx) * t;
  const sampleY = (t: number): number => ((ay * t + by) * t + cy) * t;
  const sampleDX = (t: number): number => (3 * ax * t + 2 * bx) * t + cx;
  // Newton-Raphson on x(t), bisection fallback (f32 exactness not needed).
  let t = x;
  for (let step = 0; step < 8; step += 1) {
    const err = sampleX(t) - x;
    if (Math.abs(err) < 1e-6) {
      return sampleY(t);
    }
    const d = sampleDX(t);
    if (Math.abs(d) < 1e-6) {
      break;
    }
    t -= err / d;
  }
  let lo = 0;
  let hi = 1;
  for (let step = 0; step < 32; step += 1) {
    const mid = (lo + hi) / 2;
    if (sampleX(mid) < x) {
      lo = mid;
    } else {
      hi = mid;
    }
  }
  return (lo + hi) / 2;
}

/** `motion::RESIZE.progress(t)` — the eased fraction of the resize curve. */
export function resizeProgress(raw: number): number {
  return cubicBezierEval(RESIZE_CURVE, Math.min(Math.max(raw, 0), 1));
}

/**
 * `TreeSidebarMotion` (preview.rs:94-133): openness is independent of the
 * dragged width, so resizing stays direct. Layout changes (a breakpoint
 * crossing) and a reduced-motion user snap immediately; only an explicit
 * `animateTo` (the toggle click) transitions, and it reverses from the
 * CURRENT openness rather than an endpoint.
 */
export class TreeSidebarMotion {
  #target: boolean | null = null;
  #from = 0;
  #started: number | null = null;

  /**
   * The frame's openness. Returns `[openness, animating]`; the caller keeps
   * requesting frames while `animating`.
   */
  sample(visible: boolean, now: number, reduced: boolean): [number, boolean] {
    const end = visible ? 1 : 0;
    const duration = RESIZE_SPEC.durationMs * SPEED_SCALE;
    // Layout and activation changes are immediate; only a toggle animates.
    if (reduced || this.#target !== visible) {
      this.#target = visible;
      this.#started = null;
      return [end, false];
    }
    const started = this.#started;
    if (started !== null) {
      const raw = (now - started) / duration;
      if (raw < 1) {
        return [this.#from + (end - this.#from) * resizeProgress(raw), true];
      }
      this.#started = null;
    }
    return [end, false];
  }

  /** Begin a transition from the current openness toward `visible`. */
  animateTo(previous: boolean, visible: boolean, now: number): void {
    this.#from = this.sample(previous, now, false)[0];
    this.#target = visible;
    this.#started = now;
  }
}
