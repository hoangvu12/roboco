package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone router (android-ui-parity #32) against a recording fake shell
 * — every case of the ticket's Verification list. Plain JUnit: no
 * Robolectric, no compose UI, `android.util.Log` never touched (the router
 * gets a no-op `log`).
 *
 * Threading is the interesting half: the factory must run on the open
 * dispatcher, never on the router's main dispatcher (the #609 bug 4 trap);
 * results apply on the main dispatcher; a later router call supersedes a
 * pending open through the generation counter; a cancelled router scope
 * discards a route the factory already built.
 */
class RouterTest {

    // ------------------------------------------------------------ the rig

    /**
     * The error path's tail (cases 3, 4, 8, 17), verbatim from
     * `MainTabController.swift:200-208`: order and toast copy.
     */
    private val errorTail = listOf(
        "dismissAlerts",
        "hideIme",
        "select(Sessions)",
        "popToRoot(false)",
        "syncAccessory",
        "toast($OPEN_ERROR_TOAST)",
    )

    private fun rig(scheduler: TestCoroutineScheduler): Rig = Rig(scheduler)

    private class Rig(scheduler: TestCoroutineScheduler) {
        val log = mutableListOf<String>()
        val shell = FakeShell(log)
        val io = CountingDispatcher(StandardTestDispatcher(scheduler))
        val main = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(main)

        /** What the fake factory returns (or throws) per chat id. */
        var openImpl: (String) -> SessionRoute? = { id -> FakeRoute(id, log) }

        val hooks = RouterHooks().apply {
            // The assertion behind "the factory runs on the open
            // dispatcher": io.inOpen is only true inside io.dispatch, so a
            // factory that ran on the router's main dispatcher fails here.
            sessionRoutes = SessionRouteFactory { id ->
                check(io.inOpen) { "the factory must run on the open dispatcher, never on the router's main" }
                openImpl(id)
            }
        }

        val animator = FakeAnimator(log)
        val presence = RecordingPresence()

        /** PhoneAppRouter over the fake shell; assign `hooks.handoffAnimator = animator` for handoff cases. */
        val router = PhoneAppRouter(
            shell = shell,
            hooks = hooks,
            scope = scope,
            openDispatcher = io,
            presence = presence,
            log = { _, _ -> }, // plain JUnit: android.util.Log is not mocked
        )
    }

    /** The ticket's counting dispatcher: true only inside a block dispatched by io. */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var inOpen = false
            private set

        override fun isDispatchNeeded(context: CoroutineContext) = true

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            inner.dispatch(context, Runnable { inOpen = true; try { block.run() } finally { inOpen = false } })
    }

    /** The recording shell: readable log strings, sheet state, the stored onCreated. */
    private class FakeShell(private val log: MutableList<String>) : PhoneShell {
        var sheet = false
        var onCreated: ((chatId: String, handoff: DraftHandoff?) -> Unit)? = null
            private set

        override fun selectTab(tab: TabId) {
            log += "select($tab)"
        }

        override val isSheetPresented: Boolean
            get() = sheet

        override fun presentNewSession(prompt: String?, onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit) {
            log += "presentNewSession($prompt)"
            sheet = true
            this.onCreated = onCreated
        }

        override fun dismissSheet(animated: Boolean) {
            log += "dismissSheet($animated)"
            sheet = false
        }

        /** The sheet stays presented (hidden) — removal is the handoff's complete(). */
        override fun hideSheetForHandoff() {
            log += "hideSheetForHandoff"
        }

        override fun dismissAlerts() {
            log += "dismissAlerts"
        }

        override fun popSessionsToRoot(animated: Boolean) {
            log += "popToRoot($animated)"
        }

        override fun pushSession(route: SessionRoute, animated: Boolean) {
            log += "push(${route.chatId},$animated)"
        }

        override fun setAccessoryVisible(visible: Boolean, animated: Boolean) {
            log += "setAccessoryVisible($visible,$animated)"
        }

        override fun syncAccessory() {
            log += "syncAccessory"
        }

        override fun hideIme() {
            log += "hideIme"
        }

        override fun toast(text: String) {
            log += "toast($text)"
        }
    }

    /** A session route that logs arrival / reveal / discard into the shell's log. */
    private class FakeRoute(override val chatId: String, private val log: MutableList<String>) : SessionRoute {
        @Composable
        override fun Content() {}

        override fun prepareArrival() {
            log += "prepareArrival($chatId)"
        }

        override fun finishArrival() {
            log += "finishArrival($chatId)"
        }

        override fun discard() {
            log += "discard($chatId)"
        }
    }

    /** Stores onFinished instead of running it — the sheet's removal stays deferred. */
    private class FakeAnimator(private val log: MutableList<String>) : HandoffAnimator {
        var onFinished: (() -> Unit)? = null
            private set

        override fun run(handoff: DraftHandoff, session: SessionRoute, onFinished: () -> Unit) {
            log += "animator.run(${session.chatId})"
            this.onFinished = onFinished
        }
    }

    /** Ticket 108's listener: what sessionDidAppear/Close reach on phone. */
    private class RecordingPresence : SessionPresenceListener {
        val appeared = mutableListOf<String>()
        val closed = mutableListOf<String>()

        override fun sessionDidAppear(chatId: String) {
            appeared += chatId
        }

        override fun sessionDidClose(chatId: String) {
            closed += chatId
        }
    }

    private fun handoff(): DraftHandoff = object : DraftHandoff {}

    // ------------------------------------------------- openSession(chatId)

    @Test
    fun openSession_fromSettings_selectsPopsPushes() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        advanceUntilIdle()
        // Fact 2 + acceptance: select first (fact 4: a failed open also
        // lands on Sessions), then the applied result in one main-thread
        // turn: alerts, pop-to-root unanimated, push animated.
        assertEquals(listOf("select(Sessions)", "dismissAlerts", "popToRoot(false)", "push(c1,true)"), r.log)
    }

    @Test
    fun openSession_withSheet_dismissesAnimatedFirst() = runTest {
        val r = rig(testScheduler)
        r.shell.sheet = true
        r.router.openSession("c1")
        advanceUntilIdle()
        // `MainTabController.swift:218`: the presented sheet is dismissed
        // animated, right after the tab selection, before the pop.
        assertEquals(
            listOf("select(Sessions)", "dismissSheet(true)", "dismissAlerts", "popToRoot(false)", "push(c1,true)"),
            r.log,
        )
        assertFalse(r.shell.sheet)
    }

    @Test
    fun openSession_unknown_showsErrorAndNoPush() = runTest {
        val r = rig(testScheduler)
        r.openImpl = { null }
        r.router.openSession("nope")
        advanceUntilIdle()
        // The failable open: no push, no animator; the error path runs
        // after the tab selection.
        assertEquals(listOf("select(Sessions)") + errorTail, r.log)
    }

    @Test
    fun openSession_factoryThrows_sameAsUnknown() = runTest {
        val r = rig(testScheduler)
        r.openImpl = { throw IllegalStateException("engine not paired") }
        r.router.openSession("c1")
        advanceUntilIdle()
        // Factory errors are logged ("open session failed") and treated
        // as the unknown-chat path.
        assertEquals(listOf("select(Sessions)") + errorTail, r.log)
    }

    @Test
    fun openSession_factoryRunsOffMain() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        advanceUntilIdle()
        // The Rig's factory checks io.inOpen (true only inside a block the
        // open dispatcher dispatched); had it run on the router's main
        // dispatcher, the check would have thrown and the log would be the
        // error path instead. The success log is the open-completed proof.
        assertEquals(listOf("select(Sessions)", "dismissAlerts", "popToRoot(false)", "push(c1,true)"), r.log)
    }

    // --------------------------------------------- openSession(id, handoff)

    @Test
    fun handoffNull_isPlainOpen() = runTest {
        val r = rig(testScheduler)
        r.hooks.handoffAnimator = r.animator
        r.router.openSession("c1", null)
        advanceUntilIdle()
        // Fact 5: a nil handoff behaves exactly like openSession(id).
        assertEquals(listOf("select(Sessions)", "dismissAlerts", "popToRoot(false)", "push(c1,true)"), r.log)
        assertNull(r.animator.onFinished)
    }

    @Test
    fun handoff_orderAndDeferredSheetRemoval() = runTest {
        val r = rig(testScheduler)
        r.hooks.handoffAnimator = r.animator
        r.shell.sheet = true
        r.router.openSession("c1", handoff())
        advanceUntilIdle()
        // Fact 6 / acceptance: one main-thread turn, in this order, and
        // the animator runs last.
        assertEquals(
            listOf(
                "select(Sessions)",
                "prepareArrival(c1)",
                "popToRoot(false)",
                "push(c1,false)",
                "setAccessoryVisible(false,false)",
                "hideIme",
                "hideSheetForHandoff",
                "animator.run(c1)",
            ),
            r.log,
        )
        // The hidden sheet stays presented (composed, inert) until the
        // motion's completion — never dismissed mid-animation.
        assertTrue(r.shell.sheet)
        assertNull(r.log.lastOrNull { it.startsWith("dismissSheet") })

        // onFinished dismisses it without animation — once, even when the
        // animator calls its completion twice.
        r.animator.onFinished?.invoke()
        r.animator.onFinished?.invoke()
        assertEquals(listOf("dismissSheet(false)"), r.log.drop(8))
        assertFalse(r.shell.sheet)
    }

    @Test
    fun handoff_failedOpen_dismissesSheetInstantlyAndToasts() = runTest {
        val r = rig(testScheduler)
        r.hooks.handoffAnimator = r.animator
        r.openImpl = { null }
        r.shell.sheet = true
        r.router.openSession("c1", handoff())
        advanceUntilIdle()
        // The new-session sheet is dismissed instantly (showSessionOpenError
        // runs with the sheet up), no prepareArrival, no animator.
        assertEquals(listOf("select(Sessions)", "dismissSheet(false)") + errorTail, r.log)
        assertFalse(r.shell.sheet)
        assertNull(r.animator.onFinished)
    }

    @Test
    fun handoff_defaultInstantAnimator_revealsAndRemovesSheet() = runTest {
        val r = rig(testScheduler)
        // RouterHooks() default: the Instant animator — finishArrival then
        // the completion removes the hidden sheet.
        r.shell.sheet = true
        r.router.openSession("c1", handoff())
        advanceUntilIdle()
        assertEquals(
            listOf(
                "select(Sessions)",
                "prepareArrival(c1)",
                "popToRoot(false)",
                "push(c1,false)",
                "setAccessoryVisible(false,false)",
                "hideIme",
                "hideSheetForHandoff",
                "finishArrival(c1)",
                "dismissSheet(false)",
            ),
            r.log,
        )
        assertFalse(r.shell.sheet)
    }

    // ------------------------------------------------------- supersede

    @Test
    fun latestOpenWins_staleRouteDiscarded() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        r.router.openSession("c2")
        advanceUntilIdle()
        // The latest intent wins: the first open's route is discarded
        // (never pushed, never toasted) and the second applies.
        assertEquals(
            listOf("select(Sessions)", "select(Sessions)", "discard(c1)", "dismissAlerts", "popToRoot(false)", "push(c2,true)"),
            r.log,
        )
    }

    @Test
    fun showSettingsSupersedesPendingOpen() = runTest {
        val r = rig(testScheduler)
        r.router.openSession("c1")
        r.router.showSettings()
        advanceUntilIdle()
        assertEquals(listOf("select(Sessions)", "select(Settings)", "discard(c1)"), r.log)
    }

    // ------------------------------------------------ presentNewSession

    @Test
    fun presentNewSession_presentsAndRoutesOnCreated() = runTest {
        val r = rig(testScheduler)
        r.hooks.handoffAnimator = r.animator
        r.router.presentNewSession("fix the build")
        advanceUntilIdle()
        // Only the present — no open yet (the sheet creates the chat).
        assertEquals(listOf("presentNewSession(fix the build)"), r.log)
        assertTrue(r.shell.sheet)

        // The sheet's onCreated routes through the router: the handoff
        // path (the sheet is up, so it is hidden, not dismissed).
        r.shell.onCreated?.invoke("c1", handoff())
        advanceUntilIdle()
        assertEquals(
            listOf(
                "presentNewSession(fix the build)",
                "select(Sessions)",
                "prepareArrival(c1)",
                "popToRoot(false)",
                "push(c1,false)",
                "setAccessoryVisible(false,false)",
                "hideIme",
                "hideSheetForHandoff",
                "animator.run(c1)",
            ),
            r.log,
        )
        r.animator.onFinished?.invoke()
        assertEquals(listOf("dismissSheet(false)"), r.log.drop(9))
        assertFalse(r.shell.sheet)
    }

    @Test
    fun presentNewSession_ignoredWhileSheetUp() = runTest {
        val r = rig(testScheduler)
        r.shell.sheet = true
        r.router.presentNewSession("hi")
        advanceUntilIdle()
        // UIKit refuses a second presentation — the no-op.
        assertTrue(r.log.isEmpty())
    }

    // --------------------------------------- showSettings/showSearch/pop

    @Test
    fun showSettings_showSearch_selectTabsOnly() = runTest {
        val r = rig(testScheduler)
        r.router.showSettings()
        r.router.showSearch()
        advanceUntilIdle()
        assertEquals(listOf("select(Settings)", "select(Search)"), r.log)
    }

    @Test
    fun popToFrontPage_popsOnly() = runTest {
        val r = rig(testScheduler)
        r.router.popToFrontPage()
        advanceUntilIdle()
        // Fact 7: pops the Sessions stack to root without animation and
        // does NOT select the tab.
        assertEquals(listOf("popToRoot(false)"), r.log)
    }

    // -------------------------------------------------------- presence

    @Test
    fun presence_forwardedAndNoShellCalls() = runTest {
        val r = rig(testScheduler)
        r.router.sessionDidAppear("c1")
        r.router.sessionDidClose("c2")
        advanceUntilIdle()
        // Phone no-ops: nothing reaches the shell, only the listener.
        assertTrue(r.log.isEmpty())
        assertEquals(listOf("c1"), r.presence.appeared)
        assertEquals(listOf("c2"), r.presence.closed)
    }

    // ------------------------------------------------- open-error order

    @Test
    fun showSessionOpenError_order() = runTest {
        val r = rig(testScheduler)
        r.shell.sheet = true
        r.router.showSessionOpenError()
        advanceUntilIdle()
        // Fact 8, verbatim: instant sheet dismissal, alerts, IME down,
        // Sessions, pop to root, accessory sync, then the toast.
        assertEquals(listOf("dismissSheet(false)") + errorTail, r.log)
        assertFalse(r.shell.sheet)
    }

    // ------------------------------------------- scope cancelled mid-open

    @Test
    fun scopeCancelledDuringOpen_routeDiscarded() = runTest {
        val r = rig(testScheduler)
        val job = Job()
        val scope = CoroutineScope(r.main + job)
        val router = PhoneAppRouter(
            shell = r.shell,
            hooks = r.hooks,
            scope = scope,
            openDispatcher = r.io,
            presence = r.presence,
            log = { _, _ -> },
        )
        r.openImpl = { id ->
            // Cancel the router's scope while the factory is running; the
            // route is still built and returned.
            job.cancel()
            FakeRoute(id, r.log)
        }
        router.openSession("c1")
        advanceUntilIdle()
        // withContext throws CancellationException on resume;
        // openSessionRoute discards the built route on the caller's
        // (main) dispatcher first: no push, no toast.
        assertEquals(listOf("select(Sessions)", "discard(c1)"), r.log)
    }

    // --------------------------------------------------------- defaults

    @Test
    fun routerDefaults_openDispatcherIsIo() = runBlocking {
        // Real dispatchers (not the test scheduler): runBlocking + polling
        // keeps it deterministic. The factory thread must be a
        // Dispatchers.IO worker, never the caller's thread (#609 bug 4).
        // The log and thread capture are thread-safe: they are written on
        // the router's workers and polled here.
        val log = CopyOnWriteArrayList<String>()
        val shell = FakeShell(log)
        val factoryThread = AtomicReference<Thread?>(null)
        val hooks = RouterHooks()
        hooks.sessionRoutes = SessionRouteFactory { id ->
            factoryThread.set(Thread.currentThread())
            FakeRoute(id, log)
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val router = PhoneAppRouter(shell, hooks, scope, log = { _, _ -> })
        router.openSession("c1")
        withTimeout(5_000) { while (log.none { it.startsWith("push(") }) delay(10) }
        scope.cancel()
        val thread = factoryThread.get()
        assertNotNull("the factory must have run", thread)
        assertNotSame(Thread.currentThread(), thread)
        assertTrue(thread!!.name.startsWith("DefaultDispatcher"))
    }

    @Test
    fun openSessionRoute_defaultsToIo() = runBlocking {
        val factoryThread = AtomicReference<Thread?>(null)
        val hooks = RouterHooks()
        hooks.sessionRoutes = SessionRouteFactory { factoryThread.set(Thread.currentThread()); null }
        assertNull(hooks.openSessionRoute("c1"))
        val thread = factoryThread.get()
        assertNotNull("the factory must have run", thread)
        assertNotSame(Thread.currentThread(), thread)
        assertTrue(thread!!.name.startsWith("DefaultDispatcher"))
    }
}
