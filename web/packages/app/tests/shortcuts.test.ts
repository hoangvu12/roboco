import { describe, expect, it } from "vitest";
import { emitShortcut, onShortcut } from "../src/state/shortcuts";

describe("shortcut event bus", () => {
  it("delivers a shortcut to every registered listener", () => {
    const calls: string[] = [];
    const offA = onShortcut("new-chat", () => {
      calls.push("a");
    });
    const offB = onShortcut("new-chat", () => {
      calls.push("b");
    });
    emitShortcut("new-chat");
    expect(calls.sort()).toEqual(["a", "b"]);
    offA();
    offB();
  });

  it("stops delivering to unsubscribed listeners", () => {
    const calls: string[] = [];
    const off = onShortcut("new-chat", () => {
      calls.push("hit");
    });
    off();
    emitShortcut("new-chat");
    expect(calls).toEqual([]);
  });

  it("swallows listener errors so other listeners still fire", () => {
    const calls: string[] = [];
    onShortcut("new-chat", () => {
      throw new Error("boom");
    });
    onShortcut("new-chat", () => {
      calls.push("ok");
    });
    emitShortcut("new-chat");
    expect(calls).toEqual(["ok"]);
  });

  it("is a no-op when no listeners have registered", () => {
    expect(() => emitShortcut("new-chat")).not.toThrow();
  });
});