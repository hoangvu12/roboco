import { describe, expect, it, vi } from "vitest";
import type { MessagePart, SessionMessageEntry, ToolCall } from "@roboco/proto";
import { parseMarkdown } from "../src/lib/markdown";
import { rowsForEntry, type TranscriptRow } from "../src/lib/transcript";
import { ToolGroupMotionStore } from "../src/lib/tool-motion";
import { ARRIVAL_HARD_CAP_MS, ARRIVAL_QUIESCE_MS, ChatArrivalWindow } from "../src/lib/chat-arrival";
import { StickController } from "../src/components/stick-controller";

/**
 * Ticket 58 — the chat-switch arrival. The desktop's switch is atomic
 * (`select_chat`, state.rs:1740-1792: rows re-derived and the restored
 * viewport applied in ONE frame; shell.rs:1837-1862 snaps the pane tweens;
 * composer.rs:5849-5874 ROUTE_SNAPS the morph): a switch renders its
 * destination state, and motion belongs to live streams. The web's ONE
 * predicate (`ChatArrivalWindow`) carries that contract — armed at the
 * switch remount, cleared once the measurement cascade quiesces — and every
 * arrival gate consults it: the scroller's spring, the tool groups' fold
 * tweens, the shimmer arming.
 */

const parse = (_key: string, text: string, live: boolean) => parseMarkdown(text, live);

function entry(id: string, parts: MessagePart[], fields: Partial<SessionMessageEntry> = {}): SessionMessageEntry {
  return { id, role: "assistant", parts, createdAt: 1758000000000, deviceId: "dev", status: null, ...fields };
}

function toolPart(id: string, call: ToolCall): MessagePart {
  return { kind: "tool", id, call, isError: false, resolved: true };
}

function exec(command: string): ToolCall {
  return { kind: "exec", command };
}

/** One collapsible tool-group row, built through the real row model. */
function toolGroupRow(entryId: string, commands: string[]): TranscriptRow {
  const e = entry(entryId, commands.map((command, ix) => toolPart(`c${ix}`, exec(command))));
  const rows = rowsForEntry(e, { parse });
  const group = rows.find((row) => row.rowKind.kind === "toolGroup");
  if (group === undefined) {
    throw new Error("expected a tool group row");
  }
  return group;
}

/** A minimal scroller: only the fields the controller reads and writes. */
type MutableScroller = HTMLElement & {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  addEventListener: () => void;
  removeEventListener: () => void;
  parentElement: null;
};

function fakeScroller(scrollHeight: number, clientHeight: number): MutableScroller {
  const el = {
    scrollTop: 0,
    scrollHeight,
    clientHeight,
    addEventListener: () => {},
    removeEventListener: () => {},
    parentElement: null,
  };
  return el as unknown as MutableScroller;
}

describe("ChatArrivalWindow (ticket 58 — the ONE arrival predicate)", () => {
  it("same-chat frames are not arrival; the switch arms the window", () => {
    const w = new ChatArrivalWindow();
    expect(w.isArrival(0)).toBe(false);
    expect(w.isArrival(999)).toBe(false);
    w.arm(1000);
    // Before the arm is an ordinary same-chat frame.
    expect(w.isArrival(999)).toBe(false);
    expect(w.isArrival(1000)).toBe(true);
    // Waiting for the first measure: the hard cap is the only bound.
    expect(w.isArrival(1000 + ARRIVAL_HARD_CAP_MS)).toBe(true);
    expect(w.isArrival(1000 + ARRIVAL_HARD_CAP_MS + 1)).toBe(false);
  });

  it("measurement batches extend the window; quiet frames close it", () => {
    const w = new ChatArrivalWindow();
    w.arm(1000);
    w.noteMeasure(1033);
    expect(w.isArrival(1033)).toBe(true);
    expect(w.isArrival(1033 + ARRIVAL_QUIESCE_MS)).toBe(true);
    expect(w.isArrival(1033 + ARRIVAL_QUIESCE_MS + 1)).toBe(false);
    // A later batch re-extends the quiesce deadline; the cap still bounds it.
    w.noteMeasure(1400);
    expect(w.isArrival(1400 + ARRIVAL_QUIESCE_MS)).toBe(true);
    expect(w.isArrival(1401 + ARRIVAL_HARD_CAP_MS)).toBe(false);
  });

  it("measures outside an armed window are inert; a later arm supersedes it", () => {
    const w = new ChatArrivalWindow();
    w.noteMeasure(500);
    expect(w.isArrival(500)).toBe(false);
    w.arm(1000);
    w.noteMeasure(1010);
    w.arm(2000);
    expect(w.isArrival(1999)).toBe(false);
    expect(w.isArrival(2000)).toBe(true);
  });
});

describe("StickController arrival (ticket 58 — no spring on a chat switch)", () => {
  it("chat_switch_arrival_restores_in_one_assignment_and_schedules_no_spring", () => {
    const raf = vi.fn(() => 1);
    vi.stubGlobal("requestAnimationFrame", raf);
    try {
      const arrival = new ChatArrivalWindow();
      const el = fakeScroller(4000, 600);
      const stick = new StickController({ onJumpVisibility: () => {}, arrival });
      stick.attach(el);

      // The switch lands: the first fill writes the end instantly — one
      // assignment, estimates and all.
      arrival.arm(performance.now());
      stick.snapToEnd();
      expect(el.scrollTop).toBe(3400);
      expect(raf).not.toHaveBeenCalled();

      // The settle cascade's measurement batches land; each per-commit kick
      // writes the CURRENT end directly — the viewport lands and stays, the
      // spring queue stays empty (no "scrolling down" glide).
      el.scrollHeight = 5200;
      stick.kick();
      expect(el.scrollTop).toBe(4600);
      el.scrollHeight = 6100;
      stick.kick();
      expect(el.scrollTop).toBe(5500);
      expect(raf).not.toHaveBeenCalled();

      // The window closed (cap passed): ordinary growth owns the spring again.
      arrival.arm(performance.now() - ARRIVAL_HARD_CAP_MS - 10);
      el.scrollHeight = 9000;
      stick.kick();
      expect(raf).toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("an anchored restore is ONE hard assignment — no poll frames, no spring", () => {
    const raf = vi.fn(() => 1);
    vi.stubGlobal("requestAnimationFrame", raf);
    try {
      const arrival = new ChatArrivalWindow();
      const el = fakeScroller(4000, 600);
      const stick = new StickController({ onJumpVisibility: () => {}, arrival });
      stick.attach(el);
      arrival.arm(performance.now());
      // The saved viewport is applied as a single clamped scrollTop write.
      stick.restoreViewport(1500, null, 1900);
      expect(el.scrollTop).toBe(1500);
      expect(stick.pinned).toBe(false);
      expect(raf).not.toHaveBeenCalled();
      // The post-measure correction is the per-commit anchor preserve — a
      // write, never a tween.
      stick.writePreserving(1525);
      expect(el.scrollTop).toBe(1525);
      expect(raf).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe("tool-group arrival gates (ticket 58 — folds and shimmer)", () => {
  it("no fold tween and no shimmer on the arrival frame; live flips animate", () => {
    const arrival = new ChatArrivalWindow();
    const motion = new ToolGroupMotionStore(arrival);
    const row = toolGroupRow("tools", ["pwd"]);

    // The switch lands: the baseline sync re-seeds the reveals WITHOUT
    // arming the shimmer (an already-loaded transcript's first paint).
    arrival.arm(performance.now());
    motion.sync([row], true);
    expect(motion.revealOf(row.id)!.shimmerStartedAt).toBeNull();

    // The rendered-open flip mid-arrival (the streaming-status settle/desync
    // flap — the "try to close the opened group tabs" report) records its
    // endpoint WITHOUT seeding a close tween: the group renders its final
    // fold state.
    motion.noteRendered(row.id, true, 120);
    motion.noteRendered(row.id, false, 120);
    expect(motion.groupFold(row.id)).toBeNull();

    // The window closed — same chat, live: a rendered-open flip seeds the
    // tween again, and a live sync arms the shimmer for live content.
    arrival.arm(performance.now() - ARRIVAL_HARD_CAP_MS - 10);
    motion.noteRendered(row.id, true, 120);
    motion.noteRendered(row.id, false, 120);
    expect(motion.groupFold(row.id)?.toggledAt).not.toBeNull();
    motion.sync([row], false);
    expect(motion.revealOf(row.id)!.shimmerStartedAt).not.toBeNull();
  });

  it("a store without the window keeps ticket 40's semantics unchanged", () => {
    const motion = new ToolGroupMotionStore();
    const row = toolGroupRow("tools", ["pwd"]);
    motion.sync([row], true);
    expect(motion.revealOf(row.id)!.shimmerStartedAt).not.toBeNull();
    motion.noteRendered(row.id, false, 0);
    expect(motion.revealOf(row.id)!.renderedOpen).toBe(false);
    // A same-chat flip seeds the tween with no window to consult.
    motion.noteRendered(row.id, true, 34);
    expect(motion.groupFold(row.id)?.toggledAt).not.toBeNull();
  });
});
