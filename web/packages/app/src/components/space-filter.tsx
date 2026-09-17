import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Icon, type IconName } from "@roboco/icons";
import type { Device, Space } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { uiSettings } from "../state/ui-settings";
import { deviceOnline, healedSpaceFilter, mergePendingSpaces, spaceDisplayName, spacesSorted } from "../lib/view";
import { classifyKey, filterIndices, menuStep } from "../lib/picker-search";
import { anchorBelow, anchorBelowEnd, menuAt } from "../lib/popover-anchor";
import { addSpaceStore, usePendingSpaces } from "../state/add-space";
import { sidebarNotice } from "../state/notice";
import { RbDialog } from "./base/dialog";
import {
  PopoverCard,
  SearchInputFrame,
  DialogCard,
  DialogTitle,
  DialogBody,
  DialogField,
  BtnGhost,
  BtnPrimary,
  BtnDanger,
} from "./popover/menu";
import { MenuRowNav } from "./popover/menu-row";
import { POPUP_TRIGGER_ATTR, Popup, usePopup } from "./popover/popup";

/**
 * The sidebar's space header — the desktop's `render_spaces_filter` row:
 * a 29px disclosure trigger reading "All projects" (or the picked space)
 * with a folder mark, an "@ device" tag hugging the name, an offline glyph
 * when the space's host is stale, and a quiet caret (static — no rotation).
 * The view-options button beside it opens `SidebarViewMenu`.
 *
 * The trigger opens `render_spaces_menu` (spaces.rs:1187-1323) on the shared
 * popover primitive: a search field, "All projects" (only while the query is
 * empty), every space ranked by `filterIndices` with its "@ device" tag and
 * offline glyph, and "New project…" always last. Right-clicking a space row
 * opens `SpaceContextMenu` at the pointer (rename/delete).
 *
 * The picked space both filters the chat list and targets the new-chat flow;
 * a dangling pick — space deleted, or an engine switch — heals to "All
 * projects" at read (`shell.rs`), so the header never shows a name the fleet
 * no longer has.
 */

/** `SPACES_MENU_LIST_MAX_HEIGHT` (spaces.rs) — the menu list's cap. */
const SPACES_MENU_LIST_MAX_HEIGHT = 336;
/** The 350ms tooltip show-delay on the view-options button (spaces.rs:960). */
const VIEW_OPTIONS_TOOLTIP_MS = 350;

export function SpaceFilter() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();
  const now = useNow(30_000);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"spaces">();
  // The right-click overlay: the context menu first, then whichever dialog
  // its rows open — the dialog state must outlive the menu's unmount.
  const [spaceOverlay, setSpaceOverlay] = useState<SpaceOverlay | null>(null);

  // The menu's rows: the watch cache's spaces with the add-space palette's
  // optimistic rows folded in, merged by id (a confirmed row replaces its
  // optimistic twin), in display order.
  const pending = usePendingSpaces();
  const spaces = useMemo(() => {
    const rows = snapshot === null ? [] : snapshot.spaces.rows;
    return spacesSorted(mergePendingSpaces(rows, pending));
  }, [snapshot?.spaces.rows, pending]);
  const devices = snapshot?.devices.rows ?? [];
  const filter = snapshot === null ? null : healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const picked = filter === null ? null : spaces.find((space) => space.id === filter) ?? null;
  const label = picked === null ? "All projects" : spaceDisplayName(picked);
  // The "@ device" tag rides the trigger only under a picked space, with
  // the disconnected GLYPH — never words — when its host reads offline.
  const deviceTag = picked === null ? null : spaceDeviceTag(picked, devices, now);

  const listRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);

  // Row order: "All projects" (empty query only), spaces ranked by
  // `filterIndices` over the display name, "New project…" always last.
  const labels = useMemo(() => spaces.map((space) => spaceDisplayName(space)), [spaces]);
  const matched = useMemo(() => {
    const indices = filterIndices(query, labels);
    return indices.map((ix) => spaces[ix]!);
  }, [query, labels, spaces]);
  const rows: readonly (Space | "all" | "new")[] = useMemo(() => {
    const list: (Space | "all" | "new")[] = [];
    if (query.trim().length === 0) {
      list.push("all");
    }
    list.push(...matched);
    list.push("new");
    return list;
  }, [query, matched]);

  // Opening: mints a fresh search input; anchors the cursor on the row
  // matching the current filter (spaces.rs:1200-1210).
  const opened = popup.isOpen();
  useEffect(() => {
    if (!opened) {
      return;
    }
    setQuery("");
    const target =
      filter === null ? 0 : rows.findIndex((row) => row !== "all" && row !== "new" && row.id === filter);
    setCursor(target < 0 ? 0 : target);
    // The search input takes focus before first paint (spaces.rs:1207).
    inputRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  function pick(row: Space | "all" | "new"): void {
    if (row === "all") {
      sidebarStore.setSpaceFilter(null);
      popup.dismiss();
      return;
    }
    if (row === "new") {
      // "New project…" closes the menu, THEN opens the add-space palette
      // (spaces.rs:1204-1207 / §2.7 — `close_space_menu` always runs before
      // the overlay opens; ticket 11's `addSpaceStore` owns the surface).
      popup.dismiss();
      addSpaceStore.open();
      return;
    }
    sidebarStore.setSpaceFilter(row.id);
    popup.dismiss();
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    if (key === "down" || key === "up") {
      event.preventDefault();
      setCursor((current) => menuStep(current, rows.length, key === "down" ? 1 : -1) ?? 0);
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      const row = rows[cursor];
      if (row !== undefined) {
        pick(row);
      }
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  // Scroll the highlighted row into view as the cursor moves.
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>(`[data-space-index="${cursor}"]`);
    row?.scrollIntoView({ block: "nearest" });
  }, [cursor, rows.length]);

  // The card spans the trigger row's content width — `sidebarWidth - 16`
  // (two SPACE_SM gutters) — and opens 6px below it, clamped 8px inside.
  const placeBelowTrigger = (size: { width: number; height: number }): CSSProperties => {
    const rect = triggerRef.current?.getBoundingClientRect();
    if (rect === undefined) {
      return { left: 8, top: 8 };
    }
    return anchorBelow(rect, size);
  };

  // The trigger renders unconditionally, empty engine included (shell.rs:4935
  // gates `render_spaces_filter` on nothing): with zero spaces the label falls
  // back to "All projects" and the menu degenerates to ["All projects",
  // "New project…"], exactly the desktop's empty-engine rows.
  if (snapshot === null || !snapshot.spaces.loaded) {
    return null;
  }

  return (
    <>
      <button
        type="button"
        ref={triggerRef}
        {...{ [POPUP_TRIGGER_ATTR]: "" }}
        className={`space-filter-trigger ${popup.get() !== null ? "space-filter-trigger-open" : ""}`}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("spaces");
        }}
        aria-haspopup="listbox"
        aria-expanded={popup.get() !== null}
      >
        <Icon name="folder" size={16} className="space-filter-icon" />
        <span className="space-filter-label">
          <span className="space-filter-name">{label}</span>
          {deviceTag !== null && (
            <>
              <span className="space-filter-tag">{deviceTag.tag}</span>
              {!deviceTag.online && <Icon name="wifiOff" size={12} className="space-filter-offline" />}
            </>
          )}
        </span>
        <Icon name="altArrowDown" size={14} className="space-filter-caret" />
      </button>
      <Popup popup={popup} placement={placeBelowTrigger}>
        {() => (
          <PopoverCard
            role="listbox"
            aria-label="Projects"
            className="spaces-menu-card"
            style={{ width: rowContentWidth(triggerRef.current) }}
            onKeyDown={onKeyDown}
          >
            <SearchInputFrame>
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setCursor(0);
                }}
                placeholder="Search projects…"
                spellCheck={false}
                autoComplete="off"
                aria-label="Search projects"
              />
            </SearchInputFrame>
            <div className="spaces-menu-list" id="spaces-menu-list" ref={listRef}>
              {rows.map((row, ix) => {
                if (row === "all") {
                  return (
                    <MenuRowNav
                      key="all"
                      fadeKey="all"
                      data-space-index={ix}
                      highlighted={ix === cursor && filter !== null}
                      selected={filter === null}
                      onClick={() => pick(row)}
                    >
                      <Icon name="folder" size={15} className="spaces-menu-row-icon" />
                      <span className="menu-row-label">All projects</span>
                    </MenuRowNav>
                  );
                }
                if (row === "new") {
                  return (
                    <MenuRowNav key="new" fadeKey="new" data-space-index={ix} onClick={() => pick(row)}>
                      <Icon name="plus" size={15} className="spaces-menu-row-icon" />
                      <span className="menu-row-label">New project…</span>
                    </MenuRowNav>
                  );
                }
                const tag = spaceDeviceTag(row, devices, now);
                return (
                  <MenuRowNav
                    key={row.id}
                    fadeKey={row.id}
                    data-space-index={ix}
                    highlighted={ix === cursor && row.id !== filter}
                    selected={row.id === filter}
                    onClick={() => pick(row)}
                    onContextMenu={(event) => {
                      // Right-click on a space row opens the space context
                      // menu at the pointer (spaces.rs:3336-3389).
                      event.preventDefault();
                      event.stopPropagation();
                      setSpaceOverlay({ kind: "menu", space: row, x: event.clientX, y: event.clientY });
                    }}
                  >
                    <Icon name="folder" size={15} className="spaces-menu-row-icon" />
                    <span className="menu-row-label">{spaceDisplayName(row)}</span>
                    <span className="picker-row-tag">{tag.tag}</span>
                    {!tag.online && <Icon name="wifiOff" size={12} className="picker-row-offline" />}
                  </MenuRowNav>
                );
              })}
            </div>
          </PopoverCard>
        )}
      </Popup>
      {spaceOverlay !== null && session !== null && (
        <>
          {spaceOverlay.kind === "menu" && (
            <SpaceContextMenu
              space={spaceOverlay.space}
              point={spaceOverlay}
              onClose={() => setSpaceOverlay(null)}
              onOpenDialog={(kind) =>
                setSpaceOverlay(
                  kind === "rename"
                    ? { kind: "rename", space: spaceOverlay.space }
                    : { kind: "delete", space: spaceOverlay.space },
                )
              }
            />
          )}
          {spaceOverlay.kind === "rename" && (
            <RenameSpaceDialog
              space={spaceOverlay.space}
              onCancel={() => setSpaceOverlay(null)}
              onSubmit={(value) => {
                runSpaceMutate(session, "renameSpace", { spaceId: spaceOverlay.space.id, name: value });
                setSpaceOverlay(null);
              }}
            />
          )}
          {spaceOverlay.kind === "delete" && (
            <DeleteSpaceDialog
              space={spaceOverlay.space}
              deviceName={devices.find((device) => device.id === spaceOverlay.space.deviceId)?.name ?? "its device"}
              chatCount={
                snapshot?.chats.rows.filter((chat) => chat.spaceId === spaceOverlay.space.id).length ?? 0
              }
              onCancel={() => setSpaceOverlay(null)}
              onConfirm={() => {
                runSpaceMutate(session, "deleteSpace", { spaceId: spaceOverlay.space.id });
                setSpaceOverlay(null);
              }}
            />
          )}
        </>
      )}
    </>
  );
}

/** The right-click overlay states: the context menu, then its dialogs. */
type SpaceOverlay =
  | { readonly kind: "menu"; readonly space: Space; readonly x: number; readonly y: number }
  | { readonly kind: "rename"; readonly space: Space }
  | { readonly kind: "delete"; readonly space: Space };

/** The Mutate call for a space mutation, with the notice on failure. */
function runSpaceMutate(
  session: NonNullable<ReturnType<typeof useEngineSession>>,
  op: "renameSpace" | "deleteSpace",
  params: Record<string, unknown>,
): void {
  void session.client
    .call(methods.MUTATE, { op, ...params })
    .catch((error: unknown) => sidebarNotice.set(error instanceof Error ? error.message : String(error)));
}

/**
 * The picked space's host tag (`state.rs::space_device_tag`):
 * `"@ {device}"` plus its presence, a missing device row reading online.
 */
function spaceDeviceTag(
  space: { deviceId: string },
  devices: readonly Device[],
  now: number,
): { tag: string; online: boolean } {
  const device = devices.find((row) => row.id === space.deviceId);
  return {
    tag: `@ ${device?.name ?? "Unknown device"}`,
    online: deviceOnline(device, now),
  };
}

/**
 * The sidebar row's content width — `sidebarWidth - 16`, what the desktop's
 * space menu card spans (two SPACE_SM gutters on either side of the row).
 */
function rowContentWidth(rowChild: HTMLElement | null): number {
  const parent = rowChild?.parentElement;
  if (parent === null || parent === undefined) {
    return 304;
  }
  return Math.max(200, parent.clientWidth - 16);
}

// ---------------------------------------------------------------------------
// SidebarViewMenu (spaces.rs:871-973) — the sort button's Organize/Sort/Show card
// ---------------------------------------------------------------------------

type ViewRow =
  | { readonly kind: "ByDevice" }
  | { readonly kind: "InOneList" }
  | { readonly kind: "LastUpdated" }
  | { readonly kind: "Created" }
  | { readonly kind: "ShowBranch" }
  | { readonly kind: "ShowPullRequest" }
  | { readonly kind: "ShowHarness" };

/** `SIDEBAR_VIEW_ROWS` — the exact row order and labels (spaces.rs:871-973). */
const SIDEBAR_VIEW_ROWS: readonly { row: ViewRow; label: string; icon: IconName }[] = [
  { row: { kind: "ByDevice" }, label: "By device", icon: "laptop" },
  { row: { kind: "InOneList" }, label: "In one list", icon: "list" },
  { row: { kind: "LastUpdated" }, label: "Last updated", icon: "clockCircle" },
  { row: { kind: "Created" }, label: "Created", icon: "calendar" },
  { row: { kind: "ShowBranch" }, label: "Branch", icon: "gitBranch" },
  { row: { kind: "ShowPullRequest" }, label: "Pull request", icon: "pullRequest" },
  { row: { kind: "ShowHarness" }, label: "Harness", icon: "bot" },
];

export function SidebarViewMenu() {
  const sidebar = useSidebar();
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const popup = usePopup<"view">();
  const [tooltip, setTooltip] = useState(false);
  const [cursor, setCursor] = useState<number | null>(null);

  // The 350ms show delay — the lone tooltip in the popover family
  // (spaces.rs:960-966); hovering shorter than that shows nothing.
  const showTooltip = useCallback(() => {
    const timer = setTimeout(() => setTooltip(true), VIEW_OPTIONS_TOOLTIP_MS);
    return () => {
      clearTimeout(timer);
      setTooltip(false);
    };
  }, []);

  function isSelected(row: ViewRow): boolean {
    switch (row.kind) {
      case "ByDevice":
        return sidebar.organization === "byDevice";
      case "InOneList":
        return sidebar.organization === "inOneList";
      case "LastUpdated":
        return sidebar.sort === "lastUpdated";
      case "Created":
        return sidebar.sort === "created";
      case "ShowBranch":
        return sidebar.showBranch;
      case "ShowPullRequest":
        return sidebar.showPullRequest;
      case "ShowHarness":
        return sidebar.showHarness;
    }
  }

  /** Radio rows (0-3) dismiss on pick; Show toggles (4-6) stay open. */
  function closes(row: ViewRow): boolean {
    return row.kind === "ByDevice" || row.kind === "InOneList" || row.kind === "LastUpdated" || row.kind === "Created";
  }

  function activate(row: ViewRow): void {
    // A mouse-driven pick clears the cursor so it doesn't linger
    // (spaces.rs:900-908).
    setCursor(null);
    switch (row.kind) {
      case "ByDevice":
        uiSettings.updateImmediate({ sidebarOrganization: "byDevice" });
        break;
      case "InOneList":
        uiSettings.updateImmediate({ sidebarOrganization: "inOneList" });
        break;
      case "LastUpdated":
        uiSettings.updateImmediate({ sidebarSort: "lastUpdated" });
        break;
      case "Created":
        uiSettings.updateImmediate({ sidebarSort: "created" });
        break;
      case "ShowBranch":
        uiSettings.updateImmediate({ sidebarShowBranch: !sidebar.showBranch });
        break;
      case "ShowPullRequest":
        // Also flips the change-requests-visible flag.
        uiSettings.updateImmediate({ sidebarShowPullRequest: !sidebar.showPullRequest });
        break;
      case "ShowHarness":
        uiSettings.updateImmediate({ sidebarShowHarness: !sidebar.showHarness });
        break;
    }
    if (closes(row)) {
      popup.dismiss();
    }
  }

  const onKeyDownCard = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    // The cursor is `Option<usize>` starting at None: the first Down lands
    // on 0, the first Up on 6.
    if (key === "down" || key === "up") {
      event.preventDefault();
      setCursor((current) => menuStep(current, SIDEBAR_VIEW_ROWS.length, key === "down" ? 1 : -1));
      return;
    }
    if (key === "enter" || key === "mod-enter") {
      event.preventDefault();
      if (cursor !== null) {
        const entry = SIDEBAR_VIEW_ROWS[cursor];
        if (entry !== undefined) {
          activate(entry.row);
        }
      }
      return;
    }
    if (key === "escape") {
      event.preventDefault();
      popup.closeByEscape();
    }
  };

  // `anchorBelowEnd` — right-aligned so the full-width card opens leftward
  // without leaving the sidebar.
  const placeBelowEnd = (size: { width: number; height: number }): CSSProperties => {
    const rect = buttonRef.current?.getBoundingClientRect();
    if (rect === undefined) {
      return { left: 8, top: 8 };
    }
    return anchorBelowEnd(rect, size);
  };

  const open = popup.get() !== null;
  return (
    <>
      <button
        type="button"
        ref={buttonRef}
        {...{ [POPUP_TRIGGER_ATTR]: "" }}
        className={`space-filter-sort ${open ? "space-filter-sort-open" : ""}`}
        role="button"
        aria-label="Sidebar view options"
        aria-expanded={open}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("view");
        }}
        onMouseEnter={showTooltip}
        onFocus={showTooltip}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            event.stopPropagation();
            if (popup.isOpen()) {
              popup.dismiss();
            } else {
              popup.open("view");
            }
            return;
          }
          // ArrowDown only opens, never closes.
          if (event.key === "ArrowDown" && !popup.isOpen()) {
            event.preventDefault();
            event.stopPropagation();
            popup.open("view");
          }
        }}
      >
        <Icon name="sort" size={16} />
        {tooltip && !open && (
          <span className="space-filter-sort-tooltip" role="tooltip">
            Sidebar view options
          </span>
        )}
      </button>
      <Popup popup={popup} placement={placeBelowEnd}>
        {() => (
          <PopoverCard
            role="menu"
            aria-label="Sidebar view options"
            className="spaces-menu-card"
            style={{ width: rowContentWidth(buttonRef.current) }}
            onKeyDown={onKeyDownCard}
          >
            <MenuHeadingRow label="Organize" />
            <ViewMenuRows entries={SIDEBAR_VIEW_ROWS.slice(0, 2)} offset={0} cursor={cursor} isSelected={isSelected} onActivate={activate} />
            <SeparatorRow />
            <MenuHeadingRow label="Sort" />
            <ViewMenuRows entries={SIDEBAR_VIEW_ROWS.slice(2, 4)} offset={2} cursor={cursor} isSelected={isSelected} onActivate={activate} />
            <SeparatorRow />
            <MenuHeadingRow label="Show" />
            <ViewMenuRows entries={SIDEBAR_VIEW_ROWS.slice(4, 7)} offset={4} cursor={cursor} isSelected={isSelected} onActivate={activate} />
          </PopoverCard>
        )}
      </Popup>
    </>
  );
}

function MenuHeadingRow({ label }: { label: string }) {
  return <div className="menu-heading">{label}</div>;
}

function SeparatorRow() {
  return <div className="menu-separator" role="separator" />;
}

function ViewMenuRows({
  entries,
  cursor,
  offset,
  isSelected,
  onActivate,
}: {
  readonly entries: readonly { row: ViewRow; label: string; icon: IconName }[];
  readonly cursor: number | null;
  readonly offset: number;
  readonly isSelected: (row: ViewRow) => boolean;
  readonly onActivate: (row: ViewRow) => void;
}) {
  return (
    <div className="view-menu-rows">
      {entries.map((entry, ix) => {
        const globalIx = ix + offset;
        return (
          <MenuRowNav
            key={entry.row.kind}
            fadeKey={entry.row.kind}
            highlighted={cursor === globalIx && !isSelected(entry.row)}
            selected={isSelected(entry.row)}
            onClick={() => onActivate(entry.row)}
          >
            <Icon name={entry.icon} size={15} className="spaces-menu-row-icon" />
            <span className="menu-row-label">{entry.label}</span>
            {/* The 14px check slot is always reserved so labels never shift. */}
            <span className="view-menu-check">{isSelected(entry.row) && <Icon name="check" size={14} />}</span>
          </MenuRowNav>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------
// SpaceContextMenu (spaces.rs:3336-3389) — rename/delete at the pointer
// ---------------------------------------------------------------------------

function SpaceContextMenu({
  space,
  point,
  onClose,
  onOpenDialog,
}: {
  readonly space: Space;
  readonly point: { x: number; y: number };
  readonly onClose: () => void;
  /** Opens the named dialog — the dialog state lives ABOVE this unmount. */
  readonly onOpenDialog: (kind: "rename" | "delete") => void;
}) {
  const popup = usePopup<"space-context">();

  // Open on mount; Escape and outside-press dismiss (the card is clamp-only
  // at the pointer — `menu_at`, no flip).
  useEffect(() => {
    popup.open("space-context");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Popup popup={popup} placement={(size) => menuAt(point, size)}>
      {() => (
        <PopoverCard role="menu" aria-label="Project actions" style={{ width: 170 }} onKeyDown={onKeyDownMenu(popup, onClose)}>
          <MenuRowNav
            fadeKey="space-menu-rename"
            onClick={() => {
              onClose();
              onOpenDialog("rename");
            }}
          >
            <Icon name="pen" size={16} className="spaces-menu-row-icon" />
            <span className="menu-row-label">Rename…</span>
          </MenuRowNav>
          <MenuRowNav
            fadeKey="space-menu-delete"
            className="chat-menu-row-danger"
            onClick={() => {
              onClose();
              onOpenDialog("delete");
            }}
          >
            <Icon name="trashBinMinimalistic" size={16} className="spaces-menu-row-icon-danger" />
            <span className="menu-row-label">Remove…</span>
          </MenuRowNav>
        </PopoverCard>
      )}
    </Popup>
  );
}

function onKeyDownMenu(
  popup: ReturnType<typeof usePopup<string>>,
  onClose: () => void,
): (event: React.KeyboardEvent<HTMLDivElement>) => void {
  return (event) => {
    if (popup.asOpen() === null) {
      return;
    }
    if (classifyKey(event.key, event.metaKey, event.ctrlKey) === "escape") {
      event.preventDefault();
      onClose();
      popup.closeByEscape();
    }
  };
}

/** The rename dialog (`open_rename_space` / `submit_rename_space`). */
function RenameSpaceDialog({
  space,
  onCancel,
  onSubmit,
}: {
  readonly space: Space;
  readonly onCancel: () => void;
  readonly onSubmit: (name: string) => void;
}) {
  const [value, setValue] = useState(space.name ?? "");
  const inputRef = useRef<HTMLInputElement | null>(null);

  return (
    <RbDialog
      open
      onOpenChange={(next) => {
        if (!next) {
          onCancel();
        }
      }}
      ariaLabel="Rename project"
      initialFocus={inputRef}
    >
      <DialogCard>
        <DialogTitle>Rename project</DialogTitle>
        <form
          className="dialog-form-rows"
          onSubmit={(event) => {
            event.preventDefault();
            if (value.trim().length > 0) {
              onSubmit(value.trim());
            }
            onCancel();
          }}
        >
          <DialogField>
            <input
              ref={inputRef}
              type="text"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              placeholder="Project name"
              spellCheck={false}
              aria-label="Project name"
            />
          </DialogField>
          <div className="dialog-actions-row">
            <BtnGhost type="button" onClick={onCancel}>
              Cancel
            </BtnGhost>
            <BtnPrimary type="submit">Rename</BtnPrimary>
          </div>
        </form>
      </DialogCard>
    </RbDialog>
  );
}

/**
 * The delete confirm (spaces.rs:3446-3479): "Remove project?" with the
 * singular/plural session count and curly-quote copy, verbatim.
 */
function DeleteSpaceDialog({
  space,
  deviceName,
  chatCount,
  onCancel,
  onConfirm,
}: {
  readonly space: Space;
  readonly deviceName: string;
  readonly chatCount: number;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}) {
  const name = spaceDisplayName(space);
  const copy =
    chatCount === 1
      ? `Removing \u201C${name}\u201D permanently deletes its 1 session on ${deviceName}. This can\u2019t be undone.`
      : `Removing \u201C${name}\u201D permanently deletes its ${chatCount} sessions on ${deviceName}. This can\u2019t be undone.`;
  return (
    <RbDialog
      open
      onOpenChange={(next) => {
        if (!next) {
          onCancel();
        }
      }}
      ariaLabel="Remove project?"
    >
      <DialogCard>
        <DialogTitle>Remove project?</DialogTitle>
        <DialogBody>{copy}</DialogBody>
        <div className="dialog-actions-row">
          <BtnGhost onClick={onCancel}>Cancel</BtnGhost>
          <BtnDanger onClick={onConfirm}>Remove</BtnDanger>
        </div>
      </DialogCard>
    </RbDialog>
  );
}
