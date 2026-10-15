package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/**
 * True for refs whose bytes are still crossing to the engine (ticket 67).
 * Upstream writes `pending://` refs (`RowView.swift:226`); Roboco's own
 * echo names them `pending/<uuid>/<name>` (android-app#08 §Send step 5,
 * the web's legacy synthetic ref) until the upload lands and the row
 * switches to the absolute host path. Both prefixes are accepted, like
 * the Roboco web (`user-attachments.tsx:122-126`) and desktop
 * (`transcript.rs:6186`); real host paths are absolute (`/…`, `C:\…`),
 * so `pending/` is unambiguous. If android-app#08 lands a different echo
 * prefix, add it here.
 */
fun isPendingReference(reference: String): Boolean =
    reference.startsWith("pending://") || reference.startsWith("pending/")

/**
 * The ring's pure geometry and copy math (ticket 67; every value is iOS
 * `RowView.swift:450-505`, pt → dp 1:1):
 * - diameter `min(34, 0.55 · min(w, h))` (`:497`);
 * - 3 dp stroke, round caps, start at −90°, clockwise (`:477-499`);
 * - arc sweep `360° · clamp(p, 0.02, 1)` — the 0.02 floor keeps a
 *   round-capped 7.2° stub at 0 % (`:464`);
 * - label `round(clamp(p, 0, 1) × 100)%` — [roundToInt] rounds half away
 *   from zero, Swift `.rounded()`'s rule for p ≥ 0 (`:465`);
 * - label size 9 when d < 30, else 10.5 — the initial 11 pt font in
 *   `init` is dead code, `layoutSubviews` always overwrites it (`:503`);
 * - NaN progress is treated as 0 (a defensive branch; the session layer
 *   never produces NaN).
 *
 * [SweepEasing] is Core Animation's `kCAMediaTimingFunctionDefault` —
 * the implicit-action timing an assign of `strokeEnd` outside an
 * animation block gets on iOS (`:464`). The fade tweens themselves come
 * from ticket 04's tokens ([Motion.uploadRingIn]/[Motion.uploadRingOut]).
 */
object UploadRingMath {
    /** The ring's diameter cap (`:497`). */
    const val MAX_DIAMETER_DP = 34f

    /** `d = min(34, 0.55 · min(w, h))` (`:497`). */
    const val DIAMETER_FRACTION = 0.55f

    /** The stroke width, track and arc, centred on the path (`:477`). */
    const val STROKE_DP = 3f

    /** Below this diameter the label drops to [SMALL_LABEL_PT] (`:503`). */
    const val SMALL_RING_DP = 30f

    /** The label size for small rings (`:503`). */
    const val SMALL_LABEL_PT = 9f

    /** The label size for normal rings (`:503`). */
    const val LABEL_PT = 10.5f

    /** The arc floor: even 0 % shows a 7.2° round-capped stub (`:464`). */
    const val MIN_ARC = 0.02

    /** The scrim: black over the whole thumbnail (`:473`). */
    const val SCRIM_ALPHA = 0.38f

    /** The track: white at 30 % (`:481`). */
    const val TRACK_ALPHA = 0.3f

    /** The sweep tween's duration (Core Animation's implicit action). */
    const val SWEEP_MS = 250

    /** `kCAMediaTimingFunctionDefault`: the implicit-action curve. */
    val SweepEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** `min(34, 0.55 · min(w, h))`, in dp. */
    fun diameterDp(wDp: Float, hDp: Float): Float = min(MAX_DIAMETER_DP, min(wDp, hDp) * DIAMETER_FRACTION)

    /** The arc sweep in degrees, from the −90° start. */
    fun sweepDegrees(p: Double): Float = 360f * clean(p).coerceIn(MIN_ARC, 1.0).toFloat()

    /** `round(clamp(p, 0, 1) × 100)` — Swift `.rounded()` for p ≥ 0. */
    fun percent(p: Double): Int = (clean(p).coerceIn(0.0, 1.0) * 100).roundToInt()

    /** `"<N>%"` — the visible label and the a11y value. */
    fun label(p: Double): String = "${percent(p)}%"

    /** 9 below a 30 dp ring, else 10.5 — a fixed UIKit size, not Dynamic Type. */
    fun labelPt(dDp: Float): Float = if (dDp < SMALL_RING_DP) SMALL_LABEL_PT else LABEL_PT

    private fun clean(p: Double) = if (p.isNaN()) 0.0 else p
}

/** The overlay's current layer alpha — tests only (like 66's [ImageTileAlphaKey]). */
val UploadRingAlphaKey = SemanticsPropertyKey<Float>("UploadRingAlpha")

/**
 * The arc's current sweep in degrees — tests only. Deliberate deviation
 * from the ticket's "the sweep animation invalidates drawing, not
 * composition": reading the sweep animatable here recomposes this one
 * small scope per frame while the 250 ms sweep tween runs, the same cost
 * the alpha key accepts for the fades, and it is the only way the test
 * can observe a mid-sweep value. The draw path still reads the animatable
 * directly, so nothing else recomposes.
 */
val UploadRingSweepKey = SemanticsPropertyKey<Float>("UploadRingSweep")

/**
 * Scrim + ring + percent over a pending thumbnail (ticket 67; iOS
 * `UploadRingView`, `ios:Transcript/RowView.swift:450-505`). Fill the
 * tile and place it inside ticket 66's rounded clip — the overlay slot
 * of [ImageTile] does both. No touch handling: a `Box` without pointer
 * modifiers consumes nothing, so the tile's tap still fires through.
 *
 * [progress] is the **lambda** (`TranscriptState.uploadProgress`), so a
 * progress change recomposes this scope only, not the row. One progress
 * value serves every pending tile of the session (iOS fans it out the
 * same way). Behaviour, bottom to top:
 *
 * - black scrim @ 0.38 over the whole tile;
 * - a white @ 0.3 track circle and a white arc (3 dp, round caps, from
 *   12 o'clock, clockwise) whose sweep is `360° · clamp(p, 0.02, 1)`;
 * - the percentage, Geist SemiBold, white, 9/10.5 dp fixed, centred.
 *
 * The whole overlay fades through one layer alpha — scrim, ring and
 * label together: in over 200 ms easeInOut only when it is below 1
 * (`:463`), out over 250 ms when progress ends (`:460`). During the
 * fade-out the arc and label **keep the last non-null value** (the nil
 * branch returns before touching them, `:457-461`), and once the alpha
 * reaches 0 nothing is emitted at all — no draw, no semantics node. The
 * arc snap-animates between values over 250 ms ([UploadRingMath.SweepEasing]);
 * a ring first composed while progress is already non-null starts **at**
 * that value (iOS sets `strokeEnd` before the layer ever renders).
 * Not gated by Reduce Motion — iOS keeps this animation (`RowView.swift`
 * has no `isReduceMotionEnabled` check; ticket 04 lists "upload ring" as
 * a keep).
 *
 * A11y: one merged node, present only while the overlay is visible —
 * `contentDescription` "Uploading", `stateDescription` the label text
 * ("42%"), testTag `upload-progress`, no actions. No
 * `progressBarRangeInfo` (TalkBack would read the percent twice).
 *
 * [startVisible] is previews/tests/goldens only: it starts the layer at
 * full alpha instead of fading in, freezing the fixture look. Production
 * callers never pass it.
 *
 * @param progress the session's upload fraction, `null` when nothing is
 *   in flight.
 */
@Composable
fun UploadRing(
    progress: () -> Double?,
    modifier: Modifier = Modifier,
    startVisible: Boolean = LocalInspectionMode.current,
) {
    val p = progress() // snapshot read → recomposes this scope only

    // The last non-null value, kept for the fade-out (iOS's nil branch
    // returns before touching the arc and the label). A plain array, not
    // snapshot state: nothing else reads it.
    val last = remember { DoubleArray(1) { Double.NaN } }
    if (p != null) last[0] = p
    val shown = if (p != null) p else last[0]
    val visible = p != null

    // iOS: alpha = 0 at init (:472); a ring created while progress is
    // already non-null still fades in over 200 ms (:463).
    val fade = remember { Animatable(if (startVisible && p != null) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            if (fade.value < 1f) fade.animateTo(1f, Motion.uploadRingIn())
        } else {
            fade.animateTo(0f, Motion.uploadRingOut())
        }
    }

    // Snap on first appearance (iOS sets strokeEnd before the first
    // render); later changes animate with the implicit-action tween.
    val sweep = remember {
        Animatable(if (shown.isNaN()) UploadRingMath.sweepDegrees(0.0) else UploadRingMath.sweepDegrees(shown))
    }
    LaunchedEffect(shown) {
        if (!shown.isNaN()) {
            sweep.animateTo(
                UploadRingMath.sweepDegrees(shown),
                tween(durationMillis = UploadRingMath.SWEEP_MS, easing = UploadRingMath.SweepEasing),
            )
        }
    }

    // One composition read of the fade: the emission gate and the test
    // keys stay current, recomposing this small scope per frame only
    // while a fade runs (200/250 ms) and never while idle.
    val overlayAlpha = fade.value
    if (!visible && overlayAlpha == 0f) return // pending + never shown: emit nothing

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .drawBehind {
                // `size` here is the DrawScope's, not a modifier lambda's.
                drawRect(Color.Black.copy(alpha = UploadRingMath.SCRIM_ALPHA))
                val d = UploadRingMath.diameterDp(size.width.toDp().value, size.height.toDp().value).dp.toPx()
                val r = d / 2f
                val c = center
                val sw = UploadRingMath.STROKE_DP.dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = UploadRingMath.TRACK_ALPHA),
                    radius = r,
                    center = c,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White,
                    startAngle = -90f, // 12 o'clock, clockwise
                    sweepAngle = sweep.value,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(d, d),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
            .semantics(mergeDescendants = true) {
                testTag = "upload-progress"
                contentDescription = "Uploading"
                stateDescription = UploadRingMath.label(shown)
                // Test-only observability: both reads run in composition, so
                // this one-node scope recomposes per frame while a fade or a
                // sweep tween runs (200/250 ms) and never while idle.
                this[UploadRingAlphaKey] = overlayAlpha
                this[UploadRingSweepKey] = sweep.value
            },
    ) {
        val dDp = UploadRingMath.diameterDp(maxWidth.value, maxHeight.value)
        // Clearing the text's own semantics keeps the merged node reading
        // "Uploading, 42%" once; a merging descendant is never folded into
        // the tile's clickable node, so the ring stays its own element.
        BasicText(
            text = UploadRingMath.label(shown),
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = dDp.dp)
                .clearAndSetSemantics {},
            style = uiStyle(
                Face.SansSemibold,
                LocalTypeScale.current.fixed(UploadRingMath.labelPt(dDp)),
                Color.White,
            ).copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// The fixture (previews, Roborazzi goldens, the gallery's static rows).
// ---------------------------------------------------------------------------

/** iOS `systemTeal` (light) — the fixture's photo stand-in, same in dark. */
internal val UploadRingFixtureTeal = Color(0xFF30B0C7.toInt())

/** iOS `systemPink` (light) — the fixture's rectangle, same in dark. */
internal val UploadRingFixturePink = Color(0xFFFF2D55.toInt())

/**
 * The deterministic fixture tile: a teal square with the pink rectangle
 * of the iOS UI test's image (240×180 with a 120×100 rect at (60, 40) —
 * 25 % horizontal and 22.2 % vertical insets), clipped to the tile's
 * continuous corners exactly as ticket 66's [ImageTile] clip would, with
 * the ring over it when the reference is pending. The colours stand in
 * for photo pixels, so they ignore the appearance.
 */
@Composable
internal fun UploadRingFixtureTile(
    sizeDp: Float,
    p: Double?,
    reference: String = "pending/fixture/a.png",
) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(rememberSmoothRoundedShape(imageTileRadius(sizeDp, sizeDp)))
            .background(UploadRingFixtureTeal),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.5f) // 25 % insets left and right
                .fillMaxHeight(100f / 180f) // 22.2 % insets top and bottom
                .background(UploadRingFixturePink),
        )
        if (isPendingReference(reference)) {
            UploadRing(progress = { p }, modifier = Modifier.matchParentSize(), startVisible = true)
        }
    }
}

/**
 * The golden row: 76 dp @ 0.42, 76 dp @ 0.0 and 40 dp @ 1.0, 8 dp apart
 * — the three cases the Roborazzi goldens and the previews show, alpha
 * frozen at 1 ([UploadRingFixtureTile] passes `startVisible = true`).
 */
@Composable
internal fun UploadRingFixtureRow(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UploadRingFixtureTile(76f, 0.42)
        UploadRingFixtureTile(76f, 0.0)
        UploadRingFixtureTile(40f, 1.0)
    }
}

@Preview(name = "light")
@Composable
private fun UploadRingLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            UploadRingFixtureRow()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UploadRingDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            UploadRingFixtureRow()
        }
    }
}
