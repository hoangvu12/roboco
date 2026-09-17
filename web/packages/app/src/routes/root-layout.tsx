import { Outlet, useRouterState } from "@tanstack/react-router";
import { Link } from "@tanstack/react-router";
import { EngineSessionProvider, useEngineRetry, useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { GateCard } from "../components/gate-card";
import { useConnectionState } from "../components/connection-state";

/**
 * The root route: the engine session provider, then the gate/page split —
 * the desktop's `GatePhase` (`shell.rs:238-240`).
 *
 * - **Ready** — the page, wrapped in the one keyed `fade_in("phase-app")`
 *   entrance: 500ms `EASE_OUT_EXPO`, opacity 0→1 with a 4px rise, replayed
 *   whenever the app recovers from a gate.
 * - **Failed** — a parked session (revoked credential / engine changed) is
 *   fatal on the web: the gate card replaces the page. The non-fatal
 *   transport states (connecting, reconnecting) stay as the shell's banner
 *   and the sidebar's connection pill.
 * - **Loading** — the first dial is in flight and nothing has connected yet:
 *   a plain empty root, no overlay (the boot splash is a deliberate open
 *   question and is NOT built here).
 *
 * `/pair` always shows the page: it is the one route that can FIX a dead
 * pairing, so the gate must never cover it.
 */
export function RootLayout() {
  return (
    <EngineSessionProvider>
      <GateAndPage />
    </EngineSessionProvider>
  );
}

function GateAndPage() {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const state = useConnectionState(status);
  const retry = useEngineRetry();
  const onPair = useRouterState({ select: (s) => s.location.pathname === "/pair" });

  const phase =
    session !== null && status !== null && !onPair
      ? status.state === "parked"
        ? "failed"
        : status.state === "connecting"
          ? "loading"
          : "ready"
      : "ready";

  if (phase === "loading") {
    // GatePhase::Loading — the desktop renders ONLY the root (plus a splash
    // this port deliberately defers): nothing to see, nothing to click.
    return <div className="gate-loading" />;
  }
  if (phase === "failed") {
    const error = state.detail ?? state.label;
    return (
      <GateCard error={error} onRetry={retry}>
        {/*
          Web-only escape hatch, flagged in the ticket's Comments: the parked
          credential cannot be fixed by retrying, and the gate covers every
          route that could re-pair — without this link the dead session would
          strand the browser.
        */}
        <Link to="/pair" className="gate-pair-link">
          Pair again
        </Link>
      </GateCard>
    );
  }
  return (
    <div className="page-fade" key={phase}>
      <Outlet />
    </div>
  );
}
