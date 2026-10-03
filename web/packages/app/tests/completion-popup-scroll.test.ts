// @vitest-environment jsdom

/**
 * Ticket wpn-05 — the completion popups keep the keyboard cursor visible.
 *
 * The desktop scrolls the highlighted row into view on EVERY navigation
 * step (`move_mention`/`move_slash` → `scroll_to_item(active)`,
 * composer.rs:7066-7074/:7398-7406); the web previously reset `scrollTop`
 * only when the result set changed, so with a long list the highlight
 * walked off-screen under the keyboard. The port rides the repo-wide
 * `scrollIntoView({ block: "nearest" })` substitute for `scroll_to_item`
 * (CursorList.tsx:148-152), in an effect keyed on the cursor AND the rows,
 * declared next to the existing result-change reset (the desktop's
 * `reset_menu_scroll`, composer.rs:7391-7393, stays separate).
 *
 * The mounted contracts (both popups — mention and slash):
 *
 * - Each `active` step calls scrollIntoView({ block: "nearest" }) on the
 *   row at that index. The composer owns the cursor (menu_step); the popup
 *   receives it as a prop and keeps that row visible — the browser does
 *   the fold math, the popup must make the call.
 * - The result-change reset (scrollTop = 0) still fires on a fresh row set.
 * - Skeleton state no-ops: no list element is mounted, nothing scrolls.
 * - Row glyphs are 16px (the desktop grew them, composer.rs:7168/:7565):
 *   the FileIcon/Icon width+height attributes and the
 *   `.composer-completion-row-icon` 16×16 CSS box.
 *
 * The mounted idiom follows composer-edit-failure.test.ts: no JSX,
 * per-file jsdom pragma, the matchMedia / ResizeObserver / scrollIntoView /
 * rAF stubs — scrollIntoView is a SPY here; its calls are the contract.
 */

import { act, createElement, useState, type ReactElement } from "react";
import { createRoot } from "react-dom/client";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { FileSearchMatch } from "@roboco/proto";
import { MentionPopup } from "../src/components/composer/mention-popup";
import { SlashPopup } from "../src/components/composer/slash-popup";
import type { InvocationRow } from "../src/lib/invocations";
import type { CompletionToken } from "../src/lib/mentions";

// ── jsdom gaps the mounted popups hit ──────────────────────────────────────
// matchMedia (useResolvedAppearance / useIsPhone), ResizeObserver
// (MenuScrollbar), scrollIntoView (the cursor scroll effect under test —
// installed as a spy), rAF (the skeleton pulse / scrollbar priming).

// The popups' import graph resolves the new-thread artwork at module scope
// and prewarms its decode (appearance.ts `defaultPrewarm`) — jsdom's Image
// has no decode, and the unhandled rejection would poison the run.
// Patched in `vi.hoisted` so it lands BEFORE the imports evaluate.
vi.hoisted(() => {
  if (
    typeof HTMLImageElement === "function" &&
    typeof HTMLImageElement.prototype.decode !== "function"
  ) {
    HTMLImageElement.prototype.decode = () => Promise.resolve();
  }
});

/** The completion token every mount shares — the popups only read its query. */
const TOKEN: CompletionToken = { start: 0, end: 1, query: "" };

/** One recorded scrollIntoView call: the element (its `this`) + options. */
interface ScrollCall {
  readonly element: Element;
  readonly options: unknown;
}

/** The recorded scrollIntoView calls — the contract under test. */
const scrollCalls: ScrollCall[] = [];
/** The scrollTop answers the jsdom list reports (jsdom has no layout). */
let scrollTop: number;

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
  // The browser owns the fold math; the popup's contract is making the
  // call, so the stub records (element + options) rather than no-ops —
  // the receiver arrives as `this`, not as an argument.
  Element.prototype.scrollIntoView = function scrollIntoViewRecorder(
    this: Element,
    options: unknown,
  ): void {
    scrollCalls.push({ element: this, options });
  };
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    }) as typeof requestAnimationFrame;
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
  delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
});

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  scrollCalls.length = 0;
  document.body.replaceChildren();
});

// ── The row stacks ─────────────────────────────────────────────────────────

function fileResult(path: string): FileSearchMatch {
  return { path, isDir: false };
}

/** Enough rows to walk past any fold (the list caps at 312px, rows ~30px). */
function manyFiles(): FileSearchMatch[] {
  return Array.from({ length: 12 }, (_, ix) => fileResult(`pkg/file-${ix}.rs`));
}

function commandRow(name: string): InvocationRow {
  return {
    invocation: { kind: "command", name },
    name,
    description: "Runs the command",
    inputHint: null,
    workspaceCommand: null,
  };
}

function manyCommands(): InvocationRow[] {
  return Array.from({ length: 12 }, (_, ix) => commandRow(`cmd-${ix}`));
}

/** The ranked-indices shape the composer passes after a refilter. */
const ALL_RANKED: readonly number[] = Array.from({ length: 12 }, (_, ix) => ix);

// ── The mounted harness ────────────────────────────────────────────────────

interface PopupHandle {
  /** The `.composer-completion-list` element, or null (skeleton/empty). */
  list(): HTMLDivElement | null;
  /** Step the cursor like ArrowDown would (the composer's `menu_step`). */
  setActive(ix: number | null): void;
  /** Swap in a fresh result set (a new query's list, a new refilter). */
  refresh(): void;
  unmount(): void;
}

const mounted: PopupHandle[] = [];

function mount(
  render: (state: { setActive: (ix: number | null) => void; refresh: () => void }) => ReactElement,
): PopupHandle {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const state: { setActive: (ix: number | null) => void; refresh: () => void } = {
    setActive: () => {},
    refresh: () => {},
  };
  function Host() {
    return render(state);
  }
  act(() => {
    root.render(createElement(Host));
  });
  const handle: PopupHandle = {
    list: () => document.querySelector<HTMLDivElement>(".composer-completion-list"),
    setActive(ix: number | null) {
      act(() => {
        state.setActive(ix);
      });
    },
    refresh() {
      act(() => {
        state.refresh();
      });
    },
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

/** Mention popup over `results`, cursor at `active`. */
function mountMention(results: readonly FileSearchMatch[], active: number | null, loading = false): PopupHandle {
  return mount((state) => {
    const [cursor, setCursor] = useState<number | null>(active);
    const [rows, setRows] = useState<readonly FileSearchMatch[]>(results);
    state.setActive = setCursor;
    state.refresh = () => {
      // A fresh query's result set — the row stack restarts at the top and
      // the composer resets the cursor to the first row (composer.rs:5131).
      setCursor(0);
      setRows(rows.map((row) => fileResult(`${row.path}#fresh`)));
    };
    return createElement(MentionPopup, {
      token: TOKEN,
      results: rows,
      active: cursor,
      loading,
      error: null,
      onAccept: () => {},
      onDismiss: () => {},
      onCardMouseDown: () => {},
    });
  });
}

/** Slash popup over `rows` ranked by `filtered`, cursor at `active`. */
function mountSlash(
  rows: readonly InvocationRow[],
  filtered: readonly number[],
  active: number | null,
  loading = false,
): PopupHandle {
  return mount((state) => {
    const [cursor, setCursor] = useState<number | null>(active);
    const [ranked, setRanked] = useState<readonly number[]>(filtered);
    state.setActive = setCursor;
    state.refresh = () => {
      // A refilter always re-ranks (a fresh array) and resets the cursor
      // (composer.rs:6985).
      setCursor(0);
      setRanked(ranked.map((rowIx) => rowIx));
    };
    return createElement(SlashPopup, {
      token: TOKEN,
      rows,
      filtered: ranked,
      active: cursor,
      loading,
      error: null,
      skill: false,
      supported: true,
      separateFromSlash: false,
      onAccept: () => {},
      onDismiss: () => {},
      onCardMouseDown: () => {},
    });
  });
}

/** Give the jsdom list a settable scrollTop (jsdom has no layout, so the
 *  prototype accessor silently drops writes — the reset must still land). */
function armScrollTop(list: HTMLDivElement): void {
  scrollTop = 40;
  Object.defineProperty(list, "scrollTop", {
    configurable: true,
    get: () => scrollTop,
    set: (value: number) => {
      scrollTop = value;
    },
  });
}

/** Assert the last recorded call targeted `row` with `block: "nearest"`. */
function expectScrolledTo(row: Element): void {
  const call = scrollCalls.at(-1);
  expect(call).toBeDefined();
  expect(call!.element).toBe(row);
  expect(call!.options).toEqual({ block: "nearest" });
}

// ── The mention popup's cursor scroll ─────────────────────────────────────

describe("the mention popup's keyboard cursor scroll (wpn-05)", () => {
  it("every ArrowDown step scrolls the active row into view (block: nearest)", () => {
    const handle = mountMention(manyFiles(), 0);
    const list = handle.list()!;
    expect(list).not.toBeNull();
    // The mount itself pins row 0 (the effect runs on mount, like every
    // scroll-effect mount in the repo); the navigation steps are the test.
    scrollCalls.length = 0;
    handle.setActive(1);
    expectScrolledTo(list.children.item(1)!);
    expect(scrollCalls.length).toBe(1);
    handle.setActive(2);
    expectScrolledTo(list.children.item(2)!);
    handle.setActive(11);
    // Past the fold: the browser scrolls because the popup made the call.
    expectScrolledTo(list.children.item(11)!);
    expect(scrollCalls.length).toBe(3);
    handle.unmount();
  });

  it("a null cursor never scrolls (no active row to pin)", () => {
    const handle = mountMention(manyFiles(), null);
    scrollCalls.length = 0;
    handle.setActive(5);
    expectScrolledTo(handle.list()!.children.item(5)!);
    // Back to null — `if let Some(active)` on the desktop, item(-1) here.
    handle.setActive(null);
    expect(scrollCalls.length).toBe(1);
    handle.unmount();
  });

  it("the result-change reset still fires beside the step scroll", () => {
    const handle = mountMention(manyFiles(), 7);
    const list = handle.list()!;
    armScrollTop(list);
    scrollCalls.length = 0;
    handle.refresh();
    // New result set: the stack restarts at the top (composer.rs:5131)…
    expect(scrollTop).toBe(0);
    // …and the reset cursor (row 0) is pinned, not row 7 of the old stack.
    const fresh = handle.list()!;
    expect(fresh).toBe(list);
    expectScrolledTo(list.children.item(0)!);
    handle.unmount();
  });

  it("the skeleton state no-ops: no list, no scroll calls", () => {
    const handle = mountMention([], null, true);
    expect(handle.list()).toBeNull();
    scrollCalls.length = 0;
    handle.setActive(5);
    expect(handle.list()).toBeNull();
    expect(scrollCalls.length).toBe(0);
    handle.unmount();
  });
});

// ── The slash popup's cursor scroll ────────────────────────────────────────────

describe("the slash popup's keyboard cursor scroll (wpn-05)", () => {
  it("every ArrowDown step scrolls the active row into view (block: nearest)", () => {
    const handle = mountSlash(manyCommands(), ALL_RANKED, 0);
    const list = handle.list()!;
    scrollCalls.length = 0;
    handle.setActive(1);
    expectScrolledTo(list.children.item(1)!);
    handle.setActive(11);
    expectScrolledTo(list.children.item(11)!);
    expect(scrollCalls.length).toBe(2);
    handle.unmount();
  });

  it("the refilter reset still fires beside the step scroll", () => {
    const handle = mountSlash(manyCommands(), ALL_RANKED, 7);
    const list = handle.list()!;
    armScrollTop(list);
    scrollCalls.length = 0;
    handle.refresh();
    expect(scrollTop).toBe(0);
    expectScrolledTo(list.children.item(0)!);
    handle.unmount();
  });

  it("the skeleton state no-ops: no list, no scroll calls", () => {
    const handle = mountSlash([], [], null, true);
    expect(handle.list()).toBeNull();
    scrollCalls.length = 0;
    handle.setActive(5);
    expect(handle.list()).toBeNull();
    expect(scrollCalls.length).toBe(0);
    handle.unmount();
  });
});

// ── The 16px row glyphs (box + prop) ───────────────────────────────────────────

describe("the completion rows' 16px glyphs (wpn-05)", () => {
  it("the mention rows render a 16px FileIcon", () => {
    const handle = mountMention(manyFiles(), 0);
    const img = handle.list()!.querySelector(".composer-completion-row-icon img")!;
    expect(img).not.toBeNull();
    expect(img.getAttribute("width")).toBe("16");
    expect(img.getAttribute("height")).toBe("16");
    handle.unmount();
  });

  it("the slash rows render a 16px Icon", () => {
    const handle = mountSlash(manyCommands(), ALL_RANKED, 0);
    const svg = handle.list()!.querySelector(".composer-completion-row-icon svg")!;
    expect(svg).not.toBeNull();
    expect(svg.getAttribute("width")).toBe("16");
    expect(svg.getAttribute("height")).toBe("16");
    handle.unmount();
  });

  it("the .composer-completion-row-icon box is 16×16 (CSS contract)", () => {
    const css = readFileSync(join(process.cwd(), "src/styles/app.css"), "utf8");
    const rule = css.match(/^\.composer-completion-row-icon\s*\{([^}]*)\}/m)?.[1];
    expect(rule).toBeDefined();
    expect(rule!).toMatch(/width:\s*16px;/);
    expect(rule!).toMatch(/height:\s*16px;/);
  });
});
