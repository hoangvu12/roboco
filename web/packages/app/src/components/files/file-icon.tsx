import type { CSSProperties } from "react";
import { fileIconSpriteSheet, resolveStandaloneFileIcon, standaloneDirectoryIcon } from "../../lib/tree-icons";

/**
 * `FileIcon` — the file-type icon on the trees library's built-in set
 * (ticket 06). The tree renders the same set inside its shadow DOM; this
 * component serves the non-tree consumers (markdown file references, tool
 * stat rows and badges, diff file headers, the viewer breadcrumb, the
 * mention popup) by resolving through the shared icon configuration and
 * rendering a `<use>` reference into a document-level sprite built from
 * `getBuiltInSpriteSheet("complete")`.
 *
 * The built-in symbols are single-hue `currentColor` glyphs, so an icon
 * tints with the `color` of whatever it sits in — unlike the old manifest's
 * polychrome `<img>` assets (one set per appearance) or the monochrome
 * `@roboco/icons` `Icon`. Directories render the folder glyph from the same
 * sprite (the tree itself uses its own chevron-only folder look).
 */

export type FileIconKind = "file" | "directory" | "symlink";

export interface FileIconProps {
  /** The entry kind; symlinks resolve by filename like files (no target kind). */
  readonly kind: FileIconKind;
  /** The name or path to resolve — basenames win, extensions follow. */
  readonly name: string;
  /** Default 14 — the row/stat/badge icon size across the consumers. */
  readonly size?: number;
  readonly className?: string;
  readonly style?: CSSProperties;
}

/** The document-level sprite host id (injected once, on first render). */
const SPRITE_HOST_ID = "roboco-file-icons-sprite";

let spriteInjected = false;

/** Inject the built-in sprite (plus the folder glyph) once per document. */
function ensureFileIconSprite(): void {
  if (spriteInjected || typeof document === "undefined") {
    return;
  }
  spriteInjected = true;
  if (document.getElementById(SPRITE_HOST_ID) !== null) {
    return;
  }
  const host = document.createElement("div");
  host.id = SPRITE_HOST_ID;
  host.setAttribute("aria-hidden", "true");
  // Off-stage and zero-sized, like the library's own sprite: symbols only
  // render at their `<use>` sites.
  host.style.position = "absolute";
  host.style.width = "0";
  host.style.height = "0";
  host.style.overflow = "hidden";
  // The sprite is build-time output from the trees library, not user or
  // engine content.
  host.innerHTML = fileIconSpriteSheet();
  document.body.appendChild(host);
}

export function FileIcon({ kind, name, size = 14, className, style }: FileIconProps) {
  ensureFileIconSprite();
  const icon = kind === "directory" ? standaloneDirectoryIcon() : resolveStandaloneFileIcon(name);
  return (
    <svg
      className={className}
      style={style}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      aria-hidden
      focusable="false"
    >
      <use href={`#${icon.name}`} />
    </svg>
  );
}
