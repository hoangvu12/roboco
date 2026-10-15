package sh.roboco.android.design.sheet

import android.graphics.Region
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `SmoothTopRoundedShape` (ticket 17) under Robolectric: the acceptance
 * geometry — bounds (0, 0, 360, 400) ± 0.01 at 26 dp radius and density 1,
 * the top corners rounded off (a `Region` from the outline path excludes
 * (1, 1) and (359, 1)) and the bottom corners square (contains (1, 399)
 * and (359, 399)). The rest pins the radius clamp and the
 * equality contract ticket 31's `PageSheet` relies on when it passes 38.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SheetPartsTest {

    private val density: Density = Density(1f)
    private val rtl = LayoutDirection.Ltr

    private fun outline(radius: Int, width: Float = 360f, height: Float = 400f): Outline =
        SmoothTopRoundedShape(radius.dp).createOutline(Size(width, height), rtl, density)

    private fun region(outline: Outline): Region {
        val path = (outline as Outline.Generic).path.asAndroidPath()
        return Region().apply { setPath(path, Region(-10_000, -10_000, 10_000, 10_000)) }
    }

    @Test
    fun shapeMatchesTheAcceptanceGeometry() {
        val outline = outline(26)
        // Bounds ± 0.01: Rect(0, 0, 360, 400).
        assertEquals(Rect(0f, 0f, 360f, 400f), outline.bounds)
        val shape = region(outline)
        // Top corners rounded off…
        assertFalse(shape.contains(1, 1))
        assertFalse(shape.contains(359, 1))
        // …bottom corners square, and the middle of the sheet is solid.
        assertTrue(shape.contains(1, 399))
        assertTrue(shape.contains(359, 399))
        assertTrue(shape.contains(180, 200))
        // The top edge between the corners exists.
        assertTrue(shape.contains(180, 0))
    }

    @Test
    fun boundsAreExactToAHundredth() {
        val bounds = outline(26).bounds
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(360f, bounds.right, 0.01f)
        assertEquals(400f, bounds.bottom, 0.01f)
    }

    @Test
    fun zeroRadiusIsAPlainRectangle() {
        val outline = outline(0)
        assertTrue(outline is Outline.Rectangle)
        assertEquals(Rect(0f, 0f, 360f, 400f), outline.bounds)
    }

    @Test
    fun radiusClampsToHalfTheWidthAndTheHeight() {
        // min(200, 360/2, 400) = 180: the top corners take the whole width.
        val outline = outline(200)
        val shape = region(outline)
        assertFalse("top corners must still be rounded", shape.contains(1, 1))
        assertTrue(shape.contains(1, 399))
        // A tall narrow sheet: min(26, 50/2, 60) = 25.
        val narrow = region(outline(26, width = 50f, height = 60f))
        assertFalse(narrow.contains(1, 1))
        assertTrue(narrow.contains(1, 59))
    }

    @Test
    fun sheetConstantsMatchTheTicketTable() {
        assertEquals(26.dp, GlassSheetDefaults.CornerRadius)
        assertEquals(10.dp, GlassSheetDefaults.LargeTopGap)
        assertEquals(0.5f, GlassSheetDefaults.MediumFraction, 0f)
        assertEquals(52.dp, GlassSheetDefaults.HeaderHeight)
        assertEquals(5.dp, GlassSheetDefaults.GrabberTop)
        assertEquals(1200.dp, GlassSheetDefaults.FlingVelocity)
        assertEquals(0.25f, GlassSheetDefaults.DismissFraction, 0f)
        assertEquals(0.5f, GlassSheetDefaults.DetentFraction, 0f)
        assertEquals(0.25f, GlassSheetDefaults.ScrimAlphaLight, 0f)
        assertEquals(0.45f, GlassSheetDefaults.ScrimAlphaDark, 0f)
        assertEquals(0.25f, GlassSheetDefaults.PredictiveBackTravel, 0f)
    }

    @Test
    fun equalityCoversRadiusAndSmoothing() {
        assertEquals(SmoothTopRoundedShape(26.dp), SmoothTopRoundedShape(26.dp))
        assertEquals(SmoothTopRoundedShape(26.dp).hashCode(), SmoothTopRoundedShape(26.dp).hashCode())
        assertNotEquals(SmoothTopRoundedShape(26.dp), SmoothTopRoundedShape(38.dp))
        assertNotEquals(SmoothTopRoundedShape(26.dp), SmoothTopRoundedShape(26.dp, 0.3f))
    }
}
