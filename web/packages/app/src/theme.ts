import {
  applyThemeVariant,
  findVariant,
  layout,
  layoutCssVars,
  motionCssVars,
  type Appearance,
} from "@roboco/theme";
import {
  DEFAULT_APPEARANCE,
  resolveAppearance,
  resolveSurfaceTreatment,
  resolveVariantId,
  type AppearancePreferences,
} from "./lib/appearance-store";

/**
 * Install the appearance preferences on the document root: the resolved
 * variant's color roles under the selected accent, the layout constants,
 * the motion catalog, and the glass alphas as `--rb-*` custom properties;
 * the resolved surface treatment lands on `data-surface` so the CSS can
 * thin floating surfaces into frosted glass. Re-called on every preference
 * or OS-appearance change — application is idempotent, so "live" costs a
 * style recalculation only.
 */
export function applyAppearanceToDocument(
  preferences: AppearancePreferences = DEFAULT_APPEARANCE,
  system: Appearance = "dark",
  root: HTMLElement = document.documentElement,
): void {
  const appearance = resolveAppearance(preferences.mode, system);
  const variantId = resolveVariantId(preferences, appearance);
  const variant = findVariant(variantId) ?? findVariant(DEFAULT_APPEARANCE.darkVariant);
  if (variant === undefined) {
    throw new Error(`Unknown theme variant: ${variantId}`);
  }
  applyThemeVariant(root, variant, { accent: preferences.accent });
  for (const [name, value] of Object.entries({ ...layoutCssVars(), ...motionCssVars() })) {
    root.style.setProperty(name, value);
  }
  root.style.setProperty("--rb-glass-card-alpha", String(layout.glass.cardAlpha));
  root.style.setProperty(
    "--rb-glass-overlay-alpha",
    String(variant.appearance === "dark" ? layout.glass.overlayAlphaDark : layout.glass.overlayAlphaLight),
  );
  for (const [name, value] of Object.entries(inkCssVars(variant.appearance))) {
    root.style.setProperty(name, value);
  }
  root.dataset.surface = resolveSurfaceTreatment(preferences.surface, variant);
  root.style.colorScheme = variant.appearance;
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
