import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

/**
 * The sidebar edge-fade mask, guarded at the artifact level: the shipped
 * stops must be the COMPLEMENT blend `1 − gate × (1 − ramp)`, not the naive
 * `gate × ramp`. The inverted form is the bug that masked the whole sidebar
 * out at rest (both gates 0 → every stop 0): a CSS mask paints at the
 * gradient's alpha, and `edge_fade.rs:184-202` paints fully opaque when no
 * edge is active — the gate removes the FADE, never the content.
 */

const css = readFileSync(new URL("../src/styles/app.css", import.meta.url), "utf8");

function maskStops(): string[] {
  const block = css.match(/\.sidebar-scroll\s*\{[^}]*\}/)?.[0];
  expect(block).toBeDefined();
  const mask = block!.match(/mask-image:\s*linear-gradient\(([^;]+)\);/)?.[1];
  expect(mask).toBeDefined();
  // Split on the commas BETWEEN stops (a comma ahead of the next `rgba(`),
  // never the ones inside a color's `rgba(0, 0, 0, …)`.
  return mask!.replace(/^\s*to bottom\s*,\s*/, "").split(/,\s*(?=rgba\()/).map((stop) => stop.trim());
}

/** The quadratic ramp values per band (edge_fade.rs: (distance / band)²). */
const RAMPS = [0, 0.0625, 0.25, 0.5625, 1] as const;

/** The complement: gate 0 → 1 (fully visible), gate 1 → the ramp itself. */
function stopAlpha(gate: number, ramp: number): number {
  return 1 - gate * (1 - ramp);
}

describe("sidebar edge-fade mask", () => {
  it("blends each gated stop between opaque and the fade ramp", () => {
    const stops = maskStops();
    expect(stops).toHaveLength(10);

    const gatePattern = /rgba\(0,\s*0,\s*0,\s*calc\(1 - var\(--rb-sidebar-fade-(top|bottom)\) \* ([\d.]+)\)\)/;
    for (const stop of stops) {
      const gated = stop.match(gatePattern);
      if (gated === null) {
        // The band-adjacent mid stops are always opaque (ramp 1 → 1 − G·0).
        expect(stop).toMatch(/rgba\(0,\s*0,\s*0,\s*1\)/);
        continue;
      }
      const [, , multiplier] = gated;
      expect(RAMPS).toContain(1 - Number(multiplier));
    }
  });

  it("keeps both bands' ramp order and the 24px band edges", () => {
    const stops = maskStops();
    // The quadratic ramp reads top-down 0 → 1 on both bands (mirrored for
    // bottom), so the complement multipliers run 1 → 0.9375 → 0.75 → 0.4375.
    expect(stops[0]).toMatch(/fade-top\)\s*\*\s*1\)\)\s*0px$/);
    expect(stops[1]).toMatch(/fade-top\)\s*\*\s*0\.9375\)\)\s*6px$/);
    expect(stops[2]).toMatch(/fade-top\)\s*\*\s*0\.75\)\)\s*12px$/);
    expect(stops[3]).toMatch(/fade-top\)\s*\*\s*0\.4375\)\)\s*18px$/);
    expect(stops[4]).toMatch(/rgba\(0,\s*0,\s*0,\s*1\)\s*24px$/);
    expect(stops[5]).toMatch(/rgba\(0,\s*0,\s*0,\s*1\)\s*calc\(100% - 24px\)$/);
    expect(stops[6]).toMatch(/fade-bottom\)\s*\*\s*0\.4375\)\)\s*calc\(100% - 18px\)$/);
    expect(stops[7]).toMatch(/fade-bottom\)\s*\*\s*0\.75\)\)\s*calc\(100% - 12px\)$/);
    expect(stops[8]).toMatch(/fade-bottom\)\s*\*\s*0\.9375\)\)\s*calc\(100% - 6px\)$/);
    expect(stops[9]).toMatch(/fade-bottom\)\s*\*\s*1\)\)\s*100%$/);
  });

  it("resolves to the desktop's states: opaque at rest, the ramp when gated", () => {
    // Rest (gate 0): every stop fully opaque — nothing is masked out.
    for (const ramp of RAMPS) {
      expect(stopAlpha(0, ramp)).toBe(1);
    }
    // Scrolled past the edge (gate 1): exactly the quadratic ramp.
    for (const ramp of RAMPS) {
      expect(stopAlpha(1, ramp)).toBeCloseTo(ramp, 10);
    }
  });

  it("ships both gates defaulted to 0", () => {
    const block = css.match(/\.sidebar-scroll\s*\{[^}]*\}/)![0];
    expect(block).toMatch(/--rb-sidebar-fade-top:\s*0;/);
    expect(block).toMatch(/--rb-sidebar-fade-bottom:\s*0;/);
  });
});
