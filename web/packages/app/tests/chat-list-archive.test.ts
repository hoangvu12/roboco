// @vitest-environment jsdom

/**
 * The chat list's archive affordance under the still-pointer resync
 * (upstream f1ea80d7's `chat_hover_resync`, the web parity of
 * `archive_pill_follows_a_still_pointer_down_the_list` in
 * crates/ui/src/shell/spaces.rs).
 *
 * The contract under test, in the browser's own event order:
 *   1. hovering a row swaps its status corner for the Archive pill, and
 *      only the pill's click archives (the row's own click navigates);
 *   2. an Archive click ARMS the resync at the click's client point —
 *      the pointer does not move, so when the mutation lands on the
 *      watch stream and the next row slides up under that point, no
 *      `mouseenter` ever fires for it: the resync's frame loop must
 *      light ITS pill, so a user can archive a/b/c straight down the
 *      list without jogging the mouse;
 *   3. a real `pointermove` releases the arm — the browser's own
 *      mouseenter/mouseleave tracking owns hover from there;
 *   4. the archived row never stays lit: the active list shows exactly
 *      one pill, the adopted row's.
 *
 * The REAL ChatList mounts with the real store chain (SidebarStore →
 * UiSettingsStore → jsdom localStorage); the fleet snapshot is a
 * subscribable double so the engine round-trip (the fake engine applies
 * the archive and publishes) re-renders the list mid-test. The
 * session/router layers are doubled narrowly — the mounted-suite idiom
 * (pinned-drag-gesture). No JSX (createElement), per-file jsdom pragma
 * only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { encodeScopedId, methods } from "@roboco/engine-client";
import type { Chat } from "@roboco/proto";
import { ChatList } from "../src/components/chat-list";
import { releaseStillPointer, stillPoint } from "../src/lib/still-pointer";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";

// ── Controllable doubles (the fleet/session/router layers) ─────────────────

const h = vi.hoisted(() => {
  // The new-thread artwork prewarm (state/appearance.ts, pulled in by the
  // row monogram) rides `Image#decode`; jsdom has none — patch at hoist
  // time, ahead of the import graph evaluating the store.
  if (
    typeof HTMLImageElement !== "undefined" &&
    typeof HTMLImageElement.prototype.decode !== "function"
  ) {
    (HTMLImageElement.prototype as unknown as { decode: () => Promise<void> }).decode =
      () => Promise.resolve();
  }
  const engines = [{ key: "eng-1", label: "Local Engine", baseUrl: "local" }];
  const sessions = new Map<string, unknown>();
  const navigateCalls: Array<{ to: string }> = [];
  /** The one paired engine — local scope, chats loaded, no spaces. */
  const engineEntry = {
    key: "eng-1",
    info: { deviceId: "dev-1", workspaceScope: "local" as const },
    state: "connected" as const,
    lastError: null,
    generation: 1,
    chats: { rows: [] as Chat[], loaded: true, error: null },
    spaces: { rows: [], loaded: true, error: null },
    devices: { rows: [], loaded: true, error: null },
    sessions: { rows: [], loaded: true, error: null },
  };
  const registry = { engines: [engineEntry], configurationError: null };
  const fleetListeners = new Set<() => void>();
  let snapshot = {
    generation: 1,
    capabilities: [],
    chats: engineEntry.chats,
    spaces: engineEntry.spaces,
    devices: engineEntry.devices,
    statuses: engineEntry.sessions,
  };
  return {
    engines,
    sessions,
    navigateCalls,
    engineEntry,
    registry,
    subscribeFleet: (listener: () => void): (() => void) => {
      fleetListeners.add(listener);
      return () => {
        fleetListeners.delete(listener);
      };
    },
    getSnapshot: (): typeof snapshot => snapshot,
    /** Publish a fresh snapshot (new identity, the engine's watch frame). */
    notifyFleet: (): void => {
      snapshot = { ...snapshot, chats: { ...engineEntry.chats } };
      for (const listener of fleetListeners) {
        listener();
      }
    },
  };
});

vi.mock("../src/state/fleet", async () => {
  const { useSyncExternalStore } = await import("react");
  return {
    useFleet: () => ({ active: "eng-1", engines: h.engines, configurationError: null }),
    useFleetRegistry: () => h.registry,
    useFleetSnapshot: (): ReturnType<typeof h.getSnapshot> =>
      useSyncExternalStore(h.subscribeFleet, h.getSnapshot),
    engineStatesOf: () => new Map(),
    fleetLocalDeviceId: () => null,
  };
});

vi.mock("../src/state/session-provider", () => ({
  useEngineSessions: () => h.sessions,
  useEngineSession: () => null,
}));

vi.mock("@tanstack/react-router", async () => {
  const { createElement } = await import("react");
  const navigate = (options: { to: string; params?: Record<string, string> }): Promise<void> => {
    h.navigateCalls.push(options);
    return Promise.resolve();
  };
  return {
    useNavigate: () => navigate,
    useParams: () => ({}),
    useRouterState: <T,>(opts: { select: (state: unknown) => T }): T =>
      opts.select({ location: { pathname: "/" } }),
    // The row's real anchor: an `<a>` with the chat's scoped id in the href,
    // navigating on click exactly the router's Link does.
    Link: (props: {
      to?: string;
      params?: { chatId?: string };
      className?: string;
      children?: React.ReactNode;
    }) =>
      createElement(
        "a",
        {
          href: props.params?.chatId === undefined ? "#" : `#/chat/${props.params.chatId}`,
          className: props.className,
          onClick: (event: MouseEvent) => {
            event.preventDefault();
            void navigate({ to: props.to ?? "", params: props.params });
          },
        },
        props.children,
      ),
  };
});

// ── jsdom gaps the mounted list hits (the mounted-suite set) ───────────────

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
  if (typeof Element.prototype.scrollIntoView !== "function") {
    Element.prototype.scrollIntoView = () => {};
  }
  // The resync's frame loop rides `requestAnimationFrame`; jsdom's native
  // one fires on its own ~16ms clock, too coarse to await deterministically
  // — a macrotask frame keeps the loop real (deferred, never synchronous)
  // and lets `settle()` advance it frame by frame.
  globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) =>
    setTimeout(() => callback(performance.now()), 0)) as unknown as typeof requestAnimationFrame;
  // The FLIP resort glide rides Web Animations (`Element.animate`) — jsdom
  // has none; the stub only needs `cancel` (the effect's cleanup).
  if (typeof Element.prototype.animate !== "function") {
    (Element.prototype as unknown as { animate: () => { cancel(): void } }).animate = () => ({
      cancel(): void {},
    });
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── Geometry: jsdom lays nothing out, so the resync reads fake rects ────────

const ROW_TOP = 100;
const ROW_HEIGHT = 61;
const ROW_PITCH = ROW_HEIGHT + 2;
const SIDEBAR_LEFT = 0;
const SIDEBAR_RIGHT = 280;
/** The click's (and the arm's) client point — row 0's middle. */
const POINTER = { clientX: (SIDEBAR_LEFT + SIDEBAR_RIGHT) / 2, clientY: ROW_TOP + 30 };

const rectOverrides = new WeakMap<Element, { top: number; bottom: number; left: number; right: number }>();
const realGetBoundingClientRect = Element.prototype.getBoundingClientRect;

beforeAll(() => {
  Element.prototype.getBoundingClientRect = function (this: Element): DOMRect {
    const override = rectOverrides.get(this);
    const rect = override ?? { top: 0, bottom: 0, left: 0, right: 0 };
    return {
      x: rect.left,
      y: rect.top,
      width: rect.right - rect.left,
      height: rect.bottom - rect.top,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      left: rect.left,
      toJSON(): Record<string, number> {
        return { x: rect.left, y: rect.top, width: rect.right - rect.left, height: rect.bottom - rect.top, top: rect.top, right: rect.right, bottom: rect.bottom, left: rect.left };
      },
    } as DOMRect;
  };
});

afterAll(() => {
  Element.prototype.getBoundingClientRect = realGetBoundingClientRect;
});

/**
 * Lay the ACTIVE rows out in display order: 61px rows on 63px pitch under
 * ROW_TOP. Re-applied after every re-render — the row that slides up under
 * the still pointer keeps a live rect, which is exactly what the browser
 * does with its own layout.
 */
function layoutRows(container: HTMLElement): HTMLElement[] {
  const rows = Array.from(container.querySelectorAll<HTMLElement>(".chat-row-item"));
  rows.forEach((row, index) => {
    rectOverrides.set(row, {
      top: ROW_TOP + index * ROW_PITCH,
      bottom: ROW_TOP + index * ROW_PITCH + ROW_HEIGHT,
      left: SIDEBAR_LEFT,
      right: SIDEBAR_RIGHT,
    });
  });
  return rows;
}

// ── The fake engine: MUTATE applies the archive, the watch publishes ───────

interface Call {
  readonly method: string;
  readonly params: unknown;
}

const client = {
  calls: [] as Call[],
  async call<T>(method: string, params?: unknown): Promise<T> {
    // A real EngineClient never runs a request in the task that wrote the
    // store — defer one tick so synchronous setup applies first.
    await new Promise((resolve) => setTimeout(resolve, 0));
    this.calls.push({ method, params });
    if (method === methods.MUTATE) {
      const { op, chatId, archived } = params as {
        op: string;
        chatId: string;
        archived: boolean;
      };
      if (op === "setChatArchived") {
        // The engine round-trip lands: the row flips and the watch stream
        // publishes a fresh snapshot (the frame the next row slides under).
        h.engineEntry.chats.rows = h.engineEntry.chats.rows.map((chat) =>
          chat.id === chatId ? { ...chat, archived } : chat,
        );
        h.notifyFleet();
        return undefined as T;
      }
    }
    throw new Error(`unknown method: ${method}`);
  },
  watch(): { cancel(): void } {
    // The change-request watch rides the same no-op the mounted-suite
    // idiom uses: no change requests in this fleet.
    return { cancel: () => {} };
  },
};

// ── Fixtures ───────────────────────────────────────────────────────────────

/** A scoped chat id — the sidebar's namespace for engine "eng-1" rows. */
function sc(rawId: string): string {
  return encodeScopedId("eng-1", rawId);
}

function chat(rawId: string, at: string): Chat {
  return {
    id: sc(rawId),
    deviceId: "dev-1",
    title: `Chat ${rawId}`,
    archived: false,
    cwd: null,
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: at,
    createdAt: at,
  };
}

/** Recency: a newest, then b, then c — the desktop test's a/b/c. */
function seedFleet(): void {
  h.engineEntry.chats.rows = [
    chat("a", "2026-09-16T12:04:00Z"),
    chat("b", "2026-09-16T12:03:00Z"),
    chat("c", "2026-09-16T12:02:00Z"),
  ];
}

// ── The mounted ChatList harness ───────────────────────────────────────────

interface MountedChatList {
  readonly container: HTMLElement;
  unmount(): void;
  rows(): HTMLElement[];
  /** The mounted rows' archive pills, in display order (hovered rows only). */
  archivePills(): HTMLElement[];
}

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  releaseStillPointer();
  h.navigateCalls.length = 0;
  client.calls.length = 0;
});

function mountChatList(): MountedChatList {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(ChatList));
  });
  layoutRows(container);
  const unmount = (): void => {
    act(() => {
      root.unmount();
    });
    container.remove();
  };
  mounted.push(unmount);
  return {
    container,
    unmount,
    rows: () => Array.from(container.querySelectorAll<HTMLElement>(".chat-row-item")),
    archivePills: () =>
      Array.from(container.querySelectorAll<HTMLElement>("button.chat-row-archive")),
  };
}

// ── Event drivers (real DOM events, the browser's order) ───────────────────

function fireHover(row: Element, enter: boolean): void {
  // React synthesizes onMouseEnter/onMouseLeave from the bubbling
  // `mouseover`/`mouseout` pair at the root (it never listens for the raw
  // non-bubbling events), so the jsdom recipe is the pair with a
  // relatedTarget outside the row — the browser's own dispatch shape.
  act(() => {
    row.dispatchEvent(
      new MouseEvent(enter ? "mouseover" : "mouseout", {
        bubbles: true,
        cancelable: true,
        relatedTarget: document.body,
      }),
    );
  });
}

function fireClick(target: Element, at: { clientX: number; clientY: number }): void {
  act(() => {
    target.dispatchEvent(
      new MouseEvent("click", { bubbles: true, cancelable: true, button: 0, ...at }),
    );
  });
}

function firePointerMove(at: { clientX: number; clientY: number }): void {
  act(() => {
    window.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, ...at }));
  });
}

/**
 * Let the round-trip land and the frame loop re-test: each tick advances
 * one macrotask (the engine's deferred reply, a resync frame, React's
 * scheduled renders), and the layout stays fresh between them.
 */
async function settle(rounds = 6): Promise<void> {
  for (let i = 0; i < rounds; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    layoutRows(document.body);
  }
}

// ── The suites ─────────────────────────────────────────────────────────────

beforeEach(() => {
  seedFleet();
  // Re-publish: the previous test's notify decoupled the snapshot from
  // `engineEntry.chats` (a fresh identity per watch frame), so the reseed
  // needs its own frame to reach a freshly mounted list.
  h.notifyFleet();
  h.sessions.set("eng-1", { client });
  window.localStorage.clear();
});

describe("the archive affordance (f1ea80d7's still-pointer resync)", () => {
  it("hovers the pill in, only the pill archives, and the row's click navigates", async () => {
    const handle = mountChatList();
    expect(handle.archivePills()).toHaveLength(0);
    // Row hover swaps the status corner for the Archive pill.
    fireHover(handle.rows()[0]!, true);
    expect(handle.archivePills()).toHaveLength(1);
    expect(handle.archivePills()[0]!.getAttribute("aria-label")).toBe("Archive chat");
    fireHover(handle.rows()[0]!, false);
    expect(handle.archivePills()).toHaveLength(0);
    // The row's own click is the selector: it navigates, never archives.
    fireClick(handle.rows()[0]!.querySelector("a")!, POINTER);
    expect(h.navigateCalls).toEqual([{ to: "/chat/$chatId", params: { chatId: sc("a") } }]);
    expect(client.calls).toHaveLength(0);
  });

  it("the pill follows a still pointer down the list — a, b, c without a mouse jog", async () => {
    const handle = mountChatList();
    expect(handle.rows()).toHaveLength(3);
    // Hover row a, then click its pill at the arm point.
    fireHover(handle.rows()[0]!, true);
    fireClick(handle.archivePills()[0]!, POINTER);
    // The arm is set — the pointer has not moved since the click. The
    // mutation itself rides the deferred RPC tick; it lands in the loop.
    expect(stillPoint()).toEqual({ x: POINTER.clientX, y: POINTER.clientY });

    for (const [archived, remaining] of [
      ["a", ["b", "c"]],
      ["b", ["c"]],
      ["c", []],
    ] as const) {
      // The engine round-trip lands; the next row slides up under the
      // unmoved pointer.
      await settle();
      expect(client.calls[client.calls.length - 1]).toEqual({
        method: methods.MUTATE,
        params: { op: "setChatArchived", chatId: sc(archived), archived: true },
      });
      expect(handle.rows().map((row) => row.querySelector("a")?.getAttribute("href"))).toEqual(
        remaining.map((id) => `#/chat/${sc(id)}`),
      );
      // Exactly one pill remains lit — the ADOPTED row's. The archived row
      // never stays lit (it left the active list), and the rows below
      // adopt only when the point falls inside them.
      const pills = handle.archivePills();
      expect(pills).toHaveLength(remaining.length > 0 ? 1 : 0);
      if (remaining.length > 0) {
        const adopted = pills[0]!.closest(".chat-row-item")!;
        expect(adopted.querySelector("a")?.getAttribute("href")).toBe(`#/chat/${sc(remaining[0]!)}`);
      }
      // The arm survives the round-trip: the pointer still has not moved.
      expect(stillPoint()).toEqual({ x: POINTER.clientX, y: POINTER.clientY });
      // Archive the next row straight off its own adopted pill.
      if (remaining.length > 0) {
        fireClick(pills[0]!, POINTER);
      }
    }

    // All three archived without a single pointer move; the list is empty.
    expect(handle.rows()).toHaveLength(0);
    expect(client.calls.map((call) => call.params)).toEqual([
      { op: "setChatArchived", chatId: sc("a"), archived: true },
      { op: "setChatArchived", chatId: sc("b"), archived: true },
      { op: "setChatArchived", chatId: sc("c"), archived: true },
    ]);
  });

  it("a real pointer movement hands hover back to the browser's own tracking", async () => {
    const handle = mountChatList();
    // Archive a: the arm adopts row b's pill under the still pointer.
    fireHover(handle.rows()[0]!, true);
    fireClick(handle.archivePills()[0]!, POINTER);
    await settle();
    expect(handle.rows()).toHaveLength(2);
    expect(handle.archivePills()).toHaveLength(1);
    // The pointer moves — the arm releases, the browser owns hover again.
    firePointerMove({ clientX: POINTER.clientX + 40, clientY: POINTER.clientY });
    expect(stillPoint()).toBe(null);
    // The adopted hover stands until the browser's own mouseleave fires.
    expect(handle.archivePills()).toHaveLength(1);
    fireHover(handle.rows()[0]!, false);
    expect(handle.archivePills()).toHaveLength(0);
    // And a fresh mouseenter lights the next row exactly as before.
    fireHover(handle.rows()[1]!, true);
    expect(handle.archivePills()).toHaveLength(1);
    expect(handle.archivePills()[0]!.closest("a")?.getAttribute("href")).toBe(`#/chat/${sc("c")}`);
    // A later archive without any arm (hover-driven, the pre-f1ea80d7
    // path) still works: the pill is a plain click away.
    fireClick(handle.archivePills()[0]!, POINTER);
    await settle();
    expect(handle.rows()).toHaveLength(1);
  });
});
