package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.Scroller

/**
 * [hitLink] — the iOS `RowView.link(at:)` hit-test
 * (`ios:Transcript/RowView.swift:301-314`): the ±4/±2 dp slop with
 * half-open right/bottom edges, first match in `links` order, the
 * scroller content conversion with the live offset, the unconverted
 * fallback for an out-of-range index, and the painted (not inflated)
 * result rect. Plain JUnit 4, no Robolectric: like
 * `ScrollerGeometryTest`, constructing the generated records touches no
 * JNA and no `android.os`.
 */
class LinkHitTest {

    private val noScrollers = emptyList<Scroller>()
    private val noOffset: (Int) -> Float = { 0f }

    @Test
    fun aRowLinkHitsInsideItsInflatedRectAndMissesOutside() {
        val links = listOf(LinkHit(20f, 10f, 50f, 22f, "u", null))

        // The inflated rect is [16, 74) × [8, 34): the slop edges are in,
        // the far edges are half-open (CGRect.contains semantics).
        for ((x, y) in listOf(16f to 8f, 73.9f to 33.9f, 45f to 21f)) {
            assertNotNull("($x, $y) must hit", hitLink(links, noScrollers, noOffset, x, y))
        }
        for ((x, y) in listOf(15.9f to 20f, 74f to 20f, 45f to 7.9f, 45f to 34f)) {
            assertNull("($x, $y) must miss", hitLink(links, noScrollers, noOffset, x, y))
        }
    }

    @Test
    fun aHitReportsTheUninflatedFragmentRect() {
        val links = listOf(LinkHit(20f, 10f, 50f, 22f, "u", null))
        val hit = hitLink(links, noScrollers, noOffset, 45f, 21f)!!
        assertEquals("u", hit.url)
        assertEquals(20f, hit.x, 0f)
        assertEquals(10f, hit.y, 0f)
        assertEquals(50f, hit.w, 0f)
        assertEquals(22f, hit.h, 0f)
    }

    @Test
    fun aScrollerLinkHitsAtItsPaintedPositionUsingTheLiveOffset() {
        val scrollers = listOf(Scroller(16f, 100f, 379f, 200f, 900f))
        val links = listOf(LinkHit(600f, 20f, 80f, 20f, "t", 0u))

        // Offset 300: the tap (320, 130) converts to content (604, 30),
        // inside [596, 684) × [18, 42).
        val hit = hitLink(links, scrollers, { 300f }, 320f, 130f)!!
        assertEquals("t", hit.url)
        // The result rect is the painted position: (600 + 16 − 300, 20 + 100).
        assertEquals(316f, hit.x, 0f)
        assertEquals(120f, hit.y, 0f)
        assertEquals(80f, hit.w, 0f)
        assertEquals(20f, hit.h, 0f)

        // Offset 0: the same tap converts to (304, 30), outside the link.
        assertNull(hitLink(links, scrollers, { 0f }, 320f, 130f))

        // Offset 0, tap at (616, 130) → content (600, 30): a hit even
        // though the point is far outside the scroller's viewport — iOS
        // converts without a viewport check (RowView.swift:301-314).
        assertNotNull(hitLink(links, scrollers, { 0f }, 616f, 130f))
    }

    @Test
    fun anOutOfRangeScrollerIndexIsTestedAsARowPoint() {
        val links = listOf(LinkHit(10f, 10f, 20f, 20f, "o", 5u))
        // Index 5 against no scrollers: the point is not converted, and
        // (12, 12) is inside the row rect's own slop.
        val hit = hitLink(links, noScrollers, { 7f }, 12f, 12f)!!
        assertEquals("o", hit.url)
        assertEquals(10f, hit.x, 0f)
        assertEquals(10f, hit.y, 0f)
    }

    @Test
    fun theFirstLinkInDisplayOrderWinsWhenInflatedRectsOverlap() {
        val links = listOf(
            LinkHit(10f, 5f, 20f, 10f, "first", null),
            LinkHit(10f, 5f, 20f, 10f, "second", null),
        )
        // (30, 10) is inside both inflated rects; display order wins.
        assertEquals("first", hitLink(links, noScrollers, noOffset, 30f, 10f)!!.url)
    }

    @Test
    fun aMultiLineLinkRepeatsAndHitsOnEitherLine() {
        // One LinkHit per painted line fragment (Rust markdown.rs:500-508).
        val links = listOf(
            LinkHit(0f, 0f, 50f, 20f, "two", null),
            LinkHit(0f, 25f, 50f, 20f, "two", null),
        )
        assertEquals("two", hitLink(links, noScrollers, noOffset, 10f, 10f)!!.url)
        assertEquals("two", hitLink(links, noScrollers, noOffset, 10f, 35f)!!.url)
    }
}
