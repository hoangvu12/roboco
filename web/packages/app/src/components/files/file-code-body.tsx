import { useCallback, useMemo } from "react";
import { File as LibraryFile, Virtualizer, type FileEditChangeHandler, type FileEditCompleteHandler, type FileOptions } from "@pierre/diffs/react";
import type { FileContents } from "@pierre/diffs";
import type { FileDocument, FileDocumentSnapshot } from "../../lib/file-document";
import { documentFileContents } from "../../lib/file-view";
import { applyFileEditChange, completeFileEdit, fileEditStateKey, reconcileFileEditDraft } from "../../lib/file-edit";
import { robocoDiffsThemes } from "../../lib/pierre-theme";
import { useResolvedAppearance } from "../../state/appearance";
import { previewLineHeight, previewTextSize } from "../../lib/typography";

/**
 * The code body (web-pierre-adoption, ticket 05 read-only, ticket 07 edit
 * mode): the diffs library’s virtualized `File` inside a `Virtualizer`
 * scroll container — highlighted by the registered Roboco theme pair
 * (themeType following the resolved appearance), line numbers on, word
 * wrap per the setting, the content hash as the highlight cache key. The
 * library renders in shadow DOM; our host class (`.files-code-host`,
 * app.css) owns the layout and the `--diffs-*` token mapping exactly like
 * the Changes pane’s host.
 *
 * Editable documents additionally run the library’s edit session (the
 * `EditProvider` the app shell mounts supplies the editor factory): the
 * per-file `editStateKey` retains the draft, undo history, selection, and
 * caret across unmount/remount; `onEditChange` streams the live contents
 * into the document (`lib/file-edit.ts`); `onEditComplete` is always
 * present — it accepts the session’s final contents and runs the final
 * save, because a missing handler silently rejects them. Read-only
 * documents (truncated, binary, unwritable encoding) never pass `edit`.
 *
 * This module is the viewer's LAZY entry: the file viewer
 * (`components/files/file-viewer.tsx`) mounts it through `React.lazy` +
 * `Suspense`, so the library's File rendering machinery loads on first
 * code-body mount (a brief "Loading file…" fallback — the viewer's own
 * loading arm) instead of riding the main bundle.
 */
export function FileCodeBody({
  path,
  doc,
  snapshot,
  wordWrap,
  codeFontSize,
}: {
  readonly path: string;
  readonly doc: FileDocument | null;
  readonly snapshot: FileDocumentSnapshot;
  readonly wordWrap: boolean;
  readonly codeFontSize: number;
}) {
  const appearance = useResolvedAppearance();
  const file = useMemo<FileContents>(() => documentFileContents(path, snapshot), [path, snapshot]);
  const options = useMemo<FileOptions<undefined, undefined>>(
    () => ({
      theme: robocoDiffsThemes(),
      themeType: appearance,
      overflow: wordWrap ? "wrap" : "scroll",
      stickyHeader: true,
    }),
    [appearance, wordWrap],
  );
  const editable = snapshot.editable;
  // The per-file edit state key — computed before the editor attaches so a
  // retained draft that no longer matches this document’s buffer (a reload
  // moved it) is dropped instead of resuming over fresh contents.
  const editStateKey = useMemo(() => {
    const key = fileEditStateKey(path);
    if (editable) {
      reconcileFileEditDraft(key, snapshot.text);
    }
    return key;
  }, [path, editable, snapshot.text]);
  const onEditChange = useCallback<FileEditChangeHandler<undefined, undefined>>(
    (event) => {
      if (doc !== null) {
        applyFileEditChange(doc, event);
      }
    },
    [doc],
  );
  // Mandatory in every mount path: a missing completion handler REJECTS
  // the session’s final contents (silent data loss).
  const onEditComplete = useCallback<FileEditCompleteHandler<undefined, undefined>>((event) => {
    if (doc !== null) {
      return completeFileEdit(doc, event);
    }
    return "accept";
  }, [doc]);
  return (
    <Virtualizer className="files-code-host">
      <LibraryFile
        className="files-code-file"
        style={{
          ["--diffs-font-size" as string]: `${previewTextSize(codeFontSize)}px`,
          ["--diffs-line-height" as string]: `${previewLineHeight(codeFontSize)}px`,
        }}
        file={file}
        options={options}
        edit={editable}
        editStateKey={editable ? editStateKey : undefined}
        onEditChange={onEditChange}
        onEditComplete={onEditComplete}
      />
    </Virtualizer>
  );
}

// The viewer's `React.lazy(() => import("./file-code-body"))` resolves this
// default; the named export stays for direct (non-lazy) test use.
export default FileCodeBody;
