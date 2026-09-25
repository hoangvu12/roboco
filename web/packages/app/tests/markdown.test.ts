import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { readFileSync } from "node:fs";
import { renderToString } from "react-dom/server";
import { CodeBlock } from "../src/components/markdown";
import {
  autolinkRuns,
  bareUrlLen,
  closeHanging,
  findUrlStart,
  parseInline,
  parseMarkdown,
  tableColumns,
  TABLE_CELL_PADDING,
  TABLE_MIN_COLUMN_CONTENT,
  TABLE_MIN_COLUMN_WIDTH,
  type InlineRun,
} from "../src/lib/markdown";
import { PENDING_LINK_URL } from "../src/lib/markdown";
import {
  graphemeBreaks,
  linkPresentationTruncate,
  normalizeAddress,
  resolveWorkspaceFileLink,
  transcriptAddress,
} from "../src/lib/links";
import { codeFenceLanguage } from "../src/lib/code-fence";
import {
  VEIL_CURVE_POW,
  VEIL_EMA_SEED_MS,
  VEIL_MAX_FADE_MS,
  VEIL_MIN_FADE_MS,
  veilDurationMs,
  veilEmaNext,
  veilOpacity,
} from "../src/lib/veil";

// ---------------------------------------------------------------------------
// Bare-URL autolink (parser.rs tests `bare_urls_autolink` / :1072 and
// `autolink_leaves_non_urls_alone` / :1102, ported)
// ---------------------------------------------------------------------------

/** The paragraph's single link run as (text, href), or null when none. */
function onlyLink(text: string): { text: string; link: string } | null {
  const tree = parseMarkdown(text);
  const block = tree.blocks[0]?.block;
  if (block === undefined || block.kind !== "paragraph") {
    return null;
  }
  const links = block.runs.filter((run) => run.style.link !== null && run.style.link !== undefined);
  if (links.length > 1) {
    throw new Error(`expected at most one link: ${JSON.stringify(links)}`);
  }
  const run = links[0];
  return run === undefined ? null : { text: run.text, link: run.style.link! };
}

describe("bare_urls_autolink", () => {
  it("promotes a bare URL into a clickable link run", () => {
    expect(onlyLink("PR is updated: https://github.com/zeronsh/comet/pull/31")).toEqual({
      text: "https://github.com/zeronsh/comet/pull/31",
      link: "https://github.com/zeronsh/comet/pull/31",
    });
  });

  it("trims trailing sentence punctuation", () => {
    expect(onlyLink("see https://x.dev/a, then rest.")?.link).toBe("https://x.dev/a");
  });

  it("sheds a wrapping paren but keeps one balanced by an opener in the path", () => {
    expect(onlyLink("(docs: https://x.dev/Foo_(bar))")?.link).toBe("https://x.dev/Foo_(bar)");
  });

  it("bold text still autolinks and keeps the emphasis on the run", () => {
    const tree = parseMarkdown("**see https://x.dev now**");
    const block = tree.blocks[0]!.block;
    if (block.kind !== "paragraph") {
      throw new Error("paragraph");
    }
    const link = block.runs.find((run) => run.style.link !== null && run.style.link !== undefined);
    expect(link?.style.bold).toBe(true);
    expect(link?.style.link).toBe("https://x.dev");
  });
});

describe("autolink_leaves_non_urls_alone", () => {
  it("a scheme glued to a preceding word stays text", () => {
    expect(onlyLink("foohttps://x.dev is glued")).toBeNull();
  });

  it("a bare scheme with nothing after it stays text", () => {
    expect(onlyLink("the https:// scheme alone")).toBeNull();
  });

  it("code spans shield the URL", () => {
    expect(onlyLink("`https://x.dev` in code")).toBeNull();
  });

  it("a markdown link whose TEXT is a URL keeps the written destination", () => {
    expect(onlyLink("[https://shown.dev](https://real.dev)")).toEqual({
      text: "https://shown.dev",
      link: "https://real.dev",
    });
  });

  it("runs already inside a link or code pass through untouched", () => {
    const runs: InlineRun[] = [
      { text: "plain ", style: {} },
      { text: "in code", style: { code: true } },
    ];
    expect(autolinkRuns(runs)).toEqual(runs);
  });
});

describe("find_url_start / bare_url_len", () => {
  it("rejects a scheme glued to a preceding alphanumeric", () => {
    expect(findUrlStart("foohttps://x.dev")).toBeNull();
  });

  it("accepts after punctuation and at the start", () => {
    expect(findUrlStart("see https://x.dev")).toBe(4);
    expect(findUrlStart("https://x.dev")).toBe(0);
  });

  it("runs to whitespace or a delimiter that never appears in URLs", () => {
    expect(bareUrlLen("https://x.dev/a next")).toBe("https://x.dev/a".length);
    expect(bareUrlLen('https://x.dev/a"x')).toBe("https://x.dev/a".length);
    expect(bareUrlLen("https://x.dev/a`b")).toBe("https://x.dev/a".length);
  });

  it("trims trailing .,;:!?*_~ and unbalanced parens", () => {
    expect(bareUrlLen("https://x.dev/a.")).toBe("https://x.dev/a".length);
    expect(bareUrlLen("https://x.dev/a!!!")).toBe("https://x.dev/a".length);
    expect(bareUrlLen("https://x.dev/a)")).toBe("https://x.dev/a".length);
    expect(bareUrlLen("https://x.dev/(a)")).toBe("https://x.dev/(a)".length);
  });

  it("a scheme alone is not a URL", () => {
    expect(bareUrlLen("https:// ")).toBe("https://".length);
  });
});

// ---------------------------------------------------------------------------
// transcript_address (browser/model.rs + markdown/links.rs:76-97, ported)
// ---------------------------------------------------------------------------

describe("transcript_address", () => {
  it("rejected targets never fall back", () => {
    for (const url of [
      "javascript:alert(1)",
      "data:text/plain,hi",
      "mailto:a@b.com",
      "https://user:pass@example.com",
      "https://example.com/\npath",
      "\nhttps://example.com",
      "https://",
      "example.com",
      "https:///path",
      "https://example.com/%GG",
    ]) {
      expect(transcriptAddress(url), url).toBeNull();
    }
  });

  it("accepts http and https with an authority, normalized", () => {
    expect(transcriptAddress("https://example.com")).toBe("https://example.com/");
    expect(transcriptAddress("http://example.com/path?x=1")).toBe("http://example.com/path?x=1");
    expect(transcriptAddress("HTTPS://EXAMPLE.COM")).toBe("https://example.com/");
  });

  it("keeps a valid percent escape", () => {
    expect(transcriptAddress("https://example.com/a%20b")).toBe("https://example.com/a%20b");
  });

  it("markdown links and autolinks share the validation", () => {
    const tree = parseMarkdown("[label](https://example.com) and https://example.org/path");
    const block = tree.blocks[0]!.block;
    if (block.kind !== "paragraph") {
      throw new Error("paragraph");
    }
    const urls = block.runs
      .filter((run) => run.style.link !== null && run.style.link !== undefined)
      .map((run) => run.style.link!);
    expect(urls).toEqual(["https://example.com", "https://example.org/path"]);
    expect(urls.every((url) => transcriptAddress(url) !== null)).toBe(true);
  });
});

describe("normalize_address", () => {
  it("infers https for a bare host and rejects non-web schemes", () => {
    expect(normalizeAddress("example.com")).toBe("https://example.com/");
    expect(normalizeAddress("ftp://example.com")).toBeNull();
    expect(normalizeAddress("")).toBeNull();
  });

  it("a bare host:port only counts as explicit when the suffix is numeric", () => {
    // Loopback and inferred → http, per normalize_address's loopback flip.
    expect(normalizeAddress("localhost:8080")).toBe("http://localhost:8080/");
    expect(normalizeAddress("not-a-port:")).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// table_columns (render.rs:727-742, ported)
// ---------------------------------------------------------------------------

describe("table_columns", () => {
  it("floors content, adds padding, and caps the minimum", () => {
    const { naturals, minimums, minTableWidth } = tableColumns([20, 80, 200]);
    expect(naturals).toEqual([48 + 24, 80 + 24, 200 + 24]);
    expect(minimums).toEqual([72, 96, 96]);
    expect(minTableWidth).toBe(72 + 96 + 96);
  });

  it("keeps the constants the desktop ships", () => {
    expect(TABLE_CELL_PADDING).toBe(12);
    expect(TABLE_MIN_COLUMN_CONTENT).toBe(48);
    expect(TABLE_MIN_COLUMN_WIDTH).toBe(96);
  });
});

// ---------------------------------------------------------------------------
// close_hanging (mend.rs tests, ported — the §3.2 checklist)
// ---------------------------------------------------------------------------

function mends(input: string, expected: string): void {
  expect(closeHanging(input), JSON.stringify(input)).toBe(expected);
}

function stays(input: string): void {
  expect(closeHanging(input), JSON.stringify(input)).toBeNull();
}

describe("close_hanging", () => {
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

  it("nested closers come innermost-first", () => {
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

  it("link URLs allow nested parens", () => {
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
    mends("para\n-", "para\n-\u200B");
    mends("para\n--", "para\n--\u200B");
    mends("para\n=", "para\n=\u200B");
    stays("para\n---");
    stays("-");
    stays("\n-");
  });
});

// ---------------------------------------------------------------------------
// Veil (veil.rs, ported values)
// ---------------------------------------------------------------------------

describe("veil", () => {
  it("ships the desktop's constants", () => {
    expect(VEIL_EMA_SEED_MS).toBe(160);
    expect(VEIL_MIN_FADE_MS).toBe(120);
    expect(VEIL_MAX_FADE_MS).toBe(400);
    expect(VEIL_CURVE_POW).toBe(1.6);
  });

  it("duration is clamp(ema*3, 120, 400)", () => {
    expect(veilDurationMs(0)).toBe(120);
    expect(veilDurationMs(50)).toBe(150);
    expect(veilDurationMs(200)).toBe(400);
    expect(veilDurationMs(10_000)).toBe(400);
  });

  it("opacity dissolves as 1 − (1 − p)^1.6", () => {
    expect(veilOpacity(0)).toBe(0);
    expect(veilOpacity(1)).toBe(1);
    expect(veilOpacity(0.5)).toBeCloseTo(1 - Math.pow(0.5, 1.6), 12);
    expect(veilOpacity(-1)).toBe(0);
    expect(veilOpacity(2)).toBe(1);
  });

  it("the EMA update is ema*0.7 + min(gap, 1000)*0.3", () => {
    expect(veilEmaNext(160, 40)).toBeCloseTo(160 * 0.7 + 40 * 0.3, 12);
    expect(veilEmaNext(160, 10_000)).toBeCloseTo(160 * 0.7 + 1000 * 0.3, 12);
  });
});

// ---------------------------------------------------------------------------
// Code fence language mapping (web-pierre-adoption, ticket 05: the fence
// label → the library's language id — never an unresolvable id, which
// would leave the block blank)
// ---------------------------------------------------------------------------

describe("codeFenceLanguage", () => {
  it("resolves full language names and common aliases to bundled ids", () => {
    expect(codeFenceLanguage("rust")).toBe("rust");
    expect(codeFenceLanguage("Rust")).toBe("rust");
    expect(codeFenceLanguage("python")).toBe("python");
    expect(codeFenceLanguage("js")).toBe("javascript");
    expect(codeFenceLanguage("ts")).toBe("typescript");
    expect(codeFenceLanguage("py")).toBe("python");
    expect(codeFenceLanguage("rs")).toBe("rust");
    expect(codeFenceLanguage("md")).toBe("markdown");
    expect(codeFenceLanguage("json")).toBe("json");
    expect(codeFenceLanguage("csharp")).toBe("csharp");
    expect(codeFenceLanguage("go")).toBe("go");
  });

  it("maps the fence-only aliases the extension oracle misses", () => {
    expect(codeFenceLanguage("bash")).toBe("zsh");
    expect(codeFenceLanguage("sh")).toBe("zsh");
    expect(codeFenceLanguage("shell")).toBe("zsh");
    expect(codeFenceLanguage("svg")).toBe("xml");
  });

  it("unknown labels, attributes, and emptiness degrade to plain text", () => {
    expect(codeFenceLanguage("some-prose-label")).toBe("text");
    expect(codeFenceLanguage(null)).toBe("text");
    expect(codeFenceLanguage("")).toBe("text");
    expect(codeFenceLanguage("  ")).toBe("text");
    // A fence info string may carry attributes after the language.
    expect(codeFenceLanguage("rust,ignore")).toBe("rust");
    expect(codeFenceLanguage("js title=app.tsx")).toBe("javascript");
    // The library's own plain-text ids pass straight through.
    expect(codeFenceLanguage("text")).toBe("text");
  });
});

// ---------------------------------------------------------------------------
// Code blocks through the diffs library (render.rs, ported — ticket 05)
// ---------------------------------------------------------------------------

describe("code blocks render through the diffs library", () => {
  it("renders our header chrome around the library's headerless host", () => {
    const html = renderToString(createElement(CodeBlock, { code: "fn main() {}", language: "Rust" }));
    // Our chrome: the verbatim fence-info label and the copy/fit actions.
    expect(html).toContain("md-codehead");
    expect(html).toContain(">Rust<");
    expect(html).toContain("md-copy");
    // The library's host element (a custom element — string-rendered with
    // our token-mapping class and the inline runtime metrics; the code body
    // itself renders inside its shadow DOM once mounted).
    expect(html).toContain("md-code-host");
    expect(html).toMatch(/--diffs-font-size:12\.5px/);
    expect(html).toMatch(/--diffs-line-height:18px/);
  });

  it("the CSS maps the web tokens onto the library host", () => {
    const css = readFileSync(new URL("../src/styles/app.css", import.meta.url), "utf8");
    const rule = /\.md-code-host\s*\{[^}]*\}/.exec(css)?.[0] ?? "";
    expect(rule).toContain("--diffs-font-family: var(--rb-font-mono");
    expect(rule).toContain("--diffs-tab-size: 4");
  });
});

// ---------------------------------------------------------------------------
// Workspace file links (workspace_links.rs tests, ported)
// ---------------------------------------------------------------------------

describe("resolve_workspace_file_link", () => {
  it("resolves relative, absolute and location links", () => {
    const root = "/work/comet";
    expect(resolveWorkspaceFileLink("crates/ui/src/lib.rs", root)).toEqual({
      path: "crates/ui/src/lib.rs",
      line: null,
      column: null,
    });
    expect(resolveWorkspaceFileLink("/work/comet/crates/ui/src/lib.rs:42:7", root)).toEqual({
      path: "crates/ui/src/lib.rs",
      line: 42,
      column: 7,
    });
    expect(resolveWorkspaceFileLink("file:///work/comet/README.md#L12", root)).toEqual({
      path: "README.md",
      line: 12,
      column: null,
    });
  });

  it("resolves canonical file mentions", () => {
    expect(resolveWorkspaceFileLink("roboco-file:src/a%20file.rs", "/work/comet")).toEqual({
      path: "src/a file.rs",
      line: null,
      column: null,
    });
    expect(resolveWorkspaceFileLink("roboco-file:src/%61.rs", "/work/comet")).toBeNull();
    expect(resolveWorkspaceFileLink("roboco-file:src/", "/work/comet")).toBeNull();
  });

  it("rejects external and unsafe targets", () => {
    const root = "/work/comet";
    for (const target of [
      "https://example.com/file.rs",
      "mailto:dev@example.com",
      "/work/comet-other/src/lib.rs",
      "/tmp/file.rs",
      "../secret.rs",
      "src/../../secret.rs",
      "src/./lib.rs",
      "src//lib.rs",
    ]) {
      expect(resolveWorkspaceFileLink(target, root), target).toBeNull();
    }
  });

  it("drive letters are rejected (`:` anywhere in the path)", () => {
    // Even a Windows-rooted target cannot survive the colon check.
    expect(resolveWorkspaceFileLink("C:/work/comet/src/lib.rs", "C:\\work\\comet")).toBeNull();
    // POSIX-style roots and Windows drive roots do not mix either.
    expect(resolveWorkspaceFileLink("/work/comet/src/lib.rs", "C:\\work\\comet")).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Link presentation truncation (link_presentation.rs, ported)
// ---------------------------------------------------------------------------

describe("link_presentation truncate", () => {
  const measure = (label: string): number => label.length * 10;

  it("leaves labels that fit and links that fail validation", () => {
    const result = linkPresentationTruncate(
      {
        text: "see https://example.com/a ok",
        links: [
          { start: 4, end: 29, url: "https://example.com/a" },
          { start: 30, end: 32, url: "javascript:x" },
        ],
      },
      500,
      measure,
    );
    expect(result.text).toBe("see https://example.com/a ok");
    expect(result.offsets.omissions).toHaveLength(0);
  });

  it("truncates to a grapheme boundary plus ellipsis and maps offsets back", () => {
    const result = linkPresentationTruncate(
      { text: "https://example.com/averylongpath", links: [{ start: 0, end: 33, url: "https://example.com/averylongpath" }] },
      100,
      measure,
    );
    // 10px per grapheme + the ellipsis must fit in 100px: 9 graphemes + …
    expect(result.text.endsWith("…")).toBe(true);
    expect(result.text.length).toBe(10);
    // An offset inside the ellipsis maps to the first omitted original char;
    // the end maps back to the original end.
    expect(result.offsets.original(result.text.length - 1)).toBe(9);
    expect(result.offsets.original(result.text.length)).toBe(33);
  });

  it("never makes a short label longer just to add an ellipsis", () => {
    // Only 2 chars would be cut — not worth replacing with a 1-char ellipsis.
    const result = linkPresentationTruncate(
      { text: "ab", links: [{ start: 0, end: 2, url: "https://example.com" }] },
      10,
      measure,
    );
    expect(result.text).toBe("ab");
    expect(result.offsets.omissions).toHaveLength(0);
  });
});

describe("grapheme breaks", () => {
  it("inserts a zero-width space after every grapheme", () => {
    expect(graphemeBreaks("ab")).toBe("a\u200Bb\u200B");
  });

  it("keeps surrogate pairs whole", () => {
    expect(graphemeBreaks("a😀")).toBe("a\u200B😀\u200B");
  });
});

// ---------------------------------------------------------------------------
// parseInline regression guards for the autolink integration
// ---------------------------------------------------------------------------

describe("parseInline autolink integration", () => {
  it("paragraphs autolink through the block parse", () => {
    const runs = parseMarkdown("go to https://x.dev/a now").blocks[0]!.block;
    if (runs.kind !== "paragraph") {
      throw new Error("paragraph");
    }
    expect(runs.runs.map((run) => [run.text, run.style.link ?? null])).toEqual([
      ["go to ", null],
      ["https://x.dev/a", "https://x.dev/a"],
      [" now", null],
    ]);
  });

  it("table cells autolink too", () => {
    const table = parseMarkdown("| a |\n| --- |\n| https://x.dev |").blocks[0]!.block;
    if (table.kind !== "table") {
      throw new Error("table");
    }
    const cell = table.rows[0]![0]!;
    expect(cell[0]?.style.link).toBe("https://x.dev");
  });

  it("the fence language label stays verbatim", () => {
    const block = parseMarkdown("```Rust\nfn main() {}\n```").blocks[0]!.block;
    if (block.kind !== "codeBlock") {
      throw new Error("codeBlock");
    }
    expect(block.language).toBe("Rust");
  });
});

// ---------------------------------------------------------------------------
// Code-block text selection (d353ebbf — ported onto the library path in the
// "code blocks render through the diffs library" suite above: selection of
// the code body is the library's own behavior inside its shadow DOM; our
// chrome's contract is the host + token mapping)
// ---------------------------------------------------------------------------
