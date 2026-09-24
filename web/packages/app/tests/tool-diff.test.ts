import { describe, expect, it } from "vitest";
import type { FileDiffMetadata } from "@pierre/diffs";
import {
  TOOL_DIFF_CONTEXT,
  toolDiffDetailHeight,
  toolDiffFileDiff,
  truncateFileDiffLines,
} from "../src/lib/tool-diff";

/**
 * The transcript tool-diff adapter (web-pierre-adoption, ticket 04): an
 * inline `ToolDiff` (full old/new contents) through the library's parser,
 * the `DIFF_DETAIL_MAX_LINES` line-row cap, the header-slot notices, and
 * the analytic height estimate the transcript's row estimator consumes.
 * The library's own render internals stay untested here — this covers our
 * seam only.
 */

const MAX = 600;

function parse(oldText: string | null, newText: string, path = "/w/a.rs") {
  return toolDiffFileDiff({ path, oldText, newText }, MAX);
}

describe("toolDiffFileDiff", () => {
  it("diffs the full contents with 3-line context hunks", () => {
    const oldLines = Array.from({ length: 20 }, (_, i) => `line ${i + 1}`);
    const newLines = [...oldLines];
    newLines[9] = "LINE 10";
    const parsed = parse(oldLines.join("\n") + "\n", newLines.join("\n") + "\n");
    expect(parsed).not.toBeNull();
    const file = parsed!.file;
    expect(file.hunks).toHaveLength(1);
    const hunk = file.hunks[0]!;
    // The change plus 3 context lines each side — the old Myers walk's
    // grouping, now the library's patch `context` option.
    expect(hunk.unifiedLineCount).toBe(8);
    expect(hunk.additionStart).toBe(7);
    expect(hunk.deletionStart).toBe(7);
    const change = hunk.hunkContent.find((block) => block.type === "change");
    expect(change).toMatchObject({ additions: 1, deletions: 1 });
    // Full-contents parse: the line arrays are the whole files, so the
    // hunk's inter-hunk gaps stay expandable in the rendered block.
    expect(file.isPartial).toBe(false);
    expect(file.additionLines).toHaveLength(20);
    expect(file.deletionLines).toHaveLength(20);
    expect(file.name).toBe("/w/a.rs");
    // No status notice for a modification; no truncation under the cap.
    expect(parsed!.notices).toEqual([]);
    // The cache key is a stable content identity for highlight reuse.
    expect(typeof file.cacheKey).toBe("string");
    expect(toolDiffFileDiff({ path: "/w/a.rs", oldText: oldLines.join("\n") + "\n", newText: newLines.join("\n") + "\n" }, MAX)!.file.cacheKey).toBe(file.cacheKey);
    expect(parse("a\n", "b\n")!.file.cacheKey).not.toBe(parse("a\nc\n", "b\n")!.file.cacheKey);
  });

  it("maps a null oldText to a new file with the notice", () => {
    const parsed = parse(null, "only\n", "/w/new.txt");
    expect(parsed).not.toBeNull();
    expect(parsed!.file.type).toBe("new");
    expect(parsed!.file.hunks).toHaveLength(1);
    expect(parsed!.notices).toEqual(["New file"]);
    // An empty-string old side is the same new-file condition (the
    // library classifies it from the contents).
    expect(parse("", "a\nb\n")!.file.type).toBe("new");
  });

  it("returns null for identical sides (no hunks)", () => {
    expect(parse("a\nb\n", "a\nb\n")).toBeNull();
    expect(parse(null, "")).toBeNull();
  });

  it("marks the missing end-of-file newline like the library", () => {
    const parsed = parse(null, "only", "/w/nn.txt");
    expect(parsed).not.toBeNull();
    expect(parsed!.file.hunks[0]!.noEOFCRAdditions).toBe(true);
    expect(parsed!.notices).toEqual(["New file"]);
  });

  it("diffs from the full contents, never a truncation of them", () => {
    // A change deep in the file keeps the leading context the 3-line
    // grouping gives — the parser saw the whole file, not a clipped one.
    const oldText = Array.from({ length: 100 }, (_, i) => `l${i}`).join("\n") + "\n";
    const parsed = parse(oldText, oldText.replace("l90\n", "X\n"));
    expect(parsed!.file.hunks).toHaveLength(1);
    expect(parsed!.file.hunks[0]!.additionStart).toBe(88);
  });
});

describe("the line-row cap", () => {
  it("caps a whole-file rewrite mid-hunk with the old notice copy", () => {
    // 400 old lines fully rewritten: 800 line rows, one giant hunk — the
    // pathological case the cap exists for (a stacked transcript row must
    // stay bounded).
    const oldText = Array.from({ length: 400 }, (_, i) => `o${i}`).join("\n") + "\n";
    const newText = Array.from({ length: 400 }, (_, i) => `n${i}`).join("\n") + "\n";
    const parsed = parse(oldText, newText);
    const file = parsed!.file;
    expect(file.hunks).toHaveLength(1);
    const hunk = file.hunks[0]!;
    expect(hunk.unifiedLineCount).toBe(MAX);
    // The cut reads top-down: the deletions first, then what fits of the
    // additions.
    expect(hunk.deletionCount).toBe(400);
    expect(hunk.additionCount).toBe(200);
    expect(hunk.hunkContent).toHaveLength(1);
    // The line arrays are sliced to the cut so every kept index stays in
    // range and no phantom trailing context follows it.
    expect(file.additionLines).toHaveLength(200);
    expect(file.deletionLines).toHaveLength(400);
    expect(parsed!.notices).toEqual([`Diff truncated — showing first ${MAX} of 800 lines`]);
    // A cut is mid-file by construction — no EOF markers survive it.
    expect(hunk.noEOFCRAdditions).toBe(false);
    expect(hunk.noEOFCRDeletions).toBe(false);
  });

  it("caps across hunks, keeping whole hunks while the budget allows", () => {
    // A change every 40 lines over 4800 lines: the first hunk rides the
    // file top (5 rows), the other 119 carry 3+3 context + the edit (8
    // rows) — 957 line rows total. The cap keeps the first 75 hunks whole
    // (5 + 74×8 = 597 rows) and cuts the 76th to the 3 remaining rows.
    const oldText = Array.from({ length: 4800 }, (_, i) => `o${i}`).join("\n") + "\n";
    const newText = oldText
      .split("\n")
      .map((line, ix) => (ix % 40 === 0 && line !== "" ? `X${line}` : line))
      .join("\n");
    const parsed = parse(oldText, newText);
    const file = parsed!.file;
    expect(file.hunks).toHaveLength(76);
    // Whole hunks while the budget allows: hunk 74 is the last untouched 8-rower.
    expect(file.hunks[74]!.unifiedLineCount).toBe(8);
    // The crossing hunk is cut mid-content to exactly the remaining budget.
    expect(file.hunks[75]!.unifiedLineCount).toBe(3);
    expect(file.hunks[75]!.hunkContent.some((block) => block.type === "change")).toBe(false);
    // The kept rows sum to the cap, and the notice reports both sides.
    let kept = 0;
    for (const hunk of file.hunks) {
      const before = Math.max(hunk.collapsedBefore, 0);
      kept += (before <= 1 ? before : 0) + hunk.unifiedLineCount;
    }
    expect(kept).toBe(MAX);
    expect(parsed!.notices).toEqual([`Diff truncated — showing first ${MAX} of 957 lines`]);
  });

  it("leaves under-cap files untouched (same object, no notice)", () => {
    const file = parse("a\nb\nc\n", "a\nX\nc\n")!.file;
    const { file: kept, shownLines, totalLines } = truncateFileDiffLines(file, MAX);
    expect(kept).toBe(file);
    expect(shownLines).toBe(totalLines);
    expect(shownLines).toBeLessThanOrEqual(MAX);
  });

  it("folds the cap into the cache key", () => {
    const oldText = Array.from({ length: 400 }, (_, i) => `o${i}`).join("\n") + "\n";
    const newText = Array.from({ length: 400 }, (_, i) => `n${i}`).join("\n") + "\n";
    const capped = toolDiffFileDiff({ path: "/w/b.rs", oldText, newText }, MAX)!.file;
    const uncapped = toolDiffFileDiff({ path: "/w/b.rs", oldText, newText }, 2000)!.file;
    expect(capped.cacheKey).not.toBe(uncapped.cacheKey);
  });
});

describe("toolDiffDetailHeight", () => {
  const L = 21;

  it("header + line rows + bottom pad for a new file", () => {
    const file = parse(null, "one\ntwo\n")!.file;
    // 44 (the library's header estimate) + 2 rows + 8 (bottom pad).
    expect(toolDiffDetailHeight(file, L)).toBe(44 + 2 * L + 8);
  });

  it("counts collapsed gaps as separators, not line rows", () => {
    const oldText = Array.from({ length: 40 }, (_, i) => `l${i + 1}`).join("\n") + "\n";
    const newText = oldText.replace("l10\n", "TEN\n").replace("l30\n", "THIRTY\n");
    const file = parse(oldText, newText)!.file;
    expect(file.hunks).toHaveLength(2);
    // hunk 0: a 6-line leading gap (separator 0+32+8), 8 rows;
    // hunk 1: a 13-line gap (separator 8+32+8), 8 rows;
    // trailing: 7 lines (separator 8+32); then the 8px bottom pad.
    expect(toolDiffDetailHeight(file, L)).toBe(
      44 + (32 + 8) + 8 * L + (8 + 32 + 8) + 8 * L + (8 + 32) + 8,
    );
  });

  it("counts the no-newline marker row", () => {
    const file = parse(null, "only")!.file;
    expect(toolDiffDetailHeight(file, L)).toBe(44 + L + L + 8);
  });

  it("is empty for a hunkless file and scales with the row height", () => {
    const empty: FileDiffMetadata = {
      name: "x",
      type: "change",
      hunks: [],
      splitLineCount: 0,
      unifiedLineCount: 0,
      isPartial: false,
      additionLines: [],
      deletionLines: [],
    };
    expect(toolDiffDetailHeight(empty, L)).toBe(0);
    const file = parse(null, "one\ntwo\n")!.file;
    expect(toolDiffDetailHeight(file, 25)).toBe(44 + 2 * 25 + 8);
  });
});

describe("TOOL_DIFF_CONTEXT", () => {
  it("matches the desktop's grouped_ops(3)", () => {
    expect(TOOL_DIFF_CONTEXT).toBe(3);
  });
});
