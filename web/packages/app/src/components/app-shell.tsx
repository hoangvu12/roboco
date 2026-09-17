import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { motion } from "@roboco/theme";
import { Link, Outlet, useNavigate, useRouter, useRouterState } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useFleet } from "../state/fleet";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { emitShortcut, onShortcut } from "../state/shortcuts";
import { useChrome } from "../state/chrome";
import {
  PHONE_MAX_WIDTH,
  conversationWidth,
  rightPaneMaxWidth,
  sidebarLayout,
  sidebarTarget,
  titlebarPaneBandWidth,
  titlebarRowLeft,
  useSidebarLayout,
  useViewportWidth,
} from "../state/layout";
import { resolvePaneWidth, rightPaneStore, useRightPane } from "../state/right-pane";
import { SidebarBody } from "./sidebar-body";
import { PaneSeam } from "./pane-seam";
import { RightPane, usePaneGlide } from "./right-pane";
import { RightTabStrip } from "./right-tab-strip";
import { EngineDrawer } from "./engine-drawer";
import { useConnectionState } from "./connection-state";
import { Titlebar } from "./titlebar";
import { TerminalProvider } from "../terminal/store";

/**
 * The app shell — the desktop's `shell.rs` chrome.
 *
 * Structure follows the desktop's root row (`shell.rs:8048`): one flex row
 * spanning the FULL window height, holding three columns —
 *
 *     [sidebar] [conversation] [right pane]
 *
 * — with the titlebar as an absolute glass overlay ON TOP of that row rather
 * than a band above it. That is why each column pads itself down by the
 * titlebar height instead of the bar pushing them: surfaces and seam hairlines
 * run the whole height, behind the bar, and the transcript can scroll under it.
 *
 * Both outer columns are resizable and both collapse. Their widths come from
 * `../state/layout`, which ports the desktop's three width functions verbatim
 * so a window divides the same way in both clients — including the rule that a
 * manual drag may never take the conversation below 300px while takeover may
 * consume it entirely.
 *
 * Collapse CLIPS rather than squeezes: the sidebar's outer column animates its
 * width while the content inside stays pinned at the dragged width, so rows
 * slide out of a shrinking window instead of reflowing through it
 * (`render_sidebar` caches its inner pane at a fixed width for exactly this).
 *
 * At phone widths the sidebar leaves the flow and becomes a drawer over the
 * content, the titlebar keeps its cluster, and the content owns the viewport.
 *
 * Global keyboard shortcuts mirror the desktop's wherever the browser allows:
 * Mod+N creates a new chat (skipped while typing), Mod+B toggles the sidebar,
 * and Escape closes the engine drawer.
 */
/** `motion::RESIZE` — the curve the columns glide on. */
const TAKEOVER_GLIDE_MS =
  motion.specs.find((spec) => spec.name === "resize")?.durationMs ?? 200;

export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const fleet = useFleet();
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const state = useConnectionState(status);
  const navigate = useNavigate();
  const router = useRouter();
  const chrome = useChrome();
  const sidebar = useSidebarLayout();
  const viewport = useViewportWidth();
  const sidebarWidth = sidebarTarget(sidebar);
  // The pane's owning chat, straight off the router. Deliberately NOT via the
  // chrome store: that is published from an effect and cleared on every dep
  // change, so the shell saw "no pane" for one commit on each toggle and tore
  // the column down mid-glide. The router's state is synchronous with the
  // navigation that actually changes which chat is on screen.
  const paneChatId = useRouterState({ select: (s) => chatIdOf(s.location.pathname) });
  // The pane's own per-chat state. `""` is inert — no chat, no pane.
  const pane = useRightPane(paneChatId ?? "");
  // What the pane resolves to WHEN OPEN, and what it lays out at right now.
  // Keeping the two apart is what lets the column animate between them: the
  // content keeps the open width while the column itself glides to zero.
  const paneOpenWidth = resolvePaneWidth({ ...pane, open: true }, viewport, sidebarWidth);
  const paneWidth = paneChatId !== null && pane.open ? paneOpenWidth : 0;

  const onNewChat = useCallback(() => {
    if (fleet.engines.length === 0) {
      // No engine paired: keyboard shortcut is the equivalent of the welcome
      // "Pair an engine" button.
      void navigate({ to: "/pair" });
      return;
    }
    emitShortcut("new-chat");
  }, [fleet.engines.length, navigate]);

  // One control, two meanings — the desktop's `toggle_sidebar` collapses the
  // column; at phone widths the same button opens the drawer over the content.
  // The breakpoint is the stylesheet's: asking at a wider one left a dead band
  // where the click flipped the drawer flag while CSS still drew the column.
  const onToggleSidebar = useCallback(() => {
    if (window.matchMedia(`(max-width: ${PHONE_MAX_WIDTH}px)`).matches) {
      setSidebarOpen((current) => !current);
      return;
    }
    sidebarLayout.toggleCollapsed();
  }, []);

  const onCloseDrawer = useCallback(() => {
    setSidebarOpen(false);
    setDrawerOpen(false);
  }, []);

  // The sidebar's user menu opens the engine drawer, whose open flag lives
  // here; the shortcut bus carries it rather than threading a prop through
  // the whole sidebar.
  useEffect(() => onShortcut("open-engines", () => setDrawerOpen(true)), []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      const mod = event.metaKey || event.ctrlKey;
      if (!mod) {
        return;
      }
      if (event.altKey || event.shiftKey) {
        return;
      }
      if (isEditableTarget(event.target)) {
        return;
      }
      if (event.key.toLowerCase() === "n") {
        event.preventDefault();
        onNewChat();
        return;
      }
      if (event.key.toLowerCase() === "b") {
        event.preventDefault();
        onToggleSidebar();
        return;
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onNewChat, onToggleSidebar]);

  // Escape closes the engine drawer; the chat-menu dialogs and the
  // picker popover already close themselves, and the terminal eats Esc
  // for its own key bindings.
  useEffect(() => {
    if (!drawerOpen && !sidebarOpen) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== "Escape") {
        return;
      }
      if (isEditableTarget(event.target)) {
        return;
      }
      event.preventDefault();
      onCloseDrawer();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen, sidebarOpen, onCloseDrawer]);

  const paired = fleet.engines.length > 0;
  const hasPane = paired && paneChatId !== null;
  const takeover = hasPane && pane.open && pane.expanded;
  // One glide, shared: `toggle_right_pane_expand` tweens the pane AND the
  // conversation together, so both columns have to read the same clock.
  const glide = usePaneGlide(hasPane && pane.open, takeover, hasPane ? paneOpenWidth : 0);
  // `main_takeover_tween` + `stable_panel_content_width`: across a takeover the
  // conversation is laid out at the LARGER of its two widths and clipped, so
  // the transcript is revealed or covered rather than re-wrapping through
  // every intermediate width.
  const conversationNow = conversationWidth(viewport, sidebarWidth, paneWidth);
  const conversationStable = useTakeoverStableWidth(takeover, conversationNow);
  const rowLeft = titlebarRowLeft({
    sidebar: sidebarWidth,
    showsNewSession: paired,
    takeover,
  });
  const shellClass = [
    "shell",
    sidebar.collapsed ? "shell-sidebar-collapsed" : "",
    sidebarOpen ? "shell-sidebar-open" : "",
    takeover ? "shell-pane-takeover" : "",
    // Clip the conversation for the whole glide, not just while takeover is
    // on: dropping the clip on the first frame of a collapse spills a
    // full-width transcript over the pane for 200ms.
    conversationStable !== null ? "shell-pane-gliding" : "",
  ]
    .filter((part) => part.length > 0)
    .join(" ");

  return (
    <div
      className={shellClass}
      style={
        {
          // Each outer column's laid-out width (zero when shut) and the width
          // its content keeps throughout the glide — see the clip note above.
          // The seams read these to stay parked on the moving edges.
          "--rb-sidebar-now": `${sidebarWidth}px`,
          "--rb-sidebar-content": `${sidebar.width}px`,
          "--rb-pane-now": `${paneWidth}px`,
          "--rb-pane-open": `${hasPane ? paneOpenWidth : 0}px`,
          // The title row's left inset, which tracks the sidebar so the
          // identity sits on the conversation's own edge and glides with a
          // collapse — `render_session_title_bar`'s `row_left`.
          "--rb-titlebar-row-left": `${rowLeft}px`,
          // The header strip rides the pane's animated width, capped to the
          // room the row has left — `animated_width`. Never `auto`: that made
          // it snap to full width in takeover while the column glided.
          "--rb-pane-band": `${titlebarPaneBandWidth({
            viewport,
            paneWidth,
            rowLeft,
            takeover,
          })}px`,
          // Auto outside a takeover glide, so the column is plain flex again.
          "--rb-main-stable": conversationStable === null ? "auto" : `${conversationStable}px`,
        } as CSSProperties
      }
    >
      <Titlebar
        onToggleSidebar={onToggleSidebar}
        onBack={() => router.history.back()}
        onForward={() => router.history.forward()}
        canBack={router.history.canGoBack()}
        canForward
        onNewSession={paired ? chrome.onNewSession ?? onNewChat : null}
        identity={chrome.identity}
        // Every pane control is shell-owned and synchronous with the store, so
        // the toggle's active state, the strip and the column all move on the
        // same frame.
        onTogglePane={hasPane ? () => rightPaneStore.toggle(paneChatId) : null}
        paneOpen={hasPane && pane.open}
        paneExpanded={hasPane && pane.expanded}
        // Mounted whether or not the pane is open: the band clips it to zero
        // when shut, so it can glide away with the column instead of blinking
        // out on the first frame of the close.
        paneTabs={
          hasPane ? <RightTabStrip chatId={paneChatId} pane={pane} /> : undefined
        }
        onToggleExpand={hasPane ? () => rightPaneStore.toggleExpanded(paneChatId) : null}
      />
      <aside className="sidebar">
        <div className="sidebar-inner">
          <SidebarBody key={fleet.active ?? "none"} />
        </div>
      </aside>
      {/*
        The seam floats over the sidebar/conversation seam with zero layout
        width, so the sidebar's right gutter stays exactly as wide as its left
        one — a real flex child read as lopsided spacing (`shell.rs:7997`). It
        sits outside the sidebar because that column clips its overflow.
      */}
      {!sidebar.collapsed && (
        <PaneSeam
          label="Resize sidebar"
          widthAt={(clientX) => clientX}
          onWidth={(width) => sidebarLayout.setWidth(width)}
          onReset={() => sidebarLayout.reset()}
          className="pane-seam-sidebar"
        />
      )}
      <div
        className="sidebar-backdrop"
        role="presentation"
        onClick={() => setSidebarOpen(false)}
      />
      <TerminalProvider>
        {/*
          `card` + `main` from `shell.rs`: the outer column is the flex
          remainder and the clip; the inner one carries the width the content
          is laid out at, pinned to the wider endpoint across a takeover so the
          transcript is revealed or covered rather than re-wrapping through
          every intermediate width.
        */}
        <main className="main panel">
          <div className="main-inner">
            {!paired ? (
              <Welcome />
            ) : (
              <>
                {session !== null && state.className !== "conn-connected" ? (
                  <div className={`banner ${state.parked ? "banner-alert" : ""}`} role="status">
                    <span className={`conn ${state.className}`}>
                      <span className={`dot ${state.dot}`} />
                      {state.label}
                    </span>
                    {state.detail !== null && <span className="banner-detail">{state.detail}</span>}
                    {state.pairable && (
                      <button type="button" className="btn btn-solid" onClick={() => void navigate({ to: "/pair" })}>
                        Pair again
                      </button>
                    )}
                  </div>
                ) : null}
                <Outlet />
              </>
            )}
          </div>
        </main>
        {/*
          The third column. It is chat-scoped chrome, so routes without one
          (Settings, the blank canvas) simply publish no owner and it is absent
          — their per-chat open flags survive the round trip untouched.
        */}
        {hasPane && (
          <RightPane chatId={paneChatId} pane={pane} openWidth={paneOpenWidth} glide={glide} />
        )}
      </TerminalProvider>
      {/*
        The pane's seam, parked on its left edge. Like the sidebar's it lives
        out here: the pane clips, and the target has to straddle both columns.
        Takeover derives its width from the viewport, so it carries no handle.
      */}
      {hasPane && pane.open && !pane.expanded && (
        <PaneSeam
          label="Resize panel"
          // Right-anchored: the pointer's x IS the seam, so the width is the
          // room left to the window's edge (`on_right_pane_drag`).
          widthAt={(clientX) => viewport - clientX}
          onWidth={(width) =>
            rightPaneStore.setWidth(
              paneChatId,
              width,
              rightPaneMaxWidth(viewport, sidebarWidth),
            )
          }
          onReset={() => rightPaneStore.resetWidth(paneChatId)}
          className="pane-seam-right"
        />
      )}
      <EngineDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />
    </div>
  );
}

/**
 * `main_takeover_tween` + `stable_panel_content_width`, as a hook: across a
 * takeover toggle the conversation lays out at the LARGER of its two widths
 * for the glide, and is clipped to the animating column. `null` once settled,
 * which hands the column back to plain flex.
 *
 * Only `expanded` triggers it — the desktop arms this tween in
 * `toggle_right_pane_expand` (both directions) and, for a plain open/close,
 * only when leaving takeover.
 */
function useTakeoverStableWidth(takeover: boolean, conversation: number): number | null {
  const [stable, setStable] = useState<number | null>(null);
  const previous = useRef({ takeover, conversation });

  useEffect(() => {
    const was = previous.current;
    previous.current = { takeover, conversation };
    if (was.takeover === takeover) {
      return;
    }
    setStable(Math.max(was.conversation, conversation));
    const timer = window.setTimeout(() => setStable(null), TAKEOVER_GLIDE_MS);
    return () => window.clearTimeout(timer);
  }, [takeover, conversation]);

  return stable;
}

/**
 * The chat a `/chat/$chatId` path names, or `null` anywhere else — the pane is
 * chat-scoped chrome, so Settings, the blank canvas and the changes page get
 * none. Matching the path rather than reading a route-published value keeps
 * this synchronous with navigation.
 */
function chatIdOf(pathname: string): string | null {
  const match = /^\/chat\/([^/]+)\/?$/.exec(pathname);
  if (match === null || match[1] === undefined) {
    return null;
  }
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

/** True when a keyboard event would land inside a typed-into element. */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.isContentEditable) {
    return true;
  }
  const tag = target.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
}

function Welcome() {
  return (
    <div className="empty-state">
      <Icon name="robocoLogo" size={44} className="empty-state-mark" />
      <h1>Roboco</h1>
      <p>This browser has no paired engine yet.</p>
      <Link className="btn btn-solid" to="/pair">
        Pair an engine
      </Link>
    </div>
  );
}
