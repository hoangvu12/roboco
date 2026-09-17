import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties, DragEvent } from "react";
import { motion } from "@roboco/theme";
import { Link, Outlet, useNavigate, useRouter, useRouterState } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useFleet } from "../state/fleet";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { emitShortcut, onShortcut } from "../state/shortcuts";
import { useChrome } from "../state/chrome";
import {
  ESCAPE_PRIORITY,
  installEscapeLadder,
  registerEscapeSurface,
  resolveShellEscape,
} from "../state/escape";
import {
  navEntryForPath,
  navEntryPath,
  navHistory,
  sameNavEntry,
  useNavHistory,
  type NavEntry,
} from "../state/nav-history";
import {
  PHONE_MAX_WIDTH,
  TITLEBAR_CONTENT_START,
  conversationWidth,
  rightPaneMaxWidth,
  sidebarLayout,
  sidebarTarget,
  titlebarNewSessionAlpha,
  titlebarPaneBandWidth,
  titlebarRowLeft,
  useSidebarLayout,
  useViewportWidth,
} from "../state/layout";
import { effectiveIndicator } from "../lib/view";
import { sendInterrupt } from "../lib/composer-actions";
import { sidebarNotice } from "../state/notice";
import { uiSettings } from "../state/ui-settings";
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
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const nav = useNavHistory();
  // `apply_nav`'s gate: the navigation a Back/Forward click performs must not
  // itself push, or the cursor would be dragged straight back to where it
  // started. We record the entry we asked for and let the route effect below
  // recognise — and swallow — exactly that one arrival.
  const appliedNavRef = useRef<NavEntry | null>(null);

  // The route-driven half of the nav model: every navigation the USER makes
  // (a chat row, a settings item, a link) is a visit. Paths outside the model
  // (`/pair`, `/files`) leave the stack alone.
  useEffect(() => {
    const entry = navEntryForPath(pathname);
    const applied = appliedNavRef.current;
    appliedNavRef.current = null;
    if (entry === null) {
      return;
    }
    if (applied !== null && sameNavEntry(applied, entry)) {
      return;
    }
    navHistory.visit(entry);
  }, [pathname]);

  const onNavWalk = useCallback(
    (entry: NavEntry | null) => {
      if (entry === null) {
        return;
      }
      appliedNavRef.current = entry;
      // Raw path rather than `navigate({to})`: a `NavEntry` names a route
      // computed at runtime (any chat id, any settings section), which the
      // typed router's literal `to` union cannot express.
      router.history.push(navEntryPath(entry));
    },
    [router],
  );
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

  // The shell's Escape model — one capture-phase ladder (installed once) plus
  // the bubble-phase interrupt (`on_key_down` → `resolve_shell_escape`). The
  // engine drawer and the phone sidebar register as a ladder surface; the
  // popovers and dialogs that still close on their own Escape listeners are
  // theirs to migrate onto the ladder in their tickets.
  useEffect(() => installEscapeLadder(), []);
  useEffect(() => {
    if (!drawerOpen && !sidebarOpen) {
      return;
    }
    return registerEscapeSurface(ESCAPE_PRIORITY.webDrawer, () => {
      onCloseDrawer();
      return true;
    });
  }, [drawerOpen, sidebarOpen, onCloseDrawer]);

  // Interrupts already in flight for a chat — the desktop's
  // `composer.is_interrupting(chat_id)`. A second Escape while the Stop
  // request is still on the wire resolves to Ignored instead of stacking.
  const interruptingRef = useRef<ReadonlySet<string>>(new Set());
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== "escape") {
        return;
      }
      const route = pathname.startsWith("/settings") ? "settings" : "chat";
      const selectedChatId = route === "chat" ? chatIdOf(pathname) : null;
      const indicator =
        selectedChatId === null || session === null
          ? null
          : effectiveIndicator(
              session.cache.getSnapshot().statuses.rows.find((row) => row.chatId === selectedChatId),
              Date.now(),
            );
      const outcome = resolveShellEscape({
        key: event.key,
        blockingOverlay: false,
        escapeStopsActiveAgent: uiSettings.getSnapshot().escapeStopsActiveAgent,
        route,
        interrupting: selectedChatId !== null && interruptingRef.current.has(selectedChatId),
        indicator: indicator === "none" ? null : indicator,
        selectedChatId,
      });
      if (outcome.kind !== "interruptChat" || session === null) {
        return;
      }
      event.preventDefault();
      const chatId = outcome.chatId;
      const inFlight = new Set(interruptingRef.current);
      inFlight.add(chatId);
      interruptingRef.current = inFlight;
      void sendInterrupt(session.client, chatId)
        .catch((error) => {
          sidebarNotice.set(
            `Could not interrupt: ${error instanceof Error ? error.message : String(error)}`,
          );
        })
        .finally(() => {
          const next = new Set(interruptingRef.current);
          next.delete(chatId);
          interruptingRef.current = next;
        });
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [pathname, session]);

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
  // `titlebar_plus_alpha`: the `+` (and its 32px row-left slot) exists only
  // on the chat route with a chat selected — never on the blank canvas,
  // never in Settings.
  const isChatRoute = navEntryForPath(pathname)?.kind === "chat";
  const plusAlpha = titlebarNewSessionAlpha(isChatRoute, paired && paneChatId !== null);
  // The settings route's bar is a BARE strip (`render_title_bar`,
  // shell.rs:3898-3909): no identity, no `+`, no trailing group, and its
  // left inset is flat `title_bar_content_start()` — it does not track the
  // sidebar, because the settings column is not the conversation column.
  const rowLeft = isChatRoute
    ? titlebarRowLeft({
        sidebar: sidebarWidth,
        showsNewSession: plusAlpha > 0,
        takeover,
      })
    : TITLEBAR_CONTENT_START;
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

  // ── The drop veil's drag bookkeeping ───────────────────────────────────
  // dragenter/dragleave fire per ELEMENT boundary, so a counter (not a
  // boolean) tracks whether the pointer is still inside the column; leaving
  // the window entirely (relatedTarget null) resets it in one step.
  const [dropDepth, setDropDepth] = useState(0);
  const fileDrag = (event: DragEvent): boolean => event.dataTransfer.types.includes("Files");
  const onDragEnter = (event: DragEvent): void => {
    if (!fileDrag(event)) {
      return;
    }
    event.preventDefault();
    setDropDepth((depth) => depth + 1);
  };
  const onDragOver = (event: DragEvent): void => {
    if (!fileDrag(event)) {
      return;
    }
    // Needed for the drag to stay alive over this element.
    event.preventDefault();
  };
  const onDragLeave = (event: DragEvent): void => {
    if (!fileDrag(event)) {
      return;
    }
    if (event.relatedTarget === null) {
      setDropDepth(0);
      return;
    }
    setDropDepth((depth) => Math.max(0, depth - 1));
  };
  const onDrop = (event: DragEvent): void => {
    if (!fileDrag(event)) {
      return;
    }
    // Ticket 17 owns what happens to the dropped files; here the drop is
    // only swallowed so the browser does not navigate to the file.
    event.preventDefault();
    setDropDepth(0);
  };

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
        onBack={() => onNavWalk(navHistory.back())}
        onForward={() => onNavWalk(navHistory.forward())}
        canBack={nav.canBack}
        canForward={nav.canForward}
        newSessionAlpha={plusAlpha}
        // No fallback handler: the `+` exists only where the route published
        // one (a selected chat), exactly `titlebar_plus_alpha`'s gate.
        onNewSession={chrome.onNewSession}
        identity={chrome.identity}
        // Every pane control is shell-owned and synchronous with the store, so
        // the toggle, the strip and the column all move on the same frame.
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
        <main
          className="main panel"
          onDragEnter={onDragEnter}
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onDrop={onDrop}
        >
          <div className="main-inner">
            {!paired ? (
              <Welcome />
            ) : (
              <>
                {session !== null && state.className !== "conn-connected" && !state.parked ? (
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
                {/*
                  A parked session is FATAL (revoked credential / engine
                  changed): the gate card owns that case now (see
                  root-layout), so the banner here covers the non-fatal
                  transport states only.
                */}
                <Outlet />
              </>
            )}
          </div>
          {/*
            `#attachment-drop-overlay` — the conversation column's drop veil.
            Revealed only by a drag whose payload is real files (GPUI matches
            the payload's concrete TypeId; `types.includes("Files")` is the
            web's equivalent, so resize markers and text drags can never
            reveal it). Purely visual — pointer-events none — so the drop
            itself keeps bubbling to this column (ticket 17 owns what happens
            to the files).
          */}
          {isChatRoute && paired && (
            <div
              id="attachment-drop-overlay"
              className="attachment-drop-overlay"
              data-on={dropDepth > 0 ? "1" : "0"}
              aria-hidden
            >
              Drop to attach
            </div>
          )}
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
