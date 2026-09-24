import type { FileContents } from "@pierre/diffs";
import type { FileDocumentSnapshot } from "./file-document";
import { fileName } from "./files";

/**
 * The file viewer's adapter onto the diffs library's `FileContents`
 * (web-pierre-adoption, ticket 05) — the "file-document state → file
 * contents" seam from the spec's testing decisions.
 *
 * The read path is the ONLY path for now: ADR 0008 suspends web file
 * editing until ticket 07 bridges the library's edit mode onto the
 * document machinery, so the viewer renders the document's current text
 * read-only (no `edit`, no `editStateKey`, no `onEditChange`/
 * `onEditComplete` — those are 07's bridge). Inputs:
 *
 * - `name` — the file's basename: the library's header label and its
 *   language inference (markdown paths infer `markdown` from the
 *   extension, the same language the old tokenizer forced).
 * - `contents` — the document buffer (the last read text; with editing
 *   suspended it never diverges from disk except across the banner
 *   phases the state machine already owns).
 * - `cacheKey` — the read's content hash: the library's highlight-cache
 *   key, reference-stable across re-renders so tab switches and
 *   unrelated re-renders never re-tokenize settled content.
 */

export function documentFileContents(path: string, snapshot: FileDocumentSnapshot): FileContents {
  return {
    name: fileName(path),
    contents: snapshot.text,
    cacheKey: snapshot.file?.contentHash ?? undefined,
  };
}
