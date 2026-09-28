import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { Icon } from "@roboco/icons";
import { encodeScopedId } from "@roboco/engine-client";
import type { Chat } from "@roboco/proto";
import { useEngineSession, useEngineSessions } from "../../state/session-provider";
import { useFleetChatChangeRequests } from "../../state/change-requests-store";
import { useFleetSnapshot } from "../../state/fleet";
import { useNow } from "../../state/hooks";
import { rightPaneStore } from "../../state/right-pane";
import { sidebarNotice } from "../../state/notice";
import { beginUnsavedSideChat, seedPendingSideChat } from "../../state/side-chats";
import { mintId } from "../../lib/id";
import {
  beginSideChatCreate,
  endSideChatCreate,
  forkSideChat,
} from "../../lib/side-chat-actions";
import {
  bodyBudget,
  childChatRows,
  childChatTitle,
  chromeHeight,
  contentHeight,
  effectiveShown,
  FOOTER_HEIGHT,
  pageShown,
  sectionHeaderLabel,
  sectionLabel,
  sectionsFingerprint,
  showMoreLabel,
  subagentFrozen,
  subagentIndicator,
  subagentRows,
  type ChildChatRow,
  type Section,
  type SubagentRow,
} from "../../lib/explorer-sections";
import { timeAgo } from "../../lib/view";
import { StatusDot } from "../status-dot";
import { ChangeRequestBadge } from "../change-request-badge";
import { useChatMenu } from "../chat-menu";
import { SidebarFadedLabel } from "../sidebar-faded-label";
import { useSidebarDisclosure } from "../sidebar-disclosure";
import type { SubagentOpen } from "../tool-group";

/**
 * The explorer's footer — the web port of the desktop's
 * `FilesSurface::render_sections` (crates/ui/src/files/sections.rs,
 * 731697b6): two collapsible sections docked under the file tree.
 *
 * - **Subagents** — the active chat's spawn chips with their live status;
 *   clicking one opens its right-pane tab.
 * - **Chats** — the side chats hanging off the active chat. Clicking a row
 *   opens it in the surface host; the header carries "+" (a fresh side
 *   chat) and the fork beside its caret, and the empty state offers both
 *   as pills. Rows borrow the sidebar's compact session row shape —
 *   status glyph, title, time — minus the harness/project/device icons
 *   (every row shares the parent's context).
 *
 * The footer's height budget (`FOOTER_HEIGHT`) splits between the open
 * sections (`bodyBudget`), each scrolling inside its share; a section pages
 * its rows behind "Show N more" at `INITIAL_ROWS`/`PAGE_ROWS`.
 *
 * The desktop re-renders its explorer only when the footer's contents
 * changed (the `fingerprint` gate — a streamed transcript delta must never
 * repaint the tree). The web's gate is the transcript subscription's
 * snapshot: the fingerprint string below is identity-stable until the
 * subagent rows actually change, so a delta frame costs one
 * `getSnapshot()` call and no render.
 */

export function ExplorerSections({ chatId }: { chatId: string }) {
  const session = useEngineSession();
  const sessions = useEngineSessions();
  const snapshot = useFleetSnapshot();
  const now = useNow(10_000);
  const [open, setOpen] = useState<Record<Section, boolean>>({
    subagents: true,
    chats: true,
  });
  const [shown, setShown] = useState<Record<Section, number>>({
    subagents: 10,
    chats: 10,
  });
  // The one-at-a-time creation guard (`side_chat_creating`) — local so the
  // header buttons' disabled state re-renders with it. Only the FORK arms it
  // now (upstream #568): "New side chat" mints nothing up front, so there
  // is no in-flight RPC to double-trigger.
  const [creating, setCreating] = useState(false);

  // The active chat's transcript — the SAME store the chat page renders
  // (one `WatchDocMessages` stream per open chat, from the session pool).
  const store = useMemo(
    () => (session !== null ? session.transcripts.get(chatId) : null),
    [session, chatId],
  );
  // The fingerprint gate: the snapshot is a STRING, stable until the
  // subagent rows change — a streamed delta fires the subscription but
  // `getSnapshot` returns the same value and React skips the render.
  const gate = useSyncExternalStore(
    useCallback((listener: () => void) => (store === null ? () => {} : store.subscribe(listener)), [store]),
    useCallback(
      () => (store === null ? "" : sectionsFingerprint(subagentRows(store.getSnapshot().entries), [])),
      [store],
    ),
  );
  const subagents = useMemo(
    () => (store === null ? [] : subagentRows(store.getSnapshot().entries)),
    // The gate is the transcript-driven dep; `store` covers the mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [store, gate],
  );

  const chats = snapshot.chats.loaded ? snapshot.chats.rows : [];
  const parentChat = chats.find((row) => row.id === chatId) ?? null;
  const children = useMemo(
    () =>
      childChatRows(
        chats,
        chatId,
        now,
        (chat) => snapshot.statuses.rows.find((row) => row.chatId === chat.id),
      ),
    [chats, chatId, now, snapshot.statuses.rows],
  );
  // The linked pull requests — the same fleet resolution the sidebar rows
  // read, over the children.
  const childChats = useMemo(
    () => children.map((row) => chats.find((chat) => chat.id === row.chatId)).filter((chat): chat is Chat => chat !== undefined),
    [children, chats],
  );
  const changeRequests = useFleetChatChangeRequests(sessions, childChats);
  const childrenWithRequests = useMemo(
    () =>
      children.flatMap((row) => {
        // The row's `Chat` for the right-click context menu
        // (`ChildChatContextMenu` → the chat menu, files_panel.rs:209-217).
        const chat = chats.find((candidate) => candidate.id === row.chatId);
        return chat === undefined
          ? []
          : [{ ...row, changeRequest: changeRequests.get(row.chatId) ?? null, chat }];
      }),
    [children, changeRequests, chats],
  );

  // ── Actions (the desktop's FilesEvent handlers, files_panel.rs:208-219) ──
  // A row click opens its surface; the header's "+" and fork create; the
  // subagent rows open the subagent tabs. Creation failures surface in the
  // sidebar notice strip — the web's shared error surface (the desktop
  // shows them in the conversation composer's failure chip, which the web
  // composer owns privately).
  const openChildChat = useCallback(
    (row: ChildChatRow) => {
      rightPaneStore.addSideChatSurface(chatId, { chatId: row.chatId, title: row.title });
    },
    [chatId],
  );
  const openSubagent = useCallback(
    (row: SubagentRow) => {
      const payload: SubagentOpen = {
        chatId,
        docId: row.docId,
        title: row.title,
        frozen: subagentFrozen(row),
      };
      rightPaneStore.addSubagentSurface(chatId, payload);
    },
    [chatId],
  );
  const newChildChat = useCallback(() => {
    if (session === null || parentChat === null) {
      sidebarNotice.set("Start a conversation before creating a side chat.");
      return;
    }
    // "New side chat" writes NOTHING up front (upstream #568): the tab
    // opens on a local-only chat — no doc watch, no registry row — whose
    // first send runs the createChat (with the parent link) before the run.
    // Closing it unsent drops it, draft or not: no row could reopen it. The
    // id mints locally and scopes immediately — every merged fleet row id is
    // scoped, and the surface keys on the scoped form (§2.3); the wire
    // decodes either form on the first send's mint.
    const scoped = encodeScopedId(session.engine.baseUrl, mintId());
    beginUnsavedSideChat({
      ...parentChat,
      id: scoped,
      title: null,
      lastMessagePreview: null,
      lastMessageAt: null,
      lastSeenAt: null,
      createdAt: new Date().toISOString(),
      parentChatId: parentChat.id,
    });
    rightPaneStore.addSideChatSurface(chatId, { chatId: scoped, title: "New side chat" });
  }, [session, parentChat, chatId]);
  const forkChat = useCallback(() => {
    if (session === null || parentChat === null) {
      sidebarNotice.set("Start a conversation before creating a side chat.");
      return;
    }
    if (!beginSideChatCreate()) {
      return;
    }
    setCreating(true);
    void (async () => {
      try {
        const chat = await forkSideChat(session.client, {
          chatId: mintId(),
          sourceChatId: parentChat.id,
          parentChatId: parentChat.id,
          targetDeviceId: parentChat.deviceId,
        });
        // The reply is the ENGINE's row (raw id); the surface and the
        // fleet rows speak the scoped form — and the seed (the desktop's
        // `pending_side_chat`) bridges the beat before the registry frame
        // lands the row.
        const scoped = encodeScopedId(session.engine.baseUrl, chat.id);
        seedPendingSideChat({ ...chat, id: scoped });
        rightPaneStore.addSideChatSurface(chatId, {
          chatId: scoped,
          title: childChatTitle(chat),
        });
      } catch (error) {
        sidebarNotice.set(
          error instanceof Error ? error.message : "The side chat could not be created.",
        );
      } finally {
        endSideChatCreate();
        setCreating(false);
      }
    })();
  }, [session, parentChat, chatId]);

  // ── The height budget (`render_sections`, sections.rs:384-449) ───────────
  const subagentShown = effectiveShown(shown.subagents);
  const chatShown = effectiveShown(shown.chats);
  const wants: [number, number] = [
    contentHeight("subagents", subagents.length, subagentShown),
    contentHeight("chats", children.length, chatShown),
  ];
  const budget = FOOTER_HEIGHT - chromeHeight();
  const heights = bodyBudget(budget, wants, [open.subagents, open.chats]);
  // `render_section`'s `full` (sections.rs:527-531): the tween target. Open,
  // it is the budget share (close tweens it to 0); closed, it is the WANT
  // capped by the whole budget — a re-open tweens up to what the section
  // would take on its own.
  const full = (which: "subagents" | "chats", isOpen: boolean): number => {
    const want = which === "subagents" ? wants[0]! : wants[1]!;
    const share = which === "subagents" ? heights[0]! : heights[1]!;
    return isOpen ? share : Math.max(Math.min(want, budget), 0);
  };

  return (
    <div className="files-sections" aria-label="Explorer sections">
      <SectionShell
        section="subagents"
        chatId={chatId}
        open={open.subagents}
        onToggle={(next) => setOpen((current) => ({ ...current, subagents: next }))}
        full={full("subagents", open.subagents)}
        count={subagents.length}
        label={sectionHeaderLabel("subagents", open.subagents, subagents.length)}
        actions={null}
      >
        <SubagentRows
          rows={subagents}
          shown={subagentShown}
          now={now}
          onOpen={openSubagent}
          onShowMore={() => setShown((current) => ({ ...current, subagents: pageShown(current.subagents) }))}
        />
      </SectionShell>
      <SectionShell
        section="chats"
        chatId={chatId}
        open={open.chats}
        onToggle={(next) => setOpen((current) => ({ ...current, chats: next }))}
        full={full("chats", open.chats)}
        count={children.length}
        label={sectionHeaderLabel("chats", open.chats, children.length)}
        actions={
          <span className="files-section-actions">
            <button
              type="button"
              className="files-section-action"
              aria-label="New side chat"
              title="New side chat"
              disabled={creating}
              onClick={(event) => {
                event.stopPropagation();
                newChildChat();
              }}
            >
              <Icon name="plus" size={13} />
            </button>
            <button
              type="button"
              className="files-section-action"
              aria-label="Fork this chat"
              title="Fork this chat"
              disabled={creating}
              onClick={(event) => {
                event.stopPropagation();
                forkChat();
              }}
            >
              <Icon name="gitBranch" size={13} />
            </button>
          </span>
        }
      >
        <ChatRows
          rows={childrenWithRequests}
          shown={chatShown}
          onOpen={openChildChat}
          onShowMore={() => setShown((current) => ({ ...current, chats: pageShown(current.chats) }))}
          onFork={forkChat}
          onNew={newChildChat}
        />
      </SectionShell>
    </div>
  );
}

/**
 * One section's chrome — the header (chevron, label, the count while
 * collapsed, the Chats header's hover actions) over the animated disclosure
 * body (`render_section` + `render_disclosure_body`, sections.rs:483-616).
 * The body rests at the height the budget gave it and the rows scroll
 * inside; the shared sidebar disclosure tween drives the collapse.
 */
function SectionShell({
  section,
  chatId,
  open,
  onToggle,
  full,
  count,
  label,
  actions,
  children,
}: {
  section: Section;
  chatId: string;
  open: boolean;
  onToggle: (next: boolean) => void;
  /**
   * The disclosure's tween target AND resting height
   * (`render_section`'s `full`, sections.rs:527-531): the budget share while
   * open (the resting height — the rows scroll inside it; close tweens it
   * to 0), the want capped by the whole budget while closed (a re-open
   * tweens up to what the section would take on its own).
   */
  full: number;
  count: number;
  label: string;
  actions: ReactNode;
  children: ReactNode;
}) {
  const { bodyRef, chevronRef, toggle } = useSidebarDisclosure(
    `files-section:${chatId}:${section}`,
    open,
    full,
  );

  const onHeaderClick = (): void => {
    // A pure flip — the desktop's section toggle never resets the paging
    // (only the sidebar's Archived shelf does).
    onToggle(toggle());
  };

  return (
    <section className="files-section" data-open={open ? "1" : undefined}>
      {/*
        The header row — `render_section`'s header (sections.rs:502-561): a
        role=button DIV, not a `<button>`, because the Chats header carries
        its own icon buttons beside the caret (nested interactive elements
        are invalid). The actions stop propagation — the caret is the
        resting state, the actions appear on approach.
      */}
      <div
        className="files-section-header"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        aria-label={`${open ? "Collapse" : "Expand"} ${sectionLabel(section)}`}
        onClick={onHeaderClick}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onHeaderClick();
          }
        }}
      >
        <span ref={chevronRef} className="files-section-chevron" aria-hidden>
          <Icon name="altArrowRight" size={12} />
        </span>
        <span className="files-section-label">{label}</span>
        {actions}
      </div>
      <div ref={bodyRef} className="files-section-body">
        {children}
      </div>
    </section>
  );
}

/**
 * A section list's edge fades (`faded_list`, sections.rs:832-848): rows
 * dissolve at the top/bottom 16px band while there is more to scroll to,
 * plain when everything fits. Scroll events, the list's own size (the
 * budget share), and row-count changes all recompute the pair; the mask
 * lives in the stylesheet.
 */
function useListEdgeFades(recheckKey: number): {
  listRef: React.RefObject<HTMLUListElement | null>;
  fades: { top: boolean; bottom: boolean };
} {
  const listRef = useRef<HTMLUListElement | null>(null);
  const [fades, setFades] = useState({ top: false, bottom: false });
  const recompute = useCallback(() => {
    const el = listRef.current;
    if (el === null) {
      return;
    }
    const scrolled = el.scrollTop;
    const maxScroll = el.scrollHeight - el.clientHeight;
    setFades((current) => {
      const top = scrolled > 1;
      const bottom = scrolled < maxScroll - 1;
      return current.top === top && current.bottom === bottom ? current : { top, bottom };
    });
  }, []);
  useEffect(() => {
    const el = listRef.current;
    if (el === null) {
      return;
    }
    recompute();
    el.addEventListener("scroll", recompute);
    const observer =
      typeof ResizeObserver === "undefined" ? null : new ResizeObserver(() => recompute());
    observer?.observe(el);
    return () => {
      el.removeEventListener("scroll", recompute);
      observer?.disconnect();
    };
  }, [recompute, recheckKey]);
  return { listRef, fades };
}

/** The Subagents rows — `render_subagent_rows` (sections.rs:672-735). */
function SubagentRows({
  rows,
  shown,
  now,
  onOpen,
  onShowMore,
}: {
  rows: readonly SubagentRow[];
  shown: number;
  now: number;
  onOpen: (row: SubagentRow) => void;
  onShowMore: () => void;
}) {
  const { listRef, fades } = useListEdgeFades(rows.length);
  if (rows.length === 0) {
    return (
      <div className="files-section-empty">
        <p>Subagents will appear here when they are created</p>
      </div>
    );
  }
  const visible = rows.slice(0, shown);
  const remaining = rows.length - visible.length;
  return (
    <ul
      ref={listRef}
      className="files-section-list"
      data-fade-top={fades.top ? "1" : "0"}
      data-fade-bottom={fades.bottom ? "1" : "0"}
    >
      {visible.map((row) => (
        <li key={row.docId}>
          <button
            type="button"
            className="files-section-row"
            aria-label={`Open subagent ${row.title}`}
            onClick={() => onOpen(row)}
          >
            <StatusDot status={subagentIndicator(row)} />
            <SidebarFadedLabel className="files-section-row-title" fill>
              {row.title}
            </SidebarFadedLabel>
            <span className="files-section-row-time">{subagentTimeAgo(row, now)}</span>
          </button>
        </li>
      ))}
      {remaining > 0 && (
        <li>
          <button type="button" className="files-section-row files-section-more" onClick={onShowMore}>
            {showMoreLabel(remaining)}
          </button>
        </li>
      )}
    </ul>
  );
}

/** The Chats rows — `render_chat_rows` (sections.rs:737-821). */
function ChatRows({
  rows,
  shown,
  onOpen,
  onShowMore,
  onFork,
  onNew,
}: {
  rows: readonly (ChildChatRow & {
    changeRequest: NonNullable<ChildChatRow["changeRequest"]> | null;
    chat: Chat;
  })[];
  shown: number;
  onOpen: (row: ChildChatRow) => void;
  onShowMore: () => void;
  onFork: () => void;
  onNew: () => void;
}) {
  const { listRef, fades } = useListEdgeFades(rows.length);
  if (rows.length === 0) {
    return (
      <div className="files-section-empty">
        <p>Side chats will appear here when they are created</p>
        <div className="files-section-empty-actions">
          <button
            type="button"
            className="files-section-pill"
            onClick={(event) => {
              event.stopPropagation();
              onFork();
            }}
          >
            <Icon name="gitBranch" size={12} />
            Fork
          </button>
          <button
            type="button"
            className="files-section-pill"
            onClick={(event) => {
              event.stopPropagation();
              onNew();
            }}
          >
            <Icon name="plus" size={12} />
            New side chat
          </button>
        </div>
      </div>
    );
  }
  const visible = rows.slice(0, shown);
  const remaining = rows.length - visible.length;
  return (
    <ul
      ref={listRef}
      className="files-section-list"
      data-fade-top={fades.top ? "1" : "0"}
      data-fade-bottom={fades.bottom ? "1" : "0"}
    >
      {visible.map((row) => (
        <ChatRowItem key={row.chatId} row={row} onOpen={onOpen} />
      ))}
      {remaining > 0 && (
        <li>
          <button type="button" className="files-section-row files-section-more" onClick={onShowMore}>
            {showMoreLabel(remaining)}
          </button>
        </li>
      )}
    </ul>
  );
}

/**
 * One Chats row — the compact row, right-clickable into the SAME chat
 * context menu the sidebar rows open (`ChildChatContextMenu`,
 * files_panel.rs:209-217 → the chat menu at the pointer).
 */
function ChatRowItem({
  row,
  onOpen,
}: {
  row: ChildChatRow & {
    changeRequest: NonNullable<ChildChatRow["changeRequest"]> | null;
    chat: Chat;
  };
  onOpen: (row: ChildChatRow) => void;
}) {
  const { menu, element } = useChatMenu(row.chat);
  return (
    <li>
      {menu(
        <button
          type="button"
          className="files-section-row"
          aria-label={`Open side chat ${row.title}`}
          onClick={() => onOpen(row)}
        >
          <StatusDot status={row.status} />
          <SidebarFadedLabel className="files-section-row-title" fill>
            {row.title}
          </SidebarFadedLabel>
          {row.changeRequest !== null && <ChangeRequestBadge summary={row.changeRequest} />}
          <span className="files-section-row-time">{row.timeAgo}</span>
        </button>,
      )}
      {element}
    </li>
  );
}

/** A subagent's spawn-time label — `format_time_ago(spawned_at, now)`. */
function subagentTimeAgo(row: SubagentRow, now: number): string {
  const iso = new Date(row.spawnedAt).toISOString();
  return timeAgo(iso, now);
}
