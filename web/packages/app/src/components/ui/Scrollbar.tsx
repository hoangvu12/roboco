/**
 * The floating menu scrollbar — port of `popover.rs:1170-1397` (and its
 * horizontal twin, `:1406-1546`): an on-demand rail hidden until the list
 * is hovered or a drag holds it, with a 3px resting thumb expanding to 5px
 * when active. Any list that mounts one must hide its native scrollbar
 * (`.menu-scroll-area`), since this rail replaces it visually.
 *
 * The rail must sit inside a `position: relative` wrapper that spans the
 * scroll area; it positions itself against that wrapper. Drags use pointer
 * capture (the desktop's gpui drag-stream + drag-ghost machinery is not
 * ported).
 */

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";

/** Track inset top/bottom; the thumb travels inside it. */
export const MENU_SCROLLBAR_TRACK_INSET = 4;
/** Invisible hit strip width on the right (or bottom) edge. */
export const MENU_SCROLLBAR_HIT_WIDTH = 10;
/** Resting thumb width. */
export const MENU_SCROLLBAR_THUMB_WIDTH = 3;
/** Thumb width while hovered/dragged. */
export const MENU_SCROLLBAR_HOVER_THUMB_WIDTH = 5;
/** Smallest readable thumb on very long lists. */
export const MENU_SCROLLBAR_MIN_THUMB = 24;

export interface MenuScrollbarMetrics {
  readonly trackLength: number;
  readonly thumbStart: number;
  readonly thumbLength: number;
  readonly maxScroll: number;
}

const clamp = (value: number, low: number, high: number): number =>
  Math.min(Math.max(value, low), Math.max(low, high));

/**
 * Pure geometry from the viewport and scroll distances (`from_viewport`,
 * `popover.rs:1198-1223`): null when the content fits or the viewport is
 * too small to hold a track.
 */
export function menuScrollbarMetrics(
  viewportLength: number,
  maxScroll: number,
  currentScroll: number,
): MenuScrollbarMetrics | null {
  const max = Math.max(0, maxScroll);
  if (viewportLength <= 0 || max <= 0) {
    return null;
  }
  const trackLength = Math.max(0, viewportLength - MENU_SCROLLBAR_TRACK_INSET * 2);
  if (trackLength <= 0) {
    return null;
  }
  const contentLength = viewportLength + max;
  const thumbLength = Math.min(
    Math.max((trackLength * viewportLength) / contentLength, MENU_SCROLLBAR_MIN_THUMB),
    trackLength,
  );
  const current = clamp(currentScroll, 0, max);
  const travel = Math.max(0, trackLength - thumbLength);
  return {
    trackLength,
    thumbStart: (travel * current) / max,
    thumbLength,
    maxScroll: max,
  };
}

function travelOf(metrics: MenuScrollbarMetrics): number {
  return Math.max(0, metrics.trackLength - metrics.thumbLength);
}

interface ScrollbarState {
  readonly hovered: boolean;
  readonly active: boolean;
  readonly dragging: boolean;
}

/**
 * The vertical rail. Pass the scrollable element's ref; hover tracking,
 * geometry, and the drag all hang off it.
 */
export function MenuScrollbar({ scrollRef }: { scrollRef: RefObject<HTMLElement | null> }) {
  const railRef = useRef<HTMLDivElement | null>(null);
  const grab = useRef<number | null>(null);
  const [state, setState] = useState<ScrollbarState>({ hovered: false, active: false, dragging: false });
  const [metrics, setMetrics] = useState<MenuScrollbarMetrics | null>(null);

  const measure = useCallback((): void => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    setMetrics(
      menuScrollbarMetrics(el.clientHeight, el.scrollHeight - el.clientHeight, el.scrollTop),
    );
  }, [scrollRef]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    const onScroll = (): void => measure();
    el.addEventListener("scroll", onScroll, { passive: true });
    const observer = new ResizeObserver(onScroll);
    observer.observe(el);
    measure();
    return () => {
      el.removeEventListener("scroll", onScroll);
      observer.disconnect();
    };
  }, [scrollRef, measure]);

  const pointerInTrack = (clientY: number): number => {
    const rail = railRef.current;
    if (rail === null) {
      return 0;
    }
    return clientY - rail.getBoundingClientRect().top - MENU_SCROLLBAR_TRACK_INSET;
  };

  const dragTo = (clientY: number): void => {
    const el = scrollRef.current;
    const grabOffset = grab.current;
    if (el === null || grabOffset === null || metrics === null) {
      return;
    }
    const travel = travelOf(metrics);
    const thumbTop = clamp(pointerInTrack(clientY) - grabOffset, 0, travel);
    el.scrollTop = travel <= 0 ? 0 : (thumbTop / travel) * metrics.maxScroll;
  };

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (metrics === null) {
      return;
    }
    event.preventDefault();
    const inTrack = pointerInTrack(event.clientY);
    const onThumb =
      inTrack >= metrics.thumbStart && inTrack <= metrics.thumbStart + metrics.thumbLength;
    grab.current = onThumb ? inTrack - metrics.thumbStart : metrics.thumbLength / 2;
    event.currentTarget.setPointerCapture(event.pointerId);
    setState((current) => ({ ...current, dragging: true, active: true }));
    dragTo(event.clientY);
  };

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (grab.current !== null) {
      dragTo(event.clientY);
    }
  };

  const endPress = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (grab.current === null) {
      return;
    }
    grab.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setState((current) => ({ ...current, dragging: false, active: current.hovered }));
  };

  // List hover — listeners on the scroll element itself, so the consumer's
  // markup stays unchanged.
  useEffect(() => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    const enter = (): void => setState((current) => ({ ...current, hovered: true }));
    const leave = (): void =>
      setState((current) => ({ ...current, hovered: false, active: current.dragging }));
    el.addEventListener("pointerenter", enter);
    el.addEventListener("pointerleave", leave);
    return () => {
      el.removeEventListener("pointerenter", enter);
      el.removeEventListener("pointerleave", leave);
    };
  }, [scrollRef]);

  const visible = state.hovered || state.dragging;
  if (!visible || metrics === null) {
    return null;
  }
  const thumbWidth = state.active ? MENU_SCROLLBAR_HOVER_THUMB_WIDTH : MENU_SCROLLBAR_THUMB_WIDTH;
  return (
    <div
      ref={railRef}
      className={`menu-scrollbar-rail ${state.active ? "menu-scrollbar-rail-active" : ""}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endPress}
      onPointerCancel={endPress}
      onPointerEnter={() => setState((current) => ({ ...current, active: true }))}
      onPointerLeave={() =>
        setState((current) => ({ ...current, active: current.dragging }))
      }
      aria-hidden
    >
      <div
        className="menu-scrollbar-thumb"
        style={{
          top: `${MENU_SCROLLBAR_TRACK_INSET + metrics.thumbStart}px`,
          width: `${thumbWidth}px`,
          height: `${metrics.thumbLength}px`,
          borderRadius: `${thumbWidth / 2}px`,
        }}
      />
    </div>
  );
}

/**
 * The horizontal twin (`popover.rs:1406-1546`) — identical math on the X
 * axis for code planes. Nothing in scope consumes it yet; it exists for API
 * symmetry with the vertical rail.
 */
export function HorizontalScrollbar({ scrollRef }: { scrollRef: RefObject<HTMLElement | null> }) {
  const railRef = useRef<HTMLDivElement | null>(null);
  const grab = useRef<number | null>(null);
  const [state, setState] = useState<ScrollbarState>({ hovered: false, active: false, dragging: false });
  const [metrics, setMetrics] = useState<MenuScrollbarMetrics | null>(null);

  const measure = useCallback((): void => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    setMetrics(menuScrollbarMetrics(el.clientWidth, el.scrollWidth - el.clientWidth, el.scrollLeft));
  }, [scrollRef]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    const onScroll = (): void => measure();
    el.addEventListener("scroll", onScroll, { passive: true });
    const observer = new ResizeObserver(onScroll);
    observer.observe(el);
    measure();
    return () => {
      el.removeEventListener("scroll", onScroll);
      observer.disconnect();
    };
  }, [scrollRef, measure]);

  const pointerInTrack = (clientX: number): number => {
    const rail = railRef.current;
    if (rail === null) {
      return 0;
    }
    return clientX - rail.getBoundingClientRect().left - MENU_SCROLLBAR_TRACK_INSET;
  };

  const dragTo = (clientX: number): void => {
    const el = scrollRef.current;
    const grabOffset = grab.current;
    if (el === null || grabOffset === null || metrics === null) {
      return;
    }
    const travel = travelOf(metrics);
    const thumbLeft = clamp(pointerInTrack(clientX) - grabOffset, 0, travel);
    el.scrollLeft = travel <= 0 ? 0 : (thumbLeft / travel) * metrics.maxScroll;
  };

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (metrics === null) {
      return;
    }
    event.preventDefault();
    const inTrack = pointerInTrack(event.clientX);
    const onThumb =
      inTrack >= metrics.thumbStart && inTrack <= metrics.thumbStart + metrics.thumbLength;
    grab.current = onThumb ? inTrack - metrics.thumbStart : metrics.thumbLength / 2;
    event.currentTarget.setPointerCapture(event.pointerId);
    setState((current) => ({ ...current, dragging: true, active: true }));
    dragTo(event.clientX);
  };

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (grab.current !== null) {
      dragTo(event.clientX);
    }
  };

  const endPress = (event: React.PointerEvent<HTMLDivElement>): void => {
    if (grab.current === null) {
      return;
    }
    grab.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    setState((current) => ({ ...current, dragging: false, active: current.hovered }));
  };

  useEffect(() => {
    const el = scrollRef.current;
    if (el === null) {
      return;
    }
    const enter = (): void => setState((current) => ({ ...current, hovered: true }));
    const leave = (): void =>
      setState((current) => ({ ...current, hovered: false, active: current.dragging }));
    el.addEventListener("pointerenter", enter);
    el.addEventListener("pointerleave", leave);
    return () => {
      el.removeEventListener("pointerenter", enter);
      el.removeEventListener("pointerleave", leave);
    };
  }, [scrollRef]);

  const visible = state.hovered || state.dragging;
  if (!visible || metrics === null) {
    return null;
  }
  const thumbHeight = state.active ? MENU_SCROLLBAR_HOVER_THUMB_WIDTH : MENU_SCROLLBAR_THUMB_WIDTH;
  return (
    <div
      ref={railRef}
      className={`menu-scrollbar-rail-x ${state.active ? "menu-scrollbar-rail-active" : ""}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endPress}
      onPointerCancel={endPress}
      onPointerEnter={() => setState((current) => ({ ...current, active: true }))}
      onPointerLeave={() =>
        setState((current) => ({ ...current, active: current.dragging }))
      }
      aria-hidden
    >
      <div
        className="menu-scrollbar-thumb"
        style={{
          left: `${MENU_SCROLLBAR_TRACK_INSET + metrics.thumbStart}px`,
          height: `${thumbHeight}px`,
          width: `${metrics.thumbLength}px`,
          borderRadius: `${thumbHeight / 2}px`,
        }}
      />
    </div>
  );
}
