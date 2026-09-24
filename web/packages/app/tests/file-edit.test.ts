/**
 * Ticket 07 (web-pierre-adoption): the edit-mode bridge (`lib/file-edit.ts`)
 * against the REAL file-document machinery — the store-level suite the
 * ticket's last checkbox requires. The editor events arrive as data (the
 * library's event shapes, with minimal stubs for the detached `editor`
 * field — no real Editor instance is needed at this seam), the document is
 * real, and the caller is the scripted fake-caller from the
 * `file-document.test.ts` idiom. What is pinned:
 *
 * - `onEditChange` → `document.edit`: dirty tracking + autosave scheduling
 *   per settings, and the no-op guard (an event carrying contents the
 *   document already holds must not dirty it).
 * - `onEditComplete` → ALWAYS `'accept'` + the final save through the
 *   machinery — for a capable dirty document, and never for phases the
 *   state machine reserves (externally modified, conflict, deleted on
 *   disk, read-only, a write already in flight).
 * - The per-file state key and the retained-draft reconciliation.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceFileText, WriteWorkspaceFileOutcome } from "@roboco/proto";
import { EditStateManager, Editor, TextDocument } from "@pierre/diffs/edit";
import type { EditorChangeEvent, FileEditCompleteEvent } from "@pierre/diffs/edit";
import { WorkspaceFilesClient, type FilesCaller } from "../src/lib/files-client";
import { FileDocument } from "../src/lib/file-document";
import {
  applyFileEditChange,
  completeFileEdit,
  createRobocoFileEditor,
  fileEditStateKey,
  reconcileFileEditDraft,
} from "../src/lib/file-edit";

// ── The scripted caller (file-document.test.ts's fake-caller idiom) ───────

function textFile(fields: Partial<WorkspaceFileText> = {}): WorkspaceFileText {
  return {
    checkoutId: "checkout-1",
    path: "src/lib.rs",
    text: "fn main() {}",
    contentHash: "hash-1",
    size: 12,
    encoding: "utf8",
    lineEnding: "lf",
    truncated: false,
    ...fields,
  };
}

interface FakeStore {
  client: WorkspaceFilesClient;
  written: { text: string; expectedContentHash: string }[];
  conflictWrite: () => void;
  setDisk: (file: WorkspaceFileText) => void;
}

function fakeStore(file: WorkspaceFileText): FakeStore {
  let disk = file;
  const written: FakeStore["written"] = [];
  const succeed = (): WriteWorkspaceFileOutcome => ({
    status: "written",
    file: { path: disk.path, contentHash: "hash-2", size: 3 },
  });
  let writeOutcome: () => WriteWorkspaceFileOutcome = succeed;
  const caller: FilesCaller = {
    call<T>(method: string, params?: unknown): Promise<T> {
      if (method === "ReadWorkspaceFile") {
        return Promise.resolve(disk as T);
      }
      if (method === "WriteWorkspaceFile") {
        const request = params as { text: string; expectedContentHash: string };
        written.push({ text: request.text, expectedContentHash: request.expectedContentHash });
        return Promise.resolve(writeOutcome() as T);
      }
      return Promise.reject(new Error(`unexpected method ${method}`));
    },
  };
  return {
    client: new WorkspaceFilesClient(caller, { spaceId: "s1" }),
    written,
    conflictWrite: () => {
      writeOutcome = () => ({ status: "conflict", reason: "changed", currentContentHash: "disk-hash" });
    },
    setDisk: (next) => {
      disk = next;
    },
  };
}

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function loaded(store: FakeStore, path = "src/lib.rs"): Promise<FileDocument> {
  const document = new FileDocument(store.client, path);
  document.load();
  await settle();
  return document;
}

// ── Editor events as data ─────────────────────────────────────────────────

const detachedEditor = {} as Editor<"file", undefined, undefined>;

/** A `FileEditChangeHandler` event carrying the editor's new contents. */
function changeEvent(contents: string): EditorChangeEvent<"file", undefined, undefined> {
  return {
    changes: [],
    file: { name: "lib.rs", contents },
    editor: detachedEditor,
  };
}

/** A `FileEditCompleteHandler` event: the final contents + the original. */
function completeEvent(
  finalContents: string,
  originalContents: string,
): FileEditCompleteEvent<undefined, undefined> {
  return {
    file: { name: "lib.rs", contents: finalContents },
    editor: detachedEditor,
    lineAnnotations: undefined,
    originalFile: { name: "lib.rs", contents: originalContents },
    originalLineAnnotations: [],
  };
}

// ── The bridge drives the document machinery ─────────────────────────────

describe("file-edit bridge (onEditChange → document.edit)", () => {
  it("marks the document dirty with the event's contents", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    applyFileEditChange(document, changeEvent("fn main() { println!() }"));
    const snapshot = document.getSnapshot();
    expect(snapshot.text).toBe("fn main() { println!() }");
    expect(snapshot.dirty).toBe(true);
    document.dispose();
  });

  it("schedules autosave per settings: the idle delay writes and cleans", async () => {
    vi.useFakeTimers();
    try {
      const store = fakeStore(textFile());
      const document = new FileDocument(store.client, "src/lib.rs", { autosaveDelayMs: 900 });
      document.load();
      await vi.advanceTimersByTimeAsync(0);
      document.configureAutosave(true, 900);

      applyFileEditChange(document, changeEvent("one"));
      applyFileEditChange(document, changeEvent("two"));
      // Each event reschedules — nothing fires at the halfway mark.
      await vi.advanceTimersByTimeAsync(500);
      expect(store.written).toHaveLength(0);
      await vi.advanceTimersByTimeAsync(400);
      expect(store.written).toEqual([{ text: "two", expectedContentHash: "hash-1" }]);
      expect(document.getSnapshot().dirty).toBe(false);
      document.dispose();
    } finally {
      vi.useRealTimers();
    }
  });

  it("autosave-disabled documents stay dirty until the completion flush", async () => {
    vi.useFakeTimers();
    try {
      const store = fakeStore(textFile());
      const document = new FileDocument(store.client, "src/lib.rs", { autosaveDelayMs: 100 });
      document.load();
      await vi.advanceTimersByTimeAsync(0);
      document.configureAutosave(false, 100);

      applyFileEditChange(document, changeEvent("mine"));
      await vi.advanceTimersByTimeAsync(5_000);
      expect(store.written).toHaveLength(0);
      expect(document.getSnapshot().dirty).toBe(true);
      document.dispose();
    } finally {
      vi.useRealTimers();
    }
  });

  it("skips events carrying contents the document already holds (the external-replacement republish)", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    // A clean reload landed the disk contents in the buffer; the library
    // republishes the replacement through the change stream — re-feeding
    // it would read as a user edit and dirty a clean document.
    store.setDisk(textFile({ text: "fn external() {}", contentHash: "hash-2" }));
    document.reloadFromDisk();
    await settle();
    expect(document.getSnapshot().text).toBe("fn external() {}");

    applyFileEditChange(document, changeEvent("fn external() {}"));
    expect(document.getSnapshot().dirty).toBe(false);
    expect(store.written).toHaveLength(0);
    document.dispose();
  });

  it("never edits a read-only document (truncated, binary)", async () => {
    for (const fields of [
      { truncated: true },
      { encoding: "binary", text: null, readOnlyReason: "binary" },
    ] as const) {
      const store = fakeStore(textFile(fields));
      const document = await loaded(store);
      applyFileEditChange(document, changeEvent("nope"));
      expect(document.getSnapshot().dirty).toBe(false);
      expect(document.getSnapshot().text).not.toBe("nope");
      expect(completeFileEdit(document, completeEvent("nope", "nope"))).toBe("accept");
      expect(store.written).toHaveLength(0);
      document.dispose();
    }
  });
});

describe("file-edit bridge (onEditComplete → accept + final save)", () => {
  it("accepts and runs the final save for a capable dirty document", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    applyFileEditChange(document, changeEvent("mine"));
    expect(completeFileEdit(document, completeEvent("mine", "fn main() {}"))).toBe("accept");
    await settle();
    expect(store.written).toEqual([{ text: "mine", expectedContentHash: "hash-1" }]);
    expect(document.getSnapshot().dirty).toBe(false);
    document.dispose();
  });

  it("accepts a clean document without writing", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    expect(completeFileEdit(document, completeEvent("fn main() {}", "fn main() {}"))).toBe("accept");
    await settle();
    expect(store.written).toHaveLength(0);
    document.dispose();
  });

  it("syncs unseen final contents before accepting (the stream is the source of truth)", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    applyFileEditChange(document, changeEvent("seen"));
    // A completion carrying contents the stream never delivered must not
    // install a value the document does not hold.
    expect(completeFileEdit(document, completeEvent("unseen", "fn main() {}"))).toBe("accept");
    expect(document.getSnapshot().text).toBe("unseen");
    await settle();
    expect(store.written).toEqual([{ text: "unseen", expectedContentHash: "hash-1" }]);
    document.dispose();
  });

  it("accepts without saving while a write is already in flight", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    applyFileEditChange(document, changeEvent("mine"));
    document.save();
    // The tab-close flow: prepareClose already flushed — the completion
    // must not stack a second write behind it.
    expect(completeFileEdit(document, completeEvent("mine", "fn main() {}"))).toBe("accept");
    await settle();
    expect(store.written).toHaveLength(1);
    document.dispose();
  });

  it("accepts without saving during the reserved phases — the banners own the next step", async () => {
    // externallyModified: dirty + disk changed; the buffer is kept.
    const external = fakeStore(textFile());
    const externalDoc = await loaded(external);
    applyFileEditChange(externalDoc, changeEvent("mine"));
    external.setDisk(textFile({ text: "on disk", contentHash: "hash-2" }));
    externalDoc.reconcile();
    await settle();
    expect(externalDoc.getSnapshot().phase).toEqual({ kind: "externallyModified", diskHash: "hash-2" });
    expect(externalDoc.getSnapshot().text).toBe("mine");
    expect(completeFileEdit(externalDoc, completeEvent("mine", "fn main() {}"))).toBe("accept");
    await settle();
    expect(external.written).toHaveLength(0);
    externalDoc.dispose();

    // conflict: a hash-guarded write was refused; the buffer is kept.
    const conflict = fakeStore(textFile());
    const conflictDoc = await loaded(conflict);
    applyFileEditChange(conflictDoc, changeEvent("mine"));
    conflict.conflictWrite();
    conflictDoc.save();
    await settle();
    expect(conflictDoc.getSnapshot().phase.kind).toBe("conflict");
    expect(completeFileEdit(conflictDoc, completeEvent("mine", "fn main() {}"))).toBe("accept");
    await settle();
    expect(conflict.written).toHaveLength(1);
    conflictDoc.dispose();

    // deletedOnDisk: the watch removed the file; the buffer is kept.
    const deleted = fakeStore(textFile());
    const deletedDoc = await loaded(deleted);
    applyFileEditChange(deletedDoc, changeEvent("mine"));
    deletedDoc.markDeleted();
    expect(deletedDoc.getSnapshot().phase.kind).toBe("deletedOnDisk");
    expect(deletedDoc.getSnapshot().text).toBe("mine");
    expect(completeFileEdit(deletedDoc, completeEvent("mine", "fn main() {}"))).toBe("accept");
    await settle();
    expect(deleted.written).toHaveLength(0);
    deletedDoc.dispose();
  });

  it("an externally modified dirty document keeps its buffer and reloads cleanly through the machinery", async () => {
    const store = fakeStore(textFile());
    const document = await loaded(store);
    applyFileEditChange(document, changeEvent("mine"));
    store.setDisk(textFile({ text: "on disk", contentHash: "hash-2" }));
    document.reconcile();
    await settle();
    expect(document.getSnapshot().phase).toEqual({ kind: "externallyModified", diskHash: "hash-2" });
    expect(document.getSnapshot().text).toBe("mine");

    // Reload from Disk: the fresh read replaces the buffer, the session
    // can start editing again from the new contents.
    document.reloadFromDisk();
    await settle();
    const snapshot = document.getSnapshot();
    expect(snapshot.phase).toEqual({ kind: "ready" });
    expect(snapshot.text).toBe("on disk");
    expect(snapshot.dirty).toBe(false);
    applyFileEditChange(document, changeEvent("on disk, edited"));
    expect(document.getSnapshot().dirty).toBe(true);
    document.dispose();
  });
});

describe("file-edit bridge (edit state keys)", () => {
  beforeEach(() => {
    EditStateManager.clearAll();
  });
  afterEach(() => {
    EditStateManager.clearAll();
  });

  it("keys the retained draft by the document path", () => {
    expect(fileEditStateKey("src/lib.rs")).toBe("src/lib.rs");
    expect(fileEditStateKey("src/lib.rs")).toBe(fileEditStateKey("src/lib.rs"));
  });

  it("keeps a retained draft that matches the mounting buffer, drops a divergent one", () => {
    const key = "src/lib.rs";
    const owner = new Editor("file");
    const draft = new TextDocument<"file", undefined>("lib.rs", "draft", "rust");
    EditStateManager.activate("file", key, owner, {
      type: "file",
      document: draft,
      fileInfo: { name: "lib.rs", lang: "rust" },
    });
    EditStateManager.releaseFile(key, owner);
    expect(EditStateManager.get("file", key)?.document).toBe(draft);

    // Matching buffer: the draft resumes (undo history, selection, caret).
    reconcileFileEditDraft(key, "draft");
    expect(EditStateManager.get("file", key)?.document).toBe(draft);

    // Divergent buffer (a reload moved it): the draft is dropped.
    reconcileFileEditDraft(key, "fn external() {}");
    expect(EditStateManager.get("file", key)).toBeUndefined();
  });

  it("the shared factory constructs editors of the requested type", () => {
    const editor = createRobocoFileEditor("file", {}, "src/lib.rs");
    expect(editor.type).toBe("file");
    expect(editor.getText()).toBe("");
    editor.cleanUp("discard");
  });
});
