import { EngineClient, EngineWatchCache } from "@roboco/engine-client";
import type { StoredEngine } from "../lib/engine-store";
import { PickerCatalog } from "./picker-catalog";

/**
 * One supervised connection: the `EngineClient` and its watch cache for one
 * stored engine, plus the composer's picker catalog. The fleet registry
 * (ticket 31) owns the client and watch cache — it keeps one alive for
 * EVERY paired engine simultaneously — so creating a session composes the
 * registry entry's pieces rather than dialing; disposal releases only what
 * the session layer owns (the catalog). A re-pair or a gate Retry replaces
 * the registry entry, which lands here as a new session.
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

/**
 * Wrap a registry-owned client + watch cache in the session surface the app
 * reads (the composer's catalog rides along). The client must already be
 * supervised by the registry — this never dials.
 */
export function createEngineSession(
  engine: StoredEngine,
  client: EngineClient,
  cache: EngineWatchCache,
): EngineSession {
  const catalog = new PickerCatalog(client);
  return { engine, client, cache, catalog };
}

/** Release the session layer's own resources; the registry closes the client. */
export function disposeEngineSession(session: EngineSession): void {
  session.catalog.dispose();
}
