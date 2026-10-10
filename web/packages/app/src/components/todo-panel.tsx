import { useCallback, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { Icon } from "@roboco/icons";
import type { SessionMessageEntry, TodoItem } from "@roboco/proto";
import type { TranscriptStore } from "../state/transcript-store";
import { todoPanelStore, useTodoPanelVersion } from "../state/todo-panel";
import { GlyphSpinner } from "./glyph-spinner";
import { Tooltip } from "./ui/Tooltip";
import {
  effectiveTodoStatus,
  todoHeadline,
  todoRows,
  todoSignature,
  todoSummary,
  type WireTodoItem,
} from "../lib/todo-panel-logic";

/**
 * The agent's checklist tray — the web peer of `crates/ui/src/todo_panel.rs`'s
 * second half (`render_todo_panel` on `Composer`): a frosted tray stacked
 * above the queue tray in the composer's column, one step narrower (16px
 * inset over the queue's own 16px), tucked behind the composer pill like the
 * queue is. Collapsed, the header reports `Todo 2/5` plus the current item;
 * expanded, the list folds to a 3-item focus window with "N earlier/later"
 * rows. Dismissable whenever the turn is idle.
 *
 * The pure view model lives in `lib/todo-panel-logic.ts`; the per-chat
 * presentation state in `state/todo-panel.ts` (in memory for the app run).
 */

interface TodoPanelProps {
  /** The selected chat's live transcript store — the todo list rides it. */
  readonly store: TranscriptStore;
  /** The selected chat (the per-chat presentation state's key). */
  readonly chatId: string;
  /** True while the chat's turn is running (the active item animates). */
  readonly live: boolean;
}

/** Structural read: the regenerated `TodoItem` carries the additive `status`. */
function wireTodo(entries: readonly SessionMessageEntry[]): readonly WireTodoItem[] | null {
  for (let e = entries.length - 1; e >= 0; e -= 1) {
    const entry = entries[e]!;
    if (entry.role !== "assistant") {
      continue;
    }
    for (let p = entry.parts.length - 1; p >= 0; p -= 1) {
      const part = entry.parts[p]!;
      if (part.kind === "tool" && part.call.kind === "todo" && part.call.items.length > 0) {
        return part.call.items as readonly TodoItem[] as readonly WireTodoItem[];
      }
    }
  }
  return null;
}

export function TodoPanel({ store, chatId, live }: TodoPanelProps): ReactNode {
  // Panel-state mutations (toggles, dismissal) bump this version.
  useTodoPanelVersion();
  // The transcript store's snapshot is identity-stable until an actual
  // change, so this re-renders exactly when the todo list can have moved.
  // The subscribe/getSnapshot wrappers are load-bearing: React calls both as
  // detached function references, and TranscriptStore's methods are class
  // methods over private fields — an unbound `store.getSnapshot` would throw
  // `Cannot read properties of undefined (reading '#…')` on every chat open.
  const subscribe = useCallback((listener: () => void) => store.subscribe(listener), [store]);
  const getSnapshot = useCallback(() => store.getSnapshot(), [store]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const items = useMemo(() => wireTodo(snapshot.entries), [snapshot.entries]);
  const summary = items === null ? null : todoSummary(items);
  const finished = summary !== null && summary.total > 0 && summary.done === summary.total;
  const settled = finished && !live;

  // The settled transition (everything done + idle) drops an explicit open
  // choice exactly once, so a finished list tidies itself to compact.
  useEffect(() => {
    const state = todoPanelStore.state(chatId);
    if (settled && !state.wasSettled) {
      state.expanded = null;
      todoPanelStore.bump();
    }
    state.wasSettled = settled;
  }, [chatId, settled]);

  if (items === null || summary === null) {
    return null;
  }
  const state = todoPanelStore.state(chatId);
  if (state.dismissed === todoSignature(items)) {
    return null;
  }
  // A finished list is compact by default even while a new turn runs.
  const expanded = state.expanded ?? !finished;
  const headlineIx = todoHeadline(summary);
  const headline = finished ? "All done" : headlineIx !== null ? items[headlineIx]!.text : null;
  const rows = todoRows(items, state.showEarlier, state.showLater);

  return (
    <div className="todo-panel" data-open={expanded ? "true" : "false"}>
      <div className="todo-panel-header">
        <button
          type="button"
          className="todo-panel-toggle"
          aria-expanded={expanded}
          aria-label={expanded ? "Collapse todo list" : "Expand todo list"}
          onClick={() => {
            state.expanded = !(state.expanded ?? !finished);
            todoPanelStore.bump();
          }}
        >
          <Icon name={finished ? "check" : "checklist"} size={14} className="todo-panel-glyph" />
          <span className="todo-panel-title">Todo</span>
          <span className="todo-panel-count">
            {summary.done}/{summary.total}
          </span>
          {/* The expanded list names every item; the headline is the
              collapsed state's whole point. */}
          {headline !== null && !expanded ? (
            <span className="todo-panel-headline">{headline}</span>
          ) : (
            <span className="todo-panel-headline-spacer" />
          )}
          <Icon name={expanded ? "altArrowDown" : "altArrowUp"} size={13} className="todo-panel-chevron" />
        </button>
        {/*
          Dismissable whenever the turn is idle, finished or not: a list the
          agent abandoned (an interrupted turn, a cancelled item) must not
          stay pinned forever. The dismissal holds until a different list.
        */}
        {!live ? (
          <Tooltip
            label="Dismiss"
            trigger={
              <button
                type="button"
                className="todo-panel-dismiss"
                aria-label="Dismiss todo list"
                onClick={() => {
                  state.dismissed = todoSignature(items);
                  todoPanelStore.bump();
                }}
              >
                <Icon name="close" size={11} />
              </button>
            }
          />
        ) : null}
      </div>
      {expanded ? (
        <ul className="todo-panel-list">
          {rows.map((row) =>
            row.row === "fold" ? (
              <li key={`fold-${row.side}`}>
                <button
                  type="button"
                  className="todo-panel-fold"
                  aria-label={`${row.open ? "Hide" : "Show"} ${row.count} ${row.side} items`}
                  onClick={() => {
                    if (row.side === "earlier") {
                      state.showEarlier = !state.showEarlier;
                    } else {
                      state.showLater = !state.showLater;
                    }
                    todoPanelStore.bump();
                  }}
                >
                  <Icon
                    name={
                      (row.side === "earlier" && !row.open) || (row.side === "later" && row.open)
                        ? "altArrowUp"
                        : "altArrowDown"
                    }
                    size={12}
                  />
                  <span>
                    {row.count} {row.side}
                  </span>
                </button>
              </li>
            ) : (
              <TodoItemRow key={row.ix} item={items[row.ix]!} live={live} ix={row.ix} />
            ),
          )}
        </ul>
      ) : null}
    </div>
  );
}

/** One checklist row: status glyph + text. Completed work recedes, the
 *  in-progress item is the brightest thing in the list. */
function TodoItemRow({ item, live, ix }: { item: WireTodoItem; live: boolean; ix: number }): ReactNode {
  const status = effectiveTodoStatus(item);
  const glyph =
    status === "completed" ? (
      <Icon name="check" size={12} className="todo-item-glyph todo-item-glyph-done" />
    ) : status === "inProgress" && live ? (
      <GlyphSpinner size={2.5} />
    ) : status === "inProgress" ? (
      // Idle chat: a still ring — a stopped run must not look busy forever.
      <span className="todo-item-ring todo-item-ring-active" />
    ) : (
      <span className="todo-item-ring" />
    );
  return (
    <li id={`todo-item-${ix}`} className={`todo-item todo-item-${status}`}>
      <span className="todo-item-slot">{glyph}</span>
      <span className="todo-item-text">{item.text}</span>
    </li>
  );
}
