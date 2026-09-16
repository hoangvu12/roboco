import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RpcError } from "@roboco/engine-client";
import type { HarnessDescriptor, Model } from "@roboco/proto";
import { PickerCatalog } from "../src/state/picker-catalog";

interface Call {
  method: string;
  params: unknown;
}

class FakeClient {
  readonly calls: Call[] = [];
  harnesses: HarnessDescriptor[] = [];
  models: Model[] = [];
  nextError: Error | null = null;

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    if (this.nextError !== null) {
      const error = this.nextError;
      this.nextError = null;
      throw error;
    }
    if (method === "ListHarnesses") {
      return this.harnesses as unknown as T;
    }
    if (method === "ListModels") {
      return this.models as unknown as T;
    }
    return {} as T;
  }
}

describe("PickerCatalog", () => {
  let client: FakeClient;
  let catalog: PickerCatalog;

  beforeEach(() => {
    client = new FakeClient();
    catalog = new PickerCatalog(client as unknown as ConstructorParameters<typeof PickerCatalog>[0]);
  });

  afterEach(() => {
    catalog.dispose();
  });

  it("starts unloaded", () => {
    const harnesses = catalog.getHarnesses();
    expect(harnesses.loaded).toBe(false);
    expect(harnesses.loading).toBe(false);
    expect(harnesses.rows).toEqual([]);
  });

  it("loadHarnesses fetches once and caches the rows", async () => {
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    await catalog.loadHarnesses();
    expect(client.calls).toEqual([{ method: "ListHarnesses", params: {} }]);
    expect(catalog.getHarnesses().rows).toHaveLength(1);
    expect(catalog.getHarnesses().loaded).toBe(true);

    // A second call is a no-op until invalidate.
    await catalog.loadHarnesses();
    expect(client.calls).toHaveLength(1);
  });

  it("loadModels fetches per harness and caches by harness id", async () => {
    client.models = [{ id: "sonnet", label: "Sonnet", reasoningLevels: ["medium"], options: [] }];
    await catalog.loadModels("claude-code");
    expect(client.calls).toEqual([{ method: "ListModels", params: { harness: "claude-code" } }]);
    expect(catalog.getModels("claude-code").rows).toHaveLength(1);
    expect(catalog.getModels("codex").rows).toEqual([]);
  });

  it("surfaces engine failures as a per-collection error", async () => {
    client.nextError = new RpcError("transport", "engine offline");
    await catalog.loadHarnesses();
    expect(catalog.getHarnesses().error).toBe("engine offline");
  });

  it("degrades to an empty list when the engine lacks the method", async () => {
    client.nextError = new RpcError("unknown-method", "unknown method: ListHarnesses");
    await catalog.loadHarnesses();
    expect(catalog.getHarnesses().rows).toEqual([]);
    expect(catalog.getHarnesses().loaded).toBe(true);
  });

  it("notifies subscribers on harness refresh", async () => {
    const listener = vi.fn();
    catalog.subscribe(listener);
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    await catalog.loadHarnesses();
    expect(listener).toHaveBeenCalled();
  });

  it("invalidate clears the catalog so the next fetch re-loads", async () => {
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    await catalog.loadHarnesses();
    expect(catalog.getHarnesses().rows).toHaveLength(1);
    catalog.invalidate();
    expect(catalog.getHarnesses().rows).toEqual([]);
    expect(catalog.getHarnesses().loaded).toBe(false);
    await catalog.loadHarnesses();
    expect(client.calls.filter((call) => call.method === "ListHarnesses")).toHaveLength(2);
  });
});