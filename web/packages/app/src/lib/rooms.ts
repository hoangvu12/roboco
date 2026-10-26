import { ROOMS_V1 } from "@roboco/proto";
import {
  RoomInviteError,
  parseRoomInvite,
  redeemRoomMemberViaOrigin,
  roomId,
  RpcError,
  type ParsedRoomInvite,
} from "@roboco/engine-client";
import type { EngineRegistrySnapshot } from "@roboco/engine-client";
import { directOriginEngineKey, type EngineStore, type FleetState, type StoredEngine } from "./engine-store";
import { RoomStore, type RoomsState, type StoredRoom, type StoredRoomMember } from "./room-store";

/**
 * The browser join flow (ADR 0011's web path, rooms#05): paste the
 * `roboco-room:` invite once; every member is redeemed through the ORIGIN
 * engine — the one serving the page — which relays the presentation and
 * answers with that member's grant on a `/tailcat-relay/{id}` base. Each
 * landed member is an ordinary engine (the fleet store's relayed shape,
 * supervised and listed like any other); a member that is offline fails
 * ALONE — a label-shaped error, the rest join — and stays in the room
 * record as an unjoined member so the engines UI can show it offline.
 *
 * The invite is a secret (ADR 0008): it travels exactly the redeem requests
 * and the room record; nothing here logs it, its secret, or a member
 * address, and failure messages are label-shaped at worst. The stores are
 * injected (the route passes the app singletons; tests pass fresh ones).
 */

/** One member that landed: its label plus the stored engine now riding it. */
export interface RoomJoinSuccess {
  readonly label: string;
  readonly engine: StoredEngine;
}

/** One member that failed: its label and a label-only message. */
export interface RoomJoinFailure {
  readonly label: string;
  readonly message: string;
}

export interface RoomJoinResult {
  readonly room: StoredRoom;
  readonly joined: readonly RoomJoinSuccess[];
  readonly failed: readonly RoomJoinFailure[];
}

export interface JoinRoomOptions {
  /** The engine serving the page; defaults to `window.location.origin`. */
  readonly originBaseUrl?: string;
  readonly fetch?: typeof fetch;
  /** The fleet store to land engines in (the route passes the singleton). */
  readonly fleet: EngineStore;
  /** The room store to record the room in (the route passes the singleton). */
  readonly rooms: RoomStore;
  readonly now?: () => number;
}

/** One member's failure text: capped, single-line, label-shaped at worst. */
function failureMessage(error: unknown, label: string): string {
  if (
    error instanceof Error &&
    error.message.length > 0 &&
    error.message.length <= 256 &&
    !error.message.includes("\n")
  ) {
    // The engine's refusals already name the label and nothing else; local
    // transport texts are fixed sentences.
    return error.message;
  }
  return `could not reach the room member "${label}"`;
}

/** The first entry per label: the origin engine dials the first match, so the join does too. */
function membersByLabel(invite: ParsedRoomInvite): { label: string; address: string }[] {
  const seen = new Set<string>();
  const members: { label: string; address: string }[] = [];
  for (const member of invite.members) {
    if (!seen.has(member.label)) {
      seen.add(member.label);
      members.push({ label: member.label, address: member.address });
    }
  }
  return members;
}

/**
 * Join a room: parse the invite, redeem every member through the origin
 * engine, land the grants as ordinary engines, and record the room. One
 * member's failure never blocks the others; the room is recorded with the
 * failed members unjoined (their labels surface offline in the engines UI).
 * Throws `RoomInviteError` (fixed text) when the paste is not a room invite,
 * and refuses while either persisted store is damaged (the fleet's rule).
 */
export async function joinRoom(
  invite: string,
  deviceLabel: string,
  options: JoinRoomOptions,
): Promise<RoomJoinResult> {
  const trimmed = invite.trim();
  const parsed = parseRoomInvite(trimmed);
  const origin =
    options.originBaseUrl ??
    (typeof window !== "undefined" && window.location.origin.length > 0 ? window.location.origin : null);
  if (origin === null) {
    throw new RpcError("transport", "Room pairing needs the engine serving this page");
  }
  if (options.fleet.getSnapshot().configurationError !== null) {
    throw new Error("Saved engine configuration needs repair before pairing");
  }
  if (options.rooms.getSnapshot().configurationError !== null) {
    throw new Error("Saved room configuration needs repair before joining");
  }

  const now = options.now ?? Date.now;
  const id = roomId(parsed.secret);
  const existing = options.rooms.existingRoom(id);
  const members = membersByLabel(parsed);
  const failures: RoomJoinFailure[] = [];
  const landed: RoomJoinSuccess[] = [];

  // One redeem per member, in parallel — each is an independent dial and
  // presentation; a slow or sleeping member must not serialize the rest.
  await Promise.all(
    members.map(async (member) => {
      try {
        const grant = await redeemRoomMemberViaOrigin(trimmed, member.label, deviceLabel, {
          originBaseUrl: origin,
          fetch: options.fetch,
        });
        // The first landed member becomes the active engine (the pairing
        // UX's landing); the rest land beside it without stealing focus.
        const engine = options.fleet.landRelayedGrant(grant, { active: landed.length === 0 });
        landed.push({ label: member.label, engine });
      } catch (error) {
        failures.push({ label: member.label, message: failureMessage(error, member.label) });
      }
    }),
  );

  // Every redeem mints a FRESH relay base: a re-joined member's previous
  // engine key is now a dead relay — forget it so the fleet lists the
  // member exactly once. A member whose redeem failed keeps its previous
  // key (that relay is still alive and its engine entry still works).
  const newKeys = new Map(landed.map((entry) => [entry.label, entry.engine.baseUrl]));
  if (existing !== null) {
    for (const member of existing.members) {
      const replacement = newKeys.get(member.label);
      if (member.engineKey !== null && replacement !== undefined && replacement !== member.engineKey) {
        options.fleet.remove(member.engineKey);
      }
    }
  }

  const recordedMembers: StoredRoomMember[] = members.map((member) => ({
    label: member.label,
    address: member.address,
    engineKey: newKeys.get(member.label) ?? existingMemberKey(existing, member.label),
  }));
  const room: StoredRoom = {
    id,
    name: parsed.name,
    secret: parsed.secret,
    origin,
    joinedAt: now(),
    members: recordedMembers,
  };
  options.rooms.recordJoin(room);
  return { room, joined: landed, failed: failures };
}

function existingMemberKey(existing: StoredRoom | null, label: string): string | null {
  return existing?.members.find((member) => member.label === label)?.engineKey ?? null;
}

// ── UI wiring (pure) ───────────────────────────────────────────────────────

/**
 * True when the engine SERVING THIS PAGE advertises `rooms-v1`: the direct
 * origin engine's live `EngineInfo`. No paired origin engine, or one that
 * never connected, hides the room UI — version skew degrades to absence,
 * never an error toast (ADR 0011).
 */
export function originSupportsRooms(
  fleet: FleetState,
  registry: EngineRegistrySnapshot,
  pageOrigin: string,
): boolean {
  const key = directOriginEngineKey(fleet, pageOrigin);
  if (key === null) {
    return false;
  }
  const entry = registry.engines.find((candidate) => candidate.key === key);
  return (entry?.info?.capabilities ?? []).includes(ROOMS_V1);
}

/** The room membership behind a fleet engine key (display wiring). */
export interface EngineRoomMembership {
  readonly label: string;
  readonly roomName: string;
}

/** The room member an engine key belongs to, when any (display wiring). */
export function roomMemberOfEngine(rooms: RoomsState, engineKey: string): EngineRoomMembership | null {
  for (const room of rooms.rooms) {
    const member = room.members.find((candidate) => candidate.engineKey === engineKey);
    if (member !== undefined) {
      return { label: member.label, roomName: room.name };
    }
  }
  return null;
}

/** One room member the engines UI lists as offline. */
export interface OfflineRoomMemberRow {
  /** Stable row key. */
  readonly key: string;
  readonly label: string;
  readonly roomName: string;
}

/**
 * The room members that show as OFFLINE engines: their engine key is not in
 * the fleet (the redeem failed, the member was never joined, or its engine
 * was forgotten). Members whose engines are stored are the registry's rows
 * — the existing engine list; this is the invite's member list filling in
 * the gaps (the room is the book, ticket 02's deviation note).
 */
export function offlineRoomMembers(rooms: RoomsState, fleet: FleetState): OfflineRoomMemberRow[] {
  const known = new Set(fleet.engines.map((engine) => engine.baseUrl));
  const rows: OfflineRoomMemberRow[] = [];
  for (const room of rooms.rooms) {
    for (const member of room.members) {
      if (member.engineKey === null || !known.has(member.engineKey)) {
        rows.push({ key: `${room.id}:${member.label}`, label: member.label, roomName: room.name });
      }
    }
  }
  return rows;
}

/**
 * The join outcome as one user-facing sentence: which members joined, which
 * labels failed and why (label-only texts). The room name is not a secret;
 * the message never carries the secret or any address.
 */
export function describeRoomJoinResult(result: RoomJoinResult): string {
  const total = result.room.members.length;
  const joined = result.joined.length;
  if (result.failed.length === 0) {
    return `Joined ${result.room.name}: ${joined} of ${total} member${total === 1 ? "" : "s"} now reachable.`;
  }
  const failures = result.failed.map((failure) => `${failure.label} — ${failure.message}`);
  const prefix =
    joined > 0
      ? `Joined ${result.room.name}: ${joined} of ${total} members.`
      : `${result.room.name}: no members joined.`;
  return `${prefix} Offline: ${failures.join("; ")}.`;
}

/**
 * A join refusal as a user-facing sentence: the invite's fixed shape texts
 * pass through; anything else collapses to a generic retry hint. Never the
 * invite itself.
 */
export function describeRoomJoinError(error: unknown): string {
  if (error instanceof RoomInviteError) {
    return `That is not a room invite: ${error.message}`;
  }
  if (error instanceof Error && error.message.length > 0) {
    return error.message;
  }
  return "Joining that room failed.";
}
