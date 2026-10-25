import { createHash } from "node:crypto";
import { describe, expect, test } from "vitest";
import type { RoomMember } from "@roboco/proto";
import {
  MAX_INVITE_BYTES,
  MAX_LABEL_BYTES,
  MAX_MEMBERS,
  MAX_SECRET_BYTES,
  ROOM_INVITE_PREFIX,
  RoomInviteError,
  describeRoomSecret,
  encodeRoomInvite,
  parseRoomInvite,
  roomId,
} from "../src/room-invite";

/**
 * The `roboco-room:` grammar as the browser speaks it — the exact mirror of
 * `crates/proto/src/room.rs` (ADR 0011): the frozen fields, the shape rules,
 * the redaction discipline (every error is one of the fixed texts; none
 * ever quotes the invite), and the derived room id.
 */

const SECRET = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";
const ADDRESS = "tcAAAABBBBccccDDDD_eeee-ffff0123";

/**
 * An invite string produced by the RUST encoder for the same fields
 * (`RoomInvite::new("Home fleet", SECRET, …).encode()`) — the
 * cross-implementation pin: the TS codec must decode and re-encode it
 * byte-identically.
 */
const RUST_INVITE =
  "roboco-room:eyJ2IjoxLCJuYW1lIjoiSG9tZSBmbGVldCIsInNlY3JldCI6InFWUzNsRjVpNXZOYmNKVzdEVm9HX2ZXMU5sSzhrQ3F5MzlzMlp3QWhIZk4iLCJtZW1iZXJzIjpbeyJsYWJlbCI6ImRlc2t0b3AiLCJhZGRyZXNzIjoidGNBQUFBQkJCQmNjY2NEREREX2VlZWUtZmZmZjAxMjMifSx7ImxhYmVsIjoic2VydmVyIiwiYWRkcmVzcyI6InRjYW5vdGhlcmFkZHJlc3MxMjM0In1dfQ";

function members(): RoomMember[] {
  return [
    { label: "desktop", address: ADDRESS },
    { label: "server", address: "tcanotheraddress1234" },
  ];
}

function invite(): { v: number; name: string; secret: string; members: RoomMember[] } {
  return { v: 1, name: "Home fleet", secret: SECRET, members: members() };
}

/** Every refusal text the grammar can produce (the Rust display set). */
const FIXED_TEXTS: string[] = [
  "a room invite starts with roboco-room:",
  "room invite payload is not base64url",
  "room invite payload is malformed",
  "room invite grammar version is not 1",
  "room name must be at most 256 bytes with no control characters",
  "room secret must be 1 to 4096 bytes with no control characters",
  "member labels must be at most 256 bytes with no control characters",
  "member addresses must be 1 to 4096 bytes with no whitespace or control characters",
  "room invites carry at most 512 members",
  "room invite could not be encoded",
];

/** Assert a refusal is fixed text that never quotes the invite. */
function expectShapeError(junk: string): RoomInviteError {
  let error: unknown;
  try {
    parseRoomInvite(junk);
  } catch (caught) {
    error = caught;
  }
  expect(error).toBeInstanceOf(RoomInviteError);
  const shapeError = error as RoomInviteError;
  expect(FIXED_TEXTS).toContain(shapeError.message);
  expect(shapeError.message).not.toContain(SECRET);
  expect(shapeError.message).not.toContain(ADDRESS);
  const quoted = junk.trim().slice(ROOM_INVITE_PREFIX.length, 64);
  if (quoted.length > 0) {
    expect(shapeError.message).not.toContain(quoted);
  }
  return shapeError;
}

/** A hand-crafted payload — the encoder can never produce these. */
function payload(json: string): string {
  const bytes = Buffer.from(json, "utf8");
  return `${ROOM_INVITE_PREFIX}${bytes.toString("base64url")}`;
}

describe("room invite codec", () => {
  test("decodes the Rust encoder's invite and re-encodes it byte-identically", () => {
    const decoded = parseRoomInvite(RUST_INVITE);
    expect(decoded.v).toBe(1);
    expect(decoded.name).toBe("Home fleet");
    expect(decoded.secret).toBe(SECRET);
    expect(decoded.members).toEqual(members());
    // The TS encoder emits the same wire bytes (same field order, same
    // compaction): the two codecs agree on one pasteable handle.
    expect(encodeRoomInvite(decoded)).toBe(RUST_INVITE);
  });

  test("round-trips arbitrary rooms and tolerates pasted whitespace", () => {
    const room = invite();
    const encoded = encodeRoomInvite(room);
    expect(encoded.startsWith(ROOM_INVITE_PREFIX)).toBe(true);
    expect(parseRoomInvite(`  ${encoded}\n`)).toEqual(room);
    // Multi-byte names survive (labels are byte-length limited).
    const unicode = parseRoomInvite(
      encodeRoomInvite({ v: 1, name: "Flotte à la maison", secret: SECRET, members: [] }),
    );
    expect(unicode.name).toBe("Flotte à la maison");
  });

  test("the wire fields are exactly the frozen grammar", () => {
    const encoded = encodeRoomInvite(invite());
    const json = JSON.parse(
      Buffer.from(encoded.slice(ROOM_INVITE_PREFIX.length), "base64url").toString("utf8"),
    ) as Record<string, unknown>;
    expect(Object.keys(json)).toEqual(["v", "name", "secret", "members"]);
    expect(json.v).toBe(1);
    expect((json.members as Array<Record<string, unknown>>)[0]).toEqual({
      label: "desktop",
      address: ADDRESS,
    });
  });

  test("refuses junk and wrong grammars with fixed texts", () => {
    for (const junk of [
      "",
      "http://example/pair#token=1",
      "roboco-tailcat:eyJ2IjoxfQ",
      "roboco-room:!!!!",
      "roboco-room:eyJhIjoxfQ",
      "roboco-room",
      "roboco-room:=",
      "roboco-room:eyJ2IjoxfQ=", // padded base64: URL_SAFE_NO_PAD refuses it
    ]) {
      expectShapeError(junk);
    }
    // A non-invite is its own kind.
    expect(expectShapeError("nonsense").kind).toBe("not-an-invite");
    // Not base64.
    expect(expectShapeError(`${ROOM_INVITE_PREFIX}!!!!`).kind).toBe("not-base64");
    // A length%4===1 payload is not base64 either.
    expect(expectShapeError(`${ROOM_INVITE_PREFIX}eyJhY`).kind).toBe("not-base64");
    // JSON that is not the invite object.
    expect(expectShapeError(payload("[1,2]")).kind).toBe("malformed");
    expect(expectShapeError(payload("null")).kind).toBe("malformed");
  });

  test("refuses shape violations without echoing the payload", () => {
    const oversized = "x".repeat(MAX_SECRET_BYTES + 1);
    const oversizedName = "n".repeat(MAX_LABEL_BYTES + 1);
    const oversizedMembers: RoomMember[] = Array.from({ length: MAX_MEMBERS + 1 }, () => ({
      label: "m",
      address: ADDRESS,
    }));
    for (const bad of [
      // empty, oversized, and control-carrying secrets
      payload(`{"v":1,"name":"room","secret":"","members":[]}`),
      payload(`{"v":1,"name":"room","secret":"${oversized}","members":[]}`),
      payload(`{"v":1,"name":"room","secret":"bad\\nsecret","members":[]}`),
      // bad room name
      payload(`{"v":1,"name":"","secret":"${SECRET}","members":[]}`),
      payload(`{"v":1,"name":"${oversizedName}","secret":"${SECRET}","members":[]}`),
      payload(`{"v":1,"name":"bad\\nname","secret":"SECRETTOKEN","members":[]}`),
      // bad member entries
      payload(
        `{"v":1,"name":"room","secret":"SECRETTOKEN","members":[{"label":"ok","address":"has space"}]}`,
      ),
      payload(
        `{"v":1,"name":"room","secret":"SECRETTOKEN","members":[{"label":"bad\\nlabel","address":"tcADDRESS"}]}`,
      ),
      payload(
        `{"v":1,"name":"room","secret":"SECRETTOKEN","members":[{"label":"","address":"tcADDRESS"}]}`,
      ),
      // too many members
      payload(
        `{"v":1,"name":"room","secret":"${SECRET}","members":${JSON.stringify(oversizedMembers)}}`,
      ),
    ]) {
      expectShapeError(bad);
    }
    // Kinds follow room.rs's order: name, secret, member count, entries.
    expect(
      expectShapeError(payload(`{"v":1,"name":"","secret":"${SECRET}","members":[]}`)).kind,
    ).toBe("name");
    expect(
      expectShapeError(payload(`{"v":1,"name":"room","secret":"","members":[]}`)).kind,
    ).toBe("secret");
    expect(
      expectShapeError(
        payload(`{"v":1,"name":"room","secret":"${SECRET}","members":${JSON.stringify(oversizedMembers)}}`),
      ).kind,
    ).toBe("too-many-members");
    expect(
      expectShapeError(
        payload(`{"v":1,"name":"room","secret":"${SECRET}","members":[{"label":"ok","address":" "}]}`),
      ).kind,
    ).toBe("member-address");
    // Wrong grammar version.
    expect(
      expectShapeError(
        payload(`{"v":2,"name":"room","secret":"${SECRET}","members":[]}`),
      ).kind,
    ).toBe("version");
  });

  test("unknown fields are refused (the grammar is exact)", () => {
    expect(
      expectShapeError(
        payload(`{"v":1,"name":"r","secret":"${SECRET}","members":[],"SECRETFIELD":1}`),
      ).kind,
    ).toBe("malformed");
    expect(
      expectShapeError(
        payload(
          `{"v":1,"name":"r","secret":"${SECRET}","members":[{"label":"a","address":"tca","x":1}]}`,
        ),
      ).kind,
    ).toBe("malformed");
    // Missing fields.
    expect(expectShapeError(payload(`{"v":1,"name":"r","secret":"s"}`)).kind).toBe("malformed");
    expect(
      expectShapeError(payload(`{"v":"1","name":"r","secret":"s","members":[]}`)).kind,
    ).toBe("malformed");
  });

  test("oversized invites are refused before any payload is decoded", () => {
    const huge = `${ROOM_INVITE_PREFIX}${"A".repeat(MAX_INVITE_BYTES)}`;
    expect(expectShapeError(huge).kind).toBe("not-base64");
  });

  test("encode validates the same shape rules", () => {
    expect(() => encodeRoomInvite({ ...invite(), v: 2 })).toThrow(RoomInviteError);
    expect(() => encodeRoomInvite({ ...invite(), name: "" })).toThrow(RoomInviteError);
    expect(() => encodeRoomInvite({ ...invite(), secret: "" })).toThrow(RoomInviteError);
    expect(() =>
      encodeRoomInvite({
        ...invite(),
        members: [{ label: "x", address: "has space" }],
      }),
    ).toThrow(RoomInviteError);
    expect(() => encodeRoomInvite({ ...invite(), members: [] })).not.toThrow();
  });

  test("errors and descriptions never echo secrets", () => {
    for (const junk of [
      // Invalid shapes that carry the secret: a bad name (the secret field
      // is validated only after), a bad version, and a bad member entry.
      payload(`{"v":1,"name":"","secret":"SECRETSECRET","members":[]}`),
      payload(`{"v":2,"name":"r","secret":"SECRETSECRET","members":[]}`),
      payload(
        `{"v":1,"name":"r","secret":"SECRETSECRET","members":[{"label":"x","address":" "}]}`,
      ),
      "roboco-room:SECRETSECRET",
    ]) {
      const error = expectShapeError(junk);
      expect(error.message).not.toContain("SECRETSECRET");
      expect(error.stack ?? "").not.toContain("SECRETSECRET");
    }
    expect(describeRoomSecret("SECRETSECRET")).not.toContain("SECRET");
    expect(describeRoomSecret("SECRETSECRET")).toBe("room secret…(12 chars)");
  });
});

describe("roomId", () => {
  test("derives the same domain-tagged id the engine does", () => {
    // roboco_proto::room::room_id: SHA-256 of
    // b"roboco-room-v1\0" + b"id" + b"\0" + secret, base64url (43 chars).
    const expected = createHash("sha256")
      .update(Buffer.concat([Buffer.from("roboco-room-v1\0id\0", "utf8"), Buffer.from(SECRET, "utf8")]))
      .digest("base64url");
    expect(roomId(SECRET)).toBe(expected);
    expect(roomId(SECRET)).toHaveLength(43);
  });

  test("is deterministic, opaque, and secret-divergent", () => {
    expect(roomId(SECRET)).toBe(roomId(SECRET));
    expect(roomId(SECRET)).not.toBe(roomId("another-secret"));
    expect(roomId(SECRET)).not.toContain(SECRET);
  });
});
