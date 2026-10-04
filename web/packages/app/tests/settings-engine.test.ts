import { describe, expect, it } from "vitest";
import type { EngineEntrySnapshot, EngineConnectionState, EngineRegistrySnapshot } from "@roboco/engine-client";
import type { FleetState, StoredEngine } from "../src/lib/engine-store";
import { engineConnection, settingsEngineLabel } from "../src/lib/settings-engine";
import { engineDisplayName } from "../src/lib/view";

const HOST_A = "127.0.0.1:27699";
const HOST_B = "192.168.1.20:27699";

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
