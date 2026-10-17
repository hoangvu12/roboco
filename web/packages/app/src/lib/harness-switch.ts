import { methods } from "@roboco/engine-client";
import type { HarnessId, ReasoningLevel } from "@roboco/proto";

/**
 * The one-way harness switch on the wire (t3-orchestration-v2 ticket 24)
 * — the web peer of the desktop's `Pickers::switch_chat_harness` RPC half
 * plus ticket 25's `continue_on_harness.rs` params builder. The ENGINE
 * owns every rule (ADR 0006/0007): the SwitchHarness RPC writes the
 * "Switched to <harness>" seam into the transcript, drops the chat's
 * harness session, and saves the arrived picks; the sandbox is the one
 * field the switch never owns — the engine reads it from the chat's row.
 *
 * Both web callers — the composer's unlocked harness pick (the desktop
 * picker's flow) and the Limited banner's "Continue on another harness"
 * (ticket 25, which switches first and then sends the continue prompt) —
 * go through this one wrapper.
 */

/**
 * The caller shape the switch needs — `EngineClient` satisfies it, and
 * the tests drive a fake (the `lib/limited.ts` wrapper idiom).
 */
export interface HarnessSwitchCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/** The picks a switch carries: only what the caller resolved. */
export interface HarnessSwitchPicks {
  /** The model the chat runs on after the switch; null = the harness's own default at run time. */
  readonly model?: string | null;
  /**
   * The reasoning level after the switch; null = the harness's default.
   * "Model options reset to the new harness's defaults unless the user
   * chose them" — a switch only carries what was explicitly resolved.
   */
  readonly reasoning?: ReasoningLevel | null;
  /** Per-model option picks, keyed by option id (the switch's own writes). */
  readonly modelOptions?: Readonly<Record<string, unknown>>;
}

/**
 * The SwitchHarness params (the desktop's `switch_chat_harness` payload,
 * and `continue_on_harness.rs`'s `switch_params` shape): the chat, the
 * chosen harness, and only the picks that were resolved — absent keys are
 * the engine's "default" arms. The sandbox never rides: the RPC keeps the
 * chat's own pick. Pure.
 */
export function switchHarnessParams(
  chatId: string,
  harness: HarnessId,
  picks: HarnessSwitchPicks = {},
): Record<string, unknown> {
  const params: Record<string, unknown> = { chatId, harness };
  const model = picks.model?.trim() ?? "";
  if (model.length > 0) {
    params.model = model;
  }
  if (picks.reasoning != null) {
    params.reasoning = picks.reasoning;
  }
  if (picks.modelOptions !== undefined && Object.keys(picks.modelOptions).length > 0) {
    params.modelOptions = { ...picks.modelOptions };
  }
  return params;
}

/**
 * Perform the switch — `SwitchHarness` on the chat's host device (the
 * client's request routing forwards it; the engine refuses a chat hosted
 * elsewhere). The reply is empty: the engine's chats watch carries the
 * row's new config, and the transcript stream carries the seam. A
 * failure surfaces to the caller as a rejected promise (the composer's
 * failure notice / the banner's outcome line).
 */
export async function switchHarness(
  caller: HarnessSwitchCaller,
  chatId: string,
  harness: HarnessId,
  picks: HarnessSwitchPicks = {},
): Promise<void> {
  await caller.call(methods.SWITCH_HARNESS, switchHarnessParams(chatId, harness, picks));
}

/**
 * Whether an existing chat's harness picker is unlocked for a one-way
 * switch (the desktop's `state.harness_switchable`): the chat exists, no
 * send of its own is still awaiting confirmation, and no turn is in
 * flight — Working or AwaitingInput both count as live. Pure over the
 * composer's own inputs.
 */
export function harnessSwitchable(chatExists: boolean, busy: boolean, runLive: boolean): boolean {
  return chatExists && !busy && !runLive;
}

