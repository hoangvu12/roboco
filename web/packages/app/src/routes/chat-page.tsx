import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatPageRow, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { chatRoute } from "../router";

/**
 * One chat's main panel: title, live status, and the transcript region.
 * The streaming transcript itself is the next layer's work; the shell
 * here already carries the selection, the status derivation, and the
 * layout region it renders into. Archived chats stay open (archiving
 * never closes a chat) and say so in the header.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);

  if (snapshot === null || !snapshot.chats.loaded) {
    return (
      <div className="chat-page">
        <ChatHeader title="…" status="idle" branch={null} archived={false} />
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
      <ChatHeader title={row.chat.title ?? "New session"} status={row.status} branch={row.branch} archived={row.chat.archived} />
      <div className="chat-transcript">
        <p className="chat-transcript-empty">No messages yet.</p>
      </div>
    </div>
  );
}

function ChatHeader({ title, status, branch, archived }: { title: string; status: ChatIndicator; branch: string | null; archived: boolean }) {
  return (
    <header className="chat-header">
      <div className="chat-header-title">
        <StatusDot status={status} />
        <h1>{title}</h1>
        {archived && <span className="chat-header-badge">Archived</span>}
      </div>
      {branch !== null && <div className="chat-header-branch">{branch}</div>}
    </header>
  );
}
