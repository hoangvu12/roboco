package sh.roboco.android.transcript.widgets

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.formatElapsed

/**
 * The status widgets' pure logic (android-ui-parity #123), plain JVM — no
 * Robolectric: [elapsedSeconds], [workingWord], [workingLabel]'s text and
 * span ranges (the colours are `Color` value classes, nothing Android).
 * `formatElapsed`'s own table is ticket 73's [ElapsedFormatTest]; the
 * "1s" row is re-checked here because this ticket's acceptance table
 * lists it.
 */
class StatusWidgetsLogicTest {

    private val secondary = Color(0xFF62626A.toInt())
    private val tertiary = Color(0xFF97979F.toInt())

    // ---------------------------------------------------------- elapsedSeconds

    @Test
    fun aNullSinceIsZeroSeconds() {
        assertEquals(0, elapsedSeconds(null, 0L))
        assertEquals(0, elapsedSeconds(null, 1_760_000_000_000L))
    }

    @Test
    fun elapsedSecondsTruncatesTowardZeroAndClampsAtZero() {
        // now − since: −1500, −500, 0, 999, 1000, 1999, 75_000 → the table.
        val now = 1_760_000_000_000L
        for ((delta, expected) in listOf(-1500L to 0, -500L to 0, 0L to 0, 999L to 0, 1000L to 1, 1999L to 1, 75_000L to 75)) {
            assertEquals("now − since = $delta", expected, elapsedSeconds(now - delta, now))
        }
    }

    @Test
    fun elapsedSecondsClampsAtIntMax() {
        // since = 0 (epoch): 10¹³ ms later is 10¹⁰ s, far past Int.MAX_VALUE.
        assertEquals(Int.MAX_VALUE, elapsedSeconds(0L, 10_000_000_000_000L))
    }

    // ------------------------------------------------------------ workingWord

    @Test
    fun theWordFollowsStreaming() {
        assertEquals("Working", workingWord(streaming = false))
        assertEquals("Writing", workingWord(streaming = true))
    }

    // ----------------------------------------------------------- workingLabel

    @Test
    fun theLabelIsTheWordAndAnU2026Ellipsis() {
        for ((streaming, word) in listOf(false to "Working", true to "Writing")) {
            val s = workingLabel(streaming, 0, secondary, tertiary)
            assertEquals("$word…", s.text)
            assertEquals("the ellipsis is U+2026", 0x2026, s.text[word.length].code)
            assertEquals("no suffix at 0 s", word.length + 1, s.text.length)
        }
    }

    @Test
    fun theSuffixAppearsOnlyAfterTheFirstSecond() {
        assertEquals("Working…  1s", workingLabel(false, 1, secondary, tertiary).text)
        assertEquals("Writing…  7s", workingLabel(true, 7, secondary, tertiary).text)
        assertEquals("Working…  1m 15s", workingLabel(false, 75, secondary, tertiary).text)
        assertEquals("Writing…  1h 1m", workingLabel(true, 3661, secondary, tertiary).text)
    }

    @Test
    fun theSpansCarryTheTwoColours() {
        val s = workingLabel(false, 75, secondary, tertiary)
        // "Working…" = 8 chars → 0..8; "  1m 15s" (two U+0020 + the elapsed
        // string) = 8 chars → 8..16.
        val spans = s.spanStyles
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(8, spans[0].end)
        assertEquals(secondary, spans[0].item.color)
        assertEquals(8, spans[1].start)
        assertEquals(16, spans[1].end)
        assertEquals(tertiary, spans[1].item.color)
        assertTrue("the suffix starts with two U+0020", s.text[8] == ' ' && s.text[9] == ' ')
    }

    @Test
    fun zeroSecondsHasASingleSecondarySpan() {
        val s = workingLabel(true, 0, secondary, tertiary)
        assertEquals(1, s.spanStyles.size)
        assertEquals(0, s.spanStyles[0].start)
        // "Writing…" = 7 letters + the one U+2026 char = 8, like "Working…".
        assertEquals(8, s.spanStyles[0].end)
        assertEquals(secondary, s.spanStyles[0].item.color)
    }

    // ---------------------------------------------------------- formatElapsed

    @Test
    fun theOneSecondRow() {
        assertEquals("1s", formatElapsed(1))
    }
}
