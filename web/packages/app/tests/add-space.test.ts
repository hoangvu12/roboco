import { describe, expect, it } from "vitest";
import type { DriveEntry, FolderEntry } from "@roboco/proto";
import { addSpaceStore, toggleAddSpace } from "../src/state/add-space";
import {
  activeLocation,
  addSpaceCompletion,
  breadcrumbs,
  browserRows,
  childPath,
  completionPrefixLen,
  crumbFold,
  filteredFolders,
  isStaleResponse,
  manualPathQuery,
  parentPath,
  pathUnder,
  segmentTarget,
  typedPathTarget,
} from "../src/lib/add-space";

/**
 * Ports of the desktop's picker tests (`crates/ui/src/pickers.rs` test
 * module) plus the add-space derivations from `spaces.rs` the palette
 * renders from: `folder_paths_and_breadcrumbs` (`:4883`),
 * `completion_prefix_lengths` (`:4899`), `segment_target_resolution`
 * (`:4914`), `typed_path_target_expands_absolute_and_home_paths`
 * (`:4928`), and the `is_stale` / rail-location / crumb-fold rules
 * (`spaces.rs:234-243`, `:2596-2622`, `:2755-2763`).
 */

describe("folder_paths_and_breadcrumbs (pickers.rs:4883)", () => {
  it("parentPath climbs and stops at the root", () => {
    expect(parentPath("/home/w/dev")).toBe("/home/w");
    expect(parentPath("/home")).toBe("/");
    expect(parentPath("/home/")).toBe("/");
    expect(parentPath("/")).toBe(null);
    expect(parentPath("")).toBe(null);
  });

  it("childPath joins without doubling the separator", () => {
    expect(childPath("/home", "w")).toBe("/home/w");
    expect(childPath("/", "home")).toBe("/home");
  });

  it("breadcrumbs walk root-first, accumulating the full path", () => {
    const crumbs = breadcrumbs("/home/w/dev");
    expect(crumbs.map(([label]) => label)).toEqual(["/", "home", "w", "dev"]);
    expect(crumbs[2]![1]).toBe("/home/w");
    expect(breadcrumbs("/")).toHaveLength(1);
  });
});

describe("completion_prefix_lengths (pickers.rs:4899)", () => {
  it("is case-insensitive and indexes into the name", () => {
    expect(completionPrefixLen("Documents", "doc")).toBe(3);
    expect("Documents".slice(3)).toBe("uments");
    expect(completionPrefixLen("roboco", "roboco")).toBe(6);
    expect(completionPrefixLen("roboco", "")).toBe(0);
    expect(completionPrefixLen("roboco", "dev")).toBe(null);
    // Longer than the name → not a prefix.
    expect(completionPrefixLen("dev", "devel")).toBe(null);
  });

  it("multibyte names slice on a code-point boundary", () => {
    const len = completionPrefixLen("héllo", "hé");
    expect(len).not.toBe(null);
    expect("héllo".slice(len as number)).toBe("llo");
  });
});

describe("segment_target_resolution (pickers.rs:4914)", () => {
  const names = ["github", "GitHub", "worktree"];

  it("exact casing beats the earlier case-insensitive sibling", () => {
    expect(segmentTarget(names, "GitHub")).toBe(1);
    expect(segmentTarget(names, "github")).toBe(0);
  });

  it("case-insensitive exact still lands without an exact-cased hit", () => {
    expect(segmentTarget(names, "WORKTREE")).toBe(2);
  });

  it("unique prefix descends; an ambiguous one keeps the slash honest", () => {
    expect(segmentTarget(names, "work")).toBe(2);
    expect(segmentTarget(names, "g")).toBe(null);
    expect(segmentTarget(names, "x")).toBe(null);
  });
});

describe("typed_path_target_expands_absolute_and_home_paths (pickers.rs:4928)", () => {
  const home = "/home/wing";

  it("absolute paths trim their trailing slash and need no home", () => {
    expect(typedPathTarget("/disk2/", home)).toBe("/disk2");
    expect(typedPathTarget("/disk2/projects", home)).toBe("/disk2/projects");
    expect(typedPathTarget("/", home)).toBe("/");
    expect(typedPathTarget("/disk2", null)).toBe("/disk2");
  });

  it("home-relative paths expand against home", () => {
    expect(typedPathTarget("~", home)).toBe("/home/wing");
    expect(typedPathTarget("~/", home)).toBe("/home/wing");
    expect(typedPathTarget("~/github/", home)).toBe("/home/wing/github");
  });

  it("~x is a folder name; ~ cannot expand before home is known", () => {
    expect(typedPathTarget("~x", home)).toBe(null);
    expect(typedPathTarget("src", home)).toBe(null);
    expect(typedPathTarget("~/github", null)).toBe(null);
  });
});

describe("manual_path_query (spaces.rs:254-260)", () => {
  it("recognizes every typed-path shape, trimmed", () => {
    expect(manualPathQuery("/disk2")).toBe(true);
    expect(manualPathQuery("~/x")).toBe(true);
    expect(manualPathQuery("~")).toBe(true);
    expect(manualPathQuery("\\\\server")).toBe(true);
    expect(manualPathQuery("C:\\Users")).toBe(true);
    expect(manualPathQuery("  /disk2 ")).toBe(true);
    expect(manualPathQuery("roboco")).toBe(false);
    expect(manualPathQuery(".hidden")).toBe(false);
  });
});

describe("path_under (spaces.rs:497-500)", () => {
  it("is segment-aware — a sibling prefix is not a parent", () => {
    expect(pathUnder("/media/a", "/media")).toBe(true);
    expect(pathUnder("/media", "/media")).toBe(true);
    expect(pathUnder("/media/ab", "/media/a")).toBe(false);
    expect(pathUnder("/media/a", "/media/ab")).toBe(false);
    expect(pathUnder("/anything", "/")).toBe(true);
    expect(pathUnder("/anything", "")).toBe(true);
  });
});

const entry = (name: string, isDir: boolean, isRepo = false): FolderEntry => ({
  name,
  isDir,
  isRepo,
});

describe("browserRows + filteredFolders + completion (spaces.rs:2011-2166)", () => {
  const entries = [
    entry("dev", true, true),
    entry("notes.txt", false),
    entry("Documents", true),
    entry(".config", true),
    entry("github", true),
  ];

  it("browserRows keeps directories only", () => {
    expect(browserRows(entries).map((row) => row.name)).toEqual(["dev", "Documents", ".config", "github"]);
  });

  it("filtering ranks prefix matches first and hides dotfiles by default", () => {
    const rows = filteredFolders(entries, "");
    expect(rows.map((row) => row.name)).toEqual(["dev", "Documents", "github"]);
  });

  it("a leading dot reveals the dotfiles (client-side insurance)", () => {
    const rows = filteredFolders(entries, ".");
    expect(rows.map((row) => row.name)).toEqual([".config"]);
  });

  it("substring matches come after prefix matches", () => {
    const rows = filteredFolders(entries, "d");
    expect(rows.map((row) => row.name)).toEqual(["dev", "Documents"]);
  });

  it("completion prefers the highlighted row, else the first prefix match", () => {
    const rows = filteredFolders(entries, "");
    expect(addSpaceCompletion(rows, 0, "de")).toEqual({ name: "dev", suffix: "v" });
    expect(addSpaceCompletion(rows, 1, "de")).toEqual({ name: "dev", suffix: "v" });
    expect(addSpaceCompletion(rows, 0, "doc")).toEqual({ name: "Documents", suffix: "uments" });
    // An already-complete match previews nothing; an empty query neither.
    expect(addSpaceCompletion(rows, 0, "dev")).toBe(null);
    expect(addSpaceCompletion(rows, 0, "")).toBe(null);
    expect(addSpaceCompletion(rows, 0, "zzz")).toBe(null);
  });
});

describe("is_stale (spaces.rs:234-243)", () => {
  const flow = { identity: "id-1", revision: 3, deviceId: "device-a" };

  it("drops responses from another open, a superseded browse, or another device", () => {
    expect(isStaleResponse(flow, { identity: "id-1", revision: null, deviceId: "device-a" })).toBe(false);
    expect(isStaleResponse(flow, { identity: "id-2", revision: null, deviceId: "device-a" })).toBe(true);
    expect(isStaleResponse(flow, { identity: "id-1", revision: 4, deviceId: "device-a" })).toBe(true);
    expect(isStaleResponse(flow, { identity: "id-1", revision: null, deviceId: "device-b" })).toBe(true);
    // A null revision (path-keyed loads) never trips the revision check.
    expect(isStaleResponse(flow, { identity: "id-1", revision: 99, deviceId: "device-a" })).toBe(true);
    expect(isStaleResponse({ ...flow, revision: 99 }, { identity: "id-1", revision: 99, deviceId: "device-a" })).toBe(false);
  });
});

const drive = (name: string, path: string): DriveEntry => ({ name, path });

describe("activeLocation (spaces.rs:2596-2614)", () => {
  const drives = [drive("Macintosh HD", "/"), drive("T7 Shield", "/Volumes/t7")];

  it("home wins while the browsed path sits under it", () => {
    expect(activeLocation("/home/w/dev", "/home/w", drives)).toEqual({ kind: "home" });
  });

  it("the longest covering drive wins once home stops covering", () => {
    expect(activeLocation("/Volumes/t7/projects", "/home/w", drives)).toEqual({ kind: "drive", index: 1 });
  });

  it("the system root covers everything else", () => {
    expect(activeLocation("/opt/toolchain", "/home/w", drives)).toEqual({ kind: "drive", index: 0 });
    expect(activeLocation(null, "/home/w", drives)).toBe(null);
  });
});

describe("crumbFold (spaces.rs:2755-2763)", () => {
  it("the root crumb always folds; home folds only when the path is under it", () => {
    expect(crumbFold("/home/w/dev", "/home/w", null)).toBe(3);
    expect(crumbFold("/opt", "/home/w", null)).toBe(1);
    expect(crumbFold("/opt", null, null)).toBe(1);
  });

  it("a drive mount folds into the drive crumb, overriding home", () => {
    expect(crumbFold("/Volumes/t7/projects", "/home/w", "/Volumes/t7")).toBe(3);
  });
});

describe("addSpaceStore + toggleAddSpace (shell.rs:7832-7839)", () => {
  /**
   * The fixed `mod-k` binding's toggle, against the headless store (no
   * session attached: `open()` lands on no device and fires no loads).
   * The exit window now ends when the mounted palette reports Base UI's
   * `onOpenChangeComplete(false)` — `unmounted()` here stands in for that
   * callback (the old layer's 100ms+grace timer is gone with it).
   */
  const reaped = (): void => {
    addSpaceStore.unmounted();
  };

  it("a closed palette opens; a mounted one closes", () => {
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
    expect(addSpaceStore.getSnapshot().flow).toBe(null);

    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("open");
    expect(addSpaceStore.getSnapshot().flow).not.toBe(null);

    // Mounted → the same chord closes it (the flow lives through the exit
    // window so the card can paint its way out).
    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("closing");
    reaped();
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
    expect(addSpaceStore.getSnapshot().flow).toBe(null);
  });

  it("the chord re-opens once the close has fully drained", () => {
    addSpaceStore.open();
    addSpaceStore.close();
    reaped();
    toggleAddSpace();
    expect(addSpaceStore.getSnapshot().status).toBe("open");
    // Leave the singleton closed for whichever test runs next.
    addSpaceStore.close();
    reaped();
    expect(addSpaceStore.getSnapshot().status).toBe("closed");
  });
});
