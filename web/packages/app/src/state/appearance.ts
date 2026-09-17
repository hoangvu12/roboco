import { useSyncExternalStore } from "react";
import type { Appearance } from "@roboco/theme";
import { AppearanceStore, type AppearancePreferences, resolveAppearance } from "../lib/appearance-store";
import { applyAppearanceToDocument } from "../theme";

/**
 * The browser-scoped appearance store singleton plus its live application:
 * `initAppearance` installs the stored preferences on the document root
 * before first paint (no dark-to-light flash), then re-applies on every
 * preference change and every OS light/dark move while the mode is
 * `system` — the web peer of the desktop's `appearance::observe_window`.
 */
export const appearanceStore = new AppearanceStore();

const subscribeStore = (listener: () => void) => appearanceStore.subscribe(listener);
const getSnapshot = () => appearanceStore.getSnapshot();

export function useAppearance(): AppearancePreferences {
  return useSyncExternalStore(subscribeStore, getSnapshot);
}

const DARK_QUERY = "(prefers-color-scheme: dark)";

function darkMedia(): { matches: boolean; addEventListener?: (type: string, listener: () => void) => void; removeEventListener?: (type: string, listener: () => void) => void } | null {
  const matchMedia = (
    globalThis as {
      matchMedia?: (query: string) => {
        matches: boolean;
        addEventListener?: (type: string, listener: () => void) => void;
        removeEventListener?: (type: string, listener: () => void) => void;
      };
    }
  ).matchMedia;
  return matchMedia === undefined ? null : matchMedia(DARK_QUERY);
}

/** The OS light/dark state, reactive while any reader is mounted. */
export function useSystemAppearance(): Appearance {
  const subscribe = (listener: () => void) => {
    const media = darkMedia();
    media?.addEventListener?.("change", listener);
    return () => media?.removeEventListener?.("change", listener);
  };
  return useSyncExternalStore(subscribe, () => (darkMedia()?.matches === false ? "light" : "dark"));
}

/**
 * The appearance that actually paints: the stored mode resolved against the
 * OS state. Surfaces that need to pick appearance-dependent assets (the
 * file-type icons' `dark/` tree) read this, not the raw preference.
 */
export function useResolvedAppearance(): Appearance {
  const preferences = useAppearance();
  const system = useSystemAppearance();
  return resolveAppearance(preferences.mode, system);
}

function systemAppearance(): Appearance {
  return darkMedia()?.matches === false ? "light" : "dark";
}

/** Boot wiring for main.tsx; returns a teardown (unused by the app shell). */
export function initAppearance(): () => void {
  const apply = () => applyAppearanceToDocument(appearanceStore.getSnapshot(), systemAppearance());
  apply();
  const unsubscribe = appearanceStore.subscribe(apply);
  const media = darkMedia();
  media?.addEventListener?.("change", apply);
  return () => {
    unsubscribe();
    media?.removeEventListener?.("change", apply);
  };
}
