import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { useTitlebar } from "../state/chrome";
import { emitShortcut } from "../state/shortcuts";
import { chatPageRow, type ChatRow } from "../lib/view";
import { TranscriptView } from "../components/transcript";
import { Composer } from "../components/composer";
import { QueuePanel } from "../components/queue-panel";
import { ComposerFooter } from "../components/composer-footer";
import { rightPaneStore } from "../state/right-pane";
import { chatRoute } from "../router";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { ChangeRequestBadge } from "../components/change-request-badge";
import { QueueStore } from "../state/queue-store";
import { QueueStoreProvider } from "../state/queue-store-context";
import { sidebarNotice } from "../state/notice";
import { markChatSeen } from "../lib/chat-actions";
import { describeSendError, sendRun } from "../lib/composer-actions";
import { draftFromChat } from "../lib/composer-draft";
import { echoStore, type PendingSend } from "../state/transcript-store";
import type { QueuedMessage } from "@roboco/proto";
import type { ChangeRequestSummary, ContextUsage } from "@roboco/proto";

/**
 * One chat's main panel: title, live status, the streaming transcript
 * (`../components/transcript.tsx`), the on-demand preview pane (right-dock
 * on wide viewports, stacked on phones), the queue panel above the composer
 * (ticket 09), the composer (docks at phone widths), and the terminal dock
 * (Ctrl+J). Archived chats stay open and say so in the header.
 *
 * Queue store lifecycle mirrors the change-request store: one QueueStore
 * per open chat, memoized on chatId, disposed on unmount or chat switch.
 * The store owns the per-chat `WatchQueue` subscription and the local
 * edit-lease state; the page forwards edits from the panel into the
 * composer and the lease releases back into the store.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const status = session === null ? null : session.client.status;
  const now = useNow(10_000);
  const navigate = useNavigate();

  // Mod+J reveals the Terminal surface (desktop: Cmd+J on macOS, Ctrl+J
  // elsewhere). Capture phase: a focused terminal's textarea would otherwise
  // eat the chord and send LF to the shell. It lives here rather than in the
  // dock, which only mounts while its own tab is active.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if ((event.metaKey || event.ctrlKey) && !event.shiftKey && !event.altKey && event.key.toLowerCase() === "j") {
        event.preventDefault();
        event.stopPropagation();
        rightPaneStore.show(chatId, "terminal");
      }
    };
    window.addEventListener("keydown", onKeyDown, { capture: true });
    return () => window.removeEventListener("keydown", onKeyDown, { capture: true });
  }, [chatId]);
  // Occupancy arrives on the transcript's watch; the composer footer draws it.
  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  useEffect(() => {
    setContextUsage(null);
  }, [chatId]);

  // Lazily fetch the harness catalog once per chat page open so the
  // composer chips aren't blank behind a stale "Loading." pill.
  useEffect(() => {
    if (session === null) {
      return;
    }
    void session.catalog.loadHarnesses();
  }, [session, chatId]);

  const deviceId = status?.state === "connected" ? status.info.deviceId : null;
  const chat = snapshot === null ? null : snapshot.chats.rows.find((row) => row.id === chatId) ?? null;
  const branch = chat?.branch ?? null;
  const checkoutId = chat?.checkoutId ?? null;
  const cwd = chat?.cwd ?? null;

  const crStore = useMemo(() => {
    if (session === null) {
      return null;
    }
    return new ChangeRequestStore(session.client);
  }, [session]);

  useEffect(() => () => {
    crStore?.dispose();
  }, [crStore]);

  useEffect(() => {
    if (crStore === null || deviceId === null || cwd === null || branch === null) {
      return;
    }
    const trimmed = branch.trim();
    if (trimmed.length === 0) {
      crStore.setTargets([]);
      return;
    }
    const targets: ChangeRequestTarget[] = [{ deviceId, cwd, branch: trimmed, checkoutId }];
    crStore.setTargets(targets);
  }, [crStore, deviceId, cwd, branch, checkoutId]);

  const crSummary: ChangeRequestSummary | null = useMemo(() => {
    if (crStore === null || deviceId === null || cwd === null || branch === null) {
      return null;
    }
    const snap = crStore.getSnapshot();
    return changeRequestForChat(snap.snapshots, { deviceId, cwd, branch: branch.trim(), checkoutId });
  }, [crStore, deviceId, cwd, branch, checkoutId]);

  // Queue store: one per chat. Disposed on chat switch so a fresh
  // subscription lands immediately.
  const queueStore = useMemo(() => {
    if (session === null || deviceId === null) {
      return null;
    }
    return new QueueStore(session.client, chatId, { editorDeviceId: deviceId });
  }, [session, chatId, deviceId]);

  useEffect(() => () => {
    queueStore?.dispose();
  }, [queueStore]);

  // Edit state: the chat page owns which queued row (if any) is feeding
  // text into the composer. Clearing it cancels the lease (the user
  // backed out without saving); committing finishes the lease with the
  // current composer text in place.
  const [editingRow, setEditingRow] = useState<{ id: string; text: string } | null>(null);

  // If the queue store reports the row disappeared while we were editing
  // (another device removed/sent it), drop the edit state so the composer
  // doesn't carry stale text.
  useEffect(() => {
    if (editingRow === null || queueStore === null) {
      return;
    }
    if (queueStore.rowById(editingRow.id) === null) {
      setEditingRow(null);
    }
  }, [editingRow, queueStore, queueStore?.getSnapshot().generation]);

  const onEditRow = useCallback((row: QueuedMessage) => {
    setEditingRow({ id: row.id, text: row.text });
  }, []);

  const onEditFinish = useCallback(
    (outcome: { action: "commit" | "cancel" | "releaseUnchanged"; text: string }) => {
      if (queueStore === null || editingRow === null) {
        return;
      }
      const store = queueStore;
      const rowId = editingRow.id;
      setEditingRow(null);
      void (async () => {
        try {
          const lease = store.getSnapshot().editLease;
          if (lease === null || lease.messageId !== rowId) {
            return;
          }
          if (outcome.action === "commit") {
            await store.finishEdit("commit", { text: outcome.text });
          } else {
            await store.finishEdit(outcome.action);
          }
        } catch (error) {
          sidebarNotice.set(`Could not finish edit: ${error instanceof Error ? error.message : String(error)}`);
        }
      })();
    },
    [queueStore, editingRow],
  );

  const onEditCancel = useCallback(() => {
    if (queueStore === null || editingRow === null) {
      setEditingRow(null);
      return;
    }
    const store = queueStore;
    const rowId = editingRow.id;
    setEditingRow(null);
    void (async () => {
      try {
        const lease = store.getSnapshot().editLease;
        if (lease !== null && lease.messageId === rowId) {
          await store.finishEdit("cancel");
        }
      } catch (error) {
        sidebarNotice.set(`Could not cancel edit: ${error instanceof Error ? error.message : String(error)}`);
      }
    })();
  }, [queueStore, editingRow]);

  const row =
    snapshot === null || !snapshot.chats.loaded
      ? undefined
      : chatPageRow(chatId, snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now, snapshot.devices.rows);

  // Opening a chat IS reading it (`mark_chat_seen`): the local stamp lands
  // first and stands whatever the mutation does, so a dropped `Mutate` never
  // makes a chat the user plainly looked at flash unread again.
  useEffect(() => {
    if (session === null) {
      return;
    }
    markChatSeen(session.client, chatId);
  }, [session, chatId]);

  /**
   * Retry an undelivered echo. A retry is a NEW send of the same text, not a
   * resend of the old wire message: the store mints a fresh id and restarts
   * the grace-window clock, and that id is what goes over the wire.
   *
   * The draft comes from the chat's own persisted config rather than the
   * composer's local one — this is a re-send of something already sent, so the
   * config it was sent under is the right one, and the composer may well have
   * moved on.
   */
  const onRetrySend = useCallback(
    (send: PendingSend) => {
      if (session === null || row === undefined) {
        return;
      }
      const chat = row.chat;
      const cwd = chat.cwd ?? null;
      if (cwd === null || cwd.trim().length === 0) {
        sidebarNotice.set("This chat has no working directory yet — pick a space first.");
        return;
      }
      const next = echoStore.retry(send.messageId);
      if (next === null) {
        return;
      }
      const harnesses = session.catalog.getHarnesses().rows;
      const draft = draftFromChat(chat, harnesses, session.catalog.getModels(chat.config?.harness ?? "claude-code").rows);
      void (async () => {
        try {
          await sendRun(session.client, chat.id, draft, send.text, cwd, {
            currentConfig: chat.config,
            mintMessageId: () => next.messageId,
          });
        } catch (error) {
          echoStore.removeEcho(next.messageId);
          sidebarNotice.set(`Could not send: ${describeSendError(error)}`);
        }
      })();
    },
    [session, row],
  );

  // The titlebar is the shell's; the route fills its identity and its ONE
  // trailing control — the right pane's toggle (the desktop's
  // `toggle-changes`). Panel surfaces are tabs in that pane, never buttons in
  // the bar. Hooks run unconditionally; the early returns below come after.
  // The titlebar is the shell's; this route fills only its identity. The right
  // pane, its toggle, its surface tabs and its expand control are all shell
  // chrome — see `state/chrome.ts` for why they must not travel through here.
  // Note `pane` is deliberately NOT a dep: this effect clears the store on
  // every dep change, and a toggle rebuilding the chrome is what used to tear
  // the pane column down mid-animation.
  useTitlebar(
    () => ({
      identity: row === undefined ? null : <ChatIdentity row={row} crSummary={crSummary} />,
      onNewSession: () => emitShortcut("new-chat"),
    }),
    [chatId, row?.chat.id, row?.chat.title, row?.status, row?.folder, row?.harness, crSummary],
  );

  if (snapshot === null || !snapshot.chats.loaded) {
    return <div className="chat-page" />;
  }
  if (row === undefined) {
    return (
      <div className="empty-state">
        <p>That chat is not in this engine's list.</p>
        <Link to="/" className="btn btn-ghost">
          Back to chats
        </Link>
      </div>
    );
  }
  return (
    <div className="chat-page">
      {/*
        The conversation column: the transcript, the queue, the composer and
        the session footer. Its sibling — the right pane, carrying whichever
        surface its tabs select — is a SHELL column mounted by `AppShell`, as
        on the desktop; this page only names the chat that owns it.
      */}
      <div className="chat-column">
        <div className="chat-body">
          {session === null ? (
            <div className="empty-state">
              <p>No engine connected.</p>
            </div>
          ) : (
            <TranscriptView
              client={session.client}
              docId={chatId}
              deviceId={deviceId}
              onContextUsage={setContextUsage}
              onRetrySend={onRetrySend}
            />
          )}
        </div>
        {queueStore !== null && deviceId !== null ? (
          <QueueStoreProvider value={queueStore}>
            <QueuePanel
              editorDeviceId={deviceId}
              onEditRow={onEditRow}
              editingRowId={editingRow?.id ?? null}
            />
            {session !== null && (
              <Composer
                session={session}
                chat={row.chat}
                catalog={session.catalog}
                editingMessage={editingRow}
                onEditFinish={onEditFinish}
              />
            )}
          </QueueStoreProvider>
        ) : (
          session !== null && <Composer session={session} chat={row.chat} catalog={session.catalog} />
        )}
        <ComposerFooter branch={row.branch} crSummary={crSummary} contextUsage={contextUsage} />
        {editingRow !== null && (
          <div className="chat-edit-toolbar">
            <button type="button" className="btn btn-ghost" onClick={onEditCancel}>
              Cancel edit
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The titlebar's centred identity — the desktop's transcript identity group:
 * the harness's brand mark, the chat title, and the `space @ device` line in
 * the muted subline tone, with the change-request badge trailing. It is a drag
 * region on the desktop; here it is just chrome.
 *
 * Archived chats carry no badge — the desktop's identity row is mark + title +
 * folder and nothing else, so the word rides the folder line instead.
 */
function ChatIdentity({ row, crSummary }: { row: ChatRow; crSummary: ChangeRequestSummary | null }) {
  const brand = row.harness === null ? null : harnessBrandIcon(row.harness);
  return (
    <>
      {brand !== null && (
        <Icon
          name={brand.name}
          size={14}
          className="identity-brand"
          style={brand.tint === null ? undefined : { color: brand.tint }}
        />
      )}
      <span className="identity-title">{row.chat.title ?? "New session"}</span>
      <span className="identity-folder">
        {row.chat.archived ? `${row.folder} · Archived` : row.folder}
      </span>
      {crSummary !== null && <ChangeRequestBadge summary={crSummary} />}
    </>
  );
}