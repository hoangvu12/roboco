import type { StorageLike } from "./engine-store";
import { projectSidebarPinChange, retainKnownPins, type SidebarPinChange } from "./sidebar-pins";
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
  /**
   * The pinned section's disclosure — `Shell::pinned_open`: OPEN by default,
   * session-transient (in-memory, like the desktop's Archived shelf).
   */
  readonly pinnedOpen: boolean;
  /**
   * Device-local pinned sessions per workspace profile, in visual order
   * (`UiSettings::sidebar_pinned_session_ids_by_profile`; ui-settings, never
   * synced). Callers resolve the active bucket(s) off the fleet registry.
   */
  readonly pinnedByProfile: Readonly<Record<string, readonly string[]>>;
  /** ByDevice buckets the list under per-device disclosures; InOneList is flat. */
  readonly organization: SidebarOrganization;
  /** The comparator the active list, jump order, and archived shelf share. */
  readonly sort: SidebarSort;
  /** Upstream 78e9e6ae's display toggles — the view menu writes them. */
  readonly compact: boolean;
  readonly showProjectIcon: boolean;
  readonly showProjectLabel: boolean;
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
  // `Shell::pinned_open`: pins are visible by default (a pin the section
  // hides would be pointless), and the flag never reaches storage.
  #pinnedOpen = true;
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

  /** `Shell::pinned_open`'s toggle: in-memory only, a no-op notifies nobody. */
  setPinnedOpen(open: boolean): void {
    if (open === this.#pinnedOpen) {
      return;
    }
    this.#pinnedOpen = open;
    this.#emit(this.#project(this.#settings.getSnapshot()));
  }

  /**
   * `Shell::set_chat_pinned` (68306a17): one per-item intent — a Pin anchored
   * after the current last pin, or an Unpin — projected onto the profile's
   * bucket. An emptied bucket drops out of the map. A null profile key is
   * the desktop's "identity not ready" early return; a no-op writes nothing
   * (and notifies nobody).
   */
  setChatPinned(profileKey: string | null, chatId: string, pinned: boolean): void {
    if (profileKey === null) {
      return;
    }
    const current = this.#settings.getSnapshot().sidebarPinnedSessionIdsByProfile;
    const bucket = current[profileKey] ?? [];
    if (bucket.includes(chatId) === pinned) {
      return;
    }
    const change: SidebarPinChange = pinned
      ? {
          action: "pin",
          sessionId: chatId,
          after: bucket.length > 0 ? (bucket[bucket.length - 1] ?? null) : null,
          before: null,
        }
      : { action: "unpin", sessionId: chatId };
    const next = projectSidebarPinChange(bucket, change);
    const map: Record<string, readonly string[]> = { ...current };
    if (next.length === 0) {
      delete map[profileKey];
    } else {
      map[profileKey] = next;
    }
    this.#settings.update({ sidebarPinnedSessionIdsByProfile: map }, "immediate");
  }

  /**
   * Settle the buckets after a committed drag reorder
   * (`commit_pinned_session_drag`): the input is `commitVisiblePinReorder`'s
   * output; emptied buckets drop out. A no-op writes nothing.
   */
  replacePinsByProfile(pinnedByProfile: Readonly<Record<string, readonly string[]>>): void {
    const clean: Record<string, readonly string[]> = {};
    for (const [key, ids] of Object.entries(pinnedByProfile)) {
      if (ids.length > 0) {
        clean[key] = ids;
      }
    }
    if (pinMapsEqual(this.#settings.getSnapshot().sidebarPinnedSessionIdsByProfile, clean)) {
      return;
    }
    this.#settings.update({ sidebarPinnedSessionIdsByProfile: clean }, "immediate");
  }

  /**
   * `retain_known_pins` on the desktop's synced-chats tick, per ACTIVE
   * profile: another profile's absent chats are not deletions. Archived ids
   * survive (unarchiving restores the pin); only a chat the loaded list
   * confirms deleted loses its pin. A no-op notifies nobody.
   */
  pruneUnknownPins(profileKeys: readonly string[], knownChatIds: ReadonlySet<string>): void {
    const current = this.#settings.getSnapshot().sidebarPinnedSessionIdsByProfile;
    let map: Record<string, readonly string[]> | null = null;
    for (const key of profileKeys) {
      const bucket = current[key];
      if (bucket === undefined) {
        continue;
      }
      const next = retainKnownPins([...bucket], knownChatIds);
      if (next !== null) {
        map ??= { ...current };
        if (next.length === 0) {
          delete map[key];
        } else {
          map[key] = next;
        }
      }
    }
    if (map !== null) {
      this.#settings.update({ sidebarPinnedSessionIdsByProfile: map }, "immediate");
    }
  }

  #project(settings: UiSettings): SidebarState {
    return {
      spaceFilter: settings.spaceFilter,
      lastSpaceId: settings.lastSpaceId,
      archivedOpen: this.#archivedOpen,
      pinnedOpen: this.#pinnedOpen,
      pinnedByProfile: settings.sidebarPinnedSessionIdsByProfile,
      organization: settings.sidebarOrganization,
      sort: settings.sidebarSort,
      compact: settings.sidebarCompact,
      showProjectIcon: settings.sidebarShowProjectIcon,
      showProjectLabel: settings.sidebarShowProjectLabel,
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
      state.pinnedOpen === this.#state.pinnedOpen &&
      // Healed snapshots allocate fresh containers per write — compare contents.
      pinMapsEqual(state.pinnedByProfile, this.#state.pinnedByProfile) &&
      state.organization === this.#state.organization &&
      state.sort === this.#state.sort &&
      state.compact === this.#state.compact &&
      state.showProjectIcon === this.#state.showProjectIcon &&
      state.showProjectLabel === this.#state.showProjectLabel &&
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

/** Content comparison for per-profile pin maps (healed snapshots reallocate). */
function pinMapsEqual(
  left: Readonly<Record<string, readonly string[]>>,
  right: Readonly<Record<string, readonly string[]>>,
): boolean {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every((key) => {
      const leftBucket = left[key]!;
      const rightBucket = right[key];
      return (
        rightBucket !== undefined &&
        rightBucket.length === leftBucket.length &&
        leftBucket.every((id, ix) => id === rightBucket[ix])
      );
    })
  );
}
