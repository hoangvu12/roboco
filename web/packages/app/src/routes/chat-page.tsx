import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatPageRow, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { TranscriptView } from "../components/transcript";
import { PreviewPanel } from "../components/preview-panel";
import { useTerminalStore } from "../terminal/store";
import { TerminalDock } from "../terminal/terminal-dock";
import { chatRoute } from "../router";
import { ChangeRequestStore, type ChangeRequestTarget, changeRequestForChat } from "../state/change-requests-store";
import { ChangeRequestBadge } from "../components/change-request-badge";
import type { ChangeRequestSummary } from "@roboco/proto";

/**
 * One chat's main panel: title, live status, the streaming transcript
 * (`../components/transcript.tsx`), the on-demand preview pane (right-dock
 * on wide viewports, stacked on phones), and the terminal dock (Ctrl+J).
 * Archived chats stay open and say so in the header.
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
          <TranscriptView client={session.client} docId={chatId} />
        )}
        {previewOpen && <PreviewPanel chatId={chatId} onClose={() => setPreviewOpen(false)} />}
      </div>
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