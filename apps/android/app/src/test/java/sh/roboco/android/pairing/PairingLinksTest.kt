package sh.roboco.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.shell.DeepLink

/**
 * The inbound pairing-link grammar (android-ui-parity #139) — every row
 * of the ticket's deep-link, share and `deepLinkFor` tables, plus the
 * round-trip and charset checks. Plain JUnit: [PairingLinks] is pure
 * Kotlin (no `android.*`, no FFI — the Robolectric
 * [PairingLinksUriTest] pins the `android.net.Uri` equivalence).
 *
 * The `accepts` stand-in is the gallery regex from 72
 * (`^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$`), never the
 * native parser: android-app#06 owns the real grammar's tests.
 */
class PairingLinksTest {

    /** The gallery/Pair-page-test parse stand-in (72), as a predicate. */
    private val accepts: (String) -> Boolean = { input ->
        PairingRegex.matchEntire(input.trim()) != null
    }

    // ------------------------------------------------- urlFromDeepLink

    @Test
    fun urlFromDeepLinkDecodesOnceAndTrims() {
        for ((link, expected) in DEEP_LINK_TABLE) {
            assertEquals(expected, PairingLinks.urlFromDeepLink(link))
        }
    }

    @Test
    fun urlFromDeepLinkRejectsTheNullRows() {
        for (link in NULL_LINKS) {
            assertNull(PairingLinks.urlFromDeepLink(link))
        }
    }

    @Test
    fun urlFromDeepLinkKeepsPlusLiteral() {
        assertEquals("a+b", PairingLinks.urlFromDeepLink("roboco://pair#url=a+b"))
    }

    // -------------------------------------------------- shareCandidates

    @Test
    fun shareCandidatesTrimTheScopedTrailingPunctuation() {
        assertEquals(
            listOf("http://10.0.2.2:5555/pair#token=abc"),
            PairingLinks.shareCandidates("Pair: http://10.0.2.2:5555/pair#token=abc."),
        )
        assertEquals(
            listOf("http://h/pair#token=t"),
            PairingLinks.shareCandidates("http://h/pair#token=t...,;)"),
        )
        assertEquals(
            listOf("https://a.example/pair#token=1", "http://b/pair#token=2"),
            PairingLinks.shareCandidates("a https://a.example/pair#token=1), b http://b/pair#token=2;"),
        )
    }

    @Test
    fun shareCandidatesSplitOnAnyUnicodeWhitespace() {
        // \r\n inside the text (not inside a token).
        assertEquals(
            listOf("http://h/pair#token=t"),
            PairingLinks.shareCandidates("line1\r\nhttp://h/pair#token=t\r\n"),
        )
        // U+00A0: Char.isWhitespace covers it, Java regex \s would not.
        assertEquals(
            listOf("http://h/pair#token=t"),
            PairingLinks.shareCandidates("a\u00A0http://h/pair#token=t"),
        )
        assertEquals(
            listOf("http://h/pair#token=t"),
            PairingLinks.shareCandidates("link: http://h/pair#token=t"),
        )
    }

    @Test
    fun shareCandidatesKeepEveryTokenCarryingTheTokenMarker() {
        // Both carry the substring; only the parser sorts them out later.
        assertEquals(
            listOf("http://h/other#token=a", "http://h/pair#token=b"),
            PairingLinks.shareCandidates("http://h/other#token=a http://h/pair#token=b"),
        )
    }

    @Test
    fun shareCandidatesReturnNothingForLinklessText() {
        assertEquals(emptyList<String>(), PairingLinks.shareCandidates("no link here"))
        assertEquals(emptyList<String>(), PairingLinks.shareCandidates(""))
        assertEquals(emptyList<String>(), PairingLinks.shareCandidates("roboco-tailcat:abc"))
    }

    @Test
    fun shareCandidatesCapAtEight() {
        val nine = (1..9).joinToString(" ") { n -> "http://h$n/pair#token=t$n" }
        assertEquals(
            (1..8).map { n -> "http://h$n/pair#token=t$n" },
            PairingLinks.shareCandidates(nine),
        )
    }

    // --------------------------------------------- urlFromSharedText

    @Test
    fun urlFromSharedTextReturnsTheFirstAcceptedCandidate() {
        // The first token does NOT parse (path `/other`); the second
        // does — the gallery stand-in picks the second token.
        assertEquals(
            "http://h/pair#token=b",
            PairingLinks.urlFromSharedText("http://h/other#token=a http://h/pair#token=b", accepts),
        )
        assertEquals(
            "http://h/pair#token=t",
            PairingLinks.urlFromSharedText("Pair: http://h/pair#token=t.", accepts),
        )
        assertNull(PairingLinks.urlFromSharedText("hello world", accepts))
        assertNull(PairingLinks.urlFromSharedText("roboco-tailcat:abc", accepts))
    }

    // ------------------------------------------------------ deepLinkFor

    @Test
    fun deepLinkForPercentEncodesEverythingButTheUnreservedSet() {
        assertEquals(
            "roboco://pair#url=http%3A%2F%2F10.0.2.2%3A5555%2Fpair%23token%3DAb_c-1",
            PairingLinks.deepLinkFor("http://10.0.2.2:5555/pair#token=Ab_c-1"),
        )
    }

    @Test
    fun deepLinkForUsesOnlyUnreservedAndEscapeChars() {
        val encoded = PairingLinks.deepLinkFor("http://10.0.2.2:5555/pair#token=Ab_c-1")
            .removePrefix(PairingLinks.DEEP_LINK_PREFIX)
        assertFalse(encoded.isEmpty())
        assertTrue(encoded.matches(Regex("[A-Za-z0-9._~%-]+")))
    }

    @Test
    fun deepLinkForRoundTripsThroughUrlFromDeepLink() {
        for (url in ROUND_TRIP_URLS) {
            assertEquals(url, PairingLinks.urlFromDeepLink(PairingLinks.deepLinkFor(url)))
        }
    }

    // --------------------------------------------- DeepLink.Pair redaction

    @Test
    fun deepLinkPairToStringNeverCarriesTheLinkOrItsToken() {
        val rendered = DeepLink.Pair(
            "roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dabc",
        ).toString()
        assertEquals("Pair(<redacted>)", rendered)
        assertFalse("token" in rendered)
        assertFalse("abc" in rendered)
        // The data-class equality the hub relies on is untouched.
        assertEquals(
            DeepLink.Pair("roboco://pair#url=x"),
            DeepLink.Pair("roboco://pair#url=x"),
        )
    }

    // ------------------------------------------------------------- rig

    private companion object {
        /** 72's gallery parse stand-in, as the ticket quotes it. */
        val PairingRegex = Regex("^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$")

        val DEEP_LINK_TABLE = listOf(
            "roboco://pair#url=http%3A%2F%2F192.168.1.10%3A27655%2Fpair%23token%3DAbC-123_x" to
                "http://192.168.1.10:27655/pair#token=AbC-123_x",
            "ROBOCO://Pair#url=https%3A%2F%2Fe.example.ts.net%2Fpair%23token%3Dz" to
                "https://e.example.ts.net/pair#token=z",
            // The fragment is everything after the FIRST '#' — an inner
            // URL that was never percent-encoded still survives.
            "roboco://pair#url=http://h:1/pair#token=x" to "http://h:1/pair#token=x",
            "roboco://pair#v=2&url=http%3A%2F%2Fh%2Fpair%23token%3Dx" to "http://h/pair#token=x",
            "roboco://pair#url=a&url=b" to "a",
            "roboco://pair#url=%20http%3A%2F%2Fh%2Fpair%23token%3Dx%0A" to "http://h/pair#token=x",
            // Decoded ONCE: a literal %25… pass-through (the parser then
            // rejects it — that is the point of the single decode).
            "roboco://pair#url=http%253A%252F%252Fh%252Fpair%2523token%253Dx" to
                "http%3A%2F%2Fh%2Fpair%23token%3Dx",
        )

        val NULL_LINKS = listOf(
            "roboco://pair",
            "roboco://pair#",
            "roboco://pair#url=",
            "roboco://pair#URL=x",
            "roboco://pair?url=x",
            // Malformed escapes.
            "roboco://pair#url=%GG",
            "roboco://pair#url=%2",
            // Blank after trim.
            "roboco://pair#url=%20%0A",
            // An inner control char (WHATWG would drop it silently).
            "roboco://pair#url=http%3A%2F%2Fh%2Fpa%0Air%23token%3Dx",
        )

        val ROUND_TRIP_URLS = listOf(
            "http://10.0.2.2:5555/pair#token=Ab_c-1",
            "https://e.example.ts.net/sub/pair#token=z",
            "http://[fe80::1]:27655/pair#token=q",
        )
    }
}
