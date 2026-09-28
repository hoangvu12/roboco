import { afterEach, describe, expect, it, vi } from "vitest";
import type { StorageLike } from "../src/lib/engine-store";
import {
  SETTINGS_SECTION_DEFAULT,
  SETTINGS_SECTION_SLUGS,
  isSettingsSectionSlug,
  settingsIndexTarget,
  settingsSectionFromPath,
} from "../src/state/settings-section";
import {
  SAVE_DEBOUNCE_MS,
  UI_SETTINGS_STORAGE_KEY,
  UiSettingsStore,
} from "../src/state/ui-settings";

/**
 * Web parity of the desktop's remembered Settings section (upstream
 * d268830b): the `/settings` index reopens the section last viewed, the
 * section is persisted as `settingsSection` in the ui-settings store, and
 * unknown or malformed values heal to the General default (the modal
 * redesign's landing page, ticket 26) without touching the rest of the file.
 */

function memoryStorage(): StorageLike & { dump(): Map<string, string> } {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
    dump: () => map,
  };
}

/** A store over a consolidated key holding exactly `fields`. */
function storedWith(fields: Record<string, unknown>): UiSettingsStore {
  const storage = memoryStorage();
  storage.setItem(UI_SETTINGS_STORAGE_KEY, JSON.stringify(fields));
  return new UiSettingsStore({ storage });
}

afterEach(() => {
  vi.useRealTimers();
});

describe("settings-section slugs", () => {
  it("matches the dialog nav's sections, and nothing else", () => {
    for (const slug of SETTINGS_SECTION_SLUGS) {
      expect(isSettingsSectionSlug(slug)).toBe(true);
    }
    // Appshots stays desktop-only; the desktop's "providers" slug name for
    // Harnesses is an alias the web does not carry (its route segment keeps
    // the historic "harnesses" spelling).
    for (const value of ["appshots", "providers", "conversations", "billing", "", null, 42, undefined]) {
      expect(isSettingsSectionSlug(value)).toBe(false);
    }
  });

  it("names the section a /settings/<slug> pathname opens", () => {
    expect(settingsSectionFromPath("/settings/devices")).toBe("devices");
    expect(settingsSectionFromPath("/settings/remote-access")).toBe("remote-access");
    expect(settingsSectionFromPath("/settings/appearance/")).toBe("appearance");
    expect(settingsSectionFromPath("/settings/general")).toBe("general");
    expect(settingsSectionFromPath("/settings/billing")).toBeNull();
    expect(settingsSectionFromPath("/settings")).toBeNull();
    expect(settingsSectionFromPath("/settings/")).toBeNull();
    expect(settingsSectionFromPath("/chat/abc")).toBeNull();
    expect(settingsSectionFromPath("/")).toBeNull();
  });

  it("reopens the remembered section, healing unknowns to the General default", () => {
    expect(settingsIndexTarget("appearance")).toBe("/settings/appearance");
    expect(settingsIndexTarget(SETTINGS_SECTION_DEFAULT)).toBe("/settings/general");
    // The desktop's lenient read heals unknown sections to General — the
    // web's General page is the modal redesign's landing page now.
    expect(settingsIndexTarget("billing")).toBe("/settings/general");
    expect(settingsIndexTarget("appshots")).toBe("/settings/general");
  });
});

describe("remembered settings section in the ui-settings store", () => {
  it("defaults to the desktop's General landing page with no stored data", () => {
    const settings = new UiSettingsStore({ storage: memoryStorage() }).getSnapshot();
    expect(settings.settingsSection).toBe("general");
  });

  it("round-trips every section and persists it through a debounced write", () => {
    vi.useFakeTimers();
    const storage = memoryStorage();
    const store = new UiSettingsStore({ storage });
    for (const slug of SETTINGS_SECTION_SLUGS) {
      store.updateDebounced({ settingsSection: slug });
      expect(store.getSnapshot().settingsSection).toBe(slug);
    }
    store.flush();
    const persisted = JSON.parse(storage.dump().get(UI_SETTINGS_STORAGE_KEY)!) as {
      settingsSection: string;
    };
    expect(persisted.settingsSection).toBe(SETTINGS_SECTION_SLUGS.at(-1));
    // A debounced write still lands once the page idles out.
    store.updateDebounced({ settingsSection: "appearance" });
    vi.advanceTimersByTime(SAVE_DEBOUNCE_MS + 1);
    expect(
      (JSON.parse(storage.dump().get(UI_SETTINGS_STORAGE_KEY)!) as { settingsSection: string })
        .settingsSection,
    ).toBe("appearance");
    // And it reloads as the remembered section.
    expect(
      new UiSettingsStore({ storage }).getSnapshot().settingsSection,
    ).toBe("appearance");
  });

  it("heals unknown, missing, and malformed values without defaulting other fields", () => {
    for (const raw of ["billing", "appshots", 42, null, { section: "devices" }]) {
      const settings = storedWith({ sidebarWidth: 300, settingsSection: raw }).getSnapshot();
      expect(settings.settingsSection).toBe("general");
      expect(settings.sidebarWidth).toBe(300);
    }
    const legacy = storedWith({ sidebarWidth: 300 }).getSnapshot();
    expect(legacy.settingsSection).toBe("general");
    expect(legacy.sidebarWidth).toBe(300);
  });
});
