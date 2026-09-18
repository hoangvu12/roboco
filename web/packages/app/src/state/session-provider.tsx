import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useFleet } from "./fleet";
import { fleetStore } from "./fleet";
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

const SessionContext = createContext<EngineSession | null>(null);

/**
 * `retry_engine`: tear the supervised connection down and build a fresh one —
 * the web peer of the desktop gate card's Retry (`AppState::bootstrap`). A
 * parked client is permanent for its instance, so a retry is a recreate, not
 * a re-dial.
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
      <EngineRetryContext.Provider value={retry}>
        {children}
        {/* The notification decision engine's call site (shell.rs:1677-1766's
            web peer). Keyed by session so an engine switch remounts it: fresh
            baselines (every row re-seeds silently) and a re-armed
            connectivity quiet period, the runtime-replacement reset. */}
        {session !== null ? <SessionNotificationDriver key={engineSessionKey(session.engine)} session={session} /> : null}
      </EngineRetryContext.Provider>
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

export function useEngineSession(): EngineSession | null {
  return useContext(SessionContext);
}

/** The gate card's Retry — recreates the active engine's session. */
export function useEngineRetry(): () => void {
  return useContext(EngineRetryContext);
}
