import { describe, expect, it } from "vitest";
import type { Device } from "@roboco/proto";
import type { EngineEntrySnapshot, EngineRegistrySnapshot } from "@roboco/engine-client";
import { encodeScopedId } from "@roboco/engine-client";
import type { FleetState, StoredEngine } from "../src/lib/engine-store";
import { settingsDeviceSwitcherRows } from "../src/lib/settings-device-switcher";

const HOST_A = "http://127.0.0.1:27655";
const HOST_B = "http://192.168.1.50:27655";

function stored(baseUrl: string, deviceId: string | null = null): StoredEngine {
  return {
    baseUrl,
    credential: "credential",
    label: "Web on Windows",
    sessionId: "session",
    pairedAt: 1,
    deviceId,
  };
}

function fleetOf(active: string | null, engines: readonly StoredEngine[]): FleetState {
  return { active, engines, configurationError: null };
}

function device(id: string, name: string): Device {
  return {
    id,
    name,
    platform: "linux",
    version: "0.6.0",
    lastSeenAt: "2026-01-01T00:00:00.000Z",
    createdAt: "2026-01-01T00:00:00.000Z",
  };
}

function entry(key: string, hostRaw: string, devices: readonly Device[]): EngineEntrySnapshot {
  return {
    key,
    info: { deviceId: hostRaw, capabilities: [], workspaceScope: "local" as const },
    state: "connected",
    lastError: null,
    generation: 1,
    chats: { rows: [], loaded: true, error: null },
    spaces: { rows: [], loaded: true, error: null },
    devices: { rows: [...devices], loaded: true, error: null },
    sessions: { rows: [], loaded: true, error: null },
  };
}

describe("settingsDeviceSwitcherRows", () => {
  it("listsOneHostRowPerPairedEngine", () => {
    const rawA = "dev-a";
    const rawB = "dev-b";
    const scopedA = encodeScopedId(HOST_A, rawA);
    const scopedB = encodeScopedId(HOST_B, rawB);
    const a = stored(HOST_A, rawA);
    const b = stored(HOST_B, rawB);
    const registry: EngineRegistrySnapshot = {
      configurationError: null,
      engines: [
        entry(HOST_A, rawA, [device(rawA, "local-node")]),
        entry(HOST_B, rawB, [device(rawB, "remote-node")]),
      ],
    };
    const rows = settingsDeviceSwitcherRows(registry, fleetOf(HOST_A, [a, b]));
    expect(rows.map((row) => row.id).sort()).toEqual([scopedA, scopedB].sort());
    expect(rows.map((row) => row.name).sort()).toEqual(["remote-node", "local-node"].sort());
  });

  it("usesPersistedDeviceIdWhenRegistryInfoIsMissing", () => {
    const rawB = "dev-b";
    const scopedB = encodeScopedId(HOST_B, rawB);
    const registry: EngineRegistrySnapshot = {
      configurationError: null,
      engines: [
        entry(HOST_A, "dev-a", [device("dev-a", "local-node")]),
        {
          ...entry(HOST_B, rawB, []),
          info: null,
          state: "reconnecting",
        },
      ],
    };
    const rows = settingsDeviceSwitcherRows(
      registry,
      fleetOf(HOST_A, [stored(HOST_A, "dev-a"), stored(HOST_B, rawB)]),
    );
    expect(rows.some((row) => row.id === scopedB)).toBe(true);
  });
});
