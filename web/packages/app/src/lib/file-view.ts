import type { FileContents } from "@pierre/diffs";
import type { FileDocumentSnapshot } from "./file-document";
import { fileName } from "./files";

/**
 * The file viewer's adapter onto the diffs library's `FileContents`
 * (web-pierre-adoption, ticket 05) — the "file-document state → file
 * contents" seam from the spec's testing decisions, shared by the
 * read-only and (ticket 07) editable arms of the code body.
 *
 * Inputs:
 *
 * - `name` — the file's basename: the library's header label and its
 *   language inference (markdown paths infer `markdown` from the
 *   extension, the same language the old tokenizer forced).
 * - `contents` — the document buffer (the last read text, or the live
 *   draft once the edit session streams edits through `document.edit`).
 * - `cacheKey` — the READ's content hash: the library's highlight-cache
 *   key, reference-stable across re-renders so tab switches and
 *   unrelated re-renders never re-tokenize settled content.
 *
 * The cache key doubles as the edit session's file identity (the library
 * compares cache keys when present): while a session is live, edits move
 * the buffer but not the read's hash, so the prop stays "the same file"
 * and the editor's document remains the session's source of truth — only
 * a fresh read (a reload through the document machinery) rotates the hash
 * and lands as an external document replacement, which the editor applies
 * as one undoable whole-document change with the selection remapped.
 */

export function documentFileContents(path: string, snapshot: FileDocumentSnapshot): FileContents {
  return {
    name: fileName(path),
    contents: snapshot.text,
    cacheKey: snapshot.file?.contentHash ?? undefined,
  };
}
