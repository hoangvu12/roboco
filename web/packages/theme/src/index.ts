/**
 * `@roboco/theme` — the Roboco design tokens for the web client.
 *
 * The data under `./generated/` is produced by `roboco-theme-export`
 * (`cargo run -p roboco-theme --bin roboco-theme-export`) from the same Rust
 * sources the desktop compiles: the builtin theme registry, the accent preset
 * derivations, the layout constants (`roboco_proto::layout`), and the motion
 * catalog (`roboco_proto::motion`). A CI gate (`theme-artifact.yml`) fails
 * when the artifact is stale.
 */

import { themeArtifact } from "./generated/index";
import type { AccentPresetId, AccentRoles, ThemeColors, ThemeFamily, ThemeVariant } from "./types";

export * from "./types";
export { themeArtifact };

/** Every builtin family (19), in registry order. */
export const themeFamilies: readonly ThemeFamily[] = themeArtifact.families;

/** Every builtin variant (30), in registry order. */
export const themeVariants: readonly ThemeVariant[] = themeArtifact.families.flatMap(
  (family) => family.variants,
);

/** The selectable accent presets (7) with their authored dark/light bases. */
export const accentPresets = themeArtifact.accentPresets;

/** Spacing ladder, radii, chrome heights, glass alphas (px / unit interval). */
export const layout = themeArtifact.layout;

/** The motion catalog: named curves, specs, and the two springs' parameters. */
export const motion = themeArtifact.motion;

/** Manifest of the bundled Geist/Geist Mono faces (see `../fonts.css`). */
export const fontFaces = themeArtifact.fonts;

/** Look up a builtin variant by id. */
export function findVariant(id: string): ThemeVariant | undefined {
  return themeVariants.find((variant) => variant.id === id);
}

/**
 * The accent roles a variant installs under a selection: the theme-authored
 * accent for `"themeDefault"`, otherwise the precomputed preset derivation
 * (identical to the desktop's `ThemeVariant::accent_for`).
 */
export function accentForVariant(
  variant: ThemeVariant,
  accent: AccentPresetId | "themeDefault" = "themeDefault",
): AccentRoles {
  if (accent === "themeDefault") {
    return variant.accent;
  }
  const roles = themeArtifact.accents[variant.id]?.[accent];
  if (!roles) {
    throw new Error(`no exported accent derivation for ${variant.id} + ${accent}`);
  }
  return roles;
}

const kebab = (name: string): string =>
  name.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();

/**
 * `ThemeColors` key -> CSS custom property suffix. `background` shortens to
 * `--rb-bg`; everything else is the kebab-cased role name.
 */
const COLOR_VARS: Record<keyof ThemeColors, string> = {
  background: "bg",
  shell: "shell",
  raised: "raised",
  raisedHover: "raised-hover",
  card: "card",
  dialog: "dialog",
  overlay: "overlay",
  hover: "hover",
  active: "active",
  border: "border",
  borderStrong: "border-strong",
  text: "text",
  textMuted: "text-muted",
  textFaint: "text-faint",
  textDim: "text-dim",
  solid: "solid",
  onSolid: "on-solid",
  danger: "danger",
  dangerStrong: "danger-strong",
  dangerMuted: "danger-muted",
  warning: "warning",
  warningMuted: "warning-muted",
  success: "success",
  successMuted: "success-muted",
  input: "input",
  cursor: "cursor",
  diffAdd: "diff-add",
  diffDelete: "diff-delete",
  diffHunk: "diff-hunk",
};

export interface VariantCssOptions {
  /** Accent selection; defaults to the theme-authored accent. */
  accent?: AccentPresetId | "themeDefault";
}

/**
 * A variant as CSS custom properties. Naming is systematic: `--rb-<role>`
 * for UI colors (`--rb-bg`, `--rb-text-muted`), `--rb-accent*` for the accent
 * roles, `--rb-syntax-<key>` for syntax, `--rb-term-*` for the terminal
 * palette. Colors are `#rrggbb`/`#rrggbbaa` strings, ready to assign.
 */
export function variantCssVars(
  variant: ThemeVariant,
  options: VariantCssOptions = {},
): Record<string, string> {
  const vars: Record<string, string> = {};
  for (const [key, suffix] of Object.entries(COLOR_VARS) as [keyof ThemeColors, string][]) {
    vars[`--rb-${suffix}`] = variant.colors[key];
  }
  const accent = accentForVariant(variant, options.accent);
  vars["--rb-accent"] = accent.primary;
  vars["--rb-accent-strong"] = accent.strong;
  vars["--rb-accent-wash"] = accent.wash;
  vars["--rb-on-accent"] = accent.on;
  vars["--rb-selection"] = accent.selection;
  vars["--rb-caret"] = accent.caret;
  vars["--rb-activity"] = accent.activity;
  vars["--rb-glyph-light"] = accent.glyph[0];
  vars["--rb-glyph-mid"] = accent.glyph[1];
  vars["--rb-glyph-deep"] = accent.glyph[2];
  for (const [key, color] of Object.entries(variant.syntax)) {
    vars[`--rb-syntax-${kebab(key)}`] = color;
  }
  vars["--rb-term-bg"] = variant.terminal.background;
  vars["--rb-term-fg"] = variant.terminal.foreground;
  vars["--rb-term-selection"] = variant.terminal.selection;
  variant.terminal.ansi.forEach((color, index) => {
    vars[`--rb-term-ansi-${index}`] = color;
  });
  return vars;
}

/** Apply a variant's CSS custom properties to an element's inline style. */
export function applyThemeVariant(
  element: HTMLElement,
  variant: ThemeVariant,
  options: VariantCssOptions = {},
): void {
  for (const [name, value] of Object.entries(variantCssVars(variant, options))) {
    element.style.setProperty(name, value);
  }
}

/** Layout constants as px-valued CSS custom properties. */
export function layoutCssVars(): Record<string, string> {
  const { space, radius, chrome } = layout;
  const px = (value: number): string => `${value}px`;
  return {
    "--rb-space-xs": px(space.xs),
    "--rb-space-sm": px(space.sm),
    "--rb-space-md": px(space.md),
    "--rb-space-lg": px(space.lg),
    "--rb-text-stack-gap": px(space.textStackGap),
    "--rb-radius-bubble": px(radius.bubble),
    "--rb-radius-panel": px(radius.panel),
    "--rb-radius-control": px(radius.control),
    "--rb-header-height": px(chrome.headerHeight),
    "--rb-titlebar-height": px(chrome.titlebarHeight),
    "--rb-titlebar-top-pad": px(chrome.titlebarTopPad),
    "--rb-status-strip-height": px(chrome.statusStripHeight),
    "--rb-transcript-fade-band": px(chrome.transcriptFadeBand),
  };
}

/**
 * The motion catalog as CSS custom properties: `--rb-ease-<curve>` holds the
 * `cubic-bezier(...)` value, `--rb-motion-<spec>` the duration in ms, and
 * delayed specs additionally expose `--rb-motion-<spec>-delay`.
 */
export function motionCssVars(): Record<string, string> {
  const vars: Record<string, string> = {};
  for (const [name, [x1, y1, x2, y2]] of Object.entries(motion.curves)) {
    vars[`--rb-ease-${kebab(name)}`] = `cubic-bezier(${x1}, ${y1}, ${x2}, ${y2})`;
  }
  for (const spec of motion.specs) {
    vars[`--rb-motion-${kebab(spec.name)}`] = `${spec.durationMs}ms`;
    if (spec.delayMs > 0) {
      vars[`--rb-motion-${kebab(spec.name)}-delay`] = `${spec.delayMs}ms`;
    }
  }
  return vars;
}
