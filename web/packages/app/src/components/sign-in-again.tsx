import { useCallback, useEffect, useRef, useState } from "react";
import type { EngineClient } from "@roboco/engine-client";
import type { AgentAccount, AgentLoginStart, HarnessId } from "@roboco/proto";
import { loginOptions, pollAgentLogin, providerName, signInOptionFor, startAgentLogin, completeAgentLogin, cancelAgentLogin } from "../lib/accounts";

/**
 * The inline "Sign in again" flow — the web peer of the desktop
 * `AccountUsage`'s `SignInFlow` (account_usage.rs:57-105) and its
 * `sign_in_footer`. The engine's own login (`StartAgentLogin`), driven
 * inside the card that offered the action: the browser opens, the row
 * reports progress, and a landed sign-in re-probes (the caller reloads its
 * list — fresh credentials flip the row back to signed in).
 *
 * Shared by every surface that lists a signed-out account with the action:
 * the composer footer's account ring (the desktop's home for it), the
 * Limited banner's failover picker (stories 47/50), and — dialog-shaped —
 * Settings → Accounts (its own `LoginDialog`).
 */

/** The flow's live state (desktop `SignInFlow`): null = idle. */
export type SignInAgainFlow =
  | { kind: "starting"; harness: HarnessId; provider: string | null }
  | { kind: "failed"; harness: HarnessId; provider: string | null; error: string }
  | { kind: "browser"; harness: HarnessId; provider: string | null; start: AgentLoginStart; message: string | null; error: string | null }
  | { kind: "paste-code"; harness: HarnessId; provider: string | null; start: AgentLoginStart; submitting: boolean; error: string | null };

export interface UseSignInAgainFlowOptions {
  readonly client: EngineClient | null;
  /** The device whose accounts are listed (the settings switcher's passthrough). */
  readonly targetDeviceId?: string | null;
  /** A landed sign-in: the caller re-probes (the row flips back to signed in). */
  readonly onLanded: () => void;
}

export interface SignInAgainHandle {
  readonly flow: SignInAgainFlow | null;
  /** Begin the flow for one account (the row's "Sign in again" press). */
  readonly begin: (account: AgentAccount) => void;
  /** Cancel the flow (the engine kills it; the CLI child is reaped). */
  readonly cancel: () => void;
  /** Submit the pasted code of a paste-code sign-in. */
  readonly submitCode: (code: string) => void;
}

/**
 * The flow's state machine. The browser wait loop is the lib's
 * `pollAgentLogin` (1.5s PollAgentLogin until `done`/`error`), cancelled
 * when the flow goes away — a dismissed popover stops polling.
 */
export function useSignInAgainFlow(options: UseSignInAgainFlowOptions): SignInAgainHandle {
  const [flow, setFlow] = useState<SignInAgainFlow | null>(null);
  const { client, targetDeviceId, onLanded } = options;
  // The landed callback is a closure callers rebuild per render; the loop
  // must not re-arm on its identity, so it rides a ref. The flow rides one
  // too: `cancel` reads the live state without a state-updater side
  // effect (StrictMode runs updaters twice).
  const onLandedRef = useRef(onLanded);
  onLandedRef.current = onLanded;
  const flowRef = useRef<SignInAgainFlow | null>(null);
  flowRef.current = flow;

  /** Begin: pre-open a blank tab inside the click gesture (popup blockers
   *  treat a post-await `window.open` as un-gestured — the settings page's
   *  add-account trick), start the login, route the tab to the page. */
  const begin = useCallback(
    (account: AgentAccount) => {
      if (client === null || flow !== null) {
        return;
      }
      const provider = signInOptionFor(account);
      const tab = window.open("about:blank", "_blank");
      setFlow({ kind: "starting", harness: account.harness, provider });
      void (async () => {
        try {
          const start = await startAgentLogin(client, account.harness, targetDeviceId, provider);
          if (start.cliOpensBrowser) {
            // The engine machine's CLI already opened the page — one tab total.
            tab?.close();
          } else if (tab !== null) {
            tab.location.href = start.url;
            tab.opener = null;
          } else {
            // Hard blocker ate the pre-open — retry the old direct open.
            window.open(start.url, "_blank", "noopener,noreferrer");
          }
          setFlow(
            start.mode === "paste-code"
              ? { kind: "paste-code", harness: account.harness, provider, start, submitting: false, error: null }
              : { kind: "browser", harness: account.harness, provider, start, message: null, error: null },
          );
        } catch (cause) {
          tab?.close();
          setFlow((current) =>
            current?.kind === "starting"
              ? {
                  kind: "failed",
                  harness: account.harness,
                  provider,
                  error: `Login failed to start: ${cause instanceof Error ? cause.message : String(cause)}`,
                }
              : current,
          );
        }
      })();
    },
    [client, flow, targetDeviceId],
  );

  const cancel = useCallback(() => {
    const current = flowRef.current;
    if (
      current !== null &&
      (current.kind === "browser" || current.kind === "paste-code") &&
      client !== null
    ) {
      // Best-effort; the desktop only debug-logs a failure.
      void cancelAgentLogin(client, current.start.loginId, targetDeviceId).catch(() => {});
    }
    setFlow(null);
  }, [client, targetDeviceId]);

  const submitCode = useCallback(
    (code: string) => {
      const trimmed = code.trim();
      if (client === null || flow?.kind !== "paste-code" || flow.submitting || trimmed.length === 0) {
        return;
      }
      const loginId = flow.start.loginId;
      setFlow({ ...flow, submitting: true, error: null });
      void (async () => {
        try {
          await completeAgentLogin(client, loginId, trimmed, targetDeviceId);
          setFlow(null);
          onLandedRef.current();
        } catch (cause) {
          setFlow((current) =>
            current?.kind === "paste-code"
              ? { ...current, submitting: false, error: cause instanceof Error ? cause.message : String(cause) }
              : current,
          );
        }
      })();
    },
    [client, flow, targetDeviceId],
  );

  // The browser-flow wait loop, armed only while a browser flow is live.
  const browserLoginId = flow?.kind === "browser" ? flow.start.loginId : null;
  const activeRef = useRef(true);
  useEffect(() => {
    activeRef.current = true;
    return () => {
      activeRef.current = false;
    };
  }, []);
  useEffect(() => {
    if (client === null || browserLoginId === null) {
      return;
    }
    let active = true;
    void (async () => {
      const poll = await pollAgentLogin(
        client,
        browserLoginId,
        {
          isActive: () => active && activeRef.current,
          onPending: (message) => {
            if (active && message !== null) {
              setFlow((current) => (current?.kind === "browser" ? { ...current, message } : current));
            }
          },
        },
        targetDeviceId,
      );
      if (!active || poll === null) {
        return;
      }
      if (poll.status === "done") {
        setFlow(null);
        onLandedRef.current();
      } else {
        setFlow((current) =>
          current?.kind === "browser" ? { ...current, error: poll.message ?? "Login failed" } : current,
        );
      }
    })();
    return () => {
      active = false;
    };
  }, [client, browserLoginId, targetDeviceId]);

  return { flow, begin, cancel, submitCode };
}

/**
 * The inline progress card (the desktop's `sign_in_footer`): who is being
 * signed in to, the paste-code field when the CLI has no loopback, and its
 * failure. Nothing renders while idle.
 */
export function SignInAgainCard({
  flow,
  onCancel,
  onSubmitCode,
}: {
  readonly flow: SignInAgainFlow;
  readonly onCancel: () => void;
  readonly onSubmitCode: (code: string) => void;
}) {
  const [code, setCode] = useState("");
  if (flow.kind === "failed") {
    return (
      <div className="sign-in-again-card" role="alert">
        <span className="sign-in-again-who">Signing in to {whoFor(flow.harness, flow.provider)}</span>
        <p className="sign-in-again-error">{flow.error}</p>
        <div className="sign-in-again-actions">
          <button type="button" className="sign-in-again-cancel" onClick={onCancel}>
            Close
          </button>
        </div>
      </div>
    );
  }
  if (flow.kind === "paste-code") {
    return (
      <div className="sign-in-again-card" role="status">
        <span className="sign-in-again-who">Signing in to {whoFor(flow.harness, flow.provider)} — paste the code</span>
        <a className="login-dialog-link" href={flow.start.url} target="_blank" rel="noopener noreferrer">
          Reopen the authorization page
        </a>
        <form
          className="sign-in-again-form"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmitCode(code);
          }}
        >
          <input
            className="mono"
            type="text"
            placeholder="Paste the authorization code"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            aria-label="Authorization code"
          />
          <div className="sign-in-again-actions">
            <button type="button" className="sign-in-again-cancel" onClick={onCancel}>
              Cancel
            </button>
            <button
              type="submit"
              className="sign-in-again-submit"
              disabled={flow.submitting || code.trim().length === 0}
            >
              {flow.submitting ? "Verifying…" : "Add account"}
            </button>
          </div>
        </form>
        {flow.error !== null && <p className="sign-in-again-error">{flow.error}</p>}
      </div>
    );
  }
  return (
    <div className="sign-in-again-card" role="status">
      <span className="sign-in-again-who">Signing in to {whoFor(flow.harness, flow.provider)} — finish in your browser</span>
      {flow.kind === "browser" && flow.error === null && (
        <p className="sign-in-again-pending">
          <span className="dot dot-working" /> {flow.message ?? "Waiting for the browser…"}
        </p>
      )}
      {flow.kind === "browser" && flow.error !== null && <p className="sign-in-again-error">{flow.error}</p>}
      <div className="sign-in-again-actions">
        <button type="button" className="sign-in-again-cancel" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  );
}

/** Who is being signed in to (the desktop's `parts()` + label lookup). */
function whoFor(harness: HarnessId, provider: string | null): string {
  return loginOptions(harness).find((option) => option.provider === provider)?.label ?? providerName(harness);
}
