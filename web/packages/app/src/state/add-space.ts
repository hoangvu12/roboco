import { useSyncExternalStore } from "react";
import type {
  Device,
  DriveEntry,
  DriveListing,
  FolderEntry,
  FolderListing,
  PrepareSpacePathReply,
  Space,
} from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { PopupLifecycle, type PopupStatus } from "../lib/popup-lifecycle";
import { classifyKey, menuStep } from "../lib/picker-search";
import {
  addSpaceCompletion,
  browserRows,
  childPath,
  filteredFolders,
  isStaleResponse,
  manualPathQuery,
  parentPath,
  segmentTarget,
  typedPathTarget,
  type StaleGuard,
} from "../lib/add-space";
import type { EngineSession } from "./engine-session";
import { sidebarStore } from "./sidebar";
import { uiSettings } from "./ui-settings";

/**
 * The add-space palette's state machine — the web port of the desktop's
 * `AddSpaceFlow` (`crates/ui/src/shell/spaces.rs:186-227`) plus its whole
 * action surface (`:1825-2522`): open/close, device pick, browse/load, the
 * search-edit decision tree, the keyboard handler, manual-path prepare, and
 * submit with its optimistic space row.
 *
 * The open → closing → closed mount lifecycle rides ticket 09's
 * `PopupLifecycle` (100ms exit, then unmount); the flow object itself is
 * immutable and swapped on every mutation, so the external-store snapshot
 * stays referentially honest.
 *
 * `addSpaceStore.open()` is the hook other tickets call: ticket 10's
 * spaces-menu "New project…" row and ticket 12's `Mod+K` binding both
 * open this surface (the desktop's `open_add_space`).
 */

export type AddSpaceListing =
  | "idle"
  | "loading"
  | { error: string }
  | { path: string; entries: FolderEntry[]; truncated: boolean };

/** `SpacePath` as the palette stores it (`prepare_manual_space`). */
export interface AddSpaceManualPath {
  readonly path: string;
  readonly exists: boolean;
  readonly gitDetected: boolean;
}

/** The single flow state object — one card, never a stack of steps. */
export interface AddSpaceFlow {
  /** Stamped once per open; responses from a prior open are dropped. */
  readonly identity: string;
  /** Bumped on every browse/device-switch; drops superseded in-flight work. */
  readonly revision: number;
  /** The currently browsed device; null before any device is known. */
  readonly deviceId: string | null;
  /** The requested listing path; null = "home, not yet resolved". */
  readonly browserPath: string | null;
  /** The device's resolved home — what the device crumb folds over. */
  readonly home: string | null;
  readonly query: string;
  /** A leading `.` in the query reveals dotfiles (and reloads the folder). */
  readonly hiddenQuery: boolean;
  readonly listing: AddSpaceListing;
  /** Best-effort Locations rail rows; empty on failure — no error UI. */
  readonly drives: readonly DriveEntry[];
  /** Keyboard highlight within the FILTERED rows. */
  readonly active: number;
  readonly manualPath: AddSpaceManualPath | null;
  readonly submitBusy: boolean;
  /** The footer's error line. */
  readonly error: string | null;
  /** Best-effort git seed for the current browser path. */
  readonly browserRepo: boolean;
}

export interface AddSpaceSnapshot {
  readonly status: PopupStatus;
  readonly flow: AddSpaceFlow | null;
  /**
   * Optimistic space rows minted by a submit still on the wire (the
   * desktop's `AppState.spaces` echo). Ticket 10's spaces menu merges them
   * by id; a failed createSpace rolls its row back here.
   */
  readonly pendingSpaces: readonly Space[];
}

/** What the mounted palette component supplies each session. */
export interface AddSpaceContext {
  readonly session: EngineSession | null;
  /** Route to the blank canvas — the desktop's `Route::Chat` landing. */
  readonly goToCanvas: () => void;
}

function mintId(): string {
  return crypto.randomUUID();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export class AddSpaceStore {
  readonly #popup = new PopupLifecycle<null>();
  #flow: AddSpaceFlow | null = null;
  #pending: Space[] = [];
  #context: AddSpaceContext | null = null;
  #manualInFlight = false;
  #submitInFlight = false;
  #snapshot: AddSpaceSnapshot = { status: "closed", flow: null, pendingSpaces: [] };
  readonly #listeners = new Set<() => void>();

  constructor() {
    // The popup machine drives the mount phases; when it finishes the reap
    // the flow state goes with it.
    this.#popup.subscribe(() => {
      if (this.#popup.status() === "closed") {
        this.#flow = null;
        this.#manualInFlight = false;
        this.#submitInFlight = false;
      }
      this.#commit();
    });
  }

  getSnapshot(): AddSpaceSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * `open_add_space` (spaces.rs:1825-1904): mint a fresh identity, land on
   * the local device (else the first registered device), and kick off the
   * home browse and the drives load concurrently. An explicit
   * `startDeviceId` (a device row the caller knows about) wins over the
   * local default.
   */
  open(startDeviceId?: string): void {
    const devices = this.#devices();
    const local = this.#localDeviceId();
    const device =
      (startDeviceId !== undefined ? devices.find((row) => row.id === startDeviceId) : undefined) ??
      devices.find((row) => row.id === local) ??
      (devices[0] ?? null);
    this.#manualInFlight = false;
    this.#submitInFlight = false;
    this.#pending = [];
    this.#flow = {
      identity: mintId(),
      revision: 0,
      deviceId: device?.id ?? null,
      browserPath: null,
      home: null,
      query: "",
      hiddenQuery: false,
      listing: "idle",
      drives: [],
      active: 0,
      manualPath: null,
      submitBusy: false,
      error: null,
      browserRepo: false,
    };
    this.#popup.open(null);
    if (device !== undefined && device !== null) {
      this.#loadFolders(null);
      this.#loadDrives();
    }
  }

  /** Every close path funnels here: the exit phase, then the reap. */
  close(): void {
    this.#popup.dismiss();
  }

  /** The component's session binding — re-called on engine switches. */
  attach(context: AddSpaceContext): void {
    this.#context = context;
  }

  // ── Search edits (the Edited decision tree, spaces.rs:1839-1873) ──────

  /**
   * A keystroke landed in the search input. Runs the desktop's decision
   * tree in order: slash-descend, then the manual-path fork, then the
   * plain-filter branch with its dotfile reload.
   */
  setQuery(text: string): void {
    const flow = this.#aliveFlow();
    if (flow === null || flow.query === text) {
      return;
    }
    if (this.#slashDescend(text)) {
      return;
    }
    let next: AddSpaceFlow = { ...flow, query: text };
    if (this.#manualInFlight && !this.#submitInFlight) {
      next = { ...next, submitBusy: false };
    }
    this.#manualInFlight = false;
    next = { ...next, revision: next.revision + 1, manualPath: null, error: null };
    this.#flow = next;
    if (manualPathQuery(text)) {
      this.#prepareManual(false, false);
      return;
    }
    const showHidden = text.startsWith(".");
    const reload = showHidden !== next.hiddenQuery;
    this.#flow = { ...next, hiddenQuery: showHidden, active: 0 };
    if (reload) {
      this.#loadFolders(this.#flow.browserPath);
      return;
    }
    this.#commit();
  }

  /**
   * `add_space_slash_descend` (spaces.rs:2080-2129): a trailing `/` on a
   * typed full path jumps there directly; on a folder-naming query it
   * resolves the segment against the listing (exact case, exact
   * case-insensitive, unique prefix) and descends. Returns whether it
   * fired — descending clears the query.
   */
  #slashDescend(text: string): boolean {
    const flow = this.#flow;
    if (flow === null) {
      return false;
    }
    if (text.endsWith("/") && (text.startsWith("/") || text.startsWith("~"))) {
      const target = typedPathTarget(text, flow.home);
      if (target === null) {
        return false;
      }
      this.#descend(target, false);
      return true;
    }
    if (!text.endsWith("/")) {
      return false;
    }
    const query = text.slice(0, -1);
    if (query.length === 0 || query.includes("/")) {
      return false;
    }
    const listing = this.#readyListing();
    if (listing === null) {
      return false;
    }
    const dirs = browserRows(listing.entries);
    const names = dirs.map((entry) => entry.name);
    const ix = segmentTarget(names, query);
    if (ix === null) {
      return false;
    }
    const entry = dirs[ix] as FolderEntry;
    this.#descend(childPath(listing.path, entry.name), entry.isRepo);
    return true;
  }

  // ── Browsing ───────────────────────────────────────────────────────────

  /**
   * `add_space_pick_device` (spaces.rs:1907-1936): rebrowse the same card
   * on another device — reset, clear the query, reload home + drives.
   */
  pickDevice(deviceId: string): void {
    const flow = this.#aliveFlow();
    if (flow === null || flow.deviceId === deviceId || flow.submitBusy) {
      return;
    }
    this.#manualInFlight = false;
    this.#flow = {
      ...flow,
      revision: flow.revision + 1,
      manualPath: null,
      hiddenQuery: false,
      deviceId,
      listing: "idle",
      drives: [],
      browserPath: null,
      home: null,
      browserRepo: false,
      active: 0,
      query: "",
      error: null,
    };
    this.#commit();
    this.#loadFolders(null);
    this.#loadDrives();
  }

  /** `add_space_goto_location` (spaces.rs:1940-1955): rebrowse at a drive's
   *  mount (or home). Standing on that root already is a no-op. */
  gotoLocation(path: string | null): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    const listing = this.#readyListing();
    if (listing !== null) {
      const standing = path !== null ? listing.path === path : flow.home === listing.path;
      if (standing) {
        return;
      }
    }
    this.#flow = { ...flow, browserRepo: false, query: "" };
    this.#loadFolders(path);
  }

  /**
   * A breadcrumb click: rebrowse that path with the repo seed reset and
   * the query PRESERVED (the segment crumb handlers, spaces.rs:2868-2873 —
   * only goto-location and descend clear the search text).
   */
  browse(path: string | null): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    this.#flow = { ...flow, browserRepo: false };
    this.#loadFolders(path);
  }

  /** `add_space_open_active` (spaces.rs:2035-2072): → / Enter — open the
   *  highlighted folder, or resolve a typed path when nothing matches. */
  openActive(): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    if (manualPathQuery(flow.query)) {
      this.#prepareManual(false, true);
      return;
    }
    const listing = this.#readyListing();
    if (listing === null) {
      return;
    }
    const rows = filteredFolders(listing.entries, flow.query);
    if (rows.length === 0) {
      if (flow.query.startsWith("/") || flow.query.startsWith("~")) {
        const target = typedPathTarget(flow.query, flow.home);
        if (target !== null) {
          this.#descend(target, false);
        }
      }
      return;
    }
    const entry = rows[flow.active];
    if (entry === undefined) {
      return;
    }
    this.#descend(childPath(listing.path, entry.name), entry.isRepo);
  }

  /** `add_space_go_up` (spaces.rs:2440-2452): ←, and ⌫ on an empty query. */
  goUp(): void {
    const flow = this.#aliveFlow();
    const listing = flow === null ? null : this.#readyListing();
    if (flow === null || listing === null) {
      return;
    }
    const parent = parentPath(listing.path);
    if (parent === null) {
      return;
    }
    this.#flow = { ...flow, browserRepo: false };
    this.#loadFolders(parent);
  }

  /** `add_space_accept_completion` (spaces.rs:2158-2166): ⇥ fills the query
   *  with the previewed folder's full name; descending stays on `/`/⏎. */
  acceptCompletion(): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    const listing = this.#readyListing();
    if (listing === null) {
      return;
    }
    const rows = filteredFolders(listing.entries, flow.query);
    const completion = addSpaceCompletion(rows, flow.active, flow.query);
    if (completion === null) {
      return;
    }
    this.#flow = { ...flow, query: completion.name };
    this.#commit();
  }

  /** A folder row click (mouse path): descend into it. */
  descend(full: string, isRepo: boolean): void {
    this.#descend(full, isRepo);
  }

  /** The folder-level Retry chip: reload at the current browser path. */
  retryLoad(): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    this.#loadFolders(flow.browserPath);
  }

  /**
   * `add_space_key` (spaces.rs:2460-2522): the palette's keyboard map,
   * bubbling from the focused search input. Returns whether the key was
   * consumed (the caller prevents the browser default then).
   */
  keyDown(event: KeyboardEvent): boolean {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return false;
    }
    // ←/→ act on the FOLDERS, not the text caret; ⇥ completes — all three
    // unbound in the desktop's "PaletteSearch" context so they bubble here.
    switch (event.key) {
      case "ArrowRight":
        this.openActive();
        return true;
      case "ArrowLeft":
        this.goUp();
        return true;
      case "Tab":
        this.acceptCompletion();
        return true;
      default:
        break;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    switch (key) {
      case "escape":
        this.close();
        return true;
      case "up":
      case "down": {
        const listing = this.#readyListing();
        const rows = listing === null ? [] : filteredFolders(listing.entries, flow.query);
        const delta = key === "up" ? -1 : 1;
        const next = menuStep(flow.active, rows.length, delta);
        this.#flow = { ...flow, active: next ?? 0 };
        this.#commit();
        return true;
      }
      case "enter":
        this.openActive();
        return true;
      case "mod-enter":
        this.submit();
        return true;
      case "backspace":
        if (flow.query.length === 0) {
          this.goUp();
          return true;
        }
        return false;
      default:
        return false;
    }
  }

  // ── Submit ─────────────────────────────────────────────────────────────

  /**
   * `submit_add_space` (spaces.rs:2309-2324): ⌘⏎. A typed path re-prepares
   * with create when the manual probe said it does not exist; a browsed
   * folder goes straight to the create.
   */
  submit(): void {
    const flow = this.#aliveFlow();
    if (flow === null) {
      return;
    }
    if (manualPathQuery(flow.query)) {
      const create = flow.manualPath !== null && !flow.manualPath.exists;
      this.#prepareManual(create, true);
      return;
    }
    this.#submitBrowsed();
  }

  /**
   * `submit_browsed_space` (spaces.rs:2326-2437): same (device, folder)
   * already has a space → just land in it; otherwise mint a client id,
   * echo the row optimistically, and roll back with the engine's error
   * string inline if the create fails.
   */
  #submitBrowsed(): void {
    const flow = this.#aliveFlow();
    const session = this.#session();
    if (flow === null || session === null || flow.submitBusy || flow.deviceId === null) {
      return;
    }
    const listing = this.#readyListing();
    if (listing === null) {
      return;
    }
    const path = listing.path;
    const deviceId = flow.deviceId;
    const gitDetected = flow.browserRepo;
    const identity = flow.identity;
    const existing = session.cache
      .getSnapshot()
      .spaces.rows.find((row) => row.deviceId === deviceId && row.path === path);
    if (existing !== undefined) {
      this.#land(existing.id);
      return;
    }
    const spaceId = mintId();
    this.#pending = [
      ...this.#pending,
      {
        id: spaceId,
        deviceId,
        path,
        name: null,
        gitDetected,
        gitCheckedAt: null,
        checkoutId: null,
        createdAt: new Date().toISOString(),
      },
    ];
    this.#submitInFlight = true;
    this.#flow = { ...flow, submitBusy: true, error: null };
    this.#commit();
    void session.client
      .call(methods.MUTATE, { op: "createSpace", spaceId, deviceId, path, gitDetected })
      .then(() => {
        this.#submitInFlight = false;
        // The optimistic row STAYS — the watch frame replaces it by id.
        if (this.#aliveFlow()?.identity === identity) {
          this.#land(spaceId);
        } else {
          this.#commit();
        }
      })
      .catch((error: unknown) => {
        this.#submitInFlight = false;
        this.#pending = this.#pending.filter((row) => row.id !== spaceId);
        const current = this.#aliveFlow();
        if (current !== null && current.identity === identity) {
          this.#flow = { ...current, submitBusy: false, error: errorMessage(error) };
        }
        this.#commit();
      });
  }

  /**
   * `land_in_space` (spaces.rs:653-668): close, route to the blank canvas,
   * and make the new space the new-chat target — "All" stays "All" but
   * remembers the space; an explicit project filter follows it.
   */
  #land(spaceId: string): void {
    const filter = sidebarStore.getSnapshot().spaceFilter;
    if (filter !== null) {
      sidebarStore.setSpaceFilter(spaceId);
    } else {
      uiSettings.update({ lastSpaceId: spaceId }, "immediate");
    }
    this.#context?.goToCanvas();
    this.close();
  }

  // ── Loads ──────────────────────────────────────────────────────────────

  /**
   * `load_space_folders` (spaces.rs:2180-2253): ListFolders on the flow's
   * device (relay-forwarded only when remote). Guards the response with
   * the identity/device check plus the browser path, the hidden-query
   * flag, and "the search has since become a manual path".
   */
  #loadFolders(path: string | null): void {
    const flow = this.#flow;
    const session = this.#session();
    if (flow === null || session === null || flow.deviceId === null) {
      return;
    }
    const request: StaleGuard = { identity: flow.identity, revision: null, deviceId: flow.deviceId };
    const query = flow.query;
    const hiddenQuery = query.startsWith(".");
    this.#manualInFlight = false;
    this.#flow = {
      ...flow,
      revision: flow.revision + 1,
      manualPath: null,
      hiddenQuery,
      browserPath: path,
      listing: "loading",
      active: 0,
    };
    this.#commit();
    const params: Record<string, unknown> = { query };
    if (path !== null) {
      params.path = path;
    }
    // Only target remote devices — local calls skip the relay.
    if (this.#localDeviceId() !== flow.deviceId) {
      params.targetDeviceId = flow.deviceId;
    }
    void session.client
      .call<FolderListing>(methods.LIST_FOLDERS, params)
      .then((listing) => {
        const current = this.#guard(request, path, hiddenQuery);
        if (current === null) {
          return;
        }
        this.#flow = {
          ...current,
          // A pathless browse resolved home — remember it for the crumbs.
          home: path === null ? listing.path : current.home,
          listing: {
            path: listing.path,
            entries: listing.entries,
            truncated: listing.truncated,
          },
        };
        this.#commit();
      })
      .catch((error: unknown) => {
        const current = this.#guard(request, path, hiddenQuery);
        if (current === null) {
          return;
        }
        this.#flow = { ...current, listing: { error: errorMessage(error) } };
        this.#commit();
      });
  }

  /**
   * `load_space_drives` (spaces.rs:1960-2007): ListDrives, best-effort —
   * failures stay silent, the Locations section just stays at Home.
   */
  #loadDrives(): void {
    const flow = this.#flow;
    const session = this.#session();
    if (flow === null || session === null || flow.deviceId === null) {
      return;
    }
    const request: StaleGuard = { identity: flow.identity, revision: null, deviceId: flow.deviceId };
    const params: Record<string, unknown> = {};
    if (this.#localDeviceId() !== flow.deviceId) {
      params.targetDeviceId = flow.deviceId;
    }
    void session.client
      .call<DriveListing>(methods.LIST_DRIVES, params)
      .then((listing) => {
        const current = this.#guard(request, null, null);
        if (current === null) {
          return;
        }
        this.#flow = { ...current, drives: listing.drives };
        this.#commit();
      })
      .catch(() => {
        const current = this.#guard(request, null, null);
        if (current === null) {
          return;
        }
        this.#flow = { ...current, drives: [] };
        this.#commit();
      });
  }

  /**
   * `prepare_manual_space` (spaces.rs:2256-2306): probe — and optionally
   * create — a typed path on the OWNING device. A missing folder is never
   * silently created on plain Enter; only the ⌘⏎ path passes create.
   */
  #prepareManual(create: boolean, submit: boolean): void {
    const flow = this.#aliveFlow();
    const session = this.#session();
    if (flow === null || session === null || flow.submitBusy || flow.deviceId === null) {
      return;
    }
    const request: StaleGuard = { identity: flow.identity, revision: flow.revision, deviceId: flow.deviceId };
    const path = flow.query.trim();
    this.#manualInFlight = true;
    this.#flow = { ...flow, submitBusy: submit, error: null };
    this.#commit();
    void session.client
      .call<PrepareSpacePathReply>(methods.PREPARE_SPACE_PATH, {
        path,
        createIfMissing: create,
        // Required here, unlike the two loads: path syntax resolves on the
        // owning device, never assumed local.
        targetDeviceId: flow.deviceId,
      })
      .then((result) => {
        this.#manualInFlight = false;
        const current = this.#guard(request, null, null);
        if (current === null) {
          return;
        }
        const add = submit && result.exists;
        this.#flow = {
          ...current,
          submitBusy: false,
          manualPath: {
            path: result.path,
            exists: result.exists,
            gitDetected: result.gitDetected,
          },
          ...(add
            ? {
                listing: { path: result.path, entries: [], truncated: false },
                browserRepo: result.gitDetected,
              }
            : {}),
        };
        this.#commit();
        if (add) {
          this.#submitBrowsed();
        }
      })
      .catch((error: unknown) => {
        this.#manualInFlight = false;
        const current = this.#guard(request, null, null);
        if (current === null) {
          return;
        }
        this.#flow = { ...current, submitBusy: false, error: errorMessage(error) };
        this.#commit();
      });
  }

  // ── Internals ──────────────────────────────────────────────────────────

  /** The flow, but only while genuinely open — closing reads as gone. */
  #aliveFlow(): AddSpaceFlow | null {
    return this.#popup.isOpen() ? this.#flow : null;
  }

  #session(): EngineSession | null {
    return this.#context?.session ?? null;
  }

  #devices(): readonly Device[] {
    return this.#session()?.cache.getSnapshot().devices.rows ?? [];
  }

  #localDeviceId(): string | null {
    return this.#session()?.client.engineInfo?.deviceId ?? null;
  }

  #readyListing(): { path: string; entries: FolderEntry[]; truncated: boolean } | null {
    const listing = this.#flow?.listing;
    if (typeof listing === "string" || listing === undefined || listing === null) {
      return null;
    }
    if (!("entries" in listing)) {
      return null;
    }
    return listing;
  }

  /**
   * The shared response guard: alive (open, same identity era), then
   * `is_stale`, then — for folder loads — the browser path, the hidden
   * flag, and "the search became a manual path".
   */
  #guard(request: StaleGuard, path: string | null, hiddenQuery: boolean | null): AddSpaceFlow | null {
    const current = this.#aliveFlow();
    if (current === null || isStaleResponse(current, request)) {
      return null;
    }
    if (path !== null || hiddenQuery !== null) {
      if (current.browserPath !== path) {
        return null;
      }
      if (hiddenQuery !== null && current.hiddenQuery !== hiddenQuery) {
        return null;
      }
      if (manualPathQuery(current.query)) {
        return null;
      }
    }
    return current;
  }

  #descend(full: string, isRepo: boolean): void {
    const flow = this.#flow;
    if (flow === null) {
      return;
    }
    this.#flow = { ...flow, browserRepo: isRepo, query: "" };
    this.#loadFolders(full);
  }

  #commit(): void {
    this.#snapshot = {
      status: this.#popup.status(),
      flow: this.#flow,
      pendingSpaces: this.#pending,
    };
    for (const listener of [...this.#listeners]) {
      listener();
    }
  }
}

/**
 * The palette's module-level singleton — same shape as `sidebarStore` /
 * `fleetStore`. `open()`/`close()` are the hooks tickets 10 and 12 call.
 */
export const addSpaceStore = new AddSpaceStore();

/**
 * The fixed `mod-k` binding's toggle (`shell.rs:7832-7839`): the palette
 * mounted → close it; otherwise open it. Ticket 12's shell subscribes the
 * shortcut bus's `add-space-palette` event to this.
 */
export function toggleAddSpace(): void {
  if (addSpaceStore.getSnapshot().flow !== null) {
    addSpaceStore.close();
  } else {
    addSpaceStore.open();
  }
}

const subscribe = (listener: () => void) => addSpaceStore.subscribe(listener);
const getSnapshot = () => addSpaceStore.getSnapshot();

/** The mount phases plus the flow (null only once fully closed). */
export function useAddSpaceSnapshot(): AddSpaceSnapshot {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** The flow while the palette is mounted (open or closing); null when closed. */
export function useAddSpace(): AddSpaceFlow | null {
  return useAddSpaceSnapshot().flow;
}

/**
 * The optimistic space rows, for the spaces menu to merge by id (ticket 10):
 * a row appears here when its create is still on the wire and is replaced
 * by the watch frame's confirmed row — same id — once it lands. The array
 * reference only changes when the pending set itself changes, so this hook
 * does not re-render on every palette keystroke.
 */
export function usePendingSpaces(): readonly Space[] {
  const pending = (): readonly Space[] => addSpaceStore.getSnapshot().pendingSpaces;
  return useSyncExternalStore(subscribe, pending, pending);
}
