import { createContext, useCallback, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useFleet } from "./fleet";
import { fleetStore } from "./fleet";
import { createEngineSession, disposeEngineSession, engineSessionKey, type EngineSession } from "./engine-session";

const SessionContext = createContext<EngineSession | null>(null);

/**
 * `retry_engine`: tear the supervised connection down and build a fresh one —
 * the web peer of the desktop gate card's Retry (`AppState::bootstrap`). A
 * parked client is permanent for its instance, so a retry is a recreate, not
 * a redial.
 */
const EngineRetryContext = createContext<() => void>(() => {});

/**
 * Owns the supervised connection to the active engine: rebuilt when the
 * active engine or its credential changes (engine switch, re-pair), or when
 * the user retries a failed gate, disposed on unmount or replacement. The
 * verified engine identity is pinned back into the registry so reloads keep
 * verifying it.
 */
export function EngineSessionProvider({ children }: { children: ReactNode }) {
  const fleet = useFleet();
  const engine = fleet.active === null ? null : fleet.engines.find((entry) => entry.baseUrl === fleet.active) ?? null;
  const key = engine === null ? null : engineSessionKey(engine);
  const [session, setSession] = useState<EngineSession | null>(null);
  // The gate card's Retry bumps this, forcing the effect below to dispose and
  // recreate the session for the SAME engine.
  const [retryNonce, setRetryNonce] = useState(0);
  const retry = useCallback(() => setRetryNonce((nonce) => nonce + 1), []);

  useEffect(() => {
    if (engine === null) {
      setSession((current) => {
        if (current !== null) {
          disposeEngineSession(current);
        }
        return null;
      });
      return;
    }
    const created = createEngineSession(engine);
    setSession(created);
    return () => {
      disposeEngineSession(created);
      setSession((current) => (current === created ? null : current));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed by credential+endpoint, not entry identity (pinDevice rewrites identity); the nonce is the gate's Retry
  }, [key, retryNonce]);

  useEffect(() => {
    if (session === null) {
      return;
    }
    return session.client.onStatus((status) => {
      if (status.state === "connected") {
        fleetStore.pinDevice(session.engine.baseUrl, status.info.deviceId);
      }
    });
  }, [session]);

  return (
    <SessionContext.Provider value={session}>
      <EngineRetryContext.Provider value={retry}>{children}</EngineRetryContext.Provider>
    </SessionContext.Provider>
  );
}

export function useEngineSession(): EngineSession | null {
  return useContext(SessionContext);
}

/** The gate card's Retry — recreates the active engine's session. */
export function useEngineRetry(): () => void {
  return useContext(EngineRetryContext);
}
