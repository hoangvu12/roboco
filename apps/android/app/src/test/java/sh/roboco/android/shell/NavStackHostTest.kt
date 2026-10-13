package sh.roboco.android.shell

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * Ticket 24's host behaviour (Robolectric, `createAndroidComposeRule`): the
 * spring curve and timing, UIKit-parity callback order through a real host,
 * predictive back driven through `OnBackPressedDispatcher` (commit and
 * cancel), the one-transition rule, touch blocking, tab switches, and host
 * detach. Written per the test policy; the batch run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NavStackHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val taps = HashMap<Long, Int>()
    private val tickers = HashMap<Long, Int>()
    private val saveables = HashMap<Long, Int>()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    /** A route with value equality, named for the log. */
    private data class TestRoute(override val paneTitle: String, override val hidesBottomChrome: Boolean = false) :
        NavRoute

    /** The probe screen: tap counter (also the saveable), ticker, dispose log. */
    @Composable
    private fun ProbeScreen(entry: NavEntry) {
        val id = entry.id
        DisposableEffect(id) {
            onDispose { log += "dispose-$id" }
        }
        LaunchedEffect(id) {
            while (true) {
                tickers[id] = (tickers[id] ?: 0) + 1
                delay(50)
            }
        }
        var saveableTaps by rememberSaveable(id) { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .testTag("screen-$id")
                .clickable {
                    taps[id] = (taps[id] ?: 0) + 1
                    saveableTaps++
                    saveables[id] = saveableTaps
                },
        )
        NavEntryEffect(
            onWillAppear = { log += "willAppear-$id" },
            onDidAppear = { log += "didAppear-$id" },
            onWillDisappear = { _, _ -> log += "willDisappear-$id" },
            onDidDisappear = { log += "didDisappear-$id" },
        )
    }

    private fun setContent(stack: NavStack, active: Boolean = true) {
        compose.setContent {
            RobocoTheme {
                NavStackHost(stack, active = active) { entry -> ProbeScreen(entry) }
            }
        }
    }

    /** Starts a predictive back gesture and progresses it to [progress]. */
    private fun startBackGesture(progress: Float) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, progress, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    /**
     * Runs [block] on the UI thread and publishes its snapshot writes. Under
     * Robolectric `runOnUiThread` runs inline, and a write made outside
     * composition reaches the Recomposer only through
     * `Snapshot.sendApplyNotifications()`, which Compose otherwise posts to
     * the paused main Looper; the frozen `mainClock` never idles that Looper,
     * so without this the op is noticed a variable number of frames late.
     */
    private fun writeOnUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    /**
     * Advances the frozen clock frame by frame until the driver's animation
     * has produced its first non-zero value: the animation's second frame,
     * play time = one frame (16 ms). The start-up frames before it (the
     * recomposition that launches the driver, then the animation's
     * play-time-0 frame) are Compose scheduling, not part of the curve.
     */
    private fun advanceToFirstAnimatedFrame(stack: NavStack) {
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 0f) > 0f }
    }

    // ------------------------------------------------------- curve + timing

    @Test
    fun pushAnimatesWithTheNavSpring() {
        val stack = NavStack(TestRoute("root"))
        compose.mainClock.autoAdvance = false
        setContent(stack)
        compose.mainClock.advanceTimeBy(100)
        writeOnUi { stack.push(TestRoute("b")) }
        advanceToFirstAnimatedFrame(stack) // play time 16 ms
        compose.mainClock.advanceTimeBy(240) // play time 256 ms ≈ 250 ms of spring
        val progress = stack.transition?.progress
        assertNotNull("push must still be running after 250 ms", progress)
        assertEquals(0.94f, progress!!, 0.02f)
        compose.mainClock.advanceTimeBy(344) // play time 600 ms
        assertNull("push must settle within 600 ms", stack.transition)
        assertEquals(2, stack.entries.size)
        assertTrue(stack.top.hasAppeared)
    }

    @Test
    fun reducedMotionEndsTheCrossfadeAt150ms() {
        ReduceMotion.debugOverride = true
        val stack = NavStack(TestRoute("root"))
        compose.mainClock.autoAdvance = false
        setContent(stack)
        compose.mainClock.advanceTimeBy(100)
        writeOnUi { stack.push(TestRoute("b")) }
        advanceToFirstAnimatedFrame(stack) // play time 16 ms
        compose.mainClock.advanceTimeBy(64) // play time 80 ms: the nearest frame to 75 ms
        val mid = stack.transition?.progress
        assertNotNull(mid)
        assertTrue("half-way cross-fade at 75 ms, was $mid", mid!! in 0.35f..0.6f)
        compose.mainClock.advanceTimeBy(80) // play time 160 ms: the first frame past 150 ms
        assertNull("reduced cross-fade must end at 150 ms (± a frame)", stack.transition)
        assertEquals(2, stack.entries.size)
    }

    // -------------------------------------------------------- callback order

    @Test
    fun pushCallbackOrderMatchesTheLifecycleTable() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val rootId = stack.entries[0].id
        val bId = stack.entries[1].id
        assertEquals(
            listOf(
                "willDisappear-$rootId",
                "willAppear-$bId",
                "didDisappear-$rootId",
                "didAppear-$bId",
            ),
            log,
        )
    }

    @Test
    fun committedPopTearsDownOnlyAfterDidDisappear() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val rootId = stack.entries[0].id
        val b = stack.entries[1]
        log.clear()

        compose.runOnUiThread { stack.pop() }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "willDisappear-${b.id}",
                "willAppear-$rootId",
                "didDisappear-${b.id}",
                "didAppear-$rootId",
                // The disposal runs exactly once, after didDisappear(removed = true).
                "dispose-${b.id}",
            ),
            log,
        )
        assertEquals(1, stack.entries.size)
        assertTrue(b.isClosed)
        assertFalse(b.scope.isActive)
    }

    @Test
    fun popToRootFromDepthThreeClosesTheIntermediateSilently() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("c")) }
        compose.waitForIdle()
        val rootId = stack.entries[0].id
        val b = stack.entries[1]
        val c = stack.entries[2]
        log.clear()

        compose.runOnUiThread { stack.popToRoot() }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "willDisappear-${c.id}",
                "willAppear-$rootId",
                "didDisappear-${c.id}",
                "didAppear-$rootId",
                // The intermediate b gets no appear/disappear callbacks, only close().
                // b and c are finalized in the same sweep and leave composition in
                // one frame; Compose runs onDispose of siblings removed together in
                // reverse slot order (leaving = [b, c]), so c's disposal logs first.
                "dispose-${c.id}",
                "dispose-${b.id}",
            ),
            log,
        )
        assertEquals(1, stack.entries.size)
        assertTrue(b.isClosed)
        assertTrue(c.isClosed)
    }

    @Test
    fun setRoutesLogsTheReplaceOrder() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val root = stack.entries[0]
        val old = stack.entries[1]
        log.clear()

        compose.runOnUiThread { stack.setRoutes(listOf(TestRoute("c"))) }
        compose.waitForIdle()
        val new = stack.top
        assertEquals(
            listOf(
                "willDisappear-${old.id}",
                "willAppear-${new.id}",
                "didDisappear-${old.id}",
                "didAppear-${new.id}",
                // [c] replaces both entries: the old top gets the full pair, the
                // non-top root only close(). Both are finalized in one sweep and
                // Compose disposes siblings in reverse slot order (leaving =
                // [root, old]), so the old top's disposal logs first.
                "dispose-${old.id}",
                "dispose-${root.id}",
            ),
            log,
        )
        assertEquals(1, stack.entries.size)
        assertTrue(old.isClosed)
        assertTrue(root.isClosed)
        assertEquals("c", new.route.paneTitle)
    }

    @Test
    fun setRoutesWithTheSameTopLogsNoCallbacks() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val root = stack.entries[0]
        val b = stack.entries[1]
        log.clear()

        // The new top is the old top entry (b is reused on top); the removed
        // non-top root only gets close(). No appear/disappear callbacks.
        compose.runOnUiThread { stack.setRoutes(listOf(TestRoute("b"))) }
        compose.waitForIdle()
        assertEquals(listOf("dispose-${root.id}"), log)
        assertSame(b, stack.top)
        assertFalse(b.isClosed)
    }

    // ------------------------------------------------------ predictive back

    @Test
    fun predictiveBackCancelKeepsTheScreenAlive() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val rootId = stack.entries[0].id
        val b = stack.entries[1]
        val dispatcher = compose.activity.onBackPressedDispatcher
        log.clear()

        startBackGesture(0.4f)
        assertEquals(0.4f, stack.transition?.progress ?: -1f, 0.01f)
        val ticksBefore = tickers[b.id] ?: 0

        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle() // the cancel spring runs to 0

        assertNull(stack.transition)
        assertEquals(2, stack.entries.size)
        assertFalse(b.isClosed)
        assertTrue(b.scope.isActive)
        // Cancel-restore callbacks; no disposal of the top screen.
        assertEquals(
            listOf(
                "willDisappear-${b.id}",
                "willAppear-$rootId",
                "willDisappear-$rootId",
                "willAppear-${b.id}",
                "didDisappear-$rootId",
                "didAppear-${b.id}",
            ),
            log,
        )
        // The screen-level ticker keeps ticking after the cancel.
        compose.mainClock.advanceTimeBy(200)
        assertTrue("ticker must keep ticking after a cancelled pop", (tickers[b.id] ?: 0) > ticksBefore)
    }

    @Test
    fun predictiveBackCommitSpringsToTheEndAndRemovesTheEntry() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val rootId = stack.entries[0].id
        val b = stack.entries[1]
        val dispatcher = compose.activity.onBackPressedDispatcher
        log.clear()

        startBackGesture(0.4f)
        assertEquals(0.4f, stack.transition?.progress ?: -1f, 0.01f)

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle() // the commit spring runs 0.4 → 1

        assertNull(stack.transition)
        assertEquals(1, stack.entries.size)
        assertTrue(b.isClosed)
        assertEquals(
            listOf(
                "willDisappear-${b.id}",
                "willAppear-$rootId",
                "didDisappear-${b.id}",
                "didAppear-$rootId",
                "dispose-${b.id}",
            ),
            log,
        )
    }

    @Test
    fun backHandlerIsDisabledAtDepthOneAndForInactiveTabs() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        compose.runOnUiThread { stack.pop() }
        compose.waitForIdle()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun backHandlerIsDisabledForInactiveTabs() {
        val stack = NavStack(TestRoute("root")).apply { push(TestRoute("b")) }
        setContent(stack, active = false)
        compose.waitForIdle()
        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // ---------------------------------------------------- one op at a time

    @Test
    fun popToRootThenPushInOneFrameProducesAPushFromRoot() {
        val stack = NavStack(TestRoute("root"))
        compose.mainClock.autoAdvance = false
        setContent(stack)
        compose.mainClock.advanceTimeBy(100)
        val root = stack.entries[0]
        compose.runOnUiThread {
            stack.push(TestRoute("b"))
            stack.push(TestRoute("c"))
        }
        compose.mainClock.advanceTimeBy(50) // the b → c push is mid-flight
        val t = stack.transition!!
        assertEquals(NavKind.Push, t.kind)
        assertSame(stack.entries[1], t.from)
        assertSame(stack.entries[2], t.to)

        // The iOS openSession deep link: pop to root, then push, in one frame.
        compose.runOnUiThread {
            stack.popToRoot(animated = false)
            stack.push(TestRoute("x"), animated = true)
        }
        val t2 = stack.transition!!
        assertEquals(NavKind.Push, t2.kind)
        assertSame(root, t2.from)
        assertSame(stack.entries[1], t2.to)
        assertEquals(2, stack.entries.size)
    }

    @Test
    fun aDeepLinkDuringAHeldGestureAbandonsItAndTheLateCompletionIsIgnored() {
        val stack = NavStack(TestRoute("root"))
        setContent(stack)
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher

        startBackGesture(0.4f)
        val held = stack.transition!!
        assertTrue(held.isInteractive)

        compose.runOnUiThread {
            stack.popToRoot(animated = false)
            stack.push(TestRoute("x"), animated = true)
        }
        // Checked synchronously, as popToRootThenPushInOneFrame does: with
        // autoAdvance on, waitForIdle() runs the new push to completion and
        // clears the transition.
        assertTrue(held.abandoned)
        assertEquals(true, held.cancelled)
        assertEquals(0f, held.progress, 1e-3f)
        assertEquals(2, stack.entries.size)
        assertEquals(NavKind.Push, stack.transition?.kind)
        compose.waitForIdle()
        assertEquals(2, stack.entries.size)

        // The gesture completes late: the abandoned transition ignores it and
        // no pop starts.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(2, stack.entries.size)
        assertFalse(stack.entries[1].isClosed)
    }

    // --------------------------------------------------------- input block

    @Test
    fun touchesDuringATransitionReachNeitherScreen() {
        val stack = NavStack(TestRoute("root")).apply {
            holdHostlessOps()
            push(TestRoute("b"))
            freeze(0.5f)
        }
        setContent(stack)
        compose.waitForIdle()
        assertNotNull(stack.transition)
        compose.onNodeWithTag("screen-${stack.top.id}").performClick()
        compose.waitForIdle()
        assertEquals(0, taps[stack.top.id] ?: 0)
        assertEquals(0, taps[stack.entries[0].id] ?: 0)
    }

    @Test
    fun tapsWorkOnceTheTransitionIsNull() {
        val stack = NavStack(TestRoute("root")).apply { push(TestRoute("b")) }
        setContent(stack)
        compose.waitForIdle()
        assertNull(stack.transition)
        compose.onNodeWithTag("screen-${stack.top.id}").performClick()
        compose.waitForIdle()
        assertEquals(1, taps[stack.top.id] ?: 0)
    }

    // -------------------------------------------------------- tab switching

    @Test
    fun aTabSwitchKeepsTheStackAndTheScreenState() {
        val stack = NavStack(TestRoute("root"))
        var active by mutableStateOf(true)
        compose.setContent {
            RobocoTheme {
                NavStackHost(stack, active = active) { entry -> ProbeScreen(entry) }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            stack.push(TestRoute("b"))
            stack.push(TestRoute("c"))
        }
        compose.waitForIdle()
        val c = stack.top
        compose.onNodeWithTag("screen-${c.id}").performClick()
        compose.waitForIdle()
        assertEquals(1, saveables[c.id])

        compose.runOnUiThread { active = false }
        compose.waitForIdle()
        compose.runOnUiThread { active = true }
        compose.waitForIdle()

        assertEquals(3, stack.entries.size)
        assertSame(c, stack.top)
        assertEquals(1, saveables[c.id])
        // The tab-switch callbacks: disappear then appear, on the top screen.
        val tail = log.takeLast(4)
        assertEquals(
            listOf("willDisappear-${c.id}", "didDisappear-${c.id}", "willAppear-${c.id}", "didAppear-${c.id}"),
            tail,
        )
    }

    // ---------------------------------------------------------- host detach

    @Test
    fun removingTheHostMidPopCommitsAndFinalizesInTheSameFrame() {
        val stack = NavStack(TestRoute("root"))
        var showHost by mutableStateOf(true)
        compose.setContent {
            RobocoTheme {
                if (showHost) {
                    NavStackHost(stack, active = true) { entry -> ProbeScreen(entry) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { stack.push(TestRoute("b")) }
        compose.waitForIdle()
        val b = stack.entries[1]
        var retainedCloses = 0
        compose.runOnUiThread {
            b.retain("res") { Retained { retainedCloses++ } }
        }
        log.clear()

        compose.mainClock.autoAdvance = false
        writeOnUi { stack.pop() }
        compose.mainClock.advanceTimeBy(100) // the pop is mid-animation
        assertNotNull(stack.transition)

        writeOnUi { showHost = false }
        compose.mainClock.advanceTimeBy(32) // the disposal runs
        assertNull(stack.transition)
        assertEquals(1, stack.entries.size)
        assertTrue(stack.leaving.isEmpty())
        assertTrue(b.isClosed)
        assertEquals(1, retainedCloses)
        // No attach/detach callbacks of their own. Per ticket 24 ("Disposal
        // order on host detach"), Compose forgets in reverse order, so both
        // screens' NavEntryEffect listeners (b, then root) are removed before
        // the host's onDispose runs onHostDetached(): the commit's did* pair
        // reaches no listener, and close() is the guaranteed teardown path
        // (asserted above). The whole host leaves, so the root screen
        // disposes too.
        assertEquals(
            listOf(
                "willDisappear-${b.id}",
                "willAppear-${stack.entries[0].id}",
                "dispose-${b.id}",
                "dispose-${stack.entries[0].id}",
            ),
            log,
        )
    }

    // -------------------------------------------------- non-animated pushes

    @Test
    fun nonAnimatedPushShowsTheTargetInPlaceOnTheFirstFrame() {
        val stack = NavStack(TestRoute("root"))
        compose.mainClock.autoAdvance = false
        setContent(stack)
        compose.mainClock.advanceTimeBy(100)
        writeOnUi { stack.push(TestRoute("b"), animated = false) }
        val t = stack.transition!!
        assertFalse(t.animated)
        // progress starts at 1: the very first frame shows `to` at tx 0, alpha 1,
        // with no frame of `from` alone.
        assertEquals(1f, t.progress, 1e-6f)
        compose.mainClock.advanceTimeBy(32)
        assertNull(stack.transition)
    }

    /** An AutoCloseable that counts its closes. */
    private class Retained(private val onClose: () -> Unit) : AutoCloseable {
        override fun close() {
            onClose()
        }
    }
}
