/**
 * The new-thread background image's durable home. The desktop copies the
 * chosen file into `{data_dir}/new-thread-backgrounds/` and points the
 * settings field at that path (settings.rs:305-362); a browser has no
 * filesystem, so the managed copy is an IndexedDB blob under one fixed key
 * and the settings field's `path` is that key (`idb:new-thread-composer-
 * background`) — the ticket's "object URL / IndexedDB blob" substitute.
 *
 * `url()` hands out a session-scoped object URL (a stable string the <img>
 * can consume), revoking the previous URL whenever the blob is replaced or
 * removed — the desktop's managed-file retirement.
 */

const DB_NAME = "roboco-settings";
const DB_VERSION = 1;
const STORE = "blobs";
export const NEW_THREAD_BACKGROUND_KEY = "new-thread-composer-background";

export interface BackgroundBlobStore {
  /** Overwrite the managed blob; any previous URL is retired. */
  put(blob: Blob): Promise<void>;
  /** The blob's object URL, or null when nothing (or only a broken entry) is stored. */
  url(): Promise<string | null>;
  /** Retire the blob and its URL. */
  delete(): Promise<void>;
}

/**
 * The wallpaper shuffle pool (upstream #598's folder, web-shaped): the chosen
 * images live as blobs under `wallpaper-<id>` keys in the same store, and the
 * settings snapshot holds the key list. Pool entries get their own cached
 * object URLs so a key's artwork identity is stable across shuffles — the
 * preloaded effect rasters key on it.
 */
export interface WallpaperPoolStore {
  /** Replace the whole pool with the given files; returns the new key list. */
  putAll(files: readonly File[]): Promise<readonly string[]>;
  /** The stored key list, oldest first (or null when no pool was ever set). */
  list(): Promise<readonly string[] | null>;
  /** A pool key's cached object URL, or null when the entry no longer decodes. */
  url(key: string): Promise<string | null>;
  /** Remove every pool entry. */
  clear(): Promise<void>;
}

const WALLPAPER_POOL_PREFIX = "wallpaper-";

function newPoolKey(): string {
  const random =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${WALLPAPER_POOL_PREFIX}${random}`;
}

function poolRange(): IDBKeyRange | null {
  // Bounded scan over the pool's keys; IDBKeyRange is available wherever
  // indexedDB is.
  return typeof IDBKeyRange === "undefined"
    ? null
    : IDBKeyRange.bound(WALLPAPER_POOL_PREFIX, `${WALLPAPER_POOL_PREFIX}\uffff`);
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) {
        request.result.createObjectStore(STORE);
      }
    };
    request.onsuccess = () => {
      resolve(request.result);
    };
    request.onerror = () => {
      reject(request.error ?? new Error("could not open the settings database"));
    };
  });
}

function requestToPromise<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => {
      resolve(request.result);
    };
    request.onerror = () => {
      reject(request.error ?? new Error("the settings database request failed"));
    };
  });
}

/**
 * The ONE process-wide store (ticket 35, gap G19): every background
 * resolution shares a single `cachedUrl`, so the object URL minted for a
 * blob revision — hence the artwork's identity — is stable across every
 * mount, and a put/delete retires it exactly once. Before ticket 35 each
 * call returned a fresh closure with its own cache, so every resolve minted
 * a NEW blob: URL nobody ever revoked: the artwork's id changed per mount,
 * the keyed readiness wrapper remounted, and the 120 ms fade replayed.
 */
export function idbBackgroundBlobStore(): BackgroundBlobStore {
  if (singletonBlobStore === null) {
    singletonBlobStore =
      typeof indexedDB === "undefined" ? memoryBackgroundBlobStore() : createIdbBackgroundBlobStore();
  }
  return singletonBlobStore;
}

let singletonBlobStore: BackgroundBlobStore | null = null;

/** The real store; a memory stand-in when IndexedDB is unavailable (tests). */
function createIdbBackgroundBlobStore(): BackgroundBlobStore {
  let cachedUrl: string | null = null;
  const revoke = (): void => {
    if (cachedUrl !== null) {
      URL.revokeObjectURL(cachedUrl);
      cachedUrl = null;
    }
  };
  return {
    async put(blob: Blob): Promise<void> {
      const database = await openDatabase();
      try {
        await requestToPromise(database.transaction(STORE, "readwrite").objectStore(STORE).put(blob, NEW_THREAD_BACKGROUND_KEY));
      } finally {
        database.close();
      }
      revoke();
    },
    async url(): Promise<string | null> {
      const database = await openDatabase();
      let blob: unknown;
      try {
        blob = await requestToPromise(database.transaction(STORE, "readonly").objectStore(STORE).get(NEW_THREAD_BACKGROUND_KEY));
      } finally {
        database.close();
      }
      if (!(blob instanceof Blob)) {
        revoke();
        return null;
      }
      if (cachedUrl === null) {
        cachedUrl = URL.createObjectURL(blob);
      }
      return cachedUrl;
    },
    async delete(): Promise<void> {
      const database = await openDatabase();
      try {
        await requestToPromise(database.transaction(STORE, "readwrite").objectStore(STORE).delete(NEW_THREAD_BACKGROUND_KEY));
      } finally {
        database.close();
      }
      revoke();
    },
  };
}

/**
 * The process-wide pool store (same singleton discipline as
 * `idbBackgroundBlobStore`): one cached object URL per pool key, so every
 * resolution — the shuffle's preload, the hero, the settings row — shares one
 * artwork identity per key.
 */
export function idbWallpaperPoolStore(): WallpaperPoolStore {
  if (singletonPoolStore === null) {
    singletonPoolStore =
      typeof indexedDB === "undefined" ? memoryWallpaperPoolStore() : createIdbWallpaperPoolStore();
  }
  return singletonPoolStore;
}

let singletonPoolStore: WallpaperPoolStore | null = null;

const poolUrlCache = new Map<string, string>();

function createIdbWallpaperPoolStore(): WallpaperPoolStore {
  const listKeys = async (): Promise<string[]> => {
    const database = await openDatabase();
    try {
      const range = poolRange();
      if (range === null) {
        return [];
      }
      return (await requestToPromise(
        database.transaction(STORE, "readonly").objectStore(STORE).getAllKeys(range),
      )) as string[];
    } finally {
      database.close();
    }
  };
  return {
    async putAll(files): Promise<readonly string[]> {
      const keys = files.map(() => newPoolKey());
      const database = await openDatabase();
      try {
        const store = database.transaction(STORE, "readwrite").objectStore(STORE);
        const writes = files.map((file, i) => requestToPromise(store.put(file, keys[i]!)));
        await Promise.all(writes);
      } finally {
        database.close();
      }
      for (const url of poolUrlCache.values()) {
        URL.revokeObjectURL(url);
      }
      poolUrlCache.clear();
      return keys;
    },
    async list() {
      return await listKeys();
    },
    async url(key: string) {
      const cached = poolUrlCache.get(key);
      if (cached !== undefined) {
        return cached;
      }
      const database = await openDatabase();
      let blob: unknown;
      try {
        blob = await requestToPromise(
          database.transaction(STORE, "readonly").objectStore(STORE).get(key),
        );
      } finally {
        database.close();
      }
      if (!(blob instanceof Blob)) {
        return null;
      }
      const url = URL.createObjectURL(blob);
      poolUrlCache.set(key, url);
      return url;
    },
    async clear() {
      const database = await openDatabase();
      try {
        const range = poolRange();
        const store = database.transaction(STORE, "readwrite").objectStore(STORE);
        await requestToPromise(range === null ? store.clear() : store.delete(range));
      } finally {
        database.close();
      }
      for (const url of poolUrlCache.values()) {
        URL.revokeObjectURL(url);
      }
      poolUrlCache.clear();
    },
  };
}

/** In-memory substitute: same contract, for tests and storage-less runtimes. */
export function memoryBackgroundBlobStore(): BackgroundBlobStore & { snapshot(): Blob | null } {
  let blob: Blob | null = null;
  let cachedUrl: string | null = null;
  return {
    async put(next: Blob): Promise<void> {
      if (cachedUrl !== null) {
        URL.revokeObjectURL(cachedUrl);
        cachedUrl = null;
      }
      blob = next;
    },
    async url(): Promise<string | null> {
      if (blob === null) {
        return null;
      }
      if (cachedUrl === null) {
        cachedUrl = `blob:memory-${blob.size}`;
      }
      return cachedUrl;
    },
    async delete(): Promise<void> {
      if (cachedUrl !== null) {
        URL.revokeObjectURL(cachedUrl);
        cachedUrl = null;
      }
      blob = null;
    },
    snapshot(): Blob | null {
      return blob;
    },
  };
}

/** In-memory pool substitute for tests and storage-less runtimes. */
export function memoryWallpaperPoolStore(): WallpaperPoolStore {
  const blobs = new Map<string, Blob>();
  return {
    async putAll(files) {
      blobs.clear();
      const keys = files.map(() => newPoolKey());
      files.forEach((file, i) => blobs.set(keys[i]!, file));
      return keys;
    },
    async list() {
      return [...blobs.keys()];
    },
    async url(key) {
      const blob = blobs.get(key);
      return blob === undefined ? null : `blob:pool-${key}-${blob.size}`;
    },
    async clear() {
      blobs.clear();
    },
  };
}
