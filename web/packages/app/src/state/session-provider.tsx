import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useRouterState } from "@tanstack/react-router";
import { parseScopedId } from "@roboco/engine-client";
import { engineRegistry, useFleet } from "./fleet";
import { fleetStore } from "./fleet";
import { useSidebar } from "./sidebar";
import { createEngineSession, disposeEngineSession, type EngineSession } from "./engine-session";

/**
 * The registry-backed session layer (ticket 31): one `EngineSession` alive
 * per paired engine — not just `fleet.active` — with the client and watch
 * cache owned by the fleet registry. `useEngineSession()` keeps its
 * single-session contract, now meaning "the engine this route is routed
 * to": the open chat's engine when a chat is selected, else the picked
 * space's engine (the desktop's `selected_target` precedence: chat wins,
 * then the selected space, then the default), else the active engine.
 * Requests carrying scoped ids are decoded at that engine's wire boundary
 * (`request-routing.ts`), so a chat that lives on another engine just
 * works — there is no manual switch step.
 */

const SessionContext = createContext<EngineSession | null>(null);
const SessionsContext = createContext<ReadonlyMap<string, EngineSession>>(new Map());

/**
 * `retry_engine`: recreate the failed engines' connections — parked is
 * permanent for a client instance, so a retry is a rebuild (fresh client,
 * fresh backoff), never a redial.
 */
const EngineRetryContext = createContext<() => void>(() => {});

const EMPTY_SESSIONS: ReadonlyMap<string, EngineSession> = new Map();

export function EngineSessionProvider({ children }: { children: ReactNode }) {
  const fleet = useFleet();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const [sessions, setSessions] = useState<ReadonlyMap<string, EngineSession>>(EMPTY_SESSIONS);
  const sessionsRef = useRef(sessions);

  // Keep one session alive per stored engine. The registry creates the
  // client and watch cache synchronously with the pairing-store change, so
  // this only wraps them with the session surface (the composer's catalog)
  // and disposes the ones that left the fleet or were re-paired.
  useEffect(() => {
    const previous = sessionsRef.current;
    const next = new Map<string, EngineSession>();
    for (const engine of fleet.engines) {
      const existing = previous.get(engine.baseUrl);
      if (existing !== undefined && existing.engine.credential === engine.credential) {
        // Registry-owned connection stays; refresh the stored-engine
        // metadata (a pinned identity rewrites the entry's identity).
        next.set(engine.baseUrl, existing.engine === engine ? existing : { ...existing, engine });
        continue;
      }
      const client = engineRegistry.clientFor(engine.baseUrl);
      const cache = engineRegistry.watchCacheFor(engine.baseUrl);
      if (client === null || cache === null) {
        continue;
      }
      next.set(engine.baseUrl, createEngineSession(engine, client, cache));
    }
    for (const [key, session] of previous) {
      if (next.get(key) !== session) {
        disposeEngineSession(session);
      }
    }
    sessionsRef.current = next;
    setSessions(next);
    // `fleet.engines` identity changes per store commit (pinDevice included);
    // the diff above is what makes this converge, not the dependency.
  }, [fleet.engines]);

  useEffect(
    () => () => {
      for (const session of sessionsRef.current.values()) {
        disposeEngineSession(session);
      }
      sessionsRef.current = EMPTY_SESSIONS;
    },
    [],
  );

  // ── Routing: which engine is "the" engine for this route ──────────────
  const sidebar = useSidebar();
  const sidebarFilter = sidebar.spaceFilter ?? sidebar.lastSpaceId;
  const routedKey = useMemo(
    () => routedEngineKey(pathname, sidebarFilter, fleet.active),
    [pathname, sidebarFilter, fleet.active],
  );
  const routed =
    (routedKey !== null ? sessions.get(routedKey) ?? null : null) ??
    (fleet.active !== null ? sessions.get(fleet.active) ?? null : null);

  const retry = useCallback(() => {
    const snapshot = engineRegistry.getSnapshot();
    const targets = new Set<string>();
    for (const engine of snapshot.engines) {
      if (engine.state === "off") {
        targets.add(engine.key);
      }
    }
    if (routedKey !== null) {
      targets.add(routedKey);
    }
    for (const key of targets) {
      const stored = fleetStore.getSnapshot().engines.find((engine) => engine.baseUrl === key);
      engineRegistry.restart(key, stored?.deviceId ?? null);
    }
  }, [routedKey]);

  return (
    <SessionContext.Provider value={routed}>
      <SessionsContext.Provider value={sessions}>
        <EngineRetryContext.Provider value={retry}>{children}</EngineRetryContext.Provider>
      </SessionsContext.Provider>
    </SessionContext.Provider>
  );
}

/** The engine this route routes to, per `selected_target`'s precedence. */
function routedEngineKey(pathname: string, spaceFilter: string | null, active: string | null): string | null {
  const chatId = chatIdOfPath(pathname);
  if (chatId !== null) {
    return scopedEngine(chatId, active);
  }
  // The new-thread canvas (`/`): the picked space's engine, else the active
  // engine — the composer targets the space's host when one is picked.
  if (spaceFilter !== null && (pathname === "/" || pathname === "")) {
    return scopedEngine(spaceFilter, active);
  }
  return active;
}

/** The engine key a scoped id names; unscoped (or broken) ids fall to `active`. */
function scopedEngine(id: string, active: string | null): string | null {
  try {
    const scoped = parseScopedId(id);
    return scoped.engine ?? active;
  } catch {
    return active;
  }
}

/** `/chat/<id>`'s chat id, or null anywhere else. */
function chatIdOfPath(pathname: string): string | null {
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

export function useEngineSession(): EngineSession | null {
  return useContext(SessionContext);
}

/** Every paired engine's session, keyed by engine key (`baseUrl`). */
export function useEngineSessions(): ReadonlyMap<string, EngineSession> {
  return useContext(SessionsContext);
}

/** The gate card's Retry — recreates the failed engines' connections. */
export function useEngineRetry(): () => void {
  return useContext(EngineRetryContext);
}
