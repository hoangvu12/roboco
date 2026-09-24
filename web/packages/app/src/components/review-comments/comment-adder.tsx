/**
 * The diff line's comment adder — `comment_ui.rs::render_comment_adder`
 * (:18-46): a 16px solid square with an 11px plus. Superseded as the live
 * affordance: ticket 03 offers the adder through the diffs library's
 * built-in gutter utility (`enableGutterUtility` + `onGutterUtilityClick`,
 * routes/changes-page.tsx), so this component is no longer mounted. The
 * `.diff-adder-slot` row positioning and `comment_adder_left`
 * (lib/diff.ts) it used died with the old row renderer (ticket 04).
 */

import type { MouseEvent as ReactMouseEvent } from "react";
import { Icon } from "@roboco/icons";

export interface CommentAdderProps {
  /** `open_draft` (changes.rs:2745) — the click target's `(path, side, line)`. */
  readonly onOpen: () => void;
}

export function CommentAdder({ onOpen }: CommentAdderProps) {
  return (
    <button
      type="button"
      className="comment-adder"
      aria-label="Add comment"
      onMouseDown={(event: ReactMouseEvent<HTMLButtonElement>) => event.stopPropagation()}
      onClick={(event: ReactMouseEvent<HTMLButtonElement>) => {
        // Must not steal the row's own hover/click handling
        // (`cx.stop_propagation`, comment_ui.rs:35-39).
        event.stopPropagation();
        onOpen();
      }}
    >
      <Icon name="plus" size={11} />
    </button>
  );
}
