import type { EngineStatus } from "@roboco/engine-client";
import { EngineClient, EngineWatchCache } from "@roboco/engine-client";
import type { StoredEngine } from "../lib/engine-store";
import { engineWsEndpoint } from "../lib/engine-store";
import { PickerCatalog } from "./picker-catalog";

/**
 * One supervised connection: the EngineClient and its watch cache for one
 * stored engine. Rebuilt when the active engine or its credential changes
 * (switch or re-pair); disposed when replaced. Parked is permanent for a
 * client instance — a re-pair mints a new credential, which lands here as
 * a new session.
 */
export interface EngineSession {
  readonly engine: StoredEngine;
  readonly client: EngineClient;
  readonly cache: EngineWatchCache;
  /** Picker catalog (harnesses + models) for the composer. Disposed alongside the session. */
  readonly catalog: PickerCatalog;
}

export function engineSessionKey(engine: StoredEngine): string {
  return `${engine.baseUrl}\n${engine.credential}`;
}

export function createEngineSession(engine: StoredEngine): EngineSession {
  const client = new EngineClient({
    endpoint: engineWsEndpoint(engine.baseUrl),
    credential: engine.credential,
    expectedDeviceId: engine.deviceId ?? undefined,
    // Dev-only console diagnostics. The client logs its swallowed failure
    // paths (dropped server frames with no pending request, watch item/end
    // handlers that threw) through this option — without a logger those
    // deaths are completely invisible in the browser, and a silently dead
    // WatchDocMessages stream looks exactly like "no reply arrives" (the
    // round-2 research could not split that hypothesis from a render bug
    // for lack of these lines). Production builds keep the default no-op.
    log: import.meta.env.DEV
      ? (message, detail) => console.debug("[engine]", message, detail)
      : undefined,
  });
  const cache = new EngineWatchCache(client);
  const catalog = new PickerCatalog(client);
  client.connect();
  return { engine, client, cache, catalog };
}

export function disposeEngineSession(session: EngineSession): void {
  session.cache.dispose();
  session.catalog.dispose();
  session.client.close();
}
