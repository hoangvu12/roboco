import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import type { NewThreadBackgroundEffect } from "../state/ui-settings";
import {
  BOTTOM_FADE_GRADIENT,
  cutoutMaskDataUri,
  newThreadBackgroundHeight,
  newThreadBackgroundOpacity,
  type Rect,
} from "../lib/new-thread-background";

/**
 * The new-thread background hero — the web port of `shell.rs:857-914`
 * (`new_thread_background`) over `new_thread_background_mask.rs`'s two-pass
 * feathered cutout.
 *
 * Two stacked layers paint the same cover-fit artwork (background-size:
 * cover + center == the desktop's `paint` fit math): the REVEAL pass at
 * opacity 0.5 with only the bottom fade, and the CUTOUT pass at opacity 1
 * whose mask additionally clears a feathered rounded-rect hole at the
 * composer's live bounds — so the pill reads as a window into the artwork,
 * not a sticker on it. No blend mode, no theme overlay: the masks multiply
 * source alpha and nothing else, and this file references no theme roles.
 *
 * The cutout mask is regenerated from the measured `#composer-surface` rect
 * on every commit (scheduled on the animation frame AFTER the commit's
 * layout effects, so it consumes the same frame's dock-transform write,
 * never last frame's geometry — the web peer of "all prepaint completes
 * before any paint") and on every surface resize (the 180 ms typing morph
 * grows the pill — and the hole tracks it).
 *
 * The hero uses the full conversation-canvas width even while the right pane
 * clips it: navigation must never rescale the artwork.
 */

export interface NewThreadBackgroundProps {
  /** The decoded artwork to paint, or null while resolving. */
  readonly artwork: { readonly url: string; readonly id: string | number } | null;
  readonly viewportHeight: number;
  /** `viewport_width − sidebar_now` — the full conversation canvas. */
  readonly heroWidth: number;
  /** The dock's `dissolve` channel: 1 = the established thread, 0 = the hero. */
  readonly dissolve: number;
  /** The settings-store effect — `none` renders faithfully; the raster effects render as `none` (noted in the ticket). */
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

export function NewThreadBackground({
  artwork,
  viewportHeight,
  heroWidth,
  dissolve,
  effect,
}: NewThreadBackgroundProps) {
  const reduced = useReducedMotion();
  const heroRef = useRef<HTMLDivElement | null>(null);
  const holeRef = useRef<HTMLDivElement | null>(null);
  const [ready, setReady] = useState(false);

  const height = newThreadBackgroundHeight(viewportHeight);
  // The web is forced opaque (the 2026-09-17 defrost decision — see
  // ui-settings' `surface` healing), so the frosted 0.84 branch can never
  // engage here; the pure function stays ported and unit-tested.
  const frost = newThreadBackgroundOpacity(false);
  const heroOpacity = (1 - Math.min(Math.max(dissolve, 0), 1)) * frost;

  // The readiness fade (effects.rs:11-32): a 120 ms smoothstep on image-id
  // change; the same artwork never re-fades; reduced motion snaps.
  useLayoutEffect(() => {
    if (artwork === null) {
      setReady(false);
      return;
    }
    if (reduced) {
      setReady(true);
      return;
    }
    setReady(false);
    let inner = 0;
    const outer = requestAnimationFrame(() => {
      inner = requestAnimationFrame(() => setReady(true));
    });
    return () => {
      cancelAnimationFrame(outer);
      cancelAnimationFrame(inner);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [artwork?.id, reduced]);

  // Re-mask from the live composer surface: once per commit (scheduled via
  // rAF so the dock's transform write in the same commit's layout effects is
  // already on the element) and once per surface resize (the typing morph).
  useLayoutEffect(() => {
    const remask = (): void => {
      const hero = heroRef.current;
      const hole = holeRef.current;
      if (hero === null || hole === null || artwork === null) {
        return;
      }
      const surface = document.getElementById("composer-surface");
      if (surface === null) {
        return;
      }
      const heroRect = hero.getBoundingClientRect();
      const surfaceRect = surface.getBoundingClientRect();
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
      const mask = `url("${cutoutMaskDataUri(heroBox, composerBox)}")`;
      hole.style.maskImage = mask;
      hole.style.setProperty("-webkit-mask-image", mask);
      hole.style.maskSize = "100% 100%";
      hole.style.setProperty("-webkit-mask-size", "100% 100%");
      hole.style.maskRepeat = "no-repeat";
      hole.style.setProperty("-webkit-mask-repeat", "no-repeat");
    };
    // Same-frame contract: rAF fires after this commit's layout effects (the
    // dock prepaint writes the wrapper transform there) and before paint.
    const raf = requestAnimationFrame(remask);
    const observer =
      typeof ResizeObserver !== "undefined"
        ? new ResizeObserver(() => remask())
        : null;
    const surface = document.getElementById("composer-surface");
    if (observer !== null && surface !== null) {
      observer.observe(surface);
    }
    return () => {
      cancelAnimationFrame(raf);
      observer?.disconnect();
    };
  });

  if (artwork === null) {
    return null;
  }

  const backgroundImage = `url("${artwork.url}")`;
  const fadeMask = `${BOTTOM_FADE_GRADIENT}`;
  const revealStyle: CSSProperties = { backgroundImage, maskImage: fadeMask, WebkitMaskImage: fadeMask };
  // The cutout layer carries ONLY the shared fade mask — the image itself
  // lives on the hole child (element masks multiply: fade × hole).
  const cutoutStyle: CSSProperties = { maskImage: fadeMask, WebkitMaskImage: fadeMask };

  return (
    <div
      className="new-thread-hero"
      ref={heroRef}
      data-effect={effect}
      style={{ width: `${heroWidth}px`, height: `${height}px`, opacity: heroOpacity }}
      aria-hidden="true"
    >
      {/*
        The readiness wrapper keyed on the artwork id: mounting a new id
        starts the 120 ms fade from 0; the SAME id never remounts, so it
        never re-fades.
      */}
      <div
        className="new-thread-hero-readiness"
        key={String(artwork.id)}
        data-ready={ready ? "true" : "false"}
        data-reduced={reduced ? "true" : "false"}
      >
        {/*
          The reveal pass: the same cover-fit artwork at
          CUTOUT_REVEAL_OPACITY (0.5) with only the shared bottom fade — its
          exclusion sits below the image, so it fills the cutout's hole
          without changing its shape.
        */}
        <div className="new-thread-hero-art new-thread-hero-art--reveal" style={revealStyle} />
        {/*
          The cutout pass: the fade mask on the layer intersects (element
          masks multiply) with the hole mask on the child, which carries the
          image — white with a blurred black rounded rect at the composer's
          live box (radius 26 + 8px of hard transparency, feathered over
          clamp(0.52·heroHeight, 120, 280), extending from the composer's top
          past the hero's bottom).
        */}
        <div className="new-thread-hero-art new-thread-hero-art--cutout" style={cutoutStyle}>
          <div className="new-thread-hero-hole" ref={holeRef} style={{ backgroundImage }} />
        </div>
      </div>
    </div>
  );
}
