import { describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import type {
  AgentAccount,
  AgentUsageWindow,
  HarnessDescriptor,
  HarnessId,
  ResumeAccountCandidates,
} from "@roboco/proto";
import {
  RESUME_CONTINUE_PROMPT,
  cancelScheduledResume,
  candidateResetCaption,
  continueOfferedHarnesses,
  failoverCardTitle,
  failoverListOffers,
  failoverOutcomeNotice,
  failoverRows,
  harnessOffersFailover,
  limitedBannerLabel,
  listResumeAccountCandidates,
  resumeAtResetArmable,
  resumeOnAnotherAccount,
  scheduleResumeAtReset,
} from "../src/lib/limited";

/**
 * Limited chats on the web (orchestration v2, ticket 10): the pure
 * view-model the banner renders from, and the four RPC wrappers its
 * actions go through. Names mirror the Rust tests they port
 * (proto/src/view.rs's limited tests, shell.rs's `failover_rows` /
 * `failover_outcome_notice` / `candidate_reset_caption`).
 */

const NOW = Date.parse("2026-10-16T12:00:00Z");

/** Epoch ms `secs` after the fixed now. */
function ms(secs: number): number {
  return NOW + secs * 1000;
}

function limit(fields: Partial<{ harness: HarnessId; window: string; resetsAt: number | null; accountId: string | null }> = {}): {
  harness: HarnessId;
  window: string;
  resetsAt: number | null;
  accountId: string | null;
} {
  return {
    harness: "claude-code",
    window: "5-hour",
    resetsAt: null,
    accountId: null,
    ...fields,
  };
}

function scheduled(fireAt: number): { fireAt: number } {
  return { fireAt };
}

class FakeCaller {
  readonly calls: { method: string; params: unknown }[] = [];
  replies: Map<string, unknown> = new Map();

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    const byMethod = this.replies.get(method);
    if (byMethod !== undefined) {
      return byMethod as T;
    }
    return {} as T;
  }
}

const CALLER = new FakeCaller() as unknown as EngineClient;

// ── The continue prompt ──────────────────────────────────────────────────

describe("RESUME_CONTINUE_PROMPT", () => {
  it("is the engine's fixed wording (roboco_proto::RESUME_CONTINUE_PROMPT)", () => {
    expect(RESUME_CONTINUE_PROMPT).toBe("Continue where you left off.");
  });
});

// ── The banner's label and arms ──────────────────────────────────────────

describe("limitedBannerLabel", () => {
  it("names the window and its reset (resets is relative, never past)", () => {
    expect(limitedBannerLabel(limit({ resetsAt: ms(45 * 60) }), null, NOW)).toBe(
      "5-hour limit · resets in 45m",
    );
    expect(limitedBannerLabel(limit({ resetsAt: ms(3 * 3600 + 5 * 60) }), null, NOW)).toBe(
      "5-hour limit · resets in 3h 05m",
    );
    // A reset already due: the honest offer is Resume, not a countdown.
    expect(limitedBannerLabel(limit({ resetsAt: ms(-600) }), null, NOW)).toBe(
      "5-hour limit · resets now",
    );
  });

  it("an armed continuation names when it fires instead (ticket 07)", () => {
    expect(limitedBannerLabel(limit({ resetsAt: ms(3 * 3600) }), scheduled(ms(45 * 60)), NOW)).toBe(
      "5-hour limit · resumes in 45m",
    );
  });

  it("an unknown reset says so; a missing record keeps the generic word", () => {
    expect(limitedBannerLabel(limit(), null, NOW)).toBe("5-hour limit · reset time unknown");
    expect(limitedBannerLabel(null, null, NOW)).toBe("Usage limit reached");
  });
});

describe("resumeAtResetArmable", () => {
  it("arms only with a reset still ahead and nothing armed", () => {
    expect(resumeAtResetArmable(limit({ resetsAt: ms(60) }), null, NOW)).toBe(true);
    expect(resumeAtResetArmable(limit({ resetsAt: ms(-60) }), null, NOW)).toBe(false);
    expect(resumeAtResetArmable(limit(), null, NOW)).toBe(false);
    expect(resumeAtResetArmable(limit({ resetsAt: ms(60) }), scheduled(ms(60)), NOW)).toBe(false);
    expect(resumeAtResetArmable(null, null, NOW)).toBe(false);
  });
});

// ── The failover picker's model ──────────────────────────────────────────

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

function candidates(fields: Partial<ResumeAccountCandidates> = {}): ResumeAccountCandidates {
  return {
    chatId: "chat-1",
    harness: "claude-code",
    limitedAccountId: null,
    candidates: [],
    signedOut: [],
    ...fields,
  };
}

// ── "Continue on another harness" (tickets 25/26) ─────────────────────

describe("continueOfferedHarnesses", () => {
  const descriptor = (id: HarnessId, installed = true): HarnessDescriptor => ({
    id,
    name: id,
    supportsSteering: true,
    steeringMode: "step-boundary",
    reasoningLevels: [],
    installed,
    canInstall: false,
    enabled: true,
  });

  it("offers the composer's offered set minus the chat's current harness", () => {
    const catalog = [
      descriptor("claude-code"),
      descriptor("codex"),
      descriptor("pi"),
      // Present but not installed: offered_harnesses drops it.
      descriptor("grok", false),
    ];
    const offered = continueOfferedHarnesses(catalog, "claude-code");
    expect(offered.map((row) => row.id)).toEqual(["codex", "pi"]);
    // A config-less chat (no current harness): the whole offered set.
    expect(continueOfferedHarnesses(catalog, null)).toHaveLength(3);
    // The current harness is the ONLY other one: nothing to offer.
    expect(continueOfferedHarnesses([descriptor("claude-code")], "claude-code")).toEqual([]);
    expect(continueOfferedHarnesses([], "pi")).toEqual([]);
  });
});

describe("harnessOffersFailover / failoverCardTitle", () => {
  it("only the switchable harnesses offer the account failover (ticket 09)", () => {
    expect(harnessOffersFailover("claude-code")).toBe(true);
    expect(harnessOffersFailover("codex")).toBe(true);
    expect(harnessOffersFailover("cursor")).toBe(false);
    expect(harnessOffersFailover("pi")).toBe(false);
    expect(failoverCardTitle("codex")).toBe("Resume on another Codex account");
    expect(failoverCardTitle("claude-code")).toBe("Resume on another Claude account");
  });
});

describe("failoverRows", () => {
  it("candidates first (best = recommended), then the signed-out accounts", () => {
    const best = account("best");
    const second = account("second");
    const dead = account("dead", { signInState: "signed-out" });
    const rows = failoverRows(candidates({ candidates: [best, second], signedOut: [dead] }));
    expect(rows).toEqual([
      { kind: "candidate", account: best, recommended: true },
      { kind: "candidate", account: second, recommended: false },
      { kind: "signedOut", account: dead },
    ]);
  });

  it("tolerates a payload without the additive signedOut field", () => {
    const list = candidates({ candidates: [account("best")] }) as ResumeAccountCandidates & {
      signedOut?: AgentAccount[];
    };
    delete list.signedOut;
    expect(failoverRows(list)).toEqual([{ kind: "candidate", account: account("best"), recommended: true }]);
  });
});

describe("failoverListOffers", () => {
  it("both lists empty leaves only Resume (the chip hides)", () => {
    expect(failoverListOffers(candidates())).toBe(false);
    expect(failoverListOffers(candidates({ candidates: [account("a")] }))).toBe(true);
    expect(failoverListOffers(candidates({ signedOut: [account("dead", { signInState: "signed-out" })] }))).toBe(true);
  });
});

describe("failoverOutcomeNotice", () => {
  it("switched names the account; no candidates explains itself; failed surfaces the reason", () => {
    expect(
      failoverOutcomeNotice({ type: "switched", accountId: "a1", accountEmail: "ada@example.com" }),
    ).toBe("Resumed on ada@example.com — continuing where the chat left off.");
    expect(failoverOutcomeNotice({ type: "switched", accountId: "a1", accountEmail: null })).toBe(
      "Resumed on another account — continuing where the chat left off.",
    );
    expect(failoverOutcomeNotice({ type: "noCandidates" })).toBe(
      "No other account is available right now.",
    );
    expect(failoverOutcomeNotice({ type: "failed", reason: "engine offline" })).toBe("engine offline");
  });
});

describe("candidateResetCaption", () => {
  function windowAt(label: string, resetsAt: string | null, usedFraction = 0.4): AgentUsageWindow {
    return { label, usedFraction, resetsAt };
  }

  it("one entry per shown window that knows its reset (story 49)", () => {
    const account = {
      ...accountRowBase(),
      usageWindows: [windowAt("Session", new Date(ms(45 * 60)).toISOString())],
    };
    expect(candidateResetCaption(account, NOW)).toBe("session resets in 45m");
  });

  it("joins two shown windows and drops unknown resets", () => {
    const account = {
      ...accountRowBase(),
      usageWindows: [
        windowAt("Session", new Date(ms(45 * 60)).toISOString()),
        windowAt("Week", new Date(ms(2 * 86_400 + 3 * 3600)).toISOString()),
        windowAt("Bonus", null),
      ],
    };
    expect(candidateResetCaption(account, NOW)).toBe(
      "session resets in 45m · week resets in 2d",
    );
  });

  it("ignores the windows past the two the meters show", () => {
    const account = {
      ...accountRowBase(),
      usageWindows: [windowAt("A", null), windowAt("B", null), windowAt("C", new Date(ms(60)).toISOString())],
    };
    expect(candidateResetCaption(account, NOW)).toBeNull();
  });

  it("is null with nothing to say", () => {
    expect(candidateResetCaption({ ...accountRowBase(), usageWindows: [] }, NOW)).toBeNull();
  });

  function accountRowBase(): AgentAccount {
    return account("metered");
  }
});

// ── The RPC wrappers ─────────────────────────────────────────────────────

describe("scheduleResumeAtReset / cancelScheduledResume", () => {
  it("arm and withdraw the engine's durable continuation (ticket 07)", async () => {
    const caller = new FakeCaller();
    await scheduleResumeAtReset(caller, "chat-1");
    await cancelScheduledResume(caller, "chat-1");
    expect(caller.calls).toEqual([
      { method: "Mutate", params: { op: "scheduleResumeAtReset", chatId: "chat-1" } },
      { method: "Mutate", params: { op: "cancelScheduledResume", chatId: "chat-1" } },
    ]);
  });
});

describe("listResumeAccountCandidates", () => {
  it("asks the engine's ranking RPC with the chat id", async () => {
    const caller = new FakeCaller();
    const list = candidates({ candidates: [account("best")] });
    caller.replies.set("ListResumeAccountCandidates", list);
    await expect(listResumeAccountCandidates(caller, "chat-1")).resolves.toEqual(list);
    expect(caller.calls).toEqual([
      { method: "ListResumeAccountCandidates", params: { chatId: "chat-1" } },
    ]);
  });
});

describe("resumeOnAnotherAccount", () => {
  it("sends the picked account; omitted takes the engine's recommendation", async () => {
    const caller = new FakeCaller();
    caller.replies.set("ResumeOnAnotherAccount", { type: "noCandidates" });
    await resumeOnAnotherAccount(caller, "chat-1", "a1");
    await resumeOnAnotherAccount(caller, "chat-1");
    expect(caller.calls).toEqual([
      { method: "ResumeOnAnotherAccount", params: { chatId: "chat-1", accountId: "a1" } },
      { method: "ResumeOnAnotherAccount", params: { chatId: "chat-1" } },
    ]);
  });
});

// A compile-time stand-in: the wrappers accept the real client's shape.
it("satisfies the EngineClient call surface", () => {
  expect(typeof scheduleResumeAtReset(CALLER, "chat-1")).toBe("object");
});
