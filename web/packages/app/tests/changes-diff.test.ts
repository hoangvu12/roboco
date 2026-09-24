import { beforeEach, describe, expect, it } from "vitest";
import type { CodeViewDiffItem, CodeViewItem, FileDiffMetadata } from "@pierre/diffs";
import {
  __resetChangesDiffForTests,
  diffCodeItems,
  fileDiffNotices,
  isBinaryFileDiff,
  parseDiffFiles,
} from "../src/lib/changes-diff";
import type { FileFold } from "../src/lib/diff";

/**
 * The Changes pane's Pierre-diffs adapter (ticket 02, web-pierre-adoption):
 * the patch → parsed-metadata mapping (memoized per checkout + checksum),
 * the fold/version state of the controlled items, and the file notices the
 * library header's metadata slot renders. Pure — no React, no DOM.
 */

const MODIFIED_PATCH = `diff --git a/src/lib.ts b/src/lib.ts
index 3e2f1a..9b4c2d 100644
--- a/src/lib.ts
+++ b/src/lib.ts
@@ -1,3 +1,4 @@
 context
-removed
+added
+another
 context
`;

const BRANCH_PATCH = `diff --git a/other.ts b/other.ts
index 111111..222222 100644
--- a/other.ts
+++ b/other.ts
@@ -1,1 +1,1 @@
-a
+b
`;

function diff(patch: string, checksum: string): { checkoutId: string; checksum: string; patch: string } {
  return { checkoutId: "co-1", checksum, patch };
}

function fold(collapsed: boolean): FileFold {
  return { collapsed, epoch: collapsed ? 1 : 0 };
}

function asDiffItem(item: CodeViewItem<undefined> | undefined): CodeViewDiffItem<undefined> {
  expect(item?.type).toBe("diff");
  return item as CodeViewDiffItem<undefined>;
}

beforeEach(() => {
  __resetChangesDiffForTests();
});

describe("parseDiffFiles", () => {
  it("parses the patch into the library's per-file metadata", () => {
    const files = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);
    expect(files).toHaveLength(1);
    const file = files[0]!;
    expect(file.name).toBe("src/lib.ts");
    expect(file.type).toBe("change");
    expect(file.isPartial).toBe(true);
    expect(file.hunks).toHaveLength(1);
    expect(file.hunks[0]!.additionLines).toBe(2);
    expect(file.hunks[0]!.deletionLines).toBe(1);
    // The library's line strings are newline-terminated.
    expect(file.additionLines).toEqual(["context\n", "added\n", "another\n", "context\n"]);
    expect(file.deletionLines).toEqual(["context\n", "removed\n", "context\n"]);
  });

  it("memoizes per (checkout, checksum, scope, base) — never re-parsed per render", () => {
    const first = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);
    const second = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);
    expect(second).toBe(first);
    // A different scope re-parses even with a shared checksum — the old
    // useParsedDiff keyed everything under workingTree.
    const branch = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "branch", "main");
    expect(branch).not.toBe(first);
    expect(branch[0]!.name).toBe("src/lib.ts");
    // A different base under the branch scope re-parses too.
    const branchDev = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "branch", "dev");
    expect(branchDev).not.toBe(branch);
    // A different checksum re-parses.
    const other = parseDiffFiles(diff(BRANCH_PATCH, "sum-2"), "workingTree", null);
    expect(other).not.toBe(first);
    expect(other[0]!.name).toBe("other.ts");
  });

  it("holds at most ~8 entries, evicting the oldest first", () => {
    const first = parseDiffFiles(diff(BRANCH_PATCH, "sum-0"), "workingTree", null);
    for (let ix = 1; ix < 10; ix += 1) {
      parseDiffFiles(diff(BRANCH_PATCH, `sum-${ix}`), "workingTree", null);
    }
    // sum-0 was evicted (9 newer entries): a re-parse yields a new array —
    // identity is the cache's observable, the content is identical.
    const reFirst = parseDiffFiles(diff(BRANCH_PATCH, "sum-0"), "workingTree", null);
    expect(reFirst).not.toBe(first);
    const late = parseDiffFiles(diff(BRANCH_PATCH, "sum-9"), "workingTree", null);
    const lateAgain = parseDiffFiles(diff(BRANCH_PATCH, "sum-9"), "workingTree", null);
    expect(lateAgain).toBe(late);
  });

  it("seeds the per-file highlight cache keys from the parse key", () => {
    const files = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);
    expect(files[0]!.cacheKey).toContain("co-1:sum-1:workingTree:");
    // The same key re-parses identically after an eviction (cache keys are
    // content-derived, never random).
    __resetChangesDiffForTests();
    const reparsed = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);
    expect(reparsed[0]!.cacheKey).toBe(files[0]!.cacheKey);
  });

  it("parses nothing when nothing resolved", () => {
    expect(parseDiffFiles(null, "workingTree", null)).toEqual([]);
  });

  it("classifies binary files and notices from the patch shape", () => {
    const files = parseDiffFiles(
      diff(
        `diff --git a/a.txt b/a.txt
index 111111..222222 100644
--- a/a.txt
+++ b/a.txt
@@ -1,1 +1,2 @@
-a
+b
+c

diff --git a/blob.bin b/blob.bin
index 3333333..4444444 100644
Binary files a/blob.bin and b/blob.bin differ

diff --git a/made.txt b/made.txt
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/made.txt
@@ -0,0 +1,1 @@
+hello

diff --git a/gone.txt b/gone.txt
deleted file mode 100644
index 222222..0000000
--- a/gone.txt
+++ /dev/null
@@ -1,1 +0,0 @@
-bye

diff --git a/exec.sh b/exec.sh
old mode 100644
new mode 100755

diff --git a/pure.txt b/pure2.txt
similarity index 100%
rename from pure.txt
rename to pure2.txt
`,
        "sum-3",
      ),
      "workingTree",
      null,
    );
    const byName = new Map(files.map((file) => [file.name, file]));
    expect(byName.get("a.txt")!.type).toBe("change");
    expect(isBinaryFileDiff(byName.get("blob.bin")!)).toBe(true);
    expect(byName.get("made.txt")!.type).toBe("new");
    expect(byName.get("gone.txt")!.type).toBe("deleted");
    expect(byName.get("exec.sh")!.prevMode).toBe("100644");
    expect(byName.get("exec.sh")!.mode).toBe("100755");
    expect(byName.get("pure2.txt")!.type).toBe("rename-pure");
    expect(byName.get("pure2.txt")!.prevName).toBe("pure.txt");
  });
});

describe("diffCodeItems", () => {
  const files = parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null);

  it("shapes parsed metadata into controlled diff items keyed by path", () => {
    const items = diffCodeItems(files, new Map());
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({
      id: "src/lib.ts",
      type: "diff",
      collapsed: false,
    });
    expect(asDiffItem(items[0]).fileDiff).toBe(files[0]);
  });

  it("maps the surface store's folds onto item collapsed state, bumping the version", () => {
    const expanded = diffCodeItems(files, new Map());
    const folds = new Map([["src/lib.ts", fold(true)]]);
    const collapsed = diffCodeItems(files, folds);
    expect(collapsed[0]!.collapsed).toBe(true);
    // The version moved — the library only adopts changed fileDiff/
    // collapsed state through a version bump.
    expect(collapsed[0]!.version!).toBeGreaterThan(asDiffItem(expanded[0]).version!);
    // An unchanged fold keeps the version — pure re-renders do not churn
    // the library's layout.
    expect(diffCodeItems(files, folds)[0]!.version).toBe(collapsed[0]!.version);
  });

  it("re-versions every file when the parse identity changes (scope switch)", () => {
    const before = diffCodeItems(files, new Map());
    const branchFiles = parseDiffFiles(diff(BRANCH_PATCH, "sum-2"), "branch", "main");
    const after = diffCodeItems(branchFiles, new Map());
    expect(after[0]!.version!).toBeGreaterThan(asDiffItem(before[0]).version!);
    // And back on the working-tree parse, the same file's item is
    // re-versioned again (its fileDiff object identity differs).
    const again = diffCodeItems(parseDiffFiles(diff(MODIFIED_PATCH, "sum-1"), "workingTree", null), new Map());
    expect(again[0]!.version!).toBeGreaterThan(asDiffItem(after[0]).version!);
  });
});

describe("fileDiffNotices", () => {
  function metadata(overrides: Partial<FileDiffMetadata>): FileDiffMetadata {
    return {
      name: "x.txt",
      type: "change",
      hunks: [],
      splitLineCount: 0,
      unifiedLineCount: 0,
      isPartial: true,
      additionLines: [],
      deletionLines: [],
      ...overrides,
    } as FileDiffMetadata;
  }

  it("carries the status words the old notice rows showed", () => {
    expect(fileDiffNotices(metadata({ type: "new" }))).toEqual(["New file"]);
    expect(fileDiffNotices(metadata({ type: "deleted" }))).toEqual(["Deleted file"]);
  });

  it("surfaces binary files — contents not shown", () => {
    expect(fileDiffNotices(metadata({}))).toEqual(["Binary file — contents not shown"]);
  });

  it("carries mode changes; renames rely on the library's prev → name header", () => {
    expect(fileDiffNotices(metadata({ type: "change", prevMode: "100644", mode: "100755", hunks: [{} as never] }))).toEqual([
      "Mode changed to 100755",
    ]);
    expect(fileDiffNotices(metadata({ type: "rename-pure", prevName: "old.txt" }))).toEqual([]);
    expect(fileDiffNotices(metadata({ type: "rename-changed", prevName: "old.txt" }))).toEqual([]);
  });
});
