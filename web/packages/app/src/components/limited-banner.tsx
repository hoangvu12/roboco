import { useCallback, useEffect, useRef, useState } from "react";
import type { EngineClient } from "@roboco/engine-client";
import type { AgentAccount, ResumeAccountCandidates, ScheduledResume, SessionLimit } from "@roboco/proto";
import { accountLabel } from "../lib/accounts";
import {
  cancelScheduledResume,
  candidateResetCaption,
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
import { AccountUsageMeter } from "./account-usage";
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
 *   again" action instead of a switch.
 *
 * "Continue on another harness" (ticket 25) is ticket 26's web work — the
 * desktop's fourth chip lands there, not here.
 */
export function LimitedBanner({
  chatId,
  client,
  limit,
  scheduled,
  now,
  onResume,
}: {
  /** The chat's id as the page knows it (scoped; routing decodes either form). */
  readonly chatId: string;
  /** The chat's owning engine client — null disables every action. */
  readonly client: EngineClient | null;
  readonly limit: SessionLimit | null;
  readonly scheduled: ScheduledResume | null;
  readonly now: number;
  /** The composer's fixed-prompt send ("Continue where you left off."). */
  readonly onResume: () => void;
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

  const rows = list !== null && listFor === chatId ? failoverRows(list) : [];
  const hasCandidates = list !== null && listFor === chatId && failoverListOffers(list);

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
        onClick={onResume}
      >
        Resume
      </button>
      {offersFailover && hasCandidates && (
        <PickerCard
          open={open}
          onOpenChange={setOpen}
          placement="anchorAboveEnd"
          role="dialog"
          ariaLabel={failoverCardTitle(list!.harness)}
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
            list={list!}
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
      {notice !== null && <span className="limited-banner-notice">{notice}</span>}
    </div>
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
