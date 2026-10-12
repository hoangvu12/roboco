package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Type gallery section's linear density override (android-ui-parity #22):
 * with `LinearFontScaleDensity(1f, f)`, `TypeScale.from` reports exactly `f`
 * (the clamp is iOS's 0.85…1.6 and both ends are covered), `fixed` stays at
 * its dp size whatever the font scale, and `scaled` rounds half away from the
 * factor (14 / 17 / 26 at 16.5 pt).
 */
class TypeGalleryDensityTest {

    private val factors = listOf(0.85f, 1f, 1.6f)

    @Test
    fun linearDensityGivesTheExactFactors() {
        for (f in factors) {
            val d = LinearFontScaleDensity(1f, f)
            val ts = TypeScale.from(d)
            // The clamp returns exactly 0.85/1.6 at the ends and 1 stays 1.
            assertEquals("factor at $f", f, ts.factor, 1e-6f)
            assertEquals("bodyFactor at $f", f, ts.bodyFactor, 1e-4f)
        }
    }

    @Test
    fun fixedStaysAtItsDpSizeWhateverTheFontScale() {
        for (f in factors) {
            val d = LinearFontScaleDensity(1f, f)
            val ts = TypeScale.from(d)
            val size = with(d) { ts.fixed(17f).toDp() }
            assertEquals("fixed(17) at $f", 17f, size.value, 0.01f)
        }
    }

    @Test
    fun scaledRoundsHalfAwayFromTheFactor() {
        val expected = listOf(14f, 17f, 26f)
        for ((i, f) in factors.withIndex()) {
            val d = LinearFontScaleDensity(1f, f)
            val ts = TypeScale.from(d)
            val size = with(d) { ts.scaled(16.5f).toDp() }
            assertEquals("scaled(16.5) at $f", expected[i], size.value, 0.01f)
        }
    }
}
