package sh.roboco.android.transcript

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.Scroller

/**
 * [ScrollerGeometry]'s pure conversions (ticket 116): the iOS
 * `RowView.link(at:)` / `positionedLinkElements()` math
 * (`ios:Transcript/RowView.swift:78-90`, `:301-314`), the scroll range and
 * the 0.5 dp scrollable threshold. Plain JUnit 4, no Robolectric: like
 * `RowModelTest`, constructing the generated records touches no JNA and no
 * `android.os`.
 */
class ScrollerGeometryTest {

    /** The ticket's reference scroller: maxX = 600 − 354 = 246. */
    private val s = Scroller(18f, 32f, 354f, 72f, 600f)

    @Test
    fun maxOffsetAndTheScrollableThreshold() {
        assertEquals(246f, ScrollerGeometry.maxOffset(s), 0f)
        assertTrue(ScrollerGeometry.isScrollable(s))

        // iOS maxX > 0.5: 0.5 dp of overflow is not a scroll, 0.6 is.
        assertFalse(ScrollerGeometry.isScrollable(Scroller(0f, 0f, 354f, 10f, 354.5f)))
        assertTrue(ScrollerGeometry.isScrollable(Scroller(0f, 0f, 354f, 10f, 354.6f)))

        // Content narrower than the viewport leaves the range at 0.
        assertEquals(0f, ScrollerGeometry.maxOffset(Scroller(0f, 0f, 354f, 10f, 300f)), 0f)
    }

    @Test
    fun rowToContentMatchesTheIosConversion() {
        // Row → content: (x − s.x + offset, y − s.y).
        assertEquals(Offset(282f, 18f), ScrollerGeometry.rowToContent(200f, 50f, s, 100f))
    }

    @Test
    fun contentToRowMatchesTheIosConversion() {
        // Content → row: (x + s.x − offset, y + s.y).
        assertEquals(
            Rect(218f, 32f, 278f, 52f),
            ScrollerGeometry.contentToRow(Rect(300f, 0f, 360f, 20f), s, 100f),
        )
    }

    @Test
    fun theConversionsRoundTripForEveryOffset() {
        val r = Rect(300f, 5f, 360f, 25f)
        for (off in listOf(0f, 13.5f, 246f)) {
            val rowRect = ScrollerGeometry.contentToRow(r, s, off)
            val topLeft = ScrollerGeometry.rowToContent(rowRect.left, rowRect.top, s, off)
            assertEquals("left at offset $off", r.left, topLeft.x, 1e-4f)
            assertEquals("top at offset $off", r.top, topLeft.y, 1e-4f)
            val bottomRight = ScrollerGeometry.rowToContent(rowRect.right, rowRect.bottom, s, off)
            assertEquals("right at offset $off", r.right, bottomRight.x, 1e-4f)
            assertEquals("bottom at offset $off", r.bottom, bottomRight.y, 1e-4f)
        }
    }

    @Test
    fun theViewportIsTheRustRectInRowCoordinates() {
        assertEquals(Rect(18f, 32f, 372f, 104f), ScrollerGeometry.viewport(s))
        // A degenerate size collapses to an empty rect, never negative.
        assertEquals(
            Rect(0f, 0f, 0f, 0f),
            ScrollerGeometry.viewport(Scroller(0f, 0f, -3f, -4f, 0f)),
        )
    }
}
