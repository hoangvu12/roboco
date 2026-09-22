import { Tooltip } from "./ui/Tooltip";
import { TOOLTIP_VIEW_OPTIONS_MS } from "./ui/Tooltip";
import { monogramCss, monogramLetter, monogramTone } from "../lib/monogram";
import { useResolvedAppearance } from "../state/appearance";

/**
 * Project monograms — the web peer of `crates/ui/src/shell/project_icon.rs`
 * (upstream 1ec74e40 → 378a1945): a project's initial on a small frosted
 * tile, tone from the curated palette (`lib/monogram.ts`), with the
 * pull-request-badge-style tooltip card below replaced by the app's label
 * tooltip naming the project and (from b58af627/378a1945) its owning
 * device. The web has no repository artwork surface, so the monogram IS
 * the project icon; it follows the row's selected/hover state — the
 * desktop's `selected`/`group_hover` tint strengthening.
 */

/**
 * One monogram tile. `active` is the row's selected state: the active row
 * wears the hover tint permanently (upstream 378a1945).
 */
export function ProjectMonogram({
  name,
  seed,
  active = false,
}: {
  readonly name: string;
  readonly seed: string;
  readonly active?: boolean;
}) {
  const appearance = useResolvedAppearance();
  const tone = monogramTone(seed);
  const variant: "light" | "dark" = appearance === "dark" ? "dark" : "light";
  // 8% plate / 85% letter at rest; 24% plate / full letter on the active
  // row — emitted as CSS variables so the row's hover/selected states can
  // strengthen the tint without a re-render (app.css's `.project-monogram`
  // rules pick the pair).
  const style = {
    "--rb-mono-plate": monogramCss(tone, variant, 0.08),
    "--rb-mono-plate-active": monogramCss(tone, variant, 0.24),
    "--rb-mono-ink": monogramCss(tone, variant, 0.85),
    "--rb-mono-ink-active": monogramCss(tone, variant, 1),
  } as React.CSSProperties;
  return (
    <span className="project-monogram" data-active={active ? "1" : undefined} style={style}>
      {monogramLetter(name)}
    </span>
  );
}

/**
 * The project icon frame (project_icon.rs's `project_icon_frame`): the
 * monogram with a 350ms tooltip naming the project and its device — the
 * b58af627/378a1945 device tooltip, web-shaped as the label tooltip.
 */
export function ProjectIconMark({
  name,
  seed,
  device,
  active = false,
}: {
  readonly name: string;
  readonly seed: string;
  readonly device: string;
  readonly active?: boolean;
}) {
  return (
    <Tooltip
      label={`${name} — ${device}`}
      delay={TOOLTIP_VIEW_OPTIONS_MS}
      trigger={
        <span className="project-icon-mark">
          <ProjectMonogram name={name} seed={seed} active={active} />
        </span>
      }
    />
  );
}
