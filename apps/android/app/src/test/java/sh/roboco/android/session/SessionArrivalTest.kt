package sh.roboco.android.session

import android.view.Choreographer
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.TranscriptState
import sh.roboco.android.transcript.firstUserBubbleInRoot
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The handoff arrival hooks (android-ui-parity #77): Robolectric +
 * `createComposeRule` at `w412dp-h900dp-xxhdpi` (1 dp = 3 px), the
 * frozen-clock discipline (`mainClock.autoAdvance = false`, state writes
 * through `runOnUiThread` + `Snapshot.sendApplyNotifications()`, then
 * frames).
 *
 * - the state machine (prepare/bind/finish, the own-turn deferral) runs
 *   against plain fakes;
 * - the reveals run from composition (`LaunchedEffect`), where the
 *   `MonotonicFrameClock` the hooks require lives, on the paused test
 *   clock — the caller's spec is honoured exactly;
 * - the bounds hooks run against a laid-out anchor node (unclipped root
 *   px) and a fake bubble lambda;
 * - the wiring runs through the real 74 stack (the stack channel's
 *   hit-test/semantics rule) and 75's fixture pieces (the goldens);
 * - [SessionScreenRoute]'s forwarders share the state's instance.
 *
 * The goldens are `session/arrival_prepared_{light,dark}.png` — the
 * arrival-prepared screen: header, background and bottom edge fade, no
 * transcript, no composer, no pill, no loader.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionArrivalTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ helpers

    /** A state write: inline on main, then applied to the Recomposer. */
    private fun writeState(change: () -> Unit) {
        compose.runOnUiThread(change)
        Snapshot.sendApplyNotifications()
    }

    /**
     * Drives the reveals from composition: a `LaunchedEffect` (the
     * coroutine context with the frame clock the hooks require). `go`
     * restarts the effect — a new value launches a reveal (cancelling
     * the running one), 0 cancels without launching.
     */
    private class RevealDriver {
        var go by mutableStateOf(0)
        var stackChannel by mutableStateOf(false)
        var spec by mutableStateOf<AnimationSpec<Float>>(snap())
    }

    /** Composes the reveal driver over an empty body and settles one frame. */
    private fun hostReveal(arrival: SessionArrival, driver: RevealDriver) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LaunchedEffect(driver.go) {
                    if (driver.go > 0) {
                        if (driver.stackChannel) arrival.revealArrivalComposer(driver.spec)
                        else arrival.revealArrivalTranscript(driver.spec)
                    }
                }
                Box(Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** Launches (or cancels, `go` unchanged) a reveal on the driver. */
    private fun launchReveal(driver: RevealDriver, spec: AnimationSpec<Float>, stackChannel: Boolean = false) {
        writeState {
            driver.spec = spec
            driver.stackChannel = stackChannel
            driver.go++
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Cancels the running reveal (the effect leaves composition). */
    private fun cancelReveal(driver: RevealDriver) {
        writeState { driver.go = 0 }
        compose.mainClock.advanceTimeByFrame()
    }

    /** The transcript reveal's reference spec (95's, the ticket's table). */
    private fun transcriptRevealSpec(): AnimationSpec<Float> =
        tween(350, delayMillis = 120, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))

    // -------------------------------------------------- prepare and bind

    /** After `prepareArrival()`: both channels 0, `prepared`/`arriving` on. */
    @Test
    fun prepareArrivalHidesBothChannels() {
        val arrival = SessionArrival()
        assertEquals(1f, arrival.transcriptAlpha, 1e-3f)
        assertEquals(1f, arrival.stackAlpha, 1e-3f)
        assertFalse(arrival.prepared)
        assertFalse(arrival.arriving)

        arrival.prepareArrival()
        assertEquals(0f, arrival.transcriptAlpha, 1e-3f)
        assertEquals(0f, arrival.stackAlpha, 1e-3f)
        assertTrue(arrival.prepared)
        assertTrue(arrival.arriving)
    }

    /**
     * Prepared **before** bind (ticket 32's order: `prepareArrival()`
     * runs before the push, before the screen is composed): the bound
     * own turn is invoked exactly once, at `bind`; a second `bind` never
     * re-runs it.
     */
    @Test
    fun prepareArrivalBeforeBindDefersTheOwnTurnToBind() {
        val arrival = SessionArrival()
        var ownTurns = 0
        arrival.prepareArrival()
        assertEquals("nothing runs before the transcript binds", 0, ownTurns)

        arrival.bind(beginOwnTurn = { ownTurns++ }, bubbleBounds = { null })
        assertEquals("bind runs the pending own turn exactly once", 1, ownTurns)

        arrival.bind(beginOwnTurn = { ownTurns++ }, bubbleBounds = { null })
        assertEquals("a second bind never re-runs it", 1, ownTurns)
    }

    /** Prepared **after** bind: invoked exactly once, at `prepareArrival()`. */
    @Test
    fun prepareArrivalAfterBindInvokesTheOwnTurnAtOnce() {
        val arrival = SessionArrival()
        var ownTurns = 0
        arrival.bind(beginOwnTurn = { ownTurns++ }, bubbleBounds = { null })
        assertEquals(0, ownTurns)
        arrival.prepareArrival()
        assertEquals(1, ownTurns)
    }

    /** With the real 69 `TranscriptState`, `bind` starts the own turn: following. */
    @Test
    fun theDeferredOwnTurnStartsFollowingThroughTheRealTranscriptState() {
        val arrival = SessionArrival()
        val transcript = TranscriptState(TextSystem(NoHandle), NoopPoster) { _, _ -> WiringEngine() }
        try {
            transcript.viewport.releaseFollow()
            assertFalse("not following before the arrival", transcript.viewport.following)

            arrival.prepareArrival()
            arrival.bind(beginOwnTurn = transcript::beginOwnTurn, bubbleBounds = { transcript.firstUserBubbleInRoot() })
            assertEquals("bind ran the pending own turn", 0f, arrival.transcriptAlpha, 1e-3f)
            assertTrue("beginOwnTurn sets following (69, without the spring)", transcript.viewport.following)
            assertFalse("no spring is started by the own turn", transcript.viewport.springRunning)
        } finally {
            transcript.close()
        }
    }

    // --------------------------------------------------------- the reveals

    /** `revealArrivalComposer()` with the default `snap()` lands within a frame. */
    @Test
    fun theComposerRevealSnapsWithinOneFrame() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        val driver = RevealDriver()
        hostReveal(arrival, driver)
        assertEquals(0f, arrival.stackAlpha, 1e-3f)

        launchReveal(driver, snap(), stackChannel = true)
        // The snap completes on the reveal coroutine's first frame (one
        // more frame can be launch slack).
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("snap() completes on the next frame", 1f, arrival.stackAlpha, 1e-3f)
        assertEquals("the transcript channel is untouched", 0f, arrival.transcriptAlpha, 1e-3f)
        assertTrue("still arriving: only finishArrival clears it", arrival.arriving)
    }

    /**
     * The transcript reveal honours the caller's spec (95's reference):
     * `0f` at 100 ms (inside the 120 ms delay), strictly between 0 and 1
     * at 300 ms, `1f` at 500 ms (470 ms of tween + frame slack).
     */
    @Test
    fun theTranscriptRevealRunsTheCallersSpec() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        val driver = RevealDriver()
        hostReveal(arrival, driver)
        launchReveal(driver, transcriptRevealSpec())

        compose.mainClock.advanceTimeBy(100)
        assertEquals("still inside the 120 ms delay", 0f, arrival.transcriptAlpha, 1e-3f)

        compose.mainClock.advanceTimeBy(200)
        val mid = arrival.transcriptAlpha
        assertTrue("mid-tween at 300 ms: $mid", mid > 0f && mid < 1f)

        compose.mainClock.advanceTimeBy(200)
        if (arrival.transcriptAlpha < 1f) compose.mainClock.advanceTimeByFrame()
        assertEquals("1f at 500 ms (470 ms + frame slack)", 1f, arrival.transcriptAlpha, 1e-3f)
        assertEquals("the stack channel is untouched", 0f, arrival.stackAlpha, 1e-3f)
    }

    /**
     * `finishArrival()` while the transcript reveal is mid-flight does
     * not jump: the channel with a running reveal keeps tweening (each
     * frame above the previous, below 1) and ends at 1; the channel with
     * no reveal running shows at once. `arriving` is false afterwards,
     * `prepared` stays true.
     */
    @Test
    fun finishArrivalDuringARevealLetsTheTweenFinishWithoutJumping() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        val driver = RevealDriver()
        hostReveal(arrival, driver)
        launchReveal(driver, transcriptRevealSpec())
        compose.mainClock.advanceTimeBy(300)
        val before = arrival.transcriptAlpha
        assertTrue("mid-tween when finishArrival runs: $before", before > 0f && before < 1f)

        compose.runOnUiThread { arrival.finishArrival() }
        assertFalse(arrival.arriving)
        assertTrue(arrival.prepared)
        assertEquals("no stack reveal running: shown at once", 1f, arrival.stackAlpha, 1e-3f)
        assertEquals("the running reveal owns the channel: finishArrival leaves it", before, arrival.transcriptAlpha, 1e-6f)

        compose.mainClock.advanceTimeByFrame()
        val after = arrival.transcriptAlpha
        assertTrue("the next frame keeps tweening: $after vs $before", after > before && after < 1f)

        compose.mainClock.advanceTimeBy(300)
        if (arrival.transcriptAlpha < 1f) compose.mainClock.advanceTimeByFrame()
        assertEquals("the tween ends at 1f", 1f, arrival.transcriptAlpha, 1e-3f)
    }

    /** `finishArrival()` with no reveal running shows both channels at once. */
    @Test
    fun finishArrivalWithNoRevealRunningShowsBothChannels() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        arrival.finishArrival()
        assertEquals(1f, arrival.transcriptAlpha, 1e-3f)
        assertEquals(1f, arrival.stackAlpha, 1e-3f)
        assertFalse(arrival.arriving)
        assertTrue("prepared stays for the screen's lifetime", arrival.prepared)
    }

    /** Cancelling the coroutine running a reveal leaves that channel at 1f. */
    @Test
    fun cancellingARevealNeverLeavesContentInvisible() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        val driver = RevealDriver()
        hostReveal(arrival, driver)
        launchReveal(driver, tween(10_000, delayMillis = 500))
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0f, arrival.transcriptAlpha, 1e-3f)

        cancelReveal(driver)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the cancelled reveal restores 1f", 1f, arrival.transcriptAlpha, 1e-3f)
        assertEquals("the stack channel is still hidden", 0f, arrival.stackAlpha, 1e-3f)
    }

    /** Calling a reveal again on the same channel cancels the running one. */
    @Test
    fun aSecondRevealOnAChannelCancelsTheRunningOne() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        val driver = RevealDriver()
        hostReveal(arrival, driver)
        launchReveal(driver, tween(500, easing = LinearEasing))
        compose.mainClock.advanceTimeBy(100)
        val mid = arrival.transcriptAlpha
        assertTrue("a slow reveal is in flight: $mid", mid > 0f && mid < 0.9f)

        launchReveal(driver, snap())
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the new reveal took the channel and snapped", 1f, arrival.transcriptAlpha, 1e-3f)
    }

    // ------------------------------------------------------ bounds hooks

    /** `arrivalComposerBounds()` is null before the composer is laid out. */
    @Test
    fun arrivalComposerBoundsIsNullBeforeLayout() {
        assertNull(SessionArrival().arrivalComposerBounds())
    }

    /**
     * Once laid out, the composer's bounds are its `positionInRoot()` and
     * measured size in px — **unclipped**: the anchored 100×48 node sits
     * 40 dp from the root's top inside a 60×60 parent that clips it, and
     * the full size survives. Detached (the node left composition), the
     * hook returns null again.
     */
    @Test
    fun arrivalComposerBoundsReportsTheUnclippedRootRect() {
        val arrival = SessionArrival()
        // One setContent per test: detaching flips this flag instead.
        var anchored by mutableStateOf(true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset(20.dp, 40.dp)
                            .size(60.dp, 60.dp)
                            .clipToBounds(),
                    ) {
                        if (anchored) {
                            // Unbounded so the node really measures 100 dp
                            // (size() alone is clamped to the 60 dp parent);
                            // TopStart keeps it at the parent's corner.
                            Box(
                                Modifier
                                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                                    .size(100.dp, 48.dp)
                                    .arrivalComposerAnchor(arrival)
                                    .testTag("composer"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()

        val d = compose.density.density
        val rect = arrival.arrivalComposerBounds()
        assertNotNull("laid out: the bounds exist", rect)
        assertEquals("topLeft is positionInRoot (x)", 20f * d, rect!!.left, 0.5f)
        assertEquals("topLeft is positionInRoot (y)", 40f * d, rect.top, 0.5f)
        assertEquals("unclipped width survives the clipping parent", 100f * d, rect.width, 0.5f)
        assertEquals("unclipped height survives the clipping parent", 48f * d, rect.height, 0.5f)

        writeState { anchored = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertNull("detached: null again", arrival.arrivalComposerBounds())
    }

    /**
     * `arrivalBubbleBounds()` is null before `bind`, and afterwards
     * returns exactly what the bound lambda returns, fresh on every call
     * (95 polls it up to 20 × 16 ms). The selection rule itself is
     * ticket 65's, tested there.
     */
    @Test
    fun arrivalBubbleBoundsForwardsToTheBoundLambdaEachCall() {
        val arrival = SessionArrival()
        assertNull("null before bind", arrival.arrivalBubbleBounds())

        val first = Rect(0f, 1f, 2f, 3f)
        val second = Rect(4f, 5f, 6f, 7f)
        var flip = 0
        arrival.bind(
            beginOwnTurn = {},
            bubbleBounds = {
                val r = if (flip == 0) first else second
                flip = 1 - flip
                r
            },
        )
        assertEquals(first, arrival.arrivalBubbleBounds())
        assertEquals(second, arrival.arrivalBubbleBounds())
        assertEquals(first, arrival.arrivalBubbleBounds())
    }

    // ---------------------------------------------------- the route level

    /**
     * The route's `arrival` is the state's instance, the two overrides
     * drive it, `state.arrivalPrepared` mirrors `arrival.prepared` — and
     * a fresh screen (no arrival) still holds on an animated push, so
     * 75's loader is unchanged.
     */
    @Test
    fun theRouteForwardsTheArrivalHooksToTheStatesInstance() {
        val core = FakeSessionCore(chatId = "chat-a")
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = Dispatchers.Unconfined,
            isMainThread = { false },
        )
        val state = SessionScreenState("chat-a", 0L) { error("the route test never composes the screen") }
        val route = SessionScreenRoute(
            controller = controller,
            core = core,
            state = state,
            text = TextSystem(NoHandle),
            queueActions = null,
        )

        assertSame(state.arrival, route.arrival)
        assertFalse(state.arrivalPrepared)
        assertTrue(
            "a fresh screen still holds on an animated push (75's loader unchanged)",
            OpenReveal.shouldHold(animatedPushToThis = true, hasAppeared = false, arrivalPrepared = state.arrivalPrepared),
        )

        route.prepareArrival()
        assertSame(state.arrival, route.arrival)
        assertTrue(route.arrival.prepared)
        assertTrue(route.arrival.arriving)
        assertEquals(0f, route.arrival.transcriptAlpha, 1e-3f)
        assertEquals(0f, route.arrival.stackAlpha, 1e-3f)
        assertTrue("arrivalPrepared mirrors arrival.prepared", state.arrivalPrepared)
        assertFalse(
            "the prepared arrival skips the open hold",
            OpenReveal.shouldHold(animatedPushToThis = true, hasAppeared = false, arrivalPrepared = state.arrivalPrepared),
        )

        route.finishArrival()
        assertFalse(route.arrival.arriving)
        assertEquals(1f, route.arrival.transcriptAlpha, 1e-3f)
        assertEquals(1f, route.arrival.stackAlpha, 1e-3f)
        assertTrue("prepared stays for the screen's lifetime", route.arrival.prepared)
    }

    // ------------------------------------------- the stack's hidden state

    /**
     * The stack channel through the real 74 container: while
     * `stackAlpha < 0.01` the (invisible) send button takes no taps and
     * is gone from the semantics tree (74's own rule, driven by the
     * arrival); after `revealArrivalComposer()` both work again.
     */
    @Test
    fun anInvisibleStackTakesNoTapsUntilTheComposerReveal() {
        val arrival = SessionArrival()
        val driver = RevealDriver()
        var sends = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                LaunchedEffect(driver.go) {
                    if (driver.go > 0) {
                        if (driver.stackChannel) arrival.revealArrivalComposer(driver.spec)
                        else arrival.revealArrivalTranscript(driver.spec)
                    }
                }
                SessionBottomStack(
                    state = rememberBottomStackState(),
                    model = BottomStackModel(bannerShown = true),
                    transcript = { _ -> Box(Modifier.fillMaxSize()) },
                    pill = { Box(Modifier.fillMaxWidth().height(30.dp).testTag("pill")) },
                    queue = {},
                    questions = {},
                    composer = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("send")
                                .clickable(interactionSource = null, indication = null) { sends++ },
                        )
                    },
                    jump = {},
                    stackAlpha = { arrival.stackAlpha },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()

        // The send button's centre, read while visible: the slots keep
        // laying out at the same place while hidden.
        val bounds = compose.onNodeWithTag("send").getBoundsInRoot()
        val x = bounds.left + bounds.width / 2f
        val y = bounds.top + bounds.height / 2f

        writeState { arrival.prepareArrival() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, arrival.stackAlpha, 1e-3f)
        compose.onNodeWithTag("send").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(x.toPx(), y.toPx())) }
        compose.waitForIdle()
        assertEquals("the invisible send button takes no taps", 0, sends)

        launchReveal(driver, snap(), stackChannel = true)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, arrival.stackAlpha, 1e-3f)
        compose.onNodeWithTag("send").assertExists()
        compose.onRoot().performTouchInput { click(Offset(x.toPx(), y.toPx())) }
        compose.waitForIdle()
        assertEquals("the send button works again", 1, sends)
    }

    // ---------------------------------------------------------- goldens

    /**
     * The arrival-prepared screen, light: the header and the background
     * (and the bottom edge fade) with no transcript rows, no composer,
     * no status pill and no loader — and the background where the
     * transcript would draw.
     */
    @Test
    fun arrivalPreparedLight() {
        shot(Appearance.Light, "arrival_prepared_light.png")
    }

    /** The arrival-prepared screen, dark. */
    @Test
    fun arrivalPreparedDark() {
        shot(Appearance.Dark, "arrival_prepared_dark.png")
    }

    /** Composes the arrival-prepared fixture and captures the golden. */
    private fun shot(appearance: Appearance, file: String) {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) { ArrivalPreparedFixture(arrival) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()

        // Nothing hidden shows: no loader (the arrival skips 75's hold),
        // no transcript, no composer, no pill in the semantics tree.
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
        compose.onNodeWithTag("transcript").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithTag("pill").assertDoesNotExist()

        compose.onRoot().captureRoboImage("session/$file")
    }

    /**
     * The light golden's pixel probe: where the transcript stand-in
     * would draw (5, 500 — clear of the header, the fade and the stack),
     * the pixel is the palette's background, not the stand-in's accent:
     * the arrival alpha really is 0.
     */
    @Test
    fun theTranscriptStandInDrawsNothingWhilePrepared() {
        val arrival = SessionArrival()
        arrival.prepareArrival()
        compose.setContent {
            RobocoTheme(Appearance.Light) { ArrivalPreparedFixture(arrival) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val px = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        val c = px[(5f * d).toInt(), (500f * d).toInt()]
        assertTrue(
            "the background where the transcript would draw: $c",
            colorDistance(c, Palette.of(false).background) < 2f / 255f,
        )
    }

    /**
     * The arrival-prepared session fixture: 75's scaffold and fixture
     * header around 74's real container, whose transcript slot is 75's
     * [OpenRevealLayers] with the arrival's transcript channel and the
     * hidden transcript stand-in, and whose stack channels are driven by
     * the arrival (stand-in pill/composer; both invisible). The open
     * reveal is fresh — `begin(false)`-shaped, as the arrival-prepared
     * hold decision leaves it.
     */
    @Composable
    private fun ArrivalPreparedFixture(arrival: SessionArrival) {
        val palette = RobocoTheme.palette
        val reveal = remember { OpenReveal() }
        SessionScaffold(
            reveal = reveal,
            header = { SessionOpenFixtureHeader() },
        ) {
            SessionBottomStack(
                state = rememberBottomStackState(),
                model = BottomStackModel(bannerShown = true),
                transcript = { _ ->
                    OpenRevealLayers(
                        reveal,
                        statusBarTop = 24.dp,
                        navBarBottom = 0.dp,
                        transcriptAlpha = { arrival.transcriptAlpha },
                    ) {
                        // Accent-filled so the golden and the pixel probe
                        // would catch an alpha leak (a visible stand-in).
                        Box(
                            Modifier
                                .arrivalHidden { arrival.transcriptAlpha }
                                .fillMaxSize()
                                .background(palette.accent)
                                .testTag("transcript"),
                        )
                    }
                },
                pill = { Box(Modifier.fillMaxWidth().height(30.dp).background(palette.accent).testTag("pill")) },
                queue = {},
                questions = {},
                composer = {
                    Box(Modifier.fillMaxWidth().height(50.dp).background(palette.accent).testTag("composer"))
                },
                jump = {},
                stackAlpha = { arrival.stackAlpha },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** The max channel distance between two colours (pixel probes). */
    private fun colorDistance(a: Color, b: Color): Float =
        max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))

    // ------------------------------------------------- transcript fakes

    /** A poster that never fires: no relay hop, so no frame is ever applied. */
    private object NoopPoster : FramePoster {
        override fun post(callback: Choreographer.FrameCallback) {}
        override fun remove(callback: Choreographer.FrameCallback) {}
    }

    /** The own-turn test's engine: no FFI, no frames. */
    private class WiringEngine : TranscriptView(NoHandle) {
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("the arrival test never asks the engine for a frame")
        override fun shutdown() {}
        override fun close() {}
    }
}
