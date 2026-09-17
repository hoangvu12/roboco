import { useSyncExternalStore } from "react";
import type { AccentPresetId } from "@roboco/theme";
import type { StorageLike } from "../lib/engine-store";

/**
 * Every device-local preference, in one store — the web peer of the desktop's
 * `UiSettings` (`crates/ui/src/settings.rs`), which serializes the whole
 * struct to `{data_dir}/ui-settings.json`, loads it once at boot, heals it on
 * every load, and writes it back under two save policies.
 *
 * The browser's `{data_dir}` is origin-scoped `localStorage`, so this store
 * keeps the desktop's shape: one key (`roboco.ui-settings.v1`), one snapshot,
 * every field carrying the desktop's default, clamp rule and camelCase JSON
 * key. Consumers (`layout.ts`, `sidebar-store.ts`, `appearance-store.ts`,
 * `right-pane.ts`, and the settings sections still to come) read and write
 * through here rather than owning a `localStorage` key each.
 *
 * Two deliberate divergences from the desktop, both load-bearing:
 *
 * 1. **Per-field healing, not whole-file rejection.** The desktop deserializes
 *    one typed `serde` struct: a single wrong-typed field discards the ENTIRE
 *    file and every unrelated preference with it. Here the JSON is parsed once
 *    and each field is read and healed independently, so a corrupt
 *    `sidebarWidth` costs you `sidebarWidth` and nothing else. This is an
 *    improvement, not an oversight — do not "fix" it to match the desktop
 *    without checking with product first.
 * 2. **`localStorage` has no atomic rename.** The desktop writes a temp file
 *    and renames so a crash mid-write cannot corrupt the file; `setItem` is
 *    already all-or-nothing per key, so the temp-file dance has no analog.
 *
 * Fields the desktop persists but the web has no analog for are deliberately
 * absent rather than stored-and-ignored: `openTabs`/`tabOrder`/`spaceOrder`
 * (no tab strip — the web routes per chat), `rightPaneOpen`/`terminalOpen`
 * (legacy and unread on the desktop too; panel-open is session state here),
 * `openWebLinksInRoboco` (no embedded browser), the three `appshot*` fields
 * (no screen capture in a browser), and `accentColor` (a one-shot desktop
 * migration input with no web legacy key).
 */

// ---------------------------------------------------------------------------
// Constants (`settings.rs` module level)
// ---------------------------------------------------------------------------

export const SIDEBAR_MIN = 224;
export const SIDEBAR_MAX = 400;
export const SIDEBAR_DEFAULT = 256;

export const RIGHT_PANE_MIN = 360;
export const RIGHT_PANE_DEFAULT = 520;

/** The conversation's floor beside an open right pane. */
export const CHAT_PANEL_MIN = 300;

export const TERMINAL_MIN_HEIGHT = 160;
/** Runtime ceiling only — the live viewport cap is applied where it is known. */
export const TERMINAL_MAX_VH = 0.55;
/** The healing cap for a persisted height, where no viewport is available. */
export const TERMINAL_ABS_MAX_HEIGHT = 2000;
export const TERMINAL_DEFAULT_HEIGHT = 280;

/** How long a geometry drag coalesces before one write. */
export const SAVE_DEBOUNCE_MS = 400;

export const FILES_AUTOSAVE_DELAY_DEFAULT_MS = 900;
export const FILES_AUTOSAVE_DELAY_MIN_MS = 100;
export const FILES_AUTOSAVE_DELAY_MAX_MS = 10_000;

export const FILES_EDITOR_FONT_SIZE_DEFAULT = 13;
export const FILES_EDITOR_FONT_SIZE_MIN = 9;
export const FILES_EDITOR_FONT_SIZE_MAX = 24;

/** How many sidebar rows the jump shortcuts reach. */
export const JUMP_SLOTS = 9;
export const JUMP_DEFAULTS: readonly string[] = [
  "mod-1",
  "mod-2",
  "mod-3",
  "mod-4",
  "mod-5",
  "mod-6",
  "mod-7",
  "mod-8",
  "mod-9",
];

export const GIT_HISTORY_AUTHOR_MIN = 44;
export const GIT_HISTORY_AUTHOR_MAX = 220;
export const GIT_HISTORY_DATE_MIN = 68;
export const GIT_HISTORY_DATE_MAX = 180;
export const GIT_HISTORY_SHA_MIN = 58;
export const GIT_HISTORY_SHA_MAX = 140;

/** `UiFontSize::ALL` — the interface sizes the picker offers. */
export const UI_FONT_SIZES: readonly number[] = [12, 13, 14, 15, 16, 18, 20];
export const UI_FONT_SIZE_DEFAULT = 16;

/** Cmd/Ctrl+Enter belongs to the composer on every send mode. */
const RESERVED_COMPOSER_COMBO = "mod-enter";

export const UI_SETTINGS_STORAGE_KEY = "roboco.ui-settings.v1";

/** The keys folded in on first load; left in place afterwards, never deleted. */
export const LEGACY_LAYOUT_KEY = "roboco.layout.sidebar";
export const LEGACY_SIDEBAR_KEY = "roboco.sidebar.v1";
export const LEGACY_APPEARANCE_KEY = "roboco.appearance.v1";

// ---------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------

export type ComposerSendBehavior = "enter" | "modEnter";
export type SidebarOrganization = "byProject" | "byDevice" | "inOneList";
export type SidebarSort = "lastUpdated" | "created";
export type UiAppearance = "system" | "light" | "dark";
export type GitHistoryAuthorDisplay = "avatar" | "name";
export type GitHistoryColumn = "author" | "date" | "sha";
export type UiFontFamily = "geist" | "geistMono" | "system" | `installed:${string}`;
export type UiAccentSelection = "themeDefault" | AccentPresetId;
export type UiSurfacePreference = "themeDefault" | "frosted" | "opaque";
export type NewThreadBackgroundEffect = "none" | "dither" | "ascii" | "halftone" | "scanlines";

/**
 * Persisted shortcut combos, stored platform-neutral ("mod-s"). `jumpSession`
 * is a list rather than nine fields for the same reason it is on the desktop:
 * one malformed slot heals on its own instead of taking the map with it.
 */
export interface KeymapConfig {
  /** Desktop-only (appshots); reserved here so the shape stays whole. */
  readonly captureAppshot: string;
  readonly saveFile: string;
  readonly browserReload: string;
  readonly toggleSidebar: string;
  readonly toggleChanges: string;
  readonly toggleTerminal: string;
  readonly newSession: string;
  readonly nextSession: string;
  readonly prevSession: string;
  readonly archiveSession: string;
  /** Exactly [`JUMP_SLOTS`] entries after healing, in slot order. */
  readonly jumpSession: readonly string[];
}

export interface GitHistoryColumns {
  readonly author: boolean;
  readonly date: boolean;
  readonly sha: boolean;
}

export interface GitHistoryColumnWidths {
  readonly author: number;
  readonly date: number;
  readonly sha: number;
}

/** The light/dark variant pair; the two are chosen independently. */
export interface ThemeSelection {
  readonly light: string;
  readonly dark: string;
}

/**
 * The new-thread composer's background image. The browser has no filesystem,
 * so only the shape is reserved — whoever builds the picker owns deciding what
 * `path` means on the web (ticket 15).
 */
export interface NewThreadComposerBackground {
  readonly path: string;
  readonly name: string;
}

export interface UiSettings {
  readonly composerSendBehavior: ComposerSendBehavior;
  readonly sidebarWidth: number;
  readonly sidebarCollapsed: boolean;
  /** Legacy on the desktop: persisted, never read. Kept for round-tripping. */
  readonly sidebarGrouped: boolean;
  readonly sidebarOrganization: SidebarOrganization;
  readonly sidebarSort: SidebarSort;
  readonly sidebarShowHarness: boolean;
  readonly sidebarShowBranch: boolean;
  readonly sidebarShowPullRequest: boolean;
  readonly lastSpaceId: string | null;
  readonly spaceFilter: string | null;
  readonly soundEnabled: boolean;
  readonly soundCompletionEnabled: boolean;
  readonly soundInputEnabled: boolean;
  readonly soundAttentionEnabled: boolean;
  readonly notificationsEnabled: boolean;
  readonly notificationsBackgroundOnly: boolean;
  readonly rightPaneWidth: number;
  readonly terminalHeight: number;
  readonly keymap: KeymapConfig;
  readonly escapeStopsActiveAgent: boolean;
  readonly appearance: UiAppearance;
  readonly gitHistoryColumns: GitHistoryColumns;
  readonly gitHistoryColumnWidths: GitHistoryColumnWidths;
  readonly gitHistoryColumnOrder: readonly GitHistoryColumn[];
  readonly gitHistoryAuthorDisplay: GitHistoryAuthorDisplay;
  readonly uiFontFamily: UiFontFamily;
  readonly uiFontSize: number;
  readonly themeSelection: ThemeSelection;
  readonly diffSplit: boolean;
  readonly diffWrap: boolean;
  readonly codeFencesFitContent: boolean;
  readonly filesAutosaveEnabled: boolean;
  readonly filesAutosaveDelayMs: number;
  readonly filesWordWrap: boolean;
  readonly filesEditorFontSize: number;
  readonly filesShowAll: boolean;
  readonly accent: UiAccentSelection;
  readonly surface: UiSurfacePreference;
  readonly newThreadComposerBackground: NewThreadComposerBackground | null;
  readonly newThreadBackgroundEffect: NewThreadBackgroundEffect;
}

const ACCENT_IDS: readonly UiAccentSelection[] = [
  "themeDefault",
  "roboco",
  "orange",
  "amber",
  "green",
  "cyan",
  "blue",
  "pink",
];

const GIT_HISTORY_COLUMNS: readonly GitHistoryColumn[] = ["author", "date", "sha"];

/**
 * Ctrl+Tab on every platform — but spelled the way THAT platform's recorder
 * spells ctrl. Off macOS ctrl IS the primary and stores as "mod"; on macOS it
 * is its own modifier and "mod" would mean Cmd+Tab, which the OS eats.
 */
function isMacPlatform(): boolean {
  const navigator = (globalThis as { navigator?: { platform?: string; userAgent?: string } }).navigator;
  if (navigator === undefined) {
    return false;
  }
  return /Mac|iPhone|iPad|iPod/i.test(`${navigator.platform ?? ""} ${navigator.userAgent ?? ""}`);
}

export function defaultKeymap(mac: boolean = isMacPlatform()): KeymapConfig {
  return {
    captureAppshot: mac ? "ctrl-alt-space" : "mod-alt-space",
    saveFile: "mod-s",
    browserReload: "mod-shift-r",
    toggleSidebar: "mod-b",
    toggleChanges: "mod-r",
    toggleTerminal: "mod-j",
    newSession: "mod-n",
    nextSession: mac ? "ctrl-tab" : "mod-tab",
    prevSession: mac ? "ctrl-shift-tab" : "mod-shift-tab",
    // Mod+A is the composer's Select all, so archiving takes the shifted combo.
    archiveSession: "mod-shift-a",
    jumpSession: [...JUMP_DEFAULTS],
  };
}

export function defaultUiSettings(): UiSettings {
  return {
    composerSendBehavior: "enter",
    sidebarWidth: SIDEBAR_DEFAULT,
    sidebarCollapsed: false,
    sidebarGrouped: false,
    sidebarOrganization: "inOneList",
    sidebarSort: "lastUpdated",
    sidebarShowHarness: true,
    sidebarShowBranch: true,
    sidebarShowPullRequest: true,
    lastSpaceId: null,
    spaceFilter: null,
    soundEnabled: true,
    soundCompletionEnabled: true,
    soundInputEnabled: true,
    soundAttentionEnabled: true,
    notificationsEnabled: true,
    notificationsBackgroundOnly: true,
    rightPaneWidth: RIGHT_PANE_DEFAULT,
    terminalHeight: TERMINAL_DEFAULT_HEIGHT,
    keymap: defaultKeymap(),
    escapeStopsActiveAgent: false,
    appearance: "system",
    gitHistoryColumns: { author: true, date: true, sha: true },
    gitHistoryColumnWidths: { author: 88, date: 88, sha: 74 },
    gitHistoryColumnOrder: [...GIT_HISTORY_COLUMNS],
    gitHistoryAuthorDisplay: "avatar",
    uiFontFamily: "geist",
    uiFontSize: UI_FONT_SIZE_DEFAULT,
    themeSelection: { light: "roboco-light", dark: "roboco-dark" },
    diffSplit: false,
    diffWrap: false,
    codeFencesFitContent: false,
    filesAutosaveEnabled: false,
    filesAutosaveDelayMs: FILES_AUTOSAVE_DELAY_DEFAULT_MS,
    filesWordWrap: false,
    filesEditorFontSize: FILES_EDITOR_FONT_SIZE_DEFAULT,
    filesShowAll: false,
    accent: "themeDefault",
    surface: "themeDefault",
    newThreadComposerBackground: null,
    newThreadBackgroundEffect: "none",
  };
}

// ---------------------------------------------------------------------------
// Healing primitives
// ---------------------------------------------------------------------------

/** `settings.rs::clamp_or` — a non-finite value falls back, else it clamps. */
export function clampOr(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, value));
}

/** `settings.rs::min_or` — a floor with no ceiling (the right pane's rule). */
export function minOr(value: unknown, min: number, fallback: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return fallback;
  }
  return Math.max(min, value);
}

/** `UiFontSize::normalized` — snap to the nearest offered size, ties low. */
export function normalizeUiFontSize(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return UI_FONT_SIZE_DEFAULT;
  }
  let best = UI_FONT_SIZES[0]!;
  for (const candidate of UI_FONT_SIZES) {
    if (Math.abs(candidate - value) < Math.abs(best - value)) {
      best = candidate;
    }
  }
  return best;
}

/** `GitHistoryColumnOrder::normalized` — dedup, then append what is missing. */
export function normalizeGitHistoryColumnOrder(value: unknown): readonly GitHistoryColumn[] {
  const columns: GitHistoryColumn[] = [];
  if (Array.isArray(value)) {
    for (const entry of value) {
      if (isGitHistoryColumn(entry) && !columns.includes(entry)) {
        columns.push(entry);
      }
    }
  }
  for (const column of GIT_HISTORY_COLUMNS) {
    if (!columns.includes(column)) {
      columns.push(column);
    }
  }
  return columns;
}

function isGitHistoryColumn(value: unknown): value is GitHistoryColumn {
  return value === "author" || value === "date" || value === "sha";
}

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function text(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === "string" && (allowed as readonly string[]).includes(value) ? (value as T) : fallback;
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function healUiFontFamily(value: unknown): UiFontFamily {
  if (value === "geist" || value === "geistMono" || value === "system") {
    return value;
  }
  if (typeof value === "string" && value.startsWith("installed:") && value.length > "installed:".length) {
    return value as UiFontFamily;
  }
  return "geist";
}

function healBackground(value: unknown): NewThreadComposerBackground | null {
  const raw = record(value);
  return typeof raw.path === "string" && typeof raw.name === "string"
    ? { path: raw.path, name: raw.name }
    : null;
}

/**
 * `KeymapConfig::healed` — restore the jump list's length, then release any
 * combo that has taken Cmd/Ctrl+Enter back to its default.
 */
export function healKeymap(value: unknown): KeymapConfig {
  const raw = record(value);
  const defaults = defaultKeymap();
  const storedJumps = Array.isArray(raw.jumpSession) ? raw.jumpSession : [];
  const jumpSession = JUMP_DEFAULTS.map((fallback, slot) => {
    const stored: unknown = storedJumps[slot];
    const combo = typeof stored === "string" ? stored : fallback;
    return combo === RESERVED_COMPOSER_COMBO ? fallback : combo;
  });
  const combo = (key: keyof Omit<KeymapConfig, "jumpSession">): string => {
    const stored = text(raw[key], defaults[key]);
    return stored === RESERVED_COMPOSER_COMBO ? defaults[key] : stored;
  };
  return {
    captureAppshot: combo("captureAppshot"),
    saveFile: combo("saveFile"),
    browserReload: combo("browserReload"),
    toggleSidebar: combo("toggleSidebar"),
    toggleChanges: combo("toggleChanges"),
    toggleTerminal: combo("toggleTerminal"),
    newSession: combo("newSession"),
    nextSession: combo("nextSession"),
    prevSession: combo("prevSession"),
    archiveSession: combo("archiveSession"),
    jumpSession,
  };
}

/**
 * `UiSettings::clamped` widened to a whole-snapshot read: take each field off
 * an arbitrary parsed value, heal it against its own rule, and never let one
 * bad field touch its siblings.
 */
export function healUiSettings(value: unknown): UiSettings {
  const raw = record(value);
  const defaults = defaultUiSettings();
  const columns = record(raw.gitHistoryColumns);
  const widths = record(raw.gitHistoryColumnWidths);
  const theme = record(raw.themeSelection);
  const organization = oneOf(
    raw.sidebarOrganization,
    ["byProject", "byDevice", "inOneList"] as const,
    "inOneList",
  );
  return {
    composerSendBehavior: oneOf(raw.composerSendBehavior, ["enter", "modEnter"], "enter"),
    sidebarWidth: clampOr(raw.sidebarWidth, SIDEBAR_MIN, SIDEBAR_MAX, SIDEBAR_DEFAULT),
    sidebarCollapsed: bool(raw.sidebarCollapsed, false),
    sidebarGrouped: bool(raw.sidebarGrouped, false),
    // "By project" is no longer selectable; a stored one heals to the flat list.
    sidebarOrganization: organization === "byProject" ? "inOneList" : organization,
    sidebarSort: oneOf(raw.sidebarSort, ["lastUpdated", "created"], "lastUpdated"),
    sidebarShowHarness: bool(raw.sidebarShowHarness, true),
    sidebarShowBranch: bool(raw.sidebarShowBranch, true),
    sidebarShowPullRequest: bool(raw.sidebarShowPullRequest, true),
    lastSpaceId: nullableString(raw.lastSpaceId),
    spaceFilter: nullableString(raw.spaceFilter),
    soundEnabled: bool(raw.soundEnabled, true),
    soundCompletionEnabled: bool(raw.soundCompletionEnabled, true),
    soundInputEnabled: bool(raw.soundInputEnabled, true),
    soundAttentionEnabled: bool(raw.soundAttentionEnabled, true),
    notificationsEnabled: bool(raw.notificationsEnabled, true),
    notificationsBackgroundOnly: bool(raw.notificationsBackgroundOnly, true),
    // No persisted ceiling: the live drag clamps against the window, which is
    // unavailable while loading.
    rightPaneWidth: minOr(raw.rightPaneWidth, RIGHT_PANE_MIN, RIGHT_PANE_DEFAULT),
    terminalHeight: clampOr(
      raw.terminalHeight,
      TERMINAL_MIN_HEIGHT,
      TERMINAL_ABS_MAX_HEIGHT,
      TERMINAL_DEFAULT_HEIGHT,
    ),
    keymap: healKeymap(raw.keymap),
    escapeStopsActiveAgent: bool(raw.escapeStopsActiveAgent, false),
    appearance: oneOf(raw.appearance, ["system", "light", "dark"], "system"),
    gitHistoryColumns: {
      author: bool(columns.author, true),
      date: bool(columns.date, true),
      sha: bool(columns.sha, true),
    },
    gitHistoryColumnWidths: {
      author: clampOr(widths.author, GIT_HISTORY_AUTHOR_MIN, GIT_HISTORY_AUTHOR_MAX, 88),
      date: clampOr(widths.date, GIT_HISTORY_DATE_MIN, GIT_HISTORY_DATE_MAX, 88),
      sha: clampOr(widths.sha, GIT_HISTORY_SHA_MIN, GIT_HISTORY_SHA_MAX, 74),
    },
    gitHistoryColumnOrder: normalizeGitHistoryColumnOrder(raw.gitHistoryColumnOrder),
    gitHistoryAuthorDisplay: oneOf(raw.gitHistoryAuthorDisplay, ["avatar", "name"], "avatar"),
    uiFontFamily: healUiFontFamily(raw.uiFontFamily),
    uiFontSize: normalizeUiFontSize(raw.uiFontSize),
    themeSelection: {
      light: text(theme.light, defaults.themeSelection.light),
      dark: text(theme.dark, defaults.themeSelection.dark),
    },
    diffSplit: bool(raw.diffSplit, false),
    diffWrap: bool(raw.diffWrap, false),
    codeFencesFitContent: bool(raw.codeFencesFitContent, false),
    filesAutosaveEnabled: bool(raw.filesAutosaveEnabled, false),
    filesAutosaveDelayMs: clampOr(
      raw.filesAutosaveDelayMs,
      FILES_AUTOSAVE_DELAY_MIN_MS,
      FILES_AUTOSAVE_DELAY_MAX_MS,
      FILES_AUTOSAVE_DELAY_DEFAULT_MS,
    ),
    filesWordWrap: bool(raw.filesWordWrap, false),
    filesEditorFontSize: clampOr(
      raw.filesEditorFontSize,
      FILES_EDITOR_FONT_SIZE_MIN,
      FILES_EDITOR_FONT_SIZE_MAX,
      FILES_EDITOR_FONT_SIZE_DEFAULT,
    ),
    filesShowAll: bool(raw.filesShowAll, false),
    accent: oneOf(raw.accent, ACCENT_IDS, "themeDefault"),
    surface: oneOf(raw.surface, ["themeDefault", "frosted", "opaque"], "themeDefault"),
    newThreadComposerBackground: healBackground(raw.newThreadComposerBackground),
    newThreadBackgroundEffect: oneOf(
      raw.newThreadBackgroundEffect,
      ["none", "dither", "ascii", "halftone", "scanlines"],
      "none",
    ),
  };
}

// ---------------------------------------------------------------------------
// Migration from the keys this store replaces
// ---------------------------------------------------------------------------

function parse(raw: string | null): unknown {
  if (raw === null) {
    return undefined;
  }
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * Fold the three ad hoc keys this store replaces into one snapshot. Anything
 * they do not cover takes its own default, and the keys themselves are left
 * exactly as they are: a rollback to a pre-consolidation build must still find
 * its data, so nothing is deleted until the new shape has proven itself.
 *
 * `roboco.fleet.v1` is NOT a source here. It mirrors paired-engine Session
 * credentials, not a `UiSettings` field, and keeps its own key.
 */
function migrateLegacy(storage: StorageLike): Record<string, unknown> {
  const merged: Record<string, unknown> = {};
  const layout = record(parse(storage.getItem(LEGACY_LAYOUT_KEY)));
  if (layout.width !== undefined) {
    merged.sidebarWidth = layout.width;
  }
  if (layout.collapsed !== undefined) {
    merged.sidebarCollapsed = layout.collapsed;
  }

  // `version` is deliberately ignored, as is `archivedOpen` — the shelf's
  // disclosure was never persisted, on either client.
  const sidebar = record(parse(storage.getItem(LEGACY_SIDEBAR_KEY)));
  if (sidebar.spaceFilter !== undefined) {
    merged.spaceFilter = sidebar.spaceFilter;
  }
  if (sidebar.lastSpaceId !== undefined) {
    merged.lastSpaceId = sidebar.lastSpaceId;
  }

  const appearance = record(parse(storage.getItem(LEGACY_APPEARANCE_KEY)));
  if (appearance.mode !== undefined) {
    merged.appearance = appearance.mode;
  }
  if (appearance.lightVariant !== undefined || appearance.darkVariant !== undefined) {
    merged.themeSelection = { light: appearance.lightVariant, dark: appearance.darkVariant };
  }
  if (appearance.accent !== undefined) {
    merged.accent = appearance.accent;
  }
  if (appearance.surface !== undefined) {
    merged.surface = appearance.surface;
  }
  return merged;
}

// ---------------------------------------------------------------------------
// The store
// ---------------------------------------------------------------------------

/**
 * Which write a mutation earns. `debounced` is for drag samples — a rapid
 * resize coalesces into one `setItem`; `immediate` is for discrete choices,
 * where the write should survive a tab closing in the next tick.
 */
export type SavePolicy = "immediate" | "debounced";

export interface UiSettingsStoreOptions {
  readonly storage?: StorageLike;
}

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

function defaultStorage(): StorageLike {
  try {
    const candidate = (globalThis as { localStorage?: StorageLike }).localStorage;
    return candidate ?? memoryStorage();
  } catch {
    // Storage disabled by policy — the session still gets working settings.
    return memoryStorage();
  }
}

export class UiSettingsStore {
  readonly #storage: StorageLike;
  #settings: UiSettings;
  #serialized: string;
  #timer: ReturnType<typeof setTimeout> | null = null;
  readonly #listeners = new Set<() => void>();

  constructor(options: UiSettingsStoreOptions = {}) {
    this.#storage = options.storage ?? defaultStorage();
    const stored = parse(this.#read(UI_SETTINGS_STORAGE_KEY));
    const consolidated = typeof stored === "object" && stored !== null && !Array.isArray(stored);
    this.#settings = healUiSettings(consolidated ? stored : migrateLegacy(this.#storage));
    this.#serialized = JSON.stringify(this.#settings);
    if (!consolidated) {
      // Write the merged snapshot now so the fold-in only ever happens once.
      this.#write();
    }
  }

  getSnapshot = (): UiSettings => this.#settings;

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  };

  /**
   * Apply a patch to the in-memory snapshot, healed synchronously so an
   * out-of-range value never lives in memory even transiently. A patch that
   * changes nothing schedules no write and notifies nobody.
   */
  update(patch: Partial<UiSettings>, policy: SavePolicy = "immediate"): void {
    const next = healUiSettings({ ...this.#settings, ...patch });
    const serialized = JSON.stringify(next);
    if (serialized === this.#serialized) {
      return;
    }
    this.#settings = next;
    this.#serialized = serialized;
    if (policy === "debounced") {
      this.#schedule();
    } else {
      this.#write();
    }
    for (const listener of this.#listeners) {
      listener();
    }
  }

  /** A discrete choice — theme, a toggle, a keybinding. */
  updateImmediate(patch: Partial<UiSettings>): void {
    this.update(patch, "immediate");
  }

  /** A drag sample — sidebar, right pane, terminal geometry. */
  updateDebounced(patch: Partial<UiSettings>): void {
    this.update(patch, "debounced");
  }

  /** Write any pending debounced snapshot now. */
  flush(): void {
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
      this.#timer = null;
      this.#write();
    }
  }

  #schedule(): void {
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
    }
    this.#timer = setTimeout(() => {
      this.#timer = null;
      this.#write();
    }, SAVE_DEBOUNCE_MS);
  }

  #read(key: string): string | null {
    try {
      return this.#storage.getItem(key);
    } catch {
      return null;
    }
  }

  #write(): void {
    try {
      this.#storage.setItem(UI_SETTINGS_STORAGE_KEY, this.#serialized);
    } catch {
      // Private mode or a full quota — a preference is not worth a crash.
    }
  }
}

/** The one store for this page load; every consumer reads through it. */
export const uiSettings = new UiSettingsStore();

/*
 * A debounced write is a bet that the page will still be here in 400ms. The
 * desktop settles that bet by flushing as the app quits; the browser's moment
 * is the page going away, which is `pagehide` — `beforeunload` does not fire
 * on mobile or on a back/forward-cache eviction.
 */
(globalThis as { addEventListener?: (type: string, listener: () => void) => void }).addEventListener?.(
  "pagehide",
  () => uiSettings.flush(),
);

const subscribe = (listener: () => void) => uiSettings.subscribe(listener);
const getSnapshot = () => uiSettings.getSnapshot();

export function useUiSettings(): UiSettings {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
