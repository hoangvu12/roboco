import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatListRows, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { TranscriptView } from "../components/transcript";
import { chatRoute } from "../router";

/**
 * One chat's main panel: title, live status, and the streaming transcript
 * (`../components/transcript.tsx`) rendered into the shell's layout region.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);

  if (snapshot === null || !snapshot.chats.loaded) {
    return (
      <div className="chat-page">
        <ChatHeader title="…" status="idle" branch={null} />
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
      <ChatHeader title={row.chat.title ?? "New session"} status={row.status} branch={row.branch} />
      {session === null ? (
        <div className="chat-transcript">
          <p className="chat-transcript-empty">No engine connected.</p>
        </div>
      ) : (
        <TranscriptView client={session.client} docId={chatId} />
      )}
    </div>
  );
}

function ChatHeader({ title, status, branch }: { title: string; status: ChatIndicator; branch: string | null }) {
  return (
    <header className="chat-header">
      <div className="chat-header-title">
        <StatusDot status={status} />
        <h1>{title}</h1>
      </div>
      {branch !== null && <div className="chat-header-branch">{branch}</div>}
    </header>
  );
}
