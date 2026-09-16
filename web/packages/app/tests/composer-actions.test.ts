import { describe, expect, it } from "vitest";
import { RpcError } from "@roboco/engine-client";
import type { ChatConfig } from "@roboco/proto";
import {
  buildChatConfig,
  buildRunRequest,
  describeSendError,
  sendInterrupt,
  sendRun,
  sendSteer,
  persistChatConfig,
  type DraftConfig,
} from "../src/lib/composer-actions";

class FakeCaller {
  readonly calls: { method: string; params: unknown }[] = [];
  replies: Map<string, unknown> = new Map();
  nextError: Error | null = null;
  nextReply: unknown = undefined;

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    if (this.nextError !== null) {
      const error = this.nextError;
      this.nextError = null;
      throw error;
    }
    if (this.nextReply !== undefined) {
      const reply = this.nextReply;
      this.nextReply = undefined;
      return reply as T;
    }
    const byMethod = this.replies.get(method);
    if (byMethod !== undefined) {
      return byMethod as T;
    }
    return {} as T;
  }
}

const DRAFT: DraftConfig = {
  harness: "claude-code",
  model: "claude-3-5-sonnet",
  reasoning: "high",
  sandbox: "workspace-write",
  modelOptions: { "effort": "low" },
};

const PERSISTED: ChatConfig = {
  harness: "claude-code",
  model: "claude-3-5-sonnet",
  reasoning: "high",
  sandbox: "workspace-write",
  modelOptions: { "effort": "low" },
};

describe("buildChatConfig", () => {
  it("mirrors the draft as the wire's ChatConfig", () => {
    expect(buildChatConfig(DRAFT)).toEqual(PERSISTED);
  });

  it("copies modelOptions so callers cannot mutate the draft by reference", () => {
    const config = buildChatConfig(DRAFT);
    expect(config.modelOptions).not.toBe(DRAFT.modelOptions);
    expect(config.modelOptions).toEqual(DRAFT.modelOptions);
  });
});

describe("buildRunRequest", () => {
  it("fills every field the engine requires for a Run", () => {
    const request = buildRunRequest(DRAFT, "hi", "/Users/me/proj", "msg-1");
    expect(request.prompt).toBe("hi");
    expect(request.harness).toBe("claude-code");
    expect(request.model).toBe("claude-3-5-sonnet");
    expect(request.reasoning).toBe("high");
    expect(request.cwd).toBe("/Users/me/proj");
    expect(request.sandbox).toBe("workspace-write");
    // The messageId lives on the SessionCommandPayload (`run`), not the RunRequest itself.
    expect(request).not.toHaveProperty("messageId");
  });

  it("preserves the picked harness on the wire", () => {
    const request = buildRunRequest({ ...DRAFT, harness: "codex" }, "hi", "/tmp", "m");
    expect(request.harness).toBe("codex");
  });
});

describe("sendRun", () => {
  it("sends Mutate setChatConfig (when the draft drifted) then QueueCommand Run", async () => {
    const caller = new FakeCaller();
    caller.replies.set("QueueCommand", { commandId: "cmd-1" });
    await sendRun(caller, "chat-1", DRAFT, "  ship it  ", "/Users/me/proj", {
      currentConfig: null,
      mintMessageId: () => "msg-1",
    });
    expect(caller.calls.map((entry) => entry.method)).toEqual(["Mutate", "QueueCommand"]);
    const setConfig = caller.calls[0]!.params as { op: string; chatId: string; config: ChatConfig };
    expect(setConfig.op).toBe("setChatConfig");
    expect(setConfig.chatId).toBe("chat-1");
    expect(setConfig.config).toEqual(PERSISTED);
    const queue = caller.calls[1]!.params as { chatId: string; command: { kind: string; messageId: string; request: { prompt: string; cwd: string } } };
    expect(queue.chatId).toBe("chat-1");
    expect(queue.command.kind).toBe("run");
    expect(queue.command.messageId).toBe("msg-1");
    expect(queue.command.request.prompt).toBe("ship it");
    expect(queue.command.request.cwd).toBe("/Users/me/proj");
  });

  it("skips setChatConfig when the persisted config already matches the draft", async () => {
    const caller = new FakeCaller();
    caller.replies.set("QueueCommand", { commandId: "cmd-1" });
    await sendRun(caller, "chat-1", DRAFT, "ship", "/Users/me/proj", {
      currentConfig: PERSISTED,
      mintMessageId: () => "msg-1",
    });
    expect(caller.calls.map((entry) => entry.method)).toEqual(["QueueCommand"]);
  });

  it("rejects an empty prompt before touching the wire", async () => {
    const caller = new FakeCaller();
    await expect(sendRun(caller, "chat-1", DRAFT, "   ", "/Users/me/proj")).rejects.toThrow(/empty/);
    expect(caller.calls).toHaveLength(0);
  });

  it("rejects when the chat has no cwd (project-less, unresolved)", async () => {
    const caller = new FakeCaller();
    await expect(sendRun(caller, "chat-1", DRAFT, "hi", null)).rejects.toThrow(/working directory/);
    expect(caller.calls).toHaveLength(0);
  });

  it("propagates engine failures so the caller can show a notice", async () => {
    const caller = new FakeCaller();
    caller.nextError = new RpcError("transport", "Engine is offline; reconnecting");
    await expect(sendRun(caller, "chat-1", DRAFT, "hi", "/Users/me/proj", { currentConfig: PERSISTED })).rejects.toThrow(
      "Engine is offline",
    );
  });
});

describe("sendSteer", () => {
  it("sends a Steer command with the picked prompt and a null messageId by default", async () => {
    const caller = new FakeCaller();
    await sendSteer(caller, "chat-1", "  pivot  ");
    expect(caller.calls).toEqual([
      {
        method: "QueueCommand",
        params: {
          chatId: "chat-1",
          command: { kind: "steer", prompt: "pivot", messageId: null },
          transfers: [],
        },
      },
    ]);
  });

  it("honors an explicit messageId when the caller supplies one", async () => {
    const caller = new FakeCaller();
    await sendSteer(caller, "chat-1", "pivot", "msg-9");
    const params = caller.calls[0]!.params as { command: { messageId: string } };
    expect(params.command.messageId).toBe("msg-9");
  });

  it("rejects an empty prompt", async () => {
    const caller = new FakeCaller();
    await expect(sendSteer(caller, "chat-1", "   ")).rejects.toThrow(/empty/);
    expect(caller.calls).toHaveLength(0);
  });
});

describe("sendInterrupt", () => {
  it("sends an Interrupt command with no payload", async () => {
    const caller = new FakeCaller();
    await sendInterrupt(caller, "chat-1");
    expect(caller.calls).toEqual([
      {
        method: "QueueCommand",
        params: {
          chatId: "chat-1",
          command: { kind: "interrupt" },
          transfers: [],
        },
      },
    ]);
  });
});

describe("persistChatConfig", () => {
  it("sends a setChatConfig Mutate op with the draft as the wire config", async () => {
    const caller = new FakeCaller();
    await persistChatConfig(caller, "chat-1", DRAFT);
    expect(caller.calls).toHaveLength(1);
    const params = caller.calls[0]!.params as { op: string; chatId: string; config: ChatConfig };
    expect(params).toEqual({ op: "setChatConfig", chatId: "chat-1", config: PERSISTED });
  });
});

describe("describeSendError", () => {
  it("returns the engine's error message", () => {
    expect(describeSendError(new RpcError("transport", "engine offline"))).toBe("engine offline");
  });

  it("falls back to a generic message for non-Error inputs", () => {
    expect(describeSendError("nope")).toBe("The change could not be applied.");
  });
});
