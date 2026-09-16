import { useState } from "react";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useFleet } from "../state/fleet";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { SidebarBody } from "./sidebar-body";
import { EngineDrawer } from "./engine-drawer";
import { useConnectionState } from "./connection-state";

/**
 * The app shell: sidebar (chat list + engine switch) beside stacked main
 * panels at desktop widths; the sidebar becomes a drawer behind a top bar
 * at phone widths. Connection states are surfaced honestly — a banner
 * covers connecting, reconnecting, and parked-needs-repair, including the
 * re-pair affordance.
 */
export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const fleet = useFleet();
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const state = useConnectionState(status);
  const navigate = useNavigate();

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
            <Outlet />
          </>
        )}
      </main>
      <EngineDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />
    </div>
  );
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
