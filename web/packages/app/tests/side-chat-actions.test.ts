import { describe, expect, it } from "vitest";
import type { Chat, ChatConfig } from "@roboco/proto";
import { methods } from "@roboco/engine-client";
import {
  beginSideChatCreate,
  endSideChatCreate,
  forkSideChat,
  mintUnsavedSideChat,
  resetSideChatCreate,
  sideChatCreating,
} from "../src/lib/side-chat-actions";
import {
  beginUnsavedSideChat,
  clearPendingSideChat,
  completionTargetChatId,
  dropUnsavedSideChat,
  isUnsavedSideChat,
  markSideChatSaved,
  pendingSideChat,
  sideChatDrafts,
  sideChatHasDraft,
  unsavedSideChat,
} from "../src/state/side-chats";
import { chatDrafts } from "../src/lib/composer-draft";

/**
 * Side-chat creation on the wire — the RPC half of
 * `crates/ui/src/shell/side_chats.rs` (731697b6): `FORK_SIDE_CHAT` and
 * `Mutate createChat` with `parentChatId` (the fresh child's createChat now
 * lands on the FIRST SEND, upstream #568), plus the one-at-a-time guard and
 * the local-only side-chat registry (`state/side-chats.ts`).
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

describe("mintUnsavedSideChat (the first send's createChat, upstream #568)", () => {
  it("copies the parent row's device/space/config/branch/cwd onto createChat with the parent link and the picked config", async () => {
    const caller = new FakeCaller();
    const picked: ChatConfig = {
      harness: "codex",
      model: null,
      reasoning: null,
      sandbox: "workspace-write",
      modelOptions: {},
    };
    const row: Chat = { ...parent(), id: "scoped-side", parentChatId: "source-1" };
    await mintUnsavedSideChat(caller, row, "scoped-side", picked);
    expect(caller.calls).toEqual([
      {
        method: methods.MUTATE,
        params: {
          op: "createChat",
          chatId: "scoped-side",
          spaceId: "space-1",
          cwd: "/repo",
          branch: "main",
          config: picked,
          parentChatId: "source-1",
        },
      },
    ]);
  });

  it("a projectless row names its own device and never rides the '~' cwd; a null config rides nothing", async () => {
    const caller = new FakeCaller();
    const row: Chat = {
      ...parent({ spaceId: null, cwd: "~", branch: null, config: null }),
      id: "side-2",
      parentChatId: "source-1",
    };
    await mintUnsavedSideChat(caller, row, "side-2", null);
    const params = caller.calls[0]!.params as Record<string, unknown>;
    expect(params).toMatchObject({
      op: "createChat",
      chatId: "side-2",
      deviceId: "dev-1",
      parentChatId: "source-1",
    });
    expect(params).not.toHaveProperty("spaceId");
    expect(params).not.toHaveProperty("cwd");
    expect(params).not.toHaveProperty("branch");
    expect(params).not.toHaveProperty("config");
  });

  it("mints with the tab's id — the mutation is idempotent by the id the surface keys on", async () => {
    const caller = new FakeCaller();
    const row: Chat = { ...parent(), id: "tab-id", parentChatId: "source-1" };
    await mintUnsavedSideChat(caller, row, "tab-id", null);
    const params = caller.calls[0]!.params as Record<string, unknown>;
    expect(params).toMatchObject({ op: "createChat", chatId: "tab-id" });
  });
});

describe("the unsaved side-chat registry (unsaved_side_chat, state.rs)", () => {
  it("a hand-started side chat is local-only until its first send marks it saved", () => {
    const row: Chat = {
      ...parent(),
      id: "side-local",
      parentChatId: "source-1",
      lastMessageAt: null,
    };
    beginUnsavedSideChat(row);
    expect(isUnsavedSideChat("side-local")).toBe(true);
    expect(unsavedSideChat("side-local")?.id).toBe("side-local");
    // No registry row yet: the surface renders from the local copy, not a
    // creation seed (a fork's shape — that row exists on the engine).
    expect(pendingSideChat("side-local")).toBeNull();

    // The first send minted it: the flag retires and the row seeds the gap
    // before the fleet frame lands (the fork flow's shape).
    markSideChatSaved("side-local");
    expect(isUnsavedSideChat("side-local")).toBe(false);
    expect(pendingSideChat("side-local")?.id).toBe("side-local");

    // The fleet row landed: the seed retires with it (and an unsaved
    // registration cannot outlive the row it was waiting for).
    beginUnsavedSideChat(row);
    clearPendingSideChat("side-local");
    expect(pendingSideChat("side-local")).toBeNull();
    expect(isUnsavedSideChat("side-local")).toBe(false);
  });

  it("closing an unsent unsaved side chat drops it — the row and the flag", () => {
    const row: Chat = { ...parent(), id: "side-dropped", parentChatId: "source-1" };
    beginUnsavedSideChat(row);
    // A draft cannot keep it: no row could reopen it. The pane store's
    // close arm drops the entity (drafts included) and the registration.
    dropUnsavedSideChat("side-dropped");
    expect(isUnsavedSideChat("side-dropped")).toBe(false);
    expect(unsavedSideChat("side-dropped")).toBeNull();
  });
});

describe("completionTargetChatId (completion_workspace_params, upstream #588)", () => {
  it("an unsaved side chat's discovery addresses the parent, then its own row once minted", () => {
    const row: Chat = { ...parent(), id: "side-completion", parentChatId: "source-1" };
    beginUnsavedSideChat(row);
    // No engine row exists for "side-completion" yet: SearchFiles and the
    // command/skill catalogs must query the parent (the row the checkout
    // was inherited from), never the unsaved chat itself.
    expect(completionTargetChatId(row)).toBe("source-1");

    // The first send minted it: subsequent discovery switches to the chat's
    // own persisted identity.
    markSideChatSaved("side-completion");
    expect(completionTargetChatId(row)).toBe("side-completion");
  });

  it("a top-level chat and the new-chat canvas keep their own id", () => {
    // No parent link: nothing to redirect discovery to.
    expect(completionTargetChatId(parent())).toBe("source-1");
    // An unsaved row without a parent cannot happen (the mint always links
    // one), but the guard still keeps its own id rather than redirecting.
    const orphan: Chat = { ...parent(), id: "side-orphan", parentChatId: null };
    beginUnsavedSideChat(orphan);
    expect(completionTargetChatId(orphan)).toBe("side-orphan");
    dropUnsavedSideChat("side-orphan");
    // The canvas is not a chat at all: the empty id flows through (the
    // catalog params take the space/device branches instead).
    expect(completionTargetChatId({ ...parent(), id: "" })).toBe("");
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
