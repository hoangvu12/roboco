import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { parseScopedId } from "@roboco/engine-client";
import { useEngineSessions } from "../state/session-provider";
import type { EngineSession } from "../state/engine-session";
import { useFleetSnapshot } from "../state/fleet";
import { useNow } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { sidebarNotice } from "../state/notice";
import { describeMutateError, setChatArchived } from "../lib/chat-actions";
import { archivedRows, healedSpaceFilter, type ArchivedRow as ArchivedRowData } from "../lib/view";
import { useChatMenu } from "./chat-menu";
import { SidebarDisclosureBody, SidebarDisclosureHeader, useSidebarDisclosure } from "./sidebar-disclosure";

const INITIAL = 10;
const PAGE = 25;
/** `spaces.rs::render_archived_section`'s fixed row height. */
const ARCHIVED_ROW_HEIGHT = 36;
/** `shell.rs::SIDEBAR_LIST_GAP`. */
const SIDEBAR_LIST_GAP = 2;
/** `spaces.rs::SIDEBAR_DISCLOSURE_BODY_INSET`. */
const SIDEBAR_DISCLOSURE_BODY_INSET = 4;
/**
 * `shell.rs::SIDEBAR_ARCHIVED_HARNESS_*` — the one-line shelf gives its
 * larger mark more separation than the active card's 13/8 pair.
 */
export const SIDEBAR_ARCHIVED_HARNESS_ICON_SIZE = 14;
export const SIDEBAR_ARCHIVED_HARNESS_TITLE_GAP = 10;

/**
 * The sidebar's archived shelf (`spaces.rs::render_archived_section`): a
 * collapsible "Archived (N)" disclosure — the count shows only while
 * collapsed — of slim 36px one-line rows in the user's sidebar sort (the
 * same `compareSidebarChats` the active list uses, never its own recency
 * order), filtered by the space filter. Rows dim their harness mark and
 * title at rest and brighten on hover/selection; the time label yields to
 * the Unarchive pill on row hover; the tail pages behind "Show N more".
 * Right mouse-down opens the same chat context menu the active rows use.
 * Nothing renders when nothing is archived.
 */
export function ArchivedSection() {
  // The MERGED fleet snapshot: archived rows from every paired engine.
  const snapshot = useFleetSnapshot();
  const sidebar = useSidebar();
  const now = useNow(10_000);
  const [shown, setShown] = useState(INITIAL);

  // Hooks must run unconditionally across the empty/loading returns below:
  // the shelf mounts on a page whose first render has no snapshot at all.
  const chats = snapshot.chats;
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const rows =
    chats.error === null && chats.loaded
      ? archivedRows(chats.rows, filter, now, sidebar.sort)
      : [];
  const open = sidebar.archivedOpen;
  const visible = rows.slice(0, Math.max(INITIAL, shown));
  const remaining = rows.length - visible.length;
  // The body-height estimate the disclosure tween and collapsed clipping
  // share: inset + rows + gaps + the "Show N more" tail when it pages.
  const bodyHeight =
    SIDEBAR_DISCLOSURE_BODY_INSET +
    visible.length * ARCHIVED_ROW_HEIGHT +
    Math.max(visible.length - 1, 0) * SIDEBAR_LIST_GAP +
    (remaining > 0 ? ARCHIVED_ROW_HEIGHT + SIDEBAR_LIST_GAP : 0);
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
            <ArchivedRow key={row.chat.id} row={row} showHarness={sidebar.showHarness} />
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
 * The archived row's right-slot choice (`spaces.rs:1669-1712`): exactly
 * ONE child, picked at render — the time-ago at rest, the Unarchive pill
 * while the row is hovered. Never both, and never pinned by focus or
 * touch: no CSS decides this, the row does.
 */
export function archivedRightSlot(hovered: boolean): "time" | "pill" {
  return hovered ? "pill" : "time";
}

function ArchivedRow({ row, showHarness }: { row: ArchivedRowData; showHarness: boolean }) {
  // Unarchive routes to the row's owning engine off its scoped id.
  const sessions = useEngineSessions();
  const session = archivedSession(sessions, row.chat.id);
  const harness = showHarness ? row.chat.config?.harness ?? null : null;
  const brand = harness === null ? null : harnessBrandIcon(harness);
  const { menu, element } = useChatMenu(row.chat);
  // Per-row hover state — the web equivalent of the desktop's
  // `archived_hover` field (`spaces.rs:1654`, set/cleared by the row's
  // listener at `:1729-1739`), never a sidebar-store concern.
  const [hovered, setHovered] = useState(false);

  function unarchive(event: React.MouseEvent): void {
    // The row's own click opens the chat; only the pill restores.
    event.preventDefault();
    event.stopPropagation();
    if (session === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    setChatArchived(session.client, row.chat.id, false).catch((error: unknown) => {
      sidebarNotice.set(describeMutateError(error));
    });
  }

  // Right slot: time at rest; the Unarchive affordance takes its place on
  // row hover — ONE child, chosen at render the way the desktop does it
  // (`spaces.rs:1669-1712`) and the way the active rows' corner already
  // does, so no CSS pin can hold the pill on touch or after a click. The
  // pill sits inside the row's Link, so hovering it keeps the row hovered
  // — no flicker. `menu` wraps the Link so a right-click opens the SAME
  // chat context menu the active rows use, at the pointer.
  return (
    <li className="arch-row-item">
      {menu(
        <Link
          to="/chat/$chatId"
          params={{ chatId: row.chat.id }}
          className="arch-row"
          activeProps={{ className: "arch-row arch-row-active" }}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
        >
          {brand !== null && (
            <Icon
              name={brand.name}
              size={SIDEBAR_ARCHIVED_HARNESS_ICON_SIZE}
              className="arch-row-brand"
              style={brand.tint === null ? undefined : { color: brand.tint }}
            />
          )}
          <span className="arch-row-title">{row.title}</span>
          {archivedRightSlot(hovered) === "pill" ? (
            <button
              type="button"
              className="arch-row-unarchive"
              aria-label="Unarchive chat"
              onClick={unarchive}
            >
              <Icon name="archiveUpMinimalistic" size={11} />
              Unarchive
            </button>
          ) : (
            <span className="arch-row-time">{row.timeAgo}</span>
          )}
        </Link>,
      )}
      {element}
    </li>
  );
}

/** The session owning a scoped chat id — the unarchive router. */
function archivedSession(
  sessions: ReadonlyMap<string, EngineSession>,
  chatId: string,
): EngineSession | null {
  try {
    const engine = parseScopedId(chatId).engine;
    return engine === null ? null : sessions.get(engine) ?? null;
  } catch {
    return null;
  }
}
