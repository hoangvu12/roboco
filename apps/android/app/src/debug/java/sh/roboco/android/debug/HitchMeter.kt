package sh.roboco.android.debug

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.TranscriptState
import java.util.Locale

/**
 * The Transcript Lab's hitch meter (android-ui-parity #136) — the port of
 * iOS's `HitchMeter` (`upstream/main:apps/ios/Zeron/Debug/`
 * `TranscriptLabViewController.swift:189-223`): a small monospaced-digit
 * label at the top right of the lab that counts frames whose interval is
 * strictly more than 1.5 × the frame budget, and every 30 frames shows
 * `" N hitches · layout X.Xms "` with the Rust worker time of the newest
 * layout pass.
 */

/**
 * The meter's label text: a leading and a trailing space (the only
 * "padding"), `·` is U+00B7, "hitches" has no plural rule, and `layout`
 * is `buildMicros / 1000` with one decimal. `Locale.ROOT` so the decimal
 * point never turns into a comma.
 */
internal fun meterText(hitches: Int, layoutMicros: ULong): String =
    String.format(Locale.ROOT, " %d hitches · layout %.1fms ", hitches, layoutMicros.toDouble() / 1000.0)

/**
 * iOS `HitchMeter.tick` (`TranscriptLabViewController.swift:210-221`),
 * pure: [onFrame] is called with the Choreographer's frame time and the
 * [FrameBudget]'s current budget, and returns the new label text on every
 * 30th frame, else `null`. A hitch is an interval **strictly greater**
 * than 1.5 × the budget; the first tick only records `last`; `hitches`
 * and `frames` are cumulative and never reset.
 */
class HitchCounter {
    var hitches = 0
        private set
    var frames = 0
        private set
    private var last = 0L

    /** One display frame. Returns the label text on every 30th frame. */
    fun onFrame(frameTimeNanos: Long, budgetNanos: Long, layoutMicros: ULong): String? {
        if (last > 0L && (frameTimeNanos - last).toDouble() > budgetNanos * 1.5) hitches++
        last = frameTimeNanos
        frames++
        return if (frames % 30 == 0) meterText(hitches, layoutMicros) else null
    }
}

/**
 * The meter: a self-reposting [Choreographer.FrameCallback] (iOS
 * `CADisplayLink`, preferred 80–120 Hz — see [RefreshRateRequest]) that
 * feeds [HitchCounter], plus a [TranscriptState.addFrameListener] that
 * refreshes `layoutMicros` on every applied frame
 * (`meter.layoutMicros = engine.frame().buildMicros()`). Composed only
 * with `--ez meter true` — without it nothing is composed, no frame
 * callback runs and no listener is registered (the Android form of iOS's
 * always-running hidden link is dropped: it would only add a callback per
 * vsync for no reading).
 *
 * No allocation per frame: the text is formatted only on every 30th tick,
 * and nothing else reads Compose state in the callback.
 */
@Composable
internal fun HitchMeter(state: TranscriptState, budget: FrameBudget, rate: RefreshRateRequest, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    DisposableEffect(state, budget, rate) {
        var layoutMicros = 0uL
        val removeListener = state.addFrameListener { _, new -> layoutMicros = new.buildMicros() }
        val counter = HitchCounter()
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                counter.onFrame(frameTimeNanos, budget.nanos, layoutMicros)?.let { text = it }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        rate.acquire()
        onDispose {
            choreographer.removeFrameCallback(callback)
            removeListener()
            rate.release()
        }
    }
    HitchMeterLabel(text, modifier)
}

/**
 * The meter's look, stateless: iOS's `UILabel` — monospaced-digit system
 * font 11 medium, `Palette.secondary` on `Palette.elevated` at 0.85,
 * 6-dp circular corner radius, clipped, centred; sized by its text (one
 * line, no insets). Draws nothing while [text] is empty — before the
 * 30th frame (and when the extra is absent, nothing composes at all).
 */
@Composable
fun HitchMeterLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        modifier = modifier
            .testTag("hitch-meter")
            .clip(RoundedCornerShape(6.dp))
            .background(palette.elevated.copy(alpha = 0.85f)),
        style = TextStyle(
            // The system font, not Geist: these are debug instruments on
            // iOS too (`monospacedDigitSystemFont`); `tnum` gives the
            // tabular digits that call gives for free.
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = typeScale.fixed(11f),
            color = palette.secondary,
            fontFeatureSettings = "tnum",
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

// ------------------------------------------------------------ previews

@Preview
@Composable
private fun HitchMeterLightPreview() {
    HitchMeterPreviewBody(Appearance.Light)
}

@Preview
@Composable
private fun HitchMeterDarkPreview() {
    HitchMeterPreviewBody(Appearance.Dark)
}

@Composable
private fun HitchMeterPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            HitchMeterLabel(" 3 hitches · layout 1.4ms ", Modifier.align(Alignment.Center))
        }
    }
}
