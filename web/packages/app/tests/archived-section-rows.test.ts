// @vitest-environment jsdom

/**
 * The archived shelf's row shape (parity spec: archived rows render with
 * the same card shape as active rows — status corner, project @ device
 * line, change-request badge — so the shelf's height model and row
 * renderer agree). The REAL ArchivedSection mounts over the real store
 * chain; the fleet/session/router/change-request layers are doubled
 * narrowly (the chat-list-archive mounted-suite idiom); no JSX.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { encodeScopedId, type ChatStatus } from "@roboco/engine-client";
import type { ChangeRequestSummary, Chat } from "@roboco/proto";
import { ArchivedSection } from "../src/components/archived-section";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";

// ── Controllable doubles (the fleet/session/router/CR layers) ──────────────

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
  let changeRequests = new Map<string, ChangeRequestSummary>();
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
    setChangeRequests: (map: Map<string, ChangeRequestSummary>): void => {
      changeRequests = map;
    },
    getChangeRequests: (): ReadonlyMap<string, ChangeRequestSummary> => changeRequests,
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

vi.mock("../src/state/change-requests-store", () => ({
  useFleetChatChangeRequests: () => h.getChangeRequests(),
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

afterEach(() => {
  document.body.replaceChildren();
  h.sessions.clear();
  uiSettings.updateImmediate({ sidebarCompact: false });
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
});

describe("the archived shelf renders the shared row card (story 13)", () => {
  it("an archived row is the same card as an active row — corner, folder line, badge", () => {
    h.engineEntry.chats.rows = [
      {
        id: sc("old"),
        deviceId: "dev-1",
        title: "Fix the parser",
        archived: true,
        spaceId: "space-1",
        cwd: null,
        branch: null,
        checkoutId: null,
        config: null,
        lastMessagePreview: null,
        lastMessageAt: "2026-09-16T12:04:00Z",
        createdAt: "2026-09-16T12:04:00Z",
        lastSeenAt: "2026-09-16T12:04:00Z",
        sourceContext: {
          checkoutId: "c1",
          repoRoot: "/repos/fieldnotes",
          cwd: "/repos/fieldnotes",
          branch: "feat/parser",
          observedAt: "2026-09-16T10:00:00Z",
        },
      },
    ];
    h.engineEntry.spaces.rows = [
      {
        id: "space-1",
        deviceId: "dev-1",
        path: "/repos/fieldnotes",
        name: null,
        gitDetected: false,
        createdAt: "2026-01-01T00:00:00Z",
      },
    ] as never;
    h.engineEntry.devices.rows = [
      { id: "dev-1", name: "Studio desktop", platform: "windows", lastSeenAt: null },
    ] as never;
    h.setChangeRequests(
      new Map([
        [
          sc("old"),
          {
            number: 421,
            title: "Fix the parser",
            headRef: "feat/parser",
            baseRef: "main",
            state: "open",
          } as ChangeRequestSummary,
        ],
      ]),
    );

    uiSettings.updateImmediate({ sidebarCompact: false });
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(createElement(ArchivedSection));
    });
    try {
      // The shared card, not the old slim one-liner.
      expect(container.querySelectorAll(".arch-row")).toHaveLength(0);
      expect(container.querySelectorAll(".chat-row-item")).toHaveLength(1);
      // Line 1: the project @ device line the active rows carry.
      const folder = container.querySelector<HTMLElement>(".chat-row-folder");
      expect(folder?.textContent).toBe("fieldnotes @ Studio desktop");
      // The status corner (time at rest for an idle archived row).
      expect(container.querySelector(".chat-row-corner")).not.toBeNull();
      // Line 3: the branch and the change-request badge.
      const branch = container.querySelector<HTMLElement>(".chat-row-branch");
      expect(branch?.textContent).toBe("feat/parser");
      expect(container.querySelector(".chat-row-pr")).not.toBeNull();
    } finally {
      act(() => {
        root.unmount();
      });
      container.remove();
    }
  });
});
