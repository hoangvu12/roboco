import { motion } from "@roboco/theme";

/**
 * The sidebar tween's settle contract (ticket 57a) — the pure half of the
 * cadence that replaced the per-frame pump. The desktop evaluates
 * `sidebar_now()` INSIDE render (one scalar, GPU-side); the web's peer is a
 * CSS `width` transition the BROWSER interpolates — zero React frames, zero
 * re-rasters between the flip and `transitionend`. This module owns the two
 * pieces other code needs to coordinate with that glide:
 *
 * - the SIGNAL (`sidebarTweenSignal` / `sidebarTweenActive()`): true from the
 *   flip commit until the transition settles — the deferral flag tickets
 *   59/63 (and any later per-frame work) consult so nothing re-renders or
 *   re-rasters under the moving column;
 * - the remask cadence's ONE predicate (`remaskDue` + `HeroRemaskGate`):
 *   the hero re-rasters only on settle, artwork/effect change, or — once
 *   settled — a real geometry change. While the tween runs, the cutout hole
 *   is tracked by the raster-window CSS (the readiness layer holds the
 *   pre-flip bitmap centered on the gliding hero), so geometry ticks are
 *   absorbed, never painted.
 *
 * Pure by design (no `now`, no DOM) so the unit tests drive the timeline
 * directly; `ConversationPage` arms/settles the signal, the hero's
 * ResizeObservers route through the gate.
 */

/** `motion::RESIZE` — the 200ms curve the sidebar glide and the hero's width transition ride. */
export const SIDEBAR_GLIDE_MS =
  motion.specs.find((spec) => spec.name === "resize")?.durationMs ?? 200;

/**
 * The settle cap: `transitionend` is the settle signal, but it can be
 * swallowed (the hero unmounts mid-glide, the tab hides, a style kill
 * cancels without a successor) — the page still settles within this bound.
 */
export const SIDEBAR_SETTLE_CAP_MS = 120;

/** Why a remask was requested — the settle predicate's only input. */
export type RemaskReason = "settle" | "artwork" | "hero-geometry" | "surface-geometry";

/**
 * The remask cadence's ONE predicate (§2.2): a remask is due only for
 * settle (the `transitionend` commit — the snap to the true raster) and for
 * artwork/effect changes (which re-fix the raster window themselves). Hero
 * and composer-surface geometry changes remask only ONCE the tween has
 * settled — during the glide the raster window tracks the hole, so the
 * per-frame geometry ticks the observers report are absorbed, not painted.
 */
export function remaskDue(tweenActive: boolean, reason: RemaskReason): boolean {
  if (reason === "settle" || reason === "artwork") {
    return true;
  }
  return !tweenActive;
}

/**
 * The sidebar tween's armed/settled flag: armed at the flip commit (the one
 * state flip that starts the CSS transition), settled at `transitionend`.
 * A mid-glide reversal re-arms (the CSS transition retargets from the
 * painted width — the desktop's `sidebar_now()` capture semantics).
 */
export class SidebarTweenSignal {
  #active = false;

  /** The flip landed and the CSS transition is (re)starting. */
  arm(): void {
    this.#active = true;
  }

  /** `transitionend` (or the settle cap / a disarm) — the glide is over. */
  settle(): void {
    this.#active = false;
  }

  isActive(): boolean {
    return this.#active;
  }
}

/** The page-scoped sidebar tween signal (tickets 59/63 defer work while it is active). */
export const sidebarTweenSignal = new SidebarTweenSignal();

/**
 * `true` while the sidebar's 200ms glide is running (the hero's cutout and
 * any deferrable per-frame work should ride it out, not fight it).
 */
export function sidebarTweenActive(): boolean {
  return sidebarTweenSignal.isActive();
}

/**
 * The hero remask scheduler: every remask request (dep-change, hero
 * ResizeObserver, composer-surface ResizeObserver, settle) routes through
 * the settle predicate against the LIVE tween signal, so the component's
 * observers can report every frame they see and still paint nothing during
 * the tween.
 */
export class HeroRemaskGate {
  #remask: () => void;

  constructor(remask: () => void) {
    this.#remask = remask;
  }

  note(reason: RemaskReason): void {
    if (remaskDue(sidebarTweenSignal.isActive(), reason)) {
      this.#remask();
    }
  }
}
