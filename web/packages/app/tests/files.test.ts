import { describe, expect, it } from "vitest";
import type { WorkspaceFileText } from "@roboco/proto";
import {
  fileReadOnlyReason,
  isImagePath,
  isMarkdownPath,
  readOnlyMessage,
  truncatedMessage,
  writableEncoding,
  writableLineEnding,
} from "../src/lib/files";

/**
 * The entry-order and path-math suites (compareEntries, parentPath,
 * isDirectChild, formatBytes) were deleted with their exports — ticket 06's
 * library adapters (lib/tree-adapters.ts) own that math now.
 */

describe("preview classification", () => {
  it("detects images by extension, case-insensitively", () => {
    expect(isImagePath("assets/logo.PNG")).toBe(true);
    expect(isImagePath("docs/photo.jpeg")).toBe(true);
    expect(isImagePath("src/main.rs")).toBe(false);
    expect(isImagePath("no-extension")).toBe(false);
  });

  it("detects markdown like the desktop (md, markdown)", () => {
    expect(isMarkdownPath("README.md")).toBe(true);
    expect(isMarkdownPath("notes.MARKDOWN")).toBe(true);
    expect(isMarkdownPath("src/md.rs")).toBe(false);
  });
});

describe("read-only copy (desktop read_only_message)", () => {
  it("carries the desktop's exact messages", () => {
    expect(readOnlyMessage("binary")).toBe("Binary files cannot be previewed.");
    expect(readOnlyMessage("unsupportedEncoding")).toBe("This file encoding is not supported.");
    expect(readOnlyMessage("symlink")).toBe("Symlink targets are read-only.");
    expect(readOnlyMessage("permissionDenied")).toBe("Permission denied.");
    expect(readOnlyMessage("tooLarge")).toBe("This file is too large to preview.");
    expect(readOnlyMessage("mixedLineEndings")).toBe("Files with mixed line endings are read-only.");
    expect(readOnlyMessage("notRegularFile")).toBe("This file cannot be previewed.");
    expect(readOnlyMessage(null)).toBe("This file cannot be previewed.");
  });
});

describe("writable shape derivation (desktop document.rs)", () => {
  it("maps encodings", () => {
    expect(writableEncoding("utf8")).toBe("utf8");
    expect(writableEncoding("utf8Bom")).toBe("utf8Bom");
    expect(writableEncoding("binary")).toBeNull();
    expect(writableEncoding("unsupported")).toBeNull();
  });

  it("maps line endings", () => {
    expect(writableLineEnding("lf")).toBe("lf");
    expect(writableLineEnding("none")).toBe("lf");
    expect(writableLineEnding("crlf")).toBe("crlf");
    expect(writableLineEnding("mixed")).toBeNull();
    expect(writableLineEnding(null)).toBeNull();
    expect(writableLineEnding(undefined)).toBeNull();
  });
});

function textFile(fields: Partial<WorkspaceFileText>): WorkspaceFileText {
  return {
    checkoutId: "checkout-1",
    path: "src/lib.rs",
    text: "fn main() {}",
    contentHash: "hash-1",
    size: 12,
    encoding: "utf8",
    lineEnding: "lf",
    truncated: false,
    ...fields,
  };
}

describe("fileReadOnlyReason", () => {
  it("prefers the engine's explicit reason", () => {
    expect(fileReadOnlyReason(textFile({ readOnlyReason: "symlink" }))).toBe("symlink");
  });

  it("treats truncated, textless, hashless, and mixed-ending files as notRegularFile", () => {
    expect(fileReadOnlyReason(textFile({ truncated: true }))).toBe("notRegularFile");
    expect(fileReadOnlyReason(textFile({ text: null }))).toBe("notRegularFile");
    expect(fileReadOnlyReason(textFile({ contentHash: null }))).toBe("notRegularFile");
    expect(fileReadOnlyReason(textFile({ lineEnding: "mixed" }))).toBe("notRegularFile");
    expect(fileReadOnlyReason(textFile({ encoding: "binary" }))).toBe("notRegularFile");
  });

  it("accepts an ordinary editable snapshot", () => {
    expect(fileReadOnlyReason(textFile({}))).toBeNull();
    expect(fileReadOnlyReason(textFile({ encoding: "utf8Bom", lineEnding: "crlf" }))).toBeNull();
  });
});

describe("truncatedMessage", () => {
  it("banners truncated text reads and stays quiet otherwise", () => {
    expect(truncatedMessage(textFile({ truncated: true }))).toBe(
      "Large file preview is truncated and read-only.",
    );
    expect(truncatedMessage(textFile({}))).toBeNull();
    expect(truncatedMessage(textFile({ truncated: true, text: null }))).toBeNull();
  });
});
