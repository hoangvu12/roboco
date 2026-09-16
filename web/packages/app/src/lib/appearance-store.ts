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

/**
 * Appearance preferences — the web peer of the desktop's device-local
 * appearance settings (crates/ui/src/appearance.rs + settings/appearance.rs):
 * an appearance mode, an independent light/dark theme variant pair, an accent
 * selection, and a surface preference. They live in browser storage scoped to
 * the serving origin ("These settings stay in this browser") and apply live —
 * every mutation re-installs the theme on the document root.
 *
 * Web scope cuts vs desktop (spec §Theme scope): builtin variants only, no
 * custom theme library, no interface font/size pickers, no new-thread
 * background.
 */

/** The persisted mode choice; `system` follows `prefers-color-scheme`. */
export type AppearanceMode = "system" | "light" | "dark";

/** Accent selection: the variant's authored accent, or one of the 7 presets. */
export type AccentSelection = "themeDefault" | AccentPresetId;

/** Surface policy resolved against the variant's recommended treatment. */
export type SurfacePreference = "themeDefault" | "frosted" | "opaque";

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
export const SURFACE_PREFERENCES: readonly SurfacePreference[] = ["themeDefault", "frosted", "opaque"];

interface PersistedPreferences extends AppearancePreferences {
  readonly version: 1;
}

const STORAGE_KEY = "roboco.appearance.v1";

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
 * The surface treatment in effect: the explicit choice, or the theme
 * author's recommendation when "themeDefault" (SurfacePreference::resolve).
 */
export function resolveSurfaceTreatment(surface: SurfacePreference, variant: ThemeVariant): SurfaceTreatment {
  switch (surface) {
    case "frosted":
      return "frosted";
    case "opaque":
      return "opaque";
    default:
      return variant.recommendedSurfaceTreatment;
  }
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
    case "frosted":
      return "Frosted";
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
    case "frosted":
      return "Theme-colored glass where supported.";
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

const ACCENT_IDS: readonly string[] = accentPresets.map((preset) => preset.id);

function isAppearanceMode(value: unknown): value is AppearanceMode {
  return value === "system" || value === "light" || value === "dark";
}

function isAccentSelection(value: unknown): value is AccentSelection {
  return value === "themeDefault" || (typeof value === "string" && ACCENT_IDS.includes(value));
}

function isSurfacePreference(value: unknown): value is SurfacePreference {
  return value === "themeDefault" || value === "frosted" || value === "opaque";
}

/** A variant id is only valid for the appearance it was authored for. */
function variantForAppearance(id: unknown, appearance: Appearance): string | null {
  if (typeof id !== "string") {
    return null;
  }
  const variant = findVariant(id);
  return variant !== undefined && variant.appearance === appearance ? variant.id : null;
}

export class AppearanceStore {
  readonly #storage: StorageLike;
  #preferences: AppearancePreferences = DEFAULT_APPEARANCE;
  readonly #listeners = new Set<() => void>();

  constructor(options: { storage?: StorageLike } = {}) {
    this.#storage =
      options.storage ?? (globalThis as { localStorage?: StorageLike }).localStorage ?? memoryStorage();
    this.#load();
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
    this.#persist();
    for (const listener of this.#listeners) {
      listener();
    }
  }

  #load(): void {
    const raw = this.#storage.getItem(STORAGE_KEY);
    if (raw === null) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<PersistedPreferences>;
      if (parsed.version !== 1) {
        throw new Error("unknown version");
      }
      const lightVariant = variantForAppearance(parsed.lightVariant, "light") ?? DEFAULT_APPEARANCE.lightVariant;
      const darkVariant = variantForAppearance(parsed.darkVariant, "dark") ?? DEFAULT_APPEARANCE.darkVariant;
      this.#preferences = {
        mode: isAppearanceMode(parsed.mode) ? parsed.mode : DEFAULT_APPEARANCE.mode,
        lightVariant,
        darkVariant,
        accent: isAccentSelection(parsed.accent) ? parsed.accent : DEFAULT_APPEARANCE.accent,
        surface: isSurfacePreference(parsed.surface) ? parsed.surface : DEFAULT_APPEARANCE.surface,
      };
    } catch {
      this.#storage.removeItem(STORAGE_KEY);
    }
  }

  #persist(): void {
    const persisted: PersistedPreferences = { version: 1, ...this.#preferences };
    this.#storage.setItem(STORAGE_KEY, JSON.stringify(persisted));
  }
}

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}
