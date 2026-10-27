import type { StorageLike } from "./engine-store";

/**
 * The browser's joined-room records (ADR 0011): the room secret plus the
 * invite's member list, persisted in browser storage scoped to the serving
 * origin — the same secret-at-rest class as a session credential. The room
 * is hostless, so this record IS the client's copy of the book; engines hold
 * only verifiers. Nothing here logs the secret or the member addresses (both
 * are ADR 0008 secrets); the codec is lenient on read (unknown keys are
 * ignored) so a future room record never breaks an older load.
 */

/** One room member as the web client persists it. */
export interface StoredRoomMember {
  /** The member engine's label — the invite's address-book key. */
  readonly label: string;
  /** The member's transport address (a secret: stored, never logged). */
  readonly address: string;
  /**
   * The fleet engine key backing this member once joined — the
   * `/tailcat-relay/{id}` base the origin engine minted. Null while the
   * member is offline (its redeem failed or it was never joined); a stale
   * key whose engine was forgotten reads offline the same way.
   */
  engineKey: string | null;
}

/** One joined room. */
export interface StoredRoom {
  /** The deterministic room id (`roomId` of the secret). */
  readonly id: string;
  readonly name: string;
  /** The room's shared secret — same at-rest class as a session credential. */
  readonly secret: string;
  /** The origin that redeemed the room (the page origin). */
  readonly origin: string;
  readonly joinedAt: number;
  readonly members: readonly StoredRoomMember[];
}

export interface RoomsState {
  readonly rooms: readonly StoredRoom[];
  /**
   * Set when the persisted room list (`roboco.rooms.v1`) failed to parse:
   * joining refuses until it is repaired, and the damaged bytes are
   * preserved untouched (the fleet store's rule).
   */
  readonly configurationError: string | null;
}

export interface RoomStoreOptions {
  readonly storage?: StorageLike;
  readonly now?: () => number;
}

interface PersistedRooms {
  readonly version: 1;
  readonly rooms: readonly StoredRoom[];
}

const STORAGE_KEY = "roboco.rooms.v1";
const EMPTY: RoomsState = { rooms: [], configurationError: null };
const CONFIGURATION_ERROR =
  "Saved room configuration could not be read. The stored value is preserved; joining a room is unavailable until it is repaired.";

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

function defaultStorage(): StorageLike {
  const candidate = (globalThis as { localStorage?: StorageLike }).localStorage;
  return candidate ?? memoryStorage();
}

function isMemberShape(entry: unknown): entry is StoredRoomMember {
  if (typeof entry !== "object" || entry === null) {
    return false;
  }
  const member = entry as Record<string, unknown>;
  return (
    typeof member.label === "string" &&
    typeof member.address === "string" &&
    (member.engineKey === null || member.engineKey === undefined || typeof member.engineKey === "string")
  );
}

/** Lenient read: exact-field rooms pass, unknown keys are dropped, junk rows are skipped. */
function parseRoom(entry: unknown): StoredRoom | null {
  if (typeof entry !== "object" || entry === null) {
    return null;
  }
  const room = entry as Record<string, unknown>;
  if (
    typeof room.id !== "string" ||
    typeof room.name !== "string" ||
    typeof room.secret !== "string" ||
    typeof room.origin !== "string" ||
    typeof room.joinedAt !== "number" ||
    !Array.isArray(room.members)
  ) {
    return null;
  }
  // Unknown keys at every level are ignored: a future room record never
  // breaks this load.
  const members = room.members.filter(isMemberShape).map((member) => ({
    label: member.label,
    address: member.address,
    engineKey: typeof member.engineKey === "string" ? member.engineKey : null,
  }));
  return { id: room.id, name: room.name, secret: room.secret, origin: room.origin, joinedAt: room.joinedAt, members };
}

export class RoomStore {
  readonly #storage: StorageLike;
  readonly #now: () => number;
  #state: RoomsState = EMPTY;
  #configurationError: string | null = null;
  readonly #listeners = new Set<() => void>();

  constructor(options: RoomStoreOptions = {}) {
    this.#storage = options.storage ?? defaultStorage();
    this.#now = options.now ?? Date.now;
    this.#load();
  }

  getSnapshot(): RoomsState {
    return this.#configurationError === null
      ? this.#state
      : { ...this.#state, configurationError: this.#configurationError };
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  /**
   * Record a join (or re-join): the whole room — the secret, the invite's
   * full member list, and the engine key each landed member now rides.
   * Re-joining an existing id replaces the record, keeping the first
   * join time.
   */
  recordJoin(room: StoredRoom): void {
    if (this.#configurationError !== null) {
      throw new Error("Saved room configuration needs repair before joining");
    }
    const others = this.#state.rooms.filter((entry) => entry.id !== room.id);
    const existing = this.#state.rooms.find((entry) => entry.id === room.id);
    const recorded: StoredRoom = existing === undefined ? room : { ...room, joinedAt: existing.joinedAt };
    this.#setState({ rooms: [...others, recorded].sort((a, b) => a.id.localeCompare(b.id)), configurationError: null });
  }

  /** Forget one room record (its member engines are the fleet's business). */
  remove(id: string): void {
    if (this.#configurationError !== null) {
      return;
    }
    const rooms = this.#state.rooms.filter((entry) => entry.id !== id);
    if (rooms.length === this.#state.rooms.length) {
      return;
    }
    this.#setState({ rooms, configurationError: null });
  }

  /** The recorded room for a member label — the join flow's stale-key source. */
  existingRoom(id: string): StoredRoom | null {
    return this.#state.rooms.find((entry) => entry.id === id) ?? null;
  }

  /** The joined room a fleet engine key belongs to (display wiring). */
  roomOfEngineKey(engineKey: string): StoredRoom | null {
    return (
      this.#state.rooms.find((room) => room.members.some((member) => member.engineKey === engineKey)) ?? null
    );
  }

  #load(): void {
    const raw = this.#storage.getItem(STORAGE_KEY);
    if (raw === null) {
      return;
    }
    try {
      const parsed = JSON.parse(raw) as PersistedRooms;
      if (parsed.version === 1 && Array.isArray(parsed.rooms)) {
        // Lenient: unknown keys and junk rows are dropped, valid rooms load.
        const rooms = parsed.rooms
          .map(parseRoom)
          .filter((room): room is StoredRoom => room !== null);
        this.#state = { rooms, configurationError: null };
        return;
      }
      // Wrong-shape payload: treat as damaged, preserving the bytes.
    } catch {
      // Unparseable payload: damaged — the stored bytes stay untouched.
    }
    this.#state = { rooms: [], configurationError: null };
    this.#configurationError = CONFIGURATION_ERROR;
  }

  #persist(): void {
    if (this.#configurationError !== null) {
      // Persisting a healthy list over damaged bytes would silently destroy
      // what the user needs to repair — refuse instead.
      return;
    }
    if (this.#state.rooms.length === 0) {
      this.#storage.removeItem(STORAGE_KEY);
      return;
    }
    const persisted: PersistedRooms = { version: 1, ...this.#state };
    this.#storage.setItem(STORAGE_KEY, JSON.stringify(persisted));
  }

  #setState(state: RoomsState): void {
    if (state.rooms === this.#state.rooms && state.configurationError === this.#state.configurationError) {
      return;
    }
    this.#state = state;
    this.#persist();
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

export { STORAGE_KEY as ROOMS_STORAGE_KEY };
