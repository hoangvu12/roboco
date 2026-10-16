// @vitest-environment jsdom

/**
 * The Limited banner, mounted (orchestration v2, ticket 10): the strip's
 * Limited arm — the window and reset label, the three fixed actions
 * (Resume, Resume at reset + its armed Cancel, the account failover
 * picker) — driven against a recording engine client double. The banner is
 * the web peer of the desktop's status-strip Limited branch (shell.rs), so
 * these mirror its GPUI state tests: the RPC the engine sees is the
 * assertion. The mounted-suite idiom (Base UI runs for real in jsdom, its
 * portals into document.body and all); no JSX (createElement).
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type { AgentAccount, ResumeAccountCandidates, ScheduledResume, SessionLimit } from "@roboco/proto";
import { LimitedBanner } from "../src/components/limited-banner";
import { StatusStrip } from "../src/components/transcript";

const NOW = Date.now();

// ── A recording engine client ──────────────────────────────────────────────

const h = vi.hoisted(() => {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];

  /** The next ListResumeAccountCandidates reply. */
  let candidatesReply: ResumeAccountCandidates | null = null;
  /** The next ResumeOnAnotherAccount reply. */
  let resumeReply: Record<string, unknown> = { type: "switched", accountId: "a2", accountEmail: "bea@example.com" };

  const client = {
    async call<T>(method: string, params: Record<string, unknown>): Promise<T> {
      calls.push({ method, params });
      switch (method) {
        case "ListResumeAccountCandidates":
          return (candidatesReply ?? { chatId: "c", harness: "claude-code", candidates: [], signedOut: [] }) as T;
        case "ResumeOnAnotherAccount":
          return resumeReply as T;
        case "StartAgentLogin":
          return {
            loginId: "login-1",
            url: "https://example.com/auth",
            mode: "browser",
            cliOpensBrowser: true,
            callbackPort: null,
          } as T;
        default:
          return {} as T;
      }
    },
  } as unknown as EngineClient;

  return {
    calls,
    client,
    setCandidates(next: ResumeAccountCandidates | null): void {
      candidatesReply = next;
    },
    setResumeReply(next: Record<string, unknown>): void {
      resumeReply = next;
    },
    reset(): void {
      calls.length = 0;
      candidatesReply = null;
      resumeReply = { type: "switched", accountId: "a2", accountEmail: "bea@example.com" };
    },
  };
});

// ── jsdom gaps the mounted popovers hit (account-usage.test.ts's set) ───────

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
  // The sign-in flow pre-opens a tab inside the click gesture; jsdom's
  // window.open is a no-op stub, so pin it to a quiet null.
  vi.spyOn(window, "open").mockReturnValue(null);
});

afterAll(() => {
  vi.restoreAllMocks();
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── The mounted banner harness ─────────────────────────────────────────────

const mounted: Array<() => void> = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!();
  }
  document.body.replaceChildren();
  h.reset();
});

interface MountedBanner {
  banner(): HTMLDivElement | null;
  /** The failover picker's chip (the trigger), when the list offers it. */
  failoverChip(): HTMLButtonElement | null;
  /** The failover card, wherever it portals (open only). */
  card(): HTMLElement | null;
  pressChip(label: string): void;
  unmount(): void;
}

function mountBanner(
  limit: SessionLimit | null,
  scheduled: ScheduledResume | null = null,
  onResume: () => void = () => {},
): MountedBanner {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const root: Root = createRoot(host);
  act(() => {
    root.render(
      createElement(LimitedBanner, {
        chatId: "chat-1",
        client: h.client,
        limit,
        scheduled,
        now: NOW,
        onResume,
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
  const banner = (): HTMLDivElement | null => document.querySelector<HTMLDivElement>(".limited-banner");
  return {
    banner,
    failoverChip: () =>
      Array.from(document.querySelectorAll<HTMLButtonElement>(".limited-banner-chip")).find(
        (chip) => chip.textContent === "Resume on another account",
      ) ?? null,
    card: () => document.querySelector<HTMLElement>(".failover-card"),
    pressChip(label: string): void {
      const chip = Array.from(document.querySelectorAll<HTMLButtonElement>(".limited-banner-chip")).find(
        (candidate) => candidate.textContent === label,
      );
      if (chip === undefined) {
        throw new Error(`the "${label}" chip did not render`);
      }
      press(chip);
    },
    unmount,
  };
}

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: HTMLElement): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

/** Flush the async loads and replies. */
async function flush(): Promise<void> {
  await act(async () => {});
}

function claudeLimit(fields: Partial<SessionLimit> = {}): SessionLimit {
  return { harness: "claude-code", window: "5-hour", ...fields };
}

function account(id: string, fields: Partial<AgentAccount> = {}): AgentAccount {
  return {
    id,
    harness: "claude-code",
    email: `${id}@example.com`,
    planLabel: null,
    active: false,
    usageWindows: [],
    signInState: "unknown",
    switchable: true,
    ...fields,
  };
}

// ── The label and the fixed actions ────────────────────────────────────────

describe("LimitedBanner — the label", () => {
  it("names the window and its reset", async () => {
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 45 * 60_000 }));
    await flush();
    expect(handle.banner()!.querySelector(".limited-banner-label")!.textContent).toBe(
      "5-hour limit · resets in 45m",
    );
    expect(handle.banner()!.querySelector(".limited-banner-dot")).not.toBeNull();
  });

  it("an armed continuation names when it fires instead (ticket 07)", async () => {
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 3 * 3600_000 }), { fireAt: NOW + 45 * 60_000 });
    await flush();
    expect(handle.banner()!.querySelector(".limited-banner-label")!.textContent).toBe(
      "5-hour limit · resumes in 45m",
    );
  });
});

describe("LimitedBanner — Resume at reset (ticket 07)", () => {
  it("arms the engine's continuation; the armed state offers Cancel", async () => {
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60 * 60_000 }));
    await flush();
    handle.pressChip("Resume at reset");
    expect(h.calls).toEqual([
      { method: "Mutate", params: { op: "scheduleResumeAtReset", chatId: "chat-1" } },
    ]);
    handle.unmount();

    const armed = mountBanner(claudeLimit({ resetsAt: NOW + 3 * 3600_000 }), { fireAt: NOW + 45 * 60_000 });
    await flush();
    // Drop the armed banner's warm-load call — the Cancel press is the
    // assertion, not the candidate list.
    h.reset();
    armed.pressChip("Cancel");
    expect(h.calls).toEqual([
      { method: "Mutate", params: { op: "cancelScheduledResume", chatId: "chat-1" } },
    ]);
  });

  it("a due or unknown reset offers no Resume at reset — plain Resume only", async () => {
    const due = mountBanner(claudeLimit({ resetsAt: NOW - 60_000 }));
    await flush();
    expect(() => due.pressChip("Resume at reset")).toThrow();
    expect(due.banner()!.textContent).toContain("resets now");
  });
});

describe("LimitedBanner — Resume", () => {
  it("fires the composer's fixed-prompt send (the page's resumeRef)", async () => {
    const resumed: string[] = [];
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }), null, () => resumed.push("sent"));
    await flush();
    handle.pressChip("Resume");
    expect(resumed).toEqual(["sent"]);
    // The prompt itself is the composer's send path — no RPC from here.
    expect(h.calls.filter((call) => call.method === "Mutate")).toEqual([]);
  });
});

// ── The failover picker (ticket 09) ────────────────────────────────────────

describe("LimitedBanner — Resume on another account", () => {
  it("keeps the candidate list warm while the chat is Limited (the load)", async () => {
    h.setCandidates({
      chatId: "chat-1",
      harness: "claude-code",
      limitedAccountId: "a1",
      candidates: [account("a2")],
      signedOut: [],
    });
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    expect(h.calls.map((call) => call.method)).toContain("ListResumeAccountCandidates");
    expect(handle.failoverChip()).not.toBeNull();
  });

  it("a non-switchable harness never offers the failover", async () => {
    const handle = mountBanner({ harness: "cursor", window: "usage", resetsAt: null, accountId: null });
    await flush();
    await flush();
    expect(handle.failoverChip()).toBeNull();
    expect(h.calls).toEqual([]);
  });

  it("an empty list hides the chip (only Resume and Resume at reset remain)", async () => {
    h.setCandidates({ chatId: "chat-1", harness: "claude-code", candidates: [], signedOut: [] });
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    expect(handle.failoverChip()).toBeNull();
  });

  it("lists the ranked rows with the recommended mark, and switches on a click", async () => {
    h.setCandidates({
      chatId: "chat-1",
      harness: "claude-code",
      limitedAccountId: "a1",
      candidates: [account("a2", { planLabel: "Pro" }), account("a3")],
      signedOut: [],
    });
    h.setResumeReply({ type: "switched", accountId: "a3", accountEmail: "cass@example.com" });
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    press(handle.failoverChip()!);

    const card = handle.card();
    expect(card).not.toBeNull();
    expect(card!.querySelector(".menu-heading")!.textContent).toBe("Resume on another Claude account");
    const rows = Array.from(card!.querySelectorAll(".menu-row"));
    expect(rows.length).toBe(2);
    // The first candidate is the recommendation; the caption says what
    // switching means (story 53).
    expect(rows[0]!.textContent).toContain("Recommended");
    expect(card!.querySelector(".failover-warning")!.textContent).toBe(
      "Switching applies to every chat on this harness, not just this one.",
    );

    press(rows[1]!);
    await flush();
    expect(h.calls.find((call) => call.method === "ResumeOnAnotherAccount")?.params).toEqual({
      chatId: "chat-1",
      accountId: "a3",
    });
    // The outcome rides the strip's notice line.
    expect(handle.banner()!.querySelector(".limited-banner-notice")!.textContent).toBe(
      "Resumed on cass@example.com — continuing where the chat left off.",
    );
  });

  it("a failed switch surfaces its reason on the notice line", async () => {
    h.setCandidates({
      chatId: "chat-1",
      harness: "claude-code",
      candidates: [account("a2")],
      signedOut: [],
    });
    h.setResumeReply({ type: "failed", reason: "engine offline" });
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    press(handle.failoverChip()!);
    press(Array.from(handle.card()!.querySelectorAll(".menu-row"))[0]!);
    await flush();
    expect(handle.banner()!.querySelector(".limited-banner-notice")!.textContent).toBe("engine offline");
  });

  it("a signed-out row is listed with the mark and a Sign in again action, never a switch", async () => {
    const dead = account("dead", { signInState: "signed-out" });
    h.setCandidates({
      chatId: "chat-1",
      harness: "claude-code",
      candidates: [account("a2")],
      signedOut: [dead],
    });
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    press(handle.failoverChip()!);

    const card = handle.card()!;
    const deadRow = Array.from(card.querySelectorAll<HTMLElement>(".menu-row")).find((row) =>
      row.textContent!.includes("dead@example.com"),
    )!;
    expect(deadRow.querySelector(".failover-row-signed-out")!.textContent).toBe("Signed out");
    const signIn = deadRow.querySelector<HTMLButtonElement>(".failover-row-sign-in")!;
    expect(signIn).not.toBeNull();

    press(signIn);
    await flush();
    // The engine's own login flow (story 47) — never an account switch.
    expect(h.calls.find((call) => call.method === "StartAgentLogin")?.params).toMatchObject({
      harness: "claude-code",
    });
    expect(h.calls.find((call) => call.method === "ResumeOnAnotherAccount")).toBeUndefined();
  });
});

// ── The strip mounts the banner (the chat page's wiring) ───────────────────

describe("StatusStrip — the Limited arm", () => {
  it("renders the banner instead of the error word when the chat reads Limited", async () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const root: Root = createRoot(host);
    act(() => {
      root.render(
        createElement(
          StatusStrip,
          {
            status: "errored",
            sending: false,
            limited: {
              chatId: "chat-1",
              client: h.client,
              limit: claudeLimit({ resetsAt: NOW + 45 * 60_000 }),
              scheduled: null,
              now: NOW,
              onResume: () => {},
            },
          },
        ),
      );
    });
    mounted.push(() => {
      act(() => {
        root.unmount();
      });
      host.remove();
    });
    await flush();
    const strip = document.querySelector<HTMLElement>(".status-strip");
    expect(strip).not.toBeNull();
    expect(strip!.querySelector(".limited-banner")).not.toBeNull();
    expect(strip!.querySelector(".status-strip-error")).toBeNull();
  });

  it("keeps the plain error word when the limit record is absent", () => {
    const host = document.createElement("div");
    document.body.appendChild(host);
    const root: Root = createRoot(host);
    act(() => {
      root.render(
        createElement(StatusStrip, { status: "errored", sending: false, limited: null }),
      );
    });
    mounted.push(() => {
      act(() => {
        root.unmount();
      });
      host.remove();
    });
    const strip = document.querySelector<HTMLElement>(".status-strip");
    expect(strip!.querySelector(".status-strip-error")!.textContent).toBe("Run failed");
    expect(strip!.querySelector(".limited-banner")).toBeNull();
  });
});
