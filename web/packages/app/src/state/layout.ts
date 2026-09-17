import { useSyncExternalStore } from "react";
import {
  CHAT_PANEL_MIN,
  SIDEBAR_DEFAULT,
  SIDEBAR_MAX,
  SIDEBAR_MIN,
  clampOr,
  uiSettings,
} from "./ui-settings";

/**
 * The shell's column geometry — the desktop's `shell.rs` layout state.
 *
 * Three columns share the window width: the sidebar (S), the conversation (C)
 * and the right pane (R). The desktop resolves them with three pure functions
 * (`shell.rs:189`, `:444`, `:450`) and this module is their port, so both
 * clients divide a window the same way:
 *
 *     C = max(0, V - S - R)
 *     R_max      = max(0, V - S - CHAT_PANEL_MIN)   // manual drag
 *     R_takeover = max(0, V - S)                    // expand
 *
 * The asymmetry is deliberate and load-bearing: a manual drag may never take
 * the conversation below [`CHAT_PANEL_MIN`], but takeover is allowed to consume
 * it completely. On a window too narrow to give both their minimums the *pane*
 * yields — it goes below its own floor so the chat keeps its 300px.
 *
 * The sidebar width lives here too, but its storage does not: the desktop
 * persists it in `ui-settings.json` and the browser's peer of that file is
 * `./ui-settings.ts`, so this module owns the geometry and delegates the
 * bytes. The right pane's width is per-chat and lives in `./right-pane.ts`,
 * matching the desktop's split between global widths and per-session flags.
 */

/**
 * `settings.rs`'s column bounds, re-exported from the settings store that owns
 * them so a clamp here and a heal on load can never drift apart.
 */
export { CHAT_PANEL_MIN, SIDEBAR_DEFAULT, SIDEBAR_MAX, SIDEBAR_MIN };

/** Below this the sidebar stops being a column and becomes a drawer. */
export const PHONE_MAX_WIDTH = 768;

export function conversationWidth(viewport: number, sidebar: number, right: number): number {
  return Math.max(0, viewport - sidebar - right);
}

/**
 * The widest the right pane may be dragged while the conversation retains its
 * floor. On unusually small windows this deliberately falls below the pane's
 * own minimum — see the module note.
 */
export function rightPaneMaxWidth(viewport: number, sidebar: number): number {
  return Math.max(0, viewport - sidebar - CHAT_PANEL_MIN);
}

/** Takeover width: unlike a drag, it may consume the conversation entirely. */
export function rightPaneTakeoverWidth(viewport: number, sidebar: number): number {
  return Math.max(0, viewport - sidebar);
}

// ---------------------------------------------------------------------------
// Titlebar row inset — `tabs.rs::render_session_title_bar`
// ---------------------------------------------------------------------------

/**
 * `shell.rs`'s titlebar constants. The window-control cluster is an OVERLAY on
 * the desktop, not a member of the title row, and the row pads itself past it —
 * which is what lets the identity sit at the sidebar's edge and glide with it.
 */
export const TITLEBAR_CLUSTER_PAD = 10;
export const TITLEBAR_CONTROL_GAP = 2;
export const TITLEBAR_GROUP_GAP = 8;
/** `TITLEBAR_IDENTITY_GAP` — SPACE_MD. */
export const TITLEBAR_IDENTITY_GAP = 12;
/** A 24px sidebar trigger, an 8px group gap, then two 24px history buttons. */
export const CLUSTER_BUTTONS_WIDTH = 24 * 3 + TITLEBAR_GROUP_GAP + TITLEBAR_CONTROL_GAP;
/** The new-session `+` budgets one slot so the title never sits under it. */
export const TITLEBAR_ACTION_SLOT_WIDTH = TITLEBAR_GROUP_GAP + 24;
const SPACE_LG = 16;

// ---------------------------------------------------------------------------
// Per-platform cluster geometry — `shell.rs:211-285`, ported whole so the web
// tests can assert the desktop's cases AND the web collapse (no traffic lights,
// no Linux captions: spacer 0, cluster start 10).
// ---------------------------------------------------------------------------

/** Where the cluster starts off macOS traffic lights (`left: fullscreen ? 12 : 88`). */
export function titlebarClusterStart(fullscreen: boolean): number {
  return fullscreen ? 12 : 88;
}

/** The spacer ahead of the cluster exists only to clear traffic lights. */
export function titlebarSpacerWidth(isMacos: boolean, fullscreen: boolean, containerPad: number): number {
  if (!isMacos) {
    return 0;
  }
  return Math.max(titlebarClusterStart(fullscreen) - containerPad, 0);
}

/** A row of `count` caption buttons on the cluster's 24px/2px rhythm. */
export function captionButtonsWidth(count: number): number {
  if (count === 0) {
    return 0;
  }
  return count * 24 + (count - 1) * 2;
}

/** Where the cluster's first button starts, from the window's left edge. */
export function clusterButtonsStart(
  isMacos: boolean,
  fullscreen: boolean,
  linuxLeftCaptions: number,
): number {
  if (isMacos) {
    return titlebarClusterStart(fullscreen);
  }
  if (linuxLeftCaptions > 0) {
    return 10 + captionButtonsWidth(linuxLeftCaptions) + 2;
  }
  return 10;
}

/** Left clearance a full-bleed header needs to start past the overlay cluster. */
export function clusterClearance(
  isMacos: boolean,
  fullscreen: boolean,
  linuxLeftCaptions: number,
  containerPad: number,
): number {
  return Math.max(
    clusterButtonsStart(isMacos, fullscreen, linuxLeftCaptions) +
      CLUSTER_BUTTONS_WIDTH +
      TITLEBAR_GROUP_GAP -
      containerPad,
    0,
  );
}

/**
 * `titlebar_new_session_alpha` (`shell.rs:193-199`): the `+` shows only while
 * an existing chat is selected on the chat route — never on the blank canvas,
 * never in Settings.
 */
export function titlebarNewSessionAlpha(isChatRoute: boolean, hasSelectedChat: boolean): number {
  return isChatRoute && hasSelectedChat ? 1 : 0;
}

/**
 * Where titlebar content may start: past the cluster, plus its identity gap.
 * The browser owns the window, so there are no traffic lights or caption
 * buttons to clear — `cluster_buttons_start` is a flat 10 off macOS.
 */
export const TITLEBAR_CONTENT_START =
  TITLEBAR_CLUSTER_PAD + CLUSTER_BUTTONS_WIDTH + TITLEBAR_IDENTITY_GAP;

/**
 * The title row's left inset — `render_session_title_bar`'s `row_left`.
 *
 * Normally the conversation column's own left edge (`sidebar + 16`), so the
 * identity lines up with the transcript beneath it and GLIDES as the sidebar
 * collapses, clamped so it never slides under the control cluster.
 *
 * In takeover the title hides and the pane's strip owns the whole band, so the
 * inset pulls back 8px LEFT of the sidebar seam: the strip's width is capped
 * to the room left after the row's 8px child gap, and starting 8 early cancels
 * that, landing its first chip exactly on the pane's own gutter.
 */
export function titlebarRowLeft(options: {
  readonly sidebar: number;
  readonly showsNewSession: boolean;
  readonly takeover: boolean;
}): number {
  const plusInset = options.showsNewSession ? TITLEBAR_ACTION_SLOT_WIDTH : 0;
  if (options.takeover) {
    // The − 14 cancels `TITLEBAR_IDENTITY_GAP(12)` minus the strip's own left
    // pad (`tabs.rs:216-218`): in takeover the pane's band owns the row and
    // its first chip must land on the pane's own gutter, not 12px past it.
    const clusterEnd = TITLEBAR_CONTENT_START - TITLEBAR_IDENTITY_GAP + plusInset - 14;
    return Math.max(options.sidebar - 8, clusterEnd);
  }
  return Math.max(options.sidebar + SPACE_LG, TITLEBAR_CONTENT_START + plusInset);
}

/** The titlebar's right inset — `TITLEBAR_ACTION_EDGE_INSET`. */
export const TITLEBAR_EDGE_INSET = 6;
/** The fixed pane toggle's slot: a 24px control and the gap before it. */
const TITLEBAR_TOGGLE_SLOT = 28;

/**
 * The pane header strip's width — `render_session_title_bar`'s
 * `animated_width`:
 *
 *     ((right_now - pr).min(avail) - 28).max(0)
 *
 * It rides the pane's own animated width, so the strip and the column move as
 * one. The `avail` cap matters: the row's left padding is part of its content
 * box, and a strip wider than what is left after it would overflow and clip at
 * the right edge instead of shrinking. The row's child gaps sit OUTSIDE the
 * strip, so they are budgeted too — one in takeover (the strip alone), two
 * with the title row present.
 */
export function titlebarPaneBandWidth(options: {
  readonly viewport: number;
  /** The pane's laid-out width — `right_now`. */
  readonly paneWidth: number;
  readonly rowLeft: number;
  readonly takeover: boolean;
}): number {
  const gapBudget = options.takeover ? 8 : 16;
  const avail = options.viewport - options.rowLeft - TITLEBAR_EDGE_INSET - gapBudget;
  const width = Math.min(options.paneWidth - TITLEBAR_EDGE_INSET, avail);
  return Math.max(0, width - TITLEBAR_TOGGLE_SLOT);
}

export interface SidebarLayout {
  /** The dragged width, retained across a collapse so reopening restores it. */
  readonly width: number;
  readonly collapsed: boolean;
}

/** The laid-out width: zero while collapsed, as in `shell.rs::sidebar_target`. */
export function sidebarTarget(state: SidebarLayout): number {
  return state.collapsed ? 0 : state.width;
}

export function clampSidebarWidth(width: number): number {
  return clampOr(width, SIDEBAR_MIN, SIDEBAR_MAX, SIDEBAR_DEFAULT);
}

/**
 * The sidebar's persisted geometry, projected out of the settings store.
 *
 * There is no state of its own here: `sidebarWidth`/`sidebarCollapsed` live in
 * `ui-settings.ts` like every other device-local preference, and this class is
 * the narrow view onto them the shell has always used. The projection is
 * cached on the two values it reads, not on the settings snapshot's identity,
 * so `useSyncExternalStore` keeps the same object — and the shell skips the
 * re-render — when some unrelated preference moves.
 */
class SidebarWidthStore {
  #cache: SidebarLayout | null = null;

  getSnapshot = (): SidebarLayout => {
    const settings = uiSettings.getSnapshot();
    const cached = this.#cache;
    if (
      cached !== null &&
      cached.width === settings.sidebarWidth &&
      cached.collapsed === settings.sidebarCollapsed
    ) {
      return cached;
    }
    const next: SidebarLayout = {
      width: settings.sidebarWidth,
      collapsed: settings.sidebarCollapsed,
    };
    this.#cache = next;
    return next;
  };

  subscribe = (listener: () => void): (() => void) => uiSettings.subscribe(listener);

  /** A drag sample: the pointer's x IS the width, clamped to the bounds. */
  setWidth(width: number): void {
    // A drag is a stream of samples — coalesce them into one write.
    uiSettings.update({ sidebarWidth: width, sidebarCollapsed: false }, "debounced");
  }

  toggleCollapsed(): void {
    uiSettings.update({ sidebarCollapsed: !this.getSnapshot().collapsed }, "immediate");
  }

  /** Double-clicking the seam restores the default (`shell.rs:7930`). */
  reset(): void {
    uiSettings.update({ sidebarWidth: SIDEBAR_DEFAULT, sidebarCollapsed: false }, "immediate");
  }
}

export const sidebarLayout = new SidebarWidthStore();

export function useSidebarLayout(): SidebarLayout {
  return useSyncExternalStore(sidebarLayout.subscribe, sidebarLayout.getSnapshot, () => ({
    width: SIDEBAR_DEFAULT,
    collapsed: false,
  }));
}

/**
 * The window width — the desktop stamps `viewport_width` every render and the
 * width functions above all need it.
 */
function subscribeViewport(listener: () => void): () => void {
  window.addEventListener("resize", listener);
  return () => window.removeEventListener("resize", listener);
}

export function useViewportWidth(): number {
  return useSyncExternalStore(
    subscribeViewport,
    () => window.innerWidth,
    () => SIDEBAR_DEFAULT + CHAT_PANEL_MIN,
  );
}
