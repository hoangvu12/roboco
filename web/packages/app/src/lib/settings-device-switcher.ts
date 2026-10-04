import {
  encodeScopedId,
  parseScopedId,
  projectRegistrySnapshot,
  type EngineEntrySnapshot,
  type EngineRegistrySnapshot,
} from "@roboco/engine-client";
import type { Device } from "@roboco/proto";
import type { FleetState, StoredEngine } from "./engine-store";
import { fleetHostDeviceIds } from "./devices";
import type { EngineSession } from "../state/engine-session";
import { fleetStore } from "../state/fleet";

/**
 * Devices for the settings Agents/Accounts header switcher: one host row per
 * paired engine (from the pairing store + registry, not only the active
 * engine's watch snapshot), plus non-host clients on the active engine.
 */
export function settingsDeviceSwitcherRows(
  registry: EngineRegistrySnapshot,
  fleet: FleetState,
): readonly Device[] {
  const activeEngineKey = fleet.active;
  const projected = projectRegistrySnapshot(registry);
  const hostIds = hostDeviceIds(registry, fleet);
  const byId = new Map<string, Device>();

  for (const stored of fleet.engines) {
    const row = hostDeviceRow(stored, registry.engines.find((entry) => entry.key === stored.baseUrl) ?? null, projected.devices);
    if (row !== null) {
      byId.set(row.id, row);
    }
  }
  for (const entry of registry.engines) {
    const stored = fleet.engines.find((engine) => engine.baseUrl === entry.key) ?? null;
    const row = hostDeviceRow(stored ?? { baseUrl: entry.key, deviceId: null, credential: "", label: "", sessionId: "", pairedAt: 0 }, entry, projected.devices);
    if (row !== null) {
      byId.set(row.id, row);
    }
  }

  const extra =
    activeEngineKey === null
      ? []
      : projected.devices.filter((device) => {
          if (hostIds.has(device.id)) {
            return false;
          }
          try {
            return parseScopedId(device.id).engine === activeEngineKey;
          } catch {
            return false;
          }
        });
  for (const row of extra) {
    byId.set(row.id, row);
  }

  return [...byId.values()].sort(
    (a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? "") || a.id.localeCompare(b.id),
  );
}

/** Scoped ids for every paired engine host (registry + persisted fleet pins). */
function hostDeviceIds(registry: EngineRegistrySnapshot, fleet: FleetState): ReadonlySet<string> {
  const ids = new Set(fleetHostDeviceIds(registry));
  for (const stored of fleet.engines) {
    const raw = stored.deviceId;
    if (raw !== null) {
      ids.add(encodeScopedId(stored.baseUrl, raw));
    }
  }
  return ids;
}

function hostDeviceRow(
  stored: StoredEngine,
  entry: EngineEntrySnapshot | null,
  projectedDevices: readonly Device[],
): Device | null {
  const engineKey = stored.baseUrl;
  const hostRaw = entry?.info?.deviceId ?? stored.deviceId;
  if (hostRaw === null || hostRaw === undefined) {
    return null;
  }
  const scopedId = encodeScopedId(engineKey, hostRaw);
  const fromProjected = projectedDevices.find((device) => device.id === scopedId);
  if (fromProjected !== undefined) {
    return fromProjected;
  }
  const fromWatch = entry?.devices.rows.find((row) => row.id === hostRaw);
  if (fromWatch !== undefined) {
    return { ...fromWatch, id: scopedId };
  }
  return {
    id: scopedId,
    name: stored.label.length > 0 ? stored.label : engineKey,
    platform: "linux",
    version: null,
    lastSeenAt: null,
    createdAt: null,
  };
}

/** The active engine host as a scoped device id (DeviceSwitcher "You"). */
export function settingsSwitcherLocalDeviceId(session: EngineSession | null): string | null {
  const raw = session?.client.engineInfo?.deviceId ?? null;
  if (session === null || raw === null) {
    return null;
  }
  return encodeScopedId(session.engine.baseUrl, raw);
}

/** Wire `targetDeviceId` from page state (scoped or legacy raw) + routed session. */
export function settingsRpcTargetDeviceId(target: string | null, session: EngineSession | null): string | null {
  if (target === null || session === null) {
    return null;
  }
  try {
    const scoped = parseScopedId(target);
    const localRaw = session.client.engineInfo?.deviceId ?? null;
    if (scoped.rawId === localRaw) {
      return null;
    }
    return scoped.rawId;
  } catch {
    const localRaw = session.client.engineInfo?.deviceId ?? null;
    if (target === localRaw) {
      return null;
    }
    return target;
  }
}

/**
 * Map a DeviceSwitcher pick to fleet active + page target. Picking another
 * engine's host switches `fleet.active` and clears passthrough target.
 */
export function applySettingsTargetChange(
  next: string | null,
  fleet: FleetState,
): { target: string | null; switchedEngine: boolean } {
  if (next === null) {
    return { target: null, switchedEngine: false };
  }
  try {
    const scoped = parseScopedId(next);
    if (scoped.engine !== null && scoped.engine !== fleet.active) {
      fleetStore.setActive(scoped.engine);
      return { target: null, switchedEngine: true };
    }
  } catch {
    // Legacy unscoped id on the active engine.
  }
  return { target: next, switchedEngine: false };
}
