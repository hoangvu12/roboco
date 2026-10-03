import { useEffect, useRef, useState } from "react";
import type { NativeChild, NativeChildOutcome, NativeControl, NativeUsage } from "@roboco/proto";
import {
  childControlLabel,
  childSettled,
  childStatusLabel,
  childView,
  receiptInFlight,
  usageLine,
  type ChildControlSent,
} from "../lib/native";
import { getNativeChildOutcome, readChildOutcomeBlob, sendNativeControl, type NativeCaller } from "../lib/native-actions";
import { NativeMarkdown } from "./native-markdown";

type Loaded<T> =
  | { readonly phase: "loading" }
  | { readonly phase: "failed"; readonly message: string }
  | { readonly phase: "ready"; readonly value: T };

function useLoaded<T>(load: () => Promise<T>, key: string): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ phase: "loading" });
  useEffect(() => {
    let live = true;
    setState({ phase: "loading" });
    load().then(
      (value) => live && setState({ phase: "ready", value }),
      (error: unknown) => live && setState({ phase: "failed", message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      live = false;
    };
  }, [key]);
  return state;
}

function OutcomeBody({ outcome, expectedAttempt }: { readonly outcome: NativeChildOutcome | null; readonly expectedAttempt: number }) {
  if (outcome === null) {
    return <p className="native-option-text">The host has no outcome for attempt {expectedAttempt} yet.</p>;
  }
  if (outcome.attempt !== expectedAttempt) {
    return (
      <p className="native-option-text" role="alert">
        The host returned the outcome of attempt {outcome.attempt}, not attempt {expectedAttempt}. It is not shown here.
      </p>
    );
  }
  return (
    <div className="native-outcome" data-testid="native-child-outcome">
      <p className="native-option-text">{childStatusLabel(outcome.status)}</p>
      {outcome.error !== null && (
        <p className="native-notice-body" role="alert">
          {outcome.error}
        </p>
      )}
      {outcome.result !== null && <NativeMarkdown text={outcome.result} />}
      {outcome.changedFiles.length > 0 && (
        <ul data-testid="native-child-files">
          {outcome.changedFiles.map((file) => (
            <li key={file}>{file}</li>
          ))}
        </ul>
      )}
      <UsageLine usage={outcome.usage} />
    </div>
  );
}

function UsageLine({ usage }: { readonly usage: NativeUsage }) {
  return <p className="native-option-text">{usageLine(usage)}</p>;
}

function LoadedOutcome({ state, attempt }: { readonly state: Loaded<NativeChildOutcome | null>; readonly attempt: number }) {
  switch (state.phase) {
    case "loading":
      return <p className="native-option-text">Loading the outcome.</p>;
    case "failed":
      return (
        <p className="native-banner" role="alert">
          {state.message}
        </p>
      );
    case "ready":
      return <OutcomeBody outcome={state.value} expectedAttempt={attempt} />;
  }
}

function CurrentOutcome({ client, chatId, child }: { client: NativeCaller; chatId: string; child: NativeChild }) {
  const state = useLoaded(
    () => getNativeChildOutcome(client, chatId, child.handle, child.attempt),
    `${child.handle}#${child.attempt}#${child.status}`,
  );
  return <LoadedOutcome state={state} attempt={child.attempt} />;
}

function HistoricalAttempt({
  client,
  attempt,
  status,
  outcomeRef,
}: {
  client: NativeCaller;
  attempt: number;
  status: NativeChild["status"];
  outcomeRef: string | null;
}) {
  const [open, setOpen] = useState(false);
  return (
    <li data-testid="native-child-attempt">
      <button type="button" className="btn btn-ghost" aria-expanded={open} onClick={() => setOpen(!open)}>
        Attempt {attempt} · {childStatusLabel(status)}
      </button>
      {open &&
        (outcomeRef === null ? (
          <p className="native-option-text">The host kept no outcome record for this attempt.</p>
        ) : (
          <HistoricalOutcome client={client} attempt={attempt} outcomeRef={outcomeRef} />
        ))}
    </li>
  );
}

function HistoricalOutcome({ client, attempt, outcomeRef }: { client: NativeCaller; attempt: number; outcomeRef: string }) {
  const state = useLoaded<NativeChildOutcome | null>(() => readChildOutcomeBlob(client, outcomeRef), outcomeRef);
  return <LoadedOutcome state={state} attempt={attempt} />;
}

/**
 * The header of a native child's right-pane tab. Identity, status and attempt
 * come from the parent chat's host-confirmed inventory; steer and Stop name
 * that exact attempt; outcomes are read from the host, never inferred from
 * the transcript.
 */
export function NativeChildPanel({
  client,
  chatId,
  child,
}: {
  readonly client: NativeCaller;
  readonly chatId: string;
  readonly child: NativeChild;
}) {
  const view = childView(child);
  const [text, setText] = useState("");
  const [sent, setSent] = useState<ChildControlSent | null>(null);
  const watch = useRef<AbortController | null>(null);
  const settled = childSettled(child.status);
  const label = childControlLabel(sent, child);
  const holding = receiptInFlight(sent?.receipt ?? null);

  useEffect(() => {
    setSent(null);
    return () => watch.current?.abort();
  }, [chatId, child.handle]);

  const send = async (action: ChildControlSent["action"], control: Extract<NativeControl, { control: "steerChild" | "stopChild" }>): Promise<void> => {
    const attempt = control.attempt;
    watch.current?.abort();
    const controller = new AbortController();
    watch.current = controller;
    const receipt = await sendNativeControl(client, chatId, control, {
      signal: controller.signal,
      onReceipt: (next) => setSent({ action, attempt, receipt: next }),
    });
    if (action === "guidance" && receipt.kind === "settled" && receipt.verdict.kind === "childAccepted") {
      setText("");
    }
  };

  const history = child.attempts.filter((attempt) => attempt.attempt !== child.attempt);
  return (
    <div className="native-child-head" data-testid="native-child-head">
      <div className="native-child-head-row">
        <strong>{view.title}</strong>
        <span data-testid="native-child-status">{view.statusLabel}</span>
        {view.model !== null && <span>{view.model}</span>}
        <span>Attempt {child.attempt}</span>
        <span className="native-option-text">{child.handle}</span>
      </div>
      {!settled && (
        <div className="native-child-guidance" data-testid="native-child-controls">
          <input
            className="input"
            value={text}
            placeholder={`Guidance for attempt ${child.attempt}`}
            aria-label="Guidance for this agent"
            onChange={(event) => setText(event.target.value)}
          />
          <button
            type="button"
            className="btn btn-solid"
            disabled={text.trim().length === 0 || holding}
            onClick={() =>
              void send("guidance", { control: "steerChild", handle: child.handle, attempt: child.attempt, text: text.trim() })
            }
          >
            Send guidance
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={holding}
            onClick={() => void send("stop", { control: "stopChild", handle: child.handle, attempt: child.attempt })}
          >
            Stop
          </button>
        </div>
      )}
      {label !== null && (
        <p
          className={label.tone === "danger" ? "native-feedback native-feedback-failed" : "native-feedback"}
          role={label.tone === "danger" || label.tone === "warn" ? "alert" : "status"}
          data-testid="native-child-feedback"
        >
          {label.text}
        </p>
      )}
      {sent !== null && label === null && settled && sent.attempt === child.attempt && (
        <p className="native-feedback">This attempt has ended. The outcome is below.</p>
      )}
      {child.completionPending && <p className="native-option-text">The parent has not yet received this result.</p>}
      {settled && <CurrentOutcome client={client} chatId={chatId} child={child} />}
      {history.length > 0 && (
        <ul className="native-child-history" aria-label="Earlier attempts">
          {history.map((attempt) => (
            <HistoricalAttempt
              key={attempt.attempt}
              client={client}
              attempt={attempt.attempt}
              status={attempt.status}
              outcomeRef={attempt.outcomeRef}
            />
          ))}
        </ul>
      )}
    </div>
  );
}
