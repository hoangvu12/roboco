import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import type { Chat } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { sidebarNotice } from "../state/notice";
import { deleteChat, describeMutateError, renameChat, setChatArchived, type MutateCaller } from "../lib/chat-actions";
import { singleLine } from "../lib/view";
import { classifyKey } from "../lib/picker-search";
import { menuAt } from "../lib/popover-anchor";
import { RbDialog } from "./base/dialog";
import { PopoverCard, MenuSeparator, DialogCard, DialogTitle, DialogBody, DialogField, BtnGhost, BtnPrimary, BtnDanger } from "./popover/menu";
import { MenuRow } from "./popover/menu-row";
import { Popup, usePopup } from "./popover/popup";

/**
 * The chat row's management surface — the desktop's `ChatMenuState`
 * (shell.rs:5433-5608). Opened by RIGHT mouse-down at the pointer (both the
 * active list and the archived shelf reuse it), positioned clamp-only at
 * the pointer (`menu_at` — no flip), 216px wide. The Copy row swaps the
 * card's content to a Copy page IN PLACE — no second floating layer. Rename
 * and delete open modal dialogs; mutation failures surface in the sidebar
 * notice strip.
 *
 * There is no kebab: the right-click is the only affordance (the ticket
 * settles the research's open question — right-click only, kebab removed).
 */

/** The card width (`shell.rs`'s ChatMenu card). */
const CHAT_MENU_WIDTH = 216;

export function useChatMenu(chat: Chat) {
  const session = useEngineSession();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const [dialog, setDialog] = useState<"rename" | "delete" | null>(null);

  function run(mutation: (caller: MutateCaller) => Promise<unknown>): void {
    if (session === null) {
      sidebarNotice.set("Engine not connected");
      return;
    }
    mutation(session.client).catch((error: unknown) => {
      sidebarNotice.set(describeMutateError(error));
    });
  }

  /** Open the context menu at the pointer (`ChatMenuState::position`). */
  const openAt = useCallback((x: number, y: number): void => {
    setMenu({ x, y });
  }, []);

  const element = (
    <>
      {menu !== null && (
        <ChatMenu
          chat={chat}
          anchor={menu}
          onRename={() => {
            setMenu(null);
            setDialog("rename");
          }}
          onArchive={() => {
            setMenu(null);
            run((caller) => setChatArchived(caller, chat.id, true));
          }}
          onDelete={() => {
            setMenu(null);
            setDialog("delete");
          }}
          onClose={() => setMenu(null)}
        />
      )}
      {dialog === "rename" && (
        <RenameChatDialog
          chat={chat}
          onSubmit={(title) => run((caller) => renameChat(caller, chat.id, title))}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === "delete" && (
        <DeleteChatDialog chat={chat} onDelete={() => run((caller) => deleteChat(caller, chat.id))} onClose={() => setDialog(null)} />
      )}
    </>
  );

  return { openAt, element };
}

function ChatMenu({
  chat,
  anchor,
  onRename,
  onArchive,
  onDelete,
  onClose,
}: {
  readonly chat: Chat;
  readonly anchor: { x: number; y: number };
  readonly onRename: () => void;
  readonly onArchive: () => void;
  readonly onDelete: () => void;
  readonly onClose: () => void;
}) {
  const popup = usePopup<"chat">();
  const [page, setPage] = useState<"root" | "copy">("root");

  // Open on mount at the pointer — clamp-only, never flipping above.
  useEffect(() => {
    popup.open("chat");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    if (popup.asOpen() === null) {
      return;
    }
    if (classifyKey(event.key, event.metaKey, event.ctrlKey) === "escape") {
      event.preventDefault();
      onClose();
      popup.closeByEscape();
    }
  };

  const codexLink = codexConversationLink(chat);
  const harnessSessionId =
    typeof chat.harnessSessionId === "string" && chat.harnessSessionId.trim().length > 0
      ? chat.harnessSessionId
      : null;

  async function copyToClipboard(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // A refused clipboard permission is not worth a crash.
    }
  }

  async function copyConversationLink(): Promise<void> {
    // The web's conversation link is its page URL — the desktop's
    // `roboco://open/chat/…` deep link has no browser handler.
    const link = typeof window === "undefined" ? null : new URL(`/chat/${chat.id}`, window.location.origin).toString();
    onClose();
    if (link === null) {
      sidebarNotice.set("Conversation link is not ready yet");
      return;
    }
    await copyToClipboard(link);
    sidebarNotice.set("Roboco conversation link copied");
  }

  async function copyText(text: string, notice: string): Promise<void> {
    onClose();
    await copyToClipboard(text);
    sidebarNotice.set(notice);
  }

  return (
    <Popup popup={popup} placement={(size) => menuAt(anchor, size)}>
      {() => (
        <PopoverCard role="menu" aria-label="Chat actions" style={{ width: CHAT_MENU_WIDTH }} onKeyDown={onKeyDown}>
          {page === "root" ? (
              <>
                <MenuRow fadeKey="rename" onClick={onRename}>
                  <Icon name="pen" size={16} className="chat-menu-row-icon" />
                  <span className="menu-row-label">Rename…</span>
                </MenuRow>
                <MenuRow fadeKey="archive" onClick={onArchive}>
                  <Icon name="archiveMinimalistic" size={16} className="chat-menu-row-icon" />
                  <span className="menu-row-label">Archive</span>
                </MenuRow>
                <MenuRow
                  fadeKey="copy"
                  onClick={() => {
                    // The Copy page replaces the card's content IN PLACE —
                    // no second floating layer, no portal remount.
                    setPage("copy");
                  }}
                >
                  <Icon name="copy" size={16} className="chat-menu-row-icon" />
                  <span className="menu-row-label">Copy</span>
                  <span className="chat-menu-row-spring" />
                  <Icon name="altArrowRight" size={14} className="chat-menu-row-arrow" />
                </MenuRow>
                <MenuSeparator />
                <MenuRow fadeKey="delete" className="chat-menu-row-danger" onClick={onDelete}>
                  <Icon name="trashBinMinimalistic" size={16} className="chat-menu-row-icon-danger" />
                  <span className="menu-row-label">Delete…</span>
                </MenuRow>
              </>
            ) : (
              <>
                <MenuRow
                  fadeKey="back"
                  onClick={() => {
                    setPage("root");
                  }}
                >
                  <Icon name="altArrowLeft" size={16} className="chat-menu-row-icon" />
                  <span className="menu-row-label">Back</span>
                </MenuRow>
                <MenuSeparator />
                <MenuRow fadeKey="roboco-link" onClick={() => void copyConversationLink()}>
                  <Icon name="copy" size={16} className="chat-menu-row-icon" />
                  <span className="menu-row-label">Roboco conversation link</span>
                </MenuRow>
                {codexLink !== null && (
                  <MenuRow
                    fadeKey="codex-link"
                    onClick={() => void copyText(codexLink.url, `${codexLink.label} copied`)}
                  >
                    <Icon name="copy" size={16} className="chat-menu-row-icon" />
                    <span className="menu-row-label">{codexLink.label}</span>
                  </MenuRow>
                )}
                {harnessSessionId !== null && (
                  <MenuRow
                    fadeKey="harness-session"
                    onClick={() => void copyText(harnessSessionId, "Harness session ID copied")}
                  >
                    <Icon name="copy" size={16} className="chat-menu-row-icon" />
                    <span className="menu-row-label">Harness session ID</span>
                  </MenuRow>
                )}
              </>
            )}
          </PopoverCard>
        )}
      </Popup>
  );
}

/**
 * `links.rs::harness_conversation_link` — only schemes verified against the
 * harness app: a codex chat's session id becomes `codex://threads/{id}`
 * ("Codex conversation link"). Hermes exposes a candidate scheme, but its
 * contract is not stable enough for users' clipboards yet.
 */
function codexConversationLink(chat: Chat): { label: string; url: string } | null {
  const id = chat.harnessSessionId;
  if (typeof id !== "string" || id.trim().length === 0) {
    return null;
  }
  if (chat.config === null || chat.config.harness !== "codex") {
    return null;
  }
  return { label: "Codex conversation link", url: `codex://threads/${encodeComponent(id)}` };
}

function encodeComponent(value: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(value)) {
    if (
      (byte >= 0x30 && byte <= 0x39) ||
      (byte >= 0x41 && byte <= 0x5a) ||
      (byte >= 0x61 && byte <= 0x7a) ||
      byte === 0x2d ||
      byte === 0x5f ||
      byte === 0x2e ||
      byte === 0x7e
    ) {
      out += String.fromCharCode(byte);
    } else {
      out += `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
    }
  }
  return out;
}

/**
 * The rename dialog (shell.rs open_rename_chat / submit_rename_chat):
 * prefilled single-line input, Enter submits, an empty title is a no-op.
 * Escape closes through RbDialog's escape path (`onOpenChange(false)`).
 */
function RenameChatDialog({ chat, onSubmit, onClose }: { chat: Chat; onSubmit: (title: string) => void; onClose: () => void }) {
  const [title, setTitle] = useState(chat.title ?? "");
  const inputRef = useRef<HTMLInputElement | null>(null);

  return (
    <RbDialog
      open
      onOpenChange={(next) => {
        if (!next) {
          onClose();
        }
      }}
      ariaLabel="Rename session"
      initialFocus={inputRef}
    >
      <DialogCard>
        <DialogTitle>Rename session</DialogTitle>
        <form
          className="dialog-form-rows"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit(title);
            onClose();
          }}
        >
          <DialogField>
            <input
              ref={inputRef}
              type="text"
              aria-label="Session title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              spellCheck={false}
            />
          </DialogField>
          <div className="dialog-actions-row">
            <BtnGhost type="button" onClick={onClose}>
              Cancel
            </BtnGhost>
            <BtnPrimary type="submit">Rename</BtnPrimary>
          </div>
        </form>
      </DialogCard>
    </RbDialog>
  );
}

/**
 * The delete dialog (shell.rs:5660-5701): "Delete session?" with the
 * curly-quote body — kept verbatim, it already matches the desktop.
 * Deleting the open chat navigates back to the list.
 */
function DeleteChatDialog({ chat, onDelete, onClose }: { chat: Chat; onDelete: () => void; onClose: () => void }) {
  const navigate = useNavigate();
  const params = useParams({ strict: false });
  const title = chat.title !== null && singleLine(chat.title).length > 0 ? singleLine(chat.title) : "New session";
  const openChatId = (params as { chatId?: string }).chatId;

  function confirm(): void {
    onDelete();
    onClose();
    if (openChatId === chat.id) {
      void navigate({ to: "/" });
    }
  }

  return (
    <RbDialog
      open
      onOpenChange={(next) => {
        if (!next) {
          onClose();
        }
      }}
      ariaLabel="Delete session?"
    >
      <DialogCard>
        <DialogTitle>Delete session?</DialogTitle>
        <DialogBody>{`\u201C${title}\u201D will be permanently deleted. This can\u2019t be undone.`}</DialogBody>
        <div className="dialog-actions-row">
          <BtnGhost onClick={onClose}>Cancel</BtnGhost>
          <BtnDanger onClick={confirm}>Delete</BtnDanger>
        </div>
      </DialogCard>
    </RbDialog>
  );
}
