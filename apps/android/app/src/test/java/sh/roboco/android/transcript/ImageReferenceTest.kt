package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetchable-reference rule (ticket 137) and the per-engine cache-key
 * separation (66's `imageCacheKey`, re-verified here). Plain JVM — both
 * are pure functions.
 */
class ImageReferenceTest {

    /** Every row of the ticket's Spec table, in order. */
    @Test
    fun theFetchableTable() {
        // Landed uploads: the engine resolves pending:// references.
        assertTrue(isFetchableImageReference("pending://u1/b.png"))
        // POSIX absolute paths — the engine jail decides (so /tmp/x.png is
        // fetchable yet still fails server-side).
        assertTrue(isFetchableImageReference("/workspace/calculator/5-plus-3.png"))
        assertTrue(isFetchableImageReference("/tmp/x.png"))
        assertTrue(isFetchableImageReference("//x/y.png"))
        // Windows drive-absolute paths.
        assertTrue(isFetchableImageReference("C:\\Users\\me\\shot.png"))
        assertTrue(isFetchableImageReference("C:/Users/me/shot.png"))
        // Verbatim (\\?\C:\) and UNC (\\server\share) — the engine decides.
        assertTrue(isFetchableImageReference("\\\\?\\C:\\shot.png"))
        assertTrue(isFetchableImageReference("\\\\server\\share\\a.png"))
        // The phone never fetches HTTP image URLs (#817's README).
        assertFalse(isFetchableImageReference("https://example.com/a.png"))
        assertFalse(isFetchableImageReference("http://x/a.png"))
        // Not a path.
        assertFalse(isFetchableImageReference("data:image/png;base64,iVBOR"))
        // Any other URI scheme.
        assertFalse(isFetchableImageReference("file:///tmp/a.png"))
        assertFalse(isFetchableImageReference("roboco-file:a.png"))
        // Relative: the engine requires absolute (uploads.rs:402-406).
        assertFalse(isFetchableImageReference("assets/shot.png"))
        assertFalse(isFetchableImageReference("./shot.png"))
        assertFalse(isFetchableImageReference("~/shot.png"))
        assertFalse(isFetchableImageReference("C:shot.png"))
        assertFalse(isFetchableImageReference(""))
    }

    /** A drive-letter prefix with no separator is not drive-absolute. */
    @Test
    fun aDriveLetterWithoutASeparatorIsRelative() {
        assertFalse(isFetchableImageReference("C:shot.png"))
        assertFalse(isFetchableImageReference("c:folder/x.png"))
        // But a single leading slash after the drive counts.
        assertTrue(isFetchableImageReference("C:/x.png"))
        assertTrue(isFetchableImageReference("Z:\\x.png"))
    }

    /** pr817's cache-key test: hosts and length-ambiguous splits differ. */
    @Test
    fun imageKeySeparatesHostsWithTheSamePath() {
        assertNotEquals(ImageKey("host-a", "/p.png"), ImageKey("host-b", "/p.png"))
        assertEquals(ImageKey("host-a", "/p.png"), ImageKey("host-a", "/p.png"))
    }

    /** The length-prefix property: no separator ambiguity between the pair. */
    @Test
    fun imageKeySeparatesAmbiguousSplits() {
        assertNotEquals(ImageKey("a", "bc"), ImageKey("ab", "c"))
    }
}
