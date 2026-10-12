package sh.roboco.android.design.glass

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menu placement maths (android-ui-parity #15, `GlassMenuPlacement.kt`):
 * the cover placement, the corner alignment, the margin clamp, the
 * room-based direction and `forceBelow`, and the gap mode. Pure pixel
 * maths — plain JVM, no Robolectric.
 *
 * The window is 1000 × 2000 with a 12 px margin throughout; the panel is
 * 250 × 100 unless a test says otherwise.
 */
class GlassMenuPlacementTest {

    private val bounds = IntRect(0, 0, 1000, 2000)
    private val margin = 12
    private val panel = IntSize(250, 100)

    @Test
    fun aTopRightAnchorOpensBelowWithTheTopRightCornersEqual() {
        val anchor = IntRect(700, 50, 760, 94)
        val placement = placeGlassMenu(anchor, panel, bounds, margin)
        assertTrue("top-right quadrant opens below", placement.below)
        assertTrue("the panel's right edge aligns to the anchor's", placement.alignEnd)
        assertEquals(510, placement.offset.x) // 760 − 250: right edges equal
        assertEquals(50, placement.offset.y) // the panel's top = the anchor's top
        assertEquals(760, placement.offset.x + panel.width) // top-right corner = anchor's
    }

    @Test
    fun aBottomLeftAnchorOpensAboveWithTheBottomLeftCornersEqual() {
        val tall = IntSize(250, 200)
        val anchor = IntRect(20, 1850, 80, 1894)
        val placement = placeGlassMenu(anchor, tall, bounds, margin)
        assertFalse("the panel does not fit below a low anchor", placement.below)
        assertFalse(placement.alignEnd)
        assertEquals(20, placement.offset.x) // left edges equal
        assertEquals(1894 - 200, placement.offset.y) // bottom edges equal
        assertEquals(1894, placement.offset.y + 200)
    }

    @Test
    fun anAnchorAtTheEdgeIsClampedToTheMargin() {
        val anchor = IntRect(0, 0, 50, 44)
        val placement = placeGlassMenu(anchor, panel, bounds, margin)
        assertEquals(12, placement.offset.x)
        assertEquals(12, placement.offset.y)
    }

    @Test
    fun whenNeitherSideFitsTheLargerSideWinsAndMaxHeightIsTheRoom() {
        val tall = IntSize(250, 300)
        val shortBounds = IntRect(0, 0, 1000, 400)
        // roomBelow 238 vs roomAbove 182: below wins.
        val belowWins = placeGlassMenu(IntRect(0, 150, 200, 194), tall, shortBounds, margin)
        assertTrue(belowWins.below)
        assertEquals(238, belowWins.maxHeight) // 388 − 150
        // roomBelow 138 vs roomAbove 282: above wins.
        val aboveWins = placeGlassMenu(IntRect(0, 250, 200, 294), tall, shortBounds, margin)
        assertFalse(aboveWins.below)
        assertEquals(282, aboveWins.maxHeight) // 294 − 12
    }

    @Test
    fun thePanelFitsInsideTheRoomWhenTheContentIsTaller() {
        val tall = IntSize(250, 300)
        val shortBounds = IntRect(0, 0, 1000, 400)
        val placement = placeGlassMenu(IntRect(0, 150, 200, 194), tall, shortBounds, margin)
        assertEquals(150, placement.offset.y)
        assertEquals(150 + 238, placement.offset.y + minOf(300, placement.maxHeight))
    }

    @Test
    fun withCoverFalseThePanelSitsTheGapBelowTheAnchor() {
        val anchor = IntRect(100, 100, 200, 144)
        val placement = placeGlassMenu(anchor, panel, bounds, margin, cover = false, gap = 8)
        assertTrue(placement.below)
        assertEquals(100, placement.offset.x)
        assertEquals(152, placement.offset.y) // anchor.bottom + 8
    }

    @Test
    fun withCoverFalseThePanelSitsTheGapAboveTheAnchorWhenBelowDoesNotFit() {
        val tall = IntSize(250, 200)
        val anchor = IntRect(100, 1800, 200, 1844)
        val placement = placeGlassMenu(anchor, tall, bounds, margin, cover = false, gap = 8)
        assertFalse(placement.below)
        assertEquals((1800 - 8) - 200, placement.offset.y)
        assertEquals(1800 - 8, placement.offset.y + 200)
    }

    @Test
    fun forceBelowKeepsThePanelBelowEvenWhenItGrowsTallerThanTheRoom() {
        val tall = IntSize(250, 400)
        val anchor = IntRect(100, 1800, 200, 1844)
        val unforced = placeGlassMenu(anchor, tall, bounds, margin)
        assertFalse("without force, the room above wins", unforced.below)
        val forced = placeGlassMenu(anchor, tall, bounds, margin, forceBelow = true)
        assertTrue(forced.below)
        assertEquals(1800, forced.offset.y) // the panel's top stays at the anchor
        assertEquals(188, forced.maxHeight) // 1988 − 1800
    }

    @Test
    fun aPointAnchorWorksWithTheSameMaths() {
        val point = IntRect(600, 100, 600, 100)
        val placement = placeGlassMenu(point, panel, bounds, margin)
        assertTrue(placement.alignEnd) // centre 600 > 500
        assertEquals(600 - 250, placement.offset.x)
        assertEquals(100, placement.offset.y)
    }

    @Test
    fun theGrowthOriginIsTheCornerNearestTheAnchor() {
        val topRight = placeGlassMenu(IntRect(700, 50, 760, 94), panel, bounds, margin)
        assertEquals(TransformOrigin(1f, 0f), topRight.origin)
        val bottomLeft = placeGlassMenu(IntRect(20, 1850, 80, 1894), IntSize(250, 200), bounds, margin)
        assertEquals(TransformOrigin(0f, 1f), bottomLeft.origin)
        val topLeft = placeGlassMenu(IntRect(20, 50, 80, 94), panel, bounds, margin)
        assertEquals(TransformOrigin(0f, 0f), topLeft.origin)
    }

    @Test
    fun maxHeightIsNeverZeroForAnAnchorAtTheVeryBottom() {
        val anchor = IntRect(100, 1990, 200, 2000)
        val placement = placeGlassMenu(anchor, panel, bounds, margin, forceBelow = true)
        assertTrue(placement.below)
        assertEquals(1, placement.maxHeight) // max(1, −2) → clamped floor
    }

    @Test
    fun theFunctionTakesNoLayoutDirectionParameter() {
        // RTL only mirrors the content inside the panel; the placement is
        // physical. The JVM signature (mangled for the IntSize value class)
        // must not carry a direction parameter.
        val methods = Class.forName("sh.roboco.android.design.glass.GlassMenuPlacementKt")
            .methods
            .filter { it.name.substringBefore('-') == "placeGlassMenu" }
        assertTrue("placeGlassMenu not found", methods.isNotEmpty())
        assertTrue(
            "a direction parameter leaked into the placement signature",
            methods.all { method -> method.parameterTypes.none { it.simpleName == "LayoutDirection" } },
        )
    }
}
