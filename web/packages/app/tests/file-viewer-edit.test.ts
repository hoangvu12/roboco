// @vitest-environment jsdom

/**
 * Ticket 07 (web-pierre-adoption): the file viewer's code body is EDITABLE
 * through the Pierre diffs library's edit mode. The real `FileSurface`
 * mounts (no JSX, per-file jsdom pragma, the base-tooltip idiom + ticket
 * 05's IntersectionObserver stub) inside a real `EditProvider` — the same
 * shape the app shell mounts above the right pane — over a scripted
 * `WorkspaceFilesClient` double that supports reads AND writes. The
 * editor is driven through its PUBLIC API (`applyEdits` — the same
 * document-change pipeline a keystroke takes), so assertions never reach
 * inside the shadow DOM: hosts, chrome, store state, and the library's own
 * public session manager (`EditStateManager`) carry the proof.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceFileChanges, WorkspaceFileText } from "@roboco/proto";
import { EditProvider } from "@pierre/diffs/react";
import { EditStateManager, Editor, type EditorFactory } from "@pierre/diffs/edit";
import { FileSurface } from "../src/components/files/file-viewer";
import { createRobocoFileEditor } from "../src/lib/file-edit";
import { rightPaneStore } from "../src/state/right-pane";
import { fileDocuments } from "../src/state/file-documents";
import { uiSettings } from "../src/state/ui-settings";
import type { FileDocument } from "../src/lib/file-document";

// ── Controllable doubles ──────────────────────────────────────────────────

const ENGINE_KEY = "http://engine.local";
const RAW_DEVICE_ID = "dev-own";
const CHAT_ID = "chat-file-viewer-edit";
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
  /** The write requests the fake `WriteWorkspaceFile` received. */
  const written: { text: string; expectedContentHash: string }[] = [];
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
    get written() {
      return written;
    },
  };
});

const fakeClient = {
  status: { state: "connected", info: { deviceId: RAW_DEVICE_ID, workspaceScope: "local" }, generation: 1 },
  onStatus(): () => void {
    return () => {};
  },
  call<T>(method: string, params?: unknown): Promise<T> {
    if (method === "ReadWorkspaceFile") {
      return h.disk === null ? Promise.reject(new Error("no file scripted")) : Promise.resolve(h.disk as T);
    }
    if (method === "WriteWorkspaceFile") {
      const request = params as { text: string; expectedContentHash: string };
      h.written.push({ text: request.text, expectedContentHash: request.expectedContentHash });
      return Promise.resolve({
        status: "written",
        file: { path: PATH, contentHash: "hash-2", size: 3 },
      } as T);
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

// ── jsdom gaps the mounted editor hits: the base-tooltip set, ticket 05's
// IntersectionObserver (the Virtualizer), and the editor's canvas text
// metrics (a 2d context jsdom does not implement) ─────────────────────────

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
  // The editor measures text with a canvas 2d context; jsdom has none, and
  // the library's Metrics.init throws without it (swallowed as a render
  // error, but the editor never attaches). A fixed-width measureText makes
  // the editor fully functional in jsdom.
  (HTMLCanvasElement.prototype as unknown as { getContext: () => unknown }).getContext = function getContext() {
    return {
      font: "",
      measureText: (text: string) => ({ width: text.length * 6.6 }),
    };
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

const unmounts: Array<() => void> = [];
const opened: Array<{ chatId: string; surfaceId: string }> = [];

beforeEach(() => {
  h.disk = null;
  h.watchHandlers = null;
  h.written.length = 0;
  EditStateManager.clearAll();
});

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  for (const { chatId, surfaceId } of opened.splice(0)) {
    rightPaneStore.completeFileClose(chatId, { kind: "file", id: surfaceId });
  }
  EditStateManager.clearAll();
  uiSettings.updateImmediate({ filesAutosaveEnabled: false, filesAutosaveDelayMs: 900 });
  document.body.replaceChildren();
});

// ── Fixtures ──────────────────────────────────────────────────────────────

const FILE_TEXT = 'fn main() {\n    println!("hi");\n}\n';

function textFile(fields: Partial<WorkspaceFileText> = {}): WorkspaceFileText {
  return {
    checkoutId: "co-1",
    path: PATH,
    text: FILE_TEXT,
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
  /** The editor the provider's factory created for the mounted surface. */
  readonly editor: Editor<"file">;
  unmount(): void;
}

/** Flush the library's rAF render queue + the async read/highlighter. */
async function settle(ms = 160): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
}

/** The capturing factory: the real bridge factory, with the created editor. */
let capturedEditor: Editor<"file"> | null = null;
const capturingFactory: EditorFactory<unknown, unknown> = (editorType, options, editStateKey) => {
  const editor = createRobocoFileEditor(editorType, options, editStateKey);
  if (editorType === "file") {
    capturedEditor = editor as Editor<"file">;
  }
  return editor;
};

/**
 * Open the path as a file surface and mount it inside a real EditProvider —
 * the app shell's shape: the provider sits ABOVE the surface, the factory
 * is shared context, and the surface's `File` creates its editor through it.
 */
async function mountViewer(): Promise<Mounted> {
  capturedEditor = null;
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
        { createEditor: capturingFactory },
        createElement(FileSurface, { chatId: CHAT_ID, surfaceId }),
      ),
    );
  });
  await settle();
  const document_ = fileDocuments.documentFor(surfaceId);
  if (document_ === null) {
    throw new Error("file document did not attach");
  }
  if (capturedEditor === null) {
    throw new Error("the edit provider did not create an editor");
  }
  const unmount = () => {
    act(() => {
      root.unmount();
    });
  };
  unmounts.push(unmount);
  opened.push({ chatId: CHAT_ID, surfaceId });
  return { container, root, surfaceId, document: document_, editor: capturedEditor, unmount };
}

/** Type into the mounted editor through its public API. */
function typeInEditor(editor: Editor<"file">, text: string): void {
  act(() => {
    editor.applyEdits([{ range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } }, newText: text }]);
  });
}

/**
 * The editor the provider's factory created most recently — a reload
 * transiently unmounts the code body (the loading arm) and the remount
 * creates a fresh editor through the same factory, so the surface's
 * current editor is not necessarily the one captured at mount.
 */
function currentEditor(): Editor<"file"> {
  if (capturedEditor === null) {
    throw new Error("no editor was created");
  }
  return capturedEditor;
}

/** The watch's modified-event frame shape. */
function modifiedFrame(path: string): WorkspaceFileChanges {
  return { sequence: 2, resyncRequired: false, changes: [{ kind: "modified", path }] };
}

// ── The edit bridge, mounted ──────────────────────────────────────────────

describe("FileSurface mounts the library's edit mode through the EditProvider", () => {
  it("attaches an edit session for an editable document (the provider above supplies the factory)", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();

    // The library host mounts (the Virtualizer's file element)…
    expect(mounted.container.querySelector("diffs-container.files-code-file")).not.toBeNull();
    // …and the provider's factory — the bridge's own, not a test double —
    // created the surface's editor through the context above it.
    expect(mounted.editor).toBeInstanceOf(Editor);
    // The session is live for the per-file key (the document path) and
    // holds the file's contents.
    const session = EditStateManager.get("file", PATH);
    expect(session?.document).not.toBeUndefined();
    expect(session?.document?.getText()).toBe(FILE_TEXT);
    expect(mounted.editor.getText()).toBe(FILE_TEXT);
  });

  it("streams editor changes into the document: dirty now, the completion flush writes on session end", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();

    // Autosave is off by default (the web default) — a change streams in
    // and the document goes dirty with the editor's contents.
    typeInEditor(mounted.editor, "// typed\n");
    const snapshot = mounted.document.getSnapshot();
    expect(snapshot.dirty).toBe(true);
    expect(snapshot.text).toBe(`// typed\n${FILE_TEXT}`);
    // Nothing wrote yet: the session is still live.
    expect(h.written).toHaveLength(0);

    // Session end (unmount) → the completion handler accepts and runs the
    // final save through the document machinery.
    mounted.unmount();
    await settle();
    expect(h.written).toEqual([{ text: `// typed\n${FILE_TEXT}`, expectedContentHash: "hash-1" }]);
  });

  it("autosaves follow the settings end-to-end: idle writes and cleans", async () => {
    h.disk = textFile();
    uiSettings.updateImmediate({ filesAutosaveEnabled: true, filesAutosaveDelayMs: 60 });
    const mounted = await mountViewer();

    typeInEditor(mounted.editor, "// typed\n");
    await settle(300);
    expect(h.written).toEqual([{ text: `// typed\n${FILE_TEXT}`, expectedContentHash: "hash-1" }]);
    expect(mounted.document.getSnapshot().dirty).toBe(false);
  });

  it("never attaches an edit session for a truncated (read-only) document", async () => {
    h.disk = textFile({ truncated: true, readOnlyReason: null });
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    capturedEditor = null;
    rightPaneStore.addFileSurface(CHAT_ID, PATH);
    const pane = rightPaneStore.stateFor(CHAT_ID);
    const tab = pane.tabs.find((candidate) => candidate.kind === "file");
    if (tab === undefined || tab.kind !== "file") {
      throw new Error("file surface did not open");
    }
    const surfaceId = tab.id;
    await act(async () => {
      root.render(
        createElement(
          EditProvider,
          { createEditor: capturingFactory },
          createElement(FileSurface, { chatId: CHAT_ID, surfaceId }),
        ),
      );
    });
    await settle();
    unmounts.push(() => {
      act(() => {
        root.unmount();
      });
    });
    opened.push({ chatId: CHAT_ID, surfaceId });

    // The read-only body still renders through the library…
    expect(container.querySelector("diffs-container.files-code-file")).not.toBeNull();
    expect(container.querySelector(".files-truncated-banner")).not.toBeNull();
    // …but no editor was created and no session exists for the path.
    expect(capturedEditor).toBeNull();
    expect(EditStateManager.get("file", PATH)).toBeUndefined();
  });

  it("retains the session across unmount and resumes it on remount (the same draft document)", async () => {
    h.disk = textFile();
    const first = await mountViewer();
    typeInEditor(first.editor, "// typed\n");
    const draft = EditStateManager.get("file", PATH)?.document;
    expect(draft?.getText()).toBe(`// typed\n${FILE_TEXT}`);

    // Leaving the tab: the completion flushes the save, the session's
    // draft (document + undo history + editor state) goes dormant under
    // the path key — the document's buffer survives in the registry.
    first.unmount();
    await settle();
    expect(h.written).toHaveLength(1);
    const dormant = EditStateManager.get("file", PATH)?.document;
    expect(dormant).toBe(draft);

    // Re-entering: the surface re-mounts (the registry reuses the same
    // document, whose buffer holds the written text), and the session
    // RESUMES — the same document object, not a fresh one.
    const second = await mountViewer();
    const resumed = EditStateManager.get("file", PATH)?.document;
    expect(resumed).toBe(draft);
    expect(second.document.getSnapshot().text).toBe(`// typed\n${FILE_TEXT}`);
    expect(second.editor.getText()).toBe(`// typed\n${FILE_TEXT}`);
    // The resumed draft keeps its undo history — the typed edit is undoable.
    expect(second.editor.canUndo).toBe(true);
    act(() => {
      second.editor.undo();
    });
    expect(second.document.getSnapshot().text).toBe(FILE_TEXT);
  });

  it("an external change while dirty keeps the buffer and shows the banner", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();
    typeInEditor(mounted.editor, "// typed\n");
    expect(mounted.document.getSnapshot().dirty).toBe(true);

    // The workspace watch reports the file changed on disk with new
    // contents — reconcile keeps the dirty buffer (the state machine's
    // externally-modified phase) and the banner renders.
    h.disk = textFile({ text: "fn external() {}\n", contentHash: "hash-9" });
    act(() => {
      h.watchHandlers?.onItem(modifiedFrame(PATH));
    });
    await settle();

    const snapshot = mounted.document.getSnapshot();
    expect(snapshot.phase).toEqual({ kind: "externallyModified", diskHash: "hash-9" });
    expect(snapshot.text).toBe(`// typed\n${FILE_TEXT}`);
    expect(snapshot.dirty).toBe(true);
    const banner = mounted.container.querySelector<HTMLElement>(".files-banner-external");
    expect(banner).not.toBeNull();
    expect(banner!.textContent).toContain("changed outside Roboco");
    // The editor session still holds the buffer — nothing was replaced
    // underneath the user's draft.
    expect(mounted.editor.getText()).toBe(`// typed\n${FILE_TEXT}`);
  });

  it("Reload from Disk restores the editor's contents and the edit state continues", async () => {
    h.disk = textFile();
    const mounted = await mountViewer();
    typeInEditor(mounted.editor, "// typed\n");
    h.disk = textFile({ text: "fn external() {}\n", contentHash: "hash-9" });
    act(() => {
      h.watchHandlers?.onItem(modifiedFrame(PATH));
    });
    await settle();
    expect(mounted.document.getSnapshot().phase.kind).toBe("externallyModified");

    // The banner's reload affordance on a dirty buffer asks first —
    // "Discard unsaved changes?" — then Discard & Reload re-reads.
    const banner = mounted.container.querySelector<HTMLElement>(".files-banner-external");
    expect(banner).not.toBeNull();
    act(() => {
      banner!.querySelector<HTMLButtonElement>(".files-banner-action:not(.files-banner-action-muted)")!.click();
    });
    await settle();
    expect(banner!.textContent).toContain("Discard unsaved changes?");
    act(() => {
      banner!.querySelector<HTMLButtonElement>(".files-banner-action:not(.files-banner-action-muted)")!.click();
    });
    await settle();

    // The fresh read replaced the buffer (clean, new contents). The reload
    // passes through the loading arm, so the code body remounts and a fresh
    // editor attaches — its session starts from the reloaded contents (the
    // bridge drops the discarded draft's retained state at the mount, since
    // it no longer matches the buffer).
    const snapshot = mounted.document.getSnapshot();
    expect(snapshot.phase).toEqual({ kind: "ready" });
    expect(snapshot.text).toBe("fn external() {}\n");
    expect(snapshot.dirty).toBe(false);
    expect(EditStateManager.get("file", PATH)?.document?.getText()).toBe("fn external() {}\n");
    expect(currentEditor().getText()).toBe("fn external() {}\n");

    // The session continues over the reloaded contents.
    const editor = currentEditor();
    typeInEditor(editor, "// again\n");
    const next = mounted.document.getSnapshot();
    expect(next.dirty).toBe(true);
    expect(next.text).toBe("// again\nfn external() {}\n");
  });
});
