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
  /** The next SwitchHarness reply's behavior (tickets 24/25). */
  let switchFails = false;

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
        case "ListHarnesses":
          return [
            {
              id: "claude-code",
              name: "Claude Code",
              supportsSteering: true,
              steeringMode: "step-boundary",
              reasoningLevels: [],
              installed: true,
              canInstall: false,
              enabled: true,
            },
            {
              id: "codex",
              name: "Codex",
              supportsSteering: true,
              steeringMode: "step-boundary",
              reasoningLevels: [],
              installed: true,
              canInstall: false,
              enabled: true,
            },
          ] as T;
        case "ListModels":
          return [
            { id: "gpt-6-codex", label: "GPT-6 Codex", description: "the coder", reasoningLevels: [], options: [] },
            { id: "gpt-6-mini", label: "GPT-6 Mini", reasoningLevels: [], options: [] },
          ] as T;
        case "SwitchHarness":
          if (switchFails) {
            throw new Error("The chat is working; switch harnesses between turns");
          }
          return {} as T;
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
    setSwitchFails(next: boolean): void {
      switchFails = next;
    },
    reset(): void {
      calls.length = 0;
      candidatesReply = null;
      resumeReply = { type: "switched", accountId: "a2", accountEmail: "bea@example.com" };
      switchFails = false;
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
  /** The "Continue on another harness" chip (tickets 25/26). */
  continueChip(): HTMLButtonElement | null;
  /** The failover card, wherever it portals (open only). */
  card(): HTMLElement | null;
  /** The continue picker's card, wherever it portals (open only). */
  continueCard(): HTMLElement | null;
  pressChip(label: string): void;
  unmount(): void;
}

function mountBanner(
  limit: SessionLimit | null,
  scheduled: ScheduledResume | null = null,
  onResume: () => void = () => {},
  fields: { currentHarness?: "claude-code" | "codex" | null; chatId?: string } = {},
): MountedBanner {
  const host = document.createElement("div");
  document.body.appendChild(host);
  const root: Root = createRoot(host);
  const chatId = fields.chatId ?? "chat-1";
  act(() => {
    root.render(
      createElement(LimitedBanner, {
        chatId,
        client: h.client,
        limit,
        scheduled,
        now: NOW,
        onResume,
        currentHarness: fields.currentHarness ?? "claude-code",
        targetDeviceId: null,
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
  const banner = (): HTMLDivElement | null =>
    document.querySelector<HTMLDivElement>(".limited-banner");
  return {
    banner,
    failoverChip: () =>
      Array.from(document.querySelectorAll<HTMLButtonElement>(".limited-banner-chip")).find(
        (chip) => chip.textContent === "Resume on another account",
      ) ?? null,
    continueChip: () =>
      Array.from(document.querySelectorAll<HTMLButtonElement>(".limited-banner-chip")).find(
        (chip) => chip.textContent === "Continue on another harness",
      ) ?? null,
    card: () => document.querySelector<HTMLElement>(".failover-card"),
    continueCard: () => document.querySelector<HTMLElement>(".continue-card"),
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
    const rows = Array.from(card!.querySelectorAll<HTMLElement>(".menu-row"));
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
    press(Array.from(handle.card()!.querySelectorAll<HTMLElement>(".menu-row"))[0]!);
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

// ── "Continue on another harness" (tickets 25/26) ─────────────────────

describe("LimitedBanner — Continue on another harness", () => {
  it("offers the chip on every Limited chat, whatever the harness (story 55's escape hatch)", async () => {
    const handle = mountBanner({ harness: "cursor", window: "usage", resetsAt: null, accountId: null });
    await flush();
    expect(handle.continueChip()).not.toBeNull();
    expect(handle.continueChip()!.title).toBe(
      "Switch this chat to another harness and continue there",
    );
  });

  it("the card offers the enabled harnesses minus the current one, defaulting to the first", async () => {
    const handle = mountBanner(claudeLimit({ resetsAt: NOW + 60_000 }));
    await flush();
    press(handle.continueChip()!);
    await flush();
    const card = handle.continueCard();
    expect(card).not.toBeNull();
    expect(card!.querySelector(".menu-heading")!.textContent).toBe("Continue on another harness");
    // Claude Code is the current harness: only Codex is offered, and it is
    // the resolved default (the first offered once the catalog lands).
    const chips = Array.from(card!.querySelectorAll<HTMLButtonElement>(".harness-choice-chip"));
    expect(chips.map((chip) => chip.textContent)).toEqual(["Codex"]);
    expect(chips[0]!.getAttribute("data-selected")).toBe("1");
    // The model list for the default pick, with the Default tag on the
    // first row (a null model means the default at run time).
    const rows = Array.from(card!.querySelectorAll(".harness-choice-model-row"));
    expect(rows).toHaveLength(2);
    expect(rows[0]!.textContent).toContain("Default");
  });

  it("confirming switches the harness first, then hands the composer the switched picks for the continuation", async () => {
    const resumed: unknown[][] = [];
    const handle = mountBanner(
      claudeLimit({ resetsAt: NOW + 60_000 }),
      null,
      (...args: unknown[]) => resumed.push(args),
    );
    await flush();
    press(handle.continueChip()!);
    await flush();
    // Pick a model so the switch's config write carries it.
    press(Array.from(handle.continueCard()!.querySelectorAll<HTMLButtonElement>(".harness-choice-model-row"))[1]!);
    await flush();
    const confirm = Array.from(handle.continueCard()!.querySelectorAll<HTMLButtonElement>("button")).find(
      (button) => button.textContent === "Continue",
    )!;
    press(confirm);
    await flush();
    expect(h.calls.find((call) => call.method === "SwitchHarness")?.params).toEqual({
      chatId: "chat-1",
      harness: "codex",
      model: "gpt-6-mini",
    });
    // The continuation went out AFTER the switch landed — through the
    // composer's send path carrying the SWITCHED picks (the desktop's row
    // stamp: the chats-watch frame can trail the switch's reply), so no
    // other RPC fires from the banner.
    expect(resumed).toEqual([
      [{ harness: "codex", model: "gpt-6-mini", reasoning: null, modelOptions: {} }],
    ]);
    expect(h.calls.filter((call) => call.method === "Mutate")).toEqual([]);
    // The card closed (the switch's seam + the run are the feedback).
    expect(handle.continueCard()).toBeNull();
  });

  it("a refused switch never sends the continuation — the reason rides the notice line", async () => {
    h.setSwitchFails(true);
    const resumed: unknown[][] = [];
    const handle = mountBanner(
      claudeLimit({ resetsAt: NOW + 60_000 }),
      null,
      (...args: unknown[]) => resumed.push(args),
    );
    await flush();
    press(handle.continueChip()!);
    await flush();
    press(
      Array.from(handle.continueCard()!.querySelectorAll<HTMLButtonElement>("button")).find(
        (button) => button.textContent === "Continue",
      )!,
    );
    await flush();
    expect(h.calls.find((call) => call.method === "SwitchHarness")).toBeDefined();
    expect(resumed).toEqual([]);
    expect(handle.banner()!.querySelector(".limited-banner-notice")!.textContent).toBe(
      "Could not switch the harness: The chat is working; switch harnesses between turns",
    );
    // The banner stays up for another try — still Limited, still on its
    // old harness.
    expect(handle.continueChip()).not.toBeNull();
  });

  it("a selection that moved while the switch was in flight never fires the continuation", async () => {
    // The banner re-keys through props (no remount): the guard compares
    // the live chat id against the one the switch started for — the
    // switched chat keeps its banner and Resume, never sending another
    // chat's continuation into it (the desktop's still_selected rule).
    let release: (() => void) | null = null;
    const slow = {
      async call<T>(method: string, params: Record<string, unknown>): Promise<T> {
        h.calls.push({ method, params });
        if (method === "SwitchHarness") {
          await new Promise<void>((resolve) => {
            release = resolve;
          });
          return {} as T;
        }
        if (method === "ListHarnesses") {
          return [
            {
              id: "claude-code",
              name: "Claude Code",
              supportsSteering: true,
              steeringMode: "step-boundary",
              reasoningLevels: [],
              installed: true,
              canInstall: false,
              enabled: true,
            },
            {
              id: "codex",
              name: "Codex",
              supportsSteering: true,
              steeringMode: "step-boundary",
              reasoningLevels: [],
              installed: true,
              canInstall: false,
              enabled: true,
            },
          ] as T;
        }
        return {} as T;
      },
    } as unknown as EngineClient;

    const host = document.createElement("div");
    document.body.appendChild(host);
    const root: Root = createRoot(host);
    const resumed: string[] = [];
    const render = (chatId: string): void => {
      act(() => {
        root.render(
          createElement(LimitedBanner, {
            chatId,
            client: slow,
            limit: claudeLimit({ resetsAt: NOW + 60_000 }),
            scheduled: null,
            now: NOW,
            onResume: (switched?: { readonly harness?: string }) => {
              // The override never fires into the moved selection either.
              resumed.push(switched?.harness ?? chatId);
            },
            currentHarness: "claude-code",
            targetDeviceId: null,
          }),
        );
      });
    };
    render("chat-1");
    mounted.push(() => {
      act(() => {
        root.unmount();
      });
      host.remove();
    });
    await flush();
    const chip = Array.from(document.querySelectorAll<HTMLButtonElement>(".limited-banner-chip")).find(
      (button) => button.textContent === "Continue on another harness",
    )!;
    press(chip);
    await flush();
    press(
      Array.from(document.querySelectorAll<HTMLButtonElement>("button")).find(
        (button) => button.textContent === "Continue",
      )!,
    );
    await flush();
    // The selection moves mid-switch; the switch then lands.
    render("chat-2");
    await flush();
    release!();
    await flush();
    expect(resumed).toEqual([]);
  });
});

// ── The strip mounts the banner (the chat page's wiring) ───────────────

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
              currentHarness: "claude-code",
              targetDeviceId: null,
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
