/**
 * The Changes pane's Pierre-diffs adapter (web-pierre-adoption, tickets 02
 * + 03) — the thin seam between the resolved `CheckoutDiff` patch string and
 * the library's parsed diff metadata + controlled items, and between the
 * review-comment store's anchors and the library's diff line annotations.
 * The data layer (the watch, scoped captures, retry — `state/changes-store.ts`)
 * and the comment store (`state/review-comments.ts`) are untouched; this
 * module only shapes what the renderer consumes.
 *
 * Pure functions plus their shared state:
 *
 * - `parseDiffFiles` — patch string → `FileDiffMetadata[]`, memoized per
 *   (checkout, checksum, scope, base) through the same `parseKey`
 *   fingerprint the old hand-rolled parse cache used, with the same
 *   ~8-entry LRU discipline. The cache key also seeds the library's
 *   per-file highlight `cacheKey`s (collision-safe: parse index + file
 *   index), so re-renders and re-mounted surfaces never re-tokenize.
 * - `diffCodeItems` — the parsed metadata + the surface store's fold map (+
 *   the per-file annotation arrays) → the library's controlled
 *   `CodeViewItem[]`. Each item's `version` is the controlled-update
 *   channel: the library only adopts a changed `fileDiff`/`collapsed`/
 *   `annotations` when the version moves, so the version tracks the parse
 *   identity, the file's fold epoch, and the file's annotation identity.
 * - `diffCommentAnnotations` — the visible staged diff comments + the open
 *   draft → per-file annotation arrays keyed by the file's (post-change)
 *   path, in staged order. The library's `DiffLineAnnotation` lines are the
 *   line number ON THE SIDE (`deletions` = old numbering, `additions` = new)
 *   — matching the comment store's `(path, side, line)` anchors directly.
 *   The metadata objects are STABLE per comment identity (a `WeakMap`), per
 *   the library's annotation contract (`areDiffLineAnnotationsEqual`
 *   compares metadata by identity): unrelated re-renders reuse the same
 *   metadata, so anchors are not re-synced per change event.
 * - `diffAdderAnchor` — a gutter-utility click (the library's `+` affordance)
 *   + the clicked file's metadata → the draft anchor the comment store
 *   opens, with the pre-rename path resolution preserved.
 * - `fileDiffNotices` — the per-file notice copy (new/deleted/binary/mode)
 *   for the library header's metadata slot, the old `fileNotices()` arm the
 *   hand-rolled renderer carried as notice rows.
 *
 * Nothing here touches React or the DOM; the pure suite covers it directly.
 */

import {
  parsePatchFiles,
  type CodeViewItem,
  type DiffLineAnnotation,
  type FileDiffMetadata,
  type SelectedLineRange,
} from "@pierre/diffs";
import { parseKey, type DiffScope, type FileFold } from "./diff";
import { type CommentSide, type ReviewComment } from "./review-comments";

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
 * `fileDiff` identity, fold state, or annotation identity changes, which is
 * exactly when the library must adopt the new item. Keyed weakly by the
 * metadata object, so a fresh parse (new objects) re-versions every file
 * while a pure re-render (same objects, same folds, same annotations)
 * keeps the versions — and the library's layout and element pools —
 * untouched.
 */
let itemVersions = new WeakMap<
  FileDiffMetadata,
  { version: number; collapsed: boolean; annotations: DiffCommentAnnotation[] | undefined }
>();
let nextVersion = 1;

const EMPTY_ANNOTATIONS: ReadonlyMap<string, DiffCommentAnnotation[]> = new Map();

/**
 * The parsed files + the surface store's fold map + the per-file annotation
 * arrays → the library's controlled items. The item `id` is the file's
 * (post-change) path — stable across scope switches for the same path, so
 * the library reuses its instances; the folds stay keyed by that same path
 * (`changesSurfaceStore.toggleFold`).
 */
export function diffCodeItems(
  files: readonly FileDiffMetadata[],
  folds: ReadonlyMap<string, FileFold>,
  annotationsByFile: ReadonlyMap<string, DiffCommentAnnotation[]> = EMPTY_ANNOTATIONS,
): CodeViewItem<DiffCommentAnnotationData>[] {
  const items: CodeViewItem<DiffCommentAnnotationData>[] = [];
  for (const fileDiff of files) {
    const collapsed = folds.get(fileDiff.name)?.collapsed ?? false;
    const fileAnnotations = annotationsByFile.get(fileDiff.name);
    const annotations = fileAnnotations !== undefined && fileAnnotations.length > 0 ? fileAnnotations : undefined;
    let record = itemVersions.get(fileDiff);
    if (record === undefined || record.collapsed !== collapsed || record.annotations !== annotations) {
      record = { version: nextVersion, collapsed, annotations };
      nextVersion += 1;
      itemVersions.set(fileDiff, record);
    }
    items.push({
      id: fileDiff.name,
      type: "diff",
      fileDiff,
      annotations,
      collapsed,
      version: record.version,
    });
  }
  return items;
}

// ---------------------------------------------------------------------------
// Review comments → diff line annotations
// ---------------------------------------------------------------------------

/** Annotation metadata for one staged diff comment — `{kind, id}`, stable per comment object. */
export interface DiffCommentAnnotationMeta {
  readonly kind: "comment";
  /** The staged comment's id — the renderer resolves the live comment by it. */
  readonly id: string;
}

/** Annotation metadata for the open draft — one singleton identity. */
export interface DiffDraftAnnotationMeta {
  readonly kind: "draft";
}

/** The annotation payloads the Changes pane mounts (`CodeViewItem`'s `LAnnotation`). */
export type DiffCommentAnnotationData = DiffCommentAnnotationMeta | DiffDraftAnnotationMeta;

/** A diff line annotation carrying one of the review-comment payloads. */
export type DiffCommentAnnotation = DiffLineAnnotation<DiffCommentAnnotationData>;

/** The draft anchor subset the adapter maps (the store's `DiffCommentDraft` satisfies this). */
export interface DiffDraftAnchorInput {
  readonly path: string;
  readonly side: CommentSide;
  readonly line: number;
  readonly editingId: string | null;
}

/** The draft's metadata — a singleton, so its annotation identity follows only its anchor. */
const DRAFT_ANNOTATION_META: DiffDraftAnnotationMeta = { kind: "draft" };

/** Comment → metadata, stable per comment object (the library's identity contract). */
const commentMetaCache = new WeakMap<ReviewComment, DiffCommentAnnotationMeta>();

function commentAnnotationMeta(comment: ReviewComment): DiffCommentAnnotationMeta {
  let meta = commentMetaCache.get(comment);
  if (meta === undefined) {
    meta = { kind: "comment", id: comment.id };
    commentMetaCache.set(comment, meta);
  }
  return meta;
}

/** The annotation side for a comment side — `old` cites the deletions (pre-change) numbering. */
function annotationSide(side: CommentSide): "deletions" | "additions" {
  return side === "old" ? "deletions" : "additions";
}

/**
 * The visible staged diff comments + the open draft → per-file annotation
 * arrays, keyed by the file's (post-change) path — the path the comment
 * store anchors by. Comments whose file is not in the parsed diff have no
 * anchor to render at (the old renderer interleaved per parsed file the
 * same way) and are skipped, as are file-sourced comments (those render in
 * the file viewer). Multiple comments on one anchor keep their staged
 * order; the draft renders after any same-anchor comments.
 *
 * Derive this memoized (same inputs → same arrays): the annotation arrays'
 * identity feeds the item version ledger, and the metadata objects' stable
 * identity keeps the library's annotation comparison quiet across
 * unrelated re-renders.
 */
export function diffCommentAnnotations(
  files: readonly FileDiffMetadata[],
  comments: readonly ReviewComment[],
  draft: DiffDraftAnchorInput | null,
): ReadonlyMap<string, DiffCommentAnnotation[]> {
  const byFile = new Map<string, DiffCommentAnnotation[]>();
  for (const fileDiff of files) {
    byFile.set(fileDiff.name, []);
  }
  for (const comment of comments) {
    if (comment.source.kind !== "diff") {
      continue;
    }
    const annotations = byFile.get(comment.path);
    if (annotations === undefined) {
      continue;
    }
    annotations.push({
      side: annotationSide(comment.source.side),
      lineNumber: comment.line,
      metadata: commentAnnotationMeta(comment),
    });
  }
  if (draft !== null) {
    const annotations = byFile.get(draft.path);
    if (annotations !== undefined) {
      annotations.push({
        side: annotationSide(draft.side),
        lineNumber: draft.line,
        metadata: DRAFT_ANNOTATION_META,
      });
    }
  }
  return byFile;
}

// ---------------------------------------------------------------------------
// The adder's click → the draft anchor
// ---------------------------------------------------------------------------

/** The draft anchor a gutter-utility click opens (the store's `openDiffDraft` input). */
export interface DiffAdderAnchor {
  /** The current workspace path — the comment store anchors by it. */
  readonly path: string;
  readonly side: CommentSide;
  readonly line: number;
  /** The pre-rename path an Old-side comment cites (rename's `from` path). */
  readonly oldPath: string | null;
}

/**
 * Resolve a gutter-utility click into the draft anchor — the old
 * `renderAdder` callback's resolution, now fed by the library's click
 * range. The library's `side` follows the hovered gutter line directly:
 * `deletions` = the pre-change numbering (del lines; the deletions column
 * in split), `additions` = the post-change numbering — matching the old
 * `diffLineAnchor` (del → old; else new).
 *
 * Split layout keeps the old right-half-only rule: the adder opens drafts
 * on the additions side only (a deletion-side STAGED comment still renders
 * its card through its own annotation). A drag that crossed lines anchors
 * at the range's start — the line whose `+` was pressed. `null` when the
 * click opens no draft (a sideless range cannot happen on a diff item, but
 * the anchor resolution stays total).
 */
export function diffAdderAnchor(
  fileDiff: FileDiffMetadata,
  layout: "unified" | "split",
  range: SelectedLineRange,
): DiffAdderAnchor | null {
  const side = range.side ?? range.endSide;
  if (side == null) {
    return null;
  }
  if (layout === "split" && side === "deletions") {
    return null;
  }
  const commentSide: CommentSide = side === "deletions" ? "old" : "new";
  return {
    path: fileDiff.name,
    side: commentSide,
    line: range.start,
    oldPath: commentSide === "old" ? fileDiff.prevName ?? null : null,
  };
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
