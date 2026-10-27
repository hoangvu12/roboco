import { createServer, type Server } from "node:http";
import { afterEach, describe, expect, test } from "vitest";
import type { AddressInfo } from "node:net";
import { RpcError } from "../src/rpc-error";
import { encodeRoomInvite, parseRoomInvite } from "../src/room-invite";
import { redeemRoomMemberViaOrigin } from "../src/room-redeem";

/**
 * The room-redeem codec against a scripted fake origin engine (ADR 0011's
 * browser join path): the request shape the engine's
 * `POST /pairing/room-redeem` route validates — `{invite, target, label}` as
 * JSON, exactly those keys — and every reply shape it can answer: the
 * member's grant with `baseUrl` rewritten to `/tailcat-relay/{id}`, plus the
 * refusal statuses (400/401/404/429/502) with their label-only error texts.
 * The engine-side rig (`crates/engine/tests/room_relay.rs`) covers the real
 * dial and byte pump; here the wire half is pinned.
 */

const SECRET = "qVS3lF5i5vNbcJW7DVoG_fW1NlK8kCqy39s2ZwAhHfN";

const INVITE = encodeRoomInvite({
  v: 1,
  name: "Home fleet",
  secret: SECRET,
  members: [
    { label: "server", address: "tcAAAABBBBccccDDDD_eeee-ffff0123" },
    { label: "desktop", address: "tcanotheraddress1234" },
  ],
});

interface ReceivedRequest {
  readonly url: string;
  readonly method: string;
  readonly contentType: string | null;
  readonly body: Record<string, unknown>;
}

type Responder = (
  request: ReceivedRequest,
  reply: (status: number, body: string, headers?: Record<string, string>) => void,
) => void;

/** The scripted fake origin engine: one responder per test. */
class FakeOrigin {
  #server: Server | null = null;
  #url = "";
  readonly received: ReceivedRequest[] = [];
  responder: Responder = (_request, reply) => reply(404, "not found");

  get url(): string {
    return this.#url;
  }

  listen(): Promise<void> {
    return new Promise((resolve, reject) => {
      const server = createServer((request, response) => {
        const chunks: Buffer[] = [];
        request.on("data", (chunk: Buffer) => chunks.push(chunk));
        request.on("error", () => {
          response.destroy();
        });
        request.on("end", () => {
          let body: Record<string, unknown> = {};
          try {
            body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
          } catch {
            body = { unparseable: Buffer.concat(chunks).toString("utf8") };
          }
          const received: ReceivedRequest = {
            url: request.url ?? "",
            method: request.method ?? "",
            contentType: request.headers["content-type"] ?? null,
            body,
          };
          this.received.push(received);
          this.responder(received, (status, responseBody, headers) => {
            response.writeHead(status, { "content-type": "application/json", ...headers });
            response.end(responseBody);
          });
        });
      });
      server.on("error", reject);
      server.listen(0, "127.0.0.1", () => {
        this.#server = server;
        const address = server.address() as AddressInfo;
        this.#url = `http://127.0.0.1:${address.port}`;
        resolve();
      });
    });
  }

  close(): void {
    this.#server?.close();
    this.#server = null;
  }
}

const origins: FakeOrigin[] = [];

afterEach(() => {
  for (const origin of origins.splice(0)) {
    origin.close();
  }
});

function fakeOrigin(responder: Responder): FakeOrigin {
  const origin = new FakeOrigin();
  origin.responder = responder;
  origins.push(origin);
  return origin;
}

/** The engine's exact grant reply (room_relay.rs): session + credential + relay base. */
function grantReply(origin: FakeOrigin, relayId: string, label = "Conformance web"): string {
  return JSON.stringify({
    credential: `cred-${relayId}`,
    session: {
      id: `session-${relayId}`,
      label,
      createdAt: 1_800_000_000_000,
      lastSeen: 1_800_000_000_000,
      revokedAt: null,
    },
    baseUrl: `${origin.url}/tailcat-relay/${relayId}`,
  });
}

describe("redeemRoomMemberViaOrigin", () => {
  test("posts { invite, target, label } and lands the relayed grant", async () => {
    const origin = fakeOrigin((request, reply) => {
      if (request.url === "/pairing/room-redeem" && request.method === "POST") {
        reply(200, grantReply(origin, "11111111-2222-3333-4444-555555555555"));
        return;
      }
      reply(404, JSON.stringify({ error: "not found" }));
    });
    await origin.listen();

    const grant = await redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web on Linux", {
      originBaseUrl: origin.url,
    });
    expect(grant.credential).toBe("cred-11111111-2222-3333-4444-555555555555");
    expect(grant.session.id).toBe("session-11111111-2222-3333-4444-555555555555");
    expect(grant.session.label).toBe("Conformance web");
    expect(grant.baseUrl).toBe(`${origin.url}/tailcat-relay/11111111-2222-3333-4444-555555555555`);

    // The request is exactly the engine's `{ invite, target, label }` shape.
    expect(origin.received).toHaveLength(1);
    expect(origin.received[0]!.url).toBe("/pairing/room-redeem");
    expect(origin.received[0]!.contentType).toBe("application/json");
    expect(origin.received[0]!.body).toEqual({
      invite: INVITE,
      target: "server",
      label: "Roboco web on Linux",
    });
  });

  test("carries each error status with its label-only engine text", async () => {
    const cases: Array<{
      status: number;
      body: string;
      kind: string;
      message: string;
    }> = [
      {
        status: 404,
        body: JSON.stringify({ error: 'the invite has no member labeled "laptop"' }),
        kind: "failed",
        message: 'the invite has no member labeled "laptop"',
      },
      {
        status: 502,
        body: JSON.stringify({ error: 'could not reach the room member "ghost" through Tailcat' }),
        kind: "transport",
        message: 'could not reach the room member "ghost" through Tailcat',
      },
      {
        status: 502,
        body: JSON.stringify({ error: 'could not dial the room member "ghost"' }),
        kind: "transport",
        message: 'could not dial the room member "ghost"',
      },
      {
        status: 401,
        body: JSON.stringify({ error: 'the room member "ghost" refused the presentation' }),
        kind: "failed",
        message: 'the room member "ghost" refused the presentation',
      },
      {
        status: 429,
        body: JSON.stringify({
          error: 'the room member "ghost" is throttling presentations; try again later',
        }),
        kind: "transport",
        message: 'the room member "ghost" is throttling presentations; try again later',
      },
      {
        status: 400,
        body: JSON.stringify({ error: "a room invite starts with roboco-room:" }),
        kind: "failed",
        message: "a room invite starts with roboco-room:",
      },
    ];
    for (const { status, body, kind, message } of cases) {
      const origin = fakeOrigin((_request, reply) => reply(status, body));
      await origin.listen();
      const error = await redeemRoomMemberViaOrigin(INVITE, "ghost", "Roboco web", {
        originBaseUrl: origin.url,
      }).then(
        () => null,
        (caught: unknown) => caught,
      );
      expect(error).toBeInstanceOf(RpcError);
      const rpc = error as RpcError;
      expect(rpc.kind).toBe(kind);
      expect(rpc.message).toBe(message);
      // Label-only: the failure never quotes the invite, its secret, or a
      // member address.
      expect(rpc.message).not.toContain(SECRET);
      expect(rpc.message).not.toContain("tcAAAABBBB");
      expect(rpc.message).not.toContain(INVITE);
    }
  });

  test("collapses non-JSON and suspicious bodies to the status, never their bytes", async () => {
    for (const [status, body] of [
      [400, "expected JSON { invite, target, label }"],
      [502, "invalid presentation response"],
      [500, "<html>whoops</html>"],
      // An echo attempt: the error text carries the invite (or its secret)
      // and must not surface.
      [502, JSON.stringify({ error: INVITE })],
      [502, JSON.stringify({ error: SECRET })],
      // Oversized and multi-line texts are dropped too.
      [502, JSON.stringify({ error: `${"x".repeat(300)}` })],
      [502, JSON.stringify({ error: "line one\nline two" })],
    ] as Array<[number, string]>) {
      const origin = fakeOrigin((_request, reply) => reply(status, body));
      await origin.listen();
      const error = await redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web", {
        originBaseUrl: origin.url,
      }).then(
        () => null,
        (caught: unknown) => caught,
      );
      expect(error).toBeInstanceOf(RpcError);
      const rpc = error as RpcError;
      expect(rpc.message).toBe(`Room pairing returned HTTP ${status}`);
      expect(rpc.message).not.toContain(SECRET);
      expect(rpc.message).not.toContain(INVITE);
      expect(rpc.message).not.toContain("whoops");
    }
  });

  test("refuses malformed grants and unreachable origins", async () => {
    // Not JSON.
    const notJson = fakeOrigin((_request, reply) => reply(200, "grant"));
    await notJson.listen();
    await expect(
      redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web", { originBaseUrl: notJson.url }),
    ).rejects.toMatchObject({ kind: "bad-reply", message: "Invalid room pairing response" });

    // Missing pieces of the grant.
    for (const body of [
      JSON.stringify({ credential: "c", session: { id: "s", label: "l" } }),
      JSON.stringify({ baseUrl: "http://x/tailcat-relay/1", session: { id: "s", label: "l" } }),
      JSON.stringify({ credential: "c", baseUrl: "http://x/tailcat-relay/1" }),
      JSON.stringify({ credential: 1, baseUrl: "http://x/tailcat-relay/1", session: {} }),
    ]) {
      const origin = fakeOrigin((_request, reply) => reply(200, body));
      await origin.listen();
      await expect(
        redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web", { originBaseUrl: origin.url }),
      ).rejects.toMatchObject({ kind: "bad-reply", message: "Invalid room pairing response" });
    }

    // The origin engine itself is unreachable.
    await expect(
      redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web", {
        originBaseUrl: "http://127.0.0.1:9",
        timeoutMs: 500,
      }),
    ).rejects.toMatchObject({
      kind: "transport",
      message: "Could not reach the engine to join that room. Check your connection and try again.",
    });
  });

  test("needs an origin: no window, no default URL", async () => {
    // The node test environment has no window; without an explicit origin
    // there is nothing to redeem through.
    await expect(
      redeemRoomMemberViaOrigin(INVITE, "server", "Roboco web", { fetch: async () => {
        throw new Error("must not be called");
      } }),
    ).rejects.toMatchObject({ kind: "transport", message: "Room pairing needs the engine serving this page" });
  });

  test("the redeem request is the only place the invite travels", async () => {
    // The invite string is decoded once by the join flow and posted once per
    // member; the codec itself never parses it (that is room-invite.ts's
    // job) — this pins the module boundary with a junk invite the codec
    // must still POST verbatim.
    const junkInvite = `${INVITE.slice(0, 40)}truncated`;
    const origin = fakeOrigin((request, reply) => {
      if (request.body.invite === junkInvite) {
        reply(200, grantReply(origin, "relay-x"));
        return;
      }
      reply(400, JSON.stringify({ error: "shape" }));
    });
    await origin.listen();
    const grant = await redeemRoomMemberViaOrigin(junkInvite, "server", "Roboco web", {
      originBaseUrl: origin.url,
    });
    expect(grant.baseUrl).toContain("/tailcat-relay/relay-x");
    // And the codec works from a parsed+re-encoded invite round-trip.
    const parsed = parseRoomInvite(INVITE);
    expect(parsed.members.map((member) => member.label)).toEqual(["server", "desktop"]);
  });
});
