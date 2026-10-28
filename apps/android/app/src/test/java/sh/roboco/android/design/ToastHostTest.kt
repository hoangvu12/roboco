package sh.roboco.android.design

import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.glass.GlassProgressKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The toast's behaviour (ticket 14) through a real [ToastHost] — Robolectric
 * + compose-ui-test, the clock frozen and advanced by frames, the frame
 * origin calibrated per animation (advance single frames until the value
 * first moves = 16 ms of playtime), exactly the `GlassVisibilityTest`
 * method.
 *
 * Robolectric reports no insets, so the safe bottom is the window height
 * (852 dp) and `LocalBottomChromeTop` is the only geometry input the tests
 * control. The device is the ticket's `w393dp-h852dp-xxhdpi`, so 1 dp = 3 px
 * and the ±1 px tolerances below are the ticket's.
 *
 * Geometry expectations (`chromeTop` = 734 dp unless a test says otherwise):
 * anchor 734, rest bottom 734 − 12 = 722 dp; height 12 + 19.5 + 12 =
 * 43.5 dp; "Archived · Undo" ≈ 153.2 dp wide (20 + 61.7 + 14 + 37.5 + 20);
 * "Couldn't open session. Try again." ≈ 268.8 dp. The ticket's 155.4 /
 * 271.7 are raw advance sums "before kerning"; the text renderer applies
 * Geist's GPOS `kern` pairs (Archived 63.55 → 61.71, Undo 37.86 → 37.52,
 * the error label 231.69 → 228.84 at 15 — computed from the font files),
 * and CI measured exactly those kerned widths (460 px, 807 px).
 *
 * The clock is frozen, so a `show` from the test thread only reaches the
 * Recomposer through `Snapshot.sendApplyNotifications()` and composes on
 * the next frame ([composeFrame]); `waitForIdle()` never pumps a frame here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ToastHostTest {

    @get:Rule
    val compose = createComposeRule()

    private var density: Density = Density(1f)
    private var chromeState: BottomChromeTopState? = null

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    /** The fake announcer: records every call. */
    private class RecordingAnnouncer : ToastAnnouncer {
        val calls = mutableListOf<String>()
        override fun announce(text: String) {
            calls.add(text)
        }
    }

    /** The host under test: a full-size clickable "underneath" plus a known chrome top. */
    @Composable
    private fun Host(
        state: ToasterState,
        announcer: ToastAnnouncer,
        chromeTop: Dp = Dp.Unspecified,
        onUnderClick: (() -> Unit)? = null,
    ) {
        density = LocalDensity.current
        RobocoTheme(Appearance.Light) {
            ToastHost(state, announcer) {
                val chrome = LocalBottomChromeTop.current
                chromeState = chrome
                LaunchedEffect(chrome, chromeTop) {
                    if (chromeTop.isSpecified) chrome.top = chromeTop
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("under")
                        .clickable(onClick = { onUnderClick?.invoke() }),
                )
            }
        }
    }

    private fun show(
        state: ToasterState,
        text: String,
        action: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        // The test thread is the main thread: synchronous.
        state.show(text, action, onAction)
        compose.waitForIdle()
    }

    private fun dpPx(dp: Float): Float = with(density) { dp.dp.toPx() }

    private fun hasKey(key: SemanticsPropertyKey<Float>): SemanticsMatcher =
        SemanticsMatcher("has ${key.name}") { it.config.contains(key) }

    private fun slide(): Float {
        val nodes = compose.onAllNodes(hasKey(ToastSlideKey)).fetchSemanticsNodes()
        assertTrue("no ToastSlideKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[ToastSlideKey]
    }

    private fun glassProgress(): Float {
        val nodes = compose.onAllNodes(hasKey(GlassProgressKey)).fetchSemanticsNodes()
        assertTrue("no GlassProgressKey node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[GlassProgressKey]
    }

    private fun toastBounds(): Rect {
        val node = compose.onNodeWithTag("toast").fetchSemanticsNode()
        val topLeft = node.positionInRoot
        return Rect(topLeft, topLeft + Offset(node.size.width.toFloat(), node.size.height.toFloat()))
    }

    private fun nodeCenter(tag: String): Offset {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.positionInRoot + Offset(node.size.width / 2f, node.size.height / 2f)
    }

    /** One whole 16 ms frame. */
    private fun frame() = compose.mainClock.advanceTimeByFrame()

    /**
     * Hands a test-thread state write (a `show`, a posted show) to the
     * Recomposer and runs the one frame that composes it — frame 0 of the
     * toast's animations (they start on the next frame).
     */
    private fun composeFrame() {
        Snapshot.sendApplyNotifications()
        frame()
    }

    /** Advances single frames until [still] stops holding — the animation's first tick. */
    private fun advanceToFirstTick(still: (Float) -> Boolean, read: () -> Float, maxFrames: Int = 12) {
        var frames = 0
        while (still(read()) && frames < maxFrames) {
            frame()
            frames++
        }
        assertTrue("the animation never ticked within $maxFrames frames", frames < maxFrames)
    }

    // ------------------------------------------------------- geometry

    @Test
    fun oneToastSitsCentredTwelveDpAboveTheChrome() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer(), chromeTop = 734.dp) }
        compose.waitForIdle()
        show(state, "Archived", "Undo")
        compose.mainClock.advanceTimeBy(600) // the rise settles
        compose.waitForIdle()

        assertEquals("exactly one toast node", 1, compose.onAllNodesWithTag("toast").fetchSemanticsNodes().size)
        compose.onNodeWithText("Archived").assertExists()
        compose.onNodeWithText("Undo").assertExists()

        val bounds = toastBounds()
        val windowWidth = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        assertEquals("centred on the window (left gap == right gap)", bounds.left, windowWidth - bounds.right, 1f)
        assertEquals("bottom edge 12 dp above the chrome top", dpPx(722f), bounds.bottom, 1f)
        // Wave-10 42 (N7): the action is a filled capsule (insets 6/12, SemiBold 14),
        // taller than the 19.5 dp label line, so the row takes its height.
        val action = compose.onNodeWithTag("toast-action").fetchSemanticsNode()
        assertTrue("the action capsule outgrows the label line", action.size.height > dpPx(19.5f))
        assertEquals("capsule height 12 + action + 12", dpPx(24f) + action.size.height, bounds.height, 1f)
        assertTrue("the action's insets widen 'Archived · Undo'", bounds.width > dpPx(153.2f))
    }

    @Test
    fun withoutChromeTheToastSitsTwelveDpAboveTheSafeAreaBottom() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Couldn't open session. Try again.")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        val bounds = toastBounds()
        assertEquals("bottom edge 12 dp above the 852 dp safe-area bottom", dpPx(840f), bounds.bottom, 1f)
        assertEquals("the error toast's width (kerned)", dpPx(268.8f), bounds.width, dpPx(2f))
    }

    @Test
    fun theSafeAreaBottomWinsWhenTheChromeIsLowerThanIt() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer(), chromeTop = 900.dp) }
        compose.waitForIdle()
        show(state, "Archived")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("min(safe bottom, chrome top)", dpPx(840f), toastBounds().bottom, 1f)
    }

    // ------------------------------------------- present: rise + materialize

    @Test
    fun frame0IsSixteenDpLowAndFullyDematerialized() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived", "Undo")
        composeFrame()
        // The composing frame only: the animations have not ticked yet.
        assertEquals("frame 0 translationY is 16 dp", 16f, slide(), 1e-4f)
        assertEquals("frame 0 glass progress is 0", 0f, glassProgress(), 1e-4f)
    }

    @Test
    fun theRiseSpringsToRestWhileTheGlassMaterializes() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived")
        composeFrame()

        // Calibrate the materialize's first tick (16 ms of playtime).
        advanceToFirstTick({ it <= 0.001f }, ::glassProgress)
        // 10 more frames = 176 ms of playtime ≈ half of the 350 ms materialize.
        repeat(10) { frame() }
        assertEquals("glass progress at half of a 350 ms materialize", 0.5f, glassProgress(), 0.03f)

        compose.mainClock.advanceTimeBy(1000) // past 350 ms of materialize and 400 ms of spring
        compose.waitForIdle()
        assertEquals(1f, glassProgress(), 1e-4f)
        assertTrue("the rise settled back to rest", slide() < 0.05f)
        assertEquals("settled bottom edge 12 dp above the safe bottom", dpPx(840f), toastBounds().bottom, 1f)
    }

    @Test
    fun theAnnouncerReceivesTheLabelTextOncePerToast() {
        val announcer = RecordingAnnouncer()
        val state = ToasterState()
        compose.setContent { Host(state, announcer) }
        compose.waitForIdle()
        show(state, "Archived", "Undo")
        composeFrame() // the capsule's LaunchedEffect announces
        assertEquals("exactly the label text, once", listOf("Archived"), announcer.calls)
        compose.mainClock.advanceTimeBy(5000) // present, hold, dismiss
        compose.waitForIdle()
        assertEquals("nothing is announced on dismiss", listOf("Archived"), announcer.calls)
    }

    // ---------------------------------------------- hold and dismiss

    @Test
    fun theToastHoldsForFourSecondsThenDismissesAnimated() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer(), chromeTop = 734.dp) }
        compose.waitForIdle()
        show(state, "Archived", "Undo")

        compose.mainClock.advanceTimeBy(3999)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertExists()

        compose.mainClock.advanceTimeBy(1) // t = 4000: the hold fires
        frame() // one frame past the deadline, clear of any boundary quirk
        compose.waitForIdle()
        // The dismiss has started: the action left the semantics tree
        // (07's model-value rule) while the capsule is still fading.
        compose.onNodeWithTag("toast").assertExists()
        compose.onNodeWithTag("toast-action").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(250) // the 250 ms fall finishes
        frame() // "plus one frame"
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
    }

    @Test
    fun theFallSlidesToTwelveDpWhileTheGlassFadesOut() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived")
        compose.mainClock.advanceTimeBy(1000) // fully settled
        compose.waitForIdle()
        assertTrue(slide() < 0.05f)

        compose.mainClock.advanceTimeBy(3000) // t = 4000: dismiss starts
        compose.waitForIdle()
        // Each animation gets its own frame origin (this file's method): the
        // fall launches straight from dismiss(), the dematerialize only once
        // the recomposition flips `visible` — a frame later. Sample both per
        // frame; read each 7 frames past its own first tick = 128 ms of
        // playtime ≈ half of the 250 ms easeInOut.
        var frames = 0
        var slideTick = -1
        var glassTick = -1
        var slideAtHalf = Float.NaN
        var glassAtHalf = Float.NaN
        while ((slideAtHalf.isNaN() || glassAtHalf.isNaN()) && frames < 24) {
            val s = slide()
            val g = glassProgress()
            if (slideTick < 0 && s > 0.01f) slideTick = frames
            if (glassTick < 0 && g < 0.999f) glassTick = frames
            if (slideTick >= 0 && frames == slideTick + 7) slideAtHalf = s
            if (glassTick >= 0 && frames == glassTick + 7) glassAtHalf = g
            frame()
            frames++
        }
        assertTrue("both animations ticked within 24 frames", !slideAtHalf.isNaN() && !glassAtHalf.isNaN())
        assertEquals("slide at half of a 250 ms fall (dp)", 6f, slideAtHalf, 1.5f)
        assertEquals("glass at half of a 250 ms dematerialize", 0.5f, glassAtHalf, 0.06f)

        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
    }

    @Test
    fun aHoldTimerDismissCompletesAndRemovesTheToast() {
        // Regression guard: the hold job's cancellation must not take the
        // removal down with it (the fall runs in its own coroutine).
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived")
        compose.mainClock.advanceTimeBy(4300)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
    }

    // ------------------------------------------------- the action

    @Test
    fun tappingTheActionRunsTheHandlerThenDismissesAnimated() {
        var fired = 0
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived", "Undo") { fired++ }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithTag("toast-action").performClick()
        compose.waitForIdle()
        frame() // the recomposition that flips `visible` and drops the action
        assertEquals("the handler ran exactly once", 1, fired)
        // The dismiss has started (250 ms fade still running) — a second tap
        // during it does nothing: the action node is gone from the tree.
        compose.onNodeWithTag("toast-action").assertDoesNotExist()
        compose.onNodeWithTag("toast").assertExists()

        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
    }

    @Test
    fun twoTapsBeforeTheRecompositionFireTheHandlerOnce() {
        // The Roboco adaptation: two taps can be delivered before the
        // recomposition that flips `visible`; only the first may run the
        // handler (unarchive twice would be wrong).
        var fired = 0
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived", "Undo") { fired++ }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        val center = nodeCenter("toast-action")
        compose.onRoot().performTouchInput { click(center) }
        compose.onRoot().performTouchInput { click(center) } // same frame, no idle between
        compose.waitForIdle()
        assertEquals(1, fired)
    }

    @Test
    fun theActionIsTappableWhileTheToastIsStillMaterializing() {
        // iOS `.allowUserInteraction`: the model alpha is already 1 while
        // the fade runs; the graphicsLayer slide moves the hit area too.
        var fired = 0
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived", "Undo") { fired++ }
        compose.mainClock.advanceTimeBy(128) // mid-rise, mid-materialize
        compose.waitForIdle()
        assertTrue("glass is mid-materialize", glassProgress() in 0.15f..0.85f)

        val center = nodeCenter("toast-action")
        val slidePx = dpPx(slide())
        compose.onRoot().performTouchInput { click(Offset(center.x, center.y + slidePx)) }
        compose.waitForIdle()
        assertEquals("the tap reached the action mid-materialize", 1, fired)
    }

    // ------------------------------------------------- touch rules

    @Test
    fun theCapsuleSwallowsTapsWhileItsSurroundingsPassThrough() {
        var underClicks = 0
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer(), onUnderClick = { underClicks++ }) }
        compose.waitForIdle()
        show(state, "Archived")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        val bounds = toastBounds()
        // On the capsule body (the label area): swallowed, the row
        // underneath does not get the touch.
        compose.onRoot().performTouchInput { click(Offset(bounds.left + dpPx(10f), bounds.center.y)) }
        compose.waitForIdle()
        assertEquals(0, underClicks)

        // Outside the capsule: passes through to the app.
        compose.onRoot().performTouchInput { click(Offset(dpPx(10f), dpPx(10f))) }
        compose.waitForIdle()
        assertEquals(1, underClicks)
    }

    // ------------------------------------------------- replacement

    @Test
    fun callingShowTwiceKeepsOnlyTheSecondToastAndItsOwnTimer() {
        var firstFired = 0
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "First", "Undo") { firstFired++ }
        compose.mainClock.advanceTimeBy(1000)
        show(state, "Second")
        composeFrame()

        // The very next frame: exactly one toast, showing the second text.
        assertEquals(1, compose.onAllNodesWithTag("toast").fetchSemanticsNodes().size)
        compose.onNodeWithText("Second").assertExists()
        compose.onNodeWithText("First").assertDoesNotExist()

        // The first toast's 4 s timer (armed at t = 0) cannot dismiss the
        // second: the second's own hold runs to t = 5000.
        compose.mainClock.advanceTimeBy(2999) // t = 3999
        compose.onNodeWithTag("toast").assertExists()
        compose.mainClock.advanceTimeBy(1000) // t = 4999: past the first timer
        compose.onNodeWithTag("toast").assertExists()
        compose.mainClock.advanceTimeBy(1) // t = 5000: the second's own hold fires
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
        assertEquals("the first toast's action can no longer fire", 0, firstFired)
    }

    @Test
    fun showFromABackgroundThreadDisplaysTheToastWithoutSnapshotErrors() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()

        val done = CountDownLatch(1)
        Thread {
            try {
                state.show("Background toast")
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // The posted runnable runs on the main looper.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        composeFrame()
        compose.onNodeWithText("Background toast").assertExists()
    }

    // ------------------------------------------------- reduce motion

    @Test
    fun reduceMotionSnapsTheGlassButStillSlides() {
        ReduceMotion.debugOverride = true
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, "Archived")
        composeFrame()
        // One more frame: the snap lands in the LaunchedEffect after the
        // composing frame, the progress key recomposes on the next one.
        frame()

        // The glass + content snap in (no fade)…
        assertEquals(1f, glassProgress(), 1e-4f)
        // …but the rise still animates: it has not moved (frozen clock).
        assertEquals(16f, slide(), 1e-4f)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("the rise still ran under reduce motion", slide() < 8f)

        compose.mainClock.advanceTimeBy(3400) // t = 4000: the hold fires
        frame()
        compose.waitForIdle()
        assertEquals("the glass snapped out", 0f, glassProgress(), 1e-4f)
        frame()
        compose.waitForIdle()
        assertTrue("the fall still animates under reduce motion", slide() > 0f)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("toast").assertDoesNotExist()
    }

    // ------------------------------------------------- the live anchor

    @Test
    fun aMovingChromeTopCarriesTheToastWithTheRiseSpring() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer(), chromeTop = 734.dp) }
        compose.waitForIdle()
        show(state, "Archived")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val bottomAt734 = toastBounds().bottom
        assertEquals(dpPx(722f), bottomAt734, 1f)

        // The accessory reappears under the toast: the chrome top moves.
        chromeState!!.top = 800.dp
        compose.waitForIdle()
        // Calibrate the follow spring's first tick (the recomposition frame
        // and the animation's start frame move nothing), then one more frame
        // (32 ms of playtime) so the move clears the 2 dp floor.
        advanceToFirstTick({ it <= bottomAt734 + 0.5f }, { toastBounds().bottom })
        frame()
        compose.waitForIdle()
        val inTransit = toastBounds().bottom
        // No jump: strictly between the old and the new rest positions.
        assertTrue("moving toward the new rest", inTransit > bottomAt734 + dpPx(2f))
        assertTrue("no jump to the new rest", inTransit < dpPx(788f) - dpPx(2f))

        compose.mainClock.advanceTimeBy(600) // the same rise spring settles
        compose.waitForIdle()
        assertEquals("12 dp above the new chrome top", dpPx(788f), toastBounds().bottom, 1f)
    }

    // ------------------------------------------------- long text

    @Test
    fun aLongLabelWrapsToTwoLinesInsideMaxWidthAndKeepsTheActionVisible() {
        val state = ToasterState()
        compose.setContent { Host(state, RecordingAnnouncer()) }
        compose.waitForIdle()
        show(state, ToastLongText, "Undo")
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        val bounds = toastBounds()
        val windowWidth = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        // Wave-10 42 (N7): the label wraps to two lines (12 + 2 × 19.5 + 12).
        assertEquals("two lines: the 63 dp capsule height", dpPx(63f), bounds.height, dpPx(1.5f))
        assertEquals("never wider than window − 48 dp", dpPx(345f), bounds.width, 1f)
        assertTrue(bounds.width < windowWidth - 4f)
        // The action is never truncated: fully visible inside its 20 dp end padding.
        val action = compose.onNodeWithTag("toast-action").fetchSemanticsNode()
        val actionRight = action.positionInRoot.x + action.size.width
        assertTrue("the action stays fully visible", actionRight <= bounds.right - dpPx(19.5f))
    }
}
