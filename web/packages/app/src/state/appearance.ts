import { useEffect, useState, useSyncExternalStore } from "react";
import type { Appearance } from "@roboco/theme";
import type { NewThreadBackgroundEffect, NewThreadComposerBackground } from "./ui-settings";
import { AppearanceStore, type AppearancePreferences, resolveAppearance } from "../lib/appearance-store";
import { applyAppearanceToDocument, applyTypographyToDocument } from "../theme";
import { resolveNewThreadBackground } from "../lib/new-thread-background";
import { useUiSettings, uiSettings } from "./ui-settings";

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
  return useSyncExternalStore(subscribeStore, getSnapshot, getSnapshot);
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
  return useSyncExternalStore(subscribe, systemAppearance, systemAppearance);
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

// ---------------------------------------------------------------------------
// The new-thread background (ticket 15, settings.rs:86-122)
// ---------------------------------------------------------------------------

/** The resolved new-thread hero artwork plus the settings-store effect. */
export interface NewThreadArtwork {
  /** The decoded image to paint, or null while resolving / nothing installed. */
  readonly url: string | null;
  /** The artwork's identity — the readiness fade restarts when it changes. */
  readonly id: string | number | null;
  readonly effect: NewThreadBackgroundEffect;
}

/**
 * Read `newThreadComposerBackground` + `newThreadBackgroundEffect` off the
 * ticket-03 settings store and resolve the artwork to paint: a stored
 * background is only "available" when it actually decodes (SVG is allowed
 * as an attachment but rejected as a background); nothing installed falls
 * back to the bundled `default-new-thread-background.png`. The Appearance
 * UI for setting them is ticket 28's.
 */
export function useNewThreadBackground(): NewThreadArtwork {
  const settings = useUiSettings();
  const setting: NewThreadComposerBackground | null = settings.newThreadComposerBackground;
  const effect = settings.newThreadBackgroundEffect;
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void resolveNewThreadBackground(setting).then((resolved) => {
      if (!cancelled) {
        setUrl(resolved);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [setting?.path, setting?.name]);

  return { url, id: url, effect };
}

/** Non-hook read for code outside React (the id keys the readiness clock). */
export function currentNewThreadBackgroundSetting(): NewThreadComposerBackground | null {
  return uiSettings.getSnapshot().newThreadComposerBackground;
}

/** Boot wiring for main.tsx; returns a teardown (unused by the app shell). */
export function initAppearance(): () => void {
  const apply = () => applyAppearanceToDocument(appearanceStore.getSnapshot(), systemAppearance());
  apply();
  // The interface font/size (ticket 28): applied before first paint with the
  // theme, then re-applied on every settings write — a discrete choice, so
  // any snapshot change carries it.
  const applyTypography = () => {
    const settings = uiSettings.getSnapshot();
    applyTypographyToDocument({ uiFontFamily: settings.uiFontFamily, uiFontSize: settings.uiFontSize });
  };
  applyTypography();
  const unsubscribe = appearanceStore.subscribe(apply);
  const unsubscribeTypography = uiSettings.subscribe(applyTypography);
  const media = darkMedia();
  media?.addEventListener?.("change", apply);
  return () => {
    unsubscribe();
    unsubscribeTypography();
    media?.removeEventListener?.("change", apply);
  };
}
