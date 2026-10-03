import { useState } from "react";
import { Icon } from "@roboco/icons";
import { useEngineSessions } from "../state/session-provider";
import { useFleetChatChangeRequests } from "../state/change-requests-store";
import { useFleetSnapshot } from "../state/fleet";
import { useNow } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { healedSpaceFilter, archivedChatRows, sidebarRowHeight, type ChatRow } from "../lib/view";
import { ChatListRow } from "./chat-list";
import { SidebarDisclosureBody, SidebarDisclosureHeader, useSidebarDisclosure } from "./sidebar-disclosure";

const INITIAL = 10;
const PAGE = 25;
/** `shell.rs::SIDEBAR_LIST_GAP`. */
const SIDEBAR_LIST_GAP = 2;
/** `spaces.rs::SIDEBAR_DISCLOSURE_BODY_INSET`. */
const SIDEBAR_DISCLOSURE_BODY_INSET = 4;
/** The "Show N more" tail row (the desktop keeps its one-line 36px shape). */
const ARCHIVED_MORE_ROW_HEIGHT = 36;

/**
 * The sidebar's archived shelf (`spaces.rs::render_archived_section` after
 * upstream dfd2fc0c): a collapsible "Archived (N)" disclosure — the count
 * shows only while collapsed — of rows in the user's sidebar sort sharing
 * the ACTIVE list's `ChatRow` data and layout (compact mode, project
 * monograms, branch/PR metadata — `archivedChatRows` is the web peer of
 * `sidebar_chat_data`). Archived history recedes: monogram and title dim
 * at rest and restore on hover/selection; the time label yields to the
 * Unarchive pill on row hover; the tail pages behind "Show N more".
 * Nothing renders when nothing is archived.
 */
export function ArchivedSection() {
  // The MERGED fleet snapshot: archived rows from every paired engine.
  const snapshot = useFleetSnapshot();
  const sessions = useEngineSessions();
  const sidebar = useSidebar();
  const now = useNow(10_000);
  const [shown, setShown] = useState(INITIAL);

  // Hooks must run unconditionally across the empty/loading returns below:
  // the shelf mounts on a page whose first render has no snapshot at all.
  const chats = snapshot.chats;
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  // The change-request watches the active list resolved (shared metadata).
  const visibleChats =
    chats.error === null && chats.loaded
      ? filter === null
        ? chats.rows
        : chats.rows.filter((chat) => chat.spaceId !== undefined && chat.spaceId === filter)
      : [];
  const changeRequests = useFleetChatChangeRequests(sessions, visibleChats);
  const compact = sidebar.compact;
  const showLabel = sidebar.showProjectLabel;
  const rows =
    chats.error === null && chats.loaded
      ? archivedChatRows(
          chats.rows,
          snapshot.spaces.rows,
          filter,
          snapshot.statuses.rows,
          now,
          snapshot.devices.rows,
          {
            sort: sidebar.sort,
            showHarness: sidebar.showHarness,
            showBranch: sidebar.showBranch,
            showPullRequest: sidebar.showPullRequest,
            changeRequests,
          },
        )
      : [];
  const open = sidebar.archivedOpen;
  const visible = rows.slice(0, Math.max(INITIAL, shown));
  const remaining = rows.length - visible.length;
  const rowHeight = (row: ChatRow): number =>
    sidebarRowHeight(compact, showLabel, row.branch !== null, row.changeRequest !== null);
  // The body-height estimate the disclosure tween and collapsed clipping
  // share: inset + rows + gaps + the "Show N more" tail when it pages.
  const bodyHeight =
    SIDEBAR_DISCLOSURE_BODY_INSET +
    visible.reduce((total, row) => total + rowHeight(row), 0) +
    Math.max(visible.length - 1, 0) * SIDEBAR_LIST_GAP +
    (remaining > 0 ? ARCHIVED_MORE_ROW_HEIGHT + SIDEBAR_LIST_GAP : 0);
  const { bodyRef, chevronRef, toggle } = useSidebarDisclosure("archived", open, bodyHeight);

  if (chats.error !== null || !chats.loaded) {
    return null;
  }
  if (rows.length === 0) {
    return null;
  }

  function onToggle(): void {
    // The motion begins on the CURRENT height before the flip; reopening
    // never remembers a previous "show more" expansion.
    toggle();
    sidebarStore.setArchivedOpen(!open);
    setShown(INITIAL);
  }

  return (
    <section className="archived" aria-label="Archived chats">
      <SidebarDisclosureHeader
        id="archived-toggle"
        label={open ? "Archived" : `Archived (${rows.length})`}
        open={open}
        withRule={false}
        chevronRef={chevronRef}
        onToggle={onToggle}
      />
      <SidebarDisclosureBody bodyRef={bodyRef}>
        <ul className="archived-list">
          {visible.map((row) => (
            <ArchivedRow
              key={row.chat.id}
              row={row}
              compact={compact}
              showLabel={showLabel}
              showProjectIcon={sidebar.showProjectIcon}
            />
          ))}
          {remaining > 0 && (
            <li>
              <button
                type="button"
                className="archived-more"
                onClick={() => setShown((current) => Math.max(current, INITIAL) + PAGE)}
              >
                <Icon name="plus" size={14} />
                Show {Math.min(remaining, PAGE)} more
              </button>
            </li>
          )}
        </ul>
      </SidebarDisclosureBody>
    </section>
  );
}

/**
 * The archived row — the SAME card the active list draws (the desktop's
 * archived shelf renders `render_chat_row` with `archived=true`): the
 * shared `ChatListRow` carries the status corner, the project @ device
 * line, the branch/PR metadata, the hover Unarchive pill, and the chat
 * menu. The shelf's height model (`sidebarRowHeight`) and its row
 * renderer finally agree — nothing is missing, and rows are sized for
 * what they actually render.
 */
function ArchivedRow({
  row,
  compact,
  showLabel,
  showProjectIcon,
}: {
  row: ChatRow;
  compact: boolean;
  showLabel: boolean;
  showProjectIcon: boolean;
}) {
  return (
    <li className="arch-row-item">
      <ChatListRow
        row={row}
        compact={compact}
        showLabel={showLabel}
        showProjectIcon={showProjectIcon}
      />
    </li>
  );
}
