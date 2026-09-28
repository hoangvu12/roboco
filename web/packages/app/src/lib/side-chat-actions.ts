import { methods } from "@roboco/engine-client";
import type { Chat } from "@roboco/proto";
import { createChat, type MutateCaller } from "./chat-actions";
import { mintId } from "./id";

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
 *   agent-spawned and hand-started side chats list together.
 *
 * The one-at-a-time guard (`side_chat_creating`) is module state like the
 * desktop's shell field: creation is async and a double click must not mint
 * two children.
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

/** Options for {@link createChildChat} beyond the parent row itself. */
export interface CreateChildChatOptions {
  /** Id factory — client-minted like the desktop's `Uuid::new_v4`. */
  readonly mintId?: () => string;
}

/**
 * A fresh, empty side chat under `parent` (the active chat), inheriting its
 * host device, space, config, ref and cwd — the desktop's `create_child_chat`
 * field copy. Returns the minted chat id (the mutation is idempotent by it,
 * so an optimistic retry never duplicates). The projectless `"~"` cwd never
 * rides the wire: it is the engine's own default, and it lives on the
 * `RunRequest` per the composer's rule.
 */
export async function createChildChat(
  caller: MutateCaller,
  parent: Chat,
  options: CreateChildChatOptions = {},
): Promise<string> {
  const cwd = parent.cwd !== null && parent.cwd !== "~" ? parent.cwd : undefined;
  return createChat(caller, {
    // The parent's space decides the host device; a projectless parent
    // names its own device outright.
    ...(parent.spaceId != null ? { spaceId: parent.spaceId } : { deviceId: parent.deviceId }),
    ...(cwd !== undefined ? { cwd } : {}),
    ...(parent.branch !== null ? { branch: parent.branch } : {}),
    ...(parent.config !== null ? { config: parent.config } : {}),
    parentChatId: parent.id,
    mintId: options.mintId,
  });
}

/*
 * The one-at-a-time guard (`side_chat_creating`, side_chats.rs:92/127): both
 * creators set it before the RPC and clear it when the spawn callback lands.
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
