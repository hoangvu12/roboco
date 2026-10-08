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
 * wpn-93 (zeron #526's `631a8e03` + `6e4f363`) — the selected engine's
 * catalog validates the run identity before a fresh send: the settled
 * offered-on-the-selected-engine harness check plus `selectedModelUnavailable`
 * — a model carried from engine A is not confirmed on engine B until B's
 * own catalog lands and the reasoning/options reconciliation settles. The
 * same model id on two engines can carry different ladders and option sets;
 * the send waits for B's metadata, then sends B's.
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
import type { Chat, ChatConfig, HarnessDescriptor, Model } from "@roboco/proto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { Composer } from "../src/components/composer";
import { chatDrafts, composerDefaults } from "../src/lib/composer-draft";
import { PickerCatalog } from "../src/state/picker-catalog";
import { uiSettings } from "../src/state/ui-settings";
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
/** An offered Codex — the engine-switch engines' whole catalog. */
const CODEX: HarnessDescriptor = { ...DISABLED_CODEX, installed: true, enabled: true };
const CLAUDE_MODEL: Model = { id: "haiku", label: "Haiku", reasoningLevels: [], options: [] };
/** The same model id on two engines — B's ladder/option set differ. */
const SHARED_GPT_ID = "shared-gpt";
const GPT_A: Model = {
  id: SHARED_GPT_ID,
  label: "GPT A",
  description: null,
  reasoningLevels: ["low", "medium"],
  options: [
    {
      id: "contextWindow",
      label: "Context window",
      defaultChoice: "standard",
      choices: [
        { id: "standard", label: "Standard" },
        { id: "extended", label: "Extended" },
      ],
    },
  ],
};
const GPT_B: Model = {
  id: SHARED_GPT_ID,
  label: "GPT B",
  description: null,
  reasoningLevels: ["high"],
  options: [
    {
      id: "contextWindow",
      label: "Context window",
      defaultChoice: "standard",
      choices: [{ id: "standard", label: "Standard" }],
    },
  ],
};
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

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
  readonly engineInfo: { deviceId: string; capabilities: string[] };
  readonly calls: RpcCall[] = [];
  readonly harnessReply = new Deferred<HarnessDescriptor[]>();
  readonly models = new Map<string, Model[]>([["claude-code", [CLAUDE_MODEL]]]);
  /** wpn-93: per-harness deferred replies — a pending B catalog. */
  readonly pendingModels = new Map<string, Deferred<Model[]>>();
  /** wpn-93: per-harness rejections — a failed B discovery. */
  readonly modelErrors = new Map<string, Error>();
  readonly cache: FakeCache;

  constructor(cache: FakeCache, deviceId = "device-b") {
    this.cache = cache;
    this.engineInfo = { deviceId, capabilities: [] };
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
      const pending = this.pendingModels.get(harness);
      if (pending !== undefined) return pending.promise as Promise<T>;
      const error = this.modelErrors.get(harness);
      if (error !== undefined) return Promise.reject(error);
      return Promise.resolve((this.models.get(harness) ?? []) as T);
    }
    if (method === methods.MUTATE) {
      const request = params as { op?: string; chatId?: string };
      if (request.op === "createChat" && typeof request.chatId === "string") {
        this.cache.addChat(request.chatId);
      }
      return Promise.resolve({} as T);
    }
    if (method === methods.UPLOAD_CHUNK) {
      return Promise.resolve({} as T);
    }
    if (method === methods.UPLOAD_COMMIT) {
      return Promise.resolve({ path: "/uploaded/draft.png" } as T);
    }
    if (method === methods.QUEUE_COMMAND) {
      return Promise.resolve({ commandId: "run-command" } as T);
    }
    return Promise.reject(new Error(`Unexpected test RPC ${method}`));
  }

  /** The durable calls a blocked send must never leave. */
  durableCalls(): RpcCall[] {
    return this.calls.filter(({ method }) =>
      method === methods.MUTATE ||
      method === methods.UPLOAD_CHUNK ||
      method === methods.UPLOAD_COMMIT ||
      method === methods.QUEUE_COMMAND,
    );
  }
}

// ── The mounted composer harness ──────────────────────────────────────────

/** One engine bundle: client + cache + catalog + the session that routes them. */
interface TestEngine {
  readonly client: FakeClient;
  readonly cache: FakeCache;
  readonly catalog: PickerCatalog;
  readonly session: EngineSession;
}

const mountedCatalogs = new Set<PickerCatalog>();

function makeEngine(label: string, baseUrl: string, deviceId: string): TestEngine {
  const cache = new FakeCache();
  const client = new FakeClient(cache, deviceId);
  const catalog = new PickerCatalog(client);
  mountedCatalogs.add(catalog);
  const session = {
    engine: { baseUrl, credential: "cred", label },
    client,
    cache,
    catalog,
    transcripts: {},
  } as unknown as EngineSession;
  return { client, cache, catalog, session };
}

interface MountedComposer {
  readonly host: HTMLDivElement;
  readonly client: FakeClient;
  readonly cache: FakeCache;
  readonly catalog: PickerCatalog;
  readonly textarea: HTMLTextAreaElement;
  readonly sendButton: HTMLButtonElement;
  setTargetUnavailable(unavailable: boolean): void;
  switchTo(engine: TestEngine): void;
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

function mountComposer(
  chat: Chat,
  targetUnavailable = false,
  engine: TestEngine = makeEngine("Engine B", "http://engine-b.test", "device-b"),
): MountedComposer {
  const { client, cache, catalog, session } = engine;
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
    switchTo(nextEngine: TestEngine) {
      act(() =>
        root!.render(
          createElement(Composer, {
            ...props,
            session: nextEngine.session,
            catalog: nextEngine.catalog,
          }),
        ),
      );
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
  for (const catalog of mountedCatalogs) catalog.dispose();
  mountedCatalogs.clear();
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
  return mounted.client.durableCalls();
}

/** A blocked send: both submission paths are no-ops — no durable RPC,
 *  nothing consumed, the sticky draft intact. */
async function expectBlockedSendIsANoOp(mounted: MountedComposer): Promise<void> {
  expect(mounted.sendButton.disabled).toBe(true);
  pressEnter(mounted.textarea);
  pressSend(mounted);
  await act(async () => tick());
  expect(durableCalls(mounted)).toHaveLength(0);
  expectIntentStillPresent(mounted);
}

// ── The contracts ─────────────────────────────────────────────────────────

describe("the canvas target gate (f180fcb1)", () => {
  it("blocks an unresolved project without losing the draft and resumes after resolution", async () => {
    const mounted = mountComposer(freshChat(), true);
    await stageDraft(mounted);
    await completeHarnessCatalog(mounted);
    await waitFor(() => mounted.catalog.getModels("claude-code").loaded, "model catalog");

    // Unresolved target: the button refuses, Enter is swallowed, no RPC.
    await expectBlockedSendIsANoOp(mounted);

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
    await expectBlockedSendIsANoOp(mounted);
  });

  it("disables Send and makes Enter a no-op after a catalog error", async () => {
    const mounted = mountComposer(freshChat());
    await stageDraft(mounted);
    await failHarnessCatalog(mounted);

    expect(mounted.catalog.getHarnesses().error).not.toBeNull();
    await expectBlockedSendIsANoOp(mounted);
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

describe("the settled offered-on-selected-engine check (631a8e03)", () => {
  it("blocks an established chat whose committed harness is unoffered on this engine", async () => {
    // The catalog settles with Codex disabled: the committed config keeps
    // its harness (historical state, never rewritten), and the send is
    // blocked by the settled offered-on-the-selected-engine result — the
    // pickers surface the same state as "Codex is unavailable".
    const committed: ChatConfig = {
      harness: "codex",
      model: "gpt-5",
      reasoning: "medium",
      modelOptions: {},
      sandbox: "workspace-write",
    };
    const mounted = mountComposer({ ...establishedChat(), config: committed });
    await stageDraft(mounted);
    await completeHarnessCatalog(mounted);

    expect(mounted.catalog.getHarnesses().rows.find((row) => row.id === "codex")?.enabled).toBe(false);
    await expectBlockedSendIsANoOp(mounted);
  });

  it("a fresh chat heals an unoffered remembered harness to the offered one and sends on it", async () => {
    // The remembered harness is disabled on this engine: a FRESH chat is
    // preference, not history — `reconcileFreshDraftHarness` swaps the draft
    // to the offered harness, and the send mints the chat on that one.
    composerDefaults.update({ harness: "codex" });
    const mounted = mountComposer(freshChat());
    await stageDraft(mounted);
    await completeHarnessCatalog(mounted);
    await waitFor(() => mounted.catalog.getModels("claude-code").loaded, "model catalog");
    await waitFor(() => !mounted.sendButton.disabled, "send enabled after the harness heal");

    pressSend(mounted);
    await finishRun(mounted);
    const create = mounted.client.calls.find(
      ({ method, params }) =>
        method === methods.MUTATE && (params as { op?: string }).op === "createChat",
    );
    expect(create).toBeDefined();
    expect((create!.params as { config: { harness: string } }).config.harness).toBe("claude-code");
  });
});

// ── wpn-93: fresh model validation after an engine switch (6e4f363) ───────

async function pastePng(textarea: HTMLTextAreaElement): Promise<void> {
  const file = new File([PNG], "draft.png", { type: "image/png" });
  Object.defineProperty(file, "arrayBuffer", { value: () => Promise.resolve(PNG.slice().buffer) });
  const event = new Event("paste", { bubbles: true, cancelable: true });
  Object.defineProperty(event, "clipboardData", {
    value: { items: [{ kind: "file", getAsFile: () => file }] },
  });
  await act(async () => {
    textarea.dispatchEvent(event);
    await tick();
  });
}

/** Stage text AND an image (the unsent intent the blocked send must keep). */
async function stageTextAndImage(mounted: MountedComposer): Promise<void> {
  await stageDraft(mounted);
  await pastePng(mounted.textarea);
  expect(mounted.host.querySelectorAll(".composer-staged-thumb")).toHaveLength(1);
}

/** The sticky pick survives — nothing was consumed, defaults untouched. */
function expectSwitchedIntentStillPresent(mounted: MountedComposer): void {
  expect(composerDefaults.getSnapshot().harness).toBe("codex");
  expect(mounted.host.querySelectorAll(".composer-staged-thumb")).toHaveLength(1);
}

async function loadHarnessesFor(engine: TestEngine): Promise<void> {
  const flight = engine.catalog.loadHarnesses();
  engine.client.harnessReply.resolve([CODEX]);
  await flight;
  expect(engine.catalog.getHarnesses().loaded).toBe(true);
  expect(engine.catalog.getHarnesses().rows.find((row) => row.id === "codex")?.enabled).toBe(true);
}

async function loadModelsFor(engine: TestEngine, rows: readonly Model[]): Promise<void> {
  engine.client.models.set("codex", [...rows]);
  await engine.catalog.loadModels("codex");
  expect(engine.catalog.getModels("codex").loaded).toBe(true);
}

/** Pick A's engine-only context-window choice through the real picker. */
async function selectAOption(mounted: MountedComposer): Promise<void> {
  const trigger = mounted.host.querySelector<HTMLButtonElement>("#picker-model");
  if (trigger === null) throw new Error("Missing model picker trigger");
  await act(async () => {
    trigger.click();
    await tick();
  });
  const optionTrigger = document.querySelector<HTMLElement>(
    '[data-rb-row-key="model-setting-contextWindow"]',
  );
  if (optionTrigger === null) throw new Error("Missing context-window option trigger");
  await act(async () => {
    optionTrigger.click();
    await tick();
  });
  const extendedChoice = document.querySelector<HTMLElement>(
    '[data-rb-row-key="setting-choice-contextWindow-extended"]',
  );
  if (extendedChoice === null) throw new Error("Missing A-only extended context choice");
  await act(async () => {
    extendedChoice.click();
    await tick();
    trigger.click();
    await tick();
  });
}

type ModelLoadState = "pending" | "empty" | "error";

interface SwitchedComposer {
  readonly mounted: MountedComposer;
  readonly engineA: TestEngine;
  readonly engineB: TestEngine;
  readonly pendingModels: Deferred<Model[]> | null;
}

async function mountSwitchedComposer(state: ModelLoadState, chat?: Chat): Promise<SwitchedComposer> {
  const engineA = makeEngine("Engine A", "http://engine-a.test", "device-a");
  engineA.client.models.set("codex", [GPT_A]);
  await loadHarnessesFor(engineA);
  await loadModelsFor(engineA, [GPT_A]);

  const engineB = makeEngine("Engine B", "http://engine-b.test", "device-b");
  await loadHarnessesFor(engineB);
  let pendingModels: Deferred<Model[]> | null = null;
  if (state === "pending") {
    pendingModels = new Deferred<Model[]>();
    engineB.client.pendingModels.set("codex", pendingModels);
  } else if (state === "empty") {
    await loadModelsFor(engineB, []);
  } else {
    engineB.client.modelErrors.set("codex", new Error("B model discovery failed"));
    await engineB.catalog.loadModels("codex");
    await waitFor(
      () => engineB.catalog.getModels("codex").error !== null && !engineB.catalog.getModels("codex").loading,
      "B model catalog error",
    );
  }

  composerDefaults.update({
    harness: "codex",
    modelByHarness: { codex: { id: SHARED_GPT_ID, label: GPT_A.label } },
    modelLabels: { [SHARED_GPT_ID]: GPT_A.label },
    reasoning: "low",
    reasoningByModel: {},
    modelOptionsByModel: {},
  });
  const mounted = mountComposer(chat ?? freshChat(), false, engineA);
  await stageTextAndImage(mounted);
  if (chat === undefined) await selectAOption(mounted);
  expect(engineA.catalog.getHarnesses().rows.find((row) => row.id === "codex")?.enabled).toBe(true);
  expect(engineA.catalog.getModels("codex").loaded).toBe(true);
  expect(engineB.catalog.getHarnesses().rows.find((row) => row.id === "codex")?.enabled).toBe(true);
  expectSwitchedIntentStillPresent(mounted);

  const textareaBeforeSwitch = mounted.textarea;
  mounted.switchTo(engineB);
  expect(mounted.host.querySelector("textarea.composer-input")).toBe(textareaBeforeSwitch);
  expect(engineB.catalog.getHarnesses().loaded).toBe(true);
  expect(engineB.catalog.getHarnesses().rows.find((row) => row.id === "codex")?.enabled).toBe(true);
  if (state === "pending") {
    await waitFor(
      () => engineB.catalog.getModels("codex").loading &&
        engineB.client.calls.some(({ method, params }) =>
          method === methods.LIST_MODELS && (params as { harness: string }).harness === "codex",
        ),
      "B model request pending",
    );
  } else if (state === "empty") {
    expect(engineB.catalog.getModels("codex").loaded).toBe(true);
    expect(engineB.catalog.getModels("codex").rows).toHaveLength(0);
  } else {
    await waitFor(
      () => engineB.catalog.getModels("codex").error !== null && !engineB.catalog.getModels("codex").loading,
      "B model catalog error after engine switch",
    );
  }
  return { mounted, engineA, engineB, pendingModels };
}

async function settleSendAttempt(): Promise<void> {
  await act(async () => {
    for (let attempt = 0; attempt < 12; attempt += 1) await tick();
  });
}

describe("fresh composer model validation after an engine switch", () => {
  // The identity card drives the option pick (compact is the app default,
  // but this suite follows the composer-reasoning idiom and arms the
  // opt-out presentation so the nested tray renders the same rows zeron's
  // tests drive).
  beforeEach(() => {
    uiSettings.updateImmediate({ compactModelPicker: false });
  });
  afterEach(() => {
    uiSettings.updateImmediate({ compactModelPicker: true });
  });

  for (const state of ["pending", "empty", "error"] as const) {
    for (const action of ["Enter", "Send click"] as const) {
      it(`blocks ${action} when engine B's model catalog is ${state}`, async () => {
        const { mounted, engineA, engineB } = await mountSwitchedComposer(state);
        const sendWasDisabled = mounted.sendButton.disabled;
        if (action === "Enter") pressEnter(mounted.textarea);
        else pressSend(mounted);
        await settleSendAttempt();

        expect.soft(sendWasDisabled, "Send should be disabled for the unresolved B model").toBe(true);
        expect.soft(engineA.client.durableCalls(), "engine A must receive no durable RPC").toHaveLength(0);
        expect.soft(engineB.client.durableCalls(), "engine B must receive no durable RPC").toHaveLength(0);
        expect.soft(mounted.host.querySelectorAll(".composer-staged-thumb"), "staged image should survive the switch").toHaveLength(1);
        // Roboco's canvas drafts are per-engine (wpn-86): the blocked send
        // consumed nothing — A's canvas still holds the typed text, and the
        // round trip restores it in place.
        mounted.switchTo(engineA);
        await act(async () => { await tick(); });
        expect.soft(mounted.textarea.value, "A's canvas draft survives the blocked send").toBe("Keep this unsent draft");
      });
    }
  }

  it("recovers with B's changed metadata for the same model id before sending", async () => {
    const { mounted, engineA, engineB, pendingModels } = await mountSwitchedComposer("pending");
    if (pendingModels === null) throw new Error("Expected engine B's model reply to be pending");
    await act(async () => {
      pendingModels.resolve([GPT_B]);
      await pendingModels.promise;
      await tick();
    });
    await waitFor(() => engineB.catalog.getModels("codex").loaded, "B's valid model metadata");
    await waitFor(
      () => mounted.host.querySelector(".identity-chip-suffix")?.textContent?.includes("High") === true,
      "B's reasoning metadata to reconcile into the draft",
    );

    expect(engineB.catalog.getModels("codex").rows[0]?.id).toBe(SHARED_GPT_ID);
    expect(mounted.sendButton.disabled).toBe(false);
    expectSwitchedIntentStillPresent(mounted);
    pressSend(mounted);
    await waitFor(
      () => engineB.client.calls.some(({ method }) => method === methods.QUEUE_COMMAND),
      "engine B Run RPC",
    );

    const create = engineB.client.calls.find(({ method, params }) =>
      method === methods.MUTATE && (params as { op?: string }).op === "createChat",
    );
    expect(create).toBeDefined();
    const config = (create!.params as { config: ChatConfig }).config;
    expect(config.harness).toBe("codex");
    expect(config.model).toBe(SHARED_GPT_ID);
    expect.soft(config.reasoning, "fresh ChatConfig should use B's reasoning ladder").toBe("high");
    expect.soft(config.modelOptions, "fresh ChatConfig must discard A-only option picks").toEqual({});

    const queued = engineB.client.calls.find(({ method }) => method === methods.QUEUE_COMMAND);
    expect(queued).toBeDefined();
    const payload = queued!.params as {
      command: { request: { harness: string; model: string; reasoning: string; modelOptions: Record<string, unknown>; attachments: readonly string[] } };
      transfers: readonly { uploadId: string; fileName: string }[];
    };
    expect.soft(payload.command.request.harness).toBe("codex");
    expect.soft(payload.command.request.model).toBe(SHARED_GPT_ID);
    expect.soft(payload.command.request.reasoning, "RunRequest should use B's reasoning ladder").toBe("high");
    expect.soft(payload.command.request.modelOptions, "RunRequest must discard A-only option picks").toEqual({});
    expect(payload.command.request.attachments).toEqual(["/uploaded/draft.png"]);
    expect(payload.transfers).toHaveLength(1);
    expect(payload.transfers[0]!.fileName).toBe("draft.png");
    expect(engineA.client.durableCalls()).toHaveLength(0);
  });

  it("preserves an existing chat's committed config while engine B models are pending", async () => {
    const committed: ChatConfig = {
      harness: "codex",
      model: SHARED_GPT_ID,
      reasoning: "low",
      sandbox: "workspace-write",
      modelOptions: { contextWindow: "extended" },
    };
    const chat = { ...freshChat(), id: "existing-chat", config: committed, cwd: "/workspace/project" };
    const { mounted, engineA, engineB } = await mountSwitchedComposer("pending", chat);

    expect(engineB.catalog.getModels("codex").loading).toBe(true);
    expect(mounted.sendButton.disabled).toBe(false);
    pressSend(mounted);
    await waitFor(
      () => engineB.client.calls.some(({ method }) => method === methods.QUEUE_COMMAND),
      "existing-chat Run RPC on engine B",
    );

    const queued = engineB.client.calls.find(({ method }) => method === methods.QUEUE_COMMAND);
    const payload = queued!.params as {
      command: { request: { harness: string; model: string; reasoning: string; modelOptions: Record<string, unknown> } };
    };
    expect(payload.command.request).toMatchObject({
      harness: "codex",
      model: SHARED_GPT_ID,
      reasoning: "low",
      modelOptions: { contextWindow: "extended" },
    });
    expect(engineB.client.calls.some(({ method, params }) =>
      method === methods.MUTATE && (params as { op?: string }).op === "createChat",
    )).toBe(false);
    expect(engineA.client.durableCalls()).toHaveLength(0);
  });
});
