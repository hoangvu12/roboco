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
