package sh.roboco.android.design

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The refresh timing window (android-ui-parity #48) — the port of
 * `ios:App/AppModel.swift:292-318`'s `refresh(minimum:maximum:completion:)`:
 * [holdRefresh] returns at `max(start + 0.6 s, min(done, start + 3 s))`,
 * whatever `refresh()` does (finish fast, finish late, throw, cancel
 * itself), and [PullToRefreshState] drives the spinner through
 * Refreshing → Closing → Idle on the same window.
 *
 * Plain JVM + `kotlinx-coroutines-test` virtual time — `holdRefresh` uses
 * no wall clock, so the tests share production code exactly. The state's
 * scope carries a [TestMonotonicFrameClock] so the settle's `animate`
 * finds a frame clock (plain `TestScope` has none — a plain-JVM
 * `rememberCoroutineScope` equivalent would throw).
 *
 * `android.util.Log` is swapped out via [pullRefreshLogW] — it is not
 * mocked outside Robolectric (the same seam as `FoldState.foldLogW`).
 */
@OptIn(ExperimentalTestApi::class)
class PullToRefreshTimingTest {

    private var savedLog: ((String, Throwable?) -> Unit)? = null

    @Before
    fun quietLog() {
        savedLog = pullRefreshLogW
        pullRefreshLogW = { _, _ -> }
    }

    @After
    fun restoreLog() {
        pullRefreshLogW = savedLog!!
    }

    // ------------------------------------------------------------- holdRefresh
    // The ticket's timing table, measured with the scheduler's clock.

    @Test
    fun fastRefreshHoldsTheFloor() = runTest {
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = { delay(100) })
        assertEquals(600L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun instantRefreshHoldsTheFloor() = runTest {
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = {})
        assertEquals(600L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun noClientReturnsAtTheFloor() = runTest {
        // The null-client coreRefresher returns at once — same shape.
        var called = false
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = { called = true })
        assertTrue(called)
        assertEquals(600L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun longRefreshRunsToCompletion() = runTest {
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = { delay(2000) })
        assertEquals(2000L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun neverReturningRefreshIsCappedAtThreeSeconds() = runTest {
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = { delay(10_000) })
        assertEquals(3000L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun failureStillHoldsTheFloor() = runTest {
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = {
            delay(50)
            throw IllegalStateException("probe failed")
        })
        assertEquals(600L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun selfCancellationStillHoldsTheFloor() = runTest {
        // The scope itself is NOT cancelled here — the fake throws a
        // CancellationException of its own, which holdRefresh swallows.
        val startedAt = testScheduler.currentTime
        holdRefresh(refresh = {
            delay(50)
            throw CancellationException("core cancelled itself")
        })
        assertEquals(600L, testScheduler.currentTime - startedAt)
    }

    // --------------------------------------------------------- the state machine
    // The ticket's state test: construct the state directly over a scope
    // with a virtual frame clock (density 1: threshold 80, hold 60) — the
    // scope is `this + TestMonotonicFrameClock(this)`.

    /** The state's scope: the runTest scope plus the virtual frame clock. */
    private fun TestScope.pullScope(): CoroutineScope =
        CoroutineScope(coroutineContext + TestMonotonicFrameClock(this))

    @Test
    fun spinnerHoldsAtLeastTheFloorThenCloses() = runTest {
        val scope = pullScope()
        var calls = 0
        val state = PullToRefreshState(
            scope,
            onRefresh = { calls++; delay(100) },
            thresholdPx = 80f,
            holdPx = 60f,
        )
        state.startRefresh()
        advanceTimeBy(599)
        assertEquals(PullPhase.Refreshing, state.phase)
        advanceTimeBy(601)
        assertTrue(
            "phase after the window: ${state.phase}",
            state.phase == PullPhase.Closing || state.phase == PullPhase.Idle,
        )
        advanceUntilIdle()
        assertEquals(PullPhase.Idle, state.phase)
        assertEquals(0f, state.offsetPx, 0.01f)
        assertEquals(1, calls)
    }

    @Test
    fun retriggerWhileRefreshingIsIgnored() = runTest {
        val scope = pullScope()
        var calls = 0
        val state = PullToRefreshState(
            scope,
            onRefresh = { calls++; delay(100) },
            thresholdPx = 80f,
            holdPx = 60f,
        )
        state.startRefresh()
        advanceTimeBy(100) // the fake is done; the 0.6 s floor still holds the job alive
        state.startRefresh()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(PullPhase.Idle, state.phase)
    }

    @Test
    fun longFakeSpinsPastTheFloorAndEndsAtItsOwnTime() = runTest {
        val scope = pullScope()
        val state = PullToRefreshState(
            scope,
            onRefresh = { delay(2000) },
            thresholdPx = 80f,
            holdPx = 60f,
        )
        state.startRefresh()
        advanceTimeBy(1999)
        assertEquals(PullPhase.Refreshing, state.phase)
        advanceTimeBy(2)
        assertFalse(state.phase == PullPhase.Refreshing)
        advanceUntilIdle()
        assertEquals(PullPhase.Idle, state.phase)
        assertEquals(0f, state.offsetPx, 0.01f)
    }

    @Test
    fun neverReturningFakeEndsAtTheCap() = runTest {
        val scope = pullScope()
        val state = PullToRefreshState(
            scope,
            onRefresh = { delay(10_000) },
            thresholdPx = 80f,
            holdPx = 60f,
        )
        state.startRefresh()
        advanceTimeBy(2999)
        assertEquals(PullPhase.Refreshing, state.phase)
        advanceUntilIdle()
        assertEquals(PullPhase.Idle, state.phase)
        assertEquals(0f, state.offsetPx, 0.01f)
    }

    @Test
    fun setOffsetForTestPinsTheOffset() = runTest {
        val scope = pullScope()
        val state = PullToRefreshState(
            scope,
            onRefresh = {},
            thresholdPx = 80f,
            holdPx = 60f,
        )
        state.setOffsetForTest(40f)
        assertEquals(40f, state.offsetPx, 0.01f)
        assertEquals(PullPhase.Idle, state.phase) // the phase stays as is
        advanceUntilIdle()
        assertEquals(40f, state.offsetPx, 0.01f) // pinned: no settle to fight with
    }
}
