import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import path from 'node:path';
import { WebSocket } from 'ws';
import { createConnector, type Connection } from '../src/connection-core';
import type { WsSocket } from '../../../web/packages/engine-client/src/socket';
import { applyTranscriptUpdate, type TranscriptUpdate } from '../src/liveModel';

async function waitFor(predicate: () => boolean) {
  const deadline = Date.now() + 10_000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('Engine stream did not deliver the expected state');
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}

test('mobile connector conforms to the actual Roboco engine: pairing, data, queue, streams, reconnect, restore, revocation',
  { skip: !process.env.ROBOCO_MOBILE_ENGINE_TEST, timeout: 60_000 }, async () => {
  const root = path.resolve(__dirname, '../../..');
  const target = JSON.parse(execFileSync('cargo', ['metadata', '--no-deps', '--format-version', '1'], { cwd: root, encoding: 'utf8' })).target_directory;
  const binary = path.join(target, 'debug/examples/web_conformance' + (process.platform === 'win32' ? '.exe' : ''));
  const child = spawn(binary, [], { stdio: ['ignore', 'pipe', 'pipe'] });
  let connection: Connection | null = null;
  let saved: string | null = null;
  const sockets: WebSocket[] = [];
  try {
    const announced = await new Promise<{ endpoint: string; pairCode: string; deviceId: string }>((resolve, reject) => {
      const lines = createInterface({ input: child.stdout });
      const timer = setTimeout(() => { lines.close(); reject(new Error('Conformance engine did not start')); }, 20_000);
      child.once('error', error => { clearTimeout(timer); lines.close(); reject(error); });
      child.once('exit', () => { clearTimeout(timer); lines.close(); reject(new Error('Conformance engine exited before readiness')); });
      lines.on('line', line => {
        if (line.startsWith('CONFORMANCE ')) { clearTimeout(timer); lines.close(); resolve(JSON.parse(line.slice(12))); }
      });
    });
    // Consume stderr without printing pairing secrets or transcripts.
    child.stderr.resume();
    const app = createConnector({
      load: async () => saved, save: async json => { saved = json; }, forget: async () => { saved = null; },
      openRoute: async () => { throw new Error('This fixture uses network pairing'); }, closeRoute: () => {},
      webSocket: url => { const socket = new WebSocket(url); sockets.push(socket); return socket as unknown as WsSocket; },
    });
    connection = await app.connect(`${announced.endpoint}/pair#token=${announced.pairCode}`);
    assert.equal(connection.hostDeviceId, announced.deviceId);
    const chats: { id: string }[][] = [];
    await connection.subscribe('WatchChats', {}, value => chats.push(value as { id: string }[]));
    await connection.call('Mutate', { op: 'createChat', chatId: 'android-port-test', deviceId: announced.deviceId });
    await waitFor(() => chats.some(rows => rows.some(chat => chat.id === 'android-port-test')));
    const transcript: TranscriptUpdate[] = [];
    await connection.subscribe('WatchDocMessages', { chatId: 'android-port-test', targetDeviceId: announced.deviceId }, item => {
      applyTranscriptUpdate([], item as TranscriptUpdate);
      transcript.push(item as TranscriptUpdate);
    });
    await waitFor(() => transcript.some(item => Array.isArray(item.reset)));
    const queue: { items: { id: string; text: string }[] }[] = [];
    await connection.subscribe('WatchQueue', { chatId: 'android-port-test' }, item => queue.push(item as typeof queue[number]));
    await connection.call('QueueMessage', { chatId: 'android-port-test', text: 'Android queue round trip', holdForTurnEnd: true });
    await waitFor(() => queue.some(item => item.items.some(row => row.text === 'Android queue round trip')));
    const row = queue.at(-1)!.items.find(row => row.text === 'Android queue round trip')!;
    await connection.call('RemoveQueuedMessage', { chatId: 'android-port-test', id: row.id });
    const snapshots = chats.length;
    sockets.at(-1)!.terminate();
    await waitFor(() => chats.length > snapshots && connection!.state === 'connected');
    const beforeResume = chats.length;
    connection.resume();
    await waitFor(() => chats.length > beforeResume && connection!.state === 'connected');
    connection.disconnect();
    connection = await app.restore();
    assert.equal(connection!.hostDeviceId, announced.deviceId);
    await connection!.call('RevokePairingSession', { sessionId: connection!.sessionId });
    connection!.disconnect(); connection = null;
    await assert.rejects(app.restore(), /revoked or refused/);
    await app.forget(); assert.equal(saved, null);
  } finally {
    connection?.disconnect();
    sockets.forEach(socket => socket.terminate());
    child.kill();
  }
});
