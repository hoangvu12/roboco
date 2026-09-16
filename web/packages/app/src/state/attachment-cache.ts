import { useSyncExternalStore } from "react";
import { readAttachmentImage } from "../lib/attachments";

/**
 * The transcript's attachment image cache — the web peer of
 * `crates/ui/src/transcript-attachment-cache.ts`. Decoded images, keyed by
 * `(deviceId, path)`, are seeded once and replayed across renders. Loads
 * are kicked via `beginLoad` (so a row mounts without a race); results
 * arrive through a per-key `Promise` and notify subscribers on commit.
 *
 * Errors back off with the desktop's 2s→15s ladder — the row paints a
 * skeleton and a small "retrying…" line while it waits.
 */

interface CacheEntry {
  state: "loading" | "loaded" | "error";
  attempts: number;
  image?: { name: string; mime: string; bytes: Uint8Array };
  retryAt?: number;
}

export interface AttachmentImageSnapshot {
  state: "loading" | "loaded" | "error";
  image: { name: string; mime: string; bytes: Uint8Array } | null;
  /** Milliseconds until another load is attempted (0 when no backoff). */
  retryIn: number;
}

function retryDelayMs(attempts: number): number {
  return Math.min(2000 << Math.min(attempts - 1, 3), 15000);
}

function emptySnapshot(): AttachmentImageSnapshot {
  return { state: "loading", image: null, retryIn: 0 };
}

/** Module-level so a freshly-mounted row picks up the seed from a prior
 *  send (the desktop's `seed_attachment` call). */
const cache = new Map<string, CacheEntry>();
const listeners = new Map<string, Set<() => void>>();

function keyOf(deviceId: string, path: string): string {
  return `${deviceId}\0${path}`;
}

function notify(key: string): void {
  const set = listeners.get(key);
  if (set === undefined) {
    return;
  }
  for (const listener of set) {
    listener();
  }
}

/** Seed the cache after a successful upload so the just-sent bubble's
 *  thumbnail renders from local bytes instead of round-tripping the host.
 *  Mirrors `seed_attachment` (state.rs / attachments.rs). */
export function seedAttachment(
  deviceId: string,
  path: string,
  image: { name: string; mime: string; bytes: Uint8Array },
): void {
  const key = keyOf(deviceId, path);
  cache.set(key, { state: "loaded", attempts: 0, image });
  notify(key);
}

/** Read the current snapshot for a (deviceId, path) tuple. Returns a
 *  snapshot-stable object: identity tracks the entry's state, so React's
 *  re-render only fires when something actually changed. */
export function getAttachmentSnapshot(
  deviceId: string,
  path: string,
): AttachmentImageSnapshot {
  const key = keyOf(deviceId, path);
  const entry = cache.get(key);
  if (entry === undefined) {
    return emptySnapshot();
  }
  if (entry.state === "loading") {
    return emptySnapshot();
  }
  if (entry.state === "loaded" && entry.image !== undefined) {
    return { state: "loaded", image: entry.image, retryIn: 0 };
  }
  const now = Date.now();
  const retryIn = entry.retryAt !== undefined ? Math.max(0, entry.retryAt - now) : 0;
  return { state: "error", image: null, retryIn };
}

/** Subscribe to changes for a (deviceId, path) tuple. Returns the
 *  unsubscribe; pass it to `useSyncExternalStore`. */
export function subscribeAttachment(
  deviceId: string,
  path: string,
  listener: () => void,
): () => void {
  const key = keyOf(deviceId, path);
  let set = listeners.get(key);
  if (set === undefined) {
    set = new Set();
    listeners.set(key, set);
  }
  set.add(listener);
  return () => {
    const current = listeners.get(key);
    if (current === undefined) {
      return;
    }
    current.delete(listener);
    if (current.size === 0) {
      listeners.delete(key);
    }
  };
}

/** React hook: snapshot + subscription. Pass `null` for `path` when the
 *  row has no attachment; the hook returns a stable empty snapshot. */
export function useAttachmentImage(
  deviceId: string | null,
  path: string | null,
): AttachmentImageSnapshot {
  const subscribe = (listener: () => void): (() => void) => {
    if (deviceId === null || path === null) {
      return () => {};
    }
    return subscribeAttachment(deviceId, path, listener);
  };
  const getSnapshot = (): AttachmentImageSnapshot => {
    if (deviceId === null || path === null) {
      return emptySnapshot();
    }
    return getAttachmentSnapshot(deviceId, path);
  };
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** Claim the load for a (deviceId, path) tuple. Returns `true` iff the
 *  caller should start fetching now (so concurrent renders don't
 *  double-fetch). Errors back off with a 2s→15s ladder. */
export function beginAttachmentLoad(deviceId: string, path: string): boolean {
  const key = keyOf(deviceId, path);
  const entry = cache.get(key);
  if (entry === undefined) {
    cache.set(key, { state: "loading", attempts: 0 });
    notify(key);
    return true;
  }
  if (entry.state === "loaded") {
    return false;
  }
  if (entry.state === "error") {
    const now = Date.now();
    if (entry.retryAt !== undefined && now < entry.retryAt) {
      return false;
    }
    cache.set(key, { state: "loading", attempts: entry.attempts });
    notify(key);
    return true;
  }
  return false;
}

/** Mark an entry as errored with the next retry time. */
export function storeAttachmentError(deviceId: string, path: string): void {
  const key = keyOf(deviceId, path);
  const entry = cache.get(key);
  const attempts = (entry?.attempts ?? 0) + 1;
  const delay = retryDelayMs(attempts);
  cache.set(key, { state: "error", attempts, retryAt: Date.now() + delay });
  notify(key);
}

/** Convenience: kick a load for a single source. Returns a Promise that
 *  resolves when the entry settles (loaded or errored). */
export async function loadAttachment(
  client: { call(method: string, params: unknown): Promise<unknown> },
  deviceId: string,
  path: string,
): Promise<void> {
  if (!beginAttachmentLoad(deviceId, path)) {
    return;
  }
  const image = await readAttachmentImage(client, path);
  if (image === null) {
    storeAttachmentError(deviceId, path);
    return;
  }
  cache.set(keyOf(deviceId, path), { state: "loaded", attempts: 0, image });
  notify(keyOf(deviceId, path));
}

/** Test-only escape hatch. Clears the module-level cache so each test
 *  starts from a clean slate. */
export function __resetAttachmentCacheForTests(): void {
  cache.clear();
  listeners.clear();
}