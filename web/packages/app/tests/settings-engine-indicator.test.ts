// @vitest-environment jsdom

/**
 * Ticket 91 — the settings engine indicator's naming contract (port of
 * zeron 97f86114's mounted suite): the trigger and every popover row are
 * named from each engine's OWN registry row (the host device's
 * WatchDevices name, renames included), never from the pair-session label
 * ("Roboco web on Windows"), and picking a row routes the settings engine
 * through `fleetStore.setActive`.
 */

import { act, createElement, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, beforeAll, expect, it, vi } from "vitest";
import { SettingsEngineIndicator } from "../src/components/settings-engine-indicator";

const h = vi.hoisted(() => {
  const ids = ["engine-a", "engine-b"];
  let active = ids[0]!;
  /** The fleet's stored engines — the pair label must never be shown. */
  const engines = ids.map((id) => ({
    baseUrl: id,
    credential: "credential",
    label: "Roboco web on Windows",
    sessionId: "session",
    pairedAt: 1,
    deviceId: id,
  }));
  /** Each engine's own registry entry, host row first. */
  const registry = {
    engines: ids.map((id, ix) => ({
      key: id,
      state: "connected",
      info: { deviceId: id },
      devices: { rows: [{ id, name: ix === 0 ? "Work Laptop" : "Server" }] },
    })),
  };
  const setActive = vi.fn((id: string) => {
    active = id;
  });
  return {
    ids,
    engines,
    registry,
    setActive,
    get active(): string {
      return active;
    },
    set active(value: string) {
      active = value;
    },
  };
});

vi.mock("../src/state/fleet", () => ({
  useFleet: () => ({ active: h.active, engines: h.engines, configurationError: null }),
  useFleetRegistry: () => h.registry,
  fleetStore: { setActive: h.setActive },
}));
vi.mock("../src/components/ui/PickerCard", async () => {
  const { createElement } = await import("react");
  return {
    PickerCard: ({ trigger, children }: { trigger: ReactNode; children: ReactNode }) =>
      createElement("div", null, trigger, children),
  };
});
vi.mock("../src/components/ui/MenuRows", async () => {
  const { createElement } = await import("react");
  return {
    MenuRow: ({ children, onClick }: { children: ReactNode; onClick: () => void }) =>
      createElement("button", { type: "button", onClick }, children),
  };
});

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

it("names the trigger and every row from each engine's own registry row, and picking one routes the fleet's active engine", async () => {
  h.active = h.ids[0]!;
  h.setActive.mockClear();
  const container = document.createElement("div");
  document.body.append(container);
  const root = createRoot(container);
  try {
    await act(async () => {
      root.render(createElement(SettingsEngineIndicator));
    });
    // The trigger carries the active engine's host device name — not the
    // pair-session label every stored engine carries.
    expect(container.querySelector(".settings-engine-indicator-label")?.textContent).toBe("Work Laptop");
    expect(Array.from(container.querySelectorAll(".settings-engine-row-host"), (node) => node.textContent))
      .toEqual(["Work Laptop", "Server"]);
    // The second row (engine-b) routes the settings engine through the
    // fleet store's setActive.
    await act(async () => {
      (container.querySelectorAll("button")[2]! as HTMLButtonElement).click();
    });
    expect(h.setActive).toHaveBeenCalledWith("engine-b");
    await act(async () => {
      root.render(createElement(SettingsEngineIndicator));
    });
    expect(container.querySelector(".settings-engine-indicator-label")?.textContent).toBe("Server");
  } finally {
    await act(async () => {
      root.unmount();
    });
    container.remove();
  }
});
