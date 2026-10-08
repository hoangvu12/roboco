import { describe, expect, it } from "vitest";
import type { Device } from "@roboco/proto";
import type { EngineEntrySnapshot, EngineConnectionState, EngineRegistrySnapshot } from "@roboco/engine-client";
import type { FleetState, StoredEngine } from "../src/lib/engine-store";
import { engineConnection, settingsDeviceName, settingsEngineLabel } from "../src/lib/settings-engine";
import { engineDisplayName } from "../src/lib/view";

const HOST_A = "127.0.0.1:27699";
const HOST_B = "192.168.1.20:27699";
const DEV_A = "dev-a";
const DEV_B = "dev-b";

function stored(baseUrl: string): StoredEngine {
  return {
    baseUrl,
    credential: "credential",
    label: "Web on Windows",
    sessionId: "session",
    pairedAt: 1,
    deviceId: null,
  };
}

function fleetOf(active: string | null, engines: readonly StoredEngine[]): FleetState {
  return { active, engines, configurationError: null };
}

function entryOf(key: string, state: EngineConnectionState, lastError: string | null): EngineEntrySnapshot {
  return {
    key,
    info: null,
    state,
    lastError,
    generation: 1,
    chats: { rows: [], loaded: false, error: null },
    spaces: { rows: [], loaded: false, error: null },
    devices: { rows: [], loaded: false, error: null },
    sessions: { rows: [], loaded: false, error: null },
  };
}

function registryOf(...entries: EngineEntrySnapshot[]): EngineRegistrySnapshot {
  return { engines: [...entries], configurationError: null };
}

function deviceRow(id: string, name: string): Device {
  return { id, name, platform: "macos", version: null, lastSeenAt: null, createdAt: null };
}

/** An entry whose WatchDevices rows are live, keyed by its own host id. */
function hostEntry(key: string, deviceId: string | null, rows: readonly Device[]): EngineEntrySnapshot {
  return {
    ...entryOf(key, "connected", null),
    info: deviceId === null ? null : { deviceId, capabilities: [], workspaceScope: "local" as const },
    devices: { rows: [...rows], loaded: true, error: null },
  };
}

describe("settingsEngineLabel", () => {
  it("settingsEngineLabelNamesActiveEngineOnlyWhenFleetIsPlural", () => {
    const a = stored(`http://${HOST_A}`);
    const b = stored(`http://${HOST_B}`);
    const reg = registryOf(entryOf(a.baseUrl, "connected", null), entryOf(b.baseUrl, "connected", null));
    // 0 engines: nothing to address.
    expect(settingsEngineLabel(fleetOf(null, []), reg)).toBe(null);
    // 1 engine: the single-engine case is unambiguous — no indicator.
    expect(settingsEngineLabel(fleetOf(`http://${HOST_A}`, [a]), reg)).toBe(null);
    // 2+ engines: the active engine's disambiguated name.
    expect(settingsEngineLabel(fleetOf(`http://${HOST_A}`, [a, b]), reg)).toBe(engineDisplayName(a));
    expect(settingsEngineLabel(fleetOf(`http://${HOST_B}`, [a, b]), reg)).toBe(engineDisplayName(b));
  });

  it("settingsEngineLabelNamesTheEngineFromItsOwnHostRowNotThePairSessionLabel", () => {
    // Port of zeron 97f86114: the engine's own WatchDevices row is the
    // name of record — the pair-session label ("Web on Windows") is never
    // the indicator's name, and a PEER engine's copy of the row (which may
    // be stale or identify a different engine) is never borrowed.
    const a = { ...stored(`http://${HOST_A}`), deviceId: DEV_A };
    const b = { ...stored(`http://${HOST_B}`), deviceId: DEV_B };
    const fleet = fleetOf(`http://${HOST_A}`, [a, b]);
    const registry = registryOf(
      hostEntry(`http://${HOST_A}`, DEV_A, [deviceRow(DEV_A, "Work Laptop"), deviceRow(DEV_B, "Wrong peer copy")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_B, "Server")]),
    );
    expect(settingsEngineLabel(fleet, registry)).toBe("Work Laptop");
    expect(settingsEngineLabel(fleetOf(`http://${HOST_B}`, [a, b]), registry)).toBe("Server");
    // A rename on the engine's own row is picked up live.
    const renamed = registryOf(
      hostEntry(`http://${HOST_A}`, DEV_A, [deviceRow(DEV_A, "Renamed Laptop")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_B, "Server")]),
    );
    expect(settingsEngineLabel(fleet, renamed)).toBe("Renamed Laptop");
    // A peer-only copy of the host row falls back to the disambiguated
    // host — never the other engine's device name.
    const peerOnly = registryOf(
      hostEntry(`http://${HOST_A}`, DEV_A, [deviceRow(DEV_B, "Wrong peer copy")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_B, "Server")]),
    );
    expect(settingsEngineLabel(fleet, peerOnly)).toBe(engineDisplayName(a));
  });

  it("settingsEngineLabelResolvesTheHostRowThroughThePinnedIdentityBeforeInfoLands", () => {
    // EngineInfo has not landed (spawn pending) but the devices rows were
    // re-seeded from the offline cache — the pairing store's pinned
    // deviceId still finds the engine's own row.
    const a = { ...stored(`http://${HOST_A}`), deviceId: DEV_A };
    const b = { ...stored(`http://${HOST_B}`), deviceId: DEV_B };
    const registry = registryOf(
      hostEntry(`http://${HOST_A}`, null, [deviceRow(DEV_A, "Work Laptop")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_B, "Server")]),
    );
    expect(settingsEngineLabel(fleetOf(`http://${HOST_A}`, [a, b]), registry)).toBe("Work Laptop");
  });

  it("settingsEngineLabelFallsBackForBlankNamesAndNamesNothingForAFleetMissingEngine", () => {
    const a = { ...stored(`http://${HOST_A}`), deviceId: DEV_A };
    const b = { ...stored(`http://${HOST_B}`), deviceId: DEV_B };
    // A blank host-row name is a missing name — fall back to the
    // disambiguated host, never an empty label.
    const blank = registryOf(
      hostEntry(`http://${HOST_A}`, DEV_A, [deviceRow(DEV_A, "   ")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_B, "Server")]),
    );
    expect(settingsEngineLabel(fleetOf(`http://${HOST_A}`, [a, b]), blank)).toBe(engineDisplayName(a));
    // An active key with no fleet engine names nothing (the indicator hides).
    expect(settingsEngineLabel(fleetOf("http://gone:27699", [a, b]), blank)).toBe(null);
  });
});

describe("settingsDeviceName", () => {
  it("takesTheEngineAndNeverReadsAnotherEngineForRow", () => {
    const engine = { baseUrl: `http://${HOST_A}`, label: "Web on Windows", deviceId: DEV_A };
    const registry = registryOf(
      hostEntry(`http://${HOST_A}`, DEV_A, [deviceRow(DEV_A, "Work Laptop")]),
      hostEntry(`http://${HOST_B}`, DEV_B, [deviceRow(DEV_A, "Wrong peer copy")]),
    );
    expect(settingsDeviceName(engine, registry)).toBe("Work Laptop");
    // No registry row at all: the disambiguated host (label never shown).
    expect(settingsDeviceName(engine, registryOf())).toBe(engineDisplayName(engine));
    expect(settingsDeviceName({ ...engine, deviceId: null }, registryOf())).toBe(engineDisplayName({ ...engine, deviceId: null }));
  });
});

describe("engineConnection (the folded engine-drawer row mapping)", () => {
  it("engineConnectionLabelsParkedAndIdentityChanged", () => {
    const cases: readonly [EngineEntrySnapshot | null, string, string, boolean][] = [
      // No registry entry yet (engine just paired, spawn pending).
      [null, "dot-connecting", "Starting…", false],
      [entryOf(`http://${HOST_A}`, "connected", null), "dot-connected", "Connected", false],
      [entryOf(`http://${HOST_A}`, "reconnecting", null), "dot-reconnecting", "Reconnecting…", false],
      // Parked without identity in the reason: revoked Session.
      [entryOf(`http://${HOST_A}`, "off", "handshake refused: 4401"), "dot-parked", "Session revoked", true],
      // Parked with identity in the reason: the engine changed underneath.
      [entryOf(`http://${HOST_A}`, "off", "engine identity mismatch"), "dot-parked", "Engine changed", true],
    ];
    for (const [entry, dot, label, pairable] of cases) {
      expect(engineConnection(entry)).toEqual({ dot, label, pairable });
    }
  });
});
