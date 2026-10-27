// @vitest-environment jsdom
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RpcError, type EngineClient, type WatchHandle, type WatchHandlers } from "@roboco/engine-client";
import type { PreviewService, PreviewSnapshot } from "@roboco/proto";
import type { EngineSession } from "../src/state/engine-session";
import { PreviewPanelBody } from "../src/components/preview-panel";

/**
 * The preview panel's mounted behavior (agent-previews ticket 06): the
 * subscription wiring through the real `usePreviews` hook (a fake client
 * drives the `WatchPreviews` stream), the loopback framing gate (reachable
 * → iframe rows; off-machine → the honest note, rows standing down), the
 * empty-state vocabulary, and the re-subscribe loop. No JSX (createElement),
 * per-file jsdom pragma only.
 */

interface PanelWatchHandlers extends WatchHandlers<PreviewSnapshot> {}

/** A fake engine whose only method is `watch` — params and handlers captured. */
function fakeEngine() {
  const params: unknown[] = [];
  const cancels: number[] = [];
  let handlers: PanelWatchHandlers | null = null;
  const client = {
    state: "connected",
    watch: (_method: string, params_: unknown, h: PanelWatchHandlers): WatchHandle => {
      params.push(params_);
      handlers = h;
      const token = cancels.length + 1;
      return {
        method: _method,
        cancel: () => {
          cancels.push(token);
        },
      };
    },
  } as unknown as EngineClient;
  return {
    client,
    params,
    cancels,
    item: (snapshot: PreviewSnapshot) => act(() => handlers?.onItem(snapshot, { generation: 1 })),
    end: (error?: RpcError) => act(() => handlers?.onEnd?.(error)),
  };
}

function fakeSession(baseUrl: string, client: EngineClient): EngineSession {
  return { engine: { baseUrl }, client } as unknown as EngineSession;
}

function service(partial: Partial<PreviewService>): PreviewService {
  return {
    id: "svc-1",
    projectId: "p1",
    projectName: "my-app",
    projectCwd: "/tmp/my-app",
    deviceId: "dev-1",
    deviceName: "MacBook",
    hostname: "macbook.my-app.localhost",
    name: "my-app",
    port: 5173,
    pid: 1234,
    cwd: "/tmp/my-app",
    startedAt: 0,
    robocoOwned: true,
    ...partial,
  };
}

function snapshot(partial: Partial<PreviewSnapshot>): PreviewSnapshot {
  return {
    services: [],
    proxyPort: 7331,
    error: null,
    projectName: null,
    remote: false,
    ...partial,
  };
}

let root: Root;
let container: HTMLDivElement;

function render(session: EngineSession | null, chatId: string): void {
  act(() => root.render(createElement(PreviewPanelBody, { session, chatId })));
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

describe("PreviewPanelBody — the live list", () => {
  it("subscribes to WatchPreviews for the chat and renders the rows the stream sends", () => {
    const engine = fakeEngine();
    render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
    expect(engine.params).toEqual([{ chatId: "chat-1" }]);

    engine.item(snapshot({ services: [service({ id: "svc-a", name: "web", port: 5173 })] }));
    const rows = Array.from(container.querySelectorAll(".preview-row"));
    expect(rows).toHaveLength(1);
    expect(rows[0]!.textContent).toContain("web");
    expect(rows[0]!.textContent).toContain("localhost:5173");
    expect(container.textContent).toContain("Running locally");
  });

  it("renders the desktop's empty-state vocabulary — loading, then the local hint", () => {
    const engine = fakeEngine();
    render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
    expect(container.textContent).toContain("Looking for dev servers…");

    engine.item(snapshot({}));
    expect(container.textContent).toContain(
      "Start a dev server in this project. It will appear here automatically, ready to open.",
    );
  });

  it("remote rows name their device, and the engine's error stands the rows down", () => {
    const engine = fakeEngine();
    render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
    engine.item(snapshot({ remote: true, services: [service({ name: "web" })] }));
    expect(container.textContent).toContain("Running on your device");
    expect(container.textContent).toContain("MacBook · localhost:5173");
    expect(container.querySelector(".preview-row")!.hasAttribute("disabled")).toBe(false);

    engine.item(snapshot({ error: "Preview discovery unavailable", services: [service({ name: "web" })] }));
    expect(container.querySelector(".preview-row")!.hasAttribute("disabled")).toBe(true);
    expect(container.textContent).toContain("Preview discovery unavailable");
  });

  it("drops the subscription with the panel (cancel on unmount)", () => {
    const engine = fakeEngine();
    render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
    act(() => root.unmount());
    expect(engine.cancels).toHaveLength(1);
  });
});

describe("PreviewPanelBody — the framing gate", () => {
  it("a reachable endpoint frames a clicked row: sandboxed iframe at the service URL", () => {
    const engine = fakeEngine();
    render(fakeSession("http://localhost:27655", engine.client), "chat-1");
    engine.item(snapshot({ services: [service({ id: "svc-a", name: "web" })] }));

    const row = container.querySelector<HTMLButtonElement>(".preview-row")!;
    act(() => row.click());

    const frame = container.querySelector("iframe");
    expect(frame).not.toBeNull();
    expect(frame!.getAttribute("src")).toBe("http://macbook.my-app.localhost:7331");
    expect(frame!.getAttribute("sandbox")).toContain("allow-scripts");
    expect(frame!.getAttribute("sandbox")).toContain("allow-same-origin");
    // No top-navigation escape: the dev server can never navigate the app.
    expect(frame!.getAttribute("sandbox")).not.toContain("allow-top-navigation");
    // The toolbar names the framed service and can return to the list.
    expect(container.textContent).toContain("web");
    const back = container.querySelector<HTMLButtonElement>(".preview-tool")!;
    act(() => back.click());
    expect(container.querySelector("iframe")).toBeNull();
    expect(container.querySelector(".preview-row")).not.toBeNull();
  });

  it("an SSH-forwarded loopback endpoint counts as reachable", () => {
    const engine = fakeEngine();
    render(fakeSession("http://127.0.0.1:19999", engine.client), "chat-1");
    engine.item(snapshot({ services: [service({ name: "web" })] }));
    expect(container.querySelector(".preview-note")).toBeNull();
    act(() => container.querySelector<HTMLButtonElement>(".preview-row")!.click());
    expect(container.querySelector("iframe")).not.toBeNull();
  });

  it("an off-machine endpoint never frames: rows stand down and the honest note renders", () => {
    const engine = fakeEngine();
    render(fakeSession("http://192.168.1.20:27655", engine.client), "chat-1");
    engine.item(snapshot({ services: [service({ name: "web" })] }));

    expect(container.querySelector("iframe")).toBeNull();
    const note = container.querySelector(".preview-note");
    expect(note).not.toBeNull();
    expect(note!.textContent).toBe(
      "Open this engine preview from a device that shares its loopback, or SSH-forward the engine port and the proxy port (7331)",
    );
    const row = container.querySelector<HTMLButtonElement>(".preview-row")!;
    expect(row.hasAttribute("disabled")).toBe(true);
    // A disabled row cannot frame — the browser will not deliver its click.
    row.click();
    expect(container.querySelector("iframe")).toBeNull();
  });

  it("no session is honestly unreachable: the note, no dead frame", () => {
    render(null, "chat-1");
    expect(container.querySelector(".preview-note")).not.toBeNull();
    expect(container.querySelector("iframe")).toBeNull();
  });
});

describe("PreviewPanelBody — the re-subscribe loop (state/preview.ts)", () => {
  it("re-subscribes 2s after a stream ends mid-connection, and cancels with the panel", () => {
    vi.useFakeTimers();
    try {
      const engine = fakeEngine();
      render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
      engine.item(snapshot({ services: [service({ name: "web" })] }));
      engine.end(new RpcError("transport", "stream ended"));
      expect(container.textContent).toContain("Connecting to preview discovery…");

      act(() => vi.advanceTimersByTime(2_000));
      expect(engine.params).toHaveLength(2);
      // The retry is a fresh subscription for the same chat.
      expect(engine.params[1]).toEqual({ chatId: "chat-1" });
    } finally {
      vi.useRealTimers();
    }
  });

  it("an engine too old to know the method degrades once instead of polling", () => {
    vi.useFakeTimers();
    try {
      const engine = fakeEngine();
      render(fakeSession("http://127.0.0.1:27655", engine.client), "chat-1");
      engine.end(new RpcError("unknown-method", "unknown method: WatchPreviews", "WatchPreviews"));
      expect(container.textContent).toContain("unknown method: WatchPreviews");

      act(() => vi.advanceTimersByTime(10_000));
      expect(engine.params).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
