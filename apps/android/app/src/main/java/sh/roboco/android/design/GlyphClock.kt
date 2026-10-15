package sh.roboco.android.design

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import kotlinx.coroutines.flow.collectLatest

/**
 * The one shared animation clock behind every animated [StatusGlyph]
 * (android-ui-parity #11).
 *
 * iOS gives each `StatusGlyph` its own `CAKeyframeAnimation` — added on
 * `rebuild`, `didMoveToWindow` and trait changes — so two iOS glyphs can
 * sit out of phase. Android drives every glyph from **one** absolute frame
 * time instead: one `withFrameNanos` loop per clock, running only while at
 * least one animated glyph is composed (reduce motion off), so all glyphs
 * on screen pulse together and an empty screen pays for no frame callbacks
 * at all. Per-glyph timers would cost one frame loop each; see the ticket's
 * Roboco adaptation notes.
 */
@Stable
class GlyphClock internal constructor(internal val frozenAtNanos: Long?) {

    /**
     * The frame time the draw phase reads — absolute Choreographer nanos,
     * written by the driver's frame callback. A frozen clock (tests,
     * previews, the Roborazzi fixtures) starts at [frozenAtNanos] and is
     * never written, so captures are byte-stable.
     */
    internal val frameNanos = mutableLongStateOf(frozenAtNanos ?: 0L)

    /** Animated glyphs currently composed; the frame loop runs while this is > 0. */
    internal var subscribers by mutableIntStateOf(0)

    /** Frames delivered since the loop started; the clock tests assert on it. */
    @VisibleForTesting
    internal var framesTicked = 0L
}

/**
 * The ambient clock: `null` outside [ProvideGlyphClock], in which case
 * [StatusGlyph] drives a fallback clock of its own.
 */
val LocalGlyphClock = staticCompositionLocalOf<GlyphClock?> { null }

/**
 * Provides the [GlyphClock] for a subtree. [RobocoTheme] wraps its content
 * in one (innermost, inside the palette and reduce-motion providers), so
 * every glyph in the app shares it. Tests and previews nest a **frozen**
 * [ProvideGlyphClock] inside the theme to pin the animation at a cycle
 * position: `frozenAtNanos = 0L` → cycle 0, `300_000_000L` → cycle 0.4.
 * A frozen clock has no driver, so nothing ever writes its
 * [GlyphClock.frameNanos].
 */
@Composable
fun ProvideGlyphClock(frozenAtNanos: Long? = null, content: @Composable () -> Unit) {
    val clock = remember(frozenAtNanos) { GlyphClock(frozenAtNanos) }
    if (frozenAtNanos == null) GlyphClockDriver(clock)
    CompositionLocalProvider(LocalGlyphClock provides clock, content = content)
}

/**
 * The shared loop: while any animated glyph is subscribed, one
 * `withFrameNanos` per frame stores the absolute frame time in
 * [GlyphClock.frameNanos]; reading it in the draw phase invalidates only
 * the draw — no recomposition, no re-layout, no allocation. The loop
 * suspends by itself while the app is backgrounded (the Choreographer
 * produces no frames), and `collectLatest` tears it down the moment the
 * last subscriber leaves.
 */
@Composable
internal fun GlyphClockDriver(clock: GlyphClock) {
    LaunchedEffect(clock) {
        // Hoisted out of the loop: the same callback instance serves every
        // frame, so no lambda is allocated per frame.
        val onFrame: (Long) -> Unit = {
            clock.frameNanos.longValue = it
            clock.framesTicked++
        }
        // The INFINITE-animation frame: an endless loop must be cancellable
        // by an InfiniteAnimationPolicy (ui-test cancels it under an
        // auto-advancing clock, which otherwise never idles; accessibility
        // tooling can stop it). No policy in the app: it ticks as before.
        snapshotFlow { clock.subscribers > 0 }.collectLatest { active ->
            if (active) while (true) withInfiniteAnimationFrameNanos(onFrame)
        }
    }
}
