// @vitest-environment jsdom
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { SessionMessageEntry, TodoItem, TranscriptUpdate } from "@roboco/proto";
import { TodoPanel } from "../src/components/todo-panel";
import { TranscriptStore } from "../src/state/transcript-store";

/**
 * The React-binding half of the todo panel (the pure logic lives in
 * todo-panel.test.ts). Mounts the real `TranscriptStore` — whose
 * `subscribe`/`getSnapshot` are class methods over private fields — because
 * React calls both as DETACHED function references: an unbound
 * `store.getSnapshot` throws `Cannot read properties of undefined (reading
 * '#snapshot')` on every chat open (the '#m' of the minified bundle). This
 * test exists so that can never come back.
 */

let root: Root;
let container: HTMLDivElement;
const stores: TranscriptStore[] = [];

interface Handlers {
  onItem: (frame: TranscriptUpdate, context: { generation: number }) => void;
  onEnd: (error: { message: string }) => void;
}

function makeStore(id: string): { store: TranscriptStore; reset: (entries: SessionMessageEntry[]) => void } {
  let handlers!: Handlers;
  const client = {
    watch: (_method: string, _params: unknown, callbacks: Handlers) => {
      handlers = callbacks;
      return { cancel() {} };
    },
  } as unknown as EngineClient;
  const store = new TranscriptStore(client, id);
  stores.push(store);
  return {
    store,
    reset: (entries: SessionMessageEntry[]) =>
      act(() => handlers.onItem({ reset: entries, contextUsage: null }, { generation: 1 })),
  };
}

const todoPart = (items: readonly TodoItem[]): SessionMessageEntry["parts"][number] => ({
  kind: "tool",
  id: "t",
  call: { kind: "todo", items: [...items] },
  isError: false,
  resolved: true,
}) as SessionMessageEntry["parts"][number];

const todoEntry = (items: readonly TodoItem[]): SessionMessageEntry =>
  ({
    id: "m",
    role: "assistant",
    parts: [todoPart(items)],
    createdAt: 0,
    deviceId: "d",
  }) as SessionMessageEntry;

function render(store: TranscriptStore, chatId: string, live = false): void {
  act(() => root.render(createElement(TodoPanel, { store, chatId, live })));
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
  for (const store of stores.splice(0)) store.dispose();
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

describe("todo panel (React binding)", () => {
  it("mounts against a real TranscriptStore without a detached-this crash", () => {
    const { store } = makeStore("todo-bind");
    // An empty transcript still runs the useSyncExternalStore calls — the
    // crash happened here, before any todo content existed.
    render(store, "todo-bind");
    expect(container.textContent).toBe("");
  });

  it("renders the checklist from a live reset frame", () => {
    const { store, reset } = makeStore("todo-rows");
    reset([
      todoEntry([
        { text: "first", done: true },
        { text: "second", done: false },
      ]),
    ]);
    render(store, "todo-rows");
    const panel = container.querySelector(".todo-panel");
    expect(panel).not.toBeNull();
    expect(panel!.getAttribute("data-open")).toBe("true");
    expect(panel!.querySelector(".todo-panel-count")!.textContent).toBe("1/2");
    expect(Array.from(panel!.querySelectorAll(".todo-item-text")).map((el) => el.textContent)).toEqual([
      "first",
      "second",
    ]);
  });
});
