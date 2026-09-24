/**
 * The transcript tool-diff adapter (web-pierre-adoption, ticket 04) — the
 * thin seam between an inline `ToolDiff` (a path plus the FULL old/new
 * contents) and the Pierre diffs library's `FileDiffMetadata`, which the
 * transcript's stacked diff blocks render through the library's React
 * `FileDiff` (always expanded, unified, no comment affordances).
 *
 * This replaces the hand-rolled Myers walk + hunk grouping + row model that
 * used to live in `lib/transcript.ts` (`diff_to_file`) and
 * `lib/diff.ts`/`components/diff-view.tsx`: the library diffs the two
 * contents directly (`parseDiffFromFile`, jsdiff under the hood — the peer
 * of the desktop's `similar::TextDiff::from_lines`), grouped with the same
 * 3 context lines via the patch `context` option.
 *
 * Three pure pieces:
 *
 * - `toolDiffFileDiff` — `ToolDiff` → (metadata, notices): `oldText: null`
 *   means a new file (the library's `type: "new"`), identical contents parse
 *   to zero hunks (no detail, exactly the old `diffToFile` semantics), and
 *   the whole-file guard is a LINE-ROW CAP (`truncateFileDiffLines` — the
 *   `DIFF_DETAIL_MAX_LINES` discipline: the detail is one stacked element
 *   inside its transcript row) with the old truncation notice carried in
 *   `notices` for the header slot.
 * - `truncateFileDiffLines` — caps the rendered line rows, cutting the
 *   crossing hunk mid-block and slicing the line arrays so every kept index
 *   stays valid and no phantom trailing context appears after the cut.
 * - `toolDiffDetailHeight` — the analytic height estimate for the
 *   transcript's row estimator (ticket 70's shared geometry contract),
 *   mirroring the library's own height accounting for our fixed option set
 *   (default file header, `line-info` hunk separators, collapsed
 *   inter-hunk gaps with the library's 1-line threshold).
 *
 * Nothing here touches React or the DOM; the pure suite covers it directly.
 */

import { DEFAULT_VIRTUAL_FILE_METRICS, parseDiffFromFile, type FileDiffMetadata, type Hunk } from "@pierre/diffs";
import type { ToolDiff } from "@roboco/proto";

/** Context lines around each change — `similar::TextDiff::grouped_ops(3)`. */
export const TOOL_DIFF_CONTEXT = 3;

/** The library's `line-info` hunk separator row height (its own default table). */
const LINE_INFO_SEPARATOR_HEIGHT = 32;

/** One parsed tool diff: the library metadata + the header-slot notices. */
export interface ToolDiffParsed {
  readonly file: FileDiffMetadata;
  /**
   * The display notices (the old `fileNotices` arm): status word for a new
   * file, plus the truncation notice when the cap dropped lines. Binary and
   * rename notices cannot arise from a `ToolDiff` (no binary flag, no
   * pre-rename path).
   */
  readonly notices: readonly string[];
}

// ---------------------------------------------------------------------------
// ToolDiff → FileDiffMetadata
// ---------------------------------------------------------------------------

/**
 * `diff_to_file` (transcript.rs:890), on the library's parser: one inline
 * `ToolDiff` to a `FileDiffMetadata` diffed from the full contents with 3
 * context lines. `oldText: null` (or absent) means a new file; `null` is
 * returned when the two sides parse to no hunks (identical contents — the
 * old `hunks.length === 0` no-detail case) or the contents are malformed.
 */
export function toolDiffFileDiff(diff: ToolDiff, maxLines: number): ToolDiffParsed | null {
  const oldText = diff.oldText ?? null;
  const oldFile = oldText === null ? null : { name: diff.path, contents: oldText };
  const newFile = { name: diff.path, contents: diff.newText };
  let parsed: FileDiffMetadata;
  try {
    parsed = parseDiffFromFile(oldFile, newFile, { context: TOOL_DIFF_CONTEXT });
  } catch {
    // The library throws on inputs it cannot shape into a file section;
    // the old tolerant path rendered no detail for those too.
    return null;
  }
  if (parsed.hunks.length === 0) {
    return null;
  }
  const { file, shownLines, totalLines } = truncateFileDiffLines(parsed, maxLines);
  const notices: string[] = [];
  if (file.type === "new") {
    notices.push("New file");
  } else if (file.type === "deleted") {
    notices.push("Deleted file");
  }
  if (shownLines < totalLines) {
    notices.push(`Diff truncated — showing first ${shownLines} of ${totalLines} lines`);
  }
  // The parse is fresh per call; stamping the content-hashed cache key (the
  // cap folded in — a truncated diff must not reuse the full one's
  // highlights) lets the library's worker/highlight caches survive remounts.
  file.cacheKey = toolDiffCacheKey(diff, maxLines);
  return { file, notices };
}

/** FNV-1a over the parse's identity inputs (path, both sides, cap). */
function toolDiffCacheKey(diff: ToolDiff, maxLines: number): string {
  let hash = 0xcbf29ce484222325n;
  const prime = 0x1000001b3n;
  const text = `${diff.path}\u0000${diff.oldText ?? ""}\u0001${diff.newText}\u0002${maxLines}`;
  for (let i = 0; i < text.length; i++) {
    const unit = text.charCodeAt(i);
    hash ^= BigInt(unit & 0xff);
    hash = BigInt.asUintN(64, hash * prime);
    hash ^= BigInt(unit >> 8);
    hash = BigInt.asUintN(64, hash * prime);
  }
  return `tool-diff:${BigInt.asUintN(64, hash).toString(36)}`;
}

// ---------------------------------------------------------------------------
// The line-row cap
// ---------------------------------------------------------------------------

/**
 * Cap a parsed tool diff at `maxLines` RENDERED LINE ROWS (context, +, −,
 * and no-newline marker rows — the hunks' line content, not the header or
 * separator chrome), preserving the old `truncate_file_lines` contract:
 * a stacked transcript row must stay bounded even for a whole-file
 * rewrite. Hunks are kept whole while the budget allows; the crossing
 * hunk is cut MID-BLOCK (a whole-file rewrite is one giant block), and
 * the line arrays are sliced to the cut so the kept blocks' absolute
 * indexes stay valid and no phantom trailing context follows the cut.
 *
 * `shownLines`/`totalLines` count the line rows before/after the cap —
 * equal when nothing was dropped.
 */
export function truncateFileDiffLines(
  file: FileDiffMetadata,
  maxLines: number,
): { file: FileDiffMetadata; shownLines: number; totalLines: number } {
  const totalLines = lineRows(file);
  if (totalLines <= maxLines) {
    return { file, shownLines: totalLines, totalLines };
  }
  let budget = maxLines;
  const hunks: Hunk[] = [];
  for (const hunk of file.hunks) {
    if (budget === 0) {
      break;
    }
    const leading = leadingRows(hunk);
    const rows = leading + hunk.unifiedLineCount + noNewlineRows(hunk);
    if (rows <= budget) {
      hunks.push(hunk);
      budget -= rows;
      continue;
    }
    if (budget <= leading) {
      // Nothing of this hunk's content fits — the cut ends before it.
      break;
    }
    hunks.push(cutHunk(hunk, budget - leading));
    budget = 0;
  }
  // File-level counters: no hunks follow the cut, so no trailing context.
  let unifiedLineCount = 0;
  let splitLineCount = 0;
  for (const hunk of hunks) {
    unifiedLineCount += hunk.collapsedBefore + hunk.unifiedLineCount;
    splitLineCount += hunk.collapsedBefore + hunk.splitLineCount;
  }
  const last = hunks[hunks.length - 1]!;
  const additionsEnd = sideEnd(last.additionStart, last.additionCount);
  const deletionsEnd = sideEnd(last.deletionStart, last.deletionCount);
  const capped: FileDiffMetadata = {
    ...file,
    hunks,
    unifiedLineCount,
    splitLineCount,
    // The full-content arrays sliced to the cut: every kept block's
    // absolute line index stays in range and the trailing region the
    // renderer would otherwise expand past the cut disappears.
    additionLines: file.additionLines.slice(0, Math.max(additionsEnd, 0)),
    deletionLines: file.deletionLines.slice(0, Math.max(deletionsEnd, 0)),
  };
  return { file: capped, shownLines: lineRows(capped), totalLines };
}

/** Cut one hunk to `budget` content line rows, mid-block if needed. */
function cutHunk(hunk: Hunk, budget: number): Hunk {
  let remaining = budget;
  let contextLines = 0;
  let additions = 0;
  let deletions = 0;
  const content: Hunk["hunkContent"] = [];
  for (const block of hunk.hunkContent) {
    if (remaining === 0) {
      break;
    }
    if (block.type === "context") {
      const take = Math.min(block.lines, remaining);
      remaining -= take;
      contextLines += take;
      content.push(take === block.lines ? block : { ...block, lines: take });
      continue;
    }
    // Unified renders a change block's deletions then its additions; keep
    // that order so the cut reads top-down.
    const delTake = Math.min(block.deletions, remaining);
    remaining -= delTake;
    const addTake = Math.min(block.additions, remaining);
    remaining -= addTake;
    deletions += delTake;
    additions += addTake;
    if (delTake === block.deletions && addTake === block.additions) {
      content.push(block);
    } else {
      content.push({ ...block, deletions: delTake, additions: addTake });
    }
  }
  const unifiedLineCount = contextLines + deletions + additions;
  const splitLineCount = contextLines + Math.max(additions, deletions);
  return {
    ...hunk,
    hunkContent: content,
    unifiedLineCount,
    splitLineCount,
    // Side counts include the context lines (the parser's own semantics).
    additionCount: contextLines + additions,
    deletionCount: contextLines + deletions,
    additionLines: additions,
    deletionLines: deletions,
    // The cut is mid-file by construction — no EOF markers past it.
    noEOFCRAdditions: false,
    noEOFCRDeletions: false,
    hunkSpecs: `@@ -${hunk.deletionStart},${contextLines + deletions} +${hunk.additionStart},${contextLines + additions} @@`,
  };
}

/** The rendered LINE rows of a whole parsed file (cap + notice inputs). */
function lineRows(file: FileDiffMetadata): number {
  let rows = 0;
  for (const hunk of file.hunks) {
    rows += leadingRows(hunk) + hunk.unifiedLineCount + noNewlineRows(hunk);
  }
  const trailing = trailingContextRows(file);
  return rows + (trailing <= 1 ? trailing : 0);
}

// ---------------------------------------------------------------------------
// The analytic height estimate
// ---------------------------------------------------------------------------

/**
 * The rendered height of one tool-diff block (unified, default header) —
 * the transcript estimator's input. Mirrors the library's own height
 * accounting for the option set `ToolDiffBody` renders with: the default
 * file header, `line-info` hunk separators, collapsed inter-hunk/trailing
 * context (the library's 1-line threshold), and the bottom content pad.
 * Pre-measurement only — the transcript scroller measures the mounted row.
 */
export function toolDiffDetailHeight(file: FileDiffMetadata, lineHeight: number): number {
  if (file.hunks.length === 0) {
    return 0;
  }
  const spacing = DEFAULT_VIRTUAL_FILE_METRICS.spacing;
  let total = DEFAULT_VIRTUAL_FILE_METRICS.diffHeaderHeight;
  for (let ix = 0; ix < file.hunks.length; ix += 1) {
    const hunk = file.hunks[ix]!;
    const before = Math.max(hunk.collapsedBefore, 0);
    if (before <= 1) {
      total += before * lineHeight;
    } else {
      // A collapsed gap renders one separator row: gap-before (0 before the
      // first hunk) + the row itself + gap-after.
      total += (ix > 0 ? spacing : 0) + LINE_INFO_SEPARATOR_HEIGHT + spacing;
    }
    total += hunk.unifiedLineCount * lineHeight;
    total += noNewlineRows(hunk) * lineHeight;
  }
  const trailing = trailingContextRows(file);
  if (trailing <= 1) {
    total += trailing * lineHeight;
  } else {
    // The trailing collapsed region's separator: gap-before + the row.
    total += spacing + LINE_INFO_SEPARATOR_HEIGHT;
  }
  total += spacing;
  return total;
}

// ---------------------------------------------------------------------------
// Shared hunk arithmetic (the library's own boundary semantics)
// ---------------------------------------------------------------------------

/** A hunk side's exclusive 0-based end boundary (start is 1-based). */
function sideEnd(start: number, count: number): number {
  return start - (count === 0 ? 0 : 1) + count;
}

/**
 * Gap rows a hunk renders before its content: a gap of at most the
 * library's 1-line threshold renders as its own line rows; anything larger
 * collapses into one separator (no line rows).
 */
function leadingRows(hunk: Hunk): number {
  const before = Math.max(hunk.collapsedBefore, 0);
  return before <= 1 ? before : 0;
}

/**
 * Marker rows a hunk renders for missing end-of-file newlines (the
 * library's own metadata-row accounting).
 */
function noNewlineRows(hunk: Hunk): number {
  if (!hunk.noEOFCRAdditions && !hunk.noEOFCRDeletions) {
    return 0;
  }
  const last = hunk.hunkContent[hunk.hunkContent.length - 1];
  if (last === undefined) {
    return 0;
  }
  if (last.type === "context") {
    return last.lines > 0 ? 1 : 0;
  }
  return (last.deletions > 0 && hunk.noEOFCRDeletions ? 1 : 0) + (last.additions > 0 && hunk.noEOFCRAdditions ? 1 : 0);
}

/**
 * Unchanged lines after the last hunk's context — both sides carry the
 * same tail for a valid diff, so the additions side decides (the library
 * guards the same way: no trailing region while either side's contents
 * are empty or fully consumed).
 */
function trailingContextRows(file: FileDiffMetadata): number {
  const last = file.hunks[file.hunks.length - 1];
  if (last === undefined || file.isPartial) {
    return 0;
  }
  if (file.additionLines.length === 0 || file.deletionLines.length === 0) {
    return 0;
  }
  const additionsRemaining = file.additionLines.length - sideEnd(last.additionStart, last.additionCount);
  const deletionsRemaining = file.deletionLines.length - sideEnd(last.deletionStart, last.deletionCount);
  if (additionsRemaining <= 0 || deletionsRemaining <= 0) {
    return 0;
  }
  return Math.min(additionsRemaining, deletionsRemaining);
}
