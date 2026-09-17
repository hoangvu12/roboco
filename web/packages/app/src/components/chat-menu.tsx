import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate, useParams } from "@tanstack/react-router";
import type { Chat } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { sidebarNotice } from "../state/notice";
import { deleteChat, describeMutateError, renameChat, setChatArchived, type MutateCaller } from "../lib/chat-actions";
import { singleLine } from "../lib/view";

/**
 * The chat row's management affordance — the web peer of the desktop's
 * chat context menu (shell.rs ChatMenuState). The desktop opens it on
 * RIGHT mouse-down at the pointer; the web keeps that plus a hover/focus
 * kebab (its touch-only analogue — phones have no right-click, and the
 * phone layer must keep working) and the archived shelf's rows reuse the
 * same surface. Rename and delete open modal dialogs; mutation failures
 * surface in the sidebar notice strip. Menus and dialogs portal to <body>
 * so the sidebar's overflow and the phone drawer's transform never clip
 * them.
 */
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
      {dialog === "delete" && <DeleteChatDialog chat={chat} onDelete={() => run((caller) => deleteChat(caller, chat.id))} onClose={() => setDialog(null)} />}
    </>
  );

  return { openAt, element };
}

/** The touch/hover analogue of the desktop's right-click affordance. */
export function ChatRowKebab({
  chat,
  openAt,
}: {
  chat: Chat;
  openAt: (x: number, y: number) => void;
}) {
  return (
    <button
      type="button"
      className="chat-row-kebab"
      aria-label={`Manage ${chat.title ?? "New session"}`}
      onClick={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        openAt(rect.right, rect.bottom);
      }}
    >
      ⋯
    </button>
  );
}

const MENU_WIDTH = 160;

function ChatMenu({
  anchor,
  onRename,
  onArchive,
  onDelete,
  onClose,
}: {
  anchor: { x: number; y: number };
  onRename: () => void;
  onArchive: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ left: number; top: number }>({ left: anchor.x - MENU_WIDTH, top: anchor.y + 4 });

  useLayoutEffect(() => {
    const menu = ref.current;
    if (menu === null) {
      return;
    }
    const rect = menu.getBoundingClientRect();
    const left = Math.max(8, Math.min(anchor.x - MENU_WIDTH, window.innerWidth - rect.width - 8));
    const below = anchor.y + 4;
    const top = below + rect.height > window.innerHeight - 8 ? Math.max(8, anchor.y - rect.height - 4) : below;
    setPosition((current) => (current.left === left && current.top === top ? current : { left, top }));
  }, [anchor]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return createPortal(
    <div
      className="menu-backdrop"
      onClick={onClose}
      onContextMenu={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div
        ref={ref}
        className="chat-menu panel"
        role="menu"
        style={{ left: position.left, top: position.top }}
        onClick={(event) => event.stopPropagation()}
      >
        <button type="button" role="menuitem" onClick={onRename}>
          Rename…
        </button>
        <button type="button" role="menuitem" onClick={onArchive}>
          Archive
        </button>
        <div className="chat-menu-sep" />
        <button type="button" role="menuitem" className="chat-menu-danger" onClick={onDelete}>
          Delete…
        </button>
      </div>
    </div>,
    document.body,
  );
}

/** Esc closes a dialog; rendered only while open. */
function useEscape(onClose: () => void): void {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
}

function DialogCard({ label, onClose, children }: { label: string; onClose: () => void; children: React.ReactNode }) {
  useEscape(onClose);
  return createPortal(
    <div className="dialog-backdrop" onClick={onClose}>
      <section className="dialog panel" role="dialog" aria-label={label} onClick={(event) => event.stopPropagation()}>
        {children}
      </section>
    </div>,
    document.body,
  );
}

/**
 * The rename dialog (shell.rs open_rename_chat / submit_rename_chat):
 * prefilled single-line input, Enter submits, an empty title is a no-op.
 */
function RenameChatDialog({ chat, onSubmit, onClose }: { chat: Chat; onSubmit: (title: string) => void; onClose: () => void }) {
  const [title, setTitle] = useState(chat.title ?? "");

  function submit(event: React.FormEvent): void {
    event.preventDefault();
    onSubmit(title);
    onClose();
  }

  return (
    <DialogCard label="Rename session" onClose={onClose}>
      <h2 className="dialog-title">Rename session</h2>
      <form className="dialog-form" onSubmit={submit}>
        <input
          className="input"
          type="text"
          aria-label="Session title"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          autoFocus
          spellCheck={false}
        />
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-solid">
            Rename
          </button>
        </div>
      </form>
    </DialogCard>
  );
}

/**
 * The delete dialog (shell.rs delete_confirm): names the chat and requires
 * an explicit confirm. Deleting the open chat navigates back to the list,
 * the desktop's deselect-on-delete.
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
    <DialogCard label="Delete session?" onClose={onClose}>
      <h2 className="dialog-title">Delete session?</h2>
      <p className="dialog-body">“{title}” will be permanently deleted. This can’t be undone.</p>
      <div className="dialog-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>
          Cancel
        </button>
        <button type="button" className="btn btn-danger-ghost" onClick={confirm}>
          Delete
        </button>
      </div>
    </DialogCard>
  );
}
