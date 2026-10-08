import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { methods, parseScopedId, projectRegistrySnapshot } from "@roboco/engine-client";
import type { EngineEntrySnapshot, EngineRegistrySnapshot } from "@roboco/engine-client";
import type { Device } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import {
  forgetEngine,
  useFleet,
  useFleetRegistry,
  fleetStore,
  engineStatesOf,
  fleetLocalDeviceId,
} from "../state/fleet";
import { useNow } from "../state/hooks";
import {
  BtnGhost,
  BtnPrimary,
  Dialog,
  DialogCard,
  DialogField,
  DialogTitle,
} from "../components/ui/Dialog";
import { webDeviceLabel, type StoredEngine } from "../lib/engine-store";
import { deviceOnline, type EnginePresence } from "../lib/view";
import { describeRedeemError } from "../lib/pairing-errors";
import { engineConnection, settingsDeviceName } from "../lib/settings-engine";
import {
  formatLastSeenAt,
  partitionDevices,
  fleetDeviceRows,
  fleetHostDeviceIds,
  platformGlyph,
  platformLabel,
  presenceDot,
  shortId,
  type EngineConnection,
} from "../lib/devices";

/**
 * Devices settings (desktop settings/devices.rs parity): the device registry
 * of the connected engine, split into the desktop's two labeled sections
 * (devices.rs:517-557) — "This device" (the row whose id is the active
 * engine's own device, first) and "Other devices" — each row with the
 * platform tile's corner presence dot, the meta line (platform · version
 * · connection · last seen · added · the click-to-copy id chip), Rename (via
 * the Mutate renameDevice op) and the pairing box that redeems a pairing URL
 * through the fleet store — the page's one paste entry (the `/pair` landing
 * is the other, for token URLs; zeron's WorkOS sign-in hint is not ported —
 * Roboco pairs by URL).
 *
 * One row per engine, period (zeron 779cc2e0): the legacy "Engines" card
 * (the pairing-era drawer row ticket 45 folded in above the device rows)
 * listed the fleet's engines again, so every engine appeared twice — the
 * fleet's engines and their device rows describe the same fleet, and the
 * device-style host rows are the registry of record. They come from the
 * fleet registry's projected device list: `fleetDeviceRows` picks each
 * engine's own device row — the "engine-backed" row, whose presence is
 * that engine's live connection state and whose title is the device row's
 * name of record; a parked engine's host row is engine-backed-off (Forget
 * removes it from the fleet, "Pair again" re-pairs through `/pair`);
 * every other row falls back to the last-seen window.
 */

interface RenameDialog {
  readonly deviceId: string;
  readonly name: string;
}

export function DevicesSettingsPage() {
  const session = useEngineSession();
  const client = session?.client ?? null;
  const fleet = useFleet();
  const registry = useFleetRegistry();
  const now = useNow(15_000);
  const [pairingUrl, setPairingUrl] = useState("");
  const [pairingBusy, setPairingBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [rename, setRename] = useState<RenameDialog | null>(null);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // A new engine (a successful pair) rebuilds the session; reset the page.
  useEffect(() => {
    setError(null);
    setCopied(null);
    setRename(null);
  }, [session]);

  useEffect(() => {
    return () => {
      if (copyTimer.current !== null) {
        clearTimeout(copyTimer.current);
      }
    };
  }, []);

  const projected = useMemo(() => projectRegistrySnapshot(registry), [registry]);
  const engineHostDevices = useMemo(
    () => fleetDeviceRows(registry, projected.devices),
    [registry, projected.devices],
  );
  const hostDeviceIds = useMemo(() => fleetHostDeviceIds(registry), [registry]);
  const clientDevices = useMemo(
    () => projected.devices.filter((device) => !hostDeviceIds.has(device.id)),
    [projected.devices, hostDeviceIds],
  );
  const engineStates = useMemo(() => engineStatesOf(registry), [registry]);
  const registryByEngine = new Map(registry.engines.map((entry) => [entry.key, entry]));
  const localDeviceId = fleetLocalDeviceId(registry, fleet.active);
  const devices = [...engineHostDevices, ...clientDevices];
  const partition = partitionDevices(devices, localDeviceId);

  /** Live connection for a merged device row (scoped id → supervising engine). */
  function rowConnection(deviceId: string): EngineConnection | null {
    try {
      const scoped = parseScopedId(deviceId);
      if (scoped.engine !== null) {
        const live = engineStates.get(scoped.engine);
        if (live === undefined) {
          return null;
        }
        return live === "connected" ? "connected" : live === "reconnecting" ? "reconnecting" : "off";
      }
    } catch {
      // Unscoped id — fall through.
    }
    if (deviceId === localDeviceId) {
      const live = fleet.active !== null ? engineStates.get(fleet.active) : undefined;
      if (live === undefined) {
        return null;
      }
      return live === "connected" ? "connected" : live === "reconnecting" ? "reconnecting" : "off";
    }
    return null;
  }

  function rowOnline(device: Device): boolean {
    return deviceOnline(device, now, engineStates as EnginePresence);
  }

  /** Forget a parked engine when its host row is shown off-engine. */
  function forgetTarget(deviceId: string): string | null {
    try {
      const scoped = parseScopedId(deviceId);
      if (scoped.engine !== null && scoped.engine !== fleet.active) {
        return scoped.engine;
      }
    } catch {
      // ignore
    }
    return null;
  }

  function pair() {
    if (pairingBusy) {
      return;
    }
    const url = pairingUrl.trim();
    if (url.length === 0) {
      return;
    }
    setPairingBusy(true);
    setError(null);
    void (async () => {
      try {
        await fleetStore.redeemPairingUrl(url, webDeviceLabel());
        setPairingUrl("");
      } catch (cause) {
        setError(describeRedeemError(cause));
      } finally {
        setPairingBusy(false);
      }
    })();
  }

  function forget(baseUrl: string) {
    fleetStore.remove(baseUrl);
  }

  /** The dialog closes FIRST, then the trimmed name decides whether an RPC fires (submit_rename's silent-swallow quirk). */
  function submitRename(name: string) {
    const deviceId = rename?.deviceId;
    setRename(null);
    const trimmed = name.trim();
    if (deviceId === undefined || trimmed.length === 0 || client === null) {
      return;
    }
    void (async () => {
      try {
        await client.call(methods.MUTATE, { op: "renameDevice", deviceId, name: trimmed });
      } catch (cause) {
        setError(`Rename failed: ${cause instanceof Error ? cause.message : String(cause)}`);
      }
    })();
  }

  function copyId(deviceId: string) {
    void navigator.clipboard.writeText(deviceId).catch(() => {});
    setCopied(deviceId);
    if (copyTimer.current !== null) {
      clearTimeout(copyTimer.current);
    }
    copyTimer.current = setTimeout(() => setCopied(null), 1500);
  }

  const deviceCount = engineHostDevices.length + clientDevices.length;

  /** Render each device once, keeping engine actions on its host row. */
  const renderDeviceRow = (device: Device, ix: number) => {
    if (hostDeviceIds.has(device.id)) {
      const engineKey = parseScopedId(device.id).engine;
      const entry = engineKey !== null ? (registryByEngine.get(engineKey) ?? null) : null;
      const stored = fleet.engines.find((engine) => engine.baseUrl === engineKey);
      return (
        <EngineHostDeviceRow
          key={device.id}
          device={device}
          entry={entry}
          stored={stored ?? null}
          first={ix === 0}
          isActive={engineKey !== null && engineKey === fleet.active}
          connection={rowConnection(device.id)}
          online={rowOnline(device)}
          copied={copied === device.id}
          now={now}
          onCopyId={() => copyId(device.id)}
          registry={registry}
        />
      );
    }
    return (
      <DeviceRow
        key={device.id}
        device={device}
        first={ix === 0}
        connection={rowConnection(device.id)}
        online={rowOnline(device)}
        copied={copied === device.id}
        now={now}
        forgetBaseUrl={forgetTarget(device.id)}
        onCopyId={() => copyId(device.id)}
        onForget={() => {
          const target = forgetTarget(device.id);
          if (target !== null) forget(target);
        }}
        onRename={() => setRename({ deviceId: device.id, name: device.name })}
      />
    );
  };

  return (
    <div className="settings-page">
      <h1 className="settings-title">
        Devices{deviceCount > 0 && <span className="settings-title-count">{deviceCount}</span>}
      </h1>
      <p className="settings-subtitle">Connect and manage engines.</p>

      {error !== null && (
        <p className="error-strip" role="alert" onClick={() => setError(null)}>
          {error}
        </p>
      )}

      <section className="settings-card settings-pairing-box">
        <form
          className="settings-pairing-row"
          onSubmit={(event) => {
            event.preventDefault();
            pair();
          }}
        >
          <input
            className="input mono"
            type="text"
            placeholder="Paste a pairing URL or roboco-tailcat:… invite"
            value={pairingUrl}
            onChange={(event) => setPairingUrl(event.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <button type="submit" className="btn btn-solid">
            {pairingBusy ? "Connecting…" : "Connect"}
          </button>
        </form>
        <p className="settings-pairing-hint">
          Paste an HTTPS pairing link or a <code>roboco-tailcat:…</code> invite. Tailcat pairing
          runs on the server that serves this page.
        </p>
      </section>

      {partition.kind === "unknown" ? (
        <section className="settings-card">
          {deviceCount === 0 ? (
            <p className="settings-empty settings-empty-devices">No engines or devices yet. Pair one above.</p>
          ) : (
            devices.map(renderDeviceRow)
          )}
        </section>
      ) : (
        <>
          {partition.local.length > 0 && (
            <>
              <div className="settings-section-header"><h2>This device</h2></div>
              <section className="settings-card">{partition.local.map(renderDeviceRow)}</section>
            </>
          )}
          <div className="settings-section-header"><h2>Other devices</h2></div>
          <section className="settings-card">
            {partition.others.length === 0 ? (
              <p className="settings-empty">Pair another device to see it here.</p>
            ) : (
              partition.others.map(renderDeviceRow)
            )}
          </section>
        </>
      )}

      {rename !== null && (
        <RenameDeviceDialog dialog={rename} onCancel={() => setRename(null)} onSubmit={submitRename} />
      )}
    </div>
  );
}

interface EngineHostDeviceRowProps {
  readonly device: Device;
  readonly entry: EngineEntrySnapshot | null;
  readonly stored: StoredEngine | null;
  readonly first: boolean;
  readonly isActive: boolean;
  readonly connection: EngineConnection | null;
  readonly online: boolean;
  readonly copied: boolean;
  readonly now: number;
  readonly onCopyId: () => void;
  readonly registry: EngineRegistrySnapshot;
}

/** One paired engine host — device tile styling, engine actions on the row. */
function EngineHostDeviceRow(props: EngineHostDeviceRowProps) {
  const navigate = useNavigate();
  const { device, entry, stored, registry } = props;
  const registryConnection = engineConnection(entry);
  const version =
    device.version !== null && device.version !== undefined && device.version.length > 0 ? device.version : null;
  const dot = presenceDot(props.connection, props.online);
  const engineKey = stored?.baseUrl ?? entry?.key ?? null;
  const displayTitle =
    engineKey !== null
      ? settingsDeviceName(
          // A host row with no fleet pin still names through its own
          // entry: the engine key plus its live identity.
          stored ?? { baseUrl: engineKey, label: "", deviceId: entry?.info?.deviceId ?? null },
          registry,
        )
      : device.name;

  return (
    <div className={`settings-row device-row ${props.first ? "settings-row-first" : ""}`}>
      <div className="row-tile device-tile" aria-hidden="true">
        <Icon name={platformGlyph(device.platform)} size={16} className="row-tile-icon" />
        <span className={`presence-dot presence-${dot}`} />
      </div>
      <div className="settings-row-main">
        <span className="settings-row-title">{displayTitle}</span>
        <span className="settings-meta-line">
          {platformLabel(device.platform)}
          {version !== null && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {`v${version}`}
            </>
          )}
          <span className="settings-meta-dot" aria-hidden="true">·</span>
          {registryConnection.label}
          {!props.online && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {`Last seen ${formatLastSeenAt(device.lastSeenAt, props.now)}`}
            </>
          )}
          <span className="settings-meta-dot" aria-hidden="true">·</span>
          <button
            type="button"
            className={`id-chip ${props.copied ? "id-chip-copied" : ""}`}
            onClick={props.onCopyId}
            aria-label={`Copy device id ${device.id}`}
          >
            {props.copied ? "Copied" : shortId(device.id)}
          </button>
        </span>
      </div>
      {props.isActive ? <span className="badge">Active engine</span> : null}
      {registryConnection.pairable && (
        <button type="button" className="btn btn-ghost" onClick={() => void navigate({ to: "/pair" })}>
          Pair again
        </button>
      )}
      {stored !== null && <RemoveEngineButton engine={stored} />}
    </div>
  );
}

function RemoveEngineButton({ engine }: { engine: StoredEngine }) {
  const [confirming, setConfirming] = useState(false);
  if (!confirming) {
    return (
      <button type="button" className="btn btn-ghost" onClick={() => setConfirming(true)}>
        Forget
      </button>
    );
  }
  return (
    <button type="button" className="btn btn-danger-ghost" onClick={() => forgetEngine(engine.baseUrl)}>
      Forget?
    </button>
  );
}

function DeviceRow(props: {
  readonly device: Device;
  readonly first: boolean;
  readonly connection: EngineConnection | null;
  readonly online: boolean;
  readonly copied: boolean;
  readonly now: number;
  readonly forgetBaseUrl: string | null;
  readonly onCopyId: () => void;
  readonly onForget: () => void;
  readonly onRename: () => void;
}) {
  const device = props.device;
  const dot = presenceDot(props.connection, props.online);
  const version = device.version !== null && device.version !== undefined && device.version.length > 0
    ? device.version
    : null;
  return (
    <div className="settings-row device-row">
      <div className="row-tile device-tile" aria-hidden="true">
        <Icon name={platformGlyph(device.platform)} size={16} className="row-tile-icon" />
        <span className={`presence-dot presence-${dot}`} />
      </div>
      <div className="settings-row-main">
        <span className="settings-row-title">{device.name}</span>
        <span className="settings-meta-line">
          {platformLabel(device.platform)}
          {version !== null && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {`v${version}`}
            </>
          )}
          {props.connection !== null && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {props.connection === "connected"
                ? "Connected"
                : props.connection === "reconnecting"
                  ? "Reconnecting"
                  : "Off"}
            </>
          )}
          {!props.online && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {`Last seen ${formatLastSeenAt(device.lastSeenAt, props.now)}`}
            </>
          )}
          {device.createdAt !== null && device.createdAt !== undefined && (
            <>
              <span className="settings-meta-dot" aria-hidden="true">·</span>
              {`Added ${formatLastSeenAt(device.createdAt, props.now)}`}
            </>
          )}
          <span className="settings-meta-dot" aria-hidden="true">·</span>
          <button
            type="button"
            className={`id-chip ${props.copied ? "id-chip-copied" : ""}`}
            onClick={props.onCopyId}
            aria-label={`Copy device id ${device.id}`}
          >
            {props.copied ? "Copied" : shortId(device.id)}
          </button>
        </span>
      </div>
      {props.forgetBaseUrl !== null && (
        <button type="button" className="btn btn-ghost" onClick={props.onForget}>
          Forget
        </button>
      )}
      <button type="button" className="btn btn-ghost device-rename" onClick={props.onRename}>
        <Icon name="pen" size={14} />
        Rename
      </button>
    </div>
  );
}

/**
 * The rename dialog (devices.rs render_rename_dialog): the shared
 * `ui/Dialog` family — `RbDialog`'s scrim swallows presses without
 * dismissing (its `disablePointerDismissal` carries the old hand-roll's
 * parity quirk for free) and the phone arm is the family's bottom sheet
 * (the old fixed-centered card squashed at ≤768px). Cancel, Rename, and
 * Enter close it, as before; Escape now cancels too — the one gained
 * path, matching the shared dialogs (documented deviation). Exported
 * for the mounted family test (tests/settings-dialogs.test.ts).
 */
export function RenameDeviceDialog(props: {
  readonly dialog: RenameDialog;
  readonly onCancel: () => void;
  readonly onSubmit: (name: string) => void;
}) {
  const [name, setName] = useState(props.dialog.name);
  const inputRef = useRef<HTMLInputElement | null>(null);
  return (
    <Dialog ariaLabel="Rename device" onClose={props.onCancel} initialFocus={inputRef}>
      <DialogCard>
        <DialogTitle>Rename device</DialogTitle>
        <form
          className="dialog-form-rows"
          onSubmit={(event) => {
            event.preventDefault();
            props.onSubmit(name);
          }}
        >
          <DialogField>
            <input
              ref={inputRef}
              type="text"
              placeholder="Device name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoComplete="off"
              aria-label="Device name"
            />
          </DialogField>
          <div className="dialog-actions-row">
            <BtnGhost type="button" onClick={props.onCancel}>
              Cancel
            </BtnGhost>
            <BtnPrimary type="submit">Rename</BtnPrimary>
          </div>
        </form>
      </DialogCard>
    </Dialog>
  );
}
