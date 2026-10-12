import { describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { PairedSession, PairingLink, RemoteAccessSnapshot } from "@roboco/proto";
import type { StoredEngine } from "../src/lib/engine-store";
import { formatLastSeen } from "../src/lib/devices";
import type { SessionRow } from "../src/lib/remote-access";
import {
  createPairingLink,
  getRemoteAccess,
  ownSessionId,
  pairingQr,
  revokePairingSession,
  sessionIsSelf,
  sessionRows,
  setRemoteAccess,
} from "../src/lib/remote-access";

const NOW = 1_800_000_000_000;

describe("formatLastSeen (devices.rs format_last_seen)", () => {
  it("words the buckets the desktop way", () => {
    expect(formatLastSeen(null, NOW)).toBe("never seen");
    expect(formatLastSeen(NOW - 5_000, NOW)).toBe("just now");
    expect(formatLastSeen(NOW - 59_000, NOW)).toBe("just now");
    expect(formatLastSeen(NOW - 60_000, NOW)).toBe("1m ago");
    expect(formatLastSeen(NOW - 5 * 60_000, NOW)).toBe("5m ago");
    expect(formatLastSeen(NOW - 3 * 3_600_000, NOW)).toBe("3h ago");
    expect(formatLastSeen(NOW - 2 * 86_400_000, NOW)).toBe("2d ago");
  });
});

function pairedSession(fields: Partial<PairedSession>): PairedSession {
  return {
    id: "s1",
    label: "Roboco web on Windows",
    createdAt: NOW - 86_400_000,
    lastSeen: NOW - 30_000,
    revokedAt: null,
    ...fields,
  };
}

function snapshot(sessions: PairedSession[]): RemoteAccessSnapshot {
  return {
    status: { enabled: true, configuredEnabled: true, address: "0.0.0.0:27655", source: "default", error: null },
    sessions,
  };
}

function sessionRow(fields: Partial<SessionRow> = {}): SessionRow {
  return { id: "s1", label: "Roboco web on Windows", revoked: false, lastSeenLabel: null, ...fields };
}

function storedEngine(fields: Partial<StoredEngine> = {}): StoredEngine {
  return {
    baseUrl: "http://127.0.0.1:27655",
    credential: "credential",
    label: "Roboco web on Windows",
    sessionId: "s1",
    pairedAt: NOW - 86_400_000,
    deviceId: null,
    ...fields,
  };
}

describe("sessionRows", () => {
  it("falls back to a generic label for unlabeled sessions", () => {
    const rows = sessionRows(snapshot([pairedSession({ label: "" })]), NOW);
    expect(rows[0]!.label).toBe("Paired device");
    expect(rows[0]!.revoked).toBe(false);
    expect(rows[0]!.lastSeenLabel).toBe("Last seen just now");
  });

  it("marks revoked rows and drops their last-seen line", () => {
    const rows = sessionRows(snapshot([pairedSession({ revokedAt: NOW - 1_000 })]), NOW);
    expect(rows[0]!.revoked).toBe(true);
    expect(rows[0]!.lastSeenLabel).toBe(null);
  });
});

describe("sessionIsSelfMatchesStoredSessionId", () => {
  it("matches the row whose id equals the stored engine's sessionId", () => {
    expect(sessionIsSelf(sessionRow({ id: "s1" }), storedEngine())).toBe(true);
  });

  it("leaves every other row unmatched", () => {
    expect(sessionIsSelf(sessionRow({ id: "s2" }), storedEngine())).toBe(false);
  });

  it("never matches when the stored engine has no session id", () => {
    const stored = storedEngine({ sessionId: null as unknown as string });
    expect(ownSessionId(stored)).toBe(null);
    expect(sessionIsSelf(sessionRow({ id: "s1" }), stored)).toBe(false);
  });

  it("still matches a revoked self row — the badge outlives the session", () => {
    expect(sessionIsSelf(sessionRow({ id: "s1", revoked: true }), storedEngine())).toBe(true);
  });
});

/** A call-recording fake EngineClient — the protocol-boundary check. */
function fakeClient(replies: Record<string, unknown>) {
  const calls: { method: string; params: unknown }[] = [];
  const client = {
    async call(method: string, params: unknown): Promise<unknown> {
      calls.push({ method, params });
      const reply = replies[method];
      if (reply === undefined) {
        throw new Error(`unknown method: ${method}`);
      }
      return reply;
    },
  } as unknown as EngineClient;
  return { client, calls };
}

describe("remote access RPC wrappers", () => {
  it("reads the snapshot with GetRemoteAccess and no params", async () => {
    const { client, calls } = fakeClient({ GetRemoteAccess: snapshot([]) });
    expect(await getRemoteAccess(client)).toEqual(snapshot([]));
    expect(calls).toEqual([{ method: "GetRemoteAccess", params: {} }]);
  });

  it("sends the toggle as SetRemoteAccess {enabled}", async () => {
    const { client, calls } = fakeClient({ SetRemoteAccess: snapshot([]) });
    await setRemoteAccess(client, true);
    expect(calls).toEqual([{ method: "SetRemoteAccess", params: { enabled: true } }]);
  });

  it("revokes by sessionId (revocation gates the next handshake server-side)", async () => {
    const { client, calls } = fakeClient({ RevokePairingSession: snapshot([]) });
    await revokePairingSession(client, "s1");
    expect(calls).toEqual([{ method: "RevokePairingSession", params: { sessionId: "s1" } }]);
  });

  it("mints a pairing link", async () => {
    const link = { url: "http://host:27655/pair#token=abc", expiresAt: NOW + 300_000 };
    const { client, calls } = fakeClient({ CreatePairingLink: link });
    expect(await createPairingLink(client)).toEqual(link);
    expect(calls).toEqual([{ method: "CreatePairingLink", params: {} }]);
  });
});

describe("pairingQr", () => {
  const QR_SVG =
    '<svg xmlns="http://www.w3.org/2000/svg" width="49" height="49" viewBox="0 0 49 49" shape-rendering="crispEdges"><rect width="49" height="49" fill="#fff"/><path fill="#000" d="M4 4h1v1H4V4"/></svg>';
  const link = (fields: Partial<PairingLink>): PairingLink => ({
    url: "http://host:27655/pair#token=abc",
    expiresAt: NOW + 300_000,
    ...fields,
  });

  it("builds the <img> data URI from the engine's SVG", () => {
    const qr = pairingQr(link({ qrSvg: QR_SVG }), NOW);
    expect(qr?.src).toBe(`data:image/svg+xml;utf8,${encodeURIComponent(QR_SVG)}`);
    // The URL-encoded SVG starts with an escaped "<svg" — the data URI shape
    // a browser needs, with the "#" characters of the QR kept out of the URI.
    expect(qr?.src.startsWith("data:image/svg+xml;utf8,%3Csvg")).toBe(true);
    expect(qr?.expired).toBe(false);
    expect(qr?.caption).toBe("Scan with the Roboco app on your phone.");
  });

  it("dims an expired link and says to mint a new one", () => {
    const qr = pairingQr(link({ qrSvg: QR_SVG, expiresAt: NOW - 1 }), NOW);
    expect(qr?.expired).toBe(true);
    expect(qr?.caption).toBe("Expired. Create a new pairing link.");
  });

  it("renders nothing extra against an engine whose link lacks qrSvg", () => {
    expect(pairingQr(link({ qrSvg: undefined }), NOW)).toBe(null);
    expect(pairingQr(link({}), NOW)).toBe(null);
  });
});
