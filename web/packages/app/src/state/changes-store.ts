import type { CheckoutDiff } from "@roboco/proto";
import type { EngineClient, WatchHandle } from "@roboco/engine-client";
import { methods, RpcError } from "@roboco/engine-client";
import { diffPhase, resolveDiff, scopeMode, type DiffPhase, type DiffScope } from "../lib/diff";

/**
 * The Changes store: the live working-tree diff set plus the per-chat scope
 * view (working tree / branch / latest turn) over a single EngineClient.
 *
 * The `WatchCheckoutDiffs` stream is the source of truth for the
 * working-tree scope; one-shot `GetCheckoutDiff` captures back the branch
 * and latest-turn scopes. The store resolves a per-chat diff through
 * `resolveDiff` (checkout id first, then device+cwd, then cwd) and keeps a
 * parse cache keyed on the diff checksum so re-renders are cheap.
 *
 * Same React-binding contract as the watch cache: `getSnapshot()` is
 * identity-stable until an actual change, `subscribe` fires once per change.
 */

export interface ScopedDiff {
  readonly diff: CheckoutDiff;
  readonly scope: DiffScope;
  readonly baseRef: string | null;
  readonly commitSha: string | null;
  /** Identifier for the (scope, base, commit) tuple — supersedes stale fetches. */
  readonly key: string;
}

export interface ChangesSnapshot {
  /** Every working-tree diff currently known to the engine. */
  readonly working: readonly CheckoutDiff[];
  /** The diff for the active (scope, base, commit) tuple, if any. */
  readonly scoped: ScopedDiff | null;
  /** Branches for the current chat's checkout (default branch first). */
  readonly branches: readonly string[];
  /** True once the watch has delivered its first item on this generation. */
  readonly watchLoaded: boolean;
  /** The working-tree diff resolved for the selected chat, or null. */
  readonly resolvedForChat: CheckoutDiff | null;
  /** The phase the resolved diff is in (preparing / clean / list). */
  readonly phase: DiffPhase;
  /** A terminal error from the watch or the last scoped capture. */
  readonly error: string | null;
  /** The connection generation these rows belong to. */
  readonly generation: number;
}

/** The watch surface the store needs — `EngineClient` satisfies it. */
export interface ChangesClient {
  call<T>(method: string, params?: unknown): Promise<T>;
  watch<T>(method: string, params: unknown, handlers: {
    onItem: (item: T, context: { generation: number }) => void;
    onEnd?: (error: RpcError | undefined) => void;
  }): WatchHandle;
}

interface Target {
  readonly checkoutId: string | null;
  readonly deviceId: string;
  readonly cwd: string | null;
  readonly chatId: string | null;
}

const EMPTY_BRANCHES: readonly string[] = [];

export class ChangesStore {
  readonly #client: ChangesClient;
  readonly #target: Target;
  readonly #log: (message: string, detail?: unknown) => void;
  #working: readonly CheckoutDiff[] = [];
  #scoped: ScopedDiff | null = null;
  #scopedKey: string | null = null;
  #scopedInflight: string | null = null;
  #branches: readonly string[] = EMPTY_BRANCHES;
  #branchesFor: string | null = null;
  #branchesInflight: string | null = null;
  #scope: DiffScope = "workingTree";
  #baseRef: string | null = null;
  #watchLoaded = false;
  #error: string | null = null;
  #generation = 0;
  #snapshot: ChangesSnapshot;
  #handle: WatchHandle | null = null;
  #disposed = false;

  constructor(client: EngineClient | ChangesClient, target: Target, options: { log?: (message: string, detail?: unknown) => void } = {}) {
    this.#client = client;
    this.#target = target;
    this.#log = options.log ?? (() => {});
    this.#snapshot = this.#takeSnapshot();
    this.#subscribe();
  }

  getSnapshot(): ChangesSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    const wrapped = (): void => listener();
    this.#listeners.add(wrapped);
    return () => {
      this.#listeners.delete(wrapped);
    };
  }

  /** Switch scope / base ref / commit. Clears cached captures when the key changes. */
  setScope(scope: DiffScope, baseRef?: string | null, commitSha?: string | null): void {
    const nextBase = baseRef ?? null;
    const nextSha = commitSha ?? null;
    if (this.#scope === scope && this.#baseRef === nextBase && nextSha === null) {
      return;
    }
    this.#scope = scope;
    this.#baseRef = nextBase;
    this.#scopedKey = null;
    if (scope === "workingTree") {
      this.#scoped = null;
    }
    this.#ensureBranches();
    this.#ensureScoped();
    this.#commit();
  }

  setBaseRef(base: string | null): void {
    if (this.#baseRef === base) {
      return;
    }
    this.#baseRef = base;
    this.#scopedKey = null;
    if (this.#scope === "branch") {
      this.#scoped = null;
    }
    this.#ensureScoped();
    this.#commit();
  }

  /**
   * Drop the watch + every in-flight call, then re-subscribe for a fresh
   * first item — the engine-side error recovery and the Retry affordance.
   */
  resubscribe(): void {
    if (this.#disposed) {
      return;
    }
    this.#handle?.cancel();
    this.#handle = null;
    this.#working = [];
    this.#scoped = null;
    this.#scopedKey = null;
    this.#scopedInflight = null;
    this.#watchLoaded = false;
    this.#error = null;
    this.#subscribe();
    this.#ensureBranches();
    this.#ensureScoped();
    this.#commit();
  }

  dispose(): void {
    if (this.#disposed) {
      return;
    }
    this.#disposed = true;
    this.#handle?.cancel();
    this.#handle = null;
    this.#listeners.clear();
  }

  #listeners = new Set<() => void>();

  #subscribe(): void {
    this.#handle = this.#client.watch<CheckoutDiff | CheckoutDiff[]>(methods.WATCH_CHECKOUT_DIFFS, {}, {
      onItem: (item, { generation }) => this.#onItem(item, generation),
      onEnd: (error) => this.#onEnd(error),
    });
  }

  #onItem(item: CheckoutDiff | CheckoutDiff[], generation: number): void {
    if (this.#disposed || generation < this.#generation) {
      return;
    }
    if (generation > this.#generation) {
      this.#generation = generation;
      this.#working = [];
      this.#scoped = null;
      this.#scopedKey = null;
      this.#scopedInflight = null;
      this.#branches = EMPTY_BRANCHES;
      this.#branchesFor = null;
      this.#branchesInflight = null;
      this.#watchLoaded = false;
    }
    if (Array.isArray(item)) {
      this.#working = item;
    } else {
      this.#upsertWorking(item);
    }
    this.#watchLoaded = true;
    this.#error = null;
    this.#ensureScoped();
    this.#commit();
  }

  #upsertWorking(one: CheckoutDiff): void {
    const ix = this.#working.findIndex((row) => row.checkoutId === one.checkoutId);
    if (ix < 0) {
      this.#working = [...this.#working, one];
      return;
    }
    if (this.#working[ix] === one) {
      return;
    }
    const next = this.#working.slice();
    next[ix] = one;
    this.#working = next;
  }

  #onEnd(error: RpcError | undefined): void {
    if (this.#disposed || error === undefined) {
      return;
    }
    this.#error = error.message;
    this.#commit();
  }

  #ensureBranches(): void {
    if (this.#target.cwd === null) {
      return;
    }
    const key = `${this.#target.deviceId}:${this.#target.cwd}`;
    if (this.#branchesFor === key || this.#branchesInflight === key) {
      return;
    }
    this.#branchesInflight = key;
    this.#client
      .call<string[]>(methods.LIST_BRANCHES, { repoPath: this.#target.cwd, targetDeviceId: this.#target.deviceId })
      .then((branches) => {
        if (this.#disposed || this.#branchesInflight !== key) {
          return;
        }
        this.#branchesInflight = null;
        this.#branchesFor = key;
        this.#branches = Array.isArray(branches) ? branches : [];
        if (this.#scope === "branch" && this.#baseRef === null && this.#branches.length > 0) {
          const first = this.#branches[0]!;
          if (first !== this.#baseRef) {
            this.#baseRef = first;
            this.#scopedKey = null;
            this.#ensureScoped();
          }
        }
        this.#commit();
      })
      .catch((error: unknown) => {
        if (this.#disposed || this.#branchesInflight !== key) {
          return;
        }
        this.#branchesInflight = null;
        this.#log("changes: list branches failed", describeError(error));
        this.#commit();
      });
  }

  #ensureScoped(): void {
    if (this.#scope === "workingTree") {
      this.#scoped = null;
      this.#scopedKey = null;
      this.#scopedInflight = null;
      return;
    }
    if (this.#scope === "branch" && this.#baseRef === null) {
      this.#scoped = null;
      return;
    }
    const key = `${this.#scope}:${this.#baseRef ?? ""}:${this.#target.cwd ?? ""}:${this.#target.chatId ?? ""}`;
    if (this.#scopedKey === key || this.#scopedInflight === key) {
      return;
    }
    if (
      this.#scoped !== null &&
      this.#scoped.key === key &&
      this.#scoped.scope === this.#scope &&
      this.#scoped.baseRef === this.#baseRef
    ) {
      return;
    }
    this.#scopedInflight = key;
    const params: Record<string, unknown> = {
      cwd: this.#target.cwd ?? "",
      mode: scopeMode(this.#scope),
      targetDeviceId: this.#target.deviceId,
    };
    if (this.#baseRef !== null) {
      params.baseRef = this.#baseRef;
    }
    if (this.#target.chatId !== null) {
      params.chatId = this.#target.chatId;
    }
    this.#client
      .call<CheckoutDiff>(methods.GET_CHECKOUT_DIFF, params)
      .then((diff) => {
        if (this.#disposed || this.#scopedInflight !== key) {
          return;
        }
        this.#scopedInflight = null;
        this.#scopedKey = key;
        this.#scoped = { diff, scope: this.#scope, baseRef: this.#baseRef, commitSha: null, key };
        this.#commit();
      })
      .catch((error: unknown) => {
        if (this.#disposed || this.#scopedInflight !== key) {
          return;
        }
        this.#scopedInflight = null;
        this.#error = describeError(error);
        this.#commit();
      });
  }

  #takeSnapshot(): ChangesSnapshot {
    const chat = {
      checkoutId: this.#target.checkoutId,
      deviceId: this.#target.deviceId,
      cwd: this.#target.cwd,
    };
    const resolved = resolveDiff(this.#working, chat);
    return {
      working: this.#working,
      scoped: this.#scoped,
      branches: this.#branches,
      watchLoaded: this.#watchLoaded,
      resolvedForChat: resolved,
      phase: diffPhase(resolved),
      error: this.#error,
      generation: this.#generation,
    };
  }

  #commit(): void {
    this.#snapshot = this.#takeSnapshot();
    for (const listener of this.#listeners) {
      try {
        listener();
      } catch (error) {
        this.#log("changes store listener threw", describeError(error));
      }
    }
  }
}

function describeError(error: unknown): string {
  if (error instanceof RpcError) {
    if (error.kind === "transport") {
      return "Engine is offline; reconnecting";
    }
    return error.message;
  }
  return error instanceof Error ? error.message : String(error);
}
