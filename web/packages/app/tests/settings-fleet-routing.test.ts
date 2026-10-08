// @vitest-environment jsdom

/**
 * Ticket 91 — the engine-addressing settings pages' routing contract
 * (port of zeron 97f86114's mounted suite, roboco's transport): Accounts
 * and Agents list through the fleet's active engine, a settings-engine
 * switch REMOUNTS the page body (fresh state — no passthrough target, no
 * stale engine-local editor state), and the kept `DeviceSwitcher` still
 * routes intra-engine device picks through `targetDeviceId` (roboco's
 * pairing architecture — the switcher is NOT the canvas engine chip).
 */

import { act, createElement, type ReactElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { HarnessDescriptor } from "@roboco/proto";
import { encodeScopedId } from "@roboco/engine-client";
import { AgentsSettingsPage } from "../src/routes/settings-agents";
import { AccountsSettingsPage } from "../src/routes/settings-accounts";

const h = vi.hoisted(() => {
  const ENGINES = ["engine-a", "engine-b"];
  /** Antigravity is the one harness whose enable runs a sign-in (the stale-state probe). */
  const antigravity: HarnessDescriptor = {
    id: "antigravity",
    name: "Antigravity",
    supportsSteering: false,
    steeringMode: "turn-boundary",
    reasoningLevels: [],
    installed: true,
    canInstall: true,
    enabled: false,
  };
  /** Every RPC the pages issued, with the client (engine) that issued it. */
  const calls: Array<{ engine: string; method: string; params: Record<string, unknown> }> = [];
  let active = ENGINES[0]!;
  /** The DeviceSwitcher mock's pick (scoped device id, or null for local). */
  let pick: string | null = null;
  const harnessLists: Record<string, HarnessDescriptor[]> = {
    // Both engines offer the Antigravity row: the stale engine-a failure
    // would reconcile onto engine-b's identically-keyed row without the
    // engine-key remount.
    "engine-a": [antigravity],
    "engine-b": [antigravity],
  };
  const clients = Object.fromEntries(
    ENGINES.map((id) => [
      id,
      {
        engineInfo: { deviceId: id === "engine-a" ? "dev-a" : "dev-b", capabilities: [] },
        call: async (method: string, params: Record<string, unknown>) => {
          calls.push({ engine: id, method, params });
          if (method === "ListHarnesses") return harnessLists[id] ?? [];
          if (method === "ListAgentAccounts") return { accounts: [], warnings: [] };
          if (method === "GetTitleSettings") return { harness: null, model: null };
          // The sign-in redirect lands on a loopback port of the device
          // running the agent — unreachable from this browser.
          if (method === "StartAgentLogin") throw new Error("loopback unreachable from this browser");
          return {};
        },
      },
    ]),
  );
  const sessions = Object.fromEntries(
    ENGINES.map((id) => [
      id,
      {
        engine: {
          baseUrl: id,
          credential: "credential",
          label: "Roboco web on Windows",
          sessionId: "session",
          pairedAt: 1,
          deviceId: id === "engine-a" ? "dev-a" : "dev-b",
        },
        client: clients[id],
        catalog: { loadHarnesses: async () => {} },
      },
    ]),
  );
  const setActive = (id: string) => {
    active = id;
  };
  return {
    calls,
    sessions,
    setActive,
    get active(): string {
      return active;
    },
    set active(value: string) {
      active = value;
    },
    get pick(): string | null {
      return pick;
    },
    set pick(value: string | null) {
      pick = value;
    },
  };
});

vi.mock("../src/state/fleet", () => ({
  useFleet: () => ({
    active: h.active,
    engines: [
      {
        baseUrl: "engine-a",
        credential: "credential",
        label: "Roboco web on Windows",
        sessionId: "session",
        pairedAt: 1,
        deviceId: "dev-a",
      },
      {
        baseUrl: "engine-b",
        credential: "credential",
        label: "Roboco web on Linux",
        sessionId: "session",
        pairedAt: 2,
        deviceId: "dev-b",
      },
    ],
    configurationError: null,
  }),
  useFleetRegistry: () => ({ engines: [], configurationError: null }),
  fleetStore: { setActive: h.setActive },
}));
vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => h.sessions[h.active],
}));
vi.mock("../src/state/hooks", () => ({
  useNow: () => 0,
  useWatchSnapshot: () => null,
}));
vi.mock("../src/components/ui/DeviceSwitcher", async () => {
  const { createElement } = await import("react");
  return {
    DeviceSwitcher: (props: { onTargetChange: (target: string | null) => void }): ReactElement =>
      createElement(
        "button",
        { "data-testid": "device-switcher-pick", type: "button", onClick: () => props.onTargetChange(h.pick) },
        "pick",
      ),
  };
});
vi.mock("../src/components/settings-engine-indicator", async () => {
  const { createElement } = await import("react");
  return {
    SettingsEngineIndicator: (): ReactElement =>
      createElement(
        "button",
        { "data-testid": "choose-engine-b", type: "button", onClick: () => h.setActive("engine-b") },
        "engine b",
      ),
  };
});

// ── jsdom gaps the mounted pages hit (settings-dialogs.test.ts's set) ──────

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
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    }) as typeof requestAnimationFrame;
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── The mounted page harness ───────────────────────────────────────────────

interface MountedPage {
  /** The host container the page renders into. */
  readonly container: HTMLDivElement;
  /** Re-render the page (the store mocks read the hoisted state live). */
  render(): Promise<void>;
  unmount(): Promise<void>;
}

const mounted: MountedPage[] = [];

afterEach(async () => {
  while (mounted.length > 0) {
    await mounted.pop()!.unmount();
  }
  document.body.replaceChildren();
  h.active = "engine-a";
  h.pick = null;
  h.calls.length = 0;
});

/** Mount a settings page (initial render included) for manual re-renders. */
async function mountPage(page: () => ReactElement): Promise<MountedPage> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const handle: MountedPage = {
    container,
    render: async () => {
      await act(async () => {
        root.render(page());
      });
    },
    unmount: async () => {
      await act(async () => {
        root.unmount();
      });
      container.remove();
    },
  };
  mounted.push(handle);
  await handle.render();
  return handle;
}

function callsFor(
  method: string,
): Array<{ engine: string; method: string; params: Record<string, unknown> }> {
  return h.calls.filter((call) => call.method === method);
}

async function click(container: HTMLElement, testId: string): Promise<void> {
  const button = container.querySelector<HTMLButtonElement>(`[data-testid=${testId}]`);
  if (button === null) {
    throw new Error(`no [data-testid=${testId}] button rendered`);
  }
  await act(async () => {
    button.click();
  });
}

// ── Engine switching through the settings indicator ────────────────────────

describe.each([
  ["Agents", AgentsSettingsPage, "ListHarnesses"],
  ["Accounts", AccountsSettingsPage, "ListAgentAccounts"],
] as const)("%s settings route their loads through the active engine", (_name, Page, method) => {
  it("re-lists on the engine the settings indicator picked, with no passthrough target", async () => {
    h.active = "engine-a";
    h.calls.length = 0;
    const page = await mountPage(() => createElement(Page));
    expect(callsFor(method).at(-1)?.engine).toBe("engine-a");
    // The settings engine indicator's pick (the popover row's
    // fleetStore.setActive) — the canvas engine chip is a different
    // control entirely.
    await click(page.container, "choose-engine-b");
    await page.render();
    const lists = callsFor(method);
    expect(lists.at(-1)?.engine).toBe("engine-b");
    // The switched-to page is a fresh body: the engine's own device is
    // the target — no device passthrough rides along from engine-a.
    expect(lists.at(-1)?.params).not.toHaveProperty("targetDeviceId");
  });
});

// ── The remount: engine-local page state is dropped with its engine ────────

describe("Agents settings remount on the engine key change", () => {
  it("drops an engine-a sign-in failure when the settings engine switches", async () => {
    h.active = "engine-a";
    h.calls.length = 0;
    const page = await mountPage(() => createElement(AgentsSettingsPage));
    // Enable Antigravity on engine-a: the sign-in start fails (the
    // loopback redirect is unreachable from this browser) and the failure
    // sticks in the row — engine-local page state.
    const toggle = page.container.querySelector<HTMLElement>(".rb-switch");
    if (toggle === null) {
      throw new Error("no .rb-switch rendered for the Antigravity row");
    }
    await act(async () => {
      toggle.click();
    });
    expect(page.container.querySelector(".harness-sign-in-failure")?.textContent).toContain(
      "loopback unreachable from this browser",
    );
    // Switching the settings engine remounts the page: the failure state
    // belongs to engine-a and must not reconcile onto engine-b.
    await click(page.container, "choose-engine-b");
    await page.render();
    expect(page.container.querySelector(".harness-sign-in-failure")).toBe(null);
    expect(callsFor("ListHarnesses").at(-1)?.engine).toBe("engine-b");
  });
});

// ── The kept DeviceSwitcher contract (intra-engine passthrough) ────────────

describe("Accounts settings keep the DeviceSwitcher's device routing", () => {
  it("a same-engine device pick carries targetDeviceId; another engine's host row switches engines", async () => {
    h.active = "engine-a";
    h.calls.length = 0;
    // A non-host device on the SAME engine — the intra-engine pick the
    // DeviceSwitcher still owns.
    h.pick = encodeScopedId("engine-a", "dev-b");
    const page = await mountPage(() => createElement(AccountsSettingsPage));
    expect(callsFor("ListAgentAccounts").at(-1)?.params).not.toHaveProperty("targetDeviceId");
    await click(page.container, "device-switcher-pick");
    await page.render();
    const passthrough = callsFor("ListAgentAccounts").at(-1);
    expect(passthrough?.engine).toBe("engine-a");
    expect(passthrough?.params).toMatchObject({ targetDeviceId: "dev-b" });
    // Picking another engine's host row through the same switcher
    // switches the settings engine (and the remount drops the
    // passthrough with the old engine's page state).
    h.pick = encodeScopedId("engine-b", "dev-b");
    await click(page.container, "device-switcher-pick");
    await page.render();
    const switched = callsFor("ListAgentAccounts").at(-1);
    expect(switched?.engine).toBe("engine-b");
    expect(switched?.params).not.toHaveProperty("targetDeviceId");
  });
});
