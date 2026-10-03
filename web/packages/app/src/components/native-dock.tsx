import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  ConnectivityState,
  NativeChatCatalog,
  NativeChatState,
  NativeChild,
  NativeControl,
  NativeGoal,
  NativePlanDecision,
  NativeUserRequest,
} from "@roboco/proto";
import {
  EMPTY_QUESTION_DRAFT,
  PLAN_STATUS,
  RECOVERING_COPY,
  attentionDeliveries,
  buildAnswers,
  childView,
  configurationModelId,
  configureControl,
  engineOfflineCopy,
  goalIsOpen,
  goalView,
  hydrateChildren,
  inventoryKey,
  linkView,
  needsInventory,
  nativeWorking,
  questionAnswered,
  reasoningLevels,
  receiptHoldsControl,
  receiptView,
  releaseBlocker,
  type ControlReceipt,
  toggleNoneOfAbove,
  toggleOption,
  type GoalAction,
  type QuestionDraft,
} from "../lib/native";
import {
  getNativeCatalog,
  getNativePlan,
  listNativeChildren,
  sendNativeControl,
  type NativeCaller,
} from "../lib/native-actions";
import { NativeMarkdown } from "./native-markdown";

/**
 * The native conversation dock: everything that needs the user's attention or
 * shows host-confirmed state for a Mimir chat, between the status strip and the
 * composer. Every fact shown is read from `NativeChatState`. A control the
 * user sends shows the host ledger's own receipt: pending, then the typed
 * outcome, or "not confirmed" when the read ran out. Nothing says "applied"
 * before the host did.
 */

interface Feedback {
  readonly slice: string;
  readonly receipt: ControlReceipt;
}

/**
 * One sender per dock. A receipt is scoped to its chat and control key, and a
 * newer send on the same key replaces it. Receipts that need attention (refused,
 * unknown, unconfirmed) stay until the user acts again. The rest hold only while
 * the fact the control targets is unchanged.
 */
function useControls(client: NativeCaller, chatId: string) {
  const [feedback, setFeedback] = useState<Readonly<Record<string, Feedback>>>({});
  const watches = useRef(new Map<string, AbortController>());
  useEffect(() => {
    const open = watches.current;
    return () => {
      for (const watch of open.values()) {
        watch.abort();
      }
      open.clear();
      setFeedback({});
    };
  }, [chatId]);
  const send = useCallback(
    async (key: string, slice: unknown, control: NativeControl): Promise<void> => {
      const sliceKey = JSON.stringify(slice);
      watches.current.get(key)?.abort();
      const watch = new AbortController();
      watches.current.set(key, watch);
      await sendNativeControl(client, chatId, control, {
        signal: watch.signal,
        onReceipt: (receipt) => setFeedback((all) => ({ ...all, [key]: { slice: sliceKey, receipt } })),
      });
    },
    [client, chatId],
  );
  const read = useCallback(
    (key: string, slice: unknown): ControlReceipt | null => {
      const entry = feedback[key];
      if (entry === undefined) {
        return null;
      }
      return entry.slice === JSON.stringify(slice) || receiptView(entry.receipt).attention ? entry.receipt : null;
    },
    [feedback],
  );
  return { send, read };
}

function FeedbackLine({ receipt }: { receipt: ControlReceipt | null }) {
  if (receipt === null) {
    return null;
  }
  const view = receiptView(receipt);
  const failed = view.tone === "danger" || view.tone === "warn";
  return (
    <p
      className={view.tone === "danger" ? "native-feedback native-feedback-failed" : "native-feedback"}
      role={failed ? "alert" : "status"}
      data-receipt={receipt.kind === "settled" ? receipt.verdict.kind : receipt.kind}
    >
      {view.text}
    </p>
  );
}

export interface NativeDockProps {
  readonly client: NativeCaller;
  readonly chatId: string;
  readonly state: NativeChatState;
  readonly connectivity: ConnectivityState | null | undefined;
  readonly onOpenChild: (child: NativeChild) => void;
}

/**
 * Everything below is mounted per chat: a question draft, goal editor, plan
 * read or receipt started for one chat is gone when the dock shows another,
 * so nothing typed for one chat can be sent to the next.
 */
export function NativeDock(props: NativeDockProps) {
  return <NativeDockBody key={props.chatId} {...props} />;
}

function NativeDockBody({ client, chatId, state, connectivity, onOpenChild }: NativeDockProps) {
  const controls = useControls(client, chatId);
  const link = linkView(state.link);
  const offline = engineOfflineCopy(connectivity);
  const deliveries = attentionDeliveries(state);
  const hasGoal = goalIsOpen(state.goal);
  return (
    <section className="native-dock" aria-label="Mimir conversation" data-native-dock>
      {offline !== null && (
        <p className="native-banner" data-tone="warn" data-testid="native-engine-offline">
          {offline}
        </p>
      )}
      <LinkRow state={state} link={link} controls={controls} />
      {state.recovering && (
        <p className="native-banner" data-tone="warn" role="status" data-testid="native-recovering">
          {RECOVERING_COPY}
        </p>
      )}
      <ConfigBar client={client} chatId={chatId} state={state} controls={controls} />
      {state.plan !== null && <PlanCard key={state.plan.id} client={client} chatId={chatId} state={state} controls={controls} />}
      {state.userRequest !== null && <QuestionCard request={state.userRequest} controls={controls} />}
      <GoalStrip goal={state.goal} hasGoal={hasGoal} controls={controls} />
      {deliveries.length > 0 && (
        <ul className="native-deliveries" aria-label="Message delivery">
          {deliveries.map((delivery) => (
            <li key={delivery.messageId} data-tone={delivery.tone} data-testid="native-delivery">
              <span className="native-delivery-label">{delivery.label}</span>
              {delivery.detail !== null && <span className="native-delivery-detail">{delivery.detail}</span>}
              {delivery.retryable && (
                <button
                  type="button"
                  className="btn btn-ghost"
                  disabled={receiptHoldsControl(controls.read(`retry:${delivery.messageId}`, delivery))}
                  onClick={() =>
                    void controls.send(`retry:${delivery.messageId}`, delivery, {
                      control: "retrySubmission",
                      messageId: delivery.messageId,
                    })
                  }
                >
                  Retry
                </button>
              )}
              <FeedbackLine receipt={controls.read(`retry:${delivery.messageId}`, delivery)} />
            </li>
          ))}
        </ul>
      )}
      {state.children.length > 0 && <ChildList client={client} chatId={chatId} items={state.children} onOpen={onOpenChild} />}
    </section>
  );
}

type Controls = ReturnType<typeof useControls>;

function LinkRow({ state, link, controls }: { state: NativeChatState; link: ReturnType<typeof linkView>; controls: Controls }) {
  const blocker = releaseBlocker(state);
  const working = nativeWorking(state);
  const linkSlice = state.link;
  const showRow = link.canReconnect || link.showRelease || link.tone !== "neutral" || link.detail !== null;
  if (!showRow && !working) {
    return null;
  }
  return (
    <div className="native-link" data-tone={link.tone} data-testid="native-link">
      <div className="native-link-text">
        <span className="native-link-title">{link.title}</span>
        {link.detail !== null && <span className="native-link-detail">{link.detail}</span>}
        {working && <span className="native-link-work">Mimir is working</span>}
      </div>
      <div className="native-link-actions">
        {link.canReconnect && (
          <button
            type="button"
            className="btn btn-ghost"
            disabled={receiptHoldsControl(controls.read("reconnect", linkSlice))}
            onClick={() => void controls.send("reconnect", linkSlice, { control: "reconnect" })}
          >
            Reconnect
          </button>
        )}
        {link.showRelease && (
          <button
            type="button"
            className="btn btn-ghost"
            disabled={blocker !== null || receiptHoldsControl(controls.read("release", linkSlice))}
            title={blocker ?? "Hand this conversation to the Mimir terminal app"}
            onClick={() => void controls.send("release", linkSlice, { control: "release" })}
          >
            Continue in Mimir
          </button>
        )}
      </div>
      {link.showRelease && blocker !== null && <p className="native-hint">{blocker}</p>}
      <FeedbackLine receipt={controls.read("reconnect", linkSlice) ?? controls.read("release", linkSlice)} />
    </div>
  );
}

function ConfigBar({
  client,
  chatId,
  state,
  controls,
}: {
  client: NativeCaller;
  chatId: string;
  state: NativeChatState;
  controls: Controls;
}) {
  const configuration = state.configuration;
  const attached = state.link.state === "attached";
  // Listed again on every attach (a reconnect) and on Try again.
  const [attempt, setAttempt] = useState(0);
  const listing = useLoad(() => getNativeCatalog(client, chatId), attached ? `${chatId}#${attempt}` : null);
  const catalog: NativeChatCatalog | null = listing.phase === "ready" ? listing.value : null;
  const model = configuration === null ? null : configurationModelId(configuration);
  const levels = reasoningLevels(catalog, configuration);
  if (configuration === null) {
    return (
      <p className="native-config" data-testid="native-config">
        <span className="native-config-label">Model not connected yet</span>
      </p>
    );
  }
  return (
    <div className="native-config" data-testid="native-config">
      <div className="native-segment" role="group" aria-label="Mode">
        {(["build", "plan"] as const).map((mode) => (
          <button
            key={mode}
            type="button"
            className={configuration.mode === mode ? "btn btn-active" : "btn btn-ghost"}
            aria-pressed={configuration.mode === mode}
            disabled={!attached}
            onClick={() => {
              if (configuration.mode !== mode) {
                void controls.send("configure", configuration, configureControl(configuration, { mode }));
              }
            }}
          >
            {mode === "build" ? "Build" : "Plan"}
          </button>
        ))}
      </div>
      {catalog !== null && catalog.providers.length > 0 ? (
        <select
          className="input native-select"
          aria-label="Model"
          value={model ?? ""}
          disabled={!attached}
          onChange={(event) => {
            void controls.send("configure", configuration, configureControl(configuration, { model: event.target.value }));
          }}
        >
          {model === null && <option value="">No model</option>}
          {catalog.providers.map((provider) => (
            <optgroup key={provider.id} label={provider.name}>
              {provider.models.map((choice) => (
                <option key={choice.id} value={`${provider.id}/${choice.id}`}>
                  {choice.name}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      ) : (
        <span className="native-config-value">{model ?? "No model"}</span>
      )}
      {levels.length > 0 ? (
        <select
          className="input native-select"
          aria-label="Reasoning"
          value={configuration.reasoning ?? ""}
          disabled={!attached}
          onChange={(event) => {
            void controls.send("configure", configuration, configureControl(configuration, { reasoning: event.target.value }));
          }}
        >
          {configuration.reasoning === null && <option value="">Default</option>}
          {levels.map((level) => (
            <option key={level} value={level}>
              {level}
            </option>
          ))}
        </select>
      ) : (
        configuration.reasoning !== null && <span className="native-config-value">{configuration.reasoning}</span>
      )}
      {listing.phase === "loading" && <span className="native-hint">Asking Mimir for its models.</span>}
      {listing.phase === "failed" && (
        <p className="native-feedback native-feedback-failed" role="alert" data-testid="native-models-failed">
          Could not list Mimir's models. {listing.message}{" "}
          <button type="button" className="btn btn-ghost" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        </p>
      )}
      <FeedbackLine receipt={controls.read("configure", configuration)} />
    </div>
  );
}

const PLAN_DECISIONS: readonly { decision: NativePlanDecision; label: string; hint: string }[] = [
  { decision: "implement", label: "Implement", hint: "Build the plan in this conversation" },
  { decision: "implementFresh", label: "Implement with fresh context", hint: "Clear the model's context first, then build" },
  { decision: "saveAndStop", label: "Save and stop", hint: "Keep the plan and do not implement it yet" },
];

/**
 * The current plan: readable whatever its status, decided only while it awaits
 * review. Mounted per plan id, so a read started for one plan never shows
 * under another, and an artifact for a different id is refused.
 */
function PlanCard({
  client,
  chatId,
  state,
  controls,
}: {
  client: NativeCaller;
  chatId: string;
  state: NativeChatState;
  controls: Controls;
}) {
  const plan = state.plan!;
  const [open, setOpen] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const read = useLoad(() => getNativePlan(client, chatId), open ? `${plan.id}#${attempt}` : null);
  const review = plan.status === "reviewPending";
  const shown = read.phase === "ready" && read.value !== null && read.value.id === plan.id ? read.value : null;
  const missing = read.phase === "ready" && shown === null;
  return (
    <div className="native-card" data-testid="native-plan" data-plan-status={plan.status}>
      <div className="native-card-head">
        <span className="native-card-title">Plan: {plan.name}</span>
        <span className="native-card-status">{PLAN_STATUS[plan.status]}</span>
      </div>
      <button type="button" className="btn btn-ghost native-disclosure" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? "Hide plan" : "Read plan"}
      </button>
      {open && read.phase === "loading" && <p className="native-hint">Loading the plan.</p>}
      {open && (read.phase === "failed" || missing) && (
        <p className="native-feedback native-feedback-failed" role="alert" data-testid="native-plan-error">
          {read.phase === "failed" ? `Could not read the plan. ${read.message}` : "Mimir has no document for this plan."}{" "}
          <button type="button" className="btn btn-ghost" onClick={() => setAttempt((n) => n + 1)}>
            Try again
          </button>
        </p>
      )}
      {open && shown !== null && (
        <div className="native-plan-body">
          <NativeMarkdown text={shown.markdown} />
        </div>
      )}
      {review && (
        <div className="native-actions">
          {PLAN_DECISIONS.map(({ decision, label, hint }) => (
            <button
              key={decision}
              type="button"
              className={decision === "implement" ? "btn btn-solid" : "btn btn-ghost"}
              title={hint}
              disabled={receiptHoldsControl(controls.read("plan", plan))}
              onClick={() => void controls.send("plan", plan, { control: "decidePlan", planId: plan.id, decision })}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      <FeedbackLine receipt={controls.read("plan", plan)} />
    </div>
  );
}

function QuestionCard({ request, controls }: { request: NativeUserRequest; controls: Controls }) {
  const [drafts, setDrafts] = useState<Readonly<Record<string, QuestionDraft>>>({});
  useEffect(() => setDrafts({}), [request.id]);
  const answers = useMemo(() => buildAnswers(request.questions, drafts), [request.questions, drafts]);
  const key = `answer:${request.id}`;
  const feedback = controls.read(key, request.id);
  const update = (id: string, next: QuestionDraft) => setDrafts((all) => ({ ...all, [id]: next }));
  return (
    <form
      className="native-card"
      data-testid="native-question"
      onSubmit={(event) => {
        event.preventDefault();
        if (answers !== null) {
          void controls.send(key, request.id, { control: "answer", requestId: request.id, answers });
        }
      }}
    >
      {request.questions.map((question, index) => {
        const draft = drafts[question.id] ?? EMPTY_QUESTION_DRAFT;
        return (
          <fieldset key={question.id} className="native-question">
            <legend className="wizard-question">
              {request.questions.length > 1 ? `${index + 1}. ` : ""}
              {question.prompt}
            </legend>
            {question.allowMultiple && <p className="wizard-multiselect-hint">Choose any that apply</p>}
            <div className="wizard-options">
              {question.options.map((option) => {
                const picked = draft.selected.includes(option.label);
                return (
                  <button
                    key={option.label}
                    type="button"
                    className={picked ? "wizard-option wizard-option-picked" : "wizard-option"}
                    role={question.allowMultiple ? "checkbox" : "radio"}
                    aria-checked={picked}
                    onClick={() => update(question.id, toggleOption(question, draft, option.label))}
                  >
                    <span className="native-option-text">
                      <span className="wizard-option-label">{option.label}</span>
                      {option.description.length > 0 && <span className="native-option-description">{option.description}</span>}
                    </span>
                  </button>
                );
              })}
              <button
                type="button"
                className={draft.noneOfAbove ? "wizard-option wizard-option-picked" : "wizard-option"}
                role="checkbox"
                aria-checked={draft.noneOfAbove}
                onClick={() => update(question.id, toggleNoneOfAbove(draft))}
              >
                <span className="wizard-option-label">None of the above</span>
              </button>
            </div>
            <input
              className="input native-freeform"
              type="text"
              aria-label={`Your own answer to: ${question.prompt}`}
              placeholder="Or write your own answer"
              value={draft.freeform}
              onChange={(event) => update(question.id, { ...draft, freeform: event.target.value })}
            />
          </fieldset>
        );
      })}
      <div className="native-actions">
        <button
          type="submit"
          className="btn btn-solid"
          disabled={answers === null || receiptHoldsControl(feedback)}
        >
          Send answer
        </button>
        {answers === null && request.questions.some((question) => !questionAnswered(drafts[question.id] ?? EMPTY_QUESTION_DRAFT)) && (
          <span className="native-hint">Answer every question to continue.</span>
        )}
      </div>
      <FeedbackLine receipt={feedback} />
    </form>
  );
}

function minutesToSeconds(text: string): number | null | "invalid" {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return null;
  }
  const minutes = Number(trimmed);
  return Number.isFinite(minutes) && minutes > 0 ? Math.round(minutes * 60) : "invalid";
}

type GoalForm = "start" | "edit" | "resume" | null;

function GoalStrip({ goal, hasGoal, controls }: { goal: NativeGoal | null; hasGoal: boolean; controls: Controls }) {
  const [form, setForm] = useState<GoalForm>(null);
  const [objective, setObjective] = useState("");
  const [minutes, setMinutes] = useState("");
  const view = hasGoal && goal !== null ? goalView(goal) : null;
  const slice = goal;
  const duration = minutesToSeconds(minutes);

  const open = (next: Exclude<GoalForm, null>) => {
    setForm(next);
    setObjective(next === "edit" && goal !== null ? goal.objective : "");
    setMinutes("");
  };
  const act = (action: GoalAction) => {
    if (action === "edit" || action === "resume") {
      open(action);
    } else if (action === "pause") {
      void controls.send("goal", slice, { control: "changeGoal", change: { change: "pause" } });
    } else {
      void controls.send("goal", slice, { control: "changeGoal", change: { change: "clear" } });
    }
  };
  const submit = () => {
    if (duration === "invalid") {
      return;
    }
    if (form === "start" && objective.trim().length > 0) {
      void controls.send("goal", slice, {
        control: "changeGoal",
        change: { change: "start", objective: objective.trim(), durationSeconds: duration },
      });
    } else if (form === "edit" && objective.trim().length > 0) {
      void controls.send("goal", slice, { control: "changeGoal", change: { change: "edit", objective: objective.trim() } });
    } else if (form === "resume") {
      void controls.send("goal", slice, { control: "changeGoal", change: { change: "resume", durationSeconds: duration } });
    } else {
      return;
    }
    setForm(null);
  };

  return (
    <div className="native-goal" data-testid="native-goal" data-tone={view?.tone ?? "neutral"}>
      {view !== null && goal !== null ? (
        <>
          <div className="native-goal-head">
            <span className="native-goal-phase">{view.phaseLabel}</span>
            <span className="native-goal-objective">{goal.objective}</span>
          </div>
          <p className="native-goal-meta">
            {view.causeLabel}. {view.workTurns} work {view.workTurns === 1 ? "turn" : "turns"}.
          </p>
          {view.reason !== null && <p className="native-goal-reason">Reason: {view.reason}</p>}
          {view.reviewGap !== null && <p className="native-goal-reason">Review gap: {view.reviewGap}</p>}
          {view.completion !== null && (
            <div className="native-goal-completion" data-testid="native-goal-completion">
              <p className="native-goal-meta">Accepted by review</p>
              <ul>
                {view.completion.checklist.map((item) => (
                  <li key={item.requirement}>
                    <strong>{item.requirement}</strong>
                    <span className="native-goal-evidence">{item.evidence}</span>
                  </li>
                ))}
              </ul>
              {view.completion.limitations !== null && <p className="native-goal-reason">Limitations: {view.completion.limitations}</p>}
            </div>
          )}
          <div className="native-actions">
            {view.actions.map((action) => (
              <button key={action} type="button" className="btn btn-ghost" onClick={() => act(action)}>
                {action === "edit" ? "Edit" : action === "pause" ? "Pause" : action === "resume" ? "Resume" : "Clear"}
              </button>
            ))}
          </div>
        </>
      ) : (
        form === null && (
          <button type="button" className="btn btn-ghost native-goal-start" onClick={() => open("start")}>
            Start goal
          </button>
        )
      )}
      {form !== null && (
        <form
          className="native-goal-form"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          {form !== "resume" && (
            <input
              className="input"
              type="text"
              autoFocus
              aria-label="Goal objective"
              placeholder="What should Mimir work toward?"
              value={objective}
              onChange={(event) => setObjective(event.target.value)}
            />
          )}
          {form !== "edit" && (
            <input
              className="input native-minutes"
              type="text"
              inputMode="decimal"
              aria-label="Time limit in minutes"
              placeholder="Minutes (optional)"
              aria-invalid={duration === "invalid"}
              value={minutes}
              onChange={(event) => setMinutes(event.target.value)}
            />
          )}
          <button type="submit" className="btn btn-solid" disabled={duration === "invalid" || (form !== "resume" && objective.trim().length === 0)}>
            {form === "start" ? "Start" : form === "edit" ? "Save" : "Resume"}
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => setForm(null)}>
            Cancel
          </button>
        </form>
      )}
      <FeedbackLine receipt={controls.read("goal", slice)} />
    </div>
  );
}

/**
 * The chat's delegated agents. A header-only child (the view had no room for
 * its details) is replaced by its entry in this chat's canonical inventory.
 */
function ChildList({
  client,
  chatId,
  items,
  onOpen,
}: {
  client: NativeCaller;
  chatId: string;
  items: readonly NativeChild[];
  onOpen: (child: NativeChild) => void;
}) {
  const inventory = useChildInventory(client, chatId, items);
  const children = hydrateChildren(items, inventory.children);
  return (
    <>
    <InventoryNotice inventory={inventory} children={children} />
    <ul className="native-children" aria-label="Delegated agents" data-testid="native-children">
      {children.map((child) => {
        const view = childView(child);
        return (
          <li key={child.docId} data-running={view.running}>
            <span className="native-child-title">{view.title.length > 0 ? view.title : child.handle}</span>
            <span className="native-child-meta">
              {view.statusLabel}
              {view.model !== null ? ` · ${view.model}` : ""}
              {` · attempt ${view.attempt}`}
            </span>
            <button type="button" className="btn btn-ghost" onClick={() => onOpen(child)}>
              Open
            </button>
          </li>
        );
      })}
    </ul>
    </>
  );
}

type Load<T> =
  | { readonly phase: "idle" }
  | { readonly phase: "loading" }
  | { readonly phase: "failed"; readonly message: string }
  | { readonly phase: "ready"; readonly value: T };

/** One read per key: a new key reads again, a null key reads nothing, and a stale answer is dropped. */
function useLoad<T>(load: () => Promise<T>, key: string | null): Load<T> {
  const [state, setState] = useState<Load<T>>({ phase: "idle" });
  useEffect(() => {
    if (key === null) {
      setState({ phase: "idle" });
      return;
    }
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

export interface ChildInventory {
  readonly load: Load<NativeChild[]>;
  readonly children: readonly NativeChild[];
  readonly retry: () => void;
}

/** The owning chat's canonical inventory, read only while a projected child is header-only. */
export function useChildInventory(client: NativeCaller, chatId: string, projected: readonly NativeChild[]): ChildInventory {
  const [attempt, setAttempt] = useState(0);
  const key = needsInventory(projected) ? `${chatId}|${inventoryKey(projected)}|${attempt}` : null;
  const load = useLoad(() => listNativeChildren(client, chatId), key);
  return {
    load,
    children: load.phase === "ready" ? load.value : [],
    retry: () => setAttempt((n) => n + 1),
  };
}

export function InventoryNotice({ inventory, children }: { inventory: ChildInventory; children: readonly NativeChild[] }) {
  if (!needsInventory(children)) {
    return null;
  }
  if (inventory.load.phase === "failed") {
    return (
      <p className="native-feedback native-feedback-failed" role="alert" data-testid="native-children-error">
        Mimir sent only some agents' headers, and its full list could not be read. {inventory.load.message}{" "}
        <button type="button" className="btn btn-ghost" onClick={inventory.retry}>
          Try again
        </button>
      </p>
    );
  }
  return <p className="native-hint">Reading the full details of large agents from Mimir.</p>;
}
