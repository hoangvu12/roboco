package sh.roboco.android.design

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shape math of the theme root: clamping, the capsule short-circuit,
 * outline caching and the hairline density conversion.
 *
 * Robolectric: Compose's `Path()` factory needs the Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmoothRoundedShapeTest {

    private val density = Density(1f)

    private fun outline(shape: SmoothRoundedShape, width: Float, height: Float): Outline =
        shape.createOutline(Size(width, height), LayoutDirection.Ltr, density)

    @Test
    fun clampsTheRadiusToHalfTheShorterSide() {
        assertEquals(22f, smoothCornerRadiusPx(100f, 160f, 44f))
        assertEquals(22f, smoothCornerRadiusPx(22f, 160f, 44f))
        assertEquals(12f, smoothCornerRadiusPx(12f, 160f, 64f))
        assertEquals(2.5f, smoothCornerRadiusPx(12f, 10f, 5f))
    }

    @Test
    fun polygonOutlineCoversTheRect() {
        val outline = outline(SmoothRoundedShape(16.dp), 160f, 64f)
        assertTrue("expected a generic (polygon) outline, got $outline", outline is Outline.Generic)
        assertRectWithin(Rect(0f, 0f, 160f, 64f), (outline as Outline.Generic).path.getBounds(), 0.01f)
    }

    @Test
    fun pathVersionMatchesTheOutlineRules() {
        // Below the clamp: a curved path covering the same bounds.
        assertRectWithin(Rect(0f, 0f, 160f, 64f), smoothRoundedPath(160f, 64f, 16f).getBounds(), 0.01f)
        // At the clamp: the exact capsule bounds.
        assertRectWithin(Rect(0f, 0f, 160f, 44f), smoothRoundedPath(160f, 44f, 100f).getBounds(), 0.01f)
    }

    @Test
    fun degenerateSizesFallBackToRectangles() {
        val shape = SmoothRoundedShape(16.dp)
        assertTrue(outline(shape, 0f, 64f) is Outline.Rectangle)
        assertTrue(outline(shape, 160f, 0f) is Outline.Rectangle)
        assertTrue(outline(shape, -1f, 64f) is Outline.Rectangle)
    }

    @Test
    fun equalityAndHashFollowRadiusAndSmoothing() {
        assertEquals(SmoothRoundedShape(16.dp), SmoothRoundedShape(16.dp))
        assertEquals(SmoothRoundedShape(16.dp).hashCode(), SmoothRoundedShape(16.dp).hashCode())
        assertTrue(SmoothRoundedShape(16.dp) != SmoothRoundedShape(20.dp))
        assertTrue(SmoothRoundedShape(16.dp) != SmoothRoundedShape(16.dp, 0.5f))
    }

    @Test
    fun memoisesTheLastOutline() {
        val shape = SmoothRoundedShape(16.dp)
        val first = outline(shape, 160f, 64f)
        val second = outline(shape, 160f, 64f)
        assertSame(first, second)
    }

    @Test
    fun capsuleShortCircuitReturnsTheExactRoundRect() {
        val expected = RoundRect(0f, 0f, 160f, 44f, CornerRadius(22f))
        for (shape in listOf(SmoothRoundedShape(100.dp), SmoothRoundedShape(22.dp))) {
            val outline = outline(shape, 160f, 44f)
            assertTrue("expected a capsule outline, got $outline", outline is Outline.Rounded)
            assertEquals(expected, (outline as Outline.Rounded).roundRect)
        }
    }

    @Test
    fun clampHitOnASquareIsACircle() {
        val outline = outline(SmoothRoundedShape(17.dp), 34f, 34f)
        assertTrue(outline is Outline.Rounded)
        assertEquals(
            RoundRect(0f, 0f, 34f, 34f, CornerRadius(17f)),
            (outline as Outline.Rounded).roundRect,
        )
    }

    @Test
    fun capsuleShapeMatchesTheShortCircuitGeometry() {
        // CapsuleShape (RoundedCornerShape 50%) and SmoothRoundedShape at the
        // clamp produce the same rounded rect, so they render identically.
        val capsule = CapsuleShape.createOutline(Size(160f, 44f), LayoutDirection.Ltr, density)
        assertTrue(capsule is Outline.Rounded)
        val smooth = outline(SmoothRoundedShape(100.dp), 160f, 44f)
        assertTrue(smooth is Outline.Rounded)
        assertEquals(
            (capsule as Outline.Rounded).roundRect,
            (smooth as Outline.Rounded).roundRect,
        )
    }

    @Test
    fun hairlineIsOnePhysicalPixel() {
        for (d in listOf(1f, 2f, 2.625f, 3f)) {
            val hairline = Density(d).hairlineDp().value * d
            assertEquals("density $d", 1f, hairline, 1e-4f)
        }
        // Sub-1 densities clamp at 1 dp (iOS clamps the scale at 1).
        assertEquals(0.75f, Density(0.75f).hairlineDp().value * 0.75f, 1e-4f)
        assertEquals(1f, Density(2f).hairlinePx)
    }

    private fun assertRectWithin(expected: Rect, actual: Rect, tolerance: Float) {
        assertEquals("left", expected.left, actual.left, tolerance)
        assertEquals("top", expected.top, actual.top, tolerance)
        assertEquals("right", expected.right, actual.right, tolerance)
        assertEquals("bottom", expected.bottom, actual.bottom, tolerance)
    }
}
