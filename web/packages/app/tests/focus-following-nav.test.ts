// @vitest-environment jsdom

/**
 * Focus-following tab navigation (upstream a1ccea18) — the web half's DOM
 * seams. The decision layer is pure and pinned in shortcuts.test.ts
 * (cycleNavigationKind) and right-pane.test.ts (cycleRightTabTarget); this
 * file covers what only a mounted DOM can:
 *
 * - `focusInRightPane`: the pane's content, the titlebar's strip (a SIBLING
 *   of the pane, the desktop's separate `tabs` focus handle), and the phone
 *   drawer's in-pane strip all count; anything else (body, the main
 *   composer) does not.
 * - The pane's `<aside>` is a programmatic focus target (tabIndex -1, never
 *   a tab stop) — `navigation_focus.right`'s stand-in, so a cycle that
 *   unmounts the focused surface can pin focus on the pane.
 * - `restore_right_focus_after_close`: closing the ACTIVE tab while the
 *   right zone holds focus keeps focus in the pane; closing the last tab
 *   hands it to the main composer.
 *
 * The mounted harness is right-tab-strip-phone.test.ts's (the phone arm
 * mounts the strip inside the pane; the pane HOST is the unit under test,
 * surface bodies stubbed).
 */

import { act, createElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { RightPane, usePaneGlide } from "../src/components/right-pane";
import { focusInRightPane, rightPaneStore, useRightPane } from "../src/state/right-pane";

// The surface bodies drag xterm and the engine-session providers with them;
// the pane HOST and its strip need only the registry's chrome half.
vi.mock("../src/components/surface-registry", () => ({
  surfaceEntry: () => ({
    kind: "diff",
    title: () => "Diff",
    detail: () => null,
    icon: () => "list",
    render: () => null,
  }),
  renderRightSurface: () => null,
}));

vi.mock("../src/components/surface-picker", async (importOriginal) => {
  const actual = await importOriginal<
    typeof import("../src/components/surface-picker")
  >();
  return { ...actual, useGitDetected: () => true };
});

// ── The mocked phone viewport ────────────────────────────────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = (() => {
    return {
      matches: true,
      media: "(max-width: 768px)",
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  }) as unknown as typeof window.matchMedia;
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
  delete (globalThis as { matchMedia?: unknown }).matchMedia;
});

// ── The mounted pane harness ─────────────────────────────────────────────────

interface MountedPane {
  readonly container: HTMLDivElement;
  aside(): HTMLElement | null;
  chip(ix: number): HTMLElement | null;
  closeButton(ix: number): HTMLButtonElement | null;
  unmount(): void;
}

const mounted: MountedPane[] = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  document.body.replaceChildren();
});

/** The real `RightPane` at the mocked layer, with the given tabs' chat. */
async function mountPane(chatId: string): Promise<MountedPane> {
  function Host() {
    const pane = useRightPane(chatId);
    const glide = usePaneGlide(pane.open, pane.expanded, 480);
    return createElement(RightPane, { chatId, pane, openWidth: 480, glide });
  }
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(Host));
  });
  // Settle the fleet/ui-settings microtasks the strip reads.
  await act(async () => {});
  const handle: MountedPane = {
    container,
    aside: () => document.querySelector<HTMLElement>(".right-pane"),
    chip: (ix) =>
      document.querySelectorAll<HTMLElement>(".right-tab")[ix] ?? null,
    closeButton: (ix) =>
      document.querySelectorAll<HTMLButtonElement>(".right-tab-close")[ix] ?? null,
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

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: HTMLElement): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

// ── The focus zone ───────────────────────────────────────────────────────────

describe("focusInRightPane (a1ccea18)", () => {
  it("the pane's content, the titlebar strip, and null all classify", () => {
    const pane = document.createElement("aside");
    pane.className = "right-pane";
    const inner = document.createElement("div");
    inner.className = "right-pane-body";
    pane.appendChild(inner);
    const strip = document.createElement("div");
    strip.className = "right-tab-strip";
    const chip = document.createElement("div");
    chip.className = "right-tab";
    strip.appendChild(chip);
    const outside = document.createElement("textarea");
    outside.className = "composer-input";

    expect(focusInRightPane(inner)).toBe(true);
    // The strip is a sibling of the pane, not its descendant — the desktop
    // tracks it under its own `tabs` focus handle.
    expect(focusInRightPane(strip)).toBe(true);
    expect(focusInRightPane(chip)).toBe(true);
    expect(focusInRightPane(outside)).toBe(false);
    expect(focusInRightPane(document.body)).toBe(false);
    expect(focusInRightPane(null)).toBe(false);
  });
});

// ── The pane as a focus target ───────────────────────────────────────────────

describe("the pane's aside is navigation_focus.right's stand-in", () => {
  it("carries tabIndex -1 (programmatic only, never a tab stop) and takes focus", async () => {
    const chatId = "chat-aside-focus";
    rightPaneStore.addDiffSurface(chatId, "diff");
    const pane = await mountPane(chatId);
    const aside = pane.aside()!;
    expect(aside.tabIndex).toBe(-1);
    // The cycle handler's pin: focus moves onto the pane itself, so the
    // next cycle still classifies as in-right while surfaces remount.
    act(() => {
      aside.focus();
    });
    expect(document.activeElement).toBe(aside);
    expect(focusInRightPane(document.activeElement)).toBe(true);
  });
});

// ── Close recovery ───────────────────────────────────────────────────────────

describe("closing the active tab recovers focus like restore_right_focus_after_close", () => {
  it("focus held in the pane stays in the pane while tabs remain", async () => {
    const chatId = "chat-close-active";
    rightPaneStore.addDiffSurface(chatId, "diff");
    rightPaneStore.addDiffSurface(chatId, "diff", "second");
    const pane = await mountPane(chatId);
    // Focus sits in the pane (an inner element standing in for a surface's
    // input): closing the ACTIVE tab must keep the right zone's focus.
    const aside = pane.aside()!;
    act(() => {
      aside.focus();
    });
    expect(rightPaneStore.stateFor(chatId).tabs.length).toBe(2);
    press(pane.closeButton(0)!);
    // One tab left, pane still open — focus pinned on the pane container.
    expect(rightPaneStore.stateFor(chatId).tabs.length).toBe(1);
    expect(rightPaneStore.stateFor(chatId).open).toBe(true);
    expect(document.activeElement).toBe(aside);
    expect(focusInRightPane(document.activeElement)).toBe(true);
  });

  it("the last tab hands focus to the main composer", async () => {
    const chatId = "chat-close-last";
    rightPaneStore.addDiffSurface(chatId, "diff");
    const pane = await mountPane(chatId);
    // The mounted harness has no composer: plant the textarea the shell's
    // toggle-close parity already targets.
    const composer = document.createElement("textarea");
    composer.className = "composer-input";
    document.body.appendChild(composer);
    const aside = pane.aside()!;
    act(() => {
      aside.focus();
    });
    press(pane.closeButton(0)!);
    // The pane collapsed with the last tab: navigation returns to the main
    // area (the desktop's composer fallback).
    expect(rightPaneStore.stateFor(chatId).open).toBe(false);
    expect(document.activeElement).toBe(composer);
    expect(focusInRightPane(document.activeElement)).toBe(false);
  });

  it("an inactive tab's close never moves focus", async () => {
    const chatId = "chat-close-inactive";
    rightPaneStore.addDiffSurface(chatId, "diff");
    rightPaneStore.addDiffSurface(chatId, "diff", "second");
    const pane = await mountPane(chatId);
    // Focus in the main area (the composer): closing the inactive second
    // tab is a pure state change.
    const composer = document.createElement("textarea");
    composer.className = "composer-input";
    document.body.appendChild(composer);
    act(() => {
      composer.focus();
    });
    press(pane.closeButton(1)!);
    expect(rightPaneStore.stateFor(chatId).tabs.length).toBe(1);
    expect(document.activeElement).toBe(composer);
  });
});
