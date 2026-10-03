// @vitest-environment jsdom

/**
 * The sidebar row's send truth, mounted (parity spec: sidebar row truth):
 * the corner reads Working while a send is in flight, "Failed" once a send
 * sits unadopted past the grace window, and "Queued" while a degraded
 * delivery path holds it. The REAL ChatList mounts over the real store
 * chain (SidebarStore → UiSettingsStore → jsdom localStorage) and the REAL
 * app-wide echo store — a pending send for one chat recolors exactly that
 * chat's row. The fleet/session/router layers are doubled narrowly, the
 * chat-list-archive mounted-suite idiom; no JSX (createElement).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { encodeScopedId, type ChatStatus } from "@roboco/engine-client";
import type { Chat } from "@roboco/proto";
import { ChatList } from "../src/components/chat-list";
import { echoStore, UNDELIVERED_GRACE_MS, type PendingSend } from "../src/state/transcript-store";
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

function seedFleet(): void {
  h.engineEntry.chats.rows = [
    chat("a", "2026-09-16T12:04:00Z"),
    chat("b", "2026-09-16T12:03:00Z"),
  ];
}

function send(rawId: string, startedAtMs: number): PendingSend {
  return {
    messageId: `m-${rawId}`,
    chatId: sc(rawId),
    startedAtMs,
    text: "hello",
    attachmentPaths: [],
  };
}

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  echoStore.reset();
  h.sessions.clear();
  h.engineEntry.state = "connected";
  uiSettings.updateImmediate({ sidebarPinnedSessionIdsByProfile: {} });
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
});

function mountChatList(): HTMLElement {
  // The detailed card (compact off) carries the corner the stories read;
  // compact mode is the ui-settings default, so write it for this mount.
  uiSettings.updateImmediate({ sidebarCompact: false });
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

/** One chat's corner text, by raw chat id (the status word, or the time). */
function cornerOf(container: HTMLElement, rawId: string): string | null {
  const link = Array.from(container.querySelectorAll<HTMLElement>("a.chat-row")).find(
    (el) => el.getAttribute("href") === `#/chat/${sc(rawId)}`,
  );
  const corner = link?.querySelector<HTMLElement>(".chat-row-corner");
  return corner?.textContent ?? null;
}

describe("the sidebar row's send truth (mounted)", () => {
  it("a send in flight shows Working on that chat's row (story 7)", () => {
    seedFleet();
    echoStore.pushEcho(send("a", Date.now() - 1_000));
    const container = mountChatList();
    expect(cornerOf(container, "a")).toBe("Working");
    // The untouched chat keeps its own corner word (unseen → Done).
    expect(cornerOf(container, "b")).toBe("Done");
  });

  it("a send past the grace window shows Failed (story 8)", () => {
    seedFleet();
    echoStore.pushEcho(send("a", Date.now() - UNDELIVERED_GRACE_MS - 5_000));
    const container = mountChatList();
    expect(cornerOf(container, "a")).toBe("Failed");
  });

  it("a send unconfirmed on a degraded engine stays Queued (story 9)", () => {
    seedFleet();
    echoStore.pushEcho(send("a", Date.now() - 1_000));
    // The owning engine's connectivity slot degrades — the web's
    // `chatDeliveryDegraded` reads the session cache's watch snapshot.
    h.engineEntry.state = "reconnecting";
    const cache = {
      getSnapshot: () => ({ connectivity: { value: { state: "reconnecting" }, loaded: true } }),
      subscribe: () => () => {},
    };
    h.sessions.set("eng-1", { engine: { baseUrl: "local" }, cache });
    const container = mountChatList();
    expect(cornerOf(container, "a")).toBe("Queued");
  });

  it("the corner clears when the send is confirmed", () => {
    seedFleet();
    const echo = send("a", Date.now() - 1_000);
    echoStore.pushEcho(echo);
    const container = mountChatList();
    expect(cornerOf(container, "a")).toBe("Working");
    act(() => {
      echoStore.ackFromFrame(echo.chatId, [echo.messageId]);
    });
    // The overlay yields: the corner falls back to the chat's own status
    // (unseen activity → Done), never a stuck Working.
    expect(cornerOf(container, "a")).toBe("Done");
  });
});
