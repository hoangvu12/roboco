// @vitest-environment jsdom

/**
 * The fork dialog, mounted (t3-orchestration-v2 tickets 21+22): the layer
 * the app shell owns, driven against a recording engine client double —
 * the mounted-suite idiom (Base UI runs for real in jsdom, its portals
 * into document.body and all; the limited-banner suite's set). The
 * assertions are the click→wire chain: the modal opens with the source's
 * defaults, the boundary flips the title, picking rides the store, and
 * the Fork button fires FORK_SIDE_CHAT. No JSX (createElement).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { encodeScopedId, type EngineClient } from "@roboco/engine-client";
import type { Chat, HarnessDescriptor } from "@roboco/proto";
import { ForkDialogLayer } from "../src/components/fork-dialog";
import { forkDialogStore } from "../src/state/fork-dialog";
import { clearPendingSideChat, pendingSideChat } from "../src/state/side-chats";
import { rightPaneStore } from "../src/state/right-pane";
import { resetSideChatCreate } from "../src/lib/side-chat-actions";

const ENGINE = "ws://engine.local";
const OWN_DEVICE = encodeScopedId(ENGINE, "dev-1");

function descriptor(id: HarnessDescriptor["id"]): HarnessDescriptor {
  return {
    id,
    name: id === "codex" ? "Codex" : id === "pi" ? "Pi" : id,
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed: true,
    canInstall: false,
    enabled: true,
  };
}

const CATALOG = [descriptor("codex"), descriptor("pi")];

function source(fields: Partial<Chat> = {}): Chat {
  return {
    id: "source-1",
    deviceId: OWN_DEVICE,
    title: "The source chat",
    archived: false,
    cwd: "/repo",
    branch: "main",
    checkoutId: null,
    config: { harness: "codex", model: "gpt-6-codex", reasoning: null, sandbox: "workspace-write", modelOptions: {} },
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-10-16T10:00:00Z",
    spaceId: null,
    parentChatId: null,
    ...fields,
  };
}

const h = vi.hoisted(() => {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  const client = {
    engineInfo: { deviceId: "dev-1" },
    async call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
      calls.push({ method, params });
      switch (method) {
        case "ListHarnesses":
          return CATALOG as T;
        case "ListModels":
          return [{ id: "glm-5.3", label: "GLM 5.3", reasoningLevels: [], options: [] }] as T;
        case "ForkSideChat":
          return {
            id: "fork-9",
            parentChatId: "source-1",
            title: "The forked chat",
          } as T;
        default:
          return {} as T;
      }
    },
  } as unknown as EngineClient;
  return { calls, client };
});

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
  vi.restoreAllMocks();
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  forkDialogStore.close();
  resetSideChatCreate();
  h.calls.length = 0;
  clearPendingSideChat(encodeScopedId(ENGINE, "fork-9"));
});

function mountLayer(): void {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const root: Root = createRoot(host);
  act(() => {
    root.render(createElement(ForkDialogLayer));
  });
  mounted.push(() => {
    act(() => {
      root.unmount();
    });
    host.remove();
  });
}

async function flush(): Promise<void> {
  await act(async () => {});
}

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: Element): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

function textOf(selector: string): string | null {
  return document.querySelector<HTMLElement>(selector)?.textContent ?? null;
}

function openDialog(boundary: string | null): void {
  act(() => {
    forkDialogStore.open({
      source: source(),
      parentId: "source-1",
      boundary,
      paneChatId: "pane-1",
      client: h.client,
      engineBaseUrl: ENGINE,
    });
  });
}

describe("ForkDialogLayer — mounted", () => {
  it("renders nothing while the store is closed", async () => {
    mountLayer();
    await flush();
    expect(document.querySelector(".fork-dialog-card")).toBeNull();
  });

  it("opens on the classic entry: the latest-reply title, the source's harness chip selected", async () => {
    mountLayer();
    openDialog(null);
    await flush();
    expect(textOf(".fork-dialog-title")).toBe("Fork this chat");
    expect(textOf(".fork-dialog-body-text")).toBe(
      "A new side chat continues from the latest reply. Files are not rewound.",
    );
    // The catalog landed: both offered chips render, the source's selected.
    const chips = Array.from(document.querySelectorAll<HTMLButtonElement>(".harness-choice-chip"));
    expect(chips.map((chip) => chip.textContent)).toEqual(["Codex", "Pi"]);
    expect(chips[0]!.getAttribute("data-selected")).toBe("1");
    expect(chips[1]!.getAttribute("data-selected")).toBeNull();
    // The model list for the default pick.
    expect(Array.from(document.querySelectorAll(".harness-choice-model-row"))).toHaveLength(1);
  });

  it("the boundary flips the title and body (ticket 21's entry point)", async () => {
    mountLayer();
    openDialog("a1");
    await flush();
    expect(textOf(".fork-dialog-title")).toBe("Fork from here");
    expect(textOf(".fork-dialog-body-text")).toBe(
      "A new side chat continues from this reply. Files are not rewound.",
    );
  });

  it("picking another harness resets the model and shows the cross-harness note; the Fork button forks", async () => {
    mountLayer();
    openDialog("a1");
    await flush();
    // Pick Pi: the model resets, the cross-harness note appears.
    press(document.querySelectorAll<HTMLButtonElement>(".harness-choice-chip")[1]!);
    await flush();
    expect(forkDialogStore.getSnapshot().harness).toBe("pi");
    expect(forkDialogStore.getSnapshot().model).toBeNull();
    expect(textOf(".harness-choice-note")).toContain("size-limited transcript");
    // Confirm: the RPC goes out with the boundary and the cross-harness
    // override, the seed lands, and the dialog closes.
    const fork = Array.from(document.querySelectorAll<HTMLButtonElement>("button")).find(
      (button) => button.textContent === "Fork",
    )!;
    press(fork);
    await flush();
    const call = h.calls.find((entry) => entry.method === "ForkSideChat");
    expect(call).toBeDefined();
    expect(call!.params).toMatchObject({
      sourceChatId: "source-1",
      parentChatId: "source-1",
      boundary: "a1",
      harness: "pi",
      targetDeviceId: OWN_DEVICE,
    });
    expect(pendingSideChat(encodeScopedId(ENGINE, "fork-9"))).not.toBeNull();
    expect(document.querySelector(".fork-dialog-card")).toBeNull();
  });

  it("Cancel closes without forking", async () => {
    mountLayer();
    openDialog(null);
    await flush();
    const cancel = Array.from(document.querySelectorAll<HTMLButtonElement>("button")).find(
      (button) => button.textContent === "Cancel",
    )!;
    press(cancel);
    await flush();
    expect(forkDialogStore.getSnapshot().open).toBe(false);
    expect(h.calls.filter((entry) => entry.method === "ForkSideChat")).toEqual([]);
  });
});
