package sh.roboco.android.update

/**
 * The check cadence (android-next#04), mirroring `crates/update/src/lib.rs:50-61`:
 * the desktop's 2 s post-window initial delay, the hourly repeat after a
 * successful check, and the four failure backoffs that stay at the last
 * one forever. Failures count consecutively; any success resets to the
 * hourly interval.
 */
object UpdateSchedule {

    /** The desktop's DESKTOP_INITIAL_DELAY: the launch check waits out the first frame. */
    const val INITIAL_DELAY_MS: Long = 2_000L

    /** The desktop's CHECK_INTERVAL: successful checks repeat hourly. */
    const val CHECK_INTERVAL_MS: Long = 60L * 60 * 1000

    /** The desktop's CHECK_RETRY: 1 min, 5 min, 15 min, 30 min. */
    private val CHECK_RETRY_MS = longArrayOf(
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        30 * 60_000L,
    )

    /**
     * The delay after [consecutiveFailures] failures — the first failure
     * waits the first backoff (1 min), the fourth and every later one the
     * last (30 min).
     */
    fun retryDelayMs(consecutiveFailures: Int): Long =
        CHECK_RETRY_MS[consecutiveFailures.coerceIn(1, CHECK_RETRY_MS.size) - 1]
}
