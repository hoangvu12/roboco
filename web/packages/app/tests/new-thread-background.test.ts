import { describe, expect, it } from "vitest";
import {
  BOTTOM_FADE_GRADIENT,
  CUTOUT_REVEAL_OPACITY,
  cutoutMaskDataUri,
  decodeBackgroundBlob,
  heroCutoutHole,
  heroMaskGeometry,
  newThreadBackgroundHeight,
  newThreadBackgroundOpacity,
  Readiness,
  rectEquals,
  resolveNewThreadBackground,
  type Rect,
} from "../src/lib/new-thread-background";

/**
 * The new-thread hero's geometry — each describe named after the
 * `new_thread_background_mask.rs` / `shell.rs` unit test it mirrors. The
 * hero's cutout is what makes the composer read as a window into the
 * artwork rather than a sticker on top of it, and the bottom fade is what
 * dissolves the artwork into the canvas instead of cropping it.
 */

const HERO: Rect = { x: 224.25, y: 40.5, width: 1000, height: 440 };
const COMPOSER: Rect = { x: 352, y: 406, width: 736, height: 124 };

describe("new_thread_background_height (shell.rs:8265-8281)", () => {
  it("maps the viewport ratio with the 760px ceiling", () => {
    expect(newThreadBackgroundHeight(400)).toBeCloseTo(288, 5);
    expect(newThreadBackgroundHeight(600)).toBeCloseTo(432, 5);
    expect(newThreadBackgroundHeight(1000)).toBeCloseTo(720, 5);
    expect(newThreadBackgroundHeight(1200)).toBe(760);
    // Negative viewports clamp to zero, never a negative height.
    expect(newThreadBackgroundHeight(-100)).toBe(0);
  });
});

describe("new_thread_background_opacity (shell.rs:844-850)", () => {
  it("is 0.84 frosted and 1.0 opaque", () => {
    expect(newThreadBackgroundOpacity(true)).toBe(0.84);
    expect(newThreadBackgroundOpacity(false)).toBe(1);
  });
});

describe("mask_tracks_current_surface_in_window_space_without_rounding (mask.rs:180)", () => {
  it("consumes the composer's exact window-space rect", () => {
    for (const sidebar of [0, 112.25, 224]) {
      for (const rightPanel of [0, 360]) {
        const hero: Rect = { x: sidebar, y: 40, width: 1200 - sidebar, height: 440 };
        const composer: Rect = {
          x: sidebar + 40.5,
          y: 360.25,
          width: 900 - sidebar - rightPanel,
          height: 124,
        };
        const mask = heroMaskGeometry(hero, composer, true);
        expect(rectEquals(mask.bounds, composer)).toBe(true);
        expect(mask.bottomFade.end).toBeCloseTo(480, 5);
        expect(mask.bottomFade.height).toBeCloseTo(440, 5);
        expect(mask.feather).toBeCloseTo(440 * 0.52, 5);
        expect(mask.clearance).toBe(8);
        expect(mask.radius).toBe(26);
      }
    }
  });
});

describe("taller_background_stays_cleared_below_the_composer (mask.rs:201)", () => {
  it("extends the cleared rect to the hero's bottom at the feather ceiling", () => {
    const hero: Rect = { x: 0, y: 0, width: 1440, height: 691.2 };
    const composer: Rect = { x: 352, y: 406, width: 736, height: 124 };
    const mask = heroMaskGeometry(hero, composer, true);
    expect(mask.bounds.x).toBe(composer.x);
    expect(mask.bounds.y).toBe(composer.y);
    expect(mask.bounds.width).toBe(composer.width);
    expect(mask.bounds.y + mask.bounds.height).toBeCloseTo(691.2, 5);
    expect(mask.feather).toBe(280);
  });
});

describe("new_thread_cutout_reveal_preserves_the_bottom_fade_and_image_extent (mask.rs:211)", () => {
  it("reveal pass has no hole, the same fade, and a parked exclusion rect", () => {
    const hero: Rect = { x: 0, y: 0, width: 1440, height: 691.2 };
    const composer: Rect = { x: 352, y: 406, width: 736, height: 124 };
    const cutout = heroMaskGeometry(hero, composer, true);
    const reveal = heroMaskGeometry(hero, composer, false);
    expect(cutout.bottomFade).toEqual(reveal.bottomFade);
    // The exclusion rect parks entirely below the image.
    expect(reveal.bounds.y - reveal.feather).toBeGreaterThanOrEqual(hero.y + hero.height);
    expect(reveal.radius).toBe(0);
    expect(reveal.clearance).toBe(0);
    expect(CUTOUT_REVEAL_OPACITY).toBe(0.5);
  });
});

describe("new_thread_main_fade_uses_the_full_height_at_every_window_size (mask.rs:224)", () => {
  it("the fade spans the full hero height on both passes", () => {
    for (const height of [288, 489.6, 691.2, 760]) {
      const hero: Rect = { x: 224.25, y: 40.5, width: 1000, height };
      const composer: Rect = { x: 352, y: 406, width: 736, height: 124 };
      for (const cutout of [false, true]) {
        const fade = heroMaskGeometry(hero, composer, cutout).bottomFade;
        expect(Math.abs(fade.end - fade.height - hero.y)).toBeLessThan(0.0001);
        expect(fade.end).toBeCloseTo(hero.y + hero.height, 5);
      }
    }
  });
});

describe("background_paint_sees_same_frame_composer_bounds_even_when_painted_first (mask.rs:91)", () => {
  it("the CSS mask regenerates from the live surface every call", () => {
    // The web's paint-time contract: the mask string is a pure function of
    // the CURRENT measured bounds — there is no cached geometry to go stale,
    // so the hero can never read last frame's composer box.
    const hero: Rect = { x: 40, y: 0, width: 768, height: 440 };
    const at = (x: number, width: number): Rect => ({ x, y: 360.25, width, height: 124 });
    const first = cutoutMaskDataUri(hero, at(40, 768));
    expect(first).not.toBe(cutoutMaskDataUri(hero, at(264, 544)));
    expect(first).toBe(cutoutMaskDataUri(hero, at(40, 768)));
    // The hole tracks the composer's x in hero-local coordinates.
    const hole = heroCutoutHole(hero, at(264, 544));
    expect(hole.x).toBeCloseTo(264 - 40 - 8, 5);
    expect(hole.y).toBeCloseTo(360.25 - 8, 5);
    expect(hole.width).toBeCloseTo(544 + 16, 5);
    expect(hole.height).toBeCloseTo(Math.max(360.25 + 124, 440) - 360.25 + 16, 5);
    expect(hole.radius).toBe(26 + 8);
  });
});

describe("bottom fade gradient stops (ticket §2.8 CSS mapping)", () => {
  it("approximates smoothstep with quarter-point stops", () => {
    expect(BOTTOM_FADE_GRADIENT).toContain("rgba(0,0,0,0.156) 25%");
    expect(BOTTOM_FADE_GRADIENT).toContain("rgba(0,0,0,0.5) 50%");
    expect(BOTTOM_FADE_GRADIENT).toContain("rgba(0,0,0,0.844) 75%");
  });
});

describe("readiness_opacity (new_thread_background_effects.rs:328-330)", () => {
  it("is exactly 0.5 at 60 ms, restarts only on id change, snaps reduced", () => {
    const readiness = new Readiness();
    // No image: clear state, 0.
    expect(readiness.opacity(null, false, 0)).toBe(0);
    // First sighting stores the clock.
    expect(readiness.opacity("art-1", false, 0)).toBe(0);
    expect(readiness.opacity("art-1", false, 60)).toBe(0.5);
    expect(readiness.opacity("art-1", false, 120)).toBe(1);
    // The same artwork never re-fades.
    expect(readiness.opacity("art-1", false, 5000)).toBe(1);
    // A DIFFERENT id restarts the clock.
    expect(readiness.opacity("art-2", false, 5000)).toBe(0);
    expect(readiness.opacity("art-2", false, 5060)).toBe(0.5);
    // Reduced motion snaps to 1.
    expect(readiness.opacity("art-3", true, 9000)).toBe(1);
    // Clearing again resets.
    expect(readiness.opacity(null, false, 9100)).toBe(0);
    expect(readiness.opacity("art-1", false, 9200)).toBe(0);
  });
});

describe("resolve_new_thread_background (the decode contract)", () => {
  it("falls back to the bundled default when nothing is installed", async () => {
    expect(await resolveNewThreadBackground(null, "/default.png")).toBe("/default.png");
    // A stored path that does not decode (SVG, missing) is rejected.
    expect(await resolveNewThreadBackground({ path: "missing.png", name: "x" }, "/default.png")).toBe(
      "/default.png",
    );
  });

  it("accepts only what actually decodes as a blob", async () => {
    // The positive decode path needs a real bitmap decoder
    // (`createImageBitmap`); jsdom has none, so it only runs in a browser.
    if (typeof createImageBitmap === "function") {
      const png = new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])], {
        type: "image/png",
      });
      expect(await decodeBackgroundBlob(png)).toBe(true);
    }
    // A text/SVG blob cannot become a bitmap — staging accepts it as an
    // attachment, the background must not.
    const svg = new Blob(['<svg xmlns="http://www.w3.org/2000/svg"></svg>'], { type: "image/svg+xml" });
    expect(await decodeBackgroundBlob(svg)).toBe(false);
  });
});
