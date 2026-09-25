// @vitest-environment jsdom

/**
 * Ticket 06 — the standalone `FileIcon` on the trees library's built-in
 * set: the document-level sprite (the complete set plus the folder glyph)
 * is injected once, and icons resolve through the shared configuration and
 * render `<use>` references whose symbols actually exist in the sprite.
 */

import { act } from "react";
import { createRoot } from "react-dom/client";
import { createElement } from "react";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { FileIcon } from "../src/components/files/file-icon";

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

afterEach(() => {
  document.body.replaceChildren();
});

describe("FileIcon on the trees built-in set", () => {
  it("injects the sprite once and renders use references to existing symbols", () => {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container);
    act(() => {
      root.render(
        createElement(
          "div",
          null,
          createElement(FileIcon, { kind: "file", name: "main.rs", size: 14 }),
          createElement(FileIcon, { kind: "directory", name: "src", size: 14 }),
          createElement(FileIcon, { kind: "symlink", name: "link.ts", size: 14 }),
        ),
      );
    });
    const sprite = document.getElementById("roboco-file-icons-sprite");
    expect(sprite).not.toBeNull();
    const uses = Array.from(container.querySelectorAll("svg use")).map((use) => use.getAttribute("href"));
    expect(uses).toEqual(["#file-tree-builtin-rust", "#file-tree-icon-folder", "#file-tree-builtin-typescript"]);
    // Every referenced symbol exists in the injected sprite.
    for (const use of uses) {
      expect(sprite!.innerHTML).toContain(`<symbol id="${use!.slice(1)}"`);
    }
    act(() => {
      root.unmount();
    });
  });
});
