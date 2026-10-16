// @vitest-environment jsdom

/**
 * The sidebar row's Limited marker (orchestration v2, ticket 06/10): a chat
 * whose session row is errored with a recorded usage limit shows the amber
 * "Limited" word and dot in its corner — replacing the plain "Failed" word
 * (or the idle time-ago when the chat was seen). The REAL ChatList mounts
 * over the real store chain (SidebarStore → UiSettingsStore → jsdom
 * localStorage) — the sidebar-row-send-truth mounted-suite idiom, doubled
 * the same way; no JSX (createElement).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { encodeScopedId, type ChatStatus } from "@roboco/engine-client";
import type { Chat, SessionLimit } from "@roboco/proto";
import { ChatList } from "../src/components/chat-list";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";

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

// ── Fixtures ───────────────────────────────────────────────────────────────

function sc(rawId: string): string {
  return encodeScopedId("eng-1", rawId);
}

function chat(rawId: string, fields: Partial<Chat> = {}): Chat {
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
    lastMessageAt: "2026-10-16T12:04:00Z",
    createdAt: "2026-10-16T12:04:00Z",
    ...fields,
  };
}

function limit(fields: Partial<SessionLimit> = {}): SessionLimit {
  return { harness: "claude-code", window: "5-hour", ...fields };
}

function status(rawId: string, fields: Partial<ChatStatus> = {}): ChatStatus {
  return {
    chatId: sc(rawId),
    deviceId: "dev-1",
    status: "errored",
    lastCompletedTurn: null,
    startedAt: null,
    updatedAt: new Date().toISOString(),
    limit: null,
    scheduledResume: null,
    ...fields,
  };
}

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  h.sessions.clear();
  uiSettings.updateImmediate({ sidebarPinnedSessionIdsByProfile: {} });
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
});

function mountChatList(): HTMLElement {
  // The detailed card (compact off) carries the corner the stories read.
  uiSettings.updateImmediate({ sidebarCompact: false });
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
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

/** One chat's corner, by raw chat id. */
function cornerOf(container: HTMLElement, rawId: string): HTMLElement | null {
  const link = Array.from(container.querySelectorAll<HTMLElement>("a.chat-row")).find(
    (el) => el.getAttribute("href") === `#/chat/${sc(rawId)}`,
  );
  return link?.querySelector<HTMLElement>(".chat-row-corner") ?? null;
}

describe("the sidebar row's Limited marker (mounted)", () => {
  it("a limit record turns the corner amber: word Limited, dot-limited (story 43)", () => {
    h.engineEntry.chats.rows = [chat("a"), chat("b")];
    h.engineEntry.sessions.rows = [
      status("a", { limit: limit({ resetsAt: Date.now() + 45 * 60_000 }) }),
      // The untouched chat keeps its own corner word (unseen → Done).
      status("b", { status: "idle", lastCompletedTurn: "t1" }),
    ];
    const container = mountChatList();
    const corner = cornerOf(container, "a");
    expect(corner!.textContent).toBe("Limited");
    expect(corner!.querySelector(".dot-limited")).not.toBeNull();
    expect(cornerOf(container, "b")!.textContent).toBe("Done");
  });

  it("a seen Limited chat still shows the word — the time-ago stands down", () => {
    h.engineEntry.chats.rows = [
      chat("a", { lastSeenAt: "2026-10-16T12:04:01Z" }),
    ];
    h.engineEntry.sessions.rows = [status("a", { limit: limit() })];
    const container = mountChatList();
    // Unseen would read "errored" (Failed); seen reads idle — the Limited
    // override replaces the time-ago either way, like the desktop's
    // `status_label`.
    const corner = cornerOf(container, "a");
    expect(corner!.textContent).toBe("Limited");
    expect(corner!.querySelector(".chat-row-time")).toBeNull();
  });

  it("the same record on any other status is inert — a working row never marks", () => {
    h.engineEntry.chats.rows = [chat("a")];
    h.engineEntry.sessions.rows = [
      status("a", { status: "working", limit: limit() }),
    ];
    const container = mountChatList();
    const corner = cornerOf(container, "a");
    expect(corner!.textContent).toContain("Working");
    expect(corner!.querySelector(".dot-limited")).toBeNull();
  });

  it("a plain error keeps the failure red word", () => {
    h.engineEntry.chats.rows = [chat("a")];
    h.engineEntry.sessions.rows = [status("a")];
    const container = mountChatList();
    const corner = cornerOf(container, "a");
    expect(corner!.textContent).toBe("Failed");
    expect(corner!.querySelector(".dot-errored")).not.toBeNull();
  });
});
