// @vitest-environment jsdom

/**
 * The native Mimir client slice, exercised as a user would meet it: replayed
 * host state through the real TranscriptStore, the real dock / notice / child
 * components mounted against a scripted engine client, and the pure view
 * models that decide wording. Every control assertion checks the exact
 * QueueCommand payload the engine receives. The scripted client keeps a
 * command ledger that `GetCommand` reads back, so receipt wording is asserted
 * against the host's typed outcome and never against the transport.
 * No JSX (createElement), per-file jsdom pragma only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type {
  NativeControl,
  NativeControlOutcome,
  NativeChatCatalog,
  NativeChatState,
  NativeChild,
  NativeNotice,
  NativeToolView,
  NativeUsage,
  SessionCommandEntry,
  SessionMessageEntry,
  TranscriptUpdate,
} from "@roboco/proto";
import { SlashPopup } from "../src/components/composer/slash-popup";
import { NativeDock } from "../src/components/native-dock";
import { NativeNoticeRow } from "../src/components/native-notice";
import { NativeChildPanel } from "../src/components/native-child";
import { MimirReadinessCard } from "../src/components/native-readiness";
import { NativeToolProvider, QuietCallsRow, useNativeToolActions } from "../src/components/native-tool-detail";
import { BlobStream, sendNativeControl, seriesRefs } from "../src/lib/native-actions";
import {
  childControlLabel,
  commandSendsNow,
  configAuthority,
  configSlotLabel,
  leadingCommand,
  nativeChipTitle,
  nativeConfigurationLabel,
  nativeGroupLabel,
  nativeInvocationRows,
  nativeRunDraft,
  nativeToolTitle,
  nativeToolVisible,
  noticeView,
  parseChildOutcome,
  parseToolDetail,
  readinessView,
  receiptFromCommand,
  receiptView,
  rowUnavailable,
} from "../src/lib/native";
import { estimateRowHeight } from "../src/components/transcript";
import { nativeInlineDetail, rowsForEntry, type TranscriptRow } from "../src/lib/transcript";
import { TranscriptStore } from "../src/state/transcript-store";

// The dialog shell reads the resolved appearance; the real module prewarms
// artwork through Image.decode, which jsdom lacks.
vi.mock("../src/state/appearance", () => ({
  useResolvedAppearance: () => "dark" as const,
}));

// ── Fixtures ────────────────────────────────────────────────────────────────

const CHAT = "chat-native";

const USAGE: NativeUsage = {
  inputTokens: 1200,
  outputTokens: 340,
  reasoningTokens: 0,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  totalTokens: 1540,
  provider: null,
};

function child(over: Partial<NativeChild> = {}): NativeChild {
  return {
    handle: "agent-1",
    attempt: 2,
    profile: "explore",
    description: "Map the auth module",
    model: "anthropic/claude-sonnet",
    status: "running",
    background: true,
    spawnedBy: "inv-1",
    completionPending: false,
    presentation: null,
    docId: "child-doc-1",
    oversized: false,
    attempts: [
      { attempt: 1, status: "failed", presentation: null, outcomeRef: `${CHAT}/agent-1.1.outcome` },
      { attempt: 2, status: "running", presentation: null, outcomeRef: null },
    ],
    ...over,
  };
}

function state(over: Partial<NativeChatState> = {}): NativeChatState {
  return {
    conversation: { id: "conv-1", cwd: "/work" },
    link: { state: "attached" },
    recovering: false,
    configuration: { provider: "anthropic", model: "claude-sonnet", reasoning: "high", mode: "build" },
    activeRequest: null,
    requests: [],
    plan: null,
    goal: null,
    userRequest: null,
    children: [],
    submissions: [],
    ...over,
  };
}

interface Call {
  readonly method: string;
  readonly params: unknown;
}

/** What the host's ledger holds for one command, or null while it is still pending. */
type Settlement = { status: SessionCommandEntry["status"]; resolution?: string | null; outcome?: NativeControlOutcome | null } | null;

const APPLIED: Settlement = { status: "applied", outcome: { outcome: "applied" } };
const CHILD_ACCEPTED: Settlement = { status: "applied", outcome: { outcome: "child", control: { result: "accepted" } } };
const refusedBusy = (message: string): Settlement => ({ status: "rejected", resolution: message, outcome: { outcome: "refused", kind: "busy", message } });
const unknownOutcome = (message: string): Settlement => ({ status: "unknown", resolution: message, outcome: { outcome: "unknown", message } });

function ledgerEntry(id: string, control: NativeControl, settlement: Settlement): SessionCommandEntry {
  return {
    id,
    payload: { kind: "native", control },
    issuedBy: "device",
    issuedAt: 1,
    basedOn: null,
    expiresAt: null,
    status: settlement?.status ?? "pending",
    resolution: settlement?.resolution ?? null,
    outcome: settlement?.outcome ?? null,
  };
}

/**
 * A scripted engine client: records every call and answers by method. Queued
 * controls land in a ledger that `GetCommand` reads, settled by `host`.
 */
function fakeClient(
  answers: Record<string, (params: unknown) => unknown> = {},
  host: (control: NativeControl, read: number) => Settlement = (control) => (control.control === "steerChild" || control.control === "stopChild" ? CHILD_ACCEPTED : APPLIED),
) {
  const calls: Call[] = [];
  const ledger = new Map<string, { control: NativeControl; reads: number }>();
  const caller = {
    calls,
    call<T>(method: string, params?: unknown): Promise<T> {
      calls.push({ method, params });
      const answer = answers[method];
      if (answer !== undefined) {
        try {
          return Promise.resolve(answer(params) as T);
        } catch (error) {
          return Promise.reject(error);
        }
      }
      if (method === "QueueCommand") {
        const id = `cmd-${ledger.size + 1}`;
        ledger.set(id, { control: (params as { command: { control: NativeControl } }).command.control, reads: 0 });
        return Promise.resolve({ commandId: id } as T);
      }
      if (method === "GetCommand") {
        const { chatId, commandId } = params as { chatId: string; commandId: string };
        const held = chatId === CHAT ? ledger.get(commandId) : undefined;
        if (held === undefined) {
          return Promise.resolve(null as T);
        }
        held.reads += 1;
        return Promise.resolve(ledgerEntry(commandId, held.control, host(held.control, held.reads)) as T);
      }
      return Promise.resolve(null as T);
    },
    controls(): unknown[] {
      return calls
        .filter((entry) => entry.method === "QueueCommand")
        .map((entry) => (entry.params as { command: { control: unknown } }).command.control);
    },
    count(method: string): number {
      return calls.filter((entry) => entry.method === method).length;
    },
  };
  return caller;
}

// ── Mounting ────────────────────────────────────────────────────────────────

let root: Root | null = null;
let host: HTMLElement | null = null;

beforeAll(() => {
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
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

afterEach(() => {
  act(() => root?.unmount());
  vi.useRealTimers();
  host?.remove();
  root = null;
  host = null;
});

async function mount(element: Parameters<Root["render"]>[0]): Promise<HTMLElement> {
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => root!.render(element));
  return host;
}

async function click(el: Element | null): Promise<void> {
  if (el === null) {
    throw new Error("nothing to click");
  }
  await act(async () => {
    (el as HTMLElement).click();
  });
}

function button(container: ParentNode, label: string | RegExp): HTMLButtonElement {
  const found = Array.from(container.querySelectorAll("button")).find((candidate) =>
    typeof label === "string" ? candidate.textContent?.trim() === label : label.test(candidate.textContent ?? ""),
  );
  if (found === undefined) {
    throw new Error(`no button ${String(label)}; have: ${Array.from(container.querySelectorAll("button")).map((b) => b.textContent).join(" | ")}`);
  }
  return found;
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

async function type(input: HTMLInputElement, value: string): Promise<void> {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
    setter.call(input, value);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
}

function dock(client: ReturnType<typeof fakeClient>, s: NativeChatState, connectivity: "connected" | "offline" | "reconnecting" = "connected") {
  return createElement(NativeDock, {
    client,
    chatId: CHAT,
    state: s,
    connectivity: connectivity as never,
    onOpenChild: () => {},
  });
}

// ── Replay ──────────────────────────────────────────────────────────────────

class FakeWatchClient {
  handlers: { onItem: (item: TranscriptUpdate, ctx: { generation: number }) => void } | null = null;
  readonly status = { state: "connected" };
  onStatus(): () => void {
    return () => {};
  }
  watch(_method: string, _params: unknown, handlers: FakeWatchClient["handlers"]): { cancel: () => void } {
    this.handlers = handlers;
    return { cancel: () => {} };
  }
  call(): Promise<never> {
    return Promise.resolve({} as never);
  }
}

describe("native state replay", () => {
  it("publishes host state with each frame, keeps identity when it did not change, and is null for other harnesses", () => {
    const client = new FakeWatchClient();
    const store = new TranscriptStore(client as unknown as EngineClient, CHAT);
    store.subscribe(() => {});
    const entries: SessionMessageEntry[] = [];
    const first = state({ activeRequest: "req-1" });
    client.handlers!.onItem({ contextUsage: null, reset: entries, native: first }, { generation: 1 });
    expect(store.getSnapshot().native).toEqual(first);

    const sameAgain = JSON.parse(JSON.stringify(first)) as NativeChatState;
    const held = store.getSnapshot().native;
    client.handlers!.onItem({ contextUsage: null, upsert: [], append: [], remove: [], count: 0, native: sameAgain }, { generation: 1 });
    expect(store.getSnapshot().native).toBe(held);

    const changed = state({ activeRequest: null, link: { state: "busy", message: "Open in another Mimir window" } });
    client.handlers!.onItem({ contextUsage: null, upsert: [], append: [], remove: [], count: 0, native: changed }, { generation: 1 });
    expect(store.getSnapshot().native).toEqual(changed);

    client.handlers!.onItem({ contextUsage: null, upsert: [], append: [], remove: [], count: 0 }, { generation: 1 });
    expect(store.getSnapshot().native).toBeNull();
  });
});

// ── Dock controls ───────────────────────────────────────────────────────────

describe("native dock", () => {
  it("answers a question with options, multiselect, freeform and none-of-above, sending the exact answer control", async () => {
    const client = fakeClient();
    const s = state({
      userRequest: {
        id: "ur-7",
        questions: [
          {
            id: "q-db",
            prompt: "Which databases?",
            allowMultiple: true,
            options: [
              { label: "Postgres", description: "Relational, battle tested" },
              { label: "SQLite", description: "Embedded" },
            ],
          },
          { id: "q-note", prompt: "Anything else?", allowMultiple: false, options: [{ label: "No", description: "" }] },
        ],
      },
    });
    const view = await mount(dock(client, s));
    expect(view.textContent).toContain("Relational, battle tested");
    expect(view.textContent).toContain("Embedded");

    const send = button(view, "Send answer");
    expect(send.disabled).toBe(true);
    await click(button(view, /Postgres/));
    await click(button(view, /SQLite/));
    const noneButtons = Array.from(view.querySelectorAll("button")).filter((b) => b.textContent === "None of the above");
    await click(noneButtons[1]!);
    expect(button(view, "Send answer").disabled).toBe(false);
    await click(button(view, "Send answer"));

    expect(client.controls()).toEqual([
      {
        control: "answer",
        requestId: "ur-7",
        answers: [
          { questionId: "q-db", selectedOptions: ["Postgres", "SQLite"], freeformText: null, noneOfAbove: false },
          { questionId: "q-note", selectedOptions: [], freeformText: null, noneOfAbove: true },
        ],
      },
    ]);
    expect(view.textContent).toContain("Queued. Waiting for Mimir to settle it.");
    expect(view.textContent).not.toMatch(/Applied|Answered|accepted/);
  });

  it("sends a freeform answer through the same control", async () => {
    const client = fakeClient();
    const s = state({
      userRequest: { id: "ur-8", questions: [{ id: "q", prompt: "Name?", allowMultiple: false, options: [] }] },
    });
    const view = await mount(dock(client, s));
    await type(view.querySelector("input.native-freeform") as HTMLInputElement, "ferris");
    await click(button(view, "Send answer"));
    expect(client.controls()).toEqual([
      { control: "answer", requestId: "ur-8", answers: [{ questionId: "q", selectedOptions: [], freeformText: "ferris", noneOfAbove: false }] },
    ]);
  });

  it("offers all three plan decisions with the exact plan id, and one decision at a time", async () => {
    const decisions = [
      ["Implement", "implement"],
      ["Implement with fresh context", "implementFresh"],
      ["Save and stop", "saveAndStop"],
    ] as const;
    const sent: unknown[] = [];
    for (const [label, decision] of decisions) {
      const client = fakeClient({ GetNativePlan: () => null });
      const s = state({ plan: { id: "plan-3", name: "Refactor auth", status: "reviewPending" } });
      const view = await mount(dock(client, s));
      await click(button(view, label));
      for (const [other] of decisions) {
        expect(button(view, other).disabled).toBe(true);
      }
      sent.push(...client.controls());
      act(() => root?.unmount());
      view.remove();
      root = null;
      host = null;
    }
    expect(sent).toEqual(decisions.map(([, decision]) => ({ control: "decidePlan", planId: "plan-3", decision })));
  });

  it("keeps a saved plan readable, drops a read for a replaced plan, and retries a failed read", async () => {
    let answer: (value: unknown) => void = () => {};
    let fail = false;
    const client = fakeClient({
      GetNativePlan: () => {
        if (fail) {
          throw new Error("bridge restarting");
        }
        return new Promise((resolve) => {
          answer = resolve;
        });
      },
    });
    const at = (plan: NativeChatState["plan"]) =>
      createElement(NativeDock, { client, chatId: CHAT, state: state({ plan }), connectivity: "connected" as never, onOpenChild: () => {} });
    const view = await mount(at({ id: "plan-1", name: "First", status: "reviewPending" }));
    await click(button(view, "Read plan"));
    await act(async () => root!.render(at({ id: "plan-2", name: "Second", status: "reviewPending" })));
    await act(async () => answer({ id: "plan-1", name: "First", markdown: "# The first plan", status: "reviewPending" }));
    expect(view.textContent).not.toContain("The first plan");
    await click(button(view, "Read plan"));
    await act(async () => answer({ id: "plan-2", name: "Second", markdown: "# The second plan", status: "reviewPending" }));
    expect(view.textContent).toContain("The second plan");
    await act(async () => root!.render(at({ id: "plan-2", name: "Second", status: "savedStopped" })));
    expect(view.querySelector("[data-testid=native-plan]")!.getAttribute("data-plan-status")).toBe("savedStopped");
    expect(view.textContent).toContain("The second plan");
    expect(() => button(view, "Implement")).toThrow();
    fail = true;
    await act(async () => root!.render(at({ id: "plan-3", name: "Third", status: "completed" })));
    await click(button(view, "Read plan"));
    await act(async () => {});
    expect(view.querySelector("[data-testid=native-plan-error]")!.textContent).toContain("bridge restarting");
    fail = false;
    await click(button(view, "Try again"));
    await act(async () => answer({ id: "plan-3", name: "Third", markdown: "# The third plan", status: "completed" }));
    expect(view.textContent).toContain("The third plan");
  });

  it("drops a goal editor and question draft when the dock moves to another chat", async () => {
    const client = fakeClient();
    const at = (chatId: string) =>
      createElement(NativeDock, { client, chatId, state: state(), connectivity: "connected" as never, onOpenChild: () => {} });
    const view = await mount(at("chat-a"));
    await click(button(view, "Start goal"));
    const objective = view.querySelector<HTMLInputElement>("[aria-label='Goal objective']")!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(objective, "Ship chat A");
      objective.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => root!.render(at("chat-b")));
    expect(view.querySelector("[aria-label='Goal objective']")).toBeNull();
    await click(button(view, "Start goal"));
    expect(view.querySelector<HTMLInputElement>("[aria-label='Goal objective']")!.value).toBe("");
    expect(client.controls()).toEqual([]);
  });

  it("says when Mimir's models could not be listed and lists them again on Try again", async () => {
    let fail = true;
    const client = fakeClient({
      GetNativeCatalog: () => {
        if (fail) {
          throw new Error("not attached");
        }
        return { commands: [], skills: [], providers: [{ id: "anthropic", name: "Anthropic", models: [{ id: "claude-sonnet", name: "Sonnet", reasoning: ["high"] }] }] };
      },
    });
    const view = await mount(dock(client, state()));
    await act(async () => {});
    expect(view.querySelector("[data-testid=native-models-failed]")!.textContent).toContain("not attached");
    fail = false;
    await click(button(view, "Try again"));
    await act(async () => {});
    expect(view.querySelector("[data-testid=native-models-failed]")).toBeNull();
    expect(view.querySelector("select[aria-label=Model]")).not.toBeNull();
  });

  it("replaces a header-only child with this chat's canonical inventory entry", async () => {
    const header = child({ description: "", profile: "", oversized: true, attempts: [] });
    const client = fakeClient({ ListNativeChildren: () => [child()] });
    const view = await mount(dock(client, state({ children: [header] })));
    await act(async () => {});
    const list = client.calls.filter((call) => call.method === "ListNativeChildren");
    expect(list).toEqual([{ method: "ListNativeChildren", params: { chatId: CHAT } }]);
    expect(view.querySelector("[data-testid=native-children]")!.textContent).toContain("Map the auth module");
  });

  it("shows goal phase, cause, reason and review gap, with Edit, Pause and Clear controls", async () => {
    const client = fakeClient();
    const goal = {
      id: "g1",
      objective: "Ship the migration",
      phase: "blocked" as const,
      cause: "reviewGap" as const,
      reason: "Needs a staging key",
      reviewGap: "No rollback test",
      workTurns: 4,
      startedAt: "2026-10-01T00:00:00Z",
      completion: null,
    };
    const view = await mount(dock(client, state({ goal })));
    const text = view.querySelector("[data-testid=native-goal]")!.textContent!;
    expect(text).toContain("Ship the migration");
    expect(text).toContain("Needs a staging key");
    expect(text).toContain("No rollback test");
    expect(text).toContain("4 work turns");
    await click(button(view, "Clear"));
    expect(client.controls()).toEqual([{ control: "changeGoal", change: { change: "clear" } }]);
  });

  it("starts a goal with an optional time limit in seconds", async () => {
    const client = fakeClient();
    const view = await mount(dock(client, state()));
    await click(button(view, "Start goal"));
    await type(view.querySelector("input[aria-label='Goal objective']") as HTMLInputElement, "Fix flaky tests");
    await type(view.querySelector("input[aria-label='Time limit in minutes']") as HTMLInputElement, "30");
    await click(button(view, "Start"));
    expect(client.controls()).toEqual([
      { control: "changeGoal", change: { change: "start", objective: "Fix flaky tests", durationSeconds: 1800 } },
    ]);
  });

  it("renders a completed goal with its completion evidence", async () => {
    const client = fakeClient();
    const goal = {
      id: "g2",
      objective: "Add retries",
      phase: "complete" as const,
      cause: "reviewAccepted" as const,
      reason: null,
      reviewGap: null,
      workTurns: 2,
      startedAt: "2026-10-01T00:00:00Z",
      completion: {
        checklist: [{ requirement: "Retries are bounded", evidence: "tests/retry.rs passes" }],
        limitations: "Not run on Windows",
        completedAt: "2026-10-01T01:00:00Z",
      },
    };
    const view = await mount(dock(client, state({ goal })));
    const completion = view.querySelector("[data-testid=native-goal-completion]")!.textContent!;
    expect(completion).toContain("Retries are bounded");
    expect(completion).toContain("tests/retry.rs passes");
    expect(completion).toContain("Not run on Windows");
  });

  it("disables Continue in Mimir with the engine's busy copy while work is active, and releases once idle", async () => {
    const busyClient = fakeClient();
    const busy = await mount(dock(busyClient, state({ activeRequest: "req-9" })));
    const release = button(busy, "Continue in Mimir");
    expect(release.disabled).toBe(true);
    expect(busy.textContent).toContain("Mimir is still working in this chat.");
    await click(release);
    expect(busyClient.controls()).toEqual([]);

    act(() => root?.unmount());
    host?.remove();
    const idleClient = fakeClient();
    const idle = await mount(dock(idleClient, state()));
    await click(button(idle, "Continue in Mimir"));
    expect(idleClient.controls()).toEqual([{ control: "release" }]);
  });

  it("offers Reconnect on the same chat when the link is interrupted", async () => {
    const client = fakeClient();
    const view = await mount(dock(client, state({ link: { state: "interrupted", message: "The Mimir process exited" } })));
    expect(view.textContent).toContain("The Mimir process exited");
    await click(button(view, "Reconnect"));
    expect(client.controls()).toEqual([{ control: "reconnect" }]);
  });

  it("keeps engine-offline, recovery and work status as separate statements", async () => {
    const client = fakeClient();
    const view = await mount(dock(client, state({ recovering: true, activeRequest: "req-1" }), "offline"));
    const offline = view.querySelector("[data-testid=native-engine-offline]")!.textContent!;
    const recovering = view.querySelector("[data-testid=native-recovering]")!.textContent!;
    expect(offline).toContain("engine is offline");
    expect(recovering).toContain("recovering");
    expect(recovering).not.toContain("offline");
    expect(view.querySelector("[data-testid=native-link]")!.textContent).toContain("Mimir is working");
  });

  it("shows honest delivery: unknown is not retried, only a replay-safe submission offers Retry", async () => {
    const client = fakeClient();
    const s = state({
      submissions: [
        { messageId: "m-steer", kind: "steer", delivery: { state: "unknown", message: "The connection dropped." }, retryable: false },
        { messageId: "m-prompt", kind: "prompt", delivery: { state: "refused", message: "Mimir is busy." }, retryable: true },
      ],
    });
    const view = await mount(dock(client, s));
    const rows = Array.from(view.querySelectorAll("[data-testid=native-delivery]"));
    expect(rows[0]!.textContent).toContain("Guidance delivery is unknown");
    expect(rows[0]!.textContent).toContain("It was not retried automatically.");
    expect(rows[0]!.querySelector("button")).toBeNull();
    expect(rows[1]!.textContent).toContain("Message refused by Mimir");
    await click(rows[1]!.querySelector("button"));
    expect(client.controls()).toEqual([{ control: "retrySubmission", messageId: "m-prompt" }]);
  });

  it("sends Configure naming only what changed, from the host-confirmed configuration", async () => {
    const catalog: NativeChatCatalog = {
      commands: [],
      skills: [],
      providers: [{ id: "anthropic", name: "Anthropic", models: [{ id: "claude-sonnet", name: "Sonnet", reasoning: ["low", "high"] }] }],
    };
    const client = fakeClient({ GetNativeCatalog: () => catalog });
    const view = await mount(dock(client, state()));
    await act(async () => {});
    await click(button(view, "Plan"));
    const reasoning = view.querySelector("select[aria-label=Reasoning]") as HTMLSelectElement;
    expect(Array.from(reasoning.options).map((o) => o.value)).toEqual(["low", "high"]);
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")!.set!;
      setter.call(reasoning, "low");
      reasoning.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(client.controls()).toEqual([
      { control: "configure", provider: null, model: null, reasoning: null, mode: "plan" },
      { control: "configure", provider: null, model: null, reasoning: "low", mode: null },
    ]);
    expect(view.querySelector("[data-testid=native-config]")!.textContent).toContain("Build");
  });

  it("lists delegated agents with explicit Open buttons", async () => {
    const client = fakeClient();
    const opened: string[] = [];
    const view = await mount(
      createElement(NativeDock, {
        client,
        chatId: CHAT,
        state: state({ children: [child()] }),
        connectivity: "connected" as never,
        onOpenChild: (opened_) => opened.push(opened_.docId),
      }),
    );
    expect(view.querySelector("[data-testid=native-children]")!.textContent).toContain("Map the auth module");
    await click(button(view, "Open"));
    expect(opened).toEqual(["child-doc-1"]);
  });
});

// ── Composer-facing models ──────────────────────────────────────────────────

describe("composer behavior against host state", () => {
  const draft = { model: "stale/model", reasoning: "low" } as never;

  const authority = (over: Partial<Parameters<typeof configAuthority>[0]>) =>
    configAuthority({ nativeChat: true, fresh: false, projected: true, state: state(), ...over });

  it("never lets stale per-chat model or reasoning override the host-confirmed configuration", () => {
    const host = authority({});
    expect(configSlotLabel(host)).toBe(nativeConfigurationLabel(state().configuration!));
    const sent = nativeRunDraft(draft, host) as unknown as { model: unknown; reasoning: unknown };
    expect(sent.model).toBeNull();
    expect(sent.reasoning).toBeNull();
  });

  it("holds an existing native chat until its host state lands, but not a fresh draft", () => {
    const waiting = authority({ projected: false, state: null });
    expect(waiting).toEqual({ kind: "awaitingHost" });
    expect(configSlotLabel(waiting)).toBe("Waiting for Mimir");
    expect(authority({ projected: false, state: null, fresh: true })).toEqual({ kind: "draft" });
    expect(authority({ nativeChat: false, projected: false, state: null })).toEqual({ kind: "draft" });
  });

  it("lets the user's choice through before the host has configured anything", () => {
    const unconfigured = authority({ state: state({ configuration: null }) });
    expect(unconfigured).toEqual({ kind: "draft" });
    expect(nativeRunDraft(draft, unconfigured)).toBe(draft);
    expect(configSlotLabel(unconfigured)).toBeNull();
  });

  it("sends a host command now unless its catalog entry is idle-only", () => {
    const idleOnly = (name: string) => name === "compact";
    expect(commandSendsNow("/goal ship it", idleOnly)).toBe(true);
    expect(commandSendsNow("\n  /goal ship it", idleOnly)).toBe(true);
    expect(commandSendsNow("/compact", idleOnly)).toBe(false);
    expect(commandSendsNow("please read /etc/hosts", idleOnly)).toBe(false);
    expect(commandSendsNow("    /goal indented code", idleOnly)).toBe(false);
    expect(leadingCommand("/goal  ship it ")).toEqual({ name: "goal", tail: "ship it" });
  });

  const CATALOG: NativeChatCatalog = {
    commands: [
      { name: "compact", description: "Compact the context", aliases: [], argument: null, idleOnly: true },
      { name: "terminal-only-thing", description: "Declared by the host", aliases: [], argument: "[arg]", idleOnly: false },
    ],
    skills: [{ name: "review", description: "Review code", path: null, bundled: false }],
    providers: [],
  };

  it("keeps catalog identities and marks idle-only commands unavailable only while working, with no allowlist", () => {
    const rows = nativeInvocationRows(CATALOG);
    const compact = rows.find((row) => row.name === "compact")!;
    expect(rowUnavailable(compact, true)).not.toBeNull();
    expect(rowUnavailable(compact, false)).toBeNull();
    const skill = rows.find((row) => row.name === "review")!;
    expect(skill.invocation).toMatchObject({ kind: "skill", name: "review", path: "harness-skill:review" });
    expect(rows.map((row) => row.name)).toContain("terminal-only-thing");
  });

  it("shows an idle-only command as unavailable in the popup and does not accept it", async () => {
    const rows = nativeInvocationRows(CATALOG);
    const accepted: number[] = [];
    const props = {
      token: { start: 0, end: 1, query: "" },
      rows,
      filtered: rows.map((_, ix) => ix),
      active: null,
      loading: false,
      error: null,
      skill: false,
      supported: true,
      separateFromSlash: false,
      onAccept: (ix: number) => accepted.push(ix),
      onDismiss: () => {},
      onCardMouseDown: () => {},
    };
    const working = await mount(createElement(SlashPopup, { ...props, busy: true }));
    const compactRow = Array.from(working.querySelectorAll(".composer-completion-row")).find((r) => r.textContent!.includes("/compact"))!;
    expect(compactRow.getAttribute("aria-disabled")).toBe("true");
    expect(compactRow.textContent).toContain("Available when Mimir is idle");
    await click(compactRow);
    expect(accepted).toEqual([]);
    const other = Array.from(working.querySelectorAll(".composer-completion-row")).find((r) => r.textContent!.includes("/terminal-only-thing"))!;
    await click(other);
    expect(accepted).toHaveLength(1);
  });
});

// ── Notices ─────────────────────────────────────────────────────────────────

const COMPLETION = {
  handle: "agent-1",
  attempt: 2,
  status: "completed" as const,
  description: "Map the auth module",
  resultPreview: "Found 3 entry points",
  resultTruncated: true,
  changedFiles: ["src/auth.rs"],
  omittedChangedFiles: 4,
  run: {
    attempt: 2,
    agentId: "a-1",
    agent: "explore",
    model: "anthropic/claude-sonnet",
    description: "Map the auth module",
    status: "completed" as const,
    continued: true,
    background: false,
    turns: 5,
    timeoutMs: null,
    remainingMs: null,
    phase: "done",
    toolUses: 12,
    tokens: 9000,
    contextPercent: null,
    compactions: 0,
    elapsedMs: 4000,
  },
  usage: USAGE,
};

const NOTICES: readonly NativeNotice[] = [
  { notice: "planLifecycle", planId: "p", name: "Refactor", status: "reviewPending" },
  { notice: "compaction", trigger: "Context was full", beforeTokens: 120000, afterTokens: null },
  { notice: "branchSummary", summary: "We explored two branches." },
  { notice: "pluginSnapshot", pluginId: "roboco", title: "Roboco plugin", revision: 3, fallback: "Snapshot unavailable", lines: { not: "text" } },
  { notice: "subagentCompletions", completions: [COMPLETION] },
  { notice: "commandDisplay", text: "/compact finished" },
  { notice: "status", text: "Model changed" },
  { notice: "interrupted", message: "The run was interrupted.", detailRef: `${CHAT}/interrupted-1.provisional` },
  { notice: "image", mediaType: "image/png", detailRef: `${CHAT}/img-1` },
  { notice: "unsupported", item: "hologram", detailRef: `${CHAT}/u-1.unsupported` },
];

describe("native notices", () => {
  it.each(NOTICES.map((notice) => [notice.notice, notice] as const))("renders the %s notice honestly", async (name, notice) => {
    const client = fakeClient({ FetchToolBlob: () => ({ encoding: "utf8", text: "{\"saved\":true}", offset: 0, totalBytes: 14, nextOffset: null }) });
    const view = await mount(createElement(NativeNoticeRow, { notice, client: client as unknown as EngineClient }));
    const root_ = view.querySelector(`[data-testid=native-notice-${name}]`);
    expect(root_).not.toBeNull();
    expect(root_!.textContent!.length).toBeGreaterThan(0);
  });

  it("shows every field of a subagent completion", async () => {
    const view = await mount(
      createElement(NativeNoticeRow, { notice: { notice: "subagentCompletions", completions: [COMPLETION] }, client: fakeClient() as unknown as EngineClient }),
    );
    const text = view.textContent!;
    for (const expected of [
      "Map the auth module",
      "agent-1, attempt 2",
      "explore on anthropic/claude-sonnet",
      "Found 3 entry points",
      "The result is shortened here",
      "src/auth.rs",
      "and 4 more changed files",
      "1,540 tokens",
      "5 turns, 12 tool uses",
      "continued",
    ]) {
      expect(text).toContain(expected);
    }
  });

  it("falls back to the host's text when a plugin snapshot has no readable lines", () => {
    expect(noticeView(NOTICES[3]!).body).toBe("Snapshot unavailable");
    expect(noticeView({ ...(NOTICES[3] as Extract<NativeNotice, { notice: "pluginSnapshot" }>), lines: ["a", "b"] }).body).toBe("a\nb");
  });

  it("marks an interruption as provisional and offers the kept record through the blob view", async () => {
    const client = fakeClient({
      FetchToolBlob: () => ({ encoding: "utf8", text: "[{\"kind\":\"text\"}]", offset: 0, totalBytes: 17, nextOffset: null }),
    });
    const notice = NOTICES[7]!;
    expect(noticeView(notice).body).toContain("provisional");
    const view = await mount(createElement(NativeNoticeRow, { notice, client: client as unknown as EngineClient }));
    await click(button(view, "Show the host record"));
    expect(client.calls.find((c) => c.method === "FetchToolBlob")!.params).toMatchObject({ blobRef: `${CHAT}/interrupted-1.provisional` });
    expect(view.querySelector("[data-testid=native-blob] pre")!.textContent).toBe("[{\"kind\":\"text\"}]");
  });

  it("estimates a height for every notice row", () => {
    for (const notice of NOTICES) {
      const row: TranscriptRow = {
        id: "r",
        version: 0,
        turnStart: false,
        rowKind: { kind: "notice", notice },
        entryId: "e",
        timestamp: null,
        copyText: null,
        compactFold: null,
      };
      expect(estimateRowHeight(row)).toBeGreaterThan(0);
    }
  });
});

// ── Tools ───────────────────────────────────────────────────────────────────

function view(over: Partial<NativeToolView> = {}): NativeToolView {
  return {
    name: "read",
    toolCallId: "c1",
    invocationId: "inv-c1",
    title: "Read src/a.rs",
    runningTitle: "Reading src/a.rs",
    kind: "fileRead",
    summary: null,
    locations: [],
    resultState: "normal",
    preview: "compact",
    semantic: null,
    quiet: false,
    group: null,
    subagent: null,
    progress: null,
    durationMs: null,
    detailRef: `${CHAT}/c1.native`,
    detailBytes: 100,
    child: null,
    ...over,
  };
}

function toolEntry(parts: { id: string; v: NativeToolView; isError?: boolean; resolved?: boolean }[]): SessionMessageEntry {
  return {
    id: "a1",
    role: "assistant",
    createdAt: 1000,
    deviceId: "d",
    status: null,
    parts: parts.map((part) => ({
      kind: "tool" as const,
      id: part.id,
      call: { kind: "native" as const, view: part.v },
      isError: part.isError ?? false,
      resolved: part.resolved ?? true,
    })),
  };
}

const parse = () => ({ blocks: [] });

describe("native tools", () => {
  it("shows the running title until the call settles", () => {
    expect(nativeToolTitle(view(), false)).toBe("Reading src/a.rs");
    expect(nativeToolTitle(view(), true)).toBe("Read src/a.rs");
  });

  it("renders each call's inline body as the host declared: markdown answers, compact and empty results bodiless", () => {
    const answer = nativeInlineDetail(view({ semantic: "reportMarkdown", preview: "compact" }), "## Found\n\n- **one** entry", null, null);
    expect(answer?.kind).toBe("thought");
    const runs = answer?.kind === "thought" ? answer.lines.flat() : [];
    expect(runs.some((run) => run.text.trim() === "Found" && run.style.bold)).toBe(true);
    expect(runs.some((run) => run.text.includes("##"))).toBe(false);
    expect(nativeInlineDetail(view({ preview: "compact" }), "raw text", null, null)).toBeNull();
    expect(nativeInlineDetail(view({ preview: "full", resultState: "noMatches" }), "raw text", null, null)).toBeNull();
    expect(nativeInlineDetail(view({ preview: "full" }), "raw text", null, null)).toEqual({ kind: "output", lines: ["raw text"], truncatedBy: 0 });
    expect(nativeChipTitle(view({ resultState: "empty" }), true)).toBe("Read src/a.rs · No output");
    expect(nativeChipTitle(view({ resultState: "empty" }), false)).toBe("Reading src/a.rs");
  });

  it("hides quiet successes behind a counted toggle but never a failure", () => {
    const entry = toolEntry([
      { id: "t1", v: view({ toolCallId: "1", quiet: true }) },
      { id: "t2", v: view({ toolCallId: "2", quiet: true }), isError: true },
      { id: "t3", v: view({ toolCallId: "3", quiet: true }) },
    ]);
    const hidden = rowsForEntry(entry, { parse });
    const kinds = hidden.map((row) => row.rowKind);
    expect(kinds.find((k) => k.kind === "quietCalls")).toEqual({ kind: "quietCalls", count: 2, shown: false });
    const tools = kinds.flatMap((k) => (k.kind === "toolGroup" ? k.tools : []));
    expect(tools.map((t) => t.call.kind === "native" && t.call.view.toolCallId)).toEqual(["2"]);

    const shown = rowsForEntry(entry, { parse, showQuiet: true }).map((row) => row.rowKind);
    expect(shown.find((k) => k.kind === "quietCalls")).toEqual({ kind: "quietCalls", count: 2, shown: true });
    const shownTools = shown.flatMap((k) => (k.kind === "toolGroup" ? k.tools : []));
    expect(shownTools).toHaveLength(3);
    expect(nativeToolVisible(view({ quiet: true }), false, false)).toBe(false);
    expect(nativeToolVisible(view({ quiet: true }), true, false)).toBe(true);
  });

  it("merges adjacent calls that share a group key and keeps every call inspectable", () => {
    const group = (item: string) => ({ key: "read-files", label: "Read", item });
    const entry = toolEntry([
      { id: "t1", v: view({ toolCallId: "1", group: group("a.rs") }) },
      { id: "t2", v: view({ toolCallId: "2", group: group("b.rs"), detailRef: `${CHAT}/2.native` }) },
      { id: "t3", v: view({ toolCallId: "3", group: null }) },
    ]);
    const rows = rowsForEntry(entry, { parse });
    const tools = rows.flatMap((row) => (row.rowKind.kind === "toolGroup" ? row.rowKind.tools : []));
    expect(tools).toHaveLength(2);
    const merged = tools[0]!;
    expect(merged.call.kind === "native" && merged.call.view.title).toBe("Read · a.rs, b.rs");
    expect(merged.native!.map((v) => v.toolCallId)).toEqual(["1", "2"]);
    expect(nativeGroupLabel([view({ group: group("x") }), view({ group: { key: "other", label: "Read", item: "y" } })])).toBeNull();
  });

  it("rebuilds the row when a running call settles with a different title", () => {
    const running = rowsForEntry(toolEntry([{ id: "t1", v: view(), resolved: false }]), { parse });
    const settled = rowsForEntry(toolEntry([{ id: "t1", v: view() }]), { parse });
    expect(running[0]!.version).not.toBe(settled[0]!.version);
  });

  it("rejects a detail blob that is not a tool detail and keeps host JSON as unknown", () => {
    expect(parseToolDetail({ nope: true })).toBeNull();
    expect(parseToolDetail({ name: "x", toolCallId: "1", view: view(), progress: { blobRef: 5 } })).toBeNull();
    const detail = parseToolDetail({ name: "x", toolCallId: "1", view: view(), input: { path: "a" }, output: "ok", displayContent: [{ text: "ok" }] });
    expect(detail?.input).toEqual({ path: "a" });
    expect(detail?.displayContent).toHaveLength(1);
  });
});

describe("tool detail dialog", () => {
  const detail = {
    name: "read",
    toolCallId: "c1",
    invocationId: null,
    input: { path: "src/a.rs" },
    isError: false,
    output: "# Public answer",
    displayContent: [{ type: "resource", resource_text: { text: "kept" } }],
    details: null,
    compactions: [],
    progress: { blobRef: `${CHAT}/c1.progress`, chunks: 1, bytes: 30, records: 2 },
    stream: null,
    view: view({ semantic: "answerMarkdown" }),
  };

  it("lazily reads the detail record and shows public input, semantic output and display content, never model-only text", async () => {
    const client = fakeClient({
      FetchToolBlob: (params) => {
        const ref = (params as { blobRef: string }).blobRef;
        if (ref === `${CHAT}/c1.native`) {
          const text = JSON.stringify(detail);
          return { encoding: "utf8", text, offset: 0, totalBytes: text.length, nextOffset: null };
        }
        const text = "\"first\"\n\"second\"\n";
        return { encoding: "utf8", text, offset: 0, totalBytes: text.length, nextOffset: null };
      },
    });
    function Trigger() {
      const actions = useNativeToolActions();
      return createElement("button", { onClick: () => actions.openDetail([view()]) }, "open details");
    }
    await mount(
      createElement(NativeToolProvider, { client: client as unknown as EngineClient, quietShown: false, onToggleQuiet: () => {}, entries: [], children: createElement(Trigger) }),
    );
    await click(button(document.body, "open details"));
    await act(async () => {});
    const dialog = document.querySelector("[data-testid=native-tool-detail]")!;
    expect(dialog.textContent).toContain("Public answer");
    expect(dialog.querySelector("[data-testid=native-detail-input]")!.textContent).toContain("src/a.rs");
    expect(dialog.querySelector("[data-testid=native-detail-display]")!.textContent).toContain("kept");
    expect(client.calls.filter((c) => c.method === "FetchToolBlob")).toHaveLength(1);
    await click(button(dialog, /Show Progress/));
    await act(async () => {});
    expect(dialog.querySelector("[data-testid=native-detail-progress]")!.textContent).toBe("firstsecond");
  });

  function entry(parts: readonly { view: NativeToolView; resolved: boolean }[]): SessionMessageEntry {
    return {
      id: "a1",
      role: "assistant",
      createdAt: 1,
      deviceId: "d",
      status: null,
      parts: parts.map(({ view: v, resolved }) => ({
        kind: "tool" as const,
        id: v.invocationId ?? v.toolCallId,
        call: { kind: "native" as const, view: v },
        isError: false,
        resolved,
      })),
    } as SessionMessageEntry;
  }

  function opener(views: readonly NativeToolView[]) {
    return function Trigger() {
      const actions = useNativeToolActions();
      return createElement("button", { onClick: () => actions.openDetail(views) }, "open details");
    };
  }

  it("reads an open call's record again when the call settles, never keeping the running record", async () => {
    let settled = false;
    const client = fakeClient({
      FetchToolBlob: () => {
        const text = JSON.stringify({ ...detail, progress: null, output: settled ? "# Settled answer" : "# Still running", view: view() });
        return { encoding: "utf8", text, offset: 0, totalBytes: text.length, nextOffset: null };
      },
    });
    const running = view({ durationMs: null });
    const done = view({ durationMs: 40, resultState: "noMatches" });
    const provider = (resolved: boolean) =>
      createElement(NativeToolProvider, {
        client: client as unknown as EngineClient,
        quietShown: false,
        onToggleQuiet: () => {},
        entries: [entry([{ view: resolved ? done : running, resolved }])],
        children: createElement(opener([running])),
      });
    await mount(provider(false));
    await click(button(document.body, "open details"));
    await act(async () => {});
    const dialog = () => document.querySelector("[data-testid=native-tool-detail]")!;
    expect(dialog().textContent).toContain("Still running");
    settled = true;
    await act(async () => root!.render(provider(true)));
    await act(async () => {});
    expect(client.calls.filter((c) => c.method === "FetchToolBlob")).toHaveLength(2);
    expect(dialog().textContent).toContain("Settled answer");
    expect(dialog().textContent).not.toContain("Still running");
    expect(dialog().querySelector("h3")!.textContent).toBe("Read src/a.rs · No matches");
  });

  it("opens a large record as stored, then reads it whole on request with its series and every public field", async () => {
    const big = view({ detailBytes: 5 * 1024 * 1024 });
    const record = JSON.stringify({
      ...detail,
      rawInput: '{"path":  "src/a.rs"}',
      outputProfile: { lines: 1, kind: "answer" },
      compactions: [{ reason: "size", kept: 10 }],
      view: big,
    });
    const progress = '"first"\n"second"\n';
    const client = fakeClient({
      FetchToolBlob: (params) => {
        const { blobRef, offset } = params as { blobRef: string; offset: number };
        const text = blobRef === `${CHAT}/c1.native` ? record : progress;
        const end = Math.min(text.length, offset + 64);
        return { encoding: "utf8", text: text.slice(offset, end), offset, totalBytes: text.length, nextOffset: end < text.length ? end : null };
      },
    });
    const downloads: { name: string; text: string }[] = [];
    const urls = URL as unknown as { createObjectURL?: (blob: Blob) => string; revokeObjectURL?: (url: string) => void };
    const saved = { create: urls.createObjectURL, revoke: urls.revokeObjectURL };
    urls.createObjectURL = (blob) => {
      void blob.text().then((text) => downloads.push({ name: "export", text }));
      return "blob:fixture";
    };
    urls.revokeObjectURL = () => {};
    await mount(
      createElement(NativeToolProvider, {
        client: client as unknown as EngineClient,
        quietShown: false,
        onToggleQuiet: () => {},
        entries: [entry([{ view: big, resolved: true }])],
        children: createElement(opener([big])),
      }),
    );
    await click(button(document.body, "open details"));
    await act(async () => {});
    const dialog = document.querySelector("[data-testid=native-tool-detail]")!;
    expect(dialog.querySelector("[data-testid=native-detail-large]")!.textContent).toContain("5.0 MB");
    const reads = client.calls.filter((c) => c.method === "FetchToolBlob").length;
    expect(reads).toBe(1);
    await click(button(dialog, "Read the whole record"));
    await act(async () => {});
    expect(dialog.querySelector("[data-testid=native-detail-raw-input]")!.textContent).toBe('{"path":  "src/a.rs"}');
    expect(dialog.querySelector("[data-testid=native-detail-output-profile]")!.textContent).toContain('"kind": "answer"');
    expect(dialog.querySelector("[data-testid=native-detail-compactions]")!.textContent).toContain('"reason": "size"');
    await click(button(dialog, /Show Progress/));
    await act(async () => {});
    expect(dialog.querySelector("[data-testid=native-detail-progress]")!.textContent).toBe("firstsecond");
    await click(button(dialog, "Download complete export"));
    await act(async () => {});
    await act(async () => {});
    expect(downloads).toHaveLength(1);
    expect(downloads[0]!.text).toContain(`=== Detail record: ${CHAT}/c1.native (the host's public JSON as stored) (${record.length} bytes) ===\n${record}\n=== end ===`);
    expect(downloads[0]!.text).toContain(`=== Progress lines (JSON lines): ${CHAT}/c1.progress (1 chunks, 2 records) (${progress.length} bytes) ===\n${progress}\n=== end ===`);
    urls.createObjectURL = saved.create;
    urls.revokeObjectURL = saved.revoke;
  });

  it("says when a complete export could not be read", async () => {
    const client = fakeClient({
      FetchToolBlob: () => {
        throw new Error("engine offline");
      },
    });
    const big = view({ detailBytes: 5 * 1024 * 1024 });
    await mount(
      createElement(NativeToolProvider, {
        client: client as unknown as EngineClient,
        quietShown: false,
        onToggleQuiet: () => {},
        entries: [],
        children: createElement(opener([big])),
      }),
    );
    await click(button(document.body, "open details"));
    await act(async () => {});
    const dialog = document.querySelector("[data-testid=native-tool-detail]")!;
    await click(button(dialog, "Download complete export"));
    await act(async () => {});
    expect(dialog.querySelector("[data-testid=native-export-error]")!.textContent).toContain("engine offline");
  });

  it("toggles quiet calls from the row", async () => {
    let toggled = 0;
    const view_ = await mount(
      createElement(NativeToolProvider, {
        client: fakeClient() as unknown as EngineClient,
        quietShown: false,
        onToggleQuiet: () => {
          toggled += 1;
        },
        entries: [],
        children: createElement(QuietCallsRow, { count: 3, shown: false }),
      }),
    );
    expect(view_.textContent).toContain("Quiet calls hidden: 3");
    await click(button(view_, "Show"));
    expect(toggled).toBe(1);
  });
});

describe("blob windows", () => {
  it("decodes a multi-byte character that is split across window and chunk edges", async () => {
    const bytes = new TextEncoder().encode("héllo wörld");
    const split = bytes.indexOf(0xc3) + 1;
    const first = bytes.slice(0, split);
    const second = bytes.slice(split);
    const b64 = (data: Uint8Array) => btoa(String.fromCharCode(...data));
    const client = fakeClient({
      FetchToolBlob: (params) => {
        const { blobRef } = params as { blobRef: string };
        const chunk = blobRef.endsWith("000000") ? first : second;
        return { encoding: "base64", text: b64(chunk), offset: 0, totalBytes: chunk.length, nextOffset: null };
      },
    });
    const stream = new BlobStream(client, seriesRefs({ blobRef: "b", chunks: 2, bytes: bytes.length, records: 1 }));
    await stream.readNext(1024);
    expect(stream.done).toBe(false);
    await stream.readAll();
    expect(stream.done).toBe(true);
    expect(stream.text()).toBe("héllo wörld");
    expect(stream.loadedBytes).toBe(bytes.length);
  });

  it("reads in bounded windows and follows nextOffset", async () => {
    const offsets: number[] = [];
    const client = fakeClient({
      FetchToolBlob: (params) => {
        const { offset } = params as { offset: number };
        offsets.push(offset);
        return offset === 0
          ? { encoding: "utf8", text: "abc", offset: 0, totalBytes: 6, nextOffset: 3 }
          : { encoding: "utf8", text: "def", offset: 3, totalBytes: 6, nextOffset: null };
      },
    });
    const stream = new BlobStream(client, ["one"]);
    await stream.readNext(3);
    expect(stream.text()).toBe("abc");
    expect(stream.done).toBe(false);
    await stream.readNext(3);
    expect(stream.text()).toBe("abcdef");
    expect(offsets).toEqual([0, 3]);
  });

  it("propagates a failed read so a partial record is never offered as complete", async () => {
    const client = fakeClient({
      FetchToolBlob: () => {
        throw new Error("blob missing");
      },
    });
    await expect(new BlobStream(client, ["gone"]).readAll()).rejects.toThrow("blob missing");
  });

  it("keeps a series whole and in order when two reads start at once", async () => {
    const chunks: Record<string, string> = { "s.000000": '"compiling"\n"testing"\n', "s.000001": '"summarising"\n' };
    const client = fakeClient({
      FetchToolBlob: async (params) => {
        const { blobRef } = params as { blobRef: string };
        await Promise.resolve();
        const text = chunks[blobRef]!;
        return { encoding: "utf8", text, offset: 0, totalBytes: text.length, nextOffset: null };
      },
    });
    const stream = new BlobStream(client, ["s.000000", "s.000001"]);
    await Promise.all([stream.readNext(), stream.readNext()]);
    expect(stream.done).toBe(true);
    expect(stream.text()).toBe('"compiling"\n"testing"\n"summarising"\n');
  });
});

// ── Children ────────────────────────────────────────────────────────────────

describe("native child panel", () => {
  const outcome = (attempt: number, over: Record<string, unknown> = {}) => ({
    handle: "agent-1",
    attempt,
    status: "completed",
    result: "All done",
    error: null,
    changedFiles: ["src/lib.rs"],
    usage: USAGE,
    ...over,
  });

  it("steers and stops the exact attempt, and says guidance is queued, not delivered", async () => {
    vi.useFakeTimers();
    const client = fakeClient();
    const view_ = await mount(createElement(NativeChildPanel, { client, chatId: CHAT, child: child() }));
    expect(view_.querySelector("[data-testid=native-child-head]")!.textContent).toContain("Attempt 2");
    await type(view_.querySelector("input[aria-label='Guidance for this agent']") as HTMLInputElement, "focus on login");
    await click(button(view_, "Send guidance"));
    expect(view_.querySelector("[data-testid=native-child-feedback]")!.textContent).toBe("Waiting for Mimir to accept the guidance.");
    await advance(1000);
    expect(view_.querySelector("[data-testid=native-child-feedback]")!.textContent).toBe("Guidance queued");
    await click(button(view_, "Stop"));
    expect(view_.querySelector("[data-testid=native-child-feedback]")!.textContent).toBe("Waiting for Mimir to accept the stop.");
    await advance(1000);
    expect(view_.querySelector("[data-testid=native-child-feedback]")!.textContent).toBe("Stopping");
    expect(client.controls()).toEqual([
      { control: "steerChild", handle: "agent-1", attempt: 2, text: "focus on login" },
      { control: "stopChild", handle: "agent-1", attempt: 2 },
    ]);
  });

  it("does not offer controls for a settled child and shows its complete current outcome", async () => {
    const client = fakeClient({ GetNativeChildOutcome: () => outcome(2) });
    const settled = child({ status: "completed", attempts: [{ attempt: 2, status: "completed", presentation: null, outcomeRef: null }] });
    const view_ = await mount(createElement(NativeChildPanel, { client, chatId: CHAT, child: settled }));
    await act(async () => {});
    expect(view_.querySelector("[data-testid=native-child-controls]")).toBeNull();
    const text = view_.querySelector("[data-testid=native-child-outcome]")!.textContent!;
    expect(text).toContain("All done");
    expect(text).toContain("src/lib.rs");
    expect(text).toContain("1,540 tokens");
    expect(client.calls.find((c) => c.method === "GetNativeChildOutcome")!.params).toEqual({ chatId: CHAT, handle: "agent-1", attempt: 2 });
  });

  it("refuses an outcome that belongs to a different attempt than the one shown", async () => {
    const client = fakeClient({ GetNativeChildOutcome: () => outcome(1) });
    const view_ = await mount(
      createElement(NativeChildPanel, { client, chatId: CHAT, child: child({ status: "completed" }) }),
    );
    await act(async () => {});
    expect(view_.querySelector("[data-testid=native-child-outcome]")).toBeNull();
    expect(view_.textContent).toContain("returned the outcome of attempt 1, not attempt 2");
  });

  it("reads a historical attempt's outcome from its own record", async () => {
    const client = fakeClient({
      FetchToolBlob: () => {
        const text = JSON.stringify(outcome(1, { status: "failed", result: null, error: "Timed out" }));
        return { encoding: "utf8", text, offset: 0, totalBytes: text.length, nextOffset: null };
      },
    });
    const view_ = await mount(createElement(NativeChildPanel, { client, chatId: CHAT, child: child() }));
    await click(button(view_, /Attempt 1/));
    await act(async () => {});
    expect(view_.querySelector("[data-testid=native-child-attempt]")!.textContent).toContain("Timed out");
    expect(client.calls.find((c) => c.method === "FetchToolBlob")!.params).toMatchObject({ blobRef: `${CHAT}/agent-1.1.outcome` });
  });

  it("shows an accepted control that the child has since moved past as stale", () => {
    const accepted = { kind: "settled", commandId: "cmd-1", verdict: { kind: "childAccepted" } } as const;
    const label = childControlLabel({ action: "guidance", attempt: 1, receipt: accepted }, child({ attempt: 2 }));
    expect(label?.text).toContain("Attempt 1 has ended");
    expect(label?.text).toContain("was not sent to attempt 2");
    expect(childControlLabel({ action: "stop", attempt: 2, receipt: accepted }, child())?.text).toBe("Stopping");
    expect(childControlLabel({ action: "stop", attempt: 2, receipt: accepted }, child({ status: "completed" }))).toBeNull();
  });

  it("validates an outcome at the boundary", () => {
    expect(parseChildOutcome({ handle: "h", attempt: 1 })).toBeNull();
    expect(parseChildOutcome(outcome(1, { status: "exploded" }))).toBeNull();
    expect(parseChildOutcome(outcome(1))?.attempt).toBe(1);
  });
});

// ── Setup ───────────────────────────────────────────────────────────────────

describe("Mimir setup readiness", () => {
  it("shows the manual step for a missing plugin and never runs anything", async () => {
    const client = fakeClient({
      GetNativeReadiness: () => ({ state: "pluginMissing", message: "Plugin roboco not found", action: "mimir plugin install roboco" }),
    });
    const view_ = await mount(createElement(MimirReadinessCard, { client }));
    await act(async () => {});
    expect(view_.querySelector("[data-testid=native-readiness-title]")!.textContent).toContain("plugin is not installed");
    expect(view_.querySelector("[data-testid=native-readiness-action]")!.textContent).toBe("mimir plugin install roboco");
    expect(view_.textContent).toContain("will not run it for you");
    await click(button(view_, "Check again"));
    expect(client.calls.map((c) => [c.method, c.params])).toEqual([
      ["GetNativeReadiness", { force: false }],
      ["GetNativeReadiness", { force: true }],
    ]);
    expect(client.calls.every((c) => c.method === "GetNativeReadiness")).toBe(true);
  });

  it("covers every readiness state", () => {
    expect(readinessView({ state: "ready", executable: "/bin/mimir", version: "1.2.3", bridge: "v1" }).ready).toBe(true);
    expect(readinessView({ state: "missingExecutable", action: "install mimir" }).action).toBe("install mimir");
    expect(readinessView({ state: "incompatible", message: "too old", action: "upgrade" }).detail).toBe("too old");
    expect(readinessView({ state: "noModels", action: "mimir login" }).action).toBe("mimir login");
    expect(readinessView({ state: "failed", message: "spawn failed" }).action).toBeNull();
  });
});

// ── Command receipts ────────────────────────────────────────────────────────

describe("command receipts", () => {
  const interrupted = () => state({ link: { state: "interrupted", message: "The Mimir process exited" } });
  const feedback = (view: HTMLElement) => view.querySelector("[data-receipt]");

  it("reads each typed ledger outcome as its own receipt", () => {
    const entry = (settlement: Settlement) => ledgerEntry("cmd-9", { control: "release" }, settlement);
    const verdict = (settlement: Settlement) => {
      const receipt = receiptFromCommand(entry(settlement));
      return receipt.kind === "settled" ? receipt.verdict : receipt;
    };
    expect(receiptFromCommand(entry(null))).toEqual({ kind: "pending", commandId: "cmd-9" });
    expect(verdict(APPLIED)).toEqual({ kind: "applied" });
    expect(verdict(CHILD_ACCEPTED)).toEqual({ kind: "childAccepted" });
    expect(verdict({ status: "applied", outcome: { outcome: "child", control: { result: "attemptChanged", attempt: 3 } } })).toEqual({ kind: "childMoved", attempt: 3 });
    expect(verdict({ status: "applied", outcome: { outcome: "child", control: { result: "terminal" } } })).toEqual({ kind: "childEnded", reason: "terminal" });
    expect(verdict({ status: "applied", outcome: { outcome: "child", control: { result: "finalizing" } } })).toEqual({ kind: "childEnded", reason: "finalizing" });
    expect(verdict(refusedBusy("The TUI owns this conversation."))).toEqual({ kind: "refused", errorKind: "busy", message: "The TUI owns this conversation." });
    expect(verdict(unknownOutcome("Reply lost"))).toEqual({ kind: "unknown", message: "Reply lost" });
    expect(verdict({ status: "unknown", resolution: "Reply lost" })).toEqual({ kind: "unknown", message: "Reply lost" });
    expect(verdict({ status: "expired" })).toEqual({ kind: "notRun", status: "expired", resolution: null });
    expect(verdict({ status: "applied", outcome: { outcome: "configured", configuration: { provider: "anthropic", model: "claude-sonnet", reasoning: "high", mode: "plan" } } })).toEqual({
      kind: "configured",
      label: "Plan · anthropic/claude-sonnet · high",
    });
    expect(verdict({ status: "applied", outcome: { outcome: "cancelled", newly: false } })).toEqual({ kind: "cancelled", newly: false });
  });

  it("never reports a refusal, an unknown or an expiry as success", () => {
    const text = (settlement: Settlement) => receiptView(receiptFromCommand(ledgerEntry("cmd-9", { control: "release" }, settlement)));
    expect(text(refusedBusy("Busy now."))).toMatchObject({ tone: "danger", attention: true });
    expect(text(refusedBusy("Busy now.")).text).toBe("Mimir refused it. Busy now.");
    expect(text(unknownOutcome("Reply lost")).text).toContain("may or may not have taken effect");
    expect(text(unknownOutcome("Reply lost")).tone).toBe("warn");
    expect(text({ status: "expired" }).text).toBe("The engine did not run it. It expired before it ran.");
  });

  it("shows Applied only after the host settles an answer, then lets the fact move on", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, (_control, read) => (read < 3 ? null : APPLIED));
    const s = state({ userRequest: { id: "ur-1", questions: [{ id: "q", prompt: "Name?", allowMultiple: false, options: [{ label: "Ferris", description: "" }] }] } });
    const view = await mount(dock(client, s));
    await click(button(view, /Ferris/));
    await click(button(view, "Send answer"));
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("pending");
    expect(button(view, "Send answer").disabled).toBe(true);
    await advance(2000);
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("applied");
    expect(feedback(view)!.textContent).toBe("Mimir accepted it.");
    expect(client.count("QueueCommand")).toBe(1);
  });

  it("states a host refusal verbatim and lets the user try again explicitly", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => refusedBusy("The TUI owns this conversation."));
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    await advance(1000);
    expect(feedback(view)!.textContent).toBe("Mimir refused it. The TUI owns this conversation.");
    expect(feedback(view)!.getAttribute("role")).toBe("alert");
    expect(view.textContent).not.toMatch(/accepted|Reconnected|Configured/);
    expect(button(view, "Reconnect").disabled).toBe(false);
    expect(client.count("QueueCommand")).toBe(1);
  });

  it("keeps an unknown answer unconfirmed and never queues the command again", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => unknownOutcome("The bridge dropped before replying."));
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    await advance(30_000);
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("unknown");
    expect(feedback(view)!.textContent).toContain("may or may not have taken effect");
    expect(feedback(view)!.textContent).toContain("Nothing was resent.");
    expect(client.count("QueueCommand")).toBe(1);
    expect(client.count("GetCommand")).toBe(1);
  });

  it("keeps the command id and the uncertainty when the receipt cannot be read", async () => {
    vi.useFakeTimers();
    const client = fakeClient({
      GetCommand: () => {
        throw new Error("socket closed");
      },
    });
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    await advance(30_000);
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("unconfirmed");
    expect(feedback(view)!.textContent).toContain("cmd-1");
    expect(feedback(view)!.textContent).toContain("the receipt could not be read: socket closed");
    expect(feedback(view)!.textContent).toContain("Nothing was resent.");
    expect(client.count("QueueCommand")).toBe(1);
    expect(client.count("GetCommand")).toBeLessThanOrEqual(11);
  });

  it("stops watching a command the host never settles and says so", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => null);
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    await advance(60_000);
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("unconfirmed");
    expect(feedback(view)!.textContent).toContain("the host has not settled it yet");
    const reads = client.count("GetCommand");
    await advance(60_000);
    expect(client.count("GetCommand")).toBe(reads);
    expect(client.count("QueueCommand")).toBe(1);
  });

  it("says a command was not queued when the engine refuses the enqueue itself", async () => {
    const client = fakeClient({
      QueueCommand: () => {
        throw new Error("engine offline");
      },
    });
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    expect(feedback(view)!.textContent).toBe("Could not send: engine offline");
    expect(client.count("GetCommand")).toBe(0);
  });

  it("drops a receipt, and its pending read, when the chat changes", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => null);
    const view = await mount(dock(client, interrupted()));
    await click(button(view, "Reconnect"));
    expect(feedback(view)!.getAttribute("data-receipt")).toBe("pending");
    await act(async () => {
      root!.render(createElement(NativeDock, { client, chatId: "chat-other", state: interrupted(), connectivity: "connected" as never, onOpenChild: () => {} }));
    });
    expect(feedback(view)).toBeNull();
    const reads = client.count("GetCommand");
    await advance(30_000);
    expect(client.count("GetCommand")).toBe(reads);
    expect(feedback(view)).toBeNull();
  });

  it("only says Guidance queued after the host accepts it for the exact attempt", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => ({ status: "applied", outcome: { outcome: "child", control: { result: "attemptChanged", attempt: 3 } } }));
    const props = (attempt: number) => ({ client, chatId: CHAT, child: child({ attempt }) });
    const view = await mount(createElement(NativeChildPanel, props(2)));
    const input = view.querySelector("input[aria-label='Guidance for this agent']") as HTMLInputElement;
    await type(input, "focus on login");
    await click(button(view, "Send guidance"));
    await advance(1000);
    const line = view.querySelector("[data-testid=native-child-feedback]")!.textContent!;
    expect(line).toBe("That agent moved on to attempt 3. Nothing was sent to it.");
    expect(view.textContent).not.toMatch(/Guidance queued|Stopping/);
    expect(input.value).toBe("focus on login");
    await act(async () => root!.render(createElement(NativeChildPanel, props(3))));
    await advance(30_000);
    expect(client.controls()).toEqual([{ control: "steerChild", handle: "agent-1", attempt: 2, text: "focus on login" }]);
  });

  it("does not claim Stopping when the child already ended, is finishing, or Mimir refused", async () => {
    vi.useFakeTimers();
    const cases: ReadonlyArray<readonly [Settlement, string]> = [
      [{ status: "applied", outcome: { outcome: "child", control: { result: "terminal" } } }, "That attempt has already ended. Nothing was sent."],
      [{ status: "applied", outcome: { outcome: "child", control: { result: "finalizing" } } }, "That agent is finishing and cannot take this now. Nothing was sent."],
      [refusedBusy("The agent is not reachable."), "Mimir refused it. The agent is not reachable."],
      [unknownOutcome("Reply lost"), "Mimir's answer was lost, so it may or may not have taken effect. Check the conversation state. Nothing was resent. Reply lost"],
    ];
    for (const [settlement, expected] of cases) {
      const client = fakeClient({}, () => settlement);
      const view = await mount(createElement(NativeChildPanel, { client, chatId: CHAT, child: child() }));
      await click(button(view, "Stop"));
      await advance(1000);
      expect(view.querySelector("[data-testid=native-child-feedback]")!.textContent).toBe(expected);
      expect(view.textContent).not.toContain("Stopping");
      expect(client.count("QueueCommand")).toBe(1);
      act(() => root?.unmount());
      view.remove();
      root = null;
      host = null;
    }
  });

  it("reports a Stop the host refused from the root interrupt path", async () => {
    vi.useFakeTimers();
    const client = fakeClient({}, () => refusedBusy("Nothing to cancel."));
    const received: string[] = [];
    const receipt = await (async () => {
      const pending = sendNativeControl(client, CHAT, { control: "cancelRequest", requestId: "req-1" }, { onReceipt: (next) => received.push(next.kind) });
      await vi.advanceTimersByTimeAsync(1000);
      return pending;
    })();
    expect(received).toEqual(["sending", "pending", "settled"]);
    expect(receiptView(receipt)).toMatchObject({ tone: "danger", attention: true, text: "Mimir refused it. Nothing to cancel." });
  });
});
