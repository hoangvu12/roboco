import { describe, expect, it } from "vitest";
import {
  cleanMessage,
  defaultBaseRef,
  diffPhase,
  fileCounts,
  flattenFileRows,
  gutterWidth,
  parseKey,
  parsePatch,
  resolveDiff,
  scopeLabel,
  scopeMode,
  splitPairs,
  splitPairsUpto,
} from "../src/lib/diff";

describe("parsePatch", () => {
  it("returns one modified file for a single-hunk patch", () => {
    const files = parsePatch(
      ["diff --git a/src/lib.rs b/src/lib.rs",
       "index 0123..4567 100644",
       "--- a/src/lib.rs",
       "+++ b/src/lib.rs",
       "@@ -1,3 +1,3 @@",
       " line one",
       "-line two",
       "+line TWO",
       " line three"].join("\n"),
    );
    expect(files).toHaveLength(1);
    const [file] = files;
    expect(file?.path).toBe("src/lib.rs");
    expect(file?.status).toBe("modified");
    expect(file?.additions).toBe(1);
    expect(file?.deletions).toBe(1);
    expect(file?.hunks[0]?.lines.map((line) => line.kind)).toEqual(["context", "del", "add", "context"]);
    expect(file?.hunks[0]?.lines[1]).toMatchObject({ oldNo: 2, newNo: null });
    expect(file?.hunks[0]?.lines[2]).toMatchObject({ oldNo: null, newNo: 2 });
  });

  it("marks renames and preserves the old path", () => {
    const files = parsePatch(
      ["diff --git a/old/name.ts b/new/name.ts",
       "similarity index 99%",
       "rename from old/name.ts",
       "rename to new/name.ts",
       "--- a/old/name.ts",
       "+++ b/new/name.ts",
       "@@ -0,0 +1,1 @@",
       "+export const x = 1;"].join("\n"),
    );
    expect(files[0]?.status).toBe("renamed");
    expect(files[0]?.oldPath).toBe("old/name.ts");
    expect(files[0]?.path).toBe("new/name.ts");
  });

  it("flags binary files and keeps additions zero", () => {
    const files = parsePatch(
      ["diff --git a/image.png b/image.png",
       "index 1..2",
       "Binary files /dev/null and b/image.png differ"].join("\n"),
    );
    expect(files[0]?.binary).toBe(true);
    expect(files[0]?.additions).toBe(0);
    expect(files[0]?.deletions).toBe(0);
    expect(files[0]?.notices.some((message) => message.includes("Binary"))).toBe(true);
  });

  it("parses unquoted paths with spaces via the last ` b/` separator", () => {
    const files = parsePatch(
      ["diff --git a/dir with spaces/a.rs b/dir with spaces/b.rs",
       "--- a/dir with spaces/a.rs",
       "+++ b/dir with spaces/b.rs"].join("\n"),
    );
    expect(files[0]?.path).toBe("dir with spaces/b.rs");
    expect(files[0]?.oldPath).toBe("dir with spaces/a.rs");
  });

  it("parses `\\ No newline at end of file` markers as meta lines", () => {
    const files = parsePatch(
      ["diff --git a/nl.txt b/nl.txt",
       "--- a/nl.txt",
       "+++ b/nl.txt",
       "@@ -1 +1 @@",
       "-tail",
       "\\ No newline at end of file",
       "+tail"].join("\n"),
    );
    expect(files[0]?.hunks[0]?.lines.map((line) => line.kind)).toEqual(["del", "meta", "add"]);
  });

  it("updates the hunk's start line numbers across hunks", () => {
    const files = parsePatch(
      ["diff --git a/multi.txt b/multi.txt",
       "--- a/multi.txt",
       "+++ b/multi.txt",
       "@@ -1,2 +1,2 @@",
       " one",
       "-two",
       "+TWO",
       "@@ -10,2 +10,2 @@",
       "-ten",
       "+TEN",
       " eleven"].join("\n"),
    );
    const lines = files[0]?.hunks[1]?.lines ?? [];
    expect(lines[0]?.oldNo).toBe(10);
    expect(lines[0]?.newNo).toBeNull();
    expect(lines[1]?.oldNo).toBeNull();
    expect(lines[1]?.newNo).toBe(10);
    expect(lines[2]?.oldNo).toBe(11);
    expect(lines[2]?.newNo).toBe(11);
  });
});

describe("resolveDiff", () => {
  type Diff = { readonly checkoutId: string; readonly deviceId: string; readonly cwd: string };
  const diffs: readonly Diff[] = [
    { checkoutId: "co-1", deviceId: "host", cwd: "/repo" },
    { checkoutId: "co-2", deviceId: "host", cwd: "/other" },
  ];

  it("matches by checkout id first", () => {
    expect(resolveDiff(diffs, { checkoutId: "co-2", deviceId: "viewer", cwd: "/other" })?.checkoutId).toBe("co-2");
  });

  it("falls back to device + cwd, then cwd alone", () => {
    expect(resolveDiff(diffs, { checkoutId: null, deviceId: "host", cwd: "/repo" })?.checkoutId).toBe("co-1");
    expect(resolveDiff(diffs, { checkoutId: null, deviceId: "viewer", cwd: "/repo" })?.checkoutId).toBe("co-1");
    expect(resolveDiff(diffs, { checkoutId: null, deviceId: "viewer", cwd: "/nowhere" })).toBeNull();
  });
});

describe("diff phase + scope helpers", () => {
  it("reports preparing when no diff has arrived", () => {
    expect(diffPhase(null)).toBe("preparing");
  });

  it("reports clean when the patch is empty and no files are listed", () => {
    expect(diffPhase({ patch: "", files: [] })).toBe("clean");
  });

  it("reports list otherwise", () => {
    expect(diffPhase({ patch: "diff --git a/x b/x\n", files: [] })).toBe("list");
  });

  it("maps scopes to wire modes", () => {
    expect(scopeMode("workingTree")).toBe("workingTree");
    expect(scopeMode("branch")).toBe("branch");
    expect(scopeMode("turn")).toBe("turn");
  });

  it("labels scope header strips", () => {
    expect(scopeLabel({ scope: "workingTree", count: 1 })).toBe("1 Uncommitted change");
    expect(scopeLabel({ scope: "workingTree", count: 3 })).toBe("3 Uncommitted changes");
    expect(scopeLabel({ scope: "branch", count: 4, base: "main" })).toBe("4 Changed files vs main");
    expect(scopeLabel({ scope: "branch", count: 0 })).toBe("0 Changed files");
    expect(scopeLabel({ scope: "turn", count: 2 })).toBe("2 Changed files this turn");
  });

  it("renders clean copy per scope", () => {
    expect(cleanMessage("workingTree", null)).toBe("No uncommitted changes");
    expect(cleanMessage("branch", "main")).toBe("No changes vs main");
    expect(cleanMessage("branch", null)).toBe("No branch changes");
    expect(cleanMessage("turn", null)).toBe("No changes this turn");
  });

  it("picks a default base ref", () => {
    expect(defaultBaseRef(["main"], "feature/x")).toBe("main");
    expect(defaultBaseRef(["feature/x", "main"], "feature/x")).toBe("main");
    expect(defaultBaseRef(["feature/x", "master"], "feature/x")).toBe("master");
    expect(defaultBaseRef(["feature/x", "develop"], "feature/x")).toBe("develop");
    expect(defaultBaseRef(["feature/x"], "feature/x")).toBe("feature/x");
    expect(defaultBaseRef([], null)).toBeNull();
  });
});

describe("splitPairs", () => {
  type L = { kind: "context" | "add" | "del" | "meta"; oldNo: number | null; newNo: number | null; text: string };
  const lines = (kinds: readonly L["kind"][]): L[] => kinds.map((kind) => ({ kind, oldNo: null, newNo: null, text: kind }));

  it("pairs context lines with themselves", () => {
    const pairs = splitPairs(lines(["context", "context"]));
    expect(pairs).toEqual([
      [0, 0],
      [1, 1],
    ]);
  });

  it("aligns a deletion block with its addition block", () => {
    const pairs = splitPairs(lines(["context", "del", "add", "context"]));
    expect(pairs).toEqual([
      [0, 0],
      [1, 2],
      [3, 3],
    ]);
  });

  it("flushes the block when a deletion arrives after additions", () => {
    const pairs = splitPairs(lines(["del", "add", "del", "add"]));
    expect(pairs).toEqual([
      [0, 1],
      [2, 3],
    ]);
  });

  it("pads the shorter side with nulls", () => {
    const pairs = splitPairs(lines(["del", "del", "del", "add"]));
    expect(pairs).toEqual([
      [0, 3],
      [1, null],
      [2, null],
    ]);
  });

  it("stops at the row budget", () => {
    const pairs = splitPairsUpto(lines(["del", "add", "del", "add"]), 1);
    expect(pairs).toEqual([[0, 1]]);
  });

  it("routes meta lines to the side they trail", () => {
    // A meta after a del sits in the del side; the trailing add pairs the
    // code rows together and the lone meta gets a one-sided row.
    const pairs = splitPairs(lines(["del", "meta", "add"]));
    expect(pairs).toEqual([
      [0, 2],
      [1, null],
    ]);
    // A meta after context sits on both sides — it pairs with itself.
    const pairContext = splitPairs(lines(["context", "meta"]));
    expect(pairContext).toEqual([
      [0, 0],
      [1, 1],
    ]);
  });
});

describe("gutterWidth + fileCounts", () => {
  it("clamps to the minimum column width", () => {
    const file = {
      path: "x.rs",
      oldPath: null,
      status: "modified" as const,
      binary: false,
      notices: [],
      hunks: [],
      additions: 1,
      deletions: 1,
      maxLine: 1,
    };
    expect(gutterWidth(file)).toBe(36);
  });

  it("grows with the line number", () => {
    const small = { ...baseFile(), maxLine: 9 };
    const big = { ...baseFile(), maxLine: 9999 };
    expect(gutterWidth(small)).toBe(36);
    expect(gutterWidth(big)).toBeGreaterThan(gutterWidth(small));
  });

  it("renders the +/- summary and a binary marker", () => {
    expect(fileCounts({ ...baseFile(), additions: 12, deletions: 4 })).toBe("+12 -4");
    expect(fileCounts({ ...baseFile(), additions: 5, deletions: 0 })).toBe("+5");
    expect(fileCounts({ ...baseFile(), additions: 0, deletions: 5 })).toBe("-5");
    expect(fileCounts({ ...baseFile(), binary: true })).toBe("Binary");
    expect(fileCounts({ ...baseFile(), additions: 0, deletions: 0 })).toBe("");
  });
});

describe("flattenFileRows", () => {
  it("returns just the header when collapsed", () => {
    const file = baseFile();
    const rows = flattenFileRows(file, 0, false);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.kind).toBe("fileHeader");
  });

  it("emits header + hunk header + per-line rows when expanded", () => {
    const file = parsedFile();
    const rows = flattenFileRows(file, 1, true);
    const kinds = rows.map((row) => row.kind);
    expect(kinds[0]).toBe("fileHeader");
    expect(kinds.slice(1, 1 + file.notices.length)).toEqual(file.notices.map(() => "notice"));
    for (const hunk of file.hunks) {
      expect(kinds).toContain("hunkHeader");
      expect(kinds.filter((kind) => kind === "line").length).toBeGreaterThanOrEqual(hunk.lines.length);
    }
  });
});

describe("parseKey", () => {
  it("folds every input that affects parse identity", () => {
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "ck", "branch", "dev"));
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "ck", "workingTree", "main"));
    expect(parseKey("co", "ck", "branch", "main")).not.toBe(parseKey("co", "other", "branch", "main"));
  });
});

function baseFile() {
  return {
    path: "x.rs",
    oldPath: null,
    status: "modified" as const,
    binary: false,
    notices: [],
    hunks: [] as { header: string; lines: ReturnType<typeof linesFor> }[],
    additions: 0,
    deletions: 0,
    maxLine: 1,
  };
}

function linesFor(): { kind: "context" | "add" | "del" | "meta"; oldNo: number | null; newNo: number | null; text: string }[] {
  return [];
}

function parsedFile() {
  return {
    path: "src/lib.rs",
    oldPath: null,
    status: "modified" as const,
    binary: false,
    notices: [],
    hunks: [
      {
        header: "@@ -1,2 +1,2 @@",
        lines: [
          { kind: "context" as const, oldNo: 1, newNo: 1, text: " one" },
          { kind: "del" as const, oldNo: 2, newNo: null, text: "-two" },
          { kind: "add" as const, oldNo: null, newNo: 2, text: "+TWO" },
        ],
      },
    ],
    additions: 1,
    deletions: 1,
    maxLine: 2,
  };
}
