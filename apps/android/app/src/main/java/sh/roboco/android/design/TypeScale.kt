package sh.roboco.android.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * Dynamic Type for fixed-height UI: one factor per content size category
 * (`ios:Design/Palette.swift:110-118`). Rows stay fixed-height, so lists
 * still skip self-sizing — consumers scale font sizes **and** row geometry
 * by [factor] and round half units away from zero, exactly as
 * `TypeScale.size` / `(62 * TypeScale.factor).rounded()` do on iOS.
 *
 * - `scaled(pt)` — `TypeScale.size(pt)`: `roundHalfAway(pt * factor)`, the
 *   session-list rows and every other Dynamic-Type label;
 * - `fixed(pt)` — `Fonts.ui(face, pt)`: renders at exactly `pt` dp whatever
 *   the font scale (a UIKit point, not an sp);
 * - `bodyScaled(pt)` — `UIFontMetrics(.body).scaledValue(for: pt)`: the
 *   raw body ratio, unclamped and unrounded (composer text, "Working"
 *   label, Select Text sheet);
 * - [bodyFactor] is also what the transcript viewport receives
 *   (`TranscriptListView.swift:207-211` passes the unclamped ratio to Rust;
 *   ticket 61's `setViewport(textScale = bodyFactor)`).
 *
 * `fixed`/`scaled`/`bodyScaled` convert dp → sp through the density's own
 * converter (Android 14's non-linear `FontScaleConverter` via Compose), so
 * the pair roundtrips exactly: `fixed(pt).toDp() == pt.dp`.
 */
@Immutable
class TypeScale private constructor(
    /** The clamped factor: `min(1.6, max(0.85, body))`. */
    val factor: Float,
    /** The raw body ratio `17 sp / 17 dp`, unclamped (≈1.71 at Android's 2.0 cap). */
    val bodyFactor: Float,
    private val density: Density,
) {
    /** `pt` dp of text: a UIKit point, independent of the font scale. */
    fun fixed(pt: Float): TextUnit = with(density) { pt.dp.toSp() }

    /** `TypeScale.size(pt)`: `roundHalfAway(pt * factor)`. */
    fun scaled(pt: Float): TextUnit = fixed(roundHalfAway(pt * factor))

    /** `(pt * TypeScale.factor).rounded()` for row geometry. */
    fun scaledDp(pt: Float): Dp = roundHalfAway(pt * factor).dp

    /** `UIFontMetrics(.body).scaledValue(for: pt)`: `pt * bodyFactor`, unclamped, unrounded. */
    fun bodyScaled(pt: Float): TextUnit = fixed(pt * bodyFactor)

    /**
     * Equality over `(factor, bodyFactor, density)` — the fields the
     * conversions depend on — so `remember`/recomposition can skip when
     * nothing changed.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypeScale) return false
        return factor == other.factor &&
            bodyFactor == other.bodyFactor &&
            density.density == other.density.density &&
            density.fontScale == other.density.fontScale
    }

    override fun hashCode(): Int {
        var result = factor.hashCode()
        result = 31 * result + bodyFactor.hashCode()
        result = 31 * result + density.density.hashCode()
        result = 31 * result + density.fontScale.hashCode()
        return result
    }

    companion object {
        /** `TypeScale.factor`'s floor. */
        const val MIN = 0.85f

        /** `TypeScale.factor`'s ceiling. */
        const val MAX = 1.6f

        /**
         * Builds the scale for a density: `UIFontMetrics(forTextStyle: .body)
         * .scaledValue(for: 17) / 17`. Compose's `Density` carries the same
         * non-linear `FontScaleConverter` curve the platform uses (active
         * at `fontScale >= 1.03`, linear below), and the clamp is iOS's.
         * Only valid for the [Density] it was built from — never cache one
         * across densities.
         */
        fun from(density: Density): TypeScale {
            val body = with(density) { 17.sp.toDp().value } / 17f
            return TypeScale(body.coerceIn(MIN, MAX), body, density)
        }
    }
}

/**
 * Swift's `.rounded(.toNearestOrAwayFromZero)`: ties go away from zero.
 * **Not** `kotlin.math.round` (ties to even: `round(16.5f) == 16f`) and not
 * `roundToInt` (ties up: `roundToInt(-0.5f) == 0`). Use this everywhere a
 * Swift `.rounded()` / `TypeScale.size` is ported.
 */
fun roundHalfAway(x: Float): Float = sign(x) * floor(abs(x) + 0.5f)

/**
 * The resolved type scale. No default on purpose: a component rendered
 * outside [RobocoTheme] fails at once instead of drawing unscaled text.
 */
val LocalTypeScale = staticCompositionLocalOf<TypeScale> {
    error("LocalTypeScale not provided: wrap content in RobocoTheme")
}
