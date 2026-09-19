import { describe, expect, it } from "vitest";
import { TITLEBAR_HEIGHT, TITLEBAR_TOP_PAD } from "../src/state/layout";
import {
  islandTarget,
  titlebarIslandVerticalGeometry,
} from "../src/components/titlebar";

/**
 * Ticket 34 — the titlebar island's pure logic, mirrored from the desktop:
 * `titlebar_island_vertical_geometry` (shell.rs:829-835) and `island_target`
 * (shell.rs:3990-4001, as amended by ticket 48's resolved-artwork
 * predicate). The tween's frames are `evalWidthTween`, already covered by
 * `tests/layout.test.ts`.
 */

/**
 * `island_stays_centered_on_controls_while_expanding` (shell.rs:8259-8270),
 * mirrored: the island grows 28→32 across the tween with the padded row's
 * center pinned constant — 4px of air around the 24px controls at full
 * expansion.
 */
describe("island_stays_centered_on_controls_while_expanding", () => {
  const center = (TITLEBAR_HEIGHT + TITLEBAR_TOP_PAD) * 0.5;

  it("the center never moves while the height grows 28→32", () => {
    for (let step = 0; step <= 20; step += 1) {
      const { top, height } = titlebarIslandVerticalGeometry(step / 20);
      expect(top + height * 0.5).toBe(center);
      expect(height).toBeGreaterThanOrEqual(28);
      expect(height).toBeLessThanOrEqual(32);
    }
  });

  it("full expansion centers the 24px controls with 4px of air", () => {
    expect(center).toBe(21);
    const { top, height } = titlebarIslandVerticalGeometry(1);
    expect(center - 12 - top).toBe(4);
    expect(top + height - (center + 12)).toBe(4);
  });

  it("clamps progress outside [0, 1] to the endpoints", () => {
    expect(titlebarIslandVerticalGeometry(-1).height).toBe(28);
    expect(titlebarIslandVerticalGeometry(2).height).toBe(32);
  });
});

describe("island_target", () => {
  it("is 1 exactly in the reported state — canvas, sidebar collapsed, background resolving", () => {
    expect(
      islandTarget({
        isChatRoute: true,
        hasSelectedChat: false,
        sidebarCollapsed: true,
        backgroundResolves: true,
      }),
    ).toBe(1);
  });

  it("is 0 in every counter-state", () => {
    const canvas = {
      isChatRoute: true,
      hasSelectedChat: false,
      sidebarCollapsed: true,
      backgroundResolves: true,
    };
    // A chat is selected.
    expect(islandTarget({ ...canvas, hasSelectedChat: true })).toBe(0);
    // The sidebar is open.
    expect(islandTarget({ ...canvas, sidebarCollapsed: false })).toBe(0);
    // Settings (or any non-chat route).
    expect(islandTarget({ ...canvas, isChatRoute: false })).toBe(0);
    // The background did not resolve.
    expect(islandTarget({ ...canvas, backgroundResolves: false })).toBe(0);
  });

  it("keys off the RESOLVED background, never the raw setting", () => {
    // A stored-but-undecodable entry falls back to the bundled default
    // (ticket 48's resolution): `backgroundResolves` stays true and the
    // island still shows — the caller passes the resolver's output, so
    // only a resolution failure (false) hides it.
    const gate = {
      isChatRoute: true,
      hasSelectedChat: false,
      sidebarCollapsed: true,
    };
    expect(islandTarget({ ...gate, backgroundResolves: true })).toBe(1);
    expect(islandTarget({ ...gate, backgroundResolves: false })).toBe(0);
  });
});
