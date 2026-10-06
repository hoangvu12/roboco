import { EngineClient, type EngineStatus } from '../../../web/packages/engine-client/src/client';
import { parsePairingUrl, redeemPairingCode } from '../../../web/packages/engine-client/src/pairing';
import { isTailcatInvite, parseTailcatInvite } from '../../../web/packages/engine-client/src/tailcat-invite';
import type { WebSocketFactory } from '../../../web/packages/engine-client/src/socket';
import { utf8Decode } from './utf8';

type Route = { kind: 'network'; baseUrl: string } | { kind: 'tailcat'; address: string; derpMap: string };
type SavedConnection = { version: 1; route: Route; credential: string; sessionId: string; deviceId: string };

export interface ConnectorDependencies {
  load(): Promise<string | null>;
  save(json: string): Promise<void>;
  forget(): Promise<void>;
  openRoute(address: string, derpMap: string): Promise<string>;
  closeRoute(): void;
  fetch?: typeof fetch;
  webSocket?: WebSocketFactory;
  connectTimeoutMs?: number;
}

export class Connection {
  readonly hostDeviceId: string;
  readonly sessionId: string;
  private closed = false;

  constructor(private readonly client: EngineClient, sessionId: string, private readonly closeRoute: () => void) {
    const info = client.engineInfo;
    if (!info) throw new Error('Engine identity has not been verified');
    this.hostDeviceId = info.deviceId;
    this.sessionId = sessionId;
  }

  get state() { return this.client.state; }
  onStatus(listener: (status: EngineStatus) => void) { return this.client.onStatus(listener); }

  // Operations stay on this engine. Never silently strip a foreign target.
  private params(params: Record<string, unknown>): Record<string, unknown> {
    if (params.targetDeviceId != null && params.targetDeviceId !== this.hostDeviceId) {
      throw new Error('This operation belongs to another engine; pair that engine first');
    }
    const { targetDeviceId: _, ...wire } = params;
    if (wire.target && typeof wire.target === 'object') {
      wire.target = this.params(wire.target as Record<string, unknown>);
    }
    return wire;
  }

  call(method: string, params: Record<string, unknown>): Promise<unknown> {
    return this.client.call(method, this.params(params));
  }

  async subscribe(method: string, params: Record<string, unknown>, onItem: (item: unknown) => void,
    onError: (error: Error) => void = () => {}): Promise<() => void> {
    if (this.closed || this.client.state === 'parked') throw new Error('Pair this engine again to reconnect');
    const handle = this.client.watch(method, this.params(params), {
      onItem, onEnd: error => onError(error ?? new Error('Engine stream ended')),
    }, method === 'SubscribeTerminal' ? { ackTimeoutMs: 0 } : {});
    return () => handle.cancel();
  }

  resume(): void { this.client.reconnect(); }

  disconnect(): void {
    if (this.closed) return;
    this.closed = true;
    this.client.close();
    this.closeRoute();
  }
}

function serverRoot(input: string): string {
  const url = new URL(input);
  if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password ||
      url.search || url.hash || url.pathname !== '/') {
    throw new Error('Engine address must be an HTTP or HTTPS server root');
  }
  return url.origin;
}

function savedConnection(json: string): SavedConnection {
  const saved = JSON.parse(json) as SavedConnection;
  if (saved?.version !== 1 || !saved.route || typeof saved.credential !== 'string' || !saved.credential ||
      typeof saved.sessionId !== 'string' || !saved.sessionId || typeof saved.deviceId !== 'string' || !saved.deviceId) {
    throw new Error('Saved engine connection is damaged; forget it and pair again');
  }
  if (saved.route.kind === 'network') serverRoot(saved.route.baseUrl);
  else if (saved.route.kind !== 'tailcat' || typeof saved.route.address !== 'string' ||
      !saved.route.address.startsWith('tc') || typeof saved.route.derpMap !== 'string') {
    throw new Error('Saved engine route is damaged; forget it and pair again');
  }
  return saved;
}

export function createConnector(deps: ConnectorDependencies) {
  let active: Connection | null = null;
  let connecting = false;

  async function baseUrl(route: Route): Promise<string> {
    if (route.kind === 'network') return serverRoot(route.baseUrl);
    const base = serverRoot(await deps.openRoute(route.address, route.derpMap));
    if (new URL(base).hostname !== '127.0.0.1') throw new Error('Native transport returned a non-loopback address');
    return base;
  }

  async function authenticated(base: string, credential: string, sessionId: string, deviceId?: string): Promise<Connection> {
    const client = new EngineClient({
      endpoint: base.replace(/^http/, 'ws'), credential, expectedDeviceId: deviceId,
      webSocket: deps.webSocket, connectTimeoutMs: deps.connectTimeoutMs,
    });
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => { stop(); reject(new Error('Could not connect to the engine; check its address and try again')); }, deps.connectTimeoutMs ?? 25_000);
        const stop = client.onStatus(status => {
          if (status.state === 'connected') { clearTimeout(timer); stop(); resolve(); }
          else if (status.state === 'parked' || status.state === 'closed') {
            clearTimeout(timer); stop(); reject(new Error(status.state === 'parked' ? status.detail : 'Engine connection closed'));
          }
        });
        client.connect();
      });
      return new Connection(client, sessionId, deps.closeRoute);
    } catch (error) { client.close(); throw error; }
  }

  async function exclusive<T>(work: () => Promise<T>): Promise<T> {
    if (connecting) throw new Error('An engine connection is already being opened');
    connecting = true;
    active?.disconnect();
    active = null;
    try { return await work(); }
    catch (error) { deps.closeRoute(); throw error; }
    finally { connecting = false; }
  }

  return {
    connect: (input: string) => exclusive(async () => {
      let route: Route;
      let pairCode: string;
      if (isTailcatInvite(input)) {
        const invite = parseTailcatInvite(input, utf8Decode);
        route = { kind: 'tailcat', address: invite.address, derpMap: '' };
        pairCode = invite.token;
      } else {
        const parsed = parsePairingUrl(input);
        route = { kind: 'network', baseUrl: serverRoot(parsed.baseUrl) };
        pairCode = parsed.pairCode;
      }
      const base = await baseUrl(route);
      const grant = await redeemPairingCode(base, pairCode, 'Roboco Android', { fetch: deps.fetch });
      if (typeof grant.session?.id !== 'string' || !grant.session.id || grant.session.revokedAt != null) {
        throw new Error('Engine returned an invalid pairing session');
      }
      const connection = await authenticated(base, grant.credential, grant.session.id);
      try {
        await deps.save(JSON.stringify({ version: 1, route, credential: grant.credential,
          sessionId: grant.session.id, deviceId: connection.hostDeviceId } satisfies SavedConnection));
      } catch (error) { connection.disconnect(); throw error; }
      active = connection;
      return connection;
    }),
    restore: () => exclusive(async () => {
      const json = await deps.load();
      if (!json) return null;
      const saved = savedConnection(json);
      active = await authenticated(await baseUrl(saved.route), saved.credential, saved.sessionId, saved.deviceId);
      return active;
    }),
    forget: async () => {
      if (connecting) throw new Error('Wait for the engine connection to finish');
      await deps.forget();
      active?.disconnect();
      active = null;
    },
  };
}
