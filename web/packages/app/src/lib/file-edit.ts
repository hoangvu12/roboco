import { Editor, EditStateManager } from "@pierre/diffs/edit";
import type {
  EditCompletionDecision,
  EditorChangeEvent,
  EditorFactory,
  EditorOptions,
  EditorType,
  FileEditCompleteEvent,
} from "@pierre/diffs/edit";
import type { FileDocument } from "./file-document";

/**
 * The edit-mode bridge onto the file-document machinery
 * (web-pierre-adoption, ticket 07) — the seam the research doc's §7 sketch
 * sanctioned: one thin module owns EVERYTHING the library's edit mode needs
 * from the host, and the FileDocument save/autosave/conflict machinery
 * stays untouched underneath it.
 *
 * Ownership split:
 *
 * - The library owns the editing surface (undo history, selection, caret,
 *   keymaps, bracket matching, auto-surround) inside its shadow DOM; its
 *   `edit` session is per `editStateKey`, retained in memory across
 *   unmount/remount (the library's `EditStateManager` LRU).
 * - The document owns the buffer and every write: `onEditChange` is the
 *   live change stream feeding `FileDocument.edit` (dirty + autosave
 *   scheduling — exactly the role the deleted textarea's onChange played),
 *   and `onEditComplete` is the accept/reject boundary at session end —
 *   it ALWAYS accepts and runs the final save through the existing
 *   machinery, because a missing handler (or a reject) silently discards
 *   the session's final contents.
 *
 * The event contents and the document buffer stay in lockstep: every editor
 * change (including the library's own external-document replacements) is
 * published through one change stream, and the bridge skips events that
 * carry contents the document already holds — a no-op feed would dirty a
 * clean document (an external reload must not read as a user edit).
 */

/**
 * The shared editor factory the app's `EditProvider` mounts high in the
 * component tree (one provider above the right-pane surfaces; the factory
 * is shared context, not per-file). Roboco's shared default: the viewer's
 * editor owns its scroll view (the `Virtualizer` hosts exactly one file),
 * so its vertical viewport is retained and restored across suspensions.
 * Everything else is the library's defaults — no keymap or feature
 * customization (edit mode is Beta; the seam stays thin).
 */
export const createRobocoFileEditor: EditorFactory<unknown, unknown> = <EType extends EditorType>(
  editorType: EType,
  options: EditorOptions<EType, unknown, unknown>,
  editStateKey?: string,
) => new Editor(editorType, { ...options, ownsVerticalViewport: true }, editStateKey);

/**
 * The per-file edit state key — the document path: undo history, selection,
 * and caret survive leaving and re-entering the file within a session
 * (tab switches unmount the surface; the library retains the keyed draft).
 */
export function fileEditStateKey(path: string): string {
  return path;
}

/**
 * Reconcile the retained draft with the mounting document's buffer BEFORE
 * the editor attaches: a dormant draft resumes only when it still matches
 * the buffer. A reload (or another surface's document for the same path)
 * moved the buffer underneath the retained draft, and resuming it would
 * show stale contents over a document that believes otherwise — so the
 * divergent draft is dropped and the session starts fresh from the buffer.
 * Active sessions are never cleared (the manager refuses), which keeps
 * this safe to call on every recompute.
 */
export function reconcileFileEditDraft(key: string, buffer: string): void {
  const session = EditStateManager.get("file", key);
  if (session?.document === undefined) {
    return;
  }
  if (session.document.getText() === buffer) {
    return;
  }
  EditStateManager.clear("file", key);
}

/**
 * `onEditChange` — the live change stream: the event's file carries the
 * editor's new contents; feed them to the document's edit path (dirty +
 * autosave scheduling). Contents the document already holds are skipped —
 * the library republishes external replacements through the same stream,
 * and re-feeding a synced buffer would mark a clean document dirty.
 */
export function applyFileEditChange<LAnnotation, Caret>(
  document: FileDocument,
  event: EditorChangeEvent<"file", LAnnotation, Caret>,
): void {
  const contents = event.file.contents;
  if (contents === document.getSnapshot().text) {
    return;
  }
  document.edit(contents);
}

/**
 * `onEditComplete` — the session-end boundary (tab close, edit disabled,
 * unmount): accept the completed contents and run the final save through
 * the existing machinery. The decision is ALWAYS `'accept'` — a missing
 * handler (or a reject) silently discards the session's final contents,
 * and the document already holds them via the change stream. The save is
 * the machinery's own capable-save flush (`prepareClose`'s pending path
 * does the same): documents that cannot save — a write already in flight,
 * or a phase the state machine reserves (conflict, externally modified,
 * deleted on disk) — keep their buffer and their banner instead.
 */
export function completeFileEdit<LAnnotation, Caret>(
  document: FileDocument,
  event: FileEditCompleteEvent<LAnnotation, Caret>,
): EditCompletionDecision {
  // The stream keeps the document current; a completion that somehow
  // carries contents the document never saw syncs them before accepting.
  if (event.file.contents !== document.getSnapshot().text) {
    document.edit(event.file.contents);
  }
  if (document.canSave()) {
    document.save();
  }
  return "accept";
}
