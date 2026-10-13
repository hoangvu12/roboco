package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.Face

/**
 * The row's hand layout, pure (android-ui-parity #37):
 * [SessionRowGeometry.frames] against the ticket's geometry table at
 * `width = 411` — the x-height mark alignment (y = 13 Medium / 12 SemiBold
 * at k = 1, the deliberate iOS difference), the k = 0.85 / 1.6 scale
 * rows, the worked corner-word examples and the 140 dp cap, and the
 * never-negative clamps.
 *
 * Plain JUnit 4: dp in, dp out, no device.
 */
class SessionRowGeometryTest {

    private val medium = Face.SansMedium
    private val semibold = Face.SansSemibold

    // ------------------------------------------------- k = 1 (the default)

    @Test
    fun k1MediumPlacesTheMarkOnTheXHeightAtThirteen() {
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 52.4f, hasGlyph = true)
        assertEquals(62f, f.height, 0f)
        assertEquals(RectDp(20f, 13f, 20f, 20f), f.mark)
        assertEquals(RectDp(54f, 10f, 257f, 22f), f.title)
        assertEquals(RectDp(54f, 35f, 337f, 18f), f.metaSlot)
        assertEquals(RectDp(8f, 1f, 395f, 60f), f.highlight)
    }

    @Test
    fun k1SemiboldMarkSitsOneLowerTwelve() {
        // The bolder face's x-height; iOS has the difference too. Do not fix.
        val f = SessionRowGeometry.frames(411f, 1f, semibold, wordWidth = 52.4f, hasGlyph = true)
        assertEquals(12f, f.mark.y, 0f)
    }

    @Test
    fun k1TheGlyphCentresOnTheTitleLine() {
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 52.4f, hasGlyph = true)
        assertEquals(RectDp(321f, 15f, 12f, 12f), f.glyph)
        assertNull(SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 13.8f, hasGlyph = false).glyph)
    }

    // ------------------------------------------------- the scaled factors

    @Test
    fun k0Point85ScalesTheRows() {
        for (face in listOf(medium, semibold)) {
            val f = SessionRowGeometry.frames(411f, 0.85f, face, wordWidth = 52.4f, hasGlyph = true)
            assertEquals("$face height", 53f, f.height, 0f)
            assertEquals("$face titleY", 9f, f.title.y, 0f)
            assertEquals("$face titleH", 19f, f.title.h, 0f)
            assertEquals("$face metaY", 30f, f.metaSlot.y, 0f)
            assertEquals("$face metaH", 15f, f.metaSlot.h, 0f)
            assertEquals("$face mark y", 10f, f.mark.y, 0f)
        }
    }

    @Test
    fun k1Point6ScalesTheRowsToTheClampEnd() {
        for (face in listOf(medium, semibold)) {
            val f = SessionRowGeometry.frames(411f, 1.6f, face, wordWidth = 52.4f, hasGlyph = true)
            assertEquals("$face height", 99f, f.height, 0f)
            assertEquals("$face titleY", 16f, f.title.y, 0f)
            assertEquals("$face titleH", 35f, f.title.h, 0f)
            assertEquals("$face metaY", 56f, f.metaSlot.y, 0f)
            assertEquals("$face metaH", 29f, f.metaSlot.h, 0f)
            assertEquals("$face mark y", 26f, f.mark.y, 0f)
        }
        val f = SessionRowGeometry.frames(411f, 1.6f, medium, wordWidth = 52.4f, hasGlyph = true)
        assertEquals(27.5f, f.glyph!!.y, 0f)
    }

    @Test
    fun theFixedPartsNeverScale() {
        for (k in listOf(0.85f, 1f, 1.6f)) {
            val f = SessionRowGeometry.frames(411f, k, medium, wordWidth = 52.4f, hasGlyph = true)
            assertEquals("mark x", 20f, f.mark.x, 0f)
            assertEquals("mark side", 20f, f.mark.w, 0f)
            assertEquals("mark height", 20f, f.mark.h, 0f)
            assertEquals("title x", 54f, f.title.x, 0f)
            assertEquals("meta x", 54f, f.metaSlot.x, 0f)
            assertEquals("glyph side", 12f, f.glyph!!.w, 0f)
        }
        assertEquals(54f, SessionRowGeometry.TEXT_X, 0f)
    }

    // --------------------------------------------- the worked word widths

    @Test
    fun aWordOf52_4WithAGlyph() {
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 52.4f, hasGlyph = true)
        assertEquals(RectDp(338f, 10f, 53f, 22f), f.word)
        assertEquals(321f, f.glyph!!.x, 0f)
        assertEquals(257f, f.title.w, 0f)
    }

    @Test
    fun aWordOf13_8WithoutAGlyphExtendsTheTitle() {
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 13.8f, hasGlyph = false)
        assertEquals(RectDp(377f, 10f, 14f, 22f), f.word)
        assertNull(f.glyph)
        assertEquals(313f, f.title.w, 0f)
    }

    @Test
    fun theWordCapsAt140dp() {
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 500f, hasGlyph = true)
        assertEquals(140f, f.word.w, 0f)
        assertEquals(411f - 20f - 140f, f.word.x, 0f)
    }

    @Test
    fun aNarrowRowNeverGoesNegative() {
        val f = SessionRowGeometry.frames(60f, 1f, medium, wordWidth = 14f, hasGlyph = false)
        assertEquals(0f, f.title.w, 0f)
        assertEquals(0f, f.metaSlot.w, 0f)
        assertTrue(f.title.w >= 0f && f.metaSlot.w >= 0f)
        // The background rect still spans the row minus the insets.
        assertEquals(44f, f.highlight.w, 0f)
        assertEquals(60f, f.highlight.h, 0f)
    }
}
