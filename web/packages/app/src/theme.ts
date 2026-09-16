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
  root.dataset.surface = resolveSurfaceTreatment(preferences.surface, variant);
  root.style.colorScheme = variant.appearance;
}
