import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { PHONE_MAX_WIDTH } from "./layout";
import { effectiveReducedMotion } from "../lib/reduced-motion";
import { uiSettings } from "./ui-settings";

/**
 * The one shared media-hook module (ticket 49, research M8(b)1): every
 * width branch in the app resolves through HERE so the JS breakpoint and
 * the stylesheet's can never drift apart. `PHONE_MAX_WIDTH` is the
 * stylesheet's boundary — every phone block in `app.css` is
 * `@media (max-width: 768px)` and every desktop block `(min-width: 769px)`
 * — and both queries below derive from that one constant, so a resize
 * flips JS and CSS in the same paint. (innerWidth and a media query can
 * disagree by rounding — the dead-band class of bug the old one-shot
 * `window.matchMedia` in `app-shell.tsx` documented.)
 *
 * The hook is the transcript's own 12-line `useMediaQuery` (the only
 * width-aware hook the app had), promoted: matchMedia subscribe +
 * `useSyncExternalStore`, server snapshot `false` — the desktop arm is the
 * default wherever nothing can be measured. The alternative —
 * `@base-ui/react/unstable-use-media-query` — works but adds an
 * unstable-named subpath import where this hook already existed; the
 * research's recommendation, kept.
 */

/** The phone arm's query — the stylesheet's `max-width: 768px` blocks. */
export const PHONE_QUERY = `(max-width: ${PHONE_MAX_WIDTH}px)`;

/** The desktop arm's query — the exact complement of `PHONE_QUERY`. */
export const DESKTOP_QUERY = `(min-width: ${PHONE_MAX_WIDTH + 1}px)`;

/** Live matchMedia as a React value (breakpoints only — it re-renders on flip). */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (listener: () => void) => {
      const mql = window.matchMedia(query);
      mql.addEventListener("change", listener);
      return () => mql.removeEventListener("change", listener);
    },
    [query],
  );
  const getSnapshot = useCallback(() => window.matchMedia(query).matches, [query]);
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}

/** `≤768px` — the phone layer (the sheet/drawer arms, the phone sidebar). */
export function useIsPhone(): boolean {
  return useMediaQuery(PHONE_QUERY);
}

/** `≥769px` — the desktop layer (the exact complement of `useIsPhone`). */
export function useIsDesktop(): boolean {
  return useMediaQuery(DESKTOP_QUERY);
}

// ---------------------------------------------------------------------------
// Reduced motion
// ---------------------------------------------------------------------------

/**
 * The EFFECTIVE reduced-motion flag (upstream #642): the media query under
 * system-follow, or the persisted pin when set on/off. Reactive to both the
 * query and the settings store that holds the pin.
 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(effectiveReducedMotion);
  useEffect(() => {
    const onChange = () => setReduced(effectiveReducedMotion());
    onChange();
    const query =
      typeof window.matchMedia === "function"
        ? window.matchMedia("(prefers-reduced-motion: reduce)")
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
