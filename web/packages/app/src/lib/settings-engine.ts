import type { EngineEntrySnapshot, EngineRegistrySnapshot } from "@roboco/engine-client";
import { engineDisplayName } from "./view";
import type { FleetState, StoredEngine } from "./engine-store";

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
 * The engine's own WatchDevices row is the name of record (and reflects
 * renames): the settings engine indicator names each engine by the device
 * row its own registry entry carries — never the pair-session label, and
 * never a peer's copy of the row, which may be stale or identify a
 * different engine. Discovery can carry only the device UUID (an engine's
 * `EngineInfo` has not landed yet), so the pairing store's pinned identity
 * resolves the row too; an engine with no resolvable row falls back to
 * the disambiguated host (`engineDisplayName`).
 */
export function settingsDeviceName(
  engine: Pick<StoredEngine, "baseUrl" | "label" | "deviceId">,
  registry: EngineRegistrySnapshot,
): string {
  const entry = registry.engines.find((candidate) => candidate.key === engine.baseUrl);
  const ownId = entry?.info?.deviceId ?? engine.deviceId ?? engine.baseUrl;
  const name = entry?.devices.rows.find((device) => device.id === ownId)?.name?.trim();
  return name || engineDisplayName(engine);
}

/** Name the active engine only when more than one is available. */
export function settingsEngineLabel(fleet: FleetState, registry: EngineRegistrySnapshot): string | null {
  if (fleet.engines.length < 2 || fleet.active === null) {
    return null;
  }
  const engine = fleet.engines.find((entry) => entry.baseUrl === fleet.active);
  return engine === undefined ? null : settingsDeviceName(engine, registry);
}

export function settingsEngineKey(fleet: FleetState): string | null {
  return fleet.active;
}

/**
 * The engine-addressing settings pages (Remote access, Agents, Accounts)
 * key their page body on this value — see
 * `components/settings-engine-page.tsx`.
 */

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
