package sh.roboco.android.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ComposerGeometry] — android-ui-parity #55's acceptance criteria, the
 * plain-JVM tier (no Robolectric runner): every cell of the ticket's
 * worked fixture table at `W = 366` (a 390 dp phone minus the 12 + 12
 * session margins), `aw = 34`, `thumbsH = 0`, `density = 1`, to ±0.01:
 *
 * | | `p = 0`, `textH = 50` | `p = 0.5`, `textH = 48` | `p = 1`, `textH = 46` | `p = 1`, `textH = 46`, `thumbsH = 68` |
 * |---|---|---|---|---|
 * | H | 50 | 73 | 96 | 164 |
 * | radius | 25 | 25.5 | 26 | 26 |
 * | attach (x, y) | (8, 8) | (9, 31) | (10, 54) | (10, 122) |
 * | action (x, y, w) | (324, 8, 34) | (323, 31, 34) | (322, 54, 34) | (322, 122, 34) |
 * | text (x, y, w, h) | (52, 0, 264, 50) | (34, 0, 299, 48) | (16, 0, 334, 46) | (16, 68, 334, 46) |
 * | placeholder origin | (52, 14) | (34, 14) | (16, 14) | (16, 82) |
 * | toolbar band (y, h) | (50, 0) | (48, 25) | (46, 50) | (114, 50) |
 * | chips (x, y, w) | (50, 33, 266) | (51, 43.5, 264) | (52, 54, 262) | (52, 122, 262) |
 * | chips clip (top, bottom) | (17, 17) | (4.5, 29.5) | (−8, 42) | (−8, 42) |
 * | chip alpha | 0 | 0.5 | 1 | 1 |
 * | thumbs (x, y, w, h) | (12, 0, 342, 0) | (12, 0, 342, 0) | (12, 0, 342, 0) | (12, 0, 342, 68) |
 *
 * plus the `p = 1` column again at `density = 2.625` (every px value ×
 * 2.625, the radius still dp), the ±0.5 % spring overshoot clamps, the
 * degenerate `W = 80` phone (no negative sizes) and [ComposerGeometry.textWidth]
 * agreeing with `frames().text.width` at every progress.
 */
class ComposerGeometryTest {

    private fun frames(
        p: Float,
        textH: Float,
        thumbsH: Float = 0f,
        w: Float = 366f,
        aw: Float = 34f,
        d: Float = 1f,
    ): ComposerFrames = ComposerGeometry.frames(w, thumbsH, textH, aw, p, d)

    private fun assertRect(what: String, r: Rect, x: Float, y: Float, w: Float, h: Float) {
        assertEquals("$what left", x, r.left, 0.01f)
        assertEquals("$what top", y, r.top, 0.01f)
        assertEquals("$what width", w, r.width, 0.01f)
        assertEquals("$what height", h, r.height, 0.01f)
    }

    private fun assertClip(what: String, f: ComposerFrames, top: Float, bottom: Float) {
        assertEquals("$what local top", top, f.toolbar.top - f.chips.top, 0.01f)
        assertEquals("$what local bottom", bottom, f.toolbar.bottom - f.chips.top, 0.01f)
    }

    // ------------------------------------------------------------- p = 0

    @Test
    fun p0MatchesTheWorkedTable() {
        val f = frames(p = 0f, textH = 50f)
        assertEquals("surface width", 366f, f.width, 0.01f)
        assertEquals("H", 50f, f.height, 0.01f)
        assertEquals("radius", 25f, f.cornerRadiusDp, 0.01f)
        assertRect("attach", f.attach, 8f, 8f, 34f, 34f)
        assertRect("action", f.action, 324f, 8f, 34f, 34f)
        assertRect("text", f.text, 52f, 0f, 264f, 50f)
        assertEquals("placeholder origin", Offset(52f, 14f), f.placeholderOrigin)
        assertRect("toolbar band", f.toolbar, 0f, 50f, 366f, 0f)
        assertRect("chips", f.chips, 50f, 33f, 266f, 34f)
        assertClip("chips clip", f, 17f, 17f)
        assertEquals("chip alpha", 0f, f.chipAlpha, 1e-6f)
        assertRect("thumbs", f.thumbs, 12f, 0f, 342f, 0f)
    }

    // ----------------------------------------------------------- p = 0.5

    @Test
    fun p05MatchesTheWorkedTable() {
        val f = frames(p = 0.5f, textH = 48f)
        assertEquals("H", 73f, f.height, 0.01f)
        assertEquals("radius", 25.5f, f.cornerRadiusDp, 0.01f)
        assertRect("attach", f.attach, 9f, 31f, 34f, 34f)
        assertRect("action", f.action, 323f, 31f, 34f, 34f)
        assertRect("text", f.text, 34f, 0f, 299f, 48f)
        assertEquals("placeholder origin", Offset(34f, 14f), f.placeholderOrigin)
        assertRect("toolbar band", f.toolbar, 0f, 48f, 366f, 25f)
        assertRect("chips", f.chips, 51f, 43.5f, 264f, 34f)
        assertClip("chips clip", f, 4.5f, 29.5f)
        assertEquals("chip alpha", 0.5f, f.chipAlpha, 1e-6f)
        assertRect("thumbs", f.thumbs, 12f, 0f, 342f, 0f)
    }

    // ------------------------------------------------------------- p = 1

    @Test
    fun p1MatchesTheWorkedTable() {
        val f = frames(p = 1f, textH = 46f)
        assertEquals("H", 96f, f.height, 0.01f)
        assertEquals("radius", 26f, f.cornerRadiusDp, 0.01f)
        assertRect("attach", f.attach, 10f, 54f, 34f, 34f)
        assertRect("action", f.action, 322f, 54f, 34f, 34f)
        assertRect("text", f.text, 16f, 0f, 334f, 46f)
        assertEquals("placeholder origin", Offset(16f, 14f), f.placeholderOrigin)
        assertRect("toolbar band", f.toolbar, 0f, 46f, 366f, 50f)
        assertRect("chips", f.chips, 52f, 54f, 262f, 34f)
        assertClip("chips clip", f, -8f, 42f)
        assertEquals("chip alpha", 1f, f.chipAlpha, 1e-6f)
        assertRect("thumbs", f.thumbs, 12f, 0f, 342f, 0f)
    }

    @Test
    fun p1WithThumbsMatchesTheWorkedTable() {
        val f = frames(p = 1f, textH = 46f, thumbsH = 68f)
        assertEquals("H", 164f, f.height, 0.01f)
        assertEquals("radius", 26f, f.cornerRadiusDp, 0.01f)
        assertRect("attach", f.attach, 10f, 122f, 34f, 34f)
        assertRect("action", f.action, 322f, 122f, 34f, 34f)
        assertRect("text", f.text, 16f, 68f, 334f, 46f)
        assertEquals("placeholder origin", Offset(16f, 82f), f.placeholderOrigin)
        assertRect("toolbar band", f.toolbar, 0f, 114f, 366f, 50f)
        assertRect("chips", f.chips, 52f, 122f, 262f, 34f)
        assertClip("chips clip", f, -8f, 42f)
        assertEquals("chip alpha", 1f, f.chipAlpha, 1e-6f)
        assertRect("thumbs", f.thumbs, 12f, 0f, 342f, 68f)
    }

    // --------------------------------------------------- p = 1 at 2.625x

    @Test
    fun p1AtDensity2625ScalesEveryPx() {
        // The same 366 dp phone at density 2.625 (xhdpi+): every px input
        // is the dp fixture x 2.625, so every px output is too (the radius
        // stays dp).
        val k = 2.625f
        val f = frames(p = 1f, textH = 46f * k, w = 366f * k, aw = 34f * k, d = k)
        assertEquals("H", 252f, f.height, 0.01f)
        // The radius is dp: 26 at any density.
        assertEquals("radius", 26f, f.cornerRadiusDp, 0.01f)
        assertRect("attach", f.attach, 26.25f, 141.75f, 89.25f, 89.25f)
        assertRect("action", f.action, 845.25f, 141.75f, 89.25f, 89.25f)
        assertRect("text", f.text, 42f, 0f, 876.75f, 120.75f)
        assertEquals("placeholder origin", Offset(42f, 36.75f), f.placeholderOrigin)
        assertRect("toolbar band", f.toolbar, 0f, 120.75f, 960.75f, 131.25f)
        assertRect("chips", f.chips, 136.5f, 141.75f, 687.75f, 89.25f)
        assertClip("chips clip", f, -21f, 110.25f)
        assertEquals("chip alpha", 1f, f.chipAlpha, 1e-6f)
        assertRect("thumbs", f.thumbs, 31.5f, 0f, 897.75f, 0f)
    }

    // -------------------------------------------------- the overshoots

    @Test
    fun positiveOvershootExtrapolatesPositionsAndClampsAlpha() {
        // p = 1.005 (the 0.86 spring overshoots by ~0.5 %): the band keeps
        // growing, the alpha stays 1 and the radius stays clamped.
        val f = frames(p = 1.005f, textH = 46f)
        assertEquals("toolbar height", 50.25f, f.toolbar.height, 0.01f)
        assertEquals("H", 96.25f, f.height, 0.01f)
        assertEquals("chip alpha", 1f, f.chipAlpha, 1e-6f)
        assertEquals("radius", 26f, f.cornerRadiusDp, 0.01f)
        // Positions extrapolate: attach x = lerp(8, 10, 1.005) = 10.01.
        assertEquals("attach x", 10.01f, f.attach.left, 0.01f)
    }

    @Test
    fun negativeOvershootClampsTheBandAndAlpha() {
        val f = frames(p = -0.005f, textH = 50f)
        assertEquals("toolbar height", 0f, f.toolbar.height, 0.01f)
        assertEquals("chip alpha", 0f, f.chipAlpha, 1e-6f)
        assertEquals("radius", 25f, f.cornerRadiusDp, 0.01f)
        assertEquals("H", 50f, f.height, 0.01f)
    }

    // -------------------------------------------------- the W = 80 phone

    @Test
    fun degenerateWidthNeverProducesNegativeSizes() {
        // W = 80, aw = 34: the capsule's text frame is empty and the chip
        // strip collapses to zero; the card still has 48 dp of text.
        val capsule = frames(p = 0f, textH = 50f, w = 80f)
        assertEquals("capsule textW", 0f, capsule.text.width, 0.01f)
        assertEquals("capsule chipW", 0f, capsule.chips.width, 0.01f)
        assertEquals("capsule H", 50f, capsule.height, 0.01f)
        val card = frames(p = 1f, textH = 46f, w = 80f)
        assertEquals("card textW", 48f, card.text.width, 0.01f)
        assertEquals("card chipW", 0f, card.chips.width, 0.01f)
    }

    // ------------------------------------------------- textWidth agreement

    @Test
    fun textWidthAlwaysAgreesWithTheTextFrame() {
        // The two share one formula: they can never disagree.
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.005f, -0.005f)) {
            val f = frames(p = p, textH = 50f)
            assertEquals(
                "textWidth at p = $p",
                f.text.width,
                ComposerGeometry.textWidth(366f, 34f, p, 1f),
                1e-6f,
            )
        }
    }

    @Test
    fun theTextWidthGlidesFromCapsuleToCard() {
        // Capsule: W − 52 − (16 + aw) = 264. Card: W − 32 = 334.
        assertEquals(264f, ComposerGeometry.textWidth(366f, 34f, 0f, 1f), 0.01f)
        assertEquals(299f, ComposerGeometry.textWidth(366f, 34f, 0.5f, 1f), 0.01f)
        assertEquals(334f, ComposerGeometry.textWidth(366f, 34f, 1f, 1f), 0.01f)
    }

    // ------------------------------------------------------ the radius

    @Test
    fun radiusDpLerpsAndClamps() {
        assertEquals(25f, ComposerGeometry.radiusDp(0f), 1e-6f)
        assertEquals(25.5f, ComposerGeometry.radiusDp(0.5f), 1e-6f)
        assertEquals(26f, ComposerGeometry.radiusDp(1f), 1e-6f)
        assertEquals(26f, ComposerGeometry.radiusDp(1.005f), 1e-6f)
        assertEquals(25f, ComposerGeometry.radiusDp(-0.005f), 1e-6f)
    }

    // ------------------------------------------------- the metrics table

    @Test
    fun theMetricsMatchTheComposerBarConstants() {
        assertEquals(34f, ComposerMetrics.Control, 1e-6f)
        assertEquals(25f, ComposerMetrics.CapsuleRadius, 1e-6f)
        assertEquals(26f, ComposerMetrics.CardRadius, 1e-6f)
        assertEquals(50f, ComposerMetrics.ToolbarHeight, 1e-6f)
        assertEquals(8f, ComposerMetrics.CapsuleEdge, 1e-6f)
        assertEquals(10f, ComposerMetrics.CardEdge, 1e-6f)
        assertEquals(52f, ComposerMetrics.CapsuleEdge + ComposerMetrics.Control +
            ComposerMetrics.CapsuleTextGapAfterAttach, 1e-6f)
        assertEquals(42f, ComposerMetrics.Control + ComposerMetrics.ChipGap, 1e-6f)
        assertEquals(16f, ComposerMetrics.CardTextInset, 1e-6f)
        assertEquals(12f, ComposerMetrics.ThumbsInset, 1e-6f)
        assertEquals(14f, ComposerMetrics.PlaceholderTop, 1e-6f)
        // The bottom gap is 8 in both states: lerp(8, 8, p).
        assertEquals(ComposerMetrics.CapsuleEdge, 8f, 1e-6f)
        assertTrue(ComposerMetrics.CapsuleTextGapBeforeAction == ComposerMetrics.ChipGap)
    }
}
