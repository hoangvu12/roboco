/**
 * The activity glyphs — the desktop's `loaders.rs` spinners, in CSS.
 *
 * `GlyphSpinner` is `mini_glyph_spinner`: a 2×3 grid of round cells whose
 * brightness chases clockwise around the perimeter. `MatrixSpinner` is
 * `gradient_spinner` (the WorkingIndicator): a 3×3 grid whose pulse enters at
 * the bottom edge and converges on the top-centre cell, so the wave reads as
 * travelling upward.
 *
 * Both share one brightness curve and one period, straight from
 * `roboco_proto::motion`: `gspin_opacity` — full, then a linear fall to
 * `GSPIN_DIM` over the first 45% of the cycle, a hold to 92%, and a fast
 * return — over `GRADIENT_SPIN`'s 750ms. Per-cell phase becomes a negative
 * animation delay, which is how CSS says "start this cell further along".
 *
 * Row tints come from the accent's glyph roles (`--rb-glyph-*`), the same
 * three the desktop's `GlyphPalette::rows` hands the cells.
 */

/** `motion::MINI_RING` — clockwise ring position of each (row, col) cell. */
const MINI_RING: readonly (readonly number[])[] = [
  [0, 1],
  [5, 2],
  [4, 3],
];
const MINI_RING_LEN = 6;

/** `motion::MATRIX_SIDE`. */
const MATRIX_SIDE = 3;

export interface GlyphSpinnerProps {
  /** Cell edge in px — the desktop's `cell_px` (2.0 in sidebar corners). */
  readonly size?: number;
  /** Tint every row the current text color instead of the accent's glyph roles. */
  readonly mono?: boolean;
  readonly className?: string;
}

export function GlyphSpinner({ size = 11, mono = false, className }: GlyphSpinnerProps) {
  // The desktop sizes by cell; callers here size by the slot the glyph sits
  // in, so derive the cell from the box: h = cell * 4 (3 cells + 2 half-gaps).
  const cell = size / 4;
  return (
    <span
      className={`glyph-spinner ${mono ? "glyph-spinner-mono" : ""} ${className ?? ""}`}
      style={{ "--rb-cell": `${cell}px` } as React.CSSProperties}
      aria-hidden="true"
    >
      {MINI_RING.map((cols, row) => (
        <span className="glyph-spinner-row" data-row={row} key={row}>
          {cols.map((ring, col) => (
            <span
              className="glyph-spinner-cell"
              key={col}
              style={{ animationDelay: `${(-ring / MINI_RING_LEN) * 750}ms` }}
            />
          ))}
        </span>
      ))}
    </span>
  );
}

/**
 * `gspin_cell_phase`: distance from the bottom edge plus the horizontal
 * distance from centre, normalised — the pulse converges on the top-centre.
 */
function matrixPhase(row: number, col: number): number {
  const centre = (MATRIX_SIDE - 1) / 2;
  const max = MATRIX_SIDE - 1 + centre;
  const d = MATRIX_SIDE - 1 - row + Math.abs(col - centre);
  return d / max;
}

export function MatrixSpinner({ size = 24, className }: { size?: number; className?: string }) {
  // w = h = cell * 5 (3 cells + 2 half-gaps horizontally and vertically).
  const cell = size / 5;
  return (
    <span
      className={`matrix-spinner ${className ?? ""}`}
      style={{ "--rb-cell": `${cell}px` } as React.CSSProperties}
      aria-hidden="true"
    >
      {Array.from({ length: MATRIX_SIDE }, (_, row) => (
        <span className="matrix-spinner-row" data-row={row} key={row}>
          {Array.from({ length: MATRIX_SIDE }, (_, col) => (
            <span
              className="glyph-spinner-cell"
              key={col}
              // The matrix runs at half speed on the desktop (`pulse_delta_slow`).
              style={{ animationDelay: `${-matrixPhase(row, col) * 1500}ms`, animationDuration: "1500ms" }}
            />
          ))}
        </span>
      ))}
    </span>
  );
}
