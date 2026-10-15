package sh.roboco.android.newsession

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The handoff geometry (android-ui-parity #95, `HandoffGeometry.kt`) —
 * the pure frame math of `ios:Session/DraftHandoff.swift` at density 3,
 * the ticket's worked values. Plain JUnit: no Android types beyond the
 * geometry value classes.
 */
class HandoffGeometryTest {

    private val d = 3f

    // ------------------------------------------------------------ the bubble

    /** The start frame: the text slot outset by the 15/10 bubble padding. */
    @Test
    fun bubbleStartRectOutsetsTheTextByThePadding() {
        assertEquals(
            Rect(55f, 1970f, 1045f, 2093f),
            bubbleStartRect(Rect(100f, 2000f, 1000f, 2063f), d),
        )
    }

    /** The inner rect: inset 45 px horizontally, 30 px vertically. */
    @Test
    fun bubbleInnerInsetsFortyFiveAndThirty() {
        assertEquals(
            Rect(145f, 2000f, 1000f, 2063f),
            bubbleInner(Rect(100f, 1970f, 1045f, 2093f), d),
        )
    }

    /** The poll fallback: clamped width, 16 dp from the right, 60 dp under the top. */
    @Test
    fun fallbackBubbleTargetClampsTheWidthAndPinsTheCorner() {
        val target = fallbackBubbleTarget(
            start = Rect(0f, 0f, 1000f, 120f),
            rootWidth = 1179f,
            safeTop = 162f,
            d = d,
        )
        // w = min(1000, 0.8·1179) = 943.2 → x = 1179 − 48 − 943.2 = 187.8.
        assertEquals(943.2f, target.width, 0.01f)
        assertEquals(187.8f, target.left, 0.01f)
        assertEquals(1131f, target.right, 0.01f)
        assertEquals(342f, target.top, 1e-3f)
        assertEquals(462f, target.bottom, 1e-3f)
        // A bubble narrower than 0.8·W keeps its own width.
        val narrow = fallbackBubbleTarget(start = Rect(0f, 0f, 200f, 120f), rootWidth = 1179f, safeTop = 162f, d = d)
        assertEquals(200f, narrow.width, 1e-3f)
        assertEquals(1131f, narrow.right, 1e-3f)
    }

    // ------------------------------------------------------------- the glide

    /** The target: `from`'s size, bottom-aligned on the live rect. */
    @Test
    fun glideTargetKeepsTheSizeAndAlignsTheBottom() {
        assertEquals(
            Rect(36f, 2090f, 1143f, 2450f),
            glideTarget(live = Rect(36f, 2300f, 1143f, 2450f), from = Rect(36f, 1500f, 1143f, 1860f)),
        )
    }

    /** One frame: component-wise interpolation; the size never changes. */
    @Test
    fun glideRectInterpolatesEveryEdge() {
        val from = Rect(36f, 1500f, 1143f, 1860f)
        val to = Rect(48f, 2376f, 1155f, 2736f)
        val half = glideRect(from, to, 0.5f)
        assertEquals((36f + 48f) / 2f, half.left, 1e-3f)
        assertEquals((1500f + 2376f) / 2f, half.top, 1e-3f)
        assertEquals((1107f + 1107f) / 2f, half.width, 1e-3f)
        assertEquals((360f + 360f) / 2f, half.height, 1e-3f)
        assertEquals(half.left + half.width, half.right, 1e-3f)
        // e = 0 and e = 1 are the endpoints themselves.
        assertEquals(from, glideRect(from, to, 0f))
        assertEquals(to, glideRect(from, to, 1f))
    }

    /** The eased progress: 0.9375 at half, 1 at and after the duration. */
    @Test
    fun glideProgressIsQuarticEaseOut() {
        val durationMs = 550
        assertEquals(0f, glideProgress(0L, durationMs), 1e-4f)
        assertEquals(0.9375f, glideProgress(durationMs * 1_000_000L / 2, durationMs), 1e-4f)
        assertEquals(1f, glideProgress(durationMs * 1_000_000L, durationMs), 1e-4f)
        assertEquals(1f, glideProgress(durationMs * 1_000_000L * 2, durationMs), 1e-4f)
    }

    // ------------------------------------------------------------ the flight

    /** The flight lerp: every edge interpolated, `p` never clamped. */
    @Test
    fun flightRectLerpsWithoutClamping() {
        val start = Rect(55f, 1570f, 745f, 1680f)
        val dest = Rect(187.8f, 342f, 1131f, 462f)
        val mid = flightRect(start, dest, 0.5f)
        assertEquals((55f + 187.8f) / 2f, mid.left, 1e-3f)
        assertEquals((1570f + 342f) / 2f, mid.top, 1e-3f)
        assertEquals((690f + 943.2f) / 2f, mid.width, 1e-3f)
        assertEquals((110f + 120f) / 2f, mid.height, 1e-3f)
        assertEquals(start, flightRect(start, dest, 0f))
        assertEquals(dest.left, flightRect(start, dest, 1f).left, 1e-3f)
        assertEquals(dest.top, flightRect(start, dest, 1f).top, 1e-3f)
        assertEquals(dest.width, flightRect(start, dest, 1f).width, 1e-3f)
        assertEquals(dest.height, flightRect(start, dest, 1f).height, 1e-3f)
        // An overshooting progress overshoots the rect (ζ 0.86 flight).
        val over = flightRect(start, dest, 1.01f)
        assertEquals(dest.left + 0.01f * (dest.left - start.left), over.left, 1e-3f)
        assertEquals(dest.width + 0.01f * (dest.width - start.width), over.width, 1e-3f)
    }
}
