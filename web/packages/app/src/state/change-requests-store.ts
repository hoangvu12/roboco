import type { ChangeRequestSummary, CheckoutChangeRequestStatus } from "@roboco/proto";
import type { EngineClient, WatchHandle } from "@roboco/engine-client";
import { methods, RpcError } from "@roboco/engine-client";

/**
 * Per-checkout change-request state for the web client — the web peer of
 * `crates/ui/src/change_requests.rs::ChangeRequestClientState` minus the
 * legacy `source_context`-less fallbacks. Each (device, cwd, branch) tuple
 * subscribes to `WatchCheckoutChangeRequest`; the latest successful snapshot
 * per tuple stays alive until a fresh `change_request: None` clears it
 * (the wire's authoritative successful lookup with no match, see
 * `CheckoutChangeRequestStatus`).
 *
 * A device whose engine rejects the versioned capability (the older-engine
 * `unknown method` path) is recorded as unsupported and never re-subscribed
 * until its version changes — matching the desktop's behavior so a parked
 * `older engine` does not generate a stream error every cycle.
 */

export interface ChangeRequestTarget {
  readonly deviceId: string;
  readonly cwd: string;
  readonly branch: string;
  readonly checkoutId: string | null;
}

export interface ChangeRequestSnapshot {
  readonly supported: boolean;
  /** The latest successful snapshot per target, keyed by target identity. */
  readonly snapshots: ReadonlyMap<string, CheckoutChangeRequestStatus>;
  /** The most recent unsupported rejection per device, if any. */
  readonly unsupported: ReadonlyMap<string, string>;
  /** Generation of the most recent item applied (for React binding). */
  readonly generation: number;
}

/**
 * The visible change request for a chat. `null` when none has been observed
 * yet or the branch/cwd pair was never resolved.
 */
export function changeRequestForChat(
  snapshots: ReadonlyMap<string, CheckoutChangeRequestStatus>,
  target: ChangeRequestTarget,
): ChangeRequestSummary | null {
  const snapshot = snapshots.get(keyOf(target));
  return snapshot?.changeRequest ?? null;
}

/** Identity for a (device, cwd, branch) tuple. */
export function keyOf(target: ChangeRequestTarget): string {
  return `${target.deviceId}\u0000${target.cwd}\u0000${target.branch}`;
}

export interface ChangeRequestsClient {
  call<T>(method: string, params?: unknown): Promise<T>;
  watch<T>(method: string, params: unknown, handlers: {
    onItem: (item: T, context: { generation: number }) => void;
    onEnd?: (error: RpcError | undefined) => void;
  }): WatchHandle;
}

interface WatchRecord {
  readonly target: ChangeRequestTarget;
  readonly handle: WatchHandle;
  /** True while the stream is still pending its first item. */
  inflight: boolean;
}

export class ChangeRequestStore {
  readonly #client: ChangeRequestsClient;
  readonly #log: (message: string, detail?: unknown) => void;
  #snapshots: Map<string, CheckoutChangeRequestStatus> = new Map();
  #unsupported: Map<string, string> = new Map();
  #targets: Map<string, ChangeRequestTarget> = new Map();
  #watches: Map<string, WatchRecord> = new Map();
  #snapshot: ChangeRequestSnapshot;
  #generation = 0;
  #disposed = false;
  readonly #listeners = new Set<() => void>();

  constructor(client: EngineClient | ChangeRequestsClient, options: { log?: (message: string, detail?: unknown) => void } = {}) {
    this.#client = client;
    this.#log = options.log ?? (() => {});
    this.#snapshot = this.#takeSnapshot();
  }

  getSnapshot(): ChangeRequestSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * Bring the active target set in line with the given list. Each target
   * gets its own `WatchCheckoutChangeRequest` subscription; targets that
   * stop being requested drop their subscriptions and snapshots.
   */
  setTargets(targets: readonly ChangeRequestTarget[]): void {
    if (this.#disposed) {
      return;
    }
    const next = new Map<string, ChangeRequestTarget>();
    for (const target of targets) {
      if (target.branch.trim().length === 0) {
        continue;
      }
      if (this.#unsupported.has(target.deviceId)) {
        continue;
      }
      const key = keyOf(target);
      if (next.has(key)) {
        continue;
      }
      next.set(key, target);
      this.#targets.set(key, target);
    }
    for (const [key, record] of this.#watches) {
      if (!next.has(key)) {
        record.handle.cancel();
        this.#watches.delete(key);
        this.#snapshots.delete(key);
      }
    }
    for (const [key, target] of next) {
      if (this.#watches.has(key)) {
        continue;
      }
      this.#startWatch(key, target);
    }
    this.#commit();
  }

  /** Forget an unsupported device's negative cache (a new engine version). */
  clearUnsupportedOnVersionChange(deviceId: string, version: string | null): void {
    const current = this.#unsupported.get(deviceId);
    if (current === undefined) {
      return;
    }
    if (current === (version ?? "")) {
      return;
    }
    this.#unsupported.delete(deviceId);
    this.#commit();
  }

  /** Drop every subscription and snapshot (pair the engine again etc.). */
  reset(): void {
    if (this.#disposed) {
      return;
    }
    for (const record of this.#watches.values()) {
      record.handle.cancel();
    }
    this.#watches.clear();
    this.#targets.clear();
    this.#snapshots.clear();
    this.#unsupported.clear();
    this.#commit();
  }

  dispose(): void {
    if (this.#disposed) {
      return;
    }
    this.#disposed = true;
    for (const record of this.#watches.values()) {
      record.handle.cancel();
    }
    this.#watches.clear();
    this.#listeners.clear();
  }

  #startWatch(key: string, target: ChangeRequestTarget): void {
    const record: WatchRecord = {
      target,
      handle: this.#client.watch<CheckoutChangeRequestStatus | { ok: unknown } | unknown>(
        methods.WATCH_CHECKOUT_CHANGE_REQUEST,
        watchParams(target),
        {
          onItem: (item, ctx) => this.#onItem(key, target, item, ctx.generation),
          onEnd: (error) => this.#onEnd(key, target, error),
        },
      ),
      inflight: true,
    };
    this.#watches.set(key, record);
  }

  #onItem(key: string, target: ChangeRequestTarget, item: unknown, generation: number): void {
    if (this.#disposed) {
      return;
    }
    if (generation < this.#generation) {
      return;
    }
    if (isStreamAckValue(item)) {
      const record = this.#watches.get(key);
      if (record !== undefined) {
        record.inflight = false;
      }
      return;
    }
    if (typeof item !== "object" || item === null) {
      return;
    }
    const status = item as CheckoutChangeRequestStatus;
    if (typeof status.checkoutId !== "string" || typeof status.cwd !== "string" || typeof status.branch !== "string") {
      return;
    }
    if (status.deviceId !== target.deviceId || status.cwd !== target.cwd || status.branch !== target.branch) {
      return;
    }
    this.#generation = generation;
    this.#snapshots.set(key, status);
    const record = this.#watches.get(key);
    if (record !== undefined) {
      record.inflight = false;
    }
    this.#commit();
  }

  #onEnd(key: string, target: ChangeRequestTarget, error: RpcError | undefined): void {
    if (this.#disposed || error === undefined) {
      return;
    }
    // Older engines don't implement WatchCheckoutChangeRequest; cache the
    // version so we don't keep retrying, but otherwise treat the device as
    // supported (the desktop's negative-cache invariant).
    const detail = error.message;
    if (error.kind === "unknown-method" || /unknown method/i.test(detail)) {
      this.#unsupported.set(target.deviceId, "");
      const record = this.#watches.get(key);
      if (record !== undefined) {
        record.handle.cancel();
        this.#watches.delete(key);
        this.#snapshots.delete(key);
      }
      this.#log("change requests: device unsupported", { deviceId: target.deviceId });
      this.#commit();
      return;
    }
    this.#log("change requests: stream ended", { key, detail });
    // For other errors the engine will re-deliver on next reconnect.
    this.#commit();
  }

  #takeSnapshot(): ChangeRequestSnapshot {
    return {
      supported: this.#unsupported.size === 0,
      snapshots: this.#snapshots,
      unsupported: this.#unsupported,
      generation: this.#generation,
    };
  }

  #commit(): void {
    const next = this.#takeSnapshot();
    if (next.snapshots === this.#snapshot.snapshots && next.unsupported === this.#snapshot.unsupported) {
      return;
    }
    this.#snapshot = next;
    for (const listener of this.#listeners) {
      try {
        listener();
      } catch (error) {
        this.#log("change request listener threw", error);
      }
    }
  }
}

function watchParams(target: ChangeRequestTarget): Record<string, unknown> {
  return {
    cwd: target.cwd,
    branch: target.branch,
    targetDeviceId: target.deviceId,
  };
}

/**
 * The stream readiness ack — `{ok: {stream: true}}` arrives as a bare
 * object value before any data item. It is an ack, not data, so the store
 * drops it and waits for the first real snapshot.
 */
function isStreamAckValue(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  return (value as Record<string, unknown>).stream === true;
}
