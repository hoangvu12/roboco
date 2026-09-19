import type { SurfaceTreatment } from "@roboco/theme";
import type { DockFrame } from "./composer-dock";
import { newThreadBackgroundElementOpacity } from "./new-thread-background";

/**
 * The dock glide's de-Reacted pump (ticket 57b, §2.3) — the per-frame half
 * of the route-transition choreography. The desktop ticks `DockState` and
 * hands the frame to the composer INSIDE its GPU render loop; the web's old
 * peer re-rendered the whole page per frame (`setDockFrameState` +
 * `setDockPump` in every rAF callback for the 420/470 ms glide plus the
 * 0.32 s panel handoff — the audit's S1(b) #1). The replacement keeps ONE
 * rAF loop but publishes the animated channels as CSS CUSTOM PROPERTIES on
 * the conversation column — Style/Composite only, ZERO React state per
 * frame. The JSX consumes every channel through
 * `var(--rb-dock-…, <settled fallback>)`, so a mid-glide React render (an
 * async transcript load, a phase crossing) can never clobber the live
 * values, and the settle commit's fallbacks take over the instant the vars
 * are cleared.
 *
 * This module owns the pieces other code coordinates with:
 *
 * - the CHANNEL SET (`dockGlideChannels` + `writeDockGlideVars`/
 *   `clearDockGlideVars`): the exact numbers the DOM writes carry — the
 *   transcript's opacity/rise, the column's fade-through, the composer
 *   wrapper's gliding width, the hero's dissolve opacity (the parity
 *   formula), and the chrome crossfade pair;
 * - the PHASE SEQUENCER (`DockMountSequencer`): the glide's ONLY React
 *   state writes — the chrome rows' mount/unmount crossings, the discrete
 *   per-navigation events that keep the rows unmounted at channel 0 (the
 *   web's never-duplicate-popovers contract) without per-frame renders;
 * - the SIGNAL (`dockGlideSignal` / `dockGlideActive()`): true from the
 *   navigation commit until the glide (and any surviving panel handoff)
 *   settles — the flag tickets 59/63 consult so nothing re-renders under
 *   the moving composer.
 *
 * The channel math is pure (a frame in, the var values out) so the unit
 * tests drive a whole glide at 60 Hz in node and prove the load split: the
 * DOM writes scale with the frame count, the state writes do not.
 */

// ---------------------------------------------------------------------------
// The channel set (the pump's per-frame DOM writes)
// ---------------------------------------------------------------------------

/** One pump frame's animated values — each maps to one CSS custom property. */
export interface DockGlideChannels {
  /** `--rb-dock-transcript-opacity` — the transcript channel (`.chat-body`). */
  readonly transcriptOpacity: number;
  /** `--rb-dock-transcript-rise` — the entry rise in px (`.chat-body`'s translateY). */
  readonly transcriptRise: number;
  /** `--rb-dock-pane-opacity` — the column's panel-handoff fade-through. */
  readonly paneOpacity: number;
  /** `--rb-dock-composer-width` — the composer wrapper's gliding width (px). */
  readonly composerWidth: number;
  /** `--rb-dock-hero-opacity` — the hero's dissolve element opacity (the parity product). */
  readonly heroOpacity: number;
  /** `--rb-dock-chrome-new` — the new-thread selector row's crossfade. */
  readonly chromeNewThread: number;
  /** `--rb-dock-chrome-session` — the session footer's crossfade. */
  readonly chromeSession: number;
}

/**
 * Map one dock frame to the channel values the DOM writes carry. The
 * transcript rise and the hero opacity are the page's own formulas
 * (`8·(1 − transcript)` and `new_thread_background_element_opacity`),
 * transcribed here so the pump's writes stay parity-exact and testable.
 */
export function dockGlideChannels(
  frame: DockFrame,
  paneOpacity: number,
  composerWidth: number,
  surface: SurfaceTreatment,
): DockGlideChannels {
  return {
    transcriptOpacity: frame.visuals.transcript,
    transcriptRise: 8 * (1 - frame.visuals.transcript),
    paneOpacity,
    composerWidth,
    heroOpacity: newThreadBackgroundElementOpacity(frame.visuals.dissolve, 1, surface),
    chromeNewThread: frame.visuals.selectors,
    chromeSession: frame.visuals.footer,
  };
}

/** The DOM half's target: anything with the two custom-property methods. */
export interface CustomPropertyTarget {
  setProperty(name: string, value: string): void;
  removeProperty(name: string): void;
}

/** Every custom property the pump owns — the clear list is the settle handoff. */
export const DOCK_GLIDE_VARS = [
  "--rb-dock-transcript-opacity",
  "--rb-dock-transcript-rise",
  "--rb-dock-pane-opacity",
  "--rb-dock-composer-width",
  "--rb-dock-hero-opacity",
  "--rb-dock-chrome-new",
  "--rb-dock-chrome-session",
] as const;

/**
 * Write one frame's channels as custom properties on the column (Style
 * only — the de-Reacted pump's DOM half). Values are converged at settle,
 * so the clear that follows the settle commit is visually a no-op.
 */
export function writeDockGlideVars(target: CustomPropertyTarget | null, channels: DockGlideChannels): void {
  if (target === null) {
    return;
  }
  target.setProperty("--rb-dock-transcript-opacity", `${channels.transcriptOpacity}`);
  target.setProperty("--rb-dock-transcript-rise", `${channels.transcriptRise}px`);
  target.setProperty("--rb-dock-pane-opacity", `${channels.paneOpacity}`);
  target.setProperty("--rb-dock-composer-width", `${channels.composerWidth}px`);
  target.setProperty("--rb-dock-hero-opacity", `${channels.heroOpacity}`);
  target.setProperty("--rb-dock-chrome-new", `${channels.chromeNewThread}`);
  target.setProperty("--rb-dock-chrome-session", `${channels.chromeSession}`);
}

/**
 * Remove the glide's custom properties — the settle commit's JSX fallbacks
 * are authoritative again (the values had converged onto them, so the
 * removal never paints a jump).
 */
export function clearDockGlideVars(target: CustomPropertyTarget | null): void {
  if (target === null) {
    return;
  }
  for (const name of DOCK_GLIDE_VARS) {
    target.removeProperty(name);
  }
}

// ---------------------------------------------------------------------------
// The phase sequencer (the glide's only React state writes)
// ---------------------------------------------------------------------------

/**
 * The chrome rows' mount-crossing detector: the composer mounts the
 * selector row and the footer layers only while their channel is `> 0`
 * (the web's never-duplicate-popovers contract — the hidden one is
 * unmounted, not just hidden). The channels cross zero exactly at their
 * staged windows' edges (composer_dock.rs's non-overlapping ramps), so the
 * crossings are DISCRETE — at most a couple per glide — and every other
 * frame of the glide writes zero React state. The caller publishes the
 * live frame on a crossing so the `> 0` mounts flip in the same commit.
 */
export class DockMountSequencer {
  #selectors: boolean | null = null;
  #footer: boolean | null = null;

  /**
   * Feed one frame; returns whether a chrome mount boolean flipped (the
   * page's ONE discrete publish). The first feed initializes from the
   * pre-glide mounts and never emits.
   */
  crossing(frame: DockFrame): boolean {
    const selectors = frame.visuals.selectors > 0;
    const footer = frame.visuals.footer > 0;
    const previousSelectors = this.#selectors;
    const changed =
      previousSelectors !== null && (selectors !== previousSelectors || footer !== this.#footer);
    this.#selectors = selectors;
    this.#footer = footer;
    return changed;
  }
}

// ---------------------------------------------------------------------------
// The signal (tickets 59/63 defer work while the glide runs)
// ---------------------------------------------------------------------------

/**
 * The dock glide's armed/settled flag: armed when the pump's loop starts
 * (the navigation commit's frame is in flight), settled when the glide —
 * and any panel handoff that outlives it — has converged.
 */
export class DockGlideSignal {
  #active = false;

  /** The navigation's frame is in flight; the loop is writing channels. */
  arm(): void {
    this.#active = true;
  }

  /** The glide converged (or the loop was torn down mid-flight). */
  settle(): void {
    this.#active = false;
  }

  isActive(): boolean {
    return this.#active;
  }
}

/** The page-scoped dock glide signal (tickets 59/63 defer work while it is active). */
export const dockGlideSignal = new DockGlideSignal();

/**
 * `true` while the route-transition glide (composer dock + panel handoff)
 * is running — deferrable per-frame work should ride it out.
 */
export function dockGlideActive(): boolean {
  return dockGlideSignal.isActive();
}
