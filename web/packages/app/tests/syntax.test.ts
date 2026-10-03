import { describe, expect, it } from "vitest";
import { highlightCode, type SyntaxRole } from "../src/lib/syntax";

/**
 * Ticket wpn-01 — the code-fence tokenizer covers the desktop's language
 * set. One block per newly registered language, each exercising every token
 * category from the ticket's §2 spec table, plus the §3 alias mirror, the
 * jsonc-vs-json comment distinction, and the untouched generic fallback.
 */

const rolesIn = (code: string, language: string | null): Set<SyntaxRole> =>
  new Set(
    highlightCode(code, language)
      .map((token) => token.role)
      .filter((role): role is SyntaxRole => role !== null),
  );

/** Role of the token whose text is exactly `needle` — exact matching dodges
 * the substring noise that same-role merging invites. */
const roleOf = (code: string, language: string, needle: string): SyntaxRole | null | undefined =>
  highlightCode(code, language).find((token) => token.text === needle)?.role;

const hasToken = (code: string, language: string, needle: string, role: SyntaxRole | null): boolean =>
  highlightCode(code, language).some((token) => token.role === role && token.text.includes(needle));

describe("syntax language coverage (wpn-01)", () => {
  it("css: comments, at-rules, selectors, properties, values, colors", () => {
    const code = `/* dark */
@import "reset.css";
@media screen and (min-width: 600px) {
  .btn:hover {
    font-size: 12px;
    color: #0f0f0f;
    background: rgba(0, 0, 0, 0.5);
  }
}
a { display: flex; }
@keyframes fade { from { opacity: 0 } to { opacity: 1 } }
`;
    expect(rolesIn(code, "css")).toEqual(
      new Set([
        "comment", "keyword", "string", "stringSpecial", "number", "type",
        "property", "tag", "attribute", "function", "operator", "punctuation",
      ]),
    );
    expect(roleOf(code, "css", "/* dark */")).toBe("comment");
    expect(roleOf(code, "css", "@media")).toBe("keyword");
    expect(roleOf(code, "css", '"reset.css"')).toBe("string");
    expect(roleOf(code, "css", "and")).toBe("operator");
    expect(roleOf(code, "css", ".btn")).toBe("property");
    expect(roleOf(code, "css", "hover")).toBe("attribute");
    expect(roleOf(code, "css", "font-size")).toBe("property");
    expect(roleOf(code, "css", "12")).toBe("number");
    expect(roleOf(code, "css", "px")).toBe("type");
    expect(roleOf(code, "css", "#0f0f0f")).toBe("stringSpecial");
    expect(roleOf(code, "css", "rgba")).toBe("function");
    expect(roleOf(code, "css", "a")).toBe("tag");
    expect(roleOf(code, "css", "from")).toBe("keyword");
    expect(roleOf(code, "css", "to")).toBe("keyword");
  });

  it("toml: comments, table keys, pair keys, strings, numbers, booleans", () => {
    const code = `# deps
[tool]
name = "roboco"
count = 3
ok = true
ratio = inf
tags = ["a", "b"]
desc = """
multi line
"""
`;
    expect(rolesIn(code, "toml")).toEqual(
      new Set(["comment", "type", "property", "string", "number", "boolean", "operator", "punctuation"]),
    );
    expect(roleOf(code, "toml", "# deps")).toBe("comment");
    expect(roleOf(code, "toml", "tool")).toBe("type");
    expect(roleOf(code, "toml", "name")).toBe("property");
    expect(roleOf(code, "toml", '"roboco"')).toBe("string");
    expect(roleOf(code, "toml", "3")).toBe("number");
    expect(roleOf(code, "toml", "true")).toBe("boolean");
    expect(roleOf(code, "toml", "inf")).toBe("number");
    // Multi-line strings survive as one token instead of dying at the newline.
    expect(hasToken(code, "toml", "\n", "string")).toBe(true);
  });

  it("lua: comments (both forms), keywords, long strings, numbers", () => {
    const code = `-- config
--[[ multi
line ]]
local function greet(name)
  if name == nil then
    return [[hello]]
  end
  local count = 0x10
  return "hi, " .. name
end
`;
    expect(rolesIn(code, "lua")).toEqual(
      new Set(["comment", "keyword", "function", "boolean", "string", "number", "operator", "punctuation"]),
    );
    expect(roleOf(code, "lua", "-- config")).toBe("comment");
    // `--[[` block comments must win over the `--` line marker (multi-line).
    expect(hasToken(code, "lua", "\n", "comment")).toBe(true);
    expect(roleOf(code, "lua", "local")).toBe("keyword");
    expect(roleOf(code, "lua", "greet")).toBe("function");
    expect(roleOf(code, "lua", "nil")).toBe("boolean");
    expect(roleOf(code, "lua", "[[hello]]")).toBe("string");
    expect(roleOf(code, "lua", "0x10")).toBe("number");
    expect(roleOf(code, "lua", '"hi, "')).toBe("string");
  });

  it("nix: comments, keywords, both string forms, numbers, booleans", () => {
    const code = `# flake
{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs";
  /* inline */
  msg = ''hello
world'';
  ok = true;
  lib = if ok then 1 else 2;
}
`;
    expect(rolesIn(code, "nix")).toEqual(
      new Set(["comment", "keyword", "string", "number", "boolean", "operator", "punctuation", "property"]),
    );
    expect(roleOf(code, "nix", "# flake")).toBe("comment");
    expect(roleOf(code, "nix", "/* inline */")).toBe("comment");
    expect(roleOf(code, "nix", "if")).toBe("keyword");
    expect(roleOf(code, "nix", "url")).toBe("property");
    expect(roleOf(code, "nix", '"github:NixOS/nixpkgs"')).toBe("string");
    // The `''…''` indented string spans lines as one token.
    expect(hasToken(code, "nix", "\n", "string")).toBe(true);
    expect(roleOf(code, "nix", "true")).toBe("boolean");
    expect(roleOf(code, "nix", "1")).toBe("number");
  });

  it("dockerfile: comments, instruction keywords, env keys, heredocs", () => {
    const code = `# base
FROM node:20 AS builder
ARG VERSION
ENV NODE_ENV=production
WORKDIR /app
RUN <<EOF
echo hi
EOF
CMD ["node", "server.js"]
`;
    expect(rolesIn(code, "dockerfile")).toEqual(
      new Set(["comment", "keyword", "constant", "property", "string", "number", "operator", "punctuation"]),
    );
    expect(roleOf(code, "dockerfile", "# base")).toBe("comment");
    expect(roleOf(code, "dockerfile", "FROM")).toBe("keyword");
    expect(hasToken(code, "dockerfile", "node", null)).toBe(true);
    expect(roleOf(code, "dockerfile", "VERSION")).toBe("constant");
    expect(roleOf(code, "dockerfile", "NODE_ENV")).toBe("property");
    expect(roleOf(code, "dockerfile", "20")).toBe("number");
    // The heredoc reads as one string through its closing marker.
    expect(hasToken(code, "dockerfile", "echo hi", "string")).toBe(true);
    expect(roleOf(code, "dockerfile", '"server.js"')).toBe("string");
  });

  it("make: comments, targets and recipes, keywords, variables, assignments", () => {
    const code = `# build
build: $(SRC)
\tclang -o $@ $<
.PHONY: build
CFLAGS ?= -O2
ifeq ($(OS),Linux)
EXT = .so
endif
`;
    expect(rolesIn(code, "make")).toEqual(
      new Set(["comment", "string", "keyword", "constant", "variableSpecial", "operator", "punctuation"]),
    );
    expect(roleOf(code, "make", "# build")).toBe("comment");
    expect(roleOf(code, "make", "build")).toBe("string");
    expect(hasToken(code, "make", "clang -o", "string")).toBe(true);
    expect(roleOf(code, "make", "ifeq")).toBe("keyword");
    expect(roleOf(code, "make", "endif")).toBe("keyword");
    expect(roleOf(code, "make", "$(SRC)")).toBe("constant");
    expect(roleOf(code, "make", "$(OS)")).toBe("constant");
    expect(roleOf(code, "make", "$@")).toBe("variableSpecial");
    expect(roleOf(code, "make", "$<")).toBe("variableSpecial");
    expect(roleOf(code, "make", "CFLAGS")).toBe("constant");
    expect(roleOf(code, "make", "?=")).toBe("operator");
    expect(roleOf(code, "make", ":")).toBe("punctuation");
  });

  it("markdown: headings, emphasis, code, fences, links, list markers", () => {
    const code = `# Title
**bold** and *em* and \`code\`
[link](https://roboco.dev)

- item
\`\`\`js
const x = 1
\`\`\`
    indented code
`;
    expect(rolesIn(code, "markdown")).toEqual(
      new Set([
        "punctuation", "markupHeading", "markupStrong", "markupEmphasis",
        "markupRaw", "markupReference", "markupLink",
      ]),
    );
    expect(roleOf(code, "markdown", "#")).toBe("punctuation");
    expect(roleOf(code, "markdown", "Title")).toBe("markupHeading");
    expect(roleOf(code, "markdown", "**bold**")).toBe("markupStrong");
    expect(roleOf(code, "markdown", "*em*")).toBe("markupEmphasis");
    expect(roleOf(code, "markdown", "`code`")).toBe("markupRaw");
    expect(roleOf(code, "markdown", "[link]")).toBe("markupReference");
    expect(roleOf(code, "markdown", "(https://roboco.dev)")).toBe("markupLink");
    expect(roleOf(code, "markdown", "-")).toBe("punctuation");
    expect(roleOf(code, "markdown", "```")).toBe("punctuation");
    expect(roleOf(code, "markdown", "const x = 1")).toBe("markupRaw");
    expect(roleOf(code, "markdown", "    indented code")).toBe("markupRaw");
  });

  it("jsonc: comments on top of json's key/number/boolean set", () => {
    const code = `{
  // strip
  /* block */
  "name": "roboco",
  "count": 3,
  "ok": true
}`;
    expect(rolesIn(code, "jsonc")).toEqual(
      new Set(["comment", "property", "string", "number", "boolean", "punctuation", "operator"]),
    );
    expect(roleOf(code, "jsonc", "// strip")).toBe("comment");
    expect(roleOf(code, "jsonc", "/* block */")).toBe("comment");
    expect(roleOf(code, "jsonc", '"name"')).toBe("property");
    expect(roleOf(code, "jsonc", '"roboco"')).toBe("string");
    expect(roleOf(code, "jsonc", "3")).toBe("number");
    expect(roleOf(code, "jsonc", "true")).toBe("boolean");
  });

  it("mirrors the desktop's alias table (lib.rs language_for_alias)", () => {
    const docker = "FROM node:20\n";
    expect(highlightCode(docker, "Dockerfile")).toEqual(highlightCode(docker, "dockerfile"));
    expect(highlightCode(docker, "docker")).toEqual(highlightCode(docker, "dockerfile"));

    const make = "build: $(SRC)\n";
    expect(highlightCode(make, "makefile")).toEqual(highlightCode(make, "make"));
    expect(highlightCode(make, "MAKE")).toEqual(highlightCode(make, "make"));

    expect(highlightCode("if x; then echo hi; fi\n", "console")).toEqual(
      highlightCode("if x; then echo hi; fi\n", "bash"),
    );
    expect(highlightCode("def f():\n  pass\n", "python3")).toEqual(
      highlightCode("def f():\n  pass\n", "python"),
    );
    expect(highlightCode('<div class="x">y</div>\n', "htm")).toEqual(
      highlightCode('<div class="x">y</div>\n', "html"),
    );
    expect(highlightCode("int main() { return 0; }\n", "cc")).toEqual(
      highlightCode("int main() { return 0; }\n", "cpp"),
    );
    expect(highlightCode("# T\n", "md")).toEqual(highlightCode("# T\n", "markdown"));
  });

  it("json stays comment-free while jsonc comments", () => {
    const code = '{ "x": 1 }\n// trailing\n';
    expect(rolesIn(code, "json").has("comment")).toBe(false);
    expect(rolesIn(code, "jsonc").has("comment")).toBe(true);
    expect(highlightCode(code, "jsonc")).not.toEqual(highlightCode(code, "json"));
  });

  it("unknown labels still fall back to the generic tokenizer", () => {
    const code = '# hash\n"str" //slash\n';
    expect(highlightCode(code, "xyzzy")).toEqual(highlightCode(code, null));
  });
});
