/**
 * The registered Roboco code theme for the Pierre diffs library
 * (web-pierre-adoption, ticket 02) — the shared seam ticket 05's read-only
 * file viewer reuses.
 *
 * **One source of truth.** The theme is GENERATED from the same compiled
 * theme-variant source that feeds the theme-artifact freshness gate: every
 * token color is a `var(--rb-syntax-<kebab>)` reference resolved at paint
 * time against the tokens `applyThemeVariant` installs on the document root,
 * with the fallback literal derived from the artifact's default variant for
 * the appearance. No color is hand-copied — the artifact IS the palette, so
 * the `theme-artifact.yml` gate keeps proving the code theme is fresh, and a
 * theme family or appearance switch re-colors the code surfaces live with no
 * re-registration.
 *
 * Registration shape: two names, `roboco-dark`/`roboco-light`, exposed as the
 * library's `{dark, light}` theme pair. Both arms read the SAME live tokens
 * (the app paints exactly one variant at a time), so the pair's job is not
 * two palettes — it is the library's appearance-following contract: a pair
 * theme leaves the shadow tree's `color-scheme` to the `themeType` option
 * (the resolved appearance), which flips every `light-dark()` in the
 * library's stylesheet along with the app. The two registrations differ only
 * in their fallback literals (the artifact's default dark vs light variant).
 *
 * Scope mapping: the fixed `SYNTAX_SCOPE_TO_ROLE` table maps TextMate scopes
 * onto the 25 syntax roles the theme artifact defines — the same vocabulary
 * `variantCssVars` emits as `--rb-syntax-*` and the `.tk-*` rules consume.
 * The library's grammars resolve against it; our hand-rolled tokenizer's
 * heuristics are gone with ticket 04.
 */

import { registerCustomTheme, type ThemeRegistration } from "@pierre/diffs";
import { findVariant, themeVariants, type ThemeVariant } from "@roboco/theme";
import { DEFAULT_APPEARANCE } from "./appearance-store";

/** The registered theme names — the pair `robocoDiffsThemes()` hands over. */
export const ROBOCO_DIFFS_DARK_THEME = "roboco-dark";
export const ROBOCO_DIFFS_LIGHT_THEME = "roboco-light";

/** The library theme pair: same live tokens, appearance following. */
export function robocoDiffsThemes(): { dark: typeof ROBOCO_DIFFS_DARK_THEME; light: typeof ROBOCO_DIFFS_LIGHT_THEME } {
  return { dark: ROBOCO_DIFFS_DARK_THEME, light: ROBOCO_DIFFS_LIGHT_THEME };
}

// ---------------------------------------------------------------------------
// The TextMate scope → syntax-role table (all 25 artifact roles)
// ---------------------------------------------------------------------------

/** The theme artifact's syntax role vocabulary (`ThemeVariant.syntax` keys). */
export type SyntaxRole =
  | "comment"
  | "keyword"
  | "string"
  | "stringSpecial"
  | "escape"
  | "number"
  | "boolean"
  | "type"
  | "typeBuiltin"
  | "constructor"
  | "function"
  | "functionBuiltin"
  | "macro"
  | "property"
  | "constant"
  | "variable"
  | "variableSpecial"
  | "parameter"
  | "operator"
  | "punctuation"
  | "tag"
  | "attribute"
  | "label"
  | "embedded"
  | "invalid";

/**
 * TextMate scopes → syntax roles. TextMate resolution prefers the rule whose
 * selector matches the deeper scope, so broad entries (`keyword`,
 * `punctuation`, `variable.other`) may precede their specific kin
 * (`keyword.operator`, `variable.other.property`) without stealing them.
 * The markdown entries mirror the app's own `.tk-markup*` aliases so a
 * `.md` diff reads like the app's markdown renderer.
 */
export const SYNTAX_SCOPE_TO_ROLE: readonly (readonly [scopes: readonly string[], role: SyntaxRole])[] = [
  [["comment", "punctuation.definition.comment"], "comment"],
  [["keyword", "storage", "entity.name.tag.yaml", "keyword.other.template", "punctuation.definition.template-expression"], "keyword"],
  [["string", "markup.fenced_code", "markup.inline", "markup.raw"], "string"],
  [["string.regexp", "string.interpolated", "string.template", "string.unquoted", "string.quoted.docstring.multi"], "stringSpecial"],
  [["constant.character.escape"], "escape"],
  [["constant.numeric"], "number"],
  [["constant.language"], "boolean"],
  [["entity.name.type", "entity.other.inherited-class", "entity.name.class", "entity.name.struct", "entity.name.enum", "entity.name.interface", "entity.name.namespace", "meta.type.name"], "type"],
  [["support.type", "support.class"], "typeBuiltin"],
  [["entity.name.function.constructor", "meta.instance.constructor"], "constructor"],
  [["entity.name.function", "support.function", "meta.function-call"], "function"],
  [["support.function.builtin"], "functionBuiltin"],
  [["entity.name.function.macro", "support.function.macro", "entity.name.macro"], "macro"],
  [["variable.other.property", "variable.other.object.property", "meta.property-name", "support.type.property-name"], "property"],
  [["constant.other", "constant.other.symbol", "variable.other.constant", "entity.name.constant"], "constant"],
  [["variable.other"], "variable"],
  [["variable.language"], "variableSpecial"],
  [["variable.parameter"], "parameter"],
  [["keyword.operator"], "operator"],
  [["punctuation", "meta.brace"], "punctuation"],
  [["entity.name.tag"], "tag"],
  [["entity.other.attribute-name"], "attribute"],
  [["entity.name.label"], "label"],
  [["meta.embedded"], "embedded"],
  [["invalid"], "invalid"],
  // Markdown — the app's `.tk-markup*` aliases (app.css): headings and bold
  // read as keywords, raw/inline code as strings, links as tags.
  [["markup.heading", "markup.bold", "markup.strong"], "keyword"],
  [["markup.italic", "markup.emphasis", "markup.underline.link", "meta.link"], "tag"],
];

/** `stringSpecial` → `--rb-syntax-string-special` (the `--rb-*` kebab rule). */
export function syntaxRoleVar(role: SyntaxRole): string {
  return `--rb-syntax-${role.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase()}`;
}

/** The `--rb-*` workbench tokens the registration's editor colors read. */
const EDITOR_FOREGROUND_VAR = "--rb-text";
const EDITOR_BACKGROUND_VAR = "--rb-bg";

/** `terminal.ansi*` color keys in index order (0–15). */
const ANSI_COLOR_KEYS: readonly string[] = [
  "terminal.ansiBlack",
  "terminal.ansiRed",
  "terminal.ansiGreen",
  "terminal.ansiYellow",
  "terminal.ansiBlue",
  "terminal.ansiMagenta",
  "terminal.ansiCyan",
  "terminal.ansiWhite",
  "terminal.ansiBrightBlack",
  "terminal.ansiBrightRed",
  "terminal.ansiBrightGreen",
  "terminal.ansiBrightYellow",
  "terminal.ansiBrightBlue",
  "terminal.ansiBrightMagenta",
  "terminal.ansiBrightCyan",
  "terminal.ansiBrightWhite",
];

/** A `var(--rb-…, <fallback>)` color — the token, with the variant's literal. */
function tokenColor(token: string, fallback: string): string {
  return `var(${token}, ${fallback})`;
}

/**
 * The Roboco code theme registration for one appearance's fallback variant —
 * pure: every color reads a `--rb-*` token with the variant's value as the
 * fallback literal. Shiki accepts CSS color functions in theme settings
 * (its own CSS-variables theme works this way), so the var() strings flow
 * through tokenization into the rendered spans' inline styles and resolve
 * against whatever variant the app has installed.
 */
export function robocoDiffsThemeRegistration(name: string, fallback: ThemeVariant): ThemeRegistration {
  const syntax = fallback.syntax;
  const tokenColors: ThemeRegistration["tokenColors"] = SYNTAX_SCOPE_TO_ROLE.map(([scopes, role]) => {
    const fallbackColor = syntax[role];
    return {
      scope: [...scopes],
      settings: {
        // An artifact variant always authors every role; the raw literal
        // only guards a hand-built (test) variant that omits one.
        foreground: tokenColor(syntaxRoleVar(role), fallbackColor ?? fallback.colors.text),
      },
    };
  });
  const colors: Record<string, string> = {
    "editor.foreground": tokenColor(EDITOR_FOREGROUND_VAR, fallback.colors.text),
    "editor.background": tokenColor(EDITOR_BACKGROUND_VAR, fallback.colors.background),
  };
  fallback.terminal.ansi.forEach((color, index) => {
    const key = ANSI_COLOR_KEYS[index];
    if (key !== undefined) {
      colors[key] = tokenColor(`--rb-term-ansi-${index}`, color);
    }
  });
  return {
    name,
    type: fallback.appearance,
    colors,
    tokenColors,
  };
}

// ---------------------------------------------------------------------------
// Registration (once per process)
// ---------------------------------------------------------------------------

let registered = false;

/**
 * Register the Roboco code theme pair with the diffs library's shared
 * highlighter — idempotent, safe at module scope. The fallback literals come
 * from the artifact's default variants (`DEFAULT_APPEARANCE`'s selection),
 * so the gate-regenerated artifact stays the palette's source even for the
 * fallbacks.
 */
export function registerRobocoDiffsTheme(): void {
  if (registered) {
    return;
  }
  registered = true;
  const fallbacks = {
    dark: findVariant(DEFAULT_APPEARANCE.darkVariant) ?? defaultVariantFor("dark"),
    light: findVariant(DEFAULT_APPEARANCE.lightVariant) ?? defaultVariantFor("light"),
  };
  for (const [name, appearance] of [
    [ROBOCO_DIFFS_DARK_THEME, "dark"],
    [ROBOCO_DIFFS_LIGHT_THEME, "light"],
  ] as const) {
    const fallback = fallbacks[appearance];
    registerCustomTheme(name, async () => robocoDiffsThemeRegistration(name, fallback));
  }
}

/** The artifact's first variant of an appearance (registry order). */
function defaultVariantFor(appearance: "dark" | "light"): ThemeVariant {
  const match =
    appearance === "dark"
      ? themeVariants.find((variant) => variant.appearance === "dark")
      : themeVariants.find((variant) => variant.appearance === "light");
  // The artifact always carries both; the ?? branch only guards a
  // hand-built test artifact.
  return match ?? themeVariants[0]!;
}
