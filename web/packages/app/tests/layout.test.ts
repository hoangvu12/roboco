import { describe, expect, it } from "vitest";
import {
  CHAT_PANEL_MIN,
  SIDEBAR_DEFAULT,
  SIDEBAR_MAX,
  SIDEBAR_MIN,
  TITLEBAR_CONTENT_START,
  CLUSTER_BUTTONS_WIDTH,
  TITLEBAR_ACTION_SLOT_WIDTH,
  captionButtonsWidth,
  clampSidebarWidth,
  clusterClearance,
  clusterButtonsStart,
  conversationWidth,
  rightPaneMaxWidth,
  rightPaneTakeoverWidth,
  sidebarLayout,
  sidebarTarget,
  titlebarPaneBandWidth,
  titlebarRowLeft,
  titlebarSpacerWidth,
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
    active: "changes",
    tabs: ["changes"],
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

  it("right_pane_ceiling_preserves_the_chat_floor (shell.rs:8283)", () => {
    // The desktop's own asserted pair, on its own window sizes.
    expect(rightPaneMaxWidth(1200, 256)).toBe(644);
    expect(rightPaneMaxWidth(800, 256)).toBe(244);
    expect(conversationWidth(800, 256, rightPaneMaxWidth(800, 256))).toBe(CHAT_PANEL_MIN);
  });

  it("right_pane_takeover_consumes_the_chat_column (shell.rs:8293)", () => {
    expect(rightPaneTakeoverWidth(1200, 256)).toBe(944);
    expect(conversationWidth(1200, 256, 944)).toBe(0);
    // The conversation floors at zero, never negative (shell.rs:8297).
    expect(conversationWidth(1320, 256, 520)).toBe(544);
    expect(conversationWidth(1320, 256, 1064)).toBe(0);
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

describe("titlebar cluster geometry", () => {
  it("titlebar_cluster_matches_roboco_window_controls (shell.rs:8447)", () => {
    // 24·3 controls + the 8px group gap + the 2px control gap.
    expect(CLUSTER_BUTTONS_WIDTH).toBe(82);
    // The `+`'s slot: the 8px group gap plus its own 24px control.
    expect(TITLEBAR_ACTION_SLOT_WIDTH).toBe(32);
  });

  it("titlebar_spacer_selects_per_platform_and_fullscreen (shell.rs:8462)", () => {
    // Off macOS there is no spacer at all — no phantom flex child.
    expect(titlebarSpacerWidth(false, false, 10)).toBe(0);
    expect(titlebarSpacerWidth(false, true, 10)).toBe(0);
    // macOS clears its traffic lights: 88 (12 fullscreen) minus the pad.
    expect(titlebarSpacerWidth(true, false, 10)).toBe(78);
    expect(titlebarSpacerWidth(true, true, 10)).toBe(2);
    // The browser owns the window: no captions to clear, cluster start flat.
    expect(titlebarSpacerWidth(false, false, 0)).toBe(0);
    expect(clusterButtonsStart(false, false, 0)).toBe(10);
    expect(TITLEBAR_CONTENT_START).toBe(104);
  });

  it("cluster_clearance_clears_the_overlay_buttons (shell.rs:8508)", () => {
    // Web: 10 + 82 + 8 − 10 = 90 — a full-bleed header starts past the
    // cluster with room for the group gap.
    expect(clusterClearance(false, false, 0, 10)).toBe(90);
    // macOS: the cluster starts at 88 instead of 10.
    expect(clusterClearance(true, false, 0, 10)).toBe(168);
    // Linux left captions: two caption buttons push the cluster right.
    expect(captionButtonsWidth(2)).toBe(50);
    expect(clusterClearance(false, false, 2, 10)).toBe(142);
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
