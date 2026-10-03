// @vitest-environment jsdom

/**
 * wpn-02 — the new-thread canvas's git selectors, mounted for real (the
 * terminal store, the session, and the fleet doubled; the remembered
 * target driven through the real `composerDefaults` store):
 *
 * - Refs eager-load once per remembered project (`ensure_refs`,
 *   pickers.rs:1524-1567) and the chips label themselves off the repo's
 *   current branch before any interaction.
 * - Switching the remembered project resets the whole draft — the pick,
 *   the checkout kind, the refs, and the chip's own per-ref switch state
 *   (the space/device invalidation, pickers.rs:700-737) — and reloads the
 *   new repo's refs: stale rows may never block the load.
 * - The no-project phase unmounts the row; the project that lands after
 *   it starts from a clean draft, not the one picked before it.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { encodeScopedId } from "@roboco/engine-client";
import type { RepoRef, Space } from "@roboco/proto";
import { NewThreadGitSelectors } from "../src/components/composer/new-thread-selectors";
import { composerDefaults, rememberTarget } from "../src/lib/composer-draft";

// ── Controllable doubles (the terminal/session/fleet layers) ────────────────

const NOW = "2026-09-20T12:00:00Z";
/** The connected engine's own device, SCOPED like the merged fleet rows. */
const OWN_DEVICE = encodeScopedId("eng-1", "dev-1");

const h = vi.hoisted(() => {
  /** Every `ListRefs` call's repoPath, in order. */
  const listRefsCalls: string[] = [];
  const refsByPath = new Map<string, RepoRef[]>();
  const session = {
    engine: { baseUrl: "eng-1" },
    client: {
      engineInfo: { deviceId: "dev-1" },
      async call<T>(method: string, params: Record<string, unknown>): Promise<T> {
        if (method === "ListRefs") {
          const repoPath = params.repoPath as string;
          listRefsCalls.push(repoPath);
          return (refsByPath.get(repoPath) ?? []) as T;
        }
        if (method === "SwitchRef") {
          return {} as T;
        }
        throw new Error(`unexpected method: ${method}`);
      },
    },
  };
  const snapshot = {
    generation: 1,
    capabilities: [],
    spaces: { rows: [] as Space[], loaded: true, error: null },
    devices: { rows: [] as Space[], loaded: true, error: null },
  };
  return { listRefsCalls, refsByPath, session, snapshot };
});

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => h.session,
  useEngineSessions: () => new Map(),
}));

vi.mock("../src/state/fleet", () => ({
  useFleetSnapshot: () => h.snapshot,
}));

// The xterm-backed store never imports here (nothing mounts the terminal
// action row); a namesake stub keeps the module graph jsdom-clean.
vi.mock("../src/terminal/store", () => ({
  drawerTerminalStore: {
    subscribe: () => () => {},
    getVersion: () => 0,
    stateFor: () => ({ open: false }),
    toggle: () => {},
  },
}));

// ── jsdom gaps the mounted card hits ────────────────────────────────────────

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

// ── Fixtures ───────────────────────────────────────────────────────────────

function space(id: string, path: string): Space {
  return { id, deviceId: OWN_DEVICE, path, gitDetected: true, createdAt: NOW };
}

const SPACE_A = space("sp-a", "/repo/alpha");
const SPACE_B = space("sp-b", "/repo/beta");

const REFS_A: RepoRef[] = [
  { name: "main", current: true, worktreePath: null },
  { name: "wt/nav", current: false, worktreePath: "/repo/alpha/.worktrees/nav" },
];
const REFS_B: RepoRef[] = [{ name: "trunk", current: true, worktreePath: null }];

h.snapshot.spaces.rows = [SPACE_A, SPACE_B];

beforeEach(() => {
  h.refsByPath.set("/repo/alpha", REFS_A);
  h.refsByPath.set("/repo/beta", REFS_B);
  h.listRefsCalls.length = 0;
});

afterEach(() => {
  document.body.replaceChildren();
  composerDefaults.update({ device: null, project: null, noProject: false });
});

// ── The mounted harness ─────────────────────────────────────────────────────

interface CanvasHandle {
  unmount(): void;
}

async function mountCanvas(): Promise<CanvasHandle> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  act(() => {
    root.render(createElement(NewThreadGitSelectors));
  });
  await act(async () => {});
  return {
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/** A chip trigger's label. */
function chipLabel(id: string): string {
  return document.querySelector<HTMLElement>(`#${id} .footer-menu-chip-label`)?.textContent ?? "";
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

// ── The suites ──────────────────────────────────────────────────────────────

describe("NewThreadGitSelectors (wpn-02)", () => {
  it("eager-loads refs once per remembered project and labels the current branch", async () => {
    rememberTarget(null, "sp-a", false);
    const handle = await mountCanvas();
    expect(h.listRefsCalls).toEqual(["/repo/alpha"]);
    expect(chipLabel("picker-branch")).toBe("main");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });

  it("switching the remembered project resets the draft and reloads the new repo's refs", async () => {
    rememberTarget(null, "sp-a", false);
    const handle = await mountCanvas();
    // Dirty the draft: a worktree-reuse pick (branch + checkout Local).
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await pressRow("wt/nav");
    expect(chipLabel("picker-branch")).toBe("wt/nav");
    expect(chipLabel("picker-checkout")).toBe("Current worktree");

    // The remembered project moves — the folder changed under the picks,
    // so they reset and the new repo's refs load (stale rows never block).
    act(() => {
      rememberTarget(null, "sp-b", false);
    });
    await act(async () => {});
    expect(h.listRefsCalls).toEqual(["/repo/alpha", "/repo/alpha", "/repo/beta"]);
    expect(chipLabel("picker-branch")).toBe("trunk");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });

  it("the no-project phase unmounts the row; the next project starts clean", async () => {
    rememberTarget(null, "sp-a", false);
    const handle = await mountCanvas();
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await pressRow("wt/nav");
    expect(chipLabel("picker-branch")).toBe("wt/nav");

    // "Don't work in a project" — the git row collapses with the target.
    act(() => {
      rememberTarget(null, null, true);
    });
    await act(async () => {});
    expect(document.querySelector(".new-thread-git-selectors")).toBeNull();

    // A project lands again — from the EMPTY draft, never the stale pick.
    act(() => {
      rememberTarget(null, "sp-b", false);
    });
    await act(async () => {});
    expect(chipLabel("picker-branch")).toBe("trunk");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });
});
