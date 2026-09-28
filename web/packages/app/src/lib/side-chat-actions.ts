import { methods } from "@roboco/engine-client";
import type { Chat, ChatConfig } from "@roboco/proto";
import { createChat, type MutateCaller } from "./chat-actions";

/**
 * Side-chat creation on the wire — the RPC half of the desktop's
 * `Shell::create_side_chat` / `Shell::create_child_chat`
 * (crates/ui/src/shell/side_chats.rs, 731697b6).
 *
 * Two shapes, exactly like the desktop:
 *
 * - **fork** (`FORK_SIDE_CHAT`): the engine copies the source's transcript
 *   through its latest completed response, stamps the `Forked from` seam
 *   part, and returns the fresh `Chat` row. Idempotent by chat id.
 * - **fresh child** (`Mutate createChat` with `parentChatId`): an empty chat
 *   hanging under the parent, inheriting its device/space/config/branch —
 *   the same shape the Roboco MCP server's `create_chat` mints, so
 *   agent-spawned and hand-started side chats list together. Since upstream
 *   #568 the hand-started half is LOCAL-ONLY until its first send
 *   ([`mintUnsavedSideChat`]): the tab opens on a locally minted row and the
 *   createChat lands on the send, not on the open.
 *
 * The one-at-a-time guard (`side_chat_creating`) is module state like the
 * desktop's shell field: the fork's creation is async and a double click
 * must not mint two children (the fresh child opens instantly — nothing is
 * in flight to guard).
 */

/**
 * `FORK_SIDE_CHAT` params — `{chatId, sourceChatId, parentChatId?,
 * targetDeviceId?}`. The engine defaults `parentChatId` to the source; a
 * side chat's own fork button passes the side chat's parent so the copy
 * lists as a sibling. `targetDeviceId` rides for request routing like the
 * desktop's send (the engine itself checks the source lives on it).
 */
export interface ForkSideChatParams {
  /** The minted id of the fork — idempotent by this key. */
  readonly chatId: string;
  /** The conversation the history is copied from. */
  readonly sourceChatId: string;
  /** Where the fork hangs; defaults to the source (the engine's rule). */
  readonly parentChatId?: string;
  /** The source's host device — forwarded-routing metadata, like the Rust. */
  readonly targetDeviceId?: string;
}

/**
 * Fork `sourceChatId` through its latest completed response into a new chat
 * under `parentChatId`, and get the fresh `Chat` back. The engine rejects
 * with "Wait for a completed response before starting a side chat" when the
 * source has no completed turn yet — callers surface that verbatim (the
 * desktop shows it in the composer).
 */
export async function forkSideChat(
  caller: MutateCaller,
  params: ForkSideChatParams,
): Promise<Chat> {
  return caller.call<Chat>(methods.FORK_SIDE_CHAT, {
    chatId: params.chatId,
    sourceChatId: params.sourceChatId,
    ...(params.parentChatId !== undefined ? { parentChatId: params.parentChatId } : {}),
    ...(params.targetDeviceId !== undefined ? { targetDeviceId: params.targetDeviceId } : {}),
  });
}

/**
 * Mint an UNSAVED side chat on its first send — the wire half of the
 * desktop's `unsaved_side_chat_create` (state.rs, upstream #568). The tab
 * already keys on `chatId` (locally minted; scoped or raw — the wire decodes
 * either form), so the mutation is idempotent by it. The parent link,
 * inherited device/space/branch/cwd come from the local row (the parent's
 * field copy); `config` is the one picked meanwhile — the LIVE composer
 * draft (the desktop stamps the pending copy through `apply_chat_config`,
 * which is the draft's job here). A `null` config rides nothing: the engine
 * resolves its default, like the parent-less `create_chat`.
 */
export async function mintUnsavedSideChat(
  caller: MutateCaller,
  chat: Chat,
  chatId: string,
  config: ChatConfig | null,
): Promise<void> {
  const cwd = chat.cwd !== null && chat.cwd !== "~" ? chat.cwd : undefined;
  await createChat(caller, {
    ...(chat.spaceId != null ? { spaceId: chat.spaceId } : { deviceId: chat.deviceId }),
    ...(cwd !== undefined ? { cwd } : {}),
    ...(chat.branch !== null ? { branch: chat.branch } : {}),
    ...(config !== null ? { config } : {}),
    ...(chat.parentChatId !== null ? { parentChatId: chat.parentChatId } : {}),
    mintId: () => chatId,
  });
}

/*
 * The one-at-a-time guard (`side_chat_creating`, side_chats.rs:92/127): the
 * FORK arms it before its RPC and clears it when the spawn callback lands
 * (upstream #568 removed the fresh child's arming — nothing is in flight).
 * `begin` returns false (and does NOT arm) while one is in flight.
 */
let creating = false;

/** Arm the guard; false means a creation is already in flight. */
export function beginSideChatCreate(): boolean {
  if (creating) {
    return false;
  }
  creating = true;
  return true;
}

/** Clear the guard — the creation settled (either way). */
export function endSideChatCreate(): void {
  creating = false;
}

/** Whether a side-chat creation is in flight (header buttons dim on this). */
export function sideChatCreating(): boolean {
  return creating;
}

/** Test seam — drop the guard. */
export function resetSideChatCreate(): void {
  creating = false;
}
