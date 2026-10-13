package sh.roboco.android.design.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Palette
import sh.roboco.android.design.SmoothRoundedShape

/**
 * The glass material tokens (ticket 07): every value pinned against the
 * ticket's Spec tables, and [glassFill] against its four tint-table results
 * (Porter-Duff source-over, `tint @0.18` over the fill).
 *
 * The expected values are independent literals from the ticket — the measured
 * light-mode iOS look (`1-off.png`) and android-app#14's dark values — never
 * recomputed through the implementation's own helpers.
 *
 * Colour alphas are compared within one 8-bit level (1/255): Compose packs
 * every sRGB [Color] as ARGB8888, so even `Color.White.copy(alpha = 0.72f)`
 * is stored as 184/255 = 0.72157 (CI r17), and [glassFill]'s source-over
 * result is re-quantised the same way. Float alphas are not representable for
 * sRGB colours, whichever helper builds them.
 */
class GlassTokensTest {

    // ------------------------------------------------------------- tokens

    @Test
    fun geometryAndSwitchTokensMatchTheSpec() {
        assertEquals(24.dp, Glass.BlurRadius)
        assertEquals(0f, Glass.NoiseFactor, 1e-6f)
        assertEquals(0.18f, Glass.TintMix, 1e-6f)
        assertEquals(1.06f, Glass.PressScale, 1e-6f)
        assertEquals(33, Glass.BlurMinSdk)
        assertEquals(2.dp, Glass.ShadowOffsetY)
        assertEquals(20.dp, Glass.ShadowRadius)
    }

    @Test
    fun pressSpringsMatchTheSpecPair() {
        // Ticket 07's owned pair: damping 0.7 / stiffness 600 (down), and
        // 0.55 / 380 (release). The stiffness is produced by IosSpring's
        // settle-matched conversion (no pasted literal), so it lands within
        // floating-point rounding of the spec pair.
        assertEquals(0.7f, Glass.PressDownSpring.dampingRatio, 1e-6f)
        assertEquals(600f, Glass.PressDownSpring.stiffness, 1f)

        assertEquals(0.55f, Glass.PressReleaseSpring.dampingRatio, 1e-6f)
        assertEquals(380f, Glass.PressReleaseSpring.stiffness, 1f)
    }

    @Test
    fun shapeHelperMirrorsGlassSurface() {
        // iOS Glass.surface(radius:) — nil radius is a capsule, otherwise
        // continuous corners of that radius (Glass.swift:14-21).
        assertSame(CapsuleShape, Glass.shape(null))
        assertEquals(SmoothRoundedShape(25.dp), Glass.shape(25.dp))
        assertEquals(SmoothRoundedShape(18.dp), Glass.shape(18.dp))
    }

    // ------------------------------------------------------------ colours

    private fun assertChannel(name: String, expected: Float, actual: Float, tolerance: Float = 1f / 255f) {
        assertEquals(name, expected, actual, tolerance)
    }

    private fun assertColor(name: String, color: Color, r: Int, g: Int, b: Int, alpha: Float) {
        assertChannel("$name alpha", alpha, color.alpha)
        assertChannel("$name red", r / 255f, color.red)
        assertChannel("$name green", g / 255f, color.green)
        assertChannel("$name blue", b / 255f, color.blue)
    }

    @Test
    fun lightColorsMatchTheSpecTable() {
        val c = Glass.Light
        assertColor("light fill", c.fill, 0xFF, 0xFF, 0xFF, 0.72f)
        assertColor("light fallback", c.fallback, 0xFF, 0xFF, 0xFF, 0.94f)
        assertColor("light rimTop", c.rimTop, 0xFF, 0xFF, 0xFF, 0.65f)
        assertColor("light rimBottom", c.rimBottom, 0xFF, 0xFF, 0xFF, 0.65f)
        assertColor("light shadow", c.shadow, 0x00, 0x00, 0x00, 0.08f)
        assertColor("light pressHighlight", c.pressHighlight, 0xFF, 0xFF, 0xFF, 0.12f)
    }

    @Test
    fun darkColorsMatchTheSpecTable() {
        val c = Glass.Dark
        assertColor("dark fill", c.fill, 0x1C, 0x1C, 0x1F, 0.66f)
        assertColor("dark fallback", c.fallback, 0x11, 0x11, 0x13, 0.94f)
        assertColor("dark rimTop", c.rimTop, 0xFF, 0xFF, 0xFF, 0.14f)
        assertColor("dark rimBottom", c.rimBottom, 0xFF, 0xFF, 0xFF, 0.04f)
        assertColor("dark shadow", c.shadow, 0x00, 0x00, 0x00, 0.45f)
        assertColor("dark pressHighlight", c.pressHighlight, 0xFF, 0xFF, 0xFF, 0.08f)
    }

    @Test
    fun colorsPickerFollowsAppearance() {
        assertSame(Glass.Light, Glass.colors(false))
        assertSame(Glass.Dark, Glass.colors(true))
    }

    // ----------------------------------------------------------- glassFill

    /** The ticket's tint table: (alpha, R, G, B) in sRGB levels. */
    private fun assertTint(result: Color, alpha: Double, r: Double, g: Double, b: Double) {
        assertChannel("tint alpha", alpha.toFloat(), result.alpha)
        assertChannel("tint red", (r / 255.0).toFloat(), result.red)
        assertChannel("tint green", (g / 255.0).toFloat(), result.green)
        assertChannel("tint blue", (b / 255.0).toFloat(), result.blue)
    }

    @Test
    fun glassFillCompositesTintOverLightFill() {
        val result = glassFill(Glass.Light.fill, Palette.Light.accent)
        assertTint(result, alpha = 0.7704, r = 216.68, g = 211.07, b = 249.63)
    }

    @Test
    fun glassFillCompositesTintOverDarkFill() {
        val result = glassFill(Glass.Dark.fill, Palette.Dark.accent)
        assertTint(result, alpha = 0.7212, r = 55.70, g = 51.96, b = 84.66)
    }

    @Test
    fun glassFillCompositesTintOverLightFallback() {
        val result = glassFill(Glass.Light.fallback, Palette.Light.accent)
        assertTint(result, alpha = 0.9508, r = 224.0, g = 219.0, b = 251.0)
    }

    @Test
    fun glassFillCompositesTintOverDarkFallback() {
        val result = glassFill(Glass.Dark.fallback, Palette.Dark.accent)
        assertTint(result, alpha = 0.9508, r = 40.0, g = 37.0, b = 62.0)
    }

    @Test
    fun glassFillWithoutTintReturnsTheBase() {
        // No iOS call site passes a tint today; the parameter exists for API
        // completeness, and null must be a no-op. Color is a value class, so
        // assertSame compared two separate boxes; equality is the contract.
        assertEquals(Glass.Light.fill, glassFill(Glass.Light.fill, null))
        assertEquals(Glass.Dark.fallback, glassFill(Glass.Dark.fallback, null))
    }

    @Test
    fun glassFillIsAPureSourceOverNotALerp() {
        // The result alpha is a1 + a2(1 − a1), the source-over rule, so it
        // must exceed both inputs' alphas — a plain RGB lerp would land
        // between them.
        val tinted = glassFill(Glass.Light.fill, Palette.Light.accent)
        assertTrue(tinted.alpha > Glass.Light.fill.alpha)
        assertTrue(tinted.alpha > Glass.TintMix)
    }
}
