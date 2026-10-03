import {
  applyThemeVariant,
  layout,
  layoutCssVars,
  motionCssVars,
  type Appearance,
} from "@roboco/theme";
import {
  DEFAULT_APPEARANCE,
  effectiveCodeFontFamily,
  effectiveUiFontFamily,
  fontFamilyStack,
  resolveAppearance,
  resolveSurfaceTreatment,
  resolveVariantId,
  type AppearancePreferences,
} from "./lib/appearance-store";
import { findVariantAnywhere } from "./lib/theme-library";
import { uiSettings } from "./state/ui-settings";
import { mediaPrefersReducedMotion } from "./lib/reduced-motion";
import { resolveReducedMotion } from "./lib/appearance-store";
import {
  deriveAccentRoles,
  mixHex,
  type AccentRoles,
  type ThemeVariant,
} from "@roboco/theme";

/**
 * Install the appearance preferences on the document root: the resolved
 * variant's color roles under the selected accent, the layout constants,
 * the motion catalog, and the glass alphas as `--rb-*` custom properties;
 * the resolved surface treatment lands on `data-surface` so the CSS can
 * thin floating surfaces into frosted glass. Re-called on every preference
 * or OS-appearance change — application is idempotent, so "live" costs a
 * style recalculation only.
 */
/**
 * The wallpaper-derived overlay (upstream #598's `tint_variant`, web-shaped):
 * the surfaces mix toward the artwork's dominant colour, and the interactive
 * roles re-derive from it through the same contrast-aware accent derivation.
 * User theme/accent selections stay intact — disabling the overlay restores
 * them untouched.
 */
function wallpaperTint(
  variant: ThemeVariant,
  color: string,
  appearance: Appearance,
): { variant: ThemeVariant; accentRoles: AccentRoles } {
  const dark = appearance === "dark";
  const tint = mixHex(color, dark ? "#000000" : "#ffffff", dark ? 0.88 : 0.94);
  const mixSurface = (value: string): string => mixHex(value, tint, 0.4);
  const tinted: ThemeVariant = {
    ...variant,
    colors: {
      ...variant.colors,
      background: mixSurface(variant.colors.background),
      shell: mixSurface(variant.colors.shell),
      raised: mixSurface(variant.colors.raised),
      card: mixSurface(variant.colors.card),
      dialog: mixSurface(variant.colors.dialog),
      overlay: mixSurface(variant.colors.overlay),
      input: mixSurface(variant.colors.input),
    },
    terminal: {
      ...variant.terminal,
      background: mixSurface(variant.terminal.background),
    },
  };
  const accentRoles = deriveAccentRoles(color, appearance, tinted.colors.background);
  return {
    variant: {
      ...tinted,
      colors: {
        ...tinted.colors,
        hover: withAlphaHex(accentRoles.primary, 0.09),
        active: withAlphaHex(accentRoles.primary, 0.15),
        border: withAlphaHex(accentRoles.primary, 0.14),
        borderStrong: withAlphaHex(accentRoles.primary, 0.3),
      },
      terminal: {
        ...tinted.terminal,
        selection: accentRoles.selection,
      },
    },
    accentRoles,
  };
}

function withAlphaHex(hex: string, alpha: number): string {
  const value = Math.round(Math.min(Math.max(alpha, 0), 1) * 255);
  return `${hex}${value.toString(16).padStart(2, "0")}`;
}

export function applyAppearanceToDocument(
  preferences: AppearancePreferences = DEFAULT_APPEARANCE,
  system: Appearance = "dark",
  root: HTMLElement = document.documentElement,
): void {
  const appearance = resolveAppearance(preferences.mode, system);
  const variantId = resolveVariantId(preferences, appearance);
  // The registry union: an installed custom-library variant resolves here
  // too, so a stored selection survives the reload it was persisted for.
  const variant = findVariantAnywhere(variantId) ?? findVariantAnywhere(DEFAULT_APPEARANCE.darkVariant);
  if (variant === undefined) {
    throw new Error(`Unknown theme variant: ${variantId}`);
  }
  // The wallpaper overlay owns the interactive roles while enabled: the
  // variant is cloned and tinted, its accent re-derived from the artwork.
  const settings = uiSettings.getSnapshot();
  if (settings.wallpaperThemeColors && settings.wallpaperColor !== null) {
    const { variant: tinted, accentRoles } = wallpaperTint(
      variant,
      settings.wallpaperColor,
      appearance,
    );
    applyThemeVariant(root, tinted, { accentRoles });
  } else {
    applyThemeVariant(root, variant, { accent: preferences.accent });
  }
  for (const [name, value] of Object.entries({ ...layoutCssVars(), ...motionCssVars() })) {
    root.style.setProperty(name, value);
  }
  root.style.setProperty("--rb-glass-card-alpha", String(layout.glass.cardAlpha));
  // The gradient matrix spinner's fixed "sunrise" row tints (GSPIN_ROW_TINTS,
  // proto/motion.rs:34) — the documented non-theme color exception: NOT
  // accent-derived, identical in every variant.
  root.style.setProperty("--rb-gspin-row-0", "#B6D3EF");
  root.style.setProperty("--rb-gspin-row-1", "#EDB185");
  root.style.setProperty("--rb-gspin-row-2", "#F888A0");
  const dark = variant.appearance === "dark";
  root.style.setProperty(
    "--rb-glass-overlay-alpha",
    String(dark ? layout.glass.overlayAlphaDark : layout.glass.overlayAlphaLight),
  );
  // The three derived alphas that scale a neutral rather than naming a color
  // (crates/ui/src/theme.rs: glass_selected_bg/card_selected_bg, band, scrim).
  // Selection scales `--rb-wash`; band and scrim always sit on literal black.
  root.style.setProperty(
    "--rb-selected-wash-alpha",
    String(dark ? layout.glass.selectedWashAlphaDark : layout.glass.selectedWashAlphaLight),
  );
  root.style.setProperty(
    "--rb-band-alpha",
    String(dark ? layout.glass.bandAlphaDark : layout.glass.bandAlphaLight),
  );
  root.style.setProperty(
    "--rb-scrim-alpha",
    String(dark ? layout.glass.scrimAlphaDark : layout.glass.scrimAlphaLight),
  );
  for (const [name, value] of Object.entries(inkCssVars(variant.appearance))) {
    root.style.setProperty(name, value);
  }
  root.dataset.surface = resolveSurfaceTreatment();
  root.style.colorScheme = variant.appearance;
  // The resolved reduced-motion flag (upstream #642): the stylesheet's
  // `@media (prefers-reduced-motion: reduce)` blocks still follow the OS,
  // qualified with `:not([data-reduced-motion="off"])` so an "off" pin
  // suppresses them, and `:root[data-reduced-motion="on"]` applies the same
  // rules when the pin (or a following system) asks for less motion.
  root.dataset.reducedMotion = resolveReducedMotion(
    preferences.reduceMotion,
    mediaPrefersReducedMotion(),
  )
    ? "on"
    : "off";
}

/**
 * The interface typography (typography.rs:189-318): the chosen family on
 * `--rb-font-sans` (the variable `body` consumes) and the chosen size on
 * `--rb-ui-size` (the baseline text scales with it — 14px designed at the
 * 16px default, `ui_rems(14)` at a root of `size`). Called on boot and on
 * every settings write; the font-picker block itself stays on
 * `--rb-font-sans-fixed` so the control never renders in a font it just
 * broke.
 *
 * The independent code/diff slot (upstream #374) lands on `--rb-font-mono`
 * (the variable `.mono` consumes) and `--rb-code-size` (the shared code
 * size the per-surface baselines scale from, lib/typography.ts). The
 * terminal slot bypasses CSS entirely — XTerm reads options, so
 * `terminal/store.tsx` applies it.
 */
export function applyTypographyToDocument(
  typography: {
    readonly uiFontFamily: string;
    readonly uiFontSize: number;
    readonly codeFontFamily?: string;
    readonly codeFontSize?: number;
  },
  root: HTMLElement = document.documentElement,
): void {
  root.style.setProperty("--rb-font-sans", fontFamilyStack(effectiveUiFontFamily(typography.uiFontFamily)));
  root.style.setProperty("--rb-ui-size", String(typography.uiFontSize));
  if (typography.codeFontFamily !== undefined) {
    root.style.setProperty(
      "--rb-font-mono",
      fontFamilyStack(effectiveCodeFontFamily(typography.codeFontFamily)),
    );
  }
  if (typography.codeFontSize !== undefined) {
    root.style.setProperty("--rb-code-size", String(typography.codeFontSize));
  }
}

/**
 * The transcript's content column cap (`transcript_width`, upstream cbf2ad84)
 * on `--rb-transcript-width` — the variable `.trow-col` consumes. The
 * composer's 768px column stays independent, as on the desktop.
 */
export function applyConversationWidthToDocument(
  width: number,
  root: HTMLElement = document.documentElement,
): void {
  root.style.setProperty("--rb-transcript-width", `${width}px`);
}

/**
 * `INK_HAIRLINE_SCALE` — a 1px line needs *more* ink on a bright field than a
 * plate does, so hairlines scale up in light mode where fills do not.
 */
const INK_HAIRLINE_SCALE = 1.35;

/**
 * The desktop's three neutral ladders, as `rgb()` channel triples plus the
 * light-mode hairline scale (`crates/ui/src/theme.rs`):
 *
 * - `ink` — soft-white/black fills for chips and plates.
 * - `wash` — an ink softened short of pure black or white, so hover and
 *   selection read as tinted glass rather than paint.
 * - `hairline` — borders, dividers, and rings.
 *
 * They are tone-flipped, not accent-tinted: selection on the desktop is a
 * neutral wash over the vibrancy, and reaching for an accent role here is what
 * made the web's selected row read as a purple slab. Call sites write
 * `rgb(var(--rb-wash) / 0.11)`.
 */
export function inkCssVars(appearance: Appearance): Record<string, string> {
  const dark = appearance === "dark";
  return {
    "--rb-ink": dark ? "255 255 255" : "0 0 0",
    "--rb-wash": dark ? "235 235 235" : "26 26 26",
    "--rb-hairline": dark ? "255 255 255" : "0 0 0",
    // Dark fills and hairlines use the authored alpha as-is; light scales.
    "--rb-ink-scale": "1",
    "--rb-hairline-scale": dark ? "1" : String(INK_HAIRLINE_SCALE),
  };
}
