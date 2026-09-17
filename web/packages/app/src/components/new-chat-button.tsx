import { useEffect, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus, useWatchSnapshot } from "../state/hooks";
import { useSidebar } from "../state/sidebar";
import { sidebarNotice } from "../state/notice";
import { onShortcut } from "../state/shortcuts";
import { createChat, describeMutateError, waitForChatRow } from "../lib/chat-actions";
import { healedSpaceFilter, spacesSorted } from "../lib/view";

/**
 * The new-chat flow: mint a chat on the active engine and open it. The
 * chat lands in the selected space (the sidebar filter, else the last
 * selected space, else the first space); with no spaces at all it is
 * project-less on the connected engine's own device — the desktop's
 * canvas-target resolution (state.rs effective_device_id).
 *
 * Headless. The desktop's titlebar is the single owner of the new-session
 * action in both sidebar states (`render_titlebar_cluster`), so this mounts
 * no control of its own — it subscribes to the `new-chat` shortcut event that
 * both the titlebar `+` and the app-shell keyboard layer (Cmd/Ctrl+N) emit.
 */
export function NewChatListener() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const status = useEngineStatus(session);
  const sidebar = useSidebar();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);

  const connected = session !== null && status?.state === "connected";

  async function create(): Promise<void> {
    if (busy || session === null) {
      return;
    }
    const spaces = snapshot?.spaces.rows ?? [];
    const filter = healedSpaceFilter(sidebar.spaceFilter, spaces);
    const last =
      sidebar.lastSpaceId !== null && spaces.some((space) => space.id === sidebar.lastSpaceId)
        ? sidebar.lastSpaceId
        : null;
    const spaceId = filter ?? last ?? spacesSorted(spaces)[0]?.id ?? null;
    const deviceId = session.client.engineInfo?.deviceId ?? null;
    if (spaceId === null && deviceId === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    setBusy(true);
    try {
      const chatId = await createChat(
        session.client,
        spaceId !== null ? { spaceId } : { deviceId: deviceId ?? undefined },
      );
      await waitForChatRow(session.cache, chatId);
      void navigate({ to: "/chat/$chatId", params: { chatId } });
    } catch (error) {
      sidebarNotice.set(describeMutateError(error));
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    if (!connected) {
      return;
    }
    return onShortcut("new-chat", () => {
      void create();
    });
    // Re-subscribe when connection state changes; the closure captures
    // the latest session/snapshot/sidebar.
  }, [connected, session, snapshot, sidebar.spaceFilter, sidebar.lastSpaceId]);

  return null;
}
