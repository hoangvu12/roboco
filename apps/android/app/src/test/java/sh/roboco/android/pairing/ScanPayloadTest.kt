package sh.roboco.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's payload rules (android-ui-parity #138, plain JVM):
 * `classifyScan` over the ticket's accept/reject table, `decodePairLink`
 * (the prefix gate + the shared `PairingLinks.urlFromDeepLink` decoder) and
 * `RejectDebouncer`'s one-flash-per-sighting window.
 *
 * `isPairingUrl` is ticket 72's regex stand-in (`StandInParse`); the real
 * parser's tests live in android-app#06.
 */
class ScanPayloadTest {

    private val isPairingUrl: (String) -> Boolean = { s -> StandInParse(s) != null }

    // ------------------------------------------------------------ Pairing

    @Test
    fun anHttpsPairingUrlIsAccepted() {
        assertEquals(
            ScanVerdict.Pairing("https://engine.example.ts.net/pair#token=AbC-123_x"),
            classifyScan("https://engine.example.ts.net/pair#token=AbC-123_x", isPairingUrl),
        )
    }

    @Test
    fun anHttpPairingUrlIsAccepted() {
        assertEquals(
            ScanVerdict.Pairing("http://192.168.1.10:27655/pair#token=abc"),
            classifyScan("http://192.168.1.10:27655/pair#token=abc", isPairingUrl),
        )
    }

    @Test
    fun paddingSpacesAreTrimmedBeforeAccepting() {
        assertEquals(
            ScanVerdict.Pairing("http://192.168.1.10:27655/pair#token=abc"),
            classifyScan("  http://192.168.1.10:27655/pair#token=abc \n", isPairingUrl),
        )
        assertEquals(
            ScanVerdict.Pairing("https://e.example.ts.net/pair#token=z"),
            classifyScan("\thttps://e.example.ts.net/pair#token=z ", isPairingUrl),
        )
    }

    @Test
    fun theDeepLinkWrapperIsUnwrapped() {
        val wrapped = "roboco://pair#url=http%3A%2F%2F192.168.1.10%3A27655%2Fpair%23token%3Dabc"
        assertEquals(
            ScanVerdict.Pairing("http://192.168.1.10:27655/pair#token=abc"),
            classifyScan(wrapped, isPairingUrl),
        )
    }

    @Test
    fun theDeepLinkWrapperMatchesCaseInsensitivelyWithASlash() {
        val wrapped = "ROBOCO://PAIR/#url=https%3A%2F%2Fe.example.ts.net%2Fpair%23token%3Dz"
        assertEquals(
            ScanVerdict.Pairing("https://e.example.ts.net/pair#token=z"),
            classifyScan(wrapped, isPairingUrl),
        )
    }

    @Test
    fun aWrapperAroundAnUnencodedUrlStillUnwraps() {
        // The fragment is everything after the FIRST '#', so the inner
        // URL's own #token=… survives unencoded.
        assertEquals(
            ScanVerdict.Pairing("http://h:1/pair#token=x"),
            classifyScan("roboco://pair#url=http://h:1/pair#token=x", isPairingUrl),
        )
    }

    @Test
    fun aTailcatInviteIsDeliveredNotRejected() {
        assertEquals(
            ScanVerdict.Pairing("roboco-tailcat:abc"),
            classifyScan("roboco-tailcat:abc", isPairingUrl),
        )
    }

    // Rooms (ADR 0011, ticket 04): the desktop's room invite QR is the
    // phone's scan-once — accepted like any pairing payload, whitespace
    // still refused, and it never falls through to the engine parser.

    @Test
    fun aRoomInviteIsDeliveredNotRejected() {
        assertEquals(
            ScanVerdict.Pairing("roboco-room:eyJ2IjoxfQ"),
            classifyScan("roboco-room:eyJ2IjoxfQ", isPairingUrl),
        )
        // A QR code the camera read with surrounding noise still trims.
        assertEquals(
            ScanVerdict.Pairing("roboco-room:eyJ2IjoxfQ"),
            classifyScan("  roboco-room:eyJ2IjoxfQ \n", isPairingUrl),
        )
    }

    @Test
    fun aRoomInviteWithEmbeddedWhitespaceIsForeign() {
        assertEquals(
            ScanVerdict.Foreign,
            classifyScan("roboco-room:eyJ 2IjoxfQ", isPairingUrl),
        )
    }

    @Test
    fun aRoomInviteIsNeverTriedAsAnEngineUrl() {
        // The room prefix wins before the engine parser runs (it would
        // refuse the payload as "not a URL" — the invite is not a link).
        var engineParseCalls = 0
        val counting: (String) -> Boolean = { s ->
            engineParseCalls += 1
            StandInParse(s) != null
        }
        assertEquals(
            ScanVerdict.Pairing("roboco-room:eyJ2IjoxfQ"),
            classifyScan("roboco-room:eyJ2IjoxfQ", counting),
        )
        assertEquals(0, engineParseCalls)
    }

    // ------------------------------------------------------------ Foreign

    @Test
    fun aNonPairingUrlIsForeign() {
        assertEquals(ScanVerdict.Foreign, classifyScan("https://example.com", isPairingUrl))
    }

    @Test
    fun aForeignRobocoRouteIsForeign() {
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://chat/1", isPairingUrl))
    }

    @Test
    fun aWrapperWithoutAFragmentIsForeign() {
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://pair", isPairingUrl))
    }

    @Test
    fun aMalformedEscapeInsideTheWrapperIsForeign() {
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://pair#url=%ZZ", isPairingUrl))
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://pair#url=%2", isPairingUrl))
    }

    @Test
    fun anEmptyWrapperValueIsForeign() {
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://pair#url=", isPairingUrl))
    }

    @Test
    fun aWrapperWithAQueryBeforeTheFragmentFailsThePrefixGate() {
        assertEquals(ScanVerdict.Foreign, classifyScan("roboco://pair?x=1#url=a", isPairingUrl))
    }

    @Test
    fun anInnerNewlineIsForeignEvenForAPairingUrl() {
        // Row 2b: WHATWG parsing would drop the LF, but the field must
        // not be filled with a hidden newline.
        assertEquals(
            ScanVerdict.Foreign,
            classifyScan("http://h/pa\nir#token=x", isPairingUrl),
        )
    }

    @Test
    fun aWrapperAroundANonPairingUrlIsForeign() {
        assertEquals(
            ScanVerdict.Foreign,
            classifyScan("roboco://pair#url=https%3A%2F%2Fexample.com", isPairingUrl),
        )
    }

    @Test
    fun aNestedWrapperIsNotUnwrappedTwice() {
        // One level only: the decoded `roboco://…` is not a pairing URL,
        // so it lands as Foreign, never recursively unwrapped.
        assertEquals(
            ScanVerdict.Foreign,
            classifyScan("roboco://pair#url=roboco%3A%2F%2Fpair%23url%3Dx", isPairingUrl),
        )
    }

    @Test
    fun aWrapperAroundAnInnerControlCharIsForeign() {
        assertEquals(
            ScanVerdict.Foreign,
            classifyScan("roboco://pair#url=http%3A%2F%2Fh%2Fpa%0Air%23token%3Dx", isPairingUrl),
        )
    }

    // ------------------------------------------------------------- Ignore

    @Test
    fun aBlankPayloadIsIgnored() {
        assertEquals(ScanVerdict.Ignore, classifyScan("  ", isPairingUrl))
        assertEquals(ScanVerdict.Ignore, classifyScan("", isPairingUrl))
    }

    // ------------------------------------------------------- decodePairLink

    @Test
    fun decodePairLinkKeepsALiteralPlus() {
        assertEquals("a+b", decodePairLink("roboco://pair#url=a+b"))
        assertEquals("a+b", decodePairLink("roboco://pair#url=a%2Bb"))
    }

    @Test
    fun decodePairLinkTakesTheFirstUrlParamOnly() {
        assertEquals("a", decodePairLink("roboco://pair#url=a&url=b"))
        assertEquals("http://h/pair#token=x", decodePairLink("roboco://pair#v=2&url=http://h/pair#token=x"))
    }

    @Test
    fun decodePairLinkRejectsWrongPrefixesAndKeys() {
        assertNull(decodePairLink("roboco://chat#url=a"))
        assertNull(decodePairLink("roboco://pairs#url=a"))
        assertNull(decodePairLink("roboco://pair#URL=a"))
        assertNull(decodePairLink("roboco://pair"))
        assertNull(decodePairLink("roboco://pair?x=1#url=a"))
    }

    @Test
    fun decodePairLinkTrimsAndRejectsBlankAndDirtyValues() {
        assertEquals(
            "http://h/pair#token=x",
            decodePairLink("roboco://pair#url=%20http%3A%2F%2Fh%2Fpair%23token%3Dx%0A"),
        )
        assertNull(decodePairLink("roboco://pair#url=%20%0A"))
    }

    @Test
    fun decodePairLinkDecodesOnlyOnce() {
        // Double-encoded input decodes once; the parser rejects the rest.
        assertEquals(
            "http%3A%2F%2Fh%2Fpair%23token%3Dx",
            decodePairLink("roboco://pair#url=http%253A%252F%252Fh%252Fpair%2523token%253Dx"),
        )
    }

    // ------------------------------------------------------ RejectDebouncer

    @Test
    fun theDebouncerFlashesOncePerSightingAndPerPayload() {
        val d = RejectDebouncer()
        assertTrue("(a, 0)", d.shouldShow("a", 0))
        assertFalse("(a, 500)", d.shouldShow("a", 500))
        // Last seen 500 → 1900 < 2000: still the same sighting.
        assertFalse("(a, 2400)", d.shouldShow("a", 2400))
        // 4500 − 2400 = 2100 ≥ 2000: out of view long enough to re-flash.
        assertTrue("(a, 4500)", d.shouldShow("a", 4500))
        assertTrue("(b, 4600)", d.shouldShow("b", 4600))
    }

    @Test
    fun theDebouncerWindowMatchesTheForeignCaption() {
        val d = RejectDebouncer(windowMs = 500)
        assertTrue(d.shouldShow("a", 0))
        assertFalse(d.shouldShow("a", 499))
        // Every call stores the time (KDoc: the window runs from the LAST
        // sighting): 499 + 500 is the first re-flash, not 500.
        assertFalse(d.shouldShow("a", 998))
        assertTrue(d.shouldShow("a", 1498))
    }

    // ------------------------------------------------------- PairingLinks

    @Test
    fun theEncodedFragmentIsEverythingAfterTheFirstHash() {
        assertEquals("url=a#b", PairingLinks.encodedFragment("roboco://pair#url=a#b"))
        assertNotNull(PairingLinks.encodedFragment("roboco://pair#"))
        assertNull(PairingLinks.encodedFragment("roboco://pair"))
    }
}
