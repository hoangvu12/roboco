/**
 * The effective reduced-motion read (upstream #642, web-shaped).
 *
 * The web client already snaps to CSS `prefers-reduced-motion` — system-follow
 * is native to every consumer. What #642 adds is the explicit pin: a
 * `reduceMotion` preference persisted through the appearance store that
 * OVERRIDES the media query when pinned on/off. This module is the one place
 * that resolves the two, so every JS consumer (the hand-driven tweens, the
 * tool-motion clock, the dock pumps, the artwork store) obeys the pin while
 * the stylesheet's `@media (prefers-reduced-motion: reduce)` blocks keep
 * following the OS under `:root:not([data-reduced-motion="off"])`.
 */

import { useEffect, useState } from "react";
import { resolveReducedMotion, type ReduceMotion } from "./appearance-store";
import { uiSettings } from "../state/ui-settings";

const REDUCE_QUERY = "(prefers-reduced-motion: reduce)";

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
 * The resolved flag right now: the stored pin over the live media query.
 * `motion::resolve` with the background-pause arm omitted (a browser cannot
 * observe app-window focus; that row is desktop-only).
 */
export function effectiveReducedMotion(
  preference: ReduceMotion = uiSettings.getSnapshot().reduceMotion,
): boolean {
  return resolveReducedMotion(preference, mediaPrefersReducedMotion());
}

/**
 * The effective flag as live React state: reactive to the media query AND the
 * pin's settings write.
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
    return () => {
      query?.removeEventListener("change", onChange);
      unsubscribe();
    };
  }, []);
  return reduced;
}
