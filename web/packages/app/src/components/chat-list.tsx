import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { useSidebar } from "../state/sidebar";
import { sidebarNotice } from "../state/notice";
import { describeMutateError, setChatArchived } from "../lib/chat-actions";
import { chatListRows, healedSpaceFilter, statusWord, type ChatRow } from "../lib/view";
import { ChatRowMenu } from "./chat-menu";
import { GlyphSpinner } from "./glyph-spinner";

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
  const rows = chatListRows(visible, snapshot.spaces.rows, snapshot.statuses.rows, now, snapshot.devices.rows);
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

/**
 * One sidebar chat card — the desktop's `shell.rs::render_chat_row`, line for
 * line:
 *
 * 1. `project @ device` at 11px/14px in the muted subline tone, with the
 *    status corner right-aligned. The corner is activity, not position: a
 *    small colored word beside a glyph — Working animates the pixel spinner,
 *    Done wears a check, the rest use a 6px dot — and Idle rows show the
 *    relative time instead.
 * 2. The harness brand mark (13px) beside the title at 13px/17px.
 * 3. Structural, not reserved: branch and change-request badge, omitted
 *    entirely when the chat has neither.
 *
 * Hovering the ROW (not the corner — corner-only tested as undiscoverable)
 * swaps the corner for the Archive pill, whose padding bleeds into the row's
 * so its text right-aligns exactly where the status word sat: the swap moves
 * pixels around the label, not the label itself.
 */
function ChatListRow({ row }: { row: ChatRow }) {
  const session = useEngineSession();
  const [hovered, setHovered] = useState(false);
  const word = statusWord(row.status);
  const archived = row.chat.archived;
  const brand = row.harness === null ? null : harnessBrandIcon(row.harness);

  function toggleArchive(event: React.MouseEvent): void {
    // The row's own click is the selector; only the corner archives.
    event.preventDefault();
    event.stopPropagation();
    if (session === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    setChatArchived(session.client, row.chat.id, !archived).catch((error: unknown) => {
      sidebarNotice.set(describeMutateError(error));
    });
  }

  return (
    <li
      className="chat-row-item"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <Link
        to="/chat/$chatId"
        params={{ chatId: row.chat.id }}
        className="chat-row"
        data-status={row.status}
        activeProps={{ className: "chat-row chat-row-active" }}
      >
        <div className="chat-row-line">
          <span className="chat-row-folder">{row.folder}</span>
          <span className="chat-row-corner">
            {hovered ? (
              <span className="chat-row-archive" onClick={toggleArchive} role="presentation">
                <Icon name={archived ? "archiveUpMinimalistic" : "archiveMinimalistic"} size={11} />
                {archived ? "Unarchive" : "Archive"}
              </span>
            ) : word === null ? (
              <span className="chat-row-time">{row.timeAgo}</span>
            ) : (
              <span className={`chat-row-status status-${row.status}`}>
                <StatusGlyph status={row.status} />
                {word}
              </span>
            )}
          </span>
        </div>
        <div className="chat-row-title-line">
          {brand !== null && (
            <Icon
              name={brand.name}
              size={13}
              className="chat-row-brand"
              style={brand.tint === null ? undefined : { color: brand.tint }}
            />
          )}
          <span className="chat-row-title">{row.chat.title ?? "New session"}</span>
        </div>
        {row.branch !== null && (
          <div className="chat-row-meta">
            <Icon name="gitBranch" size={11} />
            <span className="chat-row-branch">{row.branch}</span>
          </div>
        )}
      </Link>
      <ChatRowMenu chat={row.chat} />
    </li>
  );
}

/**
 * The corner's glyph slot (`render_chat_row`): Done wears the check, Working
 * the animated pixel glyph, everything else a compact 6px dot.
 */
function StatusGlyph({ status }: { status: ChatRow["status"] }) {
  if (status === "completed") {
    return <Icon name="check" size={11} />;
  }
  if (status === "working") {
    return <GlyphSpinner size={11} />;
  }
  return <span className={`dot dot-${status}`} />;
}
