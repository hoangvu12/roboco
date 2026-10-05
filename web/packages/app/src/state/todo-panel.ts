import { useSyncExternalStore } from "react";

/**
 * The todo panel's per-chat presentation state — the web peer of the
 * desktop's `Composer::todo_panels` map (`crates/ui/src/todo_panel.rs`):
 * open/fold/dismissal choices live for the APP RUN, in memory, keyed by
 * chat. A tiny subscribe store so the component re-renders when its own
 * interactions mutate the state (the transcript store only fires when the
 * list itself changes).
 */

export interface TodoPanelState {
  /** The user's explicit choice; `null` follows the automatic rule: open
   *  while work remains, compact once everything is done. */
  expanded: boolean | null;
  showEarlier: boolean;
  showLater: boolean;
  /** Signature of the dismissed list; null when nothing was dismissed. */
  dismissed: string | null;
  /** Whether the previous frame was settled (everything done + turn idle). */
  wasSettled: boolean;
}

class TodoPanelStore {
  readonly #states = new Map<string, TodoPanelState>();
  #version = 0;
  readonly #listeners = new Set<() => void>();

  version(): number {
    return this.#version;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /** Notify after a mutation the component must reflect. */
  bump(): void {
    this.#version += 1;
    for (const listener of this.#listeners) {
      listener();
    }
  }

  state(chatId: string): TodoPanelState {
    let state = this.#states.get(chatId);
    if (state === undefined) {
      state = {
        expanded: null,
        showEarlier: false,
        showLater: false,
        dismissed: null,
        wasSettled: false,
      };
      this.#states.set(chatId, state);
    }
    return state;
  }
}

export const todoPanelStore = new TodoPanelStore();

const subscribe = (listener: () => void) => todoPanelStore.subscribe(listener);
const getSnapshot = () => todoPanelStore.version();

/** Re-render hook: fires whenever any panel state mutates. */
export function useTodoPanelVersion(): number {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
