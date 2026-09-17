import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Link, useNavigate } from "@tanstack/react-router";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { parseScopedId } from "@roboco/engine-client";
import { useEngineSession, useEngineSessions } from "../state/session-provider";
import type { EngineSession } from "../state/engine-session";
import { engineStatesOf, fleetLocalDeviceId, useFleet, useFleetRegistry, useFleetSnapshot } from "../state/fleet";
import { useNow } from "../state/hooks";
import { useSidebar } from "../state/sidebar";
import { sidebarNotice } from "../state/notice";
import { cycleTarget, onShortcut } from "../state/shortcuts";
import { useJumpHints, visibleJumpOrder } from "../state/jump-hints";
import { describeMutateError, setChatArchived } from "../lib/chat-actions";
import {
  chatListRows,
  chatRowHeight,
  healedSpaceFilter,
  resortOffsets,
  sidebarGroups,
  sidebarKeyOrderChanged,
  sidebarVisibleOrder,
  statusWord,
  type ChatRow,
  type SidebarKeyed,
} from "../lib/view";
import { useFleetChatChangeRequests } from "../state/change-requests-store";
import { useChatMenu } from "./chat-menu";
import { GlyphSpinner } from "./glyph-spinner";
import { SidebarFadedLabel } from "./sidebar-faded-label";
import {
  SidebarDisclosureBody,
  SidebarDisclosureHeader,
  useSidebarDisclosure,
} from "./sidebar-disclosure";
import { ChangeRequestBadge } from "./change-request-badge";

/** `shell.rs::SIDEBAR_LIST_GAP` — the flex gap between sidebar rows. */
export const SIDEBAR_LIST_GAP = 2;
/**
 * `shell.rs::SIDEBAR_ACTIVE_HARNESS_*` — the active card keeps its harness
 * identity close on the standard 8px rhythm (SPACE_SM).
 */
export const SIDEBAR_ACTIVE_HARNESS_TITLE_GAP = 8;
export const SIDEBAR_ACTIVE_HARNESS_ICON_SIZE = 13;
/** `spaces.rs::SIDEBAR_SECTION_GAP` — every disclosure section's top band. */
const SIDEBAR_SECTION_GAP = 12;
/** `spaces.rs::SIDEBAR_DISCLOSURE_BODY_INSET` — the body's handoff padding. */
const SIDEBAR_DISCLOSURE_BODY_INSET = 4;
/**
 * `spaces.rs::SIDEBAR_DISCLOSURE_SECTION_HEIGHT` = SECTION_GAP + HEADER (28):
 * a collapsed section's keyed height for the FLIP diff.
 */
const SIDEBAR_DISCLOSURE_SECTION_HEIGHT = SIDEBAR_SECTION_GAP + 28;

/**
 * `shell.rs::RESORT` — 260ms `cubic-bezier(0.22, 1, 0.36, 1)`. A UI-level
 * spec the proto motion catalog does not carry (it lives beside the FLIP
 * code in shell.rs, not in motion.rs), so the web declares it here and as
 * `--rb-motion-resort` in app.css rather than through @roboco/theme.
 */
export const SIDEBAR_RESORT_MS = 260;
export const SIDEBAR_RESORT_CURVE: readonly [number, number, number, number] = [0.22, 1, 0.36, 1];
export const SIDEBAR_RESORT_EASING = `cubic-bezier(${SIDEBAR_RESORT_CURVE.join(", ")})`;

function prefersReducedMotion(): boolean {
  const query = (globalThis as { matchMedia?: (query: string) => { matches: boolean } }).matchMedia;
  if (query === undefined) {
    return false;
  }
  return query("(prefers-reduced-motion: reduce)").matches;
}

interface ResortState {
  readonly epoch: number;
  readonly offsets: ReadonlyMap<string, number>;
  readonly newKeys: ReadonlySet<string>;
}

const RESORT_NONE: ResortState = { epoch: 0, offsets: new Map(), newKeys: new Set() };

/**
 * The §2.7 FLIP diff, in render order: `useLayoutEffect` compares this
 * render's keyed list against the previous one AFTER the DOM is laid out at
 * its new positions but BEFORE paint — a reorder computes each surviving
 * key's paint-only start offset and bumps the epoch, so the moved elements
 * animate from the offset down to zero over `RESORT`. First fill never
 * animates; a height-only change (a disclosure opening) is not a reorder;
 * removals just go (their survivors glide up to close the gap).
 */
function useSidebarResort(keyed: readonly SidebarKeyed[]): ResortState {
  const prev = useRef<readonly SidebarKeyed[]>([]);
  const [state, setState] = useState<ResortState>(RESORT_NONE);
  useLayoutEffect(() => {
    const old = prev.current;
    prev.current = keyed;
    if (old.length === 0 || !sidebarKeyOrderChanged(old, keyed)) {
      return;
    }
    const offsets = resortOffsets(old, keyed, SIDEBAR_LIST_GAP);
    const oldKeys = new Set(old.map((entry) => entry.key));
    const newKeys = new Set(
      keyed.filter((entry) => !oldKeys.has(entry.key)).map((entry) => entry.key),
    );
    if (offsets.size === 0 && newKeys.size === 0) {
      return;
    }
    setState((current) => ({ epoch: current.epoch + 1, offsets, newKeys }));
  }, [keyed]);
  return state;
}

/**
 * One keyed element's paint-only glide: Web Animations API from
 * `translateY(dy)` to none, so layout stays at the final position and only
 * the paint offset tweens — the desktop's `with_animation` relative-inset
 * equivalent. New keys fade in via the `chat-row-in` CSS class instead
 * (fresh mount, the animation runs once). Reduced motion skips both.
 */
function useResortGlide(ref: React.RefObject<HTMLElement | null>, dy: number | undefined, epoch: number): void {
  useLayoutEffect(() => {
    const el = ref.current;
    if (el === null || dy === undefined || dy === 0 || prefersReducedMotion()) {
      return;
    }
    const animation = el.animate(
      [{ transform: `translateY(${dy}px)` }, { transform: "translateY(0)" }],
      {
        duration: SIDEBAR_RESORT_MS,
        easing: SIDEBAR_RESORT_EASING,
        fill: "none",
      },
    );
    return () => {
      animation.cancel();
    };
    // The epoch pins the effect to one resort: a later resort with the same
    // dy re-runs, a re-render with the same state does not.
  }, [ref, dy, epoch]);
}

export function ChatList() {
  // The MERGED fleet snapshot: every paired engine's chats under scoped ids,
  // one flat list — the sidebar never knows which engine owns which row.
  const snapshot = useFleetSnapshot();
  const registry = useFleetRegistry();
  const fleet = useFleet();
  const sessions = useEngineSessions();
  const sidebar = useSidebar();
  const now = useNow(10_000);
  const navigate = useNavigate();
  // The ACTIVE engine's own device is the fleet's "local device" — the
  // group its chats land in under ByDevice, promoted to the top (scoped,
  // to match the projected rows' device ids).
  const localDeviceId = fleetLocalDeviceId(registry, fleet.active);
  const engineStates = engineStatesOf(registry);

  const chats = snapshot.chats;
  const filter = snapshot === null ? null : healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const visible =
    chats.error !== null
      ? []
      : filter === null
        ? chats.rows
        : chats.rows.filter((chat) => chat.spaceId !== undefined && chat.spaceId === filter);
  const changeRequests = useFleetChatChangeRequests(sessions, visible);

  // The device-group collapse keys — in-memory only, exactly like the
  // desktop's `sidebar_collapsed_groups` (a reload re-expands every group).
  const [collapsedGroups, setCollapsedGroups] = useState<ReadonlySet<string>>(() => new Set());

  const rows =
    chats.error === null && chats.loaded
      ? chatListRows(visible, snapshot.spaces.rows, snapshot.statuses.rows, now, snapshot.devices.rows, {
          sort: sidebar.sort,
          showHarness: sidebar.showHarness,
          showBranch: sidebar.showBranch,
          showPullRequest: sidebar.showPullRequest,
          changeRequests,
          engineStates,
          // A dangling spaceId hides its chat only once the spaces frame
          // is in; until then the row renders with the "?" label (ticket 43).
          spacesLoaded: snapshot.spaces.loaded,
        })
      : [];

  const groups = sidebarGroups(rows, sidebar.organization, localDeviceId);

  // ── The keyboard's sidebar half (ticket 12) ─────────────────────────────
  // The DISPLAYED order — `sidebar_visible_order`: what cycle, jump, and the
  // jump-hint chips all read, so keyboard order never drifts from the screen.
  const order = sidebarVisibleOrder(rows, sidebar.organization, localDeviceId);

  // The chips: while the hints are visible, the first nine rows carry the
  // slot's `badgeCombo` text in the corner — ticket 08's `.chat-row-jump`
  // class renders it, this module supplies the label from the same order the
  // jump shortcut targets.
  const hints = useJumpHints();
  const jumpSlotById: Map<string, number> | null = hints.visible
    ? new Map(visibleJumpOrder(order).map((id, slot) => [id, slot] as const))
    : null;
  const jumpLabelFor = (chatId: string): string | null => {
    const slot = jumpSlotById?.get(chatId);
    return slot === undefined ? null : (hints.combos[slot] ?? null);
  };

  // The session-nav shortcuts' execution half. The dispatcher in AppShell
  // holds the route and overlay guards; these handlers act on the live
  // order through a ref, so a snapshot tick never re-subscribes them.
  const routed = useEngineSession();
  const navRef = useRef({ order, session: routed });
  navRef.current = { order, session: routed };
  useEffect(() => {
    const selectedChatId = (): string | null => {
      const match = /^\/chat\/([^/]+)\/?$/.exec(window.location.pathname);
      if (match === null || match[1] === undefined) {
        return null;
      }
      try {
        return decodeURIComponent(match[1]);
      } catch {
        return match[1];
      }
    };
    const cycleTo = (forward: boolean): void => {
      const target = cycleTarget(navRef.current.order, selectedChatId(), forward);
      if (target !== null) {
        void navigate({ to: "/chat/$chatId", params: { chatId: target } });
      }
    };
    const offs = [
      onShortcut("next-session", () => cycleTo(true)),
      onShortcut("prev-session", () => cycleTo(false)),
      // `jump_to_session`: a slot past the end does nothing; the target takes
      // the same path a click on that row takes.
      onShortcut("jump-session", (detail) => {
        const id = navRef.current.order[detail.slot ?? -1];
        if (id !== undefined) {
          void navigate({ to: "/chat/$chatId", params: { chatId: id } });
        }
      }),
      // `archive_selected_chat` — the open chat moves to the archived shelf;
      // archiving never closes an open chat.
      onShortcut("archive-session", () => {
        const chatId = selectedChatId();
        const liveSession = navRef.current.session;
        if (chatId === null || liveSession === null) {
          return;
        }
        setChatArchived(liveSession.client, chatId, true).catch((error: unknown) => {
          sidebarNotice.set(describeMutateError(error));
        });
      }),
    ];
    return () => {
      for (const off of offs) {
        off();
      }
    };
  }, [navigate]);

  const keyed: SidebarKeyed[] = [];
  const sections: React.ReactNode[] = [];
  for (const bucket of groups) {
    if (bucket.group === null) {
      for (const row of bucket.rows) {
        keyed.push({
          key: `c:${row.chat.id}`,
          height: chatRowHeight(row.branch !== null, row.changeRequest !== null),
        });
        sections.push(<ChatListRow key={row.chat.id} row={row} jumpLabel={jumpLabelFor(row.chat.id)} />);
      }
      continue;
    }
    const collapseKey = `device:${bucket.group.deviceId}`;
    const collapsed = collapsedGroups.has(collapseKey);
    keyed.push({
      key: `g:${collapseKey}`,
      height:
        SIDEBAR_DISCLOSURE_SECTION_HEIGHT + (collapsed ? 0 : sidebarGroupBodyHeight(bucket.rows)),
    });
    sections.push(
      <DeviceGroupSection
        key={collapseKey}
        collapseKey={collapseKey}
        label={bucket.group.deviceName}
        rows={bucket.rows}
        collapsed={collapsed}
        jumpLabelFor={jumpLabelFor}
        onToggle={() => {
          setCollapsedGroups((current) => {
            const next = new Set(current);
            if (next.has(collapseKey)) {
              next.delete(collapseKey);
            } else {
              next.add(collapseKey);
            }
            return next;
          });
        }}
      />,
    );
  }

  // Hooks stay unconditional across the early returns below: an unconnected
  // first render must not register fewer hooks than the connected ones.
  const resort = useSidebarResort(keyed);

  if (fleet.engines.length === 0) {
    return <p className="sidebar-note">Pair an engine to see its chats.</p>;
  }
  if (chats.error !== null) {
    return <p className="sidebar-note sidebar-note-error">{chats.error.message}</p>;
  }
  if (!chats.loaded) {
    return <p className="sidebar-note">Loading chats…</p>;
  }
  if (rows.length === 0) {
    return (
      <p className="sidebar-empty">
        {filter === null ? "No chats yet." : "No chats in this space."}
      </p>
    );
  }

  // The offsets/newKeys arrive one commit after the new order — re-wrap the
  // already-keyed children with their glide/fade state before paint.
  const decorated = sections.map((child, index) => {
    const key = keyed[index]!.key;
    if (resort.newKeys.has(key)) {
      return (
        <div className="chat-row-in" key={key}>
          {child}
        </div>
      );
    }
    const dy = resort.offsets.get(key);
    if (dy !== undefined) {
      return (
        <ResortGlideBox key={key} dy={dy} epoch={resort.epoch}>
          {child}
        </ResortGlideBox>
      );
    }
    return child;
  });
  return <div className="chat-list">{decorated}</div>;
}

/** A keyed wrapper that glides a displaced child (row or whole section). */
function ResortGlideBox({
  dy,
  epoch,
  children,
}: {
  dy: number;
  epoch: number;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  useResortGlide(ref, dy, epoch);
  return (
    <div ref={ref} className="chat-row-resort">
      {children}
    </div>
  );
}

/** `spaces.rs::render_active_rows`' body height: inset + rows + gaps. */
function sidebarGroupBodyHeight(rows: readonly ChatRow[]): number {
  let total = SIDEBAR_DISCLOSURE_BODY_INSET;
  for (const row of rows) {
    total += chatRowHeight(row.branch !== null, row.changeRequest !== null);
  }
  total += SIDEBAR_LIST_GAP * Math.max(rows.length - 1, 0);
  return total;
}

/**
 * One ByDevice disclosure section (`spaces.rs` group arm): the shared header
 * (label + hairline + chevron), the tweened body, and the 12px section band
 * above it. Its keyed height for the FLIP diff is the collapsed/open pair
 * the parent computed.
 */
function DeviceGroupSection({
  collapseKey,
  label,
  rows,
  collapsed,
  jumpLabelFor,
  onToggle,
}: {
  collapseKey: string;
  label: string;
  rows: readonly ChatRow[];
  collapsed: boolean;
  jumpLabelFor: (chatId: string) => string | null;
  onToggle: () => void;
}) {
  const bodyHeight = sidebarGroupBodyHeight(rows);
  const { bodyRef, chevronRef, toggle } = useSidebarDisclosure(
    `group:${collapseKey}`,
    !collapsed,
    bodyHeight,
  );
  return (
    <section className="sidebar-group" id={`sidebar-group-${collapseKey}`}>
      <SidebarDisclosureHeader
        label={collapsed ? `${label} (${rows.length})` : label}
        open={!collapsed}
        chevronRef={chevronRef}
        onToggle={() => {
          // The motion begins on the CURRENT height before the flip — a
          // rapid double-click reverses from mid-flight, not from rest.
          toggle();
          onToggle();
        }}
      />
      <SidebarDisclosureBody bodyRef={bodyRef}>
        <div className="sidebar-group-rows">
          {rows.map((row) => (
            <ChatListRow key={row.chat.id} row={row} jumpLabel={jumpLabelFor(row.chat.id)} />
          ))}
        </div>
      </SidebarDisclosureBody>
    </section>
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
 *    relative time instead. A jump hint (the slot's `badgeCombo`, ticket 12)
 *    takes the corner outright above both.
 * 2. The harness brand mark (13px) beside the title at 13px/17px.
 * 3. Structural, not reserved: branch and change-request badge, omitted
 *    entirely when the chat has neither — the invisible spring keeps the
 *    badge pinned right without moving anything when absent.
 *
 * Hovering the ROW (not the corner — corner-only tested as undiscoverable)
 * swaps the corner for the Archive pill, whose padding bleeds into the row's
 * so its text right-aligns exactly where the status word sat: the swap moves
 * pixels around the label, not the label itself. Right mouse-down opens the
 * chat context menu at the pointer, exactly as the desktop does.
 */
function ChatListRow({ row, jumpLabel = null }: { row: ChatRow; jumpLabel?: string | null }) {
  // A row can live on ANY paired engine — resolve its owning session off
  // the scoped chat id so archive/menu mutations route to the right one.
  const sessions = useEngineSessions();
  const owning = owningSession(sessions, row.chat.id);
  const [hovered, setHovered] = useState(false);
  const word = statusWord(row.status);
  const archived = row.chat.archived;
  const brand = row.harness === null ? null : harnessBrandIcon(row.harness);
  const { menu, element } = useChatMenu(row.chat);

  function toggleArchive(event: React.MouseEvent): void {
    // The row's own click is the selector; only the corner archives.
    event.preventDefault();
    event.stopPropagation();
    if (owning === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    setChatArchived(owning.client, row.chat.id, !archived).catch((error: unknown) => {
      sidebarNotice.set(describeMutateError(error));
    });
  }

  // `menu` wraps the row so a right-click opens the chat context menu at
  // the pointer (`useChatMenu`'s ContextMenu.Trigger adopts this div). The
  // dialogs live outside it — they portal anyway, and their state must
  // outlive the menu's unmount.
  const rowElement = menu(
    <div className="chat-row-item" onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
      <Link
        to="/chat/$chatId"
        params={{ chatId: row.chat.id }}
        className="chat-row"
        data-status={row.status}
        activeProps={{ className: "chat-row chat-row-active" }}
      >
        <div className="chat-row-line">
          <SidebarFadedLabel className="chat-row-folder" fill>
            {row.folder}
          </SidebarFadedLabel>
          <span className="chat-row-corner">
            {jumpLabel !== null ? (
              <span className="chat-row-jump mono">{jumpLabel}</span>
            ) : hovered ? (
              <button
                type="button"
                className="chat-row-archive"
                aria-label={archived ? "Unarchive chat" : "Archive chat"}
                onClick={toggleArchive}
              >
                <Icon name={archived ? "archiveUpMinimalistic" : "archiveMinimalistic"} size={11} />
                {archived ? "Unarchive" : "Archive"}
              </button>
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
              size={SIDEBAR_ACTIVE_HARNESS_ICON_SIZE}
              className="chat-row-brand"
              style={brand.tint === null ? undefined : { color: brand.tint }}
            />
          )}
          <SidebarFadedLabel className="chat-row-title" fill>
            {row.chat.title ?? "New session"}
          </SidebarFadedLabel>
        </div>
        {(row.branch !== null || row.changeRequest !== null) && (
          <div className="chat-row-meta">
            {row.branch !== null && (
              <>
                <Icon name="gitBranch" size={11} />
                <SidebarFadedLabel className="chat-row-branch">{row.branch}</SidebarFadedLabel>
              </>
            )}
            <span className="chat-row-meta-spring" />
            {row.changeRequest !== null && (
              <span
                className="chat-row-pr"
                onClick={(event) => {
                  // The badge's own anchor owns the click; the row's Link
                  // must not also navigate.
                  event.stopPropagation();
                }}
              >
                <ChangeRequestBadge summary={row.changeRequest} size="sidebar" />
              </span>
            )}
          </div>
        )}
      </Link>
    </div>,
  );
  return (
    <>
      {rowElement}
      {element}
    </>
  );
}

/**
 * The session that owns a (scoped) chat id — the row-level router for
 * sidebar mutations. Unscoped ids resolve to null (nothing to route to).
 */
function owningSession(
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
