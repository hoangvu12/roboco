import { useCallback, useSyncExternalStore } from "react";
import { PHONE_MAX_WIDTH } from "./layout";

/**
 * The one responsive primitive (research item 5; tickets 49 §2.1 and 50 §2.5):
 * live matchMedia as a React value, breakpoints only — it re-renders on flip.
 *
 * Every width branch resolves through this module rather than a raw
 * `window.matchMedia` call or an `innerWidth` compare: the CSS phone blocks
 * and the shell's layout math key on the same 768/769 boundary
 * (`PHONE_MAX_WIDTH`), and `innerWidth` and the media query can disagree by
 * rounding — the dead-band class of bug the sidebar toggle's comment in
 * `app-shell.tsx` documents. Container queries are deliberately not used: a
 * second system would drift against the same boundary.
 */

/** Promoted verbatim from the local hook `transcript.tsx` used to carry. */
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

/** The stylesheet's phone boundary (`PHONE_MAX_WIDTH`), as a live value. */
export function useIsPhone(): boolean {
  return useMediaQuery(`(max-width: ${PHONE_MAX_WIDTH}px)`);
}

/** The ≥769 complement — the desktop blocks' boundary. */
export function useIsDesktop(): boolean {
  return useMediaQuery(`(min-width: ${PHONE_MAX_WIDTH + 1}px)`);
}
