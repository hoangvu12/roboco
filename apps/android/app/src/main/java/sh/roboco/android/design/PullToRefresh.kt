package sh.roboco.android.design

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.roboco.android.core.Face

/**
 * The pull-to-refresh control (android-ui-parity #48): the port of every
 * iOS `SessionListController`'s `UIRefreshControl`
 * (`ios:Threads/SessionListController.swift:103-112`) — pull the list
 * past the top, release, "redial and re-probe" (Roboco: `CoreClient.
 * refresh()`, android-app#06), the spinner holds ≥ 0.6 s and ≤ 3 s, then
 * the list settles back. The indicator is the app's own cell spinner
 * ([StatusGlyph] [StatusGlyphKind.Spinner] at ×1.75), **not** a system
 * activity indicator and never a Material one.
 *
 * The pieces:
 *  - [PullToRefreshDefaults] / [PullToRefreshMath] — the constants and
 *    the iOS rubber-band math (pure, pinned by `PullToRefreshMathTest`);
 *  - [holdRefresh] — the timing window, pinned by
 *    `PullToRefreshTimingTest`;
 *  - [PullToRefreshState] — the gesture (a [NestedScrollConnection] with
 *    rubber-band resistance), the phase machine and the refresh job;
 *  - [rememberPullToRefreshState] / [PullToRefreshIndicator] — the
 *    composition API; `sessions/Refresher.kt`'s
 *    `rememberSessionsPullToRefresh` is the sessions-side wrapper.
 *
 * No reduce-motion branch of its own (ticket 04's rule: only the 13 iOS
 * sites branch, and the refresh control is not one). The system "Remove
 * animations" snap comes free from `MotionDurationScale` and ticket 11
 * owns the spinner's static cells.
 */
// ---------------------------------------------------------------------------
// Constants and math
// ---------------------------------------------------------------------------

/**
 * The control's tunables (android-ui-parity #48). `Threshold` is
 * android-app#20's; `RubberBandC` is UIKit's `UIScrollView` coefficient;
 * the three `*` values are approximations of system internals (UIKit's
 * 60-pt control height, its internal bounce-back curve), frozen once
 * tuned side by side against an iOS 26 device — see the ticket's
 * Comments.
 */
object PullToRefreshDefaults {

    /** The revealed gap (dp) past which a release starts a refresh. android-app#20 §Pull to refresh. */
    val Threshold = 80.dp

    /** The gap kept open while the spinner runs. `*` UIKit's standard `UIRefreshControl` height (60 pt), not app source. */
    val HoldHeight = 60.dp

    /** UIKit `UIScrollView` rubber-band coefficient (the shared [UIScrollViewRubberBandC]). */
    const val RubberBandC = UIScrollViewRubberBandC

    /** The cell spinner's scale — the 21 × 21 dp box, the same loader as the session open (ticket 11). */
    const val SpinnerScale = 1.75f

    /**
     * The settle spring's stiffness (≈ 400), carried as
     * [Motion.RefreshSettle] — a 0.4617 s critically damped spring, so no
     * stiffness literal is ever pasted (FOUNDATION-D1). `*` UIKit's
     * bounce-back curve is internal.
     */
    val SettleStiffness: Float get() = Motion.RefreshSettle.stiffness

    /** The settle's visibility threshold: 0.5 dp (the spring is done within half a dp). */
    val SettleVisibility = 0.5.dp
}

/**
 * The control's pure math (all in px — the functions are unit-agnostic).
 * Pinned by `PullToRefreshMathTest` against UIKit's reference values.
 */
object PullToRefreshMath {

    /**
     * iOS rubber band: `offset = (1 − 1 / (drag·c/v + 1)) · v` with
     * `v = max(viewport, 1)` and `drag ≥ 0`, through the shared stable
     * [rubberBandOffset] (the literal form cancels in `Float` for small
     * drags). Never NaN: a zero/negative viewport clamps to 1, a saturating
     * drag converges on `v`.
     */
    fun rubberBand(drag: Float, viewport: Float, c: Float = PullToRefreshDefaults.RubberBandC): Float =
        rubberBandOffset(drag.coerceAtLeast(0f), viewport.coerceAtLeast(1f), c)

    /**
     * The inverse of [rubberBand], for grabbing a settling list:
     * `drag = (v/c) · offset / (v − offset)` with `offset` clamped to
     * `[0, v − 1]` (the band never fully opens). Each gesture derives the
     * finger travel from the current offset instead of storing a `drag`,
     * so a second gesture on a gap already held open continues from where
     * the content is.
     */
    fun dragFor(offset: Float, viewport: Float, c: Float = PullToRefreshDefaults.RubberBandC): Float {
        val v = viewport.coerceAtLeast(1f)
        val o = offset.coerceIn(0f, v - 1f)
        if (o <= 0f) return 0f
        return rubberBandTravel(o, v, c)
    }

    /**
     * The indicator's alpha: `[offset/threshold]` while pulling (Idle),
     * `[offset/hold]` while refreshing and while closing (fades as the
     * gap closes).
     */
    fun indicatorAlpha(phase: PullPhase, offset: Float, threshold: Float, hold: Float): Float = when (phase) {
        PullPhase.Idle -> (offset / threshold).coerceIn(0f, 1f)
        PullPhase.Refreshing -> (offset / hold).coerceIn(0f, 1f)
        PullPhase.Closing -> (offset / hold).coerceIn(0f, 1f)
    }

    /** The spinner centre's y: the middle of the revealed gap (`navBottom + offset / 2`). */
    fun indicatorCenterY(navBottom: Float, offset: Float): Float = navBottom + offset / 2f
}

/**
 * The warning sink for refresh failures: production logs
 * `Log.w("RobocoPull", …)`. Plain-JVM tests swap this — the stub
 * `android.util.Log` throws "not mocked" outside Robolectric (the same
 * seam as `FoldState.foldLogW`).
 */
internal var pullRefreshLogW: (String, Throwable?) -> Unit = { message, error ->
    if (error == null) {
        Log.w(TAG, message)
    } else {
        Log.w(TAG, message, error)
    }
}

private const val TAG = "RobocoPull"

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------

/**
 * Runs [refresh] and returns at `max(start + minMs, min(done, start +
 * maxMs))` — the port of `ios:App/AppModel.swift:292-318`'s
 * `refresh(minimum:maximum:completion:)` window. Never throws except
 * cancellation of its own scope: a `CancellationException` thrown by
 * [refresh] itself is swallowed (logged) so the spinner still ends; the
 * 3 s [maxMs] backstop means a misbehaving core can never pin it (the
 * iOS regression lesson, `iosui:SessionFlowTests.swift:669-682`).
 *
 * No wall clock: virtual-time tests and production share the code —
 * `coroutineScope` waits for the [minMs] floor child, and
 * `withTimeoutOrNull` cuts [refresh] at [maxMs].
 */
suspend fun holdRefresh(
    refresh: suspend () -> Unit,
    minMs: Long = Motion.RefreshMinMs,
    maxMs: Long = Motion.RefreshMaxMs,
) = coroutineScope {
    launch { delay(minMs) }
    try {
        withTimeoutOrNull(maxMs) { refresh() }
    } catch (e: CancellationException) {
        // Rethrow only if *our* scope was cancelled (the screen left); a
        // stray self-cancel from the core is swallowed like any failure.
        coroutineContext.ensureActive()
        pullRefreshLogW("refresh cancelled itself", e)
    } catch (e: Exception) {
        pullRefreshLogW("refresh failed", e)
    }
}

// ---------------------------------------------------------------------------
// The state
// ---------------------------------------------------------------------------

/** The phase machine: pulling, spinner running, spinner closing. */
enum class PullPhase { Idle, Refreshing, Closing }

/**
 * The gesture and phase state behind one session list's pull-to-refresh
 * (#48). The gesture is a [NestedScrollConnection] placed on the parent
 * of the `LazyColumn` (ticket 25's scaffold does that): a down drag at
 * the top grows the gap with iOS rubber-band resistance, an up drag
 * shrinks it first, and a release past [PullToRefreshDefaults.Threshold]
 * triggers [onRefresh]. Everything else — what `refresh()` does in the
 * core, the fresh-data rule, the 3 s core cap — is android-app#06's.
 *
 * There is **no stored `drag`**: every event derives the finger travel
 * from the current offset with [PullToRefreshMath.dragFor], so a second
 * gesture that grabs a gap held at [PullToRefreshDefaults.HoldHeight]
 * (or a settle in flight) continues from where the content is instead of
 * jumping back to 0.
 *
 * `offsetPx` is snapshot state read only in layout/draw lambdas and
 * derived booleans — a pull frame never recomposes the hosts.
 *
 * @param scope the composition's scope: the refresh runs there, so the
 *   screen leaving cancels it (iOS's "owner gone → end at once")
 */
@Stable
class PullToRefreshState internal constructor(
    private val scope: CoroutineScope,
    private val onRefresh: suspend () -> Unit,
    /** The release threshold in px. Read by [PullToRefreshIndicator]. */
    internal val thresholdPx: Float,
    /** The held gap in px. Read by [PullToRefreshIndicator]. */
    internal val holdPx: Float,
    /** The settle spring's visibility threshold in px (0.5 dp). */
    internal val settleThresholdPx: Float = 0.5f,
) {
    var phase: PullPhase by mutableStateOf(PullPhase.Idle)
        private set

    /** Content push-down in px. Read only in layout/draw lambdas. */
    var offsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** The list viewport's height in px, written by the scaffold's `onSizeChanged`. */
    internal var viewportPx = 1f

    /** The haptic engine, assigned by [rememberPullToRefreshState] in a `SideEffect`. */
    internal var feedback: Feedback = NoFeedback

    val isRefreshing: Boolean get() = phase == PullPhase.Refreshing

    /** Whether the indicator is composed (gap open, or the spinner running/closing). */
    internal val indicatorVisible: Boolean
        get() = offsetPx > 0f || phase != PullPhase.Idle

    private var armed = false
    private var settleJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * The gesture. Placed on the parent of the list (the scaffold's source
     * box), so the list's own scroll leftovers reach it — a pull at the
     * top must never fling or stretch the list. Fling
     * ([NestedScrollSource.SideEffect]) sources are ignored on purpose:
     * a fling into the top shows Android's stretch, not a pull.
     */
    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        /**
         * An up drag while the gap is open shrinks it first; whatever is
         * left after the drag reaches 0 scrolls the list. (Screen-space
         * signs: finger up = `available.y < 0`.)
         */
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y < 0f && offsetPx > 0f) {
                beginDrag()
                val drag = PullToRefreshMath.dragFor(offsetPx, viewportPx)
                val newDrag = (drag + available.y).coerceAtLeast(0f)
                val used = newDrag - drag // ≤ 0: the part of the drag that closes the gap
                offsetPx = PullToRefreshMath.rubberBand(newDrag, viewportPx)
                updateArmed()
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        /**
         * A down drag the list could not consume (it is at the top): the
         * gap opens with rubber-band resistance. Consuming all of it keeps
         * Android's stretch overscroll from showing on a pull.
         */
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            beginDrag()
            offsetPx = PullToRefreshMath.rubberBand(
                PullToRefreshMath.dragFor(offsetPx, viewportPx) + available.y,
                viewportPx,
            )
            updateArmed()
            return Offset(0f, available.y)
        }

        /**
         * The release. Consumes the fling whenever the gap is open (a
         * release while pulled never flings the list), and either triggers
         * the refresh (past the threshold, Idle only) or settles the gap —
         * to the hold while refreshing, to 0 otherwise. Does nothing at
         * `offset == 0`: the list flings normally.
         */
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offsetPx <= 0f) return Velocity.Zero
            armed = false
            if (phase == PullPhase.Idle && offsetPx >= thresholdPx) {
                trigger()
            } else {
                settleTo(if (phase == PullPhase.Refreshing) holdPx else 0f)
            }
            return available
        }
    }

    /** Test/gallery hook: behaves like a release past the threshold (offset snapped to the hold). */
    @VisibleForTesting
    internal fun startRefresh() {
        settleJob?.cancel()
        offsetPx = holdPx
        trigger()
    }

    /** Screenshot hook: cancels any settle and pins [px] (the phase stays as is). */
    @VisibleForTesting
    internal fun setOffsetForTest(px: Float) {
        settleJob?.cancel()
        offsetPx = px
    }

    // ------------------------------------------------------------------ internals

    /**
     * The start of any drag: a settle in flight is cancelled (the next
     * line's `dragFor(offsetPx)` grabs it where it is), and a closing
     * spinner returns to Idle so a new pull can re-trigger.
     */
    private fun beginDrag() {
        if (settleJob?.isActive == true) settleJob?.cancel()
        if (phase == PullPhase.Closing) phase = PullPhase.Idle
    }

    /**
     * The threshold haptic: fired on the way in only, once per crossing —
     * `armed` re-arms when the offset drops back below the threshold.
     * While refreshing there is no haptic and no re-trigger (iOS: the
     * control is already running).
     */
    private fun updateArmed() {
        if (phase != PullPhase.Idle) return
        val now = offsetPx >= thresholdPx
        if (now && !armed) feedback.haptic(Haptic.Threshold)
        armed = now
    }

    /**
     * The refresh itself: phase flips at once, the gap settles to the
     * hold, and the job runs [holdRefresh] — whose `finally` always ends
     * the spinner, whatever `refresh()` does (exception, self-cancel,
     * timeout). Re-entry guarded: a second trigger while the job is alive
     * does nothing.
     */
    private fun trigger() {
        if (refreshJob?.isActive == true) return
        armed = false
        phase = PullPhase.Refreshing
        settleTo(holdPx)
        refreshJob = scope.launch {
            try {
                holdRefresh(onRefresh)
            } finally {
                finishRefresh()
            }
        }
    }

    /**
     * The spinner's end. If the scope is gone (the screen left) it snaps
     * shut; otherwise the gap springs to 0 and the phase returns to Idle
     * when the settle ends. Every path out of the refresh job lands here.
     */
    private fun finishRefresh() {
        if (!scope.isActive) {
            phase = PullPhase.Idle
            offsetPx = 0f
            return
        }
        phase = PullPhase.Closing
        settleTo(0f) { phase = PullPhase.Idle }
    }

    /**
     * Springs [offsetPx] to [target] (critically damped, ticket 48's
     * [Motion.RefreshSettle]; 0.5 dp visibility threshold), then runs
     * [onEnd]. `animate` needs a `MonotonicFrameClock` — composition
     * scopes carry one; JVM tests add `TestMonotonicFrameClock`. Under
     * system "Remove animations" it finishes on the first frame by
     * itself. A new drag cancels the settle (and skips [onEnd]).
     */
    private fun settleTo(target: Float, onEnd: () -> Unit = {}) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = target,
                animationSpec = Motion.RefreshSettle.spec(visibilityThreshold = settleThresholdPx),
            ) { value, _ -> offsetPx = value }
            onEnd()
        }
    }
}

/**
 * Creates one [PullToRefreshState] for a screen. The scope is the
 * composition's, so leaving the screen cancels a running refresh (iOS's
 * "owner gone → end at once"); [onRefresh] is read through
 * [rememberUpdatedState], so a new lambda never re-creates the state.
 */
@Composable
fun rememberPullToRefreshState(onRefresh: suspend () -> Unit): PullToRefreshState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onRefresh)
    val thresholdPx = with(density) { PullToRefreshDefaults.Threshold.toPx() }
    val holdPx = with(density) { PullToRefreshDefaults.HoldHeight.toPx() }
    val settlePx = with(density) { PullToRefreshDefaults.SettleVisibility.toPx() }
    val state = remember(scope, density) {
        PullToRefreshState(
            scope = scope,
            onRefresh = { latest.value() },
            thresholdPx = thresholdPx,
            holdPx = holdPx,
            settleThresholdPx = settlePx,
        )
    }
    // Read the local in composition, assign in the effect (ticket 05's
    // pattern): NoFeedback outside a provider until then.
    val feedback = LocalFeedback.current
    SideEffect { state.feedback = feedback }
    return state
}

// ---------------------------------------------------------------------------
// The indicator
// ---------------------------------------------------------------------------

/**
 * The 21-dp cell spinner centred in the gap — the Android replacement for
 * UIKit's stock activity indicator (decided in android-app#20 and ticket
 * 11). Place it as a **full-size overlay sibling above the list** (ticket
 * 25's scaffold inserts it between the source box and the edge effect).
 *
 * - centred horizontally, centre y = `navBottom + offset / 2`;
 * - fades in with `offset / threshold` while pulling, holds 1 while the
 *   gap is at the hold, fades out with the closing gap;
 * - composed only while the gap is open or the spinner runs/closes —
 *   otherwise nothing subscribes to the glyph clock;
 * - decorative: cleared semantics carrying only the `pull-to-refresh`
 *   test tag (iOS's `accessibilityIdentifier`), no TalkBack focus. The
 *   overlay itself paints nothing — the gap stays transparent so the
 *   scaffold background / wallpaper shows through — and swallows no
 *   touches (a plain Box has no pointer input).
 *
 * `offsetPx` is read only inside the `offset {}` / `graphicsLayer {}`
 * lambdas (plus a derived boolean), so a pull frame never recomposes.
 */
@Composable
fun PullToRefreshIndicator(state: PullToRefreshState, navBottom: Dp, modifier: Modifier = Modifier) {
    val show by remember { derivedStateOf { state.indicatorVisible } }
    if (!show) return
    val density = LocalDensity.current
    val navBottomPx = with(density) { navBottom.toPx() }
    val halfBoxPx = with(density) { (6.dp * PullToRefreshDefaults.SpinnerScale).toPx() }
    Box(modifier.fillMaxSize()) {
        StatusGlyph(
            kind = StatusGlyphKind.Spinner,
            scale = PullToRefreshDefaults.SpinnerScale,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val top = PullToRefreshMath.indicatorCenterY(navBottomPx, state.offsetPx) - halfBoxPx
                    IntOffset(0, top.roundToInt())
                }
                .graphicsLayer {
                    alpha = PullToRefreshMath.indicatorAlpha(
                        state.phase,
                        state.offsetPx,
                        state.thresholdPx,
                        state.holdPx,
                    )
                }
                .clearAndSetSemantics { testTag = "pull-to-refresh" },
        )
    }
}

// ---------------------------------------------------------------------------
// Preview: the indicator in its refreshing state (light + dark)
// ---------------------------------------------------------------------------

/**
 * The preview fixture: a 393 × 300 slice of the Sessions page — the
 * scaffold background, the resting large title and the 21-dp spinner
 * centred in a 60-dp gap under a 24-dp status bar + 44-dp nav row
 * (`navBottom` = 68). The fake refresh never returns, so the spinner
 * holds; the glyph clock is frozen at 0 so the preview never starts a
 * frame loop.
 */
@Composable
private fun PullToRefreshIndicatorSample(appearance: Appearance) {
    RobocoTheme(appearance) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            val typeScale = LocalTypeScale.current
            val palette = RobocoTheme.palette
            val state = rememberPullToRefreshState(onRefresh = { delay(60_000) })
            LaunchedEffect(state) { state.startRefresh() }
            Box(Modifier.fillMaxSize().background(palette.background)) {
                BasicText(
                    "Sessions",
                    Modifier.padding(start = 16.dp, top = 128.dp),
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(30f), palette.text),
                )
                PullToRefreshIndicator(state, navBottom = 68.dp)
            }
        }
    }
}

@Preview(name = "Pull to refresh light", widthDp = 393, heightDp = 300)
@Composable
private fun PullToRefreshLightPreview() {
    PullToRefreshIndicatorSample(Appearance.Light)
}

@Preview(name = "Pull to refresh dark", widthDp = 393, heightDp = 300)
@Composable
private fun PullToRefreshDarkPreview() {
    PullToRefreshIndicatorSample(Appearance.Dark)
}
