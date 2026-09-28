import { useEffect } from "react";
import type { Dispatch, RefObject, SetStateAction } from "react";

/**
 * The still-pointer hover resync (upstream f1ea80d7's `chat_hover_resync`,
 * the web port). The DOM re-rendering under an UNMOVED pointer fires no
 * `mouseenter` — the browser re-evaluates hover only on real movement,
 * exactly like gpui — so after an Archive/Unarchive pill click removes a
 * row from under the pointer, the row that slides up would never light
 * its pill: the user has to jog the mouse between every archive.
 *
 * A pill click ARMS the resync with the click's client point. While
 * armed, every registered row re-tests the point against its own bounds
 * on every animation frame — the web's paint-time hit test, the peer of
 * the desktop's per-row canvas hitbox — adopting hover when the point
 * falls inside and releasing it when it does not (one point, one row: a
 * previous row's hover releases exactly when the next one adopts). The
 * first real `pointermove` releases the arm; the browser's own hover
 * tracking owns it from there, exactly as gpui's `on_hover` does.
 */

/** A client-viewport point, as the click that armed the resync saw it. */
export interface StillPoint {
  readonly x: number;
  readonly y: number;
}

let armed: StillPoint | null = null;
let releaseMove: (() => void) | null = null;
let framePending = false;

interface HoverRow {
  readonly ref: RefObject<HTMLElement | null>;
  readonly setHovered: Dispatch<SetStateAction<boolean>>;
}

/** The rows currently subscribed (mount-scoped, like the desktop's rendered rows). */
const rows = new Set<HoverRow>();

/** Arm the resync at the pill click's client point. */
export function armStillPointer(point: StillPoint): void {
  armed = point;
  if (releaseMove === null) {
    const onMove = (): void => {
      releaseStillPointer();
    };
    window.addEventListener("pointermove", onMove);
    releaseMove = (): void => {
      window.removeEventListener("pointermove", onMove);
    };
  }
  // The click's own row still contains the point; the frames that follow
  // re-test as the archive lands and rows slide.
  for (const row of rows) {
    testRow(row, point);
  }
  scheduleFrame();
}

/**
 * Release the arm — the pointer moved, so hover tracking is the browser's
 * again. Hover state itself is left exactly where it stands: the next real
 * mouseenter/mouseleave owns it from here.
 */
export function releaseStillPointer(): void {
  if (armed === null) {
    return;
  }
  armed = null;
  releaseMove?.();
  releaseMove = null;
}

/** The armed point, or null — the seam tests read between interactions. */
export function stillPoint(): StillPoint | null {
  return armed;
}

function testRow(row: HoverRow, point: StillPoint): void {
  const element = row.ref.current;
  if (element === null) {
    return;
  }
  const bounds = element.getBoundingClientRect();
  row.setHovered(
    point.x >= bounds.left &&
      point.x <= bounds.right &&
      point.y >= bounds.top &&
      point.y <= bounds.bottom,
  );
}

/**
 * The frame loop: while armed, re-test every row per animation frame —
 * rows glide into place under the still pointer (the FLIP resort), and
 * the frame that lands one over the point is the moment its pill lights.
 * A frame that ran synchronously (a test stub of `requestAnimationFrame`)
 * never self-schedules, so the loop cannot spin.
 */
function scheduleFrame(): void {
  if (framePending || armed === null) {
    return;
  }
  framePending = true;
  let synchronous = true;
  window.requestAnimationFrame(() => {
    framePending = false;
    const point = armed;
    if (point !== null) {
      for (const row of rows) {
        testRow(row, point);
      }
      if (!synchronous) {
        scheduleFrame();
      }
    }
  });
  synchronous = false;
}

/**
 * One row's share of the resync: register for the armed frame loop (plus
 * an immediate test — a row can MOUNT mid-arm, which is exactly what the
 * row sliding under the pointer after an archive does).
 */
export function useStillPointerHover(
  ref: RefObject<HTMLElement | null>,
  setHovered: Dispatch<SetStateAction<boolean>>,
): void {
  useEffect(() => {
    const row: HoverRow = { ref, setHovered };
    rows.add(row);
    const point = armed;
    if (point !== null) {
      testRow(row, point);
    }
    return () => {
      rows.delete(row);
    };
  }, [ref, setHovered]);
}
