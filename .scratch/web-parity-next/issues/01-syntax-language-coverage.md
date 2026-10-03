# 01 — Syntax highlighting covers the desktop's language set

**What to build:** The web's code-fence tokenizer covers 18 languages plus
json/yaml/html special cases; the desktop's tree-sitter set covers 28
(`crates/syntax/src/lib.rs:806-833`). Eight languages currently fall through
to `highlightGeneric` (comments/strings only): **css, toml, markdown, jsonc,
dockerfile, lua, nix, make** — css and markdown fences are common in
assistant output and render unhighlighted. This ticket registers all eight
(and mirrors the desktop's alias table), using the existing engine: no
library, no lazy loading.

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` §1; the per-language
token specs below are implementation-ready.

**Desktop reference (for lookups only):**
`crates/syntax/src/lib.rs:806-833` (the 28-language set),
`:799-841` (`language_for_alias` — the alias table to mirror),
`:379-387` (injected_languages — all of them highlight in markdown fences),
`:60-92` (HighlightKind ↔ the web's SyntaxRole).

## 1. The engine's registration recipe

`web/packages/app/src/lib/syntax.ts` is a dependency-free, hand-rolled
single-pass tokenizer (its header, :1-8). Consumers: markdown.tsx:22,
diff-view.tsx:39, files/code-view.tsx:12 — static imports, so **zero bundle
strategy changes**.

- `SyntaxRole` (syntax.ts:9-42) mirrors the desktop's HighlightKind; every
  role already has a `.tk-*` CSS rule (app.css:10125-10168) — including the
  never-emitted `markupHeading/markupRaw/markupEmphasis/markupStrong/…` that
  the markdown tokenizer below will light up for free.
- **Spec-based languages** register in `LANGUAGES` (syntax.ts:115-137,
  interface at :53-60: `{ lineComments, blockComments, keywords, templateString? }`)
  and ride `highlightCLike` (:194-287): comments → strings (with `\` escapes;
  non-backtick strings die at newline) → numbers (NUMBER_RE :152) →
  identifiers (keyword / boolean / call-heuristic / property / type /
  constant) → punctuation → operators.
- **Dedicated functions** dispatch from `highlightCode` (:166-183) BEFORE
  the spec lookup on the raw label — the `highlightJson` (:290-328) /
  `highlightYaml` (:331-359) / `highlightMarkup` (:362-…) precedent.
- Fence label arrives verbatim (markdown.ts:270-276); `highlightCode`
  lowercases and alias-resolves (:140-144, :167) — **registering in
  LANGUAGES/ALIASES is the only change; no renderer edit.**

## 2. Per-language token specs

| Lang | Route | Spec |
| --- | --- | --- |
| **toml** | `LANGUAGES` | lineComments `#`; strings basic/literal/multiline; numbers incl. inf/nan; no keywords (bare table keys → type, pair keys → property comes free from the `=`-context heuristics) |
| **jsonc** | dedicated `highlightJsonc` (or a comment-aware flag on highlightJson) | `//` + `/* */` comments; `"…"` strings with escapes; NUMBER_RE; keys → property; `true/false/null` → boolean. **Plain `json` must stay comment-free** — keep them distinct |
| **lua** | `LANGUAGES` + long-string arm | lineComments `--`, blockComments `--[[ ]]`; `[[…]]` long strings; hex/float numbers; keywords `and break do else elseif end for function goto if in local nil not or repeat return then true until while` |
| **nix** | `LANGUAGES` + `''` string arm | lineComments `#`, blockComments `/* */`; `"…"` and `''…''` strings; numbers; keywords `if then else let inherit in rec with assert or` |
| **dockerfile** | dedicated fn (keyword-driven) | lineComments `#`; strings incl. heredocs; keywords `FROM AS RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL MAINTAINER`; `ENV X=` names → property; ALL_CAPS → constant |
| **make** | dedicated fn | lineComments `#`; targets/recipes → string; keywords `ifeq ifneq ifdef ifndef else endif foreach define endef export unexport override private include sinclude vpath`; `$(VAR)` → constant, `$@ $<` → variableSpecial; `= := ?= +=` → operator |
| **css** | dedicated fn | `/* */` comments only; `"…"` `'…'` strings (die at newline); numbers with unit suffixes; at-rules `@media @import @charset @namespace @supports @keyframes` + any `@word` → keyword; `to from` → keyword; `and or not only` → operator; selectors/property names → property or tag; colors `#hex` → stringSpecial |
| **markdown** | dedicated fn (emits the existing unused markup roles) | `#{1,6}` headings → markupHeading; fenced/indented blocks + `` `spans` `` → markupRaw; `*em*` → markupEmphasis, `**strong**` → markupStrong; link destinations → markupLink, `[labels]` → markupReference; list markers → punctuation |

## 3. Aliases to mirror (desktop `language_for_alias`, lib.rs:799-841)

Add to `ALIASES` (syntax.ts:140-144): `docker`, `makefile`, `console` →
bash, `python3` → python, `htm` → html, `cc` → cpp, `jsonc` (own dispatch,
NOT an alias to json — comments differ). `md: "markdown"` already points at
a spec that this ticket finally registers.

## 4. Tests

Create `web/packages/app/tests/syntax.test.ts` (none exists — verified):
pure `highlightCode` assertions, one case per language exercising every
category (e.g. css: `/* dark */ .btn:hover { color: #0f0f0f; }` → comment/
property/punctuation/number/stringSpecial; toml: `# deps\n[tool]\nname =
"roboco"` → comment/punctuation/type/property/string; markdown: `# T\n**b**
*i* \`c\` [x](u)` → the four markup roles + link). Plus: alias cases
(`"Dockerfile"`, `"docker"`, `"makefile"`, `"MAKE"` case-insensitivity),
`"jsonc"` vs `"json"` comment presence, and the existing generic fallback
for an unknown label stays untouched.

## 5. Acceptance checklist

- [x] All 8 languages tokenized; markdown lights up the markup roles
- [x] Aliases mirror the desktop table (incl. console→bash, python3)
- [x] json stays comment-free; jsonc comments
- [x] `tests/syntax.test.ts` covers every category per language
- [x] Full app suite green

## Comments

**Implemented and reviewed** (branch `ticket/wpn-01-syntax-language-coverage`, commits `d33ce339` → review fixes on top):

- All eight languages registered in `web/packages/app/src/lib/syntax.ts` — toml/lua/nix as `LANGUAGES` specs (new `longStrings`/`configKeys`/`numberWords` knobs), css/markdown/dockerfile/make as dedicated functions, jsonc as a comment-aware sibling of json. Zero renderer edits, zero imports added.
- Verification: `tests/syntax.test.ts` 11/11 (one block per language exercising every §2 category, alias mirror, jsonc-vs-json, untouched generic fallback); `pnpm exec tsc --noEmit` clean; full app suite `pnpm exec vitest run` → **147 files / 2204 tests green**; consumer suites (markdown, markdown-doc, diff, diff-file-open, files, files-line-reveal, file-document) green.
- Regression proof: old (f375fcb0) vs new `highlightCode` compared across every pre-existing label — byte-identical everywhere; only `md`/`htm` (new dispatch) and `json` literals (boolean fix, required by the jsonc row) differ. 86 spec-table probes + all 31 desktop aliases probed as resolving identically to their canonical labels.
- Two-axis code review passed (Standards: stale SyntaxRole contract comment fixed, duplicated string scanner extracted, `tokenizeMakeText`'s fallback param renamed; Spec: nothing missing). Documented deviations, desktop-faithful by design: Lua `nil`/`true` color as boolean (not keyword), make assignment LHS → constant, `!=`/`::=` operators included, css pseudo-classes → attribute and units → type per the desktop's css query.
- Known residual edges (conservative tokenizer, cosmetic only): css context heuristics on rare constructs (keyframes names render tag, media-feature values attribute), nix `'''` escapes and Lua `[=[` levels unhandled, TOML literal string ending in a backslash over-scans, markdown `***both***` renders plain.
