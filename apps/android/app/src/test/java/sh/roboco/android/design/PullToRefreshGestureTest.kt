package sh.roboco.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.shell.LargeTitleScaffold

/**
 * The pull-to-refresh gesture (android-ui-parity #48) through a real
 * [LargeTitleScaffold] — the Android form of iOS
 * `testPullToRefreshEnds` (`iosui:SessionFlowTests.swift:669-682`): pull,
 * spinner, settle, gone.
 *
 * Environment: the ticket's `w412dp-h700dp-xxhdpi` (1 dp = 3 px),
 * `topInset = 24` so `navBottom = 68`, 30 rows of 64 dp after the 52-dp
 * large-title item (row 0 rests at y 120, row 8 at y 632 — both stay
 * composed through every state below), the clock **pinned**
 * (`autoAdvance = false`) so the refresh window (fake `delay(1500)`: the
 * spinner ends at 600 + 1500 = 2100 virtual ms after the trigger) is
 * driven by `mainClock.advanceTimeBy` alone, and a recording [Feedback]
 * so the threshold haptic is observable.
 *
 * The 400-dp release swipe drags ≈ 165 dp of gap (rubber band, D = 700):
 * past the 80-dp threshold, one `Threshold` haptic, the settle to the
 * 60-dp hold, the 21-dp spinner box centred at (206, 98), the row 60 dp
 * down, and everything back within 3 s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class PullToRefreshGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val feedback = RecordingFeedback()
    private var refreshCalls = 0
    private val listState = LazyListState()

    @Before
    fun setUp() {
        refreshCalls = 0
        feedback.haptics.clear()
        compose.mainClock.autoAdvance = false
        // Each test mounts once (one setContent per test): host(rows = 30) or its own host(...).
    }

    /** Composes the host and waits for the first frame. */
    private fun host(
        rows: Int,
        withBackdrop: Boolean = false,
        reduceMotion: Boolean = false,
        refreshMs: Long = 1500,
    ) {
        compose.setContent {
            PullHost(rows, withBackdrop, reduceMotion, refreshMs)
        }
        compose.waitForIdle()
    }

    /**
     * The test host: the scaffold with [rows] plain 64-dp rows, optionally
     * a tagged backdrop (the wallpaper-hero stand-in) and Reduce Motion.
     * The fake refresher counts calls and delays [refreshMs] virtual ms
     * (1500 by default).
     */
    @Composable
    private fun PullHost(rows: Int, withBackdrop: Boolean, reduceMotion: Boolean, refreshMs: Long) {
        RobocoTheme(Appearance.Light) {
            CompositionLocalProvider(
                LocalFeedback provides feedback,
                LocalReduceMotion provides reduceMotion,
            ) {
                LargeTitleScaffold(
                    title = "Sessions",
                    listState = listState,
                    topInset = 24.dp,
                    showBack = false,
                    onBack = {},
                    pullToRefresh = rememberPullToRefreshState(onRefresh = {
                        refreshCalls++
                        delay(refreshMs)
                    }),
                    // A tagged stand-in for the nav row's ⋯ slot: the test
                    // asserts the bar (this and the inline title) never moves
                    // during a pull.
                    trailing = {
                        Box(Modifier.size(44.dp).testTag("nav-trailing"))
                    },
                    backdrop = if (withBackdrop) {
                        {
                            Box(Modifier.fillMaxSize().testTag("gallery-backdrop"))
                        }
                    } else {
                        {}
                    },
                ) {
                    items(rows) { index -> PlainRow(index) }
                }
            }
        }
    }

    /** One plain 64-dp row — the ticket's list fixture. */
    @Composable
    private fun PlainRow(index: Int) {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        Box(Modifier.fillMaxWidth().height(64.dp).testTag("row-$index")) {
            BasicText(
                "Row $index",
                style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
            )
        }
    }

    // ---------------------------------------------------------------- the pull

    @Test
    fun releasePastThresholdRefreshesOnceWithOneHaptic() {
        host(rows = 30)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        settleFrame()
        assertEquals(1, refreshCalls)
        assertEquals(listOf(Haptic.Threshold), feedback.haptics)
    }

    @Test
    fun spinnerHoldsInTheGapThenEnds() {
        host(rows = 30)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()

        // The spinner: a 21-dp box centred at (206, 98) — the middle of the
        // 68…128 gap held open at 60.
        val box = compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(21f, box.width.value, 0.5f)
        assertEquals(21f, box.height.value, 0.5f)
        assertEquals(206f, (box.left.value + box.right.value) / 2f, 1f)
        assertEquals(98f, (box.top.value + box.bottom.value) / 2f, 1f)

        // The list rides the gap: row 0 is 60 dp below its resting top.
        assertEquals(180f, row0Top(), 1f)
        assertEquals(1, refreshCalls)

        // The refresh (1500 ms) ends within the 3 s window; the gap springs
        // shut and the spinner leaves.
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(120f, row0Top(), 1f)
    }

    @Test
    fun smallSwipeOpensNoRefresh() {
        host(rows = 30)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 300.dp.toPx(), endY = 400.dp.toPx(), durationMillis = 300)
        }
        settleFrame()
        // Robolectric's touch slop is 16 dp (ShadowViewConfiguration), so 84 of
        // the 100 dp reach the drag: rubberBand(84, 700) ~ 43.3 dp of gap, below
        // the 80-dp threshold (a device's 8 dp slop would give ~47-51).
        val gap = row0Top() - 120f
        assertTrue("gap $gap", gap in 40f..48f)

        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(0, refreshCalls)
        assertTrue(feedback.haptics.isEmpty())
        assertEquals(120f, row0Top(), 1f)
        compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun swipeOnScrolledListScrollsBackBeforeOpeningTheGap() {
        host(rows = 30)
        // 300 dp down the content (row 8, resting at 632, moves to 332; it
        // stays composed, unlike row 0 which leaves the viewport).
        with(compose.density) { listState.dispatchRawDelta(300.dp.toPx()) }
        settleFrame()
        assertEquals(332f, rowTop(8), 1f)

        // A 400-dp downward drag: the list scrolls back to the top first
        // (~300 dp), only then does the leftover (~92 dp) open a small gap —
        // below the threshold, so nothing refreshes and nothing vibrates.
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        settleFrame()
        val top = rowTop(8)
        assertTrue("row 8 top $top", top in 670f..690f) // 632 + gap ≈ 47, not 632 + 164
        assertEquals(0, refreshCalls)
        assertTrue(feedback.haptics.isEmpty())

        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(632f, rowTop(8), 1f)
    }

    @Test
    fun secondSwipeGrowsTheHeldGapWithoutRetriggering() {
        // A 2500-ms fake refresh (still under the 3 s cap): the swipes advance
        // the pinned clock by their durations, so the final hold check lands
        // ~1716 ms after the trigger (800 + 300 + one frame + 600). With the
        // default 1500 ms the spinner had already ended and the gap was
        // springing shut (row 0 at ~124.7), which is not what this test is
        // about — the hold surviving a second swipe.
        host(rows = 30, refreshMs = 2500)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(180f, row0Top(), 1f) // the gap is held at 60

        // A further 50-dp downward swipe: the gap grows from 60 (it never
        // drops toward 0 at the start of the gesture) and the release
        // settles back to the hold without a second refresh.
        compose.onRoot().performTouchInput {
            swipeDown(startY = 200.dp.toPx(), endY = 250.dp.toPx(), durationMillis = 300)
        }
        settleFrame()
        val grown = row0Top()
        assertTrue("grown gap $grown", grown > 182f)
        assertEquals(1, refreshCalls)

        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(180f, row0Top(), 1f)
        assertEquals(1, refreshCalls)
    }

    // ------------------------------------------------------- what does not move

    @Test
    fun pullMovesTheListButNotTheBackdropOrTheBar() {
        host(rows = 30, withBackdrop = true)
        val backdropBefore = compose.onNodeWithTag("gallery-backdrop").getBoundsInRoot()
        val inlineTitleBefore = compose.onNodeWithTag("nav-inline-title").getBoundsInRoot()
        val trailingBefore = compose.onNodeWithTag("nav-trailing").getBoundsInRoot()

        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 300.dp.toPx(), durationMillis = 400)
        }
        settleFrame()
        assertTrue("row moved: ${row0Top()}", row0Top() > 125f)
        // iOS backgroundView stays fixed; the nav row's inline title and ⋯
        // button do not move either.
        assertEquals(backdropBefore, compose.onNodeWithTag("gallery-backdrop").getBoundsInRoot())
        assertEquals(inlineTitleBefore, compose.onNodeWithTag("nav-inline-title").getBoundsInRoot())
        assertEquals(trailingBefore, compose.onNodeWithTag("nav-trailing").getBoundsInRoot())
    }

    // ---------------------------------------------------------------- coverage

    @Test
    fun pullWorksOnAnEmptyList() {
        // Ticket 41's empty state: only the __large_title item — drags still
        // reach the full-screen LazyColumn and its scroll leftovers reach
        // the connection.
        host(rows = 0)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        settleFrame()
        assertEquals(1, refreshCalls)
        assertEquals(listOf(Haptic.Threshold), feedback.haptics)
    }

    @Test
    fun numbersUnchangedUnderReduceMotion() {
        // No reduce-motion branch of the control's own (ticket 04's rule):
        // with LocalReduceMotion true the pull, the hold and the close are
        // identical — only ticket 11's spinner cells stop chasing.
        host(rows = 30, reduceMotion = true)
        compose.onRoot().performTouchInput {
            swipeDown(startY = 150.dp.toPx(), endY = 550.dp.toPx(), durationMillis = 600)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val box = compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(21f, box.width.value, 0.5f)
        assertEquals(98f, (box.top.value + box.bottom.value) / 2f, 1f)
        assertEquals(180f, row0Top(), 1f)
        assertEquals(1, refreshCalls)

        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        compose.onNodeWithTag("pull-to-refresh", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(120f, row0Top(), 1f)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Lets a gesture's snapshot writes land in a frame: exactly one 16-ms
     * frame (no pending delay — the 1500-ms fake, the 600-ms floor —
     * completes), then idle.
     */
    private fun settleFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Row [index]'s top in dp (row 0 rests at 120: 24 + 44 + 52). */
    private fun rowTop(index: Int): Float =
        compose.onNodeWithTag("row-$index").getBoundsInRoot().top.value

    private fun row0Top(): Float = rowTop(0)

    /** Records every haptic fired through [LocalFeedback]. */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) {}
    }
}
