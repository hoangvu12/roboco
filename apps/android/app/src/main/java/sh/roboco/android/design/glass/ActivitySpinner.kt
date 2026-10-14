package sh.roboco.android.design.glass

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The busy spinner of [ProminentGlassButton] (ticket 08): the iOS 26
 * `UIActivityIndicatorView.Style.medium` look, drawn in code — a 20 dp box,
 * 8 spokes at 45° steps, each a round-capped 2 dp line from radius 4.5 dp
 * to 9 dp, in the button's ink colour.
 *
 * It rotates in **8 discrete steps per 1000 ms** — `step =
 * (frameTimeMs / 125) % 8` — with the head spoke at full alpha and each
 * spoke behind it (counter-rotation order) 0.1 dimmer, floored at 0.3.
 * The per-frame loop is driven by [withInfiniteAnimationFrameMillis] (an
 * endless loop must be cancellable by an InfiniteAnimationPolicy: ui-test
 * cancels it under an auto-advancing clock, which otherwise never idles;
 * no policy in the app, so it spins as before): with a frozen test
 * clock (Roborazzi, `mainClock.autoAdvance = false`) no frame ever arrives
 * and the spinner sits at step 0, so goldens stay deterministic; in the
 * gallery and on a device it spins. It keeps rotating under Reduce Motion,
 * like `UIActivityIndicatorView`.
 */
@Composable
internal fun ActivitySpinner(ink: Color, modifier: Modifier = Modifier.size(20.dp)) {
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val frameTimeMs = withInfiniteAnimationFrameMillis { it }
            step = ((frameTimeMs / 125) % 8).toInt()
        }
    }
    Canvas(modifier) {
        val innerRadius = 4.5.dp.toPx()
        val outerRadius = 9.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        for (i in 0 until 8) {
            // Spoke i sits at (i · 45°) − 90°, so step 0 points up and the
            // head advances clockwise as `step` grows.
            val angle = Math.toRadians(i * 45.0 - 90.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val alpha = max(0.3f, 1f - 0.1f * ((step - i + 8) % 8))
            drawLine(
                color = ink,
                start = Offset(center.x + dx * innerRadius, center.y + dy * innerRadius),
                end = Offset(center.x + dx * outerRadius, center.y + dy * outerRadius),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
                alpha = alpha,
            )
        }
    }
}
