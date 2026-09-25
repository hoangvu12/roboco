import { describe, expect, it } from "vitest";
import type { WorkspaceEntry, WorkspaceFileSearchMatch } from "@roboco/proto";
import {
  directoryRowDecoration,
  invalidatedDirectories,
  isLoadMoreMarkerPath,
  loadMoreMarkerDirectory,
  loadMoreMarkerPath,
  pageOperations,
  searchTreePaths,
  sequenceNeedsResync,
  treeGitStatusEntries,
  treePathOf,
  watchRemoval,
  workspacePathOf,
} from "../src/lib/tree-adapters";
import { treeSortComparator } from "../src/lib/tree-icons";

function entry(path: string, kind: WorkspaceEntry["kind"], fields: Partial<WorkspaceEntry> = {}): WorkspaceEntry {
  return { path, name: path.split("/").pop() ?? path, kind, ignored: false, readOnly: false, ...fields };
}

// ── Path vocabulary ────────────────────────────────────────────────────────

describe("treePathOf (the library's canonical form)", () => {
  it("directories carry a trailing slash; files and symlinks do not", () => {
    expect(treePathOf(entry("src", "directory"))).toBe("src/");
    expect(treePathOf(entry("src/lib.rs", "file"))).toBe("src/lib.rs");
    expect(treePathOf(entry("link", "symlink"))).toBe("link");
    expect(workspacePathOf("src/")).toBe("src");
    expect(workspacePathOf("src/lib.rs")).toBe("src/lib.rs");
  });
});

describe("load-more markers", () => {
  it("the marker row path nests under its directory and reads back out", () => {
    expect(loadMoreMarkerPath("")).toBe("Load more…");
    expect(loadMoreMarkerPath("src")).toBe("src/Load more…");
    expect(isLoadMoreMarkerPath("Load more…")).toBe(true);
    expect(isLoadMoreMarkerPath("src/Load more…")).toBe(true);
    expect(isLoadMoreMarkerPath("src/lib.rs")).toBe(false);
    expect(isLoadMoreMarkerPath("src")).toBe(false);
    expect(loadMoreMarkerDirectory("src/Load more…")).toBe("src");
    expect(loadMoreMarkerDirectory("Load more…")).toBe("");
  });
});

// ── Git status ─────────────────────────────────────────────────────────────

describe("treeGitStatusEntries (tickets 22/23 — b25dd404 parity)", () => {
  it("classifies staged and unstaged columns and untracked rows", () => {
    const entries = treeGitStatusEntries([
      { path: "src/a.rs", index: "modified", worktree: "unchanged" },
      { path: "src/b.rs", index: "unchanged", worktree: "modified" },
      { path: "src/new.rs", index: "untracked", worktree: "untracked" },
      { path: "src/c.rs", index: "added", worktree: "unchanged" },
    ]);
    expect(entries).toEqual([
      { path: "src/a.rs", status: "modified" },
      { path: "src/b.rs", status: "modified" },
      { path: "src/new.rs", status: "untracked" },
      { path: "src/c.rs", status: "added" },
    ]);
  });

  it("the worktree column wins, untracked dominates, unchanged rows carry nothing", () => {
    const entries = treeGitStatusEntries([
      { path: "a.rs", index: "added", worktree: "deleted" },
      { path: "clean.rs", index: "unchanged", worktree: "unchanged" },
    ]);
    expect(entries).toEqual([{ path: "a.rs", status: "deleted" }]);
  });

  it("copies collapse to added, typeChanged to modified, unmerged to deleted; renames keep their kind", () => {
    const entries = treeGitStatusEntries([
      { path: "copied.rs", index: "copied", worktree: "unchanged" },
      { path: "typed.rs", index: "typeChanged", worktree: "unchanged" },
      { path: "unmerged.rs", index: "unmerged", worktree: "unchanged" },
      { path: "renamed.rs", index: "renamed", worktree: "unchanged" },
    ]);
    expect(entries).toEqual([
      { path: "copied.rs", status: "added" },
      { path: "typed.rs", status: "modified" },
      { path: "unmerged.rs", status: "deleted" },
      { path: "renamed.rs", status: "renamed" },
    ]);
  });

  it("an empty or unavailable status never reports as clean — it decorates nothing", () => {
    expect(treeGitStatusEntries([])).toEqual([]);
    expect(treeGitStatusEntries(null)).toEqual([]);
  });
});

// ── Directory pages → mutation batches ─────────────────────────────────────

describe("pageOperations (directory page → mutation batch)", () => {
  it("a first page adds its entries, and the last page replaces unseen children", () => {
    const children = ["old.rs", "keep.rs"];
    const first = pageOperations({
      directory: "",
      entries: [entry("keep.rs", "file"), entry("src", "directory")],
      nextCursor: null,
      children,
      seen: new Set(),
      pendingMarker: false,
    });
    expect(first.operations).toEqual([
      { path: "src/", type: "add" },
      { path: "old.rs", type: "remove", recursive: true },
    ]);
    expect(first.children).toEqual(["keep.rs", "src/"]);
    expect(first.marker).toBe(false);
  });

  it("a paginated page re-trails the marker after its new entries", () => {
    const result = pageOperations({
      directory: "",
      entries: [entry("f0.txt", "file"), entry("f1.txt", "file")],
      nextCursor: "cursor-2",
      children: [],
      seen: new Set(),
      pendingMarker: false,
    });
    expect(result.operations).toEqual([
      { path: "f0.txt", type: "add" },
      { path: "f1.txt", type: "add" },
      { path: "Load more…", type: "add" },
    ]);
    expect(result.marker).toBe(true);
    expect(result.seen.has("f0.txt")).toBe(true);
  });

  it("a load-more page appends only unknown children and keeps the marker", () => {
    const page1 = pageOperations({
      directory: "",
      entries: [entry("f0.txt", "file")],
      nextCursor: "cursor-2",
      children: [],
      seen: new Set(),
      pendingMarker: false,
    });
    const page2 = pageOperations({
      directory: "",
      entries: [entry("z.txt", "file")],
      nextCursor: null,
      children: page1.children,
      seen: page1.seen,
      pendingMarker: true,
    });
    // The continuation's final page removes nothing established by the
    // sequence (f0 stays) and drops the marker it fetched for.
    expect(page2.operations).toEqual([
      { path: "z.txt", type: "add" },
      { path: "Load more…", type: "remove" },
    ]);
    expect(page2.children).toEqual(["f0.txt", "z.txt"]);
    expect(page2.marker).toBe(false);
  });

  it("a real entry colliding with the marker basename wins — no marker row", () => {
    const result = pageOperations({
      directory: "src",
      entries: [entry("src/Load more…", "file"), entry("src/a.rs", "file")],
      nextCursor: "cursor-2",
      children: [],
      seen: new Set(),
      pendingMarker: false,
    });
    expect(result.operations).toEqual([
      { path: "src/Load more…", type: "add" },
      { path: "src/a.rs", type: "add" },
    ]);
    expect(result.marker).toBe(false);
  });
});

// ── Watch events ───────────────────────────────────────────────────────────

describe("sequenceNeedsResync (desktop watch.rs)", () => {
  it("matches the desktop truth table", () => {
    expect(sequenceNeedsResync(null, 40)).toBe(false);
    expect(sequenceNeedsResync(40, 41)).toBe(false);
    expect(sequenceNeedsResync(40, 42)).toBe(true);
    expect(sequenceNeedsResync(40, 40)).toBe(true);
  });
});

describe("watch invalidation", () => {
  it("created/removed events invalidate their parent directory (the root for root-level paths)", () => {
    expect(invalidatedDirectories({ kind: "created", path: "src/lib.rs" })).toEqual(["src"]);
    expect(invalidatedDirectories({ kind: "removed", path: "src/lib.rs" })).toEqual(["src"]);
    expect(invalidatedDirectories({ kind: "created", path: "README.md" })).toEqual([""]);
  });

  it("renames invalidate both sides; modified touches nothing", () => {
    expect(invalidatedDirectories({ kind: "renamed", path: "src/new.rs", oldPath: "src/old.rs" })).toEqual(["src", "src"]);
    expect(invalidatedDirectories({ kind: "modified", path: "src/lib.rs" })).toEqual([]);
  });

  it("removals drop their subtree, renames drop the old path, creations defer to the re-list", () => {
    expect(watchRemoval({ kind: "removed", path: "src" })).toEqual({ path: "src", type: "remove", recursive: true });
    expect(watchRemoval({ kind: "renamed", path: "b.txt", oldPath: "a.txt" })).toEqual({ path: "a.txt", type: "remove", recursive: true });
    expect(watchRemoval({ kind: "renamed", path: "b.txt" })).toBeNull();
    expect(watchRemoval({ kind: "created", path: "b.txt" })).toBeNull();
    expect(watchRemoval({ kind: "modified", path: "b.txt" })).toBeNull();
  });
});

// ── Row decorations ────────────────────────────────────────────────────────

describe("directoryRowDecoration (loading / empty / error-retry)", () => {
  it("files and collapsed directories carry nothing", () => {
    expect(directoryRowDecoration({ kind: "loading", message: null, childrenCount: 0 }, { kind: "file", isExpanded: false })).toBeNull();
    expect(directoryRowDecoration({ kind: "loading", message: null, childrenCount: 0 }, { kind: "directory", isExpanded: false })).toBeNull();
  });

  it("expanded directories spell their load state", () => {
    const row = { kind: "directory" as const, isExpanded: true };
    expect(directoryRowDecoration({ kind: "loading", message: null, childrenCount: 0 }, row)).toEqual({ text: "Loading…", title: "Loading directory" });
    expect(directoryRowDecoration({ kind: "loaded", message: null, childrenCount: 0 }, row)).toEqual({ text: "Empty", title: "Empty folder" });
    expect(directoryRowDecoration({ kind: "error", message: "offline", childrenCount: 2 }, row)).toMatchObject({ text: "offline — Retry" });
    expect(directoryRowDecoration({ kind: "loaded", message: null, childrenCount: 1 }, row)).toBeNull();
  });
});

// ── Search results ─────────────────────────────────────────────────────────

function match(path: string, score: number, kind: WorkspaceEntry["kind"] = "file"): WorkspaceFileSearchMatch {
  return { path, name: path.split("/").pop() ?? path, kind, score };
}

describe("searchTreePaths (the search-tree build, ported)", () => {
  it("groups matches into a synthetic ancestor tree keyed by PATH COMPONENTS", () => {
    // A match whose basename equals its directory's name still nests under
    // the directory row (the desktop test
    // search_tree_uses_path_components_instead_of_name_prefixes).
    const { orderedPaths, directoryPaths } = searchTreePaths([match("NOTICES/NOTICES.md", 5)]);
    expect(orderedPaths).toEqual(["NOTICES/", "NOTICES/NOTICES.md"]);
    expect(directoryPaths).toEqual(["NOTICES/"]);
  });

  it("orders by bestScore descending, then directories before files, then name", () => {
    const { orderedPaths } = searchTreePaths([
      match("zeta.txt", 1),
      match("alpha/beta.rs", 10),
      match("alpha/gamma.rs", 10),
      match("alpha", 3, "directory"),
    ]);
    // alpha's bestScore (10) outranks zeta (1); within alpha, the directory
    // match itself is a directory row (beta.rs and gamma.rs are files).
    expect(orderedPaths).toEqual(["alpha/", "alpha/beta.rs", "alpha/gamma.rs", "zeta.txt"]);
  });

  it("ranks every emitted path so the model's comparator reproduces the order", () => {
    const { orderedPaths, rank } = searchTreePaths([match("a/b/c.rs", 5), match("d.rs", 4)]);
    expect([...rank.entries()].sort((l, r) => l[1] - r[1]).map(([path]) => path)).toEqual([...orderedPaths]);
  });

  it("directories take their canonical trailing-slash form", () => {
    const { orderedPaths } = searchTreePaths([match("src", 5, "directory"), match("src/main.rs", 2)]);
    expect(orderedPaths).toEqual(["src/", "src/main.rs"]);
  });
});

// ── Sort order ─────────────────────────────────────────────────────────────

describe("treeSortComparator", () => {
  const sortEntry = (path: string, isDirectory: boolean) => {
    const normalized = path.endsWith("/") ? path.slice(0, -1) : path;
    const basename = normalized.split("/").pop()!;
    return { basename, depth: 0, isDirectory, path, segments: normalized.split("/") };
  };

  it("directories sort before files, then case-insensitive name", () => {
    const entries = [sortEntry("b.txt", false), sortEntry("src/", true), sortEntry("a.txt", false)];
    expect([...entries].sort(treeSortComparator).map((e) => e.path)).toEqual(["src/", "a.txt", "b.txt"]);
  });

  it("the load-more marker trails its real siblings, whatever their names", () => {
    const entries = [
      sortEntry("src/z.txt", false),
      sortEntry("src/Load more…", false),
      sortEntry("src/a.txt", false),
    ];
    expect([...entries].sort(treeSortComparator).map((e) => e.path)).toEqual([
      "src/a.txt",
      "src/z.txt",
      "src/Load more…",
    ]);
  });

  it("markers in different directories stay within their own subtrees", () => {
    const entries = [
      sortEntry("b/Load more…", false),
      sortEntry("a/m.rs", false),
      sortEntry("b/", true),
    ];
    const ordered = [...entries].sort(treeSortComparator).map((e) => e.path);
    expect(ordered.indexOf("b/Load more…")).toBeGreaterThan(ordered.indexOf("b/"));
    expect(ordered.indexOf("b/")).toBeGreaterThan(ordered.indexOf("a/m.rs"));
  });
});
