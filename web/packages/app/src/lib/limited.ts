import { methods } from "@roboco/engine-client";
import type {
  AgentAccount,
  HarnessDescriptor,
  HarnessId,
  ResumeAccountCandidates,
  ResumeOnAccountOutcome,
  ScheduledResume,
  SessionLimit,
} from "@roboco/proto";
import type { ChatStatus } from "@roboco/engine-client";
import { offeredHarnesses } from "./model-rows";
import { effectiveIndicator, formatLimitReset, formatScheduledResume } from "./view";

/**
 * Limited chats on the web — the peer of the desktop's banner/failover
 * view-model (shell.rs's Limited arm of `render_status_strip` and its
 * pure helpers) plus the thin RPC wrappers the actions go through. The
 * engine owns every rule (ADR 0006/0007): the wire stays additive (a
 * Limited chat is an *errored* row carrying a `limit` record, ADR 0005),
 * the ranking and the durable Resume-at-reset schedule live engine-side;
 * this module only expresses what the user asked for and how the reply
 * reads.
 */

/**
 * The continue prompt the fixed actions send — `roboco_proto::RESUME_CONTINUE_PROMPT`,
 * emitted by wiregen into `@roboco/proto`'s constants (one source of truth
 * for the engine's and the web's words).
 */
export { RESUME_CONTINUE_PROMPT } from "@roboco/proto";

/**
 * Whether the chat page's Limited banner shows for the chat's raw status
 * row — gated by the send overlay exactly like `ChatRow.limited`
 * (lib/view.ts) and the desktop's `indicator_for`: a send in flight
 * (Resume's own continue prompt included) reads as Working, never Limited,
 * so Resume cannot be pressed twice. Pure.
 */
export function limitedBannerShows(
  sending: boolean,
  session: ChatStatus | undefined,
  now: number,
): boolean {
  return !sending && effectiveIndicator(session, now) === "limited";
}

/** How long a loaded candidate list stays fresh while the banner shows. */
export const FAILOVER_LIST_TTL_MS = 30_000;

/**
 * The harnesses whose logins switch — "Resume on another account" is
 * offered for these only (ticket 09). Pure.
 */
export function harnessOffersFailover(harness: HarnessId): boolean {
  return harness === "claude-code" || harness === "codex";
}

/** The failover picker's title for `harness`. Pure. */
export function failoverCardTitle(harness: HarnessId): string {
  return harness === "codex"
    ? "Resume on another Codex account"
    : "Resume on another Claude account";
}

/**
 * The harnesses the "Continue on another harness" picker offers
 * (t3-orchestration-v2 ticket 25, the desktop's
 * `continue_offered_harnesses`): the same offered set the composer's
 * picker uses (installed and enabled on the listing device), minus the
 * chat's CURRENT harness — a Limited chat escapes its limit by switching
 * to ANOTHER harness, never by re-picking the one that hit it. Pure.
 */
export function continueOfferedHarnesses(
  catalog: readonly HarnessDescriptor[],
  current: HarnessId | null,
): HarnessDescriptor[] {
  return offeredHarnesses(catalog).filter((descriptor) => descriptor.id !== current);
}

/**
 * The Limited banner's label — the window and its reset ("5-hour limit ·
 * resets in 45m"), or the armed continuation's fire time instead
 * ("5-hour limit · resumes in 45m", ticket 07). An unknown reset says so;
 * a missing record keeps the honest generic word. Pure.
 */
export function limitedBannerLabel(
  limit: SessionLimit | null,
  scheduled: ScheduledResume | null,
  now: number,
): string {
  if (limit === null) {
    return "Usage limit reached";
  }
  if (scheduled !== null) {
    return `${limit.window} limit · ${formatScheduledResume(scheduled.fireAt, now)}`;
  }
  const reset =
    limit.resetsAt != null ? formatLimitReset(limit.resetsAt, now) : "reset time unknown";
  return `${limit.window} limit · ${reset}`;
}

/**
 * Whether "Resume at reset" is offered (ticket 07): nothing armed yet and
 * a reset time still ahead to wait for. A due or unknown reset leaves the
 * honest offer as plain Resume. Pure.
 */
export function resumeAtResetArmable(
  limit: SessionLimit | null,
  scheduled: ScheduledResume | null,
  now: number,
): boolean {
  return scheduled === null && limit?.resetsAt != null && limit.resetsAt > now;
}

/** One row of the failover picker (ticket 09): a switch target, or a
 *  signed-out account offered "Sign in again" instead (stories 47/50 —
 *  a dead login is listed, never switched to). */
export type FailoverRow =
  | { readonly kind: "candidate"; readonly account: AgentAccount; readonly recommended: boolean }
  | { readonly kind: "signedOut"; readonly account: AgentAccount };

/**
 * The picker's row model (shell.rs `failover_rows`): the ranked candidates
 * (best first, the first recommended) followed by the signed-out accounts.
 * Pure.
 */
export function failoverRows(list: ResumeAccountCandidates): FailoverRow[] {
  const rows: FailoverRow[] = list.candidates.map((account, ix) => ({
    kind: "candidate",
    account,
    recommended: ix === 0,
  }));
  for (const account of list.signedOut ?? []) {
    rows.push({ kind: "signedOut", account });
  }
  return rows;
}

/**
 * Whether the loaded candidate list offers anything — healthy candidates,
 * signed-out rows to sign back in, or both. `false` hides the chip: only
 * Resume (and Resume at reset) remain (shell.rs `failover_list`). Pure.
 */
export function failoverListOffers(list: ResumeAccountCandidates): boolean {
  return list.candidates.length > 0 || (list.signedOut?.length ?? 0) > 0;
}

/**
 * The strip line for a failover outcome (shell.rs `failover_outcome_notice`):
 * `Switched` names the account the chat resumed on, `NoCandidates` says why
 * nothing else was offered, `Failed` surfaces the reason. Pure.
 */
export function failoverOutcomeNotice(outcome: ResumeOnAccountOutcome): string {
  switch (outcome.type) {
    case "switched":
      return `Resumed on ${outcome.accountEmail ?? "another account"} — continuing where the chat left off.`;
    case "noCandidates":
      return "No other account is available right now.";
    case "failed":
      return outcome.reason;
  }
}

/**
 * The reset caption under one candidate row's usage meters (story 49,
 * shell.rs `candidate_reset_caption`): "session resets in 45m · week
 * resets in 2h 05m" — one entry per shown window (the first two) that
 * knows its reset. Pure.
 */
export function candidateResetCaption(account: AgentAccount, now: number): string | null {
  const resets: string[] = [];
  for (const window of account.usageWindows.slice(0, 2)) {
    if (window.resetsAt == null) {
      continue;
    }
    const at = Date.parse(window.resetsAt);
    if (Number.isFinite(at)) {
      resets.push(`${window.label.toLowerCase()} ${formatLimitReset(at, now)}`);
    }
  }
  return resets.length > 0 ? resets.join(" · ") : null;
}

// ── RPC wrappers ────────────────────────────────────────────────────────
// All four follow the lib/chat-actions.ts shape: functions take the
// minimal caller so tests drive them without a socket; `EngineClient`
// satisfies it.

export interface LimitedCaller {
  call<T>(method: string, params?: unknown): Promise<T>;
}

/**
 * **Resume at reset** (ticket 07): arm the engine's durable continuation —
 * at the limit's reset time the engine sends the continue prompt like the
 * manual Resume. The engine owns the schedule; this only expresses the
 * user's intent.
 */
export async function scheduleResumeAtReset(caller: LimitedCaller, chatId: string): Promise<void> {
  await caller.call(methods.MUTATE, { op: "scheduleResumeAtReset", chatId });
}

/**
 * Cancel a Limited chat's armed Resume-at-reset continuation (the banner's
 * Cancel action). A new message, archive or delete cancels it on its own;
 * this is the explicit withdrawal. No-op engine-side when nothing is armed.
 */
export async function cancelScheduledResume(caller: LimitedCaller, chatId: string): Promise<void> {
  await caller.call(methods.MUTATE, { op: "cancelScheduledResume", chatId });
}

/**
 * The ranked switch targets for one Limited chat
 * (`ListResumeAccountCandidates`): the engine filters and ranks the
 * harness's cached accounts and names the one the limit hit.
 */
export async function listResumeAccountCandidates(
  caller: LimitedCaller,
  chatId: string,
): Promise<ResumeAccountCandidates> {
  return caller.call<ResumeAccountCandidates>(methods.LIST_RESUME_ACCOUNT_CANDIDATES, { chatId });
}

/**
 * **Resume on another account** (ticket 09): the engine probes the account,
 * activates it, retires the chat's warm runtime and dispatches the
 * continuation. `accountId` omitted takes the engine's recommendation (the
 * best-ranked candidate).
 */
export async function resumeOnAnotherAccount(
  caller: LimitedCaller,
  chatId: string,
  accountId?: string,
): Promise<ResumeOnAccountOutcome> {
  return caller.call<ResumeOnAccountOutcome>(methods.RESUME_ON_ANOTHER_ACCOUNT, {
    chatId,
    ...(accountId !== undefined ? { accountId } : {}),
  });
}
