// @vitest-environment jsdom

/**
 * The 79b8d84c web parity: the footer's plan-usage ring
 * (`AccountUsageIndicator`, the web peer of `account_usage.rs`). The ring
 * reads the live account's most-used window, degrades quietly when usage
 * is unavailable (no chip at all), and opening it lists the harness's
 * accounts — clicking one switches via `ActivateAgentAccount`, flipping
 * the rows optimistically before the engine's reply lands.
 *
 * The indicator mounts for real (the mounted-suite idiom — Base UI runs
 * for real in jsdom, its portals into document.body and all) against a
 * recording client double: exactly the `call` surface lib/accounts'
 * wrappers read. No JSX (createElement), per-file jsdom pragma only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { AgentAccount, AgentAccountsSnapshot, HarnessId } from "@roboco/proto";
import { AccountUsageIndicator } from "../src/components/account-usage";
import { PHONE_QUERY } from "../src/state/media";

// ── A recording engine client ──────────────────────────────────────────────

const h = vi.hoisted(() => {
  /** Every `call` the client saw — method, params, and the canned reply. */
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];

  /** The snapshot the next ListAgentAccounts serves; mutated per scenario. */
  let snapshot: AgentAccountsSnapshot = { accounts: [], warnings: [] };

  /** The reply the next ActivateAgentAccount serves (defaults to the snapshot). */
  let activateReply: AgentAccountsSnapshot | null = null;

  const client = {
    async call<T>(method: string, params: Record<string, unknown>): Promise<T> {
      calls.push({ method, params });
      if (method === "ActivateAgentAccount" && activateReply !== null) {
        return activateReply as T;
      }
      return snapshot as T;
    },
  } as unknown as EngineClient;

  return { calls, client, setSnapshot(next: AgentAccountsSnapshot): void { snapshot = next; }, setActivateReply(next: AgentAccountsSnapshot | null): void { activateReply = next; }, reset(): void { calls.length = 0; activateReply = null; } };
});

// ── jsdom gaps the mounted popovers hit (account-row.test.ts's set) ─────────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query === PHONE_QUERY,
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

// ── The mounted indicator harness ─────────────────────────────────────────

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  h.reset();
});

interface MountedIndicator {
  /** The ring chip, when usage gave it a reading. */
  ring(): HTMLDivElement | null;
  /** The accounts card, wherever it portals (open only, not exiting). */
  card(): HTMLElement | null;
  unmount(): void;
}

function mountIndicator(harness: HarnessId | null, targetDeviceId: string | null = null): MountedIndicator {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const root: Root = createRoot(host);
  act(() => {
    root.render(
      createElement(AccountUsageIndicator, {
        client: h.client,
        harness,
        targetDeviceId,
      }),
    );
  });
  const unmount = (): void => {
    act(() => {
      root.unmount();
    });
    host.remove();
  };
  mounted.push(unmount);
  return {
    ring: () => document.querySelector<HTMLDivElement>(".account-usage"),
    card: () => document.querySelector<HTMLElement>(".account-usage-card:not([data-closed])"),
    unmount,
  };
}

/** Flush the async load effects (the plain-then-forced list pair). */
async function flushLoads(): Promise<void> {
  await act(async () => {});
}

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: HTMLElement): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

function account(
  fields: Partial<AgentAccount> & Pick<AgentAccount, "id" | "harness" | "active" | "switchable">,
): AgentAccount {
  return {
    email: null,
    planLabel: null,
    usageWindows: [],
    ...fields,
  };
}

// ── The ring's reading ─────────────────────────────────────────────────────

describe("AccountUsageIndicator — the ring beside the context indicator", () => {
  it("reads the live account's most-used window and paints the percent", async () => {
    h.setSnapshot({
      accounts: [
        account({
          id: "codex-active",
          harness: "codex",
          active: true,
          switchable: true,
          email: "ada@example.com",
          usageWindows: [
            { label: "5h", usedFraction: 0.12, resetsAt: null },
            { label: "weekly", usedFraction: 0.64, resetsAt: null },
          ],
        }),
      ],
      warnings: [],
    });
    const handle = mountIndicator("codex");
    await flushLoads();

    const ring = handle.ring();
    expect(ring).not.toBeNull();
    expect(ring!.querySelector("span")!.textContent).toBe("64%");
    expect(ring!.dataset.tone).toBe("normal");
    // The first sight of the device lists plain, then the forced probe.
    const lists = h.calls.filter((call) => call.method === "ListAgentAccounts");
    expect(lists.map((call) => call.params.forceUsage)).toEqual([false, true]);
  });

  it("escalates the tone at the usage thresholds", async () => {
    h.setSnapshot({
      accounts: [
        account({
          id: "codex-active",
          harness: "codex",
          active: true,
          switchable: true,
          usageWindows: [{ label: "5h", usedFraction: 0.81, resetsAt: null }],
        }),
      ],
      warnings: [],
    });
    const handle = mountIndicator("codex");
    await flushLoads();
    expect(handle.ring()!.dataset.tone).toBe("warning");
  });

  it("degrades quietly when usage is unavailable", async () => {
    h.setSnapshot({
      accounts: [
        account({
          id: "codex-active",
          harness: "codex",
          active: true,
          switchable: true,
          // A live account with no usage windows: nothing to read.
          usageWindows: [],
        }),
      ],
      warnings: [],
    });
    const handle = mountIndicator("codex");
    await flushLoads();
    expect(handle.ring()).toBeNull();

    // A harness without an accounts surface (antigravity) never asks at all.
    h.reset();
    const quiet = mountIndicator("antigravity");
    await flushLoads();
    expect(quiet.ring()).toBeNull();
    expect(h.calls).toEqual([]);
  });
});

// ── The accounts card and the switch ──────────────────────────────────────

describe("AccountUsageIndicator — the accounts card", () => {
  it("lists the harness's accounts and switches optimistically on a click", async () => {
    const ada = account({
      id: "codex-ada",
      harness: "codex",
      active: true,
      switchable: true,
      email: "ada@example.com",
      planLabel: "Pro",
      usageWindows: [{ label: "5h", usedFraction: 0.4, resetsAt: null }],
    });
    const bea = account({
      id: "codex-bea",
      harness: "codex",
      active: false,
      switchable: true,
      email: "bea@example.com",
    });
    h.setSnapshot({ accounts: [ada, bea], warnings: [] });
    // The engine's reply after the switch: bea is the live login now, with
    // its own usage windows served alongside.
    h.setActivateReply({
      accounts: [
        { ...ada, active: false },
        { ...bea, active: true, usageWindows: [{ label: "5h", usedFraction: 0.2, resetsAt: null }] },
      ],
      warnings: [],
    });

    const handle = mountIndicator("codex");
    await flushLoads();
    press(handle.ring()!);

    const card = handle.card();
    expect(card).not.toBeNull();
    expect(card!.querySelector(".menu-heading")!.textContent).toBe("Codex accounts");
    const rows = Array.from(card!.querySelectorAll(".menu-row"));
    expect(rows.some((row) => row.textContent!.includes("ada@example.com"))).toBe(true);
    expect(rows.some((row) => row.textContent!.includes("bea@example.com"))).toBe(true);
    expect(card!.querySelector(".account-usage-row-in-use")!.textContent).toBe("In use");

    // Clicking the spare (switchable, not active) activates it.
    const beaRow = rows.find(
      (row): row is HTMLElement => row.textContent!.includes("bea@example.com"),
    )!;
    press(beaRow);
    await flushLoads();

    const activate = h.calls.find((call) => call.method === "ActivateAgentAccount");
    expect(activate?.params).toEqual({ id: "codex-bea", accountId: "codex-bea", harness: "codex" });
    // The engine's reply replaced the list: bea is in use now.
    const refreshed = handle.card();
    expect(refreshed).not.toBeNull();
    expect(refreshed!.textContent).toContain("bea@example.com");
    expect(Array.from(refreshed!.querySelectorAll(".account-usage-row-in-use")).length).toBe(1);
  });
});
