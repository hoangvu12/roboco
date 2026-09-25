import { describe, expect, it, vi } from "vitest";
import { getSharedHighlighter } from "@pierre/diffs";
import { findVariant } from "@roboco/theme";
import {
  registerRobocoDiffsTheme,
  robocoDiffsThemeRegistration,
  robocoDiffsThemes,
  ROBOCO_DIFFS_DARK_THEME,
  ROBOCO_DIFFS_LIGHT_THEME,
  SYNTAX_SCOPE_TO_ROLE,
  syntaxRoleVar,
  type SyntaxRole,
} from "../src/lib/pierre-theme";

/**
 * The registered Roboco code theme (ticket 02, web-pierre-adoption): the
 * registration is GENERATED from the same compiled theme-variant source that
 * feeds the theme-artifact freshness gate — every token color reads a
 * `--rb-syntax-*` variable with the artifact's default variant as the
 * fallback literal. Nothing is hand-copied: these assertions hold the
 * derivation against the artifact itself, so a regenerated artifact that
 * changes the palette changes the registration with it.
 */

const ARTIFACT_ROLES = new Set(Object.keys(findVariant("roboco-dark")!.syntax));

describe("robocoDiffsThemeRegistration", () => {
  it("derives every syntax role's color from the artifact variant — var() with the variant literal", () => {
    const dark = findVariant("roboco-dark")!;
    const registration = robocoDiffsThemeRegistration(ROBOCO_DIFFS_DARK_THEME, dark);
    expect(registration.name).toBe(ROBOCO_DIFFS_DARK_THEME);
    expect(registration.type).toBe("dark");
    const byRole = new Map<SyntaxRole, string>();
    for (const entry of registration.tokenColors ?? []) {
      for (const scope of typeof entry.scope === "string" ? [entry.scope] : entry.scope ?? []) {
        // Each scope's role is discoverable through the table; every entry
        // must point at the SAME var() its role resolves to.
        const hit = SYNTAX_SCOPE_TO_ROLE.find(([scopes]) => scopes.includes(scope));
        if (hit !== undefined) {
          byRole.set(hit[1], entry.settings.foreground ?? "");
        }
      }
    }
    for (const [role, fallback] of Object.entries(dark.syntax)) {
      expect(byRole.get(role as SyntaxRole)).toBe(`var(${syntaxRoleVar(role as SyntaxRole)}, ${fallback})`);
    }
  });

  it("covers all 25 artifact syntax roles with the scope table", () => {
    const roles = new Set(SYNTAX_SCOPE_TO_ROLE.map(([, role]) => role));
    expect([...ARTIFACT_ROLES].sort()).toEqual([...roles].sort());
    // And the var naming matches variantCssVars' kebab rule.
    expect(syntaxRoleVar("stringSpecial")).toBe("--rb-syntax-string-special");
    expect(syntaxRoleVar("typeBuiltin")).toBe("--rb-syntax-type-builtin");
    expect(syntaxRoleVar("functionBuiltin")).toBe("--rb-syntax-function-builtin");
  });

  it("reads the workbench fg/bg and the terminal palette from the live tokens", () => {
    const light = findVariant("roboco-light")!;
    const registration = robocoDiffsThemeRegistration(ROBOCO_DIFFS_LIGHT_THEME, light);
    expect(registration.type).toBe("light");
    expect(registration.colors).toMatchObject({
      "editor.foreground": `var(--rb-text, ${light.colors.text})`,
      "editor.background": `var(--rb-bg, ${light.colors.background})`,
      "terminal.ansiGreen": `var(--rb-term-ansi-2, ${light.terminal.ansi[2]})`,
      "terminal.ansiBrightWhite": `var(--rb-term-ansi-15, ${light.terminal.ansi[15]})`,
    });
  });

  it("falls back through the registry when the default variant id is missing", () => {
    // A hand-built variant exercises the derivation without the artifact's
    // exact default ids — the palette still comes from the variant, and a
    // missing role falls back to the variant's text color.
    const registration = robocoDiffsThemeRegistration("probe", {
      id: "probe",
      familyId: "probe",
      name: "Probe",
      appearance: "dark",
      recommendedSurfaceTreatment: "opaque",
      colors: {
        ...findVariant("roboco-dark")!.colors,
        text: "#abcdef",
        background: "#123456",
      },
      accent: findVariant("roboco-dark")!.accent,
      syntax: { comment: "#111111" },
      terminal: findVariant("roboco-dark")!.terminal,
      source: findVariant("roboco-dark")!.source,
    });
    const comment = (registration.tokenColors ?? []).find((entry) => entry.scope?.includes("comment"));
    expect(comment?.settings.foreground).toBe("var(--rb-syntax-comment, #111111)");
    const keyword = (registration.tokenColors ?? []).find((entry) => entry.scope?.includes("keyword"));
    expect(keyword?.settings.foreground).toBe("var(--rb-syntax-keyword, #abcdef)");
    expect(registration.colors?.["editor.foreground"]).toBe("var(--rb-text, #abcdef)");
    expect(registration.colors?.["editor.background"]).toBe("var(--rb-bg, #123456)");
  });
});

describe("registerRobocoDiffsTheme", () => {
  it("registers the pair once per process — a second call is a no-op, not an error", () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      registerRobocoDiffsTheme();
      registerRobocoDiffsTheme();
      expect(errorSpy).not.toHaveBeenCalledWith(
        expect.stringContaining("already registered"),
        expect.anything(),
      );
    } finally {
      errorSpy.mockRestore();
    }
  });

  it("exposes the theme pair the CodeView options consume", () => {
    expect(robocoDiffsThemes()).toEqual({
      dark: ROBOCO_DIFFS_DARK_THEME,
      light: ROBOCO_DIFFS_LIGHT_THEME,
    });
  });

  it("renders through the library's real highlighter: pair token colors are the live vars", async () => {
    // The end-to-end chain: the registered loaders resolve through the
    // library's shared highlighter, and a pair-theme render emits the
    // `--diffs-token-<arm>` custom properties the library's shadow
    // stylesheet consumes (`color: light-dark(…token-light, …token-dark)`).
    registerRobocoDiffsTheme();
    const highlighter = await getSharedHighlighter({
      themes: [robocoDiffsThemes().dark, robocoDiffsThemes().light],
      langs: ["js"],
    });
    const theme = highlighter.getTheme(robocoDiffsThemes().dark);
    expect(theme.fg).toContain("var(--rb-text");
    expect(theme.bg).toContain("var(--rb-bg");
    const hast = highlighter.codeToHast("const x = 1; // hi", {
      lang: "js",
      themes: robocoDiffsThemes(),
      defaultColor: false,
      cssVariablePrefix: "--diffs-token-",
    });
    const json = JSON.stringify(hast);
    expect(json).toContain("--diffs-token-dark:var(--rb-syntax-comment");
    expect(json).toContain("--diffs-token-light:var(--rb-syntax-comment");
    expect(json).toContain("--diffs-token-dark:var(--rb-syntax-number");
    // The un-tokenized remainder rides the pair's fg variables.
    expect(json).toContain("--diffs-token-light:var(--rb-text");
  });
});
