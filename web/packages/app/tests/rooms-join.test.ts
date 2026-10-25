import { createServer } from "node:http";
import type { AddressInfo, Server } from "node:net";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { encodeRoomInvite, RoomInviteError, roomId } from "@roboco/engine-client";
import { EngineStore, type StorageLike } from "../src/lib/engine-store";
import { RoomStore } from "../src/lib/room-store";
import {
  describeRoomJoinError,
  describeRoomJoinResult,
  joinRoom,
  offlineRoomMembers,
  originSupportsRooms,
  roomMemberOfEngine,
} from "../src/lib/rooms";
import type { EngineRegistrySnapshot } from "@roboco/engine-client";

/**
 * The per-member room join (rooms#05) against a scripted fake ORIGIN engine:
 * every member's `POST /pairing/room-redeem` is answered with the grant
 * shapes ticket 02 defined — session + credential + the
 * `/tailcat-relay/{id}` base — and one member answers 502 with the
 * label-shaped engine text. The real EngineStore and RoomStore land the
 * results: joined members become ordinary relayed engines, the offline
 * member stays in the room record for the engines UI's offline rows, and
 * the failures surface label-only. The room secret never logs.
 */

const SECRET = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

interface ReceivedRequest {
  readonly url: string;
  readonly body: Record<string, unknown>;
}

/** The scripted fake origin engine: 200 grants for server/desktop, 502 for ghost. */
class FakeOriginEngine {
  #server: Server | null = null;
  #url = "";
  /** target label -> relay id it grants (absent -> 502 label-only refusal). */
  grants = new Map<string, string>();
  refusal = new Map<string, { status: number; body: string }>();
  readonly received: ReceivedRequest[] = [];

  get url(): string {
    return this.#url;
  }

  listen(): Promise<void> {
    return new Promise((resolve, reject) => {
      const server = createServer((request, response) => {
        const chunks: Buffer[] = [];
        request.on("data", (chunk: Buffer) => chunks.push(chunk));
        request.on("end", () => {
          const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
          this.received.push({ url: request.url ?? "", body });
          if (request.url !== "/pairing/room-redeem") {
            response.writeHead(404, { "content-type": "application/json" });
            response.end(JSON.stringify({ error: "not found" }));
            return;
          }
          const target = typeof body.target === "string" ? body.target : "";
          const grant = this.grants.get(target);
          if (grant !== undefined) {
            response.writeHead(200, { "content-type": "application/json" });
            response.end(
              JSON.stringify({
                credential: `cred-${grant}`,
                session: {
                  id: `session-${grant}`,
                  label: body.label,
                  createdAt: 1_800_000_000_000,
                  lastSeen: 1_800_000_000_000,
                  revokedAt: null,
                },
                baseUrl: `${this.#url}/tailcat-relay/${grant}`,
              }),
            );
            return;
          }
          const refusal = this.refusal.get(target);
          response.writeHead(refusal?.status ?? 502, { "content-type": "application/json" });
          response.end(
            refusal?.body ?? JSON.stringify({ error: `could not reach the room member "${target}" through Tailcat` }),
          );
        });
      });
      server.on("error", reject);
      server.listen(0, "127.0.0.1", () => {
        this.#server = server;
        this.#url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
        resolve();
      });
    });
  }

  close(): void {
    this.#server?.close();
    this.#server = null;
  }
}

const cleanups: Array<() => void> = [];

afterEach(() => {
  for (const cleanup of cleanups.splice(0)) {
    cleanup();
  }
});

function fakeOrigin(): FakeOriginEngine {
  const origin = new FakeOriginEngine();
  cleanups.push(() => origin.close());
  return origin;
}

const INVITE = encodeRoomInvite({
  v: 1,
  name: "Home fleet",
  secret: SECRET,
  members: [
    { label: "server", address: "tcAAAABBBBccccDDDD_eeee-ffff0123" },
    { label: "desktop", address: "tcanotheraddress1234" },
    { label: "ghost", address: "tcGHOSTADDRESS000000000000000" },
  ],
});

function rig(origin: FakeOriginEngine) {
  const fleet = new EngineStore({ storage: memoryStorage(), now: () => 10_000 });
  const rooms = new RoomStore({ storage: memoryStorage(), now: () => 10_000 });
  return {
    fleet,
    rooms,
    join: (invite = INVITE, label = "Roboco web on Linux") =>
      joinRoom(invite, label, { fleet, rooms, originBaseUrl: origin.url, now: () => 10_000 }),
  };
}

describe("joinRoom against a scripted origin engine", () => {
  it("redeems every member, lands relayed engines, and records the room", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "11111111-1111-1111-1111-111111111111");
    origin.grants.set("desktop", "22222222-2222-2222-2222-222222222222");
    // ghost: no grant -> the 502 label-only refusal.

    const { fleet, rooms, join } = rig(origin);
    const result = await join();

    // Both online members landed as ordinary relayed engines.
    const engines = fleet.getSnapshot().engines;
    expect(engines.map((engine) => engine.baseUrl)).toEqual([
      `${origin.url}/tailcat-relay/11111111-1111-1111-1111-111111111111`,
      `${origin.url}/tailcat-relay/22222222-2222-2222-2222-222222222222`,
    ]);
    expect(engines[0]!.credential).toBe("cred-11111111-1111-1111-1111-111111111111");
    expect(engines[0]!.sessionId).toBe("session-11111111-1111-1111-1111-111111111111");
    // The first landed member is the active engine.
    expect(fleet.getSnapshot().active).toBe(`${origin.url}/tailcat-relay/11111111-1111-1111-1111-111111111111`);

    // The requests the origin engine saw: one per member, exactly the
    // engine's { invite, target, label } shape, the device label each time.
    expect(origin.received).toHaveLength(3);
    expect(origin.received.map((request) => request.body.target)).toEqual(["server", "desktop", "ghost"]);
    for (const request of origin.received) {
      expect(request.url).toBe("/pairing/room-redeem");
      expect(request.body).toEqual({
        invite: INVITE,
        target: request.body.target,
        label: "Roboco web on Linux",
      });
    }

    // The room record: the full member list, ghost unjoined.
    const room = rooms.getSnapshot().rooms[0]!;
    expect(room.id).toBe(roomId(SECRET));
    expect(room.name).toBe("Home fleet");
    expect(room.secret).toBe(SECRET);
    expect(room.origin).toBe(origin.url);
    expect(room.members).toEqual([
      { label: "server", address: "tcAAAABBBBccccDDDD_eeee-ffff0123", engineKey: engines[0]!.baseUrl },
      { label: "desktop", address: "tcanotheraddress1234", engineKey: engines[1]!.baseUrl },
      { label: "ghost", address: "tcGHOSTADDRESS000000000000000", engineKey: null },
    ]);

    // Partial failure: the rest joined, the failure is label-only.
    expect(result.joined.map((entry) => entry.label)).toEqual(["server", "desktop"]);
    expect(result.failed).toEqual([
      { label: "ghost", message: 'could not reach the room member "ghost" through Tailcat' },
    ]);
    const described = describeRoomJoinResult(result);
    expect(described).toContain("2 of 3 members");
    expect(described).toContain('ghost — could not reach the room member "ghost"');
    expect(described).not.toContain(SECRET);
    expect(described).not.toContain("tcGHOSTADDRESS");
    expect(described).not.toContain(INVITE);

    // The engines UI wiring: joined members resolve, ghost shows offline.
    expect(roomMemberOfEngine(rooms.getSnapshot(), engines[0]!.baseUrl)).toEqual({
      label: "server",
      roomName: "Home fleet",
    });
    expect(roomMemberOfEngine(rooms.getSnapshot(), "http://elsewhere.example")).toBeNull();
    const offline = offlineRoomMembers(rooms.getSnapshot(), fleet.getSnapshot());
    expect(offline).toEqual([{ key: `${room.id}:ghost`, label: "ghost", roomName: "Home fleet" }]);
  });

  it("re-joining mints fresh relays and forgets the dead ones", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "aaaaaaaa-0000-0000-0000-000000000001");

    const { fleet, rooms, join } = rig(origin);
    await join();
    expect(fleet.getSnapshot().engines).toHaveLength(1);

    // The origin engine restarts redeems fresh: every redeem mints a new
    // relay id (room_relay.rs), so the second join lands a new key.
    origin.grants.set("server", "aaaaaaaa-0000-0000-0000-000000000002");
    origin.grants.set("desktop", "aaaaaaaa-0000-0000-0000-000000000003");
    const second = await join();

    const engines = fleet.getSnapshot().engines;
    expect(engines.map((engine) => engine.baseUrl)).toEqual([
      `${origin.url}/tailcat-relay/aaaaaaaa-0000-0000-0000-000000000002`,
      `${origin.url}/tailcat-relay/aaaaaaaa-0000-0000-0000-000000000003`,
    ]);
    // One room, not two; the first join time survives.
    const recorded = rooms.getSnapshot().rooms;
    expect(recorded).toHaveLength(1);
    expect(recorded[0]!.joinedAt).toBe(10_000);
    expect(recorded[0]!.members.map((member) => member.label)).toEqual(["server", "desktop", "ghost"]);
    // The previous relay key is gone from the fleet — no duplicate engines.
    expect(second.joined).toHaveLength(2);
    expect(offlineRoomMembers(rooms.getSnapshot(), fleet.getSnapshot())).toHaveLength(1);
  });

  it("a member that fails on re-join keeps its previous engine", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "bbbbbbbb-0000-0000-0000-000000000001");

    const { fleet, rooms, join } = rig(origin);
    await join();
    const firstKey = fleet.getSnapshot().engines[0]!.baseUrl;

    // server goes offline; only the still-working member is re-granted.
    origin.grants.delete("server");
    origin.grants.set("desktop", "bbbbbbbb-0000-0000-0000-000000000002");
    await join();
    const engines = fleet.getSnapshot().engines;
    // server's old relay is still alive and kept — it is not forgotten.
    expect(engines.map((engine) => engine.baseUrl)).toContain(firstKey);
    const room = rooms.getSnapshot().rooms[0]!;
    expect(room.members.find((member) => member.label === "server")!.engineKey).toBe(firstKey);
  });

  it("refuses a bad invite before any request fires and stores nothing", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "cccccccc-0000-0000-0000-000000000001");

    const { fleet, rooms, join } = rig(origin);
    for (const junk of ["", "not an invite", "roboco-room:!!!!", "https://x/pair#token=y"]) {
      await expect(join(junk)).rejects.toBeInstanceOf(RoomInviteError);
    }
    expect(origin.received).toHaveLength(0);
    expect(fleet.getSnapshot().engines).toHaveLength(0);
    expect(rooms.getSnapshot().rooms).toHaveLength(0);
    expect(describeRoomJoinError(await join("nonsense").then(() => null, (error: unknown) => error))).toContain(
      "That is not a room invite: a room invite starts with roboco-room:",
    );
  });

  it("refuses while either persisted store is damaged", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "dddddddd-0000-0000-0000-000000000001");

    const damagedFleetStorage = memoryStorage();
    damagedFleetStorage.setItem("roboco.fleet.v1", "{not json");
    const fleet = new EngineStore({ storage: damagedFleetStorage });
    const rooms = new RoomStore({ storage: memoryStorage() });
    await expect(
      joinRoom(INVITE, "Roboco web", { fleet, rooms, originBaseUrl: origin.url }),
    ).rejects.toThrow("needs repair");
    expect(origin.received).toHaveLength(0);

    const damagedRoomStorage = memoryStorage();
    damagedRoomStorage.setItem("roboco.rooms.v1", "{not json");
    const healthyFleet = new EngineStore({ storage: memoryStorage() });
    const damagedRooms = new RoomStore({ storage: damagedRoomStorage });
    await expect(
      joinRoom(INVITE, "Roboco web", { fleet: healthyFleet, rooms: damagedRooms, originBaseUrl: origin.url }),
    ).rejects.toThrow("needs repair");
    expect(damagedRoomStorage.getItem("roboco.rooms.v1")).toBe("{not json");
    expect(origin.received).toHaveLength(0);
  });

  it("the origin engine unreachable: every member fails label-only, the room still records", async () => {
    const origin = fakeOrigin();
    // Never listened: connection refused.
    const { fleet, rooms, join } = rig(origin);
    const result = await join();
    expect(result.joined).toHaveLength(0);
    expect(result.failed).toHaveLength(3);
    for (const failure of result.failed) {
      expect(failure.label.length).toBeGreaterThan(0);
      expect(failure.message).not.toContain(SECRET);
      expect(failure.message).not.toContain(INVITE);
    }
    expect(fleet.getSnapshot().engines).toHaveLength(0);
    // The room record exists — the member labels persist for the offline rows.
    expect(rooms.getSnapshot().rooms).toHaveLength(1);
    expect(offlineRoomMembers(rooms.getSnapshot(), fleet.getSnapshot())).toHaveLength(3);
  });

  it("dedups repeated member labels to the first entry (the engine dials the first match)", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "eeeeeeee-0000-0000-0000-000000000001");
    const invite = encodeRoomInvite({
      v: 1,
      name: "Union book",
      secret: SECRET,
      members: [
        { label: "server", address: "tcFRESHADDRESS00000000000000" },
        { label: "server", address: "tcSTALEADDRESS0000000000000" },
      ],
    });
    const { fleet, rooms, join } = rig(origin);
    await join(invite);
    expect(origin.received).toHaveLength(1);
    expect(origin.received[0]!.body.target).toBe("server");
    expect(fleet.getSnapshot().engines).toHaveLength(1);
    expect(rooms.getSnapshot().rooms[0]!.members).toHaveLength(1);
    expect(rooms.getSnapshot().rooms[0]!.members[0]!.address).toBe("tcFRESHADDRESS00000000000000");
  });
});

describe("originSupportsRooms", () => {
  const origin = "http://127.0.0.1:7332";

  function registryFor(capabilities: string[], state = "connected"): EngineRegistrySnapshot {
    return {
      configurationError: null,
      engines: [
        {
          key: origin,
          state,
          lastError: null,
          info: { deviceId: "device-1", workspaceScope: "local", capabilities },
          generation: 1,
          chats: { rows: [] },
          spaces: { rows: [] },
          sessions: { rows: [] },
          devices: { rows: [] },
        },
      ],
    } as unknown as EngineRegistrySnapshot;
  }

  it("shows the room UI only when the origin engine advertises rooms-v1", () => {
    const fleet = {
      active: origin,
      engines: [{ baseUrl: origin, credential: "c", label: "l", sessionId: "s", pairedAt: 0, deviceId: null }],
      configurationError: null,
    };
    expect(originSupportsRooms(fleet, registryFor(["rooms-v1", "web-client"]), origin)).toBe(true);
    expect(originSupportsRooms(fleet, registryFor(["web-client"]), origin)).toBe(false);
    // A relayed engine carrying the capability does not count: rooms redeem
    // through the DIRECT origin engine.
    expect(
      originSupportsRooms(
        {
          ...fleet,
          engines: fleet.engines.concat([
            { ...fleet.engines[0]!, baseUrl: `${origin}/tailcat-relay/x` },
          ]),
        },
        registryFor(["rooms-v1"]),
        origin,
      ),
    ).toBe(true);
    // No direct origin engine at all: hidden.
    expect(
      originSupportsRooms(
        { active: null, engines: [], configurationError: null },
        registryFor(["rooms-v1"]),
        origin,
      ),
    ).toBe(false);
    // The origin engine never connected (no info yet): hidden.
    expect(originSupportsRooms(fleet, registryFor([], "reconnecting"), origin)).toBe(false);
  });
});

describe("the room secret never reaches log output during a join", () => {
  const spies: Array<ReturnType<typeof vi.spyOn>> = [];

  beforeEach(() => {
    spies.push(vi.spyOn(console, "log"), vi.spyOn(console, "info"), vi.spyOn(console, "warn"), vi.spyOn(console, "error"), vi.spyOn(console, "debug"));
  });

  afterEach(() => {
    for (const spy of spies.splice(0)) {
      for (const call of spy.mock.calls) {
        const text = call
          .map((value) => (typeof value === "string" ? value : JSON.stringify(value) ?? ""))
          .join(" ");
        expect(text).not.toContain(SECRET);
        expect(text).not.toContain(INVITE);
        expect(text).not.toContain("tcGHOSTADDRESS");
      }
      spy.mockRestore();
    }
  });

  it("a full join (success, failure, refusal) stays silent about the secret", async () => {
    const origin = fakeOrigin();
    await origin.listen();
    origin.grants.set("server", "ffffffff-0000-0000-0000-000000000001");
    const { fleet, rooms, join } = rig(origin);
    await join();
    await join("roboco-room:!!!!").then(
      () => {},
      () => {},
    );
    expect(fleet.getSnapshot().engines).toHaveLength(1);
    expect(rooms.getSnapshot().rooms).toHaveLength(1);
  });
});
