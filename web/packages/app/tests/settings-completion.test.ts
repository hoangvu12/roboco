// @vitest-environment jsdom

/**
 * Ticket 10 — the Shortcuts page's Composer completion section (desktop
 * settings/completion.rs, upstream 13cb6d7c): one card per ACTIVE agent
 * (offeredHarnesses — the composer's own installed + enabled gate — pinned
 * to the settings order), two toggles per agent, a shared Restore defaults
 * that clears the per-harness overrides, and the loading / no-engine /
 * failure states.
 *
 * The mounted-suite idiom (settings-dialogs.test.ts / account-row.test.ts):
 * the REAL section mounts with the REAL ui-settings store and RbSwitch
 * (Base UI runs for real in jsdom), while the engine session is doubled
 * narrowly — a scripted client whose `call` answers ListHarnesses. No JSX
 * (createElement), per-file jsdom pragma only.
 */

import { act, createElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { HarnessDescriptor } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { CompletionSection } from "../src/routes/settings-shortcuts";
import { uiSettings } from "../src/state/ui-settings";
import type { EngineSession } from "../src/state/engine-session";

// ── Controllable doubles ──────────────────────────────────────────────────

const h = vi.hoisted(() => {
  /** The scripted engine client — records calls, answers ListHarnesses. */
  const client = {
    calls: [] as { method: string; params: unknown }[],
    reply: null as HarnessDescriptor[] | null,
    fail: false,
    call<T>(method: string, params: unknown): Promise<T> {
      h.client.calls.push({ method, params });
      if (h.client.fail) {
        return Promise.reject(new Error("engine offline"));
      }
      return Promise.resolve((h.client.reply ?? []) as T);
    },
  };

  /** The routed session — only `client` is read on this path. */
  let session: EngineSession | null = null;

  return {
    client,
    setSession(next: EngineSession | null): void {
      session = next;
    },
    getSession(): EngineSession | null {
      return session;
    },
  };
});

vi.mock("../src/state/session-provider", () => ({
  // Only `client` is read by the completion section's loader; the real
  // provider's notification machinery is unrelated to this contract.
  useEngineSession: () => h.getSession(),
  useEngineSessions: () => new Map(),
  useEngineRetry: () => {},
  EngineSessionProvider: () => null,
}));

// ── jsdom gaps the mounted section hits (the standard stub set) ────────────

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

// ── The mounted section harness ────────────────────────────────────────────

interface Mounted {
  readonly container: HTMLDivElement;
  unmount(): void;
}

const mounted: Mounted[] = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  h.setSession(null);
  h.client.reply = null;
  h.client.fail = false;
  h.client.calls.length = 0;
  uiSettings.updateImmediate({ skillCompletionByHarness: {}, skillsInSlashMenu: false });
  document.body.replaceChildren();
});

function descriptor(fields: Partial<HarnessDescriptor>): HarnessDescriptor {
  return {
    id: "claude-code",
    name: "Claude Code",
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed: true,
    canInstall: false,
    ...fields,
  };
}

/** The desktop's active-agents fixture (completion.rs's test list). */
const CATALOG: HarnessDescriptor[] = [
  descriptor({ id: "opencode", name: "OpenCode", enabled: true }),
  descriptor({ id: "cursor", name: "Cursor", enabled: false }),
  descriptor({ id: "devin", name: "Devin", enabled: true, installed: false }),
  descriptor({ id: "codex", name: "Codex", enabled: true }),
  descriptor({ id: "claude-code", name: "Claude Code", enabled: null }),
  descriptor({ id: "grok", name: "Grok", enabled: null, installed: false }),
  descriptor({ id: "mock", name: "Mock", enabled: true }),
];

function mountSection(): Mounted {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(CompletionSection));
  });
  const handle: Mounted = {
    container,
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
  mounted.push(handle);
  return handle;
}

/** Every element whose accessible name contains `name`. */
function named(container: HTMLElement, name: string): HTMLElement[] {
  return Array.from(container.querySelectorAll("button, [role='switch']")).filter((element) =>
    (element.getAttribute("aria-label") ?? element.textContent ?? "").includes(name),
  ) as HTMLElement[];
}

async function mountReady(): Promise<Mounted> {
  h.client.reply = CATALOG;
  h.setSession({ client: h.client } as unknown as EngineSession);
  const handle = mountSection();
  // The loader's ListHarnesses promise resolves through an act boundary.
  await act(async () => {});
  return handle;
}

// ── The contract ───────────────────────────────────────────────────────────

describe("Shortcuts → Composer completion (settings/completion.rs, upstream 13cb6d7c)", () => {
  it("lists only active agents, in settings order, from ListHarnesses", async () => {
    const handle = await mountReady();
    expect(h.client.calls).toEqual([{ method: methods.LIST_HARNESSES, params: {} }]);
    // Settings order (SKILL_COMPLETION_HARNESSES), not catalog order; only
    // installed-and-enabled rows; the disabled/uninstalled/mock rows stay out.
    const titles = Array.from(handle.container.querySelectorAll(".settings-row-title")).map(
      (node) => node.textContent,
    );
    expect(titles).toEqual([
      "Claude Code",
      "Use $ for skills",
      "Separate / commands",
      "Codex",
      "Use $ for skills",
      "Separate / commands",
      "OpenCode",
      "Use $ for skills",
      "Separate / commands",
    ]);
  });

  it("toggles save locally and independently per agent", async () => {
    const handle = await mountReady();
    // Claude Code's `$` and separation flip; OpenCode's `$` alone flips.
    act(() => {
      named(handle.container, "Claude Code: Use $ for skills, off")[0]!.click();
    });
    act(() => {
      named(handle.container, "Claude Code: Separate / commands, off")[0]!.click();
    });
    act(() => {
      named(handle.container, "OpenCode: Use $ for skills, off")[0]!.click();
    });
    const stored = uiSettings.getSnapshot();
    expect(stored.skillCompletionByHarness["claude-code"]).toEqual({
      dollar: true,
      separateFromSlash: true,
    });
    expect(stored.skillCompletionByHarness["opencode"]).toEqual({
      dollar: true,
      separateFromSlash: false,
    });
    expect(stored.skillCompletionByHarness["codex"]).toBeUndefined();
    // The toggles re-render from the store: all three `$` rows now read on
    // (Codex's was already on — its defaults are native).
    expect(named(handle.container, "Use $ for skills, on")).toHaveLength(3);
  });

  it("Restore defaults appears once customized and clears the overrides", async () => {
    const handle = await mountReady();
    expect(named(handle.container, "Restore composer completion defaults")).toHaveLength(0);
    act(() => {
      named(handle.container, "Claude Code: Use $ for skills, off")[0]!.click();
    });
    const restore = named(handle.container, "Restore composer completion defaults")[0]!;
    expect(restore).toBeDefined();
    act(() => {
      restore.click();
    });
    expect(uiSettings.getSnapshot().skillCompletionByHarness).toEqual({});
    // Back to the defaults: Claude Code and OpenCode off, Codex natively on.
    expect(named(handle.container, "Use $ for skills, off")).toHaveLength(2);
    expect(named(handle.container, "Use $ for skills, on")).toHaveLength(1);
  });

  it("no engine: the connect prompt instead of a retry", () => {
    h.setSession(null);
    const handle = mountSection();
    expect(handle.container.textContent).toContain(
      "Connect this device to load its active agents.",
    );
    expect(named(handle.container, "Retry loading active agents")).toHaveLength(0);
    expect(h.client.calls).toEqual([]);
  });

  it("a failed catalog load surfaces the failure and retries on demand", async () => {
    h.setSession({ client: h.client } as unknown as EngineSession);
    h.client.fail = true;
    const handle = mountSection();
    await act(async () => {});
    expect(handle.container.textContent).toContain("Unable to load active agents.");
    expect(h.client.calls).toHaveLength(1);
    // Retry re-asks; the recovered catalog repaints the rows.
    h.client.fail = false;
    h.client.reply = CATALOG;
    act(() => {
      named(handle.container, "Retry loading active agents")[0]!.click();
    });
    await act(async () => {});
    expect(h.client.calls).toHaveLength(2);
    expect(handle.container.querySelectorAll(".settings-row-title").length).toBe(9);
  });
});
