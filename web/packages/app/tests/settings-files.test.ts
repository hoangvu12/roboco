// @vitest-environment jsdom

/**
 * Ticket wpn-09 — the Files page's two one-spot parity fixes: the show-all
 * row carries the desktop's copy ("Show hidden and ignored files",
 * files.rs:193-198) instead of the web-only "Show all files", and the
 * autosave delay renders as the in-settings `RbSelect` (files.rs:88-112's
 * 112px dropdown, the settings-appearance font-picker precedent) instead
 * of the web-only pill row. The mounted idiom follows
 * settings-general.test.ts: the REAL page over the REAL ui-settings store —
 * the Files page talks to no engine. No JSX (createElement), per-file
 * jsdom pragma only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FilesSettingsPage } from "../src/routes/settings-files";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";

// ── jsdom gaps (the mounted suites' set) ───────────────────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  // The desktop arm: nothing under 769px matches, so selects open as the
  // floating card (the phone sheet arm rides the sheet family's CSS
  // contract suite — responsive-surface.test.ts — and the drawer family's
  // own mounted suites).
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

const mounted: { root: Root; container: HTMLDivElement }[] = [];

function mountFiles(): void {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(FilesSettingsPage));
  });
  mounted.push({ root, container });
}

beforeEach(() => {
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
  // Autosave on so the delay row renders; the delay at the store's default.
  uiSettings.updateImmediate({
    filesAutosaveEnabled: true,
    filesAutosaveDelayMs: 900,
    filesWordWrap: false,
    filesShowAll: false,
  });
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

/** The settings row whose title matches, anywhere on the page. */
function rowWithTitle(title: string): HTMLElement | null {
  return (
    Array.from(document.querySelectorAll<HTMLElement>(".settings-row")).find(
      (row) => row.querySelector(".settings-row-title")?.textContent === title,
    ) ?? null
  );
}

/** The autosave-delay select's trigger (the in-settings select family). */
function delayTrigger(): HTMLButtonElement {
  const trigger = document.querySelector<HTMLButtonElement>(".settings-select-trigger");
  if (trigger === null) {
    throw new Error("the autosave-delay select trigger did not render");
  }
  return trigger;
}

// ── The show-all row copy (files.rs:193-198) ───────────────────────────────

describe("Files — the show-all row copy (wpn-09)", () => {
  it("carries the desktop's title and switch aria, not the old web copy", () => {
    mountFiles();
    const row = rowWithTitle("Show hidden and ignored files");
    expect(row).not.toBeNull();
    const control = row!.querySelector<HTMLElement>("[role='switch']");
    expect(control?.getAttribute("aria-label")).toBe("Show hidden and ignored files");
    // The old web-only label is gone.
    expect(document.querySelector(".settings-page")!.textContent).not.toContain("Show all files");
  });
});

// ── The autosave-delay select (files.rs:88-112) ────────────────────────────

describe("Files — the autosave-delay select (wpn-09)", () => {
  it("is the in-settings RbSelect at 112px with the desktop's five options", async () => {
    mountFiles();
    expect(rowWithTitle("Autosave delay")).not.toBeNull();
    const trigger = delayTrigger();
    // The appearance page's select family, with the delay row's own width
    // class — files.rs:111's `.width(112.0)`, the row's layout contract.
    expect(trigger.classList.contains("settings-select-trigger")).toBe(true);
    expect(trigger.classList.contains("delay-trigger")).toBe(true);
    expect(trigger.getAttribute("aria-label")).toBe("Autosave delay");
    expect(trigger.querySelector(".settings-select-label")?.textContent).toBe("900 ms");
    // The pill row is gone — no radiogroup of pills.
    expect(document.querySelector(".pill-row")).toBeNull();
    expect(document.querySelector(".pill")).toBeNull();

    // The option set: DELAY_OPTIONS with the desktop's labels (files.rs:95-100).
    await act(async () => {
      trigger.click();
    });
    const labels = Array.from(
      document.querySelectorAll<HTMLElement>(".settings-select-item .settings-select-item-label"),
    ).map((node) => node.textContent);
    expect(labels).toEqual(["300 ms", "600 ms", "900 ms", "1.5 s", "3 s"]);
  });

  it("picking an option commits the store and moves the trigger's label", async () => {
    mountFiles();
    await act(async () => {
      delayTrigger().click();
    });
    const items = Array.from(document.querySelectorAll<HTMLElement>(".settings-select-item"));
    expect(items).toHaveLength(5);
    await act(async () => {
      items[4]!.click();
    });
    // The row's commit seam: the ui-settings store, immediately.
    expect(uiSettings.getSnapshot().filesAutosaveDelayMs).toBe(3000);
    expect(delayTrigger().querySelector(".settings-select-label")?.textContent).toBe("3 s");
  });
});
