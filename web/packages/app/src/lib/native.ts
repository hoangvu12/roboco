import type {
  ConnectivityState,
  NativeAnswer,
  NativeChatCatalog,
  NativeChatState,
  NativeChild,
  NativeChildOutcome,
  NativeChildStatus,
  NativeConfiguration,
  NativeControlOutcome,
  NativeDelivery,
  NativeErrorKind,
  NativeGoal,
  NativeGoalCause,
  NativeGoalPhase,
  NativeLink,
  NativeNotice,
  NativePlanStatus,
  NativeQuestion,
  NativeReadiness,
  NativeRequestStatus,
  NativeSubmission,
  NativeToolDetail,
  NativeToolView,
  NativeUsage,
  ReasoningLevel,
  SessionCommandEntry,
  Skill,
  SlashCommand,
} from "@roboco/proto";
import type { DraftConfig } from "./composer-actions";
import { invocationCandidates, type InvocationRow } from "./invocations";

/**
 * The web's model of a native (Mimir) conversation. `NativeChatState` is the
 * only authority: the engine writes it from host-confirmed facts, so every
 * view here is a pure function of it. A control the user sends is "sent",
 * never "applied"; the state changing is the confirmation.
 */

function assertNever(value: never): never {
  throw new Error(`Unhandled native variant: ${JSON.stringify(value)}`);
}

// ---------------------------------------------------------------------------
// Work, release, recovery
// ---------------------------------------------------------------------------

const SETTLED_REQUEST: ReadonlySet<NativeRequestStatus> = new Set(["completed", "cancelled", "failed", "interrupted"]);

/** Everything but queued and running has ended (engine `NativeChildStatus::is_terminal`). */
export function childSettled(status: NativeChildStatus): boolean {
  return status !== "queued" && status !== "running";
}

/** `NativeChatState::working` — the engine's own busy test, from the same wire state. */
export function nativeWorking(state: NativeChatState): boolean {
  return (
    state.activeRequest !== null ||
    state.goal?.phase === "active" ||
    state.children.some((child) => !childSettled(child.status))
  );
}

export const RELEASE_BLOCKED_COPY =
  "Mimir is still working in this chat. Wait for it to finish or stop it, then continue in Mimir.";

/** The engine refuses Release with Busy while work or a question is open; the button mirrors it. */
export function releaseBlocker(state: NativeChatState): string | null {
  return nativeWorking(state) || state.userRequest !== null ? RELEASE_BLOCKED_COPY : null;
}

export type NativeTone = "neutral" | "active" | "warn" | "danger";

export interface LinkView {
  readonly title: string;
  readonly detail: string | null;
  readonly tone: NativeTone;
  /** Reconnect re-attaches this same chat's conversation. */
  readonly canReconnect: boolean;
  readonly showRelease: boolean;
}

/** Connection state of the conversation. Engine reachability and work status are separate axes. */
export function linkView(link: NativeLink): LinkView {
  switch (link.state) {
    case "detached":
      return { title: "Not connected to Mimir", detail: "Reconnect to resume this conversation.", tone: "warn", canReconnect: true, showRelease: false };
    case "attaching":
      return { title: "Connecting to Mimir", detail: null, tone: "neutral", canReconnect: false, showRelease: false };
    case "attached":
      return { title: "Connected to Mimir", detail: null, tone: "neutral", canReconnect: false, showRelease: true };
    case "busy":
      return { title: "Mimir is busy elsewhere", detail: link.message, tone: "warn", canReconnect: true, showRelease: false };
    case "interrupted":
      return { title: "Connection to Mimir was interrupted", detail: link.message, tone: "warn", canReconnect: true, showRelease: false };
    case "releasing":
      return { title: "Handing the conversation to Mimir", detail: null, tone: "neutral", canReconnect: false, showRelease: false };
    case "released":
      return { title: "Continued in Mimir", detail: "Reconnect here to take the conversation back.", tone: "neutral", canReconnect: true, showRelease: false };
    case "unavailable":
      return { title: "Mimir is unavailable", detail: link.message, tone: "danger", canReconnect: true, showRelease: false };
    default:
      return assertNever(link);
  }
}

/** Chat connectivity (the engine stream) is its own axis, never folded into the link or work status. */
export function engineOfflineCopy(connectivity: ConnectivityState | null | undefined): string | null {
  switch (connectivity) {
    case "offline":
      return "This engine is offline. Mimir's own state is unchanged and will resume when the engine is back.";
    case "reconnecting":
      return "Reconnecting to this engine.";
    default:
      return null;
  }
}

export const RECOVERING_COPY =
  "Mimir is recovering this run. Live output is withheld until the next checkpoint, so nothing shown is a complete response yet.";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/** `provider/model`, the id the engine's `split_model` reads back. */
export function configurationModelId(configuration: NativeConfiguration): string | null {
  if (configuration.model === null) {
    return null;
  }
  return configuration.provider === null ? configuration.model : `${configuration.provider}/${configuration.model}`;
}

/** The host-confirmed mode, model and reasoning as one line. */
export function nativeConfigurationLabel(configuration: NativeConfiguration): string {
  const parts = [configuration.mode === "plan" ? "Plan" : "Build", configurationModelId(configuration) ?? "No model"];
  if (configuration.reasoning !== null) {
    parts.push(configuration.reasoning);
  }
  return parts.join(" · ");
}

/**
 * Who decides the model, reasoning and mode the chat's next prompt runs with.
 * The engine reconfigures the host from any model or reasoning a prompt
 * carries, so stale per-chat picks must never ride a prompt the host owns.
 *
 * - `draft`: the composer's picks are the user's real choice: not a native
 *   chat, a chat this send creates, or a conversation the host never configured.
 * - `awaitingHost`: an existing native chat whose host state has not arrived
 *   since it was opened. Nothing is sent until it does.
 * - `host`: the host's confirmed configuration. Draft picks are never sent.
 */
export type ConfigAuthority =
  | { readonly kind: "draft" }
  | { readonly kind: "awaitingHost" }
  | { readonly kind: "host"; readonly configuration: NativeConfiguration };

export function configAuthority(input: {
  readonly nativeChat: boolean;
  readonly fresh: boolean;
  readonly projected: boolean;
  readonly state: NativeChatState | null;
}): ConfigAuthority {
  const configuration = input.state?.configuration ?? null;
  if (configuration !== null) {
    return { kind: "host", configuration };
  }
  if (input.nativeChat && !input.fresh && !input.projected) {
    return { kind: "awaitingHost" };
  }
  return { kind: "draft" };
}

/** The composer's model slot when the host owns (or may own) the configuration; null keeps the picker. */
export function configSlotLabel(authority: ConfigAuthority): string | null {
  switch (authority.kind) {
    case "draft":
      return null;
    case "awaitingHost":
      return "Waiting for Mimir";
    case "host":
      return nativeConfigurationLabel(authority.configuration);
    default:
      return assertNever(authority);
  }
}

/** The run's model settings under `authority`: a host-owned prompt carries none. */
export function nativeRunDraft(draft: DraftConfig, authority: ConfigAuthority): DraftConfig {
  return authority.kind === "host" ? { ...draft, model: null, reasoning: null } : draft;
}

/**
 * The host command a message starts with, as the engine reads it
 * (`invocation::leading_command`): leading blank lines and paragraph indent
 * are allowed, a tab or four spaces of indent makes it ordinary text.
 */
export function leadingCommand(text: string): { readonly name: string; readonly tail: string } | null {
  const trimmed = text.replace(/^[ \t\r\n]+/, "");
  const indent = text.slice(0, text.length - trimmed.length).split(/[\r\n]/).pop() ?? "";
  if (indent.includes("\t") || indent.length >= 4 || !trimmed.startsWith("/")) {
    return null;
  }
  const match = /^([^\s]+)(?:\s+([\s\S]*))?$/.exec(trimmed.slice(1));
  if (match === null) {
    return null;
  }
  return { name: match[1]!, tail: (match[2] ?? "").trim() };
}

/**
 * A host command typed while the conversation works goes to the host now: the
 * engine routes it as command routing into the running request. Only a
 * command the host's catalog marks idle-only waits for the turn to end.
 */
export function commandSendsNow(text: string, idleOnly: (name: string) => boolean): boolean {
  const command = leadingCommand(text);
  return command !== null && !idleOnly(command.name);
}

/** The picker's choice as a `Configure` control that names only what changed. */
export function configureControl(
  current: NativeConfiguration | null,
  next: { model?: string | null; reasoning?: string | null; mode?: "build" | "plan" },
): { control: "configure"; provider: string | null; model: string | null; reasoning: string | null; mode: "build" | "plan" | null } {
  let provider: string | null = null;
  let model: string | null = null;
  if (next.model !== undefined && next.model !== null) {
    const slash = next.model.indexOf("/");
    provider = slash < 0 ? null : next.model.slice(0, slash);
    model = slash < 0 ? next.model : next.model.slice(slash + 1);
  }
  return {
    control: "configure",
    provider,
    model,
    reasoning: next.reasoning !== undefined && next.reasoning !== current?.reasoning ? next.reasoning : null,
    mode: next.mode !== undefined && next.mode !== current?.mode ? next.mode : null,
  };
}

export function reasoningLevels(catalog: NativeChatCatalog | null, configuration: NativeConfiguration | null): readonly string[] {
  if (catalog === null || configuration === null) {
    return [];
  }
  for (const provider of catalog.providers) {
    if (configuration.provider !== null && provider.id !== configuration.provider) {
      continue;
    }
    const model = provider.models.find((choice) => choice.id === configuration.model);
    if (model !== undefined) {
      return model.reasoning;
    }
  }
  return [];
}

export function isReasoningLevel(value: string): value is ReasoningLevel {
  return ["minimal", "low", "medium", "high", "xhigh", "max", "ultra", "ultracode", "ultrathink"].includes(value);
}

// ---------------------------------------------------------------------------
// Goal
// ---------------------------------------------------------------------------

const GOAL_CAUSE: Record<NativeGoalCause, string> = {
  started: "Started",
  edited: "Edited",
  resumed: "Resumed",
  naturalContinuation: "Continuing on its own",
  reviewGap: "Review found a gap",
  reviewAccepted: "Review accepted the work",
  userPaused: "Paused by you",
  userInput: "Paused for your input",
  manual: "Changed manually",
  timeLimit: "Time limit reached",
  noProgress: "No progress was made",
  runtimeFailure: "Stopped by a runtime failure",
  restart: "Restarted with the host",
  blocked: "Blocked",
  reviewUnavailable: "Review was unavailable",
  cleared: "Cleared",
};

const GOAL_PHASE: Record<NativeGoalPhase, string> = {
  active: "Active",
  paused: "Paused",
  blocked: "Blocked",
  complete: "Complete",
  cleared: "Cleared",
};

export type GoalAction = "edit" | "pause" | "resume" | "clear";

export interface GoalView {
  readonly phaseLabel: string;
  readonly causeLabel: string;
  readonly tone: NativeTone;
  readonly reason: string | null;
  readonly reviewGap: string | null;
  readonly workTurns: number;
  readonly completion: NativeGoal["completion"];
  readonly actions: readonly GoalAction[];
}

export function goalView(goal: NativeGoal): GoalView {
  const actions = ((): readonly GoalAction[] => {
    switch (goal.phase) {
      case "active":
        return ["edit", "pause", "clear"];
      case "paused":
      case "blocked":
        return ["edit", "resume", "clear"];
      case "complete":
        return ["clear"];
      case "cleared":
        return [];
      default:
        return assertNever(goal.phase);
    }
  })();
  return {
    phaseLabel: GOAL_PHASE[goal.phase],
    causeLabel: GOAL_CAUSE[goal.cause],
    tone: goal.phase === "blocked" ? "danger" : goal.phase === "active" ? "active" : "neutral",
    reason: goal.reason,
    reviewGap: goal.reviewGap,
    workTurns: goal.workTurns,
    completion: goal.completion,
    actions,
  };
}

/** A goal that is gone (no goal, or cleared) can be started again. */
export function goalIsOpen(goal: NativeGoal | null): goal is NativeGoal {
  return goal !== null && goal.phase !== "cleared";
}

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

export const PLAN_STATUS: Record<NativePlanStatus, string> = {
  reviewPending: "Awaiting your review",
  savedStopped: "Saved, stopped",
  accepted: "Accepted",
  implementing: "Implementing",
  completed: "Completed",
  abandoned: "Abandoned",
};

// ---------------------------------------------------------------------------
// Questions and approvals
// ---------------------------------------------------------------------------

/** A question's in-progress answer, kept as the user typed it. */
export interface QuestionDraft {
  readonly selected: readonly string[];
  readonly freeform: string;
  readonly noneOfAbove: boolean;
}

export const EMPTY_QUESTION_DRAFT: QuestionDraft = { selected: [], freeform: "", noneOfAbove: false };

export function toggleOption(question: NativeQuestion, draft: QuestionDraft, label: string): QuestionDraft {
  const has = draft.selected.includes(label);
  const selected = question.allowMultiple
    ? has
      ? draft.selected.filter((entry) => entry !== label)
      : [...draft.selected, label]
    : has
      ? []
      : [label];
  return { ...draft, selected, noneOfAbove: false };
}

export function toggleNoneOfAbove(draft: QuestionDraft): QuestionDraft {
  return { ...draft, selected: [], noneOfAbove: !draft.noneOfAbove };
}

export function questionAnswered(draft: QuestionDraft): boolean {
  return draft.selected.length > 0 || draft.noneOfAbove || draft.freeform.trim().length > 0;
}

/** One `NativeAnswer` per question, in order; null while any question is unanswered. */
export function buildAnswers(questions: readonly NativeQuestion[], drafts: Readonly<Record<string, QuestionDraft>>): NativeAnswer[] | null {
  const answers: NativeAnswer[] = [];
  for (const question of questions) {
    const draft = drafts[question.id] ?? EMPTY_QUESTION_DRAFT;
    if (!questionAnswered(draft)) {
      return null;
    }
    const freeform = draft.freeform.trim();
    answers.push({
      questionId: question.id,
      selectedOptions: [...draft.selected],
      freeformText: freeform.length === 0 ? null : freeform,
      noneOfAbove: draft.noneOfAbove,
    });
  }
  return answers;
}

// ---------------------------------------------------------------------------
// Submissions (message delivery)
// ---------------------------------------------------------------------------

export interface DeliveryView {
  readonly messageId: string;
  readonly label: string;
  readonly detail: string | null;
  readonly tone: NativeTone;
  /** `RetrySubmission` is offered only when the host flagged it replay-safe. */
  readonly retryable: boolean;
}

const SUBMISSION_NOUN: Record<NativeSubmission["kind"], string> = {
  prompt: "Message",
  command: "Command",
  steer: "Guidance",
};

export function deliveryView(submission: NativeSubmission): DeliveryView {
  const noun = SUBMISSION_NOUN[submission.kind];
  const delivery: NativeDelivery = submission.delivery;
  const base = { messageId: submission.messageId, retryable: submission.retryable };
  switch (delivery.state) {
    case "submitting":
      return { ...base, label: `${noun} sending to Mimir`, detail: null, tone: "neutral" };
    case "admitted":
      return { ...base, label: `${noun} accepted by Mimir`, detail: `Request ${delivery.requestId}`, tone: "neutral" };
    case "steered":
      return { ...base, label: `${noun} added to the running request`, detail: `Request ${delivery.requestId}`, tone: "neutral" };
    case "handled":
      return { ...base, label: `${noun} handled by Mimir`, detail: null, tone: "neutral" };
    case "unknown":
      return {
        ...base,
        label: `${noun} delivery is unknown`,
        detail: `${delivery.message} It was not retried automatically.`,
        tone: "warn",
      };
    case "refused":
      return { ...base, label: `${noun} refused by Mimir`, detail: delivery.message, tone: "danger" };
    default:
      return assertNever(delivery);
  }
}

/** Only submissions that need attention stay in the dock; settled ones are in the transcript. */
export function attentionDeliveries(state: NativeChatState): DeliveryView[] {
  return state.submissions
    .filter((submission) => {
      const delivery = submission.delivery.state;
      return delivery === "submitting" || delivery === "unknown" || delivery === "refused";
    })
    .map(deliveryView);
}

// ---------------------------------------------------------------------------
// Readiness
// ---------------------------------------------------------------------------

export interface ReadinessView {
  readonly ready: boolean;
  readonly title: string;
  /** The corrective step to run by hand; Roboco never installs anything. */
  readonly action: string | null;
  readonly detail: string | null;
}

export function readinessView(readiness: NativeReadiness): ReadinessView {
  switch (readiness.state) {
    case "ready":
      return {
        ready: true,
        title: readiness.version === null ? "Mimir is ready" : `Mimir ${readiness.version} is ready`,
        action: null,
        detail: `${readiness.executable} with bridge ${readiness.bridge}`,
      };
    case "missingExecutable":
      return { ready: false, title: "Mimir is not installed", action: readiness.action, detail: null };
    case "pluginMissing":
      return { ready: false, title: "The Roboco plugin is not installed in Mimir", action: readiness.action, detail: readiness.message };
    case "incompatible":
      return { ready: false, title: "This Mimir version is not compatible", action: readiness.action, detail: readiness.message };
    case "noModels":
      return { ready: false, title: "Mimir has no connected models", action: readiness.action, detail: null };
    case "failed":
      return { ready: false, title: "Could not check Mimir", action: null, detail: readiness.message };
    default:
      return assertNever(readiness);
  }
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

export const IDLE_ONLY_COPY = "Available when Mimir is idle";

/** An `idleOnly` command is shown but not selectable while the conversation has active work. */
export function rowUnavailable(row: InvocationRow, working: boolean): string | null {
  return working && row.idleOnly === true ? IDLE_ONLY_COPY : null;
}

/**
 * The host's catalog as the composer's invocation rows. Identities are the
 * host's (`name`, and a skill's own `path`, or the `harness-skill:` identity the
 * engine maps back to a native skill). Nothing is filtered by a client allowlist;
 * terminal-only commands are already absent from the host's catalog.
 */
export function nativeInvocationRows(catalog: NativeChatCatalog): InvocationRow[] {
  const commands: SlashCommand[] = catalog.commands.map((command) => ({
    name: command.name,
    description: command.description,
    inputHint: command.argument,
  }));
  const skills: Skill[] = catalog.skills.map((skill) => ({
    name: skill.name,
    path: skill.path ?? `harness-skill:${skill.name}`,
    description: skill.description,
    enabled: true,
    command: null,
  }));
  const idleOnly = new Set(catalog.commands.filter((command) => command.idleOnly).map((command) => command.name));
  return invocationCandidates(commands, skills).map((row) =>
    row.invocation.kind === "command" && idleOnly.has(row.name) ? { ...row, idleOnly: true } : row,
  );
}

// ---------------------------------------------------------------------------
// Children
// ---------------------------------------------------------------------------

export interface ChildView {
  readonly handle: string;
  readonly docId: string;
  readonly attempt: number;
  readonly title: string;
  readonly model: string | null;
  readonly statusLabel: string;
  readonly running: boolean;
  readonly attemptCount: number;
}

const CHILD_STATUS: Record<NativeChildStatus, string> = {
  queued: "Queued",
  running: "Running",
  completed: "Completed",
  failed: "Failed",
  deadline: "Hit its deadline",
  stopped: "Stopped",
  interrupted: "Interrupted",
};

export function childStatusLabel(status: NativeChildStatus): string {
  return CHILD_STATUS[status];
}

export function childView(child: NativeChild): ChildView {
  return {
    handle: child.handle,
    docId: child.docId,
    attempt: child.attempt,
    title: child.description.length > 0 ? child.description : child.profile,
    model: child.model,
    statusLabel: childStatusLabel(child.status),
    running: !childSettled(child.status),
    attemptCount: child.attempts.length,
  };
}

export function childByDoc(state: NativeChatState | null, docId: string): NativeChild | null {
  return state?.children.find((child) => child.docId === docId) ?? null;
}

/** A header-only child (the view had no room for its details) needs the canonical inventory. */
export function needsInventory(children: readonly NativeChild[]): boolean {
  return children.some((child) => child.oversized);
}

/** What an inventory read is for. A new value means the read may be stale. */
export function inventoryKey(children: readonly NativeChild[]): string {
  return children
    .filter((child) => child.oversized)
    .map((child) => `${child.handle}#${child.attempt}#${child.status}`)
    .join(",");
}

/**
 * The projected children with each header-only one replaced by its canonical
 * inventory entry for the same handle and doc. One the inventory lacks stays a header.
 */
export function hydrateChildren(projected: readonly NativeChild[], inventory: readonly NativeChild[]): NativeChild[] {
  return projected.map((child) =>
    child.oversized ? (inventory.find((full) => full.handle === child.handle && full.docId === child.docId) ?? child) : child,
  );
}

// ---------------------------------------------------------------------------
// Command receipts
// ---------------------------------------------------------------------------

/**
 * What the host ledger said about one control, read back with `GetCommand`.
 * `settled` carries the typed `NativeControlOutcome` or the queue's own status.
 * `unconfirmed` means this client stopped reading while the host had not
 * settled the command. It is not a failure: the command stays in the ledger
 * and is never resent.
 */
export type ControlVerdict =
  | { readonly kind: "applied" }
  | { readonly kind: "configured"; readonly label: string }
  | { readonly kind: "admitted"; readonly requestId: string }
  | { readonly kind: "display"; readonly text: string }
  | { readonly kind: "cancelled"; readonly newly: boolean }
  | { readonly kind: "released" }
  | { readonly kind: "childAccepted" }
  | { readonly kind: "childMoved"; readonly attempt: number }
  | { readonly kind: "childEnded"; readonly reason: "terminal" | "finalizing" }
  | { readonly kind: "refused"; readonly errorKind: NativeErrorKind; readonly message: string }
  | { readonly kind: "unknown"; readonly message: string }
  | { readonly kind: "notRun"; readonly status: SessionCommandEntry["status"]; readonly resolution: string | null };

export type ControlReceipt =
  | { readonly kind: "sending" }
  | { readonly kind: "notQueued"; readonly message: string }
  | { readonly kind: "pending"; readonly commandId: string }
  | { readonly kind: "unconfirmed"; readonly commandId: string; readonly reason: string }
  | { readonly kind: "settled"; readonly commandId: string; readonly verdict: ControlVerdict };

function verdictFromOutcome(outcome: NativeControlOutcome): ControlVerdict {
  switch (outcome.outcome) {
    case "applied":
      return { kind: "applied" };
    case "configured":
      return { kind: "configured", label: nativeConfigurationLabel(outcome.configuration) };
    case "admitted":
      return { kind: "admitted", requestId: outcome.requestId };
    case "display":
      return { kind: "display", text: outcome.text };
    case "child":
      switch (outcome.control.result) {
        case "accepted":
          return { kind: "childAccepted" };
        case "attemptChanged":
          return { kind: "childMoved", attempt: outcome.control.attempt };
        case "terminal":
          return { kind: "childEnded", reason: "terminal" };
        case "finalizing":
          return { kind: "childEnded", reason: "finalizing" };
        default:
          return assertNever(outcome.control);
      }
    case "cancelled":
      return { kind: "cancelled", newly: outcome.newly };
    case "released":
      return { kind: "released" };
    case "refused":
      return { kind: "refused", errorKind: outcome.kind, message: outcome.message };
    case "unknown":
      return { kind: "unknown", message: outcome.message };
    default:
      return assertNever(outcome);
  }
}

/** The receipt for a ledger entry. A typed host outcome always wins over the queue status. */
export function receiptFromCommand(entry: SessionCommandEntry): ControlReceipt {
  if (entry.status === "pending") {
    return { kind: "pending", commandId: entry.id };
  }
  const outcome = entry.outcome ?? null;
  if (outcome !== null) {
    return { kind: "settled", commandId: entry.id, verdict: verdictFromOutcome(outcome) };
  }
  if (entry.status === "unknown") {
    return {
      kind: "settled",
      commandId: entry.id,
      verdict: { kind: "unknown", message: entry.resolution ?? "The host's answer was lost." },
    };
  }
  return { kind: "settled", commandId: entry.id, verdict: { kind: "notRun", status: entry.status, resolution: entry.resolution } };
}

const NOT_RUN_STATUS: Record<SessionCommandEntry["status"], string> = {
  pending: "is still waiting",
  applied: "was applied without host detail",
  rejected: "was rejected",
  expired: "expired before it ran",
  superseded: "was superseded by a newer command",
  cancelled: "was cancelled",
  unknown: "has an unknown result",
};

/** The user-facing sentence for a verdict. Child verdicts are worded by `childControlLabel`. */
export function verdictText(verdict: ControlVerdict): string {
  switch (verdict.kind) {
    case "applied":
      return "Mimir accepted it.";
    case "configured":
      return `Configured: ${verdict.label}`;
    case "admitted":
      return "Mimir started the request.";
    case "display":
      return verdict.text;
    case "cancelled":
      return verdict.newly ? "Stop sent. Mimir is cancelling the request." : "That request was already stopping.";
    case "released":
      return "Released. Continue the conversation in Mimir.";
    case "childAccepted":
      return "Mimir accepted it for the agent.";
    case "childMoved":
      return `That agent moved on to attempt ${verdict.attempt}. Nothing was sent to it.`;
    case "childEnded":
      return verdict.reason === "terminal"
        ? "That attempt has already ended. Nothing was sent."
        : "That agent is finishing and cannot take this now. Nothing was sent.";
    case "refused":
      return `Mimir refused it. ${verdict.message}`;
    case "unknown":
      return `Mimir's answer was lost, so it may or may not have taken effect. Check the conversation state. Nothing was resent. ${verdict.message}`.trim();
    case "notRun":
      return `The engine did not run it. It ${NOT_RUN_STATUS[verdict.status]}${verdict.resolution === null ? "." : `. ${verdict.resolution}`}`;
    default:
      return assertNever(verdict);
  }
}

/** The line a control's receipt shows. `attention` receipts stay until the user acts again. */
export function receiptView(receipt: ControlReceipt): { readonly text: string; readonly tone: "progress" | "ok" | "warn" | "danger"; readonly attention: boolean } {
  switch (receipt.kind) {
    case "sending":
      return { text: "Sending to Mimir", tone: "progress", attention: false };
    case "notQueued":
      return { text: `Could not send: ${receipt.message}`, tone: "danger", attention: true };
    case "pending":
      return { text: "Queued. Waiting for Mimir to settle it.", tone: "progress", attention: false };
    case "unconfirmed":
      return {
        text: `Mimir has not confirmed this yet (${receipt.reason}). It stays queued as ${receipt.commandId}. Nothing was resent.`,
        tone: "warn",
        attention: true,
      };
    case "settled": {
      const { verdict } = receipt;
      const text = verdictText(verdict);
      switch (verdict.kind) {
        case "refused":
        case "notRun":
          return { text, tone: "danger", attention: true };
        case "unknown":
        case "childMoved":
        case "childEnded":
          return { text, tone: "warn", attention: true };
        case "applied":
        case "configured":
        case "admitted":
        case "display":
        case "cancelled":
        case "released":
        case "childAccepted":
          return { text, tone: "ok", attention: false };
        default:
          return assertNever(verdict);
      }
    }
    default:
      return assertNever(receipt);
  }
}

/** The command is still travelling or waiting on the host: a second click would double-send. */
export function receiptInFlight(receipt: ControlReceipt | null): boolean {
  return receipt !== null && (receipt.kind === "sending" || receipt.kind === "pending");
}

/** In flight, or settled without needing attention: the control has done its one job. */
export function receiptHoldsControl(receipt: ControlReceipt | null): boolean {
  return receipt !== null && (receiptInFlight(receipt) || (receipt.kind === "settled" && !receiptView(receipt).attention));
}

/** The last child control the user sent, bound to the attempt it named. */
export interface ChildControlSent {
  readonly action: "guidance" | "stop";
  readonly attempt: number;
  readonly receipt: ControlReceipt;
}

/**
 * "Guidance queued" and "Stopping" appear only after the host's own
 * `Child Accepted`, never from the transport enqueue. A control that raced an
 * attempt change, ended, or was refused says so and is never retried on a
 * newer attempt.
 */
export function childControlLabel(sent: ChildControlSent | null, child: NativeChild): { readonly text: string; readonly tone: "progress" | "ok" | "warn" | "danger" } | null {
  if (sent === null) {
    return null;
  }
  const noun = sent.action === "stop" ? "stop" : "guidance";
  const { receipt } = sent;
  if (receipt.kind !== "settled" || receipt.verdict.kind !== "childAccepted") {
    const view = receiptView(receipt);
    if (receipt.kind === "pending") {
      return { text: `Waiting for Mimir to accept the ${noun}.`, tone: "progress" };
    }
    return { text: view.text, tone: view.tone };
  }
  if (sent.attempt !== child.attempt) {
    return { text: `Attempt ${sent.attempt} has ended. Your ${noun} was not sent to attempt ${child.attempt}.`, tone: "warn" };
  }
  if (childSettled(child.status)) {
    return null;
  }
  return { text: sent.action === "stop" ? "Stopping" : "Guidance queued", tone: "ok" };
}

// ---------------------------------------------------------------------------
// Notices
// ---------------------------------------------------------------------------

export interface NoticeView {
  readonly title: string;
  readonly body: string | null;
  readonly tone: NativeTone;
  /** Blob ref of the full record, when the host kept one. */
  readonly detailRef: string | null;
}

function tokenCount(value: number | null): string {
  return value === null ? "unknown" : value.toLocaleString("en-US");
}

/** One honest row per host notice variant; unknown shapes fall to the unsupported row. */
export function noticeView(notice: NativeNotice): NoticeView {
  switch (notice.notice) {
    case "planLifecycle":
      return { title: `Plan "${notice.name}"`, body: PLAN_STATUS[notice.status], tone: "neutral", detailRef: null };
    case "compaction":
      return {
        title: "Context compacted",
        body: `${notice.trigger}. ${tokenCount(notice.beforeTokens)} tokens before, ${tokenCount(notice.afterTokens)} after.`,
        tone: "neutral",
        detailRef: null,
      };
    case "branchSummary":
      return { title: "Branch summary", body: notice.summary, tone: "neutral", detailRef: null };
    case "pluginSnapshot":
      return {
        title: notice.title,
        body: pluginSnapshotText(notice.lines) ?? notice.fallback,
        tone: "neutral",
        detailRef: null,
      };
    case "subagentCompletions":
      return {
        title: notice.completions.length === 1 ? "Agent finished" : `${notice.completions.length} agents finished`,
        body: null,
        tone: "neutral",
        detailRef: null,
      };
    case "commandDisplay":
      return { title: "Command output", body: notice.text, tone: "neutral", detailRef: null };
    case "status":
      return { title: "Status", body: notice.text, tone: "neutral", detailRef: null };
    case "interrupted":
      return {
        title: "Interrupted",
        body: `${notice.message} Output up to the interruption is provisional.`,
        tone: "warn",
        detailRef: notice.detailRef,
      };
    case "image":
      return {
        title: "Image",
        body: notice.mediaType === null ? "The image bytes are kept by the host." : `${notice.mediaType}. The image bytes are kept by the host.`,
        tone: "neutral",
        detailRef: notice.detailRef,
      };
    case "unsupported":
      return {
        title: "Content Roboco cannot show",
        body: `Mimir sent a "${notice.item}" item this version does not render.`,
        tone: "warn",
        detailRef: notice.detailRef,
      };
    default:
      return assertNever(notice);
  }
}

/** A plugin snapshot's own lines when they are plain text; otherwise the host's fallback is used. */
function pluginSnapshotText(lines: unknown): string | null {
  if (!Array.isArray(lines) || lines.length === 0 || !lines.every((line): line is string => typeof line === "string")) {
    return null;
  }
  return lines.join("\n");
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** A running call shows its running title; a settled one its title. */
export function nativeToolTitle(view: NativeToolView, resolved: boolean): string {
  return !resolved && view.runningTitle !== null ? view.runningTitle : view.title;
}

const RESULT_STATE: Record<NativeToolView["resultState"], string | null> = {
  normal: null,
  empty: "No output",
  noMatches: "No matches",
};

/** The chip title: the running title while the call runs, then the title with the host's result state. */
export function nativeChipTitle(view: NativeToolView, resolved: boolean): string {
  const state = resolved ? RESULT_STATE[view.resultState] : null;
  const title = nativeToolTitle(view, resolved);
  return state === null ? title : `${title} · ${state}`;
}

/**
 * What a detail read is current for. The record is rewritten while the call
 * runs and once more when it settles, so an earlier read is stale when this changes.
 */
export function nativeDetailVersion(view: NativeToolView, resolved: boolean, isError: boolean): string {
  return `${resolved ? 1 : 0}:${isError ? 1 : 0}:${view.durationMs ?? "-"}:${view.detailBytes ?? "-"}`;
}

/** `quiet` hides a successful call unless details are shown; a failure is always visible. */
export function nativeToolVisible(view: NativeToolView, isError: boolean, showQuiet: boolean): boolean {
  return !view.quiet || isError || showQuiet;
}

/**
 * Adjacent calls sharing a group key render as one row ("<label> · <item>, <item>").
 * Returns the label for a run of views, or null when they do not all share a key.
 */
export function nativeGroupLabel(views: readonly NativeToolView[]): string | null {
  const first = views[0]?.group;
  if (first === undefined || first === null || views.length === 0) {
    return null;
  }
  if (!views.every((view) => view.group?.key === first.key)) {
    return null;
  }
  return `${first.label} · ${views.map((view) => view.group?.item ?? "").join(", ")}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ---------------------------------------------------------------------------
// Boundary validation for untyped blobs
// ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isSeries(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.blobRef === "string" &&
    typeof value.chunks === "number" &&
    typeof value.bytes === "number" &&
    typeof value.records === "number"
  );
}

/**
 * A tool detail blob read from the engine. The envelope fields are checked;
 * `input`, `details` and the other host JSON stay `unknown` and are shown as
 * the host wrote them. Anything that is not a detail record is rejected.
 */
export function parseToolDetail(raw: unknown): NativeToolDetail | null {
  if (!isRecord(raw) || typeof raw.name !== "string" || typeof raw.toolCallId !== "string" || !isRecord(raw.view)) {
    return null;
  }
  if (raw.progress !== null && raw.progress !== undefined && !isSeries(raw.progress)) {
    return null;
  }
  if (raw.stream !== null && raw.stream !== undefined && !isSeries(raw.stream)) {
    return null;
  }
  if (raw.output !== null && raw.output !== undefined && typeof raw.output !== "string") {
    return null;
  }
  return {
    name: raw.name,
    toolCallId: raw.toolCallId,
    invocationId: typeof raw.invocationId === "string" ? raw.invocationId : null,
    input: raw.input,
    rawInput: typeof raw.rawInput === "string" ? raw.rawInput : null,
    isError: typeof raw.isError === "boolean" ? raw.isError : null,
    output: typeof raw.output === "string" ? raw.output : null,
    displayContent: Array.isArray(raw.displayContent) ? raw.displayContent : [],
    details: raw.details,
    outputProfile: raw.outputProfile,
    compactions: Array.isArray(raw.compactions) ? raw.compactions : [],
    progress: (raw.progress ?? null) as NativeToolDetail["progress"],
    stream: (raw.stream ?? null) as NativeToolDetail["stream"],
    view: raw.view as unknown as NativeToolView,
  };
}

function isUsage(value: unknown): value is NativeUsage {
  return (
    isRecord(value) &&
    ["inputTokens", "outputTokens", "reasoningTokens", "cacheReadTokens", "cacheWriteTokens", "totalTokens"].every(
      (key) => typeof value[key] === "number",
    )
  );
}

const CHILD_STATUSES: ReadonlySet<string> = new Set(Object.keys(CHILD_STATUS));

/** A child's terminal outcome, from `GetNativeChildOutcome` or an attempt's `outcomeRef` blob. */
export function parseChildOutcome(raw: unknown): NativeChildOutcome | null {
  if (
    !isRecord(raw) ||
    typeof raw.handle !== "string" ||
    typeof raw.attempt !== "number" ||
    typeof raw.status !== "string" ||
    !CHILD_STATUSES.has(raw.status) ||
    !isUsage(raw.usage) ||
    !Array.isArray(raw.changedFiles) ||
    !raw.changedFiles.every((file): file is string => typeof file === "string")
  ) {
    return null;
  }
  return {
    handle: raw.handle,
    attempt: raw.attempt,
    status: raw.status as NativeChildStatus,
    result: typeof raw.result === "string" ? raw.result : null,
    error: typeof raw.error === "string" ? raw.error : null,
    changedFiles: raw.changedFiles,
    usage: raw.usage,
  };
}

/** Progress is JSON lines, one JSON string per line; a malformed line is shown as written. */
export function parseProgressLines(text: string): string[] {
  return text
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => {
      try {
        const value: unknown = JSON.parse(line);
        return typeof value === "string" ? value : line;
      } catch {
        return line;
      }
    });
}

export function pretty(value: unknown): string {
  return typeof value === "string" ? value : (JSON.stringify(value, null, 2) ?? "");
}

export function usageLine(usage: NativeUsage): string {
  const parts = [
    `${usage.totalTokens.toLocaleString("en-US")} tokens`,
    `${usage.inputTokens.toLocaleString("en-US")} in`,
    `${usage.outputTokens.toLocaleString("en-US")} out`,
  ];
  if (usage.reasoningTokens > 0) {
    parts.push(`${usage.reasoningTokens.toLocaleString("en-US")} reasoning`);
  }
  if (usage.cacheReadTokens > 0) {
    parts.push(`${usage.cacheReadTokens.toLocaleString("en-US")} cache read`);
  }
  if (usage.cacheWriteTokens > 0) {
    parts.push(`${usage.cacheWriteTokens.toLocaleString("en-US")} cache write`);
  }
  return parts.join(", ");
}

// ---------------------------------------------------------------------------
// Complete exports
// ---------------------------------------------------------------------------

/**
 * A complete export: every record whole and separate, each under a header that
 * names it and its exact byte count. Nothing is parsed, re-encoded or cut.
 */
export function composeExport(title: string, parts: readonly { readonly label: string; readonly bytes: Uint8Array }[]): Uint8Array {
  const encoder = new TextEncoder();
  const chunks: Uint8Array[] = [
    encoder.encode(`${title}\nEach section is one engine record, copied byte for byte. Its header gives the exact byte count.\n`),
  ];
  for (const part of parts) {
    chunks.push(encoder.encode(`\n=== ${part.label} (${part.bytes.length} bytes) ===\n`), part.bytes, encoder.encode("\n=== end ===\n"));
  }
  const out = new Uint8Array(chunks.reduce((sum, chunk) => sum + chunk.length, 0));
  let at = 0;
  for (const chunk of chunks) {
    out.set(chunk, at);
    at += chunk.length;
  }
  return out;
}
