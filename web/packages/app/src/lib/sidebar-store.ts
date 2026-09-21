import type { StorageLike } from "./engine-store";
import { retainKnownPins } from "./sidebar-pins";
import { UiSettingsStore, uiSettings, type SidebarOrganization, type SidebarSort, type UiSettings } from "../state/ui-settings";

/**
 * Browser-side sidebar UI state — the web peer of the desktop's
 * `settings.space_filter` / `settings.last_space_id` (ui-settings.json).
 * The space filter is the sidebar's space switcher AND the new-chat flow's
 * target: a chat created under a filter lands in that space; under "All
 * projects" it lands in the last selected (then first) space, or project-
 * less when the engine has no spaces. `archivedOpen` mirrors the desktop's
 * in-memory disclosure flag and is deliberately not persisted.
 *
 * The five sidebar view options (`sidebarOrganization`/`sidebarSort`/the
 * three Show toggles, settings.rs:507-544) ride along read-only here —
 * ticket 10's view menu writes them; the sidebar only reads.
 *
 * Persistence is the consolidated `state/ui-settings.ts` store — this class
 * owns the sidebar's *view* of it plus the one flag that never reaches
 * storage, not a `localStorage` key of its own.
 */

export interface SidebarState {
  /** The space the sidebar filters on; null = "All projects". */
  readonly spaceFilter: string | null;
  /** The last explicitly picked space — the new-chat fallback under "All". */
  readonly lastSpaceId: string | null;
  /** The archived shelf's disclosure (in-memory, like the desktop). */
  readonly archivedOpen: boolean;
  /** Device-local pinned sessions in visual order (ui-settings, never synced). */
  readonly pinnedSessionIds: readonly string[];
  /** ByDevice buckets the list under per-device disclosures; InOneList is flat. */
  readonly organization: SidebarOrganization;
  /** The comparator the active list, jump order, and archived shelf share. */
  readonly sort: SidebarSort;
  readonly showHarness: boolean;
  readonly showBranch: boolean;
  readonly showPullRequest: boolean;
}

export interface SidebarStoreOptions {
  /** The settings store to read through; defaults to the app's singleton. */
  readonly settings?: UiSettingsStore;
  /** Convenience for tests: a settings store over this storage. */
  readonly storage?: StorageLike;
}

export class SidebarStore {
  readonly #settings: UiSettingsStore;
  #archivedOpen = false;
  #state: SidebarState;
  readonly #listeners = new Set<() => void>();

  constructor(options: SidebarStoreOptions = {}) {
    this.#settings =
      options.settings ?? (options.storage === undefined ? uiSettings : new UiSettingsStore({ storage: options.storage }));
    this.#state = this.#project(this.#settings.getSnapshot());
    // Settings can move from elsewhere (a settings page, another view onto the
    // same fields) — re-project, and stay quiet when this slice did not move.
    this.#settings.subscribe(() => {
      this.#emit(this.#project(this.#settings.getSnapshot()));
    });
  }

  getSnapshot(): SidebarState {
    return this.#state;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * Set the sidebar's space filter (null = All projects). A picked space
   * also becomes the last selected space — the new-chat target under "All".
   */
  setSpaceFilter(spaceId: string | null): void {
    if (spaceId === this.#state.spaceFilter) {
      return;
    }
    this.#settings.update(
      { spaceFilter: spaceId, lastSpaceId: spaceId ?? this.#state.lastSpaceId },
      "immediate",
    );
  }

  setArchivedOpen(open: boolean): void {
    if (open === this.#archivedOpen) {
      return;
    }
    this.#archivedOpen = open;
    this.#emit(this.#project(this.#settings.getSnapshot()));
  }

  /**
   * `Shell::set_chat_pinned`: pins append in click order, unpins leave the
   * rest untouched. A no-op writes nothing (and notifies nobody).
   */
  setChatPinned(chatId: string, pinned: boolean): void {
    const current = this.#settings.getSnapshot().sidebarPinnedSessionIds;
    let next: string[];
    if (pinned) {
      if (current.includes(chatId)) {
        return;
      }
      next = [...current, chatId];
    } else {
      if (!current.includes(chatId)) {
        return;
      }
      next = current.filter((id) => id !== chatId);
    }
    this.#settings.update({ sidebarPinnedSessionIds: next }, "immediate");
  }

  /** Replace the whole pin order (a committed drag reorder). */
  replacePinnedSessionIds(pinnedSessionIds: string[]): void {
    const current = this.#settings.getSnapshot().sidebarPinnedSessionIds;
    if (current.length === pinnedSessionIds.length && current.every((id, ix) => id === pinnedSessionIds[ix])) {
      return;
    }
    this.#settings.update({ sidebarPinnedSessionIds: pinnedSessionIds }, "immediate");
  }

  /**
   * `retain_known_pins` on the desktop's synced-chats tick: archived ids
   * survive (unarchiving restores the pin); only a chat the loaded list
   * confirms deleted loses its pin. A prune is a settings write like any
   * other; a no-op notifies nobody.
   */
  pruneUnknownPins(knownChatIds: ReadonlySet<string>): void {
    const current = this.#settings.getSnapshot().sidebarPinnedSessionIds;
    const next = retainKnownPins([...current], knownChatIds);
    if (next !== null) {
      this.#settings.update({ sidebarPinnedSessionIds: next }, "immediate");
    }
  }

  #project(settings: UiSettings): SidebarState {
    return {
      spaceFilter: settings.spaceFilter,
      lastSpaceId: settings.lastSpaceId,
      archivedOpen: this.#archivedOpen,
      pinnedSessionIds: settings.sidebarPinnedSessionIds,
      organization: settings.sidebarOrganization,
      sort: settings.sidebarSort,
      showHarness: settings.sidebarShowHarness,
      showBranch: settings.sidebarShowBranch,
      showPullRequest: settings.sidebarShowPullRequest,
    };
  }

  #emit(state: SidebarState): void {
    if (
      state.spaceFilter === this.#state.spaceFilter &&
      state.lastSpaceId === this.#state.lastSpaceId &&
      state.archivedOpen === this.#state.archivedOpen &&
      // Healed snapshots allocate a fresh array per write — compare contents.
      state.pinnedSessionIds.length === this.#state.pinnedSessionIds.length &&
      state.pinnedSessionIds.every((id, ix) => id === this.#state.pinnedSessionIds[ix]) &&
      state.organization === this.#state.organization &&
      state.sort === this.#state.sort &&
      state.showHarness === this.#state.showHarness &&
      state.showBranch === this.#state.showBranch &&
      state.showPullRequest === this.#state.showPullRequest
    ) {
      return;
    }
    this.#state = state;
    for (const listener of this.#listeners) {
      listener();
    }
  }
}
