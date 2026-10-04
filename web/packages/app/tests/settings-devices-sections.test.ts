// @vitest-environment jsdom

/** Mounted coverage for fleet host rows, local/other grouping, and the pre-identity fallback. */

import { act, createElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Device } from "@roboco/proto";
import { DevicesSettingsPage } from "../src/routes/settings-devices";

// ── Controllable doubles ──────────────────────────────────────────────────

const h = vi.hoisted(() => {
  const NOW = 1_800_000_000_000;

  /** The engine's own device id — the partition key (`engineInfo.deviceId`). */
  let localDeviceId: string | null = "dev-a";

  /** The watch snapshot's device rows — the connected engine's registry. */
  let devices: Device[] = [];

  /** The active engine's session — only `client.engineInfo` is read here. */
  const session = {
    client: {
      get engineInfo(): { deviceId: string } | null {
        return localDeviceId === null ? null : { deviceId: localDeviceId };
      },
    },
  };

  return {
    NOW,
    get localDeviceId(): string | null {
      return localDeviceId;
    },
    set localDeviceId(value: string | null) {
      localDeviceId = value;
    },
    get devices(): Device[] {
      return devices;
    },
    set devices(value: Device[]) {
      devices = value;
    },
    session,
    /** The navigate double's record — unused by these rows, kept live. */
    navigateCalls: [] as Array<{ to: string }>,
  };
});

vi.mock("../src/state/session-provider", () => ({
  // The session owns the rename RPC; fleet identity controls grouping.
  useEngineSession: () => h.session,
}));

vi.mock("../src/state/fleet", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleet: () => ({ active: "https://engine.test", engines: [], configurationError: null }),
    useFleetRegistry: () => ({
      engines: [{
        key: "https://engine.test",
        state: "connected",
        info: h.localDeviceId === null ? null : { deviceId: h.localDeviceId, capabilities: [] },
        chats: { rows: [] },
        spaces: { rows: [] },
        sessions: { rows: [] },
        devices: { rows: h.devices },
      }],
    }),
    fleetStore: { redeemPairingUrl: vi.fn(), remove: vi.fn() },
    forgetEngine: vi.fn(),
  };
});

vi.mock("../src/state/hooks", () => ({
  // The local row's presence is the live connection; the snapshot carries
  // the device rows.
  useEngineStatus: () => ({ state: "connected", info: h.session.client.engineInfo, generation: 1 }),
  useNow: () => h.NOW,
  useWatchSnapshot: () => ({
    devices: { rows: h.devices, loaded: true, error: null },
  }),
}));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => (options: { to: string }): Promise<void> => {
    h.navigateCalls.push({ to: options.to });
    return Promise.resolve();
  },
}));

// ── jsdom gaps the mounted page hits (settings-dialogs.test.ts's set) ──────

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

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  h.localDeviceId = "dev-a";
  h.devices = [];
  h.navigateCalls.length = 0;
});

function mountDevicesPage(): void {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(DevicesSettingsPage));
  });
  mounted.push(() => {
    act(() => {
      root.unmount();
    });
    container.remove();
  });
}

function device(id: string, name: string): Device {
  return {
    id,
    name,
    platform: "macos",
    lastSeenAt: new Date(h.NOW - 30_000).toISOString(),
    createdAt: new Date(h.NOW - 3_600_000).toISOString(),
  };
}

/** Every section header's title, in DOM order. */
function sectionTitles(): string[] {
  return Array.from(document.querySelectorAll<HTMLHeadingElement>(".settings-section-header h2")).map(
    (header) => header.textContent ?? "",
  );
}

/** The `settings-card` that follows the named section header. */
function sectionCard(title: string): HTMLElement {
  const header = Array.from(
    document.querySelectorAll<HTMLHeadingElement>(".settings-section-header h2"),
  ).find((candidate) => candidate.textContent === title);
  if (header === undefined) {
    throw new Error(`no "${title}" section header rendered`);
  }
  const card = header.parentElement!.nextElementSibling;
  if (!(card instanceof HTMLElement) || !card.classList.contains("settings-card")) {
    throw new Error(`no settings-card follows the "${title}" header`);
  }
  return card;
}

/** The device-row names inside a card, in order. */
function rowNames(card: HTMLElement): string[] {
  return Array.from(card.querySelectorAll<HTMLElement>(".device-row .settings-row-title")).map(
    (title) => title.textContent ?? "",
  );
}

// ── The two-section split ──────────────────────────────────────────────────

describe("DevicesSettingsPage — the This device / Other devices split (ticket 08)", () => {
  it("renders the engine host once in the local section, with other clients below", () => {
    h.devices = [device("dev-b", "Vu's Phone"), device("dev-a", "Vu's Studio")];
    mountDevicesPage();

    // Engine actions live on the host row, within the existing two sections.
    expect(sectionTitles()).toEqual(["This device", "Other devices"]);
    // Exactly the engine's own device under "This device"…
    expect(rowNames(sectionCard("This device"))).toEqual(["Vu's Studio"]);
    // …the rest, in registry order, under "Other devices".
    expect(rowNames(sectionCard("Other devices"))).toEqual(["Vu's Phone"]);
    expect(document.querySelectorAll(".device-row")).toHaveLength(2);
    expect(sectionCard("This device").querySelector(".badge")?.textContent).toBe("Active engine");
    // The section heading replaces the old This device badge.
    expect(Array.from(document.querySelectorAll(".badge")).some((badge) => badge.textContent === "This device")).toBe(false);
  });

  it("empty others renders the desktop empty copy, and the section stays up", () => {
    // The scope gate: WatchCacheSnapshot does not expose the workspace
    // scope and the browser can pair remote synced engines, so the
    // "Other devices" section always renders (desktop hides it only for
    // local-scope workspaces, devices.rs:554-557) — its empty state is
    // the desktop's pair copy.
    h.devices = [device("dev-a", "Vu's Studio")];
    mountDevicesPage();

    expect(sectionTitles()).toEqual(["This device", "Other devices"]);
    expect(rowNames(sectionCard("This device"))).toEqual(["Vu's Studio"]);
    const others = sectionCard("Other devices");
    expect(rowNames(others)).toEqual([]);
    expect(others.querySelector(".settings-empty")?.textContent).toBe(
      "Pair another device to see it here.",
    );
    expect(Array.from(document.querySelectorAll(".badge")).some((badge) => badge.textContent === "This device")).toBe(false);
  });

  it("a known local id missing from the registry hides the This device section", () => {
    // The desktop's `.when_some(local_block, …)` (devices.rs:541-543): no
    // local row, no "This device" section — every row lives under
    // "Other devices".
    h.devices = [device("dev-b", "Vu's Phone")];
    h.localDeviceId = "dev-a";
    mountDevicesPage();

    expect(sectionTitles()).toEqual(["Other devices"]);
    expect(rowNames(sectionCard("Other devices"))).toEqual(["Vu's Phone"]);
    expect(Array.from(document.querySelectorAll(".badge")).some((badge) => badge.textContent === "This device")).toBe(false);
  });

  it("before engineInfo loads, the flat list stays — no section headers, no badge", () => {
    // The fallback arm (partitionDevices's `unknown`): engineInfo has not
    // loaded, so the split is unknowable and the page keeps the flat
    // single card until the local device id is known.
    h.devices = [device("dev-a", "Vu's Studio"), device("dev-b", "Vu's Phone")];
    h.localDeviceId = null;
    mountDevicesPage();

    expect(sectionTitles()).toEqual([]);
    const rows = Array.from(document.querySelectorAll<HTMLElement>(".device-row .settings-row-title"));
    expect(rows.map((title) => title.textContent)).toEqual(["Vu's Studio", "Vu's Phone"]);
    expect(Array.from(document.querySelectorAll(".badge")).some((badge) => badge.textContent === "This device")).toBe(false);
  });
});
