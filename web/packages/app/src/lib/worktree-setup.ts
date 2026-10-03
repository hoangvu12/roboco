import { RpcError, methods } from "@roboco/engine-client";
import type { ProjectActionRun, TerminalSession, WorktreeSpec } from "@roboco/proto";

/**
 * The worktree-setup outcome path — the web port of the desktop's two
 * halves: the composer's post-send handoff poll (composer.rs:8625-8703)
 * and the shell's attach (`shell/actions_ui.rs:42-83`,
 * `attach_worktree_setup`).
 *
 * A send whose `RunRequest` carries a space-scoped `WorktreeSpec` makes the
 * host create the isolated worktree at drain time and run the space's setup
 * Action in it; the queue reply returns before any of that happens, so the
 * sender POLLS the short-lived, command-scoped handoff until the host
 * publishes the outcome. A running setup attaches and selects a terminal
 * tab titled with the action name; a failed setup posts the sidebar
 * notice. Repeated outcomes attach the same surface rather than
 * duplicating tabs.
 *
 * The send-side worktree-spec payload shape (how the web composer would
 * carry a checkout plan into the send) is deliberately out of scope for the
 * parity spec — the poll keys off the spec the caller hands it, so the
 * outcome wiring is complete and RPC-path-testable while the payload work
 * stays deferred.
 */

/** The desktop's poll cadence: 250ms between attempts, 480 attempts bound. */
export const WORKTREE_SETUP_POLL_INTERVAL_MS = 250;
export const WORKTREE_SETUP_POLL_ATTEMPTS = 480;

/** One ready handoff: the setup Action's run (terminal attached engine-side),
 *  or the failure that replaced it. Both-null is a valid quiet outcome. */
export interface WorktreeSetupOutcome {
  readonly setupAction: ProjectActionRun | null;
  readonly setupError: string | null;
}

/** The minimal caller shape — `EngineClient` satisfies it. */
export type WorktreeSetupCaller = {
  call<T>(method: string, params?: unknown): Promise<T>;
};

/** A whole-prompt default sleep; tests inject a resolved promise. */
function defaultSleep(ms: number): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

/** An old engine's UnknownMethod reply — the poll's silent stop condition. */
function isUnknownMethod(error: unknown): boolean {
  if (error instanceof RpcError) {
    return error.kind === "unknown-method";
  }
  const message = error instanceof Error ? error.message : String(error);
  const lowered = message.toLowerCase();
  return lowered.includes("unknown method") || lowered.includes("unknownmethod");
}

/** The reply's `setupAction` half (a malformed row reads as absent, like the
 *  desktop's fallible serde decode). */
function parseSetupAction(value: unknown): ProjectActionRun | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const run = value as Partial<ProjectActionRun>;
  if (typeof run.actionId !== "string" || typeof run.actionName !== "string") {
    return null;
  }
  return { actionId: run.actionId, actionName: run.actionName, terminal: run.terminal as TerminalSession };
}

/**
 * The handoff reply: `{ready, setupAction, setupError}` — null while the
 * host has not published an outcome yet (keep polling).
 */
export function parseWorktreeSetupReply(reply: unknown): WorktreeSetupOutcome | null {
  if (typeof reply !== "object" || reply === null) {
    return null;
  }
  const record = reply as Record<string, unknown>;
  if (record.ready !== true) {
    return null;
  }
  return {
    setupAction: parseSetupAction(record.setupAction),
    setupError: typeof record.setupError === "string" ? record.setupError : null,
  };
}

/**
 * `expects_setup_handoff` + the bounded poll (composer.rs:8629-8703): a
 * send whose run carries a SPACE-scoped worktree spec polls
 * `TakeProjectActionSetup` for its command until the host publishes the
 * outcome (resolved), the engine turns out to predate the method
 * (resolved null, silently — the same UnknownMethod stop the desktop
 * makes), or the attempt bound runs out (resolved null). Transient errors
 * ride out exactly like the desktop's catch-all arm.
 */
export async function pollWorktreeSetupOutcome(
  caller: WorktreeSetupCaller,
  options: {
    readonly chatId: string;
    readonly commandId: string;
    readonly worktree: WorktreeSpec | null;
    /** The engine-local routing hint (`projectActionParams`' rule: only
     *  when the chat's device is not the local one). Stripped at the socket. */
    readonly targetDeviceId?: string | null;
    readonly sleep?: (ms: number) => Promise<void>;
  },
): Promise<WorktreeSetupOutcome | null> {
  if (options.worktree === null || options.worktree.spaceId == null) {
    return null;
  }
  const sleep = options.sleep ?? defaultSleep;
  const params: Record<string, unknown> = { chatId: options.chatId, commandId: options.commandId };
  if (options.targetDeviceId != null) {
    params.targetDeviceId = options.targetDeviceId;
  }
  for (let attempt = 0; attempt < WORKTREE_SETUP_POLL_ATTEMPTS; attempt += 1) {
    let reply: unknown;
    try {
      reply = await caller.call(methods.TAKE_PROJECT_ACTION_SETUP, params);
    } catch (error) {
      if (isUnknownMethod(error)) {
        return null;
      }
      reply = null; // A transient failure polls again, like `Err(_) => {}`.
    }
    const outcome = parseWorktreeSetupReply(reply);
    if (outcome !== null) {
      return outcome;
    }
    await sleep(WORKTREE_SETUP_POLL_INTERVAL_MS);
  }
  return null;
}

/**
 * The externally-managed terminal API the attach needs — the drawer
 * terminal store's peer of `reserve_tab_for_chat` /
 * `attach_reserved_session` / `select_tab_by_key` (panel.rs:494-560).
 * Structural, so the drawer store satisfies it without this module
 * importing the xterm-bearing store.
 */
export interface WorktreeSetupTerminals {
  reserveTabForChat(chatId: string, title: string): string | null;
  attachReservedSession(chatId: string, key: string, session: TerminalSession): boolean;
  selectTabByKey(chatId: string, key: string): void;
}

/**
 * The shell's attach half (`attach_worktree_setup`), per terminal host: a
 * failed setup posts the notice; a running one reserves the drawer tab
 * titled `{action name} (setup)`, attaches the engine-side terminal, and
 * selects the tab; an attach that cannot land (the tab closed, or no
 * engine session is bound) posts the desktop's fallback notice. Repeated
 * outcomes attach the SAME surface — the surfacer remembers the tab each
 * `(chat, title)` landed on, re-reserving only after that tab closed.
 */
export class WorktreeSetupSurfacer {
  readonly #terminals: WorktreeSetupTerminals;
  readonly #tabs = new Map<string, string>();

  constructor(terminals: WorktreeSetupTerminals) {
    this.#terminals = terminals;
  }

  surface(chatId: string, outcome: WorktreeSetupOutcome, onNotice: (message: string) => void): void {
    if (outcome.setupError !== null) {
      onNotice(`Setup action failed: ${outcome.setupError}`);
    }
    const run = outcome.setupAction;
    if (run === null) {
      return;
    }
    const title = `${run.actionName} (setup)`;
    const dedupeKey = `${chatId}\u{0}${title}`;
    const remembered = this.#tabs.get(dedupeKey) ?? null;
    if (remembered !== null && this.#terminals.attachReservedSession(chatId, remembered, run.terminal)) {
      this.#terminals.selectTabByKey(chatId, remembered);
      return;
    }
    const key = this.#terminals.reserveTabForChat(chatId, title);
    if (key === null || !this.#terminals.attachReservedSession(chatId, key, run.terminal)) {
      onNotice("Setup action started, but its terminal could not be attached");
      return;
    }
    this.#tabs.set(dedupeKey, key);
    this.#terminals.selectTabByKey(chatId, key);
  }
}
