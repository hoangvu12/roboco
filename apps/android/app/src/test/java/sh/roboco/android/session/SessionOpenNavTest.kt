package sh.roboco.android.session

import android.view.Choreographer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntryEffect
import sh.roboco.android.shell.NavKind
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The open choreography against a real navigation stack
 * (android-ui-parity #75): Robolectric + `createComposeRule` at
 * `w412dp-h900dp-xxhdpi`, ticket 24's [NavStack] + [NavStackHost] with a
 * test route that holds a [SessionScreenState] (a throwing transcript
 * factory — the nav flow never touches it) and runs `SessionScreen`'s
 * hold decision (step 5.3) and landing hook (step 5.5) around a
 * [SessionScaffold] fixture.
 *
 * The second half is the `SessionScreen` wiring check: attach and
 * markSeen once, in order, after the frame listener — through a host
 * disposal — over 51's `FakeSessionCore` and a fake-engine
 * [TranscriptState] (no Rust).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionOpenNavTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlassAndReduceMotion() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ harness

    /** The plain root route of the test stack. */
    private object RootRoute : NavRoute

    /** A session route holding its [SessionScreenState] (the route owns it, as 76 will). */
    private class SessionRoute(val state: SessionScreenState) : NavRoute {
        override val hidesBottomChrome: Boolean = true
        override val paneTitle: String? = "Session"
    }

    private var nextChat = 0

    /** A state whose transcript factory is never run by the nav flow. */
    private fun newState(): SessionScreenState = SessionScreenState(
        chatId = "chat-${nextChat++}",
        openedAtNanos = 0L,
        transcriptFactory = { error("the nav flow never touches the transcript") },
    )

    /**
     * `SessionScreen`'s steps 5.3 and 5.5, around the scaffold fixture:
     * the hold decision at first composition (before the scaffold — a
     * frame later would be too late) and the landing hook.
     */
    @Composable
    private fun SessionOpenProbeScreen(route: SessionRoute) {
        val entry = LocalNavEntry.current
        val stack = LocalNavStack.current
        remember(route.state) {
            val t = stack.transition
            route.state.reveal.begin(
                OpenReveal.shouldHold(
                    animatedPushToThis = t != null && t.kind == NavKind.Push && t.to === entry && t.animated,
                    hasAppeared = entry.hasAppeared,
                    arrivalPrepared = route.state.arrivalPrepared,
                ),
            )
        }
        NavEntryEffect(onDidAppear = { _ -> route.state.reveal.onLanded() })
        SessionScaffold(
            reveal = route.state.reveal,
            header = { SessionOpenFixtureHeader() },
        ) {
            SessionOpenFixtureBody(route.state.reveal)
        }
    }

    private fun host(stack: NavStack) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NavStackHost(stack, active = true) { entry ->
                    // A local: no smart cast on a main-module property.
                    val route = entry.route
                    if (route is SessionRoute) {
                        SessionOpenProbeScreen(route)
                    } else {
                        Box(Modifier.fillMaxSize().testTag("root-screen"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * A state write from the test, delivered to the Recomposer: under
     * Robolectric `runOnUiThread` runs inline, and a write made outside
     * composition only reaches the Recomposer through
     * `Snapshot.sendApplyNotifications()` — the frozen clock never idles
     * the paused main Looper on its own.
     */
    private fun writeOnUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    /** Pushes a session route and returns its state. */
    private fun pushSession(stack: NavStack, animated: Boolean = true): SessionScreenState {
        val state = newState()
        writeOnUi { stack.push(SessionRoute(state), animated = animated) }
        return state
    }

    /** Advances the frozen clock until the in-flight transition completes. */
    private fun settleTransition(stack: NavStack) {
        compose.mainClock.advanceTimeUntil { stack.transition == null }
    }

    // --------------------------------------------------- the hold decision

    /**
     * An animated push holds on the **first composed frame**: the loader
     * is up and the transcript is out of the semantics tree before any
     * frame could show it.
     */
    @Test
    fun anAnimatedPushHoldsOnTheFirstComposedFrame() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = pushSession(stack, animated = true)

        compose.mainClock.advanceTimeByFrame()
        assertEquals(OpenReveal.Phase.Holding, state.reveal.phase)
        assertEquals(0f, state.reveal.progress, 1e-3f)
        compose.onNodeWithTag("session-loading").assertExists()
        compose.onNodeWithTag("transcript").assertDoesNotExist()
    }

    /** After the push transition completes (0.5 s spring) the screen has landed. */
    @Test
    fun thePushLandsWhenTheTransitionCompletes() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = pushSession(stack, animated = true)

        settleTransition(stack)
        assertTrue(state.reveal.landed)
        assertEquals("no rows yet: still holding", OpenReveal.Phase.Holding, state.reveal.phase)
    }

    /** An unanimated push (the handoff's, a column swap's) never holds. */
    @Test
    fun anUnanimatedPushDoesNotHold() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = pushSession(stack, animated = false)

        compose.mainClock.advanceTimeBy(64)
        assertEquals(OpenReveal.Phase.Idle, state.reveal.phase)
        assertEquals(1f, state.reveal.progress, 1e-3f)
        assertTrue(state.reveal.landed)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
    }

    /** 77's prepared arrival skips the loader even on an animated push. */
    @Test
    fun aPreparedArrivalSkipsTheLoader() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = newState()
        writeOnUi { state.arrival.prepareArrival() }
        writeOnUi { stack.push(SessionRoute(state), animated = true) }

        compose.mainClock.advanceTimeBy(64)
        assertEquals(OpenReveal.Phase.Idle, state.reveal.phase)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
    }

    // ---------------------------------------------------- the reveal flow

    /**
     * Rows after landing: `Revealing` at once, `Shown` after the 180 ms
     * tween (plus the launch slack), the loader gone.
     */
    @Test
    fun rowsAfterLandingRevealAndFinish() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = pushSession(stack, animated = true)
        settleTransition(stack)

        writeOnUi { state.onFrameApplied(rowCount = 3, buildMicros = 1000) }
        assertEquals(OpenReveal.Phase.Revealing, state.reveal.phase)

        // 180 ms tween + two frames of launch slack, as the scaffold test.
        compose.mainClock.advanceTimeBy(250)
        assertEquals(OpenReveal.Phase.Shown, state.reveal.phase)
        assertEquals(1f, state.reveal.progress, 1e-3f)
        compose.onNodeWithTag("session-loading").assertDoesNotExist()
        compose.onNodeWithTag("transcript").assertExists()
    }

    /** With no rows, the 8 s give-up fires after landing and fakes `reportedOpen`. */
    @Test
    fun withNoRowsTheGiveUpRevealsAfterEightSeconds() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val state = pushSession(stack, animated = true)
        settleTransition(stack)

        compose.mainClock.advanceTimeBy(8_064)
        assertTrue(state.reveal.reportedOpen)
        assertEquals("revealed by the give-up, mid-tween", OpenReveal.Phase.Revealing, state.reveal.phase)
    }

    /** Popping back to a screen that has appeared leaves it `Shown`. */
    @Test
    fun poppingBackToAnAppearedScreenLeavesItShown() {
        val stack = NavStack(RootRoute)
        host(stack)
        compose.mainClock.advanceTimeBy(100)
        val first = pushSession(stack, animated = true)
        settleTransition(stack)
        writeOnUi { first.onFrameApplied(rowCount = 3, buildMicros = 1000) }
        compose.mainClock.advanceTimeBy(250)
        assertEquals(OpenReveal.Phase.Shown, first.reveal.phase)

        val second = pushSession(stack, animated = true)
        settleTransition(stack)
        assertEquals("the pushed-away screen holds, rows or not", OpenReveal.Phase.Holding, second.reveal.phase)
        writeOnUi { stack.pop() }
        settleTransition(stack)
        assertEquals(OpenReveal.Phase.Shown, first.reveal.phase)
        assertTrue("the re-appear landed again, harmlessly", first.reveal.landed)
    }

    // --------------------------------------------------- SessionScreen wiring

    /** A poster that never fires: no relay hop, so no frame is ever applied. */
    private object NoopPoster : FramePoster {
        override fun post(callback: Choreographer.FrameCallback) {}
        override fun remove(callback: Choreographer.FrameCallback) {}
    }

    /** The wiring check's engine: no FFI, no frames. */
    private class WiringEngine : TranscriptView(NoHandle) {
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("the wiring test never asks the engine for a frame")
        override fun shutdown() {}
        override fun close() {}
    }

    /** A `SessionScreenState` over the fake engine (created on main, in composition). */
    private fun wiringState(): SessionScreenState = SessionScreenState(
        chatId = "chat-a",
        openedAtNanos = 1_000_000_000L,
        transcriptFactory = {
            TranscriptState(TextSystem(NoHandle), NoopPoster) { _, _ -> WiringEngine() }
        },
    )

    /**
     * `SessionScreen` starts the controller exactly once — the frame
     * listener, then `attach`, then `markSeen` (51's call log) — and a
     * host disposal + re-entry changes nothing: the same state, the same
     * transcript, no detach, no close.
     */
    @Test
    fun sessionScreenAttachesAndMarksSeenOnceThroughHostDisposal() {
        val core = FakeSessionCore(chatId = "chat-a")
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = Dispatchers.Unconfined,
            isMainThread = { false },
        )
        val state = wiringState()
        var hostOn by mutableStateOf(true)
        val stack = NavStack(RootRoute)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                if (hostOn) {
                    NavStackHost(stack, active = true) { entry ->
                        if (entry.route is SessionRoute) {
                            SessionScreen(controller, state)
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        writeOnUi { stack.push(SessionRoute(state), animated = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()

        // attach → setViewAttached(true) → markSeen, each once, in order
        // (the frame listener was registered first — it emitted nothing:
        // no frame has been applied).
        val attachAt = core.callLog.indexOf("attachTranscript")
        val markSeenAt = core.callLog.indexOf("markSeen")
        assertTrue("attach ran", attachAt >= 0)
        assertTrue("markSeen ran", markSeenAt >= 0)
        assertTrue("attach precedes markSeen", attachAt < markSeenAt)
        assertEquals(1, core.callLog.count { it == "attachTranscript" })
        assertEquals(1, core.callLog.count { it == "setViewAttached(true)" })
        assertEquals(1, core.callLog.count { it == "markSeen" })
        val transcript = state.transcript
        assertTrue(state.transcriptCreated)

        // Leave and re-enter composition (a tab-host disposal): the route
        // still holds the same state and transcript, and the one-time
        // start never re-runs.
        writeOnUi { hostOn = false }
        compose.mainClock.advanceTimeBy(64)
        writeOnUi { hostOn = true }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        assertSame(transcript, state.transcript)
        assertEquals(1, core.callLog.count { it == "attachTranscript" })
        assertEquals(1, core.callLog.count { it == "markSeen" })
        assertFalse("this ticket never detaches (85 does, on pop commit)", core.callLog.any { it == "setViewAttached(false)" })
    }

    /**
     * The transcript's insets through the real screen: the top inset is
     * 52's `contentTopInset` (44 dp under Robolectric's zero status bar),
     * the bottom inset is 74's `covered` — `H − stackTop + 8` = 16 dp with
     * the empty-slot stack at 412 × 900 and no nav bar — not some other
     * derivation. Since 133, that write is 133's measure-time
     * `transcriptBottomInset` on the host (the same pass 74 measured the
     * stack in), not a post-layout `SideEffect`.
     */
    @Test
    fun theTranscriptInsetsFollowTheHeaderAndTheCoveredStack() {
        val core = FakeSessionCore(chatId = "chat-a")
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            io = Dispatchers.Unconfined,
            isMainThread = { false },
        )
        val state = wiringState()
        val stack = NavStack(RootRoute)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NavStackHost(stack, active = true) { entry ->
                    if (entry.route is SessionRoute) {
                        SessionScreen(controller, state)
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        writeOnUi { stack.push(SessionRoute(state), animated = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("topInset = statusBarTop(0) + 44", 44f, state.transcript.topInset, 0.5f)
            // 74's geometry: stackBottom = 900 − 0 − 8, an empty stack, so
            // stackTop = 892 and covered = 900 − 892 + 8 = 16.
            assertEquals("bottomInset = 74's covered", 16f, state.transcript.bottomInset, 0.5f)
        }
    }
}
