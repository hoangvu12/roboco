import type { Chat } from "@roboco/proto";
import type { StagedAttachment } from "../lib/attachments";
import { chatDrafts } from "../lib/composer-draft";

/**
 * Per-side-chat draft state (text + staged attachments) — the web half of
 * the desktop's closed-tab draft retention (`side_chats.rs`: a tab closed
 * with a draft keeps its `SideChatTab`, and reopening restores it).
 *
 * The desktop keeps the whole side-chat entity (state + transcript +
 * composer) alive while a draft exists. The web's composer state lives in
 * its component tree, so the retention seam is a module-scoped mirror keyed
 * by chat id: the surface's composer pushes its live draft here on every
 * change (`onDraftChange`), and a remounting surface seeds its staged set
 * from here (text seeds through the shared `chatDrafts` map, exactly like
 * the main chat's). Closing a tab keeps its entity when the mirror holds
 * content; the pane closing or switching chats unmounts the surface, but
 * the draft survives here for the reopen — the same guarantee, without
 * keeping hidden composers mounted.
 *
 * Staged review comments need no mirror: they already live in the shared
 * `reviewCommentStore` keyed by chat id and survive unmounts on their own
 * (the desktop's `has_draft` counts them too — see
 * `sideChatHasDraft`'s registration in the surface registry).
 */
export interface SideChatDraft {
  readonly text: string;
  readonly staged: readonly StagedAttachment[];
}

export class SideChatDraftStore {
  readonly #byChat = new Map<string, SideChatDraft>();

  /** The draft recorded for a side chat (empty-shaped when none). */
  get(chatId: string): SideChatDraft {
    return this.#byChat.get(chatId) ?? { text: "", staged: [] };
  }

  /**
   * Record the composer's live draft. An empty draft leaves the map (no
   * tombstones): the map stays the size of the user's actual drafts.
   */
  set(chatId: string, draft: SideChatDraft): void {
    if (draft.text.length === 0 && draft.staged.length === 0) {
      this.#byChat.delete(chatId);
      return;
    }
    this.#byChat.set(chatId, draft);
  }

  /** Whether the mirrored draft holds anything a close would lose. */
  hasContent(chatId: string): boolean {
    const draft = this.#byChat.get(chatId);
    return draft !== undefined && (draft.text.trim() !== "" || draft.staged.length > 0);
  }

  /** Drop the draft — the chat was deleted (the prune path). */
  clear(chatId: string): void {
    this.#byChat.delete(chatId);
  }

  /** Test seam — drop every mirrored draft. */
  reset(): void {
    this.#byChat.clear();
  }
}

/** The one side-chat draft map for this page load. */
export const sideChatDrafts = new SideChatDraftStore();

/**
 * Freshly created side chats, keyed by (scoped) chat id — the web half of
 * the desktop's `pending_side_chat` (state.rs:1881): the RPC reply (or the
 * mint) knows the row before the registry's watch frame carries it, and a
 * surface that mounts in that window renders from the seed instead of
 * flashing the picker. Entries live until the fleet row lands (the
 * snapshot wins by construction) or the entity is pruned.
 */
const pendingSideChats = new Map<string, Chat>();

/*
 * Hand-started side chats whose first send has yet to mint them — the web
 * half of the desktop's `unsaved_side_chat` (state.rs, upstream #568).
 * "New side chat" opens a tab on a row that exists only here: it opens no
 * doc watch and writes no registry row; its first send runs `Mutate
 * createChat` before the run (`mintUnsavedSideChat`); closing it unsent
 * drops it, draft or not, since no row could reopen it. The map is module
 * state like the seed map: it must outlive whichever surface happens to be
 * mounted.
 */
const unsavedSideChats = new Map<string, Chat>();
const unsavedListeners = new Set<() => void>();

function notifyUnsavedListeners(): void {
  for (const listener of unsavedListeners) {
    listener();
  }
}

/** Subscribe to unsaved-side-chat changes (the saved flip re-renders). */
export function subscribeUnsavedSideChats(listener: () => void): () => void {
  unsavedListeners.add(listener);
  return () => {
    unsavedListeners.delete(listener);
  };
}

/** Register a locally minted side chat row awaiting its first send. */
export function beginUnsavedSideChat(chat: Chat): void {
  unsavedSideChats.set(chat.id, chat);
  notifyUnsavedListeners();
}

/** The local-only row for a side chat whose first send has yet to mint it. */
export function unsavedSideChat(chatId: string): Chat | null {
  return unsavedSideChats.get(chatId) ?? null;
}

/** Whether `chatId` is a hand-started side chat nothing has written yet. */
export function isUnsavedSideChat(chatId: string): boolean {
  return unsavedSideChats.has(chatId);
}

/**
 * `completion_workspace_params` (composer.rs, upstream #588): an unsaved
 * side chat has no engine row until its first send mints it, so completion
 * discovery — file mentions (`SearchFiles`) and the command/skill catalogs
 * (`ListCommands`/`ListSkills`) — must address the PARENT chat in the
 * meantime, the row the checkout was inherited from. The inherited cwd and
 * device still come from the local row. A saved chat, a top-level one, or
 * the new-chat canvas keeps its own id (the empty string for the canvas).
 */
export function completionTargetChatId(chat: Chat): string {
  const parent = chat.parentChatId ?? null;
  return chat.id !== "" && parent !== null && isUnsavedSideChat(chat.id) ? parent : chat.id;
}

/**
 * The unsaved side chat now exists (`side_chat_saved`, state.rs): retire
 * the unsaved flag — a close-with-draft keeps it from here on — and keep
 * the row as a creation seed until the registry frame lands it (the fork
 * flow's shape).
 */
export function markSideChatSaved(chatId: string): void {
  const chat = unsavedSideChats.get(chatId);
  if (chat === undefined) {
    return;
  }
  unsavedSideChats.delete(chatId);
  if (!pendingSideChats.has(chatId)) {
    pendingSideChats.set(chatId, chat);
  }
  notifyUnsavedListeners();
}

/** Drop an unsaved side chat for good — closed unsent (nothing reopens it). */
export function dropUnsavedSideChat(chatId: string): void {
  if (unsavedSideChats.delete(chatId)) {
    pendingSideChats.delete(chatId);
    notifyUnsavedListeners();
  }
}

/** Seed a just-created side chat's row (scoped id on the key). */
export function seedPendingSideChat(chat: Chat): void {
  pendingSideChats.set(chat.id, chat);
}

/** The seeded row for a side chat whose fleet row has not landed yet. */
export function pendingSideChat(chatId: string): Chat | null {
  return pendingSideChats.get(chatId) ?? null;
}

/** Drop the seed — the row landed, or the chat is gone for good. */
export function clearPendingSideChat(chatId: string): void {
  pendingSideChats.delete(chatId);
  // The row landed (or the chat is gone for good) — an unsaved registration
  // cannot outlive the row it was waiting for.
  if (unsavedSideChats.delete(chatId)) {
    notifyUnsavedListeners();
  }
}

/**
 * Whether closing a side chat would lose a draft — the desktop's
 * `composer.has_draft` (text, staged attachments, staged comments; the web
 * composer has no appshots to count). The text mirror rides
 * `sideChatDrafts`; the comment count reads the shared review-comment
 * store, so only its accessor is injected here (keeping this module free
 * of the comment store's watch wiring).
 */
export function sideChatHasDraft(
  chatId: string,
  stagedComments: (chatId: string) => number = () => 0,
): boolean {
  return sideChatDrafts.hasContent(chatId) || chatDrafts.get(chatId).trim() !== "" || stagedComments(chatId) > 0;
}
