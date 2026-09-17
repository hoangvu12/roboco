import type { ChatConfig, HarnessId, ReasoningLevel, RunRequest, SandboxLevel } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import type { EngineClient } from "@roboco/engine-client";
import { describeMutateError } from "./chat-actions";
import type { StagedAttachment, UploadedAttachment } from "./attachments";
import { uploadAttachments, withAttachments } from "./attachments";

/**
 * The composer's working draft — what the user has picked for the next send.
 * Mirrors `ChatConfig` plus the `modelOptions` field the harness options picker
 * mutates separately. The desktop calls this `DraftConfig` (`crates/ui/src/pickers.rs`).
 *
 * `harness` is locked once a chat has a non-null `ChatConfig`; the picker
 * chip dims for existing chats. The web keeps the same UX: when the chat row
 * already carries a `ChatConfig`, harness changes are gated behind the user
 * opening the harness picker explicitly (the desktop's picker UI greys the
 * rail — for the web v1 we just render the picker inert).
 */
export interface DraftConfig {
  readonly harness: HarnessId;
  readonly model: string | null;
  readonly reasoning: ReasoningLevel | null;
  readonly sandbox: SandboxLevel;
  readonly modelOptions: Readonly<Record<string, unknown>>;
}

/**
 * What a picker may change. `sandbox` is deliberately absent: the desktop
 * writes `SandboxLevel::WorkspaceWrite` when the chat is created and preserves
 * it thereafter — it is never a user choice, so no update can carry it.
 */
export interface DraftConfigUpdate {
  harness?: HarnessId;
  model?: string | null;
  reasoning?: ReasoningLevel | null;
  modelOptions?: Record<string, unknown>;
}

export function buildChatConfig(draft: DraftConfig): ChatConfig {
  return {
    harness: draft.harness,
    model: draft.model,
    reasoning: draft.reasoning,
    sandbox: draft.sandbox,
    modelOptions: { ...draft.modelOptions },
  };
}

/**
 * Shape of a Run payload the engine accepts (the wire's `RunRequest`).
 *
 * The message id is deliberately NOT a parameter here: `RunRequest` carries no
 * id field on the wire (`crates/proto/src/agent.rs:94-128`). The id rides the
 * command envelope — `SessionCommandPayload::Run { request, message_id }`
 * (`crates/doc/src/commands.rs:42-46`) — and that is the id the host writes the
 * user entry under (`doc_host.rs:326-349`), so it is the only one worth
 * threading. This function used to take a `messageId` it never read, which is
 * what hid the three-ids bug in `sendRun` below.
 */
export function buildRunRequest(draft: DraftConfig, prompt: string, cwd: string): RunRequest {
  const request: RunRequest = {
    prompt,
    harness: draft.harness,
    model: draft.model,
    reasoning: draft.reasoning,
    modelOptions: { ...draft.modelOptions },
    cwd,
    sandbox: draft.sandbox,
    autoApprove: false,
    resume: null,
  };
  return request;
}

/** The minimal caller shape — `EngineClient` satisfies it. */
export interface CommandCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/** Result of a successful Send: the message id the engine will claim for the user bubble. */
export interface SendResult {
  readonly messageId: string;
  readonly commandId: string;
  /** The host-resolved paths the engine reports for each uploaded attachment
   *  (in send order). The web mirror of the desktop's `attachment_paths` —
   *  exposed so the strip can hand them to `seedAttachment` for instant
   *  bubble rendering. */
  readonly attachmentPaths: readonly string[];
}

/** Optional inputs for send. `stagedAttachments` is the bytes the user
 *  dropped/picked into the composer — the sender uploads them, embeds the
 *  refs in the prompt, and populates `transfers` so the chat's host device
 *  knows about them. `uploadProgress` is called per-chunk. */
export interface SendAttachmentsOptions {
  readonly stagedAttachments?: readonly StagedAttachment[];
  readonly uploadProgress?: (uploadedBytes: number, totalBytes: number) => void;
}

/** Mint a client-side message id (the optimistic-echo dedupe key). */
export function mintMessageId(mint: () => string = defaultMint): string {
  return mint();
}

function defaultMint(): string {
  return crypto.randomUUID();
}

/**
 * Send a message to the harness: one `QueueCommand` with a Run payload.
 * Returns the message id the engine will claim for the user bubble.
 *
 * No `Mutate setChatConfig` rides ahead of it. The desktop carries
 * model/reasoning/options ON the `RunRequest` itself (which `buildRunRequest`
 * already does); only a genuinely NEW chat persists a `ChatConfig`, via
 * `Mutate createChat`.
 *
 * When `stagedAttachments` is non-empty, the caller uploads the bytes to
 * the chat's host device first, folds the returned paths into the prompt
 * (via [`withAttachments`]), and ships the upload identity as
 * `transfers` on the `QueueCommand` call. The engine rewrites the
 * `pending://` refs to durable paths on dispatch.
 */
export async function sendRun(
  caller: CommandCaller,
  chatId: string,
  draft: DraftConfig,
  prompt: string,
  chatCwd: string | null,
  options: { mintMessageId?: () => string; currentConfig?: ChatConfig | null } = {},
  attachments: SendAttachmentsOptions = {},
): Promise<SendResult> {
  const trimmed = prompt.trim();
  const staged = attachments.stagedAttachments ?? [];
  const hasContent = trimmed.length > 0 || staged.length > 0;
  if (!hasContent) {
    throw new Error("Cannot send an empty message");
  }
  if (chatCwd === null || chatCwd.trim().length === 0) {
    throw new Error("This chat has no working directory yet");
  }
  // ONE id per send, minted once and stored. It is the dedupe key shared by
  // the command envelope, the entry the host writes back, the caller's
  // optimistic echo and any failure cleanup — three separate `messageId()`
  // calls used to produce three unrelated uuids, so nothing downstream could
  // ever say "this specific sent message".
  const messageId = (options.mintMessageId ?? defaultMint)();
  const uploaded: readonly UploadedAttachment[] = await uploadStage(
    caller,
    staged,
    attachments.uploadProgress,
  );
  const finalPrompt = withAttachments(trimmed, uploaded.map((entry) => entry.path));
  const command = {
    kind: "run" as const,
    request: buildRunRequest(draft, finalPrompt, chatCwd),
    messageId,
  };
  const reply = (await caller.call(methods.QUEUE_COMMAND, {
    chatId,
    command,
    transfers: uploaded.map((entry) => ({ uploadId: entry.uploadId, fileName: entry.fileName })),
  })) as { commandId: string };
  return {
    messageId,
    commandId: reply.commandId,
    attachmentPaths: uploaded.map((entry) => entry.path),
  };
}

/** Upload staged attachments to the chat's host device. Returns `[]`
 *  for an empty stage (the common case). */
async function uploadStage(
  caller: CommandCaller,
  staged: readonly StagedAttachment[],
  progress: ((uploaded: number, total: number) => void) | undefined,
): Promise<readonly UploadedAttachment[]> {
  if (staged.length === 0) {
    return [];
  }
  return uploadAttachments(caller, staged, progress ?? null);
}

/** Steer the live run with a new prompt (only when the harness supports it). */
export async function sendSteer(
  caller: CommandCaller,
  chatId: string,
  prompt: string,
  messageId: string | null = null,
): Promise<void> {
  const trimmed = prompt.trim();
  if (trimmed.length === 0) {
    throw new Error("Cannot steer with an empty prompt");
  }
  await caller.call(methods.QUEUE_COMMAND, {
    chatId,
    command: { kind: "steer", prompt: trimmed, messageId },
    transfers: [],
  });
}

/** Interrupt the live run. No payload — the engine knows what to stop. */
export async function sendInterrupt(caller: CommandCaller, chatId: string): Promise<void> {
  await caller.call(methods.QUEUE_COMMAND, {
    chatId,
    command: { kind: "interrupt" },
    transfers: [],
  });
}

/**
 * The composer's only place where ChatConfig drift lands on the server — every
 * mutation flows through here, so chip updates and chat-row repaints stay in
 * sync. Skipped when the chat row already matches (the common case on send).
 */
export async function persistChatConfig(
  caller: CommandCaller,
  chatId: string,
  draft: DraftConfig,
): Promise<void> {
  await caller.call(methods.MUTATE, {
    op: "setChatConfig",
    chatId,
    config: buildChatConfig(draft),
  });
}

/**
 * Retained, not wired: `sendRun` no longer mutates the chat's config before
 * every send (ticket 04 — the desktop only writes one on `Mutate createChat`).
 * `chat-actions.ts::createChat` writes no config today, so this is still the
 * right mechanism for the genuinely-new-chat write once that path is built
 * (tickets 13/15). Deliberately left with no call site until then.
 */
async function maybePersistConfig(
  caller: CommandCaller,
  chatId: string,
  draft: DraftConfig,
  current: ChatConfig | null,
): Promise<void> {
  if (current !== null && sameChatConfig(current, buildChatConfig(draft))) {
    return;
  }
  await persistChatConfig(caller, chatId, draft);
}

/** Two ChatConfigs are equivalent when every effective field matches. */
function sameChatConfig(a: ChatConfig, b: ChatConfig): boolean {
  if (a.harness !== b.harness) {
    return false;
  }
  if ((a.model ?? null) !== (b.model ?? null)) {
    return false;
  }
  if ((a.reasoning ?? null) !== (b.reasoning ?? null)) {
    return false;
  }
  if (a.sandbox !== b.sandbox) {
    return false;
  }
  return sameModelOptions(a.modelOptions, b.modelOptions);
}

function sameModelOptions(
  a: Readonly<Record<string, unknown>>,
  b: Readonly<Record<string, unknown>>,
): boolean {
  const aKeys = Object.keys(a);
  const bKeys = Object.keys(b);
  if (aKeys.length !== bKeys.length) {
    return false;
  }
  for (const key of aKeys) {
    if (a[key] !== b[key]) {
      return false;
    }
  }
  return true;
}

/** A session-scoped caller shape — `EngineClient` matches. */
export type { EngineClient };

/** User-facing mutation failure copy (mirrors chat-actions describeMutateError). */
export const describeSendError = describeMutateError;
