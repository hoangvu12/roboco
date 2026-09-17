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
 * The trailing section is ONE control — the right pane's open/close toggle.
 * It is the fixed right-edge anchor, like the sidebar control on the left;
 * with the pane open, its surface tabs and the expand button reveal to its
 * left inside a band as wide as the pane. Panel surfaces are tabs in that
 * pane, never buttons up here.
 *
 * Geometry is the desktop's to the pixel: a 38px bar
 * (`layout::TITLEBAR_HEIGHT`) whose content rides 4px lower than centre
 * (`TITLEBAR_TOP_PAD`) so the air above matches the perceived gap to the
 * content below; a 10px cluster inset (`TITLEBAR_CLUSTER_PAD`); 24px controls
 * on a 2px within-group rhythm (`TITLEBAR_CONTROL_GAP`) with 8px between
 * groups (`TITLEBAR_GROUP_GAP`) and a 6px trailing inset
 * (`TITLEBAR_ACTION_EDGE_INSET`).
 *
 * The browser owns the window, so the desktop's minimize/maximize/close
 * cluster has no counterpart here.
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
  /** Absent on the new-session canvas — opening a blank canvas from a blank canvas does nothing. */
  readonly onNewSession?: (() => void) | null;
  readonly newSessionDisabled?: boolean;
  /** The centred identity: harness mark, title, and the `space @ device` tag. */
  readonly identity?: ReactNode;
  /**
   * The right pane's toggle. Hidden on the canvas (nothing to diff yet), as
   * on the desktop.
   */
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
  onNewSession,
  newSessionDisabled = false,
  identity,
  onTogglePane,
  paneOpen = false,
  paneTabs,
  paneExpanded = false,
  onToggleExpand,
}: TitlebarProps) {
  return (
    <div className={`titlebar ${paneOpen && paneExpanded ? "titlebar-takeover" : ""}`}>
      <div className="titlebar-cluster">
        <WindowControl icon="sidebarMinimalisticLeft" label="Toggle sidebar" onClick={onToggleSidebar} />
        <div className="titlebar-group titlebar-nav">
          <WindowControl icon="arrowLeft" label="Back" onClick={onBack} disabled={!canBack} />
          <WindowControl icon="arrowRight" label="Forward" onClick={onForward} disabled={!canForward} />
        </div>
        {onNewSession != null && (
          <div className="titlebar-group">
            <WindowControl
              icon="plus"
              label="New session"
              onClick={onNewSession}
              disabled={newSessionDisabled}
            />
          </div>
        )}
      </div>
      {/*
        In panel takeover the header strip spans the whole band, so the
        identity hides for the duration rather than sitting under it.
      */}
      {identity !== undefined && !(paneOpen && paneExpanded) && (
        <div className="titlebar-identity">{identity}</div>
      )}
      {onTogglePane != null && (
        <div className="titlebar-trailing">
          {/*
            The pane's header. Its width is NOT a prop: the stylesheet derives
            it from `--rb-pane-now`, the same variable the pane column itself
            lays out on, so the two are one number and cannot drift — the
            desktop reads one `right_now` for both. Routing it through the
            chrome store instead put an effect-published copy a frame behind
            the column, which is what made the strip lurch.

            It stays mounted at width 0 while shut: a width transition needs
            something to animate from, and the toggle beside it must not shift
            when the pane closes. Its inner child is right-anchored at the open
            width, so the strip reveals from the toggle leftward instead of
            reflowing through every intermediate width.
          */}
          <div className="titlebar-pane-band">
            <div className="titlebar-pane-band-inner">
              <div className="titlebar-pane-tabs">{paneTabs}</div>
              {onToggleExpand != null && (
                <WindowControl
                  icon={paneExpanded ? "collapseArrows" : "expandArrows"}
                  label={paneExpanded ? "Collapse panel" : "Expand panel"}
                  onClick={onToggleExpand}
                />
              )}
            </div>
          </div>
          <WindowControl icon="sidebarMinimalistic" label="Toggle panel" onClick={onTogglePane} />
        </div>
      )}
    </div>
  );
}

/**
 * The desktop's `window_control_button` / `header_icon_button`: a 24px square
 * control with a 6px radius (`surface_chrome::CONTROL_RADIUS`) and a 14px
 * glyph, quiet until hovered. Disabled controls stay in place at reduced
 * opacity — the desktop's `nav_history_button` does not remove them, so the
 * cluster never reflows.
 *
 * There is no pressed/active variant: `window_control_button` has one resting
 * look and one hover, whatever the control it drives is currently doing.
 */
function WindowControl({
  icon,
  label,
  onClick,
  disabled = false,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className="window-control"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
    >
      <Icon name={icon} size={14} />
    </button>
  );
}
