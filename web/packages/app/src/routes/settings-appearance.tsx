import { accentPresets, findVariant, themeFamilies, type Appearance } from "@roboco/theme";
import { appearanceStore, useAppearance, useSystemAppearance } from "../state/appearance";
import {
  accentHelper,
  accentSwatchColor,
  appearanceModeLabel,
  resolveAppearance,
  resolveSurfaceTreatment,
  resolveVariantId,
  surfaceHelper,
  surfaceLabel,
  APPEARANCE_MODES,
  SURFACE_PREFERENCES,
  type AccentSelection,
  type SurfacePreference,
} from "../lib/appearance-store";

/**
 * Appearance settings (desktop settings/appearance.rs parity, web scope):
 * the appearance mode cards, independent light/dark variant selectors, the
 * accent preset swatches, and the glass surface choices. Everything applies
 * live and persists in this browser's storage — the desktop's "These
 * settings stay on this device." Builtin variants only (spec §Theme scope).
 */
export function AppearanceSettingsPage() {
  const preferences = useAppearance();
  const system = useSystemAppearance();
  const resolved = resolveAppearance(preferences.mode, system);
  const variant = findVariant(resolveVariantId(preferences, resolved));
  const surfaceResolved = variant !== undefined ? resolveSurfaceTreatment(preferences.surface, variant) : "opaque";

  return (
    <div className="settings-page">
      <h1 className="settings-title">Appearance</h1>
      <p className="settings-subtitle">Choose how Roboco looks. These settings stay in this browser.</p>

      <div className="settings-option-row" role="radiogroup" aria-label="Appearance mode">
        {APPEARANCE_MODES.map((mode) => (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={preferences.mode === mode}
            className={`option-card ${preferences.mode === mode ? "option-card-selected" : ""}`}
            onClick={() => appearanceStore.setMode(mode)}
          >
            {appearanceModeLabel(mode)}
          </button>
        ))}
      </div>

      <section className="settings-card">
        <VariantRow appearance="light" label="Light theme" value={preferences.lightVariant} />
        <VariantRow appearance="dark" label="Dark theme" value={preferences.darkVariant} />
        <div className="settings-row">
          <div className="settings-row-main">
            <span className="settings-row-title">Accent color</span>
            <span className="settings-row-meta">{accentHelper(preferences.accent)}</span>
          </div>
          <div className="swatch-row" role="radiogroup" aria-label="Accent color">
            <AccentSwatch choice="themeDefault" selected={preferences.accent === "themeDefault"} variantId={resolveVariantId(preferences, resolved)} />
            {accentPresets.map((preset) => (
              <AccentSwatch
                key={preset.id}
                choice={preset.id}
                selected={preferences.accent === preset.id}
                variantId={resolveVariantId(preferences, resolved)}
              />
            ))}
          </div>
        </div>
        <div className="settings-row">
          <div className="settings-row-main">
            <span className="settings-row-title">Glass</span>
            <span className="settings-row-meta">{surfaceHelper(preferences.surface, surfaceResolved)}</span>
          </div>
          <div className="choice-row" role="radiogroup" aria-label="Surface treatment">
            {SURFACE_PREFERENCES.map((surface: SurfacePreference) => (
              <button
                key={surface}
                type="button"
                role="radio"
                aria-checked={preferences.surface === surface}
                className={`choice ${preferences.surface === surface ? "choice-selected" : ""}`}
                onClick={() => appearanceStore.setSurface(surface)}
              >
                {surfaceLabel(surface)}
              </button>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

function VariantRow({ appearance, label, value }: { appearance: Appearance; label: string; value: string }) {
  const families = themeFamilies
    .map((family) => ({ ...family, variants: family.variants.filter((variant) => variant.appearance === appearance) }))
    .filter((family) => family.variants.length > 0);
  return (
    <div className="settings-row">
      <div className="settings-row-main">
        <span className="settings-row-title">{label}</span>
        <span className="settings-row-meta">Used whenever this appearance is active.</span>
      </div>
      <select
        className="input settings-select"
        aria-label={label}
        value={value}
        onChange={(event) => appearanceStore.setVariant(appearance, event.target.value)}
      >
        {families.map((family) => (
          <optgroup key={family.id} label={family.name}>
            {family.variants.map((variant) => (
              <option key={variant.id} value={variant.id}>
                {variant.name}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
    </div>
  );
}

function AccentSwatch({
  choice,
  selected,
  variantId,
}: {
  readonly choice: AccentSelection;
  readonly selected: boolean;
  readonly variantId: string;
}) {
  const variant = findVariant(variantId);
  const color = variant !== undefined ? accentSwatchColor(choice, variant) : "transparent";
  const label = choice === "themeDefault" ? "Theme default" : (accentPresets.find((preset) => preset.id === choice)?.label ?? choice);
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      title={label}
      aria-label={label}
      className={`swatch ${selected ? "swatch-selected" : ""}`}
      style={{ background: color }}
      onClick={() => appearanceStore.setAccent(choice)}
    />
  );
}
