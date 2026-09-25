import { describe, expect, it } from "vitest";
import { documentFileContents } from "../src/lib/file-view";
import type { FileDocumentSnapshot } from "../src/lib/file-document";
import type { WorkspaceFileText } from "@roboco/proto";

/**
 * The file viewer's pure adapter (web-pierre-adoption, ticket 05): a
 * file-document read outcome → the diffs library's `FileContents`. The
 * library's contract under test: the basename feeds the header label and
 * language inference, the buffer is the contents, and the read's content
 * hash is the highlight cache key.
 */

function file(fields: Partial<WorkspaceFileText> = {}): WorkspaceFileText {
  return {
    checkoutId: "co-1",
    path: "src/main.rs",
    text: "fn main() {}",
    contentHash: "hash-1",
    size: 12,
    encoding: "utf8",
    lineEnding: "lf",
    truncated: false,
    ...fields,
  };
}

function snapshot(read: WorkspaceFileText | null, text: string): FileDocumentSnapshot {
  return {
    phase: { kind: "ready" },
    text,
    file: read,
    editable: true,
    dirty: false,
    showMarkdown: false,
  };
}

describe("documentFileContents", () => {
  it("maps the basename, the buffer, and the content hash as the cache key", () => {
    const read = file();
    const contents = documentFileContents("src/main.rs", snapshot(read, "fn main() {}"));
    expect(contents.name).toBe("main.rs");
    expect(contents.contents).toBe("fn main() {}");
    expect(contents.cacheKey).toBe("hash-1");
    expect(contents.lang).toBeUndefined();
  });

  it("the basename is the final path component — deep paths keep their extension for language inference", () => {
    const contents = documentFileContents("web/packages/app/src/lib/code.ts", snapshot(file(), "export {}"));
    expect(contents.name).toBe("code.ts");
  });

  it("the buffer is the document's text, whatever the phase carries on disk", () => {
    // The state machine owns staleness (the banners); the adapter only feeds
    // the view the document's current text.
    const contents = documentFileContents("src/main.rs", snapshot(file(), "edited buffer"));
    expect(contents.contents).toBe("edited buffer");
  });

  it("a missing content hash leaves the cache key unset — contents identity drives re-renders", () => {
    const contents = documentFileContents(
      "src/main.rs",
      snapshot(file({ contentHash: null }), "fn main() {}"),
    );
    expect(contents.cacheKey).toBeUndefined();
  });

  it("no file read (pre-load) maps to an empty body with no cache key", () => {
    const contents = documentFileContents("src/main.rs", snapshot(null, ""));
    expect(contents).toEqual({ name: "main.rs", contents: "", cacheKey: undefined });
  });
});
