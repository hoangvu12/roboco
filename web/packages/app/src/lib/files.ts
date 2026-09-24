import type {
  WorkspaceFileText,
  WorkspaceLineEnding,
  WorkspaceReadOnlyReason,
  WorkspaceTextEncoding,
  WorkspaceWritableEncoding,
  WorkspaceWritableLineEnding,
} from "@roboco/proto";

/**
 * Pure helpers for the files surface, ported from the desktop's files model
 * (crates/ui/src/files/) so the web tree reads identically: same path/name
 * math on workspace-relative "/" paths, same read-only copy. (The entry
 * ordering and parent-path helpers died with lib/file-tree.ts and
 * lib/file-search-tree.ts — ticket 06's library adapters own that math now.)
 */

/** The final path component. */
export function fileName(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash >= 0 ? path.slice(slash + 1) : path;
}

/** Desktop `is_image` (image_preview.rs): extensions that get an image preview. */
export function isImagePath(path: string): boolean {
  const extension = extensionOf(path);
  return (
    extension !== null &&
    ["png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tif", "tiff"].includes(extension)
  );
}

/** Desktop `is_markdown` (markdown_preview.rs): extensions that get a markdown preview. */
export function isMarkdownPath(path: string): boolean {
  const extension = extensionOf(path);
  return extension === "md" || extension === "markdown";
}

function extensionOf(path: string): string | null {
  const name = fileName(path);
  const dot = name.lastIndexOf(".");
  if (dot <= 0) {
    return null;
  }
  return name.slice(dot + 1).toLowerCase();
}

/** Desktop `read_only_message` (preview.rs), verbatim copy. */
export function readOnlyMessage(reason: WorkspaceReadOnlyReason | null): string {
  switch (reason) {
    case "binary":
      return "Binary files cannot be previewed.";
    case "unsupportedEncoding":
      return "This file encoding is not supported.";
    case "symlink":
      return "Symlink targets are read-only.";
    case "permissionDenied":
      return "Permission denied.";
    case "tooLarge":
      return "This file is too large to preview.";
    case "mixedLineEndings":
      return "Files with mixed line endings are read-only.";
    case "notRegularFile":
    case null:
      return "This file cannot be previewed.";
  }
}

/**
 * The truncated-preview banner (preview.rs:2766-2779): shown above the code
 * scroll when text came back but the read was clipped — reachable today via
 * the markdown client-side 2 MiB clip (the server's own truncation always
 * pairs with no text).
 */
export function truncatedMessage(file: WorkspaceFileText): string | null {
  return file.truncated && file.text != null ? "Large file preview is truncated and read-only." : null;
}

/** Desktop document.rs `writable_encoding`. */
export function writableEncoding(encoding: WorkspaceTextEncoding): WorkspaceWritableEncoding | null {
  switch (encoding) {
    case "utf8":
      return "utf8";
    case "utf8Bom":
      return "utf8Bom";
    case "binary":
    case "unsupported":
      return null;
  }
}

/** Desktop document.rs `writable_line_ending`. */
export function writableLineEnding(
  lineEnding: WorkspaceLineEnding | null | undefined,
): WorkspaceWritableLineEnding | null {
  switch (lineEnding) {
    case "lf":
    case "none":
      return "lf";
    case "crlf":
      return "crlf";
    case "mixed":
    case null:
    case undefined:
      return null;
  }
}

/**
 * Desktop document.rs `read_only_reason`: the engine's explicit reason wins;
 * anything undecodable as an editable snapshot (truncated, no text, no hash,
 * non-writable encoding or line endings) degrades to notRegularFile.
 */
export function fileReadOnlyReason(file: WorkspaceFileText): WorkspaceReadOnlyReason | null {
  if (file.readOnlyReason !== null && file.readOnlyReason !== undefined) {
    return file.readOnlyReason;
  }
  const undecodable =
    file.truncated ||
    file.text === null ||
    file.text === undefined ||
    file.contentHash === null ||
    file.contentHash === undefined ||
    writableEncoding(file.encoding) === null ||
    writableLineEnding(file.lineEnding) === null;
  return undecodable ? "notRegularFile" : null;
}
