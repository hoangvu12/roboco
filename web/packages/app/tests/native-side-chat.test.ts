// @vitest-environment jsdom

/**
 * A Mimir side chat as the right pane mounts it: the real side-chat surface,
 * its real Composer and its own NativeDock over its own transcript stream.
 * Only the engine is scripted: every call the production code makes is
 * recorded and answered here, and a control is answered with a command id only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Chat, NativeChatState, SessionMessageEntry, TranscriptUpdate } from "@roboco/proto";
import type { EngineSession } from "../src/state/engine-session";
import { PickerCatalog } from "../src/state/picker-catalog";
import { TranscriptPool } from "../src/state/transcript-pool";

const MAIN = "main-chat";
const SIDE = "side-chat";

const session = { current: null as EngineSession | null };
const statuses = { rows: [] as unknown[] };

vi.mock("../src/state/session-provider", () => ({
  useEngineSession: () => session.current,
  useEngineSessions: () => new Map(),
  useEngineRetry: () => () => {},
  EngineSessionProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock("../src/state/fleet", async (original) => {
  const actual = await original<typeof import("../src/state/fleet")>();
  return {
    ...actual,
    useFleetSnapshot: () => ({
      generation: 1,
      capabilities: [],
      chats: { rows: [chatRow(MAIN), chatRow(SIDE, MAIN)], loaded: true, error: null },
      spaces: { rows: [], loaded: true, error: null },
      devices: { rows: [], loaded: true, error: null },
      statuses: { rows: statuses.rows, loaded: true, error: null },
    }),
  };
});

vi.mock("../src/state/appearance", async (original) => ({
  ...(await original<typeof import("../src/state/appearance")>()),
  useResolvedAppearance: () => "dark" as const,
}));

// jsdom has no image decoding; the new-thread artwork prewarm calls it.
HTMLImageElement.prototype.decode = () => Promise.resolve();

function chatRow(id: string, parent: string | null = null): Chat {
  return {
    id,
    deviceId: "device-1",
    parentChatId: parent,
    archived: false,
    createdAt: "2026-10-01T00:00:00Z",
    cwd: "/work",
    config: { harness: "mimir", model: "stale/old-model", reasoning: "high", sandbox: "workspace-write" },
  } as unknown as Chat;
}

function native(over: Partial<NativeChatState> = {}): NativeChatState {
  return {
    conversation: { id: "conv-side", cwd: "/work" },
    link: { state: "attached" },
    recovering: false,
    configuration: { provider: "anthropic", model: "claude-sonnet", reasoning: "low", mode: "build" },
    activeRequest: null,
    requests: [],
    plan: null,
    goal: null,
    userRequest: {
      id: "ur-side",
      questions: [{ id: "q1", prompt: "Which store?", allowMultiple: false, options: [{ label: "Postgres", description: "Relational" }] }],
    },
    children: [],
    submissions: [],
    ...over,
  };
}

interface Call {
  readonly method: string;
  readonly params: Record<string, unknown>;
}

/** The scripted engine. Watches deliver the frame the test sets; calls are recorded. */
class Engine {
  readonly calls: Call[] = [];
  readonly frames = new Map<string, TranscriptUpdate>();
  readonly #pushers = new Map<string, (frame: TranscriptUpdate) => void>();
  readonly state = "connected";
  readonly engineInfo = {
    deviceId: "device-1",
    workspaceScope: "local",
    capabilities: ["message-queue-v1", "message-queue-attachments-v1", "composer-references-v1"],
  };
  readonly status = { state: "connected", info: this.engineInfo, generation: 1 };
  #commands = 0;

  onStatus(): () => void {
    return () => {};
  }

  watch(_method: string, params: { chatId: string }, handlers: { onItem: (item: TranscriptUpdate, ctx: { generation: number }) => void }) {
    const push = (frame: TranscriptUpdate) => handlers.onItem(frame, { generation: 1 });
    this.#pushers.set(params.chatId, push);
    const frame = this.frames.get(params.chatId);
    if (frame !== undefined) {
      setTimeout(() => push(frame), 0);
    }
    return { cancel: () => {} };
  }

  push(chatId: string, frame: TranscriptUpdate): void {
    this.#pushers.get(chatId)?.(frame);
  }

  call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    this.calls.push({ method, params });
    switch (method) {
      case "QueueCommand":
        this.#commands += 1;
        return Promise.resolve({ commandId: `cmd-${this.#commands}` } as T);
      case "GetCommand":
        return Promise.resolve(null as T);
      case "GetNativeCatalog":
        return Promise.resolve({ commands: [{ name: "compact", description: "Compact", aliases: [], argument: null, idleOnly: true }], skills: [], providers: [] } as T);
      case "ListHarnesses":
        return Promise.resolve([] as T);
      default:
        return Promise.resolve(null as T);
    }
  }

  of(method: string): Call[] {
    return this.calls.filter((call) => call.method === method);
  }
}

function frame(entries: readonly SessionMessageEntry[], state: NativeChatState | null): TranscriptUpdate {
  return { contextUsage: null, reset: [...entries], native: state } as unknown as TranscriptUpdate;
}

let root: Root | null = null;
let host: HTMLElement | null = null;

beforeAll(async () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: false,
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
  } as unknown as typeof ResizeObserver;
  Element.prototype.scrollIntoView = () => {};
  await Promise.all([
    import("../src/state/right-pane"),
    import("../src/components/surface-registry"),
  ]);
});

afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  root = null;
  host = null;
  session.current = null;
  statuses.rows = [];
});

async function mountSideChat(engine: Engine): Promise<HTMLElement> {
  session.current = {
    engine: { baseUrl: "https://engine.test", credential: "cred" },
    client: engine,
    cache: { subscribe: () => () => {}, getSnapshot: () => null },
    catalog: new PickerCatalog(engine as never),
    transcripts: new TranscriptPool(engine as never),
  } as unknown as EngineSession;
  const { rightPaneStore } = await import("../src/state/right-pane");
  const { renderRightSurface } = await import("../src/components/surface-registry");
  rightPaneStore.addSideChatSurface(MAIN, { chatId: SIDE, title: "Side" });
  const surface = rightPaneStore
    .stateFor(MAIN)
    .tabs.find((candidate) => candidate.kind === "sidechat");
  if (surface === undefined) {
    throw new Error("no side chat surface");
  }
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => root!.render(createElement(() => renderRightSurface(surface, { chatId: MAIN }))));
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 5));
  });
  return host;
}

async function type(view: HTMLElement, text: string): Promise<void> {
  const area = view.querySelector<HTMLTextAreaElement>(".side-chat-composer textarea")!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!.call(area, text);
    area.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await act(async () => {
    area.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
  });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 5));
  });
}

describe("Mimir side chat", () => {
  it("waits for the side chat's host state before sending, then sends without the chat row's stale model", async () => {
    const engine = new Engine();
    const view = await mountSideChat(engine);
    expect(view.querySelector("[data-testid=composer-native-config]")!.textContent).toBe("Waiting for Mimir");
    await type(view, "hello");
    expect(engine.of("QueueCommand")).toEqual([]);
    expect(view.querySelector<HTMLTextAreaElement>(".side-chat-composer textarea")!.value).toBe("hello");
    await act(async () => engine.push(SIDE, frame([], native({ userRequest: null }))));
    expect(view.querySelector("[data-testid=composer-native-config]")!.textContent).toBe("Build · anthropic/claude-sonnet · low");
    await type(view, "hello");
    const [run] = engine.of("QueueCommand");
    expect(run!.params.chatId).toBe(SIDE);
    const request = (run!.params.command as { request: Record<string, unknown> }).request;
    expect(request.prompt).toBe("hello");
    expect(request.model ?? null).toBeNull();
    expect(request.reasoning ?? null).toBeNull();
  });

  it("sends a host command into the running request now, and queues only an idle-only one", async () => {
    const engine = new Engine();
    statuses.rows = [{ chatId: SIDE, status: "working", updatedAt: new Date().toISOString(), startedAt: new Date().toISOString() }];
    engine.frames.set(SIDE, frame([], native({ userRequest: null, activeRequest: "req-1" })));
    const view = await mountSideChat(engine);
    await type(view, "/goal ship the retry work");
    expect(engine.of("QueueMessage")).toEqual([]);
    const [run] = engine.of("QueueCommand");
    expect(run!.params.chatId).toBe(SIDE);
    expect((run!.params.command as { request: { prompt: string } }).request.prompt).toBe("/goal ship the retry work");

    // The slash popup lists the host's catalog, which marks /compact idle-only.
    const area = view.querySelector<HTMLTextAreaElement>(".side-chat-composer textarea")!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!.call(area, "/");
      area.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
    expect(engine.of("GetNativeCatalog").map((call) => call.params.chatId)).toContain(SIDE);
    await type(view, "/compact keep the tests");
    expect(engine.of("QueueCommand")).toHaveLength(1);
    const [queued] = engine.of("QueueMessage");
    expect(queued!.params.chatId).toBe(SIDE);
    expect(queued!.params.text).toBe("/compact keep the tests");
  });

  it("opens the side chat's agent as the side chat's child, with the side chat's host facts", async () => {
    const engine = new Engine();
    const child = {
      handle: "agent-1",
      attempt: 2,
      profile: "explore",
      description: "Map the auth module",
      model: null,
      status: "running" as const,
      background: true,
      spawnedBy: null,
      completionPending: false,
      presentation: null,
      docId: `${SIDE}--sub--agent-1`,
      oversized: false,
      attempts: [],
    };
    engine.frames.set(SIDE, frame([], native({ userRequest: null, children: [child] })));
    engine.frames.set(MAIN, frame([], native({ userRequest: null, children: [] })));
    const view = await mountSideChat(engine);
    const dock = view.querySelector("[data-native-dock]")!;
    await act(async () => {
      Array.from(dock.querySelectorAll("button")).find((b) => b.textContent === "Open")!.click();
    });
    const { rightPaneStore } = await import("../src/state/right-pane");
    const tab = rightPaneStore.stateFor(MAIN).tabs.find((candidate) => candidate.kind === "subagent")!;
    expect(rightPaneStore.subagentSurfaceOf(tab.id)).toMatchObject({ chatId: SIDE, docId: child.docId });
    const { renderRightSurface } = await import("../src/components/surface-registry");
    const pane = document.createElement("div");
    document.body.appendChild(pane);
    const paneRoot = createRoot(pane);
    await act(async () => paneRoot.render(createElement(() => renderRightSurface(tab, { chatId: MAIN }))));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
    expect(pane.querySelector("[data-testid=native-child-head]")!.textContent).toContain("Map the auth module");
    await act(async () => {
      pane.querySelector<HTMLInputElement>("[aria-label='Guidance for this agent']")!.value = "";
    });
    await act(async () => {
      Array.from(pane.querySelectorAll("button")).find((b) => b.textContent === "Stop")!.click();
    });
    const stop = engine.of("QueueCommand").at(-1)!;
    expect(stop.params.chatId).toBe(SIDE);
    expect(stop.params.command).toEqual({ kind: "native", control: { control: "stopChild", handle: "agent-1", attempt: 2 } });
    act(() => paneRoot.unmount());
    pane.remove();
  });

  it("answers the side chat's own question from its own dock and shows the host's model, never the stale chat setting", async () => {
    const engine = new Engine();
    engine.frames.set(SIDE, frame([], native()));
    const view = await mountSideChat(engine);
    const dock = view.querySelector("[data-native-dock]");
    expect(dock).not.toBeNull();
    expect(dock!.textContent).toContain("Which store?");
    expect(view.querySelector("[data-testid=composer-native-config]")!.textContent).toBe("Build · anthropic/claude-sonnet · low");
    expect(view.textContent).not.toContain("old-model");
    await act(async () => {
      Array.from(dock!.querySelectorAll("button")).find((b) => b.textContent?.includes("Postgres"))!.click();
    });
    await act(async () => {
      Array.from(dock!.querySelectorAll("button")).find((b) => b.textContent === "Send answer")!.click();
    });
    const queued = engine.of("QueueCommand");
    expect(queued).toHaveLength(1);
    expect(queued[0]!.params.chatId).toBe(SIDE);
    expect(queued[0]!.params.command).toEqual({
      kind: "native",
      control: {
        control: "answer",
        requestId: "ur-side",
        answers: [{ questionId: "q1", selectedOptions: ["Postgres"], freeformText: null, noneOfAbove: false }],
      },
    });
  });
});
