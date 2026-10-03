import { useCallback, useEffect, useState } from "react";
import type { NativeReadiness } from "@roboco/proto";
import { readinessView } from "../lib/native";
import { getNativeReadiness, type NativeCaller } from "../lib/native-actions";

type State =
  | { readonly phase: "loading" }
  | { readonly phase: "failed"; readonly message: string }
  | { readonly phase: "ready"; readonly readiness: NativeReadiness };

/**
 * Whether Mimir can run on the connected engine, with the step to take by
 * hand when it cannot. Roboco never installs Mimir or its plugin, and never
 * touches credentials; "Check again" only re-reads the engine's probe.
 */
export function MimirReadinessCard({ client }: { readonly client: NativeCaller }) {
  const [state, setState] = useState<State>({ phase: "loading" });
  const check = useCallback(
    (force: boolean) => {
      setState({ phase: "loading" });
      getNativeReadiness(client, force).then(
        (readiness) => setState({ phase: "ready", readiness }),
        (error: unknown) => setState({ phase: "failed", message: error instanceof Error ? error.message : String(error) }),
      );
    },
    [client],
  );
  useEffect(() => check(false), [check]);

  const view = state.phase === "ready" ? readinessView(state.readiness) : null;
  return (
    <section className="settings-card native-readiness" data-testid="native-readiness">
      <div className="native-link-actions">
        <strong>Mimir setup</strong>
        <button type="button" className="btn btn-ghost" disabled={state.phase === "loading"} onClick={() => check(true)}>
          Check again
        </button>
      </div>
      {state.phase === "loading" && <p className="native-option-text">Checking this engine.</p>}
      {state.phase === "failed" && (
        <p className="native-banner" role="alert">
          {state.message}
        </p>
      )}
      {view !== null && (
        <>
          <p data-testid="native-readiness-title" data-ready={view.ready}>
            {view.title}
          </p>
          {view.detail !== null && <p className="native-option-text">{view.detail}</p>}
          {view.action !== null && (
            <>
              <p className="native-option-text">Run this on the engine's machine. Roboco will not run it for you.</p>
              <pre className="native-pre" data-testid="native-readiness-action">
                {view.action}
              </pre>
            </>
          )}
        </>
      )}
    </section>
  );
}
