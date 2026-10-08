import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactElement } from "react";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import { HARNESS_UPDATES_V1 } from "@roboco/proto";
import type { EngineClient } from "@roboco/engine-client";
import type {
  HarnessDescriptor,
  HarnessId,
  HarnessUpdatePolicy,
  HarnessUpdateStatus,
  Model,
  TitleSettings,
} from "@roboco/proto";
import { RbSwitch } from "../components/base/switch";
import { DeviceSwitcher } from "../components/ui/DeviceSwitcher";
import { SettingsEngineIndicator } from "../components/settings-engine-indicator";
import { SettingsEnginePage } from "../components/settings-engine-page";
import { MenuRow } from "../components/ui/MenuRows";
import { PickerCard } from "../components/ui/PickerCard";
import { SkeletonRows } from "../components/ui/Skeleton";
import { useEngineSession } from "../state/session-provider";
import { useFleet, useFleetRegistry } from "../state/fleet";
import {
  applySettingsTargetChange,
  settingsDeviceSwitcherRows,
  settingsRpcTargetDeviceId,
  settingsSwitcherLocalDeviceId,
} from "../lib/settings-device-switcher";
import { TitlePickerRow } from "../components/settings-widgets";
import {
  applyHarnessUpdate,
  cancelHarnessUpdate,
  checkHarnessUpdates,
  setHarnessUpdatePolicy,
  updateLabel,
  updatePolicyOptions,
  updateRowAction,
  updateTone,
  watchHarnessUpdates,
} from "../lib/harnesses";
import {
  blurb,
  bumpHarnessCatalog,
  cancelInstall as cancelInstallRpc,
  descriptorEnabled,
  getTitleSettings,
  installHint,
  installHarness,
  installLabel,
  listHarnesses,
  listModels,
  nextSignInPhase,
  offersInstall,
  setHarnessEnabled,
  setTitleSettings as saveTitleSettings,
  signInFailureLabel,
  signInPendingLabel,
  signsInOnEnable,
  supportsTitles,
  titleHarnessLabel,
  visibleHarnesses,
  type SignInPhase,
} from "../lib/harnesses";
import { cancelAgentLogin, pollAgentLoginOnce, startAgentLogin } from "../lib/accounts";

/**
 * Agents settings — the desktop's HarnessesPage (nav label "Agents"):
 * per-device harness enablement rows (the composer offers what is on here),
 * the page-header device switcher, and the session-title pickers. Every
 * write is engine-side (`harness-prefs.json` on the target device); the
 * `SetHarnessEnabled` reply carries the fresh catalog, so the rows repaint
 * in one round trip, and the toggle ends with the composer-catalog bump so
 * the pickers re-fetch their harness list.
 */

type Loadable<T> =
  | { kind: "loading" }
  | { kind: "ready"; value: T }
  | { kind: "error"; message: string };

type TitleModels = Loadable<readonly Model[]>;

/** An enable-with-sign-in in flight (harnesses.rs `SignIn`). */
interface SignInState {
  readonly harness: HarnessId;
  /** Known once the engine accepted the start. */
  loginId: string | null;
  message: string | null;
  phase: SignInPhase;
}

/** A sign-in that failed at a phase (harnesses.rs `SignInFailure`). */
interface SignInFailure {
  readonly harness: HarnessId;
  readonly message: string;
  readonly phase: SignInPhase;
}

/**
 * The page body lives under `SettingsEnginePage`: a settings-engine
 * switch REMOUNTS it — fresh loads, and engine-local sign-in/install
 * state is dropped instead of reconciling onto the new engine — while
 * the DeviceSwitcher's intra-engine picks keep routing `targetDeviceId`
 * without a remount.
 */
export function AgentsSettingsPage() {
  return (
    <SettingsEnginePage>
      <AgentsSettingsPageBody />
    </SettingsEnginePage>
  );
}

function AgentsSettingsPageBody() {
  const session = useEngineSession();
  const client = session?.client ?? null;
  const fleet = useFleet();
  const registry = useFleetRegistry();
  const [target, setTarget] = useState<string | null>(null);
  const [harnesses, setHarnesses] = useState<Loadable<readonly HarnessDescriptor[]>>({ kind: "loading" });
  const [titleSettings, setTitleSettings] = useState<Loadable<TitleSettings>>({ kind: "loading" });
  const [titleModels, setTitleModels] = useState<TitleModels>({ kind: "loading" });
  /** null closed; false = harness picker, true = model picker. */
  const [titleMenu, setTitleMenu] = useState<boolean | null>(null);
  const [titleSaving, setTitleSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** An enable-with-sign-in in flight (antigravity). */
  const [signIn, setSignIn] = useState<SignInState | null>(null);
  const [signInFailure, setSignInFailure] = useState<SignInFailure | null>(null);
  /** Invalidates the poll loop of a cancelled/superseded sign-in. */
  const signInSeq = useRef(0);
  /** An explicit install in flight (harnesses.rs `installing`). */
  const [installing, setInstalling] = useState<HarnessId | null>(null);
  /** The live `installing` for async guards, plus the reply-invalidation
   *  counter (a retarget or supersede drops a stale install reply). */
  const installingRef = useRef<HarnessId | null>(null);
  const installSeq = useRef(0);
  const updateInstalling = useCallback((next: HarnessId | null) => {
    installingRef.current = next;
    setInstalling(next);
  }, []);

  /** The target engine's agent-CLI update lifecycle (standing watch). */
  const [updates, setUpdates] = useState<Loadable<readonly HarnessUpdateStatus[]>>({ kind: "loading" });
  /** The provider whose expanded details are open (null = collapsed). */
  const [expanded, setExpanded] = useState<HarnessId | null>(null);
  /** Which harness's policy dropdown is open. */
  const [policyMenu, setPolicyMenu] = useState<HarnessId | null>(null);
  /** Invalidates stale update-action replies on retarget. */
  const updateSeq = useRef(0);

  const devices = useMemo(() => settingsDeviceSwitcherRows(registry, fleet), [registry, fleet]);
  const localDeviceId = settingsSwitcherLocalDeviceId(session);

  // An engine switch remounts this body (SettingsEnginePage's key), so the
  // old engine's sign-in, install, and passthrough-target state never
  // carry over — no separate reset effect is needed.

  const rpcTarget = useMemo(() => settingsRpcTargetDeviceId(target, session), [target, session]);
  const supportsUpdates =
    client !== null && (client.engineInfo?.capabilities ?? []).includes(HARNESS_UPDATES_V1);

  const loadTitles = useCallback(
    async (save: TitleSettings | null) => {
      if (client === null) {
        return;
      }
      const saving = save !== null;
      setTitleMenu(null);
      setTitleSaving(saving);
      try {
        const settings = saving
          ? await saveTitleSettings(client, save, rpcTarget)
          : await getTitleSettings(client, rpcTarget);
        setTitleSettings({ kind: "ready", value: settings });
        setTitleSaving(false);
        setError(null);
        if (settings.harness !== null) {
          setTitleModels({ kind: "loading" });
          try {
            setTitleModels({ kind: "ready", value: await listModels(client, settings.harness, rpcTarget) });
          } catch (cause) {
            setTitleModels({ kind: "error", message: describe(cause) });
          }
        } else {
          setTitleModels({ kind: "loading" });
        }
      } catch (cause) {
        // A save failure surfaces in the page's error strip; a load failure
        // in the titles card itself (load_titles' split, harnesses.rs:192-207).
        setTitleSaving(false);
        if (saving) {
          setError(describe(cause));
        } else {
          setTitleSettings({ kind: "error", message: describe(cause) });
        }
      }
    },
    [client, rpcTarget],
  );

  const load = useCallback(async () => {
    if (client === null) {
      return;
    }
    setError(null);
    setHarnesses({ kind: "loading" });
    setTitleSettings({ kind: "loading" });
    setTitleModels({ kind: "loading" });
    setTitleMenu(null);
    try {
      setHarnesses({ kind: "ready", value: await listHarnesses(client, rpcTarget) });
    } catch (cause) {
      setHarnesses({ kind: "error", message: describe(cause) });
      return;
    }
    await loadTitles(null);
  }, [client, rpcTarget, loadTitles]);

  // Mount load, and a full drop-and-reload on every retarget (set_target_device).
  useEffect(() => {
    void load();
  }, [load]);

  // Unmount drops any in-flight sign-in poll loop and install reply (the
  // cancel itself is best-effort and only logged; harnesses.rs drop
  // semantics — the engine-side install guard owns the process cleanup).
  useEffect(() => {
    return () => {
      signInSeq.current += 1;
      installSeq.current += 1;
      updateSeq.current += 1;
    };
  }, []);

  // The standing update watch (harnesses.rs update_task): current snapshot
  // first, then every transition. The client re-subscribes after reconnects
  // on its own; a retarget tears it down and the new target's effect starts
  // a fresh one.
  useEffect(() => {
    if (client === null || !supportsUpdates) {
      setUpdates({ kind: supportsUpdates ? "loading" : "ready", value: [] });
      return;
    }
    setUpdates({ kind: "loading" });
    const handle = watchHarnessUpdates(
      client,
      {
        onItem: (statuses) => {
          setUpdates({ kind: "ready", value: statuses });
        },
        onEnd: (error) => {
          if (error !== undefined) {
            setUpdates({ kind: "error", message: error.message });
          } else {
            setUpdates((current) =>
              current.kind === "error"
                ? current
                : { kind: "error", message: "Connection closed. Reconnecting to device…" },
            );
          }
        },
      },
      rpcTarget,
    );
    return () => {
      handle.cancel();
    };
  }, [client, supportsUpdates, rpcTarget]);

  /**
   * One update-lifecycle action on the selected engine (harnesses.rs
   * check_updates/apply_harness_update/cancel_harness_update/
   * set_update_policy): replies and failures land in the page's error strip;
   * progress arrives through the watch.
   */
  function runUpdateAction(
    harness: HarnessId,
    run: (client: EngineClient) => Promise<unknown>,
  ) {
    if (client === null || updates.kind !== "ready") {
      return;
    }
    setError(null);
    const seq = updateSeq.current;
    void (async () => {
      try {
        await run(client);
      } catch (cause) {
        if (updateSeq.current !== seq) {
          return;
        }
        setError(describe(cause));
      }
    })();
  }

  function checkUpdatesNow() {
    if (client === null || updates.kind !== "ready") {
      return;
    }
    void (async () => {
      setError(null);
      try {
        const statuses = await checkHarnessUpdates(client, null, rpcTarget);
        setUpdates({ kind: "ready", value: statuses });
      } catch (cause) {
        setError(describe(cause));
      }
    })();
  }

  function applyUpdate(harness: HarnessId) {
    runUpdateAction(harness, (client) => applyHarnessUpdate(client, harness, rpcTarget));
  }

  function cancelUpdate(harness: HarnessId) {
    runUpdateAction(harness, (client) => cancelHarnessUpdate(client, harness, rpcTarget));
  }

  function choosePolicy(harness: HarnessId, policy: HarnessUpdatePolicy) {
    runUpdateAction(harness, (client) => setHarnessUpdatePolicy(client, harness, policy, rpcTarget));
  }

  function toggle(harness: HarnessId, enabled: boolean) {
    if (client === null) {
      return;
    }
    if (enabled && signsInOnEnable(harness)) {
      startSignIn(harness);
      return;
    }
    setError(null);
    void (async () => {
      try {
        const fresh = await setHarnessEnabled(client, harness, enabled, rpcTarget);
        setHarnesses({ kind: "ready", value: fresh });
        bumpHarnessCatalog(session);
      } catch (cause) {
        setError(describe(cause));
      }
    })();
  }

  /**
   * Sign in first, then switch on (harnesses.rs `start_sign_in`):
   * StartAgentLogin, then PollAgentLogin until the engine reports the
   * outcome, opening the sign-in page the first time a poll names it.
   */
  function startSignIn(harness: HarnessId) {
    if (client === null) {
      return;
    }
    if (rpcTarget !== null) {
      // The sign-in redirect lands on a loopback port of the device running
      // the agent, which a browser here can't reach.
      setError("Turn this agent on from its own device to sign in.");
      return;
    }
    setError(null);
    setSignInFailure(null);
    setSignIn({ harness, loginId: null, message: null, phase: "starting" });
    const seq = signInSeq.current + 1;
    signInSeq.current = seq;
    // The loop's own phase tracker (state reads inside the async closure
    // would see the phase at start time, not the current one).
    let phase: SignInPhase = "starting";
    const failure = (message: string) => {
      if (signInSeq.current !== seq) {
        return;
      }
      setSignIn(null);
      setSignInFailure({ harness, message, phase });
    };
    void (async () => {
      let loginId: string;
      try {
        const start = await startAgentLogin(client, harness, rpcTarget);
        if (signInSeq.current !== seq) {
          return;
        }
        loginId = start.loginId;
        phase = "installing";
        setSignIn((current) =>
          current === null || current.harness !== harness
            ? current
            : { ...current, loginId, phase },
        );
      } catch (cause) {
        failure(`Sign-in failed to start: ${describe(cause)}`);
        return;
      }
      // Pre-open a blank tab inside the click gesture so the poll-carried
      // url can navigate it (popup blockers eat post-await opens; the CLI's
      // own open is suppressed engine-side, settings-accounts parity).
      const tab = window.open("about:blank", "_blank");
      let opened = false;
      for (;;) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        if (signInSeq.current !== seq) {
          tab?.close();
          return;
        }
        let poll;
        try {
          poll = await pollAgentLoginOnce(client, loginId, rpcTarget);
        } catch (cause) {
          tab?.close();
          failure(describe(cause));
          return;
        }
        if (signInSeq.current !== seq) {
          tab?.close();
          return;
        }
        if (poll.status === "pending") {
          if (!opened && poll.url != null) {
            opened = true;
            if (tab !== null) {
              tab.location.href = poll.url;
              tab.opener = null;
            } else {
              window.open(poll.url, "_blank", "noopener,noreferrer");
            }
          }
          phase = nextSignInPhase(poll) ?? phase;
          setSignIn((current) =>
            current === null || current.harness !== harness
              ? current
              : { ...current, phase, message: poll.message ?? current.message },
          );
          continue;
        }
        if (poll.status === "done") {
          tab?.close();
          // The toggle itself, now that the sign-in succeeded — a failure
          // here lands as an Enable failure with the fresh catalog intact.
          phase = "enabling";
          setSignIn((current) =>
            current === null || current.harness !== harness
              ? current
              : { ...current, phase, message: null },
          );
          try {
            const fresh = await setHarnessEnabled(client, harness, true, rpcTarget);
            if (signInSeq.current !== seq) {
              return;
            }
            setHarnesses({ kind: "ready", value: fresh });
            setSignIn(null);
            setSignInFailure(null);
            bumpHarnessCatalog(session);
          } catch (cause) {
            failure(describe(cause));
          }
          return;
        }
        tab?.close();
        failure(poll.message ?? "Unknown error");
        return;
      }
    })();
  }

  function cancelSignIn() {
    const current = signIn;
    if (current === null || client === null) {
      return;
    }
    signInSeq.current += 1;
    setSignIn(null);
    if (current.loginId !== null) {
      // Best-effort; the desktop only debug-logs a failure.
      void cancelAgentLogin(client, current.loginId, rpcTarget).catch(() => undefined);
    }
  }

  /**
   * An explicit install on the selected engine (harnesses.rs `install`):
   * one at a time, and the reply — success or failure — only lands if the
   * page still targets the device the install went to. The fresh catalog
   * repaints the rows in one round trip, then the composer-catalog bump
   * re-fetches the pickers' harness list.
   */
  function install(harness: HarnessId) {
    if (client === null || installingRef.current !== null) {
      return;
    }
    setError(null);
    updateInstalling(harness);
    const seq = installSeq.current + 1;
    installSeq.current = seq;
    void (async () => {
      try {
        const fresh = await installHarness(client, harness, rpcTarget);
        if (installSeq.current !== seq) {
          return;
        }
        updateInstalling(null);
        setHarnesses({ kind: "ready", value: fresh });
        bumpHarnessCatalog(session);
      } catch (cause) {
        if (installSeq.current !== seq) {
          return;
        }
        updateInstalling(null);
        setError(`Installation failed — ${describe(cause)}`);
      }
    })();
  }

  /**
   * Cancel the running install (harnesses.rs `cancel_install`): addressed to
   * the same engine the install went to, same params. The install request
   * itself resolves next — as the cancelled failure the page reports — so
   * `installing` is not cleared here; only a failed cancellation shows an
   * error, and only while that install is still the page's in-flight one.
   */
  function cancelInstall() {
    const current = installingRef.current;
    if (current === null || client === null) {
      return;
    }
    const seq = installSeq.current;
    void cancelInstallRpc(client, current, rpcTarget).catch((cause: unknown) => {
      if (installSeq.current === seq && installingRef.current === current) {
        setError(`Cancellation failed — ${describe(cause)}`);
      }
    });
  }

  function setTargetDevice(next: string | null) {
    const { target: nextTarget, switchedEngine } = applySettingsTargetChange(next, fleet);
    if (nextTarget === target && !switchedEngine) {
      return;
    }
    // A retarget drops any in-flight sign-in (set_target_device cancels) and
    // forgets the install reply's destination (0b48258b: switching devices
    // does NOT cancel an install the user explicitly requested — it keeps
    // running on the device it belongs to; the stale reply is just dropped).
    signInSeq.current += 1;
    installSeq.current += 1;
    updateSeq.current += 1;
    setSignIn(null);
    updateInstalling(null);
    setSignInFailure(null);
    setExpanded(null);
    setPolicyMenu(null);
    setUpdates({ kind: "loading" });
    setTarget(nextTarget);
    setTitleMenu(null);
    setTitleSaving(false);
    setError(null);
  }

  return (
    <div className="settings-page">
      <div className="settings-title-row">
        <h1 className="settings-title">Agents</h1>
        <div className="harnesses-header-actions">
          {supportsUpdates && (
            <button
              type="button"
              className="btn btn-ghost harnesses-check-now"
              disabled={updates.kind !== "ready"}
              onClick={checkUpdatesNow}
            >
              Check now
            </button>
          )}
          <DeviceSwitcher
            devices={devices}
            localDeviceId={localDeviceId}
            target={target}
            engineCount={fleet.engines.length}
            onTargetChange={setTargetDevice}
          />
        </div>
      </div>
      <p className="settings-subtitle">
        Install coding agents and choose which ones the composer offers. Installations and settings apply
        to the selected device. Downloads start only when you choose Install.
        <SettingsEngineIndicator />
      </p>

      {error !== null && (
        <p className="error-strip" role="alert" onClick={() => setError(null)}>
          {error}
        </p>
      )}

      {updates.kind === "error" && (
        <div className="settings-error-retry">
          <p className="error-strip" role="alert">
            Agent updates: {updates.message}
          </p>
          <button type="button" className="btn btn-ghost" onClick={() => void load()}>
            Retry updates
          </button>
        </div>
      )}



      {harnesses.kind === "loading" ? (
        <section className="settings-card harnesses-skeleton">
          <SkeletonRows count={4} />
        </section>
      ) : harnesses.kind === "error" ? (
        <div className="settings-error-retry">
          <p className="error-strip" role="alert">
            {harnesses.message}
          </p>
          <button type="button" className="btn btn-ghost" onClick={() => void load()}>
            Retry
          </button>
        </div>
      ) : (
        <section className="settings-card">
          <HarnessRows
            list={harnesses.value}
            updates={updates.kind === "ready" ? updates.value : null}
            expanded={expanded}
            policyMenu={policyMenu}
            supportsUpdates={supportsUpdates}
            canControlUpdates={supportsUpdates && updates.kind === "ready"}
            onToggleDetails={setExpanded}
            onSetPolicyMenu={setPolicyMenu}
            onApplyUpdate={applyUpdate}
            onCancelUpdate={cancelUpdate}
            onChoosePolicy={choosePolicy}
            onToggle={toggle}
            signIn={signIn}
            signInFailure={signInFailure}
            onCancelSignIn={cancelSignIn}
            onRetrySignIn={startSignIn}
            installing={installing}
            onInstall={install}
            onCancelInstall={cancelInstall}
          />
        </section>
      )}

      <TitleSettingsCard
        titleSettings={titleSettings}
        titleModels={titleModels}
        harnesses={harnesses.kind === "ready" ? harnesses.value : []}
        titleMenu={titleMenu}
        titleSaving={titleSaving}
        onSetMenu={(open, isModel) => setTitleMenu(open ? isModel : null)}
        onChoose={(choice) => void loadTitles(choice)}
      />
    </div>
  );
}

/**
 * One provider row's update action (harnesses.rs update_action): the single
 * Update/Cancel control sits INSIDE the details trigger, before the chevron,
 * so its appearance never moves the (fixed-width) chevron.
 */
function UpdateActionButton(props: {
  readonly status: HarnessUpdateStatus;
  readonly onApply: () => void;
  readonly onCancel: () => void;
}) {
  const status = props.status;
  const action = updateRowAction(status);
  if (action === null) {
    return null;
  }
  return (
    <button
      type="button"
      className={`btn harness-update-action ${action.primary ? "harness-update-primary" : ""}`}
      onClick={(event) => {
        // Inside the details trigger: act, don't expand.
        event.stopPropagation();
        if (action.primary) {
          props.onApply();
        } else {
          props.onCancel();
        }
      }}
    >
      {action.label}
    </button>
  );
}

/** The expanded provider's Updates section (harnesses.rs render_updates_for):
 *  the policy dropdown with short labels and the chosen one explained. */
function UpdatesSection(props: {
  readonly status: HarnessUpdateStatus;
  readonly policyMenu: HarnessId | null;
  readonly onSetPolicyMenu: (harness: HarnessId | null) => void;
  readonly onChoosePolicy: (harness: HarnessId, policy: HarnessUpdatePolicy) => void;
}) {
  const status = props.status;
  const options = updatePolicyOptions();
  const selected = Math.max(
    0,
    options.findIndex((option) => option.policy === status.policy),
  );
  const current = options[selected] ?? options[0] ?? null;
  if (current === null) {
    return null;
  }
  const open = props.policyMenu === status.harness;
  return (
    <div className="harness-details">
      <span className="settings-details-label">Updates</span>
      <div className="settings-row harness-update-policy-row">
        <div className="settings-row-main">
          <span className="settings-row-title">Update policy</span>
          <span className="settings-meta-line">{current.note}</span>
        </div>
        <PickerCard
          open={open}
          onOpenChange={(next) => {
            props.onSetPolicyMenu(next ? status.harness : null);
          }}
          placement="anchorBelow"
          cardClassName="popover-card title-picker-menu harness-update-policy-menu"
          role="menu"
          ariaLabel="Update policy"
          width={220}
          initialFocus={false}
          trigger={
            <button type="button" className="btn btn-ghost title-picker-trigger">
              {current.label}
            </button>
          }
        >
          {options.map((option, ix) => (
            <MenuRow
              key={option.policy}
              fadeKey={option.policy}
              selected={option === current}
              onClick={() => {
                props.onSetPolicyMenu(null);
                props.onChoosePolicy(status.harness, option.policy);
              }}
            >
              {option.label}
            </MenuRow>
          ))}
        </PickerCard>
      </div>
    </div>
  );
}

function HarnessRows(props: {
  readonly list: readonly HarnessDescriptor[];
  readonly updates: readonly HarnessUpdateStatus[] | null;
  readonly expanded: HarnessId | null;
  readonly policyMenu: HarnessId | null;
  readonly supportsUpdates: boolean;
  readonly canControlUpdates: boolean;
  readonly onToggleDetails: (harness: HarnessId | null) => void;
  readonly onSetPolicyMenu: (harness: HarnessId | null) => void;
  readonly onApplyUpdate: (harness: HarnessId) => void;
  readonly onCancelUpdate: (harness: HarnessId) => void;
  readonly onChoosePolicy: (harness: HarnessId, policy: HarnessUpdatePolicy) => void;
  readonly onToggle: (harness: HarnessId, enabled: boolean) => void;
  readonly signIn: SignInState | null;
  readonly signInFailure: SignInFailure | null;
  readonly onCancelSignIn: () => void;
  readonly onRetrySignIn: (harness: HarnessId) => void;
  readonly installing: HarnessId | null;
  readonly onInstall: (harness: HarnessId) => void;
  readonly onCancelInstall: () => void;
}) {
  const descriptors = visibleHarnesses(props.list);
  const enabledCount = descriptors.filter((descriptor) => descriptorEnabled(descriptor)).length;
  return (
    <>
      {descriptors.map((descriptor, ix) => {
        const enabled = descriptorEnabled(descriptor);
        const installed = descriptor.installed;
        const installing = props.installing === descriptor.id;
        const signingIn =
          props.signIn !== null && props.signIn.harness === descriptor.id ? props.signIn : null;
        const signInFailure =
          props.signInFailure !== null && props.signInFailure.harness === descriptor.id
            ? props.signInFailure
            : null;
        const signInCancellable = signingIn !== null && signingIn.phase !== "enabling";
        // The one enabled harness left can't be switched off — the composer
        // needs something to run — but only when it could actually run; and
        // turning OFF never needs the CLI, turning ON still does. A row
        // mid-sign-in (or showing its failure) is inert until it resolves.
        const lastEnabled = enabled && enabledCount === 1 && installed;
        const interactive =
          signingIn === null && signInFailure === null && !lastEnabled && (enabled || installed);
        const brand = harnessBrandIcon(descriptor.id);
        const update = props.updates?.find((status) => status.harness === descriptor.id) ?? null;
        const expanded = props.expanded === descriptor.id && enabled;
        return (
          <div key={descriptor.id} className="harness-row-group">
            <div
              className={`settings-row harness-row ${!installed ? "harness-row-uninstalled" : ""} ${
                signingIn !== null ? "harness-row-signing-in" : ""
              }`}
            >
              <button
                type="button"
                className="harness-details-trigger"
                disabled={!enabled}
                aria-expanded={expanded}
                aria-label={`${descriptor.name} preferences`}
                onClick={() => props.onToggleDetails(expanded ? null : descriptor.id)}
              >
                <span className="row-tile harness-tile" aria-hidden="true">
                  <Icon
                    name={brand.name}
                    size={16}
                    className="row-tile-icon"
                    style={brand.tint === null ? undefined : { color: brand.tint }}
                  />
                </span>
                <span className="settings-row-main">
                  <span className="settings-row-title">{descriptor.name}</span>
                  <span className="settings-meta-line">
                    {blurb(descriptor.id)}
                    {signingIn !== null && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className="harness-sign-in-status">
                          {signingIn.message ?? signInPendingLabel(signingIn.phase)}
                        </span>
                      </>
                    )}
                    {signInFailure !== null && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className="harness-sign-in-failure">
                          {signInFailureLabel(signInFailure.phase)} — {signInFailure.message}
                        </span>
                      </>
                    )}
                    {installing && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className="harness-install-status">{installLabel(descriptor.name)}</span>
                      </>
                    )}
                    {!installed && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className="harness-hint">
                          {installHint(descriptor.id, enabled, descriptor.canInstall)}
                        </span>
                      </>
                    )}
                    {update !== null && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className={`harness-update-meta harness-update-meta-${updateTone(update.phase)}`}>
                          {updateLabel(update)}
                        </span>
                      </>
                    )}
                    {(descriptor.id === "cursor" || descriptor.id === "pi") && (
                      <>
                        <span className="settings-meta-dot" aria-hidden="true">·</span>
                        <span className="harness-managed-note">
                          {descriptor.id === "cursor"
                            ? "Cursor SDK · Managed by Roboco"
                            : "pi RPC bridge · Managed by Roboco"}
                        </span>
                      </>
                    )}
                  </span>
                </span>
                {props.canControlUpdates && update !== null && (
                  <UpdateActionButton
                    status={update}
                    onApply={() => props.onApplyUpdate(descriptor.id)}
                    onCancel={() => props.onCancelUpdate(descriptor.id)}
                  />
                )}
                {enabled && (
                  <span className="harness-details-chevron" aria-hidden="true">
                    <Icon name={expanded ? "altArrowDown" : "altArrowRight"} size={14} />
                  </span>
                )}
              </button>
            {installing ? (
              <button
                type="button"
                className="btn btn-ghost harness-cancel-install"
                onClick={props.onCancelInstall}
              >
                Cancel
              </button>
            ) : (
              offersInstall(descriptor.id, descriptor.installed, descriptor.canInstall) && (
                <button
                  type="button"
                  className="btn btn-ghost harness-install"
                  onClick={() => props.onInstall(descriptor.id)}
                >
                  Install
                </button>
              )
            )}
            {signInCancellable && (
              <button
                type="button"
                className="btn btn-ghost harness-sign-in-cancel"
                onClick={props.onCancelSignIn}
              >
                Cancel
              </button>
            )}
            {signInFailure !== null && (
              <button
                type="button"
                className="btn btn-ghost harness-sign-in-retry"
                onClick={() => props.onRetrySignIn(descriptor.id)}
              >
                Retry
              </button>
            )}
            {interactive ? (
              <RbSwitch
                checked={enabled}
                onCheckedChange={() => props.onToggle(descriptor.id, !enabled)}
                aria-label={descriptor.name}
              />
            ) : (
              <InertSwitch on={enabled} label={descriptor.name} />
            )}
            </div>
            {expanded && update !== null && (
              <UpdatesSection
                status={update}
                policyMenu={props.policyMenu}
                onSetPolicyMenu={props.onSetPolicyMenu}
                onChoosePolicy={props.onChoosePolicy}
              />
            )}
          </div>
        );
      })}
    </>
  );
}

/** The inert toggle twin: the same 32×18 switch with no handlers at all. */
function InertSwitch(props: { readonly on: boolean; readonly label: string }) {
  return (
    <span
      className={`toggle rb-switch-inert ${props.on ? "toggle-on" : ""}`}
      role="switch"
      aria-checked={props.on}
      aria-label={props.label}
    >
      <span className="toggle-thumb" />
    </span>
  );
}

function TitleSettingsCard(props: {
  readonly titleSettings: Loadable<TitleSettings>;
  readonly titleModels: TitleModels;
  readonly harnesses: readonly HarnessDescriptor[];
  readonly titleMenu: boolean | null;
  readonly titleSaving: boolean;
  readonly onSetMenu: (open: boolean, isModel: boolean) => void;
  readonly onChoose: (choice: TitleSettings) => void;
}) {
  const settings = props.titleSettings;
  if (settings.kind !== "ready") {
    const message = settings.kind === "error" ? settings.message : "Loading title settings…";
    return (
      <section className="settings-card settings-titles-card">
        <span className="settings-row-title">Session titles</span>
        <p className="settings-titles-loading">{message}</p>
      </section>
    );
  }
  const value = settings.value;
  const harnessName = (id: HarnessId): string => {
    const descriptor = props.harnesses.find((entry) => entry.id === id);
    return titleHarnessLabel(id, descriptor?.name ?? id);
  };
  const harnessLabel =
    value.harness !== null
      ? harnessName(value.harness)
      : "Automatic (session agent when supported)";
  const modelLabel =
    value.model !== null
      ? props.titleModels.kind === "ready"
        ? (props.titleModels.value.find((model) => model.id === value.model)?.label ?? value.model)
        : value.model
      : "Automatic (cheapest model)";

  const harnessChoices: readonly { readonly label: string; readonly value: TitleSettings }[] = [
    { label: "Automatic", value: { harness: null, model: null } },
    ...props.harnesses
      .filter(
        (descriptor) =>
          descriptorEnabled(descriptor) &&
          descriptor.installed &&
          supportsTitles(descriptor.id) &&
          descriptor.id !== "mock",
      )
      .map((descriptor) => ({
        label: descriptor.name,
        value: { harness: descriptor.id, model: null } satisfies TitleSettings,
      })),
  ];
  const modelChoices: readonly { readonly label: string; readonly value: TitleSettings }[] = [
    { label: "Automatic", value: { harness: value.harness, model: null } },
    ...(props.titleModels.kind === "ready"
      ? props.titleModels.value.map((model) => ({
          label: model.label,
          value: { harness: value.harness, model: model.id } satisfies TitleSettings,
        }))
      : []),
  ];

  const choiceRow = (choice: { readonly label: string; readonly value: TitleSettings }): ReactElement => (
    <MenuRow
      key={choice.label}
      fadeKey={choice.label}
      selected={choice.value.harness === value.harness && choice.value.model === value.model}
      onClick={() => props.onChoose(choice.value)}
    >
      {choice.label}
    </MenuRow>
  );

  return (
    <section className="settings-card settings-titles-card">
      <span className="settings-row-title">Session titles</span>
      <p className="settings-titles-subtitle">
        Choose the agent and model for automatic titles on this device. Claude Code and Codex support
        restricted title generation.
      </p>
      <TitlePickerRow
        label="Title harness"
        display={harnessLabel}
        interactive={!props.titleSaving}
        open={props.titleMenu === false}
        onOpenChange={(next) => props.onSetMenu(next, false)}
      >
        {harnessChoices.map(choiceRow)}
      </TitlePickerRow>
      <TitlePickerRow
        label="Title model"
        display={modelLabel}
        interactive={!props.titleSaving && value.harness !== null}
        open={props.titleMenu === true}
        onOpenChange={(next) => props.onSetMenu(next, true)}
      >
        {modelChoices.map(choiceRow)}
      </TitlePickerRow>
      {props.titleModels.kind === "error" && (
        <p className="error-strip" role="alert">
          {props.titleModels.message}
        </p>
      )}
    </section>
  );
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
