// @vitest-environment jsdom
import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { TranscriptUpdate } from "@roboco/proto";
import { ChatTranscriptOutlet } from "../src/components/chat-transcript-outlet";
import { TranscriptStore } from "../src/state/transcript-store";

let root: Root;
let container: HTMLDivElement;
const stores: TranscriptStore[] = [];

function destination(id: string) {
  let handlers!: { onItem: (frame: TranscriptUpdate, context: { generation: number }) => void;
    onEnd: (error: { message: string }) => void };
  const client = { watch: (_method: string, _params: unknown, callbacks: typeof handlers) => {
    handlers = callbacks;
    return { cancel() {} };
  } } as unknown as EngineClient;
  const store = new TranscriptStore(client, id);
  stores.push(store);
  return { store,
    reset: () => act(() => handlers.onItem({ reset: [], contextUsage: null }, { generation: 1 })),
    seed: () => act(() => store.seedEntries([])),
    error: () => act(() => handlers.onEnd({ message: "Offline" })),
  };
}

function render(store: TranscriptStore, departing = false) {
  act(() => root.render(createElement(ChatTranscriptOutlet, {
    store, departing,
    children: active => createElement("div", { "data-doc": active.docId }, active.docId),
  })));
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

describe("chat transcript outlet", () => {
  it("shows an already-live destination immediately with no loading state or hidden commit", () => {
    const a = destination("A");
    const b = destination("B");
    a.reset();
    b.reset();
    render(a.store);
    render(b.store);
    render(a.store);
    expect(container.querySelector('[data-doc="A"]')).not.toBeNull();
    expect(container.querySelector('[data-doc="B"]')).toBeNull();
    expect(container.querySelector('[role="status"]')).toBeNull();
    expect(container.querySelector<HTMLElement>(".chat-arrival-gate")!.style.opacity).toBe("1");
  });

  it("replaces the previous chat immediately while a delayed destination reset is pending", () => {
    const a = destination("A");
    a.reset();
    render(a.store);
    const oldGate = container.querySelector(".chat-arrival-gate");
    const b = destination("B");
    render(b.store);
    expect(container.querySelector('[data-doc="A"]')).toBeNull();
    expect(container.querySelector('[data-doc="B"]')).not.toBeNull();
    expect(container.querySelector('[role="status"]')?.textContent).toBe("Loading chat…");
    expect(container.querySelector(".chat-arrival-gate")).not.toBe(oldGate);
    b.seed();
    const gate = container.querySelector<HTMLElement>(".chat-arrival-gate")!;
    expect(gate.style.opacity).toBe("0");
    expect(gate.getAttribute("aria-hidden")).toBe("true");
    expect(gate.hasAttribute("inert")).toBe(true);
    b.reset();
    expect(container.querySelector(".chat-arrival-gate")).toBe(gate);
    expect(gate.style.opacity).toBe("1");
    expect(container.querySelector('[role="status"]')).toBeNull();
  });

  it("a late reset for an abandoned destination cannot replace the current chat", () => {
    const b = destination("B");
    render(b.store);
    const c = destination("C");
    render(c.store);
    b.reset();
    expect(container.querySelector('[data-doc="B"]')).toBeNull();
    expect(container.querySelector('[data-doc="C"]')).not.toBeNull();
    expect(container.querySelector('[role="status"]')).not.toBeNull();
    c.reset();
    expect(container.querySelector('[role="status"]')).toBeNull();
  });

  it("reveals the offline error endpoint and preserves the canvas departure", () => {
    const a = destination("A");
    a.seed();
    render(a.store);
    a.error();
    expect(container.querySelector<HTMLElement>(".chat-arrival-gate")!.style.opacity).toBe("1");
    const b = destination("B");
    render(b.store, true);
    expect(container.querySelector('[role="status"]')).toBeNull();
    expect(container.querySelector<HTMLElement>(".chat-arrival-gate")!.style.opacity).toBe("1");
  });
});
