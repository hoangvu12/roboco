import { useCallback, useEffect, useRef, useState } from "react";
import type { EngineClient } from "@roboco/engine-client";
import type {
  AgentAccount,
  HarnessDescriptor,
  HarnessId,
  Model,
  ResumeAccountCandidates,
  ScheduledResume,
  SessionLimit,
} from "@roboco/proto";
import { accountLabel } from "../lib/accounts";
import {
  cancelScheduledResume,
  candidateResetCaption,
  continueOfferedHarnesses,
  failoverCardTitle,
  failoverListOffers,
  failoverOutcomeNotice,
  failoverRows,
  harnessOffersFailover,
  limitedBannerLabel,
  listResumeAccountCandidates,
  resumeAtResetArmable,
  resumeOnAnotherAccount,
  scheduleResumeAtReset,
  FAILOVER_LIST_TTL_MS,
} from "../lib/limited";
import {
  dialogError,
  dialogLoading,
  dialogRows,
  loadDialogHarnesses,
  loadDialogModels,
  type DialogLoad,
} from "../lib/harness-choice";
import { switchHarness } from "../lib/harness-switch";
import type { DraftConfig } from "../lib/composer-actions";
import { AccountUsageMeter } from "./account-usage";
import { HarnessChip, harnessChipsEmptyText, ModelList } from "./ui/HarnessChoice";
import { PickerCard } from "./ui/PickerCard";
import { MenuHeading, MenuRowNav } from "./ui/MenuRows";
import { SignInAgainCard, useSignInAgainFlow } from "./sign-in-again";

/**
 * The Limited banner — the web peer of the desktop's status-strip Limited
 * arm (shell.rs `render_status_strip`'s `Indicator::Limited` branch). A
 * run ended on a harness usage limit: the banner names the window and its
 * reset (from the harness's own error, else the account's cached windows),
 * and offers the fixed actions:
 *
 * - **Resume** — the continue prompt as a normal run (the composer's send
 *   path; queued messages stay behind it);
 * - **Resume at reset** (ticket 07) — arms the engine's durable
 *   continuation; once armed the label shows when it fires and the chip
 *   becomes Cancel;
 * - **Resume on another account** (ticket 09) — the failover picker: the
 *   engine-ranked candidates with their usage meters and reset times, the
 *   recommended one first, and the signed-out accounts with a "Sign in
 *   again" action instead of a switch;
 * - **Continue on another harness** (t3-orchestration-v2 tickets 25/26) —
 *   the escape hatch spec story 55 asks for: pick any OTHER enabled
 *   harness and a model; confirming performs ticket 24's one-way switch
 *   and then sends the same continue prompt, so the new agent picks the
 *   work up from the budgeted history above the "Switched to" seam.
 */
export function LimitedBanner({
  chatId,
  client,
  limit,
  scheduled,
  now,
  onResume,
  currentHarness,
  targetDeviceId,
}: {
  /** The chat's id as the page knows it (scoped; routing decodes either form). */
  readonly chatId: string;
  /** The chat's owning engine client — null disables every action. */
  readonly client: EngineClient | null;
  readonly limit: SessionLimit | null;
  readonly scheduled: ScheduledResume | null;
  readonly now: number;
  /**
   * The composer's fixed-prompt send ("Continue where you left off.") —
   * called plainly for Resume, or with the switch's landed picks for
   * "Continue on another harness" (ticket 25: the continuation carries the
   * NEW harness deterministically, the desktop's row stamp).
   */
  readonly onResume: (switched?: Partial<DraftConfig>) => void;
  /** The harness the chat runs today (its row config) — never offered by the
   *  "Continue on another harness" picker (ticket 25). */
  readonly currentHarness: HarnessId | null;
  /** The picker's catalog routing: the chat's host device when it differs
   *  from the connected engine's own (scoped), else null. */
  readonly targetDeviceId: string | null;
}) {
  const label = limitedBannerLabel(limit, scheduled, now);
  const armable = resumeAtResetArmable(limit, scheduled, now);

  // ── The failover picker state (shell.rs `FailoverState`) ─────────────
  const [open, setOpen] = useState(false);
  const [list, setList] = useState<ResumeAccountCandidates | null>(null);
  const [listFor, setListFor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [switching, setSwitching] = useState(false);
  /** The last switch outcome — a warning-toned strip line that lives until
   *  the next switch or the chat's own state supersedes it. */
  const [notice, setNotice] = useState<string | null>(null);
  // The freshness bookkeeping rides refs: `track` stays identity-stable so
  // the warm-load effect (keyed on the page's `now` heartbeat) never
  // re-binds, and the in-flight guard survives the renders between the
  // call and its reply.
  const loadedRef = useRef<{ chatId: string; at: number } | null>(null);
  const loadingForRef = useRef<string | null>(null);

  const offersFailover = limit !== null && harnessOffersFailover(limit.harness);
  const track = useCallback(
    (force: boolean): void => {
      if (client === null) {
        return;
      }
      const loaded = loadedRef.current;
      if (
        !force &&
        ((loaded !== null && loaded.chatId === chatId && Date.now() - loaded.at < FAILOVER_LIST_TTL_MS) ||
          loadingForRef.current === chatId)
      ) {
        return;
      }
      loadingForRef.current = chatId;
      setLoading(true);
      void listResumeAccountCandidates(client, chatId)
        .then((fresh) => {
          loadedRef.current = { chatId, at: Date.now() };
          setList(fresh);
          setListFor(chatId);
        })
        .catch(() => {
          // A load failure clears the cache so the next pass retries — the
          // banner keeps offering plain Resume either way.
          loadedRef.current = null;
          setList(null);
          setListFor(null);
        })
        .finally(() => {
          if (loadingForRef.current === chatId) {
            loadingForRef.current = null;
          }
          setLoading(false);
        });
    },
    [chatId, client],
  );

  // Keep the banner's candidate list warm while the chat reads as Limited
  // on a switchable harness (desktop's `track_failover` from the strip's
  // render — the TTL inside makes the heartbeat's repeated calls cheap).
  useEffect(() => {
    if (offersFailover) {
      track(false);
    }
  }, [chatId, offersFailover, now, track]);

  // Opening the picker re-ranks: usage meters move as turns run.
  useEffect(() => {
    if (open) {
      track(true);
    }
  }, [open, track]);

  // A chat change drops the stale list and the outcome line.
  useEffect(() => {
    if (listFor !== null && listFor !== chatId) {
      setList(null);
      setListFor(null);
      setNotice(null);
    }
  }, [chatId, listFor]);

  const failoverList = list !== null && listFor === chatId ? list : null;
  const rows = failoverList !== null ? failoverRows(failoverList) : [];
  const showFailover = offersFailover && failoverList !== null && failoverListOffers(failoverList);

  /** Run the failover for `accountId` — the reply becomes the strip's
   *  outcome line; a failed switch surfaces the reason right there. */
  const switchTo = useCallback(
    (accountId: string): void => {
      if (client === null || switching) {
        return;
      }
      setSwitching(true);
      setNotice(null);
      void resumeOnAnotherAccount(client, chatId, accountId)
        .then((outcome) => {
          setNotice(failoverOutcomeNotice(outcome));
        })
        .catch((cause: unknown) => {
          setNotice(`Could not switch account: ${cause instanceof Error ? cause.message : String(cause)}`);
        })
        .finally(() => {
          setSwitching(false);
          setOpen(false);
        });
    },
    [chatId, client, switching],
  );

  // "Sign in again" on a signed-out row (stories 47/50): the engine's own
  // login flow, driven inline below the rows where the user already looks.
  const signIn = useSignInAgainFlow({
    client,
    onLanded: () => {
      void track(true);
    },
  });

  const chipsDisabled = client === null;

  return (
    <div className="limited-banner">
      <span className="limited-banner-dot" aria-hidden />
      <span className="limited-banner-label">{label}</span>
      {armable && (
        <button
          type="button"
          className="limited-banner-chip"
          title="Continue automatically when the limit resets"
          disabled={chipsDisabled}
          onClick={() => {
            if (client !== null) {
              void scheduleResumeAtReset(client, chatId);
            }
          }}
        >
          Resume at reset
        </button>
      )}
      {scheduled !== null && (
        <button
          type="button"
          className="limited-banner-chip"
          title="Stop the automatic continuation"
          disabled={chipsDisabled}
          onClick={() => {
            if (client !== null) {
              void cancelScheduledResume(client, chatId);
            }
          }}
        >
          Cancel
        </button>
      )}
      <button
        type="button"
        className="limited-banner-chip"
        title="Send the continue prompt now"
        disabled={chipsDisabled}
        onClick={() => onResume()}
      >
        Resume
      </button>
      {showFailover && failoverList !== null && (
        <PickerCard
          open={open}
          onOpenChange={setOpen}
          placement="anchorAboveEnd"
          role="dialog"
          ariaLabel={failoverCardTitle(failoverList.harness)}
          width={400}
          overlaySource="composer-pickers"
          trigger={
            <button
              type="button"
              className="limited-banner-chip"
              title="Continue on another account of this harness"
            >
              Resume on another account
            </button>
          }
        >
          <FailoverCard
            list={failoverList}
            rows={rows}
            loading={loading}
            switching={switching}
            now={now}
            onSwitch={switchTo}
            onSignIn={(account) => signIn.begin(account)}
          />
          {signIn.flow !== null && (
            <SignInAgainCard flow={signIn.flow} onCancel={signIn.cancel} onSubmitCode={signIn.submitCode} />
          )}
        </PickerCard>
      )}
      {/*
        CONTINUE ON ANOTHER HARNESS (orchestration v2 tickets 25/26): a
        separate action — the escape hatch spec story 55 asks for. A small
        picker card lists the enabled harnesses OTHER than the current one
        (plus a model pick); confirming performs ticket 24's one-way switch
        and then sends the same continue prompt, so the new agent picks the
        work up from the budgeted history above the "Switched to" seam.
      */}
      <ContinueOnHarnessPicker
        chatId={chatId}
        client={client}
        currentHarness={currentHarness}
        targetDeviceId={targetDeviceId}
        disabled={chipsDisabled}
        onNotice={setNotice}
        onResume={onResume}
      />
      {notice !== null && <span className="limited-banner-notice">{notice}</span>}
    </div>
  );
}

/**
 * The "Continue on another harness" picker (t3-orchestration-v2 tickets
 * 25/26) — the web peer of the desktop's `ContinueOnHarnessDialog`
 * (`shell/continue_on_harness.rs`), held in the banner's own anchored-card
 * idiom (the failover picker's shape): the harness chips (never the
 * current one), the selected harness's models, and the Continue action.
 *
 * Confirming performs ticket 24's one-way switch FIRST — its seam is what
 * arms the budgeted history and drops the limited harness's session — so
 * the continue prompt is sent only after the RPC succeeds
 * (`onResume`, the composer's fixed-prompt path). A failed switch leaves
 * the chat untouched: still Limited, still on its old harness, the banner
 * still up — the outcome line says why. A selection that moved while the
 * switch was in flight never fires the continuation: the banner re-keys
 * to the new chat (the props change, not a remount), so the guard compares
 * the live chat id against the one the switch started for — the switched
 * chat keeps its Limited banner and its own Resume, never sending another
 * chat's continuation into it (the desktop's `still_selected` rule).
 */
function ContinueOnHarnessPicker({
  chatId,
  client,
  currentHarness,
  targetDeviceId,
  disabled,
  onNotice,
  onResume,
}: {
  readonly chatId: string;
  readonly client: EngineClient | null;
  readonly currentHarness: HarnessId | null;
  readonly targetDeviceId: string | null;
  readonly disabled: boolean;
  readonly onNotice: (message: string | null) => void;
  readonly onResume: (switched?: Partial<DraftConfig>) => void;
}) {
  const [open, setOpen] = useState(false);
  const [harnesses, setHarnesses] = useState<DialogLoad<HarnessDescriptor>>("idle");
  const [models, setModels] = useState<ReadonlyMap<HarnessId, DialogLoad<Model>>>(new Map());
  const [harness, setHarness] = useState<HarnessId | null>(null);
  const [model, setModel] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);
  // The freshness guards: the banner re-keys to a new chat through props
  // (no remount), so the live chat id retires in-flight loads and the
  // confirm's continuation alike; the load epoch retires loads from a
  // closed card.
  const chatIdRef = useRef(chatId);
  chatIdRef.current = chatId;
  const loadEpochRef = useRef(0);

  // Opening loads the catalog once per open (the desktop's dialog-local
  // `ensure_continue_loads`); reopening retries. A chat change drops the
  // picks (the current harness to exclude moved with it).
  useEffect(() => {
    if (!open) {
      return;
    }
    loadEpochRef.current += 1;
    const epoch = loadEpochRef.current;
    setHarnesses("loading");
    setModels(new Map());
    setHarness(null);
    setModel(null);
    if (client === null) {
      setHarnesses({ error: "Engine not connected" });
      return;
    }
    void loadDialogHarnesses(client, targetDeviceId)
      .then((rows) => {
        if (loadEpochRef.current !== epoch || chatIdRef.current !== chatId) {
          return;
        }
        setHarnesses({ rows });
        // No pick yet: the first OFFERED harness (the current one is
        // already excluded from the offered list) becomes the pick once
        // the catalog lands — one click confirms (the desktop's default
        // resolution).
        const first = continueOfferedHarnesses(rows, currentHarness)[0]?.id ?? null;
        if (first !== null) {
          setHarness(first);
        }
      })
      .catch((cause: unknown) => {
        if (loadEpochRef.current !== epoch || chatIdRef.current !== chatId) {
          return;
        }
        setHarnesses({ error: cause instanceof Error ? cause.message : String(cause) });
      });
  }, [open, client, targetDeviceId, chatId, currentHarness]);

  // The picked harness's model list, loaded once per open per harness.
  useEffect(() => {
    if (!open || client === null || harness === null) {
      return;
    }
    if (models.get(harness) !== undefined && models.get(harness) !== "idle") {
      return;
    }
    const epoch = loadEpochRef.current;
    setModels((current) => new Map(current).set(harness, "loading"));
    void loadDialogModels(client, harness, targetDeviceId)
      .then((rows) => {
        if (loadEpochRef.current !== epoch || chatIdRef.current !== chatId) {
          return;
        }
        setModels((current) => new Map(current).set(harness, { rows }));
      })
      .catch((cause: unknown) => {
        if (loadEpochRef.current !== epoch || chatIdRef.current !== chatId) {
          return;
        }
        setModels((current) =>
          new Map(current).set(harness, { error: cause instanceof Error ? cause.message : String(cause) }),
        );
      });
  }, [open, client, harness, models, targetDeviceId, chatId]);

  /** Pick the continuation's harness — a pick resets the model (the
   *  previous pick's model id is meaningless there; its own default
   *  applies at run time). */
  const pickHarness = (next: HarnessId): void => {
    if (harness === next) {
      return;
    }
    setHarness(next);
    setModel(null);
  };

  /** Confirm: ticket 24's one-way switch, then the continue prompt — only
   *  once the engine accepts the switch (the desktop's ordering). The
   *  continuation carries the switch's landed picks explicitly: the
   *  chats-watch frame that would re-seed the composer's draft can trail
   *  the SwitchHarness reply, so the picks ride the resume handle (the
   *  desktop's `apply_chat_config` stamp). */
  const confirm = (): void => {
    if (client === null || harness === null || switching) {
      return;
    }
    const startedChatId = chatId;
    const pickedModel = model;
    setSwitching(true);
    onNotice(null);
    void switchHarness(client, chatId, harness, { model })
      .then(() => {
        setSwitching(false);
        setOpen(false);
        // The selection must still be this chat: a moved selection never
        // fires the continuation into whatever chat now owns the composer.
        if (chatIdRef.current === startedChatId) {
          onResume({
            harness,
            model: pickedModel,
            // The switch reset these to the new harness's defaults — the
            // engine stored exactly this shape.
            reasoning: null,
            modelOptions: {},
          });
        }
      })
      .catch((cause: unknown) => {
        setSwitching(false);
        // The switch never landed: the chat keeps its old harness and its
        // Limited state — the continuation is never sent into the same wall.
        onNotice(
          `Could not switch the harness: ${cause instanceof Error ? cause.message : String(cause)}`,
        );
      });
  };

  const offered = continueOfferedHarnesses(dialogRows(harnesses), currentHarness);
  const modelLoad = harness === null ? "idle" : models.get(harness) ?? "idle";

  return (
    <PickerCard
      open={open}
      onOpenChange={setOpen}
      placement="anchorAboveEnd"
      role="dialog"
      ariaLabel="Continue on another harness"
      width={400}
      overlaySource="composer-pickers"
      trigger={
        <button
          type="button"
          className="limited-banner-chip"
          title="Switch this chat to another harness and continue there"
          disabled={disabled}
        >
          Continue on another harness
        </button>
      }
    >
      <div className="continue-card">
        <MenuHeading>Continue on another harness</MenuHeading>
        <div className="continue-card-body">
          This chat hit its usage limit. Switching harnesses is one-way: the chat runs on the one
          you pick from here on, and the new agent receives a size-limited transcript of the
          conversation, then the continue prompt.
        </div>
        <div className="harness-choice-heading">Harness</div>
        <div className="harness-choice-chips">
          {offered.map((descriptor) => (
            <HarnessChip
              key={descriptor.id}
              descriptor={descriptor}
              selected={harness === descriptor.id}
              onPick={() => pickHarness(descriptor.id)}
            />
          ))}
          {offered.length === 0 && (
            <span className="harness-choice-empty">
              {harnessChipsEmptyText(harnesses, "No other harnesses are available on this device.")}
            </span>
          )}
        </div>
        <div className="harness-choice-heading">Model</div>
        <ModelList
          models={dialogRows(modelLoad)}
          error={dialogError(modelLoad)}
          loading={dialogLoading(modelLoad)}
          selectedModel={model}
          onPick={setModel}
        />
        <div className="continue-card-actions">
          <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-solid"
            disabled={switching || harness === null}
            onClick={confirm}
          >
            {switching ? "Switching…" : "Continue"}
          </button>
        </div>
      </div>
    </PickerCard>
  );
}

/**
 * The failover picker body (shell.rs `failover_card`): the ranked candidate
 * rows (usage meters with their reset times, the recommended one first),
 * the signed-out accounts with a "Sign in again" action, and the all-chats
 * warning the spec asks the switch to state (story 53).
 */
function FailoverCard({
  list,
  rows,
  loading,
  switching,
  now,
  onSwitch,
  onSignIn,
}: {
  readonly list: ResumeAccountCandidates;
  readonly rows: ReturnType<typeof failoverRows>;
  readonly loading: boolean;
  readonly switching: boolean;
  readonly now: number;
  readonly onSwitch: (accountId: string) => void;
  readonly onSignIn: (account: AgentAccount) => void;
}) {
  return (
    <div className="failover-card">
      <MenuHeading>{failoverCardTitle(list.harness)}</MenuHeading>
      <div className="picker-list picker-list-plain" data-switching={switching ? "1" : undefined}>
        {rows.map((row, ix) => {
          const signedOut = row.kind === "signedOut";
          const account = row.account;
          const caption = candidateResetCaption(account, now);
          const body = (
            <span className="failover-row-main">
              <span className="failover-row-label">{accountLabel(account)}</span>
              <span className="failover-row-meta">
                {account.planLabel != null && <span className="failover-row-plan">{account.planLabel}</span>}
                {row.kind === "candidate" && row.recommended && (
                  <span className="failover-row-recommended">Recommended</span>
                )}
                {signedOut && <span className="failover-row-signed-out">Signed out</span>}
              </span>
              {account.usageWindows.length > 0 && (
                <span className="account-usage-meters">
                  {account.usageWindows.slice(0, 2).map((window, index) => (
                    <AccountUsageMeter key={index} label={window.label} usedFraction={window.usedFraction} />
                  ))}
                </span>
              )}
              {caption !== null && <span className="failover-row-reset">{caption}</span>}
              {/* The signed-out row's own action (story 47): the same login
                  flow the account ring drives. The row is a div for these —
                  a nested button can never live inside the row button. */}
              {signedOut && (
                <button
                  type="button"
                  className="failover-row-sign-in"
                  onClick={() => {
                    onSignIn(account);
                  }}
                >
                  Sign in again
                </button>
              )}
            </span>
          );
          if (signedOut) {
            // A signed-out row is listed, never selected: a static row
            // body with its own sign-in action (no row click at all).
            return (
              <div key={account.id} className="menu-row menu-row-static" data-rb-row-key={`failover-row-${ix}`}>
                {body}
              </div>
            );
          }
          return (
            <MenuRowNav
              key={account.id}
              fadeKey={`failover-row-${ix}`}
              aria-disabled={switching || undefined}
              onClick={() => {
                if (!switching) {
                  onSwitch(account.id);
                }
              }}
            >
              {body}
            </MenuRowNav>
          );
        })}
      </div>
      {loading && <div className="picker-empty-note">Checking accounts…</div>}
      {/* The all-chats warning (spec story 53): switching is per HARNESS,
          not per chat. */}
      <div className="failover-warning">
        Switching applies to every chat on this harness, not just this one.
      </div>
    </div>
  );
}
