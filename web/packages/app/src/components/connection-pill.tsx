import { useEngineSession } from "../state/session-provider";
import { useEngineStatus } from "../state/hooks";
import { useConnectionState } from "./connection-state";

/**
 * The sidebar's connection line — the desktop's `render_connection_pill`.
 *
 * Nothing renders while healthy: the pill only exists during a real outage.
 * No surface and no border (v0.2.12 feedback) — a bare spinner beside a faint
 * 11px caption while reconnecting, a warning dot when the transport is parked
 * or closed. The transport error itself belongs in logs, not the sidebar, so
 * only the state's label shows here; the main card's banner carries the
 * detail and the re-pair affordance.
 */
export function ConnectionPill() {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const state = useConnectionState(status);

  if (session === null || state.className === "conn-connected") {
    return null;
  }
  const spinning = state.className === "conn-connecting" || state.className === "conn-reconnecting";
  return (
    <div className="connection-pill" role="status">
      {spinning ? <span className="mono-spinner" /> : <span className={`dot ${state.dot}`} />}
      <span className="connection-pill-label">{state.label}</span>
    </div>
  );
}
