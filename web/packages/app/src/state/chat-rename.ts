import { useSyncExternalStore } from "react";

/**
 * The single inline chat rename — the web peer of the desktop's
 * `Shell::chat_rename` (shell.rs): at most one chat title is edited in
 * place at a time, opened by a row double-click, the chat menu's Rename
 * row, or the composer's `/rename` command. The row that matches the id
 * swaps its title for the field (`InlineChatTitleEditor`); Enter or blur
 * commits, Escape cancels.
 *
 * Module-level (like `sidebarNotice`) because the triggers live in other
 * trees than the rows: the chat page's `/rename` dispatch, the side-chat
 * pane, and the context menu all reach the sidebar/explorer rows through
 * this store.
 */
class ChatRenameStore {
  #chatId: string | null = null;
  readonly #listeners = new Set<() => void>();

  getSnapshot(): string | null {
    return this.#chatId;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /** Start (or switch to) the inline rename of `chatId`. */
  begin(chatId: string): void {
    if (chatId === this.#chatId) {
      return;
    }
    this.#chatId = chatId;
    for (const listener of this.#listeners) {
      listener();
    }
  }

  /** The rename ended (committed, cancelled, or the chat vanished). */
  end(): void {
    if (this.#chatId === null) {
      return;
    }
    this.#chatId = null;
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

export const chatRenameStore = new ChatRenameStore();

const subscribe = (listener: () => void) => chatRenameStore.subscribe(listener);
const getSnapshot = () => chatRenameStore.getSnapshot();

/** The chat whose title is being renamed in place, if any. */
export function useChatRenameId(): string | null {
  return useSyncExternalStore(subscribe, getSnapshot);
}
