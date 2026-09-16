import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { sidebarNotice } from "../state/notice";
import { describeMutateError, setChatArchived } from "../lib/chat-actions";
import { archivedRows, healedSpaceFilter, type ArchivedRow as ArchivedRowData } from "../lib/view";

const INITIAL = 10;
const PAGE = 25;

/**
 * The sidebar's archived shelf (shell/spaces.rs render_archived_section):
 * a collapsible "Archived (N)" disclosure — the count shows only while
 * collapsed — of slim one-line rows in recency order, filtered by the
 * space filter. The time label yields to Unarchive on row hover; the tail
 * pages behind "Show N more". Nothing renders when nothing is archived.
 */
export function ArchivedSection() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();
  const now = useNow(10_000);
  const [shown, setShown] = useState(INITIAL);

  if (session === null || snapshot === null || !snapshot.chats.loaded || snapshot.chats.error !== null) {
    return null;
  }
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const rows = archivedRows(snapshot.chats.rows, filter, now);
  if (rows.length === 0) {
    return null;
  }
  const open = sidebar.archivedOpen;
  const visible = rows.slice(0, Math.max(INITIAL, shown));
  const remaining = rows.length - visible.length;

  function toggle(): void {
    sidebarStore.setArchivedOpen(!open);
    setShown(INITIAL);
  }

  return (
    <section className="archived" aria-label="Archived chats">
      <button type="button" className="archived-header" onClick={toggle} aria-expanded={open}>
        <span className="archived-label">{open ? "Archived" : `Archived (${rows.length})`}</span>
        <span className="archived-rule" />
        <span className={`archived-chevron ${open ? "archived-chevron-open" : ""}`}>▸</span>
      </button>
      {open && (
        <ul className="archived-list">
          {visible.map((row) => (
            <ArchivedRow key={row.chat.id} row={row} />
          ))}
          {remaining > 0 && (
            <li>
              <button type="button" className="archived-more" onClick={() => setShown((current) => current + PAGE)}>
                Show {Math.min(remaining, PAGE)} more
              </button>
            </li>
          )}
        </ul>
      )}
    </section>
  );
}

function ArchivedRow({ row }: { row: ArchivedRowData }) {
  const session = useEngineSession();

  function unarchive(): void {
    if (session === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    setChatArchived(session.client, row.chat.id, false).catch((error: unknown) => {
      sidebarNotice.set(describeMutateError(error));
    });
  }

  return (
    <li className="arch-row-item">
      <Link to="/chat/$chatId" params={{ chatId: row.chat.id }} className="arch-row">
        <span className="arch-row-title">{row.title}</span>
        <span className="arch-row-time">{row.timeAgo}</span>
      </Link>
      <span className="arch-row-action">
        <button type="button" className="arch-row-unarchive" onClick={unarchive}>
          Unarchive
        </button>
      </span>
    </li>
  );
}
