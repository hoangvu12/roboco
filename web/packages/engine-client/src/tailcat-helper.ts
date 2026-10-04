import type { SessionGrant } from "@roboco/proto";
import { RpcError } from "./rpc-error";

export const DEFAULT_TAILCAT_BROWSER_HELPER = "http://127.0.0.1:7333";

export interface TailcatHelperOptions {
  readonly helperBaseUrl?: string;
  readonly fetch?: typeof fetch;
  readonly timeoutMs?: number;
}

function tailcatRedeemUrl(options: TailcatHelperOptions): string {
  if (options.helperBaseUrl !== undefined) {
    return `${options.helperBaseUrl.replace(/\/+$/, "")}/pair`;
  }
  if (typeof window !== "undefined" && window.location.origin.length > 0) {
    return `${window.location.origin}/pairing/tailcat-redeem`;
  }
  return `${DEFAULT_TAILCAT_BROWSER_HELPER}/pair`;
}

/**
 * Pair through the serving engine: it dials Tailcat, redeems, and exposes the
 * remote engine at `/tailcat-relay/{id}` on the same origin as the web app.
 */
export async function redeemTailcatInviteViaHelper(
  invite: string,
  label: string,
  options: TailcatHelperOptions = {},
): Promise<SessionGrant & { baseUrl: string }> {
  const fetcher = options.fetch ?? fetch;
  const url = tailcatRedeemUrl(options);
  let response: Response;
  try {
    response = await fetcher(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ invite, label }),
      signal: AbortSignal.timeout(options.timeoutMs ?? 60_000),
    });
  } catch {
    throw new RpcError(
      "transport",
      "Could not reach the engine to redeem that Tailcat invite. Check your connection and try again.",
    );
  }
  if (!response.ok) {
    let message = `Tailcat pairing returned HTTP ${response.status}`;
    try {
      const body = (await response.json()) as { error?: string };
      if (typeof body.error === "string" && body.error.length > 0) {
        message = body.error;
      }
    } catch {
      // ignore
    }
    throw new RpcError(response.status === 401 ? "failed" : "transport", message);
  }
  const grant = (await response.json()) as SessionGrant & { baseUrl?: string };
  if (
    typeof grant.credential !== "string" ||
    typeof grant.session !== "object" ||
    grant.session === null ||
    typeof grant.baseUrl !== "string"
  ) {
    throw new RpcError("bad-reply", "Invalid Tailcat pairing response");
  }
  return grant as SessionGrant & { baseUrl: string };
}
