import { describe, expect, it } from "vitest";
import {
  BOTTOM_FADE_GRADIENT,
  CUTOUT_REVEAL_OPACITY,
  cutoutBottomFadeAlpha,
  cutoutHoleAlpha,
  cutoutMaskAlpha,
  cutoutMaskRaster,
  decodeBackgroundBlob,
  heroMaskGeometry,
  newThreadBackgroundElementOpacity,
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
 * dissolves the artwork into the canvas instead of cropping it. The mask
 * ramp cases assert the desktop's per-pixel shader exactly: the
 * smoothstep-over-SDF hole (hard 8px margin, one-sided 120–280px dome)
 * composed with the shared bottom fade by MIN inside one mask.
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

describe("new_thread_background element opacity (shell.rs:880, 5860-5864, 5893)", () => {
  it("is (1 − dissolve) × readiness × surface multiplier, clamped", () => {
    expect(newThreadBackgroundElementOpacity(0, 1, "opaque")).toBe(1);
    expect(newThreadBackgroundElementOpacity(0, 1, "frosted")).toBeCloseTo(0.84, 5);
    expect(newThreadBackgroundElementOpacity(0.5, 1, "opaque")).toBe(0.5);
    expect(newThreadBackgroundElementOpacity(0, 0.25, "frosted")).toBeCloseTo(0.21, 5);
    expect(newThreadBackgroundElementOpacity(0.5, 0.5, "opaque")).toBe(0.25);
    // Dissolve clamps before the multiply.
    expect(newThreadBackgroundElementOpacity(-0.5, 1, "opaque")).toBe(1);
    expect(newThreadBackgroundElementOpacity(1.5, 1, "frosted")).toBe(0);
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
  it("the mask raster is a pure function of the CURRENT measured bounds", () => {
    // The web's paint-time contract: the mask grid is a pure function of
    // the CURRENT measured bounds — there is no cached geometry to go
    // stale, so the hero can never read last frame's composer box.
    // (Rastered at quarter resolution for the test's sake; the shader is
    // scale-invariant.)
    const hero: Rect = { x: 40, y: 0, width: 768, height: 440 };
    const at = (x: number, width: number): Rect => ({ x, y: 360.25, width, height: 124 });
    const first = cutoutMaskRaster(hero, at(40, 768), true, 192, 110, 0.25);
    expect(Array.from(first)).not.toEqual(Array.from(cutoutMaskRaster(hero, at(264, 544), true, 192, 110, 0.25)));
    expect(Array.from(cutoutMaskRaster(hero, at(40, 768), true, 192, 110, 0.25))).toEqual(Array.from(first));
    // The hole tracks the composer's x: a pixel inside the old pill reads
    // 0; under the moved pill it is mid-ramp recovery.
    const mask = heroMaskGeometry(hero, at(264, 544), true);
    expect(cutoutMaskAlpha(mask, 44.5, 380.5)).toBeGreaterThan(0);
    expect(cutoutMaskAlpha(heroMaskGeometry(hero, at(40, 768), true), 44.5, 380.5)).toBe(0);
  });
});

describe("the hole ramp (shaders.wgsl image_mask_alpha over mask.rs geometry)", () => {
  // A tall hero (feather pinned at the 280 ceiling) with the composer well
  // inside its y-range, so the SDF at y=500 is purely horizontal distance.
  const hero: Rect = { x: 0, y: 0, width: 1440, height: 691.2 };
  const composer: Rect = { x: 352, y: 406, width: 736, height: 124 };
  const mask = heroMaskGeometry(hero, composer, true);
  const rightEdge = composer.x + composer.width;
  const atDistance = (d: number, y: number): number => cutoutHoleAlpha(mask, rightEdge + d, y);

  it("alpha is exactly 0 for SDF distance d ≤ 8 (the hard margin, inside included)", () => {
    expect(atDistance(0, 500)).toBe(0);
    expect(atDistance(4, 500)).toBe(0);
    expect(atDistance(8, 500)).toBe(0);
    // Deep inside the cleared rect the alpha is 0 as well.
    expect(cutoutHoleAlpha(mask, 400, 500)).toBe(0);
  });

  it("alpha is 0.5 at d = 8 + feather/2 and exactly 1 at d ≥ 8 + feather", () => {
    expect(mask.feather).toBe(280);
    expect(atDistance(8 + 140, 500)).toBeCloseTo(0.5, 6);
    expect(atDistance(8 + 280, 500)).toBe(1);
    // The ramp's compact support: nothing beyond 8 + feather.
    expect(atDistance(8 + 281, 500)).toBe(1);
    expect(atDistance(8 + 500, 500)).toBe(1);
    // Monotone through the dome.
    expect(atDistance(8 + 70, 500)).toBeGreaterThan(0);
    expect(atDistance(8 + 70, 500)).toBeLessThan(atDistance(8 + 140, 500));
  });

  it("feather clamps at 120/280 for short/tall heroes (mask.rs:36-40)", () => {
    const short = heroMaskGeometry({ x: 0, y: 0, width: 900, height: 200 }, composer, true);
    expect(short.feather).toBe(120);
    const mid = heroMaskGeometry({ x: 0, y: 0, width: 900, height: 500 }, composer, true);
    expect(mid.feather).toBeCloseTo(500 * 0.52, 5);
    const tall = heroMaskGeometry({ x: 0, y: 0, width: 900, height: 760 }, composer, true);
    expect(tall.feather).toBe(280);
  });
});

describe("the combined mask composes by min, not product (shaders.wgsl:1335-1338)", () => {
  it("a pixel where hole and fade are both mid-ramp yields min(hole, fade)", () => {
    const hero: Rect = { x: 0, y: 0, width: 1440, height: 691.2 };
    const composer: Rect = { x: 352, y: 406, width: 736, height: 124 };
    const mask = heroMaskGeometry(hero, composer, true);
    // The fade is exactly 0.5 at the hero's mid-height (691.2 − 345.6); the
    // hole is mid-ramp there (the row sits above the composer's top, so the
    // SDF mixes both axes — mid-ramp either way).
    const y = 345.6;
    const x = 1236;
    const hole = cutoutHoleAlpha(mask, x, y);
    const fade = cutoutBottomFadeAlpha(mask, y);
    expect(fade).toBeCloseTo(0.5, 6);
    expect(hole).toBeGreaterThan(0.05);
    expect(hole).toBeLessThan(0.95);
    expect(cutoutMaskAlpha(mask, x, y)).toBeCloseTo(Math.min(hole, fade), 6);
    // min is brighter than the product — element-mask multiplication would
    // darken this pixel to hole × fade.
    expect(cutoutMaskAlpha(mask, x, y)).toBeGreaterThan(hole * fade);
  });
});

describe("cutoutMaskRaster (the shader's per-pixel grid)", () => {
  it("evaluates cutoutMaskAlpha at every raster pixel's window-space center", () => {
    const hero: Rect = { x: 12.5, y: 9.25, width: 90, height: 60 };
    const composer: Rect = { x: 30, y: 40, width: 40, height: 12 };
    for (const cutout of [false, true]) {
      const grid = cutoutMaskRaster(hero, composer, cutout, 45, 30, 0.5);
      expect(grid.length).toBe(45 * 30);
      const mask = heroMaskGeometry(hero, composer, cutout);
      for (let y = 0; y < 30; y++) {
        for (let x = 0; x < 45; x++) {
          expect(grid[y * 45 + x]).toBeCloseTo(
            cutoutMaskAlpha(mask, hero.x + (x + 0.5) / 0.5, hero.y + (y + 0.5) / 0.5),
            6,
          );
        }
      }
    }
  });

  it("device pixels sample the same window-space shader (scale invariance)", () => {
    const hero: Rect = { x: 4, y: 6, width: 60, height: 40 };
    const composer: Rect = { x: 20, y: 30, width: 20, height: 6 };
    const grid = cutoutMaskRaster(hero, composer, true, 120, 80, 2);
    const mask = heroMaskGeometry(hero, composer, true);
    // Raster pixel (60, 40) at scale 2 samples window (4 + 60.5/2, 6 + 40.5/2).
    expect(grid[40 * 120 + 60]).toBeCloseTo(cutoutMaskAlpha(mask, 4 + 60.5 / 2, 6 + 40.5 / 2), 6);
  });

  it("the reveal pass is the fade alone (its exclusion parks below the image)", () => {
    const hero: Rect = { x: 0, y: 0, width: 100, height: 80 };
    const composer: Rect = { x: 30, y: 60, width: 40, height: 10 };
    const grid = cutoutMaskRaster(hero, composer, false, 100, 80);
    const mask = heroMaskGeometry(hero, composer, false);
    for (let y = 0; y < 80; y++) {
      const fade = cutoutBottomFadeAlpha(mask, y + 0.5);
      for (let x = 0; x < 100; x += 7) {
        expect(grid[y * 100 + x]).toBeCloseTo(fade, 6);
      }
    }
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
