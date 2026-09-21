import { describe, expect, it, vi } from "vitest";
import type { ChangeRequestSummary, CheckoutChangeRequestStatus } from "@roboco/proto";
import { RpcError, type WatchHandle } from "@roboco/engine-client";
import {
  ChangeRequestStore,
  checkoutKey,
  providerForCheckout,
} from "../src/state/change-requests-store";

type WatchHandlers = {
  onItem: (item: unknown, context: { generation: number }) => void;
  onEnd?: (error: RpcError | undefined) => void;
};

interface FakeWatch {
  method: string;
  params: unknown;
  handlers: WatchHandlers;
  cancel: () => void;
}

class FakeClient {
  generation = 1;
  watches: FakeWatch[] = [];

  async call<T>(): Promise<T> {
    return {} as T;
  }

  watch<T>(method: string, params: unknown, handlers: WatchHandlers): WatchHandle {
    const watch: FakeWatch = {
      method,
      params,
      handlers: handlers as WatchHandlers,
      cancel: vi.fn(),
    };
    this.watches.push(watch);
    return { method, cancel: () => watch.cancel() };
  }
}

function status(input: Partial<CheckoutChangeRequestStatus>): CheckoutChangeRequestStatus {
  return {
    checkoutId: "checkout-1",
    deviceId: "device-1",
    cwd: "/repo/acme/roboco",
    branch: "feature/pr",
    changeRequest: null,
    updatedAt: "2026-01-01T00:00:00Z",
    ...input,
  };
}

function summary(input: Partial<ChangeRequestSummary>): ChangeRequestSummary {
  return {
    provider: "github",
    number: 90,
    title: "Pull request 90",
    url: "https://github.com/acme/roboco/pull/90",
    state: "open",
    baseRef: "main",
    headRef: "feature/pr",
    ...input,
  };
}

function deliver(watch: FakeWatch, item: unknown, generation = 1): void {
  watch.handlers.onItem(item, { generation });
}

describe("ChangeRequestStore provider tracking", () => {
  it("records the engine-detected provider on the first CR observation", () => {
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([{ deviceId: "device-1", cwd: "/repo/acme/roboco", branch: "feature/pr", checkoutId: null }]);

    deliver(client.watches[0]!, status({ changeRequest: summary({ provider: "gitlab" }) }));

    const snap = store.getSnapshot();
    expect(providerForCheckout(snap.providers, "device-1", "/repo/acme/roboco")).toBe("gitlab");
  });

  it("keeps the last known provider across sibling branches", () => {
    // Once the engine resolves `main` against GitLab, the store should still
    // hand the same provider back when we switch the active target to a
    // sibling branch whose lookup returns `changeRequest: null`.
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([{ deviceId: "device-1", cwd: "/repo/acme/roboco", branch: "main", checkoutId: null }]);
    deliver(client.watches[0]!, status({ branch: "main", changeRequest: summary({ provider: "gitlab", number: 1, headRef: "main" }) }));
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/acme/roboco")).toBe("gitlab");

    store.setTargets([{ deviceId: "device-1", cwd: "/repo/acme/roboco", branch: "feature/pr", checkoutId: null }]);
    const newWatch = client.watches[client.watches.length - 1]!;
    deliver(newWatch, status({ branch: "feature/pr", changeRequest: null }));

    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/acme/roboco")).toBe("gitlab");
  });

  it("returns null when no provider has been observed yet for the checkout", () => {
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([{ deviceId: "device-1", cwd: "/repo/acme/roboco", branch: "feature/pr", checkoutId: null }]);
    deliver(client.watches[0]!, status({ changeRequest: null }));
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/acme/roboco")).toBeNull();
  });

  it("isolates providers by checkout", () => {
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([
      { deviceId: "device-1", cwd: "/repo/a", branch: "feature/x", checkoutId: null },
      { deviceId: "device-1", cwd: "/repo/b", branch: "feature/y", checkoutId: null },
    ]);
    deliver(client.watches[0]!, status({ cwd: "/repo/a", branch: "feature/x", changeRequest: summary({ provider: "github", number: 1 }) }));
    deliver(client.watches[1]!, status({ cwd: "/repo/b", branch: "feature/y", changeRequest: summary({ provider: "bitbucket", number: 2 }) }));
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/a")).toBe("github");
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/b")).toBe("bitbucket");
  });

  it("ignores empty provider strings and still surfaces them as null", () => {
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([{ deviceId: "device-1", cwd: "/repo/a", branch: "feature/x", checkoutId: null }]);
    deliver(client.watches[0]!, status({ changeRequest: summary({ provider: "   " }) }));
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/a")).toBeNull();
  });

  it("clears providers on reset", () => {
    const client = new FakeClient();
    const store = new ChangeRequestStore(client);
    store.setTargets([{ deviceId: "device-1", cwd: "/repo/acme/roboco", branch: "feature/pr", checkoutId: null }]);
    deliver(client.watches[0]!, status({ changeRequest: summary({ provider: "github" }) }));
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/acme/roboco")).toBe("github");

    store.reset();
    expect(providerForCheckout(store.getSnapshot().providers, "device-1", "/repo/acme/roboco")).toBeNull();
  });
});

describe("checkoutKey", () => {
  it("uses a NUL separator so deviceId and cwd cannot collide", () => {
    expect(checkoutKey("a/b", "c")).not.toBe(checkoutKey("a", "b/c"));
    expect(checkoutKey("device-1", "/repo")).toBe("device-1\u0000/repo");
  });
});