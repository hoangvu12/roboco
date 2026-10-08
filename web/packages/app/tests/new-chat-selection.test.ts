// @vitest-environment jsdom

/**
 * wpn-90 — the canvas's unresolved target (zeron #526's `f180fcb1`):
 *
 * A remembered project whose live row is absent — deleted from its owning
 * engine, or still loading into the merged fleet snapshot — must never be
 * relabeled "No project" (the pick is still SCOPED to the owner, so a send
 * would otherwise mint the chat on a substitute target: the stub's device
 * id). The target row keeps the selected identity, the chip says which
 * state it is in, and the page folds the gate into the composer:
 *
 * - `useNewThreadTarget` reports `targetUnavailable` (plus the
 *   `projectUnavailable` / `projectLoading` split: a loaded project list
 *   with the row missing means "gone", anything else means "still
 *   loading").
 * - The chat page resolves the target BEFORE catalog loading, keeps the
 *   scoped project id on the stub chat, and passes `targetUnavailable` to
 *   the composer — no harness discovery while unresolved.
 * - A canvas whose owner engine has no routed session renders the explicit
 *   "Engine unavailable" state even when another engine's cached chat rows
 *   are loaded (the merged snapshot alone must not mask a dead owner).
 *
 * The chips/cards and the `composerDefaults`/`sidebarStore` singletons run
 * for real; the fleet/session/router/composer layers are doubled narrowly
 * (the idiom of browser-engine-picker.test.ts, wpn-88's distillate of
 * zeron's new-chat-selection suite).
 */

import { act, createElement, type ReactElement, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { encodeScopedId } from "@roboco/engine-client";
import type { Chat, Device, Space } from "@roboco/proto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { NewThreadTargetSelectors } from "../src/components/composer/new-thread-selectors";
import { composerDefaults } from "../src/lib/composer-draft";
import { sidebarStore } from "../src/state/sidebar";

// ── Controllable doubles (fleet/session/router/composer) ───────────────────

const h = vi.hoisted(() => ({
  now: 1_800_000_000_000,
  snapshot: null as unknown,
  session: null as unknown,
  active: null as string | null,
  registry: { engines: [] as Array<{ key: string; state: string }> },
}));

// The real `engineStatesOf` stays: device presence reads the registry.
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
  useEngineSessions: () => new Map(),
}));

vi.mock("../src/state/hooks", () => ({
  useNow: () => h.now,
  useWatchSnapshot: () => null,
  useEngineStatus: () => ({ state: "connected" }),
}));

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => createElement("a", null, children),
  useNavigate: () => () => Promise.resolve(),
  useRouterState: (options: { select: (state: { location: { pathname: string } }) => unknown }) =>
    options.select({ location: { pathname: "/" } }),
}));

// The composer stands in as an observation point for the page's gate: the
// real submission gate is composer-availability.test.ts's mount.
vi.mock("../src/components/composer", () => ({
  Composer: ({
    session,
    chat,
    targetUnavailable = false,
  }: {
    session: { client: { state: string } };
    chat: { spaceId?: string | null };
    targetUnavailable?: boolean;
  }) =>
    createElement("div", {
      "data-composer-session-state": session.client.state,
      "data-composer-space-id": chat.spaceId ?? "",
      "data-composer-target-unavailable": String(targetUnavailable),
    }),
}));

vi.mock("../src/routes/index-page", () => ({ NewThreadCanvas: () => null }));

// The xterm-backed terminal layers stay out of the jsdom graph.
vi.mock("../src/terminal/store", () => ({
  drawerTerminalStore: {
    subscribe: () => () => {},
    getVersion: () => 0,
    stateFor: () => ({ open: false }),
    toggle: () => {},
    hideDrawerEntry: () => {},
  },
}));
vi.mock("../src/terminal/terminal-dock", () => ({ TerminalDock: () => null }));

// ── jsdom gaps the mounted page hits ────────────────────────────────────────

beforeAll(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  // Desktop-width throughout: the target policy is viewport-free (the
  // phone sheet's own behavior lives in picker-card-phone's suite).
  window.matchMedia = (() => ({
    matches: false,
    media: "",
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
  HTMLImageElement.prototype.decode ??= () => Promise.resolve();
  HTMLCanvasElement.prototype.getContext = () => null;
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
};

const projectA: Space = {
  id: PROJECT_A,
  deviceId: DEVICE_A,
  name: "Project A",
  path: "/workspace/project-a",
  gitDetected: false,
  createdAt: new Date(h.now).toISOString(),
};

const cachedEngineAChat: Chat = {
  id: encodeScopedId(ENGINE_A_KEY, "cached-chat"),
  deviceId: DEVICE_A,
  title: "Cached engine A chat",
  archived: false,
  cwd: null,
  branch: null,
  checkoutId: null,
  config: null,
  lastMessagePreview: null,
  lastMessageAt: null,
  createdAt: new Date(h.now).toISOString(),
};

/** The merged fleet snapshot; the project row set is per-test. */
function fleetSnapshot(options: { spaces: Space[]; spacesLoaded: boolean; chats?: Chat[] }): unknown {
  return {
    generation: 1,
    capabilities: [],
    devices: { rows: [deviceA, deviceB], loaded: true, error: null },
    // Engine A's project row is present and loaded; engine B's is the
    // cross-engine row missing from the owner's cache.
    spaces: { rows: options.spaces, loaded: options.spacesLoaded, error: null },
    chats: { rows: options.chats ?? [cachedEngineAChat], loaded: true, error: null },
    statuses: { rows: [], loaded: true, error: null },
    connectivity: { value: null, loaded: false, error: null },
  };
}

/** Engine B is the routed session (the remembered project's owner). */
function engineBSession(catalogLoad: ReturnType<typeof vi.fn>): unknown {
  return {
    engine: { baseUrl: ENGINE_B_KEY, label: "Engine B", credential: "cred" },
    client: {
      state: "connected",
      status: { state: "connected", info: { deviceId: "device-b" } },
      engineInfo: { deviceId: "device-b", capabilities: [] },
    },
    catalog: { loadHarnesses: catalogLoad },
    transcripts: {},
  };
}

let catalogLoad: ReturnType<typeof vi.fn>;
let ConversationPage: (typeof import("../src/routes/chat-page"))["ConversationPage"];

beforeAll(async () => {
  ConversationPage = (await import("../src/routes/chat-page")).ConversationPage;
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

/** Re-render the page (a fresh element) with the current doubles. */
function rerender(): void {
  act(() => root!.render(createElement(ConversationPage)));
}

function unmount(): void {
  act(() => root?.unmount());
  root = null;
  host?.remove();
  host = null;
}

afterEach(() => {
  unmount();
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

/** A data attribute the Composer stub publishes (the page's gate wiring). */
function composerAttr(mounted: HTMLDivElement, attr: string): string {
  return (
    mounted
      .querySelector<HTMLElement>(`[data-composer-${attr}]`)
      ?.getAttribute(`data-composer-${attr}`) ?? ""
  );
}

// ── The canvas target row keeps the unresolved pick visible ─────────────────

describe("unavailable canvas targets", () => {
  beforeEach(() => {
    h.active = ENGINE_A_KEY;
    h.registry.engines = [
      { key: ENGINE_A_KEY, state: "connected" },
      { key: ENGINE_B_KEY, state: "connected" },
    ];
    // The remembered pick is engine B's project; the merged snapshot holds
    // engine A's rows only — the cross-engine project row is missing.
    h.snapshot = fleetSnapshot({ spaces: [projectA], spacesLoaded: true });
    h.session = engineBSession((catalogLoad = vi.fn(async () => {})));
    composerDefaults.update({ device: DEVICE_B, project: PROJECT_B, noProject: false });
  });

  it("keeps a missing selected project visible instead of relabeling it as no project", () => {
    const mounted = mount(createElement(NewThreadTargetSelectors));
    expect(chipLabel("picker-project")).toBe("Selected project unavailable");
    // The pick itself is untouched — still scoped to the owning engine.
    expect(composerDefaults.getSnapshot()).toMatchObject({
      device: DEVICE_B,
      project: PROJECT_B,
      noProject: false,
    });
    expect(chipLabel("picker-device")).toContain("Engine B");
  });

  it("keeps a selected project loading distinct from no project", () => {
    // The owner engine's project list has not landed yet — still a blocked
    // target, but never "gone", and never "No project".
    h.snapshot = fleetSnapshot({ spaces: [projectA], spacesLoaded: false });
    mount(createElement(NewThreadTargetSelectors));
    expect(chipLabel("picker-project")).toBe("Selected project loading");
  });

  it("still reads No project for a deliberate projectless pick", () => {
    composerDefaults.update({ device: DEVICE_B, project: null, noProject: true });
    h.snapshot = fleetSnapshot({ spaces: [projectA], spacesLoaded: true });
    mount(createElement(NewThreadTargetSelectors));
    expect(chipLabel("picker-project")).toBe("No project");
  });

  it("keeps the persistent composer mounted with an explicit unresolved-target submission gate", () => {
    const mounted = mount(createElement(ConversationPage));
    // The composer stays mounted (the draft is preserved), but the page
    // hands it the blocked target and keeps the scoped identity on the
    // stub chat — a send can never fall back to the device id.
    expect(composerAttr(mounted, "session-state")).toBe("connected");
    expect(composerAttr(mounted, "target-unavailable")).toBe("true");
    expect(composerAttr(mounted, "space-id")).toBe(PROJECT_B);
    expect(mounted.textContent).toContain(
      "Selected project unavailable. Choose another project before sending.",
    );
    // No harness discovery on a substitute engine while unresolved.
    expect(catalogLoad).not.toHaveBeenCalled();
  });

  it("waits for a loading project before discovery, then resumes once the row lands", () => {
    h.snapshot = fleetSnapshot({ spaces: [projectA], spacesLoaded: false });
    const mounted = mount(createElement(ConversationPage));
    expect(composerAttr(mounted, "target-unavailable")).toBe("true");
    expect(mounted.textContent).toContain(
      "Selected project loading. Wait for it to resolve before sending.",
    );
    expect(catalogLoad).not.toHaveBeenCalled();

    // The owner engine's row lands: the gate lifts and discovery proceeds.
    h.snapshot = fleetSnapshot({
      spaces: [
        projectA,
        { ...projectA, id: PROJECT_B, deviceId: DEVICE_B, name: "Project B", path: "/b/project-b" },
      ],
      spacesLoaded: true,
    });
    rerender();
    expect(composerAttr(mounted, "target-unavailable")).toBe("false");
    expect(mounted.textContent).not.toContain("Selected project");
    expect(catalogLoad).toHaveBeenCalled();
  });

  it("renders an unavailable canvas target even when another engine has cached chat rows", () => {
    // The owner engine's session is not routed (not mounted / gone), while
    // engine A's cached chat rows keep the merged snapshot loaded — the
    // cached rows must not mask the dead owner with a half-rendered canvas.
    h.session = null;
    const mounted = mount(createElement(ConversationPage));
    expect(mounted.textContent).toContain(
      "Engine unavailable. Sending is disabled until the host connects.",
    );
    expect(mounted.querySelector("[data-composer-session-state]")).toBeNull();
  });
});
