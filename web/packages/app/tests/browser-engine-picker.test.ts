// @vitest-environment jsdom

/**
 * The browser engine pickers (wp-88, zeron #526's `4510bab2` + `4928e1b2`):
 *
 * - Identity (`4510bab2`): the device card tags the EFFECTIVE row
 *   "Selected engine" — never "You" (the browser is not a device row) —
 *   and the canvas's empty state reads "Select engine", not "This
 *   device". Presence comes from `engineStatesOf(registry)` (the active
 *   engine connected, the absent engine falling to last-seen), so the
 *   offline glyph marks only the engine that is actually stale.
 * - Reconciliation (`4928e1b2`): picking engine B on the canvas writes
 *   `targetForDevicePick`'s result — a foreign project is dropped AND the
 *   visible sidebar filter with it (`rememberNoProject`, while
 *   last-project history stays navigation state); picking a project
 *   writes `targetForProjectPick` (the project's OWNING engine); and
 *   "Don't work in a project" keeps the engine the chips SHOW
 *   (`currentDeviceId`), never the remembered device pick of a stale
 *   host.
 *
 * Mounted for real (jsdom + act — the chips, cards, cursor lists, and the
 * `composerDefaults`/`sidebarStore` singletons all run); the fleet /
 * session / terminal layers are doubled narrowly. The identity suite runs
 * both viewports (the phone sheet carries the same rows); the canvas
 * interaction suite runs desktop — the pick POLICY is viewport-free and
 * the sheet's own behavior lives in picker-card-phone's suite.
 */

import { act, createElement, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { encodeScopedId } from "@roboco/engine-client";
import type { Device, Space } from "@roboco/proto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { DeviceChip } from "../src/components/composer-footer";
import { NewThreadTargetSelectors } from "../src/components/composer/new-thread-selectors";
import { composerDefaults } from "../src/lib/composer-draft";
import { sidebarStore } from "../src/state/sidebar";
import { PHONE_QUERY } from "../src/state/media";

// ── Controllable doubles (the fleet/session/terminal layers) ────────────────

const h = vi.hoisted(() => ({
  phone: false,
  now: 1_800_000_000_000,
  snapshot: null as unknown,
  session: null as unknown,
  active: null as string | null,
  registry: { engines: [] as Array<{ key: string; state: string }> },
}));

// The real `engineStatesOf` stays: presence must come from the registry.
vi.mock("../src/state/fleet", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleetSnapshot: () => h.snapshot,
    useFleetRegistry: () => h.registry,
    useFleet: () => ({ active: h.active, engines: [], configurationError: null }),
  };
});

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => h.session,
}));

vi.mock("../src/state/hooks", () => ({
  useNow: () => h.now,
}));

// The xterm-backed store never imports here; a namesake stub keeps the
// module graph jsdom-clean (the canvas's terminal action row reads it).
vi.mock("../src/terminal/store", () => ({
  drawerTerminalStore: {
    subscribe: () => () => {},
    getVersion: () => 0,
    stateFor: () => ({ open: false }),
    toggle: () => {},
  },
}));

// ── jsdom gaps the mounted cards hit ────────────────────────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query === PHONE_QUERY && h.phone,
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

// ── Fixtures — two engines with distinct baseUrl fleet keys ─────────────────

const ENGINE_A_KEY = "https://engine-a.test";
const ENGINE_B_KEY = "https://engine-b.test";
const DEVICE_A = encodeScopedId(ENGINE_A_KEY, "device-a");
const DEVICE_B = encodeScopedId(ENGINE_B_KEY, "device-b");
const PROJECT_A = encodeScopedId(ENGINE_A_KEY, "project-a");
const PROJECT_B = encodeScopedId(ENGINE_B_KEY, "project-b");

const deviceA: Device = {
  id: DEVICE_A,
  name: "Engine A",
  platform: "linux",
  version: "1.0",
  lastSeenAt: new Date(h.now).toISOString(),
  createdAt: new Date(h.now).toISOString(),
};
const deviceB: Device = {
  ...deviceA,
  id: DEVICE_B,
  name: "Engine B",
  platform: "windows",
  lastSeenAt: new Date(h.now - 3 * 86_400_000).toISOString(),
};

const projectA: Space = {
  id: PROJECT_A,
  deviceId: DEVICE_A,
  name: "Project A",
  path: "/workspace/project-a",
  gitDetected: false,
  createdAt: new Date(h.now).toISOString(),
};
const projectB: Space = {
  ...projectA,
  id: PROJECT_B,
  deviceId: DEVICE_B,
  name: "Project B",
  path: "/workspace/project-b",
};

/** The merged fleet snapshot: every paired engine's device and space rows. */
const SNAPSHOT = {
  generation: 1,
  capabilities: [],
  devices: { rows: [deviceA, deviceB], loaded: true, error: null },
  spaces: { rows: [projectA, projectB], loaded: true, error: null },
};

/** Engine A is the routed session; its raw device id scopes to DEVICE_A. */
const SESSION = {
  engine: { baseUrl: ENGINE_A_KEY },
  client: { engineInfo: { deviceId: "device-a" } },
};

beforeEach(() => {
  h.snapshot = SNAPSHOT;
  h.session = SESSION;
  h.active = ENGINE_A_KEY;
  h.registry.engines = [{ key: ENGINE_A_KEY, state: "connected" }];
  composerDefaults.update({ device: DEVICE_A, project: PROJECT_A, noProject: false });
  sidebarStore.setSpaceFilter(PROJECT_A);
});

// ── The mounted harness ─────────────────────────────────────────────────────

let root: Root | null = null;
let host: HTMLDivElement | null = null;

function mount(element: ReactElement): HTMLDivElement {
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => root!.render(element));
  return host;
}

/** The canvas's floating target row (`render_new_thread_target_selectors`). */
function mountCanvas(): HTMLDivElement {
  return mount(createElement(NewThreadTargetSelectors));
}

afterEach(() => {
  if (root !== null) {
    // Dismiss any open card first (the footer harness's exit-animation
    // guard — the portal's removal defers to the exit animation's end).
    act(() => {
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });
    act(() => {});
    act(() => {
      root!.unmount();
    });
  }
  root = null;
  host?.remove();
  host = null;
  h.phone = false;
  document.body.replaceChildren();
  composerDefaults.update({ device: null, project: null, noProject: false });
  sidebarStore.setSpaceFilter(null);
});

/** A chip trigger's label (roll-text aware — the current span wins). */
function chipLabel(id: string): string {
  const label = document.querySelector<HTMLElement>(`#${id} .footer-menu-chip-label`);
  return (
    label?.querySelector<HTMLElement>(".roll-text-in, .roll-text-still")?.textContent ??
    label?.textContent ??
    ""
  );
}

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: HTMLElement): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

/** Press a popover row by its fade key. */
async function pressRow(key: string): Promise<void> {
  await act(async () => {});
  const row = document.querySelector<HTMLElement>(`[data-rb-row-key="${key}"]`);
  expect(row, `row ${key} must render`).not.toBeNull();
  press(row!);
  await act(async () => {});
}

function pickerInput(ariaLabel: "Search devices" | "Search projects"): HTMLInputElement {
  const element = document.querySelector<HTMLInputElement>(`input[aria-label="${ariaLabel}"]`);
  if (element === null) {
    throw new Error(`Missing picker input: ${ariaLabel}`);
  }
  return element;
}

function pressKey(element: HTMLElement, key: string): void {
  act(() =>
    element.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true })),
  );
}

function expectTarget(device: string | null, project: string | null, noProject: boolean): void {
  expect(composerDefaults.getSnapshot()).toMatchObject({ device, project, noProject });
}

// ── Engine identity (`4510bab2`) — both viewports ───────────────────────────

describe.each([false, true])("browser engine picker (phone=%s)", (phone) => {
  beforeEach(() => {
    h.phone = phone;
  });

  it.each([deviceA, deviceB])(
    "marks $name as selected, never as the browser's device",
    (selected) => {
      mount(
        createElement(DeviceChip, {
          devices: [deviceA, deviceB],
          effectiveDevice: selected,
          ownDeviceId: DEVICE_A,
          now: h.now,
        }),
      );
      expect(chipLabel("picker-device")).toBe(selected.name);
      press(host!.querySelector<HTMLElement>("#picker-device")!);
      const tag = document.querySelector<HTMLElement>(".picker-row-tag")!;
      expect(tag.textContent).toBe("Selected engine");
      expect(tag.closest("button")?.textContent).toContain(selected.name);
      expect(document.querySelector(".picker-list")!.textContent).not.toMatch(
        /\bYou\b|This device/,
      );
      // Presence: A is connected in the registry, B falls to its 3-day-old
      // last-seen — exactly one offline glyph.
      expect(document.querySelectorAll(".picker-row-offline")).toHaveLength(1);
    },
  );

  it("uses Select engine on the canvas with no known host", () => {
    h.session = null;
    h.snapshot = null;
    composerDefaults.update({ device: null, project: null, noProject: false });
    const mounted = mountCanvas();
    expect(chipLabel("picker-device")).toBe("Select engine");
    expect(mounted.textContent).not.toContain("This device");
  });
});

// ── Canvas pick reconciliation (`4928e1b2`) — desktop ───────────────────────

describe("canvas engine pickers reconcile the target", () => {
  it("pointer-switching from engine A to B clears the foreign project and the sidebar filter", async () => {
    const mounted = mountCanvas();
    expect(chipLabel("picker-device")).toBe("Engine A");
    expect(chipLabel("picker-project")).toBe("Project A");

    press(mounted.querySelector<HTMLElement>("#picker-device")!);
    await pressRow(DEVICE_B);

    expectTarget(DEVICE_B, null, true);
    // The visible filter drops with the foreign project; the last-project
    // history stays navigation state, never a hidden run target.
    expect(sidebarStore.getSnapshot()).toMatchObject({
      spaceFilter: null,
      lastSpaceId: PROJECT_A,
    });
    expect(chipLabel("picker-device")).toBe("Engine B");
    expect(chipLabel("picker-project")).toBe("No project");
  });

  it("keyboard engine selection retains a project owned by that same engine", async () => {
    composerDefaults.update({ device: DEVICE_B, project: PROJECT_B, noProject: false });
    const mounted = mountCanvas();

    press(mounted.querySelector<HTMLElement>("#picker-device")!);
    await act(async () => {});
    pressKey(pickerInput("Search devices"), "Enter");

    expectTarget(DEVICE_B, PROJECT_B, false);
    expect(chipLabel("picker-project")).toBe("Project B");
  });

  it("pointer-selecting project B also selects engine B", async () => {
    const mounted = mountCanvas();

    press(mounted.querySelector<HTMLElement>("#picker-project")!);
    await pressRow(PROJECT_B);

    expectTarget(DEVICE_B, PROJECT_B, false);
    expect(chipLabel("picker-device")).toBe("Engine B");
    expect(chipLabel("picker-project")).toBe("Project B");
  });

  it("no-project keeps the engine the chips show, not the remembered device", async () => {
    // The remembered device pick is engine A's, but the sidebar's project
    // filter belongs to engine B — the canvas shows B, and opting out of a
    // project must not silently restore the stale host.
    composerDefaults.update({ device: DEVICE_A, project: null, noProject: false });
    sidebarStore.setSpaceFilter(PROJECT_B);
    const mounted = mountCanvas();
    expect(chipLabel("picker-device")).toBe("Engine B");
    expect(chipLabel("picker-project")).toBe("Project B");

    press(mounted.querySelector<HTMLElement>("#picker-project")!);
    await pressRow("no-project");

    expectTarget(DEVICE_B, null, true);
    expect(sidebarStore.getSnapshot()).toMatchObject({
      spaceFilter: null,
      lastSpaceId: PROJECT_B,
    });
    expect(chipLabel("picker-device")).toBe("Engine B");
    expect(chipLabel("picker-project")).toBe("No project");
  });
});
