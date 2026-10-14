package sh.roboco.android.session

import android.view.Choreographer
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DemoRoute
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.LocalTransitionProgress
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.ProvideTransitionProgress
import sh.roboco.android.shell.TransitionProgress
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.ModelPipeline
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * [SessionNavEffects] + the choreography against a real
 * [NavStackHost] (android-ui-parity #85): Robolectric,
 * `createAndroidComposeRule<ComponentActivity>` with a frozen clock for
 * the spring sampling. A test session route composes 85's wiring
 * (rememberSessionNavAlpha → [SessionNavEffects] → the gallery's fake
 * stack and jump) around a real [SessionNavLifecycle] on fakes — a real
 * ticket-51 controller over [FakeSessionCore] (an unconfined IO lane, so
 * its call log fills at once) and a fake-engine [TranscriptState].
 *
 * The cases are the ticket's acceptance list: the push curve and its
 * equality with ticket 30's `LocalTransitionProgress.presence`,
 * predictive back (progress, cancel with the reattach, commit with the
 * post-`didDisappear` teardown), programmatic pop, the tab-switch
 * composition dispose/re-enter, the configuration-change dispose, the
 * unanimated push, and reduced motion. The Android form of
 * `testCancelledSwipeBackKeepsStreaming`'s "still streaming" is a frame
 * applied after the cancel plus the in-content ticker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SessionNavEffectsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The shared log: the adapters', the router's and the dispose marker's entries. */
    private val log = mutableListOf<String>()

    /** The per-entry ticker counts (the "still alive" probe). */
    private val ticks = HashMap<Long, Int>()

    private val core = FakeSessionCore(chatId = "chat-a")

    private val controller = SessionController.create(
        core = core,
        hub = SessionEventHub(post = { it.run() }),
        io = Dispatchers.Unconfined,
        isMainThread = { false },
    )

    private val transcript: TranscriptState = fakeTranscript()

    /** The recording router. */
    private val router = RecordingRouter(log)

    /** The newest session's alpha object, captured from composition. */
    private var navRef: SessionNavAlpha? = null

    /** Ticket 30's progress of the newest session, captured from composition. */
    private var progressRef: TransitionProgress? = null

    @Before
    fun forceFallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restoreGlassAndReduceMotion() {
        Glass.forceFallback = false
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ the route

    /** The test session route: a plain class, identity equality (like 76's). */
    private class TestSessionRoute : NavRoute {
        override val hidesBottomChrome: Boolean = true
        override val paneTitle: String = "Session"
    }

    /** The recording router: only the two presence callbacks are wired. */
    private class RecordingRouter(private val log: MutableList<String>) : AppRouter {
        override fun openSession(chatId: String) {}
        override fun openSession(chatId: String, handoff: DraftHandoff?) {}
        override fun presentNewSession(prompt: String?) {}
        override fun showSettings() {}
        override fun showSearch() {}
        override fun showSessionOpenError() {}
        override fun popToFrontPage() {}
        override fun sessionDidAppear(chatId: String) {
            log += "sessionDidAppear"
        }

        override fun sessionDidClose(chatId: String) {
            log += "sessionDidClose"
        }
    }

    // ------------------------------------------------------------ fixtures

    /** A poster that never fires: no relay hop, so no frame is applied by the engine. */
    private object NoopPoster : FramePoster {
        override fun post(callback: Choreographer.FrameCallback) {}
        override fun remove(callback: Choreographer.FrameCallback) {}
    }

    /** The fake engine: no FFI, no frames, no-op shutdown/close. */
    private class FakeEngine : TranscriptView(NoHandle) {
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("the effects test never asks the engine for a frame")
        override fun shutdown() {}
        override fun close() {}
    }

    /** A frame with a fixed revision and no rows (the "still streaming" probe). */
    private class EffectsFrame(private val rev: ULong) : LayoutFrame(NoHandle) {
        override fun revision() = rev
        override fun styleCount() = 0u
        override fun styles(): List<StyleDesc> = emptyList()
        override fun rowCount() = 0u
        override fun totalHeight() = 0f
    }

    /** A one-thread, daemon executor (no android call in its factory). */
    private fun plainWorker(): ThreadPoolExecutor =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
            Thread(runnable, "session-nav-effects-test").apply { isDaemon = true }
        }

    /** A real [TranscriptState] over the fake engine, pipeline injected. */
    private fun fakeTranscript(): TranscriptState {
        val worker = plainWorker()
        return TranscriptState(
            text = TextSystem(NoHandle),
            poster = NoopPoster,
            makeModels = { onUpgradeBuilt ->
                ModelPipeline(worker = worker, postMain = { it.run() }, onUpgradeBuilt = onUpgradeBuilt)
            },
            makeEngine = { _, _ -> FakeEngine() },
        )
    }

    // ------------------------------------------------------------ the mount

    /** The retention key of the test lifecycle, per entry. */
    private object EffectsLifecycleKey

    /** 85's wiring, exactly as 76's route does it, on the test's fakes. */
    @Composable
    private fun TestSessionContent(entry: NavEntry) {
        val stack = LocalNavStack.current
        val nav = rememberSessionNavAlpha(stack, entry)
        navRef = nav
        // The "keeps streaming" probe: the screen's own ticker.
        LaunchedEffect(entry.id) {
            while (true) {
                ticks[entry.id] = (ticks[entry.id] ?: 0) + 1
                delay(50)
            }
        }
        // 24's finalize order probe: the content's onDispose runs after the
        // committed pop's didDisappear(true) delivered the teardown.
        DisposableEffect(entry.id) {
            onDispose { log += "dispose" }
        }
        val lifecycle = remember(entry) {
            entry.retain(EffectsLifecycleKey) {
                SessionNavLifecycle(
                    chatId = core.chatId,
                    controller = controller,
                    transcript = transcript,
                    edits = QueuedEditCanceller { log += "cancelIfEditing" },
                    drafts = DraftSaver { log += "save" },
                )
            }
        }
        CompositionLocalProvider(
            LocalSessionNavAlpha provides nav,
            LocalAppRouter provides router,
        ) {
            SessionNavEffects(lifecycle, entry)
            ProvideTransitionProgress(stack, entry) {
                // .current is a @Composable read: hoisted out of the SideEffect lambda.
                val progress = LocalTransitionProgress.current
                SideEffect { progressRef = progress }
                SessionNavFakeChromePage(nav, Modifier.fillMaxSize())
            }
        }
    }

    private fun mount(stack: NavStack) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NavStackHost(stack, active = true) { entry ->
                    if (entry.route is TestSessionRoute) TestSessionContent(entry)
                    else Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    // ------------------------------------------------------------ helpers

    /** The current multipliers, off the captured alpha object. */
    private fun alphas(): SessionNavAlphas =
        (navRef as? StackSessionNavAlpha)?.alphas() ?: SessionNavAlphas.Shown

    /**
     * Runs [block] on the UI thread and publishes its snapshot writes:
     * under Robolectric `runOnUiThread` runs inline, and a write made
     * outside composition reaches the Recomposer only through
     * `Snapshot.sendApplyNotifications()` (the frozen clock never idles
     * the looper).
     */
    private fun writeOnUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    /** Pushes the session and lets the push settle (autoAdvance on). */
    private fun pushSession(stack: NavStack): NavEntry {
        compose.runOnUiThread { stack.push(TestSessionRoute()) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil { stack.transition == null }
        compose.waitForIdle()
        return stack.entries.last()
    }

    /** Starts a predictive back gesture and drags it to [progress]. */
    private fun startGesture(stack: NavStack, progress: Float) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        val height = compose.activity.window.decorView.height.toFloat()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(1f, height / 2f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(40f, height / 2f, progress, BackEventCompat.EDGE_LEFT),
            )
        }
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: -1f) >= progress }
        // Let the host's driver deliver the will* pair — the leave.
        compose.mainClock.advanceTimeUntil { log.contains("cancelIfEditing") }
    }

    // -------------------------------------------------------- the push

    /**
     * Push: α 0 on the first frame, the ticket's spring curve at 100 and
     * 250 ms, 1 when idle, jump 1 throughout — and equal (± 0.001) to
     * ticket 30's `presence` at every sample. The log shows one
     * `sessionDidAppear` and no reattach from this ticket.
     */
    @Test
    fun pushAnimatesTheStackInAndMatchesTicket30Presence() {
        val stack = NavStack(DemoRoute("Root"))
        compose.mainClock.autoAdvance = false
        mount(stack)
        compose.mainClock.advanceTimeBy(100)

        writeOnUi { stack.push(TestSessionRoute()) }
        // The recomposition frame that composes the session: the animation
        // has not produced a frame yet, so the transition still sits at
        // progress 0 — the stack is fully hidden (iOS :207-208: 0 before
        // the push runs), the jump untouched (it starts at 0 at open,
        // iOS :148).
        compose.mainClock.advanceTimeUntil { navRef != null }
        assertEquals(0f, alphas().stack, 1e-6f)
        assertEquals(1f, alphas().jump, 1e-6f)

        // The spring's curve, from the first animated frame (play 16 ms).
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 0f) > 0f }
        // Exact advances: plain advanceTimeBy rounds up to whole 16 ms frames
        // (84 -> 96, sampling at 112 ms of play instead of ~100).
        compose.mainClock.advanceTimeBy(84, ignoreFrameDuration = true) // play 100 ms
        assertEquals(0.551f, alphas().stack, 0.03f)
        assertEquals(1f, alphas().jump, 0.03f)
        assertEquals("presence == stack α", progressRef!!.presence, alphas().stack, 0.001f)
        compose.mainClock.advanceTimeBy(150, ignoreFrameDuration = true) // play 250 ms
        assertEquals(0.944f, alphas().stack, 0.02f)
        assertEquals("presence == stack α", progressRef!!.presence, alphas().stack, 0.001f)

        compose.mainClock.advanceTimeUntil { stack.transition == null }
        assertEquals(1f, alphas().stack, 1e-6f)
        assertEquals(1f, alphas().jump, 1e-6f)
        assertEquals(1, log.count { it == "sessionDidAppear" })
        assertTrue("no reattach from this ticket on the first appearance", core.callLog.none { it == "setViewAttached(true)" })
    }

    // ---------------------------------------------------- predictive back

    /**
     * Gesture start + progress 0.4: stack and jump both 0.6, `leave` only
     * (cancel then save), and no detach, markSeen, close or teardown —
     * the gesture may still be cancelled.
     */
    @Test
    fun predictiveBackStartCrossFadesAndLeavesWithoutTeardown() {
        val stack = NavStack(DemoRoute("Root"))
        mount(stack)
        compose.waitForIdle()
        val session = pushSession(stack)
        compose.mainClock.autoAdvance = false
        log.clear()
        val ffiBefore = core.callLog.toList()

        startGesture(stack, 0.4f)

        assertEquals(0.6f, alphas().stack, 0.001f)
        assertEquals(0.6f, alphas().jump, 0.001f)
        assertEquals(listOf("cancelIfEditing", "save"), log)
        assertEquals("no FFI during the gesture", ffiBefore, core.callLog.toList())
        assertFalse(transcript.isClosed)
        assertFalse(session.isClosed)
    }

    /**
     * Then `dispatchOnBackCancelled()`: the stack springs back — 0.743 at
     * the ticket's +100 ms, 0.928 at its +250 ms, 1 when idle — and the
     * log gains the reattach and a second `sessionDidAppear`; the entry
     * stays open, the ticker keeps ticking, and a frame relayed after the
     * cancel is applied: the Android form of "keeps streaming".
     */
    @Test
    fun predictiveBackCancelSpringsBackReattachesAndKeepsStreaming() {
        val stack = NavStack(DemoRoute("Root"))
        mount(stack)
        compose.waitForIdle()
        val session = pushSession(stack)
        compose.mainClock.autoAdvance = false
        startGesture(stack, 0.4f)
        val ticksBefore = ticks[session.id] ?: 0
        val appearedBefore = log.count { it == "sessionDidAppear" }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        // The cancel spring's first moved frame (play 16 ms), then the
        // ticket's gesture-row times: its own convention lands the decay
        // at ~68 / ~170 ms of spring play for the "+100 / +250 ms" labels.
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 1f) < 0.4f }
        // Exact (see the push test): rounded, 52 -> 64 samples at 80 ms.
        compose.mainClock.advanceTimeBy(52, ignoreFrameDuration = true)
        assertEquals(0.743f, alphas().stack, 0.03f)
        compose.mainClock.advanceTimeBy(102, ignoreFrameDuration = true)
        assertEquals(0.928f, alphas().stack, 0.03f)

        compose.mainClock.advanceTimeUntil { stack.transition == null }
        assertEquals(1f, alphas().stack, 1e-3f)
        assertEquals(1f, alphas().jump, 1e-3f)
        // The re-run will-appear: a reattach (harmless — nothing detached)
        // and a second didAppear. Still no teardown.
        assertTrue("the cancelled pop re-runs viewWillAppear → reattach", core.callLog.any { it == "setViewAttached(true)" })
        assertEquals(appearedBefore + 1, log.count { it == "sessionDidAppear" })
        assertFalse(core.callLog.any { it == "setViewAttached(false)" })
        assertFalse(core.callLog.any { it == "markSeen" })
        assertEquals(0, log.count { it == "sessionDidClose" })
        assertFalse(transcript.isClosed)
        assertFalse(session.isClosed)

        // The screen is still alive: the ticker keeps ticking…
        compose.mainClock.advanceTimeBy(200)
        assertTrue("the ticker must keep ticking after a cancelled pop", (ticks[session.id] ?: 0) > ticksBefore)
        // …and a frame relayed after the cancel is applied.
        compose.runOnUiThread { transcript.apply(EffectsFrame(2uL)) }
        assertEquals(2uL, transcript.frame?.revision())
    }

    /**
     * Gesture to 0.4 then `onBackPressed()` (commit): the stack decays to
     * 0 (0.385 at the ticket's +100 ms), and after idle the log ends with
     * `sessionDidClose`, `setViewAttached(false)` and `markSeen`, each
     * exactly once, after `onDidDisappear(removed = true)` and before the
     * content's `onDispose` — 24's order, never at gesture start.
     */
    @Test
    fun predictiveBackCommitTearsDownAfterDidDisappear() {
        val stack = NavStack(DemoRoute("Root"))
        mount(stack)
        compose.waitForIdle()
        val session = pushSession(stack)
        compose.mainClock.autoAdvance = false
        startGesture(stack, 0.4f)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 1f) > 0.4f }
        compose.mainClock.advanceTimeBy(52, ignoreFrameDuration = true) // exact, as above
        assertEquals(0.385f, alphas().stack, 0.03f)

        compose.mainClock.advanceTimeUntil { stack.transition == null }
        assertEquals(0f, alphas().stack, 1e-3f)
        assertEquals(0f, alphas().jump, 1e-3f)
        assertEquals(1, log.count { it == "sessionDidClose" })
        assertEquals(1, core.callLog.count { it == "setViewAttached(false)" })
        assertEquals(1, core.callLog.count { it == "markSeen" })
        assertTrue(transcript.isClosed)
        // The entry closes in the host's sweep — a LaunchedEffect keyed on
        // the cleared transition — and its content disposes on the
        // recomposition after that, so on the frozen clock both need
        // frames past `transition == null` (the programmatic-pop test,
        // on autoAdvance, gets them from its waitForIdle).
        compose.mainClock.advanceTimeUntil { session.isClosed && log.contains("dispose") }
        assertTrue(session.isClosed)
        assertEquals(1, stack.entries.size)
        // 24's order: the teardown is delivered by didDisappear(removed =
        // true) and only then does the sweep dispose the content.
        assertTrue(
            "sessionDidClose before the content's onDispose",
            log.indexOf("sessionDidClose") < log.indexOf("dispose"),
        )
    }

    /** Programmatic `pop()`: the same end state as the commit, no gesture. */
    @Test
    fun programmaticPopReachesTheSameEndState() {
        val stack = NavStack(DemoRoute("Root"))
        mount(stack)
        compose.waitForIdle()
        val session = pushSession(stack)
        log.clear()

        compose.runOnUiThread { stack.pop() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil { stack.transition == null }
        compose.waitForIdle()

        assertEquals(1, log.count { it == "sessionDidClose" })
        assertEquals(1, core.callLog.count { it == "setViewAttached(false)" })
        assertEquals(1, core.callLog.count { it == "markSeen" })
        assertTrue(transcript.isClosed)
        assertTrue(session.isClosed)
        assertTrue(log.indexOf("sessionDidClose") < log.indexOf("dispose"))
    }

    // ------------------------------------------------------- tab switching

    /**
     * The tab-switch simulation (the host leaves composition, then
     * returns): dispose logs `leave` only — no detach — and re-entering
     * logs the reattach and a new `sessionDidAppear`.
     */
    @Test
    fun tabSwitchDisposesWithLeaveAndReentersWithReattach() {
        val stack = NavStack(DemoRoute("Root"))
        var hostOn by mutableStateOf(true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                if (hostOn) {
                    NavStackHost(stack, active = true) { entry ->
                        if (entry.route is TestSessionRoute) TestSessionContent(entry)
                        else Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        pushSession(stack)
        log.clear()
        val ffiBefore = core.callLog.toList()
        val appearedBefore = log.count { it == "sessionDidAppear" }

        writeOnUi { hostOn = false }
        compose.waitForIdle()
        assertEquals(listOf("cancelIfEditing", "save"), log.filter { it != "dispose" })
        assertEquals("no detach on a tab switch", ffiBefore, core.callLog.toList())
        assertFalse(transcript.isClosed)

        writeOnUi { hostOn = true }
        compose.waitForIdle()
        assertTrue("re-entering composition re-attaches", core.callLog.any { it == "setViewAttached(true)" })
        assertEquals(appearedBefore + 1, log.count { it == "sessionDidAppear" })
        assertFalse(core.callLog.any { it == "setViewAttached(false)" })
        assertEquals(2, stack.entries.size)
    }

    /**
     * The configuration change (`scenario.recreate()`, the stack held
     * outside the activity): the dispose saves the draft **only** — no
     * edit cancel, no detach — and the recomposed screen re-attaches.
     */
    @Test
    fun configurationChangeSavesTheDraftOnlyAndReattachesAfter() {
        val stack = NavStack(DemoRoute("Root"))
        mount(stack)
        compose.waitForIdle()
        pushSession(stack)
        log.clear()
        val ffiBefore = core.callLog.toList()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle() // Robolectric posts the recreate to the looper
        // The old composition disposes as a configuration change: only the
        // save runs (the queued-edit lease must survive the recreation).
        assertEquals(listOf("save"), log.filter { it != "dispose" })
        assertEquals("no detach and no reattach on the destroy itself", ffiBefore, core.callLog.toList())
        assertFalse(transcript.isClosed)

        // The rule re-attaches content on the new activity only through a
        // fresh setContent (the TypeFamiliesTest pattern); the entry has
        // appeared, so entering composition re-attaches.
        mount(stack)
        compose.waitForIdle()
        assertTrue(core.callLog.any { it == "setViewAttached(true)" })
        assertFalse(core.callLog.any { it == "setViewAttached(false)" })
    }

    // ---------------------------------------------------- unanimated push

    /** An unanimated push (the handoff): the stack α is 1 on every frame. */
    @Test
    fun unanimatedPushShowsNoAlphaDip() {
        val stack = NavStack(DemoRoute("Root"))
        compose.mainClock.autoAdvance = false
        mount(stack)
        compose.mainClock.advanceTimeBy(100)

        writeOnUi { stack.push(TestSessionRoute(), animated = false) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphas().stack, 1e-6f)
        compose.mainClock.advanceTimeBy(64)
        assertEquals(1f, alphas().stack, 1e-6f)
        compose.mainClock.advanceTimeUntil { stack.transition == null }
        assertEquals(1f, alphas().stack, 1e-6f)
        assertEquals(1f, alphas().jump, 1e-6f)
    }

    // ------------------------------------------------------ reduced motion

    /**
     * Reduced motion: the multipliers stay 1/1 during push and pop while
     * ticket 24's 150 ms layer cross-fade runs (the transition exists
     * mid-way).
     */
    @Test
    fun reducedMotionKeepsTheMultipliersAtOne() {
        ReduceMotion.debugOverride = true
        val stack = NavStack(DemoRoute("Root"))
        compose.mainClock.autoAdvance = false
        mount(stack)
        compose.mainClock.advanceTimeBy(100)

        writeOnUi { stack.push(TestSessionRoute()) }
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 0f) > 0f }
        compose.mainClock.advanceTimeBy(64)
        assertNotNull("the reduced cross-fade is mid-way", stack.transition)
        assertEquals(1f, alphas().stack, 1e-6f)
        assertEquals(1f, alphas().jump, 1e-6f)

        compose.mainClock.advanceTimeUntil { stack.transition == null }
        writeOnUi { stack.pop() }
        compose.mainClock.advanceTimeUntil { (stack.transition?.progress ?: 0f) > 0f }
        assertEquals(1f, alphas().stack, 1e-6f)
        assertEquals(1f, alphas().jump, 1e-6f)
        compose.mainClock.advanceTimeUntil { stack.transition == null }
    }
}
