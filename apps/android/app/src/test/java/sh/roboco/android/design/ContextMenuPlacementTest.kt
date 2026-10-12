package sh.roboco.android.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context-menu placement maths (android-ui-parity #16,
 * `ContextMenuHost.kt`'s `placeContextMenu`): below → above → overlap, the
 * touch side's horizontal edge, the safe-area clamp and the growth origin.
 * Pure pixel maths — plain JVM, no Robolectric.
 *
 * All fixtures are the ticket's worked example (px = dp at density 1):
 * `safe = Rect(8, 32, 403, 851)`, menu `250 × 168`, gap 8 — plus a
 * full-width preview row.
 */
class ContextMenuPlacementTest {

    private val safe = Rect(8f, 32f, 403f, 851f)
    private val menu = IntSize(250, 168)
    private val gap = 8f

    @Test
    fun belowFitsWhenThePreviewIsHigh() {
        val preview = Rect(8f, 100f, 403f, 162f)
        val placement = placeContextMenu(preview, Offset(100f, 130f), menu, safe, gap)
        assertTrue("a high preview opens below", placement.below)
        assertFalse("a touch left of the centre aligns the left edges", placement.alignEnd)
        assertEquals(8, placement.offset.x) // preview.left
        assertEquals(170, placement.offset.y) // 162 + 8
        assertEquals(681, placement.maxHeight) // 851 − 170, the room below
        assertEquals(TransformOrigin(0f, 0f), placement.origin) // grows from the top-left
    }

    @Test
    fun aTouchOnTheRightHalfAlignsTheRightEdges() {
        val preview = Rect(8f, 100f, 403f, 162f)
        val placement = placeContextMenu(preview, Offset(300f, 130f), menu, safe, gap)
        assertTrue(placement.below)
        assertTrue("a touch right of the centre aligns the right edges", placement.alignEnd)
        assertEquals(153, placement.offset.x) // 403 − 250: right edges equal
        assertEquals(170, placement.offset.y)
        assertEquals(TransformOrigin(1f, 0f), placement.origin) // grows from the top-right
    }

    @Test
    fun onlyAboveFitsWhenThePreviewIsLow() {
        val preview = Rect(8f, 760f, 403f, 822f)
        val placement = placeContextMenu(preview, Offset(300f, 790f), menu, safe, gap)
        // below fails: 822 + 8 + 168 = 998 > 851.
        assertFalse("a low preview opens above", placement.below)
        assertTrue(placement.alignEnd)
        assertEquals(153, placement.offset.x) // 403 − 250
        assertEquals(584, placement.offset.y) // 760 − 8 − 168
        assertEquals(720, placement.maxHeight) // 760 − 8 − 32, the room above
        assertEquals(TransformOrigin(1f, 1f), placement.origin) // grows from the bottom-right
    }

    @Test
    fun neitherFitsOverlapsAtTheTouch() {
        // A row taller than the screen: below fails (851 + 8 + 168 > 851),
        // above fails (32 − 8 − 168 < 32) → the menu overlaps at the touch.
        val preview = Rect(8f, 32f, 403f, 851f)
        val placement = placeContextMenu(preview, Offset(60f, 400f), menu, safe, gap)
        assertTrue("overlap reports below (the panel grows downward)", placement.below)
        assertFalse(placement.alignEnd)
        assertEquals(8, placement.offset.x) // preview.left, the touch is on the left half
        assertEquals(400, placement.offset.y) // the touch itself, inside safe
        assertEquals(819, placement.maxHeight) // safe.height
    }

    @Test
    fun theOverlapClampsTheTouchIntoSafe() {
        val preview = Rect(8f, 32f, 403f, 851f)
        // A touch above safe.top clamps to safe.top.
        val high = placeContextMenu(preview, Offset(60f, 5f), menu, safe, gap)
        assertEquals(32, high.offset.y)
        // A touch below safe.bottom − menu.height clamps to that ceiling.
        val low = placeContextMenu(preview, Offset(60f, 900f), menu, safe, gap)
        assertEquals(683, low.offset.y) // 851 − 168
    }

    @Test
    fun aMenuWiderThanTheRemainingSpaceClampsToSafeLeft() {
        val wide = IntSize(500, 168)
        val preview = Rect(8f, 100f, 403f, 162f)
        val leftTouch = placeContextMenu(preview, Offset(100f, 130f), wide, safe, gap)
        assertEquals(8, leftTouch.offset.x)
        val rightTouch = placeContextMenu(preview, Offset(300f, 130f), wide, safe, gap)
        assertEquals("403 − 500 clamps into [8, max(8, −97)]", 8, rightTouch.offset.x)
    }

    @Test
    fun aMenuTallerThanSafeGetsTheSafeHeightAndSafeTop() {
        val tall = IntSize(250, 900)
        val preview = Rect(8f, 100f, 403f, 162f)
        val placement = placeContextMenu(preview, Offset(100f, 130f), tall, safe, gap)
        // below fails (162 + 8 + 900 > 851), above fails (100 − 8 − 900 < 32)
        // → overlap, and the ceiling collapses to safe.top.
        assertTrue(placement.below)
        assertEquals(32, placement.offset.y) // safe.top
        assertEquals(819, placement.maxHeight) // safe.height
    }

    @Test
    fun theGapIsMeasuredFromTheGivenPreviewBottom() {
        // placeContextMenu receives the *scaled* (lifted) rect from the
        // overlay and never re-scales it; the menu top sits bottom + gap.
        // Here the scaled rect is 400 × 200 (bottom 200), so the menu top is
        // 208 — the scaled preview, not the rest rect, sets the gap.
        val scaled = Rect(0f, 0f, 400f, 200f)
        val placement = placeContextMenu(scaled, Offset(50f, 100f), menu, safe, gap)
        assertTrue(placement.below)
        assertEquals(208, placement.offset.y) // 200 + 8
    }
}
