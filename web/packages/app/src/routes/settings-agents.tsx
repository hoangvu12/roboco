import { useCallback, useEffect, useState } from "react";
import type { ReactElement, ReactNode } from "react";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import type { HarnessDescriptor, HarnessId, Model, TitleSettings } from "@roboco/proto";
import { RbSwitch } from "../components/base/switch";
import { DeviceSwitcher } from "../components/ui/DeviceSwitcher";
import { SettingsEngineIndicator } from "../components/settings-engine-indicator";
import { MenuRow } from "../components/ui/MenuRows";
import { SkeletonRows } from "../components/ui/Skeleton";
import { useEngineSession } from "../state/session-provider";
import { useWatchSnapshot } from "../state/hooks";
import {
  blurb,
  bumpHarnessCatalog,
  descriptorEnabled,
  getTitleSettings,
  listHarnesses,
  listModels,
  notInstalledHint,
  setHarnessEnabled,
  setTitleSettings as saveTitleSettings,
  supportsTitles,
  titleHarnessLabel,
  visibleHarnesses,
} from "../lib/harnesses";

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

export function AgentsSettingsPage() {
  const session = useEngineSession();
  const client = session?.client ?? null;
  const snapshot = useWatchSnapshot(session);
  const [target, setTarget] = useState<string | null>(null);
  const [harnesses, setHarnesses] = useState<Loadable<readonly HarnessDescriptor[]>>({ kind: "loading" });
  const [titleSettings, setTitleSettings] = useState<Loadable<TitleSettings>>({ kind: "loading" });
  const [titleModels, setTitleModels] = useState<TitleModels>({ kind: "loading" });
  /** null closed; false = harness picker, true = model picker. */
  const [titleMenu, setTitleMenu] = useState<boolean | null>(null);
  const [titleSaving, setTitleSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const devices = (snapshot?.devices.rows ?? [])
    .slice()
    .sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? "") || a.id.localeCompare(b.id));
  const localDeviceId = session?.client.engineInfo?.deviceId ?? null;

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
          ? await saveTitleSettings(client, save, target)
          : await getTitleSettings(client, target);
        setTitleSettings({ kind: "ready", value: settings });
        setTitleSaving(false);
        setError(null);
        if (settings.harness !== null) {
          setTitleModels({ kind: "loading" });
          try {
            setTitleModels({ kind: "ready", value: await listModels(client, settings.harness, target) });
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
    [client, target],
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
      setHarnesses({ kind: "ready", value: await listHarnesses(client, target) });
    } catch (cause) {
      setHarnesses({ kind: "error", message: describe(cause) });
      return;
    }
    await loadTitles(null);
  }, [client, target, loadTitles]);

  // Mount load, and a full drop-and-reload on every retarget (set_target_device).
  useEffect(() => {
    void load();
  }, [load]);

  function toggle(harness: HarnessId, enabled: boolean) {
    if (client === null) {
      return;
    }
    setError(null);
    void (async () => {
      try {
        const fresh = await setHarnessEnabled(client, harness, enabled, target);
        setHarnesses({ kind: "ready", value: fresh });
        bumpHarnessCatalog(session);
      } catch (cause) {
        setError(describe(cause));
      }
    })();
  }

  function setTargetDevice(next: string | null) {
    if (next === target) {
      return;
    }
    setTarget(next);
    setTitleMenu(null);
    setTitleSaving(false);
    setError(null);
  }

  return (
    <div className="settings-page">
      <div className="settings-title-row">
        <h1 className="settings-title">Agents</h1>
        <DeviceSwitcher
          devices={devices}
          localDeviceId={localDeviceId}
          target={target}
          onTargetChange={setTargetDevice}
        />
      </div>
      <p className="settings-subtitle">
        Choose which coding agents the composer offers. The setting is per device — switch devices in the
        header. Agents whose CLI isn't installed on a device can't be enabled there.
        <SettingsEngineIndicator />
      </p>

      {error !== null && (
        <p className="error-strip" role="alert" onClick={() => setError(null)}>
          {error}
        </p>
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
          <HarnessRows list={harnesses.value} onToggle={toggle} />
        </section>
      )}

      <TitleSettingsCard
        titleSettings={titleSettings}
        titleModels={titleModels}
        harnesses={harnesses.kind === "ready" ? harnesses.value : []}
        titleMenu={titleMenu}
        titleSaving={titleSaving}
        onToggleMenu={(isModel) => setTitleMenu((current) => (current === isModel ? null : isModel))}
        onChoose={(choice) => void loadTitles(choice)}
      />
    </div>
  );
}

function HarnessRows(props: {
  readonly list: readonly HarnessDescriptor[];
  readonly onToggle: (harness: HarnessId, enabled: boolean) => void;
}) {
  const descriptors = visibleHarnesses(props.list);
  const enabledCount = descriptors.filter((descriptor) => descriptorEnabled(descriptor)).length;
  return (
    <>
      {descriptors.map((descriptor, ix) => {
        const enabled = descriptorEnabled(descriptor);
        const installed = descriptor.installed;
        // The one enabled harness left can't be switched off — the composer
        // needs something to run — but only when it could actually run; and
        // turning OFF never needs the CLI, turning ON still does.
        const lastEnabled = enabled && enabledCount === 1 && installed;
        const interactive = !lastEnabled && (enabled || installed);
        const brand = harnessBrandIcon(descriptor.id);
        return (
          <div
            key={descriptor.id}
            className={`settings-row harness-row ${!installed ? "harness-row-uninstalled" : ""}`}
          >
            <div className="row-tile harness-tile" aria-hidden="true">
              <Icon
                name={brand.name}
                size={16}
                className="row-tile-icon"
                style={brand.tint === null ? undefined : { color: brand.tint }}
              />
            </div>
            <div className="settings-row-main">
              <span className="settings-row-title">{descriptor.name}</span>
              <span className="settings-meta-line">
                {blurb(descriptor.id)}
                {!installed && (
                  <>
                    <span className="settings-meta-dot" aria-hidden="true">·</span>
                    <span className="harness-hint">{notInstalledHint(descriptor.id, enabled)}</span>
                  </>
                )}
              </span>
            </div>
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
  readonly onToggleMenu: (isModel: boolean) => void;
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
        onToggle={() => props.onToggleMenu(false)}
      >
        {harnessChoices.map(choiceRow)}
      </TitlePickerRow>
      <TitlePickerRow
        label="Title model"
        display={modelLabel}
        interactive={!props.titleSaving && value.harness !== null}
        open={props.titleMenu === true}
        onToggle={() => props.onToggleMenu(true)}
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

function TitlePickerRow(props: {
  readonly label: string;
  readonly display: string;
  readonly interactive: boolean;
  readonly open: boolean;
  readonly onToggle: () => void;
  readonly children: ReactNode;
}) {
  return (
    <div className={`settings-row title-picker-row ${props.interactive ? "" : "title-picker-inert"}`}>
      <span className="settings-row-title title-picker-label">{props.label}</span>
      <button
        type="button"
        className="btn btn-ghost title-picker-trigger"
        disabled={!props.interactive}
        onClick={props.onToggle}
      >
        {props.display}
      </button>
      {props.open && <div className="title-picker-options">{props.children}</div>}
    </div>
  );
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
