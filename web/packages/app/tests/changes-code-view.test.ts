// @vitest-environment jsdom

/**
 * Ticket 02 (web-pierre-adoption): the Changes pane's body renders the diff
 * through the Pierre diffs library. The real `ChangesSurface` — the
 * store-bound wrapper the surface registry mounts — renders (no JSX,
 * per-file jsdom pragma, the base-tooltip idiom) with the fleet/session
 * doubles from the empty-state suite: the scripted watch delivers a working
 * tree frame, and the fake caller answers the branch capture for the scope
 * switch. Assertions stay OUTSIDE the shadow DOM — our chrome (banner,
 * host) and the store-driven drives the library's public surface shows:
 * the fold toggle flips our header-slot chevron (a light-DOM slot child the
 * shadow tree pulls in), and the scope switch re-renders the banner through
 * the real store's scoped capture.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Chat, CheckoutDiff } from "@roboco/proto";
import { encodeScopedId } from "@roboco/engine-client";
import { ChangesSurface } from "../src/routes/changes-page";
import { changesSurfaceStore } from "../src/state/changes-surface";
import { __resetReviewCommentsForTests } from "../src/state/review-comments";
import { __resetChangesDiffForTests } from "../src/lib/changes-diff";

// ── Controllable doubles ──────────────────────────────────────────────────

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
  /** The branch capture the fake `GetCheckoutDiff` resolves, per test. */
  let branchCapture: object | null = null;
  return {
    chats,
    frames,
    get branchCapture() {
      return branchCapture;
    },
    set branchCapture(next) {
      branchCapture = next;
    },
  };
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
    if (method === "GetCheckoutDiff") {
      return Promise.resolve((h.branchCapture ?? []) as T);
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
const mounted: Array<{ chatId: string; surfaceId: string }> = [];

beforeEach(() => {
  h.chats.length = 0;
  h.frames.length = 0;
  h.branchCapture = null;
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

const BRANCH_PATCH = `diff --git a/src/other b/src/other
index 111111..222222 100644
--- a/src/other
+++ b/src/other
@@ -1,1 +1,2 @@
-a
+b
+c
`;

interface Mounted {
  readonly container: HTMLDivElement;
  readonly root: Root;
  unmount(): void;
}


/**
 * Flush the library's rAF render queue + the async highlighter/themes.
 */
async function settle(ms = 120): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
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

// ── The library render path ───────────────────────────────────────────────

describe("ChangesSurface renders diffs through the Pierre diffs library", () => {
  it("mounts the library's host inside the existing chrome when a diff resolves", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-diff");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-lib");

    // The chrome: scope banner with the file count and +N/−N.
    expect(mounted.container.querySelector(".changes-banner")).not.toBeNull();
    expect(mounted.container.textContent).toContain("1 Uncommitted change");

    // The library host: the scroll container our wrapper class owns…
    const host = mounted.container.querySelector<HTMLElement>(".changes-code-host");
    expect(host).not.toBeNull();
    // …and at least one shadow-DOM host element for a file item inside it.
    await settle();
    expect(host!.querySelector("diffs-container")).not.toBeNull();
  });

  it("drives per-file folds from the surface store through the item state", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-fold");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    const mounted = await mountSurface(chatId, "d-fold");
    await settle();

    // Our fold chevron rides the library header's prefix slot — light-DOM
    // slot content, not shadow internals.
    const chevron = mounted.container.querySelector<HTMLButtonElement>(".changes-fold-toggle");
    expect(chevron).not.toBeNull();
    expect(chevron!.getAttribute("aria-expanded")).toBe("true");

    // The store write (the toolbar's fold-all and header clicks land here)
    // re-renders the item with its collapsed flag.
    act(() => {
      changesSurfaceStore.toggleFold(chatId, "d-fold", "src/lib");
    });
    await settle();
    const collapsed = mounted.container.querySelector<HTMLButtonElement>(".changes-fold-toggle");
    expect(collapsed!.getAttribute("aria-expanded")).toBe("false");

    // Toggling back re-expands.
    act(() => {
      changesSurfaceStore.toggleFold(chatId, "d-fold", "src/lib");
    });
    await settle();
    expect(mounted.container.querySelector<HTMLButtonElement>(".changes-fold-toggle")!.getAttribute("aria-expanded")).toBe("true");
  });

  it("renders the per-file notices through the library header's metadata slot", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-notice");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([
      frame(
        `\\\\?\\C:\\repo\\probe`,
        `diff --git a/made.txt b/made.txt
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/made.txt
@@ -0,0 +1,1 @@
+hello
`,
        "sum-notice",
      ),
    ]);
    const mounted = await mountSurface(chatId, "d-notice");
    await settle();

    // The old notice rows' copy, now riding the header metadata slot.
    expect(mounted.container.querySelector(".changes-file-notices")?.textContent).toContain("New file");
  });

  it("re-renders through the real scoped capture when the store switches scope", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-scope");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([frame(`\\\\?\\C:\\repo\\probe`, WORKING_PATCH, "sum-1")]);
    h.branchCapture = frame(`\\\\?\\C:\\repo\\probe`, BRANCH_PATCH, "branch-sum");
    const mounted = await mountSurface(chatId, "d-scope");
    await settle();
    expect(mounted.container.textContent).toContain("1 Uncommitted change");

    // The toolbar's scope pick lands in the surface store; the body's store
    // effect fetches the branch capture and the banner follows.
    act(() => {
      changesSurfaceStore.setScope(chatId, "d-scope", "branch");
    });
    await settle();
    expect(mounted.container.textContent).toContain("1 Changed file vs main");
    expect(mounted.container.textContent).not.toContain("Uncommitted");
  });
});
