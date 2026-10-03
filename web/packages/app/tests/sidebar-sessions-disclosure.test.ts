// @vitest-environment jsdom

/**
 * The one-list mode's Sessions disclosure and the transient empty pinned
 * section (parity spec: sidebar row truth). The REAL ChatList mounts over
 * the real store chain; the fleet/session/router layers are doubled
 * narrowly (the chat-list-archive mounted-suite idiom); geometry reads
 * fake rects (the still-pointer suite's WeakMap override) and drop
 * hit-testing reads a stubbed `document.elementFromPoint`. No JSX
 * (createElement).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { encodeScopedId, type ChatStatus } from "@roboco/engine-client";
import type { Chat } from "@roboco/proto";
import { ChatList } from "../src/components/chat-list";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";
import { sidebarStore } from "../src/state/sidebar";

// ── Controllable doubles (the fleet/session/router layers) ─────────────────

const h = vi.hoisted(() => {
  if (
    typeof HTMLImageElement !== "undefined" &&
    typeof HTMLImageElement.prototype.decode !== "function"
  ) {
    (HTMLImageElement.prototype as unknown as { decode: () => Promise<void> }).decode =
      () => Promise.resolve();
  }
  const engines = [{ key: "eng-1", label: "Local Engine", baseUrl: "local" }];
  const sessions = new Map<string, unknown>();
  const engineEntry = {
    key: "eng-1",
    info: { deviceId: "dev-1", workspaceScope: "local" as const },
    state: "connected" as "connected" | "reconnecting" | "off",
    lastError: null,
    generation: 1,
    chats: { rows: [] as Chat[], loaded: true, error: null },
    spaces: { rows: [], loaded: true, error: null },
    devices: { rows: [], loaded: true, error: null },
    sessions: { rows: [] as ChatStatus[], loaded: true, error: null },
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
    engineEntry,
    registry,
    subscribeFleet: (listener: () => void): (() => void) => {
      fleetListeners.add(listener);
      return () => {
        fleetListeners.delete(listener);
      };
    },
    getSnapshot: (): typeof snapshot => snapshot,
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
  return {
    useNavigate: () => async () => {},
    useParams: () => ({}),
    useRouterState: <T,>(opts: { select: (state: unknown) => T }): T =>
      opts.select({ location: { pathname: "/" } }),
    Link: (props: { params?: { chatId?: string }; className?: string; children?: React.ReactNode }) =>
      createElement(
        "a",
        {
          href: props.params?.chatId === undefined ? "#" : `#/chat/${props.params.chatId}`,
          className: props.className,
          onClick: (event: MouseEvent) => event.preventDefault(),
        },
        props.children,
      ),
  };
});

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
  globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) =>
    setTimeout(() => callback(performance.now()), 0)) as unknown as typeof requestAnimationFrame;
  if (typeof Element.prototype.animate !== "function") {
    (Element.prototype as unknown as { animate: () => { cancel(): void } }).animate = () => ({
      cancel(): void {},
    });
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── Geometry: fake rects + hit-testing ─────────────────────────────────────

const rectOverrides = new WeakMap<Element, { top: number; bottom: number; left: number; right: number }>();
const realGetBoundingClientRect = Element.prototype.getBoundingClientRect;
const realElementFromPoint = document.elementFromPoint;

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
  document.elementFromPoint = realElementFromPoint;
});

function rectOf(el: Element, top: number, bottom: number, left = 0, right = 280): void {
  rectOverrides.set(el, { top, bottom, left, right });
}

// ── Fixtures ───────────────────────────────────────────────────────────────

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
    lastSeenAt: at,
  };
}

function seedFleet(): void {
  h.engineEntry.chats.rows = [
    chat("a", "2026-09-16T12:04:00Z"),
    chat("b", "2026-09-16T12:03:00Z"),
  ];
}

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  h.sessions.clear();
  uiSettings.updateImmediate({
    sidebarPinnedSessionIdsByProfile: {},
    sidebarCompact: true,
    sidebarOrganization: "inOneList",
  });
  sidebarStore.setSessionsOpen(true);
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
});

function mountChatList(): HTMLElement {
  // The detailed card carries the corner the stories read; the one-list
  // organization is the ui-settings default but pin it explicitly.
  uiSettings.updateImmediate({ sidebarCompact: false, sidebarOrganization: "inOneList" });
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(ChatList));
  });
  mounted.push(() => {
    act(() => {
      root.unmount();
    });
    container.remove();
  });
  return container;
}

function firePointer(target: Element | Window, type: string, at: { clientX: number; clientY: number }): void {
  act(() => {
    (target as Element | Window).dispatchEvent(
      new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, ...at }),
    );
  });
}

describe("the one-list mode's Sessions disclosure (mounted)", () => {
  it("regular rows sit under a collapsible Sessions section (story 14)", () => {
    seedFleet();
    const container = mountChatList();
    const header = container.querySelector<HTMLElement>("#sessions-toggle");
    expect(header).not.toBeNull();
    expect(header?.getAttribute("aria-expanded")).toBe("true");
    expect(header?.textContent).toContain("Sessions");
    expect(container.querySelectorAll(".regular-row")).toHaveLength(2);
    // Collapse: the count rides the label, and the rows leave the list —
    // hidden rows are truly hidden (the desktop skips them from the render
    // order; the visible order's exclusion is the pure suite's pin).
    act(() => {
      header!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    });
    expect(header?.textContent).toContain("Sessions (2)");
    expect(header?.getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelectorAll(".regular-row")).toHaveLength(0);
  });

  it("dropping a pinned chat on the collapsed Sessions header opens it and unpins (story 15)", () => {
    seedFleet();
    uiSettings.updateImmediate({ sidebarPinnedSessionIdsByProfile: { local: [sc("a")] } });
    const container = mountChatList();
    // Collapse the Sessions section first.
    act(() => {
      container
        .querySelector<HTMLElement>("#sessions-toggle")!
        .dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    });
    const header = container.querySelector<HTMLElement>("#sessions-toggle")!;
    // Lay out the list, the pinned group, and the sessions header: the
    // press is on the pinned row, the drag moves BELOW the pinned group
    // (the transfer-out preview), and the release is over the header.
    rectOf(container.querySelector<HTMLElement>(".chat-list")!, 0, 800);
    const pinnedRow = container.querySelector<HTMLElement>(".pinned-row")!;
    rectOf(pinnedRow, 100, 161);
    rectOf(container.querySelector<HTMLElement>('[data-testid="sidebar-pinned-sessions"]')!, 0, 170);
    rectOf(header, 200, 228);
    document.elementFromPoint = (x: number, y: number): Element | null => {
      void x;
      void y;
      return header;
    };
    firePointer(pinnedRow, "pointerdown", { clientX: 140, clientY: 130 });
    // One move past the arm threshold, then one BELOW the pinned group —
    // the transfer-out preview — before the release over the header.
    firePointer(window, "pointermove", { clientX: 140, clientY: 140 });
    firePointer(window, "pointermove", { clientX: 140, clientY: 214 });
    firePointer(window, "pointerup", { clientX: 140, clientY: 214 });
    // The drop routed to the Regular arm: the pin is gone…
    expect(uiSettings.getSnapshot().sidebarPinnedSessionIdsByProfile["local"]).toBeUndefined();
    // …and the section re-opened to receive the row.
    expect(header.getAttribute("aria-expanded")).toBe("true");
    expect(container.querySelectorAll(".regular-row")).toHaveLength(2);
  });

  it("the pinned section mounts for a drag even with no pins yet (story 12)", () => {
    seedFleet();
    const container = mountChatList();
    // No pins: the section is absent at rest…
    expect(container.querySelector('[data-testid="sidebar-pinned-section"]')).toBeNull();
    // …and mounts the moment a transfer is armed, so the first pin can be
    // made by dragging.
    rectOf(container.querySelector<HTMLElement>(".chat-list")!, 0, 800);
    const row = container.querySelectorAll<HTMLElement>(".regular-row")[0]!;
    rectOf(row, 100, 161);
    document.elementFromPoint = (x: number, y: number): Element | null => {
      void x;
      void y;
      return (
        container.querySelector('[data-testid="sidebar-pinned-section"]') ?? row
      );
    };
    firePointer(row, "pointerdown", { clientX: 140, clientY: 130 });
    firePointer(window, "pointermove", { clientX: 140, clientY: 136 });
    expect(container.querySelector('[data-testid="sidebar-pinned-section"]')).not.toBeNull();
    // Release inside the pinned section: the pin lands.
    const liveSection = container.querySelector<HTMLElement>('[data-testid="sidebar-pinned-section"]')!;
    rectOf(liveSection, 0, 90);
    firePointer(window, "pointerup", { clientX: 140, clientY: 45 });
    expect(uiSettings.getSnapshot().sidebarPinnedSessionIdsByProfile["local"]).toEqual([sc("a")]);
    expect(container.querySelectorAll(".pinned-row")).toHaveLength(1);
  });
});
