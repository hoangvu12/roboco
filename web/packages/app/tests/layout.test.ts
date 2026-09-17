import { describe, expect, it } from "vitest";
import {
  CHAT_PANEL_MIN,
  PANE_RESIZE_HITBOX_HALF_WIDTH,
  RESIZE_EDGE_NUDGE,
  SIDEBAR_DEFAULT,
  SIDEBAR_MAX,
  SIDEBAR_MIN,
  TITLEBAR_CONTENT_START,
  TITLEBAR_HEIGHT,
  clampSidebarWidth,
  conversationWidth,
  resizeBounceOffset,
  resizeDragSample,
  rightPaneMaxWidth,
  rightPaneTakeoverWidth,
  rightPanelContentWidth,
  sidebarLayout,
  sidebarTarget,
  stablePanelContentWidth,
  titlebarPaneBandWidth,
  titlebarRowLeft,
} from "../src/state/layout";
import {
  RIGHT_PANE_DEFAULT,
  RIGHT_PANE_MIN,
  resolvePaneWidth,
  type ChatPaneState,
} from "../src/state/right-pane";
import { uiSettings } from "../src/state/ui-settings";

/**
 * The shell's column arithmetic, against the desktop's (`shell.rs:189`,
 * `:444`, `:450` and `settings.rs`'s bounds). These numbers are the parity
 * contract: a window has to divide the same way in both clients.
 */

function pane(over: Partial<ChatPaneState> = {}): ChatPaneState {
  return {
    open: true,
    expanded: false,
    active: { kind: "diff", id: "d1" },
    tabs: [{ kind: "diff", id: "d1" }],
    width: RIGHT_PANE_DEFAULT,
    ...over,
  };
}

describe("column widths", () => {
  it("matches the desktop's constants", () => {
    expect([SIDEBAR_MIN, SIDEBAR_DEFAULT, SIDEBAR_MAX]).toEqual([224, 256, 400]);
    expect([RIGHT_PANE_MIN, RIGHT_PANE_DEFAULT, CHAT_PANEL_MIN]).toEqual([360, 520, 300]);
  });

  it("divides a 1440px window three ways", () => {
    expect(conversationWidth(1440, 256, 0)).toBe(1184);
    expect(conversationWidth(1440, 0, 0)).toBe(1440);
    expect(conversationWidth(1440, 256, 520)).toBe(664);
    expect(conversationWidth(1440, 0, 520)).toBe(920);
  });

  it("never reports a negative conversation", () => {
    expect(conversationWidth(600, 400, 400)).toBe(0);
  });

  it("caps a drag at the conversation's floor", () => {
    // 1440 - 256 - 300; the chat keeps 300 whatever the pointer does.
    expect(rightPaneMaxWidth(1440, 256)).toBe(884);
    expect(rightPaneMaxWidth(1440, 0)).toBe(1140);
    expect(conversationWidth(1440, 256, rightPaneMaxWidth(1440, 256))).toBe(CHAT_PANEL_MIN);
  });

  it("lets the pane — not the chat — yield on a window too small for both", () => {
    // 900 - 256 - 300 = 344, under the pane's own 360 minimum. The desktop
    // deliberately hands the scarce space to the conversation.
    const max = rightPaneMaxWidth(900, 256);
    expect(max).toBe(344);
    expect(max).toBeLessThan(RIGHT_PANE_MIN);
    expect(conversationWidth(900, 256, max)).toBe(CHAT_PANEL_MIN);
  });

  it("gives takeover everything but the sidebar", () => {
    expect(rightPaneTakeoverWidth(1440, 256)).toBe(1184);
    expect(rightPaneTakeoverWidth(1440, 0)).toBe(1440);
    expect(conversationWidth(1440, 256, rightPaneTakeoverWidth(1440, 256))).toBe(0);
  });
});

describe("sidebar width", () => {
  it("clamps a drag into the desktop's bounds", () => {
    expect(clampSidebarWidth(100)).toBe(SIDEBAR_MIN);
    expect(clampSidebarWidth(900)).toBe(SIDEBAR_MAX);
    expect(clampSidebarWidth(300)).toBe(300);
  });

  it("heals a corrupted width to the default", () => {
    expect(clampSidebarWidth(Number.NaN)).toBe(SIDEBAR_DEFAULT);
  });

  it("lays out at zero while collapsed, retaining the dragged width", () => {
    expect(sidebarTarget({ width: 320, collapsed: true })).toBe(0);
    expect(sidebarTarget({ width: 320, collapsed: false })).toBe(320);
  });
});

describe("titlebarRowLeft", () => {
  const row = (sidebar: number, over: { takeover?: boolean; showsNewSession?: boolean } = {}) =>
    titlebarRowLeft({
      sidebar,
      showsNewSession: over.showsNewSession ?? true,
      takeover: over.takeover ?? false,
    });

  it("starts titlebar content past the control cluster", () => {
    // 10 cluster pad + 82 buttons + 12 identity gap.
    expect(TITLEBAR_CONTENT_START).toBe(104);
  });

  it("puts the identity on the conversation's own left edge", () => {
    // The desktop capture: a 256px sidebar puts the identity at 272.
    expect(row(256)).toBe(272);
    expect(row(400)).toBe(416);
  });

  it("glides with the sidebar but never slides under the cluster", () => {
    // 104 + the 32px new-session slot. Collapsing stops here, not at 16.
    expect(row(0)).toBe(136);
    expect(row(100)).toBe(136);
    // Past the clamp it tracks the sidebar one-for-one.
    expect(row(130)).toBe(146);
  });

  it("drops the new-session slot when the + is absent", () => {
    expect(row(0, { showsNewSession: false })).toBe(104);
  });

  it("pulls back 8px left of the seam in takeover", () => {
    // The strip's own 8px pad then lands its first chip on the pane gutter.
    expect(row(256, { takeover: true })).toBe(248);
    // Still clears the cluster when the sidebar is collapsed.
    expect(row(0, { takeover: true })).toBe(104 - 12 + 32 - 14);
  });
});

describe("titlebarPaneBandWidth", () => {
  const band = (paneWidth: number, over: { takeover?: boolean; sidebar?: number } = {}) => {
    const sidebar = over.sidebar ?? 256;
    const takeover = over.takeover ?? false;
    return titlebarPaneBandWidth({
      viewport: 1440,
      paneWidth,
      rowLeft: titlebarRowLeft({ sidebar, showsNewSession: true, takeover }),
      takeover,
    });
  };

  it("is zero while the pane is shut", () => {
    expect(band(0)).toBe(0);
  });

  it("tracks the pane, less the edge inset and the toggle slot", () => {
    // 520 - 6 - 28. The strip's right edge then lands on the pane's.
    expect(band(520)).toBe(486);
    expect(band(360)).toBe(326);
  });

  it("rides intermediate widths, so it glides with the column", () => {
    expect(band(260)).toBe(226);
  });

  it("stays capped to the room the row has left", () => {
    // A pane wider than the row can hold must not overflow and clip right.
    const wide = band(1400);
    // avail = 1440 - 272 - 6 - 16 = 1146; minus the 28px toggle slot.
    expect(wide).toBe(1118);
  });

  it("still animates in takeover rather than snapping to full width", () => {
    const full = band(1184, { takeover: true });
    // avail = 1440 - 248 - 6 - 8 = 1178; pane-6 = 1178. Same, minus 28.
    expect(full).toBe(1150);
    // Half way through the glide it is genuinely half way.
    expect(band(700, { takeover: true })).toBe(666);
  });
});

describe("resolvePaneWidth", () => {
  it("is zero while closed", () => {
    expect(resolvePaneWidth(pane({ open: false }), 1440, 256)).toBe(0);
  });

  it("uses the stored width when it fits", () => {
    expect(resolvePaneWidth(pane(), 1440, 256)).toBe(520);
  });

  it("shrinks a too-wide stored width without destroying it", () => {
    const stored = pane({ width: 1000 });
    // 1440 - 256 - 300 = 884.
    expect(resolvePaneWidth(stored, 1440, 256)).toBe(884);
    // Widen the window and the user's own width comes back.
    expect(resolvePaneWidth(stored, 1920, 256)).toBe(1000);
  });

  it("follows the sidebar: collapsing it hands the room to the conversation", () => {
    const stored = pane({ width: 1000 });
    expect(resolvePaneWidth(stored, 1440, 0)).toBe(1000);
  });

  it("takes the window over when expanded", () => {
    expect(resolvePaneWidth(pane({ expanded: true }), 1440, 256)).toBe(1184);
  });
});

describe("right_pane_ceiling_preserves_the_chat_floor", () => {
  it("asserted values from shell.rs:8283-8291", () => {
    expect(rightPaneMaxWidth(1200, 256)).toBe(644);
    // 800 - 256 - 300 = 244, below the pane's own 360 floor — the pane yields.
    expect(rightPaneMaxWidth(800, 256)).toBe(244);
    expect(rightPaneMaxWidth(800, 256)).toBeLessThan(RIGHT_PANE_MIN);
  });
});

describe("right_pane_takeover_consumes_the_chat_column", () => {
  it("asserted values from shell.rs:8293-8297", () => {
    expect(rightPaneTakeoverWidth(1200, 256)).toBe(944);
    expect(conversationWidth(1320, 256, 520)).toBe(544);
    expect(conversationWidth(1320, 256, 1064)).toBe(0);
  });
});

describe("right_pane_takeover_control_reverses_direction", () => {
  it("flips between the stored width and the takeover width", () => {
    // `toggle_right_pane_expand` swaps the transition's direction; the pure
    // half of that is the target flipping between the two width rules.
    const stored = pane({ width: 520 });
    expect(resolvePaneWidth(stored, 1320, 256)).toBe(520);
    expect(resolvePaneWidth({ ...stored, expanded: true }, 1320, 256)).toBe(1064);
    expect(resolvePaneWidth({ ...stored, expanded: false }, 1320, 256)).toBe(520);
  });
});

describe("right_panel_content_keeps_the_larger_width_only_during_transition", () => {
  it("asserted values from shell.rs:8416-8444", () => {
    // Open/close: the content holds the LARGER endpoint (520, not 0).
    expect(rightPanelContentWidth(0, [520, 0], null)).toBe(520);
    // Takeover: the content tween OVERRIDES — it tracks the frame (760).
    expect(rightPanelContentWidth(1064, [520, 1064], 760)).toBe(760);
    // Steady state: the target itself.
    expect(stablePanelContentWidth(520, null)).toBe(520);
    expect(stablePanelContentWidth(0, [520, 0])).toBe(520);
  });
});

describe("pane_resize_hitboxes_yield_the_titlebar_chrome", () => {
  it("the 20px target starts TITLEBAR_HEIGHT down", () => {
    // shell.rs:173-175, asserted at 8401-8406: vertical seams yield the
    // global titlebar so its chrome stays clickable across an animated
    // pane boundary.
    expect(PANE_RESIZE_HITBOX_HALF_WIDTH).toBe(10);
    expect(TITLEBAR_HEIGHT).toBe(38);
  });
});

describe("sidebar_drag_nudges_each_edge_once_until_rearmed", () => {
  it("a held pointer bounces once per edge, rearmed by leaving it", () => {
    // shell.rs:8137: the latch is what makes a held pointer produce exactly
    // one nudge instead of restarting the animation on every drag event.
    // First arrival at the min edge (latch empty) arms the bounce.
    let sample = resizeDragSample(180, SIDEBAR_MIN, SIDEBAR_MAX, null, false);
    expect(sample.width).toBe(SIDEBAR_MIN);
    expect(sample.edge).toBe("min");
    expect(sample.startsBounce).toBe(true);
    let latched = sample.edge;
    // Held at the same edge: clamped, at the edge, but NO second bounce.
    sample = resizeDragSample(178, SIDEBAR_MIN, SIDEBAR_MAX, latched, false);
    expect(sample.startsBounce).toBe(false);
    // The max edge is a different edge: it bounces even mid-press.
    sample = resizeDragSample(500, SIDEBAR_MIN, SIDEBAR_MAX, latched, false);
    expect(sample.edge).toBe("max");
    expect(sample.startsBounce).toBe(true);
    latched = sample.edge;
    // Leaving the edge rearms the latch.
    sample = resizeDragSample(300, SIDEBAR_MIN, SIDEBAR_MAX, latched, false);
    expect(sample.edge).toBeNull();
    expect(sample.width).toBe(300);
    expect(resizeDragSample(180, SIDEBAR_MIN, SIDEBAR_MAX, null, false).startsBounce).toBe(true);
  });
});

describe("sidebar_drag_stays_exact_in_range_and_reduced_motion_never_nudges", () => {
  it("in-range widths pass through unclamped", () => {
    const sample = resizeDragSample(320, SIDEBAR_MIN, SIDEBAR_MAX, null, false);
    expect(sample.width).toBe(320);
    expect(sample.edge).toBeNull();
    expect(sample.startsBounce).toBe(false);
  });

  it("reduced motion never arms a bounce, even at a fresh edge", () => {
    // shell.rs:8166 — `eval_resize_edge_bounce` returns 0 under reduce.
    const sample = resizeDragSample(180, SIDEBAR_MIN, SIDEBAR_MAX, null, true);
    expect(sample.width).toBe(SIDEBAR_MIN);
    expect(sample.edge).toBe("min");
    expect(sample.startsBounce).toBe(false);
  });
});

describe("right_pane_uses_the_shared_clamp_and_edge_latch", () => {
  it("clamps into [RIGHT_PANE_MIN, max] with the same edge rules", () => {
    // shell.rs:8185: the right pane shares resize_drag_sample; max is
    // right_pane_max_width (the chat floor already priced in).
    const max = rightPaneMaxWidth(1440, 256);
    expect(max).toBe(884);
    const low = resizeDragSample(200, RIGHT_PANE_MIN, max, null, false);
    expect(low.width).toBe(RIGHT_PANE_MIN);
    expect(low.edge).toBe("min");
    expect(low.startsBounce).toBe(true);
    const high = resizeDragSample(2000, RIGHT_PANE_MIN, max, "min", false);
    expect(high.width).toBe(884);
    expect(high.edge).toBe("max");
    expect(high.startsBounce).toBe(true);
    const held = resizeDragSample(2000, RIGHT_PANE_MIN, max, "max", false);
    expect(held.startsBounce).toBe(false);
  });
});

describe("sidebar_bounce_has_rounded_out_and_return_phases", () => {
  it("rises 5px out over the first 32%, returns over the rest, zero at joins", () => {
    // shell.rs:8208 — a two-phase smoothstep pulse with zero velocity at
    // both joins. Outbound share 0.32 of 220ms = 70.4ms.
    expect(resizeBounceOffset("max", 0)).toBe(0);
    expect(resizeBounceOffset("max", 220)).toBe(0);
    expect(resizeBounceOffset("max", -50)).toBe(0);
    expect(resizeBounceOffset("max", 10_000)).toBe(0);

    const peak = resizeBounceOffset("max", 70);
    expect(peak).toBeGreaterThan(4.9);
    expect(peak).toBeLessThanOrEqual(RESIZE_EDGE_NUDGE);
    // The return phase is genuinely on the way back by half the window.
    const half = resizeBounceOffset("max", 110);
    expect(half).toBeGreaterThan(0);
    expect(half).toBeLessThan(peak);
    // Monotone out: 10ms in is smaller than 40ms in.
    expect(resizeBounceOffset("max", 10)).toBeLessThan(resizeBounceOffset("max", 40));
    // The min edge mirrors: negative offsets.
    expect(resizeBounceOffset("min", 70)).toBe(-peak);
    // No edge, no offset.
    expect(resizeBounceOffset(null, 70)).toBe(0);
  });
});

describe("sidebarLayout", () => {
  it("projects the persisted geometry out of the settings store", () => {
    // The geometry lives in ui-settings.ts now; this store is the shell's view
    // onto it, so a write through either side is visible from both.
    uiSettings.update({ sidebarWidth: 300, sidebarCollapsed: false }, "immediate");
    expect(sidebarLayout.getSnapshot()).toEqual({ width: 300, collapsed: false });

    sidebarLayout.toggleCollapsed();
    expect(uiSettings.getSnapshot().sidebarCollapsed).toBe(true);
    // A collapse keeps the dragged width so reopening restores it.
    expect(sidebarTarget(sidebarLayout.getSnapshot())).toBe(0);
    expect(sidebarLayout.getSnapshot().width).toBe(300);

    // A drag sample is clamped before it ever reaches memory, and it reopens
    // the sidebar (`shell.rs`'s drag handler).
    sidebarLayout.setWidth(9999);
    expect(sidebarLayout.getSnapshot()).toEqual({ width: SIDEBAR_MAX, collapsed: false });

    sidebarLayout.reset();
    expect(sidebarLayout.getSnapshot()).toEqual({ width: SIDEBAR_DEFAULT, collapsed: false });
  });

  it("hands useSyncExternalStore a stable snapshot across unrelated changes", () => {
    const before = sidebarLayout.getSnapshot();
    uiSettings.update({ terminalHeight: 400 }, "immediate");
    expect(sidebarLayout.getSnapshot()).toBe(before);
  });
});
