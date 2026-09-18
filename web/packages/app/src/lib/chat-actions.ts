import { methods } from "@roboco/engine-client";
import type { WatchCacheSnapshot } from "@roboco/engine-client";

/**
 * The chat-management surface of the `Mutate` RPC (crates/ui/src/shell.rs
 * sidebar mutations): create, rename, archive, delete. Every op lands on
 * the connected engine; failures surface in the sidebar notice strip, so
 * callers only get a rejected promise to describe.
 *
 * Functions take the minimal caller shape so tests drive them without a
 * socket; `EngineClient` satisfies it.
 */

export interface MutateCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/** Where a new chat lands: a space fixes host device + cwd; else the device. */
export interface CreateChatTarget {
  readonly spaceId?: string;
  readonly deviceId?: string;
}

export interface CreateChatOptions extends CreateChatTarget {
  /** Id factory — client-minted like the desktop's `Uuid::new_v4`. */
  readonly mintId?: () => string;
}

function defaultMintId(): string {
  return crypto.randomUUID();
}

/**
 * Create a chat and return its id. The engine writes the row immediately
 * (workspace_host create_chat is idempotent — a retry never duplicates).
 */
export async function createChat(caller: MutateCaller, options: CreateChatOptions = {}): Promise<string> {
  const chatId = (options.mintId ?? defaultMintId)();
  await caller.call(methods.MUTATE, {
    op: "createChat",
    chatId,
    ...(options.spaceId !== undefined ? { spaceId: options.spaceId } : {}),
    ...(options.spaceId === undefined && options.deviceId !== undefined ? { deviceId: options.deviceId } : {}),
  });
  return chatId;
}

/**
 * Rename a chat. An empty (whitespace-only) title is a no-op, mirroring the
 * desktop's submit_rename_chat. Returns whether a mutation was sent.
 */
export async function renameChat(caller: MutateCaller, chatId: string, title: string): Promise<boolean> {
  const trimmed = title.trim();
  if (trimmed.length === 0) {
    return false;
  }
  await caller.call(methods.MUTATE, { op: "renameChat", chatId, title: trimmed });
  return true;
}

/** Archive or unarchive a chat. Archiving never closes an open chat. */
export async function setChatArchived(caller: MutateCaller, chatId: string, archived: boolean): Promise<void> {
  await caller.call(methods.MUTATE, { op: "setChatArchived", chatId, archived });
}

/** Permanently delete a chat. */
export async function deleteChat(caller: MutateCaller, chatId: string): Promise<void> {
  await caller.call(methods.MUTATE, { op: "deleteChat", chatId });
}

/**
 * The local half of "this chat has been read" — the optimistic stamp the
 * desktop's `mark_chat_seen` writes before it touches the wire. Keyed by chat
 * id, epoch-ms. Module-scoped like the desktop's `AppState` field: it has to
 * outlive whichever chat page happens to be mounted.
 */
const seenAt = new Map<string, number>();

/** The optimistic `lastSeenAt` for a chat, or null if it has not been stamped. */
export function chatSeenAt(chatId: string): number | null {
  return seenAt.get(chatId) ?? null;
}

/**
 * Mark a chat read (`state.rs`'s `mark_chat_seen`).
 *
 * Idempotent — a chat already stamped at or after `nowMs` is a no-op, so the
 * repeated calls a chat page makes while it is open cost nothing. The stamp
 * lands LOCALLY first and is never rolled back: the `Mutate` is
 * fire-and-forget, and a dropped mutation must not make a chat the user
 * plainly looked at flash unread again. Returns whether a mutation was sent.
 */
export function markChatSeen(caller: MutateCaller, chatId: string, nowMs = Date.now()): boolean {
  const previous = seenAt.get(chatId);
  if (previous !== undefined && previous >= nowMs) {
    return false;
  }
  seenAt.set(chatId, nowMs);
  void caller.call(methods.MUTATE, { op: "markChatSeen", chatId }).catch(() => {
    // Fire-and-forget: the optimistic stamp stands either way.
  });
  return true;
}

/** Test seam — drops every optimistic seen stamp. */
export function resetChatSeen(): void {
  seenAt.clear();
}

/** The notice-strip text for a failed mutation (desktop shows `{err}`). */
export function describeMutateError(error: unknown): string {
  return error instanceof Error ? error.message : "The change could not be applied.";
}

interface ChatRowSource {
  getSnapshot(): WatchCacheSnapshot;
  subscribe(listener: () => void): () => void;
}

/**
 * Best-effort wait for a freshly created chat to arrive on the watch
 * stream before navigating to it, so the chat page never flashes its
 * not-found state. Resolves false on timeout — the caller navigates anyway.
 */
export function waitForChatRow(cache: ChatRowSource, chatId: string, timeoutMs = 5_000): Promise<boolean> {
  if (cache.getSnapshot().chats.rows.some((row) => row.id === chatId)) {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const unsubscribe = cache.subscribe(() => {
      if (cache.getSnapshot().chats.rows.some((row) => row.id === chatId)) {
        finish(true);
      }
    });
    const timer = setTimeout(() => finish(false), timeoutMs);
    function finish(found: boolean): void {
      clearTimeout(timer);
      unsubscribe();
      resolve(found);
    }
  });
}
