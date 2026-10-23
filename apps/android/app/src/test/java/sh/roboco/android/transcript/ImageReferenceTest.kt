package sh.roboco.android.transcript

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image-reference classifier (which sources a reference tries, in
 * order), the phone-side `data:` decoder, the retry backoff and reconnect
 * signal, and the per-engine cache-key separation (66's `imageCacheKey`).
 * Plain JVM — all pure functions. Path resolution (relative, `~`,
 * `file://`, `roboco-file:`) is the engine's `ReadImageChunk`, pinned by
 * `crates/engine/src/image_refs.rs`'s tests.
 */
class ImageReferenceTest {

    private val engineOnly = listOf(ImageSource.Engine)

    @Test
    fun webUrlsTryThePhoneThenTheEngine() {
        val web = listOf(ImageSource.Web, ImageSource.Engine)
        assertEquals(web, imageSources("https://example.com/a.png"))
        assertEquals(web, imageSources("http://localhost:8080/shot"))
        assertEquals(web, imageSources("HTTPS://EXAMPLE.COM/A.PNG"))
        assertEquals(web, imageSources("  https://example.com/a.svg  "))
    }

    @Test
    fun dataUrisDecodeLocallyThenFallBackToTheEngine() {
        // The engine fallback renders what the phone can't decode (SVG).
        assertEquals(listOf(ImageSource.Inline, ImageSource.Engine), imageSources("data:image/png;base64,iVBOR"))
        assertEquals(listOf(ImageSource.Inline, ImageSource.Engine), imageSources("DATA:image/svg+xml,<svg/>"))
    }

    @Test
    fun everyPathAndFileReferenceGoesToTheEngine() {
        for (ref in listOf(
            "pending://u1/b.png",
            "/workspace/calculator/5-plus-3.png",
            "/tmp/x.png",
            "//x/y.png",
            "C:\\Users\\me\\shot.png",
            "C:/Users/me/shot.png",
            "\\\\?\\C:\\shot.png",
            "\\\\server\\share\\a.png",
            "file:///tmp/a.png",
            "file:///C:/Users/me/a%20b.png",
            "roboco-file:shots/a%20b.png",
            "assets/shot.png",
            "./shot.png",
            "../shot.png",
            "~/shot.png",
            "C:shot.png",
            "shot.png?raw=1",
        )) {
            assertEquals(ref, engineOnly, imageSources(ref))
            assertTrue(ref, isFetchableImageReference(ref))
        }
    }

    @Test
    fun onlyBlankReferencesAreRefused() {
        assertEquals(emptyList<ImageSource>(), imageSources(""))
        assertEquals(emptyList<ImageSource>(), imageSources("   "))
        assertFalse(isFetchableImageReference(""))
    }

    @Test
    fun dataUrisDecodeBase64AndPercentEncodedPayloads() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(png, decodeDataUri("data:image/png;base64,iVBORw0KGgo="))
        // Unpadded, wrapped, and URL-safe base64.
        assertArrayEquals(png, decodeDataUri("data:image/png;base64,iVBORw0K\nGgo"))
        assertArrayEquals(byteArrayOf(-5, -1), decodeDataUri("data:application/octet-stream;base64,-_8"))
        assertArrayEquals("<svg a='b c'/>".toByteArray(), decodeDataUri("data:image/svg+xml,%3Csvg%20a='b%20c'/%3E"))
        assertNull(decodeDataUri("data:image/png;base64"))
        assertNull(decodeDataUri("https://example.com/a.png"))
    }

    @Test
    fun percentEscapesNeedTwoRealHexDigits() {
        assertArrayEquals(byteArrayOf(0x41, 0x2F, 0xFF.toByte()), percentDecodeBytes("%41%2f%FF"))
        // A sign is not a hex digit (`toIntOrNull(16)` would take "+1" / "-1").
        assertArrayEquals("%+1%-1".toByteArray(), percentDecodeBytes("%+1%-1"))
        // Non-hex and truncated escapes stay literal.
        assertArrayEquals("%zz%4".toByteArray(), percentDecodeBytes("%zz%4"))
        assertArrayEquals("100%".toByteArray(), percentDecodeBytes("100%"))
        assertArrayEquals("a+b".toByteArray(), decodeDataUri("data:text/plain,a%2Bb"))
        assertArrayEquals("%+1".toByteArray(), decodeDataUri("data:text/plain,%+1"))
    }

    @Test
    fun retriesBackOffUpToAMinute() {
        assertEquals(2_000L, imageRetryDelayMs(1))
        assertEquals(4_000L, imageRetryDelayMs(2))
        assertEquals(8_000L, imageRetryDelayMs(3))
        assertEquals(60_000L, imageRetryDelayMs(6))
        assertEquals(60_000L, imageRetryDelayMs(50))
        assertEquals(2_000L, imageRetryDelayMs(0))
        // The automatic budget is about four minutes.
        val budget = (1..MaxAutoImageRetries).sumOf { imageRetryDelayMs(it) }
        assertEquals(242_000L, budget)
    }

    @Test
    fun reconnectsFireOnlyWhenTheEngineComesBack() = runTest {
        // Already connected at subscription: no emission until a drop and
        // a return; repeats of the same state are ignored.
        val fired = reconnects(flowOf(true, true, false, false, true, true, false, true)).toList()
        assertEquals(2, fired.size)
        // Subscribing while offline fires on the first connect.
        assertEquals(1, reconnects(flowOf(false, true)).toList().size)
        assertEquals(0, reconnects(flowOf(true)).toList().size)
    }

    /** pr817's cache-key test: hosts and length-ambiguous splits differ. */
    @Test
    fun imageKeySeparatesHostsWithTheSamePath() {
        assertNotEquals(ImageKey("host-a", "/p.png"), ImageKey("host-b", "/p.png"))
        assertEquals(ImageKey("host-a", "/p.png"), ImageKey("host-a", "/p.png"))
    }

    /** A relative reference names a different file in every chat. */
    @Test
    fun imageKeySeparatesChats() {
        assertNotEquals(ImageKey("e", "shots/a.png", "chat-1"), ImageKey("e", "shots/a.png", "chat-2"))
    }

    /** The length-prefix property: no separator ambiguity between the pair. */
    @Test
    fun imageKeySeparatesAmbiguousSplits() {
        assertNotEquals(ImageKey("a", "bc"), ImageKey("ab", "c"))
    }
}
