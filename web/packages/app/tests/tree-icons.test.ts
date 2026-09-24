import { describe, expect, it } from "vitest";
import {
  FILE_TREE_DENSITY,
  FILE_TREE_ROW_HEIGHT,
  fileIconSpriteSheet,
  hasSpecificFileIcon,
  resolveStandaloneFileIcon,
  standaloneDirectoryIcon,
  treeFileIcons,
  wellBg,
} from "../src/lib/tree-icons";

/**
 * The icon seam on the trees library's built-in set — the replacement for
 * the ported manifest's suite: the resolution order, the compound-extension
 * rule, the targeted remaps, and `hasSpecificFileIcon`'s stricter check.
 */

describe("resolveStandaloneFileIcon (the shared resolution order)", () => {
  it("resolves built-in language and type icons by basename and extension", () => {
    expect(resolveStandaloneFileIcon("main.rs")).toEqual({ name: "file-tree-builtin-rust", token: "rust" });
    expect(resolveStandaloneFileIcon("index.tsx")).toEqual({ name: "file-tree-builtin-react", token: "react" });
    expect(resolveStandaloneFileIcon("app.css")).toEqual({ name: "file-tree-builtin-css", token: "css" });
    expect(resolveStandaloneFileIcon("README.md")).toEqual({ name: "file-tree-builtin-markdown", token: "markdown" });
    expect(resolveStandaloneFileIcon("package.json")).toEqual({ name: "file-tree-builtin-json", token: "json" });
    expect(resolveStandaloneFileIcon("Dockerfile")).toEqual({ name: "file-tree-builtin-docker", token: "docker" });
  });

  it("resolves from a path, not just a name — basenames win", () => {
    expect(resolveStandaloneFileIcon("crates/ui/src/main.rs").name).toBe("file-tree-builtin-rust");
  });

  it("the compound extension wins: spec.ts over ts, tsx over ts", () => {
    // No spec.ts remap here (targeted only): ts applies.
    expect(resolveStandaloneFileIcon("a.spec.ts").token).toBe("typescript");
    expect(resolveStandaloneFileIcon("component.tsx").token).toBe("react");
  });

  it("the targeted remaps cover the names the complete set lacks", () => {
    expect(resolveStandaloneFileIcon("Cargo.toml").name).toBe("file-tree-builtin-rust");
    expect(resolveStandaloneFileIcon("Cargo.lock").name).toBe("file-tree-icon-lock");
    expect(resolveStandaloneFileIcon("pnpm-lock.yaml").name).toBe("file-tree-icon-lock");
    expect(resolveStandaloneFileIcon("conf.toml").name).toBe("file-tree-builtin-text");
    expect(resolveStandaloneFileIcon("Makefile").name).toBe("file-tree-builtin-text");
    // The load-more marker row's affordance glyph.
    expect(resolveStandaloneFileIcon("Load more…").name).toBe("file-tree-icon-ellipsis");
  });

  it("unknown names fall back to the generic file glyph", () => {
    expect(resolveStandaloneFileIcon("unknown.unrecognized")).toEqual({ name: "file-tree-builtin-default", token: "default" });
  });
});

describe("standaloneDirectoryIcon", () => {
  it("the folder glyph from the document sprite", () => {
    expect(standaloneDirectoryIcon()).toEqual({ name: "file-tree-icon-folder" });
  });
});

describe("hasSpecificFileIcon (the prose-renderer check)", () => {
  it("true only when resolution would not return the generic glyph", () => {
    expect(hasSpecificFileIcon("main.ts")).toBe(true);
    expect(hasSpecificFileIcon("lib.rs")).toBe(true);
    expect(hasSpecificFileIcon("Makefile")).toBe(true);
    expect(hasSpecificFileIcon("unknown.unrecognized")).toBe(false);
    expect(hasSpecificFileIcon("no-extension")).toBe(false);
  });
});

describe("fileIconSpriteSheet (the document-level sprite)", () => {
  it("carries the complete set plus the custom folder glyph", () => {
    const sheet = fileIconSpriteSheet();
    expect(sheet).toContain('<symbol id="file-tree-builtin-rust"');
    expect(sheet).toContain('<symbol id="file-tree-builtin-typescript"');
    expect(sheet).toContain('<symbol id="file-tree-icon-lock"');
    expect(sheet).toContain('<symbol id="file-tree-icon-folder"');
    expect(sheet).toContain('<symbol id="file-tree-icon-chevron"');
  });
});

describe("treeFileIcons (the tree's icon configuration)", () => {
  it("uses the complete colored set with the targeted remaps", () => {
    expect(treeFileIcons).toMatchObject({ set: "complete", colored: true });
  });
});

describe("density (the old row metrics)", () => {
  it("27px rows at the matching density factor", () => {
    expect(FILE_TREE_ROW_HEIGHT).toBe(27);
    expect(FILE_TREE_DENSITY).toBe(0.9);
  });
});

describe("wellBg (kept verbatim from the old seam)", () => {
  it("light and dark, plain and frosted", () => {
    expect(wellBg("light", false)).toBe("rgb(255 255 255 / 0.16)");
    expect(wellBg("light", true)).toBe("rgb(255 255 255 / 0.32)");
    expect(wellBg("dark", false)).toBe("rgb(0 0 0 / 0.16)");
    expect(wellBg("dark", true)).toBe("rgb(0 0 0 / 0.32)");
  });
});
