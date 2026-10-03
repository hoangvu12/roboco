/**
 * The web shape of the desktop's `composer.add_workspace_path` (the shared
 * chat dropzone + the tree's Add to chat): a pending workspace reference,
 * keyed by chat id, that the composer of that chat applies to its draft.
 *
 * The desktop routes the insert through the shell (`attach_workspace_drag`
 * with origin checks); the web's tree panel already lives inside the chat
 * pane that will receive the reference, so a pending-mention hand-off is
 * the honest equivalent. Module-scoped so the insert survives the composer
 * unmounting between mounts (the `chatDrafts` pattern).
 */
export interface PendingChatFile {
  readonly path: string;
  readonly isDirectory: boolean;
}

class ChatFileInsertStore {
  readonly #pending = new Map<string, PendingChatFile>();
  readonly #listeners = new Set<() => void>();
  #version = 0;

  /** Queue a reference for the chat's composer to insert. */
  insert(chatId: string, file: PendingChatFile): void {
    this.#pending.set(chatId, file);
    this.#version += 1;
    for (const listener of this.#listeners) {
      listener();
    }
  }

  /** The composer consumes its pending reference (once). */
  take(chatId: string): PendingChatFile | null {
    const pending = this.#pending.get(chatId) ?? null;
    if (pending !== null) {
      this.#pending.delete(chatId);
    }
    return pending;
  }

  version(): number {
    return this.#version;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }
}

/** The one pending-insert map for this page load. */
export const chatFileInserts = new ChatFileInsertStore();
