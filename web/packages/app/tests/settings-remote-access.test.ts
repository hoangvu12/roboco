// @vitest-environment jsdom

/**
 * Ticket 32 — the mounted Remote access page shows the engine's pairing QR:
 * the minted link's `qrSvg` renders as a data-URI <img> on the white tile
 * beside the URL, dims with the "Expired." caption once `expiresAt` passes,
 * and renders nothing extra against an engine whose link has no `qrSvg`
 * (older engines). The mounted idiom follows settings-general.test.ts: the
 * REAL page component over the REAL session-provider seam, with the engine
 * session doubled narrowly — a scripted client whose `call` answers
 * GetRemoteAccess / CreatePairingLink.
 */

import { act, createElement, type ReactElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { PairingLink, RemoteAccessSnapshot } from "@roboco/proto";
import { RemoteAccessSettingsPage } from "../src/routes/settings-remote-access";
import type { EngineSession } from "../src/state/engine-session";

// ── Controllable doubles ──────────────────────────────────────────────────

const h = vi.hoisted(() => {
  const QR_SVG =
    '<svg xmlns="http://www.w3.org/2000/svg" width="49" height="49" viewBox="0 0 49 49" shape-rendering="crispEdges"><rect width="49" height="49" fill="#fff"/><path fill="#000" d="M4 4h1v1H4V4"/></svg>';
  /** The scripted engine client — records calls, answers the page's RPCs. */
  const client = {
    calls: [] as { method: string; params: unknown }[],
    snapshot: {
      status: { enabled: true, configuredEnabled: true, address: "127.0.0.1:27655", source: "test", error: null },
      sessions: [],
    } as RemoteAccessSnapshot,
    link: null as PairingLink | null,
    call<T>(method: string, params: unknown): Promise<T> {
      h.client.calls.push({ method, params });
      if (method === "GetRemoteAccess") {
        return Promise.resolve(h.client.snapshot as unknown as T);
      }
      if (method === "CreatePairingLink") {
        return Promise.resolve((h.client.link ?? {
          url: "http://127.0.0.1:27655/pair#token=abc",
          expiresAt: Date.now() + 300_000,
        }) as unknown as T);
      }
      return Promise.reject(new Error(`unexpected method: ${method}`));
    },
  };

  /** The routed session — only `client` is read on this path. */
  let session: EngineSession | null = null;

  return {
    QR_SVG,
    client,
    setSession(next: EngineSession | null): void {
      session = next;
    },
    getSession(): EngineSession | null {
      return session;
    },
  };
});

vi.mock("../src/state/session-provider", () => ({
  // Only `useEngineSession().client` is read by the Remote access page (and
  // its own-session badge needs `session.engine`, never reached with no
  // paired sessions); the real provider's machinery is unrelated here.
  useEngineSession: () => h.getSession(),
}));

// ── jsdom gaps (the mounted suites' set) ───────────────────────────────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query.startsWith("(max-width") === false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── The mounted page harness ───────────────────────────────────────────────

const mounted: { root: Root; container: HTMLDivElement }[] = [];

async function mountRemoteAccess(): Promise<void> {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(RemoteAccessSettingsPage as () => ReactElement, {}));
  });
  mounted.push({ root, container });
  await act(async () => {});
}

/** Click "Create pairing link" and let the mint + snapshot refetch settle. */
async function mint(): Promise<void> {
  const create = Array.from(document.querySelectorAll<HTMLElement>("button")).find(
    (button) => button.textContent === "Create pairing link",
  )!;
  await act(async () => {
    create.click();
  });
}

beforeEach(() => {
  h.client.calls = [];
  h.client.link = null;
  h.setSession({ client: h.client } as unknown as EngineSession);
});

afterEach(() => {
  while (mounted.length > 0) {
    const entry = mounted.pop()!;
    act(() => {
      entry.root.unmount();
    });
    entry.container.remove();
  }
  document.body.replaceChildren();
});

// ── The pairing-link QR (settings/remote_access.rs parity) ────────────────

describe("Remote access — the pairing QR (ticket 32)", () => {
  it("shows the engine's QR on the white tile beside the URL", async () => {
    // Fresh: five minutes out from the page's mounted clock.
    h.client.link = {
      url: "http://127.0.0.1:27655/pair#token=abc",
      expiresAt: Date.now() + 300_000,
      qrSvg: h.QR_SVG,
    };
    await mountRemoteAccess();
    await mint();
    const tile = document.querySelector<HTMLElement>(".pairing-qr-tile")!;
    expect(tile).not.toBeNull();
    expect(tile.className).not.toContain("pairing-qr-expired");
    const img = tile.querySelector<HTMLImageElement>('img[alt="Pairing QR code"]');
    expect(img).not.toBeNull();
    // The engine's SVG becomes the <img> data URI, "#" URL-encoded so the
    // browser keeps the whole SVG (a raw "#" would truncate the URI).
    expect(img?.getAttribute("src")).toBe(`data:image/svg+xml;utf8,${encodeURIComponent(h.QR_SVG)}`);
    expect(img?.getAttribute("src")?.includes("%23")).toBe(true);
    expect(img?.getAttribute("width")).toBe("176");
    expect(document.querySelector(".settings-url")?.textContent).toBe(
      "http://127.0.0.1:27655/pair#token=abc",
    );
    expect(document.body.textContent).toContain("Scan with the Roboco app on your phone.");
    expect(document.body.textContent).toContain("Use once within five minutes.");
  });

  it("dims the QR and swaps the caption once the link expires", async () => {
    // Expired before the page's mounted clock ever reads it.
    h.client.link = {
      url: "http://127.0.0.1:27655/pair#token=stale",
      expiresAt: Date.now() - 1,
      qrSvg: h.QR_SVG,
    };
    await mountRemoteAccess();
    await mint();
    const tile = document.querySelector<HTMLElement>(".pairing-qr-tile")!;
    expect(tile.className).toContain("pairing-qr-expired");
    expect(document.body.textContent).toContain("Expired. Create a new pairing link.");
    expect(document.body.textContent).not.toContain("Scan with the Roboco app on your phone.");
  });

  it("renders nothing extra against an engine whose link lacks qrSvg", async () => {
    h.client.link = {
      url: "http://127.0.0.1:27655/pair#token=bare",
      expiresAt: Date.now() + 300_000,
    };
    await mountRemoteAccess();
    await mint();
    expect(document.querySelector(".pairing-qr-tile")).toBeNull();
    expect(document.querySelector('img[alt="Pairing QR code"]')).toBeNull();
    expect(document.body.textContent).not.toContain("Scan with the Roboco app on your phone.");
    // The link row itself stays exactly as it was: URL, meta, Copy.
    expect(document.querySelector(".settings-url")?.textContent).toBe(
      "http://127.0.0.1:27655/pair#token=bare",
    );
    expect(document.body.textContent).toContain("Use once within five minutes.");
    expect(
      Array.from(document.querySelectorAll<HTMLElement>("button")).some(
        (button) => button.textContent === "Copy",
      ),
    ).toBe(true);
  });
});
