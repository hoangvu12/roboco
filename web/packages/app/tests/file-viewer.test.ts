// @vitest-environment jsdom

/**
 * Ticket 05 (web-pierre-adoption): the right pane's File surface renders its
 * code body through the Pierre diffs library's `File`. The real
 * `FileSurface` mounts (no JSX, per-file jsdom pragma, the base-tooltip
 * idiom) over a scripted `WorkspaceFilesClient` double — the read outcome
 * and the workspace watch drive the file-document state machine exactly as
 * the engine would. Assertions stay OUTSIDE the shadow DOM — our chrome
 * (the breadcrumb toolbar, the truncation banner, the save-status pill) and
 * the library host elements the virtualizer carries (`diffs-container`, a
 * light-DOM custom element our classes own). The edit-mode wiring (ticket
 * 07) has its own mounted suite: `file-viewer-edit.test.ts`.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceFileChanges, WorkspaceFileText } from "@roboco/proto";
import { EditProvider } from "@pierre/diffs/react";
import { FileSurface } from "../src/components/files/file-viewer";
import { createRobocoFileEditor } from "../src/lib/file-edit";
import { rightPaneStore } from "../src/state/right-pane";
import { fileDocuments } from "../src/state/file-documents";
import type { FileDocument } from "../src/lib/file-document";

// ── Controllable doubles ──────────────────────────────────────────────────

const ENGINE_KEY = "http://engine.local";
const RAW_DEVICE_ID = "dev-own";
const CHAT_ID = "chat-file-viewer";
const PATH = "src/main.rs";

const h = vi.hoisted(() => {
  if (typeof Image === "function" && Image.prototype.decode === undefined) {
    (Image.prototype as { decode: () => Promise<void> }).decode = () => Promise.resolve();
  }
  /** The scripted read outcome the fake `ReadWorkspaceFile` resolves. */
  let disk: object | null = null;
  /** The scripted watch handlers, captured when the tree subscribes. */
  let watchHandlers: {
    onItem: (frame: object) => void;
    onEnd: (error?: unknown) => void;
  } | null = null;
  return {
    get disk() {
      return disk;
    },
    set disk(next) {
      disk = next;
    },
    get watchHandlers() {
      return watchHandlers;
    },
    set watchHandlers(next) {
      watchHandlers = next;
    },
  };
});

const fakeClient = {
  status: { state: "connected", info: { deviceId: RAW_DEVICE_ID, workspaceScope: "local" }, generation: 1 },
  onStatus(): () => void {
    return () => {};
  },
  call<T>(method: string, _params?: unknown): Promise<T> {
    if (method === "ReadWorkspaceFile") {
      return h.disk === null ? Promise.reject(new Error("no file scripted")) : Promise.resolve(h.disk as T);
    }
    if (method === "ListWorkspaceDirectory") {
      return Promise.resolve({ directory: "", entries: [], nextCursor: null, truncated: false } as T);
    }
    return Promise.reject(new Error(`unexpected method ${method}`));
  },
  watch(
    method: string,
    _params: unknown,
    handlers: { onItem: (frame: object) => void; onEnd: (error?: unknown) => void },
  ): { method: string; cancel: () => void } {
    if (method === "WatchWorkspaceFiles") {
      h.watchHandlers = handlers;
    }
    return { method, cancel: () => {} };
  },
};

const session = {
  engine: { baseUrl: ENGINE_KEY, credential: "cred", label: "Engine", deviceId: RAW_DEVICE_ID },
  client: fakeClient,
};

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => session,
  useEngineSessions: () => new Map(),
  useEngineRetry: () => () => {},
}));

// ── jsdom gaps the mounted body hits (base-tooltip.test.ts's set, plus the
// library's Virtualizer, which constructs an IntersectionObserver) ────────

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
  globalThis.IntersectionObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
    takeRecords(): object[] {
      return [];
    }
  } as unknown as typeof IntersectionObserver;
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

const unmounts: Array<() => void> = [];
const opened: Array<{ chatId: string; surfaceId: string }> = [];

beforeEach(() => {
  h.disk = null;
  h.watchHandlers = null;
});

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  for (const { chatId, surfaceId } of opened.splice(0)) {
    rightPaneStore.completeFileClose(chatId, { kind: "file", id: surfaceId });
  }
  document.body.replaceChildren();
});

// ── Fixtures ──────────────────────────────────────────────────────────────

function textFile(fields: Partial<WorkspaceFileText> = {}): WorkspaceFileText {
  return {
    checkoutId: "co-1",
    path: PATH,
    text: 'fn main() {\n    println!("hi");\n}\n',
    contentHash: "hash-1",
    size: 38,
    encoding: "utf8",
    lineEnding: "lf",
    truncated: false,
    readOnlyReason: null,
    ...fields,
  };
}

interface Mounted {
  readonly container: HTMLDivElement;
  readonly root: Root;
  readonly surfaceId: string;
  readonly document: FileDocument;
  unmount(): void;
}

/** Flush the library's rAF render queue + the async read/highlighter. */
async function settle(ms = 160): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
}

/** Open the path as a file surface and mount it — the tree's tab click path,
 * wrapped in the edit provider the app shell mounts above the pane. */
async function mountViewer(): Promise<Mounted> {
  rightPaneStore.addFileSurface(CHAT_ID, PATH);
  const pane = rightPaneStore.stateFor(CHAT_ID);
  const tab = pane.tabs.find((candidate) => candidate.kind === "file");
  if (tab === undefined || tab.kind !== "file") {
    throw new Error("file surface did not open");
  }
  const surfaceId = tab.id;
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(
      createElement(
        EditProvider,
        { createEditor: createRobocoFileEditor },
        createElement(FileSurface, { chatId: CHAT_ID, surfaceId }),
      ),
    );
  });
  await settle();
  const document_ = fileDocuments.documentFor(surfaceId);
  if (document_ === null) {
    throw new Error("file document did not attach");
  }
  const unmount = () => {
    act(() => {
      root.unmount();
    });
  };
  unmounts.push(unmount);
  opened.push({ chatId: CHAT_ID, surfaceId });
  return { container, root, surfaceId, document: document_, unmount };
}

/** The tree's removed-event frame shape. */
function removedFrame(path: string): WorkspaceFileChanges {
  return { sequence: 2, resyncRequired: false, changes: [{ kind: "removed", path }] };
}

// ── The viewer renders through the library ────────────────────────────────

describe("FileSurface renders the read-only code view through the Pierre diffs library", () => {
  it("mounts the library's virtualized host inside the viewer chrome when a file loads", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();

    // Our chrome: the breadcrumb toolbar. The edit-deferral notice is gone
    // (ticket 07 restored editing); the read path itself is unchanged.
    expect(mounted.container.querySelector(".files-breadcrumb")).not.toBeNull();
    expect(mounted.container.querySelector(".files-readonly-note")).toBeNull();

    // The library's scroll container (the Virtualizer host)…
    const host = mounted.container.querySelector<HTMLElement>(".files-code-host");
    expect(host).not.toBeNull();
    // …carrying the library's file element with our token-mapping class.
    expect(host!.querySelector("diffs-container.files-code-file")).not.toBeNull();
  });

  it("re-renders around the same library element — the cache key keeps the file identity stable", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();
    const fileElement = mounted.container.querySelector<HTMLElement>("diffs-container.files-code-file");
    expect(fileElement).not.toBeNull();

    // A pane-store write re-renders the surface (an unrelated tab opens);
    // the document and the library host stay mounted — the content-hash
    // cache key means the settled file never re-tokenizes or remounts.
    act(() => {
      rightPaneStore.setActive(CHAT_ID, { kind: "picker" });
    });
    await settle();
    expect(mounted.container.querySelector("diffs-container.files-code-file")).toBe(fileElement);
  });

  it("surfaces the read-only reason instead of a code view for binary files", async () => {
    h.disk = textFile({ text: null, readOnlyReason: "binary", contentHash: null, size: 4096 });
    const mounted = await mountViewer();

    expect(mounted.container.textContent).toContain("Binary files cannot be previewed.");
    expect(mounted.container.querySelector(".files-code-host")).toBeNull();
  });

  it("keeps the truncation banner for large truncated previews (read-only, never editable)", async () => {
    h.disk = textFile({ truncated: true, readOnlyReason: null });
    const mounted = await mountViewer();

    const banner = mounted.container.querySelector<HTMLElement>(".files-truncated-banner");
    expect(banner).not.toBeNull();
    expect(banner!.textContent).toContain("Large file preview is truncated and read-only.");
    // The truncated text still renders through the library.
    expect(mounted.container.querySelector("diffs-container.files-code-file")).not.toBeNull();
  });

  it("drives the external-change pill from the watch's removed event", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();
    expect(mounted.container.querySelector(".files-save-pill")).toBeNull();

    // The workspace watch reports the open file removed on disk — the
    // file-document state machine owns the banner, the viewer renders it.
    act(() => {
      h.watchHandlers?.onItem(removedFrame(PATH));
    });
    await settle();
    const pill = mounted.container.querySelector<HTMLElement>(".files-save-pill");
    expect(pill).not.toBeNull();
    expect(pill!.textContent).toContain("Deleted on disk");
  });
});
