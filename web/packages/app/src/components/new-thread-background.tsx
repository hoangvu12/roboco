import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { SurfaceTreatment } from "@roboco/theme";
import type { NewThreadBackgroundEffect } from "../state/ui-settings";
import {
  cutoutMaskRaster,
  newThreadBackgroundElementOpacity,
  newThreadBackgroundHeight,
  type Rect,
} from "../lib/new-thread-background";
import { effectRaster, type RasterData } from "../lib/new-thread-background-effects";
import { useResolvedAppearance } from "../state/appearance";

/**
 * The new-thread background hero — the web port of `shell.rs:857-914`
 * (`new_thread_background`) over `new_thread_background_mask.rs`'s two-pass
 * feathered cutout.
 *
 * Two stacked CANVAS passes paint the same cover-fit artwork (the desktop's
 * `paint` fit math: max scale, centered, `corner_radii` 0, no grayscale —
 * drawn at the device-pixel ratio): the REVEAL pass at opacity 0.5 whose
 * alpha grid carries only the shared bottom fade, and the CUTOUT pass at
 * opacity 1 whose grid is the single combined `min(hole, fade)` mask — the
 * desktop's one shader, so the hole and the fade never multiply. The hole
 * is the exact smoothstep-over-SDF ramp (a hard 8px transparent margin,
 * then the 120–280px one-sided dome), not a Gaussian. No blend mode, no
 * theme overlay: the masks multiply source alpha and nothing else, and this
 * file references no theme roles.
 *
 * When a background effect is installed, the painted image is the
 * RASTERIZED artwork (`lib/new-thread-background-effects.ts` — still
 * cover-fit, still masked); `none` paints the raw artwork.
 *
 * The hero's frost leg reads the RESOLVED surface treatment off the
 * document root (`data-surface`): 0.84 under `"frosted"`, 1.0 under
 * `"opaque"` — the hero consumes the resolution instead of pre-deciding it,
 * while the defrost decision itself stays where it lives
 * (`lib/appearance-store.ts`).
 *
 * The mask is regenerated from the measured `#composer-surface` rect on
 * every commit (scheduled on the animation frame AFTER the commit's layout
 * effects, so it consumes the same frame's dock-transform write, never last
 * frame's geometry — the web peer of "all prepaint completes before any
 * paint") and on every surface resize (the 180 ms typing morph grows the
 * pill — and the hole tracks it).
 *
 * The hero uses the full conversation-canvas width even while the right pane
 * clips it: navigation must never rescale the artwork.
 */

export interface NewThreadBackgroundProps {
  /**
   * The resolved artwork to paint, or null while nothing is resolved at all
   * (the store's `url === null` — the only null-paint case, ticket 35);
   * `ready` is the shell-scoped readiness clock's `data-ready` flag.
   */
  readonly artwork: { readonly url: string; readonly id: string | number; readonly ready: boolean } | null;
  readonly viewportHeight: number;
  /** `viewport_width − sidebar_now` — the full conversation canvas. */
  readonly heroWidth: number;
  /** The dock's `dissolve` channel: 1 = the established thread, 0 = the hero. */
  readonly dissolve: number;
  /** The settings-store effect — `none` paints the raw artwork; the others paint their raster. */
  readonly effect: NewThreadBackgroundEffect;
}

/** `prefers-reduced-motion` at first paint, reactive afterwards. */
function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setReduced(query.matches);
    onChange();
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return reduced;
}

function readRootSurfaceTreatment(): SurfaceTreatment {
  if (typeof document === "undefined") {
    return "opaque";
  }
  return document.documentElement.dataset.surface === "frosted" ? "frosted" : "opaque";
}

/**
 * The resolved surface treatment, read off the document root's
 * `data-surface` (installed by `theme.ts`) — reactive to the attribute
 * itself, so a flipped resolution (or a forced screenshot state) re-renders
 * the hero's 0.84 frost leg without any store coupling.
 */
function useRootSurfaceTreatment(): SurfaceTreatment {
  const [surface, setSurface] = useState(readRootSurfaceTreatment);
  useEffect(() => {
    const observer = new MutationObserver(() => setSurface(readRootSurfaceTreatment()));
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-surface"],
    });
    return () => observer.disconnect();
  }, []);
  return surface;
}

/** The raw artwork, decoded and ready for `drawImage`. */
function useDecodedImage(url: string | null): HTMLImageElement | null {
  const [image, setImage] = useState<HTMLImageElement | null>(null);
  useEffect(() => {
    if (url === null) {
      setImage(null);
      return;
    }
    let cancelled = false;
    const element = new Image();
    element.src = url;
    void element.decode().then(
      () => {
        if (!cancelled) {
          setImage(element);
        }
      },
      () => {
        if (!cancelled) {
          setImage(null);
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [url]);
  return image;
}

/**
 * The rasterized artwork for the installed effect, keyed on the resolved
 * appearance (Dither shares one raster across appearances). An appearance
 * flip or artwork swap keeps painting the CURRENT raster until the new one
 * resolves — no flash of empty hero.
 */
function useEffectRaster(
  url: string | null,
  effect: NewThreadBackgroundEffect,
  light: boolean,
): RasterData | null {
  const [entry, setEntry] = useState<{ url: string; raster: RasterData } | null>(null);
  useEffect(() => {
    if (url === null || effect === "none") {
      setEntry(null);
      return;
    }
    let cancelled = false;
    effectRaster(url, effect, light).then(
      (raster) => {
        if (!cancelled) {
          setEntry({ url, raster });
        }
      },
      () => {
        if (!cancelled) {
          setEntry(null);
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [url, effect, light]);
  return entry !== null && entry.url === url ? entry.raster : null;
}

/** The raster as a drawable — painted once per raster, then blitted cover-fit. */
function rasterToCanvas(raster: RasterData): HTMLCanvasElement | null {
  if (typeof document === "undefined") {
    return null;
  }
  const canvas = document.createElement("canvas");
  canvas.width = raster.width;
  canvas.height = raster.height;
  const context = canvas.getContext("2d");
  if (context === null) {
    return null;
  }
  context.putImageData(new ImageData(raster.data, raster.width, raster.height), 0, 0);
  return canvas;
}

// ---------------------------------------------------------------------------
// The per-frame remask entry point (ticket 34)
// ---------------------------------------------------------------------------

/**
 * The sidebar-slide loop's per-frame entry into the mounted hero's remask.
 * While `.sidebar`'s CSS width glides, the pill's viewport-space rect moves
 * every frame (`margin-inline: auto` centers it inside the gliding column)
 * even though its size does not — and the cutout hole must track it, the
 * desktop's "including on sidebar resize" same-frame contract
 * (mask.rs:49-51). The mounted hero registers its CURRENT remask closure
 * here on every commit (the per-commit effect below, which stays the
 * typing-morph path); `ConversationPage`'s rAF loop calls this once per
 * frame of the tween. No hero mounted — a no-op.
 */
const remaskListeners = new Set<() => void>();

/** Re-run the mounted hero's remask once — the sidebar tween calls this per frame. */
export function remaskNewThreadBackground(): void {
  for (const remask of remaskListeners) {
    remask();
  }
}

export function NewThreadBackground({
  artwork,
  viewportHeight,
  heroWidth,
  dissolve,
  effect,
}: NewThreadBackgroundProps) {
  const reduced = useReducedMotion();
  const heroRef = useRef<HTMLDivElement | null>(null);
  const revealCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const cutoutCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const maskCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const surface = useRootSurfaceTreatment();
  const appearance = useResolvedAppearance();
  const image = useDecodedImage(artwork === null ? null : artwork.url);
  const raster = useEffectRaster(artwork === null ? null : artwork.url, effect, appearance === "light");
  const rasterCanvas = useMemo(() => (raster === null ? null : rasterToCanvas(raster)), [raster]);

  const height = newThreadBackgroundHeight(viewportHeight);
  // shell.rs:5893 — artwork_opacity × new_thread_background_opacity(is_frost);
  // the readiness leg rides the CSS wrapper (nested opacities multiply back
  // to the element formula).
  const heroOpacity = newThreadBackgroundElementOpacity(dissolve, 1, surface);
  // The readiness fade (effects.rs:11-32) is OWNED by the shell-scoped
  // artwork store (ticket 35): `artwork.ready` is its clock's past-arrival
  // flag, so a remount with the SAME id mounts ready (no re-fade — the
  // 120 ms ramp rides the store's own frame source, never this component's
  // lifecycle) and only a NEW id starts cold. `reduced` still snaps the
  // transition off here.

  // Re-paint both passes from the live composer surface: once per commit
  // (scheduled via rAF so the dock's transform write in the same commit's
  // layout effects is already on the element) and once per surface resize
  // (the typing morph). The cutout pass consumes the single combined
  // min(hole, fade) mask; the reveal pass the fade alone.
  useLayoutEffect(() => {
    const remask = (): void => {
      const hero = heroRef.current;
      const revealCanvas = revealCanvasRef.current;
      const cutoutCanvas = cutoutCanvasRef.current;
      // An installed effect paints its RASTER only — the desktop's hero is
      // `Empty` while the raster is cold, never the raw artwork swapping
      // mid-view; `none` paints the decoded raw artwork.
      const drawable = effect === "none" ? image : rasterCanvas;
      const clear = (): void => {
        if (revealCanvas !== null && revealCanvas.width !== 0) {
          revealCanvas.width = 0;
        }
        if (cutoutCanvas !== null && cutoutCanvas.width !== 0) {
          cutoutCanvas.width = 0;
        }
      };
      if (
        hero === null ||
        revealCanvas === null ||
        cutoutCanvas === null ||
        drawable === null ||
        artwork === null
      ) {
        clear();
        return;
      }
      const composerSurface = document.getElementById("composer-surface");
      if (composerSurface === null) {
        return;
      }
      const heroRect = hero.getBoundingClientRect();
      const surfaceRect = composerSurface.getBoundingClientRect();
      if (heroRect.width <= 0 || heroRect.height <= 0 || surfaceRect.width <= 0) {
        return;
      }
      const heroBox: Rect = {
        x: heroRect.x,
        y: heroRect.y,
        width: heroRect.width,
        height: heroRect.height,
      };
      const composerBox: Rect = {
        x: surfaceRect.x,
        y: surfaceRect.y,
        width: surfaceRect.width,
        height: surfaceRect.height,
      };
      const sourceWidth = drawable instanceof HTMLImageElement ? drawable.naturalWidth : drawable.width;
      const sourceHeight = drawable instanceof HTMLImageElement ? drawable.naturalHeight : drawable.height;
      if (sourceWidth <= 0 || sourceHeight <= 0) {
        clear();
        return;
      }
      const paint = (canvas: HTMLCanvasElement, cutout: boolean): void => {
        const cssWidth = Math.max(1, Math.round(heroBox.width));
        const cssHeight = Math.max(1, Math.round(heroBox.height));
        const scale = Math.max(1, window.devicePixelRatio || 1);
        const rasterWidth = Math.max(1, Math.round(heroBox.width * scale));
        const rasterHeight = Math.max(1, Math.round(heroBox.height * scale));
        if (canvas.width !== rasterWidth) {
          canvas.width = rasterWidth;
        }
        if (canvas.height !== rasterHeight) {
          canvas.height = rasterHeight;
        }
        const context = canvas.getContext("2d");
        if (context === null) {
          return;
        }
        // Cover fit (mask.rs:59-73): max scale, centered — the peer of the
        // CSS `background-size: cover; background-position: 50% 50%`.
        const cover = Math.max(rasterWidth / sourceWidth, rasterHeight / sourceHeight);
        const fittedWidth = sourceWidth * cover;
        const fittedHeight = sourceHeight * cover;
        context.clearRect(0, 0, rasterWidth, rasterHeight);
        context.drawImage(
          drawable,
          (rasterWidth - fittedWidth) / 2,
          (rasterHeight - fittedHeight) / 2,
          fittedWidth,
          fittedHeight,
        );
        // The pure shader grid at CSS resolution, applied through
        // destination-in (dest alpha ×= mask alpha — the desktop's mask
        // multiply). No per-frame data-URI PNG round-trips.
        const grid = cutoutMaskRaster(heroBox, composerBox, cutout, cssWidth, cssHeight);
        let maskCanvas = maskCanvasRef.current;
        if (maskCanvas === null) {
          maskCanvas = document.createElement("canvas");
          maskCanvasRef.current = maskCanvas;
        }
        if (maskCanvas.width !== cssWidth) {
          maskCanvas.width = cssWidth;
        }
        if (maskCanvas.height !== cssHeight) {
          maskCanvas.height = cssHeight;
        }
        const maskContext = maskCanvas.getContext("2d");
        if (maskContext === null) {
          return;
        }
        const maskImageData = new ImageData(cssWidth, cssHeight);
        const maskData = maskImageData.data;
        for (let i = 0, offset = 0; i < grid.length; i++, offset += 4) {
          maskData[offset] = 255;
          maskData[offset + 1] = 255;
          maskData[offset + 2] = 255;
          maskData[offset + 3] = grid[i]! * 255;
        }
        maskContext.putImageData(maskImageData, 0, 0);
        context.globalCompositeOperation = "destination-in";
        context.drawImage(maskCanvas, 0, 0, rasterWidth, rasterHeight);
        context.globalCompositeOperation = "source-over";
      };
      paint(revealCanvas, false);
      paint(cutoutCanvas, true);
    };
    // Same-frame contract: rAF fires after this commit's layout effects (the
    // dock prepaint writes the wrapper transform there) and before paint.
    const raf = requestAnimationFrame(remask);
    // Ticket 34's per-frame entry point: the sidebar-slide loop in
    // `ConversationPage` calls the CURRENT closure through
    // `remaskNewThreadBackground()` while the column glides.
    remaskListeners.add(remask);
    const observer =
      typeof ResizeObserver !== "undefined"
        ? new ResizeObserver(() => remask())
        : null;
    const composerSurface = document.getElementById("composer-surface");
    if (observer !== null && composerSurface !== null) {
      observer.observe(composerSurface);
    }
    return () => {
      remaskListeners.delete(remask);
      cancelAnimationFrame(raf);
      observer?.disconnect();
    };
  });

  if (artwork === null) {
    return null;
  }

  return (
    <div
      className="new-thread-hero"
      ref={heroRef}
      data-effect={effect}
      style={{ width: `${heroWidth}px`, height: `${height}px`, opacity: heroOpacity }}
      aria-hidden="true"
    >
      {/*
        The readiness wrapper keyed on the artwork id: a NEW id mounts with
        data-ready="false" and the store-owned clock flips it a frame later
        (the 120 ms CSS ramp); the SAME id mounts ready and never re-fades —
        a remount keeps its identity, so no transition runs (ticket 35).
      */}
      <div
        className="new-thread-hero-readiness"
        key={String(artwork.id)}
        data-ready={artwork.ready ? "true" : "false"}
        data-reduced={reduced ? "true" : "false"}
      >
        {/*
          The reveal pass: the same cover-fit artwork at
          CUTOUT_REVEAL_OPACITY (0.5) with only the shared bottom fade — its
          exclusion sits below the image, so it fills the cutout's hole
          without changing its shape.
        */}
        <canvas className="new-thread-hero-art new-thread-hero-art--reveal" ref={revealCanvasRef} />
        {/*
          The cutout pass: the single combined min(hole, fade) mask applied
          per pixel — the hole is the smoothstep-over-SDF ramp at the
          composer's live box (radius 26, a hard 8px transparent margin,
          feathered over clamp(0.52·heroHeight, 120, 280), extending from the
          composer's top past the hero's bottom).
        */}
        <canvas className="new-thread-hero-art new-thread-hero-art--cutout" ref={cutoutCanvasRef} />
      </div>
    </div>
  );
}
