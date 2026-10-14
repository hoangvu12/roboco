package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Trace
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.TranscriptPalette
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

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
 * Scrolling is ticket 68's [rememberTranscriptScroll]: the viewport model
 * (follow the tail, insets, R14 clamp) fed by the same gesture pipeline.
 * The offset can be negative (`−topInset`); the realized band starts
 * below zero accordingly. Anchoring is 130, the model cache 117, the
 * veil 118, widgets 66+, and the row long-press menu (128) rides on every
 * row through the one [rememberRowMenuPerformer] created here. Ticket
 * 129 adds the host's TalkBack semantics (collection, scroll range and
 * the paging `scrollBy`) on the same node as the scrollable.
 *
 * Ticket 132 tweens the rows here: while a fold or reveal runs, the
 * measured height and the placed y read the row's **drawn** geometry
 * ([TranscriptState.rowMotion] — the placement plus the live segments'
 * remainder), one `withFrameNanos` loop ticks the clock, and the offset
 * and content height stay final. Only rows whose placement moved are
 * re-measured; the tick loop is suspended while no track is live.
 */
@Composable
fun TranscriptHost(state: TranscriptState, actions: TranscriptActions, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val d = density.density
    val palette = TranscriptPalette(LocalIsDark.current)
    val currentActions by rememberUpdatedState(actions)
    // 128: one row-menu performer for the whole host, never per row —
    // rows recompose per frame while streaming.
    val menuPerformer = rememberRowMenuPerformer { currentActions }

    // 117: give the model cache back under memory pressure — iOS's
    // `didReceiveMemoryWarning` clears it (`TranscriptListView.swift:192-198`);
    // Android's closest equivalents are `onTrimMemory(level)` at or above
    // `RUNNING_LOW` and `onLowMemory()`. Registered on the application
    // context (`ComponentCallbacks` are delivered on the main thread) and
    // unregistered when the host leaves composition.
    val app = LocalContext.current.applicationContext
    DisposableEffect(state, app) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = state.onTrimMemory(level)
            override fun onLowMemory() = state.onLowMemory()
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    BoxWithConstraints(
        modifier
            .testTag("transcript")
            // 129: the TalkBack tree root — `CollectionInfo` for the
            // applied frame, a px scroll range and the paging `scrollBy`
            // (see [Modifier.transcriptHostSemantics]). Reading
            // `state.frame` here recomposes the host per frame, which
            // keeps `rowCount` current. Outer to 68's scrollable, so this
            // `scrollBy` replaces the raw one on the same node.
            .transcriptHostSemantics(state, state.frame, d)
            .clipToBounds()
            .onPlaced { state.hostCoordinates = it }
            // 133: interactive IME dismiss — a transcript drag that
            // reaches into the IME tracks the finger; observes on the
            // Final pass and never consumes, so the scroll (68), taps
            // (81) and links (126) still get every event.
            .interactiveImeDismiss(state.viewport) { state.hostCoordinates }
            // 68: the scroll model — size reporting, the Initial-pass
            // pointer observer, the follow-aware fling and the scrollable
            // itself, in one chain. The box is NOT padded by the insets:
            // content draws under the header and under the bottom stack.
            .then(
                rememberTranscriptScroll(
                    viewport = state.viewport,
                    interactionSource = state.scrollInteractions,
                ),
            )
            .transcriptTaps(state) { currentActions },
    ) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        SideEffect {
            // This ticket owns the call site of 61's viewport sync; the
            // box height flows in through the scroll modifier's
            // `onSizeChanged` (68), never from here.
            state.setViewport(widthDp, TranscriptState.bodyTextScale(density))
        }

        // 132: the row-motion clock — one frame loop per host, running only
        // while a track is live (suspended, no frame requests, while idle).
        // `collectLatest` restarts the loop whenever `version` moves (a
        // track added, the last one gone, a row dropped): the fresh loop
        // re-enters `withFrameNanos`, and the next frame stamps the new
        // segments' starts and ticks the clock — the Compose `Animatable`
        // convention, without any per-row `Animatable` (#609's `snapTo`
        // restarts are exactly what this avoids).
        LaunchedEffect(state) {
            val m = state.rowMotion
            snapshotFlow { m.version }.collectLatest {
                while (m.hasTracks) withFrameNanos { m.tick(it) }
            }
        }

        val frame = state.frame ?: return@BoxWithConstraints
        val start by remember(state) { derivedStateOf { bandStart(state.offset) } }
        SideEffect { state.hostCompositions++ } // the no-recomposition test reads this

        val placements = remember(frame, start, heightDp) { frame.rowsIn(start, bandEnd(start, heightDp)) }

        Layout(
            content = {
                for (p in placements) key(p.key) { RowHost(state, frame, p, palette, menuPerformer) { currentActions } }
            },
        ) { measurables, constraints ->
            Trace.beginSection("layout-rows") // iOS signpost name
            val t0 = System.nanoTime()
            val w = constraints.maxWidth
            // 132: the drawn geometry. `version` is subscribed on every
            // pass and `clockNanos` only while a track is live, so an idle
            // transcript never re-measures (65's counters hold); while a
            // tween runs, each tick's clock write re-measures and
            // re-places once, and rows whose constraint did not change
            // skip re-measure (Compose's own rule). `from == to` rows keep
            // their constraints exactly.
            val motion = state.rowMotion
            motion.version
            if (motion.hasTracks) motion.clockNanos
            val placeables = measurables.mapIndexed { i, measurable ->
                val height = motion.visualHeight(placements[i].key, placements[i].height)
                measurable.measure(
                    Constraints.fixed(w, (height * d).roundToInt().coerceAtLeast(0)),
                )
            }
            layout(w, constraints.maxHeight) {
                // The only per-pixel offset read: a change re-places, never
                // recomposes (state reads in the place block are observed).
                // 132: the placed y is the drawn one — the motion track's
                // remainder rides on top of the final placement.
                val offPx = (state.offset * d).roundToInt()
                placeables.forEachIndexed { i, pl ->
                    pl.placeWithLayer(
                        0,
                        (motion.visualY(placements[i].key, placements[i].y) * d).roundToInt() - offPx,
                    )
                }
                // 117: directional prefetch beyond the realized band, in
                // the direction of travel first — deduped on (frame, band,
                // viewport, direction) so its early return precedes any FFI
                // call on the per-pixel placement passes.
                state.models.afterLayout(frame, start, bandEnd(start, heightDp), heightDp, state.offset)
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
 * hit-test (126's [linkAt], scroller offsets read at tap time) and then
 * fires the generic [TranscriptActions.onTap] hook — always, after the
 * link, in a `finally` (65's contract), so both fire on a link tap and
 * 81's Initial-pass dismiss observer still sees every tap. No
 * haptics here, and nothing is ever consumed.
 */
internal fun Modifier.transcriptTaps(
    state: TranscriptState,
    actions: () -> TranscriptActions,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val up = waitForUpOrCancellation(PointerEventPass.Final) ?: return@awaitEachGesture
        val d = density
        try {
            val row = state.rowAt(up.position.y.toInt())
            if (row != null) {
                // The row spans the full host width at x 0, so pos.x is
                // already row-local; y becomes row-local via topPx().
                val hit = linkAt(row, up.position.x / d, (up.position.y - row.topPx()) / d)
                if (hit != null) {
                    val rowBounds = row.boundsInWindow()
                    // The painted fragment rect, dp → window px via the
                    // row's window origin (the inverse of RowView.swift:78-90).
                    val linkBounds = Rect(
                        rowBounds.left + hit.x * d,
                        rowBounds.top + hit.y * d,
                        rowBounds.left + (hit.x + hit.w) * d,
                        rowBounds.top + (hit.y + hit.h) * d,
                    )
                    actions().openLink(LinkTap(hit.url, rowBounds, linkBounds))
                }
            }
        } finally {
            actions().onTap()
        }
    }
}
