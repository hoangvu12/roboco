import type { EngineEntrySnapshot, EngineRegistrySnapshot } from "@roboco/engine-client";
import { engineDisplayName } from "./view";
import type { FleetState } from "./engine-store";

/**
 * The engine-addressing settings vocabulary (ticket 45): which engine
 * the settings pages that silently talk to the *active* one (Remote
 * access, Agents, Accounts) are addressing, and what one paired engine's
 * connection looks like as a row. The desktop has no analog — its
 * settings address the implicit local engine by construction
 * (`remote_access.rs:37-39`); the web's closest concept is
 * `fleet.active`, which until this ticket was invisible and
 * unswitchable.
 */

/**
 * The engine those settings pages address, named only when the fleet is
 * plural: `engineHost(fleet.active)` with two or more engines paired,
 * else null — the single-engine case is unambiguous and shows no
 * indicator at all.
 */
export function settingsEngineLabel(fleet: FleetState, registry: EngineRegistrySnapshot): string | null {
  if (fleet.engines.length <= 1 || fleet.active === null) {
    return null;
  }
  return settingsDeviceName(fleet.active, registry);
}

/** Live host device name for the settings engine switcher (not the pair-session label). */
export function settingsDeviceName(engineKey: string, registry: EngineRegistrySnapshot): string {
  const entry = registry.engines.find((engine) => engine.key === engineKey);
  const hostRaw = entry?.info?.deviceId;
  if (entry !== undefined && hostRaw !== null && hostRaw !== undefined) {
    const host = entry.devices.rows.find((row) => row.id === hostRaw);
    if (host !== undefined) {
      return host.name;
    }
  }
  const stored = registry.engines.find((engine) => engine.key === engineKey);
  return stored !== undefined
    ? engineDisplayName({ baseUrl: engineKey, label: "", deviceId: stored.info?.deviceId ?? null })
    : engineKey;
}

export function settingsEngineKey(fleet: FleetState): string | null {
  return fleet.active;
}

/** One paired engine's connection view off its registry entry state. */
export interface EngineConnectionView {
  /** The shared status-dot class (`dot-connected` and siblings). */
  readonly dot: string;
  /** The connection label for the row's meta line. */
  readonly label: string;
  /** True when the entry is parked — the row offers "Pair again". */
  readonly pairable: boolean;
}

/**
 * The engine-row state mapping, ported from the deleted engine drawer's
 * `entryConnection`: "Starting…" while the registry entry is still
 * pending, "Connected"/"Reconnecting…" off its live state, and parked
 * reads "Engine changed" when the off-reason names identity, "Session
 * revoked" otherwise — parked alone is pairable.
 */
export function engineConnection(entry: EngineEntrySnapshot | null): EngineConnectionView {
  if (entry === null) {
    return { dot: "dot-connecting", label: "Starting…", pairable: false };
  }
  switch (entry.state) {
    case "connected":
      return { dot: "dot-connected", label: "Connected", pairable: false };
    case "reconnecting":
      return { dot: "dot-reconnecting", label: "Reconnecting…", pairable: false };
    case "off":
      return {
        dot: "dot-parked",
        label: (entry.lastError ?? "").includes("identity") ? "Engine changed" : "Session revoked",
        pairable: true,
      };
  }
}
