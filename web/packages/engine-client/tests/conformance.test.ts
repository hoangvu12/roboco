import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Readable } from "node:stream";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import type { Chat, EngineInfo, SessionGrant } from "@roboco/proto";
import { EngineClient } from "../src/client";
import { ENGINE_INFO, REVOKE_PAIRING_SESSION, WATCH_CHATS } from "../src/methods";
import { redeemPairingCode } from "../src/pairing";
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
    await delay(250);
    expect(parked.sockets).toHaveLength(2);
    second.close();
  });
});
