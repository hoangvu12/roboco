import type { HarnessDescriptor, HarnessId, Model } from "@roboco/proto";
import { methods, RpcError } from "@roboco/engine-client";
import type { EngineClient } from "@roboco/engine-client";

/**
 * The pickers' data catalog — one per `EngineSession`. Lists harnesses once
 * for the composer (its picker chip row drives all four pickers) and the
 * model catalog per picked harness, cached until invalidated. The engine
 * answers both with unary calls; missing methods degrade the catalog to
 * empty so older engines fail closed instead of crash the pickers.
 *
 * React binding contract (mirrors the watch cache): `getSnapshot` /
 * `subscribe` are identity-stable until an actual change.
 */

export interface LoadableList<T> {
  readonly rows: readonly T[];
  readonly loaded: boolean;
  readonly error: string | null;
  /** A fetch is in flight on the current generation. */
  readonly loading: boolean;
  /** Bumped per fetch attempt; React keys off it for refresh-on-focus. */
  readonly generation: number;
}

const EMPTY_HARNESSES: readonly HarnessDescriptor[] = [];
const EMPTY_MODELS: readonly Model[] = [];

function emptyList<T>(): LoadableList<T> {
  return { rows: [], loaded: false, error: null, loading: false, generation: 0 };
}

function listWithRows<T>(prev: LoadableList<T>, rows: readonly T[], generation: number): LoadableList<T> {
  return { rows, loaded: true, error: null, loading: false, generation };
}

function listWithError<T>(prev: LoadableList<T>, message: string): LoadableList<T> {
  return { rows: prev.rows, loaded: prev.loaded, error: message, loading: false, generation: prev.generation };
}

function listWithLoading<T>(prev: LoadableList<T>): LoadableList<T> {
  return { rows: prev.rows, loaded: prev.loaded, error: null, loading: true, generation: prev.generation + 1 };
}

function isUnknownMethod(error: RpcError): boolean {
  return error.kind === "unknown-method" || /unknown method/i.test(error.message);
}

export interface PickerCatalogOptions {
  readonly log?: (message: string, detail?: unknown) => void;
}

export class PickerCatalog {
  readonly #client: EngineClient;
  readonly #log: (message: string, detail?: unknown) => void;

  #harnesses: LoadableList<HarnessDescriptor> = emptyList<HarnessDescriptor>();
  readonly #models = new Map<HarnessId, LoadableList<Model>>();
  readonly #listeners = new Set<() => void>();
  readonly #modelListeners = new Map<HarnessId, Set<() => void>>();
  #disposed = false;

  constructor(client: EngineClient, options: PickerCatalogOptions = {}) {
    this.#client = client;
    this.#log = options.log ?? (() => {});
  }

  /** Identity-stable harness list. Call `loadHarnesses` to refresh. */
  getHarnesses(): LoadableList<HarnessDescriptor> {
    return this.#harnesses;
  }

  /** Identity-stable model list for the picked harness. */
  getModels(harness: HarnessId): LoadableList<Model> {
    return this.#models.get(harness) ?? emptyList<Model>();
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  subscribeModels(harness: HarnessId, listener: () => void): () => void {
    let set = this.#modelListeners.get(harness);
    if (set === undefined) {
      set = new Set();
      this.#modelListeners.set(harness, set);
    }
    set.add(listener);
    return () => {
      set!.delete(listener);
      if (set!.size === 0) {
        this.#modelListeners.delete(harness);
      }
    };
  }

  /** Fire one harness fetch (no-op while one is in flight or already loaded). */
  async loadHarnesses(): Promise<void> {
    if (this.#disposed) {
      return;
    }
    if (this.#harnesses.loading || this.#harnesses.loaded) {
      return;
    }
    const generation = this.#harnesses.generation + 1;
    this.#harnesses = listWithLoading(this.#harnesses);
    this.#commitHarnesses();
    try {
      const rows = await this.#client.call<HarnessDescriptor[]>(methods.LIST_HARNESSES, {});
      if (this.#disposed || this.#harnesses.generation !== generation) {
        return;
      }
      const arr = Array.isArray(rows) ? rows : [];
      this.#harnesses = listWithRows(this.#harnesses, arr, generation);
      this.#commitHarnesses();
    } catch (error) {
      if (this.#disposed || this.#harnesses.generation !== generation) {
        return;
      }
      const rpcError = error instanceof RpcError ? error : new RpcError("transport", String(error));
      if (isUnknownMethod(rpcError)) {
        // Older engine: degrade to an empty catalog (the pickers stay inert).
        this.#harnesses = listWithRows(this.#harnesses, EMPTY_HARNESSES, generation);
        this.#commitHarnesses();
        return;
      }
      this.#harnesses = listWithError(this.#harnesses, rpcError.message);
      this.#commitHarnesses();
    }
  }

  /** Fire one model fetch for `harness`; cached per harness until invalidated. */
  async loadModels(harness: HarnessId): Promise<void> {
    if (this.#disposed) {
      return;
    }
    const current = this.getModels(harness);
    if (current.loading || current.loaded) {
      return;
    }
    const generation = current.generation + 1;
    this.#models.set(harness, listWithLoading(current));
    this.#commitModels(harness);
    try {
      const rows = await this.#client.call<Model[]>(methods.LIST_MODELS, { harness });
      if (this.#disposed) {
        return;
      }
      const live = this.getModels(harness);
      if (live.generation !== generation) {
        return;
      }
      const arr = Array.isArray(rows) ? rows : [];
      this.#models.set(harness, listWithRows(live, arr, generation));
      this.#commitModels(harness);
    } catch (error) {
      if (this.#disposed) {
        return;
      }
      const live = this.getModels(harness);
      if (live.generation !== generation) {
        return;
      }
      const rpcError = error instanceof RpcError ? error : new RpcError("transport", String(error));
      if (isUnknownMethod(rpcError)) {
        this.#models.set(harness, listWithRows(live, EMPTY_MODELS, generation));
        this.#commitModels(harness);
        return;
      }
      this.#models.set(harness, listWithError(live, rpcError.message));
      this.#commitModels(harness);
    }
  }

  /** Forget the harness + every model catalog (a fresh chat deserves fresh defaults). */
  invalidate(): void {
    this.#harnesses = emptyList<HarnessDescriptor>();
    this.#models.clear();
    this.#commitHarnesses();
    for (const harness of [...this.#modelListeners.keys()]) {
      this.#commitModels(harness);
    }
  }

  dispose(): void {
    if (this.#disposed) {
      return;
    }
    this.#disposed = true;
    this.#listeners.clear();
    this.#modelListeners.clear();
    this.#models.clear();
  }

  #commitHarnesses(): void {
    for (const listener of this.#listeners) {
      try {
        listener();
      } catch (error) {
        this.#log("picker catalog listener threw", describeError(error));
      }
    }
  }

  #commitModels(harness: HarnessId): void {
    const set = this.#modelListeners.get(harness);
    if (set === undefined) {
      return;
    }
    for (const listener of set) {
      try {
        listener();
      } catch (error) {
        this.#log("picker catalog listener threw", describeError(error));
      }
    }
  }
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
