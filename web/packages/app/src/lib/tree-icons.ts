import {
  createFileTreeIconResolver,
  getBuiltInSpriteSheet,
  type FileTreeIcons,
  type FileTreeSortEntry,
} from "@pierre/trees";
import type { Appearance } from "@roboco/theme";

/**
 * The file-type icon seam on the trees library's built-in set — the
 * replacement for the ported VS Code manifest (`lib/file-icons.ts`, the
 * `@roboco/icons` generated manifest, and `public/file-icons/**`, all
 * deleted with ticket 06).
 *
 * One icon vocabulary now serves both consumers:
 *
 * - the tree passes `treeFileIcons` through the model's `icons` option
 *   (`set: "complete"`, `colored: true`, plus targeted per-name and
 *   per-extension remaps for the names the built-in set lacks);
 * - non-tree `FileIcon` consumers resolve through the same configuration
 *   via `createFileTreeIconResolver` and render `<use>` references into a
 *   document-level sprite built from `getBuiltInSpriteSheet("complete")`.
 *
 * The built-in symbols are single-hue `currentColor` glyphs: inside the
 * tree the library colors them per token through its own `--trees-*`
 * system; outside it (markdown file references, tool stat rows, diff
 * headers, the viewer breadcrumb, the mention popup) they render as
 * monochrome glyphs tinted by the surrounding text color — the
 * appearance-driven asset split of the old manifest does not exist here,
 * and ADR 0008 accepts the divergence.
 */

/** The app's tree row density (the old tree's 27px rows). */
export const FILE_TREE_ROW_HEIGHT = 27;
/** The density factor matching 27px rows against the library's 30px default. */
export const FILE_TREE_DENSITY = 0.9;

/** The basename of the synthetic "Load more…" pagination row (see tree-adapters). */
export const LOAD_MORE_BASENAME = "Load more…";

/**
 * Targeted remaps on top of the complete set: the Rust build files, the
 * lock files the set has no glyph for, `makefile`/`license` (the set's
 * LICENSE entry is cased for its extension table, so basenames miss), the
 * `toml` extension, and the load-more row's ellipsis glyph.
 */
const FILE_NAME_REMAPS: Record<string, string> = {
  "cargo.toml": "file-tree-builtin-rust",
  "cargo.lock": "file-tree-icon-lock",
  "package-lock.json": "file-tree-icon-lock",
  "pnpm-lock.yaml": "file-tree-icon-lock",
  "yarn.lock": "file-tree-icon-lock",
  makefile: "file-tree-builtin-text",
  license: "file-tree-builtin-text",
  licence: "file-tree-builtin-text",
  [LOAD_MORE_BASENAME]: "file-tree-icon-ellipsis",
};

const FILE_EXTENSION_REMAPS: Record<string, string> = {
  toml: "file-tree-builtin-text",
};

/** The icon configuration shared by the tree model and standalone `FileIcon`s. */
export const treeFileIcons: FileTreeIcons = {
  set: "complete",
  colored: true,
  byFileName: FILE_NAME_REMAPS,
  byFileExtension: FILE_EXTENSION_REMAPS,
};

/** The resolver the tree itself uses internally, exposed for non-tree consumers. */
const resolver = createFileTreeIconResolver(treeFileIcons);

/** The custom folder glyph used by standalone `FileIcon` directory rows. */
const FOLDER_SYMBOL_ID = "file-tree-icon-folder";

/**
 * One folder symbol appended to the document sprite — the tree itself
 * renders directories with its chevron-only look, but the standalone
 * `FileIcon` still needs a folder glyph for directory rows (the mention
 * popup). Same 16×16 `currentColor` conventions as the built-ins.
 */
const FOLDER_SYMBOL = `<svg data-icon-sprite aria-hidden="true" width="0" height="0" style="position:absolute">
  <symbol id="${FOLDER_SYMBOL_ID}" viewBox="0 0 16 16">
    <path fill="currentColor" opacity=".45" d="M2.5 13V3.5A1 1 0 0 1 3.5 2.5h2.67a1.5 1.5 0 0 1 1.06.44l.56.56a.5.5 0 0 0 .36.15H12.5a1 1 0 0 1 1 1V13a.5.5 0 0 1-.5.5H3a.5.5 0 0 1-.5-.5Z" class="bg"/>
    <path fill="currentColor" d="M2.5 4.6c0-.33.27-.6.6-.6h9.8c.33 0 .6.27.6.6v2.8a.6.6 0 0 1-.6.6H3.1a.6.6 0 0 1-.6-.6Z" class="fg"/>
  </symbol>
</svg>`;

/** The complete-set sprite plus the folder glyph, for the document-level sprite host. */
export function fileIconSpriteSheet(): string {
  return `${getBuiltInSpriteSheet("complete")}\n${FOLDER_SYMBOL}`;
}

/** A resolved icon for a non-tree consumer: a `<use>` symbol id plus its token. */
export interface StandaloneIcon {
  readonly name: string;
  readonly token?: string;
}

/** Resolve the icon a file (or symlink — resolved by name) row should show. */
export function resolveStandaloneFileIcon(path: string): StandaloneIcon {
  const resolved = resolver.resolveIcon("file-tree-icon-file", path);
  return { name: resolved.name, token: resolved.token };
}

/** The folder glyph for directory rows (no per-name folder icons in the tree). */
export function standaloneDirectoryIcon(): StandaloneIcon {
  return { name: FOLDER_SYMBOL_ID };
}

/**
 * The stricter check prose renderers use to decide whether a bare filename
 * mention deserves a decorative icon: true only when resolution would
 * return something other than the generic file glyph (the old
 * `has_specific_file_icon`).
 */
export function hasSpecificFileIcon(path: string): boolean {
  const resolved = resolver.resolveIcon("file-tree-icon-file", path);
  return resolved.name !== "file-tree-icon-file" && resolved.name !== "file-tree-builtin-default";
}

/**
 * `well_bg` — a neutral backdrop a caller can place behind an icon so it
 * stays legible regardless of the surrounding surface (kept verbatim from
 * the old `lib/file-icons.ts`; markdown file references and tool badges
 * still use it).
 */
export function wellBg(appearance: Appearance, frosted: boolean): string {
  const alpha = frosted ? 0.32 : 0.16;
  return appearance === "dark" ? `rgb(0 0 0 / ${alpha})` : `rgb(255 255 255 / ${alpha})`;
}

/**
 * The tree's sibling ordering, shaped like the library's default: walk the
 * shared path segments, directories before files at the first differing
 * segment, then case-insensitive name — with the load-more marker forced
 * after its real siblings (the old tree's `compareEntries` order).
 */
export function treeSortComparator(left: FileTreeSortEntry, right: FileTreeSortEntry): number {
  if (left.path === right.path) {
    return 0;
  }
  const leftMarker = left.basename === LOAD_MORE_BASENAME;
  const rightMarker = right.basename === LOAD_MORE_BASENAME;
  if (leftMarker !== rightMarker) {
    // A marker trails every real sibling sharing its directory; against
    // other subtrees the segment walk below keeps the order total.
    const marker = leftMarker ? left : right;
    const other = leftMarker ? right : left;
    if (parentDirectoryOf(marker) === parentDirectoryOf(other)) {
      return leftMarker ? 1 : -1;
    }
  }
  const shared = Math.min(left.segments.length, right.segments.length);
  for (let depth = 0; depth < shared; depth += 1) {
    const leftSegment = left.segments[depth]!;
    const rightSegment = right.segments[depth]!;
    if (leftSegment === rightSegment) {
      continue;
    }
    const leftKind = kindAtDepth(left, depth);
    const rightKind = kindAtDepth(right, depth);
    if (leftKind !== rightKind) {
      return leftKind === 1 ? -1 : 1;
    }
    const bySegment = leftSegment.toLowerCase().localeCompare(rightSegment.toLowerCase());
    if (bySegment !== 0) {
      return bySegment;
    }
    return leftSegment < rightSegment ? -1 : 1;
  }
  if (left.segments.length !== right.segments.length) {
    return left.segments.length < right.segments.length ? -1 : 1;
  }
  if (left.isDirectory !== right.isDirectory) {
    return left.isDirectory ? -1 : 1;
  }
  return left.path < right.path ? -1 : 1;
}

/** A path's kind at one depth: ancestors are directories; only the final segment can be a file. */
function kindAtDepth(entry: FileTreeSortEntry, depth: number): 0 | 1 {
  return depth === entry.segments.length - 1 && !entry.isDirectory ? 0 : 1;
}

/** The directory segment of a sort entry (canonical or file path, both forms). */
function parentDirectoryOf(entry: FileTreeSortEntry): string {
  const cut = entry.path.length - entry.basename.length - 1;
  return cut > 0 ? entry.path.slice(0, cut) : "";
}
