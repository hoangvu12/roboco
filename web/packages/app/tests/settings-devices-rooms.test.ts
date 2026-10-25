// @vitest-environment jsdom

/**
 * The Devices settings page's room surface (rooms#05), mounted for real:
 * the "Join room" paste form appears ONLY when the engine serving this page
 * advertises `rooms-v1` (version skew degrades to absence — ADR 0011), a
 * join lands through the lib's joinRoom with the app singletons, its result
 * sentence surfaces which member labels stayed offline, and the room's
 * member list renders unjoined members as offline rows in the SAME devices
 * list (the existing offline treatment — tile, off presence-dot, meta line).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Device } from "@roboco/proto";
import { DevicesSettingsPage } from "../src/routes/settings-devices";

// ── Controllable doubles ──────────────────────────────────────────────────

function engineEntry(
  key: string,
  host: Device,
  state: "connected" | "off",
  capabilities: string[],
) {
  return {
    key,
    state,
    lastError: null,
    info: { deviceId: host.id, workspaceScope: "local", capabilities },
    generation: 1,
    chats: { rows: [] },
    spaces: { rows: [] },
    sessions: { rows: [] },
    devices: { rows: [host] },
  };
}

const h = vi.hoisted(() => {
  const NOW = 1_800_000_000_000;
  /** The DIRECT origin engine's host row — the engine serving this page. */
  const originHost: Device = {
    id: "9addd95b-827a-4acd-a050-681e53335811",
    name: "engine-serving-this-page",
    platform: "linux",
    lastSeenAt: new Date(NOW - 30_000).toISOString(),
    createdAt: new Date(NOW - 11 * 86_400_000).toISOString(),
    version: "0.3.0",
  };
  /** A second, relayed engine (a joined room member). */
  const relayHost: Device = {
    id: "6f0c31e2-4b1d-4c8e-9a3f-77d2c5a41b98",
    name: "vu-macbook-pro",
    platform: "macos",
    lastSeenAt: new Date(NOW - 45_000).toISOString(),
    createdAt: new Date(NOW - 3 * 86_400_000).toISOString(),
    version: "0.3.0",
  };
  const ORIGIN = "http://localhost:3000";
  const ORIGIN_KEY = ORIGIN;
  const RELAY_KEY = `${ORIGIN}/tailcat-relay/11111111-2222-3333-4444-555555555555`;
  const storedEngines = [
    {
      baseUrl: ORIGIN_KEY,
      credential: "session-origin",
      label: "Roboco web on Linux",
      sessionId: "grant-origin",
      pairedAt: 0,
      deviceId: originHost.id,
    },
    {
      baseUrl: RELAY_KEY,
      credential: "session-relay",
      label: "Roboco web on Linux",
      sessionId: "grant-relay",
      pairedAt: 0,
      deviceId: relayHost.id,
    },
  ];
  /** Flip the origin engine's advertised capabilities per test. */
  let originCapabilities: string[] = ["rooms-v1"];
  /** The doubled room store's snapshot, seeded per test. */
  let roomsState: { rooms: unknown[]; configurationError: string | null } = { rooms: [], configurationError: null };
  /** Store doubles the page hands to the join (identity-checked in tests). */
  const fleetStoreDouble = { redeemPairingUrl: vi.fn(), remove: vi.fn(), landRelayedGrant: vi.fn() };
  const roomStoreDouble = { recordJoin: vi.fn(), remove: vi.fn() };
  return {
    NOW,
    originHost,
    relayHost,
    ORIGIN,
    ORIGIN_KEY,
    RELAY_KEY,
    storedEngines,
    get originCapabilities(): string[] {
      return originCapabilities;
    },
    set originCapabilities(value: string[]) {
      originCapabilities = value;
    },
    get roomsState(): { rooms: unknown[]; configurationError: string | null } {
      return roomsState;
    },
    set roomsState(value: { rooms: unknown[]; configurationError: string | null }) {
      roomsState = value;
    },
    fleetStoreDouble,
    roomStoreDouble,
  };
});

vi.mock("../src/state/fleet", async (importOriginal) => {
  // Pure helpers stay real; the live fleet/registry wiring is doubled.
  const actual = await importOriginal<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleet: () => ({
      active: h.ORIGIN_KEY,
      engines: h.storedEngines,
      configurationError: null,
    }),
    useFleetRegistry: () => ({
      configurationError: null,
      engines: [
        engineEntry(h.ORIGIN_KEY, h.originHost, "connected", h.originCapabilities),
        engineEntry(h.RELAY_KEY, h.relayHost, "connected", []),
      ],
    }),
    fleetStore: h.fleetStoreDouble,
    forgetEngine: vi.fn(),
  };
});

vi.mock("../src/state/rooms", () => ({
  roomStore: h.roomStoreDouble,
  useRooms: () => h.roomsState,
}));

const joinCalls = vi.hoisted(
  () => new Array<{ invite: string; label: string; options: { fleet: unknown; rooms: unknown } }>(),
);

vi.mock("../src/lib/rooms", async (importOriginal) => {
  // Everything but the join itself stays real (the capability check, the
  // offline-row derivation, the result wording).
  const actual = await importOriginal<typeof import("../src/lib/rooms")>();
  return {
    ...actual,
    joinRoom: vi.fn(async (invite: string, label: string, options: { fleet: unknown; rooms: unknown }) => {
      joinCalls.push({ invite, label, options });
      return {
        room: {
          id: "room-id",
          name: "Home fleet",
          secret: "room-secret-value",
          origin: h.ORIGIN,
          joinedAt: 1,
          members: [
            { label: "server", address: "tcA", engineKey: h.RELAY_KEY },
            { label: "ghost", address: "tcB", engineKey: null },
          ],
        },
        joined: [{ label: "server", engine: h.storedEngines[1] }],
        failed: [{ label: "ghost", message: 'could not reach the room member "ghost" through Tailcat' }],
      };
    }),
  };
});

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => null,
}));

vi.mock("../src/state/hooks", () => ({
  useNow: () => h.NOW,
}));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => async () => {},
}));

// ── jsdom gaps the mounted page hits (settings-dialogs.test.ts's set) ─────

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

function formByPlaceholder(placeholder: string): HTMLInputElement | null {
  return document.querySelector<HTMLInputElement>(`input[placeholder="${placeholder}"]`);
}

/** Type into a React controlled input — the native-setter route React's
 *  value tracker leaves open (a bare `.value` write never re-renders). */
function typeInto(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event("input", { bubbles: true }));
}

function statusText(): string | null {
  const status = document.querySelector<HTMLElement>('[role="status"]');
  return status?.textContent ?? null;
}

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  joinCalls.length = 0;
  h.originCapabilities = ["rooms-v1"];
  h.roomsState = { rooms: [], configurationError: null };
  h.fleetStoreDouble.landRelayedGrant.mockClear();
  h.roomStoreDouble.recordJoin.mockClear();
});

/** One seeded room in the doubled room store. */
function seedRoom(members: Array<{ label: string; address: string; engineKey: string | null }>): void {
  h.roomsState = {
    rooms: [
      {
        id: "room-id",
        name: "Home fleet",
        secret: "room-secret-value",
        origin: h.ORIGIN,
        joinedAt: 1,
        members,
      },
    ],
    configurationError: null,
  };
}

describe("DevicesSettingsPage: the room join surface", () => {
  it("shows the Join room form when the origin engine advertises rooms-v1", () => {
    h.originCapabilities = ["rooms-v1", "web-client"];
    mountPage();
    const input = formByPlaceholder("Paste a roboco-room:… invite");
    expect(input).not.toBeNull();
    expect(document.body.textContent).toContain("Join room");
  });

  it("hides the room form when the origin engine lacks rooms-v1", () => {
    h.originCapabilities = ["web-client"];
    mountPage();
    expect(formByPlaceholder("Paste a roboco-room:… invite")).toBeNull();
    // The classic pair flow stays visible either way.
    expect(formByPlaceholder("Paste a pairing URL or roboco-tailcat:… invite")).not.toBeNull();
    // And no room hint text leaks in.
    expect(document.body.textContent).not.toContain("One paste reaches every member");
  });

  it("hides the room form when the origin engine carries no capability list at all", () => {
    h.originCapabilities = [];
    mountPage();
    expect(formByPlaceholder("Paste a roboco-room:… invite")).toBeNull();
  });

  it("joins on submit through the app singletons and surfaces the offline label", async () => {
    h.originCapabilities = ["rooms-v1"];
    mountPage();
    const input = formByPlaceholder("Paste a roboco-room:… invite")!;
    await act(async () => {
      typeInto(input, "roboco-room:pasted-invite-string");
    });
    const form = input.closest("form")!;
    await act(async () => {
      form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    });
    // One join call, with the pasted invite and the browser's device label.
    expect(joinCalls).toHaveLength(1);
    expect(joinCalls[0]!.invite).toBe("roboco-room:pasted-invite-string");
    expect(joinCalls[0]!.label).toContain("Roboco web on");
    // The call carried the app singletons (the fleet and room stores).
    expect(joinCalls[0]!.options.fleet).toBe(h.fleetStoreDouble);
    expect(joinCalls[0]!.options.rooms).toBe(h.roomStoreDouble);
    // The result sentence names the room, the count, and the offline label —
    // label-only, never the invite or the secret.
    const status = statusText() ?? "";
    expect(status).toContain("Home fleet");
    expect(status).toContain("ghost");
    expect(status).toContain('could not reach the room member "ghost"');
    expect(status).not.toContain("room-secret-value");
    expect(status).not.toContain("pasted-invite-string");
  });

  it("renders unjoined room members as offline rows and tags joined ones with their room", () => {
    h.originCapabilities = ["rooms-v1"];
    seedRoom([
      { label: "server", address: "tcA", engineKey: h.RELAY_KEY },
      { label: "ghost", address: "tcB", engineKey: null },
    ]);
    mountPage();
    // ghost: a device-style row with the off presence-dot, the member label
    // as its title, and the room name in the meta line.
    const row = document.querySelector<HTMLElement>("[data-room-member]");
    expect(row).not.toBeNull();
    expect(row!.textContent).toContain("ghost");
    expect(row!.textContent).toContain("Room Home fleet");
    expect(row!.textContent).toContain("Offline");
    expect(row!.querySelector(".presence-dot")!.className).toContain("presence-off");
    // server is joined: no offline row for it (its engine row is the
    // registry's), so the offline members list has exactly one row.
    expect(document.querySelectorAll("[data-room-member]")).toHaveLength(1);
    // The joined member's own host row carries its room membership in the
    // meta line — the device label the room knows it by, no new grouping.
    const hostRow = Array.from(document.querySelectorAll<HTMLElement>(".device-row")).find(
      (candidate) => candidate.textContent?.includes("vu-macbook-pro") ?? false,
    );
    expect(hostRow).toBeDefined();
    expect(hostRow!.textContent).toContain("room Home fleet · server");
  });

  it("an offline member whose engine was forgotten shows offline again", () => {
    h.originCapabilities = ["rooms-v1"];
    // The recorded engine key is not in the fleet anymore.
    seedRoom([{ label: "server", address: "tcA", engineKey: `${h.ORIGIN}/tailcat-relay/gone` }]);
    mountPage();
    const rows = document.querySelectorAll("[data-room-member]");
    expect(rows).toHaveLength(1);
    expect(rows[0]!.textContent).toContain("server");
  });

  it("no room, no offline rows", () => {
    h.originCapabilities = ["rooms-v1"];
    mountPage();
    expect(document.querySelectorAll("[data-room-member]")).toHaveLength(0);
  });
});
