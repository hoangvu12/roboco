import { useCallback, useEffect, useState } from "react";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useFleet } from "../state/fleet";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { emitShortcut } from "../state/shortcuts";
import { SidebarBody } from "./sidebar-body";
import { EngineDrawer } from "./engine-drawer";
import { useConnectionState } from "./connection-state";
import { TerminalProvider } from "../terminal/store";

/**
 * The app shell: sidebar (chat list + engine switch) beside stacked main
 * panels at desktop widths; the sidebar becomes a drawer behind a top bar
 * at phone widths. Connection states are surfaced honestly — a banner
 * covers connecting, reconnecting, and parked-needs-repair, including the
 * re-pair affordance.
 *
 * Global keyboard shortcuts mirror the desktop's wherever the browser
 * allows: Mod+N creates a new chat (skipped while typing), Mod+B toggles
 * the sidebar drawer at phone widths, and Escape closes the engine drawer.
 */
export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const fleet = useFleet();
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const state = useConnectionState(status);
  const navigate = useNavigate();

  const onNewChat = useCallback(() => {
    if (fleet.engines.length === 0) {
      // No engine paired: keyboard shortcut is the equivalent of the welcome
      // "Pair an engine" button.
      void navigate({ to: "/pair" });
      return;
    }
    emitShortcut("new-chat");
  }, [fleet.engines.length, navigate]);

  const onToggleSidebar = useCallback(() => {
    setSidebarOpen((current) => !current);
  }, []);

  const onCloseDrawer = useCallback(() => {
    setSidebarOpen(false);
    setDrawerOpen(false);
  }, []);

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

  return (
    <div className={`shell ${sidebarOpen ? "shell-sidebar-open" : ""}`}>
      <aside className="sidebar panel">
        <header className="sidebar-header">
          <span className="wordmark">Roboco</span>
          <span className="sidebar-header-actions">
            <Link to="/files" className="btn btn-ghost">
              Files
            </Link>
            <button type="button" className="btn btn-ghost" onClick={() => setDrawerOpen(true)}>
              Engines
            </button>
          </span>
        </header>
        <SidebarBody key={fleet.active ?? "none"} />
        <footer className="sidebar-footer">
          <span className={`conn ${state.className}`}>
            <span className={`dot ${state.dot}`} />
            {state.label}
          </span>
          <Link to="/settings" className="btn btn-ghost">
            Settings
          </Link>
        </footer>
      </aside>
      <div className="sidebar-backdrop" onClick={() => setSidebarOpen(false)} />
      <main className="main">
        <header className="topbar">
          <button type="button" className="btn btn-ghost" aria-label="Open chat list" onClick={() => setSidebarOpen(true)}>
            Chats
          </button>
          <span className="wordmark">Roboco</span>
          <button type="button" className="btn btn-ghost" onClick={() => setDrawerOpen(true)}>
            Engines
          </button>
        </header>
        {fleet.engines.length === 0 ? (
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
            <TerminalProvider>
              <Outlet />
            </TerminalProvider>
          </>
        )}
      </main>
      <EngineDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />
    </div>
  );
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
      <h1>Roboco</h1>
      <p>This browser has no paired engine yet.</p>
      <Link className="btn btn-solid" to="/pair">
        Pair an engine
      </Link>
    </div>
  );
}
