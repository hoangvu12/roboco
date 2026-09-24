// @vitest-environment jsdom

/**
 * Ticket 04 (web-pierre-adoption): the transcript's stacked tool-diff blocks
 * render through the Pierre diffs library. The real `ToolGroupRow` mounts
 * (no JSX, per-file jsdom pragma, the base-tooltip idiom) with a real
 * `ToolGroupMotionStore` and a `ToolItem` whose detail is an inline
 * `ToolDiff`; the store's detail-fold write opens the chip the way a click
 * does. Assertions stay OUTSIDE the shadow DOM — our chrome (the
 * `.tool-diff-body` wrapper, the library host element it carries, and the
 * notices slot content) plus the geometry the renderer and estimator share.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { ToolCall } from "@roboco/proto";
import { ToolGroupRow } from "../src/components/tool-group";
import { ToolGroupMotionStore } from "../src/lib/tool-motion";
import { toolDetail, type ToolItem } from "../src/lib/transcript";

// jsdom's Image lacks `decode` — the appearance store's artwork prewarm
// (import-time) needs it (changes-code-view.test.ts's hoisted stub).
vi.hoisted(() => {
  if (typeof Image === "function" && Image.prototype.decode === undefined) {
    (Image.prototype as { decode: () => Promise<void> }).decode = () => Promise.resolve();
  }
});

const ROW_ID = "row-tool-diff";
const DETAIL_KEY = `${ROW_ID}#d0`;

const CALL: ToolCall = {
  kind: "writeFile",
  path: "/w/notes.md",
  content: null,
};

const OLD_TEXT = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n";
const NEW_TEXT = "one\ntwo\nTHREE\nfour\nfive\nsix\nseven\neight\nnine\nTEN!\n";

function diffTool(): ToolItem {
  return {
    call: CALL,
    isError: false,
    resolved: true,
    detail: toolDetail(null, { path: "/w/notes.md", oldText: OLD_TEXT, newText: NEW_TEXT }, null),
    invocation: null,
    outputRef: null,
    outputBytes: null,
    diffRef: null,
    subagentRef: null,
    subagentStatus: null,
    subagentTail: null,
    isThought: false,
  };
}

const CLIENT = {
  call(): Promise<never> {
    return Promise.reject(new Error("no blob fetches in this suite"));
  },
};

// ── jsdom gaps the mounted row hits (base-tooltip.test.ts's set) ─────────

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
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

const unmounts: Array<() => void> = [];

afterEach(() => {
  while (unmounts.length > 0) {
    unmounts.pop()!();
  }
  document.body.replaceChildren();
});

/** Flush the library's rAF render queue + the async highlighter/themes. */
async function settle(ms = 120): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms));
  });
}

async function mountRow(tools: readonly ToolItem[], motion: ToolGroupMotionStore) {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(
      createElement(ToolGroupRow, {
        rowId: ROW_ID,
        tools,
        autoOpen: true,
        chatId: "chat-1",
        motion,
        client: CLIENT,
        onOpenSubagent: () => {},
      }),
    );
  });
  const unmount = () => {
    act(() => {
      root.unmount();
    });
  };
  unmounts.push(unmount);
  return { container, root, unmount };
}

describe("ToolGroupRow renders the tool diff through the Pierre diffs library", () => {
  it("mounts the library's FileDiff host inside the chip card once the detail opens", async () => {
    const motion = new ToolGroupMotionStore();
    const { container } = await mountRow([diffTool()], motion);
    await settle();

    // The chip card renders with its detail CLOSED (no fold write yet)…
    expect(container.querySelector(".tool-chip-card-expandable")).not.toBeNull();
    expect(container.querySelector(".tool-diff-body")).toBeNull();

    // …the store's detail-fold write (the chip header click lands here)
    // opens it, and the diff block mounts.
    act(() => {
      motion.toggleDetailFold(DETAIL_KEY, 100, false);
    });
    await settle();
    const body = container.querySelector<HTMLElement>(".tool-diff-body");
    expect(body).not.toBeNull();
    // The library's host element, carrying our token-mapping class.
    const host = body!.querySelector("diffs-container.tool-diff-host");
    expect(host).not.toBeNull();
    // The chip header's own state follows the same fold write.
    const head = container.querySelector<HTMLElement>(".tool-chip-head[aria-expanded]");
    expect(head).not.toBeNull();
    expect(head!.getAttribute("aria-expanded")).toBe("true");
  });

  it("renders the new-file notice through the library header's metadata slot", async () => {
    const motion = new ToolGroupMotionStore();
    const tool: ToolItem = {
      ...diffTool(),
      detail: toolDetail(null, { path: "/w/made.txt", oldText: null, newText: "hello\nworld\n" }, null),
    };
    const { container } = await mountRow([tool], motion);
    act(() => {
      motion.toggleDetailFold(DETAIL_KEY, 100, false);
    });
    await settle();

    // The old notice rows' copy, now riding the header metadata slot.
    const notices = container.querySelector<HTMLElement>(".tool-diff-body .changes-file-notices");
    expect(notices).not.toBeNull();
    expect(notices!.textContent).toContain("New file");
  });

  // A 600-line capped diff tokenizes for real through the shared
  // highlighter — heavier than the suite default's 5s budget under jsdom.
  it("renders the truncation notice when the cap drops lines", async () => {
    const motion = new ToolGroupMotionStore();
    const oldText = Array.from({ length: 400 }, (_, i) => `o${i}`).join("\n") + "\n";
    const newText = Array.from({ length: 400 }, (_, i) => `n${i}`).join("\n") + "\n";
    const tool: ToolItem = {
      ...diffTool(),
      detail: toolDetail(null, { path: "/w/big.rs", oldText, newText }, null),
    };
    const { container } = await mountRow([tool], motion);
    act(() => {
      motion.toggleDetailFold(DETAIL_KEY, 100, false);
    });
    await settle();

    const notices = container.querySelector<HTMLElement>(".tool-diff-body .changes-file-notices");
    expect(notices).not.toBeNull();
    expect(notices!.textContent).toContain("Diff truncated — showing first 600 of 800 lines");
  }, 20_000);
});
