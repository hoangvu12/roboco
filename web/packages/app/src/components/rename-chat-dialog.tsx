import { useRef, useState } from "react";
import type { Chat } from "@roboco/proto";
import { singleLine } from "../lib/view";
import { Dialog, DialogCard, DialogTitle, DialogField, BtnGhost, BtnPrimary } from "./ui/Dialog";

/**
 * The rename-chat dialog (`open_rename_chat` / `submit_rename_chat`):
 * prefilled single-line input, Enter submits, an empty title is a no-op.
 * Escape closes through RbDialog's escape path (`onOpenChange(false)`).
 * Shared by the chat menu's Rename row and the composer's `/rename`
 * workspace command; the submit routing (the Mutate call and its error
 * surface) belongs to the host.
 */
export function RenameChatDialog({
  chat,
  onSubmit,
  onClose,
}: {
  readonly chat: Chat;
  /** Submit the new title; the host owns the mutation and its error notice. */
  readonly onSubmit: (title: string) => void;
  readonly onClose: () => void;
}) {
  const [title, setTitle] = useState(chat.title ?? "");
  const inputRef = useRef<HTMLInputElement | null>(null);

  return (
    <Dialog ariaLabel="Rename session" onClose={onClose} initialFocus={inputRef}>
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
    </Dialog>
  );
}

/** The dialog's title seed: a single line, "New session" when untitled. */
export function renameDialogTitle(chat: Chat): string {
  return singleLine(chat.title ?? "") || "New session";
}
