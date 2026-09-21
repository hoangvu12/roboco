import type { StorageLike } from "./engine-store";

/**
 * Browser-side sidebar UI state — the web peer of the desktop's
 * `settings.space_filter` / `settings.last_space_id` (ui-settings.json).
 * The space filter is the sidebar's space switcher AND the new-chat flow's
 * target: a chat created under a filter lands in that space; under "All
 * projects" it lands in the last selected (then first) space, or project-
 * less when the engine has no spaces. `archivedOpen` mirrors the desktop's
 * in-memory disclosure flag and is deliberately not persisted.
 *
 * Persistence is origin-scoped localStorage, like the fleet registry.
 */

export interface SidebarState {
  /** The space the sidebar filters on; null = "All projects". */
  readonly spaceFilter: string | null;
  /** The last explicitly picked space — the new-chat fallback under "All". */
  readonly lastSpaceId: string | null;
  /** The archived shelf's disclosure (in-memory, like the desktop). */
  readonly archivedOpen: boolean;
}

export interface SidebarStoreOptions {
  readonly storage?: StorageLike;
}

interface PersistedSidebar {
  readonly version: 1;
  readonly spaceFilter: string | null;
  readonly lastSpaceId: string | null;
}

const STORAGE_KEY = "roboco.sidebar.v1";

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

function defaultStorage(): StorageLike {
  const candidate = (globalThis as { localStorage?: StorageLike }).localStorage;
  return candidate ?? memoryStorage();
}

const EMPTY: SidebarState = { spaceFilter: null, lastSpaceId: null, archivedOpen: false };

export class SidebarStore {
  readonly #storage: StorageLike;
  #state: SidebarState = EMPTY;
  readonly #listeners = new Set<() => void>();

  constructor(options: SidebarStoreOptions = {}) {
    this.#storage = options.storage ?? defaultStorage();
    this.#load();
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
    this.#setState({
      ...this.#state,
      spaceFilter: spaceId,
      lastSpaceId: spaceId ?? this.#state.lastSpaceId,
    });
  }

  setArchivedOpen(open: boolean): void {
    if (open === this.#state.archivedOpen) {
      return;
    }
    this.#setState({ ...this.#state, archivedOpen: open });
  }

  #load(): void {
    const raw = this.#storage.getItem(STORAGE_KEY);
    if (raw === null) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as PersistedSidebar;
      if (parsed.version === 1) {
        this.#state = {
          spaceFilter: typeof parsed.spaceFilter === "string" ? parsed.spaceFilter : null,
          lastSpaceId: typeof parsed.lastSpaceId === "string" ? parsed.lastSpaceId : null,
          archivedOpen: false,
        };
        return;
      }
    } catch {
      // fall through to the reset
    }
    this.#storage.removeItem(STORAGE_KEY);
  }

  #persist(): void {
    const persisted: PersistedSidebar = {
      version: 1,
      spaceFilter: this.#state.spaceFilter,
      lastSpaceId: this.#state.lastSpaceId,
    };
    this.#storage.setItem(STORAGE_KEY, JSON.stringify(persisted));
  }

  #setState(state: SidebarState): void {
    if (
      state.spaceFilter === this.#state.spaceFilter &&
      state.lastSpaceId === this.#state.lastSpaceId &&
      state.archivedOpen === this.#state.archivedOpen
    ) {
      return;
    }
    this.#state = state;
    this.#persist();
    for (const listener of this.#listeners) {
      listener();
    }
  }
}
