import { afterEach, describe, expect, it, vi } from "vitest";
import type { StorageLike } from "../src/lib/engine-store";
import {
  JUMP_DEFAULTS,
  LEGACY_APPEARANCE_KEY,
  LEGACY_LAYOUT_KEY,
  LEGACY_SIDEBAR_KEY,
  SAVE_DEBOUNCE_MS,
  UI_SETTINGS_STORAGE_KEY,
  UiSettingsStore,
  defaultUiSettings,
  type UiSettings,
} from "../src/state/ui-settings";

/**
 * The consolidated settings store, against `crates/ui/src/settings.rs`'s
 * `UiSettings` defaults and its `clamped()`/`healed()` rules. These numbers are
 * the parity contract: the same stored file has to mean the same thing in both
 * clients.
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
function storedWith(fields: Record<string, unknown>): UiSettings {
  const storage = memoryStorage();
  storage.setItem(UI_SETTINGS_STORAGE_KEY, JSON.stringify(fields));
  return new UiSettingsStore({ storage }).getSnapshot();
}

afterEach(() => {
  vi.useRealTimers();
});

describe("defaults", () => {
  it("returns every field's documented default with no stored data", () => {
    const settings = new UiSettingsStore({ storage: memoryStorage() }).getSnapshot();
    expect(settings.sidebarWidth).toBe(256);
    expect(settings.sidebarCollapsed).toBe(false);
    expect(settings.rightPaneWidth).toBe(520);
    expect(settings.terminalHeight).toBe(280);
    expect(settings.uiFontSize).toBe(16);
    expect(settings.uiFontFamily).toBe("geist");
    expect(settings.appearance).toBe("system");
    expect(settings.themeSelection).toEqual({ light: "roboco-light", dark: "roboco-dark" });
    expect(settings.accent).toBe("themeDefault");
    expect(settings.surface).toBe("themeDefault");
    expect(settings.soundEnabled).toBe(true);
    expect(settings.notificationsEnabled).toBe(true);
    expect(settings.notificationsBackgroundOnly).toBe(true);
    expect(settings.sidebarOrganization).toBe("inOneList");
    expect(settings.sidebarSort).toBe("lastUpdated");
    expect(settings.filesAutosaveDelayMs).toBe(900);
    expect(settings.filesEditorFontSize).toBe(13);
    expect(settings.gitHistoryColumnWidths).toEqual({ author: 88, date: 88, sha: 74 });
    expect(settings.gitHistoryColumnOrder).toEqual(["author", "date", "sha"]);
    expect(settings.newThreadComposerBackground).toBe(null);
    expect(settings.keymap.jumpSession).toHaveLength(9);
    expect(settings.keymap.jumpSession).toEqual([
      "mod-1",
      "mod-2",
      "mod-3",
      "mod-4",
      "mod-5",
      "mod-6",
      "mod-7",
      "mod-8",
      "mod-9",
    ]);
    expect(settings).toEqual(defaultUiSettings());
  });
});

describe("clamp", () => {
  it("sidebarWidth — clamps into [224, 400] and heals junk to 256", () => {
    expect(storedWith({ sidebarWidth: 50 }).sidebarWidth).toBe(224);
    expect(storedWith({ sidebarWidth: 9999 }).sidebarWidth).toBe(400);
    expect(storedWith({ sidebarWidth: 320 }).sidebarWidth).toBe(320);
    expect(storedWith({ sidebarWidth: Number.NaN }).sidebarWidth).toBe(256);
    expect(storedWith({ sidebarWidth: "wide" }).sidebarWidth).toBe(256);
  });

  it("rightPaneWidth — floors at 360 with no persisted ceiling", () => {
    expect(storedWith({ rightPaneWidth: 100 }).rightPaneWidth).toBe(360);
    // Deliberately un-capped: the live drag clamps against the window, which
    // is unavailable while loading (`settings.rs::clamped`'s min_or).
    expect(storedWith({ rightPaneWidth: 9999 }).rightPaneWidth).toBe(9999);
    expect(storedWith({ rightPaneWidth: "wide" }).rightPaneWidth).toBe(520);
  });

  it("terminalHeight — clamps into [160, 2000]", () => {
    expect(storedWith({ terminalHeight: 50 }).terminalHeight).toBe(160);
    expect(storedWith({ terminalHeight: 99999 }).terminalHeight).toBe(2000);
    expect(storedWith({ terminalHeight: null }).terminalHeight).toBe(280);
  });

  it("uiFontSize — snaps to the nearest offered size", () => {
    expect(storedWith({ uiFontSize: 19 }).uiFontSize).toBe(18);
    expect(storedWith({ uiFontSize: 250 }).uiFontSize).toBe(20);
    expect(storedWith({ uiFontSize: 0 }).uiFontSize).toBe(12);
    expect(storedWith({ uiFontSize: 15 }).uiFontSize).toBe(15);
  });

  it("filesAutosaveDelayMs — clamps into [100, 10000]", () => {
    expect(storedWith({ filesAutosaveDelayMs: 1 }).filesAutosaveDelayMs).toBe(100);
    expect(storedWith({ filesAutosaveDelayMs: 999999 }).filesAutosaveDelayMs).toBe(10000);
    expect(storedWith({ filesAutosaveDelayMs: 1500 }).filesAutosaveDelayMs).toBe(1500);
  });

  it("filesEditorFontSize — clamps into [9, 24]", () => {
    expect(storedWith({ filesEditorFontSize: 2 }).filesEditorFontSize).toBe(9);
    expect(storedWith({ filesEditorFontSize: 99 }).filesEditorFontSize).toBe(24);
  });

  it("gitHistoryColumnWidths — each sub-field clamps to its own bounds", () => {
    expect(
      storedWith({ gitHistoryColumnWidths: { author: 10, date: 1000, sha: 100 } })
        .gitHistoryColumnWidths,
    ).toEqual({ author: 44, date: 180, sha: 100 });
    expect(
      storedWith({ gitHistoryColumnWidths: { author: 999, date: 10, sha: "x" } })
        .gitHistoryColumnWidths,
    ).toEqual({ author: 220, date: 68, sha: 74 });
  });
});

describe("heal", () => {
  it("sidebarOrganization — a stored \"byProject\" heals to \"inOneList\"", () => {
    expect(storedWith({ sidebarOrganization: "byProject" }).sidebarOrganization).toBe("inOneList");
    expect(storedWith({ sidebarOrganization: "byDevice" }).sidebarOrganization).toBe("byDevice");
    expect(storedWith({ sidebarOrganization: "sideways" }).sidebarOrganization).toBe("inOneList");
  });

  it("jumpSession — pads a short list and truncates a long one to 9 slots", () => {
    const short = storedWith({
      keymap: { jumpSession: ["alt-1", "alt-2", "alt-3", "alt-4", "alt-5"] },
    }).keymap.jumpSession;
    expect(short).toHaveLength(9);
    expect(short.slice(0, 5)).toEqual(["alt-1", "alt-2", "alt-3", "alt-4", "alt-5"]);
    expect(short.slice(5)).toEqual(JUMP_DEFAULTS.slice(5));

    const long = storedWith({
      keymap: { jumpSession: Array.from({ length: 12 }, (_, index) => `alt-${index + 1}`) },
    }).keymap.jumpSession;
    expect(long).toHaveLength(9);
    expect(long[8]).toBe("alt-9");
  });

  it("keymap — a shortcut holding the composer's mod-enter goes back to default", () => {
    const keymap = storedWith({
      keymap: { toggleSidebar: "mod-enter", toggleChanges: "mod-shift-k" },
    }).keymap;
    expect(keymap.toggleSidebar).toBe("mod-b");
    expect(keymap.toggleChanges).toBe("mod-shift-k");
  });

  it("gitHistoryColumnOrder — dedups and appends the missing columns", () => {
    expect(storedWith({ gitHistoryColumnOrder: ["sha", "sha", "date"] }).gitHistoryColumnOrder).toEqual([
      "sha",
      "date",
      "author",
    ]);
    expect(storedWith({ gitHistoryColumnOrder: ["nope"] }).gitHistoryColumnOrder).toEqual([
      "author",
      "date",
      "sha",
    ]);
  });

  it("lastProjectActionBySpaceId — defaults empty, heals per-field, survives round trips", () => {
    // Default: no preferred action anywhere.
    expect(new UiSettingsStore({ storage: memoryStorage() }).getSnapshot().lastProjectActionBySpaceId).toEqual({});
    // Healing keeps only non-empty string values (settings.rs parity: the
    // map is `skip_serializing_if = "HashMap::is_empty"`).
    const healed = storedWith({
      lastProjectActionBySpaceId: { "space-1": "dev", "space-2": "", "space-3": 7 },
    });
    expect(healed.lastProjectActionBySpaceId).toEqual({ "space-1": "dev" });
    // A write lands through the debounced update path like every field.
    vi.useFakeTimers();
    const storage = memoryStorage();
    const store = new UiSettingsStore({ storage });
    store.updateDebounced({ lastProjectActionBySpaceId: { "space-1": "dev" } });
    vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    const persisted = JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!) as UiSettings;
    expect(persisted.lastProjectActionBySpaceId).toEqual({ "space-1": "dev" });
  });
});

describe("migration", () => {
  function seedLegacy(): StorageLike & { dump(): Map<string, string> } {
    const storage = memoryStorage();
    storage.setItem(LEGACY_LAYOUT_KEY, JSON.stringify({ width: 312, collapsed: true }));
    storage.setItem(
      LEGACY_SIDEBAR_KEY,
      JSON.stringify({ version: 1, spaceFilter: "space-1", lastSpaceId: "space-2", archivedOpen: true }),
    );
    storage.setItem(
      LEGACY_APPEARANCE_KEY,
      JSON.stringify({
        version: 1,
        mode: "dark",
        lightVariant: "github-light",
        darkVariant: "nord",
        accent: "pink",
        surface: "frosted",
      }),
    );
    return storage;
  }

  it("from three legacy keys — folds them in and leaves them in place", () => {
    const storage = seedLegacy();
    const settings = new UiSettingsStore({ storage }).getSnapshot();

    expect(settings.sidebarWidth).toBe(312);
    expect(settings.sidebarCollapsed).toBe(true);
    expect(settings.spaceFilter).toBe("space-1");
    expect(settings.lastSpaceId).toBe("space-2");
    expect(settings.appearance).toBe("dark");
    expect(settings.themeSelection).toEqual({ light: "github-light", dark: "nord" });
    expect(settings.accent).toBe("pink");
    // Frosted was removed by product decision; a stored one heals to the
    // explicit opaque choice, not back to the theme default.
    expect(settings.surface).toBe("opaque");

    // Anything the legacy keys never covered takes its own default.
    expect(settings.terminalHeight).toBe(280);
    expect(settings.rightPaneWidth).toBe(520);
    expect(settings.soundEnabled).toBe(true);

    // The merged snapshot is written straight away, so the fold-in runs once.
    expect(JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!)).toEqual(settings);

    // A rollback to a pre-consolidation build must still find its data.
    expect(storage.getItem(LEGACY_LAYOUT_KEY)).toBe(JSON.stringify({ width: 312, collapsed: true }));
    expect(storage.getItem(LEGACY_SIDEBAR_KEY)).not.toBe(null);
    expect(storage.getItem(LEGACY_APPEARANCE_KEY)).not.toBe(null);
  });

  it("from three legacy keys — never reads the fleet registry", () => {
    const storage = seedLegacy();
    storage.setItem("roboco.fleet.v1", JSON.stringify({ version: 1, active: "e", engines: [] }));
    const read: string[] = [];
    const spy: StorageLike = {
      getItem: (key) => {
        read.push(key);
        return storage.getItem(key);
      },
      setItem: (key, value) => storage.setItem(key, value),
      removeItem: (key) => storage.removeItem(key),
    };
    new UiSettingsStore({ storage: spy });
    expect(read).not.toContain("roboco.fleet.v1");
    expect(storage.getItem("roboco.fleet.v1")).not.toBe(null);
  });

  it("idempotent — a second store reads the consolidated key only", () => {
    const storage = seedLegacy();
    new UiSettingsStore({ storage });

    // A pre-consolidation build writing its own key after the fold-in must not
    // reach back into the new store.
    storage.setItem(LEGACY_LAYOUT_KEY, JSON.stringify({ width: 400, collapsed: false }));
    const second = new UiSettingsStore({ storage }).getSnapshot();
    expect(second.sidebarWidth).toBe(312);
    expect(second.sidebarCollapsed).toBe(true);
  });

  it("idempotent — an unparseable consolidated key falls back to the legacy fold", () => {
    const storage = seedLegacy();
    storage.setItem(UI_SETTINGS_STORAGE_KEY, "{not json");
    expect(new UiSettingsStore({ storage }).getSnapshot().sidebarWidth).toBe(312);
  });
});

describe("per-field healing", () => {
  it("one corrupt field does not lose its siblings", () => {
    const settings = storedWith({
      sidebarWidth: "banana",
      terminalHeight: 400,
      appearance: "dark",
      soundEnabled: false,
      spaceFilter: "space-7",
      keymap: { saveFile: "mod-alt-s", jumpSession: "nonsense" },
    });
    // The desktop's typed deserialization would have discarded the whole file
    // here; the web heals the one bad field and keeps the rest (deliberate).
    expect(settings.sidebarWidth).toBe(256);
    expect(settings.terminalHeight).toBe(400);
    expect(settings.appearance).toBe("dark");
    expect(settings.soundEnabled).toBe(false);
    expect(settings.spaceFilter).toBe("space-7");
    expect(settings.keymap.saveFile).toBe("mod-alt-s");
    expect(settings.keymap.jumpSession).toEqual(JUMP_DEFAULTS);
  });

  it("a non-object stored value falls back to the defaults", () => {
    expect(storedWith({}).sidebarWidth).toBe(256);
    const storage = memoryStorage();
    storage.setItem(UI_SETTINGS_STORAGE_KEY, JSON.stringify([1, 2, 3]));
    expect(new UiSettingsStore({ storage }).getSnapshot()).toEqual(defaultUiSettings());
  });
});

describe("save policies", () => {
  it("immediate — writes synchronously and notifies once", () => {
    const storage = memoryStorage();
    const store = new UiSettingsStore({ storage });
    let fired = 0;
    store.subscribe(() => {
      fired += 1;
    });
    store.update({ appearance: "dark" }, "immediate");
    expect(JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!).appearance).toBe("dark");
    // A patch that changes nothing schedules no write and notifies nobody.
    store.update({ appearance: "dark" }, "immediate");
    expect(fired).toBe(1);
  });

  it("debounced — a drag coalesces into one write", () => {
    vi.useFakeTimers();
    const storage = memoryStorage();
    const store = new UiSettingsStore({ storage });
    let writes = 0;
    const counting: StorageLike = {
      getItem: (key) => storage.getItem(key),
      setItem: (key, value) => {
        writes += 1;
        storage.setItem(key, value);
      },
      removeItem: (key) => storage.removeItem(key),
    };
    const dragging = new UiSettingsStore({ storage: counting });
    writes = 0;
    for (const width of [300, 310, 320, 330]) {
      dragging.update({ sidebarWidth: width }, "debounced");
    }
    expect(writes).toBe(0);
    expect(dragging.getSnapshot().sidebarWidth).toBe(330);
    vi.advanceTimersByTime(SAVE_DEBOUNCE_MS);
    expect(writes).toBe(1);
    expect(JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!).sidebarWidth).toBe(330);
    expect(store.getSnapshot().sidebarWidth).toBe(256);
  });

  it("debounced — flush writes the pending snapshot now", () => {
    vi.useFakeTimers();
    const storage = memoryStorage();
    const store = new UiSettingsStore({ storage });
    store.update({ sidebarWidth: 300 }, "debounced");
    // Storage still holds the snapshot written when the store was created.
    expect(JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!).sidebarWidth).toBe(256);
    store.flush();
    expect(JSON.parse(storage.getItem(UI_SETTINGS_STORAGE_KEY)!).sidebarWidth).toBe(300);
  });

  it("an out-of-range value never lives in memory, even transiently", () => {
    const store = new UiSettingsStore({ storage: memoryStorage() });
    store.update({ sidebarWidth: 9999 }, "immediate");
    expect(store.getSnapshot().sidebarWidth).toBe(400);
    store.update({ rightPaneWidth: 10 }, "immediate");
    expect(store.getSnapshot().rightPaneWidth).toBe(360);
  });
});
