// @vitest-environment jsdom

/**
 * The composer's **Resume** handle with a switched-picks override
 * (t3-orchestration-v2 tickets 25/26): "Continue on another harness"
 * switches the chat's harness through the engine and then fires the
 * fixed-prompt continuation — and the continuation must carry the NEW
 * harness and model deterministically, because the chats-watch frame that
 * would re-seed the composer's draft can trail the SwitchHarness reply.
 * The web's stamp is the resume handle's `override` parameter (the
 * desktop's `apply_chat_config` row stamp before `resume_limited`).
 *
 * The mounted idiom follows composer-edit-failure.test.ts: no JSX, the
 * session double, the matchMedia / ResizeObserver / scrollIntoView / rAF
 * stubs; the assertion is the QueueCommand the engine sees.
 */

import { act, createElement } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Chat } from "@roboco/proto";
import type { DraftConfig } from "../src/lib/composer-actions";
import { RESUME_CONTINUE_PROMPT } from "../src/lib/limited";
import { Composer } from "../src/components/composer";
import { PickerCatalog } from "../src/state/picker-catalog";
import type { EngineSession } from "../src/state/engine-session";
import type { EngineClient } from "@roboco/engine-client";

// The composer's import graph resolves the new-thread artwork at module
// scope and prewarms its decode (appearance.ts `defaultPrewarm`) — jsdom's
// Image has no decode, and the unhandled rejection would poison the run.
vi.hoisted(() => {
  if (
    typeof HTMLImageElement === "function" &&
    typeof HTMLImageElement.prototype.decode !== "function"
  ) {
    HTMLImageElement.prototype.decode = () => Promise.resolve();
  }
});

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query.startsWith("(max-width"),
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
  if (typeof Element.prototype.scrollIntoView !== "function") {
    Element.prototype.scrollIntoView = () => {};
  }
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    }) as typeof requestAnimationFrame;
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── A recording engine client: every call resolves, the queue answers ────

const h = vi.hoisted(() => {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  const client = {
    engineInfo: { deviceId: "device-1" },
    state: "connected",
    status: { state: "connected", info: null },
    onStatus(): () => void {
      return () => {};
    },
    async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
      calls.push({ method, params });
      if (method === "QueueCommand") {
        return { commandId: "cmd-1" } as T;
      }
      return {} as T;
    },
  } as unknown as EngineClient;
  return { calls, client };
});

const CATALOG = new PickerCatalog(h.client);

const SESSION = {
  engine: { baseUrl: "http://engine.test", credential: "cred", label: "test" },
  client: h.client,
  cache: {
    subscribe(): () => void {
      return () => {};
    },
    getSnapshot(): null {
      return null;
    },
  },
  catalog: CATALOG,
  transcripts: {},
} as unknown as EngineSession;

function limitedChat(id: string): Chat {
  return {
    id,
    deviceId: "device-1",
    title: null,
    archived: false,
    cwd: "/proj",
    branch: null,
    checkoutId: null,
    // The chat runs on claude-code — the harness the limit hit on.
    config: {
      harness: "claude-code",
      model: "sonnet",
      reasoning: null,
      sandbox: "workspace-write",
      modelOptions: {},
    },
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: new Date(0).toISOString(),
    spaceId: null,
  };
}

// ── The mounted composer harness (the resumeRef host is the page) ─────────

const hostRef: { current: ((override?: Partial<DraftConfig>) => void) | null } = {
  current: null,
};

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  hostRef.current = null;
  h.calls.length = 0;
  document.body.replaceChildren();
});

function mountComposer(): void {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const chat = limitedChat("chat-limited");
  function Host() {
    return createElement(Composer, {
      session: SESSION,
      chat,
      catalog: CATALOG,
      transcript: null,
      availableWidth: 700,
      resumeRef: hostRef,
    });
  }
  act(() => {
    root.render(createElement(Host));
  });
  mounted.push(() => {
    act(() => {
      root.unmount();
    });
    container.remove();
  });
}

async function flush(): Promise<void> {
  await act(async () => {});
}

function queueCommand(): Record<string, unknown> | undefined {
  return h.calls.find((call) => call.method === "QueueCommand")?.params as
    | Record<string, unknown>
    | undefined;
}

describe("resumeLimited with a switched-picks override (tickets 25/26)", () => {
  it("the continuation carries the switched harness and model, not the stale draft", async () => {
    mountComposer();
    await flush();
    expect(hostRef.current).not.toBeNull();
    act(() => {
      hostRef.current!({ harness: "pi", model: "glm-5.3", reasoning: null, modelOptions: {} });
    });
    await flush();
    const params = queueCommand();
    expect(params).toBeDefined();
    expect(params!.chatId).toBe("chat-limited");
    const command = params!.command as Record<string, unknown>;
    expect(command.kind).toBe("run");
    const request = command.request as Record<string, unknown>;
    expect(request.prompt).toBe(RESUME_CONTINUE_PROMPT);
    // The desktop's row stamp, verbatim: the NEW harness and its model,
    // never the limited claude-code draft the chats watch has not yet
    // replaced.
    expect(request.harness).toBe("pi");
    expect(request.model).toBe("glm-5.3");
    expect(request.reasoning).toBeNull();
    // The sandbox is the one field the switch never owns — the chat's own.
    expect(request.sandbox).toBe("workspace-write");
  });

  it("a plain resume (no override) keeps the draft's run identity", async () => {
    mountComposer();
    await flush();
    act(() => {
      hostRef.current!();
    });
    await flush();
    const request = (queueCommand()!.command as Record<string, unknown>).request as Record<
      string,
      unknown
    >;
    expect(request.prompt).toBe(RESUME_CONTINUE_PROMPT);
    expect(request.harness).toBe("claude-code");
    expect(request.model).toBe("sonnet");
  });
});
