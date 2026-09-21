import type { ContextUsage, SessionMessageEntry, TranscriptFrame, TranscriptUpdate } from "@roboco/proto";
import type { EngineClient, WatchHandle } from "@roboco/engine-client";
import { methods, RpcError } from "@roboco/engine-client";
import { applyTranscriptFrame, TranscriptDesync } from "../lib/transcript";

/**
 * One chat's live transcript — the store behind the transcript view. Subscribes
 * `WatchDocMessages {chatId}` (which also serves subagent docs: the doc id is
 * the parameter, so the same store backs the subagent dialog), applies
 * reset/delta frames through the ported `applyTranscriptFrame`, and resubscribes
 * for a fresh reset when the desync tripwire fires.
 *
 * Same React-binding contract as the watch cache: `getSnapshot()` is
 * identity-stable until an actual change, `subscribe` fires once per change.
 */

export interface TranscriptSnapshot {
  /** The transcript entries in document order (immutable, identity-preserving). */
  readonly entries: readonly SessionMessageEntry[];
  /** The host-owned context snapshot riding the stream (null on older engines). */
  readonly contextUsage: ContextUsage | null;
  /** A first frame has arrived on the current stream. */
  readonly loaded: boolean;
  /** The last entry is streaming (drives the live-end anchor). */
  readonly streaming: boolean;
  /** Terminal stream error on the current generation (Retry re-subscribes). */
  readonly error: string | null;
  /** The connection generation these rows belong to. */
  readonly generation: number;
}

const EMPTY_ENTRIES: readonly SessionMessageEntry[] = [];

/** The watch surface the store needs — `EngineClient` satisfies it. */
export interface TranscriptClient {
  watch<T>(method: string, params: unknown, handlers: {
    onItem: (item: T, context: { generation: number }) => void;
    onEnd?: (error: RpcError | undefined) => void;
  }): WatchHandle;
}

export class TranscriptStore {
  readonly #client: TranscriptClient;
  readonly #docId: string;
  readonly #log: (message: string, detail?: unknown) => void;
  #entries: readonly SessionMessageEntry[] = EMPTY_ENTRIES;
  #contextUsage: ContextUsage | null = null;
  #loaded = false;
  #error: string | null = null;
  #generation = 0;
  #snapshot: TranscriptSnapshot;
  #handle: WatchHandle | null = null;
  readonly #listeners = new Set<() => void>();
  #disposed = false;

  constructor(client: EngineClient | TranscriptClient, docId: string, options: { log?: (message: string, detail?: unknown) => void } = {}) {
    this.#client = client;
    this.#docId = docId;
    this.#log = options.log ?? (() => {});
    this.#snapshot = this.#takeSnapshot();
    this.#subscribe();
  }

  /** The doc this store watches (a chat id, or a subagent doc id). */
  get docId(): string {
    return this.#docId;
  }

  getSnapshot(): TranscriptSnapshot {
    return this.#snapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * Drop the stream and re-subscribe for a fresh reset — the desync recovery
   * and the Retry affordance. Frame state resets with the stream, mirroring
   * the watch cache's generation swap.
   */
  resubscribe(): void {
    if (this.#disposed) {
      return;
    }
    this.#handle?.cancel();
    this.#handle = null;
    this.#entries = EMPTY_ENTRIES;
    this.#contextUsage = null;
    this.#loaded = false;
    this.#error = null;
    this.#subscribe();
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

  #subscribe(): void {
    this.#handle = this.#client.watch<TranscriptUpdate>(methods.WATCH_DOC_MESSAGES, { chatId: this.#docId }, {
      onItem: (item, { generation }) => this.#onItem(item, generation),
      onEnd: (error) => this.#onEnd(error),
    });
  }

  #onItem(update: TranscriptUpdate, generation: number): void {
    if (this.#disposed || generation < this.#generation) {
      // A stale stream from before a reconnect must not re-apply old frames.
      return;
    }
    if (generation > this.#generation) {
      // New connection generation: the stream re-sends a full reset first, so
      // adopting it here only guards a misbehaving peer.
      this.#generation = generation;
      this.#entries = EMPTY_ENTRIES;
      this.#loaded = false;
      this.#error = null;
    }
    const frame = asFrame(update);
    if (frame === null) {
      this.#log("dropped malformed transcript frame", update);
      return;
    }
    try {
      this.#entries = applyTranscriptFrame(this.#entries, frame);
    } catch (error) {
      if (error instanceof TranscriptDesync) {
        this.#log("transcript desync; resubscribing for a reset", error.message);
        this.resubscribe();
        return;
      }
      throw error;
    }
    if (update.contextUsage !== undefined) {
      this.#contextUsage = update.contextUsage;
    }
    this.#loaded = true;
    this.#commit();
  }

  #onEnd(error: RpcError | undefined): void {
    if (this.#disposed || error === undefined) {
      return;
    }
    this.#error = error.message;
    this.#commit();
  }

  #takeSnapshot(): TranscriptSnapshot {
    const last = this.#entries[this.#entries.length - 1];
    return {
      entries: this.#entries,
      contextUsage: this.#contextUsage,
      loaded: this.#loaded,
      streaming: last?.status === "streaming",
      error: this.#error,
      generation: this.#generation,
    };
  }

  #commit(): void {
    this.#snapshot = this.#takeSnapshot();
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

/** Split a `TranscriptUpdate` into its frame, tolerating malformed items. */
function asFrame(update: TranscriptUpdate): TranscriptFrame | null {
  if (typeof update !== "object" || update === null) {
    return null;
  }
  if ("reset" in update) {
    return Array.isArray(update.reset) ? { reset: update.reset } : null;
  }
  const delta = update as { upsert?: unknown; append?: unknown; remove?: unknown; count?: unknown };
  if (typeof delta.count !== "number") {
    return null;
  }
  return {
    upsert: Array.isArray(delta.upsert) ? delta.upsert : [],
    append: Array.isArray(delta.append) ? delta.append : [],
    remove: Array.isArray(delta.remove) ? delta.remove : [],
    count: delta.count,
  };
}
