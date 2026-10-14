package sh.roboco.android.transcript.widgets

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme

/**
 * The shimmer goldens (recorded by `:app:recordRoborazziDebug`, verified by
 * `:app:verifyRoborazziDebug`): the [ShimmerSpecimen] fixture row through
 * the real layer-0 paint plus the frozen [ShimmerLayer], in light and dark
 * at the ticket's phases — the quiet start (t = 0), the k = 1 crossing
 * (0.25 / 1/3 / 0.4167) and the k = 0 crossing (0.8333). The window is
 * sized to the specimen (402 × 29.25 dp visible band on the phone's
 * 402 × 874 dp window) at xxhdpi — density 3, so the rounding pitfall (the
 * highlight glyphs exactly on the base glyphs) is what the t = 1/3 golden
 * pins. `reduced` is the no-shimmer look the t = 0 golden must equal.
 *
 * Two pixel tests complement the files: the t = 0 image is asserted
 * pixel-identical to the reduce-motion image, and the background right of
 * the title is the same flat colour at every phase (only the letters
 * change).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShimmerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun loadFonts() {
        Fonts.resetForTest()
        Fonts.load(context)
    }

    @After
    fun resetFonts() {
        Fonts.resetForTest()
    }

    private fun shot(dark: Boolean, phase: Float?, file: String) {
        captureRoboImage("transcript/$file") {
            RobocoTheme(if (dark) Appearance.Dark else Appearance.Light) {
                ShimmerSpecimen(dark, phase)
            }
        }
    }

    // ------------------------------------------------------------- goldens

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerT000Light() {
        shot(dark = false, phase = 0f, file = "shimmer_t000_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerT025Light() {
        shot(dark = false, phase = 0.25f, file = "shimmer_t025_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerT033Light() {
        shot(dark = false, phase = 1f / 3f, file = "shimmer_t033_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerT083Light() {
        shot(dark = false, phase = 0.8333f, file = "shimmer_t083_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerT033Dark() {
        shot(dark = true, phase = 1f / 3f, file = "shimmer_t033_dark.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun shimmerReducedLight() {
        shot(dark = false, phase = null, file = "shimmer_reduced_light.png")
    }

    // -------------------------------------------------- pixel behaviour

    /**
     * At t = 0 the mask is transparent everywhere, so the offscreen layer
     * composites nothing: the golden is pixel-identical to the
     * reduce-motion look (the sweep's quiet phase is indistinguishable
     * from no sweep — the seamless wrap's other end).
     */
    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun atT0TheGoldenIsPixelIdenticalToReduceMotion() {
        var phase by mutableStateOf<Float?>(0f)
        compose.setContent {
            RobocoTheme(Appearance.Light) { ShimmerSpecimen(dark = false, phase = phase) }
        }
        val atT0 = compose.onRoot().captureToImage().toPixelMap()
        compose.runOnUiThread { phase = null }
        compose.waitForIdle()
        val reduced = compose.onRoot().captureToImage().toPixelMap()
        // 29.25 dp × 3 = 87.75 px: the specimen's strip is rows 0..87.
        for (y in 0 until 88) {
            for (x in 0 until 1206) {
                assertEquals("row $y col $x", atT0[x, y], reduced[x, y])
            }
        }
    }

    /**
     * Only the letters brighten: the strip above the widget (rows 0–2, in
     * px — the widget starts at y 13.5 px) and the strip right of it (the
     * widget never extends past 49.5 + 330 = 379.5 dp = 1138.5 px) are the
     * flat transcript background at every phase.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun theBackgroundIsUnchangedAtEveryPhase() {
        var phase by mutableStateOf(0f)
        compose.setContent {
            RobocoTheme(Appearance.Light) { ShimmerSpecimen(dark = false, phase = phase) }
        }
        for (t in listOf(0f, 0.25f, 1f / 3f, 0.4167f, 0.8333f)) {
            compose.runOnUiThread { phase = t }
            compose.waitForIdle()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            for (y in intArrayOf(0, 2)) {
                for (x in 0..1200 step 25) {
                    assertPx("t = $t above the title at ($x, $y)", pixels[x, y], Palette.Light.background)
                }
            }
            for (y in intArrayOf(20, 44, 80)) {
                for (x in 1140..1200 step 6) {
                    assertPx("t = $t right of the title at ($x, $y)", pixels[x, y], Palette.Light.background)
                }
            }
        }
    }

    /** Per-channel colour assertion; [tolerance] is in 8-bit steps. */
    private fun assertPx(what: String, actual: Color, expected: Color, tolerance: Int = 2) {
        val t = tolerance / 255f
        assertTrue(
            "$what: rgb(${(actual.red * 255f).toInt()}, ${(actual.green * 255f).toInt()}, " +
                "${(actual.blue * 255f).toInt()}), expected $expected ± $tolerance",
            abs(actual.red - expected.red) <= t &&
                abs(actual.green - expected.green) <= t &&
                abs(actual.blue - expected.blue) <= t,
        )
    }
}
