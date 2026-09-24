// @vitest-environment jsdom

/**
 * Ticket 06 — the file tree panel on the trees library, mounted. The library
 * renders its rows inside shadow DOM (tested upstream); per the spec's
 * testing decisions this suite NEVER reaches inside it. Interactions are
 * driven through the model's public handles (`model.tree.getItem(path)`
 * `toggle()`, `model.loadMoreIfMarker`, watch/git-status frames) and the
 * panel's own light-DOM chrome (the search input's keys), and the suite
 * asserts the panel's chrome, the model's own state (visible rows,
 * expansion, the status lane), and the flows the acceptance list names:
 * lazy expand, pagination, watch update, sequence-gap resync, git-status
 * update, click-to-open, and the RPC search (debounce, capped-results
 * banner, reveal-and-open).
 *
 * Exactly ONE composed-click case remains (click-to-open, below): the row
 * click → open wiring lives on the host element and no model API stands in
 * for it, so that test drives a real row click through the shadow DOM's
 * `data-item-path` markup — best-effort, non-load-bearing: if upstream
 * renames the attribute, that single test fails and the rest still hold.
 */

import { act, createElement, type ComponentProps } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { methods } from "@roboco/engine-client";
import type {
  WorkspaceDirectoryPage,
  WorkspaceEntry,
  WorkspaceFileChanges,
  WorkspaceFileSearchMatch,
  WorkspaceGitStatusFrame,
} from "@roboco/proto";
import { WorkspaceFilesClient, type FilesCaller } from "../src/lib/files-client";
import { loadMoreMarkerPath } from "../src/lib/tree-adapters";
import { WorkspaceTreeModel } from "../src/lib/workspace-tree";
import { FileTreePanel } from "../src/components/files/file-tree-panel";

// ── jsdom gaps the mounted panel hits (the base-tooltip idiom) ──────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

const unmounts: Array<() => void> = [];

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  document.body.replaceChildren();
});

// ── The scripted fake engine (the file-tree.test.ts FilesCaller pattern) ────

function entry(path: string, kind: WorkspaceEntry["kind"], fields: Partial<WorkspaceEntry> = {}): WorkspaceEntry {
  return { path, name: path.split("/").pop() ?? path, kind, ignored: false, readOnly: false, ...fields };
}

interface FakeEngine {
  readonly client: WorkspaceFilesClient;
  readonly listings: { directory: string; cursor: string | undefined }[];
  readonly searches: string[];
  setDirectory(directory: string, entries: WorkspaceEntry[]): void;
  setSearchResults(matches: WorkspaceFileSearchMatch[]): void;
}

/**
 * An in-memory directory store answering ListWorkspaceDirectory (plus an
 * optional paged root: the first page carries a cursor, the second closes
 * the listing) and SearchWorkspaceFiles.
 */
function fakeEngine(directories: Record<string, WorkspaceEntry[]>, pagedRoot?: [WorkspaceEntry[], WorkspaceEntry[]]): FakeEngine {
  const listings: { directory: string; cursor: string | undefined }[] = [];
  const searches: string[] = [];
  let searchResults: WorkspaceFileSearchMatch[] = [];
  const caller: FilesCaller = {
    call<T>(method: string, params?: unknown): Promise<T> {
      if (method === methods.SEARCH_WORKSPACE_FILES) {
        const request = params as { query: string };
        searches.push(request.query);
        return Promise.resolve(searchResults as T);
      }
      const request = params as { directory: string; cursor?: string };
      listings.push({ directory: request.directory, cursor: request.cursor });
      if (pagedRoot !== undefined && request.directory === "") {
        const page: WorkspaceDirectoryPage =
          request.cursor === undefined
            ? { directory: "", entries: pagedRoot[0], nextCursor: "cursor-2", truncated: true }
            : { directory: "", entries: pagedRoot[1], nextCursor: null, truncated: false };
        return Promise.resolve(page as T);
      }
      const entries = directories[request.directory];
      if (entries === undefined) {
        return Promise.reject(new Error(`no such directory: ${request.directory}`));
      }
      const page: WorkspaceDirectoryPage = { directory: request.directory, entries, nextCursor: null, truncated: false };
      return Promise.resolve(page as T);
    },
  };
  return {
    client: new WorkspaceFilesClient(caller, { chatId: "chat-1" }),
    listings,
    searches,
    setDirectory: (directory, next) => {
      directories[directory] = next;
    },
    setSearchResults: (matches) => {
      searchResults = matches;
    },
  };
}

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

/** The mounted panel plus its model and captured interactions. */
interface Mounted {
  readonly model: WorkspaceTreeModel;
  readonly container: HTMLDivElement;
  readonly openedFiles: string[];
  searchInput(): HTMLInputElement;
  gitFrame(frame: WorkspaceGitStatusFrame): void;
  watchFrame(frame: WorkspaceFileChanges): void;
}

function mountPanel(engine: FakeEngine): Mounted {
  let watchHandler: ((frame: WorkspaceFileChanges) => void) | null = null;
  const model = new WorkspaceTreeModel({
    client: engine.client,
    watch: (handlers) => {
      watchHandler = (frame) => handlers.onItem(frame, { generation: 1 });
      return { cancel: () => {} };
    },
  });
  model.start();
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  const openedFiles: string[] = [];
  let gitHandler: ((frame: WorkspaceGitStatusFrame) => void) | null = null;
  const gitStatus: ComponentProps<typeof FileTreePanel>["gitStatus"] = (handlers) => {
    gitHandler = (frame) => handlers.onItem(frame, { generation: 1 });
    return { method: methods.WATCH_WORKSPACE_GIT_STATUS, cancel: () => {} };
  };
  act(() => {
    root.render(
      createElement(FileTreePanel, {
        model,
        client: engine.client,
        onOpenFile: (path: string) => openedFiles.push(path),
        gitStatus,
      }),
    );
  });
  unmounts.push(() => {
    root.unmount();
    model.dispose();
  });
  return {
    model,
    container,
    openedFiles,
    searchInput: () => container.querySelector<HTMLInputElement>(".files-search input")!,
    gitFrame: (frame) => gitHandler!(frame),
    watchFrame: (frame) => watchHandler!(frame),
  };
}

/** The visible row paths (directories in canonical trailing-slash form). */
function visibleRows(mounted: { readonly model: WorkspaceTreeModel }): string[] {
  const { model } = mounted;
  return model.tree.getVisibleRows(0, model.tree.getVisibleCount()).map((row) => row.path);
}

/** The library's host element (the custom element the React wrapper renders). */
function treeHost(container: HTMLElement): HTMLElement {
  return container.querySelector<HTMLElement>("file-tree-container.files-tree-host")!;
}

/**
 * Click a row inside the shadow DOM — the composed event path out to the
 * panel's host handlers. Used by EXACTLY ONE test (click-to-open): the
 * panel's row wiring has no model-API stand-in, so it needs a real composed
 * click. It relies on the library's internal `data-item-path` row markup —
 * best-effort, non-load-bearing (see the file header).
 */
function clickRow(container: HTMLElement, path: string): void {
  const row = treeHost(container).shadowRoot?.querySelector<HTMLElement>(`[data-item-path="${CSS.escape(path)}"]`);
  expect(row, `row ${path} should be rendered`).not.toBeNull();
  act(() => {
    row!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, composed: true }));
  });
}

describe("FileTreePanel on the trees library", () => {
  it("mounts the library host inside the panel chrome", async () => {
    const engine = fakeEngine({ "": [entry("a.txt", "file"), entry("src", "directory")] });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });

    // The chrome: toolbar with the search field, the show-all toggle, the host.
    expect(mounted.container.querySelector(".files-tree-panel")).not.toBeNull();
    expect(mounted.container.querySelector(".files-header")).not.toBeNull();
    expect(mounted.searchInput().getAttribute("placeholder")).toBe("Search files");
    expect(mounted.container.querySelector(".files-toggle-ignored")).not.toBeNull();
    const host = treeHost(mounted.container);
    expect(host.tagName.toLowerCase()).toBe("file-tree-container");
    // The shadow DOM is the library's; the model is the source of truth.
    expect(host.shadowRoot).not.toBeNull();
    expect(visibleRows(mounted)).toEqual(["src/", "a.txt"]);
  });

  it("expanding a folder lazily issues the directory listing", async () => {
    const engine = fakeEngine({ "": [entry("src", "directory")], src: [entry("src/lib.rs", "file")] });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });
    expect(visibleRows(mounted)).toEqual(["src/"]);
    expect(engine.listings.filter((c) => c.directory === "src")).toHaveLength(0);

    // The library's public item handle — the same toggle a native row click
    // performs — expands the directory; the model's notification re-check
    // turns the expansion into the lazy listing. `toggle` is the directory
    // handle's, so the union narrows first (`"toggle" in item`, the
    // `treeDirectoryHandle` idiom in lib/workspace-tree.ts).
    act(() => {
      const item = mounted.model.tree.getItem("src");
      if (item !== null && "toggle" in item) {
        item.toggle();
      }
    });
    await act(async () => {
      await settle();
    });
    expect(visibleRows(mounted)).toEqual(["src/", "src/lib.rs"]);
    expect(engine.listings.filter((c) => c.directory === "src")).toHaveLength(1);
  });

  it("clicking a file opens it in the right pane (the surface-add flow's trigger)", async () => {
    const engine = fakeEngine({ "": [entry("b.txt", "file"), entry("src", "directory")] });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });

    // The ONE composed-click case (see the file header): click-to-open has
    // no model-API stand-in — the host element's click handler reads the
    // row's data attributes out of the composed event path.
    clickRow(mounted.container, "b.txt");
    expect(mounted.openedFiles).toEqual(["b.txt"]);
  });

  it("the load-more row fetches the next page", async () => {
    const engine = fakeEngine(
      {},
      [
        [entry("f0.txt", "file"), entry("f1.txt", "file")],
        [entry("z.txt", "file")],
      ],
    );
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });
    expect(visibleRows(mounted)).toEqual(["f0.txt", "f1.txt", loadMoreMarkerPath("")]);

    // The marker row's activation — the model's own public helper, the
    // same call the panel's row click makes for a marker path.
    act(() => {
      mounted.model.loadMoreIfMarker(loadMoreMarkerPath(""));
    });
    await act(async () => {
      await settle();
    });
    expect(visibleRows(mounted)).toEqual(["f0.txt", "f1.txt", "z.txt"]);
  });

  it("a watch frame updates the tree and a sequence gap resyncs", async () => {
    const engine = fakeEngine({ "": [entry("src", "directory"), entry("a.txt", "file")] });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });

    // A created file invalidates the root: the re-list picks it up.
    engine.setDirectory("", [entry("src", "directory"), entry("a.txt", "file"), entry("new.txt", "file")]);
    await act(async () => {
      mounted.watchFrame({ sequence: 1, resyncRequired: false, changes: [{ kind: "created", path: "new.txt" }] });
      await settle();
    });
    expect(visibleRows(mounted)).toContain("new.txt");

    // A sequence gap forces the resync: everything stale, reloads land.
    engine.setDirectory("", [entry("src", "directory"), entry("a.txt", "file"), entry("new.txt", "file"), entry("gap.txt", "file")]);
    const listingsBefore = engine.listings.length;
    await act(async () => {
      mounted.watchFrame({ sequence: 9, resyncRequired: false, changes: [] });
      await settle();
    });
    expect(engine.listings.length).toBeGreaterThan(listingsBefore);
    expect(visibleRows(mounted)).toContain("gap.txt");
  });

  it("git status frames map onto the model's status lane", async () => {
    const engine = fakeEngine({ "": [entry("a.txt", "file"), entry("src", "directory")] });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });

    await act(async () => {
      mounted.gitFrame({
        status: {
          checkoutId: "co-1",
          deviceId: "dev-own",
          revision: "rev-1",
          complete: true,
          files: [
            { path: "a.txt", oldPath: null, index: "modified", worktree: "unchanged" },
            { path: "src/new.rs", oldPath: null, index: "untracked", worktree: "untracked" },
          ],
        },
      });
    });
    expect(mounted.model.getSnapshot().gitStatus).toEqual([
      { path: "a.txt", status: "modified" },
      { path: "src/new.rs", status: "untracked" },
    ]);

    // An unavailable status clears — never reads as clean.
    await act(async () => {
      mounted.gitFrame({ status: null });
    });
    expect(mounted.model.getSnapshot().gitStatus).toEqual([]);
  });

  it("the search flow: debounce, RPC, capped-results banner, reveal-and-open", async () => {
    // The matches must be real checkout paths — the reveal re-lists the
    // ancestor directories and selects the row the disk actually carries.
    const srcFiles = Array.from({ length: 200 }, (_, index) => entry(`src/f${index}.rs`, "file"));
    const engine = fakeEngine({
      "": [entry("src", "directory")],
      src: [entry("src/lib.rs", "file"), ...srcFiles],
    });
    const mounted = mountPanel(engine);
    await act(async () => {
      await settle();
    });

    // 200 results: the cap banner reports the first 200.
    const matches: WorkspaceFileSearchMatch[] = Array.from({ length: 200 }, (_, index) => ({
      path: `src/f${index}.rs`,
      name: `f${index}.rs`,
      kind: "file" as const,
      score: 200 - index,
    }));
    engine.setSearchResults(matches);

    const input = mounted.searchInput();
    await act(async () => {
      // React's value tracker swallows plain assignments; write through the
      // native setter so the input event reads as a change.
      const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")!.set!;
      nativeSetter.call(input, "f");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(engine.searches).toEqual([]);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 260));
    });
    // The debounced RPC fired with the query; the banner is up; the search
    // tree host mounted (the main tree host stepped aside).
    expect(engine.searches).toEqual(["f"]);
    expect(mounted.container.querySelector(".files-search-banner")?.textContent).toBe("Showing the first 200 matches");
    expect(mounted.container.querySelectorAll("file-tree-container.files-search-host")).toHaveLength(1);
    expect(mounted.container.querySelectorAll("file-tree-container.files-tree-host:not(.files-search-host)")).toHaveLength(0);
    expect(visibleRows(mounted)).toEqual(["src/"]); // the main tree is untouched

    // Activating a result — the panel's own keyboard surface (the search
    // input's arrow + enter keys, light DOM): the first result (the implied
    // `src/` ancestor) is focused on load, ArrowDown moves to `src/f0.rs`,
    // Enter activates it — reveal in the tree, then open.
    await act(async () => {
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true, cancelable: true }));
    });
    await act(async () => {
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
    });
    await act(async () => {
      await settle();
    });
    expect(mounted.openedFiles).toEqual(["src/f0.rs"]);
    // Search dismissed: the main tree host is back, the revealed directory
    // is expanded, and the activated row is selected.
    expect(mounted.container.querySelectorAll("file-tree-container.files-tree-host:not(.files-search-host)")).toHaveLength(1);
    expect(mounted.container.querySelectorAll("file-tree-container.files-search-host")).toHaveLength(0);
    expect(mounted.container.querySelector(".files-search-banner")).toBeNull();
    expect(visibleRows(mounted)).toContain("src/f0.rs");
    expect(visibleRows(mounted)).toContain("src/lib.rs");
    expect(mounted.model.tree.getSelectedPaths()).toEqual(["src/f0.rs"]);
  });
});
