// @vitest-environment jsdom

/**
 * The Devices page must render ONE row per engine (zeron 779cc2e0 / PR
 * #526). The pairing-era "Engines" card (ticket 45's folded drawer row)
 * used to list the fleet's engines again above the device rows, so every
 * engine appeared twice — and its rows named engines by their pair-session
 * label ("Roboco web on …"), not the device row of record. The engines
 * card is gone: the device-style host rows (one per engine, picked by
 * `fleetDeviceRows`) are the registry of record, and Roboco's pairing URL
 * box — where zeron shows a WorkOS sign-in hint — keeps its 16px gap
 * before the list.
 *
 * The REAL page mounts (the mounted-suite idiom: jsdom, act, the real
 * Icon components run). The fleet/registry/session layers are doubled
 * narrowly — exactly two engines over one device each, the multi-engine
 * acceptance shape. No JSX (createElement), per-file jsdom pragma.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Device } from "@roboco/proto";
import { DevicesSettingsPage } from "../src/routes/settings-devices";

// ── Controllable doubles ──────────────────────────────────────────────────

/** One registry entry: its key, its own host device row, its live state. */
function engineEntry(
  key: string,
  host: Device,
  state: "connected" | "off",
  lastError: string | null,
) {
  return {
    key,
    state,
    lastError,
    info: { deviceId: host.id, workspaceScope: "synced", capabilities: [] },
    generation: 1,
    chats: { rows: [] },
    spaces: { rows: [] },
    sessions: { rows: [] },
    devices: { rows: [host] },
  };
}

const h = vi.hoisted(() => {
  const NOW = 1_800_000_000_000;

  /** Engine A's host device — the ACTIVE engine's own row of record. */
  const hostA: Device = {
    id: "9addd95b-827a-4acd-a050-681e53335811",
    name: "threaderipper-server-nvme",
    platform: "linux",
    lastSeenAt: new Date(NOW - 30_000).toISOString(),
    createdAt: new Date(NOW - 11 * 86_400_000).toISOString(),
    version: "0.2.84",
  };

  /** Engine B's host device — the second paired engine's own row. */
  const hostB: Device = {
    id: "6f0c31e2-4b1d-4c8e-9a3f-77d2c5a41b98",
    name: "vu-macbook-pro",
    platform: "macos",
    lastSeenAt: new Date(NOW - 45_000).toISOString(),
    createdAt: new Date(NOW - 3 * 86_400_000).toISOString(),
    version: "0.2.84",
  };

  /**
   * The pairing store's persisted engines — the `label` each carries is
   * the pair-session label (`webDeviceLabel()`, "Roboco web on …"), which
   * the host rows must never render: the device row is the name of record.
   */
  const storedEngines = [
    {
      baseUrl: "https://engine-a.test",
      credential: "session-a",
      label: "Roboco web on Linux",
      sessionId: "grant-a",
      pairedAt: 0,
      deviceId: hostA.id,
    },
    {
      baseUrl: "https://engine-b.test",
      credential: "session-b",
      label: "Roboco web on macOS",
      sessionId: "grant-b",
      pairedAt: 0,
      deviceId: hostB.id,
    },
  ];

  /** Engine B's live entry — flipped to parked by the row-actions test. */
  let engineBState: "connected" | "off" = "connected";
  let engineBError: string | null = null;

  /** The active engine's session — only the rename RPC's client is read. */
  const session = { client: { call: async () => ({}) } };

  /** The navigate double's record ("Pair again" routes through /pair). */
  const navigateCalls: Array<{ to: string }> = [];

  /** The fleet-store forget double's record. */
  const forgetEngine = vi.fn();

  return {
    NOW,
    hostA,
    hostB,
    storedEngines,
    session,
    navigateCalls,
    forgetEngine,
    get engineBState(): "connected" | "off" {
      return engineBState;
    },
    set engineBState(value: "connected" | "off") {
      engineBState = value;
    },
    get engineBError(): string | null {
      return engineBError;
    },
    set engineBError(value: string | null) {
      engineBError = value;
    },
  };
});

vi.mock("../src/state/fleet", async (importOriginal) => {
  // Pure helpers (`engineStatesOf`, `fleetLocalDeviceId`) stay real; only
  // the live fleet/registry/state wiring is doubled.
  const actual = await importOriginal<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleet: () => ({
      active: "https://engine-a.test",
      engines: h.storedEngines,
      configurationError: null,
    }),
    useFleetRegistry: () => ({
      configurationError: null,
      engines: [
        engineEntry("https://engine-a.test", h.hostA, "connected", null),
        engineEntry("https://engine-b.test", h.hostB, h.engineBState, h.engineBError),
      ],
    }),
    fleetStore: { redeemPairingUrl: vi.fn(async () => {}), remove: vi.fn() },
    forgetEngine: h.forgetEngine,
  };
});

vi.mock("../src/state/session-provider", () => ({
  // The session owns the rename RPC; the registry owns the rows.
  useEngineSession: () => h.session,
}));

vi.mock("../src/state/hooks", () => ({
  useNow: () => h.NOW,
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
  h.engineBState = "connected";
  h.engineBError = null;
  h.navigateCalls.length = 0;
  h.forgetEngine.mockReset();
});

function mountPage(): HTMLElement {
  const container = document.createElement("div");
  document.body.appendChild(container);
  let root: Root | null = createRoot(container);
  mounted.push(() => {
    act(() => {
      root?.unmount();
    });
    root = null;
    container.remove();
  });
  act(() => {
    root!.render(createElement(DevicesSettingsPage));
  });
  return container;
}

/** Every section header's title, in DOM order. */
function sectionTitles(): string[] {
  return Array.from(document.querySelectorAll<HTMLHeadingElement>(".settings-section-header h2")).map(
    (header) => header.textContent ?? "",
  );
}

/** A row's buttons (the id chip is a button too — filter by text). */
function rowButtons(row: HTMLElement): HTMLButtonElement[] {
  return Array.from(row.querySelectorAll<HTMLButtonElement>("button"));
}

// ── One row per engine ─────────────────────────────────────────────────────

describe("DevicesSettingsPage — one row per engine", () => {
  it("renders the two engines' device rows of record, not an engines card above them", () => {
    const page = mountPage();

    // The bug this page must never regress to: the pairing-era Engines
    // card listed every fleet engine again (a bare settings-row per
    // engine) above the device rows — two paired engines rendered four.
    const rows = page.querySelectorAll(".settings-row");
    expect(rows.length).toBe(2);

    // Every row is a device row: the platform tile with its corner
    // presence dot, the name of record, the meta line, the id chip.
    for (const row of Array.from(rows)) {
      expect(row.classList.contains("device-row")).toBe(true);
      expect(row.querySelector(".row-tile .presence-dot")).not.toBeNull();
      expect(row.querySelector(".id-chip")).not.toBeNull();
    }
    expect(
      Array.from(page.querySelectorAll(".settings-row-title")).map((title) => title.textContent),
    ).toEqual(["threaderipper-server-nvme", "vu-macbook-pro"]);

    // The desktop's two-section split stays: the active engine's host row
    // under "This device", the second engine's under "Other devices".
    expect(sectionTitles()).toEqual(["This device", "Other devices"]);

    // The active engine's host row is badged; only one badge exists.
    expect(Array.from(page.querySelectorAll(".badge")).map((badge) => badge.textContent)).toEqual([
      "Active engine",
    ]);

    // The pair-session labels never render — no engines card block, and
    // no label fallback on the host rows ("Roboco web on …" is the label
    // the pairing store stamps at pair time).
    expect(page.textContent).not.toContain("Roboco web on");
  });

  it("keeps the engine actions on a parked engine's host row — Pair again routes to /pair, Forget removes the engine", async () => {
    h.engineBState = "off";
    h.engineBError = "session revoked";
    const page = mountPage();

    const row = Array.from(page.querySelectorAll<HTMLElement>(".device-row")).find(
      (candidate) => candidate.querySelector(".settings-row-title")?.textContent === "vu-macbook-pro",
    );
    expect(row).toBeDefined();

    // The parked connection label rides the row's own meta line.
    expect(row!.querySelector(".settings-meta-line")?.textContent).toContain("Session revoked");

    // "Pair again" re-enters the pairing flow at /pair.
    const pairAgain = rowButtons(row!).find((button) => button.textContent === "Pair again");
    expect(pairAgain).toBeDefined();
    await act(async () => {
      pairAgain!.click();
    });
    expect(h.navigateCalls).toEqual([{ to: "/pair" }]);

    // Forget confirms in place, then removes the engine from the fleet.
    const forget = rowButtons(row!).find((button) => button.textContent === "Forget");
    expect(forget).toBeDefined();
    await act(async () => {
      forget!.click();
    });
    const confirm = rowButtons(row!).find((button) => button.textContent === "Forget?");
    expect(confirm).toBeDefined();
    await act(async () => {
      confirm!.click();
    });
    expect(h.forgetEngine).toHaveBeenCalledWith("https://engine-b.test");
  });
});
