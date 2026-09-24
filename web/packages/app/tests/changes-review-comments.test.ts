// @vitest-environment jsdom

/**
 * Ticket 03 (web-pierre-adoption): the review-comment affordances ride the
 * diff library — the built-in "+" gutter utility opens a draft at the
 * clicked (path, side, line), and the staged set + open draft render inline
 * as annotation cards through the library's renderAnnotation slot. The real
 * `ChangesSurface` mounts (no JSX, per-file jsdom pragma, the base-tooltip
 * idiom) over the scripted watch from the library suite, and every mutation
 * flows through the REAL review comment store.
 *
 * The gutter-utility click itself cannot be exercised in jsdom — the built-in
 * button lives in the shadow DOM and only materializes on a real hover, which
 * jsdom's pointer model cannot produce — so the suite drives the mounted
 * option's handler directly (the library's own `window.__INSTANCE` debug
 * handle): the option IS our handler, closed over the live chat id, so the
 * drive covers everything we own (anchor resolution, pre-rename oldPath, the
 * split gate, the store flow). The pointer plumbing from the button to the
 * option is upstream's contract.
 *
 * Assertions stay in light DOM / store state: the cards and draft render as
 * light-DOM children of the item host (the shadow tree slots them in), so
 * `.comment-card` / `.comment-draft` are observable without reaching inside
 * the shadow DOM.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Chat, CheckoutDiff } from "@roboco/proto";
import { encodeScopedId } from "@roboco/engine-client";
import { ChangesSurface } from "../src/routes/changes-page";
import { changesSurfaceStore } from "../src/state/changes-surface";
import { reviewCommentStore, __resetReviewCommentsForTests } from "../src/state/review-comments";
import { __resetChangesDiffForTests, parseDiffFiles } from "../src/lib/changes-diff";

// ── Controllable doubles (the library suite's set) ─────────────────────────

const ENGINE_KEY = "http://engine.local";
const RAW_DEVICE_ID = "dev-own";
const CHAT_CWD = "C:/repo/probe";

const h = vi.hoisted(() => {
  if (typeof Image === "function" && Image.prototype.decode === undefined) {
    (Image.prototype as { decode: () => Promise<void> }).decode = () => Promise.resolve();
  }
  /** Chat rows the merged fleet snapshot serves (scoped ids, set per test). */
  const chats: object[] = [];
  /** Frames the scripted watch delivers the moment it subscribes. */
  const frames: object[] = [];
  return { chats, frames };
});

const fakeClient = {
  status: { state: "connected", info: { deviceId: RAW_DEVICE_ID, workspaceScope: "local" }, generation: 1 },
  onStatus(): () => void {
    return () => {};
  },
  call<T>(method: string, _params?: unknown): Promise<T> {
    if (method === "ListBranches") {
      return Promise.resolve(["main"] as T);
    }
    return Promise.resolve([] as T);
  },
  watch(
    method: string,
    _params: unknown,
    handlers: { onItem: (item: object, context: { generation: number }) => void },
  ): { method: string; cancel: () => void } {
    if (method === "WatchCheckoutDiffs") {
      for (const frame of [...h.frames]) {
        handlers.onItem(frame, { generation: 1 });
      }
    }
    return { method, cancel: () => {} };
  },
};

const session = {
  engine: { baseUrl: ENGINE_KEY, credential: "cred", label: "Engine", deviceId: RAW_DEVICE_ID },
  client: fakeClient,
};

vi.mock("../src/state/fleet", () => ({
  useFleet: () => ({ engines: [{ baseUrl: ENGINE_KEY, label: "Engine" }], active: ENGINE_KEY, configurationError: null }),
  useFleetSnapshot: () => ({
    generation: 1,
    capabilities: [],
    chats: { rows: h.chats, loaded: true, error: null },
    spaces: { rows: [], loaded: true, error: null },
    devices: { rows: [], loaded: false, error: null },
    statuses: { rows: [], loaded: false, error: null },
    connectivity: { value: null, loaded: false, error: null },
  }),
}));

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => session,
  useEngineSessions: () => new Map(),
  useEngineRetry: () => () => {},
}));

// ── jsdom gaps the mounted body hits (base-tooltip.test.ts's set) ─────────

beforeAll(async () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  // The diff list arrives on its own chunk (the finding-4a lazy boundary);
  // pre-warm it so the mounts below resolve it on the microtask — the
  // production shape (the chunk streams in parallel, before the first
  // diff body) — instead of paying the cold module load inside a settle
  // window (racy under a loaded parallel run).
  await import("../src/routes/changes-diff-list");
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
const mounted: Array<{ chatId: string; surfaceId: string }> = [];

beforeEach(() => {
  h.chats.length = 0;
  h.frames.length = 0;
  __resetChangesDiffForTests();
});

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  for (const { chatId, surfaceId } of mounted.splice(0)) {
    changesSurfaceStore.dispose(chatId, surfaceId);
  }
  __resetReviewCommentsForTests();
  document.body.replaceChildren();
});

// ── Fixtures ──────────────────────────────────────────────────────────────

function chatRow(overrides: Partial<Chat>): Chat {
  return {
    id: encodeScopedId(ENGINE_KEY, "chat-1"),
    deviceId: encodeScopedId(ENGINE_KEY, RAW_DEVICE_ID),
    title: "Chat",
    archived: false,
    cwd: CHAT_CWD,
    branch: "feature",
    checkoutId: null,
    sourceContext: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function frame(cwd: string, patch: string, checksum: string): CheckoutDiff {
  return {
    checkoutId: "co-raw",
    deviceId: RAW_DEVICE_ID,
    cwd,
    patch,
    files: [],
    additions: 2,
    deletions: 1,
    truncated: false,
    checksum,
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

const WORKING_PATCH = `diff --git a/src/lib b/src/lib
index 3e2f1a..9b4c2d 100644
--- a/src/lib
+++ b/src/lib
@@ -1,3 +1,4 @@
 context
-removed
+added
+another
 context
`;

/** old.txt → new.txt with a content change: deletions side cites the pre-rename path. */
const RENAME_PATCH = `diff --git a/old.txt b/new.txt
similarity index 90%
rename from old.txt
rename to new.txt
index 111111..222222 100644
--- a/old.txt
+++ b/new.txt
@@ -1,2 +1,2 @@
 keep
-gone
+here
`;

interface Mounted {
  readonly container: HTMLDivElement;
  readonly root: Root;
  unmount(): void;
}

/**
 * Poll a light-DOM condition to true, flushing React/the library's render
 * queues along the way — a fixed sleep alone can undershoot on a loaded
 * machine (the library's render is rAF-scheduled), so every
 * render-observable outcome waits adaptively instead.
 */
async function waitFor(predicate: () => boolean, ms = 3000): Promise<void> {
  const deadline = Date.now() + ms;
  for (;;) {
    if (predicate()) {
      return;
    }
    if (Date.now() >= deadline) {
      return;
    }
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 25));
    });
  }
}

/**
 * Mount the full surface (the store-bound wrapper the registry renders) —
 * the store writes below must flow back through its props.
 */
async function mountSurface(chatId: string, surfaceId: string): Promise<Mounted> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(createElement(ChangesSurface, { chatId, surfaceId }));
  });
  const result: Mounted = {
    container,
    root,
    unmount: () => {
      act(() => {
        root.unmount();
      });
    },
  };
  unmounts.push(result.unmount);
  mounted.push({ chatId, surfaceId });
  return result;
}

// ── The adder drive (the library's `window.__INSTANCE` debug handle) ───────

interface GutterUtilityClick {
  (range: { start: number; end: number; side?: "deletions" | "additions"; endSide?: "deletions" | "additions" }, context: unknown): void;
}

/**
 * The mounted CodeView's `onGutterUtilityClick` option — our handler, the
 * same function the library would call on a real "+" click.
 */
function gutterUtilityClick(): GutterUtilityClick | undefined {
  const instance = (window as unknown as { __INSTANCE?: { options?: { onGutterUtilityClick?: GutterUtilityClick } } }).__INSTANCE;
  return instance?.options?.onGutterUtilityClick;
}

/** Drive the adder on the clicked side/line of the mounted item's file. */
async function clickAdder(
  fileDiff: { name: string; type: string },
  range: { start: number; end?: number; side?: "deletions" | "additions"; endSide?: "deletions" | "additions" },
): Promise<void> {
  const handler = gutterUtilityClick();
  expect(handler).toBeTypeOf("function");
  await act(async () => {
    handler!({ end: range.end ?? range.start, ...range }, { item: { id: fileDiff.name, type: "diff", fileDiff, collapsed: false } });
  });
}

/** Type into the draft's textarea through the native value setter. */
async function typeDraftBody(body: string): Promise<void> {
  const textarea = document.querySelector<HTMLTextAreaElement>(".comment-draft-input");
  expect(textarea).not.toBeNull();
  await act(async () => {
    const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value")!.set!;
    nativeSetter.call(textarea!, body);
    textarea!.dispatchEvent(new Event("input", { bubbles: true }));
  });
}

function clickButton(selector: string): void {
  const button = document.querySelector<HTMLButtonElement>(selector);
  expect(button).not.toBeNull();
  act(() => {
    button!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  });
}

// ── The suites ────────────────────────────────────────────────────────────

describe("ChangesSurface review comments through the library", () => {
  it("opens a draft from the adder's click, commits it through the store, and renders the card", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-adder");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-review");
    await waitFor(() => gutterUtilityClick() !== undefined);

    // The library's "+" click on the additions side, line 2 ("+added"): the
    // draft opens at (path, new side, 2) with no old path (no rename).
    const files = parseDiffFiles({ checkoutId: "co-raw", checksum: "sum-1", patch: WORKING_PATCH }, "workingTree", null);
    await clickAdder(files[0]!, { start: 2, side: "additions" });

    let snapshot = reviewCommentStore.snapshotFor(chatId);
    expect(snapshot.diffDraft).toMatchObject({ path: "src/lib", side: "new", line: 2, oldPath: null, editingId: null });
    expect(reviewCommentStore.stagedFor(chatId)).toEqual([]);

    // The draft renders inline (a light-DOM slot child) with its live body,
    // anchored at the additions side, line 2.
    await waitFor(() => mounted.container.querySelector(".comment-draft") !== null);
    const draft = mounted.container.querySelector<HTMLElement>(".comment-draft");
    expect(draft).not.toBeNull();
    expect(mounted.container.querySelector(".comment-draft-location")?.textContent).toBe("src/lib:2");
    expect(draft!.closest("[slot]")?.getAttribute("slot")).toBe("annotation-additions-2");

    await typeDraftBody("Tighten this up.");
    snapshot = reviewCommentStore.snapshotFor(chatId);
    expect(snapshot.diffDraft?.body).toBe("Tighten this up.");

    // Commit: the draft stages through the unchanged store and its card
    // renders at the anchored line; the draft card is gone.
    clickButton(".comment-action-primary");
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    const staged = reviewCommentStore.stagedFor(chatId);
    expect(staged).toHaveLength(1);
    expect(staged[0]).toMatchObject({ path: "src/lib", line: 2, body: "Tighten this up.", source: { kind: "diff", side: "new" } });
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toBeNull();
    const card = mounted.container.querySelector<HTMLElement>(".comment-card");
    expect(card).not.toBeNull();
    expect(mounted.container.querySelector(".comment-card-location")?.textContent).toBe("src/lib:2");
    expect(card!.textContent).toContain("Tighten this up.");
    await waitFor(() => mounted.container.querySelector(".comment-draft") === null);
    // The anchor the library placed the card at rides the portal wrapper's
    // slot attribute (light DOM): additions side, line 2.
    expect(card!.closest("[slot]")?.getAttribute("slot")).toBe("annotation-additions-2");
  });

  it("cancel closes the draft without staging anything", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-cancel");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-cancel");
    await waitFor(() => gutterUtilityClick() !== undefined);

    const files = parseDiffFiles({ checkoutId: "co-raw", checksum: "sum-1", patch: WORKING_PATCH }, "workingTree", null);
    await clickAdder(files[0]!, { start: 4, side: "additions" });
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toMatchObject({ line: 4, side: "new" });
    await waitFor(() => mounted.container.querySelector(".comment-draft") !== null);

    clickButton(".comment-action");
    await waitFor(() => mounted.container.querySelector(".comment-draft") === null);
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toBeNull();
    expect(reviewCommentStore.stagedFor(chatId)).toEqual([]);
  });

  it("edits a staged comment from its card and removes it", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-edit");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-edit");
    await waitFor(() => gutterUtilityClick() !== undefined);

    // Stage one comment through the adder flow.
    const files = parseDiffFiles({ checkoutId: "co-raw", checksum: "sum-1", patch: WORKING_PATCH }, "workingTree", null);
    await clickAdder(files[0]!, { start: 2, side: "additions" });
    await typeDraftBody("First note.");
    clickButton(".comment-action-primary");
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    const stagedId = reviewCommentStore.stagedFor(chatId)[0]!.id;

    // The card's hover pen re-opens the staged comment as the draft,
    // pre-filled and anchored at its own line — the card is excluded while
    // editing (its card becomes the draft).
    clickButton(".comment-card-edit");
    await waitFor(() => mounted.container.querySelector(".comment-draft") !== null);
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toMatchObject({
      path: "src/lib",
      side: "new",
      line: 2,
      editingId: stagedId,
      body: "First note.",
    });
    expect(mounted.container.querySelector(".comment-card")).toBeNull();
    expect(mounted.container.querySelector(".comment-draft")).not.toBeNull();

    await typeDraftBody("Edited note.");
    clickButton(".comment-action-primary");
    await waitFor(() => (mounted.container.querySelector(".comment-card")?.textContent ?? "").includes("Edited note."));
    expect(reviewCommentStore.stagedFor(chatId)).toHaveLength(1);
    expect(reviewCommentStore.stagedFor(chatId)[0]).toMatchObject({ id: stagedId, body: "Edited note." });

    // The remove × drops the comment from the staged set.
    clickButton(".comment-card-remove");
    await waitFor(() => mounted.container.querySelector(".comment-card") === null);
    expect(reviewCommentStore.stagedFor(chatId)).toEqual([]);
  });

  it("resolves a deletions-side click on a renamed file to the pre-rename path", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-rename");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, RENAME_PATCH, "sum-rename")]);
    const mounted = await mountSurface(chatId, "d-rename");
    await waitFor(() => gutterUtilityClick() !== undefined);

    // The "-" line lives on the deletions side (old numbering): the draft
    // anchors on the old side, citing the pre-rename path.
    const files = parseDiffFiles({ checkoutId: "co-raw", checksum: "sum-rename", patch: RENAME_PATCH }, "workingTree", null);
    expect(files[0]!.name).toBe("new.txt");
    expect(files[0]!.prevName).toBe("old.txt");
    await clickAdder(files[0]!, { start: 2, side: "deletions" });

    const snapshot = reviewCommentStore.snapshotFor(chatId);
    expect(snapshot.diffDraft).toMatchObject({ path: "new.txt", side: "old", line: 2, oldPath: "old.txt" });
    await waitFor(() => mounted.container.querySelector(".comment-draft") !== null);
    // The draft header cites the same path the prompt bullet will.
    expect(mounted.container.querySelector(".comment-draft-location")?.textContent).toBe("old.txt:2");

    await typeDraftBody("This line went away.");
    clickButton(".comment-action-primary");
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    const staged = reviewCommentStore.stagedFor(chatId);
    expect(staged[0]).toMatchObject({
      path: "new.txt",
      line: 2,
      body: "This line went away.",
      source: { kind: "diff", side: "old", oldPath: "old.txt" },
    });
    // The staged card's location also cites the pre-rename path.
    expect(mounted.container.querySelector(".comment-card-location")?.textContent).toBe("old.txt:2");
  });

  it("renders comments staged before the pane mounts (the re-opened-pane path)", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-prestage");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    reviewCommentStore.addComment(chatId, {
      id: "pre-1",
      path: "src/lib",
      line: 2,
      body: "Already staged",
      source: { kind: "diff", side: "new", oldPath: null },
    });
    const mounted = await mountSurface(chatId, "d-prestage");
    // The first render already carries the annotation: the card mounts at
    // its anchored line without any store write after mount.
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    const card = mounted.container.querySelector<HTMLElement>(".comment-card");
    expect(card).not.toBeNull();
    expect(mounted.container.querySelector(".comment-card-location")?.textContent).toBe("src/lib:2");
    expect(card!.closest("[slot]")?.getAttribute("slot")).toBe("annotation-additions-2");
  });

  it("split layout offers the adder on the additions side only, while a deletion-side card still renders", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-split");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-split");
    await waitFor(() => gutterUtilityClick() !== undefined);

    const files = parseDiffFiles({ checkoutId: "co-raw", checksum: "sum-1", patch: WORKING_PATCH }, "workingTree", null);

    // Stage an old-side comment while still unified (the del line anchors
    // the old side there, matching the old diffLineAnchor semantics).
    await clickAdder(files[0]!, { start: 2, side: "deletions" });
    await typeDraftBody("Old side note.");
    clickButton(".comment-action-primary");
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    expect(reviewCommentStore.stagedFor(chatId)[0]).toMatchObject({ source: { kind: "diff", side: "old" } });

    // The layout switch to split keeps the card anchored (the deletions
    // column hosts its annotation), and keeps anchors stable across the
    // re-render.
    act(() => {
      changesSurfaceStore.toggleLayout(chatId, "d-split");
    });
    await waitFor(() => mounted.container.querySelector(".comment-card") !== null);
    expect(mounted.container.querySelector(".comment-card")).not.toBeNull();
    expect(mounted.container.querySelector(".comment-card-location")?.textContent).toBe("src/lib:2");

    // The split gate: the adder's click on the deletions side opens no
    // draft; the additions side still does.
    await clickAdder(files[0]!, { start: 2, side: "deletions" });
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toBeNull();
    await clickAdder(files[0]!, { start: 3, side: "additions" });
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toMatchObject({ side: "new", line: 3 });
    await waitFor(() => mounted.container.querySelector(".comment-draft") !== null);

    // Back to unified: the adder offers the old side again.
    await act(async () => {
      reviewCommentStore.cancelDiffDraft(chatId);
    });
    act(() => {
      changesSurfaceStore.toggleLayout(chatId, "d-split");
    });
    await waitFor(() => reviewCommentStore.snapshotFor(chatId).diffDraft === null);
    await clickAdder(files[0]!, { start: 3, side: "deletions" });
    expect(reviewCommentStore.snapshotFor(chatId).diffDraft).toMatchObject({ side: "old", line: 3 });
  });
});
