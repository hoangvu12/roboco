/**
 * The new-thread background hero's pure geometry — the web port of
 * `crates/ui/src/shell.rs:694-914` (`new_thread_background`,
 * `new_thread_background_height`, `new_thread_background_opacity`) and
 * `crates/ui/src/new_thread_background_mask.rs` (the two-pass feathered
 * cutout) plus `new_thread_background_effects.rs::Readiness` (the 120 ms
 * artwork fade-in).
 *
 * Everything is expressed in the shared window space, exactly like the
 * desktop's `Bounds<Pixels>`: the hero rect and the composer's measured
 * surface rect are both viewport coordinates, so the mask geometry ports
 * 1:1 (`tests/new-thread-background.test.ts` mirrors the mask module's
 * tests). The component converts to hero-local pixels only when it emits
 * CSS.
 *
 * This module references no theme roles at all: the mask multiplies source
 * alpha and nothing else, so the artwork resolves into the real canvas
 * (translucent themes included) with no theme-coloured overlay bleaching or
 * darkening it.
 */

import type { UiSettingsStore } from "../state/ui-settings";
import { idbBackgroundBlobStore, type BackgroundBlobStore } from "./background-blob-store";

// ---------------------------------------------------------------------------
// Constants (shell.rs:694-699, mask.rs:8)
// ---------------------------------------------------------------------------

/** Frosted themes show the hero at 0.84; opaque themes at 1.0. */
export const NEW_THREAD_BACKGROUND_FROSTED_OPACITY = 0.84;
/** The hero covers the top 72% of the viewport… */
export const NEW_THREAD_BACKGROUND_VIEWPORT_RATIO = 0.72;
/** …but never more than 760px. */
export const NEW_THREAD_BACKGROUND_MAX_HEIGHT = 760;
/** The reveal pass's opacity (mask.rs:8): half-strength artwork softening the cutout's contrast. */
export const CUTOUT_REVEAL_OPACITY = 0.5;
/** The pill's corner radius — `COMPOSER_RADIUS` (composer.rs:62). */
export const HERO_MASK_RADIUS = 26;
/** Hard transparency margin around the composer's rounded rect (mask.rs:41). */
export const HERO_MASK_CLEARANCE = 8;
/** The reveal pass's feather — 1px, a no-op ramp. */
export const REVEAL_FEATHER = 1;

/** A rect in the shared window space. */
export interface Rect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

// ---------------------------------------------------------------------------
// Geometry (shell.rs:844-855)
// ---------------------------------------------------------------------------

/** `new_thread_background_opacity` (shell.rs:844-850). */
export function newThreadBackgroundOpacity(isFrost: boolean): number {
  return isFrost ? NEW_THREAD_BACKGROUND_FROSTED_OPACITY : 1;
}

/** `new_thread_background_height` (shell.rs:852-855): `min(max(vh,0)·0.72, 760)`. */
export function newThreadBackgroundHeight(viewportHeight: number): number {
  return Math.min(Math.max(viewportHeight, 0) * NEW_THREAD_BACKGROUND_VIEWPORT_RATIO, NEW_THREAD_BACKGROUND_MAX_HEIGHT);
}

// ---------------------------------------------------------------------------
// The mask geometry (new_thread_background_mask.rs:12-47)
// ---------------------------------------------------------------------------

/** The per-pass mask parameters, ported field for field. */
export interface HeroMaskGeometry {
  /** The mask's own rect (the cleared hole for the cutout pass; parked below the image for the reveal). */
  readonly bounds: Rect;
  readonly radius: number;
  readonly feather: number;
  readonly clearance: number;
  /** Shared by both passes: `(hero.bottom, hero height)`. */
  readonly bottomFade: { readonly end: number; readonly height: number };
}

function bottom(rect: Rect): number {
  return rect.y + rect.height;
}

function rectEquals(a: Rect, b: Rect): boolean {
  return a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height;
}

/**
 * `mask(hero, composer, cutout)` (mask.rs:12-47). The cutout pass clears a
 * rounded-rect hole at the composer's live bounds — bounds = the "cleared"
 * rect (composer origin/width, bottom extended to the hero's bottom so a
 * taller image must not fade back in beneath the composer's rounded lower
 * edge), `radius = COMPOSER_RADIUS`, `feather = clamp(hero height × 0.52,
 * 120, 280)`, `clearance = 8`. The reveal pass parks its exclusion rect
 * entirely below the image so it has only the shared bottom fade.
 */
export function heroMaskGeometry(hero: Rect, composer: Rect, cutout: boolean): HeroMaskGeometry {
  const height = hero.height;
  // Keep the cleared area open through the hero's bottom.
  const cleared: Rect = {
    x: composer.x,
    y: composer.y,
    width: composer.width,
    height: Math.max(bottom(composer), bottom(hero)) - composer.y,
  };
  const parked: Rect = {
    x: hero.x,
    y: bottom(hero) + 1,
    width: hero.width,
    height: hero.height,
  };
  return {
    bounds: cutout ? cleared : parked,
    radius: cutout ? HERO_MASK_RADIUS : 0,
    feather: cutout ? Math.min(Math.max(height * 0.52, 120), 280) : REVEAL_FEATHER,
    clearance: cutout ? HERO_MASK_CLEARANCE : 0,
    // Start fading at the image's top, rather than holding full opacity
    // through its first 40% and compressing the transition near the bottom.
    // Both passes use the full height, independently of the softer cutout.
    bottomFade: { end: bottom(hero), height: Math.max(height, 1) },
  };
}

/**
 * The cutout hole as the CSS mask consumes it: the cleared rect expanded by
 * the `clearance` margin (the SDF's `d ≤ clearance` region is exactly the
 * rounded rect offset outward by 8 — the expanded rect's corner radius is
 * `radius + clearance`), in HERO-LOCAL pixels. The blurred rounded rect in
 * the SVG mask approximates the shader's smoothstep ramp over `feather`.
 */
export function heroCutoutHole(
  hero: Rect,
  composer: Rect,
): { readonly x: number; readonly y: number; readonly width: number; readonly height: number; readonly radius: number } {
  const mask = heroMaskGeometry(hero, composer, true);
  const bounds = mask.bounds;
  return {
    x: bounds.x - hero.x - mask.clearance,
    y: bounds.y - hero.y - mask.clearance,
    width: bounds.width + 2 * mask.clearance,
    height: bounds.height + 2 * mask.clearance,
    radius: mask.radius + mask.clearance,
  };
}

/** The Gaussian σ that best matches the shader's smoothstep ramp of `feather` width. */
export function featherSigma(feather: number): number {
  return feather / 2.563;
}

/**
 * Build the cutout pass's CSS mask as an SVG data URI. CSS `mask-image`
 * consumes ALPHA, so the document paints one white (opaque = visible) rect
 * through an internal SVG `<mask>` whose luminance semantics erase the
 * blurred hole — the result carries a soft-edged transparent hole exactly
 * where the composer's rounded rect sits. Regenerated from the live
 * composer geometry every frame, exactly as the desktop's paint-time mask
 * is.
 */
export function cutoutMaskDataUri(hero: Rect, composer: Rect): string {
  const hole = heroCutoutHole(hero, composer);
  const sigma = featherSigma(heroMaskGeometry(hero, composer, true).feather);
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${hero.width}" height="${hero.height}" viewBox="0 0 ${hero.width} ${hero.height}">` +
    `<defs>` +
    `<filter id="b" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="${sigma.toFixed(2)}"/></filter>` +
    `<mask id="m">` +
    `<rect width="${hero.width}" height="${hero.height}" fill="#ffffff"/>` +
    `<rect x="${hole.x.toFixed(2)}" y="${hole.y.toFixed(2)}" width="${hole.width.toFixed(2)}" height="${hole.height.toFixed(2)}" rx="${hole.radius.toFixed(2)}" fill="#000000" filter="url(#b)"/>` +
    `</mask>` +
    `</defs>` +
    `<rect width="${hero.width}" height="${hero.height}" fill="#ffffff" mask="url(#m)"/>` +
    `</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

/**
 * The shared bottom-fade gradient stops, with smoothstep values at the
 * quarter points (t=.25 → .156, .5 → .5, .75 → .844) — the ticket's CSS
 * mapping of `alpha *= smoothstep(0, hero_height, hero.bottom − y)` across
 * the hero's entire height, on BOTH passes.
 */
export const BOTTOM_FADE_GRADIENT =
  "linear-gradient(to bottom, rgba(0,0,0,1) 0%, rgba(0,0,0,0.156) 25%, rgba(0,0,0,0.5) 50%, rgba(0,0,0,0.844) 75%, rgba(0,0,0,0) 100%)";

/** Rect equality for the hero's re-measure guards. */
export { rectEquals };

// ---------------------------------------------------------------------------
// Artwork readiness (new_thread_background_effects.rs::Readiness, :11-32)
// ---------------------------------------------------------------------------

/**
 * The artwork's 120 ms fade-in, restarting only when the image id changes
 * (the same artwork does not re-fade); reduced motion snaps to 1. Exactly
 * 0.5 at 60 ms (`smoothstep(0.5)`), asserted by the desktop's test at
 * effects.rs:328-330.
 */
export class Readiness {
  #id: string | number | null = null;
  #startMs = 0;

  opacity(imageId: string | number | null, reduced: boolean, nowMs: number): number {
    if (imageId === null) {
      this.#id = null;
      return 0;
    }
    if (this.#id !== imageId) {
      this.#id = imageId;
      this.#startMs = nowMs;
    }
    if (reduced) {
      return 1;
    }
    const t = Math.min(Math.max((nowMs - this.#startMs) / 120, 0), 1);
    return t * t * (3 - 2 * t);
  }
}

// ---------------------------------------------------------------------------
// Artwork resolution (settings.rs:62-93 + the decode contract)
// ---------------------------------------------------------------------------

/** The bundled fallback's public URL — served from the app bundle. */
export const DEFAULT_NEW_THREAD_BACKGROUND_URL = "/backgrounds/default-new-thread-background.png";

/**
 * A background is only "available" when one is installed AND its file still
 * exists (shell.rs:5848-5859 resolves setting-path or default). The web's
 * managed copy lives in IndexedDB (`lib/background-blob-store.ts`); the
 * setting's `path` names it as `idb:new-thread-composer-background`, and an
 * entry only counts when it actually decodes — `createImageBitmap` accepts
 * only what really decodes, which is the decode-by-sniffing contract (SVG is
 * allowed as an ATTACHMENT but must be rejected as a background;
 * `createImageBitmap` rejects SVG blobs).
 */
export async function decodeBackgroundBlob(blob: Blob): Promise<boolean> {
  try {
    await createImageBitmap(blob);
    return true;
  } catch {
    return false;
  }
}

/**
 * Resolve the artwork to paint: the setting's managed blob if it decodes,
 * else the bundled default. A `path` that is not the managed key is fetched
 * as a URL (a same-session object URL from a pre-managed install) so legacy
 * stored paths keep resolving until they are replaced.
 */
export async function resolveNewThreadBackground(
  setting: { readonly path: string; readonly name: string } | null,
  defaultUrl: string = DEFAULT_NEW_THREAD_BACKGROUND_URL,
  blobs: BackgroundBlobStore = idbBackgroundBlobStore(),
): Promise<string> {
  const installed = await resolveInstalledBackground(setting, blobs);
  return installed ?? defaultUrl;
}

/**
 * The installed background's URL, or null when nothing is installed or the
 * stored entry no longer decodes — the Appearance row's "Image unavailable"
 * state and the effect row's gate.
 */
export async function resolveInstalledBackground(
  setting: { readonly path: string; readonly name: string } | null,
  blobs: BackgroundBlobStore = idbBackgroundBlobStore(),
): Promise<string | null> {
  if (setting === null) {
    return null;
  }
  if (setting.path === NEW_THREAD_BACKGROUND_IDB_PATH) {
    try {
      return await blobs.url();
    } catch {
      return null;
    }
  }
  try {
    const response = await fetch(setting.path);
    if (response.ok && (await decodeBackgroundBlob(await response.blob()))) {
      return setting.path;
    }
  } catch {
    // Not installed (or unreachable) — the caller falls back.
  }
  return null;
}

// ---------------------------------------------------------------------------
// Install / remove (settings.rs:305-386)
// ---------------------------------------------------------------------------

/** settings.rs:309-313 — the decode rejection's copy, verbatim. */
export const BACKGROUND_UNSUPPORTED_MESSAGE =
  "This background image is unsupported or damaged. Choose a valid image such as PNG or JPEG.";
/** settings.rs:332/344 — the persistence failure's copy, verbatim. */
export const BACKGROUND_SAVE_MESSAGE = "Unable to save the image. Check folder permissions and try again.";
/** settings.rs:371 — the removal failure's copy, verbatim. */
export const BACKGROUND_REMOVE_MESSAGE = "Unable to remove the image. Check folder permissions and try again.";

/** The settings field's `path` while the managed IndexedDB copy backs it. */
export const NEW_THREAD_BACKGROUND_IDB_PATH = "idb:new-thread-composer-background";

/** The stores an install/remove touches; injectable for tests. */
export interface BackgroundDeps {
  readonly settings: UiSettingsStore;
  readonly blobs: BackgroundBlobStore;
}

/**
 * `install_new_thread_composer_background` (settings.rs:305-362): reject a
 * file that does not decode (never persist the candidate), stage the managed
 * copy, then flip the setting — and on any staging failure leave the
 * previous background exactly as it was. The web's staging IS the blob put,
 * so the desktop's save-then-retire ordering collapses to: decode → put →
 * point (the settings write itself is atomic in `UiSettingsStore`).
 */
export async function installNewThreadBackground(file: File, deps: BackgroundDeps): Promise<string | null> {
  if (!(await decodeBackgroundBlob(file))) {
    return BACKGROUND_UNSUPPORTED_MESSAGE;
  }
  try {
    await deps.blobs.put(file);
  } catch {
    return BACKGROUND_SAVE_MESSAGE;
  }
  deps.settings.updateImmediate({
    newThreadComposerBackground: { path: NEW_THREAD_BACKGROUND_IDB_PATH, name: file.name },
  });
  return null;
}

/**
 * `remove_new_thread_composer_background` (settings.rs:364-386): clear the
 * field first, retire the managed blob after — a failed retirement leaves a
 * stray invisible blob, never a setting pointing at a deleted image.
 */
export async function removeNewThreadBackground(deps: BackgroundDeps): Promise<string | null> {
  if (deps.settings.getSnapshot().newThreadComposerBackground === null) {
    return null;
  }
  deps.settings.updateImmediate({ newThreadComposerBackground: null });
  try {
    await deps.blobs.delete();
  } catch {
    return BACKGROUND_REMOVE_MESSAGE;
  }
  return null;
}
