package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * What a [StatusGlyph] draws — the `StatusGlyph.Kind` enum of
 * `ios:Design/StatusGlyph.swift:22-29`. Colour kinds take the caller's
 * `Color` as given (alpha included), already resolved for light/dark by the
 * caller — typically a [StatusTone] or [Palette] role.
 */
sealed interface StatusGlyphKind {
    /** Draws nothing; the box still takes its 12 × scale dp. */
    data object None : StatusGlyphKind

    /** 2 columns × 3 rows of violet circles, brightness chasing the ring every 750 ms. */
    data object Spinner : StatusGlyphKind

    /** The transcript's 3 × 3 pastel spinner: blue, orange and pink rows. */
    data object Trailer : StatusGlyphKind

    /** One static 7 dp circle in the caller's colour (never animates). */
    data class Dot(val color: Color) : StatusGlyphKind

    /** The stroked tick (`check.svg` on a 16 grid) in the caller's colour (never animates). */
    data class Check(val color: Color) : StatusGlyphKind
}

/** `glyphTints` row 0/1/2, light (`StatusGlyph.swift:54-59`). Private on iOS; ticket 01 leaves them here. */
private val GLYPH_TINTS_LIGHT = listOf(Color(0xFF7965EC), Color(0xFF5B43E8), Color(0xFF4332AC))

/** `glyphTints` row 0/1/2, dark. */
private val GLYPH_TINTS_DARK = listOf(Color(0xFFABA1F9), Color(0xFF8B7CF6), Color(0xFF7266CA))

/** `trailerTints` row 0/1/2 — the same pastels in light and dark (`StatusGlyph.swift:56`). */
private val TRAILER_TINTS = listOf(Color(0xFFB6D3EF), Color(0xFFEDB185), Color(0xFFF888A0))

/**
 * The glyph's pure math — `gspin_opacity` (`StatusGlyph.swift:61-67`) and
 * the phase/geometry formulas from `layoutSubviews` (`:90-124`) and
 * `restyle()` (`:145-151`). Pinned by `StatusGlyphMathTest`.
 *
 * `opacity`, `cycle`, `fillCellAlphas` and the phase tables are the draw
 * path: no allocation per frame. The geometry helpers allocate their result
 * arrays, so the draw phase keeps the calls inside the `drawWithCache`
 * block, where they run only on a size or capture change.
 */
object StatusGlyphMath {

    /**
     * One full opacity lap: `a.duration = 0.75` (`StatusGlyph.swift:157`)
     * — the same 750 ms [Motion.SpinnerCycleMs] owns; durations always come
     * from the ticket-04 tokens, never a pasted literal.
     */
    const val PERIOD_NANOS: Long = Motion.SpinnerCycleMs * 1_000_000L

    /** The keyframes' dim floor: `values = [1, 0.1, 0.1, 1]` (`:154`). */
    const val DIM = 0.1f

    /** `ring = [[0, 1], [5, 2], [4, 3]]` (`:53`) — counter-clockwise on screen. */
    private val RING = arrayOf(intArrayOf(0, 1), intArrayOf(5, 2), intArrayOf(4, 3))

    /** Spinner phases, row-major [TL, TR, ML, MR, BL, BR] = [0, 1/6, 5/6, 2/6, 4/6, 3/6]. */
    val SPINNER_PHASES: FloatArray = FloatArray(6) { i -> spinnerPhase(i / 2, i % 2) }

    /** Trailer phases, row-major: [0.75, 0.5, 0.75, 0.5, 0.25, 0.5, 0.25, 0, 0.25]. */
    val TRAILER_PHASES: FloatArray = FloatArray(9) { i -> trailerPhase(i / 3, i % 3) }

    /**
     * `gspin_opacity` (`StatusGlyph.swift:62-67`): hold bright, fall to
     * [dim] over the first 45 %, rest there, snap back over the last 8 %.
     * A linear `CAKeyframeAnimation` with `values = [1, 0.1, 0.1, 1]`,
     * `keyTimes = [0, 0.45, 0.92, 1]` is exactly this function, which is
     * why the port can sample it per frame instead of animating.
     * `t` wraps — `u = t − floor(t)` — so negative and ≥ 1 inputs work.
     */
    fun opacity(t: Float, dim: Float = DIM): Float {
        val u = t - floor(t)
        return when {
            u < 0.45f -> 1f + (dim - 1f) * u / 0.45f
            u < 0.92f -> dim
            else -> dim + (1f - dim) * (u - 0.92f) / 0.08f
        }
    }

    /**
     * `frac(N / 750 ms)` in [0, 1). Long **modulo before** the Float
     * conversion: `frameNanos.toFloat() / 7.5e8f` loses all precision once
     * uptime nanos reach ~10¹⁴.
     */
    fun cycle(frameNanos: Long): Float =
        Math.floorMod(frameNanos, PERIOD_NANOS) / PERIOD_NANOS.toFloat()

    /**
     * The draw phase's cycle: the clock time only when animated; 0 — the
     * reduce-motion static pattern — when not. [frameNanos] is read by the
     * caller only on the animated branch, so a static glyph never observes
     * a frame tick.
     */
    internal fun drawCycle(animated: Boolean, frameNanos: Long): Float =
        if (animated) cycle(frameNanos) else 0f

    /**
     * Writes one alpha per cell into [out] (size 6 or 9):
     * `opacity(cycle + phase[i])` — a cell sits at cycle position
     * `frac(t / 0.75 s + phase)` because iOS's `timeOffset = phase · 0.75`.
     * No allocation.
     */
    fun fillCellAlphas(phases: FloatArray, cycle: Float, out: FloatArray) {
        for (i in phases.indices) out[i] = opacity(cycle + phases[i])
    }

    /** `ring[row][col] / 6` — the spinner cell's `timeOffset / 0.75`. */
    fun spinnerPhase(row: Int, col: Int): Float = RING[row][col] / 6f

    /** `(2 − row + |col − 1|) / 4` (`StatusGlyph.swift:148`) — the trailer cell's. */
    fun trailerPhase(row: Int, col: Int): Float = (2 - row + abs(col - 1)) / 4f

    // ---------------------------------------------------------------------
    // Geometry (dp at scale 1). Cell size and gap are FIXED in dp — they do
    // not scale with the bounds; only the check path does (through the box).
    // The helpers are unit-agnostic: pass box sizes in dp and leave
    // [dpToPx] at 1 (tests), or pass px sizes and the density (the draw
    // phase). Both grids overflow a 12-dp box and draw unclipped.
    // ---------------------------------------------------------------------

    /**
     * Spinner cell centres, row-major (TL, TR, ML, MR, BL, BR), as
     * `[x₀, y₀, x₁, y₁, …]` in the box's units:
     * `dx = (col − 0.5) · 5.0`, `dy = (row − 1) · 5.0` at scale 1 (step
     * `d + gap` = 5.0 dp). The 13.5-dp-tall grid overhangs a 12-dp box by
     * 0.75 top and bottom.
     */
    internal fun spinnerCellCenters(boxW: Float, boxH: Float, scale: Float, dpToPx: Float = 1f): FloatArray {
        val step = 5f * scale * dpToPx
        val cx = boxW / 2f
        val cy = boxH / 2f
        val out = FloatArray(12)
        for (i in 0 until 6) {
            val row = i / 2
            val col = i % 2
            out[2 * i] = cx + (col - 0.5f) * step
            out[2 * i + 1] = cy + (row - 1f) * step
        }
        return out
    }

    /**
     * Trailer cell centres, row-major, as `[x₀, y₀, x₁, y₁, …]`:
     * `dx = (col − 1) · 5.25`, `dy = (row − 1) · 5.25` at scale 1 (step
     * `d + gap` = 5.25 dp). The 14-dp grid overhangs a 12-dp box by 1 on
     * every side.
     */
    internal fun trailerCellCenters(boxW: Float, boxH: Float, scale: Float, dpToPx: Float = 1f): FloatArray {
        val step = 5.25f * scale * dpToPx
        val cx = boxW / 2f
        val cy = boxH / 2f
        val out = FloatArray(18)
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            out[2 * i] = cx + (col - 1f) * step
            out[2 * i + 1] = cy + (row - 1f) * step
        }
        return out
    }

    /** Cell radius: `d / 2` = 1.75 dp in both grids. */
    internal fun cellRadius(scale: Float, dpToPx: Float = 1f): Float = 1.75f * scale * dpToPx

    /** Dot radius: 3.5 dp — the 7 dp circle the code draws (`:109-110`), not the "dot 6" comment. */
    internal fun dotRadius(scale: Float, dpToPx: Float = 1f): Float = 3.5f * scale * dpToPx

    /**
     * The check's three path points `[x₁, y₁, x₂, y₂, x₃, y₃]` in box
     * units: `s = min(w, h) / 16 · 1.05`, origin `o = c − 8s`, then
     * M `o + (3.5s, 8.5s)` L `o + (6.5s, 11.5s)` L `o + (12.5s, 4.5s)`
     * (`check.svg`, `StatusGlyph.swift:111-124`). Unlike the cells, the
     * check scales through the box itself; only its stroke needs the
     * explicit × scale.
     */
    internal fun checkPathPoints(boxW: Float, boxH: Float): FloatArray {
        val s = min(boxW, boxH) / 16f * 1.05f
        val ox = boxW / 2f - 8f * s
        val oy = boxH / 2f - 8f * s
        return floatArrayOf(
            ox + 3.5f * s, oy + 8.5f * s,
            ox + 6.5f * s, oy + 11.5f * s,
            ox + 12.5f * s, oy + 4.5f * s,
        )
    }

    /** Check stroke: 1.6 dp × scale, round cap and join (`StatusGlyph.swift:39-42`). */
    internal fun checkStrokeWidth(scale: Float, dpToPx: Float = 1f): Float = 1.6f * scale * dpToPx
}

/**
 * The decorative 12 × 12 (× [scale]) status mark — the port of
 * `ios:Design/StatusGlyph.swift`, the desktop sidebar's status glyph.
 *
 * - **Draws outside its bounds** (the spinner overhangs 0.75 dp top and
 *   bottom, the trailer 1 dp on every side): never wrap it in a clip, and
 *   leave clipped hosts (a glass pill) at least 1 dp of room.
 * - **No semantics, no pointer input**: like `isUserInteractionEnabled =
 *   false` with no accessibility label on iOS, consumers put the status
 *   word in their own row label.
 * - The caller's [modifier] comes first, so a consumer `size` wins over
 *   the default 12 × scale dp box (the transcript's 14 × 14 trailer and
 *   15.75 × 15.75 spinner widget).
 * - Animated kinds read the shared [GlyphClock] in the draw phase only:
 *   per frame there is no recomposition, no re-layout and no allocation.
 *   Under reduce motion ([LocalReduceMotion]) each cell holds
 *   `opacity(phase)` — the cycle-0 static pattern — and nothing subscribes
 *   to the clock; a live flip takes effect on the next frame.
 */
@Composable
fun StatusGlyph(kind: StatusGlyphKind, modifier: Modifier = Modifier, scale: Float = 1f) {
    val reduce = LocalReduceMotion.current
    val dark = LocalIsDark.current
    val animated = !reduce && (kind == StatusGlyphKind.Spinner || kind == StatusGlyphKind.Trailer)
    // Previews and tests outside RobocoTheme's clock drive a fallback of
    // their own. It stays in phase with the app clock anyway: the frame
    // time base is absolute.
    val clock = LocalGlyphClock.current ?: remember { GlyphClock(null) }.also { GlyphClockDriver(it) }
    // Subscription lives in the effect (not in composition): a cancelled
    // composition must never leak a subscriber and keep the loop alive.
    DisposableEffect(clock, animated) {
        if (animated) clock.subscribers++
        onDispose { if (animated) clock.subscribers-- }
    }
    val tints: List<Color> = when (kind) {
        StatusGlyphKind.Trailer -> TRAILER_TINTS
        else -> if (dark) GLYPH_TINTS_DARK else GLYPH_TINTS_LIGHT
    }
    Spacer(
        modifier
            .size(12.dp * scale)
            .drawWithCache {
                // The cache re-runs only on a size or captured-value change
                // (kind, scale, dark, clock): all geometry, the check path
                // and the stroke are built here, never per frame.
                when (kind) {
                    StatusGlyphKind.None -> onDrawBehind { /* nothing: .none draws nothing */ }

                    StatusGlyphKind.Spinner -> {
                        val centers = StatusGlyphMath.spinnerCellCenters(size.width, size.height, scale, density)
                        val radius = StatusGlyphMath.cellRadius(scale, density)
                        val phases = StatusGlyphMath.SPINNER_PHASES
                        val alphas = FloatArray(phases.size)
                        onDrawBehind {
                            // frameNanos is read only here, and only when
                            // animated: the tick invalidates this draw.
                            val frame = if (animated) clock.frameNanos.longValue else 0L
                            val cyc = StatusGlyphMath.drawCycle(animated, frame)
                            StatusGlyphMath.fillCellAlphas(phases, cyc, alphas)
                            for (i in 0 until 6) {
                                drawCircle(
                                    color = tints[i / 2],
                                    radius = radius,
                                    center = Offset(centers[2 * i], centers[2 * i + 1]),
                                    alpha = alphas[i],
                                )
                            }
                        }
                    }

                    StatusGlyphKind.Trailer -> {
                        val centers = StatusGlyphMath.trailerCellCenters(size.width, size.height, scale, density)
                        val radius = StatusGlyphMath.cellRadius(scale, density)
                        val phases = StatusGlyphMath.TRAILER_PHASES
                        val alphas = FloatArray(phases.size)
                        onDrawBehind {
                            val frame = if (animated) clock.frameNanos.longValue else 0L
                            val cyc = StatusGlyphMath.drawCycle(animated, frame)
                            StatusGlyphMath.fillCellAlphas(phases, cyc, alphas)
                            for (i in 0 until 9) {
                                drawCircle(
                                    color = tints[i / 3],
                                    radius = radius,
                                    center = Offset(centers[2 * i], centers[2 * i + 1]),
                                    alpha = alphas[i],
                                )
                            }
                        }
                    }

                    is StatusGlyphKind.Dot -> {
                        val color = kind.color
                        val radius = StatusGlyphMath.dotRadius(scale, density)
                        onDrawBehind {
                            drawCircle(color = color, radius = radius, center = center)
                        }
                    }

                    is StatusGlyphKind.Check -> {
                        val color = kind.color
                        val points = StatusGlyphMath.checkPathPoints(size.width, size.height)
                        val path = Path().apply {
                            moveTo(points[0], points[1])
                            lineTo(points[2], points[3])
                            lineTo(points[4], points[5])
                        }
                        val stroke = Stroke(
                            width = StatusGlyphMath.checkStrokeWidth(scale, density),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                        onDrawBehind { drawPath(path, color, style = stroke) }
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------
// Preview / Roborazzi fixture
// ---------------------------------------------------------------------------

/**
 * The shared fixture for the previews and the `StatusGlyphScreenshotTest`
 * goldens: the five kinds at scale 1 plus the ×1.75 loader spinner. Each
 * glyph sits in a 4-dp-padded cell so the unclipped overflow (0.75 dp
 * spinner, 1 dp trailer) stays visible, and the row itself is 16-dp padded
 * — Roborazzi crops captures to the node bounds. No text: the goldens must
 * not depend on fonts. Requires [RobocoTheme] (the tones and [LocalIsDark]
 * have no default) and a frozen [ProvideGlyphClock].
 */
@Composable
internal fun StatusGlyphSampleRow() {
    val palette = RobocoTheme.palette
    Row(
        Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SampleCell { StatusGlyph(StatusGlyphKind.Spinner) }
        SampleCell { StatusGlyph(StatusGlyphKind.Trailer) }
        SampleCell { StatusGlyph(StatusGlyphKind.Dot(palette.status.input)) }
        SampleCell { StatusGlyph(StatusGlyphKind.Dot(palette.status.failed)) }
        SampleCell { StatusGlyph(StatusGlyphKind.Check(palette.status.done)) }
        SampleCell { StatusGlyph(StatusGlyphKind.Spinner, scale = 1.75f) }
    }
}

/** One glyph in a 4-dp-padded box: the overflow stays visible around it. */
@Composable
private fun SampleCell(glyph: @Composable () -> Unit) {
    Box(Modifier.padding(4.dp)) { glyph() }
}

@Preview(name = "StatusGlyph light")
@Composable
private fun StatusGlyphLightPreview() {
    RobocoTheme(Appearance.Light) {
        // Frozen at 0 ns: the preview never starts a frame loop, and it
        // overrides the theme's live clock (nested inside it).
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusGlyphSampleRow() }
    }
}

@Preview(name = "StatusGlyph dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusGlyphDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusGlyphSampleRow() }
    }
}
