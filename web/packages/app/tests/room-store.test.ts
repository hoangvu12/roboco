import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { StorageLike } from "../src/lib/engine-store";
import { ROOMS_STORAGE_KEY, RoomStore, type StoredRoom } from "../src/lib/room-store";

/**
 * The room store (rooms#05): the browser's copy of the room — secret, member
 * list, landed engine keys — persisted in browser storage with the same care
 * as a session credential. The codec is lenient (unknown keys ignored on
 * read, junk rows dropped) so a future record never breaks an older load;
 * damaged bytes are preserved and refuse joins, mirroring the fleet store.
 * The secret never reaches any log output.
 */

function memoryStorage(): StorageLike & { dump(): Map<string, string> } {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
    dump: () => map,
  };
}

const SECRET = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

function room(overrides: Partial<StoredRoom> = {}): StoredRoom {
  return {
    id: "room-id-1",
    name: "Home fleet",
    secret: SECRET,
    origin: "http://127.0.0.1:7332",
    joinedAt: 1_000,
    members: [
      { label: "server", address: "tcAAAABBBBccccDDDD_eeee-ffff0123", engineKey: "http://127.0.0.1:7332/tailcat-relay/one" },
      { label: "ghost", address: "tcANOTHERADDRESS000000000000", engineKey: null },
    ],
    ...overrides,
  };
}

describe("RoomStore", () => {
  it("records a join and round-trips through storage", () => {
    const storage = memoryStorage();
    const first = new RoomStore({ storage, now: () => 1_000 });
    first.recordJoin(room());
    const snapshot = first.getSnapshot();
    expect(snapshot.rooms).toHaveLength(1);
    expect(snapshot.rooms[0]!.secret).toBe(SECRET);
    expect(snapshot.rooms[0]!.members[0]!.engineKey).toContain("/tailcat-relay/one");
    expect(snapshot.rooms[0]!.members[1]!.engineKey).toBeNull();

    // A reload reads the same record.
    const second = new RoomStore({ storage });
    expect(second.getSnapshot().rooms).toEqual(snapshot.rooms);
    // The secret persists in browser storage — the room record's
    // secret-at-rest class (same key family as the session credential).
    expect(storage.getItem(ROOMS_STORAGE_KEY)).toContain(SECRET);
  });

  it("re-joining the same id replaces the record and keeps the first join time", () => {
    const store = new RoomStore({ storage: memoryStorage(), now: () => 2_000 });
    store.recordJoin(room());
    store.recordJoin(room({ joinedAt: 9_999, members: [{ label: "server", address: "tcA", engineKey: "http://o/tailcat-relay/two" }] }));
    const rooms = store.getSnapshot().rooms;
    expect(rooms).toHaveLength(1);
    expect(rooms[0]!.joinedAt).toBe(1_000);
    expect(rooms[0]!.members).toHaveLength(1);
    expect(rooms[0]!.members[0]!.engineKey).toContain("/tailcat-relay/two");
  });

  it("removes a room and notifies subscribers on state writes", () => {
    const store = new RoomStore({ storage: memoryStorage() });
    let fired = 0;
    const unsubscribe = store.subscribe(() => {
      fired += 1;
    });
    store.recordJoin(room());
    store.recordJoin(room());
    store.remove("unknown-id");
    store.remove(room().id);
    expect(store.getSnapshot().rooms).toHaveLength(0);
    // Three writes (join, re-join, remove); the unknown-id remove writes
    // nothing — the same identity rule the fleet store's setState applies.
    expect(fired).toBe(3);
    unsubscribe();
    store.recordJoin(room());
    expect(fired).toBe(3);
  });

  it("resolves the room behind an engine key", () => {
    const store = new RoomStore({ storage: memoryStorage() });
    store.recordJoin(room());
    expect(store.roomOfEngineKey("http://127.0.0.1:7332/tailcat-relay/one")?.name).toBe("Home fleet");
    expect(store.roomOfEngineKey("http://elsewhere.example")).toBeNull();
    expect(store.existingRoom("room-id-1")?.id).toBe("room-id-1");
  });

  it("is lenient on read: unknown keys ignored, junk rows dropped", () => {
    const storage = memoryStorage();
    // A future room record (extra keys at every level) still loads.
    storage.setItem(
      ROOMS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        futureTopLevel: true,
        rooms: [
          {
            id: "r2",
            name: "Second room",
            secret: "second-secret",
            origin: "http://o2",
            joinedAt: 5,
            futureRoomField: { nested: 1 },
            members: [
              {
                label: "laptop",
                address: "tcL",
                engineKey: "http://o2/tailcat-relay/x",
                futureMemberField: "y",
              },
              // engineKey missing reads unjoined.
              { label: "tower", address: "tcT" },
              // Junk rows are skipped, valid ones survive.
              { label: 42, address: "tcJ" },
              "not-a-member",
            ],
          },
        ],
      }),
    );
    const store = new RoomStore({ storage });
    const rooms = store.getSnapshot().rooms;
    expect(rooms).toHaveLength(1);
    expect(rooms[0]!.name).toBe("Second room");
    expect(rooms[0]!.members).toHaveLength(2);
    expect(rooms[0]!.members[1]).toEqual({ label: "tower", address: "tcT", engineKey: null });
    expect(store.getSnapshot().configurationError).toBeNull();
    // Old persisted state (no rooms key at all) reads empty — additive.
    const fresh = new RoomStore({ storage: memoryStorage() });
    expect(fresh.getSnapshot().rooms).toHaveLength(0);
  });

  it("preserves damaged persisted bytes and refuses joins until repaired", () => {
    const storage = memoryStorage();
    storage.setItem(ROOMS_STORAGE_KEY, "{not json");
    const damaged = new RoomStore({ storage });
    expect(damaged.getSnapshot().rooms).toHaveLength(0);
    expect(damaged.getSnapshot().configurationError).not.toBe(null);
    expect(storage.getItem(ROOMS_STORAGE_KEY)).toBe("{not json");
    expect(() => damaged.recordJoin(room())).toThrow();
    expect(storage.getItem(ROOMS_STORAGE_KEY)).toBe("{not json");
    // A wrong-version payload is damaged the same way.
    storage.setItem(ROOMS_STORAGE_KEY, JSON.stringify({ version: 99, rooms: [] }));
    const wrongVersion = new RoomStore({ storage });
    expect(wrongVersion.getSnapshot().configurationError).not.toBe(null);
    expect(JSON.parse(storage.getItem(ROOMS_STORAGE_KEY)!).version).toBe(99);
  });

  it("clears the storage key when the last room goes away", () => {
    const storage = memoryStorage();
    const store = new RoomStore({ storage });
    store.recordJoin(room());
    store.remove(room().id);
    expect(storage.getItem(ROOMS_STORAGE_KEY)).toBeNull();
    expect(storage.dump().has(ROOMS_STORAGE_KEY)).toBe(false);
  });
});

describe("the room secret never reaches log output", () => {
  const spies: Array<ReturnType<typeof vi.spyOn>> = [];

  beforeEach(() => {
    spies.push(vi.spyOn(console, "log"), vi.spyOn(console, "info"), vi.spyOn(console, "warn"), vi.spyOn(console, "error"), vi.spyOn(console, "debug"));
  });

  afterEach(() => {
    // Nothing any store path printed may stringify to the secret, a member
    // address, or the storage payload.
    for (const spy of spies.splice(0)) {
      for (const call of spy.mock.calls) {
        const text = call.map((value) => (typeof value === "string" ? value : JSON.stringify(value) ?? "")).join(" ");
        expect(text).not.toContain(SECRET);
        expect(text).not.toContain("tcAAAABBBBccccDDDD");
      }
      spy.mockRestore();
    }
  });

  it("record, reload, remove, and lookups stay silent about the secret", () => {
    const storage = memoryStorage();
    const store = new RoomStore({ storage });
    store.recordJoin(room());
    store.existingRoom("room-id-1");
    store.roomOfEngineKey("http://127.0.0.1:7332/tailcat-relay/one");
    store.subscribe(() => {})();
    const reloaded = new RoomStore({ storage });
    reloaded.recordJoin(room({ id: "room-id-2", secret: "another-secret-value-2" }));
    reloaded.remove("room-id-1");
    expect(reloaded.getSnapshot().rooms).toHaveLength(1);
  });
});
