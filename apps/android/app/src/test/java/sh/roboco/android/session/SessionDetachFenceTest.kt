package sh.roboco.android.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detach fence (android-ui-parity #108) — the ticket's Verification
 * list. Plain JUnit + `kotlinx-coroutines-test`: the timeout runs on
 * virtual time, the joins on the test dispatcher. No Robolectric, no
 * `android.*`.
 */
class SessionDetachFenceTest {

    @Test
    fun awaitWithNothingTrackedReturnsTrueAtOnce() = runTest {
        val fence = SessionDetachFence()
        // No pending close for the chat: nothing to join, no timeout.
        assertTrue(fence.await("c1"))
    }

    @Test
    fun awaitSuspendsUntilTheTrackedJobCompletes() = runTest {
        val fence = SessionDetachFence()
        val job = Job()
        fence.track("c1", job)
        var result: Boolean? = null
        launch { result = fence.await("c1") }
        runCurrent()
        assertNull("still joining the pending close", result)
        job.complete()
        advanceUntilIdle()
        assertTrue("joined in time", result!!)
    }

    @Test
    fun twoJobsForOneChatAreBothJoined() = runTest {
        val fence = SessionDetachFence()
        val first = Job()
        val second = Job()
        fence.track("c1", first)
        fence.track("c1", second)
        var result: Boolean? = null
        launch { result = fence.await("c1") }
        runCurrent()
        assertNull(result)
        first.complete()
        runCurrent()
        assertNull("the second close is still pending", result)
        second.complete()
        advanceUntilIdle()
        assertTrue(result!!)
    }

    @Test
    fun aCancelledJobDoesNotThrow() = runTest {
        val fence = SessionDetachFence()
        val job = Job()
        job.cancel()
        fence.track("c1", job)
        // join() never throws for a cancelled job: a failed close never
        // blocks the carry-over.
        assertTrue(fence.await("c1"))
    }

    @Test
    fun aJobThatNeverCompletesTimesOut() = runTest {
        val fence = SessionDetachFence(timeoutMs = 1000)
        fence.track("c1", Job())
        var result: Boolean? = null
        launch { result = fence.await("c1") }
        runCurrent()
        assertNull(result)
        advanceTimeBy(999)
        runCurrent()
        assertNull("the timeout has not fired yet", result)
        advanceTimeBy(1)
        advanceUntilIdle()
        assertFalse("timed out — the caller proceeds anyway", result!!)
    }

    @Test
    fun completedJobsAreRemoved() = runTest {
        val fence = SessionDetachFence()
        val job = Job()
        fence.track("c1", job)
        job.complete()
        // The completion handler removed it (synchronously for an
        // already-complete job): a later await returns true at once.
        assertTrue(fence.await("c1"))
    }
}
