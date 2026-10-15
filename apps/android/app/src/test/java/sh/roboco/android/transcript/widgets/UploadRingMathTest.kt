package sh.roboco.android.transcript.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 67's pure math, on the plain JVM (no Robolectric — nothing here
 * touches Android): the pending-reference predicate over both client
 * families, the diameter rule and its 34 dp cap, the 9/10.5 dp label
 * sizes, the arc floor and clamps, and the half-up rounding of the
 * percentage. Every expected value is a line of `ios:Transcript/RowView.swift:450-505`.
 */
class UploadRingMathTest {

    @Test
    fun thePendingPrefixesMatchBothClientFamilies() {
        // Upstream clients write `pending://` refs (RowView.swift:226).
        assertTrue(isPendingReference("pending://u1/a.png"))
        // Roboco's own echo writes `pending/<uuid>/<name>` (android-app#08).
        assertTrue(isPendingReference("pending/9f1c2a34-5b6d-4e7f-8a9b-0c1d2e3f4a5b/a.png"))
        // Real host paths are absolute — never pending.
        assertFalse(isPendingReference("/Users/x/a.png"))
        assertFalse(isPendingReference("C:\\x\\a.png"))
        assertFalse(isPendingReference("pendingx/a.png"))
        assertFalse(isPendingReference("pending"))
        assertFalse(isPendingReference(""))
    }

    @Test
    fun theDiameterIsCappedAt34Dp() {
        assertEquals(34f, UploadRingMath.diameterDp(76f, 76f), 1e-4f) // 41.8 → 34
        assertEquals(34f, UploadRingMath.diameterDp(260f, 260f), 1e-4f) // 143 → 34
        assertEquals(27.5f, UploadRingMath.diameterDp(50f, 50f), 1e-3f) // 0.55 · 50
        assertEquals(22f, UploadRingMath.diameterDp(40f, 60f), 1e-3f) // the shorter side rules
        assertEquals(34f, UploadRingMath.diameterDp(100f, 400f), 1e-4f) // 55 → 34
    }

    @Test
    fun theLabelSizeFollowsTheDiameter() {
        assertEquals(9f, UploadRingMath.labelPt(27.5f), 1e-6f) // the 50 dp tile
        assertEquals(9f, UploadRingMath.labelPt(29.9f), 1e-6f) // just below the cut
        assertEquals(10.5f, UploadRingMath.labelPt(30f), 1e-6f) // at the cut
        assertEquals(10.5f, UploadRingMath.labelPt(34f), 1e-6f) // the normal ring
    }

    @Test
    fun theSweepIsClampedToTheFloorAndOne() {
        assertEquals(7.2f, UploadRingMath.sweepDegrees(0.0), 1e-3f) // the floor stub
        assertEquals(7.2f, UploadRingMath.sweepDegrees(-1.0), 1e-3f) // below 0
        assertEquals(7.2f, UploadRingMath.sweepDegrees(Double.NaN), 1e-3f) // NaN is 0
        assertEquals(180f, UploadRingMath.sweepDegrees(0.5), 1e-3f)
        assertEquals(360f, UploadRingMath.sweepDegrees(2.0), 1e-3f) // above 1
        assertEquals(151.2f, UploadRingMath.sweepDegrees(0.42), 1e-3f) // the golden's arc
    }

    @Test
    fun thePercentRoundsHalfAwayFromZeroLikeSwiftRounded() {
        assertEquals(0, UploadRingMath.percent(0.0))
        assertEquals(0, UploadRingMath.percent(0.004)) // 0.4 → 0
        assertEquals(1, UploadRingMath.percent(0.005)) // 0.5 → 1 (half up)
        assertEquals(13, UploadRingMath.percent(0.125)) // 12.5 → 13, not .toInt()'s 12
        assertEquals(42, UploadRingMath.percent(0.42))
        assertEquals(100, UploadRingMath.percent(1.7)) // clamped
        assertEquals(0, UploadRingMath.percent(-0.3)) // clamped
        assertEquals(0, UploadRingMath.percent(Double.NaN))
    }

    @Test
    fun theLabelAppendsThePercentSign() {
        assertEquals("0%", UploadRingMath.label(0.0))
        assertEquals("0%", UploadRingMath.label(0.004))
        assertEquals("1%", UploadRingMath.label(0.005))
        assertEquals("13%", UploadRingMath.label(0.125))
        assertEquals("42%", UploadRingMath.label(0.42))
        assertEquals("100%", UploadRingMath.label(1.7))
        assertEquals("0%", UploadRingMath.label(-0.3))
    }

    @Test
    fun theImplicitActionTweenIs250Ms() {
        // Core Animation's implicit `strokeEnd` action (the default
        // media timing function's control points live in SweepEasing).
        assertEquals(250, UploadRingMath.SWEEP_MS)
        assertEquals(0.02, UploadRingMath.MIN_ARC, 0.0)
    }
}
