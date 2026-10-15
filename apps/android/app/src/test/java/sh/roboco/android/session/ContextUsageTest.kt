package sh.roboco.android.session

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.glass.MenuItem

/**
 * android-next #01's pure context-usage rules: the reading table, the
 * locale-aware grouping (desktop `with_separators`' vectors in en-US) and
 * the details card's rows, worded like desktop `details()`. Plain JUnit.
 */
class ContextUsageTest {

    // ── the reading ────────────────────────────────────────────────────

    @Test
    fun aReadingNeedsATokenCount() {
        assertNull(contextReading(null, null))
        assertNull("a window alone is not usage", contextReading(null, 200_000uL))
        assertNull("zero tokens without a window says nothing", contextReading(0uL, null))
        assertNull(contextReading(0uL, 0uL))
    }

    @Test
    fun aKnownWindowMeasuresAtAnyFraction() {
        assertEquals(ContextReading.Measured(0uL, 200_000uL), contextReading(0uL, 200_000uL))
        assertEquals(ContextReading.Measured(6_000uL, 200_000uL), contextReading(6_000uL, 200_000uL))
    }

    @Test
    fun anUnknownOrZeroWindowKeepsTheBareCount() {
        assertEquals(ContextReading.TokensOnly(48_210uL), contextReading(48_210uL, null))
        assertEquals(ContextReading.TokensOnly(48_210uL), contextReading(48_210uL, 0uL))
    }

    @Test
    fun remainingNeverGoesNegative() {
        assertEquals(200uL, ContextReading.Measured(0uL, 200uL).remaining)
        assertEquals(0uL, ContextReading.Measured(250uL, 200uL).remaining)
    }

    // ── grouping ───────────────────────────────────────────────────────

    @Test
    fun tokenCountsAreGroupedByThousands() {
        // Desktop's `token_counts_are_grouped_by_thousands` vectors.
        assertEquals("0", groupedTokens(0uL, Locale.US))
        assertEquals("999", groupedTokens(999uL, Locale.US))
        assertEquals("5,417", groupedTokens(5_417uL, Locale.US))
        assertEquals("1,048,576", groupedTokens(1_048_576uL, Locale.US))
    }

    @Test
    fun groupingFollowsTheLocale() {
        assertEquals("1.048.576", groupedTokens(1_048_576uL, Locale.GERMANY))
    }

    @Test
    fun theWholeU64RangeFormats() {
        assertEquals("18,446,744,073,709,551,615", groupedTokens(ULong.MAX_VALUE, Locale.US))
    }

    // ── the card ───────────────────────────────────────────────────────

    private fun rows(reading: ContextReading): List<MenuItem> =
        contextUsageCard(reading, Locale.US).children.map { it as MenuItem }

    @Test
    fun theMeasuredCardMatchesTheDesktopWording() {
        val card = contextUsageCard(ContextReading.Measured(5_417uL, 1_048_576uL), Locale.US)
        assertEquals("Context window", card.title)
        val (usage, remaining) = rows(ContextReading.Measured(5_417uL, 1_048_576uL))
        assertEquals("5,417 / 1,048,576 tokens", usage.title)
        assertEquals("1% used", usage.subtitle)
        assertEquals("1,043,159 tokens remaining", remaining.title)
    }

    @Test
    fun theCardWithoutAWindowShowsTheCountAndSaysSo() {
        val (usage, limit) = rows(ContextReading.TokensOnly(48_210uL))
        assertEquals("48,210 tokens used", usage.title)
        assertNull(usage.subtitle)
        assertEquals("Context limit not reported", limit.title)
    }

    @Test
    fun anOverflowingContextHasNothingRemaining() {
        val (usage, remaining) = rows(ContextReading.Measured(250uL, 200uL))
        assertEquals("125% used", usage.subtitle)
        assertEquals("0 tokens remaining", remaining.title)
    }

    @Test
    fun everyCardRowIsReadOnly() {
        val all = rows(ContextReading.Measured(1uL, 2uL)) + rows(ContextReading.TokensOnly(3uL))
        assertTrue(all.all { it.informational && !it.disabled && !it.checked && it.icon == null })
    }
}
