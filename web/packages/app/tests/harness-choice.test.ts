import { describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { HarnessDescriptor } from "@roboco/proto";
import {
  catalogTargetDeviceId,
  dialogError,
  dialogLoading,
  dialogRows,
  loadDialogHarnesses,
  loadDialogModels,
} from "../src/lib/harness-choice";

/**
 * The shared harness/model choice loads (t3-orchestration-v2 tickets
 * 21+22+25) — the loadable shape, the catalog routing rule (which device's
 * CLIs matter), and the two RPCs the fork dialog and the Limited banner's
 * "Continue on another harness" card fire. Ports the desktop dialogs'
 * `fork_catalog_target` rule and the ListHarnesses/ListModels call shapes.
 */

const HARNESSES: HarnessDescriptor[] = [
  {
    id: "codex",
    name: "Codex",
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed: true,
    canInstall: false,
    enabled: true,
  },
];

const MODELS = [
  { id: "glm-5.3", label: "GLM 5.3", reasoningLevels: [], options: [] },
];

class FakeCaller {
  readonly calls: { method: string; params: unknown }[] = [];

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    switch (method) {
      case "ListHarnesses":
        return HARNESSES as T;
      case "ListModels":
        return MODELS as T;
      default:
        return {} as T;
    }
  }
}

describe("DialogLoad helpers", () => {
  it("idle/loading expose no rows and no error; landing shapes do", () => {
    expect(dialogRows("idle")).toEqual([]);
    expect(dialogRows("loading")).toEqual([]);
    expect(dialogError("idle")).toBeNull();
    expect(dialogLoading("loading")).toBe(true);
    expect(dialogLoading("idle")).toBe(false);
    expect(dialogRows({ rows: HARNESSES })).toBe(HARNESSES);
    expect(dialogError({ error: "engine offline" })).toBe("engine offline");
    expect(dialogRows({ error: "engine offline" })).toEqual([]);
    expect(dialogLoading({ rows: [] })).toBe(false);
  });
});

describe("catalogTargetDeviceId", () => {
  it("names the chat's device unless it is the engine's own", () => {
    // The chat runs on the local engine: no routing needed.
    expect(catalogTargetDeviceId("e:dev-1", "e:dev-1")).toBeNull();
    // A chat hosted on another device: its CLIs are the ones that matter.
    expect(catalogTargetDeviceId("e:dev-1", "e:dev-2")).toBe("e:dev-2");
    // The engine never published its own device: the chat's is the target.
    expect(catalogTargetDeviceId(null, "e:dev-2")).toBe("e:dev-2");
    expect(catalogTargetDeviceId(null, null)).toBeNull();
    expect(catalogTargetDeviceId("e:dev-1", null)).toBeNull();
  });
});

describe("loadDialogHarnesses", () => {
  it("asks ListHarnesses, riding targetDeviceId only when routed", async () => {
    const local = new FakeCaller();
    await expect(loadDialogHarnesses(local, null)).resolves.toBe(HARNESSES);
    expect(local.calls).toEqual([{ method: "ListHarnesses", params: {} }]);

    const remote = new FakeCaller();
    await expect(loadDialogHarnesses(remote, "e:dev-2")).resolves.toBe(HARNESSES);
    expect(remote.calls).toEqual([
      { method: "ListHarnesses", params: { targetDeviceId: "e:dev-2" } },
    ]);
  });
});

describe("loadDialogModels", () => {
  it("asks ListModels for the harness, riding the same routing", async () => {
    const caller = new FakeCaller();
    await expect(loadDialogModels(caller, "pi", null)).resolves.toBe(MODELS);
    expect(caller.calls).toEqual([{ method: "ListModels", params: { harness: "pi" } }]);

    const routed = new FakeCaller();
    await expect(loadDialogModels(routed, "pi", "e:dev-2")).resolves.toBe(MODELS);
    expect(routed.calls).toEqual([
      { method: "ListModels", params: { harness: "pi", targetDeviceId: "e:dev-2" } },
    ]);
  });
});

// A compile-time stand-in: the loads accept the real client's shape.
it("satisfies the EngineClient call surface", () => {
  expect(typeof loadDialogHarnesses(new FakeCaller() as unknown as EngineClient, null)).toBe("object");
});
