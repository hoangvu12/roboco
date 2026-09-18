import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { parseScopedId } from "@roboco/engine-client";
import { engineRegistry, useFleet } from "./fleet";
import { fleetStore } from "./fleet";
import { useSidebar } from "./sidebar";
import { createEngineSession, disposeEngineSession, engineSessionKey, type EngineSession } from "./engine-session";
import { useWatchSnapshot } from "./hooks";
import { useUiSettings } from "./ui-settings";
import { echoStore, pendingSendStatus } from "./transcript-store";
import {
  AttentionSoundGate,
  ConnectivityNotificationState,
  chatBannerTexts,
  connectivityBannerTexts,
  onChatNotificationClick,
  postBanner,
  sessionNotificationState,
  soundSince,
} from "../lib/notifications";
import { playSound, sessionSoundEnabled } from "../lib/sounds";

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
        <EngineRetryContext.Provider value={retry}>
          {children}
          {/* The notification decision engine's call site (shell.rs:1677-1766's
              web peer). Keyed per session so an engine switch or re-pair
              remounts it: fresh baselines (every row re-seeds silently) and a
              re-armed connectivity quiet period, the runtime-replacement
              reset — one driver per paired engine, so fleet engines chime
              independently. */}
          {[...sessions.values()].map((session) => (
            <SessionNotificationDriver key={engineSessionKey(session.engine)} session={session} />
          ))}
        </EngineRetryContext.Provider>
      </SessionsContext.Provider>
    </SessionContext.Provider>
  );
}

/**
 * Banners and chimes share one chat-status detector (the port of
 * `shell.rs`'s on-state-changed block): completion markers survive queue
 * handoffs and never advance for interrupts or stale activity; a row's
 * first appearance seeds the baseline silently (boot/replay); pending sends
 * consume completion changes silently while questions still ring; output
 * settings never affect the baseline. Runs once per watch-cache identity
 * change (a meaningful state change, not a render) and never re-fires for a
 * state it has already reacted to.
 */
function SessionNotificationDriver({ session }: { session: EngineSession }) {
  const snapshot = useWatchSnapshot(session);
  const settings = useUiSettings();
  const navigate = useNavigate();
  const baselines = useRef(new Map<string, ReturnType<typeof sessionNotificationState>>());
  const connectivity = useRef(new ConnectivityNotificationState());
  const attentionGate = useRef(new AttentionSoundGate());

  // Banner click routing (open_notified_chat, lib.rs:241-262): focus the
  // window (the browser focuses the tab) then open the chat through the
  // sidebar's own path — the chat route.
  useEffect(() => {
    return onChatNotificationClick((chatId) => {
      void navigate({ to: "/chat/$chatId", params: { chatId } });
    });
  }, [navigate]);

  useEffect(() => {
    if (snapshot === null) {
      return;
    }
    const now = Date.now();
    // Background-only banners: app-level focus (any Roboco tab being the OS
    // foreground, not "this chat's route is open"), so a ping for a
    // background chat in a focused app still stays a chime.
    const appFocused = document.hasFocus();
    const titleByChat = new Map(snapshot.chats.rows.map((chat) => [chat.id, chat.title] as const));

    for (const status of snapshot.statuses.rows) {
      const baseline = sessionNotificationState(status, now);
      const prev = baselines.current.get(status.chatId) ?? null;
      baselines.current.set(status.chatId, baseline);
      if (prev === null) {
        // First appearance: seed silently, never chime or banner on boot.
        continue;
      }
      const sendPending = echoStore
        .forChat(status.chatId)
        .some((send) => pendingSendStatus(send, now) === "pending");
      const sound = soundSince(baseline, prev, sendPending);
      if (sound === null) {
        continue;
      }
      if (
        sessionSoundEnabled(settings, sound) &&
        (sound !== "attention" || attentionGate.current.shouldPlay(now))
      ) {
        playSound(sound);
      }
      if (settings.notificationsEnabled && !(settings.notificationsBackgroundOnly && appFocused)) {
        const texts = chatBannerTexts(sound, titleByChat.get(status.chatId) ?? null);
        postBanner(texts.title, texts.body, status.chatId);
      }
    }

    // Connectivity: the single-value watch's slot — `loaded` is the
    // connectivity-observed flag; an unloaded slot resets the machine.
    const sound = connectivity.current.update(
      snapshot.connectivity.value?.state ?? "disabled",
      snapshot.connectivity.loaded,
      now,
    );
    if (sound !== null) {
      if (sessionSoundEnabled(settings, sound) && attentionGate.current.shouldPlay(now)) {
        playSound(sound);
      }
      if (settings.notificationsEnabled && !(settings.notificationsBackgroundOnly && appFocused)) {
        const texts = connectivityBannerTexts(snapshot.connectivity.value?.state ?? "reconnecting");
        postBanner(texts.title, texts.body);
      }
    }
  }, [snapshot, settings]);

  return null;
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
