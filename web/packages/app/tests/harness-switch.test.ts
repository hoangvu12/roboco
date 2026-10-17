import { describe, expect, it } from "vitest";
import type { EngineClient } from "@roboco/engine-client";
import {
  harnessSwitchable,
  switchHarness,
  switchHarnessParams,
} from "../src/lib/harness-switch";

/**
 * The one-way harness switch on the wire (t3-orchestration-v2 ticket 24) —
 * the params builder, the unlock gate, and the RPC wrapper the composer's
 * unlocked picker pick and the Limited banner's "Continue on another
 * harness" (ticket 25) both go through. The payload shapes port the
 * desktop's `switch_chat_harness` / `continue_on_harness.rs`
 * `switch_params` tests.
 */

class FakeCaller {
  readonly calls: { method: string; params: unknown }[] = [];
  #fail = false;

  constructor(fail = false) {
    this.#fail = fail;
  }

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    if (this.#fail) {
      throw new Error("The chat is working; switch harnesses between turns");
    }
    return {} as T;
  }
}

describe("switchHarnessParams", () => {
  it("carries the chat and the harness; the picks ride only when resolved", () => {
    expect(switchHarnessParams("chat-1", "pi")).toEqual({ chatId: "chat-1", harness: "pi" });
    expect(switchHarnessParams("chat-1", "codex", { model: "gpt-6-codex" })).toEqual({
      chatId: "chat-1",
      harness: "codex",
      model: "gpt-6-codex",
    });
    expect(
      switchHarnessParams("chat-1", "pi", {
        model: "glm-5.3",
        reasoning: "high",
        modelOptions: { context: "1m" },
      }),
    ).toEqual({
      chatId: "chat-1",
      harness: "pi",
      model: "glm-5.3",
      reasoning: "high",
      modelOptions: { context: "1m" },
    });
  });

  it("blank and null picks are the engine's default arms, never empty overrides", () => {
    expect(switchHarnessParams("chat-1", "pi", { model: "   " })).toEqual({
      chatId: "chat-1",
      harness: "pi",
    });
    expect(switchHarnessParams("chat-1", "pi", { model: null, reasoning: null })).toEqual({
      chatId: "chat-1",
      harness: "pi",
    });
    expect(switchHarnessParams("chat-1", "pi", { modelOptions: {} })).toEqual({
      chatId: "chat-1",
      harness: "pi",
    });
    // The sandbox never rides: the RPC keeps the chat's own pick.
    expect(switchHarnessParams("chat-1", "pi")).not.toHaveProperty("sandbox");
  });
});

describe("harnessSwitchable", () => {
  it("unlocks only an idle existing chat (the desktop's harness_switchable)", () => {
    expect(harnessSwitchable(true, false, false)).toBe(true);
    // No chat to switch.
    expect(harnessSwitchable(false, false, false)).toBe(false);
    // A send of its own is still awaiting confirmation.
    expect(harnessSwitchable(true, true, false)).toBe(false);
    // A turn is in flight (Working or AwaitingInput both count).
    expect(harnessSwitchable(true, false, true)).toBe(false);
    expect(harnessSwitchable(true, true, true)).toBe(false);
  });
});

describe("switchHarness", () => {
  it("calls the engine's SwitchHarness RPC with the built params", async () => {
    const caller = new FakeCaller();
    await switchHarness(caller, "chat-1", "pi", { model: "glm-5.3" });
    expect(caller.calls).toEqual([
      {
        method: "SwitchHarness",
        params: { chatId: "chat-1", harness: "pi", model: "glm-5.3" },
      },
    ]);
  });

  it("surfaces a refusal (the engine's wording) to the caller's failure surface", async () => {
    const caller = new FakeCaller(true);
    await expect(switchHarness(caller, "chat-1", "pi")).rejects.toThrow(
      "The chat is working; switch harnesses between turns",
    );
    expect(caller.calls).toHaveLength(1);
  });
});

// A compile-time stand-in: the wrapper accepts the real client's shape.
it("satisfies the EngineClient call surface", () => {
  expect(typeof switchHarness(new FakeCaller() as unknown as EngineClient, "c", "pi")).toBe("object");
});
