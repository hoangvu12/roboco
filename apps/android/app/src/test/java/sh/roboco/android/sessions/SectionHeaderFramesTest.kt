package sh.roboco.android.sessions

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.TypeScale
import sh.roboco.android.design.iconBoxDp

/**
 * The header row's hand layout, pure (android-ui-parity #39):
 * [sectionHeaderFrames] against the ticket's geometry table with
 * px = dp (density 1), plus the [TypeScale] rows that feed it — the row
 * height `R(40k)` and the two font sizes `TypeScale.size(13.5)`, the only
 * values that scale with k.
 *
 * Plain JUnit 4: px and dp in, px out, no device.
 */
class SectionHeaderFramesTest {

    /** [sectionHeaderFrames] with the dp constants pre-converted at density 1 (px = dp). */
    private fun frames(w: Int, h: Int, titleNatural: Int, countNatural: Int): SectionHeaderFrames =
        sectionHeaderFrames(
            widthPx = w,
            heightPx = h,
            titleNaturalPx = titleNatural,
            countNaturalPx = countNatural,
            px20 = 20,
            px6 = 6,
            px120 = 120,
            px7 = 7,
            px4 = 4,
            px36 = 36,
        )

    // -------------------------------------------------- k = 1 (the default)

    @Test
    fun k1TheRowIsFortyDp() {
        assertEquals(40f, TypeScale.from(Density(1f, 1f)).scaledDp(40f).value, 0f)
    }

    @Test
    fun k1TheGeometryTable() {
        // The ticket's acceptance table at width 402, k = 1: title frame
        // x = 20, y = 14, height 20; count 7 after the title's right edge;
        // the glyph 12 × 12 at (count right + 7, 18); chevron x = 366.
        val fr = frames(402, 40, 42, 7)
        assertEquals(14, fr.bandTop) // 40 − 20 − 6
        assertEquals(20, fr.titleLeft)
        assertEquals(42, fr.titleWidth) // natural, under the cap
        assertEquals(69, fr.countLeft) // 20 + 42 + 7
        assertEquals(83, fr.glyphLeft) // 69 + 7 + 7
        assertEquals(18, fr.glyphTop) // 14 + 4 = y + 20/2 − 6
        assertEquals(366, fr.chevronLeft) // 402 − 36
    }

    @Test
    fun k1AFittingTitleKeepsItsNaturalWidth() {
        // A fitting title keeps its natural width and the count follows.
        val fr = frames(402, 40, 200, 9)
        assertEquals(200, fr.titleWidth)
        assertEquals(227, fr.countLeft)
        assertEquals(243, fr.glyphLeft)
    }

    // ----------------------------------------------------- the title cap

    @Test
    fun theTitleCapsAtRowWidthMinus120() {
        val fr = frames(402, 40, 500, 7)
        assertEquals(282, fr.titleWidth) // 402 − 120
        assertEquals(309, fr.countLeft) // 20 + 282 + 7
        assertEquals(323, fr.glyphLeft)
    }

    @Test
    fun aNarrowRowClampsTheTitleToZeroNotNegative() {
        val fr = frames(100, 40, 42, 7)
        assertEquals(0, fr.titleWidth)
        assertEquals(27, fr.countLeft) // 20 + 0 + 7 — no guard iOS lacks
    }

    // -------------------------------------------------- the scaled factors

    @Test
    fun k1Point6TheBandDropsTo38AndTheRowTo64() {
        val fr = frames(402, 64, 42, 7)
        assertEquals(38, fr.bandTop) // 64 − 20 − 6
        assertEquals(42, fr.glyphTop) // 38 + 4
        assertEquals(366, fr.chevronLeft)
        // Linear density (ticket 22): a plain Density(1f, 1.6f) goes through
        // Android's non-linear font-scale table and lands on k ≈ 1.45.
        val ts = TypeScale.from(LinearFontScaleDensity(1f, 1.6f))
        assertEquals(64f, ts.scaledDp(40f).value, 0f)
    }

    @Test
    fun k0Point85TheBandRisesTo8AndTheRowTo34() {
        val fr = frames(402, 34, 42, 7)
        assertEquals(8, fr.bandTop) // 34 − 20 − 6
        assertEquals(12, fr.glyphTop)
        val ts = TypeScale.from(Density(1f, 0.85f))
        assertEquals(34f, ts.scaledDp(40f).value, 0f) // roundHalfAway(34.0)
    }

    @Test
    fun theFixedPartsNeverScale() {
        for (h in intArrayOf(34, 40, 64)) {
            val fr = frames(402, h, 42, 7)
            assertEquals("band height", 20, h - fr.bandTop - 6)
            assertEquals("title x", 20, fr.titleLeft)
            assertEquals("chevron x", 366, fr.chevronLeft)
            assertEquals("glyph offset", 4, fr.glyphTop - fr.bandTop)
            assertEquals("gap after title", 7, fr.countLeft - fr.titleLeft - fr.titleWidth)
            assertEquals("gap after count", 7, fr.glyphLeft - fr.countLeft - 7)
        }
    }

    // ------------------------------------------------- the scaled font sizes

    @Test
    fun theTitleAndCountSizeScaleToTheTypeScale() {
        // ts.scaled(13.5): 14 at k = 1, 11 at 0.85 (13.5 × 0.85 = 11.475,
        // rounded half away), 22 at 1.6 (21.6). The sp→dp roundtrip lands
        // on the rendered dp size. Linear density, so fontScale == k exactly.
        for ((fontScale, expected) in listOf(1f to 14f, 0.85f to 11f, 1.6f to 22f)) {
            val d = LinearFontScaleDensity(1f, fontScale)
            val ts = TypeScale.from(d)
            assertEquals("k = $fontScale title", expected, with(d) { ts.scaled(13.5f).toDp().value }, 1e-3f)
        }
    }

    @Test
    fun theChevronStaysElevenPointsAtEveryScale() {
        // A SF SymbolConfiguration point size never follows Dynamic Type:
        // the 11 pt glyph reserves a fixed 11 × 1.2 = 13.2 dp box.
        for (fontScale in listOf(0.85f, 1f, 1.6f)) {
            // 11f * 1.2f is 13.200001f in float arithmetic: compare with a delta.
            assertEquals("k = $fontScale chevron box", 13.2f, iconBoxDp(11f).value, 1e-4f)
        }
    }
}
