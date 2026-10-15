package sh.roboco.android.sessions

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.LocalRouterHooks
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRoute
import sh.roboco.android.shell.SessionRouteFactory

/**
 * The list's tap-to-open path (android-ui-parity #41) over fakes — the
 * port of `SessionListController.openSession`: [rememberSessionListOpener]
 * pushes the factory's route on the list's **own** stack (no pop to root,
 * no tab change), the factory runs off the main thread through ticket 32's
 * `openSessionRoute`, a failed open calls `showSessionOpenError()` and
 * pushes nothing, two taps in flight keep only the second (the first is
 * discarded), and the tablet override replaces the whole path. Plus the
 * [SessionListOpenGate] unit cases on a test scheduler.
 *
 * The stack is hostless: `NavStack` ops complete synchronously without a
 * `NavStackHost` (the same property `NavStackTest` uses), so the pushes
 * are readable right away. The factory runs on `Dispatchers.IO` (real
 * threads, like `RouterTest`'s `openSessionRoute_defaultsToIo`), so the
 * assertions poll with [androidx.compose.ui.test.junit4.AndroidComposeTestRule.waitUntil].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionListOpenerTest {

    @get:Rule
    val compose = createComposeRule()

    // ----------------------------------------------------------- the fakes

    /** A route the factory builds; records its own discard. */
    private class FakeRoute(override val chatId: String) : SessionRoute, NavRoute {
        @Volatile var discarded = false

        @Composable
        override fun Content() {}

        override fun prepareArrival() {}

        override fun finishArrival() {}

        override fun discard() {
            discarded = true
        }
    }

    /** The stack's root: a plain NavRoute, never inspected. */
    private object RootRoute : NavRoute

    private class RecordingRouter : AppRouter {
        val errors = CopyOnWriteArrayList<String>()
        override fun openSession(chatId: String) = Unit
        override fun openSession(chatId: String, handoff: DraftHandoff?) = Unit
        override fun presentNewSession(prompt: String?) = Unit
        override fun showSettings() = Unit
        override fun showSearch() = Unit
        override fun showSessionOpenError() {
            errors += "error"
        }
        override fun popToFrontPage() = Unit
    }

    /**
     * The fake factory: records the call, its thread, and the built route;
     * awaits [firstGate] for the "a" chat so a superseded open can be held
     * mid-flight.
     */
    private class FakeFactory(val firstGate: CompletableDeferred<Unit> = CompletableDeferred()) : SessionRouteFactory {
        val calls = CopyOnWriteArrayList<String>()
        val routes = CopyOnWriteArrayList<FakeRoute>()
        val offMain = CopyOnWriteArrayList<Boolean>()

        override fun open(chatId: String): FakeRoute? {
            calls += chatId
            offMain += Looper.myLooper() != Looper.getMainLooper()
            if (chatId == "a") runBlocking { firstGate.await() } // open() is blocking, off main
            if (chatId == "null") return null
            return FakeRoute(chatId).also { routes += it }
        }
    }

    /** Hosts [rememberSessionListOpener] over the fakes and returns the tap handler. */
    private fun hostOpener(
        hooks: RouterHooks,
        stack: NavStack,
        router: AppRouter,
        override: SessionListOpener? = null,
    ): (String) -> Unit {
        var opener: ((String) -> Unit)? = null
        compose.setContent {
            RobocoTheme {
                val locals = listOfNotNull(
                    LocalNavStack provides stack,
                    LocalAppRouter provides router,
                    LocalRouterHooks provides hooks,
                    override?.let { LocalSessionListOpener provides it },
                )
                CompositionLocalProvider(*locals.toTypedArray()) {
                    opener = rememberSessionListOpener()
                }
            }
        }
        compose.waitForIdle()
        return requireNotNull(opener) { "the opener must compose before the test reads it" }
    }

    // ------------------------------------------------- the composable path

    @Test
    fun aSuccessfulOpenPushesOnTheOwnStackWithoutPoppingToRoot() {
        val hooks = RouterHooks()
        val factory = FakeFactory()
        hooks.sessionRoutes = factory
        val stack = NavStack(RootRoute)
        // A screen is already pushed: a pop-to-root open would reset the depth to 2.
        stack.push(RootRoute)
        val router = RecordingRouter()
        val opener = hostOpener(hooks, stack, router)

        compose.runOnUiThread { opener("chat-1") }
        compose.waitUntil(10_000) { stack.entries.size == 3 }
        val top = stack.entries.last().route
        assertTrue("the built route is pushed, not popped to root", top is FakeRoute && top.chatId == "chat-1")
        assertTrue(router.errors.isEmpty())
        // The factory ran exactly once, off the main thread.
        assertEquals(listOf("chat-1"), factory.calls)
        assertTrue(factory.offMain.single())
    }

    @Test
    fun aFailedOpenShowsTheErrorToastAndPushesNothing() {
        val hooks = RouterHooks()
        val factory = FakeFactory()
        hooks.sessionRoutes = factory
        val stack = NavStack(RootRoute)
        val router = RecordingRouter()
        val opener = hostOpener(hooks, stack, router)

        compose.runOnUiThread { opener("null") }
        compose.waitUntil(10_000) { router.errors.isNotEmpty() }
        assertEquals(1, router.errors.size)
        assertEquals("the stack stays at depth 1", 1, stack.entries.size)
        assertTrue(factory.routes.isEmpty())
    }

    @Test
    fun twoTapsInFlightKeepOnlyTheSecondRoute() {
        val hooks = RouterHooks()
        val factory = FakeFactory()
        hooks.sessionRoutes = factory
        val stack = NavStack(RootRoute)
        val router = RecordingRouter()
        val opener = hostOpener(hooks, stack, router)

        compose.runOnUiThread { opener("a") }
        // "a" is parked inside the factory on its gate, off the main thread.
        compose.waitUntil(10_000) { factory.calls.contains("a") }
        compose.runOnUiThread { opener("b") }
        compose.waitUntil(10_000) { stack.entries.size == 2 }
        val top = stack.entries.last().route as FakeRoute
        assertEquals("b", top.chatId)
        assertFalse(top.discarded)

        // Releasing the superseded tap: its route is discarded, nothing else pushed.
        factory.firstGate.complete(Unit)
        // `any`, not `first`: the IO thread may not have built "a" yet when
        // the condition is first polled, and a throwing condition ends waitUntil.
        compose.waitUntil(10_000) { factory.routes.any { it.chatId == "a" && it.discarded } }
        assertEquals("still exactly one pushed session", 2, stack.entries.size)
        assertTrue(router.errors.isEmpty())
    }

    @Test
    fun theOverrideRunsInsteadOfTheWholePath() {
        val hooks = RouterHooks()
        val factory = FakeFactory()
        hooks.sessionRoutes = factory
        val stack = NavStack(RootRoute)
        val router = RecordingRouter()
        val overrideCalls = CopyOnWriteArrayList<String>()
        val opener = hostOpener(hooks, stack, router, override = SessionListOpener { overrideCalls += it })

        compose.runOnUiThread { opener("chat-x") }
        compose.waitForIdle()
        assertEquals(listOf("chat-x"), overrideCalls)
        assertTrue("the factory is never called", factory.calls.isEmpty())
        assertEquals("nothing is pushed", 1, stack.entries.size)
        assertTrue(router.errors.isEmpty())
    }

    // ------------------------------------------------------ the gate alone

    @Test
    fun gateAppliesOnlyTheLatestGeneration() = runTest {
        val gate = SessionListOpenGate()
        val applied = mutableListOf<String?>()
        val a = FakeRoute("a")
        val b = FakeRoute("b")
        gate.open(this, "a", open = { a }) { r -> applied += r?.chatId }
        gate.open(this, "b", open = { b }) { r -> applied += r?.chatId }
        advanceUntilIdle()
        assertEquals(listOf<String?>("b"), applied)
        assertTrue("the superseded route is discarded", a.discarded)
        assertFalse(b.discarded)
    }

    @Test
    fun gateAppliesTheNullResult() = runTest {
        val gate = SessionListOpenGate()
        val applied = mutableListOf<String?>()
        gate.open(this, "gone", open = { null }) { r -> applied += r?.chatId }
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), applied)
    }

    @Test
    fun gateAppliesEveryUnsupersededOpen() = runTest {
        val gate = SessionListOpenGate()
        val applied = mutableListOf<String?>()
        val a = FakeRoute("a")
        val b = FakeRoute("b")
        gate.open(this, "a", open = { a }) { r -> applied += r?.chatId }
        advanceUntilIdle()
        gate.open(this, "b", open = { b }) { r -> applied += r?.chatId }
        advanceUntilIdle()
        assertEquals(listOf<String?>("a", "b"), applied)
        assertFalse(a.discarded)
        assertFalse(b.discarded)
    }
}
