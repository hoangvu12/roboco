// @vitest-environment jsdom

/**
 * Ticket 06 — queue edit-lease failures land in the composer's red failure
 * notice, not a sidebar toast. The desktop routes every edit-lease failure
 * into `Composer::failure` (queue.rs:1458-1685, rendered as the
 * `composer-failure` notice chip); the web's port is the `editFailureRef`
 * handle — the `editCommitRef` pattern: the host hands the Composer a
 * mutable ref, the Composer assigns a raiser that lands the message in the
 * chat-scoped failure notice. The mounted contracts here pin:
 *
 * - The ref is assigned while mounted and stands down on unmount.
 * - A raised lease failure paints the red notice chip (`#composer-failure`)
 *   with the message — click-dismissable, like every composer failure.
 * - The notice is chat-scoped: it stands down on the route flip (another
 *   chat's composer shows nothing) and a failure raised under the new chat
 *   keys the new chat.
 *
 * The mounted idiom follows section-menu.test.ts: no JSX, per-file jsdom
 * pragma, the matchMedia / ResizeObserver / scrollIntoView / rAF stubs.
 */

import { act, createElement, useState } from "react";
import { createRoot } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { Chat } from "@roboco/proto";
import { Composer } from "../src/components/composer";
import { PickerCatalog } from "../src/state/picker-catalog";
import type { EngineSession } from "../src/state/engine-session";
import type { EngineClient } from "@roboco/engine-client";

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

// ── The session double — every RPC stays pending (loads never land) ───────

const fakeClient = {
  engineInfo: null,
  state: "connected",
  status: { state: "connected", info: null },
  onStatus(): () => void {
    return () => {};
  },
  call(): Promise<never> {
    return new Promise(() => {});
  },
} as unknown as EngineClient;

const CATALOG = new PickerCatalog(fakeClient);

const SESSION = {
  engine: { baseUrl: "http://engine.test", credential: "cred", label: "test" },
  client: fakeClient,
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

function chat(id: string): Chat {
  return {
    id,
    deviceId: "device-1",
    title: null,
    archived: false,
    cwd: "/proj",
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: new Date(0).toISOString(),
    spaceId: null,
  };
}

// ── The mounted composer harness ──────────────────────────────────────────

interface MountedComposer {
  /** The mount container (the page's composer slot stand-in). */
  readonly container: HTMLDivElement;
  /** The red failure notice chip (`#composer-failure`), or null. */
  failureChip(): HTMLElement | null;
  /** Raise a lease failure through the host-held ref (the page's arm). */
  raiseFailure(message: string): void;
  /** Flip the route: the composer re-targets at a different chat. */
  setChat(id: string): void;
  unmount(): void;
}

/** The ref the HOST holds — the chat page's `editFailureRef`. */
const hostRef: { current: ((message: string) => void) | null } = { current: null };

const mounted: MountedComposer[] = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  hostRef.current = null;
  document.body.replaceChildren();
});

function mountComposer(initialChatId: string): MountedComposer {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  const chats = new Map<string, Chat>([
    [initialChatId, chat(initialChatId)],
    ["chat-other", chat("chat-other")],
  ]);
  let setChatState: ((id: string) => void) | null = null;
  function Host() {
    const [chatId, setChat] = useState(initialChatId);
    setChatState = setChat;
    return createElement(Composer, {
      session: SESSION,
      chat: chats.get(chatId)!,
      catalog: CATALOG,
      transcript: null,
      availableWidth: 700,
      editFailureRef: hostRef,
    });
  }
  act(() => {
    root.render(createElement(Host));
  });
  const handle: MountedComposer = {
    container,
    failureChip: () => document.querySelector<HTMLElement>("#composer-failure"),
    raiseFailure(message: string) {
      const raise = hostRef.current;
      if (raise === null) {
        throw new Error("editFailureRef was not assigned by the Composer");
      }
      act(() => {
        raise(message);
      });
    },
    setChat(id: string) {
      if (!chats.has(id)) {
        chats.set(id, chat(id));
      }
      act(() => {
        setChatState!(id);
      });
    },
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
  mounted.push(handle);
  return handle;
}

// ── The contracts ─────────────────────────────────────────────────────────

describe("the composer's edit-lease failure notice (ticket 06)", () => {
  it("a raised lease failure paints the red notice chip with the message", () => {
    const handle = mountComposer("chat-row");
    expect(handle.failureChip()).toBeNull();
    handle.raiseFailure("The edit lease was lost; your text is still in the editor");
    const chip = handle.failureChip();
    expect(chip).not.toBeNull();
    expect(chip!.textContent).toContain("The edit lease was lost; your text is still in the editor");
    // Click-dismissable, like every composer failure (composer.rs:7400-7406).
    act(() => {
      chip!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
    });
    expect(handle.failureChip()).toBeNull();
  });

  it("the notice is chat-scoped: it stands down on the route flip and re-keys under the new chat", () => {
    const handle = mountComposer("chat-row");
    handle.raiseFailure("That queued message is being edited on another device");
    expect(handle.failureChip()).not.toBeNull();
    // The route flips to another chat: the row's chat notice does not follow.
    handle.setChat("chat-other");
    expect(handle.failureChip()).toBeNull();
    // A failure raised under the new chat keys the new chat and shows there.
    handle.raiseFailure("Edit protection expired; review this message before sending");
    const chip = handle.failureChip();
    expect(chip).not.toBeNull();
    expect(chip!.textContent).toContain("Edit protection expired; review this message before sending");
  });

  it("the ref stands down on unmount (the host's raiser goes null)", () => {
    const handle = mountComposer("chat-row");
    expect(hostRef.current).not.toBeNull();
    handle.unmount();
    expect(hostRef.current).toBeNull();
  });
});
