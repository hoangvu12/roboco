package sh.roboco.android.composer

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.CoreException

/**
 * [QuestionActions] — android-ui-parity #91's routing, pinned on the
 * plain JVM with a fake [QuestionPort] (the port of
 * `CoreSessionSource.answer`, `ios:Session/CoreSessionSource.swift:133-135`):
 *
 * - the port is called on the injected `io` lane, and nothing runs
 *   before that lane advances (fire-and-forget, never main);
 * - a thrown [CoreException] is logged once and [respond]'s `onFailure`
 *   runs once **on main** (the failure hops through the main
 *   dispatcher), and no toast exists anywhere (decision COMPOSER-D2);
 * - a [CancellationException] is rethrown and never reported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionActionsTest {

    /** Records every call; can be made to fail the next call. */
    private class FakePort : QuestionPort {
        val calls = mutableListOf<Pair<String, List<QuestionAnswer>>>()
        var failWith: Throwable? = null
        var seenLane: CoroutineDispatcher? = null

        override suspend fun respondInput(requestId: String, answers: List<QuestionAnswer>) {
            seenLane = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            calls += requestId to answers
            failWith?.let { throw it }
        }
    }

    /**
     * Counts dispatches and runs the block inline, so "the failure
     * callback ran on main" is observable as at least one dispatch.
     */
    private class RecordingMain : CoroutineDispatcher() {
        var dispatched = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            block.run()
        }
    }

    private val answers = listOf(QuestionAnswer("a", listOf("Yes, both platforms")))

    @Test
    fun thePortRunsOnTheIoLaneWithTheRightPayload() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val port = FakePort()
        val actions = QuestionActions(
            port = port,
            appScope = this,
            io = io,
            main = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        actions.respond("r1", answers) {}
        assertTrue("nothing runs before the io lane advances", port.calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("r1" to answers), port.calls)
        assertTrue("the port saw the io lane, not the caller", port.seenLane === io)
    }

    @Test
    fun aThrowingPortIsLoggedOnceAndFailsOnceOnMain() = runTest {
        val port = FakePort()
        port.failWith = CoreException.HostUnavailable("Engine is offline; reconnecting")
        val logged = mutableListOf<Pair<String, Throwable>>()
        val main = RecordingMain()
        val actions = QuestionActions(
            port = port,
            appScope = this,
            io = StandardTestDispatcher(testScheduler),
            main = main,
            logW = { message, error -> logged += message to error },
        )
        var failures = 0
        actions.respond("r1", answers) { failures++ }
        advanceUntilIdle()
        assertEquals("onFailure ran once", 1, failures)
        assertTrue("the failure hopped through main", main.dispatched > 0)
        assertEquals("logged once", 1, logged.size)
        assertTrue(logged.single().first.contains("respondInput failed for r1"))
        assertEquals(port.failWith, logged.single().second)
    }

    @Test
    fun aCancellationIsRethrownAndNeverReported() = runTest {
        val port = FakePort()
        port.failWith = CancellationException("the scope went away")
        val logged = mutableListOf<Pair<String, Throwable>>()
        var failures = 0
        val actions = QuestionActions(
            port = port,
            appScope = this,
            io = StandardTestDispatcher(testScheduler),
            main = RecordingMain(),
            logW = { message, error -> logged += message to error },
        )
        actions.respond("r1", answers) { failures++ }
        advanceUntilIdle()
        assertEquals("the port was called (then cancelled)", 1, port.calls.size)
        assertEquals("onFailure never runs for a cancellation", 0, failures)
        assertTrue("nothing logged either", logged.isEmpty())
    }

    @Test
    fun aFailedCallDoesNotBreakTheNextOne() = runTest {
        val port = FakePort()
        var failures = 0
        val actions = QuestionActions(
            port = port,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
            logW = { _, _ -> },
        )
        port.failWith = CoreException.HostUnavailable("Engine is offline; reconnecting")
        actions.respond("r1", answers) { failures++ }
        // FakePort keeps failing until cleared: only r1 is the failing call.
        port.failWith = null
        actions.respond("r2", answers) { failures++ }
        assertEquals("only the failing call reported", 1, failures)
        assertEquals("both calls reached the port", listOf("r1" to answers, "r2" to answers), port.calls)
    }
}
