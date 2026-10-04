import { RpcError } from "./rpc-error";

export const TAILCAT_INVITE_PREFIX = "roboco-tailcat:";

export interface ParsedTailcatInvite {
  readonly address: string;
  readonly token: string;
  readonly expiresAt: number;
}

export function isTailcatInvite(input: string): boolean {
  return input.trimStart().startsWith(TAILCAT_INVITE_PREFIX);
}

/** Decode a `roboco-tailcat:` invite (route + pairing code + expiry). */
export function parseTailcatInvite(input: string): ParsedTailcatInvite {
  const trimmed = input.trim();
  if (!trimmed.startsWith(TAILCAT_INVITE_PREFIX)) {
    throw new RpcError("transport", "Tailcat invite must start with roboco-tailcat:");
  }
  const payload = trimmed.slice(TAILCAT_INVITE_PREFIX.length);
  let bytes: Uint8Array;
  try {
    bytes = base64UrlDecode(payload);
  } catch {
    throw new RpcError("transport", "Tailcat invite payload is not base64url");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new RpcError("transport", "Tailcat invite payload is malformed");
  }
  if (typeof parsed !== "object" || parsed === null) {
    throw new RpcError("transport", "Tailcat invite payload is malformed");
  }
  const record = parsed as Record<string, unknown>;
  const address = record.address;
  const token = record.token;
  const expiresAt = record.expiresAt;
  if (
    typeof address !== "string" ||
    !address.startsWith("tc") ||
    typeof token !== "string" ||
    token.length === 0 ||
    typeof expiresAt !== "number"
  ) {
    throw new RpcError("transport", "Tailcat invite payload is malformed");
  }
  if (expiresAt <= Date.now()) {
    throw new RpcError("failed", "Tailcat invite has expired; mint a new one on the engine");
  }
  return { address, token, expiresAt };
}

function base64UrlDecode(input: string): Uint8Array {
  const normalized = input.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}
