import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Readable } from "node:stream";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import type { Chat, EngineInfo, SessionGrant } from "@roboco/proto";
import { EngineClient } from "../src/client";
import { ENGINE_INFO, MUTATE, READ_ATTACHMENT_CHUNK, REVOKE_PAIRING_SESSION, UPLOAD_CHUNK, UPLOAD_COMMIT, WATCH_CHATS } from "../src/methods";
import { redeemPairingCode } from "../src/pairing";
import { encodeRoomInvite } from "../src/room-invite";
import { redeemRoomMemberViaOrigin } from "../src/room-redeem";
import { EngineWatchCache } from "../src/watch-cache";
import { delay, statusWhen, trackedFactory, waitUntil } from "./helpers/ws";

const FAST_BACKOFF = { initialMs: 25, jitterMs: 1, maxMs: 100 };

interface EngineHandle {
  child: ChildProcess;
  endpoint: string;
  pairCode: string;
  deviceId: string;
}

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..", "..");
const exampleBinary = join(
  repoRoot,
  "target",
  "debug",
  "examples",
  process.platform === "win32" ? "web_conformance.exe" : "web_conformance",
);

function run(command: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: repoRoot, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`${command} ${args.join(" ")} exited with ${code}`));
      }
    });
  });
}

function waitForLine(stream: Readable, prefix: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let buffer = "";
    const onData = (chunk: Buffer): void => {
      buffer += chunk.toString("utf8");
      const newline = buffer.indexOf("\n");
      if (newline >= 0 && buffer.slice(0, newline).trim().startsWith(prefix)) {
        cleanup();
        resolve(buffer.slice(0, newline).trim().slice(prefix.length));
      }
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    const cleanup = (): void => {
      stream.off("data", onData);
      stream.off("error", onError);
    };
    stream.on("data", onData);
    stream.on("error", onError);
  });
}

let engine: EngineHandle | undefined;
let grant: SessionGrant | undefined;
const tracked = trackedFactory();
let client: EngineClient | undefined;

beforeAll(async () => {
  await run("cargo", ["build", "-p", "roboco-engine", "--example", "web_conformance"]);
  const child = spawn(exampleBinary, { stdio: ["ignore", "pipe", "ignore"] });
  const line = await waitForLine(child.stdout!, "CONFORMANCE ");
  const info = JSON.parse(line) as { endpoint: string; pairCode: string; deviceId: string };
  engine = { child, ...info };
}, 600_000);

afterAll(async () => {
  client?.close();
  if (engine !== undefined) {
    engine.child.kill();
    await once(engine.child, "exit").catch(() => {});
  }
});

describe("conformance against a real engine", () => {
  test("redeems the pair code over HTTP", async () => {
    grant = await redeemPairingCode(engine!.endpoint, engine!.pairCode, "Conformance");
    expect(grant.credential).toHaveLength(43);
    expect(grant.session.label).toBe("Conformance");
    expect(typeof grant.session.id).toBe("string");
  });

  test("connects with first-frame auth, verifies identity, and makes typed calls", async () => {
    client = new EngineClient({
      endpoint: engine!.endpoint.replace("http", "ws"),
      credential: grant!.credential,
      expectedDeviceId: engine!.deviceId,
      webSocket: tracked.factory,
      backoff: FAST_BACKOFF,
    });
    client.connect();
    const status = await statusWhen(client, (value) => value.state === "connected");
    expect(status).toMatchObject({ state: "connected", info: { deviceId: engine!.deviceId } });
    expect(client.generation).toBe(1);
    expect(tracked.sockets).toHaveLength(1);

    const info = await client.call<EngineInfo>(ENGINE_INFO, {});
    expect(info.deviceId).toBe(engine!.deviceId);
    expect(info.workspaceScope).toBe("local");
    expect(info.capabilities).toContain("web-client");
  });

  test("watches the chat list stream", async () => {
    const items: Array<{ item: Chat[]; generation: number }> = [];
    client!.watch<Chat[]>(WATCH_CHATS, {}, {
      onItem: (item, context) => items.push({ item, generation: context.generation }),
    });
    await waitUntil(() => items.length >= 1, 10_000, "first WatchChats item");
    expect(items[0]!.generation).toBe(1);
    for (const chat of items[0]!.item) {
      expect(typeof chat.id).toBe("string");
      expect(typeof chat.deviceId).toBe("string");
      expect(typeof chat.archived).toBe("boolean");
      expect(typeof chat.createdAt).toBe("string");
    }
  });

  test("a hard drop reconnects, re-verifies identity, and resubscribes", async () => {
    tracked.sockets[0]!.terminate();
    await statusWhen(client!, (value) => value.state === "connected" && value.generation === 2);
    expect(tracked.sockets).toHaveLength(2);
    const info = await client!.call<EngineInfo>(ENGINE_INFO, {});
    expect(info.deviceId).toBe(engine!.deviceId);

    const items: Array<number> = [];
    const handle = client!.watch<Chat[]>(WATCH_CHATS, {}, {
      onItem: (_item, context) => items.push(context.generation),
    });
    await waitUntil(() => items.includes(2), 10_000, "resubscribed item on generation 2");
    handle.cancel();
  });
});

describe("conformance against a real engine: the watch cache", () => {
  test("populates every collection, applies mutations incrementally, and swaps on reconnect without ghost rows", async () => {
    const own = trackedFactory();
    const cacheClient = new EngineClient({
      endpoint: engine!.endpoint.replace("http", "ws"),
      credential: grant!.credential,
      expectedDeviceId: engine!.deviceId,
      webSocket: own.factory,
      backoff: FAST_BACKOFF,
    });
    try {
      const cache = new EngineWatchCache(cacheClient);
      cacheClient.connect();
      await statusWhen(cacheClient, (value) => value.state === "connected");
      await waitUntil(
        () => {
          const snapshot = cache.getSnapshot();
          return (
            snapshot.chats.loaded &&
            snapshot.spaces.loaded &&
            snapshot.devices.loaded &&
            snapshot.statuses.loaded
          );
        },
        10_000,
        "every collection loaded",
      );
      const first = cache.getSnapshot();
      expect(first.generation).toBe(1);
      expect(first.capabilities).toContain("web-client");
      expect(cache.supports("web-client")).toBe(true);

      await cacheClient.call(MUTATE, { op: "createChat", chatId: "watch-cache-a", deviceId: engine!.deviceId });
      await cacheClient.call(MUTATE, { op: "createChat", chatId: "watch-cache-b", deviceId: engine!.deviceId });
      await waitUntil(
        () => cache.getSnapshot().chats.rows.length === 2,
        10_000,
        "created chats arrive",
      );
      const before = cache.getSnapshot().chats.rows;

      await cacheClient.call(MUTATE, { op: "renameChat", chatId: "watch-cache-b", title: "Renamed" });
      await waitUntil(
        () =>
          cache
            .getSnapshot()
            .chats.rows.some((row) => row.id === "watch-cache-b" && row.title === "Renamed"),
        10_000,
        "rename lands",
      );
      const after = cache.getSnapshot().chats.rows;
      expect(after.find((row) => row.id === "watch-cache-a")).toBe(
        before.find((row) => row.id === "watch-cache-a"),
      );

      // A drop mid-stream, then server-side changes while the client is
      // offline: chat-b is deleted, chat-c is created by another client.
      own.sockets[0]!.terminate();
      const other = trackedFactory();
      const second = new EngineClient({
        endpoint: engine!.endpoint.replace("http", "ws"),
        credential: grant!.credential,
        expectedDeviceId: engine!.deviceId,
        webSocket: other.factory,
        backoff: FAST_BACKOFF,
      });
      second.connect();
      await statusWhen(second, (value) => value.state === "connected");
      await second.call(MUTATE, { op: "deleteChat", chatId: "watch-cache-b" });
      await second.call(MUTATE, { op: "createChat", chatId: "watch-cache-c", deviceId: engine!.deviceId });
      second.close();

      await statusWhen(cacheClient, (value) => value.state === "connected" && value.generation === 2);
      await waitUntil(
        () => {
          const snapshot = cache.getSnapshot();
          // statuses must be part of the wait: the assertions below read it
          // immediately after, and the collections refill independently —
          // chats landing first raced the statuses watch (the intermittent
          // CI failure at the swapped.statuses.loaded assertion).
          return (
            snapshot.generation === 2 &&
            snapshot.chats.loaded &&
            snapshot.statuses.loaded &&
            snapshot.chats.rows.length === 2
          );
        },
        10_000,
        "cache refilled on generation 2",
      );
      const swapped = cache.getSnapshot();
      expect(swapped.chats.rows.map((row) => row.id).sort()).toEqual(["watch-cache-a", "watch-cache-c"]);
      expect(swapped.statuses.loaded).toBe(true);
      expect(swapped.capabilities).toContain("web-client");
      expect(swapped.chats.rows.some((row) => row.id === "watch-cache-b")).toBe(false);
    } finally {
      cacheClient.close();
    }
  }, 60_000);
});

describe("conformance against a real engine: the upload path", () => {
  // Ticket 06's upload health check: the web upload ladder's wire half —
  // UploadChunk (680 000 b64 chars, 3-wide) → UploadCommit →
  // ReadAttachmentChunk loop — against the REAL example engine, at a size
  // the old 32 MB engine cap refused. The engine's 100 MB cap boundary is
  // covered by the engine's own tests; here the payload is 33 MB —
  // deliberately past the legacy engine cap, far under the new one.
  const UPLOAD_CHUNK_B64_CHARS = 680_000;
  const CONCURRENCY = 3;

  test("advertises uploads-100mb-v1 and round-trips a 33 MB upload", async () => {
    const info = await client!.call<EngineInfo>(ENGINE_INFO, {});
    expect(info.capabilities).toContain("uploads-100mb-v1");

    const total = 33 * 1024 * 1024;
    const payload = Buffer.alloc(total);
    for (let i = 0; i < total; i++) {
      payload[i] = (i * 31) % 251;
    }
    const b64 = payload.toString("base64");
    const chunks: string[] = [];
    for (let start = 0; start < b64.length; start += UPLOAD_CHUNK_B64_CHARS) {
      chunks.push(b64.slice(start, Math.min(start + UPLOAD_CHUNK_B64_CHARS, b64.length)));
    }
    expect(chunks.length, "33 MB ≈ 68 chunks of ~510 KB").toBe(68);

    const uploadId = `conformance-${Date.now()}`;
    let next = 0;
    const failures: unknown[] = [];
    const workers = Array.from({ length: CONCURRENCY }, async () => {
      for (;;) {
        const seq = next++;
        if (seq >= chunks.length) {
          return;
        }
        try {
          await client!.call(UPLOAD_CHUNK, { uploadId, seq, data: chunks[seq] });
        } catch (error) {
          failures.push(error);
          return;
        }
      }
    });
    await Promise.all(workers);
    expect(failures).toEqual([]);

    const committed = await client!.call<{ path: string }>(UPLOAD_COMMIT, {
      uploadId,
      fileName: "conformance-payload.bin",
    });
    expect(typeof committed.path).toBe("string");
    expect(committed.path.endsWith("conformance-payload.bin")).toBe(true);

    // Read-back: 45 000-byte chunks until done, reassembling by hash.
    const { createHash } = await import("node:crypto");
    const expected = createHash("sha256").update(payload).digest("hex");
    const hasher = createHash("sha256");
    let offset = 0;
    for (;;) {
      const chunk = await client!.call<{
        data: string;
        nextOffset: number;
        done: boolean;
      }>(READ_ATTACHMENT_CHUNK, { path: committed.path, offset });
      hasher.update(Buffer.from(chunk.data, "base64"));
      offset = chunk.nextOffset;
      if (chunk.done) {
        break;
      }
    }
    expect(offset).toBe(total);
    expect(hasher.digest("hex")).toBe(expected);
  }, 120_000);
});

describe("conformance against a real engine: rooms", () => {
  // rooms#05's wire half against the REAL engine. The full redeem
  // round-trip (dial + relayed presentation + grant + byte pump) needs a
  // SECOND engine to relay to and a scripted tailcat adapter — the rig
  // `crates/engine/tests/room_relay.rs` builds; the one-engine example
  // cannot host it, and the dial surface is engine-tested there. What this
  // harness CAN pin is everything before the dial: the capability, the
  // route's CORS-open preflight, the shape refusals' fixed texts — and the
  // decisive cross-implementation check: the REAL engine decodes an invite
  // this TS codec built (the unknown-target 404 runs only after a
  // successful decode), answering with the label-only error text.
  const ROOM_SECRET = "conformance-room-secret-0123456789abcdef";
  const invite = encodeRoomInvite({
    v: 1,
    name: "Conformance fleet",
    secret: ROOM_SECRET,
    members: [{ label: "server", address: "tcCONFORMANCE1" }],
  });

  test("advertises rooms-v1", async () => {
    const info = await client!.call<EngineInfo>(ENGINE_INFO, {});
    expect(info.capabilities).toContain("rooms-v1");
  });

  test("the room-redeem route is CORS-open like its pairing siblings", async () => {
    const preflight = await fetch(`${engine!.endpoint}/pairing/room-redeem`, {
      method: "OPTIONS",
      headers: { origin: "https://web.example" },
    });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("access-control-allow-origin")).toBe("*");
    expect(preflight.headers.get("access-control-allow-methods") ?? "").toContain("POST");
  });

  test("shape refusals answer fixed texts, never the payload", async () => {
    const junk = await fetch(`${engine!.endpoint}/pairing/room-redeem`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "not json",
    });
    expect(junk.status).toBe(400);
    expect(await junk.text()).toBe("expected JSON { invite, target, label }");

    // A room invite is required: a tailcat invite is refused with its own
    // fixed text.
    const tailcat = await fetch(`${engine!.endpoint}/pairing/room-redeem`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        invite: "roboco-tailcat:eyJ2IjoxfQ",
        target: "server",
        label: "Conformance web",
      }),
    });
    expect(tailcat.status).toBe(400);
    expect((await tailcat.json()).error).toBe("a room invite starts with roboco-room:");
  });

  test("the real engine decodes the TS codec's invite: label-only 404 for an unknown target", async () => {
    // The engine must parse our invite (version, name, secret, members)
    // before it can resolve the target — a 404 naming the label proves the
    // two codecs agree; a 400 would mean they do not.
    const missing = await fetch(`${engine!.endpoint}/pairing/room-redeem`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ invite, target: "laptop", label: "Conformance web" }),
    });
    expect(missing.status).toBe(404);
    const body = (await missing.json()) as { error: string };
    expect(body.error).toContain("laptop");
    expect(body.error).not.toContain(ROOM_SECRET);
    expect(body.error).not.toContain("tcCONFORMANCE1");

    // And through the client codec: the same refusal surfaces label-only.
    const error = await redeemRoomMemberViaOrigin(invite, "laptop", "Conformance web", {
      originBaseUrl: engine!.endpoint,
    }).then(
      () => null,
      (caught: unknown) => caught,
    );
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toContain("laptop");
    expect((error as Error).message).not.toContain(ROOM_SECRET);
    expect((error as Error).message).not.toContain(invite);
  });
});

describe("conformance against a real engine: revoked sessions", () => {
  test("a revoked session parks on the next handshake and never re-dials", async () => {
    const parked = trackedFactory();
    const second = new EngineClient({
      endpoint: engine!.endpoint.replace("http", "ws"),
      credential: grant!.credential,
      expectedDeviceId: engine!.deviceId,
      webSocket: parked.factory,
      backoff: FAST_BACKOFF,
    });
    second.connect();
    await statusWhen(second, (value) => value.state === "connected");

    await second.call(REVOKE_PAIRING_SESSION, { sessionId: grant!.session.id });

    parked.sockets[0]!.terminate();
    const status = await statusWhen(second, (value) => value.state === "parked");
    expect(status).toMatchObject({ state: "parked", reason: "invalid-credential" });
    await expect(second.call(ENGINE_INFO, {})).rejects.toMatchObject({ kind: "parked" });
    // A transient refusal before the terminal 4401 may cost one extra dial;
    // what must hold is that parking stops the dialing for good.
    const dialedAtPark = parked.sockets.length;
    await delay(250);
    expect(parked.sockets).toHaveLength(dialedAtPark);
    second.close();
  });
});
