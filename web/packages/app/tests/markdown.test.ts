import { describe, expect, it } from "vitest";
import { closeHanging, parseMarkdown, PENDING_LINK_URL, type Block } from "../src/lib/markdown";

/**
 * The mend cases are ported verbatim from crates/ui/src/markdown/mend.rs —
 * the web client mends streaming display text exactly like the desktop.
 */
describe("closeHanging", () => {
  const mends = (input: string, expected: string): void => {
    expect(closeHanging(input), JSON.stringify(input)).toBe(expected);
  };
  const stays = (input: string): void => {
    expect(closeHanging(input), JSON.stringify(input)).toBeNull();
  };

  it("balanced text needs nothing", () => {
    stays("plain words, no markers");
    stays("a **b** and *c* and `d` and ~~e~~");
    stays("[docs](https://x.dev) done");
    stays("");
  });

  it("bold and italic close", () => {
    mends("**bold", "**bold**");
    mends("some *em", "some *em*");
    mends("a __b", "a __b__");
    mends("a _b", "a _b_");
    mends("***both", "***both***");
  });

  it("half-streamed closers complete", () => {
    mends("**bold*", "**bold**");
    mends("__b_", "__b__");
    mends("~~gone~", "~~gone~~");
  });

  it("nested closers come innermost first", () => {
    mends("**a *b", "**a *b***");
    mends("*a **b", "*a **b***");
    mends("_a **b", "_a **b**_");
  });

  it("bare openers stay literal until content", () => {
    stays("**");
    stays("text **");
    stays("text ** ");
    stays("*");
    stays("~~");
    stays("`");
  });

  it("closers insert before trailing whitespace", () => {
    mends("**bold ", "**bold** ");
    mends("*em\n", "*em*\n");
  });

  it("intraword and escapes are literal", () => {
    stays("2*3 equals 6");
    stays("snake_case_name");
    stays("20~25 degrees");
    stays("\\*not emphasis");
    stays("a \\** b");
  });

  it("list markers are not openers", () => {
    stays("* item one");
    stays("- a\n* b");
  });

  it("strikethrough closes", () => {
    mends("~~gone", "~~gone~~");
    stays("~single~x");
  });

  it("inline code closes and shields markers", () => {
    mends("`code", "`code`");
    mends("call `a ** b", "call `a ** b`");
    mends("``a`", "``a```");
    stays("`done` after");
  });

  it("links mend to the pending sentinel", () => {
    mends("[docs](https://x.dev/lo", `[docs](${PENDING_LINK_URL})`);
    mends("[docs](", `[docs](${PENDING_LINK_URL})`);
    mends("see [do", `see [do](${PENDING_LINK_URL})`);
    mends("![alt](https://x/i.p", `![alt](${PENDING_LINK_URL})`);
    stays("see [");
    stays("[x] task-like");
  });

  it("link urls allow nested parens", () => {
    stays("[a](https://x.dev/(y)) done");
    mends("[a](https://x.dev/(y", `[a](${PENDING_LINK_URL})`);
  });

  it("emphasis inside link text closes inside", () => {
    mends("[**a", `[**a**](${PENDING_LINK_URL})`);
    mends("**a [b", `**a [b](${PENDING_LINK_URL})**`);
  });

  it("emphasis unclosed in a completed bracket is dropped", () => {
    stays("[**a] done");
  });

  it("setext partials get a zero-width space", () => {
    const zwsp = "​";
    mends("para\n-", `para\n-${zwsp}`);
    mends("para\n--", `para\n--${zwsp}`);
    mends("para\n=", `para\n=${zwsp}`);
    stays("para\n---");
    stays("-");
    stays("\n-");
    mends("**b\n-", `**b**\n-${zwsp}`);
  });
});

describe("parseMarkdown blocks", () => {
  const kinds = (source: string): string[] => parseMarkdown(source).blocks.map((top) => top.block.kind);

  it("splits paragraphs, headings, fences, quotes, lists, tables, rules", () => {
    const source = [
      "# Title",
      "",
      "Some text.",
      "",
      "```ts",
      "const x = 1;",
      "```",
      "",
      "> quoted",
      "",
      "- one",
      "- two",
      "",
      "| a | b |",
      "|---|---|",
      "| 1 | 2 |",
      "",
      "---",
      "",
      "tail",
    ].join("\n");
    expect(kinds(source)).toEqual([
      "heading",
      "paragraph",
      "codeBlock",
      "blockQuote",
      "list",
      "table",
      "rule",
      "paragraph",
    ]);
  });

  it("an unterminated fence closes at end of input (the streaming case)", () => {
    const tree = parseMarkdown("before\n\n```py\nprint(1)\nprint(2)");
    const blocks = tree.blocks;
    expect(blocks.map((top) => top.block.kind)).toEqual(["paragraph", "codeBlock"]);
    const fence = blocks[1]!.block;
    if (fence.kind !== "codeBlock") {
      throw new Error("expected codeBlock");
    }
    expect(fence.language).toBe("py");
    expect(fence.code).toBe("print(1)\nprint(2)");
  });

  it("records top-level ranges that cover the source", () => {
    const source = "# Hi\n\nsome **text** here\n\n```\ncode\n```";
    const tree = parseMarkdown(source);
    expect(tree.blocks.length).toBe(3);
    for (const top of tree.blocks) {
      expect(source.slice(top.start, top.end).trim().length).toBeGreaterThan(0);
    }
    expect(tree.blocks[0]!.start).toBe(0);
    // The heading's range is exactly the heading line.
    expect(source.slice(tree.blocks[0]!.start, tree.blocks[0]!.end)).toBe("# Hi");
  });

  it("parses ordered lists with a start offset and task markers", () => {
    const tree = parseMarkdown("3. third\n4. fourth");
    const list = tree.blocks[0]!.block;
    if (list.kind !== "list") {
      throw new Error("expected list");
    }
    expect(list.orderedStart).toBe(3);
    expect(list.items.length).toBe(2);

    const tasks = parseMarkdown("- [ ] todo\n- [x] done").blocks[0]!.block;
    if (tasks.kind !== "list") {
      throw new Error("expected list");
    }
    expect(tasks.items[0]!.checked).toBe(false);
    expect(tasks.items[1]!.checked).toBe(true);
  });

  it("nests lists under their parent item", () => {
    const tree = parseMarkdown("- outer\n  - inner\n- second");
    const list = tree.blocks[0]!.block;
    if (list.kind !== "list") {
      throw new Error("expected list");
    }
    expect(list.items.length).toBe(2);
    const innerList = list.items[0]!.blocks.find((block) => block.kind === "list");
    expect(innerList).toBeDefined();
  });

  it("parses table alignment from the delimiter row", () => {
    const tree = parseMarkdown("| l | c | r |\n|:---|:---:|---:|\n| 1 | 2 | 3 |");
    const table = tree.blocks[0]!.block;
    if (table.kind !== "table") {
      throw new Error("expected table");
    }
    expect(table.align).toEqual(["left", "center", "right"]);
    expect(table.rows.length).toBe(1);
  });

  it("an empty or blank source parses to no blocks", () => {
    expect(parseMarkdown("").blocks).toEqual([]);
    expect(parseMarkdown("  \n\n  ").blocks).toEqual([]);
  });
});

describe("parseMarkdown inline", () => {
  const runsOf = (source: string): Array<{ text: string; style: object }> => {
    const first = parseMarkdown(source).blocks[0]!.block;
    if (first.kind !== "paragraph") {
      throw new Error(`expected paragraph, got ${first.kind}`);
    }
    return first.runs.map((run) => ({ text: run.text, style: run.style }));
  };

  it("styles bold, italic, code, strikethrough, and links", () => {
    expect(runsOf("a **b** c")).toEqual([
      { text: "a ", style: {} },
      { text: "b", style: { bold: true } },
      { text: " c", style: {} },
    ]);
    expect(runsOf("*em*")).toEqual([{ text: "em", style: { italic: true } }]);
    expect(runsOf("`x.y()`")).toEqual([{ text: "x.y()", style: { code: true } }]);
    expect(runsOf("~~old~~")).toEqual([{ text: "old", style: { strikethrough: true } }]);
    expect(runsOf("[docs](https://x.dev)")).toEqual([
      { text: "docs", style: { link: "https://x.dev" } },
    ]);
  });

  it("keeps unclosed markers literal in the settled parse", () => {
    expect(runsOf("**bold")).toEqual([{ text: "**bold", style: {} }]);
  });

  it("mends the tail block for streaming display", () => {
    const tree = parseMarkdown("settled\n\nstream **ing", true);
    const tail = tree.blocks[1]!.block;
    if (tail.kind !== "paragraph") {
      throw new Error("expected paragraph");
    }
    expect(tail.runs).toEqual([
      { text: "stream ", style: {} },
      { text: "ing", style: { bold: true } },
    ]);
    // The settled parse of the same source stays literal.
    const settled = parseMarkdown("settled\n\nstream **ing", false).blocks[1]!.block;
    if (settled.kind !== "paragraph") {
      throw new Error("expected paragraph");
    }
    expect(settled.runs).toEqual([{ text: "stream **ing", style: {} }]);
  });

  it("mends a pending link without showing the partial URL", () => {
    const tree = parseMarkdown("see [docs](https://x", true);
    const block = tree.blocks[0]!.block;
    if (block.kind !== "paragraph") {
      throw new Error("expected paragraph");
    }
    expect(block.runs).toEqual([
      { text: "see ", style: {} },
      { text: "docs", style: { link: PENDING_LINK_URL } },
    ]);
  });

  it("renders a mended half-open fence as a code block", () => {
    const tree = parseMarkdown("writing:\n\n```ts\nconst x = ", true);
    expect(tree.blocks[1]!.block).toEqual({
      kind: "codeBlock",
      language: "ts",
      code: "const x = ",
    });
  });

  it("nests bold inside a link", () => {
    expect(runsOf("[**bold** docs](https://x.dev)")).toEqual([
      { text: "bold", style: { link: "https://x.dev", bold: true } },
      { text: " docs", style: { link: "https://x.dev" } },
    ]);
  });

  it("renders images as link-styled alt text", () => {
    expect(runsOf("![shot](https://x/i.png)")).toEqual([
      { text: "shot", style: { link: "https://x/i.png", image: true } },
    ]);
  });
});

describe("block structure helpers", () => {
  it("blockquote children parse recursively", () => {
    const tree = parseMarkdown("> # heading in quote\n> text");
    const quote = tree.blocks[0]!.block;
    if (quote.kind !== "blockQuote") {
      throw new Error("expected blockQuote");
    }
    expect(quote.children.map((block: Block) => block.kind)).toEqual(["heading", "paragraph"]);
  });
});
