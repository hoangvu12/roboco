import { describe, expect, it } from "vitest";
import type { HarnessUpdateStatus } from "@roboco/proto";
import { agentUpdateStripLabel } from "../src/components/agent-update-strip";

/**
 * The strip is the web's UpdateStatus-style watch surface for the desktop's
 * Home agent-update card (shell/harness_updates.rs): one compact line for
 * the first notice row, clicking opens Settings → Agents.
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

describe("agentUpdateStripLabel (the compact summary)", () => {
  it("names the agent and release for a single available row", () => {
    expect(agentUpdateStripLabel([status({})])).toEqual({
      label: "Codex 2.0.0 available",
      title: "Update available",
    });
    expect(agentUpdateStripLabel([status({ latestVersion: null })])).toEqual({
      label: "Codex update available",
      title: "Update available",
    });
  });

  it("aggregates multiple notices like the card title", () => {
    const notices = [
      status({}),
      status({ harness: "hermes", phase: "installing", latestVersion: null }),
    ];
    expect(agentUpdateStripLabel(notices)).toEqual({
      label: "2 agent updates",
      title: "2 agent updates",
    });
    const updated = [
      status({ phase: "updated", latestVersion: null }),
      status({ harness: "hermes", phase: "updated", latestVersion: null }),
    ];
    expect(agentUpdateStripLabel(updated)?.label).toBe("2 agents updated");
  });

  it("renders the phase summary while a mutation runs or fails", () => {
    expect(agentUpdateStripLabel([status({ phase: "installing", latestVersion: null })]))
      .toEqual({ label: "Updating Codex…", title: "Installing update" });
    expect(
      agentUpdateStripLabel([status({ phase: "failed", error: { message: "boom", retryable: true } })]),
    ).toEqual({ label: "Codex update check failed", title: "Agent update check failed" });
  });

  it("stays hidden for non-notice phases", () => {
    for (const phase of ["dormant", "checking", "current", "manual-action-required"] as const) {
      expect(agentUpdateStripLabel([status({ phase })])).toBeNull();
    }
    expect(agentUpdateStripLabel([])).toBeNull();
  });
});
