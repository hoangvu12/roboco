package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.Motion

/**
 * [SearchMorphGeometry] — android-ui-parity #29's acceptance criteria:
 * the ticket's worked table for R = 360 at m = 0 (p = 0, 0.5, 1, ±0.01
 * dp), the RTL mirror (`fieldTarget` at row x 0, `returnTarget` at row
 * x 312), the `rowBottom` / `accessoryImeAlpha` / `accessoryBottomY`
 * tables, the content fades, the magnifier's size and centre, the field
 * content boxes — and the spring ([SearchMorph.Spring]: the settle-matched
 * stiffness, same numbers as 28's token but a separate constant).
 */
class SearchMorphGeometryTest {

    /** 26's row at a 402 dp window, zero insets: R = 360, pill 290, circle at x 298. */
    private val pill0 = BarRect(0f, 0f, 290f, 62f)
    private val search0 = BarRect(298f, 0f, 62f, 62f)

    private fun assertRect(what: String, r: BarRect, x: Float, y: Float, w: Float, h: Float) {
        assertEquals("$what x", x, r.x, 0.01f)
        assertEquals("$what y", y, r.y, 0.01f)
        assertEquals("$what w", w, r.w, 0.01f)
        assertEquals("$what h", h, r.h, 0.01f)
    }

    // --------------------------------------------------------- worked table

    @Test
    fun p0MatchesTheWorkedTable() {
        assertRect("return", SearchMorphGeometry.returnRect(pill0, 0f), 0f, 0f, 290f, 62f)
        assertRect("field", SearchMorphGeometry.fieldRect(search0, 360f, 0f), 298f, 0f, 62f, 62f)
    }

    @Test
    fun p05MatchesTheWorkedTable() {
        assertRect("return", SearchMorphGeometry.returnRect(pill0, 0.5f), 0f, 7f, 169f, 55f)
        assertRect("field", SearchMorphGeometry.fieldRect(search0, 360f, 0.5f), 177f, 7f, 183f, 55f)
    }

    @Test
    fun p1MatchesTheWorkedTable() {
        assertRect("return", SearchMorphGeometry.returnRect(pill0, 1f), 0f, 14f, 48f, 48f)
        assertRect("field", SearchMorphGeometry.fieldRect(search0, 360f, 1f), 56f, 14f, 304f, 48f)
        // The targets themselves, and the 8 dp gap between the parts.
        assertRect("returnTarget", SearchMorphGeometry.returnTarget(), 0f, 14f, 48f, 48f)
        assertRect("fieldTarget", SearchMorphGeometry.fieldTarget(360f), 56f, 14f, 304f, 48f)
        assertEquals(8f, SearchMorphGeometry.fieldTarget(360f).x - SearchMorphGeometry.returnTarget().w, 0.01f)
    }

    @Test
    fun aMinimizedPill0MorphsFromTheCircle() {
        // Entering Search from a minimized bar: pill0 at m = 1 is already
        // the 48 dp circle, so the morph starts from 28's minimized look.
        val minimized0 = BarRect(0f, 14f, 48f, 48f)
        assertRect("return at m=1 p=0", SearchMorphGeometry.returnRect(minimized0, 0f), 0f, 14f, 48f, 48f)
        val minimizedSearch0 = BarRect(312f, 14f, 48f, 48f)
        assertRect(
            "field at m=1 p=0.5",
            SearchMorphGeometry.fieldRect(minimizedSearch0, 360f, 0.5f),
            184f, 14f, 176f, 48f,
        )
    }

    @Test
    fun theOvershootNeverClampsTheGeometry() {
        // p = 1.006 (the spring's peak): narrower than 48, past the target.
        val w = SearchMorphGeometry.returnRect(pill0, 1.006f).w
        assertTrue("return width at the peak: $w", w < 48f && w > 46.4f)
        // p = −0.02 (an interrupted leave): past expanded.
        assertEquals(62f + 0.02f * 14f, SearchMorphGeometry.fieldRect(search0, 360f, -0.02f).h, 0.01f)
    }

    // --------------------------------------------------------------- RTL

    @Test
    fun theMirrorSwapsTheTwoParts() {
        val field = SearchMorphGeometry.mirror(SearchMorphGeometry.fieldTarget(360f), 360f, rtl = true)
        assertRect("rtl field", field, 0f, 14f, 304f, 48f)
        val ret = SearchMorphGeometry.mirror(SearchMorphGeometry.returnTarget(), 360f, rtl = true)
        assertRect("rtl return", ret, 312f, 14f, 48f, 48f)
        // LTR is the identity.
        assertRect(
            "ltr field",
            SearchMorphGeometry.mirror(SearchMorphGeometry.fieldTarget(360f), 360f, rtl = false),
            56f, 14f, 304f, 48f,
        )
    }

    // ------------------------------------------------------------- the IME

    @Test
    fun rowBottomTable() {
        assertEquals(24f, SearchMorphGeometry.rowBottom(24f, 0f, active = true), 0.01f)
        assertEquals(308f, SearchMorphGeometry.rowBottom(24f, 300f, active = true), 0.01f)
        assertEquals(24f, SearchMorphGeometry.rowBottom(24f, 300f, active = false), 0.01f)
    }

    @Test
    fun accessoryImeAlphaTable() {
        assertEquals(1f, accessoryImeAlpha(0f), 1e-4f)
        assertEquals(0.5f, accessoryImeAlpha(28f), 1e-4f)
        assertEquals(0f, accessoryImeAlpha(56f), 1e-4f)
        assertEquals(0f, accessoryImeAlpha(320f), 1e-4f)
    }

    @Test
    fun accessoryBottomTable() {
        assertEquals(-8f, SearchMorphGeometry.accessoryBottomY(0f), 1e-4f)
        assertEquals(6f, SearchMorphGeometry.accessoryBottomY(1f), 1e-4f)
        assertEquals(-1f, SearchMorphGeometry.accessoryBottomY(0.5f), 1e-4f)
    }

    // ----------------------------------------------------------- contents

    @Test
    fun contentFades() {
        assertEquals(0f, SearchMorphGeometry.contentAlpha(0.6f), 1e-4f)
        assertEquals(0.5f, SearchMorphGeometry.contentAlpha(0.8f), 1e-4f)
        assertEquals(1f, SearchMorphGeometry.contentAlpha(1f), 1e-4f)
        assertEquals(0f, SearchMorphGeometry.contentAlpha(0f), 1e-4f)
        assertEquals(1f, SearchMorphGeometry.contentAlpha(1.006f), 1e-4f)
    }

    @Test
    fun pillContentFades() {
        assertEquals(1f, SearchMorphGeometry.pillItemsAlpha(0f), 1e-4f)
        assertEquals(0.5f, SearchMorphGeometry.pillItemsAlpha(0.25f), 1e-4f)
        assertEquals(0f, SearchMorphGeometry.pillItemsAlpha(0.5f), 1e-4f)
        assertEquals(0f, SearchMorphGeometry.pillItemsAlpha(1f), 1e-4f)
    }

    @Test
    fun collapsedIconFades() {
        assertEquals(0f, SearchMorphGeometry.collapsedIconAlpha(0.5f), 1e-4f)
        assertEquals(0.5f, SearchMorphGeometry.collapsedIconAlpha(0.75f), 1e-4f)
        assertEquals(1f, SearchMorphGeometry.collapsedIconAlpha(1f), 1e-4f)
        assertEquals(0f, SearchMorphGeometry.collapsedIconAlpha(0f), 1e-4f)
    }

    @Test
    fun magnifierSizeAndCentre() {
        assertEquals(22f, SearchMorphGeometry.magnifierPt(0f), 1e-4f)
        assertEquals(19.5f, SearchMorphGeometry.magnifierPt(0.5f), 1e-4f)
        assertEquals(17f, SearchMorphGeometry.magnifierPt(1f), 1e-4f)
        // 22 pt is clamped at p = 1 too (the colour lerp's rule).
        assertEquals(17f, SearchMorphGeometry.magnifierPt(1.2f), 1e-4f)
        // Centred in the 62 dp circle at p = 0.
        assertEquals(31f, SearchMorphGeometry.magnifierCenterX(search0, 0f), 0.01f)
        // 14 + 20.4 / 2 = 24.2 at p = 1.
        assertEquals(24.2f, SearchMorphGeometry.magnifierCenterX(SearchMorphGeometry.fieldTarget(360f), 1f), 0.01f)
    }

    @Test
    fun fieldContentBoxes() {
        // No clear button: the text ends 14 before the field's edge → the
        // box is 304 − 40 − 14 = 250 wide at R = 360 (the placeholder's
        // 224.4 dp fits).
        assertEquals(14f, SearchMorphGeometry.textEnd(clearVisible = false), 0.01f)
        // With the clear button: 12 + 20.4 + 6 = 38.4.
        assertEquals(38.4f, SearchMorphGeometry.textEnd(clearVisible = true), 0.01f)
        // The clear touch box (44 wide) centred on the glyph, whose trailing
        // edge sits 12 from the field's edge: 304 − 12 − 10.2 − 22.
        assertEquals(259.8f, SearchMorphGeometry.clearTouchStart(304f), 0.01f)
    }

    @Test
    fun lerpIsComponentwise() {
        val a = BarRect(1f, 2f, 3f, 4f)
        val b = BarRect(5f, 6f, 7f, 8f)
        assertRect("lerp .25", SearchMorphGeometry.lerp(a, b, 0.25f), 2f, 3f, 4f, 5f)
        assertRect("lerp 1", SearchMorphGeometry.lerp(a, b, 1f), 5f, 6f, 7f, 8f)
    }

    // ------------------------------------------------------------- spring

    @Test
    fun theMorphSpringIsSettleMatched() {
        assertEquals("searchMorph", SearchMorph.Spring.name)
        assertEquals(0.40, SearchMorph.Spring.durationSec, 0.0)
        assertEquals(0.85f, SearchMorph.Spring.damping, 0f)
        // ω = ln(1000) / (ζ·T) = 20.317 → 412.78 (FOUNDATION-D1, the
        // ticket's reference value; same numbers as 28's TabBarMinimize).
        assertEquals(412.78, SearchMorph.Spring.stiffness.toDouble(), 0.01)
    }

    @Test
    fun theMorphSpringIsASeparateConstantFromMinimize() {
        // Same numbers (the same system animation tuned alike), a different
        // token: 28 forbids sharing spring constants across sites, and 04's
        // allSprings count is unaffected by this one living in SearchMorph.
        assertTrue(SearchMorph.Spring !== Motion.TabBarMinimize)
        assertEquals(Motion.TabBarMinimize.stiffness, SearchMorph.Spring.stiffness, 0f)
    }

    @Test
    fun theCopyIsVerbatim() {
        assertEquals("Sessions, projects, branches", SearchMorph.Placeholder)
        assertEquals("Close", SearchMorph.CloseDescription)
        assertEquals("Search", SearchMorph.FieldDescription)
        assertEquals("Clear text", SearchMorph.ClearDescription)
    }
}
