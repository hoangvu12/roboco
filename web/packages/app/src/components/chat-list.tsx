import { Fragment, useEffect, useLayoutEffect, useRef, useState } from "react";
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
import { PinnedSection } from "./pinned-section";
import {
  commitSessionDrop,
  commitVisiblePinReorder,
  pinOrderedRows,
  pinnedHeaderKeyedHeight,
  pinnedSessionClampedIndex,
  pinnedSessionDropIndex,
  sidebarPinProfileKey,
  SIDEBAR_PINNED_DIVIDER_HEIGHT,
  SIDEBAR_PINNED_DIVIDER_KEY,
  SIDEBAR_PINNED_HEADER_KEY,
} from "../lib/sidebar-pins";
import { sidebarStore } from "../state/sidebar";
import { GlyphSpinner } from "./glyph-spinner";
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
function useSidebarResort(keyed: readonly SidebarKeyed[], resetEpoch = 0): ResortState {
  const prev = useRef<readonly SidebarKeyed[]>([]);
  const prevReset = useRef(resetEpoch);
  const [state, setState] = useState<ResortState>(RESORT_NONE);
  useLayoutEffect(() => {
    const old = prev.current;
    prev.current = keyed;
    if (prevReset.current !== resetEpoch) {
      // A pin-drag commit placed the rows visually already — adopt the new
      // order without a glide (`commit_pinned_session_drag` clears the
      // desktop's resort bookkeeping the same way).
      prevReset.current = resetEpoch;
      return;
    }
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
  }, [keyed, resetEpoch]);
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
  // Bumped by a pin-drag commit: the drop leaves every row at its final slot,
  // so the FLIP diff adopts the new order without gliding it.
  const [pinResetEpoch, setPinResetEpoch] = useState(0);

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

  // Pins are bucketed per workspace profile (settings.rs's
  // `sidebar_pinned_session_ids_by_profile`) — one bucket per registry engine
  // in pairing order; roboco engines are all local-scoped in practice, so
  // this is usually the one shared "local" bucket.
  const pinProfileKeys: string[] = [];
  for (const engine of registry.engines) {
    const key = sidebarPinProfileKey(engine.info?.workspaceScope ?? null, engine.info?.deviceId ?? null);
    if (key !== null && !pinProfileKeys.includes(key)) {
      pinProfileKeys.push(key);
    }
  }
  const pinnedIds = pinProfileKeys.flatMap((key) => sidebar.pinnedByProfile[key] ?? []);

  // `retain_known_pins` on the desktop's synced-chats tick, per ACTIVE
  // profile: another profile's absent chats are not deletions, and an engine
  // that has not loaded yet never judges its own pins.
  useEffect(() => {
    if (!chats.loaded || chats.error !== null) {
      return;
    }
    const keys = registry.engines
      .filter((engine) => engine.chats.loaded)
      .map((engine) => sidebarPinProfileKey(engine.info?.workspaceScope ?? null, engine.info?.deviceId ?? null))
      .filter((key): key is string => key !== null);
    sidebarStore.pruneUnknownPins(keys, new Set(chats.rows.map((chat) => chat.id)));
  }, [chats.loaded, chats.error, chats.rows, registry]);

  // The pinned section leads; regular rows keep the existing grouping.
  const { pinned: pinnedRows, regular: regularRows } = pinOrderedRows(rows, pinnedIds);
  const hasPinnedDivider = pinnedRows.length > 0 && regularRows.length > 0;
  const pinnedOpen = sidebar.pinnedOpen;
  const groups = sidebarGroups(regularRows, sidebar.organization, localDeviceId);

  // ── Drag transfers between Pinned and regular sessions (6851fc34) ───────
  // A regular row's press arms a transfer: dragging over the pinned section
  // highlights it (the desktop's `drag_over` wash), and a release inside
  // pins the chat at the drop index (`finish_sidebar_session_transfer`'s
  // `SidebarSessionDrop::Pinned`; a closed section opens on success).
  // Releasing anywhere else is a no-op — regular rows never acquire a manual
  // order — and the FLIP resort glide carries the row into the section on
  // commit. The pinned-section side of the gesture (dragging OUT) lives in
  // PinnedSection's `onTransferOut`.
  const [transferIn, setTransferIn] = useState<{ readonly chatId: string; readonly overPinned: boolean } | null>(null);
  const pinnedSectionRef = useRef<HTMLElement | null>(null);
  // A completed transfer drag suppresses the click its pointerup would fire.
  const suppressRowClickRef = useRef(false);
  const sidebarRef = useRef<HTMLDivElement | null>(null);
  const bucketsRef = useRef<{ keys: string[]; byProfile: Readonly<Record<string, readonly string[]>>; open: boolean }>({ keys: [], byProfile: {}, open: true });
  bucketsRef.current = {
    keys: pinProfileKeys,
    byProfile: sidebar.pinnedByProfile,
    open: pinnedOpen,
  };
  const visiblePinsRef = useRef<string[]>([]);
  visiblePinsRef.current = pinnedRows.map((row) => row.chat.id);
  // The pinned section's root bounds + the rows group's bounds (the drop
  // index math reads them off the live DOM, like the desktop's prepaint
  // row_centers).
  const pinnedDropIndex = (pointer: { clientX: number; clientY: number }): number | null => {
    const section = pinnedSectionRef.current;
    if (section === null) {
      return null;
    }
    const sectionBounds = section.getBoundingClientRect();
    if (
      pointer.clientX < sectionBounds.left ||
      pointer.clientX > sectionBounds.right ||
      pointer.clientY < sectionBounds.top ||
      pointer.clientY > sectionBounds.bottom
    ) {
      return null;
    }
    const group = section.querySelector<HTMLElement>('[data-testid="sidebar-pinned-sessions"]');
    if (group !== null) {
      const groupBounds = group.getBoundingClientRect();
      if (pointer.clientY >= groupBounds.top && pointer.clientY <= groupBounds.bottom) {
        const count = visiblePinsRef.current.length;
        if (count === 0) {
          return 0;
        }
        const relY = pointer.clientY - groupBounds.top;
        return pinnedSessionDropIndex(relY, count) ?? pinnedSessionClampedIndex(relY, count) ?? 0;
      }
    }
    // The header (or a collapsed body) pins at the top.
    return 0;
  };
  const setPinnedDrop = (over: boolean): void => {
    setTransferIn((current) => {
      if (current === null) {
        return current;
      }
      return current.overPinned === over ? current : { ...current, overPinned: over };
    });
  };
  const finishTransferIn = (chatId: string, pointer: { clientX: number; clientY: number }): void => {
    const index = pinnedDropIndex(pointer);
    if (index === null) {
      // Not over the pinned section: a regular row never reorders — the
      // drop is a no-op and the row stays at its activity position.
      return;
    }
    const { keys, byProfile, open } = bucketsRef.current;
    const buckets: Record<string, readonly string[]> = {};
    for (const key of keys) {
      buckets[key] = byProfile[key] ?? [];
    }
    sidebarStore.replacePinsByProfile(
      commitSessionDrop(buckets, visiblePinsRef.current, chatId, { kind: "pinned", index }),
    );
    if (!open) {
      sidebarStore.setPinnedOpen(true);
    }
  };
  /** A pointer must travel this far before the press reads as a drag. */
  const DRAG_ARM_PX = 4;
  const armTransferIn = (event: React.PointerEvent, chatId: string): void => {
    if (event.button !== 0) {
      return;
    }
    // Interactive corners (the Archive pill) own their press.
    if ((event.target as HTMLElement).closest("button") !== null) {
      return;
    }
    // Pinned rows carry their own gesture (PinnedSection's reorder/transfer).
    if ((event.target as HTMLElement).closest('[data-testid="sidebar-pinned-sessions"]') !== null) {
      return;
    }
    const startX = event.clientX;
    const startY = event.clientY;
    let moved = false;
    let overPinned = false;
    const onMove = (move: PointerEvent): void => {
      // `contain_pinned_session_drag`: leaving the sidebar's column cancels.
      const sidebar = sidebarRef.current;
      if (sidebar !== null) {
        const bounds = sidebar.getBoundingClientRect();
        if (move.clientX < bounds.left || move.clientX > bounds.right) {
          cancel();
          return;
        }
      }
      if (!moved) {
        if (Math.abs(move.clientX - startX) <= DRAG_ARM_PX && Math.abs(move.clientY - startY) <= DRAG_ARM_PX) {
          return;
        }
        moved = true;
        overPinned = pinnedDropIndex(move) !== null;
        setTransferIn({ chatId, overPinned });
        return;
      }
      const next = pinnedDropIndex(move) !== null;
      if (next !== overPinned) {
        overPinned = next;
        setPinnedDrop(next);
      }
    };
    const teardown = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("keydown", onKey, true);
    };
    const finish = (up: PointerEvent): void => {
      teardown();
      setTransferIn(null);
      if (moved) {
        finishTransferIn(chatId, up);
        // The pointerup lands as a click on the row's link — swallow it.
        suppressRowClickRef.current = true;
        window.setTimeout(() => {
          suppressRowClickRef.current = false;
        }, 0);
      }
    };
    const cancel = (): void => {
      teardown();
      setTransferIn(null);
    };
    const onKey = (key: KeyboardEvent): void => {
      if (key.key === "Escape") {
        key.preventDefault();
        key.stopPropagation();
        cancel();
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("keydown", onKey, true);
  };
  const suppressTransferClick = (): boolean => suppressRowClickRef.current;

  // ── The keyboard's sidebar half (ticket 12) ─────────────────────────────
  // The DISPLAYED order — `sidebar_visible_order`: what cycle, jump, and the
  // jump-hint chips all read, so keyboard order never drifts from the screen.
  // A collapsed pinned section hides its rows, so they hold no slot here.
  const order = sidebarVisibleOrder(
    rows,
    sidebar.organization,
    localDeviceId,
    pinnedIds,
    pinnedOpen,
  );

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
  const entries: { key: string; element: React.ReactNode }[] = [];
  // The pinned section leads (`render_active_rows`'s pin split). The FLIP
  // diff's order vec carries the section's PHANTOM entries — the disclosure
  // header (28px + the open body's inset) and the divider — which hold no
  // element of their own; only the pinned ROWS carry elements so the resort
  // glide still reaches them. Collapsed, the pinned rows hold no slot at
  // all (the desktop's `if ix < pinned_count && !self.pinned_open` skip).
  if (pinnedRows.length > 0) {
    keyed.push({ key: SIDEBAR_PINNED_HEADER_KEY, height: pinnedHeaderKeyedHeight(pinnedOpen) });
  }
  for (const row of pinnedRows) {
    const key = `c:${row.chat.id}`;
    keyed.push({
      key,
      height: chatRowHeight(row.branch !== null, row.changeRequest !== null),
    });
    entries.push({
      key,
      element: <ChatListRow key={row.chat.id} row={row} jumpLabel={jumpLabelFor(row.chat.id)} />,
    });
  }
  if (hasPinnedDivider && pinnedOpen) {
    keyed.push({ key: SIDEBAR_PINNED_DIVIDER_KEY, height: SIDEBAR_PINNED_DIVIDER_HEIGHT });
  }
  for (const bucket of groups) {
    if (bucket.group === null) {
      for (const row of bucket.rows) {
        const key = `c:${row.chat.id}`;
        keyed.push({
          key,
          height: chatRowHeight(row.branch !== null, row.changeRequest !== null),
        });
        entries.push({
          key,
          element: (
            <RegularRowDragArm
              key={row.chat.id}
              chatId={row.chat.id}
              onArm={armTransferIn}
              dragged={transferIn?.chatId === row.chat.id}
              shouldSuppressClick={suppressTransferClick}
            >
              <ChatListRow row={row} jumpLabel={jumpLabelFor(row.chat.id)} />
            </RegularRowDragArm>
          ),
        });
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
    entries.push({
      key: `g:${collapseKey}`,
      element: (
        <DeviceGroupSection
          key={collapseKey}
          collapseKey={collapseKey}
          label={bucket.group.deviceName}
          rows={bucket.rows}
          collapsed={collapsed}
          jumpLabelFor={jumpLabelFor}
          onRowPointerDown={armTransferIn}
          draggingChatId={transferIn?.chatId ?? null}
          shouldSuppressClick={suppressTransferClick}
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
        />
      ),
    });
  }

  // Hooks stay unconditional across the early returns below: an unconnected
  // first render must not register fewer hooks than the connected ones.
  const resort = useSidebarResort(keyed, pinResetEpoch);

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
  // already-keyed children with their glide/fade state before paint. Only
  // element-bearing entries decorate (the FLIP order vec's phantom entries
  // — the pinned header and divider — contribute heights, not elements).
  const decorated = entries.map(({ key, element }) => {
    if (resort.newKeys.has(key)) {
      return (
        <div className="chat-row-in" key={key}>
          {element}
        </div>
      );
    }
    const dy = resort.offsets.get(key);
    if (dy !== undefined) {
      return (
        <ResortGlideBox key={key} dy={dy} epoch={resort.epoch}>
          {element}
        </ResortGlideBox>
      );
    }
    return <Fragment key={key}>{element}</Fragment>;
  });
  // The decorated list splits back into the pinned section (its own drag
  // container and disclosure) and the regular sections.
  const pinnedItems = decorated.slice(0, pinnedRows.length);
  const regularItems = decorated.slice(pinnedRows.length);
  const pinBuckets = (): Record<string, readonly string[]> => {
    const buckets: Record<string, readonly string[]> = {};
    for (const key of pinProfileKeys) {
      buckets[key] = sidebar.pinnedByProfile[key] ?? [];
    }
    return buckets;
  };
  const visiblePinIds = pinnedRows.map((row) => row.chat.id);
  return (
    <div className="chat-list" ref={sidebarRef}>
      {pinnedRows.length > 0 && (
        <PinnedSection
          rows={pinnedRows}
          items={pinnedItems}
          open={pinnedOpen}
          hasDivider={hasPinnedDivider}
          dragOverPinned={transferIn?.overPinned ?? false}
          sectionRef={pinnedSectionRef}
          onToggle={() => {
            // The disclosure owns this movement: adopt the new order without
            // a second (FLIP) glide of it — the desktop's header click
            // clears its resort bookkeeping the same way.
            setPinResetEpoch((epoch) => epoch + 1);
            sidebarStore.setPinnedOpen(!pinnedOpen);
          }}
          onCommit={(from, to) => {
            // `commit_pinned_session_drag` over the profile buckets: reorder
            // the visible projection, settle every id back into its own
            // bucket (`commitVisiblePinReorder`). The rows are already
            // visually in place, so the FLIP diff adopts without gliding.
            sidebarStore.replacePinsByProfile(
              commitVisiblePinReorder(pinBuckets(), visiblePinIds, from, to),
            );
            setPinResetEpoch((epoch) => epoch + 1);
          }}
          onTransferOut={(chatId) => {
            // `finish_sidebar_session_transfer` with `SidebarSessionDrop::Regular`:
            // only the pin membership changes — the FLIP resort glide carries
            // the row to its live activity position (the transfer animation).
            sidebarStore.replacePinsByProfile(
              commitSessionDrop(pinBuckets(), visiblePinIds, chatId, { kind: "regular" }),
            );
          }}
        />
      )}
      {regularItems}
      {pinnedRows.length > 0 && regularRows.length === 0 && transferIn !== null && (
        <div className="sidebar-drop-unpin">Drop here to unpin</div>
      )}
    </div>
  );
}

/**
 * A regular row's drag arm: the pointer-press starts a sidebar session
 * transfer (6851fc34). The wrapper stays layout-neutral (a plain div) — the
 * parent's gesture runs at the window level, and the row's click is
 * suppressed after a completed drag.
 */
function RegularRowDragArm({
  chatId,
  onArm,
  dragged,
  shouldSuppressClick,
  children,
}: {
  chatId: string;
  onArm: (event: React.PointerEvent, chatId: string) => void;
  dragged: boolean;
  shouldSuppressClick: () => boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      className="regular-row"
      data-sidebar-dragging={dragged ? "1" : undefined}
      onPointerDown={(event) => onArm(event, chatId)}
      onClickCapture={(event) => {
        if (shouldSuppressClick()) {
          event.preventDefault();
          event.stopPropagation();
        }
      }}
    >
      {children}
    </div>
  );
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
  onRowPointerDown,
  draggingChatId,
  shouldSuppressClick,
  onToggle,
}: {
  collapseKey: string;
  label: string;
  rows: readonly ChatRow[];
  collapsed: boolean;
  jumpLabelFor: (chatId: string) => string | null;
  /** The parent's transfer-in gesture arm (one per regular row). */
  onRowPointerDown: (event: React.PointerEvent, chatId: string) => void;
  draggingChatId: string | null;
  shouldSuppressClick: () => boolean;
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
            <RegularRowDragArm
              key={row.chat.id}
              chatId={row.chat.id}
              onArm={onRowPointerDown}
              dragged={draggingChatId === row.chat.id}
              shouldSuppressClick={shouldSuppressClick}
            >
              <ChatListRow row={row} jumpLabel={jumpLabelFor(row.chat.id)} />
            </RegularRowDragArm>
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
          <span className="chat-row-folder">{row.folder}</span>
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
          <span className="chat-row-title">{row.chat.title ?? "New session"}</span>
        </div>
        {(row.branch !== null || row.changeRequest !== null) && (
          <div className="chat-row-meta">
            {row.branch !== null && (
              <>
                <Icon name="gitBranch" size={11} />
                <span className="chat-row-branch">{row.branch}</span>
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
