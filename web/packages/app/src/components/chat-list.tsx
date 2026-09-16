import { Link } from "@tanstack/react-router";
import type { WatchCacheSnapshot } from "@roboco/engine-client";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { chatListRows, statusWord, type ChatRow } from "../lib/view";
import { StatusDot } from "./status-dot";

export function ChatList() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const now = useNow(10_000);

  if (snapshot === null) {
    return <p className="sidebar-note">Pair an engine to see its chats.</p>;
  }
  const chats = snapshot.chats;
  if (chats.error !== null) {
    return <p className="sidebar-note sidebar-note-error">{chats.error.message}</p>;
  }
  if (!chats.loaded) {
    return <p className="sidebar-note">Loading chats…</p>;
  }
  const rows = chatListRows(chats.rows, snapshot.spaces.rows, snapshot.statuses.rows, now);
  if (rows.length === 0) {
    return <p className="sidebar-note">No chats yet.</p>;
  }
  return (
    <ul className="chat-list">
      {rows.map((row) => (
        <ChatListRow key={row.chat.id} row={row} />
      ))}
    </ul>
  );
}

function ChatListRow({ row }: { row: ChatRow }) {
  const word = statusWord(row.status);
  return (
    <Link to="/chat/$chatId" params={{ chatId: row.chat.id }} className="chat-row" data-status={row.status} activeProps={{ className: "chat-row chat-row-active" }}>
      <div className="chat-row-line">
        <span className="chat-row-project">{row.project}</span>
        <span className={`chat-row-corner corner-${row.status}`}>
          <StatusDot status={row.status} />
          {word ?? row.timeAgo}
        </span>
      </div>
      <div className="chat-row-title">{row.chat.title ?? "New session"}</div>
      {row.branch !== null && <div className="chat-row-branch">{row.branch}</div>}
    </Link>
  );
}
