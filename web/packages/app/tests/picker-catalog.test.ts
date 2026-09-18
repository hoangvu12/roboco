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
  readonly #statusListeners = new Set<(status: { state: string }) => void>();

  onStatus(listener: (status: { state: string }) => void): () => void {
    this.#statusListeners.add(listener);
    return () => this.#statusListeners.delete(listener);
  }

  /** Test seam: the engine finished dialing. */
  emitConnected(): void {
    for (const listener of this.#statusListeners) {
      listener({ state: "connected" });
    }
  }

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

  it("a forced refresh reloads a loaded slot without clearing its rows", async () => {
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    await catalog.loadHarnesses();
    const stale = catalog.getHarnesses().rows;
    // The forced load fires a second call while the old rows stay visible…
    const refresh = catalog.loadHarnesses({ force: true });
    expect(client.calls.filter((call) => call.method === "ListHarnesses")).toHaveLength(2);
    expect(catalog.getHarnesses().rows).toBe(stale);
    await refresh;
    // …and the fresh catalog replaces them.
    expect(catalog.getHarnesses().loaded).toBe(true);
  });

  it("rides targetDeviceId when the catalog targets another device", async () => {
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    catalog.setTargetDevice("remote-device");
    await catalog.loadHarnesses();
    const call = client.calls.find((entry) => entry.method === "ListHarnesses");
    expect(call?.params).toEqual({ targetDeviceId: "remote-device" });
  });

  it("setTargetDevice invalidates and re-kicks the harness catalog", async () => {
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    await catalog.loadHarnesses();
    catalog.setTargetDevice("remote-device");
    expect(catalog.getHarnesses().loaded).toBe(false);
    const calls = client.calls.filter((entry) => entry.method === "ListHarnesses");
    expect(calls).toHaveLength(2);
    expect((calls[1]!.params as { targetDeviceId?: string }).targetDeviceId).toBe("remote-device");
  });

  it("an offline call retries once the engine connects (the reload race)", async () => {
    // A page-load call races the websocket dial and fails immediately.
    client.nextError = new RpcError("transport", "Engine is offline; reconnecting");
    await catalog.loadHarnesses();
    expect(catalog.getHarnesses().error).toBe("Engine is offline; reconnecting");
    expect(catalog.getHarnesses().loaded).toBe(false);
    client.harnesses = [
      { id: "claude-code", name: "Claude", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
    ];
    client.emitConnected();
    // The connected status re-kicked the errored slot.
    await Promise.resolve();
    for (let attempt = 0; attempt < 10 && !catalog.getHarnesses().loaded; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    expect(catalog.getHarnesses().loaded).toBe(true);
    expect(catalog.getHarnesses().rows).toHaveLength(1);
  });

  it("normalizes model rows as they land", async () => {
    client.models = [{ id: "titan[1m]", label: "Titan (1M context)", reasoningLevels: [], options: [] }];
    await catalog.loadModels("codex");
    const rows = catalog.getModels("codex").rows;
    expect(rows).toHaveLength(1);
    expect(rows[0]!.id).toBe("titan");
    expect(rows[0]!.label).toBe("Titan");
    expect(rows[0]!.options.some((option) => option.id === "contextWindow")).toBe(true);
  });
});