package sh.roboco.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The update copy's pure formatters (android-next#04): the MB size, the
 * "Released <date>" label from the manifest's ISO date, the "checked <n>
 * ago" bucket, and the SHA-256 hex digest (RFC 4231's test vector). Plain
 * JVM.
 */
class UpdateFormatTest {

    // --------------------------------------------------------- the size

    @Test
    fun sizesFormatAsMbWithOneTrimmedDecimal() {
        assertEquals("24.5", mbNumber(24_530_000))
        assertEquals("1.1", mbNumber(1_100_000))
        // A whole number loses the trailing ".0".
        assertEquals("100", mbNumber(100_000_000))
        assertEquals("0.1", mbNumber(100_000))
    }

    // --------------------------------------------------------- the date

    @Test
    fun releaseDatesFormatAsEnglishMonthAndDay() {
        assertEquals("Oct 20", releaseDateLabel("2026-10-20T13:37:00Z"))
        assertEquals("Jan 1", releaseDateLabel("2027-01-01T00:00:00Z"))
    }

    @Test
    fun releaseDatesTolerateMissingOrUnparseableValues() {
        assertNull(releaseDateLabel(null))
        assertNull(releaseDateLabel(""))
        assertNull(releaseDateLabel("not a date"))
    }

    // -------------------------------------------------- the relative age

    @Test
    fun checkedAgoBucketsJustNowMinutesHoursYesterday() {
        val now = 1_000_000L
        assertEquals(CheckedAgo.Now, checkedAgo(now - 30_000, now))
        assertEquals(CheckedAgo.Now, checkedAgo(now, now))
        assertEquals(CheckedAgo.Minutes(1), checkedAgo(now - 60_000, now))
        assertEquals(CheckedAgo.Minutes(1), checkedAgo(now - 119_999, now))
        assertEquals(CheckedAgo.Minutes(2), checkedAgo(now - 120_000, now))
        assertEquals(CheckedAgo.Minutes(59), checkedAgo(now - 59 * 60_000, now))
        assertEquals(CheckedAgo.Hours(1), checkedAgo(now - 60 * 60_000, now))
        assertEquals(CheckedAgo.Hours(23), checkedAgo(now - 23 * 60 * 60_000, now))
        assertEquals(CheckedAgo.Yesterday, checkedAgo(now - 24 * 60 * 60_000, now))
        assertEquals(CheckedAgo.Yesterday, checkedAgo(now - 3 * 24 * 60 * 60_000, now))
    }

    // -------------------------------------------------- the digest hex

    @Test
    fun sha256HexMatchesRfc4231() {
        // RFC 4231 test case 1: SHA-256("abc").
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun anEmptyStreamDigestsToTheKnownEmptyValue() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    // ------------------------------------------- the download check

    @Test
    fun sha256MatchesRefusesAMissingEntry() {
        // "refuse on mismatch or missing entry" — the manifest's entry
        // without a sha256 is a refusal, not a skip.
        assertEquals(false, sha256Matches("aa", null))
        assertEquals(false, sha256Matches(null, "aa"))
    }

    @Test
    fun sha256MatchesComparesCaseInsensitively() {
        assertEquals(true, sha256Matches("AABB", "aabb"))
        assertEquals(true, sha256Matches("aabb", "AABB"))
        assertEquals(false, sha256Matches("aabb", "cccc"))
    }
}
