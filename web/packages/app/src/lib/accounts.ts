import { methods, type EngineClient } from "@roboco/engine-client";
import type {
  AgentAccount,
  AgentAccountsSnapshot,
  AgentLoginPoll,
  AgentLoginStart,
  HarnessId,
} from "@roboco/proto";

/**
 * Harness accounts settings — the web peer of the desktop's
 * settings/accounts.rs page ("Agents" → Accounts; the wire keeps the legacy
 * `Agent*` names, ADR 0005). Usage thresholds, reset wording, provider
 * order, and the force-usage policy are ported verbatim so the meters read
 * identically on both surfaces. The add-account login flows (paste-code and
 * browser-poll) follow the desktop's LoginFlow state machine.
 */

// ── RPC wrappers ────────────────────────────────────────────────────────
// Every mutation replies with the fresh AgentAccountsSnapshot, so the page
// repaints from one round trip. `targetDeviceId` is the device switcher's
// passthrough (accounts.rs:259-265): null (the local device) sends nothing —
// the calls stay direct; an explicit device rides every call the page makes.

function targetParams(targetDeviceId: string | null | undefined): Record<string, string> {
  return targetDeviceId == null ? {} : { targetDeviceId };
}

export function listAgentAccounts(
  client: EngineClient,
  forceUsage: boolean,
  targetDeviceId?: string | null,
): Promise<AgentAccountsSnapshot> {
  return client.call<AgentAccountsSnapshot>(methods.LIST_AGENT_ACCOUNTS, {
    forceUsage,
    ...targetParams(targetDeviceId),
  });
}

export function activateAgentAccount(
  client: EngineClient,
  account: AgentAccount,
  targetDeviceId?: string | null,
): Promise<AgentAccountsSnapshot> {
  // Tolerant param shape (desktop parity): both `id` and `accountId`.
  return client.call<AgentAccountsSnapshot>(methods.ACTIVATE_AGENT_ACCOUNT, {
    id: account.id,
    accountId: account.id,
    harness: account.harness,
    ...targetParams(targetDeviceId),
  });
}

export function forgetAgentAccount(
  client: EngineClient,
  account: AgentAccount,
  targetDeviceId?: string | null,
): Promise<AgentAccountsSnapshot> {
  return client.call<AgentAccountsSnapshot>(methods.FORGET_AGENT_ACCOUNT, {
    id: account.id,
    accountId: account.id,
    harness: account.harness,
    ...targetParams(targetDeviceId),
  });
}

export function startAgentLogin(
  client: EngineClient,
  harness: HarnessId,
  targetDeviceId?: string | null,
  provider?: string | null,
): Promise<AgentLoginStart> {
  return client.call<AgentLoginStart>(methods.START_AGENT_LOGIN, {
    harness,
    ...targetParams(targetDeviceId),
    ...(provider != null ? { provider } : {}),
  });
}

export function completeAgentLogin(
  client: EngineClient,
  loginId: string,
  code: string,
  targetDeviceId?: string | null,
): Promise<AgentAccountsSnapshot> {
  return client.call<AgentAccountsSnapshot>(methods.COMPLETE_AGENT_LOGIN, {
    loginId,
    code,
    ...targetParams(targetDeviceId),
  });
}

export function pollAgentLoginOnce(
  client: EngineClient,
  loginId: string,
  targetDeviceId?: string | null,
): Promise<AgentLoginPoll> {
  return client.call<AgentLoginPoll>(methods.POLL_AGENT_LOGIN, {
    loginId,
    ...targetParams(targetDeviceId),
  });
}

/** Best-effort; the desktop only debug-logs a failure. */
export async function cancelAgentLogin(
  client: EngineClient,
  loginId: string,
  targetDeviceId?: string | null,
): Promise<void> {
  await client.call(methods.CANCEL_AGENT_LOGIN, { loginId, ...targetParams(targetDeviceId) });
}

// ── Usage meters ────────────────────────────────────────────────────────

export const USAGE_WARN_FRACTION = 0.8;
export const USAGE_CRITICAL_FRACTION = 0.95;

/** Threshold classification of a usage fraction (usage_level). */
export type UsageLevel = "normal" | "warn" | "critical";

export function usageLevel(fraction: number): UsageLevel {
  if (fraction >= USAGE_CRITICAL_FRACTION) {
    return "critical";
  }
  if (fraction >= USAGE_WARN_FRACTION) {
    return "warn";
  }
  return "normal";
}

/**
 * The token behind each level (usage_color) at the meter-fill's own opacity
 * (accounts.rs:694-697): accent at 0.8 for Normal, warning/danger at 0.85 for
 * Warn/Critical — crossing a threshold swaps the bar's color AND lifts its
 * opacity a notch.
 */
export function usageColorVar(level: UsageLevel): string {
  switch (level) {
    case "critical":
      return "color-mix(in srgb, var(--rb-danger) 85%, transparent)";
    case "warn":
      return "color-mix(in srgb, var(--rb-warning) 85%, transparent)";
    default:
      return "color-mix(in srgb, var(--rb-accent) 80%, transparent)";
  }
}

/**
 * Compact absolute reset moment (format_reset): a local clock time when it
 * lands within ~22h, a short weekday within a week, else month + day. The
 * caller sees the "resets " prefix included.
 */
export function formatReset(resetsAt: string | null, now: number, locale: string = "en-US"): string | null {
  if (resetsAt === null) {
    return null;
  }
  const at = Date.parse(resetsAt);
  if (!Number.isFinite(at)) {
    return null;
  }
  const hours = (at - now) / 3_600_000;
  const date = new Date(at);
  if (hours < 22) {
    return `resets ${new Intl.DateTimeFormat(locale, { hour: "numeric", minute: "2-digit" }).format(date)}`;
  }
  if (hours < 24 * 7) {
    return `resets ${new Intl.DateTimeFormat(locale, { weekday: "short" }).format(date)}`;
  }
  return `resets ${new Intl.DateTimeFormat(locale, { month: "short", day: "numeric" }).format(date)}`;
}

// ── Load policy ─────────────────────────────────────────────────────────

/** Why a ListAgentAccounts load is happening (LoadTrigger). */
export type LoadTrigger = "mount" | "retry" | "refresh" | "postLogin" | "postAction";

/**
 * Whether a load asks the engine to probe usage (`forceUsage`). The engine
 * only hits the provider when forced; the visit's first list must force or
 * every first open renders "Usage unavailable" until a manual Refresh.
 */
export function forceUsageFor(trigger: LoadTrigger): boolean {
  switch (trigger) {
    case "mount":
    case "retry":
    case "refresh":
    case "postLogin":
      return true;
    case "postAction":
      return false;
  }
}

// ── Provider sections ───────────────────────────────────────────────────

export interface ProviderDescriptor {
  readonly harness: HarnessId;
  readonly name: string;
  /** CLI command named in the empty-state copy. */
  readonly cli: string;
}

/** The provider cards, in display order (accounts.rs PROVIDERS). Every
 * agent with a login of its own is here; what each one supports is
 * documented engine-side. Antigravity's section arrives with the
 * settings/providers polish wave. */
export const PROVIDERS: readonly ProviderDescriptor[] = [
  { harness: "claude-code", name: "Claude Code", cli: "claude" },
  { harness: "codex", name: "Codex", cli: "codex" },
  { harness: "cursor", name: "Cursor", cli: "cursor-agent" },
  { harness: "grok", name: "Grok", cli: "grok login" },
  { harness: "devin", name: "Devin", cli: "devin auth login" },
  { harness: "opencode", name: "OpenCode", cli: "opencode auth login" },
  { harness: "pi", name: "Pi", cli: "pi" },
  { harness: "hermes", name: "Hermes", cli: "hermes auth add" },
];

/** Display name of one agent (`provider_name`) — the fallback
 * [`LoginOption`] label for single-login agents. */
export function providerName(harness: HarnessId): string {
  switch (harness) {
    case "claude-code":
      return "Claude Code";
    case "codex":
      return "Codex";
    case "cursor":
      return "Cursor";
    case "antigravity":
      return "Antigravity";
    case "grok":
      return "Grok";
    case "devin":
      return "Devin";
    case "opencode":
      return "OpenCode";
    case "pi":
      return "Pi";
    case "hermes":
      return "Hermes";
    default:
      return "Agent";
  }
}

/** Whether Roboco switches this agent's logins. Hermes rotates through
 * its own credential pool (listed, never reordered); Antigravity keeps one. */
export function switchesAccounts(harness: HarnessId): boolean {
  return harness !== "hermes" && harness !== "antigravity";
}

/** A standing note under a provider's card, for an agent whose accounts
 * work differently. Hermes owns its credential pool: Roboco lists it and
 * adds to it through Hermes' own CLI, but never switches or removes its
 * entries. */
export function providerNote(harness: HarnessId): string | null {
  if (harness === "hermes") {
    return "Hermes manages its own credential pool and rotates through it. Accounts added here go through `hermes auth add`; remove one with `hermes auth remove`.";
  }
  return null;
}

/** One way to add an account: agents that keep a login PER model provider
 * (OpenCode, Pi, Hermes) sign in to a named provider; the rest have one. */
export interface LoginOption {
  /** The engine's `provider` param (`null` = the agent's only login). */
  readonly provider: string | null;
  /** Who the user signs in to. */
  readonly label: string;
}

/** The sign-ins Roboco offers for `harness`, in button order. */
export function loginOptions(harness: HarnessId): readonly LoginOption[] {
  switch (harness) {
    case "opencode":
      return [
        { provider: "openai", label: "ChatGPT" },
        { provider: "github-copilot", label: "GitHub Copilot" },
      ];
    case "pi":
      return [{ provider: "openai-codex", label: "ChatGPT" }];
    case "hermes":
      return [
        { provider: "openai-codex", label: "ChatGPT" },
        { provider: "nous", label: "Nous Portal" },
      ];
    default:
      return [{ provider: null, label: providerName(harness) }];
  }
}

/** The add-account button's label for one [`LoginOption`]: a per-provider
 * sign-in names its provider ("Connect ChatGPT", "Add GitHub Copilot
 * account"); single-login agents keep the plain label. */
export function addOptionLabel(option: LoginOption, empty: boolean): string {
  if (option.provider === null) {
    return "Add account";
  }
  return empty ? `Connect ${option.label}` : `Add ${option.label} account`;
}

/** The sign-in dialog's browser-wait copy — one sentence per provider, same
 * shape (accounts.rs `login_copy`). */
export function loginCopy(harness: HarnessId, provider: string | null): string {
  switch (harness) {
    case "claude-code":
      return "Finish signing in to Claude in your browser. The new login is saved next to your current one — nothing changes until you switch.";
    case "codex":
      return "Finish signing in to ChatGPT in your browser. The new login is saved next to your current one — nothing changes until you switch.";
    case "cursor":
      return "Finish signing in to Cursor in your browser. This mints a roboco-named API key you can revoke any time from Cursor's dashboard — it is separate from `cursor-agent login`.";
    case "grok":
      return "Finish signing in to Grok in your browser — approve the code shown below. The new login is saved next to your current one — nothing changes until you switch.";
    case "devin":
      return "Finish signing in to Devin in your browser. The new login is saved next to your current one — nothing changes until you switch.";
    case "opencode":
      if (provider === "github-copilot") {
        return "Finish signing in to GitHub in your browser — enter the code shown below. The new login is saved next to your current one — nothing changes until you switch.";
      }
      return "Finish signing in to ChatGPT in your browser. The agent gets its own login, saved next to any current one — nothing changes until you switch.";
    case "pi":
      return "Finish signing in to ChatGPT in your browser. The agent gets its own login, saved next to any current one — nothing changes until you switch.";
    case "hermes":
      return "Finish signing in in your browser — enter the code shown below. Hermes adds the login to its own credential pool and rotates through it itself.";
    default:
      return "Finish signing in in your browser.";
  }
}

/** The optimistic half of a switch: `account` becomes the live login of its
 * group — its agent, or for agents that keep a login per model provider,
 * that provider — and every other group keeps its own. */
export function markSwitched(snapshot: AgentAccountsSnapshot, account: AgentAccount): void {
  for (const row of snapshot.accounts) {
    if (row.harness === account.harness && row.provider === account.provider) {
      row.active = row.id === account.id;
    }
  }
}

/**
 * Accounts of one provider, in the engine's order — no active-first
 * re-sort: switching must not move the switched-to card.
 */
export function providerAccounts(snapshot: AgentAccountsSnapshot, harness: HarnessId): AgentAccount[] {
  return snapshot.accounts.filter((account) => account.harness === harness);
}

/** The empty-card copy under a provider with no accounts. */
export function providerEmptyCopy(provider: ProviderDescriptor): string {
  if (provider.harness === "cursor") {
    // Cursor's app login is separate from `cursor-agent login`.
    return `${provider.name} isn't connected on this device — connect it to run Cursor sessions.`;
  }
  return `No ${provider.name} login detected on this device — sign in with “${provider.cli}” or add an account.`;
}

/** The row's primary label (email, else display name, else the fallback). */
export function accountLabel(account: AgentAccount): string {
  return account.email ?? account.displayName ?? "Unknown account";
}

/** The avatar initial: the label's first letter, uppercased. */
export function accountInitial(account: AgentAccount): string {
  const first = accountLabel(account).charAt(0);
  return first.length > 0 ? first.toUpperCase() : "?";
}

/** Fallback line when a row has no usage windows (meters XOR this line). */
export function usageFallback(account: AgentAccount, refreshing = false): string {
  if (!account.switchable && switchesAccounts(account.harness)) {
    return account.harness === "claude-code" ? "Credentials unavailable" : "Couldn't identify this login";
  }
  if (account.usageError != null) {
    return account.usageError;
  }
  return refreshing ? "Checking usage…" : "Usage unavailable";
}

// ── Add-account login flows ─────────────────────────────────────────────

/** Dialog title for a login flow (LoginFlow::title); a per-provider
 * sign-in names who it is for ("Sign in to ChatGPT for OpenCode"). */
export function loginTitle(harness: HarnessId, provider?: string | null): string {
  const option = loginOptions(harness).find((option) => option.provider === (provider ?? null));
  if (option != null && option.provider !== null) {
    return `Sign in to ${option.label} for ${providerName(harness)}`;
  }
  switch (harness) {
    case "codex":
      return "Add Codex account";
    case "cursor":
      return "Connect Cursor";
    case "claude-code":
      // The long-standing dialog title (roboco's grammar).
      return "Add Claude account";
    default:
      return `Add ${providerName(harness)} account`;
  }
}

export interface PollLoginOptions {
  readonly intervalMs?: number;
  /** Return false to stop polling (dialog dismissed); the promise resolves null. */
  readonly isActive: () => boolean;
  /** Progress note from a pending poll ("Waiting for the browser…" chain). */
  readonly onPending: (message: string | null) => void;
  /** Injectable for tests. */
  readonly sleep?: (ms: number) => Promise<void>;
}

const DEFAULT_POLL_INTERVAL_MS = 1500;

/**
 * The browser-flow wait loop (spawn_poll): PollAgentLogin every 1.5s until
 * the flow lands (`done`/`error`) or the dialog goes away. A failed poll
 * resolves as an error-status poll so the dialog can show the message.
 */
export async function pollAgentLogin(
  client: EngineClient,
  loginId: string,
  options: PollLoginOptions,
  targetDeviceId?: string | null,
): Promise<AgentLoginPoll | null> {
  const intervalMs = options.intervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  while (options.isActive()) {
    await sleep(intervalMs);
    if (!options.isActive()) {
      return null;
    }
    let poll: AgentLoginPoll;
    try {
      poll = await pollAgentLoginOnce(client, loginId, targetDeviceId);
    } catch (error) {
      return { status: "error", message: `Poll failed: ${error instanceof Error ? error.message : String(error)}` };
    }
    switch (poll.status) {
      case "done":
      case "error":
        return poll;
      default:
        options.onPending(poll.message ?? null);
    }
  }
  return null;
}
