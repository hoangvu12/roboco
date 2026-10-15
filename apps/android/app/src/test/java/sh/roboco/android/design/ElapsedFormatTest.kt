package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatElapsed] (android-ui-parity #73, shared with 123): the ticket's
 * elapsed table, verbatim. Plain JUnit 4.
 */
class ElapsedFormatTest {

    @Test
    fun theElapsedTable() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("59s", formatElapsed(59))
        assertEquals("1m 0s", formatElapsed(60))
        assertEquals("1m 15s", formatElapsed(75))
        assertEquals("59m 59s", formatElapsed(3599))
        assertEquals("1h 0m", formatElapsed(3600))
        assertEquals("1h 1m", formatElapsed(3661))
    }

    /** No days branch: hours keep growing (PR #621's `Nd Nh` is not iOS). */
    @Test
    fun hoursKeepGrowingThereIsNoDaysBranch() {
        assertEquals("25h 0m", formatElapsed(90_000))
        assertEquals("100h 0m", formatElapsed(360_000))
    }

    @Test
    fun integerDivisionTruncatesLikeSwift() {
        assertEquals("1m 1s", formatElapsed(61))
        assertEquals("2h 0m", formatElapsed(7_200))
    }
}
