import type { FileTreeBatchOperation, FileTreeRowDecoration, GitStatusEntry } from "@pierre/trees";
import type { WorkspaceEntry, WorkspaceFileChange, WorkspaceFileSearchMatch } from "@roboco/proto";
import { LOAD_MORE_BASENAME } from "./tree-icons";

/**
 * Pure adapters between the files data layer and the trees library's model
 * vocabulary (ticket 06's replacement for the mutation halves of
 * `lib/file-tree.ts` and `lib/file-search-tree.ts`): wire git-status frames
 * → status-lane entries, directory pages → mutation batches, watch events →
 * invalidation targets, and search matches → the result tree's ordered path
 * list. Everything here is a pure function with its own suite; the stateful
 * model lives in `lib/workspace-tree.ts`.
 */

// ── Path vocabulary ────────────────────────────────────────────────────────

/**
 * The trees model's canonical path for a workspace entry: directories carry
 * a trailing slash, files and symlinks do not. Workspace paths are
 * "/"-separated and relative to the checkout root; the empty string is the
 * root directory.
 */
export function treePathOf(entry: Pick<WorkspaceEntry, "path" | "kind">): string {
  return entry.kind === "directory" ? `${entry.path}/` : entry.path;
}

/** The workspace path of a canonical tree path (drops the trailing slash). */
export function workspacePathOf(treePath: string): string {
  return treePath.endsWith("/") ? treePath.slice(0, -1) : treePath;
}

/** The workspace path of a tree directory (canonical or bare form). */
export function directoryWorkspacePath(treeDirectory: string): string {
  return treeDirectory.length === 0 ? "" : workspacePathOf(treeDirectory);
}

// ── Load-more marker ───────────────────────────────────────────────────────

/** The synthetic "Load more…" row path for a paginated directory (the old model's `"${directory} more"`). */
export function loadMoreMarkerPath(directory: string): string {
  return directory.length === 0 ? LOAD_MORE_BASENAME : `${directory}/${LOAD_MORE_BASENAME}`;
}

/** Whether a tree path is a load-more marker row. */
export function isLoadMoreMarkerPath(treePath: string): boolean {
  const slash = treePath.lastIndexOf("/");
  const basename = slash >= 0 ? treePath.slice(slash + 1) : treePath;
  return basename === LOAD_MORE_BASENAME;
}

/** The paginated directory a load-more marker row belongs to (workspace path). */
export function loadMoreMarkerDirectory(treePath: string): string {
  const slash = treePath.lastIndexOf("/");
  return slash >= 0 ? treePath.slice(0, slash) : "";
}

// ── Git status ─────────────────────────────────────────────────────────────

/**
 * The status-lane classification of one status row (`Decorations`'s
 * `state_kind` + the column-combination rule): staged/unstaged edits
 * collapse to `modified`, adds (and copies) to `added`, deletions and
 * unmerged entries to `deleted`, renames keep their kind, untracked
 * dominates. An unchanged row (both columns clean) carries nothing.
 */
function classifyGitStatus(index: string, worktree: string): "modified" | "added" | "deleted" | "renamed" | "untracked" | null {
  if (index === "untracked" || worktree === "untracked") {
    return "untracked";
  }
  const indexed = gitStateKind(index);
  const working = gitStateKind(worktree);
  if (indexed === null && working === null) {
    return null;
  }
  // Otherwise the worktree column wins the row, falling back to the index.
  return working ?? indexed ?? "modified";
}

function gitStateKind(state: string): "added" | "deleted" | "modified" | "renamed" | null {
  switch (state) {
    case "added":
    case "copied":
      return "added";
    case "modified":
    case "typeChanged":
      return "modified";
    case "deleted":
    case "unmerged":
      return "deleted";
    case "renamed":
      return "renamed";
    default:
      return null;
  }
}

/**
 * A `WatchWorkspaceGitStatus` frame's files → the library's status-lane
 * entries. The lane's folder rollup (a directory whose descendants changed
 * shows its change dot, and ignored directories dim their whole subtree)
 * is the library's own — this adapter only classifies the rows it carries,
 * so a partial status decorates exactly what it has and never reads clean.
 */
export function treeGitStatusEntries(
  files: readonly { readonly path: string; readonly index: string; readonly worktree: string }[] | null,
): GitStatusEntry[] {
  if (files === null) {
    return [];
  }
  const entries: GitStatusEntry[] = [];
  for (const file of files) {
    const status = classifyGitStatus(file.index, file.worktree);
    if (status !== null) {
      entries.push({ path: file.path, status });
    }
  }
  return entries;
}

/**
 * The ignored entries of a directory page → `ignored` status-lane entries
 * (the show-all dimming). Only DIRECT ignored entries are emitted — the
 * library inherits `ignored` down from ancestor directories itself, which
 * is the old model's `parentIgnored` propagation. Directories take their
 * canonical trailing-slash form so descendants inherit.
 */
export function ignoredStatusEntries(entries: readonly WorkspaceEntry[]): GitStatusEntry[] {
  const out: GitStatusEntry[] = [];
  for (const entry of entries) {
    if (entry.ignored) {
      out.push({ path: treePathOf(entry), status: "ignored" });
    }
  }
  return out;
}

// ── Directory pages → mutation batches ─────────────────────────────────────

/**
 * A `ListWorkspaceDirectory` page → the mutation batch that applies it.
 *
 * - An APPEND page (a cursor continuation, `nextCursor !== null`) only adds
 *   children the model does not know yet and re-trails the marker.
 * - The LAST page of a listing sequence (`nextCursor === null`) completes
 *   it: children the sequence never listed are removed (recursively), and
 *   the load-more marker goes away.
 *
 * `children` are the directory's CURRENT canonical child paths in the model
 * (the caller's bookkeeping); `seen` is the set of entries the current
 * listing SEQUENCE has established so far (empty at a fresh listing's
 * start, seeded with `children` when a load-more sequence begins). A real
 * entry colliding with the marker basename wins — the marker is simply not
 * (re-)added for that directory.
 */
export function pageOperations(input: {
  readonly directory: string;
  readonly entries: readonly WorkspaceEntry[];
  readonly nextCursor: string | null;
  readonly children: readonly string[];
  readonly seen: ReadonlySet<string>;
  readonly pendingMarker: boolean;
}): { readonly operations: FileTreeBatchOperation[]; readonly children: readonly string[]; readonly seen: ReadonlySet<string>; readonly marker: boolean } {
  const { directory, entries, nextCursor, children, seen, pendingMarker } = input;
  const markerPath = loadMoreMarkerPath(directory);
  const known = new Set(children);
  const incoming: string[] = [];
  for (const entry of entries) {
    incoming.push(treePathOf(entry));
  }
  const incomingSet = new Set(incoming);
  const nextSeen = new Set([...seen, ...incomingSet]);
  const operations: FileTreeBatchOperation[] = [];

  for (const treePath of incoming) {
    if (!known.has(treePath)) {
      operations.push({ path: treePath, type: "add" });
    }
  }

  if (nextCursor === null) {
    // The listing is complete: drop every child it never carried.
    for (const child of known) {
      if (child !== markerPath && !nextSeen.has(child)) {
        operations.push({ path: child, type: "remove", recursive: true });
      }
    }
    if (pendingMarker) {
      operations.push({ path: markerPath, type: "remove" });
    }
    return { operations, children: [...nextSeen], seen: nextSeen, marker: false };
  }

  const realEntryAtMarker = incomingSet.has(markerPath);
  if (!pendingMarker && !realEntryAtMarker) {
    operations.push({ path: markerPath, type: "add" });
  }
  return {
    operations,
    children: [...known, ...incoming.filter((treePath) => !known.has(treePath))],
    seen: nextSeen,
    marker: !realEntryAtMarker,
  };
}

// ── Watch events ───────────────────────────────────────────────────────────

/** Desktop watch.rs `sequence_needs_resync`. */
export function sequenceNeedsResync(previous: number | null, next: number): boolean {
  return previous !== null && next !== previous + 1;
}

/**
 * The directories a watch change invalidates (the old model's `parents`
 * set): every parent of a created/removed path, plus both sides of a
 * rename. `modified` touches nothing — the listing is unchanged and the
 * open document reacts through `onFileEvent`.
 */
export function invalidatedDirectories(change: WorkspaceFileChange): readonly string[] {
  const directories: string[] = [];
  const push = (path: string): void => {
    const slash = path.lastIndexOf("/");
    // The parent of a root-level path is the root directory ("").
    directories.push(slash >= 0 ? path.slice(0, slash) : "");
  };
  switch (change.kind) {
    case "created":
    case "removed":
      push(change.path);
      break;
    case "renamed":
      if (typeof change.oldPath === "string") {
        push(change.oldPath);
      }
      push(change.path);
      break;
    case "modified":
      break;
  }
  return directories;
}

/** A recursive remove — the only immediate mutation a watch event applies. */
export interface WatchRemoveOperation {
  readonly path: string;
  readonly type: "remove";
  readonly recursive: true;
}

/**
 * The immediate mutation a watch change applies before its parent
 * directory re-lists: removals drop their subtree, renames drop the old
 * path; creations are left to the parent re-list (the listing is the
 * source of truth for kinds). `null` when nothing immediate applies.
 */
export function watchRemoval(change: WorkspaceFileChange): WatchRemoveOperation | null {
  switch (change.kind) {
    case "removed":
      return { path: change.path, type: "remove", recursive: true };
    case "renamed":
      return typeof change.oldPath === "string" ? { path: change.oldPath, type: "remove", recursive: true } : null;
    default:
      return null;
  }
}

// ── Row decorations (loading / empty / error-retry) ─────────────────────

/** The row-visible slice of a directory's load state. */
export interface DirectoryRowState {
  readonly kind: "unloaded" | "loading" | "loaded" | "error";
  readonly message: string | null;
  readonly childrenCount: number;
}

/** The directory row's trailing decoration: its load state, spelled. */
export function directoryRowDecoration(
  state: DirectoryRowState,
  row: { readonly kind: "directory" | "file"; readonly isExpanded: boolean },
): FileTreeRowDecoration | null {
  if (row.kind !== "directory" || !row.isExpanded) {
    return null;
  }
  if (state.kind === "loading") {
    return { text: "Loading…", title: "Loading directory" };
  }
  if (state.kind === "error" && state.message !== null) {
    return {
      text: `${state.message} — Retry`,
      title: `${state.message} — click to retry`,
      parts: [
        { text: state.message, color: "var(--rb-danger)" },
        { text: " — Retry" },
      ],
    };
  }
  if (state.kind === "loaded" && state.childrenCount === 0) {
    return { text: "Empty", title: "Empty folder" };
  }
  return null;
}

// ── Search results → the result tree's ordered path list ───────────────────
/**
 * The search result tree's ordered path list — the port of
 * `buildSearchTree` + `sortSearchPaths`: flat matches grouped into a
 * synthetic ancestor tree keyed by PATH COMPONENTS (not name prefixes),
 * `bestScore` propagated up, siblings ordered by bestScore descending,
 * directories before files, then case-insensitive name, then path. The
 * emitted list is a pre-order walk, so parents always precede children.
 *
 * The trees model is handed `paths` in this order with a rank comparator
 * (`searchRankComparator`) and every directory in `directoryPaths`
 * initially expanded — the old search tree's default-open shape.
 */
export function searchTreePaths(
  matches: readonly WorkspaceFileSearchMatch[],
): { readonly orderedPaths: readonly string[]; readonly directoryPaths: readonly string[]; readonly rank: ReadonlyMap<string, number> } {
  const nodes = new Map<string, { kind: WorkspaceEntry["kind"]; score: number | null; bestScore: number; children: Set<string> }>();
  const roots = new Set<string>();

  for (const match of matches) {
    let parent: string | null = null;
    let path = "";
    for (const component of match.path.split("/").filter((part) => part.length > 0)) {
      path = path.length === 0 ? component : `${path}/${component}`;
      const isMatch = path === match.path;
      const existing = nodes.get(path);
      if (existing === undefined) {
        const node = { kind: isMatch ? match.kind : "directory", score: isMatch ? match.score : null, bestScore: match.score, children: new Set<string>() };
        nodes.set(path, node);
        if (parent === null) {
          roots.add(path);
        } else {
          nodes.get(parent)?.children.add(path);
        }
      } else if (isMatch) {
        existing.kind = match.kind;
        existing.score = Math.max(existing.score ?? Number.MIN_SAFE_INTEGER, match.score);
      }
      parent = path;
    }
  }

  for (const root of roots) {
    propagateBestScore(root, nodes);
  }

  const comparator = (left: string, right: string): number => {
    const leftNode = nodes.get(left);
    const rightNode = nodes.get(right);
    const leftScore = leftNode?.bestScore ?? Number.MIN_SAFE_INTEGER;
    const rightScore = rightNode?.bestScore ?? Number.MIN_SAFE_INTEGER;
    if (leftScore !== rightScore) {
      return rightScore - leftScore;
    }
    const leftDirectory = (leftNode?.kind ?? "directory") === "directory";
    const rightDirectory = (rightNode?.kind ?? "directory") === "directory";
    if (leftDirectory !== rightDirectory) {
      return leftDirectory ? -1 : 1;
    }
    const byName = basenameOf(left).toLowerCase().localeCompare(basenameOf(right).toLowerCase());
    if (byName !== 0) {
      return byName;
    }
    return left < right ? -1 : left > right ? 1 : 0;
  };

  const orderedPaths: string[] = [];
  const directoryPaths: string[] = [];
  const walk = (path: string): void => {
    const node = nodes.get(path);
    if (node === undefined) {
      return;
    }
    const isDirectory = node.kind === "directory";
    const canonical = isDirectory ? `${path}/` : path;
    orderedPaths.push(canonical);
    if (isDirectory) {
      directoryPaths.push(canonical);
    }
    for (const child of [...node.children].sort(comparator)) {
      walk(child);
    }
  };
  for (const root of [...roots].sort(comparator)) {
    walk(root);
  }

  const rank = new Map<string, number>();
  for (const [index, canonical] of orderedPaths.entries()) {
    rank.set(canonical, index);
  }
  return { orderedPaths, directoryPaths, rank };
}

function propagateBestScore(path: string, nodes: Map<string, { bestScore: number; score: number | null; children: Set<string> }>): number {
  const node = nodes.get(path);
  if (node === undefined) {
    return Number.MIN_SAFE_INTEGER;
  }
  let best = node.score ?? Number.MIN_SAFE_INTEGER;
  for (const child of node.children) {
    best = Math.max(best, propagateBestScore(child, nodes));
  }
  node.bestScore = best;
  return best;
}

function basenameOf(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash >= 0 ? path.slice(slash + 1) : path;
}
