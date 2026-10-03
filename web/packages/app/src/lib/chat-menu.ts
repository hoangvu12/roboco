import type { Chat } from "@roboco/proto";

/**
 * The chat menu's row model — the pure half of the desktop's
 * `ChatMenuPage::Root` build (shell.rs:8076-8146). The desktop computes one
 * `is_side_chat` flag (the menu's host surface is a side-chat tab, OR the
 * chat row carries a parent linkage) and gates the row set on it; on the
 * web every menu host already holds the `Chat`, so the flag is parent
 * linkage alone. A side chat keeps Rename and Delete but drops the rows
 * the desktop withholds for children — Pin, Archive, and the Copy page's
 * conversation-link arm. The component renders exactly this row set in
 * this order; the model is stateless about the rows' own labels (Pin vs
 * Unpin) and mutations.
 */

/** One chat-menu root row, in draw order. */
export type ChatMenuRowId = "rename" | "pin" | "archive" | "copy" | "delete";

/**
 * `is_side_chat` — the menu's child-chat flag: a chat with a parent is a
 * side chat wherever its menu opens. An orphaned child (parent deleted)
 * still counts: it is not a top-level chat and never earns the top-level
 * row set.
 */
export function isSideChat(chat: Chat): boolean {
  return chat.parentChatId != null;
}

/**
 * The root page's rows: Rename, Pin, Archive, Copy, then Delete for a
 * top-level chat (`chat-menu-rename` / `-pin` / `-archive` / `-copy` /
 * `-delete`); a side chat keeps Rename and Delete alone. The separator
 * before Delete always renders — the component owns it.
 */
export function chatMenuRows(input: {
  readonly chat: Chat;
  readonly isPinned: boolean;
}): ChatMenuRowId[] {
  if (isSideChat(input.chat)) {
    return ["rename", "delete"];
  }
  return ["rename", "pin", "archive", "copy", "delete"];
}
