import { useCallback, useEffect, useState } from "react";
import type { AgentAccount, AgentAccountsSnapshot, AgentLoginStart, HarnessId } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useNow } from "../state/hooks";
import {
  accountInitial,
  accountLabel,
  activateAgentAccount,
  cancelAgentLogin,
  completeAgentLogin,
  forceUsageFor,
  forgetAgentAccount,
  formatReset,
  listAgentAccounts,
  loginTitle,
  pollAgentLogin,
  providerAccounts,
  providerEmptyCopy,
  PROVIDERS,
  startAgentLogin,
  usageColorVar,
  usageFallback,
  usageLevel,
  type LoadTrigger,
  type ProviderDescriptor,
} from "../lib/accounts";

/**
 * Harness accounts settings (desktop settings/accounts.rs parity): one
 * provider section per harness CLI (Claude Code, Codex, Cursor) with its
 * account rows — email, plan and Active badges, usage meters, Switch and
 * Forget on inactive rows — plus the add-account login flows (paste-code
 * and browser-poll). The visit's first list forces a usage probe; post-
 * action lists ride the still-warm cache. All RPC failures render inline.
 */

type Loadable = { kind: "loading" } | { kind: "ready"; snapshot: AgentAccountsSnapshot } | { kind: "error"; message: string };

type LoginFlow =
  | { kind: "starting"; harness: HarnessId }
  | { kind: "paste-code"; harness: HarnessId; start: AgentLoginStart; submitting: boolean; error: string | null }
  | { kind: "browser"; harness: HarnessId; start: AgentLoginStart; message: string | null; error: string | null };

export function AccountsSettingsPage() {
  const session = useEngineSession();
  const client = session?.client ?? null;
  const [snapshot, setSnapshot] = useState<Loadable>({ kind: "loading" });
  const [busyAccount, setBusyAccount] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [login, setLogin] = useState<LoginFlow | null>(null);
  const now = useNow(30_000);

  const load = useCallback(
    async (trigger: LoadTrigger) => {
      if (client === null) {
        setSnapshot({ kind: "error", message: "Engine not connected" });
        return;
      }
      setSnapshot({ kind: "loading" });
      try {
        setSnapshot({ kind: "ready", snapshot: await listAgentAccounts(client, forceUsageFor(trigger)) });
      } catch (cause) {
        setSnapshot({ kind: "error", message: cause instanceof Error ? cause.message : String(cause) });
      }
    },
    [client],
  );

  // Mount load (forced usage probe), and a full reset on engine switch.
  useEffect(() => {
    setBusyAccount(null);
    setActionError(null);
    setLogin(null);
    void load("mount");
  }, [load]);

  // The browser-flow wait loop: poll until the login lands or is dismissed.
  const browserLoginId = login?.kind === "browser" ? login.start.loginId : null;
  useEffect(() => {
    if (client === null || browserLoginId === null) {
      return;
    }
    let active = true;
    void (async () => {
      const poll = await pollAgentLogin(client, browserLoginId, {
        isActive: () => active,
        onPending: (message) => {
          if (active && message !== null) {
            setLogin((current) => (current?.kind === "browser" ? { ...current, message } : current));
          }
        },
      });
      if (!active || poll === null) {
        return;
      }
      if (poll.status === "done") {
        setLogin(null);
        void load("postLogin");
      } else {
        setLogin((current) =>
          current?.kind === "browser" ? { ...current, error: poll.message ?? "Login failed" } : current,
        );
      }
    })();
    return () => {
      active = false;
    };
  }, [client, browserLoginId, load]);

  function accountAction(action: "activate" | "forget", account: AgentAccount) {
    if (client === null || busyAccount !== null) {
      return;
    }
    setBusyAccount(account.id);
    setActionError(null);
    void (async () => {
      try {
        if (action === "activate") {
          await activateAgentAccount(client, account);
        } else {
          await forgetAgentAccount(client, account);
        }
        void load("postAction");
      } catch (cause) {
        setActionError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        setBusyAccount(null);
      }
    })();
  }

  function addAccount(harness: HarnessId) {
    if (client === null || login !== null) {
      return;
    }
    setActionError(null);
    setLogin({ kind: "starting", harness });
    void (async () => {
      try {
        const start = await startAgentLogin(client, harness);
        window.open(start.url, "_blank", "noopener,noreferrer");
        setLogin(
          start.mode === "paste-code"
            ? { kind: "paste-code", harness, start, submitting: false, error: null }
            : { kind: "browser", harness, start, message: null, error: null },
        );
      } catch (cause) {
        setLogin(null);
        setActionError(`Login failed to start: ${cause instanceof Error ? cause.message : String(cause)}`);
      }
    })();
  }

  function submitCode(code: string) {
    if (client === null || login?.kind !== "paste-code" || login.submitting) {
      return;
    }
    const trimmed = code.trim();
    if (trimmed.length === 0) {
      return;
    }
    const loginId = login.start.loginId;
    setLogin({ ...login, submitting: true, error: null });
    void (async () => {
      try {
        await completeAgentLogin(client, loginId, trimmed);
        setLogin(null);
        void load("postLogin");
      } catch (cause) {
        setLogin((current) =>
          current?.kind === "paste-code"
            ? { ...current, submitting: false, error: cause instanceof Error ? cause.message : String(cause) }
            : current,
        );
      }
    })();
  }

  function dismissLogin() {
    const loginId = login?.kind === "paste-code" || login?.kind === "browser" ? login.start.loginId : null;
    setLogin(null);
    if (client !== null && loginId !== null) {
      void cancelAgentLogin(client, loginId).catch(() => {});
    }
  }

  const accountCount = snapshot.kind === "ready" && snapshot.snapshot.accounts.length > 0 ? snapshot.snapshot.accounts.length : null;

  return (
    <div className="settings-page">
      <div className="settings-title-row">
        <h1 className="settings-title">
          Accounts{accountCount !== null ? <span className="settings-title-count">{accountCount}</span> : null}
        </h1>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={snapshot.kind === "loading"}
          onClick={() => void load("refresh")}
        >
          Refresh
        </button>
      </div>
      <p className="settings-subtitle">
        The Claude Code, Codex, and Cursor logins on this device. Roboco detects the live session, keeps each account
        backed up, and can swap between them.
      </p>

      {actionError !== null && (
        <p className="error-strip" role="alert" onClick={() => setActionError(null)}>
          {actionError}
        </p>
      )}

      {snapshot.kind === "error" ? (
        <p className="error-strip" role="alert" onClick={() => void load("retry")}>
          {snapshot.message}
          <span className="error-strip-hint">Click to retry</span>
        </p>
      ) : (
        PROVIDERS.map((provider) => (
          <ProviderSection
            key={provider.harness}
            provider={provider}
            loadable={snapshot}
            busyAccount={busyAccount}
            now={now}
            onAdd={() => addAccount(provider.harness)}
            onSwitch={(account) => accountAction("activate", account)}
            onForget={(account) => accountAction("forget", account)}
          />
        ))
      )}

      <p className="settings-footnote">
        Switching rewrites the CLI’s stored login, so new agent sessions use the selected account immediately. On
        macOS, an already-running Claude Code can hold the previous login for up to ~30 seconds (Keychain cache).
      </p>

      {login !== null && (
        <LoginDialog flow={login} onCancel={dismissLogin} onSubmitCode={submitCode} />
      )}
    </div>
  );
}

function ProviderSection({
  provider,
  loadable,
  busyAccount,
  now,
  onAdd,
  onSwitch,
  onForget,
}: {
  readonly provider: ProviderDescriptor;
  readonly loadable: Loadable;
  readonly busyAccount: string | null;
  readonly now: number;
  readonly onAdd: () => void;
  readonly onSwitch: (account: AgentAccount) => void;
  readonly onForget: (account: AgentAccount) => void;
}) {
  const loading = loadable.kind === "loading";
  const accounts = loadable.kind === "ready" ? providerAccounts(loadable.snapshot, provider.harness) : [];
  const warnings = loadable.kind === "ready" ? loadable.snapshot.warnings.filter((w) => w.harness === provider.harness) : [];
  return (
    <section className="settings-provider">
      <div className="settings-section-header">
        <h2>{provider.name}</h2>
        {!loading && (
          <button type="button" className="btn btn-ghost" onClick={onAdd}>
            Add account
          </button>
        )}
      </div>
      {warnings.map((warning, index) => (
        <p className="warning-strip" role="status" key={index}>
          {warning.message}
        </p>
      ))}
      <div className="settings-card">
        {loading ? (
          <>
            <SkeletonRow />
            <SkeletonRow dim />
          </>
        ) : accounts.length === 0 ? (
          <p className="settings-empty settings-empty-center">{providerEmptyCopy(provider)}</p>
        ) : (
          accounts.map((account) => (
            <AccountRow
              key={account.id}
              account={account}
              busy={busyAccount === account.id}
              now={now}
              onSwitch={() => onSwitch(account)}
              onForget={() => onForget(account)}
            />
          ))
        )}
      </div>
    </section>
  );
}

function AccountRow({
  account,
  busy,
  now,
  onSwitch,
  onForget,
}: {
  readonly account: AgentAccount;
  readonly busy: boolean;
  readonly now: number;
  readonly onSwitch: () => void;
  readonly onForget: () => void;
}) {
  return (
    <div className="settings-row settings-account-row">
      <span className="account-avatar" aria-hidden>
        {accountInitial(account)}
      </span>
      <div className="settings-row-main">
        <span className="settings-row-title">{accountLabel(account)}</span>
        {account.usageWindows.length === 0 ? (
          <span className="settings-row-meta">{usageFallback(account)}</span>
        ) : (
          <span className="usage-list">
            {account.usageWindows.map((window, index) => (
              <UsageMeter key={index} label={window.label} usedFraction={window.usedFraction} resetsAt={window.resetsAt} now={now} />
            ))}
          </span>
        )}
      </div>
      <div className="account-side">
        <span className="account-badges">
          {account.active && <span className="badge badge-active">Active</span>}
          {account.planLabel != null && <span className="badge">{account.planLabel}</span>}
        </span>
        {!account.active && (
          <span className="account-actions">
            <button type="button" className="btn btn-danger-ghost" disabled={busy} onClick={onForget}>
              Forget
            </button>
            {account.switchable && (
              <button type="button" className="btn btn-solid" disabled={busy} onClick={onSwitch}>
                {busy ? "Switching…" : "Switch"}
              </button>
            )}
          </span>
        )}
      </div>
    </div>
  );
}

function UsageMeter({
  label,
  usedFraction,
  resetsAt,
  now,
}: {
  readonly label: string;
  readonly usedFraction: number;
  readonly resetsAt: string | null;
  readonly now: number;
}) {
  const fraction = Math.min(1, Math.max(0, usedFraction));
  const level = usageLevel(fraction);
  const reset = formatReset(resetsAt, now);
  return (
    <span className="usage-meter">
      <span className="usage-label">{label}</span>
      <span className="usage-track">
        {fraction > 0 && (
          <span
            className="usage-fill"
            style={{ width: `${Math.max(fraction, 0.015) * 100}%`, background: usageColorVar(level) }}
          />
        )}
      </span>
      <span className="usage-percent">{Math.round(fraction * 100)}% used</span>
      {reset !== null && <span className="usage-reset">{reset}</span>}
    </span>
  );
}

function SkeletonRow({ dim = false }: { readonly dim?: boolean }) {
  return (
    <div className={`settings-row skeleton-row ${dim ? "skeleton-dim" : ""}`} aria-hidden>
      <span className="account-avatar skeleton-block" />
      <div className="settings-row-main">
        <span className="skeleton-block skeleton-line" />
        <span className="skeleton-block skeleton-line skeleton-line-short" />
      </div>
    </div>
  );
}

function LoginDialog({
  flow,
  onCancel,
  onSubmitCode,
}: {
  readonly flow: LoginFlow;
  readonly onCancel: () => void;
  readonly onSubmitCode: (code: string) => void;
}) {
  const [code, setCode] = useState("");
  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <section
        className="modal-card panel"
        role="dialog"
        aria-label={loginTitle(flow.harness)}
        onClick={(event) => event.stopPropagation()}
      >
        <h2 className="modal-title">{loginTitle(flow.harness)}</h2>
        {flow.kind === "starting" && (
          <p className="settings-row-meta modal-body">Starting the login flow…</p>
        )}
        {flow.kind === "paste-code" && (
          <>
            <p className="settings-row-meta modal-body">
              A browser window opened. Sign in to the account you want to add, approve access, then paste the code
              Anthropic shows you below. Your current login is untouched until you switch.
            </p>
            <a className="modal-link" href={flow.start.url} target="_blank" rel="noopener noreferrer">
              Reopen the authorization page
            </a>
            <form
              className="modal-form"
              onSubmit={(event) => {
                event.preventDefault();
                onSubmitCode(code);
              }}
            >
              <input
                className="input mono"
                type="text"
                placeholder="Paste the authorization code"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                autoComplete="off"
                spellCheck={false}
                autoFocus
              />
              {flow.error !== null && <p className="form-error">{flow.error}</p>}
              <div className="modal-actions">
                <button type="button" className="btn btn-ghost" onClick={onCancel}>
                  Cancel
                </button>
                <button type="submit" className="btn btn-solid" disabled={flow.submitting || code.trim().length === 0}>
                  {flow.submitting ? "Verifying…" : "Add account"}
                </button>
              </div>
            </form>
          </>
        )}
        {flow.kind === "browser" && (
          <>
            <p className="settings-row-meta modal-body">
              {flow.harness === "cursor"
                ? "Finish signing in to Cursor in your browser. This mints a roboco-named API key you can revoke any time from Cursor's dashboard — it is separate from `cursor-agent login`."
                : "Finish signing in to OpenAI in your browser. The new login is captured in an isolated profile — your current session is untouched until you switch."}
            </p>
            <a className="modal-link" href={flow.start.url} target="_blank" rel="noopener noreferrer">
              Reopen the sign-in page
            </a>
            {flow.error === null ? (
              <p className="settings-row-meta modal-poll">
                <span className="dot dot-working" /> {flow.message ?? "Waiting for the browser…"}
              </p>
            ) : (
              <p className="form-error">{flow.error}</p>
            )}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={onCancel}>
                {flow.error !== null ? "Close" : "Cancel"}
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
