//! Motion — the pure math behind roboco's animation: the loader phase
//! functions, the named catalog (durations + CSS cubic-bezier curves), and the
//! physical spring parameters.
//!
//! These are the curves and constants the gpui viewport animates with
//! (`roboco-ui/src/motion.rs`, `roboco-ui/src/loaders.rs`), lifted here so any
//! surface — including the web client's generated theme artifact — animates
//! the *same* motion rather than inventing its own. A loading indicator is a
//! brand surface; two of them that disagree read as two products.
//!
//! Everything is a pure function of a phase in `0..1`, so a caller can drive
//! it from a frame delta or from wall-clock elapsed time and get identical
//! output.

/// Roboco loader pulse period.
pub const ROBOCO_PULSE_MS: u64 = 2_400;
/// Gradient matrix spinner wave period.
pub const GRADIENT_SPIN_MS: u64 = 750;

/// Cells in the roboco wave loader.
pub const ROBOCO_CELLS: usize = 5;
/// Side length of the gradient spinner matrix.
pub const MATRIX_SIDE: usize = 3;

/// Roboco loader cells rest at this opacity between pulses.
pub const PULSE_MIN_OPACITY: f32 = 0.08;
/// …and at this scale.
pub const PULSE_MIN_SCALE: f32 = 0.9;
/// Per-cell stagger, as a fraction of the pulse period (0.15s of 2.4s).
pub const PULSE_STAGGER: f32 = 0.15 / 2.4;

/// Per-row tints of the gradient matrix spinner — roboco's "sunrise" gradient
/// sampled at each row: cool blue at the top, through amber, to pink.
pub const GSPIN_ROW_TINTS: [u32; MATRIX_SIDE] = [0xB6D3EF, 0xEDB185, 0xF888A0];
/// Opacity a gradient-spinner cell rests at between pulses.
pub const GSPIN_DIM: f32 = 0.1;

/// Clockwise ring position of each `(row, col)` cell of the 2×3 mini spinner,
/// top-left first: (0,0) → (0,1) → (1,1) → (2,1) → (2,0) → (1,0). Every cell of
/// a 2×3 grid is on the ring, so the brightness chases around it.
pub const MINI_RING: [[usize; 2]; 3] = [[0, 1], [5, 2], [4, 3]];
/// Cells in the mini spinner's ring.
pub const MINI_RING_LEN: f32 = 6.0;

/// Linear interpolation.
pub fn lerp(from: f32, to: f32, t: f32) -> f32 {
    from + (to - from) * t
}

/// A cell's phase, given the loader's raw phase and the cell's index.
pub fn staggered_phase(raw_delta: f32, index: usize, stagger: f32) -> f32 {
    (raw_delta - index as f32 * stagger).rem_euclid(1.0)
}

/// Cosine pulse: 0 at phase 0, 1 at phase 0.5, back to 0 at phase 1.
pub fn pulse_wave(phase: f32) -> f32 {
    0.5 - 0.5 * (phase * std::f32::consts::TAU).cos()
}

/// Roboco loader cell opacity for a phase: 0.08 → 1 → 0.08.
pub fn pulse_opacity(phase: f32) -> f32 {
    PULSE_MIN_OPACITY + (1.0 - PULSE_MIN_OPACITY) * pulse_wave(phase)
}

/// Roboco loader cell scale for a phase: 0.9 → 1 → 0.9.
pub fn pulse_scale(phase: f32) -> f32 {
    PULSE_MIN_SCALE + (1.0 - PULSE_MIN_SCALE) * pulse_wave(phase)
}

/// Gradient-spin cell opacity for a local phase `t` (0..1 of the period),
/// ported from roboco's `gradient-spin-pulse` keyframes: full at the cycle
/// start, easing down to `dim` by 45%, resting at `dim` until 92%, then rising
/// back to full — the per-cell phase offset sweeps this pulse across the grid.
pub fn gspin_opacity(t: f32, dim: f32) -> f32 {
    let t = t.rem_euclid(1.0);
    if t < 0.45 {
        lerp(1.0, dim, t / 0.45)
    } else if t < 0.92 {
        dim
    } else {
        lerp(dim, 1.0, (t - 0.92) / 0.08)
    }
}

/// The phase offset of a `(row, col)` cell in the 3×3 gradient spinner: the
/// pulse enters at the bottom edge and converges toward the top-centre cell, so
/// the wave reads as travelling upward.
pub fn gspin_cell_phase(row: usize, col: usize) -> f32 {
    let centre = (MATRIX_SIDE as f32 - 1.0) / 2.0;
    let max = MATRIX_SIDE as f32 - 1.0 + centre;
    let d = MATRIX_SIDE as f32 - 1.0 - row as f32 + (col as f32 - centre).abs();
    if max == 0.0 { 0.0 } else { d / (max + 1.0) }
}

/// The roboco mark's pixels — `[x, y]` of each 100×100 cell on the 820×940
/// canvas (roboco's `logo.tsx` CELLS), shared by the static mark and the
/// animated loader.
#[rustfmt::skip]
pub const MARK_CELLS: [(f32, f32); 34] = [
    (0., 600.), (0., 720.), (240., 840.), (240., 720.), (120., 840.), (120., 600.), (240., 600.),
    (0., 480.), (0., 360.), (480., 840.), (480., 720.), (120., 360.), (120., 240.), (240., 360.),
    (600., 720.), (480., 600.), (360., 360.), (240., 240.), (600., 600.), (720., 600.), (720., 480.),
    (240., 120.), (600., 380.), (720., 240.), (720., 0.), (480., 240.), (480., 0.), (120., 480.),
    (240., 480.), (360., 840.), (360., 720.), (360., 600.), (360., 480.), (120., 720.),
];

/// Fraction of the pulse cycle the mark's light sweep occupies.
pub const MARK_SPREAD: f32 = 0.55;

/// Per-cell stagger along the roboco's flight axis. The stagger *adds* phase
/// (the original uses a negative CSS delay, starting the cell mid-cycle), so a
/// larger value means the cell is further along and therefore **leads**: the
/// tail tip `(720, 0)` leads at `MARK_SPREAD`, the head `(0, 840)` trails at 0.
pub fn mark_cell_stagger(x: f32, y: f32) -> f32 {
    let t = (820.0 - x + y) / 1660.0;
    (1.0 - t) * MARK_SPREAD
}

/// A mark cell's phase at loader phase `delta`.
pub fn mark_phase(delta: f32, x: f32, y: f32) -> f32 {
    (delta + mark_cell_stagger(x, y)).rem_euclid(1.0)
}

// ---------------------------------------------------------------------------
// Cubic bezier
// ---------------------------------------------------------------------------

/// A CSS `cubic-bezier(x1, y1, x2, y2)` timing function (endpoints fixed at
/// (0,0) and (1,1)). Evaluation solves x(t) = input by Newton iteration with a
/// bisection fallback — the standard UnitBezier approach.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CubicBezier {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

impl CubicBezier {
    pub const fn new(x1: f32, y1: f32, x2: f32, y2: f32) -> Self {
        Self { x1, y1, x2, y2 }
    }

    fn coefficients(a: f32, b: f32) -> (f32, f32, f32) {
        let c = 3.0 * a;
        let bb = 3.0 * (b - a) - c;
        let aa = 1.0 - c - bb;
        (aa, bb, c)
    }

    fn sample_x(&self, t: f32) -> f32 {
        let (a, b, c) = Self::coefficients(self.x1, self.x2);
        ((a * t + b) * t + c) * t
    }

    fn sample_y(&self, t: f32) -> f32 {
        let (a, b, c) = Self::coefficients(self.y1, self.y2);
        ((a * t + b) * t + c) * t
    }

    fn sample_x_derivative(&self, t: f32) -> f32 {
        let (a, b, c) = Self::coefficients(self.x1, self.x2);
        (3.0 * a * t + 2.0 * b) * t + c
    }

    /// Curve parameter `t` for a given progress `x` (both 0..1).
    fn solve_t_for_x(&self, x: f32) -> f32 {
        // Newton–Raphson.
        let mut t = x;
        for _ in 0..8 {
            let err = self.sample_x(t) - x;
            if err.abs() < 1e-6 {
                return t;
            }
            let d = self.sample_x_derivative(t);
            if d.abs() < 1e-6 {
                break;
            }
            t -= err / d;
        }
        // Bisection fallback (x(t) is monotonic for valid CSS beziers).
        let (mut lo, mut hi) = (0.0_f32, 1.0_f32);
        for _ in 0..32 {
            let mid = (lo + hi) / 2.0;
            if self.sample_x(mid) < x {
                lo = mid
            } else {
                hi = mid
            }
        }
        (lo + hi) / 2.0
    }

    /// Eased output for input progress `x ∈ [0,1]` (clamped).
    pub fn eval(&self, x: f32) -> f32 {
        if x <= 0.0 {
            return 0.0;
        }
        if x >= 1.0 {
            return 1.0;
        }
        // f32 rounding can push sample_y a hair past 1.0 (observed 1.000000119
        // near the end of menu animations); gpui's animation element asserts
        // `delta ∈ [0,1]` and aborts, so clamp the output hard.
        self.sample_y(self.solve_t_for_x(x)).clamp(0.0, 1.0)
    }

    /// This curve as an easing closure.
    pub fn easing(self) -> impl Fn(f32) -> f32 + 'static {
        move |x| self.eval(x)
    }
}

/// roboco's signature entrance curve — CSS `cubic-bezier(0.16, 1, 0.3, 1)`.
pub const EASE_OUT_EXPO: CubicBezier = CubicBezier::new(0.16, 1.0, 0.3, 1.0);
/// CSS `ease-out` — width/height transitions.
pub const EASE_OUT: CubicBezier = CubicBezier::new(0.0, 0.0, 0.58, 1.0);
/// CSS `ease` — quick fades, menu/dialog pops.
pub const EASE: CubicBezier = CubicBezier::new(0.25, 0.1, 0.25, 1.0);
/// `easeOutQuint` — CSS `cubic-bezier(0.22, 1, 0.36, 1)`.
pub const EASE_OUT_QUINT: CubicBezier = CubicBezier::new(0.22, 1.0, 0.36, 1.0);
/// Sidebar resort glide (used from M3b).
pub const EASE_RESORT: CubicBezier = EASE_OUT_QUINT;
/// CSS `ease-in-out` — the transcript scroll glide (browser smooth-scroll
/// shape: gentle start, cruise, gentle landing).
pub const EASE_IN_OUT: CubicBezier = CubicBezier::new(0.42, 0.0, 0.58, 1.0);
/// Tailwind's default transition curve — CSS `cubic-bezier(0.4, 0, 0.2, 1)`
/// (`transition-colors` et al. carry it unless overridden; roboco never does).
pub const EASE_TAILWIND: CubicBezier = CubicBezier::new(0.4, 0.0, 0.2, 1.0);

/// Every named curve, for export/lookup by name. Aliases resolve to their
/// canonical entry ([`EASE_RESORT`] is [`EASE_OUT_QUINT`]).
pub const CURVES: &[(&str, CubicBezier)] = &[
    ("easeOutExpo", EASE_OUT_EXPO),
    ("easeOut", EASE_OUT),
    ("ease", EASE),
    ("easeOutQuint", EASE_OUT_QUINT),
    ("easeInOut", EASE_IN_OUT),
    ("easeTailwind", EASE_TAILWIND),
];

// ---------------------------------------------------------------------------
// Motion specs (the catalog)
// ---------------------------------------------------------------------------

/// One catalog entry: duration + optional delay + curve. The delay is folded
/// into the animation timeline: the animation runs for `delay + duration` and
/// [`progress`](Self::progress) holds 0 until the delay has elapsed.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MotionSpec {
    pub duration_ms: u64,
    pub delay_ms: u64,
    pub curve: CubicBezier,
}

impl MotionSpec {
    pub const fn new(duration_ms: u64, curve: CubicBezier) -> Self {
        Self {
            duration_ms,
            delay_ms: 0,
            curve,
        }
    }

    pub const fn with_delay(mut self, delay_ms: u64) -> Self {
        self.delay_ms = delay_ms;
        self
    }

    /// Wall-clock span of the whole timeline (delay + duration).
    pub fn total(&self) -> std::time::Duration {
        std::time::Duration::from_millis(self.delay_ms + self.duration_ms)
    }

    /// Eased progress (0..1) for a raw timeline delta (0..1 across
    /// [`total`](Self::total)). Pure — unit-testable without a window.
    pub fn progress(&self, raw_delta: f32) -> f32 {
        let total = (self.delay_ms + self.duration_ms) as f32;
        if total <= 0.0 || self.duration_ms == 0 {
            return 1.0;
        }
        let t =
            (raw_delta.clamp(0.0, 1.0) * total - self.delay_ms as f32) / self.duration_ms as f32;
        self.curve.eval(t.clamp(0.0, 1.0))
    }
}

/// Entrances: 0.5s expo-out fade + 4px rise.
pub const FADE_IN: MotionSpec = MotionSpec::new(500, EASE_OUT_EXPO);
/// Quick fade: 0.15s.
pub const FADE_QUICK: MotionSpec = MotionSpec::new(150, EASE);
/// Popover-in: 0.14s (scale 0.96 approximated, translateY −2).
pub const MENU_IN: MotionSpec = MotionSpec::new(140, EASE);
/// Popover-out: 0.1s — quicker than the entrance (exits should get out of the
/// way; matches the Radix convention of a shorter close than open).
pub const MENU_OUT: MotionSpec = MotionSpec::new(100, EASE);
/// Dialog-in: 0.18s (scale 0.96→1 approximated).
pub const DIALOG_IN: MotionSpec = MotionSpec::new(180, EASE);
/// Boot splash exit: 0.5s fade + 6px lift after a 0.15s hold.
pub const SPLASH_OUT: MotionSpec = MotionSpec::new(500, EASE).with_delay(150);
/// Sidebar / pane width+height transitions: 200ms ease-out.
pub const RESIZE: MotionSpec = MotionSpec::new(200, EASE_OUT);
/// Terminal tab drag-reorder sliding transforms: 150ms (§1.10).
pub const TAB_SLIDE: MotionSpec = MotionSpec::new(150, EASE_OUT);
/// Diff-pane per-file collapse: 180ms height (§1.11).
pub const COLLAPSE: MotionSpec = MotionSpec::new(180, EASE_OUT);
/// Reversible new-thread ↔ session handoff. The shared composer moves and
/// morphs on a fast-starting, soft-landing curve while the canvas/transcript
/// crossfade is staged around it. Slightly longer than a utility transition,
/// but still short enough to acknowledge a send immediately.
pub const NEW_THREAD_TRANSITION: MotionSpec = MotionSpec::new(420, EASE_RESORT);
/// Diff-pane chevron rotate: 200ms (§1.11; approximated as a crossfade — gpui
/// divs have no rotation transform at the pinned rev, same caveat as scale).
pub const CHEVRON: MotionSpec = MotionSpec::new(200, EASE);
/// Rail-tick / scroll-to-row glide: 500ms ease-in-out over the whole distance
/// (Electron parity — the original rail rode the browser's native smooth
/// scroll, a fixed-duration gentle ease, never percent-of-remaining).
pub const SCROLL_GLIDE: MotionSpec = MotionSpec::new(500, EASE_IN_OUT);
/// CSS `transition-colors` default: 150ms over [`EASE_TAILWIND`] — the
/// temporal blend every interactive hover wash rides in the original.
pub const HOVER_FADE: MotionSpec = MotionSpec::new(150, EASE_TAILWIND);
/// Roboco loader pulse period: 2.4s.
pub const ROBOCO_PULSE: MotionSpec = MotionSpec::new(2400, EASE);
/// Gradient matrix spinner wave period: 750ms.
pub const GRADIENT_SPIN: MotionSpec = MotionSpec::new(750, EASE);

/// One named catalog entry, for export/lookup by name.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CatalogEntry {
    /// Stable camelCase id, e.g. `"fadeIn"`.
    pub name: &'static str,
    /// Name of the entry's curve in [`CURVES`].
    pub curve: &'static str,
    pub spec: MotionSpec,
}

/// The whole named catalog. Order is presentation order (entrances first,
/// loops last).
pub const CATALOG: &[CatalogEntry] = &[
    CatalogEntry {
        name: "fadeIn",
        curve: "easeOutExpo",
        spec: FADE_IN,
    },
    CatalogEntry {
        name: "fadeQuick",
        curve: "ease",
        spec: FADE_QUICK,
    },
    CatalogEntry {
        name: "menuIn",
        curve: "ease",
        spec: MENU_IN,
    },
    CatalogEntry {
        name: "menuOut",
        curve: "ease",
        spec: MENU_OUT,
    },
    CatalogEntry {
        name: "dialogIn",
        curve: "ease",
        spec: DIALOG_IN,
    },
    CatalogEntry {
        name: "splashOut",
        curve: "ease",
        spec: SPLASH_OUT,
    },
    CatalogEntry {
        name: "resize",
        curve: "easeOut",
        spec: RESIZE,
    },
    CatalogEntry {
        name: "tabSlide",
        curve: "easeOut",
        spec: TAB_SLIDE,
    },
    CatalogEntry {
        name: "collapse",
        curve: "easeOut",
        spec: COLLAPSE,
    },
    CatalogEntry {
        name: "newThreadTransition",
        curve: "easeOutQuint",
        spec: NEW_THREAD_TRANSITION,
    },
    CatalogEntry {
        name: "chevron",
        curve: "ease",
        spec: CHEVRON,
    },
    CatalogEntry {
        name: "scrollGlide",
        curve: "easeInOut",
        spec: SCROLL_GLIDE,
    },
    CatalogEntry {
        name: "hoverFade",
        curve: "easeTailwind",
        spec: HOVER_FADE,
    },
    CatalogEntry {
        name: "robocoPulse",
        curve: "ease",
        spec: ROBOCO_PULSE,
    },
    CatalogEntry {
        name: "gradientSpin",
        curve: "ease",
        spec: GRADIENT_SPIN,
    },
];

/// Resolve a curve by its [`CURVES`] name.
pub fn curve_named(name: &str) -> Option<CubicBezier> {
    CURVES.iter().find_map(|(n, c)| (*n == name).then_some(*c))
}

// ---------------------------------------------------------------------------
// Resize-edge feedback
// ---------------------------------------------------------------------------

/// Pane resize limits acknowledge a held pointer without persisting an
/// out-of-range size. The small displacement is shared by the shell panes and
/// nested surface splits so every seam has the same physical response.
pub const RESIZE_EDGE_NUDGE: f32 = 5.0;
pub const RESIZE_EDGE_BOUNCE_MS: u64 = 220;
pub const RESIZE_EDGE_BOUNCE_OUT_FRACTION: f32 = 0.32;

// ---------------------------------------------------------------------------
// Stick-to-bottom spring (transcript scroll)
// ---------------------------------------------------------------------------
//
// Same constants as mugen's DEFAULT_SPRING (§1e), which follows the shape of
// stackblitz/use-stick-to-bottom. `roboco-ui/src/transcript.rs` integrates
// them at a fixed 60fps sub-frame; the web client reimplements the same
// stepper in JS from these values.

/// Retains velocity frame-to-frame (higher = more glide).
pub const SPRING_DAMPING: f32 = 0.7;
/// Pull toward the target (higher = snappier).
pub const SPRING_STIFFNESS: f32 = 0.05;
/// Inertia (higher = slower to start/stop).
pub const SPRING_MASS: f32 = 1.25;
/// Reference frame for the fixed-timestep integration (60fps).
pub const SPRING_FRAME_MS: f32 = 1000.0 / 60.0;
/// Cap on simulated frames per tick — a hitch catches up instead of
/// teleporting.
pub const SPRING_MAX_CATCHUP_FRAMES: f32 = 8.0;
/// EMA rate for the feed-forward target-growth estimate.
pub const SPRING_GROWTH_EMA: f32 = 0.12;
/// While streaming, chase up to this many px above the true bottom (keeps the
/// growing tail visible instead of hugging a moving edge).
pub const SPRING_CHASE_MAX_LEAD: f32 = 32.0;
/// Treat as exactly pinned within this distance of the bottom.
pub const AT_BOTTOM_PX: f32 = 2.0;
/// Distance from the bottom within which an upward scroll re-engages the pin.
pub const STICK_THRESHOLD_PX: f32 = 70.0;
/// Retain the spring's state this long after landing, so a streaming pause
/// resumes at cruise. Retaining state does not require drawing idle frames.
pub const SPRING_SETTLE_GRACE_MS: u64 = 500;
/// Teleport when farther than this many viewports from the end; glide the
/// rest.
pub const GLIDE_MAX_VIEWPORTS: f32 = 2.5;

// ---------------------------------------------------------------------------
// Composer dock glide (new-thread ↔ thread handoff)
// ---------------------------------------------------------------------------

/// Critically damped glide: omega per second of requested duration. Twelve
/// time constants settle within a fraction of a pixel over the intended
/// 420/470ms handoff, even across a large window.
pub const DOCK_GLIDE_TIME_CONSTANTS: f32 = 12.0;
/// Glide duration (seconds) when docking into an established thread.
pub const DOCK_GLIDE_DOCK_SECONDS: f32 = 0.420;
/// Glide duration (seconds) when returning to the new-thread hero.
pub const DOCK_GLIDE_UNDOCK_SECONDS: f32 = 0.470;
/// Position epsilon below which the glide is settled.
pub const DOCK_GLIDE_SETTLE_POSITION: f32 = 0.0005;
/// Velocity epsilon below which the glide is settled.
pub const DOCK_GLIDE_SETTLE_VELOCITY: f32 = 0.005;

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f32, b: f32, what: &str) {
        assert!((a - b).abs() < 1e-5, "{what}: {a} vs {b}");
    }

    #[test]
    fn the_pulse_is_a_full_cosine_cycle() {
        close(pulse_wave(0.0), 0.0, "trough at 0");
        close(pulse_wave(0.5), 1.0, "crest at half");
        close(pulse_wave(1.0), 0.0, "trough at 1");
        // Opacity and scale ride the same wave between their own bounds.
        close(pulse_opacity(0.0), PULSE_MIN_OPACITY, "dim rest");
        close(pulse_opacity(0.5), 1.0, "full crest");
        close(pulse_scale(0.0), PULSE_MIN_SCALE, "small rest");
        close(pulse_scale(0.5), 1.0, "full scale");
    }

    #[test]
    fn stagger_offsets_each_cell_and_wraps() {
        close(staggered_phase(0.0, 0, PULSE_STAGGER), 0.0, "cell 0");
        close(
            staggered_phase(0.0, 1, PULSE_STAGGER),
            1.0 - PULSE_STAGGER,
            "cell 1 trails into the previous cycle",
        );
        // Phase is periodic: a whole extra turn changes nothing.
        close(
            staggered_phase(0.3, 2, PULSE_STAGGER),
            staggered_phase(1.3, 2, PULSE_STAGGER),
            "wraps",
        );
        // Always inside the unit interval, for any input.
        for raw in [-4.2f32, -0.1, 0.0, 0.5, 7.9] {
            for index in 0..ROBOCO_CELLS {
                let phase = staggered_phase(raw, index, PULSE_STAGGER);
                assert!((0.0..1.0).contains(&phase), "{raw} {index} -> {phase}");
            }
        }
    }

    #[test]
    fn gradient_spin_holds_dim_then_snaps_back() {
        close(gspin_opacity(0.0, GSPIN_DIM), 1.0, "starts full");
        close(gspin_opacity(0.45, GSPIN_DIM), GSPIN_DIM, "down by 45%");
        close(gspin_opacity(0.7, GSPIN_DIM), GSPIN_DIM, "rests dim");
        close(gspin_opacity(1.0, GSPIN_DIM), 1.0, "back to full");
        // Never leaves its bounds, at any phase.
        for step in 0..200 {
            let value = gspin_opacity(step as f32 / 100.0, GSPIN_DIM);
            assert!((GSPIN_DIM..=1.0).contains(&value), "{step} -> {value}");
        }
    }

    #[test]
    fn the_gradient_wave_travels_upward() {
        // The bottom row leads and the top-centre cell trails, which is what
        // makes the pulse read as rising.
        let bottom = gspin_cell_phase(MATRIX_SIDE - 1, 1);
        let top = gspin_cell_phase(0, 1);
        assert!(bottom < top, "bottom {bottom} should lead top {top}");
        // Symmetric about the centre column.
        close(gspin_cell_phase(1, 0), gspin_cell_phase(1, 2), "symmetry");
    }

    #[test]
    fn the_mark_sweeps_from_tail_to_head() {
        // The stagger adds phase, so leading means a LARGER value: the tail tip
        // is already mid-cycle while the head is still at zero.
        let tail = mark_cell_stagger(720.0, 0.0);
        let head = mark_cell_stagger(0.0, 840.0);
        assert!(tail > head, "tail {tail} should lead head {head}");
        close(head, 0.0, "the head anchors the sweep");
        close(
            tail,
            MARK_SPREAD * (1.0 - 100.0 / 1660.0),
            "tail leads by the spread",
        );
        // Every cell stays inside the unit interval once phased.
        for (x, y) in MARK_CELLS {
            let phase = mark_phase(0.3, x, y);
            assert!((0.0..1.0).contains(&phase), "({x},{y}) -> {phase}");
        }
        // Every cell's stagger stays inside the sweep window.
        for (x, y) in MARK_CELLS {
            let stagger = mark_cell_stagger(x, y);
            assert!(
                (0.0..=MARK_SPREAD).contains(&stagger),
                "({x},{y}) -> {stagger}"
            );
        }
    }

    #[test]
    fn the_mini_ring_visits_every_cell_once() {
        let mut seen: Vec<usize> = MINI_RING.iter().flatten().copied().collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..MINI_RING_LEN as usize).collect::<Vec<_>>());
    }

    #[test]
    fn catalog_curve_names_resolve_to_the_entry_curve() {
        let mut names = Vec::new();
        for entry in CATALOG {
            assert!(
                !names.contains(&entry.name),
                "duplicate catalog name {}",
                entry.name
            );
            names.push(entry.name);
            assert_eq!(
                curve_named(entry.curve),
                Some(entry.spec.curve),
                "{} names the wrong curve",
                entry.name
            );
        }
        assert_eq!(names.len(), 15, "the catalog has fifteen named specs");
    }
}
