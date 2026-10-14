package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.color
import sh.roboco.android.transcript.RowContext
import sh.roboco.android.transcript.RowModel
import sh.roboco.android.transcript.RunPainter
import sh.roboco.android.transcript.StyleFonts
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.Widget

/**
 * The live tool-group title's shimmer sweep (ticket 122; iOS `ShimmerView`,
 * `ios:Transcript/ToolViews.swift:231-292`). Rust emits a `WidgetKind.Shimmer`
 * widget whose rect covers the live group's summary title; this file
 * re-draws the title's own glyphs in `ColorRole.TEXT` through a moving
 * triangle-wave alpha mask, so only the letters brighten (light mode:
 * darker — `Palette.text` is darker than the title's `TEXT_SECONDARY`).
 * The background never changes.
 *
 * The mask maths below is the iOS `CAGradientLayer` mask verbatim: a 9w-wide
 * gradient with three black highlights (`alpha 0, 1, 0`, half-width 0.36w,
 * `3w` apart), its left edge travelling from `−6w` to `0` over the
 * [Motion.ShimmerSweepMs] period, repeating forever. Derived from it:
 * highlight k peaks at `x_k = (−4.5 + 6t + 3k)·w` at phase `t ∈ [0, 1)`.
 * Under reduce motion the widget draws nothing (iOS `isHidden = true`).
 */

/** iOS ShimmerView maths (ToolViews.swift:245-273). Units: title widths. */
internal object ShimmerMath {
    /** The sweep period (3.4 s), one place only (`Motion.ShimmerSweepMs`). */
    const val PERIOD_NS: Long = Motion.ShimmerSweepMs * 1_000_000L

    /** Each highlight falls from alpha 1 to 0 over half this many widths. */
    const val HALF = 0.36f

    /** The gradient spans [-6W, 3W] at t = 0 and travels 6W per period. */
    const val START = -6f
    const val SPAN = 9f
    const val TRAVEL = 6f

    /** The 9 stops (k = 0, 1, 2 × `alpha 0, 1, 0`), fractions of the 9w span. */
    val STOPS = FloatArray(9) { j ->
        val k = j / 3
        val dx = (j % 3 - 1) * (HALF / SPAN)
        (1.5f + 3f * k) / SPAN + dx
    }

    /** Black, alpha 255 at each peak and 0 elsewhere (DST_IN reads alpha). */
    val COLORS = IntArray(9) { j -> if (j % 3 == 1) 0xFF000000.toInt() else 0x00000000 }

    /** Phase in [0, 1); 0 at or before the start instant. */
    fun phase(elapsedNanos: Long): Float =
        if (elapsedNanos <= 0L) 0f else ((elapsedNanos % PERIOD_NS).toDouble() / PERIOD_NS).toFloat()

    /** Highlight k's peak centre at phase t, in title widths. */
    fun centre(k: Int, t: Float): Float = -4.5f + 6f * t + 3f * k

    /** Mask alpha at x (in title widths) for phase t. */
    fun alpha(x: Float, t: Float): Float =
        (0..2).maxOf { k -> (1f - abs(x - centre(k, t)) / HALF).coerceIn(0f, 1f) }
}

/**
 * The DST_IN paint (iOS `layer.mask`). One per [ShimmerLayer] cache; the
 * shader is rebuilt only when the width changes, the phase arrives as a
 * local matrix translate per draw. Main thread only.
 */
internal class ShimmerMask {
    private val matrix = Matrix()
    private var shaderWidth = Float.NaN
    private var shader: LinearGradient? = null

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /** The mask paint for a `widthPx`-wide title at phase `t`. */
    fun update(widthPx: Float, t: Float): Paint {
        val w = max(widthPx, 1f)
        if (w != shaderWidth) {
            shader = LinearGradient(
                ShimmerMath.START * w, 0f, (ShimmerMath.START + ShimmerMath.SPAN) * w, 0f,
                ShimmerMath.COLORS, ShimmerMath.STOPS, Shader.TileMode.CLAMP,
            )
            paint.shader = shader
            shaderWidth = w
        }
        matrix.setTranslate(ShimmerMath.TRAVEL * t * w, 0f)
        shader!!.setLocalMatrix(matrix)
        return paint
    }
}

/**
 * One clock per row host (ticket 65's [sh.roboco.android.transcript.RealizedRow]):
 * it starts on the first frame after the row is composed and restarts only
 * when the row re-enters composition — a new model version for the same row
 * keeps the phase (iOS restarts on every configure; desktop's
 * `shimmer_started_at.get_or_insert(now)` keeps it, and Android follows
 * desktop here so a streaming thought does not stall the sweep).
 *
 * `now` is snapshot state read only in the draw phase, so every frame
 * re-draws the shimmer's own node without recomposing the row; `startNanos`
 * is a plain field because nothing but [run] ever reads it.
 */
@Stable
class ShimmerClock {
    private var startNanos = -1L
    private val now = mutableLongStateOf(-1L)

    /** Phase in [0, 1); 0 before the first frame. */
    fun phase(): Float {
        val n = now.longValue
        return if (n < 0L || startNanos < 0L) 0f else ShimmerMath.phase(n - startNanos)
    }

    /** The last frame stamp, −1 before the first frame. Tests only. */
    internal fun lastFrameNanos(): Long = now.longValue

    /** The frame loop; one per host row, cancelled by leaving composition. */
    suspend fun run(): Nothing {
        while (true) withFrameNanos { t -> if (startNanos < 0L) startNanos = t; now.longValue = t }
    }
}

/**
 * iOS `drawRuns`'s run filter (`RowModel.swift:90-94`), layer 0 only: the
 * runs whose baseline sits inside the widget's vertical range (open below,
 * closed above with 2 pt of slack) and which start left of its right edge.
 */
internal fun shimmerRuns(model: RowModel, x: Float, y: Float, w: Float, h: Float): IntArray {
    val runs = model.display.runs
    return model.runsByLayer[0].filter { i ->
        val r = runs[i]
        r.baseline > y && r.baseline <= y + h + 2f && r.x < x + w
    }.toIntArray()
}

/** The stateless painter facade (63); file-level, never rebuilt. */
private val ShimmerPainter = RunPainter()

/** Ticket 65's [RowWidget] branch. Thin: everything testable lives in [LiveShimmer]. */
@Composable
internal fun ShimmerWidget(ctx: RowContext, w: Widget) =
    LiveShimmer(ctx.row.shimmerClock, ctx.model, w, ctx.state.fonts, ctx.palette.color(ColorRole.TEXT))

/**
 * The reduce-motion gate and the frame loop. iOS decides once per
 * `ShimmerView` instance; Android reads [LocalReduceMotion] live, so a
 * settings change hides or shows the sweep on the next frame (04). Under
 * reduce motion the composable emits nothing and the loop is not launched.
 * Tests and the gallery host this directly, without a [RowContext].
 */
@Composable
internal fun LiveShimmer(clock: ShimmerClock, model: RowModel, w: Widget, fonts: StyleFonts, color: Int) {
    if (LocalReduceMotion.current) return // iOS isHidden = true
    LaunchedEffect(clock) { clock.run() }
    ShimmerLayer(model, w.x, w.y, w.w, w.h, fonts, color, phase = clock::phase)
}

/**
 * The stateless drawing half, so previews and goldens can pass a constant
 * phase. Fills the widget box the caller sized and placed (65's
 * `WidgetBox`): glyphs re-drawn in one explicit colour (no decorations, no
 * fades — `drawRunText`, never `drawRun`), then the DST_IN mask rect over
 * the whole box. The `graphicsLayer` keeps the offscreen compositing and
 * the clip on this node alone — the row's layer 0 is never re-recorded per
 * frame — and the phase is read only inside [drawWithCache]'s
 * `onDrawBehind`, so a frame invalidates the draw, never the cache.
 *
 * Not a control: no `clickable`, no `pointerInput` — 120's disclosure tap
 * target covers the same rect and must keep receiving taps.
 */
@Composable
internal fun ShimmerLayer(
    model: RowModel,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    fonts: StyleFonts,
    color: Int,
    phase: () -> Float,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxSize()
            .testTag("shimmer")
            .graphicsLayer {
                // BlendMode-level DST_IN needs an offscreen buffer; clip
                // enforces the iOS widget-rect clip (nothing outside it).
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
            }
            .drawWithCache {
                val runs = shimmerRuns(model, x, y, w, h)
                val mask = ShimmerMask()
                // WidgetBox places the box with absoluteOffset, which rounds
                // to whole px; translating by the same rounded values keeps
                // the highlight glyphs on the base glyphs (never doubled).
                val ox = x.dp.roundToPx().toFloat()
                val oy = y.dp.roundToPx().toFloat()
                onDrawBehind {
                    if (runs.isEmpty()) return@onDrawBehind
                    val t = phase() // the only per-frame state read
                    fonts.useDensity(density) // 62: one compare; get() needs it set
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val s = nc.save()
                        nc.translate(-ox, -oy) // row coordinates → widget-local
                        for (i in runs) ShimmerPainter.drawRunText(nc, model, i, density, fonts, color)
                        nc.restoreToCount(s)
                        nc.drawRect(0f, 0f, size.width, size.height, mask.update(size.width, t))
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------
// Previews: the specimen at the k=1 peak (t = 1/3), light and dark. The
// appearance is passed explicitly; `uiMode` is display-only.
// ---------------------------------------------------------------------------

@Preview(name = "Shimmer light")
@Composable
private fun ShimmerLightPreview() {
    RobocoTheme(Appearance.Light) { ShimmerSpecimen(dark = false, phase = 1f / 3f) }
}

@Preview(name = "Shimmer dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShimmerDarkPreview() {
    RobocoTheme(Appearance.Dark) { ShimmerSpecimen(dark = true, phase = 1f / 3f) }
}
