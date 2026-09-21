import { describe, expect, it } from "vitest";
import {
  pinOrderedRows,
  pinnedDragScrollDelta,
  pinnedDragScrollStep,
  pinnedDragSnapshotIsValid,
  pinnedSessionClampedIndex,
  pinnedSessionDropIndex,
  pinnedSessionIsDraggable,
  projectPinnedFirst,
  reorderVisiblePins,
  retainKnownPins,
  SIDEBAR_SESSION_SLOT,
} from "../src/lib/sidebar-pins";
import type { ChatRow } from "../src/lib/view";

/*
 * The pinned-section's pure logic, ported against the desktop tests it
 * mirrors (`spaces.rs`'s `pinned_session_tests`, upstream zeron fd42e2ab).
 * Names follow the Rust tests one-for-one so the two suites read side by
 * side.
 */

describe("projectPinnedFirst", () => {
  it("pins_lead_without_changing_unpinned_recency", () => {
    const recency = ["newest", "p2", "middle", "p1", "oldest"];
    expect(projectPinnedFirst(recency, ["p1", "p2"])).toEqual(["p1", "p2", "newest", "middle", "oldest"]);
  });

  it("missing_duplicate_and_archived_pins_do_not_disturb_regular_rows", () => {
    const recency = ["b", "a", "c"];
    expect(projectPinnedFirst(recency, ["archived", "a", "a"])).toEqual(["a", "b", "c"]);
  });
});

describe("reorderVisiblePins", () => {
  it("filtered_pin_reorder_preserves_hidden_slots", () => {
    const saved = ["a1", "b1", "a2", "archived", "b2"];
    const visible = ["a1", "a2"];
    expect(reorderVisiblePins(saved, visible, 0, 1)).toEqual(["a2", "b1", "a1", "archived", "b2"]);
  });

  it("pin_reorder_rejects_invalid_or_noop_moves", () => {
    const saved = ["a", "b"];
    expect(reorderVisiblePins(saved, saved, 0, 0)).toEqual(saved);
    expect(reorderVisiblePins(saved, saved, 8, 0)).toEqual(saved);
  });
});

describe("retainKnownPins", () => {
  it("pin_cleanup_retains_archived_and_prunes_deleted", () => {
    const known = new Set(["active", "archived"]);
    expect(retainKnownPins(["active", "archived", "deleted", "active"], known)).toEqual(["active", "archived"]);
    expect(retainKnownPins(["active", "archived"], known)).toBe(null);
  });
});

describe("pinnedSessionDropIndex", () => {
  it("pinned_drop_index_quantizes_clamps_and_rejects_outside", () => {
    expect(pinnedSessionDropIndex(-1, 3)).toBe(null);
    expect(pinnedSessionDropIndex(0, 3)).toBe(0);
    expect(pinnedSessionDropIndex(SIDEBAR_SESSION_SLOT, 3)).toBe(1);
    expect(pinnedSessionDropIndex(500, 3)).toBe(null);
    expect(pinnedSessionDropIndex(0, 0)).toBe(null);
  });
});

describe("pinnedSessionClampedIndex", () => {
  it("sidebar_wide_pin_drag_clamps_to_the_nearest_pinned_slot", () => {
    expect(pinnedSessionClampedIndex(-50, 3)).toBe(0);
    expect(pinnedSessionClampedIndex(SIDEBAR_SESSION_SLOT, 3)).toBe(1);
    expect(pinnedSessionClampedIndex(500, 3)).toBe(2);
    expect(pinnedSessionClampedIndex(0, 0)).toBe(null);
  });
});

describe("pinnedSessionIsDraggable", () => {
  it("a_single_pin_does_not_start_a_drag", () => {
    expect(pinnedSessionIsDraggable(0)).toBe(false);
    expect(pinnedSessionIsDraggable(1)).toBe(false);
    expect(pinnedSessionIsDraggable(2)).toBe(true);
  });
});

describe("pinned drag autoscroll", () => {
  it("pinned_edge_scroll_is_proportional_and_lifecycle_bound", () => {
    const top = 100;
    const bottom = 300;
    expect(pinnedDragScrollDelta(200, top, bottom)).toBe(0);
    expect(pinnedDragScrollDelta(124, top, bottom)).toBe(-6);
    expect(pinnedDragScrollDelta(276, top, bottom)).toBe(6);
    expect(pinnedDragScrollStep(true, 4, 4, 20, 100, 6)).toBe(26);
    expect(pinnedDragScrollStep(false, 4, 4, 20, 100, 6)).toBe(null);
    expect(pinnedDragScrollStep(true, 3, 4, 20, 100, 6)).toBe(null);
  });
});

describe("pinnedDragSnapshotIsValid", () => {
  it("pinned_drag_snapshot_requires_every_original_pin", () => {
    const snapshot = ["a", "b"];
    expect(pinnedDragSnapshotIsValid("a", snapshot, new Set(["new", "a", "b"]))).toBe(true);
    expect(pinnedDragSnapshotIsValid("a", snapshot, new Set(["a"]))).toBe(false);
  });
});

describe("pinOrderedRows", () => {
  it("render_active_rows's pin split: pins lead in saved order, regulars keep theirs", () => {
    const rows = chatRows(["newest", "p2", "middle", "p1", "oldest"]);
    const { pinned, regular } = pinOrderedRows(rows, ["p1", "p2", "archived"]);
    expect(pinned.map((row) => row.chat.id)).toEqual(["p1", "p2"]);
    expect(regular.map((row) => row.chat.id)).toEqual(["newest", "middle", "oldest"]);
  });

  it("no pins leaves the rows untouched", () => {
    const rows = chatRows(["b", "a"]);
    const { pinned, regular } = pinOrderedRows(rows, []);
    expect(pinned).toEqual([]);
    expect(regular.map((row) => row.chat.id)).toEqual(["b", "a"]);
  });
});

// ---- helpers ----

function chatRows(ids: readonly string[]): ChatRow[] {
  return ids.map((id) => ({
    chat: {
      id,
      deviceId: "device-1",
      title: null,
      archived: false,
      cwd: null,
      branch: null,
      checkoutId: null,
      config: null,
      lastMessagePreview: null,
      lastMessageAt: null,
      createdAt: "2026-09-16T10:00:00Z",
    },
    status: "idle" as const,
    project: "~",
    folder: "~",
    harness: null,
    branch: null,
    timeAgo: "now",
    deviceId: "device-1",
    deviceName: null,
    deviceOffline: false,
    changeRequest: null,
  }));
}
