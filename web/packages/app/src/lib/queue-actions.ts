import { methods } from "@roboco/engine-client";
import type { EngineClient } from "@roboco/engine-client";
import type {
  BeginQueueEditOutcome,
  ChangedReply,
  FinishQueueEditOutcome,
  FinishQueuedMessageEditAction,
  QueueMessageReply,
  RemovedReply,
  RenewQueueEditOutcome,
  SentReply,
} from "@roboco/proto";
import { describeMutateError } from "./chat-actions";
import { mintId } from "./id";

/**
 * The message-queue RPC surface — web peer of `crates/engine/rpc.rs` §3.5.
 * Every mutation returns an explicit ack (`changed`, `removed`, `sent`) so a
 * racing device's row never silently moves; a `false` ack is a real "another
 * device got there first" outcome the caller surfaces, never a silent retry.
 *
 * Edit leases are host-authoritative: `BeginQueuedMessageEdit` acquires a
 * 60s lease, `RenewQueuedMessageEdit` keeps it alive while editing, and
 * `FinishQueuedMessageEdit` releases it (commit / cancel / discard /
 * releaseUnchanged). An expired lease fails closed into
 * `QueueDeliveryGate::ReviewRequired` — the row is never silently sendable
 * again until the user explicitly reviews it.
 *
 * Functions take the minimal caller shape so tests drive them without a
 * socket; `EngineClient` satisfies it.
 */

export interface QueueCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/** Append a message to the queue; the engine returns the row's id. */
export async function queueMessage(
  caller: QueueCaller,
  chatId: string,
  text: string,
  options: { attachments?: readonly string[]; holdForTurnEnd?: boolean } = {},
): Promise<string> {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    throw new Error("Cannot queue an empty message");
  }
  const reply = (await caller.call(methods.QUEUE_MESSAGE, {
    chatId,
    text: trimmed,
    ...(options.attachments !== undefined && options.attachments.length > 0
      ? { attachments: [...options.attachments] }
      : {}),
    ...(options.holdForTurnEnd === true ? { holdForTurnEnd: true } : {}),
  })) as QueueMessageReply;
  return reply.id;
}

/**
 * Update a queued message's text. Per the wire contract an empty text deletes
 * the row (a single RPC handles both update and remove); we surface the
 * `changed` ack so the caller can tell a real update from a race.
 */
export async function updateQueuedMessage(
  caller: QueueCaller,
  chatId: string,
  id: string,
  text: string,
): Promise<boolean> {
  await caller.call(methods.UPDATE_QUEUED_MESSAGE, { chatId, id, text });
  return true;
}

/** Move a queued row to a new index (drag-reorder). */
export async function moveQueuedMessage(
  caller: QueueCaller,
  chatId: string,
  id: string,
  toIndex: number,
): Promise<boolean> {
  const reply = (await caller.call(methods.MOVE_QUEUED_MESSAGE, { chatId, id, toIndex })) as ChangedReply;
  return reply.changed === true;
}

/** Remove a queued row. Empty text on `UpdateQueuedMessage` is also a remove. */
export async function removeQueuedMessage(caller: QueueCaller, chatId: string, id: string): Promise<boolean> {
  const reply = (await caller.call(methods.REMOVE_QUEUED_MESSAGE, { chatId, id })) as RemovedReply;
  return reply.removed === true;
}

/**
 * Send a queued row now — interrupts the active run (mirrors desktop's "Send
 * now (interrupt)" tooltip). The `sent` ack distinguishes a successful
 * dispatch from a race where another device drained the row first.
 */
export async function sendQueuedMessageNow(caller: QueueCaller, chatId: string, id: string): Promise<boolean> {
  const reply = (await caller.call(methods.SEND_QUEUED_MESSAGE_NOW, { chatId, id })) as SentReply;
  return reply.sent === true;
}

/** Steer the live run with this queued row now (no interrupt). */
export async function steerQueuedMessageNow(caller: QueueCaller, chatId: string, id: string): Promise<boolean> {
  const reply = (await caller.call(methods.STEER_QUEUED_MESSAGE_NOW, { chatId, id })) as SentReply;
  return reply.sent === true;
}

/** Acquire an edit lease on a queued row. The `Editing` gate the engine sets
 *  blocks the row from being delivered until the lease expires or is
 *  released. `acquired` carries the lease id, the snapshot text the editor
 *  must base `expectedTextHash` on, and `expiresAtMs` for the renewal loop.
 *  `locked` means another device/instance holds the lease — the caller shows
 *  "Editing on device X" until expiry.
 */
export async function beginQueuedMessageEdit(
  caller: QueueCaller,
  chatId: string,
  id: string,
  editorDeviceId: string,
  editorInstanceId: string,
): Promise<BeginQueueEditOutcome> {
  return (await caller.call(methods.BEGIN_QUEUED_MESSAGE_EDIT, {
    chatId,
    id,
    editorDeviceId,
    editorInstanceId,
  })) as BeginQueueEditOutcome;
}

/** Renew the lease; `renewed` carries the new expiry, `lost`/`missing` mean
 *  the lease has expired or the row vanished and the caller must surface the
 *  conflict (the row now carries a `ReviewRequired` gate). */
export async function renewQueuedMessageEdit(
  caller: QueueCaller,
  chatId: string,
  id: string,
  leaseId: string,
): Promise<RenewQueueEditOutcome> {
  return (await caller.call(methods.RENEW_QUEUED_MESSAGE_EDIT, {
    chatId,
    id,
    leaseId,
  })) as RenewQueueEditOutcome;
}

export interface FinishQueuedMessageEditOptions {
  /** The new text the editor typed (omitted for cancel/discard/release). */
  readonly text?: string;
  /** The baseTextHash returned by Begin; required on commit. */
  readonly expectedTextHash?: string;
  /** Staged attachment paths; mirrors `UpdateQueuedMessage`'s shape. */
  readonly attachments?: readonly string[];
}

/**
 * Close an edit: commit (text was changed), cancel (close without saving —
 * the row keeps its prior text), discard (drop the row entirely), or
 * releaseUnchanged (close without ever typing — text untouched). `conflict`
 *  means the row drifted under us and the caller must decide whether to
 *  overwrite, merge, or discard.
 */
export async function finishQueuedMessageEdit(
  caller: QueueCaller,
  chatId: string,
  id: string,
  leaseId: string,
  action: FinishQueuedMessageEditAction,
  options: FinishQueuedMessageEditOptions = {},
): Promise<FinishQueueEditOutcome> {
  const params: Record<string, unknown> = { chatId, id, leaseId, action };
  if (options.text !== undefined) {
    params["text"] = options.text;
  }
  if (options.expectedTextHash !== undefined) {
    params["expectedTextHash"] = options.expectedTextHash;
  }
  if (options.attachments !== undefined && options.attachments.length > 0) {
    params["attachments"] = [...options.attachments];
  }
  return (await caller.call(methods.FINISH_QUEUED_MESSAGE_EDIT, params)) as FinishQueueEditOutcome;
}

/**
 * The engine instance id the web client advertises for an edit lease.
 * Client-minted per edit so two tabs/panes on the same device never share
 * a lease by accident.
 */
export function mintEditorInstanceId(): string {
  return mintId();
}

// ── Edit-lease failure copy (the desktop's `Composer::failure` arms) ────
//
// On the desktop every edit-lease failure lands in `Composer::failure` —
// the red notice chip above the composer pill (`queue.rs:1458-1470` begin,
// `:1557-1636` finish, `:1680-1685` renewal). The strings live here,
// placement-agnostic: the mappers key on the store's normalized outcome
// kinds (`BeginLeaseOutcome`/`FinishLeaseOutcome`, state/queue-store.ts),
// and the callers route the copy to the composer's chat-scoped failure
// channel — never a sidebar toast.

/**
 * The begin-edit failure copy (queue.rs:1458-1470): `locked` → another
 * device holds the lease, `missing` → the row left the queue. `acquired`
 * returns null — the edit proceeds, no notice. The overloads carry which
 * kinds are failure arms, so a `kind !== "acquired"` guard both narrows
 * the outcome and yields a non-null message.
 */
export function describeBeginEditFailure(kind: "acquired"): null;
export function describeBeginEditFailure(kind: "locked" | "missing"): string;
export function describeBeginEditFailure(kind: "acquired" | "locked" | "missing"): string | null;
export function describeBeginEditFailure(
  kind: "acquired" | "locked" | "missing",
): string | null {
  switch (kind) {
    case "locked":
      return "That queued message is being edited on another device";
    case "missing":
      return "That queued message is no longer available";
    default:
      return null;
  }
}

/**
 * The finish-edit failure copy (queue.rs:1609-1634): `conflict`/`missing`
 * keep the edit locally, the unknown arm (`lost`) keeps the text in the
 * editor. The four terminal arms return null — the edit closed cleanly.
 */
export function describeFinishEditFailure(
  kind: "committed" | "cancelled" | "discarded" | "released",
): null;
export function describeFinishEditFailure(kind: "conflict" | "lost" | "missing"): string;
export function describeFinishEditFailure(
  kind: "committed" | "cancelled" | "discarded" | "released" | "conflict" | "lost" | "missing",
): string | null;
export function describeFinishEditFailure(
  kind: "committed" | "cancelled" | "discarded" | "released" | "conflict" | "lost" | "missing",
): string | null {
  switch (kind) {
    case "conflict":
      return "This message changed on another device; your edit was kept locally";
    case "missing":
      return "The queued message was removed; your edit was kept locally";
    case "lost":
      return "The edit lease changed; your text is still in the editor";
    default:
      return null;
  }
}

/**
 * The renewal failure copy (queue.rs:1680-1685): a non-renewed lease means
 * the row's protection expired and now needs review. `renewed` returns
 * null — the heartbeat keeps going.
 */
export function describeRenewEditFailure(kind: "renewed"): null;
export function describeRenewEditFailure(kind: "lost" | "missing"): string;
export function describeRenewEditFailure(kind: "renewed" | "lost" | "missing"): string | null;
export function describeRenewEditFailure(
  kind: "renewed" | "lost" | "missing",
): string | null {
  return kind === "renewed" ? null : "Edit protection expired; review this message before sending";
}

/** Finish with no local lease state (queue.rs:1559) — the arm the web used
 *  to swallow silently: the desktop's `let ... else` raises this instead of
 *  returning quietly, and the text stays in the editor. */
export const QUEUE_EDIT_LEASE_LOST_MESSAGE =
  "The edit lease was lost; your text is still in the editor";

/** Begin's transport failure (queue.rs:1468). */
export const QUEUE_EDIT_CONNECT_MESSAGE = "Connect to the chat host to edit this message";

/** Finish's transport failure (queue.rs:1636). */
export const QUEUE_EDIT_UNREACHABLE_MESSAGE =
  "Couldn't reach the chat host; your edit is still in the editor";

/** User-facing mutation failure copy (mirrors chat-actions describeMutateError). */
export const describeQueueError = describeMutateError;

/** A session-scoped caller shape — `EngineClient` matches. */
export type { EngineClient };