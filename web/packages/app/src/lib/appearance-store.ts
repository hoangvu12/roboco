import {
  accentPresets,
  findVariant,
  themeVariants,
  type AccentPresetId,
  type Appearance,
  type SurfaceTreatment,
  type ThemeVariant,
} from "@roboco/theme";
import type { StorageLike } from "./engine-store";
import { UiSettingsStore, uiSettings, type UiSettings } from "../state/ui-settings";

/**
 * Appearance preferences — the web peer of the desktop's device-local
 * appearance settings (crates/ui/src/appearance.rs + settings/appearance.rs):
 * an appearance mode, an independent light/dark theme variant pair, an accent
 * selection, and a surface preference. They live in the consolidated
 * `state/ui-settings.ts` store (`appearance`, `themeSelection.light`/`.dark`,
 * `accent`, `surface`), browser-scoped to the serving origin ("These settings
 * stay in this browser") and applied live — every mutation re-installs the
 * theme on the document root.
 *
 * What stays here is the part settings storage cannot know: a variant id is
 * only valid for the appearance it was authored for, so the pair is validated
 * against the theme registry on the way out of the store, not on the way in.
 *
 * Web scope cuts vs desktop (spec §Theme scope): builtin variants only, no
 * custom theme library, no interface font/size pickers, no new-thread
 * background.
 */

/** The persisted mode choice; `system` follows `prefers-color-scheme`. */
export type AppearanceMode = "system" | "light" | "dark";

/** Accent selection: the variant's authored accent, or one of the 7 presets. */
export type AccentSelection = "themeDefault" | AccentPresetId;

/**
 * Surface policy. Deliberate web deviation from the desktop (which offers
 * Theme default / Frosted / Opaque, settings/appearance.rs:522-527): the
 * frosted choice is removed by product decision and the resolution is forced
 * opaque — see `resolveSurfaceTreatment`.
 */
export type SurfacePreference = "themeDefault" | "opaque";

export interface AppearancePreferences {
  readonly mode: AppearanceMode;
  /** Variant id used whenever the light appearance is active. */
  readonly lightVariant: string;
  /** Variant id used whenever the dark appearance is active. */
  readonly darkVariant: string;
  readonly accent: AccentSelection;
  readonly surface: SurfacePreference;
}

/** The desktop's `ThemeSelection::default` + default accent/surface. */
export const DEFAULT_APPEARANCE: AppearancePreferences = {
  mode: "system",
  lightVariant: "roboco-light",
  darkVariant: "roboco-dark",
  accent: "themeDefault",
  surface: "themeDefault",
};

export const APPEARANCE_MODES: readonly AppearanceMode[] = ["system", "light", "dark"];
export const SURFACE_PREFERENCES: readonly SurfacePreference[] = ["themeDefault", "opaque"];

/** The user's choice combined with the OS state (appearance.rs `resolve`). */
export function resolveAppearance(mode: AppearanceMode, system: Appearance): Appearance {
  switch (mode) {
    case "light":
      return "light";
    case "dark":
      return "dark";
    default:
      return system;
  }
}

/** The variant id for a resolved appearance; the pair is independent. */
export function resolveVariantId(preferences: AppearancePreferences, appearance: Appearance): string {
  return appearance === "dark" ? preferences.darkVariant : preferences.lightVariant;
}

/**
 * The surface treatment in effect. A product decision (2026-09-17): the web
 * never frosts — the treatment is forced opaque regardless of the stored
 * preference or the theme author's recommendation (both default themes
 * recommend frosted, so honoring "themeDefault" would leave the app frosted
 * anyway). A deliberate deviation from the desktop's
 * `SurfacePreference::resolve`; the one seam a future flip restores.
 */
export function resolveSurfaceTreatment(): SurfaceTreatment {
  return "opaque";
}

/**
 * The selector's variant list for one appearance: only variants authored
 * for it, registry order (family by family) — the desktop's
 * `variants_for(appearance)`.
 */
export function variantChoices(appearance: Appearance): readonly ThemeVariant[] {
  return themeVariants.filter((variant) => variant.appearance === appearance);
}

/** Row label for an appearance mode card (AppearanceMode::label). */
export function appearanceModeLabel(mode: AppearanceMode): string {
  switch (mode) {
    case "system":
      return "System";
    case "light":
      return "Light";
    case "dark":
      return "Dark";
  }
}

/** Row label for a surface choice (surface_label). */
export function surfaceLabel(surface: SurfacePreference): string {
  switch (surface) {
    case "themeDefault":
      return "Theme default";
    case "opaque":
      return "Opaque";
  }
}

/** Helper copy under the accent row (accent_helper). */
export function accentHelper(accent: AccentSelection): string {
  if (accent === "themeDefault") {
    return "Theme default · Uses the palette's intended color.";
  }
  const preset = accentPresets.find((entry) => entry.id === accent);
  return `${preset?.label ?? accent} · Controls, glyphs, selections, code, and activity.`;
}

/** Helper copy under the glass row (surface_helper). */
export function surfaceHelper(surface: SurfacePreference, resolved: SurfaceTreatment): string {
  switch (surface) {
    case "themeDefault":
      return `Uses this theme's ${resolved} default.`;
    case "opaque":
      return "Solid surfaces for every theme.";
  }
}

/** The swatch color for an accent choice under a resolved appearance. */
export function accentSwatchColor(accent: AccentSelection, variant: ThemeVariant): string {
  if (accent === "themeDefault") {
    return variant.accent.primary;
  }
  const preset = accentPresets.find((entry) => entry.id === accent);
  if (preset === undefined) {
    return variant.accent.primary;
  }
  return variant.appearance === "dark" ? preset.dark : preset.light;
}

/** A variant id is only valid for the appearance it was authored for. */
function variantForAppearance(id: unknown, appearance: Appearance): string | null {
  if (typeof id !== "string") {
    return null;
  }
  const variant = findVariant(id);
  return variant !== undefined && variant.appearance === appearance ? variant.id : null;
}

export interface AppearanceStoreOptions {
  /** The settings store to read through; defaults to the app's singleton. */
  readonly settings?: UiSettingsStore;
  /** Convenience for tests: a settings store over this storage. */
  readonly storage?: StorageLike;
}

export class AppearanceStore {
  readonly #settings: UiSettingsStore;
  #preferences: AppearancePreferences;
  readonly #listeners = new Set<() => void>();

  constructor(options: AppearanceStoreOptions = {}) {
    this.#settings =
      options.settings ??
      (options.storage === undefined ? uiSettings : new UiSettingsStore({ storage: options.storage }));
    this.#preferences = project(this.#settings.getSnapshot());
    this.#settings.subscribe(() => {
      this.#apply(project(this.#settings.getSnapshot()));
    });
  }

  getSnapshot(): AppearancePreferences {
    return this.#preferences;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  setMode(mode: AppearanceMode): void {
    this.#update({ mode });
  }

  /** The desktop's set_theme: variant ids are pinned per appearance. */
  setVariant(appearance: Appearance, variantId: string): void {
    if (variantForAppearance(variantId, appearance) === null) {
      return;
    }
    this.#update(appearance === "dark" ? { darkVariant: variantId } : { lightVariant: variantId });
  }

  setAccent(accent: AccentSelection): void {
    this.#update({ accent });
  }

  setSurface(surface: SurfacePreference): void {
    this.#update({ surface });
  }

  #update(patch: Partial<AppearancePreferences>): void {
    const next: AppearancePreferences = { ...this.#preferences, ...patch };
    // Appearance is a discrete choice, never a drag — write it straight
    // through. Re-projecting afterwards keeps the validated view authoritative.
    this.#settings.update(
      {
        appearance: next.mode,
        themeSelection: { light: next.lightVariant, dark: next.darkVariant },
        accent: next.accent,
        surface: next.surface,
      },
      "immediate",
    );
    this.#apply(project(this.#settings.getSnapshot()));
  }

  #apply(next: AppearancePreferences): void {
    if (
      next.mode === this.#preferences.mode &&
      next.lightVariant === this.#preferences.lightVariant &&
      next.darkVariant === this.#preferences.darkVariant &&
      next.accent === this.#preferences.accent &&
      next.surface === this.#preferences.surface
    ) {
      return;
    }
    this.#preferences = next;
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

/**
 * The settings snapshot's appearance slice, with each variant id checked
 * against the registry it has to come from — a hand-edited or stale id (a
 * removed theme, a dark variant stored as the light one) falls back to that
 * side's default without disturbing the other.
 */
function project(settings: UiSettings): AppearancePreferences {
  return {
    mode: settings.appearance,
    lightVariant:
      variantForAppearance(settings.themeSelection.light, "light") ?? DEFAULT_APPEARANCE.lightVariant,
    darkVariant:
      variantForAppearance(settings.themeSelection.dark, "dark") ?? DEFAULT_APPEARANCE.darkVariant,
    accent: settings.accent,
    surface: settings.surface,
  };
}
