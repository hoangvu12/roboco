import { applyThemeVariant, findVariant, layoutCssVars, motionCssVars } from "@roboco/theme";

/** The desktop's default dark variant (settings.rs ThemeSelection::default). */
export const DEFAULT_DARK_VARIANT_ID = "roboco-dark";

/**
 * Install a theme variant on the document root: color roles, accent,
 * syntax, terminal palette, layout constants, and the motion catalog as
 * `--rb-*` custom properties. Appearance preferences (ticket 16) will
 * re-call this with the selected variant.
 */
export function installThemeVariant(variantId: string = DEFAULT_DARK_VARIANT_ID): void {
  const variant = findVariant(variantId);
  if (variant === undefined) {
    throw new Error(`Unknown theme variant: ${variantId}`);
  }
  const root = document.documentElement;
  applyThemeVariant(root, variant);
  for (const [name, value] of Object.entries({ ...layoutCssVars(), ...motionCssVars() })) {
    root.style.setProperty(name, value);
  }
  root.style.colorScheme = variant.appearance;
}
