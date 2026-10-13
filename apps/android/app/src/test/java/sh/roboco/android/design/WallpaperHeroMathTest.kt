package sh.roboco.android.design

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallpaper hero's pure maths (android-ui-parity #21): the 25-stop
 * smoothstep mask, the feather formula and its Skia radius conversion, the
 * alpha formula and the arrival truth table — every number pinned against
 * `ios:Design/Wallpaper.swift` (`:230-278`).
 *
 * Plain JUnit on purpose: the merge check runs it without Robolectric.
 * `WallpaperRender` needs an [ImageBitmap], so the truth table uses the
 * no-op [StubBitmap] below — `wallpaperArrival` only inspects nullity.
 */
class WallpaperHeroMathTest {

    // ------------------------------------------------------- mask stops

    @Test
    fun maskStopsHasTwentyFiveEntriesAtIOver24() {
        val stops = wallpaperMaskStops()
        assertEquals(25, stops.size)
        for (i in stops.indices) {
            assertEquals("position of stop $i", i / 24f, stops[i].first, 1e-6f)
        }
    }

    @Test
    fun maskAlphasMatchTheSmoothstepTable() {
        val stops = wallpaperMaskStops()
        // An sRGB Compose Color packs its alpha into 8 bits, so a stop's
        // alpha reads back as round(a·255)/255 (0.84375 → 215/255 =
        // 0.843137): the tolerance is half an 8-bit step, which still pins
        // each value to its own quantum.
        val q = 0.5f / 255f + 1e-6f
        assertEquals(1f, stops[0].second.alpha, q)
        assertEquals(0.84375f, stops[6].second.alpha, q)
        assertEquals(0.5f, stops[12].second.alpha, q)
        assertEquals(0.15625f, stops[18].second.alpha, q)
        assertEquals(0f, stops[24].second.alpha, q)
    }

    @Test
    fun maskAlphasAreNonIncreasing() {
        val stops = wallpaperMaskStops()
        for (i in 1 until stops.size) {
            assertTrue(
                "alpha rose between stops ${i - 1} and $i",
                stops[i].second.alpha <= stops[i - 1].second.alpha,
            )
        }
    }

    @Test
    fun maskStopsAreBlackWithVaryingAlphaOnly() {
        for (stop in wallpaperMaskStops()) {
            assertEquals(0f, stop.second.red, 1e-6f)
            assertEquals(0f, stop.second.green, 1e-6f)
            assertEquals(0f, stop.second.blue, 1e-6f)
        }
    }

    // --------------------------------------------------------- feather

    @Test
    fun featherClampsTo40And93Thirds() {
        // Wallpaper.swift:278: min(280, max(120, h × 0.52)) / 3.
        assertEquals(40f, cutoutFeatherDp(100f), 1e-6f)
        // 300 × 0.52 = 156; 156 / 3 = 52.
        assertEquals(52f, cutoutFeatherDp(300f), 1e-6f)
        // 760 × 0.52 = 395.2, clamped to 280; 280 / 3.
        assertEquals(93.333f, cutoutFeatherDp(760f), 1e-3f)
        // 230.77 × 0.52 ≈ 120: the lower clamp's edge.
        assertEquals(40f, cutoutFeatherDp(230.77f), 1e-3f)
    }

    @Test
    fun blurRadiusConvertsCgSigmaToTheSkiaRadius() {
        // σ = feather / 2 = 26 for feather 52; r = (26 − 0.5) / 0.57735.
        assertEquals(44.167f, blurMaskRadiusPx(52f), 1e-2f)
        // A sub-half-pixel σ clamps to Skia's 0.5 px minimum.
        assertEquals(0.5f, blurMaskRadiusPx(0f), 1e-6f)
    }

    // ------------------------------------------------- the alpha formula

    @Test
    fun alphaIsOpacityTimesAppearTimesClampedScrollFade() {
        assertEquals(0.8f, wallpaperAlpha(0.8f, 1f, 1.7f), 1e-6f)
        assertEquals(0f, wallpaperAlpha(0.8f, 1f, -0.3f), 1e-6f)
        assertEquals(0.2f, wallpaperAlpha(0.8f, 0.5f, 0.5f), 1e-6f)
    }

    // ------------------------------------------------- the arrival rule

    @Test
    fun arrivalFadesInOnlyForFirstOnScreenRenderWithoutReduceMotion() {
        val render = WallpaperRender(StubBitmap(), 1f)
        // Wallpaper.swift:230-240: first && on screen && !reduce.
        assertEquals(
            WallpaperArrival.FadeIn,
            wallpaperArrival(previous = null, next = render, onScreen = true, reduceMotion = false),
        )
        // All 15 other combinations snap.
        val previousValues = listOf<WallpaperRender?>(null, render)
        val nextValues = listOf<WallpaperRender?>(null, render)
        for (previous in previousValues) {
            for (next in nextValues) {
                for (onScreen in listOf(false, true)) {
                    for (reduceMotion in listOf(false, true)) {
                        val expected = previous == null && next != null && onScreen && !reduceMotion
                        if (expected) continue // the one FadeIn row, above
                        assertEquals(
                            "arrival($previous, $next, onScreen=$onScreen, reduce=$reduceMotion)",
                            WallpaperArrival.Snap,
                            wallpaperArrival(previous, next, onScreen, reduceMotion),
                        )
                    }
                }
            }
        }
    }

    /** A pure-Kotlin [ImageBitmap]: nullity is all the arrival rule reads. */
    private class StubBitmap : ImageBitmap {
        override val width: Int get() = 1
        override val height: Int get() = 1
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean get() = true
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb

        override fun prepareToDraw() {}

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) {
        }
    }
}
