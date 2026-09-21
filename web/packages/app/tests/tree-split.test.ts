import { describe, expect, it } from "vitest";
import {
  SPEED_SCALE,
  TREE_SPLIT_DEFAULT,
  TreeSidebarMotion,
  clampTreeWidth,
  isWide,
  narrowTreeWidth,
} from "../src/lib/tree-split";

/** `motion::RESIZE.total() * speed_scale()` (200ms on the web). */
const RESIZE_MS = 200 * SPEED_SCALE;

describe("tree split math (preview.rs:35-39, 475-477)", () => {
  it("clamps the free-drag width into [220, 360] and defaults to 286", () => {
    expect(TREE_SPLIT_DEFAULT).toBe(286);
    expect(clampTreeWidth(100)).toBe(220);
    expect(clampTreeWidth(300)).toBe(300);
    expect(clampTreeWidth(9999)).toBe(360);
  });

  it("narrow_tree_width is 44% of the surface, floored at 152, capped at treeWidth", () => {
    // 44% of 680 is 299.2, but the treeWidth cap pulls it to 286.
    expect(narrowTreeWidth(680, 286)).toBe(286);
    expect(narrowTreeWidth(680, 360)).toBeCloseTo(299.2, 4);
    expect(narrowTreeWidth(400, 286)).toBe(176);
    expect(narrowTreeWidth(300, 286)).toBe(152);
    expect(narrowTreeWidth(100, 220)).toBe(152);
  });

  it("is_wide at the 680px breakpoint", () => {
    expect(isWide(680)).toBe(true);
    expect(isWide(679.9)).toBe(false);
  });
});

describe("TreeSidebarMotion (preview.rs:94-133)", () => {
  it("sidebar_layout_changes_are_immediate_without_a_user_toggle", () => {
    const motion = new TreeSidebarMotion();
    const now = 1000;
    // Hidden by default: openness 0.
    expect(motion.sample(false, now, false)).toEqual([0, false]);
    // Going wide (the layout change) shows it immediately — no animation.
    expect(motion.sample(true, now, false)).toEqual([1, false]);
    expect(motion.sample(false, now, false)).toEqual([0, false]);
    // The explicit toggle arms a transition that starts from the CURRENT
    // openness (here: still at the open end).
    motion.animateTo(true, false, now);
    expect(motion.sample(false, now, false)).toEqual([1, true]);
  });

  it("sidebar_motion_reverses_from_its_current_width", () => {
    const motion = new TreeSidebarMotion();
    const now = 1000;
    expect(motion.sample(true, now, false)).toEqual([1, false]);
    motion.animateTo(true, false, now);
    expect(motion.sample(false, now, false)).toEqual([1, true]);
    const midway = now + RESIZE_MS * 0.4;
    const closing = motion.sample(false, midway, false)[0];
    expect(closing).toBeGreaterThan(0);
    expect(closing).toBeLessThan(1);
    motion.animateTo(false, true, midway);
    expect(motion.sample(true, midway, false)).toEqual([closing, true]);
    expect(motion.sample(true, midway + 10_000, false)).toEqual([1, false]);
    motion.animateTo(true, false, midway + 10_000);
    expect(motion.sample(false, midway + 20_000, false)).toEqual([0, false]);
  });

  it("sidebar_motion_snaps_when_reduced_motion_is_enabled", () => {
    const motion = new TreeSidebarMotion();
    const now = 1000;
    motion.sample(true, now, false);
    motion.animateTo(true, false, now);
    expect(motion.sample(false, now, true)).toEqual([0, false]);
    expect(motion.sample(true, now, true)).toEqual([1, false]);
    expect(motion.sample(true, now, false)).toEqual([1, false]);
  });
});
