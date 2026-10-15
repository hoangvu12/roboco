package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.max
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.WidgetKind

/**
 * A plain mirror of `WidgetKind.ToolRail` (dp, widget-local — x from the
 * widget's left edge, tops from the widget's top, which is the group
 * header's top), so previews, tests and the gallery need no uniffi. Every
 * value is Rust's; never recompute the numbers here. Identity equality on
 * purpose: one instance per row model, so `remember(spec, …)` rebuilds the
 * paths per model like iOS rebuilds its views per `configure`.
 */
@Immutable
class RailSpec(
    val trunkX: Float,
    val bend: Float,
    val branchEnd: Float,
    val rowMid: Float,
    val tops: FloatArray,
    val heights: FloatArray,
) {
    val count: Int get() = tops.size

    companion object {
        /** Lifts the uniffi record into the plain mirror. */
        fun of(k: WidgetKind.ToolRail) = RailSpec(
            k.trunkX, k.bend, k.branchEnd, k.rowMid,
            k.tops.toFloatArray(), k.heights.toFloatArray(),
        )
    }
}

/**
 * The rail's full segment paths in px plus their arc lengths
 * (`ios:Transcript/ToolViews.swift:101-119`): one lazily built [Path] per
 * row and part, one merged settled path per `first` (single-slot cache —
 * `first` never alternates within one spec's life), lengths measured once
 * with [PathMeasure]. Geometry: dp from the widget × the density.
 */
internal class RailPaths(private val spec: RailSpec, private val density: Float) {

    private val count = spec.count
    private val incomingPaths = arrayOfNulls<Path>(count)
    private val branchPaths = arrayOfNulls<Path>(count)
    private val continuationPaths = arrayOfNulls<Path?>(count) // row count-1 has none
    private val lengths = FloatArray(count * 3)
    private val measure = PathMeasure()
    private var settledFirst = -1
    private var settledPath: Path? = null

    /** `M(trunkX, top) L(trunkX, mid − bend)` — always exists. */
    fun incoming(i: Int): Path {
        incomingPaths[i]?.let { return it }
        val p = Path()
        appendIncoming(p, i)
        incomingPaths[i] = p
        return p
    }

    /** `M(trunkX, mid − bend) Q(ctrl (trunkX, mid), end (trunkX + bend, mid)) L(branchEnd, mid)`. */
    fun branch(i: Int): Path {
        branchPaths[i]?.let { return it }
        val p = Path()
        appendBranch(p, i)
        branchPaths[i] = p
        return p
    }

    /** `M(trunkX, mid − bend) L(trunkX, top + heights[i])` — only `i < count − 1`. */
    fun continuation(i: Int): Path? {
        if (i + 1 >= count) return null
        continuationPaths[i]?.let { return it }
        val p = Path()
        appendContinuation(p, i)
        continuationPaths[i] = p
        return p
    }

    fun path(part: RailPart, row: Int): Path = when (part) {
        RailPart.Continuation -> continuation(row) ?: error("row $row has no continuation")
        RailPart.Incoming -> incoming(row)
        RailPart.Branch -> branch(row)
        RailPart.Cover -> error("the cover is a rect, not a path")
    }

    /** The segment's arc length in px, measured once and cached. */
    fun length(part: RailPart, row: Int): Float {
        check(part != RailPart.Cover) { "the cover has no length" }
        val idx = row * 3 + part.ordinal
        val cached = lengths[idx]
        if (cached > 0f) return cached
        measure.setPath(path(part, row), false)
        val len = measure.length
        lengths[idx] = len
        return len
    }

    /**
     * Rows `0` until [first] as **one** path, in iOS order per row —
     * incoming, branch, then the continuation only when its successor is
     * settled too — so the joints never double their alpha (#621 draws one
     * path per row and its elbows visibly darken).
     */
    fun settled(first: Int): Path {
        if (settledFirst == first) return requireNotNull(settledPath)
        val p = Path()
        for (i in 0 until first) {
            appendIncoming(p, i)
            appendBranch(p, i)
            // The last settled row's trunk extends only once its successor draws.
            if (i + 1 < first) appendContinuation(p, i)
        }
        settledFirst = first
        settledPath = p
        return p
    }

    private fun appendIncoming(p: Path, i: Int) {
        val trunkX = spec.trunkX * density
        val top = spec.tops[i] * density
        val mid = top + spec.rowMid * density
        p.moveTo(trunkX, top)
        p.lineTo(trunkX, mid - spec.bend * density)
    }

    private fun appendBranch(p: Path, i: Int) {
        val trunkX = spec.trunkX * density
        val mid = (spec.tops[i] + spec.rowMid) * density
        val bend = spec.bend * density
        p.moveTo(trunkX, mid - bend)
        p.quadraticTo(trunkX, mid, trunkX + bend, mid)
        p.lineTo(spec.branchEnd * density, mid)
    }

    private fun appendContinuation(p: Path, i: Int) {
        val trunkX = spec.trunkX * density
        val mid = (spec.tops[i] + spec.rowMid) * density
        val bend = spec.bend * density
        p.moveTo(trunkX, mid - bend)
        p.lineTo(trunkX, (spec.tops[i] + spec.heights[i]) * density)
    }
}

/**
 * The activity rail of an expanded tool group (ticket 121;
 * `ios:Transcript/ToolViews.swift:64-229`): a 1 dp trunk from under the
 * header chevron down to a quadratic elbow at each 36 dp row, a branch to
 * just before the row's tool icon, and the trunk running on through open
 * details into the next row.
 *
 * Rows that arrive while the group is live **draw in** — the previous row's
 * trunk grows, the elbow bends, the branch extends — staggered 65 ms over
 * 480 ms with the ease-out-quint keyframe sampling, the row's icon and text
 * fading in under a background-coloured cover that fades out with its
 * branch. A row is never animated twice ([ToolRailMemory]); groups on
 * screen when the transcript attaches (the 0.8 s quiet window) and groups
 * under Reduce Motion are static; an in-flight draw-in is re-planned per
 * model and snaps to fully drawn when the count did not grow (iOS parity).
 *
 * The rail is paint only: no semantics node, no pointer input — taps fall
 * through to 120's `ToolToggle` underneath. Nothing clips: the cover runs
 * to the **row's** right edge, far outside the 63 dp widget box (iOS sets
 * `clipsToBounds = false`).
 *
 * @param groupKey   `RowDisplay.key` of the tool-group row
 * @param rowWidthDp `RowDisplay.width − widget.x` — the widget-local x of the row's right edge
 * @param live       the row carries a `Shimmer` widget (`RowContext.live`)
 * @param buildToken identity of the row model; a new token is a new iOS
 *   `configure`, so the plan is re-made. Never key the plan on `spec` alone.
 */
@Composable
fun ToolRail(
    groupKey: ULong,
    spec: RailSpec,
    rowWidthDp: Float,
    live: Boolean,
    buildToken: Any?,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    // Decided once per model, in composition (iOS decides at every
    // `configure`). Never plan again on a plain recomposition of the same
    // model: the second plan would see known == count and kill the animation.
    val plan = remember(buildToken, groupKey) { ToolRailMemory.plan(groupKey, spec.count, live, reduce) }
    val schedule = remember(plan) {
        plan.animateFrom?.let { railSchedule(it, spec.count, plan.brandNew) } ?: emptyList()
    }
    // 0 until the first frame, so the first drawn frame shows every stroke
    // hidden and every cover opaque — the new row's content never flashes.
    val elapsed = remember(plan) { mutableFloatStateOf(0f) }
    LaunchedEffect(plan) {
        val from = plan.animateFrom ?: return@LaunchedEffect
        val total = railTotalMs(from, spec.count, plan.brandNew)
        // iOS's t0 is CACurrentMediaTime() at init; here it is the first
        // frame time after composition (≤ 1 frame later). No Compose
        // animate() helpers: they carry their own easing and duration scale.
        val t0 = withFrameMillis { it }
        while (true) {
            val e = withFrameMillis { it } - t0
            elapsed.floatValue = e.toFloat()
            if (e >= total) break
        }
    }
    // Restyle() on iOS resolves the colours against the view's own traits;
    // here the draw lambda simply captures the colours of the app
    // appearance (never the system's), so a switch recolours the next frame
    // while a running draw-in continues.
    val dark = LocalIsDark.current
    val stroke = Color(TranscriptPalette(dark).color(ColorRole.TOOL_RAIL))
    val coverColor = RobocoTheme.palette.background
    val uiDensity = LocalDensity.current
    val density = uiDensity.density
    val paths = remember(spec, density) { RailPaths(spec, density) }
    // 1 dp wide with butt caps (1 pt → 1 dp, not a hairline); Stroke's
    // default join is Miter. Hoisted: the draw lambda allocates nothing.
    val style = remember(uiDensity) { with(uiDensity) { Stroke(width = 1.dp.toPx(), cap = StrokeCap.Butt) } }
    val segment = remember { Path() } // reused every frame
    val measure = remember { PathMeasure() }
    // Cover geometry: the row's header strip, 2 · rowMid tall, from
    // branchEnd + 2 to the row's right edge — outside the widget box.
    val coverLeftPx = (spec.branchEnd + 2f) * density
    val coverWidthPx = max(0f, rowWidthDp - spec.branchEnd - 2f) * density
    val coverHeightPx = 2f * spec.rowMid * density
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val first = plan.animateFrom ?: spec.count
                if (first > 0) drawPath(paths.settled(first), stroke, style = style)
                val e = elapsed.floatValue // draw-phase read only
                for (anim in schedule) {
                    val v = railValue(anim, e)
                    if (v <= 0f) continue
                    if (anim.part == RailPart.Cover) {
                        // Background colour at the branch's progress; the
                        // alpha parameter avoids a per-frame Colour copy.
                        drawRect(
                            color = coverColor,
                            topLeft = Offset(coverLeftPx, spec.tops[anim.row] * density),
                            size = Size(coverWidthPx, coverHeightPx),
                            alpha = v,
                        )
                    } else if (v >= 1f) {
                        drawPath(paths.path(anim.part, anim.row), stroke, style = style)
                    } else {
                        // strokeEnd: the first v · arc length of the segment.
                        val full = paths.path(anim.part, anim.row)
                        segment.rewind()
                        measure.setPath(full, false)
                        measure.getSegment(0f, paths.length(anim.part, anim.row) * v, segment, true)
                        drawPath(segment, stroke, style = style)
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------
// Previews — the debug fixture (ToolRailGallerySection.kt) is not on this
// source set's classpath, so a minimal copy of its static rows lives here,
// like ticket 120's preview rows.
// ---------------------------------------------------------------------------

@Preview(name = "Tool rail light")
@Composable
private fun ToolRailLightPreview() {
    RobocoTheme(Appearance.System) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ToolRailPreviewFixture()
        }
    }
}

@Preview(name = "Tool rail dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToolRailDarkPreview() {
    RobocoTheme(Appearance.System) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            ToolRailPreviewFixture()
        }
    }
}

/**
 * The worked fixture (three rows, the middle one with a 60 dp open detail),
 * static: the rail box at x = 18 in a 390 × 220 dp row, with stand-in row
 * content under the rail so the geometry reads (the real icons and text are
 * Rust paint, tickets 124 and 62).
 */
@Composable
private fun ToolRailPreviewFixture() {
    val palette = RobocoTheme.palette
    val spec = remember {
        RailSpec(14.0625f, 6.75f, 31.5f, 18f, floatArrayOf(31.5f, 67.5f, 163.5f), floatArrayOf(36f, 96f, 36f))
    }
    Box(Modifier.size(390.dp, 220.dp)) {
        for (i in 0 until spec.count) {
            val top = spec.tops[i]
            // Widget-local (36, top + 9) → row-local x 54: the icon stand-in.
            Box(Modifier.absoluteOffset(x = 54.dp, y = (top + 9).dp).size(18.dp).background(palette.secondary))
            // Widget-local (63, top + 14) → row-local x 81: the label stand-in.
            Box(Modifier.absoluteOffset(x = 81.dp, y = (top + 14).dp).size(120.dp, 8.dp).background(palette.tertiary))
        }
        // The middle row's open-detail stand-in, at (63, top + 36) widget-local.
        Box(Modifier.absoluteOffset(x = 81.dp, y = 103.5.dp).size(200.dp, 60.dp).background(palette.codeBackground))
        Box(Modifier.absoluteOffset(x = 18.dp, y = 0.dp).size(63.dp, 199.5.dp)) {
            ToolRail(groupKey = 1uL, spec = spec, rowWidthDp = 372f, live = false, buildToken = null)
        }
    }
}
