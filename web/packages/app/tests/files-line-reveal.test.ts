// @vitest-environment jsdom

/**
 * Ticket 13 (upstream d1010657): a chat file link's line jump lands in the
 * code view — the web peer of the desktop's `apply_line_navigation` /
 * `center_active_line`. The pane store records the pending navigation
 * (right-pane.test.ts) and the document flips its markdown preview
 * (file-document.test.ts covers `setShowMarkdown`); this suite pins the
 * scroll itself:
 *
 * - The referenced row scrolls to the viewport's center (`scrollTop` moves by
 *   the row-center − viewport-center delta; the browser clamps at the top,
 *   which is the desktop's clamp-at-top fix).
 * - The caret lands at the referenced line/column on editable views
 *   (`set_cursor_position`).
 * - A reveal whose text arrives after the link re-runs when the buffer grows
 *   (clamped to the shorter buffer's last row until then), and a bumped seq
 *   re-runs a repeat jump to the same line.
 *
 * The mounted idiom follows diff-file-open.test.ts: no JSX, per-file jsdom
 * pragma, ResizeObserver/matchMedia/rAF stubs. Row geometry is staged on the
 * prototype (row N at 20*(N-1) in a 600px scroller) so the effect's math sees
 * the same numbers it would in a real layout.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { CodeView } from "../src/components/files/code-view";

const LINE_HEIGHT = 20;
const VIEWPORT_HEIGHT = 600;

/** Row N (1-based) sits at LINE_HEIGHT*(N-1) in content space. */
function rowTop(line: number): number {
  return (line - 1) * LINE_HEIGHT;
}

/** The centering delta the desktop's `center_active_line` computes. */
function centeredScrollTop(line: number): number {
  return rowTop(line) + LINE_HEIGHT / 2 - VIEWPORT_HEIGHT / 2;
}

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
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    }) as typeof requestAnimationFrame;
  }
  Object.defineProperty(HTMLElement.prototype, "clientHeight", {
    configurable: true,
    get() {
      return VIEWPORT_HEIGHT;
    },
  });
  // Staged layout: a code row reports its content-space rect; the scroller
  // (and everything else) reports the viewport. The reveal effect reads
  // exactly these numbers.
  Element.prototype.getBoundingClientRect = function getBoundingClientRect(
    this: Element,
  ): DOMRect {
    const element = this as HTMLElement;
    const line = element.dataset?.line;
    if (line !== undefined && element.classList?.contains("files-code-row")) {
      const top = (Number.parseInt(line, 10) - 1) * LINE_HEIGHT;
      return {
        x: 0,
        y: top,
        top,
        left: 0,
        right: 800,
        bottom: top + LINE_HEIGHT,
        width: 800,
        height: LINE_HEIGHT,
        toJSON: () => ({}),
      } as DOMRect;
    }
    return {
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 800,
      bottom: VIEWPORT_HEIGHT,
      width: 800,
      height: VIEWPORT_HEIGHT,
      toJSON: () => ({}),
    } as DOMRect;
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
  delete (HTMLElement.prototype as { clientHeight?: number }).clientHeight;
});

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

interface MountOptions {
  readonly text: string;
  readonly editable?: boolean;
  readonly revealLine?: number | null;
  readonly revealColumn?: number | null;
  readonly revealSeq?: number;
}

function mountCodeView(options: MountOptions, onRevealApplied?: () => void): void {
  const container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  act(() => {
    root!.render(
      createElement(CodeView, {
        text: options.text,
        path: "src/lib.rs",
        editable: options.editable ?? false,
        onChange: () => {},
        codeFontSize: 13,
        wordWrap: false,
        revealLine: options.revealLine ?? null,
        revealColumn: options.revealColumn ?? null,
        revealSeq: options.revealSeq ?? 0,
        onRevealApplied,
      }),
    );
  });
}

function scroller(): HTMLDivElement {
  const element = document.querySelector<HTMLDivElement>(".files-code-scroll");
  expect(element).not.toBeNull();
  return element!;
}

describe("CodeView chat-link line reveal (d1010657)", () => {
  const text = Array.from({ length: 50 }, (_, index) => `line ${index + 1}`).join("\n");

  it("centers the referenced row and reports the reveal", () => {
    let applied = 0;
    mountCodeView({ text, revealLine: 40, revealSeq: 1 }, () => {
      applied += 1;
    });
    // Row 40's center (790) lands on the viewport's center (300): the
    // scroller rests exactly 490 down.
    expect(scroller().scrollTop).toBe(centeredScrollTop(40));
    expect(applied).toBe(1);
  });

  it("clamps a line near the top at the first row (never scrolls above it)", () => {
    let applied = 0;
    mountCodeView({ text, revealLine: 3, revealSeq: 1 }, () => {
      applied += 1;
    });
    // Row 3's center (50) is above the viewport's (300): the delta is
    // negative, and scrollTop clamps at 0 — the first line stays at the top
    // (the desktop's clamp-at-top fix).
    expect(scroller().scrollTop).toBe(0);
    expect(applied).toBe(1);
  });

  it("re-runs a repeat jump to the same line when the seq bumps", () => {
    let applied = 0;
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    const render = (seq: number): void => {
      act(() => {
        root!.render(
          createElement(CodeView, {
            text,
            path: "src/lib.rs",
            editable: false,
            onChange: () => {},
            codeFontSize: 13,
            wordWrap: false,
            revealLine: 40,
            revealColumn: null,
            revealSeq: seq,
            onRevealApplied: () => {
              applied += 1;
            },
          }),
        );
      });
    };
    render(1);
    expect(scroller().scrollTop).toBe(centeredScrollTop(40));
    // A repeat jump to the same line must re-run (the desktop's
    // line_navigation_generation).
    scroller().scrollTop = 0;
    render(2);
    expect(scroller().scrollTop).toBe(centeredScrollTop(40));
    expect(applied).toBe(2);
  });

  it("follows the text when it loads after the link (clamped until it does)", () => {
    let applied = 0;
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    const render = (loaded: string): void => {
      act(() => {
        root!.render(
          createElement(CodeView, {
            text: loaded,
            path: "src/lib.rs",
            editable: false,
            onChange: () => {},
            codeFontSize: 13,
            wordWrap: false,
            revealLine: 40,
            revealColumn: null,
            revealSeq: 1,
            onRevealApplied: () => {
              applied += 1;
            },
          }),
        );
      });
    };
    // The link opened the tab before the full read landed: a short buffer
    // clamps the reveal to its last row (the desktop's (line - 1).min(
    // lines_len - 1)), and its center still sits above the viewport's, so
    // the scroll clamps at the top.
    render(Array.from({ length: 5 }, (_, index) => `line ${index + 1}`).join("\n"));
    expect(applied).toBe(1);
    expect(scroller().scrollTop).toBe(0);
    // The text lands and the reveal re-runs at the referenced line.
    render(text);
    expect(applied).toBe(2);
    expect(scroller().scrollTop).toBe(centeredScrollTop(40));
  });

  it("places the caret at the referenced line and column on editable views", () => {
    const lines = ["alpha", "beta", "gamma", "delta"];
    let applied = 0;
    mountCodeView(
      { text: lines.join("\n"), editable: true, revealLine: 3, revealColumn: 2, revealSeq: 1 },
      () => {
        applied += 1;
      },
    );
    const textarea = document.querySelector<HTMLTextAreaElement>("textarea");
    expect(textarea).not.toBeNull();
    expect(applied).toBe(1);
    // Line 3 ("gamma"), column 2: past "alpha\n" + "beta\n" + one char.
    expect(textarea!.selectionStart).toBe("alpha\n".length + "beta\n".length + 1);
    expect(textarea!.selectionEnd).toBe(textarea!.selectionStart);
  });

  it("clamps a line past the end of the file to its last row", () => {
    let applied = 0;
    mountCodeView({ text, revealLine: 999, revealSeq: 1 }, () => {
      applied += 1;
    });
    // The desktop clamps (line - 1).min(lines_len - 1); the web asks for the
    // clamped row's element, which is the file's last line.
    expect(applied).toBe(1);
    expect(scroller().scrollTop).toBe(centeredScrollTop(50));
  });
});
