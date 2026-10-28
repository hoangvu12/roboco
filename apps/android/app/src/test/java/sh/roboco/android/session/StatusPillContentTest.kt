package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [pillContent] (android-ui-parity #73): the exact glyph + verbatim label
 * of every banner row of the ticket's pill-content table. Plain JUnit 4,
 * no Robolectric — the mapping is pure Kotlin over the chrome enum.
 */
class StatusPillContentTest {

    private val now = 1_000_000L

    @Test
    fun sendingIsTheTrailer() {
        assertEquals(PillContent(PillGlyph.Trailer, "Sending to host…"), pillContent(SessionChrome.Banner.Sending, now))
    }

    @Test
    fun workingSince925kAt1MIsWorkingOneMinuteFifteen() {
        assertEquals(
            PillContent(PillGlyph.Trailer, "Working · 1m 15s"),
            pillContent(SessionChrome.Banner.Working(925_000L, "Working"), now),
        )
    }

    @Test
    fun workingWithoutASinceIsTheBareWord() {
        assertEquals(
            PillContent(PillGlyph.Trailer, "Writing…"),
            pillContent(SessionChrome.Banner.Working(null, "Writing"), now),
        )
    }

    @Test
    fun workingInTheFirstSecondIsTheBareWord() {
        // (1_000_000 − 999_500) / 1000 = 0 → truncates toward zero.
        assertEquals(
            PillContent(PillGlyph.Trailer, "Working…"),
            pillContent(SessionChrome.Banner.Working(999_500L, "Working"), now),
        )
    }

    @Test
    fun workingUsesTheElapsedFormatAtTheMinuteBoundary() {
        assertEquals(
            PillContent(PillGlyph.Trailer, "Working · 1m 0s"),
            pillContent(SessionChrome.Banner.Working(940_000L, "Working"), now),
        )
    }

    @Test
    fun notDeliveredIsTheDangerDot() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "Not delivered · Tap to retry"),
            pillContent(SessionChrome.Banner.NotDelivered, now),
        )
    }

    @Test
    fun failedIsTheDangerDotWithTheMessage() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "Couldn't send: Engine is offline; reconnecting"),
            pillContent(
                SessionChrome.Banner.Failed("Couldn't send: Engine is offline; reconnecting"),
                now,
            ),
        )
    }

    @Test
    fun editingIsTheInputDot() {
        assertEquals(
            PillContent(PillGlyph.DotInput, "Editing queued message · Tap to cancel"),
            pillContent(SessionChrome.Banner.Editing, now),
        )
    }

    @Test
    fun engineParkedRevokedIsTheDangerDot() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "Session revoked · Tap to pair again"),
            pillContent(
                SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Revoked, "e1"),
                now,
            ),
        )
    }

    @Test
    fun engineParkedIdentityChangedIsTheDangerDot() {
        assertEquals(
            PillContent(PillGlyph.DotDanger, "Engine changed · Tap to pair again"),
            pillContent(
                SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.IdentityChanged, "e1"),
                now,
            ),
        )
    }

    @Test
    fun engineParkedIncompatibleIsTheIdleDotDefensively() {
        assertEquals(
            PillContent(PillGlyph.DotIdle, "Engine off. Cached history is read-only."),
            pillContent(
                SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Incompatible, "e1"),
                now,
            ),
        )
    }

    @Test
    fun engineOffIsTheIdleDot() {
        assertEquals(
            PillContent(PillGlyph.DotIdle, "Engine off. Cached history is read-only."),
            pillContent(SessionChrome.Banner.EngineOff, now),
        )
    }

    @Test
    fun reconnectingIsTheIdleDot() {
        assertEquals(
            PillContent(PillGlyph.DotIdle, "Reconnecting… Cached history is read-only."),
            pillContent(SessionChrome.Banner.Reconnecting, now),
        )
    }

    @Test
    fun noneHasNoPillContent() {
        val thrown = try {
            pillContent(SessionChrome.Banner.None, now)
            null
        } catch (expected: IllegalStateException) {
            // Ticket 73 step 7: `None → error("None has no pill content")`,
            // and Kotlin's `error()` throws IllegalStateException.
            expected
        }
        assertNotNull("None must throw (the caller keeps the last content instead)", thrown)
    }
}
