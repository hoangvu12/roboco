package sh.roboco.android.transcript

import android.os.Trace
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.TranscriptPalette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The virtualized Compose transcript host (ticket 65): Rust owns every
 * row's y and height, so this composable only (1) asks which rows
 * intersect a band around the viewport, (2) puts a keyed row host there,
 * and (3) keeps the scroll offset. The realized band is quantized to
 * [BandStepDp] steps: `offset` is read per pixel only in the placement
 * block (re-placement, never recomposition), and in composition only
 * through `derivedStateOf` — scrolling within a step recomposes nothing.
 *
 * The host paints **no background** (iOS `backgroundColor = .clear`,
 * `TranscriptListView.swift:185`) and clips at its own bounds, as a
 * `UIScrollView` does. Rows are never clipped (glyph overhangs must stay
 * visible). The host itself does not consume taps — [Modifier.transcriptTaps]
 * observes them on the Final pass.
 *
 * Scrolling here is the raw offset model only (clamped to
 * `[0, maxOffset]`); follow-the-tail, insets, anchoring and fling physics
 * are 68/130/134, the model cache is 117, the veil 118, widgets 66+.
 */
@Composable
fun TranscriptHost(state: TranscriptState, actions: TranscriptActions, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val d = density.density
    val palette = TranscriptPalette(LocalIsDark.current)
    val currentActions by rememberUpdatedState(actions)

    // Compose's scrollable hands the raw drag delta (px, positive = finger
    // down); offset grows toward the tail, so the double negation maps a
    // swipe-up onto a forward scroll and returns what it consumed.
    val scrollable = rememberScrollableState { px -> -state.scrollByDp(-px / d) * d }

    BoxWithConstraints(
        modifier
            .testTag("transcript")
            .clipToBounds()
            .onPlaced { state.hostCoordinates = it }
            .scrollable(
                scrollable,
                Orientation.Vertical,
                overscrollEffect = null,
                interactionSource = state.scrollInteractions,
            )
            .transcriptTaps(state) { currentActions },
    ) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        SideEffect {
            state.viewportHeight = heightDp
            // This ticket owns the call site of 61's viewport sync.
            state.setViewport(widthDp, TranscriptState.bodyTextScale(density))
        }

        val frame = state.frame ?: return@BoxWithConstraints
        val start by remember(state) { derivedStateOf { bandStart(state.offset) } }
        SideEffect { state.hostCompositions++ } // the no-recomposition test reads this

        val placements = remember(frame, start, heightDp) { frame.rowsIn(start, bandEnd(start, heightDp)) }

        Layout(
            content = {
                for (p in placements) key(p.key) { RowHost(state, frame, p, palette) { currentActions } }
            },
        ) { measurables, constraints ->
            Trace.beginSection("layout-rows") // iOS signpost name
            val t0 = System.nanoTime()
            val w = constraints.maxWidth
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(
                    Constraints.fixed(w, (placements[i].height * d).roundToInt().coerceAtLeast(0)),
                )
            }
            layout(w, constraints.maxHeight) {
                // The only per-pixel offset read: a change re-places, never
                // recomposes (state reads in the place block are observed).
                val offPx = (state.offset * d).roundToInt()
                placeables.forEachIndexed { i, pl ->
                    pl.placeWithLayer(0, (placements[i].y * d).roundToInt() - offPx)
                }
                TranscriptPerf.maxLayoutMs = max(TranscriptPerf.maxLayoutMs, (System.nanoTime() - t0) / 1e6)
                Trace.endSection()
            }
        }
    }
}

/**
 * Observes taps without consuming them (ticket 65): the down is awaited
 * on the Final pass with `requireUnconsumed = false`, the up through
 * [waitForUpOrCancellation] on the same pass — a drag the scrollable
 * consumed, or an up a widget's `clickable` consumed, yields `null` and no
 * tap (iOS controls keep their taps). A tap first tries the link
 * hit-test (the [linkAt] stub until 126) and then fires the generic
 * [TranscriptActions.onTap] hook. No haptics here.
 */
internal fun Modifier.transcriptTaps(
    state: TranscriptState,
    actions: () -> TranscriptActions,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val up = waitForUpOrCancellation(PointerEventPass.Final) ?: return@awaitEachGesture
        val row = state.rowAt(up.position.y.toInt())
        if (row != null) {
            val url = linkAt(row, up.position.x / density, (up.position.y - row.topPx()) / density)
            if (url != null) actions().openLink(url, row.boundsInWindow())
        }
        actions().onTap()
    }
}
