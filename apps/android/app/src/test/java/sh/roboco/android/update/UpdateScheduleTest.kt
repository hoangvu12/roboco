package sh.roboco.android.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check cadence (android-next#04), mirroring the desktop updater
 * (`crates/update/src/lib.rs:50-61`): hourly after a success, the four
 * failure backoffs then the last one forever, and the desktop's 2 s
 * post-first-frame initial delay. Plain JVM.
 */
class UpdateScheduleTest {

    @Test
    fun successfulChecksRepeatHourly() {
        assertEquals(60L * 60 * 1000, UpdateSchedule.CHECK_INTERVAL_MS)
    }

    @Test
    fun theLaunchCheckWaitsOutTheFirstFrame() {
        assertEquals(2_000L, UpdateSchedule.INITIAL_DELAY_MS)
    }

    @Test
    fun failuresBackOffThroughTheDesktopSchedule() {
        // CHECK_RETRY: 1 min, 5 min, 15 min, 30 min.
        assertEquals(60_000L, UpdateSchedule.retryDelayMs(1))
        assertEquals(300_000L, UpdateSchedule.retryDelayMs(2))
        assertEquals(900_000L, UpdateSchedule.retryDelayMs(3))
        assertEquals(1_800_000L, UpdateSchedule.retryDelayMs(4))
    }

    @Test
    fun theBackoffStaysAtTheLastDelayForever() {
        assertEquals(1_800_000L, UpdateSchedule.retryDelayMs(5))
        assertEquals(1_800_000L, UpdateSchedule.retryDelayMs(10))
        assertEquals(1_800_000L, UpdateSchedule.retryDelayMs(1000))
    }

    @Test
    fun zeroFailuresAreNotScheduledButDoNotCrash() {
        assertEquals(60_000L, UpdateSchedule.retryDelayMs(0))
    }
}
