// @vitest-environment jsdom

/**
 * wpn-02 — the draft footer's git chips, mounted for real (the
 * session/fleet layers doubled, the picker-card-phone.test.ts idiom):
 *
 * - Refs eager-load once per space (the `ensure_refs` cadence,
 *   pickers.rs:1524-1567) and the chips label themselves off the repo's
 *   current branch before any interaction (`selected_ref`, :2160-2183).
 * - A worktree-reuse pick flips the checkout kind to Local
 *   (:1599-1604) — the chip pair reads "Current worktree" + the bare
 *   name, never a lingering "New worktree"/"From {name}".
 * - A space switch resets the whole draft (branch, checkout kind, refs —
 *   the invalidation, pickers.rs:700-737) and reloads the new repo's
 *   refs: stale rows may never block the load.
 * - The ref popover's cursor anchors on the current branch row
 *   (`selected_ref_index`, :2144-2158), not row 0 — and re-homes when
 *   rows land late under an open popover (:1578-1585).
 * - A late resolution of the previous space's in-flight load never lands
 *   in the fresh draft (the remount drops its consumer — the cancel,
 *   pickers.rs:721-722).
 * - wpn-03: the draft footer KEEPS its placements (checkout/ref above —
 *   the adaptive stand-in; device start, project end) while the canvas
 *   chips move below, and the ref list dims while a SwitchRef runs
 *   (pickers.rs:3666).
 */

import { act, createElement, StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { RepoRef, Space } from "@roboco/proto";
import { ComposerFooter, type ComposerFooterProps } from "../src/components/composer-footer";

// ── Controllable doubles (the fleet/session layers) ─────────────────────────

const NOW = "2026-09-20T12:00:00Z";

const h = vi.hoisted(() => {
  /** Every `ListRefs` call's repoPath, in order. */
  const listRefsCalls: string[] = [];
  /** Every `SwitchRef` call's refName, in order. */
  const switchRefCalls: string[] = [];
  const refsByPath = new Map<string, RepoRef[]>();
  /** Repos whose NEXT ListRefs stays pending until released (the race probes). */
  const deferNext = new Set<string>();
  const deferredResolvers = new Map<string, (rows: RepoRef[]) => void>();
  /** Armed by a test: the NEXT SwitchRef stays pending until released (wpn-03's dim probe). */
  const deferSwitch = { armed: false, resolve: null as (() => void) | null };
  const session = {
    engine: { baseUrl: "eng-1" },
    client: {
      engineInfo: { deviceId: "dev-1" },
      async call<T>(method: string, params: Record<string, unknown>): Promise<T> {
        if (method === "ListRefs") {
          const repoPath = params.repoPath as string;
          listRefsCalls.push(repoPath);
          if (deferNext.has(repoPath)) {
            deferNext.delete(repoPath);
            return new Promise<T>((resolve) => {
              deferredResolvers.set(
                repoPath,
                (rows) => resolve(rows as T),
              );
            });
          }
          return (refsByPath.get(repoPath) ?? []) as T;
        }
        if (method === "SwitchRef") {
          switchRefCalls.push(params.refName as string);
          if (deferSwitch.armed) {
            deferSwitch.armed = false;
            return new Promise<T>((resolve) => {
              deferSwitch.resolve = () => resolve({} as T);
            });
          }
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
  return { listRefsCalls, switchRefCalls, refsByPath, deferNext, deferredResolvers, deferSwitch, session, snapshot };
});

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => h.session,
  useEngineSessions: () => new Map(),
}));

vi.mock("../src/state/fleet", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleetSnapshot: () => h.snapshot,
    useFleetRegistry: () => ({ engines: [] }),
    useFleet: () => ({ active: "eng-1", engines: [], configurationError: null }),
  };
});

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
  return { id, deviceId: "dev-1", path, gitDetected: true, createdAt: NOW };
}

const SPACE_A = space("sp-a", "/repo/alpha");
const SPACE_B = space("sp-b", "/repo/beta");

/** Ordered so the current branch sits in the MIDDLE — the anchor probe. */
const REFS_A: RepoRef[] = [
  { name: "feat/one", current: false, worktreePath: null },
  { name: "main", current: true, worktreePath: null },
  { name: "wt/nav", current: false, worktreePath: "/repo/alpha/.worktrees/nav" },
];
const REFS_B: RepoRef[] = [{ name: "trunk", current: true, worktreePath: null }];

function chat(spaceId: string): ComposerFooterProps["chat"] {
  return {
    id: "chat-1",
    branch: null,
    config: null,
    spaceId,
    cwd: null,
    deviceId: "dev-1",
  };
}

// The fleet snapshot the mocked `useFleetSnapshot` hands the footer.
h.snapshot.spaces.rows = [SPACE_A, SPACE_B];

beforeEach(() => {
  h.refsByPath.set("/repo/alpha", REFS_A);
  h.refsByPath.set("/repo/beta", REFS_B);
  h.listRefsCalls.length = 0;
  h.switchRefCalls.length = 0;
  h.deferSwitch.armed = false;
  h.deferSwitch.resolve = null;
});

afterEach(() => {
  document.body.replaceChildren();
});

// ── The mounted harness ─────────────────────────────────────────────────────

interface FooterHandle {
  rerender(next: ComposerFooterProps["chat"]): void;
  unmount(): void;
}

async function mountFooter(
  withChat: ComposerFooterProps["chat"],
  options?: { readonly strict?: boolean },
): Promise<FooterHandle> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  const render = (current: ComposerFooterProps["chat"]): void => {
    act(() => {
      root.render(
        options?.strict === true
          ? createElement(
              StrictMode,
              null,
              createElement(ComposerFooter, {
                chat: current,
                crSummary: null,
                contextUsage: null,
                harness: null,
              }),
            )
          : createElement(ComposerFooter, {
              chat: current,
              crSummary: null,
              contextUsage: null,
              harness: null,
            }),
      );
    });
  };
  render(withChat);
  await act(async () => {});
  return {
    rerender(next: ComposerFooterProps["chat"]) {
      render(next);
    },
    unmount() {
      // Dismiss any open card first: Base UI defers the portal's removal
      // to the exit animation's end — unmounting mid-exit (or open) leaves
      // a pending removal whose node the afterEach body wipe already took
      // (a jsdom NotFoundError that lands in whichever test runs next).
      act(() => {
        document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      });
      act(() => {});
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/** A chip trigger's label. */
function chipLabel(id: string): string {
  // During a label roll the span holds the outgoing copy too (roll-text);
  // the current value is the in/still span.
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

/** The one ref row the keyboard cursor highlights. */
function highlightedRow(): string | null {
  return document
    .querySelector<HTMLElement>(".menu-row-highlighted")
    ?.getAttribute("data-rb-row-key") ?? null;
}

/**
 * Opens a chip's popover and reads the positioner's placement (Base UI's
 * `data-side`/`data-align` on the OPEN positioner — the closing ones drop
 * the `data-open` marker, so sequential presses switch cards cleanly).
 */
async function openChipPlacement(
  chipId: string,
): Promise<{ readonly side: string | null; readonly align: string | null }> {
  press(document.querySelector<HTMLElement>(`#${chipId}`)!);
  await act(async () => {});
  const positioner = document.querySelector<HTMLElement>(".rb-popover-positioner[data-open]");
  expect(positioner, `${chipId}'s popover must be open`).not.toBeNull();
  return { side: positioner!.getAttribute("data-side"), align: positioner!.getAttribute("data-align") };
}

// ── The suites ──────────────────────────────────────────────────────────────

describe("ComposerFooter draft git chips (wpn-02)", () => {
  it("eager-loads refs once per space and labels the chips off the current branch", async () => {
    const handle = await mountFooter(chat("sp-a"));
    // One ListRefs for the mount — the chips read real values before any
    // interaction, never "Select ref".
    expect(h.listRefsCalls).toEqual(["/repo/alpha"]);
    expect(chipLabel("picker-branch")).toBe("main");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });

  it("a worktree-reuse pick flips the checkout kind — \"Current worktree\", no \"From\"", async () => {
    const handle = await mountFooter(chat("sp-a"));
    // Arm the bug's precondition: NewWorktree is the picked kind.
    press(document.querySelector<HTMLElement>("#picker-checkout")!);
    await pressRow("newWorktree");
    expect(chipLabel("picker-checkout")).toBe("New worktree");
    // Pick the ref that already owns a worktree.
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await pressRow("wt/nav");
    // The desktop's `config.checkout = Local` (:1599-1604): the pair reads
    // "Current worktree" + the bare name — and no SwitchRef ever ran.
    expect(chipLabel("picker-checkout")).toBe("Current worktree");
    expect(chipLabel("picker-branch")).toBe("wt/nav");
    expect(h.switchRefCalls).toEqual([]);
    handle.unmount();
  });

  it("a space switch resets the draft and reloads the new repo's refs", async () => {
    const handle = await mountFooter(chat("sp-a"));
    // Dirty the draft: a worktree pick under NewWorktree.
    press(document.querySelector<HTMLElement>("#picker-checkout")!);
    await pressRow("newWorktree");
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await pressRow("wt/nav");
    expect(chipLabel("picker-branch")).toBe("wt/nav");

    // The chat moves to another project — the folder changed under the
    // picks, so they reset and the new repo's refs load (stale rows never
    // block it — the rows.length guard the remount clears).
    handle.rerender(chat("sp-b"));
    await act(async () => {});
    // The mount's eager load, the mid-test popover open's force reload,
    // then exactly ONE load for the NEW repo — stale rows never blocked it
    // (the rows.length guard the re-key clears).
    expect(h.listRefsCalls).toEqual(["/repo/alpha", "/repo/alpha", "/repo/beta"]);
    expect(chipLabel("picker-branch")).toBe("trunk");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });

  it("the ref popover's cursor anchors on the current branch row", async () => {
    const handle = await mountFooter(chat("sp-a"));
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await act(async () => {});
    // The anchor is the CURSOR, never the selection: the current branch
    // row carries the keyboard highlight while nothing is picked (desktop
    // marks only the pick `selected`, pickers.rs:3633 — "current" gets the
    // tag, not the selection).
    const main = document.querySelector<HTMLElement>('[data-rb-row-key="main"]')!;
    expect(main.classList.contains("menu-row-highlighted")).toBe(true);
    expect(main.getAttribute("aria-selected")).toBeNull();
    // One ↓ from the anchored current row ("main", the middle row) lands
    // on the LAST row — not the second, which a row-0 anchor would give.
    act(() => {
      document
        .querySelector<HTMLElement>(".picker-key-frame")!
        .dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true, cancelable: true }));
    });
    expect(highlightedRow()).toBe("wt/nav");
    handle.unmount();
  });

  it("rows landing late under an open popover re-home the cursor to the anchor row", async () => {
    // The eager load for alpha stays pending while the popover opens —
    // the open effect anchored against an EMPTY list.
    h.deferNext.add("/repo/alpha");
    const handle = await mountFooter(chat("sp-a"));
    expect(h.listRefsCalls).toEqual(["/repo/alpha"]);
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await act(async () => {});
    // The open's force reload is absorbed by the in-flight load (one RPC
    // total — the desktop's Loading guard, pickers.rs:1531-1533).
    expect(h.listRefsCalls).toEqual(["/repo/alpha"]);

    // The rows land — the highlight re-homes to the anchor row (the
    // current branch, :1578-1585), not row 0.
    h.deferredResolvers.get("/repo/alpha")!(h.refsByPath.get("/repo/alpha")!);
    await act(async () => {});
    expect(document.querySelector<HTMLElement>('[data-rb-row-key="main"]')!.classList.contains("menu-row-highlighted")).toBe(true);
    act(() => {
      document
        .querySelector<HTMLElement>(".picker-key-frame")!
        .dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true, cancelable: true }));
    });
    expect(highlightedRow()).toBe("wt/nav");
    handle.unmount();
  });

  it("a late resolution of the previous space's in-flight load never lands in the fresh draft", async () => {
    h.deferNext.add("/repo/alpha");
    const handle = await mountFooter(chat("sp-a"));
    // The chat moves mid-flight: the re-keyed chip loads beta while
    // alpha's load is still pending.
    handle.rerender(chat("sp-b"));
    await act(async () => {});
    expect(chipLabel("picker-branch")).toBe("trunk");
    expect(h.listRefsCalls).toEqual(["/repo/alpha", "/repo/beta"]);

    // Alpha's rows resolve AFTER the switch — the unmounted chip's
    // consumer is dropped (the cancel, pickers.rs:721-722): the old
    // space's rows never leak into the new draft's labels, and no
    // re-kick fires for beta.
    h.deferredResolvers.get("/repo/alpha")!(h.refsByPath.get("/repo/alpha")!);
    await act(async () => {});
    expect(chipLabel("picker-branch")).toBe("trunk");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    expect(h.listRefsCalls).toEqual(["/repo/alpha", "/repo/beta"]);
    handle.unmount();
  });

  it("the eager load fires once under StrictMode's double effects", async () => {
    const handle = await mountFooter(chat("sp-a"), { strict: true });
    // The in-flight latch holds across setup-cleanup-setup — the stale
    // `loading` closure would race a second ListRefs.
    expect(h.listRefsCalls).toEqual(["/repo/alpha"]);
    expect(chipLabel("picker-branch")).toBe("main");
    handle.unmount();
  });

  it("opening the popover revalidates refs without double-loading on mount", async () => {
    const handle = await mountFooter(chat("sp-a"));
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await act(async () => {});
    // The open's force reload (pickers.rs:1298-1301) is the ONLY extra
    // call — the eager load ran exactly once for the space.
    expect(h.listRefsCalls.filter((repoPath) => repoPath === "/repo/alpha").length).toBe(2);
    handle.unmount();
  });

  it("a plain non-current pick under Local checks out the space folder (SwitchRef)", async () => {
    const handle = await mountFooter(chat("sp-a"));
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await pressRow("feat/one");
    // The switch path (pickers.rs:1612-1630): the RPC runs, then the pick
    // records — the chip reads the bare name under Local.
    expect(h.switchRefCalls).toEqual(["feat/one"]);
    expect(chipLabel("picker-branch")).toBe("feat/one");
    expect(chipLabel("picker-checkout")).toBe("Current checkout");
    handle.unmount();
  });
});

describe("ComposerFooter popover placement and switch dim (wpn-03)", () => {
  it("the draft footer KEEPS its placements: checkout/ref above, device start, project end", async () => {
    const handle = await mountFooter(chat("sp-a"));
    // The in-thread draft footer is the web's adaptive-above stand-in (the
    // desktop's `attach_overlay`, pickers.rs:3404-3416): the canvas chips
    // move below (wpn-03), and the footer must not follow them.
    expect(await openChipPlacement("picker-device")).toEqual({ side: "top", align: "start" });
    expect(await openChipPlacement("picker-project")).toEqual({ side: "top", align: "end" });
    expect(await openChipPlacement("picker-checkout")).toEqual({ side: "top", align: "start" });
    expect(await openChipPlacement("picker-branch")).toEqual({ side: "top", align: "start" });
    handle.unmount();
  });

  it("the ref list dims while a SwitchRef is in flight (pickers.rs:3666)", async () => {
    const handle = await mountFooter(chat("sp-a"));
    press(document.querySelector<HTMLElement>("#picker-branch")!);
    await act(async () => {});
    // At rest the list carries no dim.
    expect(document.querySelector(".picker-list[data-switching]")).toBeNull();

    // Arm the in-flight switch and pick a plain non-current ref under Local —
    // the RPC runs against the space folder and stays pending.
    h.deferSwitch.armed = true;
    await pressRow("feat/one");
    expect(h.switchRefCalls).toEqual(["feat/one"]);
    // The WHOLE list container dims while the switch runs (the desktop's
    // 0.55) — the per-row "switching…" tag rides it, never replaces it.
    const list = document.querySelector<HTMLElement>(".picker-list");
    expect(list, "the ref list must render").not.toBeNull();
    expect(list!.hasAttribute("data-switching")).toBe(true);
    // The dim value itself: the desktop's `opacity(0.55)` (cwd is the
    // app package root under vitest; jsdom rewrites import.meta.url).
    const css = readFileSync(join(process.cwd(), "src/styles/app.css"), "utf8");
    expect(css).toMatch(/\.picker-list\[data-switching\]\s*\{[^}]*opacity:\s*0\.55/);

    // The switch resolves: the pick records and the popover closes.
    await act(async () => {
      h.deferSwitch.resolve!();
    });
    expect(chipLabel("picker-branch")).toBe("feat/one");
    handle.unmount();
  });
});
