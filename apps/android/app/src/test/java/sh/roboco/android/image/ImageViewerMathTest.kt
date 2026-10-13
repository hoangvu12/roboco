package sh.roboco.android.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image viewer's pure maths (ticket 125, [ImageViewerMath.kt]), plain
 * JUnit — no Robolectric, no core: every value is pinned against the
 * ticket's worked examples (the aspect-fit rect, the aspect-fill crop
 * inside a 76 px tile, the four double-tap targets, the zoom
 * rubber-band, the dismiss thresholds), tolerance 1e-3 unless the ticket
 * says otherwise (the fill crop: ±0.01).
 */
class ImageViewerMathTest {

    private val view = Size(400f, 800f)
    private val landscape = Size(1600f, 900f)

    // ------------------------------------------------------------- fit/fill

    @Test
    fun fitRectCentresTheAspectFitRect() {
        // s = min(400/1600, 800/900) = 0.25 → 400 × 225, centred.
        val f = fitRect(landscape, view)
        assertEquals(0f, f.left, 1e-3f)
        assertEquals(287.5f, f.top, 1e-3f)
        assertEquals(400f, f.right, 1e-3f)
        assertEquals(512.5f, f.bottom, 1e-3f)
        assertEquals(400f, f.width, 1e-3f)
        assertEquals(225f, f.height, 1e-3f)
    }

    @Test
    fun fitRectCentresAPortraitToo() {
        val f = fitRect(Size(900f, 1600f), view)
        // s = min(400/900, 800/1600) = 4/9 → 400 × 711.1, letterboxed vertically.
        assertEquals(0f, f.left, 1e-3f)
        assertEquals(44.444f, f.top, 1e-3f)
        assertEquals(400f, f.right, 1e-3f)
        assertEquals(755.556f, f.bottom, 1e-3f)
    }

    @Test
    fun fillRectAspectFillsTheClip() {
        // s = max(76/1600, 76/900) = 76/900 → 135.1 × 76, centred on the tile.
        val f = fillRect(landscape, Rect(0f, 0f, 76f, 76f))
        assertEquals(-29.56f, f.left, 0.01f)
        assertEquals(0f, f.top, 0.01f)
        assertEquals(105.56f, f.right, 0.01f)
        assertEquals(76f, f.bottom, 0.01f)
    }

    @Test
    fun fillRectIsTheClipWhenTheAspectAlreadyMatches() {
        val f = fillRect(landscape, Rect(0f, 287.5f, 400f, 512.5f))
        assertEquals(0f, f.left, 0.01f)
        assertEquals(287.5f, f.top, 0.01f)
        assertEquals(400f, f.right, 0.01f)
        assertEquals(512.5f, f.bottom, 0.01f)
    }

    @Test
    fun lerpRectLerpsEveryEdge() {
        val mid = lerpRect(Rect(0f, 0f, 76f, 76f), Rect(0f, 287.5f, 400f, 512.5f), 0.5f)
        assertEquals(0f, mid.left, 1e-3f)
        assertEquals(143.75f, mid.top, 1e-3f)
        assertEquals(238f, mid.right, 1e-3f)
        assertEquals(294.25f, mid.bottom, 1e-3f)
    }

    // --------------------------------------------------------- pan bounds

    @Test
    fun clampTHoldsTheWholeViewContentLetterboxIncluded() {
        // z = 1 → [0, 0] both axes: nothing scrolls, the drag is free for
        // the transition.
        assertEquals(Offset.Zero, clampT(Offset(40f, 100f), 1f, view))
        // z = 2.5 → Tx ∈ [−600, 0], Ty ∈ [−1200, 0].
        assertEquals(Offset(-600f, -1200f), clampT(Offset(-700f, -1300f), 2.5f, view))
        assertEquals(Offset(0f, 0f), clampT(Offset(30f, 40f), 2.5f, view))
        assertEquals(Offset(-300f, -600f), clampT(Offset(-300f, -600f), 2.5f, view))
    }

    @Test
    fun zoomedRectMapsTheFitRectThroughContentZoomPlusTranslation() {
        // F = (0, 287.5, 400, 512.5); z = 2, T = (−100, −400):
        // R = (−100, 175, 700, 625).
        val r = zoomedRect(fitRect(landscape, view), 2f, Offset(-100f, -400f))
        assertEquals(-100f, r.left, 1e-3f)
        assertEquals(175f, r.top, 1e-3f)
        assertEquals(700f, r.right, 1e-3f)
        assertEquals(625f, r.bottom, 1e-3f)
    }

    // --------------------------------------------------------- double tap

    @Test
    fun doubleTapZoomsTo25CentredOnTheTapPoint() {
        val (z, t) = doubleTapTarget(1f, Offset.Zero, Offset(100f, 200f), view)
        assertEquals(2.5f, z, 1e-3f)
        assertEquals(-50f, t.x, 1e-3f)
        assertEquals(-100f, t.y, 1e-3f)
    }

    @Test
    fun doubleTapClampsTheTargetAtTheEdges() {
        val (zNear, tNear) = doubleTapTarget(1f, Offset.Zero, Offset(10f, 10f), view)
        assertEquals(2.5f, zNear, 1e-3f)
        assertEquals(Offset.Zero, tNear)
        val (zFar, tFar) = doubleTapTarget(1f, Offset.Zero, Offset(390f, 790f), view)
        assertEquals(2.5f, zFar, 1e-3f)
        assertEquals(-600f, tFar.x, 1e-3f)
        assertEquals(-1200f, tFar.y, 1e-3f)
    }

    @Test
    fun doubleTapAtZoomReturnsToIdentity() {
        val (z, t) = doubleTapTarget(3f, Offset(-600f, -1200f), Offset(390f, 790f), view)
        assertEquals(1f, z, 1e-3f)
        assertEquals(Offset.Zero, t)
    }

    // -------------------------------------------------------- rubber band

    @Test
    fun rubberZoomHalfResistancePastBothLimits() {
        assertEquals(0.5f, rubberZoom(0.25f), 1e-3f)
        assertEquals(10f, rubberZoom(20f), 1e-3f)
        assertEquals(3f, rubberZoom(3f), 1e-3f)
        assertEquals(1f, rubberZoom(1f), 1e-3f)
        assertEquals(5f, rubberZoom(5f), 1e-3f)
        // A pinch to 8× shows ≈ 6.32× during the gesture.
        assertEquals(6.3246f, rubberZoom(8f), 1e-3f)
    }

    @Test
    fun rubberBandIsSignPreservingSaturatingAndSlopeCapped() {
        // The slope at 0 is c = 0.55: a tiny overshoot moves at 55 % of
        // finger speed.
        assertEquals(0.55f * 0.01f, rubberBand(0.01f, 800f), 1e-4f)
        // The closed form, x·c·d / (x·c + d).
        assertEquals(0.55f * 800f / (0.55f + 800f), rubberBand(1f, 800f), 1e-3f)
        val big = rubberBand(10_000f, 800f)
        assertTrue("must stay below the dimension ($big)", big < 800f)
        assertEquals(4_400_000f / 6_300f, big, 0.5f)
        // Sign preserving.
        assertEquals(-rubberBand(1f, 800f), rubberBand(-1f, 800f), 1e-3f)
        assertEquals(0f, rubberBand(0f, 800f), 0f)
    }

    // ------------------------------------------------------------ dismiss

    @Test
    fun dismissProgressIsHalfAtHalfTheWindow() {
        assertEquals(0.5f, dismissProgress(200f, 800f), 1e-3f)
        assertEquals(1f, dismissProgress(800f, 800f), 1e-3f)
        assertEquals(1f, dismissProgress(-500f, 800f), 1e-3f)
        assertEquals(0f, dismissProgress(0f, 800f), 1e-3f)
    }

    @Test
    fun shouldDismissCrossesAtTheDistanceOrVelocityThreshold() {
        // density 2.625 (the ticket's example): 100 dp = 262.5 px,
        // 800 dp/s = 2100 px/s.
        val density = 2.625f
        assertTrue(shouldDismiss(101f * density, 0f, density))
        assertFalse(shouldDismiss(99f * density, 799f * density, density))
        assertTrue(shouldDismiss(20f * density, 801f * density, density))
        // Exactly at the thresholds: strictly greater is required.
        assertFalse(shouldDismiss(100f * density, 0f, density))
        assertFalse(shouldDismiss(0f, 800f * density, density))
    }

    @Test
    fun dismissTransformShrinksAboutTheCentreThenOffsets() {
        // p = 1 → k = 0.85 about (200, 400), then the offset.
        val r = dismissTransform(Rect(0f, 287.5f, 400f, 512.5f), Offset(200f, 400f), 1f, Offset(30f, -60f))
        assertEquals(60f, r.left, 1e-3f)      // 200 + (0 − 200)·0.85 + 30
        assertEquals(244.375f, r.top, 1e-3f)  // 400 + (287.5 − 400)·0.85 − 60
        assertEquals(400f, r.right, 1e-3f)    // 200 + (400 − 200)·0.85 + 30
        assertEquals(435.625f, r.bottom, 1e-3f) // 400 + (512.5 − 400)·0.85 − 60
        // p = 0: identity plus the offset.
        val zero = dismissTransform(Rect(0f, 0f, 76f, 76f), Offset(200f, 400f), 0f, Offset(1f, 2f))
        assertEquals(1f, zero.left, 1e-3f)
        assertEquals(2f, zero.top, 1e-3f)
        assertEquals(77f, zero.right, 1e-3f)
        assertEquals(78f, zero.bottom, 1e-3f)
    }
}
