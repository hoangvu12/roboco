import { describe, expect, it } from "vitest";
import type { HarnessId, HarnessUpdateStatus } from "@roboco/proto";
import {
  agentUpdateBannerEnabled,
  agentUpdateBannerTexts,
  agentUpdateNotificationKey,
  versionlessAgentUpdateKey,
  AgentUpdateWaves,
} from "../src/lib/agent-update-notifications";

/**
 * The agent-update banner's decision layer — the web port of
 * `shell/harness_updates.rs`'s notification half (the wave keys and the
 * versionless reset rule) plus `shell.rs:2193-2250`'s aggregate banner (the
 * 1s debounce lives with the store; this suite pins the pure rules) and the
 * desktop's preference gate.
 */

function status(fields: Partial<HarnessUpdateStatus>): HarnessUpdateStatus {
  return {
    harness: "codex",
    installedVersion: "1.0.0",
    latestVersion: "2.0.0",
    channel: null,
    source: "unknown",
    policy: "notify",
    phase: "available",
    progress: null,
    checkedAt: null,
    error: null,
    canApply: true,
    manualCommand: null,
    ...fields,
  };
}

describe("agentUpdateNotificationKey (harness_updates.rs's wave keys)", () => {
  it("keys an available release by harness and version", () => {
    expect(agentUpdateNotificationKey(status({}))).toBe("codex:2.0.0");
    expect(agentUpdateNotificationKey(status({ harness: "claude-code" as HarnessId }))).toBe("claude-code:2.0.0");
  });

  it("keys a versionless availability under the reserved versionless key", () => {
    expect(agentUpdateNotificationKey(status({ latestVersion: null }))).toBe(
      versionlessAgentUpdateKey("codex"),
    );
    expect(versionlessAgentUpdateKey("codex")).toBe("codex:versionless");
  });

  it("keys nothing outside the available phase (checks and installs never notify)", () => {
    for (const phase of ["checking", "waiting-for-idle", "preparing", "downloading", "installing", "verifying", "updated", "current", "failed"] as const) {
      expect(agentUpdateNotificationKey(status({ phase }))).toBeNull();
    }
  });
});

describe("AgentUpdateWaves (shell.rs:2193-2228's seen-set)", () => {
  it("reports the unseen available keys, then nothing once marked", () => {
    const waves = new AgentUpdateWaves();
    const statuses = [status({}), status({ harness: "hermes" as HarnessId, latestVersion: null })];
    expect(waves.unseenKeys(statuses)).toEqual(["codex:2.0.0", "hermes:versionless"]);
    expect(waves.unseenKeys(statuses)).toEqual(["codex:2.0.0", "hermes:versionless"]);
    waves.markSeen(["codex:2.0.0"]);
    expect(waves.unseenKeys(statuses)).toEqual(["hermes:versionless"]);
    waves.markSeen(["hermes:versionless"]);
    expect(waves.unseenKeys(statuses)).toEqual([]);
  });

  it("resets a versionless key once its harness confirms current or updated", () => {
    const waves = new AgentUpdateWaves();
    const versionless = status({ latestVersion: null });
    waves.markSeen(waves.unseenKeys([versionless]));
    // Transient checks, failures and installs do not reset the dedupe.
    waves.unseenKeys([status({ latestVersion: null, phase: "checking" })]);
    waves.unseenKeys([status({ latestVersion: null, phase: "failed" })]);
    expect(waves.unseenKeys([versionless])).toEqual([]);
    // A confirmed Current (or Updated) status re-arms the versionless wave.
    expect(waves.unseenKeys([status({ latestVersion: null, phase: "current" })])).toEqual([]);
    expect(waves.unseenKeys([versionless])).toEqual(["codex:versionless"]);
    // A VERSIONED key stays deduped for the viewport lifetime regardless.
    waves.markSeen(waves.unseenKeys([status({})]));
    waves.unseenKeys([status({ phase: "current" })]);
    expect(waves.unseenKeys([status({})])).toEqual([]);
  });
});

describe("agentUpdateBannerTexts (shell.rs:2234-2246, verbatim)", () => {
  it("singular and plural read exactly like the desktop's", () => {
    expect(agentUpdateBannerTexts(1)).toEqual({
      title: "1 agent update available",
      body: "A coding agent update is ready",
    });
    expect(agentUpdateBannerTexts(3)).toEqual({
      title: "3 agent updates available",
      body: "Coding agent updates are ready",
    });
  });
});

describe("agentUpdateBannerEnabled (the preference gate)", () => {
  const base = { notificationsEnabled: true, notificationsBackgroundOnly: true, agentUpdateNotifications: true };

  it("follows the master, the agent-updates row, and the background-only focus rule", () => {
    expect(agentUpdateBannerEnabled(base, false)).toBe(true);
    expect(agentUpdateBannerEnabled(base, true)).toBe(false);
    expect(agentUpdateBannerEnabled({ ...base, agentUpdateNotifications: false }, false)).toBe(false);
    expect(agentUpdateBannerEnabled({ ...base, notificationsEnabled: false }, false)).toBe(false);
    // Background-only off: the banner posts focused or not.
    expect(agentUpdateBannerEnabled({ ...base, notificationsBackgroundOnly: false }, true)).toBe(true);
  });
});
