/**
 * The effective reduced-motion read (upstream #642, web-shaped + wpn-07's
 * background-pause arm).
 *
 * The web client already snaps to CSS `prefers-reduced-motion` — system-follow
 * is native to every consumer. What #642 adds is the explicit pin: a
 * `reduceMotion` preference persisted through the appearance store that
 * OVERRIDES the media query when pinned on/off. This module is the one place
 * that resolves the two, so every JS consumer (the hand-driven tweens, the
 * tool-motion clock, the dock pumps, the artwork store) obeys the pin while
 * the stylesheet's `@media (prefers-reduced-motion: reduce)` blocks keep
 * following the OS under `:root:not([data-reduced-motion="off"])`.
 *
 * wpn-07 reverses this module's oldest recorded omission: the desktop's
 * `motion::resolve` has a fourth arm — `reduced || (pause_in_background &&
 * !active)` — and the web used to skip it on the rationale that "a browser
 * cannot observe app-window focus". It can: `document.hasFocus()` observes
 * DOCUMENT focus, which is the honest web equivalent for a tab. So the arm
 * folds into `effectiveReducedMotion` below (the persisted
 * `pauseAnimationsInBackground` setting + the live focus read), and the
 * focus/visibility monitor re-resolves it whenever focus moves: JS consumers
 * that poll the read inherit the arm for free, subscribers are re-notified,
 * and the root `data-animations-paused` attr carries the flip to the
 * stylesheet, whose pause scope (app.css) holds the infinite loops mid-phase
 * with `animation-play-state: paused` — deliberately NOT
 * `data-reduced-motion="on"`, whose `animation: none` snaps spinner cells to
 * their dim rest state (a visible jump on refocus); paused loops resume
 * exactly where they stopped.
 */

import { useEffect, useState } from "react";
import { resolveReducedMotion, type ReduceMotion } from "./appearance-store";
import { uiSettings } from "../state/ui-settings";

const REDUCE_QUERY = "(prefers-reduced-motion: reduce)";

/** The root attr the monitor writes (the stylesheet's pause scope keys on it). */
const ANIMATIONS_PAUSED_ATTR = "data-animations-paused";

let cachedQuery: MediaQueryList | null = null;

/** The media query's current value (false off-browser; the list is cached). */
export function mediaPrefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  cachedQuery ??= window.matchMedia(REDUCE_QUERY);
  return cachedQuery.matches;
}

/**
 * The live document-focus read (node-safe): off-browser, or without
 * `hasFocus`, the document counts as focused — the arm must never fire on a
 * read the environment cannot make.
 */
function liveDocumentHasFocus(): boolean {
  return (
    typeof document !== "undefined" &&
    typeof document.hasFocus === "function" &&
    document.hasFocus()
  );
}

/**
 * The resolved flag right now: `motion::resolve` with all four arms — the
 * stored pin over the live media query, then the background-pause arm
 * (`pauseAnimationsInBackground && !document.hasFocus()`), exactly the
 * desktop's `reduced || (pause_in_background && !active)` with document focus
 * standing in for window activation. The focus read is a construction seam
 * (tests inject it; the monitor reads it live).
 */
export function effectiveReducedMotion(
  preference: ReduceMotion = uiSettings.getSnapshot().reduceMotion,
  documentHasFocus: () => boolean = liveDocumentHasFocus,
): boolean {
  return (
    resolveReducedMotion(preference, mediaPrefersReducedMotion()) ||
    (uiSettings.getSnapshot().pauseAnimationsInBackground && !documentHasFocus())
  );
}

/**
 * The effective flag as live React state: reactive to the media query, the
 * pin's settings write, AND the background-pause arm's focus flips (the
 * monitor's channel below).
 */
export function useEffectiveReducedMotion(): boolean {
  const [reduced, setReduced] = useState(effectiveReducedMotion);
  useEffect(() => {
    const onChange = () => setReduced(effectiveReducedMotion());
    onChange();
    const query =
      typeof window.matchMedia === "function"
        ? window.matchMedia(REDUCE_QUERY)
        : null;
    query?.addEventListener("change", onChange);
    const unsubscribe = uiSettings.subscribe(onChange);
    const unsubscribePause = subscribeToBackgroundPause(onChange);
    return () => {
      query?.removeEventListener("change", onChange);
      unsubscribe();
      unsubscribePause();
    };
  }, []);
  return reduced;
}

// ---------------------------------------------------------------------------
// The background-pause monitor (wpn-07)
// ---------------------------------------------------------------------------

/** The arm's change listeners — focus flips ride no other channel. */
const backgroundPauseListeners = new Set<() => void>();

/**
 * Subscribe to background-pause flips: the monitor notifies here when the arm
 * engages or releases, so a consumer of the effective flag re-resolves beside
 * its existing sources (`useEffectiveReducedMotion` does; the media query and
 * the settings store carry their own changes).
 */
export function subscribeToBackgroundPause(listener: () => void): () => void {
  backgroundPauseListeners.add(listener);
  return () => {
    backgroundPauseListeners.delete(listener);
  };
}

/** The pause arm alone — the condition the root attr records. */
function animationsPausedNow(): boolean {
  return uiSettings.getSnapshot().pauseAnimationsInBackground && !liveDocumentHasFocus();
}

/**
 * The focus/visibility monitor — the desktop's `window_activation_changed`,
 * web-shaped. Window `focus`/`blur` plus `visibilitychange` all funnel into
 * ONE refresh that resolves through `document.hasFocus()`, never the event
 * payloads: blur fires spuriously when the user clicks the URL bar or
 * devtools while the document still holds focus, and a tab switch can arrive
 * without a blur the monitor saw. A flip of the arm moves the root
 * `data-animations-paused` attr (the stylesheet's pause scope) and re-notifies
 * the subscribers above; the settings store is watched too, so the row's own
 * write lands while the document is unfocused. Returns a teardown the app
 * shell never needs (the monitor lives for the page load).
 */
export function initBackgroundPauseMonitor(): () => void {
  if (typeof window === "undefined" || typeof document === "undefined") {
    return () => {};
  }
  const root = document.documentElement;
  const writeAttr = (paused: boolean): void => {
    if (paused) {
      root.setAttribute(ANIMATIONS_PAUSED_ATTR, "");
    } else {
      root.removeAttribute(ANIMATIONS_PAUSED_ATTR);
    }
  };
  // Install establishes the attr without notifying: no flip has happened yet,
  // and every subscriber re-resolves on its own mount (the desktop's `apply`
  // likewise refreshes only when the flag actually flips).
  let applied = animationsPausedNow();
  writeAttr(applied);
  const refresh = (): void => {
    const paused = animationsPausedNow();
    if (paused === applied) {
      return;
    }
    applied = paused;
    writeAttr(paused);
    for (const listener of [...backgroundPauseListeners]) {
      listener();
    }
  };
  window.addEventListener("focus", refresh);
  window.addEventListener("blur", refresh);
  document.addEventListener("visibilitychange", refresh);
  const unsubscribeSettings = uiSettings.subscribe(refresh);
  return () => {
    window.removeEventListener("focus", refresh);
    window.removeEventListener("blur", refresh);
    document.removeEventListener("visibilitychange", refresh);
    unsubscribeSettings();
  };
}
