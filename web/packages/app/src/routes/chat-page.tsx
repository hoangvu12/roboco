import { Link, useParams } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatPageRow, type ChatIndicator } from "../lib/view";
import { StatusDot } from "../components/status-dot";
import { TranscriptView } from "../components/transcript";
import { useTerminalStore } from "../terminal/store";
import { TerminalDock } from "../terminal/terminal-dock";
import { chatRoute } from "../router";

/**
 * One chat's main panel: title, live status, and the streaming transcript
 * (`../components/transcript.tsx`) rendered into the shell's layout region.
 * Archived chats stay open (archiving never closes a chat) and say so
 * in the header.
 */
export function ChatPage() {
  const { chatId } = useParams({ from: chatRoute.id });
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);
  const terminalStore = useTerminalStore();

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
      <ChatHeader
        title={row.chat.title ?? "New session"}
        status={row.status}
        branch={row.branch}
        archived={row.chat.archived}
        onToggleTerminal={() => terminalStore.toggle(chatId)}
      />
      {session === null ? (
        <div className="chat-transcript">
          <p className="chat-transcript-empty">No engine connected.</p>
        </div>
      ) : (
        <TranscriptView client={session.client} docId={chatId} />
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
  onToggleTerminal,
}: {
  title: string;
  status: ChatIndicator;
  branch: string | null;
  archived: boolean;
  onToggleTerminal?: () => void;
}) {
  return (
    <header className="chat-header">
      <div className="chat-header-title">
        <StatusDot status={status} />
        <h1>{title}</h1>
        {archived && <span className="chat-header-badge">Archived</span>}
      </div>
      <div className="chat-header-side">
        {branch !== null && <div className="chat-header-branch">{branch}</div>}
        {onToggleTerminal !== undefined && (
          <button
            type="button"
            className="btn btn-ghost"
            title="Toggle terminal (Ctrl+J)"
            onClick={onToggleTerminal}
          >
            Terminal
          </button>
        )}
      </div>
    </header>
  );
}
