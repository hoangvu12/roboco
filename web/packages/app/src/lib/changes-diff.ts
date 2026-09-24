/**
 * The Changes pane's Pierre-diffs adapter (web-pierre-adoption, ticket 02) —
 * the thin seam between the resolved `CheckoutDiff` patch string and the
 * library's parsed diff metadata + controlled items. The data layer (the
 * watch, scoped captures, retry — `state/changes-store.ts`) is untouched;
 * this module only shapes what the renderer consumes.
 *
 * Two pure functions plus their shared state:
 *
 * - `parseDiffFiles` — patch string → `FileDiffMetadata[]`, memoized per
 *   (checkout, checksum, scope, base) through the same `parseKey`
 *   fingerprint the old hand-rolled parse cache used, with the same
 *   ~8-entry LRU discipline. The cache key also seeds the library's
 *   per-file highlight `cacheKey`s (collision-safe: parse index + file
 *   index), so re-renders and re-mounted surfaces never re-tokenize.
 * - `diffCodeItems` — the parsed metadata + the surface store's fold map →
 *   the library's controlled `CodeViewItem[]`. Each item's `version` is the
 *   controlled-update channel: the library only adopts a changed
 *   `fileDiff`/`collapsed` when the version moves, so the version tracks
 *   both the parse identity and the file's fold epoch.
 * - `fileDiffNotices` — the per-file notice copy (new/deleted/binary/mode)
 *   for the library header's metadata slot, the old `fileNotices()` arm the
 *   hand-rolled renderer carried as notice rows.
 *
 * Nothing here touches React or the DOM; the pure suite covers it directly.
 */

import { parsePatchFiles, type CodeViewItem, type FileDiffMetadata } from "@pierre/diffs";
import { parseKey, type DiffScope, type FileFold } from "./diff";

/** The resolved diff's parsed-patch inputs (the `CheckoutDiff` subset). */
export interface DiffPatchInput {
  readonly checkoutId: string;
  readonly checksum: string;
  readonly patch: string;
}

/** The parse cache's LRU bound — the old `useParsedDiff` discipline. */
const PARSE_CACHE_MAX = 8;

const EMPTY_FILES: readonly FileDiffMetadata[] = [];

const parseCache = new Map<string, readonly FileDiffMetadata[]>();

/**
 * Parse a resolved diff's patch string through the library's parser — once
 * per (checkout, checksum, scope, base), memoized. `null` (nothing
 * resolved yet) parses to nothing, so callers need no conditional branch.
 */
export function parseDiffFiles(
  diff: DiffPatchInput | null,
  scope: DiffScope,
  baseRef: string | null,
): readonly FileDiffMetadata[] {
  if (diff === null) {
    return EMPTY_FILES;
  }
  const key = parseKey(diff.checkoutId, diff.checksum, scope, baseRef);
  const cached = parseCache.get(key);
  if (cached !== undefined) {
    // LRU: a hit re-times the entry.
    parseCache.delete(key);
    parseCache.set(key, cached);
    return cached;
  }
  const files = parsePatchFiles(diff.patch, key).flatMap((patch) => patch.files);
  parseCache.set(key, files);
  if (parseCache.size > PARSE_CACHE_MAX) {
    const oldest = parseCache.keys().next().value;
    if (oldest !== undefined) {
      parseCache.delete(oldest);
    }
  }
  return files;
}

// ---------------------------------------------------------------------------
// Fold/version state → controlled items
// ---------------------------------------------------------------------------

/**
 * The per-file version ledger: an item's version moves only when its
 * `fileDiff` identity or fold state changes, which is exactly when the
 * library must adopt the new item. Keyed weakly by the metadata object, so
 * a fresh parse (new objects) re-versions every file while a pure re-render
 * (same objects, same folds) keeps the versions — and the library's layout
 * and element pools — untouched.
 */
let itemVersions = new WeakMap<FileDiffMetadata, { version: number; collapsed: boolean }>();
let nextVersion = 1;

/**
 * The parsed files + the surface store's fold map → the library's controlled
 * items. The item `id` is the file's (post-change) path — stable across
 * scope switches for the same path, so the library reuses its instances; the
 * folds stay keyed by that same path (`changesSurfaceStore.toggleFold`).
 */
export function diffCodeItems(
  files: readonly FileDiffMetadata[],
  folds: ReadonlyMap<string, FileFold>,
): CodeViewItem<undefined>[] {
  const items: CodeViewItem<undefined>[] = [];
  for (const fileDiff of files) {
    const collapsed = folds.get(fileDiff.name)?.collapsed ?? false;
    let record = itemVersions.get(fileDiff);
    if (record === undefined || record.collapsed !== collapsed) {
      record = { version: nextVersion, collapsed };
      nextVersion += 1;
      itemVersions.set(fileDiff, record);
    }
    items.push({
      id: fileDiff.name,
      type: "diff",
      fileDiff,
      collapsed,
      version: record.version,
    });
  }
  return items;
}

// ---------------------------------------------------------------------------
// File notices
// ---------------------------------------------------------------------------

/**
 * The per-file notices for the library header's metadata slot — the old
 * renderer's `fileNotices()` copy: status word, binary marker, then parser
 * notices. Renames do not carry a notice: the library's default header
 * already renders `prevName → name` inline.
 */
export function fileDiffNotices(fileDiff: FileDiffMetadata): string[] {
  const notices: string[] = [];
  switch (fileDiff.type) {
    case "new":
      notices.push("New file");
      break;
    case "deleted":
      notices.push("Deleted file");
      break;
    case "rename-pure":
    case "rename-changed":
      break;
    case "change":
      break;
  }
  if (isBinaryFileDiff(fileDiff)) {
    notices.push("Binary file — contents not shown");
  }
  if (fileDiff.prevMode !== undefined) {
    notices.push(`Mode changed to ${fileDiff.mode ?? "?"}`);
  }
  return notices;
}

/**
 * A patch section with no hunks, no line content, and no rename/mode
 * markers is git's binary-file arm — the parser keeps the file (name and
 * index line) but has no textual content to show.
 */
export function isBinaryFileDiff(fileDiff: FileDiffMetadata): boolean {
  return (
    fileDiff.type === "change" &&
    fileDiff.hunks.length === 0 &&
    fileDiff.additionLines.length === 0 &&
    fileDiff.deletionLines.length === 0 &&
    fileDiff.prevMode === undefined
  );
}

/** Reset the adapter's module state (memo caches + version ledger). */
export function __resetChangesDiffForTests(): void {
  parseCache.clear();
  itemVersions = new WeakMap();
  nextVersion = 1;
}
