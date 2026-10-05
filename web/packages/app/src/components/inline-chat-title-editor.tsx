import { useEffect, useRef, useState } from "react";
import { describeMutateError, renameChat, type MutateCaller } from "../lib/chat-actions";
import { sidebarNotice } from "../state/notice";

/**
 * The inline chat-title editor — the web peer of the desktop's
 * `chat_title_editor` (shell.rs): the row title's place, taken by a
 * single-line field that opens with the whole name selected. Enter or blur
 * commits a changed, non-empty title; Escape drops the edit; an unchanged
 * or empty title never fires the mutation.
 *
 * The mutation routes through the caller's session (`MutateCaller`) — the
 * same `renameChat` the old `RenameChatDialog` used; failures surface in
 * the sidebar notice strip.
 */
export function InlineChatTitleEditor({
  chatId,
  initial,
  caller,
  className,
  onDone,
}: {
  /** The chat being renamed (the Mutate call's target id). */
  readonly chatId: string;
  /** The current title — the field's seed. */
  readonly initial: string;
  /** The owning engine's client, or null when the engine is gone. */
  readonly caller: MutateCaller | null;
  /** The row's editor class, carrying the slot's type sizing and ring. */
  readonly className: string;
  /** Called once the edit ends (commit or cancel). */
  readonly onDone: () => void;
}) {
  const [title, setTitle] = useState(initial);
  const inputRef = useRef<HTMLInputElement | null>(null);
  // A commit already ended this edit; the following blur must not re-fire.
  const done = useRef(false);

  useEffect(() => {
    const input = inputRef.current;
    if (input === null) {
      return;
    }
    input.focus();
    input.select();
  }, []);

  function commit(): void {
    if (done.current) {
      return;
    }
    done.current = true;
    const trimmed = title.trim();
    if (trimmed !== "" && trimmed !== initial) {
      if (caller === null) {
        sidebarNotice.set("Engine not connected");
      } else {
        renameChat(caller, chatId, trimmed).catch((error: unknown) => {
          sidebarNotice.set(describeMutateError(error));
        });
      }
    }
    onDone();
  }

  function cancel(): void {
    if (done.current) {
      return;
    }
    done.current = true;
    onDone();
  }

  return (
    <input
      ref={inputRef}
      // The field is not the row: clicks place the caret instead of
      // opening the chat or starting a drag.
      className={className}}
      type="text"
      aria-label="Session title"
      value={title}
      spellCheck={false}
      onChange={(event) => setTitle(event.target.value)}
      onMouseDown={(event) => event.stopPropagation()}
      onClick={(event) => event.stopPropagation()}
      onDoubleClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          commit();
        } else if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          cancel();
        }
      }}
      onBlur={commit}
    />
  );
}
