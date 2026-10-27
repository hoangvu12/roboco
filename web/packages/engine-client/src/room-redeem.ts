import type { SessionGrant } from "@roboco/proto";
import { ROOM_INVITE_PREFIX } from "./room-invite";
import { RpcError } from "./rpc-error";

/**
 * The browser join path of ADR 0011: a browser cannot dial, so it hands the
 * engine serving the page a `roboco-room:` invite plus the label of one
 * member to reach — `POST /pairing/room-redeem {invite, target, label}` —
 * and that engine dials the member, relays the presentation, and returns
 * the member's session grant with `baseUrl` rewritten to its own
 * `/tailcat-relay/{id}`. The invite (and its secret) travels this one
 * request and nowhere else; the reply lands exactly like a
 * `roboco-tailcat:` helper grant.
 *
 * The engine's refusals are fixed, label-shaped texts in `{error}` — a
 * member that is offline fails alone (`502` naming the label, never the
 * address, never the secret), the origin listener stays up for the next
 * member. Non-JSON bodies and oversized or invite-echoing error texts
 * collapse to the HTTP status so no payload byte can reach a message.
 */

export interface RoomRedeemOptions {
  /** The engine serving the page; defaults to `window.location.origin`. */
  readonly originBaseUrl?: string;
  readonly fetch?: typeof fetch;
  readonly timeoutMs?: number;
}

/** A member's grant: an ordinary session plus the relay base to dial. */
export type RoomMemberGrant = SessionGrant & { baseUrl: string };

/** The longest engine error text surfaced verbatim. */
const MAX_ERROR_TEXT = 256;

const DEFAULT_ROOM_REDEEM_TIMEOUT_MS = 60_000;

function roomRedeemUrl(options: RoomRedeemOptions): string {
  if (options.originBaseUrl !== undefined) {
    return `${options.originBaseUrl.replace(/\/+$/, "")}/pairing/room-redeem`;
  }
  if (typeof window !== "undefined" && window.location.origin.length > 0) {
    return `${window.location.origin}/pairing/room-redeem`;
  }
  throw new RpcError("transport", "Room pairing needs the engine serving this page");
}

/** A best-effort redaction hint: the secret carried inside an invite string. */
function inviteSecret(invite: string): string | null {
  const payload = invite.trim().slice(ROOM_INVITE_PREFIX.length);
  if (payload.length === 0 || !/^[A-Za-z0-9_-]+$/.test(payload) || payload.length % 4 === 1) {
    return null;
  }
  try {
    const normalized = payload.replaceAll("-", "+").replaceAll("_", "/");
    const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
    const text = new TextDecoder().decode(
      Uint8Array.from(atob(padded), (char) => char.charCodeAt(0)),
    );
    const match = /"secret"\s*:\s*"([^"\\]*)"/.exec(text);
    return match === null ? null : match[1] ?? null;
  } catch {
    return null;
  }
}

/** Map the origin engine's refusal to an error whose text is label-shaped at worst. */
async function roomRedeemError(response: Response, invite: string): Promise<RpcError> {
  const status = response.status;
  // 4xx (but not 429) are permanent refusals — the invite was rejected or
  // the presentation refused; throttling and gateway failures retry.
  const kind = status >= 400 && status < 500 && status !== 429 ? "failed" : "transport";
  let message: string | null = null;
  const secret = inviteSecret(invite);
  try {
    const body = (await response.json()) as { error?: unknown };
    if (
      typeof body.error === "string" &&
      body.error.length > 0 &&
      body.error.length <= MAX_ERROR_TEXT &&
      !body.error.includes("\n") &&
      !body.error.includes(invite) &&
      (secret === null || secret.length === 0 || !body.error.includes(secret))
    ) {
      message = body.error;
    }
  } catch {
    // Not JSON (the engine's plain-text refusals): the status alone —
    // the body never reaches the message.
  }
  if (message === null) {
    message = `Room pairing returned HTTP ${status}`;
  }
  return new RpcError(kind, message);
}

/**
 * Redeem one room member through the engine serving the page: it relays the
 * presentation and returns that member's grant on a `/tailcat-relay/{id}`
 * base. `target` is the member's label, `label` is this browser's device
 * label, and `invite` is the full `roboco-room:` string.
 */
export async function redeemRoomMemberViaOrigin(
  invite: string,
  target: string,
  label: string,
  options: RoomRedeemOptions = {},
): Promise<RoomMemberGrant> {
  const fetcher = options.fetch ?? fetch;
  const url = roomRedeemUrl(options);
  let response: Response;
  try {
    response = await fetcher(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ invite, target, label }),
      redirect: "error",
      signal: AbortSignal.timeout(options.timeoutMs ?? DEFAULT_ROOM_REDEEM_TIMEOUT_MS),
    });
  } catch {
    throw new RpcError(
      "transport",
      "Could not reach the engine to join that room. Check your connection and try again.",
    );
  }
  if (!response.ok) {
    throw await roomRedeemError(response, invite);
  }
  let grant: unknown;
  try {
    grant = await response.json();
  } catch {
    throw new RpcError("bad-reply", "Invalid room pairing response");
  }
  if (
    typeof grant !== "object" ||
    grant === null ||
    typeof (grant as Record<string, unknown>).credential !== "string" ||
    typeof (grant as Record<string, unknown>).baseUrl !== "string" ||
    typeof (grant as Record<string, unknown>).session !== "object" ||
    (grant as Record<string, unknown>).session === null
  ) {
    throw new RpcError("bad-reply", "Invalid room pairing response");
  }
  return grant as RoomMemberGrant;
}
