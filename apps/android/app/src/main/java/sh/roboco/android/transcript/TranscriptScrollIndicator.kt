package sh.roboco.android.transcript

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import sh.roboco.android.design.RobocoTheme

/**
 * The transcript's vertical scroll indicator (ticket 134, decision
 * TRANSCRIPT-D3): the iOS thumb `UIScrollView` shows with
 * `showsVerticalScrollIndicator = true` (TLV:184), a thin capsule
 * between the header and the composer stack whose geometry is 68's
 * thumb formula ([scrollIndicatorThumb]). It draws over the content in
 * the draw phase, reading only the viewport's snapshot state —
 * scrolling never recomposes — takes no input and adds no semantics,
 * as UIKit's.
 *
 * The thumb is visible while the user scrolls (68's `dragging` or
 * `flinging`; the bounce counts as flinging) and fades out after: hold
 * [INDICATOR_HOLD_MS], then [INDICATOR_FADE_MS] linear. Programmatic
 * moves (the follow spring, anchoring, accessibility `setOffset`) never
 * show it, because UIKit shows indicators only for user scrolling.
 *
 * The fade state lives in [TranscriptScrollIndicatorState] so tests can
 * read the alpha; [Modifier.transcriptScrollIndicator] takes it as an
 * optional parameter and creates its own when omitted.
 */
@Stable
internal class TranscriptScrollIndicatorState {

    /**
     * The thumb's alpha, 0 hidden … 1 fully visible. The test hook:
     * `TranscriptPhysicsGestureTest` reads this through the state it
     * injects into the modifier.
     */
    internal val alpha: Animatable<Float, AnimationVector1D> = Animatable(0f)
}

/**
 * The indicator's fade driver over [viewport]: `snapTo(1)` while the
 * user scrolls, and on quiet a hold then a linear fade to 0, cancelled
 * by the next scroll. `collectLatest` cancels an unfinished fade the
 * moment a new drag or fling starts, so the thumb reappears at once.
 */
@Composable
internal fun rememberTranscriptScrollIndicatorState(
    viewport: TranscriptViewport,
): TranscriptScrollIndicatorState {
    val state = remember { TranscriptScrollIndicatorState() }
    LaunchedEffect(viewport, state) {
        snapshotFlow { viewport.dragging || viewport.flinging }.collectLatest { scrolling ->
            if (scrolling) {
                state.alpha.snapTo(1f)
            } else {
                // Under a MotionDurationScale of 1, like the fling: with
                // "Remove animations" on, the thumb still fades instead of
                // blinking out (Reduce Motion does not change it on iOS).
                withContext(UnitMotionScale) {
                    delay(INDICATOR_HOLD_MS)
                    state.alpha.animateTo(0f, tween(INDICATOR_FADE_MS, easing = LinearEasing))
                }
            }
        }
    }
    return state
}

/**
 * Draws the vertical scroll indicator over the transcript box (the
 * ticket appends this at the end of 68's [rememberTranscriptScroll]
 * chain, so the host (65) and the lab (135) get it with no call-site
 * change). The colour — black at 35 % in light, white at 35 % in dark,
 * UIKit's `.default` style — is resolved in composition from ticket
 * 01's theme and captured before the draw lambda; the geometry and the
 * alpha are read inside it, from snapshot state, so scrolling redraws
 * without recomposing.
 */
@Composable
internal fun Modifier.transcriptScrollIndicator(
    viewport: TranscriptViewport,
    state: TranscriptScrollIndicatorState = rememberTranscriptScrollIndicatorState(viewport),
): Modifier {
    val thumbColor = (if (RobocoTheme.isDark) Color.White else Color.Black)
        .copy(alpha = INDICATOR_ALPHA)
    return drawWithContent {
        drawContent()
        val alpha = state.alpha.value
        if (alpha <= 0f) return@drawWithContent
        val thumb = scrollIndicatorThumb(
            offset = viewport.offset,
            minOffset = viewport.minOffset,
            maxOffset = viewport.maxOffset,
            contentHeight = viewport.contentHeight,
            viewportHeight = viewport.viewportHeight,
            topInset = viewport.topInset,
            bottomInset = viewport.bottomInset,
        ) ?: return@drawWithContent // the content fits: no thumb
        val widthPx = INDICATOR_WIDTH.dp.toPx()
        val padPx = INDICATOR_END_PAD.dp.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - padPx - widthPx else padPx
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, thumb.first.dp.toPx()),
            size = Size(widthPx, thumb.second.dp.toPx()),
            cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f), // capsule
            alpha = alpha,
        )
    }
}
