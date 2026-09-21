import { describe, expect, it } from "vitest";
import { markdownLinkTarget, parseMarkdown, resolveWorkspacePath } from "../src/lib/markdown-doc";

describe("parseMarkdown blocks", () => {
  it("parses ATX headings and paragraphs", () => {
    const blocks = parseMarkdown("# Title\n\nSome text here.\n\n## Sub");
    expect(blocks).toEqual([
      { kind: "heading", level: 1, inlines: [{ kind: "text", text: "Title" }] },
      { kind: "paragraph", inlines: [{ kind: "text", text: "Some text here." }] },
      { kind: "heading", level: 2, inlines: [{ kind: "text", text: "Sub" }] },
    ]);
  });

  it("parses setext headings", () => {
    const blocks = parseMarkdown("Title\n=====\n\nSub\n---");
    expect(blocks).toEqual([
      { kind: "heading", level: 1, inlines: [{ kind: "text", text: "Title" }] },
      { kind: "heading", level: 2, inlines: [{ kind: "text", text: "Sub" }] },
    ]);
  });

  it("parses fenced code blocks with a language", () => {
    const blocks = parseMarkdown("```rust\nfn main() {}\n```\n\nafter");
    expect(blocks).toEqual([
      { kind: "code", language: "rust", text: "fn main() {}" },
      { kind: "paragraph", inlines: [{ kind: "text", text: "after" }] },
    ]);
  });

  it("keeps an unclosed fence as code until end of input", () => {
    const blocks = parseMarkdown("```\nlet x = 1;");
    expect(blocks).toEqual([{ kind: "code", language: null, text: "let x = 1;" }]);
  });

  it("parses horizontal rules", () => {
    expect(parseMarkdown("---")).toEqual([{ kind: "rule" }]);
    expect(parseMarkdown("***")).toEqual([{ kind: "rule" }]);
  });

  it("parses unordered and ordered lists", () => {
    const blocks = parseMarkdown("- one\n- two\n\n1. first\n2. second");
    expect(blocks).toEqual([
      {
        kind: "list",
        ordered: false,
        items: [
          [{ kind: "paragraph", inlines: [{ kind: "text", text: "one" }] }],
          [{ kind: "paragraph", inlines: [{ kind: "text", text: "two" }] }],
        ],
      },
      {
        kind: "list",
        ordered: true,
        items: [
          [{ kind: "paragraph", inlines: [{ kind: "text", text: "first" }] }],
          [{ kind: "paragraph", inlines: [{ kind: "text", text: "second" }] }],
        ],
      },
    ]);
  });

  it("nests indented lists", () => {
    const blocks = parseMarkdown("- outer\n  - inner\n- back");
    expect(blocks).toHaveLength(1);
    const list = blocks[0]!;
    expect(list.kind).toBe("list");
    if (list.kind !== "list") {
      return;
    }
    expect(list.items).toHaveLength(2);
    expect(list.items[0]!.some((block) => block.kind === "list")).toBe(true);
  });

  it("parses block quotes recursively", () => {
    const blocks = parseMarkdown("> # Quoted\n> text");
    expect(blocks).toEqual([
      {
        kind: "quote",
        blocks: [
          { kind: "heading", level: 1, inlines: [{ kind: "text", text: "Quoted" }] },
          { kind: "paragraph", inlines: [{ kind: "text", text: "text" }] },
        ],
      },
    ]);
  });

  it("parses pipe tables", () => {
    const blocks = parseMarkdown("| a | b |\n|---|---|\n| 1 | 2 |");
    expect(blocks).toEqual([
      {
        kind: "table",
        header: [[{ kind: "text", text: "a" }], [{ kind: "text", text: "b" }]],
        rows: [[[{ kind: "text", text: "1" }], [{ kind: "text", text: "2" }]]],
      },
    ]);
  });

  it("a paragraph followed by a table stays a paragraph-plus-table", () => {
    const blocks = parseMarkdown("intro\n\n| h |\n|---|\n| c |");
    expect(blocks.map((block) => block.kind)).toEqual(["paragraph", "table"]);
  });
});

describe("parseMarkdown inlines", () => {
  it("parses code spans, emphasis, and strikethrough", () => {
    const [block] = parseMarkdown("a `code` **bold** *em* ~~gone~~ b");
    expect(block).toEqual({
      kind: "paragraph",
      inlines: [
        { kind: "text", text: "a " },
        { kind: "code", text: "code" },
        { kind: "text", text: " " },
        { kind: "bold", children: [{ kind: "text", text: "bold" }] },
        { kind: "text", text: " " },
        { kind: "italic", children: [{ kind: "text", text: "em" }] },
        { kind: "text", text: " " },
        { kind: "strike", children: [{ kind: "text", text: "gone" }] },
        { kind: "text", text: " b" },
      ],
    });
  });

  it("parses links and images", () => {
    const [block] = parseMarkdown("[docs](guide.md) ![shot](https://x.test/a.png)");
    expect(block).toEqual({
      kind: "paragraph",
      inlines: [
        { kind: "link", href: "guide.md", children: [{ kind: "text", text: "docs" }] },
        { kind: "text", text: " " },
        { kind: "image", src: "https://x.test/a.png", alt: "shot" },
      ],
    });
  });

  it("does not treat emphasis markers mid-word as italic", () => {
    const [block] = parseMarkdown("snake_case_name stays text");
    expect(block).toEqual({ kind: "paragraph", inlines: [{ kind: "text", text: "snake_case_name stays text" }] });
  });
});

describe("markdown link policy", () => {
  it("routes http(s) and mailto externally", () => {
    expect(markdownLinkTarget("https://example.com")).toEqual({ kind: "external", href: "https://example.com" });
    expect(markdownLinkTarget("mailto:a@b.c")).toEqual({ kind: "external", href: "mailto:a@b.c" });
  });

  it("drops unknown schemes", () => {
    expect(markdownLinkTarget("javascript:alert(1)")).toEqual({ kind: "text" });
  });

  it("treats scheme-less targets as workspace paths", () => {
    expect(markdownLinkTarget("src/lib.rs")).toEqual({ kind: "workspace", path: "src/lib.rs" });
    expect(markdownLinkTarget("./guide.md#install")).toEqual({ kind: "workspace", path: "./guide.md" });
  });

  it("resolves workspace links against the open file", () => {
    expect(resolveWorkspacePath("docs/guide.md", "install.md")).toBe("docs/install.md");
    expect(resolveWorkspacePath("docs/guide.md", "../README.md")).toBe("README.md");
    expect(resolveWorkspacePath("README.md", "docs/guide.md")).toBe("docs/guide.md");
  });
});
