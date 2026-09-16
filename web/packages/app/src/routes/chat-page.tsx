import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatPageRow, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { TranscriptView } from "../components/transcript";
import { PreviewPanel } from "../components/preview-panel";
import { Composer } from "../components/composer";
import { QueuePanel } from "../components/queue-panel";
import { useTerminalStore } from "../terminal/store";
import { TerminalDock } from "../terminal/terminal-dock";
import { chatRoute } from "../router";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { ChangeRequestBadge } from "../components/change-request-badge";
import { QueueStore } from "../state/queue-store";
import { QueueStoreProvider } from "../state/queue-store-context";
import { sidebarNotice } from "../state/notice";
import type { QueuedMessage } from "@roboco/proto";
import type { ChangeRequestSummary } from "@roboco/proto";

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
  const terminalStore = useTerminalStore();
  const [previewOpen, setPreviewOpen] = useState(false);
  useEffect(() => {
    setPreviewOpen(false);
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

  if (snapshot === null || !snapshot.chats.loaded) {
    return (
      <div className="chat-page">
        <ChatHeader
          title="…"
          status="idle"
          branch={null}
          archived={false}
          previewOpen={false}
          onTogglePreview={null}
          onToggleTerminal={null}
          crSummary={null}
          chatId={chatId}
        />
      </div>
    );
  }
  const row = chatPageRow(chatId, snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now);
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
      <ChatHeader
        title={row.chat.title ?? "New session"}
        status={row.status}
        branch={row.branch}
        archived={row.chat.archived}
        previewOpen={previewOpen}
        onTogglePreview={() => setPreviewOpen((open) => !open)}
        onToggleTerminal={() => terminalStore.toggle(chatId)}
        crSummary={crSummary}
        chatId={chatId}
      />
      <div className="chat-body">
        {session === null ? (
          <div className="chat-transcript">
            <p className="chat-transcript-empty">No engine connected.</p>
          </div>
        ) : (
          <TranscriptView client={session.client} docId={chatId} deviceId={deviceId} />
        )}
        {previewOpen && <PreviewPanel chatId={chatId} onClose={() => setPreviewOpen(false)} />}
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
      {editingRow !== null && (
        <div className="chat-edit-toolbar">
          <button type="button" className="btn btn-ghost" onClick={onEditCancel}>
            Cancel edit
          </button>
        </div>
      )}
      <TerminalDock store={terminalStore} chatId={chatId} />
    </div>
  );
}

function ChatHeader({
  title,
  status,
  branch,
  archived,
  previewOpen,
  onTogglePreview,
  onToggleTerminal,
  crSummary,
  chatId,
}: {
  title: string;
  status: ChatIndicator;
  branch: string | null;
  archived: boolean;
  previewOpen: boolean;
  onTogglePreview: (() => void) | null;
  onToggleTerminal: (() => void) | null;
  crSummary: ChangeRequestSummary | null;
  chatId: string;
}) {
  return (
    <header className="chat-header">
      <div className="chat-header-title">
        <StatusDot status={status} />
        <h1>{title}</h1>
        {archived && <span className="chat-header-badge">Archived</span>}
        {crSummary !== null && <ChangeRequestBadge summary={crSummary} />}
      </div>
      <div className="chat-header-side">
        {branch !== null && <div className="chat-header-branch">{branch}</div>}
        <Link
          to="/chat/$chatId/changes"
          params={{ chatId }}
          className="btn btn-ghost"
          activeProps={{ className: "btn btn-ghost btn-active" }}
        >
          Changes
        </Link>
        {onTogglePreview !== null && (
          <button
            type="button"
            className={`btn btn-ghost ${previewOpen ? "btn-active" : ""}`}
            aria-pressed={previewOpen}
            onClick={onTogglePreview}
          >
            Preview
          </button>
        )}
        {onToggleTerminal !== null && (
          <button type="button" className="btn btn-ghost" title="Toggle terminal (Ctrl+J)" onClick={onToggleTerminal}>
            Terminal
          </button>
        )}
      </div>
    </header>
  );
}