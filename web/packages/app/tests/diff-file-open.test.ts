// @vitest-environment jsdom

/**
 * Ticket 12 (upstream c2230f07): the diff file header's open-in-file-browser
 * button. The desktop emits `ChangesEvent::OpenFile` (the POST-rename path)
 * and the shell routes it to the Files pane; the web threads
 * `onOpenFile` through `DiffView` → `DiffSurface` → `DiffScroller` →
 * `FileHeaderRow` and the host routes it to
 * `rightPaneStore.revealInFilesPanel`. This suite pins the mounted contract:
 *
 * - The button renders beside the +N/−N counters ONLY while `onOpenFile` is
 *   provided — no callback, no button (the plain transcript diff keeps its
 *   old header).
 * - A click reports the file's POST-rename path and never toggles the fold
 *   (stopPropagation, like the desktop's chip).
 *
 * The mounted idiom follows section-menu.test.ts: no JSX, per-file jsdom
 * pragma, ResizeObserver/matchMedia/rAF stubs for the virtualized scroller.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { DiffView } from "../src/components/diff-view";
import { parsePatch } from "../src/lib/diff";

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
  // jsdom reports clientHeight 0 for every element, which would window the
  // virtualized scroller down to its first pad row; give it a viewport and
  // non-zero row rects so the prefix-sum positions stay monotonic.
  Object.defineProperty(HTMLElement.prototype, "clientHeight", {
    configurable: true,
    get() {
      return 600;
    },
  });
  Element.prototype.getBoundingClientRect = function getBoundingClientRect(
    this: Element,
  ): DOMRect {
    return {
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 800,
      bottom: 24,
      width: 800,
      height: 24,
      toJSON: () => ({}),
    } as DOMRect;
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
  delete (HTMLElement.prototype as { clientHeight?: number }).clientHeight;
});

const PATCH = [
  "diff --git a/src/old.rs b/src/new.rs",
  "similarity index 90%",
  "rename from src/old.rs",
  "rename to src/new.rs",
  "--- a/src/old.rs",
  "+++ b/src/new.rs",
  "@@ -1,2 +1,3 @@",
  " line one",
  "-line two",
  "+line two edited",
  "+line three",
].join("\n");

let root: Root | null = null;

afterEach(() => {
  if (root !== null) {
    act(() => {
      root!.unmount();
    });
    root = null;
  }
  document.body.replaceChildren();
});

function mountDiffView(onOpenFile?: (path: string) => void): void {
  const container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  const files = parsePatch(PATCH);
  act(() => {
    root!.render(
      createElement(DiffView, {
        files,
        appearance: "dark",
        onOpenFile,
      }),
    );
  });
}

describe("DiffView file-header open-in-file-browser button (ticket 12)", () => {
  it("renders the button only while onOpenFile is provided", () => {
    mountDiffView();
    expect(document.querySelector(".diff-file-open")).toBeNull();

    mountDiffView(() => {});
    const button = document.querySelector<HTMLButtonElement>(".diff-file-open");
    expect(button).not.toBeNull();
    expect(button!.getAttribute("aria-label")).toBe("Open src/new.rs in file browser");
  });

  it("reports the POST-rename path and never toggles the fold", () => {
    const opened: string[] = [];
    let folds = 0;
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    const files = parsePatch(PATCH);
    act(() => {
      root!.render(
        createElement(DiffView, {
          files,
          appearance: "dark",
          onOpenFile: (path) => {
            opened.push(path);
          },
          onToggleFold: () => {
            folds += 1;
          },
        }),
      );
    });
    const button = document.querySelector<HTMLButtonElement>(".diff-file-open");
    expect(button).not.toBeNull();
    act(() => {
      button!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
    // The POST-rename path — never old.rs.
    expect(opened).toEqual(["src/new.rs"]);
    // stopPropagation: the fold toggle under the header never fired.
    expect(folds).toBe(0);
  });
});
