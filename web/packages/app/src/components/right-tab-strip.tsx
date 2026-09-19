import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Icon } from "@roboco/icons";
import {
  resolvedActive,
  rightPaneStore,
  surfaceEqual,
  type ChatPaneState,
  type RightSurface,
  type SurfaceFacts,
} from "../state/right-pane";
import { useIsPhone } from "../state/media";
import { surfaceEntry, type SurfaceContext } from "./surface-registry";
import { surfaceChoices, useGitDetected } from "./surface-picker";
import { drawerOnOpenChange, RbDrawerSheet } from "./base/responsive-surface";

/**
 * The right pane's surface tabs — the desktop's `render_right_tab_strip`
 * (`shell.rs:6687-7198`).
 *
 * The strip lives in the titlebar band, not in the pane, because the titlebar
 * overlay owns that band's hit-testing. Chip geometry is the desktop's: a
 * 24px-tall, 112px-wide chip on a 4px strip gap (116px slot) with a 6px
 * radius, 4px/8px padding and a 3px inner gap. The active chip wears
 * `wash(0.10)`, the rest light up at `wash(0.06)` on hover; the leading 18px
 * slot swaps its icon for the close ✕ on chip hover, and the ✕ closes THAT
 * tab, never the pane (gap R9). Drag-reorder is pointer driven with a custom
 * ghost chip (gap R13) and the strip fades 36px at whichever edge hides chips
 * (gap R15).
 */

/** `CHIP_W` (`shell.rs:6690`) — the terminal drawer's drag mechanics share it. */
const CHIP_W = 112;
/** `CHIP_SLOT` = `CHIP_W + 4` — chip plus the strip's own gap (`:6691`). */
const CHIP_SLOT = CHIP_W + 4;
/** `SurfaceTabTooltip`'s show delay (`shell.rs:6877`). */
const TOOLTIP_DELAY_MS = 350;
/** The fade dead zone: past 1px of scroll the fade is on (`shell.rs:6710`). */
const FADE_DEAD_ZONE = 1;
/** A pointer must travel this far before the press reads as a drag. */
const DRAG_ARM_PX = 4;
/** The `+` menu's card width (`popover_card(theme).w(168)`, `shell.rs:7061`). */
const PLUS_MENU_W = 168;

/** `terminal::panel::drop_index` over content coords. */
export function dropIndex(relX: number, count: number): number {
  if (count === 0) {
    return 0;
  }
  return Math.min(Math.max(Math.floor(relX / CHIP_SLOT), 0), count - 1);
}

/** `slide_offset` (px): which way a sibling opens a gap for the dragged tab. */
export function slideOffset(drag: { from: number; over: number } | null, index: number): number {
  if (drag === null || drag.from === drag.over || index === drag.from) {
    return 0;
  }
  if (drag.from < drag.over && index > drag.from && index <= drag.over) {
    return -CHIP_SLOT;
  }
  if (drag.from > drag.over && index >= drag.over && index < drag.from) {
    return CHIP_SLOT;
  }
  return 0;
}

interface StripDrag {
  readonly from: number;
  readonly over: number;
  /**
   * Bumped whenever the hovered slot changes (`update_right_tab_drag_over`),
   * which restarts the slide tween — the CSS transition retargets from the
   * chip's current transform, which is that restart.
   */
  readonly epoch: number;
  /** The ghost chip follows the pointer (viewport coords). */
  readonly pointerX: number;
  readonly pointerY: number;
  readonly title: string;
}

export function RightTabStrip({ chatId, pane }: { chatId: string; pane: ChatPaneState }) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [drag, setDrag] = useState<StripDrag | null>(null);
  // The drag handlers outlive the render and need the live drag state at
  // pointerup — a ref, never a side effect inside a state updater.
  const dragRef = useRef<StripDrag | null>(null);
  const setDragState = (next: StripDrag | null): void => {
    dragRef.current = next;
    setDrag(next);
  };
  const [fades, setFades] = useState({ left: false, right: false });

  // The drag handlers outlive the render; read the live rows through a ref.
  const rowsRef = useRef<{ surface: RightSurface; facts: SurfaceFacts }[]>([]);
  const rows = rightPaneStore.surfaceRows(chatId);
  rowsRef.current = rows;

  // A completed drag suppresses the click its pointerup would fire on the
  // chip — otherwise every reorder also re-picked the dragged tab.
  const suppressClickRef = useRef(false);

  const recomputeFades = useCallback(() => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    const scrolled = el.scrollLeft;
    const maxScroll = el.scrollWidth - el.clientWidth;
    setFades((current) => {
      const left = scrolled > FADE_DEAD_ZONE;
      const right = scrolled < maxScroll - FADE_DEAD_ZONE;
      return current.left === left && current.right === right ? current : { left, right };
    });
  }, []);

  useEffect(() => {
    recomputeFades();
    const el = scrollRef.current;
    el?.addEventListener("scroll", recomputeFades);
    window.addEventListener("resize", recomputeFades);
    return () => {
      el?.removeEventListener("scroll", recomputeFades);
      window.removeEventListener("resize", recomputeFades);
    };
  }, [recomputeFades, rows.length]);

  // Pointer drag with a custom ghost (gap R13): the browser's HTML5 drag
  // image is visibly different and cannot be styled. A drag is armed by this
  // strip's own chips only, so the desktop's cross-`panel_key` guard — a
  // drag payload from another chat's strip is ignored — holds by
  // construction: there is no payload, the pointer never left this strip.
  const startDrag = (event: React.PointerEvent, from: number, title: string) => {
    if (event.button !== 0) {
      return;
    }
    // The desktop prevents default on chip mouse-down (`shell.rs:6853`) so
    // the press never starts anything but the tab's own interactions.
    event.preventDefault();
    const strip = scrollRef.current;
    if (strip === null) {
      return;
    }
    let moved = false;
    const startX = event.clientX;
    const overFor = (clientX: number): number => {
      // Content coords (`shell.rs:6732-6736`): pointer x minus the strip's
      // viewport left, plus its scroll — GPUI's scroll offset runs negative
      // when scrolled, so the Rust reads `− scroll.offset().x`; the web's
      // `scrollLeft` runs positive, which is the same term with the sign
      // flipped.
      const relX = clientX - strip.getBoundingClientRect().left + strip.scrollLeft;
      return dropIndex(relX, rowsRef.current.length);
    };
    const onMove = (move: PointerEvent): void => {
      if (!moved) {
        if (Math.abs(move.clientX - startX) <= DRAG_ARM_PX) {
          return;
        }
        moved = true;
      }
      const over = overFor(move.clientX);
      const current = dragRef.current;
      const next =
        current === null
          ? { from, over, epoch: 0, pointerX: move.clientX, pointerY: move.clientY, title }
          : current.over === over
            ? { ...current, pointerX: move.clientX, pointerY: move.clientY }
            : { ...current, over, epoch: current.epoch + 1, pointerX: move.clientX, pointerY: move.clientY };
      setDragState(next);
    };
    const finish = (up: PointerEvent): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", finish);
      const current = dragRef.current;
      const over = overFor(up.clientX);
      if (current !== null && moved && current.from !== over) {
        // Reorder to the hovered slot; a lost drag keeps its origin
        // (`shell.rs:6986-7012`).
        rightPaneStore.moveTab(chatId, current.from, over);
      }
      setDragState(null);
      if (moved) {
        suppressClickRef.current = true;
        window.setTimeout(() => {
          suppressClickRef.current = false;
        }, 0);
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", finish);
  };

  const resolved = resolvedActive(pane);

  return (
    <div
      ref={scrollRef}
      className="right-tab-strip"
      role="tablist"
      aria-label="Panel surfaces"
      data-fade-left={fades.left ? "" : undefined}
      data-fade-right={fades.right ? "" : undefined}
    >
      {rows.map(({ surface, facts }, index) => (
        <TabChip
          key={`${surface.kind}:${"id" in surface ? surface.id : ""}`}
          chatId={chatId}
          surface={surface}
          facts={facts}
          index={index}
          resolvedActive={surfaceEqual(surface, resolved)}
          dragged={drag !== null && drag.from === index}
          offset={slideOffset(drag, index)}
          onPick={() => {
            if (!suppressClickRef.current) {
              rightPaneStore.setActive(chatId, surface);
            }
          }}
          onClose={() => rightPaneStore.closeSurface(chatId, surface)}
          onDragStart={(event) => startDrag(event, index, facts.title)}
        />
      ))}
      {/*
        The `+` is part of the strip's scroll content on the desktop
        (`shell.rs:7013`), mounted only while at least one tab exists
        (`:7145-7147`).
      */}
      {rows.length > 0 && <AddSurfaceButton chatId={chatId} paneOpen={pane.open} />}
      {drag !== null &&
        createPortal(
          <div
            className="right-tab-ghost"
            style={{ left: drag.pointerX, top: drag.pointerY }}
            aria-hidden="true"
          >
            <span className="right-tab-ghost-title">{drag.title}</span>
          </div>,
          document.body,
        )}
    </div>
  );
}

function TabChip({
  chatId,
  surface,
  facts,
  index,
  resolvedActive,
  dragged,
  offset,
  onPick,
  onClose,
  onDragStart,
}: {
  chatId: string;
  surface: RightSurface;
  facts: SurfaceFacts;
  index: number;
  resolvedActive: boolean;
  /** The dragged chip leaves an invisible spacer — the ghost carries it. */
  dragged: boolean;
  offset: number;
  onPick: () => void;
  onClose: () => void;
  onDragStart: (event: React.PointerEvent) => void;
}) {
  const ctx: SurfaceContext = { chatId };
  const entry = surfaceEntry(surface.kind);
  const icon = entry?.icon(surface, ctx) ?? "list";
  // `File(_)`'s leading slot is the file-type icon at 14 (ticket 24's
  // manifest); everything else is 12 (`shell.rs:6763-6781`).
  const iconSize = surface.kind === "file" ? 14 : 12;
  // `aria_label` = the detail path (or title), suffixed when dirty.
  const label = `${facts.detail ?? facts.title}${facts.isDirty ? ", unsaved changes" : ""}`;
  const [tip, setTip] = useState<{ x: number; y: number } | null>(null);
  const tipTimer = useRef<number | null>(null);
  const chipRef = useRef<HTMLDivElement | null>(null);

  const clearTip = () => {
    if (tipTimer.current !== null) {
      window.clearTimeout(tipTimer.current);
      tipTimer.current = null;
    }
    setTip(null);
  };

  useEffect(() => clearTip, []);

  const armTip = () => {
    if (facts.detail === null) {
      return;
    }
    const rect = chipRef.current?.getBoundingClientRect();
    if (rect === undefined) {
      return;
    }
    tipTimer.current = window.setTimeout(() => {
      setTip({ x: rect.left, y: rect.bottom + 6 });
    }, TOOLTIP_DELAY_MS);
  };

  return (
    <div
      ref={chipRef}
      className={[
        "right-tab",
        resolvedActive ? "right-tab-active" : "",
        dragged ? "right-tab-dragged" : "",
      ]
        .filter((part) => part.length > 0)
        .join(" ")}
      role="tab"
      aria-selected={resolvedActive}
      aria-label={label}
      tabIndex={0}
      data-index={index}
      style={offset === 0 ? undefined : { transform: `translateX(${offset}px)` }}
      onClick={onPick}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onPick();
        }
      }}
      onPointerDown={onDragStart}
      onPointerEnter={armTip}
      onPointerLeave={clearTip}
      // Middle-click closes, like every tab strip (`shell.rs:6866-6871`).
      onAuxClick={(event) => {
        if (event.button === 1) {
          event.preventDefault();
          onClose();
        }
      }}
    >
      <span className="right-tab-slot">
        <Icon
          name={icon}
          size={iconSize}
          className={`right-tab-icon ${surface.kind === "file" ? "right-tab-icon-file" : ""}`}
        />
        <button
          type="button"
          className="right-tab-close"
          aria-label={`Close ${facts.title}`}
          // Claim the press before the drag-carrying parent arms
          // (`shell.rs:6898-6904`): without the stop the pointerdown starts
          // a tab drag instead of delivering the close click.
          onPointerDown={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.stopPropagation();
            onClose();
          }}
        >
          <Icon name="close" size={12} />
        </button>
      </span>
      <span className="right-tab-title">{facts.title}</span>
      {facts.isDirty && <span className="right-tab-dirty" />}
      {tip !== null &&
        facts.detail !== null &&
        createPortal(
          <div
            className="right-tab-tooltip"
            role="tooltip"
            style={{ left: tip.x, top: tip.y }}
          >
            {facts.detail}
          </div>,
          document.body,
        )}
    </div>
  );
}

/**
 * The `+` (gap R12): 24×24, a 13px glyph, hover `wash(0.11)` — and a
 * press-was-open toggle (`shell.rs:7013-7053`): the mouse-down notes whether
 * the menu was open, and the click closes it if it was, else opens it.
 *
 * Ticket 09 owns the popover lifecycle; until it lands this uses the
 * stylesheet's existing anchored-menu pattern (see `.right-plus-menu`) as the
 * placeholder host rather than inventing a second lifecycle. The menu is
 * portaled to the body — the strip scrolls and the titlebar band clips, and
 * the desktop's `popover::anchored_menu_below_gap` likewise paints above the
 * band rather than inside it.
 *
 * At phone widths (ticket 52, 49's explicit deferral to "the right-pane
 * phone-drawer work") the card body renders in 49's landed bottom-sheet form
 * (`RbDrawerSheet`, the same `PickerCard` phone arm) instead of the anchored
 * portal — the trigger is unchanged: same button, same press-was-open
 * toggle, same controlled `open` flag. The rows are the same component both
 * arms render (below); the sheet replaces placement, not the menu itself.
 */
function AddSurfaceButton({ chatId, paneOpen }: { chatId: string; paneOpen: boolean }) {
  const [open, setOpen] = useState(false);
  const wasOpenRef = useRef(false);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const [anchor, setAnchor] = useState<{ left: number; top: number } | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const gitDetected = useGitDetected(chatId);
  const isPhone = useIsPhone();

  // The menu belongs to the pane's strip, so it leaves with the pane: at
  // phone the strip header stays mounted through the drawer's close glide,
  // and the sheet would otherwise linger over the closed drawer (the Escape
  // ladder's drawer rung consumes the key before the sheet's own handler).
  // At desktop the strip unmounts with the band on the same flip, so this
  // is a no-op there.
  useEffect(() => {
    if (!paneOpen) {
      setOpen(false);
    }
  }, [paneOpen]);

  useEffect(() => {
    // The sheet arm needs none of this: Base UI's modal Drawer owns the
    // outside press (the backdrop) and the Escape path, and the rows live in
    // its portal — the anchored card's window listeners would read the sheet
    // itself as "outside" and close it mid-press.
    if (!open || isPhone) {
      return;
    }
    // Park the card below the button, right-aligned to its edge, 10px down
    // (`anchored_menu_below_gap`).
    const rect = buttonRef.current?.getBoundingClientRect();
    if (rect !== undefined) {
      setAnchor({ left: Math.max(rect.right - PLUS_MENU_W, 8), top: rect.bottom + 10 });
    }
    const onDown = (event: MouseEvent): void => {
      const target = event.target as Node;
      // The portal means the menu is no longer a DOM child of the host, so
      // the outside test covers both.
      if (rootRef.current !== null && !rootRef.current.contains(target)
        && !document.querySelector(".right-plus-menu")?.contains(target)) {
        setOpen(false);
      }
    };
    // The menu's own Escape (the ladder integration is ticket 06's step 7).
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [open, isPhone]);

  return (
    <div className="right-surface-add-host" ref={rootRef}>
      <button
        type="button"
        id="right-surface-add"
        ref={buttonRef}
        className="right-surface-add"
        aria-label="Add panel surface"
        aria-haspopup="menu"
        aria-expanded={open}
        onPointerDown={() => {
          wasOpenRef.current = open;
        }}
        onClick={() => {
          setOpen(wasOpenRef.current ? false : true);
        }}
      >
        <Icon name="plus" size={13} />
      </button>
      {isPhone ? (
        <RbDrawerSheet
          open={open}
          onOpenChange={drawerOnOpenChange((next: boolean) => {
            if (!next) {
              setOpen(false);
            }
          })}
          role="menu"
          ariaLabel="Add panel surface"
          cardClassName="right-plus-menu-sheet"
        >
          <AddSurfaceRows chatId={chatId} gitDetected={gitDetected} onPick={() => setOpen(false)} />
        </RbDrawerSheet>
      ) : (
        open &&
        anchor !== null &&
        createPortal(
          <AddSurfaceMenu
            chatId={chatId}
            gitDetected={gitDetected}
            left={anchor.left}
            top={anchor.top}
            onPick={() => setOpen(false)}
          />,
          document.body,
        )
      )}
    </div>
  );
}

/**
 * The `+` menu's rows (`shell.rs:7054-7144`): the same rows as the picker,
 * minus `Browser` (desktop-only). Picking a row closes the menu, as every
 * popover menu does. Shared by the desktop anchored card and the phone
 * sheet — the two arms differ in placement only.
 */
function AddSurfaceRows({
  chatId,
  gitDetected,
  onPick,
}: {
  chatId: string;
  gitDetected: boolean;
  onPick: () => void;
}) {
  return surfaceChoices(gitDetected).map((choice) => (
    <button
      key={choice.id}
      type="button"
      role="menuitem"
      className="right-plus-menu-row"
      onClick={() => {
        choice.open(chatId);
        onPick();
      }}
    >
      <Icon name={choice.icon} size={13} />
      <span className="right-plus-menu-label">{choice.label}</span>
    </button>
  ));
}

/**
 * The `+` menu's desktop form (`shell.rs:7054-7144`): the 168px card portaled
 * to the body at the button's viewport coords, 10px below it. The phone form
 * is `AddSurfaceButton`'s sheet arm above; the rows are `AddSurfaceRows`.
 */
function AddSurfaceMenu({
  chatId,
  gitDetected,
  left,
  top,
  onPick,
}: {
  chatId: string;
  gitDetected: boolean;
  left: number;
  top: number;
  onPick: () => void;
}) {
  return (
    <div className="right-plus-menu" role="menu" aria-label="Add panel surface" style={{ left, top }}>
      <AddSurfaceRows chatId={chatId} gitDetected={gitDetected} onPick={onPick} />
    </div>
  );
}
