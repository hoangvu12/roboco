import { FileTree, type FileTreeDirectoryHandle, type FileTreeItemHandle, type FileTreeRowDecoration, type FileTreeRowDecorationContext, type GitStatusEntry } from "@pierre/trees";
import type { WorkspaceDirectoryPage, WorkspaceFileChange, WorkspaceFileChanges } from "@roboco/proto";
import type { WatchHandlers } from "@roboco/engine-client";
import {
  directoryRowDecoration,
  ignoredStatusEntries,
  invalidatedDirectories,
  isLoadMoreMarkerPath,
  loadMoreMarkerDirectory,
  pageOperations,
  sequenceNeedsResync,
  treeGitStatusEntries,
  workspacePathOf,
  watchRemoval,
} from "./tree-adapters";
import { FILE_TREE_DENSITY, FILE_TREE_ROW_HEIGHT, treeFileIcons, treeSortComparator } from "./tree-icons";
import type { WorkspaceFilesClient } from "./files-client";
import { describeFilesError } from "./files-client";

/**
 * The workspace file tree on the Pierre trees library (ticket 06) — the
 * replacement for the hand-rolled `lib/file-tree.ts` model. The library
 * model (`tree`) owns the visible projection (virtualization, expansion,
 * selection, keyboard nav, the git status lane, icons); this wrapper keeps
 * the DATA layer the adoption must preserve: the per-directory
 * `ListWorkspaceDirectory` loads with pagination, the `WATCH_WORKSPACE_FILES`
 * frames with sequence-gap resync, the git-status watch join, ignored
 * dimming, the reveal walk, and the load-more/error/empty row states.
 *
 * The wrapper is headless-safe: like the old model it drives and observes
 * the trees model without a mounted host (the file viewer creates one per
 * surface purely for the watch events), and the panel renders
 * `<FileTree model={model.tree}>`.
 */

/** Watch outcomes the open document cares about (watch.rs parity). */
export type FileWatchEvent =
  | { readonly kind: "created" | "modified" | "removed"; readonly path: string }
  | { readonly kind: "renamed"; readonly path: string; readonly oldPath: string }
  | { readonly kind: "resync" };

/** The panel-facing snapshot: chrome state, not tree rows (the library owns those). */
export interface WorkspaceTreeSnapshot {
  readonly includeIgnored: boolean;
  /** Set while the change stream itself is degraded (desktop: watch_error). */
  readonly watchError: string | null;
  /** Desktop `tree_has_content` — the root has produced a page at least once. */
  readonly rootLoaded: boolean;
  /**
   * The surface-level root error (desktop `FilesSurface::error`): set when a
   * root load fails, shown INSTEAD of the tree while the root has never
   * loaded (mod.rs:213-247).
   */
  readonly rootError: string | null;
  /** Per-directory load failures (workspace path → engine message) for the retry affordances. */
  readonly directoryErrors: ReadonlyMap<string, string>;
  /** The status-lane entries currently applied (git watch + ignored dimming). */
  readonly gitStatus: readonly GitStatusEntry[];
}

export interface WorkspaceTreeModelOptions {
  readonly client: WorkspaceFilesClient;
  /** Subscribe the workspace change stream; omitted in tests (no watch). */
  readonly watch?: (handlers: WatchHandlers<WorkspaceFileChanges>) => { cancel(): void };
  readonly onFileEvent?: (event: FileWatchEvent) => void;
  readonly includeIgnored?: boolean;
}

/** One directory's listing epoch (the old model's `DirectoryLoad` + children). */
interface DirectoryState {
  /** `unloaded` before the first request, `loading` in flight, `error` failed. */
  kind: "unloaded" | "loading" | "loaded" | "error";
  /** The cursor of the in-flight/failed request (retry uses it, like the desktop). */
  requestCursor: string | null;
  /** The next page token while paginated; null when the listing is exhausted. */
  nextCursor: string | null;
  /** The directory's real children as canonical tree paths. */
  children: string[];
  /** The ignored children of the current listing epoch (canonical tree paths). */
  ignoredChildren: string[];
  /** The entries established by the current listing sequence so far. */
  seen: ReadonlySet<string> | null;
  /** Whether the load-more marker row is currently in the model. */
  marker: boolean;
  /** A stale directory re-lists on its next expansion (watch invalidation). */
  stale: boolean;
  hasLoaded: boolean;
  /** The failure message while `kind === "error"`. */
  message: string | null;
}

const ROOT = "";

export class WorkspaceTreeModel {
  readonly tree: FileTree;
  readonly #client: WorkspaceFilesClient;
  readonly #watch: WorkspaceTreeModelOptions["watch"];
  readonly #onFileEvent: WorkspaceTreeModelOptions["onFileEvent"];
  readonly #directories = new Map<string, DirectoryState>();
  readonly #listeners = new Set<() => void>();
  #includeIgnored: boolean;
  #rootError: string | null = null;
  #generation = 0;
  #watchSequence: number | null = null;
  #watchError: string | null = null;
  #watchHandle: { cancel(): void } | null = null;
  #started = false;
  #disposed = false;
  #gitEntries: readonly GitStatusEntry[] = [];
  #snapshot: WorkspaceTreeSnapshot;

  constructor(options: WorkspaceTreeModelOptions) {
    this.#client = options.client;
    this.#watch = options.watch;
    this.#onFileEvent = options.onFileEvent;
    this.#includeIgnored = options.includeIgnored ?? false;
    this.tree = new FileTree({
      // The root page lands through `start()`; the model begins empty.
      paths: [],
      // Upstream beta bugs #941/#635: flattened chains render badly and
      // ignore gitignore dimming — never enable flattening.
      flattenEmptyDirectories: false,
      // The old tree's order (directories first, then names) with the
      // load-more marker forced after its siblings.
      sort: treeSortComparator,
      itemHeight: FILE_TREE_ROW_HEIGHT,
      density: FILE_TREE_DENSITY,
      icons: treeFileIcons,
      gitStatus: [],
      renderRowDecoration: (context) => this.#rowDecoration(context),
    });
    // Expansion is model-internal: the trees mutation stream carries no
    // expand/collapse events, so every projection change re-checks the
    // directories we track — an expanded-but-unloaded directory is a load
    // request (the lazy-loading seam).
    this.tree.subscribe(() => this.#checkExpansionLoads());
    this.#snapshot = {
      includeIgnored: this.#includeIgnored,
      watchError: null,
      rootLoaded: false,
      rootError: null,
      directoryErrors: new Map(),
      gitStatus: [],
    };
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────

  /** Begin the root listing and the change stream. */
  start(): void {
    if (this.#disposed || this.#started) {
      return;
    }
    this.#started = true;
    this.#ensureDirectory(ROOT);
    this.#requestDirectory(ROOT, null);
    if (this.#watch !== undefined && this.#watchHandle === null) {
      this.#watchHandle = this.#watch({
        onItem: (frame) => this.#applyChanges(frame),
        onEnd: (error) => {
          // An ended stream re-subscribes on the next connection; the
          // desktop surfaces the interruption ("File updates interrupted —
          // retrying").
          this.#watchError = error !== undefined ? describeFilesError(error) : null;
          this.#commit();
        },
      });
    }
  }

  /** Stop the watch; in-flight loads settle harmlessly against the guard. */
  dispose(): void {
    this.#disposed = true;
    this.#watchHandle?.cancel();
    this.#watchHandle = null;
    this.#listeners.clear();
    this.tree.cleanUp();
  }

  // ── Observation ───────────────────────────────────────────────────────

  getSnapshot(): WorkspaceTreeSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  includeIgnored(): boolean {
    return this.#includeIgnored;
  }

  isExpanded(path: string): boolean {
    return treeDirectoryHandle(this.tree.getItem(path))?.isExpanded() ?? false;
  }

  // ── Git status lane ───────────────────────────────────────────────────

  /**
   * `apply_git_status` (files/git_status.rs ensure/release): join the
   * frame's status onto the lane. An unavailable (null) status clears —
   * never reports clean, the rows simply stop carrying color.
   */
  applyGitStatus(
    status: { readonly path: string; readonly index: string; readonly worktree: string }[] | null,
  ): void {
    this.#gitEntries = treeGitStatusEntries(status);
    this.#syncGitStatus();
    this.#commit();
  }

  // ── Load driving ──────────────────────────────────────────────────────

  /** Desktop "Load more" row — fetch the paginated directory's next page. */
  loadMore(directory: string): void {
    const state = this.#directories.get(directory);
    if (state === undefined || state.kind !== "loaded" || state.nextCursor === null) {
      return;
    }
    this.#requestDirectory(directory, state.nextCursor);
  }

  /**
   * The panel's row-activation helper: a load-more marker row loads its
   * directory's next page (returns true); anything else is the caller's to
   * activate (a file open).
   */
  loadMoreIfMarker(treePath: string): boolean {
    if (!isLoadMoreMarkerPath(treePath)) {
      return false;
    }
    this.loadMore(loadMoreMarkerDirectory(treePath));
    return true;
  }

  /**
   * Retry a failed directory load — the error-row affordance. A failed
   * directory stays expanded but empty; the retry re-issues the request at
   * the failed cursor and re-expands (the row's click collapsed it).
   */
  retryDirectory(directory: string): void {
    const state = this.#directories.get(directory);
    if (state === undefined || state.kind !== "error") {
      return;
    }
    treeDirectoryHandle(this.tree.getItem(directory))?.expand();
    this.#requestDirectory(directory, state.requestCursor);
  }

  /**
   * Desktop `refresh` (mod.rs:748): invalidate everything and reload the
   * root plus every expanded, loaded directory — the watch-error banner's
   * "Refresh now" forced resync.
   */
  refresh(): void {
    this.#rootError = null;
    this.#markAllStale();
    this.#reloadExpanded();
    this.#commit();
  }

  /** Desktop `retry_root` — the root-error block's Retry button. */
  retryRoot(): void {
    this.#rootError = null;
    this.#requestDirectory(ROOT, null);
    this.#commit();
  }

  /** Desktop `set_include_ignored`: flips the flag and re-lists everything. */
  setIncludeIgnored(includeIgnored: boolean): void {
    if (this.#includeIgnored === includeIgnored) {
      return;
    }
    this.#includeIgnored = includeIgnored;
    this.#generation += 1;
    this.#directories.clear();
    this.#rootError = null;
    this.#watchSequence = null;
    this.tree.resetPaths([], {});
    this.#ensureDirectory(ROOT);
    this.#requestDirectory(ROOT, null);
    this.#commit();
  }

  /**
   * Desktop `reveal_search_result` (search.rs:390): list the match's
   * ancestors — one `ListWorkspaceDirectory` per ancestor, applied page by
   * page so the tree fills in as the reveal walks down — then expand each
   * ancestor and select the row. Returns the engine's error message on
   * failure, or null on success.
   */
  async revealInTree(path: string): Promise<string | null> {
    const generation = this.#generation;
    const ancestors: string[] = [];
    let current = parentPath(path);
    while (current !== null && current !== ROOT) {
      ancestors.push(current);
      current = parentPath(current);
    }
    ancestors.reverse();
    const directories = [ROOT, ...ancestors];

    const pages: { directory: string; page: WorkspaceDirectoryPage }[] = [];
    for (const directory of directories) {
      try {
        const page = await this.#client.listDirectory(directory, this.#includeIgnored);
        if (this.#disposed || generation !== this.#generation) {
          return null;
        }
        pages.push({ directory, page });
      } catch (error: unknown) {
        if (this.#disposed || generation !== this.#generation) {
          return null;
        }
        return describeFilesError(error);
      }
    }
    if (this.#disposed || generation !== this.#generation) {
      return null;
    }
    for (const [index, { directory, page }] of pages.entries()) {
      this.#applyPage(directory, page, generation);
      const ancestor = ancestors[index - 1];
      if (ancestor !== undefined) {
        treeDirectoryHandle(this.tree.getItem(ancestor))?.expand();
      }
    }
    this.tree.getItem(path)?.select();
    this.tree.scrollToPath(path, { focus: true });
    return null;
  }

  // ── Watch application (watch.rs parity) ───────────────────────────────

  #applyChanges(frame: WorkspaceFileChanges): void {
    if (this.#disposed || typeof frame?.sequence !== "number" || !Array.isArray(frame.changes)) {
      return;
    }
    const gap = sequenceNeedsResync(this.#watchSequence, frame.sequence);
    this.#watchSequence = frame.sequence;
    this.#watchError = null;
    if (frame.resyncRequired || gap) {
      this.#markAllStale();
      this.#reloadExpanded();
      this.#onFileEvent?.({ kind: "resync" });
      this.#commit();
      return;
    }
    const parents = new Set<string>();
    for (const change of frame.changes) {
      if (typeof change?.path !== "string") {
        continue;
      }
      switch (change.kind) {
        case "created": {
          this.#onFileEvent?.({ kind: "created", path: change.path });
          for (const parent of invalidatedDirectories(change)) {
            parents.add(parent);
          }
          break;
        }
        case "modified":
          this.#onFileEvent?.({ kind: "modified", path: change.path });
          break;
        case "removed": {
          this.#onFileEvent?.({ kind: "removed", path: change.path });
          this.#applyWatchRemoval(change);
          for (const parent of invalidatedDirectories(change)) {
            parents.add(parent);
          }
          break;
        }
        case "renamed": {
          const oldPath = typeof change.oldPath === "string" ? change.oldPath : null;
          this.#onFileEvent?.({ kind: "renamed", path: change.path, oldPath: oldPath ?? change.path });
          this.#applyWatchRemoval(change);
          for (const parent of invalidatedDirectories(change)) {
            parents.add(parent);
          }
          break;
        }
      }
    }
    for (const parent of parents) {
      const state = this.#directories.get(parent);
      if (state !== undefined) {
        state.stale = true;
      }
    }
    for (const parent of parents) {
      // Desktop: only expanded directories reload in the background.
      if (this.#isExpandedDirectory(parent) && this.#directories.get(parent)?.hasLoaded) {
        this.#requestDirectory(parent, null);
      }
    }
    this.#commit();
  }

  /** The immediate part of a watch change: drop the removed/renamed-away subtree. */
  #applyWatchRemoval(change: WorkspaceFileChange): void {
    const operation = watchRemoval(change);
    if (operation === null) {
      return;
    }
    if (this.tree.getItem(operation.path) !== null) {
      this.tree.remove(operation.path, { recursive: true });
    }
    this.#pruneBookkeeping(operation.path);
  }

  #pruneBookkeeping(path: string): void {
    const parent = parentPath(path);
    if (parent !== null) {
      const state = this.#directories.get(parent);
      if (state !== undefined) {
        state.children = state.children.filter((child) => child !== path && !child.startsWith(`${path}/`));
      }
    }
    for (const key of [...this.#directories.keys()]) {
      if (key === path || key.startsWith(`${path}/`)) {
        this.#directories.delete(key);
      }
    }
  }

  // ── Load driving (private) ────────────────────────────────────────────

  #ensureDirectory(directory: string): DirectoryState {
    let state = this.#directories.get(directory);
    if (state === undefined) {
      state = {
        kind: "unloaded",
        requestCursor: null,
        nextCursor: null,
        children: [],
        ignoredChildren: [],
        seen: null,
        marker: false,
        stale: false,
        hasLoaded: false,
        message: null,
      };
      this.#directories.set(directory, state);
    }
    return state;
  }

  #requestDirectory(directory: string, cursor: string | null): void {
    if (this.#disposed) {
      return;
    }
    const state = this.#directories.get(directory);
    if (state === undefined || state.kind === "loading") {
      return;
    }
    const generation = this.#generation;
    state.kind = "loading";
    state.requestCursor = cursor;
    state.message = null;
    state.stale = false;
    if (cursor === null) {
      // A fresh listing epoch replaces the directory's children when its
      // final page lands; a continuation extends the one in progress.
      state.seen = new Set();
      state.ignoredChildren = [];
    } else if (state.seen === null) {
      state.seen = new Set(state.children);
    }
    void this.#client
      .listDirectory(directory, this.#includeIgnored, cursor ?? undefined)
      .then((page) => {
        this.#applyPage(directory, page, generation);
      })
      .catch((error: unknown) => {
        this.#failLoad(directory, cursor, describeFilesError(error), generation);
      });
  }

  #failLoad(directory: string, cursor: string | null, message: string, generation: number): void {
    if (this.#disposed || generation !== this.#generation) {
      return;
    }
    const state = this.#directories.get(directory);
    if (state === undefined) {
      return;
    }
    state.kind = "error";
    state.requestCursor = cursor;
    state.message = message;
    // mod.rs:871-873 — a root failure also sets the surface-level error the
    // root-error block renders.
    if (directory === ROOT) {
      this.#rootError = message;
    }
    this.#commit();
  }

  /** Desktop `apply_page`: append-or-replace pages, deletion only on the last. */
  #applyPage(directory: string, page: WorkspaceDirectoryPage, generation: number): void {
    if (this.#disposed || generation !== this.#generation) {
      return;
    }
    const state = this.#ensureDirectory(directory);
    const nextCursor = page.nextCursor ?? null;
    const { operations, children, seen, marker } = pageOperations({
      directory,
      entries: page.entries,
      nextCursor,
      children: state.children,
      seen: state.seen ?? new Set(),
      pendingMarker: state.marker,
    });
    // The model is the arbiter: only remove what it actually holds, and
    // every removal is recursive (we never want the non-empty throw).
    const applicable = operations.filter((operation) => operation.type !== "remove" || this.tree.getItem(operation.path) !== null);
    if (applicable.length > 0) {
      this.tree.batch(applicable);
    }
    for (const entry of page.entries) {
      if (entry.kind === "directory") {
        // A newly listed directory starts unloaded — the expansion check
        // turns its first expansion into a load request.
        this.#ensureDirectory(entry.path);
      }
    }
    state.children = [...children];
    state.seen = nextCursor === null ? null : seen;
    state.marker = marker;
    state.nextCursor = nextCursor;
    state.ignoredChildren = [...state.ignoredChildren, ...ignoredStatusEntries(page.entries).map((entry) => entry.path)];
    state.kind = "loaded";
    state.stale = false;
    state.hasLoaded = true;
    if (directory === ROOT) {
      // mod.rs:866 — any successful root page clears the surface error.
      this.#rootError = null;
    }
    this.#syncGitStatus();
    this.#commit();
  }

  /**
   * The lazy-loading seam: every projection change re-checks the tracked
   * directories — one that is expanded but unloaded (or stale) requests its
   * listing. Loading and failed directories are skipped so a failure can
   * never loop; a failed directory's retry is explicit (click, refresh).
   */
  #checkExpansionLoads(): void {
    if (this.#disposed) {
      return;
    }
    for (const [directory, state] of this.#directories) {
      if (directory === ROOT || state.kind === "loading" || state.kind === "error") {
        continue;
      }
      const item = treeDirectoryHandle(this.tree.getItem(directory));
      if (item === null || !item.isExpanded()) {
        continue;
      }
      if (!state.hasLoaded || state.stale) {
        this.#requestDirectory(directory, null);
      }
    }
  }

  #markAllStale(): void {
    for (const state of this.#directories.values()) {
      state.stale = true;
    }
  }

  #reloadExpanded(): void {
    const targets = [...this.#directories]
      .filter(([directory, state]) => (directory === ROOT || this.#isExpandedDirectory(directory)) && state.hasLoaded)
      .map(([directory]) => directory);
    targets.sort((a, b) => a.split("/").length - b.split("/").length);
    for (const path of targets) {
      this.#requestDirectory(path, null);
    }
    if (!this.#directories.get(ROOT)?.hasLoaded) {
      this.#requestDirectory(ROOT, null);
    }
  }

  #isExpandedDirectory(directory: string): boolean {
    if (directory === ROOT) {
      return true;
    }
    return treeDirectoryHandle(this.tree.getItem(directory))?.isExpanded() ?? false;
  }

  // ── Status lane join ──────────────────────────────────────────────────

  /**
   * The lane merges both sources: the git-status watch's classified entries
   * and the ignored listing entries (the show-all dimming). Both replace
   * wholesale, so the join recomputes the full set on either change.
   */
  #syncGitStatus(): void {
    this.tree.setGitStatus([...this.#gitEntries, ...this.#ignoredEntries()]);
  }

  /** The ignored listing entries of every tracked directory (the dimming source). */
  #ignoredEntries(): GitStatusEntry[] {
    const ignored: GitStatusEntry[] = [];
    for (const state of this.#directories.values()) {
      for (const treePath of state.ignoredChildren) {
        ignored.push({ path: treePath, status: "ignored" });
      }
    }
    return ignored;
  }

  // ── Row decorations (loading / empty / error-retry) ───────────────────

  #rowDecoration(context: FileTreeRowDecorationContext): FileTreeRowDecoration | null {
    const { item, row } = context;
    if (item.kind !== "directory") {
      return null;
    }
    const state = this.#directories.get(workspacePathOf(item.path));
    if (state === undefined) {
      return null;
    }
    return directoryRowDecoration(
      { kind: state.kind, message: state.message, childrenCount: state.children.length },
      row,
    );
  }

  // ── Snapshot ──────────────────────────────────────────────────────────

  #commit(): void {
    if (this.#disposed) {
      return;
    }
    const directoryErrors = new Map<string, string>();
    for (const [directory, state] of this.#directories) {
      if (state.kind === "error" && state.message !== null) {
        directoryErrors.set(directory, state.message);
      }
    }
    this.#snapshot = {
      includeIgnored: this.#includeIgnored,
      watchError: this.#watchError,
      rootLoaded: this.#directories.get(ROOT)?.hasLoaded === true,
      rootError: this.#rootError,
      directoryErrors,
      gitStatus: [...this.#gitEntries, ...this.#ignoredEntries()],
    };
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

/** Desktop model.rs `parent_path` — workspace paths are "/" separated. */
function parentPath(path: string): string | null {
  const slash = path.lastIndexOf("/");
  if (slash >= 0) {
    return path.slice(0, slash);
  }
  return path.length > 0 ? ROOT : null;
}

/** The library's item handles do not narrow through `isDirectory()` — use the structural check. */
function treeDirectoryHandle(item: FileTreeItemHandle | null): FileTreeDirectoryHandle | null {
  return item !== null && "toggle" in item ? item : null;
}
