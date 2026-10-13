package sh.roboco.android.composer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueueActions] — android-ui-parity #82's routing, pinned on the plain
 * JVM with a fake port (the port of `CoreSessionSource.queueAction`,
 * `ios:Session/CoreSessionSource.swift:137-147`):
 *
 * - SendNow / Remove / MoveUp / MoveDown reach the port with the right
 *   ids and deltas; Edit never touches the port;
 * - nothing runs before the test dispatcher advances (fire-and-forget on
 *   the serial lane);
 * - three rapid calls reach the port **in call order**;
 * - a throwing port is logged, never crashes the scope, and the next
 *   call still runs (iOS `try?`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueActionsTest {

    /** Records every call; can be made to fail the next call. */
    private class FakePort : QueuePort {
        val calls = mutableListOf<String>()
        var failNext = false

        private suspend fun record(call: String) {
            if (failNext) {
                failNext = false
                throw RuntimeException("port boom")
            }
            calls += call
        }

        override suspend fun deliverQueuedNow(id: String): Boolean {
            record("deliverNow($id)")
            return true
        }

        override suspend fun removeQueued(id: String): Boolean {
            record("remove($id)")
            return true
        }

        override suspend fun moveQueuedBy(id: String, delta: Int): Boolean {
            record("moveBy($id,$delta)")
            return true
        }
    }

    // ---------------------------------------------------------- routing

    @Test
    fun sendNowReachesThePort() = runTest {
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q1", QueueAction.SendNow)
        assertTrue("nothing runs before the dispatcher advances", port.calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("deliverNow(q1)"), port.calls)
    }

    @Test
    fun removeReachesThePort() = runTest {
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q2", QueueAction.Remove)
        advanceUntilIdle()
        assertEquals(listOf("remove(q2)"), port.calls)
    }

    @Test
    fun moveUpMovesByMinusOne() = runTest {
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q3", QueueAction.MoveUp)
        advanceUntilIdle()
        assertEquals(listOf("moveBy(q3,-1)"), port.calls)
    }

    @Test
    fun moveDownMovesByPlusOne() = runTest {
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q3", QueueAction.MoveDown)
        advanceUntilIdle()
        assertEquals(listOf("moveBy(q3,1)"), port.calls)
    }

    @Test
    fun editNeverReachesThePortAndLogs() = runTest {
        val port = FakePort()
        val logged = mutableListOf<String>()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { message, _ -> logged += message }
        actions.perform("q1", QueueAction.Edit)
        advanceUntilIdle()
        assertTrue(port.calls.isEmpty())
        assertEquals(1, logged.size)
    }

    // ------------------------------------------------------ ordering

    @Test
    fun threeRapidCallsReachThePortInCallOrder() = runTest {
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q1", QueueAction.MoveUp)
        actions.perform("q2", QueueAction.SendNow)
        actions.perform("q1", QueueAction.Remove)
        assertTrue(port.calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("moveBy(q1,-1)", "deliverNow(q2)", "remove(q1)"), port.calls)
    }

    @Test
    fun rapidCallsAlsoRunWhenOnlyCurrentIsAdvanced() = runTest {
        // A single runCurrent() (one drain of the queue) executes what was
        // launched before it, in order — the serial lane.
        val port = FakePort()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { _, _ -> }
        actions.perform("q1", QueueAction.MoveDown)
        actions.perform("q2", QueueAction.Remove)
        runCurrent()
        assertEquals(listOf("moveBy(q1,1)", "remove(q2)"), port.calls)
    }

    // ------------------------------------------------------ failures

    @Test
    fun aThrowingPortIsLoggedAndDoesNotCrashTheNextCall() = runTest {
        val port = FakePort()
        val logged = mutableListOf<String>()
        val actions = QueueActions(port, this, StandardTestDispatcher(testScheduler)) { message, error ->
            logged += "$message: ${error.message}"
        }

        port.failNext = true
        actions.perform("q1", QueueAction.SendNow)
        advanceUntilIdle()
        assertTrue("the failing call never reached the port log", port.calls.isEmpty())
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("queue SendNow failed for q1"))
        assertTrue(logged.single().contains("port boom"))

        // The scope survived: the next call runs normally.
        actions.perform("q2", QueueAction.Remove)
        advanceUntilIdle()
        assertEquals(listOf("remove(q2)"), port.calls)
    }
}
