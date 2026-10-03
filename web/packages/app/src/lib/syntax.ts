/**
 * Syntax highlighting for transcript code blocks — a compact hand-rolled
 * tokenizer standing in for the desktop's tree-sitter stack. Tokens carry the
 * theme's syntax role names (`--rb-syntax-*`), so colors come from the theme
 * artifact on every variant; the mapping is stable even when the tokenizer
 * can't see what tree-sitter would (it is deliberately conservative: unsure
 * text stays plain rather than guessing a loud role).
 */

export type SyntaxRole =
  | "comment"
  | "keyword"
  | "string"
  | "stringSpecial"
  | "escape"
  | "number"
  | "boolean"
  | "type"
  | "function"
  | "property"
  | "constant"
  | "variableSpecial"
  | "operator"
  | "punctuation"
  | "tag"
  | "attribute"
  | "macro"
  // The roles the desktop's `HighlightKind` (crates/syntax/src/lib.rs:60-92)
  // carries that this tokenizer never emits: the union stays complete so a
  // future tokenizer upgrade maps one-for-one, and every member already has
  // its `.tk-*` CSS rule.
  | "typeBuiltin"
  | "constructor"
  | "functionBuiltin"
  | "variable"
  | "parameter"
  | "label"
  | "markupHeading"
  | "markupRaw"
  | "markupLink"
  | "markupReference"
  | "markupEmphasis"
  | "markupStrong"
  | "embedded"
  | "invalid";

export interface SyntaxToken {
  readonly text: string;
  /** null = plain text color. */
  readonly role: SyntaxRole | null;
}

interface LanguageSpec {
  readonly lineComments: readonly string[];
  readonly blockComments: readonly (readonly [string, string])[];
  readonly keywords: ReadonlySet<string>;
  /** `true` treats backtick strings as template literals (stringSpecial). */
  readonly templateString?: boolean;
  /** Alternative multi-line string delimiters plain quote scanning can't
   * see: Lua `[[…]]`, Nix `''…''`, TOML `"""…"""`. */
  readonly longStrings?: readonly (readonly [string, string])[];
  /** `true` classifies config keys — `[table.header]` keys read as types,
   * `key =` / `a.b =` pair keys as properties (TOML). */
  readonly configKeys?: boolean;
  /** Bare words that read as numbers (TOML's `inf`/`nan`). */
  readonly numberWords?: ReadonlySet<string>;
}

const JS_KW = [
  "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
  "delete", "do", "else", "export", "extends", "finally", "for", "from", "function", "if", "import",
  "in", "instanceof", "let", "new", "of", "return", "static", "super", "switch", "this", "throw",
  "try", "typeof", "var", "void", "while", "with", "yield",
];
const TS_KW = [...JS_KW, "abstract", "as", "declare", "enum", "implements", "interface", "is", "keyof", "namespace", "override", "private", "protected", "public", "readonly", "satisfies", "type"];
const PY_KW = [
  "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
  "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
  "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "match",
];
const RUST_KW = [
  "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
  "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
  "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
  "use", "where", "while",
];
const C_LIKE_KW = [
  "auto", "break", "case", "catch", "class", "const", "continue", "default", "delete", "do",
  "else", "enum", "explicit", "export", "extern", "final", "finally", "for", "friend", "goto",
  "if", "inline", "interface", "long", "namespace", "new", "override", "private", "protected",
  "public", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch",
  "template", "this", "throw", "throws", "try", "typedef", "typename", "union", "unsigned",
  "using", "virtual", "void", "volatile", "while", "sealed", "record", "var", "is", "null",
];
const GO_KW = [
  "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
  "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
  "struct", "switch", "type", "var",
];
const BASH_KW = [
  "case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in", "select",
  "then", "until", "while", "echo", "cd", "export", "local", "return", "set", "source", "shift",
];
const SQL_KW = [
  "select", "from", "where", "insert", "update", "delete", "join", "left", "right", "inner",
  "outer", "on", "group", "by", "order", "limit", "offset", "create", "table", "alter", "drop",
  "index", "values", "into", "set", "and", "or", "not", "null", "as", "distinct", "union", "all",
  "having", "exists", "case", "when", "then", "else", "end", "primary", "key", "references",
];
const RUBY_KW = [
  "alias", "and", "begin", "break", "case", "class", "def", "defined", "do", "else", "elsif",
  "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
  "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until",
  "when", "while", "yield",
];
// `nil`/`true`/`false` stay in BOOLEANS: the desktop marks Lua's literals
// @boolean, and keeping them out of the keyword set colors all three alike.
const LUA_KW = [
  "and", "break", "do", "else", "elseif", "end", "for", "function", "goto", "if", "in",
  "local", "not", "or", "repeat", "return", "then", "until", "while",
];
const NIX_KW = [
  "if", "then", "else", "let", "inherit", "in", "rec", "with", "assert", "or",
];

const C_LIKE: LanguageSpec = {
  lineComments: ["//"],
  blockComments: [["/*", "*/"]],
  keywords: new Set(C_LIKE_KW),
};

const LANGUAGES: Record<string, LanguageSpec> = {
  javascript: { ...C_LIKE, keywords: new Set(JS_KW), templateString: true },
  typescript: { ...C_LIKE, keywords: new Set(TS_KW), templateString: true },
  jsx: { ...C_LIKE, keywords: new Set(JS_KW), templateString: true },
  tsx: { ...C_LIKE, keywords: new Set(TS_KW), templateString: true },
  python: { lineComments: ["#"], blockComments: [['"""', '"""'], ["'''", "'''"]], keywords: new Set(PY_KW), templateString: true },
  rust: { ...C_LIKE, keywords: new Set(RUST_KW) },
  go: { ...C_LIKE, keywords: new Set(GO_KW), templateString: true },
  c: C_LIKE,
  cpp: C_LIKE,
  java: C_LIKE,
  csharp: C_LIKE,
  kotlin: { ...C_LIKE, keywords: new Set([...C_LIKE_KW, "fun", "val", "when", "object", "companion", "data", "sealed"]) },
  swift: { ...C_LIKE, keywords: new Set([...C_LIKE_KW, "func", "let", "var", "guard", "defer", "protocol", "extension", "some"]) },
  php: { lineComments: ["//", "#"], blockComments: [["/*", "*/"]], keywords: new Set([...C_LIKE_KW, "echo", "fn", "foreach", "as", "match"]) },
  ruby: { lineComments: ["#"], blockComments: [], keywords: new Set(RUBY_KW), templateString: true },
  bash: { lineComments: ["#"], blockComments: [], keywords: new Set(BASH_KW) },
  shell: { lineComments: ["#"], blockComments: [], keywords: new Set(BASH_KW) },
  sql: { lineComments: ["--"], blockComments: [["/*", "*/"]], keywords: new Set(SQL_KW) },
  toml: {
    lineComments: ["#"],
    blockComments: [],
    keywords: new Set(),
    longStrings: [['"""', '"""'], ["'''", "'''"]],
    configKeys: true,
    numberWords: new Set(["inf", "nan"]),
  },
  lua: {
    lineComments: ["--"],
    blockComments: [["--[[", "]]"]],
    keywords: new Set(LUA_KW),
    longStrings: [["[[", "]]"]],
  },
  nix: {
    lineComments: ["#"],
    blockComments: [["/*", "*/"]],
    keywords: new Set(NIX_KW),
    longStrings: [["''", "''"]],
  },
};

const ALIASES: Record<string, string> = {
  js: "javascript", mjs: "javascript", cjs: "javascript", javascriptreact: "jsx",
  ts: "typescript", mts: "typescript", cts: "typescript", typescriptreact: "tsx",
  py: "python", rs: "rust", golang: "go", "c++": "cpp", cxx: "cpp", hpp: "cpp", h: "c",
  cs: "csharp", "c#": "csharp", kt: "kotlin", kts: "kotlin", sh: "bash", zsh: "bash",
  shellscript: "bash", bash: "bash", rb: "ruby", yml: "yaml", md: "markdown",
  python3: "python", console: "bash", docker: "dockerfile", makefile: "make",
  cc: "cpp", htm: "html",
};

const IDENT_RE = /[A-Za-z_][A-Za-z0-9_]*/y;
const NUMBER_RE = /0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?/y;

const DOCKERFILE_KW = new Set([
  "FROM", "AS", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
  "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL",
  "MAINTAINER",
]);
const MAKE_KW = new Set([
  "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "foreach", "define", "endef",
  "export", "unexport", "override", "private", "include", "sinclude", "vpath",
]);
const MAKE_ASSIGN_OPS = ["::=", ":=", "?=", "+=", "!=", "="];
/** At-rules whose braces wrap rules rather than declarations. */
const CSS_RULES_AT = new Set([
  "media", "supports", "keyframes", "document", "container", "scope", "layer",
  "starting-style",
]);
/** CSS identifiers carry hyphens (`font-size`, `-webkit-*`). */
const CSS_IDENT_RE = /-{0,2}[A-Za-z_][A-Za-z0-9_-]*/y;
/** Three-, four-, six-, and eight-digit hex colors. */
const CSS_HEX_RE = /#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})(?![0-9a-fA-F])/y;
const CSS_UNIT_RE = /[A-Za-z%]+/y;

const BOOLEANS = new Set(["true", "false", "null", "None", "True", "False", "nil", "undefined"]);

function push(tokens: SyntaxToken[], text: string, role: SyntaxRole | null): void {
  if (text.length === 0) {
    return;
  }
  const last = tokens[tokens.length - 1];
  if (last !== undefined && last.role === role) {
    tokens[tokens.length - 1] = { text: last.text + text, role };
    return;
  }
  tokens.push({ text, role });
}

/**
 * Tokenize `code` in `language` (a fence info string; aliases resolve). The
 * label arrives verbatim from the fence, so the lookup lowercases it — the
 * desktop's tree-sitter resolves case-insensitively the same way.
 */
export function highlightCode(code: string, language: string | null): SyntaxToken[] {
  const label = language?.toLowerCase() ?? null;
  const key = label === null ? null : (ALIASES[label] ?? label);
  if (key === "json") {
    return highlightJson(code);
  }
  if (key === "jsonc") {
    return highlightJsonc(code);
  }
  if (key === "yaml") {
    return highlightYaml(code);
  }
  if (key === "html" || key === "xml" || key === "svg") {
    return highlightMarkup(code);
  }
  if (key === "css") {
    return highlightCss(code);
  }
  if (key === "markdown") {
    return highlightMarkdown(code);
  }
  if (key === "dockerfile") {
    return highlightDockerfile(code);
  }
  if (key === "make") {
    return highlightMake(code);
  }
  const spec = key === null ? undefined : LANGUAGES[key];
  if (spec === undefined) {
    return highlightGeneric(code);
  }
  return highlightCLike(code, spec);
}

/** No language: comments and strings still read as structure, conservatively. */
function highlightGeneric(code: string): SyntaxToken[] {
  return highlightCLike(code, {
    lineComments: ["//", "#"],
    blockComments: [["/*", "*/"]],
    keywords: new Set(),
  });
}

function highlightCLike(code: string, spec: LanguageSpec): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  const n = code.length;
  let i = 0;

  const matchLineComment = (): string | null => {
    for (const marker of spec.lineComments) {
      if (code.startsWith(marker, i)) {
        return marker;
      }
    }
    return null;
  };
  const matchBlockComment = (): readonly [string, string] | null => {
    for (const pair of spec.blockComments) {
      if (code.startsWith(pair[0], i)) {
        return pair;
      }
    }
    return null;
  };
  const matchLongString = (): readonly [string, string] | null => {
    for (const pair of spec.longStrings ?? []) {
      if (code.startsWith(pair[0], i)) {
        return pair;
      }
    }
    return null;
  };

  while (i < n) {
    const c = code[i]!;

    // Block markers first: Lua's `--[[` must win over its `--` line marker.
    const block = matchBlockComment();
    if (block !== null) {
      const close = code.indexOf(block[1], i + block[0].length);
      const end = close < 0 ? n : close + block[1].length;
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }
    const lineComment = matchLineComment();
    if (lineComment !== null) {
      let end = code.indexOf("\n", i);
      if (end < 0) {
        end = n;
      }
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }
    const longString = matchLongString();
    if (longString !== null) {
      const close = code.indexOf(longString[1], i + longString[0].length);
      const end = close < 0 ? n : close + longString[1].length;
      push(tokens, code.slice(i, end), "string");
      i = end;
      continue;
    }

    if (c === '"' || c === "'" || c === "`") {
      const quote = c;
      const template = quote === "`" && spec.templateString === true;
      let j = i + 1;
      while (j < n) {
        if (code[j] === "\\") {
          j += 2;
          continue;
        }
        if (code[j] === quote) {
          j++;
          break;
        }
        if (quote !== "`" && code[j] === "\n") {
          // Most languages don't allow raw newlines in strings; stop so a
          // missing closer doesn't swallow the rest of the block.
          break;
        }
        j++;
      }
      push(tokens, code.slice(i, j), template ? "stringSpecial" : "string");
      i = j;
      continue;
    }

    NUMBER_RE.lastIndex = i;
    const num = NUMBER_RE.exec(code);
    if (num !== null && num[0].length > 0) {
      push(tokens, num[0], "number");
      i += num[0].length;
      continue;
    }

    IDENT_RE.lastIndex = i;
    const ident = IDENT_RE.exec(code);
    if (ident !== null && ident[0].length > 0) {
      const word = ident[0];
      let role: SyntaxRole | null = null;
      if (spec.keywords.has(word) || spec.keywords.has(word.toLowerCase())) {
        role = "keyword";
      } else if (spec.numberWords !== undefined && spec.numberWords.has(word)) {
        role = "number";
      } else if (BOOLEANS.has(word)) {
        role = "boolean";
      } else {
        let look = i + word.length;
        while (code[look] === " ") {
          look++;
        }
        if (spec.configKeys === true) {
          // Walk back over whitespace and dotted keys: a `[` behind means a
          // table header (type); an `=` or `.` ahead means a pair key.
          let back = i - 1;
          while (back >= 0 && /\s/.test(code[back]!)) {
            back--;
          }
          for (;;) {
            while (back >= 0 && /[A-Za-z0-9_-]/.test(code[back]!)) {
              back--;
            }
            if (back >= 0 && code[back] === ".") {
              back--;
              continue;
            }
            break;
          }
          if (back >= 0 && code[back] === "[") {
            role = "type";
          } else if (code[look] === "=" || code[look] === ".") {
            role = "property";
          }
        } else {
          const prev = i > 0 ? code[i - 1] : "";
          if (code[look] === "(" || code[look] === "<" && /^[a-z]/.test(word)) {
            role = "function";
          } else if (prev === ".") {
            role = "property";
          } else if (/^[A-Z]/.test(word)) {
            role = "type";
          } else if (/^[A-Z][A-Z0-9_]+$/.test(word)) {
            role = "constant";
          }
        }
      }
      push(tokens, word, role);
      i += word.length;
      continue;
    }

    if ("(){}[]".includes(c)) {
      push(tokens, c, "punctuation");
      i++;
      continue;
    }
    if ("+-*/%=<>!&|^~?:".includes(c)) {
      push(tokens, c, "operator");
      i++;
      continue;
    }
    push(tokens, c, null);
    i++;
  }
  return tokens;
}

/** JSON: keys as properties, literals as constants, strings/numbers marked. */
function highlightJson(code: string): SyntaxToken[] {
  return highlightJsonLike(code, false);
}

/** JSONC: json plus line and block comments — the desktop keeps the two
 * grammars distinct, so plain json must stay comment-free. */
function highlightJsonc(code: string): SyntaxToken[] {
  return highlightJsonLike(code, true);
}

function highlightJsonLike(code: string, comments: boolean): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  let i = 0;
  while (i < code.length) {
    const c = code[i]!;
    if (comments && code.startsWith("//", i)) {
      let end = code.indexOf("\n", i);
      if (end < 0) {
        end = code.length;
      }
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }
    if (comments && code.startsWith("/*", i)) {
      const close = code.indexOf("*/", i + 2);
      const end = close < 0 ? code.length : close + 2;
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }
    if (c === '"') {
      let j = i + 1;
      while (j < code.length && code[j] !== '"') {
        j += code[j] === "\\" ? 2 : 1;
      }
      j = Math.min(j + 1, code.length);
      let look = j;
      while (code[look] === " " || code[look] === "\t") {
        look++;
      }
      push(tokens, code.slice(i, j), code[look] === ":" ? "property" : "string");
      i = j;
      continue;
    }
    NUMBER_RE.lastIndex = i;
    const num = NUMBER_RE.exec(code);
    if (num !== null && num[0].length > 0 && !/[A-Za-z_$]/.test(code[i - 1] ?? "")) {
      push(tokens, num[0], "number");
      i += num[0].length;
      continue;
    }
    const literal = /(true|false|null)\b/y;
    literal.lastIndex = i;
    const lit = literal.exec(code);
    if (lit !== null && !/[A-Za-z_$]/.test(code[i - 1] ?? "")) {
      push(tokens, lit[0], "boolean");
      i += lit[0].length;
      continue;
    }
    if ("{}[]".includes(c)) {
      push(tokens, c, "punctuation");
    } else if (":".includes(c)) {
      push(tokens, c, "operator");
    } else {
      push(tokens, c, null);
    }
    i++;
  }
  return tokens;
}

/** YAML: mapping keys as properties, comments, and scalars stay plain. */
function highlightYaml(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  for (const line of code.split("\n")) {
    const hash = line.indexOf("#");
    const body = hash < 0 ? line : line.slice(0, hash);
    const keyMatch = /^(\s*)([^:\s][^:]*)(:)/.exec(body);
    if (keyMatch !== null) {
      const indent = keyMatch[1] ?? "";
      if (indent.length > 0) {
        push(tokens, indent, null);
      }
      push(tokens, keyMatch[2] ?? "", "property");
      push(tokens, ":", "operator");
      push(tokens, body.slice(keyMatch[0].length), null);
    } else {
      push(tokens, body, null);
    }
    if (hash >= 0) {
      push(tokens, line.slice(hash), "comment");
    }
    push(tokens, "\n", null);
  }
  return tokens;
}

/** HTML/XML: tag names, attributes, strings, comments. */
function highlightMarkup(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  let i = 0;
  while (i < code.length) {
    if (code.startsWith("<!--", i)) {
      const close = code.indexOf("-->", i + 4);
      const end = close < 0 ? code.length : close + 3;
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }
    if (code[i] === "<" && /[A-Za-z/!]/.test(code[i + 1] ?? "")) {
      const tagMatch = /^<\/?[A-Za-z][A-Za-z0-9-]*/.exec(code.slice(i));
      if (tagMatch !== null) {
        push(tokens, tagMatch[0], "tag");
        i += tagMatch[0].length;
        let inTag = true;
        while (inTag && i < code.length) {
          const c = code[i]!;
          if (c === ">") {
            push(tokens, c, "punctuation");
            i++;
            inTag = false;
          } else if (c === '"' || c === "'") {
            let j = i + 1;
            while (j < code.length && code[j] !== c) {
              j++;
            }
            push(tokens, code.slice(i, Math.min(j + 1, code.length)), "string");
            i = Math.min(j + 1, code.length);
          } else if (/[A-Za-z-]/.test(c)) {
            const attr = /^[A-Za-z-]+/.exec(code.slice(i))![0];
            push(tokens, attr, "attribute");
            i += attr.length;
          } else {
            push(tokens, c, null);
            i++;
          }
        }
        continue;
      }
    }
    push(tokens, code[i]!, null);
    i++;
  }
  return tokens;
}

/** Dockerfile: instruction keywords drive the shape — `ENV`/`ARG`/`LABEL`
 * names read as properties, ALL_CAPS words as constants, heredocs and
 * quoted strings as strings. */
function highlightDockerfile(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  const n = code.length;
  let i = 0;
  while (i < n) {
    const c = code[i]!;

    if (c === "#") {
      let end = code.indexOf("\n", i);
      if (end < 0) {
        end = n;
      }
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }

    // Heredocs: `RUN <<EOF … EOF` — one string through the closing marker.
    if (code.startsWith("<<", i)) {
      const mark = /^<<-?([A-Za-z_][A-Za-z0-9_]*)/.exec(code.slice(i));
      if (mark !== null) {
        const word = mark[1]!;
        let end = code.indexOf("\n" + word, i + mark[0].length);
        end = end < 0 ? n : end + 1 + word.length;
        push(tokens, code.slice(i, end), "string");
        i = end;
        continue;
      }
    }

    if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && code[j] !== c && code[j] !== "\n") {
        j += code[j] === "\\" ? 2 : 1;
      }
      if (j < n && code[j] === c) {
        j++;
      }
      push(tokens, code.slice(i, j), "string");
      i = j;
      continue;
    }

    NUMBER_RE.lastIndex = i;
    const num = NUMBER_RE.exec(code);
    if (num !== null && num[0].length > 0) {
      push(tokens, num[0], "number");
      i += num[0].length;
      continue;
    }

    IDENT_RE.lastIndex = i;
    const ident = IDENT_RE.exec(code);
    if (ident !== null && ident[0].length > 0) {
      const word = ident[0];
      let role: SyntaxRole | null = null;
      if (DOCKERFILE_KW.has(word) || DOCKERFILE_KW.has(word.toUpperCase())) {
        role = "keyword";
      } else {
        let look = i + word.length;
        while (look < n && code[look] === " ") {
          look++;
        }
        if (code[look] === "=") {
          role = "property";
        } else if (/^[A-Z][A-Z0-9_]+$/.test(word)) {
          role = "constant";
        }
      }
      push(tokens, word, role);
      i += word.length;
      continue;
    }

    if ("(){}[]".includes(c)) {
      push(tokens, c, "punctuation");
      i++;
      continue;
    }
    if ("+-*/%=<>!&|^~?:".includes(c)) {
      push(tokens, c, "operator");
      i++;
      continue;
    }
    push(tokens, c, null);
    i++;
  }
  return tokens;
}

/** Make: targets and recipes read as strings; variables, directives, and
 * assignment operators carry the structure. */
function highlightMake(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  for (const line of code.split("\n")) {
    // A leading tab is a recipe: shell text owned by the rule.
    if (/^\t/.test(line)) {
      push(tokens, "\t", null);
      tokenizeMakeText(line.slice(1), "string", false, tokens);
      push(tokens, "\n", null);
      continue;
    }
    const hash = line.indexOf("#");
    const body = hash < 0 ? line : line.slice(0, hash);
    const assign = /^(\s*)([^\s:=#]+)(\s*)(::=|:=|\?=|\+=|!=|=)(.*)$/.exec(body);
    if (assign !== null) {
      push(tokens, assign[1] ?? "", null);
      push(tokens, assign[2] ?? "", "constant");
      push(tokens, assign[3] ?? "", null);
      push(tokens, assign[4] ?? "", "operator");
      tokenizeMakeText(assign[5] ?? "", null, true, tokens);
    } else {
      const target = /^(\s*)([^:=]*?)(\s*):(.*)$/.exec(body);
      if (target !== null) {
        push(tokens, target[1] ?? "", null);
        push(tokens, target[2] ?? "", "string");
        push(tokens, target[3] ?? "", null);
        push(tokens, ":", "punctuation");
        tokenizeMakeText(target[4] ?? "", null, true, tokens);
      } else {
        tokenizeMakeText(body, null, true, tokens);
      }
    }
    if (hash >= 0) {
      push(tokens, line.slice(hash), "comment");
    }
    push(tokens, "\n", null);
  }
  return tokens;
}

/**
 * Scans a make fragment: variable references `$(VAR)` read as constants,
 * `$@`-style automatic variables as variableSpecial; identifiers resolve
 * against the directive keywords. Everything else carries `rest` — plain
 * for make syntax, string inside recipes.
 */
function tokenizeMakeText(
  text: string,
  rest: SyntaxRole | null,
  keywords: boolean,
  tokens: SyntaxToken[],
): void {
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (c === "$") {
      const next = text[i + 1] ?? "";
      if (next === "(" || next === "{") {
        const close = next === "(" ? ")" : "}";
        let j = i + 2;
        while (j < text.length && text[j] !== close) {
          j++;
        }
        const end = j < text.length ? j + 1 : text.length;
        push(tokens, text.slice(i, end), "constant");
        i = end;
        continue;
      }
      if (next !== "" && "@<^?*+%".includes(next)) {
        push(tokens, text.slice(i, i + 2), "variableSpecial");
        i += 2;
        continue;
      }
    }
    if (keywords) {
      IDENT_RE.lastIndex = i;
      const ident = IDENT_RE.exec(text);
      if (ident !== null && ident[0].length > 0) {
        push(tokens, ident[0], MAKE_KW.has(ident[0]) ? "keyword" : rest);
        i += ident[0].length;
        continue;
      }
      if ("(){}[]".includes(c)) {
        push(tokens, c, "punctuation");
        i++;
        continue;
      }
      const op = MAKE_ASSIGN_OPS.find((candidate) => text.startsWith(candidate, i));
      if (op !== undefined) {
        push(tokens, op, "operator");
        i += op.length;
        continue;
      }
    }
    push(tokens, c, rest);
    i++;
  }
}

/** CSS: block comments, at-rules, selectors, and declaration names carry
 * the structure; colors, strings, and unit-suffixed numbers the values. */
function highlightCss(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  const n = code.length;
  let i = 0;
  // Brace stack: `true` means directly inside a declaration block.
  // Selectors and at-rule preludes (media queries and friends) live
  // everywhere else.
  const blocks: boolean[] = [];
  let atRule: string | null = null;
  let afterSelectorColon = false;

  const inDeclarations = (): boolean =>
    blocks.length > 0 && blocks[blocks.length - 1] === true;

  // A `ident:` in selector position — a pseudo-class (`a:hover {`) rather
  // than a feature/value pair (`min-width: 600px`).
  const pseudoColon = (colon: number): boolean => {
    const rest = code.slice(colon + 1);
    const name = /^\s*::?([A-Za-z_-][A-Za-z0-9_-]*)/.exec(rest);
    if (name === null) {
      return false;
    }
    const tail = rest.slice(name[0].length);
    return /^\s*[{(,>+~]/.test(tail) || /^\s+[A-Za-z_.[\]-]/.test(tail);
  };

  while (i < n) {
    const c = code[i]!;

    if (code.startsWith("/*", i)) {
      const close = code.indexOf("*/", i + 2);
      const end = close < 0 ? n : close + 2;
      push(tokens, code.slice(i, end), "comment");
      i = end;
      continue;
    }

    if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && code[j] !== c && code[j] !== "\n") {
        j += code[j] === "\\" ? 2 : 1;
      }
      if (j < n && code[j] === c) {
        j++;
      }
      push(tokens, code.slice(i, j), "string");
      i = j;
      continue;
    }

    // `#hex` colors; other `#name` and `.name` runs are id/class selectors.
    CSS_HEX_RE.lastIndex = i;
    const hex = CSS_HEX_RE.exec(code);
    if (hex !== null) {
      push(tokens, hex[0], "stringSpecial");
      i += hex[0].length;
      continue;
    }
    if (c === "#" || c === ".") {
      CSS_IDENT_RE.lastIndex = i + 1;
      const name = CSS_IDENT_RE.exec(code);
      if (name !== null && name[0].length > 0) {
        push(tokens, c + name[0], "property");
        i += 1 + name[0].length;
        continue;
      }
    }

    NUMBER_RE.lastIndex = i;
    const num = NUMBER_RE.exec(code);
    if (num !== null && num[0].length > 0) {
      push(tokens, num[0], "number");
      i += num[0].length;
      CSS_UNIT_RE.lastIndex = i;
      const unit = CSS_UNIT_RE.exec(code);
      if (unit !== null && unit[0].length > 0) {
        push(tokens, unit[0], "type");
        i += unit[0].length;
      }
      continue;
    }

    if (c === "@") {
      CSS_IDENT_RE.lastIndex = i + 1;
      const word = CSS_IDENT_RE.exec(code);
      if (word !== null && word[0].length > 0) {
        push(tokens, "@" + word[0], "keyword");
        atRule = word[0].toLowerCase();
        i += 1 + word[0].length;
        continue;
      }
    }

    CSS_IDENT_RE.lastIndex = i;
    const ident = CSS_IDENT_RE.exec(code);
    if (ident !== null && ident[0].length > 0) {
      const word = ident[0];
      let look = i + word.length;
      while (look < n && code[look] === " ") {
        look++;
      }
      const next = look < n ? code[look]! : "";
      let role: SyntaxRole | null = null;
      if (word === "from" || word === "to") {
        role = "keyword";
      } else if (word === "and" || word === "or" || word === "not" || word === "only") {
        role = "operator";
      } else if (afterSelectorColon) {
        role = "attribute";
      } else if (next === "(") {
        role = "function";
      } else if (next === ":") {
        role = !inDeclarations() && pseudoColon(look) ? "tag" : "property";
      } else if (next === "=" || ("~^|$*".includes(next) && code[look + 1] === "=")) {
        role = "attribute";
      } else if (
        !inDeclarations() &&
        (next === "{" || next === "," || next === ">" || next === "+" || next === "~")
      ) {
        role = "tag";
      }
      push(tokens, word, role);
      afterSelectorColon = false;
      i += word.length;
      continue;
    }

    if (c === "{" || c === "}") {
      push(tokens, c, "punctuation");
      if (c === "{") {
        // At-rule wrappers (media, keyframes, …) and nested rules open rule
        // blocks; everything else opens a declaration block.
        const rules =
          (atRule !== null && CSS_RULES_AT.has(atRule)) ||
          (blocks.length > 0 && blocks[blocks.length - 1] === true);
        blocks.push(!rules);
      } else {
        blocks.pop();
      }
      atRule = null;
      afterSelectorColon = false;
      i++;
      continue;
    }
    if (c === ":") {
      push(tokens, c, "punctuation");
      CSS_IDENT_RE.lastIndex = i + 1;
      const name = CSS_IDENT_RE.exec(code);
      afterSelectorColon = !inDeclarations() && name !== null && name[0].length > 0;
      i++;
      continue;
    }
    if ("()[];,.#".includes(c)) {
      push(tokens, c, "punctuation");
      if (c === ";") {
        atRule = null;
        afterSelectorColon = false;
      }
      i++;
      continue;
    }
    if (c === "-") {
      // A spaced `-` reads as calc() arithmetic; hyphens inside values and
      // property names stay plain.
      const prev = i > 0 ? code[i - 1]! : "";
      const after = code[i + 1] ?? "";
      const arithmetic = /\s/.test(prev) && after !== "" && !/\s/.test(after);
      push(tokens, c, arithmetic ? "operator" : null);
      i++;
      continue;
    }
    if ("+*/%=<>!&|^~$?".includes(c)) {
      push(tokens, c, "operator");
      i++;
      continue;
    }
    push(tokens, c, null);
    i++;
  }
  return tokens;
}

/** Markdown: lights up the markup roles — headings, fenced and indented
 * code, inline spans, emphasis, links, list markers. */
function highlightMarkdown(code: string): SyntaxToken[] {
  const tokens: SyntaxToken[] = [];
  let fence: { marker: string; length: number } | null = null;
  for (const line of code.split("\n")) {
    if (fence !== null) {
      const closing = /^([`~])[`~]*[ \t]*$/.exec(line);
      if (
        closing !== null &&
        closing[1] === fence.marker &&
        closing[0]!.trim().length >= fence.length
      ) {
        push(tokens, line, "punctuation");
        fence = null;
      } else {
        push(tokens, line, "markupRaw");
      }
      push(tokens, "\n", null);
      continue;
    }
    const opening = /^(`{3,}|~{3,})(.*)$/.exec(line);
    if (opening !== null) {
      fence = { marker: opening[1]![0]!, length: opening[1]!.length };
      push(tokens, opening[1]!, "punctuation");
      push(tokens, opening[2] ?? "", null);
      push(tokens, "\n", null);
      continue;
    }
    const heading = /^(#{1,6})([ \t]+|[ \t]*$)(.*)$/.exec(line);
    if (heading !== null) {
      push(tokens, heading[1]!, "punctuation");
      push(tokens, heading[2] ?? "", null);
      push(tokens, heading[3] ?? "", "markupHeading");
      push(tokens, "\n", null);
      continue;
    }
    if (/^(?:\t| {4,})/.test(line)) {
      push(tokens, line, "markupRaw");
      push(tokens, "\n", null);
      continue;
    }
    const list = /^(\s*)([-*+]|\d+[.)])([ \t]+|[ \t]*$)(.*)$/.exec(line);
    if (list !== null) {
      push(tokens, list[1] ?? "", null);
      push(tokens, list[2]!, "punctuation");
      tokenizeMarkdownInline((list[3] ?? "") + (list[4] ?? ""), tokens);
      push(tokens, "\n", null);
      continue;
    }
    tokenizeMarkdownInline(line, tokens);
    push(tokens, "\n", null);
  }
  return tokens;
}

/** Inline markdown: code spans, emphasis, links. Everything else stays
 * plain — the renderer only colors what it is sure about. */
function tokenizeMarkdownInline(text: string, tokens: SyntaxToken[]): void {
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (c === "`") {
      let run = 1;
      while (text[i + run] === "`") {
        run++;
      }
      let j = i + run;
      let end = -1;
      while (j < text.length) {
        if (text[j] === "`") {
          let closing = 1;
          while (text[j + closing] === "`") {
            closing++;
          }
          if (closing === run) {
            end = j + closing;
            break;
          }
          j += closing;
          continue;
        }
        j++;
      }
      if (end > 0) {
        push(tokens, text.slice(i, end), "markupRaw");
        i = end;
        continue;
      }
      // Unterminated: literal backticks.
      push(tokens, text.slice(i, i + run), null);
      i += run;
      continue;
    }
    if (c === "*") {
      const marker = text.startsWith("**", i) ? "**" : "*";
      const close = text.indexOf(marker, i + marker.length);
      const opens = !/\s/.test(text[i + marker.length] ?? " ");
      const closes = close > 0 && !/\s/.test(text[close - 1] ?? " ");
      if (close > 0 && opens && closes) {
        push(
          tokens,
          text.slice(i, close + marker.length),
          marker === "**" ? "markupStrong" : "markupEmphasis",
        );
        i = close + marker.length;
        continue;
      }
      push(tokens, text.slice(i, i + marker.length), null);
      i += marker.length;
      continue;
    }
    if (c === "[") {
      const label = text.indexOf("]", i + 1);
      if (label > 0) {
        if (text[label + 1] === "(") {
          const destination = text.indexOf(")", label + 2);
          if (destination > 0) {
            push(tokens, text.slice(i, label + 1), "markupReference");
            push(tokens, text.slice(label + 1, destination + 1), "markupLink");
            i = destination + 1;
            continue;
          }
        } else {
          push(tokens, text.slice(i, label + 1), "markupReference");
          i = label + 1;
          continue;
        }
      }
    }
    push(tokens, c, null);
    i++;
  }
}

/** Split tokens into lines for row rendering (the newline ends its line). */
export function splitTokenLines(tokens: readonly SyntaxToken[]): SyntaxToken[][] {
  const lines: SyntaxToken[][] = [[]];
  for (const token of tokens) {
    let text = token.text;
    while (text.length > 0) {
      const nl = text.indexOf("\n");
      if (nl < 0) {
        lines[lines.length - 1]!.push({ text, role: token.role });
        break;
      }
      if (nl > 0) {
        lines[lines.length - 1]!.push({ text: text.slice(0, nl), role: token.role });
      }
      lines.push([]);
      text = text.slice(nl + 1);
    }
  }
  return lines;
}
