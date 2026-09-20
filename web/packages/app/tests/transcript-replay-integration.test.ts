// @vitest-environment jsdom

/**
 * Ticket 69 — the mounted cache→reset baseline regression. The REAL
 * TranscriptView mounts against a REAL TranscriptStore (through the public
 * `store` prop) driven by a controllable fake client and a deferred offline
 * cache, so row derivation, the ToolGroupMotionStore, the baseline effect,
 * and the scroller/StickController all run for real. Spies on the motion
 * store's `sync`/`noteRendered` and the controller's
 * `attach`/`snapToEnd`/`restoreViewport` call through — they record what the
 * mounted consumer actually did (the render-consumed baseline decisions), not
 * just what the store published. The store's own listener log rides along as
 * supplementary evidence.
 *
 * jsdom gaps are stubbed per-suite (the session-provider/composer-reasoning
 * idiom): matchMedia, a deterministic rAF queue, a controllable
 * ResizeObserver, and a mocked performance.now. Scroller geometry
 * (clientHeight/scrollHeight/scrollTop) is stubbed on the element itself —
 * these stubs prove lifecycle/state transitions only, never browser layout.
 * No JSX (createElement), per-file jsdom pragma only.
 */

import { act, createElement, StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { EngineClient, EngineStatus } from "@roboco/engine-client";
import type { MessagePart, SessionMessageEntry, TranscriptUpdate } from "@roboco/proto";
import { TranscriptView } from "../src/components/transcript";
import { StickController } from "../src/components/stick-controller";
import { ToolGroupMotionStore } from "../src/lib/tool-motion";
import type { OwnTurnAnchor, TranscriptRow } from "../src/lib/transcript";
import {
  echoStore,
  savedViewportCache,
  TranscriptStore,
  type TranscriptCache,
} from "../src/state/transcript-store";

// The transcript subtree reads only the resolved appearance (tool chips,
// markdown assets). The real module boots a shell-scoped artwork store whose
// prewarm rides Image.decode — absent in jsdom — so this suite stubs the one
// hook it consumes; baseline synchronization and the motion store are never
// mocked.
vi.mock("../src/state/appearance", () => ({
  useResolvedAppearance: () => "dark" as const,
}));

const CHAT = "chat-replay";

// ── Entries ─────────────────────────────────────────────────────────────────

function toolPart(id: string, command: string): MessagePart {
  return { kind: "tool", id, call: { kind: "exec", command }, isError: false, resolved: true };
}

function userEntry(id: string, text = "hello"): SessionMessageEntry {
  return { id, role: "user", parts: [{ kind: "text", id: `${id}#t`, text }], createdAt: 1_000, deviceId: "dev" };
}

/** A settled assistant entry whose parts are one collapsible tool group. */
function toolEntry(id: string, commands: string[]): SessionMessageEntry {
  return {
    id,
    role: "assistant",
    parts: commands.map((command, ix) => toolPart(`${id}#t${ix}`, command)),
    createdAt: 2_000,
    deviceId: "dev",
    status: null,
  };
}

// ── Controllable client + deferred cache ────────────────────────────────────

interface WatchSlot {
  onItem: (item: TranscriptUpdate, ctx: { generation: number }) => void;
  onEnd?: (error: unknown) => void;
}

class FakeClient {
  readonly status = { state: "connected" } as unknown as EngineStatus;
  readonly watches: WatchSlot[] = [];
  readonly #statusListeners = new Set<(status: EngineStatus) => void>();

  onStatus(listener: (status: EngineStatus) => void): () => void {
    this.#statusListeners.add(listener);
    return () => {
      this.#statusListeners.delete(listener);
    };
  }

  watch(_method: string, _params: unknown, handlers: WatchSlot): { cancel: () => void } {
    this.watches.push(handlers);
    return { cancel: () => {} };
  }

  call(): Promise<never> {
    return Promise.resolve({} as never);
  }

  /** Deliver a frame on the LATEST watch (a resubscribe replaces it). */
  emit(update: TranscriptUpdate, generation = 1): void {
    const slot = this.watches[this.watches.length - 1];
    if (slot === undefined) {
      throw new Error("no watch registered");
    }
    slot.onItem(update, { generation });
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

// ── Deterministic time, rAF, ResizeObserver, matchMedia ─────────────────────

let now = 10_000;
const rafQueue = new Map<number, FrameRequestCallback>();
let rafSeq = 0;

/** Run every queued animation frame once (callbacks may re-arm). */
function pumpRaf(frames = 1): void {
  for (let frame = 0; frame < frames; frame += 1) {
    const callbacks = [...rafQueue.values()];
    rafQueue.clear();
    for (const callback of callbacks) {
      callback(now);
    }
  }
}

class FakeResizeObserver {
  static instances: FakeResizeObserver[] = [];
  readonly callback: ResizeObserverCallback;
  readonly observed = new Set<Element>();

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    FakeResizeObserver.instances.push(this);
  }

  observe(el: Element): void {
    this.observed.add(el);
  }

  unobserve(el: Element): void {
    this.observed.delete(el);
  }

  disconnect(): void {
    this.observed.clear();
  }
}

/** Explicitly deliver one measurement batch to observed rows (jsdom has no layout). */
function deliverHeights(heights: Record<string, number>): void {
  for (const observer of FakeResizeObserver.instances) {
    const entries: ResizeObserverEntry[] = [];
    for (const el of observer.observed) {
      const rid = (el as HTMLElement).dataset?.rid;
      const height = rid === undefined ? undefined : heights[rid];
      if (height !== undefined) {
        entries.push({ target: el, borderBoxSize: [{ blockSize: height, inlineSize: 700 }] } as unknown as ResizeObserverEntry);
      }
    }
    if (entries.length > 0) {
      observer.callback(entries, observer as unknown as ResizeObserver);
    }
  }
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
  globalThis.ResizeObserver = FakeResizeObserver as unknown as typeof ResizeObserver;
  globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
    rafSeq += 1;
    rafQueue.set(rafSeq, callback);
    return rafSeq;
  }) as typeof requestAnimationFrame;
  globalThis.cancelAnimationFrame = ((handle: number) => {
    rafQueue.delete(handle);
  }) as typeof cancelAnimationFrame;
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── Call-through spies on the real consumer seams ───────────────────────────

const realSync = ToolGroupMotionStore.prototype.sync;
const realNoteRendered = ToolGroupMotionStore.prototype.noteRendered;
const realAttach = StickController.prototype.attach;
const realSnapToEnd = StickController.prototype.snapToEnd;
const realRestoreViewport = StickController.prototype.restoreViewport;

interface SyncRecord {
  readonly baseline: boolean;
  readonly replaying: boolean;
  /** [rowId, toolCount] for each collapsible tool group the surface synced. */
  readonly groups: readonly (readonly [string, number])[];
}

const probe = {
  syncs: [] as SyncRecord[],
  flips: [] as { rowId: string; open: boolean }[],
  snaps: 0,
  restores: 0,
  motion: null as ToolGroupMotionStore | null,
  stick: null as StickController | null,
  el: null as HTMLElement | null,
};

beforeEach(() => {
  now = 10_000;
  probe.syncs = [];
  probe.flips = [];
  probe.snaps = 0;
  probe.restores = 0;
  probe.motion = null;
  probe.stick = null;
  probe.el = null;
  FakeResizeObserver.instances = [];
  savedViewportCache.clear();
  echoStore.reset();
  vi.spyOn(performance, "now").mockImplementation(() => now);
  vi.spyOn(ToolGroupMotionStore.prototype, "sync").mockImplementation(function (
    this: ToolGroupMotionStore,
    rows: readonly TranscriptRow[],
    baseline: boolean,
    replaying = false,
  ) {
    probe.motion = this;
    const groups: [string, number][] = [];
    for (const row of rows) {
      if (row.rowKind.kind === "toolGroup") {
        groups.push([row.id, row.rowKind.tools.length]);
      }
    }
    probe.syncs.push({ baseline, replaying, groups });
    return realSync.call(this, rows, baseline, replaying);
  });
  vi.spyOn(ToolGroupMotionStore.prototype, "noteRendered").mockImplementation(function (
    this: ToolGroupMotionStore,
    rowId: string,
    open: boolean,
    bodyHeight: number,
  ) {
    if (rowId.includes("#g")) {
      probe.flips.push({ rowId, open });
    }
    return realNoteRendered.call(this, rowId, open, bodyHeight);
  });
  vi.spyOn(StickController.prototype, "attach").mockImplementation(function (this: StickController, el: HTMLElement) {
    probe.stick = this;
    probe.el = el;
    return realAttach.call(this, el);
  });
  vi.spyOn(StickController.prototype, "snapToEnd").mockImplementation(function (this: StickController) {
    probe.snaps += 1;
    return realSnapToEnd.call(this);
  });
  vi.spyOn(StickController.prototype, "restoreViewport").mockImplementation(function (
    this: StickController,
    scrollTop: number,
    ownTurn: OwnTurnAnchor | null,
    distanceFromBottom: number,
  ) {
    probe.restores += 1;
    return realRestoreViewport.call(this, scrollTop, ownTurn, distanceFromBottom);
  });
});

// ── Mounted harness ─────────────────────────────────────────────────────────

interface Mounted {
  readonly store: TranscriptStore;
  readonly client: FakeClient;
  readonly cacheLoad: { promise: Promise<readonly SessionMessageEntry[] | null>; resolve: (value: readonly SessionMessageEntry[] | null) => void };
  /** The scroller element, captured via the controller's attach. */
  el(): HTMLElement;
  unmount(): void;
}

const mounted: Mounted[] = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  vi.restoreAllMocks();
  document.body.replaceChildren();
  rafQueue.clear();
  echoStore.reset();
  savedViewportCache.clear();
});

function mountTranscript(options: { strict?: boolean } = {}): Mounted {
  const client = new FakeClient();
  const cacheLoad = deferred<readonly SessionMessageEntry[] | null>();
  const cache: TranscriptCache = { load: () => cacheLoad.promise, save: () => Promise.resolve() };
  const store = new TranscriptStore(client as unknown as EngineClient, CHAT, { cache });
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const tree = createElement(TranscriptView, {
    client: client as unknown as EngineClient,
    docId: CHAT,
    deviceId: "dev",
    store,
  });
  act(() => {
    root.render(options.strict === true ? createElement(StrictMode, null, tree) : tree);
  });
  let unmounted = false;
  const handle: Mounted = {
    store,
    client,
    cacheLoad,
    el() {
      if (probe.el === null) {
        throw new Error("the stick controller never attached a scroller");
      }
      return probe.el;
    },
    unmount() {
      if (unmounted) {
        return;
      }
      unmounted = true;
      act(() => {
        root.unmount();
      });
      container.remove();
      store.dispose();
    },
  };
  mounted.push(handle);
  return handle;
}

/** Deterministic scroller geometry on the element itself (jsdom lays out nothing). */
function stubScrollerGeometry(el: HTMLElement, dims: { clientHeight: number; scrollHeight: number }): void {
  let top = 0;
  Object.defineProperty(el, "clientHeight", { configurable: true, get: () => dims.clientHeight });
  Object.defineProperty(el, "scrollHeight", { configurable: true, get: () => dims.scrollHeight });
  Object.defineProperty(el, "scrollTop", {
    configurable: true,
    get: () => top,
    set: (value: number) => {
      top = value;
    },
  });
}

/** Settle the deferred cache load (the offline seed) through React. */
async function settleCache(handle: Mounted, entries: readonly SessionMessageEntry[] | null): Promise<void> {
  await act(async () => {
    handle.cacheLoad.resolve(entries);
  });
}

/** Raw store publications (supplementary; the sync log is the consumer evidence). */
function publicationLog(store: TranscriptStore): { replay: string; generation: number }[] {
  const publications: { replay: string; generation: number }[] = [];
  store.subscribe(() => {
    const snap = store.getSnapshot();
    publications.push({ replay: snap.replay, generation: snap.generation });
  });
  return publications;
}

function startsOf(rowId: string): (number | null)[] {
  const reveal = probe.motion?.revealOf(rowId);
  if (reveal === null || reveal === undefined) {
    throw new Error(`no reveal recorded for ${rowId}`);
  }
  return reveal.starts;
}

// ── The regressions ─────────────────────────────────────────────────────────

function runCachedResetCase(strict: boolean): void {
  it(`cached_transcript_generation_reset_rebaselines_without_rendering_pending${strict ? " (StrictMode)" : ""}`, async () => {
    const cached = [toolEntry("A", ["pwd"])];
    const handle = mountTranscript({ strict });
    stubScrollerGeometry(handle.el(), { clientHeight: 600, scrollHeight: 4000 });
    await settleCache(handle, cached);

    // The cache seed painted as the (first) baseline: closed history, one
    // initial restore, no arrival starts.
    expect(probe.snaps).toBe(1);
    expect(startsOf("A#g0").every((start) => start === null)).toBe(true);
    const syncsAfterSeed = probe.syncs.length;

    // The reported sequence: the new generation's pending commit and its
    // reset's populated commit land in ONE task — React coalesces them into a
    // single render, so the intermediate pending snapshot is never rendered.
    const publications = publicationLog(handle.store);
    const reset: SessionMessageEntry[] = [toolEntry("A", ["pwd"]), toolEntry("B", ["ls", "cat"])];
    act(() => {
      handle.client.emit({ contextUsage: null, reset }, 2);
    });

    // Raw evidence: the store DID publish pending then populated on the new
    // generation (supplementary — the gate is the consumer behavior below).
    expect(publications.map((p) => p.replay)).toEqual(["pending", "populated"]);
    expect(publications.map((p) => p.generation)).toEqual([2, 2]);
    // No render ever consumed the pending snapshot: not one sync ran in the
    // transient replaying mode after the seed settled.
    expect(probe.syncs.slice(syncsAfterSeed).every((call) => !call.replaying)).toBe(true);

    // The contract: the reset is recognized as a replay baseline ANYWAY —
    // one baseline sync carrying the reset's own rows, then the live pass.
    const baselineCalls = probe.syncs.filter((call) => call.baseline);
    expect(baselineCalls.length).toBe(2); // the seed, then the reset
    expect(baselineCalls[1]!.groups).toEqual([
      ["A#g0", 1],
      ["B#g0", 2],
    ]);
    // Replayed history gained no arrival starts and no new-group header —
    // and no group ever rendered open because of the reset.
    expect(startsOf("A#g0").every((start) => start === null)).toBe(true);
    expect(startsOf("B#g0").every((start) => start === null)).toBe(true);
    expect(probe.motion!.revealOf("B#g0")!.headerStartedAt).toBeNull();
    expect(probe.flips.filter((flip) => flip.rowId === "B#g0").every((flip) => flip.open === false)).toBe(true);
    // The same-chat reset did not re-run the initial viewport restore.
    expect(probe.snaps).toBe(1);
    expect(probe.restores).toBe(0);
  });
}

describe("mounted cache→reset replay baseline (ticket 69)", () => {
  runCachedResetCase(false);
  runCachedResetCase(true);

  function runSameGenerationCase(strict: boolean): void {
    it(`same_generation_reset_rebaselines_once_and_preserves_post_reset_arrivals${strict ? " (StrictMode)" : ""}`, async () => {
      const handle = mountTranscript({ strict });
      stubScrollerGeometry(handle.el(), { clientHeight: 600, scrollHeight: 4000 });
      await settleCache(handle, null);

      // The first live frame: a new-generation reset with one tool of history.
      act(() => {
        handle.client.emit({ contextUsage: null, reset: [toolEntry("A", ["pwd"])] }, 1);
      });
      expect(startsOf("A#g0").every((start) => start === null)).toBe(true);

      // A same-generation resubscribe (the desync recovery shape), its reset
      // — carrying GREW history (a second replayed tool on A) — and a genuine
      // post-reset arrival, all in ONE React batch.
      act(() => {
        handle.store.resubscribe();
        handle.client.emit({ contextUsage: null, reset: [toolEntry("A", ["pwd", "ls"])] }, 1);
        handle.client.emit(
          {
            contextUsage: null,
            upsert: [{ after: "A", entry: toolEntry("C", ["cat"]) }],
            append: [],
            remove: [],
            count: 2,
          },
          1,
        );
      });

      // Exactly one NEW baseline, and it carried the RESET's rows — the
      // coalesced delta's group is not part of the baseline.
      const baselineCalls = probe.syncs.filter((call) => call.baseline);
      expect(baselineCalls.length).toBe(2); // first live reset, then this one
      expect(baselineCalls[1]!.groups).toEqual([["A#g0", 2]]);
      // The replayed growth on A is history: no arrival start for the tool
      // that appeared while resubscribing.
      expect(startsOf("A#g0").every((start) => start === null)).toBe(true);
      // The genuinely post-reset group still arrives as live content.
      const revealC = probe.motion!.revealOf("C#g0")!;
      expect(revealC.headerStartedAt).not.toBeNull();
      expect(revealC.starts.some((start) => start !== null)).toBe(true);
    });
  }

  runSameGenerationCase(false);
  runSameGenerationCase(true);

  it("late_replay_preserves_escaped_anchor_and_own_turn", async () => {
    const handle = mountTranscript();
    stubScrollerGeometry(handle.el(), { clientHeight: 600, scrollHeight: 4000 });
    await settleCache(handle, [userEntry("U"), toolEntry("A", ["pwd"])]);
    expect(probe.snaps).toBe(1);
    const el = handle.el();

    // An own send installs the runway; the entry glide runs its frames.
    act(() => {
      echoStore.pushEcho({ messageId: "m1", chatId: CHAT, startedAtMs: Date.now(), text: "follow up", attachmentPaths: [] });
    });
    act(() => {
      pumpRaf(30);
    });
    expect(probe.stick!.ownTurn?.messageId).toBe("m1");

    // The user wheels up: the hold releases, the pin drops, the anchor lands.
    act(() => {
      el.scrollTop = 100;
      el.dispatchEvent(new Event("scroll"));
      pumpRaf(1);
    });
    expect(probe.stick!.pinned).toBe(false);
    expect(probe.stick!.ownTurn?.held).toBe(false);

    // The reset arrives LATE — long past the chat-switch arrival hard cap.
    now += 60_000;
    act(() => {
      handle.store.resubscribe();
      handle.client.emit({ contextUsage: null, reset: [userEntry("U"), toolEntry("A", ["pwd", "ls"])] }, 1);
    });
    act(() => {
      pumpRaf(3);
    });

    // No second initial restore, no re-engaged pin, the released runway
    // intact, and the escaped anchor pixel-stationary.
    expect(probe.snaps).toBe(1);
    expect(probe.restores).toBe(0);
    expect(probe.stick!.pinned).toBe(false);
    expect(probe.stick!.ownTurn?.messageId).toBe("m1");
    expect(probe.stick!.ownTurn?.held).toBe(false);
    expect(el.scrollTop).toBe(100);
    // The late reset baselined the grown history — no arrival starts.
    expect(startsOf("A#g0").every((start) => start === null)).toBe(true);

    // A late measurement batch changes nothing: no restore, no reclassify.
    act(() => {
      deliverHeights({ "A#g0": 60 });
      pumpRaf(2);
    });
    expect(el.scrollTop).toBe(100);
    expect(probe.snaps).toBe(1);
    expect(startsOf("A#g0").every((start) => start === null)).toBe(true);
  });
});

describe("mounted canvas-arrival artifacts (ticket 80)", () => {
  it("cache_seed_downgrades_stale_streaming_so_history_renders_closed", async () => {
    const handle = mountTranscript();
    stubScrollerGeometry(handle.el(), { clientHeight: 600, scrollHeight: 4000 });
    // A chat left mid-run: the cache saved the assistant entry with
    // `status: "streaming"`. A previous session's save cannot still be
    // streaming — the stale status would render the last tool group
    // auto-opened ("old tool calls opening") and then visibly close when
    // the authoritative reset settles it.
    const stale: SessionMessageEntry = { ...toolEntry("S", ["pwd", "ls"]), status: "streaming" };
    await settleCache(handle, [userEntry("U"), stale]);

    // The seed the surface received is downgraded to the interrupted-run
    // status, and the snapshot never claims streaming.
    const snap = handle.store.getSnapshot();
    expect(snap.entries[snap.entries.length - 1]!.status).toBe("aborted");
    expect(snap.streaming).toBe(false);
    // The group rendered CLOSED (no autoOpen): the rendered-open records
    // carry open=false, so there is no flip for the live reset to visibly
    // close either.
    expect(probe.flips.filter((flip) => flip.rowId === "S#g0").length).toBeGreaterThan(0);
    expect(
      probe.flips.filter((flip) => flip.rowId === "S#g0").every((flip) => !flip.open),
    ).toBe(true);

    // The authoritative reset lands later and settles the same closed state
    // — no fold tween is seeded (the thought-completion tracker saw the
    // resolved status from the seed already).
    act(() => {
      handle.client.emit(
        { contextUsage: null, reset: [userEntry("U"), toolEntry("S", ["pwd", "ls"])] },
        2,
      );
    });
    expect(
      probe.flips.filter((flip) => flip.rowId === "S#g0").every((flip) => !flip.open),
    ).toBe(true);
  });

  it("reset_after_the_seed_window_fell_settles_pinned_growth_atomically", async () => {
    const handle = mountTranscript();
    const el = handle.el();
    const dims = { clientHeight: 600, scrollHeight: 4000 };
    stubScrollerGeometry(el, dims);
    await settleCache(handle, [userEntry("U"), toolEntry("A", ["pwd"])]);
    // The seed's restore pinned the surface at the end.
    expect(probe.snaps).toBe(1);
    expect(el.scrollTop).toBe(3400);

    // The seed's arrival window has long fallen (past the hard cap).
    now += 60_000;

    // The authoritative reset replaces the seed with grown history: the
    // baseline consume re-arms the arrival window, so the reset's own
    // commit hard-writes the end instead of arming the stick spring (the
    // canvas route's visible "scrolling down" glide).
    act(() => {
      handle.client.emit(
        { contextUsage: null, reset: [userEntry("U"), toolEntry("A", ["pwd", "ls", "cat", "grep"])] },
        2,
      );
    });
    // The grown content measures taller (the scroll height grows with it).
    dims.scrollHeight = 4600;
    act(() => {
      deliverHeights({ "A#g0": 480 });
      pumpRaf(2);
    });
    // The measurement kick stayed inside the re-armed window: the new end
    // landed in ONE hard write — no eased partial steps toward it.
    expect(el.scrollTop).toBe(4600 - 600);
    expect(probe.snaps).toBe(1);
    expect(probe.restores).toBe(0);
  });
});
