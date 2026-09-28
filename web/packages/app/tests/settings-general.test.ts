// @vitest-environment jsdom

/**
 * Ticket 26 — the General page (the conversation-behavior page the desktop's
 * modal redesign added, upstream b782d043): the send-behavior control, the
 * compact-mode toggle, and the Escape toggle that moved off the web's
 * Shortcuts and Appearance pages, plus the thread-naming card. The mounted
 * idiom follows settings-completion.test.ts: the REAL page components over
 * the REAL ui-settings store with RbSwitch and the segmented control, while
 * the engine session is doubled narrowly — a scripted client whose `call`
 * answers GetTitleSettings / SetTitleSettings / ListHarnesses / ListModels.
 *
 * The moved-off regression rides the same harness: the Shortcuts page no
 * longer renders the send or Escape rows (they live on General now).
 */

import { act, createElement, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { HarnessDescriptor, TitleSettings } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { GeneralSettingsPage } from "../src/routes/settings-general";
import { ShortcutsSettingsPage } from "../src/routes/settings-shortcuts";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";
import type { EngineSession } from "../src/state/engine-session";

// ── Controllable doubles ──────────────────────────────────────────────────

const h = vi.hoisted(() => {
  /** The scripted engine client — records calls, answers the title RPCs. */
  const client = {
    calls: [] as { method: string; params: unknown }[],
    titles: null as TitleSettings | null,
    titlesFail: false,
    call<T>(method: string, params: unknown): Promise<T> {
      h.client.calls.push({ method, params });
      if (method === "GetTitleSettings") {
        if (h.client.titlesFail) {
          return Promise.reject(new Error("engine offline"));
        }
        return Promise.resolve((h.client.titles ?? { harness: null, model: null }) as T);
      }
      if (method === "SetTitleSettings") {
        h.client.titles = params as TitleSettings;
        return Promise.resolve(params as T);
      }
      if (method === "ListHarnesses") {
        return Promise.resolve(
          (h.client.harnesses ?? []) as unknown as T,
        );
      }
      if (method === "ListModels") {
        return Promise.resolve(
          (h.client.models ?? []) as unknown as T,
        );
      }
      return Promise.resolve([] as unknown as T);
    },
    harnesses: null as HarnessDescriptor[] | null,
    models: null as { id: string; label: string }[] | null,
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
  // Only `useEngineSession().client` is read by the General page's
  // thread-naming card (and the Shortcuts page's completion section); the
  // real provider's notification machinery is unrelated to these contracts.
  useEngineSession: () => h.getSession(),
}));

// ── jsdom gaps (the mounted suites' set) ───────────────────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query.startsWith("(max-width") === false,
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
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── The mounted page harness ───────────────────────────────────────────────

interface MountedPage {
  unmount(): void;
}

const mounted: { root: Root; container: HTMLDivElement }[] = [];

function mountPage(component: () => ReactElement): MountedPage {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(component, {}));
  });
  mounted.push({ root, container });
  return {
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

async function mountGeneral(): Promise<MountedPage> {
  const handle = mountPage(GeneralSettingsPage);
  await act(async () => {});
  return handle;
}

beforeEach(() => {
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
  uiSettings.updateImmediate({
    composerSendBehavior: "enter",
    transcriptCompactMode: false,
    escapeStopsActiveAgent: false,
    settingsSection: "general",
  });
  h.client.calls = [];
  h.client.titles = null;
  h.client.titlesFail = false;
  h.client.harnesses = [
    {
      id: "claude-code",
      name: "Claude Code",
      supportsSteering: true,
      steeringMode: "step-boundary",
      reasoningLevels: [],
      installed: true,
      enabled: true,
      canInstall: false,
    },
    {
      id: "mock",
      name: "Mock",
      supportsSteering: false,
      steeringMode: "step-boundary",
      reasoningLevels: [],
      installed: true,
      enabled: true,
      canInstall: false,
    },
  ];
  h.client.models = [
    { id: "haiku", label: "Haiku" },
    { id: "sonnet", label: "Sonnet" },
  ];
  h.setSession({ client: h.client } as unknown as EngineSession);
});

afterEach(() => {
  while (mounted.length > 0) {
    const entry = mounted.pop()!;
    act(() => {
      entry.root.unmount();
    });
    entry.container.remove();
  }
  document.body.replaceChildren();
});

// ── The conversation-behavior rows (render_general_page parity) ────────────

describe("General — the conversation page (b782d043's render_general_page)", () => {
  it("renders the page header and the three behavior rows with the desktop's concise copy", async () => {
    await mountGeneral();
    const page = document.querySelector<HTMLElement>(".settings-page")!;
    expect(page.querySelector(".settings-title")?.textContent).toBe("General");
    const titles = Array.from(page.querySelectorAll<HTMLElement>(".settings-row-title")).map(
      (node) => node.textContent,
    );
    expect(titles).toContain("Send messages with");
    expect(titles).toContain("Compact mode");
    expect(titles).toContain("Stop agent with Escape");
    // The concise meta lines (shortcuts.rs:462, 487): the descriptions the
    // desktop's General page carries, not the old web's verbose copy.
    expect(page.textContent).toContain("Collapse thinking and tools.");
    expect(page.textContent).toContain("When no dialog or menu is open.");
    expect(page.textContent).not.toContain("When no dialog, menu, picker, or terminal handles Escape");
  });

  it("the compact-mode switch writes the ui-settings store", async () => {
    await mountGeneral();
    const row = Array.from(document.querySelectorAll<HTMLElement>(".settings-row")).find((node) =>
      node.textContent?.includes("Compact mode"),
    )!;
    const control = row.querySelector<HTMLElement>("[role='switch']");
    expect(control?.getAttribute("aria-checked")).toBe("false");
    await act(async () => {
      control!.click();
    });
    expect(uiSettings.getSnapshot().transcriptCompactMode).toBe(true);
  });

  it("the Escape switch writes the ui-settings store", async () => {
    await mountGeneral();
    const control = document.querySelector<HTMLElement>(
      "[aria-label='Stop agent with Escape']",
    )!;
    await act(async () => {
      control.click();
    });
    expect(uiSettings.getSnapshot().escapeStopsActiveAgent).toBe(true);
  });

  it("the segmented control switches the send behavior and resets back to Enter", async () => {
    await mountGeneral();
    const group = document.querySelector<HTMLElement>("[role='radiogroup']");
    const options = Array.from(group!.querySelectorAll<HTMLElement>("[role='radio']"));
    expect(options.map((option) => option.textContent)).toEqual(["Enter", "Ctrl Enter"]);
    await act(async () => {
      options[1]!.click();
    });
    expect(uiSettings.getSnapshot().composerSendBehavior).toBe("modEnter");
    // The reset affordance appears once off the default.
    const reset = document.querySelector<HTMLElement>("[aria-label='Reset send behavior to Enter']");
    expect(reset).not.toBeNull();
    await act(async () => {
      reset!.click();
    });
    expect(uiSettings.getSnapshot().composerSendBehavior).toBe("enter");
  });
});

// ── The thread-naming card (thread_naming.rs parity) ───────────────────────

describe("General — thread naming (settings/thread_naming.rs)", () => {
  it("follows the session agent by default: no Reset, the session description", async () => {
    await mountGeneral();
    const card = document.querySelector(".settings-titles-card")!;
    expect(card.textContent).toContain("Thread naming");
    expect(card.textContent).toContain("Each thread is named by its own agent.");
    expect(card.querySelector(".compact-action")).toBeNull();
    expect(card.textContent).toContain("Session agent");
    // The load: GetTitleSettings, then the harness catalog (best-effort).
    expect(h.client.calls.map((call) => call.method)).toContain("GetTitleSettings");
    expect(h.client.calls.map((call) => call.method)).toContain("ListHarnesses");
  });

  it("choosing an agent saves SetTitleSettings and reveals the model row and Reset", async () => {
    await mountGeneral();
    const card = document.querySelector(".settings-titles-card")!;
    // Open the agent picker (the title-bound pickers are `PickerCard`s).
    const trigger = card.querySelector<HTMLButtonElement>(".title-picker-trigger");
    await act(async () => {
      trigger!.click();
    });
    const menu = document.querySelector(".title-picker-menu");
    expect(menu).not.toBeNull();
    // The title-capable installed harnesses only — Mock is filtered.
    const rows = Array.from(menu!.querySelectorAll<HTMLElement>("[role='menuitem'], .menu-row"));
    expect(rows.map((row) => row.textContent?.trim())).toEqual(["Session agent", "Claude Code"]);
    await act(async () => {
      rows[1]!.click();
    });
    await act(async () => {});
    expect(h.client.calls.some((call) => call.method === "SetTitleSettings")).toBe(true);
    expect(h.client.titles).toEqual({ harness: "claude-code", model: null });
    // The specific-agent description and the Reset action.
    const refreshed = document.querySelector(".settings-titles-card")!;
    expect(refreshed.textContent).toContain("A small model keeps titles fast and cheap.");
    expect(refreshed.querySelector(".compact-action")?.textContent).toContain("Reset");
  });

  it("Reset returns to the session agent", async () => {
    h.client.titles = { harness: "claude-code", model: null };
    await mountGeneral();
    const card = document.querySelector(".settings-titles-card")!;
    expect(card.querySelector(".compact-action")).not.toBeNull();
    await act(async () => {
      card.querySelector<HTMLButtonElement>(".compact-action")!.click();
    });
    await act(async () => {});
    expect(h.client.titles).toEqual({ harness: null, model: null });
  });

  it("a failed load surfaces in the card without failing the page", async () => {
    h.client.titlesFail = true;
    await mountGeneral();
    const card = document.querySelector(".settings-titles-card")!;
    expect(card.textContent).toContain("engine offline");
    // The behavior rows still render.
    expect(document.querySelector(".settings-page")!.textContent).toContain("Send messages with");
  });
});

// ── The moved-off regression: the Shortcuts page lost the conversation rows

describe("Shortcuts — the conversation rows moved to General", () => {
  it("keeps the rebind table and completion; the send and Escape rows are gone", async () => {
    const handle = mountPage(ShortcutsSettingsPage);
    await act(async () => {});
    const page = document.querySelector<HTMLElement>(".settings-page")!;
    expect(page.textContent).toContain("Keyboard shortcuts");
    // The send-behavior control and the Escape toggle moved to General.
    expect(page.textContent).not.toContain("Send messages with");
    expect(page.querySelector("[aria-label='Stop active agent with Escape']")).toBeNull();
    // The completion section stays (ticket 10's final state, decision 18).
    expect(page.textContent).toContain("Composer completion");
    handle.unmount();
  });
});
