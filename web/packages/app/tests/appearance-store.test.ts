import { describe, expect, it } from "vitest";
import { findVariant } from "@roboco/theme";
import {
  accentHelper,
  accentSwatchColor,
  AppearanceStore,
  DEFAULT_APPEARANCE,
  resolveAppearance,
  resolveSurfaceTreatment,
  resolveVariantId,
  SURFACE_PREFERENCES,
  supportsBackdropFilter,
  surfaceHelper,
  variantChoices,
} from "../src/lib/appearance-store";
import type { StorageLike } from "../src/lib/engine-store";

type CssGlobal = { supports(property: string, value: string): boolean };

/**
 * Run `body` with a stubbed `CSS.supports`. The test environment is `node`,
 * which has no CSS API at all, so every case that depends on the browser's
 * `backdrop-filter` capability states it explicitly. `supports` of `null`
 * removes the global entirely (the SSR/worker case).
 */
function withCssSupports(
  supports: ((property: string, value: string) => boolean) | null,
  body: () => void,
): void {
  const host = globalThis as { CSS?: CssGlobal };
  const previous = host.CSS;
  if (supports === null) {
    delete host.CSS;
  } else {
    host.CSS = { supports };
  }
  try {
    body();
  } finally {
    if (previous === undefined) {
      delete host.CSS;
    } else {
      host.CSS = previous;
    }
  }
}

/** `withCssSupports` for the common "everything is / nothing is" cases. */
function withBackdropFilter(supported: boolean, body: () => void): void {
  withCssSupports(() => supported, body);
}

function memoryStorage(): StorageLike & { dump(): Map<string, string> } {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
    dump: () => map,
  };
}

describe("AppearanceStore", () => {
  it("defaults to the desktop's ThemeSelection default", () => {
    const store = new AppearanceStore({ storage: memoryStorage() });
    expect(store.getSnapshot()).toEqual(DEFAULT_APPEARANCE);
    expect(store.getSnapshot().mode).toBe("system");
    expect(store.getSnapshot().darkVariant).toBe("roboco-dark");
    expect(store.getSnapshot().lightVariant).toBe("roboco-light");
  });

  it("persists every change and reloads through storage", () => {
    const storage = memoryStorage();
    const store = new AppearanceStore({ storage });
    store.setMode("light");
    store.setVariant("light", "github-light");
    store.setVariant("dark", "nord");
    store.setAccent("pink");
    store.setSurface("frosted");
    const reloaded = new AppearanceStore({ storage });
    expect(reloaded.getSnapshot()).toEqual({
      mode: "light",
      lightVariant: "github-light",
      darkVariant: "nord",
      accent: "pink",
      surface: "frosted",
    });
  });

  it("notifies subscribers on actual changes only", () => {
    const store = new AppearanceStore({ storage: memoryStorage() });
    let fired = 0;
    const unsubscribe = store.subscribe(() => {
      fired += 1;
    });
    store.setMode("dark");
    store.setMode("dark");
    store.setAccent("cyan");
    unsubscribe();
    store.setSurface("opaque");
    expect(fired).toBe(2);
  });

  it("refuses a variant authored for the other appearance", () => {
    const store = new AppearanceStore({ storage: memoryStorage() });
    store.setVariant("light", "nord"); // nord is dark-only
    expect(store.getSnapshot().lightVariant).toBe("roboco-light");
    store.setVariant("dark", "nord");
    expect(store.getSnapshot().darkVariant).toBe("nord");
  });

  it("ignores corrupted legacy state without destroying it", () => {
    // Storage moved to the consolidated ui-settings key; the legacy key is a
    // one-time migration source now, so a corrupt one heals to the defaults
    // and is left exactly where it is for a rollback to find.
    const storage = memoryStorage();
    storage.setItem("roboco.appearance.v1", "{not json");
    expect(new AppearanceStore({ storage }).getSnapshot()).toEqual(DEFAULT_APPEARANCE);
    expect(storage.getItem("roboco.appearance.v1")).toBe("{not json");
  });

  it("migrates the legacy appearance key on first load", () => {
    const storage = memoryStorage();
    storage.setItem(
      "roboco.appearance.v1",
      JSON.stringify({
        version: 1,
        mode: "dark",
        lightVariant: "github-light",
        darkVariant: "nord",
        accent: "pink",
        surface: "frosted",
      }),
    );
    expect(new AppearanceStore({ storage }).getSnapshot()).toEqual({
      mode: "dark",
      lightVariant: "github-light",
      darkVariant: "nord",
      accent: "pink",
      surface: "frosted",
    });
    expect(storage.getItem("roboco.ui-settings.v1")).not.toBe(null);
  });

  it("falls back per-field when persisted values are unknown", () => {
    const storage = memoryStorage();
    storage.setItem(
      "roboco.appearance.v1",
      JSON.stringify({
        version: 1,
        mode: "sepia",
        lightVariant: "nord",
        darkVariant: "ghost-variant",
        accent: "octarine",
        surface: "mirror",
      }),
    );
    expect(new AppearanceStore({ storage }).getSnapshot()).toEqual(DEFAULT_APPEARANCE);
  });
});

describe("appearance resolution", () => {
  it("combines the mode with the OS state (appearance.rs resolve)", () => {
    expect(resolveAppearance("system", "dark")).toBe("dark");
    expect(resolveAppearance("system", "light")).toBe("light");
    expect(resolveAppearance("light", "dark")).toBe("light");
    expect(resolveAppearance("dark", "light")).toBe("dark");
  });

  it("keeps the light and dark variants independent", () => {
    const preferences = { ...DEFAULT_APPEARANCE, lightVariant: "github-light", darkVariant: "dracula" };
    expect(resolveVariantId(preferences, "light")).toBe("github-light");
    expect(resolveVariantId(preferences, "dark")).toBe("dracula");
  });

  it("resolves the surface policy against the theme's recommendation", () => {
    const frosted = findVariant("roboco-dark"); // recommended: frosted
    const opaque = findVariant("nord"); // recommended: opaque
    expect(frosted).toBeDefined();
    expect(opaque).toBeDefined();
    withBackdropFilter(true, () => {
      expect(resolveSurfaceTreatment("themeDefault", frosted!)).toBe("frosted");
      expect(resolveSurfaceTreatment("themeDefault", opaque!)).toBe("opaque");
      expect(resolveSurfaceTreatment("frosted", opaque!)).toBe("frosted");
      expect(resolveSurfaceTreatment("opaque", frosted!)).toBe("opaque");
    });
  });

  /*
   * The browser analog of the desktop's platform branch (Linux gets opaque
   * chrome because compositor blur is not guaranteed): without
   * `backdrop-filter` there is no frost to be had, only a see-through shell.
   */
  it("forces opaque surfaces when the browser cannot composite backdrop-filter", () => {
    const frosted = findVariant("roboco-dark")!;
    const opaque = findVariant("nord")!;
    withBackdropFilter(false, () => {
      expect(supportsBackdropFilter()).toBe(false);
      for (const surface of SURFACE_PREFERENCES) {
        expect(resolveSurfaceTreatment(surface, frosted)).toBe("opaque");
        expect(resolveSurfaceTreatment(surface, opaque)).toBe("opaque");
      }
    });
  });

  it("honors the -webkit- prefixed capability alone", () => {
    const frosted = findVariant("roboco-dark")!;
    withCssSupports(
      (property) => property === "-webkit-backdrop-filter",
      () => {
        expect(supportsBackdropFilter()).toBe(true);
        expect(resolveSurfaceTreatment("themeDefault", frosted)).toBe("frosted");
      },
    );
  });

  it("treats a CSS-less environment as unsupported", () => {
    withCssSupports(null, () => {
      expect(supportsBackdropFilter()).toBe(false);
    });
  });
});

describe("variantChoices", () => {
  it("offers both appearances in registry order", () => {
    // Desktop appearance.rs test: the registry has 10 light / 20 dark builtins.
    expect(variantChoices("light")).toHaveLength(10);
    expect(variantChoices("dark")).toHaveLength(20);
    expect(variantChoices("light").every((variant) => variant.appearance === "light")).toBe(true);
    expect(variantChoices("dark").every((variant) => variant.appearance === "dark")).toBe(true);
    expect(variantChoices("dark")[0]!.id).toBe("roboco-dark");
  });
});

describe("helper copy and swatches", () => {
  it("mirrors the desktop's accent helper text", () => {
    expect(accentHelper("themeDefault")).toBe("Theme default · Uses the palette's intended color.");
    expect(accentHelper("pink")).toBe("Pink · Controls, glyphs, selections, code, and activity.");
  });

  it("mirrors the desktop's surface helper text", () => {
    expect(surfaceHelper("themeDefault", "frosted")).toBe("Uses this theme's frosted default.");
    expect(surfaceHelper("frosted", "opaque")).toBe("Theme-colored glass where supported.");
    expect(surfaceHelper("opaque", "frosted")).toBe("Solid surfaces for every theme.");
  });

  it("picks the swatch color for the resolved appearance", () => {
    const dark = findVariant("roboco-dark")!;
    const light = findVariant("roboco-light")!;
    expect(accentSwatchColor("themeDefault", dark)).toBe(dark.accent.primary);
    expect(accentSwatchColor("pink", dark)).toBe("#f472b6");
    expect(accentSwatchColor("pink", light)).toBe("#be185d");
  });
});
