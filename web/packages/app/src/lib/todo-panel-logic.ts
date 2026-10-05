import type { SessionMessageEntry, TodoItem } from "@roboco/proto";

/**
 * The todo panel's pure view model — the web peer of `crates/ui/src/
 * todo_panel.rs`'s first half (`latest_todo`, `TodoSummary`,
 * `focus_window`, `rows`). Same numbers, same shapes: the panel renders
 * from these, unit tests drive them without a window.
 */

/**
 * The wire status the regenerated `TodoItem.status` carries (`TodoStatus`,
 * proto/agent.rs: camelCase `pending | inProgress | completed`, written only
 * for in-progress items). Structural here so the logic typechecks before
 * and after the wiregen refresh — the effective-status helper derives from
 * `done` exactly like the Rust `TodoItem::status()`.
 */
export type WireTodoStatus = "pending" | "inProgress" | "completed";

/** The wire item shape the panel reads (post-regen `TodoItem`). */
export interface WireTodoItem {
  readonly text: string;
  readonly done: boolean;
  readonly status?: WireTodoStatus | null;
}

/** `TodoItem::status()` — `done` wins, then the explicit status, else pending. */
export function effectiveTodoStatus(item: WireTodoItem): WireTodoStatus {
  return item.done ? "completed" : (item.status ?? "pending");
}

/** Lists longer than this fold to a focus window around the current item. */
export const FOLD_ABOVE = 6;
/** Items a folded list keeps visible (the current one centered). */
export const FOCUS_WINDOW = 3;

/** `latest_todo` — the last non-empty `Todo` tool part of assistant entries. */
export function latestTodo(entries: readonly SessionMessageEntry[]): readonly TodoItem[] | null {
  for (let e = entries.length - 1; e >= 0; e -= 1) {
    const entry = entries[e]!;
    if (entry.role !== "assistant") {
      continue;
    }
    for (let p = entry.parts.length - 1; p >= 0; p -= 1) {
      const part = entry.parts[p]!;
      if (part.kind === "tool" && part.call.kind === "todo" && part.call.items.length > 0) {
        return part.call.items;
      }
    }
  }
  return null;
}

/** What the collapsed header reports (`TodoSummary`). */
export interface TodoSummary {
  readonly total: number;
  readonly done: number;
  /** First in-progress item. */
  readonly active: number | null;
  /** First item not yet completed. */
  readonly next: number | null;
}

export function todoSummary(items: readonly WireTodoItem[]): TodoSummary {
  const summary: { total: number; done: number; active: number | null; next: number | null } = {
    total: items.length,
    done: 0,
    active: null,
    next: null,
  };
  for (let ix = 0; ix < items.length; ix += 1) {
    switch (effectiveTodoStatus(items[ix]!)) {
      case "completed":
        summary.done += 1;
        break;
      case "inProgress":
        summary.active ??= ix;
        summary.next ??= ix;
        break;
      default:
        summary.next ??= ix;
    }
  }
  return summary;
}

/** The item the header names: what is being worked on, else what is next. */
export function todoHeadline(summary: TodoSummary): number | null {
  return summary.active ?? summary.next;
}

/**
 * The slice of a long list that stays visible when folded: three items with
 * the current one in the middle, clamped to the list ends; a finished list
 * shows its last three. Lists up to `FOLD_ABOVE` never fold.
 */
export function todoFocusWindow(items: readonly WireTodoItem[]): readonly [number, number] {
  const total = items.length;
  if (total <= FOLD_ABOVE) {
    return [0, total];
  }
  const summary = todoSummary(items);
  const focus = todoHeadline(summary) ?? total - 1;
  const start = Math.max(0, Math.min(focus - 1, total - FOCUS_WINDOW));
  return [start, start + FOCUS_WINDOW];
}

export type TodoFoldSide = "earlier" | "later";

/** One rendered row of the expanded list (`TodoRow`). */
export type TodoRowModel =
  | { readonly row: "item"; readonly ix: number }
  | { readonly row: "fold"; readonly side: TodoFoldSide; readonly count: number; readonly open: boolean };

/**
 * The rows of the expanded list, top to bottom: items in the agent's order,
 * fold toggles standing in for hidden runs at the edges they stand for.
 */
export function todoRows(
  items: readonly WireTodoItem[],
  showEarlier: boolean,
  showLater: boolean,
): readonly TodoRowModel[] {
  const [start, end] = todoFocusWindow(items);
  const earlier = start;
  const later = items.length - end;
  const out: TodoRowModel[] = [];
  if (earlier > 0) {
    out.push({ row: "fold", side: "earlier", count: earlier, open: showEarlier });
    if (showEarlier) {
      for (let ix = 0; ix < earlier; ix += 1) {
        out.push({ row: "item", ix });
      }
    }
  }
  for (let ix = start; ix < end; ix += 1) {
    out.push({ row: "item", ix });
  }
  if (later > 0) {
    if (showLater) {
      for (let ix = end; ix < items.length; ix += 1) {
        out.push({ row: "item", ix });
      }
    }
    out.push({ row: "fold", side: "later", count: later, open: showLater });
  }
  return out;
}

/**
 * `signature` — identity of a finished list, so a dismissal holds until the
 * agent writes a different one. Text + effective status of every item.
 */
export function todoSignature(items: readonly WireTodoItem[]): string {
  return items
    .map((item) => `${effectiveTodoStatus(item)}:${item.text}`)
    .join("\n");
}
