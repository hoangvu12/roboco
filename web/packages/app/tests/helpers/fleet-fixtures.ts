import type { Chat, Device, Space } from "@roboco/proto";
import type { EngineRegistrySnapshot } from "@roboco/engine-client";

/**
 * `EngineRegistrySnapshot` fixtures for the fleet tests (ticket 87's dedup
 * suites): one supervised-engine shape — `baseUrl` key, host device id
 * (null until the first handshake loads `info`), and the row sets the
 * engine reports. Object-literal shaped like a real `EngineEntrySnapshot`;
 * `projectRegistrySnapshot` reads only `key`, `info`, `state`, and the
 * four row sets, so the cast carries no risk beyond those fields.
 *
 * A mirrored workspace feeds every engine the SAME rows (the whole
 * owner's device list and space list), exactly as the synced-registry
 * engines stream — the case the dedup helpers exist to collapse.
 */

/** The row sets one fixture engine reports; omitted lists stream empty. */
export interface FleetEngineRows {
  readonly chats?: readonly Chat[];
  readonly spaces?: readonly Space[];
  readonly devices?: readonly Device[];
}

export function fleetEngine(
  key: string,
  hostDeviceId: string | null,
  rows: FleetEngineRows = {},
): unknown {
  const empty = { rows: [], loaded: true, error: null };
  return {
    key,
    info: hostDeviceId === null ? null : { deviceId: hostDeviceId, workspaceScope: null },
    state: "connected",
    lastError: null,
    generation: 1,
    chats: { rows: rows.chats ?? [], loaded: true, error: null },
    spaces: { rows: rows.spaces ?? [], loaded: true, error: null },
    devices: { rows: rows.devices ?? [], loaded: true, error: null },
    sessions: empty,
  };
}

export function fleetRegistry(...engines: readonly unknown[]): EngineRegistrySnapshot {
  return { configurationError: null, engines } as unknown as EngineRegistrySnapshot;
}
