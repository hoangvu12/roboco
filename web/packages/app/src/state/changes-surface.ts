import { useSyncExternalStore } from "react";
import {
  type DiffMode,
  type DiffScope,
  type FileFold,
} from "../lib/diff";
import { uiSettings } from "./ui-settings";

/**
 * The Changes surface's pane-level state — scope, base ref, layout, wrap, and
 * the per-file fold model — one instance per (chat, diff tab), the web peer
 * of the desktop's per-`Changes`-entity state. Ticket 07's right pane mounts
 * a surface's toolbar and body as SEPARATE element trees, and every diff tab
 * keeps its own scope selection for its whole life (`add_diff_surface`:
 * N clicks make N tabs), so this state cannot live in either component's
 * React tree. It lives here, keyed by the surface's stable minted id, and
 * both halves read it through `useSyncExternalStore`.
 *
 * `layout` and `wrap` persist globally through ticket 03's settings store
 * (`diffSplit`/`diffWrap`, written immediately on toggle — the desktop's
 * `settings::update` with `SavePolicy::Immediate`); the fold map and scope
 * are tab-local, in memory only.
 *
 * The fold model is the `collapsed`/`epoch` pair the Pierre diffs library's
 * items consume (ticket 02's `lib/changes-diff.ts`: `item.collapsed` plus a
 * version bump) — the library owns the collapse rendering; the old
 * hand-rolled tween fields died with the row model (ticket 04).
 */

export interface ChangesSurfaceSnapshot {
  readonly scope: DiffScope;
  readonly baseRef: string | null;
  readonly layout: DiffMode;
  readonly wrap: boolean;
  readonly folds: ReadonlyMap<string, FileFold>;
  /**
   * Bumped whenever the view-extent inputs change (scope, base, layout,
   * wrap) — the diff list scrolls back to the top.
   */
  readonly scrollEpoch: number;
  /**
   * The chat's conversation branch and its checkout's branch list, mirrored
   * by the body (which owns the wire) so the toolbar's `{branch} →` label
   * and base picker can render without their own fetch.
   */
  readonly branch: string | null;
  readonly branches: readonly string[];
  /**
   * The pinned commit sha (commit flavour only, `Changes::for_commit`) —
   * the surface's scope never moves off it.
   */
  readonly commitSha: string | null;
}

interface SurfaceState {
  scope: DiffScope;
  baseRef: string | null;
  layout: DiffMode;
  wrap: boolean;
  folds: ReadonlyMap<string, FileFold>;
  scrollEpoch: number;
  branch: string | null;
  branches: readonly string[];
  commitSha: string | null;
}

function freshState(): SurfaceState {
  const settings = uiSettings.getSnapshot();
  return {
    scope: "workingTree",
    baseRef: null,
    layout: settings.diffSplit ? "split" : "unified",
    wrap: settings.diffWrap,
    folds: new Map(),
    scrollEpoch: 0,
    branch: null,
    branches: EMPTY_BRANCHES,
    commitSha: null,
  };
}

const EMPTY_BRANCHES: readonly string[] = [];

export class ChangesSurfaceStore {
  readonly #bySurface = new Map<string, SurfaceState>();
  #version = 0;
  readonly #listeners = new Set<() => void>();
  /** The foldable paths of the ACTIVE surface registration. */
  #files: readonly string[] = [];

  getVersion = (): number => this.#version;

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  };

  /** The state for one (chat, diff tab) — created on first read. */
  snapshotFor(chatId: string, surfaceId: string): ChangesSurfaceSnapshot {
    const key = surfaceKey(chatId, surfaceId);
    let state = this.#bySurface.get(key);
    if (state === undefined) {
      state = freshState();
      this.#bySurface.set(key, state);
    }
    return state;
  }

  /** The viewer's current parse (the foldable paths), registered so fold
   * actions know what can fold. */
  setFiles(files: readonly string[]): void {
    this.#files = files;
  }

  /**
   * The body's mirror of the chat's branch context — the toolbar's
   * ref-selector inputs. No notification when nothing changed.
   */
  setChatContext(chatId: string, surfaceId: string, context: { branch: string | null; branches: readonly string[] }): void {
    this.#update(chatId, surfaceId, (state) => {
      if (state.branch === context.branch && state.branches === context.branches) {
        return null;
      }
      return { ...state, branch: context.branch, branches: context.branches };
    });
  }

  setScope(chatId: string, surfaceId: string, scope: DiffScope): void {
    this.#update(chatId, surfaceId, (state) => {
      if (state.commitSha !== null) {
        // A commit-pinned pane never offers its scope back (for_commit).
        return null;
      }
      if (state.scope === scope) {
        return null;
      }
      return {
        ...state,
        scope,
        // Leaving the branch scope drops its base; entering branch keeps any
        // previously picked base for the return trip (desktop parity: the
        // store's base_ref survives scope switches, only the fetch changes).
        baseRef: scope === "branch" ? state.baseRef : null,
        scrollEpoch: state.scrollEpoch + 1,
      };
    });
  }

  /**
   * Pin a commit-diff tab to its sha (`Changes::for_commit`): the scope
   * becomes `commit` for the surface's whole life — there is no scope row
   * to move it back.
   */
  pinCommit(chatId: string, surfaceId: string, sha: string): void {
    this.#update(chatId, surfaceId, (state) => {
      if (state.commitSha === sha) {
        return null;
      }
      return {
        ...state,
        scope: "commit",
        baseRef: null,
        commitSha: sha,
        scrollEpoch: state.scrollEpoch + 1,
      };
    });
  }

  setBaseRef(chatId: string, surfaceId: string, base: string): void {
    this.#update(chatId, surfaceId, (state) => {
      if (state.scope !== "branch" || state.baseRef === base) {
        return null;
      }
      return { ...state, baseRef: base, scrollEpoch: state.scrollEpoch + 1 };
    });
  }

  /**
   * Unified ⇄ split (`toggle_mode`): persisted immediately, every file's
   * horizontal scroll reset, the row model re-flattened by the re-render.
   */
  toggleLayout(chatId: string, surfaceId: string): void {
    this.#update(chatId, surfaceId, (state) => {
      const layout: DiffMode = state.layout === "split" ? "unified" : "split";
      uiSettings.update({ diffSplit: layout === "split" }, "immediate");
      return { ...state, layout, scrollEpoch: state.scrollEpoch + 1 };
    });
  }

  /**
   * Wrap ⇄ nowrap (`toggle_wrap`) — persisted immediately; the re-render
   * re-lays every row out through the library's wrap mode.
   */
  toggleWrap(chatId: string, surfaceId: string): void {
    this.#update(chatId, surfaceId, (state) => {
      const wrap = !state.wrap;
      uiSettings.update({ diffWrap: wrap }, "immediate");
      return { ...state, wrap, scrollEpoch: state.scrollEpoch + 1 };
    });
  }

  /**
   * Fold/unfold one file (`toggle_fold`): a steady write — the library's
   * `collapsed` item flag renders it; the epoch bumps the item's version
   * so the controlled update lands.
   */
  toggleFold(chatId: string, surfaceId: string, path: string): void {
    if (!this.#files.includes(path)) {
      return;
    }
    this.#update(chatId, surfaceId, (state) => {
      const current = state.folds.get(path);
      const collapsed = !(current?.collapsed ?? false);
      const fold: FileFold = {
        collapsed,
        epoch: (current?.epoch ?? 0) + 1,
      };
      const folds = new Map(state.folds);
      folds.set(path, fold);
      return { ...state, folds };
    });
  }

  /**
   * Collapse every file, or expand them all when everything is already shut
   * (`toggle_collapse_all`) — one steady write over the registered paths.
   */
  toggleCollapseAll(chatId: string, surfaceId: string): void {
    if (this.#files.length === 0) {
      return;
    }
    const collapse = !this.#files.every(
      (path) => this.snapshotFor(chatId, surfaceId).folds.get(path)?.collapsed === true,
    );
    this.#update(chatId, surfaceId, (state) => {
      const folds = new Map<string, FileFold>();
      for (const path of this.#files) {
        folds.set(path, { collapsed: collapse, epoch: 0 });
      }
      return { ...state, folds };
    });
  }

  /** Drop a closed tab's state (`diffs.remove` teardown). */
  dispose(chatId: string, surfaceId: string): void {
    const key = surfaceKey(chatId, surfaceId);
    const state = this.#bySurface.get(key);
    if (state === undefined) {
      return;
    }
    this.#bySurface.delete(key);
    this.#version += 1;
    this.#emit();
  }

  #update(chatId: string, surfaceId: string, next: (state: SurfaceState) => SurfaceState | null): void {
    const key = surfaceKey(chatId, surfaceId);
    let state = this.#bySurface.get(key);
    if (state === undefined) {
      state = freshState();
      this.#bySurface.set(key, state);
    }
    const updated = next(state);
    if (updated === null) {
      return;
    }
    this.#bySurface.set(key, updated);
    this.#version += 1;
    this.#emit();
  }

  #emit(): void {
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

function surfaceKey(chatId: string, surfaceId: string): string {
  return `${chatId}\u0000${surfaceId}`;
}

export const changesSurfaceStore = new ChangesSurfaceStore();

/**
 * Bind one (chat, diff tab) surface's state. Both the toolbar and the body
 * call this; the store instance is shared, the snapshot read per render.
 */
export function useChangesSurface(chatId: string, surfaceId: string): ChangesSurfaceSnapshot {
  const subscribe = changesSurfaceStore.subscribe;
  const version = useSyncExternalStore(subscribe, changesSurfaceStore.getVersion, changesSurfaceStore.getVersion);
  void version;
  return changesSurfaceStore.snapshotFor(chatId, surfaceId);
}
