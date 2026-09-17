import type { ReactNode } from "react";
import { Icon, type IconName } from "@roboco/icons";

/**
 * The unified titlebar — the desktop's `render_titlebar_cluster` (left) and
 * `render_session_title_bar` (identity + trailing).
 *
 * Its shape, from `tabs.rs`:
 *
 *     [sidebar toggle] [back forward] [+] … [harness icon + title + target] … [toggle-changes]
 *
 * The trailing section is ONE fixed control — the right pane's open/close
 * toggle — a 28px `header_icon_button`. With the pane open, its surface tabs
 * and the expand button reveal to its left inside a band as wide as the pane.
 * Panel surfaces are tabs in that pane, never buttons up here.
 *
 * Geometry is the desktop's to the pixel: a 38px bar
 * (`layout::TITLEBAR_HEIGHT`) whose content rides 4px lower than centre
 * (`TITLEBAR_TOP_PAD`); a 10px cluster inset (`TITLEBAR_CLUSTER_PAD`); 24px
 * cluster controls and 28px trailing controls on a 2px within-group rhythm
 * (`TITLEBAR_CONTROL_GAP`) with 8px between groups (`TITLEBAR_GROUP_GAP`) and
 * a 6px trailing inset (`TITLEBAR_ACTION_EDGE_INSET`). Icons are 16px in every
 * control (`window_control_button` / `nav_history_button` /
 * `header_icon_button` all render at size 16).
 *
 * The `+` is driven by `titlebar_new_session_alpha`: 1 only on the chat route
 * with a chat selected. It stays MOUNTED and cross-fades opacity on the
 * resize curve while `--rb-titlebar-row-left` transitions on the same curve —
 * the desktop tweens the two as one.
 *
 * The browser owns the window, so the desktop's minimize/maximize/close
 * cluster has no counterpart here, and the drag region is layout-only.
 *
 * The bar overlays the full window width: the sidebar and the main column
 * both start beneath it, which is why each pads itself down by
 * `--rb-titlebar-height`.
 */

export interface TitlebarProps {
  readonly onToggleSidebar: () => void;
  readonly onBack: () => void;
  readonly onForward: () => void;
  readonly canBack: boolean;
  readonly canForward: boolean;
  /**
   * `titlebar_new_session_alpha` — 1 while an existing chat is selected on
   * the chat route, else 0. The `+` stays mounted and fades between the two.
   */
  readonly newSessionAlpha: number;
  /** Null hides the `+` entirely (no handler — the blank canvas, Settings). */
  readonly onNewSession?: (() => void) | null;
  /** The identity group: harness mark, title, and the `space @ device` tag. */
  readonly identity?: ReactNode;
  /** The right pane's toggle. Absent when no chat owns a pane. */
  readonly onTogglePane?: (() => void) | null;
  readonly paneOpen?: boolean;
  /** The pane's surface tabs — revealed to the toggle's left while open. */
  readonly paneTabs?: ReactNode;
  readonly paneExpanded?: boolean;
  readonly onToggleExpand?: (() => void) | null;
}

export function Titlebar({
  onToggleSidebar,
  onBack,
  onForward,
  canBack,
  canForward,
  newSessionAlpha,
  onNewSession,
  identity,
  onTogglePane,
  paneOpen = false,
  paneTabs,
  paneExpanded = false,
  onToggleExpand,
}: TitlebarProps) {
  const takeover = paneOpen && paneExpanded;
  return (
    <div className={`titlebar ${takeover ? "titlebar-takeover" : ""}`}>
      <div className="titlebar-cluster">
        <WindowControl icon="sidebarMinimalisticLeft" label="Toggle sidebar" onClick={onToggleSidebar} />
        <div className="titlebar-group titlebar-nav">
          <NavHistoryButton icon="arrowLeft" label="Back" onClick={onBack} enabled={canBack} />
          <NavHistoryButton icon="arrowRight" label="Forward" onClick={onForward} enabled={canForward} />
        </div>
        {/*
          The `+` stays mounted: the fade is opacity on the same 200ms resize
          curve the row's left padding rides, and an unmount would blink the
          cluster. At alpha 0 it is invisible AND inert — `visibility` is
          transitioned with opacity, so it stays paintable through the fade
          out and then drops out of the tab order, matching the desktop's
          alpha>0.01 render gate.
        */}
        <div
          className="titlebar-new-session"
          data-alpha={onNewSession != null ? newSessionAlpha : 0}
          aria-hidden={onNewSession == null || newSessionAlpha === 0}
        >
          <WindowControl
            icon="plus"
            label="New session"
            onClick={onNewSession ?? (() => {})}
            tabIndex={onNewSession != null && newSessionAlpha > 0 ? undefined : -1}
          />
        </div>
      </div>
      {/*
        In panel takeover the header strip spans the whole band, so the
        identity hides for the duration rather than sitting under it.
      */}
      {identity !== undefined && !takeover && <div className="titlebar-identity">{identity}</div>}
      {/*
        The desktop's `flex_1` spacer (tabs.rs:353) — kept even when the
        identity is empty so the trailing group stays right-anchored.
      */}
      <div className="titlebar-fill" />
      {onTogglePane != null && (
        <div className="titlebar-trailing">
          {/*
            The pane's header. Its width is NOT a prop: the stylesheet derives
            it from `--rb-pane-now`, the same variable the pane column itself
            lays out on, so the two are one number and cannot drift — the
            desktop reads one `right_now` for both. Routing it through the
            chrome store instead put an effect-published copy a frame behind
            the column, which is what made the strip lurch.

            The band element stays mounted at width 0 while shut (a width
            transition needs something to animate from) but renders NO
            children then — the desktop unmounts the whole trailing band's
            content, and a hidden expand button must not stay tabbable
            inside a zero-width box.
          */}
          <div className="titlebar-pane-band">
            {paneOpen && (
              <div className="titlebar-pane-band-inner">
                <div className="titlebar-pane-tabs">{paneTabs}</div>
                {onToggleExpand != null && (
                  <HeaderIconButton
                    icon={paneExpanded ? "collapseArrows" : "expandArrows"}
                    label={paneExpanded ? "Collapse panel" : "Expand panel"}
                    onClick={onToggleExpand}
                  />
                )}
              </div>
            )}
          </div>
          <HeaderIconButton icon="sidebarMinimalistic" label="Toggle panel" onClick={onTogglePane} />
        </div>
      )}
    </div>
  );
}

/**
 * The desktop's `window_control_button` (`shell.rs:7369`): a 24px square
 * control with a 6px radius and a 16px glyph, quiet until hovered.
 *
 * There is no pressed/active variant: `window_control_button` has one resting
 * look and one hover, whatever the control it drives is currently doing.
 */
function WindowControl({
  icon,
  label,
  onClick,
  disabled = false,
  tabIndex,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
  disabled?: boolean;
  tabIndex?: number;
}) {
  return (
    <button
      type="button"
      className="window-control"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      tabIndex={tabIndex}
    >
      <Icon name={icon} size={16} />
    </button>
  );
}

/**
 * The desktop's `nav_history_button` (`shell.rs:7523`): enabled it is exactly
 * a `window_control_button`; disabled it is a NON-interactive 24px box — no
 * background at all, only the icon dimmed to `text_muted @ 35%` — that still
 * holds its place so the cluster never reflows.
 */
function NavHistoryButton({
  icon,
  label,
  onClick,
  enabled,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
  enabled: boolean;
}) {
  return (
    <button
      type="button"
      className="window-control"
      onClick={onClick}
      disabled={!enabled}
      aria-label={label}
      title={label}
    >
      <Icon name={icon} size={16} />
    </button>
  );
}

/**
 * The desktop's `header_icon_button` (`shell.rs:7552`) — the two trailing
 * controls: a 28px square with a 6px radius and a 16px glyph, transparent at
 * rest and `wash(0.11)` on hover. It has no active state; the pane toggle is
 * a plain button whatever the pane is doing.
 */
function HeaderIconButton({
  icon,
  label,
  onClick,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className="header-icon-button"
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      <Icon name={icon} size={16} />
    </button>
  );
}
