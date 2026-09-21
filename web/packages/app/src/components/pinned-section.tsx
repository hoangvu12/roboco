import { useEffect, useRef, useState } from "react";
import type { ChatRow } from "../lib/view";
import { slideOffset } from "../lib/queue-row-logic";
import {
  pinnedDragScrollDelta,
  pinnedDragScrollStep,
  pinnedSessionDropIndex,
  pinnedSessionIsDraggable,
  reorderVisiblePins,
  SIDEBAR_DRAG_SCROLL_FRAME_MS,
  SIDEBAR_SESSION_SLOT,
} from "../lib/sidebar-pins";

/**
 * The pinned section of the sidebar's session list — the desktop's
 * `render_pinned_session_group` + drag machinery (upstream zeron fd42e2ab…,
 * ported local-only: NO registry sync). The locally ordered pinned rows sit
 * above the divider, reorderable by dragging a row between slots: siblings
 * slide one slot toward the vacated space on the tab-slide tween and the
 * dragged row rides the pointer's slot. A drop commits the reordered pins to
 * device-local settings; a drag that leaves the sidebar's column (or Escape)
 * cancels, and a commit lands without a resort glide — the rows are already
 * visually in place.
 *
 * The parent renders and keys the rows (so the list-wide FLIP diff still
 * reaches them); this component wraps each in the drag-offset box and owns
 * the gesture, the edge autoscroll, and the commit.
 */

/** A pointer must travel this far before the press reads as a drag. */
const DRAG_ARM_PX = 4;

interface PinDrag {
  readonly chatId: string;
  readonly from: number;
  readonly over: number;
  /** The visible pins at arm time — the drag dies with any of them. */
  readonly snapshotIds: readonly string[];
}

export function PinnedSection({
  rows,
  pinnedIds,
  items,
  onCommit,
}: {
  /** The visible pinned rows, in display order. */
  readonly rows: readonly ChatRow[];
  /** The full saved pin order (hidden pins included) — the commit base. */
  readonly pinnedIds: readonly string[];
  /** The parent's keyed element per row, aligned with `rows`. */
  readonly items: readonly React.ReactNode[];
  /** A drop's commit: the full next pin order (`commit_pinned_session_drag`). */
  readonly onCommit: (nextPinnedIds: string[]) => void;
}) {
  const groupRef = useRef<HTMLDivElement | null>(null);
  const [drag, setDrag] = useState<PinDrag | null>(null);
  // The window-level handlers outlive the render; they read the live state
  // through refs (the tab strip's pattern — never a state-updater read).
  const dragRef = useRef<PinDrag | null>(null);
  const setDragState = (next: PinDrag | null): void => {
    dragRef.current = next;
    setDrag(next);
  };
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  const pinnedIdsRef = useRef(pinnedIds);
  pinnedIdsRef.current = pinnedIds;
  const onCommitRef = useRef(onCommit);
  onCommitRef.current = onCommit;
  const pointerYRef = useRef<number | null>(null);
  const teardownRef = useRef<(() => void) | null>(null);
  // A completed drag suppresses the click its pointerup would fire on the row.
  const suppressClickRef = useRef(false);

  const count = rows.length;
  const draggable = pinnedSessionIsDraggable(count);

  // A teardown outliving its gesture (unmount mid-drag — the space filter
  // flipped) is a cancel, exactly like `set_space_filter`'s guard.
  useEffect(
    () => () => {
      teardownRef.current?.();
    },
    [],
  );

  // While a drag is armed, the pointer's edge proximity scrolls the sidebar
  // (`start_pinned_session_autoscroll`: a frame-timed loop, bound to the drag).
  useEffect(() => {
    if (drag === null) {
      return;
    }
    const scroller = groupRef.current?.closest(".sidebar-list");
    if (scroller == null) {
      return;
    }
    const timer = window.setInterval(() => {
      const live = dragRef.current;
      const pointerY = pointerYRef.current;
      if (live === null || pointerY === null) {
        return;
      }
      const rect = scroller.getBoundingClientRect();
      const delta = pinnedDragScrollDelta(pointerY, rect.top, rect.bottom);
      const max = Math.max(scroller.scrollHeight - scroller.clientHeight, 0);
      const next = pinnedDragScrollStep(true, 0, 0, scroller.scrollTop, max, delta);
      if (next === null) {
        return;
      }
      scroller.scrollTop = next;
      const group = groupRef.current?.getBoundingClientRect();
      if (group !== undefined) {
        const over = pinnedSessionDropIndex(pointerY - group.top, rowsRef.current.length);
        if (over !== null && over !== live.over) {
          setDragState({ ...live, over });
        }
      }
    }, SIDEBAR_DRAG_SCROLL_FRAME_MS);
    return () => window.clearInterval(timer);
  }, [drag]);

  function armDrag(event: React.PointerEvent, chatId: string, from: number): void {
    if (!draggable || event.button !== 0) {
      return;
    }
    // Interactive corners (the Archive pill) own their press.
    if ((event.target as HTMLElement).closest("button") !== null) {
      return;
    }
    const startX = event.clientX;
    const startY = event.clientY;
    const snapshotIds = rowsRef.current.map((row) => row.chat.id);
    let moved = false;
    const onMove = (move: PointerEvent): void => {
      pointerYRef.current = move.clientY;
      const group = groupRef.current;
      if (group === null) {
        return;
      }
      // `contain_pinned_session_drag`: leaving the sidebar's column cancels.
      const bounds = group.getBoundingClientRect();
      if (move.clientX < bounds.left || move.clientX > bounds.right) {
        cancel();
        return;
      }
      if (!moved) {
        if (Math.abs(move.clientX - startX) <= DRAG_ARM_PX && Math.abs(move.clientY - startY) <= DRAG_ARM_PX) {
          return;
        }
        moved = true;
        // Arm in its own commit so the slide transition is already live when
        // the first retarget lands; commit/cancel drops class and transform
        // together, snapping instantly like the desktop's state teardown.
        setDragState({ chatId, from, over: from, snapshotIds });
        return;
      }
      const over = pinnedSessionDropIndex(move.clientY - bounds.top, rowsRef.current.length);
      if (over === null) {
        return;
      }
      const current = dragRef.current;
      if (current !== null && current.over !== over) {
        setDragState({ ...current, over });
      }
    };
    const teardown = (): void => {
      teardownRef.current = null;
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("keydown", onKey, true);
      pointerYRef.current = null;
    };
    teardownRef.current = teardown;
    const finish = (): void => {
      teardown();
      const current = dragRef.current;
      setDragState(null);
      // `commit_pinned_session_drag`: a no-op move writes nothing; a drag
      // whose snapshot pins did not all survive cancels instead.
      if (current !== null && current.from !== current.over) {
        const visible = rowsRef.current.map((row) => row.chat.id);
        const stillValid =
          current.snapshotIds.includes(current.chatId) &&
          current.snapshotIds.every((id) => visible.includes(id));
        if (stillValid) {
          onCommitRef.current(reorderVisiblePins(pinnedIdsRef.current, visible, current.from, current.over));
        }
      }
      if (moved) {
        suppressClickRef.current = true;
        window.setTimeout(() => {
          suppressClickRef.current = false;
        }, 0);
      }
    };
    const cancel = (): void => {
      teardown();
      setDragState(null);
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
  }

  const draggedIndex = drag === null ? -1 : rows.findIndex((row) => row.chat.id === drag.chatId);
  return (
    <div className="sidebar-pinned" ref={groupRef} data-testid="sidebar-pinned-sessions">
      {rows.map((row, index) => {
        const offset =
          drag === null || draggedIndex < 0
            ? 0
            : index === draggedIndex
              ? (drag.over - drag.from) * SIDEBAR_SESSION_SLOT
              : slideOffset(index, drag.from, drag.over) * SIDEBAR_SESSION_SLOT;
        return (
          <div
            key={row.chat.id}
            className="pinned-row"
            data-dragging={drag !== null ? "1" : undefined}
            style={offset === 0 ? undefined : { transform: `translateY(${offset}px)` }}
            onPointerDown={(event) => armDrag(event, row.chat.id, index)}
            onClickCapture={(event) => {
              if (suppressClickRef.current) {
                event.preventDefault();
                event.stopPropagation();
              }
            }}
          >
            {items[index]}
          </div>
        );
      })}
    </div>
  );
}
