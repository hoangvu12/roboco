import { describe, expect, it } from "vitest";
import type { Chat } from "@roboco/proto";
import { chatMenuRows, isSideChat } from "../src/lib/chat-menu";

/**
 * The chat menu's row model — the pure half of the desktop's
 * `ChatMenuPage::Root` build (shell.rs:8076-8146): a side chat (a chat
 * with a parent, opened from the explorer's Chats section or a side-chat
 * tab) keeps Rename and Delete but drops Pin, Archive, and the Copy page's
 * conversation-link arm — the desktop's `!is_side_chat` gate, verbatim.
 * The component renders whatever row set the model emits; the tests pin
 * membership and order, not internals.
 */

function chat(fields: Partial<Chat>): Chat {
  return {
    id: "chat",
    deviceId: "device-1",
    title: null,
    archived: false,
    createdAt: "2026-09-16T10:00:00Z",
    lastMessageAt: null,
    lastSeenAt: null,
    config: null,
    sourceContext: null,
    ...fields,
  } as Chat;
}

describe("chatMenuRows (shell.rs ChatMenuPage::Root)", () => {
  it("a top-level chat offers rename, pin, archive, copy, then delete", () => {
    expect(chatMenuRows(chat({ id: "a" }))).toEqual([
      "rename",
      "pin",
      "archive",
      "copy",
      "delete",
    ]);
  });

  it("the model is stateless about the pin row's label — the row set never varies with pinned state", () => {
    // The caller reads `isPinned` for the label and the mutation; the row
    // model is deliberately not an input to it.
    expect(chatMenuRows(chat({ id: "a", archived: false }))).toEqual([
      "rename",
      "pin",
      "archive",
      "copy",
      "delete",
    ]);
  });

  it("a side chat drops pin, archive, and copy — rename and delete remain", () => {
    expect(chatMenuRows(chat({ id: "child", parentChatId: "parent" }))).toEqual([
      "rename",
      "delete",
    ]);
  });

  it("side chat detection follows parent linkage alone — archived status is irrelevant", () => {
    expect(isSideChat(chat({ id: "a" }))).toBe(false);
    expect(isSideChat(chat({ id: "a", parentChatId: "parent" }))).toBe(true);
    // An orphaned child (parent deleted) is still a child: it never
    // belongs in the top-level-only row set.
    expect(isSideChat(chat({ id: "a", parentChatId: "gone", archived: true }))).toBe(true);
  });
});
