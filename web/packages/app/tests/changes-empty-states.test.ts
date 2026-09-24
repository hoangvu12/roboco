// @vitest-environment jsdom

/**
 * Ticket 01 (web-pierre-adoption): the Changes pane's preparing phase tells
 * the truth instead of spinning forever. The mounted body proves the four
 * classified outcomes render — "isn't a git repository" (the browser smoke
 * harness's plain-tempdir chat), "diffs live on its own device", "no
 * checkout folder", and the ONE genuine-loading arm that keeps the
 * "Preparing diff…" spinner — plus the resolution path: a verbatim
 * `\\?\`-cwd frame lands and the empty state gives way to the diff banner.
 *
 * The real `ChangesBody` mounts (no JSX, per-file jsdom pragma, the
 * base-tooltip idiom). The fleet/session layers are doubled narrowly the
 * account-row way: the merged fleet snapshot serves scripted SCOPED chat
 * rows (scopeChat scopes deviceId/checkoutId — the classification must
 * compare the scoped forms), and the session's client is a scripted fake
 * caller whose `WatchCheckoutDiffs` delivers the frames the engine would —
 * the initial `[]` enumeration first. The `ChangesStore` itself is real, so
 * the watch → snapshot → re-render chain runs as in the app.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Chat, CheckoutDiff } from "@roboco/proto";
import { encodeScopedId } from "@roboco/engine-client";
import { ChangesBody } from "../src/routes/changes-page";
import { changesSurfaceStore } from "../src/state/changes-surface";
import { __resetReviewCommentsForTests } from "../src/state/review-comments";

// ── Controllable doubles ──────────────────────────────────────────────────

const ENGINE_KEY = "http://engine.local";
const RAW_DEVICE_ID = "dev-own";

const h = vi.hoisted(() => {
  // jsdom's Image has no decode() — the appearance store's artwork prewarm
  // (module-singleton import of state/appearance) fires it fire-and-forget
  // during module import, before any hook can stub it; patch it here, ahead
  // of the import chain, so the run stays clean (matchMedia/ResizeObserver's
  // gap class, hoisted for timing).
  if (typeof Image === "function" && Image.prototype.decode === undefined) {
    (Image.prototype as { decode: () => Promise<void> }).decode = () => Promise.resolve();
  }
  /** Chat rows the merged fleet snapshot serves (scoped ids, set per test). */
  const chats: object[] = [];
  /** Frames the scripted watch delivers the moment it subscribes. */
  const frames: object[] = [];
  /** The live watch's onItem — late (post-subscribe) frame delivery. */
  let watchOnItem: ((item: object, context: { generation: number }) => void) | null = null;
  return {
    chats,
    frames,
    get watchOnItem() {
      return watchOnItem;
    },
    set watchOnItem(next) {
      watchOnItem = next;
    },
  };
});

/** The routed session: the engine's own device, connected, on a fake client. */
const fakeClient = {
  status: { state: "connected", info: { deviceId: RAW_DEVICE_ID, workspaceScope: "local" }, generation: 1 },
  onStatus(): () => void {
    return () => {};
  },
  call<T>(method: string, _params?: unknown): Promise<T> {
    // ListBranches on a non-repo fails engine-side; the store logs and keeps
    // [] — the fake replies the same empty list without the error.
    void method;
    return Promise.resolve([] as T);
  },
  watch(
    method: string,
    _params: unknown,
    handlers: { onItem: (item: object, context: { generation: number }) => void },
  ): { method: string; cancel: () => void } {
    if (method === "WatchCheckoutDiffs") {
      h.watchOnItem = handlers.onItem;
      // The engine's watch_stream emits the current value first: the
      // initial frame enumerates every tracked checkout (often `[]`).
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
  // Exactly what ChangesBody reads: the pairing gate (engines) and the
  // merged chat rows the chat-page lookup resolves its row from.
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
  // The diff body arrives on its own chunk (the finding-4a lazy boundary);
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
  h.watchOnItem = null;
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

/** A chat row in the merged snapshot's scoped form. */
function chatRow(overrides: Partial<Chat>): Chat {
  return {
    id: encodeScopedId(ENGINE_KEY, "chat-1"),
    deviceId: encodeScopedId(ENGINE_KEY, RAW_DEVICE_ID),
    title: "Chat",
    archived: false,
    cwd: "C:/tmp/plain-folder",
    branch: null,
    checkoutId: null,
    sourceContext: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

/** A working-tree frame, as the engine's canonicalize emits it on Windows. */
function frame(cwd: string, patch: string): CheckoutDiff {
  return {
    checkoutId: "co-raw",
    deviceId: RAW_DEVICE_ID,
    cwd,
    patch,
    files: [],
    additions: 1,
    deletions: 0,
    truncated: false,
    checksum: "sum-1",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

interface Mounted {
  readonly container: HTMLDivElement;
  readonly root: Root;
  unmount(): void;
}

/** Mount the real body on a working-tree scope; flush the store's effects. */
async function mountBody(chatId: string, surfaceId: string): Promise<Mounted> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(
      createElement(ChangesBody, {
        chatId,
        surfaceId,
        scope: "workingTree",
        requestedBase: null,
        commitSha: null,
        layout: "unified",
        wrap: false,
        folds: new Map(),
        scrollEpoch: 0,
      }),
    );
  });
  const mountedBody: Mounted = {
    container,
    root,
    unmount: () => {
      act(() => {
        root.unmount();
      });
    },
  };
  unmounts.push(mountedBody.unmount);
  mounted.push({ chatId, surfaceId });
  return mountedBody;
}

function text(mounted: Mounted): string {
  return mounted.container.textContent ?? "";
}

// ── The four classified empty states ──────────────────────────────────────

describe("ChangesBody preparing-phase empty states", () => {
  it("renders the not-a-git-repository message once the watch has enumerated (the smoke harness's chat)", async () => {
    // The browser smoke fixture exactly: a same-device chat whose cwd is a
    // plain tempdir — no checkoutId stamp, and the watch's first frame is
    // the engine's `[]` enumeration. Today's pane spins on this forever.
    const chatId = encodeScopedId(ENGINE_KEY, "chat-plain");
    h.chats.push(chatRow({ id: chatId }));
    h.frames.push([]);
    const mounted = await mountBody(chatId, "d-plain");

    expect(text(mounted)).toContain("This chat's checkout isn't a git repository.");
    // The spinner is gone — this state is an answer, not a wait.
    expect(text(mounted)).not.toContain("Preparing diff…");
    expect(mounted.container.querySelector(".changes-preparing")).toBeNull();
  });

  it("renders the remote-device message for a chat hosted on another device", async () => {
    // The engine only tracks its own device's chats (diff_sync.rs:345), so
    // the row's scoped device id mismatching the routed engine's own scoped
    // id is conclusive — even before the watch has said anything.
    const chatId = encodeScopedId(ENGINE_KEY, "chat-remote");
    h.chats.push(chatRow({ id: chatId, deviceId: encodeScopedId(ENGINE_KEY, "dev-other") }));
    const mounted = await mountBody(chatId, "d-remote");

    expect(text(mounted)).toContain("diffs live on its own device");
    expect(text(mounted)).not.toContain("Preparing diff…");
  });

  it("renders the no-checkout-folder message for a cwd-less chat", async () => {
    const chatId = encodeScopedId(ENGINE_KEY, "chat-home");
    h.chats.push(chatRow({ id: chatId, cwd: null }));
    const mounted = await mountBody(chatId, "d-home");

    expect(text(mounted)).toContain("This chat has no checkout folder.");
    expect(text(mounted)).not.toContain("Preparing diff…");
  });

  it("keeps the spinner only while the watch has not delivered", async () => {
    // Same non-git chat as the first test, but the watch stays silent: the
    // engine has not enumerated its checkouts yet, so nothing is knowable.
    const chatId = encodeScopedId(ENGINE_KEY, "chat-loading");
    h.chats.push(chatRow({ id: chatId }));
    const mounted = await mountBody(chatId, "d-loading");

    expect(text(mounted)).toContain("Preparing diff…");
    expect(mounted.container.querySelector(".changes-preparing")).not.toBeNull();
    expect(text(mounted)).not.toContain("git repository");

    // The frame arrives → the classification re-renders into the answer.
    act(() => {
      h.watchOnItem?.([], { generation: 1 });
    });
    expect(text(mounted)).toContain("This chat's checkout isn't a git repository.");
    expect(mounted.container.querySelector(".changes-preparing")).toBeNull();
  });

  it("keeps the spinner for a tracked checkout whose capture is still out", async () => {
    // The engine stamped the row's checkout id (scoped here, as the merged
    // rows carry it): the folder IS a git repository and is tracked — an
    // outstanding capture is a genuine load, never "not a git repository".
    const chatId = encodeScopedId(ENGINE_KEY, "chat-tracked");
    h.chats.push(chatRow({ id: chatId, checkoutId: encodeScopedId(ENGINE_KEY, "co-1") }));
    h.frames.push([]);
    const mounted = await mountBody(chatId, "d-tracked");

    expect(text(mounted)).toContain("Preparing diff…");
    expect(text(mounted)).not.toContain("git repository");
  });

  it("resolves a verbatim-cwd frame for a checkout-id-less chat and renders the diff banner", async () => {
    // The regression's user-visible half: the frame carries the engine's
    // canonical `\\?\`-verbatim cwd while the chat row carries the plain
    // forward-slashed path and no checkout id — the fallback now matches,
    // the phase leaves preparing, and the scope banner renders.
    const chatId = encodeScopedId(ENGINE_KEY, "chat-repo");
    h.chats.push(chatRow({ id: chatId, cwd: "C:/repo/probe" }));
    h.frames.push([frame("\\\\?\\C:\\repo\\probe", "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,1 +1,2 @@\n-x\n+x\n+y\n")]);
    const mounted = await mountBody(chatId, "d-repo");

    // The diff body now arrives on its own chunk (the finding-4a lazy
    // boundary): flush the lazy import's retry render so the preparing
    // note is gone for the right reason — the phase left, not the
    // fallback still showing (the `settle` idiom of the code-view suite).
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 120));
    });

    expect(text(mounted)).not.toContain("Preparing diff…");
    expect(text(mounted)).not.toContain("git repository");
    expect(mounted.container.querySelector(".changes-banner")).not.toBeNull();
    expect(text(mounted)).toContain("1 Uncommitted change");
  });
});
