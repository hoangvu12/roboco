import type { RoomMember } from "@roboco/proto";
import { RpcError } from "./rpc-error";

/**
 * The `roboco-room:` invite grammar, mirroring `crates/proto/src/room.rs`
 * (ADR 0011) so the browser and the engine agree on one pasteable shape:
 * `roboco-room:` + base64url-no-pad(JSON
 * `{"v":1,"name":"…","secret":"…","members":[{"label":"…","address":"…"}]}`).
 *
 * An invite carries the room's shared secret, and member addresses are
 * transport secrets (ADR 0008): every error is one of the fixed texts
 * below — none ever quotes the invite, the secret, or an address — and
 * nothing here logs either. The secret is stored by the caller with the
 * same care as a session credential and is sent nowhere except the
 * origin engine's redeem call.
 */

/** Prefix of a room invite carrying the room secret plus its address book. */
export const ROOM_INVITE_PREFIX = "roboco-room:";

/** The only grammar version (frozen at first ship, ADR 0005). */
export const ROOM_INVITE_VERSION = 1;

/** The longest encoded invite accepted. */
export const MAX_INVITE_BYTES = 65_536;

/** The longest room secret accepted (invites carry it verbatim). */
export const MAX_SECRET_BYTES = 4_096;

/** The longest member address accepted (the tailcat address grammar). */
export const MAX_MEMBER_ADDRESS_BYTES = 4_096;

/** The most members one invite may carry. */
export const MAX_MEMBERS = 512;

/** The longest label (room name, member label, presenter label). */
export const MAX_LABEL_BYTES = 256;

/** Why an invite was refused. Each kind maps to one fixed message. */
export type RoomInviteErrorKind =
  | "not-an-invite"
  | "not-base64"
  | "malformed"
  | "version"
  | "name"
  | "secret"
  | "member-label"
  | "member-address"
  | "too-many-members"
  | "encode";

/** The fixed refusal texts (byte-equal to the Rust `RoomInviteError` display). */
const ROOM_INVITE_ERROR_TEXT: Record<RoomInviteErrorKind, string> = {
  "not-an-invite": "a room invite starts with roboco-room:",
  "not-base64": "room invite payload is not base64url",
  malformed: "room invite payload is malformed",
  version: "room invite grammar version is not 1",
  name: "room name must be at most 256 bytes with no control characters",
  secret: "room secret must be 1 to 4096 bytes with no control characters",
  "member-label": "member labels must be at most 256 bytes with no control characters",
  "member-address":
    "member addresses must be 1 to 4096 bytes with no whitespace or control characters",
  "too-many-members": "room invites carry at most 512 members",
  encode: "room invite could not be encoded",
};

/** A shape-only refusal: the message is fixed text and never quotes the input. */
export class RoomInviteError extends Error {
  readonly kind: RoomInviteErrorKind;

  constructor(kind: RoomInviteErrorKind) {
    super(ROOM_INVITE_ERROR_TEXT[kind]);
    this.name = "RoomInviteError";
    this.kind = kind;
  }
}

/** The parsed invite: exactly the frozen grammar fields, nothing else. */
export interface ParsedRoomInvite {
  readonly v: number;
  readonly name: string;
  /** The room's shared secret (ADR 0008: never logged, never in URLs). */
  readonly secret: string;
  readonly members: readonly RoomMember[];
}

/** Shape-only description of a secret, safe to log. */
export function describeRoomSecret(secret: string): string {
  return `room secret…(${secret.length} chars)`;
}

// ── shape rules (mirror room.rs's validators) ──────────────────────────────

const CONTROLS = /[\u0000-\u001f\u007f-\u009f]/;

function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

function validLabel(value: string): boolean {
  return value.length > 0 && byteLength(value) <= MAX_LABEL_BYTES && !CONTROLS.test(value);
}

function validSecret(value: string): boolean {
  return value.length > 0 && byteLength(value) <= MAX_SECRET_BYTES && !CONTROLS.test(value);
}

const ADDRESS_FORBIDDEN = /[\s\u0000-\u001f\u007f-\u009f]/;

function validMemberAddress(value: string): boolean {
  return (
    value.length > 0 &&
    byteLength(value) <= MAX_MEMBER_ADDRESS_BYTES &&
    !ADDRESS_FORBIDDEN.test(value)
  );
}

function validMember(member: RoomMember): "member-label" | "member-address" | null {
  if (!validLabel(member.label)) {
    return "member-label";
  }
  if (!validMemberAddress(member.address)) {
    return "member-address";
  }
  return null;
}

// ── base64url (no pad, strict) ─────────────────────────────────────────────

const BASE64URL = /^[A-Za-z0-9_-]+$/;

function base64UrlDecode(payload: string): Uint8Array {
  // URL_SAFE_NO_PAD rejects padding and every non-alphabet byte; atob is
  // forgiving about whitespace, so the alphabet is checked first.
  if (payload.length === 0 || !BASE64URL.test(payload) || payload.length % 4 === 1) {
    throw new RoomInviteError("not-base64");
  }
  const normalized = payload.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  try {
    const binary = atob(padded);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      out[i] = binary.charCodeAt(i);
    }
    return out;
  } catch {
    throw new RoomInviteError("not-base64");
  }
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

// ── the codec ──────────────────────────────────────────────────────────────

/**
 * Decode a pasted `roboco-room:` invite. Strict shape: exactly the frozen
 * fields (`v`, `name`, `secret`, `members` — unknown keys are refused, as
 * the Rust `deny_unknown_fields` is), every length/control rule from
 * `room.rs` enforced in the same order. Errors are the fixed texts only.
 */
export function parseRoomInvite(input: string): ParsedRoomInvite {
  const trimmed = input.trim();
  if (!trimmed.startsWith(ROOM_INVITE_PREFIX)) {
    throw new RoomInviteError("not-an-invite");
  }
  const payload = trimmed.slice(ROOM_INVITE_PREFIX.length);
  // Length is shape, not content: an oversized invite is refused without
  // any of its bytes reaching an error message.
  if (trimmed.length > MAX_INVITE_BYTES) {
    throw new RoomInviteError("not-base64");
  }
  const bytes = base64UrlDecode(payload);
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new RoomInviteError("malformed");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new RoomInviteError("malformed");
  }
  const record = parsed as Record<string, unknown>;
  if (
    Object.keys(record).length !== 4 ||
    typeof record.v !== "number" ||
    !Number.isInteger(record.v) ||
    record.v < 0 ||
    record.v > 0xFFFF_FFFF ||
    typeof record.name !== "string" ||
    typeof record.secret !== "string" ||
    !Array.isArray(record.members)
  ) {
    throw new RoomInviteError("malformed");
  }
  const members: RoomMember[] = [];
  for (const entry of record.members) {
    if (
      typeof entry !== "object" ||
      entry === null ||
      Array.isArray(entry) ||
      Object.keys(entry).length !== 2 ||
      typeof (entry as Record<string, unknown>).label !== "string" ||
      typeof (entry as Record<string, unknown>).address !== "string"
    ) {
      throw new RoomInviteError("malformed");
    }
    members.push(entry as RoomMember);
  }
  if (record.v !== ROOM_INVITE_VERSION) {
    throw new RoomInviteError("version");
  }
  if (!validLabel(record.name)) {
    throw new RoomInviteError("name");
  }
  if (!validSecret(record.secret)) {
    throw new RoomInviteError("secret");
  }
  if (members.length > MAX_MEMBERS) {
    throw new RoomInviteError("too-many-members");
  }
  for (const member of members) {
    const invalid = validMember(member);
    if (invalid !== null) {
      throw new RoomInviteError(invalid);
    }
  }
  return { v: record.v, name: record.name, secret: record.secret, members };
}

/**
 * Validate and build a v1 invite string (the mirror of `RoomInvite::encode`
 * — same field order, so the same room encodes byte-identically in Rust and
 * TypeScript). The web client uses it to round-trip tests and conformance
 * fixtures; the secret rides the result, which stays a secret.
 */
export function encodeRoomInvite(invite: ParsedRoomInvite): string {
  if (invite.v !== ROOM_INVITE_VERSION) {
    throw new RoomInviteError("version");
  }
  if (!validLabel(invite.name)) {
    throw new RoomInviteError("name");
  }
  if (!validSecret(invite.secret)) {
    throw new RoomInviteError("secret");
  }
  if (invite.members.length > MAX_MEMBERS) {
    throw new RoomInviteError("too-many-members");
  }
  for (const member of invite.members) {
    const invalid = validMember(member);
    if (invalid !== null) {
      throw new RoomInviteError(invalid);
    }
  }
  const payload = JSON.stringify({
    v: invite.v,
    name: invite.name,
    secret: invite.secret,
    members: invite.members.map((member) => ({ label: member.label, address: member.address })),
  });
  const encoded = ROOM_INVITE_PREFIX + base64UrlEncode(new TextEncoder().encode(payload));
  if (encoded.length > MAX_INVITE_BYTES) {
    throw new RoomInviteError("encode");
  }
  return encoded;
}

/**
 * The deterministic room id: a domain-tagged SHA-256 of the secret,
 * base64url-encoded (43 chars) — the exact construction of
 * `roboco_proto::room::room_id`, so every engine and client holding the
 * same secret derives the same id. An id is a public handle and never
 * reveals the secret it hashes. Sync (a compact SHA-256, not
 * `crypto.subtle`, so plain-HTTP origins derive it too).
 */
export function roomId(secret: string): string {
  // `roboco-room-v1\0"id"\0` + secret — room.rs's `tagged_digest`
  // domain tag, byte for byte.
  const tagged = new TextEncoder().encode(
    `roboco-room-v1\u0000id\u0000${secret}`,
  );
  return base64UrlEncode(sha256(tagged));
}

// ── SHA-256 (compact, sync; the only digest the web client needs) ──────────

const SHA256_K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

function sha256(message: Uint8Array): Uint8Array {
  const h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  const bitLength = message.length * 8;
  const paddedLength = (((message.length + 8) >> 6) + 1) << 6;
  const block = new Uint8Array(paddedLength);
  block.set(message);
  block[message.length] = 0x80;
  const view = new DataView(block.buffer);
  view.setUint32(paddedLength - 4, bitLength >>> 0, false);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x1_0000_0000), false);
  const w = new Uint32Array(64);
  for (let start = 0; start < paddedLength; start += 64) {
    for (let i = 0; i < 16; i += 1) {
      w[i] = view.getUint32(start + i * 4, false);
    }
    for (let i = 16; i < 64; i += 1) {
      const a = w[i - 15]!;
      const b = w[i - 2]!;
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[i] = (w[i - 16]! + s0 + w[i - 7]! + s1) >>> 0;
    }
    let a = h[0]!;
    let b = h[1]!;
    let c = h[2]!;
    let d = h[3]!;
    let e = h[4]!;
    let f = h[5]!;
    let g = h[6]!;
    let hh = h[7]!;
    for (let i = 0; i < 64; i += 1) {
      const s1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const temp1 = (hh + s1 + ch + SHA256_K[i]! + w[i]!) >>> 0;
      const s0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (s0 + maj) >>> 0;
      hh = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }
    h[0] = (h[0]! + a) >>> 0;
    h[1] = (h[1]! + b) >>> 0;
    h[2] = (h[2]! + c) >>> 0;
    h[3] = (h[3]! + d) >>> 0;
    h[4] = (h[4]! + e) >>> 0;
    h[5] = (h[5]! + f) >>> 0;
    h[6] = (h[6]! + g) >>> 0;
    h[7] = (h[7]! + hh) >>> 0;
  }
  const out = new Uint8Array(32);
  const outView = new DataView(out.buffer);
  for (let i = 0; i < 8; i += 1) {
    outView.setUint32(i * 4, h[i]!, false);
  }
  return out;
}

export { sha256 };
