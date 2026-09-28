import { describe, expect, it } from "vitest";
import type { Chat } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import {
  beginSideChatCreate,
  createChildChat,
  endSideChatCreate,
  forkSideChat,
  resetSideChatCreate,
  sideChatCreating,
} from "../src/lib/side-chat-actions";
import { chatDrafts } from "../src/lib/composer-draft";
import { sideChatDrafts, sideChatHasDraft } from "../src/state/side-chats";

/**
 * Side-chat creation on the wire — the RPC half of
 * `crates/ui/src/shell/side_chats.rs` (731697b6): `FORK_SIDE_CHAT` and
 * `Mutate createChat` with `parentChatId`, plus the one-at-a-time guard.
 */

interface Recorded {
  method: string;
  params: unknown;
}

class FakeCaller {
  readonly calls: Recorded[] = [];
  #reply: unknown = { ok: true };

  constructor(reply: unknown = { ok: true }) {
    this.#reply = reply;
  }

  async call<T>(method: string, params?: unknown): Promise<T> {
    this.calls.push({ method, params });
    return this.#reply as T;
  }
}

function parent(fields: Partial<Chat> = {}): Chat {
  return {
    id: "source-1",
    deviceId: "dev-1",
    title: "The source chat",
    archived: false,
    cwd: "/repo",
    branch: "main",
    checkoutId: null,
    config: { harness: "claude-code", model: null, reasoning: null, sandbox: "workspace-write", modelOptions: {} },
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-09-19T10:00:00Z",
    spaceId: "space-1",
    ...fields,
  };
}

describe("forkSideChat", () => {
  it("sends FORK_SIDE_CHAT with the minted id, source and parent, and returns the fresh Chat", async () => {
    const forked: Chat = { ...parent(), id: "fork-9", parentChatId: "source-1" };
    const caller = new FakeCaller(forked);
    const reply = await forkSideChat(caller, {
      chatId: "fork-9",
      sourceChatId: "source-1",
      parentChatId: "source-1",
      targetDeviceId: "dev-1",
    });
    expect(reply).toEqual(forked);
    expect(caller.calls).toEqual([
      {
        method: methods.FORK_SIDE_CHAT,
        params: {
          chatId: "fork-9",
          sourceChatId: "source-1",
          parentChatId: "source-1",
          targetDeviceId: "dev-1",
        },
      },
    ]);
  });

  it("inserts only the fields it was given — parentChatId and targetDeviceId are optional", async () => {
    const caller = new FakeCaller(parent());
    await forkSideChat(caller, { chatId: "f", sourceChatId: "s" });
    const params = caller.calls[0]!.params as Record<string, unknown>;
    expect(params).toEqual({ chatId: "f", sourceChatId: "s" });
    expect(params).not.toHaveProperty("parentChatId");
    expect(params).not.toHaveProperty("targetDeviceId");
  });
});

describe("createChildChat", () => {
  it("copies the parent's device/space/config/branch/cwd onto createChat with parentChatId", async () => {
    const caller = new FakeCaller();
    const chatId = await createChildChat(caller, parent(), { mintId: () => "child-1" });
    expect(chatId).toBe("child-1");
    expect(caller.calls).toEqual([
      {
        method: methods.MUTATE,
        params: {
          op: "createChat",
          chatId: "child-1",
          spaceId: "space-1",
          cwd: "/repo",
          branch: "main",
          config: {
            harness: "claude-code",
            model: null,
            reasoning: null,
            sandbox: "workspace-write",
            modelOptions: {},
          },
          parentChatId: "source-1",
        },
      },
    ]);
  });

  it("a projectless parent names its own device and never rides the '~' cwd", async () => {
    const caller = new FakeCaller();
    await createChildChat(caller, parent({ spaceId: null, cwd: "~", branch: null }), {
      mintId: () => "child-2",
    });
    const params = caller.calls[0]!.params as Record<string, unknown>;
    expect(params).toMatchObject({
      op: "createChat",
      chatId: "child-2",
      deviceId: "dev-1",
      parentChatId: "source-1",
    });
    expect(params).not.toHaveProperty("spaceId");
    expect(params).not.toHaveProperty("cwd");
    expect(params).not.toHaveProperty("branch");
  });
});

describe("the one-at-a-time guard (side_chat_creating)", () => {
  it("arms once and refuses a second creation until it settles", () => {
    resetSideChatCreate();
    expect(beginSideChatCreate()).toBe(true);
    expect(sideChatCreating()).toBe(true);
    expect(beginSideChatCreate()).toBe(false);
    endSideChatCreate();
    expect(sideChatCreating()).toBe(false);
    expect(beginSideChatCreate()).toBe(true);
    resetSideChatCreate();
  });
});

describe("the side-chat draft store", () => {
  it("mirrors the composer's draft and reports content, clearing empty drafts", () => {
    sideChatDrafts.reset();
    chatDrafts.reset();
    const chatId = "side-1";
    expect(sideChatDrafts.get(chatId)).toEqual({ text: "", staged: [] });
    expect(sideChatHasDraft(chatId)).toBe(false);

    sideChatDrafts.set(chatId, { text: "an unsent thought", staged: [] });
    expect(sideChatDrafts.hasContent(chatId)).toBe(true);
    expect(sideChatHasDraft(chatId)).toBe(true);

    // Text-only and staged-only both count (composer_has_content).
    sideChatDrafts.set(chatId, { text: "", staged: [{ path: "a.png" }] as never[] });
    expect(sideChatHasDraft(chatId)).toBe(true);

    // An empty draft leaves the map — no tombstones.
    sideChatDrafts.set(chatId, { text: "", staged: [] });
    expect(sideChatDrafts.get(chatId)).toEqual({ text: "", staged: [] });
    expect(sideChatHasDraft(chatId)).toBe(false);

    // Staged review comments count through the injected accessor.
    expect(sideChatHasDraft("side-2", () => 0)).toBe(false);
    expect(sideChatHasDraft("side-2", () => 2)).toBe(true);

    // The shared text map counts too (the remount seed's source).
    chatDrafts.set("side-3", "leftover text");
    expect(sideChatHasDraft("side-3")).toBe(true);
    sideChatDrafts.clear("side-3");
    chatDrafts.clear("side-3");
    expect(sideChatHasDraft("side-3")).toBe(false);
  });
});
