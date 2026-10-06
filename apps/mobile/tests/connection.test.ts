import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { createServer, type Server } from 'node:http';
import { once } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createConnector, type Connection } from '../src/connection-core';
import type { WsSocket } from '../../../web/packages/engine-client/src/socket';

let http: Server;
let ws: WebSocketServer;
let base: string;
let storage: string | null;
let deviceId: string;
let revoked: boolean;
let redeems: number;
let requests: Record<string, unknown>[];
let connections: Connection[];
let routes: string[];
let routeCloses: number;

beforeEach(async () => {
  storage = null; deviceId = 'engine-one'; revoked = false; redeems = 0; requests = []; connections = []; routes = []; routeCloses = 0;
  http = createServer((req, res) => {
    if (req.url !== '/pairing/redeem' || req.method !== 'POST' || req.headers.authorization !== 'Bearer single-use-code') {
      res.writeHead(401).end(); return;
    }
    if (++redeems !== 1) { res.writeHead(401).end(); return; }
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ credential: 'session-credential', session: { id: 'session-one', revokedAt: null } }));
  });
  ws = new WebSocketServer({ server: http });
  ws.on('connection', socket => {
    let authenticated = false;
    socket.on('message', bytes => {
      const value = JSON.parse(bytes.toString());
      requests.push(value);
      if (!authenticated) {
        assert.deepEqual(value, { auth: 'session-credential' });
        if (revoked) { authenticated = true; socket.close(4401, 'invalid credential'); return; }
        authenticated = true; return;
      }
      if (revoked || value.cancel) return;
      if (value.method === 'EngineInfo') socket.send(JSON.stringify({ id: value.id, ok: { deviceId, workspaceScope: { kind: 'local' } } }));
      else if (value.method === 'WatchChats') socket.send(JSON.stringify({ id: value.id, item: [{ id: 'chat-one', deviceId }] }));
      else socket.send(JSON.stringify({ id: value.id, ok: value.params }));
    });
  });
  http.listen(0, '127.0.0.1'); await once(http, 'listening');
  base = `http://127.0.0.1:${(http.address() as { port: number }).port}`;
});

afterEach(async () => {
  connections.forEach(connection => connection.disconnect());
  for (const socket of ws.clients) socket.terminate();
  await new Promise<void>(resolve => ws.close(() => resolve()));
  await new Promise<void>(resolve => http.close(() => resolve()));
});

function connector(overrides = {}) {
  return createConnector({
    load: async () => storage,
    save: async json => { storage = json; },
    forget: async () => { storage = null; },
    openRoute: async address => { routes.push(address); return base; },
    closeRoute: () => { routeCloses++; },
    webSocket: url => new WebSocket(url) as unknown as WsSocket,
    connectTimeoutMs: 2_000,
    ...overrides,
  });
}

async function paired() {
  const app = connector();
  const connection = await app.connect(`${base}/pair#token=single-use-code`);
  connections.push(connection);
  return { app, connection };
}

test('redeems the fragment through Authorization, verifies identity, saves only the grant, and uses text RPC', async () => {
  const { connection } = await paired();
  assert.equal(connection.hostDeviceId, deviceId);
  const saved = JSON.parse(storage!);
  assert.equal(saved.credential, 'session-credential');
  assert.equal(saved.deviceId, deviceId);
  assert.equal(saved.route.baseUrl, base);
  assert.ok(!storage!.includes('single-use-code'));
  assert.deepEqual(await connection.call('ListFolders', { targetDeviceId: deviceId, path: '/project' }), { path: '/project' });
  assert.throws(() => connection.call('ListFolders', { targetDeviceId: 'another-engine' }), /another engine/);
  const rows = await new Promise<unknown>(async (resolve, reject) => {
    await connection.subscribe('WatchChats', {}, resolve, reject);
  });
  assert.deepEqual(rows, [{ id: 'chat-one', deviceId }]);
});

test('restores the persistent credential without redeeming the single-use code again', async () => {
  const { app, connection } = await paired(); connection.disconnect();
  const restored = await app.restore(); connections.push(restored!);
  assert.equal(restored!.hostDeviceId, deviceId); assert.equal(redeems, 1);
});

test('revocation refuses restored authentication and preserves the saved connection for explicit forgetting', async () => {
  const { app, connection } = await paired(); connection.disconnect(); revoked = true;
  await assert.rejects(app.restore(), /revoked or refused/);
  assert.ok(storage);
  await app.forget(); assert.equal(storage, null);
});

test('restoration refuses a different engine identity at the same address', async () => {
  const { app, connection } = await paired(); connection.disconnect(); deviceId = 'replacement-engine';
  await assert.rejects(app.restore(), /identity changed/);
});

test('Tailcat invites open a native route and persist the route rather than its ephemeral loopback port', async () => {
  const invitation = 'roboco-tailcat:' + Buffer.from(JSON.stringify({ address: 'tc-route', token: 'single-use-code', expiresAt: Date.now() + 60_000 })).toString('base64url');
  const app = connector();
  const connection = await app.connect(invitation); connections.push(connection);
  assert.deepEqual(routes, ['tc-route']);
  assert.deepEqual(JSON.parse(storage!).route, { kind: 'tailcat', address: 'tc-route', derpMap: '' });
  connection.disconnect();
  const restored = await app.restore(); connections.push(restored!);
  assert.deepEqual(routes, ['tc-route', 'tc-route']); assert.equal(redeems, 1);
});

test('expired and legacy invitations never open a route or redeem a code', async () => {
  const invitation = 'roboco-tailcat:' + Buffer.from(JSON.stringify({ address: 'tc-route', token: 'single-use-code', expiresAt: 1 })).toString('base64url');
  await assert.rejects(connector().connect(invitation), /expired/);
  await assert.rejects(connector().connect('kratos-pair:legacy'), /Pairing URL/);
  assert.equal(redeems, 0); assert.deepEqual(routes, []);
});

test('a storage failure closes the authenticated connection instead of claiming a saved pairing', async () => {
  await assert.rejects(connector({ save: async () => { throw new Error('storage unavailable'); } }).connect(`${base}/pair#token=single-use-code`), /storage unavailable/);
  assert.equal(storage, null); assert.ok(routeCloses > 0);
});
