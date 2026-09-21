/**
 * Pinned sidebar sessions — the web peer of the desktop's pin machinery
 * (`shell.rs` + `shell/spaces.rs`, upstream zeron fd42e2ab…da041aab, ported
 * local-only: NO registry sync). Pins are a device-local, presentation-only
 * preference persisted in `ui-settings.ts`; the pure projection, reorder,
 * cleanup, and drag-geometry rules live here so the components stay thin and
 * the unit tests mirror the Rust `pinned_session_tests` one-for-one.
 */

/** `shell.rs::SIDEBAR_LIST_GAP` — the flex gap between sidebar rows. */
export const SIDEBAR_LIST_GAP = 2;
/**
 * `shell.rs::SIDEBAR_SESSION_SLOT` — the pinned drag's quantization unit
 * (61px branch-row height + gap).
 */
export const SIDEBAR_SESSION_SLOT = 61 + SIDEBAR_LIST_GAP;
/** `shell.rs::SIDEBAR_DRAG_SCROLL_*` — edge autoscroll geometry. */
export const SIDEBAR_DRAG_SCROLL_BAND = 48;
export const SIDEBAR_DRAG_SCROLL_MAX = 12;
export const SIDEBAR_DRAG_SCROLL_FRAME_MS = 16;
/** The scroll region's top padding (`SIDEBAR_LIST_PAD_TOP`). */
export const SIDEBAR_LIST_PAD_TOP = 4;
/** `shell.rs::SIDEBAR_PINNED_DIVIDER_*` — the hairline box between sections. */
export const SIDEBAR_PINNED_DIVIDER_HEIGHT = 13;
export const SIDEBAR_PINNED_DIVIDER_KEY = "sidebar-pinned-divider";

/**
 * `spaces.rs::project_pinned_first` — promote the locally ordered pins above
 * the untouched activity projection. Every unpinned id keeps exactly the
 * relative order supplied by recency.
 */
export function projectPinnedFirst(recencyIds: readonly string[], pinnedIds: readonly string[]): string[] {
  const active = new Set(recencyIds);
  const pinned = new Set(pinnedIds);
  const seen = new Set<string>();
  const out: string[] = [];
  for (const id of pinnedIds) {
    if (active.has(id) && !seen.has(id)) {
      seen.add(id);
      out.push(id);
    }
  }
  for (const id of recencyIds) {
    if (!pinned.has(id) && !seen.has(id)) {
      seen.add(id);
      out.push(id);
    }
  }
  return out;
}

/**
 * `spaces.rs::reorder_visible_pins` — reorder the visible pinned projection
 * while preserving hidden or archived pins in their existing global slots.
 */
export function reorderVisiblePins(
  pinnedIds: readonly string[],
  visibleIds: readonly string[],
  from: number,
  to: number,
): string[] {
  if (from >= visibleIds.length || to >= visibleIds.length || from === to) {
    return [...pinnedIds];
  }
  const reordered = [...visibleIds];
  const [moved] = reordered.splice(from, 1);
  reordered.splice(to, 0, moved!);
  const visible = new Set(visibleIds);
  const replacements = reordered.values();
  return pinnedIds.map((id) => (visible.has(id) ? (replacements.next().value ?? id) : id));
}

/**
 * `spaces.rs::retain_known_pins` — remove only ids absent from the workspace.
 * Archived sessions remain known so unarchiving restores their local pin and
 * position. Returns true when the list changed.
 */
export function retainKnownPins(pinnedIds: string[], knownChatIds: ReadonlySet<string>): string[] | null {
  const seen = new Set<string>();
  const next = pinnedIds.filter((id) => {
    if (!knownChatIds.has(id) || seen.has(id)) {
      return false;
    }
    seen.add(id);
    return true;
  });
  return next.length === pinnedIds.length ? null : next;
}

/** `spaces.rs::pinned_session_drop_index` — strict in-section slot. */
export function pinnedSessionDropIndex(relY: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  const height = count * SIDEBAR_SESSION_SLOT - SIDEBAR_LIST_GAP;
  if (relY < 0 || relY > height) {
    return null;
  }
  return Math.min(Math.floor(relY / SIDEBAR_SESSION_SLOT), count - 1);
}

/**
 * `spaces.rs::pinned_session_clamped_index` — keep a sidebar-wide drag
 * physically bounded to the pinned section: the nearest valid pinned slot
 * while the pointer is over regular sessions (the strict helper above still
 * identifies whether the pointer is actually inside).
 */
export function pinnedSessionClampedIndex(relY: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  return Math.min(Math.floor(Math.max(relY, 0) / SIDEBAR_SESSION_SLOT), count - 1);
}

/**
 * `render_active_rows`'s pin split: re-sort rows by the projected order
 * (stable — a rank tie keeps the incoming order), then split the leading
 * pinned block off so regular rows group without them.
 */
export function pinOrderedRows<T extends { chat: { id: string } }>(
  rows: readonly T[],
  pinnedIds: readonly string[],
): { pinned: T[]; regular: T[] } {
  if (pinnedIds.length === 0 || rows.length === 0) {
    return { pinned: [], regular: [...rows] };
  }
  const orderedIds = projectPinnedFirst(
    rows.map((row) => row.chat.id),
    pinnedIds,
  );
  const rank = new Map(orderedIds.map((id, ix) => [id, ix] as const));
  const sorted = [...rows].sort(
    (a, b) => (rank.get(a.chat.id) ?? Number.MAX_SAFE_INTEGER) - (rank.get(b.chat.id) ?? Number.MAX_SAFE_INTEGER),
  );
  const active = new Set(rows.map((row) => row.chat.id));
  const pinnedCount = new Set(pinnedIds.filter((id) => active.has(id))).size;
  return { pinned: sorted.slice(0, pinnedCount), regular: sorted.slice(pinnedCount) };
}

/** `spaces.rs::pinned_session_is_draggable` — a single pin has nowhere to go. */
export function pinnedSessionIsDraggable(count: number): boolean {
  return count > 1;
}

/** `spaces.rs::pinned_drag_scroll_delta` — proportional edge autoscroll. */
export function pinnedDragScrollDelta(pointerY: number, viewportTop: number, viewportBottom: number): number {
  if (viewportBottom <= viewportTop) {
    return 0;
  }
  if (pointerY < viewportTop + SIDEBAR_DRAG_SCROLL_BAND) {
    const penetration = Math.min(Math.max((viewportTop + SIDEBAR_DRAG_SCROLL_BAND - pointerY) / SIDEBAR_DRAG_SCROLL_BAND, 0), 1);
    return -SIDEBAR_DRAG_SCROLL_MAX * penetration;
  }
  if (pointerY > viewportBottom - SIDEBAR_DRAG_SCROLL_BAND) {
    const penetration = Math.min(Math.max((pointerY - (viewportBottom - SIDEBAR_DRAG_SCROLL_BAND)) / SIDEBAR_DRAG_SCROLL_BAND, 0), 1);
    return SIDEBAR_DRAG_SCROLL_MAX * penetration;
  }
  return 0;
}

/**
 * `spaces.rs::pinned_drag_scroll_step` — one autoscroll tick: null when the
 * drag ended, the loop is stale, or the edge scroll ran out of room.
 */
export function pinnedDragScrollStep(
  dragActive: boolean,
  loopGeneration: number,
  dragGeneration: number,
  current: number,
  max: number,
  delta: number,
): number | null {
  if (!dragActive || loopGeneration !== dragGeneration || delta === 0) {
    return null;
  }
  const next = Math.min(Math.max(current + delta, 0), Math.max(max, 0));
  return next === current ? null : next;
}

/**
 * `spaces.rs::pinned_drag_snapshot_is_valid` — a drag started against a
 * snapshot of the visible pins stays live only while every original pin is
 * still around (a deletion or external unpin cancels it).
 */
export function pinnedDragSnapshotIsValid(
  draggedId: string,
  snapshotIds: readonly string[],
  currentIds: ReadonlySet<string>,
): boolean {
  return snapshotIds.includes(draggedId) && snapshotIds.every((id) => currentIds.has(id));
}
