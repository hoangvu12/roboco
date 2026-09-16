import { useEffect, useState } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatListRows, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { PreviewPanel } from "../components/preview-panel";
import { chatRoute } from "../router";

/**
 * One chat's main panel: title, live status, and the transcript region.
 * The streaming transcript itself is the next layer's work; the shell
 * here already carries the selection, the status derivation, and the
 * layout region it renders into. The preview pane (ticket 15) docks to
 * the right on demand — the web peer of the desktop's right-pane browser
 * surface, default closed per chat.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);
  const [previewOpen, setPreviewOpen] = useState(false);
  useEffect(() => {
    setPreviewOpen(false);
  }, [chatId]);

  if (snapshot === null || !snapshot.chats.loaded) {
    return (
      <div className="chat-page">
        <ChatHeader title="…" status="idle" branch={null} previewOpen={false} onTogglePreview={null} />
      </div>
    );
  }
  const row = chatListRows(snapshot.chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now).find(
    (candidate) => candidate.chat.id === chatId,
  );
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
        previewOpen={previewOpen}
        onTogglePreview={() => setPreviewOpen((open) => !open)}
      />
      <div className="chat-body">
        <div className="chat-transcript">
          <p className="chat-transcript-empty">No messages yet.</p>
        </div>
        {previewOpen && <PreviewPanel chatId={chatId} onClose={() => setPreviewOpen(false)} />}
      </div>
    </div>
  );
}

function ChatHeader({
  title,
  status,
  branch,
  previewOpen,
  onTogglePreview,
}: {
  title: string;
  status: ChatIndicator;
  branch: string | null;
  previewOpen: boolean;
  onTogglePreview: (() => void) | null;
}) {
  return (
    <header className="chat-header">
      <div className="chat-header-title">
        <StatusDot status={status} />
        <h1>{title}</h1>
      </div>
      <div className="chat-header-side">
        {branch !== null && <div className="chat-header-branch">{branch}</div>}
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
      </div>
    </header>
  );
}
