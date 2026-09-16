import { Link } from "@tanstack/react-router";
import type { WatchCacheSnapshot } from "@roboco/engine-client";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { useSidebar } from "../state/sidebar";
import { chatListRows, healedSpaceFilter, statusWord, type ChatRow } from "../lib/view";
import { StatusDot } from "./status-dot";
import { ChatRowMenu } from "./chat-menu";

export function ChatList() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();
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
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const visible =
    filter === null ? chats.rows : chats.rows.filter((chat) => chat.spaceId !== undefined && chat.spaceId === filter);
  const rows = chatListRows(visible, snapshot.spaces.rows, snapshot.statuses.rows, now);
  if (rows.length === 0) {
    return <p className="sidebar-note">{filter === null ? "No chats yet." : "No chats in this space."}</p>;
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
    <li className="chat-row-item">
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
      <ChatRowMenu chat={row.chat} />
    </li>
  );
}
