import { describe, expect, it } from "vitest";
import type { WorkspaceDirectoryPage, WorkspaceEntry, WorkspaceFileChanges } from "@roboco/proto";
import { WorkspaceFilesClient, type FilesCaller } from "../src/lib/files-client";
import { loadMoreMarkerPath } from "../src/lib/tree-adapters";
import { WorkspaceTreeModel, type FileWatchEvent } from "../src/lib/workspace-tree";

/**
 * `WorkspaceTreeModel` — the trees-library model driven by the files data
 * layer, against an in-memory paged `FilesCaller` fake (the port of the old
 * `file-tree.test.ts`). The library runs headless here: assertions read the
 * model's own state (visible rows, expansion, the status lane join) exactly
 * as the mounted panel does.
 */

function entry(path: string, kind: WorkspaceEntry["kind"], fields: Partial<WorkspaceEntry> = {}): WorkspaceEntry {
  return { path, name: path.split("/").pop() ?? path, kind, ignored: false, readOnly: false, ...fields };
}

function page(directory: string, entries: WorkspaceEntry[], nextCursor?: string): WorkspaceDirectoryPage {
  return { directory, entries, nextCursor: nextCursor ?? null, truncated: nextCursor !== undefined };
}

/** An in-memory directory store answering ListWorkspaceDirectory. */
function fakeCaller(directories: Record<string, WorkspaceEntry[]>) {
  const calls: { directory: string; cursor: string | undefined }[] = [];
  const caller: FilesCaller = {
    call<T>(_method: string, params?: unknown): Promise<T> {
      const request = params as { directory: string; cursor?: string };
      calls.push({ directory: request.directory, cursor: request.cursor });
      const entries = directories[request.directory];
      if (entries === undefined) {
        return Promise.reject(new Error(`no such directory: ${request.directory}`));
      }
      return Promise.resolve(page(request.directory, entries) as T);
    },
  };
  return { caller, calls };
}

function model(client: WorkspaceFilesClient, events?: FileWatchEvent[]): WorkspaceTreeModel {
  return new WorkspaceTreeModel({ client, onFileEvent: (event) => events?.push(event) });
}

async function settle(): Promise<void> {
  // Flush the model's promise chains (client resolve → apply → notify).
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/** The directory handle behind a workspace path, if the model holds it. */
function directory(model: WorkspaceTreeModel, path: string) {
  const item = model.tree.getItem(path);
  return item !== null && "toggle" in item ? item : null;
}

/** The visible row paths (directories in canonical trailing-slash form). */
function rows(model: WorkspaceTreeModel): string[] {
  return model.tree.getVisibleRows(0, model.tree.getVisibleCount()).map((row) => row.path);
}

describe("WorkspaceTreeModel loads", () => {
  it("loads the root on start and orders directories before files", async () => {
    const { caller } = fakeCaller({
      "": [entry("b.txt", "file"), entry("src", "directory"), entry("a.txt", "file")],
    });
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(rows(m)).toEqual(["src/", "a.txt", "b.txt"]);
    expect(m.getSnapshot().rootLoaded).toBe(true);
    m.dispose();
  });

  it("lazily loads a directory on expand and collapses it", async () => {
    const { caller, calls } = fakeCaller({
      "": [entry("src", "directory")],
      src: [entry("src/lib.rs", "file")],
    });
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(rows(m)).toEqual(["src/"]);
    expect(calls.filter((c) => c.directory === "src")).toHaveLength(0);

    directory(m, "src")!.toggle();
    await settle();
    expect(rows(m)).toEqual(["src/", "src/lib.rs"]);
    expect(calls.filter((c) => c.directory === "src")).toHaveLength(1);

    directory(m, "src")!.toggle();
    await settle();
    expect(rows(m)).toEqual(["src/"]);
    m.dispose();
  });

  it("shows the load-more marker row while paged and appends the next page", async () => {
    const first = Array.from({ length: 2 }, (_, index) => entry(`f${index}.txt`, "file"));
    const caller: FilesCaller = {
      call<T>(_method: string, params?: unknown): Promise<T> {
        const request = params as { directory: string; cursor?: string };
        if (request.cursor === undefined) {
          return Promise.resolve(page("", first, "cursor-2") as T);
        }
        return Promise.resolve(page("", [entry("z.txt", "file")]) as T);
      },
    };
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(rows(m)).toEqual(["f0.txt", "f1.txt", loadMoreMarkerPath("")]);

    m.loadMore("");
    await settle();
    expect(rows(m)).toEqual(["f0.txt", "f1.txt", "z.txt"]);
    m.dispose();
  });

  it("a failed load surfaces its message and retries on demand", async () => {
    let fail = true;
    const caller: FilesCaller = {
      call<T>(): Promise<T> {
        if (fail) {
          return Promise.reject(new Error("disk exploded"));
        }
        return Promise.resolve(page("", [entry("a.txt", "file")]) as T);
      },
    };
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(m.getSnapshot().rootError).toBe("disk exploded");
    expect(m.getSnapshot().rootLoaded).toBe(false);
    expect(m.getSnapshot().directoryErrors.get("")).toBe("disk exploded");

    fail = false;
    m.retryRoot();
    await settle();
    expect(m.getSnapshot().rootError).toBeNull();
    expect(rows(m)).toEqual(["a.txt"]);
    m.dispose();
  });

  it("setIncludeIgnored re-lists from scratch", async () => {
    const { caller } = fakeCaller({ "": [entry("a.txt", "file")] });
    const client = new WorkspaceFilesClient(caller, { spaceId: "s1" });
    const m = model(client);
    m.start();
    await settle();
    m.setIncludeIgnored(true);
    await settle();
    expect(m.getSnapshot().includeIgnored).toBe(true);
    expect(rows(m)).toEqual(["a.txt"]);
    m.dispose();
  });
});

describe("WorkspaceTreeModel watch application", () => {
  function watched(directories: Record<string, WorkspaceEntry[]>, events: FileWatchEvent[]) {
    const { caller, calls } = fakeCaller(directories);
    let handler: ((frame: WorkspaceFileChanges) => void) | null = null;
    const m = new WorkspaceTreeModel({
      client: new WorkspaceFilesClient(caller, { spaceId: "s1" }),
      watch: (handlers) => {
        handler = (frame) => handlers.onItem(frame, { generation: 1 });
        return { cancel: () => {} };
      },
      onFileEvent: (event) => events.push(event),
    });
    return { m, calls, emit: (frame: WorkspaceFileChanges) => handler!(frame) };
  }

  it("reloads the parent directory when a file is created inside it", async () => {
    const events: FileWatchEvent[] = [];
    const store: Record<string, WorkspaceEntry[]> = { "": [entry("src", "directory")] };
    const { m, calls, emit } = watched(store, events);
    m.start();
    await settle();
    expect(rows(m)).toEqual(["src/"]);

    store[""] = [entry("src", "directory"), entry("README.md", "file")];
    emit({ sequence: 1, resyncRequired: false, changes: [{ kind: "created", path: "README.md" }] });
    await settle();
    expect(rows(m)).toEqual(["src/", "README.md"]);
    expect(events).toEqual([{ kind: "created", path: "README.md" }]);
    expect(calls.filter((c) => c.directory === "").length).toBeGreaterThanOrEqual(2);
    m.dispose();
  });

  it("drops a removed subtree and notifies", async () => {
    const events: FileWatchEvent[] = [];
    const store: Record<string, WorkspaceEntry[]> = { "": [entry("src", "directory")], src: [entry("src/lib.rs", "file")] };
    const { m, emit } = watched(store, events);
    m.start();
    await settle();
    directory(m, "src")!.toggle();
    await settle();
    expect(rows(m)).toEqual(["src/", "src/lib.rs"]);

    // The disk truth moves with the event; the parent reload confirms it.
    store[""] = [];
    emit({ sequence: 1, resyncRequired: false, changes: [{ kind: "removed", path: "src" }] });
    await settle();
    expect(rows(m)).toEqual([]);
    expect(events).toEqual([{ kind: "removed", path: "src" }]);
    m.dispose();
  });

  it("a failed background refresh keeps the loaded rows (desktop regression)", async () => {
    let fail = false;
    const base = fakeCaller({ "": [entry("a.txt", "file")] });
    const caller: FilesCaller = {
      call<T>(method: string, params?: unknown): Promise<T> {
        if (fail) {
          return Promise.reject(new Error("offline"));
        }
        return base.caller.call(method, params);
      },
    };
    const events: FileWatchEvent[] = [];
    let handler: ((frame: WorkspaceFileChanges) => void) | null = null;
    const m = new WorkspaceTreeModel({
      client: new WorkspaceFilesClient(caller, { spaceId: "s1" }),
      watch: (handlers) => {
        handler = (frame) => handlers.onItem(frame, { generation: 1 });
        return { cancel: () => {} };
      },
      onFileEvent: (event) => events.push(event),
    });
    m.start();
    await settle();
    expect(rows(m)).toEqual(["a.txt"]);

    // A created event invalidates the parent and reloads it in the
    // background; the failure must not blank the listing.
    fail = true;
    handler!({ sequence: 1, resyncRequired: false, changes: [{ kind: "created", path: "b.txt" }] });
    await settle();
    expect(rows(m)).toEqual(["a.txt"]);
    expect(m.getSnapshot().directoryErrors.get("")).toBe("offline");
    m.dispose();
  });

  it("a sequence gap resyncs: every loaded directory re-lists", async () => {
    const events: FileWatchEvent[] = [];
    const store: Record<string, WorkspaceEntry[]> = { "": [entry("src", "directory")], src: [entry("src/a.rs", "file")] };
    const { m, calls, emit } = watched(store, events);
    m.start();
    await settle();
    directory(m, "src")!.toggle();
    await settle();
    emit({ sequence: 1, resyncRequired: false, changes: [] });
    const before = calls.length;

    store["src"] = [entry("src/a.rs", "file"), entry("src/b.rs", "file")];
    emit({ sequence: 3, resyncRequired: false, changes: [{ kind: "modified", path: "src/b.rs" }] });
    await settle();
    expect(events.some((event) => event.kind === "resync")).toBe(true);
    expect(calls.length).toBeGreaterThan(before);
    expect(rows(m)).toContain("src/b.rs");
    m.dispose();
  });

  it("resyncRequired forces a reload without a gap", async () => {
    const events: FileWatchEvent[] = [];
    const { m, emit } = watched({ "": [entry("a.txt", "file")] }, events);
    m.start();
    await settle();
    emit({ sequence: 1, resyncRequired: true, changes: [] });
    await settle();
    expect(events).toEqual([{ kind: "resync" }]);
    m.dispose();
  });

  it("forwards modified and renamed events for the open document", async () => {
    const events: FileWatchEvent[] = [];
    const { m, emit } = watched({ "": [entry("a.txt", "file")] }, events);
    m.start();
    await settle();
    emit({ sequence: 1, resyncRequired: false, changes: [{ kind: "modified", path: "a.txt" }] });
    emit({ sequence: 2, resyncRequired: false, changes: [{ kind: "renamed", path: "b.txt", oldPath: "a.txt" }] });
    await settle();
    expect(events).toEqual([
      { kind: "modified", path: "a.txt" },
      { kind: "renamed", path: "b.txt", oldPath: "a.txt" },
    ]);
    m.dispose();
  });
});

describe("WorkspaceTreeModel git status lane", () => {
  it("maps the frame's files onto the lane and clears on null", async () => {
    const { caller } = fakeCaller({ "": [entry("a.txt", "file")] });
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();

    m.applyGitStatus([
      { path: "a.txt", index: "modified", worktree: "unchanged" },
      { path: "clean.txt", index: "unchanged", worktree: "unchanged" },
    ]);
    expect(m.getSnapshot().gitStatus).toEqual([{ path: "a.txt", status: "modified" }]);

    m.applyGitStatus(null);
    expect(m.getSnapshot().gitStatus).toEqual([]);
    m.dispose();
  });

  it("ignored listing entries join the lane as the show-all dimming", async () => {
    const { caller } = fakeCaller({
      "": [entry("a.txt", "file"), entry("target", "directory", { ignored: true }), entry("target/out.rs", "file", { ignored: true })],
    });
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(m.getSnapshot().gitStatus).toContainEqual({ path: "target/", status: "ignored" });
    expect(m.getSnapshot().gitStatus).toContainEqual({ path: "target/out.rs", status: "ignored" });
    m.dispose();
  });
});

describe("WorkspaceTreeModel reveal and refresh", () => {
  it("revealInTree lists ancestors, expands them, and selects the row", async () => {
    const { caller, calls } = fakeCaller({
      "": [entry("src", "directory")],
      src: [entry("src/inner", "directory")],
      "src/inner": [entry("src/inner/main.rs", "file")],
    });
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    expect(m.isExpanded("src")).toBe(false);

    const error = await m.revealInTree("src/inner/main.rs");
    expect(error).toBeNull();
    expect(m.isExpanded("src")).toBe(true);
    expect(m.isExpanded("src/inner")).toBe(true);
    expect(m.tree.getSelectedPaths()).toEqual(["src/inner/main.rs"]);
    expect(rows(m)).toEqual(["src/", "src/inner/", "src/inner/main.rs"]);
    // start() already listed the root once; the reveal walks it again.
    expect(calls.map((c) => c.directory)).toEqual(["", "", "src", "src/inner"]);
    m.dispose();
  });

  it("revealInTree reports the engine's failure without touching the tree", async () => {
    const { caller } = fakeCaller({ "": [entry("src", "directory")] });
    const failing: FilesCaller = {
      call<T>(method: string, params?: unknown): Promise<T> {
        const request = params as { directory: string };
        if (request.directory === "src") {
          return Promise.reject(new Error("no such directory: src"));
        }
        return caller.call<T>(method, params);
      },
    };
    const m = model(new WorkspaceFilesClient(failing, { spaceId: "s1" }));
    m.start();
    await settle();

    const error = await m.revealInTree("src/main.rs");
    expect(error).toContain("no such directory");
    expect(m.tree.getSelectedPaths()).toEqual([]);
    m.dispose();
  });

  it("refresh marks everything stale and reloads the root plus expanded directories", async () => {
    const store: Record<string, WorkspaceEntry[]> = {
      "": [entry("src", "directory")],
      src: [entry("src/lib.rs", "file")],
    };
    const { caller, calls } = fakeCaller(store);
    const m = model(new WorkspaceFilesClient(caller, { spaceId: "s1" }));
    m.start();
    await settle();
    directory(m, "src")!.toggle();
    await settle();
    const before = calls.length;

    store["src"] = [entry("src/lib.rs", "file"), entry("src/new.rs", "file")];
    m.refresh();
    await settle();
    expect(calls.length).toBeGreaterThan(before);
    expect(calls.filter((c) => c.directory === "src").length).toBeGreaterThanOrEqual(2);
    expect(rows(m)).toEqual(["src/", "src/lib.rs", "src/new.rs"]);
    m.dispose();
  });
});
