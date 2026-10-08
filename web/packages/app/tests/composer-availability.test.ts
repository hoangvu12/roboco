// @vitest-environment jsdom

/**
 * wpn-90 — the composer's submission gate (zeron #526's `d57b27fd` +
 * `f180fcb1`): a fresh send must wait for its target to be resolvable.
 *
 * - `targetUnavailable` (`f180fcb1`): the page hands the composer a canvas
 *   project that is loading or missing — the send button disables, Enter
 *   is a no-op, NO durable RPC leaves (no createChat, no QueueCommand),
 *   and the draft survives intact. Once the target resolves, the same
 *   draft sends.
 * - `selectedHarnessUnavailable` (`d57b27fd`): a NEW chat also waits for
 *   the selected engine's own harness catalog — loading and failed
 *   discoveries block the send (the sticky draft is preserved, never
 *   substituted). An ESTABLISHED chat keeps its committed config and is
 *   not blocked by a reloading catalog.
 *
 * The real Composer, ComposerPickers and PickerCatalog run over a
 * controlled RPC boundary (the composer-edit-failure.test.ts idiom: no
 * module mocks, jsdom gap stubs only) so discovery destinations and
 * durable-call destinations can be asserted.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { methods } from "@roboco/engine-client";
import type { EngineClient, WatchCacheSnapshot } from "@roboco/engine-client";
import type { Chat, HarnessDescriptor, Model } from "@roboco/proto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { Composer } from "../src/components/composer";
import { chatDrafts, composerDefaults } from "../src/lib/composer-draft";
import { PickerCatalog } from "../src/state/picker-catalog";
import type { EngineSession } from "../src/state/engine-session";

// ── jsdom gaps the mounted composer hits ─────────────────────────────────

// The composer's import graph resolves the new-thread artwork at module
// scope and prewarms its decode (appearance.ts `defaultPrewarm`) — jsdom's
// Image has no decode, and the unhandled rejection would poison the run.
// Patched in `vi.hoisted` so it lands BEFORE the imports evaluate.
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
  window.matchMedia = (() => ({
    matches: false,
    media: "",
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
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── The controlled RPC boundary ───────────────────────────────────────────

const CLAUDE: HarnessDescriptor = {
  id: "claude-code",
  name: "Claude Code",
  supportsSteering: true,
  steeringMode: "step-boundary",
  reasoningLevels: ["low", "medium", "high"],
  installed: true,
  canInstall: false,
  enabled: true,
};
const DISABLED_CODEX: HarnessDescriptor = {
  id: "codex",
  name: "Codex",
  supportsSteering: false,
  steeringMode: "turn-boundary",
  reasoningLevels: ["medium"],
  installed: false,
  canInstall: false,
  enabled: false,
};
const CLAUDE_MODEL: Model = { id: "haiku", label: "Haiku", reasoningLevels: [], options: [] };

/** A pending harness reply the test settles on demand. */
class Deferred<T> {
  readonly promise: Promise<T>;
  #resolve!: (value: T) => void;
  #reject!: (reason?: unknown) => void;
  #settled = false;

  constructor() {
    this.promise = new Promise<T>((resolve, reject) => {
      this.#resolve = resolve;
      this.#reject = reject;
    });
  }

  get settled(): boolean {
    return this.#settled;
  }

  resolve(value: T): void {
    if (this.#settled) return;
    this.#settled = true;
    this.#resolve(value);
  }

  reject(reason: unknown): void {
    if (this.#settled) return;
    this.#settled = true;
    this.#reject(reason);
  }
}

/** The watch-cache stand-in: chat rows land so `waitForChatRow` resolves. */
class FakeCache {
  #chats: Chat[] = [];
  readonly #listeners = new Set<() => void>();

  getSnapshot(): WatchCacheSnapshot {
    return { chats: { rows: this.#chats } } as unknown as WatchCacheSnapshot;
  }

  subscribe(listener: () => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  addChat(id: string): void {
    this.#chats = [...this.#chats, { id } as Chat];
    for (const listener of this.#listeners) listener();
  }
}

interface RpcCall {
  readonly method: string;
  readonly params: unknown;
}

class FakeClient {
  readonly state = "connected";
  readonly status = { state: "connected" as const };
  readonly engineInfo = { deviceId: "device-b", capabilities: [] as string[] };
  readonly calls: RpcCall[] = [];
  readonly harnessReply = new Deferred<HarnessDescriptor[]>();
  readonly cache: FakeCache;
  readonly #models = new Map<string, Model[]>([["claude-code", [CLAUDE_MODEL]]]);

  constructor(cache: FakeCache) {
    this.cache = cache;
  }

  onStatus(): () => void {
    return () => {};
  }

  call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    if (method === methods.LIST_HARNESSES) {
      return this.harnessReply.promise as Promise<T>;
    }
    if (method === methods.LIST_MODELS) {
      const harness = (params as { harness: string }).harness;
      return Promise.resolve((this.#models.get(harness) ?? []) as T);
    }
    if (method === methods.MUTATE) {
      const request = params as { op?: string; chatId?: string };
      if (request.op === "createChat" && typeof request.chatId === "string") {
        this.cache.addChat(request.chatId);
      }
      return Promise.resolve({} as T);
    }
    if (method === methods.QUEUE_COMMAND) {
      return Promise.resolve({ commandId: "run-command" } as T);
    }
    return Promise.reject(new Error(`Unexpected test RPC ${method}`));
  }
}

// ── The mounted composer harness ──────────────────────────────────────────

interface MountedComposer {
  readonly host: HTMLDivElement;
  readonly client: FakeClient;
  readonly cache: FakeCache;
  readonly catalog: PickerCatalog;
  readonly textarea: HTMLTextAreaElement;
  readonly sendButton: HTMLButtonElement;
  setTargetUnavailable(unavailable: boolean): void;
  unmount(): void;
}

const mounted: MountedComposer[] = [];
let root: Root | null = null;
let host: HTMLDivElement | null = null;

function freshChat(): Chat {
  return {
    id: "",
    deviceId: "device-b",
    title: null,
    archived: false,
    cwd: null,
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: new Date(0).toISOString(),
    spaceId: null,
  };
}

function establishedChat(): Chat {
  return { ...freshChat(), id: "chat-established", spaceId: "space-b" };
}

function mountComposer(chat: Chat, targetUnavailable = false): MountedComposer {
  const cache = new FakeCache();
  const client = new FakeClient(cache);
  const catalog = new PickerCatalog(client);
  const session = {
    engine: { baseUrl: "http://engine-b.test", credential: "cred", label: "Engine B" },
    client,
    cache,
    catalog,
    transcripts: {},
  } as unknown as EngineSession;
  const props = {
    session,
    chat,
    catalog,
    transcript: null,
    availableWidth: 720,
    targetUnavailable,
  } as Parameters<typeof Composer>[0];
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => root!.render(createElement(Composer, props)));
  const textarea = host.querySelector<HTMLTextAreaElement>("textarea.composer-input");
  const sendButton = host.querySelector<HTMLButtonElement>("button.composer-send");
  if (textarea === null || sendButton === null) {
    throw new Error("Fresh Composer controls did not mount");
  }
  const handle: MountedComposer = {
    host,
    client,
    cache,
    catalog,
    textarea,
    sendButton,
    setTargetUnavailable(unavailable: boolean) {
      act(() => root!.render(createElement(Composer, { ...props, targetUnavailable: unavailable })));
    },
    unmount() {
      act(() => root!.unmount());
      host?.remove();
      root = null;
      host = null;
    },
  };
  mounted.push(handle);
  return handle;
}

beforeEach(() => {
  chatDrafts.reset();
  composerDefaults.update({
    harness: "claude-code",
    modelByHarness: {},
    modelLabels: {},
    reasoning: null,
    modelOptionsByModel: {},
    device: null,
    project: null,
    noProject: false,
  });
});

afterEach(() => {
  while (mounted.length > 0) {
    const handle = mounted.pop()!;
    if (!handle.client.harnessReply.settled) {
      handle.client.harnessReply.resolve([CLAUDE, DISABLED_CODEX]);
    }
    handle.unmount();
  }
  document.body.replaceChildren();
  chatDrafts.reset();
  composerDefaults.update({
    harness: null,
    modelByHarness: {},
    modelLabels: {},
    reasoning: null,
    modelOptionsByModel: {},
    device: null,
    project: null,
    noProject: false,
  });
});

// ── The harness ───────────────────────────────────────────────────────────

function setTextareaValue(textarea: HTMLTextAreaElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  if (setter === undefined) throw new Error("HTMLTextAreaElement.value setter is unavailable");
  setter.call(textarea, value);
  act(() => textarea.dispatchEvent(new Event("input", { bubbles: true })));
}

async function stageDraft(mounted: MountedComposer): Promise<void> {
  setTextareaValue(mounted.textarea, "Keep this unsent draft");
  expect(mounted.textarea.value).toBe("Keep this unsent draft");
}

function pressEnter(textarea: HTMLTextAreaElement): void {
  act(() =>
    textarea.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "Enter",
        code: "Enter",
        bubbles: true,
        cancelable: true,
      }),
    ),
  );
}

function pressSend(mounted: MountedComposer): void {
  act(() =>
    mounted.sendButton.dispatchEvent(
      new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }),
    ),
  );
}

async function tick(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitFor(predicate: () => boolean, description: string): Promise<void> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (predicate()) return;
    await act(async () => tick());
  }
  throw new Error(`Timed out waiting for ${description}`);
}

async function completeHarnessCatalog(mounted: MountedComposer): Promise<void> {
  await act(async () => {
    mounted.client.harnessReply.resolve([CLAUDE, DISABLED_CODEX]);
    await mounted.client.harnessReply.promise;
    await tick();
  });
  await waitFor(() => mounted.catalog.getHarnesses().loaded, "harness catalog completion");
}

async function failHarnessCatalog(mounted: MountedComposer): Promise<void> {
  const caught = mounted.client.harnessReply.promise.catch(() => undefined);
  await act(async () => {
    mounted.client.harnessReply.reject(new Error("selected engine catalog unavailable"));
    await caught;
    await tick();
  });
  await waitFor(() => mounted.catalog.getHarnesses().error !== null, "harness catalog error");
}

async function finishRun(mounted: MountedComposer): Promise<void> {
  await waitFor(
    () => mounted.client.calls.some(({ method }) => method === methods.QUEUE_COMMAND),
    "Run command RPC",
  );
}

/** The blocked send preserves the sticky draft — nothing is consumed. */
function expectIntentStillPresent(mounted: MountedComposer): void {
  expect(composerDefaults.getSnapshot().harness).toBe("claude-code");
  expect(mounted.textarea.value).toBe("Keep this unsent draft");
}

/** The durable calls a blocked send must never leave. */
function durableCalls(mounted: MountedComposer): RpcCall[] {
  return mounted.client.calls.filter(({ method }) =>
    method === methods.MUTATE || method === methods.QUEUE_COMMAND,
  );
}

// ── The contracts ─────────────────────────────────────────────────────────

describe("the canvas target gate (f180fcb1)", () => {
  it("blocks an unresolved project without losing the draft and resumes after resolution", async () => {
    const mounted = mountComposer(freshChat(), true);
    await stageDraft(mounted);
    await completeHarnessCatalog(mounted);
    await waitFor(() => mounted.catalog.getModels("claude-code").loaded, "model catalog");

    // Unresolved target: the button refuses, Enter is swallowed, no RPC.
    expect(mounted.sendButton.disabled).toBe(true);
    pressEnter(mounted.textarea);
    pressSend(mounted);
    await act(async () => tick());
    expect(durableCalls(mounted)).toHaveLength(0);
    expectIntentStillPresent(mounted);

    // The project row lands: the SAME draft sends.
    mounted.setTargetUnavailable(false);
    expect(mounted.sendButton.disabled).toBe(false);
    expectIntentStillPresent(mounted);
    pressSend(mounted);
    await finishRun(mounted);
    expect(mounted.client.calls.some(({ method }) => method === methods.QUEUE_COMMAND)).toBe(true);
  });
});

describe("fresh sends wait for the selected engine's catalog (d57b27fd)", () => {
  it("disables Send and makes Enter a no-op while the catalog is loading", async () => {
    const mounted = mountComposer(freshChat());
    await stageDraft(mounted);

    expect(mounted.catalog.getHarnesses().loading).toBe(true);
    expect(mounted.sendButton.disabled).toBe(true);
    pressEnter(mounted.textarea);
    pressSend(mounted);
    await act(async () => tick());
    expect(durableCalls(mounted)).toHaveLength(0);
    expectIntentStillPresent(mounted);
  });

  it("disables Send and makes Enter a no-op after a catalog error", async () => {
    const mounted = mountComposer(freshChat());
    await stageDraft(mounted);
    await failHarnessCatalog(mounted);

    expect(mounted.catalog.getHarnesses().error).not.toBeNull();
    expect(mounted.sendButton.disabled).toBe(true);
    pressEnter(mounted.textarea);
    pressSend(mounted);
    await act(async () => tick());
    expect(durableCalls(mounted)).toHaveLength(0);
    expectIntentStillPresent(mounted);
  });

  it("sends once the selected engine confirms an offered harness", async () => {
    const mounted = mountComposer(freshChat());
    await stageDraft(mounted);
    expect(mounted.sendButton.disabled).toBe(true);

    await completeHarnessCatalog(mounted);
    await waitFor(() => mounted.catalog.getModels("claude-code").loaded, "model catalog");

    expect(mounted.sendButton.disabled).toBe(false);
    expectIntentStillPresent(mounted);
    pressSend(mounted);
    await finishRun(mounted);

    // The fresh chat is minted FIRST, carrying the draft's config — then
    // the run command rides the same engine.
    const create = mounted.client.calls.find(
      ({ method, params }) =>
        method === methods.MUTATE && (params as { op?: string }).op === "createChat",
    );
    expect(create).toBeDefined();
    expect((create!.params as { config: { harness: string } }).config.harness).toBe("claude-code");
    const queued = mounted.client.calls.find(({ method }) => method === methods.QUEUE_COMMAND);
    expect(queued).toBeDefined();
    const payload = queued!.params as { command: { request: { prompt: string } } };
    expect(payload.command.request.prompt).toContain("Keep this unsent draft");
  });

  it("an established chat is not blocked while its catalog reloads", async () => {
    // The established chat carries a committed config; a reloading catalog
    // never delays its sends (loading blocks FRESH chats only).
    const mounted = mountComposer(establishedChat());
    await stageDraft(mounted);

    expect(mounted.catalog.getHarnesses().loading).toBe(true);
    expect(mounted.sendButton.disabled).toBe(false);
    pressSend(mounted);
    await finishRun(mounted);
    expect(durableCalls(mounted)).toHaveLength(1);
    expect(durableCalls(mounted)[0]!.method).toBe(methods.QUEUE_COMMAND);
  });
});
