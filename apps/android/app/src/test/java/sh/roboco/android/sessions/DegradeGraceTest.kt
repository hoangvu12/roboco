package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DegradeGrace] (android-ui-parity #50) — the Kotlin port of upstream's
 * `ConnectivityTracker::graced` (`upstream/main:crates/client/src/
 * connectivity.rs:14,70-84,110`): show-slow, hide-fast, independent
 * timers per source, and the `retain` that drops gone sources.
 *
 * Plain JUnit: the tracker is pure Kotlin.
 */
class DegradeGraceTest {

    // ------------------------------------------------------------- graced

    @Test
    fun aSourceNeedsTheFullGraceBeforeItReportsDegraded() {
        val g = DegradeGrace(graceMs = 4000)
        assertFalse(g.graced("a", true, 0))
        assertFalse(g.graced("a", true, 3999))
        assertTrue(g.graced("a", true, 4000))
    }

    @Test
    fun recoveryReportsInstantlyAndRestartsTheTimer() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("a", true, 0)
        // Hide-fast: the first healthy sample reports false and drops the
        // timer, so a later outage starts counting from zero again.
        assertFalse(g.graced("a", false, 4001))
        assertFalse(g.graced("a", true, 4002))
        assertFalse(g.graced("a", true, 7999))
        assertTrue(g.graced("a", true, 8002))
    }

    @Test
    fun healthySamplesNeverStartATimer() {
        val g = DegradeGrace(graceMs = 4000)
        assertFalse(g.graced("a", false, 0))
        assertFalse(g.graced("a", false, 100_000))
        // No timer was ever started: a first degraded sample at 100_001
        // needs its own full 4000 ms.
        assertFalse(g.graced("a", true, 100_001))
        assertTrue(g.graced("a", true, 104_001))
    }

    @Test
    fun sourcesKeepIndependentTimers() {
        val g = DegradeGrace(graceMs = 4000)
        assertFalse(g.graced("os", true, 0))
        assertFalse(g.graced("engine:a", true, 1500))
        // The OS key is 1500 ms ahead in its window; the engine key has
        // not been touched.
        assertTrue(g.graced("os", true, 4000))
        assertFalse(g.graced("engine:a", true, 4000))
        assertTrue(g.graced("engine:a", true, 5500))
        // And recovering one key does not disturb the other.
        assertFalse(g.graced("os", false, 6000))
        assertTrue(g.graced("engine:a", true, 6000))
    }

    // --------------------------------------------------------- nextDeadline

    @Test
    fun nextDeadlineAfterTheFirstSampleIs4000() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("a", true, 0)
        assertEquals(4000L, g.nextDeadline(0))
    }

    @Test
    fun nextDeadlineIsTheEarliestWindowStillOpen() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("a", true, 0)
        g.graced("b", true, 1500)
        assertEquals(4000L, g.nextDeadline(2000))
        // At 4000 "a" is graced (its deadline is no longer in the future)
        // and "b" still is: the earliest open window.
        assertEquals(5500L, g.nextDeadline(4000))
        assertNull(g.nextDeadline(5500))
    }

    @Test
    fun anEmptyTrackerHasNoDeadline() {
        assertNull(DegradeGrace().nextDeadline(0))
    }

    @Test
    fun aRecoveryDropsTheDeadline() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("a", true, 0)
        assertEquals(4000L, g.nextDeadline(0))
        g.graced("a", false, 10)
        assertNull(g.nextDeadline(10))
    }

    // ------------------------------------------------------------- retain

    @Test
    fun retainDropsEveryKeyNotInTheLiveSet() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("os", true, 0)
        g.graced("engine:a", true, 0)
        g.graced("engine:b", true, 0)
        g.retain(setOf("os", "engine:a"))
        // A dropped key restarts from zero when it comes back, exactly
        // like `compute`'s `degraded_since.retain` (`connectivity.rs:110`).
        assertFalse(g.graced("engine:b", true, 5000))
        assertTrue(g.graced("engine:a", true, 5000))
        assertEquals(9000L, g.nextDeadline(5000))
    }

    @Test
    fun retainWithAnEmptySetDropsEveryKey() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("os", true, 0)
        g.graced("engine:a", true, 0)
        g.retain(emptySet())
        assertNull(g.nextDeadline(0))
        assertFalse(g.graced("os", true, 5000))
    }

    // -------------------------------------------------------------- clear

    @Test
    fun clearForgetsEveryTimer() {
        val g = DegradeGrace(graceMs = 4000)
        g.graced("a", true, 0)
        assertTrue(g.graced("a", true, 4000))
        g.clear()
        // Checked right after the clear: the graced call below starts a
        // fresh timer at 4001 (deadline 8001), as a first sighting should.
        assertNull(g.nextDeadline(4001))
        assertFalse(g.graced("a", true, 4001))
    }

    @Test
    fun theDefaultGraceIsUpstreamsDegradeGraceMs() {
        assertEquals(4_000L, DegradeGrace.GRACE_MS)
        assertEquals(4_000L, DegradeGrace().graceMs)
    }
}
