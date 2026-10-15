package sh.roboco.android.design

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edge-fade math (android-ui-parity #10): the two pure tables of
 * `ios:Design/EdgeFade.swift` — the four-stop horizontal mask
 * (`EdgeFade.mask`, `:6-20`) and the scroll ramp
 * (`FadingScrollView.updateFade`, `:74-86`) — pinned row by row, plus the
 * constants and the [EdgeFade.OverlayStops] table from `EdgeFadeOverlay.restyle`
 * (`:105-109`).
 *
 * Plain JUnit on purpose: the merge check runs it without Robolectric. The
 * rendering side (DST_IN over an offscreen layer) is covered by the pixel
 * tests in `EdgeFadeScreenshotTest`.
 */
class EdgeFadeMathTest {

    // ---------------------------------------------------------- constants

    @Test
    fun constantsMatchIos() {
        // EdgeFade.swift:7 `static let width: CGFloat = 24`
        assertEquals(24.dp, EdgeFade.Width)
        // FadingLabel layoutSubviews:43 — overflow only past `bounds.width + 0.5`
        assertEquals(0.5.dp, EdgeFade.OverflowTolerance)
        // FadingScrollView updateFade:72 — `guard maxX > 0.5`
        assertEquals(0.5.dp, EdgeFade.ScrollThreshold)
    }

    // ------------------------------------------------------- mask stops

    /** width, left, right → a, b (the two opaque-stop locations). */
    private data class MaskRow(
        val width: Float,
        val left: Float,
        val right: Float,
        val a: Float,
        val b: Float,
    )

    @Test
    fun maskStopsMatchTheIosTable() {
        val rows = listOf(
            // EdgeFade.swift:13-16: a = min(left/w, 0.5), b = max(1 − right/w, 0.5).
            MaskRow(width = 200f, left = 0f, right = 24f, a = 0f, b = 0.88f),
            MaskRow(width = 200f, left = 24f, right = 0f, a = 0.12f, b = 1f),
            MaskRow(width = 200f, left = 24f, right = 24f, a = 0.12f, b = 0.88f),
            // 1 − 24/40 = 0.4 → capped at 0.5.
            MaskRow(width = 40f, left = 0f, right = 24f, a = 0f, b = 0.5f),
            MaskRow(width = 40f, left = 24f, right = 24f, a = 0.5f, b = 0.5f),
            // w = max(width, 1) guards the division: w → 1, b → max(1 − 24, 0.5) = 0.5.
            MaskRow(width = 0f, left = 0f, right = 24f, a = 0f, b = 0.5f),
        )
        for (r in rows) {
            val (a, b) = EdgeFade.maskStops(r.width, r.left, r.right)
            assertEquals("opaque start for ${r.width}/${r.left}/${r.right}", r.a, a, 1e-4f)
            assertEquals("opaque end for ${r.width}/${r.left}/${r.right}", r.b, b, 1e-4f)
        }
    }

    @Test
    fun maskStopsNeverLetTheRampsCross() {
        // a ≤ 0.5 ≤ b for any inputs, including fades wider than the slot
        // (EdgeFade.swift:15-16 caps each side at half the width).
        val cases = listOf(
            0f to 0f, 24f to 24f, 400f to 0f, 0f to 400f, 400f to 400f, -8f to 24f,
        )
        for ((left, right) in cases) {
            val (a, b) = EdgeFade.maskStops(200f, left, right)
            assertTrue("a=$a > 0.5 for left=$left", a <= 0.5f)
            assertTrue("b=$b < 0.5 for right=$right", b >= 0.5f)
        }
    }

    @Test
    fun horizontalMaskIsTheFourStopIosGradient() {
        // EdgeFade.swift:10-11: clear → black → black → clear, physical
        // left → right over 0..width. Only alpha matters (it is a mask).
        val (a, b) = EdgeFade.maskStops(200f, 24f, 24f)
        assertEquals(0.12f, a, 1e-4f)
        assertEquals(0.88f, b, 1e-4f)
        assertEquals(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                a to Color.Black,
                b to Color.Black,
                1f to Color.Transparent,
                startX = 0f,
                endX = 200f,
            ),
            EdgeFade.horizontalMask(200f, 24f, 24f),
        )
    }

    // -------------------------------------------------------- scroll ramp

    /** travel x, maxX → start fade, end fade (fw = 24). */
    private data class RampRow(
        val x: Float,
        val maxX: Float,
        val start: Float,
        val end: Float,
    )

    @Test
    fun scrollFadesMatchTheIosRamp() {
        val rows = listOf(
            // EdgeFade.swift:76-79: leading = fw·min(1, max(0, x/fw)),
            // trailing = fw·min(1, max(0, (maxX − x)/fw)).
            RampRow(x = 0f, maxX = 300f, start = 0f, end = 24f),
            RampRow(x = 6f, maxX = 300f, start = 6f, end = 24f),
            RampRow(x = 24f, maxX = 300f, start = 24f, end = 24f),
            RampRow(x = 150f, maxX = 300f, start = 24f, end = 24f),
            RampRow(x = 290f, maxX = 300f, start = 24f, end = 10f),
            RampRow(x = 300f, maxX = 300f, start = 24f, end = 0f),
            // Rubber-band past the start: the clamp keeps the leading fade at 0.
            RampRow(x = -8f, maxX = 300f, start = 0f, end = 24f),
            RampRow(x = 0f, maxX = 10f, start = 0f, end = 10f),
        )
        for (r in rows) {
            val (s, e) = EdgeFade.scrollFades(r.x, r.maxX, 24f)
            assertEquals("start fade at x=${r.x}, maxX=${r.maxX}", r.start, s, 1e-4f)
            assertEquals("end fade at x=${r.x}, maxX=${r.maxX}", r.end, e, 1e-4f)
        }
    }

    @Test
    fun scrollFadesClampPastBothEnds() {
        // Over-scroll either way (iOS bounce, Android stretch): the ramp
        // stays pinned at 0/24 and never goes negative or past the width.
        assertEquals(0f to 24f, EdgeFade.scrollFades(-100f, 300f, 24f))
        assertEquals(24f to 0f, EdgeFade.scrollFades(400f, 300f, 24f))
    }

    @Test
    fun scrollFadesGuardsNonPositiveFadeWidth() {
        assertEquals(0f to 0f, EdgeFade.scrollFades(100f, 300f, 0f))
        assertEquals(0f to 0f, EdgeFade.scrollFades(100f, 300f, -24f))
    }

    // ------------------------------------------------------ mask gating

    @Test
    fun unmeasuredScrollStateIsNeverMasked() {
        // Compose's ScrollState.maxValue is Int.MAX_VALUE before the first
        // measure; iOS has no equivalent because contentSize is set with the
        // layout. Treated as "no mask" (Spec ▸ FadingHorizontalScroll).
        assertFalse(EdgeFade.scrollMasked(Int.MAX_VALUE, 0.5f))
        assertFalse(EdgeFade.scrollMasked(Int.MAX_VALUE, 1000f))
    }

    @Test
    fun scrollMaskedOnlyPastTheThreshold() {
        // maxX ≤ 0.5 px of overflow: no mask (EdgeFade.swift:72). Int px
        // maxValue, threshold 0.5 dp = 0.5 px at mdpi.
        assertFalse(EdgeFade.scrollMasked(0, 0.5f))
        assertTrue(EdgeFade.scrollMasked(1, 0.5f))
        assertTrue(EdgeFade.scrollMasked(454, 0.5f))
    }

    // ---------------------------------------------------- overlay stops

    @Test
    fun overlayStopsAreTheSevenIosPairsExactly() {
        // EdgeFade.swift:106: (0,0) (0.12,0.18) (0.24,0.5) (0.36,0.82)
        // (0.46,0.96) (0.55,1) (1,1) — verbatim.
        assertEquals(
            listOf(
                0f to 0f,
                0.12f to 0.18f,
                0.24f to 0.5f,
                0.36f to 0.82f,
                0.46f to 0.96f,
                0.55f to 1f,
                1f to 1f,
            ),
            EdgeFade.OverlayStops,
        )
    }
}
