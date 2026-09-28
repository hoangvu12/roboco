import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { EngineClient } from "@roboco/engine-client";
import type { AgentAccount, AgentAccountsSnapshot, HarnessId } from "@roboco/proto";
import {
  activeAccount,
  activateAgentAccount,
  listAgentAccounts,
  markSwitched,
  providerAccounts,
  providerName,
  reportsUsage,
  signsIn,
  usedFraction,
  usageLevel,
} from "../lib/accounts";
import { PickerCard } from "./ui/PickerCard";
import { MenuHeading, MenuRowNav } from "./ui/MenuRows";
import { useCursorList } from "./ui/CursorList";

/**
 * The footer's plan-usage ring — the web peer of the desktop's
 * `account_usage.rs`. It shows how much of the session harness's live
 * account limit is used (its most-used window), beside the context ring;
 * clicking it opens that harness's accounts with their usage meters, and
 * clicking one switches to it via `ActivateAgentAccount` — the same call
 * Settings → Accounts runs.
 *
 * Both rings share the context indicator's geometry (a 16px ring with the
 * percentage beside it); the account ring's arc keeps the theme accent
 * (amber at 80%, red at 95%) so the two read apart, while its label stays
 * muted below the thresholds. The ring degrades quietly: no active account
 * with usage windows, no harness that reports usage, no client — no chip.
 *
 * Loads mirror the desktop: the first sight of a target lists plain-then-
 * forced (the engine's persisted usage paints at once, then the probe
 * replaces it); opening the card re-probes, throttled to 30s; a 5-minute
 * background re-probe tracks usage as turns run. A switch flips the rows
 * optimistically and restores the previous list on a refusal.
 */

/** `FORCE_MIN_INTERVAL` (account_usage.rs) — the ring re-probes on every
 * open, so it never asks more often than this. */
const FORCE_MIN_INTERVAL_MS = 30_000;
/** `POLL_INTERVAL` — background re-probe while the footer is alive. */
const POLL_INTERVAL_MS = 5 * 60_000;

export interface AccountUsageIndicatorProps {
  /** The routed engine client (the chat's owning engine). */
  readonly client: EngineClient | null;
  /** The session's harness; `null` (or a harness without accounts/usage) renders nothing. */
  readonly harness: HarnessId | null;
  /** The chat's host device when it isn't the connected engine's own. */
  readonly targetDeviceId: string | null;
}

export function AccountUsageIndicator({ client, harness, targetDeviceId }: AccountUsageIndicatorProps) {
  const [open, setOpen] = useState(false);
  const [snapshot, setSnapshot] = useState<AgentAccountsSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** The target the current `snapshot` belongs to (`null` = this device). */
  const loadedTargetRef = useRef<string | null | undefined>(undefined);
  const lastForcedRef = useRef(0);

  const load = useCallback(
    async (forceUsage: boolean): Promise<void> => {
      if (client === null) {
        return;
      }
      if (forceUsage && Date.now() - lastForcedRef.current < FORCE_MIN_INTERVAL_MS) {
        return;
      }
      const paintFirst = forceUsage && snapshot === null;
      if (forceUsage) {
        lastForcedRef.current = Date.now();
      }
      // Plain list first when nothing is cached (the engine's persisted
      // usage paints at once), then the forced probe replaces it.
      if (paintFirst) {
        try {
          setSnapshot(await listAgentAccounts(client, false, targetDeviceId));
        } catch {
          // The forced probe below replaces or errors on its own.
        }
      }
      try {
        setSnapshot(await listAgentAccounts(client, forceUsage, targetDeviceId));
        setError(null);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    },
    [client, targetDeviceId, snapshot],
  );

  // `track()`: a different target is a different accounts world — drop the
  // list and the throttle state; the first sight of any target loads.
  useEffect(() => {
    if (loadedTargetRef.current !== targetDeviceId) {
      loadedTargetRef.current = targetDeviceId;
      setSnapshot(null);
      setError(null);
      lastForcedRef.current = 0;
    }
    if (harness !== null && signsIn(harness) && reportsUsage(harness) && client !== null) {
      void load(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, harness, targetDeviceId]);

  // The background re-probe (usage moves as turns run). The interval re-arms
  // on every list — the next probe lands five minutes after the last one,
  // which matches the desktop's cadence in practice.
  useEffect(() => {
    if (harness === null || !signsIn(harness) || !reportsUsage(harness) || client === null) {
      return;
    }
    const timer = window.setInterval(() => void load(true), POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [client, harness, load]);

  // Opening the card is the moment someone cares: re-probe.
  useEffect(() => {
    if (open) {
      void load(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  /** Switch optimistically: the rows flip at once and the engine's reply
   * replaces them; a refusal restores the list. */
  const switchTo = useCallback(
    async (account: AgentAccount): Promise<void> => {
      if (client === null) {
        return;
      }
      const previous = snapshot;
      if (snapshot !== null) {
        const optimistic: AgentAccountsSnapshot = {
          ...snapshot,
          accounts: snapshot.accounts.map((row) => ({ ...row })),
        };
        markSwitched(optimistic, account);
        setSnapshot(optimistic);
      }
      setError(null);
      try {
        setSnapshot(await activateAgentAccount(client, account, targetDeviceId));
      } catch (cause) {
        if (previous !== null) {
          setSnapshot(previous);
        }
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    },
    [client, snapshot, targetDeviceId],
  );

  const rows = useMemo(
    () => (snapshot !== null && harness !== null ? providerAccounts(snapshot, harness) : []),
    [snapshot, harness],
  );
  const fraction = useMemo(() => {
    if (snapshot === null || harness === null) {
      return null;
    }
    const active = activeAccount(snapshot, harness);
    return active !== null ? usedFraction(active) : null;
  }, [snapshot, harness]);

  if (client === null || harness === null || !signsIn(harness) || !reportsUsage(harness) || fraction === null) {
    return null;
  }

  const level = usageLevel(fraction);
  const tone = level === "critical" ? "danger" : level === "warn" ? "warning" : "normal";
  const label = `${Math.round(fraction * 100)}%`;

  return (
    <PickerCard
      open={open}
      onOpenChange={setOpen}
      placement="anchorAboveEnd"
      role="dialog"
      ariaLabel={`${providerName(harness)} accounts`}
      width={400}
      overlaySource="composer-pickers"
      trigger={
        <div
          className="account-usage"
          data-tone={tone}
          data-open={open || undefined}
          title={`${providerName(harness)} plan usage`}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
            {/* Rotated so the arc starts at 12 o'clock, like the desktop's paths. */}
            <g transform="rotate(-90 8 8)" fill="none" strokeWidth="1.8">
              <circle cx="8" cy="8" r={RING_RADIUS} className="account-usage-track" />
              <circle
                cx="8"
                cy="8"
                r={RING_RADIUS}
                className="account-usage-arc"
                strokeDasharray={`${RING_CIRCUMFERENCE * fraction} ${RING_CIRCUMFERENCE}`}
                strokeLinecap="butt"
              />
            </g>
          </svg>
          <span>{label}</span>
        </div>
      }
    >
      <AccountUsageCard
        open={open}
        title={`${providerName(harness)} accounts`}
        rows={rows}
        error={error}
        onSwitch={(account) => void switchTo(account)}
      />
    </PickerCard>
  );
}

const RING_RADIUS = 6;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

function AccountUsageCard({
  open,
  title,
  rows,
  error,
  onSwitch,
}: {
  readonly open: boolean;
  readonly title: string;
  readonly rows: readonly AgentAccount[];
  readonly error: string | null;
  readonly onSwitch: (account: AgentAccount) => void;
}) {
  const { cursor, setCursor, onKeyDown } = useCursorList({
    enabled: open,
    count: rows.length,
    onActivate: (ix) => {
      const row = rows[ix];
      if (row !== undefined && !row.active && row.switchable) {
        onSwitch(row);
      }
    },
  });

  return (
    <div className="account-usage-card" onKeyDown={onKeyDown}>
      <MenuHeading>{title}</MenuHeading>
      {rows.length === 0 ? (
        <div className="picker-empty-note">No accounts on this device.</div>
      ) : (
        <div className="picker-list picker-list-plain">
          {rows.map((account, ix) => {
            const canSwitch = !account.active && account.switchable;
            const label = account.email ?? account.displayName ?? "Unknown account";
            return (
              <MenuRowNav
                key={account.id}
                fadeKey={`account-usage-row-${ix}`}
                highlighted={ix === cursor && !account.active}
                selected={account.active}
                className={canSwitch ? undefined : "menu-row-static"}
                onClick={() => {
                  if (canSwitch) {
                    setCursor(ix);
                    onSwitch(account);
                  }
                }}
              >
                <span className="account-usage-row-main">
                  <span className="account-usage-row-label">{label}</span>
                  <span className="account-usage-row-meta">
                    {account.planLabel != null && <span className="account-usage-row-plan">{account.planLabel}</span>}
                    {account.active && <span className="account-usage-row-in-use">In use</span>}
                    {account.usageError != null && account.usageWindows.length === 0 && (
                      <span className="account-usage-row-reason">{account.usageError}</span>
                    )}
                  </span>
                  {account.usageWindows.length > 0 && (
                    <span className="account-usage-meters">
                      {account.usageWindows.slice(0, 2).map((window, index) => (
                        <AccountUsageMeter key={index} label={window.label} usedFraction={window.usedFraction} />
                      ))}
                    </span>
                  )}
                </span>
              </MenuRowNav>
            );
          })}
        </div>
      )}
      {error !== null && (
        <div className="picker-trailing-error" role="alert">
          {error}
        </div>
      )}
    </div>
  );
}

/** One compact meter line (`render_usage_meter`): label, a short bar,
 * percent. The reset moment stays out of this card — the desktop's footer
 * card likewise leaves it to the settings page's fuller rows. */
function AccountUsageMeter({ label, usedFraction: fraction }: { readonly label: string; readonly usedFraction: number }) {
  const clamped = Math.min(Math.max(fraction, 0), 1);
  const level = usageLevel(clamped);
  return (
    <span className="account-usage-meter">
      <span className="account-usage-meter-label">{label}</span>
      <span className="account-usage-meter-track">
        {clamped > 0 && (
          <span
            className="account-usage-meter-fill"
            style={{ width: `${Math.max(clamped, 0.015) * 100}%`, background: meterFillVar(level) }}
          />
        )}
      </span>
      <span className={`account-usage-meter-percent ${level === "normal" ? "" : `account-usage-meter-percent-${level}`}`}>
        {Math.round(clamped * 100)}%
      </span>
    </span>
  );
}

/** The meter fill color (`usage_color` at the level's own opacity). */
function meterFillVar(level: "normal" | "warn" | "critical"): string {
  switch (level) {
    case "critical":
      return "color-mix(in srgb, var(--rb-danger) 90%, transparent)";
    case "warn":
      return "color-mix(in srgb, var(--rb-warning) 90%, transparent)";
    default:
      return "color-mix(in srgb, var(--rb-accent) 80%, transparent)";
  }
}
